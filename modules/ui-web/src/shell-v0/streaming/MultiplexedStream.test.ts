// @vitest-environment happy-dom

/**
 * Tests for MultiplexedStream — tempdoc 662's cross-channel SSE fan-out: demux by streamId,
 * ref-counted subscribe, the per-channel resume bundle, and shared connection-state fan-out.
 */

import { describe, expect, it, beforeEach, vi } from 'vitest';
import { MultiplexedStream } from './MultiplexedStream.js';
import type { SseEnvelope } from './envelope-types.js';
import {
  getLastOriginContactMs,
  __resetOriginContactForTest,
} from '../state/originContact.js';
import {
  getCurrentOpenChannelCount,
  getPeakOpenChannelCount,
  __resetLiveChannelBudgetForTest,
} from '../state/liveChannelBudget.js';

class FakeEventSource extends EventTarget {
  url: string;
  closed = false;
  readyState = 0;
  constructor(url: string) {
    super();
    this.url = url;
  }
  emitFrame(envelope: SseEnvelope): void {
    this.dispatchEvent(new MessageEvent('frame', { data: JSON.stringify(envelope) }));
  }
  emitOpen(): void {
    this.readyState = 1;
    this.dispatchEvent(new Event('open'));
  }
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
  lastPayloadKind: string | null;
}
const COUNTER_INITIAL: CounterState = { count: 0, lastPayloadKind: null };
const counterReducer = (state: CounterState, envelope: SseEnvelope): CounterState => {
  const payload = envelope.payload as { kind: string } | null;
  if (envelope.frameKind === 'LIFECYCLE') {
    return { ...state, lastPayloadKind: payload?.kind ?? null };
  }
  return { count: state.count + 1, lastPayloadKind: payload?.kind ?? null };
};

function updateFrame(streamId: string, seq: number, resumeToken: string): SseEnvelope {
  return {
    streamId,
    frameKind: 'UPDATE',
    seq,
    ts: '2026-07-01T00:00:00Z',
    payload: { kind: 'delta' },
    resumeToken,
  };
}

function lifecycleFrame(streamId: string, kind: string, seq: number, resumeToken: string): SseEnvelope {
  return {
    streamId,
    frameKind: 'LIFECYCLE',
    seq,
    ts: '2026-07-01T00:00:00Z',
    payload: { kind },
    resumeToken,
  };
}

