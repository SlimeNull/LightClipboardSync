using System.Runtime.Versioning;

namespace LightClipboardSync.Windows.Service;

internal static class Program
{
    [STAThread]
    [SupportedOSPlatform("windows")]
    private static async Task Main()
    {
        Console.Title = "LightClipboardSync Windows Service";
        var store = new ConfigStore();
        var quit = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        await using var sync = new SyncCoordinator(store);
        await using var pipe = new PipeServer(store, sync, () => quit.TrySetResult());
        sync.Start();
        pipe.Start();
        ServiceLog.Info("service_started");
        ServiceLog.Info($"pipe_ready name={PipeServer.Name}");
        Console.CancelKeyPress += (_, e) => { e.Cancel = true; quit.TrySetResult(); };
        AppDomain.CurrentDomain.ProcessExit += (_, _) => quit.TrySetResult();
        await quit.Task;
        ServiceLog.Info("service_stopping");
    }
}
