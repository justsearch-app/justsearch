// SPDX-License-Identifier: Apache-2.0
/**
 * jf-sv3-session-row — the Search v3 sidebar's session row (tempdoc 822 slice 2).
 *
 * Derived from a third-party design system (MIT) — see THIRD-PARTY-NOTICES.md in this directory.
 *
 * ONE surface model for every row, per the design spec: the surface encodes INTERACTION (active,
 * selected, hover) and the content encodes STATUS. The spec tried the other way — elevated cards for live
 * threads, plain rows for settled ones — and recorded that it produced neither a hierarchy nor a
 * reliable hover cue.
 *
 * Precedence is written into the selectors, not into declaration order: the hover rule is GUARDED
 * out of the claimed states (`:host(:not([active]):not([selected]))`) so exactly one fill can ever
 * win. Declaration order alone would leave a later-added rule free to stack two fills.
 *
 * Status colour is a 3-budget: act-now, in-motion, broken. A resting row spends none
 * of it — its slot carries a muted timestamp instead, so colour keeps meaning "attend to this".
 *
 * The trailing slot SWAPS (tempdoc 822 Phase F3, extended to an action SET in tempdoc 831): status
 * at rest, the row's actions on hover or keyboard focus, hidden state out of flow, one width floor
 * so nothing jitters. With one exception, which is the whole reason the rule is quotable: an act-now
 * or broken status never yields — the spec's PR badge stays visible and clickable while the row is
 * hovered, and only the time label yields. Those two statuses are this window's honesty facts, so
 * the actions appear beside them instead of on top of them.
 *
 * Three actions, each backed by an operation the session list really has: rename (the F5 triad,
 * reached by pointer here and by F2 from the keyboard), pin (the F3 shelf move), and discard. The
 * discard is WITHHELD, not disabled, while work is in flight — a control that is present but inert
 * asks the reader to find out by pressing it.
 *
 * The RENAME is withheld the same way, on the same grounds, when no store session backs the
 * conversation (tempdoc 859 slice C PR-2, `store-backed`): a delegate conversation is persisted as
 * agent runs, and the rename endpoint writes to a `ConversationStore` session it has none of — so the
 * control would 404 rather than rename. Both gestures that reach the rename (double-click, F2) are
 * withheld with it: an affordance removed from the trailing slot and still reachable by keyboard
 * would be the same broken write behind a less visible door. The pin is LOCAL to this window and the
 * discard deletes the conversation's runs, so both stay.
 *
 * Side-effect registers <jf-sv3-session-row>.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import { icon } from '../../components/Icon.js';
import { sv3Shared } from './sv3-shared-styles.js';
import type { Sv3RowStatus } from './fixtures.js';

/** Screen-reader text for the three states that spend a colour; a resting row announces nothing. */
const STATUS_LABEL: Record<Exclude<Sv3RowStatus, 'resting'>, string> = {
  'act-now': 'New results',
  'in-motion': 'Searching',
  broken: 'Failed',
};

/** The row asks to be pinned or unpinned; the sidebar names which row, the window owns the list. */
export const SV3_SESSION_PIN_TOGGLE = 'sv3-session-pin-toggle';

/**
 * The rename triad (tempdoc 822 Phase F5). The row raises intent and holds no title of its own: the
 * one title lives in `sv3-sessions.ts`, so an edit in flight cannot become a second copy of it.
 */
export const SV3_SESSION_RENAME_START = 'sv3-session-rename-start';
export const SV3_SESSION_RENAME_COMMIT = 'sv3-session-rename-commit';
export const SV3_SESSION_RENAME_CANCEL = 'sv3-session-rename-cancel';

export interface Sv3SessionRenameCommit {
  readonly title: string;
}

/**
 * The row asks to be discarded (tempdoc 831). Like the pin, it names no session — the panel knows
 * which row this is, and the window owns the list and the authority the deletion is written to.
 * A row only ever raises this when it OFFERS the action, which a live conversation does not.
 */
export const SV3_SESSION_REMOVE_REQUEST = 'sv3-session-remove-request';

/** The shared icon set's glyphs, at the slim row's control size. */
const ACTION_GLYPH_SIZE = 14;

