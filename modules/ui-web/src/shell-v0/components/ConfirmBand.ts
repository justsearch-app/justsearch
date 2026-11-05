// SPDX-License-Identifier: Apache-2.0
/**
 * ConfirmBand (`jf-confirm-band`) — the in-place confirmation (design system §7, ref-06).
 *
 * A person's own risky action that cannot be reversed by Undo asks right where they clicked: a
 * band under the item with one consequence sentence (what changes, what stays, undo or not), a
 * danger button named with the verb and object ("Remove Notes") and "Keep it". It replaces the
 * window-blocking dialogs for ordinary own actions; typed confirmation stays a dialog (ConfirmDialog).
 *
 * Interaction contract:
 *  - Focus moves to "Keep it" when the band appears (the safe answer is the default).
 *  - Escape or "Keep it" cancels and returns focus to the control that opened the band.
 *  - The danger button answers once: a double-click, a repeated activation or an auto-repeating
 *    Enter/Space cannot run the action twice. Auto-repeat of a key held on the opener cannot answer
 *    the band either.
 *
 * The band only asks; the host owns the action and removes the band (or sets `busy`) on
 * `confirm-band-confirm`. Events bubble and are composed so themed renderers can forward them.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { deepActiveElement } from '../utils/keyboardHandler.js';
import { icon } from './Icon.js';
import './Button.js';

const CONFIRM_BAND_CONFIRM = 'confirm-band-confirm';
const CONFIRM_BAND_CANCEL = 'confirm-band-cancel';

export class ConfirmBand extends JfElement {
  static properties = {
    heading: { type: String },
    consequence: { type: String },
    actionLabel: { type: String, attribute: 'action-label' },
    keepLabel: { type: String, attribute: 'keep-label' },
    busy: { type: Boolean, reflect: true },
  } as const;

  /** Optional question naming the object ("Remove Notes from your library?"). */
  declare heading: string;
  /** One sentence: what changes, what stays, undo or not. */
  declare consequence: string;
  /** The danger button: verb and object ("Remove Notes"). */
  declare actionLabel: string;
  declare keepLabel: string;
  /** The host is running the action; neither answer is offered again. */
  declare busy: boolean;
  /** The control that opened the band; focus returns there on cancel. Defaults to the focused element. */
  opener: HTMLElement | null = null;
  private answered = false;
  /** A document is a window, including bands nested inside different shadow roots. */
  private static readonly openBands = new WeakMap<Document, ConfirmBand>();

  constructor() {
    super();
    this.heading = '';
    this.consequence = '';
    this.actionLabel = '';
    this.keepLabel = 'Keep it';
    this.busy = false;
  }

  static styles = css`
    :host { display: block; min-width: 0; max-width: 100%; }
    .band {
      display: grid;
      gap: var(--jf-space-2);
      padding: var(--jf-space-3) var(--jf-space-4);
      border-inline-start: var(--frame-focus-width) solid var(--accent-danger);
      background: var(--accent-danger-08);
      color: var(--text-primary);
      font-size: var(--font-size-sm);
    }
    .heading { margin: 0; font-weight: var(--jf-weight-medium); overflow-wrap: anywhere; }
    .band > * { min-width: 0; }
    jf-button { min-width: 0; max-width: 100%; }
    jf-button::part(control) { min-width: 0; max-width: 100%; box-sizing: border-box; }
    .action-label { min-width: 0; overflow-wrap: anywhere; }
    .consequence { margin: 0; color: var(--text-secondary); overflow-wrap: anywhere; }
    .answers { display: flex; flex-wrap: wrap; align-items: center; gap: var(--jf-space-2); }
    .hint { color: var(--text-secondary); font-size: var(--font-size-xs); }
    kbd { font: inherit; padding-inline: var(--jf-space-1); border: var(--frame-hairline) solid var(--border-subtle);
      border-radius: var(--frame-control-radius); }
    @media (forced-colors: active) {
      .band { border-inline-start-color: CanvasText; background: Canvas; color: CanvasText; }
      .consequence, .hint { color: CanvasText; }
    }
  `;

  override connectedCallback(): void {
    super.connectedCallback();
    if (!this.opener) {
      const active = deepActiveElement();
      this.opener = active instanceof HTMLElement && active !== document.body ? active : null;
    }
    this.answered = false;
    const previous = ConfirmBand.openBands.get(this.ownerDocument);
    ConfirmBand.openBands.set(this.ownerDocument, this);
    if (previous && previous !== this) {
      // Clear the previous owner's draft too; reopening it must ask afresh. Do not restore its
      // focus: this band's Keep it now owns focus. An action already running is not interrupted.
      previous.answered = true;
      previous.dispatchEvent(new CustomEvent(CONFIRM_BAND_CANCEL, { bubbles: true, composed: true }));
    }
    // Capture: a parent window (Settings, a drawer) must not close on the Escape that cancels the band.
    this.addEventListener('keydown', this.onKeyDown, true);
  }

  override disconnectedCallback(): void {
    if (ConfirmBand.openBands.get(this.ownerDocument) === this) ConfirmBand.openBands.delete(this.ownerDocument);
    this.removeEventListener('keydown', this.onKeyDown, true);
    super.disconnectedCallback();
  }

  protected override firstUpdated(): void {
    void this.focusKeep();
  }

  /** Focus "Keep it" (the safe answer), once it and its inner control have rendered. */
  async focusKeep(): Promise<void> {
    await this.updateComplete;
    type Rendered = HTMLElement & { updateComplete?: Promise<unknown> };
    const keep = this.shadowRoot?.querySelector<Rendered>('jf-button.keep');
    await keep?.updateComplete;
    await keep?.shadowRoot?.querySelector<Rendered>('jf-control')?.updateComplete;
    if (this.isConnected && !this.answered && ConfirmBand.openBands.get(this.ownerDocument) === this) keep?.focus();
  }

  private readonly onKeyDown = (event: KeyboardEvent): void => {
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      if (!event.repeat) this.cancel();
      return;
    }
    // An auto-repeating Enter/Space (a key held since the opener, or held on an answer) answers nothing.
    if (event.repeat && (event.key === 'Enter' || event.key === ' ')) {
      event.preventDefault();
      event.stopPropagation();
    }
  };

  private confirm(): void {
    if (this.answered || this.busy) return;
    this.answered = true;
    this.dispatchEvent(new CustomEvent(CONFIRM_BAND_CONFIRM, { bubbles: true, composed: true }));
  }

  private cancel(): void {
    if (this.answered || this.busy) return;
    this.answered = true;
    const opener = this.opener;
    this.dispatchEvent(new CustomEvent(CONFIRM_BAND_CANCEL, { bubbles: true, composed: true }));
    // The host removes the band; focus returns to the control that opened it, if it is still there.
    if (opener?.isConnected) opener.focus({ preventScroll: true });
  }

  override render(): TemplateResult {
    return html`<div class="band" role="group" aria-label=${this.heading || this.actionLabel}
      aria-describedby="consequence">
      ${this.heading ? html`<p class="heading">${this.heading}</p>` : nothing}
      <p class="consequence" id="consequence">${this.consequence}</p>
      <div class="answers">
        <jf-button class="confirm" tone="error" title=${this.actionLabel} .disabled=${this.busy}
          .onActivate=${() => this.confirm()}>${icon({ name: 'trash-2', size: 14 })} <span class="action-label">${this.actionLabel}</span></jf-button>
        <jf-button class="keep" .disabled=${this.busy} .onActivate=${() => this.cancel()}>${this.keepLabel}</jf-button>
        <span class="hint" aria-hidden="true"><kbd>Esc</kbd> keeps it</span>
      </div>
    </div>`;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-confirm-band')) {
  customElements.define('jf-confirm-band', ConfirmBand);
}

declare global {
  interface HTMLElementTagNameMap {
    'jf-confirm-band': ConfirmBand;
  }
}
