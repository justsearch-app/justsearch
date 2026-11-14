// SPDX-License-Identifier: Apache-2.0
/**
 * Array (list of items) control renderer.
 *
 * Per slice 3a.0 §B.A.3: matches JSON schemas with `type: "array"`.
 * Iterates the data array and instantiates the appropriate child
 * renderer for each item via the registry's `dispatch()`. Uses
 * `schema.items` as the per-item schema.
 *
 * Path management: each item's child path is `${parentPath}.${index}`.
 *
 * Add / remove buttons: V1 ships add (append) + remove (per-item).
 * Reorder is a future enhancement.
 *
 * Recursive: items may themselves be Object / Array controls.
 */

import { html, css, type TemplateResult } from 'lit';
import type { JsonSchema, UISchemaElement } from '@jsonforms/core';
import { JsonFormsRendererBase } from '../JsonFormsRendererBase.js';
import type { RendererTester } from '../rendererTypes.js';
import { RANK_STRUCTURAL_CONTROL } from '../rendererTypes.js';
import { dispatchRenderer, registerRenderer } from '../dispatch.js';

export class ArrayControl extends JsonFormsRendererBase {
  static styles = css`
    :host {
      display: block;
      margin-block-end: var(--justsearch-shell-form-control-spacing, 0.75rem);
    }
    .array-title {
      font-size: var(--justsearch-shell-form-section-title-size, 20px);
      font-variation-settings: "opsz" 32;
      font-weight: var(--justsearch-shell-form-section-title-weight, 600);
      margin-block-end: 0.5rem;
    }
    .array-items {
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
    }
    .array-item {
      display: flex;
      align-items: flex-start;
      gap: 0.5rem;
      padding: 0.5rem;
      border: 1px solid var(--justsearch-shell-form-nesting-border, #eee);
      border-radius: 4px;
    }
    .array-item-content {
      flex: 1;
    }
    button {
      padding: var(--justsearch-shell-form-button-padding, 0.25rem 0.5rem);
      font: inherit;
      font-size: var(--font-size-sm);
      border: 1px solid var(--justsearch-shell-form-button-border, #ccc);
      border-radius: 4px;
      background: var(--justsearch-shell-form-button-bg, transparent);
      cursor: pointer;
    }
    button:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }
    .add-btn {
      align-self: flex-start;
      margin-block-start: 0.5rem;
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

    const itemSchema =
      (this.schema as { items?: JsonSchema | JsonSchema[] }).items;

    if (!itemSchema || Array.isArray(itemSchema)) {
      // Tuple-typed schemas (items as array) deferred to a future phase.
      return html`<div class="error">
        Array control: tuple-typed item schemas not yet supported (got
        ${Array.isArray(itemSchema) ? 'array' : 'undefined'}).
      </div>`;
    }

    const items = Array.isArray(this.data) ? (this.data as unknown[]) : [];

    return html`
      ${titleText
        ? html`<div class="array-title">${titleText}</div>`
        : null}
      <div class="array-items">
        ${items.map((itemValue, index) =>
          this.renderItem(itemSchema, itemValue, index, items),
        )}
      </div>
      <button
        class="add-btn"
        ?disabled=${!this.enabled}
        @click=${() => this.handleAdd(itemSchema, items)}
      >
        + Add
      </button>
      ${this.errors ? html`<div class="error">${this.errors}</div>` : null}
    `;
  }

  private renderItem(
    itemSchema: JsonSchema,
    itemValue: unknown,
    index: number,
    items: ReadonlyArray<unknown>,
  ): TemplateResult {
    const itemUischema: UISchemaElement = {
      type: 'Control',
      scope: '#',
    } as UISchemaElement;

    const tag = dispatchRenderer(itemUischema, itemSchema);
    if (!tag) {
      return html`<div class="error">
        No renderer for item ${index} (schema: ${JSON.stringify(itemSchema)})
      </div>`;
    }

    const itemPath = this.path ? `${this.path}.${index}` : String(index);

    const child = document.createElement(tag) as JsonFormsRendererBase;
    child.schema = itemSchema;
    child.uischema = itemUischema;
    child.path = itemPath;
    child.data = itemValue;
    child.errors = '';
    child.enabled = this.enabled;
    child.visible = true;
    child.onChange = this.onChange;

    return html`
      <div class="array-item">
        <div class="array-item-content">${child}</div>
        <button
          ?disabled=${!this.enabled}
          @click=${() => this.handleRemove(index, items)}
        >
          Remove
        </button>
      </div>
    `;
  }

  private handleAdd(itemSchema: JsonSchema, items: ReadonlyArray<unknown>): void {
    if (!this.enabled) {
      return;
    }
    const next = [...items, this.defaultValueForSchema(itemSchema)];
    this.updateData(next);
  }

  private handleRemove(index: number, items: ReadonlyArray<unknown>): void {
    if (!this.enabled) {
      return;
    }
    const next = items.filter((_, i) => i !== index);
    this.updateData(next);
  }

  /**
   * Best-effort default value for a JSON Schema fragment. Used when
   * appending a new array item.
   */
  private defaultValueForSchema(schema: JsonSchema): unknown {
    const t = (schema as { type?: string }).type;
    switch (t) {
      case 'string':
        return '';
      case 'number':
      case 'integer':
        return 0;
      case 'boolean':
        return false;
      case 'array':
        return [];
      case 'object':
        return {};
      default:
        return null;
    }
  }
}

customElements.define('jf-array-control', ArrayControl);

export const arrayControlTester: RendererTester = (
  uischema: UISchemaElement,
  schema: JsonSchema,
): number => {
  if (uischema.type !== 'Control') {
    return -1;
  }
  if ((schema as { type?: string }).type !== 'array') {
    return -1;
  }
  return RANK_STRUCTURAL_CONTROL;
};

// Self-register into the dispatch store (cycle-free; tempdoc 530 UI-cycle gate).
registerRenderer(arrayControlTester, 'jf-array-control');
