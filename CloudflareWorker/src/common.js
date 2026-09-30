export const MAX_CONTENT_BYTES = 25 * 1024 * 1024;
export const INLINE_TEXT_BYTES = 64 * 1024;
export const HISTORY_LIMIT = 3;
export const SUBSCRIBER_QUEUE_LIMIT = 32;
export const KEEP_ALIVE_MS = 15_000;

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function normalizeUuid(value) {
  if (typeof value !== "string" || !UUID_PATTERN.test(value)) {
    return null;
  }
  return value.toLowerCase();
}

export function authenticate(request) {
  const header = request.headers.get("authorization") ?? "";
  if (header.length === 0) {
    return { ok: false, reason: "missing" };
  }
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
    credentials = new TextDecoder("utf-8", { fatal: true, ignoreBOM: true }).decode(bytes);
  } catch {
    return { ok: false, reason: "encoding" };
  }

  const separator = credentials.indexOf(":");
  if (separator <= 0 || separator !== credentials.length - 1) {
    return { ok: false, reason: "credentials" };
  }

  const user = normalizeUuid(credentials.slice(0, separator));
  if (user === null) {
    return { ok: false, reason: "credentials" };
  }
  return { ok: true, user };
}

export function getClientId(request) {
  const value = request.headers.get("x-client-id");
  if (value === null || value.length === 0) {
    return { ok: true, value: null };
  }

  const client = normalizeUuid(value);
  return client === null
    ? { ok: false, value: null }
    : { ok: true, value: client };
}

export function parseContentType(value) {
  if (typeof value !== "string" || value.length === 0) {
    return { mediaType: null, charset: null };
  }

  const parts = value.split(";");
  const mediaType = parts.shift().trim().toLowerCase();
  let charset = null;

  for (const part of parts) {
    const separator = part.indexOf("=");
    if (separator < 0) continue;
    const name = part.slice(0, separator).trim().toLowerCase();
    if (name !== "charset") continue;
    charset = part
      .slice(separator + 1)
      .trim()
      .replace(/^"|"$/g, "")
      .toLowerCase();
  }

  return { mediaType, charset };
}

export async function readBodyWithLimit(request, limit) {
  const contentLength = request.headers.get("content-length");
  if (contentLength !== null) {
    const declaredLength = Number(contentLength);
    if (Number.isFinite(declaredLength) && declaredLength > limit) {
      return { ok: false, tooLarge: true };
    }
  }

  if (request.body === null) {
    return { ok: true, bytes: new Uint8Array(0) };
  }

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
      return { ok: false, tooLarge: true };
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

export function jsonResponse(value, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8"
    }
  });
}

export function textResponse(value, status) {
  return new Response(value, {
    status,
    headers: {
      "Content-Type": "text/plain; charset=utf-8"
    }
  });
}

export function logEvent(event, fields = {}) {
  console.log(JSON.stringify({ event, ...fields }));
}

export function logWarning(event, fields = {}) {
  console.warn(JSON.stringify({ event, ...fields }));
}
