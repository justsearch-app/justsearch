// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 497 — Unified conversation surface.
 *
 * Consolidates Ask + Chat + Extract into one view with affordance-driven
 * per-message shape routing. The user types a message; affordance toggles
 * (Documents, Schema) determine which ConversationShape the backend dispatches.
 * Default (no affordance) → FreeChat. Each message dispatches independently
 * via POST /api/chat/dispatch with {shapeId, ...body}.
 *
 * Conditional rendering: citations panel appears when rag.citation_matches
 * SSE events arrive; JSON output renders when Extract shape is active.
 * The conversation thread is FE-maintained — each message is tagged with the
 * shape that produced its response.
 */

import { html, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { authorizedFetch } from '../api/authorizedFetch.js';
// Tempdoc 621 Phase 1 — the chat window's body styles, extracted to keep this file readable.
import { unifiedChatBodyStyles } from './unifiedChatStyles.js';
import {
  buildRequestBody,
  SHAPE_LABELS,
  RAISE_BUDGET_STEP_TOKENS,
  EMPTY_PREFIX_SENTINEL,
  CONVERSATION_ZONES,
  type ShapeId,
  type ThreadMessage,
} from './unifiedChatRequest.js';
import {
  alignToZoneStyles,
  composeGridStyles,
  subscribeShortViewport,
} from '../primitives/compositionLayout.js';
import { friendlyStreamError } from '../utils/streamError.js';
import { deepActiveElement, isTypingTarget } from '../utils/keyboardHandler.js';
import { composerStyles } from '../components/Composer.js';
import '../components/Composer.js';
import { takePendingSelection, takePendingForceShape, resolveDispatchShape, wireSelectionKind, takePendingAutoRun, compose } from '../utils/compose.js';
import { selectionItemToWirePayload } from '../utils/selectionWire.js';
// Search Thread S1 — the ONE results card (side-effect import registers <jf-results-card>).
import '../components/searchResults/ResultsCard.js';
import type {
  CardSelectionDetail,
  CardHit,
  CardSnapshot,
  SearchProvenance,
} from '../components/searchResults/ResultsCard.js';
// Search Thread S6 (the Reading Stage) — the reading surface (`<jf-document-pane>`), mounted as the
// conversation-zone's 5th column. Replaces the retired `components/InspectorPane.ts` as the visual
// consumer of inspectorState (see that module's header comment).
import '../components/documentPane/DocumentPane.js';
import type { DocumentLineRange } from '../components/documentPane/DocumentPane.js';
import {
  subscribeInspector,
  setOpen as setInspectorOpen,
} from '../state/inspectorState.js';
import type { SearchTrace } from '../../api/generated/index.js';
import {
  getSelection as getCurrentSelection,
  subscribeSelection,
  setSelection as setInternalSelection,
  DEFAULT_CAPABILITIES_BY_KIND,
  type SelectionItem,
} from '../state/selectionState.js';
import { setAiActivity, subscribeAiState, getAiState, type AiState } from '../state/aiStateStore.js';
import { reportLayoutWidth, subscribeWide } from '../state/responsiveState.js';
import { copyToClipboard } from '../utils/clipboardCopy.js';
import { orElse, whenKnown } from '../state/known.js';
import {
  readinessNotice,
  reasonFor,
  isReindexCause,
  warrantsSearchDegradationBanner,
  type ReadinessNoticeView,
} from '../state/readinessNotice.js';
import { verdictTone, type SystemHealthVerdict } from '../state/verdict.js';
import { projectAvailability, unavailableBecause } from '../state/availability.js';
// Tempdoc 738 — the degradation banner's disclosure now projects from the app-wide Simple/Detailed
// authority (uiMode), replacing tempdoc 687's per-cause-set "seen-hash" bookmark.
import { subscribeUiMode, isAdvancedMode } from '../state/uiModeState.js';
import '../components/SystemNotice.js';
import '../components/OpButton.js';
import '../components/Control.js';
// Search Thread D2/D3 (stage S2) — the per-turn ROUTE heuristic + its visible chip.
import { inferRoute, type TurnRoute } from '../state/routeHeuristic.js';
import '../components/RouteChip.js';
import {
  projectUnifiedThread,
  projectLiveAgentActivity,
  terminalAssistantIds,
  PROMINENCE_SCALE,
  TERMINAL_NODE_WEIGHT,
  type ThreadEvent,
  type UnifiedTurnItem,
  type RunSegmentRef,
} from './unifiedThreadProjection.js';
import {
  fetchWorkflowCatalog,
  type WorkflowCatalogEntry,
} from '../../api/registry/WorkflowCatalogClient.js';
import { presentLabel } from '../display/present.js';
import {
  fetchUnifiedThread,
  type ThreadFetchFailureReason,
  type ThreadLifecycle,
} from './unifiedThreadClient.js';
import {
  projectBudget,
  projectContextHorizon,
  type BudgetInput,
} from './budgetProjection.js';

import {
  consumeShapeStream,
  dispatchShapeEventToHandlers,
  type RagMetaPayload,
} from '../../api/streams.js';
import type {
  CitationMatch,
  RetrievalCitation,
} from '../components/chat/CitationsPanel.js';
// Tempdoc 565 §15.B — `Claim` (the RAG accumulation model) relocated to the leaf `citationTypes`
// when `StreamingTextBlock` was retired into the one `MarkdownBlock`/`Citation` renderer.
import type { Claim } from '../components/chat/citationTypes.js';
import {
  getUnifiedChatState,
  subscribeUnifiedChat,
  resetUnifiedChatState,
  type Affordance,
} from '../state/unifiedChatState.js';
// Tempdoc 577 Goal 3 (§3.2/§3.3) — the retrieve BASE tier reuses the FE search store directly
// (pure search via /api/knowledge/search through the one buildSearchIntent seam); it is NOT an LLM
// conversation shape. The window's input drives setQuery for live hits; escalation (Ask/Delegate)
// promotes to the existing rag-ask / agent-run shapes.
import {
  subscribeSearch,
  setQuery as setSearchQuery,
  submitSearch,
  setSearchApiBase,
  recordOpenDisposition,
  subscribeScopeChips,
  addScopeChip,
  removeScopeChip,
  type SearchState,
  type SearchHit,
  type SearchScopeChip,
} from '../state/searchState.js';
import {
  subscribePinnedSearches,
  isPinned,
  pinSearch,
  unpinSearch,
  recordRun,
  type SearchPin,
} from '../state/pinnedSearchState.js';
// Search Thread S3 — the shared scope-chip row renderer (mirrors the facet-chip precedent).
import { renderScopeChips, scopeChipRowStyles } from '../components/scopeChipRow.js';
import { icon } from '../components/Icon.js';
// Search Thread S1 — the why/facet/count/highlight RENDERING moved into the one
// `jf-results-card`; this view keeps only the shared style sheets its remaining
// templates still compose (the card carries its own copies for its shadow root).
import { whyThisResultStyles } from '../components/searchResults/whyThisResult.js';
import { facetChipStyles } from '../components/searchResults/facetChips.js';
import { highlightStyles } from '../components/searchResults/resultRowPresentation.js';
import {
  getFacetSelections,
  subscribeFacetSelections,
  toggleFacetValue,
} from '../state/searchFiltersState.js';
// Tempdoc 561 C-2 (graded continuum): chrome grades on the agency posture (affordance × dial).
import { agencyPosture, postureChrome, deriveAffordance } from '../state/agencyPosture.js';
import { getAutonomyLevel, subscribeAutonomy } from '../substrates/autonomy/index.js';
// Tempdoc 577 §2.14 Root I (#19) — temporal anchoring: relative time on turn boundaries.
import { formatRelative } from '../utils/relativeTime.js';
import '../components/AutonomyDial.js';
// Search Thread S7 (tempdoc decision 4) — the agent search tool card's evidence projection; used
// here only to resolve a `card-open` hit back out of the SAME structuredData the card rendered from.
import { findAgentSearchHit } from '../components/chat/toolSearchCard.js';
// Tempdoc 561 (surface tier): the ONE shared agent controller + the retrospective drawer.
import { getAgentSessionController, subscribeAgentSession } from '../state/agentSessionStore.js';
import { openRetrospectiveAt, toggleRetrospective } from '../state/retrospectiveDrawer.js';
import { toggleSources } from '../state/sourcesDrawer.js';
// Tempdoc 610 §K — the context-inspector drawer (what the last turn saw).
import '../components/ContextInspectorPane.js';
import type {
  InspectorView,
  InspectorPhase,
  InspectorSegment,
} from '../components/ContextInspectorPane.js';
import {
  toggleContextInspector,
  isContextInspectorOpen,
  setContextInspectorView,
} from '../state/contextInspectorDrawer.js';
// Tempdoc 565 §12.3.E — the source-chip row reuses the cross-surface selection store + the filename helper.
import {
  getSelectedSource,
  setSelectedSource,
  subscribeSelectedSource,
  sourceKey,
} from '../state/selectedSource.js';
import {
  filenameOf,
  groundingCoverage,
  answerFrame,
  answerFrameLabel,
  groundingDegraded,
  sourcesAreChunkPrecise,
  coverageHonesty,
  coverageNote,
  isVerifiedProducer,
  type AnswerFrame,
  type CoverageHonesty,
} from '../components/chat/evidenceProjection.js';
// Tempdoc 847 S1 — the ONE `claimMatches` envelope reader. The record→claims/matches conversion and
// the producer gate over it used to be private methods here, which made this view the only place the
// gate existed; search v3 needs the same conversion, so it moved to the shared authority both paths
// read (§2.3). This view delegates and derives nothing of its own from the envelope.
import {
  admittedMatches,
  claimsFromRecord,
  matchesFromRecord,
  readScorer,
} from '../components/chat/recordEvidence.js';
import type { CitationSelectDetail, SourceCoverage } from '../components/chat/citationTypes.js';
// Tempdoc 561 surface tier: the one window is the view for EVERY interaction shape.
import { registerViewFactory, getViewFactory } from '../router/viewFactoryRegistry.js';
import {
  CORE_INTERACTION_SHAPES,
  ONE_WINDOW_MOUNT_TAG,
  type CoreInteractionShapeId,
} from '../plugin-api/coreInteractionShapes.js';

import '../components/chat/CitationsPanel.js';
import '../components/chat/ReasoningBlock.js';
import '../components/chat/CitationHoverCard.js';
import '../components/chat/ConversationHistory.js';
// Tempdoc 561 P-B (body-unification): the agent run renders INLINE in this one conversation body —
// there is no separate <jf-agent-view>. We host the AgentSessionController here and reuse its children.
import {
  AgentSessionController,
  type AgentSource,
  type AgentSentenceCite,
  type ToolCall,
} from '../controllers/AgentSessionController.js';
// Tempdoc 565 §30 — the ONE control-intent seam every run-control affordance dispatches through.
import { dispatchRunControl } from '../controllers/runControlIntent.js';
import { requestSurfaceNavigation } from '../controllers/navigateRequest.js';
import { notifyDraftKeptOnce } from '../controllers/draftKeptHint.js';
import { DraftPersistence } from '../controllers/draftPersistence.js';
import '../components/Button.js';
// Tempdoc 610 — the per-turn ⋯ overflow menu reuses the ONE context-menu primitive
// (rides TransientController for single-open arbitration), not a hand-rolled popover.
import { openContextMenu, type ContextMenuAction } from '../components/ContextMenu.js';
import '../components/chat/ToolCallCard.js';
// Tempdoc 585 §D Phase 2 (D2) — the structured multi-agent handoff card.
import '../components/chat/HandoffCard.js';
// Tempdoc 577 §2.13 #17 — the agent authority-space panel ("what can it do, what will ask first").
import '../components/chat/AgentAuthorityPanel.js';
import '../components/chat/MarkdownBlock.js';
// Tempdoc 565 §12.3.E — the persistent evidence rail reuses the SourcesPane in docked mode (no fork).
import '../components/SourcesPane.js';
import type { Citation } from '../components/chat/MarkdownBlock.js';
import {
  claimsToCitations,
  // Tempdoc 577 Phase 1 — the shared agent-answer citation resolver (one mapping authority).
  resolveAnswerCitations as resolveAgentAnswerCitations,
} from '../components/chat/citationResolve.js';
import type { CitationHoverCard, CitationHoverData } from '../components/chat/CitationHoverCard.js';
import {
  setConversationApiBase,
  exportConversationMarkdown,
  resumeConversation,
  generateConversationTitle,
  createConversationId,
  getRecentSessions,
  recordRecentSession,
  branchConversation,
  fetchMessageIds,
  // Tempdoc 610 Phase B — the loaded conversation list + the pure sibling-set
  // projection drive the inline version pager (no new endpoint).
  subscribeConversationList,
  loadConversations,
  siblingSessionsAt,
  type Conversation,
  // Tempdoc 610 Phase C — effective-context floor (rewind) endpoints.
  setContextFloor,
  clearContextFloor,
  // Tempdoc 610 Phase D — compaction (summarize-then-floor).
  compactContext,
  // Tempdoc 610 §E.2 — edit the compaction summary in place.
  editContextFloorSummary,
  // Tempdoc 610 §E.3 — per-message exclude from the effective context.
  setMessageExcluded,
} from '../state/conversationListStore.js';
// Tempdoc 610 §J.3 — the shared hidden-source store (one source of truth across the chips + rail).
import {
  getExcludedSources,
  setExcludedSources,
  toggleExcludedSource,
  subscribeExcludedSources,
  sourceExcludeKey,
} from '../state/excludedSources.js';
// Tempdoc 609 Phase 3 — per-tab pointer so returning to chat auto-restores the thread this tab was
// viewing (instead of the global most-recent card).
import {
  setLastViewedConversation,
  clearLastViewedConversation,
  readLastViewedConversation,
} from '../controllers/lastViewedConversation.js';
import { ReasoningController, reasoningBlocksFromRecord } from '../controllers/ReasoningController.js';
// Tempdoc 565 §17 — the ONE run-step presentation projection + the ONE run-node primitive. The spine
// node and the trace node compose the descriptor (tone + glyph + label) instead of hand-authoring a
// status dot (no `statusAccent` here any more — that authority is consumed only inside the projector).
import { stepPresentation } from './runStepPresentation.js';
// Tempdoc 621 Phase 5 — the run-spine's pure presentation helpers.
import { computeSpinePositions, spineNodeLabel } from './runSpinePresentation.js';
import { computeSpacedPositions, clusterAdjacent, type PlacedGroup } from '../primitives/adaptiveSpacing.js';
import { NavigationController } from '../primitives/navigation.js';
import '../components/chat/RunNode.js';

/**
 * Tempdoc 814 §D4 (settled parameter) — the spine's aggregation threshold: non-landmark markers whose
 * de-overlapped positions still sit closer than this collapse into one counted cluster badge.
 */
const SPINE_CLUSTER_MIN_GAP_PX = 14;

/**
 * Tempdoc 814 §D3 (settled parameter) — the docked evidence rail is a BOUNDED INDEX, not a scroller:
 * it renders at most this many source cards plus an "Open all · N" row into the sanctioned drawer.
 */
const EVIDENCE_RAIL_MAX_VISIBLE = 3;

/**
 * Search Thread S4-final — a live search FROZEN at the moment of consequence (open/ask/pin). A
 * view-local capture of the searchState snapshot at commit time, rendered as a `jf-results-card`
 * `variant='snapshot'`/`'excerpt'` card. NOT a second search authority: every field is copied
 * verbatim from `SearchState`/`SearchTrace` at the instant of commit, never independently derived
 * (the projection-not-fork discipline) — and it is APPEND-ONLY: a commit is never mutated or
 * removed, only added to (see `commitLiveSearch`).
 */
interface CommittedSearch {
  readonly id: string;
  readonly query: string;
  readonly mode: string;
  readonly matchCount: number;
  readonly resultCount: number;
  readonly docIds: string[];
  readonly executedAt: string;
  /** Up to 20 hits captured at commit time (the card's rows). */
  readonly hits: CardHit[];
}

/**
 * Opaque id for a {@link CommittedSearch}. Prefers `crypto.randomUUID` (happy-dom shims it in
 * tests, per pinnedSearchState.ts's `makePinId` — the same defensive pattern reused here); falls
 * back to a timestamp+random hybrid for older environments.
 */
function makeCommittedSearchId(): string {
  const c = (globalThis as { crypto?: { randomUUID?: () => string } }).crypto;
  if (c?.randomUUID) return `cs-${c.randomUUID()}`;
  return `cs-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}

/**
 * Search Thread Round-2 R1a — drop a single cause bullet that only restates the headline+body (the
 * live-audit finding: the "Reindex required." headline already names the rebuild story, so the sole
 * `index.*_legacy`/`index.schema_mismatch`/`index.embedding_mismatch` cause bullet is a duplicate).
 * Keyed on the reason CODE via `isReindexCause` — not string-matching the wording — per the round-2
 * ruling ("dedup by comparing notice code, not string matching"). Any other single- or multi-cause
 * notice (the generic degraded-capability headlines, which never name a specific code) is unaffected.
 */
function dedupDegradationCauses(
  notice: ReadinessNoticeView,
  verdict: SystemHealthVerdict,
): string[] {
  if (notice.causes.length === 1 && verdict.reasons.length === 1 && isReindexCause(verdict.reasons[0]!)) {
    return [];
  }
  return notice.causes;
}

/**
 * Round-13 review (P3) — the docked rungs' ACTIVE tier was `?data-pressed` only: a CSS hook, invisible
 * to assistive tech, because `jf-control` has no `aria-pressed` passthrough. This project already has
 * the convention for exactly that gap — {@link UnifiedChatView.renderPinToggle} carries the toggle
 * state in the accessible LABEL ("Pin this search" / "Unpin this search"). Same convention here: the
 * label states which rung you are ON, so a screen-reader user can tell the current tier without seeing
 * the highlight. One helper, so the four rungs cannot word it four different ways.
 */
function rungLabel(base: string, active: boolean): string {
  return active ? `${base} (current mode)` : base;
}

/** Tempdoc 836 S2S3-A.1 — read the per-source examination facts off a payload or a record. */
function readSourceCoverage(payload: unknown): SourceCoverage[] {
  if (!payload || typeof payload !== 'object') return [];
  const raw = (payload as { sourceCoverage?: unknown }).sourceCoverage;
  if (!Array.isArray(raw)) return [];
  const out: SourceCoverage[] = [];
  for (const c of raw) {
    if (!c || typeof c !== 'object') continue;
    const r = c as Record<string, unknown>;
    if (
      typeof r.sourceIndex === 'number' &&
      typeof r.windowsConsidered === 'number' &&
      typeof r.windowsScored === 'number'
    ) {
      out.push({
        sourceIndex: r.sourceIndex,
        windowsConsidered: r.windowsConsidered,
        windowsScored: r.windowsScored,
      });
    }
  }
  return out;
}

export class UnifiedChatView extends JfElement {
  static properties = {
    apiBase: { attribute: 'api-base', type: String },
    // Tempdoc 561 surface tier: when mounted via <jf-chat-shape-mount> for a deeplink/resume, the
    // shape-id presets the affordance (the one window is now the view for every interaction shape).
    shapeId: { attribute: 'shape-id', type: String },
    // Tempdoc 561 P-B3: the view-factory forwards host_ to any inner view that declares it (see
    // view-factory.ts §507/508). The agent loop's streamViaHost path requires it for
    // shapeId='core.agent-run', so the one-window agent affordance forwards it to <jf-agent-view>.
    host_: { attribute: false },
    inputDraft: { state: true },
    // Tempdoc 565 §30 — the live-run steer input draft (the DIRECTION authority's interject).
    steerDraft: { state: true },
    // Tempdoc 585 §D Phase 3 (C2) — the time-travel fork edit input (open + draft).
    forkEditing: { state: true },
    forkDraft: { state: true },
    // Tempdoc 610 Phase A — transcript edit-in-place: the user turn being edited
    // (by message id) and its working draft. Distinct from forkEditing/forkDraft,
    // which are the agent-run time-travel fork (585).
    editingMessageId: { state: true },
    editingDraft: { state: true },
    // Tempdoc 610 Phase B — the loaded conversation list + the current
    // conversation's fork pointers feed the inline version pager.
    conversations: { state: true },
    branchParentId: { state: true },
    branchPointId: { state: true },
    // Tempdoc 610 Phase C — the effective-context floor message id (null = full
    // context). Drives the floor divider + out-of-context band.
    contextFloorId: { state: true },
    // Tempdoc 610 Phase D — the compaction summary attached to the floor (null
    // for a plain rewind), an expand toggle, and the in-flight compacting flag.
    contextFloorSummary: { state: true },
    showFloorSummary: { state: true },
    // Tempdoc 610 §E.4 — last turn's prompt-token occupancy (from the chat done payload),
    // fed into the context-budget meter; null until a turn completes.
    contextPromptTokens: { state: true },
    // Tempdoc 610 §I.2 — the per-phase token split (system/conversation/retrieved) for the meter breakdown.
    contextBreakdown: { state: true },
    // Tempdoc 610 §E.2 — in-place editing of the compaction summary.
    editingFloorSummary: { state: true },
    floorSummaryDraft: { state: true },
    // Tempdoc 610 §E.3 — message ids excluded from the effective context (per-message hide).
    excludedMessageIds: { state: true },
    compacting: { state: true },
    schemaDraft: { state: true },
    isStreaming: { state: true },
    streamingText: { state: true },
    errorMessage: { state: true },
    explicitAffordance: { state: true },
    schemaAttached: { state: true },
    thread: { state: true },
    aiState: { state: true },
    showResumePrompt: { state: true },
    // Tempdoc 629 (LAYER) — the resumed conversation's store is encrypted + locked (history 423'd).
    historyLocked: { state: true },
    // Tempdoc 734 round-14 F4 — a send the locked store refused (dispatch answered 423).
    lockedSendNotice: { state: true },
    // Tempdoc 577 §2.13 #17 — the agent authority-space panel toggle.
    showAbilities: { state: true },
    // Search Thread Round-2 R1a — the degradation banner's expand/collapse disclosure. A transient
    // UI panel (closes in settleTransients), mirroring showAbilities/queryTrailOpen.
    degradationBannerExpanded: { state: true },
    // Round-14 finding 12(a) — the run-telemetry band's disclosure, bound so "collapsed by default"
    // is a stated property rather than whatever the `<details>` element was last left in.
    activityRailExpanded: { state: true },
    // Slice 515 FIX-1: docIds carried from askAi navigation, forwarded to
    // RAG dispatch so scoped retrieval actually works. Captured in
    // connectedCallback before unifiedChatState is reset.
    pinnedDocIds: { state: true },
    // Slice 515 FIX-8 — parent's first-message preview surfaced from the
    // backend so the branch banner names which parent this branch came from.
    parentFirstMessagePreview: { state: true },
    // RAG state (active during streaming)
    citations: { state: true },
    sources: { state: true },
    ragMeta: { state: true },
    rewriteNote: { state: true },
    claims: { state: true },
    // Tempdoc 577 Goal 3 — the retrieve base tier's live search snapshot (ephemeral hit-list).
    searchSnapshot: { state: true },
    retrieveSelectedIds: { state: true },
    // Tempdoc 577 Goal 3 §3.9a — facet selections drive the retrieve tier's chips.
    facetSelections: { state: true },
    // Search Thread D2/D3 (stage S2) — the user's explicit per-turn route override (chip click /
    // Ctrl+Enter). null = follow the inferRoute() heuristic guess.
    routeOverride: { state: true },
    // Search Thread D5 (stage S3) — the pinned scope chips (mirrored from the searchState module
    // store). Recoverable task state, not a transient — survives tab switch like selection/facets.
    scopeChips: { state: true },
    pinnedSearches: { state: true },
    // Search Thread S4-final — committed (frozen) searches, oldest first. Recoverable task state
    // (like `thread`/`scopeChips`): these are part of the visible thread history, not in-flight UI —
    // NOT reset in settleTransients.
    committedSearches: { state: true },
    // Search Thread S4-final — the last 8 distinct committed-or-superseded queries this session,
    // newest first. Recoverable (a convenience history), not a transient.
    queryTrail: { state: true },
    // Search Thread S4-final — the query-trail dropdown's open/closed toggle. A transient UI panel
    // (closes in settleTransients), mirroring showAbilities/forkEditing.
    queryTrailOpen: { state: true },
    // Search Thread S6 (the Reading Stage) — the document currently open in the reading pane (+ its
    // optional highlight/chunk line-ranges). Recoverable task state (like `scopeChips`/`thread`), NOT a
    // transient — a return to this surface should still show the document the user was reading, so
    // this is deliberately NOT reset in settleTransients(); the retention gate's transient-name pattern
    // match (`busy`/`loading`/`*Error`/…) doesn't cover `readingDocPath`, so it carries no settle
    // obligation, the same way `scopeChips` needs none.
    readingDocPath: { state: true },
    readingHighlightRange: { state: true },
    readingChunkRange: { state: true },
  };

  declare apiBase: string;
  declare shapeId: string | undefined;
  /** Tempdoc 561 P-B3: forwarded to <jf-agent-view> when the agent affordance is active. */
  declare host_: import('../plugin-api/plugin-types.js').PluginHostApi | undefined;
  declare inputDraft: string;
  // Tempdoc 609 §R (T2.1) — reload-durable composer draft (flush on hide, rehydrate on a fresh mount).
  readonly draftPersist = new DraftPersistence(
    this,
    'unified-chat.composer',
    () => this.inputDraft,
    (v) => {
      this.inputDraft = v;
    },
  );
  /** Tempdoc 565 §30 — the live-run steer input draft (the DIRECTION authority's interject). */
  declare steerDraft: string;
  declare forkEditing: boolean;
  declare forkDraft: string;
  /** Tempdoc 610 Phase A — the user turn currently being edited (message id), or null. */
  declare editingMessageId: string | null;
  /** Tempdoc 610 Phase A — working text for the in-place edit. */
  declare editingDraft: string;
  /** Tempdoc 610 Phase B — loaded conversation list (for the version pager). */
  declare conversations: Conversation[];
  /** Tempdoc 610 Phase B — the current conversation's parent (null on roots). */
  declare branchParentId: string | null;
  /** Tempdoc 610 Phase B — the current conversation's branch point (null on roots). */
  declare branchPointId: string | null;
  /** Tempdoc 610 Phase C — the effective-context floor message id (null = full context). */
  declare contextFloorId: string | null;
  /** Tempdoc 610 Phase D — the compaction summary attached to the floor (null = rewind). */
  declare contextFloorSummary: string | null;
  /** Tempdoc 610 §E.4 — last turn's prompt tokens (context occupancy) for the budget meter. */
  declare contextPromptTokens: number | null;
  /** Tempdoc 610 §I.2 — last turn's per-phase token split for the meter attribution breakdown. */
  declare contextBreakdown: { system: number; conversation: number; retrieved: number } | null;
  /** Tempdoc 610 §E.2 — whether the compaction summary is being edited in place. */
  declare editingFloorSummary: boolean;
  /** Tempdoc 610 §E.2 — the working draft while editing the compaction summary. */
  declare floorSummaryDraft: string;
  /** Tempdoc 610 §E.3 — message ids excluded from the effective context (still shown, not sent). */
  declare excludedMessageIds: Set<string>;
  /** Tempdoc 610 Phase D — whether the floor divider's summary is expanded. */
  declare showFloorSummary: boolean;
  /** Tempdoc 610 Phase D — a compaction request is in flight (LLM summarizing). */
  declare compacting: boolean;
  declare schemaDraft: string;
  declare isStreaming: boolean;
  declare streamingText: string;
  declare errorMessage: string;
  /**
   * Search Thread S5a — the sticky EXPLICIT tier choice (tab click, shape preset, restored
   * session), or null when the tier is derived. The standing `affordance` is a computed
   * projection (see the accessor pair below) — never a stored field.
   */
  declare explicitAffordance: Affordance | null;
  /**
   * S5a (decision 6) — a schema ATTACHMENT is a deliberate act ('+ Schema' on the bar), tracked
   * separately from `schemaDraft` (whose constructor template is a convenience, not an intent).
   * Recoverable: an attachment survives tab switches. Attached ⇒ the tier derives 'extract'.
   */
  declare schemaAttached: boolean;
  declare thread: ThreadMessage[];
  declare citations: CitationMatch[];
  /**
   * Tempdoc 836 S2S3-A.2 — the current answer's coverage facts (what was examined, and what was
   * not), or null when the run reported none. Read by the coverage line so it can distinguish
   * "nothing supports this" from "verification did not run".
   */
  declare coverage: CoverageHonesty | null;
  /** Tempdoc 836 S2S3-A.3 — the per-source examination facts behind the sources panel's third state. */
  declare sourceCoverage: SourceCoverage[];
  declare sources: RetrievalCitation[];
  declare ragMeta: RagMetaPayload | null;
  /** Tempdoc 603 C2 — the current answer's decontextualized standalone question (transparency), or null. */
  declare rewriteNote: { original: string; standalone: string } | null;
  declare claims: Claim[];
  declare aiState: AiState | null;
  /** Tempdoc 577 Goal 3 — the retrieve base tier's live search snapshot (ephemeral hit-list). */
  declare searchSnapshot: SearchState | null;
  /**
   * Search Thread S1 — multi-select set for the retrieve tier's card (mirrors SearchSurface's
   * selectedHitIds; instance @state so the Stage's element retention preserves it across
   * navigation — 609). Selection is retained view state by design, not a transient.
   */
  declare retrieveSelectedIds: ReadonlySet<string>;
  declare facetSelections: Record<string, string[]>;
  /** Search Thread D2/D3 (stage S2) — the user's explicit per-turn route override, or null to follow
   * the {@link inferRoute} heuristic guess. Reset on submit and whenever the draft empties out. */
  declare routeOverride: TurnRoute | null;
  /** Search Thread D5 (stage S3) — the pinned scope chips, mirrored from the searchState module
   *  store (subscribeScopeChips in connectedCallback). Constrains both instant search (via
   *  buildSearchIntent, already unioned in searchState) and AI retrieval (unioned into docIds at
   *  send time — see effectiveDocIds()). */
  declare scopeChips: SearchScopeChip[];
  /** S5b pin-parity — the persisted pinned searches (UserStateDocument via pinnedSearchState). */
  declare pinnedSearches: readonly SearchPin[];
  /** Search Thread S4-final — committed (frozen) searches, oldest first; see {@link CommittedSearch}. */
  declare committedSearches: CommittedSearch[];
  /** Search Thread S4-final — recent-query trail, newest first, deduped, capped at 8. */
  declare queryTrail: string[];
  /** Search Thread S4-final — the query-trail dropdown's open/closed toggle. */
  declare queryTrailOpen: boolean;
  /** Search Thread S6 — the document open in the reading pane (`<jf-document-pane>`), or null when
   *  closed. Set by `handleRetrieveCardOpen`/`handleCommittedCardOpen` (via the shared inspectorState
   *  subscription) and by citation clicks; cleared by the pane's own `pane-close`. */
  declare readingDocPath: string | null;
  /** Search Thread S6 — the passage line-range to highlight in the reading pane (citation deep-links
   *  carry `startLine`/`endLine`; a plain document open carries none). */
  declare readingHighlightRange: DocumentLineRange | null;
  /** Search Thread S6 — the wider containing chunk to tint (no current producer carries this — kept
   *  for parity with DocumentPane's own `chunkRange` prop should a future producer supply it). */
  declare readingChunkRange: DocumentLineRange | null;
  declare showResumePrompt: boolean;
  /** Tempdoc 629 (LAYER) — the resumed conversation is encrypted + locked (history returned 423). */
  declare historyLocked: boolean;
  /**
   * Tempdoc 734 round-14 F4 — the wording for a send the locked store refused: the dispatch answered
   * 423 because the store locked between this composer's render and the submit. Empty unless that
   * happened, so the ordinary locked view (a conversation resumed while locked) is unchanged.
   */
  declare lockedSendNotice: string;
  /** Tempdoc 577 §2.13 #17 — the agent authority-space ("what can it do") panel is open. */
  declare showAbilities: boolean;
  /**
   * Tempdoc 738 — the degradation banner's LOCAL "See details" expand toggle. Default collapsed; the
   * render derives the effective state from disclosure (isAdvancedMode) + severity (see
   * renderDegradationBanner). Tempdoc 687's per-cause-set seen-hash default machinery is removed.
   */
  declare degradationBannerExpanded: boolean;
  /**
   * Round-14 finding 12(a) — the run-telemetry band's disclosure. Developer instrumentation with a
   * disclosure triangle should start closed; this makes that the declared default (and keeps a user's
   * own toggle across re-renders).
   */
  declare activityRailExpanded: boolean;
  /** Tempdoc 738 — re-render the disclosure-gated banner when the Simple/Detailed mode changes. */
  private uiModeUnsubscribe: (() => void) | null = null;
  declare pinnedDocIds: string[];
  declare parentFirstMessagePreview: string | null;
  // Tempdoc 565 §12.3.C + multi-turn fix (A) — the run-trace collapse is PER-SEGMENT: a multi-turn
  // session has one trace segment per run, each independently collapsible. This holds the user's
  // EXPLICIT toggle per segment (keyed by the segment's first-item id); a segment with no entry uses
  // its structural default (open iff it is the trailing/in-flight run — see renderRunTrace). Not a
  // reactive property — the only mutation is the summary click, which calls requestUpdate() itself.
  private runTraceToggles = new Map<string, boolean>();
  // Tempdoc 565 §13.8 P3 — the under-answer source-chip row is a COLLAPSIBLE echo of the docked rail
  // (which owns the full source detail). This holds the user's EXPLICIT per-answer expand choice
  // (keyed by the answer item id, or `__live__` for the streaming block); no entry → the structural
  // default (collapsed when the wide rail is showing the same sources, expanded at narrow). Not a
  // reactive property — the only mutation is the summary click, which calls requestUpdate() itself.
  private sourceChipsToggles = new Map<string, boolean>();
  // Tempdoc 577 §2.14 Root I (#19) — the id of the first live item that follows restored record
  // history (the run/session boundary seam), or null when the timeline is all-record or all-live.
  // Derived during render by {@link mergedTimeline} (the one merge authority); read by the renderer.
  private resumeSeamId: string | null = null;
  // Tempdoc 562: pointer only — no cached message content. The resume preview is derived from the
  // lock-safe backend conversation list at render time, never from a client-side plaintext cache.
  private recentSession: { sessionId: string; timestamp: number } | null = null;
  private abortController: AbortController | null = null;
  readonly reasoning = new ReasoningController(() => this.requestUpdate());
  private sessionId: string;
  private storeUnsubscribe: (() => void) | null = null;
  /** Tempdoc 610 Phase B — conversation-list subscription (drives the version pager). */
  private convListUnsub: (() => void) | null = null;
  private selectionUnsubscribe: (() => void) | null = null;
  private aiStateUnsubscribe: (() => void) | null = null;
  // Tempdoc 577 Goal 3 — the retrieve base tier's search-store subscription.
  private searchUnsub: (() => void) | null = null;
  private facetUnsub: (() => void) | null = null;
  // Search Thread D5 (stage S3) — the scope-chip store subscription.
  private scopeChipsUnsub: (() => void) | null = null;
  private pinnedSearchesUnsub: (() => void) | null = null;
  // Search Thread S6 — the shared inspectorState subscription (the "open a document for reading"
  // signal — see that module's header comment): projects `selected`/`isOpen` onto readingDocPath.
  private inspectorUnsub: (() => void) | null = null;
  // Tempdoc 561 C-2: re-render the graded chrome when the autonomy dial changes (chrome only).
  private autonomyUnsubscribe: (() => void) | null = null;
  /** Tempdoc 610 §J.3 — re-render when the shared hidden-source set changes (e.g. toggled from the rail). */
  private excludedSourcesUnsub: (() => void) | null = null;
  // Tempdoc 561 P-A/P-B (Slice 2): the canonical thread record (GET /api/thread/{id}). When present,
  // the conversation renders the unified interleaved thread (chat turns + agent activity) projected
  // from this ONE record; empty -> fall back to the live this.thread render (offline / pre-fetch).
  private unifiedEvents: ThreadEvent[] = [];
  // Tempdoc 561 P-A/P-A2: the agent runs' typed loop objects (state + Turn/Iteration counts + budget)
  // projected from the record; surfaced in the Activity rail.
  private unifiedLifecycles: ThreadLifecycle[] = [];
  // Tempdoc 727 F-8: fetchUnifiedThread's EMPTY-on-failure fallback is deliberate (the projector must
  // never become an authority — see refreshUnifiedThread), but it was completely silent: a backend
  // error left the live render in place with no hint anything was wrong. This is the out-of-band
  // signal, set on a failed refresh and cleared on the next successful one; it renders a notice, not
  // a substitute for the EMPTY contract.
  private unifiedThreadRefreshFailed: { reason: ThreadFetchFailureReason; detail?: string } | null =
    null;
  // Tempdoc 561 P-A/P-B (Slice 3): the agent run's budget, surfaced from <jf-agent-view> for the
  // secondary Activity rail (the demoted chrome; the conversation stays primary). Null until a run
  // reports budget.
  private agentBudget: BudgetInput | null = null;
  // Tempdoc 561 P-B (body-unification): the agent run is hosted HERE and renders inline in the one
  // thread. Lazily created on first crossing into agent mode (no idle cost otherwise); kept for the
  // view's life so a chat<->agent round-trip is lossless.
  private agentCtrl: AgentSessionController | null = null;
  // Tempdoc 577 Root I (#1d) — one-shot guard for the cross-tab reattach-on-load (see ensureAgentCtrl).
  private reattachChecked = false;
  // Tempdoc 561 surface tier: the agent controller is shared (agentSessionStore); the window only
  // subscribes — it must NOT destroy it on disconnect (the retrospective drawer also reads it).
  private agentSessionUnsub: (() => void) | null = null;
  // Tempdoc 565 §12.3.E — re-render the source chips when the cross-surface selection changes (an inline
  // [n] mark or a rail card was focused), so chip ↔ inline ↔ rail stay in sync.
  private selectedSourceUnsub: (() => void) | null = null;
  // Tempdoc 565 §12.3.E fix F — track the wide breakpoint so the docked evidence rail mounts ONLY when
  // wide (the narrow fallback is the toggle drawer); one SourcesPane instance per surface, not two.
  // 574 F1 — the breakpoint comes from the one responsiveState authority, not a per-instance mql.
  // 798 round 8 — it is the SURFACE box's width, reported below, that the authority decides on: the
  // same box the `@container chat-surface` queries use, so a mount gate and the grid can never
  // disagree about whether the wide layout is in effect.
  private wideZone = true;
  private unsubWide: (() => void) | null = null;
  // Tempdoc 814 §D6 — the BLOCK-axis sibling of `wideZone`: is the window below the one block-axis
  // breakpoint (primitives/compositionLayout.ts)? Chrome that may spend height freely on a tall window
  // yields on a short one. Defaults to NOT short so an unknown viewport (SSR, unit tests) keeps every
  // band's full form — the same unavailable-means-roomy default `wideZone` carries.
  private shortZone = false;
  private unsubShort: (() => void) | null = null;
  private zoneResizeObserver: ResizeObserver | null = null;
  private observedBox: HTMLElement | null = null;
  // Tempdoc 565 §21 — the chat-first Navigation authority. The run-spine's "where am I / how do I move"
  // (POSITION dots · WINDOW box · FOCUS ring · the jump/pin CONTROL) is owned by ONE reading-position
  // model in the NavigationController (`primitives/navigation.ts`), not hand-wired here. renderRunSpine is
  // a pure projection of `this.nav.{fractions,trackPx,viewport,activeId}`; the controller self-manages its
  // observers + scroll listener + lifecycle (hostUpdated/hostDisconnected), mirroring the Adaptivity
  // controllers (OverflowController/DensityController). It is active only in agent mode at the wide
  // breakpoint — 574 F1: the breakpoint is the shared `wideZone` (responsiveState), not a per-instance mql.
  private readonly nav = new NavigationController(this, {
    scrollEl: () => (this.shadowRoot?.querySelector('.conversation') as HTMLElement | null) ?? null,
    spineEl: () => (this.shadowRoot?.querySelector('.run-spine') as HTMLElement | null) ?? null,
    active: () => this.affordance === 'agent' && this.wideZone,
  });
  // 548 §4.5: set when an `answer` verb activated this surface; drives a
  // one-shot auto-send once the prompt is present and the AI is chat-capable.
  private autoRunPending = false;
  private renderTickTimer: number | null = null;
  // Tempdoc 565 §15.C (fix) — true while the workflow shape is mounted but the run hasn't been
  // triggered yet; renders an explicit RUN affordance instead of auto-running on mount.
  private workflowPending = false;
  // Tempdoc 565 §26.C — the workflow PICKER: the launcher projects the `/api/registry/workflows`
  // catalog (replacing the hardcoded `WORKFLOW_ID` const, §25.2). `workflows` is the fetched catalog;
  // `selectedWorkflowId` is the picker's current choice (defaults to the first entry).
  private workflows: WorkflowCatalogEntry[] = [];
  private selectedWorkflowId: string | null = null;
  private boundCiteRefHover = this.onCiteRefHover.bind(this);
  private boundCiteRefLeave = this.onCiteRefLeave.bind(this);
  // Tempdoc 565 §33 — J/K step-nav is a window-level shortcut (the conversation div is not focusable, so
  // a div-scoped @keydown never fired for a real user). Added on connect, removed on disconnect.
  private boundWindowKeydown = this.onConversationKeydown.bind(this);
  // Search Thread D2/D3 (stage S2) — Shell dispatches `jf-focus-composer` on Ctrl+L / '/'; focus the
  // composer textarea. Added on connect, removed on disconnect (mirrors boundWindowKeydown).
  private boundFocusComposer = this.onFocusComposer.bind(this);
  private hoverCard: CitationHoverCard | null = null;
  // Slice 515 FIX-4 — monotonic token to discard stale syncMessageIds
  // responses. Each invocation bumps the token; only the latest one's
  // response is applied to the thread.
  private syncToken = 0;

  constructor() {
    super();
    this.apiBase = '';
    this.degradationBannerExpanded = false;
    this.activityRailExpanded = false;
    this.inputDraft = '';
    this.steerDraft = '';
    this.forkEditing = false;
    this.forkDraft = '';
    this.editingMessageId = null;
    this.editingDraft = '';
    this.conversations = [];
    this.branchParentId = null;
    this.branchPointId = null;
    this.contextFloorId = null;
    this.contextFloorSummary = null;
    this.contextPromptTokens = null;
    this.contextBreakdown = null;
    this.editingFloorSummary = false;
    this.floorSummaryDraft = '';
    this.excludedMessageIds = new Set();
    setExcludedSources([]);
    this.showFloorSummary = false;
    this.compacting = false;
    this.schemaDraft ='{\n  "type": "object",\n  "properties": {}\n}';
    this.isStreaming = false;
    this.streamingText = '';
    this.errorMessage = '';
    this.historyLocked = false;
    this.lockedSendNotice = '';
    // Tempdoc 577 Goal 3 (§3.11) / Search Thread S5a — the window lands DERIVED (no explicit pin):
    // deriveAffordance yields the `retrieve` base tier, the always-available search floor. The old
    // AI-online auto-upgrade to `documents` is gone (decision B14 — capability appearing must not
    // move the user); a restored session with a real affordance still pins explicitly below.
    this.explicitAffordance = null;
    this.schemaAttached = false;
    this.thread = [];
    this.citations = [];
    this.coverage = null;
    this.sourceCoverage = [];
    this.sources = [];
    this.ragMeta = null;
    this.rewriteNote = null;
    this.claims = [];
    this.aiState = null;
    this.searchSnapshot = null;
    this.retrieveSelectedIds = new Set<string>();
    this.facetSelections = {};
    this.routeOverride = null;
    this.scopeChips = [];
    this.pinnedSearches = [];
    this.committedSearches = [];
    this.queryTrail = [];
    this.queryTrailOpen = false;
    this.degradationBannerExpanded = false;
    this.readingDocPath = null;
    this.readingHighlightRange = null;
    this.readingChunkRange = null;
    this.showResumePrompt = false;
    this.pinnedDocIds = [];
    this.parentFirstMessagePreview = null;
    this.sessionId = createConversationId();
    const recent = getRecentSessions();
    if (recent.length > 0 && recent[0]) {
      this.recentSession = recent[0];
      this.showResumePrompt = true;
    }
  }

  override connectedCallback(): void {
    super.connectedCallback();
    // Tempdoc 565 §12.3.E — re-render when the cross-surface source selection changes (chip highlight).
    this.selectedSourceUnsub = subscribeSelectedSource(() => this.requestUpdate());
    // Tempdoc 738 — re-render the disclosure-gated banner when the Simple/Detailed mode changes; the
    // render reads isAdvancedMode() live, so a bare requestUpdate is all that is needed.
    this.uiModeUnsubscribe = subscribeUiMode(() => this.requestUpdate());
    // Tempdoc 565 fix F / 574 F1 — re-render the rail mount when the wide breakpoint is crossed,
    // via the shared responsiveState authority (fires once immediately with the current value).
    this.unsubWide = subscribeWide((wide) => {
      this.wideZone = wide;
      this.requestUpdate();
    });
    // Tempdoc 814 §D6 — the same fan-out on the block axis (fires once immediately with the current
    // value), so a vertical resize across the breakpoint re-renders the height-gated chrome.
    this.unsubShort = subscribeShortViewport((short) => {
      this.shortZone = short;
      this.requestUpdate();
    });
    this.observeSurfaceWidth();
    // Tempdoc 561 surface tier: when this one window is mounted for a specific shape (a deeplink /
    // resume via <jf-chat-shape-mount>), preset the affordance from the shape-id — so every entry
    // point lands HERE in the right mode, not in a separate per-shape view.
    const presetByShape: Record<string, Affordance> = {
      'core.rag-ask': 'documents',
      'core.extract': 'extract',
      'core.agent-run': 'agent',
      // Tempdoc 565 §15.C — the workflow run is a MODE of the one window, rendered through the SAME
      // agent path (one run-render authority): tool cards + the shared approval ceremony + the answer.
      'core.workflow-run': 'agent',
      'core.free-chat': 'none',
    };
    if (this.shapeId && presetByShape[this.shapeId] !== undefined) {
      // S5a — a deep-linked shape is an explicit tier choice (the setter pins it sticky).
      this.affordance = presetByShape[this.shapeId]!;
    }
    // Tempdoc 565 §15.C (fix): mounting the workflow shape arms a one-shot RUN affordance instead of
    // auto-running — the user explicitly triggers the run (no surprising re-run on every mount). The
    // run still streams through the unified agent controller into the one window's run authority.
    // Tempdoc 565 §26.C: the affordance is now a PICKER over the fetched workflow catalog.
    if (this.shapeId === 'core.workflow-run') {
      this.workflowPending = true;
      void this.loadWorkflows();
    }
    const initial = getUnifiedChatState();
    if (initial.query) this.inputDraft = initial.query;
    if (initial.affordance !== 'none') {
      this.affordance = initial.affordance;
    }
    // 548 §4.5: drain the one-shot auto-run flag parked by the IntentRouter's
    // `answer` lowering. maybeAutoRun() fires once the prompt + AI capability
    // are both present (here, in the store subscription, or in the aiState
    // subscription — whichever settles last).
    this.autoRunPending = takePendingAutoRun();
    this.maybeAutoRun();
    // Tempdoc 526 §14.5 T3 — pinnedDocIds is sourced from selectionState's
    // result-set kind. The legacy unifiedChatState.docIds path remains as a
    // URL-restore bridge (bootstrap.ts publishes the restored set into
    // selectionState; we still read selectionState here).
    const refreshDocsFromSelection = (): void => {
      const cur = getCurrentSelection().items[0];
      if (cur && cur.kind === 'result-set') {
        // S5a (decision B14) — the selection carries into sends as docIds; it no longer
        // auto-flips the standing tier out from under the user.
        this.pinnedDocIds = cur.items.map((r) => r.id);
      }
    };
    refreshDocsFromSelection();
    this.selectionUnsubscribe = subscribeSelection(() => {
      if (this.isStreaming) return;
      refreshDocsFromSelection();
      // The mode chip's shape is computed from the LIVE selection (the same computation send()
      // dispatches on), and the selection store is not a Lit reactive property — so a selection
      // change has to ask for the re-render itself or the chip goes stale.
      this.requestUpdate();
    });
    this.storeUnsubscribe = subscribeUnifiedChat((s) => {
      if (this.isStreaming) return;
      if (s.query) this.inputDraft = s.query;
      if (s.affordance !== 'none') {
        this.affordance = s.affordance;
      }
      this.maybeAutoRun();
    });
    // Tempdoc 609 (M1) — do NOT reset the chat store on mount. `query` (composer draft prefill)
    // and `affordance` (mode) are recoverable task state held in the singleton `unifiedChatState`
    // store; resetting here is what discarded a draft on a brief tab switch. Clearing now happens
    // only through the explicit `newConversation()` (New chat) action.
    this.addEventListener('cite-ref-hover', this.boundCiteRefHover as EventListener);
    this.addEventListener('cite-ref-leave', this.boundCiteRefLeave as EventListener);
    // §33 — window-level J/K step-nav (guarded to the agent run + non-input focus inside the handler).
    window.addEventListener('keydown', this.boundWindowKeydown);
    // Search Thread D2/D3 (stage S2) — Ctrl+L / '/' (dispatched by Shell) focuses the composer.
    window.addEventListener('jf-focus-composer', this.boundFocusComposer);

    setConversationApiBase(this.apiBase || '');
    // Tempdoc 610 Phase B — track the loaded conversation list so the inline
    // version pager can resolve a turn's sibling set; fetch it once on connect.
    this.convListUnsub = subscribeConversationList((s) => {
      this.conversations = s.conversations;
    });
    void loadConversations();
    // Tempdoc 609 — auto-restore the conversation this tab was viewing, but ONLY when the thread is
    // empty. Under instance-retention, a same-session tab switch reuses this element with `this.thread`
    // already populated, so connect must NOT re-fetch (that would blank-then-reload — the §K.2 flicker).
    // The auto-load therefore fires only on a genuinely cold/empty instance: a fresh page reload (new
    // instance, empty thread) restores the thread via the per-tab `lastViewedConversation` pointer
    // (sessionStorage survives reload); same-session navigation keeps the retained thread silently.
    // Cold start with no pointer keeps the constructor's most-recent resume-card behavior.
    const lastViewed = readLastViewedConversation();
    if (lastViewed && this.thread.length === 0) {
      this.showResumePrompt = false;
      void this.loadConversation(lastViewed, 'core.free-chat');
    }
    this.aiStateUnsubscribe = subscribeAiState((s) => this.applyAiState(s));
    // Tempdoc 561 C-2: the dial change only re-grades chrome (placeholder / send label / rail
    // posture); it touches no record and no in-flight run.
    this.autonomyUnsubscribe = subscribeAutonomy(() => this.requestUpdate());
    this.excludedSourcesUnsub = subscribeExcludedSources(() => this.requestUpdate());
    // Tempdoc 577 Goal 3 — the retrieve base tier reads the one search store. Same apiBase as chat.
    setSearchApiBase(this.apiBase || '');
    this.searchUnsub = subscribeSearch((s) => {
      // Search Thread S4-final — a query change SUPERSEDES the previous one; remember it on the
      // trail before it's gone (the "committed-or-superseded" half — commitLiveSearch below covers
      // the "committed" half at consequence time).
      const prevQuery = this.searchSnapshot?.query ?? '';
      if (prevQuery.trim() && prevQuery !== s.query) this.rememberQueryInTrail(prevQuery);
      this.searchSnapshot = s;
    });
    // §3.9a — facet selections drive the retrieve tier's chips; seed + subscribe.
    this.facetSelections = getFacetSelections();
    this.facetUnsub = subscribeFacetSelections((sel) => {
      this.facetSelections = sel;
    });
    // Search Thread D5 (stage S3) — mirror the scope-chip store (fires once immediately with the
    // current chips, mirroring subscribeSearch/subscribeFacetSelections above).
    this.scopeChipsUnsub = subscribeScopeChips((chips) => {
      this.scopeChips = chips;
    });
    // S5b pin-parity — the pinned-search strip moved from the retired SearchSurface onto the
    // landing + the bar's pin toggle (plan: "pinned chips land on the card/landing").
    this.pinnedSearchesUnsub = subscribePinnedSearches((pins) => {
      this.pinnedSearches = pins;
    });
    // Search Thread S6 — the shared "open a document for reading" signal (inspectorState; fires once
    // immediately with the current state, mirroring the subscriptions above). Every existing producer
    // (`host.ui.showInspector` on the plugin API — the internal openRetrieveHit/handleCommittedCardOpen/
    // republishRetrieveSelection call sites all reach it that way — and citation clicks, reworked in
    // Shell.onCitationSelect to call `setSelected` with the passage's line range) funnels through this
    // ONE store, so this ONE subscription is the ONE place readingDocPath is derived from it. An
    // explicit close (`setOpen(false)` / `resetInspectorState()`) clears the reading pane; a fresh
    // `selected` while open sets it (and its highlight range, when the producer carried one).
    this.inspectorUnsub = subscribeInspector((s) => {
      if (s.selected && s.isOpen) {
        this.readingDocPath = s.selected.path;
        this.readingHighlightRange =
          typeof s.selected.highlightStartLine === 'number' &&
          typeof s.selected.highlightEndLine === 'number'
            ? { startLine: s.selected.highlightStartLine, endLine: s.selected.highlightEndLine }
            : null;
        this.readingChunkRange = null;
      } else if (!s.isOpen) {
        this.readingDocPath = null;
        this.readingHighlightRange = null;
        this.readingChunkRange = null;
      }
    });
    this.republishRetrieveSelection();
  }

  /**
   * Search Thread S5b (state-retention parity with the retired SearchSurface, tempdoc 609
   * instance-retention) — the retrieve tier's multi-select is instance @state
   * (`retrieveSelectedIds`), so it survives a disconnect/reconnect cycle; but the GLOBAL
   * selectionState does not (other rail surfaces clear it on surface change). Re-publish it +
   * reopen the inspector for the primary hit, mirroring SearchSurface.connectedCallback. Stale ids
   * (results changed while away) are dropped — only ids still present in the current snapshot count.
   */
  private republishRetrieveSelection(): void {
    if (this.retrieveSelectedIds.size === 0 || (this.searchSnapshot?.results.length ?? 0) === 0) return;
    const hits = this.searchSnapshot!.results;
    const primaryIndex = hits.findIndex((h) => this.retrieveSelectedIds.has(h.id));
    const survivingIds = hits.filter((h) => this.retrieveSelectedIds.has(h.id)).map((h) => h.id);
    if (survivingIds.length === 0) {
      this.retrieveSelectedIds = new Set();
      return;
    }
    // Reopen the inspector for the primary hit — a passive preview restore, NOT a fresh "open"
    // (recordOpenDisposition stays scoped to openRetrieveHit's real user-click path).
    const primaryHit = primaryIndex >= 0 ? hits[primaryIndex] : hits.find((h) => survivingIds.includes(h.id));
    this.handleRetrieveCardSelection({
      ids: survivingIds,
      primaryId: primaryHit?.id ?? survivingIds[0]!,
      primaryIndex: primaryIndex >= 0 ? primaryIndex : 0,
    });
    const host = this.host_;
    if (primaryHit && host?.search && host?.ui) {
      host.ui.showInspector(
        host.search.hitToSelectedItem(primaryHit as unknown as import('../plugin-api/plugin-types.js').SearchHitSnapshot),
      );
    }
  }

  /**
   * Tempdoc 734 §"Locked thread stays readable after lock" (629 authored `renderHistoryLocked` /
   * the initial 423 gate this follows up on) — the ONE subscribeAiState callback (connectedCallback)
   * already receives the polled `/api/status` snapshot on every tick; this derives `historyLocked` from its
   * `conversationProtection.state` instead of leaving it a write-once value from the initial
   * `loadConversation()` 423. No new store/subscription — a lock taken elsewhere (idle/auto-lock,
   * another tab) now clears the rendered transcript instead of leaving it readable forever.
   *
   * KNOWN BOUND: this only catches up on the next scheduled poll — `statusPoll.ts`'s `INTERVAL_MS` is
   * 10s (`subscribeStatus`/`fetchOnce`, `modules/ui-web/src/shell-v0/utils/statusPoll.ts:27,76-84`). A
   * lock triggered elsewhere is reflected within ~10s, not immediately — better than forever (the prior
   * defect), but do not read this as instant. `SecuritySurface.ts` shortens ITS OWN window by calling
   * `refreshStatusNow()` right after its own lock/unlock POST (tempdoc 727 F-8); that only helps the
   * in-app-initiated case, and is out of scope here — see report for a candidate follow-up.
   *
   * S5a (decision B14) — the old auto-upgrade-to-'documents' on rag capability is DELETED: the user's
   * tier is sticky-explicit or derived from what they hold; a model coming online changes availability
   * chrome (route chip unpins), never the standing view.
   */
  private applyAiState(s: AiState): void {
    this.aiState = s;
    const convState = s.status?.conversationProtection?.state;
    if (convState === 'locked') {
      this.historyLocked = true;
    } else if (convState === 'unlocked') {
      this.historyLocked = false;
      // Tempdoc 734 round-14 F4 — the refusal notice is about a lock that is now gone; keeping it
      // would leave a stale "your message was not sent" over a composer that can send again.
      this.lockedSendNotice = '';
    }
    this.maybeAutoRun();
  }

  /**
   * 548 §4.5 — one-shot auto-send for the `answer` verb. Fires `send()` exactly
   * once when an `answer` intent activated this surface AND the prompt is
   * prefilled AND the AI is chat-capable. Idempotent: clears the flag before
   * dispatch so the multiple subscription paths that call it can't double-fire.
   * If the AI is offline the flag simply never fires and the prompt stays
   * prefilled for the user to send manually once the model is up.
   */
  private maybeAutoRun(): void {
    if (!this.autoRunPending) return;
    if (this.isStreaming) return;
    if (!this.inputDraft.trim()) return;
    if (!this.aiState?.capabilities?.chat) return;
    this.autoRunPending = false;
    void this.send();
  }

  /**
   * Tempdoc 609 (instance-retention) — settle transient state on hide. The Stage retains this element
   * across navigation, so `@state` survives; this resets the in-flight / partial-answer / error /
   * transient-panel fields so a return never shows a stale "thinking" spinner, a half-streamed answer,
   * or a stale error. Recoverable state (thread, inputDraft, affordance, sessionId, showResumePrompt,
   * facetSelections) is deliberately NOT touched. Auto-invoked via JfElement.disconnectedCallback (so it
   * runs through `super.disconnectedCallback()` below, BEFORE the abort/teardown).
   */
  protected override settleTransients(): void {
    // A torn-down stream is no longer live — settle the global activity indicator (Phase 4).
    if (this.isStreaming) {
      setAiActivity({ state: 'idle', shapeId: null, startedAtMs: null, canCancel: false, cancel: null });
    }
    this.isStreaming = false;
    this.streamingText = '';
    this.errorMessage = '';
    this.historyLocked = false;
    this.lockedSendNotice = '';
    this.citations = [];
    this.coverage = null;
    this.sourceCoverage = [];
    this.sources = [];
    this.claims = [];
    this.ragMeta = null;
    this.rewriteNote = null;
    this.reasoning.reset();
    // Close transient panels/editors so they don't reopen on return.
    this.showAbilities = false;
    // Tempdoc 738 — the banner's local "See details" toggle resets on hide; the effective expand
    // state is re-derived from disclosure + severity on the next render.
    this.degradationBannerExpanded = false;
    // Round-14 finding 12(a) — the run-telemetry band is a transient panel too: it returns to its
    // collapsed default rather than reopening on the next visit.
    this.activityRailExpanded = false;
    this.forkEditing = false;
    this.forkDraft = '';
    this.steerDraft = '';
    // Search Thread D2/D3 (stage S2) — the per-turn route override is a transient (per-turn) choice,
    // not recoverable task state; a return should re-run the heuristic guess, not keep a stale flip.
    this.routeOverride = null;
    // Search Thread S4-final — the query-trail dropdown is a transient panel (mirrors
    // showAbilities/forkEditing above); `committedSearches`/`queryTrail` themselves are deliberately
    // NOT touched here — they are recoverable task state, the same category as `thread`/`scopeChips`.
    this.queryTrailOpen = false;
    // S5a — `explicitAffordance` (sticky tier choice) and `schemaAttached` (a held attachment)
    // are recoverable task state, deliberately NOT settled here: clearing them on navigation
    // would un-pin the tier / drop the attachment on every tab switch — the churn B14 retired.
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.uiModeUnsubscribe?.();
    this.uiModeUnsubscribe = null;
    // Tempdoc 609 §R (T1.4) — instance-retention keeps the composer draft across navigation; surface that
    // reassurance once (per session) when leaving with a non-empty draft. settleTransients keeps inputDraft
    // (recoverable), so it's intact here.
    notifyDraftKeptOnce('core.unified-chat-surface', this.inputDraft.trim().length > 0);
    this.abortController?.abort();
    this.abortController = null;
    this.reasoning.destroy();
    // Tempdoc 561 surface tier: the agent controller is shared — unsubscribe, do NOT destroy it.
    this.agentSessionUnsub?.();
    this.agentSessionUnsub = null;
    this.selectedSourceUnsub?.();
    this.selectedSourceUnsub = null;
    this.unsubWide?.();
    this.unsubWide = null;
    this.unsubShort?.();
    this.unsubShort = null;
    this.zoneResizeObserver?.disconnect();
    this.zoneResizeObserver = null;
    this.observedBox = null;
    reportLayoutWidth(this, null);
    this.searchUnsub?.();
    this.searchUnsub = null;
    this.facetUnsub?.();
    this.facetUnsub = null;
    this.scopeChipsUnsub?.();
    this.scopeChipsUnsub = null;
    this.pinnedSearchesUnsub?.();
    this.pinnedSearchesUnsub = null;
    this.inspectorUnsub?.();
    this.inspectorUnsub = null;
    this.excludedSourcesUnsub?.();
    this.excludedSourcesUnsub = null;
    // §21 — the run-spine's observers + scroll listeners are torn down by NavigationController.hostDisconnected.
    this.agentCtrl = null;
    this.stopRenderTick();
    this.storeUnsubscribe?.();
    this.storeUnsubscribe = null;
    this.convListUnsub?.();
    this.convListUnsub = null;
    this.selectionUnsubscribe?.();
    this.selectionUnsubscribe = null;
    this.aiStateUnsubscribe?.();
    this.aiStateUnsubscribe = null;
    this.autonomyUnsubscribe?.();
    this.autonomyUnsubscribe = null;
    this.removeEventListener('cite-ref-hover', this.boundCiteRefHover as EventListener);
    this.removeEventListener('cite-ref-leave', this.boundCiteRefLeave as EventListener);
    window.removeEventListener('keydown', this.boundWindowKeydown);
    window.removeEventListener('jf-focus-composer', this.boundFocusComposer);
    this.hoverCard?.remove();
  }

  /**
   * 798 round 8 — report the measured CONTENT-box inline size of the `chat-surface` query container to
   * the one breakpoint authority, so every wide-layout mount gate decides on the width the conversation
   * zone actually gets rather than on the viewport (which still contains the Shell rail and this
   * surface's padding). Measuring the very element the `@container chat-surface` rules resolve against
   * — `.answer-plane` — is what keeps CSS and TS from disagreeing; the host is the fallback for the
   * pre-first-render call, and is the same width while the plane carries no padding of its own.
   *
   * The `ResizeObserver` guard mirrors the Adaptivity controllers (`adaptiveDensity.ts`): where it is
   * absent (happy-dom / SSR) nothing is reported and the authority stays on its viewport fallback.
   * Reporting from the observer callback (which runs after layout, before paint) rather than from a
   * rAF means the corrected decision is rendered in the same frame — no first-paint flash.
   */
  private observeSurfaceWidth(): void {
    if (typeof ResizeObserver === 'undefined') return;
    this.zoneResizeObserver = new ResizeObserver((entries) => {
      const entry = entries[0];
      if (!entry) return;
      const inline = entry.contentBoxSize?.[0]?.inlineSize;
      reportLayoutWidth(this, inline ?? entry.contentRect.width);
    });
    this.observeQueryContainer();
  }

  /** Point the observer at the `.answer-plane` query container once it exists; the host until then. */
  private observeQueryContainer(): void {
    const ro = this.zoneResizeObserver;
    if (!ro) return;
    const plane = this.shadowRoot?.querySelector('.answer-plane');
    const target = plane instanceof HTMLElement ? plane : this;
    if (target === this.observedBox) return;
    ro.disconnect();
    ro.observe(target, { box: 'content-box' });
    this.observedBox = target;
  }

  private startRenderTick(): void {
    this.stopRenderTick();
    this.renderTickTimer = window.setInterval(() => this.requestUpdate(), 1000);
  }

  private stopRenderTick(): void {
    if (this.renderTickTimer !== null) {
      window.clearInterval(this.renderTickTimer);
      this.renderTickTimer = null;
    }
  }

  private get thinkingElapsedSec(): number {
    const start = this.aiState?.activity?.startedAtMs;
    if (!start) return 0;
    return Math.round((Date.now() - start) / 1000);
  }

  /**
   * Tempdoc 811 C-4 — the number the "Searching N documents" preview may honestly show: the
   * DEFAULT-search-scope population, not the whole index. `indexedDocuments` counts collections the
   * default scope excludes (agent-run transcripts), so it described a corpus the user cannot see or
   * enumerate. An older backend omits `searchableDocumentCount` (UNKNOWN) → fall back to the
   * whole-index count; a KNOWN `0` is a real value and stays 0.
   */
  private get docCount(): number {
    const index = this.aiState?.index;
    if (!index) return 0;
    return whenKnown(
      index.searchableDocumentCount,
      (n) => n,
      () => orElse(index.documentCount, 0),
    );
  }

  // Tempdoc 565 §15.B — the inline marks now carry their resolved source directly (every mode renders
  // through the one `MarkdownBlock` weave), so the hover handler reads `detail.source` only; the
  // sentence-index → claim → source lookup (and the `cite-ref-click` re-dispatch, and the
  // `resolveMessageData` helper they shared) retired with `StreamingTextBlock`. Marks now dispatch
  // `citation-select` themselves, which bubbles to `Shell.onCitationSelect`.
  private onCiteRefHover(e: Event): void {
    const detail = (e as CustomEvent).detail as
      | {
          rect: DOMRect;
          source?: { excerpt: string; parentDocId: string; score: number; headingText: string };
        }
      | undefined;
    if (!detail || !detail.source) return;
    const data: CitationHoverData = {
      excerpt: detail.source.excerpt,
      parentDocId: detail.source.parentDocId,
      score: detail.source.score,
      headingText: detail.source.headingText,
    };
    if (!this.hoverCard) {
      this.hoverCard = document.createElement('jf-citation-hover-card') as CitationHoverCard;
      this.shadowRoot!.appendChild(this.hoverCard);
    }
    this.hoverCard.show(data, detail.rect);
  }

  private onCiteRefLeave(): void {
    this.hoverCard?.hide();
  }

  private copyText(text: string): void {
    void copyToClipboard(text);
  }

  // ---- Tempdoc 610 Phase A — transcript edit / retry controls ----

  /** A user turn is controllable (edit/retry) when it is settled, own, has a
   * stable id, and is not part of an agent run (513 §A.5 keeps agent-run
   * branching out of scope). */
  private canTurnControl(m: ThreadMessage): boolean {
    return (
      !m.inheritedFromParent &&
      typeof m.id === 'string' &&
      !this.isStreaming &&
      m.shapeId !== 'core.agent-run' &&
      this.affordance !== 'agent'
    );
  }

  /** Open the per-turn ⋯ overflow menu via the ONE ContextMenu primitive.
   * (Edit is rendered inline on user turns, not here — §13.1.) */
  private async openTurnMenu(e: Event, idx: number): Promise<void> {
    e.stopPropagation();
    const m = this.thread[idx];
    if (!m) return;
    const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
    const allowRetryEdit =
      !this.isStreaming && this.affordance !== 'agent' && m.shapeId !== 'core.agent-run';
    // Tempdoc 610 §13.1 — Edit is the user turn's defining action and renders
    // INLINE on the turn (not here); the ⋯ overflow holds only the rest.
    const actions: ContextMenuAction[] = [];
    if (allowRetryEdit) {
      actions.push({ id: 'retry', label: 'Retry from here', category: 'ai', enabled: true });
    }
    if (typeof m.id === 'string' && !m.inheritedFromParent) {
      actions.push({
        id: 'branch',
        label: 'Branch to new thread',
        icon: 'git-branch',
        category: 'ai',
        enabled: true,
      });
    }
    // Tempdoc 610 Phase C — reset effective context to this turn (the next
    // prompt starts here; the transcript still shows everything above it).
    if (allowRetryEdit && typeof m.id === 'string') {
      actions.push({
        id: 'reset-context',
        label: 'Reset context to here',
        icon: 'history',
        category: 'ai',
        enabled: this.contextFloorId !== m.id || this.contextFloorSummary !== null,
      });
      // Tempdoc 610 Phase D — compact (summarize) everything above this turn.
      // Only offered when there is something above to summarize.
      if (idx > 0) {
        actions.push({
          id: 'compact',
          label: 'Compact up to here',
          icon: 'history',
          category: 'ai',
          enabled: !this.compacting,
        });
      }
    }
    // Tempdoc 610 §E.3 — per-message exclude: a per-message generalization of the floor. Hide this
    // turn from the next prompt while it stays in the transcript (dimmed). Toggles include/exclude.
    if (typeof m.id === 'string' && !this.isStreaming) {
      const isExcluded = this.excludedMessageIds.has(m.id);
      actions.push({
        id: 'toggle-exclude',
        label: isExcluded ? 'Include in context' : 'Exclude from context',
        category: 'ai',
        enabled: true,
      });
    }
    if (actions.length === 0) return;
    const chosen = await openContextMenu({ actions, anchor: { x: rect.left, y: rect.bottom + 4 } });
    if (chosen === 'retry') await this.retryFrom(idx);
    else if (chosen === 'branch') await this.branchHere(m.id as string);
    else if (chosen === 'reset-context') await this.resetContextTo(idx);
    else if (chosen === 'compact') await this.compactTo(idx);
    else if (chosen === 'toggle-exclude') await this.toggleMessageExcluded(idx);
  }

  /**
   * Tempdoc 610 §E.3 — toggle whether this turn is excluded from the effective context. The
   * transcript still shows it (dimmed); the next prompt drops it. The per-message sibling of the
   * floor — applied in loadEffectiveContext before the floor trim.
   */
  private async toggleMessageExcluded(idx: number): Promise<void> {
    const m = this.thread[idx];
    if (!m || typeof m.id !== 'string' || !this.sessionId) return;
    const id = m.id;
    const nextExcluded = !this.excludedMessageIds.has(id);
    const ok = await setMessageExcluded(this.sessionId, id, nextExcluded);
    if (!ok) return;
    const next = new Set(this.excludedMessageIds);
    if (nextExcluded) next.add(id);
    else next.delete(id);
    this.excludedMessageIds = next;
  }

  private startEdit(idx: number): void {
    const m = this.thread[idx];
    if (!m || m.role !== 'user' || typeof m.id !== 'string') return;
    this.editingMessageId = m.id;
    this.editingDraft = m.content;
  }

  private cancelEdit(): void {
    this.editingMessageId = null;
    this.editingDraft = '';
  }

  private onEditKeydown(e: KeyboardEvent, idx: number): void {
    if (e.key === 'Escape') {
      e.preventDefault();
      this.cancelEdit();
    } else if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
      e.preventDefault();
      void this.commitEdit(idx);
    }
  }

  private async commitEdit(idx: number): Promise<void> {
    const text = this.editingDraft.trim();
    this.cancelEdit();
    if (!text) return;
    await this.branchAndResend(idx, text);
  }

  /** Retry = regenerate the answer to a turn. Resolves to the prompting user
   * turn, then re-dispatches its text unchanged (Tempdoc 610 §4 — retry is
   * branch-from-before + re-dispatch, no engine change). */
  private async retryFrom(idx: number): Promise<void> {
    const m = this.thread[idx];
    if (!m) return;
    const userIdx = m.role === 'user' ? idx : idx - 1;
    const userTurn = this.thread[userIdx];
    if (!userTurn || userTurn.role !== 'user') return;
    await this.branchAndResend(userIdx, userTurn.content);
  }

  /** Shared edit/retry flow: branch from BEFORE the user turn (so the
   * re-dispatched turn is the first divergent message), switch to the branch,
   * then re-dispatch via the normal send path. First turn → empty-prefix
   * sentinel (no preceding message to branch from). */
  private async branchAndResend(userIdx: number, text: string): Promise<void> {
    if (this.isStreaming) return;
    let branchPoint: string;
    if (userIdx === 0) {
      branchPoint = EMPTY_PREFIX_SENTINEL;
    } else {
      const prev = this.thread[userIdx - 1];
      if (!prev || typeof prev.id !== 'string') {
        this.errorMessage = 'Cannot edit yet — the previous message is still saving.';
        return;
      }
      branchPoint = prev.id;
    }
    const shapeId: ShapeId = this.thread[userIdx]?.shapeId ?? 'core.free-chat';
    const preview = this.thread.find((mm) => mm.role === 'user')?.content ?? text;
    const newSessionId = await branchConversation(this.sessionId, branchPoint, preview);
    if (!newSessionId) {
      this.errorMessage = 'Failed to create branch';
      return;
    }
    // loadConversation aborts any in-flight stream (516 FIX-T1) and loads the
    // inherited prefix [0..userIdx-1].
    await this.loadConversation(newSessionId, shapeId);
    // Re-dispatch the (edited or original) turn through the normal send path.
    this.inputDraft = text;
    await this.send();
  }

  // ---- Tempdoc 610 Phase B — inline sibling version pager ----

  /** Resolve the version set for a turn, or null when it is not a divergence
   * point with >1 version. Two cases: (A) the current conversation is itself a
   * branch and this is its first own (post-prefix) turn → the fork is
   * (parent, branchPoint); (B) the current conversation is the BASE for loaded
   * branches that fork at the message just before this turn (or at the first
   * own message, for empty-prefix forks) → it is version 1 of that fork. Pure
   * read over the loaded list (no network). */
  private pagerForTurn(m: ThreadMessage): { sessions: string[]; index: number } | null {
    if (typeof m.id !== 'string' || this.conversations.length === 0) return null;
    // Case A — current conversation is a branch; pager on its first own turn.
    if (this.branchParentId && this.branchPointId && !m.inheritedFromParent) {
      const firstOwn = this.thread.find(
        (x) => !x.inheritedFromParent && typeof x.id === 'string',
      );
      if (firstOwn?.id === m.id) {
        const sessions = siblingSessionsAt(
          this.conversations,
          this.branchParentId,
          this.branchPointId,
        );
        if (sessions.length > 1) {
          return { sessions, index: Math.max(0, sessions.indexOf(this.sessionId)) };
        }
      }
    }
    // Case B — current conversation is the base; this turn is version 1 of any
    // fork whose branch point is the message before it (or the empty sentinel
    // at the first own turn).
    const idx = this.thread.findIndex((x) => x.id === m.id);
    const candidateKeys: string[] = [];
    if (idx > 0) {
      const prevId = this.thread[idx - 1]?.id;
      if (typeof prevId === 'string') candidateKeys.push(prevId);
    }
    const firstOwnIdx = this.thread.findIndex((x) => !x.inheritedFromParent);
    if (idx === firstOwnIdx) candidateKeys.push(EMPTY_PREFIX_SENTINEL);
    for (const key of candidateKeys) {
      const sessions = siblingSessionsAt(this.conversations, this.sessionId, key);
      if (sessions.length > 1) return { sessions, index: 0 };
    }
    return null;
  }

  private renderVersionPager(info: { sessions: string[]; index: number }): TemplateResult {
    const { sessions, index } = info;
    const shapeId: ShapeId = this.thread[0]?.shapeId ?? 'core.free-chat';
    const go = (next: number): void => {
      if (next < 0 || next >= sessions.length || next === index) return;
      void this.loadConversation(sessions[next]!, shapeId);
    };
    return html`<span class="version-pager" role="group" aria-label="Message versions">
      <button
        class="ver-nav"
        aria-label="Previous version"
        ?disabled=${index <= 0}
        @click=${() => go(index - 1)}
      >
        ${icon({ name: 'chevron-left', size: 14 })}
      </button>
      <span class="ver-count">${index + 1} / ${sessions.length}</span>
      <button
        class="ver-nav"
        aria-label="Next version"
        ?disabled=${index >= sessions.length - 1}
        @click=${() => go(index + 1)}
      >
        ${icon({ name: 'chevron-right', size: 14 })}
      </button>
    </span>`;
  }

  /**
   * Tempdoc 610 §D.2 — the ONE per-turn action bar, rendered BELOW each settled
   * turn (the ChatGPT/Claude affordance grammar) instead of in the header /
   * bubble-corner. Primary verbs are visible icon buttons; the rest live behind
   * the `⋯` overflow (openContextMenu). Native icon buttons (keyboard-operable,
   * token-styled) so the `⋯` keeps the click event needed to anchor the menu.
   * Reused by renderMessage (live + delegated user turns) and the
   * renderUnifiedItem assistant record branches, so it shows on reloaded turns.
   */
  private renderTurnActionBar(m: ThreadMessage, idx: number): TemplateResult | typeof nothing {
    const pager = this.pagerForTurn(m);
    if (!this.canTurnControl(m)) {
      // No controls on inherited/streaming/agent turns, but a sibling pager may
      // still apply (it is gated independently on a stable id).
      return pager
        ? html`<div class="turn-actions ${m.role}-actions">${this.renderVersionPager(pager)}</div>`
        : nothing;
    }
    const isUser = m.role === 'user';
    return html`<div class="turn-actions ${m.role}-actions">
      ${pager ? this.renderVersionPager(pager) : nothing}
      ${isUser
        ? html`<button
            class="turn-act-btn"
            title="Edit"
            aria-label="Edit message"
            @click=${() => this.startEdit(idx)}
          >
            ${icon({ name: 'pencil', size: 15 })}
          </button>`
        : html`<button
              class="turn-act-btn"
              title="Copy"
              aria-label="Copy answer"
              @click=${() => this.copyText(m.content)}
            >
              ${icon({ name: 'clipboard-copy', size: 15 })}
            </button>
            <button
              class="turn-act-btn"
              title="Retry"
              aria-label="Retry"
              @click=${() => void this.retryFrom(idx)}
            >
              ${icon({ name: 'refresh-cw', size: 15 })}
            </button>`}
      <button
        class="turn-act-btn"
        title="More actions"
        aria-label="More message actions"
        @click=${(e: Event) => void this.openTurnMenu(e, idx)}
      >
        ${icon({ name: 'more-horizontal', size: 15 })}
      </button>
    </div>`;
  }

  // ---- Tempdoc 610 Phase C — effective-context floor (rewind) ----

  private async resetContextTo(idx: number): Promise<void> {
    const m = this.thread[idx];
    if (!m || typeof m.id !== 'string') return;
    const ok = await setContextFloor(this.sessionId, m.id);
    if (ok) {
      this.contextFloorId = m.id;
      // A plain rewind carries no summary (the backend clears it too).
      this.contextFloorSummary = null;
      this.showFloorSummary = false;
    } else {
      this.errorMessage = 'Failed to reset context';
    }
  }

  private async restoreContext(): Promise<void> {
    const ok = await clearContextFloor(this.sessionId);
    if (ok) {
      this.contextFloorId = null;
      this.contextFloorSummary = null;
      this.showFloorSummary = false;
    } else {
      this.errorMessage = 'Failed to restore context';
    }
  }

  /** Tempdoc 610 Phase D — compact: the backend summarizes everything above
   * this turn (one-shot LLM) and attaches the summary to a floor here. */
  private async compactTo(idx: number): Promise<void> {
    const m = this.thread[idx];
    if (!m || typeof m.id !== 'string' || this.compacting) return;
    this.compacting = true;
    try {
      const summary = await compactContext(this.sessionId, m.id);
      if (summary) {
        this.contextFloorId = m.id;
        this.contextFloorSummary = summary;
        this.showFloorSummary = false;
      } else {
        this.errorMessage = 'Compaction unavailable (the model may be offline)';
      }
    } finally {
      this.compacting = false;
    }
  }

  /** The thread index of the context floor, or -1 when no floor is set. */
  private floorIndex(): number {
    if (!this.contextFloorId) return -1;
    return this.thread.findIndex((m) => m.id === this.contextFloorId);
  }

  /** The full-width divider rendered just above the floor message: everything
   * above it is shown but out-of-context (not sent to the next turn). Distinct
   * from the ↪ inherited-prefix banner. */
  /**
   * Tempdoc 610 §E.4 — the chat-surface context-budget meter: how full the model's context window
   * is (last turn's prompt occupancy ÷ n_ctx). Reuses the §577 `projectContextHorizon` projection +
   * the shared budget-bar visual + the one fullness→colour authority. The agent surface has its own
   * headroom meter in the activity rail; this is its non-agent sibling. Omitted in agent mode, or
   * when there is no occupancy/denominator yet (so it never shows a misleading 0%).
   */
  private renderContextMeter(): TemplateResult | typeof nothing {
    if (this.affordance === 'agent') return nothing;
    const horizon = projectContextHorizon({
      tokensConsumed: 0,
      tokensRemaining: 0,
      promptTokens: this.contextPromptTokens ?? 0,
      contextWindow: this.aiState?.runtime?.contextWindow ?? 0,
    });
    if (!horizon) return nothing;
    // Tempdoc 610 §I.2 — the per-phase attribution. The bar TOTAL stays the real occupancy; the
    // split (system/conversation/documents) is an over-estimate, shown only as proportions/≈. Revealed
    // on hover (the compact bar is the default) + carried in the title for SR/keyboard users.
    const b = this.contextBreakdown;
    const split = b
      ? ` — split (est.): system ~${b.system}, conversation ~${b.conversation}, documents ~${b.retrieved}`
      : '';
    return html`
      <div class="context-meter" title="How full the assistant's context window is${split}">
        <button
          type="button"
          class="context-meter-label context-meter-trigger"
          aria-label="Context ${horizon.pct}% used — show what the assistant sees"
          @click=${() => this.openContextInspector()}
        >
          Context ${horizon.pct}% · ${horizon.occupancy} / ${horizon.window} tokens
        </button>
        <div
          class="budget-bar"
          role="meter"
          aria-label="Context window used"
          aria-valuenow=${horizon.pct}
          aria-valuemin="0"
          aria-valuemax="100"
        >
          <div
            class="budget-bar-fill context-fill-${horizon.color}"
            style=${`width:${horizon.pct}%`}
          ></div>
        </div>
        ${b
          ? html`<span class="context-meter-breakdown"
              >system ~${b.system} · conversation ~${b.conversation} · documents ~${b.retrieved}
              <span class="cmb-est">(estimated)</span></span
            >`
          : nothing}
      </div>
    `;
  }

  /**
   * Tempdoc 610 §I.2 — an aggregate readout of how many turns the user has individually hidden from
   * the effective context, with a one-click "Include all" to undo them in bulk. Complements the
   * per-turn exclude (the dashed-rail dimming); shown only when at least one turn is hidden.
   */
  private renderExcludedSummary(): TemplateResult | typeof nothing {
    if (this.affordance === 'agent') return nothing;
    const n = this.excludedMessageIds.size;
    if (n === 0) return nothing;
    return html`
      <div class="excluded-summary">
        <span class="excluded-summary-label"
          >${n} turn${n === 1 ? '' : 's'} hidden from context</span
        >
        <button
          class="excluded-summary-action"
          aria-label="Include all hidden turns back into context"
          @click=${() => void this.includeAll()}
        >
          Include all
        </button>
      </div>
    `;
  }

  /** Tempdoc 610 §I.2 — bulk-undo: re-include every individually-hidden turn into the context. */
  private async includeAll(): Promise<void> {
    if (!this.sessionId || this.excludedMessageIds.size === 0) return;
    const ids = [...this.excludedMessageIds];
    const next = new Set(this.excludedMessageIds);
    for (const id of ids) {
      const ok = await setMessageExcluded(this.sessionId, id, false);
      if (ok) next.delete(id);
    }
    this.excludedMessageIds = next;
  }

  /** Tempdoc 610 §K — open the (shell-mounted) inspector, seeding its view from the current prompt. */
  private openContextInspector(): void {
    setContextInspectorView(this.buildInspectorView());
    toggleContextInspector();
  }

  /**
   * Tempdoc 814 §D2 — the held-gate EXCEPTION to "in-flow chrome is summary-height, detail is
   * on-demand". A run parked awaiting a budget decision is the primary thing on screen at that
   * moment, and the decision row that resolves it ("Add tokens / Finish with what it has / Stop",
   * 577 Move 2) lives in the activity rail's BODY — inside a `<details>` that defaults to collapsed.
   * Without this, the one state where the remedies are real renders them out of sight.
   *
   * Keyed on the TRANSITION into the held state (`agentCtrl.budgetGate != null` — the same predicate
   * the summary's "Paused — awaiting budget" chip renders on), not on the state itself: the rail is
   * forced open ONCE when the gate engages, so a user who collapses it while the run is still parked
   * keeps it collapsed (their choice wins over the exception). No other lifecycle state — DONE
   * included — ever forces it open; a terminal over-budget run is history, and §D2 keeps history in
   * the collapsed summary.
   */
  private budgetGateWasHeld = false;

  protected override willUpdate(_changed: Map<string, unknown>): void {
    const held = this.agentCtrl?.budgetGate != null;
    if (held && !this.budgetGateWasHeld) {
      this.activityRailExpanded = true;
    }
    this.budgetGateWasHeld = held;
  }

  /** Tempdoc 610 §K — keep the shell-mounted inspector's view fresh while it is open (e.g. a new turn). */
  protected override updated(_changed: Map<string, unknown>): void {
    if (isContextInspectorOpen()) {
      setContextInspectorView(this.buildInspectorView());
    }
    // 798 — the `.answer-plane` query container only exists after the first render; re-point the
    // width observer at it (a no-op once it is already the observed box).
    this.observeQueryContainer();
    // (Search Thread S2 note: the landing→docked transition is CSS-only — the composer never
    // re-parents, so no focus restoration is needed; see the stable-slot rule in renderAnswerPlane.)
  }

  /**
   * Tempdoc 610 §J/§K — the whole-prompt projection the inspector renders, computed POST-HOC from the
   * last completed turn (no re-retrieval): the system phase (token count), the in-context conversation
   * turns (+ the standing summary), and the last assistant turn's retrieved sources. Each conversation
   * turn / document gets a §L.1 position marker over the COMBINED prompt order (start/end attend well;
   * the middle is "weak"). Per-segment tokens are left null — the phase total (from the estimated
   * contextBreakdown) carries the magnitude; the real promptTokens is the authoritative grand total.
   */
  private buildInspectorView(): InspectorView {
    const b = this.contextBreakdown;
    const floorIdx = this.floorIndex();
    const start = floorIdx >= 0 ? floorIdx : 0;
    const convTurns = this.thread
      .slice(start)
      .filter((m) => typeof m.id !== 'string' || !this.excludedMessageIds.has(m.id));
    const lastAssistant = [...this.thread].reverse().find((m) => m.role === 'assistant');
    const sources = lastAssistant?.sources ?? [];

    // The whole-prompt order for the position signal: summary → conversation turns → documents.
    const ordered: Array<{ kind: 'turn' | 'source'; label: string; text: string }> = [];
    if (this.contextFloorSummary) {
      ordered.push({
        kind: 'turn',
        label: 'Summary of earlier turns',
        text: this.contextFloorSummary,
      });
    }
    for (const m of convTurns) {
      ordered.push({
        kind: 'turn',
        label: m.role === 'user' ? 'You' : 'Assistant',
        text: m.content,
      });
    }
    for (const s of sources) {
      ordered.push({ kind: 'source', label: s.headingText || s.parentDocId, text: s.excerpt });
    }
    const total = ordered.length;
    const posOf = (i: number): 'strong' | 'weak' => {
      if (total <= 4) return 'strong';
      const head = Math.ceil(total * 0.25);
      const tail = Math.floor(total * 0.75);
      return i < head || i >= tail ? 'strong' : 'weak';
    };

    const convSegs: InspectorSegment[] = [];
    const docSegs: InspectorSegment[] = [];
    ordered.forEach((o, i) => {
      const seg: InspectorSegment = {
        label: o.label,
        text: o.text,
        tokens: null,
        position: posOf(i),
      };
      if (o.kind === 'source') docSegs.push(seg);
      else convSegs.push(seg);
    });

    const phases: InspectorPhase[] = [
      { name: 'Conversation', tokens: b?.conversation ?? null, segments: convSegs },
      { name: 'Documents', tokens: b?.retrieved ?? null, segments: docSegs },
    ];
    return {
      systemTokens: b?.system ?? null,
      phases,
      totalTokens: this.contextPromptTokens,
      windowTokens: this.aiState?.runtime?.contextWindow ?? null,
    };
  }

  private renderFloorDivider(): TemplateResult {
    const compacted = this.contextFloorSummary !== null;
    const label = compacted
      ? '❏ Context compacted — earlier messages summarized for the assistant'
      : '↺ Context reset — the assistant no longer sees messages above this line';
    return html`<div class="context-floor-divider" role="separator">
        <span class="cfd-label">${label}</span>
        ${compacted
          ? html`<button
              class="cfd-restore"
              @click=${() => {
                this.showFloorSummary = !this.showFloorSummary;
              }}
            >
              ${this.showFloorSummary ? 'Hide summary' : 'Show summary'}
            </button>`
          : nothing}
        ${compacted && this.showFloorSummary && !this.editingFloorSummary
          ? html`<button
              class="cfd-restore"
              @click=${() => {
                this.floorSummaryDraft = this.contextFloorSummary ?? '';
                this.editingFloorSummary = true;
              }}
            >
              Edit
            </button>`
          : nothing}
        <button class="cfd-restore" @click=${() => void this.restoreContext()}>Restore</button>
      </div>
      ${compacted && this.showFloorSummary
        ? this.editingFloorSummary
          ? html`<div class="cfd-summary cfd-summary-editing">
              <textarea
                class="cfd-summary-input"
                aria-label="Edit context summary"
                .value=${this.floorSummaryDraft}
                @input=${(e: Event) => {
                  this.floorSummaryDraft = (e.target as HTMLTextAreaElement).value;
                }}
              ></textarea>
              <div class="cfd-summary-actions">
                <button class="cfd-restore" @click=${() => void this.commitFloorSummaryEdit()}>
                  Save
                </button>
                <button
                  class="cfd-restore"
                  @click=${() => {
                    this.editingFloorSummary = false;
                  }}
                >
                  Cancel
                </button>
              </div>
            </div>`
          : html`<div class="cfd-summary">${this.contextFloorSummary}</div>`
        : nothing}`;
  }

  /**
   * Tempdoc 610 §E.2 — persist an in-place edit of the compaction summary. The floor is unchanged;
   * only the stored summary text is replaced (the backend reuses compactContext), so the corrected
   * summary stands in for the dropped turns on the next prompt — the §E.1 "no write barriers" answer.
   */
  private async commitFloorSummaryEdit(): Promise<void> {
    if (!this.sessionId) return;
    const text = this.floorSummaryDraft;
    const ok = await editContextFloorSummary(this.sessionId, text);
    if (ok) {
      this.contextFloorSummary = text;
      this.editingFloorSummary = false;
    }
  }

  /** Tempdoc 610 — floor divider + out-of-context class for a record-path turn
   * (renderUnifiedItem), keyed by the record item's id → thread index. Mirrors
   * the renderMessage floor handling so the divider/band render on persisted
   * assistant turns too (not only the live-overlay path). */
  /**
   * Tempdoc 610 §F.3 — the SINGLE source for a turn's effective-context "frame parts": the floor
   * divider (if this is the floor turn) and the dim-class (out-of-context band when above the floor,
   * the dashed-rail when individually excluded). Both render paths derive from here — the live path
   * (renderMessage) and the record path (recordFloorParts) — so a per-turn affordance can no longer
   * be added to one path and forgotten on the other (the root cause of the two §C.1/§D.5 bugs).
   */
  private floorFrameParts(
    id: string | undefined,
    idx: number,
  ): { divider: TemplateResult | typeof nothing; cls: string } {
    const fi = this.floorIndex();
    const outOfContext = fi >= 0 && idx >= 0 && idx < fi ? ' out-of-context' : '';
    const excluded =
      typeof id === 'string' && this.excludedMessageIds.has(id) ? ' excluded' : '';
    return {
      divider: fi >= 0 && idx === fi ? this.renderFloorDivider() : nothing,
      cls: outOfContext + excluded,
    };
  }

  private recordFloorParts(itemId: string | undefined): {
    divider: TemplateResult | typeof nothing;
    cls: string;
  } {
    const idx = typeof itemId === 'string' ? this.thread.findIndex((m) => m.id === itemId) : -1;
    return this.floorFrameParts(itemId, idx);
  }

  /** Tempdoc 610 §D.2 — the per-turn action bar for a record-path turn
   * (renderUnifiedItem), keyed by the record item's id → live-thread message,
   * so reloaded assistant turns get copy/retry/⋯ like the live-overlay path. */
  private recordActionBar(itemId: string | undefined): TemplateResult | typeof nothing {
    const idx = typeof itemId === 'string' ? this.thread.findIndex((m) => m.id === itemId) : -1;
    if (idx < 0) return nothing;
    return this.renderTurnActionBar(this.thread[idx]!, idx);
  }

  private async onConversationSelect(e: CustomEvent): Promise<void> {
    const { sessionId, shapeId } = e.detail as { sessionId: string; shapeId: string };
    this.showResumePrompt = false;
    await this.loadConversation(sessionId, shapeId);
  }

  private async loadConversation(sessionId: string, shapeId: string): Promise<void> {
    // Slice 516 FIX-T1 — cancel any in-flight stream so its onDone doesn't
    // write into the new conversation's thread. AbortError is caught in
    // consumeShapeStream; neither onDone nor onError fires, so no further
    // mutation reaches `this.thread`.
    this.abortController?.abort();
    this.sessionId = sessionId;
    // Tempdoc 609 Phase 3 — this tab is now viewing `sessionId`; remember it so a navigation
    // round-trip auto-restores THIS thread (not the global most-recent one).
    setLastViewedConversation(sessionId);
    this.errorMessage = '';
    this.thread = [];
    // Tempdoc 561 P-A/P-B (Slice 2): load the unified thread for the (re)loaded conversation.
    this.unifiedEvents = [];
    // Tempdoc 577 Move 1 — accountability dies with its run: the previous conversation's budget
    // and lifecycle must not survive into this one (the stale-budget-after-reload defect).
    this.agentBudget = null;
    this.unifiedLifecycles = [];
    this.unifiedThreadRefreshFailed = null;
    void this.refreshUnifiedThread();
    const resumed = await resumeConversation(sessionId, shapeId);
    // Tempdoc 629 (LAYER): the conversation store is encrypted + locked. Render a locked notice with an
    // Unlock affordance instead of an empty transcript (§L4: locked must never look deleted).
    this.historyLocked = resumed.locked === true;
    if (this.historyLocked) {
      this.thread = [];
      this.showResumePrompt = false;
      void loadConversations();
      return;
    }
    const resolvedShape: ShapeId =
      resumed.shapeId === 'core.rag-ask' ||
      resumed.shapeId === 'core.extract' ||
      resumed.shapeId === 'core.free-chat' ||
      resumed.shapeId === 'core.agent-run'
        ? resumed.shapeId
        : 'core.free-chat';
    // Slice 513 — if this is a branch, find the index of the branch point in
    // the resolved message list. All messages up to and including that index
    // were inherited from the parent.
    let inheritedThrough = -1;
    if (resumed.parentSessionId && resumed.branchPointMessageId) {
      for (let i = 0; i < resumed.messages.length; i++) {
        if (resumed.messages[i]?.id === resumed.branchPointMessageId) {
          inheritedThrough = i;
          break;
        }
      }
    }
    this.thread = resumed.messages.map((m, idx) => ({
      role: m.role,
      content: m.content,
      shapeId: resolvedShape,
      id: m.id,
      inheritedFromParent: idx <= inheritedThrough,
    }));
    // Slice 515 FIX-8 — capture parent preview for the branch banner.
    this.parentFirstMessagePreview = resumed.parentFirstUserMessage ?? null;
    // Tempdoc 610 Phase B — record this conversation's fork pointers so the
    // version pager can place itself on the divergent turn, and refresh the
    // conversation list so any freshly-created sibling is discoverable.
    this.branchParentId = resumed.parentSessionId ?? null;
    this.branchPointId = resumed.branchPointMessageId ?? null;
    // Tempdoc 610 Phase C — restore the effective-context floor so the divider
    // + out-of-context band render on reload.
    this.contextFloorId = resumed.contextFloor ?? null;
    // Tempdoc 610 Phase D — restore the compaction summary (if any).
    this.contextFloorSummary = resumed.contextFloorSummary ?? null;
    // Tempdoc 610 §E.3 — restore the per-message excluded set so the toggle state + dimming persist.
    this.excludedMessageIds = new Set(resumed.excludedMessageIds ?? []);
    setExcludedSources(resumed.excludedSourceIds ?? []);
    this.showFloorSummary = false;
    void loadConversations();
  }

  private exportMarkdown(): void {
    const md = exportConversationMarkdown(
      this.thread.map((m) => ({ role: m.role, content: m.content })),
      null,
    );
    void copyToClipboard(md);
  }

  private generateTitle(): void {
    if (this.thread.length < 2) return;
    const userMsg = this.thread.find((m) => m.role === 'user')?.content ?? '';
    const aiMsg = this.thread.find((m) => m.role === 'assistant')?.content ?? '';
    void generateConversationTitle(this.sessionId, userMsg, aiMsg);
  }

  // Tempdoc 577 Move 1 — renamed from `resumeSession`: this restores a CONVERSATION thread (a
  // different mechanism from the controller's agent-session resume, which now flows only through
  // the dispatchRunControl seam; the rename keeps the `.resumeSession(` channel pattern unambiguous).
  private restoreRecentConversation(sessionId: string): void {
    this.showResumePrompt = false;
    // Tempdoc 577 Goal 3 (§3.13 / A2) — leave the retrieve base tier when restoring a past chat, else
    // the loaded thread renders BEHIND the still-showing hit-list. The restored conversation is a
    // free-chat thread; viewing it needs no model (only sending a new turn does).
    if (this.affordance === 'retrieve') {
      this.affordance = 'none';
    }
    void this.loadConversation(sessionId, 'core.free-chat');
  }

  /**
   * Tempdoc 577 Goal 3 (§3.13 / A2) — the "Continue your last conversation?" landing card. Rendered in
   * BOTH the retrieve base tier and the LLM tiers so past chats stay reachable when the model is offline
   * (restoring LOADS a conversation to read; only sending a new turn needs the model). In the retrieve
   * tier it shows only on the bare landing (no query) so it never sits above a live hit-list. Derived
   * state (Ext III 3e): the card cannot render once any thread / event / run source has content.
   */
  private renderResumePrompt(): TemplateResult | typeof nothing {
    const queryActive = (this.searchSnapshot?.query ?? '').trim().length > 0;
    if (
      !this.showResumePrompt ||
      !this.recentSession ||
      this.thread.length !== 0 ||
      this.unifiedEvents.length !== 0 ||
      (this.agentCtrl?.conversation.length ?? 0) !== 0 ||
      (this.agentCtrl?.streamingText.length ?? 0) !== 0 ||
      (this.affordance === 'retrieve' && queryActive)
    ) {
      return nothing;
    }
    // Tempdoc 562: the preview snippet is derived from the lock-safe backend conversation list
    // (`firstUserMessage`, which `listSessions` returns as "" while the chat store is encrypted + locked) —
    // never from a client-side plaintext cache. A present-but-blank entry means the store is locked, so we
    // show the shared `conversations.locked` lock affordance (no content), never the message text.
    const conv = this.conversations.find((c) => c.id === this.recentSession!.sessionId);
    const preview = (conv?.firstUserMessage ?? '').trim();
    const locked = conv != null && preview === '';
    return html`<div class="resume-prompt">
      <span>Continue your last conversation?</span>
      ${preview !== ''
        ? html`<em>"${preview}"</em>`
        : locked
          ? html`<em class="resume-locked">${icon({ name: 'shield', size: 13 })} ${reasonFor('conversations.locked').wording}</em>`
          : nothing}
      <div class="resume-actions">
        <button class="resume-btn" @click=${() => this.restoreRecentConversation(this.recentSession!.sessionId)}>Continue</button>
        <button class="dismiss-btn" @click=${() => this.dismissResumePrompt()}>Start fresh</button>
      </div>
    </div>`;
  }

  private dismissResumePrompt(): void {
    this.showResumePrompt = false;
    // Tempdoc 609 Phase 3 — "Start fresh" is an explicit cold start; forget the last-viewed pointer
    // so a later return does not auto-restore the conversation the user just dismissed.
    clearLastViewedConversation();
  }

  private newConversation(): void {
    // Slice 516 FIX-T1 — cancel any in-flight stream so its onDone doesn't
    // append to the new (empty) thread.
    this.abortController?.abort();
    // Tempdoc 609 (M1) — clearing the recoverable chat store is now intent-driven: the
    // explicit "New chat" action resets the composer draft + mode, replacing the old
    // reset-on-mount that discarded a draft on every navigation.
    this.inputDraft = '';
    resetUnifiedChatState();
    // Tempdoc 609 Phase 3 — New chat means "don't auto-restore the old thread"; forget the pointer.
    clearLastViewedConversation();
    this.thread = [];
    this.sessionId = createConversationId();
    this.streamingText = '';
    this.errorMessage = '';
    this.citations = [];
    this.coverage = null;
    this.sourceCoverage = [];
    this.sources = [];
    this.ragMeta = null;
    this.rewriteNote = null;
    this.claims = [];
    // S8 live finding — the previous conversation's unified record (events/lifecycles) must not
    // survive into the fresh one: stale events kept isLanding() false, so New chat never
    // returned to the bare landing.
    this.unifiedEvents = [];
    this.unifiedLifecycles = [];
    this.unifiedThreadRefreshFailed = null;
    this.agentBudget = null;
    this.showResumePrompt = false;
    // Slice 515 FIX-1: forget the previous askAi-pinned docIds so a new
    // conversation starts with open-retrieval unless the user re-selects.
    this.pinnedDocIds = [];
    // S5a — New chat clears the sticky tier pin and any held schema attachment: the fresh
    // conversation derives back to the retrieve floor.
    this.explicitAffordance = null;
    this.schemaAttached = false;
    this.parentFirstMessagePreview = null;
    // Tempdoc 610 Phase B — a fresh conversation is a root: no fork pointers.
    this.branchParentId = null;
    this.branchPointId = null;
    // Tempdoc 610 Phase C/D — a fresh conversation has full context, no summary.
    this.contextFloorId = null;
    this.contextFloorSummary = null;
    this.contextPromptTokens = null;
    this.contextBreakdown = null;
    this.editingFloorSummary = false;
    this.floorSummaryDraft = '';
    this.excludedMessageIds = new Set();
    setExcludedSources([]);
    this.showFloorSummary = false;
    this.editingMessageId = null;
    this.editingDraft = '';
    // Tempdoc 577 Move 1 — accountability dies with its run (see loadConversation).
    this.agentBudget = null;
    this.unifiedLifecycles = [];
  }

  /**
   * Tempdoc 565 §30 — the live-run STEER input (the DIRECTION authority's `interject`). Renders only
   * while an agent run is in flight; submitting dispatches an `interject` directive through the ONE
   * control-intent seam, which the backend drains at the run's next step boundary and folds into the
   * next LLM call. (Peers: `halt` = the seam's session stop `dispatchRunControl({kind:'halt'})` →
   * `cancelSession`; the composer's inline cancel does a lighter `abortController.abort()` stream
   * teardown. `set-posture` = the autonomy dial → the global 561 P-D store, a peer channel.)
   */
  private renderSteerInput(): TemplateResult | typeof nothing {
    // Tempdoc 565 §33 — show ONLY for a steerable, in-flight agent run. Gate on the CONTROLLER's
    // streaming state (the view's own `this.isStreaming` tracks the non-agent answer plane and is FALSE
    // during an agent run — the original §30 gate used it by mistake, so the steer input never showed),
    // AND on `runKind === 'agent'`: a workflow run streams in the agent affordance too but goes through
    // WorkflowShapeRunner (no interject drain) + isn't an AgentLoopService session, so steering it 404s.
    const ctrl = this.agentCtrl;
    if (this.affordance !== 'agent' || !ctrl?.isStreaming || ctrl.runKind !== 'agent') {
      return nothing;
    }
    const submit = () => {
      const text = this.steerDraft.trim();
      const ctrl = this.agentCtrl;
      if (!text || !ctrl) return;
      void dispatchRunControl(ctrl, { kind: 'interject', text });
      this.steerDraft = '';
    };
    return html`<div class="run-steer" data-steer-input>
      <input
        class="run-steer__input"
        type="text"
        .value=${this.steerDraft}
        placeholder="Redirect the agent…"
        aria-label="Steer the running agent"
        @input=${(e: Event) => (this.steerDraft = (e.target as HTMLInputElement).value)}
        @keydown=${(e: KeyboardEvent) => {
          if (e.key === 'Enter') {
            e.preventDefault();
            submit();
          }
        }}
      />
      <jf-button
        size="sm"
        label="Steer the agent"
        .availability=${!this.steerDraft.trim()
          ? unavailableBecause('Type a direction to send')
          : undefined}
        .onActivate=${submit}
        >Steer</jf-button
      >
    </div>`;
  }

  private renderAffordancePreview(): TemplateResult | typeof nothing {
    if (this.affordance === 'documents') {
      return html`<div class="affordance-preview">Searching ${this.docCount} documents</div>`;
    }
    if (this.affordance === 'extract') {
      try {
        const schema = JSON.parse(this.schemaDraft);
        const propCount = Object.keys(schema?.properties ?? {}).length;
        return html`<div class="affordance-preview">Extracting with schema (${propCount} ${propCount === 1 ? 'property' : 'properties'})</div>`;
      } catch {
        return html`<div class="affordance-preview">Extracting with schema</div>`;
      }
    }
    return nothing;
  }

  static styles = [composerStyles, whyThisResultStyles, facetChipStyles, highlightStyles, scopeChipRowStyles, unifiedChatBodyStyles,
    // §13 Pillar B — the GENERATED grid frame for the conversation-zone (replaces the hand-authored
    // grid-template-columns + per-zone placements removed above; faithful per de-risk Probe S2).
    composeGridStyles(CONVERSATION_ZONES, {
      container: '.conversation-zone',
      // 798 round 8 — the query container is the surface host (`container-type: inline-size` in
      // unifiedChatBodyStyles), i.e. the box the zone is actually laid out in, not the viewport.
      containerName: 'chat-surface',
      breakpoint: '64rem',
      gap: '1.5rem',
    }),
    // Tempdoc 816 §5 — the docked composer, its escalation strip and the conversation column are three
    // rows of ONE reading column. The composer is a stable DOM slot that must NEVER re-parent (the
    // keystroke-drop race documented in renderAnswerPlane), so it cannot become a child of the zone;
    // instead it is laid out on the SAME generated frame and its children are placed in the SAME
    // column track. One zones authority, two consumers — not a hand-copied `max-width`.
    // `.answer-plane > .composer` deliberately: `<jf-composer>` renders its own `div.composer` into
    // THIS shadow root (Composer.ts createRenderRoot returns `this`), and only the outer wrapper is a
    // direct child of the query container.
    alignToZoneStyles(CONVERSATION_ZONES, {
      container: '.answer-plane > .composer',
      containerName: 'chat-surface',
      breakpoint: '64rem',
      // Row gap stays the composer's own tight stack (0.35rem); the COLUMN gap must equal the zone's
      // 1.5rem or the tracks — and therefore the column — would not coincide.
      gap: '0.35rem 1.5rem',
      alignTo: '.conversation',
      alignedChildren: '.answer-plane > .composer > *',
    }),
  ];

  /**
   * The shape this window would dispatch RIGHT NOW — the one computation the mode chip, the
   * streaming header, and {@link send} all read.
   *
   * Tempdoc 526 §16 F13 (single dispatch resolver) / §561 P-B3 (the agent affordance is the
   * action plane, so it is excluded from answer-plane shape resolution). The chip used to call
   * the resolver with the selection kind hardcoded to `'none'` while `send()` passed the live
   * kind, so the label could name a shape that was never dispatched.
   */
  private dispatchShape(): ShapeId {
    return resolveDispatchShape(
      this.affordance,
      wireSelectionKind(getCurrentSelection().items[0]?.kind),
    ) as ShapeId;
  }

  override render(): TemplateResult {
    const currentShape = this.dispatchShape();
    const agentMode = this.affordance === 'agent';
    // Tempdoc 561 P-B3 (Tier-1 correctness fix): both planes are rendered on every pass and the
    // inactive one is hidden (visibility toggle), NOT swapped via a ?: branch. A branch destroyed
    // <jf-agent-view> on every crossing → its disconnectedCallback ran AgentSessionController.destroy()
    // (abort streams, stop polling) → re-entry constructed a fresh controller → the in-progress run
    // (conversation, tool cards, pending approvals, streaming) was wiped. Keeping the element mounted
    // makes a chat↔agent round-trip lossless. We mount the agent plane lazily on first entry (so users
    // who never open the agent pay no idle-controller cost), then keep it mounted for the view's life.
    // Tempdoc 561 P-B (body-unification): ONE conversation body. No separate <jf-agent-view> plane and
    // no visibility swap — the agent run renders INLINE in the unified thread (renderLiveAgentActivity).
    // Lazily create the hosted controller on first crossing; keep it for the view's life (lossless
    // chat<->agent round-trip).
    if (agentMode) this.ensureAgentCtrl();
    return html`
      <div class="header">
        <div>
          <strong>Search</strong> — ask anything
          <jf-conversation-history
            @conversation-select=${(e: CustomEvent) => this.onConversationSelect(e)}
          ></jf-conversation-history>
          ${/* Search Thread S5b — the affordance-bar row retired; Activity (the retrospective drawer:
                Sessions/Timeline/History) moves next to New chat/Export so it stays reachable from
                every tier, not just from within the (now-gone) tab row. */ ''}
          <button
            class="new-chat-btn"
            @click=${() => toggleRetrospective()}
            title="Activity — sessions, system activity, this run, background runs"
          >
            Activity
          </button>
          ${/* Round-14 finding 14 — the header control set is RUNG-INVARIANT. New chat + Export used
                to carry `&& !agentMode`, so crossing to Delegate removed both with ~1000px of empty
                header space beside them (ruled out as overflow: three captures one click apart at an
                identical 1462x800). Nothing in the code or the design history justified the gate, and
                it stranded a finished, unresumable run with neither a reset nor a save affordance —
                Export worst of all, since a Delegate run is the costliest artifact the product makes.
                The remaining gate (`thread.length > 0`) is state, not rung: it holds identically on
                every rung, so it cannot make the set differ between them. */ ''}
          ${/* Tempdoc 821 §4 — `thread.length > 0` still hid New chat ENTIRELY on a fresh/empty chat,
                leaving no visible entry point for a control that is exactly the affordance a user
                reaches for on a fresh surface. New chat now always renders and is disabled (not
                removed) when there is nothing to reset, matching the .ver-nav disabled idiom. Export
                stays gated on thread state — with an empty thread there is genuinely nothing to
                export, unlike New chat which is meaningful UI chrome regardless of state. */ ''}
          <button
            class="new-chat-btn"
            ?disabled=${this.thread.length === 0}
            title=${this.thread.length === 0 ? 'Already a new chat' : nothing}
            @click=${() => this.newConversation()}
          >New chat</button>
          ${this.thread.length > 0
            ? html`<button class="new-chat-btn" @click=${() => this.exportMarkdown()}>Export</button>`
            : nothing}
        </div>
        <span class="shape-indicator">${
          agentMode
            ? 'Agent'
            : // Search Thread S5b — resolveShape falls through 'retrieve' to 'core.free-chat'
              // (SHAPE_LABELS 'Chat'), which read wrong once the header/rail/tab renamed to
              // "Search": the badge must not call the search-only tier "Chat".
              this.affordance === 'retrieve'
              ? 'Search'
              : SHAPE_LABELS[currentShape]
        }</span>
      </div>
      ${this.renderDegradationBanner()}
      ${this.renderThreadRefreshFailedNotice()}
      <div class="answer-plane">${this.renderAnswerPlane()}</div>
    `;
  }

  /**
   * Tempdoc 596 §11.4 — the chat window's degradation banner. The search surface already explains
   * an AI/readiness degradation with a reachable, REMEDIED notice (SearchSurface.renderDegradationBanner);
   * the chat surface — where the capability affordances get disabled — did not, so the only "why" was
   * a suppressed `title`. This mirrors that one idiom (the same `readinessNotice` projection +
   * `<jf-system-notice>` + remedy button) so the window-level reason ("AI offline · [Reload AI]") is
   * reachable and actionable beside the affordance bar. Reads the SAME ONE verdict (595 §4.2) the
   * search banner consumes, so the two windows cannot disagree.
   */
  private renderDegradationBanner(): TemplateResult | typeof nothing {
    const verdict = this.aiState?.verdict;
    if (!verdict) return nothing;
    // Round-14 finding 9 — the banner is warning-tier chrome, so an info-severity-only verdict does
    // not get it (the same verdict's causes are still carried, calmly, by Health). The tier decision
    // lives in the notice authority, beside the wording it gates, not as a local severity test here.
    if (!warrantsSearchDegradationBanner(verdict)) return nothing;
    const notice = readinessNotice(verdict);
    if (!notice) return nothing;
    // Tempdoc 738 — disclosure decides how much banner. Simple (default) is the one-line pill
    // (headline + remedy, raw causes hidden); Detailed shows the causes. A severe (`error`) verdict
    // opens expanded even in Simple so a genuine failure is never a single ellipsized line. The local
    // "See details" toggle (degradationBannerExpanded) lets a Simple user open a cosmetic notice on
    // demand; nothing is remembered per cause-set (687's seen-hash machinery is gone).
    // Tempdoc 814 §D2/§D6 — Detailed mode buys its extra height from the conversation, and on a short
    // window there is none to buy: below the block-axis breakpoint Detailed renders the pill FIRST and
    // expands on interaction (the expand chevron below), so the detail is one click away rather than
    // permanently in flow. An `error` verdict still forces expansion at any height — a genuine failure
    // is never a single ellipsized line. The 600 wording invariant is untouched either way: the pill
    // carries the worded headline + the remedy, and every worded cause stays reachable.
    const forcedExpanded =
      verdict.severity === 'error' || (isAdvancedMode() && !this.shortZone);
    if (!forcedExpanded && !this.degradationBannerExpanded) {
      return this.renderCollapsedDegradationBanner(verdict, notice);
    }
    const causes = dedupDegradationCauses(notice, verdict);
    return html`<jf-system-notice
      tone=${verdictTone(verdict.severity)}
      live="status"
      class="degradation-banner"
      data-testid="chat-degradation"
    >
      <span class="notice-row"
        >${icon({ name: 'alert-triangle', size: 13 })}
        <span class="degradation-summary"><strong>${notice.headline}</strong> ${notice.body}</span>
        ${/* Tempdoc 738 — the collapse ("See less") chevron shows only when the expansion is
              user-driven; a forced expansion (Detailed mode or a severe verdict) has nothing to
              collapse to, so hide the dead control. */ ''}
        ${forcedExpanded
          ? nothing
          : html`<button
              type="button"
              class="degradation-collapse"
              data-testid="chat-degradation-collapse"
              aria-expanded="true"
              title="See less"
              @click=${() => {
                this.degradationBannerExpanded = false;
              }}
            >
              ⌃
            </button>`}</span
      >
      ${causes.length > 0
        ? html`<ul class="notice-causes" data-testid="chat-degradation-causes">
            ${causes.map((c) => html`<li>${c}</li>`)}
          </ul>`
        : nothing}
      ${this.renderDegradationRemedy(notice)}
    </jf-system-notice>`;
  }

  /**
   * The collapsed one-line form: severity icon + "N causes — <headline>" + the strongest remedy + an
   * expand ("See details") chevron. Tempdoc 738 — this is the DEFAULT form in Simple mode (raw causes
   * hidden); {@link renderDegradationBanner} decides expanded-vs-collapsed from disclosure + severity.
   */
  private renderCollapsedDegradationBanner(
    verdict: SystemHealthVerdict,
    notice: ReadinessNoticeView,
  ): TemplateResult {
    const causes = dedupDegradationCauses(notice, verdict);
    const count = causes.length;
    return html`<jf-system-notice
      tone=${verdictTone(verdict.severity)}
      live="status"
      class="degradation-banner degradation-banner-collapsed"
      data-testid="chat-degradation"
    >
      <span class="notice-row notice-row-collapsed">
        ${icon({ name: 'alert-triangle', size: 13 })}
        <span class="degradation-summary" data-testid="chat-degradation-summary">
          ${count > 0 ? `${count} ${count === 1 ? 'cause' : 'causes'} — ` : ''}${notice.headline}
        </span>
        ${this.renderDegradationRemedy(notice)}
        <button
          type="button"
          class="degradation-expand"
          data-testid="chat-degradation-expand"
          aria-expanded="false"
          title="Show details"
          @click=${() => {
            this.degradationBannerExpanded = true;
          }}
        >
          ⌄
        </button>
      </span>
    </jf-system-notice>`;
  }

  /**
   * The ONE remedy render shared by the expanded + collapsed banner. `readinessNotice` already picks
   * a single highest-priority remedy (`pickRemedy`), so "the strongest remedy" needs no further
   * ranking here — there is only ever one.
   */
  private renderDegradationRemedy(notice: ReadinessNoticeView): TemplateResult {
    return html`<span class="notice-remedy">
      ${notice.remedy.kind === 'operation'
        ? html`<jf-op-button
            operation-id=${notice.remedy.operationId}
            api-base=${this.apiBase}
            data-testid="chat-degradation-remedy-op"
          ></jf-op-button>`
        : html`<jf-button
            variant="secondary"
            data-testid="chat-degradation-remedy-nav"
            .onActivate=${() => this.openRemedyTarget((notice.remedy as { target: string }).target)}
            >${notice.remedy.label}</jf-button
          >`}
    </span>`;
  }

  /** Tempdoc 596 §11.4 — navigate to a notice remedy target (mirrors SearchSurface.openRemedyTarget). */
  private openRemedyTarget(target: string): void {
    this.dispatchEvent(
      new CustomEvent('navigate-with-context', {
        detail: { target, state: {} },
        bubbles: true,
        composed: true,
      }),
    );
  }

  /**
   * Search Thread D2/D3 (stage S2) — Ask is PINNED to `'search'` when the documents affordance is
   * unavailable/blocked (Hard Invariant: never a silent no-op). Mirrors `RouteChip`'s own pinned
   * semantics so the chip and the actual Enter-key routing can never disagree.
   */
  private askPinned(): boolean {
    const a = projectAvailability('documents', this.aiState);
    return a.kind === 'unavailable' || a.kind === 'blocked';
  }

  /**
   * Search Thread D2/D3 (stage S2) — the route Enter (or the submit button) will take for the
   * current turn: the user's explicit per-turn override when set, else the {@link inferRoute}
   * heuristic guess — pinned to `'search'` whenever Ask is unavailable.
   */
  private currentRoute(): TurnRoute {
    if (this.askPinned()) return 'search';
    return this.routeOverride ?? inferRoute(this.inputDraft);
  }

  /**
   * Search Thread S5a — the standing tier is a COMPUTED projection (deriveAffordance in
   * agencyPosture.ts, the one authority): explicit choice > schema attachment > 'retrieve'.
   * Route is passed null here — a typed question flips the SUBMIT (escalateAsk), never the
   * standing view (the live search floor must not be yanked mid-keystroke). Reactivity holds
   * because every derivation input is itself reactive state.
   */
  get affordance(): Affordance {
    return deriveAffordance({
      explicit: this.explicitAffordance,
      route: null,
      hasSchemaAttachment: this.schemaAttached,
    });
  }

  /** Assignment IS explicit selection (tab clicks, tests, restores) — it pins the tier sticky. */
  set affordance(v: Affordance) {
    this.explicitAffordance = v;
  }

  /**
   * Search Thread S5b — the affordance TAB ROW is retired (the escalation affordances that replace
   * it: the route chip, the results card's Ask AI, and the landing escalation strip). The agent-only
   * controls the row used to host (the supervision dial, Abilities, Sources) move onto the composer,
   * rendered ONLY while `affordance === 'agent'` — {@link renderComposerBlock} calls this.
   */
  private renderAgentToolbar(): TemplateResult | typeof nothing {
    if (this.affordance !== 'agent') return nothing;
    return html`
      <div class="agent-toolbar">
        ${/* Tempdoc 561 C-2: the supervision dial makes the answer→action phase transition visible. */ ''}
        <jf-autonomy-dial compact></jf-autonomy-dial>
        ${/* Tempdoc 577 §2.13 #17 — the authority-space toggle: "what can this agent do, and what
            will ask first" — calibrate trust by inspection, before delegating. */ ''}
        <button
          class="agent-tool-btn"
          aria-pressed=${this.showAbilities ? 'true' : 'false'}
          @click=${() => {
            this.showAbilities = !this.showAbilities;
          }}
          title="What this agent can do, and what will ask first"
        >
          Abilities
        </button>
        ${/* Tempdoc 565 §3.A: open the answer's grounding sources (clickable local passages).
              Tempdoc 814 §D5 — the count has ONE authority: while the docked evidence rail is
              mounted its head owns it, so this chip does not RENDER (it was already CSS-hidden at
              wide widths — `unifiedChatStyles.ts` ~976 — which suppressed it visually while leaving
              a second count in the DOM for the status-fact singleton probe and for AT to read). */ ''}
        ${!this.evidenceRailMounted() && (this.agentCtrl?.answerSources.length ?? 0) > 0
          ? html`<button
              class="agent-tool-btn sources-affordance"
              @click=${() => toggleSources()}
              title="The latest answer's grounding sources"
            >
              Sources · ${this.agentCtrl?.answerSources.length ?? 0}
            </button>`
          : nothing}
      </div>
    `;
  }

  private renderAnswerPlane(): TemplateResult {
    // §21 AFFORDANCE — when the run-spine is mounted it IS the scroll control (the draggable minimap
    // thumb), so the reading column hides its native scrollbar (`scrollbar-gutter: stable` reserves the
    // gutter so hiding it causes no reflow). Documents/narrow (no spine) keeps the thin native bar.
    // Round-14 finding 15 — read the SAME predicate the spine mounts on: agent+wide is no longer
    // sufficient (an unsegmented single-turn run has no spine), and hiding the native bar behind an
    // absent minimap would leave the column with no scroll control at all.
    const spineShown = this.spineItems() !== null;
    // 798 round 8 — `landing-collapsed` swaps the zone's `flex: 1; min-height: 0` for content-sizing so
    // the composer can centre in the freed space (687 R5a). That premise is "the landing zone is empty",
    // and clearing the query does not empty it: nothing unmounts a reading pane on a query clear, so the
    // zone content-sized around a `height: 100%` pane whose basis had just become indefinite — the pane
    // laid out at full document height and pushed the composer and the escalation ladder below the fold
    // (reproduced twice at 1040x709, recoverable only by navigating away and back).
    //
    // Of the two repairs — suppress the collapse while a pane is mounted, or unmount the pane on a
    // return to landing — this takes the first. A preview is opened by a deliberate act and the pane
    // carries its own close control, so closing it is the user's call; clearing the search box is a
    // query-scoped action and should not destroy the reading surface (and its scroll position) as a
    // side effect. The composer's decorative centring is the thing that can afford to yield.
    const landingCollapsed = this.isLanding() && !this.documentPaneMounted();
    return html`
      <div class="conversation-zone ${landingCollapsed ? 'landing-collapsed' : ''}">
        ${this.renderRunSpine()}
        ${/* Tempdoc 814 §D7.4 — `tabindex="0"` because this is THE surface's scroll region (D3), and a
              scrollable region that no keyboard user can focus cannot be scrolled without a pointer
              (axe `scrollable-region-focusable`). It went unnoticed until the closure audit's finding C
              made a capture actually overflow: every prior capture measured `scrollableCount` 0, so the
              rule never had a scroller to fire on. Not a tab-stop for its own sake — the spine's
              `role="scrollbar"` thumb is the pointer/AT affordance, this is the plain-keyboard one. */ ''}
        <div
          id="run-conversation"
          tabindex="0"
          class="conversation ${spineShown ? 'spine-scrolled jf-scrollbar-none' : ''}"
        >
          ${/* Tempdoc 577 Goal 3 (§3.2) — the retrieve base tier renders the ephemeral hit-list IN
                the window; it owns no thread history. Escalation (Ask/Delegate) promotes to a turn. */ ''}
          ${/* Tempdoc 577 Goal 3 (§3.13 / A2) — the resume card renders BEFORE the tier split so past
                chats stay reachable in the retrieve base tier too (offline-friendly); renderResumePrompt
                guards it to the bare landing in retrieve, and "Continue" leaves retrieve to show the thread. */ ''}
          ${this.renderResumePrompt()}
          ${this.affordance === 'retrieve' ? this.renderRetrieveTier() : nothing}
          ${/* Search Thread D2/D3 (stage S2) — the bare landing (empty draft, no history, no active
                query) renders the centered search-bar landing INSIDE the conversation column, in place
                of the retired empty prompt (whose own branch now just returns nothing below). */ ''}
          ${this.affordance === 'retrieve'
            ? nothing
            : html`
          ${this.thread.some((m) => m.inheritedFromParent)
            ? html`<div class="branch-indicator">
                ↪ Branched from ${this.parentFirstMessagePreview
                  ? html`"<em>${this.parentFirstMessagePreview.slice(0, 80)}</em>"`
                  : 'an earlier conversation'}
              </div>`
            : nothing}
          ${/* Tempdoc 577 §2.13 #17 — the agent's authority-space, on demand: inspect what it can do
               and what would ask first, BEFORE delegating (the §2.11 #8 ceremony reachable by inspection). */ ''}
          ${this.showAbilities
            ? html`<div class="abilities-panel">
                <jf-agent-authority-panel
                  .tools=${this.agentCtrl?.tools ?? []}
                  level=${getAutonomyLevel()}
                ></jf-agent-authority-panel>
              </div>`
            : nothing}
          ${/* Tempdoc 585 §D Phase 1 (C1) — run-replay scrubber: shown only when the shared controller
               is in replayMode (a finished run loaded via RetrospectivePanel → loadReplay). */ ''}
          ${this.agentCtrl?.replayMode ? this.renderReplayBar() : nothing}
          ${/* Tempdoc 734 round-14 F4 (the second half of "200, no answer, NO ERROR"): the locked
                branch used to replace the whole column, error div included. A locked dispatch DID
                report itself — `loadEffectiveContext` → `readMeta` raises KeyLockedException before
                any append, and the controller writes it as an SSE `error` event — but the surface
                had already stopped rendering the only element that shows one. Whatever the stream
                says is now said in both branches; the transcript stays gated, the failure does not. */ ''}
          ${this.historyLocked
            ? html`${this.renderHistoryLocked()}
                ${this.errorMessage
                  ? html`<div class="error">${this.errorMessage}</div>`
                  : nothing}`
            : html`
                ${this.renderUnifiedConversation()}
                ${this.renderLiveOverlay()}
                ${this.renderStreamingBlock()}
                ${this.errorMessage
                  ? html`<div class="error">${this.errorMessage}</div>`
                  : nothing}`}`}
        </div>
        ${this.renderEvidenceRail()}
        ${this.renderDocumentPane()}
      </div>
      ${this.renderActivityRail()}
      ${this.renderContextMeter()}
      ${this.renderExcludedSummary()}
      ${/* Search Thread D2/D3 (stage S2, tempdoc decision 8) — the composer lives in ONE stable DOM
            slot in every state. Live-validation found that re-parenting it into a landing block drops
            keystrokes racing the first render (the landing→dock transition detaches the textarea
            mid-word), so landing centering is pure CSS: the `.landing-dock` class bounds and centers
            this container while the intro (title/corpus) renders in the conversation column and the
            escalation strip rides under the bar. */ ''}
      <div class="composer ${this.isLanding() ? 'landing-dock' : ''}">
        ${/* 687 R5a — ONE flex column owns title → corpus → bar → strip: the stateless intro
              renders INSIDE the stable composer container (the composer element itself still never
              re-parents — the stable-slot invariant holds; only static text moved). */ ''}
        ${this.isLanding() ? this.renderLanding() : nothing}
        ${this.renderComposerBlock()}
        ${/* Tempdoc 807 B.2 — the rungs used to render ONLY while isLanding(): once a search had run
              every escalation control left the DOM, so Delegate (round 11) and Structured (round 13,
              which cost that round its `shape:core.extract` coverage) were reachable only from an
              empty landing / a fresh session. The rungs THEMSELVES were already correct; only this
              condition was wrong. Landing keeps its exact strip (687 R5a's stable slot is untouched —
              both branches render in the SAME position inside the SAME composer container, and the
              composer element itself still never re-parents); the docked branch adds the two rungs the
              landing reaches through the route row (Search floor / + Schema), which is gone whenever
              the tier is not `retrieve`. */ ''}
        ${this.isLanding()
          ? html`<div class="escalation-strip">
              <div>Search instantly · no AI</div>
              ${this.renderEscalationRungs()}
            </div>`
          : html`<div
              class="escalation-strip escalation-strip-docked"
              data-testid="escalation-strip-docked"
            >
              ${/* The way BACK to the always-available floor. It clears the sticky pin AND any held
                    schema attachment: with the attachment held, clearing only the pin would re-derive
                    'extract' (deriveAffordance precedence) and the click would be a silent no-op. */ ''}
              <jf-control
                class="escalation-search"
                data-testid="escalation-search"
                ?data-pressed=${this.affordance === 'retrieve'}
                label=${rungLabel('Back to instant search — no AI needed', this.affordance === 'retrieve')}
                .onActivate=${() => {
                  this.explicitAffordance = null;
                  this.schemaAttached = false;
                  this.routeOverride = null;
                }}
                >Search — instant, no AI</jf-control
              >
              ${this.renderEscalationRungs()}
              ${/* Structured is an ATTACHMENT, not a place (S5a decision 6) — so this rung sets the
                    attachment and lets the ONE derivation authority resolve the tier, which also keeps
                    "Detach schema" honest. Clearing the sticky pin is required: `explicit` outranks the
                    attachment, so attaching from a pinned Ask/Delegate would otherwise change nothing. */ ''}
              <jf-control
                class="escalation-structured"
                data-testid="escalation-structured"
                ?data-pressed=${this.affordance === 'extract'}
                label=${rungLabel('Extract structured fields against a JSON schema', this.affordance === 'extract')}
                .availability=${this.aiState?.capabilities?.chat
                  ? undefined
                  : unavailableBecause('The local AI model is offline')}
                .onActivate=${() => {
                  this.explicitAffordance = null;
                  this.schemaAttached = true;
                }}
                >Structured — fields as JSON</jf-control
              >
            </div>`}
      </div>
    `;
  }

  /**
   * Tempdoc 807 B.2 — the two AI escalation rungs, rendered by BOTH the landing strip and the docked
   * one so the pair cannot drift (one definition of label, availability gate and test id). The
   * `data-pressed` bindings and the {@link rungLabel} active-suffix are inert on landing (isLanding()
   * implies the `retrieve` tier), so the landing renders the same elements, attributes, names and
   * order it did before.
   */
  private renderEscalationRungs(): TemplateResult {
    return html`
      ${/* Tempdoc 804 §B9 (round-10 F14) — Ask was the ONE escalation rung that failed
            silently offline: a plain <div>, so clicking it changed nothing and said nothing
            while its siblings (Delegate, Extract) both named the reason. It is the same kind
            of affordance as Delegate, so it is the same kind of control: availability-gated
            by the one operability authority, with the sibling wording. */ ''}
      <jf-control
        class="escalation-ask"
        data-testid="escalation-ask"
        ?data-pressed=${this.affordance === 'documents'}
        label=${rungLabel('Ask a question and get an answer with citations', this.affordance === 'documents')}
        .availability=${this.aiState?.capabilities?.chat
          ? undefined
          : unavailableBecause('The local AI model is offline')}
        .onActivate=${() => {
          this.affordance = 'documents';
        }}
        >Ask — answers with citations</jf-control
      >
      ${/* S8 live finding — the tab row's death orphaned agent-mode entry (the palette
            only carries diagnostics); until delegation folds into ask-turns entirely, the
            strip's Delegate line IS the entry (explicit pin, availability-gated). */ ''}
      <jf-control
        class="escalation-delegate"
        data-testid="escalation-delegate"
        ?data-pressed=${this.affordance === 'agent'}
        label=${rungLabel('Delegate a multi-step task to the agent', this.affordance === 'agent')}
        .availability=${this.aiState?.capabilities?.chat
          ? undefined
          : unavailableBecause('The local AI model is offline')}
        .onActivate=${() => {
          this.affordance = 'agent';
        }}
        >Delegate — the agent works multi-step</jf-control
      >
    `;
  }

  /**
   * The ONE reason this composer's submit cannot run right now, worded — empty string when it can.
   * Both the `submit-disabled` gate and the `submit-title` reason read it, so a disabled Send always
   * names why (the sibling of the escalation rungs' `unavailableBecause`, which words their own).
   *
   * Tempdoc 734 round-14 F4 adds the locked arm. It is gated on the affordance for the same reason
   * the backend gates its 423 on the write key: the `retrieve` tier runs a plain search, which is
   * neither AI-dependent nor encrypted — disabling it while locked would refuse a turn that works
   * (the locked notice itself promises "your search index is unaffected"). The empty-draft case is
   * NOT here: it disables Send with no wording because it needs none.
   */
  private sendBlockedReason(): string {
    if (this.aiState?.verdict?.kind === 'unreachable') return 'Backend disconnected';
    if (this.affordance === 'retrieve') return '';
    if (!this.aiState?.capabilities?.chat) return 'AI offline';
    if (this.historyLocked) {
      return `${reasonFor('conversations.locked').wording} — unlock it in Security to send`;
    }
    return '';
  }

  /**
   * Search Thread D2/D3 (stage S2) — the composer's inner content (steer input / affordance preview /
   * schema input / the retrieve-tier route chip / the one `jf-composer`), extracted so both the
   * docked (bottom) composer and the landing composer render the SAME template — never two mounted
   * instances.
   */
  private renderComposerBlock(): TemplateResult {
    return html`
      ${this.renderAgentToolbar()}
      ${this.renderSteerInput()}
      ${this.renderAffordancePreview()}
      ${this.affordance === 'extract' ? this.renderSchemaInput() : nothing}
      ${this.renderScopeChipRow()}
      ${this.affordance === 'retrieve' ? this.renderRouteRow() : nothing}
      <jf-composer
        cancellable
        .value=${this.inputDraft}
        placeholder=${this.getPlaceholder()}
        ?streaming=${this.isStreaming}
        ?submit-disabled=${!this.inputDraft.trim() || this.sendBlockedReason() !== ''}
        submit-label=${this.getSubmitLabel()}
        submit-title=${this.sendBlockedReason()}
        cancel-label=${this.streamingText ? 'Stop' : 'Cancel'}
        @composer-input=${(e: CustomEvent<{ value: string }>) => {
          this.inputDraft = e.detail.value;
          // Search Thread D2/D3 (stage S2) — the FLOOR RULE: every keystroke feeds instant search
          // regardless of route/affordance (the always-on search rail behind Ask/Delegate).
          setSearchQuery(e.detail.value);
          // Search Thread D2/D3 (stage S2) — an emptied draft drops any explicit route override so the
          // NEXT turn starts back at the plain heuristic guess rather than a stale flip.
          if (!e.detail.value.trim()) this.routeOverride = null;
        }}
        @composer-submit=${() => this.handleComposerSubmit()}
        @composer-submit-alt=${() => this.handleComposerSubmitAlt()}
        @composer-cancel=${() => this.abortController?.abort()}
        @keydown=${(e: KeyboardEvent) => this.handleComposerKeydown(e)}
      ></jf-composer>
    `;
  }

  /**
   * Search Thread D5 (stage S3) — the pinned scope-chip row: shown above the composer (and above the
   * retrieve-only route-chip row when present) in EVERY affordance — a pinned file/result-set scopes
   * both instant search and a grounded Ask, so it isn't gated to the retrieve tier. Empty when no
   * chips are pinned (renderScopeChips returns `nothing`).
   */
  private renderScopeChipRow(): TemplateResult | typeof nothing {
    if (this.scopeChips.length === 0) return nothing;
    return html`<div class="scope-row">
      ${renderScopeChips(this.scopeChips, { onRemove: (i) => this.handleScopeChipRemove(i) })}
    </div>`;
  }

  /**
   * Search Thread D5 (stage S3) — remove a pinned scope chip. Mirrors the facet-toggle re-issue
   * precedent: the store mutation is pure, the host decides whether to re-run the active search.
   */
  private handleScopeChipRemove(index: number): void {
    removeScopeChip(index);
    if (this.affordance === 'retrieve' && (this.searchSnapshot?.query ?? '').trim()) {
      submitSearch();
    }
  }

  /**
   * Search Thread D5 (stage S3) — Backspace on an EMPTY draft pops the last pinned scope chip (the
   * chip-as-token-in-the-input affordance). The composer renders its textarea in light DOM (own
   * `createRenderRoot` returns `this`), so a native (uncomposed) keydown bubbles from the textarea up
   * through `<jf-composer>` without crossing a shadow boundary — this handler on the host element
   * catches it directly; Composer itself is not edited.
   */
  private handleComposerKeydown(e: KeyboardEvent): void {
    if (e.key !== 'Backspace') return;
    if (this.inputDraft !== '') return;
    if (this.scopeChips.length === 0) return;
    this.handleScopeChipRemove(this.scopeChips.length - 1);
  }

  /**
   * Search Thread D2/D3 (stage S2) — the composer's Enter path. Only the retrieve affordance routes
   * per-turn (documents/extract/agent keep their existing dispatch, untouched this stage).
   */
  private handleComposerSubmit(): void {
    if (this.affordance !== 'retrieve') {
      void this.send();
      return;
    }
    this.runRoute(this.currentRoute());
  }

  /** Search Thread D2/D3 (stage S2) — Ctrl+Enter: the OPPOSITE of whatever Enter would do right now. */
  private handleComposerSubmitAlt(): void {
    if (this.affordance !== 'retrieve') return;
    this.runRoute(this.currentRoute() === 'search' ? 'ask' : 'search');
  }

  /**
   * Search Thread D2/D3 (stage S2) — run ONE route for the current turn. Never escalates to `'ask'`
   * while pinned (Hard Invariant: no silent no-op — the pinned reason is reachable via the chip's own
   * tooltip, so a forced-search fallback here is a safe default, not a swallowed intent).
   */
  private runRoute(route: TurnRoute): void {
    const effective = route === 'ask' && this.askPinned() ? 'search' : route;
    if (effective === 'search') {
      submitSearch();
      this.routeOverride = null;
    } else {
      this.escalateAsk();
    }
  }

  /**
   * Search Thread D2/D3 (stage S2) — escalate the current retrieve turn to a grounded Ask.
   * Search Thread S4-final — asking is a consequence: commit-on-consequence freezes the active
   * search BEFORE the affordance flips away from retrieve (reason 'ask') — flipping first would
   * leave `searchSnapshot` looking the same, but the commit belongs to the query the user was
   * escalating FROM, so the order is deliberate.
   */
  private escalateAsk(): void {
    this.commitLiveSearch('ask');
    // S5a — the submit-time derivation: route 'ask' derives the documents tier through the ONE
    // authority (agencyPosture.deriveAffordance), pinned for the conversation that follows.
    this.explicitAffordance = deriveAffordance({
      explicit: null,
      route: 'ask',
      hasSchemaAttachment: false,
    });
    void this.send();
    this.routeOverride = null;
  }

  /**
   * Search Thread D2/D3 (stage S2) — the visible per-turn route indicator, shown only in the retrieve
   * affordance (documents/extract/agent have no ambiguous Enter to disambiguate).
   * Search Thread S4-final — also hosts the "⌄ recent" query-trail dropdown.
   */
  /**
   * S5b pin-parity — pin/unpin the CURRENT query (pinnedSearchState, the same persisted store
   * the retired SearchSurface's header used). Rendered only when a query is active.
   */
  /**
   * Search Thread Round-2 R4 — a `jf-control` composition (the RouteChip precedent: skin the
   * composed atom via `::part(control)` rather than a bespoke button class). The pinned/unpinned
   * state is carried by the accessible LABEL + slot text (not `aria-pressed` — `jf-control`'s
   * internal button has no toggle-state passthrough); `?data-pressed` is a plain presentation
   * attribute the quiet-tier stylesheet keys its "active" look off of.
   */
  private renderPinToggle(): TemplateResult | typeof nothing {
    const q = (this.searchSnapshot?.query ?? '').trim();
    if (!q) return nothing;
    const pinned = isPinned(q);
    return html`<jf-control
      class="pin-toggle"
      data-testid="pin-toggle"
      ?data-pressed=${pinned}
      label=${pinned ? 'Unpin this search' : 'Pin this search'}
      .onActivate=${() => {
        if (pinned) {
          const pin = this.pinnedSearches.find((p) => p.query === q);
          if (pin) unpinSearch(pin.id);
        } else {
          pinSearch(q);
        }
      }}
      >${pinned ? '★ Pinned' : '☆ Pin'}</jf-control
    >`;
  }

  private renderRouteRow(): TemplateResult {
    const route = this.currentRoute();
    return html`<div class="route-row">
      ${this.renderPinToggle()}
      ${this.renderQueryTrail()}
      ${/* S5a (decision 6) — Structured is an attachment you ADD to the bar: attaching seeds the
            draft template (if empty) and the tier derives 'extract' while it is held. */ ''}
      ${this.schemaAttached
        ? nothing
        : html`<jf-control
            class="schema-attach"
            data-testid="schema-attach"
            label="Attach a JSON schema — turns this turn into a structured extraction"
            .onActivate=${() => {
              this.schemaAttached = true;
            }}
            >+ Schema</jf-control
          >`}
      <jf-route-chip
        .route=${route}
        .askAvailability=${projectAvailability('documents', this.aiState)}
        ?pinned=${this.askPinned()}
        @route-toggle=${() => {
          this.routeOverride = route === 'search' ? 'ask' : 'search';
        }}
      ></jf-route-chip>
    </div>`;
  }

  /**
   * Search Thread D2/D3 (tempdoc decision 8, stage S2) — the bare landing: no draft, no history, no
   * active query, no agent conversation. The search bar is CENTERED in the conversation column rather
   * than docked at the bottom; it docks the moment any of those conditions stop holding.
   */
  private isLanding(): boolean {
    return (
      this.affordance === 'retrieve' &&
      !this.isStreaming &&
      this.thread.length === 0 &&
      this.unifiedEvents.length === 0 &&
      (this.agentCtrl?.conversation.length ?? 0) === 0 &&
      (this.agentCtrl?.streamingText.length ?? 0) === 0 &&
      (this.searchSnapshot?.query ?? '').trim() === ''
    );
  }

  /**
   * Search Thread D2/D3 (tempdoc decision 8, stage S2) — the landing INTRO only (title + corpus
   * line). The composer itself never moves (stable-slot rule above); this block renders at the
   * bottom of the conversation column so the intro sits directly above the CSS-centered bar.
   */
  private renderLanding(): TemplateResult {
    const docs = this.aiState?.lastSettledIndex;
    // Tempdoc 811 C-4 — the default-search-scope population, falling back to the whole-index count
    // when an older backend omits the field (`null`). A reported `0` is real: the landing then
    // offers "Add folders" rather than claiming to search 0 files.
    const fileCount = docs == null ? null : (docs.searchableDocumentCount ?? docs.documentCount);
    const hasDocs = fileCount != null && fileCount > 0;
    return html`
      <div class="landing">
        <div class="landing-title">Search your files</div>
        <div class="landing-corpus" data-testid="landing-corpus">
          ${hasDocs
            ? html`Searching ${fileCount} files`
            : html`<button
                type="button"
                class="landing-add-folders"
                @click=${() => this.openRemedyTarget('core.library-surface')}
              >
                Add folders in Library to start searching
              </button>`}
        </div>
        ${/* S5b pin-parity — pinned searches (persisted, UserStateDocument) resurface on the
              landing: one click re-runs the saved query through the normal floor. */ ''}
        ${this.pinnedSearches.length > 0
          ? html`<div class="pinned-row" data-testid="landing-pins" role="list" aria-label="Pinned searches">
              ${this.pinnedSearches.map(
                (pin) => html`<button
                  type="button"
                  role="listitem"
                  class="pinned-search-btn"
                  title="Run pinned search"
                  @click=${() => this.runPinnedSearch(pin)}
                >
                  ${pin.query}
                </button>`,
              )}
            </div>`
          : nothing}
      </div>
    `;
  }

  /** S5b pin-parity — re-run a pinned search: restore the draft + fire the floor. */
  private runPinnedSearch(pin: SearchPin): void {
    this.explicitAffordance = null;
    this.routeOverride = null;
    this.inputDraft = pin.query;
    setSearchQuery(pin.query);
    this.onFocusComposer();
  }

  /** Search Thread D2/D3 (stage S2) — window-level `jf-focus-composer` → focus the textarea. */
  private onFocusComposer(): void {
    const textarea = this.shadowRoot?.querySelector('jf-composer textarea');
    if (textarea instanceof HTMLTextAreaElement) textarea.focus();
  }

  /**
   * Tempdoc 565 §12.3.D/E — the persistent evidence rail: the THIRD zone of the three-zone layout (a
   * DOCKED <jf-sources-pane> — the SAME component as the toggle drawer, NOT a fork — mounted inline in
   * the conversation's right column, OUTSIDE the single-drawer arbiter so it never closes when the
   * retrospective/advisory drawers open). Shown in agent mode whenever the answer carries grounding, so
   * the evidence is ambient (always visible), not modal. CSS hides it below the wide breakpoint, where
   * the "Sources · N" affordance + the toggle drawer remain the fallback. Cross-highlights the inline
   * [n] marks via the shared selectedSource store.
   */
  /**
   * Tempdoc 814 §D5 — is the docked evidence rail mounted? THE predicate: {@link renderEvidenceRail}
   * mounts on it and the in-answer source-count renders suppress on it, so "the rail's head is the
   * one persistent authority for the source count" cannot drift into two disagreeing conditions.
   *
   * Fix F — the docked rail mounts ONLY at the wide breakpoint (where it is visible); narrow viewports
   * fall back to the "Sources · N" chip + the toggle drawer. So exactly one SourcesPane subscribes per
   * viewport, not a dormant duplicate. Default to mounted when matchMedia is unavailable (tests/SSR).
   */
  private evidenceRailMounted(): boolean {
    return (
      this.affordance === 'agent' &&
      (this.agentCtrl?.answerSources.length ?? 0) > 0 &&
      this.wideZone
    );
  }

  private renderEvidenceRail(): TemplateResult {
    if (!this.evidenceRailMounted()) return html`${nothing}`;
    return html`<jf-sources-pane
      docked
      class="evidence-rail"
      .maxVisible=${EVIDENCE_RAIL_MAX_VISIBLE}
      api-base=${this.apiBase}
      .host_=${this.host_ ?? undefined}
    ></jf-sources-pane>`;
  }

  /**
   * Is the reading pane mounted INSIDE the conversation grid? 687 R5b — the grid mount is wide-only;
   * below the breakpoint the SAME component presents through Shell's OverlayHost right-drawer slot (the
   * one sanctioned overlay seam) instead of auto-placing into an implicit stacked row (the
   * audit-measured composer collision). The ONE predicate {@link renderDocumentPane} and the
   * landing-collapse gate in {@link renderAnswerPlane} share, so the class and the mount agree.
   */
  private documentPaneMounted(): boolean {
    return this.readingDocPath !== null && this.wideZone;
  }

  /**
   * Search Thread S6 (the Reading Stage) — the reading pane (`<jf-document-pane>`, `.document-pane`
   * zone col 5), mounted only while `readingDocPath` is set (empty-collapse: an unmounted zone's
   * `fit-content` track collapses to 0, same mechanism as {@link renderEvidenceRail}) and the surface
   * is wide enough for the grid to give it a column ({@link documentPaneMounted}).
   */
  private renderDocumentPane(): TemplateResult | typeof nothing {
    if (!this.documentPaneMounted()) return nothing;
    return html`<jf-document-pane
      class="document-pane"
      api-base=${this.apiBase}
      .docPath=${this.readingDocPath}
      .highlightRange=${this.readingHighlightRange}
      .chunkRange=${this.readingChunkRange}
      @pane-close=${() => this.handleDocumentPaneClose()}
    ></jf-document-pane>`;
  }

  /** Search Thread S6 — the reading pane's own close action. Clears the local reading state AND
   *  closes the shared inspectorState (`setOpen(false)`) so a plugin polling `host.getInspectorState()`
   *  sees the close too (parity with the retired InspectorPane's close button, which called the same
   *  `setOpen(false)`). */
  private handleDocumentPaneClose(): void {
    this.readingDocPath = null;
    this.readingHighlightRange = null;
    this.readingChunkRange = null;
    setInspectorOpen(false);
  }

  /**
   * The timeline items the run-spine projects, or `null` when the spine must NOT mount.
   *
   * Tempdoc 565 §13/§19.4 — the WHOLE merged timeline as a POSITION-PROPORTIONAL minimap: primary
   * turns (user/assistant) are landmark nodes placed at their conversation scroll fraction, the
   * secondary/ambient steps are smaller texture interpolated between them (`computeSpinePositions`).
   *
   * Round-14 finding 15 — the spine is the `RunSegmentRef` / `assignRunSegments` node-boundary
   * visualization ("the spine marks node boundaries", 565 §26), and it was rendering UNCONDITIONALLY
   * in ordinary chat, where there are no boundaries worth marking: measured live against four content
   * blocks of a SINGLE turn it drew ~10 markers in three glyph types with no legend, at ~2.5x content
   * density, colliding in colour with both the grounded-status dot and the user bubble. The defect is
   * the unconditional render, not the component — so it mounts only when the run HAS structure to
   * index: more than one turn, or real workflow-node boundaries.
   *
   * This is ONE predicate on purpose: `renderAnswerPlane` hides the reading column's native scrollbar
   * when the spine is mounted (the minimap IS the scroll control), so a spine gated separately from
   * that would leave a surface with neither.
   */
  private spineItems(): UnifiedTurnItem[] | null {
    if (this.affordance !== 'agent') return null;
    if (!this.wideZone) return null;
    const items = this.mergedTimeline().filter(
      (it) =>
        it.kind === 'user' ||
        it.kind === 'assistant' ||
        it.kind === 'tool-activity' ||
        it.kind === 'progress' ||
        it.kind === 'error',
    );
    if (items.length === 0) return null;
    const turns = items.filter((it) => it.kind === 'user').length;
    // A run whose steps all sit in one node (or in none) has no boundary to mark — one distinct
    // `nodeId` is not a boundary, it is the whole run.
    const nodeIds = new Set(items.map((it) => it.segment?.nodeId).filter((id) => id !== undefined));
    if (turns < 2 && nodeIds.size < 2) return null;
    return items;
  }

  /**
   * Tempdoc 565 §12.3.D/F — the left run-spine: "one ordered run made visual." The LATEST run's steps
   * render as a persistent vertical status spine (a minimap) in the conversation's left margin — one
   * node per step (status-tinted via the §3.B `statusAccent`), the answer the terminal node — so the
   * run's status is scannable at a glance even when the inline trace is collapsed. Positioned in the
   * margin (no grid disruption); wide viewports only; `aria-hidden` because the real, operable content
   * is the conversation — this is a decorative projection of the SAME merged timeline.
   */
  private renderRunSpine(): TemplateResult {
    const items = this.spineItems();
    if (!items) return html`${nothing}`;
    const activeId = this.nav.activeId;
    // Tempdoc 565 §17 — the ONE run-step presentation descriptor per item; §19.3 — its declared
    // prominence weight.
    const pres = items.map((it) => stepPresentation(it));
    // §13/§19.3 — the terminal "Answer" peak is the LAST assistant message of each TURN. The agent loop
    // emits several assistant messages per turn (intermediate tool-call preambles + the final answer);
    // only the final one is the destination, so only it gets TERMINAL_NODE_WEIGHT. Earlier intermediate
    // assistants recede to `secondary` texture (and are relabelled below — they are not answers).
    const terminalIds = terminalAssistantIds(items);
    // Tempdoc 565 §26.B — the FIRST item of each workflow node is a segment-boundary landmark on the
    // spine (the node-graph structure §15.C flattened, made navigable). A run with no nodes has none.
    const segmentStartIds = new Set<string>();
    let prevNodeId: string | undefined;
    for (const it of items) {
      const nid = it.segment?.nodeId;
      if (nid && nid !== prevNodeId) segmentStartIds.add(it.id);
      prevNodeId = nid;
    }
    const weights = items.map((it, idx) => {
      if (it.kind === 'assistant') {
        return terminalIds.has(it.id) ? TERMINAL_NODE_WEIGHT : PROMINENCE_SCALE.secondary;
      }
      return PROMINENCE_SCALE[pres[idx]!.prominence];
    });
    // §19.4 — each node sits at its conversation scroll fraction: the minimap contract is that a dot
    // points to WHERE its content actually is. Turns anchor at their measured midpoint; intra-turn steps
    // interpolate between anchors (`computeSpinePositions`). There is deliberately NO even-spacing blend —
    // a faithful position map. A long answer legitimately leaves a marker-free stretch (the viewport box
    // marks the reading position there), and the de-overlap pass below is the ONLY adjustment (minimal,
    // collision-only — it preserves position except where two nodes would otherwise overlap).
    const fractions = computeSpinePositions(items, this.nav.fractions);
    // §19.4 placement facet (565 §19 / 559 Adaptivity) — de-overlap the ideal fractions so dense runs
    // don't pile nodes onto the same point (the measured-audit defect). Convert to px against the
    // measured track height, then space with the min-separation primitive (order-preserving, minimal
    // displacement). Before the track is measured (first paint / jsdom) trackPx is 0 → fall back to the
    // %-based ideal placement (graceful, like the sibling adaptive primitives).
    const PX_PER_REM = 16;
    const trackPx = this.nav.trackPx;
    const spacedPx: number[] | null =
      trackPx > 0
        ? computeSpacedPositions(
            fractions.map((f) => f * trackPx),
            weights.map((w) => w.sizeRem * PX_PER_REM),
            trackPx,
            2,
          )
        : null;
    // Tempdoc 814 §D4 — a LANDMARK is a structural index entry (a turn, a workflow-node boundary, a
    // human steering directive). It renders as its own marker always: never merged into a cluster, and
    // it breaks a cluster run, so the spine's density floor is the run's STRUCTURE.
    const isLandmark = items.map(
      (it) =>
        it.kind === 'user' ||
        it.kind === 'assistant' ||
        segmentStartIds.has(it.id) ||
        it.attributes?.steer === true,
    );
    // §D4's declared aggregation rule: everything else that still sits within SPINE_CLUSTER_MIN_GAP_PX
    // after the de-overlap pass collapses into ONE counted badge, so marker count is bounded by the
    // track (≈ trackPx / gap), not by event count. Unmeasured (first paint / jsdom) → %-placement, one
    // marker per item (no clustering without a measured track).
    const groups: PlacedGroup[] = spacedPx
      ? clusterAdjacent(
          spacedPx,
          isLandmark.map((l) => !l),
          SPINE_CLUSTER_MIN_GAP_PX,
        )
      : fractions.map((f, i) => ({ positionPx: f * 100, indices: [i] }));
    const topOf = (g: PlacedGroup): string =>
      spacedPx ? `${g.positionPx.toFixed(2)}px` : `${g.positionPx.toFixed(2)}%`;
    // §13 Pillar A binding — the spine is an operable nav (keyboard-operable buttons with accessible
    // names → controls-a11y-clean); click/Enter jumps the reading column to that timeline item, and the
    // scroll-spy marks the in-view node `.active`.
    // §13/§19 — the viewport indicator: a decorative box marking the slice of the conversation on
    // screen, so the long answer body is navigable and the full track reads as a map (the spatial
    // "reading position" binding §13 specified). Drawn behind the operable nodes; hidden when nothing
    // scrolls. The active-item ring stays the per-item cue; this is the where-in-the-scroll cue.
    const vp = this.nav.viewport;
    return html`<nav class="run-spine" aria-label="Run timeline — jump to a turn">
      ${vp
        ? html`<div
            class="run-spine-viewport"
            role="scrollbar"
            tabindex="0"
            aria-controls="run-conversation"
            aria-orientation="vertical"
            aria-label="Scroll the conversation"
            aria-valuemin="0"
            aria-valuemax="100"
            aria-valuenow=${Math.round(vp.topFrac * 100)}
            style=${`top:${(vp.topFrac * 100).toFixed(2)}%;height:${((vp.botFrac - vp.topFrac) * 100).toFixed(2)}%`}
            @pointerdown=${this.onSpineThumbPointerDown}
            @pointermove=${this.onSpineThumbPointerMove}
            @pointerup=${this.onSpineThumbPointerUp}
            @pointercancel=${this.onSpineThumbPointerUp}
            @keydown=${this.onSpineThumbKeyDown}
          >
          </div>`
        : nothing}
      ${groups.map((g) => {
        // Tempdoc 814 §D4 — an aggregated group renders as ONE counted badge: a real <button> (the
        // same jump control the single markers are, so it is keyboard-operable by construction) whose
        // accessible name states what it stands for ("5 steps, 2 errors"), jumping to the first member.
        if (g.indices.length > 1) {
          const first = items[g.indices[0] as number]!;
          let errors = 0;
          let warnings = 0;
          for (const i of g.indices) {
            const tone = pres[i]!.tone;
            if (tone === 'error') errors++;
            else if (tone === 'warning') warnings++;
          }
          const parts = [`${g.indices.length} steps`];
          if (errors > 0) parts.push(`${errors} error${errors === 1 ? '' : 's'}`);
          if (warnings > 0) parts.push(`${warnings} warning${warnings === 1 ? '' : 's'}`);
          const clusterLabel = `${parts.join(', ')} — jump to the first`;
          const isActive = g.indices.some((i) => items[i]!.id === activeId);
          return html`<button
            type="button"
            class="run-spine-cluster ${isActive ? 'active' : ''} ${errors > 0 ? 'has-error' : ''}"
            style=${`top:${topOf(g)}`}
            data-cluster-size=${g.indices.length}
            title=${clusterLabel}
            aria-label=${clusterLabel}
            @click=${() => this.nav.jumpTo(first.id)}
          >
            <span aria-hidden="true">${g.indices.length}</span>
          </button>`;
        }
        const idx = g.indices[0] as number;
        const it = items[idx]!;
        // The button owns placement/size/active/jump; <jf-run-node> owns the glyph+tone visual
        // (density `minimal` → a clean tone-dot at this scale, §19.2).
        const p = pres[idx]!;
        const w = weights[idx]!;
        // Tempdoc 565 §26.B — a segment-boundary node names its workflow node ("Node: think — jump").
        const isBoundary = segmentStartIds.has(it.id);
        // Intermediate assistant messages are NOT the answer — only the terminal one per turn reads
        // "Answer" (the prominence demotion is in `weights` above; this is the matching label).
        const label = isBoundary
          ? `Node: ${it.segment?.label ?? it.segment?.nodeId ?? 'step'} — jump`
          : it.kind === 'assistant' && !terminalIds.has(it.id)
            ? 'Working step'
            : p.label || spineNodeLabel(it);
        const style = `top:${topOf(g)};--node-size:${w.sizeRem}rem;opacity:${w.opacity}`;
        // Tempdoc 565 §30 — a human STEERING directive (the DIRECTION authority's interject) is a
        // human-origin POINT landmark on the spine, marked so it reads distinctly from agent steps.
        const isSteer = it.attributes?.steer === true;
        // Tempdoc 565 §29 Tier-2 — a run-health tick: an error step gets a distinct marker so a glance
        // at the spine shows which nodes failed (the error tone is already projected; this names it).
        const isError = it.kind === 'error';
        const spineLabel = isSteer
          ? `Your direction: ${it.content} — jump`
          : isError
            ? `Error: ${it.content.slice(0, 50)} — jump`
            : label;
        return html`<button
          type="button"
          class="run-spine-node ${it.id === activeId ? 'active' : ''} ${isBoundary
            ? 'node-boundary'
            : ''} ${isSteer ? 'steer-landmark' : ''} ${isError ? 'has-error' : ''}"
          style=${style}
          data-item-id=${it.id}
          ?data-steer=${isSteer}
          title=${spineLabel}
          aria-label=${spineLabel}
          @click=${() => this.nav.jumpTo(it.id)}
        >
          <jf-run-node
            density="minimal"
            ?outline=${!isLandmark[idx]}
            .presentation=${p}
          ></jf-run-node>
        </button>`;
      })}
    </nav>`;
  }

  /**
   * Tempdoc 565 §19.4 — project each timeline item to its 0..1 vertical position in the minimap. Items
   * with a conversation anchor (user/assistant turns, measured into `this.nav.fractions`) sit at their
   * scroll fraction; the intra-turn steps (tool/progress/error — which live inside a turn's collapsible
   * trace and have no independent scroll position) are interpolated evenly between the surrounding
   * anchors, so they read as texture between the landmarks. With no measurement yet (first paint / jsdom)
   * every item is unanchored → an even spread over 0..1, a graceful default.
   */
  // §21 AFFORDANCE — the minimap-as-scrollbar pointer/keyboard handlers. Bound arrow FIELDS so the
  // template references them without an inline `=>` (a `>` in the attribute would truncate the
  // controls-a11y tag scan); the scroll math lives in the NavigationController (Spike A's exact mapping).
  private readonly onSpineThumbPointerDown = (e: PointerEvent): void => {
    const el = e.currentTarget as HTMLElement;
    el.setPointerCapture?.(e.pointerId);
    this.nav.beginDrag(e.clientY);
    e.preventDefault(); // don't let the grab start a text selection / native scroll …
    el.focus?.({ preventScroll: true }); // … but still focus the thumb so arrow-keys work after a grab
  };
  private readonly onSpineThumbPointerMove = (e: PointerEvent): void => {
    const el = e.currentTarget as HTMLElement;
    if (!el.hasPointerCapture?.(e.pointerId)) return; // act only while a drag is captured
    this.nav.dragTo(e.clientY);
  };
  private readonly onSpineThumbPointerUp = (e: PointerEvent): void => {
    (e.currentTarget as HTMLElement).releasePointerCapture?.(e.pointerId);
  };
  private readonly onSpineThumbKeyDown = (e: KeyboardEvent): void => {
    const map: Record<string, 'line-up' | 'line-down' | 'page-up' | 'page-down' | 'home' | 'end'> = {
      ArrowUp: 'line-up',
      ArrowDown: 'line-down',
      PageUp: 'page-up',
      PageDown: 'page-down',
      Home: 'home',
      End: 'end',
    };
    const kind = map[e.key];
    if (!kind) return;
    e.preventDefault(); // the thumb owns these keys (don't also scroll the page)
    this.nav.nudge(kind);
  };

  /**
   * Tempdoc 561 P-A/P-B (Slice 2): refresh the unified thread from the canonical record. Best-effort
   * — failures leave the live this.thread render in place (the projector never becomes an authority).
   *
   * <p>Tempdoc 727 F-8: that silent fallback used to be truly silent — a backend bug that 500'd
   * `/api/thread/{id}` for every encrypted conversation left the user staring at an empty thread with
   * no hint anything was wrong. `fetchUnifiedThread`'s `onFailure` callback is the out-of-band signal;
   * it does not change the EMPTY-on-failure contract itself (still pinned by
   * `unifiedThreadClient.test.ts`'s "returns EMPTY on a non-ok response" case).
   */
  private async refreshUnifiedThread(): Promise<void> {
    if (!this.sessionId) return;
    let failed: { reason: ThreadFetchFailureReason; detail?: string } | null = null;
    const res = await fetchUnifiedThread(this.apiBase, this.sessionId, undefined, (reason, detail) => {
      failed = { reason, detail };
    });
    this.unifiedThreadRefreshFailed = failed;
    this.unifiedEvents = res.events;
    this.unifiedLifecycles = res.lifecycles;
    this.hydrateAnswerEvidenceFromRecord(res.events);
    this.requestUpdate();
  }

  /**
   * Tempdoc 727 F-8 — the honest, non-alarming failed-refresh notice. Reuses `<jf-system-notice>` (the
   * same primitive the AI-degradation banner renders through, tempdoc 559 Authority III) rather than a
   * new component; `tone="info"` keeps it visually distinct from the degradation banner's warning/error
   * tones, since a failed thread refresh is a different, usually transient cause (a request failure),
   * not an ongoing AI-readiness degradation — the two must not be conflated into one banner.
   */
  private renderThreadRefreshFailedNotice(): TemplateResult | typeof nothing {
    if (!this.unifiedThreadRefreshFailed) return nothing;
    const { reason, detail } = this.unifiedThreadRefreshFailed;
    const statusSuffix = reason === 'http-error' && detail ? ` (status ${detail})` : '';
    return html`<jf-system-notice
      tone="info"
      live="status"
      class="thread-refresh-failed-notice"
      data-testid="thread-refresh-failed"
    >
      <span class="notice-row"
        >${icon({ name: 'alert-triangle', size: 13 })} Couldn't load the full activity thread — showing
        what's here.${statusSuffix}</span
      >
    </jf-system-notice>`;
  }

  /**
   * Tempdoc 565 §3.A/persistence — on (re)load the live controller carries no evidence (loadConversation
   * rebuilds role/content only). Rehydrate the answer's sources/citations from the latest persisted agent
   * assistant message so the Sources pane + the "Sources · N" affordance render from the record. The
   * record is the single authority; this never invents data.
   */
  private hydrateAnswerEvidenceFromRecord(events: readonly ThreadEvent[]): void {
    for (let i = events.length - 1; i >= 0; i--) {
      const e = events[i];
      const sources = e?.attributes?.sources;
      if (e?.kind === 'ASSISTANT_MESSAGE' && Array.isArray(sources) && sources.length > 0) {
        const scorer = e.attributes.citationScorer;
        this.agentCtrl?.hydrateAnswerEvidence(
          sources as AgentSource[],
          Array.isArray(e.attributes.citations) ? (e.attributes.citations as AgentSentenceCite[]) : [],
          // The run whose evidence this is: the controller's own, since this rehydrates the run the
          // window is displaying (tempdoc 859 §3c).
          this.agentCtrl?.sessionId ?? null,
          // Tempdoc 859 §4 / amendment 6 — the record's producer stamp, or `null` for a record
          // written before the field existed (the pre-stamp allowance). Without carrying it here the
          // legacy surface would pass `undefined` forever and the gate would never fire on it.
          typeof scorer === 'string' ? scorer : null,
        );
        return;
      }
    }
  }

  /**
   * Tempdoc 561 P-B (body-unification) + surface tier: the inline agent run uses the ONE shared
   * controller (agentSessionStore) — the same instance the retrospective drawer reads, so the
   * window and the panels project the same records. The window subscribes for re-render; it does
   * NOT own/destroy the shared controller.
   */
  private ensureAgentCtrl(): AgentSessionController {
    const ctrl = getAgentSessionController(this.apiBase, this.host_);
    ctrl.conversationId = this.sessionId ?? null;
    if (!this.agentSessionUnsub) {
      this.agentSessionUnsub = subscribeAgentSession(() => this.requestUpdate());
    }
    this.agentCtrl = ctrl;
    // Tempdoc 834 §15.3 — one-shot cross-tab reattach: the FIRST time this tab mounts the agent
    // surface, ask the backend's live-run enumeration (`GET /api/chat/runs/live`) whether a run this
    // tab may adopt is executing — including one started in ANOTHER tab, or by no tab at all — and
    // attach to it. Guarded both here (one-shot) and inside reattachActiveRunOnLoad (skips if this
    // tab already owns/observes a run), and scheduled off the render via microtask.
    if (!this.reattachChecked) {
      this.reattachChecked = true;
      queueMicrotask(() => void ctrl.reattachActiveRunOnLoad());
    }
    return ctrl;
  }

  /**
   * Tempdoc 565 §3.C follow-up — resolve the agent answer's per-sentence citations (`AgentSentenceCite`)
   * against its grounding sources (`AgentSource`) into the markdown block's `MarkdownCitation[]`. The
   * `[n]` label is the source's 1-based position so it cross-references the Sources pane; the deep-link
   * detail reuses the same `citation-select` contract the Sources pane and RAG path use.
   */
  private agentAnswerCitations(): Citation[] {
    return this.resolveAnswerCitations(
      this.agentCtrl?.answerSources ?? [],
      this.agentCtrl?.answerCitations ?? [],
      this.agentCtrl?.answerCitationScorer ?? null,
    );
  }

  /** Resolve the AGENT path's per-sentence cites against its sources into the one `Citation` shape.
   *  Tempdoc 577 Phase 1 — body extracted to the shared {@link resolveAgentAnswerCitations} so the
   *  Inspector's Answer tab resolves through the same authority (no fork of the mapping).
   *  Tempdoc 859 §4 / amendment 6 — the producer stamp is threaded through EVERY call site: a site
   *  that omitted it would be admitted by the pre-stamp allowance in perpetuity, i.e. permanently
   *  ungated, which is the whole reason the stamp rides the controller. */
  private resolveAnswerCitations(
    sources: readonly AgentSource[],
    cites: readonly AgentSentenceCite[],
    scorer: string | null,
  ): Citation[] {
    return resolveAgentAnswerCitations(sources, cites, scorer);
  }

  /**
   * Tempdoc 565 §15.B — resolve the RAG path's grounded `Claim`s against its `RetrievalCitation`
   * sources into the SAME `Citation` shape the agent path uses, so both render through the one
   * `MarkdownBlock` weave. Mirrors the (now-retired) `cite-ref-click` source-index lookup, so RAG marks
   * gain the deep-link + cross-surface selection they previously lacked. Ungrounded sentences
   * (`verifiedRefs` empty) get no mark — they render as neutral prose (the medium-appropriate take on the
   * flat-text dimming). The matcher already filtered to grounded sentences via the §15.A cutoff.
   */
  private resolveClaimCitations(
    claims: readonly Claim[],
    sources: readonly RetrievalCitation[],
  ): Citation[] {
    return claimsToCitations(claims, sources);
  }

  /**
   * Tempdoc 561 P-A/P-B (Slice 3): the secondary "Activity" rail — the agent chrome (budget readout)
   * demoted to a collapsible rail so the conversation stays primary. Hidden until a run reports
   * budget. Timeline / History / Sessions remain reachable in the agent affordance's <jf-agent-view>.
   */
  private renderActivityRail(): TemplateResult {
    // Tempdoc 561 P-B (body-unification): budget comes from the inline-hosted controller now.
    // Tempdoc 577 Move 1 — the controller is a shared singleton; its budget belongs to the run it
    // last served. Project it only when that run is THIS conversation's (accountability dies with
    // its run — the stale-budget defect's second leak path).
    const ctrlBudgetIsOurs =
      this.agentCtrl != null && this.agentCtrl.conversationId === this.sessionId;
    const latestBudget =
      ctrlBudgetIsOurs && this.agentCtrl!.budgetUpdates.length > 0
        ? this.agentCtrl!.budgetUpdates[this.agentCtrl!.budgetUpdates.length - 1]!
        : this.agentBudget;
    const budget = projectBudget(latestBudget);
    // Tempdoc 577 §2.14 Root II (#14) — the COGNITIVE-headroom sibling of the economic budget: how
    // full the model's context window is (occupancy ÷ n_ctx), so "ran out of memory" reads distinct
    // from "ran out of money". Only the `llm_response` phase carries occupancy; `iteration_start`
    // events carry promptTokens 0, so reading the ABSOLUTE-latest event would null the horizon
    // between iterations and make the meter flicker. Project from the last budget update that
    // actually carries occupancy, so the meter persists the last known fullness across iterations.
    const latestOccupancy =
      ctrlBudgetIsOurs && this.agentCtrl!.budgetUpdates.length > 0
        ? [...this.agentCtrl!.budgetUpdates]
            .reverse()
            .find((b) => (b.promptTokens ?? 0) > 0 && (b.contextWindow ?? 0) > 0)
        : undefined;
    const horizon = projectContextHorizon(latestOccupancy ?? latestBudget);
    // Tempdoc 561 P-A/P-A2: the latest agent run's typed loop object (state + Turn/Iteration counts).
    const lc =
      this.unifiedLifecycles.length > 0
        ? this.unifiedLifecycles[this.unifiedLifecycles.length - 1]
        : null;
    // Tempdoc 561 C-2: in agent mode the rail (action-plane chrome) is always present, naming the
    // approval posture in its summary — even before a run reports budget. Outside agent mode it stays
    // hidden until a run produces budget/lifecycle (the prior behaviour).
    const agentMode = this.affordance === 'agent';
    if (!agentMode && !budget && !lc) return html`${nothing}`;
    const approvalPosture = postureChrome(
      agencyPosture(this.affordance, getAutonomyLevel()),
    ).approvalPosture;
    // Round-14 finding 12(b) — a run that reported DONE is a FACT, not an alarm. The measured case:
    // state DONE with a real answer over 57 sources, while the band rendered "Over budget" twice in
    // alarm styling. The code already knows this asymmetry — the over-budget remedies below render
    // only while `runInFlight` — so the presentation follows the same line: the collapsed summary
    // drops the chip entirely, and the body row states the fact in neutral text.
    const runCompleted = lc?.state === 'DONE';
    // Tempdoc 577 Ext III — the live run's accountability record, not "Activity" (that name belongs
    // to the retrospective; two records, two names). The summary separates live STATUS from posture
    // POLICY grammatically ("Policy: …"); the budget states its unit (tokens) and ceiling; an
    // over-budget state escalates WITH its remedies (halt / raise) through the one control seam.
    // Round-14 finding 12(a) — the band DEFAULTS TO COLLAPSED. `<details>` without a bound `open`
    // inherits whatever DOM state it was last left in, so "collapsed by default" was an accident of
    // first paint rather than a property anything could assert. Bind it to view state (and record the
    // user's own toggle) so the default is explicit, testable, and survives a re-render.
    return html`
      <details
        class="activity-rail"
        data-testid="activity-rail"
        ?open=${this.activityRailExpanded}
        @toggle=${(e: Event) => {
          this.activityRailExpanded = (e.target as HTMLDetailsElement).open;
        }}
      >
        <summary>
          This run${agentMode && approvalPosture
            ? html` · <span class="posture-policy">Policy: ${approvalPosture}</span>`
            : nothing}${this.agentCtrl?.budgetGate
            ? html` · <span class="over-budget"
                >${/* Tempdoc 738 (C8) — plain in Simple, technical in Detailed. */ ''}${isAdvancedMode()
                  ? 'Paused — awaiting budget'
                  : 'Paused — waiting to continue'}</span
              >`
            : budget?.overBudget && !runCompleted
              ? html` · <span class="over-budget"
                  >${isAdvancedMode()
                    ? html`Over budget +${budget.overBy} tokens`
                    : 'Paused — needs more room'}</span
                >`
              : nothing}
        </summary>
        ${/* Tempdoc 577 Move 2 — the HELD budget gate: the run is genuinely parked and waiting, so
              this row IS the decision point (continue +N / finalize / stop), all through the one
              control seam. This is the state in which the remedies are real, not decorative. */ ''}
        ${this.agentCtrl?.budgetGate
          ? html`<div class="activity-budget budget-gate-row">
              <span class="over-budget"
                >Paused: needs ~${this.agentCtrl.budgetGate.tokensNeeded} tokens, ${Math.max(
                  0,
                  this.agentCtrl.budgetGate.tokensRemaining,
                )} left</span
              >
              <span class="budget-actions">
                <button class="budget-action" @click=${() => this.onRaiseBudget()}>
                  Add ${RAISE_BUDGET_STEP_TOKENS} tokens
                </button>
                <button class="budget-action" @click=${() => this.onBudgetDecision('finalize')}>
                  Finish with what it has
                </button>
                <button class="budget-action" @click=${() => this.onBudgetDecision('stop')}>
                  Stop
                </button>
              </span>
            </div>`
          : nothing}
        ${/* Tempdoc 577 §2.14 Root II — the HELD context-pressure gate: the prompt is approaching
              the model's memory (n_ctx). The decision offers COMPACTION (the option the budget gate
              lacks): continue anyway / compact older turns / stop, through the one control seam. */ ''}
        ${this.agentCtrl?.contextGate
          ? html`<div class="activity-budget context-gate-row" data-testid="context-gate">
              <span class="over-budget"
                >Context filling up: ${this.agentCtrl.contextGate.promptTokens} of
                ${this.agentCtrl.contextGate.contextWindow} tokens</span
              >
              <span class="budget-actions">
                <button class="budget-action" @click=${() => this.onContextDecision('summarize')}>
                  Compact older turns
                </button>
                <button class="budget-action" @click=${() => this.onContextDecision('continue')}>
                  Continue anyway
                </button>
                <button class="budget-action" @click=${() => this.onContextDecision('stop')}>
                  Stop
                </button>
              </span>
            </div>`
          : nothing}
        ${lc
          ? html`<div class="activity-lifecycle">
              ${lc.turns} turn${lc.turns === 1 ? '' : 's'} · ${lc.iterations}
              iteration${lc.iterations === 1 ? '' : 's'} · ${lc.toolCalls}
              tool${lc.toolCalls === 1 ? '' : 's'} ·
              ${(lc.actors?.length ?? 1) > 1
                ? html`${lc.actors.length} agents ·`
                : nothing}
              <span class="lifecycle-state">${lc.state}</span>
            </div>`
          : nothing}
        ${budget
          ? html`<div class="activity-budget">
              ${budget.overBudget
                ? html`<span
                      class=${runCompleted ? 'budget-settled' : 'over-budget'}
                      data-testid="activity-over-budget"
                      >Over budget by ${budget.overBy} tokens (granted ${budget.ceiling})</span
                    >
                    ${/* Tempdoc 577 Ext III — control chrome attaches to the LIVE run: the backend
                          evicts a finished session, so the remedies render only while the stream is
                          in flight (a finished over-budget run is a fact, not an actionable state —
                          live-validation finding, the 404 case). */ ''}
                    ${this.agentCtrl?.runInFlight
                      ? html`<span class="budget-actions">
                          <button
                            class="budget-action"
                            @click=${() => this.onRaiseBudget()}
                          >
                            Add ${RAISE_BUDGET_STEP_TOKENS} tokens
                          </button>
                          <button class="budget-action" @click=${() => this.onHaltRun()}>
                            Stop run
                          </button>
                        </span>`
                      : nothing}`
                : html`<span
                    >Tokens: ${budget.consumed} of ${budget.ceiling} used ·
                    ${budget.remaining} left</span
                  >`}
              <div class="budget-bar">
                <div class="budget-bar-fill" style=${`width:${budget.pct}%`}></div>
              </div>
            </div>`
          : nothing}
        ${/* Tempdoc 577 §2.14 Root II (#14) — the context-headroom meter: the COGNITIVE sibling of
              the economic budget above. Distinguishes running out of money (budget) from running out
              of memory (context window). Omitted when the model's n_ctx isn't on the wire. */ ''}
        ${horizon
          ? html`<div class="activity-budget activity-context">
              <span
                >Context: ${horizon.occupancy} of ${horizon.window} tokens (${horizon.pct}%)</span
              >
              <div class="budget-bar" role="meter" aria-label="Context window used"
                aria-valuenow=${horizon.pct} aria-valuemin="0" aria-valuemax="100">
                <div
                  class="budget-bar-fill context-fill-${horizon.color}"
                  style=${`width:${horizon.pct}%`}
                ></div>
              </div>
            </div>`
          : nothing}
      </details>
    `;
  }

  /** Tempdoc 577 Ext III — the raise-budget remedy, dispatched through the one control seam. */
  private onRaiseBudget(): void {
    const ctrl = this.agentCtrl;
    if (!ctrl) return;
    void dispatchRunControl(ctrl, {
      kind: 'raise-budget',
      addTokens: RAISE_BUDGET_STEP_TOKENS,
    });
  }

  /** Tempdoc 577 Ext III — the halt remedy on the over-budget row (the existing seam directive). */
  private onHaltRun(): void {
    const ctrl = this.agentCtrl;
    if (!ctrl) return;
    void dispatchRunControl(ctrl, { kind: 'halt' });
  }

  /** Tempdoc 577 Move 2 — resolve the held budget gate (finalize | stop), through the one seam. */
  private onBudgetDecision(decision: 'finalize' | 'stop'): void {
    const ctrl = this.agentCtrl;
    if (!ctrl) return;
    void dispatchRunControl(ctrl, { kind: 'budget-decision', decision });
  }

  /** Tempdoc 577 §2.14 Root II — resolve the held context gate (continue | summarize | stop). */
  private onContextDecision(decision: 'continue' | 'summarize' | 'stop'): void {
    const ctrl = this.agentCtrl;
    if (!ctrl) return;
    void dispatchRunControl(ctrl, { kind: 'context-decision', decision });
  }

  /**
   * Tempdoc 565 §12 Phase 2 — the SINGLE ordered run projection + render. The record
   * ({@link projectUnifiedThread}) and the live agent run ({@link projectLiveAgentActivity}) project
   * into the SAME {@link UnifiedTurnItem} contract, merge into one timestamp-ordered timeline, and
   * render through the ONE {@link renderUnifiedItem} — retiring the old fork (a record renderer +
   * a parallel `renderLiveAgentActivity` with its own `renderAgentEntry`). The live items are deduped
   * against the record (tool by callId, message by `kind+content`); the record WINS because it is
   * terminal-only (§12.10), so the dedup is permanent and the two halves can never double-render.
   * The in-flight streaming answer is the timeline's tail (not a discrete record event) and is pinned
   * last. In non-agent (chat/RAG) mode there is no live agent run, so this renders the record alone.
   *
   * <p>Tempdoc 565 §12.3.D — extracted as the ONE shared timeline source so the centre conversation AND
   * the left run-spine project from the same merge (they can never diverge).
   */
  /**
   * Tempdoc 621 Phase 4 — the live/record reconciliation, moved OUT of render and INTO the merge
   * authority. Previously `renderUnifiedItem` reached into `this.thread` AT RENDER TIME to "prefer the
   * fresher live message" — the 610 §F.3 cross-source render-time reconciliation, the proven divergence
   * mechanism. Now {@link mergedTimeline} attaches the matched live {@link ThreadMessage} to the record
   * item ONCE (`attributes.live`); the renderer reads it and never re-derives. The match rules are
   * byte-identical to the former render-time logic: a USER turn matches by stable id; an ASSISTANT turn
   * matches by content AND only when the live message carries fresher evidence (sources/claims) — on
   * reload the rebuilt live thread has none, so the record renders (the reload-durability case).
   */
  private attachLiveMatch(it: UnifiedTurnItem): UnifiedTurnItem {
    if (it.kind === 'user') {
      const live = this.thread.find((m) => m.role === 'user' && m.id === it.id);
      return live ? { ...it, attributes: { ...it.attributes, live } } : it;
    }
    if (it.kind === 'assistant') {
      const live = this.thread.find((m) => m.role === 'assistant' && m.content === it.content);
      const hasEvidence =
        !!live && (((live.sources?.length ?? 0) > 0) || ((live.claims?.length ?? 0) > 0));
      return live && hasEvidence ? { ...it, attributes: { ...it.attributes, live } } : it;
    }
    return it;
  }

  private mergedTimeline(): UnifiedTurnItem[] {
    // The reconciliation is computed here (the one merge authority), not at render time (621 Phase 4).
    const recordItems = projectUnifiedThread(this.unifiedEvents).map((it) => this.attachLiveMatch(it));
    const ctrl = this.agentCtrl;
    // Tempdoc 859 §3c / amendment 7 — the run-id guard belongs HERE, at the read site, not inside
    // `projectLiveAgentActivity` (which is pure and receives the grounding as a parameter). Evidence
    // is written only by `onDone`, so a run that terminated without one — error, abort, watchdog,
    // budget stop — leaves the previous run's sources standing; handing them to this projection
    // would attach run N-1's grounding to run N's failed activity.
    const evidenceIsThisRun =
      ctrl !== null &&
      ctrl.answerEvidenceRunId !== null &&
      ctrl.answerEvidenceRunId === ctrl.sessionId;
    const liveItems =
      this.affordance === 'agent' && ctrl
        ? projectLiveAgentActivity(ctrl.conversation, ctrl.toolCalls, {
            sources: evidenceIsThisRun ? ctrl.answerSources : [],
            citations: evidenceIsThisRun ? ctrl.answerCitations : [],
          })
        : [];

    // Dedup the live overlay against the record (record wins — it is terminal-only, §12.10). Messages
    // dedup by (kind, content) because live/record ids are different spaces — but OCCURRENCE-AWARE (fix
    // D): a live message is deduped only against an UNUSED record occurrence, so two identical
    // consecutive turns ("ok" / "ok") no longer collapse to one.
    const recordedCallIds = new Set<string>();
    const recordedContentCount = new Map<string, number>();
    for (const it of recordItems) {
      if (it.kind === 'tool-activity') {
        const cid = typeof it.attributes.callId === 'string' ? it.attributes.callId : it.id;
        recordedCallIds.add(cid);
      } else if (it.kind === 'user' || it.kind === 'assistant') {
        const k = `${it.kind} ${it.content}`;
        recordedContentCount.set(k, (recordedContentCount.get(k) ?? 0) + 1);
      }
    }
    const usedContent = new Map<string, number>();
    const liveOnly = liveItems.filter((it) => {
      if (it.kind === 'tool-activity') {
        const cid = typeof it.attributes.callId === 'string' ? it.attributes.callId : it.id;
        return !recordedCallIds.has(cid);
      }
      if (it.kind === 'user' || it.kind === 'assistant') {
        const k = `${it.kind} ${it.content}`;
        const used = usedContent.get(k) ?? 0;
        if (used < (recordedContentCount.get(k) ?? 0)) {
          usedContent.set(k, used + 1); // consume one record occurrence → this live turn is already recorded
          return false;
        }
        return true; // no unused record occurrence remains → keep (e.g. a 2nd identical turn)
      }
      return true; // progress / handoff / error are ephemeral — never in the record
    });

    const ordered = [...recordItems, ...liveOnly].sort(
      (a, b) => a.ts - b.ts || a.id.localeCompare(b.id),
    );

    // Tempdoc 577 §2.14 Root I (#19) — the run/session boundary seam: when a thread is RESTORED
    // (the record carries prior turns) and a new run continues live, the seam marks where the
    // persisted history ends and this session's live run begins, so a resumed thread does not read
    // as one continuous exchange (it also surfaces §2.11 #3's evidence-loss boundary). Computed here
    // (the one merge authority knows record-vs-live origin); the renderer reads it, never re-derives.
    // No seam when the timeline is all-record (nothing live yet) or all-live (a fresh run).
    // A new run ALWAYS begins with a USER turn, so the seam is the first live USER item that
    // follows record content. Anchoring on user-kind (not just any first live item) avoids the
    // mid-turn false positive: when a live answer fails to dedup against the reconciled record
    // answer, the user turn is a record item and the answer is the first live item — keying on the
    // answer would draw "resumed · new run" BETWEEN a question and its own answer. A non-user first
    // live item is in-turn reconciliation drift, never a run boundary. (Residual: a new user turn
    // already reconciled into the record shows no seam — a missing seam beats a false one; a fuller
    // structural fix via an explicit per-item run id is deferred.)
    const liveIds = new Set(liveOnly.map((it) => it.id));
    let seamId: string | null = null;
    let sawRecord = false;
    for (const it of ordered) {
      if (liveIds.has(it.id)) {
        if (sawRecord && it.kind === 'user') seamId = it.id;
        break;
      }
      sawRecord = true;
    }
    this.resumeSeamId = seamId;
    return ordered;
  }

  /** Tempdoc 565 §12 — render the merged timeline (centre conversation) + the in-flight streaming tail. */
  /**
   * Tempdoc 565 §26.C — fetch the workflow catalog the picker projects (replacing the hardcoded
   * `WORKFLOW_ID`). Defaults the selection to the first entry so the Run button is immediately usable.
   */
  private async loadWorkflows(): Promise<void> {
    const entries = await fetchWorkflowCatalog(this.apiBase);
    this.workflows = entries;
    if (this.selectedWorkflowId === null && entries.length > 0) {
      this.selectedWorkflowId = entries[0]!.id;
    }
    this.requestUpdate();
  }

  /** Tempdoc 565 §26.C — the human label for a workflow, via the ONE display projector (present()). */
  private workflowLabel(w: WorkflowCatalogEntry): string {
    return presentLabel({ kind: 'workflow', id: w.id, labelKey: w.presentation.labelKey });
  }

  /**
   * Tempdoc 565 §26.C — the workflow PICKER + RUN affordance (replaces §15.C's single-id trigger). Lists
   * the fetched catalog; selecting one and clicking Run streams it through the unified controller into the
   * one window's run authority (a >1-node workflow then renders as labelled node segments, §26.A/§26.B).
   */
  private renderWorkflowTrigger(): TemplateResult {
    const selected = this.selectedWorkflowId;
    const chosen = this.workflows.find((w) => w.id === selected) ?? this.workflows[0];
    return html`<div class="workflow-trigger">
      <label class="workflow-picker-label" for="workflow-picker">Workflow</label>
      <select
        id="workflow-picker"
        class="workflow-picker"
        aria-label="Choose a workflow to run"
        @change=${(e: Event) => {
          this.selectedWorkflowId = (e.target as HTMLSelectElement).value;
          this.requestUpdate();
        }}
      >
        ${this.workflows.length === 0
          ? html`<option disabled selected>No workflows available</option>`
          : this.workflows.map(
              (w) => html`<option value=${w.id} ?selected=${w.id === (chosen?.id ?? '')}>
                ${this.workflowLabel(w)} · ${w.nodes.length} node${w.nodes.length === 1 ? '' : 's'}
              </option>`,
            )}
      </select>
      <button
        class="new-chat-btn"
        ?disabled=${!chosen}
        @click=${() => {
          const id = this.selectedWorkflowId ?? chosen?.id;
          if (!id) return;
          this.workflowPending = false;
          void this.ensureAgentCtrl().runWorkflow(id);
        }}
      >
        Run workflow
      </button>
    </div>`;
  }

  /**
   * Tempdoc 577 Goal 3 (§3.2) — the retrieve base tier. The ONE window's lowest intent tier:
   * pure search (the ephemeral hit-list) reading the FE `searchState` store directly — NOT an LLM
   * conversation shape (§3.3). The hit-list owns no thread history; escalation to Documents (Ask,
   * grounded) or Agent (Delegate, run) via the affordance bar is what promotes intent to a turn.
   * Rendered in the conversation-zone in place of the chat thread while `affordance === 'retrieve'`.
   */
  private renderRetrieveTier(): TemplateResult | typeof nothing {
    const s = this.searchSnapshot;
    const q = (s?.query ?? '').trim();
    if (!q) {
      // Search Thread D2/D3 (tempdoc decision 8, stage S2) — the old static empty prompt is retired;
      // the bare landing now renders the centered `.landing` search bar instead (see isLanding() /
      // renderLanding(), inserted by renderAnswerPlane immediately after this call).
      return nothing;
    }
    if (s?.error) {
      return html`<div class="retrieve-tier">
        <div class="error" data-testid="retrieve-error">${s.error}</div>
      </div>`;
    }
    // Search Thread S1 — the retrieve tier renders the ONE results card (`jf-results-card`),
    // the same component the standalone Search surface mounts: meta line (funnel count via the
    // shared matchCountLabel + latency + retrieval mode + quick/refining/refined✓), facet chips,
    // copy actions, Ask AI, and the multi-select row list. The bespoke retrieve-row markup this
    // replaces was the last presentational fork between the two surfaces.
    return html`<div class="retrieve-tier" data-testid="retrieve-tier">
      ${/* Search Thread S3 live fix — the card's meta line is the ONE empty-state
            message ("No matches for …"); the view no longer duplicates it. */ ''}
      ${this.renderCommittedSearches()}
      ${this.retrieveSelectedIds.size > 1
        ? html`<button
            type="button"
            class="scope-selection-btn"
            data-testid="scope-selection-btn"
            @click=${() => this.handleScopeSelectionClick()}
          >
            Ask about these ${this.retrieveSelectedIds.size} results
          </button>`
        : nothing}
      <jf-results-card
        .snapshot=${s}
        .facetSelections=${this.facetSelections}
        .selectedIds=${this.retrieveSelectedIds}
        .askAvailability=${projectAvailability('documents', this.aiState)}
        @card-open=${(e: CustomEvent<{ id: string }>) => this.handleRetrieveCardOpen(e.detail.id)}
        @card-selection=${(e: CustomEvent<CardSelectionDetail>) => this.handleRetrieveCardSelection(e.detail)}
        @card-facet-toggle=${(e: CustomEvent<{ field: string; value: string }>) =>
          this.handleRetrieveFacetToggle(e.detail.field, e.detail.value)}
        @card-ask-ai=${(e: CustomEvent<{ query: string; shiftKey: boolean }>) =>
          this.handleRetrieveAskAi(e.detail.shiftKey)}
        @card-scope-file=${(e: CustomEvent<{ id: string; path: string; title: string }>) =>
          this.handleCardScopeFile(e.detail)}
      ></jf-results-card>
    </div>`;
  }

  /**
   * Search Thread D5 (stage S3) — "Ask about this file" (the card's context-menu affordance) pins a
   * `file` scope chip. `docIds` carries the hit's PATH, not its `id` — the finding from item 5:
   * `handleRetrieveCardSelection`'s existing result-set publish already sends `{ id: h.path }` down
   * the rag-ask docIds path (below, §2964), and the backend's `filters.docIds` term-filter matches
   * against `SchemaFields.PATH` (QueryFilterBuilder.java:187/260) — so a chip's docIds must be paths
   * to be consistent with BOTH consumers, never the SearchHit `id`.
   */
  private handleCardScopeFile(detail: { id: string; path: string; title: string }): void {
    addScopeChip({ kind: 'file', label: filenameOf(detail.path), docIds: [detail.path] });
    if ((this.searchSnapshot?.query ?? '').trim()) submitSearch();
    // Route the user to ask-readiness — the natural next intent after scoping to a file is asking
    // about it — unless Ask is pinned to search (Hard Invariant: never a silent no-op).
    if (!this.askPinned()) this.routeOverride = 'ask';
  }

  /**
   * Search Thread D5 (stage S3) — the quiet "Ask about these N results" affordance shown above the
   * card when >1 rows are selected. Does NOT auto-add on selection (526 §17 T1B publishes the
   * `result-set` SelectionItem for the existing ask path already); this is a SEPARATE, explicit pin
   * of the same selection as a scope chip. `docIds` carries paths (same reconciliation as
   * {@link handleCardScopeFile}).
   */
  private handleScopeSelectionClick(): void {
    const hits = this.searchSnapshot?.results ?? [];
    const paths: string[] = [];
    for (const h of hits) {
      if (this.retrieveSelectedIds.has(h.id)) paths.push(h.path);
    }
    if (paths.length === 0) return;
    addScopeChip({ kind: 'result-set', label: `${paths.length} results`, docIds: paths });
    if (!this.askPinned()) this.routeOverride = 'ask';
  }

  /**
   * Search Thread S1 — card open → the shared host inspector seam (same path SearchSurface uses).
   * Search Thread S4-final — opening a hit on the LIVE card is a consequence: it FREEZES the active
   * search into a committed snapshot before navigating (commit-on-consequence, reason 'open').
   * Search Thread S6 — opening a hit for reading also auto-pins its `file` scope chip (dedup is
   * `addScopeChip`'s own job — same kind + docId set is a no-op), so a follow-up Ask is scoped to the
   * document the user is now reading without a separate "Ask about this file" click.
   */
  private handleRetrieveCardOpen(hitId: string): void {
    const hit = (this.searchSnapshot?.results ?? []).find((h) => h.id === hitId);
    if (hit) {
      this.commitLiveSearch('open');
      this.openRetrieveHit(hit);
      addScopeChip({ kind: 'file', label: filenameOf(hit.path), docIds: [hit.path] });
    }
  }

  /**
   * Search Thread S4-final — a `card-open` from an already-committed snapshot/excerpt card
   * navigates to the document but commits nothing new: the snapshot already froze that search;
   * only the LIVE card's own open ({@link handleRetrieveCardOpen}) creates a fresh commit
   * (commit-on-consequence is about the ACTIVE query, not re-interacting with history). Deliberately
   * does not reuse `openRetrieveHit` verbatim — that helper also calls `recordOpenDisposition`,
   * which would misattribute a historical open to whatever interactionId the CURRENT live search
   * happens to hold.
   */
  private handleCommittedCardOpen(hitId: string): void {
    const host = this.host_;
    if (!host?.search || !host?.ui) return;
    for (const cs of this.committedSearches) {
      const hit = cs.hits.find((h) => h.id === hitId);
      if (hit) {
        host.ui.showInspector(
          host.search.hitToSelectedItem(hit as unknown as import('../plugin-api/plugin-types.js').SearchHitSnapshot),
        );
        // Search Thread S6 — same auto-pin as the live card's own open (handleRetrieveCardOpen):
        // reading a historical snapshot's hit scopes a follow-up Ask to it too.
        addScopeChip({ kind: 'file', label: filenameOf(hit.path), docIds: [hit.path] });
        return;
      }
    }
  }

  /**
   * Search Thread S4-final — "Search again" (a snapshot/excerpt card's provenance-header fork
   * affordance, `card-fork`): re-issue the frozen query as a NEW live search. Append-only — the
   * commit the affordance was clicked from is never mutated, only a fresh search starts.
   */
  private handleCardFork(query: string): void {
    // S5a — forking back to a live search UNPINS the tier (derived → 'retrieve'); with the B14
    // auto-upgrade deleted, nothing snaps it back when aiState next emits.
    this.explicitAffordance = null;
    this.routeOverride = null;
    this.inputDraft = query;
    setSearchQuery(query);
    this.onFocusComposer();
  }

  /**
   * Search Thread S4-final — remember `query` on the recent-query trail: newest-first, deduped
   * (an existing entry moves to the front rather than duplicating), capped at 8.
   */
  private rememberQueryInTrail(query: string): void {
    const q = query.trim();
    if (!q) return;
    this.queryTrail = [q, ...this.queryTrail.filter((existing) => existing !== q)].slice(0, 8);
  }

  /** Search Thread S4-final — the query-trail dropdown's entry click: restore the draft + re-issue. */
  private pickTrailQuery(query: string): void {
    this.queryTrailOpen = false;
    this.inputDraft = query;
    setSearchQuery(query);
    this.onFocusComposer();
  }

  /**
   * Search Thread S4-final — commit-on-consequence: the user OPENED a hit, ASKED (escalated), or
   * PINNED the active retrieve-tier search. Captures the live search into an append-only
   * {@link CommittedSearch} snapshot (rendered above the live card, oldest first —
   * {@link renderCommittedSearches}) and persists it once `this.sessionId` exists. A no-op when
   * there is no active query or it carries no results (nothing to freeze). The live search is left
   * running — the commit is a frozen COPY, never a replacement (the live card keeps updating).
   *
   * `reason` is accepted (not read) — it documents WHICH consequence triggered the commit at each
   * call site (handleRetrieveCardOpen / escalateAsk); there is no per-reason branching today.
   */
  private commitLiveSearch(reason: 'open' | 'ask' | 'pin'): void {
    void reason;
    const s = this.searchSnapshot;
    const query = (s?.query ?? '').trim();
    if (!s || !query || s.results.length === 0) return;
    // Tempdoc 805 §G.2/U5 — the frozen card's retrieval-mode identity may only come from the REFINED
    // pass of this query. The quick pass genuinely runs `mode: 'text'` (searchState.buildSearchIntent
    // pins it), so a commit landing inside the quick window used to freeze "Keyword" as the search's
    // identity — round 11 saw exactly that on a hybrid search — and the removed `?? 'TEXT'` default
    // asserted the same thing from a MISSING trace. Unknown now renders as nothing (ResultsCard's
    // mode labels return null), never as a positive claim.
    const trace = s.searchTrace as SearchTrace | null | undefined;
    const mode = s.passStage === 'refined' && trace?.effectiveMode ? trace.effectiveMode : 'UNKNOWN';
    const committed: CommittedSearch = {
      id: makeCommittedSearchId(),
      query,
      mode,
      matchCount: s.matchCount,
      resultCount: s.results.length,
      docIds: s.results.map((h) => h.path),
      executedAt: new Date().toISOString(),
      hits: s.results.slice(0, 20),
    };
    this.committedSearches = [...this.committedSearches, committed];
    this.rememberQueryInTrail(query);
    // S5b pin-parity — a committed search is a real run: feed the pin's run history (no-op
    // unless this query is pinned; pinnedSearchState gates re-records within MIN_RUN_GAP_MS).
    recordRun(query, s.totalHits);
    // Search Thread S4-final — `this.sessionId` (constructor: `createConversationId()`) is a
    // client-generated id present from the moment this view mounts, and the backend's write path
    // (`AgentRunStore.appendSearchEvent`) keys/creates the event's home purely off this string with
    // NO precondition that a "conversation" already exists server-side (verified against
    // unifiedThreadClient.ts's GET path + InteractionThreadController/AgentRunStore.java) — so there
    // is no FE-observable "conversationId absent" state to special-case here: the id is always
    // present, and the POST always fires immediately. The guard stays explicit (not a dead
    // assumption) so a future change making sessionId nullable degrades to FE-local-only rather than
    // posting an empty path segment.
    if (this.sessionId) void this.postSearchEvent(committed);
  }

  /** Search Thread S4-final — POST the committed search as a durable SEARCH thread event
   *  (fire-and-forget: a failure is warned, never surfaced to the user — the live/FE-local
   *  snapshot already rendered and stays correct either way). */
  private async postSearchEvent(committed: CommittedSearch): Promise<void> {
    try {
      const res = await authorizedFetch(
        `${this.apiBase || ''}/api/thread/${encodeURIComponent(this.sessionId)}/events`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            kind: 'SEARCH',
            query: committed.query,
            mode: committed.mode,
            matchCount: committed.matchCount,
            resultCount: committed.resultCount,
            docIds: committed.docIds,
            executedAt: committed.executedAt,
          }),
        },
      );
      if (!res.ok) {
        console.warn(`commitLiveSearch: failed to persist SEARCH event (HTTP ${res.status})`);
      }
    } catch (err) {
      console.warn('commitLiveSearch: failed to persist SEARCH event', err);
    }
  }

  /**
   * Search Thread S4-final — the committed searches, rendered ABOVE the live card, oldest first.
   * Auto-collapse (item 4): only the 3 most recent render as the full `variant='snapshot'`; older
   * ones collapse to the one-line `variant='excerpt'`.
   */
  private renderCommittedSearches(): TemplateResult | typeof nothing {
    if (this.committedSearches.length === 0) return nothing;
    const total = this.committedSearches.length;
    return html`${this.committedSearches.map((cs, i) => {
      const variant: 'snapshot' | 'excerpt' = total - i <= 3 ? 'snapshot' : 'excerpt';
      const snapshot: CardSnapshot = {
        query: cs.query,
        results: cs.hits,
        matchCount: cs.matchCount,
        totalHits: cs.resultCount,
        facetsTruncated: false,
        isSearching: false,
        processingTimeMs: null,
        error: null,
      };
      const provenance: SearchProvenance = {
        actor: 'user',
        query: cs.query,
        mode: cs.mode,
        matchCount: cs.matchCount,
        resultCount: cs.resultCount,
        executedAt: cs.executedAt,
      };
      return html`<jf-results-card
        variant=${variant}
        .snapshot=${snapshot}
        .provenance=${provenance}
        @card-open=${(e: CustomEvent<{ id: string }>) => this.handleCommittedCardOpen(e.detail.id)}
        @card-fork=${(e: CustomEvent<{ query: string }>) => this.handleCardFork(e.detail.query)}
      ></jf-results-card>`;
    })}`;
  }

  /**
   * Search Thread S4-final — the "⌄ recent" query-trail dropdown, rendered beside the route chip.
   * Mirrors `RecentsMenu`'s native-button + `role="menu"`/`role="menuitem"` convention (keyboard-
   * operable by construction; Escape closes from any descendant via the wrapper's bubbling keydown).
   */
  private renderQueryTrail(): TemplateResult | typeof nothing {
    if (this.queryTrail.length === 0) return nothing;
    return html`<div
      class="query-trail"
      @keydown=${(e: KeyboardEvent) => {
        if (e.key === 'Escape') this.queryTrailOpen = false;
      }}
    >
      <button
        type="button"
        class="query-trail-toggle"
        data-testid="query-trail-toggle"
        aria-haspopup="menu"
        aria-expanded=${this.queryTrailOpen ? 'true' : 'false'}
        aria-label="Recent searches"
        title="Recent searches"
        @click=${() => {
          this.queryTrailOpen = !this.queryTrailOpen;
        }}
      >⌄ recent</button>
      ${this.queryTrailOpen
        ? html`<div class="query-trail-menu" role="menu" data-testid="query-trail-menu">
            ${this.queryTrail.map(
              (q) => html`<button
                type="button"
                class="query-trail-item"
                role="menuitem"
                data-testid="query-trail-item"
                @click=${() => this.pickTrailQuery(q)}
              >${q}</button>`,
            )}
          </div>`
        : nothing}
    </div>`;
  }

  /**
   * Search Thread S1 — the retrieve tier gains the same multi-select publish the standalone
   * surface has (526 §17 T1B): >1 selected docs publish ONE `result-set` SelectionItem (the
   * substrate the S3 scope chips consume); single select keeps the per-hit publish shape.
   */
  private handleRetrieveCardSelection(detail: CardSelectionDetail): void {
    this.retrieveSelectedIds = new Set(detail.ids);
    const hits = this.searchSnapshot?.results ?? [];
    const ids = new Set(detail.ids);
    if (ids.size > 1) {
      const refs: Array<{ id: string; kind: 'doc' }> = [];
      for (const h of hits) {
        if (ids.has(h.id)) refs.push({ id: h.path, kind: 'doc' });
      }
      setInternalSelection({
        items: [
          {
            kind: 'result-set',
            items: refs,
            query: this.searchSnapshot?.query || undefined,
            capabilities: DEFAULT_CAPABILITIES_BY_KIND['result-set'],
          },
        ],
        primaryIndex: 0,
        surfaceId: 'core.unified-chat-surface',
      });
      return;
    }
    const items: SelectionItem[] = [];
    let normalizedPrimary = 0;
    for (let i = 0; i < hits.length; i++) {
      const h = hits[i]!;
      if (!ids.has(h.id)) continue;
      if (i === detail.primaryIndex) normalizedPrimary = items.length;
      items.push({
        kind: 'search-hit',
        hitId: h.id,
        title: h.title,
        path: h.path,
        capabilities: DEFAULT_CAPABILITIES_BY_KIND['search-hit'],
      });
    }
    setInternalSelection({ items, primaryIndex: normalizedPrimary, surfaceId: 'core.unified-chat-surface' });
  }

  /**
   * Search Thread S1 — "Ask AI" escalation FROM the default search tier (live-audit finding II.C
   * D1: the retrieve tier previously had no path from results to AI at all). The card's jf-control
   * is availability-gated, so offline this is reachable-reason, never a silent no-op.
   *
   * Round-2 R2 (one gesture, one meaning) — the default (unmodified) activation now SENDS
   * immediately, the exact same path Enter/Ctrl+Enter's 'ask' route takes ({@link escalateAsk}:
   * commit-on-consequence, the S5a affordance derivation, then `send()`) — the card's Ask AI and
   * the route chip's Enter used to disagree (stage-only vs send-now), an unforced inconsistency.
   * A SHIFT-modified activation (the rephrase case) keeps the PRE-round-2 stage-only behavior —
   * routes through the one compose() seam so the user can edit the prefilled prompt before sending.
   */
  private handleRetrieveAskAi(shiftKey: boolean): void {
    if (shiftKey) {
      compose({
        operation: 'core.ask',
        source: 'BUTTON',
        userPrompt: this.searchSnapshot?.query ?? '',
        affordance: 'documents',
      });
      return;
    }
    this.escalateAsk();
  }

  /** §3.9a — toggle a facet then re-run through the one searchState seam (picks up selections). */
  private handleRetrieveFacetToggle(field: string, value: string): void {
    toggleFacetValue(field, value);
    if ((this.searchSnapshot?.query ?? '').trim().length > 0) submitSearch();
  }

  /** Open a hit through the shared host inspector seam (same path SearchSurface uses). */
  private openRetrieveHit(hit: SearchHit): void {
    const host = this.host_;
    if (!host?.search || !host?.ui) return;
    // Tempdoc 580 §17 P3 — the live search surface's OPENED disposition. This is the
    // retrieve-tier twin of SearchSurface.handleClick: opening a hit here is the same
    // positive outcome signal, so it feeds the one canonical disposition stream (carrying
    // the live search's interactionId from searchState) for the §17.4 snapshot join.
    recordOpenDisposition(hit.id);
    host.ui.showInspector(
      host.search.hitToSelectedItem(hit as unknown as import('../plugin-api/plugin-types.js').SearchHitSnapshot),
    );
  }

  /**
   * Tempdoc 629 (LAYER) — the conversation store is encrypted + locked (history returned 423). Render a
   * clear notice + an Unlock affordance routing to the Settings "Chat encryption" control (the validated
   * unlock path), NOT an empty transcript (§L4: locked must never look deleted). Search/index stay usable
   * (they are not encrypted), so only the transcript pane is gated.
   */
  private renderHistoryLocked(): TemplateResult {
    // Tempdoc 629 (#3): the wording + remedy come from the ONE CAUSE_ROWS authority (reasonFor), not
    // hardcoded here — so the locked-chat gate speaks the same vocabulary as every other readiness cause.
    const r = reasonFor('conversations.locked');
    const nav = r.remedy?.kind === 'navigate' ? r.remedy : null;
    return html`
      <div class="history-locked" role="status">
        <p>${icon({ name: 'shield', size: 16 })} <strong>${r.wording}</strong>.</p>
        <p class="help">Unlock it to read your chat history — your search index is unaffected.</p>
        ${/* Tempdoc 734 round-14 F4 — present only when a send was actually refused (dispatch 423),
              so the resumed-while-locked case reads exactly as it did before. It says what became of
              the message, next to the remedy that fixes it; the draft is back in the composer. */ ''}
        ${this.lockedSendNotice
          ? html`<p class="locked-send-notice" role="alert">
              ${this.lockedSendNotice} Your text is back in the composer — unlock to send it.
            </p>`
          : nothing}
        ${nav
          ? html`<jf-button .onActivate=${() => requestSurfaceNavigation(nav.target)}>
              ${icon({ name: 'shield', size: 14 })} ${nav.label}
            </jf-button>`
          : nothing}
      </div>
    `;
  }

  /**
   * Tempdoc 585 §D Phase 1 (C1) — the run-replay scrubber. Shown only while the shared controller is
   * in replayMode; play/step/seek call the controller's replay primitives, which re-apply the
   * persisted events through the SAME per-event handlers + projection the live thread uses (the four
   * live-only side-effects are suppressed in replayMode). "Exit replay" returns to an idle controller.
   */
  private renderReplayBar(): TemplateResult {
    const ctrl = this.agentCtrl;
    if (!ctrl) return html`${nothing}`;
    const total = ctrl.replayTotal;
    const cursor = ctrl.replayCursor;
    return html`
      <div class="replay-bar" role="group" aria-label="Run replay controls">
        <span class="replay-label">Replaying past run · ${cursor}/${total}</span>
        <button
          class="replay-btn"
          aria-label="Step back one event"
          ?disabled=${cursor <= 0}
          @click=${() => ctrl.replayStepBack()}
        >
          ◀
        </button>
        <input
          class="replay-slider"
          type="range"
          min="0"
          max=${total}
          .value=${String(cursor)}
          aria-label="Replay position"
          @input=${(e: Event) => ctrl.replaySeek(Number((e.target as HTMLInputElement).value))}
        />
        <button
          class="replay-btn"
          aria-label="Step forward one event"
          ?disabled=${cursor >= total}
          @click=${() => ctrl.replayStepForward()}
        >
          ▶
        </button>
        <button
          class="replay-fork"
          title="Branch a new run from this one — edit the question and re-run"
          @click=${() => {
            this.forkEditing = !this.forkEditing;
          }}
        >
          Fork &amp; edit
        </button>
        <button class="replay-exit" @click=${() => ctrl.exitReplay()}>Exit replay</button>
      </div>
      ${this.forkEditing ? this.renderForkEditor() : nothing}
    `;
  }

  /**
   * Tempdoc 585 §D Phase 3 (C2) — the inline fork editor: edit the run's last question and branch a
   * NEW run from it. Submitting drives {@link AgentSessionController.forkRun} (which leaves replay and
   * streams the fresh run); a blank box re-rolls the original question.
   */
  private renderForkEditor(): TemplateResult {
    const ctrl = this.agentCtrl;
    const run = (): void => {
      const id = ctrl?.sessionId;
      if (!ctrl || !id) return;
      const text = this.forkDraft;
      this.forkEditing = false;
      this.forkDraft = '';
      void ctrl.forkRun(id, text);
    };
    return html`<div class="fork-editor" role="group" aria-label="Fork the run from here">
      <textarea
        class="fork-input"
        aria-label="Edited question for the forked run"
        placeholder="Edit the question and re-run (leave blank to re-roll the original)…"
        .value=${this.forkDraft}
        @input=${(e: Event) => (this.forkDraft = (e.target as HTMLTextAreaElement).value)}
      ></textarea>
      <button class="fork-run" @click=${run}>Run fork</button>
    </div>`;
  }

  private renderUnifiedConversation(): TemplateResult {
    const merged = this.mergedTimeline();
    const ctrl = this.agentCtrl;
    return html`
      ${this.workflowPending ? this.renderWorkflowTrigger() : nothing}
      ${this.renderTimeline(merged)}
      ${ctrl?.streamingText
        ? html`<div class="message assistant">
            <jf-markdown-block
              .text=${ctrl.streamingText}
              .citations=${this.agentAnswerCitations()}
            ></jf-markdown-block>
            ${this.renderGroundingBadge(
              ctrl.streamingText,
              ctrl.answerSources ?? [],
              ctrl.answerCitations,
              false,
            )}
            ${this.renderSourceChips(ctrl.answerSources ?? [], '__live__')}
          </div>`
        : nothing}
    `;
  }

  /**
   * Tempdoc 565 §14 ④/⑤ — the grounding-honesty badge: ④ a "Grounded" readiness state (shown when the
   * answer carries sources) + ⑤ the "N of M sentences" coverage — BOTH a read of the one §15.A grounding
   * verdict ({@link groundingCoverage}). Surfaced beside the answer so its grounding is explicit, not
   * buried (the §14 honesty rule). Hidden for an answer with no sources (the RAG/plain path owns its own).
   */
  private renderGroundingBadge(
    answerText: string,
    sources: readonly AgentSource[],
    rawCitations: unknown,
    // Tempdoc 720 — has the run FINISHED? A live render (settled=false) keeps the mid-stream coverage
    // readout; a settled render whose matcher tied no sentence to a chunk-precise passage must NOT claim
    // "Grounded · 0 of N" — it states provenance instead (badge + frame agree via the same settle flag).
    settled: boolean,
  ): TemplateResult | typeof nothing {
    if (sources.length === 0) return nothing;
    // Tempdoc 814 §D5 (one authority, one pointer) — the SOURCE-COUNT fact has exactly one persistent
    // render: the evidence rail's head ("Sources · N") while the rail is mounted. The two provenance
    // branches below are count lines ("Based on N documents/sources"), so they stand down for that
    // state — the honest grounding disclaimer is NOT theirs to carry: it is the answer-frame line
    // ("Based on your documents — per-sentence grounding not verified", `answerFrameLabel`), which
    // renders next to this badge in every state and is untouched. The coverage branch below
    // ("Grounded · X of Y sentences") states VERIFICATION, not a source count, and always renders.
    const countIsOwnedByTheRail = this.evidenceRailMounted();
    const citations = Array.isArray(rawCitations) ? (rawCitations as AgentSentenceCite[]) : [];
    const cov = groundingCoverage(citations, answerText);
    const chunkPrecise = sourcesAreChunkPrecise(sources);
    // Tempdoc 603 D-4 — the SOURCED (provenance) state: the answer drew on these documents but they are
    // DOCUMENT-LEVEL (no chunk identity → the per-sentence matcher could not run), so there is no
    // "N of M sentences" verdict to give. Show provenance honestly — NEVER "Grounded · 0 of N" (the
    // over-confidence) — derived from the same authority predicate the frame uses, so badge + frame agree.
    if (cov.cited === 0 && !chunkPrecise) {
      if (countIsOwnedByTheRail) return nothing;
      const n = sources.length;
      return html`<details class="grounding-badge grounding-badge-sourced">
        <summary class="grounding-badge-summary" role="status">
          <span>Based on ${n} document${n === 1 ? '' : 's'}</span>
        </summary>
        <div class="grounding-why">
          <div>
            ${n === 1 ? 'This document was' : `These ${n} documents were`} retrieved and informed the
            answer, but per-sentence grounding was not verified — keyword-only retrieval returned whole
            documents, so each statement could not be tied to a specific passage.
          </div>
        </div>
      </details>`;
    }
    // Tempdoc 720 — CHUNK-PRECISE sources but the SETTLED run tied no sentence to a passage: the matcher
    // finished and matched nothing, so this is provenance-without-verification, NOT "Grounded · 0 of N"
    // (the C1 over-confidence reproduced in the settled render path). Mid-stream (settled=false) keeps the
    // coverage readout below, since marks may still arrive.
    if (cov.cited === 0 && chunkPrecise && settled) {
      if (countIsOwnedByTheRail) return nothing;
      const n = sources.length;
      return html`<details class="grounding-badge grounding-badge-sourced">
        <summary class="grounding-badge-summary" role="status">
          <span>Based on ${n} source${n === 1 ? '' : 's'}</span>
        </summary>
        <div class="grounding-why">
          <div>
            ${n === 1 ? 'This source was' : `These ${n} sources were`} retrieved and informed the answer,
            but no statement matched a specific passage above the grounding threshold — treat the wording
            as the model's own.
          </div>
        </div>
      </details>`;
    }
    const uncited = Math.max(0, cov.total - cov.cited);
    // Tempdoc 577 §2.12 Move 4 — the answer plane's "Why uncited?" tier (§2.11 #7): a native
    // <details> disclosure (keyboard/AT-accessible by construction, no hover-only title) explaining
    // the breakdown and WHY the uncited sentences carry no mark. The search window got "Why this
    // result?"; this is its mirror for the answer.
    return html`<details class="grounding-badge">
      <summary class="grounding-badge-summary" role="status">
        <span class="grounding-dot" aria-hidden="true"></span>
        ${/* Tempdoc 836 S2S3-A.2 — the badge renders the PROJECTION's line rather than re-composing
            it here. Before this, "Grounded · N of M sentences" existed twice: once as
            `GroundingCoverage.label` and once inlined at this render site, so the honesty rules
            added to the projection would simply not have reached the surface that shows them. */ ''}
        <span>${cov.label}</span>
      </summary>
      <div class="grounding-why">
        <div>${cov.grounded} strong + ${cov.weak} supporting of ${cov.total} sentences cite a source.</div>
        ${uncited > 0
          ? html`<div>
              ${uncited} sentence${uncited === 1 ? ' is' : 's are'} not backed by a retrieved
              passage above the match threshold — treat ${uncited === 1 ? 'it' : 'them'} as the
              model's own wording.
            </div>`
          : nothing}
      </div>
    </details>`;
  }

  /**
   * Tempdoc 565 §12.3.E + §13.8 P3 — the source-chip row under a grounded answer: one compact chip per
   * grounding source ([n] · filename), an ambient-grounding surface alongside the inline [n] marks and
   * the evidence rail. §13.8 makes it a COLLAPSIBLE "Sources · N" disclosure (mirroring
   * {@link CitationsPanel}'s "N sources" toggle) because the docked rail already owns the full source
   * detail — so the chips are an on-demand echo, not a redundant always-on third copy. Collapsed by
   * default when the wide rail is showing the same sources; expanded by default at narrow (no rail);
   * an explicit click pins the per-answer choice in {@link sourceChipsToggles}. All surfaces still
   * cross-highlight through the ONE {@link selectedSource} store, and a chip click reuses the existing
   * `citation-select` deep-link (mirrors {@link SourcesPane}'s card).
   */
  private renderSourceChips(sources: readonly AgentSource[], key: string): TemplateResult {
    if (!sources || sources.length === 0) return html`${nothing}`;
    // Tempdoc 814 §D5 — the in-answer "Sources · N" disclosure exists only where the rail is NOT. With
    // the rail mounted it was the third persistent render of the source count within ~250px; the rail's
    // head is the authority, and the rail carries the same per-source cards (selection, hide/restore),
    // so this is a duplicate to delete for that state, not a capability to preserve.
    if (this.evidenceRailMounted()) return html`${nothing}`;
    // Structural default: collapsed when the wide rail shows the detail; expanded at narrow (no rail).
    const railShown = this.wideZone;
    const open = this.sourceChipsToggles.get(key) ?? !railShown;
    const selected = getSelectedSource();
    const bodyId = `source-chips-${key}`;
    return html`<div class="source-disclosure">
      <button
        class="source-disclosure-summary"
        aria-expanded=${open ? 'true' : 'false'}
        aria-controls=${bodyId}
        aria-label=${`${open ? 'Hide' : 'Show'} answer sources (${sources.length})`}
        @click=${() => {
          this.sourceChipsToggles.set(key, !open);
          this.requestUpdate();
        }}
      >
        <span class="disclosure-chevron ${open ? 'open' : ''}" aria-hidden="true">▸</span>
        <span>Sources · ${sources.length}</span>
      </button>
      ${open
        ? html`<div class="source-chips" id=${bodyId} role="group" aria-label="Answer sources">
            ${sources.map((s, i) => {
              const name = s.title || filenameOf(s.path);
              const isSel = selected === sourceKey(s.parentDocId, s.startLine);
              const isHidden = getExcludedSources().has(sourceExcludeKey(s.parentDocId, s.chunkIndex));
              return html`<span class="source-chip-wrap ${isHidden ? 'hidden-source' : ''}">
                <button
                  class="source-chip ${isSel ? 'selected' : ''}"
                  aria-current=${isSel ? 'true' : 'false'}
                  aria-label=${`Source ${i + 1}: ${name} — open at line ${s.startLine}`}
                  title="Open ${s.path} at line ${s.startLine}"
                  @click=${() => this.onChipSelect(s)}
                ><span class="source-chip-n">${i + 1}</span
                  ><span class="source-chip-name">${name}</span></button>
                <button
                  class="source-exclude"
                  aria-label=${isHidden
                    ? `Restore ${name} to the assistant's retrieval`
                    : `Hide ${name} from the assistant's retrieval`}
                  title=${isHidden ? 'Restore to retrieval' : 'Hide from retrieval'}
                  @click=${() => void this.toggleSourceExcluded(s)}
                >${isHidden ? '↺' : '×'}</button>
              </span>`;
            })}
          </div>`
        : nothing}
    </div>`;
  }

  /** Tempdoc 610 §J.3 — toggle a retrieved source's hidden state via the shared store (persist + notify). */
  private async toggleSourceExcluded(s: AgentSource): Promise<void> {
    if (!this.sessionId) return;
    const key = sourceExcludeKey(s.parentDocId, s.chunkIndex);
    await toggleExcludedSource(this.sessionId, key, !getExcludedSources().has(key));
  }

  /** Tempdoc 565 §12.3.E — focus a source across surfaces + deep-link to its local passage (chip click). */
  private onChipSelect(s: AgentSource): void {
    setSelectedSource(sourceKey(s.parentDocId, s.startLine));
    this.dispatchEvent(
      new CustomEvent<CitationSelectDetail>('citation-select', {
        detail: {
          parentDocId: s.parentDocId,
          startLine: s.startLine,
          endLine: s.endLine,
          startChar: 0,
          endChar: 0,
          excerpt: s.excerpt,
        },
        bubbles: true,
        composed: true,
      }),
    );
  }

  /**
   * Tempdoc 565 §12.3.C/F — compose the ordered timeline answer-first: a `primary` item (the user
   * turn, the answer) renders at full prominence as a direct conversation child (so `.message.user`
   * keeps its right-aligned `align-self`); each run of consecutive `secondary`/`ambient` items (the
   * tool + progress steps) collapses into ONE status-coloured trace spine that recedes to a one-line
   * summary once the answer lands. The run is audit-on-demand — dense + expandable — not a wall above
   * the answer. Multiple turns yield multiple trace segments (each a spine between its primary items);
   * each collapses independently (fix A). A segment flushed BECAUSE a primary item followed it is
   * NON-trailing (its run answered → default collapsed); the segment flushed after the loop is the
   * TRAILING (in-flight) run with no answer yet → default open. So the collapse default is derived from
   * timeline structure, not a global streaming flag.
   */
  private renderTimeline(items: readonly UnifiedTurnItem[]): TemplateResult {
    // Tempdoc 565 §26.B — partition the flat stream into consecutive RUN SEGMENTS (workflow nodes).
    // Ungrouped items (an agent run with no nodes — the degenerate one-segment case) render inline
    // EXACTLY as before; a workflow node's items render wrapped in a labelled `<section>` so the node
    // structure §15.C flattened is visible. The intra-node primary/trace composition is unchanged.
    const groups: Array<{ segment?: RunSegmentRef; items: UnifiedTurnItem[] }> = [];
    for (const it of items) {
      const key = it.segment?.nodeId;
      const last = groups[groups.length - 1];
      if (last && last.segment?.nodeId === key) last.items.push(it);
      else groups.push({ segment: it.segment, items: [it] });
    }
    return html`${groups.map((g) =>
      g.segment && g.segment.nodeId
        ? this.renderRunSegment(g.segment, g.items)
        : this.renderTimelineItems(g.items),
    )}`;
  }

  /**
   * Tempdoc 565 §26.B — one workflow node rendered as a labelled segment: a header naming the node
   * (its label + kind) over the node's items, composed through the unchanged intra-node renderer. The
   * node structure that was flattened (§25.1) is now a visible grouping, without nesting the item model.
   */
  /**
   * Tempdoc 565 §29 Tier-2 / §33 — J/K steps focus between run landmarks via the Navigation authority
   * (it owns the ordered positions + the jump path). A WINDOW-level shortcut (§33: the conversation div
   * is not focusable, so a div-scoped handler never fired for a real user). Guarded so it acts only on
   * an agent run with the spine shown, and NEVER while the user is typing (the active element is an
   * input/textarea/contenteditable — so `j`/`k` in the composer or steer input types normally). Letters
   * only — no arrow keys, so scroll/caret movement is untouched.
   */
  private onConversationKeydown(e: KeyboardEvent): void {
    if (this.affordance !== 'agent' || !this.wideZone) return;
    const dir = e.key === 'j' ? 1 : e.key === 'k' ? -1 : 0;
    if (dir === 0) return;
    // §33 — never hijack typing: descend through nested shadow roots (jf-unified-chat-view →
    // jf-composer → textarea) to the truly-focused element, and bail if it's an editable control.
    // Tempdoc 857 PR-A — the descent and the predicate are the SHARED ones now
    // (`utils/keyboardHandler.ts`), so this window and Search v3 cannot disagree about what "typing"
    // is. The shared predicate's union also covers `SELECT`, which this inline copy omitted: with the
    // workflow picker (`:3987`) focused, `j`/`k` used to steal its native type-ahead.
    if (isTypingTarget(deepActiveElement())) return;
    const landmarks = this.nav.landmarks ?? [];
    if (landmarks.length === 0) return;
    e.preventDefault();
    const cur = landmarks.findIndex((l) => l.id === this.nav.activeId);
    const next =
      cur < 0
        ? dir > 0
          ? 0
          : landmarks.length - 1
        : Math.min(landmarks.length - 1, Math.max(0, cur + dir));
    const target = landmarks[next];
    if (target) this.nav.jumpTo(target.id);
  }

  private renderRunSegment(
    segment: RunSegmentRef,
    items: readonly UnifiedTurnItem[],
  ): TemplateResult {
    const label = segment.label ?? segment.nodeId ?? 'Step';
    // §26.D — a background run is one segment with the `background` chip; a workflow node shows its kind.
    // Tempdoc 814 (finding 7, one authority + one pointer) — for a BACKGROUND-origin segment the chip
    // becomes a marked POINTER instead of an unmarked peer copy: the run's authority is its inbox item
    // (the drawer's Background-runs tab, `/api/presence`), and this control opens the drawer there.
    const backgroundOrigin = segment.originKind === 'background';
    const kindChip = backgroundOrigin ? null : segment.nodeKind;
    // Tempdoc 565 §29 Tier-2 — per-segment elapsed time from the items' authoritative timestamps
    // (`ts` already on every UnifiedTurnItem): the wall-clock the node took, shown in the header.
    const elapsedSec =
      items.length > 1
        ? Math.max(0, (items[items.length - 1]!.ts - items[0]!.ts) / 1000)
        : 0;
    const elapsedLabel = elapsedSec >= 0.05 ? `${elapsedSec.toFixed(1)}s` : '';
    return html`<section
      class="run-segment origin-${segment.originKind}"
      data-node-id=${segment.nodeId ?? ''}
    >
      <header class="run-segment-header">
        <span class="run-segment-name">${label}</span>
        ${kindChip ? html`<span class="run-segment-kind">${kindChip}</span>` : nothing}
        ${backgroundOrigin
          ? html`<button
              class="run-segment-kind run-segment-ref"
              data-testid="background-run-ref"
              title="This run is tracked in Background runs — open it there"
              @click=${() => openRetrospectiveAt('inbox')}
            >
              background run ↗
            </button>`
          : nothing}
        ${elapsedLabel
          ? html`<span class="run-segment-elapsed" title="Time this step took">${elapsedLabel}</span>`
          : nothing}
      </header>
      ${this.renderTimelineItems(items)}
    </section>`;
  }

  /** Tempdoc 565 §12.3 — the intra-segment composition (primary items lead; secondary/ambient steps
   * collapse into a run-trace). Extracted from `renderTimeline` so §26.B can wrap it per node segment. */
  private renderTimelineItems(items: readonly UnifiedTurnItem[]): TemplateResult {
    const out: TemplateResult[] = [];
    let trace: UnifiedTurnItem[] = [];
    // Search Thread S7 (tempdoc decision 6) — the agent turn's receipt duration, best-effort: the
    // nearest PRECEDING user item's timestamp (no new instrumentation — `ts` already exists on every
    // UnifiedTurnItem). Omitted (never fabricated) when no preceding user item is in view.
    let lastUserTs: number | null = null;
    const flush = (isTrailing: boolean): void => {
      if (trace.length > 0) {
        out.push(this.renderRunTrace(trace, isTrailing));
        trace = [];
      }
    };
    for (const it of items) {
      if (it.kind === 'user') lastUserTs = it.ts;
      // Tempdoc 577 §2.14 Root I (#19) — the run/session boundary seam, rendered before the first
      // live item that follows restored history so a resumed thread reads as two exchanges, not one.
      if (this.resumeSeamId !== null && it.id === this.resumeSeamId) {
        flush(false);
        out.push(
          html`<div class="run-seam" role="separator" aria-label="New run in this session">
            <span class="run-seam-label">resumed · new run</span>
          </div>`,
        );
      }
      if (it.prominence === 'primary') {
        flush(false); // a primary item follows this segment → its run answered → collapsed
        out.push(this.renderUnifiedItem(it, lastUserTs));
      } else {
        trace.push(it);
      }
    }
    flush(true); // the last segment has no primary after it → the in-flight run → open
    return html`${out}`;
  }

  /**
   * Tempdoc 565 §12.3.F — the run trace as a status-coloured vertical spine: one node per step, tinted
   * by the §3.B status tone, collapsible (§12.3.C). PER-SEGMENT collapse (fix A): default-open iff this
   * is the trailing (in-flight) run — a completed run whose answer follows is collapsed; the user's
   * explicit toggle (keyed by the segment's first-item id in `runTraceToggles`) pins the choice for THAT
   * segment only. Uses native `<details>` disclosure (the §12.10 correction — NOT the horizontal
   * `OverflowController`), fully controlled so a re-render never fights the user's toggle.
   */
  private renderRunTrace(
    trace: readonly UnifiedTurnItem[],
    isTrailing: boolean,
  ): TemplateResult {
    const segId = trace[0]!.id; // stable: record InteractionEvent id / live ConversationEntry id
    const tools = trace.filter((t) => t.kind === 'tool-activity');
    const denied = tools.filter((t) => t.attributes.status === 'rejected').length;
    // Tempdoc 577 Ext I — outcome-aware summary: a run containing a failed call may not summarize
    // as unqualified success ("2 steps · 1.3s" hiding a failure was the §2.9 V2 trust defect).
    const failed = tools.filter(
      (t) => t.attributes.status === 'completed' && t.attributes.success === false,
    ).length;
    const errors = trace.filter((t) => t.kind === 'error').length;
    const open = this.runTraceToggles.has(segId)
      ? this.runTraceToggles.get(segId)!
      : isTrailing;
    // Fix E — the step count names TOOL actions; a tool-less/error-less run falls back to its event
    // count ("3 events") rather than a misleading "0 steps".
    // Tempdoc 565 §29 Tier-2 — the collapsed completed-run summary also carries wall-clock elapsed
    // (the "completed-run summary card" intent, via the existing per-segment collapse — extend, not fork).
    const elapsedSec =
      trace.length > 1
        ? Math.max(0, (trace[trace.length - 1]!.ts - trace[0]!.ts) / 1000)
        : 0;
    const parts = [
      tools.length > 0 ? `${tools.length} step${tools.length === 1 ? '' : 's'}` : null,
      failed > 0 ? `${failed} failed` : null,
      denied > 0 ? `${denied} denied` : null,
      errors > 0 ? `${errors} error${errors === 1 ? '' : 's'}` : null,
      elapsedSec >= 0.05 ? `${elapsedSec.toFixed(1)}s` : null,
    ].filter(Boolean);
    const summary =
      parts.length > 0
        ? parts.join(' · ')
        : `${trace.length} event${trace.length === 1 ? '' : 's'}`;
    return html`
      <details class="run-trace" ?open=${open}>
        <summary
          class="run-trace-summary"
          @click=${(e: Event) => {
            e.preventDefault();
            this.runTraceToggles.set(segId, !open);
            this.requestUpdate();
          }}
        >
          <span class="run-trace-caret">${open ? '▾' : '▸'}</span>
          <span class="run-trace-label">${summary}</span>
        </summary>
        <div class="trace-spine">
          ${trace.map((it) => this.renderTraceNode(it))}
        </div>
      </details>
    `;
  }

  /**
   * Tempdoc 565 §12.3.F — one spine node (status-tinted via the §3.B `statusAccent` authority) + the
   * item body at its declared prominence (`secondary` dense, `ambient` faint — the §12.3.C facet).
   */
  private renderTraceNode(it: UnifiedTurnItem): TemplateResult {
    // Tempdoc 565 §17 — the trace node is the ONE run-node primitive composing the ONE step descriptor
    // (glyph + tone), no hand-authored status lookup (§17.G). The body carries the human label.
    const p = stepPresentation(it);
    // §19.I-R3 — anchor the trace step for the minimap + spine-jump: when the trace is EXPANDED the row
    // has a real scroll position (NavigationController.measure reads it, skipping it when collapsed → the step
    // interpolates between turn anchors as before). Also makes a spine tool/progress node click-jump to
    // its row (previously unmatched). data-item-id mirrors the spine node's id (the same mergedTimeline item).
    return html`<div class="trace-row prominence-${it.prominence}" data-item-id=${it.id}>
      <span class="trace-node"><jf-run-node density="compact" .presentation=${p}></jf-run-node></span>
      <div class="trace-body">${this.renderUnifiedItem(it)}</div>
    </div>`;
  }

  /**
   * Tempdoc 561 P-B — the live SSE overlay. The canonical {@code /api/thread} projection
   * ({@link renderUnifiedThread}) is the SINGLE read-model; this renders only the in-flight turns the
   * record has not reconciled yet (the current exchange, before {@link refreshUnifiedThread} folds it
   * into {@code unifiedEvents} on terminal). Once a turn is in the record it renders from the
   * projection and is deduped out here by (kind, content), so the two models can never double-render
   * or drift — killing the prior "render record OR live-thread" fork.
   */
  private renderLiveOverlay(): TemplateResult {
    // Tempdoc 561 P-B: dedup the in-flight overlay against the record. Prefer the STABLE id (exact,
    // robust to duplicate content); fall back to a (kind, content) key for the brief pre-id window
    // before syncMessageIds runs. Either way an event in the record never double-renders.
    const recordedIds = new Set(this.unifiedEvents.map((e) => e.id));
    const recordedContent = new Set(
      this.unifiedEvents.map((e) => `${e.kind}\u0000${e.content}`),
    );
    const kindOf = (role: string): string =>
      role === 'user' ? 'USER_MESSAGE' : 'ASSISTANT_MESSAGE';
    const pending = this.thread.filter((m) => {
      if (m.id && recordedIds.has(m.id)) return false;
      return !recordedContent.has(`${kindOf(m.role)}\u0000${m.content}`);
    });
    return html`${pending.map((m) => this.renderMessage(m, this.thread.indexOf(m)))}`;
  }

  /**
   * Tempdoc 577 §2.12 Move 3 — the current mode's conversation shape id (the inverse of the
   * shape→affordance preset). The answer-frame authority keys on this to decide a shape's declared
   * grounding class. A record-side answer belongs to the conversation's current shape.
   */
  private currentShapeId(): CoreInteractionShapeId {
    switch (this.affordance) {
      case 'documents':
        return 'core.rag-ask';
      case 'extract':
        return 'core.extract';
      case 'agent':
        return 'core.agent-run';
      default:
        return 'core.free-chat';
    }
  }

  /**
   * Tempdoc 577 §2.12 Move 3 — the epistemic frame for an answer: the shape's declared grounding
   * class refined by the actual outcome (source count + per-sentence coverage). One authority
   * ({@link answerFrame}); the render sites read it, never re-derive.
   */
  private frameFor(
    shapeId: CoreInteractionShapeId,
    sourceCount: number,
    coverageCites: ReadonlyArray<{ readonly similarity: number }>,
    answerText: string,
    // Tempdoc 603 D-4 — whether the attached sources are chunk-precise (matcher-eligible) or
    // document-level (provenance). Defaults TRUE (the RAG/chunk-native tier); the agent path passes the
    // real predicate so an all-document-level source list frames as `sourced`, not "Grounded · 0 of N".
    chunkPrecise = true,
    // Tempdoc 720 — has the run finished? A settled render can no longer treat a zero-cite chunk-precise
    // answer as "marks pending ⇒ grounded"; the streaming render sites pass false, the committed/reloaded
    // sites pass true. See {@link answerFrame}.
    settled = false,
    // Tempdoc 836 S2S3-A.2 — the run's coverage facts. They change the DENOMINATOR (the backend's
    // BreakIterator count replaces the frontend regex estimate, closing the §3.6 fork) and let the
    // coverage line distinguish an evidence verdict from a budget shortfall.
    honesty: CoverageHonesty | null = null,
  ): AnswerFrame {
    return answerFrame(
      shapeId,
      sourceCount,
      groundingCoverage(coverageCites, answerText, honesty),
      chunkPrecise,
      settled,
    );
  }

  /**
   * Search Thread S7 (tempdoc decision 6) — the quiet per-turn receipt tail: duration + model name,
   * plain tokens (never a fabricated value — each part is omitted when unavailable). Read once here
   * so `renderAnswerFrameLine`'s two call sites (and its own frame-authority) share the one format,
   * rather than each re-deriving the "Xs · model" string.
   */
  private formatReceiptTail(receipt?: { durationMs?: number; modelLabel?: string | null }): string {
    if (!receipt) return '';
    const parts: string[] = [];
    if (typeof receipt.durationMs === 'number' && receipt.durationMs >= 0) {
      parts.push(
        receipt.durationMs >= 1000
          ? `${(receipt.durationMs / 1000).toFixed(1)}s`
          : `${receipt.durationMs}ms`,
      );
    }
    // Tempdoc 738 (C7) — the model name is a technical fact; show it only in Detailed mode. The
    // duration (a plain "how long it took") stays in both.
    if (receipt.modelLabel && isAdvancedMode()) parts.push(receipt.modelLabel);
    return parts.join(' · ');
  }

  /**
   * Tempdoc 577 Move 3 — the answer's epistemic header line (`null` grounding label for
   * grounded/transform). Search Thread S7 (tempdoc decision 6) — this is now ALSO the ONE quiet
   * per-turn receipt: EXTENDED (not forked) with an optional duration+model tail, so a completed
   * ask/agent turn gets ONE line, never a second. §2.16 — `degraded` refines the `ungrounded`
   * wording: a shape that SEARCHED but found nothing to cite reads distinct from a mode that never
   * searches (computed via groundingDegraded at the call site, where shapeId × sourceCount are known).
   */
  private renderAnswerFrameLine(
    frame: AnswerFrame,
    degraded = false,
    receipt?: { durationMs?: number; modelLabel?: string | null },
    // Tempdoc 836 S2S3-A.2 — the coverage line, present only when the run said verification did
    // not fully happen. It rides the existing receipt line rather than adding a second one: the
    // fact belongs beside the frame ("what may this answer claim?"), and a separate always-on
    // banner would be a third persistent line under a two-line answer.
    coverage: string | null = null,
  ): TemplateResult | typeof nothing {
    const label = answerFrameLabel(frame, degraded);
    const receiptText = this.formatReceiptTail(receipt);
    if (label === null && !receiptText && coverage === null) return nothing;
    const parts: Array<unknown> = [];
    if (label !== null) parts.push(label);
    if (coverage !== null) parts.push(html`<span class="answer-coverage">${coverage}</span>`);
    if (receiptText) parts.push(html`<span class="answer-receipt">${receiptText}</span>`);
    return html`<div class="answer-frame answer-frame-${frame}" role="note">
      ${parts.map((p, i) => html`${i > 0 ? ' · ' : nothing}${p}`)}
    </div>`;
  }

  private renderUnifiedItem(it: UnifiedTurnItem, turnStartedAtMs: number | null = null): TemplateResult {
    switch (it.kind) {
      case 'user': {
        // Tempdoc 610 — the transcript controls (edit-in-place, the per-turn ⋯ menu, the version pager,
        // the context-floor divider) live in renderMessage. When this record turn has a matching live
        // message, render it so the user turn gets those affordances on the canonical record path too
        // (mirroring the assistant case). The match is now computed ONCE by the merge authority
        // (621 Phase 4 — `attachLiveMatch`), so the renderer reads it instead of reaching into the thread.
        const live = it.attributes.live as ThreadMessage | undefined;
        if (live) return this.renderMessage(live, this.thread.indexOf(live));
        // Tempdoc 577 §2.14 Root I (#19) — temporal anchoring on the turn boundary: an ambient
        // relative-time label on each user turn (the turn's start), absolute time on hover. Gentle
        // (ambient altitude, not per-line noise) — only turn boundaries carry it.
        return html`<div class="message user" data-item-id=${it.id}>
          <span class="message-body">${it.content}</span>
          ${it.ts > 0
            ? html`<time
                class="turn-time"
                datetime=${new Date(it.ts).toISOString()}
                title=${new Date(it.ts).toLocaleString()}
                >${formatRelative(it.ts)}</time
              >`
            : nothing}
        </div>`;
      }
      case 'assistant': {
        // Tempdoc 561 P-A (evidence non-divergence): prefer the live message ONLY when it carries fresher
        // evidence (the in-session case). On reload the live thread is rebuilt WITHOUT evidence
        // (loadConversation maps role/content only), so we render evidence FROM the record — the record is
        // the single authority; the two paths can no longer diverge on reload. The "prefer fresher" match
        // is now computed ONCE by the merge authority (621 Phase 4 — `attachLiveMatch`); the renderer reads
        // `attributes.live` and never reaches into the thread at render time (closes the 610 §F.3 fork).
        const live = it.attributes.live as ThreadMessage | undefined;
        if (live) return this.renderMessage(live, this.thread.indexOf(live));
        // Tempdoc 565 §3.A — the AGENT answer record carries `sources` (AgentSource) + `citations`
        // (AgentSentenceCite); render it as markdown with inline [n] marks FROM the record, so a
        // reloaded thread matches the live render (the reload-durability case). Distinguished from the
        // RAG record (RetrievalCitation under `citations` + `claimMatches`) by the `sources` key.
        if (Array.isArray(it.attributes.sources) && it.attributes.sources.length > 0) {
          const cites = Array.isArray(it.attributes.citations)
            ? (it.attributes.citations as AgentSentenceCite[])
            : [];
          const agentSources = it.attributes.sources as AgentSource[];
          // Tempdoc 859 §4 — the RECORD's own producer stamp, so a reloaded delegate answer is gated
          // by what actually scored it rather than by the pre-stamp allowance.
          const recordScorer = it.attributes.citationScorer;
          const marks = this.resolveAnswerCitations(
            agentSources,
            cites,
            typeof recordScorer === 'string' ? recordScorer : null,
          );
          // Tempdoc 577 Move 3 / 603 D-4 — even a sourced agent answer carries a frame: full coverage →
          // grounded (no banner); partial → partially-grounded; document-level (no chunk identity) →
          // `sourced` (provenance, no per-sentence verification). One authority decides.
          const frame = this.frameFor(
            this.currentShapeId(),
            agentSources.length,
            cites,
            it.content,
            sourcesAreChunkPrecise(agentSources),
            // Tempdoc 720 — a committed timeline item is settled (the live answer streams via
            // `ctrl.streamingText`, not this branch), so a zero-cite chunk-precise answer frames `sourced`.
            true,
          );
          const degraded = groundingDegraded(this.currentShapeId(), it.attributes.sources.length);
          const partsA = this.recordFloorParts(it.id);
          // Search Thread S7 (tempdoc decision 6) — the receipt tail: duration best-effort from the
          // nearest preceding user item's ts (no persisted per-turn timing yet); model name read live
          // from aiState (the current session's active model — the best available fact for a just-
          // completed run; a reloaded past turn may show a since-changed model, so this is omitted
          // only when aiState carries none, never fabricated).
          const receipt = {
            durationMs:
              turnStartedAtMs != null && it.ts > turnStartedAtMs
                ? it.ts - turnStartedAtMs
                : undefined,
            modelLabel: getAiState().runtime?.modelLabel ?? null,
          };
          return html`${partsA.divider}<div class="message assistant${partsA.cls}" data-item-id=${it.id}>
            ${/* Search Thread S7 — the transform-shaped "unmissable" warning stays PRE-content (its
                established position: an unmissable strip that abuts the extracted result — see
                `.answer-frame-transform`); every other frame's line is now the quiet receipt UNDER the
                answer. Exactly one of the two ever renders for a given turn — the ONE authority, split
                by call site, never a second simultaneous line. */ ''}
            ${frame === 'transform' ? this.renderAnswerFrameLine(frame, degraded, receipt) : nothing}
            ${/* Tempdoc 848 §2.6 — the agent run's thinking, folded from the run journal onto this
                answer event by `AgentInteractionMapper.fromRunEvents`. Same position as the chat
                path: before the answer body. */ ''}
            ${reasoningBlocksFromRecord(it.attributes.reasoning).map(
              (block) => html`<jf-reasoning-block
                data-testid="chat-turn-reasoning"
                .text=${block.text}
                .durationMs=${block.durationMs}
              ></jf-reasoning-block>`,
            )}
            <jf-markdown-block .text=${it.content} .citations=${marks} frame=${frame}></jf-markdown-block>
            ${frame !== 'transform' ? this.renderAnswerFrameLine(frame, degraded, receipt) : nothing}
            ${this.renderGroundingBadge(
              it.content,
              it.attributes.sources as AgentSource[],
              it.attributes.citations,
              // Tempdoc 720 — committed timeline item ⇒ settled (see the frameFor call above).
              true,
            )}
            ${this.renderSourceChips(it.attributes.sources as AgentSource[], it.id)}
            ${this.recordActionBar(it.id)}
          </div>`;
        }
        // Tempdoc 621 Phase 4-full — the RAG/chat record turn renders through the ONE chat/RAG body
        // (`renderMessage`), so a RELOADED turn renders IDENTICALLY to its live render (shape tag +
        // per-turn frame + inline marks + citations panel + action bar) — the full 610 §F.3 "live==record"
        // closure, eliminating the second (inline) render path. The live thread entry (present on reload —
        // `loadConversation` rebuilds role/content/id/shapeId) is ENRICHED with the record's persisted
        // evidence (the record stays the single authority; we never invent data); a thread-less record turn
        // (the transient load window) falls back to a minimal reconstruction. `renderMessage` derives the
        // floor parts from `floorFrameParts(id, idx)` — identical to the former `recordFloorParts(id)` — and
        // omits the thread-coupled action bar when `idx < 0` (matching the former `recordActionBar`).
        const idx = this.thread.findIndex((m) => m.id === it.id);
        const shapeId = this.currentShapeId();
        const ragSources = Array.isArray(it.attributes.citations)
          ? (it.attributes.citations as RetrievalCitation[])
          : [];
        // Tempdoc 621 Phase 4-full — FORWARD-COMPAT read: the decontextualized "Interpreted as…" question is
        // delivered LIVE via the `rag.rewrite` SSE event but is NOT persisted on the assistant record
        // (`ConversationEngine.persistedAssistant` stores only citations/calibration/claimMatches), so this is
        // ABSENT today and the note does not render on reload — a backend follow-up (persist it on the record),
        // the sibling of the per-message `shapeId` gap. Wired now so it lights up the day the record carries it.
        const standalone =
          typeof it.attributes['rag.standaloneQuestion'] === 'string'
            ? (it.attributes['rag.standaloneQuestion'] as string)
            : typeof it.attributes.standaloneQuestion === 'string'
              ? (it.attributes.standaloneQuestion as string)
              : undefined;
        const base: ThreadMessage =
          idx >= 0
            ? this.thread[idx]!
            : { role: 'assistant', content: it.content, shapeId, id: it.id };
        const recordReasoning = reasoningBlocksFromRecord(it.attributes.reasoning);
        const enriched: ThreadMessage = {
          ...base,
          // Tempdoc 621 Phase 4-full — the turn's shape on the record path is the window's CURRENT shape
          // (`currentShapeId()`), NOT the reloaded thread's `shapeId`: the auto-restore seeds the thread
          // with a placeholder `core.free-chat` (per-message shape is not persisted — the documented
          // backend gap), so inheriting it mislabels a reloaded Document-Q&A turn as "Chat". This mirrors
          // the former record branch (which framed via `currentShapeId()`); now it also drives the shape tag.
          shapeId,
          // Tempdoc 621 review fix — a reloaded EXTRACT turn must keep its verbatim (`transform`) render, not
          // re-render as markdown. Extract carries no per-turn flag on the record, so derive it from the mode.
          isExtract: shapeId === 'core.extract',
          sources: ragSources,
          claims: claimsFromRecord(it.attributes.claimMatches),
          citations: matchesFromRecord(it.attributes.claimMatches),
          // Tempdoc 848 §2.6 — record-first, live fallback: the record is the authority for a
          // reloaded turn (where `base` carries none), and in-session the `attributes.live`
          // short-circuit above means this path is effectively reload-only.
          reasoning: recordReasoning.length > 0 ? recordReasoning : base.reasoning,
          // Tempdoc 836 S2S3-A.6f — the RELOADED turn reads its coverage facts from the same
          // persisted payload the live turn emitted, so the two render paths cannot disagree about
          // whether verification ran.
          coverage: coverageHonesty(
            it.attributes.claimMatches as Parameters<typeof coverageHonesty>[0],
          ),
          sourceCoverage: readSourceCoverage(it.attributes.claimMatches),
          ...(standalone ? { standaloneQuestion: standalone } : {}),
        };
        return this.renderMessage(enriched, idx);
      }
      case 'tool-activity':
        return this.renderToolActivity(it);
      case 'error': {
        // Tempdoc 565 §12 Phase 2 — carry the error code (live + record render identically now).
        const code = typeof it.attributes.errorCode === 'string' ? it.attributes.errorCode : '';
        // Tempdoc 848 §2.4 (D-7) — a run that failed or was halted still THOUGHT, and the agent fold
        // attaches those trailing blocks to its terminal ERROR event. Rendering them here is what
        // makes the record's honesty visible: what the model worked out before it broke is the most
        // useful thing on a failed turn, and dropping it would leave the fold writing to nothing.
        const failedReasoning = reasoningBlocksFromRecord(it.attributes.reasoning);
        return html`<div class="error">${code ? html`[${code}] ` : nothing}${it.content}</div>
          ${failedReasoning.map(
            (block) => html`<jf-reasoning-block
              data-testid="chat-turn-reasoning"
              .text=${block.text}
              .durationMs=${block.durationMs}
            ></jf-reasoning-block>`,
          )}`;
      }
      case 'progress':
        // Search Thread S4-final (item 3) — a restored SEARCH event. `unifiedThreadProjection.ts`
        // (read-only to this consumer) rides SEARCH on the existing 'progress' UnifiedTurnKind —
        // UnifiedTurnKind is a closed union owned by that file, so branching on the `searchEvent`
        // marker attribute HERE (rather than adding a new UnifiedTurnKind literal there) is the only
        // option available to this consumer; see `renderRestoredSearchItem` for the render.
        if (it.attributes?.searchEvent === true) {
          return this.renderRestoredSearchItem(it);
        }
        // Tempdoc 565 §30 — a human STEERING directive (the DIRECTION authority's interject) renders as
        // a distinct human-origin chip, not an ambient agent step, so the user sees their direction land.
        if (it.attributes?.steer === true) {
          return html`<div class="steer-directive" data-steer>
            <span class="steer-directive__label">Your direction</span>
            <span class="steer-directive__text">${it.content}</span>
          </div>`;
        }
        // Tempdoc 565 §17 — the step's tone/glyph is carried by the <jf-run-node> beside this in the
        // trace row; the label is the backend's already-human AgentProgress message rendered as-is
        // (NOT CSS-uppercased — the §16.4/§17.G fix; "Calling LLM", not "CALLING LLM").
        return html`<div class="trace-label">${stepPresentation(it).label}</div>`;
      case 'handoff': {
        // Tempdoc 585 §D Phase 2 (D2) — the structured handoff card (from → to + reason), replacing
        // the prior flat trace-label text. Falls back to the content string if the ids are absent
        // (e.g. a legacy persisted handoff that predates the discrete-id projection).
        const from = it.attributes?.fromAgentId as string | undefined;
        const to = it.attributes?.toAgentId as string | undefined;
        if (!from && !to) {
          return html`<div class="trace-label">${it.content}</div>`;
        }
        return html`<jf-handoff-card
          .from=${from ?? ''}
          .to=${to ?? ''}
          .reason=${(it.attributes?.reason as string | undefined) ?? ''}
        ></jf-handoff-card>`;
      }
    }
  }

  /**
   * Search Thread S4-final (item 3) — a restored SEARCH thread event, rendered `variant='excerpt'`
   * (collapsed by default, matching a reloaded thread's ambient posture). The backend persists only
   * the search's IDENTITY (`query`/`mode`/`matchCount`/`resultCount`/`docIds`/`executedAt`) — never
   * the full hit objects — so `snapshot.results` is deliberately empty here: expanding shows the
   * provenance header + the "Search again" fork affordance + `renderSnapshotRows`'s honest
   * "results not stored — run again to see them" note, never a fabricated row list.
   */
  private renderRestoredSearchItem(it: UnifiedTurnItem): TemplateResult {
    const a = it.attributes;
    const query = typeof a.query === 'string' ? a.query : '';
    const mode = typeof a.mode === 'string' ? a.mode : 'TEXT';
    const matchCount = typeof a.matchCount === 'number' ? a.matchCount : 0;
    const resultCount = typeof a.resultCount === 'number' ? a.resultCount : 0;
    const executedAt = typeof a.executedAt === 'string' ? a.executedAt : new Date(it.ts).toISOString();
    const snapshot: CardSnapshot = {
      query,
      results: [],
      matchCount,
      totalHits: resultCount,
      facetsTruncated: false,
      isSearching: false,
      processingTimeMs: null,
      error: null,
    };
    const provenance: SearchProvenance = { actor: 'user', query, mode, matchCount, resultCount, executedAt };
    return html`<jf-results-card
      variant="excerpt"
      .snapshot=${snapshot}
      .provenance=${provenance}
      @card-fork=${(e: CustomEvent<{ query: string }>) => this.handleCardFork(e.detail.query)}
    ></jf-results-card>`;
  }

  /** Tempdoc 561 P-A/P-B (Slice 2): an agent tool call rendered inline in the unified thread. */
  private renderToolActivity(it: UnifiedTurnItem): TemplateResult {
    const a = it.attributes;
    // Tempdoc 565 §12.3.B — render the record's tool activity through the SAME <jf-tool-call-card>
    // the live half uses (ONE tool renderer; retires the static `🔧 tool · status` fork). The
    // projection merges the call's lifecycle events, so `attributes` carry identity (toolName /
    // arguments / risk from `proposed`) + outcome (output / structuredData from `completed`, reason
    // from `rejected`). Risk persists lowercase; the card expects the live uppercase ToolRisk.
    const toolCall: ToolCall = {
      callId: typeof a.callId === 'string' ? a.callId : it.id,
      toolName: typeof a.toolName === 'string' ? a.toolName : 'tool',
      arguments: typeof a.arguments === 'string' ? a.arguments : '',
      risk: (typeof a.risk === 'string' ? a.risk.toUpperCase() : 'LOW') as ToolCall['risk'],
      status: (typeof a.status === 'string' ? a.status : 'completed') as ToolCall['status'],
      output: typeof a.output === 'string' ? a.output : undefined,
      success: typeof a.success === 'boolean' ? a.success : undefined,
      rejectReason: typeof a.reason === 'string' ? a.reason : undefined,
      structuredData:
        a.structuredData && typeof a.structuredData === 'object'
          ? (a.structuredData as Record<string, unknown>)
          : undefined,
      gateBehavior:
        typeof a.gateBehavior === 'string'
          ? (a.gateBehavior as ToolCall['gateBehavior'])
          : undefined,
    };
    return html`<div class="message assistant tool-activity">
      <jf-tool-call-card
        .toolCall=${toolCall}
        .stepPresentation=${stepPresentation(it)}
        @card-open=${(e: CustomEvent<{ id: string }>) => this.handleToolEvidenceOpen(toolCall, e.detail.id)}
      ></jf-tool-call-card>
    </div>`;
  }

  /**
   * Search Thread S7 (tempdoc decision 4) — a `card-open` from the agent-search tool card nested
   * inside `<jf-tool-call-card>` (its `card-open` bubbles + composes through the results-card's OWN
   * shadow boundary, then through ToolCallCard's, arriving here). Mirrors `handleCommittedCardOpen`:
   * looks the hit back up by id (== path) in the tool call's OWN structuredData — the card carries no
   * independent hit store — and opens it through the same reading-pane path.
   */
  private handleToolEvidenceOpen(toolCall: ToolCall, hitId: string): void {
    const host = this.host_;
    if (!host?.search || !host?.ui) return;
    const hit = findAgentSearchHit(toolCall.structuredData, hitId);
    if (!hit) return;
    host.ui.showInspector(
      host.search.hitToSelectedItem(hit as unknown as import('../plugin-api/plugin-types.js').SearchHitSnapshot),
    );
    addScopeChip({ kind: 'file', label: filenameOf(hit.path), docIds: [hit.path] });
  }

  private renderMessage(m: ThreadMessage, idx: number): TemplateResult {
    // Tempdoc 610 Phase C — floor divider + out-of-context band. Messages above
    // the floor render dimmed (distinct from the ↪ inherited treatment); the
    // divider renders just above the floor message.
    // Tempdoc 610 §F.3 — the floor divider + out-of-context/excluded dim-class come from the one
    // shared authority (floorFrameParts); the live path adds only its own `inherited` treatment.
    const fp = this.floorFrameParts(m.id, idx);
    const floorDivider = fp.divider;
    const inheritedClass = (m.inheritedFromParent ? ' inherited' : '') + fp.cls;
    if (m.role === 'user') {
      // Tempdoc 610 Phase A — edit-in-place: when this user turn is being edited,
      // swap its text for a native textarea (Save → branch-from-before + re-dispatch).
      if (this.editingMessageId && m.id === this.editingMessageId) {
        return html`${floorDivider}<div class="message user editing">
          <textarea
            class="msg-edit"
            .value=${this.editingDraft}
            aria-label="Edit message"
            @input=${(e: Event) => {
              this.editingDraft = (e.target as HTMLTextAreaElement).value;
            }}
            @keydown=${(e: KeyboardEvent) => this.onEditKeydown(e, idx)}
          ></textarea>
          <div class="msg-edit-actions">
            <button class="msg-edit-save" @click=${() => void this.commitEdit(idx)}>Save</button>
            <button class="msg-edit-cancel" @click=${() => this.cancelEdit()}>Cancel</button>
          </div>
        </div>`;
      }
      // Tempdoc 610 Phase A — user-turn hover toolbar (mirrors the assistant one).
      // Gated identically to the assistant Branch affordance: own (non-inherited)
      // turn, stable backend id present, not mid-stream, and not an agent run
      // (agent-run branching is out of scope per 513 §A.5).
      // Tempdoc 610 §D.2 — the per-turn controls render in the action bar BELOW
      // the bubble (not in the bubble corner), matching ChatGPT/Claude.
      return html`${floorDivider}<div class="message user${inheritedClass}" data-item-id=${m.id ?? nothing}>
          <span class="message-body">${m.content}</span>
        </div>
        ${idx >= 0 ? this.renderTurnActionBar(m, idx) : nothing}`;
    }
    const shapeLabel = SHAPE_LABELS[m.shapeId] ?? m.shapeId;
    // Tempdoc 610 §D.2 — per-turn controls (incl. branch) now live in the action
    // bar below the message (renderTurnActionBar), gated by canTurnControl.
    // Tempdoc 577 §2.12 Move 3 — the epistemic frame for the live ThreadMessage path (carries
    // shapeId). The claims' similarity feeds the coverage; the source count is the grounding signal.
    // Tempdoc 822 §3d — only a cross-encoder-verified claim contributes a similarity to the coverage
    // read. A lexical-only claim is excluded STRUCTURALLY (it has no score on this scale to give),
    // so "Grounded · N of M" can no longer be lifted by word overlap.
    // Tempdoc 822 §3b — the coverage counts the RESOLVED MARKS, which is the same list the answer
    // renders, so a claim the resolver dropped (no verified ref, or one addressing no source) is
    // not counted as grounded. "Grounded · 4 of 6" instead of "5 of 6" is the honest read: the
    // frame degrades because the evidence degraded, rather than claiming a mark that isn't there.
    const marks = this.resolveClaimCitations(m.claims ?? [], m.sources ?? []);
    const sourceCount = (m.sources?.length ?? 0) + (m.claims?.length ?? 0);
    // Tempdoc 603 D-4 — document-level sources (agent, no chunk identity) frame as `sourced`, not
    // "Grounded · 0 of N". RAG sources (RetrievalCitation, chunk-native) carry no sentinel → chunk-precise.
    const frame: AnswerFrame = m.isExtract
      ? 'transform'
      : this.frameFor(
          m.shapeId,
          sourceCount,
          marks,
          m.content,
          sourcesAreChunkPrecise(m.sources ?? []),
          // Tempdoc 720 — renderMessage renders committed messages (the in-progress answer streams via
          // renderStreamingBlock / ctrl.streamingText), so it is settled: a zero-cite chunk-precise answer
          // frames `sourced`, not "grounded" over zero sentences.
          true,
          // Tempdoc 836 S2S3-A.2 — the turn's coverage facts, so the denominator is the backend's
          // sentence count and the line can say why it degraded.
          m.coverage ?? null,
        );
    const degraded = m.isExtract ? false : groundingDegraded(m.shapeId, sourceCount);
    // Search Thread S7 (tempdoc decision 6) — the receipt tail: duration from the message's own
    // captured `durationMs` (set at `send()`'s onDone; absent on a reloaded/record turn — omitted,
    // never fabricated), model name read live from aiState.
    const receipt = { durationMs: m.durationMs, modelLabel: getAiState().runtime?.modelLabel ?? null };
    // Tempdoc 836 S2S3-A.2 — the coverage line for THIS turn, read from the same projection the
    // frame reads. Null unless the run reported an incomplete pass, so an answer whose text was
    // fully examined renders exactly as it did before.
    const coverageNoteText = m.isExtract
      ? null
      : coverageNote(groundingCoverage(marks, m.content, m.coverage ?? null));
    return html`
      ${floorDivider}
      <div class="message assistant${inheritedClass}" data-item-id=${m.id ?? nothing} data-msg-idx=${idx}>
        <div class="message-shape-tag">${shapeLabel}</div>
        ${/* Tempdoc 848 §2.6 — the turn's thinking, rendered from the COMMITTED/record message so it
            survives `done` and a reload. It keeps the position `renderStreamingBlock` gives it (after
            the shape tag, before the answer), so the block does not move as the turn settles. The
            streaming render stays where it is: the two are complementary phases of one turn. */ ''}
        ${(m.reasoning ?? []).map(
          (block) => html`<jf-reasoning-block
            data-testid="chat-turn-reasoning"
            .text=${block.text}
            .durationMs=${block.durationMs}
          ></jf-reasoning-block>`,
        )}
        ${m.standaloneQuestion
          ? html`<div class="rewrite-note" role="note">
              Interpreted as: <em>${m.standaloneQuestion}</em>
            </div>`
          : nothing}
        ${/* Search Thread S7 — the transform-shaped "unmissable" warning stays PRE-content (its
            established position abutting the extracted result); every other frame's line is now the
            quiet receipt UNDER the answer. Exactly one of the two ever renders per turn. */ ''}
        ${frame === 'transform' ? this.renderAnswerFrameLine(frame, degraded, receipt, coverageNoteText) : nothing}
        ${m.isExtract
          ? // Tempdoc 565 §15.B — extract is verbatim text: the ONE renderer in `plain` format.
            html`<jf-markdown-block format="plain" .text=${m.content} frame=${frame}></jf-markdown-block>`
          : (m.claims?.length ?? 0) > 0
            ? // Tempdoc 565 §15.B — RAG grounding now renders through the ONE renderer + weave: the
              // claims map into the shared `Citation` shape, so RAG gains source-stable, deep-linked,
              // cross-surface-selectable marks (the markdown-aware decoration the §3.C note awaited).
              html`<jf-markdown-block
                format="plain"
                .text=${m.content}
                .citations=${marks}
                frame=${frame}
              ></jf-markdown-block>`
            : // Tempdoc 565 §15.B — the canonical answer block for every other mode (agent/chat).
              html`<jf-markdown-block .text=${m.content} frame=${frame}></jf-markdown-block>`}
        ${frame !== 'transform' ? this.renderAnswerFrameLine(frame, degraded, receipt, coverageNoteText) : nothing}
        ${(m.sources?.length ?? 0) > 0 || (m.citations?.length ?? 0) > 0
          ? html`<jf-citations-panel
              .sources=${m.sources ?? []}
              .citations=${m.citations ?? []}
              .sourceCoverage=${m.sourceCoverage ?? []}
              .retrievalMode=${m.ragMeta?.retrieval_mode ?? ''}
            ></jf-citations-panel>`
          : nothing}
        ${idx >= 0 ? this.renderTurnActionBar(m, idx) : nothing}
      </div>
    `;
  }

  /**
   * Slice 513 — splice backend-assigned message ids into the local thread so
   * freshly-streamed messages are branchable without a full reload. Order is
   * stable (engine appends sequentially); we positionally line up the ids.
   */
  private async syncMessageIds(): Promise<void> {
    // Slice 515 FIX-4 + 516 FIX-T2 — stale-response guard. Rapid sends can
    // produce overlapping syncMessageIds chains; only the latest one's
    // result should reach the thread. Capture token + sessionId before
    // fetch; reject if either changed (another send, or conversation
    // switched mid-fetch) by the time we return.
    const myToken = ++this.syncToken;
    const mySession = this.sessionId;
    const backend = await fetchMessageIds(mySession);
    if (!backend) return;
    if (myToken !== this.syncToken) return;
    if (mySession !== this.sessionId) return;
    // Count how many thread entries need ids; early-exit once all matched
    // to avoid O(N²) walks on long sessions.
    let unmatched = 0;
    for (const m of this.thread) if (!m.id && !m.inheritedFromParent) unmatched++;
    if (unmatched === 0) return;
    let cursor = 0;
    const next = this.thread.map((m) => {
      if (m.id || m.inheritedFromParent) return m;
      if (unmatched === 0) return m;
      while (cursor < backend.length) {
        const candidate = backend[cursor++];
        if (!candidate) continue;
        if (candidate.role === m.role && candidate.content === m.content && candidate.id) {
          unmatched--;
          return { ...m, id: candidate.id };
        }
      }
      return m;
    });
    // Re-check token + session after the (synchronous) walk to be safe under
    // future refactors that might insert awaits.
    if (myToken !== this.syncToken) return;
    if (mySession !== this.sessionId) return;
    this.thread = next;
  }

  /**
   * Slice 513 — branch the current conversation from the given assistant
   * message. Creates a new session on the backend whose history will lazily
   * resolve the parent prefix up to this message, then swaps the chat view
   * to the new session so the user can continue the divergent thread.
   */
  private async branchHere(fromMsgId: string): Promise<void> {
    const preview = this.thread.find((m) => m.role === 'user')?.content ?? '';
    const newSessionId = await branchConversation(this.sessionId, fromMsgId, preview);
    if (!newSessionId) {
      this.errorMessage = 'Failed to create branch';
      return;
    }
    await this.loadConversation(newSessionId, this.thread[0]?.shapeId ?? 'core.free-chat');
  }

  private renderStreamingBlock(): TemplateResult | typeof nothing {
    if (!this.streamingText && !this.isStreaming && !this.reasoning.isThinking) return nothing;
    const currentShape = this.dispatchShape();
    const isExtract = currentShape === 'core.extract';
    return html`
      <div class="message assistant">
        <div class="message-shape-tag">
          ${SHAPE_LABELS[currentShape]} ${this.isStreaming
            ? this.streamingText ? '(streaming)' : ''
            : ''}
        </div>
        ${this.isStreaming && !this.streamingText && !this.reasoning.isThinking && this.thinkingElapsedSec >= 2
          ? html`<div class="thinking-timer">Thinking… ${this.thinkingElapsedSec}s</div>`
          : nothing}
        ${this.ragMeta ? this.renderPreamble() : nothing}
        ${this.rewriteNote
          ? html`<div class="rewrite-note" role="note">
              Interpreted as: <em>${this.rewriteNote.standalone}</em>
            </div>`
          : nothing}
        ${this.reasoning.isThinking
          ? html`<jf-reasoning-block .controller=${this.reasoning}></jf-reasoning-block>`
          : nothing}
        ${this.reasoning.reasoningBlocks.length > 0 && !this.reasoning.isThinking
          ? this.reasoning.reasoningBlocks.map(
              (block) => html`<jf-reasoning-block
                .text=${block.text} .durationMs=${block.durationMs}
              ></jf-reasoning-block>`,
            )
          : nothing}
        ${isExtract
          ? html`<jf-markdown-block
              format="plain"
              .text=${this.streamingText}
              ?is-streaming=${this.isStreaming}
            ></jf-markdown-block>`
          : html`<jf-markdown-block
              format="plain"
              .text=${this.streamingText}
              .citations=${this.resolveClaimCitations(this.claims, this.sources)}
              ?is-streaming=${this.isStreaming}
            ></jf-markdown-block>`}
        ${this.sources.length > 0 || this.citations.length > 0
          ? html`<jf-citations-panel
              .sources=${this.sources}
              .citations=${this.citations}
              .retrievalMode=${this.ragMeta?.retrieval_mode ?? ''}
            ></jf-citations-panel>`
          : nothing}
      </div>
    `;
  }

  private renderPreamble(): TemplateResult {
    const m = this.ragMeta!;
    const mode = m.retrieval_mode ?? '';
    if (mode === 'FULLTEXT_FALLBACK') {
      return html`<div class="preamble">
        Full-document retrieval (document too small for chunking)
      </div>`;
    }
    const chunks = m.chunks_used ?? 0;
    const found = m.chunks_found ?? 0;
    const coverage = m.retrieval_coverage ?? 0;
    const truncated = m.context_truncated ?? false;
    const parts: string[] = [];
    parts.push(
      `${chunks} ${chunks === 1 ? 'passage' : 'passages'} used` +
        (found > chunks ? ` (${found} found)` : ''),
    );
    parts.push(mode.toLowerCase().replace(/_/g, ' '));
    if (coverage > 0)
      parts.push(`coverage ${Math.round(coverage * 100)}%`);
    if (truncated) parts.push('context truncated');
    return html`<div class="preamble">${parts.join(' · ')}</div>`;
  }

  private renderSchemaInput(): TemplateResult {
    return html`
      <div>
        <div class="schema-label">
          JSON Schema
          ${/* S5a (decision 6) — a derived attachment can be detached; an explicit Structured
                tab selection toggles off through the tab instead. */ ''}
          ${this.schemaAttached
            ? html`<jf-control
                class="schema-detach"
                data-testid="schema-detach"
                label="Detach schema"
                .onActivate=${() => {
                  this.schemaAttached = false;
                }}
                >Detach schema</jf-control
              >`
            : nothing}
        </div>
        <textarea
          class="mono"
          rows="4"
          .value=${this.schemaDraft}
          ?disabled=${this.isStreaming}
          @input=${(e: Event) =>
            (this.schemaDraft = (e.target as HTMLTextAreaElement).value)}
        ></textarea>
      </div>
    `;
  }

  private getPlaceholder(): string {
    // Tempdoc 561 C-2: in agent mode the placeholder grades with the autonomy posture.
    if (this.affordance === 'agent') {
      return postureChrome(agencyPosture(this.affordance, getAutonomyLevel())).placeholder;
    }
    switch (this.affordance) {
      case 'retrieve':
        return 'Search your files…';
      case 'documents':
        return 'Ask a question about your indexed documents…';
      case 'extract':
        return 'Describe what to extract…';
      default:
        return 'Type a message…';
    }
  }

  /** Tempdoc 561 C-2: the send-button label grades with the agency posture (agent mode only). */
  private getSubmitLabel(): string {
    // Tempdoc 577 Goal 3 / Search Thread D2/D3 (stage S2) — the retrieve base tier is pure search
    // (no LLM dispatch by default); its submit runs the search, so it reads "Search" whether or not a
    // chat model is online (it never says "AI Offline") — UNLESS the current turn's route reads as
    // 'ask' (a '?', a starter word, …), in which case the button names what Enter will actually do.
    if (this.affordance === 'retrieve') return this.currentRoute() === 'ask' ? 'Ask' : 'Search';
    if (!this.aiState?.capabilities?.chat) return 'AI Offline';
    if (this.affordance === 'agent') {
      return postureChrome(agencyPosture(this.affordance, getAutonomyLevel())).sendLabel;
    }
    return 'Send';
  }

  /**
   * Search Thread D5 (stage S3) — the docIds a grounded Ask forwards: the union of `pinnedDocIds`
   * (the existing selectionState-sourced result-set pin, Slice 515 FIX-1) and every pinned scope
   * chip's docIds, deduped. Both sources carry PATHS (not the SearchHit `id` — see the reconciliation
   * note on {@link handleCardScopeFile}), so the union is a plain string-set merge.
   */
  private effectiveDocIds(): string[] {
    const ids = new Set<string>(this.pinnedDocIds);
    for (const chip of this.scopeChips) {
      for (const id of chip.docIds) ids.add(id);
    }
    return [...ids];
  }

  private async send(): Promise<void> {
    const text = this.inputDraft.trim();
    if (!text || this.isStreaming) return;

    // Tempdoc 561 P-B (body-unification): in agent mode the conversation IS the agent run — route the
    // message to the inline-hosted controller (it renders in this same thread; approvals route through
    // the global ceremony; the autonomy dial is obeyed via the backend gateBehavior, P-D).
    if (this.affordance === 'agent') {
      const ctrl = this.ensureAgentCtrl();
      if (ctrl.available !== true || ctrl.isStreaming) return;
      this.inputDraft = '';
      ctrl.navigationReceipts = [];
      // Tempdoc 561 P-B (non-divergence): on the run's terminal, refresh the record so the live overlay
      // folds into the /api/thread projection (the dedup above then renders each entry from the record).
      // Tempdoc 565 §30 — `initiate` flows through the ONE control-intent seam (the DIRECTION authority),
      // alongside interject (steer) / set-posture (dial) / halt (stop).
      void dispatchRunControl(ctrl, { kind: 'initiate', prompt: text }).then(
        () => void this.refreshUnifiedThread(),
      );
      return;
    }

    // Tempdoc 526 §4.5 / §6 — single dispatch routing: prefer the compose()-
    // parked shapeId; otherwise consult the same resolver compose() uses,
    // with the current selection's kind + the UI affordance hint. T6 closed
    // the askAi-vs-direct-route fork by removing the legacy affordanceToShape
    // fallback; SEND-button flows now share the (operation, kind, affordance)
    // → shapeId table with compose()-driven flows.
    const forced = takePendingForceShape();
    const shapeId: ShapeId = (forced as ShapeId | null) ?? this.dispatchShape();
    // Tempdoc 526 §12.4 — drain the pending selection set by compose() on the
    // navigation event. It is a ONE-SHOT register that only compose()-driven
    // flows write, so an ordinary Send used to carry no body.selection at all:
    // every document-bearing injector (SelectionContextInjector) then saw an
    // empty selection and the shape ran with no document behind it. Fall back
    // to what the user actually has selected — the live selection IS the
    // document they mean.
    const selection =
      takePendingSelection() ?? selectionItemToWirePayload(getCurrentSelection().items[0]);
    const body = buildRequestBody(
      shapeId,
      text,
      this.sessionId,
      this.schemaDraft,
      this.effectiveDocIds(),
      selection,
    );

    // Tempdoc 561 P-A/P-B: stamp the surface conversationId on EVERY dispatch so the backend records
    // the answer-plane turn (incl. ephemeral RAG, which carries no sessionId) onto the ONE canonical
    // conversation record. The unified thread, History, and Timeline then project the grounded answer
    // + its evidence FROM that record — they cannot disagree. EPHEMERAL retrieval semantics are
    // unchanged backend-side: conversationId is a write key, never a context-load key.
    body.conversationId = this.sessionId;

    // Cross-turn context forwarding: include recent conversation history
    // so the model has continuity across shape boundaries. For FreeChat
    // (PERSISTENT), only include non-FreeChat turns — FreeChat's own
    // history is loaded from the backend session store, so including
    // FreeChat turns here would duplicate them.
    if (this.thread.length > 0) {
      const recent = this.thread.slice(-10);
      const context: Array<{ role: string; content: string }> = [];
      for (const m of recent) {
        if (shapeId === 'core.free-chat' && m.shapeId === 'core.free-chat') continue;
        context.push({ role: m.role, content: m.content });
      }
      if (context.length > 0) {
        body.context = context;
      }
    }

    this.thread = [...this.thread, { role: 'user', content: text, shapeId }];
    this.showResumePrompt = false;
    if (this.thread.length === 1) {
      // Tempdoc 562: record the session POINTER only — the preview is later derived from the lock-safe
      // backend list, so no plaintext message content is cached client-side.
      recordRecentSession(this.sessionId);
    }
    // Tempdoc 609 Phase 3 — a brand-new conversation (started by sending, not by loadConversation) is
    // now what this tab is viewing; remember it so a navigation round-trip restores it.
    setLastViewedConversation(this.sessionId);
    this.inputDraft = '';
    this.abortController = new AbortController();
    this.isStreaming = true;
    this.startRenderTick();
    setAiActivity({
      state: shapeId === 'core.extract' ? 'extracting' : 'thinking',
      shapeId,
      startedAtMs: Date.now(),
      canCancel: true,
      cancel: () => this.abortController?.abort(),
    });
    this.streamingText = '';
    this.reasoning.reset();
    this.errorMessage = '';
    this.citations = [];
    this.coverage = null;
    this.sourceCoverage = [];
    this.sources = [];
    this.ragMeta = null;
    this.rewriteNote = null;
    this.claims = [];

    const url = (this.apiBase || '') + '/api/chat/dispatch';

    const handlers: Record<string, (payload: unknown) => void> = {
      onReasoningChunk: (payload: unknown) => {
        this.reasoning.handleReasoningChunk(payload);
      },
      onChunk: (payload: unknown) => {
        this.reasoning.endThinking();
        const p = payload as Record<string, unknown> | string | null;
        const t =
          typeof p === 'string'
            ? p
            : typeof p?.text === 'string'
              ? (p.text as string)
              : '';
        if (t) {
          if (!this.streamingText) setAiActivity({ state: 'streaming' });
          this.streamingText = this.streamingText + t;
        }
      },
      onDone: (payload: unknown) => {
        this.reasoning.finalize();
        // Tempdoc 610 §E.4 — capture the prompt-token occupancy the backend now surfaces on the
        // done payload, so the context-budget meter reflects this turn's window fullness.
        const donePayload = payload as
          | {
              promptTokens?: number;
              contextBreakdown?: { system?: number; conversation?: number; retrieved?: number };
            }
          | null;
        if (donePayload && typeof donePayload.promptTokens === 'number') {
          this.contextPromptTokens = donePayload.promptTokens;
        }
        // Tempdoc 610 §I.2 — capture the per-phase token split for the meter attribution breakdown.
        const b = donePayload?.contextBreakdown;
        if (b && typeof b === 'object') {
          this.contextBreakdown = {
            system: typeof b.system === 'number' ? b.system : 0,
            conversation: typeof b.conversation === 'number' ? b.conversation : 0,
            retrieved: typeof b.retrieved === 'number' ? b.retrieved : 0,
          };
        }
        const msg: ThreadMessage = {
          role: 'assistant',
          content: this.streamingText,
          shapeId,
          isExtract: shapeId === 'core.extract',
        };
        // Search Thread S7 (tempdoc decision 6) — the receipt's duration, read BEFORE the activity
        // reset below clears `startedAtMs`. Omitted (never fabricated) if the activity state was
        // somehow already cleared (defensive; `startedAtMs` is set at the top of `send()`).
        const turnStartedAtMs = getAiState().activity.startedAtMs;
        if (turnStartedAtMs != null) msg.durationMs = Date.now() - turnStartedAtMs;
        if (this.citations.length > 0) msg.citations = [...this.citations];
        if (this.sources.length > 0) msg.sources = [...this.sources];
        if (this.claims.length > 0) msg.claims = [...this.claims];
        // Tempdoc 836 S2S3-A.2 — the coverage facts are part of the turn, not of the live stream:
        // the committed message keeps them so its frame line reads the same facts a reload will.
        if (this.coverage) msg.coverage = this.coverage;
        if (this.sourceCoverage.length > 0) msg.sourceCoverage = [...this.sourceCoverage];
        if (this.ragMeta) msg.ragMeta = { ...this.ragMeta };
        // Tempdoc 848 §2.6 — the thinking the reader just watched belongs to the turn, not to the
        // stream: copy it onto the committed message (same conditional-copy idiom as citations
        // above) so it survives `done` instead of unmounting with the streaming block.
        if (this.reasoning.reasoningBlocks.length > 0) {
          msg.reasoning = [...this.reasoning.reasoningBlocks];
        }
        // Tempdoc 603 C2 — pin the decontextualized question onto the committed turn so the
        // "Interpreted as: …" line persists past the live stream (mirrors citations/ragMeta).
        if (this.rewriteNote) msg.standaloneQuestion = this.rewriteNote.standalone;
        if (this.streamingText.trim()) {
          this.thread = [...this.thread, msg];
        }
        this.streamingText = '';
        this.isStreaming = false;
        this.stopRenderTick();
        setAiActivity({ state: 'idle', shapeId: null, startedAtMs: null, canCancel: false, cancel: null });
        // Tempdoc 561 P-A/P-B (Slice 2): the durable record now has this turn — refresh the unified
        // thread so the conversation projects the one record.
        void this.refreshUnifiedThread();
        if (this.thread.length === 2) this.generateTitle();
        // Slice 513 — splice backend-side ids into the freshly-appended
        // messages so "Branch here" works without requiring the user to
        // resume the conversation first. The thread's role+content order
        // matches the persisted log; we positionally line up the ids.
        void this.syncMessageIds();
      },
      onError: (payload: unknown) => {
        const p = payload as Record<string, unknown> | null;
        this.errorMessage =
          (p?.error as string) ??
          (p?.message as string) ??
          'An error occurred';
        this.isStreaming = false;
        this.stopRenderTick();
        setAiActivity({ state: 'idle', shapeId: null, startedAtMs: null, canCancel: false, cancel: null });
      },
      onRagMeta: (payload: unknown) => {
        const p = payload as RagMetaPayload | null;
        if (p) this.ragMeta = p;
      },
      onRagRewrite: (payload: unknown) => {
        // 603 C2 — a follow-up was decontextualized (rag.rewrite → onRagRewrite via the shape-event
        // dispatcher); show what retrieval actually ran on.
        const d = payload as { original?: string; standalone?: string } | null;
        if (d && typeof d.standalone === 'string' && typeof d.original === 'string') {
          this.rewriteNote = { original: d.original, standalone: d.standalone };
        }
      },
      onRagCitations: (payload: unknown) => {
        const p = payload as { citations?: RetrievalCitation[] } | null;
        if (p && Array.isArray(p.citations)) {
          this.sources = p.citations;
        }
      },
      onRagCitationDelta: (payload: unknown) => {
        const p = payload as {
          sentenceIndex?: number;
          sentenceText?: string;
          citations?: Array<{
            parentDocId: string;
            sourceIndex: number;
            score: number;
          }>;
        } | null;
        if (
          p &&
          Array.isArray(p.citations) &&
          typeof p.sentenceText === 'string'
        ) {
          const bestScore = Math.max(...p.citations.map((c) => c.score), 0);
          const existing = this.claims.find(
            (cl) => cl.sentenceIndex === (p.sentenceIndex ?? 0),
          );
          if (!existing) {
            this.claims = [
              ...this.claims,
              {
                sentenceIndex: p.sentenceIndex ?? 0,
                sentenceText: p.sentenceText,
                // Tempdoc 822 §3d — this event is the STREAMING LEXICAL matcher (word-overlap
                // coverage), not the cross-encoder. It lands in `lexicalScore` and leaves
                // `verifiedScore` null, so it can never reach the tier thresholds.
                verifiedScore: null,
                lexicalScore: bestScore,
                // Tempdoc 822 §3b — the same producer split applies to the REFS. Deltas arrive
                // first, so merging them into one ref list made the first ref of every
                // doubly-matched sentence the lexical guess; a mark resolves through verified refs
                // only, so these can never become a deep-link target.
                verifiedRefs: [],
                lexicalRefs: p.citations.map((c) => c.sourceIndex),
              },
            ];
          }
          for (const c of p.citations) {
            const existingCit = this.citations.find(
              (m) =>
                m.parentDocId === c.parentDocId &&
                m.sentenceIndex === (p.sentenceIndex ?? 0),
            );
            if (!existingCit) {
              this.citations = [
                ...this.citations,
                {
                  sentenceIndex: p.sentenceIndex ?? 0,
                  sentenceText: p.sentenceText,
                  sourceIndex: c.sourceIndex,
                  similarity: c.score,
                  parentDocId: c.parentDocId,
                },
              ];
            }
          }
          // If the stream already completed, update the committed thread
          // message so late-arriving delta events aren't lost.
          if (!this.isStreaming) {
            const last = this.thread.at(-1);
            if (last?.role === 'assistant') {
              last.claims = [...this.claims];
              last.citations = [...this.citations];
              this.thread = [...this.thread];
            }
          }
        }
      },
      onRagCitationMatches: (payload: unknown) => {
        const p = payload as { matches?: CitationMatch[] } | null;
        if (p && Array.isArray(p.matches)) {
          // Tempdoc 836 §4 — the PRODUCER gate, live side. A cosine-fallback score is a number on a
          // scale the grounding thresholds are not calibrated for, so it may not become a verified
          // score. The claim still exists (it is what arrived); it simply mints no mark.
          const scorer = readScorer(p);
          const verifiedProducer = isVerifiedProducer(scorer);
          // Tempdoc 847 S1 — and the SOURCES panel answers to the same verdict: `this.citations`
          // feeds `sourceGrounding`, which reads `similarity` straight into a per-source tier, so an
          // ungated assignment here would paint verification beside a source whose sentence got no
          // mark. Gated through the shared authority, live and reloaded alike (§2.3).
          this.citations = [...admittedMatches(p.matches, scorer)];
          // Tempdoc 836 S2S3-A.2 — the run's coverage facts, kept beside the matches so the frame
          // line can say WHY it degraded (budget vs evidence) and the sources panel can tell an
          // unexamined source from an uncited one. Same payload the record persists, so the
          // reloaded render reads exactly the same facts (S2S3-A.6f).
          this.coverage = coverageHonesty(p as Parameters<typeof coverageHonesty>[0]);
          this.sourceCoverage = readSourceCoverage(p);
          // F-5 fix: derive claims from authoritative embedding matches when
          // streaming-lexical didn't fire enough deltas. StreamingCitationMatcher
          // emits rag.citation_delta only when word-overlap matches; that's too
          // strict for typical LLM summary phrasing. rag.citation_matches at
          // done-time gives authoritative cosine-similarity matches — convert
          // them into claims so grounded spans + inline markers (F-17) render.
          // Preserve any existing claims (from streaming deltas) and merge.
          // Tempdoc 822 §3d — the merge keeps the two producers' scores APART. A delta's word-overlap
          // ratio and a match's cross-encoder probability are different quantities; the old
          // `Math.max` across them fed the lexical number into cross-encoder-calibrated thresholds.
          const bySentence = new Map<
            number,
            {
              text: string;
              verifiedScore: number | null;
              lexicalScore: number;
              // Tempdoc 822 §3b — two ref sets, for the same reason there are two scores: the
              // producers do not measure the same thing, and only the verified one may resolve.
              verifiedRefs: Set<number>;
              lexicalRefs: Set<number>;
            }
          >();
          for (const cl of this.claims) {
            bySentence.set(cl.sentenceIndex, {
              text: cl.sentenceText,
              verifiedScore: cl.verifiedScore,
              lexicalScore: cl.lexicalScore,
              verifiedRefs: new Set(cl.verifiedRefs),
              lexicalRefs: new Set(cl.lexicalRefs),
            });
          }
          for (const m of p.matches) {
            const idx = m.sentenceIndex ?? 0;
            const text = m.sentenceText ?? '';
            const sim =
              typeof m.similarity === 'number' && verifiedProducer ? m.similarity : null;
            const existing = bySentence.get(idx);
            if (existing) {
              // 847 S5 — the verified side's text WINS over a mid-stream draft's. The draft cuts an
              // incomplete markdown buffer as prose and the final cuts parsed block nodes, so the
              // same `sentenceIndex` usually names two different sentences; keeping the draft's
              // text would place a mark by evidence another sentence earned, and make this live
              // render disagree with its own reload.
              if (text) existing.text = text;
              existing.verifiedScore =
                sim === null ? existing.verifiedScore : Math.max(existing.verifiedScore ?? 0, sim);
              if (verifiedProducer && typeof m.sourceIndex === 'number') {
                existing.verifiedRefs.add(m.sourceIndex);
              }
            } else {
              const verifiedRefs = new Set<number>();
              if (verifiedProducer && typeof m.sourceIndex === 'number') {
                verifiedRefs.add(m.sourceIndex);
              }
              bySentence.set(idx, {
                text,
                verifiedScore: sim,
                lexicalScore: 0,
                verifiedRefs,
                lexicalRefs: new Set<number>(),
              });
            }
          }
          this.claims = Array.from(bySentence.entries())
            .map(([sentenceIndex, v]) => ({
              sentenceIndex,
              sentenceText: v.text,
              ...(scorer !== null ? { scorer } : {}),
              verifiedScore: v.verifiedScore,
              lexicalScore: v.lexicalScore,
              verifiedRefs: Array.from(v.verifiedRefs),
              lexicalRefs: Array.from(v.lexicalRefs),
            }))
            .sort((a, b) => a.sentenceIndex - b.sentenceIndex);
          // Propagate to the committed thread message if streaming has ended. The coverage facts
          // ride along (tempdoc 836 S2S3-A.6f): the committed message is what renderMessage reads,
          // and it must hold the same facts the reloaded record supplies.
          if (!this.isStreaming) {
            const last = this.thread.at(-1);
            if (last?.role === 'assistant') {
              last.claims = [...this.claims];
              last.citations = [...this.citations];
              last.coverage = this.coverage;
              last.sourceCoverage = [...this.sourceCoverage];
              this.thread = [...this.thread];
            }
          }
        }
      },
    };

    try {
      await consumeShapeStream(
        url,
        body,
        (event, payload) => {
          dispatchShapeEventToHandlers(handlers, event, payload);
        },
        this.abortController.signal,
      );
    } catch (err) {
      const status = err instanceof Error ? (err as Error & { status?: number }).status : undefined;
      if (status === 423) {
        this.noteRefusedWhileLocked(text);
      } else if (!(err instanceof Error && err.name === 'AbortError')) {
        this.errorMessage = friendlyStreamError(err);
      }
    } finally {
      this.isStreaming = false;
      this.abortController = null;
    }
  }

  /**
   * Tempdoc 734 round-14 F4 — the store locked between this composer's render and the submit (an
   * idle/auto-lock, another window, another tab), so dispatch refused with 423 instead of accepting a
   * turn no store would hold. Three things follow, and all three are required for the surface to stay
   * honest: adopt the locked state the server just reported (which renders the notice + its "Unlock in
   * Security" affordance and disables Send), take back the optimistic user bubble — a message shown as
   * sent that was never recorded is the same lie in the UI as the 200 was on the wire — and put the
   * text back in the composer, because it is the user's and nothing else is holding it.
   */
  private noteRefusedWhileLocked(text: string): void {
    this.historyLocked = true;
    this.lockedSendNotice = `${reasonFor('conversations.locked').wording} — your message was not sent.`;
    const last = this.thread.at(-1);
    if (last?.role === 'user' && last.content === text) {
      this.thread = this.thread.slice(0, -1);
    }
    if (!this.inputDraft.trim()) this.inputDraft = text;
  }
}

if (
  typeof customElements !== 'undefined' &&
  !customElements.get('jf-unified-chat-view')
) {
  customElements.define('jf-unified-chat-view', UnifiedChatView);
}

// Tempdoc 561 surface tier: the one window is the canonical view for EVERY direct-LLM interaction
// shape. Every deeplink / resume path resolves here (with the shape-id presetting the mode),
// instead of mounting a separate per-shape view — and check-shape-view-coverage stays green.
// These literal registrations are the per-shape coverage the shape-view-coverage gate greps for;
// the interaction-surface gate cross-checks that every CORE_INTERACTION_SHAPE registers to
// ONE_WINDOW_MOUNT_TAG here and to no other tag (no second interaction view).
registerViewFactory('core.rag-ask', ONE_WINDOW_MOUNT_TAG);
registerViewFactory('core.free-chat', ONE_WINDOW_MOUNT_TAG);
registerViewFactory('core.extract', ONE_WINDOW_MOUNT_TAG);
registerViewFactory('core.agent-run', ONE_WINDOW_MOUNT_TAG);
// Tempdoc 565 §15.C — the workflow run is a MODE of the one window, not a bespoke surface; the
// retired WorkflowSurface/WorkflowView fork registered 'jf-workflow-surface' here.
registerViewFactory('core.workflow-run', ONE_WINDOW_MOUNT_TAG);
// Load-time exhaustiveness guard: every interaction mode in the FE mirror of the Java authority
// must have registered the one-window view above (and to no other tag). A mode added to
// CORE_INTERACTION_SHAPES without a registration line throws here at import — the runtime
// counterpart of the interaction-surface gate, and a real consumer of the mirror value.
for (const shape of CORE_INTERACTION_SHAPES) {
  if (getViewFactory(shape) === undefined) {
    throw new Error(
      `[one-window] interaction mode '${shape}' is declared in CORE_INTERACTION_SHAPES but has ` +
        `no registered view factory — register it to ${ONE_WINDOW_MOUNT_TAG}.`,
    );
  }
}
