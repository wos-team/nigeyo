export function workerObservability({ environment, persist, emit = console.log, now = Date.now }) {
  let feed;
  let failures = 0;
  let accepted = 0;
  let signingFailures = 0;
  const recentAccepted = [];
  const recentFailures = [];
  const write = (value) => emit(JSON.stringify(value));
  function log(event, details = {}) {
    const timestamp = now();
    if (event === "regional-feed-disconnected" || event === "regional-feed-unresponsive") feed = "reconnecting";
    if (event === "regional-feed-connected") feed = "connected";
    if (event === "regional-delivery-result") {
      if (details.outcome === "accepted") accepted++;
      else failures++;
      if (details.outcome === "provider-auth") signingFailures++;
      const sample = details.outcome === "accepted" ? recentAccepted : recentFailures;
      sample.push({ timestamp, ...details });
      if (sample.length > 10) sample.shift();
      // Sample repeated per-device failures in the metric record instead of flooding logs.
      return;
    }
    write({ timestamp, event, ...details });
  }

  async function tick(monitor) {
    const timestamp = now();
    const pending = Object.values(monitor.records).flatMap((record) => record.pending);
    const age = Math.max(0, ...pending.map((entry) => (timestamp - Date.parse(entry.issuedAt)) / 1000).filter(Number.isFinite));
    const metrics = { WorkerHeartbeat: 1, FeedDown: monitor.state === "connected" || monitor.state === "stopped" ? 0 : 1,
      DeliveryFailures: failures, SigningFailures: signingFailures, OldestPendingSeconds: Math.floor(age) };
    const snapshot = { timestamp, feed: feed ?? monitor.state, accepted, failures, pending: pending.length,
      oldestPendingSeconds: metrics.OldestPendingSeconds, recent: [...recentAccepted.splice(0), ...recentFailures.splice(0)].sort((a, b) => a.timestamp - b.timestamp) };
    failures = 0;
    accepted = 0;
    signingFailures = 0;
    write({
      _aws: { Timestamp: timestamp, CloudWatchMetrics: [{ Namespace: "Nigeyo/Worker", Dimensions: [["Environment"]],
        Metrics: Object.keys(metrics).map((Name) => ({ Name, Unit: Name === "OldestPendingSeconds" ? "Seconds" : "Count" })) }] },
      Environment: environment, ...metrics, ...snapshot,
    });
    if (persist) {
      try { await persist(snapshot); }
      catch { log("regional-operations-write-failed"); }
    }
  }
  return { log, tick };
}
