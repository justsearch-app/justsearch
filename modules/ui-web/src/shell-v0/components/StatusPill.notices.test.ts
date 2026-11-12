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
import { ModalityController, __resetModalityForTest, modalOwnsFocus } from '../primitives/modality.js';
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
const engine = (kind: string, startedAtEpochMs = 1000) => ({ aiEngine: { kind, stability: { kind: 'settled' }, installFailure: null } as unknown as AiState['aiEngine'],
  installStatus: { startedAtEpochMs } as unknown as AiState['installStatus'] });
/** The engine's status, with the time its indexing state last changed. */
const idleSince = (since: string): Partial<AiState> => ({ status: { worker: { core: { indexState: 'IDLE', pendingJobs: 0 }, enrichment: {} },
  operating: { indexing: { since } } } as unknown as AiState['status'] });
const SINCE = '2026-11-06T10:00:00Z';
const sent = () => vi.mocked(sendDesktopNotification).mock.calls.map(call => call[0]);
const noticeGone = () => root().querySelector('[data-testid="status-notice-gone"]')?.textContent;
const lastBadge = () => vi.mocked(setTaskbarBadge).mock.calls.at(-1)?.[0];
/** The shell reports a clicked notification. */
const clickNotification = (id: string) => vi.mocked(installNoticeActivationBridge).mock.calls.at(-1)![0](id);
/** The next focus check waits until answered. */
function pendingFocusCheck() {
  let answer: (focused: boolean) => void = () => {};
  vi.mocked(isWindowFocused).mockImplementationOnce(() => new Promise(resolve => { answer = resolve; }));
  return { answer: (focused: boolean) => answer(focused) };
}
/** The engine out of contact: what a reader of the status sees between losing and regaining it. */
const disconnected = (): Partial<AiState> => ({ phase: 'stale', snapshotLive: false, indexingStatusLive: false,
  connection: { reachable: false, lastSuccessMs: Date.now() - 60_000, lastContactMs: Date.now() - 60_000, consecutiveFailures: 1 } });
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
  it('a background result older than focus returning to the window sends nothing', async () => {
    const check = pendingFocusCheck();
    agent('k1'); await flush();
    vi.mocked(isWindowFocused).mockResolvedValue(true);
    try {
      window.dispatchEvent(new Event('focus'));
      check.answer(false); await flush();
      expect(sendDesktopNotification).not.toHaveBeenCalled();
      expect(pill.status.model.pill).toMatchObject({ kind: 'needs', count: 1 });
    } finally { vi.mocked(isWindowFocused).mockResolvedValue(false); }
  });
  it('a focused result older than the window losing focus is read again, so the request still notifies', async () => {
    const check = pendingFocusCheck();
    agent('k2'); await flush();
    window.dispatchEvent(new Event('blur'));
    check.answer(true); await flush();
    expect(sent().map(notice => notice.id)).toEqual(['authorization:k2']);
  });
  it('focus changing again during the second read keeps reading until a result is current', async () => {
    const first = pendingFocusCheck();
    agent('k3'); await flush();
    const second = pendingFocusCheck();
    window.dispatchEvent(new Event('focus'));
    first.answer(false); await flush();
    vi.mocked(isWindowFocused).mockResolvedValue(true);
    try {
      window.dispatchEvent(new Event('focus'));
      second.answer(false); await flush();
      expect(sendDesktopNotification).not.toHaveBeenCalled();
    } finally { vi.mocked(isWindowFocused).mockResolvedValue(false); }
  });
  it('C10 detaching and reattaching while the focus check waits cancels the old decision', async () => {
    const check = pendingFocusCheck();
    agent('g2'); await flush();
    pill.remove(); document.body.append(pill); await flush();
    check.answer(false); await flush();
    expect(sendDesktopNotification).not.toHaveBeenCalled();
    agent('g3'); await flush();
    expect(sent().map(notice => notice.id)).toEqual(['authorization:g3']);
  });
  it('C10 a remount watches focus once: the old watch is stopped', async () => {
    const add = vi.spyOn(window, 'addEventListener');
    const remove = vi.spyOn(window, 'removeEventListener');
    const focusListeners = (spy: typeof add | typeof remove) => spy.mock.calls.filter(call => call[0] === 'focus').map(call => call[1]);
    pill.remove(); document.body.append(pill); await flush();
    const [attached] = focusListeners(add);
    pill.remove();
    expect(focusListeners(remove)).toContain(attached);
    expect(focusListeners(add)).toHaveLength(1);
    document.body.append(pill); await flush();
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
    await observe({ ...verdict('operational'), ...idleSince(SINCE) });
    expect(sent()).toEqual([{ id: `Search is up to date@${SINCE}#0`, title: 'Search is up to date', sound: false }]);
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

describe('C6 work ending across a gap in observation', () => {
  it('a transition finished while disconnected is silent, and Earlier still lists it', async () => {
    await observe(verdict('operational'));
    await observe(verdict('transitioning'));
    elapsed += 60_000;
    await observe(disconnected());
    await observe(verdict('operational'));
    expect(sendDesktopNotification).not.toHaveBeenCalled();
    expect(panel().model!.sections.earlier.some(row => row.title === 'Search is up to date')).toBe(true);
  });
  it('indexing finished while disconnected is silent', async () => {
    const indexing = { status: { worker: { core: { indexState: 'INDEXING', pendingJobs: 5 }, enrichment: {} } } as AiState['status'] };
    await observe(indexing);
    elapsed += 60_000;
    await observe(disconnected());
    await observe({});
    expect(sendDesktopNotification).not.toHaveBeenCalled();
  });
  it('an install finished while disconnected is silent', async () => {
    await observe(engine('installing'));
    elapsed += 60_000;
    await observe(disconnected());
    await observe(engine('online'));
    expect(sendDesktopNotification).not.toHaveBeenCalled();
  });
  it('an install that failed while disconnected is silent, its end was not observed either', async () => {
    await observe(engine('installing'));
    await observe(disconnected());
    await observe(engine('install_failed'));
    expect(sendDesktopNotification).not.toHaveBeenCalled();
  });
  it.each([['complete'], ['failed']])('an operation %s while disconnected is silent', async (state) => {
    const advance = operation('gap-op', 'Rebuild search', { state: 'running', historySince: 0 });
    await advance();
    elapsed += 60_000;
    await observe(disconnected());
    await advance({ state, historySince: 0 });
    await advance();
    expect(sendDesktopNotification).not.toHaveBeenCalled();
  });
  it('an operation ending behind a failed read is silent', async () => {
    const advance = operation('gap-read', 'Rebuild search', { state: 'running', historySince: 0 });
    await advance();
    elapsed += 60_000;
    const history = vi.mocked(fetch).getMockImplementation()!;
    vi.mocked(fetch).mockImplementation(async () => new Response('{}', { status: 503 }));
    __feedForTest({ status: { worker: { core: { indexState: 'IDLE', pendingJobs: 0 }, enrichment: {} } } as unknown as StatusSnapshot });
    await vi.waitFor(() => expect(Object.values(panel().model!.sections).flat().find(row => row.id === 'operation:gap-read')?.stale).toBe(true));
    vi.mocked(fetch).mockImplementation(history);
    await advance({ state: 'complete', historySince: 0 });
    await advance();
    expect(sendDesktopNotification).not.toHaveBeenCalled();
  });
  it('an operation ending across a detach is silent', async () => {
    const advance = operation('gap-detach', 'Rebuild search', { state: 'running', historySince: 0 });
    await advance();
    elapsed += 60_000;
    pill.remove();
    // The operation ends while this window is detached; the first read after return sees it complete.
    vi.stubGlobal('fetch', vi.fn(async (url: string) => String(url).includes('/api/operation-history/gap-detach')
      ? Response.json({ state: 'complete', historySince: 0 }) : new Response('{}', { status: 503 })));
    document.body.append(pill); await flush();
    await advance();
    await advance();
    expect(Object.values(panel().model!.sections).flat().find(row => row.id === 'operation:gap-detach')?.detail).not.toMatch(/running/i);
    expect(sendDesktopNotification).not.toHaveBeenCalled();
  });
  it('a poll that lagged while contact held is not a gap, so the end still notifies', async () => {
    await observe(verdict('transitioning'));
    await observe({ phase: 'stale' });
    elapsed += 60_000;
    await observe(verdict('operational'));
    expect(sent().map(notice => notice.title)).toEqual(['Search is up to date']);
  });
  it('work seen active again after the gap ends live, so it notifies', async () => {
    await observe(verdict('transitioning'));
    await observe(disconnected());
    await observe(verdict('transitioning'));
    elapsed += 60_000;
    await observe(verdict('operational'));
    expect(sent().map(notice => notice.title)).toEqual(['Search is up to date']);
  });
  it('an install seen active again after the gap ends live, so it notifies', async () => {
    await observe(engine('installing'));
    await observe(disconnected());
    await observe(engine('installing'));
    elapsed += 60_000;
    await observe(engine('online'));
    expect(sent().map(notice => notice.title)).toEqual(['AI install complete']);
  });
  it('an operation seen running again after the gap ends live, so it notifies', async () => {
    const advance = operation('gap-live', 'Rebuild search', { state: 'running', historySince: 0 });
    await advance();
    await observe(disconnected());
    await observe({});
    const reads = () => vi.mocked(fetch).mock.calls.filter(([url]) => String(url).includes('/api/operation-history/gap-live')).length;
    const readsBefore = reads();
    await advance();
    await vi.waitFor(() => expect(reads()).toBeGreaterThan(readsBefore));
    await flush();
    elapsed += 60_000;
    await advance({ state: 'complete', historySince: 0 });
    await advance();
    expect(sent().map(notice => notice.title)).toEqual(['Rebuild search finished']);
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
    await observe({ ...verdict('operational'), ...idleSince(SINCE) });
    await flush();
    expect(sent()).toEqual([{ id: `Search is up to date@${SINCE}#0`, title: 'Search is up to date', sound: false }]);
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
    await observe(idleSince(SINCE));
    expect(sent()).toEqual([{ id: `Search is up to date@${SINCE}#0`, title: 'Search is up to date', sound: false }]);
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
  it('a request answered since says so in the panel, and opens no review', async () => {
    clickNotification('authorization:gone'); await flush(); await flush();
    expect(noticeGone()).toBe('What that notification was about is no longer here.');
    expect(root().querySelector('[data-testid="status-review"]')).toBeNull();
    pill.close(); await flush();
    agent('i2'); await flush();
    clickNotification('authorization:i2'); await flush(); await flush();
    expect(noticeGone()).toBeUndefined();
  });
  it('a row that is there says nothing about it having gone', async () => {
    agent('i3'); await flush();
    clickNotification('authorization:i3'); await flush(); await flush();
    expect(noticeGone()).toBeUndefined();
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

describe('DE5 clicking a notification while a dialog is open', () => {
  const reviewing = () => root().querySelector('[data-testid="status-review"]')?.getAttribute('data-fact');
  /** A dialog owning the screen, as Settings does. */
  function openDialog() { const dialog = new ModalityController(pill); dialog.enter(); return dialog; }
  const settle = async () => { await new Promise(resolve => setTimeout(resolve)); await flush(); await flush(); };
  it('waits for the dialog, then opens the review', async () => {
    agent('m1'); await flush();
    const dialog = openDialog();
    clickNotification('authorization:m1'); await flush(); await flush();
    expect(pill.open).toBe(false);
    dialog.exit(); await settle();
    expect(pill.open).toBe(true);
    expect(reviewing()).toBe('authorization:m1');
  });
  it('waits for the last of two dialogs', async () => {
    agent('m2'); await flush();
    const under = openDialog(); const over = openDialog();
    clickNotification('authorization:m2');
    over.exit(); await settle();
    expect(pill.open).toBe(false);
    under.exit(); await settle();
    expect(reviewing()).toBe('authorization:m2');
  });
  it('keeps waiting when another dialog follows the closed one in the same turn', async () => {
    agent('m3'); await flush();
    const first = openDialog();
    clickNotification('authorization:m3');
    first.exit();
    const next = openDialog(); await settle();
    expect(pill.open).toBe(false);
    next.exit(); await settle();
    expect(reviewing()).toBe('authorization:m3');
  });
  it('the latest click is the one remembered', async () => {
    agent('m4'); agent('m5'); await flush();
    const dialog = openDialog();
    clickNotification('authorization:m4'); clickNotification('authorization:m5');
    dialog.exit(); await settle();
    expect(reviewing()).toBe('authorization:m5');
  });
  it('a request answered meanwhile opens no review and says so in the panel', async () => {
    agent('m6'); await flush();
    const dialog = openDialog();
    clickNotification('authorization:m6');
    withdrawAuthorization('m6');
    dialog.exit(); await settle();
    expect(pill.open).toBe(true);
    expect(reviewing()).toBeUndefined();
    expect(noticeGone()).toBe('What that notification was about is no longer here.');
  });
  it('a request that expired meanwhile opens no review and says so in the panel', async () => {
    agent('m7'); await flush();
    const dialog = openDialog();
    clickNotification('authorization:m7');
    elapsed += 5 * 60_000;
    dialog.exit(); await settle();
    expect(reviewing()).toBeUndefined();
    expect(noticeGone()).toBe('What that notification was about is no longer here.');
  });
  it('C10 a detach forgets the click', async () => {
    agent('m8'); await flush();
    const dialog = openDialog();
    clickNotification('authorization:m8');
    pill.remove(); document.body.append(pill); await flush();
    dialog.exit(); await settle();
    expect(pill.open).toBe(false);
  });
  it('with a review open, the review moves to the request clicked', async () => {
    agent('n1'); agent('n2'); await flush();
    clickNotification('authorization:n1'); await flush(); await flush();
    expect(reviewing()).toBe('authorization:n1');
    clickNotification('authorization:n2'); await flush(); await flush();
    expect(reviewing()).toBe('authorization:n2');
  });
  it('with a review open, a request that has gone leaves the review where it is and says so', async () => {
    agent('n3'); await flush();
    clickNotification('authorization:n3'); await flush(); await flush();
    clickNotification('authorization:gone'); await flush(); await flush();
    expect(reviewing()).toBe('authorization:n3');
    expect(noticeGone()).toBe('What that notification was about is no longer here.');
  });
  it('with a request\'s security dialog open, the review moves to the request clicked, and the first keeps waiting', async () => {
    agent('n4', { gateBehavior: 'TYPED_CONFIRM' }); agent('n5'); await flush();
    clickNotification('authorization:n4'); await settle();
    expect(modalOwnsFocus()).toBe(true);
    clickNotification('authorization:n5'); await settle();
    expect(modalOwnsFocus()).toBe(false);
    expect(reviewing()).toBe('authorization:n5');
    expect(pill.status.model.sections.needs.map(row => row.id).sort()).toEqual(['authorization:n4', 'authorization:n5']);
  });
  it('with a request\'s security dialog open, a typed request clicked takes the dialog', async () => {
    agent('n8', { gateBehavior: 'TYPED_CONFIRM' }); agent('n9', { gateBehavior: 'TYPED_CONFIRM' }); await flush();
    clickNotification('authorization:n8'); await settle();
    clickNotification('authorization:n9'); await settle();
    expect(modalOwnsFocus()).toBe(true);
    expect(host.statusRequests()[0]).toMatchObject({ prompt: { pendingId: 'n9' } });
    expect(pill.status.model.sections.needs.map(row => row.id).sort()).toEqual(['authorization:n8', 'authorization:n9']);
  });
  it('a dialog the person started is not an agent request\'s: it stays, and the target waits for it', async () => {
    agent('n6'); await flush();
    void requestAuthorization({ pendingId: 'own', operationId: 'core.ingest-files', gateBehavior: 'TYPED_CONFIRM', riskTier: 'HIGH' }); await settle();
    expect(modalOwnsFocus()).toBe(true);
    clickNotification('authorization:n6'); await settle();
    expect(modalOwnsFocus()).toBe(true);
    expect(pill.open).toBe(false);
  });
  it('clicking the request whose dialog is open keeps that dialog', async () => {
    agent('n7', { gateBehavior: 'TYPED_CONFIRM' }); await flush();
    clickNotification('authorization:n7'); await settle();
    clickNotification('authorization:n7'); await settle();
    expect(modalOwnsFocus()).toBe(true);
    expect(pill.open).toBe(false);
  });
});

describe('DE5 each end is its own event', () => {
  const ids = () => sent().map(notice => notice.id);
  it('two AI install failures a second apart each notify', async () => {
    await observe(engine('installing', 1000)); await observe(engine('install_failed', 1000));
    elapsed += 1_000;
    await observe(engine('installing', 2000)); await observe(engine('install_failed', 2000));
    expect(ids()).toEqual(['AI install failed@1000', 'AI install failed@2000']);
  });
  it('two installs completing each notify', async () => {
    await observe(engine('installing', 1000)); elapsed += 60_000; await observe(engine('online', 1000));
    await observe(engine('installing', 2000)); elapsed += 60_000; await observe(engine('online', 2000));
    expect(ids()).toEqual(['AI install complete@1000', 'AI install complete@2000']);
  });
  it('two settlings at different engine times each notify', async () => {
    await observe(verdict('transitioning')); elapsed += 60_000; await observe({ ...verdict('operational'), ...idleSince(SINCE) });
    elapsed += 60_000;
    await observe(verdict('transitioning')); elapsed += 60_000; await observe({ ...verdict('operational'), ...idleSince('2026-11-06T10:05:00Z') });
    expect(ids()).toEqual([`Search is up to date@${SINCE}#0`, 'Search is up to date@2026-11-06T10:05:00Z#0']);
  });
  it('a settling that changed nothing in the engine\'s indexing state is still its own event', async () => {
    await observe(verdict('transitioning')); elapsed += 60_000; await observe({ ...verdict('operational'), ...idleSince(SINCE) });
    elapsed += 60_000;
    await observe(verdict('transitioning')); elapsed += 60_000; await observe({ ...verdict('operational'), ...idleSince(SINCE) });
    expect(ids()).toEqual([`Search is up to date@${SINCE}#0`, `Search is up to date@${SINCE}#1`]);
  });
  it('a second window seeing the same end later gives the same id, so the shell shows it once', async () => {
    const other = new StatusPill(); other.store = store; other.authorizationHost = () => host; document.body.append(other); await flush();
    try {
      for (const [window, delay] of [[pill, 0], [other, 700]] as const) {
        window.status.ai = ai(engine('installing')); window.status.refresh(); await flush();
        elapsed += delay;
        window.status.ai = ai(engine('install_failed')); window.status.refresh(); await flush();
        window.status.ai = ai({ ...verdict('transitioning') }); window.status.refresh(); await flush();
        elapsed += 60_000 + delay;
        window.status.ai = ai({ ...verdict('operational'), ...idleSince(SINCE) }); window.status.refresh(); await flush();
      }
      const [first, second] = [ids().slice(0, 2), ids().slice(2)];
      expect(first).toEqual(['AI install failed@1000', `Search is up to date@${SINCE}#0`]);
      expect(second).toEqual(first);
    } finally { other.remove(); }
  });
  it('clicking one lands on its row by its words', async () => {
    await observe(engine('installing', 1000)); await observe(engine('install_failed', 1000));
    clickNotification('AI install failed@1000'); await flush(); await flush();
    expect(root().activeElement?.closest('article')?.textContent).toContain('AI install failed');
    expect(noticeGone()).toBeUndefined();
  });
});

describe('C6 live regions follow the same rule as notifications', () => {
  /** Everything the pill's live regions hold now. */
  const spoken = () => [...pill.shadowRoot!.querySelectorAll('[aria-live], jf-system-notice')].map(region => region.textContent?.trim()).filter(Boolean);
  const earlier = () => panel().model!.sections.earlier.map(row => row.title);
  it('a transition finished while disconnected says nothing, and Earlier lists it', async () => {
    await observe(verdict('operational'));
    await observe(verdict('transitioning'));
    await observe(disconnected());
    await observe(verdict('operational'));
    expect(spoken()).not.toContain('Search is up to date');
    expect(earlier()).toContain('Search is up to date');
  });
  it('indexing finished while disconnected says nothing, and Earlier lists it', async () => {
    await observe({ status: { worker: { core: { indexState: 'INDEXING', pendingJobs: 5 }, enrichment: {} } } as AiState['status'] });
    await observe(disconnected());
    await observe({});
    expect(spoken()).not.toContain('Search is up to date');
    expect(earlier()).toContain('Search is up to date');
  });
  it('an install that failed while disconnected says nothing, in either announcer, and Earlier lists it', async () => {
    await observe(engine('installing'));
    await observe(disconnected());
    await observe(engine('install_failed'));
    expect(spoken()).not.toContain('AI install failed');
    expect(spoken()).not.toContain('Install Failed');
    expect(earlier()).toContain('AI install failed');
  });
  it('an install completed while disconnected says nothing, and Earlier lists it', async () => {
    await observe(engine('installing'));
    await observe(disconnected());
    await observe(engine('online'));
    expect(spoken()).not.toContain('AI install complete');
    expect(earlier()).toContain('AI install complete');
  });
  it('an operation finished while disconnected says nothing, and Earlier lists it', async () => {
    const advance = operation('gap-spoken', 'Rebuild search', { state: 'running', historySince: 0 });
    await advance();
    await observe(disconnected());
    await advance({ state: 'complete', historySince: 0 });
    await advance();
    expect(spoken()).not.toContain('Rebuild search finished');
    expect(earlier()).toContain('Rebuild search finished');
  });
  it('after the unsaid end, the spoken status moves on with what the pill shows, never the engine\'s own headline (FC10a)', async () => {
    await observe(engine('installing'));
    await observe(disconnected());
    await observe(engine('install_failed'));
    await observe(engine('offline'));
    const shown = pill.status.model.pill;
    expect(pill.status.verdictAnnouncement?.text, 'the pill\'s state, in its words').toBe(shown.words ?? shown.label);
    for (const text of spoken()) expect(text).not.toMatch(/^(Offline|Install Failed|Not Installed)$/);
  });
  it('an end observed live is still announced', async () => {
    await observe(verdict('transitioning'));
    await observe(verdict('operational'));
    expect(spoken()).toContain('Search is up to date');
    await observe(engine('installing'));
    await observe(engine('install_failed'));
    expect(spoken()).toContain('AI install failed');
    // FC10a — in the pill's words only: never the engine's title-case headline beside them.
    expect(spoken()).not.toContain('Install Failed');
  });
});
