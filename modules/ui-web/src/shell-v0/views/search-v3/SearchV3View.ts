// SPDX-License-Identifier: Apache-2.0
/**
 * SearchV3View — the Search v3 window host (tempdoc 822 slices 1 and 3; wired in Phase A1).
 *
 * Derived from a third-party design system (MIT) — see THIRD-PARTY-NOTICES.md in this directory.
 *
 * A from-scratch window rebuilt on a new design system: no presentation code is carried over
 * from `UnifiedChatView` — and no search client either, in the other direction: the
 * store this host subscribes to is the SHARED `state/searchState.ts` the shipped window reads,
 * which is what "from-scratch components, shared authorities" means in practice. This host owns six
 * things and delegates the rest:
 *
 *  1. **The token sheet.** `sv3Tokens` is applied HERE, on the window host — never on `:root`. Custom
 *     properties inherit down through every nested shadow root, so one host-scoped declaration
 *     reaches the whole window while the shipped app's palette stays untouched. WHICH of its two
 *     sets is live is the app's decision, not this window's: the `theme` attribute mirrors the app's
 *     appearance authority (852 S4, ledger row 14) and the sheet's light block keys off it.
 *  2. **The window grid.** A fixed `--sidebar-width` panel that does not flex, beside a main column
 *     of topbar → content surface → composer band.
 *  3. **The scroll policy.** The window region never scrolls: this host and the main column are
 *     clipped, and the ONE scroller is the content surface's inner scroller. Chrome therefore
 *     cannot be scrolled out of reach, and there is no scroller nested inside another.
 *  4. **The composer state, and the morph between its two forms** (slice 3). The state lives here
 *     rather than in the composer because it is a WINDOW layout: hero means the composer owns the
 *     content region and there are no results; docked means the results do. Three ways in, all
 *     through the same morph: the send control, `Escape` in the field, and the `composer-state`
 *     attribute (a dev-only handle for live measurement, which is why an external write is routed
 *     through the morph rather than applied straight).
 *  5. **Ask's rendered projection.** `Sv3AskDomain` opens and settles the turns and retains the
 *     conversation's stream and Stop handles; `sv3-ask.ts` remains its one issuance site. The host
 *     supplies composer routing, session transforms, activity and presentation.
 *  6. **The session list, as a PROJECTION of the product's record** (Phase A2; conversations since
 *     F1; on the record since F6). A session IS a conversation: its identity and its listing come
 *     from `state/conversationListStore.ts`, and its transcript from the canonical
 *     `GET /api/thread/{id}` record via `sv3-record.ts`. The host holds the projected list and routes
 *     record and stream transforms through the funnels above. `Sv3SessionsDomain` owns roster,
 *     selection, navigation and row actions through that same projection; the sidebar and content
 *     render it and issue nothing. What is still window-local — the pin and the unread bit — and
 *     why, is stated in `sv3-sessions.ts`'s persistence-boundary note.
 *
 * Mounted as the main USER/RAIL Search surface, keeping its established address:
 * `#justsearch://surface/core.search-v3-surface`.
 *
 * Side-effect registers <jf-sv3-window> and its four regions.
 */
import { Sv3SessionsDomain, type Sv3RecentItem } from './sv3-sessions-domain.js';
import { Sv3SessionsController } from './sv3-sessions-controller.js';
import { Sv3RunDomain } from './sv3-run-domain.js';
import { Sv3RunController } from './sv3-run-controller.js';
import { Sv3ContextDomain } from './sv3-context-domain.js';
import { Sv3ContextController } from './sv3-context-controller.js';
import { Sv3DraftDomain } from './sv3-draft-domain.js';
import { Sv3DraftController } from './sv3-draft-controller.js';
import { Sv3AskDomain } from './sv3-ask-domain.js';
import { Sv3AskController } from './sv3-ask-controller.js';
import { Sv3BranchDomain } from './sv3-branch-domain.js';
import { Sv3BranchController } from './sv3-branch-controller.js';
import { Sv3ComposerPort } from './Sv3ComposerPort.js';
import { flushPendingNavigationWrite, refreshProjection } from '../../router/URLProjector.js';
import { registerViewFactory, getViewFactory } from '../../router/viewFactoryRegistry.js';
import { CORE_INTERACTION_SHAPES, ONE_WINDOW_MOUNT_TAG } from '../../plugin-api/coreInteractionShapes.js';
import { subscribeInspector, setOpen as setInspectorOpen } from '../../state/inspectorState.js';
import type { DocumentLineRange } from '../../components/documentPane/DocumentPane.js';
import { peekSearchV3Handoff, takeSearchV3Handoff } from '../../utils/compose.js';
import { html, css, nothing, type TemplateResult } from 'lit';
import { keyed } from 'lit/directives/keyed.js';
import { JfElement } from '../../primitives/JfElement.js';
import { FrameLatch } from '../../primitives/frameLatch.js';
import { sv3Tokens } from './sv3-tokens.css.js';
import { sv3Shared } from './sv3-shared-styles.js';
import { COMPOSER_STATE_DEFAULT, WINDOW_TITLE, type Sv3ComposerState } from './fixtures.js';
import { resetSearchState, setSearchApiBase } from '../../state/searchState.js';
import { clearFilters, getFilters, hasActiveFilter } from '../../state/searchFiltersState.js';
import {
  adoptSv3MorphSheet,
  releaseSv3MorphSheet,
  runSv3ComposerMorph,
} from './sv3-composer-morph.js';
import {
  type Sv3Composer,
  type Sv3ComposerDraft,
  type Sv3ComposerStateRequest,
  type Sv3ComposerSubmit,
  type Sv3EffortChange,
  type Sv3TierChange,
} from './Sv3Composer.js';
import {
  Sv3SearchController,
  sv3ResendScopeNote,
  sv3ScopeLabel,
} from './sv3-search.js';
import { localizeResourceKey } from '../../../i18n/resourceCatalog.js';
import type { Sv3ResultsFilterRemove } from './Sv3Results.js';
import type { CardSelectionDetail } from '../../components/searchResults/ResultsCard.js';
import {
  type Sv3SessionPin,
  type Sv3SessionRemove,
  type Sv3SessionRename,
  type Sv3SessionSelect,
} from './Sv3Sidebar.js';
import {
  clampSv3PaneWidth,
  clampSv3SidebarWidth,
  forgetSv3PaneWidth,
  forgetSv3SidebarWidth,
  readStoredSv3PaneWidth,
  readStoredSv3SidebarCollapsed,
  readStoredSv3SidebarWidth,
  resolveInitialSv3PaneWidth,
  resolveInitialSv3SidebarWidth,
  storeSv3PaneWidth,
  storeSv3SidebarCollapsed,
  storeSv3SidebarWidth,
  sv3PaneIsInline,
  sv3PaneOccupiedWidth,
  SV3_GRIP_KEY_STEP_PX,
  SV3_MAIN_MIN_PX,
  SV3_PANE_DEFAULT_PX,
  SV3_SIDEBAR_COLLAPSED_PX,
  SV3_SIDEBAR_DEFAULT_PX,
} from './sv3-boundaries.js';
import {
  activeTurns,
  adoptRunSession,
  appendTurnDelta,
  applySv3History,
  applySv3Record,
  mergeStoreConversations,
  latestTurnRef,
  projectSv3Sessions,
  sessionById,
  setSessionContextUsage,
  dropTurn,
  setTurnEvidence,
  setTurnReasoning,
  setTurnRewrite,
  settleAgentTurn,
  settleTurn,
  startNewSession,
  submitInSession,
  SV3_SESSIONS_EMPTY,
  type Sv3RunGate,
  type Sv3Session,
  type Sv3SessionList,
} from './sv3-sessions.js';
// The effective-context set (tempdoc 610, ported by 852 S2) — the pure half. The WRITES are the five
// shared store functions below; this module decides what each turn's frame and menu are, and what
// the shared inspector renders.
import {
  sv3ContextMenuItems,
  sv3ExcludedTurnCount,
  sv3FloorUnseen,
  sv3UnseenExcludedIds,
  type Sv3ContextAction,
  type Sv3ContextActionId,
  type Sv3ContextMenuRequest,
} from './sv3-context.js';
// Branch / edit / retry and the version pager (slice 513 + 610 Phase A/B, ported by 852 S3) — the
// pure half. All three are ONE backend act (`branchConversation`); this module decides which message
// id each of them names, and which conversations are the versions of a turn.
import {
  isSv3BranchActionId,
  sv3BranchMenuItems,
  type Sv3BranchAction,
  type Sv3VersionSelect,
} from './sv3-branch.js';
// The window's honesty derivations (tempdoc 822 Phase F7): the lock, the corpus, and the ONE remedy
// channel every region's fix-it control leaves through.
import {
  deriveSv3HistoryLocked,
  sv3ExplainsRankingTrace,
  projectSv3Corpus,
  type Sv3RemedyDetail,
} from './sv3-honesty.js';
// The product's ONE reasoning model (inventory C9). The window holds it for the turn that is
// streaming and hands the finalized blocks to that turn at its terminal; nothing here parses a
// thinking payload.
import { ReasoningController } from '../../controllers/ReasoningController.js';
// Tempdoc 596 §11.4 — the shared remedy navigation, reached from exactly one place in this window.
import { requestSurfaceNavigation } from '../../controllers/navigateRequest.js';
import { projectSv3RecordTurns } from './sv3-record.js';
import {
  SV3_ASK_SHAPE_ID,
  SV3_EFFORT_DEFAULT,
  SV3_MODEL_SHAPE_ID,
  type Sv3Effort,
} from './sv3-ask.js';
// The app-wide CONVERSATION authority (tempdoc 510 Design D; inventory A1). A v3 session IS a
// conversation, so identity, existence, title and its markdown export come from here — this window
// mints none of them.
import {
  rememberServerConversation,
  branchConversation,
  clearContextFloor,
  compactContext,
  createConversationId,
  deleteConversationWithCascade,
  editContextFloorSummary,
  generateConversationTitle,
  getConversationListState,
  loadConversations,
  loadForkConversations,
  restoreActiveConversation,
  resumeConversation,
  setActiveConversation,
  setContextFloor,
  setConversationApiBase,
  setConversationTitle,
  setMessageExcluded,
  subscribeConversationList,
} from '../../state/conversationListStore.js';
// The product's ONE confirm dialog. Cascade-delete asks a second question — "and its branches?" —
// and asking it in a second dialect would be a second dismiss rule for the same gesture.
// Tempdoc 610 §K — the SHELL-mounted context inspector. The window pushes the projection and opens
// the drawer; the drawer is the product's one "what did the assistant actually see" surface and is
// not re-implemented here.
import {
  isContextInspectorOpen,
  setContextInspectorView,
  toggleContextInspector,
} from '../../state/contextInspectorDrawer.js';
// The product's ONE overflow-menu primitive (slice 458). A window-local menu would be a second
// keyboard model and a second dismiss rule for the same gesture.
import { openContextMenu } from '../../components/ContextMenu.js';
// The shared clipboard util — the export's destination, the same one the shipped window uses.
import './Sv3ConversationExport.js';
// Tempdoc 857 PR-A's shared "is the reader typing?" pair — read here for the one question the focus
// authority has to answer before it moves a caret: is focus already somewhere the reader put it?
import { deepActiveElement, isTypingTarget } from '../../utils/keyboardHandler.js';
// The canonical thread RECORD (tempdoc 561 P-A; inventory D1) — the shared fetch, already consumed
// by the shipped window. The shared PROJECTOR is reached through 'sv3-record.ts',
// this window's registered run-projection site (governance/run-renderers.v1.json), never from here.
import { fetchUnifiedThread } from '../unifiedThreadClient.js';
// The shared per-raise step (565 run-control seam), so the button's label and the directive it
// dispatches cannot promise different numbers.
// Tempdoc 609 Phase 3 — the ONE per-tab pointer (sessionStorage): a reload restores the thread THIS
// tab was reading, not the globally-most-recent one.
import {
  clearLastViewedConversation,
  readLastViewedConversation,
  setLastViewedConversation,
} from '../../controllers/lastViewedConversation.js';
// Tempdoc 609 §R — the shared reload-durable draft controller (T2.1) and its one-shot leave hint (T1.4).
import { authorizedFetch } from '../../api/authorizedFetch.js';
import { DraftPersistence } from '../../controllers/draftPersistence.js';
import { requestLoadAnyway, retryWording } from '../../state/chatResidency.js';
import { notifyDraftKeptOnce } from '../../controllers/draftKeptHint.js';
// Tempdoc 834 §15.3 — the backend's live-run enumeration, asked on a cold load.
import { discoverLiveAgentRun } from '../../controllers/liveRuns.js';
import {
  getAgentSessionController,
  peekAgentSessionController,
} from '../../state/agentSessionStore.js';
import type { AgentSessionController } from '../../controllers/AgentSessionController.js';
import {
  sv3PrimaryAction,
  SV3_TIER_DEFAULT,
  type Sv3ComposerTier,
  type Sv3RunView,
} from './sv3-run.js';
import { type Sv3CitationOpen, type Sv3RunDecision, type Sv3StopState } from './Sv3Main.js';
import {
  sameCitationHeader,
  type CitationHeader,
} from '../../components/chat/evidenceProjection.js';
import type { DocumentCitationAnchor } from '../../components/documentPane/DocumentPane.js';
import {
  sv3CitationHeader,
  sv3MatchedSentence,
  SV3_SOURCE_INDEX_ABSENT,
} from './sv3-citation-anchor.js';
import { setAiActivity, subscribeAiState, type AiState } from '../../state/aiStateStore.js';
import { selectIndexingProgress } from '../../state/indexingProgress.js';
import '../../components/IndexingControls.js';
import { projectAvailability } from '../../state/availability.js';
import { reasonFor } from '../../state/readinessNotice.js';
import { isAdvancedMode, subscribeUiMode } from '../../state/uiModeState.js';
// Tempdoc 874 — the reader's chat-column width preset, applied onto this host.
import {
  subscribeChatWidth,
  chatWidthMeasure,
  CHAT_WIDTH_VAR,
  type ChatWidth,
} from '../../state/chatWidthState.js';
import {
  getAppearanceMode,
  subscribeAppearanceMode,
  type AppearanceMode,
} from '../../state/themeState.js';
import { confirmConversationTree, conversationDeleteOutcome } from '../../components/chat/conversationDelete.js';
import { emitEphemeralToast } from '../../components/advisory/ephemeralToast.js';
import { publishForgetCompletion, type IndexedForgetCompletion } from '../../../api/domains/forget.js';
import { confirmForget } from '../../components/searchResults/forgetConsent.js';
import type { CardForgetDetail, ResultsCard } from '../../components/searchResults/ResultsCard.js';
import { projectSv3Degradation, type Sv3Degradation } from './sv3-degradation.js';
import {
  PANE_LABEL,
  SV3_COMMAND_SEARCH,
  SV3_DRAFT_KEY,
  SV3_SURFACE_KEY,
} from './fixtures.js';
import { type Sv3PaletteRun } from './Sv3Palette.js';
import type { Sv3Palette } from './Sv3Palette.js';
import './Sv3Topbar.js';
import './Sv3Sidebar.js';
import './Sv3Main.js';
import './Sv3ContextBar.js';
import './Sv3Composer.js';
import './Sv3Palette.js';
// The window's citation-inspection region (tempdoc 822 Phase F8). It mounts the product's ONE reading
// surface by its own side-effect import; this host owns only where it sits and how wide it may be.
import './Sv3Pane.js';

const COMPOSER_STATE_ATTR = 'composer-state';

/** Tempdoc 867 — a stable empty-set identity for when no run controller exists yet. */
const EMPTY_EVIDENCE_PATHS: ReadonlySet<string> = new Set();

/**
 * Tempdoc 859 §B — the occluded band: what the floating dock takes out of the bottom of the
 * transcript scroller's client box. Declared with a non-zero default in `sv3-tokens.css.ts` and
 * overwritten here with the dock's measured height.
 */
const OCCLUSION_VAR = '--sv3-composer-occlusion';

/** The grip names all three of its gestures, because two of them are not discoverable by pointing. */
const SIDEBAR_GRIP_LABEL =
  'Resize the sidebar — arrow keys resize, Home returns to automatic, double-click resets';

/**
 * The palette chord, matched only for events that reach THIS window. The shipped shell binds the same
 * chord globally (`mod+k` → `shell.toggle-palette`), so the scope is the whole contract: a keystroke
 * outside the window must never be seen here.
 */
const isPaletteChord = (event: KeyboardEvent): boolean =>
  (event.ctrlKey || event.metaKey) && !event.altKey && event.key.toLowerCase() === 'k';

const isComposerState = (value: string | null): value is Sv3ComposerState =>
  value === 'hero' || value === 'docked';

/**
 * Is the keystroke coming out of a session row that is BEING RENAMED? The row is asked, not the
 * DOM shape: `renaming` is the row's own public state, so the test cannot drift from the markup the
 * edit happens to use. Open shadow roots put the real origin in the composed path (the same
 * property the palette's invoker lookup relies on), so a capture-phase listener on the host can see
 * an editor three shadow roots down.
 */
const renamingRowInPath = (event: KeyboardEvent): boolean =>
  event.composedPath().some((node) => {
    const el = node as Element & { renaming?: unknown };
    return el.localName === 'jf-sv3-session-row' && el.renaming === true;
  });

/**
 * Is the keystroke coming out of a composer whose control MENU is open (tempdoc 822 Phase F10)? The
 * same question as the rename above, asked of the same kind of state, and answered the same way:
 * the element is asked for its own public flag rather than the DOM being pattern-matched.
 */
const openControlMenuInPath = (event: KeyboardEvent): boolean =>
  event.composedPath().some((node) => {
    const el = node as Element & { effortMenuOpen?: unknown; tierMenuOpen?: unknown };
    if (el.localName !== 'jf-sv3-composer') return false;
    // EITHER control menu (852 S4 added the second). The ladder's rung is "a menu is open in the
    // control row", not "the effort menu is open" — a mode menu that Escape closed by falling
    // through to the window would take the composer back to hero with it.
    return el.effortMenuOpen === true || el.tierMenuOpen === true;
  });

