// SPDX-License-Identifier: Apache-2.0
/**
 * Vertical layout renderer.
 *
 * Per slice 3a.0 §B.A.3: matches uischema with `type: "VerticalLayout"`.
 * Stacks children vertically with consistent spacing.
 */

import { html, css, type TemplateResult } from 'lit';
import type { JsonSchema, UISchemaElement } from '@jsonforms/core';
import { JsonFormsRendererBase } from '../JsonFormsRendererBase.js';
import type { RendererTester } from '../rendererTypes.js';
import { RANK_LAYOUT } from '../rendererTypes.js';
import { registerRenderer } from '../dispatch.js';
import { createChildRenderer } from './layoutDispatch.js';

type LayoutUISchema = UISchemaElement & { elements: UISchemaElement[] };

export class VerticalLayout extends JsonFormsRendererBase {
  private children_: (JsonFormsRendererBase | null)[] = [];

  static styles = css`
    :host {
      display: flex;
      flex-direction: column;
      gap: var(--justsearch-shell-form-vertical-gap, 0.75rem);
    }
  `;

  override render(): TemplateResult {
    if (!this.visible) {
      return html``;
    }
    const elements = (this.uischema as LayoutUISchema).elements ?? [];
    this.children_.length = elements.length;
    return html`
      ${elements.map((childUischema, index) => this.renderChild(childUischema, index))}
    `;
  }

  private renderChild(childUischema: UISchemaElement, index: number): TemplateResult {
    const child = this.children_[index] = createChildRenderer(
      childUischema,
      this.schema,
      this.path,
      this.data,
      this.enabled,
      this.onChange,
      this.userConfig, // 543-fwd — was dropped (6 args), so child renderers lost user config
      this.children_[index],
    );
    if (!child) {
      return html`<div>
        Layout: no renderer for child (uischema:
        ${JSON.stringify(childUischema)})
      </div>`;
    }
    return html`${child}`;
  }
}

customElements.define('jf-vertical-layout', VerticalLayout);

export const verticalLayoutTester: RendererTester = (
  uischema: UISchemaElement,
  _schema: JsonSchema,
): number => {
  if (uischema.type !== 'VerticalLayout') {
    return -1;
  }
  return RANK_LAYOUT;
};

// Self-register into the dispatch store (cycle-free; tempdoc 530 UI-cycle gate).
registerRenderer(verticalLayoutTester, 'jf-vertical-layout');
