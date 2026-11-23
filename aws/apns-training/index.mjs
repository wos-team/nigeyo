import { createPrivateKey, randomUUID, sign } from "node:crypto";
import { sensitiveHeaders } from "node:http2";
import { GetSecretValueCommand, SecretsManagerClient } from "@aws-sdk/client-secrets-manager";

import { buildActivityStart, sendActivity, validActivityRegistration } from "./activity-push.mjs";
import { classifyAPNs, createAPNsTransport } from "./transport.mjs";

const drills = {
  earthquake: { title: "EARTHQUAKE_TEST", body: "【訓練】地震です。頭を守ってください。" },
  tsunami: { title: "TSUNAMI_TEST", body: "【訓練】津波です。高い場所へ避難してください。" },
};
const ttlSeconds = 120;
const transport = createAPNsTransport();
export const resetAPNsConnection = () => transport.close();
const secrets = new SecretsManagerClient({ maxAttempts: 1 });

function isDeviceToken(value) {
  return typeof value === "string" && /^(?:[a-fA-F0-9]{2})+$/.test(value);
}

function isRegion(value) {
  return typeof value === "string" && value.length > 0 && value.length <= 80 &&
    value === value.trim() && !/[\p{C}]/u.test(value);
}

function validTrainingRegions(regions) {
  return regions && Object.keys(regions).every((hazard) => Object.hasOwn(drills, hazard)) &&
    Object.values(regions).some((areas) => Array.isArray(areas) && areas.length) &&
    Object.values(regions).every((areas) => Array.isArray(areas) && areas.every(isRegion));
}

export function readConfig(value, { requireRegions = false, signingOnly = false } = {}) {
  const { teamId, keyId, privateKey, bundleId, deviceToken, devices } = JSON.parse(value);
  if (typeof teamId !== "string" || !/^[A-Z0-9]{10}$/.test(teamId) ||
      typeof keyId !== "string" || !/^[A-Z0-9]{10}$/.test(keyId) ||
      typeof bundleId !== "string" || !/^[a-zA-Z0-9-]+(?:\.[a-zA-Z0-9-]+)+$/.test(bundleId) ||
      (!signingOnly && devices === undefined && !isDeviceToken(deviceToken))) {
    throw new Error("Invalid APNs configuration");
  }
  const key = createPrivateKey(privateKey);
  if (key.asymmetricKeyType !== "ec" || key.asymmetricKeyDetails.namedCurve !== "prime256v1") {
    throw new Error("Invalid APNs key type");
  }
  if (signingOnly) return { teamId, keyId, key, bundleId };
  if (devices === undefined) return { teamId, keyId, key, bundleId, deviceToken };

  if (deviceToken !== undefined || !Array.isArray(devices) || devices.length < 1 || devices.length > 4) {
    throw new Error("Invalid APNs device list");
  }
  const ids = new Set();
  const tokens = new Set();
  for (const device of devices) {
    if (typeof device?.id !== "string" || !/^[A-Za-z0-9_-]{1,24}$/.test(device.id) ||
        !isDeviceToken(device.deviceToken) || ids.has(device.id) || tokens.has(device.deviceToken.toLowerCase()) ||
        ((requireRegions || device.regions !== undefined) && !validTrainingRegions(device.regions))) {
      throw new Error("Invalid APNs device");
    }
    ids.add(device.id);
    tokens.add(device.deviceToken.toLowerCase());
  }
  return { teamId, keyId, key, bundleId, devices };
}

export function providerToken({ keyId, teamId, key }, issuedAt) {
  const encode = (value) => Buffer.from(JSON.stringify(value)).toString("base64url");
  const claims = `${encode({ alg: "ES256", kid: keyId })}.${encode({ iss: teamId, iat: issuedAt })}`;
  const signature = sign("sha256", Buffer.from(claims), { key, dsaEncoding: "ieee-p1363" });
  return `${claims}.${signature.toString("base64url")}`;
}

export async function sendToAPNs(headers, payload, openConnection) {
  if (!openConnection) return transport.send(headers, payload);
  const custom = createAPNsTransport({ openConnection });
  try { return await custom.send(headers, payload); }
  finally { custom.close(); }
}