export class SearchV3View extends JfElement {
  static styles = [
    sv3Tokens,
    sv3Shared,
    css`
      :host {
        display: flex;
        height: 100%;
        min-height: 0;
        overflow: hidden;
        /* The containing block for the palette overlay, which is why the palette can be window-scoped
           at all: it is absolutely positioned against THIS box and cannot reach the shipped chrome. */
        position: relative;
        background: var(--background);
        color: var(--foreground);
        font-family: var(--font-sans);
        font-size: var(--font-size-sv3-sm);
      }
      .branch-origin {
        display: flex;
        align-items: center;
        padding: var(--space-2) var(--space-4);
        border-bottom: 1px solid var(--toolbar-border);
        color: var(--muted-foreground);
        font-size: var(--font-size-sv3-xs);
      }
      .branch-origin span {
        min-width: 0;
        overflow-wrap: anywhere;
      }
      .branch-origin button {
        margin-inline-start: var(--space-3);
        flex-shrink: 0;
        min-height: 24px;
        padding: var(--space-1) var(--space-2);
        border: 1px solid var(--toolbar-border);
        border-radius: var(--control-radius);
        background: transparent;
        color: var(--foreground);
        font: inherit;
        cursor: pointer;
      }
      .branch-origin button:hover { background: var(--toolbar-control-hover); }
      .branch-origin button:focus-visible {
        outline: 2px solid var(--ring);
        outline-offset: 1px;
      }
      jf-sv3-sidebar {
        flex: 0 0 var(--sidebar-width);
        width: var(--sidebar-width);
        /* The spec's own collapse animation (transition-[width] duration-200
           ease-linear). Suppressed during a drag below, for the spec's reason: an eased width lags
           a pointer that is setting it directly. */
        transition:
          flex-basis var(--duration-sv3-layout) var(--ease-sv3-linear),
          width var(--duration-sv3-layout) var(--ease-sv3-linear);
      }
      :host([sidebar-collapsed]) jf-sv3-sidebar {
        flex-basis: var(--sidebar-width-icon);
        width: var(--sidebar-width-icon);
      }
      :host([resizing]) jf-sv3-sidebar {
        transition: none;
      }
      /* THE GRIP (tempdoc 822 Phase F5). The spec's anatomy exactly: a w-4
         (16px) hit area straddling the boundary (-translate-x-1/2) with a 2px LINE drawn by ::after
         at its centre, invisible until hover. A native button rather than the spec's tabIndex={-1}
         rail, so the keyboard half of the boundary exists at all — the same construction the
         retired search-v2 window used for the same job. */
      button.sidebar-grip {
        position: absolute;
        inset-block: 0;
        left: var(--sidebar-width);
        transform: translateX(-50%);
        inline-size: var(--space-4);
        /* Tempdoc 859 — WCAG 2.2 2.5.8 (Target Size, Minimum): the grip measured 16 CSS px wide
           against the criterion's 24 px floor.
           WHAT ENFORCES THIS (measured audit 2026-09-07 — the original note here credited axe, and
           that was wrong in both directions). The repo's 'ui_measure.py' runs axe WITHOUT the
           'wcag22aa' tag, so it never evaluated target-size at all; and when the tag IS added, the
           16px grip PASSED, because 2.5.8's spacing exception covers an undersized target with
           enough clear space around it. So axe is not the authority for this row either way — the
           geometry assertion in 'sv3-tokens.test.ts' is. The floor is still worth pinning: the
           exception is contingent on neighbouring layout that a later change can remove silently,
           and the criterion's own minimum does not depend on it.
           The TARGET grows; the VISUAL does not. What a reader sees of this control is the 2px line
           '::after' draws at 'left: 50%', which is positioned against the button's centre and is
           therefore unmoved and unresized by a wider box — the box itself is 'background: transparent'
           and paints nothing. Written as a floor rather than by editing 'inline-size' so the spec's
           16px anatomy stays legible as the intent, and as the literal 24px the criterion names
           rather than a spacing token a later scale re-tune could drop back under the floor (the
           '.run-prompt-auto' / ReasoningBlock precedent). The block axis is already full-height via
           'inset-block: 0'. */
        min-inline-size: 24px;
        padding: 0;
        border: 0;
        background: transparent;
        cursor: w-resize;
        /* Tempdoc 859 §B (D11) — one rung ABOVE the floating dock, not level with it. Both grips
           used to sit at --z-sticky, which is the dock's rung; at the same rung DOM order decides,
           and this grip is rendered BEFORE .column while the pane's grip is rendered after — so the
           dock would have painted over this one's bottom band while the pane's grip painted over
           the dock. That asymmetry is not acceptable in either direction. A full-height boundary
           control belongs above a bottom-anchored band, so both grips move to --z-overlay. */
        z-index: var(--z-overlay);
        /* A drag must not be interpreted as a page scroll/pan gesture mid-gesture. */
        touch-action: none;
        transition: left var(--duration-sv3-layout) var(--ease-sv3-linear);
      }
      :host([sidebar-collapsed]) button.sidebar-grip {
        left: var(--sidebar-width-icon);
        cursor: e-resize;
      }
      :host([resizing]) button.sidebar-grip {
        transition: none;
      }
      button.sidebar-grip::after {
        content: '';
        position: absolute;
        inset-block: 0;
        left: 50%;
        inline-size: 2px;
        background: transparent;
        transition: background-color var(--duration-sv3-micro) var(--ease-sv3-enter);
      }
      button.sidebar-grip:hover::after {
        background: var(--sidebar-border);
      }
      /* Focus lights the LINE rather than drawing a ring: an outline around a 16px-wide, full-height
         hit area reads as a second boundary beside the first. The ring colour is used so the
         indicator is unmistakably a focus state and not the hover treatment. */
      button.sidebar-grip:focus-visible {
        outline: none;
      }
      button.sidebar-grip:focus-visible::after {
        background: var(--ring);
      }
      @media (prefers-reduced-motion: reduce) {
        jf-sv3-sidebar,
        button.sidebar-grip,
        button.sidebar-grip::after {
          transition: none;
        }
      }
      .column {
        display: flex;
        flex-direction: column;
        flex: 1 1 auto;
        min-width: 0;
        min-height: 0;
        overflow: hidden;
        /* The containing block for the hero composer, which leaves the flow to centre itself over
           the content region — and, since tempdoc 859 §B, for the DOCKED dock as well. */
        position: relative;
      }

      /* The notice occupies its own row; hero and dock position within the space below it. */
      .indexing-pause-note {
        flex: none;
        padding: var(--space-3) var(--floating-content-inset);
      }
      .indexing-pause-note p { margin: 0 0 var(--space-2); }
      .column jf-sv3-composer[state='hero'] { inset-block-start: 0; }

      /* ── THE DOCK (tempdoc 859 §B) ─────────────────────────────────────────
         The context bar and the composer, in ONE positioned wrapper, floating over the transcript.
         §1-B's finding was that a composer in document flow cuts the transcript off hard at its top
         edge; §3 already rejected "just CSS" because this is a viewport-ownership change. The
         scroller now owns the full column and the dock floats above it, which is the construction
         ':host([state='hero'])' has been shipping all along — extended to 'docked', not invented.

         ONE wrapper, not two floating siblings: the dock's height has to be OBSERVABLE as a single
         box (it is published as --sv3-composer-occlusion), and observing two boxes and summing them
         re-introduces the enumeration this whole change exists to avoid. The context bar rides with
         the composer because it IS composer chrome — leaving it in flow would leave a second
         horizontal cut across the transcript, the exact defect being removed.

         --z-sticky, deliberately BELOW --z-overlay: the hero composer, the overlaid citation pane
         and the palette all share the overlay rung and must stay above a bottom band. .column has
         z-index:auto so it opens no stacking context and the rungs compare globally.

         The positioning is STATE-GATED, and that is load-bearing rather than tidy: the hero
         composer is 'position:absolute' against the nearest positioned ancestor, so a dock that
         were positioned in hero too would silently steal hero's containing block from .column and
         collapse the centred landing into a bottom-anchored strip. In hero the dock is a plain flow
         wrapper and hero resolves against .column exactly as before. */
      .dock {
        /* Click-through except the two centred content columns inside it
           (.band in the composer, .bar in the context bar), so the transcript underneath stays
           wheel-scrollable right up to the glass edge. */
        pointer-events: none;
      }
      /* Recovery has its own bounded row, like indexing/origin notices. It must not
         share the hero's absolute positioning or the transcript's floating dock. */
      .column > .recovery-notice {
        box-sizing: border-box;
        flex: 0 0 auto;
        min-block-size: 0;
        max-block-size: 20%;
        overflow: auto;
        overflow-wrap: anywhere;
        padding: var(--space-3) var(--floating-content-inset);
        border-block-end: 1px solid var(--border);
        background: var(--background);
        user-select: text;
      }
      .column > .recovery-notice p, .column > .recovery-notice pre { margin: 0 0 var(--space-2); }
      .column > .recovery-notice pre { white-space: pre-wrap; overflow-wrap: anywhere; }
      .column > .recovery-notice button {
        max-inline-size: 100%;
        white-space: normal;
        margin: 0 var(--space-1) var(--space-2) 0;
        padding: var(--space-1) var(--space-2);
        border: 1px solid var(--border);
        border-radius: var(--control-radius);
        background: var(--background);
        color: var(--foreground);
        font: inherit;
        cursor: pointer;
      }
      .column > .recovery-notice button:focus-visible { outline: 2px solid var(--ring); outline-offset: 1px; }

      :host([composer-state='docked']) .dock {
        position: absolute;
        inset-inline: 0;
        inset-block-end: 0;
        z-index: var(--z-sticky);
        max-block-size: 100%;
        display: flex;
        flex-direction: column;
      }
      :host([composer-state='docked']) .dock > jf-sv3-composer {
        min-block-size: 0;
      }
      /* THE VEIL (859 §B / D12). Below the glass box there is the composer host's own
         --floating-content-inset of clear space, through which UNBLURRED transcript would slide
         past the band's bottom edge. This strip is painted in the window's own background so
         content simply disappears behind the glass's bottom edge — the design basis's own
         behaviour, and it reads as the window edge rather than as a bar.

         Strictly the strip BELOW the glass box, never behind it: 'backdrop-filter' samples whatever
         is painted behind the element, so a veil under the glass would be what gets blurred and the
         content-glides-beneath effect would vanish into a flat blurred colour.

         The 0/1 pair is intra-component stacking inside the dock's own context, the same z-0/z-1
         idiom the composer's glass uses — NOT a rung of the window's z-scale, which is why it is
         typed rather than read from a token. It is needed because a POSITIONED pseudo-element at
         z-index auto paints above static in-flow siblings whatever the source order, so the veil
         would otherwise cover the composer's own bottom edge and clip its elevation in light mode.
         The chrome takes rung 1, the veil rung 0, and the transcript stays below both. */
      :host([composer-state='docked']) .dock::after {
        content: '';
        position: absolute;
        z-index: 0;
        inset-inline: 0;
        inset-block-end: 0;
        block-size: var(--floating-content-inset);
        background: var(--background);
      }
      :host([composer-state='docked']) .dock > * {
        position: relative;
        z-index: 1;
      }
      /* ── THE CITATION PANE (tempdoc 822 Phase F8) ──────────────────────────
         The mirror image of the sidebar: a fixed track that does not flex, on the other side of the
         main column. No open/close transition, which is the spec's own treatment of the INLINE
         panel (a conditional render — only the narrow SHEET animates, and
         that animation is the pane's own). In the overlay presentation the element takes itself out
         of the flow, so no rule here has to undo the track. */
      /* Guarded on the INLINE presentation, and the guard is load-bearing (found by live
         measurement): a width declared here on the element beats the element's own :host rules —
         outer-tree wins — so an unguarded track width left the overlaid pane 540px wide and anchored
         to the LEFT edge, its own inset: 0 outvoted by a width it could not see. */
      :host(:not([pane-overlay])) jf-sv3-pane {
        flex: 0 0 var(--pane-width);
        width: var(--pane-width);
      }
      /* THE PANE'S GRIP. The sidebar grip's anatomy exactly (a 16px hit area straddling the boundary
         with a 2px line drawn at its centre, invisible until hover), mirrored to the other edge: the
         spec's own right-panel handle is 8px wide with a 1px line,
         and taking the sidebar's numbers instead is the deliberate
         choice — one window may not have two differently-sized grips, and 16px is the accessible one
         of the spec's two. col-resize IS the spec's cursor for this handle. */
      button.pane-grip {
        position: absolute;
        inset-block: 0;
        right: var(--pane-width);
        transform: translateX(50%);
        inline-size: var(--space-4);
        /* 859 — the sidebar grip's 2.5.8 floor, for the sidebar grip's reason (including the note
           there on what does and does not enforce it). "One window may not have two differently-sized
           grips" (above) is exactly why this cannot be fixed on one of them: leaving this at 16px
           would fork the anatomy that comment pins. */
        min-inline-size: 24px;
        padding: 0;
        border: 0;
        background: transparent;
        cursor: col-resize;
        /* 859 §B (D11) — the sidebar grip's rung, for the sidebar grip's reason. */
        z-index: var(--z-overlay);
        touch-action: none;
      }
      button.pane-grip::after {
        content: '';
        position: absolute;
        inset-block: 0;
        left: 50%;
        inline-size: 2px;
        background: transparent;
        transition: background-color var(--duration-sv3-micro) var(--ease-sv3-enter);
      }
      button.pane-grip:hover::after {
        background: var(--sidebar-border);
      }
      button.pane-grip:focus-visible {
        outline: none;
      }
      button.pane-grip:focus-visible::after {
        background: var(--ring);
      }
      @media (prefers-reduced-motion: reduce) {
        button.pane-grip::after {
          transition: none;
        }
      }
      /* Reuse the icon rail when the two regions cannot fit. Its controls are fixed-size icons;
         text zoom must not double the empty rail or the gutters at the expense of the answer. */
      :host([sidebar-narrow]) {
        --sidebar-width-icon: var(--space-12);
        --sidebar-content-inset: var(--space-2);
        --floating-content-inset: var(--space-3);
      }
      /* Reopening the sidebar in a narrow window is a sheet, like the citation pane. The column
         keeps exactly the icon rail's reservation, so opening the sheet never squeezes it. */
      :host([sidebar-narrow]:not([sidebar-collapsed])) jf-sv3-sidebar {
        position: absolute;
        inset-block: 0;
        inset-inline-start: 0;
        max-width: 100%;
        z-index: var(--z-overlay);
      }
      :host([sidebar-narrow]:not([sidebar-collapsed])) .column {
        margin-inline-start: var(--sidebar-width-icon);
      }
      :host([sidebar-narrow]) button.sidebar-grip {
        display: none;
      }
      .sidebar-backdrop {
        position: absolute;
        inset: 0;
        border: 0;
        padding: 0;
        background: var(--dialog-backdrop);
        cursor: default;
        z-index: var(--z-overlay);
      }
    `,
  ];

  static properties = {
    composerState: { type: String, reflect: true, attribute: COMPOSER_STATE_ATTR },
    theme: { type: String, reflect: true },
    apiBase: { type: String, attribute: 'api-base' },
    sessions: { state: true },
    aiSnapshot: { state: true },
    streaming: { state: true },
    askStopping: { state: true },
    askCheckingTurnId: { state: true },
    sidebarWidthPx: { state: true },
    sidebarCollapsed: { type: Boolean, reflect: true, attribute: 'sidebar-collapsed' },
    sidebarNarrow: { type: Boolean, reflect: true, attribute: 'sidebar-narrow' },
    resizing: { type: Boolean, reflect: true },
    shapeId: { type: String, attribute: 'shape-id' },
    paneHighlight: { state: true },
    paneDocPath: { state: true },
    paneCitation: { state: true },
    paneCitationHeader: { state: true },
    paneSource: { state: true },
    paneWidthPx: { state: true },
    paneOverlay: { type: Boolean, reflect: true, attribute: 'pane-overlay' },
    renamingId: { state: true },
    historyLocked: { state: true },
    lockedRefusal: { state: true },
    effort: { state: true },
    tier: { state: true },
    initialNavigationPending: { type: Boolean, attribute: false },
  };