export class Sv3SessionRow extends JfElement {
  static styles = [
    sv3Shared,
    css`
      :host {
        display: block;
        /* The containing block for the action set, which overlays the row's trailing slot. The
           actions are SIBLINGS of the row button rather than children: a button inside a button is
           invalid, and the claim control must stay the row's one big target. */
        position: relative;
        /* The action set's width, as a token because the never-yields gutter below has to reserve
           exactly it (tempdoc 831) — three squares, and one narrower where the discard is withheld.
           Host-scoped, like every other measure this window authors. */
        --sv3-row-actions-inline: calc(3 * var(--space-6));
      }
      :host([live]),
      :host(:not([store-backed])) {
        --sv3-row-actions-inline: calc(2 * var(--space-6));
      }
      /* Both withheld: the pin is the only action left (tempdoc 859 slice C PR-2). */
      :host([live]:not([store-backed])) {
        --sv3-row-actions-inline: var(--space-6);
      }

      button.row {
        display: flex;
        align-items: center;
        gap: var(--sidebar-control-gap);
        width: 100%;
        /* The spec's SLIM SESSION row (h-9), not the menu-button ladder: the sidebar-comparison
           pass found the row had been built off the wrong referent, and these rows are sessions. */
        height: var(--space-9);
        padding-inline: var(--sidebar-row-content-inset);
        padding-block: 0.375rem;
        border: 0;
        border-radius: var(--control-radius);
        background: transparent;
        color: var(--sidebar-foreground);
        font-family: inherit;
        font-size: var(--font-size-sv3-sm);
        font-weight: 400;
        text-align: left;
        cursor: pointer;
        overflow: hidden;
        user-select: none;
        /* A long session list stays cheap without a virtualizer: the browser skips rendering work
           for rows outside the viewport, and the intrinsic size keeps the scrollbar honest. */
        content-visibility: auto;
        contain-intrinsic-size: auto var(--space-9);
        transition:
          background-color var(--duration-sv3-micro) var(--ease-sv3-enter),
          opacity var(--duration-sv3-micro) var(--ease-sv3-enter);
      }

      /* ── Surface: interaction only, in precedence order ──────────────────── */
      :host([active]) button.row {
        background: var(--sidebar-row-active);
        color: var(--sidebar-foreground);
      }
      :host([selected]:not([active])) button.row {
        background: var(--sidebar-row-selected);
        color: var(--sidebar-foreground);
      }
      :host(:not([active]):not([selected])) button.row:hover {
        background: var(--sidebar-row-hover);
      }
      /* Tempdoc 864 Layer 3(c)(i) — THE PARKED ROW IS AN ARMED TRIGGER, SO IT IS UNMISSABLE. Focus
         lands here by design and stays: tempdoc 831 keeps a pointerless reader off '<body>' after a
         rename or a discard, and 864 §2.7c keeps "'Space' activates a focused button" as the
         platform's contract rather than something the app breaks. What is left is to make the row a
         bare 'Space' would swap the conversation from impossible to miss while it holds focus — a
         2px hairline the reader was not looking at is what the incident walked into. Two marks, one
         hue: the ring at the repo's own focus idiom ('Control.ts' — 'outline: … solid var(--ring)'),
         plus the composer's halo idiom (tempdoc 859 §B's glass ring — a graded 'color-mix' of the
         SAME token, never a second colour). Both INSET: the row clips its own overflow and sits
         flush in the sidebar's inset, so anything drawn outward is trimmed. */
      button.row:focus-visible {
        outline: 2px solid var(--ring);
        outline-offset: -2px;
        box-shadow: inset 0 0 0 var(--space-2) color-mix(in srgb, var(--ring) 22%, transparent);
      }
      /* In-flight is ORTHOGONAL to the fill ladder: it dims the whole row and lifts on hover,
         and it yields entirely once the row is the claimed one. */
      :host([inflight]:not([active]):not([selected])) button.row {
        opacity: 0.7;
      }
      :host([inflight]:not([active]):not([selected])) button.row:hover {
        opacity: 1;
      }

      /* ── Content: the source glyph ───────────────────────────────────────── */
      .glyph {
        inline-size: var(--space-4);
        block-size: var(--space-4);
        flex-shrink: 0;
        border-radius: var(--radius-sm);
        background: var(--sidebar-control-surface);
        transition:
          opacity var(--duration-sv3-micro) var(--ease-sv3-enter),
          filter var(--duration-sv3-micro) var(--ease-sv3-enter);
      }
      /* Settled history recedes: dimmed glyph at rest, restored on hover so the tail stays
         scannable when you are hunting through it. */
      :host(:not([active])) .glyph {
        opacity: 0.4;
        filter: grayscale(1);
      }
      :host(:not([active])) button.row:hover .glyph {
        opacity: 1;
        filter: grayscale(0);
      }

      /* ── Content: the title ladder — emphasis is foreground ALPHA, never a hue ─ */
      .row-label {
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        color: color-mix(in srgb, var(--foreground) 90%, transparent);
      }
      :host([unread]) .row-label {
        color: var(--foreground);
        font-weight: 500;
      }
      :host([status='broken']) .row-label {
        color: color-mix(in srgb, var(--foreground) 95%, transparent);
      }
      :host([receded]:not([active]):not([selected])) .row-label {
        color: color-mix(in srgb, var(--sidebar-muted-foreground) 75%, transparent);
        font-weight: 400;
      }
      :host([receded]:not([active]):not([selected])) button.row:hover .row-label {
        color: var(--sidebar-foreground);
      }

      /* ── Content: the status / meta slot, and the action it swaps for ────── */
      .status-slot {
        position: relative;
        margin-left: auto;
        display: flex;
        align-items: center;
        justify-content: flex-end;
        block-size: var(--space-5);
        /* A floor on the slot so rows do not jitter as their contents change width. */
        min-inline-size: var(--space-8);
        flex-shrink: 0;
        font-size: var(--font-size-sv3-xs);
        /* Tempdoc 870 item 6b — the widening below (32px floor → the action set's width) SNAPPED in
           one frame while '.slot-content' faded out over the same 150ms, so the title's clip jumped
           before the status it was making room for had finished leaving. Both halves of one swap now
           run on the same duration and the same curve. Both ends are real lengths, which is what
           makes 'min-inline-size' interpolable here at all. */
        transition: min-inline-size var(--duration-sv3-micro) var(--ease-sv3-enter);
      }
      .slot-content {
        display: flex;
        align-items: center;
        transition: opacity var(--duration-sv3-micro) var(--ease-sv3-enter);
      }
      /* THE SWAP: the visible state owns the slot's width — status at rest, the action
         on hover or keyboard focus. The hidden state leaves the FLOW (absolute) so the title can
         reclaim the width without the two states overlapping, and the slot's min floor is what keeps
         the row from jittering as they trade places. The status-state guards are the exception rule
         below, written into the selector so a later rule cannot quietly stack on top of them.

         Each trigger is its OWN rule, and none of them nests :has() inside :host() — Chrome rejects
         that nesting as a syntax error, and an invalid member takes its whole selector list down
         with it, which is how the first cut of this swap passed its CSS-text unit tests while doing
         nothing at all in the browser (live-measured, 822 F3). */
      :host(:hover:not([compact]):not([status='act-now']):not([status='broken'])) .slot-content {
        position: absolute;
        inset-inline-end: 0;
        opacity: 0;
      }
      /* Keyboard, in two halves: the row itself focused, and an action focused. The action set is a
         FOLLOWING SIBLING of the row button, so the second half is a :has() on a plain element. */
      :host(:not([compact]):not([status='act-now']):not([status='broken']))
        button.row:focus-visible
        .slot-content {
        position: absolute;
        inset-inline-end: 0;
        opacity: 0;
      }
      :host(:not([compact]):not([status='act-now']):not([status='broken']))
        button.row:has(~ .actions button.act:focus-visible)
        .slot-content {
        position: absolute;
        inset-inline-end: 0;
        opacity: 0;
      }
      /* THE NEVER-YIELDS EXCEPTION (the spec's counterpoint: the PR badge "must remain visible AND
         clickable while the row is hovered. Only the time/jump label yields"). Act-now and broken are
         this window's PR badge: one says the run is blocked on the reader, the other that it failed,
         and a fact that only shows itself when the pointer is elsewhere is not a fact the reader can
         rely on. So the status keeps its place and the actions appear BESIDE it, in a gutter reserved
         at rest — reserving it on hover instead would move the dot, which is the jitter the floor
         exists to prevent. */
      :host([status='act-now']) .status-slot,
      :host([status='broken']) .status-slot {
        padding-inline-end: var(--sv3-row-actions-inline);
      }

      /* THE OTHER HALF OF THE SAME RESERVATION (tempdoc 831, D1). The gutter above only covers the
         two statuses that never yield; every OTHER row reserved just the slot's 32px floor, so a
         revealed 72px action set painted its icons over the tail of the title — 32px of text under
         the glyphs at the default sidebar width, at 2.91:1 against them (measured). A yielding row
         therefore widens its slot to the action set's width for exactly as long as the set is shown:
         the title truncates one ellipsis earlier while you are pointing at the row, and no glyph is
         ever composited under an icon.

         Widening the SLOT and not the label is what keeps this free of side effects — the slot is
         already the trailing reservation, so nothing moves but the title's clip. It is safe to do on
         hover here precisely because these are the rows whose slot content has yielded: there is no
         dot to move (that is why the never-yields rows keep their at-rest gutter instead).

         Same three triggers as the yield above, same guards, still three SEPARATE rules, and none of
         them nests :has() inside the host selector — Chrome's parse error there takes the whole
         selector list with it. */
      :host(:hover:not([compact]):not([status='act-now']):not([status='broken'])) .status-slot {
        min-inline-size: var(--sv3-row-actions-inline);
      }
      :host(:not([compact]):not([status='act-now']):not([status='broken']))
        button.row:focus-visible
        .status-slot {
        min-inline-size: var(--sv3-row-actions-inline);
      }
      :host(:not([compact]):not([status='act-now']):not([status='broken']))
        button.row:has(~ .actions button.act:focus-visible)
        .status-slot {
        min-inline-size: var(--sv3-row-actions-inline);
      }

      /* ── The ACTION SET (tempdoc 831) ────────────────────────────────────────
         The slot swaps for a SET, not a single control: rename, pin, and — only where it is safe —
         discard. One flex group, absolutely positioned over the row's trailing edge, so the whole
         set is out of the flow and the swap cannot change the row's height by construction.

         Its width is the --sv3-row-actions-inline token declared on the host above, because the
         never-yields gutter has to reserve exactly it — and a conversation with work in flight
         offers no discard, so the set is one square narrower there. Reserving the wider figure for
         both would leave a gutter with nothing in it, which is width taken from the title for no
         fact. */
      .actions {
        position: absolute;
        inset-inline-end: var(--sidebar-row-content-inset);
        top: 50%;
        transform: translateY(-50%);
        display: flex;
        align-items: center;
        /* The GROUP is never a target — only its buttons are, and only once revealed. A group box
           that took the hit would make the row's trailing strip dead to the claim click even at
           rest, when there is nothing there to press. A child re-enabling itself still receives
           events, which is exactly what the reveal below does. */
        pointer-events: none;
      }
      button.act {
        display: flex;
        align-items: center;
        justify-content: center;
        inline-size: var(--space-6);
        block-size: var(--space-6);
        padding: 0;
        border: 0;
        border-radius: var(--control-radius);
        background: transparent;
        color: var(--icon-muted);
        cursor: pointer;
        opacity: 0;
        /* At rest the action is not a pointer target: the whole row is one claim target, and an
           invisible button that swallowed the click would make the row's edge lie. Keyboard reach is
           unaffected — the button stays in the tab order and reveals itself on focus. */
        pointer-events: none;
        transition: opacity var(--duration-sv3-micro) var(--ease-sv3-enter);
      }
      /* Three SEPARATE reveal rules, never one list with a nested :has() in it (the F3 defect
         quoted above): the pointer, the row's own focus, and the action's focus each stand alone,
         so an invalid member cannot take the other two down with it. */
      :host(:hover) button.act {
        opacity: 1;
        pointer-events: auto;
      }
      button.row:focus-visible ~ .actions button.act {
        opacity: 1;
        pointer-events: auto;
      }
      button.act:focus-visible {
        opacity: 1;
        pointer-events: auto;
        outline: 2px solid var(--ring);
        outline-offset: -1px;
      }
      button.act:hover {
        background: var(--sidebar-row-active);
        color: var(--sidebar-foreground);
      }
      /* Pressed state as foreground weight, not a hue: the 3-colour budget is for status only. */
      :host([pinned]) button.pin {
        color: var(--sidebar-foreground);
      }
      .meta {
        color: var(--secondary-label);
        font-variant-numeric: tabular-nums;
      }
      .dot-box {
        position: relative;
        display: inline-flex;
        inline-size: var(--space-3);
        block-size: var(--space-3);
        align-items: center;
        justify-content: center;
      }
      .dot {
        inline-size: var(--space-2);
        block-size: var(--space-2);
        border-radius: 50%;
      }
      .ping {
        position: absolute;
        inset: 0;
        border-radius: 50%;
      }
      :host([status='act-now']) .dot {
        background: var(--success);
      }
      :host([status='in-motion']) .dot {
        background: var(--warning);
      }
      :host([status='broken']) .dot {
        background: var(--destructive);
      }
      :host([status='in-motion']) .ping {
        background: color-mix(in srgb, var(--warning) 60%, transparent);
      }

      /* ── COMPACT: the row on the 3rem icon rail (tempdoc 822 Phase F5) ───────────────────────
         The spec's icon mode squares the menu button
         (group-data-[collapsible=icon]:size-8 plus its content inset) and HIDES the row's action
         (group-data-[collapsible=icon]:hidden on the menu action). The label goes with them:
         48px minus the panel's two 8px insets is not a place a title can be read.

         WHAT DOES NOT GO: the status dot. Act-now means the run is blocked on the reader and broken
         means it failed — the same two facts the never-yields exception above refuses to let a hover
         take away, and collapsing the rail is no better a reason than hovering was. So the slot
         leaves the flow and becomes a corner badge on the square, and only the RESTING slot's
         timestamp is dropped (it is the one thing here that spends no colour and claims nothing). */
      :host([compact]) button.row {
        inline-size: var(--space-8);
        /* The rail's 48px is a TOTAL: its 8px insets and 1px border come out of it (border-box), so
           the content column is 31px and the spec's 32px square would overflow it by one pixel.
           The square is still the declared size; the column is what bounds it. */
        max-inline-size: 100%;
        block-size: var(--space-8);
        padding-inline: 0;
        justify-content: center;
        contain-intrinsic-size: auto var(--space-8);
      }
      :host([compact]) .row-label,
      :host([compact]) .meta,
      :host([compact]) .actions {
        display: none;
      }
      :host([compact]) .status-slot {
        position: absolute;
        inset-block-start: 0;
        inset-inline-end: 0;
        min-inline-size: 0;
        block-size: auto;
        padding-inline-end: 0;
      }

      /* ── RENAMING: the spec's inline input, in the row's own box ─────────────────────────────
         The spec swaps its title span for an autoFocused input in place.
         Ours swaps the whole ROW ELEMENT with it, because our row is a native button and an input
         inside a button is invalid content — and because a row that is being renamed is not a
         navigation target: the reader is editing it, not going into it. */
      .row-static {
        display: flex;
        align-items: center;
        gap: var(--sidebar-control-gap);
        width: 100%;
        height: var(--space-9);
        padding-inline: var(--sidebar-row-content-inset);
        padding-block: 0.375rem;
        border-radius: var(--control-radius);
        background: var(--sidebar-row-active);
        overflow: hidden;
      }
      input.rename {
        min-width: 0;
        flex: 1 1 auto;
        border: 1px solid var(--input);
        border-radius: var(--radius-sm);
        padding-inline: var(--space-1);
        background: var(--card);
        color: var(--card-foreground);
        font-family: inherit;
        font-size: var(--font-size-sv3-sm);
        font-weight: 500;
        outline: none;
      }
      input.rename:focus {
        border-color: var(--foreground);
        /* Transparent, not none: forced colors repaint it, and there the border change is erased. */
        outline: 2px solid transparent;
        outline-offset: -2px;
      }

      /* Forced colors (Windows High Contrast) erase the fill ladder above, so the claimed rows restate
         theirs in system colours: the active row takes the selection pair, the selected one an edge.
         'forced-color-adjust: none' keeps the browser from painting its Canvas backplate behind the
         row's text, which would put HighlightText on Canvas; the descendants are given HighlightText
         by hand for the same reason. The focus ring on the active row turns HighlightText, because
         it is drawn INSET, on the fill. */
      @media (forced-colors: active) {
        :host([active]) button.row,
        :host([active]) button.act {
          forced-color-adjust: none;
          background: Highlight;
          color: HighlightText;
        }
        :host([active]) button.row * {
          color: HighlightText;
        }
        :host([active]) button.row:focus-visible,
        :host([active]) button.act:focus-visible {
          outline-color: HighlightText;
        }
        :host([selected]:not([active])) button.row {
          outline: 1px solid Highlight;
          outline-offset: -1px;
        }
        :host([selected]:not([active])) button.row:focus-visible {
          outline-width: 2px;
          outline-offset: -2px;
        }
      }

      @media (prefers-reduced-motion: reduce) {
        button.row,
        button.act,
        .slot-content,
        .status-slot,
        .glyph {
          transition: none;
        }
      }
      :host([active]) { --sv3-row-actions-inline: calc(4 * var(--space-6)); }
      :host([active][live]), :host([active]:not([store-backed])) { --sv3-row-actions-inline: calc(3 * var(--space-6)); }
      :host([active][live]:not([store-backed])) { --sv3-row-actions-inline: calc(2 * var(--space-6)); }
      :host([active]:not([compact])) button.act { opacity: 1; pointer-events: auto; }
      :host([active]:not([compact]):not([status='act-now']):not([status='broken'])) .slot-content { opacity: 0; position: absolute; }
      :host([active]:not([compact]):not([status='act-now']):not([status='broken'])) .status-slot { inline-size: var(--sv3-row-actions-inline); }

    `,
  ];

