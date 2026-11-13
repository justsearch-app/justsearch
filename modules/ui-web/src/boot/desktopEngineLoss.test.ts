// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// S5c — engine loss after the app has loaded, desktop runtime: the real boot listener and the real
// Shell, with the native command and event channels as doubles. Supervisor states arrive through
// the same `justsearch://supervisor-state` listener the host feeds.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({ invoke: vi.fn(), listen: vi.fn(), restart: vi.fn() }));
vi.mock('../utils/tauriRuntime.js', () => ({ isTauriRuntime: () => true }));
vi.mock('./apiBase.js', () => ({ resolveBootApiBase: async () => 'http://127.0.0.1:4100' }));
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

type Updating = HTMLElement & { updateComplete: Promise<unknown> };
const running = {
  schemaVersion: 1, kind: 'engine-supervisor-state.v1', supervisor: 'tauri',
  state: 'running', incarnation: 1, restartCount: 0, maxRestartAttempts: 3,
};

function supervisorEvent(patch: Record<string, unknown>): void {
  const onState = mocks.listen.mock.calls.find(([name]) => name === 'justsearch://supervisor-state')![1];
  onState({ payload: { ...running, ...patch } });
}
function backendRestartEvent(): void {
  const onRestart = mocks.listen.mock.calls.find(([name]) => name === 'justsearch://backend-restart')![1];
  onRestart({ payload: {} });
}

/**
 * The new incarnation, at its new address, answers its manifest read (F-desktop B2 FD1a: a usable app
 * re-binds only to an engine that has proven alive); the old address stays as it was.
 */
function newEngineAnswers(): void {
  const base = mocks.invoke.getMockImplementation()!;
  mocks.invoke.mockImplementation(async (command: string, args?: unknown) => command === 'api_port' ? 4101 : base(command, args));
  const fetch = globalThis.fetch;
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) =>
    String(input) === 'http://127.0.0.1:4101/api/runtime/manifest'
      ? new Response(JSON.stringify({ instanceId: 'incarnation-2' }), { status: 200 }) : fetch(input, init)));
}
/** How long the restarted engine must stay up before the app re-binds (FD1a's stability window). */
const STABLE_MS = 5_000;

/** The engine is gone: no request answers, so nothing counts as contact with it. */
function engineGone(): void {
  vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch'); }));
}

async function resetEngineLoss(): Promise<void> {
  // Found by glob: before S5c the store does not exist, nothing matches and the checks fail by
  // assertion, not at import.
  const store = import.meta.glob<typeof import('../shell-v0/state/engineLoss.js')>('../shell-v0/state/engineLoss.ts');
  for (const load of Object.values(store)) (await load()).__resetEngineLossForTest();
}

