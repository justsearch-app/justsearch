// SPDX-License-Identifier: Apache-2.0
/**
 * LibrarySurface — slice 449 phase 7b calibration target, slice 450 polish.
 *
 * Bespoke specialty Lit element (Option-Y per slice 449 §7 D3): consumes
 * the {@code core.indexed-roots} TABULAR × ONE_SHOT Resource directly via
 * REST + the OperationClient for the 5 wired Operations.
 *
 * <p>Slice 450 §1 polish: Tauri runtime detection + browser-mode warning,
 * native folder picker (Tauri only), file-count fetch, relative-time
 * formatting, icon primitives, CTA color tokens, persisted-excludes
 * hydration via /api/settings/v2.
 *
 * <p>Side-effect registers {@code <jf-library-surface>} for the chrome
 * dispatcher.
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import { createSettingsAttempt, executeSettingsAttempt, readSettingsObservation, SettingsAttemptError, type SettingsAttempt, type SettingsWitness } from '../../api/settingsAttempt.js';
import { JfElement } from '../primitives/JfElement.js';
import '../components/OpButton.js';
import '../components/Button.js';
import '../components/IngestionSummary.js';
// Tempdoc 571 §11 / 578 — Library ⊇ Browse: Library hosts the Browse file-tree as a tab.
import '../components/SurfaceTabs.js';
import type { SurfaceTabItem } from '../components/SurfaceTabs.js';
import { getSurface } from '../../api/registry/SurfaceCatalogClient.js';
import { present } from '../display/present.js';
import { takeMemberTabIntent, subscribeMemberTab } from '../router/memberTabIntent.js';
import { resolvePathLazy } from '../hooks/resolvePathLazy.js';
import { icon } from '../components/Icon.js';
// 569 §14 — render the indexed-folder cards through the projection engine (the 2nd real surface).
import { activeBodyFor, subscribePresentation } from '../state/presentationRuntime.js';
import { subscribeAiState } from '../state/aiStateStore.js';
import { LIBRARY_CARDS_REGION } from '../themes/builtinPresentations.js';
import '../components/DeclaredSurface.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
// Tempdoc 564 Phase 2: the indexed-roots wire shape is the generated IndexedRootView projection
// (record → JSON Schema → {TS, Zod}); validated at the fetch boundary, no hand interface.
import { z } from 'zod';
import { parseWireContract } from '../../api/schemas.js';
import {
  indexedRootViewSchema,
  type IndexedRootView,
} from '../../api/generated/schema-types/indexed-root-view.js';
import { operationOutcomeViewSchema } from '../../api/generated/schema-types/operation-outcome-view.js';
// Tempdoc 599 §9.1 — the ONE per-folder status derivation; the row glyph + meta line project from it.
import {
  folderStatus,
  rememberFailedCounts,
  failedChipCopy,
  type FolderStatus,
} from '../state/folderStatus.js';
// Tempdoc 813 §4 — the index-wide progress authority supplies enrichment-stage applicability, which
// the per-root wire row cannot carry (counts only, no enabled flags).
import {
  selectIndexingProgress,
  type EnrichmentApplicability,
} from '../state/indexingProgress.js';
import { enrichmentProgress } from '../state/enrichmentCoverage.js';
// Tempdoc 811 C-2a — the non-root (ad-hoc / MCP) sources the folder rows cannot describe.
import {
  deriveOtherSources,
  facetScanCapFor,
  EMPTY_OTHER_SOURCES,
  // Tempdoc 914 D4 — the FE mirror of IngestCollectionPolicy's reserved names, so the Add Folder
  // collection field refuses one with a reachable reason instead of creating the root.
  isReservedCollection,
  type OtherSource,
  type OtherSourcesSnapshot,
} from '../state/otherSources.js';
// The ONE `/api/knowledge/search` issuance site (577 Ext II / the `search-issuance` gate) — the
// enumeration probe is issued there, not re-shaped here.
import { fetchCollectionFacet } from '../state/searchState.js';
import { UNKNOWN, type Maybe } from '../state/known.js';
// Tempdoc 599 §16/B1 — the clickable "N failed" chip opens the per-folder failed-files drawer.
import { openFailedJobs } from '../state/failedJobsDrawer.js';
// Tempdoc 599 §9.4 — gate the Add button with a reachable reason (596 operability authority).
import { unavailableBecause, AVAILABLE } from '../state/availability.js';
// Tempdoc 914 D3 — the failed chip's shared look (one authority for both render sites).
import { failedChipStyles } from '../components/failedChipPresentation.js';

/** Tempdoc 599 §9.4 — add-time path validation result from /api/indexing-roots/preview. */
interface FolderPreview {
  readonly path: string;
  readonly exists: boolean;
  readonly isDir: boolean;
  readonly fileCount: number;
  /** Tempdoc 599 Fix 3 — the count walk hit its cap; fileCount is a lower bound ("N+"). */
  readonly capped: boolean;
  readonly alreadyWatched: boolean;
}

// The substrate list response: a thin {items, count} envelope around the generated IndexedRootView.
const listResponseSchema = z
  .object({
    items: z.array(indexedRootViewSchema),
    count: z.number().optional(),
  })
  .loose();

type GapDecisionView = { reindexKey: string; gapListHash: string;
  gaps: readonly { unitId: string; reason: string }[] };

/**
 * Tempdoc 804 §B9 (round-10 F8) — how a watched-folder row NAMES itself.
 *
 * The row used to render `[b5ec60937d1a…]` — the raw path hash — whenever the lazy resolver had
 * not answered, next to a Remove button: the only offered action on an unidentifiable row was the
 * destructive one. The wire is hash-only by construction (ADR-0028 + `LibraryResolveHashOnlyCallerPin`
 * keep raw paths off it), so the fix is not "put the path on the wire": it is that an UNRESOLVED row
 * says so in words, and a resolved one shows its folder name with the full path on hover.
 *
 * Returns the visible `label` and the `title` (hover/assistive text). The hash stays the row's
 * identity for Remove/failed-jobs intents — it is just never the user-visible name.
 */
export function folderRowLabel(
  pathHash: string,
  resolvedPath: string | undefined,
): { label: string; title: string } {
  if (resolvedPath && resolvedPath.length > 0) {
    const name = resolvedPath.split(/[\\/]/).filter((s) => s.length > 0).pop() ?? resolvedPath;
    return { label: name, title: resolvedPath };
  }
  return {
    label: 'Folder (path unavailable)',
    // The short hash is diagnostic detail, not the name — reachable on hover, never the label.
    title: pathHash ? `Path could not be resolved (id ${pathHash.slice(0, 12)}…)` : 'Path could not be resolved',
  };
}

const RESOURCE_ID = 'core.indexed-roots';
const ENDPOINT = '/api/indexing-roots/substrate';
const OP_ADD = 'core.add-watched-root';
const OP_REMOVE = 'core.remove-watched-root';
const OP_PREVIEW = 'core.preview-excludes';
const OP_APPLY = 'core.apply-excludes';

// Tempdoc 599 §9.3 — minimum spacing between aiState-tick-driven (counts-free) live refreshes, so
// the row updates while indexing without re-fetching faster than the underlying status cadence.
const LIVE_REFRESH_MIN_MS = 4000;

export class LibrarySurface extends JfElement {
  static properties = {
    apiBase: { attribute: 'api-base', type: String },
    host_: { attribute: false },
    roots: { state: true },
    resolvedPaths: { state: true },
    loading: { state: true },
    error: { state: true },
    excludesText: { state: true },
    excludesLoaded: { state: true },
    excludesBusy: { state: true },
    pendingPath: { state: true },
    pendingCollection: { state: true },
    pendingPreview: { state: true },
    showManualInput: { state: true },
    isTauri: { state: true },
    activeTab: { state: true },
    provisional: { state: true },
    enrichmentStages: { state: true },
    enrichmentPending: { state: true },
    enrichmentBlocked: { state: true },
    otherSources: { state: true },
    gapDecision: { state: true },
    gapDecisionBusy: { state: true },
    gapDecisionError: { state: true },
  };

