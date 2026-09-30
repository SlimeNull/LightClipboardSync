using System.IO;
using System.IO.Pipes;
using System.Text;
using System.Text.Json;

namespace LightClipboardSync.Windows.Service;

public sealed class PipeServer : IAsyncDisposable
{
    public const string Name = "LightClipboardSync";
    private readonly ConfigStore _store;
    private readonly SyncCoordinator _sync;
    private readonly Action _shutdown;
    private readonly CancellationTokenSource _stop = new();
    private readonly JsonSerializerOptions _json = new(JsonSerializerDefaults.Web);
    private Task? _loop;

    public PipeServer(ConfigStore store, SyncCoordinator sync, Action shutdown)
    {
        _store = store;
        _sync = sync;
        _shutdown = shutdown;
    }
    public void Start()
    {
        _loop = Task.Run(ListenAsync);
        ServiceLog.Info($"pipe_server_started name={Name}");
    }

    private async Task ListenAsync()
    {
        while (!_stop.IsCancellationRequested)
        {
            await using var pipe = new NamedPipeServerStream(Name, PipeDirection.InOut, 1,
                PipeTransmissionMode.Byte, PipeOptions.Asynchronous);
            try { await pipe.WaitForConnectionAsync(_stop.Token); } catch (OperationCanceledException) { break; }
            try
            {
                using var reader = new StreamReader(pipe, Encoding.UTF8, leaveOpen: true);
                await using var writer = new StreamWriter(pipe, new UTF8Encoding(false), leaveOpen: true) { AutoFlush = true };
                var line = await reader.ReadLineAsync(_stop.Token);
                var response = Handle(line);
                await writer.WriteLineAsync(JsonSerializer.Serialize(response, _json));
            }
            catch (Exception ex) { ServiceLog.Error($"pipe_error message={ex.Message}"); }
        }
    }

    private object Handle(string? line)
    {
        try
        {
            var request = JsonSerializer.Deserialize<PipeRequest>(line ?? "", _json);
            if (request?.Command == "get-config") return new { ok = true, config = _store.Get() };
            if (request?.Command == "get-status") return new { ok = true, status = _sync.Status };
            if (request?.Command == "ignore-clipboard")
            {
                _sync.IgnoreCurrentClipboardChange();
                return new { ok = true };
            }
            if (request?.Command == "shutdown")
            {
                ServiceLog.Info("shutdown_requested_by_configuration_app");
                _shutdown();
                return new { ok = true };
            }
            if (request?.Command == "set-config" && request.Config is not null)
            {
                if (_store.TryUpdate(request.Config, out var validationError))
                    return new { ok = true, config = _store.Get() };
                return new { ok = false, error = validationError };
            }
            return new { ok = false, error = "未知命令。" };
        }
        catch (Exception ex)
        {
            ServiceLog.Error($"pipe_request_error message={ex.Message}");
            return new { ok = false, error = ex.Message };
        }
    }

    public async ValueTask DisposeAsync()
    {
        _stop.Cancel();
        if (_loop is not null) { try { await _loop; } catch (OperationCanceledException) { } }
        _stop.Dispose();
    }
}
