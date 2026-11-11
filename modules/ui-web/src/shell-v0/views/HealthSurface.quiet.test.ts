// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './HealthSurface.js';
import type { HealthSurface } from './HealthSurface.js';
import { FrameStatusController } from '../controllers/frameStatusController.js';
import { __resetAiStateForTest, __feedForTest, __tickClockForTest, startAiStateStore, type StatusSnapshot } from '../state/aiStateStore.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import { readIndexedRoots } from '../state/indexedRoots.js';
import { __resetTasksForTest, bindTaskOperation, startTask } from '../substrates/tasks/index.js';
import { __resetRecallCursor, markSeen } from '../substrates/recall/recallCursor.js';
import type { MultiplexedStream } from '../streaming/MultiplexedStream.js';

const status = (): StatusSnapshot => ({
  status: 'ok',
  worker: { core: { indexState: 'IDLE', indexHealthy: true, indexedDocuments: 5, searchableDocuments: 5, pendingJobs: 0 },
    migration: { pendingJobsCount: 0, pendingReadyJobsCount: 0, processingJobsCount: 0 } },
  readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] }, aiFeatures: { state: 'READY', reasonCodes: [] } } },
  gpu: { available: true, cudaFunctional: true }, memoryUsedBytes: 5, memoryMaxBytes: 10,
});
const json = (value: unknown, code = 200) => new Response(JSON.stringify(value), { status: code });
let el: HealthSurface | undefined;
let controller: FrameStatusController | undefined;
let failRecovery = false;
let failPresence = false;
let gpu = status().gpu;
let cpuModel = true;
let outcome: unknown = null;
let outcomes: Record<string, unknown> = {};
async function mount(ready = true): Promise<HealthSurface> {
  if (ready) {
    startAiStateStore('');
    __feedForTest({ status: { ...status(), gpu }, inference: { mode: 'online', starting: false, available: true } });
    __tickClockForTest();
    controller = new FrameStatusController({ addController() {}, removeController() {}, requestUpdate() {}, updateComplete: Promise.resolve(true), apiBase: '', multiplex: null, authorizationHost: null });
    controller.hostConnected();
  }
  el = document.createElement('jf-health-surface') as HealthSurface;
  el.host_ = createMockHostApi({ data: { fetch: async path => {
    if (path === '/api/condition-recovery-index') return json({ entries: [] }, failRecovery ? 503 : 200);
    return json({ jobs: [] }, path.includes('/stream') ? 404 : 200);
  } } });
  document.body.append(el);
  await vi.waitFor(() => expect(el!.loading).toBe(false));
  await el.updateComplete;
  return el;
}
const row = (id: string) => el!.shadowRoot!.querySelector(`[data-health-row=${id}] dd`);
/** The retained action-ledger history, as the shell's multiplexed stream delivers each snapshot. */
function ledger(): (keys: readonly [string, 'SUCCESS' | 'FAILURE'][]) => Promise<void> {
  let deliver: ((snapshot: { payload: unknown[] }) => void) | undefined;
  controller!.setMultiplex({ subscribe: (_id: string, _config: unknown, listener: typeof deliver) => { deliver = listener; return () => undefined; } } as unknown as MultiplexedStream);
  return async keys => {
    deliver!({ payload: keys.map(([key, result]) => ({ id: `operation:${key}`, kind: 'operation', occurredAt: '2026-11-11T11:58:00Z',
      originator: 'USER', operationId: 'core.rebuild-index', outcome: result })) });
    for (let i = 0; i < 5; i++) await new Promise(resolve => setTimeout(resolve, 0));
    await el!.updateComplete;
  };
}

beforeEach(() => {
  __resetAiStateForTest(); __resetTasksForTest(); __resetRecallCursor();
  failRecovery = false; failPresence = false; gpu = status().gpu; cpuModel = true; outcome = null; outcomes = {};
  vi.useFakeTimers({ toFake: ['Date'] }); vi.setSystemTime(new Date('2026-11-11T12:00:00Z'));
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input);
    if (path.includes('/api/ai/install/status')) return json({ state: 'idle', installedFully: true });
    if (path.includes('/api/ai/runtime/status')) return json(cpuModel ? { active: { modelPath: '/models/local', gpuLayers: 0 } } : {});
    if (path.includes('/api/ai/packs/status')) return json({ state: 'idle', phase: '' });
    if (path.includes('/api/inference')) return json({ mode: 'online', starting: false, available: true });
    if (path.includes('/api/status')) return json({ ...status(), gpu });
    if (path.includes('/api/presence')) return json({ runs: [] }, failPresence ? 503 : 200);
    if (path.includes('indexing-roots')) return json({ items: [], count: 0 });
    const key = path.split('/api/operation-history/')[1];
    if (key && outcomes[key]) return json(outcomes[key]);
    if (outcome && path.includes('/api/operation-history/')) return json(outcome);
    return json({});
  }));
});
afterEach(() => { el?.remove(); el = undefined; controller?.hostDisconnected(); controller = undefined;
  __resetAiStateForTest(); __resetTasksForTest(); __resetRecallCursor(); vi.unstubAllGlobals(); vi.useRealTimers(); });

