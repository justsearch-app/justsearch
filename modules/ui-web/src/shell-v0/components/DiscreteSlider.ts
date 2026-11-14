// SPDX-License-Identifier: Apache-2.0
/**
 * DiscreteSlider (`jf-discrete-slider`) — tempdoc 855 §15.2 (T2's small-ordinal-scale shape). @atom
 *
 * A themed discrete slider over a small fixed set of steps (owner's Density idiom:
 * compact/comfortable/spacious) — a native `<input type="range">` (keyboard-operable by
 * construction) with tick labels below and `aria-valuetext` carrying the
 * CURRENT step's own label (the native range's index-based `aria-valuenow`/min/max are meaningless
 * to a screen reader on their own — no themed slider existed anywhere in the codebase before this;
 * radios remain the documented fallback if a future consumer's steps don't fit this shape).
 *
 * Plain props only: `steps` (`{value,label}[]`) + `value` (the CURRENT step's `value`) + optional
 * `label` (the control's own accessible name, e.g. "Density") + `disabled`. Emits
 * `CustomEvent('change', {detail:{value}})` on every `input` (instant-apply, matching the house
 * pattern's other controls — jf-switch/jf-option-button-group also apply immediately, no separate
 * commit step).
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';

/** Plain-props step shape: a stop's persisted `value` + its display `label`. */
export interface DiscreteSliderStep {
  readonly value: string;
  readonly label: string;
}

export class DiscreteSlider extends JfElement {
  static properties = {
    steps: { attribute: false },
    value: { type: String },
    label: { type: String },
    disabled: { type: Boolean },
  };

  declare steps: readonly DiscreteSliderStep[];
  declare value: string;
  declare label?: string;
  declare disabled: boolean;

  constructor() {
    super();
    this.steps = [];
    this.value = '';
    this.label = undefined;
    this.disabled = false;
  }

  static styles = css`
    :host {
      display: block;
      width: 100%;
      max-width: 280px;
    }
    input[type='range'] {
      width: 100%;
      -webkit-appearance: none;
      appearance: none;
      height: 1.25rem;
      background: transparent;
      cursor: pointer;
      margin: 0;
      padding: 0;
    }
    input[type='range']:disabled {
      cursor: not-allowed;
      opacity: 0.5;
    }
    input[type='range']::-webkit-slider-runnable-track {
      height: 0.25rem;
      border-radius: 9999px;
      background: var(--surface-tertiary);
    }
    input[type='range']::-moz-range-track {
      height: 0.25rem;
      border-radius: 9999px;
      background: var(--surface-tertiary);
    }
    input[type='range']::-webkit-slider-thumb {
      -webkit-appearance: none;
      appearance: none;
      width: 1rem;
      height: 1rem;
      border-radius: 50%;
      background: var(--text-secondary);
      border: 2px solid var(--surface-primary);
      margin-top: -0.375rem;
    }
    input[type='range']::-moz-range-thumb {
      width: 1rem;
      height: 1rem;
      border: 2px solid var(--surface-primary);
      border-radius: 50%;
      background: var(--text-secondary);
    }
    input[type='range']:focus-visible {
      outline: none;
    }
    input[type='range']:focus-visible::-webkit-slider-thumb {
      outline: 2px solid var(--focus-ring-color);
      outline-offset: 2px;
    }
    input[type='range']:focus-visible::-moz-range-thumb {
      outline: 2px solid var(--focus-ring-color);
      outline-offset: 2px;
    }
    .ticks {
      display: flex;
      justify-content: space-between;
      margin-top: 0.25rem;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .ticks span {
      flex: 1;
      text-align: center;
    }
    .ticks span:first-child {
      text-align: left;
    }
    .ticks span:last-child {
      text-align: right;
    }
  `;

  private indexOf(value: string): number {
    const i = this.steps.findIndex((s) => s.value === value);
    return i >= 0 ? i : 0;
  }

  private onInput(e: Event): void {
    if (this.disabled) return;
    const index = Number((e.target as HTMLInputElement).value);
    const step = this.steps[index];
    if (!step) return;
    this.value = step.value;
    this.dispatchEvent(
      new CustomEvent('change', { detail: { value: step.value }, bubbles: true, composed: true }),
    );
  }

  override render(): TemplateResult {
    if (this.steps.length === 0) return html``;
    const index = this.indexOf(this.value);
    const current = this.steps[index];
    return html`
      <input
        type="range"
        min="0"
        max=${this.steps.length - 1}
        step="1"
        .value=${String(index)}
        ?disabled=${this.disabled}
        aria-label=${this.label ?? nothing}
        aria-valuetext=${current?.label ?? ''}
        @input=${(e: Event) => this.onInput(e)}
      />
      <div class="ticks" aria-hidden="true">${this.steps.map((s) => html`<span>${s.label}</span>`)}</div>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-discrete-slider')) {
  customElements.define('jf-discrete-slider', DiscreteSlider);
}
