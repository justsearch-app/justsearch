// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  native: vi.fn(() => true),
  endpoint: vi.fn(),
  invoke: vi.fn(),
  listen: vi.fn(),
}));
vi.mock('../utils/tauriRuntime.js', () => ({ isTauriRuntime: mocks.native }));
vi.mock('../api/http', () => ({ resolveApiEndpoint: mocks.endpoint }));
vi.mock('@tauri-apps/api/core', () => ({ invoke: mocks.invoke }));
vi.mock('@tauri-apps/api/event', () => ({ listen: mocks.listen }));

import { resolveBootWithRecovery } from './desktopRecovery.js';
import { __resetForTest } from '../shell-v0/state/appUpdateState.js';
import type { EngineRecovery } from '../shell-v0/components/EngineRecovery.js';

const terminal = {
  schemaVersion: 1, kind: 'engine-supervisor-state.v1', supervisor: 'tauri',
  state: 'exhausted', incarnation: 1, restartCount: 3, maxRestartAttempts: 3,
};

describe('desktop boot recovery', () => {
  beforeEach(() => {
    __resetForTest();
    mocks.native.mockReturnValue(true);
    mocks.listen.mockReset().mockResolvedValue(() => {});
    mocks.endpoint.mockReset().mockResolvedValue({ baseUrl: null });
    mocks.invoke.mockReset().mockImplementation(async (command) => command === 'supervisor_state'
      ? terminal : { state: 'available', currentVersion: '1.0', availableVersion: '1.1' });
    document.body.innerHTML = '<div id="root"></div>';
  });
  afterEach(() => { document.body.innerHTML = ''; });

  it('subscribes before API discovery and exposes host installation without an API', async () => {
    mocks.endpoint.mockImplementation(async () => {
      expect(mocks.listen).toHaveBeenCalledWith('justsearch://supervisor-state', expect.any(Function));
      expect(mocks.listen).toHaveBeenCalledWith('justsearch://backend-restart', expect.any(Function));
      return { baseUrl: null };
    });
    const root = document.getElementById('root')!;
    expect(await resolveBootWithRecovery(root)).toBeNull();
    const recovery = root.querySelector('jf-engine-recovery') as EngineRecovery;
    await recovery.updateComplete;
    expect(recovery.shadowRoot?.textContent).toContain('JustSearch could not restart');
    expect(recovery.shadowRoot?.textContent).toContain('JustSearch 1.1 is available');
    const install = [...recovery.shadowRoot!.querySelectorAll('jf-button')]
      .find((button) => button.textContent?.trim() === 'Install update') as HTMLElement & { onActivate: () => Promise<void> };
    await install.onActivate();
    expect(mocks.invoke).toHaveBeenCalledWith('install_app_update');
    expect(mocks.endpoint).toHaveBeenCalledOnce();
  });

  it('removes the recovery surface when the real API is available', async () => {
    mocks.endpoint.mockResolvedValue({ baseUrl: 'http://127.0.0.1:40404' });
    const root = document.getElementById('root')!;
    expect(await resolveBootWithRecovery(root)).toBe('http://127.0.0.1:40404');
    expect(root.querySelector('jf-engine-recovery')).toBeNull();
  });

  it('keeps an update failure visible and allows checking again', async () => {
    mocks.invoke.mockImplementation(async (command) => command === 'supervisor_state'
      ? terminal : { state: 'error', currentVersion: '1.0' });
    const root = document.getElementById('root')!;
    await resolveBootWithRecovery(root);
    const recovery = root.querySelector('jf-engine-recovery') as EngineRecovery;
    await recovery.updateComplete;
    expect(recovery.shadowRoot?.querySelector('[role="alert"]')?.textContent).toContain('The update could not finish');
    const check = [...recovery.shadowRoot!.querySelectorAll('jf-button')]
      .find((button) => button.textContent?.trim() === 'Check for updates') as HTMLElement & { disabled: boolean; onActivate: () => Promise<void> };
    expect(check.disabled).toBe(false);
    mocks.invoke.mockResolvedValue({ state: 'up_to_date', currentVersion: '1.0' });
    await check.onActivate();
    await recovery.updateComplete;
    expect(mocks.invoke).toHaveBeenCalledWith('check_for_app_update');
    expect(recovery.shadowRoot?.querySelector('[role="alert"]')).toBeNull();
    expect(recovery.shadowRoot?.textContent).toContain('You have the latest version');
  });

  it('keeps browser unresolved behavior and never invokes native recovery commands', async () => {
    mocks.native.mockReturnValue(false);
    const root = document.getElementById('root')!;
    expect(await resolveBootWithRecovery(root)).toBeNull();
    expect(root.querySelector('[role="alert"]')?.textContent).toBe('Unable to connect to the JustSearch backend.');
    expect(mocks.invoke).not.toHaveBeenCalled();
    expect(mocks.listen).not.toHaveBeenCalled();
  });
});