describe('quiet System Health', () => {
  it('cold start shows six unconfirmed rows without healthy or idle claims', async () => {
    const health = await mount(false);
    expect([...health.shadowRoot!.querySelectorAll('.health-row dt')].map(node => node.textContent?.trim()))
      .toEqual(['Engine', 'Folders', 'Index', 'AI models', 'Graphics card', 'Background work']);
    for (const id of ['engine', 'folders', 'index', 'models', 'gpu', 'work']) expect(row(id)?.textContent).toContain('unconfirmed');
    expect(health.shadowRoot!.querySelector('.header')?.textContent).not.toContain('Everything is working');
  });
  it('current fine observations leave six empty cells and one summary', async () => {
    await mount();
    await vi.waitFor(() => expect([...el!.shadowRoot!.querySelectorAll('.health-row dd')].map(node => node.textContent?.trim())).toEqual(['', '', '', '', '', '']));
    expect(el!.shadowRoot!.querySelectorAll('[role=status]')).toHaveLength(1);
    expect(el!.shadowRoot!.querySelector('[role=status]')?.textContent).toBe('Everything is working');
    expect(el!.shadowRoot!.querySelector('details')?.open).toBe(false);
  });
  it('status expires despite inference contact and Diagnostics makes no connected or Online claim', async () => {
    await mount();
    vi.setSystemTime(Date.now() + 21_000);
    __feedForTest({ inference: { mode: 'online', starting: false, available: true } }); __tickClockForTest();
    el!.requestUpdate(); await el!.updateComplete;
    expect(row('engine')?.textContent).toContain('unconfirmed');
    expect(row('gpu')?.textContent).toContain('unconfirmed');
    const details = el!.shadowRoot!.querySelector('details')!; details.open = true;
    expect([...details.querySelectorAll('.val')].map(node => node.textContent?.trim())).not.toContain('connected');
    expect(details.textContent).not.toMatch(/\bOnline\b/);
  });
  it('unavailable GPU telemetry alone cannot establish CPU-only health', async () => {
    gpu = { available: false }; cpuModel = false;
    await mount();
    expect(row('gpu')?.textContent).toContain('unconfirmed');
  });
  it('current CPU realization allows a CPU-only setup without a warning', async () => {
    gpu = { available: false };
    await mount();
    await vi.waitFor(() => expect(row('gpu')?.textContent?.trim()).toBe(''));
  });
  it('recovery-read failure leaves only dependent index health unconfirmed', async () => {
    failRecovery = true;
    await mount();
    expect(row('index')?.textContent).toContain('Recovery conditions unconfirmed');
    expect(row('engine')?.textContent?.trim()).toBe('');
    expect(el!.shadowRoot!.querySelector('.recommended jf-operation')).toBeNull();
  });
  it('failed presence does not invalidate engine or folder observations', async () => {
    failPresence = true;
    await mount();
    expect(row('work')?.textContent).toContain('unconfirmed');
    expect(row('engine')?.textContent?.trim()).toBe('');
    await vi.waitFor(() => expect(row('folders')?.textContent?.trim()).toBe(''));
  });
  it('a failed operation stays a current problem until Activity marks it read through the shared seen cursor', async () => {
    const completedAt = Date.parse('2026-11-11T11:59:00Z');
    outcome = { state: 'failed', historySince: 0, completedAt, message: 'Could not rebuild the index' };
    bindTaskOperation(startTask({ label: 'Rebuild search' }), 'op-failed');
    await mount();
    await vi.waitFor(() => expect(row('work')?.textContent ?? '').toContain('Rebuild search'));
    expect(el!.shadowRoot!.querySelector('[role=status]')?.textContent).not.toBe('Everything is working');
    markSeen(new Date(completedAt + 1_000).toISOString());
    await vi.waitFor(() => expect(row('work')?.textContent ?? '').not.toContain('Rebuild search'));
  });
  it('SH7 a retired failure stays retired when its receipts leave the bounded history; a new failure is reported', async () => {
    const key = (n: number) => `01970000-0000-7000-8000-00000000000${n}`;
    const failed = (minute: number) => ({ state: 'failed', historySince: 0, completedAt: Date.parse(`2026-11-11T11:${minute}:00Z`), message: 'Could not rebuild the index' });
    outcomes = { [key(1)]: failed(50), [key(2)]: { state: 'complete', historySince: 0, completedAt: Date.parse('2026-11-11T11:51:00Z') } };
    await mount();
    const history = ledger();
    const work = () => row('work')?.textContent ?? '';
    await history([[key(1), 'FAILURE']]);
    await vi.waitFor(() => expect(work()).toContain(': failed'), { timeout: 10_000 });
    await history([[key(1), 'FAILURE'], [key(2), 'SUCCESS']]);
    await vi.waitFor(() => expect(work()).not.toContain(': failed'), { timeout: 10_000 });
    await history([]);
    expect(work(), 'retired by a later success').not.toContain(': failed');
    outcomes[key(3)] = failed(52);
    await history([[key(3), 'FAILURE']]);
    await vi.waitFor(() => expect(work(), 'a new failure').toContain(': failed'), { timeout: 10_000 });
    markSeen('2026-11-11T11:53:00.000Z');
    await vi.waitFor(() => expect(work()).not.toContain(': failed'));
    await history([]);
    expect(work(), 'retired by mark-read').not.toContain(': failed');
  });
  it('unknown job counts keep a successful folder response unconfirmed', async () => {
    await mount();
    await readIndexedRoots(async () => json({ items: [{ pathHash: 'folder', collection: 'default', status: 'indexed', walkCompleted: true, inFlightCount: null, failedCount: null }] }));
    await el!.updateComplete;
    expect(row('folders')?.textContent).toContain('unconfirmed');
  });
});
