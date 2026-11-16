// SPDX-License-Identifier: Apache-2.0
import { css, html, nothing, type PropertyValues } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { authorizedFetch } from '../api/authorizedFetch.js';
import { getOperationClient, OperationError, type OperationInvocationSuccess } from '../operations/OperationClient.js';
import { requestAuthorization } from '../operations/authorizationBroker.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import { settingsV2Schema, type SettingsV2 } from '../../api/generated/schema-types/settings-v2.js';
import { requireSettingsWitness, type SettingsWitness } from '../../api/settingsAttempt.js';
import { createOperationKey } from '../../api/operationKey.js';
import { keepUnsettled, MAY_BE_LOST, readUnsettled, SaveQueue, type SendOutcome } from '../state/saveQueue.js';
import { refreshStatusNow } from '../state/aiStateStore.js';
import type { IndexingProgress } from '../state/indexingProgress.js';
import { z } from 'zod';

const NOTHING_WAITING = 'Nothing is waiting to be read.';

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
 * R24 — this window's unsettled reading-rule saves, kept in sessionStorage across a reload: the rules
 * and revision they are based on, the captured rule pairs, and the head's sent write (key and revision).
 */
const UNSETTLED_READING_RULES = 'justsearch.library.unsettledReadingRules';
const keptWitnessSchema = z.object({ acceptedRevision: z.number(), lastCommittedOperationKey: z.string().nullable() });
const keptRulesSchema = z.object({
  schedule: z.object({ enabled: z.boolean(), days: z.array(z.number().int().min(1).max(7)),
    startMinute: z.number().int().min(0).max(1439), endMinute: z.number().int().min(0).max(1439) }).nullable(),
  pauseOnBattery: z.boolean(),
});
const unsettledReadingRulesSchema = z.object({
  base: z.object({ rules: keptRulesSchema, witness: keptWitnessSchema }),
  snapshots: z.array(keptRulesSchema).min(1),
  sent: z.object({ key: z.string(), witness: keptWitnessSchema }).nullable(),
});
type UnsettledReadingRules = { base: { rules: SavedIndexingRules; witness: SettingsWitness }; snapshots: SavedIndexingRules[];
  sent: { key: string; witness: SettingsWitness } | null };

function readUnsettledReadingRules(): UnsettledReadingRules | null {
  const parsed = unsettledReadingRulesSchema.safeParse(readUnsettled(UNSETTLED_READING_RULES));
  if (!parsed.success) return null;
  try {
    const { base, snapshots, sent } = parsed.data;
    return { base: { rules: base.rules, witness: requireSettingsWitness(base.witness) },
      snapshots: snapshots.map(snapshot => Object.freeze(snapshot)),
      sent: sent ? { key: sent.key, witness: requireSettingsWitness(sent.witness) } : null };
  } catch {
    return null;
  }
}

/** R18/R22 — how long one write, every network step of it, runs before its outcome is called unknown. */
const WRITE_BOUND_MS = 10_000;

/** The bound reached: the write's outcome is unknown. */
class WriteBoundReached extends OperationError {
  constructor() { super('The reading rules were not confirmed saved yet.', 'UNKNOWN'); }
}

/** `work`, or a rejection once `signal` aborts: a step past the bound settles nothing. */
function withinBound<T>(signal: AbortSignal, work: Promise<T>): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const abort = () => reject(new WriteBoundReached());
    if (signal.aborted) { abort(); return; }
    signal.addEventListener('abort', abort, { once: true });
    work.then(resolve, reject).finally(() => signal.removeEventListener('abort', abort));
  });
}

/** A sent write whose answer was lost: it may or may not have been saved. */
function outcomeUnknown(error: unknown): boolean {
  return !(error instanceof OperationError)
    || ['NETWORK_ERROR', 'SERIALIZATION_ERROR', 'HANDLER_ERROR', 'UNKNOWN'].includes(error.errorClass)
    || [502, 503, 504].includes(error.httpStatus ?? 0);
}

