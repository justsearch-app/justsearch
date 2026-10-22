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
