// SPDX-License-Identifier: Apache-2.0
import { css, html, nothing, type PropertyValues } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { selectIndexingProgress, type IndexingProgress } from '../state/indexingProgress.js';
import { getOperationClient } from '../operations/OperationClient.js';
import { requestAuthorization } from '../operations/authorizationBroker.js';

/** Shared policy actions. Displayed state always comes from the status projection. */
class IndexingControls extends JfElement {
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
    button:disabled, select:disabled { opacity: .65; cursor: default; }
    button:focus-visible, select:focus-visible { outline: 2px solid var(--accent-tint); outline-offset: 2px; }
    p { margin: 0; font-size: var(--font-size-xs); overflow-wrap: anywhere; }
    p:not(:empty) { margin-top: .25rem; }
    @media (forced-colors: active) {
      button, select { color: ButtonText; background: ButtonFace; border-color: ButtonText; }
      button:focus-visible, select:focus-visible { outline-color: Highlight; }
    }
  `;

  protected override updated(changed: PropertyValues): void {
    // A new observation reconciles the request; never synthesize paused/resumed locally.
    if (changed.has('progress') && !this.pending && this.message && this.progress.activity.confirmed) this.message = '';
  }

  private async invoke(): Promise<void> {
    if (this.pending || !this.progress.activity.confirmed) return;
    const resume = !!this.progress.pause;
    const args = !resume && this.duration > 0 ? { untilEpochMs: Date.now() + this.duration } : {};
    this.pending = true;
    this.message = '';
    try {
      await getOperationClient(this.apiBase).invokeWithConsent(
        resume ? 'core.indexing-resume' : 'core.indexing-pause', { args },
        { requestConsent: requestAuthorization },
      );
      this.message = 'Waiting for status to confirm the request.';
    } catch (error: unknown) {
      this.message = `Could not confirm the indexing request. Check the current status before trying again. ${error instanceof Error ? error.message : ''}`;
    } finally {
      this.pending = false;
    }
  }

  override render() {
    const resume = !!this.progress.pause;
    if (!resume && !this.allowPause) return nothing;
    return html`<div class="actions" aria-busy=${this.pending ? 'true' : 'false'}>
      ${!resume ? html`<label>Pause duration
        <select aria-label="Pause duration" ?disabled=${this.pending || !this.progress.activity.confirmed}
          .value=${String(this.duration)} @change=${(e: Event) => { this.duration = Number((e.target as HTMLSelectElement).value); }}>
          <option value="0">Until I resume</option>
          <option value="900000">15 minutes</option>
          <option value="3600000">1 hour</option>
        </select></label>` : nothing}
      <button type="button" ?disabled=${this.pending || !this.progress.activity.confirmed}
        @click=${() => void this.invoke()}>${resume ? 'Resume indexing' : 'Pause indexing'}</button>
    </div>
    <p role="status">${this.message}</p>`;
  }
}

if (!customElements.get('jf-indexing-controls')) customElements.define('jf-indexing-controls', IndexingControls);
