// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 490 §4.D — Advisory store. Subscribes to advisory-shaped SSE
 * streams (slice 490 §4.A `kind = "advisory-event-stream"` Resources),
 * accumulates events in memory, joins them with persisted
 * acknowledgment state from {@link UserStateDocument}, and broadcasts
 * snapshots to chrome consumers (toast, inbox drawer, rail badge).
 *
 * Slice 490 substrate-completion follow-up (P2.2): generic Resource-
 * Catalog discovery. The store walks {@link listResources} filtered by
 * `kind === KIND_ADVISORY`, creates one `EnvelopeStream` per discovered
 * Resource, and aggregates per-stream snapshots into one combined
 * {@link AdvisorySnapshot}. Per-event {@code sourceRenderHint} stamping
 * is derived from each event's source Resource's
 * `emissionPolicy.renderHint` at arrival time — replacing the v1
 * per-store renderHint which would have aliased once multiple advisory
 * classes coexist.
 *
 * On `onCatalogChange`: new advisory Resources spawn fresh streams;
 * removed Resources have their streams stopped. {@code start()} /
 * {@code stop()} propagate to all underlying streams + the catalog
 * listener.
 *
 * Stable advisory key: `<operationId>@<occurredAt>` — uniquely
 * identifies an advisory event for read-state correlation across all
 * sources.
 */

import {
  EnvelopeStream,
  type EnvelopeReducer,
} from '../../streaming/EnvelopeStream.js';
import type { MultiplexedStream } from '../../streaming/MultiplexedStream.js';
import { advisoryEndpointToShellEventStreamId } from '../../streaming/shellEventStreamIds.js';
import type { SseEnvelope } from '../../streaming/envelope-types.js';
import {
  listResources,
  onCatalogChange,
} from '../../../api/registry/ResourceCatalogClient.js';
// Allowlisted in eslint.config.js — see 511-followup-B. AdvisoryStore
// is a catalog client (constructs Resource records from the wire); it
// is upstream of the `<jf-resource>` substrate boundary, not a
// destructuring consumer of it.
import {
  KIND_ADVISORY,
  type RenderHint,
  type Resource,
} from '../../../api/types/registry.js';
import {
  ACKNOWLEDGED_ADVISORIES_CAP,
  getDocument,
  mutateDocument,
  subscribeDocument,
} from '../../state/UserStateDocument.js';
import {
  EPHEMERAL_TOAST_EVENT,
  type EphemeralToastSpec,
  type MessageSeverity,
} from './ephemeralToast.js';

/**
 * Slice 494: uniform wire shape for all advisory classes. Mirrors the
 * backend {@code AdvisoryRecord} record. Class-specific data lives in
 * {@code classExtras}; FE switches on {@code classId} for presentation.
 */
export interface AdvisoryEvent {
  readonly classId: string;
  readonly id: string;
  readonly occurredAt: string;
  readonly renderHint: RenderHint;
  /**
   * Tempdoc 559 Authority III — orthogonal tone axis (info/success/warning/
   * error), independent of {@link RenderHint}'s lifecycle/channel axis. Optional
   * + back-compat: server advisories omit it today (tone falls back to the
   * classId-derived chrome); local ephemeral messages set it. The wire record
   * (`AdvisoryRecord.java`) is the declared extension point for a future
   * server-declared severity (one-line registry stamp).
   */
  readonly severity?: MessageSeverity;
  readonly diagnosticsLink: string | null;
  readonly provenance: {
    readonly transport: string;
    readonly executor: string;
    readonly initiator: string | null;
    readonly occurredAt: string;
  } | null;
  readonly primaryAction: {
    readonly target: string;
    readonly defaultArgsJson: string;
  } | null;
  readonly primaryActionKind?: string | null;
  readonly bodyI18nKey: string | null;
  readonly classExtras: Record<string, unknown>;
}

export type { RenderHint };

