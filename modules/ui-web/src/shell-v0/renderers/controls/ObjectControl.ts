// SPDX-License-Identifier: Apache-2.0
/**
 * Object (nested-properties) control renderer.
 *
 * Per slice 3a.0 §B.A.3: matches JSON schemas with `type: "object"`.
 * Iterates `schema.properties` and instantiates the appropriate
 * child renderer for each property via the registry's `dispatch()`.
 *
 * Path management: each property's child path is `${parentPath}.${propertyName}`,
 * or `propertyName` when at the root.
 *
 * Recursive: child renderers may themselves be Object / Array controls.
 * The dispatch lookup happens once per child on each render pass.
 */

import { html, css, type TemplateResult } from 'lit';
import type { JsonSchema, UISchemaElement } from '@jsonforms/core';
import { JsonFormsRendererBase } from '../JsonFormsRendererBase.js';
import type { RendererTester } from '../rendererTypes.js';
import { RANK_STRUCTURAL_CONTROL } from '../rendererTypes.js';
import { dispatchRenderer, registerRenderer } from '../dispatch.js';

export class ObjectControl extends JsonFormsRendererBase {
  static styles = css`
    :host {
      display: block;
      margin-block-end: var(--justsearch-shell-form-control-spacing, 0.75rem);
    }
    .object-title {
      font-size: var(--justsearch-shell-form-section-title-size, 20px);
      font-weight: var(--justsearch-shell-form-section-title-weight, 600);
      margin-block-end: 0.5rem;
    }
    .object-children {
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
      padding-inline-start: var(--justsearch-shell-form-nesting-indent, 1rem);
      border-inline-start: 1px solid
        var(--justsearch-shell-form-nesting-border, #eee);
    }
    .error {
      color: var(--justsearch-shell-form-error-color, #c00);
      font-size: var(--font-size-xs);
      margin-block-start: 0.25rem;
    }
  `;

  override render(): TemplateResult {
    if (!this.visible) {
      return html``;
    }

    const titleText =
      (this.uischema as { label?: string }).label ??
      (this.schema as { title?: string }).title ??
      this.path;

    const properties =
      (this.schema as { properties?: Record<string, JsonSchema> }).properties ??
      {};

    const dataObj =
      this.data && typeof this.data === 'object' && !Array.isArray(this.data)
        ? (this.data as Record<string, unknown>)
        : {};

    return html`
      ${titleText && this.path !== ''
        ? html`<div class="object-title">${titleText}</div>`
        : null}
      <div class="object-children">
        ${Object.entries(properties).map(([propName, propSchema]) =>
          this.renderChild(propName, propSchema, dataObj[propName]),
        )}
      </div>
      ${this.errors ? html`<div class="error">${this.errors}</div>` : null}
    `;
  }

  /**
   * Render one child property using the registry's dispatch. Returns
   * a Lit directive that mounts the child custom element and threads
   * props through.
   */
  private renderChild(
    propName: string,
    propSchema: JsonSchema,
    propValue: unknown,
  ): TemplateResult {
    const childUischema: UISchemaElement = {
      type: 'Control',
      scope: `#/properties/${propName}`,
    } as UISchemaElement;

    const tag = dispatchRenderer(childUischema, propSchema);
    if (!tag) {
      return html`<div class="error">
        No renderer for property '${propName}' (schema:
        ${JSON.stringify(propSchema)})
      </div>`;
    }

    const childPath = this.path ? `${this.path}.${propName}` : propName;

    // Lit's html template doesn't natively pass complex objects as props
    // via attribute-style binding to dynamically-named elements. The
    // workaround: imperatively create the child element, set its
    // reactive properties, and let Lit's html`${...}` insert the live
    // node into the template.
    const child = document.createElement(tag) as JsonFormsRendererBase;
    child.schema = propSchema;
    child.uischema = childUischema;
    child.path = childPath;
    child.data = propValue;
    child.errors = ''; // Per-property error routing is JSON Forms host concern
    child.enabled = this.enabled;
    child.visible = true;
    child.onChange = this.onChange;
    return html`${child}`;
  }
}

customElements.define('jf-object-control', ObjectControl);

export const objectControlTester: RendererTester = (
  uischema: UISchemaElement,
  schema: JsonSchema,
): number => {
  if (uischema.type !== 'Control') {
    return -1;
  }
  if ((schema as { type?: string }).type !== 'object') {
    return -1;
  }
  return RANK_STRUCTURAL_CONTROL;
};

// Self-register into the dispatch store (cycle-free; tempdoc 530 UI-cycle gate).
registerRenderer(objectControlTester, 'jf-object-control');