  declare apiBase: string;
  declare host_: PluginHostApi;
  declare roots: IndexedRootView[];
  declare resolvedPaths: Record<string, string>;
  declare loading: boolean;
  declare error: string | null;
  declare excludesText: string;
  declare excludesLoaded: boolean;
  declare excludesBusy: boolean;
  private excludesWitness: SettingsWitness | null = null;
  private excludesAttempt: SettingsAttempt | null = null;
  private excludesLoadStarted = false;
  declare pendingPath: string;
  /**
   * Tempdoc 914 D4 — the optional collection the new watched root's documents are tagged with.
   * Blank ⟹ the argument is omitted and the backend's own default ("default", the untagged bucket)
   * applies; the field exists because until now the Add Folder flow sent `{path}` only, so the label
   * that `Library > Folders` shows on every row and that search results filter on was unreachable
   * from the one surface that creates roots.
   */
  declare pendingCollection: string;
  /** Tempdoc 599 §9.4 — add-time validation result for the typed path (null = not yet checked). */
  declare pendingPreview: FolderPreview | null;
  declare showManualInput: boolean;
  declare isTauri: boolean;
  /** Active composition tab id: 'folders' (own body) or a member surface id. */
  declare activeTab: string;
  /**
   * 595 §4.3 — the backend is mid-transition (rebuild / worker restart / initial
   * load). While provisional, an empty `roots` is "unknown right now", NOT a
   * settled "no folders configured" — so we render the transition, not the
   * catastrophe-reading empty state. Projected from the one `Stability` axis.
   */
  declare provisional: boolean;
  /**
   * Tempdoc 813 §4 — which enrichment stages apply, from the ONE index-wide progress derivation.
   * Passed into `folderStatus` so a row's second tier (the catching-up caveat + this root's percent
   * → "fully searchable") is computed from applicable stages only. `null` until the first poll
   * snapshot arrives, which the seam reads as "coverage unknown" (no percent).
   */
  declare enrichmentStages: EnrichmentApplicability | null;
  /**
   * 809 finding 1 — the enrichment backfill still owes work, so a drained folder is keyword-searchable
   * but not yet semantically searchable. Projected from the one `enrichmentProgress` derivation and
   * handed to `folderStatus`, which decides whether the row may make the terminal "✓ indexed" claim.
   */
  declare enrichmentPending: boolean;
  /**
   * Round-15 F1b — the semantic enrichment stage cannot run at all (no embedding service), so a
   * drained folder is keyword-searchable and will stay that way until AI is installed. Same
   * derivation, same tick as {@link enrichmentStages}; `folderStatus` turns it into the row's caveat.
   */
  declare enrichmentBlocked: boolean;
  /**
   * Tempdoc 811 C-2a — the collections that documents ingested OUTSIDE every watched folder carry
   * (the MCP/API ingest path). The folder rows describe watched roots only, so without this the
   * user could not see, let alone remove, what an agent had ingested. Empty ⇒ the section is absent
   * entirely, not an empty shell.
   */
  declare otherSources: OtherSourcesSnapshot;
  declare gapDecision: GapDecisionView | null;
  declare gapDecisionBusy: boolean;
  declare gapDecisionError: string | null;
  private observedGapKey: string | null = null;
  private gapRequestSerial = 0;
  private gapDecisionAttemptSerial = 0;
  private gapLoading = false;
  private lastGapReadAtMs = 0;

  private aiUnsub: (() => void) | null = null;
  /**
   * Tempdoc 811 C-2a — the default-scope document count, used to size the collection facet scan so
   * it cannot truncate (truncation OMITS collections; see `otherSources.ts`). Not reactive state:
   * it only feeds the next probe's request, never the render.
   */
  private defaultScopeDocuments: Maybe<number> = UNKNOWN;
  /**
   * Tempdoc 914 D3 — pathHash → the failed count last observed while that root's queue was settled.
   * Not reactive state: it is folded from each poll's rows in `refresh()` (which already re-renders)
   * and only ever feeds `folderStatus`. Bounded by `rememberFailedCounts`, which drops a root the
   * moment a SETTLED poll reports it has no failures — see its javadoc for the retention rule.
   */
  private lastKnownFailed: Record<string, number> = {};
  // Tempdoc 599 §9.4 — debounce the add-time preview while typing.
  private previewTimer: number | null = null;
  private previewSeq = 0;

  constructor() {
    super();
    this.apiBase = '';
    this.host_ = undefined as unknown as PluginHostApi;
    this.roots = [];
    this.resolvedPaths = {};
    this.loading = false;
    this.error = null;
    this.excludesText = '';
    this.excludesLoaded = false;
    this.excludesBusy = false;
    this.pendingPath = '';
    this.pendingCollection = '';
    this.pendingPreview = null;
    this.showManualInput = false;
    this.isTauri = false;
    this.activeTab = 'folders';
    this.provisional = false;
    this.enrichmentStages = null;
    this.enrichmentPending = false;
    this.enrichmentBlocked = false;
    this.otherSources = EMPTY_OTHER_SOURCES;
    this.gapDecision = null;
    this.gapDecisionBusy = false;
    this.gapDecisionError = null;
  }

