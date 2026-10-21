// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 621 Phase 2 — the chat window's request/message prologue, extracted from UnifiedChatView.ts so
 * the host file reads as orchestration, not declarations. Pure (no element/runtime dependencies):
 *  - {@link ThreadMessage} — the FE-maintained live thread message (Phase 4 will also project it into the
 *    unified turn model).
 *  - {@link buildRequestBody} — the per-shape POST body the composer dispatches.
 *  - {@link CONVERSATION_ZONES} — the declared multi-zone composition the host feeds `composeGridStyles`.
 */
import { type ZoneDecl } from '../primitives/compositionLayout.js';
import type { SelectionPayload } from '../../api/types/selection.js';
import type { CitationMatch, RetrievalCitation } from '../components/chat/CitationsPanel.js';
import type { Claim, SourceCoverage } from '../components/chat/citationTypes.js';
import type { CoverageHonesty } from '../components/chat/evidenceProjection.js';
import type { RagMetaPayload } from '../../api/streams.js';
import type { CoreInteractionShapeId } from '../plugin-api/coreInteractionShapes.js';

// The interaction "modes" of the one window — the FE mirror of the Java authority
// (CoreConversationShapeCatalog.CORE_USER_INTERACTION_SHAPES), kept in sync by the
// interaction-surface discipline gate.
export type ShapeId = CoreInteractionShapeId;

// Tempdoc 610 Phase A — reserved branch-point marking an empty-prefix branch (inherit
// nothing). Used to edit/retry the FIRST turn, where there is no preceding message.
// MUST match ConversationStore.EMPTY_PREFIX_SENTINEL (Java) — same cross-language
// coupling as THROWAWAY_SESSION_PREFIX.
export const EMPTY_PREFIX_SENTINEL = '__empty_prefix__';

export interface ThreadMessage {
  role: 'user' | 'assistant';
  content: string;
  shapeId: ShapeId;
  citations?: CitationMatch[];
  sources?: RetrievalCitation[];
  claims?: Claim[];
  /**
   * Tempdoc 836 S2S3-A.2 — the turn's coverage facts (which axes of verification actually ran).
   * Carried on the message so the LIVE render and the RELOADED render read the same facts; absent
   * for a producer that reported none.
   */
  coverage?: CoverageHonesty | null;
  /** Tempdoc 836 S2S3-A.3 — per-source examination facts, for the sources panel's third state. */
  sourceCoverage?: SourceCoverage[];
  ragMeta?: RagMetaPayload;
  isExtract?: boolean;
  // Tempdoc 603 C2 — the decontextualized standalone question this follow-up's retrieval ran on
  // (from the `rag.rewrite` SSE event). Persisted per-turn so the "Interpreted as: …" transparency
  // line survives turn completion + restored history, not just the live stream.
  standaloneQuestion?: string;
  // The reader stopped this turn (the record's `CANCELLED` disposition, written by the Engine for a
  // Stop): it says so, and an answer cut off before any text is not shown as an empty answer.
  stopped?: boolean;
  // Slice 513 — stable id from backend (real UUID for post-513 writes,
  // synthetic idx-N for legacy). Required for "Branch here" affordance.
  id?: string;
  // Slice 513 — true for messages inherited from a parent conversation
  // when this session is itself a branch. Render with a subtle indicator
  // and suppress the "Branch here" button (you can only branch from your
  // own session's messages, not a re-resolved prefix).
  inheritedFromParent?: boolean;
  // Search Thread S7 (tempdoc decision 6) — the completed turn's wall-clock duration, captured at
  // `send()`'s onDone from `aiState.activity.startedAtMs` (never persisted server-side, so a
  // reloaded/record turn simply lacks it — the receipt line omits duration rather than fabricate it).
  durationMs?: number;
  /**
   * Tempdoc 848 §2.1/§2.6 — the turn's thinking, as the ordered blocks the run produced. Persisted
   * server-side (`ConversationEngine.persistedAssistant`) and lifted onto the thread record, so a
   * settled or reloaded turn renders the same blocks the stream showed. The element shape is
   * declared STRUCTURALLY here (its source is `controllers/ReasoningController.ts`'s
   * `ReasoningBlock`) because this module states it is pure — no element/runtime dependencies — and
   * the controller is a runtime class module.
   */
  reasoning?: { readonly text: string; readonly durationMs: number }[];
}

export const SHAPE_LABELS: Record<ShapeId, string> = {
  'core.free-chat': 'Chat',
  'core.rag-ask': 'Document Q&A',
  'core.extract': 'Extraction',
  'core.agent-run': 'Tools',
  // Tempdoc 565 §15.C — the workflow run is a mode of the one window.
  'core.workflow-run': 'Workflow',
};

export function buildRequestBody(
  shapeId: ShapeId,
  text: string,
  sessionId: string,
  schemaDraft: string,
  docIds: string[],
  selection?: SelectionPayload | null,
): Record<string, unknown> {
  const body: Record<string, unknown> = { shapeId };
  switch (shapeId) {
    case 'core.rag-ask':
      body.question = text;
      // Slice 515 FIX-1: forward docIds from unifiedChatState so scoped
      // retrieval (askAi from BrowseSurface/SearchSurface) actually filters
      // to the referenced docs instead of silently degrading to open-retrieval.
      // Empty array preserves the open-retrieval fallback in RAGAskShape.
      body.docIds = docIds;
      break;
    case 'core.extract':
      body.prompt = text;
      body.schema = schemaDraft;
      break;
    case 'core.agent-run':
    case 'core.free-chat':
    default:
      body.prompt = text;
      body.sessionId = sessionId;
      break;
  }
  // Tempdoc 526 §12.4 — forward the typed selection as body.selection so the backend
  // SelectionContextInjector receives it verbatim. Applies to every shape today; shapes that
  // don't read body.selection simply ignore the field. A shape opts in by listing
  // `SelectionContextInjector.ID` in its `contextInjectorIds` — SummarizeShape and ExtractShape
  // do. (There is no `selectionPolicy` concept anywhere in the Java code; the comment that
  // claimed one described an opt-in mechanism that never existed.)
  if (selection) {
    body.selection = selection;
  }
  return body;
}

