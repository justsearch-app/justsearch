// SPDX-License-Identifier: Apache-2.0
/**
 * Group (titled section) layout renderer.
 *
 * Per slice 3a.0 §B.A.3: matches uischema with `type: "Group"`.
 * Renders a titled section with a border around its children.
 */

import { html, css, type TemplateResult } from 'lit';
import type { JsonSchema, UISchemaElement } from '@jsonforms/core';
import { JsonFormsRendererBase } from '../JsonFormsRendererBase.js';
import type { RendererTester } from '../rendererTypes.js';
import { RANK_LAYOUT } from '../rendererTypes.js';
import { registerRenderer } from '../dispatch.js';
import { createChildRenderer } from './layoutDispatch.js';

type GroupUISchema = UISchemaElement & {
  elements: UISchemaElement[];
  label?: string;
};

export class GroupLayout extends JsonFormsRendererBase {
  static styles = css`
    :host {
      display: block;
      margin-block-end: var(--justsearch-shell-form-control-spacing, 0.75rem);
    }
    fieldset {
      border: 1px solid var(--justsearch-shell-form-group-border, #ddd);
      border-radius: 4px;
      padding: var(--justsearch-shell-form-group-padding, 0.75rem);
      margin: 0;
    }
    legend {
      font-size: var(--justsearch-shell-form-section-title-size, 20px);
      font-weight: var(--justsearch-shell-form-section-title-weight, 600);
      padding: 0 0.5rem;
    }
    .group-children {
      display: flex;
      flex-direction: column;
      gap: var(--justsearch-shell-form-vertical-gap, 0.75rem);
    }
  `;

  override render(): TemplateResult {
    if (!this.visible) {
      return html``;
    }
    const ui = this.uischema as GroupUISchema;
    const labelText = ui.label ?? '';
    const elements = ui.elements ?? [];

    return html`
      <fieldset>
        ${labelText ? html`<legend>${labelText}</legend>` : null}
        <div class="group-children">
          ${elements.map((childUischema) => this.renderChild(childUischema))}
        </div>
      </fieldset>
    `;
  }

  private renderChild(childUischema: UISchemaElement): TemplateResult {
    const child = createChildRenderer(
      childUischema,
      this.schema,
      this.path,
      this.data,
      this.enabled,
      this.onChange,
    );
    if (!child) {
      return html`<div>
        Group: no renderer for child (uischema:
        ${JSON.stringify(childUischema)})
      </div>`;
    }
    return html`${child}`;
  }
}

customElements.define('jf-group-layout', GroupLayout);

export const groupLayoutTester: RendererTester = (
  uischema: UISchemaElement,
  _schema: JsonSchema,
): number => {
  if (uischema.type !== 'Group') {
    return -1;
  }
  return RANK_LAYOUT;
};

// Self-register into the dispatch store (cycle-free; tempdoc 530 UI-cycle gate).
registerRenderer(groupLayoutTester, 'jf-group-layout');
