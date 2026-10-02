// SPDX-License-Identifier: Apache-2.0
import { isTauriRuntime } from '../utils/tauriRuntime.js';
import { installSupervisorStateBridge, isTerminal } from '../api/supervisorState.js';
import type { EngineRecovery } from '../shell-v0/components/EngineRecovery.js';
import { installBackendRestartBridge } from '../api/backendRestart.js';
import { resolveBootApiBase } from './apiBase.js';

const recoverySurfaces = new WeakMap<HTMLElement, EngineRecovery>();

/** Keep terminal supervisor presentation authoritative while asynchronous shell boot finishes. */
export function mountBootApplication(root: HTMLElement, application: HTMLElement): void {
  const recovery = recoverySurfaces.get(root);
  const presentation = recovery && isTerminal(recovery.supervisor) ? recovery : application;
  if (root.childNodes.length !== 1 || root.firstChild !== presentation) root.replaceChildren(presentation);
}

/** Resolve the API only after desktop recovery events can reach the boot surface. */
export async function resolveBootWithRecovery(root: HTMLElement): Promise<string | null> {
  if (!isTauriRuntime()) {
    const base = await resolveBootApiBase();
    if (!base) {
      const alert = document.createElement('div');
      alert.setAttribute('role', 'alert');
      alert.style.cssText = 'padding:24px;font:14px system-ui,sans-serif';
      alert.textContent = 'Unable to connect to the JustSearch backend.';
      root.replaceChildren(alert);
    }
    return base;
  }

  const { EngineRecovery } = await import('../shell-v0/components/EngineRecovery.js');
  const recovery = new EngineRecovery();
  recoverySurfaces.set(root, recovery);
  root.replaceChildren(recovery);
  let waitingForApi = false;
  await installSupervisorStateBridge((state) => {
    recovery.supervisor = state;
    if (isTerminal(state)) {
      // This also disconnects the running Lit shell and tears down its subscriptions.
      if (recovery.parentElement !== root) root.replaceChildren(recovery);
      waitingForApi = true;
    }
    // Initial boot has no backend-restart event. If it finishes after API discovery gave up,
    // retry discovery through the normal entry point when the host announces it is running.
    if (waitingForApi && state.state === 'running') window.location.reload();
  });
  await installBackendRestartBridge(() => window.location.reload());
  const base = await resolveBootApiBase();
  if (base && !isTerminal(recovery.supervisor)) recovery.remove();
  else waitingForApi = true;
  return base;
}
