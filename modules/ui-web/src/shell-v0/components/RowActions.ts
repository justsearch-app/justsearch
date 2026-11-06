// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 3a.1.9 §A.7 — `<jf-row-actions>` element.
 *
 * Reads `itemOperations` from a Resource's catalog declaration and
 * renders one `<jf-action-button>` per Operation. Used by
 * `<jf-resource-view>` (TABULAR Category) as the per-row trailing
 * actions cell.
 *
 * Host responsibilities:
 *  - Set `resource-id` (looks up the Resource).
 *  - Set `row-key` (primary-key value, forwarded as Operation args).
 *  - Set `row` (full row data, available to consumer extensions).
 *
 * The element:
 *  - Looks up the Resource from `ResourceCatalogClient`.
 *  - For each `itemOperations` id, looks up the Operation in the
 *    `OperationCatalogClient` to localize its label + read its risk
 *    tier for ActionButton's confirmation UX.
 *  - Wires `action-invoke` → `OperationClient.invoke(opId, {args:
 *    {[primaryKey]: rowKey}})`. Forwards success / error events for
 *    the host to observe.
 *
 * Args convention: the Resource's primary-key field name (e.g.,
 * `pathHash` for `core.indexing-jobs`) becomes the argument name.
 * If a future Operation expects different argument names, the
 * registration site can override via a per-Operation argsBuilder
 * extension (not in V1).
 */

import { html, css, type TemplateResult, nothing } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { OperationClient, getOperationClient } from '../operations/OperationClient.js';
import {
  getOperation,
  onCatalogChange as onOperationCatalogChange,
} from '../../api/registry/OperationCatalogClient.js';
import {
  getResource,
  onCatalogChange as onResourceCatalogChange,
} from '../../api/registry/ResourceCatalogClient.js';
import { present } from '../display/present.js';
import './ActionButton.js';
import type { ActionInvokeEventDetail } from './ActionButton.js';

export class RowActions extends JfElement {
  static override properties = {
    resourceId: { type: String, attribute: 'resource-id' },
    rowKey: { type: String, attribute: 'row-key' },
    row: { attribute: false },
    apiBase: { type: String, attribute: 'api-base' },
  } as const;

  declare resourceId: string;
  declare rowKey: string;
  declare row: Record<string, unknown> | undefined;
  declare apiBase: string;

  private clientRef: OperationClient | null = null;
  /**
   * Catalog-arrival listeners for the first-visit boot race (slice
   * 3a.2 closure §B.B.E.1). RowActions silently returns `nothing`
   * when getResource/getOperation lookups miss, so without these
   * subscriptions a row mounted before the catalogs finish booting
   * stays empty until some unrelated property change forces a
   * re-render. Subscribed in connectedCallback, cleared in
   * disconnectedCallback.
   */
  private resourceCatalogUnsubscribe: (() => void) | null = null;
  private operationCatalogUnsubscribe: (() => void) | null = null;

  constructor() {
    super();
    this.resourceId = '';
    this.rowKey = '';
    this.row = undefined;
    this.apiBase = '';
  }

  override connectedCallback(): void {
    super.connectedCallback();
    this.resourceCatalogUnsubscribe = onResourceCatalogChange(() => {
      this.requestUpdate();
    });
    this.operationCatalogUnsubscribe = onOperationCatalogChange(() => {
      this.requestUpdate();
    });
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    if (this.resourceCatalogUnsubscribe) {
      this.resourceCatalogUnsubscribe();
      this.resourceCatalogUnsubscribe = null;
    }
    if (this.operationCatalogUnsubscribe) {
      this.operationCatalogUnsubscribe();
      this.operationCatalogUnsubscribe = null;
    }
  }

  static styles = css`
    /**
     * Tempdoc 914 D2 — the button strip WRAPS. Each jf-action-button renders its operation's
     * full label ("Retry indexing job"), which does not shrink, so in a narrow host (the 26rem
     * failed-files list, a narrow table cell) a non-wrapping strip either overflows or starves
     * the neighbouring content column of its width.
     */
    :host {
      display: inline-flex;
      flex-wrap: wrap;
      gap: 0.25rem;
      align-items: center;
    }
  `;

  private client(): OperationClient {
    if (!this.clientRef) {
      const apiBase =
        this.apiBase ||
        (typeof globalThis !== 'undefined' &&
        (globalThis as { location?: { origin?: string } }).location?.origin
          ? (globalThis as { location: { origin: string } }).location.origin
          : '');
      this.clientRef = getOperationClient(apiBase);
    }
    return this.clientRef;
  }

  override render(): TemplateResult | typeof nothing {
    if (!this.resourceId) return nothing;
    const resource = getResource(this.resourceId);
    if (!resource) return nothing;
    const ops = resource.itemOperations;
    if (!ops || ops.length === 0) return nothing;
    const argName = resource.primaryKey || 'id';
    return html`${ops.map((opId) => this.renderActionButton(opId, argName))}`;
  }

  private renderActionButton(
    operationId: string,
    argName: string,
  ): TemplateResult {
    const op = getOperation(operationId);
    const label = present({ kind: 'operation', id: operationId }).label;
    const risk = op?.policy.risk ?? 'LOW';
    return html`
      <jf-action-button
        operation-id=${operationId}
        label=${label}
        risk=${risk}
        @action-invoke=${(e: CustomEvent<ActionInvokeEventDetail>) =>
          this.handleInvoke(operationId, argName, e)}
      ></jf-action-button>
    `;
  }

  private async handleInvoke(
    operationId: string,
    argName: string,
    event: CustomEvent<ActionInvokeEventDetail>,
  ): Promise<void> {
    event.stopPropagation();
    const button = event.target as { pending?: boolean } | null;
    if (button) button.pending = true;
    try {
      const result = await this.client().invoke(operationId, {
        args: { [argName]: this.rowKey },
      });
      this.dispatchEvent(
        new CustomEvent('row-action-success', {
          detail: { operationId, rowKey: this.rowKey, result },
          bubbles: true,
          composed: true,
        }),
      );
    } catch (err) {
      this.dispatchEvent(
        new CustomEvent('row-action-error', {
          detail: { operationId, rowKey: this.rowKey, error: err },
          bubbles: true,
          composed: true,
        }),
      );
    } finally {
      if (button) button.pending = false;
    }
  }
}

customElements.define('jf-row-actions', RowActions);
