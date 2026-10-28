// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({ invoke: vi.fn(), listen: vi.fn() }));
vi.mock('../utils/tauriRuntime.js', () => ({ isTauriRuntime: () => true }));
vi.mock('./apiBase.js', () => ({ resolveBootApiBase: async () => 'http://bound' }));
vi.mock('@tauri-apps/api/core', () => ({ invoke: mocks.invoke }));
vi.mock('@tauri-apps/api/event', () => ({ listen: mocks.listen }));

import { mountBootApplication, resolveBootWithRecovery } from './desktopRecovery.js';
import { __resetForTest, getAppUpdateStatus, installAppUpdate, refreshAppUpdateStatus } from '../shell-v0/state/appUpdateState.js';
import type { EngineRecovery } from '../shell-v0/components/EngineRecovery.js';

const running = {
  schemaVersion: 1, kind: 'engine-supervisor-state.v1', supervisor: 'tauri',
  state: 'running', incarnation: 1, restartCount: 0, maxRestartAttempts: 3,
};
function supervisorEvent(state: string, reason?: string): void {
  const onState = mocks.listen.mock.calls.find(([name]) => name === 'justsearch://supervisor-state')![1];
  onState({ payload: { ...running, state, reason } });
}

beforeEach(() => {
  __resetForTest();
  vi.useFakeTimers();
  mocks.invoke.mockReset();
  mocks.listen.mockReset().mockResolvedValue(() => {});
  document.body.innerHTML = '<div id="root"></div>';
});
afterEach(() => {
  document.body.innerHTML = '';
  vi.restoreAllMocks();
  vi.useRealTimers();
});

it.each(['success', 'cancelled', 'repair'] as const)('routes the running Linux app to its existing updating surface throughout the installer lock wait and stops polling after %s', async (outcome) => {
  vi.spyOn(window.navigator, 'platform', 'get').mockReturnValue('Linux x86_64');
  const reload = vi.spyOn(window.location, 'reload').mockImplementation(() => {});
  let finish!: () => void;
  const waiting = new Promise<void>((resolve) => { finish = resolve; });
  let state = 'available';
  const repairInstruction = 'Reinstall manually: sudo apt install ./JustSearch-update.deb';
  mocks.invoke.mockImplementation((command: string) => {
    if (command === 'factory_reset_status') return Promise.resolve({ phase: 'idle', remaining: [] });
    if (command === 'supervisor_state') return Promise.resolve(running);
    if (command === 'install_app_update') {
      // The production handoff holds/stops the Engine before entering pkexec's lock wait.
      state = 'install_launching';
      supervisorEvent('stopped', 'upgrade');
      return waiting.then(() => {
        state = outcome === 'success' ? 'install_launched' : outcome === 'repair' ? 'repair_required' : 'available';
        if (outcome === 'repair') throw new Error(repairInstruction);
        if (outcome === 'cancelled') {
          supervisorEvent('running');
          throw new Error('prompt dismissed');
        }
      });
    }
    return Promise.resolve({ state, currentVersion: '0.9.0', availableVersion: '0.9.1',
      error: state === 'repair_required' ? repairInstruction : null });
  });
  const root = document.getElementById('root')!;
  const teardown = vi.fn();
  await resolveBootWithRecovery(root, teardown);
  const application = document.createElement('jf-shell');
  mountBootApplication(root, application);
  await refreshAppUpdateStatus();
  expect(application.isConnected).toBe(true);
  // Same host command/projection as Settings; no recovery element is mounted at invocation.
  const result = installAppUpdate().catch((error: Error) => error.message);
  await vi.dynamicImportSettled();
  const recovery = root.querySelector<EngineRecovery>('jf-engine-recovery');
  expect(recovery, 'an upgrade hold must replace the running shell, not leave its Reconnecting chip').not.toBeNull();
  expect(application.isConnected).toBe(false);
  expect(teardown).toHaveBeenCalledOnce();
  await recovery!.updateComplete;
  expect(recovery!.shadowRoot?.textContent).toContain('Updating JustSearch…');
  await vi.advanceTimersByTimeAsync(180_000);
  await recovery!.updateComplete;
  expect(getAppUpdateStatus()?.state).toBe('install_launching');
  expect(recovery!.shadowRoot?.textContent).toContain('Updating JustSearch…');
  // A late completion of asynchronous application boot cannot displace the hold surface.
  mountBootApplication(root, application);
  expect(root.firstElementChild).toBe(recovery);
  finish();
  expect(await result).toBe(outcome === 'success' ? undefined : outcome === 'repair' ? repairInstruction : 'prompt dismissed');
  expect(getAppUpdateStatus()?.state).toBe(outcome === 'success' ? 'install_launched' : outcome === 'repair' ? 'repair_required' : 'available');
  await recovery!.updateComplete;
  if (outcome === 'repair') expect(recovery!.shadowRoot?.textContent).toContain(repairInstruction);
  expect(reload).toHaveBeenCalledTimes(outcome === 'cancelled' ? 1 : 0);
  const calls = mocks.invoke.mock.calls.length;
  await vi.advanceTimersByTimeAsync(2_000);
  expect(mocks.invoke).toHaveBeenCalledTimes(calls);
});

it('preserves the Windows running shell during its installer handoff', async () => {
  vi.spyOn(window.navigator, 'platform', 'get').mockReturnValue('Win32');
  mocks.invoke.mockImplementation(async (command: string) => command === 'factory_reset_status'
    ? { phase: 'idle', remaining: [] } : command === 'supervisor_state' ? running
      : { state: 'install_launching', currentVersion: '0.9.0' });
  const root = document.getElementById('root')!;
  const teardown = vi.fn();
  await resolveBootWithRecovery(root, teardown);
  const application = document.createElement('jf-shell');
  mountBootApplication(root, application);
  supervisorEvent('stopped', 'upgrade');
  await refreshAppUpdateStatus();
  expect(root.firstElementChild).toBe(application);
  expect(teardown).not.toHaveBeenCalled();
});