/** Snapshot delivered to consumers — joins live events with persisted ack state. */
export interface AdvisorySnapshot {
  readonly advisories: readonly AdvisoryRecord[];
  readonly unreadCount: number;
  /**
   * Slice 490 substrate-completion (P2.2): aggregate connection state across
   * all discovered advisory streams. `true` iff every stream is connected.
   * Multiple streams in partial-connect state (some up, some down) report
   * `false` so the disconnected indicator surfaces the degraded state.
   */
  readonly isConnected: boolean;
  /**
   * Aggregate frame discriminator: `'initial'` before any stream delivers a
   * frame; `'snapshot'` while at least one stream has delivered its first
   * LIFECYCLE snapshot but no UPDATE has arrived; `'update'` once any stream
   * has delivered an UPDATE. The toast host uses this to gate "is this a
   * historical replay or a live update?"
   */
  readonly lastFrameKind: 'initial' | 'snapshot' | 'update';
}

/**
 * Slice 490 substrate-completion — per-event record now carries the source
 * Resource's renderHint (was per-store in v1). Toast and drawer dispatch on
 * this discriminator so EPHEMERAL advisories never enter the inbox and
 * REQUIRES_ACK toasts persist.
 */
export interface AdvisoryRecord {
  readonly key: string;
  readonly event: AdvisoryEvent;
  readonly acknowledged: boolean;
  readonly sourceRenderHint: RenderHint;
  /**
   * Tempdoc 559 Authority III — provenance of this record. `'stream'` records
   * come from an SSE advisory Resource (the historical model); `'local'` records
   * are client-originated ephemeral messages emitted via {@link emitEphemeralToast}.
   * The toast host renders local records ungated by the frame-kind replay gate
   * (they are always live), and never persists them.
   */
  readonly origin: 'stream' | 'local';
  /** Present iff `origin === 'local'` — the caller's transient-message spec. */
  readonly toast?: EphemeralToastSpec;
}

/** Internal envelope-stream payload (per-stream). */
interface AdvisoryStreamPayload {
  readonly advisories: readonly AdvisoryEvent[];
  /**
   * Per-stream tracking of the most-recently-applied frame kind. Aggregated
   * across all streams to derive the snapshot's `lastFrameKind`.
   */
  readonly lastFrameKind: 'initial' | 'snapshot' | 'update';
}

/** Derive the stable advisory key — uses the backend-stamped id directly. */
export function advisoryKey(event: AdvisoryEvent): string {
  return event.id;
}

/**
 * Reducer: routes envelope frames into the typed advisory payload.
 *
 * - LIFECYCLE with payload.kind === 'snapshot' → replace advisories; stamp
 *   `lastFrameKind = 'snapshot'`.
 * - LIFECYCLE with payload.kind === 'connected' / other → preserve advisories;
 *   `lastFrameKind` unchanged.
 * - UPDATE → append the payload event; stamp `lastFrameKind = 'update'`.
 */
export const advisoryReducer: EnvelopeReducer<AdvisoryStreamPayload> = (
  state,
  envelope: SseEnvelope,
) => {
  if (envelope.frameKind === 'LIFECYCLE') {
    const payload = envelope.payload as
      | { kind?: string; advisories?: AdvisoryEvent[] }
      | undefined;
    if (payload && payload.kind === 'snapshot' && Array.isArray(payload.advisories)) {
      return { advisories: payload.advisories, lastFrameKind: 'snapshot' };
    }
    return state;
  }
  if (envelope.frameKind === 'UPDATE') {
    const event = envelope.payload as AdvisoryEvent | undefined;
    if (event && typeof event.classId === 'string') {
      return {
        advisories: [...state.advisories, event],
        lastFrameKind: 'update',
      };
    }
    return state;
  }
  return state;
};

/** Listener shape. */
export type AdvisoryListener = (snapshot: AdvisorySnapshot) => void;

/**
 * Slice 490 substrate-completion follow-up — FIFO-cap a list at
 * {@link ACKNOWLEDGED_ADVISORIES_CAP}. When the input exceeds the cap, the
 * oldest entries are dropped. Returns the input array unchanged when it's
 * within the limit.
 */
