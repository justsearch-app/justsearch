// SPDX-License-Identifier: Apache-2.0
import { html, css, type PropertyValues, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import type { OperationOutcomeView } from '../../api/generated/schema-types/index.js';
import { OPERATION_KEY_PATTERN, readOperationReceipt } from '../operations/operationReceipt.js';

/** Follows a recorded operation through acceptance to its retained outcome details. */
export class OperationOutcomeDetail extends JfElement {
  static override properties = {
    operationKey: { type: String, attribute: 'operation-key' },
    apiBase: { type: String, attribute: 'api-base' },
    outcome: { state: true },
  };

  declare operationKey: string;
  declare apiBase: string;
  declare outcome: OperationOutcomeView | null;
  private requestSerial = 0;
  private pollTimer: ReturnType<typeof setTimeout> | undefined;

  constructor() {
    super();
    this.operationKey = '';
    this.apiBase = '';
    this.outcome = null;
  }

  static styles = css`
    :host { display: block; white-space: normal; overflow-wrap: anywhere; }
    small { display: block; color: var(--text-secondary); }
  `;

  protected override updated(changed: PropertyValues): void {
    if (changed.has('operationKey') || changed.has('apiBase')) {
      this.outcome = null;
      void this.load();
    }
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.requestSerial++;
    clearTimeout(this.pollTimer);
  }

  protected override onReveal(): void { void this.load(); }

  private async load(): Promise<void> {
    const serial = ++this.requestSerial;
    clearTimeout(this.pollTimer);
    if (!OPERATION_KEY_PATTERN.test(this.operationKey)) return;
    let pending = true;
    try {
      const outcome = await readOperationReceipt(this.apiBase, this.operationKey);
      if (serial !== this.requestSerial) return;
      pending = ['accepted', 'running'].includes(outcome.state);
      this.outcome = outcome.state === 'failed' ? outcome : null;
    } catch {
      // A refused or failed read, or a restart briefly interrupting reads: the operation may continue.
    } finally {
      if (pending && serial === this.requestSerial && this.isConnected) {
        this.pollTimer = setTimeout(() => void this.load(), 4_000);
      }
    }
  }

  override render(): TemplateResult {
    const outcome = this.outcome;
    const code = outcome?.result?.code ?? outcome?.reason;
    return html`${outcome?.message ? html`<div role="alert">${outcome.message}</div>` : ''}
      ${code && !outcome?.message ? html`<small>Code: ${code}</small>` : ''}`;
  }
}

if (!customElements.get('jf-operation-outcome-detail')) {
  customElements.define('jf-operation-outcome-detail', OperationOutcomeDetail);
}
