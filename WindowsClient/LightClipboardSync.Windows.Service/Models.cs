using System.Text.Json.Serialization;

namespace LightClipboardSync.Windows.Service;

public sealed class ServiceConfig
{
    public string Endpoint { get; set; } = "http://localhost:5000";
    public string SharedId { get; set; } = Guid.NewGuid().ToString("D");
    public string ClientId { get; set; } = Guid.NewGuid().ToString("D");

    [JsonIgnore]
    public Uri? EventsUri => BuildUri("events");

    public Uri? BuildUri(string path)
    {
        if (!Uri.TryCreate(Endpoint.TrimEnd('/') + "/" + path.TrimStart('/'), UriKind.Absolute, out var uri))
            return null;
        return uri;
    }

    public bool TryValidate(out string error)
    {
        if (BuildUri("events") is not { Scheme: "http" or "https" } || BuildUri("push") is null)
        {
            error = "服务器地址必须是有效的 HTTP 或 HTTPS URL。";
            return false;
        }
        if (!Guid.TryParseExact(SharedId, "D", out _))
        {
            error = "共享 ID 必须是标准 UUID。";
            return false;
        }
        if (!Guid.TryParseExact(ClientId, "D", out _))
        {
            error = "设备 ID 必须是标准 UUID。";
            return false;
        }
        error = string.Empty;
        return true;
    }

    public ServiceConfig Clone() => new()
    {
        Endpoint = Endpoint,
        SharedId = SharedId,
        ClientId = ClientId
    };
}

public sealed record ClipboardPayload(string Type, string ContentType, byte[] Data)
{
    public string? Text => Type == "text" ? System.Text.Encoding.UTF8.GetString(Data) : null;
}

public sealed record ServiceStatus(bool Running, bool Connected, string? LastError, long? LastEventId,
    string? LastActivityUtc);

public sealed record PipeRequest(string Command, ServiceConfig? Config = null);
