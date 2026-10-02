// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { EPHEMERAL_TOAST_EVENT } from '../components/advisory/ephemeralToast.js';

const mocks = vi.hoisted(() => ({ send: vi.fn() }));
vi.mock('../api/authorizedFetch.js', async () => {
  const { fetchWithAdmissionWait } = await import('../../api/admissionFetch.js');
  return {
    authorizedFetch: (input: RequestInfo | URL, init?: RequestInit) =>
      fetchWithAdmissionWait(mocks.send, input, init),
  };
});

import { subscribeStatus, setStatusApiBase, refreshStatusNow, __resetStatusPollForTest } from './statusPoll.js';
import { subscribeInference, setInferenceApiBase, __resetInferencePollForTest } from './inferencePoll.js';

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: Error) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

beforeEach(() => { vi.useFakeTimers(); mocks.send.mockReset(); });
afterEach(() => {
  __resetStatusPollForTest();
  __resetInferencePollForTest();
  vi.useRealTimers();
});

describe.each([
  { name: 'status', subscribe: subscribeStatus, rebind: setStatusApiBase,
    interval: 10_000, snapshot: { uptimeMs: 123 } },
  { name: 'inference', subscribe: subscribeInference, rebind: setInferenceApiBase,
    interval: 5_000, snapshot: { generation: 123 } },
])('$name polling lifecycle', ({ subscribe, rebind, interval, snapshot }) => {
  it('keeps slow polls single-flight and resumes polling after completion', async () => {
    const pending = deferred<Response>();
    mocks.send.mockReturnValue(pending.promise);
    const listener = vi.fn();
    subscribe(listener);
    await vi.advanceTimersByTimeAsync(30_000);
    expect(mocks.send).toHaveBeenCalledTimes(1);
    expect(listener).not.toHaveBeenCalled();
    pending.resolve(new Response(JSON.stringify(snapshot)));
    await vi.advanceTimersByTimeAsync(0);
    expect(listener).toHaveBeenCalledExactlyOnceWith(snapshot);
    mocks.send.mockResolvedValue(new Response('{}'));
    await vi.advanceTimersByTimeAsync(interval);
    expect(mocks.send).toHaveBeenCalledTimes(2);
  });

  it('cancels an actual admission wait on last unsubscription', async () => {
    mocks.send.mockImplementation(async () => new Response(JSON.stringify({
      errorCode: 'ADMISSION_ENGINE_LIMIT', retrySafe: true,
    }), { status: 429, headers: { 'Retry-After': '3600' } }));
    const notice = new Promise<void>((resolve) => {
      document.addEventListener(EPHEMERAL_TOAST_EVENT, () => resolve(), { once: true });
    });
    const listener = vi.fn();
    const unsubscribe = subscribe(listener);
    await notice;
    await vi.advanceTimersByTimeAsync(30_000);
    expect(mocks.send).toHaveBeenCalledTimes(1);
    const signal = mocks.send.mock.calls[0]![1]?.signal as AbortSignal;
    unsubscribe();
    expect(signal.aborted).toBe(true);
    await vi.advanceTimersByTimeAsync(4_000_000);
    expect(mocks.send).toHaveBeenCalledTimes(1);
    expect(listener).not.toHaveBeenCalled();
    expect(vi.getTimerCount()).toBe(0);
  });

  it.each(['resubscribe', 'rebind'])('ignores obsolete JSON completion after %s', async (lifecycle) => {
    const oldBody = deferred<unknown>();
    const newRequest = deferred<Response>();
    mocks.send.mockResolvedValueOnce({ status: 200, ok: true, json: () => oldBody.promise } as Response)
      .mockReturnValueOnce(newRequest.promise);
    const oldListener = vi.fn();
    const unsubscribe = subscribe(oldListener);
    await vi.advanceTimersByTimeAsync(0);
    const oldSignal = mocks.send.mock.calls[0]![1]?.signal as AbortSignal;
    const newListener = lifecycle === 'resubscribe' ? vi.fn() : oldListener;
    if (lifecycle === 'resubscribe') {
      unsubscribe();
      subscribe(newListener);
    } else {
      rebind('http://replacement');
    }
    expect(oldSignal.aborted).toBe(true);
    expect(mocks.send).toHaveBeenCalledTimes(2);
    if (lifecycle === 'rebind') expect(mocks.send.mock.calls[1]![0]).toContain('http://replacement/');
    oldBody.resolve(snapshot);
    await vi.advanceTimersByTimeAsync(0);
    expect(newListener).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(30_000);
    expect(mocks.send).toHaveBeenCalledTimes(2);
    newRequest.resolve(new Response(JSON.stringify(snapshot)));
    await vi.advanceTimersByTimeAsync(0);
    expect(newListener).toHaveBeenCalledExactlyOnceWith(snapshot);
    const replayListener = vi.fn();
    subscribe(replayListener);
    expect(replayListener).toHaveBeenCalledExactlyOnceWith(snapshot);
  });

  it('ignores an obsolete network failure after resubscription', async () => {
    const oldRequest = deferred<Response>();
    const newRequest = deferred<Response>();
    mocks.send.mockReturnValueOnce(oldRequest.promise).mockReturnValueOnce(newRequest.promise);
    const unsubscribe = subscribe(vi.fn());
    unsubscribe();
    const listener = vi.fn();
    subscribe(listener);
    oldRequest.reject(new Error('obsolete binding'));
    await vi.advanceTimersByTimeAsync(0);
    expect(listener).not.toHaveBeenCalled();
    newRequest.resolve(new Response(JSON.stringify(snapshot)));
    await vi.advanceTimersByTimeAsync(0);
    expect(listener).toHaveBeenCalledExactlyOnceWith(snapshot);
  });
});

