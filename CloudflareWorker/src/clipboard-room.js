import {
  HISTORY_LIMIT,
  INLINE_TEXT_BYTES,
  KEEP_ALIVE_MS,
  MAX_CONTENT_BYTES,
  SUBSCRIBER_QUEUE_LIMIT,
  authenticate,
  getClientId,
  jsonResponse,
  logEvent,
  logWarning,
  parseContentType,
  readBodyWithLimit,
  textResponse
} from "./common.js";

const encoder = new TextEncoder();

export class ClipboardRoom {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.lastId = 0;
    this.entries = [];
    this.subscribers = new Map();
    this.nextSubscriberId = 1;
  }

  async fetch(request) {
    const started = Date.now();
    const url = new URL(request.url);
    let response;

    try {
      const auth = authenticate(request);
      if (!auth.ok) {
        logWarning("auth_failed", { path: url.pathname, reason: auth.reason });
        response = new Response(null, { status: 401 });
      } else {
        const client = getClientId(request);
        if (!client.ok) {
          logWarning("invalid_client_id", { path: url.pathname, user: auth.user });
          response = textResponse("Invalid X-Client-ID.", 400);
        } else if (url.pathname === "/push" && request.method === "POST") {
          response = await this.handlePush(request, url, auth.user, client.value);
        } else if (url.pathname === "/pull" && request.method === "GET") {
          response = this.handlePull(url, auth.user, client.value);
        } else if (url.pathname === "/events" && request.method === "GET") {
          response = this.handleEvents(request, auth.user, client.value);
        } else {
          response = textResponse("Not Found", 404);
        }
      }
    } catch (error) {
      logWarning("worker_exception", {
        path: url.pathname,
        message: error instanceof Error ? error.message : String(error)
      });
      response = textResponse("Internal Server Error", 500);
    }

    // 对于 SSE，这条日志记录的是响应建立时间；连接关闭另外记录。
    logEvent("http_request", {
      method: request.method,
      path: url.pathname,
      status: response.status,
      elapsed_ms: Date.now() - started
    });
    return response;
  }

  async handlePush(request, url, user, clientId) {
    const type = url.searchParams.get("type") ?? "";
    if (type !== "text" && type !== "image") {
      logWarning("invalid_push_type", { user, type });
      return textResponse("type must be text or image.", 400);
    }

    const parsedContentType = parseContentType(request.headers.get("content-type"));
    if (parsedContentType.mediaType === null) {
      logWarning("invalid_content_type", { path: "/push", user, type });
      return textResponse("A valid Content-Type is required.", 400);
    }

    if (
      type === "text" &&
      (parsedContentType.mediaType !== "text/plain" ||
        (parsedContentType.charset !== null && parsedContentType.charset !== "utf-8"))
    ) {
      logWarning("invalid_content_type", {
        path: "/push",
        user,
        type,
        content_type: parsedContentType.mediaType
      });
      return textResponse("Text must use text/plain with UTF-8 encoding.", 400);
    }

    if (
      type === "image" &&
      !["image/png", "image/jpeg", "image/webp"].includes(parsedContentType.mediaType)
    ) {
      logWarning("invalid_content_type", {
        path: "/push",
        user,
        type,
        content_type: parsedContentType.mediaType
      });
      return textResponse("Images must use image/png, image/jpeg, or image/webp.", 400);
    }

    const body = await readBodyWithLimit(request, MAX_CONTENT_BYTES);
    if (!body.ok) {
      logWarning("payload_too_large", { path: "/push", user, type });
      return new Response(null, { status: 413 });
    }

    let text = null;
    if (type === "text") {
      try {
        text = new TextDecoder("utf-8", { fatal: true, ignoreBOM: true }).decode(body.bytes);
      } catch {
        logWarning("invalid_utf8", { path: "/push", user });
        return textResponse("Text body must be valid UTF-8.", 400);
      }
    }

    const inlineContent =
      type === "text" && body.bytes.byteLength < INLINE_TEXT_BYTES ? text : null;
    const notification = this.push({
      type,
      contentType: parsedContentType.mediaType,
      data: body.bytes,
      content: inlineContent,
      sourceClient: clientId
    });

    logEvent("clipboard_push", {
      user,
      client: clientId,
      id: notification.id,
      type,
      bytes: body.bytes.byteLength
    });
    return jsonResponse(notification, 201);
  }

  handlePull(url, user, clientId) {
    const rawId = url.searchParams.get("id");
    if (rawId === null || !/^-?\d+$/.test(rawId)) {
      logWarning("invalid_pull_id", { user, id: rawId });
      return textResponse("id must be positive or -1 for the latest clipboard.", 400);
    }

    const id = Number(rawId);
    if (!Number.isSafeInteger(id) || (id <= 0 && id !== -1)) {
      logWarning("invalid_pull_id", { user, id: rawId });
      return textResponse("id must be positive or -1 for the latest clipboard.", 400);
    }

    const entry = this.find(id, clientId);
    if (entry === null) {
      logEvent("clipboard_pull_miss", {
        user,
        client: clientId,
        requested_id: id
      });
      return new Response(null, { status: 404 });
    }

    logEvent("clipboard_pull_hit", {
      user,
      client: clientId,
      requested_id: id,
      id: entry.id,
      type: entry.type,
      bytes: entry.data.byteLength
    });
    return new Response(entry.data, {
      status: 200,
      headers: {
        "Content-Type": entry.contentType,
        "Cache-Control": "no-store",
        "X-Clipboard-ID": String(entry.id),
        "X-Clipboard-Timestamp": String(entry.timestamp),
        "X-Clipboard-Type": entry.type
      }
    });
  }

  handleEvents(request, user, clientId) {
    const id = this.nextSubscriberId++;
    const subscriber = new Subscriber(this, id, user, clientId, request);
    this.subscribers.set(id, subscriber);
    if (subscriber.closed) {
      this.subscribers.delete(id);
    }
    if (!subscriber.closed) {
      logEvent("events_connected", { user, client: clientId });
    }

    return new Response(subscriber.stream, {
      status: 200,
      headers: {
        "Content-Type": "text/event-stream; charset=utf-8",
        "Cache-Control": "no-cache",
        "X-Accel-Buffering": "no"
      }
    });
  }

  push({ type, contentType, data, content, sourceClient }) {
    const entry = {
      id: ++this.lastId,
      type,
      contentType,
      data,
      content,
      sourceClient,
      timestamp: Date.now()
    };

    this.entries.push(entry);
    while (this.entries.length > HISTORY_LIMIT) {
      this.entries.shift();
    }

    const notification = {
      id: entry.id,
      type: entry.type,
      content: entry.content,
      timestamp: entry.timestamp
    };

    for (const subscriber of this.subscribers.values()) {
      if (sourceClient === null || subscriber.clientId !== sourceClient) {
        subscriber.enqueueEvent(notification);
      }
    }
    return notification;
  }

  find(id, requestingClient) {
    if (id === -1) {
      for (let index = this.entries.length - 1; index >= 0; index -= 1) {
        const entry = this.entries[index];
        if (requestingClient === null || entry.sourceClient !== requestingClient) {
          return entry;
        }
      }
      return null;
    }

    return this.entries.find((entry) => entry.id === id) ?? null;
  }

  removeSubscriber(id, user, clientId) {
    if (this.subscribers.delete(id)) {
      logEvent("events_disconnected", { user, client: clientId });
    }
  }
}

