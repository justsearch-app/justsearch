// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * The transition trackers and live announcers moved out of the retired status bar (StatusDeck)
 * into the always-mounted pill controller (ST8). Pins carried over: 595 §15.1 E1 verdict
 * announcer, 595 §15.3 N1 completion edge, 663 AI-engine edges; plus ST6 durable completion.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { StatusPill } from './StatusPill.js';
import type { StatusPanel } from './StatusPanel.js';
import { AdvisoryStore } from './advisory/AdvisoryStore.js';
import { emitEphemeralToast } from './advisory/ephemeralToast.js';
import { __feedForTest, __resetAiStateForTest, type AiState, type StatusSnapshot } from '../state/aiStateStore.js';
import { __resetTasksForTest, startTask, bindTaskOperation, getTask, updateTaskProgress } from '../substrates/tasks/index.js';
import { registerStatusBarItem, __resetForTest as resetRegistry } from '../commands/StatusBarRegistry.js';
import { CORE_PROVENANCE } from '../primitives/provenance.js';
import { sendDesktopNotification } from '../../utils/notify.js';
import { isWindowFocused } from '../../utils/windowFocus.js';
import { AuthorizationHost } from './AuthorizationHost.js';
import { requestAuthorization } from '../operations/authorizationBroker.js';
vi.mock('../../utils/windowFocus.js', () => ({ isWindowFocused: vi.fn(async () => false) }));
vi.mock('../../utils/notify.js', () => ({ sendDesktopNotification: vi.fn(async () => {}) }));

let pill: StatusPill;
let store: AdvisoryStore;
const panel = () => pill.shadowRoot!.querySelector<StatusPanel>('jf-status-panel')!;
const notice = (id: string) => pill.shadowRoot!.querySelector(`[data-testid="${id}"]`);
async function flush() { for (let i = 0; i < 4; i++) await Promise.resolve(); await pill.updateComplete; await panel().updateComplete; }
const settledToasts = () => store.snapshot().advisories.filter(r => r.origin === 'local').map(r => r.toast!.message);
function ai(overrides: Partial<AiState> = {}): AiState {
  const now = Date.now();
  return { phase: 'connected', connection: { reachable: true, lastSuccessMs: now, lastContactMs: now, consecutiveFailures: 0 },
    verdict: { kind: 'operational', severity: 'ok', reasons: [] }, stability: { kind: 'settled' },
    aiEngine: { kind: 'offline', stability: { kind: 'settled' }, installFailure: null },
    activity: { state: 'idle', shapeId: null, startedAtMs: null, canCancel: false, cancel: null },
    status: { worker: { core: { indexState: 'IDLE', pendingJobs: 0 }, enrichment: {} } },
    indexingStatusLive: true, snapshotLive: true, episodeMaxPendingJobs: 0, enrichSettleSamples: [], ...overrides,
  } as AiState;
}
/** Drive the controller with one observed state (the store's own derivation is pinned elsewhere). */
async function observe(state: Partial<AiState>) { pill.status.ai = ai(state); pill.status.refresh(); await flush(); }
const verdict = (kind: string, severity = 'info', reasons: string[] = []) => ({ verdict: { kind, severity, reasons } as AiState['verdict'] });
const engine = (kind: string) => ({ aiEngine: { kind, stability: { kind: 'settled' }, installFailure: null } as unknown as AiState['aiEngine'] });

beforeEach(async () => {
  vi.clearAllMocks();
  vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 503 })));
  __resetAiStateForTest(); __resetTasksForTest(); resetRegistry();
  store = new AdvisoryStore(); store.start();
  pill = new StatusPill(); pill.store = store; document.body.append(pill); await flush();
});
afterEach(() => { pill.remove(); store.stop(); __resetAiStateForTest(); __resetTasksForTest(); resetRegistry(); vi.unstubAllGlobals(); });

describe('verdict and AI-engine announcers (595 §15.1 E1, moved from the status bar)', () => {
  it('mirrors the verdict politely and an error verdict assertively, never as "All systems operational"', async () => {
    await observe(verdict('operational', 'ok'));
    // FC10a — in the pill's own words.
    expect(pill.status.model.pill.label).toBe('Up to date');
    expect(notice('verdict-announcer')!.getAttribute('live')).toBe('status');
    expect(notice('verdict-announcer')!.textContent).toBe('Up to date');
    expect(pill.shadowRoot!.textContent).not.toContain('All systems operational');
    // FC10 — spoken as the pill shows it: checking while contact is lost within the grace, then
    // assertively that the engine cannot be reached.
    vi.useFakeTimers({ toFake: ['Date'] });
    try {
      const lost = { connection: { reachable: false, lastSuccessMs: Date.now(), lastContactMs: Date.now(), consecutiveFailures: 1 } } as Partial<AiState>;
      await observe({ ...verdict('unreachable', 'error'), ...lost });
      expect(notice('verdict-announcer')!.getAttribute('live')).toBe('status');
      expect(notice('verdict-announcer')!.textContent).toBe('Checking search status');
      vi.setSystemTime(Date.now() + 11_000);
      await observe({ ...verdict('unreachable', 'error'), ...lost });
      expect(pill.status.model.pill.kind).toBe('unreachable');
      expect(notice('verdict-announcer')!.getAttribute('live')).toBe('alert');
      expect(notice('verdict-announcer')!.textContent).toBe("Can't reach the engine");
    } finally { vi.useRealTimers(); }
  });
  it('speaks no AI-engine headline the pill never shows (FC10a)', async () => {
    await observe(engine('online'));
    expect(notice('ai-engine-announcer')).toBeNull();
    expect(notice('verdict-announcer')!.textContent).toBe(pill.status.model.pill.label);
  });
});

