// SPDX-License-Identifier: Apache-2.0
import { css, html, nothing, type PropertyValues } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { authorizedFetch } from '../api/authorizedFetch.js';
import { getOperationClient, OperationError, type OperationInvocationSuccess } from '../operations/OperationClient.js';
import { requestAuthorization } from '../operations/authorizationBroker.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import { settingsV2Schema, type SettingsV2 } from '../../api/generated/schema-types/settings-v2.js';
import type { SettingsWitness } from '../../api/settingsAttempt.js';

export type SavedIndexingRules = {
  schedule: NonNullable<NonNullable<NonNullable<SettingsV2['ui']>['indexingPause']>['schedule']> | null;
  pauseOnBattery: boolean;
};

/** Field order on the wire is not ours: compare the rule pair by value. */
function sameRules(a: SavedIndexingRules, b: SavedIndexingRules): boolean {
  const key = (rules: SavedIndexingRules) => JSON.stringify([rules.pauseOnBattery, rules.schedule && [rules.schedule.enabled,
    [...(rules.schedule.days ?? [])].sort((x, y) => x - y), rules.schedule.startMinute, rules.schedule.endMinute]]);
  return key(a) === key(b);
}

/**
 * The reading-hours editor. Only the explicit rule pair is written, and it saves as it changes: a
 * choice on change, a time when its field is left. Each write carries the settings revision its draft
 * was based on, so a save made elsewhere since is a conflict rather than overwritten (B6').
 */
export class IndexingRulesEditor extends JfElement {
  static properties = {
    developer: { type: Boolean },
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
  declare developer: boolean;
  declare apiBase: string;
  declare host_: PluginHostApi | undefined;
  declare readOnly: boolean;
  /** Library serializes its settings writers and advances its own shared settings witness. */
  declare saveWithSettings: ((write: (operationKey: string) => Promise<OperationInvocationSuccess>) => Promise<void>) | undefined;
  private savedRules: SavedIndexingRules = { schedule: null, pauseOnBattery: false };
  /** The revision the saved rules were observed at; every write is based on it. */
  private savedWitness: SettingsWitness | null = null;
  declare private conflictingRules: SavedIndexingRules | null;
  private conflictWitness: SettingsWitness | null = null;
  private loadSerial = 0;
  /** A change arrived while a save was in flight; it is saved when that one settles. */
  private saveAgain = false;
  private get indexingRulesDirty(): boolean {
    const next = this.draftRules();
    return next !== null && !sameRules(next, this.savedRules);
  }
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
    if (Object.keys(patch).some(key => key !== 'pauseOnBattery')) this.indexingScheduleConfigured = true;
    this.indexingRules = { ...this.indexingRules, ...patch };
    this.indexingRulesMessage = '';
  }

  /** The draft as the rule pair it would save, or null while its times cannot be read. */
  private draftRules(): SavedIndexingRules | null {
    const draft = this.indexingRules;
    const minute = (value: string): number => {
      const parts = /^(\d{2}):(\d{2})$/.exec(value);
      return parts ? Number(parts[1]) * 60 + Number(parts[2]) : NaN;
    };
    const startMinute = minute(draft.start), endMinute = minute(draft.end);
    if (!Number.isInteger(startMinute) || !Number.isInteger(endMinute)) return null;
    return { schedule: this.indexingScheduleConfigured
      ? { enabled: draft.enabled, days: [...draft.days], startMinute, endMinute } : null,
      pauseOnBattery: draft.pauseOnBattery };
  }

  /** Why the draft cannot be saved, in the person's words; empty when it can. */
  private draftProblem(): string {
    const next = this.draftRules();
    if (!next) return 'Enter a start time and an end time.';
    if (next.schedule && next.schedule.startMinute === next.schedule.endMinute) return 'Start and end times must be different.';
    if (next.schedule?.enabled && !(next.schedule.days ?? []).length) return 'Choose at least one day or turn reading hours off.';
    return '';
  }

  private readonly submitIndexingRules = (event: Event): void => {
    event.preventDefault();
    void this.saveIndexingRules();
  };

