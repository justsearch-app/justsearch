// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { clearMocks, mockIPC } from '@tauri-apps/api/mocks';

const invoke = vi.fn();

import { EngineRecovery } from './EngineRecovery.js';
import { __resetForTest, getAppUpdateStatus, installAppUpdate, refreshAppUpdateStatus } from '../state/appUpdateState.js';

beforeEach(() => {
  __resetForTest();
  vi.useFakeTimers();
  invoke.mockReset();
  mockIPC((command) => invoke(command));
});
afterEach(() => {
  document.body.innerHTML = '';
  clearMocks();
  vi.useRealTimers();
});

it.each(['success', 'cancelled'] as const)('shows the existing updating state during the installer wait and stops polling after %s', async (outcome) => {
  let finish!: () => void;
  const waiting = new Promise<void>((resolve) => { finish = resolve; });
  let state = 'available';
  invoke.mockImplementation((command: string) => {
    if (command === 'install_app_update') return waiting.then(() => {
      state = outcome === 'success' ? 'install_launched' : 'available';
      if (outcome === 'cancelled') throw new Error('prompt dismissed');
    });
    return Promise.resolve({ state, currentVersion: '0.9.0', availableVersion: '0.9.1' });
  });
  await refreshAppUpdateStatus();
  const recovery = new EngineRecovery();
  document.body.append(recovery);
  await vi.dynamicImportSettled();
  await recovery.updateComplete;
  expect(getAppUpdateStatus()?.state).toBe('available');
  const result = installAppUpdate().catch((error: Error) => error.message);
  await vi.dynamicImportSettled();
  state = 'install_launching';
  await vi.advanceTimersByTimeAsync(1_000);
  await recovery.updateComplete;
  expect(getAppUpdateStatus()?.state).toBe('install_launching');
  expect(recovery.shadowRoot?.textContent).toContain('Updating JustSearch…');
  await vi.advanceTimersByTimeAsync(5_000);
  expect(recovery.shadowRoot?.textContent).toContain('Updating JustSearch…');
  finish();
  expect(await result).toBe(outcome === 'success' ? undefined : 'prompt dismissed');
  expect(getAppUpdateStatus()?.state).toBe(outcome === 'success' ? 'install_launched' : 'available');
  const calls = invoke.mock.calls.length;
  await vi.advanceTimersByTimeAsync(2_000);
  expect(invoke).toHaveBeenCalledTimes(calls);
});
