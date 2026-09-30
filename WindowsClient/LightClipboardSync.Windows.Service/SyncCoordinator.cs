using System.IO;
using System.Net.Http.Headers;
using System.Net.Http;
using System.Text;
using System.Text.Json;

namespace LightClipboardSync.Windows.Service;

public sealed class SyncCoordinator : IAsyncDisposable
{
    private readonly ConfigStore _store;
    private readonly HttpClient _http = new() { Timeout = Timeout.InfiniteTimeSpan };
    private readonly ClipboardMonitor _clipboard;
    private readonly CancellationTokenSource _stop = new();
    private readonly SemaphoreSlim _pushGate = new(1, 1);
    private readonly object _statusGate = new();
    private CancellationTokenSource _reconnect = new();
    private string? _lastError;
    private long? _lastEventId;
    private DateTimeOffset? _lastActivity;
    private bool _connected;
    private string? _ignoredSignature;
    private DateTimeOffset _ignoreUntil;
    private int _ignoreNextClipboardChange;
    private Task? _eventsTask;

    public SyncCoordinator(ConfigStore store)
    {
        _store = store;
        _clipboard = new ClipboardMonitor(OnClipboardChanged);
        _store.Changed += (_, _) => RestartConnection();
        _http.DefaultRequestHeaders.UserAgent.ParseAdd("LightClipboardSync.Windows/1.0");
    }

    public ServiceStatus Status
    {
        get
        {
            lock (_statusGate)
                return new ServiceStatus(true, _connected, _lastError, _lastEventId,
                    _lastActivity?.ToUniversalTime().ToString("O"));
        }
    }

    public void Start()
    {
        _clipboard.Start();
        _eventsTask = Task.Run(() => EventsLoopAsync(_stop.Token));
        ServiceLog.Info("sync_coordinator_started");
    }

    public void IgnoreCurrentClipboardChange() => Interlocked.Exchange(ref _ignoreNextClipboardChange, 1);

    private void RestartConnection()
    {
        lock (_statusGate)
        {
            _lastError = null;
            _connected = false;
        }
        var old = Interlocked.Exchange(ref _reconnect, new CancellationTokenSource());
        old.Cancel();
        old.Dispose();
        ServiceLog.Info("events_reconnect_requested");
    }

    private async Task EventsLoopAsync(CancellationToken stopping)
    {
        while (!stopping.IsCancellationRequested)
        {
            var config = _store.Get();
            using var linked = CancellationTokenSource.CreateLinkedTokenSource(stopping, _reconnect.Token);
            if (!config.TryValidate(out _))
            {
                await DelayAsync(TimeSpan.FromSeconds(2), linked.Token);
                continue;
            }
            try
            {
                await ReadEventsAsync(config, linked.Token);
            }
            catch (OperationCanceledException) when (linked.IsCancellationRequested)
            {
                ServiceLog.Info("events_connection_cancelled");
            }
            catch (Exception ex)
            {
                lock (_statusGate) _lastError = ex.Message;
                ServiceLog.Error($"events_connection_error message={ex.Message}");
            }
            lock (_statusGate) _connected = false;
            ServiceLog.Info("events_disconnected");
            // Configuration changes cancel the old stream; reconnect promptly.
            await DelayAsync(TimeSpan.FromMilliseconds(100), stopping);
        }
    }

