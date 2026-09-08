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
 * In browser (vite dev): no-op — Tauri APIs absent, and there is no supervisor. That is not a
 * degraded mode, it is design 3.1's explicitly unsupervised shape.
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
 * Subscribe to the shell's supervisor-state event.
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
    return await listen(TAURI_EVENT_NAME, (event: { payload?: unknown }) => {
      const payload = event?.payload as SupervisorState | undefined;
      if (!payload || typeof payload !== 'object' || typeof payload.state !== 'string') {
        return;
      }
      onState(payload);
    });
  } catch (err) {
    console.warn('[supervisorState] failed to subscribe to Tauri event:', err);
    return () => {
      /* no-op */
    };
  }
}
