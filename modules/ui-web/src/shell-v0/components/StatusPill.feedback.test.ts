// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { StatusPill } from './StatusPill.js';
import type { StatusPanel } from './StatusPanel.js';
import { AdvisoryStore, type AdvisoryRecord, type AdvisorySnapshot } from './advisory/AdvisoryStore.js';
import { emitEphemeralToast } from './advisory/ephemeralToast.js';
import { __feedForTest, __feedContactForTest, __resetAiStateForTest, type StatusSnapshot } from '../state/aiStateStore.js';
import { dispatchPluginError, dispatchPluginLoaded } from '../state/pluginLoadEvents.js';
import { sendDesktopNotification } from '../../utils/notify.js';
vi.mock('../../utils/windowFocus.js', () => ({ isWindowFocused: async () => false }));
vi.mock('../../utils/notify.js', () => ({ sendDesktopNotification: vi.fn(async () => {}) }));
let pill: StatusPill;
let store: AdvisoryStore;
const button = () => pill.shadowRoot!.querySelector<HTMLButtonElement>('.trigger')!;
const panel = () => pill.shadowRoot!.querySelector<StatusPanel>('jf-status-panel')!;
async function flush() { await Promise.resolve(); await pill.updateComplete; await panel().updateComplete; await Promise.resolve(); }
beforeEach(async () => {
  vi.useFakeTimers(); vi.clearAllMocks();
  vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 503 })));
  __resetAiStateForTest(); store = new AdvisoryStore(); store.start();
  pill = new StatusPill(); pill.store = store; document.body.append(pill); await flush();
});
afterEach(() => { pill.remove(); store.stop(); __resetAiStateForTest(); vi.useRealTimers(); vi.unstubAllGlobals(); });
function feed(pending: number, state = pending ? 'INDEXING' : 'IDLE') {
  __feedForTest({ status: { worker: { core: { indexState: state, pendingJobs: pending, indexHealthy: true }, enrichment: {} },
    readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } } } as unknown as StatusSnapshot });
}
describe('status feedback ownership', () => {
  it('C1 ST7 local action outcomes stay visible in the closed pill and recoverable in Earlier', async () => {
    emitEphemeralToast({ message: 'Could not copy the URL.', severity: 'error' }); await flush();
    expect(button().getAttribute('aria-label')).toBe('Could not copy the URL.');
    expect(pill.open).toBe(false);
    expect(pill.status.assertive).toBe('Could not copy the URL.');
    await vi.advanceTimersByTimeAsync(6000); await flush();
    expect(button().textContent).not.toContain('Could not copy the URL.');
    expect(panel().model!.sections.earlier.map(row => row.title)).toContain('Could not copy the URL.');
  });
  it('ST8 a witnessed active-to-ready edge completes once; initial ready and reconnect replay stay silent', async () => {
    feed(0); await flush(); expect(store.snapshot().advisories).toHaveLength(0);
    feed(8); await flush(); feed(0); await flush();
    expect(store.snapshot().advisories.filter(row => row.event.classId === 'core.verdict.settled')).toHaveLength(1);
    expect(button().textContent).toContain('Search is up to date');
    feed(0); await flush(); expect(store.snapshot().advisories).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(6000); await flush(); expect(pill.status.model.pill.kind).not.toBe('finished');
  });
  it('undo pauses while hovered or focused, survives a concurrent arrival, and keeps its action identity', async () => {
    const undo = vi.fn();
    emitEphemeralToast({ message: 'Notes removed', actionLabel: 'Undo', onAction: undo }); await flush();
    const action = pill.shadowRoot!.querySelector<HTMLButtonElement>('.transient-action')!;
    action.focus(); action.dispatchEvent(new FocusEvent('focus')); await flush();
    emitEphemeralToast({ message: 'A concurrent action completed' }); await flush();
    expect(pill.shadowRoot!.querySelector('.transient-action')).toBe(action);
    // Nothing more urgent holds the pill: the engine stays in contact (R1 — an unreachable engine would).
    await vi.advanceTimersByTimeAsync(9000); __feedContactForTest(); await flush(); expect(button().textContent).toContain('Notes removed');
    action.click(); await flush(); expect(undo).toHaveBeenCalledTimes(1);
    action.dispatchEvent(new FocusEvent('blur')); await vi.advanceTimersByTimeAsync(6000); await flush();
    expect(button().textContent).toContain('A concurrent action completed');
  });
  it('C6 retained snapshots and late discovery stay silent; live REQUIRES_ACK arrivals retain desktop escalation', async () => {
    const listeners = new Set<(s: AdvisorySnapshot) => void>();
    let snapshot: AdvisorySnapshot = { advisories: [], unreadCount: 0, isConnected: true, lastFrameKind: 'snapshot' };
    const stub = { subscribe: (cb: (s: AdvisorySnapshot) => void) => { listeners.add(cb); cb(snapshot); return () => listeners.delete(cb); } };
    pill.store = stub as unknown as AdvisoryStore; await flush();
    const record = (key: string, liveArrival: boolean): AdvisoryRecord => ({ key, liveArrival, origin: 'stream', acknowledged: false, sourceRenderHint: 'REQUIRES_ACK', event: {
      id: key, classId: 'authorization.pending', occurredAt: new Date().toISOString(), renderHint: 'REQUIRES_ACK', classExtras: { pendingId: key, operationId: 'core.test' }, primaryAction: null, bodyI18nKey: null, diagnosticsLink: null, provenance: null,
    } });
    snapshot = { ...snapshot, advisories: [record('old', false)] }; listeners.forEach(cb => cb(snapshot)); await flush();
    expect(pill.status.assertive).toBe(''); expect(sendDesktopNotification).not.toHaveBeenCalled();
    snapshot = { ...snapshot, lastFrameKind: 'update', advisories: [...snapshot.advisories, record('new', true)] }; listeners.forEach(cb => cb(snapshot)); await flush();
    expect(sendDesktopNotification).toHaveBeenCalledTimes(1);
    listeners.forEach(cb => cb(snapshot)); await flush(); expect(sendDesktopNotification).toHaveBeenCalledTimes(1);
    pill.remove(); document.body.append(pill); await flush(); expect(sendDesktopNotification).toHaveBeenCalledTimes(1);
  });
  it('B3 C6 a hold observed at startup or after a reconnect is silent; a live hold arrival is announced once in plain grammar', async () => {
    const held = (state: string) => __feedForTest({ status: { operating: { indexing: { state, reasons: ['user_paused'], untilEpochMs: null, waitingForResume: true } },
      worker: { core: { indexState: 'INDEXING', pendingJobs: 3, indexedDocuments: 12, searchableDocuments: 12, indexHealthy: true },
        migration: { processingJobsCount: 0 }, enrichment: { backfillMode: 'idle' } },
      readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } } } as unknown as StatusSnapshot });
    // Reload with indexing paused: the first observation lists the hold without an arrival announcement.
    held('PAUSED'); __feedContactForTest(); await flush();
    expect(pill.status.model.sections.needs.map(row => row.id)).toEqual(['indexing-hold']);
    expect(pill.status.assertive).toBe('');
    // The hold is resolved while observed, then arrives again live (paused elsewhere): one announcement.
    held('RUNNING'); __feedContactForTest(); await flush();
    expect(pill.status.model.sections.needs).toEqual([]);
    held('PAUSED'); __feedContactForTest(); await flush();
    expect(pill.status.assertive).toBe('1 item needs you.');
    const announced = pill.status.assertiveKey;
    held('PAUSED'); __feedContactForTest(); await flush();
    expect(pill.status.assertiveKey).toBe(announced);
  });
  it('ST5 plugin errors leave active Problems on recovery and keep history; copy failures have visible feedback', async () => {
    dispatchPluginError({ pluginUrl: 'https://plugin.example/test.js', message: 'Invalid manifest', stack: 'stack details' }); await flush();
    expect(panel().model!.sections.problems.find(row => row.id.startsWith('plugin-error:'))?.detail).toContain('Invalid manifest');
    vi.spyOn(navigator.clipboard, 'writeText').mockRejectedValueOnce(new Error('denied'));
    await pill.status.copyPluginError('https://plugin.example/test.js'); await flush();
    // R1 — the active problem keeps the pill; the copy failure shows beside it, not in its place.
    expect(button().getAttribute('aria-label')).toMatch(/^Problems right now \(1\)/);
    expect(pill.shadowRoot!.querySelector('[data-testid="status-notice"]')!.textContent).toBe('Could not copy plugin error details.');
    dispatchPluginLoaded('https://plugin.example/test.js'); await flush();
    expect(panel().model!.sections.problems.filter(row => row.id.startsWith('plugin-error:'))).toEqual([]);
    expect(panel().model!.sections.earlier.some(row => row.title.includes('Invalid manifest'))).toBe(true);
  });
});