beforeEach(async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date'] });
  vi.spyOn(window.navigator, 'platform', 'get').mockReturnValue('Linux x86_64');
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ entries: [] }), { status: 200 })));
  __resetAiStateForTest(); __resetStatusPollForTest(); __resetInferencePollForTest(); resetAppUpdate();
  await resetEngineLoss();
  mocks.listen.mockReset().mockResolvedValue(() => {});
  mocks.restart.mockReset().mockResolvedValue(undefined);
  mocks.invoke.mockReset().mockImplementation(async (command: string) => {
    if (command === 'factory_reset_status') return { phase: 'idle', remaining: [] };
    if (command === 'supervisor_state') return running;
    if (command === 'restart_engine') return mocks.restart();
    if (command === 'app_update_status') return { state: 'up_to_date', currentVersion: '1.0' };
    return undefined;
  });
  document.body.innerHTML = '<div id="root"></div>';
});
afterEach(async () => {
  document.body.replaceChildren();
  __resetAiStateForTest(); __resetStatusPollForTest(); __resetInferencePollForTest();
  __resetBootstrapForTest(); __resetUserConfigForTest(); __resetModalityForTest();
  await resetEngineLoss();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

async function pass(ms: number): Promise<void> {
  for (let left = ms; left > 0; left -= 1_000) await vi.advanceTimersByTimeAsync(Math.min(1_000, left));
}

/** Boot as main.jsx does, with the app presented and in contact with its engine. */
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

const deepText = (root: Node | null | undefined): string => {
  if (!root) return '';
  let text = '';
  for (const node of (root as ParentNode).childNodes ?? []) {
    if (node.nodeType === Node.TEXT_NODE) text += node.textContent;
    else if (node instanceof Element) {
      text += ` ${deepText(node)}`;
      if (node.shadowRoot) text += ` ${deepText(node.shadowRoot)}`;
    }
  }
  return text.replace(/\s+/g, ' ');
};

function treatment(shell: Updating): HTMLElement | null {
  return shell.shadowRoot!.querySelector('jf-engine-loss')?.shadowRoot?.querySelector<HTMLElement>('[data-testid="engine-loss"]') ?? null;
}

function button(shell: Updating, name: RegExp): (HTMLElement & { onActivate: () => unknown }) | null {
  return [...treatment(shell)?.querySelectorAll<HTMLElement & { onActivate: () => unknown }>('jf-button') ?? []]
    .find(b => name.test(b.textContent?.replace(/\s+/g, ' ').trim() ?? '')) ?? null;
}

/** Activate an action of the treatment; asserting first that it is offered. */
function click(shell: Updating, name: RegExp): void {
  const found = button(shell, name);
  expect(found, `${name} is offered`).not.toBeNull();
  found!.onActivate();
}

async function settle(shell: Updating): Promise<void> {
  await pass(1_000);
  await shell.updateComplete;
  await shell.shadowRoot!.querySelector<Updating>('jf-engine-loss')?.updateComplete;
}

describe('SC2 supervisor loss after the app has loaded keeps the app', () => {
  it.each([
    ['not ready', { notReady: 'Index: UNAVAILABLE (index.failed)' }, 'JustSearch’s engine isn’t ready', 'The engine is running, but its index is not ready.'],
    ['a terminal stop', { state: 'stopped', reason: 'crash' }, 'JustSearch can’t reach its engine', 'The engine stopped.'],
    ['exhausted restarts', { state: 'exhausted', restartCount: 3, recentExits: { count: 3, codes: { '1': 3 } } },
      'JustSearch can’t reach its engine', 'The engine stopped 3 times, so JustSearch stopped restarting it.'],
    ['exhausted on a locked data folder', { state: 'exhausted', restartCount: 1, reason: 'ENGINE_RESTART_EXHAUSTED:data_dir_locked',
      lastExit: { code: 2, reason: 'data_dir_locked', class: 'NON_TRANSIENT' }, recentExits: { count: 1, codes: { '2': 1 } } },
    'JustSearch can’t reach its engine', 'Another program is using JustSearch’s data folder. Close it, then restart the engine.'],
  ] as const)('%s: the same treatment, the reason in plain words, Health reachable', async (_name, patch, title, reason) => {
    const { root, shell, teardown } = await bootApp();
    supervisorEvent(patch);
    await settle(shell);
    expect(root.querySelector('jf-engine-recovery'), 'the app is not replaced').toBeNull();
    expect(shell.isConnected).toBe(true);
    expect(teardown, 'the app keeps its stores').not.toHaveBeenCalled();
    const words = deepText(treatment(shell));
    expect(words).toContain(title);
    expect(words).toContain(reason);
    expect(words).toContain('Searching and indexing are on hold. Your files and your index are safe.');
    expect(button(shell, /^Restart the engine$/)).not.toBeNull();
    expect(button(shell, /^Open Health$/), 'Health stays reachable').not.toBeNull();
    expect(button(shell, /^Copy report$/)).not.toBeNull();
    expect(words, 'restarting the engine is not reloading the window').not.toMatch(/reload|Restart JustSearch/i);
    expect(shell.shadowRoot!.querySelector<HTMLElement>('.stage')!.inert).toBe(true);
    // The engine comes back by itself: the treatment leaves without a reload.
    supervisorEvent({});
    __feedContactForTest();
    await settle(shell);
    expect(treatment(shell)).toBeNull();
    expect(shell.shadowRoot!.querySelector<HTMLElement>('.stage')!.inert).toBe(false);
  });

  it('before the app has loaded, the existing recovery page stays, also while the start-up frame still covers the app', async () => {
    const root = document.getElementById('root')!;
    root.innerHTML = '<div id="boot-frame"></div>';
    await resolveBootWithRecovery(root, vi.fn());
    // API discovery succeeded and the recovery page left; the frame is back over the root.
    expect(root.querySelector('#boot-frame')).not.toBeNull();
    const shell = document.createElement('jf-shell') as Updating;
    mountBootApplication(root, shell);
    expect(shell.inert, 'the app is still covered by the start-up frame').toBe(true);
    supervisorEvent({ state: 'exhausted', restartCount: 3 });
    expect(root.querySelector('jf-engine-recovery'), 'the recovery page takes over').not.toBeNull();
    expect(shell.isConnected).toBe(false);
  });

  it('the Linux update hold still replaces the loaded app', async () => {
    const { root, shell, teardown } = await bootApp();
    supervisorEvent({ state: 'exhausted', restartCount: 3 });
    await settle(shell);
    expect(treatment(shell)).not.toBeNull();
    supervisorEvent({ state: 'stopped', reason: 'upgrade' });
    expect(root.querySelector('jf-engine-recovery'), 'the update hold replaces the app').not.toBeNull();
    expect(shell.isConnected).toBe(false);
    expect(teardown).toHaveBeenCalledOnce();
  });

  it('Windows keeps its installer hand-off: an upgrade stop shows no treatment and keeps the app', async () => {
    vi.spyOn(window.navigator, 'platform', 'get').mockReturnValue('Win32');
    const { root, shell, teardown } = await bootApp();
    supervisorEvent({ state: 'stopped', reason: 'upgrade' });
    await settle(shell);
    expect(root.firstElementChild).toBe(shell);
    expect(teardown).not.toHaveBeenCalled();
    expect(treatment(shell)).toBeNull();
  });
});

describe('SC3 Restart the engine', () => {
  it('uses the native restart once for repeated clicks, shows it under way, and leaves when the engine answers', async () => {
    const reload = vi.spyOn(window.location, 'reload').mockImplementation(() => {});
    const { shell } = await bootApp();
    supervisorEvent({ state: 'exhausted', restartCount: 3 });
    engineGone();
    await settle(shell);
    let accept!: () => void;
    mocks.restart.mockImplementation(() => new Promise<void>(resolve => { accept = resolve; }));
    const restart = button(shell, /^Restart the engine$/);
    expect(restart).not.toBeNull();
    restart!.onActivate(); restart!.onActivate(); restart!.onActivate();
    await settle(shell);
    click(shell, /Restart/);
    accept();
    await settle(shell);
    expect(mocks.invoke.mock.calls.filter(([command]) => command === 'restart_engine'), 'one native restart').toHaveLength(1);
    expect(button(shell, /^Restarting the engine…$/), 'the button says it is under way').not.toBeNull();
    expect(deepText(treatment(shell))).toContain('Restarting the engine… This window stays open.');
    supervisorEvent({ state: 'starting', restartCount: 0 });
    await settle(shell);
    expect(deepText(treatment(shell)), 'still under way while it starts').toContain('Restarting the engine…');
    supervisorEvent({ state: 'running', restartCount: 0 });
    __feedContactForTest();
    await settle(shell);
    expect(treatment(shell), 'the engine answers: the treatment leaves').toBeNull();
    expect(reload, 'restarting the engine never reloads the window by itself').not.toHaveBeenCalled();
  });

  it('says when the restarted engine fails again, and a later restart is offered again', async () => {
    const { shell } = await bootApp();
    supervisorEvent({ state: 'exhausted', restartCount: 3 });
    engineGone();
    await settle(shell);
    click(shell, /^Restart the engine$/);
    await settle(shell);
    supervisorEvent({ state: 'starting' });
    await settle(shell);
    supervisorEvent({ state: 'exhausted', restartCount: 3 });
    await settle(shell);
    expect(deepText(treatment(shell))).toContain('The engine stopped again.');
    expect(button(shell, /^Restart the engine$/), 'offered again').not.toBeNull();
  });

  /** Lost on exhausted restarts with no engine answering, then Restart the engine accepted. */
  async function acceptedRestart(): Promise<Updating> {
    const { shell } = await bootApp();
    supervisorEvent({ state: 'exhausted', restartCount: 3 });
    engineGone();
    await settle(shell);
    click(shell, /^Restart the engine$/);
    await settle(shell);
    expect(mocks.invoke.mock.calls.filter(([command]) => command === 'restart_engine')).toHaveLength(1);
    supervisorEvent({ state: 'starting', restartCount: 0 });
    await settle(shell);
    return shell;
  }
  const underWay = (shell: Updating, when: string): void => {
    expect(treatment(shell), `${when}: the treatment stays`).not.toBeNull();
    expect(deepText(treatment(shell)), `${when}: a restart is under way`).toContain('Restarting the engine… This window stays open.');
    expect(button(shell, /^Restarting the engine…$/), `${when}: the button says so`).not.toBeNull();
    expect(shell.shadowRoot!.querySelector<HTMLElement>('.stage')!.inert, `${when}: the window area waits`).toBe(true);
  };

  it('a running report without contact keeps the restart under way; contact with the engine ends it', async () => {
    const reload = vi.spyOn(window.location, 'reload').mockImplementation(() => {});
    const shell = await acceptedRestart();
    supervisorEvent({ state: 'running', restartCount: 0 });
    await settle(shell);
    underWay(shell, 'running, no contact yet');
    await pass(30_000);
    await settle(shell);
    underWay(shell, 'running, still no contact');
    expect(deepText(treatment(shell)), 'no claim that the engine stopped while it restarts').not.toContain('The engine stopped');
    __feedContactForTest();
    await settle(shell);
    expect(treatment(shell), 'the engine answers: the treatment leaves').toBeNull();
    expect(shell.shadowRoot!.querySelector<HTMLElement>('.stage')!.inert).toBe(false);
    expect(reload, 'restarting the engine never reloads the window by itself').not.toHaveBeenCalled();
  });

  it('contact from before the restart does not end it', async () => {
    const shell = await acceptedRestart();
    __feedContactForTest(Date.now() - 5_000);
    supervisorEvent({ state: 'running', restartCount: 0 });
    await settle(shell);
    underWay(shell, 'an older contact stamp');
  });

  it('a restart that reports running and then fails again says so and offers the restart again', async () => {
    const shell = await acceptedRestart();
    supervisorEvent({ state: 'running', restartCount: 0 });
    await settle(shell);
    underWay(shell, 'running, no contact');
    supervisorEvent({ state: 'exhausted', restartCount: 3 });
    await settle(shell);
    expect(deepText(treatment(shell))).toContain('The engine stopped again.');
    expect(button(shell, /^Restart the engine$/), 'offered again').not.toBeNull();
  });

  it('a restart that reports running but not ready says so', async () => {
    const shell = await acceptedRestart();
    supervisorEvent({ state: 'running', restartCount: 0 });
    await settle(shell);
    supervisorEvent({ state: 'running', restartCount: 0, notReady: 'Index: UNAVAILABLE (index.failed)' });
    await settle(shell);
    expect(deepText(treatment(shell))).toContain('The engine started again, but its index is not ready.');
    expect(button(shell, /^Restart the engine$/), 'offered again').not.toBeNull();
  });

  it('a new engine identity after the restart rebinds by reloading the window, with the restart still shown until then', async () => {
    const reload = vi.spyOn(window.location, 'reload').mockImplementation(() => {});
    const shell = await acceptedRestart();
    supervisorEvent({ state: 'running', restartCount: 0, incarnation: 2 });
    await settle(shell);
    underWay(shell, 'before the rebind');
    expect(reload).not.toHaveBeenCalled();
    newEngineAnswers();
    backendRestartEvent();
    await settle(shell);
    underWay(shell, 'while the new engine proves it stays up');
    expect(reload).not.toHaveBeenCalled();
    await pass(STABLE_MS);
    expect(reload, 'the window rebinds once').toHaveBeenCalledOnce();
  });

  it.each([
    ['Quit or upgrade is in progress; restart refused', 'JustSearch is quitting or installing an update, so the engine can’t be restarted now.'],
    ['An update holds Engine replacement; restart refused', 'An update is being installed. The engine starts again when it finishes.'],
    ['Engine has no terminal state; restart refused', 'The engine is already starting or running, so it can’t be restarted now.'],
    ['The Engine is already starting or running; restart refused', 'The engine is already starting or running, so it can’t be restarted now.'],
    ['Engine was stopped for quit or upgrade; restart refused', 'JustSearch is quitting or installing an update, so the engine can’t be restarted now.'],
    ['The previous supervision generation is finishing; try again', 'The engine is still shutting down. Try again in a moment.'],
  ])('a refusal (%s) is said in plain words and the treatment stays usable', async (refusal, words) => {
    const { shell } = await bootApp();
    supervisorEvent({ notReady: 'Index: UNAVAILABLE (index.failed)' });
    await settle(shell);
    mocks.restart.mockRejectedValue(refusal);
    click(shell, /^Restart the engine$/);
    await settle(shell);
    const text = deepText(treatment(shell));
    expect(text).toContain(words);
    expect(text, 'the host’s own text is not shown').not.toContain(refusal);
    expect(button(shell, /^Restart the engine$/), 'can be asked again').not.toBeNull();
    expect(button(shell, /^Open Health$/)).not.toBeNull();
  });
});

describe('SC6 a new engine identity', () => {
  it('rebinds as today: the session is dropped and the window reloads', async () => {
    const reload = vi.spyOn(window.location, 'reload').mockImplementation(() => {});
    const { shell } = await bootApp();
    supervisorEvent({ state: 'exhausted', restartCount: 3 });
    await settle(shell);
    expect(treatment(shell)).not.toBeNull();
    // The supervisor's later retry brings up a new incarnation that stays up and answers.
    newEngineAnswers();
    supervisorEvent({ state: 'starting', restartCount: 0, incarnation: 2 });
    backendRestartEvent();
    supervisorEvent({ state: 'running', restartCount: 0, incarnation: 2 });
    expect(reload, 'not before the new engine has proven alive').not.toHaveBeenCalled();
    await pass(STABLE_MS);
    expect(reload).toHaveBeenCalledOnce();
  });
});

describe('SC5 Copy report on the desktop', () => {
  it('copies the supervisor facts as fixed fields and never its free text', async () => {
    // Assembled at run time so the source holds no key-shaped literal for the secret scan.
    const secret = ['sk', 'live', 'SECRET', '0042'].join('-');
    const writes: string[] = [];
    Object.defineProperty(navigator, 'clipboard', { value: { writeText: async (text: string) => { writes.push(text); } }, configurable: true });
    try {
      const { shell } = await bootApp();
      supervisorEvent({
        state: 'exhausted', restartCount: 3, pid: 4242, apiPort: 51234, instanceId: `inst-${secret}`,
        reason: `ENGINE_RESTART_EXHAUSTED:spawn_failed: C:\\Users\\secret-person\\jre\\java.exe ${secret}`,
        requestedReason: `/home/secret-person/${secret}`,
        notReady: `Index: FAILED (/home/secret-person/${secret})`,
        lastExit: { code: 3, reason: `custom ${secret}`, class: 'TRANSIENT' },
        recentExits: { count: 5, codes: { '3': 4, '1': 1, [`/home/${secret}`]: 9 } },
        crashFile: { name: `hs_err_${secret}.log`, at: '2026-11-10T01:02:03Z', exception: `EXCEPTION ${secret}`, problematicFrame: `C:\\secret-person\\${secret}.dll` },
        retryAt: '2026-11-10T02:00:00Z',
      });
      await settle(shell);
      mocks.restart.mockRejectedValue(`The Engine is already starting or running; restart refused ${secret} /home/secret-person`);
      click(shell, /^Restart the engine$/);
      await settle(shell);
      const copy = button(shell, /^Copy report$/);
      expect(copy).not.toBeNull();
      copy!.onActivate();
      await settle(shell);
      expect(writes).toHaveLength(1);
      const report = JSON.parse(writes[0]!) as Record<string, unknown>;
      expect(Object.keys(report).sort()).toEqual(['lastContactAgeSeconds', 'operatingSystem', 'productVersion', 'reason',
        'recentExitCodes', 'runtimeKind', 'supervisorState', 'time']);
      expect(report).toMatchObject({ runtimeKind: 'desktop', operatingSystem: 'Linux', supervisorState: 'exhausted',
        reason: 'restart_exhausted:spawn_failed', recentExitCodes: { '3': 4, '1': 1 } });
      for (const leak of [secret, 'secret-person', 'java.exe', '51234', '4242', 'hs_err', 'EXCEPTION', '127.0.0.1', '/home', 'C:\\']) {
        expect(writes[0], `no ${leak}`).not.toContain(leak);
      }
      expect(deepText(treatment(shell)), 'no supervisor or host text in the treatment either').not.toContain(secret);
    } finally {
      Reflect.deleteProperty(navigator, 'clipboard');
    }
  });
});
