// SPDX-License-Identifier: Apache-2.0
import { SignalWatcher } from '@lit-labs/signals';
import { css, html, nothing, type PropertyValues } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { selectStyles } from '../primitives/selectStyles.js';
import { selectIndexingProgress, type IndexingProgress } from '../state/indexingProgress.js';
import { getOperationClient, type OperationInvocationSuccess } from '../operations/OperationClient.js';
import { requestSurfaceNavigation, requestIndexingRuleNavigation } from '../controllers/navigateRequest.js';
import { icon } from './Icon.js';
import { requestAuthorization } from '../operations/authorizationBroker.js';

/** Shared policy actions. Displayed state always comes from the status projection. */
class IndexingControls extends SignalWatcher(JfElement) {
  static properties = {
    progress: { attribute: false }, apiBase: { type: String, attribute: 'api-base' },
    compact: { type: Boolean, reflect: true },
    toolbar: { type: Boolean, reflect: true }, menuOpen: { state: true },
    allowPause: { type: Boolean, attribute: 'allow-pause' },
    entry: { type: Boolean, reflect: true },
    duration: { state: true }, pending: { state: true }, message: { state: true },
    saveWithSettings: { attribute: false },
  };
  declare progress: IndexingProgress;
  declare apiBase: string;
  declare compact: boolean;
  declare toolbar: boolean;
  declare menuOpen: boolean;
  declare allowPause: boolean;
  /**
   * An entry's action in the status panel (fm-15): text actions like the panel's own, and Pause reading
   * pauses until resumed, as Library's "Until I resume" does; the durations stay in Library's menu.
   */
  declare entry: boolean;
  declare duration: number;
  declare pending: boolean;
  declare message: string;
  /**
   * Library's shared settings write chain (R23): it bounds the request, cancels it through `signal` at
   * the bound and answers with the timeout; a late answer settles nothing.
   */
  declare saveWithSettings: ((write: (operationKey: string, signal: AbortSignal) => Promise<OperationInvocationSuccess>) => Promise<void>) | undefined;

  // Retain the last observed action through uncertain polls; page controls also retain it while focused.
  // This is control identity, never a claim that indexing is paused or running.
  private action: 'pause' | 'resume' | 'settings' | 'hours' | 'battery' | null = null;
  private awaitingStatus = false;
  private focused = false;
  private requestedResume = false;

  private get reasons(): readonly string[] {
    const pause = this.progress.pause;
    return pause?.reasons?.length ? pause.reasons : pause ? [pause.reason] : [];
  }

  private get remainingRules(): string {
    return [this.reasons.includes('schedule') ? 'reading hours' : '',
      this.reasons.includes('on_battery') ? 'battery power' : ''].filter(Boolean).join(' and ');
  }

  constructor() {
    super();
    this.progress = selectIndexingProgress(null, false, 0, []);
    this.apiBase = '';
    this.allowPause = false;
    this.entry = false;
    this.compact = false;
    this.toolbar = false;
    this.menuOpen = false;
    this.duration = 0;
    this.pending = false;
    this.message = '';
  }

  static styles = [selectStyles, css`
    :host { display: inline-block; max-width: 100%; min-width: 0; }
    .actions { display: flex; flex-wrap: wrap; align-items: center; gap: .4rem; }
    label { display: flex; flex-wrap: wrap; align-items: center; gap: .35rem; font-size: var(--font-size-xs); }
    button, select {
      font: inherit; font-size: var(--font-size-xs); color: var(--text-primary);
      background: var(--surface-1); border: 1px solid var(--border-default);
      border-radius: 8px; padding: .3rem .45rem; min-height: 1.75rem; max-width: 100%;
    }
    button { cursor: pointer; font-weight: 500; }
    button[aria-disabled="true"] { opacity: .65; cursor: default; }
    button:focus-visible, select:focus-visible { outline: 2px solid var(--focus-ring-color); outline-offset: 2px; }
    p { margin: 0; font-size: var(--font-size-xs); overflow-wrap: anywhere; }
    p:not(:empty) { margin-top: .25rem; }
    :host([compact]) button { border: 0; background: transparent; }
    :host([compact]) select { border: 0; }
    .pause-menu { position: relative; }
    .pause-menu > button { display: inline-flex; align-items: center; gap: .4rem; }
    /* "Pause reading" in a tab row is a text action: body size, like the actions beside it. */
    :host([toolbar]) .pause-menu > button { font-size: var(--font-size-sm); }
    /* A transient overlay like the shared menu: above the page's own layers, such as Browse's sticky column heads. */
    .pause-options { position: absolute; top: 100%; right: 0; z-index: var(--z-overlay-transient); min-width: 11rem;
      padding: .35rem; background: var(--surface-1); border: 1px solid var(--border-subtle); }
    .pause-options button { display: block; width: 100%; text-align: start; }
    .pause-options button:hover { background: var(--surface-2); }
    :host([compact]) select { font-size: var(--font-size-xs); }
    /* fm-15 — in a status panel entry the controls are its text actions, as the entry's own. */
    :host([entry]) { display: block; margin-top: var(--jf-space-2); }
    :host([entry]) .actions { gap: var(--jf-space-3); }
    :host([entry]) button { font-size: var(--font-size-sm); border: 0; background: transparent; padding: var(--jf-space-1); min-height: 0; border-radius: 0; }
    :host([entry]) button:hover { text-decoration: underline; }
    :host([entry]) button:focus-visible { outline-width: var(--frame-focus-width); outline-offset: var(--jf-space-1); }
    @media (forced-colors: active) {
      button, select { color: ButtonText; background: ButtonFace; border-color: ButtonText; }
      button:focus-visible, select:focus-visible { outline-color: Highlight; }
    }
  `];

