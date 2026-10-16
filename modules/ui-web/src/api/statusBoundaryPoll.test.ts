// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import { startAiStateStore, stopAiStateStore, getAiState, __resetAiStateForTest } from '../shell-v0/state/aiStateStore.js';
import '../shell-v0/views/SecuritySurface.js';
import fixture from './__fixtures__/status-response-live.json';
import { observeStatusEvent, observeStatusResponse } from './statusInvalidation.js';
import { subscribeStatus, __resetStatusPollForTest, type StatusSnapshot } from '../shell-v0/utils/statusPoll.js';

afterEach(() => { document.body.replaceChildren(); stopAiStateStore(); __resetAiStateForTest(); __resetStatusPollForTest(); vi.useRealTimers(); vi.unstubAllGlobals(); });
const body = (epoch: string, change: number, reflected: number, state = 'READY') => {
  const value = structuredClone(fixture);
  value.operating.chat.state = state;
  return new Response(JSON.stringify(value), { headers: {
    'X-JustSearch-Status-Epoch': epoch, 'X-JustSearch-Status-Change': String(change),
    'X-JustSearch-Status-Reflected': String(reflected),
  } });
};

it('a restarted head with a refused read clears subscribers and cannot replay the old body to a new subscriber', async () => {
  vi.useFakeTimers();
  const send = vi.fn(async (input: RequestInfo | URL) => String(input).includes('fresh=true')
    ? new Response('{}', { status: 500, headers: {
      'X-JustSearch-Status-Epoch': 'B', 'X-JustSearch-Status-Change': '1',
    } }) : body('A', 3, 3));
  vi.stubGlobal('fetch', send);
  let latest: StatusSnapshot | null = null;
  subscribeStatus(value => { latest = value; });
  await vi.advanceTimersByTimeAsync(0);
  expect(latest!.operating!.chat!.state).toBe('READY');
  await observeStatusResponse('/api/read-only', body('B', 1, 1));
  expect(latest).toBeNull();
  let replayed = false;
  subscribeStatus(value => { if (value) replayed = true; });
  send.mockImplementation(async () => new Response(JSON.stringify(fixture)));
  await vi.advanceTimersByTimeAsync(10000);
  expect(latest).toBeNull();
  expect(replayed).toBe(false);
  expect(send.mock.calls.filter(([input]) => String(input).includes('fresh=true'))).toHaveLength(1);
});

it('an ordinary cached poll schedules one fresh read and a failed read never schedules itself', async () => {
  vi.useFakeTimers();
  let changed = false;
  const send = vi.fn(async (input: RequestInfo | URL) => String(input).includes('fresh=true')
    ? new Response('{}', { status: 500, headers: {
      'X-JustSearch-Status-Epoch': 'A', 'X-JustSearch-Status-Change': '3',
    } }) : body('A', changed ? 2 : 1, 1));
  vi.stubGlobal('fetch', send);
  subscribeStatus(() => {});
  await vi.advanceTimersByTimeAsync(0);
  changed = true;
  await vi.advanceTimersByTimeAsync(10000);
  const fresh = () => send.mock.calls.filter(([input]) => String(input).includes('fresh=true')).length;
  expect(fresh()).toBeLessThanOrEqual(2);
  expect(fresh()).toBeGreaterThan(0);
  const reads = fresh();
  await vi.advanceTimersByTimeAsync(9000);
  expect(fresh()).toBe(reads);
});


it.each([true, false])('the mounted Security view discards the prior epoch while preserving transient same-epoch failures (metadata=%s)', async knownEpoch => {
  vi.useFakeTimers();
  const value = structuredClone(fixture);
  value.conversationProtection = { state: 'unlocked' };
  const send = vi.fn(async (input: RequestInfo | URL) => String(input).includes('/api/status')
    ? new Response(JSON.stringify(value), { headers: knownEpoch ? { 'X-JustSearch-Status-Epoch': 'A',
      'X-JustSearch-Status-Change': '3', 'X-JustSearch-Status-Reflected': '3' } : {} })
    : new Response('{}'));
  vi.stubGlobal('fetch', send);
  startAiStateStore('');
  const surface = document.createElement('jf-security-surface') as HTMLElement & {
    host_: unknown; status: StatusSnapshot | null; encState: string;
  };
  surface.host_ = { data: { fetch: async () => new Response(JSON.stringify({ state: 'unlocked' })) } };
  document.body.append(surface);
  await vi.advanceTimersByTimeAsync(0);
  expect(surface.status?.conversationProtection?.state).toBe('unlocked');
  send.mockImplementation(async () => new Response('{}', { status: 500 }));
  await vi.advanceTimersByTimeAsync(10000);
  expect(getAiState().status?.conversationProtection?.state).toBe('unlocked');
  await observeStatusResponse('/api/read-only', body('B', knownEpoch ? 1 : 0, knownEpoch ? 1 : 0));
  await vi.advanceTimersByTimeAsync(0);
  expect(getAiState().status).toBeNull();
  expect(surface.status).toBeNull();
  expect(surface.encState).toBe('unknown');
});

it('restarting the store cannot retain the previous head body when the next head refuses its first read', async () => {
  vi.useFakeTimers();
  const send = vi.fn(async (input: RequestInfo | URL) => String(input).includes('/api/status')
    ? body('A', 3, 3) : new Response('{}'));
  vi.stubGlobal('fetch', send);
  startAiStateStore('');
  await vi.advanceTimersByTimeAsync(0);
  expect(getAiState().status?.operating?.chat?.state).toBe('READY');
  send.mockImplementation(async () => new Response('{}', { status: 500, headers: {
    'X-JustSearch-Status-Epoch': 'B', 'X-JustSearch-Status-Change': '0',
  } }));
  stopAiStateStore();
  startAiStateStore('');
  await vi.advanceTimersByTimeAsync(0);
  expect(getAiState().status).toBeNull();
});

// b82 S4: a proxy strips the status headers, while SSE envelopes carry the publication epoch in
// their JSON. Learning that epoch must not block every later headerless canonical body forever.
it.each([false, true])('headerlessStatusConvergesAfterSseEpoch (SSE before the first body=%s)', async sseFirst => {
  vi.useFakeTimers();
  let state = 'READY';
  const send = vi.fn(async (input: RequestInfo | URL) => {
    if (!String(input).includes('/api/status')) return new Response('{}');
    const value = structuredClone(fixture);
    value.operating.chat.state = state;
    return new Response(JSON.stringify(value)); // no status headers survive the proxy
  });
  vi.stubGlobal('fetch', send);
  let latest: StatusSnapshot | null = null;
  subscribeStatus(value => { latest = value; });
  if (sseFirst) await observeStatusEvent({ statusChangeEpoch: 'A', statusChangeCounter: 5 });
  await vi.advanceTimersByTimeAsync(0);
  if (!sseFirst) {
    expect(latest!.operating!.chat!.state, 'the first headerless body displays').toBe('READY');
    await observeStatusEvent({ statusChangeEpoch: 'A', statusChangeCounter: 5 });
  }
  state = 'OFF';
  await observeStatusEvent({ statusChangeEpoch: 'A', statusChangeCounter: 6 });
  // Two intervals of successful ordinary polls.
  await vi.advanceTimersByTimeAsync(20000);
  expect(latest, 'canonical status is visible again').not.toBeNull();
  expect(latest!.operating!.chat!.state, 'and it is the current canonical state').toBe('OFF');
});