  declare composerState: Sv3ComposerState;
  /**
   * The window's light/dark set (852 S4, ledger row 14). The token sheet has carried a COMPLETE
   * authored light palette behind `:host([theme='light'])` since slice 1 (`sv3-tokens.css.ts:333`)
   * and nothing ever set the attribute, so the window painted its dark set inside a light app — the
   * polarity conflict the 2026-08-19 measured audit recorded as F-06.
   *
   * MIRRORED, NEVER OWNED: the value is the app's own appearance authority's (`themeState`), read at
   * connect and re-read on every change, including an OS flip while the reader has chosen "Follow
   * OS". This window has no theme control of its own and must not grow one — a second writer would
   * be a surface disagreeing with the app about what mode it is in.
   */
  declare theme: AppearanceMode;
  /** Set by the shell on every render of a mounted surface (`chrome/Shell.ts:2945-2949`). */
  declare apiBase: string;
  declare forgetCompletionOwner: object | undefined;
  /** Shell settles cold ingress before this mount may dispose of the legacy draft. */
  declare initialNavigationPending: boolean;
  /**
   * The window's session list — a PROJECTION and a per-render cache of the app-wide conversation
   * store plus the canonical thread record, not an authority of its own (Phase F6; the exact split
   * between what the store owns and what stays window-local is in `sv3-sessions.ts`).
   */
  declare sessions: Sv3SessionList;
  /** The observed-state authority's latest emission; the ONE input to this window's availability. */
  declare aiSnapshot: AiState | null;
  /** A response is in flight. Window-level, not session-level: the composer's slot is one slot. */
  declare streaming: boolean;
  /** Where the reader's Stop on the streaming answer stands, until the Engine ends the turn. */
  declare askStopping: Sv3StopState | null;
  /** The signal belongs to this turn alone and is never saved with a conversation. */
  declare askCheckingTurnId: string | null;
  /**
   * The sidebar's chosen width (tempdoc 822 Phase F5). Kept EXPANDED-only: collapsing renders the
   * icon rail without touching this number, which is what makes "expand restores the width I chose"
   * true by construction rather than by saving and re-applying it.
   */
  declare sidebarWidthPx: number;
  declare sidebarCollapsed: boolean;
  private declare sidebarNarrow: boolean;
  private sidebarSheetInvoker: HTMLElement | null = null;
  /** A drag is in progress — the transitions stand down so the panel tracks the pointer exactly. */
  declare resizing: boolean;
  /**
   * The CITED document the pane is open on, or null for closed (tempdoc 822 Phase F8). The pane is
   * mounted exactly while this is non-null, and this field has exactly ONE writer that sets it —
   * {@link onCitationOpen}. That is the scope guard: no search result, no browse row and no typed
   * path can reach the reading surface from this window, because nothing else assigns here.
   */
  declare shapeId: string | undefined;
  declare paneHighlight: DocumentLineRange | null;
  private sharedInspectorPath: string | null = null;
  private inspectorUnsubscribe: (() => void) | null = null;
  private readonly draftPersistence: DraftPersistence;
  declare paneDocPath: string | null;
  /**
   * The cited passage in CHARACTER coordinates, handed to the reader as its anchor (tempdoc 849 §3).
   * The reader derives its own lines from it; this window converts nothing.
   */
  declare paneCitation: DocumentCitationAnchor | null;
  /**
   * Which turn's which source the open pane is showing, so a claim match that lands AFTER the pane
   * opened can be re-resolved onto it (§4). `rag.citations` arrives at retrieval time and
   * `rag.citation_matches` only once the answer has streamed, so the pane is routinely opened before
   * its matched sentence exists — the upgrade is the common path, not a repair.
   */
  declare paneSource: { readonly turnId: string; readonly sourceIndex: number; readonly sourceUnavailable?: true } | null;
  /**
   * Tempdoc 849 §7 — what the open pane may say about the citation that opened it. Held HERE rather
   * than derived inside the pane for the reason slice 2 recorded: the event carries identity only,
   * and the facts live on this window's own turn records, which the reading pane has no access to
   * and must not be given a second copy of.
   */
  declare paneCitationHeader: CitationHeader | null;
  /** The pane's chosen width. Held whether or not the pane is open, exactly as the sidebar's is. */
  declare paneWidthPx: number;
  /** The pane presents as a window-scoped overlay — the spec's 980px switch, asked of OUR box. */
  declare paneOverlay: boolean;
  /** The session whose title is being edited, or null. */
  declare renamingId: string | null;
  /**
   * The conversation store is encrypted and locked (tempdoc 629; inventory E4/E5). Derived from EVERY
   * observed-state snapshot rather than written once from a 423, which is the half tempdoc 734 had to
   * add later: before it, a lock taken elsewhere — an idle auto-lock, another tab — left the
   * transcript readable forever. The derivation itself is `deriveSv3HistoryLocked`, and it is
   * tri-state, so a snapshot that does not mention the lock changes nothing.
   */
  declare historyLocked: boolean;
  /**
   * A send this window made was refused by that lock (tempdoc 734 round-14 F4). It is what lets the
   * locked view say what became of the message, and it is cleared the moment the lock lifts — a
   * notice about a lock that is gone would be describing a refusal that can no longer happen.
   */
  declare lockedRefusal: boolean;
  /**
   * How much work the next question asks for (tempdoc 822 Phase F10). WINDOW-LOCAL and in-memory by
   * decision, not by omission: there is no shared per-conversation preference seam to hold it —
   * `conversationListStore` carries identity, title and protection, and nothing per-conversation the
   * FE may add to — and minting a `localStorage` key would make an ask-time parameter into a
   * persisted chrome preference like the sidebar width, which is a different kind of thing. So the
   * control describes THIS window's next send, which is exactly what it says.
   */
  get effort(): Sv3Effort { return this.draftDomain.effort; }
  set effort(value: Sv3Effort) { this.draftDomain.effort = value; }
  /**
   * WHERE the next send goes (852 S4) — ask the local model, or delegate the draft to the agent.
   *
   * Held on exactly {@link effort}'s terms and for the same reasons: window-local, in-memory, not a
   * persisted preference, and read at DISPATCH time so an in-flight run is never re-routed. The
   * composer renders it and announces a change; the routing itself is decided here, in
   * `onComposerSubmit`, which is the one place a send becomes a run.
   */
  get tier(): Sv3ComposerTier { return this.draftDomain.tier; }
  set tier(value: Sv3ComposerTier) { this.draftDomain.tier = value; }

  /** Watches the window box so the pane's presentation follows it (Phase F8). */
  private boxObserver: ResizeObserver | null = null;
  /** 859 §B — the dock's own box watcher, and the node it is currently bound to. */
  private dockObserver: ResizeObserver | null = null;
  private observedRecentList: HTMLElement | null = null;
  private observedDock: HTMLElement | null = null;
  private aiUnsubscribe: (() => void) | null = null;
  private uiModeUnsubscribe: (() => void) | null = null;
  private appearanceUnsubscribe: (() => void) | null = null;
  private chatWidthUnsubscribe: (() => void) | null = null;
  private onRecentSelect(event: Event): void {
    this.sessionsDomain.selectRecent((event as CustomEvent<Sv3RecentItem>).detail);
  }

  private recoveryNotice(): TemplateResult | typeof nothing {
    const task = this.draftDomain.humanTask;
    const current = { place: this.draftDomain.humanPlace(), text: this.currentDraft(), preparation: this.draftDomain.currentPreparation() };
    const companions = this.draftDomain.recoveryDrafts.filter(d => this.draftDomain.draftHasInputs(d) && !this.draftDomain.sameDraft(d, current));
    if (!this.draftDomain.departureBlocked && !this.draftPersistence.restoreUnavailable && !this.draftDomain.draftReview && task === null && !this.draftDomain.recoveryError && this.draftDomain.refusedHandoff === null && companions.length === 0 && this.aiSnapshot?.chatUnloadedReason == null) return nothing;
    const eligible = task !== null && this.draftDomain.humanOwned(task) && task.saved.delivery === 'unaccepted' && task.refusal !== null;
    return html`<div class="notice recovery-notice" role="status" data-testid="sv3-residency-notice">
      ${this.draftDomain.recoveryError ? html`<p role="alert">${this.draftDomain.recoveryError}</p>` : nothing}
      ${this.draftDomain.refusedHandoff === null ? nothing : html`<section data-testid="sv3-refused-handoff" aria-label="Incoming task not prepared">
        <p>Incoming task not prepared (${this.draftDomain.refusedHandoff.files} files). Nothing sent.</p>
        <pre>${this.draftDomain.refusedHandoff.text}</pre>
      </section>`}
      ${this.draftDomain.departureBlocked ? html`<p role="alert" data-testid="sv3-departure-notice">The address has moved, but this view did not leave safely. Text and settings are kept here. Use Forward or select this conversation to return before sending; no new task will be issued from this mismatched view.</p>` : nothing}
      ${this.draftPersistence.restoreUnavailable ? html`<p role="alert">A raw draft could not be read or safely retired. Saved text may remain; raw draft saving is unavailable in this view. Keep or copy current text and settings before closing.</p>` : nothing}
      ${this.draftDomain.draftReview ? html`<section data-testid="sv3-draft-review" aria-label="Review restored draft">
        <p><strong>Review restored draft</strong></p>
        <p>Your text is editable. Its previous mode and inputs are not permission to search or send.</p>
        <p>${this.draftDomain.preparationSummary(this.draftDomain.draftProposal)}</p>
        ${this.draftDomain.draftProposal !== null ? html`<p>Use reviewed settings applies this proposal to the text currently in the composer. Requested files are checked by the backend; missing inputs are never replaced with all files.</p>` : nothing}
        <button type="button" data-testid="sv3-draft-review-confirm" ?disabled=${!this.draftDomain.preparationUsable(this.draftDomain.draftProposal)}
          @click=${() => this.draftDomain.reviewDraft(false)}>Use reviewed settings</button>
        <p>${this.draftDomain.preparationSummary({ kind: 'search' })}</p>
        <button type="button" data-testid="sv3-draft-review-search" @click=${() => this.draftDomain.reviewDraft(true)}>Use Search with current filters</button>
        <p>Choose a valid mode, schema and inputs before confirming. Confirmation does not search or send. Extra task details last only for this tab session; your current text uses normal draft saving when storage is available.</p>
      </section>` : nothing}
      ${task === null ? nothing : html`<p>${task.saved.delivery === 'unknown'
        ? 'Delivery unknown—check the saved conversation before making a new submission.'
        : task.saved.delivery === 'unaccepted' ? 'Question not sent.' : 'Review the retained question before a new submission.'}</p>
        <pre>${task.saved.raw || (task.saved.record ? 'Saved question: ' + task.saved.record : '')}</pre>
        <p>${task.refusal?.message ?? ''} ${task.refusal ? retryWording(task.refusal) : ''}</p>
        ${task.saved.needsReselection || task.restored ? html`<p>Review the original mode, schema and files. Reselect any passage before sending. Restored copies never send automatically.</p>` : nothing}
        ${eligible ? html`<button type="button" data-testid="sv3-residency-retry" ?disabled=${this.draftDomain.humanLoad !== null || this.streaming || Date.now() < task.retryAt}
          @click=${() => { void this.draftDomain.continueHumanTask(false); }}>Retry</button>
          ${task.refusal?.forceAllowed === true ? html`<button type="button" data-testid="sv3-residency-force" ?disabled=${this.draftDomain.humanLoad !== null || this.streaming}
            @click=${() => { void this.draftDomain.continueHumanTask(true); }}>Load anyway</button>` : nothing}` : nothing}
        ${this.draftDomain.humanLoad !== null ? html`<button type="button" data-testid="sv3-residency-stop" @click=${() => { this.draftDomain.revokeHumanTask(); this.draftDomain.saveRecovery(); }}>Stop waiting</button>` : nothing}
        <button type="button" data-testid="sv3-residency-discard" ?disabled=${this.streaming}
          @click=${() => this.draftDomain.discardHumanTask()}>Discard retained task; allow a new submission</button>`}
      ${companions.map(draft => html`<p>Recovered draft (${draft.place ?? 'new conversation'}):</p><pre>${draft.text}</pre>
        <p>${this.draftDomain.preparationSummary(draft.preparation ?? null)}</p>
        <button type="button" data-testid="sv3-recovery-review-copy" @click=${() => this.draftDomain.reviewRecoveryDraft(draft)}>Review this draft</button>
        <button type="button" data-testid="sv3-recovery-discard-copy" @click=${() => this.draftDomain.discardRecoveryDraft(draft)}>Discard this extra recovery copy</button>`)}
      ${task !== null || companions.length > 0 ? html`<p>Extra recovery copies last only for this tab session. They are not permanent history and may be lost when the tab or app closes. Your current draft uses normal draft saving when storage is available.</p>` : nothing}
      ${task === null && this.aiSnapshot?.chatUnloadedReason ? html`<p>${reasonFor(this.aiSnapshot.chatUnloadedReason).wording}</p>` : nothing}
    </div>`;
  }

  private readonly composerDraft = new Sv3ComposerPort(() => this.composer);

  private readonly draftDomain: Sv3DraftDomain = new Sv3DraftDomain({
    connected: () => this.isConnected,
    activeConversationId: () => this.sessions.activeId,
    lastTurnShape: () => this.activeSession?.turns.at(-1)?.shapeId,
    session: id => sessionById(this.sessions, id),
    startNewSession: () => { this.sessions = startNewSession(this.sessions); },
    keepUnsentConversation: (id, text, now) => {
      this.sessions = mergeStoreConversations(this.sessions, [
        { id, title: 'Unsent draft', firstUserMessage: text, createdAt: now, lastActiveAt: now },
      ]);
    },
    releaseDraftOwnership: () => this.sessionsDomain.releaseDraftOwnership(),
    abortRemovalConfirmation: () => this.sessionsDomain.abortRemovalConfirmation(),
    clearRenaming: () => { this.renamingId = null; },
    clearRecordNotice: () => { this.contextDomain.recordNotice = false; },
    detachRun: () => this.runDomain.detach(),
    dismissPalette: () => this.palette?.dismiss(),
    focusIncomingComposer: () => {
      void this.updateComplete.then(async () => { const c = this.composer; if (c !== null) { await c.updateComplete; c.focusField(); } });
    },
    searchFilterLabels: () => this.search.view({ kind: 'available' }).filters.map(f => f.label),
    search: () => this.search,
    persistence: () => this.draftPersistence,
    context: () => this.contextDomain,
    historyLocked: () => this.historyLocked,
    aiSnapshot: () => this.aiSnapshot,
    streaming: () => this.streaming,
    shapeId: () => this.shapeId,
    initialNavigationPending: () => this.initialNavigationPending,
    writeDraft: text => this.writeDraft(text),
    closePane: () => this.closePane(),
    leaveForgetPlacement: () => this.search.leaveForgetPlacement(),
    restoreLastViewed: () => this.sessionsDomain.restoreLastViewed(),
    revealRecoveryOutcome: () => this.revealRecoveryOutcome(),
    focusComposer: () => this.focusComposer(),
    abortAsk: () => this.askDomain.abortAsk(),
    setComposerState: state => this.setComposerState(state),
    runAsk: (question, scope, schema, model, options) => this.askDomain.runAsk(question, scope, schema, model, options),
    peekHandoff: peekSearchV3Handoff,
    takeHandoff: takeSearchV3Handoff,
    refreshProjection,
    flushNavigation: flushPendingNavigationWrite,
    clearLastViewed: clearLastViewedConversation,
    readLastViewed: readLastViewedConversation,
    setActiveConversation,
    createConversationId,
    filters: getFilters,
    announce: (message, severity) => emitEphemeralToast({ message, severity }),
    loadAnyway: signal => requestLoadAnyway(this.apiBase,
      (input, init) => authorizedFetch(input, { ...init, signal }, { retry: false })),
    requestUpdate: (name, previous) => this.requestUpdate(name, previous),
  }, this.composerDraft);
  private readonly draftController = new Sv3DraftController(this, this.draftDomain);

  private readonly contextDomain: Sv3ContextDomain = new Sv3ContextDomain({
    activeConversationId: () => this.sessions.activeId,
    session: id => sessionById(this.sessions, id),
    connected: () => this.isConnected,
    applyHistory: (id, history) => { this.sessions = applySv3History(this.sessions, id, history); },
    setContextUsage: (id, usage) => { this.sessions = setSessionContextUsage(this.sessions, id, usage); },
    contextWindow: () => this.aiSnapshot?.runtime.contextWindow ?? null,
    recordReadWait: id => this.runDomain.recordReadWait(id),
    recordArrival: (id, record) => this.runDomain.recordArrival(id, record),
    scheduleRecordRetry: () => this.runDomain.scheduleRecordRetry(),
    clearRecordRetry: () => this.runDomain.clearRecordRetry(),
    retryRecords: () => this.runDomain.retryRecords(),
    noteRefusedWhileLocked: () => this.noteRefusedWhileLocked(),
    revokeHumanTask: () => this.draftDomain.revokeHumanTask(),
    openContextInspector: () => this.openContextInspector(),
    announceWarning: message => emitEphemeralToast({ message, severity: 'warning' }),
    requestUpdate: () => this.requestUpdate(),
  }, {
    fetchRecord: (id, signal, onFailure) => fetchUnifiedThread(this.apiBase, id, signal, onFailure),
    readHistory: id => resumeConversation(id, SV3_ASK_SHAPE_ID, { claim: false }),
    setContextFloor, clearContextFloor, compactContext, editContextFloorSummary, setMessageExcluded,
  });
  private readonly contextController = new Sv3ContextController(this, this.contextDomain);

  private readonly runDomain = new Sv3RunDomain({
    createConversationId: () => createConversationId(),
    activeConversationId: () => this.sessions.activeId,
    showingResults: () => this.search.showing,
    hasPreparedHandoff: () => this.draftDomain.preparedHandoff !== null,
    connected: () => this.isConnected,
    turn: ref => sessionById(this.sessions, ref.sessionId)?.turns.find(turn => turn.id === ref.turnId),
    lastTurn: id => sessionById(this.sessions, id)?.turns.at(-1),
    adoptRun: (title, now, id, turnId) => {
      const { list, ref } = adoptRunSession(this.sessions, title, now, id, turnId);
      this.sessions = list;
      return ref;
    },
    ensureConversation: (id, now) => {
      this.sessions = mergeStoreConversations(this.sessions, [
        { id, title: null, firstUserMessage: '', createdAt: now, lastActiveAt: now },
      ]);
    },
    setEvidence: (ref, evidence) => { this.sessions = setTurnEvidence(this.sessions, ref, evidence); },
    settleRun: (ref, outcome, calls, now, disposition) => {
      this.sessions = settleAgentTurn(this.sessions, ref, outcome, calls, now, '', disposition);
    },
    applyRecord: (id, record) => {
      this.sessions = applySv3Record(this.sessions, id, projectSv3RecordTurns(record.events));
    },
    preserveFailedReceipt: (ref, detail) => {
      this.sessions = { ...this.sessions, sessions: this.sessions.sessions.map(session =>
        session.id !== ref.sessionId ? session : { ...session, turns: session.turns.map(turn =>
          turn.id !== ref.turnId ? turn : { ...turn, status: 'failed', detail }) }) };
    },
    refreshRecord: id => this.contextDomain.refreshRecord(id),
    announceStop: message => emitEphemeralToast({ message }),
    persistSteerDraft: () => this.draftDomain.persistDraft({ place: this.draftDomain.humanPlace(), text: '', preparation: this.draftDomain.currentPreparation() }),
    dockComposer: () => { void this.setComposerState('docked'); },
    focusDecision: () => this.focusRunDecision(),
    requestUpdate: () => this.requestUpdate(),
  }, this.composerDraft, {
    peek: () => peekAgentSessionController(),
    get: () => getAgentSessionController(this.apiBase),
    discover: () => discoverLiveAgentRun(this.apiBase),
  }, {
    now: () => Date.now(),
    setTimeout: (callback, ms) => window.setTimeout(callback, ms),
    clearTimeout: timer => window.clearTimeout(timer),
  });
  private readonly askDomain: Sv3AskDomain = new Sv3AskDomain({
    activeConversationId: () => this.sessions.activeId,
    session: id => sessionById(this.sessions, id),
    connected: () => this.isConnected,
    streaming: () => this.streaming,
    shapeId: () => this.shapeId,
    apiBase: () => this.apiBase,
    draft: () => this.draftDomain,
    context: () => this.contextDomain,
    search: () => this.search,
    submitTurn: (question, now, kind, target, shape, selectionKind) => {
      this.sessions = submitInSession(this.sessions, question, now, kind, target, shape, selectionKind);
      return latestTurnRef(this.sessions);
    },
    dropTurn: ref => { this.sessions = dropTurn(this.sessions, ref); },
    setReasoning: (ref, blocks) => { this.sessions = setTurnReasoning(this.sessions, ref, blocks); },
    settleTurn: (ref, status, now, detail, model) => { this.sessions = settleTurn(this.sessions, ref, status, now, detail, model); },
    appendDelta: (ref, text) => { this.sessions = appendTurnDelta(this.sessions, ref, text); },
    setEvidence: (ref, evidence) => { this.sessions = setTurnEvidence(this.sessions, ref, evidence); },
    setRewrite: (ref, standalone) => { this.sessions = setTurnRewrite(this.sessions, ref, standalone); },
    projectActiveAsk: (streaming, stopping, checkingTurnId) => {
      this.streaming = streaming;
      this.askStopping = stopping;
      this.askCheckingTurnId = checkingTurnId;
    },
    createReasoning: () => new ReasoningController(() => this.requestUpdate()),
    runInFlight: () => this.agentController()?.runInFlight === true,
    modelAnswerUnavailableReason: () => this.modelAnswerUnavailableReason,
    currentModelLabel: () => this.currentModelLabel,
    claimConversation: id => this.sessionsDomain.claimConversation(id),
    setComposerState: state => this.setComposerState(state),
    focusComposer: () => this.focusComposer(),
    writeDraft: text => this.writeDraft(text),
    startActivity: (shapeId, now, stop) => setAiActivity({ state: 'streaming', shapeId, startedAtMs: now, canCancel: true, cancel: stop }),
    settleAiActivity: () => this.settleAiActivity(),
    noteRefusedWhileLocked: () => this.noteRefusedWhileLocked(),
    announce: (message, severity) => emitEphemeralToast({ message, severity }),
    requestUpdate: () => this.requestUpdate(),
  }, { createConversationId, rememberServerConversation, loadConversations, generateConversationTitle });

