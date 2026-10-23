// SPDX-License-Identifier: Apache-2.0
/**
 * OpButton — catalog-driven operation button (tempdoc 509 Phase 2).
 *
 * Accepts only an operation ID. Resolves label, risk, and confirmation
 * strategy from the OperationCatalog + i18n catalog. Wraps
 * `<jf-action-button>` for the risk-driven confirmation UX and
 * `OperationClient` for dispatch.
 *
 * There is no `label` prop — the label comes from the catalog, always.
 * This makes the hardcoded-label path structurally unreachable.
 *
 * Usage:
 *   <jf-op-button
 *     operation-id="core.reindex"
 *     api-base=${this.apiBase}
 *     @op-success=${() => this.refresh()}
 *     @op-error=${(e) => this.showError(e.detail)}
 *   ></jf-op-button>
 */

import { html, css, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import {
  getOperation,
  onCatalogChange,
} from '../../api/registry/OperationCatalogClient.js';
import { OperationClient, OperationError, getOperationClient } from '../operations/OperationClient.js';
import { present } from '../display/present.js';
import type {
  ActionInvokeEventDetail,
  ConfirmKind,
  Risk,
} from './ActionButton.js';
import './ActionButton.js';

/**
 * Detail shape for the `op-success` CustomEvent OpButton dispatches
 * after a successful catalog operation. Tempdoc 511 §511-followup-D
 * exports this so surfaces can declare typed handlers (`(e:
 * CustomEvent<OpSuccessEventDetail>) => ...`) instead of casting
 * `e.detail` ad hoc — a misspelled field would compile cleanly
 * otherwise.
 *
 * Fields mirror `OperationResult` on the wire: `message` is the
 * human-readable summary; `executionId` correlates with audit logs;
 * `structuredData` is the handler's structured output (e.g.
 * `{path: '...'}` from `core.export-diagnostics`).
 */
export interface OpSuccessEventDetail {
  message?: string;
  executionId?: string;
  structuredData?: Record<string, unknown>;
}

/**
 * Detail shape for the `op-error` CustomEvent OpButton dispatches
 * when an `OperationError` is thrown. Tempdoc 511 §511-followup-D —
 * same rationale as {@link OpSuccessEventDetail}.
 */
export interface OpErrorEventDetail {
  message: string;
  errorClass: string;
  errorCode?: string;
}

function deriveLabel(operationId: string): string {
  // §2.A: the operation-label logic now lives in the one display projector.
  return present({ kind: 'operation', id: operationId }).label;
}

function deriveRisk(operationId: string): Risk {
  const op = getOperation(operationId);
  return (op?.policy?.risk as Risk) ?? 'LOW';
}

/**
 * Tempdoc 511-followup Track B: explicit confirm-kind override.
 *
 * If the caller passes a `confirm-kind` attribute (typically from
 * the (Operation, button) aggregate strategy, which reads
 * `op.policy.confirm.kind` directly from the wire), use it; the
 * wire's declared ceremony wins. Otherwise derive from the
 * operation's catalog-resolved confirm shape — `INLINE` when typed,
 * `INLINE` when inline, `NONE` otherwise.
 *
 * The legacy risk-as-proxy-for-ceremony fallback in ActionButton
 * is preserved for any callsite that passes neither confirm-kind
 * nor a usable operation id.
 */
function deriveConfirmKind(
  operationId: string,
  override: ConfirmKind | '',
): ConfirmKind | '' {
  if (override === 'NONE' || override === 'INLINE' || override === 'TYPED') {
    return override;
  }
  const op = getOperation(operationId);
  const kind = op?.policy?.confirm?.kind;
  if (kind === 'NONE' || kind === 'INLINE' || kind === 'TYPED') {
    return kind;
  }
  return '';
}

export class OpButton extends JfElement {
  static override properties = {
    operationId: { type: String, attribute: 'operation-id' },
    apiBase: { type: String, attribute: 'api-base' },
    confirmKind: { type: String, attribute: 'confirm-kind' },
    args: { attribute: false },
    disabled: { type: Boolean },
  } as const;

  declare operationId: string;
  declare apiBase: string;
  declare confirmKind: ConfirmKind | '';
  declare args: Record<string, unknown>;
  declare disabled: boolean;

  private catalogUnsub: (() => void) | null = null;
  private client: OperationClient | null = null;
  private pending = false;

  constructor() {
    super();
    this.operationId = '';
    this.apiBase = '';
    this.confirmKind = '';
    this.args = {};
    this.disabled = false;
  }

  static styles = css`
    :host { display: inline-block; }
  `;

  override connectedCallback(): void {
    super.connectedCallback();
    this.catalogUnsub = onCatalogChange(() => this.requestUpdate());
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.catalogUnsub?.();
    this.catalogUnsub = null;
  }

  private getClient(): OperationClient {
    if (!this.client) {
      this.client = getOperationClient(this.apiBase || globalThis.location?.origin || '');
    }
    return this.client;
  }

  private async handleInvoke(e: Event): Promise<void> {
    const detail = (e as CustomEvent<ActionInvokeEventDetail>).detail;
    if (!detail?.operationId) return;
    this.pending = true;
    this.requestUpdate();
    try {
      // Tempdoc 550 C3: HIGH-risk hits a TYPED_CONFIRM gate. The in-component typed-confirm
      // UX already ran (the consent); invokeWithConsent invokes, and on the backend's 428
      // approves the backend-issued pending by id and re-invokes with the capsule. The FE
      // never mints for an arbitrary op — only approves what the backend gated.
      const result = await this.getClient().invokeWithConsent(
        detail.operationId,
        { args: this.args },
        { consented: detail.risk === 'HIGH' },
      );
      this.dispatchEvent(
        new CustomEvent<OpSuccessEventDetail>('op-success', {
          detail: {
            message: result.message,
            executionId: result.executionId,
            structuredData: result.structuredData,
          },
          bubbles: true,
          composed: true,
        }),
      );
    } catch (err) {
      if (err instanceof OperationError) {
        this.dispatchEvent(
          new CustomEvent<OpErrorEventDetail>('op-error', {
            detail: { message: err.message, errorClass: err.errorClass,
              ...(err.errorCode ? { errorCode: err.errorCode } : {}) },
            bubbles: true,
            composed: true,
          }),
        );
      } else {
        throw err;
      }
    } finally {
      this.pending = false;
      this.requestUpdate();
    }
  }

  override render(): TemplateResult {
    const label = deriveLabel(this.operationId);
    const risk = deriveRisk(this.operationId);
    const confirmKind = deriveConfirmKind(this.operationId, this.confirmKind);
    return html`
      <jf-action-button
        operation-id=${this.operationId}
        label=${label}
        risk=${risk}
        confirm-kind=${confirmKind}
        ?disabled=${this.disabled || this.pending}
        ?pending=${this.pending}
        data-hover-aggregate-kind="Operation"
        data-hover-aggregate-id=${this.operationId}
        @action-invoke=${(e: Event) => void this.handleInvoke(e)}
      ></jf-action-button>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-op-button')) {
  customElements.define('jf-op-button', OpButton);
}
