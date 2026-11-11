// SPDX-License-Identifier: Apache-2.0
/**
 * `<jf-action-ledger>` — Activity's one feed (tempdoc 550 C1, S6 Activity).
 *
 * One chronological list over the three records of what happened, joined by exact identities in
 * {@link projectActivityFeed}: the backend action ledger (`GET /api/action-ledger` seed plus its
 * live stream), the committed operation history (`core.operation-history`) and this window's
 * Effect Journal. Each source's state is said separately — loading, unavailable (it failed before
 * a snapshot read it), partial, or last known while its live channel is down — so an empty list is
 * only claimed about sources read.
 *
 * Arbitration: in both streams a snapshot is the Engine's current retention, but it cannot erase an
 * UPDATE no snapshot has contained yet; only a reset voids earlier observations (applySnapshot).
 * Between the ledger's two reads the stream wins: the GET seed only stands in before the first
 * snapshot, and an answer that started before a reset, or arrives after a snapshot, is discarded. The view is bounded by what the
 * sources retain (the ledger ring, the history window, the journal) and says so; nothing older is
 * fetched. A row whose source no longer holds it leaves when its last contributing source drops it.
 *
 * Filters: one quick choice (All / Agents / Your actions / Indexing / Problems) and advanced
 * originator, outcome, proposal-decision, routine and date constraints (values OR within a
 * dimension, dimensions AND together), within a scope: everything loaded, the records this window's
 * journal holds, or the current agent run (whose session-filtered read joins the feed too).
 *
 * Selection: the feed is a list box whose records are its options; a click, Enter or Space selects
 * one (`feed-select`). The owner holds the selection (`selectedKey`) and shows its details; after
 * every change the feed says what the selected key now stands for (`feed-record`): the joined row
 * found through any of its identities, a burst's or a scan's member records, the records of the
 * same approval request, or null once no loaded record answers to it. Rows hold no controls of
 * their own.
 */

import { html, css, nothing, type PropertyValues, type TemplateResult } from 'lit';
import '@lit-labs/virtualizer';
import { JfElement } from '../primitives/JfElement.js';
import {
  ActionLedgerClient,
  indexLabel,
  indexResultOf,
  openLedgerSource,
  type BackendLedgerEntry,
  type LedgerStreamState,
  type UnifiedActionEntry,
} from '../operations/ActionLedgerClient.js';
import {
  DEFAULT_FEED_FILTER,
  matchesFilter,
  projectActivityFeed,
  routineShown,
  startOfLocalDay,
  type FeedFilter,
  type FeedNote,
  type FeedRow,
  type FeedScope,
  type SelectedRecord,
  historyLedgerId,
  type OperationHistoryRow,
  type QuickFilter,
  type RoutineChoice,
} from '../operations/activityFeed.js';
import { listJournal, subscribeJournal, type JournalEntry } from '../substrates/effects/index.js';
// tempdoc 812 D4 — the SAME hash→path resolver the Tasks bridge uses (`core.indexing-jobs`'
// privacy resolver), so a per-document row reads "corpus/report.pdf", not "default (f7e852)".
import { resolvePathLazy, friendlyPathName } from '../hooks/resolvePathLazy.js';
import type { MultiplexedStream } from '../streaming/MultiplexedStream.js';
import { getSharedShellEventsMultiplex } from '../streaming/shellEventsMultiplexInstance.js';
import { SHELL_EVENT_STREAM_IDS } from '../streaming/shellEventStreamIds.js';
import { EnvelopeStream, type EnvelopeReducer } from '../streaming/EnvelopeStream.js';
import { FetchEventSource, type StreamSource } from '../streaming/FetchEventSource.js';
import { applySnapshot, observe } from '../streaming/snapshotArbitration.js';
import { collapseBursts } from '../projections/boundedProjection.js';
import { compareInstants } from '../primitives/instant.js';
import { getResource, onCatalogChange } from '../../api/registry/ResourceCatalogClient.js';
import { strategyFor, type StrategyState } from '../strategies/subscriptionStrategy.js';
// tempdoc 558 §S1 — the ONE shared ledger-row projection (also used by the retrospective History tab).
import { renderEventRow, renderEventWhen, eventRowStyles } from './eventRow.js';
import './FilterChip.js';
// tempdoc 612 §UX/§CI R1 — the ONE shared "seen" authority (577 §2.14), read here and advanced by
// "mark all read"; a second cursor would re-create the two-authorities bug 577 removed.
import { getSeenCursor, markSeen, subscribeSeenCursor } from '../substrates/recall/recallCursor.js';
import { deepActiveElement, isImeComposition, isTypingTarget } from '../utils/keyboardHandler.js';
import { modalOwnsFocus } from '../primitives/modality.js';

/** A collapsed indexing burst — adjacent same-group index rows summarized, members kept. */
interface IndexBurstSummary {
  readonly summary: true;
  readonly key: string;
  readonly collection: string;
  readonly members: readonly FeedRow[];
  /** Members per what their state establishes (`indexResultOf`); unknown is never counted as indexed. */
  readonly indexed: number;
  readonly failed: number;
  readonly unknown: number;
  readonly occurredAt: string;
}

function isBurst(row: FeedRow | IndexBurstSummary): row is IndexBurstSummary {
  return (row as IndexBurstSummary).summary === true;
}

/** What a burst summary says: how many were indexed, failed or are of unknown state, and where. */
function burstLabel(burst: IndexBurstSummary): string {
  return [`Indexed ${burst.indexed}`, burst.failed ? `${burst.failed} failed` : '',
    burst.unknown ? `${burst.unknown} with unknown state` : '', burst.collection].filter(Boolean).join(' · ');
}

/** The nearest scrolling ancestor across shadow boundaries, else the document's scroller. */
function scrollerOf(start: Element): Element {
  let node: Node | null = start;
  while (node) {
    node = node instanceof ShadowRoot ? node.host : (node as Element).assignedSlot ?? node.parentNode;
    if (node instanceof Element && /(auto|scroll)/.test(getComputedStyle(node).overflowY)
        && node.scrollHeight > node.clientHeight) return node;
  }
  return document.scrollingElement ?? document.documentElement;
}

/** The part of the window a scroller shows, in viewport coordinates. */
function viewOf(scroller: Element): { top: number; bottom: number } {
  return scroller === document.scrollingElement || scroller === document.documentElement
    ? { top: 0, bottom: window.innerHeight } : scroller.getBoundingClientRect();
}

/** The Resource whose `privacy.resolver` (core.resolve-path-hash) resolves an index row's hash. */
const INDEXING_JOBS_RESOURCE = 'core.indexing-jobs';
const HISTORY_RESOURCE = 'core.operation-history';
/** Above this many rows the list is windowed (the Table's threshold); below it renders directly. */
const VIRTUALIZE_ABOVE = 100;

type SourceState = 'loading' | 'live' | 'last-known' | 'unavailable';

interface HistorySourceState {
  readonly inner: StrategyState<unknown>;
  /** A snapshot frame was read since the last reset: only then is an empty history a claim. */
  readonly snapshotSeen: boolean;
  /** UPDATE rows no snapshot has contained since the last reset (see {@link applySnapshot}). */
  readonly unconfirmed: readonly OperationHistoryRow[];
}

