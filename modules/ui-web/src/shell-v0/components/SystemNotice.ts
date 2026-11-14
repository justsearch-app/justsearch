// SPDX-License-Identifier: Apache-2.0
/**
 * SystemNotice — tempdoc 559 Authority III follow-up (the notice-presentation authority).
 *
 * THE single owner of how a *system notice* looks (severity tone) and announces
 * (ARIA live region). Before this, the toast host and the search degradation
 * banner each hand-rolled their own tone CSS + role/aria-live, so a warning
 * toast and a warning banner could drift apart. Both now render their notice
 * shell through `<jf-system-notice tone live>`:
 *   - `tone` → the left-accent color token (the ONE severity→token map).
 *   - `live` → role + aria-live (status/polite, alert/assertive, off).
 * Content is the default slot; the consumer owns its inner layout + any
 * interaction chrome (the toast's animation/dismiss/action stay on its wrapper;
 * the banner keeps its inline margin). This is the presentation seam — it does
 * NOT own message lifecycle (that's the AdvisoryStore) or state (that's 557).
 */
import { html, css, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';

export type NoticeTone = 'neutral' | 'info' | 'success' | 'warning' | 'error';
export type NoticeLive = 'status' | 'alert' | 'off';

export class SystemNotice extends JfElement {
  static properties = {
    tone: { type: String, reflect: true },
    live: { type: String, reflect: true },
  };

  declare tone: NoticeTone;
  declare live: NoticeLive;

  constructor() {
    super();
    this.tone = 'neutral';
    this.live = 'status';
  }

  static styles = css`
    :host {
      display: block;
      box-sizing: border-box;
      padding: 0.625rem 0.875rem;
      background: var(--surface-2);
      border: 1px solid var(--border-subtle);
      /* The ONE place a notice's severity becomes a color (left accent). */
      border-left: 3px solid var(--jf-notice-accent, var(--border-subtle));
      border-radius: 0.5rem;
      color: var(--text-primary);
    }
    :host([tone='info']) {
      --jf-notice-accent: var(--text-secondary);
    }
    :host([tone='success']) {
      --jf-notice-accent: var(--accent-success);
    }
    :host([tone='warning']) {
      --jf-notice-accent: var(--accent-warning);
    }
    :host([tone='error']) {
      --jf-notice-accent: var(--accent-danger);
    }
    /* tone='neutral' falls back to --border-subtle via the var default. */
  `;

  /** Drive the ARIA live region from `live` (role + aria-live are not CSS-settable). */
  private applyLive(): void {
    if (this.live === 'off') {
      this.removeAttribute('role');
      this.setAttribute('aria-live', 'off');
      return;
    }
    this.setAttribute('role', this.live === 'alert' ? 'alert' : 'status');
    this.setAttribute('aria-live', this.live === 'alert' ? 'assertive' : 'polite');
  }

  override connectedCallback(): void {
    super.connectedCallback();
    this.applyLive();
  }

  override updated(changed: Map<string, unknown>): void {
    if (changed.has('live')) this.applyLive();
  }

  override render(): TemplateResult {
    return html`<slot></slot>`;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-system-notice')) {
  customElements.define('jf-system-notice', SystemNotice);
}
