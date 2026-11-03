// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it, vi } from 'vitest';
import { __resetAiInstallPollForTest, subscribeAiInstall, type AiInstallSnapshot } from './aiInstallPoll.js';

const json = (body: unknown) => new Response(JSON.stringify(body));
afterEach(() => { __resetAiInstallPollForTest(); vi.useRealTimers(); vi.unstubAllGlobals(); });

describe('AI status deadline repairs', () => {
  it('T2 a late admission refusal after stop cannot re-arm a deadline timer', async () => {
    vi.useFakeTimers();
    const finish: Array<(response: Response) => void> = [];
    const signals: AbortSignal[] = [];
    const fetch = vi.fn((_input: RequestInfo | URL, init?: RequestInit) => {
      signals.push(init!.signal!);
      return new Promise<Response>(resolve => finish.push(resolve));
    });
    vi.stubGlobal('fetch', fetch);
    const unsubscribe = subscribeAiInstall(() => {});
    await vi.advanceTimersByTimeAsync(0);
    expect(fetch).toHaveBeenCalledTimes(3);
    unsubscribe();
    await vi.advanceTimersByTimeAsync(0);
    expect(signals.every(signal => signal.aborted)).toBe(true);
    expect(vi.getTimerCount()).toBe(0);
    finish.forEach(resolve => resolve(new Response(JSON.stringify({ errorCode: 'ADMISSION_ENGINE_LIMIT', retrySafe: true }), {
      status: 429, headers: { 'Retry-After': '30' },
    })));
    await vi.advanceTimersByTimeAsync(0);
    expect(vi.getTimerCount()).toBe(0);
    await vi.advanceTimersByTimeAsync(35_000);
    expect(fetch).toHaveBeenCalledTimes(3);
  });

  it('T2 fast polling accepts a healthy loaded Engine read just before 5s', async () => {
    vi.useFakeTimers();
    const signals: AbortSignal[] = [];
    let slow = false;
    const fetch = vi.fn((_input: RequestInfo | URL, init?: RequestInit) => {
      signals.push(init!.signal!);
      return slow ? new Promise<Response>(resolve => setTimeout(() => resolve(json({ state: 'running' })), 4999))
        : Promise.resolve(json({ state: 'running' }));
    });
    vi.stubGlobal('fetch', fetch);
    const seen: AiInstallSnapshot[] = [];
    subscribeAiInstall(snapshot => seen.push(snapshot));
    await vi.advanceTimersByTimeAsync(0);
    slow = true;
    await vi.advanceTimersByTimeAsync(1000);
    expect(fetch).toHaveBeenCalledTimes(6);
    await vi.advanceTimersByTimeAsync(4998);
    expect(signals.every(signal => !signal.aborted)).toBe(true);
    await vi.advanceTimersByTimeAsync(1);
    expect(seen.at(-1)?.installObservation).toEqual({ latestReadFailed: false });
    expect(seen.at(-1)?.runtimeObservation).toEqual({ latestReadFailed: false });
    expect(seen.at(-1)?.packsObservation).toEqual({ latestReadFailed: false });
    expect(signals.every(signal => !signal.aborted)).toBe(true);
    await vi.advanceTimersByTimeAsync(999);
    expect(fetch).toHaveBeenCalledTimes(6);
    await vi.advanceTimersByTimeAsync(1);
    expect(fetch).toHaveBeenCalledTimes(9);
  });
});
