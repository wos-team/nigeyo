import { pathToFileURL } from "node:url";
import { createServer } from "node:http";
import { createHash } from "node:crypto";
import { sensitiveHeaders } from "node:http2";
import { setTimeout as delay } from "node:timers/promises";
import { deviceFingerprint, disabledSubscription, loadActivityUpdate, loadLiveSubscription } from "./live-registration.mjs";
import { installationStore } from "./installation-store.mjs";
import { workerObservability } from "./worker-observability.mjs";
import { createRegionalStateStore } from "./regional-state.mjs";
import { RegionalMonitor } from "../server/monitoring/service.mjs";
import { validSubscription } from "../server/monitoring/regions.mjs";
import { loadAPNsSecret, providerToken, readConfig, resetAPNsConnection, sendToAPNs } from "./apns-training/index.mjs";

import { classifyAPNs } from "./apns-training/transport.mjs";
import { activityContent, buildActivityStart, sendActivity, validActivityRegistration } from "./apns-training/activity-push.mjs";

const titles = {
  earthquake: "EARTHQUAKE_ALERT",
  Warning: "TSUNAMI_ALERT",
  MajorWarning: "TSUNAMI_ALERT",
  Watch: "TSUNAMI_WATCH",
};

// The existing monitor persists each regional decision before calling this sender.
// Never pass non-alert state changes, test events, or stale outbox entries to APNs.
export function buildRegionalPush(command, now = Date.now()) {
  const issuedAt = Date.parse(command.issuedAt);
  const expires = Math.floor((issuedAt + 120_000) / 1000);
  const title = command.hazard === "earthquake" ? titles.earthquake : titles[command.grade];
  if (!["earthquake", "tsunami"].includes(command.hazard) ||
      command.mode !== "live" || command.status !== "active" || !command.notify ||
      !Object.hasOwn(titles, command.hazard === "earthquake" ? "earthquake" : command.grade) ||
      !Number.isFinite(expires) || expires * 1000 <= now) return null;

  const matches = command.hazard === "earthquake" ? command.matches
    : command.matches.filter(({ region }) => command.tsunamiAreas?.[region] === command.grade);
  if (!matches.length) return null;
  const regions = [...new Set(matches.map(({ region }) => region))].join("、");
  const label = command.hazard === "earthquake" ? "緊急地震速報"
    : { Watch: "津波注意報", Warning: "津波警報", MajorWarning: "大津波警報" }[command.grade];
  const body = `登録地域（${regions}）に${label}が発表されました。気象庁・自治体の情報を確認してください。`;
  const { delivery: _delivery, ...publicCommand } = command;
  return {
    expires,
    payload: {
      aps: { alert: { title, body }, sound: "default" },
      nigeyoType: "regional-alert",
      regionalAlert: JSON.stringify({
        ...publicCommand, matches, sentAt: new Date(now).toISOString(), expiresAt: new Date(expires * 1000).toISOString(),
      }),
    },
  };
}

function activityRequestId(commandId, channel) {
  const bytes = createHash("sha256").update(commandId + ":" + channel).digest().subarray(0, 16);
  bytes[6] = (bytes[6] & 0x0f) | 0x50;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.toString("hex");
  return [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.slice(20)].join("-");
}

