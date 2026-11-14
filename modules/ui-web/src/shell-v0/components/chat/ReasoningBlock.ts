// SPDX-License-Identifier: Apache-2.0
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import { copyToClipboard } from '../../utils/clipboardCopy.js';
import type { ReasoningController } from '../../controllers/ReasoningController.js';
// Tempdoc 859 §A §1.7 (A4) — the product's ONE icon set. The clipboard glyph this replaces was a raw
// emoji codepoint, which each platform draws in its own colour and metrics.
import { icon } from '../Icon.js';

import './MarkdownBlock.js';

export class ReasoningBlock extends JfElement {
  static properties = {
    controller: { attribute: false },
    text: { type: String },
    durationMs: { type: Number },
    streaming: { type: Boolean },
    inline: { type: Boolean, reflect: true },
  };

  declare controller: ReasoningController | null;
  declare text: string;
  declare durationMs: number;
  /**
   * Tempdoc 859 §A — the LIVE flag for a controller-less block. The run timeline renders each region
   * as a value (text + duration + this), and at most one of them — the run's open region, always the
   * newest — is ever true, so a finished thought has no path to the live affordance at all.
   */
  declare streaming: boolean;
  /**
   * Tempdoc 859 §A §1.6 (A2) — the IN-FEED placement: an annotation on the step below it, not a card
   * competing with one. Declared as a mode on the component rather than as host CSS reaching into the
   * shadow tree, so the two placements are a stated choice and not a selector accident.
   */
  declare inline: boolean;

  private collapsed = true;
  private wasStreaming = false;

  constructor() {
    super();
    this.controller = null;
    this.text = '';
    this.durationMs = 0;
    this.streaming = false;
    this.inline = false;
  }

  private get effectiveText(): string {
    if (this.controller) {
      if (this.controller.isThinking) return this.controller.reasoningText;
      const last = this.controller.reasoningBlocks[this.controller.reasoningBlocks.length - 1];
      return last?.text ?? '';
    }
    return this.text;
  }

  private get effectiveIsStreaming(): boolean {
    return this.controller?.isThinking ?? this.streaming;
  }

  private get effectiveDurationMs(): number {
    if (this.controller?.isThinking) return this.controller.elapsedSeconds * 1000;
    if (this.controller) {
      const last = this.controller.reasoningBlocks[this.controller.reasoningBlocks.length - 1];
      return last?.durationMs ?? 0;
    }
    return this.durationMs;
  }

  override updated(_changed: Map<string, unknown>): void {
    const streaming = this.effectiveIsStreaming;
    if (this.wasStreaming && !streaming && this.effectiveText) {
      this.collapsed = true;
      this.requestUpdate();
    }
    this.wasStreaming = streaming;
  }

