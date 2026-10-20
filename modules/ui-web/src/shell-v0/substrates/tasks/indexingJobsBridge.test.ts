// @vitest-environment happy-dom

/**
 * §32 #1 — indexing-jobs → Task bridge tests. Covers the (stateless) projection
 * lifecycle directly — including the reset-cycle (1A) and remount (1B)
 * regressions — and the full SSE-frame → reducer → projection chain via a fake
 * EventSource.
 */

import { beforeEach, describe, it, expect, vi } from 'vitest';
import {
  projectJobsToTasks,
  startIndexingJobsBridge,
  __setResolveHashForTest,
  __resetResolvedLabelsForTest,
} from './indexingJobsBridge.js';
import {
  listTasks,
  getTask,
  startTask,
  __resetTasksForTest,
} from './index.js';
import {
  subscribePooled,
  __resetEnvelopeStreamPoolForTest,
  __poolSizeForTest,
} from '../../streaming/EnvelopeStreamPool.js';
import { MultiplexedStream } from '../../streaming/MultiplexedStream.js';

type Row = { pathHash: string; state: string; collection: string; lastUpdatedMs?: number };
// lastUpdatedMs defaults FRESH (Date.now()), so a PROCESSING job is `running`
// unless a test explicitly supplies a stale timestamp (575 §14 liveness).
const rows = (...rs: Row[]) =>
  new Map(rs.map((r) => [r.pathHash, { lastUpdatedMs: Date.now(), ...r }]));

beforeEach(() => {
  __resetTasksForTest();
  __resetEnvelopeStreamPoolForTest();
  // 602 R5 — restore the default hash resolver + clear the resolved-label cache
  // so label-resolution state never leaks across tests.
  __setResolveHashForTest(null);
  __resetResolvedLabelsForTest();
});