function capList(list: readonly string[]): readonly string[] {
  if (list.length <= ACKNOWLEDGED_ADVISORIES_CAP) return list;
  return list.slice(list.length - ACKNOWLEDGED_ADVISORIES_CAP);
}

/**
 * Per-Resource entry — either a direct EnvelopeStream OR a subscription on the shared
 * `MultiplexedStream` (tempdoc 662) + its snapshot. `stream` is present only for the
 * direct-connection fallback path (no `multiplex` configured, or an endpoint
 * `advisoryEndpointToShellEventStreamId` doesn't recognize) — the multiplexed path has no
 * per-resource socket to stop, only `unsubscribe()` to call.
 * Slice 494: renderHint moved to the wire record (AdvisoryRecord.renderHint);
 * the per-Resource stamp is no longer needed.
 */
interface ResourceStreamEntry {
  readonly resourceId: string;
  readonly stream?: EnvelopeStream<AdvisoryStreamPayload>;
  /** Local snapshot mirroring this stream's latest payload + connection. */
  snapshot: {
    advisories: readonly AdvisoryEvent[];
    isConnected: boolean;
    lastFrameKind: 'initial' | 'snapshot' | 'update';
  };
  /** Unsubscribe from this stream's listener. */
  unsubscribe: () => void;
}

/**
 * Live store. Walks the Resource Catalog for advisory-shaped Resources,
 * subscribes to each, and aggregates snapshots.
 */
export class AdvisoryStore {
  private readonly apiBase: string;
  private readonly eventSourceFactory?: (url: string) => EventSource;
  /**
   * Keyed by Resource.id. Each entry owns one EnvelopeStream + its latest
   * per-stream snapshot.
   */
  private readonly entries = new Map<string, ResourceStreamEntry>();
  private readonly listeners = new Set<AdvisoryListener>();
  private readonly userStateUnsubscribe: () => void;
  private catalogUnsubscribe: (() => void) | null = null;
  private started = false;
  /**
   * Tempdoc 559 Authority III — client-originated EPHEMERAL records. They live
   * outside the per-Resource SSE streams (they have no server source) but flow
   * through the same snapshot → AdvisoryToastHost path, so there is ONE message
   * model. Dropped when the toast dismisses (they never persist).
   */
  private localEphemeral: readonly AdvisoryRecord[] = [];
  private localSeq = 0;
  /** Document-event bridge so decoupled callers reach the one store. */
  private ephemeralListener: ((e: Event) => void) | null = null;

  /**
   * Tempdoc 662: the shared `MultiplexedStream` (one of the 5 always-on streams collapsed
   * onto `/api/shell-events/stream`). When set, each discovered advisory Resource whose
   * endpoint `advisoryEndpointToShellEventStreamId` recognizes subscribes on it instead of
   * opening its own EventSource; an unrecognized endpoint (a future advisory class added
   * before that map is updated) falls back to a direct EnvelopeStream, same as when no
   * `multiplex` is configured at all.
   */
  private readonly multiplex?: MultiplexedStream;

  constructor(opts?: {
    apiBase?: string;
    eventSourceFactory?: (url: string) => EventSource;
    multiplex?: MultiplexedStream;
  }) {
    this.apiBase = opts?.apiBase ?? '';
    this.eventSourceFactory = opts?.eventSourceFactory;
    this.multiplex = opts?.multiplex;
    this.userStateUnsubscribe = subscribeDocument(() => this.notify());
  }

  /**
   * Start the store: discover advisory Resources from the catalog and open one
   * EnvelopeStream per discovered Resource. Subscribes to catalog changes to
   * pick up newly-registered advisory Resources mid-session.
   */
  start(): void {
    if (this.started) return;
    this.started = true;
    this.reconcileFromCatalog();
    this.catalogUnsubscribe = onCatalogChange(() => this.reconcileFromCatalog());
    // 559 Authority III — consume client-originated ephemeral toasts into the
    // one model (replaces the retired SimpleToast `jf-show-toast` listener).
    if (typeof document !== 'undefined') {
      this.ephemeralListener = (e: Event) => {
        const spec = (e as CustomEvent<EphemeralToastSpec>).detail;
        if (spec && typeof spec.message === 'string') this.emitEphemeral(spec);
      };
      document.addEventListener(EPHEMERAL_TOAST_EVENT, this.ephemeralListener);
    }
  }

