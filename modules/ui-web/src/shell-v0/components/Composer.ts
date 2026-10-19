// SPDX-License-Identifier: Apache-2.0
/**
 * Shared chat-input composer extracted from FreeChatView / AskView /
 * NavigateView / SummarizeView. Renders into the parent's shadow tree
 * (Light DOM) so existing `shadowRoot.querySelector('textarea' | 'button')`
 * call-sites in the view tests continue to work after the migration.
 *
 * Events:
 *   - composer-input  ({ value: string })  fires on textarea input
 *   - composer-submit                       fires on Enter / Ctrl+Enter / button click
 *   - composer-cancel                       fires when cancellable && streaming and the
 *                                           user clicks the cancel button
 *
 * Submit modes:
 *   - 'enter'        — Enter submits (newline still on Shift+Enter via handleSubmitKey)
 *   - 'ctrl-enter'   — Ctrl+Enter submits, Enter inserts newline
 *
 * Cancellable mode (?cancellable):
 *   While streaming, the submit button is replaced by a red cancel button
 *   that emits `composer-cancel` on click. When NOT streaming, the button is
 *   a normal submit button. Consumers wire `@composer-cancel` to their
 *   AbortController for user-facing cancel-while-streaming.
 *
 *   Note: when `?cancellable` is set, `streaming-label` is unused — the
 *   cancel button takes the streaming slot. `streaming-label` only renders
 *   in the non-cancellable label-swap path.
 *
 *   Cancel is button-only by design. The textarea is disabled while
 *   streaming (so the user can't keep typing into an unanswerable prompt),
 *   and real browsers don't deliver keyboard events to disabled inputs. To
 *   cancel via keyboard, the user can Tab to the cancel button and press
 *   Enter — buttons natively activate on Enter when focused.
 *
 * Titles (`submit-title`, `cancel-title`):
 *   Optional `title` attribute strings forwarded to the rendered button.
 *   Empty values omit the attribute entirely (via Lit's `nothing`).
 */

