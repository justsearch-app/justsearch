// SPDX-License-Identifier: Apache-2.0
/**
 * jf-sv3-composer — the Search v3 window's composer (tempdoc 822 slice 3).
 *
 * Derived from a third-party design system (MIT) — see THIRD-PARTY-NOTICES.md in this directory.
 *
 * The design spec's composer anatomy, minus the welded-tray `clip-path` the charter excludes — and
 * therefore minus the layer split that tray exists for: radius, glass fill, blur and elevation all
 * sit on ONE node, with only the 1px outline ring on a `::after` above the content.
 *
 * The material is entirely token-fed (`--composer-*`), which is what makes the dark ELEVATION
 * INVERSION expressible at all: the spec writes it as `.dark` rules, and a selector inside a shadow
 * root cannot see a class on `<html>` (§8.3). Light casts a shadow down; dark removes the shadow and
 * catches light on its top edge instead.
 *
 * The composer OWNS the draft and nothing else: sending announces the draft (Phase A1's
 * `sv3-composer-submit`) and the window decides what that means — which is now ASKING the local
 * model (Phase F1). It does not dock itself and it knows neither the search store nor the ask client
 * — the alternative would put a second issuance site here.
 *
 * Its primary-action slot holds exactly ONE control, per the design spec:
 * Send, or Stop while a response streams. Never both, and never one disabled behind the other.
 *
 * ONE component in TWO states. HERO centres it in the main region under a headline (the empty
 * window); DOCKED returns it to the bottom band (the working window). Docking evaporates the control
 * LABEL leftward into its glyph (§5.9's signature compaction) and the window morphs the moving
 * box with the view transition in `sv3-composer-morph.ts` (§5.5).
 *
 * Its control row holds TWO controls — the MODE the next draft is sent at (852 S4: ask the model, or
 * delegate to the agent) and the EFFORT rung it carries (tempdoc 822 Phase F10) — and, beside them,
 * ONE FACT (Phase F11): which model would answer. Controls first, fact second,
 * the design spec's own footer order; mode before effort, because effort qualifies a send whose
 * destination the mode has already decided. Slice 3's two scope PLACEHOLDERS are gone with them — they stood for
 * the search axis the §4b standing directive defers indefinitely and did nothing when clicked.
 *
 * Side-effect registers <jf-sv3-composer>.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import { icon, type IconName } from '../../components/Icon.js';
// The product's ONE operability primitive (tempdoc 559 Authority V; 852 parity ledger row 11). The
// two REMEDIES in this element are born on it; the menu triggers, the menu rungs, the disclosure and
// the primary slot are not, and each of those exceptions is stated at its own render site.
import '../../components/Control.js';
import { sv3Shared } from './sv3-shared-styles.js';
import {
  COMPOSER_PLACEHOLDER,
  COMPOSER_STATE_DEFAULT,
  CORPUS_ADD_FOLDERS,
  CORPUS_REMEDY_TARGET,
  HERO_HEADLINE,
  SV3_DEGRADATION_DETAIL_ID,
  SV3_DEGRADATION_GLYPH_SIZE,
  SV3_DEGRADATION_HEADLINE_ID,
  SV3_DEGRADATION_LESS,
  SV3_DEGRADATION_MORE,
  type Sv3ComposerState,
} from './fixtures.js';
import {
  SV3_EFFORT_DEFAULT,
  SV3_EFFORT_MENU_LABEL,
  SV3_EFFORT_OPTIONS,
  sv3EffortLabel,
  type Sv3Effort,
} from './sv3-ask.js';
import {
  sv3PrimaryAction,
  SV3_TIER_DEFAULT,
  SV3_TIER_MENU_LABEL,
  SV3_TIER_OPTIONS,
  sv3TierLabel,
  type Sv3ComposerTier,
  type Sv3SlotKind,
} from './sv3-run.js';
import {
  SV3_CORPUS_UNKNOWN,
  SV3_REMEDY,
  type Sv3Corpus,
  type Sv3RemedyDetail,
} from './sv3-honesty.js';
import { sv3ComposerReason, type Sv3Degradation } from './sv3-degradation.js';

/** Raised when the composer asks the window for the other state; the window owns the morph. */
export const SV3_COMPOSER_STATE_REQUEST = 'sv3-composer-state-request';

export interface Sv3ComposerStateRequest {
  readonly state: Sv3ComposerState;
}

/**
 * Raised when the draft is SENT (tempdoc 822 Phase A1). The composer holds the draft and therefore
 * announces it; the window decides what a send means — issuing the search and docking are both its
 * calls, made once, in one handler. Every affordance that sends (the control, Enter) goes through
 * {@link Sv3Composer.submit}, so there is exactly one place a send can originate.
 */
export const SV3_COMPOSER_SUBMIT = 'sv3-composer-submit';

/**
 * Which tier the reader routed the draft to (tempdoc 822 Phase F2; declared in `sv3-run.ts`, the
 * module that owns the send-routing vocabulary, and re-exported here so this element's submit
 * contract reads in one file).
 *
 * Until 852 S4 the keys were the WHOLE difference — Enter asked, Ctrl+Enter delegated, and the only
 * statement of that was the send slot's aria-label. A routing a reader can only discover by pressing
 * a chord they were never told about is not an affordance, so the control row gained a mode control
 * (§3.2) and the chord stayed as its accelerator.
 */
export type { Sv3ComposerTier };

export interface Sv3ComposerSubmit {
  readonly query: string;
  readonly tier: Sv3ComposerTier;
}

/**
 * Raised when the reader halts a streaming response (tempdoc 822 Phase F1). The window holds the
 * AbortController, so the composer announces the intent and nothing else.
 */
export const SV3_COMPOSER_STOP = 'sv3-composer-stop';

/**
 * Raised by the `answer` rung of the primary slot (tempdoc 822 Phase F2). The composer cannot resolve
 * a typed prompt — that is the point of pattern (f) — so the control does the one thing it honestly
 * can: it asks the window to take the reader to the decision that is holding the run.
 */
export const SV3_COMPOSER_ANSWER = 'sv3-composer-answer';

/**
 * Raised when the reader picks an effort rung (tempdoc 822 Phase F10). The composer owns the
 * control, never the choice: the window holds the rung and stamps it on the next dispatch, which is
 * the same boundary the draft's own submit event draws.
 */
export const SV3_EFFORT_CHANGE = 'sv3-effort-change';

export interface Sv3EffortChange {
  readonly effort: Sv3Effort;
}

/**
 * Raised when the reader picks a send TIER (852 S4). Same boundary as the effort rung and the draft:
 * the composer owns the control, the window owns the value and stamps it on the next dispatch. The
 * composer never routes anything itself — a tier it kept would be a second answer to "what does Enter
 * do" beside the window's own.
 */
export const SV3_TIER_CHANGE = 'sv3-tier-change';

export interface Sv3TierChange {
  readonly tier: Sv3ComposerTier;
}

/** The spec's composer control icon at its default optical size (`size-4`). */
const CONTROL_GLYPH_SIZE = 16;

/** The spec's composer control chevron — `size-3.5`. */
const CHEVRON_SIZE = 14;

/**
 * The effort control's glyph. The design spec's equivalent trigger carries a bolt for the fast
 * rung; this control wears it always, because docking evaporates the
 * label and the glyph is then the only thing left to say what the control is about.
 */
const EFFORT_GLYPH = 'zap';

/**
 * The mode control's glyph (852 S4). A ROUTING mark, not a destination: the control chooses WHERE a
 * draft goes, so a `bot` would name one of its two tiers and have nothing left to wear for the other
 * — and `send` is already the primary control's own mark. Worn always, for the reason the effort
 * glyph is: docking evaporates the label and the glyph is then all the control has left to say.
 */
const TIER_GLYPH = 'arrow-right-left';