  // Tempdoc 571 §11 / 578: Library is a host surface — it delegates layout to <jf-surface-tabs>
  // (a display:contents pass-through, the layout-purity-approved host pattern, cf. AgentSurface→
  // AgentView). The Folders tab's own body scrolls inside `.folders-scroll`; the Browse member
  // surface carries its own SurfaceLayout.
  static styles = [
    css`
    :host {
      display: contents;
    }
    .folders-scroll {
      height: 100%;
      overflow-y: auto;
      padding: 1rem 1.5rem;
      box-sizing: border-box;
    }
    .header {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 1rem;
      margin-bottom: 1rem;
    }
    .header-left h2 {
      margin: 0;
      font-size: var(--font-size-lg);
      font-weight: 600;
    }
    .header-left .subtitle {
      margin: 0.125rem 0 0 0;
      font-size: var(--font-size-sm);
      color: var(--text-secondary);
    }
    .actions {
      display: flex;
      gap: 0.5rem;
      flex-shrink: 0;
    }
    /* 574 B1 — the button look is the jf-button atom's single authority; the per-surface
       base + primary/danger variant fork (§14 V2) is deleted. */
    .browser-banner {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      padding: 0.625rem 0.75rem;
      margin-bottom: 1rem;
      background: var(--accent-warning-08);
      border: 1px solid var(--accent-warning-30);
      border-radius: 0.375rem;
      color: var(--text-warning);
      font-size: var(--font-size-sm);
    }
    .gap-decision {
      margin-bottom: 1rem;
      padding: 1rem;
      border: 1px solid var(--accent-warning-30);
      border-radius: 0.5rem;
      background: var(--accent-warning-08);
    }
    .gap-decision h3 { margin: 0 0 0.5rem; font-size: var(--font-size-md); }
    .gap-decision p { margin: 0 0 0.75rem; font-size: var(--font-size-sm); }
    .gap-decision ul { max-height: 12rem; overflow-y: auto; padding-left: 1.25rem; }
    .gap-decision li { font-size: var(--font-size-sm); overflow-wrap: anywhere; }
    .cards {
      display: grid;
      gap: 0.5rem;
    }
    .card {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
      padding: 0.75rem 1rem;
      background: var(--surface-secondary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.5rem;
    }
    .card-icon {
      flex-shrink: 0;
      color: var(--text-tint);
    }
    .card-info {
      flex: 1;
      min-width: 0;
    }
    .card-path {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      font-family: monospace;
      font-size: var(--font-size-sm);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .card-meta {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      margin-top: 0.25rem;
    }
    .status-icon {
      flex-shrink: 0;
    }
    .status-icon.indexed {
      color: var(--text-success);
    }
    .status-icon.error {
      color: var(--text-danger);
    }
    .status-icon.pending {
      color: var(--text-warning);
    }
    .status-icon.unavailable {
      color: var(--text-secondary);
    }
    .status-icon.unverified {
      color: var(--text-warning);
    }
    .reindex-chip {
      margin-left: 0.4rem;
      --jf-button-color: var(--text-secondary);
      color: var(--text-secondary);
    }
    .empty {
      padding: 2rem;
      text-align: center;
      color: var(--text-secondary);
    }
    .error-banner {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      padding: 0.75rem;
      margin-bottom: 1rem;
      background: var(--accent-danger-08);
      border: 1px solid var(--accent-danger);
      border-radius: 0.375rem;
      color: var(--text-danger);
    }
    .error-banner button {
      margin-left: auto;
      background: transparent;
      border: none;
      color: inherit;
      padding: 0.25rem;
    }
    .add-row {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
      margin-bottom: 0.5rem;
    }
    .add-preview {
      display: flex;
      align-items: center;
      gap: 0.35rem;
      margin-bottom: 1rem;
      font-size: var(--font-size-sm);
    }
    .add-preview.ok {
      color: var(--text-success);
    }
    .add-preview.err {
      color: var(--text-danger);
    }
    .add-preview.warn {
      color: var(--text-warning);
    }
    .add-row input {
      flex: 1;
      padding: 0.4rem 0.5rem;
      background: var(--surface-secondary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      color: var(--text-primary);
      font-family: monospace;
      font-size: var(--font-size-sm);
      min-inline-size: 12rem;
    }
    /* Tempdoc 914 D4 — the path is the long field; the optional collection takes a fixed smaller
       share (and the row wraps) so adding it cannot squeeze the path — the D2 failure mode. */
    .add-row input.add-collection {
      flex: 0 1 11rem;
      min-inline-size: 8rem;
      font-family: inherit;
    }
    /* Tempdoc 811 C-2a — "Other sources" reuses the excludes panel's chrome and the folder-row card
       verbatim; no new idiom, only a second section in the same visual language. */
    .other-sources-note {
      display: flex;
      align-items: center;
      gap: 0.35rem;
      margin-top: 0.5rem;
      font-size: var(--font-size-xs);
      color: var(--text-warning);
    }
    .excludes-section {
      margin-top: 1.5rem;
      padding: 1rem;
      background: var(--surface-secondary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.5rem;
    }
    .excludes-header {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 1rem;
      margin-bottom: 0.5rem;
    }
    .excludes-header h3 {
      margin: 0;
      font-size: var(--font-size-md);
      font-weight: 600;
    }
    .excludes-header p {
      margin: 0.125rem 0 0 0;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    textarea {
      width: 100%;
      min-height: 6rem;
      padding: 0.5rem;
      background: var(--surface-tertiary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      color: var(--text-primary);
      font-family: monospace;
      font-size: var(--font-size-sm);
      box-sizing: border-box;
      resize: vertical;
    }
    .jf-icon-spin {
      animation: jf-spin 1s linear infinite;
    }
  `,
    // Tempdoc 914 D3 — the failed chip's look is shared with the declared FolderCardRenderer so the
    // two render sites cannot drift; see failedChipPresentation.ts.
    failedChipStyles,
  ];

  // 569 §14 — re-render when the active presentation changes (the declared cards region appears
  // when CORE_DECLARED is applied, reverts when cleared/quarantined — degrade-never-fail).
  private presentationUnsub: (() => void) | null = null;
  private memberTabUnsub: (() => void) | null = null;
  // Tempdoc 599 §9.3 — live-refresh guards (overlap + throttle); not reactive state.
  private refreshing = false;
  private lastLiveRefreshAtMs = 0;
  /**
   * Tempdoc 811 C-2a — the `maxDocsScanned` the last enumeration probe used, and an overlap guard.
   *
   * The first probe necessarily runs BEFORE the first status poll (the surface fetches roots on
   * connect), so it is sized at the engine's floor. On a corpus larger than that floor a capped scan
   * OMITS collections outright — so when a poll later reveals a bigger index, the probe must run
   * again rather than leaving a silently short list on screen. `0` means "never probed".
   */
  private lastProbeCap = 0;
  private probing = false;

  override connectedCallback(): void {
    super.connectedCallback();
    // Tempdoc 571 §11 / 578 — if we were reached via a member deep-link (core.browse-surface →
    // redirected here), open that member's tab. Drain a pending intent (host mounting now) AND
    // subscribe (so a member deep-link while THIS host is already active still switches the tab).
    const requested = takeMemberTabIntent('core.library-surface');
    if (requested) this.activeTab = requested;
    this.memberTabUnsub = subscribeMemberTab((hostId, memberId) => {
      if (hostId !== 'core.library-surface') return false;
      this.activeTab = memberId;
      return true;
    });
    if (this.host_) {
      this.isTauri = this.host_.platform.capabilities.has('folder-picker');
    }
    void this.refresh();
    if (!this.excludesLoadStarted) void this.loadExcludes();
    this.presentationUnsub = subscribePresentation(() => this.requestUpdate());
    // 595 §4.3 — observe the one Stability axis so an empty roots list during a
    // transition renders as "Rebuilding…", not "No watched folders".
    this.aiUnsub = subscribeAiState((s) => {
      this.provisional = s.stability.kind === 'provisional';
      this.observeGapDecision(s.status?.worker?.migration);
      // Tempdoc 813 §4 — the per-root second tier needs to know which stages apply; take it from the
      // ONE index-wide progress derivation rather than re-reading the enrichment wire flags here.
      const progress = selectIndexingProgress(
        s.status,
        s.snapshotLive,
        s.episodeMaxPendingJobs,
        s.enrichSettleSamples,
      );
      this.enrichmentStages = progress.stages;
      // Round-15 F1b — same projection, same tick: whether the semantic stage can run at all, so a
      // row cannot render an unqualified "✓ … Verified just now" over an index with no vectors.
      this.enrichmentBlocked = progress.phase === 'blocked';
      // 809 finding 1 — same tick, same store: whether the enrichment backfill is still running, so a
      // row cannot claim the terminal "✓ indexed" while semantic search is still being built.
      this.enrichmentPending = enrichmentProgress(s.status).pending;
      // Tempdoc 811 C-2a — the facet probe runs under the DEFAULT search scope, so the
      // default-scope population is its exact bound; fall back to the whole-index count on a
      // backend that predates `searchableDocuments` (C-4).
      this.defaultScopeDocuments = s.index.searchableDocumentCount.known
        ? s.index.searchableDocumentCount
        : s.index.documentCount;
      // Only once a first probe has run (i.e. after the roots are loaded, so root-owned collections
      // can be subtracted): a poll revealing a LARGER corpus than the last scan covered must
      // re-probe — a capped scan omits collections rather than undercounting them.
      if (this.lastProbeCap > 0 && facetScanCapFor(this.defaultScopeDocuments) > this.lastProbeCap) {
        void this.loadOtherSources();
      }
      // Tempdoc 599 §9.3 — ride the existing status tick to live-refresh the rows (counts-free, no
      // new poller), so a folder's "Indexing · N remaining → ✓ indexed" updates without re-nav.
      void this.refresh({ live: true });
    });
  }

