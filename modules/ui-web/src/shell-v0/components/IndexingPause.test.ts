// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './TaskList.js';
import './StatusDeck.js';
import './IndexingOverlay.js';
import '../views/SettingsSurface.js';
import '../views/HealthSurface.js';
import '../views/LibrarySurface.js';
import '../views/search-v3/SearchV3View.js';
import { __feedForTest, __feedContactForTest, __resetAiStateForTest, type StatusSnapshot } from '../state/aiStateStore.js';
import { selectIndexingProgress } from '../state/indexingProgress.js';
import { getViewerAudience, setViewerAudience } from '../state/viewerAudienceState.js';
import { folderStatus } from '../state/folderStatus.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import { setAuthorizationPresenter } from '../operations/authorizationBroker.js';
import { resetSearchState } from '../state/searchState.js';
import { __resetConversationListForTest } from '../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../controllers/draftKeptHint.js';
import type { IndexedRootView } from '../../api/generated/schema-types/indexed-root-view.js';

type Mounted = HTMLElement & { updateComplete: Promise<unknown> };
let fetchMock: ReturnType<typeof vi.fn>;
function snapshot(state: string, pendingJobs = 3): StatusSnapshot {
  return { operating: { indexing: { state, reasons: ['user_paused'], untilEpochMs: null,
    waitingForResume: pendingJobs > 0 } }, worker: { core: { indexState: pendingJobs ? 'INDEXING' : 'IDLE',
    pendingJobs, indexedDocuments: 12, searchableDocuments: 12, indexHealthy: true },
    migration: { processingJobsCount: 0 }, enrichment: { backfillMode: 'idle' } } } as unknown as StatusSnapshot;
}
function feed(state: string, pendingJobs = 3): void {
  __feedForTest({ status: snapshot(state, pendingJobs) });
  __feedContactForTest();
}
async function settle(el: Mounted): Promise<void> {
  await el.updateComplete;
  await new Promise(resolve => setTimeout(resolve, 0));
  await el.updateComplete;
}
async function mount(tag: string): Promise<Mounted> {
  const el = document.createElement(tag) as Mounted;
  Object.assign(el, { host_: createMockHostApi(), activeCategory: 'indexing' });
  document.body.appendChild(el);
  await settle(el);
  return el;
}
async function controls(el: Mounted): Promise<Mounted> {
  const control = el.shadowRoot?.querySelector('jf-indexing-controls') as Mounted | null;
  expect(control, 'surface exposes indexing controls').not.toBeNull();
  await control!.updateComplete;
  return control!;
}
const button = (el: Mounted) => el.shadowRoot!.querySelector<HTMLButtonElement>('button')!;
beforeEach(() => {
  __resetAiStateForTest();
  resetSearchState();
  __resetConversationListForTest();
  __resetDraftProvidersForTest();
  __resetDraftKeptForTest();
  fetchMock = vi.fn().mockRejectedValue(new TypeError('Test backend unavailable'));
  vi.stubGlobal('fetch', fetchMock);
});
afterEach(async () => {
  for (const child of [...document.body.children]) child.remove();
  setAuthorizationPresenter(null);
  resetSearchState();
  __resetAiStateForTest();
  await new Promise(resolve => setTimeout(resolve, 0));
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('WP3.7 shared pause surfaces', () => {
  for (const tag of ['jf-task-list', 'jf-status-deck', 'jf-health-surface', 'jf-library-surface', 'jf-settings-surface']) {
    it(`S37.1 S37.5 S37.6 ${tag} exposes Resume even with nothing queued`, async () => {
      feed('PAUSED', 0);
      const el = await mount(tag);
      expect(el.shadowRoot!.textContent).toContain('Indexing paused');
      expect(el.shadowRoot!.textContent).toContain('Paused by you');
      const control = await controls(el);
      expect(button(control).textContent).toContain('Resume indexing');
      expect(button(control).disabled).toBe(false);
      expect(el.hasAttribute('data-empty')).toBe(false);
      feed('RUNNING', 0);
      await settle(el);
      expect(el.shadowRoot!.textContent).not.toContain('Indexing paused');
    });
  }

  it('S37.1 invokes Pause with the chosen deadline through the existing approval path without optimistic paused state', async () => {
    feed('RUNNING');
    const el = await mount('jf-settings-surface');
    const control = await controls(el);
    const duration = control.shadowRoot!.querySelector<HTMLSelectElement>('select');
    expect(duration).not.toBeNull();
    duration!.value = '3600000';
    duration!.dispatchEvent(new Event('change', { bubbles: true }));
    const presenter = vi.fn().mockResolvedValue({ approved: true, allowAlways: false });
    setAuthorizationPresenter(presenter);
    fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).endsWith('/authorizations/approve')) return Response.json({ capsule: 'approved' });
      if (String(input).endsWith('/core.indexing-pause/invoke')) {
        return JSON.parse(String(init?.body)).confirmationToken
          ? Response.json({ success: true, message: 'Accepted' })
          : Response.json({ success: false, errorClass: 'CONFIRMATION_REQUIRED', pendingId: 'pause-1', gateBehavior: 'INLINE_CONFIRM' }, { status: 428 });
      }
      throw new TypeError('Test backend unavailable');
    });
    const now = Date.now();
    button(control).click();
    await settle(control);
    expect(presenter).toHaveBeenCalledWith(expect.objectContaining({ operationId: 'core.indexing-pause', pendingId: 'pause-1' }));
    const calls = fetchMock.mock.calls.filter(c => String(c[0]).endsWith('/core.indexing-pause/invoke'));
    expect(calls).toHaveLength(2);
    const first = JSON.parse(String(calls[0]![1]!.body));
    expect(first.args.untilEpochMs).toBeGreaterThanOrEqual(now + 3600000);
    expect(first.args.untilEpochMs).toBeLessThanOrEqual(Date.now() + 3600000);
    expect(JSON.parse(String(calls[1]![1]!.body)).args).toEqual(first.args);
    expect(el.shadowRoot!.textContent).not.toContain('Indexing paused');
    expect(control.shadowRoot!.textContent).toContain('Waiting for status');
    feed('PAUSING');
    await settle(el);
    expect(el.shadowRoot!.textContent).toContain('Pausing — finishing current files');
    feed('PAUSED');
    await settle(el);
    expect(el.shadowRoot!.textContent).toContain('Indexing paused');
  });

  it('S37.1 the progress view pauses indefinitely and a sibling surface resumes from the same observation', async () => {
    feed('RUNNING');
    const tasks = await mount('jf-task-list');
    const settings = await mount('jf-settings-surface');
    const taskControl = await controls(tasks);
    fetchMock.mockImplementation(async () => Response.json({ success: true, message: 'Accepted' }));
    button(taskControl).click();
    await settle(taskControl);
    const pause = fetchMock.mock.calls.find(c => String(c[0]).endsWith('/core.indexing-pause/invoke'));
    expect(pause).toBeDefined();
    expect(JSON.parse(String(pause![1].body)).args).toEqual({});
    feed('PAUSED');
    await settle(settings);
    const resume = await controls(settings);
    button(resume).click();
    await settle(resume);
    expect(fetchMock.mock.calls.some(c => String(c[0]).endsWith('/core.indexing-resume/invoke'))).toBe(true);
    expect(tasks.shadowRoot!.textContent).toContain('Indexing paused');
    feed('RUNNING');
    await settle(tasks);
    expect(tasks.shadowRoot!.textContent).not.toContain('Indexing paused');
  });

  it('S37.1 reports uncertain invocation failures without claiming paused', async () => {
    feed('RUNNING');
    const el = await mount('jf-task-list');
    const control = await controls(el);
    button(control).click();
    await settle(control);
    expect(control.shadowRoot!.textContent).toContain('Could not confirm');
    expect(el.shadowRoot!.textContent).not.toContain('Indexing paused');
  });

  it('S37.4 waiting folder work names Resume and retains failure and searchability evidence', () => {
    const ctx = { relativeTime: '', verifiedRelativeTime: '', provisional: false, indexingPaused: true };
    const row = { status: 'indexed', indexed: true, fileCount: 10, inFlightCount: 3, failedCount: 2 } as unknown as IndexedRootView;
    const pending = folderStatus(row, ctx);
    expect(pending.metaText).toContain('Waiting for Resume');
    expect(pending.failed).toBe(2);
    for (const status of ['unavailable', 'error']) {
      const caveat = folderStatus({ ...row, status, walkError: status === 'error' ? 'Read failed' : undefined } as IndexedRootView, ctx);
      expect(caveat.failed).toBe(2);
      expect(caveat.metaText).not.toContain('Indexing ·');
    }
  });

  it('S37.4 paused unverified folders retain their verification caveat and recovery state', () => {
    const ctx = { relativeTime: '', verifiedRelativeTime: '', provisional: false, indexingPaused: true };
    const row = { status: 'indexed', fileCount: 10, inFlightCount: 3, failedCount: 2,
      deleteDetectionUnverified: true } as IndexedRootView;
    const p = folderStatus(row, ctx);
    expect(p.state).toBe('unverified');
    expect(p.metaText).toContain("couldn't verify deletions");
    expect(p.metaText).toContain('Waiting for Resume');
    expect(p.failed).toBe(2);
  });

  it('S37.7 the status announcer changes once per pause/resume edge, staying silent on repeated polls', async () => {
    feed('RUNNING');
    const el = await mount('jf-status-deck');
    const announcer = el.shadowRoot!.querySelector('[data-testid="indexing-announcer"]')!;
    const changes: string[] = [];
    const observer = new MutationObserver(() => changes.push(announcer.textContent!.trim()));
    observer.observe(announcer, { subtree: true, childList: true, characterData: true });
    try {
      feed('PAUSED'); await settle(el);
      feed('PAUSED'); await settle(el);
      feed('RUNNING'); await settle(el);
      feed('RUNNING'); await settle(el);
      expect(changes.filter(s => s.includes('Indexing paused'))).toHaveLength(1);
      expect(changes.filter(s => s.includes('Indexing resumed'))).toHaveLength(1);
    } finally { observer.disconnect(); }
  });

  it('S37.7 announces confirmed pause and resume once across details and unconfirmed blips', async () => {
    feed('RUNNING', 0);
    const el = await mount('jf-status-deck');
    const announcer = el.shadowRoot!.querySelector('[data-testid="indexing-announcer"]')!;
    const changes: string[] = [];
    const observer = new MutationObserver(() => changes.push(announcer.textContent!.trim()));
    observer.observe(announcer, { subtree: true, childList: true, characterData: true });
    const blip = async (state: string, pending: number) => {
      const stale = snapshot(state, pending);
      stale.meta = { ...stale.meta, workerRpcStale: true } as typeof stale.meta;
      __feedForTest({ status: stale }); __feedContactForTest(); await settle(el);
      feed(state, pending); await settle(el);
    };
    try {
      feed('PAUSED', 0); await settle(el);
      feed('PAUSED', 4); await settle(el);
      expect(el.shadowRoot!.textContent).toContain('waiting for Resume');
      await blip('PAUSED', 4);
      feed('RUNNING', 4); await settle(el);
      feed('RUNNING', 0); await settle(el);
      await blip('RUNNING', 0);
      expect(changes.filter(s => s.includes('Indexing paused'))).toHaveLength(1);
      expect(changes.filter(s => s.includes('Indexing resumed'))).toHaveLength(1);
    } finally { observer.disconnect(); }
  });

  it('S37.8 Search v3 offers Resume alongside paused waiting-file context for Search and Ask', async () => {
    feed('PAUSED');
    __feedForTest({ inference: { mode: 'online', available: true, activeModelId: 'test-model' } as never });
    const el = await mount('jf-sv3-window');
    expect(el.shadowRoot!.textContent).toContain('Indexing paused');
    expect(el.shadowRoot!.textContent).toContain('waiting for Resume');
    expect(button(await controls(el)).textContent).toContain('Resume indexing');
    const composer = el.shadowRoot!.querySelector('jf-sv3-composer') as Mounted;
    await composer.updateComplete;
    composer.shadowRoot!.querySelector<HTMLButtonElement>('[data-testid="sv3-composer-tier"]')!.click();
    await composer.updateComplete;
    const ask = composer.shadowRoot!.querySelector<HTMLButtonElement>('[data-tier="ask"]')!;
    expect(ask.disabled).toBe(false);
    ask.click();
    await settle(el);
    expect(el.shadowRoot!.textContent).toContain('waiting for Resume');
    expect(button(await controls(el)).textContent).toContain('Resume indexing');
    feed('PAUSED', 0);
    await settle(el);
    expect(el.shadowRoot!.querySelector('jf-indexing-controls')).toBeNull();
  });

  it('S37.1 the indexing overlay exposes the same Pause and Resume control', async () => {
    const el = await mount('jf-indexing-overlay');
    Object.assign(el, { progress: selectIndexingProgress(snapshot('PAUSED'), true, 3, []),
      activity: selectIndexingProgress(snapshot('PAUSED'), true, 3, []).activity });
    await settle(el);
    expect(button(await controls(el)).textContent).toContain('Resume indexing');
  });
});


