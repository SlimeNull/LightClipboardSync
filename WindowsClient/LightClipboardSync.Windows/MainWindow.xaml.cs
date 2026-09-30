using Microsoft.Win32;
using System.Diagnostics;
using System.IO;
using System.IO.Pipes;
using System.Text;
using System.Text.Json;
using System.Windows;
using System.Windows.Media;
using System.Windows.Threading;

namespace LightClipboardSync.Windows;

public partial class MainWindow : Window
{
    private const string PipeName = "LightClipboardSync";
    private const string RunKeyPath = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private readonly JsonSerializerOptions _json = new(JsonSerializerDefaults.Web);
    private readonly DispatcherTimer _statusTimer = new() { Interval = TimeSpan.FromMilliseconds(500) };
    private string _clientId = Guid.NewGuid().ToString("D");
    private bool _loading;

    public MainWindow()
    {
        InitializeComponent();
        Loaded += async (_, _) => await LoadAsync();
        Closed += (_, _) => _statusTimer.Stop();
        _statusTimer.Tick += async (_, _) => await RefreshConnectionStatusAsync();
    }

    private async Task LoadAsync()
    {
        _loading = true;
        SetStatus(false);
        var response = await SendAsync(new { command = "get-config" });
        if (response is null)
        {
            StartService();
            for (var i = 0; i < 15 && response is null; i++)
            {
                await Task.Delay(250);
                response = await SendAsync(new { command = "get-config" });
            }
        }
        if (response?.RootElement.TryGetProperty("config", out var config) == true)
        {
            var loaded = config.Deserialize<ServiceConfigDto>(_json);
            if (loaded is not null)
            {
                EndpointBox.Text = loaded.Endpoint;
                SharedIdBox.Text = loaded.SharedId;
                if (Guid.TryParse(loaded.ClientId, out var clientId)) _clientId = clientId.ToString("D");
            }
        }
        else
        {
            EndpointBox.Text = "http://localhost:5000";
            SharedIdBox.Text = Guid.NewGuid().ToString("D");
        }
        AutostartBox.IsChecked = IsAutostartEnabled();
        _loading = false;
        _statusTimer.Start();
        await WaitForConnectionAsync();
    }

    private async void Save_Click(object sender, RoutedEventArgs e)
    {
        if (!TryReadSettings(out var endpoint, out var sharedId)) return;
        SetStatus(false);
        var response = await SendAsync(new
        {
            command = "set-config",
            // ClientId is an internal routing identifier; it is never shown in the UI.
            config = new { endpoint, sharedId, clientId = _clientId }
        });
        if (response?.RootElement.TryGetProperty("ok", out var ok) != true || !ok.GetBoolean())
        {
            MessageBox.Show(GetError(response), "保存设置失败", MessageBoxButton.OK, MessageBoxImage.Error);
            return;
        }
        await WaitForConnectionAsync();
    }

    private async void Exit_Click(object sender, RoutedEventArgs e)
    {
        await SendAsync(new { command = "shutdown" });
        Application.Current.Shutdown();
    }

    private async void CopyId_Click(object sender, RoutedEventArgs e)
    {
        await SendAsync(new { command = "ignore-clipboard" });
        Clipboard.SetText(SharedIdBox.Text);
    }

    private void RegenerateId_Click(object sender, RoutedEventArgs e)
    {
        SharedIdBox.Text = Guid.NewGuid().ToString("D");
    }

    private void AutostartBox_Changed(object sender, RoutedEventArgs e)
    {
        if (_loading) return;
        SetAutostart(AutostartBox.IsChecked == true);
    }

    private bool TryReadSettings(out string endpoint, out string sharedId)
    {
        endpoint = EndpointBox.Text.Trim().TrimEnd('/');
        sharedId = SharedIdBox.Text.Trim();
        if (!Uri.TryCreate(endpoint, UriKind.Absolute, out var uri) || uri.Scheme is not ("http" or "https") ||
            string.IsNullOrEmpty(uri.Host) || uri.UserInfo.Length != 0 || !string.IsNullOrEmpty(uri.Query) ||
            !string.IsNullOrEmpty(uri.Fragment))
        {
            MessageBox.Show("请输入有效的 HTTP(S) 服务器地址。", "设置无效", MessageBoxButton.OK, MessageBoxImage.Warning);
            return false;
        }
        if (!Guid.TryParse(sharedId, out var parsed))
        {
            MessageBox.Show("请输入有效的同步 UUID。", "设置无效", MessageBoxButton.OK, MessageBoxImage.Warning);
            return false;
        }
        endpoint = uri.ToString().TrimEnd('/');
        sharedId = parsed.ToString("D");
        EndpointBox.Text = endpoint;
        SharedIdBox.Text = sharedId;
        return true;
    }