export function makeHandler({ loadSecret, subscriptionSource, installationSource, send = sendToAPNs, publishAndroid, now = Date.now }) {
  // Reuse the provider JWT across warm invocations. A new Secrets Manager version
  // invalidates the cache immediately without putting credentials in the environment.
  let cached;
  return async (event) => {
    if (!event || Object.keys(event).some((key) => key !== "hazard" && key !== "region" && !(installationSource && key === "installationId")) || !Object.hasOwn(event, "hazard") ||
        typeof event.hazard !== "string" || !Object.hasOwn(drills, event.hazard)) {
      return { accepted: false, error: "expected_earthquake_or_tsunami" };
    }
    if (Object.hasOwn(event, "region") && !isRegion(event.region)) {
      return { accepted: false, error: "invalid_training_region" };
    }

    if (installationSource && (typeof event.installationId !== "string" ||
        !/^[a-f0-9]{64}$/.test(event.installationId) || event.region !== undefined)) {
      return { accepted: false, error: "training_installation_required" };
    }
    const hazard = event.hazard;
    const eventId = randomUUID();
    let installation;
    if (installationSource) {
      try { installation = await installationSource(event.installationId); }
      catch { return { accepted: false, eventId, error: "installation_unavailable" }; }
      if (!installation?.subscription?.enabled) return { accepted: false, eventId, error: "installation_not_enabled" };
      if (installation.platform === "android") {
        if (!publishAndroid) return { accepted: false, eventId, error: "android_delivery_disabled" };
        const timestamp = now();
        const expires = timestamp + ttlSeconds * 1_000;
        try {
          const accepted = await publishAndroid(event.installationId, "training", {
            nigeyoType: "remote-training", eventId, hazard, expiresAt: new Date(expires).toISOString(),
          }, expires, timestamp);
          return { accepted, eventId, delivery: "android-inbox" };
        } catch {
          return { accepted: false, eventId, error: "android_inbox_failed" };
        }
      }
    }
    let config;
    let jwt;
    let issuedAt;
    try {
      const secret = await loadSecret();
      issuedAt = Math.floor(now() / 1000);
      if (!cached || cached.version !== secret.VersionId || issuedAt >= cached.issuedAt + 45 * 60) {
        config = readConfig(secret.SecretString, { requireRegions: !subscriptionSource, signingOnly: !!installationSource });
        jwt = providerToken(config, issuedAt);
        cached = { version: secret.VersionId, issuedAt, config, jwt };
      } else {
        ({ config, jwt } = cached);
      }
    } catch {
      return { accepted: false, eventId, error: "apns_configuration_unavailable" };
    }

    const sendDrill = async (deviceToken, region, apnsId = eventId, activity) => {
      const expires = issuedAt + ttlSeconds;
      const alert = drills[hazard];
      const payload = {
        aps: { alert: region ? { ...alert, body: `【訓練】${region}：${alert.body.slice("【訓練】".length)}` } : alert, sound: "default" },
        nigeyoType: "remote-training",
        eventId,
        hazard,
        expiresAt: new Date(expires * 1000).toISOString(),
      };
      const headers = {
        ":method": "POST",
        ":path": `/3/device/${deviceToken}`,
        "content-type": "application/json",
        authorization: `bearer ${jwt}`,
        [sensitiveHeaders]: ["authorization", ":path"],
        "apns-topic": config.bundleId,
        "apns-push-type": "alert",
        "apns-priority": "10",
        "apns-id": apnsId,
        "apns-expiration": String(expires),
      };
      const notification = (async () => {
        try {
          const result = classifyAPNs(await send(headers, payload));
          if (!Number.isInteger(result.status)) throw new Error("APNs response had no status");
          return { accepted: result.outcome === "accepted", apnsStatus: result.status,
            ...(result.reason && { apnsReason: result.reason }) };
        } catch {
          return { accepted: false, error: "apns_transport_failed" };
        }
      })();
      if (!validActivityRegistration(activity)) return notification;
      const activityPush = sendActivity({ send, config, jwt, token: activity.token, expires, eventId,
        payload: buildActivityStart({ hazard, eventId, expiresAt: payload.expiresAt,
          timestamp: issuedAt, alert: { title: "nigeyo", body: payload.aps.alert.body }, language: activity.language }),
      });
      const [result, liveActivity] = await Promise.all([notification, activityPush]);
      return { ...result, liveActivity };
    };

    if (installation) {
      return { ...await sendDrill(installation.deviceToken, undefined, eventId, installation.subscription.activity), eventId };
    }

    if (!config.devices && !subscriptionSource) {
      if (event.region) return { accepted: false, eventId, error: "regional_devices_not_configured" };
      return { ...await sendDrill(config.deviceToken), eventId };
    }
    if (!event.region) return { accepted: false, eventId, error: "training_region_required" };
    let matches;
    try {
      const devices = config.devices ?? [{ id: undefined, deviceToken: config.deviceToken }];
      const selected = await Promise.all(devices.map(async (device) => {
        if (!subscriptionSource) return device.regions?.[hazard]?.includes(event.region) ? { device } : null;
        const subscription = await subscriptionSource(device);
        const matchesRegion = subscription.enabled && subscription.targets.some((target) =>
          hazard === "earthquake"
            ? target.earthquakeArea.name === event.region
            : target.tsunamiAreas.includes(event.region));
        return matchesRegion ? { device, activity: subscription.activity } : null;
      }));
      matches = selected.filter(Boolean);
    } catch {
      return { accepted: false, eventId, error: "regional_subscriptions_unavailable" };
    }
    if (!matches.length) return { accepted: false, eventId, error: "no_devices_in_region" };
    const results = await Promise.all(matches.map(async ({ device, activity }) => ({
      id: device.id ?? "iphone-1",
      ...await sendDrill(device.deviceToken, event.region, randomUUID(), activity),
    })));
    return { accepted: results.every((result) => result.accepted), eventId, results };
  };
}

export function readAWSSecret(secretId) {
  return secrets.send(
    new GetSecretValueCommand({ SecretId: secretId }),
    { abortSignal: AbortSignal.timeout(3000) },
  );
}

export const loadAPNsSecret = () => readAWSSecret(process.env.APNS_SECRET_ARN);
let signingSecret;
export async function loadCachedAPNsSecret() {
  if (signingSecret && Date.now() < signingSecret.expires) return signingSecret.value;
  const value = await loadAPNsSecret();
  signingSecret = { value, expires: Date.now() + 300_000 };
  return value;
}

export const handler = makeHandler({ loadSecret: loadAPNsSecret });
