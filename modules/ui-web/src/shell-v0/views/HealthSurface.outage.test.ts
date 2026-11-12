// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * Health when the Engine stops answering (follow-up C1, FC1-FC3). The status reads go through the real
 * status poller; the Engine's answers are replaced by a refused connection, an aborted read or a server
 * error. Each case observes only Health's rendered words.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './HealthSurface.js';
import type { HealthSurface } from './HealthSurface.js';
import { __feedContactForTest, __resetAiStateForTest, getStatusReceivedAtMs, refreshStatusNow, startAiStateStore, type StatusSnapshot } from '../state/aiStateStore.js';
import { __resetEngineLossForTest } from '../state/engineLoss.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import { readIndexedRoots } from '../state/indexedRoots.js';

type Engine = 'up' | 'refused' | 'aborted' | 'server error' | 'hung';
let engine: Engine = 'up';
const json = (value: unknown, code = 200) => new Response(JSON.stringify(value), { status: code, headers: { 'Content-Type': 'application/json' } });
const status = (): StatusSnapshot => ({
  status: 'ok',
  worker: { core: { indexState: 'IDLE', indexHealthy: true, indexedDocuments: 5, searchableDocuments: 5, ownSearchableDocuments: 5, pendingJobs: 0 } },
  readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] }, aiFeatures: { state: 'READY', reasonCodes: [] } } },
  gpu: { available: true, cudaFunctional: true }, memoryUsedBytes: 5, memoryMaxBytes: 10,
} as unknown as StatusSnapshot);

/** The Engine as the browser sees it: answering, or each way a stopped Engine fails a read. */
async function answer(path: string): Promise<Response> {
  if (engine === 'refused') throw new TypeError('Failed to fetch');
  if (engine === 'aborted') throw new DOMException('The user aborted a request.', 'AbortError');
  if (engine === 'server error') return json({ error: 'Internal Server Error' }, 500);
  if (engine === 'hung') return new Promise<Response>(() => {});
  if (path.includes('/api/status')) return json(status());
  if (path.includes('/api/inference')) return json({ mode: 'online', starting: false, available: true });
  if (path.includes('/api/ai/install/status')) return json({ state: 'idle', installedFully: true });
  if (path.includes('/api/ai/runtime/status')) return json({ active: { modelPath: '/models/local', gpuLayers: 0 } });
  if (path.includes('indexing-roots')) return json({ items: [{ pathHash: 'notes', collection: 'default', status: 'indexed', walkCompleted: true, fileCount: 4, inFlightCount: 0, failedCount: 0 }], count: 1 });
  if (path === '/api/condition-recovery-index') return json({ entries: [] });
  if (path.includes('/stream')) return json({}, 404);
  return json({ jobs: [] });
}

let el: HealthSurface | undefined;
const row = (id: string) => el!.shadowRoot!.querySelector(`[data-health-row=${id}] dd`)?.textContent?.trim() ?? '';
const metric = (label: string) => [...el!.shadowRoot!.querySelectorAll('.health-metrics > div')]
  .find(node => node.querySelector('dt')?.textContent?.trim() === label)?.querySelector('dd')?.textContent?.trim() ?? '';
const text = () => el!.shadowRoot!.textContent ?? '';
async function settle(): Promise<void> {
  for (let i = 0; i < 20; i++) { await Promise.resolve(); await new Promise(resolve => setTimeout(resolve, 0)); }
  el!.requestUpdate(); await el!.updateComplete;
}
function create(): HealthSurface {
  el = document.createElement('jf-health-surface') as HealthSurface;
  el.host_ = createMockHostApi({ data: { fetch: answer } });
  return el;
}
/** One status read through the poller, then let Health's own reads finish. */
async function statusRead(): Promise<void> {
  await refreshStatusNow();
  await (el as unknown as { refresh(): Promise<void> }).refresh();
  await settle();
}
/** The app with the Engine answering and Health on screen, every source fine and current. */
async function healthy(): Promise<void> {
  startAiStateStore('');
  await vi.waitFor(() => expect(getStatusReceivedAtMs()).not.toBeNull());
  await readIndexedRoots(path => answer(path));
  document.body.append(create());
  await vi.waitFor(() => expect(el!.loading).toBe(false));
  await settle();
  expect(['engine', 'folders', 'index', 'gpu'].map(row), 'every row is quiet while the Engine answers').toEqual(['', '', '', '']);
  expect(metric('Files')).toBe('5');
}

beforeEach(() => {
  __resetAiStateForTest(); __resetEngineLossForTest(); engine = 'up';
  vi.useFakeTimers({ toFake: ['Date'] }); vi.setSystemTime(new Date('2026-11-12T12:00:00Z'));
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => answer(new URL(String(input), 'http://localhost').pathname)));
});
afterEach(() => {
  el?.remove(); el = undefined;
  __resetAiStateForTest(); __resetEngineLossForTest(); vi.unstubAllGlobals(); vi.useRealTimers();
});

