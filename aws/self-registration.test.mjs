import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";
import { EventEmitter } from "node:events";
import { test } from "node:test";
import { makeHandler } from "./apns-training/index.mjs";
import { installationStore } from "./installation-store.mjs";
import { selfRegistrationHandler } from "./self-registration.mjs";
import { runRegionalWorker } from "./regional-worker.mjs";

const area = { pref: "愛知", name: "愛知県西部" };
const subscription = { version: 1, enabled: true, language: "ja", targets: [{
  slot: "home.earthquake", siteId: "fixture", earthquakeArea: area,
  tsunamiStatus: "configured", tsunamiAreas: ["伊勢・三河湾"],
}] };

function localDatabase() {
  const items = new Map();
  const itemKey = (key) => key.kind.S + "/" + key.id.S;
  return {
    items,
    async send(command) {
      const input = command.input;
      const type = command.constructor.name;
      if (type === "GetItemCommand") return { Item: items.get(itemKey(input.Key)) };
      if (type === "QueryCommand") {
        const kind = input.ExpressionAttributeValues[":kind"].S;
        const rows = [...items.values()].filter((item) => item.kind.S === kind);
        const start = input.ExclusiveStartKey ? rows.findIndex((item) => item.id.S === input.ExclusiveStartKey.id.S) + 1 : 0;
        const page = rows.slice(start, start + 100);
        return { Items: page, LastEvaluatedKey: start + page.length < rows.length ? page.at(-1) : undefined };
      }
      const writes = input.TransactItems ?? [type === "PutItemCommand" ? { Put: input } : { Delete: input }];
      for (const { Put, Delete } of writes) {
        const write = Put ?? Delete;
        const old = items.get(itemKey(Put?.Item ?? Delete.Key));
        const values = write.ExpressionAttributeValues;
        const fails = (write.ConditionExpression === "attribute_not_exists(id)" && old) ||
          (values?.[":revision"] && values[":revision"].S !== old?.revision.S) ||
          (values?.[":owner"] && values[":owner"].S !== old?.data.S);
        if (fails) throw Object.assign(new Error(), { name: "ConditionalCheckFailedException" });
      }
      for (const { Put, Delete } of writes) {
        if (Put) items.set(itemKey(Put.Item), Put.Item);
        else items.delete(itemKey(Delete.Key));
      }
      return {};
    },
  };
}

function request(handler, rawPath, method, token, body) {
  return handler({ rawPath: "/v2/" + rawPath, requestContext: { http: { method } },
    headers: { authorization: "Bearer " + token }, body: JSON.stringify(body) });
}

test("self enrollment is idempotent, isolates installations, rotates tokens and avoids reviving old owners", async () => {
  const db = localDatabase();
  const store = installationStore("test", db);
  const handler = selfRegistrationHandler(store, { validUpdateToken: () => true });
  const enroll = async (seed) => {
    const result = await request(handler, "installations", "PUT", seed);
    assert.equal(result.statusCode, 200);
    return JSON.parse(result.body).credential;
  };
  const first = await enroll("a".repeat(64));
  assert.equal(await enroll("a".repeat(64)), first);
  const second = await enroll("b".repeat(64));
  const token = "12".repeat(32);
  const firstId = first.split(".")[0];
  const secondId = second.split(".")[0];
  const set = (auth, deviceToken) => request(handler, "subscription", "PUT", auth, { ...subscription, deviceToken });
  assert.equal((await set(firstId + "." + second.split(".")[1], token)).statusCode, 403);
  assert.equal((await set(first, token)).statusCode, 204);
  const stored = db.items.get("installation/" + firstId);
  assert.ok(!stored.data.S.includes(first.split(".")[1]));
  assert.equal((await set(first, token)).statusCode, 204);
  assert.equal(db.items.get("installation/" + firstId), stored);

  assert.equal((await set(second, token)).statusCode, 204);
  assert.deepEqual((await store.listDevices()).map(({ id }) => id), [secondId]);
  const rotated = "34".repeat(32);
  assert.equal((await set(second, rotated)).statusCode, 204);
  assert.equal((await store.get(secondId)).deviceToken, rotated);
  assert.equal(await store.get(firstId), undefined);

  assert.equal((await request(handler, "installations", "DELETE", second)).statusCode, 204);
  assert.equal((await request(handler, "installations", "DELETE", second)).statusCode, 204);
  assert.equal((await set(second, rotated)).statusCode, 403);
  assert.deepEqual(await store.listDevices(), []);
});

