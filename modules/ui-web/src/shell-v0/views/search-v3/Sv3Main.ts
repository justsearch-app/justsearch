// SPDX-License-Identifier: Apache-2.0
/**
 * jf-sv3-main — the Search v3 window's content surface (tempdoc 822 slice 1; wired in Phase A1).
 *
 * Derived from a third-party design system (MIT) — see THIRD-PARTY-NOTICES.md in this directory.
 *
 * The ONE scroller in the window. The host itself is clipped; only `.scroller` inside it scrolls,
 * so the window's frame (topbar, sidebar, composer) can never be scrolled out of reach.
 *
 * The region is EMPTY in the composer's hero state (slice 3): nothing has been asked yet, so the
 * hero composer is the region's only subject. Once docked it holds the active session's TRANSCRIPT
 * (Phase F1) — and, when that session has no turns, the window's read of the shared search store
 * (`sv3-results.ts`, now the secondary axis). The region owns no store subscription and no client of
 * its own, so it cannot render anything the window did not hand it.
 *
 * The count line is computed HERE, off the same array the rows are mapped from, because that is the
 * only construction in which the number cannot come to describe a different set than the one on
 * screen. It is the shipped `matchCountLabel`, not a second count authority.
 *
 * Side-effect registers <jf-sv3-main>.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
// The product's ONE reading-position authority (tempdoc 565 §21), and this surface is its SECOND
// adopter — 565 §21 deferred "the general multi-surface authority … to the 2nd adopter", so the
// controller is consumed here rather than re-derived. Only its NAVIGATION half is used: `spineEl`
// returns null, which the authority explicitly supports (`primitives/navigation.ts:332-333`), so
// the minimap's measurements degrade to zero and the landmark/jump path is untouched.
import { NavigationController } from '../../primitives/navigation.js';
// The product's ONE "is the reader typing?" predicate (tempdoc 857 PR-A). A window-local copy is
// what let the retiree's own guard drift out of sync with `KeybindingRegistry`'s.
import { deepActiveElement, isTypingTarget } from '../../utils/keyboardHandler.js';
import { matchCountLabel } from '../../components/searchResults/matchCountLabel.js';
import { sv3Shared } from './sv3-shared-styles.js';
import './Sv3Empty.js';
// The product's ONE tool-call primitive (`governance/run-renderers.v1.json` — this file is a
// registered mount site). A window-local tool card would be the second render path that register
// exists to forbid, so the reference implementation's own tool row is deliberately NOT ported.
import '../../components/chat/ToolCallCard.js';
// The product's ONE markdown renderer and ONE citations panel (tempdoc 822 Phase F4). A window-local
// markdown pass would be a second parse of the same text with a second sanitiser behind it, and a
// window-local source list a second evidence presentation — both are what these authorities exist to
// prevent. The window supplies only the CLOTHES: the design spec's chat-markdown values, re-expressed
// on sv3 tokens through the custom properties the two components read.
import '../../components/chat/MarkdownBlock.js';
import '../../components/chat/CitationsPanel.js';
// The product's ONE citation hover preview and ONE reasoning block (tempdoc 822 Phase F7; inventory
// C3 and C9). Both are shared components the shipped window mounts for the same events this surface
// now receives; a window-local preview card or thinking disclosure would be a second presentation of
// evidence and of the model's own output.
import '../../components/chat/CitationHoverCard.js';
import '../../components/chat/ReasoningBlock.js';
// The product's ONE operability primitive (tempdoc 559 Authority V; 852 parity ledger row 11). Every
// control this slice adds is born on it rather than hand-rolled: the context acts are asynchronous
// writes, and `jf-control`'s promise-aware busy, its re-entrancy guard and its visually-hidden
// acknowledgement region are exactly what an act that takes a round trip owes the reader.
import '../../components/Control.js';
import type { CitationHoverCard, CitationHoverData } from '../../components/chat/CitationHoverCard.js';
import type { CitationSelectDetail } from '../../components/chat/citationTypes.js';
import type { DocumentCitationAnchor } from '../../components/documentPane/DocumentPane.js';
import {
  sv3CitationAnchor,
  sv3MatchedSentence,
  sv3SourceIndex,
} from './sv3-citation-anchor.js';
import type { ReasoningController } from '../../controllers/ReasoningController.js';
// The shared clipboard util (slice 486 G35) — permission-denied and API-absent already handled, so a
// per-turn copy needs no error path of its own.
import { copyToClipboard } from '../../utils/clipboardCopy.js';
import { icon } from '../../components/Icon.js';
// The ONE readiness vocabulary. The locked transcript's heading and its remedy are read from it, not
// worded here (tempdoc 629 #3 — the locked-chat gate speaks the same words as every other cause).
import { reasonFor } from '../../state/readinessNotice.js';
import type { Availability } from '../../state/availability.js';
import { RAISE_BUDGET_STEP_TOKENS } from '../unifiedChatRequest.js';
import {
  BRANCH_EDIT_CANCEL,
  BRANCH_EDIT_INPUT_LABEL,
  BRANCH_EDIT_LABEL,
  BRANCH_EDIT_SEND,
  BRANCH_EDIT_WAIT,
  COMPOSER_STATE_DEFAULT,
  CONTEXT_FLOOR_COMPACTED,
  CONTEXT_FLOOR_GROUP_LABEL,
  CONTEXT_FLOOR_RESET,
  CONTEXT_FLOOR_RESTORE,
  CONTEXT_FLOOR_RESTORE_LABEL,
  CONTEXT_MENU_LABEL,
  CONTEXT_SUMMARY_CANCEL,
  CONTEXT_SUMMARY_EDIT,
  CONTEXT_SUMMARY_EDIT_LABEL,
  CONTEXT_SUMMARY_HIDE,
  CONTEXT_SUMMARY_INPUT_LABEL,
  CONTEXT_SUMMARY_SAVE,
  CONTEXT_SUMMARY_SHOW,
  HISTORY_LOCKED_HELP,
  HISTORY_LOCKED_REFUSED,
  MAIN_EMPTY,
  MAIN_UNREACHABLE,
  RECORD_UNREACHABLE,
  REWRITE_NOTE_LABEL,
  RUN_DISPATCHING,
  TURN_COPY_DONE,
  TURN_COPY_FEEDBACK_MS,
  TURN_COPY_LABEL,
  TURN_EMPTY_ANSWER,
  TURN_FAILED,
  TURN_HALTED,
  VERSION_AT_FIRST,
  VERSION_AT_LAST,
  VERSION_NEXT,
  VERSION_PAGER_LABEL,
  VERSION_PREVIOUS,
  versionPagerCount,
} from './fixtures.js';
import type { Sv3ComposerState } from './fixtures.js';
import { SV3_RESULTS_IDLE, type Sv3ResultsView } from './sv3-results.js';
import { sv3TurnSourceCount, type Sv3Turn } from './sv3-sessions.js';
import {
  SV3_CONTEXT_ACTION,
  SV3_CONTEXT_MENU,
  sv3TurnContextFor,
  type Sv3ContextAction,
  type Sv3ContextMenuRequest,
  type Sv3TurnContext,
} from './sv3-context.js';
import {
  SV3_BRANCH_ACTION,
  SV3_VERSION_SELECT,
  sv3LineageFor,
  type Sv3BranchAction,
  type Sv3TurnLineage,
  type Sv3VersionSelect,
  type Sv3VersionSet,
} from './sv3-branch.js';
import {
  projectSv3AnswerFrame,
  sv3SourcesTrigger,
  sv3SourcesTriggerCount,
  sv3SourcesTriggerLabel,
  SV3_REMEDY,
  type Sv3RemedyDetail,
} from './sv3-honesty.js';
import { sv3RunReceiptLabel } from './sv3-run.js';
import type { Sv3RunFeedItem, Sv3RunPrompt, Sv3RunView } from './sv3-run.js';

/**
 * Raised when the reader resolves a typed prompt with its OWN dedicated control (tempdoc 822 Phase
 * F2). The surface announces the decision; the window dispatches it through the
 * ONE `dispatchRunControl` seam, because only the window may reach the run.
 */
export const SV3_RUN_DECISION = 'sv3-run-decision';

/**
 * Raised when a citation is followed (tempdoc 822 Phase F8) — the window's own event, carrying only
 * what the shared `citation-select` already knew. See {@link Sv3Main.onCitationSelect} for why the
 * shared event does not leave this surface.
 */
export const SV3_CITATION_OPEN = 'sv3-citation-open';

export interface Sv3CitationOpen {
  readonly docPath: string;
  /**
   * Tempdoc 849 §3 — the cited passage in the producer's OWN coordinate: document-relative character
   * offsets plus the excerpt quoted from them, or null when the citation carried no usable span. The
   * derived line numbers this used to carry are gone: they were computed 1-based upstream and read
   * 0-based by the reader, an off-by-one nothing downstream could recompute because the primary they
   * came from was dropped at this very hop.
   */
  readonly anchor: DocumentCitationAnchor | null;
  /** Which turn cited it. */
  readonly turnId: string;
  /**
   * Where the source sits in that turn's retrieval set (`-1` ⇒ not in it). With {@link turnId} this
   * is how a claim match arriving AFTER the pane opened is re-resolved onto the open pane (§4).
   */
  readonly sourceIndex: number;
}

export type Sv3RunDecision =
  | { readonly kind: 'budget'; readonly decision: 'raise' | 'finalize' | 'stop' }
  | { readonly kind: 'context'; readonly decision: 'continue' | 'summarize' | 'stop' };

/** Enough bars to fill the region's first screen without claiming a result count it cannot know. */
const SKELETON_ROWS = 6;

/**
 * How close to the end counts as "at the end" for the follow re-arm below. The design spec's own
 * re-arm is a boolean `isAtEnd` reported by a virtual list (at-end → `following-end`, otherwise →
 * `free-scrolling`); with a plain scroller the equivalent test
 * is a threshold, kept small so only a reader who is genuinely at the bottom stays armed, and
 * non-zero so sub-pixel scroll heights cannot disarm the follow on their own.
 */
const FOLLOW_END_SLACK_PX = 24;

/** The reference `size-3.5` on the inline disclosure's chevron. */
const TAIL_CHEVRON_SIZE = 14;

/** The reference `size-3` on the copy glyph — the tail's own 12px. */
const TAIL_GLYPH_SIZE = 12;

/** The disclosure's target, per turn — the ids live in this element's shadow root and nowhere else. */
const sourcesBodyId = (turnId: string): string => `sv3-sources-${turnId}`;

/**
 * The legend's own id, because the disclosure reveals TWO elements and `aria-controls` has to name
 * both. Naming only the panel left the key unreachable by the very relationship that exists to lead
 * a reader to it: the legend is a sibling BEFORE the panel, so a reader following `aria-controls`
 * from the trigger landed past it. `aria-controls` takes an ID list, so the region is described in
 * DOM order rather than restructured — the alternative, wrapping both in one element, would change
 * the tail's box model for an ARIA fix.
 */
const citeLegendId = (turnId: string): string => `sv3-cite-legend-${turnId}`;

/**
 * Tempdoc 822 §5.7 (F7) — the ONE line that names the answer's mark vocabulary. Sentence case, like
 * every other string this window says: v3 uses UPPERCASE nowhere. It exists mostly for the two greys
 * — a `.pseudo-cite` and a `.cite-weak` look identical and mean "the model invented this reference"
 * versus "a real reference the evidence supports weakly" — and it says what selection is FOR, which
 * is the question §5.3's sentence region answers but nothing on screen asks.
 */
const CITE_LEGEND =
  'Select a source to see the sentences it supports. A dotted underline marks a sentence the ' +
  'evidence supports weakly; amber marks one it does not support. A grey number is a weak reference.';

