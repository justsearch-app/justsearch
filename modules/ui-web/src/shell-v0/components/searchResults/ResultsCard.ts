// SPDX-License-Identifier: Apache-2.0
/**
 * Search Thread S1 — `<jf-results-card>`: the ONE search-result card.
 *
 * Every rendering of search results in the product goes through this component
 * (Search Thread tempdoc, decision 4): the standalone Search surface's list, the
 * unified window's retrieve tier, and (later stages) committed snapshot cards in
 * the thread and agent tool-search excerpts. It composes the existing shared
 * authorities — `projectResultView` (typed row view), `resultRowPresentation`
 * (path/highlight), `matchCountLabel` (funnel count), `facetChips`,
 * `whyThisResult` — and adds the meta-line richness that previously lived only
 * in SearchSurface (latency, retrieval-mode indicator, quick/refining badges),
 * plus the terminal "refined ✓" stamp (the two-stage search's missing third act)
 * and the count-coherence guarantee (headline never silently disagrees with the
 * rendered rows — the shown>matched case is now visibly classified).
 *
 * Presentation-pure: data in via properties, intent out via composed events
 * (`card-open`, `card-selection`, `card-facet-toggle`, `card-ask-ai`). The only
 * side effects the card owns are presentation-local: clipboard copy (through the
 * injected `copyText` so hosts route their own clipboard seam), the in-control
 * copy receipt (613 §6 — a receipt, never a toast), the shared context menu, and
 * the canonical open-disposition record (580 §17 P3 — the one positive-outcome
 * signal, emitted here so no host can forget it).
 *
 * Registered: run-renderers `searchResultRendering.consumerSites` (one render
 * authority) and execution-surfaces `fe-results-card` (it projects from
 * `SearchTrace.effectiveMode` and, per register F-052, from the `cross-encoder`
 * stage's drop reason; guard: ResultsCard.searchTrace.test).
 */

import { html, css, type TemplateResult, nothing } from 'lit';
import { repeat } from 'lit/directives/repeat.js';
import { JfElement } from '../../primitives/JfElement.js';
import { icon } from '../Icon.js';
import {
  formatDisplayPath,
  formatLocationBreadcrumb,
  highlightTerms,
  highlightStyles,
} from './resultRowPresentation.js';
import { matchCountLabel } from './matchCountLabel.js';
import { isAdvancedMode, subscribeUiMode } from '../../state/uiModeState.js';
import { renderFacetChips, facetChipStyles } from './facetChips.js';
import {
  renderWhyDisclosure,
  whyThisResultStyles,
  type WhyHit,
} from './whyThisResult.js';
import { CROSS_ENCODER_SKIP_WORDING } from '../../aggregate-substrate/strategies/searchTraceExplain.js';
import {
  projectResultView,
  type ResultViewInput,
} from '../../views/searchResultViewModel.js';
import {
  formatAsMarkdown,
  formatAsJson,
  formatAsPaths,
} from '../../utils/searchResultFormatters.js';
import { ReceiptController } from '../../primitives/receiptController.js';
import { openContextMenu } from '../ContextMenu.js';
import { setMenuAnchor } from '../../utils/selectionAnchor.js';
import { recordOpenDisposition } from '../../state/searchState.js';
import { copyToClipboard } from '../../utils/clipboardCopy.js';
import { formatRelative } from '../../utils/relativeTime.js';
import type { Availability } from '../../state/availability.js';
import type { SearchTrace } from '../../../api/generated/index.js';
import '../Control.js';

/** Structural hit shape — satisfied by both SearchHit (state) and SearchHitSnapshot (plugin API). */
export interface CardHit {
  readonly id: string;
  readonly title: string;
  readonly path: string;
  readonly trace?: unknown;
  /** Tempdoc 811 (C-1a) — the corpus the hit came from; drives the row's collection pill. */
  readonly collection?: string;
}

/**
 * Structural snapshot subset the card renders from — satisfied by both the
 * plugin-API `SearchSnapshot` and the state-store `SearchState` (the two hosts'
 * mirrors of the ONE `searchState`), so the card cannot fork the data model.
 */
export interface CardSnapshot {
  readonly query: string;
  readonly results: readonly CardHit[];
  readonly matchCount: number;
  readonly totalHits: number;
  readonly facetsTruncated: boolean;
  readonly isSearching: boolean;
  readonly processingTimeMs: number | null;
  readonly error: string | null;
  readonly isRefining?: boolean;
  readonly passStage?: string | null;
  readonly slowSearch?: boolean;
  readonly searchTrace?: unknown;
  readonly facets?: unknown;
}

export interface CardSelectionDetail {
  ids: string[];
  primaryId: string;
  primaryIndex: number;
}

export interface CardForgetDetail {
  readonly id: string;
  readonly path: string;
  readonly scope: 'FILE' | 'FOLDER';
  readonly invoker: HTMLElement | null;
}

/**
 * Search Thread S4-final — the identity of a COMMITTED (frozen) search, carried by `variant`
 * `'snapshot'` | `'excerpt'`: who ran it, what they typed, and the funnel counts at the moment it
 * froze. Distinct from `snapshot` (the row DATA, which may be empty for a restored thread event —
 * the backend persists only `docIds`, not full hit objects) — `provenance` is always present for a
 * non-`'live'` card; `snapshot` supplies the rows when they happen to be available.
 */