  protected override willUpdate(changed: PropertyValues): void {
    if (this.toolbar) {
      if (!this.action || !this.pending && this.progress.activity.confirmed) {
        this.action = this.reasons.includes('user_paused') ? 'resume' : 'pause';
      }
    } else if (!this.toolbar && this.progress.activity.confirmed && this.progress.pause?.reason === 'settings_recovery') {
      this.action = 'settings';
    } else if (!this.focused && !this.pending && this.progress.activity.confirmed) {
      this.action = this.progress.pause?.reason === 'settings_recovery' ? 'settings'
        : this.reasons.includes('user_paused') ? 'resume'
          : this.reasons.includes('schedule') ? 'hours' : this.reasons.includes('on_battery') ? 'battery'
            : this.progress.pause ? null : this.allowPause ? 'pause' : null;
    }
    if (!this.pending && this.awaitingStatus && this.progress.activity.confirmed
      && (changed.has('progress') || (this.requestedResume && !this.reasons.includes('user_paused')))) {
      if (this.requestedResume && (this.reasons.includes('user_paused')
        || !this.progress.pause && !['indexing', 'enriching', 'ready'].includes(this.progress.phase))) return;
      this.message = this.action !== 'resume' && this.requestedResume && this.remainingRules
        ? `Manual pause cleared. Reading is still held by ${this.remainingRules}.` : '';
      this.awaitingStatus = false;
    }
  }

  private get unavailableReason(): string {
    if (this.pending) return 'Request in progress. Waiting for the Engine.';
    if (!this.progress.activity.confirmed) return this.toolbar && this.action === 'pause' ? 'Checking reading status.' : '';
    if (this.action === 'resume' && !this.progress.pause) return 'Reading is already running.';
    if (this.action === 'resume' && this.progress.pause?.reason === 'settings_recovery') {
      return 'Settings recovery requires confirmation of the reset in Settings.';
    }
    if (this.action === 'resume' && !this.reasons.includes('user_paused')) {
      return `Manual pause is cleared.${this.remainingRules ? ` Reading is still held by ${this.remainingRules}.` : ''}`;
    }
    if (!this.toolbar && this.action === 'pause' && this.progress.pause) return 'Reading is already on hold.';
    return '';
  }

  private async invoke(): Promise<void> {
    if (this.unavailableReason || !this.action) return;
    if (this.action === 'settings') {
      requestSurfaceNavigation('core.settings-surface', { focusSettingsRecovery: true });
      return;
    }
    if (this.action === 'hours' || this.action === 'battery') {
      this.changeRule(this.action);
      return;
    }
    const resume = this.action === 'resume';
    const args = !resume && this.duration > 0 ? { untilEpochMs: Date.now() + this.duration } : {};
    this.pending = true;
    this.message = '';
    this.awaitingStatus = false;
    try {
      const write = (operationKey?: string, signal?: AbortSignal) => getOperationClient(this.apiBase).invokeWithConsent(
        resume ? 'core.indexing-resume' : 'core.indexing-pause', { args, ...(operationKey ? { idempotencyKey: operationKey } : {}) },
        { requestConsent: requestAuthorization, ...(signal ? { signal } : {}) },
      );
      if (this.saveWithSettings) await this.saveWithSettings(write); else await write();
      this.requestedResume = resume;
      this.awaitingStatus = true;
      this.message = resume ? 'Resuming. Waiting for status to confirm the request.' : 'Waiting for status to confirm the request.';
    } catch (error: unknown) {
      this.message = `Could not confirm the reading request. Check the current status before trying again. ${error instanceof Error ? error.message : ''}`;
    } finally {
      this.pending = false;
    }
  }

