// SPDX-License-Identifier: Apache-2.0
/**
 * Engine loss after the app has loaded (S5c: FL5, SC1–SC9). One answer to "has the loaded app lost
 * its engine, why, and what is the person's restart or retry doing?" for the in-shell treatment,
 * Health and the report.
 *
 * It owns no timer, poller or grace period. Its inputs are two existing signals:
 *  - the status pill's own "Can't reach the engine" decision (contact lost past the 40 s contact
 *    window and the pill's 10 s grace), reported by `FrameStatusController` on every refresh, so the
 *    pill and the treatment can never tell different stories;
 *  - on the desktop, the supervisor state the boot listener (`boot/desktopRecovery.ts`) receives:
 *    exhausted or stopped restarts and "not ready" count as loss. Before the app is presented the
 *    boot listener still shows its own recovery page; the Linux update hold still replaces the app.
 *
 * Restart uses the existing native `restart_engine`; there is no new command.
 */
import { formatTimeMinutes } from '../display/format.js';
import { isTerminal, type SupervisorState } from '../../api/supervisorState.js';
import { isTauriRuntime } from '../../utils/tauriRuntime.js';
import { createObservableStore } from './createObservableStore.js';

type EngineLossCause = 'unreachable' | 'not-ready' | 'stopped';

/** What the person's last request in the treatment is doing. */
type EngineLossAction =
  | { readonly kind: 'idle' }
  /**
   * Desktop: a restart was asked for. Shown until the app is in contact with the engine again, or the
   * engine fails again; a supervisor report of "running" alone does not end it.
   */
  | { readonly kind: 'restarting' }
  /** Desktop: the host refused the restart; `message` says why in plain words. */
  | { readonly kind: 'refused'; readonly message: string }
  /** Desktop: the restarted engine stopped again or did not become ready. */
  | { readonly kind: 'failed'; readonly message: string }
  /** Try again: one fresh status read is in flight. */
  | { readonly kind: 'trying' }
  /** Try again finished and the engine still does not answer. */
  | { readonly kind: 'still-lost' };

export interface EngineLoss {
  /** The treatment shows. */
  readonly lost: boolean;
  /** Why, most specific first: a supervisor fact beats the contact signal. Kept during a restart. */
  readonly cause: EngineLossCause | null;
  /** The pill says "Can't reach the engine". */
  readonly unreachable: boolean;
  /** The supervisor's last state (desktop), and when this window received it. */
  readonly supervisor: SupervisorState | null;
  readonly supervisorAtMs: number | null;
  readonly action: EngineLossAction;
  /** Health is on screen: the treatment steps aside to a band so Health stays readable. */
  readonly healthOpen: boolean;
  /** Grows with each outage, so a second outage is announced like the first. */
  readonly episode: number;
}

const IDLE: EngineLossAction = { kind: 'idle' };
const INITIAL: EngineLoss = {
  lost: false, cause: null, unreachable: false, supervisor: null, supervisorAtMs: null,
  action: IDLE, healthOpen: false, episode: 0,
};
const store = createObservableStore<EngineLoss>(INITIAL);
let healthViewers = 0;
/** During an accepted restart: the supervisor has left its failed state since the request. */
let restartLeftFailure = false;
/** When the restart under way was asked for: only contact after it is contact with the restarted engine. */
let restartAskedAtMs: number | null = null;

function supervisorLoss(state: SupervisorState | null): EngineLossCause | null {
  if (isTerminal(state)) return 'stopped';
  if (state?.notReady) return 'not-ready';
  return null;
}

function update(patch: Partial<EngineLoss>): void {
  const draft = { ...store.get(), ...patch };
  const bySupervisor = supervisorLoss(draft.supervisor);
  const restarting = draft.action.kind === 'restarting';
  const lost = bySupervisor !== null || draft.unreachable || restarting;
  const cause = bySupervisor ?? (draft.unreachable ? 'unreachable' : restarting ? draft.cause : null);
  const before = store.get();
  const next: EngineLoss = {
    ...draft, lost, cause: lost ? cause : null,
    // Recovery ends the outage: the next one starts from its first words again.
    action: lost ? draft.action : IDLE,
    episode: lost && !before.lost ? before.episode + 1 : before.episode,
  };
  const same = (Object.keys(next) as (keyof EngineLoss)[]).every(key => Object.is(next[key], before[key]));
  if (!same) store.set(next);
}

export function getEngineLoss(): EngineLoss {
  return store.get();
}

export function subscribeEngineLoss(listener: (loss: EngineLoss) => void): () => void {
  return store.subscribe(listener, { immediate: true });
}

/**
 * The pill's decision, after its grace, and the app's last contact with the engine (any answer or
 * stream frame). Called on every status refresh; repeated values are free. Contact after a restart
 * was asked for ends it: the app reaches the engine again.
 */
