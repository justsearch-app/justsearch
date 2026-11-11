// @vitest-environment happy-dom
import { describe, it, expect, beforeEach } from 'vitest';
import {
  getSeenCursor,
  markSeen,
  isNewSinceSeen,
  subscribeSeenCursor,
  __resetRecallCursor,
} from './recallCursor.js';

beforeEach(() => {
  globalThis.localStorage?.clear();
  __resetRecallCursor();
});

describe('recallCursor — the one seen authority', () => {
  it('starts empty ("never looked") so everything is new', () => {
    expect(getSeenCursor()).toBe('');
    expect(isNewSinceSeen('2026-01-01T00:00:00Z')).toBe(true);
  });

  it('markSeen advances the cursor and persists it', () => {
    markSeen('2026-01-01T00:00:05Z');
    expect(getSeenCursor()).toBe('2026-01-01T00:00:05Z');
    expect(isNewSinceSeen('2026-01-01T00:00:04Z')).toBe(false);
    expect(isNewSinceSeen('2026-01-01T00:00:06Z')).toBe(true);
    expect(globalThis.localStorage?.getItem('justsearch.recall.seen-cursor.v1')).toBe(
      '2026-01-01T00:00:05Z',
    );
  });

  it('is monotonic — a stale (older) mark never rewinds "seen"', () => {
    markSeen('2026-01-01T00:00:05Z');
    markSeen('2026-01-01T00:00:01Z');
    expect(getSeenCursor()).toBe('2026-01-01T00:00:05Z');
  });

  it('ignores an empty mark', () => {
    markSeen('2026-01-01T00:00:05Z');
    markSeen('');
    expect(getSeenCursor()).toBe('2026-01-01T00:00:05Z');
  });

  it('notifies subscribers only on a real advance', () => {
    let hits = 0;
    const unsub = subscribeSeenCursor(() => {
      hits += 1;
    });
    markSeen('2026-01-01T00:00:05Z'); // advance → notify
    markSeen('2026-01-01T00:00:02Z'); // stale → no notify
    expect(hits).toBe(1);
    unsub();
    markSeen('2026-01-01T00:00:09Z');
    expect(hits).toBe(1); // unsubscribed
  });

  // The Engine writes Instant.toString() (no fraction on a whole second, up to nine digits) and this
  // window writes three digits: lexically "…00.100Z" < "…00Z" although it is 100 ms later.
  it('orders whole-second, millisecond and finer timestamps by time, not by spelling', () => {
    markSeen('2026-01-01T00:00:00Z');
    expect(isNewSinceSeen('2026-01-01T00:00:00.100Z')).toBe(true);
    markSeen('2026-01-01T00:00:00.100Z');
    expect(getSeenCursor()).toBe('2026-01-01T00:00:00.100Z');
    expect(isNewSinceSeen('2026-01-01T00:00:00.1Z')).toBe(false);
    expect(isNewSinceSeen('2026-01-01T00:00:00.100001Z')).toBe(true);
    markSeen('2026-01-01T00:00:00.100000500Z');
    expect(isNewSinceSeen('2026-01-01T00:00:00.1000005Z')).toBe(false);
    expect(isNewSinceSeen('2026-01-01T00:00:00.100001Z')).toBe(true);
  });

  it('a whole-second mark after a later millisecond mark does not rewind the cursor', () => {
    markSeen('2026-01-01T00:00:00.900Z');
    markSeen('2026-01-01T00:00:00Z');
    expect(getSeenCursor()).toBe('2026-01-01T00:00:00.900Z');
    expect(globalThis.localStorage?.getItem('justsearch.recall.seen-cursor.v1')).toBe('2026-01-01T00:00:00.900Z');
  });
});