describe('CR37 keyboard continuity and recovery', () => {
  const tags = ['jf-task-list', 'jf-status-deck', 'jf-health-surface', 'jf-library-surface', 'jf-settings-surface', 'jf-sv3-window'];
  function observe(state: string, reason = 'user_paused', stale = false): void {
    const status = snapshot(state);
    status.operating!.indexing!.reasons = [reason] as never;
    status.meta = { ...status.meta, workerRpcStale: stale } as typeof status.meta;
    __feedForTest({ status });
    __feedContactForTest();
  }

  for (const tag of tags) {
    it(`CR37 ${tag} retains focused Resume across unconfirmed observations and accepts activation`, async () => {
      observe('PAUSED');
      const el = await mount(tag);
      const control = await controls(el);
      const resume = button(control);
      resume.focus();
      observe('PAUSED', 'user_paused', true);
      await settle(el);
      expect(el.shadowRoot!.querySelector('jf-indexing-controls'), 'poll must retain the control').toBe(control);
      expect(control.shadowRoot!.activeElement, 'poll must retain keyboard focus').toBe(resume);
      expect(resume.disabled, 'uncertain activity does not make Resume impossible').toBe(false);
      fetchMock.mockResolvedValue(Response.json({ success: true, message: 'Accepted' }));
      resume.click();
      await settle(control);
      expect(fetchMock.mock.calls.some(c => String(c[0]).endsWith('/core.indexing-resume/invoke'))).toBe(true);
    });

    it(`CR37a ${tag} explains damaged settings and navigates to the existing reset without offering Resume`, async () => {
      observe('PAUSED', 'settings_recovery');
      const el = await mount(tag);
      const text = el.shadowRoot!.textContent!;
      expect(text).toContain('saved settings were damaged and replaced by defaults');
      expect(text).toContain('reset is confirmed in Settings');
      expect(text).toContain('Check exclusions');
      expect(text).not.toMatch(/held until you resume|waiting for Resume/i);
      const control = await controls(el);
      expect(control.shadowRoot!.textContent).not.toContain('Resume indexing');
      const open = button(control);
      expect(open.textContent).toContain('Open Settings at View tier');
      expect(control.shadowRoot!.textContent).toContain('In Settings, set View tier to Operator, then Reset to defaults.');
      open.focus();
      expect(control.shadowRoot!.activeElement).toBe(open);
      const navigate = vi.fn();
      document.addEventListener('jf-navigate-to-surface', navigate);
      try { open.click(); await settle(control); }
      finally { document.removeEventListener('jf-navigate-to-surface', navigate); }
      expect(navigate).toHaveBeenCalledWith(expect.objectContaining({ detail: {
        surfaceId: 'core.settings-surface', state: { focusSettingsRecovery: true },
      } }));
      expect(fetchMock.mock.calls.filter(c => /core\.(indexing-resume|reset-settings)\/invoke/.test(String(c[0])))).toHaveLength(0);
    });
  }

  it('CR37a the indexing overlay and waiting folder rows direct settings recovery to Settings', async () => {
    const status = snapshot('PAUSED');
    status.operating!.indexing!.reasons = ['settings_recovery'];
    const progress = selectIndexingProgress(status, true, 3, []);
    const overlay = await mount('jf-indexing-overlay');
    Object.assign(overlay, { progress, activity: progress.activity });
    await settle(overlay);
    expect(overlay.shadowRoot!.textContent).toContain('saved settings were damaged and replaced by defaults');
    expect(button(await controls(overlay)).textContent).toContain('Open Settings at View tier');
    const row = { status: 'indexed', fileCount: 10, inFlightCount: 3, failedCount: 2 } as IndexedRootView;
    const ctx = { relativeTime: '', verifiedRelativeTime: '', provisional: false, indexingPaused: true,
      indexingPauseReason: 'settings_recovery' };
    const waiting = folderStatus(row, ctx);
    expect(waiting.metaText).toContain('Waiting for Settings reset');
    expect(waiting.metaText).not.toContain('Resume');
    expect(waiting.failed).toBe(2);
  });

  for (const tier of ['OPERATOR', 'DEVELOPER'] as const) {
    it(`CR37b ${tier} recovery opens at the existing Reset action without changing tier`, async () => {
      const original = getViewerAudience();
      setViewerAudience(tier);
      try {
        observe('PAUSED', 'settings_recovery');
        const el = await mount('jf-task-list');
        const control = await controls(el);
        expect(button(control).textContent).toContain('Open Settings at Reset to defaults');
        expect(control.shadowRoot!.textContent).not.toContain('set View tier to Operator');
        const navigate = vi.fn();
        document.addEventListener('jf-navigate-to-surface', navigate);
        try { button(control).click(); await settle(control); }
        finally { document.removeEventListener('jf-navigate-to-surface', navigate); }
        expect(navigate).toHaveBeenCalledWith(expect.objectContaining({ detail: {
          surfaceId: 'core.settings-surface', state: { focusSettingsRecovery: true },
        } }));
        expect(getViewerAudience()).toBe(tier);
      } finally { setViewerAudience(original); }
    });
  }

  it('CR37 retains Pause and duration focus during uncertain observations and pending requests', async () => {
    observe('RUNNING');
    const el = await mount('jf-settings-surface');
    const control = await controls(el);
    const duration = control.shadowRoot!.querySelector<HTMLSelectElement>('select')!;
    duration.focus();
    observe('RUNNING', 'user_paused', true);
    await settle(el);
    expect(control.shadowRoot!.activeElement).toBe(duration);
    expect(duration.disabled).toBe(false);
    let finish!: (response: Response) => void;
    fetchMock.mockImplementation(() => new Promise<Response>(resolve => { finish = resolve; }));
    const pause = button(control);
    pause.focus(); pause.click();
    await control.updateComplete;
    try {
      expect(control.shadowRoot!.activeElement).toBe(pause);
      expect(pause.disabled).toBe(false);
      expect(pause.getAttribute('aria-disabled')).toBe('true');
      expect(control.shadowRoot!.textContent).toContain('Request in progress');
    } finally { finish(Response.json({ success: true, message: 'Accepted' })); await settle(control); }
  });

  it('CR37 explains an impossible action in place without removing its focused control', async () => {
    observe('PAUSED');
    const el = await mount('jf-settings-surface');
    const control = await controls(el);
    const resume = button(control);
    resume.focus();
    observe('RUNNING');
    await settle(el);
    expect(control.shadowRoot!.activeElement).toBe(resume);
    expect(resume.textContent).toContain('Resume indexing');
    expect(resume.getAttribute('aria-disabled')).toBe('true');
    expect(control.shadowRoot!.textContent).toContain('Indexing is already running');
    resume.click(); await settle(control);
    expect(fetchMock.mock.calls.some(c => String(c[0]).endsWith('/core.indexing-resume/invoke'))).toBe(false);
  });

  for (const [state, code, message] of [
    ['PAUSED', 'SETTINGS_RECOVERY_REQUIRED', 'Settings history cannot be verified'],
    ['RUNNING', 'SETTINGS_READ_ONLY', 'Settings persistence is disabled'],
  ]) {
    it(`CR37 retains the Engine refusal ${code} where pressed across status polls`, async () => {
      observe(state!);
      const el = await mount('jf-settings-surface');
      const control = await controls(el);
      fetchMock.mockResolvedValue(Response.json({ success: false, errorClass: 'HANDLER_FAILURE', errorCode: code, message }));
      button(control).click(); await settle(control);
      expect(control.shadowRoot!.textContent).toContain(message);
      observe(state!); await settle(el); await settle(control);
      expect(control.shadowRoot!.textContent).toContain(message);
      expect(control.shadowRoot!.textContent).not.toContain('Waiting for status');
    });
  }
});

