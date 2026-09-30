import { authenticate, logWarning, textResponse } from "./common.js";
import { ClipboardRoom } from "./clipboard-room.js";

// Durable Object 类必须从 Worker 入口模块导出，才能匹配 wrangler.jsonc 的 class_name。
export { ClipboardRoom };

const ROUTES = new Map([
  ["/push", "POST"],
  ["/pull", "GET"],
  ["/events", "GET"]
]);

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const expectedMethod = ROUTES.get(url.pathname);

    if (expectedMethod === undefined) {
      return textResponse("Not Found", 404);
    }
    if (request.method !== expectedMethod) {
      return new Response(null, {
        status: 405,
        headers: { Allow: expectedMethod }
      });
    }

    // 入口处先认证，用 UUID 选择 DO；DO 内部还会再次认证。
    const auth = authenticate(request);
    if (!auth.ok) {
      logWarning("auth_failed", { path: url.pathname, reason: auth.reason });
      return new Response(null, { status: 401 });
    }

    const objectId = env.CLIPBOARD_ROOM.idFromName(auth.user);
    const room = env.CLIPBOARD_ROOM.get(objectId);
    return room.fetch(request);
  }
};