test("running worker adopts hundreds of self-registered installations and applies a revocation before delivery", async (t) => {
  const db = localDatabase();
  const store = installationStore("test", db);
  const handler = selfRegistrationHandler(store, { validUpdateToken: () => true });
  const { privateKey } = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  const secret = JSON.stringify({
    teamId: "TEAM123456", keyId: "KEY1234567", bundleId: "jp.nigeyo.hackathon",
    privateKey: privateKey.export({ format: "pem", type: "pkcs8" }),
  });
  class Feed extends EventEmitter { terminate() {} }
  const sent = [];
  let revoke;
  let drillTarget;
  const registry = {
    listDevices: () => store.listDevices(),
    async get(id) {
      if (revoke?.startsWith(id + ".")) {
        const credential = revoke;
        revoke = null;
        await request(handler, "installations", "DELETE", credential);
      }
      return store.get(id);
    },
  };
  const monitor = await runRegionalWorker({
    stateStore: store.state, registry, WebSocketImpl: Feed,
    now: () => Date.parse("2026-11-13T03:00:01+09:00"), log: () => {},
    send: async (headers) => { sent.push(headers[":path"]); return 200; },
    secret,
  });
  t.after(() => monitor.stop());
  assert.ok(monitor.ws);

  for (let i = 0; i < 305; i++) {
    const seed = i.toString(16).padStart(64, "0");
    const { body } = await request(handler, "installations", "PUT", seed);
    const { credential } = JSON.parse(body);
    const deviceToken = (i + 1).toString(16).padStart(64, "0");
    assert.equal((await request(handler, "subscription", "PUT", credential, {
      ...subscription, deviceToken,
    })).statusCode, 204);
    if (i === 0) revoke = credential;
    if (i === 4) drillTarget = credential.split(".")[0];
  }
  assert.equal(Object.keys(monitor.records).length, 0);
  const drillPaths = [];
  const sendDrill = makeHandler({
    loadSecret: async () => ({ SecretString: secret, VersionId: "test" }),
    installationSource: (id) => store.get(id),
    send: async (headers) => { drillPaths.push(headers[":path"]); return 200; },
  });
  assert.equal((await sendDrill({ hazard: "earthquake" })).accepted, false);
  assert.equal((await sendDrill({ hazard: "earthquake", installationId: drillTarget })).accepted, true);
  assert.deepEqual(drillPaths, ["/3/device/" + "5".padStart(64, "0")]);

  const quake = {
    code: 556, id: "quake", cancelled: false, areas: [area],
    issue: { time: "2026/11/13 03:00:00", eventId: "fixture", serial: "1" },
  };
  monitor.ws.emit("message", Buffer.from(JSON.stringify(quake)), false);
  await monitor.feedQueue;
  await new Promise((resolve) => setImmediate(resolve));
  await monitor.queue;
  assert.equal(sent.length, 304);
  assert.equal(new Set(sent).size, 304);
  assert.ok(!sent.includes("/3/device/" + "1".padStart(64, "0")));
  const snapshot = await store.state.load();
  assert.equal(Object.keys(snapshot.records).length, 305);
  assert.ok(Object.values(snapshot.records).every((record) => record.pending.length === 0));
});

test("a rotated token survives an old APNs invalidation and the current token can be disabled", async () => {
  const store = installationStore("test", localDatabase());
  const handler = selfRegistrationHandler(store, { validUpdateToken: () => true });
  const response = await request(handler, "installations", "PUT", "c".repeat(64));
  const { credential } = JSON.parse(response.body);
  const [id] = credential.split(".");
  const first = "ab".repeat(32);
  const second = "cd".repeat(32);
  assert.equal((await request(handler, "subscription", "PUT", credential, { ...subscription, deviceToken: first })).statusCode, 204);
  const old = await store.get(id);
  assert.equal((await request(handler, "subscription", "PUT", credential, { ...subscription, deviceToken: second })).statusCode, 204);
  assert.equal(await store.invalidate(id, { channel: "notification", token: old.deviceToken, tokenGeneration: old.deviceTokenGeneration }), false);
  const current = await store.get(id);
  assert.equal(current.deviceToken, second);
  assert.equal(await store.invalidate(id, { channel: "notification", token: current.deviceToken, tokenGeneration: current.deviceTokenGeneration }), true);
  assert.equal(await store.get(id), undefined);
  assert.deepEqual(await store.listDevices(), []);
});

