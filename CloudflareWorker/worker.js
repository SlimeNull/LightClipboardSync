// Dashboard Web VSC 版本。
// Wrangler 部署请使用 src/index.js 和 src/clipboard-room.js。

const MAX_CONTENT_BYTES = 25 * 1024 * 1024;
const INLINE_TEXT_BYTES = 64 * 1024;
const HISTORY_LIMIT = 3;
const SUBSCRIBER_QUEUE_LIMIT = 32;
const KEEP_ALIVE_MS = 15_000;
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const encoder = new TextEncoder();

function normalizeUuid(value) {
  return typeof value === "string" && UUID_PATTERN.test(value)
    ? value.toLowerCase()
    : null;
}

function authenticate(request) {
  const header = request.headers.get("authorization") ?? "";
  if (header.length === 0) return { ok: false, reason: "missing" };
  if (!header.toLowerCase().startsWith("basic ")) {
    return { ok: false, reason: "scheme" };
  }

  let binary;
  try {
    binary = atob(header.slice(6).trim());
  } catch {
    return { ok: false, reason: "base64" };
  }

  let credentials;
  try {
    const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0));
    credentials = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    return { ok: false, reason: "encoding" };
  }

  const separator = credentials.indexOf(":");
  if (separator <= 0 || separator !== credentials.length - 1) {
    return { ok: false, reason: "credentials" };
  }
  const user = normalizeUuid(credentials.slice(0, separator));
  return user === null
    ? { ok: false, reason: "credentials" }
    : { ok: true, user };
}

function getClientId(request) {
  const value = request.headers.get("x-client-id");
  if (value === null || value.length === 0) return { ok: true, value: null };
  const client = normalizeUuid(value);
  return client === null
    ? { ok: false, value: null }
    : { ok: true, value: client };
}

function parseContentType(value) {
  if (typeof value !== "string" || value.length === 0) {
    return { mediaType: null, charset: null };
  }
  const parts = value.split(";");
  const mediaType = parts.shift().trim().toLowerCase();
  let charset = null;
  for (const part of parts) {
    const separator = part.indexOf("=");
    if (separator < 0) continue;
    if (part.slice(0, separator).trim().toLowerCase() !== "charset") continue;
    charset = part.slice(separator + 1).trim().replace(/^"|"$/g, "").toLowerCase();
  }
  return { mediaType, charset };
}

async function readBodyWithLimit(request, limit) {
  const contentLength = request.headers.get("content-length");
  if (contentLength !== null) {
    const declaredLength = Number(contentLength);
    if (Number.isFinite(declaredLength) && declaredLength > limit) {
      return { ok: false };
    }
  }
  if (request.body === null) return { ok: true, bytes: new Uint8Array(0) };

  const reader = request.body.getReader();
  const chunks = [];
  let total = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    if (!(value instanceof Uint8Array)) continue;
    total += value.byteLength;
    if (total > limit) {
      await reader.cancel();
      return { ok: false };
    }
    chunks.push(value);
  }

  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return { ok: true, bytes };
}

function jsonResponse(value, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8" }
  });
}

function textResponse(value, status) {
  return new Response(value, {
    status,
    headers: { "Content-Type": "text/plain; charset=utf-8" }
  });
}

function logEvent(event, fields = {}) {
  console.log(JSON.stringify({ event, ...fields }));
}

function logWarning(event, fields = {}) {
  console.warn(JSON.stringify({ event, ...fields }));
}

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
      return textResponse("type must be text or image.", 400);
    }

    const contentType = parseContentType(request.headers.get("content-type"));
    if (contentType.mediaType === null) {
      return textResponse("A valid Content-Type is required.", 400);
    }
    if (
      type === "text" &&
      (contentType.mediaType !== "text/plain" ||
        (contentType.charset !== null && contentType.charset !== "utf-8"))
    ) {
      return textResponse("Text must use text/plain with UTF-8 encoding.", 400);
    }
    if (
      type === "image" &&
      !["image/png", "image/jpeg", "image/webp"].includes(contentType.mediaType)
    ) {
      return textResponse("Images must use image/png, image/jpeg, or image/webp.", 400);
    }

    const body = await readBodyWithLimit(request, MAX_CONTENT_BYTES);
    if (!body.ok) return new Response(null, { status: 413 });

    let text = null;
    if (type === "text") {
      try {
        text = new TextDecoder("utf-8", { fatal: true }).decode(body.bytes);
      } catch {
        return textResponse("Text body must be valid UTF-8.", 400);
      }
    }
    const content =
      type === "text" && body.bytes.byteLength < INLINE_TEXT_BYTES ? text : null;
    const notification = this.push({
      type,
      contentType: contentType.mediaType,
      data: body.bytes,
      content,
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
      return textResponse("id must be positive or -1 for the latest clipboard.", 400);
    }
    const id = Number(rawId);
    if (!Number.isSafeInteger(id) || (id <= 0 && id !== -1)) {
      return textResponse("id must be positive or -1 for the latest clipboard.", 400);
    }
    const entry = this.find(id, clientId);
    if (entry === null) return new Response(null, { status: 404 });

    logEvent("clipboard_pull_hit", {
      user,
      client: clientId,
      requested_id: id,
      id: entry.id,
      type: entry.type,
      bytes: entry.data.byteLength
    });
    return new Response(entry.data, {
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
    if (!subscriber.closed) logEvent("events_connected", { user, client: clientId });
    return new Response(subscriber.stream, {
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
    while (this.entries.length > HISTORY_LIMIT) this.entries.shift();

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
    this.timer = setInterval(
      () => this.enqueueRaw(": keep-alive\n\n", true),
      KEEP_ALIVE_MS
    );
    this.abortHandler = () => this.close();
    if (this.request.signal.aborted) this.close();
    else this.request.signal.addEventListener("abort", this.abortHandler, { once: true });
  }

  enqueueEvent(notification) {
    this.enqueueRaw(
      `id: ${notification.id}\ndata: ${JSON.stringify(notification)}\n\n`,
      false
    );
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
      if (this.queue.length >= SUBSCRIBER_QUEUE_LIMIT) this.queue.shift();
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
      // 客户端已经断开时，controller 可能已经关闭。
    }
  }
}

const ROUTES = new Map([
  ["/push", "POST"],
  ["/pull", "GET"],
  ["/events", "GET"]
]);

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const expectedMethod = ROUTES.get(url.pathname);
    if (expectedMethod === undefined) return textResponse("Not Found", 404);
    if (request.method !== expectedMethod) {
      return new Response(null, { status: 405, headers: { Allow: expectedMethod } });
    }

    const auth = authenticate(request);
    if (!auth.ok) {
      logWarning("auth_failed", { path: url.pathname, reason: auth.reason });
      return new Response(null, { status: 401 });
    }
    const objectId = env.CLIPBOARD_ROOM.idFromName(auth.user);
    return env.CLIPBOARD_ROOM.get(objectId).fetch(request);
  }
};