export class Sv3Composer extends JfElement {
  static styles = [
    sv3Shared,
    css`
      /* Tempdoc 859 §B — no 'flex-shrink' any more: the composer is no longer a flex item of the
         window's column. Both states are out of the flow now (hero centres itself over the content
         region; docked rides the floating '.dock' wrapper), so the declaration described a layout
         that no longer exists. */
      :host {
        display: block;
        padding: var(--floating-content-inset);
        font-family: var(--font-sans);
      }

      /* HERO and DOCKED are both overlays over the content region since 859 §B; what distinguishes
         them is WHERE and HOW MUCH. Hero owns the whole region below the topbar and centres itself
         in it — hence the flex centring and the topbar-token top inset, rather than a repeated
         number. Docked is bottom-anchored and publishes its measured height as the transcript's
         occluded band; its positioning lives on the window's '.dock', not here, because the context
         bar rides with it and the pair has to be ONE observable box. */
      :host([state='hero']) {
        position: absolute;
        inset: var(--workspace-topbar-height) 0 0 0;
        z-index: var(--z-overlay);
        display: flex;
        align-items: center;
        pointer-events: none;
      }

      /* The moving box of the morph is the composer, not the overlay around it. The name is set
         ONLY while the window is morphing: a view-transition-name must be unique in the document,
         and a permanently-named element would join any other transition the app runs. */
      :host([morphing]) .band {
        view-transition-name: sv3-composer;
      }
      :host([morphing]) .headline {
        view-transition-name: sv3-hero-headline;
      }

      /* 859 §B — the band is the ONE part of the composer that takes pointer events, in BOTH states
         now: everything around it (the host's own inset, and in docked the window's '.dock'
         wrapper) is click-through so the transcript underneath stays wheel-scrollable right up to
         the glass edge. Unconditional rather than hero-only, because docked is an overlay too. */
      .band {
        position: relative;
        width: 100%;
        max-inline-size: 48rem;
        margin-inline: auto;
        pointer-events: auto;
      }

      /* The hero INTRO — headline plus the corpus line under it (tempdoc 822 Phase F7, inventory
         E10). It sits directly above the composer box, which is the shipped landing's own placement
         ("this block renders at the bottom of the conversation column so the intro sits directly
         above the CSS-centered bar", views/UnifiedChatView.ts:3016-3018). The absolute positioning
         moved here off .headline so the two lines are ONE block above the band; .headline keeps its
         type and its view-transition name, so the morph is untouched. */
      .landing {
        position: absolute;
        inset-inline: 0;
        bottom: 100%;
        padding-bottom: var(--space-8);
      }

      .headline {
        margin: 0;
        color: var(--foreground);
        font-size: var(--font-size-sv3-display);
        font-weight: 400;
        letter-spacing: -0.025em;
        text-align: center;
        text-wrap: balance;
      }

      /* The corpus fact under the headline. Recedes to the secondary label because it is context for
         the question, not the question — and the REMEDY inside it is a real control, so it takes the
         foreground and an underline rather than becoming a coloured word that only looks clickable. */
      .corpus {
        margin: var(--space-2) 0 0;
        color: var(--secondary-label);
        font-size: var(--font-size-sv3-sm);
        text-align: center;
        text-wrap: balance;
      }
      /* Geometry only: the focus ring comes from jf-control itself (852 S4 adoption), which is why
         no :focus-visible rule remains here — two rings would be the drift the primitive exists
         against. */
      .corpus-remedy::part(control) {
        padding: 0;
        border: 0;
        background: none;
        color: var(--foreground);
        font-family: inherit;
        font-size: inherit;
        text-decoration: underline;
        text-underline-offset: 2px;
        cursor: pointer;
      }
      .corpus-remedy::part(control):hover {
        color: var(--primary);
      }

      /* The design spec stacks composer banners ABOVE the box, 8px clear of it, at the composer's
         own radius ('mx-auto mb-2 max-w-3xl', and the same
         rounded value the composer box carries, which is --radius-3xl here). Since 859 §B the
         composer floats rather than sitting in the flow, so a banner appearing here grows the
         FLOATING dock — which is measured, so the transcript's occluded band grows with it and the
         banner covers nothing.
         This window's one banner is the availability reason: the local model cannot answer, said
         where the send would have happened. It is TEXT, not a disabled control's tooltip — the
         availability authority's whole point is that the reason stays reachable ('state/availability.ts:18-20'). */
      .notice {
        margin-bottom: var(--space-2);
        padding: var(--space-2) var(--space-4);
        border: 1px solid var(--border);
        border-radius: var(--radius-3xl);
        background: var(--muted);
        color: var(--foreground);
        font-size: var(--font-size-sv3-xs);
        line-height: 1.5;
      }

      /* ── Reduced capability, at summary height (inventory E1/E3) ──────────
         The SAME slot and the same box as the .notice box above — one banner idiom, not two — so the
         degradation and the availability reason cannot read as two different kinds of chrome. What
         it adds is a ROW: a severity mark, the resting headline, the remedy, the disclosure. Its
         resting height is one line by construction (the detail is a sibling that only exists when
         opened), which is the founding constraint this window is built against. */
      .degradation {
        margin-bottom: var(--space-2);
        padding: var(--space-2) var(--space-4);
        border: 1px solid var(--border);
        border-radius: var(--radius-3xl);
        background: var(--muted);
        color: var(--foreground);
        font-size: var(--font-size-sv3-xs);
        line-height: 1.5;
      }
      .degradation-line {
        display: flex;
        gap: var(--space-2);
        align-items: center;
        margin: 0;
      }
      /* The severity mark is the only colour the banner spends, and it spends it on the tier the
         verdict actually reported — amber for an impairment, red for a failure. The colour is on the
         wrapper and the glyph inherits it through currentColor, so the svg can be aria-hidden
         without the tone moving off the element the tone rules select (830 audit A6). */
      .degradation-mark {
        display: inline-flex;
        flex: none;
        align-items: center;
        color: var(--warning);
      }
      .degradation[data-severity='error'] .degradation-mark {
        color: var(--destructive);
      }
      /* The honesty fact. It takes the row's slack and ellipsizes rather than wrapping, because a
         second line here is the height this banner exists not to spend.

         Ellipsis clips PIXELS, not text: the element's text content is always the whole sentence, so
         the accessible name is unaffected and assistive tech never sees a truncated fact. The
         SIGHTED route to the clipped tail is the element's title attribute (830 audit D2 — the
         earlier version of this comment claimed the disclosed detail carried the headline, which it
         does not, and claimed a named region that does not exist). Below roughly 640px the widest
         authority headline stops fitting; at 520px and under every one of them does. */
      .degradation-headline {
        flex: 1 1 auto;
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      /* The one-click fix, in the composer's own text-button idiom (.corpus-remedy) — a filled
         button in a one-line banner would out-weigh the send. */
      .degradation-remedy {
        flex: none;
      }
      .degradation-remedy::part(control) {
        padding: 0;
        border: 0;
        background: none;
        color: var(--foreground);
        font-family: inherit;
        font-size: inherit;
        text-decoration: underline;
        text-underline-offset: 2px;
        cursor: pointer;
      }
      .degradation-remedy::part(control):hover {
        color: var(--primary);
      }
      .degradation-disclosure {
        display: inline-flex;
        flex: none;
        align-items: center;
        justify-content: center;
        padding: 0;
        border: 0;
        background: none;
        color: var(--icon-muted);
        cursor: pointer;
      }
      .degradation-disclosure:hover {
        color: var(--foreground);
      }
      .degradation-disclosure:focus-visible {
        outline: 2px solid var(--ring);
        outline-offset: 2px;
        border-radius: var(--control-radius);
      }
      .degradation-detail {
        /* Aligned under the headline, past the mark's own column (the glyph plus the row's gap), so
           the elaboration reads as belonging to the line above it rather than as a second banner. */
        margin-top: var(--space-1);
        padding-left: var(--space-5);
        color: var(--secondary-label);
      }
      .degradation-body {
        margin: 0;
      }
      .degradation-causes {
        margin: 0;
        padding-left: var(--space-4);
      }

      /* ── The glass: ONE node carrying the whole recipe ────────────────────
         The design spec splits the fill onto a pseudo-element under a separate host, because its welded
         attachment tray needs the material on its own clip-pathed layer. This port excludes that
         tray, so the split has no remaining purpose — and a split silhouette is a trap: the radius
         lands on the element while the blur and fill land on a sibling layer, so the surface anyone
         inspects reports no glass at all. Radius, fill, blur and elevation stay together here; only
         the 1px ring is a pseudo-element, because a border would eat into the padding. */
      .glass {
        position: relative;
        /* Holds the ring's stacking context even in the no-blur fallback below, where the
           backdrop-filter that would otherwise establish one is gone. */
        isolation: isolate;
        border-radius: var(--radius-3xl);
        /* Tempdoc 859 §B (D2) — the fill's translucency is DERIVED from the same multiplier that
           drives the blur, so the two can never disagree. '--glass-blur-scale' is the shipped
           app's ONE blur knob ('styles/tokens.css'), reached two ways: '[data-surface-mode="solid"]'
           and 'prefers-reduced-transparency: reduce'. At scale 1 this resolves to '--glass-opacity'
           (80%, today's value, unchanged); at scale 0 it resolves to 100% — fully opaque, which is
           the half the '@supports' companion below exists to enforce: a translucent surface with
           nothing blurred behind it is unreadable, not subtle.

           The derivation must be LOCAL, and the reason is worth writing down because the obvious
           fix does not work: this sheet declares '--glass-opacity' on its own ':host'
           (sv3-tokens.css.ts), and an element's own declaration always beats an inherited value
           whatever the outer selector's specificity — so a ':root'-side override of it is
           structurally unreachable. '--glass-blur-scale' is the one multiplier sv3 does NOT
           re-declare, which is exactly why it can carry the escape in — and it is read WITHOUT a
           var() fallback on purpose (strip-token-fallbacks): it is a real design token declared in
           the shipped styles/tokens.css, so a hardcoded 1 here would be a dead second source of
           the default and exactly the drift that gate exists to remove. */
        background: color-mix(
          in srgb,
          var(--composer-glass-surface)
            calc(100% - (100% - var(--glass-opacity)) * var(--glass-blur-scale)),
          transparent
        );
        -webkit-backdrop-filter: blur(calc(var(--glass-blur) * var(--glass-blur-scale)))
          saturate(var(--glass-saturation));
        backdrop-filter: blur(calc(var(--glass-blur) * var(--glass-blur-scale)))
          saturate(var(--glass-saturation));
        box-shadow: var(--composer-shadow);
      }
      /* Mandatory companion to any glass surface: where blur is unsupported the fill goes opaque,
         because a translucent surface with nothing blurred behind it is unreadable, not subtle. */
      @supports not ((-webkit-backdrop-filter: blur(1px)) or (backdrop-filter: blur(1px))) {
        .glass {
          background: var(--composer-glass-surface);
        }
      }

      .glass::after {
        content: '';
        pointer-events: none;
        position: absolute;
        z-index: 1;
        inset: 0;
        border: 1px solid var(--composer-outline);
        border-radius: inherit;
        box-shadow: var(--composer-highlight);
      }

      /* Per the design spec: the field itself stays unstyled and every state is read off the wrapper, so
         focus and validity are one ring rather than two competing outlines. */
      :host(:has(textarea:focus-visible)) .glass::after {
        border-color: var(--ring);
        outline: 3px solid color-mix(in srgb, var(--ring) 24%, transparent);
      }
      :host(:has(textarea[aria-invalid='true'])) .glass::after {
        border-color: color-mix(in srgb, var(--destructive) 36%, transparent);
      }
      :host(:has(textarea[aria-invalid='true']:focus-visible)) .glass::after {
        outline-color: color-mix(in srgb, var(--destructive) 16%, transparent);
      }

      /* ── The field ───────────────────────────────────────────────────────── */
      .field {
        padding: var(--space-4) var(--space-4) var(--space-2);
      }
      /* The spec's compact row inset (px-3 py-2), split across our two rows: 8 above the field, 8
         below the controls, with a 4px seam where the spec has none because it has only one row. */
      :host([state='docked']) .field {
        padding: var(--space-2) var(--space-3) var(--space-1);
      }
      .editor {
        position: relative;
      }
      textarea {
        display: block;
        width: 100%;
        min-width: 0;
        margin: 0;
        padding: 0;
        border: 0;
        outline: none;
        resize: none;
        background: transparent;
        color: var(--foreground);
        font-family: inherit;
        font-size: var(--font-size-sv3-sm);
        line-height: 1.625;
        /* Grows with its content between the spec's floor and ceiling; past the ceiling the UA
           scrolls the field itself, which is the field's own overflow and not a window scroller. */
        field-sizing: content;
        min-block-size: var(--composer-field-min-hero);
        max-block-size: var(--composer-field-max);
      }
      /* The spec's compact composer is a SINGLE truncating line beside the send control, and its
         expanded form is the 70px editor — the two forms differ in INTERNAL layout, not just in
         position, which is the whole reason the morph crossfades rather than cutting. Only the FLOOR
         moves: field-sizing and the ceiling stay on the base rule, so a docked draft still grows. */
      :host([state='docked']) textarea {
        min-block-size: var(--composer-field-min-docked);
      }
      /* The placeholder is a real overlaid element rather than the input pseudo-element: that pseudo
         is an ambient facet this window may not re-author, and the spec overlays an element too. */
      .placeholder {
        pointer-events: none;
        position: absolute;
        inset: 0;
        color: var(--placeholder);
        font-size: var(--font-size-sv3-sm);
        line-height: 1.625;
      }

      /* ── The footer: scope controls left, primary action right ──────────────── */
      .footer {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: var(--space-2);
        min-width: 0;
        padding: 0 var(--space-4) var(--space-4);
      }
      :host([state='docked']) .footer {
        padding: 0 var(--space-3) var(--space-2);
      }
      /* The control row is the ANCHOR for the control's menu, which is why it is positioned: the
         menu opens upward from a composer that sits at the bottom of the window. Still true of the
         composer's POSITION since 859 §B, no longer of its place in the flow — it is anchored
         there by the floating dock rather than pushed there by the column. */
      .controls {
        position: relative;
        display: flex;
        align-items: center;
        gap: var(--space-1);
        min-width: 0;
      }

      /* The spec's composer control on the button sm ladder:
         h-7 / min-h-7, gap-1.5, px-2.5, the ghost variant's secondary label
         with the icon dimmed one step further, and transition-none on colour.
         THE CHIP-REFERENT QUESTION IS SETTLED HERE (tempdoc 822 §5, the polish pass's open item
         (a)): slice 3's placeholder chips were 24px off §3.2's menu-button ladder, which was the
         right referent while they were inert scope furniture. They are gone; what stands in the row
         now is a real composer control, so the row takes the spec's own composer referent —
         28px — and the two numbers stop competing. */
      button.composer-control {
        display: inline-flex;
        align-items: center;
        gap: var(--space-1-5);
        height: var(--space-7);
        min-height: var(--space-7);
        min-width: 0;
        /* Per the design spec: the inset is reduced by exactly the 1px border, so the VISUAL
           padding is the ladder's 10px rather than 11. */
        padding-inline: calc(var(--space-2-5) - 1px);
        border: 1px solid transparent;
        border-radius: var(--control-radius);
        background: transparent;
        color: var(--secondary-label);
        font-family: inherit;
        font-size: var(--font-size-sv3-sm);
        font-weight: 500;
        cursor: pointer;
        --control-icon-color: var(--icon-muted);
        /* Per the design spec: a button transitions its ELEVATION only, so a hover fill lands instantly
           while the depth change eases. */
        transition: box-shadow var(--duration-sv3-micro) var(--ease-sv3-enter);
      }
      button.composer-control:hover {
        background: var(--accent-surface);
        color: var(--foreground);
      }
      button.composer-control:focus-visible {
        outline: 2px solid var(--ring);
        outline-offset: 1px;
      }
      /* The model identity (Phase F11): the spec's model-picker width ladder (max-w-48) with
         its truncation + tooltip, but no trigger chrome — a FACT in a row of controls, one step down
         from the control's 14px/500 so the eye separates "thing I can change" from "thing I am told".
         Deliberately NOT given the docked evaporation the label above gets. */
      .model-label {
        min-width: 0;
        max-inline-size: 12rem;
        overflow: hidden;
        white-space: nowrap;
        text-overflow: ellipsis;
        color: var(--secondary-label);
        font-size: var(--font-size-sv3-xs);
        font-weight: 400;
        cursor: default;
      }

      /* A real glyph, not a placeholder swatch: the label evaporates on docking, so whatever is left
         has to carry the control's meaning on its own. Lucide strokes read currentColor. */
      .control-glyph {
        flex-shrink: 0;
        color: var(--control-icon-color);
      }
      /* The spec's composer control chevron: 14px, the muted icon token,
         and a −2px optical margin so it sits closer to the label than the gap would put it. */
      .control-chevron {
        flex-shrink: 0;
        margin-inline: -2px;
        color: var(--icon-muted);
      }

      /* ── The control's menu (popup + radio items) ────────────────────────
         Authored here rather than mounted from the shipped set: this window has no menu primitive
         and the spec's material is a THIRD glass recipe (denser than the composer's, lighter than
         the dialog's), which is exactly the kind of thing the token layer is for. */
      .menu {
        position: absolute;
        /* The spec's positioner: sideOffset = 4, align="start". The composer is anchored at the
           bottom of the window (by the floating dock since 859 §B, by the flow before it), so the
           side that has room is the top either way — which is where the spec's positioner flips to
           for the same reason. An open menu grows the dock, and the dock is measured, so the
           transcript's occluded band grows with it. */
        bottom: calc(100% + var(--space-1));
        inset-inline-start: 0;
        /* The composer glass ISOLATES, so this is intra-component stacking (the z-0/z-1/z-2
           rung) and any positive value clears the outline ring at 1 — but the number is still read
           off the window's z-scale rather than typed, so the ladder has one home. */
        z-index: var(--z-sticky);
        min-inline-size: 16rem;
        padding: var(--space-1);
        border: 1px solid var(--dropdown-border);
        border-radius: var(--radius-lg);
        /* 859 §B (D2) — '--dropdown-surface' derives its own translucency from the same multiplier
           in the token sheet, so this fill goes opaque with the blur. */
        background: var(--dropdown-surface);
        -webkit-backdrop-filter: blur(calc(var(--glass-blur) * var(--glass-blur-scale)));
        backdrop-filter: blur(calc(var(--glass-blur) * var(--glass-blur-scale)));
        box-shadow: var(--dropdown-shadow);
      }
      @supports not ((-webkit-backdrop-filter: blur(1px)) or (backdrop-filter: blur(1px))) {
        .menu {
          background: var(--popover);
        }
      }
      /* The spec's menu group label: px-2 py-1.5, medium, muted, 12px. */
      .menu-label {
        padding: var(--space-1-5) var(--space-2);
        color: var(--muted-foreground);
        font-size: var(--font-size-sv3-xs);
        font-weight: 500;
      }
      /* The spec's menu radio item: min-h-7, rounded-sm, px-2 py-1, 14px, the checked rung filled at
         foreground/8% and the highlighted one on the accent surface. */
      button.menu-item {
        display: block;
        width: 100%;
        min-height: var(--space-7);
        padding: var(--space-1) var(--space-2);
        border: 0;
        border-radius: var(--radius-sm);
        background: transparent;
        color: var(--foreground);
        font-family: inherit;
        font-size: var(--font-size-sv3-sm);
        text-align: start;
        cursor: pointer;
      }
      /* The design spec highlights a menu item with accent fill + accent foreground; the FILL is copied
         and the text stays on --foreground, because an accent-role token used as text is the
         growth the accent-as-text ratchet exists to stop (tempdoc 576 §6 rung-1). Same resolved
         ink in dark, one step darker in light — and one text colour across the window. */
      button.menu-item:hover {
        background: var(--accent-surface);
        color: var(--foreground);
      }
      button.menu-item:focus-visible {
        outline: 2px solid var(--ring);
        outline-offset: -2px;
      }
      button.menu-item[aria-checked='true'] {
        background: color-mix(in srgb, var(--foreground) 8%, transparent);
      }
      .menu-item-head {
        display: flex;
        align-items: center;
        gap: var(--space-1-5);
      }
      /* The spec's default badge: h-4, px-1.5, 10px semibold, muted fill and a
         border one step lighter than the surface's own. */
      .menu-badge {
        display: inline-flex;
        align-items: center;
        height: var(--space-4);
        padding-inline: var(--space-1-5);
        border: 1px solid color-mix(in srgb, var(--border) 70%, transparent);
        border-radius: var(--control-radius);
        /* The spec's badge adds a muted FILL under this text. Measured on THIS window's denser
           menu glass, that fill lifts the surface and drops the pair to 4.31:1 — under AA for 10px
           text — so the outline variant keeps its border and drops its fill, and the pair reads
           4.56:1 like every other muted line in the menu. Legibility is measured, never eyeballed
           (the import-bridge clause's own rule, applied to a component authored here). */
        background: transparent;
        color: var(--muted-foreground);
        font-size: var(--font-size-sv3-2xs);
        font-weight: 600;
        line-height: 1;
      }
      /* The spec's select-item description line — the runtime-mode menu, its own place for saying
         what a mode DOES. It is the honesty half of this control: each
         line names the parameters its rung sends and nothing more. */
      .menu-item-description {
        display: block;
        color: var(--muted-foreground);
        font-size: var(--font-size-sv3-xs);
        line-height: 1.334;
      }

      /* §5.9's compaction, in two elements: the outer carries the WIDTH (which collapses in one
         frame, so the footer reflows immediately) and the inner carries the MOTION. Docking is
         therefore an instant layout change that the morph's mid-transition crossfade covers — which
         is what that crossfade is for (§5.5) — while the reverse, and any state change made without
         a view transition, animates the label back in over the 180ms. */
      .control-label {
        display: block;
        min-inline-size: 0;
        max-inline-size: 240px;
      }
      :host([state='docked']) .control-label {
        max-inline-size: 0;
      }
      .control-label-motion {
        display: block;
        width: 100%;
        min-width: 0;
        max-width: 240px;
        overflow: hidden;
        white-space: nowrap;
        text-overflow: ellipsis;
        text-align: left;
        transform-origin: left;
        transition:
          opacity var(--duration-sv3-morph) var(--ease-sv3-morph),
          transform var(--duration-sv3-morph) var(--ease-sv3-morph);
      }
      :host([state='docked']) .control-label-motion {
        transform: translateX(-0.25rem) scaleX(0.95);
        opacity: 0;
      }

      /* ── The primary-action SLOT ──────────────────────────────────────────
         The slot holds exactly ONE control, chosen by a strict-priority state machine (Phase F2):
         Answer ▸ Stop ▸ Follow-up ▸ Send. That is the design spec's own construction — an early
         'return' renders the stop button INSTEAD of the send control
         — and it is what makes double-firing structurally impossible rather than merely guarded: an
         unrendered Send cannot be clicked. Every occupant is the same box with the same physics, so
         the geometry is declared once for all of them; only the material differs below. */
      button.stop,
      button.send {
        position: relative;
        isolation: isolate;
        flex-shrink: 0;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        inline-size: var(--space-8);
        block-size: var(--space-8);
        padding: 0;
        overflow: hidden;
        border: 0;
        border-radius: 9999px;
        background: var(--message-action);
        color: var(--message-action-foreground);
        font-size: var(--font-size-sv3-sm);
        box-shadow:
          var(--control-inset-highlight),
          0 1px 2px 0 color-mix(in srgb, var(--message-action) 24%, transparent);
        cursor: pointer;
        transition: all var(--duration-sv3-micro) var(--ease-sv3-enter);
      }
      /* The spec's 'bg-destructive/90' at rest, full strength on hover.
         Halting a response is destructive-tier by the spec's own colour budget: it is act-now. */
      button.stop {
        background: color-mix(in srgb, var(--destructive) 90%, transparent);
        color: var(--color-white);
        box-shadow:
          var(--control-inset-highlight),
          0 1px 2px 0 color-mix(in srgb, var(--destructive) 24%, transparent);
      }
      /* The ACT-NOW rung. The 3-colour budget spends --success on "you are the blocker", which is
         exactly what a held decision is; the control is a jump to the decision, never the decision. */
      button.send.answer {
        background: var(--success);
        color: var(--color-white);
        box-shadow:
          var(--control-inset-highlight),
          0 1px 2px 0 color-mix(in srgb, var(--success) 24%, transparent);
      }
      button.stop:hover:not(:disabled),
      button.send:hover:not(:disabled) {
        background: var(--message-action-hover);
        transform: scale(1.05);
      }
      /* Declared after the shared hover rule, or the slot's send material would win on a Stop. */
      button.stop:hover:not(:disabled) {
        background: var(--destructive);
      }
      button.send.answer:hover:not(:disabled) {
        background: var(--success);
      }
      /* Pressing does three things at once: the highlight flips from top-light to top-dark and the
         drop shadow goes, so the control reads as pressed INTO the surface rather than merely dimmed. */
      button.stop:active:not(:disabled),
      button.send:active:not(:disabled) {
        box-shadow: var(--control-inset-pressed);
      }
      button.stop:focus-visible,
      button.send:focus-visible {
        outline: 2px solid var(--ring);
        outline-offset: 1px;
      }
      button.send:disabled {
        pointer-events: none;
        opacity: 0.3;
        box-shadow: none;
        transform: none;
      }

      @media (prefers-reduced-motion: reduce) {
        /* The fade survives, the transform does not — the spec keeps whichever half still carries
           the meaning. */
        .control-label-motion {
          transition: opacity var(--duration-sv3-morph) var(--ease-sv3-morph);
        }
        :host([state='docked']) .control-label-motion {
          transform: none;
        }
        button.composer-control,
        button.stop,
        button.send {
          transition: none;
        }
        button.stop:hover:not(:disabled),
        button.send:hover:not(:disabled) {
          transform: none;
        }
      }
    `,
  ];

