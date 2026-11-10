// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 491 §9.D Phase E (C2) — `<jf-navigation-receipt>` Lit block.
 *
 * Renders the three `navigate.url_*` SSE events emitted by
 * `core.navigate-chat` (and, after C4, by `core.agent-run`):
 *
 *   - `navigate.url_extracted` → "URL extracted: <target>" (transitional)
 *   - `navigate.url_dispatched` → "Navigated to <target>" (final success)
 *   - `navigate.url_rejected` → "Rejected: <reasonCode> — <message>" (final
 *     failure; reasonCode commonly `confirmation-required`,
 *     `trust-gate-denied`, `dispatch-failed` per `URLExtractor.java`).
 *
 * The actual chrome-level navigation is performed by `bootIntentStreamBridge`
 * (slice 492) consuming the same envelope from `/api/intent/stream`. The
 * NavigationReceipt block only RENDERS what happened; it doesn't dispatch.
 */

import { html, css, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';

export type NavigationOutcome = 'extracted' | 'dispatched' | 'forwarded' | 'rejected' | 'unresolved' | 'auto-corrected';

export interface ReceiptSuggestion {
  readonly id: string;
  readonly label: string;
}

export class NavigationReceipt extends JfElement {
  static properties = {
    outcome: { type: String, reflect: true },
    target: { type: String },
    addressKind: { type: String, attribute: 'address-kind' },
    reasonCode: { type: String, attribute: 'reason-code' },
    correctedFrom: { type: String, attribute: 'corrected-from' },
    message: { type: String },
    suggestions: { type: Array },
  };

  declare outcome: NavigationOutcome;
  declare target: string;
  declare addressKind: 'navigate' | 'invoke' | '';
  declare reasonCode: string;
  declare message: string;
  declare correctedFrom: string;
  declare suggestions: ReceiptSuggestion[];

  constructor() {
    super();
    this.outcome = 'extracted';
    this.target = '';
    this.addressKind = '';
    this.reasonCode = '';
    this.message = '';
    this.correctedFrom = '';
    this.suggestions = [];
  }

  static styles = css`
    :host {
      display: block;
      margin: 0.5rem 0;
    }
    .receipt {
      display: inline-flex;
      align-items: center;
      gap: 0.5rem;
      padding: 0.5rem 0.75rem;
      border-radius: 0.375rem;
      font-family: ui-monospace, monospace;
      font-size: var(--font-size-sm);
      background: var(--surface-2);
      border: 1px solid var(--border-subtle);
      color: var(--text-secondary);
    }
    :host([outcome='dispatched']) .receipt,
    :host([outcome='forwarded']) .receipt {
      background: var(--accent-tint-08);
      border-color: var(--accent-tint-30);
      color: var(--text-tint);
    }
    :host([outcome='rejected']) .receipt {
      background: var(--accent-danger-08);
      border-color: var(--accent-danger-30);
      color: var(--text-danger);
    }
    :host([outcome='unresolved']) .receipt {
      background: var(--accent-warning-08);
      border-color: var(--accent-warning-30);
      color: var(--text-warning);
    }
    :host([outcome='auto-corrected']) .receipt {
      background: var(--accent-tint-08);
      border-color: var(--accent-tint-30);
      color: var(--text-tint);
    }
    .corrected-from {
      text-decoration: line-through;
      opacity: 0.6;
      margin-right: 0.25rem;
    }
    .glyph {
      font-weight: bold;
      font-size: var(--font-size-sm);
    }
    .target {
      color: var(--text-primary);
      font-weight: 500;
    }
    .reason {
      opacity: 0.85;
    }
    .suggestions {
      display: flex;
      align-items: center;
      gap: 0.375rem;
      margin-top: 0.375rem;
      font-size: var(--font-size-xs);
    }
    .suggestions-label {
      opacity: 0.7;
    }
    .suggestion-chip {
      display: inline-block;
      padding: 0.2rem 0.5rem;
      border-radius: 0.25rem;
      border: 1px solid var(--accent-tint-45);
      background: var(--accent-tint-08);
      color: var(--text-tint);
      cursor: pointer;
      font-family: ui-monospace, monospace;
      font-size: var(--font-size-xs);
      transition: background var(--duration-fast);
    }
    .suggestion-chip:hover {
      background: var(--accent-tint-16);
    }
    .suggestion-chip:focus-visible {
      outline: 2px solid var(--focus-ring-color);
      outline-offset: 1px;
    }
  `;

  override render(): TemplateResult {
    if (!this.target && !this.message) {
      return html``;
    }
    const glyph = this.glyphFor();
    const verb = this.verbFor();
    const targetLabel = this.target || '<unknown>';
    return html`<div class="receipt">
      <span class="glyph">${glyph}</span>
      <span>${verb}</span>
      ${this.outcome === 'auto-corrected' && this.correctedFrom
        ? html`<span class="corrected-from">${this.correctedFrom}</span>`
        : ''}
      <span class="target">${targetLabel}</span>
      ${this.reasonCode || this.message
        ? html`<span class="reason">— ${this.reasonCode}${this.message ? ': ' + this.message : ''}</span>`
        : ''}
    </div>
    ${this.outcome === 'unresolved' && this.suggestions.length > 0
      ? html`<div class="suggestions">
          <span class="suggestions-label">Did you mean:</span>
          ${this.suggestions.map(s => html`<button
            class="suggestion-chip"
            aria-label="Navigate to ${s.label || s.id}"
            @click=${() => this.dispatchEvent(new CustomEvent('suggestion-click', {
              detail: { id: s.id, addressKind: this.addressKind },
              bubbles: true, composed: true,
            }))}
          >${s.label || s.id}</button>`)}
        </div>`
      : ''}`;
  }

  private glyphFor(): string {
    switch (this.outcome) {
      case 'dispatched':
      case 'forwarded':
        return '✓';
      case 'rejected':
        return '✗';
      case 'unresolved':
        return '?';
      case 'auto-corrected':
      case 'extracted':
      default:
        return '→';
    }
  }

  private verbFor(): string {
    const isInvoke = this.addressKind === 'invoke';
    switch (this.outcome) {
      case 'dispatched':
      case 'forwarded':
        return isInvoke ? 'Invoked' : 'Navigated to';
      case 'rejected':
        return isInvoke ? 'Invocation rejected' : 'Navigation rejected';
      case 'unresolved':
        return isInvoke ? 'Unknown operation' : 'Unknown surface';
      case 'auto-corrected':
        return 'Corrected to';
      case 'extracted':
      default:
        return isInvoke ? 'Invocation extracted' : 'URL extracted';
    }
  }
}

if (
  typeof customElements !== 'undefined' &&
  !customElements.get('jf-navigation-receipt')
) {
  customElements.define('jf-navigation-receipt', NavigationReceipt);
}