  stop(): void {
    if (!this.started) return;
    this.started = false;
    if (this.catalogUnsubscribe) {
      this.catalogUnsubscribe();
      this.catalogUnsubscribe = null;
    }
    if (this.ephemeralListener && typeof document !== 'undefined') {
      document.removeEventListener(EPHEMERAL_TOAST_EVENT, this.ephemeralListener);
      this.ephemeralListener = null;
    }
    this.localEphemeral = [];
    for (const entry of this.entries.values()) {
      entry.unsubscribe();
      entry.stream?.stop();
    }
    this.entries.clear();
    this.userStateUnsubscribe();
  }

  subscribe(listener: AdvisoryListener): () => void {
    this.listeners.add(listener);
    listener(this.snapshot());
    return () => {
      this.listeners.delete(listener);
    };
  }

  snapshot(): AdvisorySnapshot {
    const acks = new Set(getDocument().acknowledgedAdvisories ?? []);
    // Flatten + sort by occurredAt ascending across all streams. Per slice
    // 494: renderHint now comes from the wire record itself (uniform shape),
    // not from the source Resource's emissionPolicy.
    const flat: AdvisoryEvent[] = [];
    for (const entry of this.entries.values()) {
      for (const event of entry.snapshot.advisories) {
        flat.push(event);
      }
    }
    flat.sort((a, b) => a.occurredAt.localeCompare(b.occurredAt));
    const streamRecords: AdvisoryRecord[] = flat.map((event) => {
      const key = advisoryKey(event);
      return {
        key,
        event,
        acknowledged: acks.has(key),
        sourceRenderHint: event.renderHint,
        origin: 'stream',
      };
    });
    // 559 Authority III — one model: client-originated ephemeral records join the
    // same snapshot the toast host renders. They are EPHEMERAL (toast-only), so
    // the inbox already filters them and they never count as unread.
    const records: AdvisoryRecord[] = [...streamRecords, ...this.localEphemeral];
    // 559 Authority III — EPHEMERAL is toast-only; it must not inflate the rail
    // badge's unread count (the inbox drawer already excludes EPHEMERAL from its
    // own count — this aligns the store-level count with that single rule).
    const unreadCount = streamRecords.filter(
      (r) => !r.acknowledged && r.sourceRenderHint !== 'EPHEMERAL',
    ).length;
    return {
      advisories: records,
      unreadCount,
      isConnected: this.deriveIsConnected(),
      lastFrameKind: this.deriveLastFrameKind(),
    };
  }

  /**
   * Mark an advisory as acknowledged. Persists via {@link mutateDocument}.
   * Enforces the {@link ACKNOWLEDGED_ADVISORIES_CAP} cap: when the list would
   * exceed the cap, the oldest entries are FIFO-evicted (Group B5 follow-up).
   */
  acknowledge(key: string): void {
    mutateDocument((doc) => {
      const current = doc.acknowledgedAdvisories ?? [];
      if (current.includes(key)) return doc;
      const next = capList([...current, key]);
      return { ...doc, acknowledgedAdvisories: next };
    });
  }

  /** Mark all current advisories acknowledged. */
  acknowledgeAll(): void {
    const allKeys: string[] = [];
    for (const entry of this.entries.values()) {
      for (const event of entry.snapshot.advisories) {
        allKeys.push(advisoryKey(event));
      }
    }
    if (allKeys.length === 0) return;
    mutateDocument((doc) => {
      const currentSet = new Set(doc.acknowledgedAdvisories ?? []);
      const currentArr: string[] = [...(doc.acknowledgedAdvisories ?? [])];
      let changed = false;
      for (const k of allKeys) {
        if (!currentSet.has(k)) {
          currentSet.add(k);
          currentArr.push(k);
          changed = true;
        }
      }
      if (!changed) return doc;
      return { ...doc, acknowledgedAdvisories: capList(currentArr) };
    });
  }

