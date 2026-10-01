# LightClipboardSync Cloudflare Worker

这个目录是 LightClipboardSync 服务端的 Cloudflare Workers 部署版本。它使用一个 Worker 和一个 Durable Object 类 `ClipboardRoom`，保持现有的三个 API：

- `POST /push?type=text|image`
- `GET /pull?id=<正数或 -1>`
- `GET /events`

每个同步 UUID 映射到一个 Durable Object。对象内存中最多保存三条记录和当前 SSE 订阅者；代码不使用 Durable Object Storage、KV、D1 或 R2。因此它保持当前服务端的“不持久化”语义：Worker/DO 重启或重新部署后，历史记录、订阅者和事件 ID 可能清空。

## 目录

```text
CloudflareWorker/
├── package.json
├── wrangler.jsonc
├── README.md
├── worker.js                  # Dashboard Web VSC 单文件版本
└── src/
    ├── common.js
    ├── index.js
    └── clipboard-room.js
```

## 使用 Wrangler 部署

Wrangler 是 Cloudflare 官方命令行工具。需要 Node.js 和一个已启用 Durable Objects 的 Cloudflare 账户。

在本目录执行：

```bash
npm install
npx wrangler login
npx wrangler deploy
```

第一次部署会根据 `wrangler.jsonc`：

1. 创建或更新 Worker；
2. 注册 `ClipboardRoom` Durable Object 类；
3. 创建 `CLIPBOARD_ROOM` 绑定；
4. 输出 Worker 的 `workers.dev` 地址。

后续修改源码后再次运行：

```bash
npx wrangler deploy
```

`compatibility_date` 必须是不晚于 Cloudflare 当前日期的有效日期。当前配置使用 `2026-09-29`，可以在确认 Cloudflare 日期后再改成更近的过去日期；不要填写未来日期。

如果当前 Wrangler 版本不接受 `new_sqlite_classes`，把 `wrangler.jsonc` 中的 migration 改为：

```jsonc
"migrations": [
  {
    "tag": "v1",
    "new_classes": ["ClipboardRoom"]
  }
]
```

不要在已经部署过 `v1` 后反复删除或重命名已有 migration。普通源码修改不需要新增 migration。

## 使用 Dashboard Web VSC

不想安装 Node.js 时，可以直接在 Cloudflare 控制台编辑和部署。请将 [`worker.js`](worker.js) 粘贴到 Web VSC，然后参考 [Cloudflare 部署文档](../docs/cloudflare_worker_deploy.md) 的 Dashboard 章节添加 Durable Object binding：

```text
Binding variable name: CLIPBOARD_ROOM
Durable Object class: ClipboardRoom
```


## 客户端服务器地址

部署后得到的地址通常类似：

```text
https://light-clipboard-sync.<account-subdomain>.workers.dev
```

客户端配置这个基础地址即可，不要手动追加 `/push`、`/pull` 或 `/events`；客户端会自行生成 API 路径。也可以在 Worker 的 **Domains & Routes** 中绑定自己的 HTTPS 域名。

Cloudflare Worker 使用 HTTPS。局域网自建 ASP.NET 服务器仍可以继续使用 HTTP；Android 的 cleartext traffic 配置只影响后者。

## 本地运行和验证

启动本地 Worker：

```bash
npm run dev
```

准备测试变量：

```bash
export BASE_URL="http://127.0.0.1:8787"
export SYNC_UUID="11111111-1111-4111-8111-111111111111"
export CLIENT_A="aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
export CLIENT_B="bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
```

终端一保持设备 B 的 SSE：

```bash
curl -N \
  -u "$SYNC_UUID:" \
  -H "X-Client-ID: $CLIENT_B" \
  "$BASE_URL/events"
```

终端二用设备 A 推送：

```bash
curl -i \
  -u "$SYNC_UUID:" \
  -H "X-Client-ID: $CLIENT_A" \
  -H "Content-Type: text/plain; charset=utf-8" \
  --data-binary 'hello from worker' \
  "$BASE_URL/push?type=text"
```

设备 A 获取最近一条其他设备记录：

```bash
curl -i \
  -u "$SYNC_UUID:" \
  -H "X-Client-ID: $CLIENT_A" \
  "$BASE_URL/pull?id=-1"
```

验证图片原始字节：

```bash
curl -i \
  -u "$SYNC_UUID:" \
  -H "X-Client-ID: $CLIENT_A" \
  -H "Content-Type: image/png" \
  --data-binary @sample.png \
  "$BASE_URL/push?type=image"
```

## 重要限制

- 单条内容上限为 25 MiB；文本必须是严格 UTF-8，图片只接受 PNG、JPEG、WebP。
- 每个同步 UUID 只保留最近三条记录，事件 ID 在该 UUID 的 Durable Object 内递增。
- `/events` 使用 SSE，每 15 秒发送 keep-alive；断线不会重放事件，客户端必须重连并调用 `pull?id=-1` 恢复。
- Durable Object 内存可能因实例重启、部署替换或故障迁移而清空；不要把它当作持久化数据库。
- 每个 SSE 客户端是一个长期连接，正式部署前应确认目标账户的连接时长、DO 生命周期、费用和实际断线行为。
- 代码不会记录 Authorization、请求体或图片内容；日志通过 `console.log`/`console.warn` 输出，可用 `npx wrangler tail` 查看。

## 回滚

Worker 与原有 ASP.NET 服务器互不覆盖。验证失败时，把 Android、macOS 和 Windows 客户端的服务器地址改回原服务器即可。由于两端都不持久化迁移历史，切换地址后只会同步切换之后产生的内容。