export class Sv3Main extends JfElement {
  static styles = [
    sv3Shared,
    css`
      :host {
        display: flex;
        flex-direction: column;
        flex: 1 1 auto;
        min-height: 0;
        overflow: hidden;
        background: var(--background);
        color: var(--foreground);
        font-family: var(--font-sans);
      }
      .scroller {
        flex: 1 1 auto;
        min-height: 0;
        padding: var(--floating-content-inset);
        /* ── The occluded band (tempdoc 859 §B) ──────────────────────────────
           The composer FLOATS over this scroller now, so the client box is no longer the visible
           region. Two declarations, two different jobs, and neither substitutes for the other:

           'padding-block-end' makes the band REACHABLE — scrollHeight grows by it, so "scrolled to
           the end" means "the last line is above the glass" rather than "behind it". The calc() is
           deliberate: the shorthand above already sets a 12px inset on all four sides, and a bare
           'padding-block-end' would REPLACE it rather than add to it, silently changing the
           transcript's bottom rhythm.

           'scroll-padding-block-end' makes every BROWSER-DRIVEN scroll respect it, by shrinking the
           scrollport that scrolling reasons about: a scrollIntoView asking for CENTRE centres in
           the VISIBLE region (857's landmark jumps land where the reader can see them) and one
           asking for NEAREST stops counting an occluded target as "already in view". It governs
           focus-driven scrolling too. One declaration on the one scroller covers every present and
           future target — a 'scroll-margin' sprinkled on landmarks would be a list that is
           incomplete the day someone adds a new item type. What it does NOT cover is raw scrollTop
           arithmetic, which is why navigation.ts's nudge subtracts the band itself. */
        padding-block-end: calc(var(--floating-content-inset) + var(--sv3-composer-occlusion));
        scroll-padding-block-end: var(--sv3-composer-occlusion);
        display: flex;
        flex-direction: column;
        gap: var(--space-1);
      }
      h2 {
        margin: 0 0 var(--space-2);
        font-size: var(--font-size-sv3-sm);
        font-weight: 600;
      }
      .row {
        display: flex;
        align-items: baseline;
        gap: var(--space-2);
        padding: var(--space-2) var(--space-3);
        border-radius: var(--radius-md);
        font-size: var(--font-size-sv3-sm);
        /* A long list stays cheap: the browser skips rendering work for rows outside the
           viewport, and the intrinsic size keeps the scrollbar honest while they are skipped. */
        content-visibility: auto;
        contain-intrinsic-size: auto 36px;
      }
      .row-title {
        font-weight: 500;
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .row-path {
        margin-left: auto;
        flex-shrink: 0;
        font-size: var(--font-size-sv3-xs);
        color: var(--secondary-label);
        font-family: var(--font-mono);
      }

      /* The pending state is the row rhythm with the content withheld — same height, same radius,
         same gap — so the list does not jump when the answer replaces it. The sweep is the shared
         sheet's duty-cycled keyframe (transform-only, long hold), not a continuous shimmer. */
      .skeleton-row {
        position: relative;
        overflow: hidden;
        height: var(--space-9);
        border-radius: var(--radius-md);
        background: var(--muted);
      }
      .skeleton-sheen {
        position: absolute;
        inset: 0;
        background: linear-gradient(
          90deg,
          transparent,
          color-mix(in srgb, var(--foreground) 8%, transparent),
          transparent
        );
      }

      /* ── The transcript ────────────────────────────────────────────────────
         One measure for the whole conversation, centred, matching the composer's own box: the
         design spec gives the timeline root the same 'max-w-3xl' its composer uses, so a turn and
         the field that produced it share an edge. */
      .transcript {
        width: 100%;
        max-inline-size: 48rem;
        min-width: 0;
        margin-inline: auto;
      }
      /* The design spec's turn rhythm: 16px under a message row ('pb-4', with 'pb-2' reserved for
         the commentary rows this window has none of). Bottom padding rather than a gap, so every
         turn is separated by the same measure regardless of what follows it.

         (This used to say "so the LAST turn keeps its breathing room above the composer". Tempdoc
         859 §B took the composer out of the flow: the last turn's clearance is now the scroller's
         --sv3-composer-occlusion padding, not this 16px.) */
      .turn {
        padding-bottom: var(--space-4);
      }

      /* ── The effective context (tempdoc 610, ported by 852 S2) ──────────────
         A turn the model no longer reads is DIMMED and never hidden: the transcript is the reader's
         record of what happened, and the floor changes what the next prompt contains, not what the
         conversation was. Recession is the whole visual budget here — no colour, because the 3-colour
         law reserves it for act-now / in-motion / broken and an out-of-context turn is none of those. */
      .turn[data-out-of-context] {
        opacity: 0.55;
      }
      /* Individually hidden: the same recession plus a rail, because this one was the READER's own act
         and the rail is what distinguishes "I hid this" from "the floor moved past it". */
      .turn[data-excluded] {
        opacity: 0.55;
        border-inline-start: 2px dashed var(--border);
        padding-inline-start: var(--space-2);
      }
      .context-floor {
        margin-block: var(--space-3);
      }
      /* The boundary itself: the one node carrying role="separator", and deliberately empty. */
      .context-floor-rule {
        border-top: 1px dashed var(--border);
      }
      .context-floor-line {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: var(--space-2);
        padding-block: var(--space-1);
        color: var(--secondary-label);
        font-size: var(--font-size-sv3-xs);
      }
      .context-floor-label {
        min-width: 0;
      }
      .context-floor-act::part(control) {
        display: inline-flex;
        align-items: center;
        height: var(--space-6);
        padding-inline: var(--space-1);
        border: 0;
        border-radius: var(--control-radius);
        background: none;
        color: inherit;
        font: inherit;
        cursor: pointer;
      }
      .context-floor-act::part(control):hover {
        color: var(--foreground);
      }
      .context-floor-summary {
        margin: var(--space-1) 0 0;
        padding: var(--space-2);
        border-radius: var(--control-radius);
        background: var(--muted);
        color: var(--secondary-label);
        font-size: var(--font-size-sv3-xs);
        line-height: 1.5;
      }
      .context-floor-input {
        inline-size: 100%;
        min-block-size: var(--space-12);
        box-sizing: border-box;
        padding: var(--space-1);
        border: 1px solid var(--border);
        border-radius: var(--control-radius);
        background: var(--background);
        color: var(--foreground);
        font: inherit;
        resize: vertical;
      }
      .context-floor-input:focus-visible {
        outline: 2px solid var(--ring);
        outline-offset: 1px;
      }
      .context-floor-summary-acts {
        display: flex;
        gap: var(--space-2);
        margin-top: var(--space-1);
      }
      /* The turn's ⋯ overflow RESTS, and is the one control in the row that does. L14's law is that
         exactly one thing yields (the copy action, tempdoc 818 §6b) — and the context acts behind
         this glyph are reachable from nowhere else in the window, so hiding them until the pointer
         finds the turn would make five capabilities discoverable only by accident. It is muted
         rather than revealed: present at rest, quiet until wanted. */
      .tail-menu::part(control) {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        inline-size: var(--space-6);
        block-size: var(--space-6);
        border-radius: var(--control-radius);
        color: var(--icon-muted);
        cursor: pointer;
      }
      .tail-menu::part(control):hover {
        color: var(--foreground);
      }
      /* Per the design spec: 'flex flex-col items-end gap-1'. */
      .ask {
        display: flex;
        flex-direction: column;
        align-items: flex-end;
        gap: var(--space-1);
      }
      /* Per the design spec: 'max-w-[80%] rounded-2xl bg-message p-3 text-message-foreground'.
         The fill is the ONE surface in the transcript. */
      .ask-bubble {
        max-inline-size: 80%;
        padding: var(--space-3);
        border-radius: var(--radius-2xl);
        background: var(--message-surface);
        color: var(--message-foreground);
        font-size: var(--font-size-sv3-sm);
        line-height: 1.625;
        white-space: pre-wrap;
        overflow-wrap: anywhere;
      }
      /* Edit RESTS beside the question for the reason the ⋯ rests below the answer: it is the only
         way to rewrite a question in this window, and a capability reachable nowhere else must not
         be discoverable by pointer alone. Muted at rest, like its sibling. */
      .ask-edit::part(control) {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        inline-size: var(--space-6);
        block-size: var(--space-6);
        border-radius: var(--control-radius);
        color: var(--icon-muted);
        cursor: pointer;
      }
      .ask-edit::part(control):hover {
        color: var(--foreground);
      }
      /* The editor takes the bubble's own measure, so a rewrite sits where the question sat rather
         than jumping the column width the moment it opens. */
      .ask-editing {
        align-items: stretch;
      }
      .ask-input {
        inline-size: 100%;
        min-block-size: var(--space-12);
        box-sizing: border-box;
        padding: var(--space-2);
        border: 1px solid var(--border);
        border-radius: var(--control-radius);
        background: var(--background);
        color: var(--foreground);
        font: inherit;
        font-size: var(--font-size-sv3-sm);
        resize: vertical;
      }
      .ask-input:focus-visible {
        outline: 2px solid var(--ring);
        outline-offset: 1px;
      }
      .ask-edit-acts {
        display: flex;
        justify-content: flex-end;
        gap: var(--space-2);
      }
      .ask-edit-act::part(control) {
        display: inline-flex;
        align-items: center;
        height: var(--space-6);
        padding-inline: var(--space-2);
        border: 0;
        border-radius: var(--control-radius);
        background: none;
        color: var(--secondary-label);
        font: inherit;
        font-size: var(--font-size-sv3-xs);
        cursor: pointer;
      }
      .ask-edit-act::part(control):hover {
        color: var(--foreground);
      }
      /* The pager is a READING, not an action: the count is the fact and the two chevrons move it. */
      .version-pager {
        display: inline-flex;
        align-items: center;
        gap: var(--space-1);
        color: var(--secondary-label);
        font-size: var(--font-size-sv3-xs);
        font-variant-numeric: tabular-nums;
      }
      .version-nav::part(control) {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        inline-size: var(--space-5);
        block-size: var(--space-5);
        border-radius: var(--control-radius);
        color: var(--icon-muted);
        cursor: pointer;
      }
      .version-nav::part(control):hover {
        color: var(--foreground);
      }
      /* The response has NO bubble and NO alignment — plain content on the panel, inset by the
         design spec's 'px-1 py-0.5'. Phase F4 fills it with the shared
         markdown renderer, so the block-level rhythm is the renderer's and this box no longer
         preserves source whitespace (a 'pre-wrap' around block children would re-introduce the
         template's own newlines as vertical space). */
      .answer {
        position: relative;
        min-width: 0;
        /* The spec's 'max-w-3xl' — the reading measure is the COLUMN's
           property, not the renderer's, which is why it sits here (tempdoc 822 §2.5). */
        max-inline-size: var(--measure-prose);
        padding: var(--space-0-5) var(--space-1);
        font-size: var(--font-size-sv3-sm);
        line-height: 1.625;
        overflow-wrap: anywhere;
      }

      /* ── The chat-markdown clothes on the shared renderer ───────────────────
         The renderer is the product's (components/chat/MarkdownBlock.ts) and is NOT forked; what
         it exposes is the set of custom properties it reads, so the design spec's values arrive as
         a re-mapping of those names onto sv3 tokens.

         The recorded GAP is CLOSED (tempdoc 822 §C2, slices S4 + S5). Its first half: the renderer
         names its block geometry as '--md-*' on its own ':host' with byte-identical defaults, so
         the block rhythm, the inline chip's edge + step-down, the pre chrome and the quote rule
         arrive from here as a re-mapping like every colour above. Its
         second half was the markup with NO rule at all — headings, tables, hr,
         img, task lists — which no token can express, so it lives behind the renderer's
         ':host([prose])' variant and this window OPTS IN at both transcript call sites (the settled
         answer and the agent-run text item). The variant's own defaults are the shipped type ramp;
         the heading re-points below are what align them with the spec. The reasoning trace is
         deliberately NOT opted in (§2.1): a compact trace should not adopt prose rhythm. */
      .sv3-markdown,
      .sv3-citations {
        /* Headings and code sit at full foreground. */
        --text-primary: var(--foreground);
        /* The h6 + blockquote recession. */
        --text-secondary: var(--muted-foreground);
        /* A link is the info hue, not the brand accent. */
        --text-tint: var(--info-foreground);
        /* Inline code and code blocks share the muted fill. */
        --surface-tertiary: var(--muted);
        --surface-2: var(--muted);
        --surface-3: var(--secondary);
        /* The blockquote rule and the code block's edge. */
        --border-subtle: var(--border);
        --accent-tint: var(--primary);
        --accent-on-tint: var(--primary-foreground);
        /* Tempdoc 822 citation-mark presentation §5.2/§5.3 — a selected citation is a NEUTRAL
           foreground wash, never the accent: charter law 5 keeps colour for act-now / in-motion /
           broken, and '--primary' is also '--message-action', the composer's send button. Painting a
           resting selection with the act-now material spends it on a state. Three rungs of one
           system: region 5% → mark 9% → edge 14%. The mark's INK is deliberately not re-pointed —
           that channel belongs to the grounding tier. The rest padding is the one geometry the
           window opts into: it reserves the selected mark's width at rest, so selecting a citation
           stops reflowing prose by 6px. '--md-cite-pad-x' and '--md-cite-radius' are named at the
           value they already default to — the window makes no pixel judgement about a 13px chip
           here; a '--md-*' name the cite rules own simply has no other definition site (the
           renderer's ':host' block belongs to the block-geometry workstream, whose containment proof
           enumerates its fifteen names exactly). */
        --md-cite-selected-bg: var(--sv3-selected);
        --md-cite-selected-edge: var(--sv3-selected-edge);
        /* The wash and the tier ink are ONE decision, taken together (§7.5). A window that paints a
           wash behind a 12px numeral changes the background that numeral's contrast is measured
           against, so the two SUBDUED tiers are re-pointed here at the same time — a wash bridged
           without them puts the grey tier at 4.22:1 in dark and 3.97:1 in light, i.e. it breaks AA
           at the exact moment the design exists to survive. Values and their reasons live at the
           token sheet; 'Sv3Main.imports.test.ts' asserts the composite, both themes, both tiers. */
        --md-cite-weak-color: var(--sv3-cite-weak);
        --md-cite-ungrounded-color: var(--sv3-cite-ungrounded);
        --md-cite-region-bg: var(--sv3-selected-region);
        --md-cite-pad-x-rest: 0.25em;
        --md-cite-pad-x: 0.25em;
        --md-cite-radius: 0.25em;
        /* §5.3 (F2) — the sentence region's horizontal breathing room. Flush to the glyphs the wash
           read as a text-selection smear rather than a designed region; the inset cancels the padding
           exactly, so it paints ~3px wider on each side and occupies the same width — not one glyph
           moves. Horizontal only: the grounding tiers' dotted 'border-bottom' rides this same
           element, and vertical padding would drop a selected sentence's underline below its
           neighbours'. Both names default to 0 in the renderer, so no other window changes. */
        --md-cite-region-pad-x: 0.25em;
        --md-cite-region-inset-x: -0.25em;
        /* §5.4 — the SAME three rungs on the FAR side of that selection. This window renders
           'jf-citations-panel' where the shipped window renders the rail card, so without these the
           mark lit and its source card stayed identical to every other card (F1) — the state's whole
           justification is relating two surfaces. The panel's own defaults are the card's EXISTING
           fill and edge ('--surface-2' / '--border-subtle'), not transparent — its '[data-selected]'
           rule outranks the base one, so a nothing-default would blank a selected card wherever the
           store is already written. Shipped cards are therefore untouched and this window is the
           only one that washes them; these names have no other definition site, exactly
           like the '--md-cite-*' block above. '--cp-hover-edge' is the panel's one accent spend
           recovered: a hover edge is a pointer's shadow, not an act-now signal.

           The panel's rungs sit ONE STEP UP from the mark's, and the reason is measured, not
           stylistic: a mark is painted on the window BACKGROUND (0 %), but a source card already
           carries '--muted' — a 4 % white fill. Spending the mark's 5 % region rung on a card is a
           ONE-POINT step (4 % -> 5 %), which a real-browser capture showed to be invisible: the
           selected card was indistinguishable from its neighbours even though '[data-selected]' was
           correctly set. The spec's own 6/9 idiom assumes a TRANSPARENT resting row, so it has to be
           re-based against the surface it actually lands on. Hence 4 % resting -> 9 % selected ->
           14 % selected+hover, preserving the spec’s rule that a row which is both takes the higher
           wash, and never two competing fills. */
        --cp-selected-region: var(--sv3-selected-region);
        --cp-selected-edge: var(--sv3-selected-edge-strong);
        --cp-selected: var(--sv3-selected-region);
        --cp-hover-edge: var(--sv3-selected-edge);
        /* Hover must never read as LESS selected: a selected card holds its strong edge under the
           pointer, while an unselected card still gets the subtle one above. */
        --cp-selected-hover-edge: var(--sv3-selected-edge-strong);
        --accent-warning: var(--warning-foreground);
        --text-warning: var(--warning-foreground);
        --text-command: var(--foreground);
        --font-size-sm: var(--font-size-sv3-sm);
        /* Inline code, block code and table text all step down. */
        --font-size-xs: var(--font-size-sv3-xs);
        /* The window's motion budget reaches the shared components too, so a transition inside
           them cannot outlast one authored here. */
        --duration-fast: var(--duration-sv3-micro);
        --duration-normal: var(--duration-sv3-layout);
        --ease-standard: var(--ease-sv3-enter);
      }
      .sv3-markdown {
        display: block;
        min-width: 0;
        /* An unbroken token in chat prose must not widen the measure. */
        overflow-wrap: anywhere;
        word-break: break-word;

        /* The geometry half of the bridge (tempdoc 822 §2.2). These names are the renderer's own,
           declared on its ':host' with the SHIPPED literals; the values here are the spec's, and
           they reach only the elements carrying this class — the citations list and the reasoning
           trace keep the shipped rhythm on purpose. Two of the fifteen are absent because sv3 keeps
           the shipped value: '--md-list-indent' (1.25rem) and '--md-pre-padding'. */
        /* The transcript's prose leading. */
        --md-line-height: 1.625;
        /* One 0.65rem-class rhythm for every block, wide or not (10px on the ladder). */
        --md-block-gap: var(--space-2-5);
        --md-block-gap-wide: var(--space-2-5);
        /* List items sit tight; the variant's 'li + li' carries the gap (S5). */
        --md-item-gap: 0;
        /* The inline chip: an edge, the small radius, a tighter inset and the
           12px step-down in the window's mono face. */
        --md-code-border: 1px solid var(--border);
        --md-code-radius: var(--radius-sm);
        --md-code-padding: 0.1rem 0.35rem;
        --md-code-size: var(--font-size-sv3-xs);
        --md-code-font: var(--font-mono);
        /* The code block takes the window's full radius knob. */
        --md-pre-radius: var(--radius);
        /* A thinner quote rule, a slightly wider inset. */
        --md-quote-border: 2px solid var(--border);
        --md-quote-padding: 0.8rem;
        /* A link is coloured, not underlined; the renderer's unconditional ':hover'
           rule restores the affordance under the pointer. */
        --md-link-decoration: none;

        /* The prose variant's heading ramp (tempdoc 822 §2.3, slice S5). The variant reads the
           SHIPPED type scale for h1/h2/h3 (and the already-re-pointed '--font-size-sm' for h4-h6),
           so the spec's heading scale — 1.25 / 1.125 / 1 / 0.875rem — arrives the same
           way the two steps above do: as a re-point onto this window's own ramp, which already IS
           that scale. Not one rem literal is copied (§2.1). Inside the renderer nothing else reads
           these three steps, so re-pointing the ramp here retunes exactly the headings.
           The variant's remaining defaults (weight 600, line-height 1.3, the asymmetric margin, the
           table padding and rules, the 24rem truncation cap, the between-items gap) already match
           the spec's numbers, or they read a colour/size token re-pointed above — so they are
           deliberately NOT re-pointed; 'Sv3Main.imports.test.ts' carries that decision in writing,
           name by name. */
        --font-size-xl: var(--font-size-sv3-xl);
        --font-size-lg: var(--font-size-sv3-lg);
        --font-size-md: var(--font-size-sv3-base);
      }
      /* The expanded evidence sits under the TAIL ROW, not under the answer (Phase F11), so its
         rhythm is the row's own 8px. An outer-tree rule on the host beats the component's own :host
         margin (the F8 pane lesson), which is why the shared component's 0.5rem is neutralised from
         here rather than edited inside an authority three windows render through. */
      .sv3-citations {
        display: block;
        margin: var(--space-2) 0 0;
      }
      .answer-empty {
        color: var(--secondary-label);
      }
      /* Tempdoc 822 §5.7 (F7) — the mark legend. A reader can meet five mark types in one answer
         plus two dotted underlines with no key anywhere, and the two GREYS mean opposite things:
         '.pseudo-cite' is "the model invented this reference", '.cite-weak' is "a real reference the
         evidence supports weakly". It lives INSIDE the Sources disclosure, so it is already gated and
         costs zero resting chrome against the fit audit's 16. Existing tokens only, no new colour,
         no icon — a legend that needed its own swatch would be a sixth mark type. */
      .cite-legend {
        margin: var(--space-2) 0 0;
        font-size: var(--font-size-sv3-xs);
        color: var(--secondary-label);
      }

      /* ── The same clothes on the three remaining imports (Phase F9) ────────
         Identical pattern to '.sv3-markdown' above and 'Sv3Pane.ts:78' — the shared components are
         NOT forked; what they expose is the set of custom properties they read, and a property the
         window does not re-point falls through to the shipped app's ':root' — a palette that is now
         in the SAME mode as this window (852 S4 mirrors the app's light/dark onto the host) but is
         still a different scale. Unbridged, that is not a
         taste difference but a polarity inversion: the F-series fit audit measured this card
         painting a near-white light-theme fill under a near-white tool name — white on white —
         and the reasoning block painting light-theme slate text on the window's near-black.

         Every pair below is computed against the window's own tokens in 'Sv3Main.imports.test.ts'
         (>= 4.5:1), which is why the surface choices are not always the nearest name: a 4 %-white
         wash NESTED inside another one leaves subdued text short of the floor, so a well inside a
         raised card goes DOWN the ladder (to --background) rather than up. */

      /* The agent run's tool call (components/chat/ToolCallCard.ts). */
      jf-tool-call-card {
        /* The card is the ONE raised surface here; its wells sit on it, and the quoted-output frame
           (which the component draws with a 3px --border-strong rule) goes deeper instead. */
        --surface-secondary: var(--card);
        --surface-tertiary: var(--muted);
        --surface-2: var(--background);
        --text-primary: var(--foreground);
        --text-secondary: var(--secondary-label);
        --text-warning: var(--warning-foreground);
        --border-subtle: var(--border);
        --border-strong: var(--input);
        /* As in the '.sv3-markdown' rule above, same reason — a link is the info hue. This
           is also the token whose NAME collided: sv3's hover material used to be called --accent,
           so this link painted at 4 % opacity (audit DEFECT-6, fixed in sv3-tokens.css.ts). */
        --accent: var(--info-foreground);
        --accent-tint: var(--primary);
        --accent-on-tint: var(--primary-foreground);
        /* The risk tiers keep the spec's 45 % edge grade, spent on the window's three-colour
           budget (818 law 5: act-now / in-motion / broken, no fourth role). */
        --accent-danger-45: color-mix(in srgb, var(--destructive) 45%, transparent);
        --accent-warning-45: color-mix(in srgb, var(--warning) 45%, transparent);
        /* The status word is written by an INLINE style (ToolCallCard.ts:354) off
           utils/statusTone.ts:88-104, but what that authority returns is 'var(--accent-<tone>)' —
           a custom property, so the inline colour resolves against these declarations like any
           other. (The audit recorded it as unreachable from a host token; it is not.) */
        --accent-success: var(--success-foreground);
        --accent-warning: var(--warning-foreground);
        --accent-danger: var(--error-foreground);
        /* Law 10: one family. The component's 'display' face is the window's only sans. */
        --font-display: var(--font-sans);
        --font-size-sm: var(--font-size-sv3-sm);
        --font-size-xs: var(--font-size-sv3-xs);
        --duration-normal: var(--duration-sv3-layout);
        --ease-standard: var(--ease-sv3-enter);
      }

      /* The model's thinking (components/chat/ReasoningBlock.ts). Its --text-muted is the resting
         body and --text-secondary the emphasis it brightens to on hover, so the two map to
         DIFFERENT rungs than the tool card's (which uses --text-secondary as its subdued rung) —
         the shipped hierarchy is per-component, and a bridge carries meaning, not names. */
      jf-reasoning-block {
        --surface-subtle: var(--muted);
        --text-muted: var(--secondary-label);
        --text-secondary: var(--foreground);
        --border-muted: var(--border);
        --accent-primary: var(--ring);
        /* Passed down to the nested <jf-markdown-block> the block renders its content into: it is
           outside the '.sv3-markdown' class bridge, so its tokens arrive here or not at all. The
           component re-points --text-primary to --text-muted itself (ReasoningBlock.ts:120-123),
           which is why a code well on the already-washed container goes to --background. */
        --surface-tertiary: var(--background);
        --text-primary: var(--foreground);
        --text-tint: var(--info-foreground);
        --border-subtle: var(--border);
        --accent-tint: var(--primary);
        --accent-on-tint: var(--primary-foreground);
        --accent-warning: var(--warning-foreground);
        /* Tempdoc 822 §3c — the ungrounded citation MARK's color (the warning role's text member;
           the body underline beside it uses the fill member above). Bridged in the same slice that
           introduced the read, per the charter's import-bridge clause. */
        --text-warning: var(--warning-foreground);
        --font-size-sm: var(--font-size-sv3-sm);
        --font-size-xs: var(--font-size-sv3-xs);
        --duration-fast: var(--duration-sv3-micro);
        --ease-standard: var(--ease-sv3-enter);
      }

      /* The citation preview (components/chat/CitationHoverCard.ts). It floats over the transcript,
         so it takes the window's opaque raised surface: --popover would leave its dimmest line
         (the match score) at exactly the 4.5:1 floor with no margin. Its own drop shadow is a
         hard-coded literal the bridge cannot reach (recorded, not worked around: the design spec
         wants no drop shadow in dark, and closing it is an edit inside the shared component). */
      jf-citation-hover-card {
        --surface-2: var(--card);
        --text-primary: var(--foreground);
        --text-secondary: var(--foreground);
        --text-muted: var(--secondary-label);
        --text-tint: var(--info-foreground);
        --border-subtle: var(--border);
        --font-size-sm: var(--font-size-sv3-sm);
        --font-size-xs: var(--font-size-sv3-xs);
        --z-modal: var(--z-tooltip);
        --duration-fast: var(--duration-sv3-micro);
        --ease-standard: var(--ease-sv3-enter);
      }

      /* ── The honesty pack (tempdoc 822 Phase F7) ───────────────────────────
         Every line below is a FACT about the answer, so none of them is allowed to hide behind hover
         (818 §6b L14): only the action bar at the bottom of the turn yields, and it yields on its
         own. They share the answer's own inset so a fact and the text it qualifies line up. */

      /* C8 — what retrieval actually searched for, above the answer it produced (the shipped
         window's own placement, views/UnifiedChatView.ts:5550-5553). */
      .rewrite-note {
        margin: 0 0 var(--space-1);
        padding-inline: var(--space-1);
        color: var(--secondary-label);
        font-size: var(--font-size-sv3-xs);
      }
      .rewrite-note em {
        color: var(--foreground);
        font-style: italic;
      }

      /* ── THE ANSWER TAIL: C1 + the evidence disclosure + A9, in ONE row (Phase F11) ────────
         The design spec's assistant footer is exactly this: mt-1.5,
         gap-2, text-xs, tabular-nums, items-center, one line under the message. Everything that used
         to stack below an answer — the frame line, the imported panel's own uppercase disclosure and
         the action bar — is this row now, at 30px instead of ~103px.

         The honesty facts hold at opacity 1 (818 §6b L14); the ONE thing that yields is the copy
         button, on its own, exactly as the F7 action bar did. */
      .tail {
        display: flex;
        /* The honest overflow: the tail WRAPS. It never truncates and never hides a fact. */
        flex-wrap: wrap;
        align-items: center;
        gap: var(--space-2);
        /* The spec's xs control height — reserved whether or not the copy button is revealed, so nothing
           in the turn resizes under the pointer. */
        min-height: var(--space-6);
        margin-top: var(--space-1-5);
        /* Matches the .answer inset above, so the tail and the text it belongs to share an edge. */
        padding-inline: var(--space-1);
        color: var(--secondary-label);
        font-size: var(--font-size-sv3-xs);
        line-height: 1.5;
        font-variant-numeric: tabular-nums;
      }
      .tail-note[data-broken='true'] {
        color: var(--error-foreground);
      }

      /* The window's ONE disclosure affordance (the fit audit's axis 3, answered for this region):
         the spec's own inline turn-fold row — sentence case,
         gap-1, rounded, px-1, muted → foreground on hover. No uppercase, no letter-spacing, and the
         same 12px as the facts beside it. */
      .tail-sources {
        display: inline-flex;
        align-items: center;
        gap: var(--space-1);
        height: var(--space-6);
        padding-inline: var(--space-1);
        border: 0;
        border-radius: var(--control-radius);
        background: none;
        color: inherit;
        font: inherit;
        cursor: pointer;
      }
      .tail-sources:hover {
        color: var(--foreground);
      }
      .tail-sources:focus-visible {
        outline: 2px solid var(--ring);
        outline-offset: 1px;
      }
      .tail-chevron {
        flex-shrink: 0;
        color: var(--icon-muted);
      }

      /* A9 — the one thing in a turn that hides until the reader reaches for it. The spec's
         xs button (24x24) carrying the copy-button glyph pair. */
      .tail-copy {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        inline-size: var(--space-6);
        block-size: var(--space-6);
        border: 0;
        border-radius: var(--control-radius);
        background: none;
        color: inherit;
        cursor: pointer;
        opacity: 0;
        transition: opacity var(--duration-sv3-layout) var(--ease-sv3-enter);
      }
      /* THREE SEPARATE RULES, never one rule nesting a focus test inside :has() — that selector is
         a Chrome syntax error and it killed the whole rule list in Phase F3 (static-green is not
         live-working).
         Focus must not depend on the reveal having finished, and a keyboard reader gets no hover. */
      .turn:hover .tail-copy {
        opacity: 1;
      }
      .turn:focus-within .tail-copy {
        opacity: 1;
      }
      .tail-copy:focus-visible {
        opacity: 1;
        outline: 2px solid var(--ring);
        outline-offset: 1px;
      }
      .tail-copy:hover {
        background: var(--accent-surface);
        color: var(--foreground);
      }
      @media (prefers-reduced-motion: reduce) {
        .tail-copy {
          transition: none;
        }
      }

      /* ── E4/E5: the store is locked, so the transcript is NOT READABLE ─────
         Tempdoc 629 §L4 — locked must never look deleted, and it must never look readable either.
         The region renders this INSTEAD of the transcript; nothing of the conversation is drawn
         behind it, which is the whole difference from the stale-readable state tempdoc 734 fixed. */
      .locked-detail {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: var(--space-3);
      }
      .locked-refusal {
        margin: 0;
        color: var(--foreground);
      }
      /* Geometry only — the focus ring is the primitive's own (components/Control.ts), which is the
         point of adopting it: one ring, one keyboard contract, across every control in the window. */
      .locked-remedy::part(control) {
        display: inline-flex;
        align-items: center;
        gap: var(--space-1-5);
        padding: var(--space-1-5) var(--space-3);
        border: 1px solid var(--border);
        border-radius: var(--control-radius);
        background: var(--background);
        color: var(--foreground);
        font-family: inherit;
        font-size: var(--font-size-sv3-sm);
        cursor: pointer;
      }
      .locked-remedy::part(control):hover {
        background: var(--muted);
      }
      /* The turn's terminal, said in words. Halting is the reader's own act and gets no colour — the
         3-colour budget is for act-now / in-motion / broken, and a stop is none of those. */

      /* ── The delegated run (Phase F2) ──────────────────────────────────────
         The live feed sits where the answer would be, at the same measure and the same rhythm: a run
         and an answer are two ways the same turn can be answered, so they must not read as two
         different regions of the window. */
      .run {
        display: flex;
        flex-direction: column;
        gap: var(--space-2);
        min-width: 0;
      }
      .run-feed {
        display: flex;
        flex-direction: column;
        gap: var(--space-2);
        min-width: 0;
      }
      .run-echo {
        margin: 0;
        padding-inline: var(--space-1);
        color: var(--secondary-label);
        font-size: var(--font-size-sv3-sm);
      }
      .run-note {
        margin: 0;
        padding-inline: var(--space-1);
        color: var(--secondary-label);
        font-size: var(--font-size-sv3-xs);
        line-height: 1.5;
      }
      .run-note-label {
        color: var(--foreground);
        font-weight: 500;
      }
      .run-note[data-label='Error'] .run-note-label {
        color: var(--error-foreground);
      }
      /* A held decision is act-now, which is the one place this window spends --success on a surface.
         It is a SIBLING of the feed, never inside it, so no amount of feed content can bury it. */
      .run-prompt {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: var(--space-2);
        padding: var(--space-3);
        border: 1px solid color-mix(in srgb, var(--success) 40%, transparent);
        border-radius: var(--radius-lg);
        background: color-mix(in srgb, var(--success) 8%, transparent);
      }
      .run-prompt-text {
        flex: 1 1 100%;
        margin: 0;
        font-size: var(--font-size-sv3-sm);
      }
      .run-prompt jf-control::part(control) {
        padding: var(--space-1) var(--space-3);
        border: 1px solid var(--border);
        border-radius: var(--control-radius);
        background: var(--background);
        color: var(--foreground);
        font-family: inherit;
        font-size: var(--font-size-sv3-xs);
        cursor: pointer;
      }
      .run-prompt jf-control::part(control):hover {
        background: var(--muted);
      }

      /* The store's own failure text, kept at diagnostic altitude: the state is said in words above
         it, and this is the detail that makes the words checkable. */
      .failure-detail {
        color: var(--secondary-label);
        font-family: var(--font-mono);
        font-size: var(--font-size-sv3-xs);
      }

      /* ── The record could not be read (Phase F6 / inventory D2) ────────────
         In FLOW at the top of the transcript, not floating over it: it qualifies everything below,
         so it has to be the first thing read and it has to scroll away with the content it qualifies.
         It carries no colour from the 3-colour budget — a refresh that failed is neither act-now nor
         broken, it is a shortfall in what the window can show. */
      .record-notice {
        margin-block-end: var(--space-4);
        padding: var(--space-2) var(--space-3);
        border: 1px solid var(--border);
        border-radius: var(--radius-lg);
        background: var(--muted);
      }
      .record-notice-title {
        margin: 0;
        font-size: var(--font-size-sv3-sm);
      }
      .record-notice-detail {
        margin: var(--space-1) 0 0;
        color: var(--secondary-label);
        font-size: var(--font-size-sv3-xs);
      }
    `,
  ];