describe('MultiplexedStream', () => {
  it('reducer failure clears only its stream checkpoint before reconnecting the shared source', async () => {
    vi.useFakeTimers();
    const sources: FakeEventSource[] = [];
    const mux = new MultiplexedStream({
      url: 'http://test/api/shell-events/stream',
      reconnectBaseMs: 10, reconnectCapMs: 10, watchdogStaleMs: 0,
      eventSourceFactory: (url) => {
        const source = new FakeEventSource(url);
        sources.push(source);
        return source as unknown as EventSource;
      },
    });
    mux.subscribe('system:test-a', () => ({
      initialState: 0,
      reducer: (state: number, envelope: SseEnvelope) => {
        if (envelope.seq === 2) throw new Error('cannot apply A');
        return envelope.frameKind === 'LIFECYCLE' ? 7 : state + 1;
      },
    }), () => {});
    mux.subscribe('system:test-b', () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }), () => {});
    try {
      mux.start();
      sources[0]!.emitFrame(updateFrame('system:test-a', 1, 'tok-a-1'));
      sources[0]!.emitFrame(updateFrame('system:test-b', 1, 'tok-b-1'));
      sources[0]!.emitFrame(updateFrame('system:test-a', 2, 'tok-a-2'));
      expect(sources[0]!.closed).toBe(true);
      expect(mux.getSnapshot<number>('system:test-a')).toMatchObject({
        payload: 1, seq: 2, resumeToken: null, isConnected: false,
      });
      expect(mux.getSnapshot<CounterState>('system:test-b')).toMatchObject({
        resumeToken: 'tok-b-1', isConnected: false,
      });
      sources[0]!.emitFrame(updateFrame('system:test-b', 2, 'ignored-late-token'));
      expect(mux.getSnapshot<CounterState>('system:test-b')!.resumeToken).toBe('tok-b-1');
      await vi.advanceTimersByTimeAsync(10);
      expect(sources).toHaveLength(2);
      expect(sources[1]!.url).toBe('http://test/api/shell-events/stream?since=tok-b-1');
      sources[1]!.emitFrame(lifecycleFrame('system:test-a', 'snapshot', 10, 'tok-a-10'));
      sources[1]!.emitFrame(updateFrame('system:test-b', 2, 'tok-b-2'));
      expect(mux.getSnapshot<number>('system:test-a')).toMatchObject({ payload: 7, resumeToken: 'tok-a-10' });
      expect(mux.getSnapshot<CounterState>('system:test-b')!.payload.count).toBe(2);
    } finally {
      mux.stop();
      vi.useRealTimers();
    }
  });

  beforeEach(() => {
    __resetOriginContactForTest();
    __resetLiveChannelBudgetForTest();
  });

  it('tempdoc 662 §D2 — opening the ONE multiplexed connection bumps the runtime peak signal exactly once, well under the budget', () => {
    // The whole point of 662: the boot baseline is ONE physical multiplexed socket replacing
    // 5 always-on EventSources — assert that baseline against the declared budget
    // (governance/live-channels.v1.json budget.maxAlwaysOnPhysical = 3) so a regression that
    // silently reopens per-stream sockets is caught by a live measurement, not just the
    // static register (Reach §"declare claimants statically; measure the peak at runtime").
    const BUDGET_MAX_ALWAYS_ON_PHYSICAL = 3;
    const mux = new MultiplexedStream({
      url: 'http://test/api/shell-events/stream',
      eventSourceFactory: (url) => new FakeEventSource(url) as unknown as EventSource,
    });
    mux.start();
    expect(getCurrentOpenChannelCount()).toBe(1);
    expect(getPeakOpenChannelCount()).toBe(1);
    expect(getPeakOpenChannelCount()).toBeLessThanOrEqual(BUDGET_MAX_ALWAYS_ON_PHYSICAL);
    mux.stop();
    expect(getCurrentOpenChannelCount()).toBe(0);
    expect(getPeakOpenChannelCount()).toBe(1); // peak survives the close
  });

  it('demuxes frames to the matching streamId subscriber only', () => {
    let source: FakeEventSource | undefined;
    const mux = new MultiplexedStream({
      url: 'http://test/api/shell-events/stream',
      eventSourceFactory: (url) => {
        source = new FakeEventSource(url);
        return source as unknown as EventSource;
      },
    });
    mux.start();
    source!.emitOpen(); // realistic EventSource ordering: 'open' always precedes any 'frame'

    const aSnapshots: CounterState[] = [];
    const bSnapshots: CounterState[] = [];
    mux.subscribe('system:test-a', () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }), (s) =>
      aSnapshots.push(s.payload),
    );
    mux.subscribe('system:test-b', () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }), (s) =>
      bSnapshots.push(s.payload),
    );

    source!.emitFrame(updateFrame('system:test-a', 1, 'tok-a-1'));
    source!.emitFrame(updateFrame('system:test-b', 1, 'tok-b-1'));
    source!.emitFrame(updateFrame('system:test-a', 2, 'tok-a-2'));

    expect(aSnapshots).toHaveLength(2);
    expect(aSnapshots[1]!.count).toBe(2);
    expect(bSnapshots).toHaveLength(1);
    expect(bSnapshots[0]!.count).toBe(1);
  });

  it('silently drops a frame for an unregistered streamId (e.g. the heartbeat pseudo-channel)', () => {
    let source: FakeEventSource | undefined;
    const mux = new MultiplexedStream({
      url: 'http://test/api/shell-events/stream',
      eventSourceFactory: (url) => {
        source = new FakeEventSource(url);
        return source as unknown as EventSource;
      },
    });
    mux.start();
    source!.emitOpen(); // realistic EventSource ordering: 'open' always precedes any 'frame'
    const seen: CounterState[] = [];
    mux.subscribe('system:test-a', () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }), (s) =>
      seen.push(s.payload),
    );

    expect(() =>
      source!.emitFrame(lifecycleFrame('system:shell-events-heartbeat', 'heartbeat', 1, 'tok-hb-1')),
    ).not.toThrow();
    expect(seen).toHaveLength(0);
  });

  it('ref-counts subscriptions: last unsubscribe removes the entry, a fresh subscribe re-seeds initialState', () => {
    let source: FakeEventSource | undefined;
    const mux = new MultiplexedStream({
      url: 'http://test/api/shell-events/stream',
      eventSourceFactory: (url) => {
        source = new FakeEventSource(url);
        return source as unknown as EventSource;
      },
    });
    mux.start();
    source!.emitOpen(); // realistic EventSource ordering: 'open' always precedes any 'frame'

    const seen1: CounterState[] = [];
    const unsub1 = mux.subscribe(
      'system:test-a',
      () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }),
      (s) => seen1.push(s.payload),
    );
    const seen2: CounterState[] = [];
    const unsub2 = mux.subscribe(
      'system:test-a',
      () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }),
      (s) => seen2.push(s.payload),
    );

    source!.emitFrame(updateFrame('system:test-a', 1, 'tok-a-1'));
    expect(seen1).toHaveLength(1);
    expect(seen2).toHaveLength(1);

    unsub1();
    // Still one ref left — the entry survives, the second subscriber keeps receiving frames.
    source!.emitFrame(updateFrame('system:test-a', 2, 'tok-a-2'));
    expect(seen1).toHaveLength(1); // unsubscribed — no further updates
    expect(seen2).toHaveLength(2);

    unsub2();
    expect(mux.getSnapshot('system:test-a')).toBeNull();

    // Fresh subscribe after full unsubscribe re-seeds from createConfig (count resets to 0 baseline).
    const seen3: CounterState[] = [];
    mux.subscribe('system:test-a', () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }), (s) =>
      seen3.push(s.payload),
    );
    source!.emitFrame(updateFrame('system:test-a', 3, 'tok-a-3'));
    expect(seen3[0]!.count).toBe(1); // fresh entry, not 3 — confirms re-seed, not stale accumulation
  });

  it('getSnapshot returns null for an unregistered streamId and the current state for a registered one', () => {
    let source: FakeEventSource | undefined;
    const mux = new MultiplexedStream({
      url: 'http://test/api/shell-events/stream',
      eventSourceFactory: (url) => {
        source = new FakeEventSource(url);
        return source as unknown as EventSource;
      },
    });
    mux.start();
    expect(mux.getSnapshot('system:never-registered')).toBeNull();

    mux.subscribe('system:test-a', () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }), () => {});
    source!.emitFrame(updateFrame('system:test-a', 1, 'tok-a-1'));
    const snap = mux.getSnapshot<CounterState>('system:test-a');
    expect(snap).not.toBeNull();
    expect(snap!.payload.count).toBe(1);
    expect(snap!.resumeToken).toBe('tok-a-1');
  });

  it('builds the ?since= bundle as a comma-joined set of every registered stream\'s last token, reconnecting with it', async () => {
    const sources: FakeEventSource[] = [];
    const urls: string[] = [];
    const mux = new MultiplexedStream({
      url: 'http://test/api/shell-events/stream',
      eventSourceFactory: (url) => {
        const fake = new FakeEventSource(url);
        sources.push(fake);
        urls.push(url);
        return fake as unknown as EventSource;
      },
      watchdogStaleMs: 40,
      reconnectBaseMs: 5,
      reconnectCapMs: 20,
    });
    mux.subscribe('system:test-a', () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }), () => {});
    mux.subscribe('system:test-b', () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }), () => {});
    mux.start();

    expect(urls[0]).toBe('http://test/api/shell-events/stream'); // no tokens yet — no ?since=

    sources[0]!.emitFrame(updateFrame('system:test-a', 1, 'tok-a-1'));
    sources[0]!.emitFrame(updateFrame('system:test-b', 1, 'tok-b-1'));
    sources[0]!.emitError(true); // CLOSED — forces FE-owned reconnect

    await new Promise((r) => setTimeout(r, 60));

    expect(urls.length).toBeGreaterThanOrEqual(2);
    const reconnectUrl = urls[1]!;
    // Order is registration order (Map iteration) — assert both tokens present, comma-joined.
    expect(reconnectUrl).toBe(
      'http://test/api/shell-events/stream?since=' + encodeURIComponent('tok-a-1,tok-b-1'),
    );
    mux.stop();
  });

  it('omits ?since= entirely when no registered stream has received a frame yet', () => {
    let capturedUrl = '';
    const mux = new MultiplexedStream({
      url: 'http://test/api/shell-events/stream',
      eventSourceFactory: (url) => {
        capturedUrl = url;
        return new FakeEventSource(url) as unknown as EventSource;
      },
    });
    mux.subscribe('system:test-a', () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }), () => {});
    mux.start();
    expect(capturedUrl).toBe('http://test/api/shell-events/stream');
  });

  it('propagates the shared physical connection state to every registered stream without double-firing on a normal frame', () => {
    let source: FakeEventSource | undefined;
    const mux = new MultiplexedStream({
      url: 'http://test/api/shell-events/stream',
      eventSourceFactory: (url) => {
        source = new FakeEventSource(url);
        return source as unknown as EventSource;
      },
    });
    mux.start();
    const aCalls: boolean[] = [];
    const bCalls: boolean[] = [];
    mux.subscribe('system:test-a', () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }), (s) =>
      aCalls.push(s.isConnected),
    );
    mux.subscribe('system:test-b', () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }), (s) =>
      bCalls.push(s.isConnected),
    );

    // First frame transitions the shared connection false -> true; both streams observe it,
    // but stream B (which received no frame) must ALSO see the flip via the connection broadcast.
    source!.emitFrame(updateFrame('system:test-a', 1, 'tok-a-1'));
    expect(aCalls.some((c) => c === true)).toBe(true);
    expect(bCalls.some((c) => c === true)).toBe(true);

    const aCallsBeforeSecondFrame = aCalls.length;
    const bCallsBeforeSecondFrame = bCalls.length;
    // A second frame on an ALREADY-connected channel must not re-trigger a connection broadcast
    // for the uninvolved stream B (only A's own listener fires).
    source!.emitFrame(updateFrame('system:test-a', 2, 'tok-a-2'));
    expect(aCalls.length).toBe(aCallsBeforeSecondFrame + 1);
    expect(bCalls.length).toBe(bCallsBeforeSecondFrame); // unchanged — no spurious broadcast

    // An error (CLOSED) flips isConnected false for the shared connection — both streams see it.
    source!.emitError(true);
    expect(aCalls[aCalls.length - 1]).toBe(false);
    expect(bCalls[bCalls.length - 1]).toBe(false);
  });

  it('a received frame bumps the shared originContact stamp (649 reachability authority unaffected by demux)', () => {
    let source: FakeEventSource | undefined;
    const mux = new MultiplexedStream({
      url: 'http://test/api/shell-events/stream',
      eventSourceFactory: (url) => {
        source = new FakeEventSource(url);
        return source as unknown as EventSource;
      },
    });
    mux.start();
    mux.subscribe('system:test-a', () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }), () => {});
    expect(getLastOriginContactMs()).toBeNull();
    source!.emitFrame(updateFrame('system:test-a', 1, 'tok-a-1'));
    expect(getLastOriginContactMs()).not.toBeNull();
  });
});