  /**
   * WP1.1 — Search mode: the shared search store's reader, the result list's chosen rows, and the
   * files a prepared Ask is limited to (`sv3-search.ts`). The window keeps only the navigation.
   */
  private readonly search = new Sv3SearchController({
    addController: controller => this.addController(controller),
    requestUpdate: () => this.requestUpdate(),
    composer: this.composerDraft,
    draft: () => this.draftDomain,
    historyLocked: () => this.historyLocked,
    clearAskFilterError: () => { this.askDomain.askFilterError = ''; },
    recordSearch: query => this.sessionsDomain.recordSearch(query),
    closeRecents: () => this.composer?.closeRecents(),
    focusComposer: () => this.focusComposer(),
    connected: () => this.isConnected,
    apiBase: () => this.apiBase,
    activeConversationId: () => this.sessions.activeId,
    forgetCompletionOwner: () => this.forgetCompletionOwner,
    paneDocPath: () => this.paneDocPath,
    closePane: () => this.closePane(),
    openResult: docPath => this.openPane({ docPath, anchor: null, turnId: '', sourceIndex: SV3_SOURCE_INDEX_ABSENT }),
    announceWarning: message => emitEphemeralToast({ message, severity: 'warning' }),
    confirmForget,
    publishForgetCompletion: detail => publishForgetCompletion(this, detail.apiBase, detail.outcome, detail.owner),
  });

  private readonly sessionsDomain = new Sv3SessionsDomain({
    sessions: () => this.sessions,
    setSessions: sessions => { this.sessions = sessions; },
    draft: () => this.draftDomain,
    context: () => this.contextDomain,
    run: () => this.runDomain,
    search: () => this.search,
    renamingId: () => this.renamingId,
    setRenamingId: id => { this.renamingId = id; },
    connected: () => this.isConnected,
    historyLocked: () => this.historyLocked,
    apiBase: () => this.apiBase,
    runGate: () => this.runGate(),
    closePane: () => this.closePane(),
    setComposerState: state => this.setComposerState(state),
    focusComposer: () => this.focusComposer(),
    revealRecoveryOutcome: () => this.revealRecoveryOutcome(),
    invalidateForkReads: (loading, changed) => this.branchDomain.invalidateForkReads(loading, changed),
    resetProfilePresentation: () => {
      this.composer?.closeRecents();
      resetSearchState();
      clearFilters();
      this.composer?.shadowRoot?.querySelector('jf-sv3-filters')?.resetInputs();
    },
    searchNow: text => this.search.searchNow(text),
    writeDraft: text => this.writeDraft(text),
    confirmTree: confirmConversationTree,
    deletionOutcome: conversationDeleteOutcome,
    announce: (message, severity) => emitEphemeralToast({ message, severity }),
    requestUpdate: () => this.requestUpdate(),
  }, { setConversationApiBase, subscribeConversationList, loadConversations, getConversationListState,
    restoreActiveConversation, setActiveConversation, setConversationTitle, deleteConversationWithCascade,
    readLastViewed: readLastViewedConversation, setLastViewed: setLastViewedConversation,
    clearLastViewed: clearLastViewedConversation, hasFreshHandoff: () => peekSearchV3Handoff()?.origin === 'fresh',
  }, this.composerDraft);
  private readonly sessionsController = new Sv3SessionsController(this, this.sessionsDomain);

  private readonly branchDomain = new Sv3BranchDomain({
    activeSession: () => this.activeSession,
    activeConversationId: () => this.sessions.activeId,
    conversations: () => this.sessionsDomain.conversations,
    draft: () => this.draftDomain,
    ask: () => this.askDomain,
    search: () => this.search,
    sessions: () => this.sessionsDomain,
    connected: () => this.isConnected,
    streaming: () => this.streaming,
    historyLocked: () => this.historyLocked,
    lockedRefusal: () => this.lockedRefusal,
    delegateUnavailableReason: () => this.delegateUnavailableReason,
    runInFlight: () => this.agentController()?.runInFlight === true,
    delegate: (text, turnAction) => this.delegate(text, turnAction),
    announceWarning: message => emitEphemeralToast({ message, severity: 'warning' }),
    requestUpdate: () => this.requestUpdate(),
  }, { branchConversation, loadForkConversations }, { replace: text => this.writeDraft(text) });
  private readonly branchController = new Sv3BranchController(this, this.branchDomain);

  constructor() {
    super();
    new Sv3RunController(this, this.runDomain);
    new Sv3AskController(this, this.askDomain, () => this.sessions);
    this.composerState = COMPOSER_STATE_DEFAULT;
    // Read in the CONSTRUCTOR, not on connect: the attribute is reflected, so a value arriving one
    // render late would paint the dark set for a frame in a light app — the flash the app's own
    // pre-paint script exists to avoid.
    this.theme = getAppearanceMode();
    this.apiBase = '';
    this.initialNavigationPending = false;
    this.sessions = SV3_SESSIONS_EMPTY;
    this.aiSnapshot = null;
    this.streaming = false;
    this.askStopping = null;
    this.askCheckingTurnId = null;
    this.sidebarWidthPx = SV3_SIDEBAR_DEFAULT_PX;
    this.sidebarCollapsed = false;
    this.sidebarNarrow = false;
    this.resizing = false;
    this.paneDocPath = null;
    this.paneHighlight = null;
    this.paneCitation = null;
    this.paneSource = null;
    this.paneCitationHeader = null;
    this.paneWidthPx = SV3_PANE_DEFAULT_PX;
    this.paneOverlay = false;
    this.renamingId = null;
    this.contextDomain.recordNotice = false;
    this.historyLocked = false;
    this.lockedRefusal = false;
    this.effort = SV3_EFFORT_DEFAULT;
    this.tier = SV3_TIER_DEFAULT;
    // Constructed HERE rather than on connect: a Lit controller added before connection still gets
    // its `hostConnected`, and adding it inside `connectedCallback` would add a second one on every
    // re-attach of this retained instance.
    this.draftPersistence = new DraftPersistence(
      this,
      SV3_DRAFT_KEY,
      () => this.currentDraft(),
      (value) => {
        if (value !== '') this.draftDomain.markDraftReview();
        this.writeDraft(value);
        this.requestUpdate();
      },
    );
  }

  /** The draft as it stands, wherever it currently lives — the composer owns it once it exists. */
  private currentDraft(): string {
    return this.composerDraft.read();
  }

  override connectedCallback(): void {
    super.connectedCallback();
    void this.search.loadForgetCapability();
    this.sessionsController.connectDraft();
    this.draftDomain.mountDraftPending = !this.draftDomain.recoveryOpened;
    this.draftDomain.mountHasIncoming = false;
    adoptSv3MorphSheet();
    this.restoreBoundaryPreferences();
    this.observeWindowBox();
    setSearchApiBase(this.apiBase || '');
    this.aiUnsubscribe = subscribeAiState((snapshot) => {
      const reconnected = this.aiSnapshot?.connection.reachable === false && snapshot.connection.reachable;
      this.aiSnapshot = snapshot;
      this.applyLockState(snapshot);
      if (reconnected) this.contextDomain.retryContextOnReconnect();
    });
    // Inventory E3 — the app-wide Simple/Detailed authority. Nothing is COPIED into this element:
    // every render site reads `isAdvancedMode()` live, so the subscription's only job is to ask for
    // a re-render when the reader changes the preference on another surface.
    this.uiModeUnsubscribe = subscribeUiMode(() => this.requestUpdate());
    // Ledger row 14 — the app's light/dark decision, mirrored onto the host so the token sheet's
    // authored light set activates. Re-read on connect as well as subscribed: a retained instance
    // re-attaches into whatever mode the app is in now, not the one it was unmounted in.
    this.theme = getAppearanceMode();
    this.appearanceUnsubscribe = subscribeAppearanceMode((mode) => {
      this.theme = mode;
    });
    // Tempdoc 874 — the reader's chat-column width. `subscribeChatWidth` fires immediately with the
    // current value, so this subscription IS the initial apply as well as the live one.
    this.chatWidthUnsubscribe = subscribeChatWidth((w) => this.applyChatWidth(w));
    // ── The conversation record (tempdoc 822 Phase F6) ─────────────────────────────────────────
    // A session is a conversation, so the app-wide store is where the list comes from. The
    // subscription fires immediately with whatever is already loaded; the fetch refreshes it.
    this.sessionsController.connect();
    window.addEventListener('sv3-handoff', this.draftDomain.receiveHandoff);
    if (!this.draftDomain.receiveHandoff()) {
      if (this.shapeId) this.draftDomain.prepareCompatibilityShape();
    }
    this.draftDomain.settleMountDraft();
    this.draftController.connect();
    this.inspectorUnsubscribe = subscribeInspector(state => {
      if (state.isOpen && state.selected?.path) {
        this.openPane({ docPath: state.selected.path, turnId: '', sourceIndex: SV3_SOURCE_INDEX_ABSENT, anchor: null });
        this.paneSource = null;
        this.paneCitationHeader = null;
        this.sharedInspectorPath = state.selected.path;
        this.paneHighlight = typeof state.selected.highlightStartLine === 'number' && typeof state.selected.highlightEndLine === 'number'
          ? { startLine: state.selected.highlightStartLine, endLine: state.selected.highlightEndLine } : null;
      } else if (this.sharedInspectorPath !== null) this.clearPane();
    });
    // Cold-load reattach, started here and NOT awaited: discovery is a network round-trip now
    // (tempdoc 834 §15.3) and mount must not block on it. The ordering it used to buy — the run
    // already on the controller before the first presence look — is instead carried by the
    // run controller subscription: `attachToRun` notifies, `onAgentUpdate` runs
    // `syncRunPresence` first, and a recovered run reaches the window that way.
    void this.runDomain.reattachLiveRun();
    // The window may be mounting BESIDE a run that is already going (a surface switch, a re-mount).
    // The store notifies on change only, so the first look has to be taken here — see
    // `syncRunPresence` for why an unrepresented live run is this window's problem to state.
    this.runDomain.syncRunPresence();
    // Scoped to the HOST, not to `window`. A host listener is only reached by events whose composed
    // path runs through this window, so a chord pressed anywhere else in the shipped app is invisible
    // here by construction — there is no "is the focus inside?" test to get wrong. Capture phase so
    // the palette's own field cannot swallow the chord before the window sees it.
    this.addEventListener('keydown', this.onHostKeydown, true);
    this.addEventListener('focusout', this.onHostFocusOut);
    // Tempdoc 864 Layer 1(c2), PR B — Ctrl+L / '/' dispatch `jf-focus-composer` on `window`
    // (`chrome/Shell.ts`, `shell.focus-composer`); `UnifiedChatView` is the only other listener
    // (`views/UnifiedChatView.ts` connectedCallback). Routed through the SAME `focusComposer()` as
    // every other entry path below, not a second focus mechanism — its refusal set (renaming,
    // palette open, already typing elsewhere) applies here exactly as it does on mount.
    window.addEventListener('jf-focus-composer', this.onFocusComposerEvent);
    this.contextController.connect();
    // Tempdoc 864 Layer 1(a) — ARRIVING AT THIS WINDOW PUTS THE READER IN THE COMPOSER. Activation is
    // an entry path like the other two ({@link onSessionSelect}, {@link onSessionNew}), and it is the
    // one that covers both a fresh hero and the restored record `restoreLastViewed` just claimed:
    // that runs above, in this same connect, and the focus lands after the render it schedules.
    // Everything above this line runs on EVERY connect — subscriptions and observers have to be
    // re-taken however the element got here. Only the focus move belongs to an entry alone, which is
    // what {@link movedRatherThanEntered} separates out (PR E).
    if (!this.movedRatherThanEntered) void this.focusComposer();
  }

  /**
   * Tempdoc 864 Layer 1(a) — the window's focus authority: after an entry that leaves the reader at a
   * ready composer, the caret is IN the composer. Nothing on this surface used to do this, so `<body>`
   * kept focus under a box that looks primed, every global single-key shortcut stayed armed, and the
   * reader's typing went to whatever control focus was parked on (§2.9/§2.11).
   *
   * TWO waits, because Lit does not await its children: this window's own update guarantees the
   * composer ELEMENT exists, not that its shadow tree holds a field yet — the same second wait
   * `Sv3Sidebar.placeFocusAfterDiscard` takes before reaching into a row.
   *
   * THREE refusals, and their standing differs — stated, because a guard whose reach is assumed is
   * how the next author decides it must be doing something:
   *
   *  - the TYPING guard carries the general case, and is what a reader who has put focus somewhere
   *    themselves is protected by;
   *  - the RENAME refusal is independently reachable and tested: `renamingId` survives an unmount, so
   *    re-entering this window mid-rename is an entry path arriving at a half-finished edit with
   *    focus wherever the unmount left it;
   *  - the PALETTE refusal is belt-and-braces TODAY and kept deliberately. The popup's only focusable
   *    is its own field, and `onHostFocusOut` dismisses the palette as soon as focus falls out of the
   *    window — so "open palette, focus not in a field" is currently unreachable and the typing guard
   *    covers the rest. `Sv3Palette.trapTab` already enumerates buttons and tabindex nodes, so the
   *    first non-field focusable added to that popup makes the state live (§2.9(e)), and the failure
   *    it would cause — a caret yanked out of an open modal — is silent.
   *
   * A row button or `<body>` is not a transient: those are the parked-nowhere states this exists to
   * end, and refusing them would refuse the entire fix.
   */
  private async focusComposer(): Promise<void> {
    await this.updateComplete;
    const composer = this.composer;
    if (composer === null) return;
    await composer.updateComplete;
    if (this.renamingId !== null) return;
    if (this.palette?.open === true) return;
    if (isTypingTarget(deepActiveElement())) return;
    composer.focusField();
  }

  override disconnectedCallback(): void {
    this.search.disconnectForget();
    this.sessionsDomain.disconnectDraft();
    this.draftDomain.retireRefusedHandoff();
    this.draftDomain.revokeHumanTask(true);
    this.draftDomain.saveRecovery();
    this.draftController.disconnect();
    // BEFORE `super`, which is what tears the shadow tree's controllers down: the reassurance has to
    // read the draft while the composer still holds it (tempdoc 609 §R T1.4 — instance retention
    // keeps the draft, and this is what makes that invisible guarantee legible, once per session).
    notifyDraftKeptOnce(SV3_SURFACE_KEY, this.currentDraft().trim().length > 0);
    // Tempdoc 609 Phase 4 (inventory G11) — a torn-down stream is no longer live, so the app-wide
    // activity indicator must not be left claiming this window's work is still going.
    if (this.streaming) this.settleAiActivity();
    super.disconnectedCallback();
    releaseSv3MorphSheet();
    this.inspectorUnsubscribe?.();
    this.inspectorUnsubscribe = null;
    this.sessionsController.disconnect();
    this.branchController.disconnect();
    this.contextDomain.disconnect();
    this.aiUnsubscribe?.();
    this.aiUnsubscribe = null;
    this.uiModeUnsubscribe?.();
    this.uiModeUnsubscribe = null;
    this.appearanceUnsubscribe?.();
    this.appearanceUnsubscribe = null;
    this.chatWidthUnsubscribe?.();
    this.chatWidthUnsubscribe = null;
    // Lifecycle containment: an unmounted window's stream would keep a connection open against the
    // shared channel budget and settle a turn nobody can see.
    this.askDomain.disconnect();
    this.boxObserver?.disconnect();
    this.boxObserver = null;
    this.dockObserver?.disconnect();
    this.dockObserver = null;
    this.observedDock = null;
    this.observedRecentList = null;
    this.removeEventListener('keydown', this.onHostKeydown, true);
    this.removeEventListener('focusout', this.onHostFocusOut);
    window.removeEventListener('jf-focus-composer', this.onFocusComposerEvent);
    window.removeEventListener('sv3-handoff', this.draftDomain.receiveHandoff);
    // Tempdoc 864 PR E — LAST, so a re-parent is recorded however the teardown above went. See
    // {@link movedRatherThanEntered}.
    this.markPossibleMove();
  }

