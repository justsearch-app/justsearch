// SPDX-License-Identifier: Apache-2.0
import { LitElement, css, html, nothing } from 'lit';
import { invoke } from '@tauri-apps/api/core';

interface ResetStatus {
  phase: 'idle' | 'requested' | 'incomplete' | 'awaiting_storage' | 'complete';
  remaining: { path: string; error: string }[];
}

/** This surface deliberately needs neither an Engine nor hydrated application state. */
class FactoryResetRecovery extends LitElement {
  static properties = {
    status: { attribute: false }, error: { state: true }, busy: { state: true },
  };
  declare status: ResetStatus | null;
  declare error: string;
  declare busy: boolean;
  constructor() {
    super();
    this.status = null; this.error = ''; this.busy = false;
  }
  static styles = css`
    :host { display: block; box-sizing: border-box; min-height: 100vh; padding: 1.5rem;
      color: var(--text-primary, CanvasText); background: var(--surface-1, Canvas);
      font: 1rem system-ui, sans-serif; overflow-wrap: anywhere; }
    section { max-width: 38rem; margin: 2rem auto; min-width: 0; }
    p, li { line-height: 1.6; }
    ul { padding-inline-start: 1.25rem; }
    .actions { display: flex; flex-wrap: wrap; gap: 0.75rem; margin-top: 1.5rem; }
    button { padding: 0.65rem 1rem; border: 1px solid var(--border-subtle, ButtonText);
      border-radius: 0.375rem; color: var(--text-primary, ButtonText);
      background: var(--surface-2, ButtonFace); font: inherit; cursor: pointer; }
    button:focus-visible { outline: 2px solid var(--accent-tint, Highlight); outline-offset: 3px; }
    button:disabled { cursor: wait; }
    [role='alert'] { border-inline-start: 3px solid currentColor; padding-inline-start: 0.75rem; }
    @media (forced-colors: active) {
      :host { color: CanvasText; background: Canvas; }
      button { color: ButtonText; background: ButtonFace; border-color: ButtonText; }
      button:focus-visible { outline-color: Highlight; }
    }
  `;

  private async run(command: string): Promise<void> {
    if (this.busy) return;
    this.busy = true; this.error = '';
    try {
      await invoke(command);
      if (command === 'acknowledge_factory_reset') window.location.reload();
      // The other commands request a shell restart. Keep normal boot blocked until it exits.
    } catch (error) {
      this.error = error instanceof Error ? error.message : String(error);
      this.busy = false;
    }
  }

  override render() {
    const phase = this.status?.phase;
    return html`
      <section aria-labelledby="reset-title">
        <h1 id="reset-title" tabindex="-1">${phase === 'complete' ? 'Reset complete'
          : phase === 'incomplete' ? 'Reset incomplete' : 'Finishing data reset'}</h1>
        <p role="status" aria-live="polite">${this.busy ? 'Restarting JustSearch automatically…'
          : phase === 'complete' ? 'Reset complete. Your local data and window storage were deleted. AI models and AI runtime were kept.'
            : phase === 'incomplete' ? 'Some data could not be removed. Normal use is paused until you retry or cancel this reset.'
              : 'Checking and clearing local window storage before JustSearch can start.'}</p>
        ${this.status?.remaining.length ? html`<ul aria-label="What could not be removed">
          ${this.status.remaining.map((failure) => html`<li><strong>${failure.path}</strong>: ${failure.error}</li>`)}
        </ul>` : nothing}
        ${this.error ? html`<p role="alert">${this.error}</p>` : nothing}
        ${phase === 'incomplete' ? html`<p>Cancel reset keeps the remaining data as ordinary data.
          Data already deleted cannot be recovered.</p>` : nothing}
        <div class="actions">
          ${phase === 'incomplete' ? html`
            <button ?disabled=${this.busy} @click=${() => void this.run('retry_factory_reset')}>Retry reset</button>
            <button ?disabled=${this.busy} @click=${() => void this.run('cancel_factory_reset')}>Cancel reset</button>
          ` : phase === 'complete' ? html`
            <button ?disabled=${this.busy} @click=${() => void this.run('acknowledge_factory_reset')}>Continue</button>
          ` : this.error ? html`<button @click=${() => window.location.reload()}>Try again</button>` : nothing}
        </div>
      </section>`;
  }
}
customElements.define('jf-factory-reset-recovery', FactoryResetRecovery);

/** Called before catalogs, state restoration, API discovery, or any normal surface. */
export async function gateFactoryReset(root: HTMLElement): Promise<boolean> {
  let recovery: FactoryResetRecovery | null = null;
  try {
    const status = await invoke<ResetStatus>('factory_reset_status');
    if (!status || !['idle', 'requested', 'incomplete', 'awaiting_storage', 'complete'].includes(status.phase)
        || !Array.isArray(status.remaining)) throw new Error('Unable to read the data reset status. Try again.');
    if (status.phase === 'idle') return true;
    recovery = new FactoryResetRecovery();
    recovery.status = status;
    root.replaceChildren(recovery);
    await recovery.updateComplete;
    recovery.shadowRoot?.querySelector<HTMLElement>('h1')?.focus();
    if (status.phase === 'awaiting_storage') {
      // The old renderer has exited; its pagehide/draft flushes cannot recreate this data.
      // This runs before state singletons can hydrate, so stale in-memory copies cannot write it back.
      localStorage.clear();
      sessionStorage.clear();
      if (localStorage.length || sessionStorage.length) throw new Error('Window storage could not be cleared.');
      await invoke('complete_factory_reset_storage');
      recovery.busy = true;
    }
  } catch (error) {
    if (!recovery) { recovery = new FactoryResetRecovery(); root.replaceChildren(recovery); }
    recovery.error = error instanceof Error ? error.message : String(error);
    await recovery.updateComplete;
  }
  return false;
}
