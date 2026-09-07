// @vitest-environment happy-dom
/**
 * Tempdoc 941 — the stream handler-failure diagnostics ring.
 *
 * Mirrors `wireDriftTelemetry.test.ts`'s shape because the module deliberately mirrors
 * `wireDriftTelemetry.ts`: a localStorage ring, age-filtered, capped, silent on failure. The
 * property that matters is that a telemetry sink can never break the thing it observes — a stream
 * that survives a throwing handler must not then die on a full localStorage.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import {
  recordStreamHandlerFailure,
  readStreamHandlerFailures,
  summarizeStreamHandlerFailures,
} from './streamHandlerTelemetry.js';

const KEY = 'jf.stream-handler-telemetry';

describe('streamHandlerTelemetry', () => {
  beforeEach(() => localStorage.removeItem(KEY));
  afterEach(() => {
    localStorage.removeItem(KEY);
    vi.restoreAllMocks();
  });

  it('records the event and the error message', () => {
    recordStreamHandlerFailure('citations', new Error('renderer blew up'));
    expect(readStreamHandlerFailures()).toEqual([
      { event: 'citations', message: 'renderer blew up', timestamp: expect.any(Number) },
    ]);
  });

  it('accepts a non-Error throw without losing it', () => {
    recordStreamHandlerFailure('chunk', 'a bare string throw');
    expect(readStreamHandlerFailures()[0]?.message).toBe('a bare string throw');
  });

  it('truncates a runaway message rather than growing the key unbounded', () => {
    recordStreamHandlerFailure('chunk', new Error('x'.repeat(5000)));
    expect(readStreamHandlerFailures()[0]?.message).toHaveLength(300);
  });

  it('summarizes by event, most-broken first, with the last timestamp', () => {
    recordStreamHandlerFailure('chunk', new Error('a'));
    recordStreamHandlerFailure('citations', new Error('b'));
    recordStreamHandlerFailure('chunk', new Error('c'));
    const summary = summarizeStreamHandlerFailures();
    expect(summary.byEvent).toEqual([
      { event: 'chunk', count: 2 },
      { event: 'citations', count: 1 },
    ]);
    expect(summary.lastTimestamp).toEqual(expect.any(Number));
  });

  it('drops entries older than the retention window', () => {
    const stale = Date.now() - 8 * 24 * 60 * 60 * 1000;
    localStorage.setItem(KEY, JSON.stringify([{ event: 'chunk', message: 'old', timestamp: stale }]));
    expect(readStreamHandlerFailures()).toEqual([]);
    expect(summarizeStreamHandlerFailures().lastTimestamp).toBeNull();
  });

  it('caps the ring, keeping the most recent', () => {
    for (let i = 0; i < 250; i++) recordStreamHandlerFailure('chunk', new Error(`e${i}`));
    const entries = readStreamHandlerFailures();
    expect(entries).toHaveLength(200);
    expect(entries[entries.length - 1]?.message).toBe('e249');
  });

  it('degrades silently on unreadable or unwritable storage — telemetry never breaks a stream', () => {
    localStorage.setItem(KEY, 'not json at all');
    expect(readStreamHandlerFailures()).toEqual([]);
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('QuotaExceededError');
    });
    expect(() => recordStreamHandlerFailure('chunk', new Error('boom'))).not.toThrow();
    setItem.mockRestore();
  });
});