  /**
   * Tempdoc 864 PR E (the residual PR A's review found and left) — A RE-PARENT IS NOT AN ENTRY.
   *
   * `connectedCallback` is this window's entry signal and is right for the two entries that reach it:
   * a first mount, and a return to a RETAINED instance (`Shell.ts`'s append-only surface cache
   * re-attaches the same element). It is wrong for a third thing the platform routes through the same
   * callback — the split-view toggle re-templates the stage (`Shell.ts` `render()`: the surface moves
   * from the single-pane slot into `.split > .pane`), so the very same element is removed and put back
   * with no entry having happened, and the caret jumped into the composer on a LAYOUT gesture the
   * reader made for some other reason.
   *
   * The discriminator is the TASK, not a timer: Lit removes and re-inserts the node inside one
   * synchronous update, so a re-parent's `connectedCallback` runs before any microtask queued by the
   * `disconnectedCallback` that preceded it, while a real re-entry needs a user event and is a later
   * task by construction. The coupling this rests on is lit-html's own: a changed template shape
   * CLEARS the old part's DOM and INSERTS the new instance within one `commit`, both synchronous —
   * if a future lit-html ever deferred the insert past a microtask, this would stop recognising the
   * move and simply focus the composer — failing the other way is harmless, because that is exactly
   * the behaviour this narrows.
   */
  private movedRatherThanEntered = false;

  private markPossibleMove(): void {
    this.movedRatherThanEntered = true;
    queueMicrotask(() => {
      this.movedRatherThanEntered = false;
      this.draftDomain.retireDepartedHandoff();
    });
  }

  /**
   * The window box, watched (Phase F8). The pane's presentation is a fact about how wide the WINDOW
   * is, and the window is resized by things this surface never hears about — the shipped rail
   * expanding, a split changing, the OS window itself. `ResizeObserver` is the repo's own answer to
   * exactly this (`primitives/adaptiveDensity.ts`'s `DensityController`); a box that cannot be
   * observed at all (a DOM without the API) simply keeps the inline presentation, which is the same
   * "an unknown width is not a narrow width" rule the clamps use.
   */
  private observeWindowBox(): void {
    if (typeof ResizeObserver !== 'function') return;
    this.boxObserver = new ResizeObserver(() => {
      this.paneOverlay = !sv3PaneIsInline(this.availableWidth());
      this.reflowSidebar();
      this.composer?.keepRecentSelectionVisible();
    });
    this.boxObserver.observe(this);
  }

  /**
   * Tempdoc 859 §B — bind the dock's box watcher, once per NODE.
   *
   * The same `ResizeObserver`-to-custom-property mechanism as {@link observeWindowBox} above and
   * {@link applySidebarWidth} below, for the same reason: the dock's height is a measured fact
   * nothing else can tell us. It grows with everything 859 §4 names — a multiline draft, the
   * availability notice, the degradation banner, an open tier/effort menu — and it is MEASURED
   * rather than enumerated precisely so no future banner has to be added to a list.
   *
   * No feedback loop: the variable changes the SCROLLER's padding, never the dock's own height.
   */
  private syncDockObserver(): void {
    const dock = this.shadowRoot?.querySelector<HTMLElement>('.dock') ?? null;
    if (dock === null) return;
    if (this.observedDock !== dock) {
      this.dockObserver?.disconnect();
      this.observedDock = dock;
      this.observedRecentList = null;
      if (typeof ResizeObserver === 'function') {
        this.dockObserver = new ResizeObserver(() => this.publishOcclusion());
        this.dockObserver.observe(dock);
      }
      // Also publish before the first ResizeObserver delivery (and in DOM-only tests).
      this.publishOcclusion();
    }
    // Hero's dock has zero height; its inner list can resize without changing that box.
    const list = this.composer?.shadowRoot?.getElementById('sv3-recent-list') ?? null;
    if (list !== this.observedRecentList) {
      if (this.observedRecentList) this.dockObserver?.unobserve(this.observedRecentList);
      this.observedRecentList = list;
      if (list) this.dockObserver?.observe(list);
    }
  }

  /**
   * 859 §B — publish the occluded band, and tell the reading-position authority it moved.
   *
   * Two zeroes with opposite meanings, which is the whole subtlety here:
   *
   *  - In `hero` the composer's box IS the content region, so publishing its height would pad the
   *    scroller by a column height. `0px` is written DELIBERATELY (the hero scroller renders no
   *    transcript anyway).
   *  - A `0` MEASUREMENT in `docked` means not-yet-laid-out, detached or hidden — never "no band".
   *    An inline `setProperty` REPLACES the token sheet's `:host` default rather than flooring it,
   *    so writing that zero would restore exactly the clipping this slice removes.
   *
   * Which is why the docked no-measurement branch is a REMOVE and not a plain skip: a bare skip
   * would leave hero's deliberate `0px` standing across the morph into docked, and on a platform
   * where the dock never measures (no `ResizeObserver`, or a first paint that beats layout) the
   * transcript would be clipped by the whole band with nothing in the code saying so. Clearing the
   * inline value hands the question back to the token sheet's non-zero default, which exists for
   * exactly this case. A previous DOCKED height is kept rather than cleared — a slightly stale
   * measurement of the real dock beats a generic estimate.
   *
   * The remeasure is not optional (859 §B / D5): the `ResizeObserver` inside `NavigationController`
   * watches the SCROLLER, and under the overlay the scroller's box no longer changes when the
   * composer grows — so without this the reading window and the FOCUS ring go stale on exactly the
   * keystroke that changed the band.
   */
  private publishOcclusion(): void {
    this.composer?.keepRecentSelectionVisible();
    const current = this.style.getPropertyValue(OCCLUSION_VAR);
    if (this.composerState !== 'hero') {
      const px = this.observedDock?.getBoundingClientRect().height ?? 0;
      if (!(px > 0)) {
        if (current !== '0px') return;
        this.style.removeProperty(OCCLUSION_VAR);
        this.shadowRoot?.querySelector('jf-sv3-main')?.remeasureReadingWindow();
        return;
      }
      const next = `${Math.round(px)}px`;
      if (current === next) return;
      this.style.setProperty(OCCLUSION_VAR, next);
    } else {
      if (current === '0px') return;
      this.style.setProperty(OCCLUSION_VAR, '0px');
    }
    this.shadowRoot?.querySelector('jf-sv3-main')?.remeasureReadingWindow();
  }

  /**
   * Tempdoc 874 — the reader's chat-width preset, written as an inline custom property on THIS host.
   * The token sheet declares `--measure-prose` on `:host`, so an inline value on the same element
   * wins and inherits into the whole shadow subtree — the transcript, the composer band and the
   * context bar all cap on it, which is why one write moves the whole column.
   */
  private applyChatWidth(width: ChatWidth): void {
    this.style.setProperty(CHAT_WIDTH_VAR, chatWidthMeasure(width));
  }

  /**
   * The shell re-sets `api-base` on a CACHED element rather than reconstructing it, so the base has
   * to follow the attribute and not just the first connect.
   */
  /**
   * The late claim match is resolved BEFORE the render that will show it, not after: writing state
   * from `updated()` schedules a second update for the same frame and Lit's dev build warns about
   * exactly that. The shared reader does its equivalent derivation in `willUpdate` for the same
   * reason.
   */
  protected override willUpdate(changed: Map<string, unknown>): void {
    const previousSessions = changed.get('sessions') as Sv3SessionList | undefined;
    if (previousSessions && previousSessions.activeId !== this.sessions.activeId) this.search.leaveForgetPlacement();
    if (changed.has('sessions')) { this.upgradeOpenPaneAnchor(); }
    this.branchController.update();
    if (changed.has('sidebarWidthPx') || changed.has('paneWidthPx') || changed.has('paneDocPath')) {
      this.reflowSidebar();
    }
    // Here rather than in the store's callback: by now a first connect has also restored the saved
    // draft, which `followSharedQuery` must see before it writes the composer.
    const move = this.search.takeSharedMove();
    if (move !== null) this.draftDomain.followSharedQuery(move.previous);
  }

  protected override updated(changed: Map<string, unknown>): void {
    this.draftDomain.settleMountDraft();
    if (changed.has('apiBase')) {
      setSearchApiBase(this.apiBase || '');
      setConversationApiBase(this.apiBase || '');
      this.search.updateForgetApiBase();
    }
    if (changed.has('sidebarWidthPx')) this.applySidebarWidth(this.sidebarWidthPx);
    if (changed.has('paneWidthPx')) this.applyPaneWidth(this.paneWidthPx);
    // 859 §B — the dock exists only after a render, and the hero↔docked morph changes what the
    // band MEANS, not just how tall it is (see publishOcclusion's two zeroes).
    this.syncDockObserver();
    // Child rendering creates/removes the list after this parent's update.
    void this.composer?.updateComplete.then(() => {
      if (this.isConnected) this.syncDockObserver();
    });
    if (changed.has('composerState')) this.publishOcclusion();
    // The restored draft reaches the composer on the first update that produced one. Once only: a
    // later re-application would clobber what the reader has typed since.
    this.composerDraft.flushSeed();
    // Tempdoc 610 §K — keep the OPEN inspector current. Its subject is "what the last turn sent", so
    // a new turn, a moved floor or a hidden turn changes it; a drawer left showing the previous
    // answer's prompt would be the stalest possible answer to the one question it exists to answer.
    if (isContextInspectorOpen()) setContextInspectorView(this.contextDomain.buildContextInspectorView());
  }

  private revealRecoveryOutcome(): void {
    const warning = this.draftDomain.recoveryError, departure = this.draftDomain.departureBlocked;
    this.requestUpdate();
    void this.updateComplete.then(() => {
      if (!this.isConnected || this.draftDomain.recoveryError !== warning || this.draftDomain.departureBlocked !== departure) return;
      const row = this.shadowRoot?.querySelector<HTMLElement>('[data-testid="sv3-residency-notice"]');
      if (row) row.scrollTop = 0;
    });
  }

  /* ── The effective context (tempdoc 610, ported by 852 S2) ────────────────────────────────── */

  /** The claimed conversation, or null when nothing is claimed. */
  private get activeSession(): Sv3Session | null {
    const id = this.sessions.activeId;
    return id === null ? null : sessionById(this.sessions, id);
  }

  /**
   * The reader asked for a turn's ⋯ menu. The entries come from the SAME pure derivation the region
   * gated its trigger on, so a menu can never offer an act the turn cannot address; an empty list
   * opens nothing at all rather than an empty menu.
   */
  private async onContextMenu(event: Event): Promise<void> {
    const detail = (event as CustomEvent<Sv3ContextMenuRequest>).detail;
    const session = this.activeSession;
    if (detail === undefined || session === null) return;
    const contexts = this.contextDomain.turnContexts();
    const items = sv3ContextMenuItems(contexts, detail.turnId, {
      compacting: this.contextDomain.compacting,
      streaming: this.streaming,
      contextFloor: session.history?.contextFloor ?? null,
      hasSummary: (session.history?.contextFloorSummary ?? null) !== null,
    });
    // ONE menu for both sets (852 S3). They are two derivations because they are gated on different
    // ids — a turn can be excludable and not forkable — but a turn has one ⋯, and a second overflow
    // beside the first would be a second place to look for "what can I do with this turn".
    const branch = sv3BranchMenuItems(this.branchDomain.turnLineage(), detail.turnId, {
      streaming: this.streaming,
    });
    if (items.length === 0 && branch.length === 0) return;
    const turn = session.turns.find(turn => turn.id === detail.turnId);
    const shapeId = turn?.shapeId;
    const retryNote = turn?.kind === 'model' ? 'This answer does not search your files.' : sv3ExplainsRankingTrace(turn ?? null)
      ? (this.askDomain.summaryInput(detail.turnId) !== undefined) ? 'Explain with the original ranking trace.' : 'After reload, start Explain from the result again.'
      : shapeId === 'core.extract' ? this.askDomain.structuredNote(detail.turnId)
      : shapeId === 'core.summarize'
        ? (this.askDomain.summaryInput(detail.turnId) !== undefined) ? 'Summarize with the original inputs.' : 'Opens Summarize; choose the original inputs before sending.'
        : sv3ResendScopeNote(this.search.scopesByTurn.get(detail.turnId), this.search.filtersOfTurn(detail.turnId));
    const chosen = await openContextMenu({
      // The branch acts lead: they fork the conversation, and the context acts change what the next
      // prompt carries within it. The reference window orders them the same way.
      actions: [
        ...branch.map((item) => ({
          id: item.id,
          // WP1.1 S1.7 — a Retry says what it will search before it is pressed: the same chosen
          // files when this window limited the turn, known unrestricted files, or a truthful unknown-input remedy after reload.
          label:
            (item.id === 'retry' || item.id === 'edit')
              ? `${item.label} · ${retryNote}`
              : item.label,
          icon: 'git-branch' as const,
          category: 'ai' as const,
          enabled: item.enabled,
        })),
        ...items.map((item) => ({
          id: item.id,
          label: item.label,
          icon: 'history' as const,
          category: 'ai' as const,
          enabled: item.enabled,
        })),
      ],
      anchor: { x: detail.x, y: detail.y },
    });
    if (chosen === null) return;
    if (isSv3BranchActionId(chosen)) {
      await this.branchDomain.runBranchAction({ action: chosen, turnId: detail.turnId });
      return;
    }
    await this.contextDomain.runContextAction({ action: chosen as Sv3ContextActionId, turnId: detail.turnId });
  }

  private renderBranchOrigin(): TemplateResult | typeof nothing {
    const origin = this.branchDomain.branchOrigin();
    if (origin === null) return nothing;
    const { parent, name } = origin;
    return html`<div class="branch-origin" data-testid="sv3-branch-origin">
      <span>Branched from: ${name}</span>
      <button type="button" aria-label=${`Open original: ${name}`}
        @click=${() => { void this.branchDomain.openBranch(parent); }}>Open original</button>
    </div>`;
  }

  private onBranchAction(event: Event): void {
    const detail = (event as CustomEvent<Sv3BranchAction>).detail;
    if (detail === undefined) return;
    void this.branchDomain.runBranchAction(detail);
  }

  private onVersionSelect(event: Event): void {
    this.branchDomain.selectVersion((event as CustomEvent<Sv3VersionSelect>).detail?.sessionId ?? '');
  }

  /**
   * Tempdoc 610 §K — open the shared inspector on what the last completed turn actually sent. The
   * projection is pushed rather than subscribed to, the same way the shipped window does it, and it
   * is re-pushed on every update while the drawer is open so a new turn does not leave it stale.
   */
  private openContextInspector(): void {
    setContextInspectorView(this.contextDomain.buildContextInspectorView());
    toggleContextInspector();
  }

  /**
   * D3 — the COLD-LOAD half of run recovery. F3 closed the same-instance half (presence adopts a run
   * the shared controller still holds); this closes the half a full page load opens, where the
   * controller singleton is gone with the tab that made it. What survives a page load is the run
   * itself, on the backend, and `GET /api/chat/runs/live` (tempdoc 834 §15.3) is how this window
   * learns of it — it asks, once, and then lets presence synthesise the session exactly as it does
   * for a run it found already running.
   *
   * Conditional on the enumeration ACTUALLY NAMING a live agent run, so the F2 law holds: a window
   * with no live run to recover still constructs no controller and starts no polling by being
   * mounted. That is why the enumeration is awaited HERE in the cold case rather than left to
   * `reattachActiveRunOnLoad` — asking the controller would mean constructing it first, which is
   * the exact side effect the law forbids. When a controller already exists the law is not at
   * stake, so the ask goes straight to it and its own conversation guard decides which run to
   * adopt. In the cold case that guard is vacuous: a controller this window just built has no
   * conversation pinned to it.
   */
  /* ── The lock (tempdoc 822 Phase F7; inventory E4/E5) ─────────────────────────────────────── */

  /**
   * Adopt whatever the observed-state authority says about the conversation store's lock.
   *
   * This is the whole of E5: the lock is not a fact about this window's own sends, it is a fact about
   * the STORE, and it can be taken anywhere — an idle auto-lock, the Security surface, another tab.
   * Reading it from every snapshot is what makes a lock taken elsewhere reach this transcript, at the
   * poll's own ~10s bound. An UNLOCK also clears the refusal, because that notice describes a lock
   * that is gone (tempdoc 734 round-14 F4, the same clear at `views/UnifiedChatView.ts:1047`).
   */
  private applyLockState(snapshot: AiState | null): void {
    const locked = deriveSv3HistoryLocked(this.historyLocked, snapshot);
    if (locked === this.historyLocked) return;
    this.historyLocked = locked;
    this.sessionsDomain.invalidateRecentEpoch();
    if (!locked) {
      this.lockedRefusal = false;
      this.sessionsDomain.refreshUnlockedRecents();
    }
  }

  /**
   * The 423 path's own half. The poll is up to ~10s behind, and a refusal is the SERVER saying the
   * store is locked right now — so the window adopts it immediately rather than leaving the
   * transcript readable until the next poll agrees.
   */
  private noteRefusedWhileLocked(): void {
    this.sessionsDomain.invalidateRecentEpoch();
    this.historyLocked = true;
    this.lockedRefusal = true;
  }

  /** The ONE remedy exit (inventory E1's remedy nav, E10's, and the locked view's). */
  private onRemedy(event: Event): void {
    const target = (event as CustomEvent<Sv3RemedyDetail>).detail?.target ?? '';
    if (target === '') return;
    requestSurfaceNavigation(target);
  }

  /** The app-wide activity indicator, settled (tempdoc 609 Phase 4 / inventory G11). */
  private settleAiActivity(): void {
    setAiActivity({
      state: 'idle',
      shapeId: null,
      startedAtMs: null,
      canCancel: false,
      cancel: null,
    });
  }