describe('completion edges (595 §15.3 N1, 663 AI engine; ST8, C6)', () => {
  it('a witnessed transition completes once, through an intermediate checking stop', async () => {
    await observe(verdict('transitioning', 'busy', ['rebuilding']));
    expect(settledToasts()).toEqual([]);
    await observe(verdict('checking'));
    expect(settledToasts()).toEqual([]);
    await observe(verdict('operational', 'ok'));
    expect(settledToasts()).toEqual(['Search is up to date']);
    expect(pill.status.model.pill).toMatchObject({ kind: 'finished', label: 'Search is up to date' });
    // ST8 — a polite live announcement, whatever the layout (the pill is the only mount).
    expect(pill.status.polite).toBe('Search is up to date');
    expect(pill.shadowRoot!.querySelector('span[aria-live="polite"]')!.textContent).toBe('Search is up to date');
    await observe(verdict('degraded', 'warn', ['x']));
    await observe(verdict('operational', 'ok'));
    expect(settledToasts()).toEqual(['Search is up to date']);
  });
  it('first load and reconnect never announce a completion (C6)', async () => {
    await observe(verdict('connecting'));
    await observe(verdict('operational', 'ok'));
    await observe(verdict('unreachable', 'error'));
    await observe(verdict('operational', 'ok'));
    expect(settledToasts()).toEqual([]);
    expect(pill.status.model.pill.kind).toBe('calm');
  });
  it('an indexing transition and its ready edge announce one completion, not two', async () => {
    const indexing = { status: { worker: { core: { indexState: 'INDEXING', pendingJobs: 5 }, enrichment: {} } } as AiState['status'] };
    await observe({ ...indexing, ...verdict('transitioning', 'busy', ['index.indexing']) });
    await observe(verdict('operational', 'ok'));
    expect(settledToasts()).toEqual(['Search is up to date']);
  });
  it.each([['online', 'AI install complete'], ['install_failed', 'AI install failed']])(
    'installing → %s fires exactly one outcome', async (terminal, message) => {
      await observe(engine('online'));
      expect(settledToasts()).toEqual([]);
      await observe(engine('installing'));
      await observe(engine(terminal)); await observe(engine(terminal));
      expect(settledToasts()).toEqual([message]);
    });
});

describe('own-action receipts and desktop escalation (C1, ST14)', () => {
  it.each([['warning', 'assertive'], ['success', 'polite'], ['info', 'polite']] as const)('a local %s receipt announces %s', async (severity, region) => {
    emitEphemeralToast({ message: `Receipt ${severity}`, severity }); await flush();
    expect(region === 'assertive' ? pill.status.assertive : pill.status.polite).toBe(`Receipt ${severity}`);
  });
  it('only a live agent arrival while unfocused reaches the desktop', async () => {
    // DE3 — the host's live arrival decides escalation; a rediscovered request (reload, reconnect) never does.
    const host = new AuthorizationHost(); document.body.append(host);
    pill.authorizationHost = () => host; await flush();
    const ask = async (pendingId: string, live: boolean) => {
      void requestAuthorization({ pendingId, operationId: 'core.test', gateBehavior: 'INLINE_CONFIRM', arrival: 'needs-you', live });
      await host.updateComplete; pill.status.refresh(); await flush();
    };
    try {
      await ask('rediscovered', false);
      vi.mocked(isWindowFocused).mockResolvedValueOnce(true);
      await ask('focused', true);
      expect(sendDesktopNotification).not.toHaveBeenCalled();
      await ask('background', true);
      expect(sendDesktopNotification).toHaveBeenCalledTimes(1);
    } finally { host.remove(); }
  });
});

