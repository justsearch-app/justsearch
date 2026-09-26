// SPDX-License-Identifier: Apache-2.0
/**
 * EnvelopeStream — framework-agnostic generic SSE consumer for the
 * universal envelope (slice 436). Wraps an EventSource, parses the
 * `"frame"` SSE event, and threads each frame through a typed reducer.
 *
 * Tracks four hook-owned signals:
 *  - `seq` — most recent envelope.seq seen.
 *  - `resumeToken` — checkpoint of the most recently applied frame; cleared on reducer failure.
 *  - `isConnected` — true while EventSource readyState is OPEN.
 *  - `payload` — consumer-supplied accumulator updated by reducer.
 *
 * Subscribers receive snapshots of all four after every frame.
 *
 * Re-establishment (tempdoc 604): the browser `EventSource` does NOT reconnect after the user agent
 * FAILS a connection — a 5xx / wrong-MIME / abnormal close sets `readyState = CLOSED` and the browser
 * gives up permanently (WHATWG SSE §9.2; probe-confirmed, 604 PART VI U2). So the FE owns
 * re-establishment: on a `CLOSED` error (the fast path) OR on a heartbeat-absence watchdog expiry (the
 * silent-wedge path the error never fires for), the stream reconnects in place with bounded backoff,
 * replaying missed frames via the held `resumeToken` (`?since=`). This completes the codebase's
 * liveness law (heartbeat + stale-window backstop → recover) for the live-stream family.
 *
 * Used by:
 *  - The React `useEnvelopeStream<T>` hook (this directory's sibling).
 *  - Lit reactive controllers in slice 3a.2+ surface ports.
 */

import type { SseEnvelope } from './envelope-types.js';
import { STREAM_WATCHDOG_STALE_MS } from '../../api/generated/stream-liveness-constants.js';
import { bumpOriginContact } from '../state/originContact.js';
import { bumpChannelClosed, bumpChannelOpened } from '../state/liveChannelBudget.js';

/**
 * `EventSource.readyState === CLOSED`. The spec value is a stable `2`; using the literal (not the
 * `EventSource.CLOSED` static) avoids depending on the `EventSource` global being present — it is in
 * the Tauri/Vite runtime, but not in every test environment (happy-dom omits it).
 */
const EVENTSOURCE_CLOSED = 2;

/** Reducer signature: takes the current payload + the envelope, returns the new payload. */
export type EnvelopeReducer<T> = (state: T, envelope: SseEnvelope) => T;

/** Frozen snapshot of the stream state at a point in time. */
export interface EnvelopeStreamSnapshot<T> {
  payload: T;
  seq: number;
  resumeToken: string | null;
  isConnected: boolean;
}

/** Constructor configuration for an EnvelopeStream. */
export interface EnvelopeStreamConfig<T> {
  /** Absolute URL of the SSE endpoint (e.g., 'http://127.0.0.1:33221/api/health/events/stream'). */
  url: string;
  /** Initial payload state (consumer-typed). */
  initialState: T;
  /**
   * Reducer applied to every frame, including lifecycle frames. The
   * consumer routes by frameKind / payload.kind themselves.
   */
  reducer: EnvelopeReducer<T>;
  /**
   * Optional EventSource factory for tests. Defaults to
   * `(url) => new EventSource(url)`.
   */
  eventSourceFactory?: (url: string) => EventSource;
  /**
   * Optional starting resume token. Appended to the URL as
   * `?since=<token>` on initial connect. Useful when remounting after
   * a reload to replay missed frames.
   */
  initialResumeToken?: string | null;
  /**
   * Optional override for the heartbeat-absence watchdog window (ms). Defaults to the generated
   * `STREAM_WATCHDOG_STALE_MS`. Set `0` to disable the watchdog (the reactive `CLOSED` path still
   * runs). Tests pass a small value to exercise the watchdog with real timers.
   */
  watchdogStaleMs?: number;
  /** Optional override for the first reconnect-backoff delay (ms). Default 500. */
  reconnectBaseMs?: number;
  /** Optional override for the reconnect-backoff cap (ms). Default 10_000. */
  reconnectCapMs?: number;
  /**
   * Tempdoc 662 — optional override for the `?since=` value used on every (re)connect. When
   * set, this REPLACES the single auto-tracked `resumeToken` for URL construction (a `null`
   * return omits `?since=` entirely); the single-token field still checkpoints successfully
   * applied frames and clears on reducer failure (unused for this URL). The sole consumer is
   * `MultiplexedStream`, which needs the resume value to be a comma-joined BUNDLE of several
   * logical streams' tokens (one per demuxed `streamId`), not the single last-seen token a
   * one-channel-per-connection stream tracks. Single-channel consumers never set this — their
   * `urlWithResumeToken()` behavior is unchanged.
   */
  resumeTokenProvider?: () => string | null;
}

/**
 * Listener callback shape. Receives the current snapshot after every
 * relevant change. Listeners may not throw; the stream catches and
 * silently drops listener errors to avoid breaking other listeners.
 */
