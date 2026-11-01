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
import { repeat } from 'lit/directives/repeat.js';
import { renderFolderRow, folderRowStyles, type FolderRow, type FolderRowIntent } from '../components/folderRow.js';
import { createSettingsAttempt, executeSettingsAttempt, readSettingsObservation, SettingsAttemptError, type SettingsAttempt, type SettingsWitness } from '../../api/settingsAttempt.js';
import { describeExactPath, type ExactPathExclusion } from '../../api/exclusionPolicy.js';
import { decideForget, forgetObservationText, parseForgetPage, publishForgetCompletion } from '../../api/domains/forget.js';
import { deepActiveElement } from '../utils/keyboardHandler.js';
import type { ForgetPage, ForgetOutcome } from '../../api/generated/schema-types/index.js';
import { confirmForget } from '../components/searchResults/forgetConsent.js';
import { JfElement } from '../primitives/JfElement.js';
import '../components/OpButton.js';
import { OperationError } from '../operations/OperationClient.js';
import type { OpSuccessEventDetail, OpErrorEventDetail } from '../components/OpButton.js';
import '../components/Button.js';
import type { JfButton } from '../components/Button.js';
import '../components/IngestionSummary.js';
// Tempdoc 571 §11 / 578 — Library ⊇ Browse: Library hosts the Browse file-tree as a tab.
import '../components/SurfaceTabs.js';
import type { SurfaceTabItem } from '../components/SurfaceTabs.js';
import { getSurface } from '../../api/registry/SurfaceCatalogClient.js';
import { present } from '../display/present.js';
import { takeIndexingRuleFocus, subscribeIndexingRuleFocus, type IndexingRuleControl } from '../router/indexingRuleIntent.js';
import { takeMemberTabIntent, subscribeMemberTab } from '../router/memberTabIntent.js';
import { resolvePathLazy } from '../hooks/resolvePathLazy.js';
import { icon } from '../components/Icon.js';
// 569 §14 — render the indexed-folder cards through the projection engine (the 2nd real surface).
import { activeBodyFor, subscribePresentation } from '../state/presentationRuntime.js';
import { subscribeAiState } from '../state/aiStateStore.js';
import '../components/IndexingControls.js';
import '../components/IndexingRulesEditor.js';
import type { IndexingRulesEditor } from '../components/IndexingRulesEditor.js';
import '../components/FailedJobsDrawer.js';
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
import { operationOutcomeViewSchema, type OperationOutcomeView } from '../../api/generated/schema-types/index.js';
// Tempdoc 599 §9.1 — the ONE per-folder status derivation; the row glyph + meta line project from it.
import {
  folderStatus,
  rememberFailedCounts,
  rootCoverage,
  type FolderStatus,
} from '../state/folderStatus.js';
// Tempdoc 813 §4 — the index-wide progress authority supplies enrichment-stage applicability, which
// the per-root wire row cannot carry (counts only, no enabled flags).
import {
  selectIndexingProgress,
  type EnrichmentApplicability,
} from '../state/indexingProgress.js';
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
  /** Optional for older Engines; exact membership still uses alreadyWatched. */
  readonly overlap?: { readonly path: string; readonly relationship: 'inside' | 'containing' };
}

function overlapExplanation(preview: FolderPreview): string | null {
  const overlap = preview.overlap;
  if (!overlap) return null;
  return overlap.relationship === 'inside'
    ? `This folder is inside the watched folder “${overlap.path}” and is already covered by it. Choose a separate folder.`
    : `This folder contains the watched folder “${overlap.path}”. Choose a separate folder.`;
}

// The substrate list response: a thin {items, count} envelope around the generated IndexedRootView.
const listResponseSchema = z
  .object({
    items: z.array(indexedRootViewSchema),
    count: z.number().optional(),
  })
  .loose();

const excludesPolicySchema = z.object({
  source: z.string(), sourceDetail: z.string().nullable(), patterns: z.array(z.string()),
});
type ExcludesPolicy = z.infer<typeof excludesPolicySchema>;
function isOverride(policy: ExcludesPolicy | null): boolean {
  return policy?.source === 'env_var' || policy?.source === 'jvm_arg';
}
function policySource(policy: ExcludesPolicy): string {
  return `${policy.source === 'jvm_arg' ? 'system property' : 'environment variable'} ${policy.sourceDetail ?? policy.source}`;
}

// Operation results are untrusted wire data; absent partial-scope fields cannot mean success.
const excludesResultSchema = z.object({
  effectivePolicy: excludesPolicySchema.optional(),
  dryRun: z.boolean(), matchedFiles: z.number().int().nonnegative(),
  confirmedRemovals: z.number().int().nonnegative(), capped: z.boolean(),
  perPattern: z.array(z.object({ pattern: z.string(), matches: z.number().int().nonnegative() })),
  uncheckedScopes: z.array(z.object({ path: z.string(), status: z.enum(['not_checked', 'not_removed']), reason: z.string() })),
});
type ExcludesResult = z.infer<typeof excludesResultSchema>;

