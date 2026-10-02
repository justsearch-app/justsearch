// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({ send: vi.fn(), invoke: vi.fn(), listen: vi.fn() }));
vi.mock('../utils/tauriRuntime.js', () => ({ isTauriRuntime: () => true }));
vi.mock('./apiBase.js', () => ({ resolveBootApiBase: async () => 'http://bound' }));
vi.mock('@tauri-apps/api/core', () => ({ invoke: mocks.invoke }));
vi.mock('@tauri-apps/api/event', () => ({ listen: mocks.listen }));
vi.mock('../shell-v0/api/authorizedFetch.js', async () => {
  const { fetchWithAdmissionWait } = await import('../api/admissionFetch.js');
  return { authorizedFetch: (input: RequestInfo | URL, init?: RequestInit) =>
    fetchWithAdmissionWait(mocks.send, input, init) };
});

import { mountBootApplication, resolveBootWithRecovery } from './desktopRecovery.js';
import { startAiStateStore, stopAiStateStore, __resetAiStateForTest } from '../shell-v0/state/aiStateStore.js';
import { __resetStatusPollForTest } from '../shell-v0/utils/statusPoll.js';
import { __resetInferencePollForTest } from '../shell-v0/utils/inferencePoll.js';
import { __resetForTest as resetAppUpdate } from '../shell-v0/state/appUpdateState.js';
import { EPHEMERAL_TOAST_EVENT } from '../shell-v0/components/advisory/ephemeralToast.js';

const terminal = {
  schemaVersion: 1, kind: 'engine-supervisor-state.v1', supervisor: 'tauri',
  state: 'exhausted', incarnation: 1, restartCount: 3, maxRestartAttempts: 3,
};

beforeEach(() => {
  vi.useFakeTimers();
  __resetAiStateForTest();
  __resetStatusPollForTest();
  __resetInferencePollForTest();
  resetAppUpdate();
  mocks.send.mockReset();
  mocks.listen.mockReset().mockResolvedValue(() => {});
  mocks.invoke.mockReset().mockImplementation(async (command) => command === 'supervisor_state'
    ? { ...terminal, state: 'running' } : { state: 'up_to_date', currentVersion: '1.0' });
  document.body.innerHTML = '<div id="root"></div>';
});
afterEach(() => {
  document.body.innerHTML = '';
  __resetAiStateForTest();
  __resetStatusPollForTest();
  __resetInferencePollForTest();
  vi.useRealTimers();
});

function supervisorEvent(state: string): void {
  const onState = mocks.listen.mock.calls.find(([name]) => name === 'justsearch://supervisor-state')![1];
  onState({ payload: { ...terminal, state } });
}

describe('terminal recovery application polling teardown', () => {
  it.each(['pending', 'scheduled'])('stops all real store pollers with %s requests', async (phase) => {
    const finish: Array<(response: Response) => void> = [];
    mocks.send.mockImplementation(() => new Promise<Response>((resolve) => { finish.push(resolve); }));
    const root = document.getElementById('root')!;
    await resolveBootWithRecovery(root, stopAiStateStore);
    mountBootApplication(root, document.createElement('div'));
    startAiStateStore('http://bound');
    expect(mocks.send).toHaveBeenCalledTimes(5);
    const signals = mocks.send.mock.calls.map(([, init]) => init?.signal as AbortSignal | undefined);
    expect(signals.every((signal) => !signal?.aborted)).toBe(true);
    if (phase === 'scheduled') {
      finish.forEach((resolve) => resolve(new Response('{}')));
      await vi.advanceTimersByTimeAsync(0);
      expect(vi.getTimerCount()).toBe(4);
    }
    supervisorEvent('restarting');
    expect(signals.every((signal) => !signal?.aborted)).toBe(true);
    supervisorEvent('exhausted');
    expect(root.querySelector('jf-engine-recovery')).not.toBeNull();
    expect(signals.every((signal) => signal?.aborted === true)).toBe(true);
    if (phase === 'pending') finish.forEach((resolve) => resolve(new Response('{}')));
    await vi.advanceTimersByTimeAsync(30_000);
    expect(mocks.send).toHaveBeenCalledTimes(5);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('aborts admission retry waits owned by all five store requests', async () => {
    mocks.send.mockImplementation(async () => new Response(JSON.stringify({
      errorCode: 'ADMISSION_ENGINE_LIMIT', retrySafe: true,
    }), { status: 429, headers: { 'Retry-After': '3600' } }));
    let notices = 0;
    const waiting = new Promise<void>((resolve) => {
      const onNotice = () => {
        if (++notices === 5) {
          document.removeEventListener(EPHEMERAL_TOAST_EVENT, onNotice);
          resolve();
        }
      };
      document.addEventListener(EPHEMERAL_TOAST_EVENT, onNotice);
    });
    const root = document.getElementById('root')!;
    await resolveBootWithRecovery(root, stopAiStateStore);
    startAiStateStore('http://bound');
    await waiting;
    supervisorEvent('exhausted');
    await vi.advanceTimersByTimeAsync(4_000_000);
    expect(mocks.send).toHaveBeenCalledTimes(5);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('tears down a late store start when terminal recovery prevents application mounting', async () => {
    mocks.send.mockImplementation(() => new Promise<Response>(() => {}));
    const root = document.getElementById('root')!;
    await resolveBootWithRecovery(root, stopAiStateStore);
    supervisorEvent('exhausted');
    startAiStateStore('http://bound');
    const application = document.createElement('div');
    mountBootApplication(root, application);
    expect(application.isConnected).toBe(false);
    expect(mocks.send.mock.calls.every(([, init]) => init?.signal?.aborted === true)).toBe(true);
    expect(vi.getTimerCount()).toBe(0);
  });
});
