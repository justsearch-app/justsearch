// SPDX-License-Identifier: Apache-2.0
import { SignalWatcher } from '@lit-labs/signals';
import { getViewerAudience } from '../state/viewerAudienceState.js';
import { css, html, nothing, type PropertyValues } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { selectIndexingProgress, type IndexingProgress } from '../state/indexingProgress.js';
import { getOperationClient } from '../operations/OperationClient.js';
import { requestSurfaceNavigation } from '../controllers/navigateRequest.js';
import { requestAuthorization } from '../operations/authorizationBroker.js';

/** Shared policy actions. Displayed state always comes from the status projection. */
class IndexingControls extends SignalWatcher(JfElement) {
  static properties = {
    progress: { attribute: false }, apiBase: { type: String, attribute: 'api-base' },
    allowPause: { type: Boolean, attribute: 'allow-pause' },
    duration: { state: true }, pending: { state: true }, message: { state: true },
  };
  declare progress: IndexingProgress;
  declare apiBase: string;
  declare allowPause: boolean;
  declare duration: number;
  declare pending: boolean;
  declare message: string;

  // Retain the last observed action through uncertain polls and while it has focus.
  // This is control identity, never a claim that indexing is paused or running.
  private action: 'pause' | 'resume' | 'settings' | null = null;
  private awaitingStatus = false;
  private focused = false;

  constructor() {
    super();
    this.progress = selectIndexingProgress(null, false, 0, []);
    this.apiBase = '';
    this.allowPause = false;
    this.duration = 0;
    this.pending = false;
    this.message = '';
  }

  static styles = css`
    :host { display: inline-block; max-width: 100%; min-width: 0; }
    .actions { display: flex; flex-wrap: wrap; align-items: center; gap: .4rem; }
    label { display: flex; flex-wrap: wrap; align-items: center; gap: .35rem; font-size: var(--font-size-xs); }
    button, select {
      font: inherit; font-size: var(--font-size-xs); color: var(--text-primary);
      background: var(--surface-1); border: 1px solid var(--border-default);
      border-radius: .3rem; padding: .3rem .45rem; min-height: 1.75rem; max-width: 100%;
    }
    button { cursor: pointer; }
    button[aria-disabled="true"] { opacity: .65; cursor: default; }
    button:focus-visible, select:focus-visible { outline: 2px solid var(--accent-tint); outline-offset: 2px; }
    p { margin: 0; font-size: var(--font-size-xs); overflow-wrap: anywhere; }
    p:not(:empty) { margin-top: .25rem; }
    @media (forced-colors: active) {
      button, select { color: ButtonText; background: ButtonFace; border-color: ButtonText; }
      button:focus-visible, select:focus-visible { outline-color: Highlight; }
    }
  `;

  protected override willUpdate(changed: PropertyValues): void {
    if (this.progress.activity.confirmed && this.progress.pause?.reason === 'settings_recovery') {
      this.action = 'settings';
    } else if (!this.focused && !this.pending && this.progress.activity.confirmed) {
      this.action = this.progress.pause?.reason === 'settings_recovery' ? 'settings'
        : this.progress.pause ? 'resume' : this.allowPause ? 'pause' : null;
    }
    if (changed.has('progress') && !this.pending && this.awaitingStatus && this.progress.activity.confirmed) {
      this.message = '';
      this.awaitingStatus = false;
    }
  }

  private get unavailableReason(): string {
    if (this.pending) return 'Request in progress. Waiting for the Engine.';
    if (!this.progress.activity.confirmed) return '';
    if (this.action === 'resume' && !this.progress.pause) return 'Indexing is already running.';
    if (this.action === 'resume' && this.progress.pause?.reason === 'settings_recovery') {
      return 'Settings recovery requires confirmation of the reset in Settings.';
    }
    if (this.action === 'pause' && this.progress.pause) return 'Indexing is already on hold.';
    return '';
  }

  private async invoke(): Promise<void> {
    if (this.unavailableReason || !this.action) return;
    if (this.action === 'settings') {
      requestSurfaceNavigation('core.settings-surface', { focusSettingsRecovery: true });
      return;
    }
    const resume = this.action === 'resume';
    const args = !resume && this.duration > 0 ? { untilEpochMs: Date.now() + this.duration } : {};
    this.pending = true;
    this.message = '';
    this.awaitingStatus = false;
    try {
      await getOperationClient(this.apiBase).invokeWithConsent(
        resume ? 'core.indexing-resume' : 'core.indexing-pause', { args },
        { requestConsent: requestAuthorization },
      );
      this.awaitingStatus = true;
      this.message = 'Waiting for status to confirm the request.';
    } catch (error: unknown) {
      this.message = `Could not confirm the indexing request. Check the current status before trying again. ${error instanceof Error ? error.message : ''}`;
    } finally {
      this.pending = false;
    }
  }

  override render() {
    if (!this.action) return nothing;
    const resume = this.action === 'resume';
    const unavailable = this.unavailableReason;
    const userRecovery = this.action === 'settings' && getViewerAudience() === 'USER';
    return html`<div class="actions" aria-busy=${this.pending ? 'true' : 'false'}
      @focusin=${() => { this.focused = true; }}
      @focusout=${(event: FocusEvent) => {
        this.focused = event.relatedTarget instanceof Node && !!this.shadowRoot?.contains(event.relatedTarget);
        this.requestUpdate();
      }}>
      ${this.action === 'pause' ? html`<label>Pause duration
        <select aria-label="Pause duration"
          .value=${String(this.duration)} @change=${(e: Event) => { this.duration = Number((e.target as HTMLSelectElement).value); }}>
          <option value="0">Until I resume</option>
          <option value="900000">15 minutes</option>
          <option value="3600000">1 hour</option>
        </select></label>` : nothing}
      <button type="button" aria-disabled=${unavailable ? 'true' : 'false'} aria-describedby="control-state"
        @click=${() => void this.invoke()}>${this.action === 'settings' ? userRecovery ? 'Open Settings at View tier' : 'Open Settings at Reset to defaults' : resume ? 'Resume indexing' : 'Pause indexing'}</button>
    </div>
    <p id="control-state" role="status">${[userRecovery ? 'In Settings, set View tier to Operator, then Reset to defaults.' : '', unavailable, this.message].filter(Boolean).join(' ')}</p>`;
  }
}

if (!customElements.get('jf-indexing-controls')) customElements.define('jf-indexing-controls', IndexingControls);