describe('MultiplexedStream — late-subscribe reconnect (tempdoc 662 post-implementation fix)', () => {
  beforeEach(() => {
    __resetOriginContactForTest();
  });

  function multiplexWithRecording(reconnectDebounceMs = 10) {
    const sources: FakeEventSource[] = [];
    const urls: string[] = [];
    const mux = new MultiplexedStream({
      url: 'http://test/api/shell-events/stream',
      eventSourceFactory: (url) => {
        const fake = new FakeEventSource(url);
        sources.push(fake);
        urls.push(url);
        return fake as unknown as EventSource;
      },
      reconnectDebounceMs,
    });
    return { mux, sources, urls };
  }

  it('subscribe BEFORE the connection has ever opened needs no reconnect — receives the normal first burst', () => {
    const { mux, sources } = multiplexWithRecording();
    const seen: CounterState[] = [];
    // Subscribe before start() — lastConnected is still false, the common/safe path.
    mux.subscribe('system:test-a', () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }), (s) =>
      seen.push(s.payload),
    );
    mux.start();
    sources[0]!.emitOpen();
    sources[0]!.emitFrame(updateFrame('system:test-a', 1, 'tok-a-1'));

    expect(sources).toHaveLength(1); // no reconnect triggered
    // A listener registered before the connection opens ALSO receives the pre-existing
    // connection-state broadcast on open (unrelated to this fix — see broadcastConnectionChange);
    // the load-bearing assertion here is that the real frame's data arrives correctly LAST.
    expect(seen[seen.length - 1]!.count).toBe(1);
  });

  it('subscribe AFTER the connection has already opened triggers exactly one debounced reconnect, and the late entry receives a fresh snapshot', async () => {
    const { mux, sources } = multiplexWithRecording(10);
    mux.start();
    sources[0]!.emitOpen(); // lastConnected flips true — the connection has "already connected"

    const seen: CounterState[] = [];
    // Late subscribe: registered AFTER the connection is already open.
    mux.subscribe('system:test-late', () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }), (s) =>
      seen.push(s.payload),
    );

    expect(sources).toHaveLength(1); // reconnect is debounced, not immediate
    await new Promise((r) => setTimeout(r, 30));
    expect(sources).toHaveLength(2); // exactly one reconnect fired

    // The new connection's fresh burst now includes the late streamId (no resumeToken existed
    // for it, so the server-side contract — mirrored here — is: snapshot as a normal UPDATE).
    sources[1]!.emitOpen();
    sources[1]!.emitFrame(updateFrame('system:test-late', 1, 'tok-late-1'));
    // The reconnect cycle itself (stop -> false, start+open -> true) also notifies the
    // already-registered late listener via the pre-existing connection-broadcast mechanism
    // (unrelated to this fix); the load-bearing assertion is that the real frame's data
    // arrives correctly LAST — i.e. the late entry genuinely received its snapshot.
    expect(seen[seen.length - 1]!.count).toBe(1);
  });

  it('several late subscribes within the debounce window coalesce into exactly ONE reconnect', async () => {
    const { mux, sources } = multiplexWithRecording(10);
    mux.start();
    sources[0]!.emitOpen();

    // Simulates AdvisoryStore.reconcileFromCatalog()'s synchronous loop registering several
    // channels back-to-back after the connection is already open.
    mux.subscribe('system:test-late-a', () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }), () => {});
    mux.subscribe('system:test-late-b', () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }), () => {});
    mux.subscribe('system:test-late-c', () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }), () => {});

    await new Promise((r) => setTimeout(r, 30));
    expect(sources).toHaveLength(2); // ONE reconnect, not three
  });

  it('a late subscriber that unsubscribes before the debounce window elapses does NOT trigger a stray reconnect', async () => {
    const { mux, sources } = multiplexWithRecording(10);
    mux.start();
    sources[0]!.emitOpen();

    // Registers late (schedules a debounced reconnect), then unsubscribes before the debounce
    // fires — e.g. a fast component mount/unmount, or a catalog entry filtered back out. The
    // reconnect that was scheduled for this streamId is no longer needed by anything.
    const unsub = mux.subscribe(
      'system:test-late-transient',
      () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }),
      () => {},
    );
    unsub();

    await new Promise((r) => setTimeout(r, 30));
    expect(sources).toHaveLength(1); // no reconnect — nothing left needs one
  });

  it('a late subscriber that unsubscribes still lets a DIFFERENT concurrently-late subscriber trigger the reconnect', async () => {
    const { mux, sources } = multiplexWithRecording(10);
    mux.start();
    sources[0]!.emitOpen();

    const unsub = mux.subscribe(
      'system:test-late-transient',
      () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }),
      () => {},
    );
    mux.subscribe(
      'system:test-late-persistent',
      () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }),
      () => {},
    );
    unsub(); // only the transient one unsubscribes; the persistent one still needs the burst

    await new Promise((r) => setTimeout(r, 30));
    expect(sources).toHaveLength(2); // the reconnect still fires for the surviving subscriber
  });

  it('an already-flowing entry survives the late-subscribe reconnect via its resume token (no data loss/duplication)', async () => {
    const { mux, sources, urls } = multiplexWithRecording(10);
    const seenA: CounterState[] = [];
    mux.subscribe('system:test-a', () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }), (s) =>
      seenA.push(s.payload),
    );
    mux.start();
    sources[0]!.emitOpen();
    sources[0]!.emitFrame(updateFrame('system:test-a', 1, 'tok-a-1'));
    // A listener registered before the connection opens also receives the pre-existing
    // connection-broadcast on open (unrelated to this fix); the real frame's data is last.
    expect(seenA[seenA.length - 1]!.count).toBe(1);
    const seenABeforeLateSubscribe = seenA.length;

    // A late subscriber joins after A is already flowing.
    mux.subscribe('system:test-late', () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }), () => {});
    await new Promise((r) => setTimeout(r, 30));
    expect(sources).toHaveLength(2);

    // The reconnect's ?since= bundle must carry A's token (resuming it), not drop it.
    expect(urls[1]).toContain(encodeURIComponent('tok-a-1'));

    // A's listener is notified of the reconnect's transient connection-state flip (the
    // pre-existing connection-broadcast mechanism, unrelated to this fix) but its PAYLOAD must
    // not be lost, reset, or duplicated by the reconnect itself — count stays 1, not 0 or 2.
    expect(seenA.length).toBe(seenABeforeLateSubscribe + 1);
    expect(seenA[seenA.length - 1]!.count).toBe(1);
  });

  it('stop() cancels a pending debounced reconnect (no stray reconnect after an intentional stop)', async () => {
    const { mux, sources } = multiplexWithRecording(10);
    mux.start();
    sources[0]!.emitOpen();
    mux.subscribe('system:test-late', () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }), () => {});

    mux.stop(); // stop before the debounce window elapses
    await new Promise((r) => setTimeout(r, 30));
    expect(sources).toHaveLength(1); // no reconnect fired post-stop
  });
});