/** The history window the strategy keeps; unconfirmed observations beyond it are no longer shown. */
const HISTORY_WINDOW = 200;

/** A history row's identity: its invocation key, else the exact legacy identity of its raw fields. */
const historyIdentity = (row: OperationHistoryRow): string | undefined =>
  row && typeof row.operationId === 'string' && typeof row.endTime === 'string' ? historyLedgerId(row) : undefined;

const QUICK_FILTERS: ReadonlyArray<readonly [QuickFilter, string]> = [
  ['all', 'All'], ['agents', 'Agents'], ['mine', 'Your actions'], ['indexing', 'Indexing'], ['problems', 'Problems'],
];

const NOTE_TEXT: Record<FeedNote, string> = {
  'recorded-locally': 'Recorded locally',
  'ambiguous-legacy': 'Older local record: it may be the same event as another row, which cannot be proven',
  'ambiguous-history': 'Several history records share this identity',
  'outcome-unavailable': 'Outcome unavailable',
};

const SCOPE_WORDS: Record<Exclude<FeedScope, 'all'>, string> = {
  local: 'Showing what was recorded in this window.',
  run: 'Showing the current agent run.',
};

const SOURCE_NAMES = { ledger: 'Activity ledger', history: 'Operation history' } as const;

export class ActionLedgerView extends JfElement {
  static properties = {
    apiBase: { type: String, attribute: 'api-base' },
    // Tempdoc 662: the shared MultiplexedStream (an object, not an attribute-able primitive).
    multiplex: { attribute: false },
    filter: { attribute: false },
    ledger: { state: true },
    ledgerConnected: { state: true },
    seed: { state: true },
    ledgerReadFailed: { state: true },
    history: { state: true },
    historyState: { state: true },
    journal: { state: true },
    // tempdoc 612 §UX — the seen-cursor snapshot, mirrored so "new" marks re-fold when it advances.
    seenCursor: { state: true },
    selectedKey: { attribute: false },
    runRecords: { attribute: false },
    currentRun: { attribute: false },
    // tempdoc 812 D4 — pathHash → resolved path, filled lazily by core.resolve-path-hash.
    resolvedPaths: { state: true },
  } as const;

  declare apiBase: string;
  declare multiplex?: MultiplexedStream;
  /** The active filters; ephemeral (never persisted). */
  declare filter: FeedFilter;
  declare ledger: LedgerStreamState | null;
  declare ledgerConnected: boolean;
  declare seed: readonly BackendLedgerEntry[] | null;
  declare ledgerReadFailed: boolean;
  declare history: HistorySourceState | null;
  declare historyState: SourceState;
  declare journal: readonly JournalEntry[];
  declare seenCursor: string;
  /** The selected record's key, held by the owner; null when none is selected. */
  declare selectedKey: string | null;
  /** The current run's records from its session-filtered read, joined into the feed. */
  declare runRecords: readonly BackendLedgerEntry[];
  /** The current agent run's session id, when there is one: the run scope offers it. */
  declare currentRun: string | null;
  declare resolvedPaths: Record<string, string>;

  /** Hashes asked for and not yet answered or answered with a name; a failed answer is retried later. */
  private readonly resolvingHashes = new Set<string>();
  /** Injected only by tests; production uses the real `core.resolve-path-hash` resolver. */
  resolveHash?: (resourceId: string, pathHash: string) => Promise<string | null>;
  /** Injected only by tests; production uses the real EventSource via openLedgerSource. */
  eventSourceFactory?: (url: string) => EventSource;
  /** Injected only by tests; production opens the operation-history stream with a real EventSource. */
  historyEventSourceFactory?: (url: string) => EventSource;
  /** Injected only by tests; production uses `authorizedFetch` via {@link ActionLedgerClient}. */
  ledgerFetch?: typeof fetch;

  private stops: Array<() => void> = [];
  /** Bumped on every disconnect, so an answer started by an earlier mount changes nothing. */
  private mountGeneration = 0;
  private midnightTimer: ReturnType<typeof setTimeout> | null = null;
  /** The history stream failed since its last snapshot (or before its first): it is unavailable, not loading. */
  private historyFailed = false;
  /** Reopens the history stream now; set while the stream is open. */
  private retryHistory: (() => void) | null = null;
  /** The first record on screen before new records arrive, when the reader has scrolled into the list. */
  private readingAnchor: { key: string; top: number } | null = null;
  /** The feed entry Tab lands on: the one last focused, else the newest. */
  private tabStop = 0;
  /** Bumped by each scrolling key and by the reader's own wheel or pointer: the latest one wins. */
  private keyScroll = 0;

  constructor() {
    super();
    this.apiBase = '';
    this.filter = DEFAULT_FEED_FILTER;
    this.ledger = null;
    this.ledgerConnected = false;
    this.seed = null;
    this.ledgerReadFailed = false;
    this.history = null;
    this.historyState = 'loading';
    this.journal = [];
    this.seenCursor = getSeenCursor();
    this.selectedKey = null;
    this.runRecords = [];
    this.currentRun = null;
    this.resolvedPaths = {};
  }

  // tempdoc 558 §S1 — the row styles are single-sourced in eventRow.ts (shared with the History tab).
  static styles = [
    eventRowStyles,
    css`
      :host {
        display: block;
        font-size: var(--font-size-sm);
        min-width: 0;
      }
      .empty {
        opacity: 0.6;
        padding: 0.5rem 0;
      }
      .link {
        padding: 0;
        border: 0;
        background: none;
        color: var(--accent);
        font: inherit;
        text-decoration: underline;
        cursor: pointer;
      }
      .reveal-routine {
        margin-left: 0.4rem;
      }
      .controls {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 0.5rem 0.75rem;
        padding: 0 0 0.5rem 0;
      }
      .quick {
        display: flex;
        flex-wrap: wrap;
        gap: 0.25rem;
      }
      .quick button {
        padding: 0.125rem 0.6rem;
        border-radius: 9999px;
        border: 1px solid var(--border-subtle);
        background: transparent;
        color: var(--text-secondary);
        font: inherit;
        font-size: var(--font-size-xs);
        cursor: pointer;
      }
      /* Selected reads by weight and a heavier border as well as tint, so it survives forced colours. */
      .quick button[aria-checked='true'] {
        border: 2px solid var(--accent);
        color: var(--text-primary);
        font-weight: 600;
      }
      .quick button:focus-visible,
      .link:focus-visible,
      .entry:focus-visible,
      summary:focus-visible,
      select:focus-visible {
        outline: 2px solid var(--focus-ring-color);
        outline-offset: 1px;
      }
      @media (forced-colors: active) {
        .quick button[aria-checked='true'] {
          border-color: Highlight;
        }
        .quick button:focus-visible,
        .link:focus-visible,
        .entry:focus-visible,
        summary:focus-visible,
        select:focus-visible {
          outline-color: Highlight;
        }
      }
      .new-count {
        font-size: var(--font-size-xs);
        color: var(--text-tint);
        font-weight: 600;
      }
      .mark-read {
        font-size: var(--font-size-xs);
      }
      details.advanced > summary {
        cursor: pointer;
        font-size: var(--font-size-xs);
        color: var(--text-secondary);
      }
      .filters {
        display: flex;
        flex-wrap: wrap;
        gap: 0.75rem;
        padding: 0.25rem 0 0.5rem 0;
      }
      .filters .group {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 0.35rem;
      }
      .group-label {
        font-size: var(--font-size-xs);
        color: var(--text-secondary);
      }
      .notice,
      .source-state {
        margin: 0 0 0.35rem 0;
        font-size: var(--font-size-xs);
        color: var(--text-secondary);
      }
      .notes {
        padding: 0 0 0.25rem 0;
        font-size: var(--font-size-xs);
        color: var(--text-secondary);
      }
      .notes span + span::before {
        content: ' · ';
      }
      .members {
        flex: none;
        color: var(--text-secondary);
        font-size: var(--font-size-xs);
      }
      .row,
      .label {
        min-width: 0;
        overflow-wrap: anywhere;
      }
      .row {
        flex-wrap: wrap;
      }
      lit-virtualizer {
        width: 100%;
      }
      /* An entry spans the row, so a click anywhere on it focuses and selects it. */
      .entry {
        box-sizing: border-box;
        width: 100%;
        padding-inline-start: calc(var(--jf-space-2) + 2 * var(--frame-focus-width));
        cursor: pointer;
      }
      /* Selected reads by a bar at the start as well as by tint: only the selected record has a border
         there, so forced colours, which paint every border, still tell it apart. */
      .entry[aria-selected='true'] {
        padding-inline-start: var(--jf-space-2);
        border-inline-start: calc(2 * var(--frame-focus-width)) solid var(--accent);
        background: var(--accent-tint-16);
      }
      .scope {
        display: inline-flex;
        align-items: center;
        gap: var(--jf-space-1);
        font-size: var(--font-size-xs);
        color: var(--text-primary);
        font-weight: 600;
      }
      @media (forced-colors: active) {
        .entry[aria-selected='true'] {
          border-inline-start-color: Highlight;
        }
      }
    `,
  ];

