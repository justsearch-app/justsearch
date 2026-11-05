// SPDX-License-Identifier: Apache-2.0
/**
 * ActionButton — invokes a server-side Operation by id.
 *
 * Per slice 3a.1 §"Slice 3a.1" 4-renderer scope: surfaces an
 * Operation invocation point. The component holds the Operation
 * id + the policy axes (risk, requires-confirmation,
 * requires-double-confirmation) and emits an `action-invoke`
 * CustomEvent when the user activates the button. The shell host
 * is responsible for actually issuing the API call (the component
 * doesn't fetch directly — that keeps it free of API-base coupling
 * and testable in isolation).
 *
 * Risk-driven UI:
 *   - LOW: button click immediately fires `action-invoke`.
 *   - MEDIUM (INLINE): button click → the in-place confirmation band
 *     (`<jf-confirm-band>`: consequence sentence, verb button, Keep it)
 *     → fires on the verb.
 *   - HIGH (TYPED): button click → typed-confirmation form (user types
 *     the operation id) → fires on match.
 *
 * The HIGH ceremony is deliberately in-component for V0; later
 * slices can replace it with a richer dialog primitive when one
 * exists.
 *
 * Usage:
 *   <jf-action-button
 *     operation-id="indexing.reset"
 *     label="Reset Index"
 *     risk="HIGH"
 *     @action-invoke=${(e) => api.invoke(e.detail.operationId)}
 *   ></jf-action-button>
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { operationConsequence } from './operationConsequence.js';
import './ConfirmBand.js';

export type Risk = 'LOW' | 'MEDIUM' | 'HIGH';

/**
 * Tempdoc 511 Phase 0 (2026-05-18): the component previously
 * derived ceremony from `risk` alone, ignoring the wire's
 * `policy.confirm.kind`. That left wire shapes like
 * `{risk: 'LOW', confirm: {kind: 'TYPED'}}` clickable without any
 * ceremony. The `confirm` prop now drives ceremony first-class;
 * when unset, behavior falls back to the risk-derived ceremony for
 * backwards compatibility with existing callsites.
 */
export type ConfirmKind = 'NONE' | 'INLINE' | 'TYPED';

export interface ActionInvokeEventDetail {
  operationId: string;
  risk: Risk;
}

export class ActionButton extends JfElement {
  static override properties = {
    operationId: { type: String, attribute: 'operation-id' },
    label: { type: String },
    risk: { type: String },
    confirmKind: { type: String, attribute: 'confirm-kind' },
    disabled: { type: Boolean },
    pending: { type: Boolean },
    requireConfirmText: { type: String, attribute: 'require-confirm-text' },
    confirmStage: { state: true },
    typedConfirm: { state: true },
  } as const;

  declare operationId: string;
  declare label: string;
  declare risk: Risk;
  /**
   * Optional explicit confirm-ceremony selector. When unset (default),
   * ceremony derives from `risk` (legacy behavior). When set, the
   * specified ceremony is used regardless of risk. Driven by the
   * wire's `policy.confirm.kind` via the catalog-resolution path.
   */
  declare confirmKind: ConfirmKind | '';
  declare disabled: boolean;
  declare pending: boolean;
  declare requireConfirmText: string;
  declare confirmStage: 'idle' | 'awaiting-confirm' | 'awaiting-typed';
  declare typedConfirm: string;

  constructor() {
    super();
    this.operationId = '';
    this.label = 'Action';
    this.risk = 'LOW';
    this.confirmKind = '';
    this.disabled = false;
    this.pending = false;
    this.requireConfirmText = '';
    this.confirmStage = 'idle';
    this.typedConfirm = '';
  }

