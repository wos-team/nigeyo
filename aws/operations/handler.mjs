import { createHash, timingSafeEqual } from "node:crypto";
import { readFileSync } from "node:fs";

const html = readFileSync(new URL("./index.html", import.meta.url), "utf8");
const script = readFileSync(new URL("./dashboard.js", import.meta.url), "utf8");
const css = readFileSync(new URL("./dashboard.css", import.meta.url), "utf8");
const assets = {
  "/ops": ["text/html; charset=utf-8", html],
  "/ops/dashboard.js": ["text/javascript; charset=utf-8", script],
  "/ops/dashboard.css": ["text/css; charset=utf-8", css],
};
const headers = {
  "cache-control": "no-store",
  "content-security-policy": "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
  "x-content-type-options": "nosniff",
  "referrer-policy": "no-referrer",
};

const reply = (statusCode, data) => ({
  statusCode, headers: { ...headers, "content-type": "application/json; charset=utf-8" },
  body: JSON.stringify(data),
});

export function createOperationsHandler({ loadConfig, store, training, allowShortKey = false, now = Date.now }) {
  const bearer = allowShortKey ? /^Bearer ([a-z0-9]{3,64})$/ : /^Bearer ([a-f0-9]{64})$/;
  return async (event) => {
    const path = event.rawPath;
    const method = event.requestContext?.http?.method;
    if (method === "GET" && Object.hasOwn(assets, path)) {
      const [type, body] = assets[path];
      return { statusCode: 200, headers: { ...headers, "content-type": type }, body };
    }
    const key = event.headers?.authorization?.match(bearer)?.[1];
    if (!key) return reply(401, { error: "authentication_required" });
    try {
      const config = await loadConfig();
      const digest = createHash("sha256").update(key).digest();
      const operator = Object.entries(config.operators ?? {}).find(([, hash]) => typeof hash === "string" &&
        /^[a-f0-9]{64}$/.test(hash) && timingSafeEqual(digest, Buffer.from(hash, "hex")))?.[0];
      if (!operator) return reply(403, { error: "forbidden" });
      const targets = Object.entries(config.testInstallations ?? {}).filter(([, id]) =>
        typeof id === "string" && /^[a-f0-9]{64}$/.test(id));
      if (method === "GET" && path === "/ops/api/status") {
        const [devices, snapshots, drills] = await Promise.all([
          store.listDevices(), store.operations.recentSnapshots(), store.operations.recentDrills(),
        ]);
        const ids = new Set(devices.map(({ id }) => id));
        const regions = new Map();
        for (const { subscription } of devices) {
          const names = new Set(subscription.targets.flatMap((target) => [
            "地震 / " + target.earthquakeArea.name, ...target.tsunamiAreas.map((name) => "津波 / " + name),
          ]));
          for (const name of names) regions.set(name, (regions.get(name) ?? 0) + 1);
        }
        return reply(200, {
          registered: devices.length, regions: [...regions].sort(([a], [b]) => a.localeCompare(b, "ja")),
          monitor: snapshots[0] && { feed: snapshots[0].feed, timestamp: snapshots[0].timestamp, pending: snapshots[0].pending },
          delivery: snapshots.map(({ timestamp, accepted, failures, recent }) => ({ timestamp, accepted, failures, recent })),
          testTargets: targets.filter(([, id]) => ids.has(id)).map(([name]) => name), drills,
        });
      }
      if (method === "POST" && path === "/ops/api/drills") {
        if (event.isBase64Encoded || typeof event.body !== "string" || Buffer.byteLength(event.body) > 1024) {
          return reply(400, { error: "invalid_request" });
        }
        let body;
        try { body = JSON.parse(event.body); } catch { return reply(400, { error: "invalid_request" }); }
        const { target, hazard, requestId } = body ?? {};
        const id = targets.find(([name]) => name === target)?.[1];
        if (!id || !["earthquake", "tsunami"].includes(hazard) || typeof requestId !== "string" ||
            !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(requestId)) return reply(400, { error: "invalid_drill" });
        if (!await store.get(id)) return reply(409, { error: "test_target_unavailable" });
        const timestamp = now();
        const entry = { timestamp, requestId, operator, target, hazard, state: "requested" };
        if (!await store.operations.claimDrill(entry)) return reply(409, { error: "duplicate_drill" });
        let response;
        try { response = await training({ hazard, installationId: id }); }
        catch { response = { accepted: false, error: "training_unavailable" }; }
        const result = {
          ...entry, state: response.accepted ? "accepted" : "failed", eventId: response.eventId,
          error: response.error, apnsStatus: response.apnsStatus, apnsReason: response.apnsReason,
          ...(response.liveActivity && { activityAccepted: response.liveActivity.accepted,
            activityStatus: response.liveActivity.apnsStatus, activityReason: response.liveActivity.apnsReason }),
        };
        await store.operations.finishDrill(result);
        return reply(200, result);
      }
      return reply(404, { error: "not_found" });
    } catch {
      // Configuration values, authorization and device records are never logged.
      console.error("operations-request-failed");
      return reply(503, { error: "operations_unavailable" });
    }
  };
}