  /**
   * `--sidebar-width` is written as an INLINE custom property on the host — the spec's own mechanism,
   * which is why the panel, the grip's
   * position and the collapse animation all read one number instead of three. Inline beats the token
   * sheet's `:host` declaration, so the 16rem default stays the value the window opens at when
   * nothing has been chosen.
   */
  private applySidebarWidth(px: number): void {
    this.style.setProperty('--sidebar-width', `${px}px`);
  }

  /** The pane's half of the same mechanism (Phase F8): one number, read by the track and the grip. */
  private applyPaneWidth(px: number): void {
    this.style.setProperty('--pane-width', `${px}px`);
  }

  /**
   * What the OTHER movable region currently takes out of the shared box. Both clamps need it, and
   * both need the RENDERED width rather than the chosen one: a collapsed sidebar occupies its 48px
   * rail, and an overlaid pane occupies nothing at all.
   */
  private sidebarOccupiedWidth(): number {
    return this.sidebarCollapsed || this.sidebarNarrow
      ? SV3_SIDEBAR_COLLAPSED_PX : this.sidebarWidthPx;
  }

  private paneOccupiedWidth(available: number): number {
    return sv3PaneOccupiedWidth(this.paneDocPath === null ? null : this.paneWidthPx, available);
  }

  /**
   * The box the sidebar and the main region actually share — the spec's `wrapper`, not the viewport.
   *
   * An UNMEASURABLE box (0, i.e. not laid out yet) yields no ceiling rather than a tiny one: an
   * unknown width is not a narrow width, and treating it as one would collapse a remembered
   * preference to the floor as a side effect of the window not having been painted. The FLOOR still
   * applies — `clampSv3SidebarWidth` keeps it on the outside — so nothing illegal gets through.
   */
  private availableWidth(): number {
    const measured = this.getBoundingClientRect().width;
    return measured > 0 ? measured : Number.POSITIVE_INFINITY;
  }

  /**
   * Both boundaries, restored in the order their arithmetic depends on: the sidebar first (the pane
   * is closed on a cold mount, so it occupies nothing), then the pane against the sidebar that
   * restoring just settled.
   */
  private restoreBoundaryPreferences(): void {
    const available = this.availableWidth();
    this.sidebarCollapsed = readStoredSv3SidebarCollapsed();
    this.sidebarWidthPx = resolveInitialSv3SidebarWidth(
      readStoredSv3SidebarWidth(),
      available,
      this.paneOccupiedWidth(available),
    );
    this.applySidebarWidth(this.sidebarWidthPx);
    this.paneWidthPx = resolveInitialSv3PaneWidth(
      readStoredSv3PaneWidth(),
      available,
      this.sidebarOccupiedWidth(),
    );
    this.applyPaneWidth(this.paneWidthPx);
    this.reflowSidebar();
  }

  /** A temporary presentation choice never overwrites the reader's saved preference. Reopening
   *  the sidebar while narrow overlays it; returning to a wide box restores the saved choice. */
  private reflowSidebar(): void {
    const available = this.availableWidth();
    const narrow = available - this.paneOccupiedWidth(available)
      < this.sidebarWidthPx + SV3_MAIN_MIN_PX;
    if (narrow === this.sidebarNarrow) return;
    this.sidebarNarrow = narrow;
    this.sidebarCollapsed = narrow || readStoredSv3SidebarCollapsed();
  }

  /** A chosen width is adopted AND remembered; the two are one act (818 L13). */
  private adoptSidebarWidth(px: number): void {
    this.sidebarWidthPx = px;
    storeSv3SidebarWidth(px);
  }

  private adoptPaneWidth(px: number): void {
    this.paneWidthPx = px;
    storeSv3PaneWidth(px);
  }

  /**
   * L13 / the spec's `resetSidebarWidth`: returning the boundary to
   * automatic FORGETS the remembered width rather than storing the default over it — the reader
   * withdrew a choice, and a window that stored "256" would reopen at 256 even after the default moved.
   */
  private resetSidebarWidth(): void {
    forgetSv3SidebarWidth();
    const available = this.availableWidth();
    this.sidebarWidthPx = resolveInitialSv3SidebarWidth(
      null,
      available,
      this.paneOccupiedWidth(available),
    );
  }

  /** The pane's half of the same withdrawal (818 L13 / the spec's `resetSidebarWidth`). */
  private resetPaneWidth(): void {
    forgetSv3PaneWidth();
    this.paneWidthPx = resolveInitialSv3PaneWidth(
      null,
      this.availableWidth(),
      this.sidebarOccupiedWidth(),
    );
  }

  /**
   * The spec's drag, ported: pointer capture so the gesture survives the
   * pointer outrunning the 16px handle, the width written DIRECTLY during the move (a re-render per
   * frame would re-project every session row), the clamp taken from the box measured AT DRAG TIME,
   * and the chosen width adopted once at the end.
   */
  private onGripPointerDown(event: PointerEvent): void {
    if (event.button !== 0 || this.sidebarCollapsed) return;
    event.preventDefault();
    const grip = event.currentTarget as HTMLElement;
    grip.setPointerCapture?.(event.pointerId);
    const available = this.availableWidth();
    const occupied = this.paneOccupiedWidth(available);
    const startX = event.clientX;
    const startWidth = this.sidebarWidthPx;
    let width = startWidth;
    this.resizing = true;
    const move = (moved: PointerEvent): void => {
      width = clampSv3SidebarWidth(startWidth + (moved.clientX - startX), available, occupied);
      this.applySidebarWidth(width);
    };
    const end = (): void => {
      grip.removeEventListener('pointermove', move);
      grip.removeEventListener('pointerup', end);
      grip.removeEventListener('pointercancel', end);
      this.resizing = false;
      this.adoptSidebarWidth(width);
    };
    grip.addEventListener('pointermove', move);
    grip.addEventListener('pointerup', end);
    grip.addEventListener('pointercancel', end);
  }

  /**
   * The pane's drag, mirrored (Phase F8). Three differences from the sidebar's, all of them the
   * spec's own for THIS boundary:
   * the pane is right-anchored so leftward motion GROWS it (hence the negated delta); the width is
   * written on the animation frame rather than on every move event (`rAF`-coalesced — a pointer
   * emits faster than the compositor paints, and this boundary moves a whole document reader);
   * and a CANCELLED gesture REVERTS to the width the drag started from instead of adopting where
   * the pointer happened to be when the system took the capture away.
   */
  private onPaneGripPointerDown(event: PointerEvent): void {
    if (event.button !== 0) return;
    event.preventDefault();
    const grip = event.currentTarget as HTMLElement;
    grip.setPointerCapture?.(event.pointerId);
    const available = this.availableWidth();
    const sidebar = this.sidebarOccupiedWidth();
    const startX = event.clientX;
    const startWidth = this.paneWidthPx;
    let width = startWidth;
    // Tempdoc 860 §6.4 — the shared latch, so a drag that outlives the page's frames (a background
    // throttle mid-gesture) still lands its last width instead of holding the latch to pointerup.
    const frame = new FrameLatch();
    this.resizing = true;
    const move = (moved: PointerEvent): void => {
      width = clampSv3PaneWidth(startWidth - (moved.clientX - startX), available, sidebar);
      frame.request(() => this.applyPaneWidth(width));
    };
    const stop = (): void => {
      grip.removeEventListener('pointermove', move);
      grip.removeEventListener('pointerup', up);
      grip.removeEventListener('pointercancel', cancel);
      // The gesture's own width is settled by `up`/`cancel` below — a pass still in flight would
      // write the pointer's last position over that verdict.
      frame.cancel();
      this.resizing = false;
    };
    const up = (): void => {
      stop();
      this.adoptPaneWidth(width);
    };
    const cancel = (): void => {
      stop();
      this.applyPaneWidth(startWidth);
    };
    grip.addEventListener('pointermove', move);
    grip.addEventListener('pointerup', up);
    grip.addEventListener('pointercancel', cancel);
  }

  /**
   * The pane grip's keyboard half. `Home` resets and `Escape` does NOT: while the pane is open,
   * Escape belongs to the pane (it closes it), so the grip names Home and double-click as its two
   * ways back to automatic rather than claiming a key the window has already spent.
   */
  private onPaneGripKeydown(event: KeyboardEvent): void {
    if (event.key === 'Home') {
      event.preventDefault();
      this.resetPaneWidth();
      return;
    }
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
    event.preventDefault();
    // Right-anchored: LEFT grows the pane, the same direction the pointer drags it.
    const step = event.key === 'ArrowLeft' ? SV3_GRIP_KEY_STEP_PX : -SV3_GRIP_KEY_STEP_PX;
    this.adoptPaneWidth(
      clampSv3PaneWidth(this.paneWidthPx + step, this.availableWidth(), this.sidebarOccupiedWidth()),
    );
  }

  /**
   * The keyboard half of the SAME boundary — same clamp, same floor, one nudge at a time. The spec
   * has no equivalent (its rail is `tabIndex={-1}`); this is the retired search-v2 window's answer,
   * and the a11y contract's: a boundary a pointer can move must be movable without one.
   */
  private onGripKeydown(event: KeyboardEvent): void {
    if (event.key === 'Home' || event.key === 'Escape') {
      event.preventDefault();
      this.resetSidebarWidth();
      return;
    }
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
    event.preventDefault();
    const step = event.key === 'ArrowRight' ? SV3_GRIP_KEY_STEP_PX : -SV3_GRIP_KEY_STEP_PX;
    const available = this.availableWidth();
    this.adoptSidebarWidth(
      clampSv3SidebarWidth(
        this.sidebarWidthPx + step,
        available,
        this.paneOccupiedWidth(available),
      ),
    );
  }

  /**
   * A click on the grip EXPANDS a collapsed panel and otherwise does nothing — the spec's own rule
   * (the rail toggles exactly when it cannot resize). No drag-suppression
   * latch is needed on this side of it, because a collapsed panel is the only state in which the
   * click does anything and {@link onGripPointerDown} refuses to start a drag there.
   */
  private onGripClick(): void {
    if (this.sidebarCollapsed) this.setSidebarCollapsed(false);
  }

  private setSidebarCollapsed(collapsed: boolean): void {
    this.sidebarCollapsed = collapsed;
    storeSv3SidebarCollapsed(collapsed);
  }

  private onSidebarToggle(event: Event): void {
    this.sidebarSheetInvoker = this.sidebarNarrow && this.sidebarCollapsed
      ? (event.composedPath()[0] ?? null) as HTMLElement | null
      : null;
    this.setSidebarCollapsed(!this.sidebarCollapsed);
  }

  /** Dismissing a temporary sheet is not a new sidebar preference. */
  private async dismissSidebarSheet(): Promise<void> {
    if (!this.sidebarNarrow || this.sidebarCollapsed) return;
    this.sidebarCollapsed = true;
    const invoker = this.sidebarSheetInvoker;
    this.sidebarSheetInvoker = null;
    await this.updateComplete;
    if (invoker?.isConnected) invoker.focus();
  }

  /* ── The citation pane (tempdoc 822 Phase F8) ─────────────────────────────────────────────── */

  /**
   * The citation landing, and the window's ONE writer of {@link paneDocPath}. The event is
   * `Sv3Main`'s own — the shared `citation-select` is stopped at the element that produced it, so the
   * Shell's unguarded host listener never writes this click onto the shared inspector selection and
   * the shipped window's reading pane stays where it was.
   *
   * The pane's width is re-clamped ON OPEN rather than only on drag: the box may have changed since
   * the width was chosen, and the sidebar may have moved since — a remembered 540 beside a sidebar
   * dragged out to 900 would open the pane on top of the main column's 640.
   */
  private onCitationOpen(event: Event): void {
    const detail = (event as CustomEvent<Sv3CitationOpen>).detail;
    if (!detail?.docPath) return;
    this.openPane(detail);
  }

  private openPane(detail: Sv3CitationOpen): void {
    this.sharedInspectorPath = null;
    const available = this.availableWidth();
    this.paneOverlay = !sv3PaneIsInline(available);
    this.paneWidthPx = clampSv3PaneWidth(this.paneWidthPx, available, this.sidebarOccupiedWidth());
    this.applyPaneWidth(this.paneWidthPx);
    this.paneDocPath = detail.docPath;
    this.paneHighlight = null;
    this.paneCitation = detail.anchor ?? null;
    this.paneSource = { turnId: detail.turnId, sourceIndex: detail.sourceIndex,
      ...(detail.sourceUnavailable ? { sourceUnavailable: detail.sourceUnavailable } : {}) };
    this.paneCitationHeader = this.headerFor(detail.turnId, detail.sourceIndex, this.paneCitation);
  }

  /**
   * Tempdoc 849 §7 — the citation header for the turn/source the pane is showing. One helper, two
   * callers, for the same reason `sv3CitationAnchor` has two: the header is resolved when the
   * citation is followed and AGAIN when a late claim match changes what may be said about it, and
   * the two answers must be produced by the same join.
   */
  private headerFor(
    turnId: string,
    sourceIndex: number,
    anchor: DocumentCitationAnchor | null,
  ): CitationHeader | null {
    const turn = activeTurns(this.sessions).find((candidate) => candidate.id === turnId) ?? null;
    return sv3CitationHeader(turn, sourceIndex, anchor);
  }

  /**
   * Tempdoc 849 §4 — the late claim match. A citation followed while the answer is still streaming
   * has a retrieved chunk and no matched sentence, so the pane opens tinting the passage; when
   * `rag.citation_matches` lands, the open pane gains its sentence and lands the strong emphasis.
   *
   * Only the OPEN pane's own source is upgraded, and only from `null` — a match for a different
   * source does not touch it, and a pane that already has its sentence is not re-anchored by a later
   * projection of the same turn. The reader's `armedHighlightKey` guard does the rest: the new,
   * distinct range arms the strong phase exactly once.
   */
  private upgradeOpenPaneAnchor(): void {
    const source = this.paneSource;
    if (source === null || source.sourceIndex === SV3_SOURCE_INDEX_ABSENT) return;
    const anchor = this.paneCitation;

    // The ANCHOR upgrade, which needs an anchor to upgrade and a sentence it does not yet have.
    if (anchor !== null && anchor.sentenceText === null) {
      const turn = activeTurns(this.sessions).find((c) => c.id === source.turnId) ?? null;
      const sentence = sv3MatchedSentence(turn, source.sourceIndex);
      if (sentence !== null) this.paneCitation = { ...anchor, sentenceText: sentence };
    }

    // The HEADER refresh, which needs neither (review LOW-5). The two used to share one early
    // return, so a pane opened on an UNUSABLE span — `citation === null`, exactly the S10 case this
    // slice added — was excluded from the header refresh forever: its grounding line stayed frozen
    // at "Retrieved · not cited" no matter what the matcher later found. A citation with no usable
    // position is still a citation whose source can be grounded, and the header is the only thing
    // that can say so.
    const next = this.headerFor(source.turnId, source.sourceIndex, this.paneCitation);
    // Written only on a real change: this runs on every `sessions` update, i.e. every streamed
    // chunk, and a fresh object each time would re-render the reader continuously for a header
    // whose words never moved.
    if (!sameCitationHeader(this.paneCitationHeader, next)) this.paneCitationHeader = next;
  }

  /** A place change retires its own citation, while a shared inspector belongs to its caller. */
  private closePane(): void {
    if (this.sharedInspectorPath === null) this.clearPane();
  }

  /** Explicit dismissal closes both the visible reader and the shared inspector projection. */
  private dismissPane(): void {
    this.clearPane();
    setInspectorOpen(false);
  }

  private clearPane(): void {
    this.sharedInspectorPath = null;
    this.paneHighlight = null;
    this.paneDocPath = null;
    this.paneCitation = null;
    this.paneSource = null;
    this.paneCitationHeader = null;
  }

  private readonly onHostKeydown = (event: KeyboardEvent): void => {
    // ESCAPE ORDER — rename > pane > sidebar sheet > palette > composer flip. The MOST LOCAL transient state wins:
    // an Escape belongs to the smallest thing the reader is currently inside, and only after that
    // to the regions around it. Stopping the event at the winner is what makes the order true
    // rather than approximately true — unstopped, the SAME keystroke would also hide the palette
    // (`Sv3Palette.onKeydown`) or flip the composer back to its hero (`Sv3Composer.onKeydown`),
    // closing two things at once.
    //
    // Rename is FIRST and is served by yielding rather than by handling: an inline editor owns its
    // own cancel key (`Sv3SessionRow.onRenameKeydown`), and this listener is on the CAPTURE phase,
    // so the row's `stopPropagation` cannot defend it — the window has to decline. Without this,
    // an Escape pressed while typing in the rename field closed the PANE, silently, in a region
    // the reader was not looking at, and left the edit open (F-series fit audit, DEFECT-7).
    if (event.key === 'Escape' && !event.isComposing && (this.composer?.recentsOpen || this.composer?.filtersOpen)
      && event.composedPath().includes(this.composer)) return;
    if (event.key === 'Escape' && renamingRowInPath(event)) return;
    // An open control menu is served the same way and for the same reason: it is the most local
    // transient the reader is inside, it closes itself (`Sv3Composer.onMenuKeydown`), and without
    // this yield the same keystroke would close the PANE behind it (F9's DEFECT-7, one control on).
    if (event.key === 'Escape' && openControlMenuInPath(event)) return;
    if (event.key === 'Escape' && this.paneDocPath !== null) {
      event.preventDefault();
      event.stopPropagation();
      this.dismissPane();
      return;
    }
    if (event.key === 'Escape' && !event.isComposing && this.sidebarNarrow && !this.sidebarCollapsed) {
      event.preventDefault();
      event.stopPropagation();
      void this.dismissSidebarSheet();
      return;
    }
    if (!isPaletteChord(event)) return;
    event.preventDefault();
    event.stopPropagation();
    // The deepest node in the composed path is the real invoker; `document.activeElement` retargets
    // to this host at the shadow boundary and would send focus back to a non-focusable element.
    const invoker = (event.composedPath()[0] ?? null) as HTMLElement | null;
    this.togglePalette(invoker);
  };

