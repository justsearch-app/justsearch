// SPDX-License-Identifier: Apache-2.0
/**
 * CorpusPickerRenderer — canonical first-party `x-ui-renderer` hint
 * renderer per Tempdoc 543 §13.3.1.
 *
 * Demonstrates the Form primitive's hint-keyed dispatch: an Action
 * (or any consumer) declares `parameters: { type: 'object', properties:
 * { corpusId: { type: 'string', 'x-ui-renderer': 'corpus-picker' } } }`
 * and the kernel mounts this element in place of the default text
 * input.
 *
 * Today the corpus catalog is not first-class state, so this renderer
 * presents a simple text input with a placeholder. The structural
 * point is the dispatch: the substrate routes `corpus-picker` here
 * instead of the basic text control, proving the §13.3.1 wire.
 *
 * Per §13.3.1 layer placement: this renderer is feature-module code.
 * It registers itself via `registerXUiRenderer()` at module load.
 * Plugins will ship analogous renderers through the
 * ContributionManifest's `renderers` entry once Slice 9 lands.
 */

import { html, css, type TemplateResult } from 'lit';
import { JsonFormsRendererBase } from '../JsonFormsRendererBase.js';
import { registerXUiRenderer } from './XUiRendererControl.js';

export class CorpusPickerRenderer extends JsonFormsRendererBase {
  static styles = css`
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
    .picker {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      padding: 0.375rem 0.5rem;
      border: 1px solid var(--justsearch-shell-form-input-border, #ccc);
      border-radius: var(--justsearch-shell-form-input-radius, 4px);
      background: var(--justsearch-shell-form-input-bg, transparent);
    }
    .picker__icon {
      flex: 0 0 auto;
      width: 1rem;
      height: 1rem;
      color: var(--text-tint);
    }
    .picker__input {
      flex: 1 1 auto;
      border: none;
      outline: none;
      background: transparent;
      color: inherit;
      font: inherit;
      padding: 0.125rem 0;
    }
    /* Transparent, not none: invisible as before, but forced colors repaint it into a ring. */
    .picker__input:focus-visible {
      outline: 2px solid transparent;
    }
    .picker__hint {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
  `;

  override render(): TemplateResult {
    if (!this.visible) {
      return html``;
    }
    const labelText =
      (this.uischema as { label?: string }).label ??
      (this.schema as { title?: string }).title ??
      'Corpus';
    const value = typeof this.data === 'string' ? this.data : '';
    return html`
      <label data-testid="corpus-picker">
        ${labelText}
        <div class="picker">
          <svg
            class="picker__icon"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <ellipse cx="12" cy="5" rx="9" ry="3" />
            <path d="M3 5v14a9 3 0 0 0 18 0V5" />
            <path d="M3 12a9 3 0 0 0 18 0" />
          </svg>
          <input
            class="picker__input"
            type="text"
            .value=${value}
            placeholder="corpus-id"
            ?disabled=${!this.enabled}
            @input=${(e: Event) =>
              this.onChange(
                (e.target as HTMLInputElement).value,
                this.path,
              )}
          />
        </div>
        <div class="picker__hint">
          Pick a corpus id (placeholder UI — corpus catalog not yet
          first-class state).
        </div>
      </label>
    `;
  }
}

if (
  typeof customElements !== 'undefined' &&
  !customElements.get('jf-corpus-picker')
) {
  customElements.define('jf-corpus-picker', CorpusPickerRenderer);
}

// Side-effect registration: the hint name `'corpus-picker'` now
// routes through the x-ui-renderer dispatcher to `<jf-corpus-picker>`.
// Importing this module from a feature's index.ts (or Shell.ts boot)
// is enough to wire it.
registerXUiRenderer('corpus-picker', 'jf-corpus-picker');
