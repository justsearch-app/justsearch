// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// F-desktop B2 — engine loss at the moments of desktop start (FD1), the desktop report's exit counts
// (FD3) and the treatment's progress line (FD4): the real boot listener and the real Shell, with the
// native command and event channels as doubles, as in desktopEngineLoss.test.ts.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  invoke: vi.fn(), listen: vi.fn(), restart: vi.fn(),
  discovery: vi.fn<() => Promise<string | null>>(),
}));
vi.mock('../utils/tauriRuntime.js', () => ({ isTauriRuntime: () => true }));
vi.mock('./apiBase.js', () => ({ resolveBootApiBase: () => mocks.discovery() }));
vi.mock('@tauri-apps/api/core', () => ({ invoke: (command: string, args?: unknown) => mocks.invoke(command, args) }));
vi.mock('@tauri-apps/api/event', () => ({ listen: mocks.listen }));

import { mountBootApplication, resolveBootWithRecovery } from './desktopRecovery.js';
import '../shell-v0/chrome/Shell.js';
import { __feedContactForTest, __resetAiStateForTest, stopAiStateStore } from '../shell-v0/state/aiStateStore.js';
import { __resetStatusPollForTest } from '../shell-v0/utils/statusPoll.js';
import { __resetInferencePollForTest } from '../shell-v0/utils/inferencePoll.js';
import { __resetBootstrapForTest } from '../shell-v0/router/bootstrap.js';
import { __resetUserConfigForTest } from '../shell-v0/state/userConfigState.js';
import { __resetForTest as resetAppUpdate } from '../shell-v0/state/appUpdateState.js';
import { __resetModalityForTest } from '../shell-v0/primitives/modality.js';
import { __resetEngineLossForTest } from '../shell-v0/state/engineLoss.js';

type Updating = HTMLElement & { updateComplete: Promise<unknown> };
const running = {
  schemaVersion: 1, kind: 'engine-supervisor-state.v1', supervisor: 'tauri',
  state: 'running', incarnation: 1, restartCount: 0, maxRestartAttempts: 3,
};
const exhausted = { state: 'exhausted', restartCount: 3 };