export function reportEngineReachability(unreachable: boolean, lastContactMs: number | null): void {
  const reached = store.get().action.kind === 'restarting' && restartAskedAtMs !== null
    && lastContactMs !== null && lastContactMs > restartAskedAtMs;
  update(reached ? { unreachable, action: IDLE } : { unreachable });
}

/** The supervisor's state as the desktop host announced it. */
export function reportSupervisorState(state: SupervisorState): void {
  const current = store.get();
  let action = current.action;
  if (action.kind === 'restarting') {
    const failure = supervisorLoss(state);
    if (!failure) restartLeftFailure = true;
    else if (restartLeftFailure) action = { kind: 'failed', message: failure === 'not-ready'
      ? 'The engine started again, but its index is not ready.' : 'The engine stopped again.' };
  }
  update({ supervisor: state, supervisorAtMs: Date.now(), action });
}

/** Health counts itself while it is on screen. */
export function noteHealthOnScreen(onScreen: boolean): void {
  healthViewers = Math.max(0, healthViewers + (onScreen ? 1 : -1));
  update({ healthOpen: healthViewers > 0 });
}

/** The host's refusal, in plain words. The host's own text is never shown or copied. */
function restartRefusalWords(error: unknown): string {
  const text = error instanceof Error ? error.message : String(error);
  if (/update holds Engine replacement/i.test(text)) return 'An update is being installed. The engine starts again when it finishes.';
  if (/quit or upgrade/i.test(text)) return 'JustSearch is quitting or installing an update, so the engine can’t be restarted now.';
  if (/already starting or running|no terminal state/i.test(text)) return 'The engine is already starting or running, so it can’t be restarted now. JustSearch keeps trying to reach it.';
  if (/previous supervision generation is finishing/i.test(text)) return 'The engine is still shutting down. Try again in a moment.';
  return 'The engine could not be restarted.';
}

/**
 * Desktop: ask the host to restart the engine. Repeated requests while one is under way are ignored.
 * The browser has no restart; this does nothing there.
 */
export async function restartEngine(): Promise<void> {
  if (!isTauriRuntime() || store.get().action.kind === 'restarting') return;
  restartLeftFailure = supervisorLoss(store.get().supervisor) === null;
  restartAskedAtMs = Date.now();
  update({ action: { kind: 'restarting' } });
  try {
    const { invoke } = await import('@tauri-apps/api/core');
    await invoke('restart_engine');
  } catch (error) {
    if (store.get().action.kind === 'restarting') update({ action: { kind: 'refused', message: restartRefusalWords(error) } });
  }
}

/** Read the engine's status once now. Repeated requests while one is in flight are ignored. */
export async function tryEngineAgain(): Promise<void> {
  const kind = store.get().action.kind;
  if (kind === 'trying' || kind === 'restarting') return;
  update({ action: { kind: 'trying' } });
  try {
    // Loaded on use: the boot listener imports this module before any status store exists.
    const { refreshStatusNow } = await import('./aiStateStore.js');
    await refreshStatusNow();
  } catch { /* the pill decides; a failed read changes nothing */ }
  if (store.get().action.kind === 'trying') update({ action: { kind: 'still-lost' } });
}

/** The supervisor's reason for the treatment, in plain words; empty when it has none to give. */
export function engineLossReason(loss: EngineLoss): string {
  const supervisor = loss.supervisor;
  // A restart is under way: the earlier failure is no longer the story, and the progress line tells it.
  if (loss.action.kind === 'restarting') return '';
  if (loss.cause === 'not-ready') return 'The engine is running, but its index is not ready.';
  if (loss.cause === 'stopped') {
    const exit = supervisor?.lastExit?.reason ?? '';
    const raw = supervisor?.reason ?? '';
    const times = supervisor?.recentExits?.count ?? supervisor?.restartCount ?? 0;
    const why = exit === 'data_dir_locked' ? ' Another program is using JustSearch’s data folder. Close it, then restart the engine.'
      : exit === 'out_of_memory' ? ' It ran out of memory.'
        : exit === 'escalated_restart' ? ' It hit a serious failure.'
          : /hang/.test(raw) || exit === 'hang' ? ' It stopped responding.'
            : /spawn_failed/.test(raw) ? ' It could not be started.' : '';
    const retry = supervisor?.retryAt && supervisor.lastExit?.code !== 2
      ? ` JustSearch tries once more at ${formatTimeMinutes(new Date(supervisor.retryAt))}.` : '';
    return supervisor?.state === 'exhausted'
      ? `The engine stopped ${times} ${times === 1 ? 'time' : 'times'}, so JustSearch stopped restarting it.${why}${retry}`
      : `The engine stopped.${why}`;
  }
  if (supervisor?.state === 'restarting' || supervisor?.state === 'starting') return 'The engine is restarting by itself.';
  if (supervisor?.state === 'running') return 'The engine is running but does not answer.';
  return '';
}

/** Test seam: forget every outage, restart and Health count. */
export function __resetEngineLossForTest(): void {
  healthViewers = 0;
  restartLeftFailure = false;
  restartAskedAtMs = null;
  store.set(INITIAL);
}
