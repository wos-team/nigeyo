import assert from "node:assert/strict";
import { once } from "node:events";
import { connect, createServer } from "node:http2";
import { test } from "node:test";
import { classifyAPNs, createAPNsTransport } from "./transport.mjs";

test("concurrent APNs requests reuse a session and recover after GOAWAY", async (t) => {
  const server = createServer();
  const sessions = [];
  server.on("session", (session) => sessions.push(session));
  server.on("stream", (stream, headers) => {
    stream.resume();
    stream.on("end", () => {
      if (headers[":path"] === "/expired") {
        stream.respond({ ":status": 410 });
        stream.end(JSON.stringify({ reason: "Unregistered", timestamp: 1000 }));
      } else {
        stream.respond({ ":status": 200 });
        stream.end();
      }
    });
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const clients = [];
  const transport = createAPNsTransport({ openConnection: () => {
    const client = connect("http://127.0.0.1:" + server.address().port);
    clients.push(client);
    return client;
  } });
  t.after(() => {
    transport.close();
    for (const session of sessions) session.destroy();
    server.close();
  });

  const send = (path = "/success") => transport.send({ ":method": "POST", ":path": path }, {});
  for (let i = 0; i < 10; i++) {
    const responses = await Promise.all(Array.from({ length: 30 }, () => send()));
    assert.ok(responses.every((response) => response.outcome === "accepted"));
  }
  assert.equal(sessions.length, 1);
  const expired = await send("/expired");
  assert.deepEqual([expired.status, expired.reason, expired.timestamp, expired.outcome], [410, "Unregistered", 1000, "invalid-token"]);
  const closed = once(clients[0], "goaway");
  sessions[0].goaway();
  await closed;
  assert.equal((await send()).status, 200);
  assert.equal(sessions.length, 2);
  assert.equal(classifyAPNs({ status: 403, reason: "InvalidProviderToken" }).outcome, "provider-auth");
  assert.equal(classifyAPNs(429).outcome, "rate-limited");
  assert.equal(classifyAPNs(503).outcome, "apns-unavailable");
});
