// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it, vi } from 'vitest';
import * as poll from './aiInstallPoll.js';

const json = () => new Response(JSON.stringify({ state: 'idle' }));
afterEach(() => { poll.__resetAiInstallPollForTest(); vi.useRealTimers(); vi.unstubAllGlobals(); });

describe('install refresh now', () => {
  it('reads immediately between ticks and resumes the regular schedule', async () => {
    vi.useFakeTimers();
    const fetch = vi.fn(async () => json()); vi.stubGlobal('fetch', fetch);
    const unsubscribe = poll.subscribeAiInstall(() => {});
    await vi.advanceTimersByTimeAsync(0);
    expect(fetch).toHaveBeenCalledTimes(3);
    expect(typeof poll.refreshAiInstallNow).toBe('function');
    await poll.refreshAiInstallNow();
    expect(fetch).toHaveBeenCalledTimes(6);
    await vi.advanceTimersByTimeAsync(4999); expect(fetch).toHaveBeenCalledTimes(6);
    await vi.advanceTimersByTimeAsync(1); expect(fetch).toHaveBeenCalledTimes(9);
    unsubscribe();
  });

  it('shares one queued read among concurrent callers without overlapping any endpoint', async () => {
    vi.useFakeTimers();
    const finish: Array<() => void> = [];
    const fetch = vi.fn(() => new Promise<Response>(resolve => finish.push(() => resolve(json()))));
    vi.stubGlobal('fetch', fetch); poll.subscribeAiInstall(() => {});
    expect(typeof poll.refreshAiInstallNow).toBe('function');
    const first = poll.refreshAiInstallNow(); const second = poll.refreshAiInstallNow();
    expect(second).toBe(first);
    // A live read holds the batch until it answers or reaches its one-interval deadline.
    await vi.advanceTimersByTimeAsync(999); expect(fetch).toHaveBeenCalledTimes(3);
    finish[0]!(); finish[1]!(); await vi.advanceTimersByTimeAsync(0);
    expect(fetch).toHaveBeenCalledTimes(3); // Even a different endpoint's pending read holds the batch.
    finish[2]!(); await vi.advanceTimersByTimeAsync(0);
    expect(fetch).toHaveBeenCalledTimes(6);
    await vi.advanceTimersByTimeAsync(4999); expect(fetch).toHaveBeenCalledTimes(6);
    finish.slice(3).forEach(resolve => resolve()); await Promise.all([first, second]);
    await vi.advanceTimersByTimeAsync(5000); expect(fetch).toHaveBeenCalledTimes(9);
  });

  it('does nothing without subscribers and drops queued reads when the last subscriber leaves', async () => {
    vi.useFakeTimers();
    const finish: Array<() => void> = [];
    const fetch = vi.fn(() => new Promise<Response>(resolve => finish.push(() => resolve(json()))));
    vi.stubGlobal('fetch', fetch);
    expect(typeof poll.refreshAiInstallNow).toBe('function');
    await poll.refreshAiInstallNow(); expect(fetch).not.toHaveBeenCalled();
    const unsubscribe = poll.subscribeAiInstall(() => {});
    const queued = poll.refreshAiInstallNow(); await vi.advanceTimersByTimeAsync(0); unsubscribe();
    finish.forEach(resolve => resolve()); await queued;
    await vi.advanceTimersByTimeAsync(20_000); expect(fetch).toHaveBeenCalledTimes(3);
    await poll.refreshAiInstallNow(); expect(fetch).toHaveBeenCalledTimes(3);
  });

  it('an obsolete queued read cannot refresh a replacement API base', async () => {
    vi.useFakeTimers();
    const finish: Array<() => void> = [];
    const fetch = vi.fn(() => new Promise<Response>(resolve => finish.push(() => resolve(json()))));
    vi.stubGlobal('fetch', fetch); poll.setAiInstallApiBase('http://old');
    poll.subscribeAiInstall(() => {});
    expect(typeof poll.refreshAiInstallNow).toBe('function');
    const queued = poll.refreshAiInstallNow();
    await vi.advanceTimersByTimeAsync(0);
    poll.setAiInstallApiBase('http://replacement');
    finish.slice(0, 3).forEach(resolve => resolve()); await queued;
    expect(fetch).toHaveBeenCalledTimes(6);
    finish.slice(3).forEach(resolve => resolve()); await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(5000); expect(fetch).toHaveBeenCalledTimes(9);
  });
});
