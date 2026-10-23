// SPDX-License-Identifier: Apache-2.0
/**
 * ConfirmDialog — Lit modal confirmation primitive (slice 457).
 *
 * Replaces `globalThis.confirm()` callsites in Lit surfaces. Drop-in
 * replacement for the React `<ConfirmDialog>` (`components/ui/
 * ConfirmDialog.tsx`) — same variants, same Promise-based API via
 * `confirmAsync()` helper.
 *
 * Variants: danger / warning / info.
 *
 * Typed-confirm mode: when `typed-confirm-word` is set, the confirm
 * button is disabled until the user types the exact word into the
 * prompt input. Used for HIGH-risk operations (Force Rebuild Index,
 * Delete all data, etc.).
 *
 * Side-effect registers `<jf-confirm-dialog>`.
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import './Button.js';
import { ModalController } from '../primitives/modalController.js';
import { deepActiveElement } from '../utils/keyboardHandler.js';
import { icon } from './Icon.js';

type Variant = 'danger' | 'warning' | 'info';

export class ConfirmDialog extends JfElement {
  static properties = {
    open: { type: Boolean, reflect: true },
    title: { type: String },
    message: { type: String },
    confirmLabel: { type: String, attribute: 'confirm-label' },
    cancelLabel: { type: String, attribute: 'cancel-label' },
    variant: { type: String },
    typedConfirmWord: { type: String, attribute: 'typed-confirm-word' },
    typedInput: { state: true },
  };

  declare open: boolean;
  declare title: string;
  declare message: string;
  declare confirmLabel: string;
  declare cancelLabel: string;
  declare variant: Variant;
  declare typedConfirmWord?: string;
  declare typedInput: string;

  private boundKey = (e: KeyboardEvent) => this.onKey(e);

  constructor() {
    super();
    this.open = false;
    this.title = '';
    this.message = '';
    this.confirmLabel = 'Confirm';
    this.cancelLabel = 'Cancel';
    this.variant = 'danger';
    this.typedInput = '';
  }

  static styles = css`
    :host {
      display: contents;
    }
    /* 574 remediation A1 — native <dialog> (showModal): browser inert + focus-trap + Top Layer,
       so no .backdrop div + no hand-picked z-index (the §16 S4 z-literal is gone here). */
    dialog {
      border: 1px solid var(--border-subtle);
      border-radius: 0.75rem;
      max-width: 28rem;
      width: 100%;
      padding: 0;
      background: var(--surface-1);
      color: var(--text-primary);
      box-shadow: 0 20px 40px rgba(0, 0, 0, 0.5);
    }
    dialog::backdrop {
      background: rgba(0, 0, 0, 0.55);
    }
    .card {
      padding: 1.25rem;
    }
    .head {
      display: flex;
      align-items: flex-start;
      gap: 0.75rem;
      margin-bottom: 0.75rem;
    }
    .icon-box {
      width: 2.25rem;
      height: 2.25rem;
      border-radius: 0.5rem;
      display: flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
    }
    .icon-box.danger {
      background: var(--accent-danger-08);
      color: var(--text-danger);
    }
    .icon-box.warning {
      background: var(--accent-warning-08);
      color: var(--text-warning);
    }
    .icon-box.info {
      background: var(--accent-tint-08);
      color: var(--text-tint);
    }
    .title {
      font-size: var(--font-size-md);
      font-weight: 600;
      margin: 0;
    }
    .close {
      margin-left: auto;
    }
    .message {
      overflow-wrap: anywhere;
      margin: 0 0 1rem 0;
      font-size: var(--font-size-sm);
      line-height: 1.5;
      color: var(--text-secondary);
    }
    .typed-row {
      margin-bottom: 0.875rem;
      display: flex;
      flex-direction: column;
      gap: 0.375rem;
    }
    .typed-row label {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .typed-row code {
      font-family: monospace;
      color: var(--text-primary);
      background: var(--surface-tertiary);
      padding: 0.125rem 0.375rem;
      border-radius: 0.25rem;
    }
    .typed-row input {
      padding: 0.4rem 0.625rem;
      background: var(--surface-tertiary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      color: var(--text-primary);
      font-family: monospace;
      font-size: var(--font-size-sm);
    }
    .actions {
      display: flex;
      gap: 0.5rem;
      justify-content: flex-end;
    }
    /* 574 B (remediation) — the cancel/confirm/close buttons are jf-button atoms now: cancel =
       secondary, close = ghost icon, and the confirm is a SOLID CTA via the atom's tone variant
       (danger→tone="error", warning/info pass through) — the one tone→accent authority, replacing
       this dialog's hand-picked .confirm.{danger,warning,info} fills. */
  `;

  override connectedCallback(): void {
    super.connectedCallback();
    document.addEventListener('keydown', this.boundKey);
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    document.removeEventListener('keydown', this.boundKey);
  }

  /** The FULL modal contract (574 §22.G) — native `<dialog>` (inert + focus-trap + Top Layer) +
      scroll-lock + focus-restore, fired atomically by `open()`/`close()` so this dialog can never be
      half-wired. `onOpened` focuses the typed-confirm input or the confirm CTA after showModal. */
  private readonly modal = new ModalController(this, {
    dialog: () => this.shadowRoot?.querySelector('dialog'),
    onOpened: () => {
      requestAnimationFrame(() => {
        if (this.typedConfirmWord) {
          this.shadowRoot?.querySelector<HTMLInputElement>('.typed-row input')?.focus();
        } else {
          // jf-button delegates focus() to its composed native <button>.
          (this.shadowRoot?.querySelector('jf-button.confirm') as HTMLElement | null)?.focus();
        }
      });
    },
  });

  override updated(changed: Map<string, unknown>): void {
    if (!changed.has('open')) return;
    if (this.open) {
      this.modal.open(); // 574 §22.G — enter + showModal + focus, atomically by construction
    } else {
      this.typedInput = '';
      this.modal.close();
    }
  }

  private onKey(e: KeyboardEvent): void {
    if (!this.open) return;
    // Escape is handled natively by <dialog> (the @cancel handler in render); here only Enter.
    if (e.key !== 'Enter' || this.confirmDisabled()) return;
    // Focus can be several shadow roots deep. Focused controls own Enter (including the
    // native Confirm button and the typed input); only the dialog itself uses this shortcut.
    const active = deepActiveElement();
    if (active && active !== document.body && active !== this &&
        active !== this.shadowRoot?.querySelector('dialog')) return;
    e.preventDefault();
    this.confirm();
  }

  private confirmDisabled(): boolean {
    if (this.typedConfirmWord) {
      return this.typedInput.trim() !== this.typedConfirmWord;
    }
    return false;
  }

  private confirm(): void {
    this.dispatchEvent(
      new CustomEvent('confirm', { bubbles: true, composed: true }),
    );
  }

  private cancel(): void {
    this.dispatchEvent(
      new CustomEvent('cancel', { bubbles: true, composed: true }),
    );
  }

  private onBackdrop(e: Event): void {
    if (e.target === e.currentTarget) {
      this.cancel();
    }
  }

  override render(): TemplateResult {
    const variantIcon = (
      this.variant === 'info' ? 'check-circle-2'
      : this.variant === 'warning' ? 'alert-triangle'
      : 'alert-triangle'
    ) as 'check-circle-2' | 'alert-triangle';
    return html`
      <dialog
        aria-labelledby="dlg-title"
        @cancel=${(e: Event) => {
          e.preventDefault();
          this.cancel();
        }}
        @click=${(e: Event) => this.onBackdrop(e)}
      >
        <div class="card">
          <div class="head">
            <span class="icon-box ${this.variant}">
              ${icon({ name: variantIcon, size: 18 })}
            </span>
            <h3 id="dlg-title" class="title">${this.title}</h3>
            <jf-button
              class="close"
              variant="ghost"
              size="icon"
              label="Close"
              .onActivate=${() => this.cancel()}
            >
              ${icon({ name: 'x', size: 14 })}
            </jf-button>
          </div>
          <p class="message">${this.message}</p>
          ${this.typedConfirmWord
            ? html`
                <div class="typed-row">
                  <label for="typed-confirm-input">
                    Type <code>${this.typedConfirmWord}</code> to confirm:
                  </label>
                  <input
                    id="typed-confirm-input"
                    type="text"
                    .value=${this.typedInput}
                    @input=${(e: Event) =>
                      (this.typedInput = (e.target as HTMLInputElement).value)}
                    @keydown=${(e: KeyboardEvent) => {
                      if (e.key === 'Enter' && !this.confirmDisabled()) {
                        e.preventDefault();
                        this.confirm();
                      }
                    }}
                  />
                </div>
              `
            : nothing}
          <div class="actions">
            <jf-button class="cancel" label=${this.cancelLabel} .onActivate=${() => this.cancel()}>
              ${this.cancelLabel}
            </jf-button>
            <jf-button
              class="confirm"
              tone=${this.variant === 'danger' ? 'error' : this.variant}
              label=${this.confirmLabel}
              ?disabled=${this.confirmDisabled()}
              .onActivate=${() => this.confirm()}
            >
              ${this.confirmLabel}
            </jf-button>
          </div>
        </div>
      </dialog>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-confirm-dialog')) {
  customElements.define('jf-confirm-dialog', ConfirmDialog);
}

// ---------- Promise-based helper ----------

export interface ConfirmAsyncOptions {
  title: string;
  message: string;
  confirmLabel?: string;
  cancelLabel?: string;
  variant?: Variant;
  typedConfirmWord?: string;
}

/**
 * Promise-based wrapper. Creates an off-DOM `<jf-confirm-dialog>`,
 * appends to body, resolves with true/false on user action.
 */
export function confirmAsync(opts: ConfirmAsyncOptions): Promise<boolean> {
  return new Promise<boolean>((resolve) => {
    const el = document.createElement('jf-confirm-dialog') as ConfirmDialog;
    el.title = opts.title;
    el.message = opts.message;
    if (opts.confirmLabel) el.confirmLabel = opts.confirmLabel;
    if (opts.cancelLabel) el.cancelLabel = opts.cancelLabel;
    el.variant = opts.variant ?? 'danger';
    if (opts.typedConfirmWord) el.typedConfirmWord = opts.typedConfirmWord;
    el.open = true;
    const cleanup = (result: boolean) => {
      el.open = false;
      // Allow CSS transition out before removing.
      setTimeout(() => el.remove(), 200);
      resolve(result);
    };
    el.addEventListener('confirm', () => cleanup(true), { once: true });
    el.addEventListener('cancel', () => cleanup(false), { once: true });
    document.body.appendChild(el);
  });
}