  /**
   * The palette may never be left OPEN with the keyboard somewhere else. Its own exits (Escape,
   * backdrop click) both assume focus is still inside it; when something outside the window takes
   * focus away — the shipped shell binds the same Ctrl+K and its palette steals the field
   * (`KeybindingRegistry.ts:178`, the recorded cutover-scope duplicate) — the capture-phase Escape
   * listener above is never reached again and the sv3 palette becomes visible-but-unreachable, a
   * keyboard trap only a pointer could clear (F-series fit audit, DEFECT-8).
   *
   * `relatedTarget` is the test rather than `document.activeElement`, which during `focusout` still
   * reports the OLD node. `null` (focus fell to `<body>`) and any node outside the window close it;
   * focus moving anywhere inside — including into the palette's own field — leaves it open. Closing
   * does NOT reclaim focus: the invoker restore in `hide()` would yank the caret back out of
   * whatever legitimately took it, which is the fight the shipped Ctrl+K already starts.
   */
  private readonly onHostFocusOut = (event: FocusEvent): void => {
    const palette = this.palette;
    if (palette === null || !palette.open) return;
    if (this.ownsNode(event.relatedTarget as Node | null)) return;
    palette.dismiss();
  };

  /**
   * Tempdoc 864 Layer 1(c2), PR B — the `jf-focus-composer` window event (Ctrl+L / '/', dispatched
   * by `shell.focus-composer`) reaches this window. Delegates straight to {@link focusComposer}: no
   * second focus path, so the same three refusals (renaming, palette open, already typing elsewhere)
   * govern an explicit keypress exactly as they govern an entry path.
   */
  private readonly onFocusComposerEvent = (): void => {
    void this.focusComposer();
  };

  /**
   * Shadow-crossing containment. `contains` alone is not enough in either direction: a browser
   * retargets `relatedTarget` to this host (which `contains` reports as inside, being inclusive),
   * while a test DOM hands over the raw node three roots down — so the walk climbs host by host and
   * both forms answer the same question.
   */
  private ownsNode(node: Node | null): boolean {
    let current = node;
    while (current !== null) {
      if (this.contains(current)) return true;
      const root = current.getRootNode();
      if (!(root instanceof ShadowRoot)) return false;
      current = root.host;
    }
    return false;
  }

  private get palette(): Sv3Palette | null {
    return this.shadowRoot?.querySelector('jf-sv3-palette') ?? null;
  }

  /** The one way the palette opens or closes, whichever affordance asked. */
  togglePalette(invoker: HTMLElement | null): void {
    const palette = this.palette;
    if (palette === null) return;
    if (palette.open) palette.hide();
    else void palette.show(invoker);
  }

  private onPaletteRequest(event: Event): void {
    this.togglePalette((event.composedPath()[0] ?? null) as HTMLElement | null);
  }

  override attributeChangedCallback(name: string, older: string | null, value: string | null): void {
    // An external write of the dev handle animates like every other route into the state. Lit's own
    // reflection also lands here, but by then the property already holds the value, so it falls
    // through to the default path and cannot loop.
    if (
      name === COMPOSER_STATE_ATTR &&
      isComposerState(value) &&
      this.hasUpdated &&
      value !== this.composerState
    ) {
      void this.setComposerState(value);
      return;
    }
    super.attributeChangedCallback(name, older, value);
  }

  /** The one way the state changes: applied inside the scoped view transition. */
  async setComposerState(next: Sv3ComposerState): Promise<void> {
    if (next === this.composerState) return;
    const composer = this.shadowRoot?.querySelector('jf-sv3-composer');
    const apply = async (): Promise<void> => {
      this.composerState = next;
      await this.updateComplete;
      // The regions schedule their OWN updates off this render, and the API captures the "after"
      // state when this callback resolves. Waiting on a FRAME here would deadlock: the browser
      // suspends rendering until the callback settles, so a requested frame is never serviced and
      // the transition is skipped at the ~4s callback timeout (measured). Their update promises are
      // microtask-backed and settle regardless.
      await Promise.all(
        [...(this.shadowRoot?.querySelectorAll('jf-sv3-main, jf-sv3-composer') ?? [])].map(
          (region) => (region as HTMLElement & { updateComplete: Promise<unknown> }).updateComplete,
        ),
      );
    };
    if (composer === null || composer === undefined) {
      await apply();
      return;
    }
    await runSv3ComposerMorph(composer, apply);
  }

  private onStateRequest(event: Event): void {
    const detail = (event as CustomEvent<Sv3ComposerStateRequest>).detail;
    if (!isComposerState(detail?.state ?? null)) return;
    void this.setComposerState(detail.state);
  }

  /**
   * A send is one ask and one morph, in that order: the request goes out in this tick, so the
   * transcript is already showing the streaming turn by the time the morph settles.
   *
   * THREE destinations, decided here and nowhere else (the composer announces a draft and a tier; it
   * does not know what a run is). The mid-run case comes FIRST and overrides the tier: a submit while
   * a delegated run is live JOINS that run as a steering directive — it is not an interrupt and it is
   * not a second commitment, so pressing Ctrl+Enter mid-run cannot start a competing run.
   */
  private onComposerSubmit(event: Event): void {
    const detail = (event as CustomEvent<Sv3ComposerSubmit>).detail;
    const text = (detail?.query ?? '').trim();
    if (text === '') return;
    if (!this.draftDomain.draftMayIssue(false)) return;
    // WP1.1 — a SEARCH is decided before the run is consulted at all: it is not a send into any
    // conversation, so it can never steer, answer or stop a run (S1.8).
    if (detail?.tier === 'search') {
      this.search.searchNow(text);
      return;
    }
    if (this.draftDomain.handoffBlocked) return;
    // A chord pressed in Search mode reached here past none of the composer's run rules — the
    // composer's slot was the search slot — so they are applied here, the same ones.
    if (this.tier === 'search' && this.chordRefused()) return;
    if (this.draftDomain.preparedHandoff === null && this.runDomain.steerableRun !== null) {
      this.steerLiveRun(text);
      return;
    }
    if (detail?.tier === 'delegate') {
      this.delegate(text);
      return;
    }
    if (detail?.tier === 'structured') {
      void this.askDomain.runAsk(text, this.search.pendingScope, this.draftDomain.schemaDraft);
      return;
    }
    // A hand-off and a results Ask share the same fixed, one-send file scope.
    void this.askDomain.runAsk(text, this.search.showing || this.draftDomain.preparedHandoff !== null || this.draftDomain.placeScopes.has(this.sessions.activeId ?? '') ? this.search.pendingScope : null);
  }

  /** Whether a response or a delegated run is live — the slot's `running`, read in one place. */
  private isRunning(run: Sv3RunView | null): boolean {
    return (
      this.streaming ||
      run?.phase === 'dispatching' ||
      run?.phase === 'running' ||
      run?.phase === 'holding'
    );
  }

  /**
   * The composer's run rules for a Ctrl+Enter pressed in Search mode: a held decision takes no
   * send, a live run takes only a steer, and an idle window delegates only when the agent can run.
   */
  private chordRefused(): boolean {
    const run = this.runDomain.run?.sessionId === this.sessions.activeId ? this.runDomain.snapshot() : null;
    if (run !== null && run.prompts.length > 0) return true;
    if (this.isRunning(run)) return this.runDomain.steerableRun === null;
    return this.delegateUnavailableReason !== '';
  }

  /* ── Search mode (WP1.1) ─────────────────────────────────────────────────────────────────── */

  /** A settled keystroke. In Search mode it is a search, staged exactly as the default window's. */
  private onComposerDraft(event: Event): void {
    const detail = (event as CustomEvent<Sv3ComposerDraft>).detail;
    if (detail === undefined) return;
    const composer = this.composer;
    if (composer !== null) {
      const id = this.search.showing ? null : this.sessions.activeId;
      this.sessionsDomain.noteLiveDraft(id);
    }
    if (this.draftDomain.retiredHandoffText !== null && detail.text !== this.draftDomain.retiredHandoffText) {
      this.draftDomain.clearRetiredHandoff();
      this.draftDomain.saveRecovery();
    }
    if (this.draftDomain.draftReview) { this.draftDomain.saveRecovery(); return; }
    if (this.draftDomain.humanTask !== null && this.tier !== 'search') this.draftDomain.saveRecovery();
    if (this.tier !== 'search' || !this.draftDomain.draftMayIssue()) return;
    if (detail.text.trim() !== '' && !this.draftDomain.enterResults(detail.previous, detail.text)) return;
    this.search.type(detail.text);
  }

  /** Put text in the composer — or, before its first render, where the composer will take it from. */
  private writeDraft(text: string): void {
    if (this.draftDomain.retiredHandoffText !== null && text !== this.draftDomain.retiredHandoffText) this.draftDomain.clearRetiredHandoff();
    this.composerDraft.replace(text);
  }

  private get filterNote(): string {
    if (!hasActiveFilter(getFilters())) return '';
    if (this.tier === 'delegate' || this.tier === 'structured' || this.draftDomain.livePreparation().kind === 'model') return localizeResourceKey('search.filters.other-mode');
    if (this.search.pendingScope !== null || this.draftDomain.preparedHandoff?.selection != null) return localizeResourceKey('search.filters.suspended');
    return '';
  }

  private onResultsFilterRemove(event: Event): void {
    this.search.onResultsFilterRemove((event as CustomEvent<Sv3ResultsFilterRemove>).detail);
  }

  private onCardFacetToggle(event: Event): void {
    this.search.onCardFacetToggle((event as CustomEvent<{ field: string; value: string }>).detail);
  }

  private onCardSelection(event: Event): void {
    this.search.onCardSelection((event as CustomEvent<CardSelectionDetail>).detail);
  }

  /** Stage's existing completion endpoint; the controller retains the target-specific policy. */
  reconcileConfirmedForget(detail: IndexedForgetCompletion): void {
    this.search.reconcileConfirmedForget(detail);
  }

  private async onCardForget(event: Event): Promise<void> {
    const detail = (event as CustomEvent<CardForgetDetail>).detail;
    await this.search.onCardForget(detail && { id: detail.id, path: detail.path, scope: detail.scope }, {
      cancelled: async current => {
        await this.updateComplete;
        // Busy temporarily removes the row's tabindex. Restore it before returning focus.
        const main = this.shadowRoot?.querySelector('jf-sv3-main');
        await main?.updateComplete;
        const results = main?.shadowRoot?.querySelector('jf-sv3-results');
        await results?.updateComplete;
        const card = (detail.invoker?.getRootNode() as ShadowRoot | undefined)?.host as ResultsCard | undefined;
        await card?.updateComplete;
        if (!current()) return;
        if (detail.invoker?.isConnected) detail.invoker.focus();
        else this.composer?.focusField();
      },
      completed: () => {
        const focused = deepActiveElement();
        if (focused === document.body || focused === detail.invoker || detail.invoker?.contains(focused)) this.composer?.focusField();
      },
    });
  }

  private onCardOpen(event: Event): void {
    this.search.onCardOpen((event as CustomEvent<{ id: string }>).detail?.id);
  }

  private onCardScopeFile(event: Event): void {
    this.search.onCardScopeFile((event as CustomEvent<{ path: string }>).detail?.path ?? '');
  }

  private onResultsAsk(): void {
    this.search.onResultsAsk();
  }

  /** The model uses the shared model gate, plus the conversation's write lock. No document gate. */
  private get modelAnswerUnavailableReason(): string {
    const id = this.sessions.activeId;
    if (id !== null && this.contextDomain.hasPendingWrite(id)) return this.contextDomain.contextNotice(id);
    if (this.historyLocked) return reasonFor('conversations.locked').wording;
    return this.draftDomain.humanUnavailable(SV3_MODEL_SHAPE_ID);
  }

  private onModelAnswer(event: Event): void {
    this.askDomain.modelAnswer((event as CustomEvent<{ turnId?: string }>).detail?.turnId);
  }

  /**
   * The window's reduced-capability fact (inventory E1), from the SAME observed-state authority the
   * availability projection above reads — so the banner and the composer's refusal can never
   * disagree about what is wrong. Derived per render rather than held: the store is the authority,
   * and a cached copy would be a second one.
   */
  private get degradation(): Sv3Degradation | null {
    return projectSv3Degradation(this.aiSnapshot);
  }

  /** The composer's availability, projected from the ONE observed-state authority. */
  private get askUnavailableReason(): string {
    if (this.draftDomain.preparedHandoff?.shapeId === 'core.summarize') return this.draftDomain.humanUnavailable('core.summarize');
    const modelOnly = this.draftDomain.preparedHandoff?.shapeId === SV3_MODEL_SHAPE_ID || (this.draftDomain.preparedHandoff === null
      && (this.shapeId === SV3_MODEL_SHAPE_ID || this.activeSession?.turns.at(-1)?.shapeId === SV3_MODEL_SHAPE_ID));
    return this.draftDomain.humanUnavailable(modelOnly ? SV3_MODEL_SHAPE_ID : SV3_ASK_SHAPE_ID);
  }

  /**
   * The DELEGATE tier's own gate, from the same authority. Its conditions are a strict SUBSET of the
   * ask tier's — both need a live backend and a loaded model (`state/availability.ts:96-133`, shared
   * by every affordance), and only ASK additionally needs an indexed document to ground an answer in
   * (`:139-146`). Reading one reason for both would therefore refuse a delegation the agent could
   * have served, which is why the two tiers get two projections rather than one shared guess.
   *
   * That containment is load-bearing beyond the wording (852 S4): while it holds, a Ctrl+Enter
   * delegate from ask mode can never meet a closed delegate gate behind an open ask one. See the
   * §S4 remainder note in `docs/tempdocs/852-sv3-promotion.md` for what breaks if `agent` ever
   * grows a gate of its own.
   */
  private get structuredUnavailableReason(): string { return this.draftDomain.humanUnavailable('core.extract'); }

  private get delegateUnavailableReason(): string {
    const availability = projectAvailability('agent', this.aiSnapshot);
    return availability.kind === 'unavailable' ? availability.reason : '';
  }

  /**
   * The composer announced a new effort rung (tempdoc 822 Phase F10). The window keeps it, and the
   * NEXT dispatch carries it — an in-flight stream is never re-parameterised, because its request
   * has already left.
   */
  private onEffortChange(event: Event): void {
    this.draftDomain.changeEffort((event as CustomEvent<Sv3EffortChange>).detail?.effort);
  }

  /**
   * The composer announced a new send TIER (852 S4). Validated rather than trusted, exactly as the
   * effort rung is: the event crosses a shadow boundary and an unknown tier would otherwise fall
   * through to `runAsk` and silently ask when the reader chose to delegate.
   */
  private onTierChange(event: Event): void {
    this.draftDomain.changeTier((event as CustomEvent<Sv3TierChange>).detail?.tier);
  }

  /** Pin the session before the export's independent reads can yield to navigation. */
  private exportActiveConversation(): void {
    const session = this.activeSession;
    if (session === null) return;
    void this.shadowRoot?.querySelector('jf-sv3-conversation-export')?.open(session, this.apiBase);
  }

  /* ── The delegate tier (tempdoc 822 Phase F2) ─────────────────────────────────────────────── */

  /**
   * The shared run controller as a READ. `peek` never constructs one, so "is a run live?" can be
   * asked on every render without this window creating a controller — and starting its polling — as
   * a side effect of being looked at.
   */
  private agentController(): AgentSessionController | null {
    return peekAgentSessionController();
  }

  /**
   * The DELEGATE path: the draft becomes an agent task. The causal order mirrors the ask — the turn
   * is opened first, then the run is dispatched — so the transcript already shows what was committed
   * before anything can come back, and the run has a turn to be written to from its very first frame.
   *
   * The run is HOSTED, never re-implemented: the shared `AgentSessionController` runs it and every
   * directive leaves through the ONE `dispatchRunControl` seam (`governance/steering-surfaces.v1.json`).
   */
  private delegate(text: string, turnAction = false): void {
    // Defence in depth, not a second gate: the composer already refuses an unavailable delegate and
    // keeps the draft. This exists because `delegate` is reachable from the window's own routing.
    if (this.delegateUnavailableReason !== '') return;
    if (!turnAction && !this.draftDomain.draftMayIssue(false)) return;
    if (this.draftDomain.humanTask !== null) {
      this.draftDomain.recoveryError = 'Review the retained question, then explicitly discard it before starting another submission.';
      this.requestUpdate(); return;
    }
    const target = this.sessions.activeId ?? createConversationId();
    if (!this.draftDomain.persistDraft({ place: target, text: '', preparation: { kind: 'delegate' } })) return;
    this.draftDomain.preparedHandoff = null;
    this.draftDomain.handoffNotice = ''; this.draftDomain.handoffBlocked = false;
    this.draftDomain.leaveResultsBySending();
    this.sessions = submitInSession(this.sessions, text, Date.now(), 'agent', target);
    const ref = latestTurnRef(this.sessions);
    if (ref === null) return;
    this.sessionsDomain.claimConversation(ref.sessionId);
    this.runDomain.dispatch(ref, text, this.effort);
  }

  /**
   * A mid-run submit JOINS the live turn. Through the seam, like every other per-run directive.
   *
   * Named `steerLiveRun` and not `steer` on purpose: the steering register bans a bare `.steer(` call
   * anywhere but the seam, and `this.steer(` would read as exactly that to the gate's scan. A method
   * whose name makes a correct call look like the forbidden one is a trap for the next reader too.
   */
  private steerLiveRun(text: string): void {
    if (!this.draftDomain.draftMayIssue(false)) return;
    this.runDomain.steerLiveRun(text);
  }

  /**
   * The `answer` rung of the primary slot. The composer cannot resolve a typed prompt, so the control
   * does the one honest thing available to it: it takes the reader to the decision.
   */
  private focusRunDecision(): void {
    const main = this.shadowRoot?.querySelector('jf-sv3-main');
    const prompt = main?.shadowRoot?.querySelector<HTMLElement>('[data-testid="sv3-run-prompt"]');
    if (prompt === null || prompt === undefined) return;
    prompt.scrollIntoView({ block: 'nearest' });
    // Through the primitive's shadow root, because the decision controls are `jf-control`s (852 S4
    // adoption) and `jf-control` does not delegate focus: focusing the HOST would move focus nowhere
    // and leave the reader on a decision they were just taken to.
    const decision = prompt.querySelector('jf-control');
    decision?.shadowRoot?.querySelector('button')?.focus();
  }

