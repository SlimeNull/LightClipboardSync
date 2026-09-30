# Cloudflare Workers 可行性评估

当前服务端协议适合改写为 Worker JavaScript：Basic UUID 认证、MIME 校验、严格 UTF-8、25 MiB 请求体、原始图片响应和 JSON/SSE 格式都可以使用 Fetch API 和 Web Streams 实现。

不能直接把 ASP.NET Core 程序放进 Worker，也不能用 Worker 文件顶部的全局 `Map` 代替 `ClipboardStore`。Worker 隔离实例可能重启、并发请求可能进入不同实例，普通全局内存无法可靠共享历史记录或 SSE 订阅。

推荐结构是 Worker 路由加 Durable Object：

```text
客户端 → Worker Fetch Router → ClipboardRoom Durable Object
                                  ├─ 最近三条记录
                                  ├─ 事件 ID
                                  └─ SSE 订阅队列
```

每个同步 GUID 对应一个 `ClipboardRoom`。Durable Object 负责同一 GUID 的请求串行化和连接共享；当前实现不使用 Storage，因此重启后仍然会清空内存数据，符合现有产品决策。

需要重点实测：

- 长时间 SSE 的断开、连接数量、Durable Object 生命周期和费用；
- 25 MiB 请求体在目标 Workers 套餐中的请求和内存限制；
- 部署替换或对象重启后，客户端能否通过 `pull?id=-1` 恢复；
- Cloudflare HTTPS Worker 与 Android、macOS、Windows 客户端的连接稳定性。

完整实现已经放入 [`CloudflareWorker/`](../CloudflareWorker/)，部署说明见[Cloudflare 部署文档](cloudflare_worker_deploy.md)。
