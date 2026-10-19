// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { startAutoLock, writeAutoLockMinutes } from './autoLock.js';

/** Tempdoc 629 (#10) — the auto-lock idle watcher fires a lock only when idle AND unlocked. */
describe('autoLock', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    localStorage.clear();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  function mockFetch(state: string) {
    return vi.fn(
      async (path: string) =>
        ({ json: async () => (path.endsWith('/encryption') ? { state } : {}) }) as Response,
    );
  }

  const LOCK = '/api/conversations/encryption/auto-lock';

  it('locks after the idle timeout when unlocked', async () => {
    writeAutoLockMinutes(5);
    let t = 0;
    const fetchFn = mockFetch('unlocked');
    const h = startAutoLock(fetchFn, () => t);
    t = 6 * 60_000; // idle clock past 5 min
    await vi.advanceTimersByTimeAsync(30_000); // one tick
    expect(fetchFn).toHaveBeenCalledWith(LOCK, { method: 'POST' });
    h.stop();
  });

  it('does not lock when the timeout is Off (0)', async () => {
    writeAutoLockMinutes(0);
    let t = 0;
    const fetchFn = mockFetch('unlocked');
    const h = startAutoLock(fetchFn, () => t);
    t = 60 * 60_000;
    await vi.advanceTimersByTimeAsync(30_000);
    expect(fetchFn).not.toHaveBeenCalled();
    h.stop();
  });

  it('does not lock when already locked', async () => {
    writeAutoLockMinutes(5);
    let t = 0;
    const fetchFn = mockFetch('locked');
    const h = startAutoLock(fetchFn, () => t);
    t = 6 * 60_000;
    await vi.advanceTimersByTimeAsync(30_000);
    expect(fetchFn).not.toHaveBeenCalledWith(LOCK, { method: 'POST' });
    h.stop();
  });

  it('does not lock before the timeout elapses', async () => {
    writeAutoLockMinutes(15);
    let t = 0;
    const fetchFn = mockFetch('unlocked');
    const h = startAutoLock(fetchFn, () => t);
    t = 5 * 60_000; // only 5 of 15 min idle
    await vi.advanceTimersByTimeAsync(30_000);
    expect(fetchFn).not.toHaveBeenCalled();
    h.stop();
  });
});
