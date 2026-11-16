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
import { createOperationKey } from '../../api/operationKey.js';
import { settingsV2Schema } from '../../api/generated/schema-types/settings-v2.js';
import { repeat } from 'lit/directives/repeat.js';
import { renderFolderRow, folderRowStyles, type FolderRow, type FolderRowIntent } from '../components/folderRow.js';
import { captureSettingsPatch, createSettingsAttempt, executeSettingsAttempt, readSettingsObservation, requireSettingsWitness, SettingsAttemptError, type SettingsAttempt, type SettingsWitness } from '../../api/settingsAttempt.js';
import { describeExactPath, type ExactPathExclusion } from '../../api/exclusionPolicy.js';
import { decideForget, forgetObservationText, parseForgetPage, publishForgetCompletion } from '../../api/domains/forget.js';
import { deepActiveElement } from '../utils/keyboardHandler.js';
import type { ForgetPage, ForgetOutcome } from '../../api/generated/schema-types/index.js';
import { confirmForget } from '../components/searchResults/forgetConsent.js';
import { getShowDeveloperTools, subscribeShowDeveloperTools } from '../state/developerToolsState.js';
import { JfElement } from '../primitives/JfElement.js';
import '../components/OpButton.js';
import { getOperationClient, OperationError, type OperationInvocationSuccess } from '../operations/OperationClient.js';
import '../components/Button.js';
import { requestAuthorization } from '../operations/authorizationBroker.js';
import '../components/IngestionSummary.js';
import '../components/InterruptedImports.js';
// Tempdoc 571 §11 / 578 — Library ⊇ Browse: Library hosts the Browse file-tree as a tab.
import '../components/SurfaceTabs.js';
import type { SurfaceTabs, SurfaceTabItem } from '../components/SurfaceTabs.js';
import { getSurface } from '../../api/registry/SurfaceCatalogClient.js';
import { present } from '../display/present.js';
import { formatCount, formatFileCount } from '../display/format.js';
import { offerReversal, ReversalClosedError } from '../state/ownReversals.js';
import { keepUnsettled, MAY_BE_LOST, readUnsettled, SaveQueue, type SendOutcome } from '../state/saveQueue.js';
import { FocusKeeper, focusRowsUnder } from '../primitives/focusKeeper.js';
import '../components/ConfirmBand.js';
import { emitEphemeralToast } from '../components/advisory/ephemeralToast.js';
import type { ConfirmBand } from '../components/ConfirmBand.js';
import { folderName } from '../state/frameStatus.js';
import { takeIndexingRuleFocus, subscribeIndexingRuleFocus, type IndexingRuleControl } from '../router/indexingRuleIntent.js';
import { takeMemberTabIntent, subscribeMemberTab } from '../router/memberTabIntent.js';
import { readIndexedRoots, subscribeIndexedRoots, getIndexedRoots, forgetIndexedRoots, withRootCounts } from '../state/indexedRoots.js';
import { takeLibraryRoot, subscribeLibraryRoot, type LibraryRootRequest } from '../router/libraryRootIntent.js';
import { resolvePathLazy } from '../hooks/resolvePathLazy.js';
import { icon } from '../components/Icon.js';
import { disclosureMark, disclosureStyles } from '../components/disclosure.js';
// 569 §14 — render the indexed-folder cards through the projection engine (the 2nd real surface).
import { activeBodyFor, subscribePresentation } from '../state/presentationRuntime.js';
import { subscribeAiState } from '../state/aiStateStore.js';
import '../components/IndexingControls.js';
import '../components/IndexingRulesEditor.js';
import type { IndexingRulesEditor } from '../components/IndexingRulesEditor.js';
import { revealFailedFiles } from '../components/FailedFilesList.js';
import { LIBRARY_CARDS_REGION } from '../themes/builtinPresentations.js';
import '../components/DeclaredSurface.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
// Tempdoc 564 Phase 2: the indexed-roots wire shape is the generated IndexedRootView projection
// (record → JSON Schema → {TS, Zod}); validated at the fetch boundary, no hand interface.
import { z } from 'zod';
import { parseWireContract } from '../../api/schemas.js';
import {
  type IndexedRootView,
} from '../../api/generated/schema-types/indexed-root-view.js';
import { operationOutcomeViewSchema, type OperationOutcomeView } from '../../api/generated/schema-types/index.js';
// Tempdoc 599 §9.1 — the ONE per-folder status derivation; the row glyph + meta line project from it.
import {
  folderAttention,
  folderStatus,
  isWalkErrorCode,
  rootCoverage,
  type FolderStatus,
  meaningProblem,
} from '../state/folderStatus.js';
// Tempdoc 813 §4 — the index-wide progress authority supplies enrichment-stage applicability, which
// the per-root wire row cannot carry (counts only, no enabled flags).
import {
  selectIndexingProgress,
  type EnrichmentApplicability,
  type IndexingProgress,
} from '../state/indexingProgress.js';
// Tempdoc 811 C-2a — the non-root (ad-hoc / MCP) sources the folder rows cannot describe.
import {
  deriveOtherSources,
  facetScanCapFor,
  EMPTY_OTHER_SOURCES,
  // Tempdoc 914 D4 — the FE mirror of IngestCollectionPolicy's reserved names, so the Add Folder
  // collection field refuses one with a reachable reason instead of creating the root.
  type OtherSource,
  type OtherSourcesSnapshot,
} from '../state/otherSources.js';
// The ONE `/api/knowledge/search` issuance site (577 Ext II / the `search-issuance` gate) — the
// enumeration probe is issued there, not re-shaped here.
import { fetchCollectionFacet } from '../state/searchState.js';
import { UNKNOWN, type Maybe } from '../state/known.js';
// Tempdoc 599 §9.4 — gate the Add button with a reachable reason (596 operability authority).
import { FolderAddFlow, focusFolderAdd, type FolderPreview } from '../controllers/FolderAddFlow.js';
// Tempdoc 914 D3 — the failed chip's shared look (one authority for both render sites).
import { failedChipStyles } from '../components/failedChipPresentation.js';

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

/** The glob rules a text holds: one per line, blank lines aside. */
function globRules(text: string | null): string[] {
  return (text ?? '').split('\n').map(rule => rule.trim()).filter(Boolean);
}

/** Why a rule is not complete yet, or null. Mirrors the glob compiler's refusals the person can type. */
function halfPattern(rule: string): string | null {
  let square = false, curly = false;
  for (let i = 0; i < rule.length; i++) {
    const c = rule[i];
    if (c === '\\') { if (i === rule.length - 1) return 'it ends with a \\'; i++; continue; }
    if (square) { if (c === ']') square = false; continue; }
    if (c === '[') square = true;
    else if (c === '{') { if (curly) return 'a { } group cannot hold another one'; curly = true; }
    else if (c === '}' && curly) curly = false;
  }
  return square ? 'a [ needs a closing ]' : curly ? 'a { needs a closing }' : null;
}

/** The ask after a rule is added is about the whole saved list (core.apply-excludes applies all rules). */
const REMOVAL_ASK = 'Remove files already read that match your skip rules from search? Files on disk are not touched.';
type RemovalAsk = { readonly serial: number; readonly list: string; readonly base: readonly string[] };
/**
 * What one answer did, for the ask (serial) and list it answered (R12). The result counts deletion
 * requests, not documents, so the outcome holds no number (R10).
 */
type RemovalOutcome = { readonly kind: 'done' | 'partly' | 'unsure' | 'unavailable' | 'kept' | 'nothing'; readonly serial: number;
  readonly capped: boolean; readonly scopes: ExcludesResult['uncheckedScopes']; readonly list: string; readonly detail: string };
/**
 * R22/R23 — how long one owner of Library's shared settings write chain (a skip-rule or reading-hours
 * save, the toolbar's Pause or Resume) waits for it and holds it, every network step included, before
 * its outcome is unknown.
 */
const SETTINGS_WRITE_BOUND_MS = 10_000;
const OVERRIDE_NO_REMOVAL = 'Skip rules set by an override are in effect, so files can\'t be removed from here.';
/** A skip-rule draft: its glob rules as one text, and the exact-path entries kept with them. */
type SkipDraft = { readonly text: string; readonly literals: readonly ExactPathExclusion[] };
/**
 * R11 — exactly what one save-triggering event saves. An Undo restores the glob rules as they were
 * before the save it is bound to, on that save's own revision only (B5').
 */
type SkipSnapshot = SkipDraft & { readonly undo?: { readonly serial: number; readonly witness: SettingsWitness } };

/**
 * R24 — this window's unsettled skip-rule saves, kept in sessionStorage across a reload: the list and
 * revision they are based on, the captured snapshots, and the head's sent attempt (key and bytes).
 */
const UNSETTLED_SKIP_RULES = 'justsearch.library.unsettledSkipRules';
const keptWitnessSchema = z.object({ acceptedRevision: z.number(), lastCommittedOperationKey: z.string().nullable() });
const unsettledSkipRulesSchema = z.object({
  base: z.object({ text: z.string(), literals: z.array(z.unknown()), witness: keptWitnessSchema }),
  snapshots: z.array(z.object({ text: z.string(), literals: z.array(z.unknown()),
    undo: z.object({ serial: z.number().int(), witness: keptWitnessSchema }).optional() })).min(1),
  attempt: z.object({ operationKey: z.string(), witness: keptWitnessSchema, body: z.string(),
    headers: z.record(z.string(), z.string()) }).nullable(),
  undoSerial: z.number().int(),
});
type UnsettledSkipRules = { base: SkipDraft & { witness: SettingsWitness }; snapshots: SkipSnapshot[];
  attempt: SettingsAttempt | null; undoSerial: number };

/** The kept record, if it is one: its revisions and exact-path entries are checked as the save path does. */
function readUnsettledSkipRules(): UnsettledSkipRules | null {
  const parsed = unsettledSkipRulesSchema.safeParse(readUnsettled(UNSETTLED_SKIP_RULES));
  if (!parsed.success) return null;
  const { base, snapshots, attempt, undoSerial } = parsed.data;
  try {
    const literals = (entries: unknown[]) => {
      captureSettingsPatch({ ui: { excludePatterns: entries as ExactPathExclusion[] } });
      if (entries.some(entry => typeof entry === 'string')) throw new Error('not an exact-path entry');
      return entries as ExactPathExclusion[];
    };
    return {
      base: { text: base.text, literals: literals(base.literals), witness: requireSettingsWitness(base.witness) },
      snapshots: snapshots.map(snapshot => Object.freeze({ text: snapshot.text, literals: literals(snapshot.literals),
        ...snapshot.undo ? { undo: { serial: snapshot.undo.serial, witness: requireSettingsWitness(snapshot.undo.witness) } } : {} })),
      attempt: attempt ? Object.freeze({ ...attempt, witness: Object.freeze(requireSettingsWitness(attempt.witness)) }) : null,
      undoSerial,
    };
  } catch {
    return null;
  }
}

