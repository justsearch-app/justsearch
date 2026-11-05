// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * DE5 — only live events leave the app, only while it is in the background, in the words the panel's
 * first line uses; the badge counts Needs you; a clicked notification opens the panel on its item.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { StatusPill } from './StatusPill.js';
import type { StatusPanel } from './StatusPanel.js';
import { AuthorizationHost } from './AuthorizationHost.js';
import { AdvisoryStore } from './advisory/AdvisoryStore.js';
import { emitEphemeralToast } from './advisory/ephemeralToast.js';
import { requestAuthorization, withdrawAuthorization, type AuthorizationPrompt } from '../operations/authorizationBroker.js';
import { __resetAiStateForTest, __feedForTest, type AiState, type StatusSnapshot } from '../state/aiStateStore.js';
import { __resetTasksForTest, startTask, bindTaskOperation } from '../substrates/tasks/index.js';
import { __resetUserStateForTest } from '../state/UserStateDocument.js';
import { setNotifications } from '../state/notificationsState.js';
import { ModalityController, __resetModalityForTest } from '../primitives/modality.js';
import { sendDesktopNotification } from '../../utils/notify.js';
import { setTaskbarBadge } from '../../utils/taskbarBadge.js';
import { isWindowFocused } from '../../utils/windowFocus.js';
import { installNoticeActivationBridge } from '../router/tauriBridge.js';
vi.mock('../../utils/windowFocus.js', () => ({ isWindowFocused: vi.fn(async () => false) }));
vi.mock('../../utils/notify.js', () => ({ sendDesktopNotification: vi.fn(async () => {}) }));
vi.mock('../../utils/taskbarBadge.js', () => ({ setTaskbarBadge: vi.fn(async () => {}) }));
vi.mock('../router/tauriBridge.js', () => ({ installNoticeActivationBridge: vi.fn(() => () => {}) }));

let pill: StatusPill;
let host: AuthorizationHost;
let store: AdvisoryStore;
let elapsed = 0;
const panel = () => pill.shadowRoot!.querySelector<StatusPanel>('jf-status-panel')!;
const root = () => panel().shadowRoot!;
async function flush() {
  for (let i = 0; i < 4; i++) await Promise.resolve();
  await host.updateComplete; pill.status.refresh(); await pill.updateComplete; await panel().updateComplete;
  await Promise.resolve(); await panel().updateComplete;
}
function agent(id: string, extra: Partial<AuthorizationPrompt> = {}) {
  void requestAuthorization({ pendingId: id, operationId: 'core.ingest-files', gateBehavior: 'INLINE_CONFIRM', riskTier: 'MEDIUM',
    argsSummary: '/home/me/Downloads/secret.txt', requestedBy: 'Claude Desktop', arrival: 'needs-you', live: true,
    askedAt: new Date(Date.now() - 60_000).toISOString(), expiresAt: new Date(Date.now() + 4 * 60_000).toISOString(),
    reject: async () => {}, refresh: async () => ({ operationId: 'core.ingest-files', argsSummary: '/home/me/Downloads/secret.txt', requestedBy: 'Claude Desktop' }), ...extra });
}
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
async function observe(state: Partial<AiState>) { pill.status.ai = ai(state); pill.status.refresh(); await flush(); }
const verdict = (kind: string) => ({ verdict: { kind, severity: 'info', reasons: [] } as unknown as AiState['verdict'] });
const engine = (kind: string) => ({ aiEngine: { kind, stability: { kind: 'settled' }, installFailure: null } as unknown as AiState['aiEngine'] });
const sent = () => vi.mocked(sendDesktopNotification).mock.calls.map(call => call[0]);
const lastBadge = () => vi.mocked(setTaskbarBadge).mock.calls.at(-1)?.[0];
/** The shell reports a clicked notification. */
const clickNotification = (id: string) => vi.mocked(installNoticeActivationBridge).mock.calls.at(-1)![0](id);
/** An operation the engine reports through the status cadence, starting in `first`. */
function operation(key: string, label: string, first: Record<string, unknown>) {
  let outcome = first;
  vi.stubGlobal('fetch', vi.fn(async (url: string) => String(url).includes(`/api/operation-history/${key}`)
    ? Response.json(outcome) : new Response('{}', { status: 503 })));
  const feed = () => __feedForTest({ status: { worker: { core: { indexState: 'IDLE', pendingJobs: 0 }, enrichment: {} } } as unknown as StatusSnapshot });
  bindTaskOperation(startTask({ label }), key); feed();
  return async (next?: Record<string, unknown>) => {
    if (next) outcome = next;
    await new Promise(resolve => setTimeout(resolve, 2)); feed();
    await vi.waitFor(() => expect(Object.values(panel().model!.sections).flat().find(row => row.id === `operation:${key}`)?.stale).toBe(false));
    await flush();
  };
}