  override connectedCallback(): void {
    super.connectedCallback();
    const generation = this.mountGeneration;
    // Tempdoc 662: an un-bound `multiplex` property falls back to the shared singleton Shell sets at
    // boot; tests inject `multiplex` or an EventSource factory directly.
    const multiplex = this.multiplex ?? getSharedShellEventsMultiplex() ?? undefined;
    this.stops.push(openLedgerSource({
      apiBase: this.apiBase,
      ...(multiplex ? { multiplex } : {}),
      ...(this.eventSourceFactory ? { eventSourceFactory: this.eventSourceFactory } : {}),
    }, (state, isConnected) => {
      this.ledger = state;
      this.ledgerConnected = isConnected;
    }));
    // Joining a channel the Shell already reads: its connect frames went to the readers before this
    // one, and an idle ledger channel sends nothing more (the connection's heartbeat has its own
    // channel). Start from what the channel has established and whether the connection is up.
    const current = multiplex?.getSnapshot<LedgerStreamState>(SHELL_EVENT_STREAM_IDS.ACTION_LEDGER);
    if (current && current.seq > 0) {
      this.ledger = current.payload;
      this.ledgerConnected = current.isConnected;
    }
    void this.seedFromLedgerSnapshot(generation);
    this.openHistory();
    this.journal = listJournal();
    this.stops.push(subscribeJournal(() => { this.journal = listJournal(); }));
    this.seenCursor = getSeenCursor();
    this.stops.push(subscribeSeenCursor(() => { this.seenCursor = getSeenCursor(); }));
    this.armMidnight();
    window.addEventListener('keydown', this.onPageKey);
    this.stops.push(() => window.removeEventListener('keydown', this.onPageKey));
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.mountGeneration += 1;
    for (const stop of this.stops.splice(0)) stop();
    if (this.midnightTimer !== null) clearTimeout(this.midnightTimer);
    this.midnightTimer = null;
    this.ledger = null;
    this.ledgerConnected = false;
    this.seed = null;
    this.ledgerReadFailed = false;
    this.history = null;
    this.historyState = 'loading';
    this.historyFailed = false;
    this.retryHistory = null;
  }

  /**
   * Tempdoc 804 §B9 (F9) — read the ledger the surface claims to audit, so an undelivered stream
   * frame never renders as a confident "nothing happened". The GET is the same projection as the
   * stream's snapshot; it only stands in until the stream's first snapshot. An answer is dropped if
   * this element was disconnected since, a reset arrived since, or the stream already answered.
   */
  private async seedFromLedgerSnapshot(generation: number): Promise<void> {
    const resetsAtStart = this.ledger?.resets ?? 0;
    const client = new ActionLedgerClient({
      apiBase: this.apiBase,
      ...(this.ledgerFetch ? { fetchImpl: this.ledgerFetch } : {}),
    });
    try {
      const backend = await client.fetchBackendLedger();
      if (generation !== this.mountGeneration || (this.ledger?.resets ?? 0) !== resetsAtStart) return;
      this.ledgerReadFailed = false;
      if (!this.ledger?.snapshotApplied) this.seed = backend;
    } catch {
      if (generation !== this.mountGeneration) return;
      this.ledgerReadFailed = true;
    }
  }

  /** Open `core.operation-history` from its catalog declaration (waiting for the catalog if needed). */
  private openHistory(): void {
    const generation = this.mountGeneration;
    const resource = getResource(HISTORY_RESOURCE);
    if (!resource) {
      const stopWaiting = onCatalogChange(() => {
        if (generation !== this.mountGeneration || !getResource(HISTORY_RESOURCE)) return;
        stopWaiting();
        this.openHistory();
      });
      this.stops.push(stopWaiting);
      return;
    }
    const strategy = strategyFor(resource);
    if (!strategy) {
      this.historyState = 'unavailable';
      return;
    }
    // Snapshot arbitration (amendment 4): a snapshot cannot erase or replace an observation no
    // snapshot has contained yet; only a reset voids what was observed before it.
    const reducer: EnvelopeReducer<HistorySourceState> = (state, envelope) => {
      const payload = envelope.payload as { kind?: string; entries?: unknown } | null;
      const kind = envelope.frameKind === 'LIFECYCLE' ? payload?.kind : undefined;
      if (kind === 'snapshot' && Array.isArray(payload?.entries)) {
        const next = applySnapshot(payload.entries as OperationHistoryRow[], state.unconfirmed, historyIdentity);
        return {
          inner: strategy.reducer(state.inner, { ...envelope, payload: { ...payload, entries: next.rows } }),
          snapshotSeen: true,
          unconfirmed: next.unconfirmed,
        };
      }
      const inner = strategy.reducer(state.inner, envelope);
      if (kind === 'reset') return { inner, snapshotSeen: false, unconfirmed: [] };
      if (envelope.frameKind === 'UPDATE') {
        const unconfirmed = observe(state.unconfirmed, envelope.payload as OperationHistoryRow, historyIdentity);
        return { inner, snapshotSeen: state.snapshotSeen, unconfirmed: unconfirmed.slice(-HISTORY_WINDOW) };
      }
      return { ...state, inner, snapshotSeen: kind === 'snapshot' ? true : state.snapshotSeen };
    };
    // A failure is said only about a source that has not read a snapshot since: once read, a lost
    // channel shows the last-known records instead. Connection or heartbeat frames prove nothing.
    const open = this.historyEventSourceFactory ?? ((url: string) => new FetchEventSource(url));
    const eventSourceFactory = (url: string): StreamSource => {
      const source = open(url) as StreamSource;
      source.addEventListener('error', () => {
        if (generation !== this.mountGeneration || this.history?.snapshotSeen) return;
        this.historyFailed = true;
        this.historyState = 'unavailable';
      });
      return source;
    };
    const base = (this.apiBase ?? '').replace(/\/$/, '');
    const stream = new EnvelopeStream<HistorySourceState>({
      url: /^https?:\/\//i.test(resource.endpoint) ? resource.endpoint : `${base}${resource.endpoint}`,
      initialState: { inner: strategy.initialState, snapshotSeen: false, unconfirmed: [] },
      reducer,
      eventSourceFactory,
    });
    const unsubscribe = stream.subscribe((snap) => {
      this.history = snap.payload;
      if (snap.payload.snapshotSeen) this.historyFailed = false;
      this.historyState = snap.payload.snapshotSeen ? (snap.isConnected ? 'live' : 'last-known')
        : this.historyFailed ? 'unavailable' : 'loading';
    });
    stream.start();
    // The stream reconnects itself with backoff; Retry reopens it now.
    this.retryHistory = () => {
      if (generation !== this.mountGeneration) return;
      this.historyFailed = false;
      if (!this.history?.snapshotSeen) this.historyState = 'loading';
      stream.stop();
      stream.start();
    };
    this.stops.push(() => { unsubscribe(); stream.stop(); });
  }