/**
 * Spec A step 9 (reconciliation F8/CH1) — marks a dispatch body as a person's chat: `allowLoad: true`
 * lets the Engine load an unloaded chat model for this turn (or offer "load anyway" under pressure)
 * instead of refusing it as an agent's request. Only the chat window's own send path calls this,
 * for `/api/chat/dispatch`; the Engine also requires the app's own client before it believes it.
 */
export function withHumanLoad(body: Record<string, unknown>): Record<string, unknown> {
  body.allowLoad = true;
  return body;
}

/**
 * Tempdoc 565 §13 + §13.8/§13.9 — the agent window's DECLARED multi-zone composition. The grid frame is
 * GENERATED from this by {@link composeGridStyles} (no hand-authored `grid-template-columns`).
 *
 * The reading column (`.conversation`, capped at the ~88ch reading measure) is FLANKED by the spine (a
 * narrow left gutter) and the evidence rail (right). §13.9 corrects two space defects a live audit found:
 * (a) the spine + rail tracks are now CONTENT-SIZED (`auto` / `fit-content`) so an UNMOUNTED zone truly
 * COLLAPSES to 0 — a `minmax(0, max)` track does NOT collapse (it claims free space up to its max; the
 * empty rail's old `minmax(15rem,20rem)` reserved ~320px in Documents mode and shoved the column ~280px
 * left). (b) the outer margins are CAPPED (`minmax(0, 8rem)`) rather than unbounded `1fr`, so the group
 * sits fuller-width with bounded gutter instead of stranded in centre-margin on wide screens. The reading
 * measure is zone CONTENTS (on the answer block), not the frame — outside the generator (the 559 §8 cut).
 */
// Search Thread S6 (the Reading Stage) — a FIFTH zone, `.document-pane` (col 5), for the
// `<jf-document-pane>` reading surface (opened from a search-result/citation "open"). Wide viewport
// reads [conversation | evidence-rail | document-pane] (evidence-rail is usually empty outside agent
// mode, so in practice this is [conversation | document-pane]); the trailing margin track shifts from
// col 5 to col 6. At narrow the zone stays wideOnly (excluded from the narrow track list, mirroring
// `.evidence-rail`'s precedent), but — UNLIKE the evidence rail, which simply disappears below the
// wide breakpoint — the pane keeps rendering: with only ONE explicit narrow column (`.conversation`)
// and no `grid-column` override, default grid auto-placement puts an unmounted-from-the-wide-track
// `.document-pane` into its own implicit row below `.conversation` (a "stacks" narrow layout, not an
// overlay — `position:fixed` here would trip the layout-purity gate's OverlayHost-only rule for free-
// floating overlays; see unifiedChatStyles.ts).
export const CONVERSATION_ZONES: readonly ZoneDecl[] = [
  { track: 'minmax(0, 8rem)', wideOnly: true },
  { selector: '.run-spine', track: 'auto', col: 2, wideOnly: true },
  // The reading column's floor lives in the TRACK, not in `.conversation`'s own CSS: an explicit
  // `minmax(0, …)` pins the track minimum at 0, which SUPPRESSES the item's automatic minimum
  // contribution — so an item-side `min-width` makes the item overflow its (still-0-floored) track
  // and overlap the rail instead of widening it. Measured at a 1050px viewport with rail +
  // document-pane mounted: `minmax(0, 50rem)` sized the track to 102px (~one word per line) while
  // the rail (240px) and pane (384px) held their own `min-width` floors; adding `min-width: 24rem`
  // to the item left the track at 102px and rendered a 384px item straddling the rail. A `24rem`
  // track floor (the document-pane's own 24-28rem convention) sizes the track to 384px instead.
  // The budget these floors are drawn against, stated in full (798 round 8 — the earlier note here
  // estimated the shortfall at ~15px because it measured against a bare viewport instead of the box
  // the tracks are laid out in; the real figure was 56px). The zone's width is the SURFACE content
  // width — viewport minus the Shell rail (11rem expanded) minus the surface's 1rem padding — so a
  // 1040px window gives the zone 832px, not 1040px. Six tracks always charge five 1.5rem gutters
  // (7.5rem = 120px), even across the collapsed ones. Reading column + reading pane + gutters =
  // 24 + 24 + 7.5 = 55.5rem (888px), which is why the wide layout is now gated on a `@container`
  // query against that 832px box rather than a `@media` query against the 1040px viewport: at the
  // 64rem (1024px) container the two floors fit with 136px of slack instead of overflowing by 56px.
  // KNOWN RESIDUAL, unchanged by this: in agent mode the evidence rail's own 15rem floor makes it
  // 24 + 15 + 24 + 7.5 = 70.5rem (1128px), so a container between 64rem and 70.5rem with all three
  // zones mounted still overflows (by up to 104px). Closing that needs a mount threshold of its own
  // for whichever zone yields — a separate decision, not a smaller number here.
  { selector: '.conversation', track: 'minmax(24rem, 50rem)', col: 3 },
  { selector: '.evidence-rail', track: 'fit-content(20rem)', col: 4, wideOnly: true },
  { selector: '.document-pane', track: 'fit-content(28rem)', col: 5, wideOnly: true },
  { track: 'minmax(0, 8rem)', wideOnly: true },
];
