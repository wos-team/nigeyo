import { randomUUID } from "node:crypto";
import { enrollment, validToken } from "./installation-store.mjs";
import { validActivityRegistration } from "./apns-training/activity-push.mjs";
import { validSubscription } from "../server/monitoring/regions.mjs";

export function selfRegistrationHandler(store, { validUpdateToken }) {
  return async (event) => {
    const method = event.requestContext?.http?.method;
    const path = event.rawPath;
    const bearer = event.headers?.authorization?.match(/^Bearer ([a-f0-9]{64}(?:\.[a-f0-9]{64})?)$/)?.[1];
    if (!bearer) return { statusCode: 403 };

    if (path === "/v2/installations" && method === "PUT" && !bearer.includes(".")) {
      const { id, credential } = enrollment(bearer);
      await store.enroll(id, credential);
      return { statusCode: 200, headers: { "content-type": "application/json", "cache-control": "no-store" },
        body: JSON.stringify({ credential: id + "." + credential }) };
    }
    const [id, credential] = bearer.split(".");
    if (!credential) return { statusCode: 403 };

    if (path === "/v2/installations" && method === "DELETE") {
      return { statusCode: await store.remove(id, credential) ? 204 : 403 };
    }
    if (method !== "PUT") return { statusCode: 404 };
    if (event.isBase64Encoded || typeof event.body !== "string" || Buffer.byteLength(event.body) > 8192) {
      return { statusCode: 400 };
    }
    let body;
    try { body = JSON.parse(event.body); } catch { return { statusCode: 400 }; }

    if (path === "/v2/subscription") {
      if (!validSubscription(body) || !validToken(body.deviceToken) ||
          (body.activity !== undefined && !validActivityRegistration(body.activity))) return { statusCode: 400 };
      const { version, enabled, targets, activity, deviceToken, language } = body;
      if (!["ja", "en", "ko"].includes(language)) return { statusCode: 400 };
      const token = deviceToken.toLowerCase();
      const subscription = enabled ? { version, enabled, targets } : { version, enabled, targets: [] };
      if (enabled && activity) subscription.activity = { token: activity.token, language: activity.language };
      const result = await store.update(id, credential, (current) => {
        if (current.deviceToken === token && current.language === language &&
            JSON.stringify(current.subscription) === JSON.stringify(subscription)) return current;
        return { ...current, deviceToken: token, language, subscription,
          ...(current.deviceToken !== token && {
            deviceTokenGeneration: randomUUID(), deviceTokenUpdatedAt: Date.now(), invalidDeviceToken: undefined,
          }),
          ...(current.subscription?.activity?.token !== subscription.activity?.token && {
            activityTokenGeneration: randomUUID(), activityTokenUpdatedAt: Date.now(), invalidActivityToken: undefined,
          }),
          activityUpdates: enabled ? current.activityUpdates : undefined };
      });
      return { statusCode: result ? 204 : 403 };
    }
    if (path === "/v2/activity") {
      if (!validUpdateToken(body)) return { statusCode: 400 };
      const result = await store.update(id, credential, (current) => {
        const old = current.activityUpdates?.[body.hazard];
        if (!current.subscription?.enabled || (old && Date.parse(old.issuedAt) > Date.parse(body.issuedAt))) return current;
        const { hazard, token, issuedAt, sourceEventId, sessionId } = body;
        const update = { hazard, token, issuedAt, sourceEventId, sessionId };
        if (old && Object.entries(update).every(([name, value]) => old[name] === value)) return current;
        return { ...current, activityUpdates: {
          ...current.activityUpdates, [hazard]: { ...update, generation: randomUUID(), receivedAt: Date.now() },
        } };
      });
      return { statusCode: result ? 204 : 403 };
    }
    return { statusCode: 404 };
  };
}
