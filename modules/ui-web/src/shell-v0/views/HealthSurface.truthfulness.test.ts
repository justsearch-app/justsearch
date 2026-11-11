// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * Quiet Health (S6 piece 8): what Health states must be true of a current observation. Each case
 * observes only rendered words and controls, so it reads the same on any Health that renders them.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './HealthSurface.js';
import type { HealthSurface } from './HealthSurface.js';
import { __feedForTest, __resetAiStateForTest, __tickClockForTest, type StatusSnapshot } from '../state/aiStateStore.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import { requestMemberTab } from '../router/memberTabIntent.js';
import { __resetEngineLossForTest, reportEngineReachability } from '../state/engineLoss.js';
import { folderStatus, rememberFailedCounts } from '../state/folderStatus.js';
import { observeStatusSignal } from '../../api/statusInvalidation.js';
import { getIndexedRoots, readIndexedRoots } from '../state/indexedRoots.js';
import type { IndexedRootView } from '../../api/generated/schema-types/indexed-root-view.js';

const json = (value: unknown, code = 200) => new Response(JSON.stringify(value), { status: code, headers: { 'Content-Type': 'application/json' } });
const status = (over: Record<string, unknown> = {}): StatusSnapshot => ({
  status: 'ok',
  worker: { core: { indexState: 'IDLE', indexHealthy: true, indexedDocuments: 5, searchableDocuments: 5, pendingJobs: 0 } },
  readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] }, aiFeatures: { state: 'READY', reasonCodes: [] } } },
  gpu: { available: true, cudaFunctional: true }, memoryUsedBytes: 5, memoryMaxBytes: 10, ...over,
} as unknown as StatusSnapshot);
const remedy = (target: string) => ({ entries: [{ target, conditions: [{ conditionId: 'schema.reindex-required', subject: 'worker.schema', defaultArgsJson: '{}' }] }] });

type Route = (path: string) => Promise<Response> | Response | undefined;
let route: Route = () => undefined;
let el: HealthSurface | undefined;
let recoveryStream: ReadableStreamDefaultController<Uint8Array> | undefined;

async function mount(): Promise<HealthSurface> {
  el = document.createElement('jf-health-surface') as HealthSurface;
  el.host_ = createMockHostApi({ data: { fetch: async path => {
    const answer = await route(path);
    if (answer) return answer;
    if (path === '/api/condition-recovery-index') return json({ entries: [] });
    return json({ jobs: [] }, path.includes('/stream') ? 404 : 200);
  } } });
  document.body.append(el);
  await el.updateComplete;
  return el;
}
const text = () => el!.shadowRoot!.textContent ?? '';
async function settle(): Promise<void> {
  for (let i = 0; i < 20; i++) { await Promise.resolve(); await el!.updateComplete; }
}

beforeEach(() => {
  __resetAiStateForTest(); __resetEngineLossForTest(); route = () => undefined;
  vi.stubGlobal('fetch', vi.fn(async () => json({})));
});
afterEach(() => {
  el?.remove(); el = undefined; recoveryStream = undefined;
  __resetAiStateForTest(); __resetEngineLossForTest(); vi.unstubAllGlobals(); vi.useRealTimers();
});

describe('Health states only current facts', () => {
  it('SH3 status out of date while inference answers: no "connected" or "Online" claim anywhere on Health', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    __feedForTest({ status: status(), inference: { mode: 'online', starting: false, available: true } }); __tickClockForTest();
    vi.setSystemTime(Date.now() + 21_000);
    __feedForTest({ inference: { mode: 'online', starting: false, available: true } }); __tickClockForTest();
    await mount(); await settle();
    expect([...el!.shadowRoot!.querySelectorAll('.val')].map(node => node.textContent?.trim())).not.toContain('connected');
    expect(text()).not.toMatch(/\bOnline\b/);
  });

  it('a memory reading the status did not report carries no healthy tone', async () => {
    __feedForTest({ status: status({ memoryUsedBytes: undefined, memoryMaxBytes: undefined }) }); __tickClockForTest();
    await mount(); await settle();
    const memory = [...el!.shadowRoot!.querySelectorAll('.card')]
      .find(card => card.querySelector('.card-label')?.textContent?.trim() === 'Memory');
    expect(memory, 'the Memory reading renders').toBeDefined();
    expect(memory!.querySelector('jf-status-dot')?.getAttribute('tone')).not.toBe('success');
  });

  it('SH12 a failed recovery-condition read is stated, never read as "no recoverable conditions"', async () => {
    __feedForTest({ status: status() }); __tickClockForTest();
    route = path => path === '/api/condition-recovery-index' ? json({}, 503) : undefined;
    await mount(); await settle();
    expect(text()).not.toContain('No recoverable conditions active');
    await vi.waitFor(() => expect(text()).toContain('Recovery conditions unconfirmed'));
  });

  it('invented backup and integrity placeholders are gone', async () => {
    __feedForTest({ status: status() }); __tickClockForTest();
    await mount(); await settle();
    expect(text()).not.toContain('Last backup');
    expect(text()).not.toContain('Integrity check');
  });

  it('SH-GPU unavailable graphics telemetry alone never states that inference runs on the CPU', async () => {
    __feedForTest({ status: status({ gpu: { available: false } }) }); __tickClockForTest();
    await mount(); await settle();
    expect(text()).not.toContain('Inference runs on CPU');
  });
});

