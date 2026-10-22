// SPDX-License-Identifier: Apache-2.0
import { html, css, type PropertyValues, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { authorizedFetch } from '../api/authorizedFetch.js';
import { operationOutcomeViewSchema, type OperationOutcomeView } from '../../api/generated/schema-types/index.js';
import { parseWireContract } from '../../api/schemas.js';

/** Reads retained failure details by the ledger's committed operation key; no journal migration. */
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
    if (changed.has('operationKey') || changed.has('apiBase')) void this.load();
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.requestSerial++;
  }

  protected override onReveal(): void { void this.load(); }

  private async load(): Promise<void> {
    const serial = ++this.requestSerial;
    this.outcome = null;
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(this.operationKey)) return;
    try {
      const response = await authorizedFetch(`${this.apiBase.replace(/\/$/, '')}/api/operation-history/${encodeURIComponent(this.operationKey)}`);
      if (!response.ok) return;
      const outcome = parseWireContract(operationOutcomeViewSchema, await response.json(), 'operation outcome');
      if (serial === this.requestSerial && outcome.state === 'failed') this.outcome = outcome;
    } catch {
      // The ledger still shows its committed failure when history is unavailable or expired.
    }
  }

  override render(): TemplateResult {
    const outcome = this.outcome;
    return html`${outcome?.message ?? ''}
      ${outcome?.result?.code ? html`<small>Code: ${outcome.result.code}</small>` : ''}`;
  }
}

if (!customElements.get('jf-operation-outcome-detail')) {
  customElements.define('jf-operation-outcome-detail', OperationOutcomeDetail);
}