export interface SearchProvenance {
  readonly actor: 'user' | 'agent';
  readonly query: string;
  readonly mode: string;
  readonly matchCount: number;
  readonly resultCount: number;
  /** ISO-8601 — when the frozen search ran. */
  readonly executedAt: string;
}

/** How long the terminal "refined ✓" stamp stays before decaying to the mode text. */
const REFINED_STAMP_MS = 4000;

/**
 * Search Thread S1/S4-final — the retrieval-mode label, shared by the live meta line
 * ({@link ResultsCard.renderRetrievalMode}) and the frozen-card provenance header.
 *
 * Tempdoc 805 §G.2 — `'UNKNOWN'` (a commit whose refined-pass trace was not yet available) is
 * labelled NOTHING, exactly like any unrecognized mode: the absence of a label is honest, whereas
 * every label here is a positive claim about how the search ran.
 */
function retrievalModeLabel(mode: string | null | undefined): string | null {
  if (mode === 'UNKNOWN') return null;
  return mode === 'HYBRID' ? 'Semantic + keyword' : mode === 'VECTOR' ? 'Semantic' : mode === 'TEXT' ? 'Keyword' : null;
}

/** Tempdoc 738 (C2) — the plain-language retrieval-mode label shown in Simple mode.
 *  805 §G.2: `'UNKNOWN'` labels nothing (see {@link retrievalModeLabel}). */
function plainRetrievalModeLabel(mode: string | null | undefined): string | null {
  if (mode === 'UNKNOWN') return null;
  return mode === 'HYBRID'
    ? 'meaning + words'
    : mode === 'VECTOR'
      ? 'meaning-based'
      : mode === 'TEXT'
        ? 'exact-word search'
        : null;
}

export class ResultsCard extends JfElement {
  static properties = {
    snapshot: { attribute: false },
    facetSelections: { attribute: false },
    selectedIds: { attribute: false },
    askAvailability: { attribute: false },
    forgetAvailable: { type: Boolean, attribute: false },
    copyText: { attribute: false },
    variant: { type: String },
    selectionMode: { type: String, attribute: 'selection-mode' },
    refinedStampVisible: { state: true },
    // Search Thread S4-final — the frozen-card identity (who/what/when); required for
    // 'snapshot'/'excerpt', ignored for 'live'.
    provenance: { attribute: false },
    // 'snapshot' — the "Show all N" / "Show less" toggle over the row list (collapsed to 3 by
    // default). 'excerpt' — the inline expand-to-snapshot toggle.
    expanded: { state: true },
    excerptOpen: { state: true },
  };

  declare snapshot: CardSnapshot | null;
  declare facetSelections: Record<string, string[]>;
  declare selectedIds: ReadonlySet<string>;
  /** null hides the Ask AI control (hosts that cannot escalate). */
  declare askAvailability: Availability | null;
  /** True only for a host that handles card-forget through the durable operation boundary. */
  declare forgetAvailable: boolean;
  /** Host clipboard seam (plugin hosts route host.ui.copyToClipboard); defaults to the shared util. */
  declare copyText: (text: string) => Promise<unknown>;
  /** 'live' — the active search. 'snapshot' — a committed frozen record (full render, collapsible
   *  rows). 'excerpt' — the one-line collapsed form of a snapshot (expands in place). */
  declare variant: 'live' | 'snapshot' | 'excerpt';
  /**
   * 'replace' (default) — a plain click replaces the selection with the row and opens it.
   * 'explicit' — opening and choosing are separate acts (Search v3, WP1.1): a plain click or the
   * menu's "Open document" only OPENS, so reading a hit never throws away a multi-file choice;
   * Ctrl/⌘ and Shift only CHOOSE; and the row menu carries a choose/unchoose entry, which is the
   * keyboard's way to build a choice (the rows themselves are not tab stops).
   */
  declare selectionMode: 'replace' | 'explicit';
  declare refinedStampVisible: boolean;
  declare provenance: SearchProvenance | null;
  declare expanded: boolean;
  declare excerptOpen: boolean;

  /** Shift-range anchor (ports SearchSurface's 508-followup §γ4 model into the one card). */
  private anchorIndex = -1;
  private refinedStampTimer: ReturnType<typeof setTimeout> | null = null;
  private wasSettling = false;
  /**
   * Search Thread Round-2 R2 — `jf-control.onActivate` takes no arguments (it can't forward the
   * originating MouseEvent), so the Ask AI control's modifier detection happens via a CAPTURE-phase
   * click listener on the composed `jf-control` (fires BEFORE its own bubble-phase click → activate()
   * → onActivate, so the flag is set in time) — the same shift-detection idiom `handleRowClick` uses,
   * ported to a control whose activation signature can't carry the event directly.
   */
  private pendingAskShift = false;
  private readonly copyReceipt = new ReceiptController(this);

