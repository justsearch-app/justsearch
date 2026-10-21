// @vitest-environment happy-dom

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { invoke, isTauriRuntime } = vi.hoisted(() => ({
  invoke: vi.fn(),
  isTauriRuntime: vi.fn(() => true),
}));
vi.mock('@tauri-apps/api/core', () => ({ invoke }));
vi.mock('../../utils/tauriRuntime.js', () => ({ isTauriRuntime }));

import {
  __resetForTest,
  checkForAppUpdate,
  getAppUpdateStatus,
  installAppUpdate,
  refreshAppUpdateStatus,
  startAppUpdateMonitor,
  subscribeAppUpdate,
} from './appUpdateState.js';

describe('appUpdateState', () => {
  beforeEach(() => {
    __resetForTest();
    invoke.mockReset();
    isTauriRuntime.mockReturnValue(true);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('publishes trusted host status to all chrome consumers', async () => {
    invoke.mockResolvedValueOnce({
      state: 'available',
      currentVersion: '1.0.0',
      availableVersion: '1.1.0',
      releaseSequence: 8,
    });
    const states: Array<string | undefined> = [];
    const stop = subscribeAppUpdate((status) => states.push(status?.state));

    await checkForAppUpdate();

    expect(states).toEqual([undefined, 'checking', 'available']);
    expect(getAppUpdateStatus()?.releaseSequence).toBe(8);
    stop();
  });

  it('requires an explicit install call before invoking the install command', async () => {
    invoke
      .mockResolvedValueOnce({
        state: 'available',
        currentVersion: '1.0.0',
        availableVersion: '1.1.0',
      })
      .mockResolvedValueOnce(undefined)
      .mockResolvedValueOnce({
        state: 'install_launched',
        currentVersion: '1.0.0',
        availableVersion: '1.1.0',
      });
    await checkForAppUpdate();
    expect(invoke).not.toHaveBeenCalledWith('install_app_update');

    await installAppUpdate();
    expect(invoke).toHaveBeenCalledWith('install_app_update');
  });

  it('exposes host-reported download progress bytes to consumers', async () => {
    invoke.mockResolvedValueOnce({
      state: 'downloading',
      currentVersion: '1.0.0',
      availableVersion: '1.1.0',
      bytesDownloaded: 4_194_304,
      bytesTotal: 262_144_000,
    });

    await refreshAppUpdateStatus();

    const status = getAppUpdateStatus();
    expect(status?.bytesDownloaded).toBe(4_194_304);
    expect(status?.bytesTotal).toBe(262_144_000);
  });

  it('keeps bytesTotal null when the host reports an unknown download size, never fabricating 0', async () => {
    invoke.mockResolvedValueOnce({
      state: 'downloading',
      currentVersion: '1.0.0',
      availableVersion: '1.1.0',
      bytesDownloaded: 1_048_576,
      bytesTotal: null,
    });

    await refreshAppUpdateStatus();

    const status = getAppUpdateStatus();
    expect(status?.bytesDownloaded).toBe(1_048_576);
    expect(status?.bytesTotal).toBeNull();
    expect(status?.bytesTotal).not.toBe(0);
  });

  it('keeps a suppressed scheduled check idle, with manual checks still explicit', async () => {
    vi.useFakeTimers();
    invoke.mockResolvedValue({ state: 'idle', currentVersion: '1.0.0' });
    const states: Array<string | undefined> = [];
    const unsubscribe = subscribeAppUpdate((status) => states.push(status?.state));
    const stop = startAppUpdateMonitor();
    await vi.advanceTimersByTimeAsync(30_000);
    expect(invoke).toHaveBeenCalledWith('check_for_app_update', { automatic: true });
    expect(states).not.toContain('checking');
    expect(states).not.toContain('up_to_date');
    expect(getAppUpdateStatus()?.state).toBe('idle');
    invoke.mockClear().mockResolvedValue({ state: 'up_to_date', currentVersion: '1.0.0' });
    await checkForAppUpdate();
    expect(invoke).toHaveBeenCalledWith('check_for_app_update');
    expect(states).toContain('checking');
    expect(getAppUpdateStatus()?.state).toBe('up_to_date');
    stop();
    unsubscribe();
  });

  it('defers the passive check until after startup', async () => {
    vi.useFakeTimers();
    invoke.mockResolvedValue({
      state: 'up_to_date',
      currentVersion: '1.0.0',
    });

    const stop = startAppUpdateMonitor(1_000);
    await vi.advanceTimersByTimeAsync(0);
    expect(invoke).toHaveBeenCalledWith('app_update_status');
    expect(invoke).not.toHaveBeenCalledWith('check_for_app_update');

    await vi.advanceTimersByTimeAsync(1_000);
    expect(invoke).toHaveBeenCalledWith('check_for_app_update', { automatic: true });
    stop();
  });
});
