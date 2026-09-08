// SPDX-License-Identifier: Apache-2.0
/**
 * Supervisor-state bridge — lane F stage B item B10 (design 7.1, "visible state").
 *
 * The shell emits `justsearch://supervisor-state` on every transition of the Engine supervisor,
 * carrying the contents of `<dataDir>/runtime/supervisor.v1.json`. The webview needs it because the
 * moment it matters is the moment nothing else can tell it anything: an Engine that is `restarting`
 * answers no HTTP, and an Engine that is `exhausted` never will again.
 *
 * **Why this is not `backendRestart.ts`, and must not become it.** That event answers "your binding
 * is stale" — a new instanceId, a new port, a dead session token — and its consumer drops the token.
 * This one answers "here is what the supervisor is doing", which fires several times per restart
 * (restarting, starting, running) and does NOT mean the binding changed. Folding them would make
 * every cooldown tick look like a rebind. The shell keeps them distinct and pins that in
 * `backend_restart_event_is_untouched_by_the_supervisor`; this file is the other end of the same
 * decision.
 *
 * In a browser this native bridge is absent. The dev-runner may still supervise the Engine;
 * it does not expose Tauri's native event channel to the browser.
 */

import { isTauriRuntime } from '../utils/tauriRuntime.js';

const TAURI_EVENT_NAME = 'justsearch://supervisor-state';

/** The supervisor's states (design 7.1). One vocabulary, two writers (the dev-runner and the shell). */
export type SupervisorStateName =
  | 'starting'
  | 'running'
  | 'stopping'
  | 'restarting'
  | 'exhausted';

/**
 * The state record as both supervisors write it. Optional fields are optional in the file too — a
 * supervisor that has not observed a death yet has no `lastExit`, and treating an absent one as a
 * failure would make the first seconds of every boot look like an error.
 */
export interface SupervisorState {
  schemaVersion: number;
  kind: 'engine-supervisor-state.v1';
  /** Which implementation wrote it: `tauri` in production, `dev-runner` in development. */
  supervisor: string;
  state: SupervisorStateName;
  incarnation: number;
  restartCount: number;
  maxRestartAttempts: number;
  /** `product` or `harness` — which policy numbers were in force when this was written. */
  policyProfile?: string;
  pid?: number | null;
  apiPort?: number | null;
  instanceId?: string | null;
  reason?: string | null;
  requestedReason?: string | null;
  lastExit?: {
    code: number;
    reason: string;
    class: string;
    counted?: boolean;
    incarnation?: number;
  } | null;
  updatedAt?: string;
}

/** True when the supervisor has given up: design 7.1's terminal state, `ENGINE_RESTART_EXHAUSTED`. */
export function isTerminal(state: SupervisorState | null | undefined): boolean {
  return state?.state === 'exhausted';
}

/**
 * True while the Engine is coming back. Deliberately includes `starting`: to a caller waiting for
 * the API, "the supervisor is spawning" and "the Engine is booting" are the same outage, and a
 * consumer that distinguished them would flicker between two messages for one event.
 */
export function isRecovering(state: SupervisorState | null | undefined): boolean {
  return state?.state === 'restarting' || state?.state === 'starting';
}

/**
 * Subscribe, then obtain the current host's retained in-memory snapshot. An event received
 * during the snapshot request wins over that snapshot; an old disk file is never consulted.
 *
 * Returns an unsubscribe handle (no-op outside Tauri or when subscription fails). A malformed
 * payload is IGNORED rather than passed on: this event exists to explain an outage, and a consumer
 * that threw on a half-written record would turn an explanation into a second failure.
 */
export async function installSupervisorStateBridge(
  onState: (state: SupervisorState) => void,
): Promise<() => void> {
  if (!isTauriRuntime()) {
    return () => {
      /* no-op */
    };
  }
  try {
    const { listen } = await import('@tauri-apps/api/event');
    let receivedEvent = false;
    const unsubscribe = await listen(TAURI_EVENT_NAME, (event: { payload?: unknown }) => {
      if (!isSupervisorState(event?.payload)) return;
      receivedEvent = true;
      onState(event.payload);
    });
    try {
      const { invoke } = await import('@tauri-apps/api/core');
      const snapshot: unknown = await invoke('supervisor_state');
      if (!receivedEvent && isSupervisorState(snapshot)) onState(snapshot);
    } catch {
      // Older hosts can still supply events when the snapshot command is unavailable.
    }
    return unsubscribe;
  } catch (err) {
    console.warn('[supervisorState] failed to subscribe to Tauri event:', err);
    return () => {
      /* no-op */
    };
  }
}

function isSupervisorState(value: unknown): value is SupervisorState {
  if (!value || typeof value !== 'object') return false;
  const state = value as Partial<SupervisorState>;
  return state.schemaVersion === 1
    && state.kind === 'engine-supervisor-state.v1'
    && ['starting', 'running', 'stopping', 'restarting', 'exhausted'].includes(state.state ?? '')
    && Number.isInteger(state.incarnation)
    && Number.isInteger(state.restartCount)
    && Number.isInteger(state.maxRestartAttempts);
}
