// @vitest-environment happy-dom

/**
 * Tests for the framework-agnostic EnvelopeStream<T>.
 *
 * Uses a fake EventSource that exposes hooks for the test harness to
 * simulate frame arrival, open events, and error events without a
 * real network connection.
 */

import { afterEach, describe, expect, it, beforeEach, vi } from 'vitest';
import { EnvelopeStream } from './EnvelopeStream.js';
import type { SseEnvelope } from './envelope-types.js';
import {
  getLastOriginContactMs,
  __resetOriginContactForTest,
} from '../state/originContact.js';

class FakeEventSource extends EventTarget {
  url: string;
  closed = false;
  /**
   * Mirrors the browser EventSource.readyState. The 604 re-establishment logic reads this in the
   * error handler: CONNECTING (0) ⟹ the browser is retrying natively; CLOSED (2) ⟹ the browser
   * gave up (5xx/abnormal) and the FE must reconnect. Default CONNECTING — a transient drop.
   */
  readyState = 0;
  constructor(url: string) {
    super();
    this.url = url;
  }
  emitFrame(envelope: SseEnvelope): void {
    this.dispatchEvent(
      new MessageEvent('frame', { data: JSON.stringify(envelope) }),
    );
  }
  emitOpen(): void {
    this.readyState = 1; // OPEN
    this.dispatchEvent(new Event('open'));
  }
  /** Simulate an error; pass `closed` to mark the connection FAILED (readyState CLOSED). */
  emitError(closed = false): void {
    this.readyState = closed ? 2 : 0;
    this.dispatchEvent(new Event('error'));
  }
  close(): void {
    this.closed = true;
    this.readyState = 2;
  }
}

interface CounterState {
  count: number;
  lastKind: string | null;
  resetCount: number;
}

const COUNTER_INITIAL: CounterState = {
  count: 0,
  lastKind: null,
  resetCount: 0,
};

const counterReducer = (
  state: CounterState,
  envelope: SseEnvelope,
): CounterState => {
  const payload = envelope.payload as { kind: string };
  if (envelope.frameKind === 'LIFECYCLE') {
    if (payload.kind === 'reset') {
      return { ...state, resetCount: state.resetCount + 1, lastKind: 'reset' };
    }
    return { ...state, lastKind: payload.kind };
  }
  return {
    ...state,
    count: state.count + 1,
    lastKind: payload.kind,
  };
};

function makeStream(
  source: FakeEventSource,
  config?: { initialResumeToken?: string },
): EnvelopeStream<CounterState> {
  return new EnvelopeStream<CounterState>({
    url: 'http://test/api/x/stream',
    initialState: COUNTER_INITIAL,
    reducer: counterReducer,
    eventSourceFactory: () => source as unknown as EventSource,
    initialResumeToken: config?.initialResumeToken,
  });
}

describe('EnvelopeStream lifecycle', () => {
  it('opens the EventSource on start() and closes on stop()', () => {
    const fake = new FakeEventSource('http://test/api/x/stream');
    const stream = makeStream(fake);
    stream.start();
    expect(fake.closed).toBe(false);
    stream.stop();
    expect(fake.closed).toBe(true);
  });

  it('start() is idempotent', () => {
    const fake = new FakeEventSource('http://test/api/x/stream');
    let factoryCalls = 0;
    const stream = new EnvelopeStream<CounterState>({
      url: 'http://test/api/x/stream',
      initialState: COUNTER_INITIAL,
      reducer: counterReducer,
      eventSourceFactory: () => {
        factoryCalls++;
        return fake as unknown as EventSource;
      },
    });
    stream.start();
    stream.start();
    expect(factoryCalls).toBe(1);
    stream.stop();
  });

  it('stop() is idempotent', () => {
    const fake = new FakeEventSource('http://test/api/x/stream');
    const stream = makeStream(fake);
    stream.start();
    stream.stop();
    stream.stop(); // Should not throw
    expect(fake.closed).toBe(true);
  });

  it('appends ?since=<token> when initialResumeToken is set', () => {
    let capturedUrl = '';
    const stream = new EnvelopeStream<CounterState>({
      url: 'http://test/api/x/stream',
      initialState: COUNTER_INITIAL,
      reducer: counterReducer,
      eventSourceFactory: (url) => {
        capturedUrl = url;
        return new FakeEventSource(url) as unknown as EventSource;
      },
      initialResumeToken: 'abc123',
    });
    stream.start();
    expect(capturedUrl).toBe('http://test/api/x/stream?since=abc123');
    stream.stop();
  });

  it('uses & instead of ? when URL already has a query string', () => {
    let capturedUrl = '';
    const stream = new EnvelopeStream<CounterState>({
      url: 'http://test/api/x/stream?dataset=foo',
      initialState: COUNTER_INITIAL,
      reducer: counterReducer,
      eventSourceFactory: (url) => {
        capturedUrl = url;
        return new FakeEventSource(url) as unknown as EventSource;
      },
      initialResumeToken: 'abc123',
    });
    stream.start();
    expect(capturedUrl).toBe(
      'http://test/api/x/stream?dataset=foo&since=abc123',
    );
    stream.stop();
  });
});

