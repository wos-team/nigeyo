import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { test } from "node:test";
import { createOperationsHandler } from "./operations/handler.mjs";
import { workerObservability } from "./worker-observability.mjs";

test("operations requires an administrator and drills are confirmed against an allowlist and audited once", async () => {
  const token = "a".repeat(64);
  const id = "b".repeat(64);
  const audit = new Map();
  const sends = [];
  const handler = createOperationsHandler({
    loadConfig: async () => ({
      operators: { kishida: createHash("sha256").update(token).digest("hex") },
      testInstallations: { "テスト端末": id },
    }),
    store: {
      listDevices: async () => [{ id, deviceToken: "must not appear", subscription: { targets: [
        { earthquakeArea: { name: "愛知県西部" }, tsunamiAreas: ["伊勢・三河湾"] },
        { earthquakeArea: { name: "愛知県西部" }, tsunamiAreas: [] },
      ] } }],
      get: async (target) => target === id,
      operations: {
        recentSnapshots: async () => [{ timestamp: 1, feed: "connected", pending: 2, accepted: 1, failures: 0, recent: [] }],
        recentDrills: async () => [...audit.values()],
        claimDrill: async (entry) => {
          if (audit.has(entry.requestId)) return false;
          audit.set(entry.requestId, entry);
          return true;
        },
        finishDrill: async (entry) => audit.set(entry.requestId, entry),
      },
    },
    training: async (request) => { sends.push(request); return { accepted: true, apnsStatus: 200 }; },
  });

  const request = (path, credential, method = "GET", body) => handler({
    rawPath: path, requestContext: { http: { method } },
    headers: credential ? { authorization: "Bearer " + credential } : {}, body: JSON.stringify(body),
  });
  const ui = await request("/ops");
  assert.equal(ui.statusCode, 200);
  assert.match(ui.headers["content-security-policy"], /frame-ancestors 'none'/);
  assert.equal((await request("/ops/api/status")).statusCode, 401);
  assert.equal((await request("/ops/api/status", "c".repeat(64))).statusCode, 403);
  const data = await request("/ops/api/status", token);
  assert.doesNotMatch(data.body, /must not appear|bbbbbbbb/);
  assert.deepEqual(new Map(JSON.parse(data.body).regions), new Map([["津波 / 伊勢・三河湾", 1], ["地震 / 愛知県西部", 1]]));
  const drill = { target: "テスト端末", hazard: "earthquake", requestId: "80000000-8000-4000-a000-800000000000" };
  assert.equal((await request("/ops/api/drills", null, "POST", drill)).statusCode, 401);
  assert.equal((await request("/ops/api/drills", token, "POST", { ...drill, target: id })).statusCode, 400);
  assert.equal((await request("/ops/api/drills", token, "POST", drill)).statusCode, 200);
  assert.equal((await request("/ops/api/drills", token, "POST", drill)).statusCode, 409);
  assert.deepEqual(sends, [{ hazard: "earthquake", installationId: id }]);
  assert.equal(audit.get(drill.requestId).operator, "kishida");
  assert.equal(audit.get(drill.requestId).state, "accepted");
});

test("minute metrics flag a silent feed, preserve failure samples, and report old queued work", async () => {
  const lines = [];
  const samples = [];
  const metrics = workerObservability({ environment: "test", emit: (line) => lines.push(JSON.parse(line)),
    persist: (value) => samples.push(value), now: () => 60000 });
  metrics.log("regional-feed-unresponsive");
  metrics.log("regional-delivery-result", { outcome: "provider-auth", channel: "notification", eventId: "source", commandId: "command" });
  for (let i = 0; i < 300; i++) metrics.log("regional-delivery-result", { outcome: "accepted", eventId: "source" });
  await metrics.tick({ state: "reconnecting", records: { one: { pending: [{ issuedAt: new Date(0).toISOString() }] } } });
  assert.deepEqual([lines.at(-1).WorkerHeartbeat, lines.at(-1).FeedDown, lines.at(-1).OldestPendingSeconds, lines.at(-1).DeliveryFailures, lines.at(-1).SigningFailures],
    [1, 1, 60, 1, 1]);
  assert.deepEqual(lines.at(-1)._aws.CloudWatchMetrics[0].Dimensions, [["Environment"]]);
  assert.equal(samples[0].recent.length, 11);
  assert.ok(samples[0].recent.some((value) => value.commandId === "command"));
});

test("short operator keys require validation mode and a matching hash", async () => {
  const config = { operators: { tester: createHash("sha256").update("dev").digest("hex") } };
  const deps = { loadConfig: async () => config };
  const request = (key) => ({ rawPath: "/ops/api/status", requestContext: { http: { method: "GET" } },
    headers: { authorization: "Bearer " + key } });
  assert.equal((await createOperationsHandler(deps)(request("dev"))).statusCode, 401);
  const validation = createOperationsHandler({
    ...deps, allowShortKey: true,
    store: { listDevices: async () => [], operations: { recentSnapshots: async () => [], recentDrills: async () => [] } },
  });
  assert.equal((await validation(request("abc"))).statusCode, 403);
  assert.equal((await validation(request("dev"))).statusCode, 200);
});
