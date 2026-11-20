import { connect, constants } from "node:http2";

const origin = "https://api.push.apple.com";
const reasons = new Set([
  "BadDeviceToken", "DeviceTokenNotForTopic", "ExpiredToken", "Unregistered", "ExpiredProviderToken",
  "InvalidProviderToken", "MissingProviderToken", "UnrelatedKeyIdInToken", "BadEnvironmentKeyIdInToken",
  "Forbidden", "TooManyRequests", "TooManyProviderTokenUpdates", "PayloadTooLarge", "Shutdown",
  "InternalServerError", "ServiceUnavailable",
]);

export function classifyAPNs(response) {
  const { status, reason, timestamp, retryAfterMs } = typeof response === "number" ? { status: response } : response ?? {};
  const safeReason = reasons.has(reason) ? reason : undefined;
  let outcome = "rejected";
  if (status === 200) outcome = "accepted";
  else if (status === 410 || ["BadDeviceToken", "DeviceTokenNotForTopic"].includes(safeReason)) outcome = "invalid-token";
  else if (status === 403 || safeReason === "TooManyProviderTokenUpdates") outcome = "provider-auth";
  else if (status === 429) outcome = "rate-limited";
  else if (status >= 500) outcome = "apns-unavailable";
  else if (!Number.isInteger(status)) outcome = "transport";
  return { status, reason: safeReason, outcome, timestamp, retryAfterMs };
}

export function createAPNsTransport({ openConnection = connect, timeoutMs = 5000 } = {}) {
  let current;
  function retire(session) {
    if (current === session) current = undefined;
    session.close();
  }
  function connection() {
    if (current && !current.closed && !current.destroyed) return current;
    const session = openConnection(origin);
    current = session;
    session.unref?.();
    session.on("error", () => retire(session));
    session.on("goaway", () => retire(session));
    session.once("close", () => { if (current === session) current = undefined; });
    return session;
  }
  return {
    send(headers, payload) {
      return new Promise((resolve, reject) => {
        const session = connection();
        session.ref?.();
        let stream;
        try { stream = session.request(headers); }
        catch {
          retire(session);
          reject(new Error("APNs stream unavailable"));
          return;
        }
        let response;
        let size = 0;
        let body = "";
        let settled = false;
        const timer = setTimeout(() => {
          retire(session);
          finish(new Error("APNs request timed out"));
          stream.close(constants.NGHTTP2_CANCEL);
        }, timeoutMs);
        function finish(error, value) {
          if (settled) return;
          settled = true;
          clearTimeout(timer);
          session.unref?.();
          if (error) reject(error);
          else resolve(value);
        }
        stream.setEncoding("utf8");
        stream.once("response", (value) => { response = value; });
        stream.on("data", (chunk) => {
          size += Buffer.byteLength(chunk);
          if (size <= 2048) body += chunk;
        });
        stream.once("error", () => finish(new Error("APNs stream failed")));
        stream.once("close", () => finish(new Error("APNs stream closed")));
        stream.once("end", () => {
          const status = response?.[":status"];
          if (!Number.isInteger(status)) return finish(new Error("APNs response unavailable"));
          let details = {};
          if (body && size <= 2048) {
            try { details = JSON.parse(body) ?? {}; } catch { /* Status remains usable. */ }
          }
          const retryAfter = response["retry-after"];
          const seconds = Number(retryAfter);
          const retryAfterMs = retryAfter === undefined ? undefined :
            Number.isFinite(seconds) ? Math.max(0, seconds * 1000) : Math.max(0, Date.parse(retryAfter) - Date.now());
          finish(null, classifyAPNs({ status, reason: details.reason, timestamp: details.timestamp,
            retryAfterMs: Number.isFinite(retryAfterMs) ? retryAfterMs : undefined }));
        });
        stream.end(JSON.stringify(payload));
      });
    },
    close() { if (current) retire(current); },
  };
}
