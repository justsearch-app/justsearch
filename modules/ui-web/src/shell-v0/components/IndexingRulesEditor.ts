// SPDX-License-Identifier: Apache-2.0
import { css, html, nothing, type PropertyValues } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { authorizedFetch } from '../api/authorizedFetch.js';
import { getOperationClient, OperationError, type OperationInvocationSuccess } from '../operations/OperationClient.js';
import { requestAuthorization } from '../operations/authorizationBroker.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import { settingsV2Schema, type SettingsV2 } from '../../api/generated/schema-types/settings-v2.js';

export type SavedIndexingRules = {
  schedule: NonNullable<NonNullable<NonNullable<SettingsV2['ui']>['indexingPause']>['schedule']> | null;
  pauseOnBattery: boolean;
};

/** The indexing editor, extracted from Settings. Only the explicit rule pair is written. */
export class IndexingRulesEditor extends JfElement {
  static properties = {
    apiBase: { type: String, attribute: 'api-base' }, host_: { attribute: false },
    readOnly: { state: true },
    saveWithSettings: { attribute: false },
    indexingRules: { state: true },
    indexingRulesLoaded: { state: true },
    indexingRulesSaving: { state: true },
    indexingRulesError: { state: true },
    indexingRulesMessage: { state: true },
    conflictingRules: { state: true },
  };
  declare apiBase: string;
  declare host_: PluginHostApi | undefined;
  declare readOnly: boolean;
  /** Library serializes its two editors and advances their shared settings witness. */
  declare saveWithSettings: ((write: (operationKey: string) => Promise<OperationInvocationSuccess>, saved: SavedIndexingRules) => Promise<void>) | undefined;
  private savedRules: SavedIndexingRules = { schedule: null, pauseOnBattery: false };
  declare private conflictingRules: SavedIndexingRules | null;
  private loadSerial = 0;
  private indexingRulesDirty = false;
  private indexingScheduleConfigured = false;
  declare indexingRules: { enabled: boolean; days: number[]; start: string; end: string; pauseOnBattery: boolean };
  declare indexingRulesLoaded: boolean;
  declare indexingRulesSaving: boolean;
  declare indexingRulesError: string;
  declare indexingRulesMessage: string;

  private indexingRuleFocus: 'hours' | 'battery' | null = null;
  private arrivalScroll: ReturnType<typeof setTimeout> | undefined;

  async focusIndexingRule(control: 'hours' | 'battery'): Promise<void> {
    this.cancelIndexingRuleFocus();
    this.indexingRuleFocus = control;
    await this.focusPendingIndexingRule();
  }

  cancelIndexingRuleFocus(): void {
    this.indexingRuleFocus = null;
    clearTimeout(this.arrivalScroll);
  }

  private async focusPendingIndexingRule(): Promise<void> {
    await this.updateComplete;
    const control = this.indexingRuleFocus;
    if (!control || !this.indexingRulesLoaded) return;
    // Let the containing surface and its tabs finish their queued updates. A view
    // transition can suspend animation frames while its update callback is pending,
    // so arrival focus must not wait for a paint to become available.
    await new Promise<void>(resolve => setTimeout(resolve, 0));
    if (!this.isConnected || this.indexingRuleFocus !== control) return;
    this.indexingRuleFocus = null;
    const target = this.shadowRoot?.getElementById(control === 'hours' ? 'indexing-hours' : 'indexing-battery');
    const focusTarget = this.readOnly ? this.shadowRoot?.getElementById('when-to-index') : target;
    focusTarget?.focus({ preventScroll: true });
    if (!focusTarget) return;
    // Loading roots, tabs and finite view transitions can still move the section
    // after focus. Keep the control in view for their bounded settling interval.
    // Instant scrolling also respects reduced motion. Timers keep navigation free
    // to finish even while a view transition suspends animation frames.
    const deadline = Date.now() + 1000;
    let previousTop: number | undefined;
    const keepInView = () => {
      if (!this.isConnected || this.shadowRoot?.activeElement !== focusTarget) return;
      const top = focusTarget.getBoundingClientRect().top;
      if (top !== previousTop) focusTarget.scrollIntoView?.({ block: 'center', behavior: 'instant' });
      previousTop = focusTarget.getBoundingClientRect().top;
      if (Date.now() < deadline) this.arrivalScroll = setTimeout(keepInView, 50);
    };
    keepInView();
  }