  static properties = {
    state: { type: String, reflect: true },
    view: { attribute: false },
    turns: { attribute: false },
    run: { attribute: false },
    recordNotice: { type: Boolean, attribute: 'record-notice' },
    historyLocked: { type: Boolean, attribute: 'history-locked' },
    lockedRefusal: { type: Boolean, attribute: 'locked-refusal' },
    // A MUTABLE handle, not a value: the controller accumulates thinking in place, so its identity
    // never changes and Lit's default equality would hold this region back from re-rendering while
    // the model is thinking. Declared changed whenever one is present, unchanged when it is absent.
    reasoning: {
      attribute: false,
      hasChanged: (value: unknown, old: unknown) => value !== old || value !== null,
    },
    reasoningTurnId: { attribute: false },
    currentModelLabel: { attribute: false },
    detailed: { type: Boolean, reflect: true },
    copiedTurnId: { state: true },
    expandedSources: { state: true },
    turnContexts: { attribute: false },
    turnLineage: { attribute: false },
    floorSummary: { attribute: false },
    streaming: { type: Boolean },
    showFloorSummary: { state: true },
    editingFloorSummary: { state: true },
    floorSummaryDraft: { state: true },
    editingTurnId: { state: true },
    editingDraft: { state: true },
  };

  declare state: Sv3ComposerState;
  declare view: Sv3ResultsView;
  /** The ACTIVE session's turns, oldest first. Handed down; the region holds no session list. */
  declare turns: readonly Sv3Turn[];
  /**
   * The ONE live agent run, or null. Rendered against `run.turnId` and no other turn, so the feed
   * cannot appear under a turn that did not open it (tempdoc 822 Phase F2).
   */
  declare run: Sv3RunView | null;
  /**
   * The claimed conversation's canonical record could not be read, so what is on screen may be
   * incomplete (tempdoc 822 Phase F6 / inventory D2). A BOOLEAN, not a message: the copy is fixed
   * ({@link RECORD_UNREACHABLE}) and belongs with the window's other fixed copy, so a caller cannot
   * word this state a second way.
   */
  declare recordNotice: boolean;
  /**
   * The conversation store is encrypted and locked, so this region MUST NOT render the transcript
   * (tempdoc 629 §L4; inventory E4/E5). Handed down already derived by the window's one tri-state
   * reading of the polled protection state (`sv3-honesty.ts`), because a lock taken elsewhere reaches
   * every surface the same way and no region may decide it locally.
   */
  declare historyLocked: boolean;
  /** A send this window made was REFUSED by that lock, so the locked view says what became of it. */
  declare lockedRefusal: boolean;
  /**
   * The SHARED reasoning controller driving the turn that is streaming right now, or null (inventory
   * C9). Live only: a settled turn renders the blocks stored on it, so a finished conversation does
   * not depend on a controller that has since been reset.
   */
  declare reasoning: ReasoningController | null;
  /**
   * The id of the turn that OWNS the live reasoning controller above, or null (tempdoc 848 §2.7).
   * The same identity discipline `run.turnId` already applies to the feed: a controller renders
   * under one turn, never under whichever turn happens to be in `streaming` status.
   */
  declare reasoningTurnId: string | null;
  /**
   * The model the COMPOSER is currently naming (tempdoc 822 Phase F11). Handed down so a turn's own
   * stamped model can be suppressed when the two agree and re-stated when they do not — the region
   * decides neither, it is given both and asks the one derivation (`sv3TailModelLabel`).
   */
  declare currentModelLabel: string | null;
  /**
   * The app-wide Simple/Detailed authority's answer (inventory E3), handed down by the window.
   *
   * It gates ONE thing here: whether the frame line names the MODEL. The model is a technical
   * identifier — the shipped window reached the same conclusion and gates the same fact the same way
   * (`views/UnifiedChatView.ts:5040`). What it never gates is an honesty fact: the grounding verdict
   * and the duration are in both modes, because "how much of this is backed by your documents" is
   * the thing a Simple reader most needs and least knows to ask for.
   */
  declare detailed: boolean;
  /** The turn whose answer was just copied — the confirmation, and the only state this region owns. */
  declare copiedTurnId: string | null;
  /**
   * Which turns have their evidence open, BY TURN ID. Per turn and never global, so opening turn 3's
   * sources leaves turn 7 collapsed; replaced rather than mutated, because a Set mutated in place is
   * the same Set and Lit would not re-render.
   */
  declare expandedSources: ReadonlySet<string>;
  /**
   * What the effective context does with each turn (tempdoc 610, ported by 852 S2) — POSITIONAL,
   * one entry per turn in {@link turns}. Derived by the window from the conversation's `/history`
   * companion record, because the window is the one that loads it and the one that must reload it
   * after every write; this region decides nothing about the context and renders what it is given.
   */
  declare turnContexts: readonly Sv3TurnContext[];
  /**
   * What each turn can do about BRANCHING (852 S3) — positional, one entry per turn, derived by the
   * window because the version sets are a read over the shared conversation LIST and this region
   * holds no list. Same split as {@link turnContexts}, and for the same reason: the region renders
   * the arithmetic and performs none of it.
   */
  declare turnLineage: readonly Sv3TurnLineage[];
  /** The compaction summary standing at the floor, or null for a plain rewind / no floor. */
  declare floorSummary: string | null;
  /**
   * Something in this WINDOW is streaming (tempdoc 852 S2). Handed down rather than inferred from a
   * turn's status: the context acts are withheld for the whole conversation while a prompt is in
   * flight, and a turn that is not itself streaming cannot see that.
   */
  declare streaming: boolean;
  /** The divider's disclosure — the summary is a paragraph of the model's words, not a label. */
  declare showFloorSummary: boolean;
  declare editingFloorSummary: boolean;
  declare floorSummaryDraft: string;
  /**
   * The turn whose question is open for rewriting, and the rewrite itself (852 S3). Region state,
   * exactly like the floor-summary editor above and for the same reason: an editor is a place the
   * reader is standing, not a fact about the conversation, and the window has no use for it until
   * the reader presses Send.
   */
  declare editingTurnId: string | null;
  declare editingDraft: string;