  constructor() {
    super();
    this.snapshot = null;
    this.facetSelections = {};
    this.selectedIds = new Set<string>();
    this.askAvailability = null;
    this.forgetAvailable = false;
    this.variant = 'live';
    this.selectionMode = 'replace';
    this.refinedStampVisible = false;
    this.provenance = null;
    this.expanded = false;
    this.excerptOpen = false;
    // The one shared clipboard seam by default; plugin hosts may override with
    // their host.ui.copyToClipboard wrapper (same util underneath).
    this.copyText = (text: string) => copyToClipboard(text);
  }

  /** Tempdoc 738 — re-render the disclosure-gated meta line + result locations on Simple/Detailed change. */
  private uiModeUnsubscribe: (() => void) | null = null;

  override connectedCallback(): void {
    super.connectedCallback();
    this.uiModeUnsubscribe = subscribeUiMode(() => this.requestUpdate());
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.uiModeUnsubscribe?.();
    this.uiModeUnsubscribe = null;
    if (this.refinedStampTimer !== null) {
      clearTimeout(this.refinedStampTimer);
      this.refinedStampTimer = null;
    }
  }

  /**
   * The refined ✓ transition: while a search is refining (or showing the quick
   * pass) we arm; when it settles WITH results, the stamp shows for
   * REFINED_STAMP_MS then decays. The stamp is presentation-local state — it
   * never outlives the component and resets on any new in-flight pass.
   */
  override willUpdate(changed: Map<PropertyKey, unknown>): void {
    super.willUpdate?.(changed);
    const s = this.snapshot;
    const settling = !!s && (s.isRefining === true || s.passStage === 'quick' || s.isSearching);
    if (settling) {
      this.wasSettling = true;
      if (this.refinedStampVisible) this.refinedStampVisible = false;
    } else if (this.wasSettling && s && s.results.length > 0 && !s.isSearching) {
      this.wasSettling = false;
      this.refinedStampVisible = true;
      if (this.refinedStampTimer !== null) clearTimeout(this.refinedStampTimer);
      this.refinedStampTimer = setTimeout(() => {
        this.refinedStampVisible = false;
        this.refinedStampTimer = null;
      }, REFINED_STAMP_MS);
    }
  }

  // ---------------------------------------------------------------- events

  private emitCard<T>(name: string, detail: T): void {
    this.dispatchEvent(new CustomEvent(name, { detail, bubbles: true, composed: true }));
  }

  /**
   * Multi-select-aware click (ports SearchSurface.handleClick verbatim minus the
   * host side effects, which the hosts perform on `card-selection`/`card-open`):
   * plain click = replace selection + open; shift = anchor range; ctrl/meta =
   * toggle membership. The open disposition is recorded HERE (the one canonical
   * positive-outcome signal — 580 §17 P3) so neither host can forget it.
   */
  private handleRowClick(hit: CardHit, event: MouseEvent): void {
    // Search Thread S4-final — a frozen card's rows are OPENABLE but not SELECTABLE: no
    // multi-select event, and no `recordOpenDisposition` (that feedback signal is keyed to the
    // CURRENT live search's interactionId in the searchState singleton — attributing it to a
    // historical/frozen search here would misfile the ranking signal under the wrong query).
    if (this.variant !== 'live') {
      this.emitCard('card-open', { id: hit.id });
      return;
    }
    const hits = this.snapshot?.results ?? [];
    const clickedIndex = hits.findIndex((h) => h.id === hit.id);
    const explicit = this.selectionMode === 'explicit';
    const choosing = event.shiftKey || event.ctrlKey || event.metaKey;
    if (explicit && !choosing) {
      // Open only. The anchor still moves, so a following Shift-click ranges from the row read last.
      this.anchorIndex = clickedIndex;
      recordOpenDisposition(hit.id);
      this.emitCard('card-open', { id: hit.id });
      return;
    }
    if (!explicit) recordOpenDisposition(hit.id);
    const rowEl = event.currentTarget as HTMLElement | null;
    if (rowEl) {
      const rect = rowEl.getBoundingClientRect();
      if (rect.width > 0 || rect.height > 0) {
        setMenuAnchor({ top: rect.top, left: rect.left, bottom: rect.bottom, right: rect.right });
      }
    }
    let nextIds: Set<string>;
    if (event.shiftKey && this.anchorIndex >= 0 && this.anchorIndex < hits.length && clickedIndex >= 0) {
      const start = Math.min(this.anchorIndex, clickedIndex);
      const end = Math.max(this.anchorIndex, clickedIndex);
      nextIds = new Set<string>();
      for (let i = start; i <= end; i++) {
        const h = hits[i];
        if (h) nextIds.add(h.id);
      }
    } else if (event.ctrlKey || event.metaKey) {
      nextIds = new Set(this.selectedIds);
      if (nextIds.has(hit.id)) nextIds.delete(hit.id);
      else nextIds.add(hit.id);
      this.anchorIndex = clickedIndex;
    } else {
      nextIds = new Set([hit.id]);
      this.anchorIndex = clickedIndex;
    }
    this.emitCard<CardSelectionDetail>('card-selection', {
      ids: [...nextIds],
      primaryId: hit.id,
      primaryIndex: clickedIndex,
    });
    if (!explicit) this.emitCard('card-open', { id: hit.id });
  }