beforeEach(async () => {
  vi.clearAllMocks();
  elapsed = 0;
  const now = Date.now.bind(Date);
  vi.spyOn(Date, 'now').mockImplementation(() => now() + elapsed);
  vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 503 })));
  __resetAiStateForTest(); __resetTasksForTest(); __resetUserStateForTest(); __resetModalityForTest();
  store = new AdvisoryStore(); store.start();
  host = new AuthorizationHost(); document.body.append(host);
  host.addEventListener('status-requests-changed', () => pill.status.refresh());
  pill = new StatusPill(); pill.store = store; pill.authorizationHost = () => host; document.body.append(pill); await flush();
});
afterEach(() => {
  pill.remove(); host.remove(); store.stop();
  __resetAiStateForTest(); __resetTasksForTest(); __resetUserStateForTest(); __resetModalityForTest(); vi.unstubAllGlobals(); vi.restoreAllMocks();
});

describe('DE5 an agent request', () => {
  it('in the background notifies once, saying who, what and the deadline, never its arguments', async () => {
    agent('a1'); await flush(); pill.status.refresh(); await flush();
    expect(sent()).toEqual([{ id: 'authorization:a1', title: 'Claude Desktop requests Ingest Files', body: 'Denied automatically in 4 min', sound: false }]);
    expect(JSON.stringify(sent())).not.toContain('secret.txt');
  });
  it('each request arriving together notifies, so each click lands on its own review', async () => {
    agent('b1'); agent('b2'); await flush();
    expect(sent().map(notice => notice.id).sort()).toEqual(['authorization:b1', 'authorization:b2']);
  });
  it('with the window focused stays in the app: the pill and its announcement are the notice', async () => {
    vi.mocked(isWindowFocused).mockResolvedValue(true);
    try {
      agent('c1'); await flush();
      expect(sendDesktopNotification).not.toHaveBeenCalled();
      expect(pill.status.model.pill).toMatchObject({ kind: 'needs', count: 1 });
      expect(pill.status.assertive).toContain('Claude Desktop');
    } finally { vi.mocked(isWindowFocused).mockResolvedValue(false); }
  });
  it('Off sends nothing while the pill, the announcement and the badge stay', async () => {
    setNotifications('off');
    agent('d1'); await flush();
    expect(sendDesktopNotification).not.toHaveBeenCalled();
    expect(pill.status.assertive).toContain('Claude Desktop');
    expect(lastBadge()).toBe(1);
  });
  it('On with sound asks for the platform sound', async () => {
    setNotifications('sound');
    agent('e1'); await flush();
    expect(sent()).toMatchObject([{ id: 'authorization:e1', sound: true }]);
  });
  it('C6 a request rediscovered after a reload notifies nothing and still counts as needing you', async () => {
    agent('f1', { live: false }); await flush();
    expect(sendDesktopNotification).not.toHaveBeenCalled();
    expect(lastBadge()).toBe(1);
  });
  it('C10 a teardown while the focus check is in flight sends nothing, and a remount does not repeat it', async () => {
    let answer: (focused: boolean) => void = () => {};
    vi.mocked(isWindowFocused).mockImplementationOnce(() => new Promise(resolve => { answer = resolve; }));
    agent('g1'); await flush();
    pill.remove(); answer(false); await flush();
    expect(sendDesktopNotification).not.toHaveBeenCalled();
    document.body.append(pill); await flush();
    expect(sendDesktopNotification).not.toHaveBeenCalled();
  });
});

