// SPDX-License-Identifier: Apache-2.0
/**
 * StatusDot (`jf-status-dot`) — tempdoc 574 Move 3 (the visual-atom tier). @atom
 *
 * The ONE status-dot atom. Before this, a tone-coloured dot was re-authored inline in every
 * status-dense surface (§14 V1 / §16 S2 — HealthSurface `.card-status-dot`, the connection dot,
 * `.disconnected-dot`, …), each picking its own size and `var(--accent-*)` colour. This atom is
 * empirically uniform (a small filled circle whose colour is the status tone), so it passes the AHA
 * cut where a generic "badge" did not (§21/P3): every status dot is one shell + a tone.
 *
 * Colour is PROJECTED from the 565 `statusTone` authority — pass `status` (a lifecycle word) or an
 * explicit `tone`; the dot can never carry an off-palette colour. Set `live` to express an in-progress
 * lifecycle (a soft opacity pulse, via a dot-local `@keyframes jf-dot-pulse` — the ambient `spin`
 * keyframe is for spinners, not status dots); `prefers-reduced-motion` disables it. a11y:
 * decorative by default (`role="presentation"`); pass `label` to announce it as a status image.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { type NoticeTone, statusToTone, toneAccent } from '../utils/statusTone.js';

export class StatusDot extends JfElement {
  static properties = {
    tone: { type: String },
    status: { type: String },
    size: { type: String, reflect: true },
    live: { type: Boolean, reflect: true },
    label: { type: String },
  };

  declare tone?: NoticeTone;
  declare status?: string;
  declare size?: 'sm' | 'md' | 'lg';
  declare live?: boolean;
  declare label?: string;

  constructor() {
    super();
    this.size = 'md';
  }

  static styles = css`
    :host {
      display: inline-flex;
      align-items: center;
    }
    .dot {
      width: var(--dot-size, 0.5rem);
      height: var(--dot-size, 0.5rem);
      border-radius: 50%;
      background: var(--dot-color, var(--text-secondary));
      flex: none;
    }
    :host([size='sm']) {
      --dot-size: 0.4rem;
    }
    :host([size='lg']) {
      --dot-size: 0.625rem;
    }
    :host([live]) .dot {
      animation: jf-dot-pulse 1.4s ease-in-out infinite;
    }
    @keyframes jf-dot-pulse {
      0%,
      100% {
        opacity: 1;
      }
      50% {
        opacity: 0.35;
      }
    }
    @media (prefers-reduced-motion: reduce) {
      :host([live]) .dot {
        animation: none;
      }
    }
  `;

  override render(): TemplateResult {
    const tone = this.tone ?? statusToTone(this.status);
    const labelled = this.label != null && this.label !== '';
    return html`<span
      class="dot"
      part="dot"
      role=${labelled ? 'img' : 'presentation'}
      aria-label=${labelled ? this.label! : nothing}
      .style=${`--dot-color: ${toneAccent(tone)}`}
    ></span>`;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-status-dot')) {
  customElements.define('jf-status-dot', StatusDot);
}
