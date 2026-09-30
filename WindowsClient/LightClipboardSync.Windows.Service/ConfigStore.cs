using System.IO;
using System.Text.Json;

namespace LightClipboardSync.Windows.Service;

public sealed class ConfigStore
{
    private readonly object _gate = new();
    private readonly string _path;
    private ServiceConfig _config;
    private readonly JsonSerializerOptions _json = new(JsonSerializerDefaults.Web) { WriteIndented = true };

    public ConfigStore()
    {
        var directory = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "LightClipboardSync");
        Directory.CreateDirectory(directory);
        _path = Path.Combine(directory, "windows-service.json");
        _config = Load();
    }

    public event EventHandler? Changed;

    public ServiceConfig Get()
    {
        lock (_gate) return _config.Clone();
    }

    public bool TryUpdate(ServiceConfig candidate, out string error)
    {
        var copy = candidate.Clone();
        if (!copy.TryValidate(out error)) return false;
        lock (_gate)
        {
            _config = copy;
            var temporary = _path + ".tmp";
            File.WriteAllText(temporary, JsonSerializer.Serialize(copy, _json));
            File.Move(temporary, _path, true);
        }
        ServiceLog.Info("config_updated");
        Changed?.Invoke(this, EventArgs.Empty);
        return true;
    }

    private ServiceConfig Load()
    {
        try
        {
            if (File.Exists(_path))
            {
                var loaded = JsonSerializer.Deserialize<ServiceConfig>(File.ReadAllText(_path));
                if (loaded is not null && loaded.TryValidate(out _))
                {
                    ServiceLog.Info($"config_loaded path={_path}");
                    return loaded;
                }
            }
        }
        catch
        {
            ServiceLog.Warn("config_load_failed_using_defaults");
        }
        var defaults = new ServiceConfig();
        try { File.WriteAllText(_path, JsonSerializer.Serialize(defaults, _json)); } catch { }
        ServiceLog.Info($"config_created path={_path}");
        return defaults;
    }
}