  /**
   * The SECONDARY axis (822 §4b course correction). Phase A1's search seam stays wired and tested,
   * but a plain submit no longer reaches it — it is called from the palette's "Search this text"
   * command, which keeps the seam demonstrable until the deferred search-integration conversation
   * decides what search means in a conversational window. It deliberately does NOT touch the session
   * list: a search is not a turn, and recording it as one would fabricate a conversation.
   */
  private onPaletteRun(event: Event): void {
    const id = (event as CustomEvent<Sv3PaletteRun>).detail?.id ?? '';
    // WP1.1 S1.11 — the palette's search IS Search mode: the mode changes where the reader can see
    // it, the draft is searched exactly as Enter would search it, and the caret goes back to it.
    if (id === SV3_COMMAND_SEARCH) {
      this.search.paletteSearch();
      return;
    }
    if (id === 'cmd-memory') requestSurfaceNavigation('core.memory-surface');
  }

  private get composer(): Sv3Composer | null {
    return this.shadowRoot?.querySelector('jf-sv3-composer') ?? null;
  }

  /**
   * WHICH MODEL WOULD ANSWER RIGHT NOW, read from the observed-state authority (tempdoc 822 Phase
   * F11). ONE expression, used in all three places it is needed — the composer's fact, the stamp a
   * terminal writes onto a turn, and the comparison the tail makes between the two — so "the current
   * model" cannot come to mean two different things in the same window.
   */
  private get currentModelLabel(): string | null {
    return this.aiSnapshot?.runtime.modelLabel ?? null;
  }

  private onSessionSelect(event: Event): void {
    this.sessionsDomain.selectSession((event as CustomEvent<Sv3SessionSelect>).detail?.id ?? '');
  }

  private onSessionRename(event: Event): void {
    const detail = (event as CustomEvent<Sv3SessionRename>).detail;
    if (detail === undefined) return;
    if (detail.phase === 'start') { this.renamingId = detail.id; return; }
    this.renamingId = null;
    if (detail.phase === 'commit') this.sessionsDomain.renameSession(detail.id, detail.title ?? '');
  }

  private onSessionPin(event: Event): void {
    this.sessionsDomain.pinSession((event as CustomEvent<Sv3SessionPin>).detail?.id ?? '');
  }
  /**
   * What the window knows about work in flight (tempdoc 831), read in ONE place and handed to both
   * the projection that paints the rows and the removal that refuses a live one. Two callers of one
   * expression, rather than two expressions — the row's affordance and the list's refusal have to be
   * the same judgement or the reader gets offered a delete that silently does nothing.
   */
  private runGate(run: Sv3RunView | null = this.runDomain.snapshot()): Sv3RunGate {
    // `isRefining` counts too: the store runs a re-query BEHIND displayed results quietly
    // (`state/searchState.ts:611` — no skeleton, so the content surface keeps the old rows), and the
    // row's dot is then the only thing on screen saying the session is running a pass.
    const searching = this.search.busy;
    const local = this.runDomain.run;
    // The render pass HANDS IN the projection it already made: projecting the feed a second time per
    // frame would be the same answer at twice the cost.
    const pendingPrompt = (run?.prompts.length ?? 0) > 0;
    return {
      searching,
      // Named by session, not by flag: only the conversation that OPENED the parked run may wear the
      // act-now colour, whichever row the reader happens to be looking at.
      awaitingDecisionIn: pendingPrompt && local !== null ? local.sessionId : null,
    };
  }

  private onSessionRemove(event: Event): void {
    this.sessionsDomain.removeSession((event as CustomEvent<Sv3SessionRemove>).detail?.id ?? '');
  }

  private onSessionNew(): void { this.sessionsDomain.newSession(); }
  /**
   * The Stop slot serves BOTH streams, because there is one slot. Which one it halts is decided here,
   * by which one is actually running — the composer says the reader pressed Stop and nothing more.
   */
  private onComposerStop(): void {
    if (this.draftDomain.humanLoad !== null && !this.streaming) { this.draftDomain.revokeHumanTask(); this.draftDomain.saveRecovery(); return; }
    if (this.streaming) {
      this.askDomain.stop();
      return;
    }
    this.runDomain.haltRun();
  }

  /**
   * The citation pane and the boundary that moves it — mounted ONLY while a cited document is open
   * (Phase F8), so a closed pane is not an inert region holding a stale document, a focusable node
   * and a second reader of the api base.
   */
  private pane(): TemplateResult | typeof nothing {
    if (this.paneDocPath === null) return nothing;
    // The grip is not rendered at all in the overlay presentation, rather than hidden: an overlaid
    // sheet has no boundary to move, and a `display: none` button would still be a control the
    // element tree carries around (and a tab stop the moment a rule stopped applying).
    const grip = this.paneOverlay
      ? nothing
      : html`<button
          type="button"
          class="pane-grip"
          data-testid="sv3-pane-grip"
          aria-label=${PANE_LABEL.grip}
          title=${PANE_LABEL.grip}
          @pointerdown=${this.onPaneGripPointerDown}
          @keydown=${this.onPaneGripKeydown}
          @dblclick=${this.resetPaneWidth}
        ></button>`;
    return html`
      ${grip}
      ${keyed(this.paneSource?.sourceUnavailable ? this.paneSource : null, html`<jf-sv3-pane
        data-testid="sv3-pane"
        .docPath=${this.paneDocPath}
        .highlightRange=${this.paneHighlight}
        .citation=${this.paneCitation}
        .citationHeader=${this.paneCitationHeader}
        ?overlay=${this.paneOverlay}
        api-base=${this.apiBase}
        @sv3-pane-close=${this.dismissPane}
      ></jf-sv3-pane>`)}
    `;
  }

  render(): TemplateResult {
    const ai = this.aiSnapshot;
    const indexing = selectIndexingProgress(ai?.status, ai?.indexingStatusLive ?? false,
      ai?.episodeMaxPendingJobs ?? 0, ai?.enrichSettleSamples ?? []);
    const results = this.search.view(projectAvailability('documents', this.aiSnapshot, true));
    const run = this.runDomain.snapshot();
    const turns = activeTurns(this.sessions);
    const visibleRun = !this.search.showing && (this.runDomain.run?.sessionId === this.sessions.activeId || this.sessions.activeId === null && this.draftDomain.preparedHandoff === null) ? run : null;
    const pendingPrompt = visibleRun !== null && visibleRun.prompts.length > 0;
    const scope = this.search.showing || this.tier === 'structured' || this.draftDomain.preparedHandoff !== null || this.draftDomain.placeScopes.has(this.sessions.activeId ?? '') ? this.search.pendingScope : null;
    const slot = sv3PrimaryAction({
      pendingPrompt,
      running: this.isRunning(visibleRun),
      followUp: turns.length > 0,
      tier: this.tier,
      steerable: this.runDomain.steerableRun !== null,
      delegateReady: this.delegateUnavailableReason === '',
      scoped: scope !== null,
    });
    // Relative timestamps are computed HERE, on render, and never ticked: a sidebar that re-renders
    // itself every second is continuous motion at rest, which the spec's duty-cycle law rules out.
    const sessionGroups = projectSv3Sessions(this.sessions, {
      ...this.runGate(run),
      now: Date.now(),
    });
    // ONE reading of the effective context per render, shared by the transcript's frames and the
    // bar's aggregate — so the dimmed turns and the "N turns hidden" count cannot disagree.
    const session = this.activeSession;
    const contexts = this.contextDomain.turnContexts();
    return html`
      ${this.sidebarNarrow && !this.sidebarCollapsed ? html`<button
        type="button"
        class="sidebar-backdrop"
        data-testid="sv3-sidebar-backdrop"
        aria-label="Close the sidebar"
        @click=${this.dismissSidebarSheet}
      ></button>` : nothing}
      <jf-sv3-conversation-export .locked=${this.historyLocked}></jf-sv3-conversation-export>
      <jf-sv3-sidebar
        .groups=${sessionGroups}
        .renamingId=${this.renamingId}
        ?collapsed=${this.sidebarCollapsed}
        data-testid="sv3-sidebar"
        @sv3-session-select=${this.onSessionSelect}
        @sv3-session-export=${this.exportActiveConversation}
        @sv3-session-pin=${this.onSessionPin}
        @sv3-session-remove=${this.onSessionRemove}
        @sv3-session-new=${this.onSessionNew}
        @sv3-session-rename=${this.onSessionRename}
        @sv3-sidebar-toggle=${this.onSidebarToggle}
      ></jf-sv3-sidebar>
      <button
        type="button"
        class="sidebar-grip"
        data-testid="sv3-sidebar-grip"
        aria-label=${SIDEBAR_GRIP_LABEL}
        title=${SIDEBAR_GRIP_LABEL}
        @pointerdown=${this.onGripPointerDown}
        @keydown=${this.onGripKeydown}
        @click=${this.onGripClick}
        @dblclick=${this.resetSidebarWidth}
      ></button>
      <div
        class="column"
        data-testid="sv3-column"
        @sv3-composer-assignment=${this.draftDomain.onHumanAssignment}
        @sv3-composer-state-request=${this.onStateRequest}
        @sv3-schema-change=${(event: CustomEvent<{ text: string }>) => this.draftDomain.changeSchema(event.detail.text)}
        @sv3-composer-submit=${this.onComposerSubmit}
        @sv3-composer-draft=${this.onComposerDraft}
        @sv3-composer-scope-clear=${() => this.draftDomain.clearScope()}
        @sv3-results-filter-remove=${this.onResultsFilterRemove}
        @sv3-results-ask=${this.onResultsAsk}
        @sv3-results-clear-selection=${() => { this.draftDomain.revokeHumanTask(); this.search.select([]); }}
        @card-open=${this.onCardOpen}
        @card-selection=${this.onCardSelection}
        @card-forget=${this.onCardForget}
        @card-facet-toggle=${this.onCardFacetToggle}
        @card-scope-file=${this.onCardScopeFile}
        @sv3-composer-stop=${this.onComposerStop}
        @sv3-composer-answer=${() => this.runDomain.answer()}
        @sv3-effort-change=${this.onEffortChange}
        @sv3-tier-change=${this.onTierChange}
        @sv3-run-decision=${(event: CustomEvent<Sv3RunDecision>) => this.runDomain.decide(event.detail)}
        @sv3-palette-request=${this.onPaletteRequest}
        @sv3-remedy=${this.onRemedy}
        @sv3-citation-open=${this.onCitationOpen}
        @sv3-context-menu=${this.onContextMenu}
        @sv3-context-action=${(event: CustomEvent<Sv3ContextAction>) => {
          if (event.detail !== undefined) void this.contextDomain.runContextAction(event.detail);
        }}
        @sv3-model-answer=${this.onModelAnswer}
        @sv3-model-focus=${this.onFocusComposerEvent}
        @sv3-branch-action=${this.onBranchAction}
        @sv3-version-select=${this.onVersionSelect}
      >
        <jf-sv3-topbar window-title=${WINDOW_TITLE} data-testid="sv3-topbar"></jf-sv3-topbar>
        ${indexing.pause?.waiting || !indexing.activity.confirmed || this.shadowRoot?.querySelector('jf-indexing-controls:focus-within')
          ? html`<div class="indexing-pause-note" data-testid="sv3-indexing-pause">
          ${indexing.pause?.waiting ? html`<p>${indexing.activity.summary} — ${indexing.activity.detail} Existing indexed files remain searchable.</p>` : nothing}
          <jf-indexing-controls .progress=${indexing} api-base=${this.apiBase}></jf-indexing-controls>
        </div>` : nothing}
        ${this.renderBranchOrigin()}
        ${this.recoveryNotice()}
        <div class="column">
        ${this.search.forgetNotice ? html`<div role="status" aria-live="polite" data-testid="sv3-forget-notice">
          <span style="white-space:pre-wrap;overflow-wrap:anywhere">${this.search.forgetNotice}</span>
          <jf-button label="Review in Library" .onActivate=${() => requestSurfaceNavigation('core.library-surface')}>Review in Library</jf-button>
        </div>` : nothing}
        <jf-sv3-main
          .apiBase=${this.apiBase}
          state=${this.composerState}
          .view=${results}
          .forgetAvailable=${this.search.forgetAvailable && !this.search.forgetBusy}
          .turns=${turns}
          .conversationId=${session?.id ?? ''}
          .turnScopes=${this.search.scopesByTurn}
          .turnFilters=${this.search.filtersByTurn}
          .modelAnswerUnavailableReason=${this.modelAnswerUnavailableReason}
          ?model-answer-busy=${this.askDomain.contextPending || this.agentController()?.runInFlight === true}
          .structuredNotes=${new Map(turns.filter(t => t.shapeId === 'core.extract').map(t => [t.id, this.askDomain.structuredNote(t.id)]))}
          .run=${visibleRun}
          .liveEvidencePaths=${this.agentController()?.groundingDeltaPaths ?? EMPTY_EVIDENCE_PATHS}
          .unconfirmedSteerNotes=${this.runDomain.unconfirmedSteerNotes}
          ?record-notice=${this.contextDomain.recordNotice}
          ?history-locked=${this.historyLocked}
          ?locked-refusal=${this.lockedRefusal}
          .reasoning=${this.askDomain.liveReasoning()}
          .reasoningTurnId=${this.askDomain.liveReasoningTurnId()}
          .currentModelLabel=${this.currentModelLabel}
          ?detailed=${isAdvancedMode()}
          .turnContexts=${contexts}
          .turnLineage=${this.branchDomain.turnLineage()}
          .continuationVersions=${this.branchDomain.continuationVersions()}
          .floorSummary=${session?.history?.contextFloorSummary ?? null}
          ?streaming=${this.streaming}
          .stopping=${this.askStopping ?? this.runDomain.runStopping()}
          .checkingTurnId=${this.askCheckingTurnId}
          data-testid="sv3-main"
        ></jf-sv3-main>
        <!-- 859 §B — ONE positioned wrapper for the whole dock, so its height is one observable
             box and the transcript scrolls beneath it rather than being cut off above it. -->
        <div class="dock" data-testid="sv3-dock">
        <jf-sv3-context-bar
          .notice=${session === null ? '' : this.contextDomain.contextNotice(session.id)}
          .usage=${session?.contextUsage ?? null}
          .contextWindow=${this.aiSnapshot?.runtime.contextWindow ?? null}
          hidden-turns=${sv3ExcludedTurnCount(contexts)}
          unseen-hidden=${sv3UnseenExcludedIds(contexts, session?.history ?? null).length}
          ?floor-unseen=${session !== null && sv3FloorUnseen(session.turns, session.history)}
          .floorSummary=${session?.history?.contextFloorSummary ?? null}
          data-testid="sv3-context-bar-host"
        ></jf-sv3-context-bar>
        <jf-sv3-composer
          state=${this.composerState}
          slot-kind=${slot.kind}
          slot-reason=${slot.reason}
          halt-reason=${slot.haltReason}
          ?steerable=${this.runDomain.steerableRun !== null}
          unavailable-reason=${this.draftDomain.retiredHandoffNotice || (this.draftDomain.handoffBlocked ? this.draftDomain.handoffNotice : this.askUnavailableReason)}
          delegate-unavailable-reason=${this.draftDomain.retiredHandoffNotice || this.delegateUnavailableReason}
          structured-unavailable-reason=${this.draftDomain.retiredHandoffNotice || this.structuredUnavailableReason}
          .degradation=${this.degradation}
          ?detailed=${isAdvancedMode()}
          .corpus=${projectSv3Corpus(this.aiSnapshot)}
          effort=${this.effort}
          tier=${this.tier}
          .searchError=${this.search.snapshot?.errorInfo?.code === 'SEARCH_SYNTAX_INVALID' ? this.search.snapshot.error ?? '' : ''}
          .schemaDraft=${this.draftDomain.schemaDraft}
          .filterNote=${this.filterNote}
          .filterError=${this.askDomain.askFilterError || (this.search.snapshot?.errorInfo?.status === 400 && this.search.snapshot.errorInfo.code !== 'SEARCH_SYNTAX_INVALID' ? this.search.snapshot.errorInfo.message : '')}
          .recentItems=${this.sessionsDomain.recentItems}
          .recentLocked=${this.historyLocked}
          @sv3-recents-refresh=${() => { void this.sessionsDomain.refreshRecents(); }}
          @sv3-recents-clear=${() => this.sessionsDomain.clearRecents()}
          @sv3-recent-select=${this.onRecentSelect}
          .structuredError=${this.askDomain.structuredError}
          task-notice=${this.draftDomain.retiredHandoffNotice || this.draftDomain.handoffNotice || (this.draftDomain.preparedHandoff?.shapeId === 'core.summarize' ? 'Summarize prepared. Edit the instruction and send.' : '')}
          scope-label=${scope !== null ? sv3ScopeLabel(scope) : ''}
          model-label=${this.currentModelLabel ?? ''}
          data-testid="sv3-composer"
        ></jf-sv3-composer>
        </div>
        </div>
      </div>
      ${this.pane()}
      <!-- LAST in the shadow root on purpose: the palette and the hero composer share the overlay
           rung, so DOM order is what puts the palette on top. The pane above shares that rung too
           when it is overlaid, and is deliberately BEFORE the palette for the same reason: a command
           palette invoked over an open document belongs on top of it. -->
      <jf-sv3-palette data-testid="sv3-palette" @sv3-palette-run=${this.onPaletteRun}></jf-sv3-palette>
    `;
  }
}

customElements.define('jf-sv3-window', SearchV3View);

declare global {
  interface HTMLElementTagNameMap {
    'jf-sv3-window': SearchV3View;
  }
}

// WP1.5a — all compatibility shape mounts share the promoted interaction window.
registerViewFactory('core.rag-ask', ONE_WINDOW_MOUNT_TAG);
registerViewFactory('core.free-chat', ONE_WINDOW_MOUNT_TAG);
registerViewFactory('core.extract', ONE_WINDOW_MOUNT_TAG);
registerViewFactory('core.agent-run', ONE_WINDOW_MOUNT_TAG);
registerViewFactory('core.workflow-run', ONE_WINDOW_MOUNT_TAG);
for (const shape of CORE_INTERACTION_SHAPES) {
  if (getViewFactory(shape) === undefined) throw new Error(`[one-window] missing view factory for ${shape}`);
}
