// SPDX-License-Identifier: Apache-2.0
/**
 * SourceChipsRenderer — first-party `x-ui-renderer` hint renderer (569 Fix A / §9 agent kind).
 *
 * Renders an agent answer's sources array as a chip row (the bespoke look of the agent surface's
 * citations), through the engine — part of proving the §9 "dynamic agent surface" KIND as a declared
 * body. CONTENT only: the live token-streaming + tool-call choreography is the §7 long tail (a
 * streaming-aware renderer + a Move-8 statechart), NOT this static projection.
 *
 * Schema fragment (hint on the array property):
 *   sources: { type:'array', 'x-ui-renderer':'source-chips', items:{ properties:{ title, url } } }
 *
 * Side-effect registers `'source-chips'` → `'jf-source-chips'` at module load.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JsonFormsRendererBase } from '../JsonFormsRendererBase.js';
import { registerXUiRenderer } from './XUiRendererControl.js';

interface Source {
  readonly title?: string;
  readonly url?: string;
}

export class SourceChipsRenderer extends JsonFormsRendererBase {
  static styles = css`
    :host {
      display: block;
      margin-block-end: var(--justsearch-shell-form-control-spacing, 0.75rem);
    }
    .chips {
      display: flex;
      flex-wrap: wrap;
      gap: 0.375rem;
    }
    .chip {
      display: inline-flex;
      align-items: center;
      gap: 0.25rem;
      padding: 0.2rem 0.5rem;
      border-radius: 9999px;
      border: 1px solid var(--border-subtle);
      background: var(--surface-2);
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
    }
    .chip .num {
      color: var(--text-secondary);
      font-weight: 600;
    }
  `;

  override render(): TemplateResult {
    if (!this.visible) return html``;
    const sources = Array.isArray(this.data) ? (this.data as Source[]) : [];
    if (sources.length === 0) return html``;
    return html`
      <div class="chips" role="list" aria-label="Sources">
        ${sources.map(
          (s, i) => html`
            <span class="chip" role="listitem">
              <span class="num">${i + 1}</span>
              ${s.title ?? s.url ?? nothing}
            </span>
          `,
        )}
      </div>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-source-chips')) {
  customElements.define('jf-source-chips', SourceChipsRenderer);
}

registerXUiRenderer('source-chips', 'jf-source-chips');
