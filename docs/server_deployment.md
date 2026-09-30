# 服务器部署

LightClipboardSync 的服务器有两种部署方式：

1. 在自己的服务器上运行 `Server/LightClipboardSync.Server`；
2. 将兼容版本部署到 Cloudflare Workers。

Cloudflare Workers 的详细配置见[Cloudflare 部署文档](cloudflare_worker_deploy.md)。本文说明自建 ASP.NET Server，以及如何用 Nginx 做本机反向代理和 HTTPS 终止。

## 1. 自建 ASP.NET Server

### 环境要求

- .NET 10 SDK 或对应运行时；
- 一台能够被客户端访问的主机；
- 如果客户端跨公网访问，推荐一个域名和 HTTPS 证书。

服务器当前只使用内存保存数据，不需要数据库。进程重启后，最近三条记录、订阅者和事件 ID 都会清空。

### 直接启动

在项目根目录执行：

```sh
dotnet run --project Server/LightClipboardSync.Server/LightClipboardSync.Server.csproj --urls http://127.0.0.1:5078
```

只允许本机访问时使用 `127.0.0.1`。如果需要让局域网内设备直接访问，可以绑定局域网地址或所有接口：

```sh
dotnet run --project Server/LightClipboardSync.Server/LightClipboardSync.Server.csproj --urls http://0.0.0.0:5078
```

直接使用 HTTP 只适合受信任的局域网。Basic UUID 凭证和剪贴板内容会以明文传输，不推荐直接暴露到公网。

### 发布后启动

构建发布目录：

```sh
dotnet publish Server/LightClipboardSync.Server/LightClipboardSync.Server.csproj -c Release -o ./publish/server
```

启动发布版本：

```sh
ASPNETCORE_URLS=http://127.0.0.1:5078 ./publish/server/LightClipboardSync.Server
```

也可以用 systemd、Docker 或其他进程管理工具保持进程运行。具体进程管理方式不影响客户端协议。

## 2. 推荐使用 Nginx 本机反向代理

当前 ASP.NET Server 本身没有证书配置和 HTTPS 终止功能。推荐让 Server 只监听本机回环地址，再由 Nginx 负责：

- 对外提供 HTTPS；
- 管理证书；
- 将请求反向代理到 `127.0.0.1:5078`；
- 支持 `/events` 的长期 SSE 连接。

示例 Nginx 配置：

```nginx
server {
    listen 443 ssl http2;
    server_name sync.example.com;

    ssl_certificate     /etc/letsencrypt/live/sync.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/sync.example.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:5078;
        proxy_http_version 1.1;

        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # /events 是 SSE，不能被 Nginx 缓冲或过早超时。
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 1h;
        proxy_send_timeout 1h;
    }
}

server {
    listen 80;
    server_name sync.example.com;
    return 301 https://$host$request_uri;
}
```

证书可以使用 Let’s Encrypt、企业证书或其他证书服务。Nginx 与 Server 在同一台机器上时，Server 不需要知道证书路径，也不需要监听公网端口。

启动后，客户端填写：

```text
https://sync.example.com
```

不要填写 `/push`、`/pull` 或 `/events`，客户端会自动追加 API 路径。

### 局域网 HTTP 反代

如果只在可信局域网使用，也可以让 Nginx 监听 HTTP：

```nginx
server {
    listen 8000;
    server_name _;

    location / {
        proxy_pass http://127.0.0.1:5078;
        proxy_http_version 1.1;
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 1h;
    }
}
```

此时客户端使用 `http://<服务器局域网地址>:8000`。Android 端需要允许 cleartext traffic。即使使用了 Nginx，HTTP 仍然不会保护 UUID 凭证和剪贴板内容；公网部署应使用 HTTPS。

## 3. Cloudflare Workers

Cloudflare 部署版本位于 `CloudflareWorker/`，使用一个 Worker 和一个 Durable Object。部署步骤、Wrangler 配置、Dashboard Web VSC 方案和验证命令见：

- [CloudflareWorker/README.md](../CloudflareWorker/README.md)
- [Cloudflare Worker 详细部署文档](cloudflare_worker_deploy.md)

Cloudflare Worker 默认提供 HTTPS，不需要在 Worker 前面再配置 Nginx。它与自建 Server 使用相同的三个 API，但当前实现仍然不持久化剪贴板数据。

## 4. 服务器行为和限制

- 认证：`Authorization: Basic base64(<GUID>:)`；
- 可选设备标识：`X-Client-ID`；
- 单条内容上限：25 MiB；
- 支持：UTF-8 文本、PNG、JPEG、WebP；
- 每个同步 GUID 保留最近三条记录；
- `/events` 是 SSE，发送期间产生的新事件；
- 断线不会重放事件，客户端重连后使用 `pull?id=-1` 恢复；
- 服务端不解码图片，保存并返回客户端提供的原始字节和 MIME 类型。

完整接口说明见根目录的 `README.md` 和 Cloudflare 部署文档中的 API 兼容性章节。

## 5. 反向代理检查清单

部署完成后，建议确认：

1. Server 只监听 `127.0.0.1`，公网入口由 Nginx 管理；
2. HTTPS 证书有效且自动续期；
3. Nginx 没有缓冲 `/events`，读取超时足够长；
4. 25 MiB 请求体没有被 Nginx 的默认限制提前拒绝；必要时设置：

   ```nginx
   client_max_body_size 25m;
   ```

5. 用两个不同的 `X-Client-ID` 验证 push、events 和 `pull?id=-1`；
6. 客户端填写的是服务器基础地址，并且所有设备使用同一个同步 GUID。
