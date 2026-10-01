// SPDX-License-Identifier: Apache-2.0
/**
 * StatusCard — generic labeled-status renderer.
 *
 * Per slice 3a.1 §"Slice 3a.1" 4-renderer scope: the StatusCard is
 * the substrate's primary HealthEvent Condition surface. Reads
 * `severity` / `reason` / `i18nKey` and renders a labeled card.
 *
 * Severity drives the visual treatment via CSS custom properties
 * (`--justsearch-shell-status-severity-info` etc.). Phase 3 catalog
 * doesn't yet define these; Phase 4a adds the status-domain tokens
 * to `default.css` alongside this component.
 *
 * Usage:
 *   <jf-status-card
 *     severity="WARNING"
 *     subject="WorkerHandshake"
 *     reason="WorkerOffline"
 *     i18nKey="health.workerHandshake.offline"
 *     details="index component did not respond within 5s."
 *   ></jf-status-card>
 */

import { html, css, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';

export type StatusCardSeverity = 'INFO' | 'WARNING' | 'ERROR' | 'UNKNOWN';

export class StatusCard extends JfElement {
  static override properties = {
    // Reflect drives :host([severity=...]) selectors that change the
    // border colour per severity. Without reflect, the attribute is
    // only set when explicitly authored in HTML, not when set as a
    // JS property.
    severity: { type: String, reflect: true },
    subject: { type: String },
    reason: { type: String },
    i18nKey: { type: String, attribute: 'i18n-key' },
    details: { type: String },
  } as const;

  declare severity: StatusCardSeverity;
  declare subject: string;
  declare reason: string;
  declare i18nKey: string;
  declare details: string;

  constructor() {
    super();
    this.severity = 'UNKNOWN';
    this.subject = '';
    this.reason = '';
    this.i18nKey = '';
    this.details = '';
  }

  static styles = css`
    :host {
      display: block;
      padding: var(--justsearch-shell-status-card-padding, 0.75rem);
      border: 1px solid
        var(--justsearch-shell-status-card-border, #ccc);
      border-radius: var(--justsearch-shell-status-card-radius, 4px);
      margin-block-end: var(--justsearch-shell-form-control-spacing, 0.75rem);
      background: var(--justsearch-shell-status-card-bg, transparent);
    }
    :host([severity='INFO']) {
      border-color: var(--justsearch-shell-status-severity-info, #3a8dde);
    }
    :host([severity='WARNING']) {
      border-color: var(--justsearch-shell-status-severity-warning, #d97706);
    }
    :host([severity='ERROR']) {
      border-color: var(--justsearch-shell-status-severity-error, #c00);
    }
    :host([severity='UNKNOWN']) {
      border-color: var(--justsearch-shell-status-severity-unknown, #888);
    }
    .header {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      font-weight: var(--justsearch-shell-status-header-weight, 600);
      font-size: var(--justsearch-shell-status-header-size, 0.9375rem);
    }
    .severity-badge {
      font-size: var(--font-size-xs);
      text-transform: uppercase;
      padding: 0.125rem 0.4rem;
      border-radius: 3px;
      background: var(--justsearch-shell-status-badge-bg, #f3f3f3);
    }
    .reason {
      margin-block-start: 0.25rem;
      font-size: var(--font-size-sm);
      color: var(--justsearch-shell-status-reason-color, inherit);
    }
    .details {
      margin-block-start: 0.5rem;
      font-size: var(--font-size-sm);
      color: var(--justsearch-shell-status-details-color, #555);
      white-space: pre-wrap;
    }
  `;

  override render(): TemplateResult {
    return html`
      <div class="header">
        <span class="severity-badge" aria-label="Severity">
          ${this.severity}
        </span>
        ${this.subject ? html`<span>${this.subject}</span>` : null}
      </div>
      ${this.reason ? html`<div class="reason">${this.reason}</div>` : null}
      ${this.details
        ? html`<div class="details">${this.details}</div>`
        : null}
    `;
  }
}

customElements.define('jf-status-card', StatusCard);