describe('ST6 durable operations finish with the backend, not on invocation return', () => {
  it('keeps the invocation task running until the operation history reports complete', async () => {
    let outcome: Record<string, unknown> = { state: 'running', historySince: 0 };
    vi.stubGlobal('fetch', vi.fn(async (url: string) => String(url).includes('/api/operation-history/op1')
      ? Response.json(outcome) : new Response('{}', { status: 503 })));
    const id = startTask({ label: 'Rebuild search' });
    bindTaskOperation(id, 'op1');
    const status = (pending: number) => __feedForTest({ status: { worker: { core: { indexState: 'IDLE', pendingJobs: pending }, enrichment: {} } } as unknown as StatusSnapshot });
    status(0); await vi.waitFor(() => expect(panel().model!.sections.running.map(r => r.id)).toContain('operation:op1'));
    expect(getTask(id)!.status).toBe('running');
    expect(panel().model!.sections.earlier.map(r => r.id)).not.toContain(`task:${id}`);
    outcome = { state: 'complete', historySince: 0 };
    await new Promise(resolve => setTimeout(resolve, 2)); status(0);
    await vi.waitFor(() => expect(getTask(id)!.status).toBe('succeeded'));
    expect(panel().model!.sections.earlier.map(r => r.id)).toContain('operation:op1');
    expect(settledToasts()).toContain('Rebuild search finished');
  });
  it('complete-with-gaps stays a decision and never finishes the task', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => String(url).includes('/api/operation-history/op2')
      ? Response.json({ state: 'complete', phase: 'awaiting_acceptance', historySince: 0 }) : new Response('{}', { status: 503 })));
    const id = startTask({ label: 'Rebuild search' });
    bindTaskOperation(id, 'op2');
    __feedForTest({ status: { worker: { core: { indexState: 'IDLE', pendingJobs: 0 }, enrichment: {} } } as unknown as StatusSnapshot });
    await vi.waitFor(() => expect(panel().model!.sections.needs.map(r => r.id)).toContain('operation:op2'));
    expect(getTask(id)!.status).toBe('running');
    expect(settledToasts()).toEqual([]);
  });
  it('a response arriving after teardown cannot write into the remounted pill', async () => {
    let release: (r: Response) => void = () => {};
    vi.stubGlobal('fetch', vi.fn((url: string) => String(url).includes('/api/operation-history/op3')
      ? new Promise<Response>(resolve => { release = resolve; }) : Promise.resolve(new Response('{}', { status: 503 }))));
    const id = startTask({ label: 'Rebuild search' });
    bindTaskOperation(id, 'op3');
    __feedForTest({ status: { worker: { core: { indexState: 'IDLE', pendingJobs: 0 }, enrichment: {} } } as unknown as StatusSnapshot });
    await vi.waitFor(() => expect(vi.mocked(fetch).mock.calls.some(c => String(c[0]).includes('op3'))).toBe(true));
    pill.remove(); document.body.append(pill); await flush();
    release(Response.json({ state: 'complete', historySince: 0 })); await flush();
    expect(getTask(id)!.status).toBe('running');
  });
});

describe('ST1/ST9 calm pill', () => {
  it('keeps the "Up to date" accessible name on its dot and keeps plugin items in the panel', async () => {
    await observe({});
    const trigger = pill.shadowRoot!.querySelector('.trigger')!;
    expect(trigger.getAttribute('aria-label')).toBe('Up to date');
    expect(trigger.querySelector('.label')).toBeNull();
    expect(trigger.getAttribute('title')).toBe('Up to date');
    registerStatusBarItem({ id: 'plugin.timer', source: 'plugin', provenance: CORE_PROVENANCE, priority: 1, position: 'right', render: () => 'Timer' });
    await flush();
    expect(trigger.querySelector('[aria-label="Plugin status items"]')).toBeNull();
    expect(panel().plugins.map(item => item.id)).toEqual(['plugin.timer']);
    pill.pluginsVisible = false; await flush();
    expect(trigger.querySelector('[aria-label="Plugin status items"]')).toBeNull();
    expect(trigger.getAttribute('aria-label')).toBe('Up to date');
  });
});

describe('task rows and announcements moved from the task tray and status bar', () => {
  it('a cancellable running task shows its measured progress and cancels through the substrate', async () => {
    const cancel = vi.fn();
    const id = startTask({ label: 'Export conversation', cancel });
    updateTaskProgress(id, 0.5); await flush();
    expect(panel().model!.sections.running.find(r => r.id === `task:${id}`)!.percent).toBe(50);
    pill.open = true; await flush();
    [...panel().shadowRoot!.querySelectorAll<HTMLButtonElement>(`article[data-fact="task:${id}"] button`)].find(b => b.textContent === 'Cancel')!.click();
    await flush();
    expect(cancel).toHaveBeenCalledTimes(1); expect(getTask(id)!.status).toBe('cancelled');
  });
  it('announces retry waiting with its backlog and deadline', async () => {
    await observe({ status: { worker: { core: { indexState: 'WAITING', pendingJobs: 2, indexHealthy: true },
      failure: { nextRetryAtMs: Date.now() + 60_000 } } } as AiState['status'] });
    const text = notice('indexing-announcer')!.textContent!;
    expect(text).toContain('Waiting to retry'); expect(text).toContain('2 files waiting'); expect(text).toContain('Next retry');
  });
});

