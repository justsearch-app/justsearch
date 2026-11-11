// SPDX-License-Identifier: Apache-2.0
/**
 * `<jf-action-ledger>` — Activity's one feed (tempdoc 550 C1, S6 Activity).
 *
 * One chronological list over the three records of what happened, joined by exact identities in
 * {@link projectActivityFeed}: the backend action ledger (`GET /api/action-ledger` seed plus its
 * live stream), the committed operation history (`core.operation-history`) and this window's
 * Effect Journal. Each source's state is said separately — loading, unavailable, partial, or last
 * known while its live channel is down — so an empty list is only claimed about sources read.
 *
 * Arbitration between the ledger's two reads: the stream's snapshot is the Engine's current
 * retention and wins; the GET seed only stands in before the first snapshot, and an answer that
 * started before a reset, or arrives after a snapshot, is discarded. The view is bounded by what the
 * sources retain (the ledger ring, the history window, the journal) and says so; nothing older is
 * fetched. A row whose source no longer holds it leaves when its last contributing source drops it.
 *
 * Filters: one quick choice (All / Agents / Your actions / Indexing / Problems) and advanced
 * originator, outcome, proposal-decision, routine and date constraints (values OR within a
 * dimension, dimensions AND together).
 */

import { html, css, nothing, type PropertyValues, type TemplateResult } from 'lit';
import '@lit-labs/virtualizer';
import { JfElement } from '../primitives/JfElement.js';
import {
  ActionLedgerClient,
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
import { EnvelopeStream, type EnvelopeReducer } from '../streaming/EnvelopeStream.js';
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

/** A collapsed indexing burst — adjacent same-group index rows summarized, members kept. */
interface IndexBurstSummary {
  readonly summary: true;
  readonly key: string;
  readonly collection: string;
  readonly members: readonly FeedRow[];
  readonly failed: number;
  readonly occurredAt: string;
}

function isBurst(row: FeedRow | IndexBurstSummary): row is IndexBurstSummary {
  return (row as IndexBurstSummary).summary === true;
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
}

const QUICK_FILTERS: ReadonlyArray<readonly [QuickFilter, string]> = [
  ['all', 'All'], ['agents', 'Agents'], ['mine', 'Your actions'], ['indexing', 'Indexing'], ['problems', 'Problems'],
];

const NOTE_TEXT: Record<FeedNote, string> = {
  'recorded-locally': 'Recorded locally',
  'ambiguous-legacy': 'Older local record: it may be the same event as another row, which cannot be proven',
  'ambiguous-history': 'Several history records share this identity',
  'outcome-unavailable': 'Outcome unavailable',
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
    expanded: { state: true },
    // tempdoc 812 D4 — pathHash → friendly name, filled lazily by core.resolve-path-hash.
    resolvedNames: { state: true },
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
  /** Scan rollups and bursts the person expanded, by key. */
  declare expanded: readonly string[];
  declare resolvedNames: Record<string, string>;

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
  /** The first record on screen before new records arrive, when the reader has scrolled into the list. */
  private readingAnchor: { key: string; top: number } | null = null;

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
    this.expanded = [];
    this.resolvedNames = {};
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
      .expand:focus-visible,
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
        .expand:focus-visible,
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
      /* tempdoc 812 D4 — the rollup/burst expand control reads as the row's label: same type and
         colour, only the disclosure caret is added. */
      .expand {
        flex: 1;
        min-width: 0;
        padding: 0;
        border: 0;
        background: none;
        color: inherit;
        font: inherit;
        text-align: left;
        cursor: pointer;
      }
      .child {
        padding-left: 1.25rem;
      }
      .child.empty {
        padding: 0.25rem 0 0.25rem 1.25rem;
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
    void this.seedFromLedgerSnapshot(generation);
    this.openHistory();
    this.journal = listJournal();
    this.stops.push(subscribeJournal(() => { this.journal = listJournal(); }));
    this.seenCursor = getSeenCursor();
    this.stops.push(subscribeSeenCursor(() => { this.seenCursor = getSeenCursor(); }));
    this.armMidnight();
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
    const reducer: EnvelopeReducer<HistorySourceState> = (state, envelope) => {
      const kind = envelope.frameKind === 'LIFECYCLE' ? (envelope.payload as { kind?: string } | null)?.kind : undefined;
      return {
        inner: strategy.reducer(state.inner, envelope),
        snapshotSeen: kind === 'snapshot' ? true : kind === 'reset' ? false : state.snapshotSeen,
      };
    };
    const base = (this.apiBase ?? '').replace(/\/$/, '');
    const stream = new EnvelopeStream<HistorySourceState>({
      url: /^https?:\/\//i.test(resource.endpoint) ? resource.endpoint : `${base}${resource.endpoint}`,
      initialState: { inner: strategy.initialState, snapshotSeen: false },
      reducer,
      ...(this.historyEventSourceFactory ? { eventSourceFactory: this.historyEventSourceFactory } : {}),
    });
    const unsubscribe = stream.subscribe((snap) => {
      this.history = snap.payload;
      this.historyState = !snap.payload.snapshotSeen ? 'loading' : snap.isConnected ? 'live' : 'last-known';
    });
    stream.start();
    this.stops.push(() => { unsubscribe(); stream.stop(); });
  }

  /**
   * Live arrivals keep the reader's place: newest-first rows land above whatever is being read, so
   * once the list's start is scrolled away, the first record on screen is noted before the update
   * and the scroller is moved by however far that record was pushed once the list has laid out.
   */
  protected override willUpdate(changed: PropertyValues<this>): void {
    this.readingAnchor = null;
    const arrivals = changed.has('ledger') || changed.has('history') || changed.has('journal') || changed.has('seed');
    if (!arrivals || changed.has('filter') || changed.has('expanded') || this.getBoundingClientRect().top >= 0) return;
    let best: { key: string; top: number } | null = null;
    for (const row of this.renderRoot.querySelectorAll<HTMLElement>('[data-key]')) {
      const top = row.getBoundingClientRect().top;
      if (top >= 0 && (!best || top < best.top)) best = { key: row.dataset.key ?? '', top };
    }
    this.readingAnchor = best;
  }

  protected override updated(): void {
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
    if (this.ledger?.snapshotApplied || !this.seed) return live;
    const ids = new Set(live.map((e) => e.id).filter(Boolean));
    return [...this.seed.filter((e) => !e.id || !ids.has(e.id)), ...live];
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
    if (!pathHash || this.resolvingHashes.has(pathHash) || this.resolvedNames[pathHash]) return;
    this.resolvingHashes.add(pathHash);
    const generation = this.mountGeneration;
    const resolve = this.resolveHash ?? resolvePathLazy;
    void resolve(INDEXING_JOBS_RESOURCE, pathHash)
      .then((path) => {
        if (path && generation === this.mountGeneration) {
          this.resolvedNames = { ...this.resolvedNames, [pathHash]: friendlyPathName(path) };
        } else this.resolvingHashes.delete(pathHash);
      })
      .catch(() => { this.resolvingHashes.delete(pathHash); });
  }

  /** Relabel an index row with its resolved name when one is known (else leave it untouched). */
  private withResolvedLabel(row: UnifiedActionEntry): UnifiedActionEntry {
    if (row.kind !== 'index' || !row.pathHash) return row;
    this.ensureResolvedName(row.pathHash);
    const name = this.resolvedNames[row.pathHash];
    if (!name) return row;
    return { ...row, label: `${row.outcome === 'FAILED' ? 'Index failed' : 'Indexed'} · ${name}` };
  }

  private toggleExpanded(key: string): void {
    this.expanded = this.expanded.includes(key) ? this.expanded.filter((k) => k !== key) : [...this.expanded, key];
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
      + (this.filter.routine !== 'auto' ? 1 : 0) + (this.filter.date !== 'all' ? 1 : 0);
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
        <div class="group">
          <span class="group-label">date</span>
          <jf-filter-chip ?active=${this.filter.date === 'today'} data-testid="feed-date-today"
            @click=${() => this.setFilter({ date: this.filter.date === 'today' ? 'all' : 'today' })}>Today only</jf-filter-chip>
        </div>
      </div>
    </details>`;
  }

  private renderSourceStates(feed: readonly FeedRow[]): TemplateResult {
    const states: Array<[keyof typeof SOURCE_NAMES, SourceState]> = [['ledger', this.ledgerState()], ['history', this.historyState]];
    const affected = states.filter(([, state]) => state !== 'live');
    const anyRead = states.some(([, s]) => s === 'live' || s === 'last-known') || this.journal.length > 0;
    const words = (state: SourceState): string => state === 'loading' ? 'loading'
      : state === 'unavailable' ? 'could not be read' : 'showing last-known records; live updates are not connected';
    return html`${affected.map(([name, state]) => html`<p class="source-state" role="status"
      data-testid=${`feed-source-${name}`} data-state=${state}>${anyRead && feed.length ? 'Partial activity: ' : ''}${SOURCE_NAMES[name]} ${words(state)}.</p>`)}`;
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
   * tempdoc 812 D4 — one scan = one collapsible row, expandable to the per-document rows of that
   * scan still retained. The rollup is the durable record; the children are surviving detail.
   */
  private renderScanRollup(row: FeedRow, feed: readonly FeedRow[]): TemplateResult {
    const scanId = row.entry.scanId ?? '';
    const key = `scan:${scanId}`;
    const expanded = this.expanded.includes(key);
    const children = expanded ? feed.filter((r) => r.entry.kind === 'index' && r.entry.scanId === scanId) : [];
    const isNew = this.isNew(row.entry.occurredAt);
    return html`<div class="row" data-testid="ledger-scan-rollup" data-kind="scan" data-scan-id=${scanId} data-key=${row.key}
        ?data-new=${isNew} ?data-problem=${row.problem}>
        ${this.newDot(isNew)}
        <span class="who" data-originator=${row.entry.originator}>${row.entry.originator}</span>
        <button type="button" class="expand" data-testid="ledger-scan-expand" aria-expanded=${expanded ? 'true' : 'false'}
          @click=${() => this.toggleExpanded(key)}>${expanded ? '▾' : '▸'} ${row.entry.label}</button>
        <span class="src">backend</span>
        ${renderEventWhen(row.entry.occurredAt)}
      </div>
      ${expanded && children.length === 0
        ? html`<div class="child empty" data-testid="ledger-scan-empty">No per-document rows retained for this scan.</div>` : nothing}
      ${children.map((child) => html`<div class="child" data-testid="ledger-scan-child">
        ${renderEventRow(this.withResolvedLabel(child.entry), { apiBase: this.apiBase })}</div>`)}`;
  }

  /** A burst summary that says how many failed, expandable to every member it stands for. */
  private renderBurst(burst: IndexBurstSummary): TemplateResult {
    const key = `burst:${burst.key}`;
    const expanded = this.expanded.includes(key);
    const isNew = this.isNew(burst.occurredAt);
    const ok = burst.members.length - burst.failed;
    return html`<div class="row" data-testid="ledger-burst" data-kind="index" ?data-new=${isNew} ?data-problem=${burst.failed > 0}>
        ${this.newDot(isNew)}
        <span class="who" data-originator="system">system</span>
        <button type="button" class="expand" data-testid="ledger-burst-expand" aria-expanded=${expanded ? 'true' : 'false'}
          @click=${() => this.toggleExpanded(key)}>${expanded ? '▾' : '▸'} <span class="label">${burst.failed
            ? `Indexed ${ok} · ${burst.failed} failed · ${burst.collection}` : `Indexed ${burst.members.length} · ${burst.collection}`}</span></button>
        <span class="src">backend</span>
        ${renderEventWhen(burst.occurredAt)}
      </div>
      ${expanded ? burst.members.map((member) => html`<div class="child" data-testid="ledger-burst-member">
        ${renderEventRow(this.withResolvedLabel(member.entry), { apiBase: this.apiBase })}</div>`) : nothing}`;
  }

  private renderItem(item: FeedRow | IndexBurstSummary, feed: readonly FeedRow[]): TemplateResult {
    if (isBurst(item)) return this.renderBurst(item);
    if (item.entry.isScanRollup) return this.renderScanRollup(item, feed);
    // tempdoc 612 §UX — the "new" mark is an additive opt-in; History/Timeline render the row without it.
    const sources = [item.ledger ? 'ledger' : '', item.history.length ? 'history' : '', item.journal ? 'journal' : '']
      .filter(Boolean).join(' ');
    return html`<div class="item" data-key=${item.key} data-sources=${sources} ?data-problem=${item.problem}>${renderEventRow(this.withResolvedLabel(item.entry),
      { isNew: this.isNew(item.entry.occurredAt, item.entry.isRoutine), apiBase: this.apiBase })}${this.renderNotes(item)}</div>`;
  }

  override render(): TemplateResult {
    const feed = projectActivityFeed(this.ledgerRows(), this.historyRows(), this.journal);
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
      summarize: (groupKey, items) => ({
        summary: true,
        key: `${groupKey}:${items[0]?.key ?? ''}`,
        collection: items[0]?.entry.collection ?? groupKey,
        members: items,
        failed: items.filter((item) => item.problem).length,
        occurredAt: items[0]?.entry.occurredAt ?? '',
      }),
    });
    return html`${header}<div class="list" data-testid="feed-list">${projected.length <= VIRTUALIZE_ABOVE
      ? projected.map((item) => this.renderItem(item, feed))
      : html`<lit-virtualizer .items=${projected}
          .renderItem=${(item: FeedRow | IndexBurstSummary) => this.renderItem(item, feed)}></lit-virtualizer>`}</div>`;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-action-ledger')) {
  customElements.define('jf-action-ledger', ActionLedgerView);
}