  private async openResultMenu(hit: CardHit, anchor: { x: number; y: number }, invoker: HTMLElement | null = null): Promise<void> {
    const addressable = { kind: 'search-result' as const, id: hit.id, payload: hit };
    const explicit = this.selectionMode === 'explicit';
    const chosen = this.selectedIds.has(hit.id);
    const result = await openContextMenu({
      actions: [
        { id: 'open-document', label: 'Open document', icon: 'layers', category: 'system', enabled: true },
        // Search Thread S3 (tempdoc D5) — scope the thread to this file: hosts add a
        // scope chip constraining both the instant-search floor and AI retrieval.
        { id: 'ask-about-this', label: 'Ask about this file', icon: 'message-square', category: 'system', enabled: true },
        ...(explicit
          ? [
              {
                id: 'toggle-chosen',
                label: chosen ? 'Remove from chosen files' : 'Add to chosen files',
                icon: 'check-circle-2' as const,
                category: 'system' as const,
                enabled: true,
              },
            ]
          : []),
      ],
      anchor,
      context: 'search-result-row',
      payload: this.forgetAvailable ? { ...hit,
        forget: (scope: 'FILE' | 'FOLDER') => this.emitCard<CardForgetDetail>('card-forget',
          { id: hit.id, path: hit.path, scope, invoker }) } : hit,
      addressable,
    });
    if (result === 'ask-about-this') {
      this.emitCard('card-scope-file', { id: hit.id, path: hit.path, title: hit.title });
      return;
    }
    const hits = this.snapshot?.results ?? [];
    const index = hits.findIndex((h) => h.id === hit.id);
    if (result === 'toggle-chosen') {
      const next = new Set(this.selectedIds);
      if (chosen) next.delete(hit.id);
      else next.add(hit.id);
      this.anchorIndex = index;
      this.emitCard<CardSelectionDetail>('card-selection', {
        ids: [...next],
        primaryId: hit.id,
        primaryIndex: index,
      });
      return;
    }
    if (result === 'open-document' && explicit) {
      this.emitCard('card-open', { id: hit.id });
      return;
    }
    if (result === 'open-document') {
      this.emitCard<CardSelectionDetail>('card-selection', {
        ids: [hit.id],
        primaryId: hit.id,
        primaryIndex: (this.snapshot?.results ?? []).findIndex((h) => h.id === hit.id),
      });
      this.emitCard('card-open', { id: hit.id });
    }
  }

  private async handleCopyClick(format: 'md' | 'json' | 'paths'): Promise<void> {
    const hits = (this.snapshot?.results ?? []) as unknown as import('../../state/searchState.js').SearchHit[];
    const text =
      format === 'md' ? formatAsMarkdown(hits) : format === 'json' ? formatAsJson(hits) : formatAsPaths(hits);
    await this.copyText(text);
    this.copyReceipt.flash('Copied!', { key: format });
  }

  // ---------------------------------------------------------------- render

  /** Tempdoc 738 (C2) — the retrieval-mode label: plain in Simple, technical in Detailed. */
  private modeLabel(mode: string | null | undefined): string | null {
    return isAdvancedMode() ? retrievalModeLabel(mode) : plainRetrievalModeLabel(mode);
  }

  private renderRetrievalMode(): unknown {
    const mode = (this.snapshot?.searchTrace as SearchTrace | null | undefined)?.effectiveMode;
    const label = this.modeLabel(mode);
    if (label == null) return nothing;
    return html` <span class="retrieval-mode" data-testid="retrieval-mode" data-mode=${mode}>· ${label}</span>`;
  }

  /**
   * Register F-052 — a cross-encoder DROP (deadline miss, RPC failure, model absent) changes the
   * order these rows are in, so it belongs on the meta line next to the retrieval mode. The
   * by-design skips have no wording entry and stay silent. Wording comes from the ONE authority
   * (`CROSS_ENCODER_SKIP_WORDING`), never re-authored here.
   */
  private renderRerankDrop(): unknown {
    const trace = this.snapshot?.searchTrace as SearchTrace | null | undefined;
    const stage = (trace?.stages ?? []).find((s) => s.id === 'cross-encoder');
    if (stage?.status !== 'skipped' || !stage.reason) return nothing;
    const worded = CROSS_ENCODER_SKIP_WORDING[stage.reason];
    if (!worded) return nothing;
    return html` <span class="rerank-drop" data-testid="rerank-drop" data-reason=${stage.reason}
      >· ${worded}</span
    >`;
  }

  private static formatLatency(ms: number): string {
    return ms >= 1000 ? `${(ms / 1000).toFixed(1)}s` : `${ms}ms`;
  }

  /** Tempdoc 738 (C2) — plain latency for Simple mode ("found in 0.02s"), hiding the raw ms timing. */
  private static formatLatencyPlain(ms: number): string {
    return `${(ms / 1000).toFixed(2)}s`;
  }

