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
 *  5. **The one ask.** A send opens a turn and dispatches it through `sv3-ask.ts`, the window's ONE
 *     issuance site, holding the `AbortController` that Stop uses and settling the turn on whichever
 *     terminal the stream reports. Phase A1's SEARCH issuance (`setQuery` + `submitSearch`) is
 *     still here and still exactly one request,
 *     but it is the SECONDARY axis now: only the palette's "Search this text" reaches it.
 *  6. **The session list, as a PROJECTION of the product's record** (Phase A2; conversations since
 *     F1; on the record since F6). A session IS a conversation: its identity and its listing come
 *     from `state/conversationListStore.ts`, and its transcript from the canonical
 *     `GET /api/thread/{id}` record via `sv3-record.ts`. The host holds the projected list and routes
 *     everything that changes it (a send, a row click, New session, every stream event, and each
 *     record refresh) through the funnels above; the sidebar and the content surface render
 *     projections and issue nothing. What is still window-local — the pin and the unread bit — and
 *     why, is stated in `sv3-sessions.ts`'s persistence-boundary note.
 *
 * Mounted as a hidden DEEPLINK surface, dev audience, no rail entry:
 * `#justsearch://surface/core.search-v3-surface`.
 *
 * Side-effect registers <jf-sv3-window> and its four regions.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import { sv3Tokens } from './sv3-tokens.css.js';