  /**
   * The design spec's two scroll modes as one flag: armed = `following-end` (the reader is at the end, so
   * new text keeps the end in view), disarmed = `free-scrolling` (the reader scrolled up and owns
   * the viewport until they return to the end, which RE-ARMS it).
   */
  private followEnd = true;
  /**
   * 859 §B (D1) — the content height at the last render, so the snap can ask whether the content
   * GREW instead of firing on every render. Starts at 0 so a cold thread open (which grows from
   * nothing) is followed and lands on the newest turn.
   */
  private lastScrollHeight = 0;
  /**
   * The node {@link lastScrollHeight} was measured on. This element emits `.scroller` from FOUR
   * different render arms (857 PR-A / A2 found the same trap in the navigation controller), so a
   * height carried across an arm swap would compare two different boxes: a shorter new arm would
   * read as "did not grow" and the reader would land at the TOP of a transcript that just mounted.
   * A fresh node starts at scrollTop 0 with nothing measured, so resetting is also exactly right.
   */
  private lastScrolledEl: HTMLElement | null = null;
  private copiedTimer: ReturnType<typeof setTimeout> | null = null;
  /** The summary text a save is waiting on, or null — how this region learns the write landed. */
  private pendingSummary: string | null = null;

  /**
   * The reading-position authority for this transcript (tempdoc 857 PR-A; 565 §21's 2nd adopter).
   *
   * `spineEl` returns null on purpose: this window ports the run spine's KEYBOARD NAVIGATION, not
   * its minimap. The authority allows it — `trackPx` degrades to 0 and feeds only dot placement —
   * and the keyboard reader's feedback is the browser's own focus ring on the jumped-to step, which
   * is what `jumpTo` moves focus for.
   */
  private readonly nav = new NavigationController(this, {
    scrollEl: () => this.scroller,
    spineEl: () => null,
    active: () => this.transcriptArmRendered,
    occludedEndPx: () => this.occludedEndPx(),
  });

  /**
   * Tempdoc 859 §B — how much of the scroller's client box the floating dock covers, read back off
   * the ONE published variable rather than re-derived. The window measures the dock and writes
   * `--sv3-composer-occlusion` on its own host; custom properties inherit through shadow roots, so
   * the scroller resolves the same number the padding and scroll-padding above it resolve — one
   * measurement, three readers, no way for them to disagree.
   */
  private occludedEndPx(): number {
    const el = this.scroller;
    if (el === null) return 0;
    const px = Number.parseFloat(
      getComputedStyle(el).getPropertyValue('--sv3-composer-occlusion'),
    );
    return Number.isFinite(px) && px > 0 ? px : 0;
  }

  /**
   * 859 §B (D5) — the window calls this right after publishing a new band.
   *
   * The reading-position authority observes the SCROLLER, and a growing composer used to resize it
   * (a flex sibling shrinking the column). Under the overlay it does not, so the one signal that
   * used to keep the reading window honest through a growing draft is gone and has to be replaced
   * by an explicit one.
   */
  remeasureReadingWindow(): void {
    this.nav.remeasure();
  }

  /**
   * True exactly when {@link render} takes the LOCKED arm: the store refuses to be read and there is
   * something it would otherwise have shown. Extracted so the arm and {@link transcriptArmRendered}
   * cannot disagree about which one wins.
   */
  private get locksTranscript(): boolean {
    const turns = this.turns ?? [];
    return this.historyLocked && (turns.length > 0 || this.recordNotice || this.lockedRefusal);
  }

  /**
   * True exactly when {@link render} takes the TRANSCRIPT arm — the ONE definition, read by the arm
   * itself and by the navigation controller's `active()`.
   *
   * Two properties of this predicate are load-bearing, and neither is incidental:
   *
   *  1. **It is answerable BEFORE any measurement.** An `active()` derived from `nav.landmarks`
   *     deadlocks: landmarks populate only inside `measure()`, and `measure()` runs only when
   *     `active()` is already true (`primitives/navigation.ts:131-135`), so zero landmarks would
   *     mean permanently inactive. Host state, always.
   *  2. **It is FALSE in every non-transcript arm** — hero, search rows, pending, unreachable,
   *     empty and locked. That is what tears the controller down when the arm changes
   *     (`navigation.ts:134-135`), which is what makes it rebind to the NEW `.scroller` node on the
   *     way back in: this element emits `.scroller` from four different templates, so the node is
   *     not stable and a controller that stayed active across a swap would keep its observer and
   *     listeners on a detached element. A future `active()` that stays true across arms silently
   *     reintroduces that. (The authority also guards the swap on its own side now — see
   *     `setupResize` — but neither defence subsumes the other.)
   *
   * Deliberately NOT width-gated. The retiree gates the same navigation on `wideZone` because its
   * MINIMAP needs a gutter; this window has no gutter, so gating would deny a narrow viewport the
   * only keyboard navigation the product has for run steps. A reviewer must not "restore parity"
   * by re-adding the gate.
   */
  private get transcriptArmRendered(): boolean {
    if (this.locksTranscript) return false;
    return (this.turns ?? []).length > 0 || this.recordNotice;
  }