/** Support codes stay on each exact gap; the decision itself uses plain language. */
function rebuildGapReason(code: string): string {
  const reasons: Record<string, string> = {
    CANDIDATE_PROJECTION_MISSING: 'The document was not added to the new index.',
    PROJECTION_WITNESS_MISSING: 'The document could not be verified in the new index.',
    DELETE_NOT_APPLIED: 'The document could not be removed from the new index.',
    PROJECTION_DELETE_NOT_APPLIED: 'The document removal could not be verified.',
    PROJECTION_SOURCE_INCOMPLETE: 'Documents from this source are not ready.',
    PROJECTION_SOURCE_MARKER_MISSING: 'This document source could not be verified.',
    PARSER_FAILED: 'The file could not be read by its parser.',
    PARSER_TIMEOUT: 'Reading the file took too long.',
    SANDBOX_FAILED: 'The file reader could not run safely.',
    UNREADABLE: 'The file could not be read.',
    SOURCE_UNREADABLE: 'The source file could not be read.',
    INDEXED_SOURCE_UNREADABLE: 'The indexed source file could not be read again.',
    ROOT_UNAVAILABLE: 'The containing folder could not be found or read.',
    IO_ERROR: 'The file could not be accessed.',
    INPUT_TOO_LARGE: 'The file exceeds the size limit.',
    OFFICE_INPUT_TOO_LARGE: 'The Office document exceeds the size limit.',
    WRITE_FAILED: 'The document could not be saved to the new index.',
    WRITE_UNAVAILABLE_DRAINING: 'The index was unavailable while finishing other work.',
    MISSING_AT_PROCESSING: 'The source file was missing when processing started.',
    DELETED_OR_MISSING: 'The source file was deleted or could not be found.',
    DELETED_AFTER_SNAPSHOT: 'The source file was deleted during the rebuild.',
    STALE_AFTER_EXTRACTION: 'The source file changed while being read.',
    SIZE_CHANGED_AFTER_SNAPSHOT: 'The file size changed during the rebuild.',
    MODIFIED_TIME_CHANGED_AFTER_SNAPSHOT: 'The file was modified during the rebuild.',
    FILE_KEY_CHANGED_AFTER_SNAPSHOT: 'The source file was replaced during the rebuild.',
    SOURCE_KIND_CHANGED_AFTER_SNAPSHOT: 'The source is no longer the same kind of file.',
    LOST_CLAIM_OWNERSHIP: 'Processing ownership changed before the document was ready.',
    NON_REGULAR_SOURCE: 'The source is not a regular file.',
    MISSING_INGESTION_BOUNDARY: 'The document could not be tracked through processing.',
    CLOUD_PLACEHOLDER: 'The file is stored in the cloud and is not available locally.',
    EXTRACTION_DROPOUT_UNRECOVERED: 'No usable text could be recovered from the file.',
    EXTRACTION_DROPOUT_PENDING_FALLBACK: 'The file still needs another reading method.',
    RECOVERY_REFUSED: 'The document could not be recovered after an interruption.',
    SKIPPED_TEMP_OR_SYSTEM: 'The source is a temporary or system file.',
    RETRY_EXHAUSTED: 'The file could not be processed after repeated attempts.',
  };
  return reasons[code] ?? 'The document could not be included. The support code is available in the tooltip.';
}

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
  private expandedFolders = new Set<string>();
  private indexProgress = selectIndexingProgress(null, false, 0, []);
  static properties = {
    apiBase: { attribute: 'api-base', type: String },
    host_: { attribute: false },
    roots: { state: true },
    resolvedPaths: { state: true },
    loading: { state: true },
    error: { state: true },
    excludesText: { state: true },
    excludesLiterals: { state: true },
    unfinishedForgets: { state: true },
    forgetReadStatus: { state: true },
    forgetDecisionKey: { state: true },
    forgetMessage: { state: true },
    excludesInvalidPatterns: { state: true },
    excludesLoaded: { state: true },
    excludesBusy: { state: true },
    excludesError: { state: true },
    excludesNotice: { state: true },
    excludesResult: { state: true },
    excludesPolicy: { state: true },
    excludesConflict: { state: true },
    addBusy: { state: true },
    removingHashes: { state: true },
    actionMessage: { state: true },
    reindexOutcome: { state: true },
    reindexOutcomeError: { state: true },
    reindexOutcomeBusy: { state: true },
    pendingPath: { state: true },
    pendingCollection: { state: true },
    pendingPreview: { state: true },
    showManualInput: { state: true },
    isTauri: { state: true },
    activeTab: { state: true },
    provisional: { state: true },
    indexingActivity: { state: true },
    retryWaiting: { state: true },
    enrichmentStages: { state: true },
    enrichmentPending: { state: true },
    enrichmentBlocked: { state: true },
    otherSources: { state: true },
    bulkOutcome: { state: true },
    gapDecision: { state: true },
    gapDecisionBusy: { state: true },
    gapDecisionError: { state: true },
    gapDecisionErrorCode: { state: true },
    reindexCancelKey: { state: true },
    reindexCancelBusy: { state: true },
    reindexCancelError: { state: true },
    reindexCancelErrorCode: { state: true },
    reindexCancelRecorded: { state: true },
  };

  declare apiBase: string;
  declare host_: PluginHostApi;
  declare roots: IndexedRootView[];
  declare resolvedPaths: Record<string, string>;
  declare loading: boolean;
  declare error: string | null;
  declare excludesText: string;
  declare excludesInvalidPatterns: string[];
  declare excludesLoaded: boolean;
  declare excludesBusy: boolean;
  declare private excludesError: string | null;
  declare private excludesNotice: string;
  declare private excludesResult: ExcludesResult | null;
  private declare unfinishedForgets: ForgetPage | null;
  /** Ephemeral Stage API-owner identity, captured before an asynchronous decision. */
  declare forgetCompletionOwner: object | undefined;
  private declare forgetReadStatus: 'loading' | 'ready' | 'unavailable' | 'unsupported';
  private declare forgetDecisionKey: string | null;
  private declare forgetMessage: string;
  private forgetAfterId = 0;
  private forgetPlacement = 0;
  private forgetRead: AbortController | null = null;
  private forgetConsent: AbortController | null = null;
  declare private excludesConflict: boolean;
  declare private excludesPolicy: ExcludesPolicy | null;
  private excludesWitness: SettingsWitness | null = null;
  private excludesSavedText: string | null = null;
  declare private excludesLiterals: ExactPathExclusion[];
  private excludesSavedLiterals: ExactPathExclusion[] = [];
  private get excludesDirty(): boolean {
    return this.excludesText !== this.excludesSavedText
      || JSON.stringify(this.excludesLiterals) !== JSON.stringify(this.excludesSavedLiterals);
  }
  private excludesAttempt: SettingsAttempt | null = null;
  private excludesLoadStarted = false;
  declare addBusy: boolean;
  declare removingHashes: ReadonlySet<string>;
  declare actionMessage: string | null;
  declare reindexOutcome: string | null;
  declare reindexOutcomeError: boolean;
  declare reindexOutcomeBusy: boolean;
  private bulkOutcomeKey: string | null = null;
  declare bulkOutcome: OperationOutcomeView | null;
  private reindexOutcomeKey: string | null = null;
  private reindexReadSerial = 0;
  private lastReindexReadAtMs = 0;
  private surfaceSerial = 0;
  private confirmingHashes = new Set<string>();
  private refreshSerial = 0;
  private rootsObserved = false;
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
  declare indexingActivity: ReturnType<typeof selectIndexingProgress>['activity'];
  declare retryWaiting: boolean;
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
  declare gapDecisionErrorCode: string | null;
  declare reindexCancelKey: string | null;
  declare reindexCancelBusy: boolean;
  declare reindexCancelError: string | null;
  declare reindexCancelErrorCode: string | null;
  declare reindexCancelRecorded: boolean;
  private observedGapKey: string | null = null;
  private gapRequestSerial = 0;
  private gapDecisionAttemptSerial = 0;
  private reindexCancelAttemptSerial = 0;
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
    this.excludesInvalidPatterns = [];
    this.excludesLoaded = false;
    this.excludesLiterals = [];
    this.unfinishedForgets = null;
    this.forgetReadStatus = 'loading';
    this.forgetDecisionKey = null;
    this.forgetMessage = '';
    this.excludesBusy = false;
    this.excludesError = null;
    this.excludesNotice = '';
    this.excludesResult = null;
    this.excludesPolicy = null;
    this.excludesConflict = false;
    this.addBusy = false;
    this.removingHashes = new Set();
    this.actionMessage = null;
    this.bulkOutcome = null;
    this.reindexOutcome = null;
    this.reindexOutcomeError = false;
    this.reindexOutcomeBusy = false;
    this.pendingPath = '';
    this.pendingCollection = '';
    this.pendingPreview = null;
    this.showManualInput = false;
    this.isTauri = false;
    this.activeTab = 'folders';
    this.provisional = false;
    this.indexingActivity = selectIndexingProgress(null, false, 0, []).activity;
    this.retryWaiting = false;
    this.enrichmentStages = null;
    this.enrichmentPending = false;
    this.enrichmentBlocked = false;
    this.otherSources = EMPTY_OTHER_SOURCES;
    this.gapDecision = null;
    this.gapDecisionBusy = false;
    this.gapDecisionError = null;
    this.gapDecisionErrorCode = null;
    this.reindexCancelKey = null;
    this.reindexCancelBusy = false;
    this.reindexCancelError = null;
    this.reindexCancelErrorCode = null;
    this.reindexCancelRecorded = false;
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
      flex-wrap: wrap;
    }
    @media (max-width: 640px) {
      .header, .excludes-header { flex-wrap: wrap; }
      .add-row input { min-inline-size: 0; }
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
    .empty {
      padding: .5rem 0;
      text-align: start;
      color: var(--text-secondary);
    }
    .action-message { margin-bottom: 1rem; overflow-wrap: anywhere; }
    .error-banner {
      overflow-wrap: anywhere;
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
      overflow-wrap: anywhere;
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
    .excludes-section pre { white-space: pre-wrap; }
    .excludes-section pre, .excludes-section li { overflow-wrap: anywhere; }
    .excludes-header > div { min-width: 0; }
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
    folderRowStyles,
    css`
      .folders-scroll { padding: 1.8rem 2.5rem; min-width: 0; }
      .header-left h2 { font-size: 26px; font-weight: 400; }
      .header { margin-bottom: 1.5rem; }
      .subtitle { overflow-wrap: anywhere; }
      .header-left .subtitle { font-size: 14px; }
      jf-declared-surface::part(heading) { font-size: 12px; font-weight: 500;
        text-transform: none; letter-spacing: normal; }
      .removal-history { margin-top: 1rem; font-size: 12px; color: var(--text-secondary); }
      .removal-history summary { cursor: pointer; }
      .window-actions { display: flex; flex-wrap: wrap; gap: .5rem; align-items: center; }
      .more-actions { position: relative; }
      .more-actions summary { cursor: pointer; list-style: none; padding: .4rem; color: var(--text-secondary); }
      .more-actions[open] > div { position: absolute; z-index: 2; right: 0; min-width: 15rem;
        padding: .5rem; background: var(--surface-2); border: 1px solid var(--border-subtle); }
      .browser-banner { padding: 0; border: 0; background: transparent; color: var(--text-secondary); font-size: 12px; }
      .excludes-section, .gap-decision { background: transparent; border: 0; border-radius: 0;
        border-top: 1px solid var(--border-subtle); padding: 1rem 0; }
      h3, .excludes-header h3 { color: var(--text-secondary); font-size: 12px; font-weight: 500; text-transform: none; letter-spacing: normal; }
      .attention { margin-bottom: 1.75rem; }
      .attention:empty, .action-message:empty { display: none; }
      .library-rules { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 2.5rem; margin-top: 1.5rem; }
      .library-rules > .excludes-section { margin: 0; padding: 0; border: 0; }
      .excludes-header { display: block; }
      .excludes-header .actions { margin: .5rem 0; }
      .rules-help { font-size: 12px; color: var(--text-secondary); margin: .5rem 0; }
      .rules-help summary { cursor: pointer; }
      .skip-rule { display: flex; align-items: center; border-bottom: 1px solid var(--border-subtle); }
      .skip-rule input { flex: 1; min-width: 0; padding: .5rem .2rem; font: inherit; font-size: 14px; font-family: monospace;
        border: 0; background: transparent; color: var(--text-primary); }
      .skip-rule input:focus-visible { outline: 2px solid var(--accent-tint); outline-offset: 2px; }
      .add-rule::part(control) { color: var(--accent-tint); }
      @media (max-width: 800px) { .library-rules { grid-template-columns: minmax(0, 1fr); gap: 1.5rem; } }
      @media (forced-colors: active) { .skip-rule { border-color: CanvasText; } .skip-rule input:focus-visible { outline-color: Highlight; } }
      .gap-decision h3 { font-size: 14px; font-weight: 400; color: var(--text-primary); }
      .attention-item { padding: .85rem 0; border-top: 1px solid var(--border-subtle); }
      .attention-title { display: flex; gap: .7rem; align-items: center; font-size: 14px; }
      .attention-title > span { color: var(--text-warning); }
      .attention-item p { margin: .35rem 0 .3rem 1.7rem; font-size: 13px; color: var(--text-secondary); overflow-wrap: anywhere; }
      .attention-actions { margin-left: 1.7rem; }
      .attention-actions jf-failed-jobs-drawer { margin-left: 0; }
      .bottom-links { display: flex; flex-wrap: wrap; gap: 1.5rem; margin-top: 1.5rem;
        border-top: 1px solid var(--border-subtle); padding-top: .75rem; font-size: 13px; color: var(--text-secondary); }
      .bottom-links details { min-width: 0; flex: 1 1 17rem; }
      .bottom-links summary { cursor: pointer; }
      .bottom-links .excludes-section { margin-top: .5rem; }
      .other-source-card { display: flex; gap: .75rem; align-items: center; padding: .65rem 0; border-bottom: 1px solid var(--border-subtle); }
      .other-source-card .card-info { flex: 1; }
      @media (max-width: 800px) { .folders-scroll { padding: 1.25rem; } }
      @media (max-width: 640px) { .folders-scroll { padding: 1rem; } }
      @media (forced-colors: active) { .attention-item, .bottom-links, .excludes-section { border-color: CanvasText; } }
    `,
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

  private indexingRuleUnsub: (() => void) | undefined;
  private indexingFocusSerial = 0;

  private requestIndexingFocus(control: IndexingRuleControl): void {
    const serial = ++this.indexingFocusSerial;
    // Coalesce requests in one turn, and never focus after leaving the Library.
    void Promise.resolve().then(() => {
      if (this.isConnected && serial === this.indexingFocusSerial) void this.focusIndexingRule(control);
    });
  }

  override connectedCallback(): void {
    super.connectedCallback();
    this.indexingRuleUnsub = subscribeIndexingRuleFocus(control => this.requestIndexingFocus(control));
    const ruleFocus = takeIndexingRuleFocus();
    if (ruleFocus) this.requestIndexingFocus(ruleFocus);
    void this.loadUnfinishedForgets(0);
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
    if (this.reindexOutcomeKey) void this.checkReindexOutcome();
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
        s.indexingStatusLive,
        s.episodeMaxPendingJobs,
        s.enrichSettleSamples,
      );
      this.indexingActivity = progress.activity;
      this.indexProgress = progress;
      this.retryWaiting = progress.activity.confirmed && progress.phase === 'waiting';
      this.enrichmentStages = progress.stages;
      // Round-15 F1b — same projection, same tick: whether the semantic stage can run at all, so a
      // row cannot render an unqualified "✓ … Verified just now" over an index with no vectors.
      this.enrichmentBlocked = progress.blockedPending > 0;
      // 809 finding 1 — same tick, same store: whether the enrichment backfill is still running, so a
      // row cannot claim the terminal "✓ indexed" while semantic search is still being built.
      this.enrichmentPending = progress.enrichingPending > 0;
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
      if (this.reindexOutcomeKey && Date.now() - this.lastReindexReadAtMs >= LIVE_REFRESH_MIN_MS) {
        void this.checkReindexOutcome();
      }
    });
  }

  /** Tempdoc 609 — settle transient state on hide: the in-flight `loading` flag + last `error` (a stale
   *  spinner / error would otherwise survive navigation). Folder/excludes drafts, the active tab, and the
   *  fetched roots are recoverable and KEPT. */
  protected override settleTransients(): void {
    this.excludesError = null;
    this.excludesNotice = '';
    this.excludesResult = null;
    this.loading = false;
    this.error = null;
    this.surfaceSerial++;
    this.refreshSerial++;
    this.refreshing = false;
    this.addBusy = false;
    this.removingHashes = new Set();
    this.confirmingHashes.clear();
    this.actionMessage = null;
    this.reindexOutcome = null;
    this.reindexOutcomeError = false;
    this.reindexReadSerial++;
    this.reindexOutcomeBusy = false;
    this.gapDecisionError = null;
    this.gapDecisionErrorCode = null;
    this.gapRequestSerial++;
    this.gapDecisionAttemptSerial++;
    this.gapLoading = false;
    this.gapDecisionBusy = false;
    this.reindexCancelAttemptSerial++;
    this.reindexCancelBusy = false;
    this.reindexCancelError = null;
    this.reindexCancelErrorCode = null;
  }

  override disconnectedCallback(): void {
    this.indexingRuleUnsub?.();
    this.indexingRuleUnsub = undefined;
    this.indexingFocusSerial++;
    this.shadowRoot?.querySelector<IndexingRulesEditor>('jf-indexing-rules-editor')?.cancelIndexingRuleFocus();
    this.leaveForgetPlacement();
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

  protected override updated(changed: Map<string, unknown>): void {
    super.updated(changed);
    if (changed.has('apiBase') && changed.get('apiBase') !== undefined
        || changed.has('activeTab') && changed.get('activeTab') !== undefined) {
      this.leaveForgetPlacement();
      if (this.activeTab === 'folders') void this.loadUnfinishedForgets(0);
    }
  }

  private leaveForgetPlacement(): void {
    this.forgetPlacement++;
    this.forgetRead?.abort();
    this.forgetConsent?.abort();
    this.forgetRead = null;
    this.forgetConsent = null;
    this.forgetMessage = '';
  }

  private async loadUnfinishedForgets(afterId: number): Promise<void> {
    this.forgetRead?.abort();
    const read = new AbortController();
    this.forgetRead = read;
    const base = this.apiBase;
    const placement = this.forgetPlacement;
    this.forgetReadStatus = 'loading';
    try {
      const response = await this.doFetch(`/api/indexing/forgets?afterId=${afterId}&limit=100`, { signal: read.signal });
      if (!response.ok) throw Object.assign(new Error('Unfinished forgets are unavailable'), { status: response.status });
      const page = parseForgetPage(await response.json(), afterId);
      if (!this.isConnected || read.signal.aborted || this.forgetPlacement !== placement || this.apiBase !== base) return;
      this.unfinishedForgets = page;
      this.forgetAfterId = afterId;
      this.forgetReadStatus = 'ready';
    } catch (error: unknown) {
      if (!this.isConnected || read.signal.aborted || this.forgetPlacement !== placement || this.apiBase !== base) return;
      this.forgetReadStatus = (error as { status?: number })?.status === 404 ? 'unsupported' : 'unavailable';
    }
  }

  private async decideUnfinishedForget(item: ForgetOutcome, decision: 'retry' | 'withdraw'): Promise<void> {
    const key = item.operationKey;
    if (!key || item.path === null || item.scope === null || this.forgetDecisionKey !== null
        || this.forgetReadStatus !== 'ready' || decision === 'retry' && item.reason === 'WITHDRAWAL_PENDING') return;
    const base = this.apiBase;
    const placement = this.forgetPlacement;
    const completionOwner = this.forgetCompletionOwner;
    const current = () => this.isConnected && this.apiBase === base && this.activeTab === 'folders'
      && this.forgetPlacement === placement;
    const consent = new AbortController();
    const focused = deepActiveElement();
    // jf-control replaces its disabled native button. Retain the enclosing jf-button, whose
    // focus() delegates to the current native control once the decision controls are enabled.
    const invoker = focused instanceof HTMLElement
      ? ((focused.getRootNode() as ShadowRoot).host?.getRootNode() as ShadowRoot)?.host : null;
    this.forgetConsent = consent;
    this.forgetDecisionKey = key;
    this.forgetMessage = 'Preparing the exact saved target…';
    try {
      const outcome = await decideForget(base, key, decision, async prompt =>
        current() && !consent.signal.aborted && await confirmForget(prompt, consent.signal) && current(),
        { path: item.path, scope: item.scope });
      // The owning Stage also retains detached Search v3 nodes. This notification does not paint
      // the departed Library, and carries the exact API owner captured before the request.
      if (decision === 'retry' && outcome !== null) publishForgetCompletion(this, base, outcome, completionOwner);
      if (!current()) return;
      this.forgetMessage = outcome === null ? 'Decision cancelled before approval. This decision changed nothing.'
        : `${describeExactPath(item.path)}: ${forgetObservationText(outcome)}`;
      if (outcome === null) {
        this.forgetDecisionKey = null;
        await this.updateComplete;
        await (invoker as HTMLElement & { updateComplete?: Promise<unknown> } | null)?.updateComplete;
        if (invoker instanceof HTMLElement && invoker.isConnected) invoker.focus();
      }
      if (outcome !== null) {
        await this.loadUnfinishedForgets(this.forgetAfterId);
        if (outcome.status === 'WITHDRAWN' && !this.excludesDirty) void this.loadExcludes();
      }
    } catch {
      if (current()) this.forgetMessage = 'The decision outcome could not be confirmed. Refresh this list before trying again.';
    } finally {
      if (this.forgetConsent === consent) this.forgetConsent = null;
      this.forgetDecisionKey = null;
    }
  }

  private renderUnfinishedForgets(): TemplateResult | typeof nothing {
    if (this.forgetReadStatus === 'unsupported') return nothing;
    const busy = this.forgetDecisionKey !== null;
    const ready = this.forgetReadStatus === 'ready';
    const page = this.unfinishedForgets;
    return html`<section aria-label="Unfinished forgets" data-testid="unfinished-forgets">
      <h3>Unfinished forgets</h3>
      <p>Review an unfinished removal even after its search result disappears. Withdrawal releases only that intent's own keep-out; prior removal may already have happened and no rows or original files are restored.</p>
      <div role="status" aria-live="polite">${this.forgetReadStatus === 'loading' ? 'Reading unfinished forgets…'
        : this.forgetReadStatus === 'unavailable' ? 'Unfinished forgets could not be read. Their outcomes are unknown.'
          : page?.items.length === 0 ? 'No unfinished forgets on this page.' : ''}</div>
      <ul>${(page?.items ?? []).map(item => html`<li>
        <div><code style="white-space:pre-wrap;overflow-wrap:anywhere">${item.path === null ? 'Exact target unavailable' : describeExactPath(item.path)}</code></div>
        <p>${forgetObservationText(item)}</p>
        <jf-button label=${`Retry forget for ${item.path === null ? 'unavailable target' : describeExactPath(item.path)}`} .disabled=${busy || !ready || item.path === null || item.scope === null || item.reason === 'WITHDRAWAL_PENDING'}
          .onActivate=${() => void this.decideUnfinishedForget(item, 'retry')}>Retry</jf-button>
        <jf-button label=${`${item.reason === 'WITHDRAWAL_PENDING' ? 'Retry withdrawal of' : 'Withdraw'} forget for ${item.path === null ? 'unavailable target' : describeExactPath(item.path)}`} .disabled=${busy || !ready || item.path === null || item.scope === null}
          .onActivate=${() => void this.decideUnfinishedForget(item, 'withdraw')}>${item.reason === 'WITHDRAWAL_PENDING' ? 'Retry withdrawal' : 'Withdraw'}</jf-button>
      </li>`)}</ul>
      <jf-button label="Refresh unfinished forgets" .disabled=${busy || this.forgetReadStatus === 'loading'}
        .onActivate=${() => void this.loadUnfinishedForgets(this.forgetAfterId)}>Refresh</jf-button>
      ${this.forgetAfterId > 0 ? html`<jf-button label="First page of unfinished forgets" .disabled=${busy || !ready}
        .onActivate=${() => void this.loadUnfinishedForgets(0)}>First page</jf-button>` : nothing}
      ${page?.complete === false && page.nextId !== null ? html`<jf-button label="Next unfinished forgets" .disabled=${busy || !ready}
        .onActivate=${() => void this.loadUnfinishedForgets(page.nextId!)}>Next unfinished forgets</jf-button>` : nothing}
      <div role="status" aria-live="polite" data-testid="forget-decision-result">${this.forgetMessage}</div>
    </section>`;
  }

  /**
   * 569 §14 — the indexed-folder cards. When the active presentation declares the cards region,
   * project them through the engine (<jf-declared-surface> + the folder-card renderer) at bespoke
   * parity, pre-resolving each card's display fields; otherwise the built-in Lit render (the
   * quarantine fallback, degrade-never-fail). The Remove INTENT the renderer emits is handled here
   * (the gated operation + confirm stay surface-owned).
   */
  private rowStatus(root: IndexedRootView): FolderStatus {
    return folderStatus(root, {
      relativeTime: this.host_.utilities.formatRelativeTime(root.lastIndexedIsoTime ?? ''),
      verifiedRelativeTime: this.host_.utilities.formatRelativeTime(root.lastVerifiedIsoTime ?? ''),
      provisional: this.provisional,
      enrichmentStages: this.enrichmentStages,
      enrichmentPending: this.enrichmentPending,
      enrichmentBlocked: this.enrichmentBlocked,
      enrichmentBlockedDetail: this.indexProgress.phase === 'blocked' ? this.indexingActivity.detail : null,
      retryWaiting: this.retryWaiting,
      indexingPaused: this.indexProgress.pause?.state === 'paused',
      indexingPauseReason: this.indexProgress.pause?.reason,
      indexingPauseReasons: this.indexProgress.pause?.reasons,
      lastKnownFailed: this.lastKnownFailed[root.pathHash ?? ''],
    });
  }

  private projectFolder(root: IndexedRootView): FolderRow {
    const pathHash = root.pathHash ?? '';
    const fullPath = this.resolvedPaths[pathHash];
    const name = folderRowLabel(pathHash, fullPath);
    const fs = this.rowStatus(root);
    const progress = rootCoverage(root, this.enrichmentStages);
    const unknown = !this.indexingActivity.confirmed || this.provisional || fs.state === 'unavailable';
    const statusText = fs.state === 'ready' ? 'Up to date'
      : fs.state === 'empty' ? 'No indexable files'
      : fs.state === 'enriching' ? `Meaning processing${progress?.percent != null ? ` · ${progress.percent}%` : ''}`
      : fs.state === 'failed' && !root.walkError ? 'Files need review'
      : fs.metaText.split(' · ').slice(1).join(' · ');
    return {
      expanded: this.expandedFolders.has(pathHash),
      pathHash, displayPath: name.label, fullPath, pathTitle: name.title, walkError: undefined,
      status: !this.indexingActivity.confirmed && fs.glyph === 'indexed' ? 'pending' : fs.glyph,
      statusText: !this.indexingActivity.confirmed && fs.state === 'ready' ? this.indexingActivity.summary
        : statusText + (this.retryWaiting && this.indexingActivity.detail ? ` · ${this.indexingActivity.detail}` : ''),
      metaText: this.removingHashes.has(pathHash) ? 'Removing folder…' : this.folderActivityText(fs.metaText),
      removalBusy: this.removingHashes.has(pathHash),
      failed: fs.failed, failedLastKnown: fs.failedIsLastKnown === true,
      filesFound: (root.fileCount ?? -1) >= 0
        ? `${unknown ? 'Last known ' : ''}${(root.fileCount ?? 0).toLocaleString()} files found` : 'Files found: unknown',
      collection: root.collection ?? 'default',
      wordsVerdict: fs.state === 'ready' || fs.state === 'enriching' || fs.state === 'keyword-only' || fs.state === 'unverified'
        ? `${this.indexingActivity.confirmed ? 'Now' : 'Last known'}${fs.state === 'unverified' ? ' · deletions unverified' : ''}`
        : 'Not yet confirmed; see status',
      processingPercent: progress?.percent,
      showProgress: fs.state === 'enriching',
      indexedAgo: root.lastIndexedIsoTime ? this.host_.utilities.formatRelativeTime(root.lastIndexedIsoTime) : '',
      verifiedAgo: root.lastVerifiedIsoTime ? this.host_.utilities.formatRelativeTime(root.lastVerifiedIsoTime) : '',
      canReveal: this.isTauri,
      revealUnavailable: !fullPath ? 'The folder path could not be resolved.'
        : fs.state === 'unavailable' ? 'Folder not found. Reconnect the drive or check the folder path.' : '',
      needsVerification: fs.state === 'unverified',
    };
  }

  private folderIntent(intent: FolderRowIntent, pathHash: string): void {
    if (intent === 'remove') void this.handleRemoveRoot(pathHash);
    else if (intent === 'show-failed') openFailedJobs(pathHash);
    else if (intent === 'reindex') void this.handleReindexToVerify();
    else void this.revealFolder(pathHash);
  }

  private async revealFolder(pathHash: string): Promise<void> {
    if (!this.isTauri) return;
    const root = this.roots.find(r => r.pathHash === pathHash);
    const path = this.resolvedPaths[pathHash];
    if (!root || !path || root.status === 'unavailable') {
      this.error = !root ? 'This folder was removed from the Library.'
        : !path ? 'The folder path could not be resolved.' : 'Folder not found. Reconnect the drive or check the folder path.';
      return;
    }
    const serial = this.surfaceSerial;
    try {
      await this.host_.platform.revealInExplorer(path);
    } catch (error: unknown) {
      if (serial === this.surfaceSerial) this.error = `Could not open the folder in Explorer. ${error instanceof Error ? error.message : String(error)}`;
    }
  }

  private renderCardsRegion(): TemplateResult {
    const body = activeBodyFor(LIBRARY_CARDS_REGION);
    const folders = this.roots.map(r => this.projectFolder(r));
    if (!body) return html`<h3>Folders</h3><div class="cards" role="list">${repeat(folders, f => f.pathHash, f => renderFolderRow(f, {
      expanded: this.expandedFolders.has(f.pathHash ?? ''), enabled: true,
      toggle: () => this.toggleFolder(f.pathHash ?? ''),
      invoke: intent => this.folderIntent(intent, f.pathHash ?? ''),
    }))}</div>`;
    return html`<jf-declared-surface .declaration=${body} .data=${{ folders }} .enabled=${true}
      @jf-folder-card-toggle=${(e: CustomEvent<{ pathHash: string }>) => this.toggleFolder(e.detail.pathHash)}
      @jf-folder-card-remove=${(e: CustomEvent<{ pathHash: string }>) => this.folderIntent('remove', e.detail.pathHash)}
      @jf-folder-card-show-failed=${(e: CustomEvent<{ pathHash: string }>) => this.folderIntent('show-failed', e.detail.pathHash)}
      @jf-folder-card-reveal=${(e: CustomEvent<{ pathHash: string }>) => this.folderIntent('reveal', e.detail.pathHash)}
      @jf-folder-card-reindex=${(e: CustomEvent<{ pathHash: string }>) => this.folderIntent('reindex', e.detail.pathHash)}
    ></jf-declared-surface>`;
  }

  private toggleFolder(hash: string): void {
    if (this.expandedFolders.has(hash)) this.expandedFolders.delete(hash); else this.expandedFolders.add(hash);
    this.requestUpdate();
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
  private async refresh(opts: { live?: boolean; force?: boolean } = {}): Promise<boolean> {
    const live = opts.live === true;
    if (live && !opts.force) {
      if (this.refreshing) return false;
      const sinceLast = Date.now() - this.lastLiveRefreshAtMs;
      if (sinceLast < LIVE_REFRESH_MIN_MS) return false;
      this.lastLiveRefreshAtMs = Date.now();
    }
    const serial = ++this.refreshSerial;
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
      if (serial !== this.refreshSerial) return false;
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
      this.rootsObserved = true;
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
      return true;
    } catch (err) {
      if (serial !== this.refreshSerial) return false;
      // Live (background) failures stay silent — don't clobber the surface with a tick error.
      if (!live) {
        this.error = err instanceof Error ? err.message : String(err);
      }
      return false;
    } finally {
      if (serial === this.refreshSerial) {
        this.loading = false;
        this.refreshing = false;
      }
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
    const key = ['MIGRATING', 'SWITCHING', 'AWAITING_ACCEPTANCE'].includes(migration?.migrationState ?? '')
      && /^g-[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(building)
      ? building.slice(2) : null;
    if (key && key !== this.bulkOutcomeKey) {
      this.bulkOutcomeKey = key;
      this.bulkOutcome = null;
      this.gapRequestSerial++;
      this.gapLoading = false;
      this.lastGapReadAtMs = 0;
    }
    if (key !== this.reindexCancelKey) {
      this.reindexCancelKey = key;
      this.reindexCancelBusy = false;
      this.reindexCancelError = null;
      this.reindexCancelErrorCode = null;
      this.reindexCancelRecorded = false;
      this.reindexCancelAttemptSerial++;
    }
    const gapKey = migration?.migrationState === 'AWAITING_ACCEPTANCE' ? key : null;
    if (gapKey !== this.observedGapKey) {
      this.observedGapKey = gapKey;
      this.gapDecision = null;
      this.gapDecisionError = null;
      this.gapDecisionErrorCode = null;
      this.gapRequestSerial++;
      this.gapLoading = false;
      this.lastGapReadAtMs = 0;
    }
    if (this.bulkOutcomeKey && !this.gapLoading
        && !['complete', 'failed', 'unknown', 'expired'].includes(this.bulkOutcome?.state ?? '')
        && Date.now() - this.lastGapReadAtMs >= 4_000) {
      void this.loadGapDecision(this.bulkOutcomeKey);
    }
  }

  private async loadGapDecision(key: string): Promise<void> {
    this.gapLoading = true;
    this.lastGapReadAtMs = Date.now();
    const serial = ++this.gapRequestSerial;
    try {
      const response = await this.doFetch(`/api/operation-history/${encodeURIComponent(key)}`);
      if (!response.ok) throw new Error(`Could not read migration gaps (HTTP ${response.status})`);
      const outcome: OperationOutcomeView = parseWireContract(operationOutcomeViewSchema,
        await response.json(), '/api/operation-history/{operationKey}');
      if (serial !== this.gapRequestSerial || key !== this.bulkOutcomeKey) return;
      this.bulkOutcome = outcome;
      if (key !== this.observedGapKey || outcome.phase !== 'awaiting_acceptance') {
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
      this.gapDecisionErrorCode = null;
    } catch (failure) {
      if (serial === this.gapRequestSerial && key === this.observedGapKey) {
        this.gapDecisionError = failure instanceof Error ? failure.message : String(failure);
        this.gapDecisionErrorCode = failure instanceof OperationError ? failure.errorCode ?? null : null;
      }
    } finally {
      if (serial === this.gapRequestSerial) this.gapLoading = false;
    }
  }

  private async acceptCurrentGaps(): Promise<void> {
    const decision = this.gapDecision;
    if (!decision || this.gapDecisionBusy || this.reindexCancelBusy) return;
    const attemptSerial = ++this.gapDecisionAttemptSerial;
    this.gapDecisionBusy = true;
    this.gapDecisionError = null;
    this.gapDecisionErrorCode = null;
    try {
      const result = await this.host_.data.invokeOperation('core.accept-gaps', {
        reindexKey: decision.reindexKey, gapListHash: decision.gapListHash,
      }, { consented: true });
      if (!result.success) throw new OperationError(result.message ?? 'Migration gap acceptance failed',
        'HANDLER_FAILURE', undefined, result.errorCode);
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
        this.gapDecisionErrorCode = failure instanceof OperationError ? failure.errorCode ?? null : null;
      }
    } finally {
      if (attemptSerial === this.gapDecisionAttemptSerial) this.gapDecisionBusy = false;
    }
  }

  private async cancelCurrentReindex(): Promise<void> {
    const key = this.reindexCancelKey;
    if (!key || this.reindexCancelBusy || this.reindexCancelRecorded || this.gapDecisionBusy) return;
    const attemptSerial = ++this.reindexCancelAttemptSerial;
    const confirmed = await this.host_.ui.showConfirmDialog(
      'Cancel this rebuild? Your current index stays available while the unfinished index is removed.',
      { confirmLabel: 'Cancel rebuild', destructive: true },
    );
    if (!confirmed || !this.isConnected || key !== this.reindexCancelKey
        || attemptSerial !== this.reindexCancelAttemptSerial) return;
    this.reindexCancelBusy = true;
    this.reindexCancelError = null;
    this.reindexCancelErrorCode = null;
    try {
      const result = await this.host_.data.invokeOperation('core.cancel-reindex',
        { reindexKey: key }, { consented: true });
      if (!result.success) throw new OperationError(result.message ?? 'Rebuild cancellation failed',
        'HANDLER_FAILURE', undefined, result.errorCode);
      if (this.isConnected && key === this.reindexCancelKey
          && attemptSerial === this.reindexCancelAttemptSerial) {
        this.reindexCancelRecorded = true;
        await this.loadGapDecision(key);
      }
    } catch (failure) {
      if (this.isConnected && key === this.reindexCancelKey
          && attemptSerial === this.reindexCancelAttemptSerial) {
        this.reindexCancelError = failure instanceof Error ? failure.message : String(failure);
        this.reindexCancelErrorCode = failure instanceof OperationError ? failure.errorCode ?? null : null;
      }
    } finally {
      if (attemptSerial === this.reindexCancelAttemptSerial) this.reindexCancelBusy = false;
    }
  }

  private async loadExcludesPolicy(): Promise<void> {
    this.excludesPolicy = null;
    try {
      const response = await this.doFetch('/api/indexing/excludes', { signal: AbortSignal.timeout(10_000) });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      this.excludesPolicy = excludesPolicySchema.parse(await response.json());
    } catch {
      // Keep effects unavailable until a later observation succeeds.
    }
  }

  private async loadExcludes(keepDraft = false): Promise<void> {
    if (this.excludesBusy || this.excludesAttempt) return;
    this.excludesLoadStarted = true;
    this.excludesBusy = true;
    try {
      const body = await readSettingsObservation((path, init) => this.doFetch(path, init));
      const entries = body.ui?.excludePatterns ?? [];
      this.excludesSavedText = entries.filter((entry) => typeof entry === 'string').join('\n');
      this.excludesSavedLiterals = entries.filter((entry): entry is ExactPathExclusion => typeof entry !== 'string');
      if (!keepDraft) this.excludesLiterals = [...this.excludesSavedLiterals];
      if (!keepDraft) this.excludesText = this.excludesSavedText;
      this.excludesConflict = false;
      this.excludesError = null;
      this.excludesResult = null;
      this.excludesNotice = keepDraft ? 'Latest saved patterns loaded below. Review them before saving your retained draft.' : '';
      this.excludesInvalidPatterns = body.ui?.invalidExcludePatterns ?? [];
      this.excludesWitness = body.witness;
      this.excludesLoaded = true;
      await this.loadExcludesPolicy();
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
    if (!this.excludesAttempt && !this.excludesDirty) return true;
    try {
      if (!this.excludesAttempt) {
        const patterns = this.excludesText.split('\n').map((value) => value.trim()).filter(Boolean);
        this.excludesAttempt = createSettingsAttempt({ ui: { excludePatterns: [...patterns, ...this.excludesLiterals] } }, this.excludesWitness);
      }
      const result = await executeSettingsAttempt((path, init) => this.doFetch(path, init), this.excludesAttempt);
      this.excludesWitness = result.witness;
      this.excludesAttempt = null;
      this.excludesSavedText = this.excludesText;
      this.excludesSavedLiterals = [...this.excludesLiterals];
      this.excludesInvalidPatterns = [];
      await this.loadExcludesPolicy();
      this.excludesNotice = isOverride(this.excludesPolicy)
        ? 'Patterns saved. The saved list has no effect while the override is set; it takes effect after the override is removed. Existing search results have not been removed.'
        : this.excludesPolicy ? 'Patterns saved. Future indexing skips these matches. Existing search results have not been removed.'
          : 'Patterns saved. Effective policy is unavailable; reload before Preview or Apply.';
      return true;
    } catch (err) {
      // An uncertain save retains its exact key and bytes until Save checks the receipt.
      const state = err instanceof SettingsAttemptError ? err.response?.['state'] : undefined;
      const invalid = err instanceof SettingsAttemptError && ['INVALID_REQUEST', 'INVALID_PATH'].includes(err.code);
      const terminal = state === 'FAILED' || state === 'CANCELLED'
        || (err instanceof SettingsAttemptError && ['VERSION_CONFLICT', 'SETTINGS_READ_ONLY', 'INVALID_REQUEST', 'INVALID_PATH',
          'OPERATION_KEY_INVALID', 'OPERATION_KEY_REUSED', 'OPERATION_KEY_EXPIRED'].includes(err.code));
      if (terminal) {
        this.excludesAttempt = null;
        if (!invalid) {
          this.excludesLoaded = false;
          this.excludesWitness = null;
        }
      }
      this.excludesConflict = err instanceof SettingsAttemptError && err.code === 'VERSION_CONFLICT';
      this.excludesError = err instanceof Error ? err.message : String(err);
      return false;
    }
  }

  /**
   * The HEADER's "Add Folder" — open the add flow. Distinct from {@link handleAddRoot}, which is the
   * form's submit (tempdoc 914 D4): with one method for both, the header button submitted whenever
   * the form happened to be open, and in Tauri mode it would then never re-open the picker.
   *
   * The native picker answers WHICH folder, not which collection, so it now fills the same add form
   * the browser flow uses (path pre-filled, preview fetched) and the user confirms with Add — that is
   * where the collection field lives, in both modes. Cancelling the picker leaves the form unchanged.
   */
  private async handleAddFolderClick(): Promise<void> {
    if (this.host_.platform.capabilities.has('folder-picker')) {
      const picked = await this.host_.platform.pickFolder();
      if (picked) {
        this.showManualInput = true;
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
    const serial = this.surfaceSerial;
    this.addBusy = true;
    this.error = null;
    this.actionMessage = 'Adding folder…';
    try {
      const result = await this.host_.data.invokeOperation(OP_ADD, this.addRootArgs(path));
      if (serial !== this.surfaceSerial) return;
      if (!result.success) throw new Error(result.message || 'Add refused');
      this.pendingPath = '';
      this.pendingCollection = '';
      this.pendingPreview = null;
      this.showManualInput = false;
      this.actionMessage = 'Folder added. Indexing activity is shown below.';
      await this.refresh({ live: true, force: true });
      if (serial === this.surfaceSerial) {
        this.addBusy = false;
        await this.updateComplete;
        const addButton = this.renderRoot.querySelector<JfButton>('[data-add-folder]');
        await addButton?.updateComplete;
        if (serial === this.surfaceSerial) addButton?.focus();
      }
    } catch (err) {
      if (serial !== this.surfaceSerial) return;
      this.actionMessage = null;
      this.addBusy = false;
      this.error = `Folder was not added. ${err instanceof Error ? err.message : String(err)} Correct the path or retry Add.`;
      await this.updateComplete;
      this.renderRoot.querySelector<HTMLInputElement>('[aria-label="Folder path"]')?.focus();
    } finally {
      if (serial === this.surfaceSerial) this.addBusy = false;
    }
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
        ...(typeof body?.overlap?.path === 'string' && body.overlap.path.trim()
          && (body.overlap.relationship === 'inside' || body.overlap.relationship === 'containing')
          ? { overlap: { path: body.overlap.path, relationship: body.overlap.relationship } } : {}),
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
    if (this.addBusy) return unavailableBecause('Adding folder…', true);
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
    const overlap = overlapExplanation(p);
    if (overlap) return unavailableBecause(overlap);
    return AVAILABLE;
  }

  private async handleRemoveRoot(pathHash: string): Promise<void> {
    if (this.removingHashes.has(pathHash) || this.confirmingHashes.has(pathHash)) return;
    const resolved = this.resolvedPaths[pathHash];
    if (!resolved) {
      this.error = 'Path resolution pending; retry in a moment.';
      return;
    }
    const serial = this.surfaceSerial;
    this.confirmingHashes.add(pathHash);
    let affected = [pathHash];
    let acquired = false;
    try {
      // Confirm every legacy registration affected by subtree removal. The wire remains hash-only.
      await Promise.all(this.roots.map(async (root) => {
        const hash = root.pathHash;
        if (!hash || this.resolvedPaths[hash]) return;
        const path = await resolvePathLazy(RESOURCE_ID, hash, { apiBase: this.apiBase });
        if (path && serial === this.surfaceSerial) this.resolvedPaths = { ...this.resolvedPaths, [hash]: path };
      }));
      if (serial !== this.surfaceSerial) return;
      if (this.roots.some(root => root.pathHash && !this.resolvedPaths[root.pathHash])) {
        this.error = 'Could not check which watched folders this removal affects. Retry when their paths are available.';
        return;
      }
      const paths = this.roots.map(root => ({ hash: root.pathHash ?? '', path: this.resolvedPaths[root.pathHash ?? ''] ?? '' }));
      const enclosing = paths.find(other => other.hash !== pathHash && this.pathInside(resolved, other.path));
      const nested = paths.filter(other => other.hash !== pathHash && this.pathInside(other.path, resolved));
      const nestedHashes = nested.map(other => other.hash);
      if (nestedHashes.some(hash => this.removingHashes.has(hash))) {
        this.error = 'A folder inside this one is being removed. Wait for its outcome, then retry.';
        return;
      }
      const consequence = enclosing
        ? `Its files stay searchable because the enclosing watched folder ${enclosing.path} covers them. Only the selected registrations and their own watchers are removed.`
        : 'Files indexed from this folder will be removed from search results, and any enrichment still running for it is stopped and discarded.';
      const inner = nested.length ? ` This also removes these watched folders: ${nested.map(other => other.path).join(', ')}.` : '';
      const ok = await this.host_.ui.showConfirmDialog(`Remove ${resolved}? ${consequence}${inner}`,
        { confirmLabel: 'Remove', destructive: true });
      if (!ok || serial !== this.surfaceSerial) return;
      if ([pathHash, ...nestedHashes].some(hash => this.removingHashes.has(hash))) return;
      affected = [pathHash, ...nestedHashes];
      this.removingHashes = new Set([...this.removingHashes, ...affected]);
      acquired = true;
      this.error = null;
      this.actionMessage = `Removing folder ${resolved}…`;
      let removed = false;
      try {
        const result = await this.host_.data.invokeOperation(OP_REMOVE, { path: resolved });
        if (serial !== this.surfaceSerial) return;
        if (!result.success) throw new Error(result.message || 'Remove refused');
        removed = true;
      } catch (err) {
        if (serial !== this.surfaceSerial) return;
        const errorClass = (err as { errorClass?: string } | null)?.errorClass;
        // Any response can be lost after effects completed, including a server-side error.
        // Reconcile membership before labelling the removal a failure.
        const observed = await this.refresh({ live: true, force: true });
        if (serial !== this.surfaceSerial) return;
        removed = observed && !this.roots.some(root => root.pathHash === pathHash);
        if (!removed) {
          this.error = errorClass === 'NETWORK_ERROR' || errorClass === 'SERIALIZATION_ERROR'
            ? 'Removal outcome is unconfirmed. Return to Library to check current folders, or retry Remove.'
            : `Removal did not complete. ${err instanceof Error ? err.message : String(err)} You can retry Remove. Live watching is unconfirmed.`;
        }
      }
      if (serial !== this.surfaceSerial) return;
      if (removed) {
        // The synchronous result proves removal; invalidate older list reads before publishing it.
        this.refreshSerial++;
        this.roots = this.roots.filter(root => !affected.includes(root.pathHash ?? ''));
        this.actionMessage = enclosing ? 'Folder removed. Its files stay searchable through the enclosing watched folder.' : 'Folder removed from Library and search results.';
        await this.refresh({ live: true, force: true });
        if (serial === this.surfaceSerial) {
          await this.updateComplete;
          this.renderRoot.querySelector<HTMLElement>('[data-add-folder]')?.focus();
        }
      } else {
        this.actionMessage = null;
      }
    } catch (err) {
      if (serial === this.surfaceSerial) {
        this.actionMessage = null;
        this.error = `Removal did not complete. ${err instanceof Error ? err.message : String(err)} You can retry Remove.`;
      }
    } finally {
      if (serial === this.surfaceSerial) {
        this.confirmingHashes.delete(pathHash);
        if (acquired) this.removingHashes = new Set([...this.removingHashes].filter(hash => !affected.includes(hash)));
        if (this.error) {
          await this.updateComplete;
          this.renderRoot.querySelector<HTMLElement>('.error-banner')?.focus();
        }
      }
    }
  }

  private pathInside(child: string, parent: string): boolean {
    // These are Engine-resolved paths; Windows paths compare case-insensitively, on segment edges.
    const normalize = (path: string) => {
      const slash = path.replaceAll('\\', '/');
      return (/^[a-z]:/i.test(slash) || slash.startsWith('//') ? slash.toLowerCase() : slash).replace(/\/+$/, '');
    };
    return !!parent && normalize(child).startsWith(normalize(parent) + '/');
  }

  private async reindexAccepted(detail: { structuredData?: unknown }): Promise<void> {
    if (!this.isConnected) return;
    this.error = null;
    const data = detail.structuredData;
    const key = data && typeof data === 'object' && 'operationKey' in data ? data.operationKey : undefined;
    this.reindexReadSerial++;
    this.reindexOutcomeBusy = false;
    this.reindexOutcomeKey = typeof key === 'string' && /^[0-9a-f-]{36}$/.test(key) ? key : null;
    this.reindexOutcomeError = false;
    this.reindexOutcome = 'Reindex accepted. Completion has not yet been confirmed.';
    if (this.reindexOutcomeKey) await this.checkReindexOutcome();
  }

  private async checkReindexOutcome(): Promise<void> {
    const key = this.reindexOutcomeKey;
    if (!key || this.reindexOutcomeBusy) return;
    const serial = ++this.reindexReadSerial;
    this.reindexOutcomeBusy = true;
    this.lastReindexReadAtMs = Date.now();
    try {
      const response = await this.doFetch(`/api/operation-history/${encodeURIComponent(key)}`);
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const outcome = parseWireContract(operationOutcomeViewSchema, await response.json(), 'reindex outcome');
      if (serial !== this.reindexReadSerial) return;
      this.reindexOutcomeError = outcome.state === 'failed';
      if (outcome.state === 'complete') {
        this.reindexOutcome = 'Reindex completed for the recorded request. Current indexing activity is shown below.';
        this.reindexOutcomeKey = null;
      } else if (outcome.state === 'failed') {
        this.reindexOutcome = `Reindex did not complete${outcome.result?.code ? ` (${outcome.result.code})` : ''}. Check folder availability and retry Reindex; failed files can also be retried below.`;
        this.reindexOutcomeKey = null;
      } else {
        this.reindexOutcome = `Reindex accepted. ${['accepted', 'running'].includes(outcome.state) ? 'Completion has not yet been confirmed.' : 'Its outcome is unavailable; check again before retrying.'}`;
      }
    } catch {
      if (serial === this.reindexReadSerial) this.reindexOutcome = 'Reindex accepted. Its outcome could not be read; check again before retrying.';
    } finally {
      if (serial === this.reindexReadSerial) this.reindexOutcomeBusy = false;
    }
  }

  /**
   * Tempdoc 626 §Axis-C — the one-click recovery for an "unverified" folder (its reconcile couldn't
   * verify deletions). {@code core.reindex} is incremental-all-roots; {@code force:true} bypasses the
   * unchanged fast-path so deletions are re-checked and the per-root unverified state clears once the
   * reconcile verifies it. The accepted operation receipt separately reports its completion.
   */
  private async handleReindexToVerify(): Promise<void> {
    const serial = this.surfaceSerial;
    try {
      this.error = null;
      const result = await this.host_.data.invokeOperation('core.reindex', { force: true });
      if (serial !== this.surfaceSerial) return;
      if (!result.success) throw new Error(result.message || 'Reindex refused');
      await this.reindexAccepted(result);
    } catch (err) {
      if (serial !== this.surfaceSerial) return;
      this.error = `Reindex was not confirmed. ${err instanceof Error ? err.message : String(err)} You can retry Reindex.`;
    }
  }

  private async handleSaveExcludes(): Promise<void> {
    if (this.excludesBusy || !this.excludesLoaded) return;
    this.excludesBusy = true;
    this.excludesError = null;
    this.excludesNotice = '';
    this.excludesResult = null;
    try { await this.persistExcludes(); }
    finally { this.excludesBusy = false; }
  }

  private async handlePreviewExcludes(): Promise<void> {
    await this.invokeExcludes(false);
  }

  private async handleApplyExcludes(): Promise<void> {
    await this.invokeExcludes(true);
  }

  private async invokeExcludes(apply: boolean): Promise<void> {
    if (this.excludesBusy || !this.excludesLoaded || !this.excludesPolicy || this.excludesAttempt
        || this.excludesDirty) return;
    this.excludesBusy = true;
    this.excludesError = null;
    this.excludesNotice = '';
    this.excludesResult = null;
    try {
      if (apply && !await this.host_.ui.showConfirmDialog(
        `${isOverride(this.excludesPolicy) ? `Apply the effective patterns from ${policySource(this.excludesPolicy)}:\n${this.excludesPolicy.patterns.join('\n') || '(none)'}\n` : 'Apply saved patterns '}to the watched folders on disk? Matching files and folders will be removed from search. Offline, missing or unreadable scope may remain searchable and will be listed in the result.`,
        { confirmLabel: 'Apply', destructive: true, typedConfirmWord: OP_APPLY })) {
        this.excludesNotice = 'Apply cancelled. Nothing was removed.';
        return;
      }
      const result = apply
        ? await this.host_.data.invokeOperation(OP_APPLY, {}, { consented: true })
        : await this.host_.data.invokeOperation(OP_PREVIEW, {});
      if (!result.success) throw new Error(result.message ?? 'Exclusion operation failed');
      const parsed = excludesResultSchema.safeParse(result.structuredData);
      if (!parsed.success || parsed.data.dryRun === apply
          || (isOverride(this.excludesPolicy) && !parsed.data.effectivePolicy)) {
        this.excludesNotice = `${apply ? 'Apply' : 'Preview'} outcome is unconfirmed. Check again before relying on this result.`;
      } else {
        this.excludesResult = parsed.data;
      }
      if (apply) await this.refresh();
    } catch (err) {
      this.excludesError = `${apply ? 'Apply outcome is unconfirmed. Some matches may already have been removed. Retry to reconcile. ' : ''}${err instanceof Error ? err.message : String(err)}`;
    } finally {
      this.excludesBusy = false;
    }
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
  private folderActivityText(folderText: string): string {
    const activity = this.indexingActivity;
    if (!activity.confirmed || activity.detail) {
      // The global reason is explicitly scoped to the index; per-folder failures and verification
      // caveats remain owned by folderStatus, even when its observation is only last known.
      const scope = activity.confirmed ? 'Index: ' : 'Index (folder observation last known): ';
      return `${folderText} · ${scope}${activity.summary}${activity.detail ? ` — ${activity.detail}` : ''}`;
    }
    return folderText;
  }

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
      <div class="other-source-card">
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

  private attentionItems(): { key: string; title: string; detail?: string; actions?: TemplateResult }[] {
    const items: { key: string; title: string; detail?: string; actions?: TemplateResult }[] = [];
    for (const root of this.roots) {
      const hash = root.pathHash ?? '';
      const name = folderRowLabel(hash, this.resolvedPaths[hash]).label;
      const status = this.rowStatus(root);
      if (status.state === 'unavailable') items.push({ key: `missing:${hash}`, title: `${name} can't be found`,
        detail: 'Reconnect the drive or remove it. Last-known indexed content may still be searchable; reindex cannot cover this folder while it is offline.',
        actions: html`<jf-operation context="button" operation-id="core.reconcile-root" .args=${{ pathHash: hash }} api-base=${this.apiBase}
          @op-success=${() => void this.refresh({ force: true })}></jf-operation>
          <jf-button variant="ghost" label="Remove from library" .disabled=${this.removingHashes.has(hash)}
            .onActivate=${() => this.handleRemoveRoot(hash)}>Remove from library</jf-button>` });
      else if (root.walkError) items.push({ key: `walk:${hash}`, title: `${name} needs attention`, detail: root.walkError,
        actions: html`<jf-button variant="ghost" .disabled=${this.removingHashes.has(hash)}
          .onActivate=${() => root.walkError?.startsWith('Removal incomplete.') ? this.handleRemoveRoot(hash) : this.handleReindexToVerify()}
          >${root.walkError.startsWith('Removal incomplete.') ? 'Retry removal' : 'Reindex'}</jf-button>` });
      if (status.failed > 0) items.push({ key: `files:${hash}`, title: `${status.failed} ${status.failed === 1 ? 'file needs' : 'files need'} review · ${name}${status.failedIsLastKnown ? ' · last known' : ''}`,
        detail: 'Review the named files and their reasons, including unreadable sources, exhausted retries and extraction failures.',
        actions: html`<jf-failed-jobs-drawer inline .folderPathHash=${hash} .observedCount=${status.failed}
          .lastKnown=${status.failedIsLastKnown === true} .host_=${this.host_} api-base=${this.apiBase}></jf-failed-jobs-drawer>
          <jf-button variant="ghost" .onActivate=${() => openFailedJobs(hash)}>Open failed-files list</jf-button>` });
      if (status.state === 'unverified') items.push({ key: `deletions:${hash}`, title: `${name}: deletions not verified`,
        detail: 'Some stale entries may remain searchable.', actions: html`<jf-button variant="ghost" label="Reindex to verify"
          .disabled=${this.removingHashes.has(hash)} .onActivate=${() => this.handleReindexToVerify()}>Reindex to verify</jf-button>` });
    }
    if (this.indexProgress.pause) items.push({ key: 'hold', title: this.indexingActivity.summary,
      detail: this.indexingActivity.detail ?? undefined,
      actions: html`<jf-indexing-controls compact .progress=${this.indexProgress} api-base=${this.apiBase}></jf-indexing-controls>` });
    if (this.reindexCancelKey && !['complete', 'failed', 'unknown', 'expired'].includes(this.bulkOutcome?.state ?? '')) items.push({ key: 'rebuild', title: 'Rebuild in progress' });
    for (const item of this.unfinishedForgets?.items ?? []) items.push({ key: `forget:${item.operationKey}`, title: 'Unfinished removal' });
    if (this.bulkOutcome && ['failed', 'unknown', 'expired'].includes(this.bulkOutcome.state)) items.push({ key: 'bulk', title: 'Rebuild outcome needs checking',
      detail: this.bulkOutcome.message ?? undefined, actions: html`<jf-button variant="ghost" .onActivate=${() => this.bulkOutcomeKey ? this.loadGapDecision(this.bulkOutcomeKey) : undefined}>Check outcome</jf-button>` });
    return items;
  }

  private renderAttention(): TemplateResult {
    return html`<div class="attention">
      ${repeat(this.attentionItems().filter(item => item.key !== 'rebuild' && !item.key.startsWith('forget:')), item => item.key, item => html`
        <section class="attention-item" aria-label=${item.title}>
          <div class="attention-title"><span>${icon({ name: 'alert-triangle', size: 16 })}</span>${item.title}</div>
          ${item.detail ? html`<p role=${item.key === 'bulk' ? 'alert' : nothing}>${item.detail}</p>` : nothing}
          <div class="attention-actions">${item.actions ?? nothing}</div>
        </section>`)}
      ${this.renderReindexDecision()}
      ${(this.unfinishedForgets?.items.length ?? 0) > 0 ? this.renderUnfinishedForgets() : nothing}
    </div>`;
  }

  private get activitySentence(): string {
    const activity = this.indexingActivity;
    if (!activity.confirmed) return '';
    // The detail already names this wait and its cause; announce it once.
    if (activity.summary === 'Meaning processing is waiting' && activity.detail) return activity.detail;
    return [activity.summary, activity.detail].filter(Boolean).join(' — ');
  }

  async focusIndexingRule(control: 'hours' | 'battery'): Promise<void> {
    const serial = ++this.indexingFocusSerial;
    this.activeTab = 'folders';
    await this.updateComplete;
    if (!this.isConnected || serial !== this.indexingFocusSerial) return;
    const editor = this.shadowRoot?.querySelector<IndexingRulesEditor>('jf-indexing-rules-editor');
    await editor?.focusIndexingRule(control);
  }

  private renderHeader(): TemplateResult {
    const count = this.attentionItems().length;
    const headline = this.loading ? 'Loading your Library…'
      : !this.indexingActivity.confirmed ? this.indexingActivity.summary
        : count ? `${count} ${count === 1 ? 'thing needs' : 'things need'} your attention`
          : this.provisional ? 'Rebuilding your Library'
            : !this.roots.length ? 'Add a folder to start searching'
              : !this.defaultScopeDocuments.known ? 'Checking your folders'
                : this.defaultScopeDocuments.value === 0 ? 'Indexing your files' : 'Your files are searchable';
    return html`<div class="header"><div class="header-left"><h2>${headline}</h2>
      <p class="subtitle">${this.defaultScopeDocuments.known ? `${this.defaultScopeDocuments.value.toLocaleString()} searchable documents` : 'Searchable document count unconfirmed'}
        · ${this.roots.length} ${this.roots.length === 1 ? 'folder' : 'folders'}${this.activitySentence ? ` · ${this.activitySentence}` : ''}</p>
    </div></div>`;
  }

  private renderWindowActions(): TemplateResult {
    return html`        <div slot="actions" class="window-actions">
          <jf-indexing-controls data-testid="library-indexing-pause" compact toolbar allow-pause .progress=${this.indexProgress} api-base=${this.apiBase}></jf-indexing-controls>
          <details class="more-actions"><summary aria-label="More Library actions">${icon({ name: 'more-horizontal', size: 18 })}</summary><div>
          <!-- Tempdoc 511 Phase 9: <jf-operation> aggregate component
               supersedes the slice 509 <jf-op-button>. LibrarySurface
               is user-tier; core.reindex's wire audience=USER passes
               the gate. -->
          ${this.rootsObserved && this.roots.length === 0 && !this.provisional
            ? html`<span>No watched folders to reindex.</span>`
            : html`<jf-operation context="button" operation-id="core.reindex" api-base=${this.apiBase}
                @op-success=${(e: CustomEvent<OpSuccessEventDetail>) => void this.reindexAccepted(e.detail)}
                @op-error=${(e: CustomEvent<OpErrorEventDetail>) => { this.error = `Reindex was not confirmed. ${e.detail.message} You can retry Reindex.`; }}
              ></jf-operation>`}
          <!-- Tempdoc 813 §6: the manual "Process pending enrichment" trigger — drains the pending
               VDU + embedding work for already-indexed documents, same catalog-driven pattern as
               core.reindex above. Its LABEL comes from the operation catalog (one string, both
               render sites); the operation id keeps its historical name. -->
          <jf-operation
            context="button"
            operation-id="core.trigger-offline-processing"
            api-base=${this.apiBase}
          ></jf-operation>
          </div></details>
          <jf-button
            variant="primary"
            data-add-folder
            label="Add folder"
            .disabled=${this.addBusy}
            .onActivate=${() => this.handleAddFolderClick()}
          >
            ${icon({ name: 'folder-plus', size: 14 })} Add folder
          </jf-button>
        </div>`;
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
        ${this.renderWindowActions()}
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
    const overlap = overlapExplanation(p);
    if (overlap) {
      return html`<div class="add-preview warn" role="status">${icon({ name: 'alert-circle', size: 14 })} ${overlap}</div>`;
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
      ${this.renderAttention()}
      <div class="action-message" role="status" aria-live="polite">${this.actionMessage ?? ''}</div>
      ${this.reindexOutcome ? html`<div class="action-message" role=${this.reindexOutcomeError ? 'alert' : 'status'}>
        ${this.reindexOutcome}
        ${this.reindexOutcomeKey ? html`<jf-button label="Check reindex outcome" .disabled=${this.reindexOutcomeBusy}
          .onActivate=${() => this.checkReindexOutcome()}>Check reindex outcome</jf-button>` : nothing}
        <p>${this.indexingActivity.summary}${this.indexingActivity.detail ? ` — ${this.indexingActivity.detail}` : ''}</p>
      </div>` : nothing}


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
        ? html`<div class="error-banner" role="alert" tabindex="-1">
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
                ?disabled=${this.addBusy}
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
                ?disabled=${this.addBusy}
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
              <jf-button label="Cancel" .disabled=${this.addBusy} .onActivate=${() => (this.showManualInput = false)}
                >Cancel</jf-button
              >
            </div>
            ${this.renderPreviewLine()}
          `
        : nothing}

      ${this.loading
        ? html`<div class="empty">Loading…</div>`
        : this.roots.length === 0
          ? !this.indexingActivity.confirmed
            ? html`<div class="empty">${this.indexingActivity.summary}. Folder details are last known; current status is unconfirmed.</div>`
            : this.provisional
            ? html`<div class="empty">
                Rebuilding index… your watched folders will reappear when it finishes.
              </div>`
            : html`<p class="subtitle">Add a folder to make its files searchable.</p>`
          : this.renderCardsRegion()}


      <div class="library-rules">
      <section class="excludes-section" aria-label="Exclude patterns">
        <div class="excludes-header">
          <div>
            <h3>Skip these files</h3>
            <p>One rule per line. Save to skip future matches.</p>
            <details class="rules-help"><summary>How rules work</summary><p>
              One glob per line. Bare names like <code>node_modules</code> or
              <code>dist/</code> match at any depth.
              <code>[ab]</code> matches one listed character; <code>[a-z]</code> matches a range;
              <code>[!ab]</code> matches a character not listed.
              <code>{draft,final}</code> matches either alternative.
              These meanings also apply to patterns saved by earlier versions.
            </p>
            ${this.excludesLoaded && !this.excludesBusy && !this.excludesPolicy ? html`<p role="alert">Effective exclusion policy is unavailable. Reload saved patterns before Preview or Apply.</p>` : nothing}
            <p id="excludes-help">${isOverride(this.excludesPolicy) ? html`Preview counts matches on disk using the effective patterns above and removes nothing.
              Apply uses those patterns to remove matches from search. During an index rebuild, removal may wait until the rebuild finishes.
              Missing or offline files cannot be checked.` : html`Save changes what future indexing skips; it does not remove existing search results.
              Preview counts matches on disk using the saved list and removes nothing.
              Apply uses the saved list to remove matches from search. During an index rebuild, removal may wait until the rebuild finishes.
              Missing or offline files cannot be checked.`}</p></details>
          </div>

        </div>
            ${isOverride(this.excludesPolicy) && this.excludesPolicy ? html`<div id="excludes-override" data-testid="excludes-override" role="note" tabindex="0"
              aria-label=${`Exclusion override: ${policySource(this.excludesPolicy)}. Effective patterns: ${this.excludesPolicy.patterns.join(', ') || '(none)'}. Save edits the saved list; it has no effect until the override is removed.`}>
              <p>Exclusion override: ${policySource(this.excludesPolicy)} controls future indexing, Preview and Apply.
                Save edits the saved list, which has no effect until the override is removed.</p>
              <p>Effective patterns:</p><pre>${this.excludesPolicy.patterns.join('\n') || '(none)'}</pre>
            </div>` : nothing}
        ${this.excludesAttempt && !this.excludesBusy
          ? html`<p>Save completion is unknown. Retry Save patterns to check the same save. Your draft is retained.</p>` : nothing}
        ${this.excludesConflict ? html`<jf-button variant="ghost" label="Review latest settings, keep draft" .disabled=${this.excludesBusy}
          .onActivate=${() => void this.loadExcludes(true)}>Review latest settings, keep draft</jf-button>` : nothing}
        ${this.excludesLoaded && this.excludesDirty ? html`<p>Unsaved draft. Save before Preview or Apply.</p>
          <details class="saved-patterns"><summary>Current saved patterns</summary><pre>${this.excludesSavedText || '(none)'}</pre></details>` : nothing}
        ${this.excludesInvalidPatterns.length > 0
          ? html`<div role="status"><ul>${this.excludesInvalidPatterns.map((pattern) => html`<li>“${pattern}” has invalid pattern syntax and is matched as a literal name. Correct or remove it.</li>`)}</ul></div>`
          : nothing}
        ${this.excludesLiterals.length ? html`<section aria-label="Exact paths kept out">
          <h4>Exact paths kept out</h4>
          <p>These paths are kept out of future indexing. Removing an entry permits a later indexing pass; it does not add a watched folder or index anything automatically. Save patterns to apply your edits.</p>
          <ul>${this.excludesLiterals.map((entry) => html`<li>
            <span>${entry.literal.scope === 'FOLDER' ? 'Folder' : 'File'}: </span><code style="white-space:pre-wrap;overflow-wrap:anywhere">${describeExactPath(entry.literal.path)}</code>
            <jf-button variant="ghost" label=${`Remove keep-out for ${describeExactPath(entry.literal.path)}`}
              .disabled=${this.excludesBusy || this.excludesAttempt !== null}
              .onActivate=${() => { this.excludesLiterals = this.excludesLiterals.filter((current) => current.literal.id !== entry.literal.id); }}>Remove keep-out</jf-button>
          </li>`)}</ul>
        </section>` : nothing}
        <div class="skip-rules" role="group" aria-label="Exclude patterns">
          ${this.excludesText.split('\n').map((rule, index) => html`<div class="skip-rule">
            <input type="text" aria-label=${`Skip rule ${index + 1}`}
              aria-busy=${this.excludesBusy}
              aria-describedby=${`excludes-help${isOverride(this.excludesPolicy) ? ' excludes-override' : ''}${this.excludesError ? ' excludes-error' : ''}`}
              aria-invalid=${this.excludesError ? 'true' : 'false'}
              ?disabled=${!this.excludesLoaded || this.excludesBusy || this.excludesAttempt !== null}
              .value=${rule} @input=${(event: Event) => {
                const rules = this.excludesText.split('\n'); rules[index] = (event.target as HTMLInputElement).value;
                this.excludesText = rules.join('\n');
              }}>
            <jf-button variant="ghost" label=${`Remove skip rule ${index + 1}`}
              .disabled=${!this.excludesLoaded || this.excludesBusy || this.excludesAttempt !== null}
              .onActivate=${() => { this.excludesText = this.excludesText.split('\n').filter((_, i) => i !== index).join('\n'); }}
              >${icon({ name: 'x', size: 14 })}</jf-button>
          </div>`)}
          <jf-button class="add-rule" variant="ghost" label="Add a rule"
            .disabled=${!this.excludesLoaded || this.excludesBusy || this.excludesAttempt !== null}
            .onActivate=${async () => {
              this.excludesText += '\n'; await this.updateComplete;
              this.shadowRoot?.querySelector<HTMLInputElement>('.skip-rule:last-of-type input')?.focus();
            }}><span aria-hidden="true">+</span> Add a rule</jf-button>
        </div>
          <div class="actions">
            <jf-button variant="ghost" label="Save patterns" .disabled=${!this.excludesLoaded || this.excludesBusy || (!this.excludesAttempt && !this.excludesDirty)}
              .onActivate=${() => void this.handleSaveExcludes()}>Save patterns</jf-button>
            <jf-button variant="ghost" label="Reload saved patterns" .disabled=${this.excludesBusy || this.excludesAttempt !== null}
              .onActivate=${() => void this.loadExcludes()}>Reload saved patterns</jf-button>
            <jf-button variant="ghost" label="Preview" .disabled=${!this.excludesLoaded || !this.excludesPolicy || this.excludesBusy || this.excludesAttempt !== null || this.excludesDirty}
              .onActivate=${() => void this.handlePreviewExcludes()}
              >Preview</jf-button
            >
            <jf-button variant="ghost" label="Apply" .disabled=${!this.excludesLoaded || !this.excludesPolicy || this.excludesBusy || this.excludesAttempt !== null || this.excludesDirty}
              .onActivate=${() => void this.handleApplyExcludes()}
              >Apply</jf-button
            >
          </div>
        ${this.excludesError ? html`<p id="excludes-error" role="alert">${this.excludesError}</p>` : nothing}
        <div role="status" aria-live="polite" aria-atomic="true" data-testid="excludes-result">
          ${this.excludesBusy ? 'Working on exclusions…' : this.excludesResult ? this.renderExcludesResult(this.excludesResult) : this.excludesNotice}
        </div>
      </section>
      <jf-indexing-rules-editor api-base=${this.apiBase} .host_=${this.host_}></jf-indexing-rules-editor>
      </div>
      ${!(this.unfinishedForgets?.items.length ?? 0) ? html`<details class="removal-history"><summary>${this.forgetReadStatus === 'unavailable' ? 'Removal history unavailable — check again' : 'Removal history'}</summary>${this.renderUnfinishedForgets()}</details>` : nothing}
      <div class="bottom-links">
        ${this.otherSources.sources.length || this.otherSources.truncated ? html`<details><summary>
          Added by other apps · ${this.otherSources.sources.reduce((sum, source) => sum + source.docCount, 0).toLocaleString()} documents${this.otherSources.truncated ? ' · incomplete list' : ''}
        </summary>${this.renderOtherSources()}</details>` : nothing}
        ${this.activeTab === 'folders' ? html`<jf-ingestion-summary compact .host_=${this.host_}></jf-ingestion-summary>` : nothing}
      </div>
    `;
  }

  private renderExcludesResult(result: ExcludesResult): TemplateResult {
    const partial = result.capped || result.uncheckedScopes.length > 0;
    return html`<p>${result.dryRun ? (partial ? 'Partial Preview' : 'Preview complete') : (partial ? 'Partial Apply' : 'Apply complete')}.
      ${result.dryRun ? `${result.matchedFiles} matching files found on disk. Files may not have been indexed. Nothing was removed.`
        : `${result.confirmedRemovals} matching files or folders confirmed no longer searchable. This counts confirmed deletion requests, including matches that were never indexed.`}</p>
      ${isOverride(result.effectivePolicy ?? null) && result.effectivePolicy ? html`<p>Effective patterns from ${policySource(result.effectivePolicy)}:</p><pre>${result.effectivePolicy.patterns.join('\n') || '(none)'}</pre>` : nothing}
      ${result.capped ? html`<p>Entry cap reached. Remaining disk scope was not checked.</p>` : nothing}
      ${result.uncheckedScopes.length ? html`<ul>${result.uncheckedScopes.map((scope) => html`<li>${scope.status === 'not_checked' ? 'Not checked' : 'Not removed'}: ${scope.path} — ${scope.reason}</li>`)}</ul>
        <p>Some matches may remain searchable. Retry Apply after resolving the listed scope.</p>` : nothing}
      ${result.dryRun ? html`<ul>${result.perPattern.map((match) => html`<li>${match.pattern}: ${match.matches} files</li>`)}</ul>` : nothing}`;
  }

  private renderReindexDecision(): TemplateResult | typeof nothing {
    if (!this.reindexCancelKey || ['complete', 'failed', 'unknown', 'expired'].includes(this.bulkOutcome?.state ?? '')) return nothing;
    const decision = this.gapDecision;
    const waitingForGaps = this.observedGapKey === this.reindexCancelKey;
    const confirmed = this.indexingActivity.confirmed;
    return html`<section class="gap-decision" aria-label=${waitingForGaps ? 'Migration gaps' : 'Rebuild control'}>
      <h3>${!confirmed ? 'Indexing status unconfirmed — rebuild details last observed' : waitingForGaps && decision ? `The new index is ready, but ${decision.gaps.length} document${decision.gaps.length === 1 ? '' : 's'} couldn't be included`
        : waitingForGaps ? 'The new index needs your decision' : 'New index is being built'}</h3>
      ${!confirmed ? html`<p>Rebuild details are last observed; current progress and index availability are unconfirmed.</p>` : nothing}
      ${waitingForGaps && decision ? html`
        ${confirmed ? html`<p>Your current index stays available; activation requires your decision to accept this list.</p>` : nothing}
        <ul>${decision.gaps.map((gap) => html`<li title=${gap.reason}>${gap.unitId}: ${rebuildGapReason(gap.reason)}</li>`)}</ul>
      ` : !confirmed ? nothing : waitingForGaps ? html`<p>Reading the migration gap list…</p>`
        : html`<p>Your current index stays available during the rebuild.</p>`}
      <div class="actions">
        ${waitingForGaps && decision ? html`
        <jf-button label="Use the new index without them" variant="ghost"
          .disabled=${this.gapDecisionBusy || this.reindexCancelBusy || this.reindexCancelRecorded}
          .onActivate=${() => void this.acceptCurrentGaps()}>Use the new index without them</jf-button>
        ` : nothing}
        <jf-button label="Cancel rebuild" variant="ghost"
          .disabled=${this.reindexCancelBusy || this.reindexCancelRecorded || this.gapDecisionBusy}
          .onActivate=${() => void this.cancelCurrentReindex()}>Cancel rebuild</jf-button>
      </div>
      ${this.gapDecisionError ? html`<p role="alert">${this.gapDecisionError}
        ${this.gapDecisionErrorCode && !this.gapDecisionError.endsWith(` (Code: ${this.gapDecisionErrorCode})`) ? html`<small style="display: block">Code: ${this.gapDecisionErrorCode}</small>` : nothing}</p>` : nothing}
      ${this.reindexCancelError ? html`<p role="alert">${this.reindexCancelError}
        ${this.reindexCancelErrorCode && !this.reindexCancelError.endsWith(` (Code: ${this.reindexCancelErrorCode})`) ? html`<small style="display: block">Code: ${this.reindexCancelErrorCode}</small>` : nothing}</p>` : nothing}
      ${this.reindexCancelRecorded ? html`<p role="status">Rebuild cancellation recorded.</p>` : nothing}
    </section>`;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-library-surface')) {
  customElements.define('jf-library-surface', LibrarySurface);
}