  /**
   * Live arrivals keep the reader's place: newest-first rows land above whatever is being read, so
   * once the list's start is scrolled away, the first record on screen is noted before the update
   * and the scroller is moved by however far that record was pushed once the list has laid out.
   */
  protected override willUpdate(changed: PropertyValues<this>): void {
    this.readingAnchor = null;
    const arrivals = changed.has('ledger') || changed.has('history') || changed.has('journal') || changed.has('seed')
      || changed.has('runRecords');
    if (!arrivals || changed.has('filter') || this.getBoundingClientRect().top >= 0) return;
    let best: { key: string; top: number } | null = null;
    for (const row of this.renderRoot.querySelectorAll<HTMLElement>('[data-key]')) {
      const top = row.getBoundingClientRect().top;
      if (top >= 0 && (!best || top < best.top)) best = { key: row.dataset.key ?? '', top };
    }
    this.readingAnchor = best;
  }

  protected override updated(changed: PropertyValues<this>): void {
    if (this.selectedKey !== null && ['selectedKey', 'ledger', 'history', 'journal', 'seed', 'runRecords', 'resolvedPaths', 'filter']
      .some((name) => changed.has(name as keyof ActionLedgerView))) {
      this.dispatchEvent(new CustomEvent<{ key: string; record: SelectedRecord | null }>('feed-record',
        { detail: { key: this.selectedKey, record: this.recordFor(this.selectedKey) } }));
    }
    const anchor = this.readingAnchor;
    if (!anchor) return;
    this.readingAnchor = null;
    // The windowed list lays out over the next frames: follow the record for a few of them, and
    // stop as soon as the reader scrolls themselves.
    const scroller = scrollerOf(this);
    let expected = scroller.scrollTop;
    let frames = 6;
    const follow = (): void => {
      if (!this.isConnected || scroller.scrollTop !== expected) return;
      const row = this.renderRoot.querySelector<HTMLElement>(`[data-key="${CSS.escape(anchor.key)}"]`);
      const moved = row ? row.getBoundingClientRect().top - anchor.top : 0;
      if (Math.abs(moved) >= 1) scroller.scrollTop += moved;
      expected = scroller.scrollTop;
      if (--frames > 0) requestAnimationFrame(follow);
    };
    requestAnimationFrame(follow);
  }

  /** Re-render at local midnight so Today's boundary advances; an early wake just re-arms. */
  private armMidnight(): void {
    const now = new Date();
    const next = startOfLocalDay(now);
    next.setDate(next.getDate() + 1);
    this.midnightTimer = setTimeout(() => {
      this.midnightTimer = null;
      if (!this.isConnected) return;
      this.requestUpdate();
      this.armMidnight();
    }, Math.max(1000, next.getTime() - now.getTime()));
  }

  /** The ledger rows this view holds: the stream's after its first snapshot, else the seed plus live rows. */
  private ledgerRows(): readonly BackendLedgerEntry[] {
    const live = this.ledger?.rows ?? [];
    const streamed = this.ledger?.snapshotApplied || !this.seed ? live : (() => {
      const ids = new Set(live.map((e) => e.id).filter(Boolean));
      return [...this.seed!.filter((e) => !e.id || !ids.has(e.id)), ...live];
    })();
    // The run's own read can hold records the general window no longer does; the feed joins by id,
    // so a record in both is one row (the stream's copy first).
    return this.runRecords.length ? [...streamed, ...this.runRecords] : streamed;
  }

  private historyRows(): readonly OperationHistoryRow[] {
    const data = this.history?.inner.data as { events?: unknown[]; entries?: unknown[] } | undefined;
    return ((data?.events ?? data?.entries ?? []) as OperationHistoryRow[])
      .filter((row) => row && typeof row.operationId === 'string' && typeof row.endTime === 'string');
  }

  private ledgerState(): SourceState {
    const read = !!this.ledger?.snapshotApplied || this.seed !== null || (this.ledger?.rows.length ?? 0) > 0;
    if (!read) return this.ledgerReadFailed ? 'unavailable' : 'loading';
    return this.ledgerConnected ? 'live' : 'last-known';
  }

  /**
   * tempdoc 812 D4 — resolve an index row's pathHash to a friendly name, then re-render so the row
   * relabels in place. Until it resolves the short-hash label stands; an unanswered resolve is
   * forgotten so a later render can ask again once the resolver recovers.
   */
  private ensureResolvedName(pathHash: string): void {
    if (!pathHash || this.resolvingHashes.has(pathHash) || this.resolvedPaths[pathHash]) return;
    this.resolvingHashes.add(pathHash);
    const generation = this.mountGeneration;
    // Ask the Engine this view reads from, as Library and the failed-files list do (amendment 14).
    const resolve = this.resolveHash
      ?? ((resource: string, hash: string) => resolvePathLazy(resource, hash, { apiBase: this.apiBase || undefined }));
    void resolve(INDEXING_JOBS_RESOURCE, pathHash)
      .then((path) => {
        if (path && generation === this.mountGeneration) {
          this.resolvedPaths = { ...this.resolvedPaths, [pathHash]: path };
        } else this.resolvingHashes.delete(pathHash);
      })
      .catch(() => { this.resolvingHashes.delete(pathHash); });
  }

  /** Relabel an index row with its resolved name when one is known (else leave it untouched). */
  private withResolvedLabel(row: UnifiedActionEntry): UnifiedActionEntry {
    if (row.kind !== 'index' || !row.pathHash) return row;
    this.ensureResolvedName(row.pathHash);
    const path = this.resolvedPaths[row.pathHash];
    if (!path) return row;
    return { ...row, label: indexLabel(row.indexState, friendlyPathName(path)) };
  }

