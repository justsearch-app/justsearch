import { afterEach, describe, expect, it, vi } from 'vitest';
import { StopAttempt, STOP_RESPONSE_WAIT_MS } from './stopAttempt.js';

afterEach(() => vi.useRealTimers());

describe('shared Ask Stop and agent Halt delivery vocabulary', () => {
  it.each([true, false])('latches intent, separates acceptance from refusal (ok=%s), and deduplicates pending delivery', async (ok) => {
    const stop = new StopAttempt();
    let release!: (response: { ok: boolean }) => void;
    const request = vi.fn(() => new Promise<{ ok: boolean }>((resolve) => { release = resolve; }));
    const first = stop.deliver(request);
    expect(stop.intent).toBe(true); expect(stop.pending).toBe(true);
    await stop.deliver(request); expect(request).toHaveBeenCalledTimes(1);
    release({ ok }); await first;
    expect(stop.outcome).toBe(ok ? 'accepted' : 'refused'); expect(stop.pending).toBe(false);
    if (!ok) {
      const retry = stop.deliver(request); expect(request).toHaveBeenCalledTimes(2);
      release({ ok: true }); await retry; expect(stop.outcome).toBe('accepted');
    }
    await stop.deliver(request); expect(stop.attempt).toBe(ok ? 1 : 2);
  });

  it('a missing answer becomes unknown; its late response cannot settle the new attempt', async () => {
    vi.useFakeTimers();
    const stop = new StopAttempt();
    let late!: (response: { ok: boolean }) => void;
    const first = stop.deliver(() => new Promise((resolve) => { late = resolve; }), STOP_RESPONSE_WAIT_MS);
    await vi.advanceTimersByTimeAsync(STOP_RESPONSE_WAIT_MS); await first;
    expect(stop.outcome).toBe('unknown');
    await stop.deliver(async () => ({ ok: false }));
    late({ ok: true }); await Promise.resolve();
    expect(stop.outcome).toBe('refused'); expect(stop.attempt).toBe(2);
  });
});
