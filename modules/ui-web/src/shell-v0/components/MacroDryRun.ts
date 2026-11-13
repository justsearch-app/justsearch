// SPDX-License-Identifier: Apache-2.0
/**
 * <jf-macro-dry-run> — Tempdoc 543-fwd idea #12 (macro dry-run diff).
 *
 * Before a macro replays, shows exactly what it WOULD do: one row per effect
 * (labelled via describeEffect), and — when the macro would re-POST to the
 * backend (invoke-operation / undo-operation) — a warning naming those
 * operations. This is the confirmation gate for the §12 backend-replay guard:
 * the palette routes a backend-bearing macro here instead of running it
 * silently, and "Run macro" calls runMacro(id, { allowBackendReplay: true }).
 *
 * Opens via the `jf-open-macro-dry-run` CustomEvent ({ macroId }); closes via
 * Escape, the scrim, Cancel, or after Run.
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import './ErrorAlert.js';
import { JfElement } from '../primitives/JfElement.js';
import './Button.js';
import { ModalController } from '../primitives/modalController.js';
import { repeat } from 'lit/directives/repeat.js';
import {
  previewMacroReplay,
  runMacro,
  getMacro,
  type MacroReplayPlan,
} from '../substrates/macros/index.js';
import { describeChange } from '../substrates/effects/describe.js';
// 543-fwd UPDATE 10 P1 — shared effect-presentation primitive for the step label
// (EffectLine routes its label through describeEffect — the one effect authority).
import './EffectLine.js';

export class MacroDryRun extends JfElement {
  static properties = {
    open: { type: Boolean, reflect: true },
    macroId: { state: true },
    plan: { state: true },
  };

  declare open: boolean;
  declare macroId: string | null;
  declare plan: MacroReplayPlan;

  private openListener: ((e: Event) => void) | null = null;

  constructor() {
    super();
    this.open = false;
    this.macroId = null;
    this.plan = { entries: [], backendOps: [] };
  }

  connectedCallback(): void {
    super.connectedCallback();
    if (!this.openListener) {
      this.openListener = (e: Event) => {
        const id = (e as CustomEvent<{ macroId: string }>).detail?.macroId;
        if (id) this.show(id);
      };
      document.addEventListener('jf-open-macro-dry-run', this.openListener);
    }
  }

  disconnectedCallback(): void {
    super.disconnectedCallback();
    if (this.openListener) {
      document.removeEventListener('jf-open-macro-dry-run', this.openListener);
      this.openListener = null;
    }
  }

  /** The FULL modal contract (574 §22.G) — native <dialog> + scroll-lock + focus-restore, atomic by construction. */
  private readonly modal = new ModalController(this, {
    dialog: () => this.shadowRoot?.querySelector('dialog'),
  });

  /** Drive the native <dialog> from the reactive `open` flag (full contract by construction). */
  override updated(changed: Map<string, unknown>): void {
    if (changed.has('open')) {
      if (this.open) this.modal.open();
      else this.modal.close();
    }
  }

  /** Open the panel for a macro id, computing its replay plan. */
  show(macroId: string): void {
    this.macroId = macroId;
    this.plan = previewMacroReplay(macroId);
    this.open = true;
  }

  /** Request close (Cancel / programmatic); `updated` drives the native dialog. */
  private close(): void {
    this.open = false;
  }

  /**
   * Fires for Escape, backdrop, and programmatic close. Only syncs the flag — the
   * stale macroId/plan are harmless while hidden and `show()` overwrites them, so we
   * avoid resetting reactive state inside the close cycle (no change-in-update churn).
   */
  private onDialogClose(): void {
    this.open = false;
  }

  private async handleRun(): Promise<void> {
    const id = this.macroId;
    if (!id) return;
    this.close();
    // The user confirmed the dry-run, so backend re-POST is now allowed.
    await runMacro(id, { allowBackendReplay: true });
  }

  static styles = css`
    /* 543-fwd UPDATE 10 P3 — native <dialog> (showModal) provides focus management,
       Escape, ::backdrop, and aria-modal; the host is a passthrough. */
    :host {
      display: contents;
    }
    dialog {
      width: 30rem;
      max-width: calc(100vw - 2rem);
      max-height: 70vh;
      padding: 0;
      border: 1px solid var(--border-default);
      border-radius: 0.5rem;
      background: var(--surface-1);
      color: var(--text-primary);
      box-shadow: 0 8px 24px rgba(0, 0, 0, 0.4);
    }
    dialog::backdrop {
      background: rgba(0, 0, 0, 0.4);
    }
    .panel {
      display: flex;
      flex-direction: column;
      max-height: inherit;
    }
    header {
      padding: 0.75rem 1rem;
      border-bottom: 1px solid var(--border-default);
      font-size: var(--font-size-md);
      font-weight: 600;
    }
    ol {
      flex: 1;
      overflow-y: auto;
      margin: 0.75rem 0;
      padding: 0 1rem 0 2.25rem;
      font-size: var(--font-size-sm);
    }
    li {
      padding: 0.1875rem 0;
      display: flex;
      flex-direction: column;
      gap: 0.0625rem;
    }
    li.backend {
      color: var(--text-warning);
    }
    /* 543-fwd #12 — before→after diff row. */
    .step-label {
      font-weight: 500;
    }
    .step-diff {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .diff-before {
      text-decoration: line-through;
      opacity: 0.7;
    }
    .diff-arrow {
      margin: 0 0.375rem;
      color: var(--text-tertiary);
    }
    .diff-after {
      color: var(--text-primary);
    }
    li.backend .diff-after {
      color: var(--text-warning);
    }
    .empty {
      padding: 1.5rem 1rem;
      text-align: center;
      color: var(--text-tertiary);
      font-size: var(--font-size-sm);
    }
    footer {
      display: flex;
      gap: 0.5rem;
      justify-content: flex-end;
      padding: 0.75rem 1rem;
      border-top: 1px solid var(--border-default);
    }
    /* 574 B (remediation) — cancel = jf-button (secondary), Run macro = jf-button variant="primary";
       the bespoke button/.primary fork is deleted. */
    :host .dry-run-error { margin: 0.75rem 1rem 0; }

  `;

  render(): TemplateResult {
    const macro = this.macroId ? getMacro(this.macroId) : undefined;
    const { entries, backendOps } = this.plan;
    return html`
      <dialog
        data-testid="macro-dry-run-dialog"
        aria-label="Macro dry run"
        @close=${() => this.onDialogClose()}
        @keydown=${(e: KeyboardEvent) => {
          // Some environments don't fire the dialog `close` event; sync on Escape.
          if (e.key === 'Escape') this.close();
        }}
        @click=${(e: MouseEvent) => {
          if (e.target === e.currentTarget) this.close();
        }}
      >
        <div class="panel">
          <header>Dry run: ${macro?.label ?? this.macroId ?? 'macro'}</header>
        ${backendOps.length > 0
          ? html`<jf-error-alert
              tone="warning"
              data-testid="macro-dry-run-warning"
              class="dry-run-error"
            >
              ⚠ This macro re-runs ${backendOps.length} backend
              operation${backendOps.length === 1 ? '' : 's'}:
              ${backendOps.join(', ')}. Running it will execute them again.
            </jf-error-alert>`
          : nothing}
        ${entries.length === 0
          ? html`<div class="empty">This macro has no effects.</div>`
          : html`<ol>
              ${repeat(
                entries,
                (_e, i) => i,
                (e) => {
                  const change = describeChange(e.effect);
                  const backend =
                    e.effect.kind === 'invoke-operation' || e.effect.kind === 'undo-operation';
                  // 543-fwd #4 — the before→after diff line only earns its place when a
                  // real prior state exists (open/close, set-form-value). For
                  // navigate/toast `change.before` is undefined and `change.after` merely
                  // restates the label, so suppress the redundant second line.
                  return html`<li
                    class=${backend ? 'backend' : ''}
                    data-testid="macro-dry-run-row"
                  >
                    <jf-effect-line class="step-label" .effect=${e.effect}></jf-effect-line>
                    ${change.before !== undefined
                      ? html`<span class="step-diff">
                          <span class="diff-before">${change.before}</span
                          ><span class="diff-arrow">→</span
                          ><span class="diff-after">${change.after}</span>
                        </span>`
                      : nothing}
                  </li>`;
                },
              )}
            </ol>`}
        <footer>
          <jf-button data-testid="macro-dry-run-cancel" label="Cancel" .onActivate=${() => this.close()}>
            Cancel
          </jf-button>
          <jf-button
            class="primary"
            variant="primary"
            data-testid="macro-dry-run-run"
            label="Run macro"
            .onActivate=${() => void this.handleRun()}
          >
            Run macro
          </jf-button>
        </footer>
        </div>
      </dialog>
    `;
  }
}

if (
  typeof customElements !== 'undefined' &&
  !customElements.get('jf-macro-dry-run')
) {
  customElements.define('jf-macro-dry-run', MacroDryRun);
}