/**
 * The reading-hours editor. Only the explicit rule pair is written, and it saves as it changes: a
 * choice on change, a time when its field is left. Each change is captured then and saved in order
 * (R11). Each write carries the settings revision its draft was based on, so a save made elsewhere
 * since is a conflict rather than overwritten (B6').
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
    readWaiting: { attribute: false },
    readNowSending: { state: true },
    readNowStarted: { state: true },
    readNowNote: { state: true },
  };
  /** Slice 6c — from fresh status: whether a reading rule alone holds files, and the Engine's release. */
  declare readWaiting: IndexingProgress['readWaiting'];
  declare private readNowSending: boolean;
  /** This window asked and the Engine started (or joined) the release; kept until status shows its end. */
  declare private readNowStarted: boolean;
  declare private readNowNote: string;
  private readNowSawActive = false;
  private readNowQuietUpdates = 0;
  private readNowHadFocus = false;
  declare developer: boolean;
  declare apiBase: string;
  declare host_: PluginHostApi | undefined;
  declare readOnly: boolean;
  /** Library serializes its settings writers and advances its own shared settings witness. */
  /** Library's shared settings write chain; `bound` covers the wait for it too (R23). */
  declare saveWithSettings: ((write: (operationKey: string) => Promise<OperationInvocationSuccess>, operationKey: string,
    bound: AbortSignal) => Promise<void>) | undefined;
  private savedRules: SavedIndexingRules = { schedule: null, pauseOnBattery: false };
  /** The revision the saved rules were observed at; every write is based on it. */
  private savedWitness: SettingsWitness | null = null;
  declare private conflictingRules: SavedIndexingRules | null;
  private conflictWitness: SettingsWitness | null = null;
  private loadSerial = 0;
  /** R11 — the rule pairs captured by save-triggering events, saved in order; it outlives leaving Library. */
  private readonly queue = new SaveQueue<SavedIndexingRules>(next => this.sendRules(next), () => {
    this.indexingRulesSaving = this.queue.sending;
    this.keepUnsettledRules();
    this.requestUpdate();
  });
  /** R24 — the head's write while it is out: a reload before its answer settles it with this key. */
  private inFlight: { key: string; witness: SettingsWitness } | null = null;
  private restoreChecked = false;
  /** R27 — the unsettled changes could not be kept for a reload; tried again on each queue change. */
  private checkpointLost = false;
  /** R25 — the control the person last changed, for "Keep my draft". */
  private lastControl: HTMLElement | null = null;
  /**
   * R8 — the queue head's sent write whose outcome is unknown: its key and base revision. It is sent
   * again with the same key and bytes, and settled, before anything newer; until then "Not sure this saved".
   */
  private retained: { key: string; witness: SettingsWitness } | null = null;
  /** The rule pair saved once every captured change is: what the draft is compared with. */
  private get queuedRules(): SavedIndexingRules {
    return this.queue.snapshots.at(-1) ?? this.savedRules;
  }
  private get indexingRulesDirty(): boolean {
    const next = this.draftRules();
    return next !== null && !sameRules(next, this.queuedRules);
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

  /**
   * A save-triggering event (a choice, leaving a time field, leaving Library, Try again): the draft as it
   * is now is captured and queued behind any earlier change (R11). Nothing reads the draft later.
   */
  private saveIndexingRules(): Promise<void> {
    if (!this.indexingRulesLoaded || this.readOnly) return Promise.resolve();
    const problem = this.draftProblem();
    if (!this.queue.uncertain) this.indexingRulesError = problem;
    if (problem || !this.indexingRulesDirty) return this.queue.resume();
    ++this.loadSerial;
    this.indexingRulesMessage = '';
    return this.queue.push(Object.freeze(structuredClone(this.draftRules()!)));
  }

  /**
   * One captured rule pair, written on the revision the saved rules were observed at; a revision moved
   * only by other settings is taken up and the same pair tried again (B11). A retained write whose answer
   * was lost is sent again with its own key and base revision (same bytes).
   *
   * R22/R23 — every step of it (the wait for Library's shared settings chain, the send, retries,
   * admission waits, follow-up polls, the re-read after a refusal) runs under one bound, started when
   * the change is taken up. At the bound the steps are cancelled, the shared settings chain is freed,
   * and the outcome is unknown: the snapshot stays queued, a sent write keeps its key and bytes, and a
   * late answer settles nothing.
   */
  private async sendRules(next: SavedIndexingRules): Promise<SendOutcome> {
    if (this.conflictingRules) return 'stopped';
    const bound = new AbortController();
    const timer = setTimeout(() => bound.abort(), WRITE_BOUND_MS);
    try {
      return await this.sendRulesWithin(next, bound.signal);
    } finally {
      clearTimeout(timer);
      bound.abort();
    }
  }

  private async sendRulesWithin(next: SavedIndexingRules, signal: AbortSignal): Promise<SendOutcome> {
    for (let attempt = 0; attempt < 3; attempt++) {
      const same = this.retained;
      const witness = same?.witness ?? this.savedWitness;
      if (!witness) { this.indexingRulesError = 'Could not save reading rules. Reload Library and try again.'; return 'stopped'; }
      const operationKey = same?.key ?? createOperationKey();
      let sent = false;
      const request = { idempotencyKey: operationKey, args: { ...next, witness } };
      const invoke = () => withinBound(signal, getOperationClient(this.apiBase).invokeWithConsent('core.set-indexing-rules', request,
        { requestConsent: requestAuthorization, signal }));
      // R14 — a replay of an attempt not finished yet (ACCEPTED, RUNNING) carries no witness and is not a save.
      const finished = (result: OperationInvocationSuccess) => {
        const state = result.structuredData?.['state'];
        return typeof state !== 'string' || state === 'COMPLETE';
      };
      const write = async (): Promise<OperationInvocationSuccess> => {
        signal.throwIfAborted();
        sent = true;
        this.inFlight = { key: operationKey, witness };
        this.keepUnsettledRules();
        const first = await invoke();
        return finished(first) ? first : this.followRunning(invoke, finished, signal);
      };
      try {
        let receipt: unknown;
        if (this.saveWithSettings) {
          await this.saveWithSettings(async () => { const result = await write(); receipt = result.structuredData?.['witness']; return result; },
            operationKey, signal);
        } else receipt = (await write()).structuredData?.['witness'];
        const observed = settingsV2Schema.shape.witness.safeParse(receipt);
        this.retained = null;
        this.inFlight = null;
        // Only this write's direct successor is known to hold exactly these rules; otherwise the next
        // write is refused and rebased after reading the saved rules.
        if (observed.success && observed.data && observed.data.lastCommittedOperationKey === operationKey
            && observed.data.acceptedRevision === witness.acceptedRevision + 1) {
          this.savedWitness = observed.data;
        }
        this.savedRules = next;
        this.indexingRulesError = '';
        this.indexingRulesMessage = 'Reading rules saved.';
        return 'saved';
      } catch (error) {
        this.inFlight = null;
        const conflict = error instanceof OperationError && (error.httpStatus === 409 || error.errorCode === 'VERSION_CONFLICT');
        if (!conflict) {
          if (!sent && signal.aborted) {
            // The bound came while waiting for the shared chain: nothing new went out, and a write
            // retained from before keeps its key and bytes.
            this.indexingRulesError = '';
            return 'uncertain';
          }
          if (sent && outcomeUnknown(error)) {
            // The answer was lost: keep the exact write to settle before anything newer.
            this.retained = { key: operationKey, witness };
            this.indexingRulesError = '';
            return 'uncertain';
          }
          this.retained = null;
          this.indexingRulesError = `Could not save reading rules. ${error instanceof Error ? error.message : String(error)}`;
          return 'dropped';
        }
        // Refused on its revision: that write was not saved.
        this.retained = null;
        const observed = await this.observeSaved(signal);
        if (observed === 'bound') { this.indexingRulesError = ''; return 'uncertain'; }
        if (!observed) return 'stopped';
        if (sameRules(observed.rules, next)) { this.savedRules = next; this.savedWitness = observed.witness; return 'saved'; }
        if (sameRules(observed.rules, this.savedRules)) { this.savedWitness = observed.witness; continue; }
        this.conflictingRules = observed.rules;
        this.conflictWitness = observed.witness;
        this.indexingRulesError = '';
        return 'stopped';
      }
    }
    this.indexingRulesError = 'Could not save reading rules: settings kept changing. Try the change again.';
    return 'stopped';
  }

  /**
   * R14/R18 — the same key and bytes are sent again until the attempt completes or is refused, within
   * the write's bound: a stalled call or an admission wait is abandoned at it, and the write's outcome is
   * then unknown (its queue head keeps key and bytes). An answer arriving after the bound is ignored.
   */
  private async followRunning(invoke: () => Promise<OperationInvocationSuccess>,
      finished: (result: OperationInvocationSuccess) => boolean, signal: AbortSignal): Promise<OperationInvocationSuccess> {
    for (;;) {
      await withinBound(signal, new Promise(resolve => setTimeout(resolve, 250)));
      const result = await invoke();
      if (finished(result)) return result;
    }
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

  /** R24 — what is not settled yet, kept in this window's sessionStorage; removed once nothing is. */
  private keepUnsettledRules(): void {
    const snapshots = this.queue.snapshots;
    const kept = keepUnsettled(UNSETTLED_READING_RULES, snapshots.length && this.savedWitness ? {
      base: { rules: this.savedRules, witness: this.savedWitness }, snapshots, sent: this.retained ?? this.inFlight } : null);
    const lost = snapshots.length > 0 && !(kept && this.savedWitness);
    if (lost !== this.checkpointLost) { this.checkpointLost = lost; this.requestUpdate(); }
  }

  /**
   * R24 — after a reload, the changes this window had not settled come back on the rules and revision
   * they were based on, the latest as the draft. A write that was out is sent again first with its same
   * key and bytes; the rest wait for Try again ("Your changes are not saved yet").
   */
  private restoreUnsettledRules(): void {
    const kept = readUnsettledReadingRules();
    if (!kept || this.readOnly) return;
    this.savedRules = kept.base.rules;
    this.savedWitness = kept.base.witness;
    this.retained = kept.sent;
    const draft = kept.snapshots.at(-1)!;
    this.showRules(draft.schedule, draft.pauseOnBattery);
    this.queue.restore(kept.snapshots, kept.sent !== null);
    if (kept.sent) void this.queue.settleHead();
  }

  /** The draft shows these rules; no schedule shows the defaults and keeps none configured. */
  private showRules(schedule: { enabled?: boolean; days?: number[] | null; startMinute?: number; endMinute?: number } | null | undefined,
      pauseOnBattery: boolean): void {
    this.indexingScheduleConfigured = schedule != null;
    const time = (minutes: number) => `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;
    this.indexingRules = { enabled: schedule?.enabled ?? false,
      days: schedule?.days ? [...schedule.days] : [1, 2, 3, 4, 5, 6, 7],
      start: time(schedule?.startMinute ?? 540), end: time(schedule?.endMinute ?? 1020), pauseOnBattery };
  }

  /** R25 — after a conflict choice the keyboard stays in this editor: on `target`, else its first control. */
  private async focusControl(target: HTMLElement | null): Promise<void> {
    await this.updateComplete;
    const first = this.shadowRoot?.querySelector<HTMLInputElement>('fieldset input');
    const usable = target?.isConnected && !(target as HTMLInputElement).disabled ? target : first;
    usable?.focus();
  }

  private readonly noteControl = (event: Event): void => {
    const target = event.composedPath()[0];
    if (target instanceof HTMLInputElement) this.lastControl = target;
  };

  /** The saved rules and their revision, read within the write's bound ('bound' once it is reached). */
  private async observeSaved(signal: AbortSignal): Promise<{ rules: SavedIndexingRules; witness: SettingsWitness } | 'bound' | null> {
    try {
      const data = await withinBound(signal, (async () => {
        const res = this.host_ ? await this.host_.data.fetch('/api/settings/v2', { signal })
          : await authorizedFetch(`${this.apiBase}/api/settings/v2`, { signal });
        if (!res.ok) throw new Error(`Could not load reading rules (${res.status}).`);
        return settingsV2Schema.parse(await res.json());
      })());
      if (!data.witness) throw new Error('Saved reading rules did not include a revision.');
      const policy = data.ui?.indexingPause;
      return { rules: { schedule: policy?.schedule ? { enabled: policy.schedule.enabled ?? false, days: [...(policy.schedule.days ?? [])],
        startMinute: policy.schedule.startMinute ?? 540, endMinute: policy.schedule.endMinute ?? 1020 } : null,
        pauseOnBattery: policy?.pauseOnBattery ?? false }, witness: data.witness };
    } catch (error) {
      if (signal.aborted) return 'bound';
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
    this.readNowSending = false;
    this.readNowStarted = false;
    this.readNowNote = '';
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

  protected override willUpdate(changed: PropertyValues): void {
    super.willUpdate(changed);
    this.readNowHadFocus = this.shadowRoot?.activeElement?.matches('[data-read-now]') ?? false;
    if (!changed.has('readWaiting')) return;
    const status = this.readWaiting;
    if (status?.active) this.readNowSawActive = true;
    else if (this.readNowStarted && (this.readNowSawActive || ++this.readNowQuietUpdates >= 2)) this.readNowStarted = false;
    // "Nothing is waiting" answers one click: the next fresh observation replaces it.
    if (this.readNowNote && (!status?.offered || this.readNowNote === NOTHING_WAITING)) this.readNowNote = '';
  }

  protected override updated(changed: PropertyValues): void {
    super.updated(changed);
    if (this.indexingRuleFocus) void this.focusPendingIndexingRule();
    // The action is replaced in place: focus follows to its line, or to the group's heading.
    if (this.readNowHadFocus && !this.shadowRoot?.querySelector('[data-read-now]')) {
      const line = this.shadowRoot?.getElementById('read-now-status');
      (line?.textContent?.trim() ? line : this.shadowRoot?.getElementById('when-to-index'))?.focus();
    }
  }

  /** "Read waiting files now": one release in the Engine; the reading rules are not changed. */
  private async readWaitingNow(): Promise<void> {
    if (this.readNowSending) return;
    this.readNowSending = true;
    this.readNowNote = '';
    try {
      const result = await getOperationClient(this.apiBase).invokeWithConsent('core.indexing-read-now',
        { args: {}, idempotencyKey: createOperationKey() }, { requestConsent: requestAuthorization });
      const outcome = result.structuredData?.['outcome'];
      if (outcome === 'started' || outcome === 'joined') {
        this.readNowStarted = true;
        this.readNowSawActive = false;
        this.readNowQuietUpdates = 0;
      } else if (outcome === 'nothing-waiting') {
        this.readNowNote = NOTHING_WAITING;
      } else {
        void refreshStatusNow();
      }
    } catch (error: unknown) {
      this.readNowNote = `Could not confirm the reading request. Check the current status before trying again. ${
        error instanceof Error ? error.message : ''}`.trim();
    } finally {
      this.readNowSending = false;
    }
  }

  private renderReadWaiting() {
    const reading = this.readWaiting?.active || this.readNowStarted;
    const offer = !reading && this.readWaiting?.offered && this.readNowNote !== NOTHING_WAITING;
    return html`<div class="read-now">
      ${offer ? html`<button type="button" class="text-action" data-read-now
        aria-disabled=${this.readNowSending ? 'true' : 'false'}
        @click=${() => void this.readWaitingNow()}>Read waiting files now</button>` : nothing}
      <p id="read-now-status" role="status" tabindex="-1">${reading
        ? 'Reading the waiting files. Your reading rules are unchanged.' : this.readNowNote}</p>
    </div>`;
  }

  private async loadSettings(mode: 'preserve' | 'discard' = 'preserve'): Promise<boolean> {
    const serial = ++this.loadSerial;
    const shown = JSON.stringify(this.indexingRules);
    // R17 — a revision taken up meanwhile (Library's own successor write) is newer than this read.
    const witnessAtStart = this.savedWitness;
    // R22 — this read also settles "Use the saved rules": it is bounded like a write's re-read, and
    // that reconciliation read is cancelled at the bound. The passive load's late answer is ignored.
    const bound = new AbortController();
    const timer = setTimeout(() => bound.abort(), WRITE_BOUND_MS);
    const init = mode === 'discard' ? { signal: bound.signal } : undefined;
    try {
      const data = await withinBound(bound.signal, (async () => {
        const res = this.host_ ? await this.host_.data.fetch('/api/settings/v2', init)
          : await authorizedFetch(`${this.apiBase}/api/settings/v2`, init);
        if (!res.ok) throw new Error(`Could not load reading rules (${res.status}).`);
        return settingsV2Schema.parse(await res.json());
      })()).finally(() => clearTimeout(timer));
      if (serial !== this.loadSerial || !this.isConnected) return false;
      this.readOnly = data.settingsMode === 'in_memory';
      const policy = data.ui?.indexingPause;
      const schedule = policy?.schedule;
      const saved: SavedIndexingRules = { schedule: schedule ? { enabled: schedule.enabled ?? false, days: [...(schedule.days ?? [])],
        startMinute: schedule.startMinute ?? 540, endMinute: schedule.endMinute ?? 1020 } : null, pauseOnBattery: policy?.pauseOnBattery ?? false };
      const dirty = this.indexingRulesLoaded && this.indexingRulesDirty;
      // Captured changes not saved yet stay as they are: their own writes find out what changed (R11).
      const queued = this.queue.snapshots.length > 0 || this.queue.sending;
      if (mode === 'preserve' && dirty && !queued && !sameRules(saved, this.savedRules)) {
        this.conflictingRules = saved;
        this.conflictWitness = data.witness ?? null;
      }
      // R13 — the draft is replaced only while it is still what it was when this read began; after
      // "Use the saved rules", a change made meanwhile stays as a draft over the saved rules.
      const unchanged = JSON.stringify(this.indexingRules) === shown;
      const adopt = mode === 'discard' || !dirty && !queued && unchanged && this.savedWitness === witnessAtStart;
      if (adopt) { this.savedRules = saved; this.savedWitness = data.witness ?? null; }
      if (adopt && unchanged) this.showRules(schedule, policy?.pauseOnBattery ?? false);
      if (mode === 'discard') {
        this.conflictingRules = null;
        this.conflictWitness = null;
        this.indexingRulesError = '';
        this.indexingRulesMessage = '';
      }
      this.indexingRulesLoaded = true;
      if (!this.restoreChecked) { this.restoreChecked = true; this.restoreUnsettledRules(); }
      return true;
    } catch (error) {
      if (serial === this.loadSerial) {
        const message = bound.signal.aborted ? 'Could not load reading rules: no answer in time.'
          : error instanceof Error ? error.message : String(error);
        this.indexingRulesError = message;
      }
      return false;
    }
  }

  /** "Changed elsewhere": keep the draft over the rules shown, or take the saved rules and write nothing. */
  private async resolveConflict(keepDraft: boolean): Promise<void> {
    if (this.queue.sending || this.readOnly || !this.conflictingRules) return;
    // Either choice settles every change captured before it: none of them is sent.
    this.queue.clear();
    this.retained = null;
    if (keepDraft) {
      // The draft kept is captured now. Consent covers exactly the rules displayed: it is based on their
      // revision, so a further change elsewhere since is shown as a new conflict instead of overwritten.
      this.savedRules = this.conflictingRules;
      this.savedWitness = this.conflictWitness;
      this.conflictingRules = null;
      this.conflictWitness = null;
      const saving = this.saveIndexingRules();
      await this.focusControl(this.lastControl);
      await saving;
      return;
    }
    this.indexingRulesSaving = true;
    await this.loadSettings('discard');
    this.indexingRulesSaving = false;
    await this.focusControl(null);
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
    .read-now .text-action { color: var(--text-secondary); padding: .25rem 0; }
    .read-now .text-action:hover { color: var(--text-primary); text-decoration: underline; }
    #read-now-status:focus { outline: none; }
    #read-now-status:focus-visible { outline: 2px solid var(--focus-ring-color); outline-offset: 2px; }
    @media (forced-colors: active) {
      button, input { color: CanvasText; background: Canvas; border-color: CanvasText; }
      button:focus-visible, input:focus-visible, summary:focus-visible { outline-color: Highlight; }
    }
  `;

  override render() {
    return html`<section aria-labelledby="when-to-index">
      <h3 id="when-to-index" tabindex="-1">When to read files</h3>
      <form @submit=${this.submitIndexingRules} @focusout=${this.saveOnLeave} @input=${this.noteControl} @change=${this.noteControl}
        novalidate aria-label="Reading rules">
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
        ${this.queue.snapshots.length > 0 && !this.indexingRulesSaving && !this.conflictingRules
          ? html`<p role="alert">${this.queue.uncertain ? `Not sure this saved.${this.queue.snapshots.length > 1
            ? ' Your newer changes are not saved yet.' : ''}` : 'Your changes are not saved yet.'}
          <button type="button" @click=${() => void this.saveIndexingRules()}>Try again</button></p>` : nothing}
        <p id="indexing-rules-error" role="alert">${this.indexingRulesError}</p>
        ${this.checkpointLost ? html`<p role="status">${MAY_BE_LOST}.</p>` : nothing}
        <p role="status">${this.queue.sending && !this.conflictingRules ? 'Saving…'
          : this.indexingRulesDirty || this.queue.snapshots.length ? '' : this.indexingRulesMessage}</p>
        ${!this.indexingRulesLoaded ? html`<p>Waiting for saved reading rules.</p>` : nothing}
        ${this.readOnly ? html`<p>Settings are read-only in this session.</p>` : nothing}
      </form>
      ${this.renderReadWaiting()}
    </section>`;
  }
}
if (!customElements.get('jf-indexing-rules-editor')) customElements.define('jf-indexing-rules-editor', IndexingRulesEditor);
