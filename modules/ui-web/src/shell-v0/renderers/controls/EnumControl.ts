// SPDX-License-Identifier: Apache-2.0
/**
 * Enum (select dropdown) control renderer.
 *
 * Per slice 3a.0 §B.A.3: matches JSON schemas with an `enum` array.
 * Rank: RANK_SPECIALIZED_CONTROL — outranks the basic string/number
 * tester so an `enum`-shaped schema gets a select instead of a text
 * input.
 */

import { html, css, type PropertyValues, type TemplateResult } from 'lit';
import type { JsonSchema, UISchemaElement } from '@jsonforms/core';
import { JsonFormsRendererBase } from '../JsonFormsRendererBase.js';
import { selectStyles } from '../../primitives/selectStyles.js';
import type { RendererTester } from '../rendererTypes.js';
import { RANK_SPECIALIZED_CONTROL } from '../rendererTypes.js';
import { registerRenderer } from '../dispatch.js';

export class EnumControl extends JsonFormsRendererBase {
  static styles = [selectStyles, css`
    :host {
      display: block;
      margin-block-end: var(--justsearch-shell-form-control-spacing, 0.75rem);
    }
    label {
      display: block;
      font-size: var(--justsearch-shell-form-label-size, 0.8125rem);
      font-weight: var(--justsearch-shell-form-label-weight, 500);
      margin-block-end: 0.25rem;
    }
    select {
      width: 100%;
      box-sizing: border-box;
      padding: var(--justsearch-shell-form-input-padding, 0.5rem);
      border: 1px solid var(--justsearch-shell-form-input-border, #ccc);
      border-radius: var(--justsearch-shell-form-input-radius, 4px);
      font: inherit;
    }
    select:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }
    .error {
      color: var(--justsearch-shell-form-error-color, #c00);
      font-size: var(--font-size-xs);
      margin-block-start: 0.25rem;
    }
  `];

  override render(): TemplateResult {
    if (!this.visible) {
      return html``;
    }

    const labelText =
      (this.uischema as { label?: string }).label ??
      (this.schema as { title?: string }).title ??
      this.path;

    const enumValues =
      (this.schema as { enum?: ReadonlyArray<unknown> }).enum ?? [];

    return html`
      <label>
        ${labelText}
        <select
          ?disabled=${!this.enabled}
          @change=${(e: Event) => {
            const raw = (e.target as HTMLSelectElement).value;
            if (raw === '') {
              this.updateData(undefined);
              return;
            }
            // Re-find the original enum value (preserve type) from the
            // string that select fired with.
            const matched = enumValues.find((v) => String(v) === raw);
            this.updateData(matched ?? raw);
          }}
        >
          <option value=""></option>
          ${enumValues.map(
            (v) => html`<option value=${String(v)}>${String(v)}</option>`,
          )}
        </select>
      </label>
      ${this.errors ? html`<div class="error">${this.errors}</div>` : null}
    `;
  }

  /**
   * Sync `select.value` after every render. The browser's HTMLSelectElement
   * needs its option children to exist before `.value` can be set, so
   * doing it inline as a `.value=` directive in the template loses the
   * value (Lit appends children after committing properties). The
   * `updated()` hook fires after children are in the DOM.
   */
  protected override updated(_changed: PropertyValues): void {
    const select = this.shadowRoot?.querySelector('select');
    if (select) {
      const currentValue = this.data ?? '';
      const target = String(currentValue);
      if (select.value !== target) {
        select.value = target;
      }
    }
  }
}

customElements.define('jf-enum-control', EnumControl);

/**
 * Tester: matches schemas with an `enum` array. Outranks basic
 * string/number testers.
 */
export const enumControlTester: RendererTester = (
  uischema: UISchemaElement,
  schema: JsonSchema,
): number => {
  if (uischema.type !== 'Control') {
    return -1;
  }
  const e = (schema as { enum?: ReadonlyArray<unknown> }).enum;
  if (!Array.isArray(e) || e.length === 0) {
    return -1;
  }
  return RANK_SPECIALIZED_CONTROL;
};

// Self-register into the dispatch store (cycle-free; tempdoc 530 UI-cycle gate).
registerRenderer(enumControlTester, 'jf-enum-control');