  static properties = {
    label: { type: String, attribute: false },
    meta: { type: String, attribute: false },
    status: { type: String, reflect: true },
    active: { type: Boolean, reflect: true },
    selected: { type: Boolean, reflect: true },
    receded: { type: Boolean, reflect: true },
    unread: { type: Boolean, reflect: true },
    inflight: { type: Boolean, reflect: true },
    pinned: { type: Boolean, reflect: true },
    live: { type: Boolean, reflect: true },
    storeBacked: { type: Boolean, reflect: true, attribute: 'store-backed' },
    compact: { type: Boolean, reflect: true },
    renaming: { type: Boolean, reflect: true },
  };

  declare label: string;
  declare meta: string;
  declare status: Sv3RowStatus;
  declare active: boolean;
  declare selected: boolean;
  declare receded: boolean;
  declare unread: boolean;
  declare inflight: boolean;
  declare pinned: boolean;
  /**
   * Work is in flight in this conversation (tempdoc 831). The row does not decide this — the window
   * projects it ({@link file://./sv3-sessions.ts} `sv3SessionIsLive`), and the same predicate refuses
   * the removal downstream, so a row that offers no discard and a list that would decline one are
   * saying the same thing.
   */
  declare live: boolean;
  /**
   * A `ConversationStore` session backs this conversation (tempdoc 859 slice C PR-2). Defaults TRUE:
   * every conversation this window could show before the list joined a second record had one, so the
   * default keeps every existing row's action set exactly as it was. The window projects it
   * ({@link file://./sv3-sessions.ts} `Sv3SessionRowView.storeBacked`) from what the list endpoint
   * reported; the row never guesses it.
   */
  declare storeBacked: boolean;
  /** The sidebar is on its collapsed icon rail; the row is a 32px square. */
  declare compact: boolean;
  /** The reader is editing this row's title (tempdoc 822 Phase F5). */
  declare renaming: boolean;