  /** Save the draft as it now is; a change during a save is saved right after it. */
  private async saveIndexingRules(): Promise<void> {
    if (!this.indexingRulesLoaded || this.readOnly || this.conflictingRules) return;
    if (this.indexingRulesSaving) { this.saveAgain = true; return; }
    this.indexingRulesError = this.draftProblem();
    if (this.indexingRulesError || !this.indexingRulesDirty) return;
    this.indexingRulesSaving = true;
    this.indexingRulesMessage = '';
    ++this.loadSerial;
    try {
      do {
        this.saveAgain = false;
        if (this.draftProblem()) { this.indexingRulesError = this.draftProblem(); break; }
        if (!this.indexingRulesDirty || !await this.writeRules(this.draftRules()!)) break;
      } while (this.saveAgain && this.isConnected);
    } finally {
      this.indexingRulesSaving = false;
    }
  }

  /** One rule write on the observed revision; a revision moved only by other settings is taken up and tried again. */
  private async writeRules(next: SavedIndexingRules): Promise<boolean> {
    for (let attempt = 0; attempt < 3; attempt++) {
      const witness = this.savedWitness;
      if (!witness) { this.indexingRulesError = 'Could not save reading rules. Reload Library and try again.'; return false; }
      const write = (operationKey?: string) => getOperationClient(this.apiBase).invokeWithConsent('core.set-indexing-rules', {
        ...(operationKey ? { idempotencyKey: operationKey } : {}), args: { ...next, witness },
      }, { requestConsent: requestAuthorization });
      try {
        let receipt: unknown, usedKey: string | undefined;
        if (this.saveWithSettings) {
          await this.saveWithSettings(async key => { usedKey = key; const result = await write(key); receipt = result.structuredData?.['witness']; return result; });
        } else receipt = (await write()).structuredData?.['witness'];
        const observed = settingsV2Schema.shape.witness.safeParse(receipt);
        // Only this write's direct successor is known to hold exactly these rules; otherwise the next
        // write is refused and rebased after reading the saved rules.
        if (observed.success && observed.data && usedKey && observed.data.lastCommittedOperationKey === usedKey
            && observed.data.acceptedRevision === witness.acceptedRevision + 1) {
          this.savedWitness = observed.data;
        }
        this.savedRules = next;
        this.indexingRulesError = '';
        this.indexingRulesMessage = 'Reading rules saved.';
        return true;
      } catch (error) {
        const conflict = error instanceof OperationError && (error.httpStatus === 409 || error.errorCode === 'VERSION_CONFLICT');
        if (!conflict) {
          this.indexingRulesError = `Could not save reading rules. ${error instanceof Error ? error.message : String(error)}`;
          return false;
        }
        const observed = await this.observeSaved();
        if (!observed) return false;
        if (sameRules(observed.rules, next)) { this.savedRules = next; this.savedWitness = observed.witness; return true; }
        if (sameRules(observed.rules, this.savedRules)) { this.savedWitness = observed.witness; continue; }
        this.conflictingRules = observed.rules;
        this.conflictWitness = observed.witness;
        this.indexingRulesError = '';
        return false;
      }
    }
    this.indexingRulesError = 'Could not save reading rules: settings kept changing. Try the change again.';
    return false;
  }

  /**
   * Library's own settings write (a skip-rule save, Pause, Resume) committed directly after the
   * revision these rules were observed at: it did not touch them, so they hold at its successor too.
   */
  adoptOwnSuccessor(before: SettingsWitness, after: SettingsWitness): void {
    const at = this.savedWitness;
    if (at && at.acceptedRevision === before.acceptedRevision && at.lastCommittedOperationKey === before.lastCommittedOperationKey
        && after.acceptedRevision === before.acceptedRevision + 1) this.savedWitness = after;
  }

  private async observeSaved(): Promise<{ rules: SavedIndexingRules; witness: SettingsWitness } | null> {
    try {
      const res = this.host_ ? await this.host_.data.fetch('/api/settings/v2') : await authorizedFetch(`${this.apiBase}/api/settings/v2`);
      if (!res.ok) throw new Error(`Could not load reading rules (${res.status}).`);
      const data = settingsV2Schema.parse(await res.json());
      if (!data.witness) throw new Error('Saved reading rules did not include a revision.');
      const policy = data.ui?.indexingPause;
      return { rules: { schedule: policy?.schedule ? { enabled: policy.schedule.enabled ?? false, days: [...(policy.schedule.days ?? [])],
        startMinute: policy.schedule.startMinute ?? 540, endMinute: policy.schedule.endMinute ?? 1020 } : null,
        pauseOnBattery: policy?.pauseOnBattery ?? false }, witness: data.witness };
    } catch (error) {
      this.indexingRulesError = `Could not check the saved reading rules. ${error instanceof Error ? error.message : String(error)}`;
      return null;
    }
  }