describe('EnvelopeStream frame processing', () => {
  it('routes UPDATE frames through reducer and updates seq/resumeToken/isConnected', () => {
    const fake = new FakeEventSource('http://test/api/x/stream');
    const stream = makeStream(fake);
    stream.start();

    fake.emitFrame({
      streamId: 'x/v1',
      frameKind: 'UPDATE',
      seq: 5,
      ts: '2026-05-05T00:00:00Z',
      payload: { kind: 'item-added' },
      resumeToken: 'tok-5',
    });

    const snap = stream.getSnapshot();
    expect(snap.payload.count).toBe(1);
    expect(snap.payload.lastKind).toBe('item-added');
    expect(snap.seq).toBe(5);
    expect(snap.resumeToken).toBe('tok-5');
    expect(snap.isConnected).toBe(true);
    stream.stop();
  });

  it('routes LIFECYCLE frames through reducer', () => {
    const fake = new FakeEventSource('http://test/api/x/stream');
    const stream = makeStream(fake);
    stream.start();

    fake.emitFrame({
      streamId: 'x/v1',
      frameKind: 'LIFECYCLE',
      seq: 1,
      ts: '2026-05-05T00:00:00Z',
      payload: { kind: 'connected' },
      resumeToken: 'tok-1',
    });
    fake.emitFrame({
      streamId: 'x/v1',
      frameKind: 'LIFECYCLE',
      seq: 2,
      ts: '2026-05-05T00:00:01Z',
      payload: { kind: 'reset' },
      resumeToken: 'tok-2',
    });

    const snap = stream.getSnapshot();
    expect(snap.payload.resetCount).toBe(1);
    expect(snap.payload.lastKind).toBe('reset');
    expect(snap.seq).toBe(2);
    expect(snap.resumeToken).toBe('tok-2');
    stream.stop();
  });

  it('ignores malformed JSON frames', () => {
    const fake = new FakeEventSource('http://test/api/x/stream');
    const stream = makeStream(fake);
    stream.start();
    fake.dispatchEvent(new MessageEvent('frame', { data: '{bad json' }));
    expect(stream.getSnapshot().seq).toBe(0);
    stream.stop();
  });

  it('ignores frames missing seq or with wrong frameKind', () => {
    const fake = new FakeEventSource('http://test/api/x/stream');
    const stream = makeStream(fake);
    stream.start();
    fake.dispatchEvent(
      new MessageEvent('frame', {
        data: JSON.stringify({
          streamId: 'x/v1',
          frameKind: 'BOGUS',
          seq: 1,
          ts: '...',
          payload: {},
          resumeToken: 'tok',
        }),
      }),
    );
    fake.dispatchEvent(
      new MessageEvent('frame', {
        data: JSON.stringify({
          streamId: 'x/v1',
          frameKind: 'UPDATE',
          ts: '...',
          payload: { kind: 'noop' },
          resumeToken: 'tok',
        }),
      }),
    );
    expect(stream.getSnapshot().seq).toBe(0);
    stream.stop();
  });

  it('catches reducer errors and preserves prior payload while still advancing seq', () => {
    const fake = new FakeEventSource('http://test/api/x/stream');
    const stream = new EnvelopeStream<CounterState>({
      url: 'http://test/api/x/stream',
      initialState: COUNTER_INITIAL,
      reducer: (_state, _env) => {
        throw new Error('reducer boom');
      },
      eventSourceFactory: () => fake as unknown as EventSource,
    });
    stream.start();
    fake.emitFrame({
      streamId: 'x/v1',
      frameKind: 'UPDATE',
      seq: 7,
      ts: '...',
      payload: { kind: 'noop' },
      resumeToken: 'tok-7',
    });
    const snap = stream.getSnapshot();
    expect(snap.seq).toBe(7);
    expect(snap.resumeToken).toBe('tok-7');
    expect(snap.payload).toBe(COUNTER_INITIAL); // unchanged
    stream.stop();
  });
});