describe('§32 #1 — projectJobsToTasks (stateless reconcile)', () => {
  // Tempdoc 550 Thesis II: each job renders its TRUE state. PENDING is `queued`
  // (waiting, NOT running) — the F-1 cure. `running` is asserted only for a job
  // actually in PROCESSING (an authoritative running-signal), never for mere
  // membership in the live set.
  it('a PENDING job → queued Task (NOT running); read-only (not cancellable)', () => {
    projectJobsToTasks(rows({ pathHash: 'h1', state: 'PENDING', collection: 'docs' }));
    const t = getTask('idxjob:h1');
    expect(t?.status).toBe('queued');
    expect(t?.cancellable).toBe(false); // cancel stays in the Resource view
    expect(t?.label).toContain('docs');
  });

  it('a PROCESSING job with a FRESH heartbeat → running (575 §14 live owner)', () => {
    projectJobsToTasks(rows({ pathHash: 'h1p', state: 'PROCESSING', collection: 'docs' }));
    expect(getTask('idxjob:h1p')?.status).toBe('running');
  });

  // Tempdoc 575 §14: the badge derives from a LIVE owner (heartbeat freshness),
  // not mere PROCESSING membership. A job whose owner stopped beating (a wedged
  // loop) is stale → demoted to `queued`, not asserted as a phantom RUNNING pill.
  it('a PROCESSING job with a STALE heartbeat → queued, NOT running (575 §14)', () => {
    projectJobsToTasks(
      rows({
        pathHash: 'hstale',
        state: 'PROCESSING',
        collection: 'docs',
        lastUpdatedMs: Date.now() - 300_000, // far past the 90s freshness window
      }),
    );
    expect(getTask('idxjob:hstale')?.status).toBe('queued');
  });

  it('a DONE job → vanishes from the live rail (success is history, not work)', () => {
    projectJobsToTasks(rows({ pathHash: 'h1d', state: 'DONE', collection: 'docs' }));
    expect(getTask('idxjob:h1d')).toBeUndefined();
  });

  it('a FAILED job → failed Task', () => {
    projectJobsToTasks(rows({ pathHash: 'h2', state: 'FAILED', collection: 'docs' }));
    expect(getTask('idxjob:h2')?.status).toBe('failed');
  });

  it('a RETRY_EXHAUSTED job → failed Task (not the unknown-state fallback), labelled as giving up', () => {
    // Tempdoc 885 item 21b. Without an explicit case this state falls to `default`, which returns
    // 'queued' AND warns — so an exhausted job would render as still-waiting work forever.
    projectJobsToTasks(rows({ pathHash: 'h2x', state: 'RETRY_EXHAUSTED', collection: 'docs' }));
    expect(getTask('idxjob:h2x')?.status).toBe('failed');
    // The status alone says "failed"; the label is what distinguishes "this file is unreadable"
    // from "we stopped trying". Same words the Activity ledger uses for the same state, so the
    // tray and the timeline do not describe one event two ways.
    expect(getTask('idxjob:h2x')?.label).toContain('Index gave up');
    expect(getTask('idxjob:h2x')?.label).not.toContain('Indexing ·');
  });

  it('a plain FAILED job keeps the "Indexing ·" label — the verb only changes for the exhausted case', () => {
    projectJobsToTasks(rows({ pathHash: 'h2y', state: 'FAILED', collection: 'docs' }));
    expect(getTask('idxjob:h2y')?.label).toContain('Indexing ·');
    expect(getTask('idxjob:h2y')?.label).not.toContain('gave up');
  });

  it('a job that leaves the live set → REMOVED (not marked succeeded)', () => {
    projectJobsToTasks(rows({ pathHash: 'h3', state: 'PENDING', collection: 'docs' }));
    expect(getTask('idxjob:h3')?.status).toBe('queued');
    projectJobsToTasks(new Map()); // h3 gone
    expect(getTask('idxjob:h3')).toBeUndefined(); // vanishes, not 'succeeded'
  });

  it('a present FAILED job stays a single failed task across frames; departs → removed', () => {
    projectJobsToTasks(rows({ pathHash: 'h4', state: 'FAILED', collection: 'docs' }));
    projectJobsToTasks(rows({ pathHash: 'h4', state: 'FAILED', collection: 'docs' }));
    expect(listTasks().filter((t) => t.id === 'idxjob:h4')).toHaveLength(1);
    expect(getTask('idxjob:h4')?.status).toBe('failed');
    projectJobsToTasks(new Map()); // cleared
    expect(getTask('idxjob:h4')).toBeUndefined();
  });

  it('a running job that transitions to FAILED → failed (no duplicate task)', () => {
    projectJobsToTasks(rows({ pathHash: 'h5', state: 'PENDING', collection: 'docs' }));
    projectJobsToTasks(rows({ pathHash: 'h5', state: 'FAILED', collection: 'docs' }));
    expect(listTasks().filter((t) => t.id === 'idxjob:h5')).toHaveLength(1);
    expect(getTask('idxjob:h5')?.status).toBe('failed');
  });

  // 1A regression: a reset (empty items) must NOT strand a still-running job as
  // a terminal status. With removeTask-on-departure, the job vanishes and the
  // next snapshot cleanly re-adds it as running.
  it('1A: reset (empty) then re-appear → queued again, NOT stuck terminal', () => {
    projectJobsToTasks(rows({ pathHash: 'h6', state: 'PENDING', collection: 'docs' }));
    projectJobsToTasks(new Map()); // stream reset → items empty
    expect(getTask('idxjob:h6')).toBeUndefined();
    projectJobsToTasks(rows({ pathHash: 'h6', state: 'PENDING', collection: 'docs' }));
    expect(getTask('idxjob:h6')?.status).toBe('queued'); // not stuck succeeded
  });

  // 1B regression: the projection is stateless — it reconciles against whatever
  // job-tasks already exist in the substrate (e.g. left by a prior bridge
  // instance before a remount), with no reliance on per-instance closure state.
  it('1B: reconciles against pre-existing substrate job-tasks (remount-safe)', () => {
    // Simulate a prior bridge instance having started a job-task.
    startTask({ id: 'idxjob:h7', label: 'Indexing · docs (h7)' });
    expect(getTask('idxjob:h7')?.status).toBe('running');
    // A fresh projection (new "instance", no shared closure) where h7 is gone
    // must still remove it — proving truth is derived from the substrate.
    projectJobsToTasks(new Map());
    expect(getTask('idxjob:h7')).toBeUndefined();
  });

  // F1.2: a same-pathHash retry that flips FAILED → non-FAILED in place (no
  // intervening departure) must revive the task to running, not stay failed.
  it('an in-place FAILED → PENDING flip revives the task to queued', () => {
    projectJobsToTasks(rows({ pathHash: 'h9', state: 'FAILED', collection: 'docs' }));
    expect(getTask('idxjob:h9')?.status).toBe('failed');
    projectJobsToTasks(rows({ pathHash: 'h9', state: 'PENDING', collection: 'docs' }));
    expect(getTask('idxjob:h9')?.status).toBe('queued');
  });

  it('does not touch non-job tasks (idxjob: prefix scoping)', () => {
    startTask({ id: 'task-agent-1', label: 'Agent operation: core.search' });
    projectJobsToTasks(rows({ pathHash: 'h8', state: 'PENDING', collection: 'docs' }));
    projectJobsToTasks(new Map()); // removes h8 only
    expect(getTask('task-agent-1')?.status).toBe('running'); // untouched
    expect(getTask('idxjob:h8')).toBeUndefined();
  });
});