describe('late and retired reads', () => {
  function heldFailedJobs() {
    let release: (value: Response) => void = () => {};
    let first = true;
    route = path => {
      if (!path.startsWith('/api/indexing/failed-jobs')) return undefined;
      if (!first) return json({ jobs: [] });
      first = false;
      return new Promise<Response>(resolve => { release = resolve; });
    };
    return (jobs: unknown[]) => release(json({ jobs }));
  }

  it('a late failed-files answer cannot resurrect failures a newer answer cleared', async () => {
    __feedForTest({ status: status() }); __tickClockForTest();
    const releaseFirst = heldFailedJobs();
    await mount();
    await (el as unknown as { refresh(): Promise<void> }).refresh();
    await settle();
    releaseFirst([{ path: '/home/me/late.txt', errorMessage: 'Parser failed' }]);
    await settle();
    expect(text()).not.toContain('/home/me/late.txt');
  });

  it('a failed-files answer arriving after Health left updates nothing', async () => {
    __feedForTest({ status: status() }); __tickClockForTest();
    const releaseFirst = heldFailedJobs();
    await mount();
    el!.remove();
    releaseFirst([{ path: '/home/me/late.txt', errorMessage: 'Parser failed' }]);
    for (let i = 0; i < 20; i++) await Promise.resolve();
    await el!.updateComplete;
    expect(el!.shadowRoot!.textContent).not.toContain('/home/me/late.txt');
  });

  it('SH12 after the recovery stream ends, a later REST answer replaces the streamed remedy', async () => {
    vi.useFakeTimers({ toFake: ['setInterval'] });
    __feedForTest({ status: status() }); __tickClockForTest();
    let current = remedy('core.reindex');
    route = path => path === '/api/condition-recovery-index' ? json(current)
      : path === '/api/condition-recovery-index/stream'
        ? new Response(new ReadableStream<Uint8Array>({ start(controller) { recoveryStream = controller; } }), { headers: { 'Content-Type': 'text/event-stream' } })
        : undefined;
    await mount();
    await vi.waitFor(() => expect(recoveryStream).toBeDefined());
    recoveryStream!.enqueue(new TextEncoder().encode(`data: ${JSON.stringify({ frameKind: 'LIFECYCLE', payload: { kind: 'snapshot', index: current } })}\n\n`));
    await vi.waitFor(() => expect(el!.recommendedActions.get('schema.reindex-required|worker.schema')?.target).toBe('core.reindex'));
    recoveryStream!.close();
    current = remedy('core.rebuild-index');
    await vi.advanceTimersByTimeAsync(5_000);
    await vi.waitFor(() => expect(el!.recommendedActions.get('schema.reindex-required|worker.schema')?.target).toBe('core.rebuild-index'));
  });

  it('learning the engine\'s identity for the first time is not a restart: the remedy stays', async () => {
    __feedForTest({ status: status() }); __tickClockForTest();
    route = path => path === '/api/condition-recovery-index' ? json(remedy('core.reindex')) : undefined;
    await mount();
    await vi.waitFor(() => expect(el!.shadowRoot!.querySelector('.recommended jf-operation')).not.toBeNull());
    await observeStatusSignal('health-epoch-first', 1);
    await settle();
    expect(el!.shadowRoot!.querySelector('.recommended jf-operation')).not.toBeNull();
  });

  it('a new engine process withholds the previous process\'s remedy until it is read again', async () => {
    await observeStatusSignal('health-epoch-before', 1);
    __feedForTest({ status: status() }); __tickClockForTest();
    let answer: Response | Promise<Response> = json(remedy('core.reindex'));
    route = path => path === '/api/condition-recovery-index' ? answer : undefined;
    await mount();
    await vi.waitFor(() => expect(el!.shadowRoot!.querySelector('.recommended jf-operation')).not.toBeNull());
    answer = new Promise<Response>(() => {});
    await observeStatusSignal('health-epoch-after', 1);
    await settle();
    expect(el!.shadowRoot!.querySelector('.recommended jf-operation')).toBeNull();
  });
});