  static styles = css`
    :host {
      display: block;
    }
    /* Tempdoc 870 item 3, the seam the slim form has to bring with it. The card this replaced
       carried its own 0.5rem of padding and a fill, which is what separated it from whatever
       followed; a bare 12px line has neither, and NOT every container supplies the gap. The four
       non-sv3 mount sites split on exactly this: NavigateView's and SummarizeView's '.conversation'
       are flex columns with 'gap: 0.75rem', but UnifiedChatView's '.message'
       (views/unifiedChatStyles.ts) is a plain block and Search v3's own '.turn' has only a
       'padding-bottom' — in both the disclosure would abut the answer prose with nothing between
       them. The margin is declared HERE, on the block, because the block is what changed; pushing it
       onto each container would be four edits that can drift and a fifth mount site with none.
       Gap-bearing containers add it to their gap, which is the direction that is safe to be wrong in
       (the net box is still shorter than the card's was). The '[inline]' form is excluded: the run
       feed's gap is that arm's ONE spacing authority, per 859 §A §1.6. */
    :host(:not([inline])) {
      margin-block-end: 0.5rem;
    }
    .container {
      font-size: var(--font-size-sm);
      /* Tempdoc 853 (F-07) — was '--text-muted', measured 4.11:1 (light) / 4.40:1 (hc-light) at 13px:
         below AA on the label AND the whole reasoning transcript. '--text-secondary' is the next
         grade up and is redeclared by every palette including both HC blocks, so it clears AA in all
         four (9.62 / 11.02 / 11.93 / 13.39 measured on the same surface family). The muted grade's own
         HC gap is fixed in 'styles/tokens.css' for every other consumer; this block does not need to
         be the dimmest grade to read as an aside — the rule and the surface already say that. */
      color: var(--text-secondary);
    }
    /* Tempdoc 859 §A §1.6 (A2) — the IN-FEED form. A tool card is an ACTION; a thought is subordinate
       to the action it produced, so in the run timeline the block keeps the hairline left rule, which
       is what says "aside". It used to also RESET a filled box and a radius the base rule set; 870
       item 3 retired those from the base for both forms, so the resets went with them rather than
       staying on as no-ops that read like live declarations. Vertical rhythm comes from '.run-feed''s
       gap — ONE spacing authority, which is also why this form takes no host margin — so the block
       contributes padding only: 0.25rem above and below the 24px WCAG target floor puts the collapsed
       row at 32px. The floor itself (F-09) and the '--text-secondary' grade (F-07) are untouched. */
    :host([inline]) .container {
      border-left: 3px solid var(--border-muted);
      padding-block: 0.25rem;
      padding-inline: 0.75rem 0;
    }
    /* Tempdoc 870 item 3 — the ASK-ARM form, which was the card the '[inline]' rule above already
       walked away from: a filled, radiused, full-width box announcing "Thought for 7s" louder than
       the answer it precedes. It is a slim text disclosure now — the same shape as the tail's
       'Sources ›' trigger one row below it (Sv3Main.ts '.tail-sources'): muted 12px text, a trailing
       chevron, no box. The card declarations moved OFF the base rule rather than being overridden
       back off it, so neither form carries chrome it does not draw, and '[inline]' restates the one
       thing it does keep — the hairline rule that says "aside".
       The GRADE is deliberately NOT dropped to the muted rung with the chrome: tempdoc 853 F-07
       measured '--text-muted' at 4.11:1 on this component's own label and moved it up, so "slimmer"
       may not buy itself contrast an audit already spent. The de-emphasis this item wanted is bought
       with SIZE and with the box, which cost no contrast at all. */
    .header {
      display: flex;
      align-items: center;
      gap: 0.5rem;
    }
    /* Sizes to its CONTENT, so the collapsed row is a line of text with a hit target around it and
       not a full-width band painted across the column. */
    :host(:not([inline])) .header {
      display: inline-flex;
      font-size: var(--font-size-xs);
    }
    /* Tempdoc 853 (F-08) — the disclosure is a REAL button and the copy control is its SIBLING, not
       its descendant. The header was 'role="button" tabindex="0"' wrapping a focusable
       '<button class="copy-btn">': axe 'nested-interactive' (serious, WCAG 4.1.2) in every palette
       and both windows, with AT free to drop the copy control entirely. The row itself is now inert
       layout. Same disclosure shape ToolCallCard's '.expand-toggle' already uses. */
    .disclosure {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      flex: 1;
      min-width: 0;
      /* Tempdoc 853 (F-09) — WCAG 2.2 2.5.8 (Target Size, Minimum). The audit measured this row's
         hit area at 541 x 20 CSS px: wide enough, two pixels short in the block axis. 24px is the
         criterion's own number, so it is written as the literal the spec names rather than routed
         through a design-scale token that could later be re-tuned out from under it. The row is
         already taller than its text at rest, so this changes nothing visually — it only pins the
         floor against a future line-height or font-size change. */
      min-height: 24px;
      background: none;
      border: none;
      padding: 0;
      margin: 0;
      font: inherit;
      color: inherit;
      text-align: start;
      cursor: pointer;
      user-select: none;
    }
    /* The slim form's row is as wide as its own text (870 item 3); only the in-feed form still
       stretches, where the copy control's 'margin-left: auto' is what puts it at the far edge. */
    :host(:not([inline])) .disclosure {
      flex: 0 0 auto;
    }
    .disclosure:hover {
      color: var(--text-primary);
    }
    .disclosure:focus-visible {
      outline: 2px solid var(--focus-ring-color);
      outline-offset: 2px;
      border-radius: 4px;
    }
    .chevron {
      transition: transform var(--duration-fast) var(--ease-standard);
      font-size: var(--font-size-xs);
    }
    .chevron.expanded {
      transform: rotate(90deg);
    }
    /* The chevron TRAILS the label in the slim form (t3code's disclosure shape), by flex order
       rather than by DOM order: the label is what AT should reach first, and the run-timeline form
       keeps its leading marker. */
    :host(:not([inline])) .chevron {
      order: 1;
    }
    .label {
      font-weight: 500;
    }
    .copy-btn {
      margin-left: auto;
      /* Tempdoc 853 (F-09) — measured 23 x 19 CSS px, under the WCAG 2.2 2.5.8 24 x 24 floor in both
         axes. inline-flex + centring grows the box around the glyph without moving or resizing the
         glyph itself, so the padding below still governs the resting look. */
      display: inline-flex;
      align-items: center;
      justify-content: center;
      min-width: 24px;
      min-height: 24px;
      background: none;
      border: none;
      color: var(--text-secondary);
      cursor: pointer;
      font-size: var(--font-size-xs);
      padding: 0.125rem 0.25rem;
      border-radius: 4px;
    }
    .copy-btn:hover {
      color: var(--text-primary);
      background: var(--surface-subtle);
    }
    .copy-btn:focus-visible {
      outline: 2px solid var(--focus-ring-color);
      outline-offset: 2px;
    }
    .content {
      margin-top: 0.5rem;
      max-height: 300px;
      overflow-y: auto;
    }
    .content.hidden {
      display: none;
    }
    /* The expanded trace keeps the hairline the collapsed header gave up (870 item 3), so an open
       block is still visibly subordinate — the same treatment '[inline]' carries on its container. */
    :host(:not([inline])) .content {
      border-left: 3px solid var(--border-muted);
      padding-left: 0.75rem;
    }
    .content jf-markdown-block {
      /* Tempdoc 853 (F-07) — the transcript body rode the same failing grade as the label. */
      --text-primary: var(--text-secondary);
      font-size: var(--font-size-sm);
    }
    jf-pulse-dots {
      margin-left: 0.25rem;
    }
  `;