const FAILURES: Engine[] = ['refused', 'aborted', 'server error'];

describe('FC1 FC2 one failed status read makes the dependent facts unconfirmed at once', () => {
  it.each(FAILURES)('Engine %s with Health open: rows and counts unconfirmed within one read, words instead of a raw error; rows return when reads resume', async failure => {
    await healthy();
    vi.setSystemTime(Date.now() + 3_000);
    engine = failure;
    await statusRead();
    // Three seconds after the last answer: well inside the 20 s freshness bound.
    expect(row('engine'), 'Engine row').toMatch(/unconfirmed/i);
    expect(row('gpu'), 'Graphics card row').toMatch(/unconfirmed/i);
    expect(row('index'), 'Index row').toMatch(/unconfirmed/i);
    expect(row('folders'), 'Folders row').toMatch(/unconfirmed/i);
    expect(metric('Files'), 'Files count').toMatch(/last known|unconfirmed/i);
    expect(metric('Queue'), 'Queue count').toMatch(/last known|unconfirmed|—/i);
    expect(el!.shadowRoot!.querySelector('[role=status]')?.textContent, 'the headline').not.toContain('Everything is working');
    expect(text(), 'no raw transport error').not.toMatch(/Failed to fetch|aborted a request|Health observation unavailable|\(500\)/);
    expect(el!.shadowRoot!.querySelector('.header [role=status]')?.textContent, 'the headline says it in words').toMatch(/Engine/);

    vi.setSystemTime(Date.now() + 2_000);
    engine = 'up';
    await statusRead();
    await readIndexedRoots(path => answer(path));
    await settle();
    expect(['engine', 'folders', 'index', 'gpu'].map(row), 'rows return without a reload').toEqual(['', '', '', '']);
    expect(metric('Files')).toBe('5');
    expect(text()).not.toMatch(/Can.t get current status/);
  });

  it('Engine refused while Health is hidden: Health shows the rows unconfirmed when it returns', async () => {
    await healthy();
    el!.remove();
    vi.setSystemTime(Date.now() + 3_000);
    engine = 'refused';
    await refreshStatusNow();
    document.body.append(el!);
    await settle();
    expect(row('engine')).toMatch(/unconfirmed/i);
    expect(row('gpu')).toMatch(/unconfirmed/i);
    expect(metric('Files')).toMatch(/last known|unconfirmed/i);
  });

  it('the Engine comes back while Health is hidden: Health returns quiet, without a reload', async () => {
    await healthy();
    vi.setSystemTime(Date.now() + 3_000);
    engine = 'refused';
    await statusRead();
    expect(row('engine')).toMatch(/unconfirmed/i);
    el!.remove();
    vi.setSystemTime(Date.now() + 2_000);
    engine = 'up';
    await refreshStatusNow();
    await readIndexedRoots(path => answer(path));
    document.body.append(el!);
    await settle();
    expect(['engine', 'folders', 'index', 'gpu'].map(row)).toEqual(['', '', '', '']);
  });

  it('a reload while the Engine is down: no raw transport error, no claim of health', async () => {
    engine = 'refused';
    startAiStateStore('');
    await readIndexedRoots(path => answer(path));
    document.body.append(create());
    await vi.waitFor(() => expect(el!.loading).toBe(false));
    await settle();
    expect(text()).not.toMatch(/Failed to fetch/);
    for (const id of ['engine', 'folders', 'index', 'gpu']) expect(row(id), id).toMatch(/unconfirmed/i);
    expect(el!.shadowRoot!.querySelector('[role=status]')?.textContent).not.toContain('Everything is working');
  });
});

describe('FC3 during an outage Index and Folders claim no work that is not happening', () => {
  it('reads fail while the event stream still beats: no "Catching up", "backend is busy" or "Rebuilding"', async () => {
    await healthy();
    vi.setSystemTime(Date.now() + 16_000);
    __feedContactForTest();
    engine = 'refused';
    await statusRead();
    await readIndexedRoots(path => answer(path));
    await settle();
    for (const id of ['index', 'folders']) {
      expect(row(id), id).not.toMatch(/Catching up|backend is busy|Rebuilding/i);
      expect(row(id), id).toMatch(/unconfirmed/i);
    }
  });

  it('reads stop answering while the event stream still beats: Index names no catching up', async () => {
    await healthy();
    vi.setSystemTime(Date.now() + 16_000);
    __feedContactForTest();
    engine = 'hung';
    el!.requestUpdate(); await el!.updateComplete;
    for (const id of ['index', 'folders']) expect(row(id), id).not.toMatch(/Catching up|backend is busy|Rebuilding/i);
    expect(row('index')).toMatch(/unconfirmed/i);
  });
});