  /**
   * Tempdoc 559 Authority III — fold a client-originated transient message into
   * the one model as a local-origin EPHEMERAL record. Rendered by the single
   * AdvisoryToastHost; never enters the inbox or the unread count.
   */
  emitEphemeral(spec: EphemeralToastSpec): void {
    const seq = ++this.localSeq;
    const key = `local:${seq}`;
    const classId = spec.classId ?? 'core.ephemeral';
    // Tempdoc 602 R4 — opt-in single-occupancy: a superseding toast drops any
    // prior live toast of the SAME classId so recurring same-class transients
    // (navigation) replace rather than stack. The AdvisoryToastHost prunes the
    // departed record from its visible list on the resulting snapshot.
    if (spec.supersede) {
      this.localEphemeral = this.localEphemeral.filter((r) => r.event.classId !== classId);
    }
    const event: AdvisoryEvent = {
      classId,
      id: key,
      occurredAt: new Date().toISOString(),
      renderHint: 'EPHEMERAL',
      severity: spec.severity ?? 'info',
      diagnosticsLink: null,
      provenance: null,
      primaryAction: null,
      primaryActionKind: null,
      bodyI18nKey: null,
      classExtras: { message: spec.message },
    };
    const record: AdvisoryRecord = {
      key,
      event,
      acknowledged: false,
      sourceRenderHint: 'EPHEMERAL',
      origin: 'local',
      toast: spec,
    };
    this.localEphemeral = [...this.localEphemeral, record];
    this.notify();
  }

  /** Remove a local-origin ephemeral record once its toast has dismissed. */
  dropEphemeral(key: string): void {
    const next = this.localEphemeral.filter((r) => r.key !== key);
    if (next.length !== this.localEphemeral.length) {
      this.localEphemeral = next;
      this.notify();
    }
  }

  /**
   * Slice 490 substrate-completion — diff the catalog against currently-active
   * streams; start streams for newly-discovered advisory Resources; stop streams
   * for Resources that have been removed.
   */
  private reconcileFromCatalog(): void {
    const advisoryResources = listResources().filter(
      (r) => r.kind === KIND_ADVISORY,
    );
    const desired = new Set(advisoryResources.map((r) => r.id));
    // Remove streams for Resources no longer in the catalog.
    for (const [id, entry] of this.entries) {
      if (!desired.has(id)) {
        entry.unsubscribe();
        entry.stream?.stop();
        this.entries.delete(id);
      }
    }
    // Add streams for newly-discovered Resources.
    for (const resource of advisoryResources) {
      if (this.entries.has(resource.id)) continue;
      this.startStreamForResource(resource);
    }
    this.notify();
  }

  private startStreamForResource(resource: Resource): void {
    const streamId = this.multiplex
      ? advisoryEndpointToShellEventStreamId(resource.endpoint)
      : null;
    if (this.multiplex && streamId) {
      this.startMultiplexedStreamForResource(resource, this.multiplex, streamId);
      return;
    }
    this.startDirectStreamForResource(resource);
  }

  /** Tempdoc 662: subscribe this Resource's recognized streamId on the shared multiplexer
   * instead of opening a dedicated EventSource. */
  private startMultiplexedStreamForResource(
    resource: Resource,
    multiplex: MultiplexedStream,
    streamId: string,
  ): void {
    const entry: ResourceStreamEntry = {
      resourceId: resource.id,
      snapshot: { advisories: [], isConnected: false, lastFrameKind: 'initial' },
      unsubscribe: () => {},
    };
    entry.unsubscribe = multiplex.subscribe<AdvisoryStreamPayload>(
      streamId,
      () => ({ initialState: { advisories: [], lastFrameKind: 'initial' }, reducer: advisoryReducer }),
      (s) => {
        entry.snapshot = {
          advisories: s.payload.advisories,
          isConnected: s.isConnected,
          lastFrameKind: s.payload.lastFrameKind,
        };
        this.notify();
      },
    );
    this.entries.set(resource.id, entry);
  }