  static properties = {
    state: { type: String, reflect: true },
    slotKind: { type: String, reflect: true, attribute: 'slot-kind' },
    slotReason: { type: String, attribute: 'slot-reason' },
    steerable: { type: Boolean, reflect: true },
    unavailableReason: { type: String, attribute: 'unavailable-reason' },
    delegateUnavailableReason: { type: String, attribute: 'delegate-unavailable-reason' },
    degradation: { attribute: false },
    detailed: { type: Boolean, reflect: true },
    corpus: { attribute: false },
    effort: { type: String, reflect: true },
    tier: { type: String, reflect: true },
    modelLabel: { type: String, reflect: true, attribute: 'model-label' },
    draft: { state: true },
    effortMenuOpen: { state: true },
    tierMenuOpen: { state: true },
    degradationOpen: { state: true },
  };

  declare state: Sv3ComposerState;
  /**
   * Which control occupies the primary slot, decided by the WINDOW's `sv3PrimaryAction` state machine
   * (`sv3-run.ts`) — the composer renders the verdict and never re-derives it, so the slot's priority
   * order lives in exactly one place. The attribute is `slot-kind`, not `slot`: `slot` is a reserved
   * global attribute and would try to assign this element to a light-DOM slot.
   */
  declare slotKind: Sv3SlotKind;
  /** The reason from the same derivation, carried into the control's aria-label and title. */
  declare slotReason: string;
  /**
   * The live run accepts a mid-run submit as a STEER that joins it (an agent run does; an ask stream
   * has no such channel). It is what decides whether a submit while the slot is Stop is a refusal or
   * a directive — the window then routes it, because only the window may reach the run.
   */
  declare steerable: boolean;
  /**
   * Why the ask tier cannot be used right now, from the app's availability authority — empty means
   * available. The composer refuses its OWN send on it rather than dispatching a send the window
   * would have to un-do, which is what keeps the draft safe: a refused send never leaves here.
   */
  declare unavailableReason: string;
  /**
   * The same authority's answer for the DELEGATE tier, which is gated separately because it is gated
   * differently: an agent task needs a live model, but not an indexed document to ground an answer
   * in. The visible notice stays the ASK tier's — Enter is the composer's default and its refusal is
   * the one that must never be silent — so a window that can delegate but cannot ask says exactly
   * that, and Ctrl+Enter still works while the notice explains why Enter does not.
   */
  declare delegateUnavailableReason: string;
  /**
   * Reduced capability, from the shared readiness authority (inventory E1) — already projected by
   * `sv3-degradation.ts`, so the composer neither reads the verdict nor words a cause. `null` is the
   * ordinary state and costs no chrome.
   *
   * It shares the ONE banner slot with {@link unavailableReason}, and the two cannot state the same
   * fact twice: `sv3ComposerReason` drops the affordance-scoped reason when this banner already
   * words its code.
   */
  declare degradation: Sv3Degradation | null;
  /**
   * The app-wide Simple/Detailed authority's answer (inventory E3), handed down by the window so the
   * composer holds no second copy of the preference. Detailed opens the banner's causes; Simple
   * keeps the resting line and offers them behind the disclosure.
   */
  declare detailed: boolean;
  /**
   * What the next question can be answered from (tempdoc 822 Phase F7; inventory E10). Handed down
   * already DERIVED by the window's one projection (`sv3-honesty.ts`), so the composer decides
   * nothing about the corpus — including whether "not reported" counts as zero, which is exactly the
   * decision that must not be made twice.
   */
  declare corpus: Sv3Corpus;
  /**
   * The effort rung the next send will carry, HELD BY THE WINDOW and handed down (tempdoc 822 Phase
   * F10). The composer renders it and announces a change; it does not keep the choice, for the same
   * reason it does not keep the session: the thing that dispatches is the thing that must know.
   */
  declare effort: Sv3Effort;
  /**
   * Which tier the next send routes to (852 S4), HELD BY THE WINDOW exactly as the effort rung is.
   *
   * It changes what a plain Enter and the send control do; the Ctrl/⌘+Enter accelerator is UNCHANGED
   * and still delegates from either mode, which is why the keyboard path in {@link onKeydown} reads
   * the modifier first and this property second.
   */
  declare tier: Sv3ComposerTier;
  /**
   * Which model would answer the next question, VERBATIM from the runtime authority (tempdoc 822
   * Phase F11; the same expression `SearchV3View` stamps on a turn). The window authors no model
   * name: no shortening, no re-casing, no vendor-stripping.
   *
   * IDENTITY ONLY, NEVER STATE: empty means the label is ABSENT — no "no model", no "offline", no em
   * dash. The availability notice above the box is the ONE place a state is said, in the one
   * readiness vocabulary's own wording, and a second sense of "offline" in the same box is exactly
   * the duplicate the F-series audit measured zero of.
   */
  declare modelLabel: string;
  declare draft: string;
  /**
   * Public because the window's Escape ladder has to see it: an open menu is the MOST LOCAL
   * transient in the composer, so the window declines the key the way it declines to an open rename
   * (`SearchV3View.onHostKeydown`).
   */
  declare effortMenuOpen: boolean;
  /**
   * The mode menu's own open flag. Public for the same reason {@link effortMenuOpen} is: the window's
   * Escape ladder yields to whichever control menu is open. The two are mutually exclusive by
   * construction (opening one closes the other), so the row never holds two open menus.
   */
  declare tierMenuOpen: boolean;
  /**
   * The reader's own disclosure of the banner's detail, in Simple mode. Window-local and forgotten
   * on unmount: nothing is remembered per cause-set, so a banner never opens itself because an
   * earlier one was opened. Detailed mode does not read this at all — it opens the causes outright.
   */
  declare degradationOpen: boolean;

