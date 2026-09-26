// SPDX-License-Identifier: Apache-2.0
/**
 * MultiplexedStream — tempdoc 662's cross-channel SSE fan-out.
 *
 * Opens ONE underlying `EnvelopeStream` connection — reusing its reconnect / heartbeat-
 * absence-watchdog liveness / `originContact` bump UNCHANGED — to a multiplexed endpoint
 * (e.g. `/api/shell-events/stream`), and demuxes every received frame by its `streamId` to
 * the matching registered consumer's reducer. N logical streams (intent, the two advisory
 * classes, action-ledger, indexing-jobs) share ONE physical connection instead of N
 * EventSources, which is the actual fix for the browser's ~6-per-host connection-pool
 * exhaustion that starved the cheap `/api/status`/`/api/inference/status` polls under load
 * (tempdoc 649).
 *
 * Per-stream resume is REUSED, not reinvented: each frame's `resumeToken` is already the
 * server's per-channel `streamId:seq` token (`ResumeTokenCodec`, backend
 * `MultiplexedSseWriter`). On (re)connect, the bundle of every currently-registered stream's
 * last-known resumeToken is comma-joined into the `?since=` query param via
 * `EnvelopeStream`'s `resumeTokenProvider` hook — no new codec on either side.
 *
 * Subscription shape mirrors `EnvelopeStreamPool.subscribePooled` (ref-counted: the first
 * subscriber for a `streamId` creates the demux entry from `createConfig`; the last
 * unsubscribe removes it), so migrating an existing `new EnvelopeStream({...}).subscribe(l)`
 * call site to `multiplex.subscribe(streamId, () => ({...}), l)` is a small, local edit.
 *
 * Tempdoc 662 post-implementation fix (critical-analysis pass): the backend sends its
 * one-time `connected`+`snapshot` burst for every channel at the moment the ONE physical
 * connection opens — a moment `Shell.connectedCallback()` triggers eagerly, independent of
 * consumer readiness. Two migrated consumers (`AdvisoryStore`, the intent bridge via
 * `BackendStreamSource`) register their `subscribe()` call only after an unrelated async
 * fetch resolves (the Resource Catalog, the surface-schema fetch respectively) — an
 * unsynchronized race against the connection's own opening. Pre-662, this was impossible by
 * construction (each consumer opened its OWN connection exactly when ready); consolidating
 * onto one eagerly-opened connection broke that guarantee. The fix: `subscribe()` detects a
 * genuinely NEW entry registered after the connection has already completed its first
 * connect (`lastConnected === true`) and schedules a debounced reconnect of the shared
 * transport, so the server's next connect burst is received with the late consumer already
 * registered. This reuses the EXISTING resume protocol unchanged — the late entry has no
 * resumeToken yet, so it is naturally excluded from the `?since=` bundle and the server
 * already treats a bundle-absent channel as "first-time subscriber, send a fresh snapshot"
 * (see `MultiplexedSseWriter.attachAll`); already-flowing entries resume normally via their
 * existing tokens. Debounced (not immediate) because a single synchronous catalog-reconcile
 * loop can register several channels back-to-back — without coalescing, each would trigger
 * its own reconnect.
 */

import { EnvelopeStream } from './EnvelopeStream.js';
import type {
  EnvelopeReducer,
  EnvelopeStreamConfig,
  EnvelopeStreamListener,
  EnvelopeStreamSnapshot,
} from './EnvelopeStream.js';
import type { SseEnvelope } from './envelope-types.js';

// Detect dev mode safely (Vite sets this via import.meta.env) — mirrors api/schemas.ts's
// IS_DEV guard so this module doesn't depend on ImportMetaEnv being typed for every consumer.
const IS_DEV = (() => {
  try {

    return (import.meta as any).env?.DEV === true;
  } catch {
    return false;
  }
})();

interface StreamEntry<T> {
  payload: T;
  seq: number;
  resumeToken: string | null;
  reducer: EnvelopeReducer<T>;
  listeners: Set<EnvelopeStreamListener<T>>;
  refs: number;
}

/** Constructor configuration — the subset of `EnvelopeStreamConfig` that applies to the
 * shared physical transport (no `initialState`/`reducer`/`initialResumeToken`/
 * `resumeTokenProvider`: those are per-demuxed-stream or computed internally). */
