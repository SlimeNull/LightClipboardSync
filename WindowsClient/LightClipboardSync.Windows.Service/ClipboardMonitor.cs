using System.IO;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media.Imaging;

namespace LightClipboardSync.Windows.Service;

/// <summary>Uses AddClipboardFormatListener/WM_CLIPBOARDUPDATE; no timer or clipboard polling is used.</summary>
public sealed class ClipboardMonitor : IDisposable
{
    private const int WmClipboardUpdate = 0x031D;
    private const int WsPopup = unchecked((int)0x80000000);
    private readonly Action<ClipboardPayload> _changed;
    private readonly ManualResetEventSlim _ready = new(false);
    private readonly object _gate = new();
    private Thread? _thread;
    private HwndSource? _source;
    private bool _stopping;

    public ClipboardMonitor(Action<ClipboardPayload> changed) => _changed = changed;

    public void Start()
    {
        lock (_gate)
        {
            if (_thread is not null) return;
            _thread = new Thread(Run) { IsBackground = true, Name = "Clipboard listener" };
            _thread.SetApartmentState(ApartmentState.STA);
            _thread.Start();
        }
        _ready.Wait(TimeSpan.FromSeconds(10));
    }

    public Task WriteAsync(ClipboardPayload payload)
    {
        var source = _source;
        if (source is null) return Task.FromException(new InvalidOperationException("剪贴板监听器尚未启动。"));
        return source.Dispatcher.InvokeAsync(() => Write(payload)).Task;
    }

    private void Run()
    {
        try
        {
            var parameters = new HwndSourceParameters("LightClipboardSync.ClipboardListener")
            {
                Width = 0, Height = 0, WindowStyle = WsPopup
            };
            _source = new HwndSource(parameters);
            _source.AddHook(WindowProc);
            if (!AddClipboardFormatListener(_source.Handle))
                throw new InvalidOperationException($"AddClipboardFormatListener 失败: {Marshal.GetLastWin32Error()}");
            ServiceLog.Info($"clipboard_listener_started hwnd=0x{_source.Handle.ToInt64():X}");
            _ready.Set();
            System.Windows.Threading.Dispatcher.Run();
        }
        catch (Exception ex)
        {
            ServiceLog.Error($"clipboard_listener_error message={ex.Message}");
            _ready.Set();
        }
        finally
        {
            if (_source is not null)
            {
                RemoveClipboardFormatListener(_source.Handle);
                _source.RemoveHook(WindowProc);
                _source.Dispose();
                _source = null;
            }
        }
    }

    private IntPtr WindowProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg == WmClipboardUpdate)
        {
            try
            {
                var payload = Read();
                if (payload is not null) _changed(payload);
            }
            catch (Exception ex) { ServiceLog.Error($"clipboard_read_error message={ex.Message}"); }
            handled = true;
        }
        return IntPtr.Zero;
    }

    private static ClipboardPayload? Read()
    {
        if (Clipboard.ContainsText(TextDataFormat.UnicodeText))
        {
            var bytes = System.Text.Encoding.UTF8.GetBytes(Clipboard.GetText(TextDataFormat.UnicodeText));
            return new ClipboardPayload("text", "text/plain", bytes);
        }
        if (Clipboard.ContainsImage())
        {
            var image = Clipboard.GetImage();
            if (image is null) return null;
            image.Freeze();
            var encoder = new PngBitmapEncoder();
            encoder.Frames.Add(BitmapFrame.Create(image));
            using var stream = new MemoryStream();
            encoder.Save(stream);
            return new ClipboardPayload("image", "image/png", stream.ToArray());
        }
        return null;
    }

    private static void Write(ClipboardPayload payload)
    {
        if (payload.Type == "text")
        {
            Clipboard.SetText(System.Text.Encoding.UTF8.GetString(payload.Data), TextDataFormat.UnicodeText);
            return;
        }
        if (payload.Type == "image")
        {
            using var stream = new MemoryStream(payload.Data, writable: false);
            var image = new BitmapImage();
            image.BeginInit();
            image.CacheOption = BitmapCacheOption.OnLoad;
            image.StreamSource = stream;
            image.EndInit();
            image.Freeze();
            Clipboard.SetImage(image);
            return;
        }
        throw new InvalidDataException($"不支持的剪贴板类型: {payload.Type}");
    }

    public void Dispose()
    {
        lock (_gate)
        {
            if (_stopping) return;
            _stopping = true;
        }
        var source = _source;
        if (source is not null)
            source.Dispatcher.BeginInvokeShutdown(System.Windows.Threading.DispatcherPriority.Normal);
        _thread?.Join(TimeSpan.FromSeconds(3));
        _ready.Dispose();
    }

    [DllImport("user32.dll", SetLastError = true)] private static extern bool AddClipboardFormatListener(IntPtr hwnd);
    [DllImport("user32.dll", SetLastError = true)] private static extern bool RemoveClipboardFormatListener(IntPtr hwnd);
}