  /** A feed row as the list and the details show it: its file named once resolved. */
  private shown(row: FeedRow): FeedRow {
    const entry = this.withResolvedLabel(row.entry);
    return entry === row.entry ? row : { ...row, entry };
  }

  /** The projection the last render drew: every loaded row, and the visible items in order. */
  private drawn: { feed: readonly FeedRow[]; items: ReadonlyArray<FeedRow | IndexBurstSummary> } = { feed: [], items: [] };

  /** What `key` stands for now, or null when no loaded record answers to it. */
  private recordFor(key: string): SelectedRecord | null {
    const { feed, items } = this.drawn;
    const paths = this.resolvedPaths;
    const burst = items.find((item): item is IndexBurstSummary => isBurst(item) && item.key === key);
    if (burst) {
      return { key, kind: 'burst', label: burstLabel(burst), occurredAt: burst.occurredAt,
        members: burst.members.map((m) => this.shown(m)), related: [], paths };
    }
    const found = feed.find((r) => r.key === key) ?? feed.find((r) => r.aliases.includes(key));
    if (!found) return null;
    const row = this.shown(found);
    if (row.entry.isScanRollup) {
      const members = feed.filter((r) => r.entry.kind === 'index' && !!row.entry.scanId && r.entry.scanId === row.entry.scanId);
      return { key: row.key, kind: 'scan', row, label: row.entry.label, occurredAt: row.entry.occurredAt,
        members: members.map((m) => this.shown(m)), related: [], paths };
    }
    const request = row.ledger?.pendingId;
    const related = request ? feed.filter((r) => r !== found && r.ledger?.pendingId === request) : [];
    return { key: row.key, kind: 'record', row, label: row.entry.label, occurredAt: row.entry.occurredAt,
      members: [], related, paths };
  }

  /** Put the keyboard on the record `key` (bringing it into the windowed list); false when not shown. */
  async focusRecord(key: string): Promise<boolean> {
    await this.updateComplete;
    const items = this.drawn.items;
    let index = items.findIndex((item) => item.key === key);
    if (index < 0) index = items.findIndex((item) => !isBurst(item) && item.aliases.includes(key));
    if (index < 0) return false;
    await this.focusEntry(index, items.length, 'nearest');
    return (this.renderRoot as ShadowRoot).activeElement?.classList.contains('entry') ?? false;
  }

  private select(key: string): void {
    this.dispatchEvent(new CustomEvent<{ key: string }>('feed-select', { detail: { key } }));
  }

  private setFilter(change: Partial<FeedFilter>): void {
    this.filter = { ...this.filter, ...change };
  }

  /** A foreground row newer than the one shared seen-cursor (parsed instants). Routine rows are never marked. */
  private isNew(occurredAt: string, routine = false): boolean {
    return !routine && (this.seenCursor === '' ? !!occurredAt : compareInstants(occurredAt, this.seenCursor) > 0);
  }

  /**
   * "Mark all read" moves the shared cursor to the newest loaded foreground record, whatever the
   * filters show — the cursor's scope is every loaded record, not the visible ones.
   */
  private markAllRead(feed: readonly FeedRow[]): void {
    let newest = '';
    for (const row of feed) {
      if (!row.entry.isRoutine && (newest === '' || compareInstants(row.entry.occurredAt, newest) > 0)) newest = row.entry.occurredAt;
    }
    if (newest) markSeen(newest);
  }

  private onQuickKey(event: KeyboardEvent): void {
    const order = QUICK_FILTERS.map(([value]) => value);
    const step = event.key === 'ArrowRight' || event.key === 'ArrowDown' ? 1
      : event.key === 'ArrowLeft' || event.key === 'ArrowUp' ? -1 : 0;
    if (!step) return;
    event.preventDefault();
    const next = order[(order.indexOf(this.filter.quick) + step + order.length) % order.length]!;
    this.setFilter({ quick: next });
    void this.updateComplete.then(() => {
      this.renderRoot.querySelector<HTMLElement>(`[data-quick="${next}"]`)?.focus();
    });
  }

  private renderQuick(): TemplateResult {
    return html`<div class="quick" role="radiogroup" aria-label="Show activity" data-testid="feed-quick"
      @keydown=${(e: KeyboardEvent) => this.onQuickKey(e)}>
      ${QUICK_FILTERS.map(([value, label]) => html`<button type="button" role="radio"
        data-quick=${value} aria-checked=${this.filter.quick === value ? 'true' : 'false'}
        tabindex=${this.filter.quick === value ? '0' : '-1'}
        @click=${() => this.setFilter({ quick: value })}>${label}</button>`)}
    </div>`;
  }

  /** Distinct values + counts over all loaded rows (a stable chip set). */
  private facet(feed: readonly FeedRow[], pick: (row: FeedRow) => string | undefined): Array<{ value: string; count: number }> {
    const counts = new Map<string, number>();
    for (const row of feed) {
      const v = pick(row);
      if (v) counts.set(v, (counts.get(v) ?? 0) + 1);
    }
    return [...counts.entries()].map(([value, count]) => ({ value, count }));
  }

  private renderAdvanced(feed: readonly FeedRow[]): TemplateResult {
    const originators = this.facet(feed, (r) => r.entry.originator);
    const outcomes = this.facet(feed, (r) => r.entry.outcome);
    const proposals = this.facet(feed, (r) => r.proposal);
    const toggle = <K extends 'originators' | 'outcomes' | 'proposals'>(dim: K, value: string): void => {
      const cur = this.filter[dim] as readonly string[];
      this.setFilter({ [dim]: cur.includes(value) ? cur.filter((v) => v !== value) : [...cur, value] } as Partial<FeedFilter>);
    };
    const chips = (dim: 'originators' | 'outcomes' | 'proposals', label: string, values: Array<{ value: string; count: number }>) =>
      html`<div class="group" data-testid=${`feed-facet-${dim}`}>
        <span class="group-label">${label}</span>
        ${values.map((f) => html`<jf-filter-chip ?active=${(this.filter[dim] as readonly string[]).includes(f.value)}
          @click=${() => toggle(dim, f.value)}>${f.value} <span class="chip-count">(${f.count})</span></jf-filter-chip>`)}
      </div>`;
    const active = this.filter.originators.length + this.filter.outcomes.length + this.filter.proposals.length
      + (this.filter.routine !== 'auto' ? 1 : 0) + (this.filter.date !== 'all' ? 1 : 0) + (this.filter.scope !== 'all' ? 1 : 0);
    return html`<details class="advanced" data-testid="feed-advanced">
      <summary>Filter${active ? ` (${active} active)` : ''}</summary>
      <div class="filters" data-testid="ledger-filters">
        ${originators.length > 1 ? chips('originators', 'who', originators) : nothing}
        ${outcomes.length > 1 ? chips('outcomes', 'outcome', outcomes) : nothing}
        ${proposals.some((p) => p.value !== 'normal') ? chips('proposals', 'proposal', proposals) : nothing}
        <div class="group">
          <label class="group-label" for="routine-choice">routine activity</label>
          <select id="routine-choice" data-testid="feed-routine-choice"
            @change=${(e: Event) => this.setFilter({ routine: (e.target as HTMLSelectElement).value as RoutineChoice })}>
            <option value="auto" ?selected=${this.filter.routine === 'auto'}>Automatic: hidden in All and Agents</option>
            <option value="show" ?selected=${this.filter.routine === 'show'}>Always show, in every view</option>
            <option value="hide" ?selected=${this.filter.routine === 'hide'}>Always hide, in every view</option>
          </select>
        </div>
        <div class="group" data-testid="feed-scope-choices">
          <span class="group-label">records</span>
          <jf-filter-chip ?active=${this.filter.scope === 'local'} data-testid="feed-scope-local"
            @click=${() => this.setScope(this.filter.scope === 'local' ? 'all' : 'local')}>Recorded in this window</jf-filter-chip>
          ${this.currentRun ? html`<jf-filter-chip ?active=${this.filter.scope === 'run'} data-testid="feed-scope-run"
            @click=${() => this.setScope(this.filter.scope === 'run' ? 'all' : 'run')}>This run</jf-filter-chip>` : nothing}
        </div>
        <div class="group">
          <span class="group-label">date</span>
          <jf-filter-chip ?active=${this.filter.date === 'today'} data-testid="feed-date-today"
            @click=${() => this.setFilter({ date: this.filter.date === 'today' ? 'all' : 'today' })}>Today only</jf-filter-chip>
        </div>
      </div>
    </details>`;
  }