  constructor() {
    super();
    this.label = '';
    this.meta = '';
    this.status = 'resting';
    this.active = false;
    this.selected = false;
    this.receded = false;
    this.unread = false;
    this.inflight = false;
    this.pinned = false;
    this.live = false;
    this.storeBacked = true;
    this.compact = false;
    this.renaming = false;
  }

  /**
   * The pin is a SECOND action inside one row, so the claim must not also fire: a reader pinning a
   * conversation is not asking to be taken into it. Every action in the set stops the same way, for
   * the same reason — acting ON a row is not asking to be taken into it.
   */
  private togglePin(event: Event): void {
    event.stopPropagation();
    this.dispatchEvent(new CustomEvent(SV3_SESSION_PIN_TOGGLE, { bubbles: true, composed: true }));
  }

  /** The pointer's route to the rename the keyboard reaches with F2 — one intent, two affordances. */
  private startRename(event: Event): void {
    event.stopPropagation();
    if (this.renaming) return;
    this.dispatchEvent(new CustomEvent(SV3_SESSION_RENAME_START, { bubbles: true, composed: true }));
  }

  /**
   * The discard — named for the intent rather than `remove`, which on an HTMLElement subclass would
   * shadow the DOM's own `Element.remove()`. It is raised UNCONDITIONALLY because the control only exists on a row
   * that may be discarded — a guard repeated at the dispatch site would be a second place for the
   * rule to be wrong, and the window refuses a live removal in the list itself regardless.
   */
  private discard(event: Event): void {
    event.stopPropagation();
    this.dispatchEvent(
      new CustomEvent(SV3_SESSION_REMOVE_REQUEST, { bubbles: true, composed: true }),
    );
  }