describe('EnvelopeStream connection events', () => {
  it('flips isConnected on open and off on error', () => {
    const fake = new FakeEventSource('http://test/api/x/stream');
    const stream = makeStream(fake);
    stream.start();

    expect(stream.getSnapshot().isConnected).toBe(false);
    fake.emitOpen();
    expect(stream.getSnapshot().isConnected).toBe(true);
    fake.emitError();
    expect(stream.getSnapshot().isConnected).toBe(false);
    stream.stop();
  });

  it('recovers isConnected on the next frame after an error (EventSource auto-reconnect)', () => {
    // EventSource silently reconnects after a transient error; the first
    // frame to land post-reconnect must flip isConnected back to true even
    // if no explicit `open` event is observed first.
    const fake = new FakeEventSource('http://test/api/x/stream');
    const stream = makeStream(fake);
    stream.start();
    fake.emitOpen();
    fake.emitError();
    expect(stream.getSnapshot().isConnected).toBe(false);

    fake.emitFrame({
      streamId: 'x/v1',
      frameKind: 'UPDATE',
      seq: 9,
      ts: '...',
      payload: { kind: 'after-reconnect' },
      resumeToken: 'tok-9',
    });

    const snap = stream.getSnapshot();
    expect(snap.isConnected).toBe(true);
    expect(snap.seq).toBe(9);
    expect(snap.payload.lastKind).toBe('after-reconnect');
    stream.stop();
  });

  it('flips isConnected to false on stop()', () => {
    const fake = new FakeEventSource('http://test/api/x/stream');
    const stream = makeStream(fake);
    stream.start();
    fake.emitOpen();
    expect(stream.getSnapshot().isConnected).toBe(true);
    stream.stop();
    expect(stream.getSnapshot().isConnected).toBe(false);
  });
});