function supervisorEvent(patch: Record<string, unknown>): void {
  const onState = mocks.listen.mock.calls.find(([name]) => name === 'justsearch://supervisor-state')![1];
  onState({ payload: { ...running, ...patch } });
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date'] });
  vi.spyOn(window.navigator, 'platform', 'get').mockReturnValue('Linux x86_64');
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ entries: [] }), { status: 200 })));
  __resetAiStateForTest(); __resetStatusPollForTest(); __resetInferencePollForTest(); resetAppUpdate();
  __resetEngineLossForTest();
  mocks.discovery.mockReset().mockResolvedValue('http://127.0.0.1:4100');
  mocks.listen.mockReset().mockResolvedValue(() => {});
  mocks.restart.mockReset().mockResolvedValue(undefined);
  mocks.invoke.mockReset().mockImplementation(async (command: string) => {
    if (command === 'factory_reset_status') return { phase: 'idle', remaining: [] };
    if (command === 'supervisor_state') return running;
    if (command === 'restart_engine') return mocks.restart();
    if (command === 'app_update_status') return { state: 'up_to_date', currentVersion: '1.0' };
    return undefined;
  });
  // The start-up frame index.html paints, over the root, before any application code.
  document.body.innerHTML = '<div id="root"><div id="boot-frame"></div></div>';
});
afterEach(() => {
  document.body.replaceChildren();
  __resetAiStateForTest(); __resetStatusPollForTest(); __resetInferencePollForTest();
  __resetBootstrapForTest(); __resetUserConfigForTest(); __resetModalityForTest();
  __resetEngineLossForTest();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

async function pass(ms: number): Promise<void> {
  for (let left = ms; left > 0; left -= 1_000) await vi.advanceTimersByTimeAsync(Math.min(1_000, left));
}

/** Boot as main.jsx does: discovery, then the shell attached beneath the frame. */
async function bootApp(): Promise<{ root: HTMLElement; shell: Updating; teardown: ReturnType<typeof vi.fn> }> {
  const root = document.getElementById('root')!;
  const teardown = vi.fn(stopAiStateStore);
  await resolveBootWithRecovery(root, teardown);
  const shell = document.createElement('jf-shell') as Updating;
  shell.setAttribute('api-base', 'http://127.0.0.1:4100');
  mountBootApplication(root, shell);
  await shell.updateComplete;
  await pass(1_000);
  __feedContactForTest();
  await pass(1_000);
  return { root, shell, teardown };
}

function treatment(shell: Updating): HTMLElement | null {
  return shell.shadowRoot!.querySelector('jf-engine-loss')?.shadowRoot?.querySelector<HTMLElement>('[data-testid="engine-loss"]') ?? null;
}

async function settle(shell: Updating): Promise<void> {
  await pass(1_000);
  await shell.updateComplete;
  await shell.shadowRoot!.querySelector<Updating>('jf-engine-loss')?.updateComplete;
}

/** The window paints nothing while it is hidden (started to the tray, minimized): no animation frame runs. */
function hiddenWindow(): void {
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden');
  vi.spyOn(document, 'hidden', 'get').mockReturnValue(true);
  vi.stubGlobal('requestAnimationFrame', vi.fn(() => 0));
}

describe('FD1 the app counts as usable once it has painted and booted', () => {
  it('a window that is hidden while the app starts: once the shell has rendered, loss shows inside it, not the recovery page', async () => {
    hiddenWindow();
    const { root, shell, teardown } = await bootApp();
    expect(root.querySelector('#boot-frame'), 'nothing waits for a paint the hidden window cannot make').toBeNull();
    expect(shell.inert, 'the app takes input').toBe(false);
    supervisorEvent(exhausted);
    await settle(shell);
    expect(root.querySelector('jf-engine-recovery'), 'the app is not replaced').toBeNull();
    expect(shell.isConnected).toBe(true);
    expect(teardown).not.toHaveBeenCalled();
    expect(treatment(shell), 'the in-app treatment shows').not.toBeNull();
  });

  it('a window hidden while the hand-off waits for its paint: the hand-off completes and loss shows inside the app', async () => {
    vi.stubGlobal('requestAnimationFrame', vi.fn(() => 0));
    const { root, shell } = await bootApp();
    expect(root.querySelector('#boot-frame'), 'visible: the frame waits for the first paint').not.toBeNull();
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden');
    vi.spyOn(document, 'hidden', 'get').mockReturnValue(true);
    document.dispatchEvent(new Event('visibilitychange'));
    await pass(1_000);
    expect(root.querySelector('#boot-frame')).toBeNull();
    supervisorEvent(exhausted);
    await settle(shell);
    expect(root.querySelector('jf-engine-recovery')).toBeNull();
    expect(treatment(shell)).not.toBeNull();
  });

  it('before the app has painted, loss still shows the recovery page (preserved)', async () => {
    vi.stubGlobal('requestAnimationFrame', vi.fn(() => 0));
    const { root, shell } = await bootApp();
    expect(shell.inert).toBe(true);
    supervisorEvent(exhausted);
    expect(root.querySelector('jf-engine-recovery')).not.toBeNull();
    expect(shell.isConnected).toBe(false);
  });

  it('a reload after a restart keeps the start-up frame while it finds the engine: no Connecting page', async () => {
    let found!: (base: string) => void;
    mocks.discovery.mockImplementation(() => new Promise<string>(resolve => { found = resolve; }));
    const root = document.getElementById('root')!;
    const resolving = resolveBootWithRecovery(root, vi.fn());
    await pass(1_000);
    expect(mocks.discovery).toHaveBeenCalledOnce();
    expect(root.querySelector('jf-engine-recovery'), 'no Connecting to JustSearch while discovery runs').toBeNull();
    expect(root.querySelector('#boot-frame'), 'the frame stays').not.toBeNull();
    // The engine starting again during discovery is still no page of its own: the frame says Starting.
    supervisorEvent({ state: 'starting' });
    expect(root.querySelector('jf-engine-recovery')).toBeNull();
    found('http://127.0.0.1:4100');
    expect(await resolving).toBe('http://127.0.0.1:4100');
    expect(root.querySelector('#boot-frame')).not.toBeNull();
    expect(root.querySelector('jf-engine-recovery')).toBeNull();
  });

  it('when discovery finds no engine, the Connecting page waits and a running engine reloads (preserved)', async () => {
    const reload = vi.spyOn(window.location, 'reload').mockImplementation(() => {});
    mocks.discovery.mockResolvedValue(null);
    const root = document.getElementById('root')!;
    expect(await resolveBootWithRecovery(root, vi.fn())).toBeNull();
    const recovery = root.querySelector<Updating>('jf-engine-recovery');
    expect(recovery).not.toBeNull();
    await recovery!.updateComplete;
    expect(recovery!.shadowRoot!.textContent).toContain('Connecting to JustSearch');
    supervisorEvent({});
    expect(reload).toHaveBeenCalledOnce();
  });

  it('a terminal state during discovery shows the recovery page (preserved)', async () => {
    mocks.discovery.mockImplementation(() => new Promise(() => {}));
    const root = document.getElementById('root')!;
    void resolveBootWithRecovery(root, vi.fn());
    await pass(1_000);
    supervisorEvent(exhausted);
    expect(root.querySelector('jf-engine-recovery')).not.toBeNull();
    expect(root.querySelector('#boot-frame')).toBeNull();
  });
});

describe('FD3 FD4 the report and the progress line', () => {
  it('the report counts recent exits as the supervisor recorded them, signals included', async () => {
    const writes: string[] = [];
    Object.defineProperty(navigator, 'clipboard', { value: { writeText: async (text: string) => { writes.push(text); } }, configurable: true });
    try {
      const { shell } = await bootApp();
      // The shell's record after 16 SIGKILLs on Linux: the ledger keys a signal by its name.
      supervisorEvent({ state: 'exhausted', restartCount: 16, lastExit: { code: -9, reason: 'restart', class: 'TRANSIENT' },
        recentExits: { count: 16, codes: { SIGKILL: 16 } } });
      await settle(shell);
      const copy = [...treatment(shell)!.querySelectorAll<HTMLElement & { onActivate: () => unknown }>('jf-button')]
        .find(b => /Copy report/.test(b.textContent ?? ''))!;
      copy.onActivate();
      await settle(shell);
      expect(JSON.parse(writes[0]!).recentExitCodes).toEqual({ SIGKILL: 16 });
    } finally {
      Reflect.deleteProperty(navigator, 'clipboard');
    }
  });

  it('“Report copied.” leaves the progress line when the next action starts', async () => {
    Object.defineProperty(navigator, 'clipboard', { value: { writeText: async () => {} }, configurable: true });
    try {
      const { shell } = await bootApp();
      supervisorEvent(exhausted);
      await settle(shell);
      const action = (name: RegExp) => [...treatment(shell)!.querySelectorAll<HTMLElement & { onActivate: () => unknown }>('jf-button')]
        .find(b => name.test(b.textContent ?? ''))!;
      const progress = () => treatment(shell)!.querySelector('.progress')!.textContent!.trim();
      action(/Copy report/).onActivate();
      await settle(shell);
      expect(progress()).toBe('Report copied.');
      mocks.restart.mockRejectedValue('The Engine is already starting or running; restart refused');
      action(/Restart the engine/).onActivate();
      await settle(shell);
      expect(progress()).toBe('The engine is already starting or running, so it can’t be restarted now. JustSearch keeps trying to reach it.');
    } finally {
      Reflect.deleteProperty(navigator, 'clipboard');
    }
  });
});