export type EnvelopeStreamListener<T> = (snapshot: EnvelopeStreamSnapshot<T>) => void;

export class EnvelopeStream<T> {
  private readonly url: string;
  private readonly reducer: EnvelopeReducer<T>;
  private readonly factory: (url: string) => EventSource;

  private payload: T;
  private seq: number = 0;
  private resumeToken: string | null;
  private isConnected: boolean = false;
  private source: EventSource | null = null;

  // ── FE-owned re-establishment state (tempdoc 604) ──
  private readonly watchdogStaleMs: number;
  private readonly reconnectBaseMs: number;
  private readonly reconnectCapMs: number;
  private readonly resumeTokenProvider: (() => string | null) | undefined;
  /** True between an intentional stop() and the next start() — suppresses auto-reconnect. */
  private closedByUs: boolean = false;
  /** Pending reconnect timer, or null when none is scheduled. */
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  /** Consecutive reconnect attempts since the last clean open — drives the backoff. */
  private reconnectAttempt: number = 0;
  /** Heartbeat-absence watchdog timer, or null when disarmed. */
  private watchdogTimer: ReturnType<typeof setTimeout> | null = null;

  private readonly listeners = new Set<EnvelopeStreamListener<T>>();
  private readonly handleFrameBound = this.handleFrame.bind(this);
  private readonly handleOpenBound = this.handleOpen.bind(this);
  private readonly handleErrorBound = this.handleError.bind(this);

  constructor(config: EnvelopeStreamConfig<T>) {
    this.url = config.url;
    this.payload = config.initialState;
    this.reducer = config.reducer;
    this.factory =
      config.eventSourceFactory ?? ((u) => new EventSource(u));
    this.resumeToken = config.initialResumeToken ?? null;
    this.watchdogStaleMs = config.watchdogStaleMs ?? STREAM_WATCHDOG_STALE_MS;
    this.reconnectBaseMs = config.reconnectBaseMs ?? 500;
    this.reconnectCapMs = config.reconnectCapMs ?? 10_000;
    this.resumeTokenProvider = config.resumeTokenProvider;
  }

  /**
   * Open the EventSource and begin processing frames. Idempotent: a
   * second start() while already running is a no-op (but logs nothing
   * — there's no logger yet at the substrate level).
   */
  start(): void {
    if (this.source) {
      return;
    }
    this.closedByUs = false;
    const url = this.urlWithResumeToken();
    const es = this.factory(url);
    this.source = es;
    // Tempdoc 662 — the exact instant a physical long-lived connection opens (paired with
    // bumpChannelClosed() at the exact instant detachSource() actually closes one).
    bumpChannelOpened();
    es.addEventListener('frame', this.handleFrameBound as EventListener);
    es.addEventListener('open', this.handleOpenBound);
    es.addEventListener('error', this.handleErrorBound);
    // Arm the heartbeat-absence watchdog: a live channel emits a heartbeat (or any frame) within
    // the window; silence past it means the channel is dead even if no `error` ever fired.
    this.armWatchdog();
  }

  /**
   * Close the EventSource and stop receiving frames. After stop(),
   * the snapshot reflects whatever was last received plus
   * `isConnected = false`. Idempotent.
   */
  stop(): void {
    // Intentional stop: suppress any auto-reconnect and cancel both timers, even if the source was
    // already torn down mid-reconnect (so a pending reconnect can't resurrect a stopped stream).
    this.closedByUs = true;
    this.clearWatchdog();
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    if (!this.source) {
      return;
    }
    this.detachSource();
    if (this.isConnected) {
      this.isConnected = false;
      this.notify();
    }
  }

  /**
   * Subscribe to snapshots. Returns an unsubscribe function. The
   * listener is NOT called immediately with the current snapshot; the
   * caller can read `getSnapshot()` for that.
   */
  subscribe(listener: EnvelopeStreamListener<T>): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  /** Read the current snapshot synchronously. */
  getSnapshot(): EnvelopeStreamSnapshot<T> {
    return {
      payload: this.payload,
      seq: this.seq,
      resumeToken: this.resumeToken,
      isConnected: this.isConnected,
    };
  }

  /**
   * Append the resume-token query parameter to the URL when set.
   * Tolerates an existing query string in the URL.
   */
  private urlWithResumeToken(): string {
    const token = this.resumeTokenProvider ? this.resumeTokenProvider() : this.resumeToken;
    if (!token) {
      return this.url;
    }
    const sep = this.url.includes('?') ? '&' : '?';
    return `${this.url}${sep}since=${encodeURIComponent(token)}`;
  }

