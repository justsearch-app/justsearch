// SPDX-License-Identifier: Apache-2.0
/**
 * EnterActionPickerRenderer — first-party `x-ui-renderer` hint
 * renderer per Tempdoc 543 §20.7 B1.
 *
 * Renders the "default action when pressing Enter on a result" enum
 * (open | reveal | preview) as a styled select. Replaces a hand-
 * rolled `<select>` in SettingsSurface so the substrate's
 * x-ui-renderer dispatch path has one real first-party production
 * consumer beyond CorpusPickerRenderer's placeholder.
 *
 * Schema fragment that triggers this renderer:
 *   {
 *     type: 'string',
 *     enum: ['open', 'reveal', 'preview'],
 *     'x-ui-renderer': 'enter-action-select',
 *   }
 *
 * Side-effect registers `'enter-action-select'` → `'jf-enter-action-picker'`
 * at module load.
 */

import { html, css, type TemplateResult } from 'lit';
import { JsonFormsRendererBase } from '../JsonFormsRendererBase.js';
import { selectStyles } from '../../primitives/selectStyles.js';
import { registerXUiRenderer } from './XUiRendererControl.js';

export class EnterActionPickerRenderer extends JsonFormsRendererBase {
  static styles = [selectStyles, css`
    :host {
      display: block;
      margin-block-end: var(--justsearch-shell-form-control-spacing, 0.75rem);
    }
    select {
      padding: 0.375rem 0.5rem;
      border: 1px solid var(--justsearch-shell-form-input-border, #ccc);
      border-radius: var(--justsearch-shell-form-input-radius, 4px);
      font: inherit;
      min-width: 8rem;
    }
    select:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }
  `];

  override render(): TemplateResult {
    if (!this.visible) {
      return html``;
    }
    const value = typeof this.data === 'string' ? this.data : 'open';
    const enumValues = ((this.schema as { enum?: readonly unknown[] }).enum ??
      ['open', 'reveal', 'preview']) as readonly string[];
    // 855 P1 (a11y fix riding along) — axe `select-name`: this <select> had no accessible name
    // (governance/ui-a11y-baseline.v1.json `settings`/`settings-light`, pre-855). Fixed generically
    // at the renderer layer via the standard JSON-Schema `title` field (any x-ui-renderer:
    // 'enter-action-select' consumer now gets a real name), not hardcoded to this one call site.
    const name = this.schema.title ?? 'Enter action';
    return html`
      <select
        data-testid="enter-action-picker"
        aria-label=${name}
        .value=${value}
        ?disabled=${!this.enabled}
        @change=${(e: Event) =>
          this.onChange(
            (e.target as HTMLSelectElement).value,
            this.path,
          )}
      >
        ${enumValues.map(
          (v) => html`<option value=${v}>${labelFor(v)}</option>`,
        )}
      </select>
    `;
  }
}

function labelFor(value: string): string {
  // Title-case the enum value for display. Specific overrides for
  // known values; fallback is title-case.
  switch (value) {
    case 'open':
      return 'Open';
    case 'reveal':
      return 'Reveal';
    case 'preview':
      return 'Preview';
    default:
      return value.charAt(0).toUpperCase() + value.slice(1);
  }
}

if (
  typeof customElements !== 'undefined' &&
  !customElements.get('jf-enter-action-picker')
) {
  customElements.define('jf-enter-action-picker', EnterActionPickerRenderer);
}

// Side-effect registration: hint `'enter-action-select'` now routes
// through the x-ui-renderer dispatcher to <jf-enter-action-picker>.
registerXUiRenderer('enter-action-select', 'jf-enter-action-picker');
