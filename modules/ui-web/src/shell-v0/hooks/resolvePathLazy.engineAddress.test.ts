// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * FC7 — a file name is the answer of the Engine asked. The resolver keys what it remembers by the
 * Engine's address as well as the resource and the hash, so two Engines with the same hash (another
 * address, or a profile on another Engine) never share a cached name or a cached not-found. The
 * Tasks tray asks the Engine it reads its jobs from, like Activity, Library and the failed-files
 * list, and a late answer from the Engine it read before never names a file of the one it reads now.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { resolvePathLazy, __resetForTest as resetResolutions } from './resolvePathLazy.js';
import {
  __resetForTest as resetResourceCatalog,
  __seedForTest as seedResourceCatalog,
} from '../../api/registry/ResourceCatalogClient.js';
import { __resetTasksForTest, getTask } from '../substrates/tasks/index.js';
import { startIndexingJobsBridge, __resetResolvedLabelsForTest } from '../substrates/tasks/indexingJobsBridge.js';
import { __resetEnvelopeStreamPoolForTest } from '../streaming/EnvelopeStreamPool.js';

const ENGINE_A = 'http://127.0.0.1:4101';
const ENGINE_B = 'http://127.0.0.1:4102';
const HASH = 'same-hash-on-both';
const RESOURCE = 'core.indexing-jobs';

/** What each Engine knows of the hash; anything else (the page's own origin) has no such route. */
let known: Record<string, string | null>;
/** Every file-name read, by URL, with its answer held until released when `hold` is set. */
let asked: string[];
let hold: Map<string, () => void> | null;

function seedResource(): void {
  seedResourceCatalog({ schemaVersion: '1.0', catalogVersion: 1, namespace: 'core', primitive: 'Resource', entries: [{
    id: RESOURCE, kind: 'indexing-jobs', primaryKey: 'pathHash', category: 'TABULAR', subscriptionMode: 'SSE_STREAM',
    endpoint: '/api/indexing-jobs/stream', schema: '', history: null, recovery: null,
    presentation: { labelKey: RESOURCE, descriptionKey: RESOURCE, iconHint: null, category: null },
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' },
    privacy: { pathPolicy: 'HASHED_REQUIRES_RESOLVER', loopbackOnly: true, resolver: 'core.resolve-path-hash' },
    itemOperations: [], collectionOperations: [], audience: 'USER', consumers: [], role: 'PRODUCT',
  }] } as never);
}

beforeEach(() => {
  resetResolutions();
  resetResourceCatalog();
  seedResource();
  asked = [];
  hold = null;
  known = { [ENGINE_A]: '/engine-a/reports/report.pdf', [ENGINE_B]: '/engine-b/notes/other.txt' };
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input instanceof Request ? input.url : input);
    if (!url.endsWith('/api/library/path-label')) return new Response('{}', { status: 503 });
    asked.push(url);
    const engine = Object.keys(known).find((base) => url.startsWith(`${base}/`));
    if (hold && engine) await new Promise<void>((release) => hold!.set(engine, release));
    if (!engine) return new Response('not found', { status: 404 });
    const path = known[engine];
    return Response.json(path ? { found: true, path } : { found: false });
  }));
});

afterEach(() => {
  resetResolutions();
  resetResourceCatalog();
  vi.unstubAllGlobals();
});