  constructor() {
    super();
    this.state = COMPOSER_STATE_DEFAULT;
    this.view = SV3_RESULTS_IDLE;
    this.turns = [];
    this.run = null;
    this.recordNotice = false;
    this.historyLocked = false;
    this.lockedRefusal = false;
    this.reasoning = null;
    this.reasoningTurnId = null;
    this.currentModelLabel = null;
    this.detailed = false;
    this.copiedTurnId = null;
    this.expandedSources = new Set();
    this.turnContexts = [];
    this.turnLineage = [];
    this.floorSummary = null;
    this.streaming = false;
    this.showFloorSummary = false;
    this.editingFloorSummary = false;
    this.floorSummaryDraft = '';
    this.editingTurnId = null;
    this.editingDraft = '';
  }

  override connectedCallback(): void {
    super.connectedCallback();
    window.addEventListener('keydown', this.onWindowKeydown);
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    // The confirmation's timer would otherwise outlive the region and set state on a detached element.
    if (this.copiedTimer !== null) clearTimeout(this.copiedTimer);
    this.copiedTimer = null;
    window.removeEventListener('keydown', this.onWindowKeydown);
  }

  /**
   * J / K — step focus forward and back through the run's landmarks (tempdoc 857 PR-A, porting
   * 565 §33 from `views/UnifiedChatView.ts:4803-4825`). The ONLY keyboard navigation the product has
   * for run steps, and the reason it is kept: `jumpTo` moves real DOM focus to the step, so a
   * keyboard or screen-reader reader lands on the content.
   *
   * A WINDOW listener, matching the source: the reader must be able to step the transcript while
   * focus sits in the sidebar or on the window frame, and this element's own shadow root is the one
   * place a transcript-scoped listener would never hear those presses from. The shell mounts one
   * surface at a time and only a CONNECTED element listens, so a cached sibling window cannot
   * double-handle.
   *
   * Four guards, in order, and each answers a different question:
   *
   *  - `defaultPrevented` — a window listener is the LAST handler in the chain and has no business
   *    acting on an event an inner one already claimed. This is not hypothetical:
   *    `components/advisory/AdvisoryInboxDrawer.ts:379-386` binds bare `j`/`k` on its rows, is
   *    mounted app-wide in the Shell's right drawer (`chrome/Shell.ts:2384`), and calls
   *    `preventDefault()` without `stopPropagation()`, so its keys reach this listener by bubbling.
   *    Its rows are `role="button"`, not editables, so the typing guard below would not stop them.
   *  - modifiers — `Ctrl+J` and friends belong to the browser and to chorded bindings.
   *  - the typing guard — the shared descent + predicate, so a `j` typed into the composer, or into
   *    a `<select>`, is a character rather than a jump.
   *  - a non-empty landmark list — an unmeasured or item-less transcript no-ops rather than throws.
   *
   * `composedPath()` containment was rejected as the collision guard even though this window prefers
   * it elsewhere (`SearchV3View.ts:1854-1856`): the path of a press made while nothing inside this
   * element has focus is `[body, html, document, window]`, so containment would trade a rare
   * collision for a key that is dead most of the time.
   */
  private readonly onWindowKeydown = (event: KeyboardEvent): void => {
    if (event.defaultPrevented) return;
    const dir = event.key === 'j' ? 1 : event.key === 'k' ? -1 : 0;
    if (dir === 0) return;
    if (event.ctrlKey || event.metaKey || event.altKey || event.shiftKey) return;
    if (isTypingTarget(deepActiveElement())) return;
    // The landmark list OUTLIVES the arm that produced it: `teardown()` releases the observer, the
    // listeners, the pin and the viewport, but deliberately keeps `landmarks`/`fractions`/`trackPx`
    // (`primitives/navigation.ts:376-386`). So after a transcript→locked transition the list is
    // stale-but-non-empty, the length check below passes, `preventDefault()` fires, and `jumpTo`
    // then bails because the locked arm renders no `.scroller` — a key swallowed to no effect, over
    // a transcript the store is currently refusing to show. Ask the arm, not the leftovers.
    if (!this.transcriptArmRendered) return;
    const landmarks = this.nav.landmarks;
    if (landmarks.length === 0) return;
    event.preventDefault();
    const cur = landmarks.findIndex((landmark) => landmark.id === this.nav.activeId);
    const next =
      cur < 0
        ? dir > 0
          ? 0
          : landmarks.length - 1
        : Math.min(landmarks.length - 1, Math.max(0, cur + dir));
    const target = landmarks[next];
    if (target) this.nav.jumpTo(target.id);
  };

  private get scroller(): HTMLElement | null {
    return this.shadowRoot?.querySelector('.scroller') ?? null;
  }

  /** Re-arm/disarm on the reader's own scrolling — never on a scroll this element caused itself. */
  private readonly onScroll = (): void => {
    const el = this.scroller;
    if (el === null) return;
    this.followEnd = el.scrollHeight - el.scrollTop - el.clientHeight <= FOLLOW_END_SLACK_PX;
  };

  protected override updated(changed: Map<string, unknown>): void {
    this.settleFloorSummaryEditor(changed);
    this.settleQuestionEditor();
    const el = this.scroller;
    if (el === null) return;
    if (el !== this.lastScrolledEl) {
      this.lastScrolledEl = el;
      this.lastScrollHeight = 0;
    }
    const grew = el.scrollHeight > this.lastScrollHeight;
    this.lastScrollHeight = el.scrollHeight;
    // Tempdoc 859 §B (D1) — the snap is gated on CONTENT GROWTH, not on a render.
    //
    // It used to re-assert `scrollTop = scrollHeight` on EVERY render while armed, which is one of
    // the two candidate causes of §7's "the Sources disclosure is unreachable": a render caused by
    // something other than new content would drag the reader back to the end.
    //
    // Growth, deliberately, rather than a `streaming` flag: `streaming` is set at three sites, all
    // inside the ask path, and the delegate/agent path never sets it — so a stream-gated snap would
    // stop the transcript following agent runs entirely. It also mishandles mount (`followEnd`
    // starts armed, so a freshly-opened thread would land at the TOP). Growth needs no source
    // enumeration: an agent-run feed appending items grows and is followed, a cold thread open
    // grows from zero and lands at the newest turn, and a render that added nothing moves nothing.
    if (!this.followEnd || !grew) return;
    el.scrollTop = el.scrollHeight;
  }

  /**
   * The disclosure's own state answers to the STORE, not to the press that started the act.
   *
   *  - The saved text came back ⇒ close the editor and keep it disclosed, so the reader reads what
   *    now stands rather than what they typed.
   *  - The summary changed for any other reason (a different conversation, a re-compaction, a
   *    restore) ⇒ the draft was written against a summary that is gone, and it goes with it.
   *  - Neither ⇒ nothing happens, which is the refused-write case: the editor stays open with the
   *    reader's correction in it while the toast says the act failed.
   */
  private settleFloorSummaryEditor(changed: Map<string, unknown>): void {
    if (this.pendingSummary !== null && this.floorSummary === this.pendingSummary) {
      this.pendingSummary = null;
      this.editingFloorSummary = false;
      return;
    }
    if (!changed.has('floorSummary')) return;
    this.pendingSummary = null;
    this.editingFloorSummary = false;
    this.floorSummaryDraft = '';
    this.showFloorSummary = false;
  }

  /**
   * The question editor answers to the TRANSCRIPT, for the reason the summary editor answers to the
   * store (S2's own defect): closing it on the press would throw away the reader's rewrite exactly
   * when the branch was refused and it is hardest to reproduce.
   *
   * What it waits for is the turn LEAVING. An accepted edit forks from before this question, so the
   * branch the window then opens does not contain the turn that was being edited — its disappearance
   * IS the write landing, and the same rule closes the editor when the reader claims some other
   * conversation. A refused branch leaves the transcript exactly as it was, so the editor stays open
   * with the rewrite in it while the window's toast names the act that failed.
   */
  private settleQuestionEditor(): void {
    const editing = this.editingTurnId;
    if (editing === null) return;
    if ((this.turns ?? []).some((turn) => turn.id === editing)) return;
    this.editingTurnId = null;
    this.editingDraft = '';
  }

  render(): TemplateResult {
    const view = this.view ?? SV3_RESULTS_IDLE;
    const turns = this.turns ?? [];
    // THE LOCK COMES FIRST, and it replaces the transcript rather than covering it (inventory E4):
    // a store that refuses to be read must not leave a readable copy of what it holds on screen. Only
    // the CONVERSATION is gated — the search projection below is a different, unencrypted store, and
    // gating it too would be a true statement about the wrong data (tempdoc 629's own scope rule).
    if (this.locksTranscript) return this.locked();
    // The conversation owns the region whenever the claimed session has one. The search projection
    // below is the SECONDARY axis now (822 §4b course correction) and speaks only for a session that
    // has asked nothing — it is reached from the palette, never from a plain submit.
    if (this.transcriptArmRendered) return this.transcript(turns);
    // Nothing but the hero composer belongs in the region until the window has docked: an untouched
    // window's emptiness is the composer's to speak for, not a state to announce.
    if (this.state !== 'docked' || view.status === 'idle') {
      return html`<div class="scroller sv3-scroller" data-testid="sv3-main-scroller"></div>`;
    }
    if (view.status === 'loading') return this.pending();
    if (view.status === 'unreachable') return this.unreachable(view.failure);
    if (view.status === 'empty') {
      return html`
        <jf-sv3-empty
          roomy
          data-testid="sv3-main-empty"
          glyph="&#9634;"
          heading=${MAIN_EMPTY.title}
          description=${MAIN_EMPTY.description}
        ></jf-sv3-empty>
      `;
    }
    const rows = view.rows;
    return html`
      <div class="scroller sv3-scroller" data-testid="sv3-main-scroller">
        <h2 data-testid="sv3-main-count">
          ${matchCountLabel(view.matched, rows.length, false, view.ranked, view.truncated)}
        </h2>
        ${rows.map(
          (row) => html`
            <div class="row" data-testid="sv3-main-row">
              <span class="row-title">${row.title}</span>
              <span class="row-path">${row.path}</span>
            </div>
          `,
        )}
      </div>
    `;
  }

  /**
   * One turn = the question as a right-aligned bubble, the response as plain content beneath it.
   * The asymmetry is the design spec's and it is load-bearing: only the user's turn carries a fill, so the
   * transcript reads as answers punctuated by asks rather than as two columns of chat.
   */
  private transcript(turns: readonly Sv3Turn[]): TemplateResult {
    return html`
      <div
        class="scroller sv3-scroller"
        data-testid="sv3-main-scroller"
        @scroll=${this.onScroll}
        aria-busy=${turns.at(-1)?.status === 'streaming' ? 'true' : 'false'}
        @cite-ref-hover=${this.onCiteRefHover}
        @cite-ref-leave=${this.onCiteRefLeave}
      >
        <div class="transcript" data-testid="sv3-transcript">
          ${this.recordNotice
            ? html`<div class="record-notice" role="status" data-testid="sv3-record-notice">
                <p class="record-notice-title">${RECORD_UNREACHABLE.title}</p>
                <p class="record-notice-detail">${RECORD_UNREACHABLE.description}</p>
              </div>`
            : nothing}
          ${turns.map((turn) => this.turn(turn))}
        </div>
      </div>
      <!-- OUTSIDE the scroller: the card is viewport-positioned from the mark's own rect, so a
           scroller that clipped it would hide the preview at the region's edges. -->
      <jf-citation-hover-card data-testid="sv3-citation-hover"></jf-citation-hover-card>
    `;
  }

  /**
   * C3 — the mark's preview, in the product's ONE hover card. The event is the shared markdown
   * block's own (`components/chat/MarkdownBlock.ts:591-607`), carrying both the trigger's rect and
   * the resolved source, so this surface looks nothing up: it forwards what the mark already knows.
   * Delegated at the scroller rather than bound per mark, because the marks are woven into the shared
   * block's shadow DOM and this window never touches them.
   */
  private readonly onCiteRefHover = (event: Event): void => {
    const detail = (event as CustomEvent).detail as
      | { rect?: DOMRect; source?: CitationHoverData }
      | undefined;
    const source = detail?.source;
    const rect = detail?.rect;
    if (source === undefined || rect === undefined) return;
    this.hoverCard?.show(source, rect);
  };

  private readonly onCiteRefLeave = (): void => {
    this.hoverCard?.hide();
  };

  private get hoverCard(): CitationHoverCard | null {
    return this.shadowRoot?.querySelector('jf-citation-hover-card') ?? null;
  }

  /**
   * E4/E5 — the locked store's own view, in the window's ONE empty-state component. The
   * heading is `reasonFor('conversations.locked')`'s wording and the remedy is that cause's own
   * declared navigation, so the locked transcript speaks the vocabulary every other readiness cause
   * in the product speaks and points at the surface that actually owns the unlock.
   */
  private locked(): TemplateResult {
    const reason = reasonFor('conversations.locked');
    const nav = reason.remedy?.kind === 'navigate' ? reason.remedy : null;
    return html`
      <jf-sv3-empty
        roomy
        data-testid="sv3-history-locked"
        glyph="&#9634;"
        heading=${reason.wording}
        description=${HISTORY_LOCKED_HELP}
      >
        <div class="locked-detail">
          ${this.lockedRefusal
            ? html`<p class="locked-refusal" role="alert" data-testid="sv3-history-locked-refusal">
                ${HISTORY_LOCKED_REFUSED}
              </p>`
            : nothing}
          ${nav === null
            ? nothing
            : html`<jf-control
                class="locked-remedy"
                data-testid="sv3-history-locked-remedy"
                label=${nav.label}
                .onActivate=${() => this.remedy(nav.target)}
              >
                ${icon({ name: 'shield', size: 14 })} ${nav.label}
              </jf-control>`}
        </div>
      </jf-sv3-empty>
    `;
  }