  /** Tempdoc 609 — settle transient state on hide: the in-flight `loading` flag + last `error` (a stale
   *  spinner / error would otherwise survive navigation). Folder/excludes drafts, the active tab, and the
   *  fetched roots are recoverable and KEPT. */
  protected override settleTransients(): void {
    this.loading = false;
    this.error = null;
    this.gapDecisionError = null;
    this.gapRequestSerial++;
    this.gapDecisionAttemptSerial++;
    this.gapLoading = false;
    this.gapDecisionBusy = false;
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.presentationUnsub?.();
    this.presentationUnsub = null;
    this.memberTabUnsub?.();
    this.memberTabUnsub = null;
    this.aiUnsub?.();
    this.aiUnsub = null;
    if (this.previewTimer !== null) {
      window.clearTimeout(this.previewTimer);
      this.previewTimer = null;
    }
  }

  /**
   * 569 §14 — the indexed-folder cards. When the active presentation declares the cards region,
   * project them through the engine (<jf-declared-surface> + the folder-card renderer) at bespoke
   * parity, pre-resolving each card's display fields; otherwise the built-in Lit render (the
   * quarantine fallback, degrade-never-fail). The Remove INTENT the renderer emits is handled here
   * (the gated operation + confirm stay surface-owned).
   */
  private renderCardsRegion(): TemplateResult {
    const body = activeBodyFor(LIBRARY_CARDS_REGION);
    if (!body) {
      return html`<div class="cards">${this.roots.map((r) => this.renderCard(r))}</div>`;
    }
    const folders = this.roots.map((r) => {
      const pathHash = r.pathHash ?? '';
      // Tempdoc 599 §9.1 — project the card's glyph (`status`) + `metaText` from the one seam, so the
      // declared FolderCardRenderer and the hand renderCard cannot diverge. `status` carries the
      // glyph token (indexed/error/pending); `metaText` carries the truthful state line. walkError is
      // already folded into metaText by the seam, so it is not passed again (no duplicate display).
      const fs = folderStatus(r, {
        relativeTime: this.host_.utilities.formatRelativeTime(r.lastIndexedIsoTime ?? ''),
        // Tempdoc 626 §Recency — the freshness heartbeat: when the index↔disk correspondence was last
        // confirmed (distinct from lastIndexed, the last write). Same host time-ago util, no new formatter.
        verifiedRelativeTime: this.host_.utilities.formatRelativeTime(r.lastVerifiedIsoTime ?? ''),
        provisional: this.provisional,
        // Tempdoc 813 §4 — the enrichment tier of the row's meta line.
        enrichmentStages: this.enrichmentStages,
        enrichmentPending: this.enrichmentPending,
        enrichmentBlocked: this.enrichmentBlocked,
        // Tempdoc 914 D3 — keeps the failed chip (and its drill-down) reachable across a retry
        // re-queue window, when the wire row reports the failures as in-flight instead.
        lastKnownFailed: this.lastKnownFailed[pathHash],
      });
      return {
        pathHash,
        // Tempdoc 804 §B9 (F8) — never the raw hash as the row's name (see folderRowLabel).
        displayPath: folderRowLabel(pathHash, this.resolvedPaths[pathHash]).label,
        status: fs.glyph,
        metaText: fs.metaText,
        walkError: undefined,
        // Tempdoc 599 §16/B1 — the clickable "N failed" chip's count (the declared renderer emits an
        // intent the surface handles by opening the failed-files drawer). Named `failed` (the seam's
        // output field), not `failedCount`, to stay clear of the folder-status-derivation gate's
        // raw-wire-field scan (only the seam reads `row.failedCount`).
        failed: fs.failed,
        // Tempdoc 914 D3 — `failed` is being carried across a retry window, so the chip's
        // accessible name says "last known" instead of asserting it as this tick's count.
        failedLastKnown: fs.failedIsLastKnown === true,
      };
    });
    return html`<jf-declared-surface
      .declaration=${body}
      .data=${{ folders }}
      .enabled=${true}
      @jf-folder-card-remove=${(e: Event) =>
        void this.handleRemoveRoot((e as CustomEvent<{ pathHash: string }>).detail.pathHash)}
      @jf-folder-card-show-failed=${(e: Event) =>
        openFailedJobs((e as CustomEvent<{ pathHash: string }>).detail.pathHash)}
    ></jf-declared-surface>`;
  }

  /**
   * Fetch the watched-root rows.
   *
   * <p>Tempdoc 599 §9.3: in `live` mode (the aiState-tick refresh) the request is COUNTS-FREE —
   * it skips the expensive per-root `Files.walk` (the §11/U6 cost) and relies on the cheap, always-
   * returned per-folder job counts (`inFlightCount`/`failedCount`) to drive the live status. The
   * filesystem `fileCount` (which a counts-free response reports as -1) is preserved from the last
   * full refresh by pathHash, so it does not flicker to "count pending". Live refreshes are
   * throttled and never toggle the loading spinner or clobber the error banner.
   */
  private async refresh(opts: { live?: boolean } = {}): Promise<void> {
    const live = opts.live === true;
    if (live) {
      if (this.refreshing) return;
      const sinceLast = Date.now() - this.lastLiveRefreshAtMs;
      if (sinceLast < LIVE_REFRESH_MIN_MS) return;
      this.lastLiveRefreshAtMs = Date.now();
    }
    this.refreshing = true;
    if (!live) {
      this.loading = true;
      this.error = null;
    }
    try {
      const res = await this.doFetch(live ? ENDPOINT : ENDPOINT + '?counts=true');
      if (!res.ok) {
        throw new Error(`HTTP ${res.status}`);
      }
      const body = parseWireContract(listResponseSchema, await res.json(), ENDPOINT);
      let items = body.items ?? [];
      if (live) {
        // Counts-free rows carry fileCount=-1; keep the last-known filesystem count per folder.
        const prev = new Map(this.roots.map((r) => [r.pathHash, r.fileCount]));
        items = items.map((r) => {
          if (r.fileCount != null && r.fileCount >= 0) return r;
          const known = r.pathHash ? prev.get(r.pathHash) : undefined;
          return known != null && known >= 0 ? { ...r, fileCount: known } : r;
        });
      }
      // Tempdoc 914 D3 — fold the newest counts into the bounded last-known-failed memory BEFORE
      // the rows render, so a poll that lands mid-retry (failures re-queued ⟹ `failedCount` 0,
      // `inFlightCount` > 0) still renders a reachable "N failed" chip. The retention rule lives in
      // the pure fold next to the seam that reads it.
      this.lastKnownFailed = rememberFailedCounts(this.lastKnownFailed, items, {
        provisional: this.provisional,
      });
      this.roots = items;
      // Lazy-resolve hashes via core.resolve-path-hash (slice 450 §1.1).
      for (const r of this.roots) {
        const hash = r.pathHash;
        if (hash && !this.resolvedPaths[hash]) {
          void resolvePathLazy(RESOURCE_ID, hash, { apiBase: this.apiBase }).then((path) => {
            if (path) {
              this.resolvedPaths = { ...this.resolvedPaths, [hash]: path };
            }
          });
        }
      }
      // Tempdoc 811 C-2a — only on a full refresh. The probe is a corpus-wide facet scan, far too
      // heavy for the 4-second aiState tick, and non-root sources only change on an ingest.
      if (!live) {
        void this.loadOtherSources();
      }
    } catch (err) {
      // Live (background) failures stay silent — don't clobber the surface with a tick error.
      if (!live) {
        this.error = err instanceof Error ? err.message : String(err);
      }
    } finally {
      if (!live) {
        this.loading = false;
      }
      this.refreshing = false;
    }
  }