  /** Fallback: a direct EnvelopeStream — used when no `multiplex` is configured, or for an
   * advisory Resource whose endpoint `advisoryEndpointToShellEventStreamId` doesn't (yet)
   * recognize. */
  private startDirectStreamForResource(resource: Resource): void {
    const url = this.apiBase
      ? `${this.apiBase}${resource.endpoint}`
      : resource.endpoint;
    const stream = new EnvelopeStream<AdvisoryStreamPayload>({
      url,
      initialState: { advisories: [], lastFrameKind: 'initial' },
      reducer: advisoryReducer,
      eventSourceFactory: this.eventSourceFactory,
    });
    const entry: ResourceStreamEntry = {
      resourceId: resource.id,
      stream,
      snapshot: { advisories: [], isConnected: false, lastFrameKind: 'initial' },
      unsubscribe: () => {},
    };
    entry.unsubscribe = stream.subscribe((s) => {
      entry.snapshot = {
        advisories: s.payload.advisories,
        isConnected: s.isConnected,
        lastFrameKind: s.payload.lastFrameKind,
      };
      this.notify();
    });
    this.entries.set(resource.id, entry);
    // eslint-disable-next-line no-restricted-globals -- Availability probe; requests use authorizedFetch.
    if (typeof fetch !== 'undefined') {
      stream.start();
    }
  }

  /**
   * Aggregate connection state across all streams. `true` iff every active
   * stream is connected. With zero streams, returns `false` (no signal yet).
   */
  private deriveIsConnected(): boolean {
    if (this.entries.size === 0) return false;
    for (const entry of this.entries.values()) {
      if (!entry.snapshot.isConnected) return false;
    }
    return true;
  }

  /**
   * Aggregate frame discriminator:
   * - `initial` if no stream has delivered any frame.
   * - `snapshot` if at least one stream has delivered its first LIFECYCLE
   *   snapshot but no UPDATE has been seen by any stream.
   * - `update` once any stream has delivered an UPDATE frame.
   */
  private deriveLastFrameKind(): 'initial' | 'snapshot' | 'update' {
    let sawSnapshot = false;
    for (const entry of this.entries.values()) {
      if (entry.snapshot.lastFrameKind === 'update') return 'update';
      if (entry.snapshot.lastFrameKind === 'snapshot') sawSnapshot = true;
    }
    return sawSnapshot ? 'snapshot' : 'initial';
  }

  private notify(): void {
    const s = this.snapshot();
    for (const l of this.listeners) {
      try {
        l(s);
      } catch (e) {
        console.warn('[advisory] listener error:', e);
      }
    }
  }
}

/**
 * Construct an {@link AdvisoryStore} and start its stream when the runtime
 * supports {@code EventSource}; in non-browser environments (Node tests
 * without happy-dom EventSource shim, SSR), the returned store stays dormant —
 * subscribers observe an empty snapshot and never connect.
 *
 * <p>Group B4 follow-up: replaces the previous module-level singleton. {@code Shell}
 * constructs one store at mount and passes it to each advisory chrome element
 * via a property — eliminating the first-caller-wins {@code apiBase} race the
 * singleton had.
 *
 * @param multiplex Tempdoc 662: the shared `MultiplexedStream` each recognized advisory
 *     Resource subscribes on instead of opening its own EventSource. Omit to keep the
 *     pre-662 direct-connection behavior (e.g. in tests that don't exercise multiplexing).
 */
export function createAdvisoryStore(
  apiBase?: string,
  multiplex?: MultiplexedStream,
): AdvisoryStore {
  const store = new AdvisoryStore({ apiBase, multiplex });
  // eslint-disable-next-line no-restricted-globals -- Availability probe; requests use authorizedFetch.
  if (typeof fetch !== 'undefined') {
    store.start();
  }
  return store;
}