  /** The scope's words, and the way back to everything loaded. */
  private renderScope(): TemplateResult | typeof nothing {
    const scope = this.filter.scope;
    if (scope === 'all') return nothing;
    return html`<span class="scope" data-testid="feed-scope" data-scope=${scope}>${SCOPE_WORDS[scope]}
      <button type="button" class="link" data-testid="feed-scope-clear" @click=${() => this.setScope('all')}>Show all activity</button></span>`;
  }

  private setScope(scope: FeedScope): void {
    this.setFilter(scope === 'run' && this.currentRun ? { scope, runId: this.currentRun } : { scope });
  }

  private renderSourceStates(feed: readonly FeedRow[]): TemplateResult {
    const states: Array<[keyof typeof SOURCE_NAMES, SourceState]> = [['ledger', this.ledgerState()], ['history', this.historyState]];
    const affected = states.filter(([, state]) => state !== 'live');
    const anyRead = states.some(([, s]) => s === 'live' || s === 'last-known') || this.journal.length > 0;
    const words = (state: SourceState): string => state === 'loading' ? 'loading'
      : state === 'unavailable' ? 'could not be read' : 'showing last-known records; live updates are not connected';
    return html`${affected.map(([name, state]) => html`<p class="source-state" role="status"
      data-testid=${`feed-source-${name}`} data-state=${state}>${anyRead && feed.length ? 'Partial activity: ' : ''}${SOURCE_NAMES[name]} ${words(state)}.${
        name === 'history' && state === 'unavailable' && this.retryHistory
          ? html` <button type="button" class="retry" data-testid="feed-source-history-retry"
              @click=${() => this.retryHistory?.()}>Retry</button>` : nothing}</p>`)}`;
  }

  private renderNotes(row: FeedRow): TemplateResult | typeof nothing {
    if (!row.notes.length) return nothing;
    return html`<div class="notes" data-testid="feed-notes">${row.notes.map((note) =>
      html`<span data-testid="feed-note" data-note=${note}>${NOTE_TEXT[note]}</span>`)}</div>`;
  }

  private newDot(isNew: boolean): TemplateResult | typeof nothing {
    return isNew ? html`<span class="new-dot" data-testid="ledger-new-dot" role="img" aria-label="new since you looked"></span>` : nothing;
  }

  /**
   * tempdoc 812 D4 — one scan = one row; the per-document rows of that scan still retained are its
   * members, listed in its details. The rollup is the durable record; the members are surviving detail.
   */
  private renderScanRollup(row: FeedRow, feed: readonly FeedRow[]): TemplateResult {
    const scanId = row.entry.scanId ?? '';
    const members = feed.filter((r) => r.entry.kind === 'index' && r.entry.scanId === scanId).length;
    const isNew = this.isNew(row.entry.occurredAt);
    return html`<div class="row" data-testid="ledger-scan-rollup" data-kind="scan" data-scan-id=${scanId} data-key=${row.key}
        ?data-new=${isNew} ?data-problem=${row.problem}>
        ${this.newDot(isNew)}
        <span class="who" data-originator=${row.entry.originator}>${row.entry.originator}</span>
        <span class="label">${row.entry.label}</span>
        <span class="members" data-testid="ledger-scan-members">${members ? `${members} document${members === 1 ? '' : 's'} retained` : 'no documents retained'}</span>
        <span class="src">backend</span>
        ${renderEventWhen(row.entry.occurredAt)}
      </div>`;
  }

  /** A burst summary that says how many failed; its members are listed in its details. */
  private renderBurst(burst: IndexBurstSummary): TemplateResult {
    const isNew = this.isNew(burst.occurredAt);
    return html`<div class="row" data-testid="ledger-burst" data-kind="index" data-key=${burst.key} ?data-new=${isNew} ?data-problem=${burst.failed > 0}>
        ${this.newDot(isNew)}
        <span class="who" data-originator="system">system</span>
        <span class="label">${burstLabel(burst)}</span>
        <span class="src">backend</span>
        ${renderEventWhen(burst.occurredAt)}
      </div>`;
  }

  private renderItem(item: FeedRow | IndexBurstSummary, feed: readonly FeedRow[]): TemplateResult {
    if (isBurst(item)) return this.renderBurst(item);
    if (item.entry.isScanRollup) return this.renderScanRollup(item, feed);
    // tempdoc 612 §UX — the "new" mark is an additive opt-in; History/Timeline render the row without it.
    const sources = [item.ledger ? 'ledger' : '', item.history.length ? 'history' : '', item.journal ? 'journal' : '']
      .filter(Boolean).join(' ');
    // The receipt is read in the record's details, not on every row.
    const { operationOutcomeKey: _receipt, ...entry } = this.withResolvedLabel(item.entry);
    return html`<div class="item" data-key=${item.key} data-sources=${sources} ?data-problem=${item.problem}>${renderEventRow(entry,
      { isNew: this.isNew(item.entry.occurredAt, item.entry.isRoutine), apiBase: this.apiBase })}${this.renderNotes(item)}</div>`;
  }

  /**
   * The feed's own keys: Home and End (with or without Ctrl) go to the newest and oldest loaded
   * entry, PageUp and PageDown by a screenful, and the entry reached is focused. The browser's own
   * scrolling for these keys cannot be relied on over the windowed list: its smooth scroll is cut
   * short whenever newly measured rows correct the position.
   */
  private onListKey(e: KeyboardEvent, count: number): void {
    if (e.altKey || e.shiftKey || e.metaKey) return;
    const target = e.target as Element;
    if (target.closest('input, select, textarea, [contenteditable]')) return;
    if ((e.key === 'Enter' || e.key === ' ') && !e.ctrlKey && !isImeComposition(e)) {
      const key = target.closest<HTMLElement>('.entry')?.dataset.entryKey;
      if (key) { e.preventDefault(); this.select(key); }
      return;
    }
    const current = Number(target.closest<HTMLElement>('.entry')?.dataset.index ?? this.tabStop);
    let next: number;
    switch (e.key) {
      case 'Home': next = 0; break;
      case 'End': next = count - 1; break;
      case 'PageUp': next = current - this.pageSize(); break;
      case 'PageDown': next = current + this.pageSize(); break;
      default: return;
    }
    e.preventDefault();
    void this.focusEntry(Math.max(0, Math.min(count - 1, next)), count, next <= current ? 'start' : 'end');
  }

