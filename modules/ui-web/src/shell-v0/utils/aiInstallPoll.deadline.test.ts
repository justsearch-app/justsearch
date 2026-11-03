// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  __resetAiInstallPollForTest, refreshAiInstallNow, subscribeAiInstall,
  type AiInstallSnapshot,
} from './aiInstallPoll.js';

const json = (body: unknown) => ({ ok: true, json: async () => body }) as Response;
const healthy = {
  install: { state: 'running', phase: 'downloading' },
  runtime: { activation: { state: 'idle' } },
  packs: { state: 'idle', phase: 'idle' },
};

afterEach(() => {
  __resetAiInstallPollForTest();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('AI status read deadline', () => {
  for (const endpoint of ['install', 'runtime', 'packs'] as const) {
    it.each(['response', 'body'] as const)(`T1 abandons a held ${endpoint} %s and recovers on the next tick`, async (step) => {
      vi.useFakeTimers();
      let calls = 0;
      let heldSignal: AbortSignal | null | undefined;
      let finish!: () => void;
      const recovered = endpoint === 'runtime'
        ? { activation: { state: 'running' } }
        : { state: 'running', phase: 'recovered' };
      vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
        if (!url.endsWith(`/api/ai/${endpoint}/status`)) {
          const key = url.split('/').at(-2) as keyof typeof healthy;
          return Promise.resolve(json(healthy[key]));
        }
        calls += 1;
        if (calls !== 2) return Promise.resolve(json(calls === 1 ? healthy[endpoint] : recovered));
        heldSignal = init?.signal;
        // Deliberately ignore abort, as a held fetch/body need not cooperate with cancellation.
        if (step === 'response') return new Promise<Response>(resolve => {
          finish = () => resolve(json(healthy[endpoint]));
        });
        return Promise.resolve({ ok: true, json: () => new Promise(resolve => {
          finish = () => resolve(healthy[endpoint]);
        }) } as Response);
      }));
      const seen: AiInstallSnapshot[] = [];
      subscribeAiInstall(snapshot => seen.push(snapshot));
      await vi.advanceTimersByTimeAsync(0);
      expect(seen.at(-1)?.[endpoint]).toEqual(healthy[endpoint]);
      await vi.advanceTimersByTimeAsync(1000);
      expect(calls).toBe(2);
      await vi.advanceTimersByTimeAsync(4999);
      expect(heldSignal?.aborted).toBe(false);
      await vi.advanceTimersByTimeAsync(1);
      const failed = seen.at(-1);
      await vi.advanceTimersByTimeAsync(999);
      expect(calls).toBe(2);
      await vi.advanceTimersByTimeAsync(1);
      // This fails on the before-state because the held read prevents the next tick entirely.
      expect(calls).toBe(3);
      expect(heldSignal?.aborted).toBe(true);
      expect(failed?.[endpoint]).toEqual(healthy[endpoint]);
      expect(failed?.[`${endpoint}Observation`]).toEqual({ latestReadFailed: true });
      expect(seen.at(-1)?.[`${endpoint}Observation`]).toEqual({ latestReadFailed: false });
      expect(seen.at(-1)?.[endpoint]).toEqual(recovered);
      const publications = seen.length;
      finish();
      await vi.advanceTimersByTimeAsync(0);
      expect(seen).toHaveLength(publications);
      expect(seen.at(-1)?.[endpoint]).toEqual(recovered);
    });
  }

  it('T1 keeps polling when every response is held forever', async () => {
    vi.useFakeTimers();
    const signals: AbortSignal[] = [];
    const fetch = vi.fn((_url: string, init?: RequestInit) => {
      signals.push(init!.signal!);
      return new Promise<Response>(() => {});
    });
    vi.stubGlobal('fetch', fetch);
    const seen: AiInstallSnapshot[] = [];
    subscribeAiInstall(snapshot => seen.push(snapshot));
    await vi.advanceTimersByTimeAsync(18000);
    expect(fetch).toHaveBeenCalledTimes(12); // Batches at 0, 6s, 12s, 18s; each gets 5s to answer.
    expect(signals.slice(0, 9).every(signal => signal.aborted)).toBe(true);
    expect(signals.slice(9).every(signal => !signal.aborted)).toBe(true);
    expect(seen.at(-1)?.installObservation).toEqual({ latestReadFailed: true });
    expect(seen.at(-1)?.runtimeObservation).toEqual({ latestReadFailed: true });
    expect(seen.at(-1)?.packsObservation).toEqual({ latestReadFailed: true });
  });

  it('T2 uses the idle interval as the deadline and accepts a slow answer before it', async () => {
    vi.useFakeTimers();
    let slow = false;
    const signals: AbortSignal[] = [];
    const fetch = vi.fn((_url: string, init?: RequestInit) => {
      signals.push(init!.signal!);
      return slow ? new Promise<Response>(resolve => setTimeout(() => resolve(json({ state: 'idle' })), 4999))
        : Promise.resolve(json({ state: 'idle' }));
    });
    vi.stubGlobal('fetch', fetch);
    const seen: AiInstallSnapshot[] = [];
    subscribeAiInstall(snapshot => seen.push(snapshot));
    await vi.advanceTimersByTimeAsync(0);
    slow = true;
    await vi.advanceTimersByTimeAsync(5000);
    expect(fetch).toHaveBeenCalledTimes(6);
    await vi.advanceTimersByTimeAsync(4998);
    expect(signals.every(signal => !signal.aborted)).toBe(true);
    await vi.advanceTimersByTimeAsync(1);
    expect(seen.at(-1)?.installObservation).toEqual({ latestReadFailed: false });
    expect(seen.at(-1)?.runtimeObservation).toEqual({ latestReadFailed: false });
    expect(signals.every(signal => !signal.aborted)).toBe(true);
    await vi.advanceTimersByTimeAsync(4999);
    expect(fetch).toHaveBeenCalledTimes(6);
    await vi.advanceTimersByTimeAsync(1);
    expect(fetch).toHaveBeenCalledTimes(9);
  });

  it('T1/T2 abandons an idle read at 5s and shares one queued refresh after abandonment', async () => {
    vi.useFakeTimers();
    let hold = false;
    const signals: AbortSignal[] = [];
    const fetch = vi.fn((_url: string, init?: RequestInit) => {
      signals.push(init!.signal!);
      return hold ? new Promise<Response>(() => {}) : Promise.resolve(json({ state: 'idle' }));
    });
    vi.stubGlobal('fetch', fetch);
    subscribeAiInstall(() => {});
    await vi.advanceTimersByTimeAsync(0);
    hold = true;
    const read = refreshAiInstallNow();
    const queued = refreshAiInstallNow();
    expect(refreshAiInstallNow()).toBe(queued);
    await vi.advanceTimersByTimeAsync(4999);
    expect(fetch).toHaveBeenCalledTimes(6);
    expect(signals.slice(3).every(signal => !signal.aborted)).toBe(true);
    hold = false;
    await vi.advanceTimersByTimeAsync(1);
    expect(fetch).toHaveBeenCalledTimes(9);
    expect(signals.slice(3, 6).every(signal => signal.aborted)).toBe(true);
    await Promise.all([read, queued]);
    await vi.advanceTimersByTimeAsync(4999);
    expect(fetch).toHaveBeenCalledTimes(9);
    await vi.advanceTimersByTimeAsync(1);
    expect(fetch).toHaveBeenCalledTimes(12);
  });
});
