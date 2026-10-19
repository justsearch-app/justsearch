// SPDX-License-Identifier: Apache-2.0
/**
 * Switch (`jf-switch`) — tempdoc 855 §15.2/§17 R2 (the one shared switch atom). @atom
 *
 * Extracted verbatim (visual + a11y) from `ToggleSwitchRenderer`'s `.switch` — the settings page
 * hand-rolled FOUR copies of this look (missing `aria-checked`, a literal `white` knob) while the
 * declared-renderer stack already had a correct one. This is the single source of truth now:
 * `ToggleSwitchRenderer` composes it internally, and every hand-rolled `.switch` site converts to it.
 * Registered in `governance/atom-facets.v1.json` (`forkClasses: ["switch"]`) so the atom-fork ratchet
 * forbids a fifth hand-rolled copy.
 *
 * Plain props only (no JsonForms plumbing): `checked` / `label` (→ `aria-label`, optional) /
 * `disabled`. Emits `CustomEvent('change', { detail: { checked } })` on click or Space/Enter — the
 * component does not own state, the consumer re-renders with the new `checked`.
 *
 * The click/keydown handlers sit on the SAME element that carries `role="switch"` + `tabindex`
 * (the 559 standalone-affordance triad, formerly scanned by the retired controls-a11y gate).
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';

export class Switch extends JfElement {
  static properties = {
    checked: { type: Boolean },
    label: { type: String },
    disabled: { type: Boolean },
  };

  declare checked: boolean;
  declare label?: string;
  declare disabled: boolean;

  constructor() {
    super();
    this.checked = false;
    this.label = undefined;
    this.disabled = false;
  }

  static styles = css`
    :host {
      display: inline-flex;
    }
    .switch {
      width: 2.5rem;
      height: 1.25rem;
      border-radius: 9999px;
      background: var(--surface-tertiary);
      border: 1px solid var(--border-subtle);
      position: relative;
      cursor: pointer;
      transition: background var(--duration-fast) var(--ease-standard);
      flex: none;
    }
    .switch::after {
      content: '';
      position: absolute;
      top: 1px;
      left: 1px;
      width: 1rem;
      height: 1rem;
      border-radius: 50%;
      background: var(--text-secondary);
      transition: left var(--duration-fast) var(--ease-standard), background var(--duration-fast) var(--ease-standard);
    }
    .switch.on {
      background: var(--accent-tint);
      border-color: var(--accent-tint);
    }
    .switch.on::after {
      left: 1.25rem;
      background: var(--accent-on-tint);
    }
    .switch.disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }
    /* Forced colors (Windows High Contrast) repaint both fills as Canvas, which made the thumb
       disappear and on/off identical. The thumb is restated in CanvasText, and "on" takes the
       selection pair: a Highlight track under a HighlightText thumb. */
    @media (forced-colors: active) {
      .switch::after {
        background: CanvasText;
      }
      .switch.on {
        background: Highlight;
        border-color: Highlight;
      }
      .switch.on::after {
        background: HighlightText;
      }
    }
  `;

  private toggle(): void {
    if (this.disabled) return;
    this.dispatchEvent(
      new CustomEvent('change', {
        detail: { checked: !this.checked },
        bubbles: true,
        composed: true,
      }),
    );
  }

  override render(): TemplateResult {
    return html`
      <div
        class="switch ${this.checked ? 'on' : ''} ${this.disabled ? 'disabled' : ''}"
        role="switch"
        aria-checked=${this.checked ? 'true' : 'false'}
        aria-disabled=${this.disabled ? 'true' : nothing}
        aria-label=${this.label ?? nothing}
        tabindex=${this.disabled ? '-1' : '0'}
        @click=${this.toggle}
        @keydown=${(e: KeyboardEvent) => {
          if (e.key === ' ' || e.key === 'Enter') {
            e.preventDefault();
            this.toggle();
          }
        }}
      ></div>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-switch')) {
  customElements.define('jf-switch', Switch);
}
