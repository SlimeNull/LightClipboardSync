# Windows 客户端

Windows 客户端由两个可执行项目组成：

- `LightClipboardSync.Windows.Service`：管理员权限控制台进程。通过隐藏 WPF 窗口注册 `AddClipboardFormatListener`，收到 `WM_CLIPBOARDUPDATE` 后上传 `/push`，并消费服务器 `/events` 将远端文本或 PNG 写入系统剪贴板。
- `LightClipboardSync.Windows`：管理员权限 WPF 配置窗口。它只通过 `LightClipboardSync` 命名管道读取和保存配置，可以关闭而不影响后台服务。

服务配置保存于 `%ProgramData%\LightClipboardSync\windows-service.json`。首次运行会生成同步 UUID；将各设备配置为相同 UUID 即可同步。设备路由标识由服务内部维护，不在配置界面展示。

Service 日志同时输出到控制台，并写入可执行文件旁的 `logs\service.log`，记录连接、断线重连、事件接收、剪贴板写入和上传结果。

配置窗口只显示服务器地址和同步 UUID，状态只有“正在连接”和“已连接”。保存设置会取消旧的 events 连接并立即重连；“完全退出”会同时关闭配置窗口和后台服务。勾选“开机自启动”后，服务程序会写入当前用户的启动项。

构建：

```powershell
dotnet build .\LightClipboardSync.Windows.slnx -c Release
```
