using System.Buffers;
using System.Diagnostics;
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
var logger = app.Services.GetRequiredService<ILoggerFactory>()
    .CreateLogger("LightClipboardSync.Server.Http");

app.Use(async (context, next) =>
{
    var started = Stopwatch.GetTimestamp();
    try
    {
        await next(context);
    }
    finally
    {
        var elapsed = Stopwatch.GetElapsedTime(started);
        logger.LogInformation(
            "http_request method={Method} path={Path} status={StatusCode} elapsed_ms={ElapsedMs} content_length={ContentLength}",
            context.Request.Method,
            context.Request.Path.Value,
            context.Response.StatusCode,
            elapsed.TotalMilliseconds,
            context.Request.ContentLength);
    }
});

app.MapPost("/push", async (HttpContext context, ClipboardStore store) =>
{
    if (!BasicAuthentication.TryGetUser(context.Request.Headers.Authorization.ToString(), out var user,
            out var authFailure))
    {
        logger.LogWarning("auth_failed path=/push reason={Reason}", authFailure);
        return Results.Unauthorized();
    }
    if (!TryGetClientId(context, out var clientId))
    {
        logger.LogWarning("invalid_client_id path=/push user={User}", user);
        return Results.BadRequest("Invalid X-Client-ID.");
    }

    var type = context.Request.Query["type"].ToString();
    if (type is not ("text" or "image"))
    {
        logger.LogWarning("invalid_push_type user={User} type={Type}", user, type);
        return Results.BadRequest("type must be text or image.");
    }
    if (!MediaTypeHeaderValue.TryParse(context.Request.ContentType, out var mediaType))
    {
        logger.LogWarning("invalid_content_type path=/push user={User} type={Type}", user, type);
        return Results.BadRequest("A valid Content-Type is required.");
    }

    var contentType = mediaType.MediaType.Value?.ToLowerInvariant();
    if (type == "text" && (contentType != "text/plain" ||
        (!string.IsNullOrEmpty(mediaType.Charset.Value) &&
         !mediaType.Charset.Value.Equals("utf-8", StringComparison.OrdinalIgnoreCase))))
    {
        logger.LogWarning("invalid_content_type path=/push user={User} type=text content_type={ContentType}",
            user, contentType);
        return Results.BadRequest("Text must use text/plain with UTF-8 encoding.");
    }
    if (type == "image" && contentType is not ("image/png" or "image/jpeg" or "image/webp"))
    {
        logger.LogWarning("invalid_content_type path=/push user={User} type=image content_type={ContentType}",
            user, contentType);
        return Results.BadRequest("Images must use image/png, image/jpeg, or image/webp.");
    }

    if (context.Request.ContentLength > maxContentBytes)
    {
        logger.LogWarning("payload_too_large path=/push user={User} type={Type} content_length={ContentLength}",
            user, type, context.Request.ContentLength);
        return Results.StatusCode(StatusCodes.Status413PayloadTooLarge);
    }

    using var buffer = new MemoryStream();
    var rented = ArrayPool<byte>.Shared.Rent(64 * 1024);
    try
    {
        int count;
        while ((count = await context.Request.Body.ReadAsync(rented, context.RequestAborted)) != 0)
        {
            if (buffer.Length + count > maxContentBytes)
            {
                logger.LogWarning("payload_too_large path=/push user={User} type={Type}", user, type);
                return Results.StatusCode(StatusCodes.Status413PayloadTooLarge);
            }
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
            logger.LogWarning("invalid_utf8 path=/push user={User}", user);
            return Results.BadRequest("Text body must be valid UTF-8.");
        }
    }

    var inlineContent = type == "text" && data.Length < inlineTextBytes ? text : null;
    var notification = store.Push(user, type, contentType!, data, inlineContent, clientId);
    logger.LogInformation("clipboard_push user={User} client={Client} id={Id} type={Type} bytes={Bytes}",
        user, clientId, notification.Id, type, data.Length);
    return Results.Json(notification, jsonOptions, statusCode: StatusCodes.Status201Created);
});

app.MapGet("/pull", (HttpContext context, ClipboardStore store) =>
{
    if (!BasicAuthentication.TryGetUser(context.Request.Headers.Authorization.ToString(), out var user,
            out var authFailure))
    {
        logger.LogWarning("auth_failed path=/pull reason={Reason}", authFailure);
        return Results.Unauthorized();
    }
    if (!long.TryParse(context.Request.Query["id"], out var id) || (id <= 0 && id != -1))
    {
        logger.LogWarning("invalid_pull_id user={User} id={Id}", user, context.Request.Query["id"].ToString());
        return Results.BadRequest("id must be positive or -1 for the latest clipboard.");
    }
    if (!TryGetClientId(context, out var clientId))
    {
        logger.LogWarning("invalid_client_id path=/pull user={User}", user);
        return Results.BadRequest("Invalid X-Client-ID.");
    }

    var entry = store.Find(user, id, clientId);
    if (entry is null)
    {
        logger.LogInformation("clipboard_pull_miss user={User} client={Client} requested_id={Id}", user, clientId, id);
        return Results.NotFound();
    }
    context.Response.Headers["X-Clipboard-ID"] = entry.Id.ToString(System.Globalization.CultureInfo.InvariantCulture);
    context.Response.Headers["X-Clipboard-Timestamp"] = entry.Timestamp.ToString(System.Globalization.CultureInfo.InvariantCulture);
    context.Response.Headers["X-Clipboard-Type"] = entry.Type;
    context.Response.Headers.CacheControl = "no-store";
    logger.LogInformation("clipboard_pull_hit user={User} client={Client} requested_id={RequestedId} id={Id} type={Type} bytes={Bytes}",
        user, clientId, id, entry.Id, entry.Type, entry.Data.Length);
    return Results.File(entry.Data, entry.ContentType);
});

app.MapGet("/events", async (HttpContext context, ClipboardStore store) =>
{
    if (!BasicAuthentication.TryGetUser(context.Request.Headers.Authorization.ToString(), out var user,
            out var authFailure))
    {
        logger.LogWarning("auth_failed path=/events reason={Reason}", authFailure);
        context.Response.StatusCode = StatusCodes.Status401Unauthorized;
        return;
    }
    if (!TryGetClientId(context, out var clientId))
    {
        logger.LogWarning("invalid_client_id path=/events user={User}", user);
        context.Response.StatusCode = StatusCodes.Status400BadRequest;
        return;
    }

    using var subscription = store.Subscribe(user, clientId);
    logger.LogInformation("events_connected user={User} client={Client}", user, clientId);
    context.Response.ContentType = "text/event-stream; charset=utf-8";
    context.Response.Headers.CacheControl = "no-cache";
    context.Response.Headers["X-Accel-Buffering"] = "no";
    await context.Response.StartAsync(context.RequestAborted);
    await context.Response.WriteAsync(": connected\n\n", context.RequestAborted);
    await context.Response.Body.FlushAsync(context.RequestAborted);

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
    finally
    {
        logger.LogInformation("events_disconnected user={User} client={Client}", user, clientId);
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
