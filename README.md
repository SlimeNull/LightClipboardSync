# LightClipboardSync

轻量的文本与图片剪贴板同步。当前实现包含 ASP.NET Core 服务器、macOS 菜单栏客户端和 Android 客户端。

## 启动

需要 .NET 10 SDK。启动服务器：

```sh
dotnet run --project Server/LightClipboardSync.Server/LightClipboardSync.Server.csproj --urls http://0.0.0.0:5078
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

在应用内填入可从手机访问的服务器地址，以及与 macOS 相同的同步 UUID。主界面“同步剪切板”会上传当前文本或图片，并用 Toast 报告结果。Android 客户端启动后会运行前台同步服务，基础 SSE、`pull?id=-1` 恢复和远端剪贴板接收不依赖 LSPosed 模块；启用 API 102 模块后，系统框架只负责捕获其他应用的复制并自动上传。Android 10+ 会限制后台读取系统剪贴板；应用提供可选的悬浮窗焦点回退，首次使用后台手动同步前需在设置中授予“显示在其他应用上层”权限。设置页也提供忽略电池优化入口，ColorOS 等厂商系统还可能要求用户在系统设置中允许后台活动和自启动。屏幕熄灭后仍保持 events 连接 5 分钟，连续无操作后主动断开以节省开销，亮屏后立即重连并用 `pull?id=-1` 检查最新的其他设备记录。通知操作和控制中心磁贴会直接读取并上传剪贴板，不跳转到同步 Activity。远端写入使用系统 `ClipboardManager`，图片通过应用的 `content://` provider 提供给系统剪贴板；单条写入失败不会中断 events。部分 ColorOS 版本会把 `WRITE_CLIPBOARD` 设置为仅前台，即使前台服务和悬浮窗权限都已开启，后台事件仍只能接收而不能写入系统剪贴板；应用会记录“接收失败 权限”，回到前台后通过恢复请求再次尝试。要在这类系统上实现无界面后台写入，需要系统框架 hook/模块直接调用剪贴板服务，普通应用 API 没有可绕过的权限。服务器响应头携带记录 ID、Unix 毫秒时间戳和类型。保存设置会同步到模块远程配置并重连。活动日志记录连接、恢复、上传、接收和错误结果，最多保留 100 行。部分手机的省电策略可能暂停后台网络；服务端不会重发断线期间的事件。

APK 同时包含现代 libxposed API 102 模块入口，适用于支持 API 102 的 LSPosed/Vector 框架。安装后在模块管理器中启用本模块，推荐作用域为“系统框架”（现代模块的 `system`，即 `system_server`），然后重启设备使系统进程加载 hook；每次更新 APK 后也需重启才能加载新的模块代码。模块观察 `ClipboardService.setPrimaryClipInternalLocked`（旧 Android 使用 `setPrimaryClipInternal`）提交的文本和图片，并在系统框架内直接 POST；对已启用模块的本应用，仅额外放行 ColorOS 的后台 `WRITE_CLIPBOARD` 检查，使应用自己的 events 路径能够完成远端写入。模块不负责 SSE、恢复或手动同步；没有模块时，应用自己的前台服务仍会持续接收远端事件并支持手动上传。图片 URI 使用系统剪贴板服务原有的授权方法读取。模块日志会记录加载、hook 安装、上传、接收和异常；应用活动日志会记录连接、恢复、上传、接收和错误结果。实际运行仍依赖设备的系统实现，尚待新版本的设备测试。

界面设计稿和图标源文件位于 `design/`，Android 主界面使用 Compose Material 3 `Scaffold`，macOS 设置窗口使用 SwiftUI 布局容器。

## HTTP 协议

三个接口均使用 `Authorization: Basic <base64(UUID:)>`，即 UUID 为 Basic 用户名、密码为空。UUID 使用标准带连字符格式。未授权返回 401。可选的 `X-Client-ID: <设备 UUID>` 标识发送设备；同一设备的 `/events` 订阅不会收到自身 `/push` 产生的事件。

| 接口 | 用途 |
| --- | --- |
| `POST /push?type=text` | 请求体为 UTF-8 文本，`Content-Type: text/plain; charset=utf-8` |
| `POST /push?type=image` | 请求体为图片，`Content-Type` 为 `image/png`、`image/jpeg` 或 `image/webp` |
| `GET /events` | SSE；每条消息的 `data` 是 `{"id":1,"type":"text","content":"...","timestamp":1730000000000}` |
| `GET /pull?id=1` | 获取指定事件的原始请求体和 Content-Type |
| `GET /pull?id=-1` | 配合 `X-Client-ID` 获取最近一条其他设备记录；响应头包含 `X-Clipboard-ID`、`X-Clipboard-Timestamp`（Unix 毫秒）和 `X-Clipboard-Type` |

每次 `/push` 返回 201 和同格式 JSON 事件，其中包含 `timestamp`（Unix 毫秒）。事件 ID 在当前服务器进程内全局递增。文本的 UTF-8 请求体严格小于 64 KiB 时，SSE 的 `content` 内联文本；大文本和图片的 `content` 为 `null`，客户端用 `/pull` 获取。单条上传上限为 25 MiB，超过返回 413。每个 UUID 只保留最近 3 条内容，更早的 `/pull` 返回 404。

SSE 仅发送订阅期间产生的新事件；断线重连不会重发。服务器数据保存在内存中，重启后历史和事件计数清空。服务器不验证 UUID 的所有权，持有同一 UUID 即可读写同一份剪贴板；在不受信任的网络中应使用 HTTPS，明文 HTTP 会暴露 Basic 凭证和内容。