describe('EnvelopeStream subscribers', () => {
  it('notifies subscribers on every relevant change', () => {
    const fake = new FakeEventSource('http://test/api/x/stream');
    const stream = makeStream(fake);
    const calls: number[] = [];
    stream.subscribe((s) => calls.push(s.seq));
    stream.start();
    fake.emitOpen();
    fake.emitFrame({
      streamId: 'x/v1',
      frameKind: 'UPDATE',
      seq: 1,
      ts: '...',
      payload: { kind: 'a' },
      resumeToken: 't1',
    });
    fake.emitFrame({
      streamId: 'x/v1',
      frameKind: 'UPDATE',
      seq: 2,
      ts: '...',
      payload: { kind: 'b' },
      resumeToken: 't2',
    });
    fake.emitError();
    stream.stop();
    // open(seq=0) → frame(seq=1) → frame(seq=2) → error(seq=2, isConnected=false).
    // stop() doesn't notify a second time because isConnected is already false.
    expect(calls).toEqual([0, 1, 2, 2]);
  });

  it('unsubscribe removes the listener', () => {
    const fake = new FakeEventSource('http://test/api/x/stream');
    const stream = makeStream(fake);
    let called = 0;
    const off = stream.subscribe(() => {
      called++;
    });
    stream.start();
    fake.emitOpen();
    expect(called).toBeGreaterThan(0);
    const before = called;
    off();
    fake.emitFrame({
      streamId: 'x/v1',
      frameKind: 'UPDATE',
      seq: 1,
      ts: '...',
      payload: { kind: 'a' },
      resumeToken: 't1',
    });
    expect(called).toBe(before);
    stream.stop();
  });

  it('listener errors do not break other listeners', () => {
    const fake = new FakeEventSource('http://test/api/x/stream');
    const stream = makeStream(fake);
    const calls: number[] = [];
    stream.subscribe(() => {
      throw new Error('listener boom');
    });
    stream.subscribe((s) => calls.push(s.seq));
    stream.start();
    fake.emitFrame({
      streamId: 'x/v1',
      frameKind: 'UPDATE',
      seq: 5,
      ts: '...',
      payload: { kind: 'x' },
      resumeToken: 't',
    });
    expect(calls).toEqual([5]);
    stream.stop();
  });
});

describe('EnvelopeStream re-establishment (tempdoc 604)', () => {
  // The watchdog + reconnect backoff are pure `setTimeout` schedules (EnvelopeStream.ts armWatchdog /
  // scheduleReconnect). Driving them with a fake clock makes "did the timer fire?" a property of the
  // code under test rather than of wall-clock scheduling under parallel CI load — the real-timer form
  // asserted "a 40ms watchdog fired within 70ms of real time", which a starved event loop breaks.
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  /** A factory that hands out a fresh FakeEventSource per connect, recording each + the URL used. */
  function reconnectingStream(overrides?: { initialResumeToken?: string }) {
    const sources: FakeEventSource[] = [];
    const urls: string[] = [];
    const stream = new EnvelopeStream<CounterState>({
      url: 'http://test/api/x/stream',
      initialState: COUNTER_INITIAL,
      reducer: counterReducer,
      eventSourceFactory: (url) => {
        const fake = new FakeEventSource(url);
        sources.push(fake);
        urls.push(url);
        return fake as unknown as EventSource;
      },
      initialResumeToken: overrides?.initialResumeToken,
      // Tiny windows, advanced on the fake clock (no wall-clock dependence).
      watchdogStaleMs: 40,
      reconnectBaseMs: 5,
      reconnectCapMs: 20,
    });
    return { stream, sources, urls };
  }

  it('reconnects in place after a CLOSED error, replaying from the freshest resume token', async () => {
    const { stream, sources, urls } = reconnectingStream();
    stream.start();
    expect(sources).toHaveLength(1);
    // A frame advances the resume token; the reconnect must resume from THIS token, not the start one.
    sources[0]!.emitFrame({
      streamId: 'x/v1',
      frameKind: 'UPDATE',
      seq: 3,
      ts: '...',
      payload: { kind: 'a' },
      resumeToken: 'tok-3',
    });
    // The worker bounces: the SSE returns 503 → the browser FAILS the connection (CLOSED, no retry).
    sources[0]!.emitError(true);
    expect(stream.getSnapshot().isConnected).toBe(false);

    await vi.advanceTimersByTimeAsync(40);
    // The FE owned the recovery: a second EventSource was opened, carrying ?since=<freshest token>.
    // Exactly two: the backoff (5ms base + <=2.5ms jitter) fires inside the window, and the
    // reconnected source's own 40ms watchdog expires past it.
    expect(sources).toHaveLength(2);
    expect(urls[1]!).toBe('http://test/api/x/stream?since=tok-3');
    stream.stop();
  });

  it('does NOT reconnect on a CONNECTING error (the browser retries natively)', async () => {
    const { stream, sources } = reconnectingStream();
    stream.start();
    sources[0]!.emitOpen();
    sources[0]!.emitError(false); // transient drop — readyState CONNECTING
    // Wait under the 40ms watchdog window so this isolates the error path (a reconnect, if wrongly
    // scheduled, would fire within the 5–20ms backoff).
    await vi.advanceTimersByTimeAsync(25);
    expect(sources).toHaveLength(1); // no FE-owned reconnect
    stream.stop();
  });

  it('reconnects when the heartbeat-absence watchdog expires (silent wedge, no error fired)', async () => {
    const { stream, sources } = reconnectingStream();
    stream.start();
    sources[0]!.emitOpen();
    // No frames at all — the channel is silently dead. The watchdog (40ms) must force a reconnect.
    await vi.advanceTimersByTimeAsync(70);
    // Exactly two: watchdog at 40ms → backoff fires by ~47.5ms → the fresh source's own watchdog
    // would not expire until ~87.5ms, past the advanced window.
    expect(sources).toHaveLength(2);
    stream.stop();
  });

  it('a frame resets the watchdog, so a steadily-beating stream never reconnects', async () => {
    const { stream, sources } = reconnectingStream();
    stream.start();
    sources[0]!.emitOpen();
    // Beat every 25ms (< the 40ms window) five times — the watchdog must never trip.
    for (let i = 0; i < 5; i++) {
      await vi.advanceTimersByTimeAsync(25);
      sources[0]!.emitFrame({
        streamId: 'x/v1',
        frameKind: 'LIFECYCLE',
        seq: i + 1,
        ts: '...',
        payload: { kind: 'heartbeat' },
        resumeToken: `tok-${i + 1}`,
      });
    }
    expect(sources).toHaveLength(1);
    stream.stop();
  });

  it('stop() cancels a pending reconnect (a stopped stream cannot resurrect)', async () => {
    const { stream, sources } = reconnectingStream();
    stream.start();
    sources[0]!.emitError(true); // schedule a reconnect…
    stream.stop(); // …then stop before it fires.
    await vi.advanceTimersByTimeAsync(40);
    expect(sources).toHaveLength(1);
  });
});