export type MultiplexedStreamConfig = Pick<
  EnvelopeStreamConfig<null>,
  'url' | 'eventSourceFactory' | 'watchdogStaleMs' | 'reconnectBaseMs' | 'reconnectCapMs'
> & {
  /** Debounce window (ms) for the late-subscribe reconnect (see class doc). Default 50ms.
   * Test seam: set to 0 for an immediate (synchronous-after-microtask) reconnect, or a small
   * value to exercise the debounce with real timers. */
  reconnectDebounceMs?: number;
};

export class MultiplexedStream {
  private readonly inner: EnvelopeStream<null>;
  private readonly entries = new Map<string, StreamEntry<unknown>>();
  /** Cached physical-connection state, updated ONLY by the inner subscription below — see
   * the class-level note on why routeFrame must read this cache, not `inner.getSnapshot()`
   * directly (the inner stream flips `isConnected` AFTER invoking the reducer). */
  private lastConnected = false;
  private readonly reconnectDebounceMs: number;
  /** Pending debounced late-subscribe reconnect timer, or null when none is scheduled. */
  private reconnectDebounceTimer: ReturnType<typeof setTimeout> | null = null;
  /** True between `start()` and `stop()` — gates `nudgeReconnect` so a consumer-requested
   * reconnect can never resurrect a connection the owner intentionally closed. */
  private started = false;

  constructor(config: MultiplexedStreamConfig) {
    this.reconnectDebounceMs = config.reconnectDebounceMs ?? 50;
    this.inner = new EnvelopeStream<null>({
      url: config.url,
      initialState: null,
      reducer: (_state, envelope) => {
        this.routeFrame(envelope);
        return null;
      },
      eventSourceFactory: config.eventSourceFactory,
      watchdogStaleMs: config.watchdogStaleMs,
      reconnectBaseMs: config.reconnectBaseMs,
      reconnectCapMs: config.reconnectCapMs,
      resumeTokenProvider: () => this.bundleResumeToken(),
    });
    // The SOLE place `lastConnected` is updated. Fires on every inner notify — including the
    // ones already triggered by routeFrame's reducer call (same isConnected value at that
    // point, so `changed` is false and no extra broadcast happens) AND on connection-only
    // events the reducer never sees (open/error/watchdog timeout) — see class doc.
    this.inner.subscribe((snapshot) => {
      const changed = snapshot.isConnected !== this.lastConnected;
      this.lastConnected = snapshot.isConnected;
      if (changed) {
        this.broadcastConnectionChange();
      }
    });
  }

  /** Opens the shared physical connection. Idempotent (delegates to `EnvelopeStream.start`). */
  start(): void {
    this.started = true;
    this.inner.start();
  }

  /** Closes the shared physical connection. Idempotent. Cancels any pending late-subscribe
   * reconnect (an intentional stop() supersedes it — the caller no longer wants a connection). */
  stop(): void {
    this.started = false;
    if (this.reconnectDebounceTimer !== null) {
      clearTimeout(this.reconnectDebounceTimer);
      this.reconnectDebounceTimer = null;
    }
    this.inner.stop();
  }

  /**
   * Re-establish the shared physical connection NOW, at a consumer's request — the actuator for
   * a per-channel stall (tempdoc 798 B4).
   *
   * `EnvelopeStream`'s heartbeat-absence watchdog and reconnect backoff operate on the PHYSICAL
   * connection: ANY frame on ANY multiplexed channel re-arms the watchdog. So a single wedged
   * channel is invisible to it — the backend stops emitting `surface:indexing-jobs` frames while
   * advisories/intent/ledger keep flowing, the transport is provably alive, and only one demuxed
   * channel is dead. Nothing at the transport layer can observe that; only a consumer holding a
   * per-channel liveness signal can (`indexingJobsBridge`'s `isFeedStalled`), and before this
   * method it had nowhere to route that observation — the UI rendered "reconnecting…" while no
   * code reconnected anything.
   *
   * Mechanism is `scheduleLateSubscribeReconnect`'s, unchanged: `inner.stop()` + `inner.start()`,
   * so the `?since=` bundle is rebuilt from the CURRENT entries and every already-flowing channel
   * resumes from its own token (`bundleResumeToken`). The demux `entries` — payload, seq,
   * resumeToken — are owned by THIS object and untouched by the transport cycle, so no cursor or
   * accumulated state is lost.
   *
   * Rate-limiting is deliberately the CALLER's: this performs the reconnect on every call while
   * started, and the consumer that owns the stall policy owns how often it may fire
   * (`indexingJobsBridge`'s `FEED_STALL_NUDGE_MIN_INTERVAL_MS`). Keeping the policy at one site
   * avoids two independent throttles whose interaction nobody can reason about.
   *
   * @returns `true` when a reconnect was performed; `false` when the stream is not started (never
   * started, or intentionally stopped) — a nudge must not resurrect a closed connection.
   */
  nudgeReconnect(): boolean {
    if (!this.started) {
      return false;
    }
    this.inner.stop();
    this.inner.start();
    return true;
  }