  constructor() {
    super();
    this.state = COMPOSER_STATE_DEFAULT;
    this.slotKind = 'send';
    this.slotReason = sv3PrimaryAction({
      pendingPrompt: false,
      running: false,
      followUp: false,
    }).reason;
    this.steerable = false;
    this.unavailableReason = '';
    this.delegateUnavailableReason = '';
    this.degradation = null;
    this.detailed = false;
    this.corpus = SV3_CORPUS_UNKNOWN;
    this.effort = SV3_EFFORT_DEFAULT;
    this.tier = SV3_TIER_DEFAULT;
    this.modelLabel = '';
    this.draft = '';
    this.effortMenuOpen = false;
    this.tierMenuOpen = false;
    this.degradationOpen = false;
  }

  private request(next: Sv3ComposerState): void {
    this.dispatchEvent(
      new CustomEvent<Sv3ComposerStateRequest>(SV3_COMPOSER_STATE_REQUEST, {
        detail: { state: next },
        bubbles: true,
        composed: true,
      }),
    );
  }

  private onInput(event: Event): void {
    this.draft = (event.target as HTMLTextAreaElement).value;
  }

  private onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      this.request('hero');
      return;
    }
    if (event.key !== 'Enter' || event.isComposing) return;
    // Shift+Enter is the newline the multi-line field would otherwise have no way to take, and it
    // wins over the modifier tiers: a reader adding a line never means to dispatch anything. An IME
    // composing a character owns the key first (`isComposing`), or a Japanese or Chinese draft is
    // sent halfway through being typed.
    if (event.shiftKey) return;
    event.preventDefault();
    // Ctrl+Enter (⌘↩ on macOS) DELEGATES, from either mode — the accelerator is UNCHANGED by S4's
    // mode control, which is why the modifier is read first and the chosen tier second. Plain Enter
    // sends at the chosen tier, which is `ask` in a window whose reader has not touched the control.
    // Alt is left alone — an Alt+Enter this window claimed would swallow a chord the platform or the
    // shell may already own.
    this.submit(event.ctrlKey || event.metaKey ? 'delegate' : this.tier);
  }

  /**
   * Empties the draft. The composer still OWNS it — this is the window asking for a documented state
   * change (starting a new session returns the window to its empty form, and a leftover draft would
   * be the previous session's text sitting in a fresh one), not the window reaching in to write it.
   */
  clearDraft(): void {
    this.draft = '';
  }

  /**
   * The ONE origin of a send, whichever affordance asked. An empty draft is not a send.
   *
   * Three refusals, each returning WITHOUT TOUCHING THE DRAFT. The reason is already on screen (the
   * banner, or the slot's own control), so a refusal is legible rather than silent, and the text the
   * reader typed survives. Clearing a draft the window never accepted would be destroying work.
   *
   *  - the model is unreachable;
   *  - a typed prompt is holding the run (`answer` rung) — THE structural half of that pattern:
   *    a held approval or question is resolved by its own dedicated command, so there must be no path
   *    by which typing a sentence into the composer could resolve it. This is a refusal and not a
   *    disabled field, because the reader may legitimately be drafting the message they will send
   *    once the decision is made;
   *  - a response is streaming and the run takes no steer (the ask tier has no interject channel).
   *    A STEERABLE run does not refuse: the submit leaves as a directive that joins the live turn,
   *    which is the window's call to make, not this element's.
   */
  private submit(tier: Sv3ComposerTier): void {
    const query = this.draft.trim();
    if (query.length === 0) return;
    if (this.slotKind === 'answer') return;
    if (this.slotKind === 'stop' && !this.steerable) return;
    // A STEER is not a new commitment against the tier's gate — it joins a run that is already
    // running, so it is refused only by the slot rule above.
    if (this.slotKind !== 'stop') {
      const reason = tier === 'delegate' ? this.delegateUnavailableReason : this.unavailableReason;
      if (reason !== '') return;
    }
    this.dispatchEvent(
      new CustomEvent<Sv3ComposerSubmit>(SV3_COMPOSER_SUBMIT, {
        detail: { query, tier },
        bubbles: true,
        composed: true,
      }),
    );
  }

  /** Halt the streaming response. The window owns the stream; this only says the reader asked. */
  private stop(): void {
    this.dispatchEvent(new CustomEvent(SV3_COMPOSER_STOP, { bubbles: true, composed: true }));
  }

  /** Ask the window to take the reader to the decision holding the run. Resolves nothing itself. */
  private answer(): void {
    this.dispatchEvent(new CustomEvent(SV3_COMPOSER_ANSWER, { bubbles: true, composed: true }));
  }

  render(): TemplateResult {
    const empty = this.draft.trim().length === 0;
    // WHICHEVER TIER THE READER CHOSE is the one whose refusal must be on screen (852 S4). Before the
    // mode control, the notice was deliberately the ASK tier's — Enter's refusal was the one that
    // could never be silent. Now Enter routes wherever the control points, so a delegate-mode window
    // showing the ask tier's availability would refuse the send for a reason nothing on screen states.
    const activeReason = this.activeUnavailableReason;
    // Soft, never `disabled`: the availability authority's contract is that the reason stays
    // reachable, and a natively-disabled control is not even focusable (`state/availability.ts:6-20`).
    const unavailable = activeReason !== '';
    // The ONE banner slot, shared. The affordance-scoped reason yields whenever the degradation
    // banner already words its code, so the same fact never stands in the slot twice.
    const reason = sv3ComposerReason(this.degradation, activeReason);
    return html`
      <div class="band" data-testid="sv3-composer-band">
        ${this.state === 'hero' ? this.landing() : nothing}
        ${this.degradationBanner()}
        ${reason === ''
          ? nothing
          : html`<p class="notice" id="sv3-composer-notice" role="status" data-testid="sv3-composer-notice">
              ${reason}
            </p>`}
        <div class="glass" data-testid="sv3-composer-shell">
          <div class="field">
            <div class="editor">
              <textarea
                rows="1"
                .value=${this.draft}
                aria-label=${COMPOSER_PLACEHOLDER}
                data-testid="sv3-composer-input"
                @input=${this.onInput}
                @keydown=${this.onKeydown}
              ></textarea>
              ${empty
                ? html`<span
                    class="placeholder"
                    aria-hidden="true"
                    data-testid="sv3-composer-placeholder"
                    >${COMPOSER_PLACEHOLDER}</span
                  >`
                : nothing}
            </div>
          </div>
          <div class="footer">
            <div class="controls" @focusout=${this.onControlsFocusOut}>
              ${this.tierControl()}${this.effortControl()}${this.modelLabelFact()}
            </div>
            ${this.primaryAction(empty, unavailable, this.refusalDescribedBy(reason))}
          </div>
        </div>
      </div>
    `;
  }

  /**
   * The control row's SHARED trigger (tempdoc 822 Phase F10's grammar, generalised by 852 S4 when a
   * second control joined the row) — the design spec's traits picker re-expressed on this window's
   * tokens: a ghost composer control whose LABEL IS THE CURRENT VALUE, a chevron, and a menu of radio
   * rungs behind it.
   *
   * The accessible name carries BOTH halves ("Effort: Standard", "Mode: Ask"), because the visible
   * label is only the value: docking evaporates it into the glyph (§5.9), and a control whose
   * remaining glyph means "effort" to nobody would have gone quiet exactly when it got smaller.
   *
   * ONE renderer, because the two controls are one grammar and a second copy of it is how a row of
   * controls starts drifting. Only the MENUS are still written twice, and only because their option
   * rows carry different data-attribute NAMES (`data-effort` is read by the ui-shot harness,
   * `scripts/jseval/jseval/ui_check.py:1539`) — a lit template cannot parametrise an attribute name.
   */
  private controlTrigger(spec: {
    readonly testid: string;
    readonly menuLabel: string;
    readonly glyph: IconName;
    readonly value: string;
    readonly open: boolean;
    readonly toggle: () => void;
  }): TemplateResult {
    const name = `${spec.menuLabel}: ${spec.value}`;
    return html`
      <button
        type="button"
        class="composer-control"
        data-testid=${spec.testid}
        aria-haspopup="menu"
        aria-expanded=${spec.open ? 'true' : 'false'}
        aria-label=${name}
        title=${name}
        @click=${spec.toggle}
        @keydown=${(event: KeyboardEvent) => this.onTriggerKeydown(event, spec.open, spec.toggle)}
      >
        ${icon({ name: spec.glyph, size: CONTROL_GLYPH_SIZE, className: 'control-glyph' })}
        <span class="control-label"><span class="control-label-motion">${spec.value}</span></span>
        ${icon({ name: 'chevron-down', size: CHEVRON_SIZE, className: 'control-chevron' })}
      </button>
    `;
  }

  private effortControl(): TemplateResult {
    return html`
      ${this.controlTrigger({
        testid: 'sv3-composer-effort',
        menuLabel: SV3_EFFORT_MENU_LABEL,
        glyph: EFFORT_GLYPH,
        value: sv3EffortLabel(this.effort),
        open: this.effortMenuOpen,
        toggle: this.toggleEffortMenu,
      })}${this.effortMenuOpen ? this.effortMenu() : nothing}
    `;
  }

  /**
   * WHERE THE DRAFT GOES (852 S4, ledger row 12) — the window's two dispatch tiers, made visible.
   *
   * Both were live and tested long before this control existed; `delegate` was reachable only by
   * Ctrl/⌘+Enter, announced nowhere but in the send control's aria-label. A capability a reader can
   * only find by pressing a chord nobody told them about is not an affordance, and this window's own
   * honesty law does not have a category for "present but undiscoverable".
   *
   * It sits in the `.controls` wrapper beside the effort rung and NOT in the primary-action slot: that
   * slot early-returns exactly one control and never disables the loser behind the winner. It is a
   * real control, so it takes the full button treatment — unlike the model FACT beside it, which is
   * deliberately not focusable and carries no role.
   */
  private tierControl(): TemplateResult {
    return html`
      ${this.controlTrigger({
        testid: 'sv3-composer-tier',
        menuLabel: SV3_TIER_MENU_LABEL,
        glyph: TIER_GLYPH,
        value: sv3TierLabel(this.tier),
        open: this.tierMenuOpen,
        toggle: this.toggleTierMenu,
      })}${this.tierMenuOpen ? this.tierMenu() : nothing}
    `;
  }

  /**
   * WHICH MODEL WOULD ANSWER (tempdoc 822 Phase F11) — the design spec's model-picker slot in the
   * control row, degenerated to a static label because this window has one local model and no
   * provider concept: a picker with nothing to pick would be chrome that lies about what it can do.
   *
   * A FACT IN A ROW OF CONTROLS, so it must not look clickable — one step down from the control's
   * 14px/500, not focusable, not a button, no invented role. And it does NOT adopt the effort
   * control's evaporate-on-dock treatment (`:host([state='docked']) .control-label`): docked is the
   * transcript-reading state, which is exactly when "which model wrote this" is being asked.
   */
  private modelLabelFact(): TemplateResult | typeof nothing {
    if (this.modelLabel === '') return nothing;
    return html`<span class="model-label" data-testid="sv3-composer-model" title=${this.modelLabel}
      >${this.modelLabel}</span
    >`;
  }

  private effortMenu(): TemplateResult {
    return html`
      <div
        class="menu"
        role="menu"
        aria-label=${SV3_EFFORT_MENU_LABEL}
        data-testid="sv3-composer-effort-menu"
        @keydown=${this.onMenuKeydown}
      >
        <div class="menu-label">${SV3_EFFORT_MENU_LABEL}</div>
        ${SV3_EFFORT_OPTIONS.map(
          (option) => html`
            <button
              type="button"
              class="menu-item"
              role="menuitemradio"
              data-testid="sv3-composer-effort-option"
              data-effort=${option.id}
              aria-checked=${option.id === this.effort ? 'true' : 'false'}
              @click=${() => this.chooseEffort(option.id)}
            >
              <span class="menu-item-head">
                ${option.label}
                ${option.isDefault
                  ? html`<span class="menu-badge" data-testid="sv3-composer-effort-default"
                      >Default</span
                    >`
                  : nothing}
              </span>
              <span class="menu-item-description">${option.description}</span>
            </button>
          `,
        )}
      </div>
    `;
  }

  /**
   * The mode menu. Written out rather than shared with {@link effortMenu} for the one reason stated
   * at {@link controlTrigger}: the option rows differ in an attribute NAME.
   */
  private tierMenu(): TemplateResult {
    return html`
      <div
        class="menu"
        role="menu"
        aria-label=${SV3_TIER_MENU_LABEL}
        data-testid="sv3-composer-tier-menu"
        @keydown=${this.onMenuKeydown}
      >
        <div class="menu-label">${SV3_TIER_MENU_LABEL}</div>
        ${SV3_TIER_OPTIONS.map(
          (option) => html`
            <button
              type="button"
              class="menu-item"
              role="menuitemradio"
              data-testid="sv3-composer-tier-option"
              data-tier=${option.id}
              aria-checked=${option.id === this.tier ? 'true' : 'false'}
              @click=${() => this.chooseTier(option.id)}
            >
              <span class="menu-item-head">
                ${option.label}
                ${option.isDefault
                  ? html`<span class="menu-badge" data-testid="sv3-composer-tier-default"
                      >Default</span
                    >`
                  : nothing}
              </span>
              <span class="menu-item-description">${option.description}</span>
            </button>
          `,
        )}
      </div>
    `;
  }

  /**
   * Bound fields, not methods: the trigger's keydown handler receives its own toggle as a value, and
   * an unbound method passed that way would lose the element when called.
   *
   * The two menus are MUTUALLY EXCLUSIVE. Opening one closes the other rather than layering it: they
   * open into the same space above the same row, and the keyboard walk below addresses "the open
   * menu" — a second one would make that phrase ambiguous.
   */
  private readonly toggleEffortMenu = (): void => {
    this.effortMenuOpen = !this.effortMenuOpen;
    if (this.effortMenuOpen) this.tierMenuOpen = false;
  };

  private readonly toggleTierMenu = (): void => {
    this.tierMenuOpen = !this.tierMenuOpen;
    if (this.tierMenuOpen) this.effortMenuOpen = false;
  };

  /**
   * The rung is the WINDOW's to keep — the composer announces it exactly as it announces a draft,
   * and re-renders from the property the window writes back. A rung that is already current is
   * still a close, never a second announcement.
   */
  private chooseEffort(effort: Sv3Effort): void {
    this.effortMenuOpen = false;
    this.focusTrigger('sv3-composer-effort');
    if (effort === this.effort) return;
    this.dispatchEvent(
      new CustomEvent<Sv3EffortChange>(SV3_EFFORT_CHANGE, {
        detail: { effort },
        bubbles: true,
        composed: true,
      }),
    );
  }

  /** The tier, on exactly the effort rung's terms — the composer announces it and keeps nothing. */
  private chooseTier(tier: Sv3ComposerTier): void {
    this.tierMenuOpen = false;
    this.focusTrigger('sv3-composer-tier');
    if (tier === this.tier) return;
    this.dispatchEvent(
      new CustomEvent<Sv3TierChange>(SV3_TIER_CHANGE, {
        detail: { tier },
        bubbles: true,
        composed: true,
      }),
    );
  }

  /** Down-arrow on the trigger opens the menu onto its first rung (the spec's menu behaviour). */
  private onTriggerKeydown(event: KeyboardEvent, open: boolean, toggle: () => void): void {
    if (event.key !== 'ArrowDown' || open) return;
    event.preventDefault();
    toggle();
    void this.updateComplete.then(() => this.menuItems()[0]?.focus());
  }

  /**
   * The menu's own keys. Escape is FIRST and is stopped here, which is what keeps the window's
   * Escape ladder true: `SearchV3View` yields to an open menu the way it yields to a rename, and
   * the focus returns to the control that opened it rather than to wherever it was before.
   */
  private onMenuKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      // Read WHICH menu is open before closing it — the focus goes back to the control that opened
      // this menu, not to whichever trigger happens to be first in the row.
      const trigger = this.tierMenuOpen ? 'sv3-composer-tier' : 'sv3-composer-effort';
      this.effortMenuOpen = false;
      this.tierMenuOpen = false;
      this.focusTrigger(trigger);
      return;
    }
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
    const items = this.menuItems();
    const active = items.findIndex((item) => item === this.shadowRoot?.activeElement);
    if (items.length === 0) return;
    event.preventDefault();
    const step = event.key === 'ArrowDown' ? 1 : -1;
    const next = (active + step + items.length) % items.length;
    items[next]?.focus();
  }

  /**
   * Focus leaving the control row closes the menu (F9's rule for the palette, applied to the second
   * transient this window owns): a menu left open behind a moved caret is reachable by pointer only.
   */
  private onControlsFocusOut(event: FocusEvent): void {
    if (!this.effortMenuOpen && !this.tierMenuOpen) return;
    const next = event.relatedTarget as Node | null;
    const row = this.shadowRoot?.querySelector('.controls') ?? null;
    if (next !== null && row?.contains(next) === true) return;
    this.effortMenuOpen = false;
    this.tierMenuOpen = false;
  }

  /** The rungs of THE open menu — the two are mutually exclusive, so at most one menu is rendered. */
  private menuItems(): HTMLButtonElement[] {
    return [
      ...(this.shadowRoot?.querySelectorAll<HTMLButtonElement>('button.menu-item') ?? []),
    ];
  }

  private focusTrigger(testid: string): void {
    this.shadowRoot
      ?.querySelector<HTMLButtonElement>(`button.composer-control[data-testid="${testid}"]`)
      ?.focus();
  }

  /**
   * The hero intro: the headline, and what the next question can actually be answered from
   * (tempdoc 822 Phase F7; inventory E10 / tempdoc 811 C-4).
   *
   * THREE outcomes, and the third is silence. A corpus of zero offers the REMEDY instead of letting
   * the window imply it will search something; a known corpus states its size; and an `unknown` one
   * says nothing at all, because a window that has not been told the count must not fill the gap with
   * either claim. The remedy is a real navigation the window performs — the composer announces it,
   * exactly as it announces a send.
   */
  private landing(): TemplateResult {
    return html`
      <div class="landing">
        <h1 class="headline" data-testid="sv3-composer-headline">${HERO_HEADLINE}</h1>
        ${this.corpusLine()}
      </div>
    `;
  }

  private corpusLine(): TemplateResult | typeof nothing {
    if (this.corpus.kind === 'unknown') return nothing;
    if (this.corpus.kind === 'documents') {
      return html`<p class="corpus" data-testid="sv3-composer-corpus" data-kind="documents">
        Searching ${this.corpus.count.toLocaleString()}
        ${this.corpus.count === 1 ? 'file' : 'files'}
      </p>`;
    }
    return html`<p class="corpus" data-testid="sv3-composer-corpus" data-kind="empty">
      <jf-control
        class="corpus-remedy"
        data-testid="sv3-composer-corpus-remedy"
        label=${CORPUS_ADD_FOLDERS}
        .onActivate=${() => this.remedy()}
        >${CORPUS_ADD_FOLDERS}</jf-control
      >
    </p>`;
  }

  private remedy(): void {
    this.dispatchEvent(
      new CustomEvent<Sv3RemedyDetail>(SV3_REMEDY, {
        detail: { target: CORPUS_REMEDY_TARGET },
        bubbles: true,
        composed: true,
      }),
    );
  }

  /**
   * The availability of the tier the reader has CHOSEN (852 S4). The two tiers are gated separately
   * and deliberately — an agent task needs a live model but no indexed document, so the ask tier can
   * be refused while delegate is fine — and the window hands down both, so the composer asks the one
   * that is about to run rather than the one that used to be the only answer.
   */
  private get activeUnavailableReason(): string {
    return this.tier === 'delegate' ? this.delegateUnavailableReason : this.unavailableReason;
  }

  /**
   * Which node in the ONE banner slot explains a refusal (inventory E1).
   *
   * The send's `aria-describedby` used to name the availability notice unconditionally, which would
   * now point at a node that yielded its line to the banner. It names whichever node is actually in
   * the slot, and nothing when the composer is not refusing — a dangling reference is read out as
   * silence, which is the one outcome the reachable-reason contract rules out.
   */
  private refusalDescribedBy(reason: string): string | null {
    if (this.activeUnavailableReason === '') return null;
    if (reason !== '') return 'sv3-composer-notice';
    return this.degradation === null ? null : SV3_DEGRADATION_HEADLINE_ID;
  }

  /**
   * REDUCED CAPABILITY, at summary height (inventory E1/E3).
   *
   * One line rests: the severity mark, the headline the readiness authority worded, and the one-click
   * remedy. Everything else — the consequence sentence and the worded causes — is DISCLOSURE, opened
   * by the reader or, for a reader who has told the app they want detail, by Detailed mode.
   *
   * That split is the whole point. The shipped window's banner is a block: headline, body, a bulleted
   * cause list and a remedy, permanently in flow. Here the honesty fact ("capability is reduced, and
   * here is the fix") rests and the elaboration extends — the same L14 boundary the answer frame
   * draws, and the reason this window's degradation costs one line instead of an eighth of the
   * window. Nothing is behind HOVER: the disclosure is a real button with a real expanded state.
   *
   * NO LIVE REGION HERE (830 audit D1). The row was `role="status"`, which got the announcement
   * exactly backwards in both directions: the region is created *together with* its content (one
   * childList mutation adds the whole subtree), so the appearance does not reliably announce — while
   * the two BUTTONS inside it made every disclosure toggle fire six attribute mutations in an
   * `atomic` polite region, re-announcing "Semantic search degraded. Open Health Hide what is
   * reduced" on each open and close. The state change is already announced, correctly, by the
   * shell's always-mounted verdict announcer ("All systems operational" → "Service degraded"), so
   * this banner is a rendering of that fact and not a second announcer of it.
   */
  private degradationBanner(): TemplateResult | typeof nothing {
    const degradation = this.degradation;
    if (degradation === null) return nothing;
    const open = this.detailed || this.degradationOpen;
    const hasDetail = degradation.body !== '' || degradation.causes.length > 0;
    return html`<div
      class="degradation"
      data-testid="sv3-degradation"
      data-severity=${degradation.severity}
      data-open=${String(open)}
    >
      <p class="degradation-line" data-testid="sv3-degradation-line">
        ${/* Decorative (830 audit A6): the severity is carried by the headline's words and by the
              tone, so the glyph adds nothing to the accessible name and is hidden from AT. */ ''}
        <span class="degradation-mark" aria-hidden="true"
          >${icon({
            name: degradation.severity === 'error' ? 'alert-circle' : 'alert-triangle',
            size: SV3_DEGRADATION_GLYPH_SIZE,
          })}</span
        >
        ${/* 830 audit D2 — the pointer recovery for a CLIPPED headline. The element's text is always
              the whole sentence (CSS ellipsis clips pixels, not the accessible name), so AT is
              unaffected either way; below ~640px the widest authority headline no longer fits the
              row and a sighted reader needs a route to the rest. Same idiom, and the same accepted
              residual, as the answer frame's `title` (`Sv3Main.tailFacts`): a sighted keyboard-only
              reader sees the clipped line. */ ''}
        <span
          class="degradation-headline"
          id=${SV3_DEGRADATION_HEADLINE_ID}
          title=${degradation.headline}
          data-testid="sv3-degradation-headline"
          >${degradation.headline}</span
        >
        <jf-control
          class="degradation-remedy"
          data-testid="sv3-degradation-remedy"
          label=${degradation.remedy.label}
          .onActivate=${() => this.takeDegradationRemedy()}
          >${degradation.remedy.label}</jf-control
        >
        ${hasDetail ? this.degradationDisclosure(open) : nothing}
      </p>
      ${open && hasDetail ? this.degradationDetail(degradation) : nothing}
    </div>`;
  }

  /**
   * The disclosure. Hidden in Detailed mode, which is not a hidden control but an ABSENT one: there
   * is nothing left to disclose, and a toggle whose only state is "already open" is the dead control
   * the shipped window learned to drop.
   */
  private degradationDisclosure(open: boolean): TemplateResult | typeof nothing {
    if (this.detailed) return nothing;
    return html`<button
      type="button"
      class="degradation-disclosure"
      data-testid="sv3-degradation-disclosure"
      aria-expanded=${open ? 'true' : 'false'}
      aria-controls=${open ? SV3_DEGRADATION_DETAIL_ID : nothing}
      aria-label=${open ? SV3_DEGRADATION_LESS : SV3_DEGRADATION_MORE}
      title=${open ? SV3_DEGRADATION_LESS : SV3_DEGRADATION_MORE}
      @click=${this.toggleDegradation}
    >
      ${icon({
        name: open ? 'chevron-down' : 'chevron-right',
        size: SV3_DEGRADATION_GLYPH_SIZE,
      })}
    </button>`;
  }

  /** The elaboration: the consequence sentence, then the causes — one line each, deduped by code. */
  private degradationDetail(degradation: Sv3Degradation): TemplateResult {
    return html`<div
      class="degradation-detail"
      id=${SV3_DEGRADATION_DETAIL_ID}
      data-testid="sv3-degradation-detail"
    >
      ${degradation.body === ''
        ? nothing
        : html`<p class="degradation-body" data-testid="sv3-degradation-body">
            ${degradation.body}
          </p>`}
      ${degradation.causes.length === 0
        ? nothing
        : html`<ul class="degradation-causes" data-testid="sv3-degradation-causes">
            ${degradation.causes.map(
              (cause) => html`<li data-code=${cause.code}>${cause.wording}</li>`,
            )}
          </ul>`}
    </div>`;
  }

  private toggleDegradation(): void {
    this.degradationOpen = !this.degradationOpen;
  }

  /**
   * The remedy, taken. It leaves through the window's ONE remedy exit (`SV3_REMEDY`) exactly as the
   * corpus remedy does — the composer announces where the reader asked to go and the window performs
   * the navigation, so this element still reaches the app's router in exactly zero places.
   */
  private takeDegradationRemedy(): void {
    const target = this.degradation?.remedy.target ?? '';
    if (target === '') return;
    this.dispatchEvent(
      new CustomEvent<Sv3RemedyDetail>(SV3_REMEDY, {
        detail: { target },
        bubbles: true,
        composed: true,
      }),
    );
  }

  /**
   * The primary slot holds EXACTLY ONE control — a switch with four arms, not four conditionals that
   * could each independently decide to render. The design spec early-returns its one action
   * and never disables the loser behind the winner; F1
   * made that structural for Stop-vs-Send and F2 keeps the same construction across all four rungs.
   *
   * The reason string is the window's derivation, and it lands in BOTH `aria-label` and `title`: the
   * routing (Enter vs Ctrl+Enter, or that Enter now steers) is explained here and nowhere else, which
   * is what "no new chrome" means in practice — the composer gained a tier without gaining a band.
   */
  private primaryAction(
    empty: boolean,
    unavailable: boolean,
    describedBy: string | null,
  ): TemplateResult {
    switch (this.slotKind) {
      case 'answer':
        return html`
          <button
            type="button"
            class="send answer"
            aria-label=${this.slotReason}
            title=${this.slotReason}
            data-testid="sv3-composer-answer"
            @click=${this.answer}
          >
            &#8226;
          </button>
        `;
      case 'stop':
        return html`
          <button
            type="button"
            class="stop"
            aria-label=${this.slotReason}
            title=${this.slotReason}
            data-testid="sv3-composer-stop"
            @click=${this.stop}
          >
            <!-- The spec's 12px square with a 1.5 radius. -->
            <svg width="12" height="12" viewBox="0 0 12 12" fill="currentColor" aria-hidden="true">
              <rect x="2" y="2" width="8" height="8" rx="1.5"></rect>
            </svg>
          </button>
        `;
      case 'follow-up':
      case 'send':
        // TWO forms of the one control, and the split is not cosmetic. An empty draft keeps slice-3's
        // native `disabled` — there is nothing to route, so nothing to explain — and carries NO
        // `title`, because a browser suppresses a tooltip on a disabled element and an unreachable
        // reason is worse than none (596 face 1.1, enforced by `check-controls-a11y`). The moment
        // there IS a draft the control is live and the routing hint becomes both true and reachable.
        return empty
          ? html`
              <button
                type="button"
                class="send"
                aria-label="Send"
                disabled
                data-unavailable=${String(unavailable)}
                data-testid="sv3-composer-send"
              >
                &#8593;
              </button>
            `
          : html`
              <button
                type="button"
                class="send"
                aria-label=${this.slotReason}
                title=${this.slotReason}
                data-unavailable=${String(unavailable)}
                aria-describedby=${describedBy ?? nothing}
                data-testid="sv3-composer-send"
                @click=${() => this.submit(this.tier)}
              >
                &#8593;
              </button>
            `;
    }
  }
}

customElements.define('jf-sv3-composer', Sv3Composer);

declare global {
  interface HTMLElementTagNameMap {
    'jf-sv3-composer': Sv3Composer;
  }
}
