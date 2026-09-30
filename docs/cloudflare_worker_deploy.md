# Cloudflare Workers 部署

仓库中的 Cloudflare 实现位于 [`CloudflareWorker/`](../CloudflareWorker/)。它使用一个 Worker 和一个 `ClipboardRoom` Durable Object，保持现有三个 API：

- `POST /push?type=text|image`
- `GET /pull?id=<正数或 -1>`
- `GET /events`

每个同步 GUID 对应一个 Durable Object。对象只在内存中保留最近三条记录和当前 SSE 订阅者，不使用 KV、D1、R2 或 Durable Object Storage 保存剪贴板数据。

## Wrangler 部署

进入目录：

```sh
cd CloudflareWorker
```

安装依赖并登录：

```sh
npm install
npx wrangler login
```

部署：

```sh
npx wrangler deploy
```

首次部署会根据 [`wrangler.jsonc`](../CloudflareWorker/wrangler.jsonc) 注册 `ClipboardRoom` 类并创建 `CLIPBOARD_ROOM` binding。部署成功后，Wrangler 会输出 `workers.dev` 地址。

后续只修改 JavaScript 源码时再次执行：

```sh
npx wrangler deploy
```

不要把 `compatibility_date` 设置为未来日期。配置中的日期必须不晚于 Cloudflare 当前日期；如果 Cloudflare 报 `Can't set compatibility date in the future`，改成一个更早的日期即可。

## 配置说明

### Durable Object binding

`wrangler.jsonc` 中的关键配置是：

```jsonc
"durable_objects": {
  "bindings": [
    {
      "name": "CLIPBOARD_ROOM",
      "class_name": "ClipboardRoom"
    }
  ]
}
```

Worker 入口通过同步 GUID 选择对象：

```js
const objectId = env.CLIPBOARD_ROOM.idFromName(auth.user);
const room = env.CLIPBOARD_ROOM.get(objectId);
return room.fetch(request);
```

因此不需要为每台设备手动创建 Durable Object。相同 GUID 的所有客户端会进入同一个对象，不同 GUID 会进入不同对象。

第一次部署还需要 migration：

```jsonc
"migrations": [
  {
    "tag": "v1",
    "new_sqlite_classes": ["ClipboardRoom"]
  }
]
```

代码没有调用 `state.storage`，所以这个 migration 不会自动把剪贴板数据变成持久化数据。若当前 Wrangler 版本不接受 `new_sqlite_classes`，可以按版本要求改成 `new_classes`；已经部署过的 migration 不要反复删除或重命名。

### Cloudflare Dashboard Web VSC

不使用 Wrangler 也可以从控制台部署：

1. 在 **Workers & Pages** 创建 Worker，打开 **Edit code**；
2. 将 [`CloudflareWorker/worker.js`](../CloudflareWorker/worker.js) 完整粘贴到编辑器；
3. 保存并部署；
4. 打开 **Settings → Bindings**；
5. 添加 Durable Object binding，变量名填写 `CLIPBOARD_ROOM`，类名填写 `ClipboardRoom`；
6. 创建新的 namespace 后再次部署。

如果控制台没有 Durable Object binding 选项，需要先确认账户套餐和权限。Dashboard 版本使用 Module Worker，必须保留 `export default` 和 `export class ClipboardRoom`。

## 客户端配置

客户端只填写 Worker 基础地址，例如：

```text
https://light-clipboard-sync.<account-subdomain>.workers.dev
```

不要手动追加 API 路径。Cloudflare Worker 默认提供 HTTPS，不需要在 Worker 前面再加 Nginx。自定义域名可以在 **Domains & Routes** 中配置。

## 协议兼容性

### 认证

请求使用：

```http
Authorization: Basic base64(<GUID>:)
```

GUID 必须是带连字符的标准 UUID。可选的 `X-Client-ID` 标识设备；同一设备的 `/events` 不会收到自己发出的 push。

### `/push`

- 文本使用 `text/plain`，可带 UTF-8 charset；
- 图片使用 `image/png`、`image/jpeg` 或 `image/webp`；
- 单条请求体上限 25 MiB；
- 非法 UTF-8 返回 `400`，超过限制返回 `413`；
- 图片保留客户端发送的原始字节和 MIME 类型；
- 小于 64 KiB 的文本内联到事件 JSON，大文本和图片需要客户端再 pull。

### `/pull`

`id` 可以是正数或 `-1`。`id=-1` 返回当前客户端之外最近的一条记录。响应使用原始 `Content-Type`，并携带：

```http
X-Clipboard-ID
X-Clipboard-Timestamp
X-Clipboard-Type
Cache-Control: no-store
```

### `/events`

响应是 `text/event-stream; charset=utf-8`，连接建立时发送 `: connected`，之后每 15 秒发送 `: keep-alive`。事件内容包含 `id`、`type`、`content` 和 `timestamp`。

当前协议没有事件重放。SSE 断开后，客户端必须重连并使用 `pull?id=-1` 恢复。部署或 Durable Object 重启也可能清空内存数据和事件 ID，这属于当前服务的预期语义。

## 验证

启动本地 Worker：

```sh
npm run dev
```

保持一个客户端的 SSE 连接：

```sh
curl -N -u "$SYNC_GUID:" \
  -H "X-Client-ID: $CLIENT_B" \
  http://127.0.0.1:8787/events
```

再从另一个客户端 push：

```sh
curl -i -u "$SYNC_GUID:" \
  -H "X-Client-ID: $CLIENT_A" \
  -H "Content-Type: text/plain; charset=utf-8" \
  --data-binary 'hello from worker' \
  'http://127.0.0.1:8787/push?type=text'
```

检查以下行为：

1. 另一个客户端能收到 SSE；
2. push 的客户端不会收到自己的事件；
3. `pull?id=-1` 返回最近的其他设备记录；
4. 第四条记录会淘汰最早记录；
5. 断开 SSE 后重新连接能继续工作；
6. 25 MiB 和 64 KiB 边界符合服务端协议。

实际 Cloudflare 环境还需要观察长期 SSE 连接、Durable Object 生命周期、连接数和费用。少量个人设备适合先部署测试，但不能假设长连接永久存在。

## 日志和回滚

查看日志：

```sh
npx wrangler tail
```

日志不会记录 Authorization、请求体或图片内容。Worker 与原有 ASP.NET Server 独立，验证失败时可以把客户端服务器地址改回自建服务器。