  /**
   * The browser's own Home, End (with or without Ctrl), PageUp and PageDown when focus is not in the
   * feed's entries: on the page, or on a control beside the feed. Which scroller they move is the
   * browser's choice, so nothing is done unless it starts moving the feed's scroller. Over the
   * windowed list the browser's smooth scroll stops short: the list's correction for the rows it has
   * just measured is a scroll of its own, which ends the smooth one partway. So once that scroll has
   * ended, the feed's scroller is taken the rest of the way to the key's destination, instantly. Keys
   * already handled (the feed's entries, other areas' own keys), a text field or an open modal are
   * left alone, and the reader's own wheel, touch or click cancels the rest.
   */
  private readonly onPageKey = (e: KeyboardEvent): void => {
    if (e.defaultPrevented || e.altKey || e.shiftKey || e.metaKey || isImeComposition(e) || modalOwnsFocus()) return;
    if (!['Home', 'End', 'PageUp', 'PageDown'].includes(e.key) || (e.ctrlKey && e.key.startsWith('Page'))) return;
    if (isTypingTarget(deepActiveElement()) || !this.renderRoot.querySelector('lit-virtualizer')) return;
    const scroller = scrollerOf(this);
    const events: EventTarget = scroller === document.scrollingElement || scroller === document.documentElement
      ? document : scroller;
    const from = { top: scroller.scrollTop, entry: this.rowOnScreen(scroller, e.key === 'PageDown' ? 'last' : 'first') };
    const token = ++this.keyScroll;
    const cancel = (): void => { this.keyScroll += 1; };
    const reader = ['wheel', 'pointerdown', 'touchstart'] as const;
    for (const type of reader) window.addEventListener(type, cancel, { capture: true, passive: true });
    const stop = (): void => {
      events.removeEventListener('scroll', started);
      events.removeEventListener('scrollend', ended);
      for (const type of reader) window.removeEventListener(type, cancel, { capture: true });
      clearTimeout(timer);
    };
    // No scroll on the feed's scroller soon after the key: the browser moved something else, or nothing.
    let timer = setTimeout(stop, 500);
    const started = (): void => {
      events.removeEventListener('scroll', started);
      clearTimeout(timer);
      timer = setTimeout(ended, 2_000);
    };
    const ended = (): void => {
      stop();
      if (token === this.keyScroll && this.isConnected) void this.finishKeyScroll(scroller, e.key, from, token);
    };
    events.addEventListener('scroll', started);
    events.addEventListener('scrollend', ended);
  };

  /** The first or last entry at least partly on screen, and where its top is within the view. */
  private rowOnScreen(scroller: Element, which: 'first' | 'last'): { index: string; top: number } | null {
    const view = viewOf(scroller);
    let found: { index: string; top: number } | null = null;
    for (const entry of this.renderRoot.querySelectorAll<HTMLElement>('.entry')) {
      const box = entry.getBoundingClientRect();
      if (box.bottom <= view.top || box.top >= view.bottom) continue;
      if (!found || (which === 'first' ? box.top < found.top : box.top > found.top)) {
        found = { index: entry.dataset.index ?? '', top: box.top - view.top };
      }
    }
    return found;
  }

  /**
   * Take the scroller to where `key` goes, instantly, and hold it there while the windowed list
   * measures the rows it brings in and corrects for them, until the reader scrolls themselves. Home
   * and End go to the start and the end. PageUp and PageDown move what was on screen by the browser's
   * own page, seven eighths of the view, measured on an entry that was on screen (`from.entry`), so a
   * correction cannot carry the scroll past a row the reader has not seen; with no entry on screen,
   * from where the scroller was (`from.top`).
   */
  private async finishKeyScroll(scroller: Element, key: string,
    from: { top: number; entry: { index: string; top: number } | null }, token: number): Promise<void> {
    const max = (): number => scroller.scrollHeight - scroller.clientHeight;
    const step = Math.max(1, scroller.clientHeight * 0.875) * (key === 'PageUp' ? -1 : 1);
    const page = key === 'PageUp' || key === 'PageDown';
    /** How far the scroller still has to move: by the reference entry for a page, else to the end. */
    const remaining = (): number => {
      const top = scroller.scrollTop;
      if (!page) return (key === 'Home' ? 0 : max()) - top;
      const entry = from.entry && this.renderRoot.querySelector<HTMLElement>(`.entry[data-index="${from.entry.index}"]`);
      const by = from.entry && entry
        ? entry.getBoundingClientRect().top - viewOf(scroller).top - (from.entry.top - step)
        : from.top + step - top;
      return Math.max(-top, Math.min(max() - top, by));
    };
    const cancel = (): void => { this.keyScroll += 1; };
    const reader = ['wheel', 'pointerdown', 'touchstart', 'keydown'] as const;
    for (const type of reader) window.addEventListener(type, cancel, { capture: true, passive: true });
    try {
      await this.listSettled();
      for (let round = 0, still = 0; round < 60 && still < 2 && token === this.keyScroll && this.isConnected; round += 1) {
        const by = remaining();
        if (Math.abs(by) < 1) still += 1;
        else {
          still = 0;
          scroller.scrollTo({ top: scroller.scrollTop + by, behavior: 'instant' });
        }
        await this.listSettled();
      }
    } finally {
      for (const type of reader) window.removeEventListener(type, cancel, { capture: true });
    }
  }

  /** The windowed list has measured the rows it shows (or a moment has passed). */
  private listSettled(): Promise<unknown> {
    const list = this.renderRoot.querySelector('lit-virtualizer') as { layoutComplete?: Promise<void> } | null;
    return Promise.race([list?.layoutComplete?.catch(() => undefined),
      new Promise((resolve) => setTimeout(resolve, 250))]);
  }

  /** How many entries fit on screen, less one kept for context; at least one. */
  private pageSize(): number {
    const view = viewOf(scrollerOf(this));
    let shown = 0;
    for (const entry of this.renderRoot.querySelectorAll<HTMLElement>('.entry')) {
      const box = entry.getBoundingClientRect();
      if (box.top >= view.top && box.bottom <= view.bottom) shown += 1;
    }
    return Math.max(1, shown - 1);
  }