  private editIndexingRules(patch: Partial<typeof this.indexingRules>): void {
    this.indexingRulesDirty = true;
    if (Object.keys(patch).some(key => key !== 'pauseOnBattery')) this.indexingScheduleConfigured = true;
    this.indexingRules = { ...this.indexingRules, ...patch };
    this.indexingRulesMessage = '';
  }

  private async saveIndexingRules(event: Event): Promise<void> {
    event.preventDefault();
    if (!this.indexingRulesLoaded || this.indexingRulesSaving || this.readOnly) return;
    const draft = this.indexingRules;
    const minute = (value: string): number => {
      const parts = /^(\d{2}):(\d{2})$/.exec(value);
      return parts ? Number(parts[1]) * 60 + Number(parts[2]) : NaN;
    };
    const startMinute = minute(draft.start), endMinute = minute(draft.end);
    this.indexingRulesError = '';
    this.indexingRulesMessage = '';
    if (!Number.isInteger(startMinute) || !Number.isInteger(endMinute)) {
      this.indexingRulesError = 'Enter a start time and an end time.';
    } else if (startMinute === endMinute) {
      this.indexingRulesError = 'Start and end times must be different.';
    } else if (draft.enabled && !draft.days.length) {
      this.indexingRulesError = 'Choose at least one day or turn indexing hours off.';
    }
    if (this.indexingRulesError) return;
    this.indexingRulesSaving = true;
    ++this.loadSerial;
    try {
      const next: SavedIndexingRules = { schedule: this.indexingScheduleConfigured
        ? { enabled: draft.enabled, days: [...draft.days], startMinute, endMinute } : null,
        pauseOnBattery: draft.pauseOnBattery };
      const write = (operationKey?: string) => getOperationClient(this.apiBase).invokeWithConsent('core.set-indexing-rules', {
        ...(operationKey ? { idempotencyKey: operationKey } : {}), args: next,
      }, { requestConsent: requestAuthorization });
      if (this.saveWithSettings) await this.saveWithSettings(write, this.savedRules); else await write();
      this.savedRules = next;
      this.indexingRulesDirty = false;
      this.conflictingRules = null;
      this.indexingRulesMessage = 'Indexing rules saved.';
    } catch (error) {
      const conflict = error instanceof OperationError && (error.httpStatus === 409 || error.errorCode === 'VERSION_CONFLICT');
      this.indexingRulesError = conflict
        ? `Settings changed before this save. Review your draft and save again. ${error.message}`
        : `Could not save indexing rules. ${error instanceof Error ? error.message : String(error)}`;
      if (conflict) await this.loadSettings('conflict');
    } finally {
      this.indexingRulesSaving = false;
    }
  }


  constructor() {
    super();
    this.apiBase = '';
    this.readOnly = false;
    this.indexingRules = { enabled: false, days: [1, 2, 3, 4, 5, 6, 7], start: '09:00', end: '17:00', pauseOnBattery: false };
    this.indexingRulesLoaded = false;
    this.indexingRulesSaving = false;
    this.indexingRulesError = '';
    this.indexingRulesMessage = '';
    this.conflictingRules = null;
  }

  override connectedCallback(): void {
    super.connectedCallback();
    void this.loadSettings();
  }

  override disconnectedCallback(): void {
    this.cancelIndexingRuleFocus();
    ++this.loadSerial;
    super.disconnectedCallback();
  }

  protected override updated(changed: PropertyValues): void {
    super.updated(changed);
    if (this.indexingRuleFocus) void this.focusPendingIndexingRule();
  }