  /**
   * Subscribes to one logical stream's demuxed frames. The FIRST subscriber for a given
   * `streamId` creates its demux entry from `createConfig()`; subsequent subscribers attach
   * to the existing entry (its `initialState`/`reducer` are fixed by the first caller — a
   * second caller passing a different config is assumed compatible, mirroring
   * `EnvelopeStreamPool.subscribePooled`'s contract). The LAST unsubscribe for a `streamId`
   * removes its entry, so frames for an unregistered stream are dropped (see `routeFrame`).
   */
  subscribe<T>(
    streamId: string,
    createConfig: () => { initialState: T; reducer: EnvelopeReducer<T> },
    listener: EnvelopeStreamListener<T>,
  ): () => void {
    let entry = this.entries.get(streamId) as StreamEntry<T> | undefined;
    if (!entry) {
      const cfg = createConfig();
      entry = {
        payload: cfg.initialState,
        seq: 0,
        resumeToken: null,
        reducer: cfg.reducer,
        listeners: new Set(),
        refs: 0,
      };
      this.entries.set(streamId, entry as StreamEntry<unknown>);
      // Tempdoc 662 post-implementation fix: a genuinely NEW entry registered AFTER the
      // connection has already completed its first connect missed the server's one-time
      // connect+snapshot burst for this streamId (it fired before this entry existed). Schedule
      // a debounced reconnect so the NEXT connect burst includes this streamId. A subscribe
      // that happens BEFORE the connection has ever been open (`lastConnected` still `false`,
      // the common/safe case) needs no reconnect — it will correctly receive the first burst
      // once the connection eventually opens.
      if (this.lastConnected) {
        this.scheduleLateSubscribeReconnect();
      }
    }
    entry.listeners.add(listener);
    entry.refs += 1;
    let released = false;
    return () => {
      if (released) return;
      released = true;
      const current = this.entries.get(streamId) as StreamEntry<T> | undefined;
      if (!current) return;
      current.listeners.delete(listener);
      current.refs -= 1;
      if (current.refs <= 0) {
        this.entries.delete(streamId);
      }
    };
  }

  /** Read a logical stream's current snapshot synchronously, or `null` if unregistered. */
  getSnapshot<T>(streamId: string): EnvelopeStreamSnapshot<T> | null {
    const entry = this.entries.get(streamId) as StreamEntry<T> | undefined;
    if (!entry) return null;
    return {
      payload: entry.payload,
      seq: entry.seq,
      resumeToken: entry.resumeToken,
      isConnected: this.lastConnected,
    };
  }

  private routeFrame(envelope: SseEnvelope): void {
    const entry = this.entries.get(envelope.streamId);
    if (!entry) {
      // Unregistered streamId. The server's dedicated heartbeat pseudo-channel (and any
      // heartbeat frame in general) is EXPECTED to have no registered consumer — silent drop.
      // Anything else (an UPDATE, or a non-heartbeat lifecycle frame) for an unknown streamId
      // signals an FE/BE streamId-constant mismatch, worth surfacing in dev.
      const payload = envelope.payload as { kind?: string } | null;
      const isHeartbeat = envelope.frameKind === 'LIFECYCLE' && payload?.kind === 'heartbeat';
      if (!isHeartbeat && IS_DEV) {
        console.warn(`[MultiplexedStream] frame for unregistered streamId: ${envelope.streamId}`);
      }
      return;
    }
    let nextPayload: unknown;
    try {
      nextPayload = entry.reducer(entry.payload, envelope);
    } catch (failure) {
      // Only this logical stream needs a fresh snapshot. The physical stream's
      // existing recovery owner detaches/reconnects; other entries retain their cursors.
      entry.seq = envelope.seq;
      entry.resumeToken = null;
      throw failure;
    }
    entry.payload = nextPayload;
    entry.seq = envelope.seq;
    entry.resumeToken = envelope.resumeToken ?? entry.resumeToken;
    const snapshot: EnvelopeStreamSnapshot<unknown> = {
      payload: entry.payload,
      seq: entry.seq,
      resumeToken: entry.resumeToken,
      isConnected: this.lastConnected,
    };
    for (const listener of entry.listeners) {
      try {
        listener(snapshot);
      } catch {
        // Swallow listener errors so one bad subscriber doesn't break others (mirrors
        // EnvelopeStream.notify's contract).
      }
    }
  }