describe('MultiplexedStream — nudgeReconnect (tempdoc 798 B4 per-channel stall actuator)', () => {
  beforeEach(() => {
    __resetOriginContactForTest();
    __resetLiveChannelBudgetForTest();
  });

  function multiplexWithRecording(watchdogStaleMs?: number) {
    const sources: FakeEventSource[] = [];
    const urls: string[] = [];
    const mux = new MultiplexedStream({
      url: 'http://test/api/shell-events/stream',
      eventSourceFactory: (url) => {
        const fake = new FakeEventSource(url);
        sources.push(fake);
        urls.push(url);
        return fake as unknown as EventSource;
      },
      ...(watchdogStaleMs === undefined ? {} : { watchdogStaleMs }),
    });
    return { mux, sources, urls };
  }

  it('THE FIELD CONDITION — one channel wedged while another keeps flowing: the physical watchdog never fires, so ONLY the nudge reconnects', async () => {
    // The exact shape of the defect: `surface:indexing-jobs` goes silent while advisory/ledger
    // frames keep arriving on the SAME physical connection, re-arming EnvelopeStream's
    // heartbeat-absence watchdog. The transport is provably alive; only one demuxed channel is
    // dead. Nothing at the transport layer can observe that — which is why the stall detector
    // needs its own actuator.
    const { mux, sources } = multiplexWithRecording(40);
    mux.subscribe('system:test-wedged', () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }), () => {});
    mux.subscribe('system:test-flowing', () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }), () => {});
    mux.start();
    sources[0]!.emitOpen();

    // ~90ms of traffic on the OTHER channel only, at well under the 40ms watchdog window.
    for (let i = 1; i <= 6; i += 1) {
      await new Promise((r) => setTimeout(r, 15));
      sources[sources.length - 1]!.emitFrame(updateFrame('system:test-flowing', i, `tok-flow-${i}`));
    }
    // 90ms > 2x the watchdog window, yet no reconnect: the wedged channel is invisible to it.
    expect(sources).toHaveLength(1);

    // The consumer-owned actuator is the only thing that can re-establish the transport here.
    expect(mux.nudgeReconnect()).toBe(true);
    expect(sources).toHaveLength(2);
    mux.stop();
  });

  it('the nudge rebuilds the ?since= bundle so every already-flowing channel resumes from its own token', () => {
    const { mux, sources, urls } = multiplexWithRecording();
    const seenA: CounterState[] = [];
    mux.subscribe('system:test-a', () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }), (s) =>
      seenA.push(s.payload),
    );
    mux.subscribe('system:test-b', () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }), () => {});
    mux.start();
    sources[0]!.emitOpen();
    sources[0]!.emitFrame(updateFrame('system:test-a', 1, 'tok-a-1'));
    sources[0]!.emitFrame(updateFrame('system:test-b', 1, 'tok-b-1'));
    const seenABeforeNudge = seenA.length;

    mux.nudgeReconnect();

    expect(sources).toHaveLength(2);
    expect(urls[1]).toBe(
      'http://test/api/shell-events/stream?since=' + encodeURIComponent('tok-a-1,tok-b-1'),
    );
    // The demux entries are owned by MultiplexedStream, not the transport — the reconnect must
    // not reset, lose, or duplicate an already-accumulated payload.
    expect(mux.getSnapshot<CounterState>('system:test-a')!.payload.count).toBe(1);
    expect(mux.getSnapshot<CounterState>('system:test-a')!.resumeToken).toBe('tok-a-1');
    // Only the transient connection-state flip reaches A's listener; no payload change.
    expect(seenA[seenA.length - 1]!.count).toBe(1);
    expect(seenA.length).toBeGreaterThanOrEqual(seenABeforeNudge);
    mux.stop();
  });

  it('the nudge is a no-op before start() and after stop() — it never resurrects a closed connection', () => {
    const { mux, sources } = multiplexWithRecording();
    mux.subscribe('system:test-a', () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }), () => {});

    expect(mux.nudgeReconnect()).toBe(false); // never started
    expect(sources).toHaveLength(0);

    mux.start();
    sources[0]!.emitOpen();
    mux.stop();
    expect(mux.nudgeReconnect()).toBe(false); // intentionally stopped
    expect(sources).toHaveLength(1);
  });
});