  private async loadSettings(mode: 'preserve' | 'conflict' | 'discard' = 'preserve'): Promise<boolean> {
    const serial = ++this.loadSerial;
    try {
      const res = this.host_ ? await this.host_.data.fetch('/api/settings/v2')
        : await authorizedFetch(`${this.apiBase}/api/settings/v2`);
      if (!res.ok) throw new Error(`Could not load indexing rules (${res.status}).`);
      const data = settingsV2Schema.parse(await res.json());
      if (serial !== this.loadSerial || !this.isConnected) return false;
      this.readOnly = data.settingsMode === 'in_memory';
      const policy = data.ui?.indexingPause;
      const schedule = policy?.schedule;
      const saved = { schedule: schedule ?? null, pauseOnBattery: policy?.pauseOnBattery ?? false };
      if (mode === 'conflict' || mode === 'preserve' && this.indexingRulesDirty
          && JSON.stringify(saved) !== JSON.stringify(this.savedRules)) this.conflictingRules = saved;
      if (mode === 'discard' || !this.indexingRulesDirty && !this.indexingRulesSaving) this.savedRules = saved;
      if (mode === 'discard' || !this.indexingRulesDirty && !this.indexingRulesSaving) {
        this.indexingScheduleConfigured = schedule != null;
        const time = (minutes: number) => `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;
        this.indexingRules = { enabled: schedule?.enabled ?? false,
          days: schedule?.days ? [...schedule.days] : [1, 2, 3, 4, 5, 6, 7],
          start: time(schedule?.startMinute ?? 540), end: time(schedule?.endMinute ?? 1020),
          pauseOnBattery: policy?.pauseOnBattery ?? false };
      }
      if (mode === 'discard') {
        this.indexingRulesDirty = false;
        this.conflictingRules = null;
        this.indexingRulesError = '';
        this.indexingRulesMessage = 'Saved indexing rules loaded.';
      }
      this.indexingRulesLoaded = true;
      return true;
    } catch (error) {
      if (serial === this.loadSerial) {
        const message = error instanceof Error ? error.message : String(error);
        this.indexingRulesError = mode === 'conflict' ? `${this.indexingRulesError} ${message}` : message;
      }
      return false;
    }
  }

  private async resolveConflict(event: Event, keepDraft: boolean): Promise<void> {
    if (this.indexingRulesSaving || this.readOnly) return;
    if (keepDraft) {
      // Consent covers exactly the rules displayed in this review. The serialized
      // save guard must reject another writer's change since that observation.
      if (!this.conflictingRules) return;
      this.savedRules = this.conflictingRules;
      await this.saveIndexingRules(event);
      return;
    }
    this.indexingRulesSaving = true;
    await this.loadSettings('discard');
    this.indexingRulesSaving = false;
  }

  private renderSavedRules() {
    const saved = this.conflictingRules;
    if (!saved) return nothing;
    const schedule = saved.schedule;
    const days = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];
    const time = (minutes: number) => `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;
    return html`<section aria-label="Saved indexing rules">
      <p>Indexing times changed elsewhere. Your draft is kept. Current saved rules:</p>
      <details open><summary>Current saved indexing rules</summary>
      <p>${schedule?.enabled ? 'Only between' : 'Any time'}${schedule ? html` · ${time(schedule.startMinute ?? 540)}–${time(schedule.endMinute ?? 1020)}
        · ${(schedule.days ?? []).map(day => days[day - 1]).join(', ')}${!schedule.enabled ? ' (hours turned off; days and times kept)' : ''}` : nothing}
        · Pause while on battery: ${saved.pauseOnBattery ? 'on' : 'off'}</p></details>
      <div class="conflict-actions"><button type="button" ?disabled=${this.indexingRulesSaving || this.readOnly}
        @click=${(event: Event) => void this.resolveConflict(event, true)}>Save my draft</button>
      <button type="button" ?disabled=${this.indexingRulesSaving || this.readOnly}
        @click=${(event: Event) => void this.resolveConflict(event, false)}>Discard my draft</button></div>
    </section>`;
  }

  static styles = css`
    :host { display: block; min-width: 0; }
    h3 { margin: 0 0 .75rem; font-size: var(--font-size-xs); font-weight: 500; color: var(--text-secondary); }
    fieldset { border: 0; padding: 0; margin: 0; min-width: 0; }
    label { display: inline-flex; align-items: center; gap: .45rem; font-size: var(--font-size-sm); }
    .conflict-actions { display: flex; flex-wrap: wrap; gap: .5rem 1rem; }
    .rule-choice { display: flex; flex-wrap: wrap; gap: .5rem; align-items: center; margin-bottom: .8rem; }
    .indexing-days { display: flex; flex-wrap: wrap; gap: .5rem .9rem; margin: .5rem 0 1rem; }
    .indexing-days label { font-size: var(--font-size-xs); }
    .indexing-days[hidden] { display: none; }
    input { font: inherit; color: var(--text-primary); accent-color: var(--text-secondary); max-width: 100%; }
    input[type=time] { background: transparent; border: 0; border-bottom: 1px dotted var(--border-subtle); padding: .1rem; color-scheme: inherit; }
    button { font: inherit; font-size: var(--font-size-sm); background: transparent; border: 0; color: var(--text-primary); padding: .4rem 0; cursor: pointer; }
    button[aria-disabled=true] { opacity: .65; cursor: default; }
    button:focus-visible, input:focus-visible, summary:focus-visible { outline: 2px solid var(--focus-ring-color); outline-offset: 2px; }
    p, details { margin: .5rem 0; color: var(--text-secondary); font-size: var(--font-size-xs); overflow-wrap: anywhere; }
    p:empty { display: none; }
    summary { cursor: pointer; }
    #indexing-rules-error { color: var(--text-primary); }
    @media (forced-colors: active) {
      button, input { color: CanvasText; background: Canvas; border-color: CanvasText; }
      button:focus-visible, input:focus-visible, summary:focus-visible { outline-color: Highlight; }
    }
  `;

  override render() {
    return html`<section aria-labelledby="when-to-index">
      <h3 id="when-to-index" tabindex="-1">When to index</h3>
      <form @submit=${(event: Event) => void this.saveIndexingRules(event)} novalidate aria-label="Indexing rules">
        <fieldset ?disabled=${!this.indexingRulesLoaded || this.indexingRulesSaving || this.readOnly}
          aria-label="When to index" aria-describedby="indexing-rules-error">
          <div class="rule-choice"><label><input type="radio" name="indexing-hours" .checked=${!this.indexingRules.enabled}
            @change=${() => this.editIndexingRules({ enabled: false })}>Any time</label></div>
          <div class="rule-choice"><label><input id="indexing-hours" type="radio" name="indexing-hours" .checked=${this.indexingRules.enabled}
            @change=${() => this.editIndexingRules({ enabled: true })}>Only between</label>
            <label><span class="visually-hidden">Start time</span><input type="time" .value=${this.indexingRules.start} aria-describedby="indexing-local-time indexing-rules-error"
              @input=${(e: Event) => this.editIndexingRules({ start: (e.target as HTMLInputElement).value })}></label>
            and <label><span class="visually-hidden">End time</span><input type="time" .value=${this.indexingRules.end} aria-describedby="indexing-local-time indexing-rules-error"
              @input=${(e: Event) => this.editIndexingRules({ end: (e.target as HTMLInputElement).value })}></label>
          </div>
          <div class="indexing-days" role="group" aria-label="Indexing days" ?hidden=${!this.indexingRules.enabled}>
            ${['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'].map((day, index) => html`
              <label><input type="checkbox" .checked=${this.indexingRules.days.includes(index + 1)}
                @change=${(e: Event) => this.editIndexingRules({ days: (e.target as HTMLInputElement).checked
                  ? [...this.indexingRules.days, index + 1].sort((a, b) => a - b)
                  : this.indexingRules.days.filter(d => d !== index + 1) })}>${day}</label>`)}
          </div>
          <label><input id="indexing-battery" type="checkbox" .checked=${this.indexingRules.pauseOnBattery}
            aria-describedby="indexing-battery-help"
            @change=${(e: Event) => this.editIndexingRules({ pauseOnBattery: (e.target as HTMLInputElement).checked })}>Pause while on battery</label>
        </fieldset>
        <details><summary>About indexing times</summary>
          <p id="indexing-local-time">Hours follow this computer's local time. An end time before the start continues into the next day. Turning hours off keeps your days and times.</p>
          <p id="indexing-battery-help">Applies only while this computer is known to run on battery. If the power source is unknown, this rule does not hold indexing. Your preference is kept.</p>
        </details>
        <button type="submit" ?disabled=${!this.indexingRulesLoaded || this.readOnly}
          aria-disabled=${this.indexingRulesSaving ? 'true' : 'false'}>${this.indexingRulesSaving ? 'Saving indexing rules…' : 'Save indexing rules'}</button>
        ${this.renderSavedRules()}
        <p id="indexing-rules-error" role="alert">${this.indexingRulesError}</p>
        <p role="status">${this.indexingRulesMessage}</p>
        ${!this.indexingRulesLoaded ? html`<p>Waiting for saved indexing rules.</p>` : nothing}
        ${this.readOnly ? html`<p>Settings are read-only in this session.</p>` : nothing}
      </form>
    </section>`;
  }
}
if (!customElements.get('jf-indexing-rules-editor')) customElements.define('jf-indexing-rules-editor', IndexingRulesEditor);
