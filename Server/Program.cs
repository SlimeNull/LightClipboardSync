using System.Buffers;
using System.Text;
using System.Text.Json;
using LightClipboardSync.Server;
using Microsoft.Net.Http.Headers;

const int maxContentBytes = 25 * 1024 * 1024;
const int inlineTextBytes = 64 * 1024;
var strictUtf8 = new UTF8Encoding(false, true);
var jsonOptions = new JsonSerializerOptions(JsonSerializerDefaults.Web);

var builder = WebApplication.CreateBuilder(args);
builder.Services.AddSingleton<ClipboardStore>();
var app = builder.Build();

app.MapPost("/push", async (HttpContext context, ClipboardStore store) =>
{
    if (!BasicAuthentication.TryGetUser(context.Request.Headers.Authorization.ToString(), out var user))
        return Results.Unauthorized();
    if (!TryGetClientId(context, out var clientId))
        return Results.BadRequest("Invalid X-Client-ID.");

    var type = context.Request.Query["type"].ToString();
    if (type is not ("text" or "image"))
        return Results.BadRequest("type must be text or image.");
    if (!MediaTypeHeaderValue.TryParse(context.Request.ContentType, out var mediaType))
        return Results.BadRequest("A valid Content-Type is required.");

    var contentType = mediaType.MediaType.Value?.ToLowerInvariant();
    if (type == "text" && (contentType != "text/plain" ||
        (!string.IsNullOrEmpty(mediaType.Charset.Value) &&
         !mediaType.Charset.Value.Equals("utf-8", StringComparison.OrdinalIgnoreCase))))
        return Results.BadRequest("Text must use text/plain with UTF-8 encoding.");
    if (type == "image" && contentType is not ("image/png" or "image/jpeg" or "image/webp"))
        return Results.BadRequest("Images must use image/png, image/jpeg, or image/webp.");

    if (context.Request.ContentLength > maxContentBytes)
        return Results.StatusCode(StatusCodes.Status413PayloadTooLarge);

    using var buffer = new MemoryStream();
    var rented = ArrayPool<byte>.Shared.Rent(64 * 1024);
    try
    {
        int count;
        while ((count = await context.Request.Body.ReadAsync(rented, context.RequestAborted)) != 0)
        {
            if (buffer.Length + count > maxContentBytes)
                return Results.StatusCode(StatusCodes.Status413PayloadTooLarge);
            buffer.Write(rented, 0, count);
        }
    }
    finally
    {
        ArrayPool<byte>.Shared.Return(rented);
    }

    var data = buffer.ToArray();
    string? text = null;
    if (type == "text")
    {
        try
        {
            text = strictUtf8.GetString(data);
        }
        catch (DecoderFallbackException)
        {
            return Results.BadRequest("Text body must be valid UTF-8.");
        }
    }

    var inlineContent = type == "text" && data.Length < inlineTextBytes ? text : null;
    var notification = store.Push(user, type, contentType!, data, inlineContent, clientId);
    return Results.Json(notification, jsonOptions, statusCode: StatusCodes.Status201Created);
});

app.MapGet("/pull", (HttpContext context, ClipboardStore store) =>
{
    if (!BasicAuthentication.TryGetUser(context.Request.Headers.Authorization.ToString(), out var user))
        return Results.Unauthorized();
    if (!long.TryParse(context.Request.Query["id"], out var id) || id <= 0)
        return Results.BadRequest("A positive numeric id is required.");

    var entry = store.Find(user, id);
    return entry is null ? Results.NotFound() : Results.File(entry.Data, entry.ContentType);
});

app.MapGet("/events", async (HttpContext context, ClipboardStore store) =>
{
    if (!BasicAuthentication.TryGetUser(context.Request.Headers.Authorization.ToString(), out var user))
    {
        context.Response.StatusCode = StatusCodes.Status401Unauthorized;
        return;
    }
    if (!TryGetClientId(context, out var clientId))
    {
        context.Response.StatusCode = StatusCodes.Status400BadRequest;
        return;
    }

    using var subscription = store.Subscribe(user, clientId);
    context.Response.ContentType = "text/event-stream; charset=utf-8";
    context.Response.Headers.CacheControl = "no-cache";
    context.Response.Headers["X-Accel-Buffering"] = "no";
    await context.Response.StartAsync(context.RequestAborted);

    try
    {
        while (!context.RequestAborted.IsCancellationRequested)
        {
            var waitTask = subscription.Reader.WaitToReadAsync(context.RequestAborted).AsTask();
            while (!waitTask.IsCompleted)
            {
                var completed = await Task.WhenAny(waitTask, Task.Delay(TimeSpan.FromSeconds(15), context.RequestAborted));
                if (completed == waitTask)
                    break;
                await context.Response.WriteAsync(": keep-alive\n\n", context.RequestAborted);
                await context.Response.Body.FlushAsync(context.RequestAborted);
            }
            if (!await waitTask)
                break;
            while (subscription.Reader.TryRead(out var notification))
            {
                var json = JsonSerializer.Serialize(notification, jsonOptions);
                await context.Response.WriteAsync($"id: {notification.Id}\ndata: {json}\n\n", context.RequestAborted);
                await context.Response.Body.FlushAsync(context.RequestAborted);
            }
        }
    }
    catch (OperationCanceledException) when (context.RequestAborted.IsCancellationRequested)
    {
    }
    catch (IOException) when (context.RequestAborted.IsCancellationRequested)
    {
    }
});

app.Run();

static bool TryGetClientId(HttpContext context, out Guid? clientId)
{
    clientId = null;
    var value = context.Request.Headers["X-Client-ID"].ToString();
    if (value.Length == 0)
        return true;
    if (!Guid.TryParseExact(value, "D", out var parsed))
        return false;
    clientId = parsed;
    return true;
}