export async function runRegionalWorker({ stateStore, secret, send = sendToAPNs, WebSocketImpl, now = Date.now, log = console.log, subscriptionSource, activityUpdateSource, registry, refreshSigningSecret }) {
  let config = readConfig(secret, { signingOnly: !!registry });
  const devices = registry ? [] : config.devices ?? [{ deviceToken: config.deviceToken, subscription: JSON.parse(secret).subscription }];
  const installations = new Map(devices.map((device) => [
    device.id ? `aws-device-${deviceFingerprint(device)}` : "aws-demo-device", device,
  ]));
  const subscriptions = new Map();
  const monitor = new RegionalMonitor({
    stateStore, WebSocketImpl, now: () => new Date(now()), log,
    alwaysConnect: !!registry,
    durableDelivery: !!registry,
    onEvent: registry ? async (item) => {
      await refreshRegistry();
      await monitor.ingest(item);
    } : undefined,
  });
  const inFlight = new Set();
  const waiting = [];
  let activeDeliveries = 0;
  let refreshedAt = now();
  let refreshing;
  let token;
  let tokenIssuedAt;

  function dispatch(installation, device, command, fingerprint) {
    if (inFlight.has(command.commandId)) return;
    inFlight.add(command.commandId);
    waiting.push({ installation, device, command, fingerprint });
    drainDeliveries();
  }

  function drainDeliveries() {
    if (monitor.stopped) return;
    const limit = registry ? 32 : Infinity;
    while (activeDeliveries < limit && waiting.length) {
      const { installation, device, command, fingerprint } = waiting.shift();
      activeDeliveries++;
      deliver(installation, device, command, fingerprint).catch(() => log("regional-apns-retry")).finally(() => {
        inFlight.delete(command.commandId);
        activeDeliveries--;
        drainDeliveries();
      });
    }
  }

  async function signingToken(timestamp) {
    const seconds = Math.floor(timestamp / 1000);
    if (refreshSigningSecret && timestamp - refreshedAt >= 300_000) {
      refreshing ??= (async () => {
        try {
          const { SecretString } = await refreshSigningSecret();
          const next = readConfig(SecretString, { signingOnly: !!registry });
          if (config.keyId !== next.keyId || config.teamId !== next.teamId || !config.key.equals(next.key)) {
            token = undefined;
            resetAPNsConnection();
          }
          config = next;
        } catch {
          log("regional-signing-refresh-failed");
        } finally {
          refreshedAt = now();
          refreshing = undefined;
        }
      })();
      await refreshing;
    }
    if (!token || seconds - tokenIssuedAt >= 45 * 60) {
      token = providerToken(config, seconds);
      tokenIssuedAt = seconds;
    }
    return token;
  }

  async function deliver(installation, device, command, fingerprint) {
    const finish = () => monitor.acknowledge(installation, command.commandId, command);
    if (registry) {
      const latest = await registry.get(device.id);
      if (!latest?.subscription?.enabled) return finish();
      device = { id: device.id, ...latest };
    }
    const subscription = subscriptionSource ? await subscriptionSource(device) : device.subscription;
    if ((subscriptionSource || registry) && JSON.stringify(regionalSettings(subscription)) !== fingerprint) return finish();
    const entry = monitor.currentDelivery(installation, command);
    if (!entry || monitor.stopped) return;
    const timestamp = now();
    const deadline = Date.parse(command.issuedAt) + 120_000;
    if (!(timestamp < deadline)) return finish();

    const jwt = await signingToken(timestamp);
    const push = buildRegionalPush(command, timestamp);
    const activity = subscription?.activity;
    const seconds = Math.floor(timestamp / 1000);
    const current = () => !monitor.stopped && now() < deadline && monitor.currentDelivery(installation, command);
    const shouldEnd = (activityUpdateSource || registry) && validActivityRegistration(activity) &&
      (command.status === "cancelled" || (command.hazard === "tsunami" && command.grade === "Watch" &&
        Object.values(command.tsunamiAreas ?? {}).every((grade) => ["Watch", "Cancelled"].includes(grade))));
    const jobs = [];
    const notify = push && Buffer.byteLength(JSON.stringify(push.payload)) <= 4096;
    if (push && !notify) log("regional-apns-payload-too-large", { eventId: command.sourceEventId });
    if (notify) {
      jobs.push(["notification", device.deviceToken, () => send({
        ":method": "POST",
        ":path": `/3/device/${device.deviceToken}`,
        "content-type": "application/json",
        authorization: `bearer ${jwt}`,
        [sensitiveHeaders]: ["authorization", ":path"],
        "apns-topic": config.bundleId,
        "apns-push-type": "alert",
        "apns-priority": "10",
        "apns-id": command.commandId,
        "apns-collapse-id": command.commandId,
        "apns-expiration": String(push.expires),
      }, push.payload), undefined, device.deviceTokenGeneration]);
      if (command.grade !== "Watch" && validActivityRegistration(activity)) {
        jobs.push(activityJob("activityStart", activity.token, buildActivityStart({
          hazard: command.hazard, eventId: command.commandId, expiresAt: new Date(push.expires * 1000).toISOString(),
          timestamp: seconds, alert: { title: "nigeyo", body: push.payload.aps.alert.body },
          language: activity.language, regionalAlert: push.payload.regionalAlert,
        }), undefined, device.activityTokenGeneration));
      }
    }
    if (shouldEnd && !entry.delivery?.activityEnd?.done) {
      const update = registry ? device.activityUpdates?.[command.hazard] : await activityUpdateSource(device, command.hazard);
      if (!update || (command.hazard === "earthquake" && update.sourceEventId !== command.sourceEventId)) {
        jobs.push(["activityEnd"]);
      } else if (Date.parse(update.issuedAt) <= Date.parse(command.issuedAt)) {
        jobs.push(activityJob("activityEnd", update.token, { aps: {
          timestamp: seconds, event: "end", "dismissal-date": seconds,
          "content-state": { ...activityContent(command.hazard, activity.language, false), interactive: false },
        } }, update.sessionId, update.generation));
      }
    }

    function activityJob(channel, destination, payload, sessionId, tokenGeneration) {
      return [channel, destination, async () => {
        const response = await sendActivity({ send, config, jwt, token: destination, expires: Math.floor(deadline / 1000),
          eventId: command.commandId, requestId: activityRequestId(command.commandId, channel), payload });
        if (response.error === "activity_payload_too_large") return 413;
        return { status: response.apnsStatus, reason: response.apnsReason, timestamp: response.timestamp,
          retryAfterMs: response.retryAfterMs };
      }, sessionId, tokenGeneration];
    }

    const settled = await Promise.all(jobs.map(async ([channel, destination, work, sessionId, tokenGeneration]) => {
      const previous = entry.delivery?.[channel];
      if (previous?.done) return true;
      if (!work || previous?.retryAt > now() || !current()) return false;
      let result;
      try { result = classifyAPNs(await work()); }
      catch { result = classifyAPNs(); }
      const { status, reason, outcome, retryAfterMs } = result;
      log("regional-delivery-result", { channel, status, reason, outcome, commandId: command.commandId,
        eventId: command.sourceEventId, revision: command.sourceRevision });
      const refreshExpired = reason === "ExpiredProviderToken" &&
        (jwt !== token || Math.floor(now() / 1000) - tokenIssuedAt >= 1200);
      if (refreshExpired && jwt === token) token = undefined;
      const attempts = (previous?.attempts ?? 0) + 1;
      // Apple recommends waiting 15 minutes before retrying an APNs 5xx response.
      const retryDelay = Math.max(outcome === "apns-unavailable" ? 900_000 : 0,
        Math.min(30_000, 5000 * 2 ** Math.min(attempts - 1, 3)), retryAfterMs ?? 0);
      const retryable = ["transport", "rate-limited", "apns-unavailable"].includes(outcome) || refreshExpired;
      const retryAt = now() + retryDelay;
      const done = !retryable || retryAt >= deadline;
      if (outcome === "invalid-token" && registry?.invalidate) {
        await registry.invalidate(device.id, { channel, token: destination, tokenGeneration,
          hazard: command.hazard, sessionId, timestamp: result.timestamp });
      }
      await monitor.recordDelivery(installation, command, channel, { outcome, done, attempts, ...(!done && { retryAt }) });
      return done;
    }));
    if (!monitor.stopped && settled.every(Boolean)) await finish();
  }

  // Token rotations and language changes must not reset the monitor's deduplication history.
  function regionalSettings({ version, enabled, targets }) {
    return { version, enabled, targets };
  }

  try {
    await monitor.load();
    if (registry) await refreshRegistry();
    else {
      for (const id of Object.keys(monitor.records)) {
        if (!installations.has(id)) await monitor.configure(id, disabledSubscription, () => {});
      }
      for (const [id, device] of installations) await configure(id, device);
    }
  } catch (error) {
    monitor.stop();
    throw error;
  }
  if (subscriptionSource) void refreshSubscriptions();
  return monitor;

  async function refreshRegistry() {
    const devices = await registry.listDevices();
    const active = new Set(devices.map((device) => device.id));
    for (const id of Object.keys(monitor.records)) {
      if (!active.has(id)) {
        await monitor.forget(id);
        subscriptions.delete(id);
      }
    }
    for (const device of devices) await configure(device.id, device);
  }

  async function configure(id, device) {
    const subscription = subscriptionSource ? await subscriptionSource(device) : device.subscription;
    if (!validSubscription(subscription) || (!subscriptionSource && !subscription.enabled)) {
      throw new Error("Missing confirmed regional subscription");
    }
    const settings = regionalSettings(subscription);
    const fingerprint = JSON.stringify(settings);
    const signature = registry ? JSON.stringify([fingerprint, device.deviceToken]) : fingerprint;
    if (subscriptions.get(id) === signature) return;
    await monitor.configure(id, settings, (command) => dispatch(id, device, command, fingerprint));
    subscriptions.set(id, signature);
  }

  async function refreshSubscriptions() {
    while (!monitor.stopped) {
      await delay(3000);
      if (monitor.stopped) return;
      for (const [id, device] of installations) {
        try { await configure(id, device); }
        catch { log("regional-subscription-refresh-failed"); }
      }
    }
  }
}