  private remedy(target: string): void {
    this.dispatchEvent(
      new CustomEvent<Sv3RemedyDetail>(SV3_REMEDY, {
        detail: { target },
        bubbles: true,
        composed: true,
      }),
    );
  }

  private turn(turn: Sv3Turn): TemplateResult {
    const streaming = turn.status === 'streaming';
    const empty = turn.answer === '';
    // The run this turn OPENED, if it is the one live — matched by id, never by "the last turn". An
    // ENDED run renders nothing here: its live feed was attention, and the receipt below is what
    // survives it (the same record/attention split the retired search-v2 window's L8 made).
    const live = this.run;
    const run =
      turn.kind === 'agent' && live?.turnId === turn.id && live.phase !== 'ended' ? live : null;
    // Tempdoc 610 (852 S2) — what the EFFECTIVE context does with this turn. A turn the window has
    // no context frame for renders exactly as it did before the port: nothing about the prompt is
    // claimed for a conversation whose `/history` has not been read.
    const context = sv3TurnContextFor(this.turnContexts, turn.id);
    return html`
      ${context?.isFloor === true ? this.floorDivider() : nothing}
      <div
        class="turn"
        data-testid="sv3-turn"
        data-kind=${turn.kind}
        data-status=${turn.status}
        ?data-out-of-context=${context?.outOfContext === true}
        ?data-excluded=${context?.hasExcluded === true}
      >
        ${this.question(turn)}
        ${turn.kind === 'agent'
          ? html`${/* Tempdoc 848 §2.7 — an agent turn shows its thinking too. Leaving one turn kind
                      reasoning-less would rebuild, inside this window, the same live/record
                      asymmetry the persistence work exists to remove. */ ''}
              ${this.reasoningBlocks(turn, streaming)}${run === null
                ? this.recordedActivity(turn)
                : this.runBody(run)}`
          : html`
              ${this.rewriteNote(turn)}${this.reasoningBlocks(turn, streaming)}
              <div class="answer" data-testid="sv3-turn-answer" data-item-id=${`${turn.id}:a`}>
                ${empty && !streaming
                  ? html`<span class="answer-empty" data-testid="sv3-turn-answer-empty"
                      >${TURN_EMPTY_ANSWER}</span
                    >`
                  : html`<jf-markdown-block
                      class="sv3-markdown"
                      prose
                      data-testid="sv3-turn-markdown"
                      data-turn-id=${turn.id}
                      .text=${turn.answer}
                      ?is-streaming=${streaming}
                      .citations=${[...(turn.evidence?.marks ?? [])]}
                      @citation-select=${this.onCitationSelect}
                    ></jf-markdown-block>`}
              </div>
            `}
        ${this.tail(turn)}${this.citations(turn)}
      </div>
    `;
  }

  /**
   * The turn's question, and the ONE affordance that acts on it: EDIT (tempdoc 610 Phase A, ported
   * by 852 S3).
   *
   * Edit renders here rather than in the ⋯ because it is the question's defining act and the
   * reference window makes the same split for the same reason (§13.1 — "Edit is the user turn's
   * defining action and renders INLINE on the turn"). Retry and Branch are in the overflow, where
   * that window also keeps them.
   *
   * It renders only when the turn can actually be edited: a store-minted question AND a nameable
   * fork point ({@link Sv3TurnLineage.canEdit}). An inherited turn, an agent turn and a live turn all
   * fail that and get no control — the honest null this window already applies to the context acts,
   * rather than a pencil that would fork the wrong conversation or 404.
   */
  private question(turn: Sv3Turn): TemplateResult | typeof nothing {
    if (turn.question === '') return nothing;
    if (this.editingTurnId === turn.id) return this.questionEditor(turn);
    const canEdit = sv3LineageFor(this.turnLineage, turn.id)?.canEdit === true;
    return html`<div class="ask">
      <!-- 857 PR-A — a J/K landmark. The :q suffix keeps the turn's anchors out of the run feed's
           id space by construction (feed ids are the controller's entry/call ids). -->
      <div class="ask-bubble" data-testid="sv3-turn-question" data-item-id=${`${turn.id}:q`}>${turn.question}</div>
      ${canEdit && !this.streaming
        ? html`<jf-control
            class="ask-edit"
            data-testid="sv3-turn-edit"
            data-turn=${turn.id}
            label=${BRANCH_EDIT_LABEL}
            .onActivate=${() => {
              this.editingDraft = turn.question;
              this.editingTurnId = turn.id;
            }}
            >${icon({ name: 'pencil', size: TAIL_GLYPH_SIZE })}</jf-control
          >`
        : nothing}
    </div>`;
  }

  /**
   * The rewrite in progress. Ctrl/⌘+Enter sends and Escape cancels — the reference's own keys
   * (`onEditKeydown`, `views/UnifiedChatView.ts:1668-1677`), so the gesture a reader learned in one
   * window is the gesture in this one.
   */
  private questionEditor(turn: Sv3Turn): TemplateResult {
    // ONE refusal for BOTH ways in. The window refuses an edit raised while something is streaming,
    // and a keyboard path that raised it anyway would be refused out of sight — the button explains
    // itself, the shortcut would just do nothing. Both go through this, so the only way to reach the
    // act is the way the control describes.
    const blocked = (): boolean => this.streaming;
    const send = (): void => {
      const text = this.editingDraft.trim();
      if (text === '' || blocked()) return;
      this.branchAct({ action: 'edit', turnId: turn.id, text });
    };
    return html`<div class="ask ask-editing">
      <textarea
        class="ask-input"
        data-testid="sv3-turn-edit-input"
        aria-label=${BRANCH_EDIT_INPUT_LABEL}
        .value=${this.editingDraft}
        @input=${(event: Event) => {
          this.editingDraft = (event.target as HTMLTextAreaElement).value;
        }}
        @keydown=${(event: KeyboardEvent) => {
          if (event.key === 'Escape') {
            event.preventDefault();
            this.editingTurnId = null;
            this.editingDraft = '';
          } else if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) {
            event.preventDefault();
            send();
          }
        }}
      ></textarea>
      <div class="ask-edit-acts">
        <jf-control
          class="ask-edit-act"
          data-testid="sv3-turn-edit-send"
          label=${BRANCH_EDIT_SEND}
          .availability=${this.streaming
            ? ({ kind: 'unavailable', reason: BRANCH_EDIT_WAIT } as const)
            : ({ kind: 'available' } as const)}
          .onActivate=${send}
          >${BRANCH_EDIT_SEND}</jf-control
        >
        <jf-control
          class="ask-edit-act"
          data-testid="sv3-turn-edit-cancel"
          label=${BRANCH_EDIT_CANCEL}
          .onActivate=${() => {
            this.editingTurnId = null;
            this.editingDraft = '';
          }}
          >${BRANCH_EDIT_CANCEL}</jf-control
        >
      </div>
    </div>`;
  }

  /**
   * The inline pager between the versions of one turn (tempdoc 610 Phase B). It renders wherever a
   * divergence point has more than one version — INCLUDING on a turn that offers no other control,
   * because a fork the reader cannot navigate is a fork they cannot see they made (the reference
   * gates it independently of its action bar for the same reason, `:1583-1588`).
   *
   * Selecting a version CLAIMS that conversation; it is navigation, not a write, so the window's
   * ordinary open path handles it and nothing here is optimistic.
   */
  private versionPager(turn: Sv3Turn): TemplateResult | typeof nothing {
    const versions = sv3LineageFor(this.turnLineage, turn.id)?.versions ?? null;
    if (versions === null) return nothing;
    const go = (next: number): void => {
      const target = versions.sessions[next];
      if (target === undefined || next === versions.index) return;
      this.dispatchEvent(
        new CustomEvent<Sv3VersionSelect>(SV3_VERSION_SELECT, {
          detail: { sessionId: target },
          bubbles: true,
          composed: true,
        }),
      );
    };
    return html`<span class="version-pager" role="group" aria-label=${VERSION_PAGER_LABEL}>
      <jf-control
        class="version-nav"
        data-testid="sv3-version-previous"
        label=${VERSION_PREVIOUS}
        .availability=${this.versionStep(versions, -1)}
        .onActivate=${() => go(versions.index - 1)}
        >${icon({ name: 'chevron-left', size: TAIL_GLYPH_SIZE })}</jf-control
      >
      <span class="version-count" data-testid="sv3-version-count"
        >${versionPagerCount(versions.index, versions.sessions.length)}</span
      >
      <jf-control
        class="version-nav"
        data-testid="sv3-version-next"
        label=${VERSION_NEXT}
        .availability=${this.versionStep(versions, 1)}
        .onActivate=${() => go(versions.index + 1)}
        >${icon({ name: 'chevron-right', size: TAIL_GLYPH_SIZE })}</jf-control
      >
    </span>`;
  }

  /**
   * Why an END of the pager is UNAVAILABLE rather than disabled-and-silent: `jf-control`'s typed
   * availability carries the reason to assistive tech, and "this is the first version" is the whole
   * answer to why the control did not move.
   */
  private versionStep(versions: Sv3VersionSet, step: number): Availability {
    const next = versions.index + step;
    return next >= 0 && next < versions.sessions.length
      ? { kind: 'available' }
      : { kind: 'unavailable', reason: step < 0 ? VERSION_AT_FIRST : VERSION_AT_LAST };
  }

  /** Every branch act this region raises goes through one seam, for the window to resolve. */
  private branchAct(detail: Sv3BranchAction): void {
    this.dispatchEvent(
      new CustomEvent<Sv3BranchAction>(SV3_BRANCH_ACTION, {
        detail,
        bubbles: true,
        composed: true,
      }),
    );
  }

  /**
   * C8 (tempdoc 603 C2) — the standalone question retrieval actually ran on, shown back. A follow-up
   * like "and the second one?" is searched as something else entirely, and a reader who cannot see
   * what that was cannot tell a bad answer from a bad rewrite.
   */
  private rewriteNote(turn: Sv3Turn): TemplateResult | typeof nothing {
    if (turn.standaloneQuestion === '') return nothing;
    return html`<p class="rewrite-note" data-testid="sv3-turn-rewrite">
      ${REWRITE_NOTE_LABEL} <em>${turn.standaloneQuestion}</em>
    </p>`;
  }

  /**
   * C9 — the model's thinking, in the product's ONE controlled block: collapsed by its own
   * disclosure, never mixed into the answer text. Two sources, and only ever one of them: the LIVE
   * controller while this turn streams, the blocks recorded on the turn once it has settled.
   */
  private reasoningBlocks(turn: Sv3Turn, streaming: boolean): TemplateResult | typeof nothing {
    const live = this.reasoning;
    // Tempdoc 848 §2.7 — the live controller renders for the turn that actually OWNS the live
    // stream, matched by id exactly as the run feed is bound above. `streaming` alone is per-turn
    // (`turn.status === 'streaming'`) and two turns in that status are reachable — an adopted run
    // arrives without coordinating with the ask path — so a turn that does not own the stream would
    // otherwise show another turn's thinking. A turn-KIND check would not close that: both could be
    // the same kind.
    if (streaming && live !== null && this.reasoningTurnId === turn.id) {
      if (!live.isThinking && live.reasoningBlocks.length === 0) return nothing;
      return html`<jf-reasoning-block
        data-testid="sv3-turn-reasoning"
        .controller=${live}
      ></jf-reasoning-block>`;
    }
    if (turn.reasoning.length === 0) return nothing;
    return html`${turn.reasoning.map(
      (block) => html`<jf-reasoning-block
        data-testid="sv3-turn-reasoning"
        .text=${block.text}
        .durationMs=${block.durationMs}
      ></jf-reasoning-block>`,
    )}`;
  }

  /**
   * THE ANSWER TAIL (tempdoc 822 Phase F11) — everything below a settled answer, in ONE row.
   *
   * Three stacked rows became one: the frame line's facts, the evidence disclosure (previously the
   * imported panel's own `▸ N SOURCES` header, on its own line, in a dialect the window speaks
   * nowhere else) and the copy action. The row is the design spec's assistant-message footer,
   * which is exactly this composition.
   *
   * It renders only when it has something in it, so a STREAMING turn has no row at all — the spec's
   * own rule (no footer while streaming) and today's behaviour unchanged.
   */
  private tail(turn: Sv3Turn): TemplateResult | typeof nothing {
    const facts = this.tailFacts(turn);
    const note = this.turnNote(turn);
    const sources = this.tailSources(turn);
    const copy = this.tailCopy(turn);
    const context = this.tailContextMenu(turn);
    // The pager is the one tail member that is NOT an action on this turn — it says which of several
    // versions of it is on screen — so it leads the controls rather than sitting among them.
    const versions = this.versionPager(turn);
    if (
      facts === nothing &&
      note === nothing &&
      sources === nothing &&
      copy === nothing &&
      context === nothing &&
      versions === nothing
    ) {
      return nothing;
    }
    return html`<div class="tail" data-testid="sv3-turn-tail">
      ${facts}${note}${sources}${versions}${copy}${context}
    </div>`;
  }

  /**
   * The turn's ⋯ overflow, and the ONE place the effective-context acts are reachable from a turn
   * (tempdoc 610 §13.1's split, applied to a window whose unit is a turn rather than a message).
   *
   * IT RENDERS ONLY WHEN THE TURN NAMES A STORE MESSAGE. A live turn carries a positional handle and
   * an agent turn's ids belong to the run plane, so neither can be the subject of a floor or an
   * exclusion — and the honest form of that is no control, not a control that fails when pressed
   * (852 §2.3b: an affordance that needs a backend id is unavailable until the turn has one).
   *
   * The trigger ANNOUNCES; the window decides what the menu contains, because the entries are
   * derived from the conversation's `/history` and only the window holds it.
   */
  private tailContextMenu(turn: Sv3Turn): TemplateResult | typeof nothing {
    // WINDOW-wide, not per-turn: the context of a prompt in flight is not editable, and the menu's
    // own derivation says so (`sv3ContextMenuItems` returns nothing while streaming). A trigger
    // gated only on THIS turn's status would render on every settled turn during someone else's
    // stream and open an empty menu — "a control that fails when pressed", which is the alternative
    // this slice's honest-null rule exists to refuse.
    if (this.streaming || turn.status === 'streaming') return nothing;
    const context = sv3TurnContextFor(this.turnContexts, turn.id);
    if (context === null || context.messageIds.length === 0) return nothing;
    return html`<jf-control
      class="tail-menu"
      data-testid="sv3-turn-context-menu"
      data-turn=${turn.id}
      label=${CONTEXT_MENU_LABEL}
      .onActivate=${() => this.requestContextMenu(turn.id)}
      >${icon({ name: 'more-horizontal', size: TAIL_GLYPH_SIZE })}</jf-control
    >`;
  }