  private toggle(): void {
    this.collapsed = !this.collapsed;
    this.requestUpdate();
  }

  private async copyText(): Promise<void> {
    const text = this.effectiveText;
    if (!text) return;
    // 574 B2 — the one clipboard authority (never throws; handles availability + failure).
    await copyToClipboard(text);
  }

  override render(): TemplateResult {
    const streaming = this.effectiveIsStreaming;
    const text = this.effectiveText;
    const durationMs = this.effectiveDurationMs;
    const showContent = !this.collapsed && text.length > 0;
    const seconds = Math.max(1, Math.round(durationMs / 1000));
    // Tempdoc 859 §A §3.1.8 (D-5) — the elapsed figure falls back to the ITEM's own duration. A
    // controller-less streaming block (the run timeline's open region) has no controller to ask, and
    // the `?? 0` this replaces rendered a live, ticking thought as "Thinking (0s)" forever.
    const label = streaming
      ? `Thinking (${this.controller?.elapsedSeconds ?? seconds}s)`
      : `Thought for ${seconds}s`;

    return html`
      <div class="container">
        ${/* Tempdoc 853 (F-08) — `.header` is inert layout; the disclosure and the copy control are
             SIBLING buttons inside it. A native <button> also brings Enter AND Space activation for
             free, which the hand-rolled `role="button"` keydown handler this replaces had to spell
             out (and which no longer needs a `stopPropagation` on copy — nothing to bubble into). */ ''}
        <div class="header">
          <button
            class="disclosure"
            aria-expanded="${showContent}"
            aria-label="Model reasoning trace"
            @click=${this.toggle}
          >
            <span class="chevron ${showContent ? 'expanded' : ''}">&#x25B6;</span>
            <span class="label">${label}</span>
            ${streaming ? html`<jf-pulse-dots></jf-pulse-dots>` : nothing}
          </button>
          ${/* Tempdoc 853 (F-09) — the accessible name used to compute from the glyph itself, so the
               control announced as "clipboard" (or as nothing) and `title` never won. `aria-label`
               names it outright and the glyph is removed from the a11y tree, matching the
               title + aria-label pairing UnifiedChatView's turn actions already use. */ ''}
          ${/* Tempdoc 870 item 4 — the copy control belongs to the TRACE, and the trace is what the
               disclosure hides. It lived in `.header`, which is outside `.content`, so a collapsed
               block offered "copy" for text the reader could not see: an action on hidden content,
               and a second control competing with the one-line disclosure it sits beside. It is
               gated on the same `showContent` the trace is now. */ ''}
          ${showContent && !streaming && text
            ? html`<button
                class="copy-btn"
                @click=${() => void this.copyText()}
                title="Copy reasoning"
                aria-label="Copy reasoning"
              ><span aria-hidden="true">${icon({ name: 'copy', size: 14 })}</span></button>`
            : nothing}
        </div>
        <div class="content ${showContent ? '' : 'hidden'}">
          <jf-markdown-block
            .text=${text}
            ?is-streaming=${streaming}
          ></jf-markdown-block>
        </div>
      </div>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-reasoning-block')) {
  customElements.define('jf-reasoning-block', ReasoningBlock);
}
