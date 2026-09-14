// SPDX-License-Identifier: Apache-2.0
import { isTauriRuntime } from '../utils/tauriRuntime.js';
import { installSupervisorStateBridge } from '../api/supervisorState.js';
import { installBackendRestartBridge } from '../api/backendRestart.js';
import { resolveBootApiBase } from './apiBase.js';

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
  root.replaceChildren(recovery);
  let waitingForApi = false;
  await installSupervisorStateBridge((state) => {
    recovery.supervisor = state;
    // Initial boot has no backend-restart event. If it finishes after API discovery gave up,
    // retry discovery through the normal entry point when the host announces it is running.
    if (waitingForApi && state.state === 'running') window.location.reload();
  });
  await installBackendRestartBridge(() => window.location.reload());
  const base = await resolveBootApiBase();
  if (base) recovery.remove();
  else waitingForApi = true;
  return base;
}