  private renderMeta(): TemplateResult {
    // Defensive projection — hosts can hand a partially-populated snapshot during
    // early boot (or tests can fixture one); every numeric/list read degrades to
    // the empty shape, never throws.
    const raw = this.snapshot;
    const s = {
      query: raw?.query ?? '',
      results: raw?.results ?? [],
      matchCount: raw?.matchCount ?? 0,
      totalHits: raw?.totalHits ?? 0,
      facetsTruncated: raw?.facetsTruncated ?? false,
      isSearching: raw?.isSearching ?? false,
      processingTimeMs: raw?.processingTimeMs ?? null,
      isRefining: raw?.isRefining,
      passStage: raw?.passStage,
      slowSearch: raw?.slowSearch,
      searchTrace: raw?.searchTrace,
    };
    return html`<div class="meta" data-testid="card-meta">
      <div class="meta-info">
        ${s.isSearching
          ? html`${icon({ name: 'loader-2', size: 11, spin: true })} ${s.slowSearch
                ? 'Searching your documents — almost there…'
                : 'Searching…'}`
          : s.results.length > 0
            ? html`${matchCountLabel(
                s.matchCount,
                s.results.length,
                (s.searchTrace as SearchTrace | null | undefined)?.effectiveMode === 'VECTOR',
                s.totalHits,
                s.facetsTruncated,
              )}${s.processingTimeMs != null
                ? isAdvancedMode()
                  ? html` · ${ResultsCard.formatLatency(s.processingTimeMs)}`
                  : html` · found in ${ResultsCard.formatLatencyPlain(s.processingTimeMs)}`
                : nothing}${this.renderRetrievalMode()}${this.renderRerankDrop()}${s.isRefining
                ? html` <span class="meta-refining" data-testid="meta-refining"
                    >${icon({ name: 'loader-2', size: 10, spin: true })} refining…</span
                  >`
                : s.passStage === 'quick'
                  ? html` <span class="meta-refining" data-testid="meta-quick">· quick results</span>`
                  : this.refinedStampVisible
                    ? html` <span class="meta-refined" data-testid="meta-refined">refined ✓</span>`
                    : nothing}`
            : html`<span class="meta-empty">No matches for "${s.query}"</span>`}
      </div>
      ${s.results.length > 0
        ? html`<div class="copy-actions" data-testid="copy-actions">
            ${this.renderCopyBtn('md', 'Copy as Markdown', 'MD')}
            ${this.renderCopyBtn('json', 'Copy as JSON', 'JSON')}
            ${this.renderCopyBtn('paths', 'Copy paths only', 'Paths')}
            ${this.askAvailability !== null
              ? html`<jf-control
                  class="copy-btn ask-ai-btn"
                  .availability=${this.askAvailability}
                  @click=${{
                    handleEvent: (e: MouseEvent) => {
                      this.pendingAskShift = e.shiftKey;
                    },
                    capture: true,
                  }}
                  .onActivate=${() =>
                    this.emitCard('card-ask-ai', { query: s.query, shiftKey: this.pendingAskShift })}
                  >Ask AI</jf-control
                >`
              : nothing}
          </div>`
        : nothing}
    </div>`;
  }

  private renderCopyBtn(format: 'md' | 'json' | 'paths', title: string, label: string): TemplateResult {
    const flashing = this.copyReceipt.isFlashing(format);
    return html`<button
      class=${flashing ? 'copy-btn flashing' : 'copy-btn'}
      title=${title}
      data-testid="copy-btn-${format}"
      @click=${() => void this.handleCopyClick(format)}
    >
      ${icon({ name: 'copy', size: 11 })} ${flashing ? 'Copied!' : label}
    </button>`;
  }