import { html, css, nothing, type CSSResult, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { handleSubmitKey } from '../utils/keyboardHandler.js';

export type ComposerSubmitMode = 'enter' | 'ctrl-enter';

export const composerStyles: CSSResult = css`
  .composer {
    display: flex;
    gap: 0.5rem;
    align-items: stretch;
  }
  .composer textarea {
    flex: 1;
    padding: 0.5rem;
    background: var(--surface-1);
    color: var(--text-primary);
    border: 1px solid var(--border-subtle);
    border-radius: 0.25rem;
    font-family: inherit;
    font-size: var(--font-size-sm);
    resize: vertical;
    min-height: 3rem;
  }
  .composer textarea.mono {
    font-family: ui-monospace, monospace;
  }
  .composer textarea[rows] {
    min-height: 0;
  }
  .composer textarea:focus {
    outline: 2px solid transparent;
    border-color: var(--accent-tint);
  }
  .composer button {
    padding: 0.5rem 1rem;
    background: var(--accent-tint);
    /* 559 contrast-pair: AA-safe text on the accent-tint (was a bare white that
       failed WCAG AA on the light-teal accent — the 558 D1 class). */
    color: var(--accent-on-tint);
    border: none;
    border-radius: 0.25rem;
    font: inherit;
    font-weight: 600;
    cursor: pointer;
  }
  .composer button:disabled {
    background: var(--border-subtle);
    color: var(--text-secondary);
    cursor: not-allowed;
  }
  .composer button.cancel {
    background: var(--accent-danger);
    color: var(--accent-on-danger);
  }
  .composer button.cancel:hover {
    filter: brightness(1.05);
  }
`;

export class JfComposer extends JfElement {
  static properties = {
    value: { type: String },
    placeholder: { type: String },
    streaming: { type: Boolean, reflect: true },
    submitDisabled: { type: Boolean, attribute: 'submit-disabled' },
    submitLabel: { type: String, attribute: 'submit-label' },
    streamingLabel: { type: String, attribute: 'streaming-label' },
    mono: { type: Boolean, reflect: true },
    rows: { type: Number },
    submitMode: { type: String, attribute: 'submit-mode' },
    cancellable: { type: Boolean, reflect: true },
    cancelLabel: { type: String, attribute: 'cancel-label' },
    submitTitle: { type: String, attribute: 'submit-title' },
    cancelTitle: { type: String, attribute: 'cancel-title' },
  };

  declare value: string;
  declare placeholder: string;
  declare streaming: boolean;
  declare submitDisabled: boolean;
  declare submitLabel: string;
  declare streamingLabel: string;
  declare mono: boolean;
  declare rows: number | null;
  declare submitMode: ComposerSubmitMode;
  declare cancellable: boolean;
  declare cancelLabel: string;
  declare submitTitle: string;
  declare cancelTitle: string;

  constructor() {
    super();
    this.value = '';
    this.placeholder = '';
    this.streaming = false;
    this.submitDisabled = false;
    this.submitLabel = 'Send';
    this.streamingLabel = 'Sending…';
    this.mono = false;
    this.rows = null;
    this.submitMode = 'enter';
    this.cancellable = false;
    this.cancelLabel = 'Cancel';
    this.submitTitle = '';
    this.cancelTitle = '';
  }

  override createRenderRoot(): HTMLElement {
    // Light DOM: child <textarea>/<button> live in the parent view's shadow
    // tree, so existing view CSS (`composerStyles` added to the view's
    // static styles) and `shadowRoot.querySelector('textarea')` call-sites
    // both keep working after the migration.
    return this;
  }

  private onInput = (e: Event): void => {
    const v = (e.target as HTMLTextAreaElement).value;
    this.value = v;
    this.dispatchEvent(
      new CustomEvent('composer-input', {
        detail: { value: v },
        bubbles: true,
        composed: true,
      }),
    );
  };

  private onKeydown = (e: KeyboardEvent): void => {
    // Cancel-via-keyboard is unreachable here: the textarea is disabled
    // while streaming, and real browsers don't deliver keydown to disabled
    // inputs. Users cancel via the button (focus + Enter natively activates
    // a button's click handler). See tempdoc 528 Appendix A — D1.
    if (this.submitMode === 'ctrl-enter') {
      if (e.key === 'Enter' && e.ctrlKey) {
        e.preventDefault();
        this.fireSubmit();
      }
      return;
    }
    // Search Thread D3/S2 — in 'enter' mode, Ctrl+Enter is the ALTERNATE submit
    // ("send via the other route"): hosts that route per-turn listen for
    // `composer-submit-alt`; hosts that don't simply ignore it (no behavior
    // change for ctrl-enter-mode consumers, whose Ctrl+Enter stays the submit).
    if (e.key === 'Enter' && e.ctrlKey) {
      e.preventDefault();
      this.fireSubmitAlt();
      return;
    }
    handleSubmitKey(e, () => this.fireSubmit());
  };

  private fireSubmit(): void {
    if (this.streaming || this.submitDisabled) return;
    this.dispatchEvent(
      new CustomEvent('composer-submit', { bubbles: true, composed: true }),
    );
  }

  private fireSubmitAlt(): void {
    if (this.streaming || this.submitDisabled) return;
    this.dispatchEvent(
      new CustomEvent('composer-submit-alt', { bubbles: true, composed: true }),
    );
  }

  private fireCancel(): void {
    this.dispatchEvent(
      new CustomEvent('composer-cancel', { bubbles: true, composed: true }),
    );
  }

  override render(): TemplateResult {
    const showCancel = this.cancellable && this.streaming;
    return html`
      <div class="composer">
        <textarea
          class=${this.mono ? 'mono' : ''}
          .value=${this.value}
          ?disabled=${this.streaming}
          placeholder=${this.placeholder}
          rows=${this.rows ?? nothing}
          @input=${this.onInput}
          @keydown=${this.onKeydown}
        ></textarea>
        ${showCancel
          ? html`<button
              type="button"
              class="cancel"
              title=${this.cancelTitle || nothing}
              @click=${() => this.fireCancel()}
            >
              ${this.cancelLabel}
            </button>`
          : html`<button
              type="button"
              ?disabled=${this.streaming || this.submitDisabled}
              title=${this.submitTitle || nothing}
              @click=${() => this.fireSubmit()}
            >
              ${this.streaming ? this.streamingLabel : this.submitLabel}
            </button>`}
      </div>
    `;
  }
}

if (!customElements.get('jf-composer')) {
  customElements.define('jf-composer', JfComposer);
}

declare global {
  interface HTMLElementTagNameMap {
    'jf-composer': JfComposer;
  }
}