class Subscriber {
  constructor(room, id, user, clientId, request) {
    this.room = room;
    this.id = id;
    this.user = user;
    this.clientId = clientId;
    this.request = request;
    this.controller = null;
    this.queue = [];
    this.closed = false;
    this.timer = null;
    this.abortHandler = null;

    this.stream = new ReadableStream({
      start: (controller) => this.start(controller),
      pull: (controller) => this.flush(controller),
      cancel: () => this.close()
    });
  }

  start(controller) {
    this.controller = controller;
    this.enqueueRaw(": connected\n\n", false);

    this.timer = setInterval(() => {
      // keep-alive 不是业务事件；客户端背压时可以丢弃这一条注释。
      this.enqueueRaw(": keep-alive\n\n", true);
    }, KEEP_ALIVE_MS);

    this.abortHandler = () => this.close();
    if (this.request.signal.aborted) {
      this.close();
    } else {
      this.request.signal.addEventListener("abort", this.abortHandler, { once: true });
    }
  }

  enqueueEvent(notification) {
    const json = JSON.stringify(notification);
    this.enqueueRaw(`id: ${notification.id}\ndata: ${json}\n\n`, false);
  }

  enqueueRaw(text, dropIfBackpressured) {
    if (this.closed || this.controller === null) return;

    try {
      const desiredSize = this.controller.desiredSize;
      if (this.queue.length === 0 && desiredSize !== null && desiredSize > 0) {
        this.controller.enqueue(encoder.encode(text));
        return;
      }

      if (dropIfBackpressured) return;
      if (this.queue.length >= SUBSCRIBER_QUEUE_LIMIT) {
        this.queue.shift();
      }
      this.queue.push(encoder.encode(text));
    } catch {
      this.close();
    }
  }

  flush(controller) {
    if (this.closed) return;
    try {
      while (
        this.queue.length > 0 &&
        controller.desiredSize !== null &&
        controller.desiredSize > 0
      ) {
        controller.enqueue(this.queue.shift());
      }
    } catch {
      this.close();
    }
  }

  close() {
    if (this.closed) return;
    this.closed = true;
    if (this.timer !== null) clearInterval(this.timer);
    if (this.abortHandler !== null) {
      this.request.signal.removeEventListener("abort", this.abortHandler);
    }

    this.room.removeSubscriber(this.id, this.user, this.clientId);
    try {
      this.controller?.close();
    } catch {
      // 客户端已经断开时，ReadableStream controller 可能已经关闭。
    }
  }
}