describe('EnvelopeStream feeds origin contact (tempdoc 649)', () => {
  beforeEach(() => __resetOriginContactForTest());

  it('a received frame (incl. a heartbeat) records positive origin contact', () => {
    const fake = new FakeEventSource('http://test/api/x/stream');
    const stream = makeStream(fake);
    stream.start();
    expect(getLastOriginContactMs()).toBeNull(); // no frame yet

    fake.emitFrame({
      streamId: 'x/v1',
      frameKind: 'LIFECYCLE',
      seq: 1,
      ts: '2026-05-05T00:00:00Z',
      payload: { kind: 'heartbeat' },
      resumeToken: 'tok-1',
    });

    // The heartbeat is proof of life — contact is now stamped.
    expect(getLastOriginContactMs()).not.toBeNull();
    stream.stop();
  });

  it('a clean open records positive origin contact', () => {
    const fake = new FakeEventSource('http://test/api/x/stream');
    const stream = makeStream(fake);
    stream.start();
    fake.emitOpen();
    expect(getLastOriginContactMs()).not.toBeNull();
    stream.stop();
  });

  it('frames do not perturb the strict subscriber-notify order (no extra notify)', () => {
    const fake = new FakeEventSource('http://test/api/x/stream');
    const stream = makeStream(fake);
    const seen: number[] = [];
    stream.subscribe((snap) => seen.push(snap.payload.count));
    stream.start();
    fake.emitFrame({
      streamId: 'x/v1', frameKind: 'UPDATE', seq: 1, ts: '2026-05-05T00:00:00Z',
      payload: { kind: 'item-added' }, resumeToken: 't1',
    });
    fake.emitFrame({
      streamId: 'x/v1', frameKind: 'UPDATE', seq: 2, ts: '2026-05-05T00:00:01Z',
      payload: { kind: 'item-added' }, resumeToken: 't2',
    });
    // One notify per frame — the contact bump adds no listener fan-out.
    expect(seen).toEqual([1, 2]);
    stream.stop();
  });
});
