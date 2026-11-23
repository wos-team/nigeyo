const byId = (id) => document.getElementById(id);
const login = byId("login");
const dashboard = byId("dashboard");
const message = byId("message");
const target = byId("target");
const confirmation = byId("confirm");
let credential;
let pendingDrill;
let session = 0;

async function api(path, options = {}) {
  const response = await fetch("/ops/api/" + path, {
    ...options, headers: { authorization: "Bearer " + credential, "content-type": "application/json" },
    cache: "no-store",
  });
  if (!response.ok) throw new Error(String(response.status));
  return response.json();
}

function list(node, rows) {
  node.replaceChildren(...rows.map((row) => {
    const item = document.createElement("li");
    item.textContent = row;
    return item;
  }));
}
const time = (value) => new Intl.DateTimeFormat("ja", { timeStyle: "short" }).format(value);

function render(data) {
  byId("registered").textContent = String(data.registered);
  const feed = data.monitor && Date.now() - data.monitor.timestamp < 180000 ?
    ({ connected: "接続中", reconnecting: "再接続中", connecting: "接続処理中", stopped: "停止中" })[data.monitor.feed] : "応答を確認できません";
  byId("feed").textContent = feed ?? "不明";
  byId("pending").textContent = data.monitor?.pending ?? "—";
  list(byId("regions"), data.regions.map(([name, count]) => name + "　" + count));
  const totals = data.delivery.reduce(([ok, failure], row) => [ok + row.accepted, failure + row.failures], [0, 0]);
  byId("delivery-counts").textContent = `APNs受付 ${totals[0]} 件 / 失敗 ${totals[1]} 件`;
  const channel = { notification: "通常通知", activityStart: "Live Activity開始", activityEnd: "Live Activity終了" };
  const delivery = data.delivery.flatMap((row) => row.recent.toReversed()).slice(0, 20);
  list(byId("delivery"), delivery.map((row) =>
    [time(row.timestamp), channel[row.channel], row.reason || row.outcome, row.eventId, row.commandId].filter(Boolean).join(" / ")));
  list(byId("drills"), data.drills.map((row) =>
    [time(row.timestamp), row.operator, row.target, row.hazard === "earthquake" ? "地震" : "津波",
      ({ requested: "結果待ち", accepted: row.delivery === "android-inbox" ? "Android配信待ち" : "APNs受付", failed: "失敗" })[row.state],
      row.apnsReason ?? row.error,
      row.activityAccepted === undefined ? "" : row.activityAccepted ? "Live Activity受付" :
        "Live Activity失敗 " + (row.activityReason ?? row.activityStatus ?? "")].filter(Boolean).join(" / ")));
  const current = target.value;
  target.replaceChildren(...data.testTargets.map((name) => new Option(name, name)));
  if (data.testTargets.includes(current)) target.value = current;
  byId("preview").disabled = !data.testTargets.length;
}

async function refresh() {
  const generation = session;
  try {
    const data = await api("status");
    if (generation !== session) return;
    render(data);
    login.hidden = true;
    dashboard.hidden = false;
    message.textContent = "最終更新 " + time(Date.now());
  } catch (error) {
    if (generation !== session) return;
    if (["401", "403"].includes(error.message)) {
      resetSession();
      message.textContent = "運用キーを確認してください。";
    } else {
      message.textContent = "情報を取得できませんでした。";
    }
  }
}

login.addEventListener("submit", (event) => {
  event.preventDefault();
  credential = byId("key").value.trim();
  byId("key").value = "";
  session++;
  void refresh();
});
byId("refresh").addEventListener("click", () => { void refresh(); });
function resetSession() {
  credential = undefined;
  pendingDrill = undefined;
  session++;
  dashboard.hidden = true;
  login.hidden = false;
  message.textContent = "";
}
byId("logout").addEventListener("click", resetSession);
byId("drill").addEventListener("submit", (event) => {
  event.preventDefault();
  if (!target.value) return;
  const hazard = byId("hazard").value;
  pendingDrill = { target: target.value, hazard, requestId: crypto.randomUUID() };
  byId("confirmation").textContent = `${target.value} に${hazard === "earthquake" ? "地震" : "津波"}の訓練通知を送信します。`;
  confirmation.returnValue = "cancel";
  confirmation.showModal();
});
confirmation.addEventListener("close", async () => {
  if (confirmation.returnValue !== "send" || !pendingDrill) return;
  const drill = pendingDrill;
  const generation = session;
  pendingDrill = undefined;
  byId("preview").disabled = true;
  try {
    const result = await api("drills", { method: "POST", body: JSON.stringify(drill) });
    if (generation !== session) return;
    await refresh();
    if (generation === session) message.textContent = result.state === "accepted" ? "APNsが訓練通知を受け付けました。" : "訓練通知の受付を確認できませんでした。";
  } catch {
    if (generation === session) message.textContent = "送信結果を取得できませんでした。訓練履歴を確認してください。";
  } finally {
    if (generation === session) byId("preview").disabled = !target.options.length;
  }
});