  private renderRow(hit: CardHit): TemplateResult {
    // Search Thread S4-final — a frozen card's rows never carry the live selection/context-menu
    // affordances (no multi-select, no "Ask about this file" scope mutation from a historical
    // search); they stay openable via the plain click branch in handleRowClick.
    const isLive = this.variant === 'live';
    const selected = isLive && this.selectedIds.has(hit.id);
    const view = projectResultView(hit as unknown as ResultViewInput);
    const query = this.snapshot?.query ?? '';
    return html`
      <div
        class=${selected ? 'row selected' : 'row'}
        role="listitem"
        id=${`search-opt-${hit.id}`}
        data-testid="search-result-row"
        aria-current=${selected ? 'true' : 'false'}
        data-selected=${selected ? 'true' : 'false'}
        data-kind=${view.kind}
        data-addressable-kind="search-result"
        data-addressable-id=${hit.id}
        tabindex=${isLive && this.forgetAvailable ? 0 : nothing}
        @click=${(e: MouseEvent) => this.handleRowClick(hit, e)}
        @contextmenu=${(e: MouseEvent) => {
          if (!isLive) return;
          e.preventDefault();
          const row = e.currentTarget as HTMLElement;
          const rect = row.getBoundingClientRect();
          void this.openResultMenu(hit, e.clientX || e.clientY ? { x: e.clientX, y: e.clientY }
            : { x: rect.left, y: rect.bottom }, row);
        }}
        @keydown=${(e: KeyboardEvent) => {
          if (!isLive || !this.forgetAvailable || !(e.key === 'ContextMenu' || e.key === 'F10' && e.shiftKey)) return;
          e.preventDefault(); e.stopPropagation();
          const row = e.currentTarget as HTMLElement;
          const rect = row.getBoundingClientRect();
          void this.openResultMenu(hit, { x: rect.left, y: rect.bottom }, row);
        }}
      >
        <div class="title">
          <span class="kind-icon" aria-hidden="true">${icon({ name: view.icon, size: 13 })}</span>
          <span class="title-text">${view.title}</span>
          ${view.kind === 'code' && view.approxLine != null
            ? html`<span class="line-anchor">:L${view.approxLine}</span>`
            : nothing}
          ${/* Tempdoc 811 (C-1a) — the corpus marker: a hit from a named non-default collection
                says so on the row, so the built-in help docs JustSearch ships (UIX-015) are not
                silently indistinguishable from the user's own documents. */ ''}
          ${view.collection
            ? html`<span
                class="collection-pill"
                data-testid="collection-pill"
                data-collection=${view.collection.collection}
                data-tone=${view.collection.tone}
                title=${view.collection.title}
              >${view.collection.label}</span>`
            : nothing}
          ${isLive
            ? html`<button
                class="row-actions"
                data-testid="row-actions"
                title="Actions"
                aria-label=${`Actions for ${view.title}`}
                @click=${(e: MouseEvent) => {
                  e.stopPropagation();
                  const r = (e.currentTarget as HTMLElement).getBoundingClientRect();
                  void this.openResultMenu(hit, { x: r.left, y: r.bottom }, e.currentTarget as HTMLElement);
                }}
              >⋯</button>`
            : nothing}
        </div>
        <div class="path" title=${hit.path}>
          ${/* Tempdoc 738 (C4) — Simple shows the humanized breadcrumb (empty for a root file, which is
                fine — the filename is already the row title); Detailed shows the full path. No raw-path
                fallback in Simple, so a drive-root file can't re-leak the raw path (728 review §1). */ ''}
          ${isAdvancedMode() ? formatDisplayPath(hit.path) : formatLocationBreadcrumb(hit.path)}
        </div>
        ${view.snippet
          ? html`<div class="snippet" data-snippet-source=${view.snippetSource}>
              ${highlightTerms(view.snippet, query)}
            </div>`
          : nothing}
        ${renderWhyDisclosure(hit as unknown as WhyHit)}
      </div>
    `;
  }

  /**
   * Search Thread S4-final — the frozen-card provenance header: "[actor] searched "<query>" ·
   * <funnel counts> · <mode> · <relative time>" + the "Search again" fork affordance. Shared by
   * `variant='snapshot'` and the expanded form of `variant='excerpt'`.
   */
  private renderProvenanceHeader(): TemplateResult | typeof nothing {
    const p = this.provenance;
    if (!p) return nothing;
    const actorLabel = p.actor === 'agent' ? 'Agent' : 'You';
    const countLabel = matchCountLabel(p.matchCount, p.resultCount, p.mode === 'VECTOR', p.matchCount, false);
    const mLabel = this.modeLabel(p.mode);
    const when = formatRelative(new Date(p.executedAt).getTime());
    return html`<div class="provenance" data-testid="card-provenance">
      <span class="provenance-text">
        <span class="provenance-actor">${actorLabel}</span> searched
        <span class="provenance-query">"${p.query}"</span>
        · ${countLabel}${mLabel ? html` · ${mLabel}` : nothing} · ${when}
      </span>
      <button
        type="button"
        class="provenance-fork"
        data-testid="card-fork-btn"
        @click=${() => this.emitCard('card-fork', { query: p.query })}
      >Search again</button>
    </div>`;
  }

  /**
   * Search Thread S4-final — the row list under the provenance header: collapsed to the top 3
   * rows with a "Show all N" expander, or (a restored thread event carries no persisted hits — the
   * backend keeps only `docIds`) an honest empty note instead of a fabricated row list.
   */
  private renderSnapshotRows(): TemplateResult {
    const rows = this.snapshot?.results ?? [];
    if (rows.length === 0) {
      return html`<div class="snapshot-empty-note" data-testid="snapshot-empty-note">
        results not stored — run again to see them
      </div>`;
    }
    const visible = this.expanded ? rows : rows.slice(0, 3);
    return html`
      <div class="results-list snapshot-rows" role="list" id="search-results-list" aria-label="Search results">
        ${repeat(
          visible,
          (h) => h.id,
          (h) => this.renderRow(h),
        )}
      </div>
      ${rows.length > 3
        ? html`<button
            type="button"
            class="snapshot-expander"
            data-testid="snapshot-expander"
            @click=${() => {
              this.expanded = !this.expanded;
            }}
          >${this.expanded ? 'Show less' : `Show all ${rows.length}`}</button>`
        : nothing}
    `;
  }

  /** Search Thread S4-final — the full frozen-card render: header + collapsible rows. */
  private renderSnapshotVariant(): TemplateResult {
    return html`<div class="results-card-snapshot" data-testid="card-snapshot">
      ${this.renderProvenanceHeader()} ${this.renderSnapshotRows()}
    </div>`;
  }