export function workerHealthServer(monitor) {
  return createServer((_request, response) => {
    response.writeHead(monitor.stopped ? 503 : 204).end();
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const { SecretString } = await loadAPNsSecret();
    const registry = process.env.INSTALLATION_TABLE ? installationStore(process.env.INSTALLATION_TABLE) : undefined;
    const stateStore = registry?.state ?? createRegionalStateStore({ bucket: process.env.STATE_BUCKET });
    const subscriptionSource = !registry && process.env.APP_REGISTRATION_ENABLED === "true"
      ? (device) => loadLiveSubscription(process.env.STATE_BUCKET, device)
      : undefined;
    const activityUpdateSource = subscriptionSource
      ? (device, hazard) => loadActivityUpdate(process.env.STATE_BUCKET, device, hazard)
      : undefined;
    const monitor = await runRegionalWorker({ stateStore, secret: SecretString, subscriptionSource, activityUpdateSource, registry, refreshSigningSecret });
    const health = workerHealthServer(monitor);
    health.on("error", () => {
      console.error("regional-worker-health-server-failed");
      monitor.stop();
      clearInterval(report);
      resetAPNsConnection();
      process.exitCode = 1;
    });
    health.listen(8080, "127.0.0.1");
    process.on("SIGTERM", () => {
      monitor.stop();
      clearInterval(report);
      health.close();
      resetAPNsConnection();
    });
  } catch {
    console.error("regional-worker-startup-failed");
    process.exitCode = 1;
  }
}
