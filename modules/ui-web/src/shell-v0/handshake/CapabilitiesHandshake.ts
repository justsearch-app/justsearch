// SPDX-License-Identifier: Apache-2.0
/**
 * CapabilitiesHandshake — framework-agnostic consumer of the
 * `/infra/capabilities` HTTP endpoint + the
 * `/infra/capabilities/stream` SSE endpoint.
 *
 * Lifecycle:
 *  1. start(apiBase) — issues the HTTP GET, then opens the SSE
 *     stream and subscribes to `lifecycle.kind: reset` frames. Any
 *     reset triggers a re-fetch of the HTTP endpoint.
 *  2. getSnapshot() — returns the current `CapabilitiesSnapshot`.
 *  3. subscribe(listener) — listener receives a snapshot on each
 *     change (load/refetch/error). Returns an unsubscribe function.
 *  4. stop() — closes the SSE stream and aborts any in-flight fetch.
 *
 * Used by:
 *  - The React `useServerCapabilities` hook (sibling file).
 *  - Lit reactive controllers in slice 3a.2+ surface ports.
 */
import { FetchEventSource, type StreamSource } from '../streaming/FetchEventSource.js';

import { EnvelopeStream } from '../streaming/EnvelopeStream.js';
import type { SseEnvelope } from '../streaming/envelope-types.js';
import { authorizedFetch } from '../api/authorizedFetch.js';
import type {
  CapabilitiesSnapshot,
  CapabilitiesView,
} from './capabilities-types.js';

/** Listener callback receiving snapshots on each change. */
export type CapabilitiesListener = (snapshot: CapabilitiesSnapshot) => void;

/** Constructor configuration. */
export interface CapabilitiesHandshakeConfig {
  /** Absolute API base (e.g., 'http://127.0.0.1:33221'). No trailing slash. */
  apiBase: string;
  /** Optional fetch override for tests. */
  fetchImpl?: typeof fetch;
  /** Optional EventSource factory for tests. */
  eventSourceFactory?: (url: string) => StreamSource;
}

const EMPTY_SNAPSHOT: CapabilitiesSnapshot = {
  isLoaded: false,
  error: null,
  view: null,
  hasEnvelope: false,
};

export class CapabilitiesHandshake {
  private readonly apiBase: string;
  private readonly fetchImpl: typeof fetch;
  private readonly eventSourceFactory: (url: string) => StreamSource;

  private snapshot: CapabilitiesSnapshot = EMPTY_SNAPSHOT;
  private stream: EnvelopeStream<{ resetCount: number }> | null = null;
  private abortController: AbortController | null = null;
  private streamUnsubscribe: (() => void) | null = null;
  private stopped = false;

  private readonly listeners = new Set<CapabilitiesListener>();

  constructor(config: CapabilitiesHandshakeConfig) {
    this.apiBase = config.apiBase.replace(/\/$/, '');
    this.fetchImpl = config.fetchImpl ?? authorizedFetch;
    this.eventSourceFactory =
      config.eventSourceFactory ?? ((url) => new FetchEventSource(url));
  }

  /**
   * Fetch capabilities and start the SSE stream. Idempotent: a
   * second start() call is a no-op.
   */
  start(): void {
    if (this.stream || this.abortController || this.stopped) {
      return;
    }
    void this.fetchOnce();
    this.startStream();
  }

  /** Stop the stream and abort any in-flight fetch. */
  stop(): void {
    this.stopped = true;
    if (this.streamUnsubscribe) {
      this.streamUnsubscribe();
      this.streamUnsubscribe = null;
    }
    if (this.stream) {
      this.stream.stop();
      this.stream = null;
    }
    if (this.abortController) {
      this.abortController.abort();
      this.abortController = null;
    }
  }

  getSnapshot(): CapabilitiesSnapshot {
    return this.snapshot;
  }

  subscribe(listener: CapabilitiesListener): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  /**
   * Re-fetch the capabilities view via HTTP. Public so consumers can
   * trigger a refresh imperatively (e.g., after a known-good config
   * change).
   */
  async refetch(): Promise<void> {
    await this.fetchOnce();
  }

  private async fetchOnce(): Promise<void> {
    if (this.stopped) {
      return;
    }
    if (this.abortController) {
      this.abortController.abort();
    }
    const ctrl = new AbortController();
    this.abortController = ctrl;
    try {
      const response = await this.fetchImpl(
        `${this.apiBase}/infra/capabilities`,
        { signal: ctrl.signal },
      );
      if (!response.ok) {
        this.update({
          ...this.snapshot,
          isLoaded: true,
          error: `HTTP ${response.status} ${response.statusText}`,
        });
        return;
      }
      const view = (await response.json()) as CapabilitiesView;
      const hasEnvelope =
        (view.serverCapabilities?.streamingEnvelope?.version ?? 0) >= 1;
      this.update({ isLoaded: true, error: null, view, hasEnvelope });
    } catch (err) {
      // Aborted fetches are expected during teardown — don't surface them.
      const isAbort =
        (err as { name?: string } | null)?.name === 'AbortError';
      if (isAbort) {
        return;
      }
      this.update({
        ...this.snapshot,
        isLoaded: true,
        error: err instanceof Error ? err.message : String(err),
      });
    } finally {
      if (this.abortController === ctrl) {
        this.abortController = null;
      }
    }
  }

  private startStream(): void {
    const url = `${this.apiBase}/infra/capabilities/stream`;
    const stream = new EnvelopeStream<{ resetCount: number }>({
      url,
      initialState: { resetCount: 0 },
      reducer: (state, env: SseEnvelope) => {
        if (env.frameKind !== 'LIFECYCLE') {
          return state;
        }
        const kind = (env.payload as { kind?: string } | null)?.kind;
        if (kind === 'reset') {
          // Trigger a re-fetch on reset frames. The substrate's
          // reducer should be pure; trigger the side-effect on
          // observation rather than from inside the reducer.
          return { resetCount: state.resetCount + 1 };
        }
        return state;
      },
      eventSourceFactory: this.eventSourceFactory,
    });

    let lastSeenResetCount = 0;
    this.streamUnsubscribe = stream.subscribe((s) => {
      if (s.payload.resetCount > lastSeenResetCount) {
        lastSeenResetCount = s.payload.resetCount;
        // Side-effect: re-fetch the HTTP capabilities view.
        void this.fetchOnce();
      }
    });
    stream.start();
    this.stream = stream;
  }

  private update(snapshot: CapabilitiesSnapshot): void {
    this.snapshot = snapshot;
    for (const listener of this.listeners) {
      try {
        listener(snapshot);
      } catch {
        // Swallow listener errors so one bad subscriber doesn't break others.
      }
    }
  }
}