  /**
   * The anchor is the TRIGGER's own rect — the one fact about where the menu goes that only this
   * region has. Looked up BY TURN, because the primitive's activation carries no event and the
   * first trigger in the transcript is a different turn's.
   */
  private requestContextMenu(turnId: string): void {
    const anchor = this.shadowRoot
      ?.querySelector(`.tail-menu[data-turn="${turnId}"]`)
      ?.getBoundingClientRect();
    this.dispatchEvent(
      new CustomEvent<Sv3ContextMenuRequest>(SV3_CONTEXT_MENU, {
        detail: { turnId, x: anchor?.left ?? 0, y: (anchor?.bottom ?? 0) + 4 },
        bubbles: true,
        composed: true,
      }),
    );
  }

  /** Every context act this region raises goes through one seam, for the window to resolve. */
  private contextAct(detail: Sv3ContextAction): void {
    this.dispatchEvent(
      new CustomEvent<Sv3ContextAction>(SV3_CONTEXT_ACTION, {
        detail,
        bubbles: true,
        composed: true,
      }),
    );
  }

  /**
   * Tempdoc 610 Phase C/D — the full-width line above the floor turn: everything above it is still
   * READ by the reader and no longer read by the model. Two forms, because they are two different
   * facts — a plain rewind DROPPED those turns, a compaction SUMMARIZED them — and the summary is
   * reachable, editable and revertible from the line that claims it.
   */
  private floorDivider(): TemplateResult {
    const summary = this.floorSummary;
    const compacted = summary !== null;
    return html`<div class="context-floor" data-testid="sv3-context-floor">
        ${/* The BOUNDARY is this hairline and only this hairline. `role="separator"` is
              children-presentational: a conforming screen reader prunes everything inside a node
              carrying it, which on the row below would have hidden Restore — the one way back from
              a floor — along with the summary's own controls. So the role sits on an empty rule and
              the row beside it is a plain group. */ ''}
        <div class="context-floor-rule" role="separator"></div>
        <div class="context-floor-line" role="group" aria-label=${CONTEXT_FLOOR_GROUP_LABEL}>
          <span class="context-floor-label" data-testid="sv3-context-floor-label"
            >${compacted ? CONTEXT_FLOOR_COMPACTED : CONTEXT_FLOOR_RESET}</span
          >
          ${compacted
            ? html`<jf-control
                class="context-floor-act"
                data-testid="sv3-context-floor-summary-toggle"
                label=${this.showFloorSummary ? CONTEXT_SUMMARY_HIDE : CONTEXT_SUMMARY_SHOW}
                .onActivate=${() => {
                  this.showFloorSummary = !this.showFloorSummary;
                  if (!this.showFloorSummary) this.editingFloorSummary = false;
                }}
                >${this.showFloorSummary ? CONTEXT_SUMMARY_HIDE : CONTEXT_SUMMARY_SHOW}</jf-control
              >`
            : nothing}
          ${compacted && this.showFloorSummary && !this.editingFloorSummary
            ? html`<jf-control
                class="context-floor-act"
                data-testid="sv3-context-floor-summary-edit"
                label=${CONTEXT_SUMMARY_EDIT_LABEL}
                .onActivate=${() => {
                  this.floorSummaryDraft = summary ?? '';
                  this.editingFloorSummary = true;
                }}
                >${CONTEXT_SUMMARY_EDIT}</jf-control
              >`
            : nothing}
          <jf-control
            class="context-floor-act"
            data-testid="sv3-context-floor-restore"
            label=${CONTEXT_FLOOR_RESTORE_LABEL}
            .onActivate=${() => this.contextAct({ action: 'restore' })}
            >${CONTEXT_FLOOR_RESTORE}</jf-control
          >
        </div>
        ${compacted && this.showFloorSummary ? this.floorSummaryBody(summary ?? '') : nothing}
      </div>`;
  }

  private floorSummaryBody(summary: string): TemplateResult {
    if (!this.editingFloorSummary) {
      return html`<p class="context-floor-summary" data-testid="sv3-context-floor-summary">
        ${summary}
      </p>`;
    }
    return html`<div class="context-floor-summary">
      <textarea
        class="context-floor-input"
        data-testid="sv3-context-floor-summary-input"
        aria-label=${CONTEXT_SUMMARY_INPUT_LABEL}
        .value=${this.floorSummaryDraft}
        @input=${(event: Event) => {
          this.floorSummaryDraft = (event.target as HTMLTextAreaElement).value;
        }}
      ></textarea>
      <div class="context-floor-summary-acts">
        <jf-control
          class="context-floor-act"
          data-testid="sv3-context-floor-summary-save"
          label=${CONTEXT_SUMMARY_SAVE}
          .onActivate=${() => {
            // The editor stays OPEN until the STORE says the text landed (it closes in `updated`
            // when the summary comes back as what was saved). Closing on the press would throw away
            // the reader's correction the moment the write was refused — the one state in which
            // they most need it back.
            this.pendingSummary = this.floorSummaryDraft;
            this.contextAct({ action: 'summary', text: this.floorSummaryDraft });
          }}
          >${CONTEXT_SUMMARY_SAVE}</jf-control
        >
        <jf-control
          class="context-floor-act"
          data-testid="sv3-context-floor-summary-cancel"
          label=${CONTEXT_SUMMARY_CANCEL}
          .onActivate=${() => {
            this.editingFloorSummary = false;
          }}
          >${CONTEXT_SUMMARY_CANCEL}</jf-control
        >
      </div>
    </div>`;
  }

  /**
   * C1 — the honest answer frame: what it is based on, how long it took, and which model wrote it in
   * the one case the composer would otherwise mislabel. The whole line is `sv3-honesty.ts`'s
   * derivation over the SHARED frame authority; this renders it and decides nothing, which is what
   * keeps the wording identical to the shipped window's.
   *
   * TWO NODES, ONE FACT. The authority's label is `"<verdict> — <elaboration>"`; the verdict is an
   * honesty fact and RESTS, the elaboration is elaboration by L14's own words and extends on pointer
   * (`title`). What is NOT done is hiding it from assistive tech: the `.visually-hidden` half carries
   * the authority's WHOLE string permanently, so nothing was hidden from AT and nothing has to be
   * revealed to it. The residual — a sighted keyboard-only reader sees the verdict, not the
   * elaboration — is accepted rather than paid for with a turn that resizes under the pointer or a
   * second tab stop per turn (tempdoc 822 F11 §2.6).
   */
  private tailFacts(turn: Sv3Turn): TemplateResult | typeof nothing {
    const frame = projectSv3AnswerFrame(turn, this.currentModelLabel, this.detailed);
    if (frame === null) return nothing;
    const verdict = frame.verdict ?? '';
    const label =
      verdict === '' || frame.elaboration === ''
        ? verdict
        : `${verdict} — ${frame.elaboration}`;
    // The separator lives ONLY inside this text node. Between the facts and the controls beside them
    // there is an 8px gap and no dot: a middle dot between a sentence and a button is nobody's idiom.
    const join = (head: string): string =>
      head === '' ? frame.tail : frame.tail === '' ? head : `${head} · ${frame.tail}`;
    const full = join(label);
    return html`<span
      class="tail-facts"
      role="note"
      data-testid="sv3-answer-frame"
      title=${full}
      ><span class="visually-hidden">${full}</span
      ><span aria-hidden="true">${join(verdict)}</span
    ></span>`;
  }

  /**
   * The window's ONE disclosure affordance for a turn's evidence (the fit audit's axis 3, answered
   * for this region): the design spec's own inline trigger, sentence case at the tail's 12px, opening the
   * SHARED panel beneath the row rather than a window-local source list.
   *
   * The count is not on the resting surface (the owner's direction) but is never lost: the
   * accessible name carries it always, so the calibration fact reaches AT unconditionally and the
   * sighted reader is one click from it. `SV3_SOURCES_COUNT_IN_TRIGGER` is the one-line flip.
   */
  private tailSources(turn: Sv3Turn): TemplateResult | typeof nothing {
    if (!this.panelSpeaks(turn)) return nothing;
    const trigger = sv3SourcesTrigger(turn.evidence);
    if (trigger === null) return nothing;
    const count = sv3SourcesTriggerCount(turn.evidence);
    const expanded = this.expandedSources.has(turn.id);
    return html`<button
      type="button"
      class="tail-sources"
      data-testid="sv3-turn-sources"
      aria-expanded=${expanded ? 'true' : 'false'}
      aria-controls=${expanded
        ? `${citeLegendId(turn.id)} ${sourcesBodyId(turn.id)}`
        : nothing}
      aria-label=${`${trigger}: ${count}`}
      @click=${() => this.toggleSources(turn.id)}
    >
      <span>${sv3SourcesTriggerLabel(trigger, count)}</span>
      ${icon({
        name: expanded ? 'chevron-down' : 'chevron-right',
        size: TAIL_CHEVRON_SIZE,
        className: 'tail-chevron',
      })}
    </button>`;
  }

  /** Per TURN, never global: opening turn 3's sources must not open turn 7's. */
  private toggleSources(id: string): void {
    const next = new Set(this.expandedSources);
    const opening = !next.delete(id);
    if (opening) next.add(id);
    this.expandedSources = next;
    if (!opening) return;
    // Tempdoc 859 §B — opening a disclosure GROWS the content, and the growth-gated snap (see
    // `updated`) would otherwise read that as new material to follow and jump the reader to the end
    // of the transcript instead of to the thing they just revealed. A reveal is a navigation
    // INTENT: it disarms the follow and then says where the view should go itself.
    this.followEnd = false;
    void this.revealSources(id);
  }

  /**
   * 859 §B (consumer #4) — put the revealed panel where the reader can see it.
   *
   * `block: 'nearest'` and not `'start'`: a panel already fully visible should not move the view at
   * all. Under the scroller's `scroll-padding-block-end` (859 §B §4.3) "nearest" now means "nearest
   * VISIBLE", so a panel that mounted behind the floating composer counts as off-screen and is
   * brought up — which is the whole point. Revealing something the reader asked for and leaving it
   * under the glass is the defect.
   *
   * Awaits the render that mounts it: the panel does not exist until `expandedSources` lands.
   */
  private async revealSources(id: string): Promise<void> {
    await this.updateComplete;
    // Matched on the ATTRIBUTE rather than interpolated into the selector: a turn id is a record id
    // this element does not author, and a selector built from foreign text is a parse waiting to
    // happen (a quote or a bracket throws out of querySelector, taking the reveal with it).
    const panels = this.shadowRoot?.querySelectorAll<HTMLElement>(
      '[data-testid="sv3-turn-citations"]',
    );
    for (const panel of panels ?? []) {
      if (panel.getAttribute('data-turn-id') !== id) continue;
      panel.scrollIntoView({ block: 'nearest' });
      return;
    }
  }

  /**
   * A9 — copy this answer. The ONE affordance in a turn that hides until the reader reaches for it;
   * every honesty fact beside it stays visible, which is L14's boundary drawn exactly where the
   * design spec draws its own. Icon-only since Phase F11, so the label is the
   * ACCESSIBLE name and the confirmation moved to the row's own live region — a name that changed to
   * "Copied" would rename the control instead of reporting the act.
   *
   * Offered only for an answer there IS: a streaming turn's text is still arriving, and a halted or
   * failed one is a fragment the reader did not ask to keep.
   */
  private tailCopy(turn: Sv3Turn): TemplateResult | typeof nothing {
    if (turn.kind !== 'ask' || turn.status !== 'complete' || turn.answer === '') return nothing;
    const copied = this.copiedTurnId === turn.id;
    return html`<button
        type="button"
        class="tail-copy"
        data-testid="sv3-turn-copy"
        aria-label=${TURN_COPY_LABEL}
        title=${TURN_COPY_LABEL}
        @click=${() => void this.copyAnswer(turn)}
      >
        ${icon({
          name: copied ? 'check-circle-2' : 'clipboard-copy',
          size: TAIL_GLYPH_SIZE,
        })}</button
      ><span
        class="visually-hidden"
        role="status"
        aria-live="polite"
        data-testid="sv3-turn-copy-status"
        >${copied ? TURN_COPY_DONE : ''}</span
      >`;
  }

  private async copyAnswer(turn: Sv3Turn): Promise<void> {
    // The util never throws and reports whether the write landed; a confirmation is shown only when
    // it did, so "Copied" is never said over an empty clipboard.
    if (!(await copyToClipboard(turn.answer))) return;
    if (this.copiedTimer !== null) clearTimeout(this.copiedTimer);
    this.copiedTurnId = turn.id;
    this.copiedTimer = setTimeout(() => {
      this.copiedTurnId = null;
      this.copiedTimer = null;
    }, TURN_COPY_FEEDBACK_MS);
  }

  /**
   * What the RECORD says happened in an agent turn that is not the live one (tempdoc 822 Phase F6;
   * inventory D1). Two sources, ONE renderer: `runItem` below draws both the live controller feed and
   * this, because they are the same three item shapes — so a run the reader watched and a run they
   * came back to cannot be drawn differently. The record's order is the record's, interleaved
   * (561 P-A), never re-sorted here.
   *
   * Empty until the record has spoken for the turn, which is also what a run that ended before this
   * window could refresh looks like — the receipt line below still says what it was.
   */
  private recordedActivity(turn: Sv3Turn): TemplateResult | typeof nothing {
    if (turn.activity.length === 0) return nothing;
    return html`<div class="run-feed" data-testid="sv3-record-activity">
      ${turn.activity.map((item) => this.runItem(item))}
    </div>`;
  }

  /**
   * Whether the shared panel has anything to render for this turn — the ONE test, asked by the
   * renderer below AND by {@link turnNote}, so the note can never repeat a count the panel is
   * already showing (`CitationsPanel.render` returns nothing when both sets are empty).
   */
  private panelSpeaks(turn: Sv3Turn): boolean {
    // Tempdoc 847 §2.4.4 — gated on the EVIDENCE, never on the turn's kind. `kind` is derived from
    // whether the record shows activity (`sv3-record.ts`), so one progress note on an ordinary
    // grounded ask flipped it to `agent` and hid the sources of the very turns most likely to have
    // them. A fact must be gated on itself, not on a classification that merely correlates with it
    // (the same shape 839 F2 fixed); `kind` still governs the activity feed, which is what it is
    // about.
    if (turn.status === 'streaming' || turn.evidence === null) return false;
    return turn.evidence.sources.length > 0 || turn.evidence.matches.length > 0;
  }