    private async Task WaitForConnectionAsync()
    {
        SetStatus(false);
        for (var i = 0; i < 20; i++)
        {
            var response = await SendAsync(new { command = "get-status" });
            if (response?.RootElement.TryGetProperty("status", out var status) == true &&
                status.TryGetProperty("connected", out var connected) && connected.GetBoolean())
            {
                SetStatus(true);
                return;
            }
            await Task.Delay(150);
        }
        SetStatus(false);
    }

    private async Task RefreshConnectionStatusAsync()
    {
        var response = await SendAsync(new { command = "get-status" });
        var connected = response?.RootElement.TryGetProperty("status", out var status) == true &&
                        status.TryGetProperty("connected", out var value) && value.GetBoolean();
        SetStatus(connected);
    }

    private void SetStatus(bool connected)
    {
        StatusText.Text = connected ? "已连接" : "正在连接";
        StatusDot.Fill = connected ? new SolidColorBrush(Color.FromRgb(34, 163, 112))
                                   : new SolidColorBrush(Color.FromRgb(245, 158, 11));
    }

    private static async Task<JsonDocument?> SendAsync(object request)
    {
        try
        {
            await using var pipe = new NamedPipeClientStream(".", PipeName, PipeDirection.InOut, PipeOptions.Asynchronous);
            await pipe.ConnectAsync(700);
            await using var writer = new StreamWriter(pipe, new UTF8Encoding(false), leaveOpen: true) { AutoFlush = true };
            using var reader = new StreamReader(pipe, Encoding.UTF8, leaveOpen: true);
            await writer.WriteLineAsync(JsonSerializer.Serialize(request, new JsonSerializerOptions(JsonSerializerDefaults.Web)));
            var line = await reader.ReadLineAsync();
            return line is null ? null : JsonDocument.Parse(line);
        }
        catch { return null; }
    }

    private static string GetError(JsonDocument? response) =>
        response?.RootElement.TryGetProperty("error", out var error) == true
            ? error.GetString() ?? "未知错误" : "后台服务未连接";

    private static bool IsAutostartEnabled()
    {
        using var key = Registry.CurrentUser.OpenSubKey(RunKeyPath, false);
        var value = key?.GetValue("LightClipboardSync") as string;
        return string.Equals(value?.Trim().Trim('"'), ServiceExecutablePath(), StringComparison.OrdinalIgnoreCase);
    }

    private static void SetAutostart(bool enabled)
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKeyPath, true) ??
                            Registry.CurrentUser.CreateSubKey(RunKeyPath);
            if (key is null) return;
            if (enabled) key.SetValue("LightClipboardSync", $"\"{ServiceExecutablePath()}\"");
            else key.DeleteValue("LightClipboardSync", false);
        }
        catch (Exception ex)
        {
            MessageBox.Show($"无法修改开机自启动设置：{ex.Message}", "设置失败", MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private static string ServiceExecutablePath()
    {
        var serviceName = "LightClipboardSync.Windows.Service.exe";
        var candidates = new[]
        {
            Path.Combine(AppContext.BaseDirectory, serviceName),
            Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "LightClipboardSync.Windows.Service", "bin", "Release", "net10.0-windows", serviceName),
            Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "LightClipboardSync.Windows.Service", "bin", "Debug", "net10.0-windows", serviceName)
        };
        return candidates.Select(Path.GetFullPath).FirstOrDefault(File.Exists) ?? Path.Combine(AppContext.BaseDirectory, serviceName);
    }

    private static void StartService()
    {
        try
        {
            var path = ServiceExecutablePath();
            if (File.Exists(path)) Process.Start(new ProcessStartInfo(path) { UseShellExecute = true });
        }
        catch { }
    }

    private sealed record ServiceConfigDto(string Endpoint, string SharedId, string ClientId);
}
