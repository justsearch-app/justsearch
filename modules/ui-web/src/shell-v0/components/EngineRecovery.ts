// SPDX-License-Identifier: Apache-2.0
import { css, html, nothing } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { isTerminal, type SupervisorState } from '../../api/supervisorState.js';
import { reasonFor } from '../state/readinessNotice.js';
import {
  checkForAppUpdate,
  getAppUpdateStatus,
  installAppUpdate,
  refreshAppUpdateStatus,
  subscribeAppUpdate,
  type AppUpdateStatus,
} from '../state/appUpdateState.js';
import './Button.js';

/** Desktop recovery controls that require no Engine API or surface registry. */
export class EngineRecovery extends JfElement {
  static properties = {
    supervisor: { attribute: false },
    status: { state: true },
    busy: { state: true },
    error: { state: true },
  };

  static transientState = { busy: false, error: '' };
  declare supervisor: SupervisorState | null;
  declare status: AppUpdateStatus | null;
  declare busy: boolean;
  declare error: string;
  onRetry: () => void = () => window.location.reload();
  private unsubscribe: (() => void) | null = null;

  constructor() {
    super();
    this.supervisor = null;
    this.status = getAppUpdateStatus();
    this.busy = false;
    this.error = '';
  }

  static styles = css`
    :host {
      display: block;
      box-sizing: border-box;
      min-height: 100vh;
      padding: 2rem;
      color: var(--text-primary);
      background: var(--surface-1);
      font-family: system-ui, sans-serif;
    }
    section { max-width: 36rem; margin: 4rem auto; }
    h1 { font-size: var(--font-size-xl); }
    p { line-height: 1.6; overflow-wrap: anywhere; }
    .actions { display: flex; flex-wrap: wrap; gap: 0.75rem; margin-top: 1.5rem; }
  `;

  override connectedCallback(): void {
    super.connectedCallback();
    this.unsubscribe = subscribeAppUpdate((status) => { this.status = status; });
    void refreshAppUpdateStatus();
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.unsubscribe?.();
    this.unsubscribe = null;
  }

  private async runUpdateAction(install: boolean): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    this.error = '';
    try {
      if (install) await installAppUpdate();
      else await checkForAppUpdate();
    } catch (error) {
      this.error = error instanceof Error ? error.message : String(error);
    } finally {
      this.busy = false;
    }
  }

  private async restartEngine(): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    this.error = '';
    try {
      const { invoke } = await import('@tauri-apps/api/core');
      await invoke('restart_engine');
    } catch (error) {
      this.error = String(error);
    } finally { this.busy = false; }
  }

  private async openCrashFolder(): Promise<void> {
    try {
      const { invoke } = await import('@tauri-apps/api/core');
      const paths = await invoke<{ home: string }>('justsearch_paths');
      await invoke('reveal_in_explorer', { path: `${paths.home}/crashes` });
    } catch (error) { this.error = String(error); }
  }

  override render() {
    const terminal = isTerminal(this.supervisor);
    const status = this.status;
    const cause = (this.supervisor?.reason ?? this.supervisor?.lastExit?.reason ?? 'unknown')
      .replace(/^ENGINE_RESTART_EXHAUSTED:/, '').replace(/^(?:spawn_failed|restart_failed):/, '');
    const available = status?.state === 'available';
    const updating = [
      'checking', 'downloading', 'preparing', 'prepared', 'head_stopped', 'engine_unrecoverable',
      'install_launching', 'install_launched', 'reconciling',
    ].includes(status?.state ?? '');
    return html`
      <section aria-labelledby="recovery-title" data-testid="engine-recovery">
        <h1 id="recovery-title">${terminal
          ? reasonFor('engine.restart_exhausted').wording
          : this.supervisor?.lastExit?.reason === 'escalated_restart'
            ? reasonFor('engine.escalated_restart').wording
            : this.supervisor?.notReady ? 'The search engine is running but not ready' : 'Connecting to JustSearch'}</h1>
        <p role="status">${terminal
          ? 'You can restart JustSearch or check for an update.'
          : 'JustSearch is starting or recovering. Update controls are available while you wait.'}</p>
        ${this.supervisor?.notReady ? html`<p role="status">${this.supervisor.notReady}</p>` : nothing}
        ${terminal ? html`<p>The search engine stopped ${this.supervisor?.recentExits?.count ?? this.supervisor?.restartCount ?? 0} times in the last 24 hours.
          Last cause: ${cause}.
          ${this.supervisor?.lastExit ? `Exit ${this.supervisor.lastExit.code}.` : ''}</p>` : nothing}
        ${this.supervisor?.lastExit?.code === 2 ? html`<p>The data folder is in use by another process. Close the other instance before restarting.</p>` : nothing}
        ${this.supervisor?.crashFile ? html`<p>${this.supervisor.crashFile.exception}
          ${this.supervisor.crashFile.problematicFrame} (${this.supervisor.crashFile.name}, ${this.supervisor.crashFile.at})</p>` : nothing}
        ${this.supervisor?.retryAt ? html`<p>Automatic retry at ${new Date(this.supervisor.retryAt).toLocaleTimeString()}.</p>` : nothing}
        ${available ? html`<p>JustSearch ${status.availableVersion ?? 'update'} is available.</p>` : nothing}
        ${status?.state === 'up_to_date' ? html`<p>You have the latest version.</p>` : nothing}
        ${status?.state === 'repair_required' ? html`<p>The last update needs repair. Automatic restart may be paused until the update is recovered.</p>` : nothing}
        ${this.busy && !updating ? html`<p role="status">Please wait…</p>` : nothing}
        ${updating ? html`<p role="status">${status?.state === 'checking' ? 'Checking for updates…' : 'Updating JustSearch…'}</p>` : nothing}
        ${this.error || status?.state === 'error' ? html`<p role="alert">${this.error || status?.error
          || 'The update could not finish. Try checking for updates again.'}</p>` : nothing}
        <div class="actions">
          <jf-button .disabled=${this.busy || updating} .onActivate=${() => isTerminal(this.supervisor) ? this.restartEngine() : this.onRetry()}>${terminal ? 'Restart JustSearch' : 'Try connecting again'}</jf-button>
          ${this.supervisor?.crashFile ? html`<jf-button .onActivate=${() => this.openCrashFolder()}>Open crash folder</jf-button>` : nothing}
          <jf-button .disabled=${this.busy || updating} .onActivate=${() => this.runUpdateAction(false)}>Check for updates</jf-button>
          ${available ? html`<jf-button variant="primary" .disabled=${this.busy || updating}
            .onActivate=${() => this.runUpdateAction(true)}>Install update</jf-button>` : nothing}
        </div>
      </section>
    `;
  }
}

if (!customElements.get('jf-engine-recovery')) {
  customElements.define('jf-engine-recovery', EngineRecovery);
}
