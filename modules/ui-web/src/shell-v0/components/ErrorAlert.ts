// SPDX-License-Identifier: Apache-2.0
/**
 * ErrorAlert (`jf-error-alert`) — tempdoc 574 Move 3 (the visual-atom tier). @atom
 *
 * The ONE inline alert/notice box: a tone-bordered, tone-tinted message panel — the §14 `.alert`/
 * `.warning` look re-authored across ~7 surfaces with drifting padding/border/colour. Tone is PROJECTED
 * from the 565 `statusTone` authority (`toneAccent` border+text, `toneAccentSoft` tinted bg), so an alert
 * can never carry an off-palette severity. Composes `jf-button` for the optional dismiss.
 *
 * Scope (the §19.6 AHA cut): the inline *severity message box* only — a toned panel with a message (+
 * optional leading icon, + optional dismiss). The rich error CARD (`PluginErrorOverlay`: title + source +
 * stack + actions) stays bespoke; it is not "a toned message box".
 *
 * Accessible-name rule (WCAG 2.5.3): the dismiss button is icon-only, so `label="Dismiss"` names it; the
 * message is the slotted text. `role="alert"` announces the severity to assistive tech (the bare `.alert`
 * divs had none — a structural a11y gain).
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { type NoticeTone, statusToTone, toneAccent, toneAccentSoft } from '../utils/statusTone.js';
import { icon } from './Icon.js';
import './Button.js';

export class ErrorAlert extends JfElement {
  static properties = {
    tone: { type: String, reflect: true },
    status: { type: String },
    onDismiss: { attribute: false },
  };

  declare tone?: NoticeTone;
  declare status?: string;
  /**
   * When set, a trailing dismiss button renders and invokes this on activate (mirrors jf-button).
   * Reactive (`attribute: false`) so the dismiss appears whenever it is assigned, independent of
   * render timing — a bare `el.onDismiss = fn` after mount re-renders, not only the template binding.
   */
  declare onDismiss: (() => void) | null;

  static styles = css`
    :host {
      display: block;
    }
    .alert {
      display: flex;
      align-items: flex-start;
      gap: 0.5rem;
      padding: 0.625rem 0.75rem;
      border-radius: 8px;
      border: 1px solid var(--alert-fg);
      background: var(--alert-bg);
      color: var(--alert-fg);
      font-size: var(--font-size-sm);
    }
    .msg {
      flex: 1;
      min-width: 0;
    }
    .dismiss {
      margin-left: auto;
      flex-shrink: 0;
    }
  `;

  override render(): TemplateResult {
    const tone = this.tone ?? statusToTone(this.status);
    return html`<div
      class="alert"
      part="alert"
      role="alert"
      .style=${`--alert-fg: ${toneAccent(tone)}; --alert-bg: ${toneAccentSoft(tone)}`}
    >
      <slot name="icon"></slot>
      <span class="msg"><slot></slot></span>
      ${this.onDismiss
        ? html`<jf-button
            class="dismiss"
            variant="ghost"
            size="icon"
            label="Dismiss"
            .onActivate=${this.onDismiss}
            >${icon({ name: 'x', size: 12 })}</jf-button
          >`
        : nothing}
    </div>`;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-error-alert')) {
  customElements.define('jf-error-alert', ErrorAlert);
}