  /**
   * Focus entry `index`. The windowed list renders only entries near the screen, so until it is
   * rendered the scroller is moved to where the entry is estimated to be, and again once the rows
   * there are measured. (The virtualizer's own scroll-to-index is not used: inside an outer
   * scroller that has moved it counts the list's offset twice and renders nothing.)
   */
  private async focusEntry(index: number, count: number, block: ScrollLogicalPosition): Promise<void> {
    this.tabStop = index;
    const find = (): HTMLElement | null => this.renderRoot.querySelector<HTMLElement>(`.entry[data-index="${index}"]`);
    for (let attempt = 0; attempt < 10 && !find(); attempt += 1) {
      const list = this.renderRoot.querySelector('lit-virtualizer');
      if (!list || !this.isConnected || this.tabStop !== index) return;
      const scroller = scrollerOf(this);
      const view = viewOf(scroller);
      const box = list.getBoundingClientRect();
      const estimate = box.top + (box.height * (index + 0.5)) / count;
      scroller.scrollTo({ top: scroller.scrollTop + estimate - (view.top + view.bottom) / 2, behavior: 'instant' });
      await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    }
    const entry = find();
    if (!entry || this.tabStop !== index) return;
    this.moveTabStop(entry);
    entry.focus({ preventScroll: true });
    entry.scrollIntoView({ block, behavior: 'instant' });
  }

  /** One entry is in the Tab order; it follows focus without a re-render. */
  private moveTabStop(entry: HTMLElement): void {
    this.tabStop = Number(entry.dataset.index);
    for (const other of this.renderRoot.querySelectorAll<HTMLElement>('.entry[tabindex="0"]')) other.tabIndex = -1;
    entry.tabIndex = 0;
  }

  private renderEntry(item: FeedRow | IndexBurstSummary, index: number, count: number, feed: readonly FeedRow[]): TemplateResult {
    const selected = this.selectedKey !== null && (item.key === this.selectedKey
      || (!isBurst(item) && item.aliases.includes(this.selectedKey)));
    return html`<div class="entry" role="option" data-index=${index} data-entry-key=${item.key}
      aria-selected=${selected ? 'true' : 'false'} aria-posinset=${index + 1} aria-setsize=${count}
      tabindex=${index === Math.min(this.tabStop, count - 1) ? '0' : '-1'}
      @click=${() => this.select(item.key)}>${this.renderItem(item, feed)}</div>`;
  }

  override render(): TemplateResult {
    const feed = projectActivityFeed(this.ledgerRows(), this.historyRows(), this.journal);
    this.drawn = { feed, items: [] };
    const now = new Date();
    const showRoutine = routineShown(this.filter);
    const matching = feed.filter((row) => matchesFilter(row, this.filter, now));
    const routineCount = matching.filter((row) => row.entry.isRoutine).length;
    // tempdoc 812 D4 — a scan's STARTED row is a placeholder; once its completion exists, the
    // completion IS the record.
    const finishedScans = new Set(feed.filter((r) => r.entry.isScanRollup && r.entry.outcome !== 'STARTED' && r.entry.scanId)
      .map((r) => r.entry.scanId as string));
    const visible = matching.filter((row) => (showRoutine || !row.entry.isRoutine)
      && !(row.entry.isScanRollup && row.entry.outcome === 'STARTED' && row.entry.scanId && finishedScans.has(row.entry.scanId)));
    const newCount = feed.filter((row) => this.isNew(row.entry.occurredAt, row.entry.isRoutine)).length;
    const header = html`
      <div class="controls">
        ${this.renderQuick()}
        ${this.renderScope()}
        ${routineCount > 0 ? html`<jf-filter-chip ?active=${showRoutine} data-testid="ledger-routine-toggle"
          @click=${() => this.setFilter({ routine: showRoutine ? 'hide' : 'show' })}>routine
          <span class="chip-count">(${routineCount})</span></jf-filter-chip>` : nothing}
        ${newCount > 0 ? html`<span class="new-count" data-testid="ledger-new-count">${newCount} new since you looked</span>
          <button type="button" class="link mark-read" data-testid="ledger-mark-read"
            title="Marks every loaded record read, including ones the filters hide"
            @click=${() => this.markAllRead(feed)}>mark all read</button>` : nothing}
      </div>
      ${this.renderAdvanced(feed)}
      <p class="notice" data-testid="feed-window-notice">Recent activity is shown. Older records may not be loaded.</p>
      ${this.renderSourceStates(feed)}`;
    if (feed.length === 0) {
      // tempdoc 804 §B9 (F9) — "no activity" is a claim about the sources, made only once one was read.
      const ledger = this.ledgerState();
      return html`${header}${ledger === 'unavailable'
        ? html`<div class="empty" data-testid="ledger-unreadable">Activity unavailable — the action ledger could not be read.</div>`
        : ledger === 'loading'
          ? html`<div class="empty" data-testid="ledger-loading">Loading activity…</div>`
          : html`<div class="empty" data-testid="ledger-empty">No activity in loaded records.</div>`}`;
    }
    if (visible.length === 0 && this.filter.scope === 'local' && !feed.some((row) => row.journal)) {
      return html`${header}<div class="empty" data-testid="ledger-empty">Nothing recorded in this window yet. What you or
        the assistant does here is listed as it happens.</div>`;
    }
    if (visible.length === 0) {
      const onlyRoutineHidden = !showRoutine && routineCount > 0 && matching.every((row) => row.entry.isRoutine);
      return html`${header}<div class="empty" data-testid="ledger-empty">
        ${onlyRoutineHidden
          ? html`Only routine activity this session.
            <button type="button" class="link reveal-routine" data-testid="ledger-reveal-routine"
              @click=${() => this.setFilter({ routine: 'show' })}>Show routine</button>`
          : html`No activity matches the filter.`}</div>`;
    }
    // Bounded projection (tempdoc 550 III(b)): adjacent same-group index rows collapse into one
    // summary that keeps its members and counts its failures.
    const projected = collapseBursts<FeedRow, IndexBurstSummary>(visible, {
      keyOf: (row) => (row.entry.kind === 'index' ? (row.entry.groupKey ?? 'default') : null),
      // Keyed by its oldest member, which newer arrivals above it do not change.
      summarize: (groupKey, items) => ({
        summary: true,
        key: `burst:${groupKey}:${items[items.length - 1]?.key ?? ''}`,
        collection: items[0]?.entry.collection ?? groupKey,
        members: items,
        indexed: items.filter((item) => indexResultOf(item.entry.indexState) === 'indexed').length,
        failed: items.filter((item) => indexResultOf(item.entry.indexState) === 'failed').length,
        unknown: items.filter((item) => indexResultOf(item.entry.indexState) === 'unknown').length,
        occurredAt: items[0]?.entry.occurredAt ?? '',
      }),
    });
    this.drawn = { feed, items: projected };
    const count = projected.length;
    return html`${header}<div class="list" data-testid="feed-list" role="listbox" aria-label="Activity"
      @keydown=${(e: KeyboardEvent) => this.onListKey(e, count)}
      @focusin=${(e: FocusEvent) => {
        const entry = (e.target as Element).closest<HTMLElement>('.entry');
        if (entry) this.moveTabStop(entry);
      }}>${count <= VIRTUALIZE_ABOVE
      ? projected.map((item, index) => this.renderEntry(item, index, count, feed))
      : html`<lit-virtualizer .items=${projected}
          .keyFunction=${(item: FeedRow | IndexBurstSummary) => item.key}
          .renderItem=${(item: FeedRow | IndexBurstSummary, index: number) => this.renderEntry(item, index, count, feed)}></lit-virtualizer>`}</div>`;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-action-ledger')) {
  customElements.define('jf-action-ledger', ActionLedgerView);
}