  /**
   * Search Thread S4-final — the collapsed one-line form: a compact chip-style summary that
   * expands IN PLACE to the full snapshot rendering (never navigates away / never mutates the
   * underlying commit — append-only).
   */
  private renderExcerpt(): TemplateResult {
    const p = this.provenance;
    if (!p) return html``;
    if (this.excerptOpen) {
      return html`<div class="results-card-excerpt-expanded" data-testid="card-excerpt-expanded">
        ${this.renderSnapshotVariant()}
        <button
          type="button"
          class="excerpt-collapse"
          data-testid="excerpt-collapse"
          @click=${() => {
            this.excerptOpen = false;
          }}
        >Collapse</button>
      </div>`;
    }
    const n = this.snapshot?.results?.length ? this.snapshot.results.length : p.resultCount;
    return html`<button
      type="button"
      class="results-card-excerpt"
      data-testid="card-excerpt"
      aria-expanded="false"
      @click=${() => {
        this.excerptOpen = true;
      }}
    >
      <span aria-hidden="true">🔍</span> "${p.query}" · ${n} result${n === 1 ? '' : 's'} ▸
    </button>`;
  }

  override render(): TemplateResult | typeof nothing {
    // Search Thread S4-final — a frozen card renders entirely through its own template (the
    // provenance header replaces the live meta line; no facets/copy/Ask AI on a frozen record).
    if (this.variant === 'excerpt') return this.renderExcerpt();
    if (this.variant === 'snapshot') return this.renderSnapshotVariant();
    const s = this.snapshot;
    const results = s?.results ?? [];
    if (!s || (!s.isSearching && results.length === 0 && !(s.query ?? '').trim())) return nothing;
    return html`
      ${this.renderMeta()}
      ${renderFacetChips(s.facets as Parameters<typeof renderFacetChips>[0], this.facetSelections, {
        onToggle: (field, value) => this.emitCard('card-facet-toggle', { field, value }),
      })}
      ${results.length > 0
        ? html`<div class="results-list" role="list" id="search-results-list" aria-label="Search results">
            ${repeat(
              results,
              (h) => h.id,
              (h) => this.renderRow(h),
            )}
          </div>`
        : nothing}
    `;
  }