describe('602 R5 — resolved file/folder labels (Tasks tray)', () => {
  const flush = () => new Promise((r) => setTimeout(r, 0));

  it('resolves a job pathHash to a friendly "parent/file" label, relabelling in place', async () => {
    __setResolveHashForTest(async () => 'C:/Users/me/Documents/Projects/report.pdf');
    projectJobsToTasks(rows({ pathHash: 'hh', state: 'PROCESSING', collection: 'docs' }));
    // Until the async resolve settles, the short-hash fallback shows.
    expect(getTask('idxjob:hh')?.label).toContain('(hh');
    await flush();
    // In production the resolve callback re-projects; here we re-project to assert
    // the cached friendly label is now used.
    projectJobsToTasks(rows({ pathHash: 'hh', state: 'PROCESSING', collection: 'docs' }));
    expect(getTask('idxjob:hh')?.label).toBe('Indexing · Projects/report.pdf');
  });

  it('keeps the short-hash fallback when the hash cannot be resolved (found:false)', async () => {
    __setResolveHashForTest(async () => null);
    projectJobsToTasks(rows({ pathHash: 'hx', state: 'PENDING', collection: 'docs' }));
    await flush();
    projectJobsToTasks(rows({ pathHash: 'hx', state: 'PENDING', collection: 'docs' }));
    expect(getTask('idxjob:hx')?.label).toBe('Indexing · docs (hx)');
  });

  it('a bare filename (no parent folder) resolves to just the file name', async () => {
    __setResolveHashForTest(async () => 'report.pdf');
    projectJobsToTasks(rows({ pathHash: 'hf', state: 'PROCESSING', collection: 'docs' }));
    await flush();
    projectJobsToTasks(rows({ pathHash: 'hf', state: 'PROCESSING', collection: 'docs' }));
    expect(getTask('idxjob:hf')?.label).toBe('Indexing · report.pdf');
  });
});

// Minimal EventSource double: EnvelopeStream attaches a 'frame' listener and
// reads event.data (JSON). We emit frames by invoking that listener.
class FakeEventSource {
  listeners: Record<string, Array<(e: unknown) => void>> = {};
  closed = false;
  constructor(public url: string) {}
  addEventListener(type: string, fn: (e: unknown) => void): void {
    (this.listeners[type] ??= []).push(fn);
  }
  removeEventListener(type: string, fn: (e: unknown) => void): void {
    this.listeners[type] = (this.listeners[type] ?? []).filter((f) => f !== fn);
  }
  close(): void {
    this.closed = true;
  }
  emitFrame(envelope: unknown): void {
    for (const fn of this.listeners['frame'] ?? [])
      fn({ data: JSON.stringify(envelope) });
  }
  emitOpen(): void {
    for (const fn of this.listeners['open'] ?? []) fn(new Event('open'));
  }
}

