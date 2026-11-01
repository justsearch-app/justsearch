// SPDX-License-Identifier: Apache-2.0
import { css, html, nothing, type PropertyValues } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { authorizedFetch } from '../api/authorizedFetch.js';
import { getOperationClient, OperationError } from '../operations/OperationClient.js';
import { requestAuthorization } from '../operations/authorizationBroker.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import type { SettingsV2 } from '../../api/generated/schema-types/settings-v2.js';

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
  };
  declare apiBase: string;
  declare host_: PluginHostApi | undefined;
  declare readOnly: boolean;
  /** Library serializes its two editors and advances their shared settings witness. */
  declare saveWithSettings: ((write: (operationKey: string) => Promise<unknown>) => Promise<void>) | undefined;
  private loadSerial = 0;
  private indexingRulesDirty = false;
  private indexingScheduleConfigured = false;
  declare indexingRules: { enabled: boolean; days: number[]; start: string; end: string; pauseOnBattery: boolean };
  declare indexingRulesLoaded: boolean;
  declare indexingRulesSaving: boolean;
  declare indexingRulesError: string;
  declare indexingRulesMessage: string;

  private indexingRuleFocus: 'hours' | 'battery' | null = null;

  async focusIndexingRule(control: 'hours' | 'battery'): Promise<void> {
    this.indexingRuleFocus = control;
    await this.focusPendingIndexingRule();
  }

  cancelIndexingRuleFocus(): void { this.indexingRuleFocus = null; }

  private async focusPendingIndexingRule(): Promise<void> {
    await this.updateComplete;
    const control = this.indexingRuleFocus;
    if (!control || !this.indexingRulesLoaded) return;
    const target = this.shadowRoot?.getElementById(control === 'hours' ? 'indexing-hours' : 'indexing-battery');
    // Navigation can request focus before the containing surface becomes visible. Wait for
    // its layout frame before scrolling, and cancel if a newer request or navigation wins.
    await new Promise<void>(resolve => requestAnimationFrame(() => resolve()));
    if (!this.isConnected || this.indexingRuleFocus !== control) return;
    this.indexingRuleFocus = null;
    const focusTarget = this.readOnly ? this.shadowRoot?.getElementById('when-to-index') : target;
    focusTarget?.focus({ preventScroll: true });
    focusTarget?.scrollIntoView?.({ block: 'center', behavior: 'instant' });
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
      const write = (operationKey?: string) => getOperationClient(this.apiBase).invokeWithConsent('core.set-indexing-rules', {
        ...(operationKey ? { idempotencyKey: operationKey } : {}),
        args: { schedule: this.indexingScheduleConfigured
          ? { enabled: draft.enabled, days: [...draft.days], startMinute, endMinute } : null,
          pauseOnBattery: draft.pauseOnBattery },
      }, { requestConsent: requestAuthorization });
      if (this.saveWithSettings) await this.saveWithSettings(write); else await write();
      this.indexingRulesDirty = false;
      this.indexingRulesMessage = 'Indexing rules saved.';
    } catch (error) {
      this.indexingRulesError = error instanceof OperationError && (error.httpStatus === 409 || error.errorCode === 'VERSION_CONFLICT')
        ? `Settings changed before this save. Review your draft and save again. ${error.message}`
        : `Could not save indexing rules. ${error instanceof Error ? error.message : String(error)}`;
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
  }

  override connectedCallback(): void {
    super.connectedCallback();
    void this.loadSettings();
  }

  override disconnectedCallback(): void {
    this.cancelIndexingRuleFocus();
    super.disconnectedCallback();
  }

  protected override updated(changed: PropertyValues): void {
    super.updated(changed);
    if (this.indexingRuleFocus) void this.focusPendingIndexingRule();
  }

  private async loadSettings(): Promise<void> {
    const serial = ++this.loadSerial;
    try {
      const res = this.host_ ? await this.host_.data.fetch('/api/settings/v2')
        : await authorizedFetch(`${this.apiBase}/api/settings/v2`);
      if (!res.ok) throw new Error(`Could not load indexing rules (${res.status}).`);
      const data = await res.json() as SettingsV2;
      if (serial !== this.loadSerial) return;
      this.readOnly = data.settingsMode === 'in_memory';
      if (!this.indexingRulesDirty && !this.indexingRulesSaving) {
        const policy = data.ui?.indexingPause;
        const schedule = policy?.schedule;
        this.indexingScheduleConfigured = schedule != null;
        const time = (minutes: number) => `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;
        this.indexingRules = { enabled: schedule?.enabled ?? false,
          days: schedule?.days ? [...schedule.days] : [1, 2, 3, 4, 5, 6, 7],
          start: time(schedule?.startMinute ?? 540), end: time(schedule?.endMinute ?? 1020),
          pauseOnBattery: policy?.pauseOnBattery ?? false };
      }
      this.indexingRulesLoaded = true;
    } catch (error) {
      if (serial === this.loadSerial) this.indexingRulesError = error instanceof Error ? error.message : String(error);
    }
  }

  static styles = css`
    :host { display: block; min-width: 0; }
    h3 { margin: 0 0 .75rem; font-size: 12px; font-weight: 500; color: var(--text-secondary); }
    fieldset { border: 0; padding: 0; margin: 0; min-width: 0; }
    label { display: inline-flex; align-items: center; gap: .45rem; font-size: 14px; }
    .rule-choice { display: flex; flex-wrap: wrap; gap: .5rem; align-items: center; margin-bottom: .8rem; }
    .indexing-days { display: flex; flex-wrap: wrap; gap: .5rem .9rem; margin: .5rem 0 1rem; }
    .indexing-days label { font-size: 12px; }
    .indexing-days[hidden] { display: none; }
    input { font: inherit; color: var(--text-primary); accent-color: var(--accent-tint); max-width: 100%; }
    input[type=time] { background: transparent; border: 0; border-bottom: 1px dotted var(--border-subtle); padding: .1rem; color-scheme: inherit; }
    button { font: inherit; font-size: 14px; background: transparent; border: 0; color: var(--text-primary); padding: .4rem 0; cursor: pointer; }
    button[aria-disabled=true] { opacity: .65; cursor: default; }
    button:focus-visible, input:focus-visible, summary:focus-visible { outline: 2px solid var(--accent-tint); outline-offset: 2px; }
    p, details { margin: .5rem 0; color: var(--text-secondary); font-size: 12px; overflow-wrap: anywhere; }
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
        <p id="indexing-rules-error" role="alert">${this.indexingRulesError}</p>
        <p role="status">${this.indexingRulesMessage}</p>
        ${!this.indexingRulesLoaded ? html`<p>Waiting for saved indexing rules.</p>` : nothing}
        ${this.readOnly ? html`<p>Settings are read-only in this session.</p>` : nothing}
      </form>
    </section>`;
  }
}
if (!customElements.get('jf-indexing-rules-editor')) customElements.define('jf-indexing-rules-editor', IndexingRulesEditor);