  static styles = css`
    :host {
      display: inline-block;
      margin-block-end: var(--justsearch-shell-form-control-spacing, 0.75rem);
    }
    button.invoke {
      padding: var(--justsearch-shell-action-button-padding, 0.5rem 1rem);
      border: 1px solid var(--justsearch-shell-form-button-border, #ccc);
      border-radius: var(--justsearch-shell-form-input-radius, 4px);
      background: var(--justsearch-shell-form-button-bg, transparent);
      cursor: pointer;
      font: inherit;
      color: inherit;
    }
    button.invoke:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }
    button.invoke[data-risk='HIGH'] {
      border-color: var(--justsearch-shell-status-severity-error, #c00);
      color: var(--justsearch-shell-status-severity-error, #c00);
    }
    button.invoke[data-risk='MEDIUM'] {
      border-color: var(--justsearch-shell-status-severity-warning, #d97706);
    }
    .confirm-row {
      display: flex;
      gap: 0.5rem;
      margin-block-start: 0.5rem;
      align-items: center;
    }
    input.typed-confirm {
      padding: var(--justsearch-shell-form-input-padding, 0.5rem);
      border: 1px solid var(--justsearch-shell-form-input-border, #ccc);
      border-radius: var(--justsearch-shell-form-input-radius, 4px);
      font: inherit;
    }
  `;

  override render(): TemplateResult {
    // One stable invoke button: a stage change only adds the confirmation after it, so the control
    // that opened a confirmation is still there to take focus back when it is cancelled.
    const expected = this.requireConfirmText || this.operationId;
    const matches = this.typedConfirm === expected;
    return html`
      <button
        class="invoke"
        data-risk=${this.risk}
        aria-expanded=${this.confirmStage === 'idle' ? nothing : 'true'}
        ?disabled=${this.disabled || this.pending}
        @click=${this.handleInitialClick}
      >
        ${this.label}
      </button>
      ${this.confirmStage === 'awaiting-typed' ? html`
        <div class="confirm-row">
          <span>Type <code>${expected}</code> to confirm:</span>
          <input
            class="typed-confirm"
            .value=${this.typedConfirm}
            @input=${(e: Event) => {
              this.typedConfirm = (e.target as HTMLInputElement).value;
            }}
          />
          <button
            class="invoke"
            data-risk=${this.risk}
            ?disabled=${!matches || this.disabled || this.pending}
            @click=${this.fireInvoke}
          >
            ${this.label}
          </button>
          <button class="invoke" @click=${this.cancelConfirm}>Cancel</button>
        </div>` : nothing}
      ${this.confirmStage === 'awaiting-confirm' ? html`
        <jf-confirm-band
          consequence=${operationConsequence(this.operationId, this.label)}
          action-label=${this.label}
          ?busy=${this.disabled || this.pending}
          @confirm-band-confirm=${this.fireInvoke}
          @confirm-band-cancel=${this.cancelConfirm}
        ></jf-confirm-band>` : nothing}
    `;
  }

  /**
   * Resolve the ceremony kind to apply on the initial click. When
   * `confirmKind` is set, it wins outright (the wire's
   * `policy.confirm.kind` is the authoritative signal). When unset,
   * fall back to the legacy risk-derived ceremony so existing
   * callsites that only pass `risk` continue to behave as before.
   */
  private resolvedConfirmKind(): ConfirmKind {
    if (this.confirmKind === 'NONE' || this.confirmKind === 'INLINE' || this.confirmKind === 'TYPED') {
      return this.confirmKind;
    }
    if (this.risk === 'HIGH') return 'TYPED';
    if (this.risk === 'MEDIUM') return 'INLINE';
    return 'NONE';
  }

  private readonly handleInitialClick = (): void => {
    if (this.disabled || this.pending || this.confirmStage !== 'idle') {
      return;
    }
    const kind = this.resolvedConfirmKind();
    if (kind === 'TYPED') {
      this.confirmStage = 'awaiting-typed';
      this.typedConfirm = '';
      return;
    }
    if (kind === 'INLINE') {
      this.confirmStage = 'awaiting-confirm';
      return;
    }
    this.fireInvoke();
  };

  private readonly fireInvoke = (): void => {
    if (this.disabled || this.pending) {
      return;
    }
    this.dispatchEvent(
      new CustomEvent<ActionInvokeEventDetail>('action-invoke', {
        detail: { operationId: this.operationId, risk: this.risk },
        bubbles: true,
        composed: true,
      }),
    );
    this.confirmStage = 'idle';
    this.typedConfirm = '';
  };

  private readonly cancelConfirm = (): void => {
    this.confirmStage = 'idle';
    this.typedConfirm = '';
    this.shadowRoot?.querySelector<HTMLButtonElement>('button.invoke')?.focus();
  };
}

customElements.define('jf-action-button', ActionButton);