/** Support codes stay on each exact gap; the decision itself uses plain language. */
function rebuildGapReason(code: string): string {
  const reasons: Record<string, string> = {
    CANDIDATE_PROJECTION_MISSING: 'The document was not added to the new search data.',
    PROJECTION_WITNESS_MISSING: 'The document could not be verified in the new search data.',
    DELETE_NOT_APPLIED: 'The document could not be removed from the new search data.',
    PROJECTION_DELETE_NOT_APPLIED: 'The document removal could not be verified.',
    PROJECTION_SOURCE_INCOMPLETE: 'Documents from this source are not ready.',
    PROJECTION_SOURCE_MARKER_MISSING: 'This document source could not be verified.',
    PARSER_FAILED: 'The file could not be read by its parser.',
    PARSER_TIMEOUT: 'Reading the file took too long.',
    SANDBOX_FAILED: 'The file reader could not run safely.',
    UNREADABLE: 'The file could not be read.',
    SOURCE_UNREADABLE: 'The source file could not be read.',
    INDEXED_SOURCE_UNREADABLE: 'The read source file could not be read again.',
    ROOT_UNAVAILABLE: 'The containing folder could not be found or read.',
    IO_ERROR: 'The file could not be accessed.',
    INPUT_TOO_LARGE: 'The file exceeds the size limit.',
    OFFICE_INPUT_TOO_LARGE: 'The Office document exceeds the size limit.',
    WRITE_FAILED: 'The document could not be saved to the new search data.',
    WRITE_UNAVAILABLE_DRAINING: 'The search data was unavailable while finishing other work.',
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
    SKIPPED_PROTECTED: 'The source holds passwords or keys, or is in a protected folder.',
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
const OP_ADD = 'core.add-watched-root';
const OP_REMOVE = 'core.remove-watched-root';
const OP_PREVIEW = 'core.preview-excludes';
const OP_APPLY = 'core.apply-excludes';

// Tempdoc 599 §9.3 — minimum spacing between aiState-tick-driven (counts-free) live refreshes, so
// the row updates while indexing without re-fetching faster than the underlying status cadence.
const LIVE_REFRESH_MIN_MS = 4000;

export class LibrarySurface extends JfElement {
  private expandedFolders = new Set<string>();
  private contactUnconfirmed = false;
  private rebuilding = false;
  private provisionalHeadline = 'Reading status unconfirmed';
  private indexProgress = selectIndexingProgress(null, false, 0, []);
  /** Slice 6c — the status body held now: a new one is a new observation, a reprojection keeps it. */
  private statusObservation: unknown = null;
  static properties = {
    developer: { state: true }, rereadConfirmOpen: { state: true }, rereadBusy: { state: true }, activityOpen: { state: true },
    apiBase: { attribute: 'api-base', type: String },
    host_: { attribute: false },
    roots: { state: true },
    resolvedPaths: { state: true },
    loading: { state: true },
    error: { state: true },
    excludesText: { state: true },
    skipFieldOpen: { state: true },
    excludesLiterals: { state: true },
    unfinishedForgets: { state: true },
    forgetReadStatus: { state: true },
    forgetDecisionKey: { state: true },
    forgetMessage: { state: true },
    excludesInvalidPatterns: { state: true },
    excludesLoaded: { state: true },
    excludesReading: { state: true },
    excludesError: { state: true },
    excludesNotice: { state: true },
    excludesResult: { state: true },
    excludesPolicy: { state: true },
    excludesConflict: { state: true },
    excludesConflictSaved: { state: true },
    excludesLoadFailed: { state: true },
    excludesRuleProblem: { state: true },
    removalAsk: { state: true },
    removalOutcome: { state: true },
    removalBusy: { state: true },
    addBusy: { state: true },
    removingHashes: { state: true },
    removeConfirm: { state: true },
    collectionConfirm: { state: true },
    cancelRebuildConfirm: { state: true },
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
    ownDocuments: { state: true },
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

  private developerUnsub: (() => void) | undefined;
  declare developer: boolean;
  declare rereadConfirmOpen: boolean;
  declare rereadBusy: boolean;
  declare activityOpen: boolean;
  declare apiBase: string;
  declare host_: PluginHostApi;
  declare roots: IndexedRootView[];
  declare resolvedPaths: Record<string, string>;
  declare loading: boolean;
  declare error: string | null;
  declare excludesText: string;
  /** fm-07 — an empty field added while the list holds no rule; with none, no rule field shows. */
  declare private skipFieldOpen: boolean;
  declare excludesInvalidPatterns: string[];
  declare excludesLoaded: boolean;
  /** A load or a match count is running. */
  declare private excludesReading: boolean;
  /** Reading, or a captured skip-rule change is being saved. */
  get excludesBusy(): boolean { return this.excludesReading || this.skipQueue.sending; }
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
  /** "Changed elsewhere": the saved list (and its revision) observed when this window's save was refused. */
  declare private excludesConflictSaved: { text: string; literals: ExactPathExclusion[]; witness: SettingsWitness } | null;
  declare private excludesLoadFailed: boolean;
  /** A rule that is not complete yet: never sent; its field names why. */
  declare private excludesRuleProblem: { index: number; reason: string } | null;
  /** The one in-place removal ask, bound to the saved list it is about. */
  declare private removalAsk: RemovalAsk | null;
  declare private removalOutcome: RemovalOutcome | null;
  declare private removalBusy: boolean;
  private removalAskSerial = 0;
  private lastEditedRule = 0;
  /** PD4'' — the last rule field is a new one from Add a rule, not yet left with a rule in it. */
  private addedRuleOpen = false;
  /** PD4'' — a pointer press is in progress (from pointerdown until its pointerup). */
  private pointerPressing = false;
  /** PD4'' — the new field was left empty during a pointer press: it goes once the press has ended. */
  private addedRuleLeft = false;
  private readonly onPointerDown = () => { this.pointerPressing = true; };
  private readonly onPointerUp = () => {
    this.pointerPressing = false;
    if (!this.addedRuleLeft) return;
    this.addedRuleLeft = false;
    // After the press's own click: its target has not moved from under the pointer.
    setTimeout(() => this.dropAddedRule());
  };
  /**
   * R7/R11 — the drafts captured when their field was left (or by Try again or Undo), saved in order.
   * Only these bytes are ever sent: text typed after returning to a field stays a draft until it is left.
   */
  private readonly skipQueue = new SaveQueue<SkipSnapshot>(next => this.sendSkipRules(next), () => {
    this.keepUnsettledSkipRules();
    this.requestUpdate();
  });
  /** Only the latest glob change can be undone. */
  private skipUndoSerial = 0;
  /** R27 — the unsettled skip-rule saves could not be kept for a reload; tried again on each queue change. */
  private skipCheckpointLost = false;
  /** The Undo being run, answered when its queued snapshot settles. */
  private undoWaiter: { serial: number; resolve: () => void; reject: (error: Error) => void } | null = null;
  declare private excludesPolicy: ExcludesPolicy | null;
  private excludesWitness: SettingsWitness | null = null;
  private excludesSavedText: string | null = null;
  declare private excludesLiterals: ExactPathExclusion[];
  private excludesSavedLiterals: ExactPathExclusion[] = [];
  /** The list saved once every captured change is. */
  private get queuedList(): SkipDraft {
    return this.skipQueue.snapshots.at(-1) ?? { text: this.excludesSavedText ?? '', literals: this.excludesSavedLiterals };
  }
  private get excludesDirty(): boolean {
    const draft = this.savedListKey(this.excludesText, this.excludesLiterals);
    const queued = this.queuedList;
    if (draft === this.savedListKey(queued.text, [...queued.literals])) return false;
    // R13 — while an Undo waits, the list it replaces is still shown: that is not a new change.
    const snapshots = this.skipQueue.snapshots;
    if (!snapshots.at(-1)?.undo) return true;
    const replaced = snapshots.at(-2) ?? { text: this.excludesSavedText ?? '', literals: this.excludesSavedLiterals };
    return draft !== this.savedListKey(replaced.text, [...replaced.literals]);
  }
  /** The queue head's sent attempt: kept with its key and bytes while its outcome is unknown. */
  private excludesAttempt: SettingsAttempt | null = null;
  private excludesLoadStarted = false;
  /** Identifies the passive read in flight; leaving the Library orphans it. */
  private excludesRefreshRead: object | null = null;
  private readonly onWindowFocus = () => { void this.refreshExcludes(); };
  private get excludesClean(): boolean {
    return this.excludesLoaded && !this.excludesBusy && !this.skipQueue.snapshots.length && !this.excludesConflict && !this.excludesDirty;
  }
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
  /** DE1 — the removal waiting in its in-place band, with the registrations it affects. */
  declare removeConfirm: { readonly hash: string; readonly origin: 'row' | 'attention'; readonly resolved: string;
    readonly enclosing: boolean; readonly nested: readonly string[]; readonly heading: string;
    readonly consequence: string; readonly actionLabel: string } | null;
  /** DE1 — the other-app collection whose removal is waiting in its band. */
  declare collectionConfirm: string | null;
  /** DE1 — Cancel rebuild is waiting in its band, for the rebuild with this key. */
  declare cancelRebuildConfirm: boolean;
  private cancelRebuildKey: string | null = null;
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
  /** The person's own searchable files (the bundled Help aside), as the header counts them (FA4). */
  declare ownDocuments: Maybe<number>;
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
   * Tempdoc 811 C-2a — the default-scope document count (Help included), used to size the collection
   * facet scan so it cannot truncate (truncation OMITS collections; see `otherSources.ts`). Not
   * reactive state: it only feeds the next probe's request and, on an Engine that does not report
   * the own count, the headline's "has anything been indexed" question.
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

  constructor() {
    super();
    // L21 — focus stays on its control across row updates; when its row leaves, a neighbour (else Add folder) takes it.
    new FocusKeeper(this, () => this.renderRoot.querySelector<HTMLElement>('[data-add-folder]'));
    this.apiBase = '';
    this.rereadConfirmOpen = false;
    this.rereadBusy = false;
    this.host_ = undefined as unknown as PluginHostApi;
    this.roots = [];
    this.resolvedPaths = {};
    this.loading = false;
    this.error = null;
    this.excludesText = '';
    this.skipFieldOpen = false;
    this.excludesInvalidPatterns = [];
    this.excludesLoaded = false;
    this.excludesLiterals = [];
    this.unfinishedForgets = null;
    this.forgetReadStatus = 'loading';
    this.forgetDecisionKey = null;
    this.forgetMessage = '';
    this.excludesReading = false;
    this.pendingIndexingFocus = undefined;
    this.excludesError = null;
    this.excludesNotice = '';
    this.excludesResult = null;
    this.excludesPolicy = null;
    this.excludesConflict = false;
    this.excludesConflictSaved = null;
    this.excludesLoadFailed = false;
    this.excludesRuleProblem = null;
    this.removalAsk = null;
    this.removalOutcome = null;
    this.removalBusy = false;
    this.addBusy = false;
    this.removingHashes = new Set();
    this.removeConfirm = null;
    this.collectionConfirm = null;
    this.cancelRebuildConfirm = false;
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
    this.ownDocuments = UNKNOWN;
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
    disclosureStyles,
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
      font-variation-settings: "opsz" 32;
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
      border-radius: 8px;
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
      border-radius: 8px;
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
      border-radius: 8px;
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
      border-radius: 8px;
      color: var(--text-primary);
      font-family: monospace;
      font-size: var(--font-size-sm);
      box-sizing: border-box;
      resize: vertical;
    }
    .jf-icon-spin {
      animation: jf-spin 1s linear infinite;
    }
    :host .literal-path { white-space:pre-wrap;overflow-wrap:anywhere; }
    :host .error-code { display: block; }

  `,
    // Tempdoc 914 D3 — the failed chip's look is shared with the declared FolderCardRenderer so the
    // two render sites cannot drift; see failedChipPresentation.ts.
    failedChipStyles,
    folderRowStyles,
    css`
      /* The page column: the scroller spans the window, its content sits on the centred column. */
      .folders-scroll { padding: 1.8rem calc(var(--surface-content-gutter) + 2.5rem); min-width: 0; }
      .header-left h2 { font-size: var(--font-size-headline); font-variation-settings: "opsz" 32; font-weight: 400; }
      .header { margin-bottom: 1.5rem; }
      .subtitle { overflow-wrap: anywhere; }
      .header-left .subtitle { font-size: var(--font-size-sm); }
      jf-declared-surface::part(heading) { font-size: var(--font-size-xs); font-weight: 500;
        text-transform: none; letter-spacing: normal; }
      .removal-history { margin-top: 1rem; font-size: var(--font-size-xs); color: var(--text-secondary); }
      .removal-history summary { cursor: pointer; }
      .window-actions { display: flex; flex-wrap: wrap; gap: .5rem; align-items: center; }
      @media (max-width: 45rem) { .window-actions { gap: .25rem; } }
      .text-action { align-self: center; padding: .25rem 0; border: 0; background: transparent;
        color: var(--text-secondary); font: inherit; cursor: pointer; }
      .text-action:hover { color: var(--text-primary); text-decoration: underline; }
      .text-action[aria-disabled="true"] { cursor: default; opacity: .65; }
      .text-action[aria-disabled="true"]:hover { color: var(--text-secondary); text-decoration: none; }
      .text-action:focus-visible { outline: 2px solid var(--focus-ring-color); outline-offset: 2px; }
      .reread-confirmation { flex-basis: 100%; }
      .reread-confirmation:empty { display: none; }
      .reread-confirmation p { margin: 0 0 .5rem; }
      .reread-confirmation button + button { margin-inline-start: .75rem; }
      .browser-banner { padding: 0; border: 0; background: transparent; color: var(--text-secondary); font-size: var(--font-size-xs); }
      .excludes-section, .gap-decision { background: transparent; border: 0; border-radius: 0;
        border-top: 1px solid var(--border-subtle); padding: 1rem 0; }
      h3, .excludes-header h3 { color: var(--text-secondary); font-size: var(--font-size-xs); font-weight: 500; text-transform: none; letter-spacing: normal; }
      .attention { margin-bottom: 1.75rem; }
      .attention:empty, .action-message:empty { display: none; }
      .library-rules { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 2.5rem; margin-top: 1.5rem; }
      .library-rules > .excludes-section { margin: 0; padding: 0; border: 0; }
      .excludes-header { display: block; }
      .excludes-header .actions { margin: .5rem 0; }
      .rules-help { font-size: var(--font-size-xs); color: var(--text-secondary); margin: .5rem 0; }
      .skip-rule { display: flex; align-items: center; border-bottom: 1px solid var(--border-subtle); }
      .skip-rule input { flex: 1; min-width: 0; padding: .5rem .2rem; font: inherit; font-size: var(--font-size-sm); font-family: monospace;
        border: 0; background: transparent; color: var(--text-primary); }
      .skip-rule input:focus-visible { outline: 2px solid var(--focus-ring-color); outline-offset: 2px; }
      .add-rule::part(control) { color: var(--text-primary); font-weight: 500; }
      .rule-problem { margin: .25rem 0 .5rem; font-size: var(--font-size-xs); color: var(--text-primary); overflow-wrap: anywhere; }
      .removal-slot:empty, .skip-conflict:empty { display: none; }
      .removal-ask, .skip-conflict { margin-top: .75rem; font-size: var(--font-size-sm); }
      .removal-ask p, .skip-conflict p { margin: 0 0 .5rem; overflow-wrap: anywhere; }
      .removal-ask button + button, .skip-conflict button + button { margin-inline-start: .75rem; }
      .removal-slot ul { margin: .25rem 0; padding-left: 1.25rem; font-size: var(--font-size-xs); overflow-wrap: anywhere; }
      @media (max-width: 800px) { .library-rules { grid-template-columns: minmax(0, 1fr); gap: 1.5rem; } }
      @media (forced-colors: active) { .skip-rule { border-color: CanvasText; } .skip-rule input:focus-visible { outline-color: Highlight; } }
      .gap-decision h3 { font-size: var(--font-size-sm); font-weight: 400; color: var(--text-primary); }
      .attention-item { padding: .85rem 0; border-top: 1px solid var(--border-subtle); }
      .attention-title { display: flex; gap: .7rem; align-items: center; font-size: var(--font-size-sm); }
      .attention-title > span { color: var(--text-warning); }
      .attention-item p { margin: .35rem 0 .3rem 1.7rem; font-size: var(--font-size-sm); color: var(--text-secondary); overflow-wrap: anywhere; }
      .attention-actions { margin-left: 1.7rem; }
      .bottom-links { display: flex; flex-wrap: wrap; gap: 1.5rem; margin-top: 1.5rem;
        border-top: 1px solid var(--border-subtle); padding-top: .75rem; font-size: var(--font-size-sm); color: var(--text-secondary); }
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
  private pendingIndexingFocus: IndexingRuleControl | undefined;
  private lastKnownAttention = new Map<string, { key: string; title: string; detail?: string; actions?: TemplateResult }>();
  private lastKnownHold: IndexingProgress | undefined;

  private requestIndexingFocus(control: IndexingRuleControl): void {
    const serial = ++this.indexingFocusSerial;
    // Coalesce requests in one turn, and never focus after leaving the Library.
    void Promise.resolve().then(() => {
      if (this.isConnected && serial === this.indexingFocusSerial) void this.focusIndexingRule(control);
    });
  }

  private rootsUnsub?: () => void;
  private rootArrivalUnsub?: () => void;
  private rootArrivalSerial = 0;

  private async receiveRoot(request: LibraryRootRequest): Promise<void> {
    const serial = ++this.rootArrivalSerial;
    this.activeTab = 'folders';
    const confirmed = await this.refresh({ live: true, force: true });
    if (!this.isConnected || serial !== this.rootArrivalSerial) return;
    if (!confirmed) { this.error = 'Folder availability is unconfirmed. Retry when Library can refresh.'; return; }
    if (!this.roots.some(root => root.pathHash === request.pathHash)) {
      this.error = 'This folder is no longer in your library.';
      return;
    }
    this.expandedFolders.add(request.pathHash);
    this.requestUpdate();
    await this.updateComplete;
    if (!this.isConnected || serial !== this.rootArrivalSerial) return;
    const declared = this.renderRoot.querySelector<HTMLElement & { updateComplete: Promise<unknown> }>('jf-declared-surface');
    if (declared) await declared.updateComplete;
    if (!this.isConnected || serial !== this.rootArrivalSerial || !this.roots.some(root => root.pathHash === request.pathHash)) {
      this.error = 'This folder is no longer in your library.';
      return;
    }
    const row = focusRowsUnder(this.renderRoot).find(element => element.dataset.focusRow === request.pathHash);
    row?.scrollIntoView?.({ block: 'nearest' });
    row?.querySelector<HTMLElement>('.folder-toggle')?.focus();
    if (request.action === 'remove') await this.handleRemoveRoot(request.pathHash);
  }

  override connectedCallback(): void {
    super.connectedCallback();
    this.developer = getShowDeveloperTools();
    this.developerUnsub = subscribeShowDeveloperTools(show => { this.developer = show; });
    this.rootsUnsub = subscribeIndexedRoots(() => {
      const observation = getIndexedRoots();
      this.resolvedPaths = { ...this.resolvedPaths, ...observation.paths };
      if (observation.confirmed) this.roots = withRootCounts(observation.roots, this.resolvedPaths);
      this.lastKnownFailed = { ...observation.lastKnownFailed };
    });
    this.rootArrivalUnsub = subscribeLibraryRoot(request => { void this.receiveRoot(request); });
    const rootArrival = takeLibraryRoot();
    if (rootArrival) void this.receiveRoot(rootArrival);
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
    if (!rootArrival) void this.refresh();
    if (this.reindexOutcomeKey) void this.checkReindexOutcome();
    if (!this.excludesLoadStarted) void this.loadExcludes();
    else void this.refreshExcludes();
    window.addEventListener('focus', this.onWindowFocus);
    window.addEventListener('pointerdown', this.onPointerDown, true);
    window.addEventListener('pointerup', this.onPointerUp, true);
    window.addEventListener('pointercancel', this.onPointerUp, true);
    this.presentationUnsub = subscribePresentation(() => this.requestUpdate());
    // Keep empty roots provisional during transitions, but only call a real
    // rebuild/migration "Rebuilding". Other transitions use the status bar's wording.
    this.aiUnsub = subscribeAiState((s) => {
      this.provisional = s.stability.kind === 'provisional';
      this.rebuilding = s.stability.kind === 'provisional' &&
        (s.stability.cause === 'rebuilding' || s.stability.cause === 'generation-switch');
      this.provisionalHeadline = this.rebuilding ? 'Rebuilding your Library' : s.statusLabel;
      this.contactUnconfirmed = s.stability.kind === 'provisional' &&
        ['initial-load', 'updating', 'channel-stale'].includes(s.stability.cause);
      this.observeGapDecision(s.status?.worker?.migration);
      // Tempdoc 813 §4 — the per-root second tier needs to know which stages apply; take it from the
      // ONE index-wide progress derivation rather than re-reading the enrichment wire flags here.
      const progress = selectIndexingProgress(
        s.status,
        s.indexingStatusLive,
        s.episodeMaxPendingJobs,
        s.enrichSettleSamples,
      );
      if (progress.activity.confirmed) this.lastKnownHold = progress.pause ? progress : undefined;
      this.indexingActivity = progress.activity;
      this.indexProgress = progress;
      this.statusObservation = s.status;
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
      this.ownDocuments = s.index.ownDocumentCount;
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
    this.rereadConfirmOpen = false;
    this.rereadBusy = false;
    this.excludesError = null;
    this.excludesNotice = '';
    this.excludesResult = null;
    this.removalOutcome = null;
    this.loading = false;
    this.error = null;
    this.surfaceSerial++;
    this.refreshSerial++;
    this.refreshing = false;
    this.addBusy = false;
    this.removingHashes = new Set();
    this.confirmingHashes.clear();
    this.removeConfirm = null;
    this.collectionConfirm = null;
    this.cancelRebuildConfirm = false;
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
    // Leaving Library leaves the field: the draft is saved (or its retained attempt checked).
    if (this.excludesLoaded && (this.excludesDirty || this.skipQueue.uncertain)) void this.handleSaveExcludes();
    this.developerUnsub?.();
    this.rootsUnsub?.();
    this.rootArrivalUnsub?.();
    this.rootArrivalSerial++;
    window.removeEventListener('focus', this.onWindowFocus);
    window.removeEventListener('pointerdown', this.onPointerDown, true);
    window.removeEventListener('pointerup', this.onPointerUp, true);
    window.removeEventListener('pointercancel', this.onPointerUp, true);
    this.excludesRefreshRead = null;
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
    this.addFlow.dispose();
  }

  private focusedFolderToggle: HTMLElement | null = null;

  protected override willUpdate(changed: Map<string, unknown>): void {
    super.willUpdate(changed);
    // fm-07 — an open empty field belongs to the empty list it was added to; a rule ends that.
    if (changed.has('excludesText') && this.excludesText !== '') this.skipFieldOpen = false;
    // DE1 — a removal band whose folder left the list (removed in another window) is no longer asking.
    if (changed.has('roots') && this.removeConfirm && !this.roots.some(root => root.pathHash === this.removeConfirm?.hash)) {
      this.removeConfirm = null;
    }
    // With no watched folder there is nothing to read again: the action and its ask leave the page.
    if (changed.has('roots') && this.rereadConfirmOpen && this.noWatchedFolders()) this.rereadConfirmOpen = false;
    // L18 — the same for a collection: a band whose source left the list closes for good; it opens
    // again only on a press, never because the source came back.
    if (changed.has('otherSources') && this.collectionConfirm
      && !this.otherSources.sources.some(source => source.collection === this.collectionConfirm)) {
      this.collectionConfirm = null;
    }
    const active = deepActiveElement();
    this.focusedFolderToggle = active instanceof HTMLElement && active.matches('.folder-toggle')
      && this.shadowRoot?.activeElement ? active : null;
  }

  private async restoreFolderToggleFocus(previous: HTMLElement): Promise<void> {
    // Declared presentations replace nested renderer elements. Wait for each new renderer's
    // update, then restore only the disclosure that held focus; never steal a later focus move.
    const controls = previous.getAttribute('aria-controls');
    const visit = async (root: ShadowRoot): Promise<HTMLElement | null> => {
      for (const child of root.querySelectorAll<HTMLElement>('*')) {
        if ('updateComplete' in child) await (child as JfElement).updateComplete;
        if (child.matches('.folder-toggle') && child.getAttribute('aria-controls') === controls) return child;
        if (child.shadowRoot) { const found = await visit(child.shadowRoot); if (found) return found; }
      }
      return null;
    };
    const replacement = await visit(this.shadowRoot!);
    const active = deepActiveElement();
    if (this.isConnected && !previous.isConnected && (!active || active === document.body)) {
      replacement?.focus({ preventScroll: true });
    }
  }

  protected override updated(changed: Map<string, unknown>): void {
    super.updated(changed);
    if (!this.loading && this.pendingIndexingFocus) {
      const control = this.pendingIndexingFocus;
      this.pendingIndexingFocus = undefined;
      void this.focusIndexingRule(control);
    }
    if (this.focusedFolderToggle) void this.restoreFolderToggleFocus(this.focusedFolderToggle);
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
        <div><code class="literal-path">${item.path === null ? 'Exact target unavailable' : describeExactPath(item.path)}</code></div>
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
      rebuilding: this.rebuilding,
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

  /**
   * A failed inventory read leaves the folders unchecked, independently of status polls: whether or
   * not an earlier read succeeded, and whether or not rows were retained across an Engine change.
   */
  private get rootsUnconfirmed(): boolean {
    const observation = getIndexedRoots();
    return !observation.confirmed && observation.error !== null;
  }

  private projectFolder(root: IndexedRootView): FolderRow {
    const pathHash = root.pathHash ?? '';
    const fullPath = this.resolvedPaths[pathHash];
    const name = folderRowLabel(pathHash, fullPath);
    const fs = this.rowStatus(root);
    const progress = rootCoverage(root, this.enrichmentStages);
    const confirmed = this.indexingActivity.confirmed && !this.rootsUnconfirmed;
    const unknown = !confirmed || this.provisional || fs.state === 'unavailable';
    const files = formatFileCount(root.fileCount, root.fileCountCapped);
    // Meaning state is said once, in the open row's "Search by meaning" line: a folder that word search
    // covers, and whose meaning work only waits for AI, is up to date. Failed or missing meaning work
    // is a problem with this folder, so the row says so.
    const problem = fs.state === 'keyword-only' || fs.state === 'unknown' && fs.glyph === 'unverified' ? meaningProblem(root, this.enrichmentStages) : null;
    const statusText = problem === 'failed' ? 'Reading for meaning failed for some files. Word search remains available.'
      : problem === 'incomplete' ? 'Reading for meaning is incomplete for some files. Word search remains available.'
      : problem === 'unconfirmed' ? 'Reading for meaning is unconfirmed. Word search remains available.'
      : fs.state === 'ready' || fs.state === 'keyword-only' || fs.state === 'enriching' && this.enrichmentBlocked ? 'Up to date'
      : fs.state === 'empty' ? 'No files to read'
      : fs.state === 'enriching' ? `Meaning processing${progress?.percent != null ? ` · ${progress.percent}%` : ''}`
      : fs.state === 'failed' && !root.walkError ? 'Files need review'
      : fs.state === 'unverified' ? "Couldn't verify deletions — read again to be sure"
      : fs.metaText.split(' · ').slice(1).filter(part => !/^(last known )?\d[\d.,'\u00a0\u202f\u2019]*[+]? files?$|^read |^indexed |^Verified /.test(part)).join(' · ');
    return {
      expanded: this.expandedFolders.has(pathHash),
      pathHash, displayPath: name.label, fullPath, pathTitle: name.title,
      walkError: this.developer && root.walkError && isWalkErrorCode(root.walkError) ? root.walkError : undefined,
      status: !confirmed && fs.glyph === 'indexed' ? 'pending' : fs.glyph,
      statusText: this.rootsUnconfirmed ? 'Reading status unconfirmed'
        : !confirmed && statusText === 'Up to date' ? this.indexingActivity.summary
        : statusText,
      metaText: this.removingHashes.has(pathHash) ? 'Removing folder…' : fs.metaText,
      removalBusy: this.removingHashes.has(pathHash),
      ...(this.removeConfirm?.origin === 'row' && this.removalPlan(pathHash) ? { removeConfirm: this.removalPlan(pathHash)! } : {}),
      failed: fs.failed, failedLastKnown: fs.failedIsLastKnown === true, apiBase: this.apiBase,
      filesFound: files ? `${unknown ? 'Last known ' : ''}${files}` : '',
      developer: this.developer,
      meaningText: this.meaningText(root, fs),
      reading: confirmed && !this.indexProgress.pause && (fs.state === 'indexing' || fs.state === 'scanning'),
      collection: root.collection ?? 'default',
      wordsVerdict: fs.state === 'ready' || fs.state === 'enriching' || fs.state === 'keyword-only' || fs.state === 'unverified'
        ? `${confirmed ? 'Now' : 'Last known'}${fs.state === 'unverified' ? ' · deletions unverified' : ''}`
        : 'Not yet confirmed; see status',
      processingPercent: progress?.percent,
      verifiedAgo: root.lastVerifiedIsoTime ? this.host_.utilities.formatRelativeTime(root.lastVerifiedIsoTime) : '',
      canReveal: this.isTauri,
      revealUnavailable: !fullPath ? 'The folder path could not be resolved.'
        : fs.state === 'unavailable' ? 'Folder not found. Reconnect the drive or check the folder path.' : '',
      needsVerification: root.deleteDetectionUnverified === true || fs.state === 'unverified',
    };
  }

  private folderIntent(intent: FolderRowIntent, pathHash: string): void {
    if (intent === 'remove') void this.handleRemoveRoot(pathHash);
    else if (intent === 'confirm-remove') { if (this.removeConfirm?.hash === pathHash) void this.confirmRemoveRoot(); }
    else if (intent === 'keep') { if (this.removeConfirm?.hash === pathHash) this.keepRoot(); }
    else if (intent === 'show-failed') revealFailedFiles(pathHash);
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
      if (serial === this.surfaceSerial) this.error = `Could not open the folder in Files. ${error instanceof Error ? error.message : String(error)}`;
    }
  }

  private renderCardsRegion(): TemplateResult {
    const body = activeBodyFor(LIBRARY_CARDS_REGION);
    const folders = this.roots.map(r => this.projectFolder(r));
    if (!body) return html`<div class="cards" role="list">${repeat(folders, f => f.pathHash, f => renderFolderRow(f, {
      expanded: this.expandedFolders.has(f.pathHash ?? ''), enabled: true, host: this.host_,
      toggle: () => this.toggleFolder(f.pathHash ?? ''),
      invoke: intent => this.folderIntent(intent, f.pathHash ?? ''),
    }))}</div>`;
    return html`<jf-declared-surface .declaration=${body} .data=${{ folders }} .enabled=${true}
      @jf-folder-card-toggle=${(e: CustomEvent<{ pathHash: string }>) => this.toggleFolder(e.detail.pathHash)}
      @jf-folder-card-remove=${(e: CustomEvent<{ pathHash: string }>) => this.folderIntent('remove', e.detail.pathHash)}
      @jf-folder-card-show-failed=${(e: CustomEvent<{ pathHash: string }>) => this.folderIntent('show-failed', e.detail.pathHash)}
      @jf-folder-card-reveal=${(e: CustomEvent<{ pathHash: string }>) => this.folderIntent('reveal', e.detail.pathHash)}
      @jf-folder-card-reindex=${(e: CustomEvent<{ pathHash: string }>) => this.folderIntent('reindex', e.detail.pathHash)}
      @jf-folder-card-confirm-remove=${(e: CustomEvent<{ pathHash: string }>) => this.folderIntent('confirm-remove', e.detail.pathHash)}
      @jf-folder-card-keep=${(e: CustomEvent<{ pathHash: string }>) => this.folderIntent('keep', e.detail.pathHash)}
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
      const observation = await readIndexedRoots(path => this.doFetch(path), !live, this.apiBase);
      if (serial !== this.refreshSerial) return false;
      if (!observation) {
        if (!live) this.error = getIndexedRoots().error;
        return false;
      }
      this.lastKnownFailed = { ...observation.lastKnownFailed };
      this.resolvedPaths = { ...this.resolvedPaths, ...observation.paths };
      this.roots = withRootCounts(observation.roots, this.resolvedPaths);
      this.rootsObserved = true;
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
    // DE1 — the band under this source asks; its verb runs the removal ({@link confirmRemoveCollection}).
    if (this.collectionConfirm === source.collection) {
      await this.updateComplete;
      this.renderRoot.querySelector<ConfirmBand>('.other-source-confirm jf-confirm-band')?.focusKeep();
      return;
    }
    this.collectionConfirm = source.collection;
  }

  private async confirmRemoveCollection(source: OtherSource): Promise<void> {
    if (this.collectionConfirm !== source.collection) return;
    this.collectionConfirm = null;
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
      emitEphemeralToast({ classId: 'core.own-action', message: `Removed collection ${source.collection}`, severity: 'success' });
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

  /** DE1 — Cancel rebuild asks in place, under the rebuild it would stop. */
  private requestCancelReindex(): void {
    if (!this.reindexCancelKey || this.reindexCancelBusy || this.reindexCancelRecorded || this.gapDecisionBusy) return;
    if (this.cancelRebuildConfirm) {
      void this.updateComplete.then(() => this.renderRoot.querySelector<ConfirmBand>('.gap-decision jf-confirm-band')?.focusKeep());
      return;
    }
    this.cancelRebuildKey = this.reindexCancelKey;
    this.cancelRebuildConfirm = true;
  }

  private async cancelCurrentReindex(): Promise<void> {
    const key = this.reindexCancelKey;
    const asked = this.cancelRebuildKey;
    this.cancelRebuildConfirm = false;
    this.cancelRebuildKey = null;
    // The band answered for the rebuild it named; a different rebuild is not cancelled by it.
    if (!key || key !== asked || this.reindexCancelBusy || this.reindexCancelRecorded || this.gapDecisionBusy) return;
    const attemptSerial = ++this.reindexCancelAttemptSerial;
    if (!this.isConnected) return;
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

  private async readExcludesPolicy(): Promise<ExcludesPolicy> {
    const response = await this.doFetch('/api/indexing/excludes', { signal: AbortSignal.timeout(10_000) });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return excludesPolicySchema.parse(await response.json());
  }

  private async loadExcludesPolicy(): Promise<void> {
    this.excludesPolicy = null;
    try {
      this.excludesPolicy = await this.readExcludesPolicy();
    } catch {
      // Keep effects unavailable until a later observation succeeds.
    }
  }

  /**
   * R17 — what a read that would replace the shown rules and their witness began with: the draft, the
   * queue and the witness. A read is applied only if all three are still what they were.
   */
  private skipEditorMark(): string {
    return JSON.stringify([this.excludesText, this.excludesLiterals, this.skipQueue.version, this.excludesWitness]);
  }

  private async loadExcludes(): Promise<void> {
    if (this.excludesBusy || this.skipQueue.snapshots.length) return;
    if (!this.excludesLoadStarted && await this.restoreUnsettledSkipRules()) return;
    this.excludesLoadStarted = true;
    this.excludesReading = true;
    const mark = this.skipEditorMark();
    try {
      const body = await readSettingsObservation((path, init) => this.doFetch(path, init));
      // A change captured or saved meanwhile, or text typed meanwhile, is not replaced by the list read
      // before it (R13, R17).
      if (this.skipQueue.snapshots.length || (this.excludesLoaded && this.excludesDirty) || this.skipEditorMark() !== mark) return;
      const entries = body.ui?.excludePatterns ?? [];
      this.excludesSavedText = entries.filter((entry) => typeof entry === 'string').join('\n');
      this.excludesSavedLiterals = entries.filter((entry): entry is ExactPathExclusion => typeof entry !== 'string');
      this.excludesLiterals = [...this.excludesSavedLiterals];
      this.excludesText = this.excludesSavedText;
      this.excludesConflict = false;
      this.excludesConflictSaved = null;
      this.excludesRuleProblem = null;
      this.excludesError = null;
      this.excludesResult = null;
      this.excludesNotice = '';
      this.excludesInvalidPatterns = body.ui?.invalidExcludePatterns ?? [];
      this.excludesWitness = body.witness;
      this.excludesLoaded = true;
      this.excludesLoadFailed = false;
      await this.loadExcludesPolicy();
      this.reconsiderRemovalAsk();
    } catch {
      this.excludesLoaded = false;
      this.excludesLoadFailed = true;
      this.excludesWitness = null;
    } finally {
      this.excludesReading = false;
    }
  }

  /** R24 — what is not settled yet, kept in this window's sessionStorage; removed once nothing is. */
  private keepUnsettledSkipRules(): void {
    const snapshots = this.skipQueue.snapshots;
    const kept = keepUnsettled(UNSETTLED_SKIP_RULES, snapshots.length && this.excludesWitness ? {
      base: { text: this.excludesSavedText ?? '', literals: this.excludesSavedLiterals, witness: this.excludesWitness },
      snapshots, attempt: this.excludesAttempt, undoSerial: this.skipUndoSerial } : null);
    const lost = snapshots.length > 0 && !(kept && this.excludesWitness);
    if (lost !== this.skipCheckpointLost) { this.skipCheckpointLost = lost; this.requestUpdate(); }
  }

  /**
   * R24 — after a reload, the saves this window had not settled come back as they were: on the list and
   * revision they were based on, the latest one as the draft. An uncertain write is sent again first
   * with its same key and bytes; the rest wait for Try again ("Your changes are not saved yet").
   */
  private async restoreUnsettledSkipRules(): Promise<boolean> {
    const kept = readUnsettledSkipRules();
    if (!kept) return false;
    this.excludesLoadStarted = true;
    this.excludesSavedText = kept.base.text;
    this.excludesSavedLiterals = [...kept.base.literals];
    this.excludesWitness = kept.base.witness;
    const draft = kept.snapshots.at(-1)!;
    this.excludesText = draft.text;
    this.excludesLiterals = [...draft.literals];
    this.excludesAttempt = kept.attempt;
    this.skipUndoSerial = kept.undoSerial;
    this.excludesLoaded = true;
    this.excludesLoadFailed = false;
    this.skipQueue.restore(kept.snapshots, kept.attempt !== null);
    this.excludesReading = true;
    try {
      await this.loadExcludesPolicy();
    } finally {
      this.excludesReading = false;
    }
    if (kept.attempt) await this.skipQueue.settleHead();
    return true;
  }

  /**
   * R13/R26 — text replacing the shown rules (a completed Undo, a list saved elsewhere) keeps the empty
   * fields the person opened at the end; the one that had focus keeps it.
   */
  private replaceDraftText(text: string): void {
    const fields = this.skipFields;
    let open = 0;
    while (open < fields.length && !fields[fields.length - 1 - open]!.trim()) open++;
    const next = [...(text ? text.split('\n') : []), ...fields.slice(fields.length - open)];
    const inputs = [...(this.shadowRoot?.querySelectorAll<HTMLInputElement>('.skip-rule input') ?? [])];
    const active = deepActiveElement();
    const at = inputs.findIndex(input => input === active);
    this.setSkipFields(next);
    // PD4'' — with no empty field kept at the end, no new field from Add a rule is open.
    if (open === 0) this.addedRuleOpen = false;
    if (open === 0 || at < fields.length - open) return;
    const target = next.length - (fields.length - at);
    void this.updateComplete.then(() => {
      const now = deepActiveElement();
      if (now && now !== active && now !== document.body) return;
      this.shadowRoot?.querySelectorAll<HTMLInputElement>('.skip-rule input')[target]?.focus();
    });
  }

  /** fm-07 — the rule fields shown: one per line of the draft, none for an empty list unless one was added. */
  private get skipFields(): string[] {
    return this.excludesText === '' && !this.skipFieldOpen ? [] : this.excludesText.split('\n');
  }

  private setSkipFields(fields: readonly string[]): void {
    this.excludesText = fields.join('\n');
    this.skipFieldOpen = this.excludesText === '' && fields.length > 0;
  }

  /** Drop rule fields; the last edited rule (else the one before it) and a rule's problem follow their field. */
  private dropSkipFields(drop: (rule: string, index: number) => boolean): void {
    const kept: string[] = [];
    const to = this.skipFields.map((rule, index) => drop(rule, index) ? -1 : kept.push(rule) - 1);
    this.lastEditedRule = Math.max(0, ...to.slice(0, this.lastEditedRule + 1));
    const problem = this.excludesRuleProblem;
    if (problem) this.excludesRuleProblem = (to[problem.index] ?? -1) < 0 ? null : { ...problem, index: to[problem.index]! };
    this.setSkipFields(kept);
  }

  /** R25 — focus a rule field (the first when that one is gone), or Add a rule when no rule is shown. */
  private async focusSkipRule(index: number): Promise<void> {
    await this.updateComplete;
    const fields = [...(this.shadowRoot?.querySelectorAll<HTMLInputElement>('.skip-rule input') ?? [])];
    const target = globRules(this.excludesText).length ? fields[index] ?? fields[0] : undefined;
    if (target) target.focus();
    else this.shadowRoot?.querySelector<HTMLElement>('.add-rule')?.focus();
  }

  /** The saved list as one comparable value (glob rules, then exact-path entries). */
  private savedListKey(text: string | null = this.excludesSavedText, literals: unknown[] = this.excludesSavedLiterals): string {
    return JSON.stringify([...globRules(text), ...literals]);
  }

  /**
   * B2' — the ask is about the whole saved list. When that list changes while it is open (this window
   * or another), it is withdrawn and asked again for the new list, while the list still holds a rule
   * added since the ask began; otherwise it is withdrawn.
   */
  private reconsiderRemovalAsk(): void {
    const ask = this.removalAsk;
    if (!ask || ask.list === this.savedListKey()) return;
    const added = globRules(this.excludesSavedText).filter(rule => !ask.base.includes(rule));
    this.removalAsk = added.length && !isOverride(this.excludesPolicy)
      ? { serial: ++this.removalAskSerial, list: this.savedListKey(), base: ask.base } : null;
  }

  /**
   * Passive catch-up after focus or a return to the Library: another window may have saved. It runs
   * only while the editor holds nothing of the user's, and is applied only if that is still true,
   * so a draft or a save attempt keeps its original witness. A failed read changes nothing.
   */
  private async refreshExcludes(): Promise<void> {
    if (this.excludesRefreshRead || !this.excludesClean) return;
    const read = (this.excludesRefreshRead = {});
    const mark = this.skipEditorMark();
    try {
      const [body, policy] = await Promise.all([
        readSettingsObservation((path, init) => this.doFetch(path, init)),
        this.readExcludesPolicy(),
      ]);
      if (this.excludesRefreshRead !== read || !this.excludesClean || this.skipEditorMark() !== mark) return;
      this.adoptObservedList(body.ui?.excludePatterns ?? [], body.witness, body.ui?.invalidExcludePatterns ?? []);
      if (JSON.stringify(policy) !== JSON.stringify(this.excludesPolicy)) { this.excludesResult = null; this.excludesNotice = ''; }
      this.excludesPolicy = policy;
    } catch {
      // The shown list and its witness stay; the next focus or return reads again.
    } finally {
      if (this.excludesRefreshRead === read) this.excludesRefreshRead = null;
    }
  }

  /** A clean editor takes up a list saved elsewhere, with its revision. */
  private adoptObservedList(entries: readonly unknown[], witness: SettingsWitness, invalid: string[]): void {
    const text = entries.filter((entry): entry is string => typeof entry === 'string').join('\n');
    const literals = entries.filter((entry): entry is ExactPathExclusion => typeof entry !== 'string');
    // A shown notice described the list this observation replaces.
    if (this.savedListKey(text, literals) !== this.savedListKey()) { this.excludesResult = null; this.excludesNotice = ''; }
    this.excludesSavedText = text;
    this.excludesSavedLiterals = literals;
    this.replaceDraftText(text);
    this.excludesLiterals = [...literals];
    this.excludesInvalidPatterns = invalid;
    this.excludesWitness = witness;
    this.reconsiderRemovalAsk();
  }

  private settingsWrites: Promise<unknown> = Promise.resolve();

  /**
   * One Library settings write at a time. R23 — every owner holds the chain under one bound (its own,
   * or one started now) that covers its wait for the chain too. At the bound the owner's turn is
   * cancelled (never started if it was still waiting), the chain moves on and the owner is answered
   * with the timeout; its late completion settles nothing.
   */
  private queueSettingsWrite<T>(write: (signal: AbortSignal) => Promise<T>, bound?: AbortSignal): Promise<T> {
    const own = bound ? null : new AbortController();
    const timer = own ? setTimeout(() => own.abort(new Error('Settings request timed out.')), SETTINGS_WRITE_BOUND_MS) : undefined;
    const signal = bound ?? own!.signal;
    const turn = this.settingsWrites.then(() => { signal.throwIfAborted(); return write(signal); });
    const held = new Promise<T>((resolve, reject) => {
      const timedOut = () => reject(signal.reason instanceof Error ? signal.reason : new Error('Settings request timed out.'));
      if (signal.aborted) timedOut(); else signal.addEventListener('abort', timedOut, { once: true });
      turn.then(resolve, reject).finally(() => { signal.removeEventListener('abort', timedOut); clearTimeout(timer); });
    });
    // The next owner waits for this one and every earlier one, even one released before its turn.
    this.settingsWrites = Promise.allSettled([this.settingsWrites, held]);
    return held;
  }

  private async invokeSettingsWrite(write: (operationKey: string, signal: AbortSignal) => Promise<OperationInvocationSuccess>,
      signal: AbortSignal, operationKey: string = createOperationKey()): Promise<void> {
    const result = await write(operationKey, signal);
    // Past the bound the chain has moved on: this late answer settles nothing.
    if (signal.aborted) return;
    const receipt = settingsV2Schema.pick({ witness: true }).safeParse({ witness: result.structuredData?.witness });
    const witness = receipt.success ? receipt.data.witness : null;
    // Only this write's direct successor can advance a retained draft. A missing receipt
    // or an intervening writer leaves its original witness to the normal conflict path.
    if (witness && this.excludesWitness && !this.excludesAttempt && !this.excludesConflict
        && witness.acceptedRevision === this.excludesWitness.acceptedRevision + 1
        && witness.lastCommittedOperationKey === operationKey) {
      this.ownSuccessor(this.excludesWitness, witness);
      this.excludesWitness = witness;
    }
  }

  /** The reading-hours editor's rules hold across Library's own direct successor write too. */
  private ownSuccessor(before: SettingsWitness, after: SettingsWitness): void {
    this.shadowRoot?.querySelector<IndexingRulesEditor>('jf-indexing-rules-editor')?.adoptOwnSuccessor(before, after);
  }

  /** The toolbar's (and a hold card's) Pause or Resume: bounded like every chain owner (R23). */
  private readonly saveSettingsOperation = (write: (operationKey: string, signal: AbortSignal) => Promise<OperationInvocationSuccess>): Promise<void> =>
    this.queueSettingsWrite(signal => this.invokeSettingsWrite(write, signal));

  /**
   * Reading-hours writes carry their own base revision (B6') and their own key, so an uncertain one is
   * sent again unchanged (R8); the shared write chain keeps one Library settings write in flight. An
   * uncertain skip-rule write does not hold them: its own retry rebases across them (B11). The editor's
   * bound holds for its wait for the chain too (R23).
   */
  private readonly saveIndexingRulesWithSettings = (write: (operationKey: string) => Promise<OperationInvocationSuccess>,
      operationKey: string, bound: AbortSignal): Promise<void> =>
    this.queueSettingsWrite(signal => this.invokeSettingsWrite(write, signal, operationKey), bound);

  /**
   * Send one captured snapshot (R11), never the live text. The head's attempt whose outcome is unknown
   * is sent again with its same key and bytes. A refusal because the revision moved is resolved by
   * reading the saved list: still this snapshot's base (the list saved before it) means another
   * setting was saved, so the same snapshot is sent again on the new revision (B11); changed means
   * "Changed elsewhere", and the queue waits for the person's choice.
   *
   * R22/R23 — the whole write (its wait for the shared settings chain, every send, retry, admission wait
   * and the re-read after a refusal) runs under one bound; at it the write is cancelled, the chain is
   * freed and its outcome is unknown: the snapshot stays queued and a sent attempt keeps its key and
   * bytes. A late answer settles nothing.
   */
  private async sendSkipRules(next: SkipSnapshot): Promise<SendOutcome> {
    const bound = new AbortController();
    const timer = setTimeout(() => bound.abort(new Error('Settings request timed out.')), SETTINGS_WRITE_BOUND_MS);
    try {
      return await this.queueSettingsWrite(signal => next.undo ? this.sendSkipUndo(next, next.undo, signal) : this.sendSkipList(next, signal),
        bound.signal);
    } catch (err) {
      if (!bound.signal.aborted) throw err;
      // The bound was reached while waiting for the chain or before the write's own answer: unknown.
      return 'uncertain';
    } finally {
      clearTimeout(timer);
    }
  }

  private async sendSkipList(next: SkipSnapshot, bound: AbortSignal): Promise<SendOutcome> {
    if (!this.excludesLoaded || !this.excludesWitness || this.excludesConflict) return 'stopped';
    for (let rebase = 0; rebase < 3; rebase++) {
      this.excludesAttempt ??= createSettingsAttempt({ ui: { excludePatterns: [...globRules(next.text), ...next.literals] } }, this.excludesWitness);
      const attempt = this.excludesAttempt;
      // R24 — kept before it is sent: a reload from here on settles it with this key and these bytes.
      this.keepUnsettledSkipRules();
      const before = { text: this.excludesSavedText ?? '', literals: [...this.excludesSavedLiterals] };
      try {
        const result = await executeSettingsAttempt((path, init) => this.doFetch(path, init), attempt, { signal: bound });
        this.ownSuccessor(attempt.witness, result.witness);
        this.excludesWitness = result.witness;
        this.excludesAttempt = null;
        this.excludesError = null;
        this.savedSkipRules(before, next, result.witness);
        return 'saved';
      } catch (err) {
        const code = err instanceof SettingsAttemptError ? err.code : '';
        const state = err instanceof SettingsAttemptError ? err.response?.['state'] : undefined;
        const terminal = state === 'FAILED' || state === 'CANCELLED' || ['VERSION_CONFLICT', 'SETTINGS_READ_ONLY', 'INVALID_REQUEST',
          'INVALID_PATH', 'OPERATION_KEY_INVALID', 'OPERATION_KEY_REUSED', 'OPERATION_KEY_EXPIRED'].includes(code);
        // An uncertain save retains its exact key and bytes until it is sent again.
        this.excludesError = err instanceof Error ? err.message : String(err);
        if (!terminal) return 'uncertain';
        this.excludesAttempt = null;
        if (code === 'VERSION_CONFLICT') {
          const observed = await this.observeSavedList(bound);
          if (!observed && bound.aborted) return 'uncertain';
          if (!observed) return 'stopped';
          if (observed.key === this.savedListKey(next.text, [...next.literals])) {
            this.excludesWitness = observed.witness;
            this.savedSkipRules(before, next, observed.witness, 'observed');
            this.excludesError = null;
            return 'saved';
          }
          if (observed.key === this.savedListKey()) {
            // Only other settings moved: the same snapshot is sent again on the new revision.
            this.excludesWitness = observed.witness;
            this.excludesError = null;
            continue;
          }
          this.excludesConflict = true;
          this.excludesConflictSaved = { text: observed.text, literals: observed.literals, witness: observed.witness };
          this.excludesError = null;
          return 'stopped';
        }
        if (code === 'INVALID_REQUEST' || code === 'INVALID_PATH') {
          const count = globRules(next.text).length + next.literals.length;
          const plain = count > 512 ? `At most 512 skip rules can be saved; this list has ${count}. Remove some to save your changes.`
            : 'These skip rules could not be saved. Check the rule you changed last.';
          this.excludesError = this.developer && this.excludesError ? `${plain} (${this.excludesError})` : plain;
          return 'dropped';
        }
        // Not saved, and nothing more can be sent on what this window knows: read the saved list again.
        this.dropSkipQueue();
        this.excludesLoaded = false;
        this.excludesLoadFailed = true;
        this.excludesWitness = null;
        return 'stopped';
      }
    }
    this.excludesError = 'Your skip rules could not be saved: settings kept changing. Leave the field again to retry.';
    return 'stopped';
  }

  /**
   * B5' — an Undo is sent on the revision of the save it reverses, so any settings save since refuses
   * it and the Undo closes. One that waited behind a later skip-rule change closes without a write; one
   * whose answer was lost is sent again with its same key and bytes before anything newer.
   */
  private async sendSkipUndo(next: SkipSnapshot, undo: NonNullable<SkipSnapshot['undo']>, bound: AbortSignal): Promise<SendOutcome> {
    const waiter = this.undoWaiter?.serial === undo.serial ? this.undoWaiter : null;
    const answer = (error?: Error) => {
      if (!waiter || this.undoWaiter !== waiter) return;
      this.undoWaiter = null;
      if (error) waiter.reject(error); else waiter.resolve();
    };
    if (!this.excludesAttempt) {
      if (undo.serial !== this.skipUndoSerial) {
        answer(new ReversalClosedError('a later skip-rule change replaced this one.'));
        return 'dropped';
      }
      this.excludesAttempt = createSettingsAttempt({ ui: { excludePatterns: [...globRules(next.text), ...next.literals] } }, undo.witness);
    }
    const attempt = this.excludesAttempt;
    this.keepUnsettledSkipRules();
    const before = { text: this.excludesSavedText ?? '', literals: [...this.excludesSavedLiterals] };
    try {
      const result = await executeSettingsAttempt((path, init) => this.doFetch(path, init), attempt, { signal: bound });
      this.ownSuccessor(attempt.witness, result.witness);
      this.excludesWitness = result.witness;
      this.excludesAttempt = null;
      this.excludesError = null;
      this.skipUndoSerial++;
      this.savedSkipRules(before, next, result.witness, 'undone');
      // R13 — the editor follows the Undo only if, now, it still shows the list the Undo was based on
      // and nothing newer is captured: text typed meanwhile stays and is saved when its field is left.
      if (this.skipQueue.snapshots.length === 1
          && this.savedListKey(this.excludesText, this.excludesLiterals) === this.savedListKey(before.text, before.literals)) {
        this.replaceDraftText(next.text);
        this.excludesLiterals = [...next.literals];
      }
      answer();
      return 'saved';
    } catch (err) {
      const code = err instanceof SettingsAttemptError ? err.code : '';
      const state = err instanceof SettingsAttemptError ? err.response?.['state'] : undefined;
      if (code === 'VERSION_CONFLICT') {
        this.excludesAttempt = null;
        answer(new ReversalClosedError('the settings were saved again since, so the earlier skip rules were not restored.'));
        return 'dropped';
      }
      const error = err instanceof Error ? err : new Error(String(err));
      if (state === 'FAILED' || state === 'CANCELLED' || ['SETTINGS_READ_ONLY', 'INVALID_REQUEST', 'INVALID_PATH',
        'OPERATION_KEY_INVALID', 'OPERATION_KEY_REUSED', 'OPERATION_KEY_EXPIRED'].includes(code)) {
        this.excludesAttempt = null;
        answer(error);
        return 'dropped';
      }
      // The answer was lost: the same Undo is settled first, by Undo again or the next save.
      answer(error);
      return 'uncertain';
    }
  }

  /** Every captured skip-rule change is dropped unsent; an Undo among them is closed. */
  private dropSkipQueue(): void {
    const dropped = this.skipQueue.clear();
    this.excludesAttempt = null;
    const waiter = this.undoWaiter;
    if (waiter && dropped.some(snapshot => snapshot.undo?.serial === waiter.serial)) {
      this.undoWaiter = null;
      waiter.reject(new ReversalClosedError('the skip rules were not restored.'));
    }
  }

  private async observeSavedList(signal: AbortSignal): Promise<{ key: string; text: string; literals: ExactPathExclusion[]; witness: SettingsWitness } | null> {
    try {
      const body = await readSettingsObservation((path, init) => this.doFetch(path, init), { signal });
      const entries = body.ui?.excludePatterns ?? [];
      const text = entries.filter((entry): entry is string => typeof entry === 'string').join('\n');
      const literals = entries.filter((entry): entry is ExactPathExclusion => typeof entry !== 'string');
      return { key: this.savedListKey(text, literals), text, literals, witness: body.witness };
    } catch (err) {
      this.excludesError = `Couldn't check the saved skip rules. ${err instanceof Error ? err.message : String(err)}`;
      return null;
    }
  }

  /**
   * After a confirmed save: the saved list moves on, the Undo and the removal ask follow it. A list
   * found saved (`observed`) offers no Undo; an Undo neither offers one nor asks to remove files.
   */
  private savedSkipRules(before: SkipDraft, after: SkipDraft, witness: SettingsWitness,
      how: 'sent' | 'observed' | 'undone' = 'sent'): void {
    this.excludesSavedText = after.text;
    this.excludesSavedLiterals = [...after.literals];
    this.excludesInvalidPatterns = this.excludesInvalidPatterns.filter(pattern => globRules(after.text).includes(pattern));
    this.excludesRuleProblem = null;
    if (how === 'sent') this.offerSkipRuleUndo(before, after, witness);
    const added = globRules(after.text).filter(rule => !globRules(before.text).includes(rule));
    if (this.removalAsk) this.reconsiderRemovalAsk();
    else if (how !== 'undone' && added.length && this.excludesPolicy && !isOverride(this.excludesPolicy)) {
      this.removalOutcome = null;
      this.removalAsk = { serial: ++this.removalAskSerial, list: this.savedListKey(), base: globRules(before.text) };
    }
    this.excludesNotice = isOverride(this.excludesPolicy)
      ? 'Skip rules saved. The saved list takes effect when the override is removed.' : 'Skip rules saved.';
  }

  /** "Changed elsewhere": keep the draft over the list shown, or take the saved list and write nothing. */
  private async resolveSkipConflict(keepDraft: boolean): Promise<void> {
    const saved = this.excludesConflictSaved;
    if (!saved) return;
    this.excludesConflict = false;
    this.excludesConflictSaved = null;
    this.excludesError = null;
    // Either choice settles every change captured before it: none of them is sent.
    this.dropSkipQueue();
    if (keepDraft) {
      // The draft kept is captured now. Consent covers the list shown: it is based on its revision, so
      // a later change is a new conflict.
      this.excludesWitness = saved.witness;
      this.excludesSavedText = saved.text;
      this.excludesSavedLiterals = saved.literals;
      // R25 — the keyboard goes back to the field being edited.
      const saving = this.handleSaveExcludes();
      await this.focusSkipRule(this.lastEditedRule);
      await saving;
      return;
    }
    this.adoptObservedList([...globRules(saved.text), ...saved.literals], saved.witness, []);
    this.excludesRuleProblem = null;
    await this.focusSkipRule(0);
  }

  /**
   * B5' — the latest skip-rule glob change is reversed by saving the rule list as it was. It is bound
   * to that save: a later glob change replaces it, and any other settings save since (another window,
   * reading hours, Pause) moves the revision, so the engine refuses the stale witness and the Undo
   * closes instead of overwriting. Exact-path changes offer no Undo: exact entries may only be kept or
   * removed, never re-added from here. Files removed from search are not brought back by Undo.
   */
  private offerSkipRuleUndo(before: SkipDraft, after: SkipDraft, witness: SettingsWitness): void {
    const added = globRules(after.text).filter(rule => !globRules(before.text).includes(rule));
    const removed = globRules(before.text).filter(rule => !globRules(after.text).includes(rule));
    const serial = ++this.skipUndoSerial;
    if (JSON.stringify(before.literals) !== JSON.stringify(after.literals)) return;
    if (!added.length && !removed.length) return;
    // A later change is already captured: this one is not the latest.
    if (this.skipQueue.snapshots.length > 1) return;
    const message = added.length + removed.length > 1 ? 'Skip rules changed' : added.length ? 'Skip rule added' : 'Skip rule removed';
    const named = [...added.map(rule => `${rule} is skipped from now on`), ...removed.map(rule => `${rule} is no longer skipped`)];
    const restore: SkipSnapshot = Object.freeze({ text: globRules(before.text).join('\n'), literals: [...after.literals], undo: { serial, witness } });
    offerReversal({
      message,
      detail: `${named.join('; ')}. Undo puts the skip rules back as they were; files removed from search come back when read again.`,
      reversed: 'Skip rules are back as they were',
      // Undo is a save-triggering event: its snapshot joins the queue (R11), behind anything captured earlier.
      reverse: () => new Promise<void>((resolve, reject) => {
        if (serial !== this.skipUndoSerial && !this.skipQueue.snapshots.includes(restore)) {
          void this.skipQueue.resume();
          reject(new ReversalClosedError('a later skip-rule change replaced this one.'));
          return;
        }
        this.undoWaiter?.reject(new ReversalClosedError('a later Undo replaced this one.'));
        this.undoWaiter = { serial, resolve, reject };
        if (this.skipQueue.snapshots.includes(restore)) void this.skipQueue.resume();
        else void this.skipQueue.push(restore);
      }),
    });
  }

  private readonly addFlow = new FolderAddFlow({
    state: this,
    api: () => this.host_,
    changed: () => this.requestUpdate(),
    current: () => { const serial = this.surfaceSerial; return () => serial === this.surfaceSerial && this.isConnected; },
    open: async () => {
      this.activeTab = 'folders'; await this.updateComplete;
      await this.shadowRoot?.querySelector<SurfaceTabs>('jf-surface-tabs')?.updateComplete;
    },
    focus: async target => { await this.updateComplete; await focusFolderAdd(this.renderRoot, target); },
    added: async () => { await this.refresh({ live: true, force: true }); },
  });

  private handleAddFolderClick(): Promise<void> { return this.addFlow.open(); }

  /**
   * DE6/L1 — removal is reversed by adding the same folder again (its files are read again). The
   * offer shows in the pill, then in Earlier, and Ctrl+Z runs it; it does not depend on the Library
   * staying open. Other windows and a reload offer the same from the engine's removal record.
   */
  private offerAddAgain(path: string, collection: string | undefined, nested: number): void {
    const name = folderName(path);
    const host = this.host_;
    offerReversal({
      message: `Removed ${name} from your library`,
      detail: `${path}. Adding it again reads its files again${nested ? `; the ${nested === 1 ? 'folder' : `${nested} folders`} inside it must be added separately` : ''}.`,
      actionLabel: `Add ${name} again`,
      restoresFolder: path,
      reversed: `Added ${name} again. Reading its files.`,
      // L20 — a folder another window (or the Library) already added back closes this offer: it says
      // so and sends no add. An overlap or a missing folder is reported by the add and can be retried.
      reverse: async () => {
        const preview = await host.data.fetch('/api/indexing-roots/preview', { method: 'POST',
          headers: { 'content-type': 'application/json' }, body: JSON.stringify({ path }) })
          .then(res => res.ok ? res.json() as Promise<{ alreadyWatched?: unknown }> : null).catch(() => null);
        if (preview?.alreadyWatched === true) throw new ReversalClosedError(`${name} is already back in your library.`);
        const result = await host.data.invokeOperation(OP_ADD, collection && collection !== 'default' ? { path, collection } : { path });
        if (!result.success) throw new Error(result.message || 'Add refused');
      },
    });
  }

  /** The band's words for removing one folder (DE1, ref-06). */
  private removalPlan(pathHash: string): { heading: string; consequence: string; actionLabel: string } | null {
    const plan = this.removeConfirm;
    if (!plan || plan.hash !== pathHash || !this.roots.some(root => root.pathHash === pathHash)) return null;
    return { heading: plan.heading, consequence: plan.consequence, actionLabel: plan.actionLabel };
  }

  /**
   * DE1 — Remove asks in place: the band under the folder (or under the problem that offered it)
   * names what leaves search, what stays on disk, and the way back. Affected registrations are
   * checked first; the removal itself runs only from the band's verb ({@link confirmRemoveRoot}).
   */
  private async handleRemoveRoot(pathHash: string, origin: 'row' | 'attention' = 'row'): Promise<void> {
    if (this.removingHashes.has(pathHash) || this.confirmingHashes.has(pathHash)) return;
    if (this.removeConfirm?.hash === pathHash) {
      // Asked again while it is asking: bring the answer back into view instead of a second band.
      this.removeConfirm = { ...this.removeConfirm, origin };
      await this.updateComplete;
      this.renderRoot.querySelector('jf-confirm-band')?.focusKeep();
      return;
    }
    if (!this.roots.some(root => root.pathHash === pathHash)) { this.error = 'This folder is no longer in your library.'; return; }
    const resolved = this.resolvedPaths[pathHash];
    if (!resolved) {
      this.error = 'Path resolution pending; retry in a moment.';
      return;
    }
    const serial = this.surfaceSerial;
    this.confirmingHashes.add(pathHash);
    try {
      // Confirm every legacy registration affected by subtree removal. The wire remains hash-only.
      await Promise.all(this.roots.map(async (root) => {
        const hash = root.pathHash;
        if (!hash || this.resolvedPaths[hash]) return;
        const path = await resolvePathLazy(RESOURCE_ID, hash, { apiBase: this.apiBase });
        if (path && serial === this.surfaceSerial) this.resolvedPaths = { ...this.resolvedPaths, [hash]: path };
      }));
      if (serial !== this.surfaceSerial) return;
      if (!this.roots.some(root => root.pathHash === pathHash)) { this.error = 'This folder is no longer in your library.'; return; }
      if (this.roots.some(root => root.pathHash && !this.resolvedPaths[root.pathHash])) {
        this.error = 'Could not check which watched folders this removal affects. Retry when their paths are available.';
        return;
      }
      const paths = this.roots.map(root => ({ hash: root.pathHash ?? '', path: this.resolvedPaths[root.pathHash ?? ''] ?? '' }));
      const enclosing = paths.find(other => other.hash !== pathHash && this.pathInside(resolved, other.path));
      const nested = paths.filter(other => other.hash !== pathHash && this.pathInside(other.path, resolved));
      if (nested.some(other => this.removingHashes.has(other.hash))) {
        this.error = 'A folder inside this one is being removed. Wait for its outcome, then retry.';
        return;
      }
      const name = folderName(resolved);
      const row = this.roots.find(root => root.pathHash === pathHash);
      const files = formatFileCount(row?.fileCount, row?.fileCountCapped);
      const consequence = enclosing
        ? `Its files stay searchable because the enclosing watched folder ${enclosing.path} covers them. Only this registration and its own watcher are removed.`
        : `${files ? `Its ${files} ${files === '1 file' ? 'leaves' : 'leave'}` : 'Its files leave'} search and any enrichment still running for it is stopped. Your files on disk stay where they are, and adding ${name} again reads it from scratch.`;
      const inner = nested.length ? ` This also removes these watched folders: ${nested.map(other => other.path).join(', ')}.` : '';
      this.error = null;
      this.removeConfirm = { hash: pathHash, origin, resolved, enclosing: !!enclosing, nested: nested.map(other => other.hash),
        heading: `Remove ${name} from your library?`, consequence: `${consequence}${inner}`, actionLabel: `Remove ${name}` };
    } finally {
      if (serial === this.surfaceSerial) {
        this.confirmingHashes.delete(pathHash);
        if (this.error) {
          await this.updateComplete;
          this.renderRoot.querySelector<HTMLElement>('.error-banner')?.focus();
        }
      }
    }
  }

  private keepRoot(): void {
    this.removeConfirm = null;
  }

  /** The band's verb: remove the folder (and the registrations inside it) it named. */
  private async confirmRemoveRoot(): Promise<void> {
    const plan = this.removeConfirm;
    if (!plan) return;
    this.removeConfirm = null;
    const { hash: pathHash, resolved, nested: nestedHashes } = plan;
    if (!this.roots.some(root => root.pathHash === pathHash)) {
      // Removed meanwhile (another window): nothing left to remove here.
      this.actionMessage = `${folderName(resolved)} is no longer in your library.`;
      return;
    }
    if ([pathHash, ...nestedHashes].some(hash => this.removingHashes.has(hash))) return;
    const serial = this.surfaceSerial;
    const collection = this.roots.find(root => root.pathHash === pathHash)?.collection;
    const affected = [pathHash, ...nestedHashes];
    this.removingHashes = new Set([...this.removingHashes, ...affected]);
    this.error = null;
    this.actionMessage = `Removing folder ${resolved}…`;
    try {
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
        forgetIndexedRoots(affected);
        this.roots = this.roots.filter(root => !affected.includes(root.pathHash ?? ''));
        this.actionMessage = plan.enclosing ? 'Folder removed. Its files stay searchable through the enclosing watched folder.' : 'Folder removed from Library and search results.';
        this.offerAddAgain(resolved, collection, nestedHashes.length);
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
        this.removingHashes = new Set([...this.removingHashes].filter(hash => !affected.includes(hash)));
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
    this.reindexOutcome = 'Read again accepted. Completion has not yet been confirmed.';
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
      const outcome = parseWireContract(operationOutcomeViewSchema, await response.json(), 'read again outcome');
      if (serial !== this.reindexReadSerial) return;
      this.reindexOutcomeError = outcome.state === 'failed';
      // The pass changed what each folder holds: read the folders again, counts included, so the rows'
      // file counts are the ones after this pass rather than before it (live refreshes carry no counts).
      if (outcome.state === 'complete' || outcome.state === 'failed') void readIndexedRoots(path => this.doFetch(path), true, this.apiBase);
      if (outcome.state === 'complete') {
        this.reindexOutcome = 'Read again completed for the recorded request. Current reading activity is shown below.';
        this.reindexOutcomeKey = null;
      } else if (outcome.state === 'failed') {
        this.reindexOutcome = `Read again did not complete${outcome.result?.code ? ` (${outcome.result.code})` : ''}. Check folder availability and retry Read again; failed files can also be retried below.`;
        this.reindexOutcomeKey = null;
      } else {
        this.reindexOutcome = `Read again accepted. ${['accepted', 'running'].includes(outcome.state) ? 'Completion has not yet been confirmed.' : 'Its outcome is unavailable; check again before retrying.'}`;
      }
    } catch {
      if (serial === this.reindexReadSerial) this.reindexOutcome = 'Read again accepted. Its outcome could not be read; check again before retrying.';
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
      if (!result.success) throw new Error(result.message || 'Read again refused');
      await this.reindexAccepted(result);
    } catch (err) {
      if (serial !== this.surfaceSerial) return;
      this.error = `Read again was not confirmed. ${this.actionFailure(err)} You can retry Read again.`;
    }
  }

  /**
   * Leaving a skip-rule field saves the draft (below). PD4'' — a field cleared of a rule keeps its row, so
   * rows never move under a press; only the new field from Add a rule, left empty, goes (without a save),
   * after any pointer press in progress has ended. Leaving the window keeps it.
   */
  private leaveSkipRule(event: FocusEvent): void {
    const field = event.target as HTMLElement;
    if (!field.matches('.skip-rule input')) return;
    const inputs = [...(this.shadowRoot?.querySelectorAll('.skip-rule input') ?? [])];
    // A field leaving the page while the shown rules are replaced (a completed Undo) is not a person leaving it.
    if (this.addedRuleOpen && field === inputs.at(-1) && inputs.length === this.skipFields.length) {
      if (this.skipFields.at(-1)?.trim()) this.addedRuleOpen = false;
      else if (event.relatedTarget || document.hasFocus()) {
        if (this.pointerPressing) this.addedRuleLeft = true;
        else this.dropAddedRule();
      }
    }
    void this.handleSaveExcludes();
  }

  /** PD4'' — the new field from Add a rule goes while it is empty and not focused. */
  private dropAddedRule(): void {
    const fields = this.skipFields;
    const inputs = this.shadowRoot?.querySelectorAll('.skip-rule input');
    if (!this.addedRuleOpen || fields.at(-1)?.trim() || (inputs && inputs[inputs.length - 1] === deepActiveElement())) return;
    this.addedRuleOpen = false;
    this.dropSkipFields((_, i) => i === fields.length - 1);
  }

  /**
   * A save-triggering event — leaving a skip-rule field, switching tabs, leaving Library, Try again:
   * the draft as it is now is captured and queued behind every earlier change (R7, R11). A rule that
   * is not complete yet is not captured; its field says why. Typing after returning to a field changes
   * nothing already captured.
   */
  private handleSaveExcludes(): Promise<void> {
    if (!this.excludesLoaded) return Promise.resolve();
    this.excludesNotice = '';
    this.excludesResult = null;
    let snapshot: SkipSnapshot | null = null;
    if (this.excludesDirty) {
      const known = globRules(this.queuedList.text);
      const rules = this.excludesText.split('\n');
      const index = rules.findIndex(rule => { const trimmed = rule.trim(); return trimmed && !known.includes(trimmed) && halfPattern(trimmed); });
      this.excludesRuleProblem = index < 0 ? null : { index, reason: `“${rules[index]!.trim()}” isn't a complete rule yet: ${halfPattern(rules[index]!.trim())}. It is not saved.` };
      if (!this.excludesRuleProblem) snapshot = Object.freeze({ text: globRules(this.excludesText).join('\n'), literals: [...this.excludesLiterals] });
    } else this.excludesRuleProblem = null;
    // Any captured change replaces the Undo of the one before it.
    if (snapshot) this.skipUndoSerial++;
    // An event while a save is in flight only joins the queue; the call that started sending waits for it.
    const sending = this.skipQueue.sending;
    const drain = snapshot ? this.skipQueue.push(snapshot) : this.skipQueue.resume();
    return sending ? Promise.resolve() : drain;
  }

  /** Developer tools only: per-rule match counts on disk; removes nothing. */
  private async handlePreviewExcludes(): Promise<void> {
    // R20 — counting waits until a running removal has finished (main's overlap guard).
    if (this.excludesBusy || this.removalBusy || !this.excludesLoaded || !this.excludesPolicy || this.skipQueue.snapshots.length
        || this.excludesDirty) return;
    this.excludesReading = true;
    this.excludesError = null;
    this.excludesNotice = '';
    this.excludesResult = null;
    try {
      const result = await this.host_.data.invokeOperation(OP_PREVIEW, {});
      if (!result.success) throw new Error(result.message ?? 'Exclusion operation failed');
      const parsed = excludesResultSchema.safeParse(result.structuredData);
      if (!parsed.success || !parsed.data.dryRun || (isOverride(this.excludesPolicy) && !parsed.data.effectivePolicy)) {
        this.excludesNotice = 'Match counts are unconfirmed. Count again before relying on them.';
      } else {
        this.excludesResult = parsed.data;
      }
    } catch (err) {
      this.excludesError = err instanceof Error ? err.message : String(err);
    } finally {
      this.excludesReading = false;
    }
  }

  /**
   * The answer "Remove from search" to one ask, or Try again for one outcome: either names the serial
   * and list it is for (R12). The saved list is read first: if it is no longer that list, nothing runs
   * and the ask is asked again (B2'). The removal carries the list it was consented for, and the engine
   * refuses before removing anything when the rules in effect differ from it (R6). The result updates
   * only its own ask and outcome — an ask raised meanwhile for a newer list stays — and is said in plain
   * words that never claim complete removal from a success receipt alone (B4').
   */
  private async handleApplyExcludes(
      asked: { readonly serial: number; readonly list: string; readonly kind?: RemovalOutcome['kind'] } | null
        = this.removalAsk ?? this.removalOutcome): Promise<void> {
    if (!asked || asked.kind === 'kept' || asked.kind === 'nothing') return;
    const { serial: askSerial, list } = asked;
    if (asked.kind !== undefined && !this.removalBusy && isOverride(this.excludesPolicy)) {
      // R19 — Try again under an override says why nothing runs.
      this.removalOutcome = { kind: 'nothing', serial: askSerial, capped: false, scopes: [], list, detail: OVERRIDE_NO_REMOVAL };
      return;
    }
    if (!list || this.removalBusy || !this.excludesPolicy || isOverride(this.excludesPolicy)) return;
    const returnFocus = this.focusInRemoval();
    this.removalBusy = true;
    const serial = this.surfaceSerial;
    try {
      let observed: Awaited<ReturnType<typeof readSettingsObservation>>;
      let mark = this.skipEditorMark();
      try { observed = await readSettingsObservation((path, init) => this.doFetch(path, init)); }
      catch (err) { this.removalFailed(asked, err); return; }
      const entries = observed.ui?.excludePatterns ?? [];
      if (this.observedListKey(entries) !== list) { this.askAgain(entries, observed, asked, mark); return; }
      let result: Awaited<ReturnType<PluginHostApi['data']['invokeOperation']>>;
      try { result = await this.host_.data.invokeOperation(OP_APPLY, { rules: entries }, { consented: true }); }
      catch (err) {
        if (err instanceof OperationError && err.errorCode === 'EXCLUDES_CHANGED') {
          // Nothing was removed: the rules in effect are no longer the list asked about.
          mark = this.skipEditorMark();
          try { [observed] = await Promise.all([readSettingsObservation((path, init) => this.doFetch(path, init)), this.loadExcludesPolicy()]); }
          catch (readErr) { this.removalFailed(asked, readErr); return; }
          this.askAgain(observed.ui?.excludePatterns ?? [], observed, asked, mark);
          return;
        }
        this.removalFailed(asked, err); return;
      }
      if (this.removalAsk?.serial === askSerial) this.removalAsk = null;
      if (!result.success) { this.removalFailed(asked, new Error(result.message ?? 'The engine did not confirm it.')); return; }
      const parsed = excludesResultSchema.safeParse(result.structuredData);
      if (!parsed.success || parsed.data.dryRun) {
        this.removalOutcome = { kind: 'unsure', serial: askSerial, capped: false, scopes: [], list, detail: '' };
      } else {
        const partial = parsed.data.capped || parsed.data.uncheckedScopes.length > 0;
        this.removalOutcome = { kind: partial ? 'partly' : 'done', serial: askSerial,
          capped: parsed.data.capped, scopes: parsed.data.uncheckedScopes, list, detail: '' };
      }
      if (serial === this.surfaceSerial) await this.refresh();
    } finally {
      this.removalBusy = false;
      if (returnFocus) void this.returnFocusFromRemoval();
    }
  }

  private observedListKey(entries: readonly unknown[]): string {
    return this.savedListKey(entries.filter((entry): entry is string => typeof entry === 'string').join('\n'),
      entries.filter(entry => typeof entry !== 'string'));
  }

  /**
   * B2' — the list changed since it was asked about: nothing runs over an unasked list. The ask
   * answered is withdrawn and, while the list still holds a rule added since the ask began, asked again
   * for it. An override set meanwhile withdraws it (the saved list does not take effect). An ask raised
   * meanwhile for a newer list is its own and stays, as does the outcome a Try again was for (R12).
   */
  private askAgain(entries: readonly unknown[], observed: Awaited<ReturnType<typeof readSettingsObservation>>,
      asked: { readonly serial: number; readonly kind?: RemovalOutcome['kind'] }, mark: string): void {
    if (this.excludesClean && this.skipEditorMark() === mark) {
      this.adoptObservedList(entries, observed.witness, observed.ui?.invalidExcludePatterns ?? []);
    }
    const askSerial = asked.serial;
    if (asked.kind !== undefined) { this.retryForChangedList(entries, asked.serial); return; }
    if (this.removalAsk?.serial !== askSerial) return;
    const base = this.removalAsk.base;
    const added = entries.filter((entry): entry is string => typeof entry === 'string' && !base.includes(entry));
    this.removalOutcome = null;
    this.removalAsk = added.length && !isOverride(this.excludesPolicy)
      ? { serial: ++this.removalAskSerial, list: this.observedListKey(entries), base } : null;
  }

  /**
   * R19 — Try again on an outcome whose list is no longer the saved one always answers visibly: an ask
   * open for a newer list stays and the old outcome goes; otherwise the removal is asked for the list
   * saved now; with nothing to remove, or an override in effect, it says so.
   */
  private retryForChangedList(entries: readonly unknown[], outcomeSerial: number): void {
    if (this.removalOutcome?.serial === outcomeSerial) this.removalOutcome = null;
    if (this.removalAsk) return;
    if (entries.length && !isOverride(this.excludesPolicy)) {
      this.removalAsk = { serial: ++this.removalAskSerial, list: this.observedListKey(entries), base: [] };
      return;
    }
    this.removalOutcome = { kind: 'nothing', serial: outcomeSerial, capped: false, scopes: [], list: this.observedListKey(entries),
      detail: isOverride(this.excludesPolicy) ? OVERRIDE_NO_REMOVAL : 'No skip rules are saved now, so there is nothing to remove from search.' };
  }

  private removalFailed(asked: { readonly serial: number; readonly list: string }, err: unknown): void {
    if (this.removalAsk?.serial === asked.serial) this.removalAsk = null;
    const unreachable = err instanceof OperationError ? ['NETWORK_ERROR', 'SERIALIZATION_ERROR'].includes(err.errorClass)
      || [502, 503, 504].includes(err.httpStatus ?? 0) : (err as { errorClass?: string })?.errorClass === 'NETWORK_ERROR' || err instanceof TypeError;
    this.removalOutcome = { kind: unreachable ? 'unavailable' : 'unsure', serial: asked.serial, capped: false, scopes: [], list: asked.list,
      detail: this.developer ? (err instanceof Error ? err.message : String(err)) : '' };
  }

  private keepRemovableFiles(ask: RemovalAsk): void {
    const returnFocus = this.focusInRemoval();
    if (this.removalAsk?.serial === ask.serial) this.removalAsk = null;
    this.removalOutcome = { kind: 'kept', serial: ask.serial, capped: false, scopes: [], list: ask.list, detail: '' };
    if (returnFocus) void this.returnFocusFromRemoval();
  }

  private focusInRemoval(): boolean {
    const active = deepActiveElement();
    return !!active && !!this.shadowRoot?.querySelector('.removal-slot')?.contains(active);
  }

  /** An answered ask leaves the page; the keyboard returns to the rule that was last left. */
  private async returnFocusFromRemoval(): Promise<void> {
    await this.updateComplete;
    const active = deepActiveElement();
    if (active && active !== document.body && this.shadowRoot?.contains(active)
      && !this.shadowRoot.querySelector('.removal-slot')?.contains(active)) return;
    const rule = this.shadowRoot?.querySelector<HTMLInputElement>(`input[aria-label="Skip rule ${this.lastEditedRule + 1}"]`);
    if (rule) rule.focus();
    else this.shadowRoot?.querySelector<HTMLElement>('.add-rule')?.focus();
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
              Nothing reads them again, so removing a source is permanent.
            </p>
          </div>
        </div>
        ${sources.length > 0
          ? html`<div class="cards">
              ${repeat(sources, source => source.collection, source => this.renderOtherSourceCard(source))}
            </div>`
          : nothing}
        ${truncated
          ? html`<div class="other-sources-note">
              ${icon({ name: 'alert-triangle', size: 12 })}
              <span
                >The search data was too large to scan completely — this list may be missing
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
      <div class="other-source-card" data-focus-row=${`source:${source.collection}`}>
        <span class="card-icon">${icon({ name: 'hard-drive', size: 24 })}</span>
        <div class="card-info">
          <div class="card-path">
            <span data-testid="library-other-source-name">${source.collection}</span>
          </div>
          <div class="card-meta">${formatCount(source.docCount)} ${noun}</div>
        </div>
        <jf-button
          variant="danger"
          label=${`Remove ${source.collection}`}
          .onActivate=${() => void this.handleRemoveCollection(source)}
        >
          ${icon({ name: 'trash-2', size: 14 })} Remove
        </jf-button>
      </div>
      ${this.collectionConfirm === source.collection ? html`<div class="other-source-confirm">
        <jf-confirm-band heading=${`Remove the ${formatCount(source.docCount)} ${noun} added as ${source.collection}?`}
          consequence="They leave search for good: they were added from outside your watched folders, so nothing adds them back. Files on disk are not touched."
          action-label=${`Remove ${source.collection}`}
          @confirm-band-confirm=${() => void this.confirmRemoveCollection(source)}
          @confirm-band-cancel=${() => { this.collectionConfirm = null; }}></jf-confirm-band></div>` : nothing}
    `;
  }

  /** DE1 — a removal asked for from a folder's problem answers in place, under that problem. */
  private attentionBand(hash: string): TemplateResult | typeof nothing {
    const plan = this.removeConfirm?.origin === 'attention' ? this.removalPlan(hash) : null;
    return plan ? html`<jf-confirm-band heading=${plan.heading} consequence=${plan.consequence} action-label=${plan.actionLabel}
      @confirm-band-confirm=${() => void this.confirmRemoveRoot()} @confirm-band-cancel=${() => this.keepRoot()}></jf-confirm-band>` : nothing;
  }

  private attentionItems(): { key: string; title: string; detail?: string; actions?: TemplateResult; band?: TemplateResult | typeof nothing }[] {
    const items: { key: string; title: string; detail?: string; actions?: TemplateResult; band?: TemplateResult | typeof nothing }[] = [];
    for (const root of this.roots) {
      const hash = root.pathHash ?? '';
      const name = folderRowLabel(hash, this.resolvedPaths[hash]).label;
      const status = this.rowStatus(root);
      for (const item of folderAttention(root, status, name)) items.push({ key: item.key, title: item.title,
        detail: item.kind === 'walk' && this.developer && root.walkError && isWalkErrorCode(root.walkError) ? `${item.detail} (${root.walkError})` : item.detail,
        band: item.kind === 'missing' || item.kind === 'walk' ? this.attentionBand(hash) : nothing,
        actions: item.kind === 'missing' ? html`<jf-operation context="button" operation-id="core.reconcile-root" .args=${{ pathHash: hash }} api-base=${this.apiBase}
          @op-success=${() => void this.refresh({ force: true })}></jf-operation>
          <jf-button variant="ghost" label="Remove from library" .disabled=${this.removingHashes.has(hash)}
            .onActivate=${() => this.handleRemoveRoot(hash, 'attention')}>Remove from library</jf-button>`
          : item.kind === 'walk' ? html`<jf-button variant="ghost" .disabled=${this.removingHashes.has(hash)}
          .onActivate=${() => root.walkError?.startsWith('Removal incomplete.') ? this.handleRemoveRoot(hash, 'attention') : this.handleReindexToVerify()}
          >${root.walkError?.startsWith('Removal incomplete.') ? 'Retry removal' : 'Read again'}</jf-button>`
          : item.kind === 'files' ? html`<jf-button variant="ghost" .onActivate=${() => revealFailedFiles(hash)}>Show files</jf-button>`
          : html`<jf-button variant="ghost" label="Read again to verify"
          .disabled=${this.removingHashes.has(hash)} .onActivate=${() => this.handleReindexToVerify()}>Read again to verify</jf-button>` });
    }
    const hold = this.indexingActivity.confirmed ? this.indexProgress : this.lastKnownHold;
    if (hold?.pause) items.push({ key: 'hold', title: hold.activity.summary,
      detail: hold.activity.detail ?? undefined,
      actions: html`<jf-indexing-controls compact .progress=${this.indexingActivity.confirmed ? hold
        : { ...hold, live: this.indexProgress.live, activity: { ...hold.activity, confirmed: false } }} api-base=${this.apiBase}
        .saveWithSettings=${this.saveSettingsOperation}></jf-indexing-controls>` });
    if (this.reindexCancelKey && !['complete', 'failed', 'unknown', 'expired'].includes(this.bulkOutcome?.state ?? '')) items.push({ key: 'rebuild', title: 'Rebuild in progress' });
    for (const item of this.unfinishedForgets?.items ?? []) items.push({ key: `forget:${item.operationKey}`, title: 'Unfinished removal' });
    if (this.bulkOutcome && ['failed', 'unknown', 'expired'].includes(this.bulkOutcome.state)) items.push({ key: 'bulk', title: 'Rebuild outcome needs checking',
      detail: this.bulkOutcome.message ?? undefined, actions: html`<jf-button variant="ghost" .onActivate=${() => this.bulkOutcomeKey ? this.loadGapDecision(this.bulkOutcomeKey) : undefined}>Check outcome</jf-button>` });
    // An unconfirmed poll cannot clear an observed problem. Replace the retained
    // causes only when the shared progress authority confirms an observation.
    if (this.indexingActivity.confirmed) {
      this.lastKnownAttention = new Map(items.map(item => [item.key, item]));
      return items;
    }
    for (const item of items) this.lastKnownAttention.set(item.key, item);
    return [...this.lastKnownAttention.values()].map(item => ({ ...item,
      title: item.title.includes('last known') ? item.title : `${item.title} · last known`,
    }));
  }

  private renderAttention(): TemplateResult {
    return html`<div class="attention">
      ${repeat(this.attentionItems().filter(item => item.key !== 'rebuild' && !item.key.startsWith('forget:')), item => item.key, item => html`
        <section class="attention-item" data-focus-row=${`attention:${item.key}`} aria-label=${item.title}>
          <div class="attention-title"><span>${icon({ name: 'alert-triangle', size: 16 })}</span>${item.title}</div>
          ${item.detail ? html`<p role=${item.key === 'bulk' ? 'alert' : nothing}>${item.detail}</p>` : nothing}
          <div class="attention-actions">${item.actions ?? nothing}</div>
          ${item.band ?? nothing}
        </section>`)}
      ${this.indexingActivity.confirmed && this.indexingActivity.detail && !this.indexProgress.pause
        && this.indexProgress.phase !== 'blocked'
        && (this.indexProgress.phase === 'waiting' || this.indexingActivity.enrichmentDeferred || this.indexingActivity.detail.includes('shares time'))
        ? html`<p class="reading-detail">${this.indexingActivity.detail}</p>` : nothing}
      ${this.renderReindexDecision()}
      ${(this.unfinishedForgets?.items.length ?? 0) > 0 || this.forgetReadStatus === 'unavailable' || this.forgetMessage ? this.renderUnfinishedForgets() : nothing}
    </div>`;
  }

  async focusIndexingRule(control: 'hours' | 'battery'): Promise<void> {
    const serial = ++this.indexingFocusSerial;
    this.activeTab = 'folders';
    this.pendingIndexingFocus = control;
    await this.updateComplete;
    if (this.loading || !this.isConnected || serial !== this.indexingFocusSerial) return;
    this.pendingIndexingFocus = undefined;
    const editor = this.shadowRoot?.querySelector<IndexingRulesEditor>('jf-indexing-rules-editor');
    await editor?.focusIndexingRule(control);
  }

  private renderHeader(): TemplateResult {
    const count = this.attentionItems().length;
    // The person's own files (Help excluded). Unknown own files are never said to be searchable: once
    // the Engine has answered without them (an older Engine), the headline says only what is known.
    const documents = this.ownDocuments;
    const ownUnknown = this.indexProgress.phase === 'indexing' ? this.indexingActivity.summary : 'Checking your folders';
    const headline = this.loading ? 'Loading your Library…'
      : !this.indexProgress.live || this.contactUnconfirmed || this.rootsUnconfirmed ? 'Reading status unconfirmed'
        : count ? `${count} ${count === 1 ? 'thing needs' : 'things need'} your attention`
          : this.provisional ? this.provisionalHeadline
            : !this.indexingActivity.confirmed ? this.indexingActivity.summary
              : ['indexing', 'waiting'].includes(this.indexProgress.phase) ? this.indexingActivity.summary
              : !this.roots.length ? (documents.known && documents.value > 0 || this.otherSources.sources.some(source => source.docCount > 0) ? 'Your files are searchable' : 'Add a folder to start searching')
                : !documents.known ? ownUnknown
                  : documents.value === 0 ? 'Reading your files' : 'Your files are searchable';
    return html`<div class="header"><div class="header-left"><h2>${headline}</h2></div></div>`;
  }

  private meaningText(root: IndexedRootView, status: FolderStatus): string {
    const words = status.state === 'ready' || status.state === 'enriching' || status.state === 'keyword-only' || status.state === 'unverified';
    const fallback = words ? ' · word search is available' : ' · word search availability not known';
    if (!this.indexingActivity.confirmed || this.rootsUnconfirmed || this.provisional || status.state === 'unknown' || status.state === 'unavailable') return `Not known${words ? ' · word search was available at the last check' : fallback}`;
    const stages = this.enrichmentStages;
    if (stages && Object.entries(root.failedStages ?? {}).some(([id, failed]) => failed && (id === 'chunkVectors' ? stages.embedding : stages[id as keyof EnrichmentApplicability]))) return `Some files failed${fallback}`;
    const missingArtifact = stages?.embedding && Object.entries(root.missingArtifactStages ?? {}).some(([id, missing]) => missing && ['embedding', 'chunkVectors'].includes(id));
    const coverage = rootCoverage(root, stages);
    if (coverage?.complete && stages?.embedding && !missingArtifact) return 'On';
    if (this.enrichmentBlocked) return `${stages?.embedding ? 'Blocked' : 'Needs AI'}${fallback}`;
    if (missingArtifact) return `In progress${fallback}`;
    if (status.state === 'enriching' || coverage && !coverage.complete) return `In progress${fallback}`;
    if (stages && !stages.embedding) return `Needs AI${fallback}`;
    return `Not known${fallback}`;
  }

  /**
   * Why a Library action was not confirmed, in the app's words. The Engine's own message names
   * operations ("core.reindex") and codes; those stay behind developer tools.
   */
  private actionFailure(error: unknown): string {
    const message = (error instanceof Error ? error.message : String(error)).trim();
    const unreachable = error instanceof OperationError && ['NETWORK_ERROR', 'SERIALIZATION_ERROR'].includes(error.errorClass);
    const engineWords = /\b[\w-]+\.[\w.-]+|index|HTTP \d/i.test(message) || /\b[A-Z][A-Z0-9]*_[A-Z0-9_]+\b/.test(message);
    const plain = unreachable ? 'The app could not reach the engine.' : engineWords || !message ? 'The engine did not confirm it.'
      : /[.!?]$/.test(message) ? message : `${message}.`;
    return this.developer && plain !== message && message ? `${plain} (${message})` : plain;
  }

  /** Confirmed empty folder list (not merely unread or provisional). */
  private noWatchedFolders(): boolean {
    return this.rootsObserved && this.roots.length === 0 && !this.provisional;
  }

  /** Close the in-place ask without reading anything; the keyboard returns to its opener. */
  private cancelReread(): void {
    this.rereadConfirmOpen = false;
    this.shadowRoot?.querySelector<HTMLButtonElement>('[data-reread]')?.focus();
  }

  /** While a request is in flight the opener is aria-disabled, not disabled, so the focus it was given stays. */
  private async runLibraryAction(operation: 'core.reindex'): Promise<void> {
    if (this.rereadBusy) return;
    this.shadowRoot?.querySelector<HTMLElement>('[data-reread]')?.focus();
    this.rereadConfirmOpen = false;
    this.rereadBusy = true;
    const serial = this.surfaceSerial;
    try {
      const result = await getOperationClient(this.apiBase).invokeWithConsent(operation,
        { args: { force: true } }, { requestConsent: requestAuthorization });
      if (serial !== this.surfaceSerial) return;
      await this.reindexAccepted(result);
    } catch (error) {
      if (serial === this.surfaceSerial) this.error = `Reading was not confirmed. ${this.actionFailure(error)} Check Activity before retrying.`;
    } finally { if (serial === this.surfaceSerial) this.rereadBusy = false; }
  }

  private renderWindowActions(): TemplateResult {
    return html`<div slot="actions" class="window-actions">
      <jf-indexing-controls data-testid="library-indexing-pause" compact toolbar allow-pause .progress=${this.indexProgress} api-base=${this.apiBase}
        .saveWithSettings=${this.saveSettingsOperation}></jf-indexing-controls>
      <jf-button variant="primary" data-add-folder label="Add a folder" .disabled=${this.addBusy}
        .onActivate=${() => this.handleAddFolderClick()}>${icon({ name: 'folder-plus', size: 14 })} Add a folder</jf-button>
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
        @tab-change=${(e: CustomEvent<{ id: string }>) => {
          // Switching tabs leaves the field: the draft is saved.
          if (this.activeTab === 'folders' && e.detail.id !== 'folders') void this.handleSaveExcludes();
          this.activeTab = e.detail.id;
        }}
      >
        ${this.renderWindowActions()}
        <div slot="tab-folders" class="folders-scroll">${this.renderFolders()}</div>
      </jf-surface-tabs>
    `;
  }

  private renderFolders(): TemplateResult {
    const otherDocumentCount = this.otherSources.sources.reduce((sum, source) => sum + source.docCount, 0);
    return html`
      ${this.renderHeader()}
      ${this.activeTab === 'folders' ? html`<jf-interrupted-imports api-base=${this.apiBase} .host_=${this.host_}></jf-interrupted-imports>` : nothing}
      ${this.renderAttention()}
      <div class="action-message" role="status" aria-live="polite">${this.actionMessage ?? ''}</div>
      ${this.reindexOutcome ? html`<div class="action-message" role=${this.reindexOutcomeError ? 'alert' : 'status'}>
        ${this.reindexOutcome}
        ${this.reindexOutcomeKey ? html`<jf-button label="Check reading outcome" .disabled=${this.reindexOutcomeBusy}
          .onActivate=${() => this.checkReindexOutcome()}>Check reading outcome</jf-button>` : nothing}
        ${this.retryWaiting || this.indexProgress.phase === 'blocked' ? nothing
          : html`<p>${this.indexingActivity.summary}${this.indexingActivity.detail ? ` — ${this.indexingActivity.detail}` : ''}</p>`}
      </div>` : nothing}


      ${this.developer && !this.isTauri
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

      ${this.addFlow.renderForm(this.developer)}

      ${this.loading
        ? html`<div class="empty">Loading…</div>`
        : this.roots.length === 0
          // During an observed rebuild the folders are expected to be unreadable; that is said instead.
          ? this.contactUnconfirmed || !this.indexProgress.live || this.rootsUnconfirmed && !(this.provisional && this.rebuilding)
            ? html`<div class="empty">Reading status unconfirmed. Folder details are last known; current status is unconfirmed.</div>`
            : this.provisional && !this.rebuilding
            ? html`<div class="empty">${this.provisionalHeadline} ${!this.indexingActivity.confirmed ? `${this.indexingActivity.summary}. ` : ''}Folder details are unconfirmed.</div>`
            : !this.indexingActivity.confirmed
            ? html`<div class="empty">${this.indexingActivity.summary}. Folder details are last known; current status is unconfirmed.</div>`
            : this.rebuilding
            ? html`<div class="empty">
                Reading your files again… your watched folders will reappear when it finishes.
              </div>`
            : this.otherSources.sources.some(source => source.docCount > 0) || this.ownDocuments.known && this.ownDocuments.value > 0 ? nothing : html`<p class="subtitle">Add a folder to make its files searchable.</p>`
          : this.renderCardsRegion()}


      <div class="library-rules">
      <section class="excludes-section" aria-label="Exclude patterns">
        <div class="excludes-header">
          <div>
            <h3>Skip these files</h3>
            <details class="rules-help"><summary class="disclosure">${disclosureMark()}How rules work</summary><p>
              One glob per line. Bare names like <code>node_modules</code> or
              <code>dist/</code> match at any depth.
              <code>[ab]</code> matches one listed character; <code>[a-z]</code> matches a range;
              <code>[!ab]</code> matches a character not listed.
              <code>{draft,final}</code> matches either alternative.
              These meanings also apply to patterns saved by earlier versions.
            </p>
            <p id="excludes-help">${isOverride(this.excludesPolicy) ? html`While the override is set, it decides what reading skips;
              your rules here are saved and take over when it is removed.` : html`Saving a rule changes what future reading skips.
              Files already read stay searchable unless you choose to remove them. During a rebuild of search data, removal may wait
              until the rebuild finishes. Missing or offline files cannot be checked.`}</p></details>
          </div>
        </div>
            ${isOverride(this.excludesPolicy) && this.excludesPolicy ? html`<div id="excludes-override" data-testid="excludes-override" role="note" tabindex="0"
              aria-label=${`Exclusion override: ${policySource(this.excludesPolicy)}. Effective patterns: ${this.excludesPolicy.patterns.join(', ') || '(none)'}. Your rules are still saved; the saved list takes effect when the override is removed.`}>
              <p>Exclusion override: ${policySource(this.excludesPolicy)} controls which files are skipped.
                Your rules below are still saved; the saved list takes effect when the override is removed.</p>
              <p>Effective patterns:</p><pre>${this.excludesPolicy.patterns.join('\n') || '(none)'}</pre>
            </div>` : nothing}
        ${this.excludesLoaded && !this.excludesBusy && !this.excludesPolicy ? html`<p role="alert">The rules in effect couldn't be checked,
          so files already read can't be removed from here right now.</p>` : nothing}
        ${this.excludesLoadFailed && !this.excludesLoaded && !this.excludesBusy ? html`<p role="alert">Couldn't load your skip rules.
          <button type="button" class="text-action" @click=${() => void this.loadExcludes()}>Try again</button></p>` : nothing}
        ${this.skipQueue.snapshots.length > 0 && !this.skipQueue.sending && !this.excludesConflict && this.excludesLoaded
          ? html`<p role="alert">${this.skipQueue.uncertain ? `Not sure this saved.${this.skipQueue.snapshots.length > 1
            ? ' Your newer changes are not saved yet.' : ''}` : 'Your changes are not saved yet.'}
          <button type="button" class="text-action" @click=${() => void this.handleSaveExcludes()}>Try again</button></p>` : nothing}
        ${this.excludesConflictSaved ? html`<div class="skip-conflict" role="alert">
          <p>Changed elsewhere. Your draft is kept. The saved rules are now:</p>
          <pre>${[...globRules(this.excludesConflictSaved.text), ...this.excludesConflictSaved.literals.map(entry => describeExactPath(entry.literal.path))].join('\n') || '(none)'}</pre>
          <button type="button" class="text-action" @click=${() => void this.resolveSkipConflict(false)}>Use the saved rules</button>
          <button type="button" class="text-action" @click=${() => void this.resolveSkipConflict(true)}>Keep my draft</button>
        </div>` : nothing}
        ${this.excludesInvalidPatterns.length > 0
          ? html`<div role="status"><ul>${this.excludesInvalidPatterns.map((pattern) => html`<li>“${pattern}” has invalid pattern syntax and is matched as a literal name. Correct or remove it.</li>`)}</ul></div>`
          : nothing}
        ${this.excludesLiterals.length ? html`<section aria-label="Exact paths kept out">
          <h4>Exact paths kept out</h4>
          <p>These paths are kept out of future reading. Removing an entry saves at once and permits a later reading pass; it does not add a watched folder or read anything automatically.</p>
          <ul>${this.excludesLiterals.map((entry) => html`<li>
            <span>${entry.literal.scope === 'FOLDER' ? 'Folder' : 'File'}: </span><code class="literal-path">${describeExactPath(entry.literal.path)}</code>
            <jf-button variant="ghost" label=${`Remove keep-out for ${describeExactPath(entry.literal.path)}`}
              .disabled=${!this.excludesLoaded}
              .onActivate=${() => {
                this.excludesLiterals = this.excludesLiterals.filter((current) => current.literal.id !== entry.literal.id);
                void this.handleSaveExcludes();
              }}>Remove keep-out</jf-button>
          </li>`)}</ul>
        </section>` : nothing}
        <div class="skip-rules" role="group" aria-label="Exclude patterns" @focusout=${this.leaveSkipRule}>
          <span id="excludes-saving" class="visually-hidden">A rule is saved when you leave its field.</span>
          ${this.skipFields.map((rule, index, fields) => {
            const problem = this.excludesRuleProblem?.index === index ? this.excludesRuleProblem : null;
            return html`<div class="skip-rule">
            <input type="text" aria-label=${`Skip rule ${index + 1}`}
              aria-describedby=${`excludes-saving excludes-help${isOverride(this.excludesPolicy) ? ' excludes-override' : ''}${problem ? ` rule-problem-${index}` : ''}${this.excludesError ? ' excludes-error' : ''}`}
              aria-invalid=${problem || this.excludesError ? 'true' : 'false'}
              ?disabled=${!this.excludesLoaded}
              .value=${rule} @input=${(event: Event) => {
                const rules = this.skipFields; rules[index] = (event.target as HTMLInputElement).value;
                this.setSkipFields(rules);
                this.lastEditedRule = index;
              }}>
            ${this.addedRuleOpen && index === fields.length - 1 && !rule.trim() ? nothing : html`<jf-button variant="ghost" label=${`Remove skip rule ${index + 1}`}
              .disabled=${!this.excludesLoaded}
              .onActivate=${() => {
                const fromRow = !!this.shadowRoot?.activeElement?.closest('.skip-rule');
                this.setSkipFields(this.skipFields.filter((_, i) => i !== index));
                this.lastEditedRule = Math.max(0, index - 1);
                void this.handleSaveExcludes();
                // PD4' — the keyboard never falls to the page: the next rule's field, else the previous one,
                // else Add a rule. A new empty field from Add a rule is not a rule (it goes when left).
                if (fromRow) {
                  void this.updateComplete.then(() => {
                    const inputs = [...(this.shadowRoot?.querySelectorAll<HTMLInputElement>('.skip-rule input') ?? [])];
                    if (this.addedRuleOpen && !this.skipFields.at(-1)?.trim()) inputs.pop();
                    (inputs[index] ?? inputs[index - 1] ?? this.shadowRoot?.querySelector<HTMLElement>('.add-rule'))?.focus();
                  });
                }
              }}
              >${icon({ name: 'x', size: 14 })}</jf-button>`}
          </div>${problem ? html`<p id=${`rule-problem-${index}`} class="rule-problem" role="alert">${problem.reason}</p>` : nothing}`;
          })}
          <jf-button class="add-rule" variant="ghost" label="Add a rule"
            .disabled=${!this.excludesLoaded}
            .onActivate=${() => {
              // R26 — one empty focused field per press; nothing is awaited, so no press is refused as busy.
              // PD4'' — never a second empty field: an empty one (cleared of its rule, or new) is focused instead.
              const fields = this.skipFields;
              let at = fields.findIndex(rule => !rule.trim());
              if (at < 0) {
                at = fields.length;
                this.setSkipFields([...fields, '']);
                this.addedRuleOpen = true;
              }
              void this.updateComplete.then(() => {
                this.shadowRoot?.querySelectorAll<HTMLInputElement>('.skip-rule input')[at]?.focus();
              });
            }}><span aria-hidden="true">+</span> Add a rule</jf-button>
        </div>
        ${this.developer ? html`<div class="actions">
            <jf-button variant="ghost" .disabled=${!this.excludesLoaded || !this.excludesPolicy || this.excludesBusy || this.removalBusy
              || this.skipQueue.snapshots.length > 0 || this.excludesDirty}
              .onActivate=${() => void this.handlePreviewExcludes()}>Count matching files</jf-button>
          </div>` : nothing}
        ${this.excludesError && !this.skipQueue.uncertain ? html`<p id="excludes-error" role="alert">${this.excludesError}</p>` : nothing}
        ${this.skipCheckpointLost ? html`<p role="status">${MAY_BE_LOST}.</p>` : nothing}
        <div role="status" aria-live="polite" aria-atomic="true" data-testid="excludes-result">
          ${this.excludesResult ? this.renderExcludesResult(this.excludesResult)
            // "Saved" is never said over text that is not saved yet.
            : (this.excludesDirty || this.skipQueue.snapshots.length) && this.excludesNotice.startsWith('Skip rules saved') ? '' : this.excludesNotice}
        </div>
        <div class="removal-slot" aria-live="polite">${this.renderRemoval()}</div>
      </section>
      <jf-indexing-rules-editor api-base=${this.apiBase} .host_=${this.host_} .readWaiting=${this.indexProgress.readWaiting}
        .statusObservation=${this.statusObservation}
        .developer=${this.developer} .saveWithSettings=${this.saveIndexingRulesWithSettings}></jf-indexing-rules-editor>
      </div>
      ${this.developer && !(this.unfinishedForgets?.items.length ?? 0) && this.forgetReadStatus !== 'unavailable' && !this.forgetMessage ? html`<details class="removal-history"><summary>Removal history</summary>${this.renderUnfinishedForgets()}</details>` : nothing}
      <div class="bottom-links">
        ${this.otherSources.sources.length || this.otherSources.truncated ? html`<details><summary>
          ${formatCount(otherDocumentCount)} ${otherDocumentCount === 1 ? 'document' : 'documents'} from other apps${this.otherSources.truncated ? ' · incomplete list' : ''}
        </summary>${this.renderOtherSources()}</details>` : nothing}
        <jf-button variant="ghost" label="Activity" .onActivate=${() => { this.activityOpen = !this.activityOpen; }}>Activity</jf-button>
        ${this.noWatchedFolders() ? nothing : html`<button type="button" class="text-action" data-reread
          aria-disabled=${this.rereadBusy ? 'true' : 'false'}
          aria-expanded=${String(this.rereadConfirmOpen)} aria-controls="reread-confirmation"
          @click=${() => { if (!this.rereadBusy) this.rereadConfirmOpen = true; }}
          @keydown=${(event: KeyboardEvent) => {
            if (event.key !== 'Escape' || event.isComposing || !this.rereadConfirmOpen) return;
            event.preventDefault(); event.stopPropagation(); this.cancelReread();
          }}>Read everything again</button>
        <div id="reread-confirmation" class="reread-confirmation" aria-live="polite">${this.rereadConfirmOpen ? html`
          <section role="region" aria-label="Read every watched folder again?" @keydown=${(event: KeyboardEvent) => {
            if (event.key !== 'Escape' || event.isComposing) return;
            event.preventDefault(); event.stopPropagation(); this.cancelReread();
          }}>
            <p>Read every watched folder again?</p>
            <button type="button" class="text-action" @click=${() => void this.runLibraryAction('core.reindex')}>Read again</button>
            <button type="button" class="text-action" @click=${() => this.cancelReread()}>Cancel</button>
          </section>` : nothing}</div>`}
        ${this.activeTab === 'folders' && (this.developer || this.activityOpen) ? html`<jf-ingestion-summary compact .host_=${this.host_}></jf-ingestion-summary>` : nothing}
      </div>
    `;
  }

  /** Developer tools: per-rule match counts on disk (the dry run); nothing is removed. */
  private renderExcludesResult(result: ExcludesResult): TemplateResult {
    const partial = result.capped || result.uncheckedScopes.length > 0;
    return html`<p>${partial ? 'Partial count' : 'Count complete'}. ${result.matchedFiles} matching files found on disk. Files may not have been read. Nothing was removed.</p>
      ${isOverride(result.effectivePolicy ?? null) && result.effectivePolicy ? html`<p>Effective patterns from ${policySource(result.effectivePolicy)}:</p><pre>${result.effectivePolicy.patterns.join('\n') || '(none)'}</pre>` : nothing}
      ${result.capped ? html`<p>Entry cap reached. Remaining disk scope was not checked.</p>` : nothing}
      ${result.uncheckedScopes.length ? html`<ul>${result.uncheckedScopes.map((scope) => html`<li>${scope.status === 'not_checked' ? 'Not checked' : 'Not removed'}: ${scope.path} — ${scope.reason}</li>`)}</ul>` : nothing}
      <ul>${result.perPattern.map((match) => html`<li>${match.pattern}: ${match.matches} files</li>`)}</ul>`;
  }

  /**
   * What the last answer did, in plain words, and the in-place ask open now (B2', B4'). An outcome for
   * a list that is no longer the saved one names the rules it was for (R12).
   */
  private renderRemoval(): TemplateResult | typeof nothing {
    const ask = this.removalAsk;
    const outcome = this.removalOutcome;
    if (!ask && !outcome && !this.removalBusy) return nothing;
    // R15 — while a removal runs one polite status says so; the outcome replaces it and is read as a whole.
    return html`${this.removalBusy ? html`<p role="status" aria-atomic="true">Removing matching files from search…</p>`
      : outcome ? this.renderRemovalOutcome(outcome) : nothing}${ask ? html`${repeat([ask], item => item.serial, () => html`<section role="region" aria-label=${REMOVAL_ASK} class="removal-ask">
        <p>${REMOVAL_ASK}</p>
        <button type="button" class="text-action" aria-disabled=${this.removalBusy ? 'true' : 'false'}
          @click=${() => { if (!this.removalBusy) void this.handleApplyExcludes(ask); }}>Remove from search</button>
        <button type="button" class="text-action" aria-disabled=${this.removalBusy ? 'true' : 'false'}
          @click=${() => { if (!this.removalBusy) this.keepRemovableFiles(ask); }}>Keep them</button>
      </section>`)}` : nothing}`;
  }

  private renderRemovalOutcome(outcome: RemovalOutcome): TemplateResult {
    if (outcome.kind === 'kept') return html`<p role="status" aria-atomic="true">Files already read stay searchable.</p>`;
    if (outcome.kind === 'nothing') return html`<p role="status" aria-atomic="true">${outcome.detail}</p>`;
    const current = outcome.list === this.savedListKey();
    const rules = current ? 'your skip rules' : (JSON.parse(outcome.list) as unknown[])
      .map(entry => typeof entry === 'string' ? entry : describeExactPath((entry as ExactPathExclusion).literal.path)).join(', ');
    const missing = 'Files that are no longer on disk aren\'t checked and may stay searchable.';
    const retry = html`<button type="button" class="text-action" aria-disabled=${this.removalBusy ? 'true' : 'false'}
      @click=${() => { if (!this.removalBusy) void this.handleApplyExcludes(outcome); }}>Try again</button>`;
    // The result confirms deletion requests (a folder is one), not documents, so no number is said (R10).
    if (outcome.kind === 'done') return html`<p role="status" aria-atomic="true">Done. Files already read that match ${rules} are no longer in search. ${missing}</p>`;
    if (outcome.kind === 'partly') {
      const said = (scope: ExcludesResult['uncheckedScopes'][number]) => scope.status === 'not_removed'
        ? /rebuild/i.test(scope.reason) ? 'Waits for the rebuild to finish' : 'Couldn\'t be removed'
        : /offline|missing|unavailable/i.test(scope.reason) ? 'Offline or missing folder, not checked' : 'Couldn\'t be read, not checked';
      return html`<div role="status" aria-atomic="true"><p>Partly done${current ? '' : ` for ${rules}`}. Some matching files may still be searchable:</p>
        <ul>${outcome.scopes.map(scope => html`<li title=${scope.reason}>${said(scope)}: <span class="literal-path">${scope.path}</span></li>`)}
          ${outcome.capped ? html`<li>Too many files to check them all at once.</li>` : nothing}</ul>
        <p>${missing}</p>${retry}</div>`;
    }
    return html`<p role="alert">${outcome.kind === 'unavailable' ? "Couldn't remove files now: reading isn't available."
      : current ? 'Not sure the files were removed.' : `Not sure the files matching ${rules} were removed.`}
      ${outcome.detail ? html`<small class="error-code">${outcome.detail}</small>` : nothing} ${retry}</p>`;
  }

  private renderReindexDecision(): TemplateResult | typeof nothing {
    if (!this.reindexCancelKey || ['complete', 'failed', 'unknown', 'expired'].includes(this.bulkOutcome?.state ?? '')) return nothing;
    const decision = this.gapDecision;
    const waitingForGaps = this.observedGapKey === this.reindexCancelKey;
    const confirmed = this.indexingActivity.confirmed;
    return html`<section class="gap-decision" aria-label=${waitingForGaps ? 'Migration gaps' : 'Rebuild control'}>
      <h3>${!confirmed ? 'Reading status unconfirmed — rebuild details last observed' : waitingForGaps && decision ? `The new search data is ready, but ${decision.gaps.length} document${decision.gaps.length === 1 ? '' : 's'} couldn't be included`
        : waitingForGaps ? 'The new search data needs your decision' : 'Reading everything again'}</h3>
      ${!confirmed ? html`<p>Rebuild details are last observed; current progress and search data availability are unconfirmed.</p>` : nothing}
      ${waitingForGaps && decision ? html`
        ${confirmed ? html`<p>Your current search data stays available; activation requires your decision to accept this list.</p>` : nothing}
        <ul>${decision.gaps.map((gap) => html`<li title=${gap.reason}>${gap.unitId}: ${rebuildGapReason(gap.reason)}</li>`)}</ul>
      ` : !confirmed ? nothing : waitingForGaps ? html`<p>Reading the migration gap list…</p>`
        : html`<p>Your current search data stays available during the rebuild.</p>`}
      <div class="actions">
        ${waitingForGaps && decision ? html`
        <jf-button label="Use the new search data without them" variant="ghost"
          .disabled=${this.gapDecisionBusy || this.reindexCancelBusy || this.reindexCancelRecorded}
          .onActivate=${() => void this.acceptCurrentGaps()}>Use the new search data without them</jf-button>
        ` : nothing}
        <jf-button label="Cancel rebuild" variant="ghost"
          .disabled=${this.reindexCancelBusy || this.reindexCancelRecorded || this.gapDecisionBusy}
          .onActivate=${() => this.requestCancelReindex()}>Cancel rebuild</jf-button>
      </div>
      ${this.cancelRebuildConfirm && this.cancelRebuildKey === this.reindexCancelKey ? html`<jf-confirm-band
        heading="Cancel this rebuild?" action-label="Cancel rebuild"
        consequence="The unfinished new search data is removed and your current search data stays available; search keeps using it. The work done so far is lost, and starting again rebuilds from the beginning."
        @confirm-band-confirm=${() => void this.cancelCurrentReindex()}
        @confirm-band-cancel=${() => { this.cancelRebuildConfirm = false; this.cancelRebuildKey = null; }}></jf-confirm-band>` : nothing}
      ${this.gapDecisionError ? html`<p role="alert">${this.gapDecisionError}
        ${this.gapDecisionErrorCode && !this.gapDecisionError.endsWith(` (Code: ${this.gapDecisionErrorCode})`) ? html`<small class="error-code">Code: ${this.gapDecisionErrorCode}</small>` : nothing}</p>` : nothing}
      ${this.reindexCancelError ? html`<p role="alert">${this.reindexCancelError}
        ${this.reindexCancelErrorCode && !this.reindexCancelError.endsWith(` (Code: ${this.reindexCancelErrorCode})`) ? html`<small class="error-code">Code: ${this.reindexCancelErrorCode}</small>` : nothing}</p>` : nothing}
      ${this.reindexCancelRecorded ? html`<p role="status">Rebuild cancellation recorded.</p>` : nothing}
    </section>`;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-library-surface')) {
  customElements.define('jf-library-surface', LibrarySurface);
}