  /**
   * The spec's rename trigger: a DOUBLE-CLICK on the row, with its two
   * guards — a modified double-click is a selection gesture, and a double-click that landed on a
   * control inside the row belongs to that control.
   */
  private onRowDoubleClick(event: MouseEvent): void {
    if (!this.storeBacked || this.renaming) return;
    if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    if ((event.target as HTMLElement | null)?.closest('.actions') !== null) return;
    event.preventDefault();
    this.dispatchEvent(
      new CustomEvent(SV3_SESSION_RENAME_START, { bubbles: true, composed: true }),
    );
  }

  /**
   * The keyboard half of the same trigger. The spec reaches rename by a row MENU it has and this
   * window does not, so the gesture is F2 — the platform convention for
   * renaming the focused item, and the only way a pointerless reader gets to the affordance at all.
   */
  private onRowKeydown(event: KeyboardEvent): void {
    if (event.key !== 'F2' || this.renaming || !this.storeBacked) return;
    event.preventDefault();
    this.dispatchEvent(
      new CustomEvent(SV3_SESSION_RENAME_START, { bubbles: true, composed: true }),
    );
  }

  /** Enter commits, Escape cancels, per the design spec. */
  private onRenameKeydown(event: KeyboardEvent): void {
    // The row list is inside the window's own key handling; an edit's keys are the edit's alone.
    event.stopPropagation();
    if (event.key !== 'Enter' && event.key !== 'Escape') return;
    event.preventDefault();
    // Both keyboard routes out of the edit have to LAND somewhere (tempdoc 831, audit advisory):
    // the input is removed when the edit closes, and focus falls to <body> — a pointerless reader
    // ends up at the top of the document having lost the row they were naming.
    this.restoreFocusOnRenameExit = true;
    if (event.key === 'Enter') {
      this.commitRename(event.currentTarget as HTMLInputElement);
      return;
    }
    this.renameCommitted = true;
    this.dispatchEvent(
      new CustomEvent(SV3_SESSION_RENAME_CANCEL, { bubbles: true, composed: true }),
    );
  }

