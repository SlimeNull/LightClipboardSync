# LightClipboardSync

LightClipboardSync 是一个轻量的文本与图片剪贴板同步工具，支持 Android、macOS、Windows 和自建服务器。同步服务只提供上传、拉取和实时事件三个接口，适合个人设备或小规模私有部署。

## 使用概述

1. 部署一个服务器：可以运行项目中的 ASP.NET Server，也可以部署到 Cloudflare Worker。
2. 从项目 Releases 下载对应平台的客户端。
3. 在**所有客户端中设置完全相同的同步 GUID**。GUID 不同的客户端属于不同的同步空间，无法互相同步。
4. 在客户端填写服务器基础地址，例如 `https://sync.example.com`，不要追加 `/push`、`/pull` 或 `/events`。

Android 客户端没有安装模块时，仍然可以接收远端剪贴板、执行恢复和手动同步；模块只用于自动捕获其他应用复制的内容并调用 `/push`。Windows 服务和 macOS 菜单栏应用可以在后台运行并消费实时事件。

## 服务器部署

服务器有两种部署方式：

- 在自己的服务器上运行 `Server/LightClipboardSync.Server`；
- 使用 `CloudflareWorker/` 部署到 Cloudflare Workers + Durable Object。

详细说明见 [服务器部署文档](docs/server_deployment.md) 和 [CloudflareWorker/README.md](CloudflareWorker/README.md)。

自建 ASP.NET Server 目前不负责证书和 HTTPS 终止。公网或跨网络部署时，推荐让 Server 只监听本机地址，再使用 Nginx 等反向代理工具处理 HTTPS、证书和 `/events` 长连接。可信局域网可以使用 HTTP，但明文 HTTP 不适合公网。

## 安全与数据保存

- 服务器不会持久化存储任何用户数据；当前只在内存中保留每个 GUID 最近三条记录，服务重启后即清空。
- GUID 同时作为共享认证凭证。能够连接服务器并持有相同 GUID 的设备，都可以读写对应的剪贴板。
- 项目更推荐用户自己搭建服务器，以便自行控制网络、日志和数据生命周期。
- 在不受信任的网络中应使用 HTTPS。自建服务器可通过 Nginx 反向代理提供 HTTPS；Cloudflare Worker 默认使用 HTTPS。

## HTTP 接口

所有请求使用：

```http
Authorization: Basic base64(<GUID>:)
```

可选的 `X-Client-ID` 用于标识设备，并避免设备收到自己发送的实时事件。

| 接口 | 用途 |
| --- | --- |
| `POST /push?type=text|image` | 上传 UTF-8 文本或 PNG/JPEG/WebP 原始图片 |
| `GET /pull?id=<id>` | 拉取指定记录；`id=-1` 获取最近一条其他设备记录 |
| `GET /events` | 通过 SSE 接收新记录 |

单条内容上限为 25 MiB，每个 GUID 保留最近三条记录。图片由客户端编码，服务器只保存和返回原始字节及 MIME 类型。SSE 断线不会重放事件，客户端通过重新连接和 `pull?id=-1` 恢复。

## 项目结构

- `Server/`：ASP.NET Core 服务端；
- `CloudflareWorker/`：Cloudflare Workers 部署版本；
- `AndroidClient/`：Android 客户端及可选系统框架模块；
- `MacClient/`：macOS 菜单栏客户端；
- `WindowsClient/`：Windows 后台服务和配置窗口；
- `docs/`：服务器部署、反向代理和 Cloudflare 说明；
- `design/`：界面和图标设计资源。

## 文档

更多服务器说明见 [docs/](docs/README.md)。客户端构建细节面向开发者，正式使用时请优先从 Releases 获取已经构建好的客户端。