describe('FC7 the resolver remembers each Engine\'s own answer', () => {
  it('two Engines with the same hash: each name is read from, and remembered for, its own Engine', async () => {
    expect(await resolvePathLazy(RESOURCE, HASH, { apiBase: ENGINE_A })).toBe('/engine-a/reports/report.pdf');
    expect(await resolvePathLazy(RESOURCE, HASH, { apiBase: ENGINE_B })).toBe('/engine-b/notes/other.txt');
    expect(asked).toEqual([`${ENGINE_A}/api/library/path-label`, `${ENGINE_B}/api/library/path-label`]);
    // Each answer is remembered for its own Engine: asking again reads nothing.
    expect(await resolvePathLazy(RESOURCE, HASH, { apiBase: ENGINE_A })).toBe('/engine-a/reports/report.pdf');
    expect(await resolvePathLazy(RESOURCE, HASH, { apiBase: ENGINE_B })).toBe('/engine-b/notes/other.txt');
    expect(asked).toHaveLength(2);
  });

  it('one Engine\'s "not found" is not another Engine\'s answer (a profile switched to another Engine)', async () => {
    known[ENGINE_A] = null;
    expect(await resolvePathLazy(RESOURCE, HASH, { apiBase: ENGINE_A })).toBeNull();
    expect(await resolvePathLazy(RESOURCE, HASH, { apiBase: ENGINE_B })).toBe('/engine-b/notes/other.txt');
  });

  it('the page\'s own Engine is one Engine, whether it is named by its address or left unnamed', async () => {
    known[window.location.origin] = '/own/file.md';
    expect(await resolvePathLazy(RESOURCE, HASH)).toBe('/own/file.md');
    expect(await resolvePathLazy(RESOURCE, HASH, { apiBase: window.location.origin })).toBe('/own/file.md');
    expect(asked).toHaveLength(1);
  });
});

class Source extends EventTarget {
  readyState = 1;
  close(): void { this.readyState = 2; }
  emit(frame: unknown): void { this.dispatchEvent(new MessageEvent('frame', { data: JSON.stringify(frame) })); }
}

describe('FC7 the Tasks tray asks the Engine it reads from', () => {
  let stop: (() => void) | undefined;
  let seq = 0;

  beforeEach(() => {
    __resetTasksForTest();
    __resetEnvelopeStreamPoolForTest();
    __resetResolvedLabelsForTest();
  });
  afterEach(() => {
    stop?.();
    stop = undefined;
    __resetTasksForTest();
    __resetEnvelopeStreamPoolForTest();
  });

  /** A tray reading `engine`'s jobs, with one file being indexed there. */
  function trayAt(engine: string): Source {
    const source = new Source();
    stop = startIndexingJobsBridge(engine, { eventSourceFactory: () => source as unknown as EventSource });
    source.emit({ streamId: 'indexing-jobs/v1', frameKind: 'LIFECYCLE', seq: ++seq, ts: '2026-11-12T00:00:00Z', resumeToken: `t${seq}`,
      payload: { kind: 'snapshot', items: [{ pathHash: HASH, state: 'PROCESSING', collection: 'docs', lastUpdatedMs: Date.now() }] } });
    return source;
  }

  async function label(): Promise<string | undefined> {
    for (let i = 0; i < 5; i++) {
      await new Promise((resolve) => setTimeout(resolve, 20));
    }
    return getTask(`idxjob:${HASH}`)?.label;
  }

  it('a tray whose Engine has its own address names the file from that Engine, not the page\'s origin', async () => {
    trayAt(ENGINE_A);
    await vi.waitFor(() => expect(asked.length).toBeGreaterThan(0));
    expect(asked.every((url) => url.startsWith(`${ENGINE_A}/`)), `every file-name read went to the Engine: ${asked.join(', ')}`).toBe(true);
    expect(await label()).toBe('Indexing · reports/report.pdf');
  });

  it('after the tray moves to another Engine, the earlier Engine\'s late answer names nothing', async () => {
    hold = new Map();
    trayAt(ENGINE_A);
    await vi.waitFor(() => expect(hold!.has(ENGINE_A)).toBe(true));
    stop!();
    trayAt(ENGINE_B);
    await vi.waitFor(() => expect(hold!.has(ENGINE_B)).toBe(true));
    hold.get(ENGINE_A)!();
    expect(await label(), 'the earlier Engine\'s file is not this Engine\'s').not.toContain('report.pdf');
    hold.get(ENGINE_B)!();
    expect(await label()).toBe('Indexing · notes/other.txt');
  });
});