import { sv3Shared } from './sv3-shared-styles.js';
import { COMPOSER_STATE_DEFAULT, WINDOW_TITLE, type Sv3ComposerState } from './fixtures.js';
import {
  setQuery,
  setSearchApiBase,
  submitSearch,
  subscribeSearch,
  type SearchState,
} from '../../state/searchState.js';
import {
  adoptSv3MorphSheet,
  releaseSv3MorphSheet,
  runSv3ComposerMorph,
} from './sv3-composer-morph.js';
import {
  type Sv3Composer,
  type Sv3ComposerStateRequest,
  type Sv3ComposerSubmit,
  type Sv3EffortChange,
  type Sv3TierChange,
} from './Sv3Composer.js';
import { projectSv3Results, type Sv3ResultsView } from './sv3-results.js';
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
  focusSession,
  mergeStoreConversations,
  renameSession,
  toggleSessionPin,
  latestTurnRef,
  projectSv3Sessions,
  removeSession,
  restoreSessionTitle,
  sessionById,
  setSessionContextUsage,
  setTurnEvidence,
  setTurnReasoning,
  setTurnRewrite,
  settleAgentTurn,
  settleTurn,
  startNewSession,
  submitInSession,
  sv3ShouldGenerateTitle,
  SV3_SESSIONS_EMPTY,
  type Sv3RunGate,
  type Sv3Session,
  type Sv3SessionList,
} from './sv3-sessions.js';
// The effective-context set (tempdoc 610, ported by 852 S2) — the pure half. The WRITES are the five
// shared store functions below; this module decides what each turn's frame and menu are, and what
// the shared inspector renders.
import {
  projectSv3ContextInspector,
  projectSv3TurnContexts,
  sv3ContextMenuItems,
  sv3ExcludedMessageIds,
  sv3ExcludedTurnCount,
  sv3TurnContextFor,
  type Sv3ContextAction,
  type Sv3ContextActionId,
  type Sv3ContextMenuRequest,
  type Sv3TurnContext,
} from './sv3-context.js';
// Branch / edit / retry and the version pager (slice 513 + 610 Phase A/B, ported by 852 S3) — the
// pure half. All three are ONE backend act (`branchConversation`); this module decides which message
// id each of them names, and which conversations are the versions of a turn.
import {
  isSv3BranchActionId,
  projectSv3TurnLineage,
  sv3BranchMenuItems,
  sv3LineageFor,
  type Sv3BranchAction,
  type Sv3TurnLineage,
  type Sv3VersionSelect,
} from './sv3-branch.js';
// The window's honesty derivations (tempdoc 822 Phase F7): the lock, the corpus, and the ONE remedy
// channel every region's fix-it control leaves through.
import {
  deriveSv3HistoryLocked,
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
  isSv3Effort,
  sv3Ask,
  type Sv3Effort,
} from './sv3-ask.js';
// The app-wide CONVERSATION authority (tempdoc 510 Design D; inventory A1). A v3 session IS a
// conversation, so identity, existence, title and its markdown export come from here — this window
// mints none of them.
import {
  branchConversation,
  clearContextFloor,
  compactContext,
  createConversationId,
  deleteConversationWithCascade,
  editContextFloorSummary,
  exportConversationMarkdown,
  generateConversationTitle,
  loadConversations,
  resumeConversation,
  setActiveConversation,
  setContextFloor,
  setConversationApiBase,
  setConversationTitle,
  setMessageExcluded,
  subscribeConversationList,
  type Conversation,
} from '../../state/conversationListStore.js';
// The product's ONE confirm dialog. Cascade-delete asks a second question — "and its branches?" —
// and asking it in a second dialect would be a second dismiss rule for the same gesture.
import { confirmAsync } from '../../components/ConfirmDialog.js';
// Tempdoc 610 §K — the SHELL-mounted context inspector. The window pushes the projection and opens
// the drawer; the drawer is the product's one "what did the assistant actually see" surface and is
// not re-implemented here.
import {
  isContextInspectorOpen,
  setContextInspectorView,
  toggleContextInspector,
} from '../../state/contextInspectorDrawer.js';
import type { InspectorView } from '../../components/ContextInspectorPane.js';
// The product's ONE overflow-menu primitive (slice 458). A window-local menu would be a second
// keyboard model and a second dismiss rule for the same gesture.
import { openContextMenu } from '../../components/ContextMenu.js';
// The shared clipboard util — the export's destination, the same one the shipped window uses.
import { copyToClipboard } from '../../utils/clipboardCopy.js';
// The canonical thread RECORD (tempdoc 561 P-A; inventory D1) — the shared fetch, already consumed
// by the shipped window. The shared PROJECTOR is reached through 'sv3-record.ts',
// this window's registered run-projection site (governance/run-renderers.v1.json), never from here.
import { fetchUnifiedThread } from '../unifiedThreadClient.js';
// The shared per-raise step (565 run-control seam), so the button's label and the directive it
// dispatches cannot promise different numbers.
import { RAISE_BUDGET_STEP_TOKENS } from '../unifiedChatRequest.js';
// Tempdoc 609 Phase 3 — the ONE per-tab pointer (sessionStorage): a reload restores the thread THIS
// tab was reading, not the globally-most-recent one.
import {
  clearLastViewedConversation,
  readLastViewedConversation,
  setLastViewedConversation,
} from '../../controllers/lastViewedConversation.js';
// Tempdoc 609 §R — the shared reload-durable draft controller (T2.1) and its one-shot leave hint (T1.4).
import { DraftPersistence } from '../../controllers/draftPersistence.js';
import { notifyDraftKeptOnce } from '../../controllers/draftKeptHint.js';
// Tempdoc 834 §15.3 — the backend's live-run enumeration, asked on a cold load.
import { discoverLiveAgentRun } from '../../controllers/liveRuns.js';
import {
  getAgentSessionController,
  peekAgentSessionController,
  subscribeAgentSession,
} from '../../state/agentSessionStore.js';
import type { AgentSessionController } from '../../controllers/AgentSessionController.js';
import {
  directiveAvailable,
  dispatchRunControl,
  type RunControlRefusal,
} from '../../controllers/runControlIntent.js';
import {
  deriveSv3RunPhase,
  hasServerAcknowledgedLocalDispatch,
  projectSv3RunFeed,
  projectSv3RunPrompts,
  sv3PrimaryAction,
  isSv3Tier,
  SV3_TIER_DEFAULT,
  type Sv3ComposerTier,
  sv3RunNeedsPresence,
  sv3RunOutcome,
  sv3RunPresenceStart,
  sv3RunPresenceTitle,
  sv3RunSessionStatus,
  SV3_RUN_FEED_EMPTY,
  type Sv3RunFeed,
  type Sv3RunLocal,
  type Sv3RunTurnState,
  type Sv3RunView,
} from './sv3-run.js';
import { type Sv3CitationOpen, type Sv3RunDecision } from './Sv3Main.js';
import {
  sameCitationHeader,
  type CitationHeader,
} from '../../components/chat/evidenceProjection.js';
// Tempdoc 859 §3 — the ONE delegate-plane evidence projection, shared with the record reader.
import { agentAnswerEvidence } from '../../components/chat/agentEvidence.js';
import type { DocumentCitationAnchor } from '../../components/documentPane/DocumentPane.js';
import {
  sv3CitationHeader,
  sv3MatchedSentence,
  SV3_SOURCE_INDEX_ABSENT,
} from './sv3-citation-anchor.js';
import { setAiActivity, subscribeAiState, type AiState } from '../../state/aiStateStore.js';
import { projectAvailability } from '../../state/availability.js';
import { reasonFor } from '../../state/readinessNotice.js';
import { isAdvancedMode, subscribeUiMode } from '../../state/uiModeState.js';
import {
  getAppearanceMode,
  subscribeAppearanceMode,
  type AppearanceMode,
} from '../../state/themeState.js';
import { emitEphemeralToast } from '../../components/advisory/ephemeralToast.js';
import { projectSv3Degradation, type Sv3Degradation } from './sv3-degradation.js';
import {
  BRANCH_FAILED,
  CONTEXT_COMPACT_FAILED,
  CONTEXT_EXCLUDE_FAILED,
  CONTEXT_FLOOR_FAILED,
  CONTEXT_INCLUDE_FAILED,
  CONTEXT_RESTORE_FAILED,
  CONTEXT_SUMMARY_FAILED,
  DELETE_FAILED,
  deleteCascadeConfirm,
  deleteCascadeMessage,
  deleteCascadeTitle,
  PANE_LABEL,
  SV3_COMMAND_EXPORT_MARKDOWN,
  SV3_COMMAND_SEARCH_TEXT,
  SV3_DRAFT_KEY,
  SV3_RENAME_FAILED,
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
        padding: 0;
        border: 0;
        background: transparent;
        cursor: w-resize;
        z-index: var(--z-sticky);
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
           the content region. */
        position: relative;
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
        padding: 0;
        border: 0;
        background: transparent;
        cursor: col-resize;
        z-index: var(--z-sticky);
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
    `,
  ];

  static properties = {
    composerState: { type: String, reflect: true, attribute: COMPOSER_STATE_ATTR },
    theme: { type: String, reflect: true },
    apiBase: { type: String, attribute: 'api-base' },
    searchSnapshot: { state: true },
    asked: { state: true },
    sessions: { state: true },
    aiSnapshot: { state: true },
    streaming: { state: true },
    sidebarWidthPx: { state: true },
    sidebarCollapsed: { type: Boolean, reflect: true, attribute: 'sidebar-collapsed' },
    resizing: { type: Boolean, reflect: true },
    paneDocPath: { state: true },
    paneCitation: { state: true },
    paneCitationHeader: { state: true },
    paneSource: { state: true },
    paneWidthPx: { state: true },
    paneOverlay: { type: Boolean, reflect: true, attribute: 'pane-overlay' },
    renamingId: { state: true },
    recordNotice: { state: true },
    historyLocked: { state: true },
    lockedRefusal: { state: true },
    effort: { state: true },
    tier: { state: true },
    compacting: { state: true },
    conversations: { state: true },
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
  /** The latest store emission. Null only until the subscription's first (immediate) call. */
  declare searchSnapshot: SearchState | null;
  /**
   * Whether THIS window has sent anything. The store is a process-wide singleton, so without this
   * the window would render another surface's results as its own the moment it docked.
   */
  declare asked: boolean;
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
  /**
   * The sidebar's chosen width (tempdoc 822 Phase F5). Kept EXPANDED-only: collapsing renders the
   * icon rail without touching this number, which is what makes "expand restores the width I chose"
   * true by construction rather than by saving and re-applying it.
   */
  declare sidebarWidthPx: number;
  declare sidebarCollapsed: boolean;
  /** A drag is in progress — the transitions stand down so the panel tracks the pointer exactly. */
  declare resizing: boolean;
  /**
   * The CITED document the pane is open on, or null for closed (tempdoc 822 Phase F8). The pane is
   * mounted exactly while this is non-null, and this field has exactly ONE writer that sets it —
   * {@link onCitationOpen}. That is the scope guard: no search result, no browse row and no typed
   * path can reach the reading surface from this window, because nothing else assigns here.
   */
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
  declare paneSource: { readonly turnId: string; readonly sourceIndex: number } | null;
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
   * The claimed conversation's canonical record could not be read (tempdoc 822 Phase F6; inventory
   * D2 / tempdoc 727 F-8). Distinct from "the conversation is empty": a failed refresh leaves the
   * live state on screen by `fetchUnifiedThread`'s contract, and this is what stops that being silent.
   */
  declare recordNotice: boolean;
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
  declare effort: Sv3Effort;
  /**
   * WHERE the next send goes (852 S4) — ask the local model, or delegate the draft to the agent.
   *
   * Held on exactly {@link effort}'s terms and for the same reasons: window-local, in-memory, not a
   * persisted preference, and read at DISPATCH time so an in-flight run is never re-routed. The
   * composer renders it and announces a change; the routing itself is decided here, in
   * `onComposerSubmit`, which is the one place a send becomes a run.
   */
  declare tier: Sv3ComposerTier;
  /**
   * A compaction is in flight (tempdoc 610 Phase D). WINDOW-level because it is one LLM call and the
   * menu that starts it must not offer a second while the first is running — the reference window's
   * own guard (`views/UnifiedChatView.ts:1658-1673`).
   */
  declare compacting: boolean;
  /**
   * The shared conversation store's list, AS ROWS (852 S3). This window's own {@link sessions} is a
   * projection that drops the two fields the version pager is made of — `parentSessionId` and
   * `branchPointMessageId` — so the pager reads the store's rows directly rather than having those
   * pointers copied onto a second shape that would then have to be kept in step.
   *
   * Held rather than re-fetched: `siblingSessionsAt` is a pure read and the subscription below keeps
   * this current, which is why a branch created here calls `loadConversations` — the new row is what
   * makes the fork visible to the pager at all.
   */
  declare conversations: readonly Conversation[];

  /** Watches the window box so the pane's presentation follows it (Phase F8). */
  private boxObserver: ResizeObserver | null = null;
  private searchUnsubscribe: (() => void) | null = null;
  private aiUnsubscribe: (() => void) | null = null;
  private uiModeUnsubscribe: (() => void) | null = null;
  private appearanceUnsubscribe: (() => void) | null = null;
  private agentUnsubscribe: (() => void) | null = null;
  private convListUnsubscribe: (() => void) | null = null;
  /** The in-flight record fetch, so a claim that supersedes another cannot land out of order. */
  private recordAbort: AbortController | null = null;
  /**
   * The `/history` companion load's generation token (852 S3, closing S2's F5) — the record half's
   * {@link recordAbort} discipline applied to the other half of the same open.
   */
  private historyAbort: AbortController | null = null;
  /**
   * One-shot, mirroring the shipped window's `reattachChecked` (`views/UnifiedChatView.ts:3496`): a
   * cold load asks the shared controller to reattach to a live run ONCE. Re-asking on every render
   * would re-attach a run the reader has since halted.
   */
  private reattachChecked = false;
  /**
   * The draft restored from storage before the composer existed to receive it. `DraftPersistence`
   * rehydrates during `hostConnected`, which is BEFORE the first render, so the value is parked here
   * and applied on the update that first creates the composer.
   */
  private draftSeed = '';
  private draftSeedPending = false;
  /** The in-flight ask's abort handle; null exactly when no response is streaming. */
  private askAbort: AbortController | null = null;
  /**
   * What this window remembers about the delegated run it dispatched — including the explicit turn
   * ref that is its `activeTurnId`. Not reactive state: it is mutated in place by the controller's
   * notifications (a latch and two flags), and every one of those paths already re-renders.
   */
  private run: Sv3RunLocal | null = null;
  /** Whether the run was observed LIVE, so its terminal is an EDGE rather than a repeated verdict. */
  private runLive = false;
  /**
   * Controller run ids this window has already given a session (Phase F3 presence). Adoption happens
   * once per run: without the latch, the same live run would be re-adopted on the next notification
   * after its turn settled, and the sidebar would grow a row per frame.
   */
  private readonly adoptedRunIds = new Set<string>();
  /**
   * Conversations this window has already asked the model to name (inventory A11). Once per
   * conversation: the title is the row's label forever, so a second generation would rename a row
   * the reader has already learned to recognise.
   */
  private readonly titledSessionIds = new Set<string>();
  /**
   * The SHARED reasoning model for the ask that is streaming (inventory C9). One controller, reset
   * per ask, exactly as the shipped window holds it (`views/UnifiedChatView.ts:645`) — its blocks are
   * handed to the turn at the terminal, which is what makes a past turn's thinking survive the reset.
   */
  private readonly askReasoning = new ReasoningController(() => this.requestUpdate());
  /**
   * The turn the ask controller above is streaming for, or null (tempdoc 848 §2.7). Handed down so
   * the transcript binds the live thinking to ONE turn by id, exactly as it binds the run feed —
   * `streaming` status alone is per-turn and reachable for two turns at once.
   */
  private askReasoningTurnId: string | null = null;

  constructor() {
    super();
    this.composerState = COMPOSER_STATE_DEFAULT;
    // Read in the CONSTRUCTOR, not on connect: the attribute is reflected, so a value arriving one
    // render late would paint the dark set for a frame in a light app — the flash the app's own
    // pre-paint script exists to avoid.
    this.theme = getAppearanceMode();
    this.apiBase = '';
    this.searchSnapshot = null;
    this.asked = false;
    this.sessions = SV3_SESSIONS_EMPTY;
    this.aiSnapshot = null;
    this.streaming = false;
    this.sidebarWidthPx = SV3_SIDEBAR_DEFAULT_PX;
    this.sidebarCollapsed = false;
    this.resizing = false;
    this.paneDocPath = null;
    this.paneCitation = null;
    this.paneSource = null;
    this.paneCitationHeader = null;
    this.paneWidthPx = SV3_PANE_DEFAULT_PX;
    this.paneOverlay = false;
    this.renamingId = null;
    this.recordNotice = false;
    this.historyLocked = false;
    this.lockedRefusal = false;
    this.effort = SV3_EFFORT_DEFAULT;
    this.tier = SV3_TIER_DEFAULT;
    this.compacting = false;
    this.conversations = [];
    // Constructed HERE rather than on connect: a Lit controller added before connection still gets
    // its `hostConnected`, and adding it inside `connectedCallback` would add a second one on every
    // re-attach of this retained instance.
    new DraftPersistence(
      this,
      SV3_DRAFT_KEY,
      () => this.currentDraft(),
      (value) => {
        this.draftSeed = value;
        this.draftSeedPending = true;
        this.requestUpdate();
      },
    );
  }

  /** The draft as it stands, wherever it currently lives — the composer owns it once it exists. */
  private currentDraft(): string {
    return this.composer?.draft ?? this.draftSeed;
  }

  override connectedCallback(): void {
    super.connectedCallback();
    adoptSv3MorphSheet();
    this.restoreBoundaryPreferences();
    this.observeWindowBox();
    setSearchApiBase(this.apiBase || '');
    this.searchUnsubscribe = subscribeSearch((snapshot) => {
      this.searchSnapshot = snapshot;
    });
    this.aiUnsubscribe = subscribeAiState((snapshot) => {
      this.aiSnapshot = snapshot;
      this.applyLockState(snapshot);
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
    // Subscribing does NOT create a controller (the read below is a `peek`), so a window that never
    // delegates never starts the agent controller's polling as a side effect of being mounted.
    this.agentUnsubscribe = subscribeAgentSession(this.onAgentUpdate);
    // ── The conversation record (tempdoc 822 Phase F6) ─────────────────────────────────────────
    // A session is a conversation, so the app-wide store is where the list comes from. The
    // subscription fires immediately with whatever is already loaded; the fetch refreshes it.
    setConversationApiBase(this.apiBase || '');
    this.convListUnsubscribe = subscribeConversationList((state) => {
      this.sessions = mergeStoreConversations(this.sessions, state.conversations);
      // The ROWS as well as the projection (852 S3): the version pager is read from the store's own
      // parent pointers, which `mergeStoreConversations` deliberately does not carry across.
      this.conversations = state.conversations;
    });
    void loadConversations();
    this.restoreLastViewed();
    // Cold-load reattach, started here and NOT awaited: discovery is a network round-trip now
    // (tempdoc 834 §15.3) and mount must not block on it. The ordering it used to buy — the run
    // already on the controller before the first presence look — is instead carried by the
    // subscription taken just above: `attachToRun` notifies, `onAgentUpdate` runs
    // `syncRunPresence` first, and a recovered run reaches the window that way.
    void this.reattachLiveRun();
    // The window may be mounting BESIDE a run that is already going (a surface switch, a re-mount).
    // The store notifies on change only, so the first look has to be taken here — see
    // `syncRunPresence` for why an unrepresented live run is this window's problem to state.
    this.syncRunPresence();
    // Scoped to the HOST, not to `window`. A host listener is only reached by events whose composed
    // path runs through this window, so a chord pressed anywhere else in the shipped app is invisible
    // here by construction — there is no "is the focus inside?" test to get wrong. Capture phase so
    // the palette's own field cannot swallow the chord before the window sees it.
    this.addEventListener('keydown', this.onHostKeydown, true);
    this.addEventListener('focusout', this.onHostFocusOut);
  }

  override disconnectedCallback(): void {
    // BEFORE `super`, which is what tears the shadow tree's controllers down: the reassurance has to
    // read the draft while the composer still holds it (tempdoc 609 §R T1.4 — instance retention
    // keeps the draft, and this is what makes that invisible guarantee legible, once per session).
    notifyDraftKeptOnce(SV3_SURFACE_KEY, this.currentDraft().trim().length > 0);
    // Tempdoc 609 Phase 4 (inventory G11) — a torn-down stream is no longer live, so the app-wide
    // activity indicator must not be left claiming this window's work is still going.
    if (this.streaming) this.settleAiActivity();
    super.disconnectedCallback();
    releaseSv3MorphSheet();
    this.convListUnsubscribe?.();
    this.convListUnsubscribe = null;
    this.recordAbort?.abort();
    this.recordAbort = null;
    this.historyAbort?.abort();
    this.historyAbort = null;
    this.searchUnsubscribe?.();
    this.searchUnsubscribe = null;
    this.aiUnsubscribe?.();
    this.aiUnsubscribe = null;
    this.uiModeUnsubscribe?.();
    this.uiModeUnsubscribe = null;
    this.appearanceUnsubscribe?.();
    this.appearanceUnsubscribe = null;
    // The RUN is not cancelled here. Unlike the ask stream (which this window owns), a delegated run
    // is hosted by the product-wide controller and may be watched from another surface; tearing it
    // down because this dev window unmounted would be this window deciding for the whole product.
    this.agentUnsubscribe?.();
    this.agentUnsubscribe = null;
    // Lifecycle containment: an unmounted window's stream would keep a connection open against the
    // shared channel budget and settle a turn nobody can see.
    this.abortAsk();
    // The controller runs a 1s tick while the model is thinking; an unmounted window's tick would go
    // on requesting updates on a detached element forever.
    this.askReasoning.destroy();
    this.boxObserver?.disconnect();
    this.boxObserver = null;
    this.removeEventListener('keydown', this.onHostKeydown, true);
    this.removeEventListener('focusout', this.onHostFocusOut);
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
    });
    this.boxObserver.observe(this);
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
    if (changed.has('sessions')) this.upgradeOpenPaneAnchor();
  }

  protected override updated(changed: Map<string, unknown>): void {
    if (changed.has('apiBase')) {
      setSearchApiBase(this.apiBase || '');
      setConversationApiBase(this.apiBase || '');
    }
    if (changed.has('sidebarWidthPx')) this.applySidebarWidth(this.sidebarWidthPx);
    if (changed.has('paneWidthPx')) this.applyPaneWidth(this.paneWidthPx);
    // The restored draft reaches the composer on the first update that produced one. Once only: a
    // later re-application would clobber what the reader has typed since.
    if (this.draftSeedPending) {
      const composer = this.composer;
      if (composer !== null) {
        this.draftSeedPending = false;
        composer.draft = this.draftSeed;
      }
    }
    // Tempdoc 610 §K — keep the OPEN inspector current. Its subject is "what the last turn sent", so
    // a new turn, a moved floor or a hidden turn changes it; a drawer left showing the previous
    // answer's prompt would be the stalest possible answer to the one question it exists to answer.
    if (isContextInspectorOpen()) setContextInspectorView(this.buildContextInspectorView());
  }

  /* ── The conversation record (tempdoc 822 Phase F6) ───────────────────────────────────────── */

  /**
   * A3 (tempdoc 609 Phase 3) — restore the conversation THIS TAB was reading. The pointer is per-tab
   * `sessionStorage`, deliberately not the globally-most-recent conversation: a second tab must land
   * cold rather than adopt what another tab had open.
   *
   * The session is created from the pointer alone, before the store list arrives, so the claim can
   * happen in this tick; {@link mergeStoreConversations} fills its real title when the list lands and
   * the record fetch fills its transcript. A pointer to a conversation that no longer exists resolves
   * to an empty record, which leaves the placeholder row and says nothing false.
   */
  private restoreLastViewed(): void {
    const id = readLastViewedConversation();
    if (id === null || sessionById(this.sessions, id) !== null) return;
    const now = Date.now();
    this.sessions = mergeStoreConversations(this.sessions, [
      { id, title: null, firstUserMessage: '', createdAt: now, lastActiveAt: now },
    ]);
    this.sessions = focusSession(this.sessions, id, now);
    setActiveConversation(id);
    void this.setComposerState('docked');
    void this.refreshRecord(id);
    void this.refreshHistory(id);
  }

  /**
   * D1 — project the conversation from its canonical record. The window is not the authority: the
   * turns it renders for a settled conversation are `GET /api/thread/{id}` projected through the two
   * SHARED authorities, so a run's history outlives the controller that produced it and survives a
   * reload the controller singleton does not.
   *
   * D2 (tempdoc 727 F-8) — a failed fetch returns EMPTY by contract precisely so it cannot wipe the
   * caller's live state, which is right and was also completely silent. `onFailure` is the out-of-band
   * signal; the notice is raised and the merge is SKIPPED, so a failure never re-renders the thread.
   */
  private async refreshRecord(conversationId: string): Promise<void> {
    this.recordAbort?.abort();
    const abort = new AbortController();
    this.recordAbort = abort;
    let failed = false;
    const record = await fetchUnifiedThread(this.apiBase, conversationId, abort.signal, () => {
      failed = true;
    });
    // A superseded claim's response must not land on the conversation the reader moved to.
    if (abort.signal.aborted) return;
    if (this.recordAbort === abort) this.recordAbort = null;
    this.recordNotice = failed;
    if (failed) return;
    this.sessions = applySv3Record(
      this.sessions,
      conversationId,
      projectSv3RecordTurns(record.events),
    );
  }

  /**
   * The `/history` COMPANION load (tempdoc 852 §2.3c). The shipped window reads both records at
   * adjacent lines (`views/UnifiedChatView.ts:2048-2049`); this one read only the thread, so a
   * conversation's branch lineage, its effective-context floor and its two exclusion ledgers were
   * never on the wire it listened to. Nothing renders them yet — S2/S3 do — but a window that cannot
   * ask for them cannot grow an affordance that needs them.
   *
   * On CLAIM, not per turn: these are properties of a conversation, and re-asking at every terminal
   * would spend a round trip per answer to re-read facts that a turn does not change.
   *
   * `claim: false`, and the pointer is never touched here. `resumeConversation` claims the app-wide
   * active conversation as a side effect of a successful read, which is right for the shipped
   * window's open path and wrong for a companion load: a slow read landing after the reader moved on
   * — to another v3 conversation, to New session, or into the OTHER window, which claims the same
   * shared pointer — would re-point the product at the conversation they walked away from. Reading
   * without claiming removes the race rather than compensating for it afterwards; this window
   * already claims at open ({@link claimConversation}).
   *
   * TWO GUARDS, because there are two ways an answer can be stale (852 S3, closing S2's F5).
   *
   *  - **Session** — the reader moved on, and these fields belong to a conversation that is no longer
   *    the one on screen. That guard shipped with S1.
   *  - **ORDER** — two reloads of the SAME conversation can land out of order, and the older answer
   *    would then stand. S2 recorded this as F5 and named the fix: the record half's generation
   *    token. Branch and edit multiply the reload rate (every act is a write followed by a reload,
   *    and an edit is two acts in a row), so the same discipline applies here: each load supersedes
   *    the one before it, and a superseded load's answer is DROPPED however late it arrives.
   *
   * HONEST LIMIT on the order guard: `resumeConversation` accepts no signal, so the superseded
   * REQUEST is not cancelled — only its answer is discarded. That is what F5 named (ordering), and
   * the request that is still in flight costs a round trip and changes nothing.
   *
   * HONEST LIMIT: `resumeConversation` reports a failed read as an empty resume by contract, so a
   * conversation whose history could not be read is recorded as one with no floor and no parent
   * rather than as one that was not told. The record half has {@link recordNotice} for that; this
   * half would need the shared store to distinguish the two, which is not S1's to change.
   *
   * OBLIGATION ON S2/S3: every one of these fields is mutable by an affordance those slices ship —
   * setting or clearing a floor, compacting, excluding a message or a source. Each must re-run this
   * load after the write lands, or the window renders a floor the backend no longer holds.
   */
  private async refreshHistory(conversationId: string): Promise<void> {
    this.historyAbort?.abort();
    const abort = new AbortController();
    this.historyAbort = abort;
    const history = await resumeConversation(conversationId, SV3_ASK_SHAPE_ID, { claim: false });
    if (abort.signal.aborted) return;
    if (this.historyAbort === abort) this.historyAbort = null;
    if (this.sessions.activeId !== conversationId) return;
    this.sessions = applySv3History(this.sessions, conversationId, {
      parentSessionId: history.parentSessionId,
      branchPointMessageId: history.branchPointMessageId,
      parentFirstUserMessage: history.parentFirstUserMessage,
      contextFloor: history.contextFloor,
      contextFloorSummary: history.contextFloorSummary,
      excludedMessageIds: history.excludedMessageIds,
      excludedSourceIds: history.excludedSourceIds,
      locked: history.locked,
    });
  }

  /* ── The effective context (tempdoc 610, ported by 852 S2) ────────────────────────────────── */

  /** The claimed conversation, or null when nothing is claimed. */
  private get activeSession(): Sv3Session | null {
    const id = this.sessions.activeId;
    return id === null ? null : sessionById(this.sessions, id);
  }

  /**
   * What the effective context does with each turn on screen. Computed ONCE per render and handed to
   * both consumers (the transcript's frames, the bar's aggregate) plus every act below, so what the
   * window renders and what it writes are derived from one reading of one record.
   */
  private turnContexts(): readonly Sv3TurnContext[] {
    const session = this.activeSession;
    if (session === null) return [];
    return projectSv3TurnContexts(session.turns, session.history);
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
    const contexts = this.turnContexts();
    const items = sv3ContextMenuItems(contexts, detail.turnId, {
      compacting: this.compacting,
      streaming: this.streaming,
      contextFloor: session.history?.contextFloor ?? null,
      hasSummary: (session.history?.contextFloorSummary ?? null) !== null,
    });
    // ONE menu for both sets (852 S3). They are two derivations because they are gated on different
    // ids — a turn can be excludable and not forkable — but a turn has one ⋯, and a second overflow
    // beside the first would be a second place to look for "what can I do with this turn".
    const branch = sv3BranchMenuItems(this.turnLineage(), detail.turnId, {
      streaming: this.streaming,
    });
    if (items.length === 0 && branch.length === 0) return;
    const chosen = await openContextMenu({
      // The branch acts lead: they fork the conversation, and the context acts change what the next
      // prompt carries within it. The reference window orders them the same way.
      actions: [
        ...branch.map((item) => ({
          id: item.id,
          label: item.label,
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
      await this.runBranchAction({ action: chosen, turnId: detail.turnId });
      return;
    }
    await this.runContextAction({ action: chosen as Sv3ContextActionId, turnId: detail.turnId });
  }

  private onContextAction(event: Event): void {
    const detail = (event as CustomEvent<Sv3ContextAction>).detail;
    if (detail === undefined) return;
    void this.runContextAction(detail);
  }

  /**
   * The five acts of tempdoc 610, and the two reads beside them. Each write goes through the SHARED
   * store function that owns its endpoint (this window mints no request), and — the obligation 852
   * S1 recorded when it loaded `/history` without rendering it — **every write is followed by a
   * re-load of that record**. The backend is the authority on the floor and the exclusions; a
   * window that patched its own copy instead would keep rendering a floor a failed or partial write
   * never established, and would drift from the other client the moment there is one.
   */
  private async runContextAction(detail: Sv3ContextAction): Promise<void> {
    const session = this.activeSession;
    if (session === null) return;
    const sessionId = session.id;
    if (detail.action === 'inspect') {
      this.openContextInspector();
      return;
    }
    const contexts = this.turnContexts();
    const turn = detail.turnId === undefined ? null : sv3TurnContextFor(contexts, detail.turnId);
    switch (detail.action) {
      case 'floor': {
        const messageId = turn?.floorMessageId ?? null;
        if (messageId === null) return;
        await this.settleContextWrite(
          sessionId,
          await setContextFloor(sessionId, messageId),
          CONTEXT_FLOOR_FAILED,
        );
        return;
      }
      case 'restore':
        await this.settleContextWrite(
          sessionId,
          await clearContextFloor(sessionId),
          CONTEXT_RESTORE_FAILED,
        );
        return;
      case 'compact': {
        const messageId = turn?.floorMessageId ?? null;
        if (messageId === null || this.compacting) return;
        this.compacting = true;
        try {
          // The summary comes back on the response, and is then re-read from `/history` like every
          // other field: one place decides what the floor currently says.
          const summary = await compactContext(sessionId, messageId);
          await this.settleContextWrite(sessionId, summary !== null, CONTEXT_COMPACT_FAILED);
        } finally {
          this.compacting = false;
        }
        return;
      }
      case 'summary':
        if (detail.text === undefined) return;
        await this.settleContextWrite(
          sessionId,
          await editContextFloorSummary(sessionId, detail.text),
          CONTEXT_SUMMARY_FAILED,
        );
        return;
      case 'exclude':
      case 'include': {
        const ids = turn?.messageIds ?? [];
        if (ids.length === 0) return;
        const excluded = detail.action === 'exclude';
        // Both of a turn's messages, because the reader hid a TURN: leaving the question in the
        // prompt while dropping the answer would send the model a question it never answered.
        const ok = await this.excludeInTurn(sessionId, ids, excluded);
        await this.settleContextWrite(
          sessionId,
          ok,
          excluded ? CONTEXT_EXCLUDE_FAILED : CONTEXT_INCLUDE_FAILED,
        );
        return;
      }
      case 'include-all': {
        const ids = sv3ExcludedMessageIds(contexts);
        if (ids.length === 0) return;
        const ok = await this.excludeInTurn(sessionId, ids, false);
        await this.settleContextWrite(sessionId, ok, CONTEXT_INCLUDE_FAILED);
        return;
      }
      default:
        return;
    }
  }

  /**
   * Several exclusion toggles, ONE AT A TIME — never `Promise.all`.
   *
   * The endpoint's write is read-modify-write over a SHARED document and the store takes no lock:
   * `FileConversationStore.toggleStringInMeta` reads `meta.json`, adds or removes the id, and calls
   * `writeMetaAtomic` (`:503-527`). The write is atomic per file; the SEQUENCE is not, and each POST
   * is served on its own HTTP thread. Two toggles in flight together therefore race on one snapshot,
   * and the loser's id is silently dropped.
   *
   * What that costs here is specific and invisible: a turn's two ids go out together, one lands, and
   * the turn is left HALF excluded — the prompt carries a question whose answer was dropped, while
   * the transcript dims the turn either way (`hasExcluded` is true on one id as on two). Serializing
   * removes the race rather than detecting it afterwards, and it is what the shipped window already
   * does (`views/UnifiedChatView.ts:1767-1776`, a sequential `for`-await).
   *
   * Returns whether EVERY toggle landed; the caller reloads `/history` either way.
   */
  private async excludeInTurn(
    sessionId: string,
    ids: readonly string[],
    excluded: boolean,
  ): Promise<boolean> {
    let ok = true;
    for (const id of ids) {
      if (!(await setMessageExcluded(sessionId, id, excluded))) ok = false;
    }
    return ok;
  }

  /**
   * The tail every context write shares: RELOAD, then report a refusal. The reload runs whether or
   * not the write landed — a partially-applied bulk exclusion is exactly the case where the window's
   * own idea of the ledger is least trustworthy — and the toast names the act, because the remedies
   * differ (a refused floor is the store; a compaction that returned nothing is usually the model).
   */
  private async settleContextWrite(
    sessionId: string,
    ok: boolean,
    failure: string,
  ): Promise<void> {
    await this.refreshHistory(sessionId);
    if (ok) return;
    emitEphemeralToast({ message: failure, severity: 'warning' });
  }

  /* ── Branch, edit / retry and the version pager (852 S3) ──────────────────────────────────── */

  /**
   * What each turn can do about branching, derived once per render from the SAME three inputs the
   * acts below read: the turns on screen, the conversation's `/history`, and the store's own rows.
   * The trigger's gate and the act's target are therefore one derivation, which is what stops a menu
   * offering a fork the window would then compute differently.
   */
  private turnLineage(): readonly Sv3TurnLineage[] {
    const session = this.activeSession;
    if (session === null) return [];
    return projectSv3TurnLineage(session.turns, session.history, session.id, this.conversations);
  }

  private onBranchAction(event: Event): void {
    const detail = (event as CustomEvent<Sv3BranchAction>).detail;
    if (detail === undefined) return;
    void this.runBranchAction(detail);
  }

  /**
   * The three acts, all of them ONE backend act with a different `fromMsgId` and a different thing
   * to do afterwards (`views/UnifiedChatView.ts:1471-1497` — there is no edit endpoint and no retry
   * endpoint, and inventing one here would be inventing a contract the backend does not hold).
   *
   *  - **Branch** forks after this turn's ANSWER and continues there; nothing is re-sent.
   *  - **Retry** forks before this turn's QUESTION and re-sends it unchanged.
   *  - **Edit** forks at the same point and sends the rewrite, so the new text is the first
   *    divergent message rather than a second one appended below the old exchange.
   *
   * The ids come from {@link projectSv3TurnLineage} and nowhere else, so an act can never be pointed
   * at a message `?fromMsgId=` would reject — and a turn that names none renders no affordance to
   * press in the first place.
   */
  private async runBranchAction(detail: Sv3BranchAction): Promise<void> {
    const session = this.activeSession;
    if (session === null || this.streaming) return;
    const lineage = sv3LineageFor(this.turnLineage(), detail.turnId);
    if (lineage === null) return;
    const turn = session.turns.find((t) => t.id === detail.turnId) ?? null;
    switch (detail.action) {
      case 'branch':
        if (lineage.branchFromId === null) return;
        await this.branchInto(session.id, lineage.branchFromId, null);
        return;
      case 'retry': {
        const question = turn?.question ?? '';
        if (lineage.forkKey === null || question === '') return;
        await this.branchInto(session.id, lineage.forkKey, question);
        return;
      }
      case 'edit': {
        const text = (detail.text ?? '').trim();
        if (lineage.forkKey === null || text === '' || !lineage.canEdit) return;
        await this.branchInto(session.id, lineage.forkKey, text);
        return;
      }
      default:
        return;
    }
  }

  /**
   * Fork, open the fork, and — for the two acts that re-send — ask again IN it.
   *
   * ORDER IS THE WHOLE THING. The branch is opened and CLAIMED before the re-send, because
   * {@link runAsk} appends to whatever conversation is active: sending first would append the
   * rewritten question to the conversation the reader was replacing, which is the failure this act
   * exists to avoid and would look entirely plausible on screen.
   *
   * A refused branch changes nothing and says so. There is no local fallback — a window that
   * appended the re-send to the current conversation "because the branch failed" would silently do
   * the one thing the reader did not ask for.
   */
  private async branchInto(
    sessionId: string,
    fromMsgId: string,
    resend: string | null,
  ): Promise<void> {
    // The preview is the conversation's OPENING question, the same thing the shipped window sends
    // (`:1485`): it is what makes the new session surface as a recent one with a name.
    const preview = this.activeSession?.turns.find((t) => t.question !== '')?.question ?? '';
    const branched = await branchConversation(sessionId, fromMsgId, preview);
    if (branched === null) {
      emitEphemeralToast({ message: BRANCH_FAILED, severity: 'warning' });
      return;
    }
    await this.openBranch(branched);
    if (resend !== null) await this.runAsk(resend);
  }

  /**
   * Open a conversation this window has just created. The same four moves a row click makes
   * ({@link onSessionSelect}) — project the row, focus it, claim it, load both records — plus the
   * one a row click does not need: RE-LIST.
   *
   * The re-list is what makes the fork visible. A branch's siblings are read from the store's own
   * `parentSessionId`/`branchPointMessageId` pointers, and the row carrying them does not exist in
   * this window's copy of the list until `listSessions` returns it — so without this the pager would
   * render nothing on the very fork the reader just made.
   */
  private async openBranch(id: string): Promise<void> {
    const prevId = this.sessions.activeId;
    const now = Date.now();
    this.sessions = mergeStoreConversations(this.sessions, [
      { id, title: null, firstUserMessage: '', createdAt: now, lastActiveAt: now },
    ]);
    this.sessions = focusSession(this.sessions, id, now);
    this.claimConversation(id);
    this.recordNotice = false;
    // The same rule `onSessionSelect` applies: an edit in another row is DROPPED rather than
    // committed, because navigating away must not write text the reader walked away from.
    this.renamingId = null;
    // 857 D4 (drafted as 854) — same guard as `onSessionSelect`: close only on a real switch, using the id captured
    // before the claim above (`this.sessions === before` identity is not this method's guard — its
    // callers already establish it is a real switch or a branch that's always new, but comparing ids
    // directly here keeps the two sites' close logic identical rather than relying on caller proofs).
    if (prevId !== id) this.closePane();
    void this.setComposerState('docked');
    await Promise.all([this.refreshRecord(id), this.refreshHistory(id), loadConversations()]);
  }

  /**
   * The reader paged to another version of a turn. It is an OPEN, not a write: the target is a real
   * conversation and claiming it is what claiming any conversation does, so this routes through the
   * one open path rather than a second, branch-flavoured one.
   */
  private onVersionSelect(event: Event): void {
    const id = (event as CustomEvent<Sv3VersionSelect>).detail?.sessionId ?? '';
    if (id === '' || id === this.sessions.activeId) return;
    void this.openBranch(id);
  }

  /**
   * Tempdoc 610 §K — open the shared inspector on what the last completed turn actually sent. The
   * projection is pushed rather than subscribed to, the same way the shipped window does it, and it
   * is re-pushed on every update while the drawer is open so a new turn does not leave it stale.
   */
  private openContextInspector(): void {
    setContextInspectorView(this.buildContextInspectorView());
    toggleContextInspector();
  }

  private buildContextInspectorView(): InspectorView {
    const session = this.activeSession;
    return projectSv3ContextInspector(
      session?.turns ?? [],
      this.turnContexts(),
      session?.history ?? null,
      session?.contextUsage ?? null,
      this.aiSnapshot?.runtime.contextWindow ?? null,
    );
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
  private async reattachLiveRun(): Promise<void> {
    if (this.reattachChecked) return;
    this.reattachChecked = true;
    const existing = peekAgentSessionController();
    if (existing !== null) {
      void existing.reattachActiveRunOnLoad();
      return;
    }
    if ((await discoverLiveAgentRun(this.apiBase)) === null) return;
    void getAgentSessionController(this.apiBase).reattachActiveRunOnLoad();
    // The mount-time presence look already ran, synchronously, while this round-trip was still in
    // flight — and it found no controller because there was none yet to find. Take it again now
    // that there is one, so a recovered run reaches the window on this path directly rather than
    // only through whatever the controller happens to notify next.
    this.syncRunPresence();
  }

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
    if (!locked) this.lockedRefusal = false;
  }

  /**
   * The 423 path's own half. The poll is up to ~10s behind, and a refusal is the SERVER saying the
   * store is locked right now — so the window adopts it immediately rather than leaving the
   * transcript readable until the next poll agrees.
   */
  private noteRefusedWhileLocked(): void {
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
    return this.sidebarCollapsed ? SV3_SIDEBAR_COLLAPSED_PX : this.sidebarWidthPx;
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
    let frame = 0;
    this.resizing = true;
    const move = (moved: PointerEvent): void => {
      width = clampSv3PaneWidth(startWidth - (moved.clientX - startX), available, sidebar);
      if (frame !== 0) return;
      frame = requestAnimationFrame(() => {
        frame = 0;
        this.applyPaneWidth(width);
      });
    };
    const stop = (): void => {
      grip.removeEventListener('pointermove', move);
      grip.removeEventListener('pointerup', up);
      grip.removeEventListener('pointercancel', cancel);
      if (frame !== 0) cancelAnimationFrame(frame);
      frame = 0;
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

  private onSidebarToggle(): void {
    this.setSidebarCollapsed(!this.sidebarCollapsed);
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
    const available = this.availableWidth();
    this.paneOverlay = !sv3PaneIsInline(available);
    this.paneWidthPx = clampSv3PaneWidth(this.paneWidthPx, available, this.sidebarOccupiedWidth());
    this.applyPaneWidth(this.paneWidthPx);
    this.paneDocPath = detail.docPath;
    this.paneCitation = detail.anchor ?? null;
    this.paneSource = { turnId: detail.turnId, sourceIndex: detail.sourceIndex };
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

  /**
   * The pane's exits — its own close control (`pane-close`, re-raised by the region) and Escape. The
   * chosen width is NOT forgotten here: closing a document is not withdrawing a boundary preference.
   */
  private closePane(): void {
    this.paneDocPath = null;
    this.paneCitation = null;
    this.paneSource = null;
    this.paneCitationHeader = null;
  }

  private readonly onHostKeydown = (event: KeyboardEvent): void => {
    // ESCAPE ORDER — rename > pane > palette > composer flip. The MOST LOCAL transient state wins:
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
    if (event.key === 'Escape' && renamingRowInPath(event)) return;
    // An open control menu is served the same way and for the same reason: it is the most local
    // transient the reader is inside, it closes itself (`Sv3Composer.onMenuKeydown`), and without
    // this yield the same keystroke would close the PANE behind it (F9's DEFECT-7, one control on).
    if (event.key === 'Escape' && openControlMenuInPath(event)) return;
    if (event.key === 'Escape' && this.paneDocPath !== null) {
      event.preventDefault();
      event.stopPropagation();
      this.closePane();
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
    if (this.steerableRun !== null) {
      this.steerLiveRun(text);
      return;
    }
    if (detail?.tier === 'delegate') {
      this.delegate(text);
      return;
    }
    void this.runAsk(text);
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
    const availability = projectAvailability('documents', this.aiSnapshot);
    return availability.kind === 'unavailable' ? availability.reason : '';
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
  private get delegateUnavailableReason(): string {
    const availability = projectAvailability('agent', this.aiSnapshot);
    return availability.kind === 'unavailable' ? availability.reason : '';
  }

  /**
   * The ONE path a question takes. There is no second entry: the composer refuses an unavailable or
   * busy send before it leaves, so this method is not a second gate re-deciding the same question —
   * it opens the turn, dispatches through the window's single ask site, and settles that turn.
   */
  private async runAsk(rawQuestion: string): Promise<void> {
    const question = rawQuestion.trim();
    if (question === '') return;
    this.sessions = submitInSession(this.sessions, question, Date.now(), 'ask', createConversationId());
    const ref = latestTurnRef(this.sessions);
    if (ref === null) return;
    this.claimConversation(ref.sessionId);
    this.composer?.clearDraft();
    void this.setComposerState('docked');

    const abort = new AbortController();
    this.askAbort = abort;
    this.streaming = true;
    // The controller is per-ASK, not per-turn: it accumulates and times one stream's thinking, and
    // the blocks it finalizes are written onto the turn below. Resetting here is what stops the
    // previous answer's thinking appearing under this one.
    this.askReasoning.reset();
    this.askReasoningTurnId = ref.turnId;
    // The app-wide activity indicator, raised for the duration and settled at the terminal below
    // (inventory G11). Cancelling through the SAME abort handle Stop uses, so there is one way to
    // stop this stream however the reader reaches it.
    setAiActivity({
      state: 'streaming',
      shapeId: SV3_ASK_SHAPE_ID,
      startedAtMs: Date.now(),
      canCancel: true,
      cancel: () => abort.abort(),
    });
    // Every terminal below settles the SAME ref the dispatch opened, so a reader who claims another
    // session mid-stream still gets the answer written where it was asked.
    const settle = (
      status: 'complete' | 'halted' | 'refused' | 'failed',
      detail = '',
    ): void => {
      // The thinking is closed out and RECORDED on the turn at every terminal, including a halt: what
      // the model thought before the reader stopped it was really produced, and dropping it would
      // lose evidence the window actually received (inventory C9).
      this.askReasoning.finalize();
      this.sessions = setTurnReasoning(this.sessions, ref, [...this.askReasoning.reasoningBlocks]);
      this.sessions = settleTurn(
        this.sessions,
        ref,
        status,
        Date.now(),
        detail,
        // The model AS OF THIS TERMINAL (inventory C1). Recorded rather than read at render, so a
        // transcript re-read after a model swap still names the one that wrote each answer.
        this.currentModelLabel,
      );
      // Only THIS dispatch's terminal may clear the window's busy state: a later ask has already
      // installed its own controller, and clearing it here would strand a live stream with a Send
      // control in the slot.
      if (this.askAbort === abort) {
        this.askAbort = null;
        this.streaming = false;
        this.askReasoningTurnId = null;
        this.settleAiActivity();
      }
    };
    await sv3Ask(
      {
        apiBase: this.apiBase,
        question,
        conversationId: ref.sessionId,
        // The rung AS OF THIS SEND (tempdoc 822 Phase F10). Read here rather than inside the ask
        // client, so the one place a question is dispatched is also the one place its parameters
        // are decided — and a rung changed mid-stream cannot rewrite a request already in flight.
        effort: this.effort,
        signal: abort.signal,
      },
      {
        onDelta: (text) => {
          this.sessions = appendTurnDelta(this.sessions, ref, text);
        },
        onEvidence: (evidence) => {
          this.sessions = setTurnEvidence(this.sessions, ref, evidence);
        },
        onReasoning: (payload) => {
          this.askReasoning.handleReasoningChunk(payload);
        },
        onRewrite: (standalone) => {
          this.sessions = setTurnRewrite(this.sessions, ref, standalone);
        },
        onDone: (usage) => {
          // Tempdoc 610 §E.4 — the occupancy this turn reported, recorded on the CONVERSATION that
          // spent it (a terminal that reported none leaves the previous reading standing rather than
          // replacing it with a zero the backend never sent).
          if (usage !== null) {
            this.sessions = setSessionContextUsage(this.sessions, ref.sessionId, usage);
          }
          // The answer has arrived, so the thinking that preceded it is over — the shipped window
          // ends it on the first content chunk; ending it at the terminal is the same edge for a
          // window whose transcript renders the block beside the answer rather than instead of it.
          settle('complete');
          // The record now holds this turn; the store now holds the conversation. Both refreshed so
          // a reload projects exactly what is on screen (inventory A1 + D1).
          void loadConversations();
          void this.refreshRecord(ref.sessionId);
          void this.maybeGenerateTitle(ref.sessionId);
        },
        // The lock's refusal is worded by the ONE reason vocabulary, not re-phrased here.
        onRefused: () => {
          settle('refused', reasonFor('conversations.locked').wording);
          // The SERVER just said the store is locked, which is newer than any poll (inventory E4/E5).
          this.noteRefusedWhileLocked();
        },
        onHalted: () => settle('halted'),
        onFailed: (message) => settle('failed', message),
      },
    );
  }

  /**
   * The composer announced a new effort rung (tempdoc 822 Phase F10). The window keeps it, and the
   * NEXT dispatch carries it — an in-flight stream is never re-parameterised, because its request
   * has already left.
   */
  private onEffortChange(event: Event): void {
    const effort = (event as CustomEvent<Sv3EffortChange>).detail?.effort;
    if (!isSv3Effort(effort)) return;
    this.effort = effort;
  }

  /**
   * The composer announced a new send TIER (852 S4). Validated rather than trusted, exactly as the
   * effort rung is: the event crosses a shadow boundary and an unknown tier would otherwise fall
   * through to `runAsk` and silently ask when the reader chose to delegate.
   */
  private onTierChange(event: Event): void {
    const tier = (event as CustomEvent<Sv3TierChange>).detail?.tier;
    if (!isSv3Tier(tier)) return;
    this.tier = tier;
  }

  /** Halting is always the reader's; the turn settles `halted` through the sink's own terminal. */
  private abortAsk(): void {
    this.askAbort?.abort();
  }

  /**
   * A11 — ask the model to name the conversation, through the store's own authority
   * (`generateConversationTitle`, which dispatches a throwaway free-chat turn, cleans it up, and
   * writes the result to `setConversationTitle`). The window neither prompts nor parses: it decides
   * WHETHER, and `sv3-sessions.ts`'s `sv3ShouldGenerateTitle` is where that decision lives.
   *
   * ONCE per conversation, and never over a rename. The second guard runs AFTER the call returns as
   * well: naming takes a model round-trip, and a reader who renamed the row while it was in flight
   * has answered the same question better — so their title is written back rather than lost to a
   * result that was already stale when it landed.
   */
  private async maybeGenerateTitle(sessionId: string): Promise<void> {
    if (this.titledSessionIds.has(sessionId)) return;
    const session = sessionById(this.sessions, sessionId);
    if (session === null || !sv3ShouldGenerateTitle(session)) return;
    this.titledSessionIds.add(sessionId);
    const turn = session.turns.find((t) => t.kind === 'ask' && t.status === 'complete');
    if (turn === undefined) return;
    const generated = await generateConversationTitle(sessionId, turn.question, turn.answer);
    if (generated === null) return;
    const after = sessionById(this.sessions, sessionId);
    // The reader's name goes back as THEIRS, not as the model's: adopting the auto provenance here
    // would license the next reload's first ask to name the conversation over them (tempdoc 838).
    if (after !== null && after.renamed) void setConversationTitle(sessionId, after.title, 'user');
  }

  /**
   * A10 — the conversation as Markdown, on the clipboard. Both halves are the product's:
   * `exportConversationMarkdown` is the ONE serialisation (the shipped window's export writes the
   * same bytes) and `copyToClipboard` is the ONE write. It lives in the PALETTE rather than in a
   * per-conversation control, which is the spec's economy for a real capability with no resting
   * chrome to spend on it.
   */
  private exportActiveConversation(): void {
    const active = this.sessions.activeId;
    const session = active === null ? null : sessionById(this.sessions, active);
    if (session === null || session.turns.length === 0) return;
    // A turn that has not settled is NOT exported as an answer: whatever is on screen mid-stream is a
    // fragment, and a markdown file that recorded it as the assistant's reply would be a record of
    // something that never finished being said.
    const thread = session.turns
      .filter((turn) => turn.status !== 'streaming')
      .flatMap((turn) => [
        { role: 'user', content: turn.question },
        { role: 'assistant', content: turn.answer },
      ]);
    if (thread.length === 0) return;
    void copyToClipboard(exportConversationMarkdown(thread, session.title));
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
   * The live thinking, whichever tier is producing it (tempdoc 848 §2.8). The ask stream's own
   * controller while an ask streams; otherwise the SHARED agent controller's, which has been
   * accumulating a delegated run's reasoning all along with nothing reading it — a run showed no
   * thinking live while a settled one now shows it from the record, which is the same asymmetry
   * inverted. One reasoning surface, both tiers.
   */
  private liveReasoning(): ReasoningController | null {
    if (this.streaming) return this.askReasoning;
    if (this.run === null) return null;
    return this.agentController()?.reasoning ?? null;
  }

  /** The turn that owns {@link liveReasoning}'s controller — the id the transcript binds it to. */
  private liveReasoningTurnId(): string | null {
    if (this.streaming) return this.askReasoningTurnId;
    return this.run?.turnId ?? null;
  }

  /**
   * The live run this window owns AND that accepts a steer, or null. Both halves matter: the
   * controller is product-wide, so a run this window did not dispatch is not this window's to
   * redirect, and only an `agent` run has an interject channel at all (a workflow or background run
   * does not) — the seam's own lifecycle predicate decides the rest.
   */
  private get steerableRun(): AgentSessionController | null {
    const ctrl = this.agentController();
    if (ctrl === null || this.run === null) return null;
    if (ctrl.runKind !== 'agent') return null;
    return directiveAvailable(ctrl, { kind: 'interject', text: '' }) ? ctrl : null;
  }

  /**
   * The DELEGATE path: the draft becomes an agent task. The causal order mirrors the ask — the turn
   * is opened first, then the run is dispatched — so the transcript already shows what was committed
   * before anything can come back, and the run has a turn to be written to from its very first frame.
   *
   * The run is HOSTED, never re-implemented: the shared `AgentSessionController` runs it and every
   * directive leaves through the ONE `dispatchRunControl` seam (`governance/steering-surfaces.v1.json`).
   */
  private delegate(text: string): void {
    // Defence in depth, not a second gate: the composer already refuses an unavailable delegate and
    // keeps the draft. This exists because `delegate` is reachable from the window's own routing.
    if (this.delegateUnavailableReason !== '') return;
    this.sessions = submitInSession(this.sessions, text, Date.now(), 'agent', createConversationId());
    const ref = latestTurnRef(this.sessions);
    if (ref === null) return;
    this.claimConversation(ref.sessionId);
    this.composer?.clearDraft();
    void this.setComposerState('docked');

    const ctrl = getAgentSessionController(this.apiBase);
    // The run's thread events are stamped with THIS window's session, so a delegated run lands under
    // the conversation that asked for it rather than under whatever the controller ran last.
    ctrl.conversationId = ref.sessionId;
    this.run = {
      sessionId: ref.sessionId,
      turnId: ref.turnId,
      // The window's slice of a product-wide conversation: everything before this belongs to someone
      // else's run and must never be counted into this one's receipt.
      entryStart: ctrl.conversation.length,
      sessionIdAtDispatch: ctrl.sessionId,
      acknowledged: false,
      haltRequested: false,
      haltDispatched: false,
    };
    this.runLive = false;
    this.requestUpdate();
    void dispatchRunControl(ctrl, { kind: 'initiate', prompt: text });
  }

  /**
   * A mid-run submit JOINS the live turn. Through the seam, like every other per-run directive.
   *
   * Named `steerLiveRun` and not `steer` on purpose: the steering register bans a bare `.steer(` call
   * anywhere but the seam, and `this.steer(` would read as exactly that to the gate's scan. A method
   * whose name makes a correct call look like the forbidden one is a trap for the next reader too.
   */
  private steerLiveRun(text: string): void {
    const ctrl = this.steerableRun;
    if (ctrl === null) return;
    this.composer?.clearDraft();
    void dispatchRunControl(ctrl, { kind: 'interject', text });
  }

  /**
   * The reader's stop. Recorded FIRST and dispatched second, because the record of the decision is
   * what makes `halted` an honest receipt outcome — and because a stop pressed before the stream
   * opened cannot be delivered yet (the seam's predicate refuses a halt on a run with no abort handle
   * yet). Remembering it means the next update delivers it, instead of the decision being dropped.
   */
  private haltRun(): void {
    const local = this.run;
    if (local === null) return;
    local.haltRequested = true;
    this.deliverHalt();
    this.requestUpdate();
  }

  private deliverHalt(): void {
    const ctrl = this.agentController();
    const local = this.run;
    if (ctrl === null || local === null || local.haltDispatched) return;
    void dispatchRunControl(ctrl, { kind: 'halt' }).then((result) => {
      // Only an ACCEPTED halt closes the door; a lifecycle refusal leaves the request pending so the
      // next update can try again, and does so without a retry storm.
      if ((result as RunControlRefusal | undefined)?.refused !== true) local.haltDispatched = true;
    });
  }

  /**
   * The shared controller moved. Three things happen: the optimistic handoff latches the first time
   * the SERVER says anything, a pending halt is delivered once the run can honour it, and the run's
   * TERMINAL edge is detected so exactly one receipt lands.
   */
  /**
   * Does this window have an OPEN turn standing for a run? A settled turn stands for nothing: its
   * receipt is written and a later run is not the same run.
   */
  private get runRepresented(): boolean {
    const local = this.run;
    if (local === null) return false;
    const turn = sessionById(this.sessions, local.sessionId)?.turns.find(
      (t) => t.id === local.turnId,
    );
    return turn?.status === 'streaming';
  }

  /**
   * PRESENCE (tempdoc 822 Phase F3) — the fix for F2's named finding: *window-local in-memory
   * sessions orphan a live run on reload*. A fresh window showed zero sessions while the run went on
   * holding server-side, so the window was silently disagreeing with the product about what was
   * happening.
   *
   * The rule this establishes: the SHARED CONTROLLER is the authority on whether a run is live, and
   * this window's memory is not. When the controller reports a live or holding run that no session
   * here accounts for, the window synthesises one — titled with the run's own task text, carrying an
   * open agent turn, landing on the Active shelf — and adopts it as the run it renders. `entryStart`
   * is 0 because this window dispatched nothing: the whole conversation the controller holds is that
   * run's. `acknowledged` is true for the same reason — there is no optimistic local echo to yield;
   * every frame of it came from the server already.
   *
   * It reads through `peek`, so a window that never delegates still constructs no controller and
   * starts no polling by being mounted (the F2 law). And it does not CLAIM the adopted session: the
   * run is news, not a navigation.
   */
  private syncRunPresence(): void {
    const ctrl = this.agentController();
    if (ctrl === null) return;
    // The controller's conversation accumulates across runs, so the LIVE run's slice starts at the
    // task it was given — not at 0, which would read a finished run's steps as this one's.
    const start = sv3RunPresenceStart(ctrl);
    const feed = projectSv3RunFeed(ctrl, start);
    const probe = {
      status: sv3RunSessionStatus(ctrl, feed),
      represented: this.runRepresented,
      runId: ctrl.sessionId,
      adoptedRunIds: this.adoptedRunIds,
    };
    if (!sv3RunNeedsPresence(probe)) return;
    // The controller's own conversationId when it has one (a run dispatched from another surface
    // carries the conversation it belongs to), so the adopted session IS that conversation rather
    // than a second row for it. Only a run with no conversation gets a freshly minted id.
    const { list, ref } = adoptRunSession(
      this.sessions,
      sv3RunPresenceTitle(ctrl),
      Date.now(),
      ctrl.conversationId ?? createConversationId(),
    );
    this.sessions = list;
    this.run = {
      sessionId: ref.sessionId,
      turnId: ref.turnId,
      entryStart: start,
      sessionIdAtDispatch: null,
      acknowledged: true,
      haltRequested: false,
      haltDispatched: false,
    };
    // Observed live at adoption, so the run's terminal is an EDGE for this window too and the
    // adopted turn gets its one receipt instead of streaming forever.
    this.runLive = true;
    if (ctrl.sessionId !== null) this.adoptedRunIds.add(ctrl.sessionId);
  }

  private readonly onAgentUpdate = (): void => {
    // Presence first: a run this window has no session for gets one before the frame is read, so the
    // rest of this method has something to write the run's progress into.
    this.syncRunPresence();
    const ctrl = this.agentController();
    const local = this.run;
    if (ctrl !== null && local !== null) {
      // Latched once and never read again: a run whose evidence later disappears cannot push this
      // window back into claiming it is still sending.
      if (!local.acknowledged && hasServerAcknowledgedLocalDispatch(local, ctrl)) {
        local.acknowledged = true;
      }
      // A run this window is already rendering is a run it must never ALSO adopt as presence — the
      // id is only knowable once the server names it, which is here rather than at dispatch.
      if (ctrl.sessionId !== null) this.adoptedRunIds.add(ctrl.sessionId);
      const feed = projectSv3RunFeed(ctrl, local.entryStart);
      const status = sv3RunSessionStatus(ctrl, feed);
      if (status === 'live' || status === 'holding') {
        this.runLive = true;
        if (local.haltRequested) this.deliverHalt();
      } else if (this.runLive) {
        this.runLive = false;
        this.concludeRun(local, feed);
      }
    }
    this.requestUpdate();
  };

  /**
   * The run's terminal: exactly ONE receipt, written to the turn the dispatch opened. The count comes
   * from the SAME feed projection the cards were rendered from, so the receipt can never disagree
   * with what the reader watched — and it is addressed by ref, so a later run started from another
   * surface cannot write its numbers into this window's turn.
   */
  private concludeRun(local: Sv3RunLocal, feed: Sv3RunFeed): void {
    // Tempdoc 859 §3b — the run's EVIDENCE, written at the same terminal as its receipt and through
    // the same shared projection the record reader uses, so live and record produce one value from
    // one function over the same bytes. This is the C-small defect: the delegate path resolved
    // grounding on the backend, persisted it, and then never wrote it onto the turn, so a delegate
    // answer rendered with no marks and no sources while the evidence sat on the wire.
    //
    // §3c — GUARDED by the run the evidence belongs to. `concludeRun` fires on every terminal,
    // including the ones that emit no `done` (error, abort, watchdog, budget stop), and only `onDone`
    // writes the controller's evidence fields. Without this test a failed run N would settle wearing
    // run N-1's sources.
    //
    // TWO conditions, because the id test alone has a hole: a dispatch the server never named
    // leaves `ctrl.sessionId` still holding the PREVIOUS run's id, which the id test would then
    // read as a match. `acknowledged` is the window's own latch for "the server said something
    // about the run I opened" — the same identity discipline `entryStart` and `adoptedRunIds`
    // apply, and the reason it is not `runInFlight`/`isStreaming` (both local optimism, set inside
    // `send()` before the server answers).
    const ctrl = this.agentController();
    if (
      ctrl !== null &&
      local.acknowledged &&
      ctrl.answerEvidenceRunId !== null &&
      ctrl.answerEvidenceRunId === ctrl.sessionId
    ) {
      const evidence = agentAnswerEvidence(
        ctrl.answerSources,
        ctrl.answerCitations,
        ctrl.answerCitationScorer,
      );
      this.sessions = setTurnEvidence(
        this.sessions,
        { sessionId: local.sessionId, turnId: local.turnId },
        { ...evidence, retrievalMode: '' },
      );
    }
    this.sessions = settleAgentTurn(
      this.sessions,
      { sessionId: local.sessionId, turnId: local.turnId },
      sv3RunOutcome(feed, local.haltRequested),
      feed.toolCallCount,
      Date.now(),
    );
    // The live feed was ATTENTION; the record is what survives it. Refreshing at the terminal is what
    // makes the yield happen (inventory D1): the settled turn re-renders from the canonical record's
    // interleaved items instead of vanishing with the controller that produced them.
    void this.refreshRecord(local.sessionId);
  }

  /** A typed prompt resolved by its OWN control — never by anything typed into the composer. */
  private onRunDecision(event: Event): void {
    const ctrl = this.agentController();
    const detail = (event as CustomEvent<Sv3RunDecision>).detail;
    if (ctrl === null || detail === undefined) return;
    if (detail.kind === 'budget') {
      // B8 — RAISE is a different directive, not a third value of the gate's decision: the gate is
      // resolved by finalize/stop, whereas raising extends the run's allowance and leaves the gate to
      // clear itself (tempdoc 577 Ext III, `views/UnifiedChatView.ts:3748-3755`). Same seam either
      // way — `dispatchRunControl` is the only way a directive leaves this window.
      if (detail.decision === 'raise') {
        void dispatchRunControl(ctrl, {
          kind: 'raise-budget',
          addTokens: RAISE_BUDGET_STEP_TOKENS,
        });
        return;
      }
      if (detail.decision === 'stop') this.markHaltRequested();
      void dispatchRunControl(ctrl, { kind: 'budget-decision', decision: detail.decision });
      return;
    }
    if (detail.decision === 'stop') this.markHaltRequested();
    void dispatchRunControl(ctrl, { kind: 'context-decision', decision: detail.decision });
  }

  /** A gate resolved with "stop" IS the reader halting, so the receipt must say so. */
  private markHaltRequested(): void {
    if (this.run !== null) this.run.haltRequested = true;
  }

  /**
   * The `answer` rung of the primary slot. The composer cannot resolve a typed prompt, so the control
   * does the one honest thing available to it: it takes the reader to the decision.
   */
  private onComposerAnswer(): void {
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
  private runSearch(rawQuery: string): void {
    const query = rawQuery.trim();
    if (query === '') return;
    this.asked = true;
    setQuery(query);
    submitSearch();
    void this.setComposerState('docked');
  }

  private onPaletteRun(event: Event): void {
    const id = (event as CustomEvent<Sv3PaletteRun>).detail?.id ?? '';
    if (id === SV3_COMMAND_SEARCH_TEXT) {
      this.runSearch(this.composer?.draft ?? '');
      return;
    }
    if (id === SV3_COMMAND_EXPORT_MARKDOWN) this.exportActiveConversation();
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

  /**
   * A row click CLAIMS that conversation and shows its transcript. It re-runs nothing: a session is
   * a thread now, and re-issuing its opening question on a click would append a turn the reader
   * never asked for (Phase F1 — A2's row click re-ran the search, which was right for a search list
   * and is wrong for a conversation).
   */
  private onSessionSelect(event: Event): void {
    const id = (event as CustomEvent<Sv3SessionSelect>).detail?.id ?? '';
    // An edit in another row is DROPPED rather than committed, the spec's rule:
    // navigating away must not write text the reader walked away from.
    this.renamingId = null;
    const before = this.sessions;
    const prevId = before.activeId;
    this.sessions = focusSession(this.sessions, id, Date.now());
    if (this.sessions === before) return; // unknown id — nothing was claimed, so nothing to record
    this.claimConversation(id);
    // 857 D4 (drafted as 854) — the evidence pane belongs to the conversation it was opened against; a real switch
    // closes it. Guarded on the id captured BEFORE the claim, not object identity: focusSession
    // returns a NEW list even when id === prevId (it stamps lastVisitedAt on the row), so an
    // unguarded closePane() here would also fire on a re-click of the already-active row.
    if (prevId !== id) this.closePane();
    // The RECORD is what the reader is being shown, so it is fetched on the claim rather than trusted
    // from whatever this window happened to still hold (inventory D1).
    void this.refreshRecord(id);
    void this.refreshHistory(id);
    void this.setComposerState('docked');
  }

  /**
   * The two pointers a claim moves, both shared authorities: the store's active conversation (what
   * the product thinks is open) and the per-tab reload pointer (what THIS tab was reading — 609
   * Phase 3, and per-tab on purpose, so a second tab lands cold rather than adopting this one's thread).
   */
  private claimConversation(id: string): void {
    setActiveConversation(id);
    setLastViewedConversation(id);
  }

  /**
   * The reader names a conversation (tempdoc 822 Phase F5). The three phases meet here because the
   * window owns the list: the row raises intent, the panel says which row, and only this decides.
   * `commit` routes through `renameSession`, so an empty title reverts by the pure module's rule
   * rather than by a check duplicated at the view.
   */
  private onSessionRename(event: Event): void {
    const detail = (event as CustomEvent<Sv3SessionRename>).detail;
    if (detail === undefined) return;
    if (detail.phase === 'start') {
      this.renamingId = detail.id;
      return;
    }
    this.renamingId = null;
    if (detail.phase !== 'commit') return;
    const previous = sessionById(this.sessions, detail.id);
    const before = previous?.title ?? '';
    const wasRenamed = previous?.renamed ?? false;
    this.sessions = renameSession(this.sessions, detail.id, detail.title ?? '');
    const after = sessionById(this.sessions, detail.id)?.title ?? '';
    // WRITTEN THROUGH to the conversation store, which is where a name now LIVES (tempdoc 838: a
    // sealed `title` on the conversation's own record, not a browser-local map) — so it survives the
    // process, a cleared site-data and a second client, and every surface listing this conversation
    // shows the one the reader chose. Derived from the pure module's OUTCOME rather than re-deciding
    // here: `resolveSv3Rename` stays the one place an empty or unchanged title is judged.
    if (after !== before) void this.writeTitleThrough(detail.id, after, before, wasRenamed);
  }

  /**
   * The write half of a rename, and the one thing the optimistic update owes the reader: a name the
   * store REFUSED is put back and said out loud (tempdoc 838). The store reverts the row itself; this
   * reverts the window's own list, which is a separate copy, and words the two cases the same way
   * every other mutation in this product words them — a `423` is the lock, in the ONE reason
   * vocabulary, and anything else is a plain failure that names the status rather than hiding it.
   */
  private async writeTitleThrough(
    id: string,
    title: string,
    before: string,
    wasRenamed: boolean,
  ): Promise<void> {
    const written = await setConversationTitle(id, title, 'user');
    if (written.ok) return;
    this.sessions = restoreSessionTitle(this.sessions, id, before, wasRenamed);
    emitEphemeralToast({
      message:
        written.status === 423
          ? `${reasonFor('conversations.locked').wording} — that name was not saved.`
          : SV3_RENAME_FAILED,
      severity: 'warning',
    });
  }

  /**
   * The reader parks a conversation on the Pinned shelf, or takes it off. It is a list write and
   * nothing else — pinning does not claim the conversation, does not reorder anything, and does not
   * move a run off the Active shelf (a blocked run cannot be hidden; `projectSv3Sessions` owns that).
   */
  private onSessionPin(event: Event): void {
    const id = (event as CustomEvent<Sv3SessionPin>).detail?.id ?? '';
    this.sessions = toggleSessionPin(this.sessions, id);
  }

  /**
   * What the window knows about work in flight (tempdoc 831), read in ONE place and handed to both
   * the projection that paints the rows and the removal that refuses a live one. Two callers of one
   * expression, rather than two expressions — the row's affordance and the list's refusal have to be
   * the same judgement or the reader gets offered a delete that silently does nothing.
   */
  private runGate(run: Sv3RunView | null = this.projectRun()): Sv3RunGate {
    const snapshot = this.searchSnapshot;
    // `isRefining` counts too: the store runs a re-query BEHIND displayed results quietly
    // (`state/searchState.ts:611` — no skeleton, so the content surface keeps the old rows), and the
    // row's dot is then the only thing on screen saying the session is running a pass.
    const searching =
      this.asked && (snapshot?.isSearching === true || snapshot?.isRefining === true);
    const local = this.run;
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

  /**
   * The reader discards a conversation (tempdoc 831). `removeSession` DECIDES — including the
   * refusal for a conversation with work in flight — and the deletion is WRITTEN THROUGH to the
   * conversation store, the authority for a conversation's existence.
   *
   * THE ROW LEAVES WHEN THE STORE SAYS IT LEFT (852 S3), not on the press. This used to be
   * optimistic, which the cascade port made untenable: the delete can now stop and ASK ("this has 2
   * branches — delete those too?"), and asking about a row that has already vanished from the list
   * behind the dialog is asking about something the reader can no longer see. Removing on the answer
   * also retires the restore-by-re-list dance the optimistic version needed for every refusal.
   */
  private onSessionRemove(event: Event): void {
    const id = (event as CustomEvent<Sv3SessionRemove>).detail?.id ?? '';
    const gate = this.runGate();
    // The DECISION, taken now and carried into the write: an unchanged list is the refusal (work in
    // flight), and nothing else may happen on a refusal. The gate travels with it rather than being
    // re-derived after the await — the reader's press is what is being answered, and by the time the
    // store answers it has already deleted the conversation regardless of what has started since.
    if (removeSession(this.sessions, id, gate) === this.sessions) return;
    void this.deleteThroughStore(id, gate);
  }

  /**
   * Delete, CASCADE-AWARE (slice 517 FIX-U1, ported by 852 S3 — the one behavior the retired
   * conversation-history dropdown had that this window's sidebar did not).
   *
   * Slices 515/516 made it impossible to orphan a branch: the store REFUSES to delete a conversation
   * other conversations were forked from, with `409` + the children's ids. This window's delete
   * called the plain store function, which reports that refusal as a bare `false` — so the row
   * vanished from the list, the conversation stayed on disk, and the reader was told nothing. That
   * is not a missing feature; it is a delete that silently does not delete.
   *
   * What the reader is now asked is the same question the shipped window asked, in this window's
   * own words, and it NAMES the branches — the ids come back from the refusal, the labels from the
   * list this window already holds. It promises ONE level, because that is what the cascade does:
   * the store function recurses into each child WITHOUT the consent callback, so a child that has
   * children of its own answers `409`, the cascade aborts, and nothing is deleted. The copy says so
   * ({@link deleteCascadeMessage}) rather than promising a depth this act refuses to perform.
   *
   * Nothing leaves the list until the store says it left — including the conversation the reader
   * pressed delete on, which is why removing it on screen happens HERE and not at the press.
   */
  private async deleteThroughStore(id: string, gate: Sv3RunGate): Promise<void> {
    let cascaded: readonly string[] = [];
    let declined = false;
    const result = await deleteConversationWithCascade(id, async (childIds) => {
      cascaded = childIds;
      const consented = await confirmAsync({
        title: deleteCascadeTitle,
        message: deleteCascadeMessage(
          this.conversationLabel(id),
          childIds.map((childId) => this.conversationLabel(childId)),
        ),
        variant: 'danger',
        confirmLabel: deleteCascadeConfirm(childIds.length),
      });
      declined = !consented;
      return consented;
    });
    if (!result.ok) {
      // A DECLINED cascade is NOT a failure — the reader was asked and said no — and it is the ONE
      // silent case. A plain refusal and a consented cascade that then broke halfway are both real
      // failures and both must say so; the store function's return cannot tell the three apart
      // (`{ok:false, childIds}` covers two of them), which is why the reader's own answer is tracked
      // here instead of inferred from it. Nothing was removed on screen, so there is nothing to put
      // back — the re-list is for the CHILDREN a half-broken cascade may really have deleted.
      if (!declined) emitEphemeralToast({ message: DELETE_FAILED, severity: 'warning' });
      void loadConversations();
      return;
    }
    // The store deleted it, so now the screen follows. Removing the open conversation routes through
    // New session first, so the window leaves the transcript the way every other exit from it does —
    // an emptied pane and nothing claimed — rather than a partial teardown free to forget a pointer.
    // The children go with it: the list must not keep offering conversations the store no longer has.
    for (const gone of [id, ...cascaded]) {
      if (this.sessions.activeId === gone) this.onSessionNew();
      if (this.renamingId === gone) this.renamingId = null;
      this.sessions = removeSession(this.sessions, gone, gate);
    }
  }

  /** A conversation's name as the reader knows it, for a dialog that must name what it will delete. */
  private conversationLabel(id: string): string {
    const session = sessionById(this.sessions, id);
    const row = this.conversations.find((c) => c.id === id) ?? null;
    return session?.title ?? row?.title ?? row?.firstUserMessage ?? id;
  }

  /**
   * New session: back to the hero with an empty draft and nothing claimed about the corpus. The
   * sessions so far stay in the list — starting one is not ending the others. An in-flight response
   * IS ended, because its own session is no longer the one on screen and a stream nobody is watching
   * still spends a connection.
   */
  private onSessionNew(): void {
    this.abortAsk();
    // DETACHED, not halted (Slice 516 FIX-T1's rule, applied to the run half). A delegated run is
    // hosted by the product-wide controller and may be watched elsewhere, so this window stops
    // RENDERING it and never stops it. Dropping the local handle is also what keeps the composer's
    // slot honest: Send belongs in a fresh conversation, not a Stop for a run that is not in it.
    this.run = null;
    this.runLive = false;
    this.renamingId = null;
    this.recordNotice = false;
    // The open document was a citation of THIS conversation's answer; it says nothing about the one
    // being started, and a pane left open would attach another session's evidence to a blank hero.
    this.closePane();
    this.sessions = startNewSession(this.sessions);
    this.asked = false;
    this.composer?.clearDraft();
    // New conversation means "do not restore the one I just left" (tempdoc 609 Phase 3).
    setActiveConversation(null);
    clearLastViewedConversation();
    void this.setComposerState('hero');
  }

  /**
   * The Stop slot serves BOTH streams, because there is one slot. Which one it halts is decided here,
   * by which one is actually running — the composer says the reader pressed Stop and nothing more.
   */
  private onComposerStop(): void {
    if (this.streaming) {
      this.abortAsk();
      return;
    }
    this.haltRun();
  }

  /**
   * TWO AXES, ONE PHASE. The SESSION axis is what the shared controller says; the
   * TURN axis is what this window's own turn is doing — including the optimistic window before the
   * server has acknowledged the dispatch, which no controller field can report because the controller
   * is optimistic too. `deriveSv3RunPhase` collapses them into the one value everything renders from,
   * so the slot, the feed, the sidebar colour and the receipt cannot each decide separately.
   */
  private projectRun(): Sv3RunView | null {
    const local = this.run;
    if (local === null) return null;
    const ctrl = this.agentController();
    const feed = ctrl === null ? SV3_RUN_FEED_EMPTY : projectSv3RunFeed(ctrl, local.entryStart);
    const prompts = ctrl === null ? [] : projectSv3RunPrompts(ctrl, feed);
    const turn = sessionById(this.sessions, local.sessionId)?.turns.find(
      (t) => t.id === local.turnId,
    );
    const turnState: Sv3RunTurnState =
      turn === undefined || turn.status !== 'streaming'
        ? 'settled'
        : local.acknowledged
          ? 'open'
          : 'dispatching';
    return {
      turnId: local.turnId,
      phase: deriveSv3RunPhase({ session: sv3RunSessionStatus(ctrl, feed), turn: turnState }),
      feed,
      prompts,
    };
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
      <jf-sv3-pane
        data-testid="sv3-pane"
        .docPath=${this.paneDocPath}
        .citation=${this.paneCitation}
        .citationHeader=${this.paneCitationHeader}
        ?overlay=${this.paneOverlay}
        api-base=${this.apiBase}
        @sv3-pane-close=${this.closePane}
      ></jf-sv3-pane>
    `;
  }

  render(): TemplateResult {
    const results: Sv3ResultsView = projectSv3Results(this.searchSnapshot, this.asked);
    const run = this.projectRun();
    const turns = activeTurns(this.sessions);
    const pendingPrompt = run !== null && run.prompts.length > 0;
    const slot = sv3PrimaryAction({
      pendingPrompt,
      running:
        this.streaming ||
        run?.phase === 'dispatching' ||
        run?.phase === 'running' ||
        run?.phase === 'holding',
      followUp: turns.length > 0,
      tier: this.tier,
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
    const contexts = this.turnContexts();
    return html`
      <jf-sv3-sidebar
        .groups=${sessionGroups}
        .renamingId=${this.renamingId}
        ?collapsed=${this.sidebarCollapsed}
        data-testid="sv3-sidebar"
        @sv3-session-select=${this.onSessionSelect}
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
        @sv3-composer-state-request=${this.onStateRequest}
        @sv3-composer-submit=${this.onComposerSubmit}
        @sv3-composer-stop=${this.onComposerStop}
        @sv3-composer-answer=${this.onComposerAnswer}
        @sv3-effort-change=${this.onEffortChange}
        @sv3-tier-change=${this.onTierChange}
        @sv3-run-decision=${this.onRunDecision}
        @sv3-palette-request=${this.onPaletteRequest}
        @sv3-remedy=${this.onRemedy}
        @sv3-citation-open=${this.onCitationOpen}
        @sv3-context-menu=${this.onContextMenu}
        @sv3-context-action=${this.onContextAction}
        @sv3-branch-action=${this.onBranchAction}
        @sv3-version-select=${this.onVersionSelect}
      >
        <jf-sv3-topbar window-title=${WINDOW_TITLE} data-testid="sv3-topbar"></jf-sv3-topbar>
        <jf-sv3-main
          state=${this.composerState}
          .view=${results}
          .turns=${turns}
          .run=${run}
          ?record-notice=${this.recordNotice}
          ?history-locked=${this.historyLocked}
          ?locked-refusal=${this.lockedRefusal}
          .reasoning=${this.liveReasoning()}
          .reasoningTurnId=${this.liveReasoningTurnId()}
          .currentModelLabel=${this.currentModelLabel}
          ?detailed=${isAdvancedMode()}
          .turnContexts=${contexts}
          .turnLineage=${this.turnLineage()}
          .floorSummary=${session?.history?.contextFloorSummary ?? null}
          ?streaming=${this.streaming}
          data-testid="sv3-main"
        ></jf-sv3-main>
        <jf-sv3-context-bar
          .usage=${session?.contextUsage ?? null}
          .contextWindow=${this.aiSnapshot?.runtime.contextWindow ?? null}
          hidden-turns=${sv3ExcludedTurnCount(contexts)}
          data-testid="sv3-context-bar-host"
        ></jf-sv3-context-bar>
        <jf-sv3-composer
          state=${this.composerState}
          slot-kind=${slot.kind}
          slot-reason=${slot.reason}
          ?steerable=${this.steerableRun !== null}
          unavailable-reason=${this.askUnavailableReason}
          delegate-unavailable-reason=${this.delegateUnavailableReason}
          .degradation=${this.degradation}
          ?detailed=${isAdvancedMode()}
          .corpus=${projectSv3Corpus(this.aiSnapshot)}
          effort=${this.effort}
          tier=${this.tier}
          model-label=${this.currentModelLabel ?? ''}
          data-testid="sv3-composer"
        ></jf-sv3-composer>
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