test("a restarted worker resumes only the unfinished APNs channel and cancels an older pending revision", async (t) => {
  const store = installationStore("test", localDatabase());
  const handler = selfRegistrationHandler(store, { validUpdateToken: () => true });
  const { body } = await request(handler, "installations", "PUT", "d".repeat(64));
  const { credential } = JSON.parse(body);
  const [id] = credential.split(".");
  await request(handler, "subscription", "PUT", credential, { ...subscription, deviceToken: "ab".repeat(32),
    activity: { token: "ac".repeat(32), language: "ja" } });

  const { privateKey } = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  const secret = JSON.stringify({ teamId: "TEAM123456", keyId: "KEY1234567", bundleId: "jp.nigeyo.hackathon",
    privateKey: privateKey.export({ format: "pem", type: "pkcs8" }) });
  class Feed extends EventEmitter { terminate() {} }
  let clock = Date.parse("2026-11-13T03:00:01+09:00");
  let activityAvailable = false;
  const requests = [];
  let failCancellation = false;
  const stateStore = {
    load: store.state.load,
    saveChanges(changes, options) {
      if (failCancellation && changes[id]?.pending.some((job) => job.status === "cancelled")) {
        return new Set([id]);
      }
      return store.state.saveChanges(changes, options);
    },
  };
  const start = () => runRegionalWorker({
    stateStore, registry: store, WebSocketImpl: Feed, now: () => clock, log: () => {}, secret,
    send: async (headers) => {
      const type = headers[":path"].endsWith("ac".repeat(32)) ? "start" : "notification";
      requests.push({ type, id: headers["apns-id"] });
      return type === "start" && !activityAvailable ? { status: 429, reason: "TooManyRequests", retryAfterMs: 5000 } : 200;
    },
  });
  let monitor = await start();
  t.after(() => monitor.stop());
  const waitForReceipts = async () => {
    await new Promise((resolve) => setImmediate(resolve));
    await monitor.queue;
  };
  const publish = async (item) => {
    monitor.ws.emit("message", Buffer.from(JSON.stringify(item)), false);
    await monitor.feedQueue;
    await waitForReceipts();
  };
  const quake = { code: 556, id: "first", cancelled: false, areas: [area],
    issue: { time: "2026/11/13 03:00:00", eventId: "fixture", serial: "1" } };
  await publish(quake);
  const pending = (await store.state.load()).records[id].pending[0];
  assert.equal(pending.delivery.notification.outcome, "accepted");
  assert.equal(pending.delivery.activityStart.done, false);
  assert.equal(pending.delivery.activityStart.retryAt, clock + 5000);

  monitor.stop();
  clock += 5000;
  activityAvailable = true;
  monitor = await start();
  await waitForReceipts();
  assert.deepEqual(requests.map(({ type }) => type), ["notification", "start", "start"]);
  assert.equal(requests[1].id, requests[2].id);
  assert.equal(monitor.records[id].pending.length, 0);

  activityAvailable = false;
  const later = { ...quake, id: "later", issue: { ...quake.issue, eventId: "later-fixture" } };
  await publish(later);
  assert.equal(monitor.records[id].pending[0].notify, true);
  const cancellation = { ...later, id: "cancelled", cancelled: true, areas: [], issue: { ...later.issue, serial: "2" } };
  failCancellation = true;
  await publish(cancellation);
  clock += 5000;
  monitor.flush(id);
  await waitForReceipts();
  assert.equal(requests.length, 5, "a known cancellation fences old work even while storage is unavailable");
  failCancellation = false;
  await publish(cancellation);
  assert.equal(monitor.records[id].pending[0]?.status, "cancelled");
  clock += 5000;
  monitor.flush(id);
  await waitForReceipts();
  assert.equal(requests.length, 5, "cancelled activity-start is never retried");
});
