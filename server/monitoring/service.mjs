import { join } from 'node:path';
import WebSocket from 'ws';
import { atomicWrite, readJsonOrNull, SCHEMA_VERSION } from '../demo/util.mjs';
import { evaluate, initialState } from './engine.mjs';
import { validSubscription } from './regions.mjs';

/** A single national feed, with isolated durable per-installation state and outboxes. */
export class RegionalMonitor {
  constructor({ stateDir, stateStore, alwaysConnect = false, durableDelivery = false, onEvent, feedTiming = {}, log = async () => {}, WebSocketImpl = WebSocket, now = () => new Date() }) {
    if (stateStore) {
      this.stateStore = stateStore;
    } else {
      const path = join(stateDir, 'regional-monitor.json');
      this.stateStore = {
        load: () => readJsonOrNull(path),
        save: (snapshot) => atomicWrite(path, JSON.stringify(snapshot)),
      };
    }
    this.log = log;
    this.alwaysConnect = alwaysConnect;
    this.durableDelivery = durableDelivery;
    this.updating = new Set();
    this.superseded = new Set();
    this.feedTiming = { pingMs: 20_000, retryMs: 5000, maxRetryMs: 60_000, jitter: Math.random, ...feedTiming };
    this.reconnectAttempts = 0;
    this.onEvent = onEvent ?? ((item) => this.ingest(item));
    this.feedQueue = Promise.resolve();
    this.WebSocketImpl = WebSocketImpl;
    this.now = now;
    this.records = {};
    this.clients = new Map();
    this.queue = Promise.resolve();
    this.state = 'stopped';
    this.stopped = false;
  }
  async load() {
    const saved = await this.stateStore.load();
    if (saved?.version === 1) this.records = saved.records ?? {};
    if (this.alwaysConnect || Object.values(this.records).some((r) => r.subscription?.enabled)) this.connect();
  }
  serial(work) {
    const result = this.queue.then(work);
    this.queue = result.catch((e) => this.log('regional-monitor-error', { reason: e.message }));
    return result;
  }
  save(id, records = this.records) {
    if (this.stateStore.saveChanges && id !== undefined) {
      return this.stateStore.saveChanges({ [id]: this.records[id] ?? null });
    }
    return this.stateStore.save({ version: 1, records });
  }
  forget(id) {
    return this.serial(async () => {
      this.clients.delete(id);
      const previous = this.records[id];
      if (!previous) return;
      delete this.records[id];
      try {
        await this.save(id);
        for (const entry of previous.pending) this.superseded.delete(JSON.stringify([entry.commandId, entry.sourceRevision ?? null]));
      }
      catch (error) { this.records[id] = previous; throw error; }
    });
  }
  configure(id, subscription, send) {
    return this.serial(async () => {
      if (!validSubscription(subscription)) subscription = { version: 1, enabled: false, targets: [] };
      const fingerprint = JSON.stringify(subscription);
      const old = this.records[id];
      if (!old || old.fingerprint !== fingerprint) {
        this.records[id] = { subscription, fingerprint, state: initialState(), pending: [] };
        try {
          if (!this.stateStore.saveChanges) await this.save(id);
          for (const entry of old?.pending ?? []) this.superseded.delete(JSON.stringify([entry.commandId, entry.sourceRevision ?? null]));
        }
        catch (e) { if (old) this.records[id] = old; else delete this.records[id]; throw e; }
      }
      this.clients.set(id, send);
      if (subscription.enabled) this.connect();
      this.flush(id);
    });
  }
  detach(id, send) { if (this.clients.get(id) === send) this.clients.delete(id); }
  acknowledge(id, commandId, expected) {
    return this.serial(async () => {
      const r = this.records[id];
      if (!r) return;
      const previous = r.pending;
      r.pending = previous.filter((p) => p.commandId !== commandId ||
        (expected && (p.issuedAt !== expected.issuedAt || p.sourceRevision !== expected.sourceRevision)));
      if (r.pending.length === previous.length) return;
      try { await this.save(id); } catch (e) { r.pending = previous; throw e; }
    });
  }
  recordDelivery(id, command, channel, result) {
    return this.serial(async () => {
      const record = this.records[id];
      const entry = record?.pending.find((value) => value.commandId === command.commandId && value.issuedAt === command.issuedAt && value.sourceRevision === command.sourceRevision);
      const current = entry ?? (result.outcome === "accepted" &&
        record?.pending.find((value) => value.commandId === command.commandId));
      if (!current) return false;
      const previous = current.delivery;
      current.delivery = { ...previous, [channel]: result };
      try { await this.save(id); }
      catch (error) { current.delivery = previous; throw error; }
      return true;
    });
  }
  currentDelivery(id, command) {
    if (this.updating.has(id)) return undefined;
    const revision = JSON.stringify([command.commandId, command.sourceRevision ?? null]);
    if (this.superseded.has(revision)) {
      if (+this.now() < Date.parse(command.issuedAt) + 120_000) return undefined;
      this.superseded.delete(revision);
    }
    return this.records[id]?.pending.find((entry) => entry.commandId === command.commandId &&
      entry.issuedAt === command.issuedAt && entry.sourceRevision === command.sourceRevision);
  }
  ingest(item, { source = 'live' } = {}) {
    return this.serial(async () => {
      const changed = [];
      const replaced = new Map();
      const modified = [];
      // Do not mutate committed state until its replacement has been persisted.
      const previous = this.records;
      const nextRecords = { ...previous };
      for (const [id, r] of Object.entries(previous)) {
        const result = evaluate(item, r.subscription, r.state, { now: this.now(), source });
        if (result.state === r.state && !result.action) continue;
        const next = { ...r, state: result.state };
        nextRecords[id] = next;
        modified.push(id);
        let action = result.action;
        const sameEvent = (entry) => entry.hazard === (item.code === 556 ? 'earthquake' : 'tsunami') &&
          (item.code !== 556 || entry.sourceEventId === item.issue.eventId);
        const previousAlert = r.pending.find(sameEvent);
        if (this.durableDelivery && item.code === 556 && previousAlert?.notify && !action && result.update) {
          action = { ...result.update, notify: true, commandId: previousAlert.commandId, delivery: previousAlert.delivery };
        }
        if (this.durableDelivery && item.code === 552 && previousAlert?.notify && action &&
          !action.notify && action.status === 'active' && action.grade === previousAlert.grade) {
          action = { ...action, notify: true, commandId: previousAlert.commandId, delivery: previousAlert.delivery };
        }
        if (this.durableDelivery && action) action = {
          ...action, sourceRevision: item.code === 556 ? Number(item.issue.serial) : item.id,
        };
        if (action || (this.durableDelivery && previousAlert)) {
          if (previousAlert) replaced.set(id, JSON.stringify([previousAlert.commandId, previousAlert.sourceRevision ?? null]));
          next.pending = r.pending.filter((entry) => !sameEvent(entry));
          if (action) next.pending.push(action);
          changed.push(id);
        }
      }
      for (const id of changed) this.updating.add(id);
      try {
        if (this.stateStore.saveChanges) {
          const failed = await this.stateStore.saveChanges(Object.fromEntries(modified.map((id) => [id, nextRecords[id]])),
            { partial: true });
          for (const id of failed) {
            nextRecords[id] = previous[id];
            if (replaced.has(id)) this.superseded.add(replaced.get(id));
          }
          for (const [id, revision] of replaced) if (!failed.has(id)) this.superseded.delete(revision);
          this.records = nextRecords;
          for (const id of changed) this.updating.delete(id);
          for (const id of changed) if (!failed.has(id)) this.flush(id);
          if (failed.size) throw new Error("Some regional monitor records could not be saved");
        } else {
          await this.save(undefined, nextRecords);
          this.records = nextRecords;
          for (const id of changed) this.updating.delete(id);
          for (const id of changed) this.flush(id);
        }
      } finally {
        for (const id of changed) this.updating.delete(id);
      }
      const eventId = item.code === 556 ? item.issue?.eventId : item.id;
      await this.log('regional-evaluated', { code: item.code, eventId: typeof eventId === 'string' ? eventId.slice(0, 128) : undefined,
        matchedInstallations: changed.length, source });
    });
  }
  flush(id) {
    const r = this.records[id], send = this.clients.get(id);
    if (!r?.subscription.enabled || !send || this.updating.has(id)) return;
    for (const action of r.pending) {
      if (!this.durableDelivery && !this.stateStore.saveChanges && action.hazard === 'earthquake' && action.status === 'active' && this.now() - new Date(action.issuedAt) > 120_000) continue;
      const now = this.now();
      send({ schemaVersion: SCHEMA_VERSION, ...action, sentAt: now.toISOString(), expiresAt: new Date(+now + 20_000).toISOString() });
    }
  }
  connect() {
    if (this.stopped || this.ws || (!this.alwaysConnect && !Object.values(this.records).some((r) => r.subscription.enabled))) return;
    this.state = 'connecting';
    const ws = new this.WebSocketImpl('wss://api.p2pquake.net/v2/ws', {
      maxPayload: 1024 * 1024, handshakeTimeout: 15_000,
    });
    this.ws = ws;
    let waitingForPong = false;
    const healthy = () => {
      waitingForPong = false;
      this.reconnectAttempts = 0;
    };
    ws.on('open', () => {
      if (this.ws !== ws || this.stopped) return;
      this.state = 'connected';
      this.log('regional-feed-connected', {});
      if (typeof ws.ping !== 'function') return;
      this.feedHeartbeat = setInterval(() => {
        if (waitingForPong) {
          this.log('regional-feed-unresponsive', {});
          ws.terminate();
          return;
        }
        waitingForPong = true;
        try { ws.ping(); }
        catch { ws.terminate(); }
      }, this.feedTiming.pingMs);
      this.feedHeartbeat.unref?.();
    });
    ws.on('pong', healthy);
    ws.on('message', (data, binary) => {
      if (this.ws !== ws || this.stopped || binary) return;
      healthy();
      let item;
      try { item = JSON.parse(data.toString('utf8')); } catch { return; }
      if ([556, 552].includes(item?.code)) {
        this.feedQueue = this.feedQueue.then(() => {
          if (!this.stopped) return this.onEvent(item);
        }).catch(() => this.log('regional-ingest-failed', { code: item.code }));
      }
    });
    ws.on('error', () => this.log('regional-feed-error', {}));
    ws.on('close', (code) => {
      if (this.ws !== ws) return;
      clearInterval(this.feedHeartbeat);
      this.ws = null;
      if (this.stopped) return;
      this.state = 'reconnecting';
      // Only a healthy response resets the backoff. Disconnects keep warning state.
      const base = this.feedTiming.retryMs * 2 ** Math.min(this.reconnectAttempts++, 6);
      const retryMs = Math.round(Math.min(this.feedTiming.maxRetryMs, base * (1 + this.feedTiming.jitter() * 0.25)));
      this.log('regional-feed-disconnected', { code, retryMs });
      this.retry = setTimeout(() => this.connect(), retryMs);
      this.retry.unref?.();
    });
    if (!this.deliveryTimer) {
      this.deliveryTimer = setInterval(() => { for (const id of this.clients.keys()) this.flush(id); }, 5000);
      this.deliveryTimer.unref?.();
    }
  }
  status(id) {
    const record = this.records[id];
    return { feed: this.state, enabled: record?.subscription.enabled ?? false, targets: record?.subscription.targets ?? [], state: record?.state ?? null, pending: record?.pending.length ?? 0 };
  }
  stop() {
    this.stopped = true;
    this.state = 'stopped';
    clearTimeout(this.retry);
    clearInterval(this.deliveryTimer);
    clearInterval(this.feedHeartbeat);
    this.ws?.terminate();
    this.ws = null;
  }
}