  /** Blur commits what is there, unless a key already settled it, per the design spec. */
  private onRenameBlur(event: FocusEvent): void {
    if (this.renameCommitted) return;
    this.commitRename(event.currentTarget as HTMLInputElement);
  }

  /** Latch so the blur that FOLLOWS an Enter or an Escape does not commit a second time. */
  private renameCommitted = false;

  /**
   * The edit was left BY KEY, so the focus it is about to lose is owed back to the row. Set only on
   * the keyboard routes: a blur-commit means the reader clicked something else, and taking their
   * focus back from whatever they just clicked would be the worse bug.
   */
  private restoreFocusOnRenameExit = false;

  private commitRename(input: HTMLInputElement): void {
    this.renameCommitted = true;
    this.dispatchEvent(
      new CustomEvent<Sv3SessionRenameCommit>(SV3_SESSION_RENAME_COMMIT, {
        detail: { title: input.value },
        bubbles: true,
        composed: true,
      }),
    );
  }

  /**
   * Put the keyboard back on this row's claim control (tempdoc 831). The button lives in the shadow
   * root, so the panel above cannot reach it — the row owns its own focus, and this is the one way
   * in. Used when a row is discarded (the survivor takes the focus) and when an edit is left by key.
   */
  focusRow(): void {
    this.shadowRoot?.querySelector<HTMLButtonElement>('button.row')?.focus();
  }