  static styles = [
    whyThisResultStyles,
    facetChipStyles,
    highlightStyles,
    css`
      :host {
        display: block;
      }
      .meta {
        margin-top: 0.4rem;
        padding: 0 1.25rem;
        font-size: var(--font-size-xs);
        color: var(--text-secondary);
        display: flex;
        align-items: center;
        gap: 0.4rem;
      }
      .meta-info {
        display: flex;
        align-items: center;
        gap: 0.4rem;
        flex: 1;
        min-width: 0;
      }
      .meta-refining {
        display: inline-flex;
        align-items: center;
        gap: 0.25rem;
        color: var(--text-tertiary);
      }
      /* Search Thread S1 — the two-stage search's terminal stamp: a brief, calm
         confirmation that the refined pass settled, decaying to the mode text. */
      .meta-refined {
        color: var(--text-success);
        font-weight: 600;
      }
      .meta-empty {
        opacity: 0.6;
      }
      .retrieval-mode {
        color: var(--text-tertiary);
      }
      .retrieval-mode[data-mode='HYBRID'],
      .retrieval-mode[data-mode='VECTOR'] {
        color: var(--text-secondary);
      }
      /* Register F-052 — the cross-encoder drop notice. Same tier as the retrieval mode: it
         states what the ranking is, it is not a warning. */
      .rerank-drop {
        color: var(--text-secondary);
      }
      .copy-actions {
        display: flex;
        align-items: center;
        gap: 0.25rem;
        flex-shrink: 0;
      }
      .copy-actions .copy-btn {
        display: inline-flex;
        align-items: center;
        gap: 0.25rem;
        padding: 0.125rem 0.5rem;
        background: transparent;
        border: 1px solid var(--border-subtle);
        border-radius: 0.25rem;
        color: var(--text-secondary);
        font-size: var(--font-size-xs);
        cursor: pointer;
        font-family: inherit;
      }
      .copy-actions .copy-btn:hover {
        color: var(--text-primary);
        border-color: var(--accent-tint);
      }
      .copy-actions .copy-btn.flashing {
        color: var(--text-success);
        border-color: var(--accent-success);
        background: var(--accent-success-16);
        font-weight: 600;
      }
      .copy-actions jf-control.copy-btn {
        border: none;
        background: transparent;
        padding: 0;
      }
      .copy-actions jf-control.copy-btn::part(control) {
        display: inline-flex;
        align-items: center;
        gap: 0.25rem;
        padding: 0.125rem 0.5rem;
        background: transparent;
        border: 1px solid var(--border-subtle);
        border-radius: 0.25rem;
        color: var(--text-secondary);
        font-size: var(--font-size-xs);
        cursor: pointer;
      }
      .copy-actions jf-control.copy-btn:hover::part(control) {
        color: var(--text-primary);
        border-color: var(--accent-tint);
      }
      .results-list {
        padding: 0.5rem 0;
      }
      .row {
        padding: 0.625rem 1.25rem;
        cursor: pointer;
        border-left: 2px solid transparent;
      }
      .row:hover {
        background: var(--surface-secondary);
      }
      .row.selected {
        border-left-color: var(--accent-tint);
        background: var(--accent-tint-08);
      }
      .row .title {
        display: flex;
        align-items: center;
        gap: 0.4rem;
        color: var(--text-link);
        font-size: var(--font-size-sm);
        font-weight: 500;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .row .kind-icon {
        display: inline-flex;
        color: var(--text-tertiary);
        flex-shrink: 0;
      }
      .row .title-text {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .row .line-anchor {
        color: var(--text-tertiary);
        font-size: var(--font-size-xs);
        flex-shrink: 0;
      }
      /* Tempdoc 811 (C-1a) — the per-collection row marker. Same pill geometry as the shared
         .hit-why-signal / .facet-chip chips; the help tone reuses the gate-verified teal fill
         pair (--accent-tint / --accent-on-tint) the selected facet chip already uses, which is
         the teal "Help" pill docs/reference/search-ui-behavior.md documents. */
      .row .collection-pill {
        flex-shrink: 0;
        padding: 0.05rem 0.45rem;
        border: 1px solid var(--border-subtle);
        border-radius: 1rem;
        background: var(--surface-2);
        color: var(--text-tertiary);
        font-size: var(--font-size-xs);
        font-weight: 500;
        white-space: nowrap;
      }
      .row .collection-pill[data-tone='help'] {
        background: var(--accent-tint);
        border-color: var(--accent-command);
        color: var(--accent-on-tint);
      }
      .row .path {
        font-size: var(--font-size-xs);
        color: var(--text-secondary);
        font-family: monospace;
        margin-top: 0.125rem;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .row .snippet {
        font-size: var(--font-size-xs);
        color: var(--text-secondary);
        margin-top: 0.25rem;
        line-height: 1.4;
        display: -webkit-box;
        -webkit-line-clamp: 2;
        -webkit-box-orient: vertical;
        overflow: hidden;
      }
      .row .row-actions {
        margin-left: auto;
        padding: 0 0.4rem;
        font-size: var(--font-size-sm);
        font-family: inherit;
        line-height: 1.2;
        border: 1px solid transparent;
        border-radius: 4px;
        background: none;
        color: var(--text-tertiary);
        cursor: pointer;
        opacity: 0;
        flex-shrink: 0;
      }
      .row:hover .row-actions,
      .row .row-actions:focus-visible {
        opacity: 1;
      }
      .row .row-actions:hover,
      .row .row-actions:focus-visible {
        color: var(--text-primary);
        border-color: var(--border-subtle);
        background: var(--surface-hover);
        outline: 2px solid transparent;
      }
      /* Search Thread S4-final — the frozen-card frame: a bordered card distinguishes a committed
         snapshot from the live card it sits above in the retrieve tier's flow. */
      .results-card-snapshot {
        margin: 0.35rem 0;
        border: 1px solid var(--border-subtle);
        border-radius: 0.375rem;
        overflow: hidden;
      }
      .results-card-excerpt-expanded .results-card-snapshot {
        margin: 0;
        border: none;
        border-radius: 0;
      }
      .provenance {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 0.5rem;
        padding: 0.5rem 1.25rem;
        font-size: var(--font-size-xs);
        color: var(--text-secondary);
        border-bottom: 1px solid var(--border-subtle);
      }
      .provenance-text {
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .provenance-actor {
        font-weight: 600;
        color: var(--text-primary);
      }
      .provenance-query {
        color: var(--text-primary);
      }
      .provenance-fork {
        flex-shrink: 0;
        padding: 0.125rem 0.5rem;
        background: transparent;
        border: 1px solid var(--border-subtle);
        border-radius: 0.25rem;
        color: var(--text-link);
        font-size: var(--font-size-xs);
        font-family: inherit;
        cursor: pointer;
      }
      .provenance-fork:hover,
      .provenance-fork:focus-visible {
        color: var(--text-primary);
        border-color: var(--accent-tint);
        outline: 2px solid transparent;
      }
      .snapshot-rows .row {
        cursor: pointer;
      }
      .snapshot-expander,
      .excerpt-collapse {
        display: block;
        margin: 0.25rem 1.25rem 0.6rem;
        padding: 0.125rem 0.5rem;
        background: transparent;
        border: 1px solid var(--border-subtle);
        border-radius: 0.25rem;
        color: var(--text-secondary);
        font-size: var(--font-size-xs);
        font-family: inherit;
        cursor: pointer;
      }
      .snapshot-expander:hover,
      .snapshot-expander:focus-visible,
      .excerpt-collapse:hover,
      .excerpt-collapse:focus-visible {
        color: var(--text-primary);
        border-color: var(--accent-tint);
        outline: 2px solid transparent;
      }
      .snapshot-empty-note {
        padding: 0.5rem 1.25rem 0.75rem;
        font-size: var(--font-size-xs);
        color: var(--text-tertiary);
        font-style: italic;
      }
      .results-card-excerpt {
        display: flex;
        align-items: center;
        gap: 0.4rem;
        width: 100%;
        padding: 0.4rem 1.25rem;
        background: transparent;
        border: none;
        color: var(--text-secondary);
        font-size: var(--font-size-sm);
        font-family: inherit;
        text-align: left;
        cursor: pointer;
      }
      .results-card-excerpt:hover,
      .results-card-excerpt:focus-visible {
        background: var(--surface-secondary);
        color: var(--text-primary);
        outline: 2px solid transparent;
      }
    `,
  ];
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-results-card')) {
  customElements.define('jf-results-card', ResultsCard);
}