  private readonly saveOnLeave = (event: FocusEvent): void => {
    if ((event.target as HTMLElement | null)?.matches?.('input[type=time]')) void this.saveIndexingRules();
  };

  private changeAndSave(patch: Partial<typeof this.indexingRules>): void {
    this.editIndexingRules(patch);
    void this.saveIndexingRules();
  }


  constructor() {
    super();
    this.developer = false;
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
    // Leaving Library leaves a time field too: its change is saved like any other.
    if (this.indexingRulesLoaded && this.indexingRulesDirty && !this.draftProblem()) void this.saveIndexingRules();
    ++this.loadSerial;
    super.disconnectedCallback();
  }

  protected override updated(changed: PropertyValues): void {
    super.updated(changed);
    if (this.indexingRuleFocus) void this.focusPendingIndexingRule();
  }

  private async loadSettings(mode: 'preserve' | 'discard' = 'preserve'): Promise<boolean> {
    const serial = ++this.loadSerial;
    try {
      const res = this.host_ ? await this.host_.data.fetch('/api/settings/v2')
        : await authorizedFetch(`${this.apiBase}/api/settings/v2`);
      if (!res.ok) throw new Error(`Could not load reading rules (${res.status}).`);
      const data = settingsV2Schema.parse(await res.json());
      if (serial !== this.loadSerial || !this.isConnected) return false;
      this.readOnly = data.settingsMode === 'in_memory';
      const policy = data.ui?.indexingPause;
      const schedule = policy?.schedule;
      const saved: SavedIndexingRules = { schedule: schedule ? { enabled: schedule.enabled ?? false, days: [...(schedule.days ?? [])],
        startMinute: schedule.startMinute ?? 540, endMinute: schedule.endMinute ?? 1020 } : null, pauseOnBattery: policy?.pauseOnBattery ?? false };
      const dirty = this.indexingRulesLoaded && this.indexingRulesDirty;
      if (mode === 'preserve' && dirty && !sameRules(saved, this.savedRules)) {
        this.conflictingRules = saved;
        this.conflictWitness = data.witness ?? null;
      }
      const adopt = mode === 'discard' || !dirty && !this.indexingRulesSaving;
      if (adopt) { this.savedRules = saved; this.savedWitness = data.witness ?? null; }
      if (adopt) {
        this.indexingScheduleConfigured = schedule != null;
        const time = (minutes: number) => `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;
        this.indexingRules = { enabled: schedule?.enabled ?? false,
          days: schedule?.days ? [...schedule.days] : [1, 2, 3, 4, 5, 6, 7],
          start: time(schedule?.startMinute ?? 540), end: time(schedule?.endMinute ?? 1020),
          pauseOnBattery: policy?.pauseOnBattery ?? false };
      }
      if (mode === 'discard') {
        this.conflictingRules = null;
        this.conflictWitness = null;
        this.indexingRulesError = '';
        this.indexingRulesMessage = '';
      }
      this.indexingRulesLoaded = true;
      return true;
    } catch (error) {
      if (serial === this.loadSerial) {
        const message = error instanceof Error ? error.message : String(error);
        this.indexingRulesError = message;
      }
      return false;
    }
  }

  /** "Changed elsewhere": keep the draft over the rules shown, or take the saved rules and write nothing. */
  private async resolveConflict(keepDraft: boolean): Promise<void> {
    if (this.indexingRulesSaving || this.readOnly || !this.conflictingRules) return;
    if (keepDraft) {
      // Consent covers exactly the rules displayed: the write is based on their revision, so a
      // further change elsewhere since is shown as a new conflict instead of overwritten.
      this.savedRules = this.conflictingRules;
      this.savedWitness = this.conflictWitness;
      this.conflictingRules = null;
      this.conflictWitness = null;
      await this.saveIndexingRules();
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
    return html`<section aria-label="Saved reading rules" role="alert">
      <p>Changed elsewhere. Your draft is kept. The saved rules are now:</p>
      <details open><summary>Current saved reading rules</summary>
      <p>${schedule?.enabled ? 'Only between' : 'Any time'}${schedule ? html` · ${time(schedule.startMinute ?? 540)}–${time(schedule.endMinute ?? 1020)}
        · ${(schedule.days ?? []).map(day => days[day - 1]).join(', ')}${!schedule.enabled ? ' (hours turned off; days and times kept)' : ''}` : nothing}
        · Pause while on battery: ${saved.pauseOnBattery ? 'on' : 'off'}</p></details>
      <div class="conflict-actions"><button type="button" ?disabled=${this.readOnly}
        @click=${() => void this.resolveConflict(false)}>Use the saved rules</button>
      <button type="button" ?disabled=${this.readOnly}
        @click=${() => void this.resolveConflict(true)}>Keep my draft</button></div>
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
    button { font: inherit; font-size: var(--font-size-sm); font-weight: 500; background: transparent; border: 0; color: var(--text-primary); padding: .4rem 0; cursor: pointer; }
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
      <h3 id="when-to-index" tabindex="-1">When to read files</h3>
      <form @submit=${this.submitIndexingRules} @focusout=${this.saveOnLeave} novalidate aria-label="Reading rules">
        <fieldset ?disabled=${!this.indexingRulesLoaded || this.readOnly}
          aria-label="When to read files" aria-describedby="indexing-rules-error">
          <div class="rule-choice"><label><input type="radio" name="indexing-hours" .checked=${!this.indexingRules.enabled}
            @change=${() => this.changeAndSave({ enabled: false })}>Any time</label></div>
          <div class="rule-choice"><label><input id="indexing-hours" type="radio" name="indexing-hours" .checked=${this.indexingRules.enabled}
            @change=${() => this.changeAndSave({ enabled: true })}>Only between</label>
            <label><span class="visually-hidden">Start time</span><input type="time" .value=${this.indexingRules.start} aria-describedby="indexing-local-time indexing-rules-error"
              @input=${(e: Event) => this.editIndexingRules({ start: (e.target as HTMLInputElement).value })}></label>
            and <label><span class="visually-hidden">End time</span><input type="time" .value=${this.indexingRules.end} aria-describedby="indexing-local-time indexing-rules-error"
              @input=${(e: Event) => this.editIndexingRules({ end: (e.target as HTMLInputElement).value })}></label>
            ${this.indexingRules.enabled && this.indexingRules.days.length !== 7 ? html`<span class="effective-days">${this.indexingRules.days.map(day => ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'][day - 1]).join(', ') || 'No days selected'}</span>` : nothing}
          </div>
          <div class="indexing-days" role="group" aria-label="Reading days" ?hidden=${!this.developer || !this.indexingRules.enabled}>
            ${['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'].map((day, index) => html`
              <label><input type="checkbox" .checked=${this.indexingRules.days.includes(index + 1)}
                @change=${(e: Event) => this.changeAndSave({ days: (e.target as HTMLInputElement).checked
                  ? [...this.indexingRules.days, index + 1].sort((a, b) => a - b)
                  : this.indexingRules.days.filter(d => d !== index + 1) })}>${day}</label>`)}
          </div>
          <label><input id="indexing-battery" type="checkbox" .checked=${this.indexingRules.pauseOnBattery}
            aria-describedby="indexing-battery-help"
            @change=${(e: Event) => this.changeAndSave({ pauseOnBattery: (e.target as HTMLInputElement).checked })}>Pause while on battery</label>
        </fieldset>
        <details><summary>About reading times</summary>
          <p id="indexing-local-time">Hours follow this computer's local time. An end time before the start continues into the next day. Turning hours off keeps your days and times.</p>
          <p id="indexing-battery-help">Applies only while this computer is known to run on battery. If the power source is unknown, this rule does not hold reading. Your preference is kept.</p>
        </details>
        ${this.renderSavedRules()}
        <p id="indexing-rules-error" role="alert">${this.indexingRulesError}</p>
        <p role="status">${this.indexingRulesMessage}</p>
        ${!this.indexingRulesLoaded ? html`<p>Waiting for saved reading rules.</p>` : nothing}
        ${this.readOnly ? html`<p>Settings are read-only in this session.</p>` : nothing}
      </form>
    </section>`;
  }
}
if (!customElements.get('jf-indexing-rules-editor')) customElements.define('jf-indexing-rules-editor', IndexingRulesEditor);