  private changeRule(control: 'hours' | 'battery'): void {
    requestIndexingRuleNavigation(control);
  }

  override render() {
    if (!this.action) return nothing;
    const resume = this.action === 'resume';
    const unavailable = this.unavailableReason;
    if (this.toolbar) return html`<div class="pause-menu" @focusin=${() => { this.focused = true; }}
      @focusout=${(event: FocusEvent) => {
        this.focused = event.relatedTarget instanceof Node && !!this.shadowRoot?.contains(event.relatedTarget);
        this.requestUpdate();
      }} @keydown=${(event: KeyboardEvent) => {
      if (event.key === 'Escape' && this.menuOpen) {
        this.menuOpen = false;
        this.shadowRoot?.querySelector<HTMLButtonElement>('#pause-toggle')?.focus();
      }
    }}><button id="pause-toggle" type="button" aria-expanded=${!resume && this.menuOpen ? 'true' : 'false'}
      aria-controls="pause-options" aria-disabled=${unavailable ? 'true' : 'false'}
      title=${[unavailable, this.message].filter(Boolean).join(' ') || nothing} aria-describedby="control-state"
      @click=${() => {
        if (unavailable) return;
        if (resume) void this.invoke(); else this.menuOpen = !this.menuOpen;
      }}>${icon({ name: resume ? 'play' : 'pause', size: 14 })}${resume ? 'Resume reading' : 'Pause reading'}
      ${!resume ? icon({ name: 'chevron-down', size: 12 }) : nothing}</button>
      ${!resume && this.menuOpen ? html`<div id="pause-options" class="pause-options">
        ${[[0, 'Until I resume'], [900000, '15 minutes'], [3600000, '1 hour']].map(([duration, label]) => html`
          <button type="button" @click=${() => {
            this.duration = Number(duration); this.menuOpen = false;
            this.shadowRoot?.querySelector<HTMLButtonElement>('#pause-toggle')?.focus();
            void this.invoke();
          }}>${label}</button>`)}
      </div>` : nothing}</div><p id="control-state" class="visually-hidden" role="status">${[unavailable, this.message].filter(Boolean).join(' ')}</p>`;
    return html`<div class="actions" aria-busy=${this.pending ? 'true' : 'false'}
      @focusin=${() => { this.focused = true; }}
      @focusout=${(event: FocusEvent) => {
        this.focused = event.relatedTarget instanceof Node && !!this.shadowRoot?.contains(event.relatedTarget);
        this.requestUpdate();
      }}>
      ${this.action === 'pause' && !this.entry ? html`<label><span class=${this.compact ? 'visually-hidden' : ''}>Pause duration</span>
        <select aria-label="Pause duration"
          .value=${String(this.duration)} @change=${(e: Event) => { this.duration = Number((e.target as HTMLSelectElement).value); }}>
          <option value="0">Until I resume</option>
          <option value="900000">15 minutes</option>
          <option value="3600000">1 hour</option>
        </select></label>` : nothing}
      <button type="button" aria-disabled=${unavailable ? 'true' : 'false'} aria-describedby="control-state"
        @click=${() => void this.invoke()}>${this.action === 'settings' ? 'Open Settings at Reset to defaults' : this.action === 'hours' ? this.compact ? 'Change when to read files' : 'Change hours' : this.action === 'battery' ? this.compact ? 'Change when to read files' : 'Change battery setting' : resume ? 'Resume reading' : 'Pause reading'}</button>
      ${!this.compact && this.action !== 'settings' && this.action !== 'hours' && this.reasons.includes('schedule')
        ? html`<button type="button" @click=${() => this.changeRule('hours')}>Change hours</button>` : nothing}
      ${!this.compact && this.action !== 'settings' && this.action !== 'battery' && this.reasons.includes('on_battery')
        ? html`<button type="button" @click=${() => this.changeRule('battery')}>Change battery setting</button>` : nothing}
      ${this.compact && resume && this.remainingRules ? html`<button type="button"
        @click=${() => this.changeRule(this.reasons.includes('schedule') ? 'hours' : 'battery')}>Change when to read files</button>` : nothing}
    </div>
    <p id="control-state" role="status">${[unavailable, this.message].filter(Boolean).join(' ')}</p>`;
  }
}

if (!customElements.get('jf-indexing-controls')) customElements.define('jf-indexing-controls', IndexingControls);