describe('DE5 long work', () => {
  it('finishing notifies once, by its row', async () => {
    const advance = operation('op1', 'Rebuild search', { state: 'running', historySince: 0 });
    await advance();
    expect(sendDesktopNotification).not.toHaveBeenCalled();
    elapsed += 60_000;
    await advance({ state: 'complete', historySince: 0 });
    await advance();
    expect(sent()).toEqual([{ id: 'operation:op1', title: 'Rebuild search finished', sound: false }]);
  });
  it('failing notifies once, leaving the failure message in the panel', async () => {
    const advance = operation('op2', 'Rebuild search', { state: 'running', historySince: 0 });
    await advance();
    await advance({ state: 'failed', historySince: 0, message: 'Could not read /home/me/secret.txt' });
    await advance();
    expect(sent()).toEqual([{ id: 'operation:op2', title: 'Rebuild search failed', body: 'Open JustSearch to see what to do next.', sound: false }]);
  });
  it.each([['complete'], ['failed']])('C6 a %s receipt discovered late is silent', async (state) => {
    await operation('op3', 'Rebuild search', { state, historySince: 0 })();
    expect(sendDesktopNotification).not.toHaveBeenCalled();
  });
  it('complete with gaps is a decision, not a completion', async () => {
    const advance = operation('op4', 'Rebuild search', { state: 'running', historySince: 0 });
    await advance();
    await advance({ state: 'complete', phase: 'awaiting_acceptance', historySince: 0 });
    expect(sendDesktopNotification).not.toHaveBeenCalled();
  });
  it('a witnessed transition settling notifies; a first load in the settled state does not', async () => {
    await observe(verdict('operational'));
    expect(sendDesktopNotification).not.toHaveBeenCalled();
    await observe(verdict('transitioning'));
    elapsed += 60_000;
    await observe(verdict('operational'));
    expect(sent()).toEqual([{ id: 'Search is up to date', title: 'Search is up to date', sound: false }]);
  });
  it('an AI install completing or failing notifies; finding one already online does not', async () => {
    await observe(engine('online'));
    expect(sendDesktopNotification).not.toHaveBeenCalled();
    await observe(engine('installing')); elapsed += 60_000; await observe(engine('online'));
    await observe(engine('installing')); await observe(engine('install_failed'));
    expect(sent().map(notice => notice.title)).toEqual(['AI install complete', 'AI install failed']);
  });
  it('with the window focused the pill is enough', async () => {
    vi.mocked(isWindowFocused).mockResolvedValue(true);
    try {
      await observe(verdict('transitioning')); await observe(verdict('operational'));
      expect(pill.status.model.pill).toMatchObject({ kind: 'finished', label: 'Search is up to date' });
      expect(sendDesktopNotification).not.toHaveBeenCalled();
    } finally { vi.mocked(isWindowFocused).mockResolvedValue(false); }
  });
});

describe('DE5 short work stays inside', () => {
  it('59 seconds stays in the pill and Earlier; a later 60 second run notifies once', async () => {
    await observe(verdict('transitioning'));
    elapsed += 59_000;
    await observe(verdict('operational'));
    expect(sendDesktopNotification).not.toHaveBeenCalled();
    expect(pill.status.model.pill).toMatchObject({ kind: 'finished', label: 'Search is up to date' });
    expect(panel().model!.sections.earlier.some(row => row.title === 'Search is up to date')).toBe(true);
    await observe(verdict('transitioning'));
    elapsed += 60_000;
    await observe(verdict('operational'));
    await flush();
    expect(sent()).toEqual([{ id: 'Search is up to date', title: 'Search is up to date', sound: false }]);
  });
  it('indexing durations reset between runs, so repeated short runs stay silent', async () => {
    const indexing = { status: { worker: { core: { indexState: 'INDEXING', pendingJobs: 5 }, enrichment: {} } } as AiState['status'] };
    for (let run = 0; run < 2; run++) {
      await observe(indexing);
      elapsed += 30_000;
      await observe({});
      expect(pill.status.model.pill).toMatchObject({ kind: 'finished', label: 'Search is up to date' });
      expect(sendDesktopNotification).not.toHaveBeenCalled();
    }
    await observe(indexing);
    elapsed += 60_000;
    await observe({});
    expect(sent()).toEqual([{ id: 'Search is up to date', title: 'Search is up to date', sound: false }]);
  });
  it('a short operation completes without notifying', async () => {
    const advance = operation('short-op', 'Rebuild search', { state: 'running', historySince: 0 });
    await advance();
    await advance({ state: 'complete', historySince: 0 });
    expect(sendDesktopNotification).not.toHaveBeenCalled();
    expect(pill.status.model.pill).toMatchObject({ kind: 'finished', label: 'Rebuild search finished' });
  });
  it('a short install completes silently, but a short failure notifies', async () => {
    await observe(engine('installing')); await observe(engine('online'));
    expect(sendDesktopNotification).not.toHaveBeenCalled();
    expect(pill.status.model.pill).toMatchObject({ kind: 'finished', label: 'AI install complete' });
    await observe(engine('installing')); await observe(engine('install_failed'));
    expect(sent().map(notice => notice.title)).toEqual(['AI install failed']);
  });
});

