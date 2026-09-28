# LightClipboardSync

轻量的文本与图片剪贴板同步。当前实现包含 ASP.NET Core 服务器、macOS 菜单栏客户端和 Android 客户端。

## 启动

需要 .NET 10 SDK。启动服务器：

```sh
dotnet run --project Server/LightClipboardSync.Server.csproj --urls http://0.0.0.0:5078
```

`0.0.0.0` 允许局域网内其他设备连接。macOS 客户端需要 macOS 13 或更新版本与完整 Xcode。用 Xcode 打开 `MacClient/LightClipboardSync.xcodeproj`，选择 `LightClipboardSync` scheme 即可构建运行；也可生成菜单栏应用：

```sh
sh MacClient/build-app.sh
open MacClient/dist/LightClipboardSync.app
```

首次启动会生成并保存同步 UUID。点击菜单栏剪贴板图标，在“设置…”中填写服务器地址；复制该 UUID，并在其他设备上设置相同值即可使用同一份剪贴板。客户端每 0.5 秒检查系统剪贴板变化，启动时已有的内容不会自动上传。

## Android 客户端

用 Android Studio 打开 `AndroidClient` 目录运行，或在本机已有 Android SDK 和 Android Studio 的环境中构建：

```sh
cd AndroidClient
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

在应用内填入可从手机访问的服务器地址，以及与 macOS 相同的同步 UUID。主界面“同步剪切板”会上传当前文本或图片，并用 Toast 报告结果。桌面长按应用图标的快捷方式、控制中心磁贴和常驻通知也提供同一操作；这些外部入口会短暂获取应用焦点以读取剪贴板，不展示设置页面。主界面可见时保持 SSE 连接；开启“常驻通知”后，前台服务在后台继续保持连接并把远端内容写入剪贴板。保存设置会断开旧连接并用新配置重连，主界面显示实际连接状态。活动日志只记录时间、方向和类型，最多保留 100 行。部分手机的省电策略可能暂停后台网络；服务端不会重发断线期间的事件。

APK 同时包含现代 libxposed API 102 模块入口，适用于支持 API 102 的 LSPosed/Vector 框架。安装后在模块管理器中启用本模块，推荐作用域为“系统框架”（现代模块的 `system`，即 `system_server`），然后重启设备使系统进程加载 hook；每次更新 APK 后也需重启才能加载新的模块代码。`android` 在现代模块中指普通“安卓系统”包，不能代替系统框架作用域。模块观察 `ClipboardService.setPrimaryClipInternalLocked`（旧 Android 使用 `setPrimaryClipInternal`）提交的文本和图片，并通过签名权限保护的广播交给应用上传。图片 URI 使用系统剪贴板服务原有的授权方法转交。模块日志会记录加载、hook 安装、广播转发和异常；应用活动日志会记录自动发送及失败，仅包含时间和类型。实际运行仍依赖设备的系统实现，尚待新版本的设备测试。没有模块时，上述手动入口仍可使用。

界面设计稿和图标源文件位于 `design/`，Android 主界面使用 Compose Material 3 `Scaffold`，macOS 设置窗口使用 SwiftUI 布局容器。

## HTTP 协议

三个接口均使用 `Authorization: Basic <base64(UUID:)>`，即 UUID 为 Basic 用户名、密码为空。UUID 使用标准带连字符格式。未授权返回 401。可选的 `X-Client-ID: <设备 UUID>` 标识发送设备；同一设备的 `/events` 订阅不会收到自身 `/push` 产生的事件。

| 接口 | 用途 |
| --- | --- |
| `POST /push?type=text` | 请求体为 UTF-8 文本，`Content-Type: text/plain; charset=utf-8` |
| `POST /push?type=image` | 请求体为图片，`Content-Type` 为 `image/png`、`image/jpeg` 或 `image/webp` |
| `GET /events` | SSE；每条消息的 `data` 是 `{"id":1,"type":"text","content":"..."}` |
| `GET /pull?id=1` | 获取指定事件的原始请求体和 Content-Type |

每次 `/push` 返回 201 和同格式 JSON 事件。事件 ID 在当前服务器进程内全局递增。文本的 UTF-8 请求体严格小于 64 KiB 时，SSE 的 `content` 内联文本；大文本和图片的 `content` 为 `null`，客户端用 `/pull` 获取。单条上传上限为 25 MiB，超过返回 413。每个 UUID 只保留最近 3 条内容，更早的 `/pull` 返回 404。

SSE 仅发送订阅期间产生的新事件；断线重连不会重发。服务器数据保存在内存中，重启后历史和事件计数清空。服务器不验证 UUID 的所有权，持有同一 UUID 即可读写同一份剪贴板；在不受信任的网络中应使用 HTTPS，明文 HTTP 会暴露 Basic 凭证和内容。
