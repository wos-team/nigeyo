import { createHash, randomUUID, timingSafeEqual } from "node:crypto";
import { DeleteItemCommand, DynamoDBClient, GetItemCommand, PutItemCommand, QueryCommand, TransactWriteItemsCommand } from "@aws-sdk/client-dynamodb";

const digest = (value) => createHash("sha256").update(value).digest("hex");
const conditionalFailure = (error) => error.name === "ConditionalCheckFailedException" ||
  (error.name === "TransactionCanceledException" &&
    error.CancellationReasons?.some(({ Code }) => Code === "ConditionalCheckFailed" || Code === "TransactionConflict"));
const key = (kind, id) => ({ kind: { S: kind }, id: { S: id } });

export const validToken = (value) => typeof value === "string" && /^(?:[0-9a-f]{2}){16,256}$/i.test(value);

export function enrollment(bootstrap) {
  return {
    id: digest("installation:" + bootstrap),
    credential: digest("credential:" + bootstrap),
  };
}

export function authorized(record, credential) {
  return !!record && timingSafeEqual(Buffer.from(record.credentialHash), Buffer.from(digest(credential)));
}

export function installationStore(tableName, client = new DynamoDBClient({})) {
  const get = async (kind, id) => {
    const { Item } = await client.send(new GetItemCommand({ TableName: tableName, Key: key(kind, id), ConsistentRead: true }));
    return Item && JSON.parse(Item.data.S);
  };
  const putRequest = (kind, id, data, condition = {}) => ({
    TableName: tableName,
    Item: { ...key(kind, id), data: { S: JSON.stringify(data) }, revision: { S: data.revision ?? randomUUID() },
      ...(data.expiresAt && { expiresAt: { N: String(data.expiresAt) } }) },
    ...condition,
  });
  const put = (...args) => client.send(new PutItemCommand(putRequest(...args)));
  async function list(kind, limit) {
    const entries = [];
    let start;
    do {
      const page = await client.send(new QueryCommand({
        TableName: tableName, KeyConditionExpression: "kind = :kind",
        ExpressionAttributeValues: { ":kind": { S: kind } }, ConsistentRead: true, ExclusiveStartKey: start,
        ...(limit && { Limit: limit, ScanIndexForward: false }),
      }));
      for (const item of page.Items ?? []) entries.push([item.id.S, JSON.parse(item.data.S)]);
      start = limit ? undefined : page.LastEvaluatedKey;
    } while (start);
    return entries;
  }
  const erase = (kind, id) => client.send(new DeleteItemCommand({ TableName: tableName, Key: key(kind, id) }));
  const deliverable = (record) => record.subscription?.enabled && record.invalidDeviceToken !== record.deviceToken;
  const destination = (record) => record.invalidActivityToken === record.subscription.activity?.token &&
      record.subscription.activity ? { ...record, subscription: { ...record.subscription, activity: undefined } } : record;
  return {
    async publishAndroid(id, channel, payload, expires, timestamp = Date.now()) {
      if (!["earthquake", "tsunami", "training"].includes(channel) || expires <= timestamp) return false;
      const record = await get("installation", id);
      if (record?.platform !== "android" || !deliverable(record)) return false;
      const inboxId = id + "#" + channel;
      for (let attempt = 0; attempt < 4; attempt++) {
        const previous = await get("android-alert", inboxId);
        if (previous?.publishedAt > timestamp) return true;
        let delivered = payload;
        if (channel !== "training" && previous?.expiresAt * 1000 > timestamp && previous.payload?.regionalAlert) {
          const prior = JSON.parse(previous.payload.regionalAlert);
          const next = JSON.parse(payload.regionalAlert);
          if (prior.notify && next.status === "active" && next.grade === prior.grade &&
              next.sourceEventId === prior.sourceEventId && !next.notify) {
            delivered = { ...payload, regionalAlert: JSON.stringify({ ...next, notify: true }) };
          }
        }
        const value = { payload: delivered, publishedAt: timestamp, expiresAt: Math.ceil(expires / 1000), revision: randomUUID() };
        const condition = previous ? {
          ConditionExpression: "revision = :revision",
          ExpressionAttributeValues: { ":revision": { S: previous.revision } },
        } : { ConditionExpression: "attribute_not_exists(id)" };
        try {
          await put("android-alert", inboxId, value, condition);
          return true;
        } catch (error) {
          if (!conditionalFailure(error)) throw error;
        }
      }
      throw new Error("Concurrent Android delivery");
    },
    async pollAndroid(id, credential, now = Date.now()) {
      const record = await get("installation", id);
      if (!authorized(record, credential) || !record.subscription?.enabled || record.platform !== "android") return null;
      const entries = await Promise.all(["earthquake", "tsunami", "training"].map((channel) =>
        get("android-alert", id + "#" + channel)));
      return entries.filter((entry) => entry && entry.expiresAt * 1000 > now &&
        entry.publishedAt >= (record.deviceTokenUpdatedAt ?? 0))
        .sort((a, b) => a.publishedAt - b.publishedAt).map(({ payload }) => payload);
    },
    async invalidate(id, { channel, token, tokenGeneration, hazard, sessionId, timestamp }) {
      for (let attempt = 0; attempt < 4; attempt++) {
        const current = await get("installation", id);
        if (!current) return false;
        let next;
        if (channel === "notification" && current.deviceToken === token &&
            current.deviceTokenGeneration === tokenGeneration &&
            !(Number.isFinite(timestamp) && timestamp < (current.deviceTokenUpdatedAt ?? 0))) {
          next = { ...current, invalidDeviceToken: token };
        } else if (channel === "activityStart" && current.subscription?.activity?.token === token &&
            current.activityTokenGeneration === tokenGeneration &&
            !(Number.isFinite(timestamp) && timestamp < (current.activityTokenUpdatedAt ?? 0))) {
          next = { ...current, invalidActivityToken: token };
        } else if (channel === "activityEnd") {
          const update = current.activityUpdates?.[hazard];
          if (update?.token !== token || update.sessionId !== sessionId || update.generation !== tokenGeneration ||
              (Number.isFinite(timestamp) && timestamp < (update.receivedAt ?? 0))) return false;
          const activityUpdates = { ...current.activityUpdates };
          delete activityUpdates[hazard];
          next = { ...current, activityUpdates };
        } else return false;
        try {
          await put("installation", id, { ...next, revision: randomUUID() }, {
            ConditionExpression: "revision = :revision",
            ExpressionAttributeValues: { ":revision": { S: current.revision } },
          });
          return true;
        } catch (error) {
          if (!conditionalFailure(error)) throw error;
        }
      }
      throw new Error("Concurrent token invalidation");
    },
    async get(id) {
      const record = await get("installation", id);
      if (!record || !deliverable(record)) return undefined;
      const owner = await get("token", digest(record.deviceToken));
      return owner?.id === id ? destination(record) : undefined;
    },
    async enroll(id, credential) {
      try {
        await put("installation", id, { credentialHash: digest(credential), revision: randomUUID() },
          { ConditionExpression: "attribute_not_exists(id)" });
      } catch (error) {
        if (!conditionalFailure(error)) throw error;
      }
    },
    async update(id, credential, transform) {
      for (let attempt = 0; attempt < 4; attempt++) {
        const current = await get("installation", id);
        if (!authorized(current, credential)) return false;
        const next = transform(current);
        if (next === current) return true;
        try {
          const write = putRequest("installation", id, { ...next, revision: randomUUID() }, {
            ConditionExpression: "revision = :revision",
            ExpressionAttributeValues: { ":revision": { S: current.revision } },
          });
          if (next.deviceToken && next.deviceToken !== current.deviceToken) {
            const writes = [
              { Put: write },
              { Put: putRequest("token", digest(next.deviceToken), { id }) },
            ];
            if (current.deviceToken) {
              const previousKey = key("token", digest(current.deviceToken));
              const previous = await get("token", digest(current.deviceToken));
              if (previous?.id === id) writes.push({ Delete: {
                TableName: tableName, Key: previousKey, ConditionExpression: "#data = :owner",
                ExpressionAttributeNames: { "#data": "data" },
                ExpressionAttributeValues: { ":owner": { S: JSON.stringify(previous) } },
              } });
            }
            await client.send(new TransactWriteItemsCommand({ TransactItems: writes }));
          } else {
            await client.send(new PutItemCommand(write));
          }
          return true;
        } catch (error) {
          if (!conditionalFailure(error)) throw error;
        }
      }
      throw new Error("Concurrent installation updates");
    },
    async remove(id, credential) {
      for (let attempt = 0; attempt < 4; attempt++) {
        const current = await get("installation", id);
        if (!current) return true;
        if (!authorized(current, credential)) return false;
        const writes = [{ Delete: {
          TableName: tableName, Key: key("installation", id), ConditionExpression: "revision = :revision",
          ExpressionAttributeValues: { ":revision": { S: current.revision } },
        } }];
        if (current.deviceToken && (await get("token", digest(current.deviceToken)))?.id === id) {
          writes.push({ Delete: {
            TableName: tableName, Key: key("token", digest(current.deviceToken)),
            ConditionExpression: "#data = :owner", ExpressionAttributeNames: { "#data": "data" },
            ExpressionAttributeValues: { ":owner": { S: JSON.stringify({ id }) } },
          } });
        }
        try {
          await client.send(new TransactWriteItemsCommand({ TransactItems: writes }));
          return true;
        } catch (error) {
          if (!conditionalFailure(error)) throw error;
        }
      }
      throw new Error("Concurrent installation removal");
    },
    async listDevices() {
      const installations = await list("installation");
      if (!installations.length) return [];
      const owners = new Map(await list("token"));
      return installations.filter(([id, record]) => deliverable(record) &&
        owners.get(digest(record.deviceToken))?.id === id).map(([id, record]) => ({ id, ...destination(record) }));
    },
    operations: {
      async claimDrill(entry) {
        const data = { ...entry, expiresAt: Math.floor(entry.timestamp / 1000) + 7 * 86400 };
        try {
          const id = String(entry.timestamp).padStart(16, "0") + "#" + entry.requestId;
          await client.send(new TransactWriteItemsCommand({ TransactItems: [
            { Put: putRequest("ops-drill", entry.requestId, data, { ConditionExpression: "attribute_not_exists(id)" }) },
            { Put: putRequest("ops-audit", id, data) },
          ] }));
          return true;
        } catch (error) {
          if (!conditionalFailure(error)) throw error;
          return false;
        }
      },
      finishDrill(entry) {
        const id = String(entry.timestamp).padStart(16, "0") + "#" + entry.requestId;
        return put("ops-audit", id, { ...entry, expiresAt: Math.floor(entry.timestamp / 1000) + 7 * 86400 });
      },
      async recentDrills() {
        return (await list("ops-audit", 20)).map(([, value]) => value)
          .filter((value) => value.expiresAt > Date.now() / 1000).sort((a, b) => b.timestamp - a.timestamp).slice(0, 20);
      },
      saveSnapshot(snapshot) {
        const id = String(snapshot.timestamp).padStart(16, "0");
        return put("ops", id, { ...snapshot, expiresAt: Math.floor(snapshot.timestamp / 1000) + 86400 });
      },
      async recentSnapshots() {
        return (await list("ops", 15)).map(([, value]) => value).filter((value) => value.expiresAt > Date.now() / 1000);
      },
    },
    state: {
      async load() {
        return { version: 1, records: Object.fromEntries(await list("monitor")) };
      },
      async saveChanges(changes, { partial = false } = {}) {
        // Each record commits its warning state and outbox in a single write.
        const entries = Object.entries(changes);
        const failed = new Set();
        for (let offset = 0; offset < entries.length; offset += 20) {
          const batch = entries.slice(offset, offset + 20);
          const results = await Promise.allSettled(batch.map(([id, record]) =>
            record ? put("monitor", id, record) : erase("monitor", id)));
          for (const [index, result] of results.entries()) {
            if (result.status !== "rejected") continue;
            if (!partial) throw result.reason;
            failed.add(batch[index][0]);
          }
        }
        return failed;
      },
    },
  };
}