  /**
   * Tempdoc 811 C-2a — enumerate the non-root collections via the `collection` facet.
   *
   * Runs AFTER `this.roots` is populated: a watched root's own collection is subtracted, so the
   * section lists only what no folder row already describes. Failures are silent (the section is
   * simply absent) — the same posture as `loadExcludes`: an unavailable enumeration must not put an
   * error banner over a Library whose folder rows loaded fine.
   */
  private async loadOtherSources(): Promise<void> {
    if (this.probing) return;
    this.probing = true;
    const cap = facetScanCapFor(this.defaultScopeDocuments);
    // Stamped before the request, so a failing probe cannot re-fire on every status tick.
    this.lastProbeCap = cap;
    try {
      const payload = await fetchCollectionFacet(cap, this.apiBase);
      this.otherSources = deriveOtherSources(
        payload,
        this.roots.map((r) => r.collection),
      );
    } catch {
      // Silent: an unreachable probe leaves the last known list rather than asserting "none".
    } finally {
      this.probing = false;
    }
  }

  /**
   * Tempdoc 811 C-2a — remove every document carrying this collection via the #380 route. Names the
   * count in the confirm, because "mcp-ingest" tells the user nothing about how much is at stake and
   * nothing else on this surface can show them the documents first.
   */
  private async handleRemoveCollection(source: OtherSource): Promise<void> {
    const noun = source.docCount === 1 ? 'document' : 'documents';
    const ok = await this.host_.ui.showConfirmDialog(
      `Remove ${source.docCount.toLocaleString()} ${noun} ingested as '${source.collection}'? `
        + 'They were added outside your watched folders, so nothing will re-index them.',
      { confirmLabel: 'Remove', destructive: true },
    );
    if (!ok) return;
    this.error = null;
    try {
      const res = await this.doFetch('/api/indexing/collections', {
        method: 'DELETE',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ collection: source.collection }),
      });
      if (!res.ok) {
        throw new Error(`HTTP ${res.status}`);
      }
      await this.refresh();
    } catch (err) {
      this.error = err instanceof Error ? err.message : String(err);
    }
  }

  private doFetch(path: string, init?: RequestInit): Promise<Response> {
    return this.host_.data.fetch(path, {
      method: init?.method,
      headers: init?.headers as Record<string, string> | undefined,
      body: init?.body as string | undefined,
      signal: init?.signal ?? undefined,
    });
  }

  private observeGapDecision(migration: { migrationState?: string | null;
    buildingGenerationId?: string | null } | null | undefined): void {
    const building = migration?.buildingGenerationId ?? '';
    const key = migration?.migrationState === 'AWAITING_ACCEPTANCE'
      && /^g-[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(building)
      ? building.slice(2) : null;
    if (key !== this.observedGapKey) {
      this.observedGapKey = key;
      this.gapDecision = null;
      this.gapDecisionError = null;
      this.gapRequestSerial++;
      this.lastGapReadAtMs = 0;
    }
    if (key && !this.gapLoading && Date.now() - this.lastGapReadAtMs >= 4_000) {
      void this.loadGapDecision(key);
    }
  }

  private async loadGapDecision(key: string): Promise<void> {
    this.gapLoading = true;
    this.lastGapReadAtMs = Date.now();
    const serial = ++this.gapRequestSerial;
    try {
      const response = await this.doFetch(`/api/operation-history/${encodeURIComponent(key)}`);
      if (!response.ok) throw new Error(`Could not read migration gaps (HTTP ${response.status})`);
      const outcome = parseWireContract(operationOutcomeViewSchema,
        await response.json(), '/api/operation-history/{operationKey}');
      if (serial !== this.gapRequestSerial || key !== this.observedGapKey) return;
      if (outcome.phase !== 'awaiting_acceptance') {
        this.gapDecision = null;
        return;
      }
      const result = outcome.result;
      if (!result?.gapListHash || !/^[0-9a-f]{64}$/.test(result.gapListHash)
          || !result.gaps?.length || result.gaps.some((gap) => !gap.unitId || !gap.reason)) {
        throw new Error('Migration gap decision is incomplete');
      }
      this.gapDecision = { reindexKey: key, gapListHash: result.gapListHash,
        gaps: result.gaps.map((gap) => ({ unitId: gap.unitId!, reason: gap.reason! })) };
      this.gapDecisionError = null;
    } catch (failure) {
      if (serial === this.gapRequestSerial && key === this.observedGapKey) {
        this.gapDecisionError = failure instanceof Error ? failure.message : String(failure);
      }
    } finally {
      if (serial === this.gapRequestSerial) this.gapLoading = false;
    }
  }

  private async acceptCurrentGaps(): Promise<void> {
    const decision = this.gapDecision;
    if (!decision || this.gapDecisionBusy) return;
    const attemptSerial = ++this.gapDecisionAttemptSerial;
    this.gapDecisionBusy = true;
    this.gapDecisionError = null;
    try {
      const result = await this.host_.data.invokeOperation('core.accept-gaps', {
        reindexKey: decision.reindexKey, gapListHash: decision.gapListHash,
      }, { consented: true });
      if (!result.success) throw new Error(result.message ?? 'Migration gap acceptance failed');
      if (attemptSerial === this.gapDecisionAttemptSerial && this.isConnected
          && decision.reindexKey === this.observedGapKey) {
        await this.loadGapDecision(decision.reindexKey);
      }
    } catch (failure) {
      if (attemptSerial !== this.gapDecisionAttemptSerial || !this.isConnected
          || decision.reindexKey !== this.observedGapKey) return;
      const refreshSerial = this.gapRequestSerial + 1;
      await this.loadGapDecision(decision.reindexKey);
      if (refreshSerial === this.gapRequestSerial && this.isConnected
          && attemptSerial === this.gapDecisionAttemptSerial) {
        this.gapDecisionError = failure instanceof Error ? failure.message : String(failure);
      }
    } finally {
      if (attemptSerial === this.gapDecisionAttemptSerial) this.gapDecisionBusy = false;
    }
  }

  private async loadExcludes(): Promise<void> {
    if (this.excludesBusy || this.excludesAttempt) return;
    this.excludesLoadStarted = true;
    this.excludesBusy = true;
    try {
      const body = await readSettingsObservation((path, init) => this.doFetch(path, init));
      this.excludesText = (body.ui?.excludePatterns ?? []).join('\n');
      this.excludesWitness = body.witness;
      this.excludesLoaded = true;
      this.error = null;
    } catch (err) {
      this.excludesLoaded = false;
      this.excludesWitness = null;
      this.error = err instanceof Error ? err.message : String(err);
    } finally {
      this.excludesBusy = false;
    }
  }

  private async persistExcludes(): Promise<boolean> {
    if (!this.excludesLoaded || !this.excludesWitness) return false;
    try {
      if (!this.excludesAttempt) {
        const patterns = this.excludesText.split('\n').map((value) => value.trim()).filter(Boolean);
        this.excludesAttempt = createSettingsAttempt({ ui: { excludePatterns: patterns } }, this.excludesWitness);
      }
      const result = await executeSettingsAttempt((path, init) => this.doFetch(path, init), this.excludesAttempt);
      this.excludesWitness = result.witness;
      this.excludesAttempt = null;
      return true;
    } catch (err) {
      // An uncertain save remains the same attempt on the next Preview/Apply click.
      // Do not let an edited draft acquire a newer witness while that row is unresolved.
      const state = err instanceof SettingsAttemptError ? err.response?.['state'] : undefined;
      const terminal = state === 'FAILED' || state === 'CANCELLED'
        || (err instanceof SettingsAttemptError && ['VERSION_CONFLICT', 'SETTINGS_READ_ONLY', 'INVALID_REQUEST', 'INVALID_PATH',
          'OPERATION_KEY_INVALID', 'OPERATION_KEY_REUSED', 'OPERATION_KEY_EXPIRED'].includes(err.code));
      if (terminal) {
        this.excludesAttempt = null;
        this.excludesLoaded = false;
        this.excludesWitness = null;
      }
      this.error = err instanceof Error ? err.message : String(err);
      return false;
    }
  }

  private async invoke(
    operationId: string,
    args: Record<string, unknown> = {},
  ): Promise<void> {
    this.error = null;
    try {
      await this.host_.data.invokeOperation(operationId, args);
      await this.refresh();
    } catch (err) {
      this.error = err instanceof Error ? err.message : String(err);
    }
  }

  /**
   * The HEADER's "Add Folder" — open the add flow. Distinct from {@link handleAddRoot}, which is the
   * form's submit (tempdoc 914 D4): with one method for both, the header button submitted whenever
   * the form happened to be open, and in Tauri mode it would then never re-open the picker.
   *
   * The native picker answers WHICH folder, not which collection, so it now fills the same add form
   * the browser flow uses (path pre-filled, preview fetched) and the user confirms with Add — that is
   * where the collection field lives, in both modes. A cancelled picker still just opens the form.
   */
  private async handleAddFolderClick(): Promise<void> {
    if (this.host_.platform.capabilities.has('folder-picker')) {
      const picked = await this.host_.platform.pickFolder();
      this.showManualInput = true;
      if (picked) {
        this.pendingPath = picked;
        this.pendingPreview = null;
        void this.fetchPreview(picked);
      }
      return;
    }
    this.showManualInput = true;
  }

  /** Submit the add form (its Add button, or Enter in either field). */
  private async handleAddRoot(): Promise<void> {
    const path = this.pendingPath.trim();
    if (!path) return;
    // Tempdoc 599 §9.4 — don't create the job if the preview already says it can't be added.
    if (this.addAvailability().kind !== 'available') return;
    await this.invoke(OP_ADD, this.addRootArgs(path));
    this.pendingPath = '';
    this.pendingCollection = '';
    this.pendingPreview = null;
    this.showManualInput = false;
  }

  /**
   * Tempdoc 914 D4 — the `core.add-watched-root` arguments.
   *
   * `collection` is OMITTED when blank rather than sent as `""` or as a literal `"default"`. Not
   * because the stored result would differ — `AddWatchedRootHandler` and `RootLifecycleOps` both
   * normalize absent/blank to `DEFAULT_COLLECTION`, so all three spellings converge (review nit) —
   * but because the request should say what the USER said. Omitting keeps the backend's default the
   * backend's to choose; hard-coding `"default"` here would pin another component's decision in the
   * FE, and `""` is a value `IngestCollectionPolicy.normalizeRequested` explicitly rejects.
   */
  private addRootArgs(path: string): Record<string, unknown> {
    const collection = this.pendingCollection.trim();
    return collection ? { path, collection } : { path };
  }

  /** Tempdoc 599 §9.4 — typed-path input: update the path + (debounced) validate it. */
  private onPendingPathInput(value: string): void {
    this.pendingPath = value;
    this.pendingPreview = null;
    if (this.previewTimer !== null) {
      window.clearTimeout(this.previewTimer);
      this.previewTimer = null;
    }
    const path = value.trim();
    if (!path) return;
    this.previewTimer = window.setTimeout(() => void this.fetchPreview(path), 350);
  }

  /** Tempdoc 599 §9.4 — fetch add-time validation for a typed path; last-write-wins via previewSeq. */
  private async fetchPreview(path: string): Promise<void> {
    const seq = ++this.previewSeq;
    try {
      const res = await this.doFetch('/api/indexing-roots/preview', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ path }),
      });
      if (!res.ok) return;
      const body = await res.json();
      if (seq !== this.previewSeq) return; // a newer keystroke superseded this result
      this.pendingPreview = {
        path,
        exists: Boolean(body?.exists),
        isDir: Boolean(body?.isDir),
        fileCount: typeof body?.fileCount === 'number' ? body.fileCount : -1,
        capped: Boolean(body?.capped),
        alreadyWatched: Boolean(body?.alreadyWatched),
      };
    } catch {
      // Silent: preview is advisory; the Add path still validates server-side.
    }
  }

  /**
   * Tempdoc 599 §9.4 — the Add button's availability, gated on the preview (596 reachable reason).
   * Available while still typing/checking (so the button isn't dead before the preview lands); a
   * settled preview that can't be added yields a soft `unavailable` with a reachable reason.
   */
  private addAvailability() {
    const path = this.pendingPath.trim();
    if (!path) return unavailableBecause('Enter a folder path', true);
    // Tempdoc 914 D4 — a supplied collection must not name an app-internal corpus. The AUTHORITY is
    // `IngestCollectionPolicy`, enforced by the REST route and, since tempdoc 913 D6, by the
    // Operation handler this surface actually invokes (`AddWatchedRootHandler.java:72-77` routes the
    // value through `normalizeRequested` and returns INVALID_REQUEST). This check is defence in
    // depth, not the enforcement: it gives the reason at the keystroke, in the field, with Add
    // disabled — instead of a round trip that returns an error banner after the user has committed.
    const collection = this.pendingCollection.trim();
    if (collection && isReservedCollection(collection)) {
      return unavailableBecause(`"${collection}" is reserved for JustSearch's own documents`);
    }
    const p = this.pendingPreview;
    if (!p || p.path !== path) return AVAILABLE; // checking — don't block prematurely
    if (!p.exists) return unavailableBecause('Path not found');
    if (!p.isDir) return unavailableBecause('Not a folder');
    if (p.alreadyWatched) return unavailableBecause('Already watched');
    return AVAILABLE;
  }

  private async handleRemoveRoot(pathHash: string): Promise<void> {
    const resolved = this.resolvedPaths[pathHash];
    if (!resolved) {
      this.error = 'Path resolution pending; retry in a moment.';
      return;
    }
    const ok = await this.host_.ui.showConfirmDialog(
      // 813 §19 (W3) — the confirm states the ENRICHMENT consequence too: removal also discards
      // whatever semantic work was still in flight for this folder. Deliberately no numeric latency:
      // the stop-to-quiet bound is mode-dependent (combined mode checkpoints at 1-8 document
      // granularity; individual mode retains whole-batch atomicity), so "within seconds" would
      // overclaim exactly where the old copy underclaimed.
      `Remove ${resolved}? Files indexed from this folder will be removed from search results, and any enrichment still running for it is stopped and discarded.`,
      { confirmLabel: 'Remove', destructive: true },
    );
    if (!ok) return;
    await this.invoke(OP_REMOVE, { path: resolved });
  }

  /**
   * Tempdoc 626 §Axis-C — the one-click recovery for an "unverified" folder (its reconcile couldn't
   * verify deletions). {@code core.reindex} is incremental-all-roots; {@code force:true} bypasses the
   * unchanged fast-path so deletions are re-checked and the per-root unverified state clears once the
   * reconcile verifies it. {@link #invoke} surfaces errors and refreshes the rows on completion.
   */
  private async handleReindexToVerify(): Promise<void> {
    await this.invoke('core.reindex', { force: true });
  }

  private async handlePreviewExcludes(): Promise<void> {
    await this.saveAndInvokeExcludes(OP_PREVIEW);
  }

  private async handleApplyExcludes(): Promise<void> {
    await this.saveAndInvokeExcludes(OP_APPLY);
  }

  private async saveAndInvokeExcludes(operation: string): Promise<void> {
    if (this.excludesBusy || !this.excludesLoaded) return;
    this.excludesBusy = true;
    try {
      if (await this.persistExcludes()) await this.invoke(operation, {});
    } finally {
      this.excludesBusy = false;
    }
  }

  private renderStatusIcon(status: string): TemplateResult {
    if (status === 'indexed') {
      return html`<span class="status-icon indexed"
        >${icon({ name: 'check-circle-2', size: 16 })}</span
      >`;
    }
    if (status === 'error') {
      return html`<span class="status-icon error"
        >${icon({ name: 'alert-circle', size: 16 })}</span
      >`;
    }
    if (status === 'unavailable') {
      // Tempdoc 599 §16/A1 — folder path gone: a muted x-circle (not the red error glyph).
      return html`<span class="status-icon unavailable"
        >${icon({ name: 'x-circle', size: 16 })}</span
      >`;
    }
    if (status === 'unverified') {
      // Tempdoc 626 §Axis-C — indexed but deletions couldn't be verified: a muted caution glyph,
      // never the green ✓ (the folder is searchable but its delete correspondence is unknown).
      return html`<span class="status-icon unverified"
        >${icon({ name: 'alert-triangle', size: 16 })}</span
      >`;
    }
    return html`<span class="status-icon pending"
      >${icon({ name: 'clock', size: 16 })}</span
    >`;
  }

  /**
   * Tempdoc 599 §16/B1 — the clickable "N failed" chip. Tempdoc 914 D3 / review S2-2: when the count
   * is being carried across a window with no settled answer, the chip says so ON SCREEN (the shared
   * `failedChipCopy` text plus the muted-italic treatment) and not only to a screen reader. The
   * `label` it sets always STARTS with that visible text, which is what WCAG 2.5.3 asks for (see
   * `failedChipCopy` for why the label cannot simply be dropped here).
   */
  private renderFailedChip(pathHash: string, status: FolderStatus): TemplateResult {
    const lastKnown = status.failedIsLastKnown === true;
    const copy = failedChipCopy(status.failed, lastKnown);
    return html`<jf-button
      class="failed-chip"
      data-last-known=${lastKnown ? 'true' : 'false'}
      variant="ghost"
      size="sm"
      label=${copy.label}
      title=${copy.title || nothing}
      .onActivate=${() => openFailedJobs(pathHash)}
      >${icon({ name: 'alert-circle', size: 12 })} ${copy.text}</jf-button
    >`;
  }

  private renderCard(root: IndexedRootView): TemplateResult {
    const pathHash = root.pathHash ?? '';
    // Tempdoc 804 §B9 (F8) — the row's NAME is its folder name (full path on hover), or an honest
    // "path unavailable"; never the raw hash beside a Remove button.
    const { label: displayPath, title: displayTitle } = folderRowLabel(
      pathHash,
      this.resolvedPaths[pathHash],
    );
    // Tempdoc 599 §9.1 — glyph + meta line project from the single folderStatus seam, so the row
    // reports a truthful state (✓ only on job drain) and a live "Indexing · N remaining" count-down.
    const status = folderStatus(root, {
      relativeTime: this.host_.utilities.formatRelativeTime(root.lastIndexedIsoTime ?? ''),
      // Tempdoc 626 §Recency — the freshness heartbeat (last index↔disk confirmation), distinct from
      // lastIndexed (last write). Same host time-ago util.
      verifiedRelativeTime: this.host_.utilities.formatRelativeTime(root.lastVerifiedIsoTime ?? ''),
      provisional: this.provisional,
      // Tempdoc 813 §4 — the enrichment tier of the row's meta line.
      enrichmentStages: this.enrichmentStages,
      enrichmentPending: this.enrichmentPending,
      enrichmentBlocked: this.enrichmentBlocked,
      // Tempdoc 914 D3 — see the declared-renderer projection above: same memory, same reason.
      lastKnownFailed: this.lastKnownFailed[pathHash],
    });
    return html`
      <div class="card">
        <span class="card-icon">${icon({ name: 'folder', size: 24 })}</span>
        <div class="card-info">
          <div class="card-path">
            ${this.renderStatusIcon(status.glyph)}<span
              class="folder-name"
              title=${displayTitle}
              data-testid="library-folder-name"
              >${displayPath}</span
            >
          </div>
          <div class="card-meta">
            ${status.metaText}${status.failed > 0
              ? this.renderFailedChip(pathHash, status)
              : nothing}${status.state === 'unverified'
              ? html`<jf-button
                  class="reindex-chip"
                  variant="ghost"
                  size="sm"
                  label="Reindex to verify"
                  .onActivate=${() => this.handleReindexToVerify()}
                  >${icon({ name: 'refresh-cw', size: 12 })} Reindex</jf-button
                >`
              : nothing}
          </div>
        </div>
        <jf-button
          variant="danger"
          label="Remove"
          .onActivate=${() => this.handleRemoveRoot(pathHash)}
        >
          ${icon({ name: 'trash-2', size: 14 })} Remove
        </jf-button>
      </div>
    `;
  }

  /**
   * Tempdoc 811 C-2a — the sources that are not a watched folder: documents an agent (or the ingest
   * API) added from a path under no root. They are searchable and pill-labelled in results, so
   * leaving them off this surface made them un-seeable and un-removable.
   *
   * Absent when there is nothing to say. A TRUNCATED scan is the one case where an empty list still
   * renders: the scan omits collections rather than undercounting them (see `otherSources.ts`), so
   * silence there would be the same invisibility this section exists to end.
   */
  private renderOtherSources(): TemplateResult | typeof nothing {
    const { sources, truncated } = this.otherSources;
    if (sources.length === 0 && !truncated) return nothing;
    return html`
      <div class="excludes-section" data-testid="library-other-sources">
        <div class="excludes-header">
          <div>
            <h3>Other sources</h3>
            <p>
              Documents added from outside your watched folders — by an agent or the ingest API.
              Nothing re-indexes them, so removing a source is permanent.
            </p>
          </div>
        </div>
        ${sources.length > 0
          ? html`<div class="cards">
              ${sources.map((s) => this.renderOtherSourceCard(s))}
            </div>`
          : nothing}
        ${truncated
          ? html`<div class="other-sources-note">
              ${icon({ name: 'alert-triangle', size: 12 })}
              <span
                >The index was too large to scan completely — this list may be missing
                sources.</span
              >
            </div>`
          : nothing}
      </div>
    `;
  }

  private renderOtherSourceCard(source: OtherSource): TemplateResult {
    const noun = source.docCount === 1 ? 'document' : 'documents';
    return html`
      <div class="card">
        <span class="card-icon">${icon({ name: 'hard-drive', size: 24 })}</span>
        <div class="card-info">
          <div class="card-path">
            <span data-testid="library-other-source-name">${source.collection}</span>
          </div>
          <div class="card-meta">${source.docCount.toLocaleString()} ${noun}</div>
        </div>
        <jf-button
          variant="danger"
          label=${`Remove ${source.collection}`}
          .onActivate=${() => void this.handleRemoveCollection(source)}
        >
          ${icon({ name: 'trash-2', size: 14 })} Remove
        </jf-button>
      </div>
    `;
  }

  private renderHeader(): TemplateResult {
    return html`
      <div class="header">
        <div class="header-left">
          <h2>Library</h2>
          <p class="subtitle">Manage your indexed folders</p>
        </div>
        <div class="actions">
          <!-- Tempdoc 511 Phase 9: <jf-operation> aggregate component
               supersedes the slice 509 <jf-op-button>. LibrarySurface
               is user-tier; core.reindex's wire audience=USER passes
               the gate. -->
          <jf-operation context="button" operation-id="core.reindex" api-base=${this.apiBase} @op-success=${() => this.refresh()}></jf-operation>
          <!-- Tempdoc 813 §6: the manual "Process pending enrichment" trigger — drains the pending
               VDU + embedding work for already-indexed documents, same catalog-driven pattern as
               core.reindex above. Its LABEL comes from the operation catalog (one string, both
               render sites); the operation id keeps its historical name. -->
          <jf-operation
            context="button"
            operation-id="core.trigger-offline-processing"
            api-base=${this.apiBase}
          ></jf-operation>
          <jf-button
            variant="primary"
            label="Add Folder"
            .onActivate=${() => this.handleAddFolderClick()}
          >
            ${icon({ name: 'folder-plus', size: 14 })} Add Folder
          </jf-button>
        </div>
      </div>
    `;
  }

  override render(): TemplateResult {
    // Tempdoc 571 §11 / 578 — host/member composition: tab 0 is Library's own "Folders" body
    // (projected via a slot so this surface's shadow CSS styles it); the remaining tabs are the
    // declared member surfaces (Browse), mounted by <jf-surface-tabs>. Members are read from the
    // live catalog (the wire's `members`), so a new member is purely a catalog declaration.
    const members = getSurface('core.library-surface')?.members ?? [];
    const items: SurfaceTabItem[] = [
      { id: 'folders', label: 'Folders', altitude: 'PRODUCT', slot: 'tab-folders' },
      ...members.map((mid) => ({
        id: mid,
        label: present({ kind: 'surface', id: mid }).label,
        altitude: getSurface(mid)?.altitude,
        surfaceId: mid,
      })),
    ];
    // A single declared member with no host-body of interest could render bare; but Library always
    // has its Folders body, so the tab strip is always meaningful.
    return html`
      <jf-surface-tabs
        tablist-label="Library views"
        api-base=${this.apiBase}
        .host_=${this.host_}
        active-id=${this.activeTab}
        .items=${items}
        @tab-change=${(e: CustomEvent<{ id: string }>) => (this.activeTab = e.detail.id)}
      >
        <div slot="tab-folders" class="folders-scroll">${this.renderFolders()}</div>
      </jf-surface-tabs>
    `;
  }

  /** Tempdoc 599 §9.4 — the add-time preview status line below the path input. */
  private renderPreviewLine(): TemplateResult {
    const path = this.pendingPath.trim();
    const p = this.pendingPreview;
    if (!path || !p || p.path !== path) return html``;
    if (!p.exists) {
      return html`<div class="add-preview err">${icon({ name: 'alert-circle', size: 14 })} Path not found</div>`;
    }
    if (!p.isDir) {
      return html`<div class="add-preview err">${icon({ name: 'alert-circle', size: 14 })} Not a folder</div>`;
    }
    if (p.alreadyWatched) {
      return html`<div class="add-preview warn">${icon({ name: 'alert-circle', size: 14 })} Already watched</div>`;
    }
    const files =
      p.fileCount >= 0
        ? ` · ${p.fileCount.toLocaleString()}${p.capped ? '+' : ''} ${p.fileCount === 1 && !p.capped ? 'file' : 'files'}`
        : '';
    return html`<div class="add-preview ok">${icon({ name: 'check-circle-2', size: 14 })} Folder found${files}</div>`;
  }

  private renderFolders(): TemplateResult {
    return html`
      ${this.renderHeader()}
      ${this.renderGapDecision()}
      ${!this.isTauri
        ? html`<div class="browser-banner">
            ${icon({ name: 'alert-circle', size: 14 })}
            <span
              >Running in browser mode. Native folder picker not available — enter
              paths manually.</span
            >
          </div>`
        : nothing}

      ${this.error
        ? html`<div class="error-banner">
            ${icon({ name: 'alert-circle', size: 14 })}
            <span>${this.error}</span>
            <jf-button
              variant="ghost"
              size="icon"
              label="Dismiss error"
              .onActivate=${() => (this.error = null)}
              >${icon({ name: 'x', size: 12 })}</jf-button
            >
          </div>`
        : nothing}

      ${this.showManualInput
        ? html`
            <div class="add-row">
              <input
                type="text"
                aria-label="Folder path"
                placeholder="C:\\path\\to\\folder"
                .value=${this.pendingPath}
                @keydown=${(e: KeyboardEvent) => {
                  if (e.key === 'Enter') void this.handleAddRoot();
                }}
                @input=${(e: Event) =>
                  this.onPendingPathInput((e.target as HTMLInputElement).value)}
              />
              <!-- Tempdoc 914 D4 — the collection this folder's documents are tagged with. Optional:
                   left blank, the argument is omitted and the backend applies its own default. -->
              <input
                class="add-collection"
                type="text"
                aria-label="Collection (optional)"
                data-testid="library-add-collection"
                placeholder="Collection (optional)"
                .value=${this.pendingCollection}
                @keydown=${(e: KeyboardEvent) => {
                  if (e.key === 'Enter') void this.handleAddRoot();
                }}
                @input=${(e: Event) =>
                  (this.pendingCollection = (e.target as HTMLInputElement).value)}
              />
              <jf-button
                variant="primary"
                label="Add"
                .availability=${this.addAvailability()}
                .onActivate=${() => this.handleAddRoot()}
                >Add</jf-button
              >
              <jf-button label="Cancel" .onActivate=${() => (this.showManualInput = false)}
                >Cancel</jf-button
              >
            </div>
            ${this.renderPreviewLine()}
          `
        : nothing}

      ${this.loading
        ? html`<div class="empty">Loading…</div>`
        : this.roots.length === 0
          ? this.provisional
            ? html`<div class="empty">
                Rebuilding index… your watched folders will reappear when it finishes.
              </div>`
            : html`<div class="empty">
              No watched folders. Click "Add Folder" to add one.
            </div>`
          : this.renderCardsRegion()}

      ${this.renderOtherSources()}

      ${this.activeTab === 'folders'
        ? html`<jf-ingestion-summary .host_=${this.host_}></jf-ingestion-summary>`
        : nothing}

      <div class="excludes-section">
        <div class="excludes-header">
          <div>
            <h3>Exclude patterns</h3>
            <p>
              One glob per line. Bare names like <code>node_modules</code> or
              <code>dist/</code> match at any depth.
            </p>
          </div>
          <div class="actions">
            <jf-button label="Reload saved patterns" .disabled=${this.excludesBusy || this.excludesAttempt !== null}
              .onActivate=${() => void this.loadExcludes()}>Reload saved patterns</jf-button>
            <jf-button label="Preview" .disabled=${!this.excludesLoaded || this.excludesBusy}
              .onActivate=${() => void this.handlePreviewExcludes()}
              >Preview</jf-button
            >
            <jf-button label="Apply" .disabled=${!this.excludesLoaded || this.excludesBusy}
              .onActivate=${() => void this.handleApplyExcludes()}
              >Apply</jf-button
            >
          </div>
        </div>
        ${this.excludesAttempt && !this.excludesBusy
          ? html`<p>Save completion is unknown. Retry Preview or Apply to check the same save.</p>` : nothing}
        <textarea
          aria-label="Exclude patterns"
          ?disabled=${!this.excludesLoaded || this.excludesBusy || this.excludesAttempt !== null}
          .value=${this.excludesText}
          @input=${(e: Event) =>
            (this.excludesText = (e.target as HTMLTextAreaElement).value)}
          placeholder="**/node_modules/**"
        ></textarea>
      </div>
    `;
  }

  private renderGapDecision(): TemplateResult | typeof nothing {
    if (!this.observedGapKey) return nothing;
    const decision = this.gapDecision;
    return html`<section class="gap-decision" aria-label="Migration gaps">
      <h3>New index — activation requires your decision</h3>
      ${decision ? html`
        <p>${decision.gaps.length} document${decision.gaps.length === 1 ? '' : 's'} could not be
          included in the new index. Your current index stays available until you accept this list.</p>
        <ul>${decision.gaps.map((gap) => html`<li>${gap.unitId}: ${gap.reason}</li>`)}</ul>
        <jf-button label="Accept gaps and activate" variant="primary"
          .disabled=${this.gapDecisionBusy}
          .onActivate=${() => void this.acceptCurrentGaps()}>Accept gaps and activate</jf-button>
      ` : html`<p>Reading the migration gap list…</p>`}
      ${this.gapDecisionError ? html`<p role="alert">${this.gapDecisionError}</p>` : nothing}
    </section>`;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-library-surface')) {
  customElements.define('jf-library-surface', LibrarySurface);
}