  /**
   * The answer's evidence, in the product's ONE citations panel — the same component every window
   * mounts on a landed answer, with the DISCLOSURE moved out to the tail row above (Phase F11).
   *
   * `externalDisclosure` is what makes the tail one line: without it the panel heads itself, on its
   * own row, in an uppercase dialect the window speaks nowhere else. The panel is mounted only while
   * open, so a collapsed turn contributes no box and no margin to the 30px tail — a permanently
   * mounted empty panel would push the next turn 8px down for a body nobody asked to see.
   */
  private citations(turn: Sv3Turn): TemplateResult | typeof nothing {
    if (!this.panelSpeaks(turn) || turn.evidence === null) return nothing;
    if (!this.expandedSources.has(turn.id)) return nothing;
    // §5.7 — the legend renders ONLY while the disclosure is open, which is what makes it free: the
    // reader who is looking at sources is exactly the reader who needs the key. It sits BEFORE the
    // panel and carries its own id, which the trigger's `aria-controls` names first: the disclosure
    // reveals two elements, so naming one of them left the key outside the announced relationship.
    return html`<p
      class="cite-legend"
      id=${citeLegendId(turn.id)}
      data-testid="sv3-cite-legend"
    >${CITE_LEGEND}</p>
    <jf-citations-panel
      class="sv3-citations"
      id=${sourcesBodyId(turn.id)}
      data-testid="sv3-turn-citations"
      data-turn-id=${turn.id}
      .externalDisclosure=${true}
      .sourcesExpanded=${true}
      .citations=${[...turn.evidence.matches]}
      .sources=${[...turn.evidence.sources]}
      .retrievalMode=${turn.evidence.retrievalMode}
      @citation-select=${this.onCitationSelect}
    ></jf-citations-panel>`;
  }

  /**
   * THE IN-WINDOW CITATION LANDING (tempdoc 822 Phase F8), and the one line in it that matters is
   * `stopPropagation`.
   *
   * `citation-select` is `bubbles: true, composed: true` from every producer (the panel above at
   * `components/chat/CitationsPanel.ts:291-296`, the inline `[n]` mark at
   * `components/chat/MarkdownBlock.ts:571-576`), and the Shell listens for it at the HOST with no
   * guard at all — "the ONE listener" (`chrome/Shell.ts:533-554`), which writes the cited document
   * onto the shared `state/inspectorState.ts` and thereby opens the SHIPPED window's reading pane.
   * Until F8 nothing collided only because the stage mounts one surface at a time; that is an
   * accident of the mount, not a guard. Stopping the event AT THE PRODUCING ELEMENT is the guard: an
   * in-window citation click is answered in-window, and the shared selection is not touched.
   *
   * What leaves this surface instead is the window's own `sv3-citation-open` — the window owns the
   * pane, because the pane is a region of the window grid whose width is clamped against the
   * sidebar's.
   */
  private readonly onCitationSelect = (event: Event): void => {
    event.stopPropagation();
    const detail = (event as CustomEvent<CitationSelectDetail>).detail;
    if (!detail?.parentDocId) return;
    // WHICH TURN raised it comes off the listening element, not off a per-turn closure: this handler
    // is bound on every turn's markdown block and citations panel, and a closure would be a new
    // function identity on every render — re-binding two listeners per turn on every streamed chunk.
    const turnId = (event.currentTarget as HTMLElement | null)?.dataset.turnId ?? '';
    const turn = this.turns.find((candidate) => candidate.id === turnId) ?? null;
    const sourceIndex = sv3SourceIndex(turn, detail);
    const anchor = sv3CitationAnchor(detail, sv3MatchedSentence(turn, sourceIndex));
    this.dispatchEvent(
      new CustomEvent<Sv3CitationOpen>(SV3_CITATION_OPEN, {
        detail: { docPath: detail.parentDocId, anchor, turnId, sourceIndex },
        bubbles: true,
        composed: true,
      }),
    );
  };

  /**
   * The live run: its feed, then the decisions it is parked on. Prompts come LAST and outside the
   * feed's own flow, because a held decision must not be something the reader can scroll past — the
   * same "incompressible occupant" rule the retired search-v2 window gave its run controls.
   *
   * `dispatching` is the optimistic window: the reader's task left and the server has not answered.
   * It is a distinct STATE, not an empty feed, so the window never has to imply progress it cannot
   * see (the handoff predicate in `sv3-run.ts` is what leaves it).
   */
  private runBody(run: Sv3RunView): TemplateResult {
    return html`
      <div class="run" data-testid="sv3-run" data-phase=${run.phase}>
        ${run.phase === 'dispatching'
          ? html`<p class="run-echo" data-testid="sv3-run-echo" role="status">${RUN_DISPATCHING}</p>`
          : html`
              <div class="run-feed" data-testid="sv3-run-feed">
                ${run.feed.items.map((item) => this.runItem(item))}
              </div>
            `}
        ${run.prompts.map((prompt) => this.runPrompt(prompt))}
      </div>
    `;
  }

  /**
   * ONE renderer for both the live feed and the record, so a run the reader watched and a run they
   * came back to cannot be drawn differently — which is also why ONE stamp per arm gives J/K the
   * same landmarks in a live conversation and a reloaded one. The retiree reaches the same coverage
   * only by merging two projections.
   */
  private runItem(item: Sv3RunFeedItem): TemplateResult {
    if (item.kind === 'text') {
      // The agent's prose is the same kind of text as an answer and gets the same renderer: a feed
      // that showed raw asterisks beside a settled turn that did not would be two markdown policies
      // in one transcript. Never streaming — a feed entry arrives whole.
      return html`<div class="answer" data-testid="sv3-run-text" data-item-id=${item.id}>
        <jf-markdown-block class="sv3-markdown" prose .text=${item.text}></jf-markdown-block>
      </div>`;
    }
    if (item.kind === 'tool') {
      return html`<jf-tool-call-card
        data-testid="sv3-run-tool"
        data-item-id=${item.id}
        .toolCall=${item.call}
        .stepPresentation=${null}
      ></jf-tool-call-card>`;
    }
    return html`<p
      class="run-note"
      data-testid="sv3-run-note"
      data-item-id=${item.id}
      data-label=${item.label}
    >
      <span class="run-note-label">${item.label}</span> ${item.text}
    </p>`;
  }

  /**
   * A typed prompt with its OWN controls. The APPROVAL arm deliberately carries
   * no Approve/Deny of its own: the product has exactly one approve/deny ceremony
   * (`operations/authorizationBroker.ts:14-21`, which those inline per-card buttons were retired
   * INTO), so this block SAYS what is held and lets the one ceremony ask. The two gates are the
   * window's to resolve, and each button is a dedicated typed command — never a sentence typed into
   * the composer, which refuses to send while any prompt is pending.
   */
  /**
   * 857 PR-A — a held decision is a J/K landmark, and the fourth stamp site is not optional: prompts
   * render OUTSIDE `.run-feed` precisely because "a held decision must not be something the reader
   * can scroll past", so a three-site plan would have made the one item this window most wants a
   * reader to reach the only run element the keyboard skips.
   *
   * The `:hold` suffix is load-bearing, not decoration. An APPROVAL prompt's id IS the tool call's
   * id — `projectSv3RunFeed` pushes `{kind:'tool', id: callId}` and `{kind:'approval', id: callId}`
   * from the same call (`sv3-run.ts:190, 194`) — so stamping `prompt.id` bare would put the same
   * `data-item-id` on the tool card and on the hold, and `jumpTo` resolves by first match: the hold
   * would be unreachable and the landmark list would carry a duplicate. Same reason the turn anchors
   * carry `:q`/`:a`.
   */
  private runPromptAnchorId(prompt: Sv3RunPrompt): string {
    return `${prompt.id}:hold`;
  }

  private runPrompt(prompt: Sv3RunPrompt): TemplateResult {
    if (prompt.kind === 'budget') {
      return html`
        <div class="run-prompt" role="group" aria-label="Budget decision" data-testid="sv3-run-prompt" data-item-id=${this.runPromptAnchorId(prompt)} data-kind="budget">
          <p class="run-prompt-text">
            The run needs ${prompt.tokensNeeded.toLocaleString()} more tokens;
            ${prompt.tokensRemaining.toLocaleString()} remain.
          </p>
          <!-- B8 — the REMEDY comes first (tempdoc 577 Ext III, views/UnifiedChatView.ts:3648): the
               other two arms both give something up, and offering them before the one that does not
               would put the concession where the reader looks first. The step is the shared
               RAISE_BUDGET_STEP_TOKENS, so the label cannot promise a different number than the
               directive spends. -->
          <jf-control
            data-testid="sv3-run-budget-raise"
            label=${`Add ${RAISE_BUDGET_STEP_TOKENS.toLocaleString()} tokens`}
            .onActivate=${() => this.decide({ kind: 'budget', decision: 'raise' })}
            >Add ${RAISE_BUDGET_STEP_TOKENS.toLocaleString()} tokens</jf-control
          >
          <jf-control
            data-testid="sv3-run-budget-finalize"
            label="Finish with what it has"
            .onActivate=${() => this.decide({ kind: 'budget', decision: 'finalize' })}
            >Finish with what it has</jf-control
          >
          <jf-control
            data-testid="sv3-run-budget-stop"
            label="Stop the run"
            .onActivate=${() => this.decide({ kind: 'budget', decision: 'stop' })}
            >Stop the run</jf-control
          >
        </div>
      `;
    }
    if (prompt.kind === 'context') {
      return html`
        <div class="run-prompt" role="group" aria-label="Context decision" data-testid="sv3-run-prompt" data-item-id=${this.runPromptAnchorId(prompt)} data-kind="context">
          <p class="run-prompt-text">
            The prompt is ${prompt.promptTokens.toLocaleString()} of
            ${prompt.contextWindow.toLocaleString()} tokens.
          </p>
          <jf-control
            data-testid="sv3-run-context-continue"
            label="Continue anyway"
            .onActivate=${() => this.decide({ kind: 'context', decision: 'continue' })}
            >Continue anyway</jf-control
          >
          <jf-control
            data-testid="sv3-run-context-summarize"
            label="Compact older turns"
            .onActivate=${() => this.decide({ kind: 'context', decision: 'summarize' })}
            >Compact older turns</jf-control
          >
          <jf-control
            data-testid="sv3-run-context-stop"
            label="Stop the run"
            .onActivate=${() => this.decide({ kind: 'context', decision: 'stop' })}
            >Stop the run</jf-control
          >
        </div>
      `;
    }
    return html`
      <div
        class="run-prompt"
        role="group"
        aria-label="Tool approval"
        data-testid="sv3-run-prompt"
        data-item-id=${this.runPromptAnchorId(prompt)}
        data-kind="approval"
      >
        <p class="run-prompt-text">
          ${prompt.toolName} is waiting for your approval (${prompt.risk.toLowerCase()} risk).
        </p>
      </div>
    `;
  }

  private decide(decision: Sv3RunDecision): void {
    this.dispatchEvent(
      new CustomEvent<Sv3RunDecision>(SV3_RUN_DECISION, {
        detail: decision,
        bubbles: true,
        composed: true,
      }),
    );
  }

  /**
   * What became of the turn, in words — the tail row's facts slot when the turn has no answer frame
   * of its own. A streaming turn says nothing: the text arriving IS the state, and a "generating…"
   * label beside moving text would be a second, redundant claim.
   */
  private turnNote(turn: Sv3Turn): TemplateResult | typeof nothing {
    if (turn.status === 'streaming') return nothing;
    const broken = turn.status === 'failed' || turn.status === 'refused';
    if (turn.kind === 'agent') {
      return html`<span
        class="tail-note"
        data-testid="sv3-run-receipt"
        data-outcome=${turn.status}
        data-broken=${String(broken)}
        >${sv3RunReceiptLabel(turn.toolCalls, turn.status)}</span
      >`;
    }
    const sources = sv3TurnSourceCount(turn);
    const note =
      turn.status === 'halted'
        ? TURN_HALTED
        : turn.status === 'refused'
          ? turn.detail
          : turn.status === 'failed'
            ? `${TURN_FAILED} ${turn.detail}`.trim()
            : // The completed turn's evidence line, and only when the panel is not already showing
              // it: `null` means the backend never said, which is not "0 sources", and a panel with
              // cards in it heads its own count — two of them would be one claim too many.
              sources === null || this.panelSpeaks(turn)
              ? ''
              : `${sources} ${sources === 1 ? 'source' : 'sources'}`;
    if (note === '') return nothing;
    return html`<span
      class="tail-note"
      data-testid="sv3-turn-note"
      data-broken=${String(broken)}
      >${note}</span
    >`;
  }

  private pending(): TemplateResult {
    return html`
      <div
        class="scroller sv3-scroller"
        data-testid="sv3-main-scroller"
        aria-busy="true"
        aria-label="Searching"
      >
        ${Array.from(
          { length: SKELETON_ROWS },
          () => html`
            <div class="skeleton-row" data-testid="sv3-main-skeleton" aria-hidden="true">
              <span class="skeleton-sheen sv3-anim-skeleton"></span>
            </div>
          `,
        )}
      </div>
    `;
  }

  /**
   * The request never reached the backend, so NOTHING is known about the corpus — which is why this
   * is its own state rather than the zero-results one wearing different words.
   */
  private unreachable(failure: string): TemplateResult {
    return html`
      <jf-sv3-empty
        roomy
        data-testid="sv3-main-unreachable"
        glyph="&#9634;"
        heading=${MAIN_UNREACHABLE.title}
        description=${MAIN_UNREACHABLE.description}
      >
        ${failure === ''
          ? nothing
          : html`<span class="failure-detail" data-testid="sv3-main-failure-detail"
              >${failure}</span
            >`}
      </jf-sv3-empty>
    `;
  }
}

customElements.define('jf-sv3-main', Sv3Main);

declare global {
  interface HTMLElementTagNameMap {
    'jf-sv3-main': Sv3Main;
  }
}