describe('Diagnostics reachability', () => {
  it('SH14 AI Brain\'s engine-history link opens Diagnostics and focuses the engine section', async () => {
    __feedForTest({ status: status(), inference: { mode: 'online', starting: false, available: true } }); __tickClockForTest();
    requestMemberTab('core.health-surface', 'ai-engine');
    await mount(); await settle();
    expect(el!.shadowRoot!.querySelector('details')?.open).toBe(true);
    expect(el!.shadowRoot!.activeElement?.id).toBe('ai-engine');
  });

  it('SH14 an engine-history link arriving during engine loss focuses the section once the engine returns', async () => {
    __feedForTest({ status: status(), inference: { mode: 'online', starting: false, available: true } }); __tickClockForTest();
    reportEngineReachability(true, Date.now() - 60_000);
    await mount(); await settle();
    requestMemberTab('core.health-surface', 'ai-engine');
    await settle();
    reportEngineReachability(false, Date.now());
    await settle();
    expect(el!.shadowRoot!.activeElement?.id).toBe('ai-engine');
  });
});

describe('frozen worker sample, then engine loss', () => {
  it('last-known facts keep an uncounted index unknown and state the sample age beside the receipt', async () => {
    const sampledAt = Date.now() - 10 * 60_000;
    __feedForTest({ status: status({ meta: { workerRpcAtMs: sampledAt, workerRpcStale: true },
      worker: { core: { indexState: 'IDLE', indexHealthy: true, indexedDocuments: 0, searchableDocuments: 0,
        indexedDocumentsUnknown: true, pendingJobs: 3 } } }) });
    __tickClockForTest();
    reportEngineReachability(true, Date.now());
    await mount(); await settle();
    const fact = (label: string) => el!.shadowRoot!.querySelector(`[data-fact="${label}"]`)?.textContent ?? '';
    expect(fact('Searchable files')).toContain('Unknown');
    expect(fact('Searchable files')).not.toMatch(/\b0\b/);
    expect(fact('Files waiting')).toMatch(/last seen (just now|\d+ s ago)/);
    expect(fact('Files waiting')).toMatch(/sampled by the engine 10 min ago/);
  });
});

describe('SH-COUNTS unknown folder job counts', () => {
  const row = (over: Partial<IndexedRootView>): IndexedRootView => ({ pathHash: 'notes', collection: 'default',
    status: 'indexed', walkCompleted: true, fileCount: 4, inFlightCount: 0, failedCount: 0, ...over });
  const context = { relativeTime: '', verifiedRelativeTime: '', provisional: false };

  it('a folder whose count query failed is unconfirmed, never ready', () => {
    const state = folderStatus(row({ inFlightCount: null, failedCount: null }), context);
    expect(state.state).toBe('unknown');
    expect(state.metaText).toContain('unconfirmed');
  });

  it('a failed count keeps the last observed failures instead of reading them as cleared', () => {
    const remembered = rememberFailedCounts({}, [row({ failedCount: 2 })]);
    expect(rememberFailedCounts(remembered, [row({ inFlightCount: null, failedCount: null })])).toEqual({ notes: 2 });
  });
});

describe('one folder observation, several readers', () => {
  it('a newer failed read by another reader cannot cancel an earlier read\'s answer', async () => {
    let answer: (response: Response) => void = () => {};
    const library = readIndexedRoots(() => new Promise<Response>(resolve => { answer = resolve; }), true);
    expect(await readIndexedRoots(async () => json({}, 503))).toBeNull();
    answer(json({ items: [{ pathHash: 'notes', collection: 'default', status: 'indexed', walkCompleted: true, fileCount: 4 }] }));
    expect((await library)?.roots.map(root => root.pathHash)).toEqual(['notes']);
    expect(getIndexedRoots().confirmed).toBe(true);
  });
});