describe('DE5 the taskbar badge', () => {
  it('reports the count it starts with, so a count left before a reload can clear', () => {
    expect(vi.mocked(setTaskbarBadge).mock.calls).toEqual([[0]]);
  });
  it('follows Needs you live, is sent only on a change, and clears at zero', async () => {
    agent('h1'); await flush(); agent('h2'); await flush();
    pill.status.refresh(); await flush();
    expect(vi.mocked(setTaskbarBadge).mock.calls.map(call => call[0])).toEqual([0, 1, 2]);
    withdrawAuthorization('h1'); await flush();
    withdrawAuthorization('h2'); await flush();
    expect(vi.mocked(setTaskbarBadge).mock.calls.map(call => call[0])).toEqual([0, 1, 2, 1, 0]);
  });
  it('C6 retained Earlier records do not count', async () => {
    emitEphemeralToast({ message: 'Search is up to date', classId: 'core.verdict.settled' }); await flush();
    expect(panel().model!.sections.earlier.length).toBeGreaterThan(0);
    expect(vi.mocked(setTaskbarBadge).mock.calls).toEqual([[0]]);
  });
});

describe('DE5 clicking a notification', () => {
  it('opens the review of the agent request', async () => {
    agent('i1'); await flush();
    clickNotification('authorization:i1'); await flush(); await flush();
    expect(pill.open).toBe(true);
    expect(root().querySelector('[data-testid="status-review"]')!.getAttribute('data-fact')).toBe('authorization:i1');
  });
  it('opens the panel on a failed operation, with focus on its row', async () => {
    const advance = operation('op5', 'Rebuild search', { state: 'running', historySince: 0 });
    await advance();
    await advance({ state: 'failed', historySince: 0 });
    clickNotification('operation:op5'); await flush(); await flush();
    expect(pill.open).toBe(true);
    expect(root().activeElement?.closest('article')?.getAttribute('data-fact')).toBe('operation:op5');
  });
  it('finds an event listed only by its words in Earlier', async () => {
    emitEphemeralToast({ message: 'Search is up to date', classId: 'core.verdict.settled' }); await flush();
    clickNotification('Search is up to date'); await flush(); await flush();
    expect(pill.open).toBe(true);
    expect(root().activeElement?.closest('article')?.textContent).toContain('Search is up to date');
  });
  it('a request answered since opens the panel on what is true now', async () => {
    clickNotification('authorization:gone'); await flush(); await flush();
    expect(pill.open).toBe(true);
    expect(root().querySelector('[data-testid="status-review"]')).toBeNull();
  });
  it('never opens over a must-stop dialog', async () => {
    agent('j1'); await flush();
    new ModalityController(pill).enter();
    clickNotification('authorization:j1'); await flush(); await flush();
    expect(pill.open).toBe(false);
  });
  it('C10 a remount listens once: the old listener is stopped', async () => {
    const stop = vi.fn();
    vi.mocked(installNoticeActivationBridge).mockReturnValueOnce(stop);
    pill.remove(); document.body.append(pill); await flush();
    expect(stop).not.toHaveBeenCalled();
    pill.remove();
    expect(stop).toHaveBeenCalledTimes(1);
    document.body.append(pill); await flush();
  });
});