  protected override updated(changed: Map<string, unknown>): void {
    if (!changed.has('renaming')) return;
    if (!this.renaming) {
      // The edit closed. If a key closed it, the input that held the focus is gone now and the row
      // takes it back; anything else means the reader moved on themselves.
      if (!this.restoreFocusOnRenameExit) return;
      this.restoreFocusOnRenameExit = false;
      this.focusRow();
      return;
    }
    this.renameCommitted = false;
    // The spec's `autoFocus` + `onFocus → select()`: the edit opens with
    // the old title selected, so typing replaces it and Escape still has something to restore to.
    const input = this.shadowRoot?.querySelector<HTMLInputElement>('input.rename');
    input?.focus();
    input?.select();
  }

  /**
   * The row while it is being edited. The input's value is UNCONTROLLED (`.value` set once, on the
   * first render of the edit) — the committed title is the session's, and a keystroke round-tripping
   * through the window would put the caret at the mercy of a re-render.
   */
  private renameRow(): TemplateResult {
    return html`
      <div class="row-static" data-testid="sv3-session-row-renaming">
        <span class="glyph" aria-hidden="true"></span>
        <input
          class="rename"
          type="text"
          aria-label="Conversation title"
          .value=${this.label}
          data-testid="sv3-session-row-rename-input"
          @keydown=${this.onRenameKeydown}
          @blur=${this.onRenameBlur}
        />
      </div>
    `;
  }