  /**
   * Debounced reconnect of the shared physical connection, triggered when a new streamId
   * registers after the connection has already delivered its first connect+snapshot burst
   * (see class doc). Debounced (not immediate) so a burst of late registrations in the same
   * tick/short window — e.g. `AdvisoryStore.reconcileFromCatalog()`'s synchronous loop over
   * several discovered advisory Resources — coalesces into ONE reconnect instead of one per
   * registration. Idempotent: re-arms the SAME timer on each additional late registration
   * within the window, so the reconnect fires once, `reconnectDebounceMs` after the LAST
   * late-subscribe in the burst.
   */
  private scheduleLateSubscribeReconnect(): void {
    if (this.reconnectDebounceTimer !== null) {
      clearTimeout(this.reconnectDebounceTimer);
    }
    this.reconnectDebounceTimer = setTimeout(() => {
      this.reconnectDebounceTimer = null;
      // Tempdoc 662 post-implementation fix (critical-analysis pass): the streamId that scheduled
      // this reconnect may have been unsubscribed again before the debounce window elapsed (e.g. a
      // component mount/unmount race, or a catalog entry that got filtered back out). Re-check
      // whether ANY current entry still genuinely needs the next connect burst — the existing
      // per-entry signal for that is `resumeToken === null` (never received a frame yet), the same
      // condition the class doc already documents. If nothing needs it any more, skip the
      // reconnect entirely rather than disrupting every other currently-flowing stream for no
      // reason — reconnecting the shared connection here would undermine the debounce's own
      // purpose (avoid a reconnect per registration) for a registration that no longer exists.
      const stillNeeded = [...this.entries.values()].some((entry) => entry.resumeToken === null);
      if (!stillNeeded) return;
      // stop()+start() (not a raw reconnect) so the resume bundle is rebuilt fresh from the
      // CURRENT entries — including the newly-registered one(s), which have no resumeToken yet
      // and are therefore naturally excluded from the bundle, so the server treats them as
      // first-time subscribers and sends a fresh snapshot (MultiplexedSseWriter.attachAll).
      // Already-flowing entries resume normally via their existing tokens.
      this.inner.stop();
      this.inner.start();
    }, this.reconnectDebounceMs);
  }

  private broadcastConnectionChange(): void {
    for (const entry of this.entries.values()) {
      const snapshot: EnvelopeStreamSnapshot<unknown> = {
        payload: entry.payload,
        seq: entry.seq,
        resumeToken: entry.resumeToken,
        isConnected: this.lastConnected,
      };
      for (const listener of entry.listeners) {
        try {
          listener(snapshot);
        } catch {
          // Swallow listener errors — same contract as routeFrame above.
        }
      }
    }
  }

  /** Comma-joins every registered stream's last-known resumeToken for the `?since=` bundle
   * (Investigation §D / Design §D1 — reuses the per-channel `ResumeTokenCodec` tokens
   * verbatim; no new codec). A stream with no token yet (never received a frame) is omitted —
   * the backend's `MultiplexedSseWriter` treats a missing bundle entry for a channel
   * identically to a fresh subscriber: it sends that channel its own initial snapshot. */
  private bundleResumeToken(): string | null {
    const tokens: string[] = [];
    for (const entry of this.entries.values()) {
      if (entry.resumeToken) tokens.push(entry.resumeToken);
    }
    return tokens.length > 0 ? tokens.join(',') : null;
  }
}