    private async Task ReadEventsAsync(ServiceConfig config, CancellationToken cancellationToken)
    {
        var uri = config.EventsUri!;
        ServiceLog.Info($"events_connecting endpoint={uri}");
        using var request = new HttpRequestMessage(HttpMethod.Get, uri);
        ApplyHeaders(request, config);
        using var response = await _http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        response.EnsureSuccessStatusCode();
        lock (_statusGate) { _lastError = null; _connected = true; }
        ServiceLog.Info($"events_connected status={(int)response.StatusCode}");
        await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken);
        using var reader = new StreamReader(stream, Encoding.UTF8);
        long? eventId = null;
        var data = new StringBuilder();
        while (!cancellationToken.IsCancellationRequested)
        {
            var line = await reader.ReadLineAsync(cancellationToken);
            if (line is null) break;
            if (line.Length == 0)
            {
                if (data.Length != 0)
                    await HandleEventAsync(config, eventId, data.ToString(), cancellationToken);
                eventId = null;
                data.Clear();
                continue;
            }
            if (line.StartsWith(':')) continue;
            if (line.StartsWith("id:", StringComparison.Ordinal))
            {
                if (long.TryParse(line[3..].Trim(), out var parsedId)) eventId = parsedId;
            }
            else if (line.StartsWith("data:", StringComparison.Ordinal))
            {
                if (data.Length != 0) data.Append('\n');
                data.Append(line[5..].TrimStart());
            }
        }
    }

    private async Task HandleEventAsync(ServiceConfig config, long? eventId, string json, CancellationToken cancellationToken)
    {
        var notification = JsonSerializer.Deserialize<ClipboardEvent>(json, new JsonSerializerOptions(JsonSerializerDefaults.Web));
        if (notification is null || notification.Type is not ("text" or "image")) return;
        var source = notification.Content is null ? "pull" : "inline";
        ServiceLog.Info($"events_received id={notification.Id} type={notification.Type} source={source}");
        var payload = notification.Content is not null
            ? new ClipboardPayload("text", "text/plain", Encoding.UTF8.GetBytes(notification.Content))
            : await DownloadAsync(config, eventId ?? notification.Id, notification.Type, cancellationToken);
        if (payload is null) return;
        _ignoredSignature = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(payload.Data));
        _ignoreUntil = DateTimeOffset.UtcNow.AddSeconds(5);
        Interlocked.Exchange(ref _ignoreNextClipboardChange, 1);
        await _clipboard.WriteAsync(payload);
        ServiceLog.Info($"clipboard_write_success id={notification.Id} type={payload.Type} bytes={payload.Data.Length}");
        lock (_statusGate)
        {
            _lastEventId = notification.Id;
            _lastActivity = DateTimeOffset.UtcNow;
        }
    }

    private async Task<ClipboardPayload?> DownloadAsync(ServiceConfig config, long id, string type, CancellationToken cancellationToken)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, config.BuildUri($"pull?id={id}")!);
        ApplyHeaders(request, config);
        using var response = await _http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        response.EnsureSuccessStatusCode();
        var data = await response.Content.ReadAsByteArrayAsync(cancellationToken);
        var contentType = response.Content.Headers.ContentType?.MediaType ?? (type == "image" ? "image/png" : "text/plain");
        ServiceLog.Info($"clipboard_download_success id={id} type={type} bytes={data.Length}");
        return new ClipboardPayload(type, contentType, data);
    }

    private void OnClipboardChanged(ClipboardPayload payload)
    {
        if (Interlocked.Exchange(ref _ignoreNextClipboardChange, 0) == 1)
        {
            ServiceLog.Info("clipboard_change_ignored reason=local_write_or_uuid_copy");
            return;
        }
        var signature = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(payload.Data));
        if (_ignoredSignature == signature && DateTimeOffset.UtcNow <= _ignoreUntil)
        {
            _ignoredSignature = null;
            ServiceLog.Info("clipboard_change_ignored reason=remote_write_match");
            return;
        }
        ServiceLog.Info($"clipboard_changed type={payload.Type} bytes={payload.Data.Length}");
        _ = PushAsync(payload, _stop.Token);
    }

    private async Task PushAsync(ClipboardPayload payload, CancellationToken cancellationToken)
    {
        var config = _store.Get();
        if (!config.TryValidate(out _)) return;
        await _pushGate.WaitAsync(cancellationToken);
        try
        {
            ServiceLog.Info($"push_started type={payload.Type} bytes={payload.Data.Length}");
            using var request = new HttpRequestMessage(HttpMethod.Post, config.BuildUri($"push?type={payload.Type}")!);
            ApplyHeaders(request, config);
            request.Content = new ByteArrayContent(payload.Data);
            request.Content.Headers.ContentType = new MediaTypeHeaderValue(payload.ContentType);
            if (payload.Type == "text") request.Content.Headers.ContentType.CharSet = "utf-8";
            using var response = await _http.SendAsync(request, cancellationToken);
            response.EnsureSuccessStatusCode();
            lock (_statusGate) _lastActivity = DateTimeOffset.UtcNow;
            ServiceLog.Info($"push_succeeded type={payload.Type} status={(int)response.StatusCode}");
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested) { }
        catch (Exception ex)
        {
            lock (_statusGate) _lastError = ex.Message;
            ServiceLog.Error($"push_failed message={ex.Message}");
        }
        finally { _pushGate.Release(); }
    }

    private static void ApplyHeaders(HttpRequestMessage request, ServiceConfig config)
    {
        request.Headers.Authorization = new AuthenticationHeaderValue("Basic",
            Convert.ToBase64String(Encoding.UTF8.GetBytes(config.SharedId + ":")));
        request.Headers.Add("X-Client-ID", config.ClientId);
    }

    private static async Task DelayAsync(TimeSpan delay, CancellationToken cancellationToken)
    {
        try { await Task.Delay(delay, cancellationToken); } catch (OperationCanceledException) { }
    }

    public async ValueTask DisposeAsync()
    {
        _stop.Cancel();
        _reconnect.Cancel();
        if (_eventsTask is not null) await _eventsTask;
        _clipboard.Dispose();
        _http.Dispose();
        _pushGate.Dispose();
        _stop.Dispose();
        _reconnect.Dispose();
        ServiceLog.Info("sync_coordinator_stopped");
    }
}

public sealed record ClipboardEvent(long Id, string Type, string? Content, long Timestamp);