  /**
   * An action's accessible name says WHICH conversation it acts on: a screen reader walking the set
   * hears three "Delete" buttons otherwise, one per row, and cannot tell them apart. The visible
   * tooltip stays the bare verb — the row it is sitting on is already on screen.
   */
  private named(verb: string): string {
    return this.label === '' ? `${verb} conversation` : `${verb} ${this.label}`;
  }

  render(): TemplateResult {
    if (this.renaming) return this.renameRow();
    const colored = this.status !== 'resting';
    return html`
      <button
        type="button"
        class="row"
        aria-current=${this.active ? 'true' : nothing}
        aria-label=${this.compact && this.label !== '' ? this.label : nothing}
        title=${this.compact && this.label !== '' ? this.label : nothing}
        data-testid="sv3-session-row-button"
        @dblclick=${this.onRowDoubleClick}
        @keydown=${this.onRowKeydown}
      >
        <span class="glyph" aria-hidden="true"></span>
        <span class="row-label">${this.label}</span>
        <span class="status-slot">
          <span class="slot-content" data-testid="sv3-session-row-slot">
            ${colored
              ? html`
                  <span
                    class="dot-box"
                    role="img"
                    aria-label=${STATUS_LABEL[this.status as Exclude<Sv3RowStatus, 'resting'>]}
                    data-testid="sv3-session-row-status"
                  >
                    ${this.status === 'in-motion'
                      ? html`<span class="ping sv3-anim-status-ping" aria-hidden="true"></span>`
                      : nothing}
                    <span class="dot"></span>
                  </span>
                `
              : html`<span class="meta" data-testid="sv3-session-row-meta">${this.meta}</span>`}
          </span>
        </span>
      </button>
      <div class="actions" data-testid="sv3-session-row-actions">
        ${this.active ? html`<button type="button" class="act export"
          aria-label=${this.named('Export')} title="Export conversation"
          data-testid="sv3-session-row-export" @click=${(event: Event) => {
            event.stopPropagation();
            this.dispatchEvent(new CustomEvent('sv3-session-export', { bubbles: true, composed: true }));
          }}>${icon({ name: 'download', size: ACTION_GLYPH_SIZE })}</button>` : nothing}
        ${this.storeBacked
          ? html`
              <button
                type="button"
                class="act rename"
                aria-label=${this.named('Rename')}
                title="Rename"
                data-testid="sv3-session-row-rename"
                @click=${this.startRename}
              >
                ${icon({ name: 'pencil', size: ACTION_GLYPH_SIZE })}
              </button>
            `
          : nothing}
        <button
          type="button"
          class="act pin"
          aria-pressed=${this.pinned ? 'true' : 'false'}
          aria-label=${this.label === '' ? 'Pin conversation' : `Pin ${this.label}`}
          title="Pin"
          data-testid="sv3-session-row-pin"
          @click=${this.togglePin}
        >
          ${icon({ name: 'bookmark', size: ACTION_GLYPH_SIZE })}
        </button>
        ${this.live
          ? nothing
          : html`
              <button
                type="button"
                class="act remove"
                aria-label=${this.named('Delete')}
                title="Delete"
                data-testid="sv3-session-row-remove"
                @click=${this.discard}
              >
                ${icon({ name: 'trash-2', size: ACTION_GLYPH_SIZE })}
              </button>
            `}
      </div>
    `;
  }
}

customElements.define('jf-sv3-session-row', Sv3SessionRow);

declare global {
  interface HTMLElementTagNameMap {
    'jf-sv3-session-row': Sv3SessionRow;
  }
}