  private handleFrame(event: MessageEvent): void {
    let envelope: SseEnvelope;
    try {
      envelope = JSON.parse(event.data) as SseEnvelope;
    } catch {
      // Malformed frame — ignore (the next valid frame will catch up
      // via seq + resumeToken).
      return;
    }
    if (
      typeof envelope.seq !== 'number' ||
      (envelope.frameKind !== 'UPDATE' && envelope.frameKind !== 'LIFECYCLE')
    ) {
      // Wrong-shape frame — ignore.
      return;
    }
    let nextPayload: T;
    try {
      nextPayload = this.reducer(this.payload, envelope);
    } catch {
      // An unapplied frame cannot be acknowledged. Preserve the visible payload,
      // record the observed sequence, and ask the existing reconnect owner for a
      // fresh snapshot. Detach before notifying so late frames cannot advance the cursor.
      this.seq = envelope.seq;
      this.resumeToken = null;
      this.isConnected = false;
      this.scheduleReconnect();
      this.notify();
      return;
    }
    this.payload = nextPayload;
    this.seq = envelope.seq;
    this.resumeToken = envelope.resumeToken ?? this.resumeToken;
    // A frame (incl. a heartbeat) is proof of life: the channel is healthy, so reset both the
    // watchdog and the reconnect backoff.
    // Tempdoc 649: a received frame is also positive contact with the backend origin — feed the ONE
    // reachability authority so a poll starved behind the connection-pool limit can't read as
    // "Backend disconnected" while this stream is provably alive. Pure stamp bump, no listener notify
    // (the strict subscriber-order test must stay [0,1,2,2]).
    bumpOriginContact();
    this.reconnectAttempt = 0;
    this.armWatchdog();
    if (!this.isConnected) {
      this.isConnected = true;
    }
    this.notify();
  }

  private handleOpen(): void {
    // A clean open means the transport is healthy again — reset the backoff and re-arm the watchdog.
    // Tempdoc 649: a clean open is positive contact with the origin — feed the reachability authority.
    bumpOriginContact();
    this.reconnectAttempt = 0;
    this.armWatchdog();
    if (!this.isConnected) {
      this.isConnected = true;
      this.notify();
    }
  }

  private handleError(): void {
    // Reflect the disconnected state. Then decide who owns recovery: the browser auto-reconnects
    // ONLY for a transient drop (readyState CONNECTING); for a FAILED connection (readyState CLOSED
    // — a 5xx / wrong-MIME / abnormal close) it gives up permanently, so the FE must reconnect.
    if (this.isConnected) {
      this.isConnected = false;
      this.notify();
    }
    if (this.source && this.source.readyState === EVENTSOURCE_CLOSED) {
      this.scheduleReconnect();
    }
  }

  /** Detach listeners and close the current EventSource, leaving `source = null`. */
  private detachSource(): void {
    const es = this.source;
    if (!es) {
      return;
    }
    es.removeEventListener('frame', this.handleFrameBound as EventListener);
    es.removeEventListener('open', this.handleOpenBound);
    es.removeEventListener('error', this.handleErrorBound);
    es.close();
    this.source = null;
    // Tempdoc 662 — the exact instant a physical long-lived connection closes (paired with
    // bumpChannelOpened() in start()). Covers both an intentional stop() and a 604 reconnect's
    // teardown-before-reopen (scheduleReconnect() also calls this).
    bumpChannelClosed();
  }

  /**
   * Arm (or re-arm) the heartbeat-absence watchdog: if no frame arrives within `watchdogStaleMs`,
   * the channel is presumed dead (the silent-wedge case the `error` event never fires for) and the
   * FE reconnects. A `watchdogStaleMs` of 0 disables it.
   */
  private armWatchdog(): void {
    this.clearWatchdog();
    if (this.watchdogStaleMs <= 0 || this.closedByUs) {
      return;
    }
    this.watchdogTimer = setTimeout(() => {
      this.watchdogTimer = null;
      // No frame within the window — the channel is silently dead. Reflect it and reconnect.
      if (this.isConnected) {
        this.isConnected = false;
        this.notify();
      }
      this.scheduleReconnect();
    }, this.watchdogStaleMs);
  }

  private clearWatchdog(): void {
    if (this.watchdogTimer !== null) {
      clearTimeout(this.watchdogTimer);
      this.watchdogTimer = null;
    }
  }

  /**
   * FE-owned re-establishment. Tears down the dead/silent source and reopens it after bounded
   * exponential backoff (capped, jittered, infinite at the cap per the 604 decision). Reuses a
   * pending timer, but always detaches a failed source, including one reopened by a listener.
   * Does nothing after an intentional stop(). `start()` re-reads
   * the freshest `resumeToken`, so the replay is gap-free.
   */
  private scheduleReconnect(): void {
    if (this.closedByUs) {
      return;
    }
    this.detachSource();
    this.clearWatchdog();
    if (this.reconnectTimer !== null) {
      return;
    }
    const exp = Math.min(this.reconnectCapMs, this.reconnectBaseMs * 2 ** this.reconnectAttempt);
    const jitter = Math.random() * Math.min(this.reconnectBaseMs / 2, exp);
    const delay = Math.min(this.reconnectCapMs, exp + jitter);
    this.reconnectAttempt += 1;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      if (!this.closedByUs) {
        this.start();
      }
    }, delay);
  }

  private notify(): void {
    const snapshot = this.getSnapshot();
    for (const listener of this.listeners) {
      try {
        listener(snapshot);
      } catch {
        // Swallow listener errors so one bad subscriber doesn't break others.
      }
    }
  }
}
