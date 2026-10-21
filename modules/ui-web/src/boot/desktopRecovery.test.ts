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
vi.mock('@tauri-apps/api/core', () => ({ invoke: (command: string) => command === 'factory_reset_status'
  ? Promise.resolve({ phase: 'idle', remaining: [] }) : mocks.invoke(command) }));
vi.mock('@tauri-apps/api/event', () => ({ listen: mocks.listen }));

import { mountBootApplication, resolveBootWithRecovery } from './desktopRecovery.js';
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

  it('words an escalated Engine exit from the supervisor observation while the API is down', async () => {
    mocks.invoke.mockImplementation(async (command) => command === 'supervisor_state'
      ? { ...terminal, state: 'restarting', lastExit: { code: 42, reason: 'escalated_restart', class: 'counted' } }
      : { state: 'up_to_date', currentVersion: '1.0' });
    const root = document.getElementById('root')!;
    expect(await resolveBootWithRecovery(root)).toBeNull();
    const recovery = root.querySelector('jf-engine-recovery') as EngineRecovery;
    await recovery.updateComplete;
    expect(recovery.shadowRoot?.textContent).toContain('JustSearch is restarting after a serious failure');
  });

  it('removes the recovery surface when the real API is available', async () => {
    mocks.invoke.mockImplementation(async (command) => command === 'supervisor_state'
      ? { ...terminal, state: 'running' } : { state: 'up_to_date', currentVersion: '1.0' });
    mocks.endpoint.mockResolvedValue({ baseUrl: 'http://127.0.0.1:40404' });
    const root = document.getElementById('root')!;
    expect(await resolveBootWithRecovery(root)).toBe('http://127.0.0.1:40404');
    expect(root.querySelector('jf-engine-recovery')).toBeNull();
  });

  it('replaces a running shell with terminal recovery without a successor binding', async () => {
    mocks.invoke.mockImplementation(async (command) => command === 'supervisor_state'
      ? { ...terminal, state: 'running' } : { state: 'up_to_date', currentVersion: '1.0' });
    mocks.endpoint.mockResolvedValue({ baseUrl: 'http://127.0.0.1:40404' });
    const root = document.getElementById('root')!;
    await resolveBootWithRecovery(root);
    const shell = document.createElement('jf-shell');
    root.replaceChildren(shell);
    expect(shell.isConnected).toBe(true);
    const onState = mocks.listen.mock.calls.find(([name]) => name === 'justsearch://supervisor-state')![1];
    onState({ payload: { ...terminal, state: 'restarting' } });
    expect(shell.isConnected).toBe(true);
    onState({ payload: terminal });
    const recovery = root.querySelector('jf-engine-recovery') as EngineRecovery;
    expect(recovery).not.toBeNull();
    expect(shell.isConnected).toBe(false);
    await recovery.updateComplete;
    expect(recovery.shadowRoot?.textContent).toContain('JustSearch could not restart');
    expect(recovery.shadowRoot?.textContent).toContain('Restart JustSearch');
    expect(recovery.shadowRoot?.textContent).toContain('Check for updates');
  });

  it('preserves exhaustion that arrives while the application is still mounting', async () => {
    mocks.invoke.mockImplementation(async (command) => command === 'supervisor_state'
      ? { ...terminal, state: 'running' } : { state: 'up_to_date', currentVersion: '1.0' });
    mocks.endpoint.mockResolvedValue({ baseUrl: 'http://127.0.0.1:40404' });
    const root = document.getElementById('root')!;
    await resolveBootWithRecovery(root);
    const onState = mocks.listen.mock.calls.find(([name]) => name === 'justsearch://supervisor-state')![1];
    onState({ payload: terminal });
    const shell = document.createElement('jf-shell');
    mountBootApplication(root, shell);
    expect(shell.isConnected).toBe(false);
    const recovery = root.querySelector('jf-engine-recovery') as EngineRecovery;
    await recovery.updateComplete;
    expect(recovery.shadowRoot?.textContent).toContain('JustSearch could not restart');
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

  it.each(['Check for updates', 'Install update'])('shows administrator refusal for %s with the Engine down', async (label) => {
    const refusal = 'App updates are disabled by administrator policy (appUpdatesEnabled=false).';
    const root = document.getElementById('root')!;
    await resolveBootWithRecovery(root);
    const recovery = root.querySelector('jf-engine-recovery') as EngineRecovery;
    await recovery.updateComplete;
    const button = [...recovery.shadowRoot!.querySelectorAll('jf-button')]
      .find((candidate) => candidate.textContent?.trim() === label) as HTMLElement & { onActivate: () => Promise<void> };
    expect(button).toBeDefined();
    mocks.invoke.mockImplementation(async (command) => {
      if (command === 'app_update_status') return { state: 'available', currentVersion: '1.0', availableVersion: '1.1' };
      throw refusal;
    });
    await button.onActivate();
    await recovery.updateComplete;
    expect(recovery.shadowRoot?.querySelector('[role="alert"]')?.textContent).toContain(refusal);
    expect(mocks.endpoint).toHaveBeenCalledOnce();
  });

  it('restarts through the host and shows a refused restart reason without losing controls', async () => {
    const root = document.getElementById('root')!;
    await resolveBootWithRecovery(root);
    const recovery = root.querySelector('jf-engine-recovery') as EngineRecovery;
    await recovery.updateComplete;
    const restart = [...recovery.shadowRoot!.querySelectorAll('jf-button')]
      .find(button => button.textContent?.trim() === 'Restart JustSearch') as HTMLElement & { onActivate: () => Promise<void> };
    expect(restart).toBeDefined();
    mocks.invoke.mockRejectedValueOnce(new Error('An update holds Engine replacement; restart refused'));
    await restart.onActivate();
    await recovery.updateComplete;
    expect(mocks.invoke).toHaveBeenCalledWith('restart_engine');
    expect(recovery.shadowRoot?.querySelector('[role="alert"]')?.textContent).toContain('update holds');
  });

  it('shows the spawn obstacle, retained crash evidence, and the count across budget resets', async () => {
    mocks.invoke.mockImplementation(async command => command === 'supervisor_state' ? {
      ...terminal, reason: 'spawn_failed: Java executable missing',
      recentExits: { count: 12, codes: { '3': 12 } },
      crashFile: { name: 'hs_err_pid42.log', at: '2026-10-20T01:02:03Z', problematicFrame: 'C [onnxruntime.dll+0x123]' },
    } : { state: 'up_to_date' });
    const root = document.getElementById('root')!;
    await resolveBootWithRecovery(root);
    const recovery = root.querySelector('jf-engine-recovery') as EngineRecovery;
    await recovery.updateComplete;
    expect(recovery.shadowRoot?.textContent).toContain('Java executable missing');
    expect(recovery.shadowRoot?.textContent).toContain('12 times');
    expect(recovery.shadowRoot?.textContent).toContain('onnxruntime.dll');
    const open = [...recovery.shadowRoot!.querySelectorAll('jf-button')]
      .find(button => button.textContent?.trim() === 'Open crash folder') as HTMLElement & { onActivate: () => Promise<void> };
    mocks.invoke.mockResolvedValueOnce({ home: 'C:/Users/Name With Spaces/data' }).mockResolvedValueOnce(undefined);
    await open.onActivate();
    expect(mocks.invoke).toHaveBeenCalledWith('reveal_in_explorer', { path: 'C:/Users/Name With Spaces/data/crashes' });
  });

  it('keeps the running-but-not-ready explanation visible when the API answers', async () => {
    mocks.invoke.mockImplementation(async command => command === 'supervisor_state'
      ? { ...terminal, state: 'running', notReady: 'Index: UNAVAILABLE (index.failed)' } : { state: 'up_to_date' });
    mocks.endpoint.mockResolvedValue({ baseUrl: 'http://127.0.0.1:40404' });
    const root = document.getElementById('root')!;
    await resolveBootWithRecovery(root);
    const recovery = root.querySelector('jf-engine-recovery') as EngineRecovery;
    expect(recovery).not.toBeNull();
    await recovery.updateComplete;
    expect(recovery.shadowRoot?.textContent).toContain('running but not ready');
    expect(recovery.shadowRoot?.textContent).toContain('index.failed');
  });

  it('explains a locked data folder without suggesting an automatic retry', async () => {
    mocks.invoke.mockImplementation(async command => command === 'supervisor_state'
      ? { ...terminal, reason: 'ENGINE_RESTART_EXHAUSTED:data_dir_locked', lastExit: { code: 2, reason: 'data_dir_locked', class: 'NON_TRANSIENT' } }
      : { state: 'up_to_date' });
    const root = document.getElementById('root')!;
    await resolveBootWithRecovery(root);
    const recovery = root.querySelector('jf-engine-recovery') as EngineRecovery;
    await recovery.updateComplete;
    expect(recovery.shadowRoot?.textContent).toContain('data folder is in use by another process');
    expect(recovery.shadowRoot?.textContent).not.toContain('Automatic retry');
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