describe('WP3.7 verifier layout and focus repairs', () => {
  it('F1 keeps empty-queue Resume visible through an unconfirmed observation', async () => {
    feed('PAUSED', 0);
    const task = await mount('jf-task-list');
    const control = await controls(task);
    const resume = button(control); resume.focus();
    const stale = snapshot('PAUSED', 0);
    stale.meta = { ...stale.meta, workerRpcStale: true } as typeof stale.meta;
    __feedForTest({ status: stale }); __feedContactForTest(); await settle(task);
    expect(task.hasAttribute('data-empty'), 'uncertainty must not hide a focused action').toBe(false);
    expect(control.shadowRoot!.activeElement).toBe(resume);
    fetchMock.mockResolvedValue(Response.json({ success: true, message: 'Accepted' }));
    resume.click(); await settle(control);
    expect(fetchMock.mock.calls.some(c => String(c[0]).endsWith('/core.indexing-resume/invoke'))).toBe(true);
  });

  it('F2 preserves a sensible focus and announces remote quiet-queue Resume once', async () => {
    feed('PAUSED', 0);
    const deck = await mount('jf-status-deck');
    const task = await mount('jf-task-list');
    const control = await controls(task);
    const resume = button(control); resume.focus();
    const announcer = deck.shadowRoot!.querySelector('[data-testid="indexing-announcer"]')!;
    const announcements: string[] = [];
    const observer = new MutationObserver(() => announcements.push(announcer.textContent!.trim()));
    observer.observe(announcer, { childList: true, subtree: true, characterData: true });
    try {
      feed('RUNNING', 0); await settle(task); await settle(deck);
      feed('RUNNING', 0); await settle(task); await settle(deck);
      expect(task.hasAttribute('data-empty'), 'remote resume must not hide the focused control').toBe(false);
      expect(task.shadowRoot!.activeElement).toBe(task.shadowRoot!.querySelector('[data-testid=task-title]'));
      expect(task.shadowRoot!.textContent).toContain('Indexing is already running');
      expect(announcements.filter(t => t.includes('Indexing resumed.'))).toHaveLength(1);
    } finally { observer.disconnect(); }
  });

  it('F3 keeps the focused status item outside hidden adaptive overflow', async () => {
    feed('PAUSED');
    const deck = await mount('jf-status-deck');
    const control = await controls(deck);
    const resume = button(control); resume.focus();
    // The measured cut when the longer unconfirmed description no longer fits.
    Object.defineProperty(deck.shadowRoot!.querySelector('.adaptive-bar'), 'clientWidth', { value: 1, configurable: true });
    for (const item of deck.shadowRoot!.querySelectorAll('.item')) Object.defineProperty(item, 'offsetWidth', { value: 50, configurable: true });
    const overflow = (deck as unknown as { overflow: { visibleCount: number } }).overflow;
    overflow.visibleCount = 0;
    const stale = snapshot('PAUSED');
    stale.meta = { ...stale.meta, workerRpcStale: true } as typeof stale.meta;
    __feedForTest({ status: stale }); __feedContactForTest(); await settle(deck);
    overflow.visibleCount = 0;
    (deck as Mounted & { requestUpdate: () => void }).requestUpdate();
    await deck.updateComplete;
    const item = control.closest<HTMLElement>('.item')!;
    expect(item.hidden, 'a focused item must stay in the visible prefix').toBe(false);
    expect(control.shadowRoot!.activeElement).toBe(resume);
    resume.blur(); await settle(deck);
    await vi.waitFor(() => expect(item.hidden, 'ordinary overflow returns after focus leaves').toBe(true));
  });

  it('F5 keeps a mature folder semantic-install caveat and pending glyph through Pause', async () => {
    function observe(state: string): void {
      const s = snapshot(state, 0);
      s.worker!.enrichment = { backfillMode: 'idle', embeddingEnabled: false, spladeEnabled: false,
        nerEnabled: false, embeddingDocCount: 12, embeddingPendingCount: 12 } as never;
      __feedForTest({ status: s }); __feedContactForTest();
    }
    observe('RUNNING');
    const library = await mount('jf-library-surface');
    Object.assign(library, { roots: [{ pathHash: 'mature', collection: 'mature', status: 'indexed',
      indexed: true, fileCount: 3, inFlightCount: 0, failedCount: 0 }], loading: false, error: null,
      rootsObserved: true });
    await settle(library);
    const card = () => library.shadowRoot!.querySelector('.card')!;
    expect(card().textContent).toContain('semantic search waiting for AI install');
    expect(card().querySelector('.status-icon.pending')).not.toBeNull();
    observe('PAUSED'); await settle(library);
    expect(card().textContent).toContain('semantic search waiting for AI install');
    expect(card().textContent).toContain('keyword search ready');
    expect(card().querySelector('.status-icon.pending')).not.toBeNull();
    expect(card().querySelector('.status-icon.indexed')).toBeNull();
  });
});