const lifecycle = (seq: number, payload: unknown) => ({
  streamId: 'indexing-jobs/v1',
  frameKind: 'LIFECYCLE',
  seq,
  ts: '2026-05-25T00:00:00Z',
  resumeToken: `t${seq}`,
  payload,
});

describe('§32 #1 — startIndexingJobsBridge (stream → reducer → tasks)', () => {
  it('projects a snapshot, removes on delete, and survives a reset cycle', () => {
    let es: FakeEventSource | undefined;
    const stop = startIndexingJobsBridge('http://127.0.0.1:55393', {
      eventSourceFactory: (url) => {
        es = new FakeEventSource(url);
        return es as unknown as EventSource;
      },
    });
    expect(es?.url).toBe('http://127.0.0.1:55393/api/indexing-jobs/stream');

    es!.emitFrame(
      lifecycle(1, {
        kind: 'snapshot',
        items: [
          { pathHash: 'a1b2c3d4e5', state: 'PROCESSING', collection: 'docs', lastUpdatedMs: Date.now() },
          { pathHash: 'f6g7h8i9j0', state: 'FAILED', collection: 'docs' },
        ],
      }),
    );
    expect(getTask('idxjob:a1b2c3d4e5')?.status).toBe('running');
    expect(getTask('idxjob:f6g7h8i9j0')?.status).toBe('failed');

    // delete frame → that job's task is removed (not 'succeeded').
    es!.emitFrame({
      streamId: 'indexing-jobs/v1',
      frameKind: 'UPDATE',
      seq: 2,
      ts: '2026-05-25T00:00:01Z',
      resumeToken: 't2',
      payload: { kind: 'delete', primaryKeyValue: 'a1b2c3d4e5' },
    });
    expect(getTask('idxjob:a1b2c3d4e5')).toBeUndefined();

    // reset → all job-tasks removed; a subsequent snapshot rebuilds cleanly.
    es!.emitFrame(lifecycle(3, { kind: 'reset' }));
    expect(getTask('idxjob:f6g7h8i9j0')).toBeUndefined();
    es!.emitFrame(
      lifecycle(4, {
        kind: 'snapshot',
        items: [{ pathHash: 'f6g7h8i9j0', state: 'PROCESSING', collection: 'docs', lastUpdatedMs: Date.now() }],
      }),
    );
    expect(getTask('idxjob:f6g7h8i9j0')?.status).toBe('running'); // not stuck

    stop();
    expect(es!.closed).toBe(true);
  });

  it('residue #3 — bridge + a second consumer of the same URL share ONE pooled EventSource', () => {
    let es: FakeEventSource | undefined;
    const stop = startIndexingJobsBridge('http://127.0.0.1:55393', {
      eventSourceFactory: (url) => (es = new FakeEventSource(url)) as unknown as EventSource,
    });
    // A second consumer (e.g. the core.indexing-jobs Resource view) subscribes
    // to the SAME stream URL — it must attach to the bridge's pooled stream, not
    // open a new EventSource. createConfig throwing proves it is never called.
    let secondFrames = 0;
    const stop2 = subscribePooled(
      'http://127.0.0.1:55393/api/indexing-jobs/stream',
      () => {
        secondFrames += 1;
      },
      () => {
        throw new Error('createConfig must not run — URL already pooled');
      },
    );
    expect(__poolSizeForTest()).toBe(1); // ONE shared stream, not two

    es!.emitFrame(
      lifecycle(1, {
        kind: 'snapshot',
        items: [{ pathHash: 'z1', state: 'PROCESSING', collection: 'docs', lastUpdatedMs: Date.now() }],
      }),
    );
    // The shared EventSource hydrates an eligible, fresh PROCESSING row for both consumers.
    expect(getTask('idxjob:z1')?.status).toBe('running'); // bridge projected
    expect(secondFrames).toBeGreaterThan(0); // second consumer also received

    stop2();
    expect(es!.closed).toBe(false); // bridge still subscribed → stream stays open
    stop();
    expect(es!.closed).toBe(true); // last release closes the shared stream
  });

  // Tempdoc 575 §14: a job that STOPS getting heartbeat frames must demote on the
  // re-eval tick — the projection re-derives staleness from the last known rows,
  // so a wedged-loop job (no new Delta.Update) crosses the freshness window and
  // flips running → queued WITHOUT a new frame.
  it('575 §14: the re-eval tick demotes a job whose heartbeat goes stale (no new frame)', () => {
    vi.useFakeTimers();
    try {
      const t0 = Date.now();
      let es: FakeEventSource | undefined;
      const stop = startIndexingJobsBridge('http://127.0.0.1:55393', {
        eventSourceFactory: (url) => (es = new FakeEventSource(url)) as unknown as EventSource,
      });
      // A PROCESSING job beating NOW → running.
      es!.emitFrame(
        lifecycle(1, {
          kind: 'snapshot',
          items: [{ pathHash: 'tick1', state: 'PROCESSING', collection: 'docs', lastUpdatedMs: t0 }],
        }),
      );
      expect(getTask('idxjob:tick1')?.status).toBe('running');
      // No new frame; time passes the freshness window. The re-eval tick fires and
      // re-derives staleness from the last known rows → demotes to queued.
      vi.advanceTimersByTime(120_000);
      expect(getTask('idxjob:tick1')?.status).toBe('queued');
      stop();
    } finally {
      vi.useRealTimers();
    }
  });

  it('is a headless no-op when EventSource is unavailable and no factory given', () => {
    const g = globalThis as { EventSource?: unknown };
    const saved = g.EventSource;
    Reflect.deleteProperty(globalThis, 'EventSource'); // simulate SSR/headless
    try {
      const stop = startIndexingJobsBridge('http://x');
      expect(listTasks()).toHaveLength(0);
      stop(); // does not throw
    } finally {
      g.EventSource = saved;
    }
  });

  describe('tempdoc 662 — multiplexed path', () => {
    function multiplexOn(): { multiplex: MultiplexedStream; source: FakeEventSource } {
      let source!: FakeEventSource;
      const multiplex = new MultiplexedStream({
        url: 'http://test/api/shell-events/stream',
        eventSourceFactory: (url) => {
          source = new FakeEventSource(url);
          return source as unknown as EventSource;
        },
      });
      multiplex.start();
      source.emitOpen(); // realistic EventSource ordering: 'open' precedes any 'frame'
      return { multiplex, source };
    }

    it('subscribes on the shared multiplexer instead of opening its own EventSource', () => {
      const { multiplex, source } = multiplexOn();
      const stop = startIndexingJobsBridge('http://127.0.0.1:55393', { multiplex });

      // Only the ONE multiplexed connection's EventSource exists — no dedicated socket.
      source.emitFrame({
        streamId: 'surface:indexing-jobs',
        frameKind: 'LIFECYCLE',
        seq: 1,
        ts: '2026-07-01T00:00:00Z',
        resumeToken: 'tok-1',
        payload: {
          kind: 'snapshot',
          items: [{ pathHash: 'mux1', state: 'PROCESSING', collection: 'docs', lastUpdatedMs: Date.now() }],
        },
      });
      expect(getTask('idxjob:mux1')?.status).toBe('running');

      stop();
    });

    it('ignores a frame for a different streamId on the same multiplexed connection', () => {
      const { multiplex, source } = multiplexOn();
      const stop = startIndexingJobsBridge('http://127.0.0.1:55393', { multiplex });

      source.emitFrame({
        streamId: 'surface:action-ledger', // a DIFFERENT logical stream on the same socket
        frameKind: 'UPDATE',
        seq: 1,
        ts: '2026-07-01T00:00:00Z',
        resumeToken: 'tok-1',
        payload: { kind: 'entry-appended' },
      });
      expect(listTasks().filter((t) => t.id.startsWith('idxjob:'))).toHaveLength(0);

      stop();
    });
  });
});