it('queues one post-action status read and resolves refreshes only after it publishes', async () => {
  const beforeAction = deferred<Response>();
  const afterAction = deferred<Response>();
  mocks.send.mockReturnValueOnce(beforeAction.promise).mockReturnValueOnce(afterAction.promise);
  const listener = vi.fn();
  subscribeStatus(listener);
  const first = refreshStatusNow();
  const second = refreshStatusNow();
  let refreshed = false;
  void first.then(() => { refreshed = true; });
  expect(mocks.send).toHaveBeenCalledTimes(1);
  await vi.advanceTimersByTimeAsync(30_000);
  expect(mocks.send).toHaveBeenCalledTimes(1);
  beforeAction.resolve(new Response(JSON.stringify({ conversationProtection: { state: 'locked' } })));
  await vi.advanceTimersByTimeAsync(0);
  expect(mocks.send).toHaveBeenCalledTimes(2);
  expect(listener).toHaveBeenLastCalledWith({ conversationProtection: { state: 'locked' } });
  expect(refreshed).toBe(false);
  await vi.advanceTimersByTimeAsync(30_000);
  expect(mocks.send).toHaveBeenCalledTimes(2);
  afterAction.resolve(new Response(JSON.stringify({ conversationProtection: { state: 'unlocked' } })));
  await Promise.all([first, second]);
  expect(listener).toHaveBeenCalledTimes(2);
  expect(listener).toHaveBeenLastCalledWith({ conversationProtection: { state: 'unlocked' } });
  expect(refreshed).toBe(true);
});

it('queues another fresh read for an action during the follow-up request', async () => {
  const pending = Array.from({ length: 3 }, () => deferred<Response>());
  for (const request of pending) mocks.send.mockReturnValueOnce(request.promise);
  subscribeStatus(vi.fn());
  const firstRefresh = refreshStatusNow();
  pending[0]!.resolve(new Response('{}'));
  await vi.advanceTimersByTimeAsync(0);
  expect(mocks.send).toHaveBeenCalledTimes(2);
  const secondRefresh = refreshStatusNow();
  let secondDone = false;
  void secondRefresh.then(() => { secondDone = true; });
  pending[1]!.resolve(new Response('{}'));
  await firstRefresh;
  await vi.advanceTimersByTimeAsync(0);
  expect(mocks.send).toHaveBeenCalledTimes(3);
  expect(secondDone).toBe(false);
  pending[2]!.resolve(new Response('{}'));
  await secondRefresh;
  expect(secondDone).toBe(true);
});

it.each(['resubscribe', 'rebind'])('discards queued refreshes from the old lifecycle after %s', async (lifecycle) => {
  const oldRequest = deferred<Response>();
  const newRequest = deferred<Response>();
  mocks.send.mockReturnValueOnce(oldRequest.promise).mockReturnValueOnce(newRequest.promise);
  const unsubscribe = subscribeStatus(vi.fn());
  const oldRefresh = refreshStatusNow();
  const listener = vi.fn();
  if (lifecycle === 'resubscribe') {
    unsubscribe();
    subscribeStatus(listener);
  } else {
    setStatusApiBase('http://replacement');
    subscribeStatus(listener);
  }
  oldRequest.resolve(new Response('{}'));
  await oldRefresh;
  await vi.advanceTimersByTimeAsync(0);
  expect(mocks.send).toHaveBeenCalledTimes(2);
  expect(listener).not.toHaveBeenCalled();
  newRequest.resolve(new Response(JSON.stringify({ uptimeMs: 456 })));
  await vi.advanceTimersByTimeAsync(0);
  expect(listener).toHaveBeenCalledExactlyOnceWith({ uptimeMs: 456 });
});
