// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import { __feedContactForTest, __feedForTest, __resetAiStateForTest } from '../../state/aiStateStore.js';
import { __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';
import { resetInspectorState } from '../../state/inspectorState.js';
import { resetSearchState } from '../../state/searchState.js';

import { isSv3Recovery } from './sv3-recovery.js';
import type { Sv3Recovery } from './sv3-recovery.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';

import { __resetAgentSessionStore } from '../../state/agentSessionStore.js';
import { registerCoreStores } from '../../router/bootstrap.js';
import { createNavigationHandler } from '../../router/navigationHandler.js';
import { deactivateProjection } from '../../router/URLProjector.js';
import { takeSearchV3Handoff, takePendingForceShape, takePendingSelection, takePendingAutoRun } from '../../utils/compose.js';

type ElementView = HTMLElement & { updateComplete: Promise<unknown> };
let fetchMock: ReturnType<typeof vi.fn>;
const answer = 'summary';
const q = <T extends Element = HTMLElement>(host: ElementView, id: string): T | null =>
  host.shadowRoot?.querySelector<T>(`[data-testid="${id}"]`) ?? null;
const dispatches = (): unknown[][] => fetchMock.mock.calls.filter(c => String(c[0]).includes('/api/chat/dispatch'));
async function drain(view: ElementView): Promise<void> {
  for (let i = 0; i < 6; i++) await new Promise<void>(resolve => setTimeout(resolve, 0));
  await view.updateComplete;
}
async function composer(view: ElementView): Promise<ElementView> {
  const c = view.shadowRoot!.querySelector('jf-sv3-composer') as ElementView;
  await c.updateComplete; return c;
}
async function mount(): Promise<SearchV3View> {
  const view = document.createElement('jf-sv3-window') as SearchV3View;
  document.body.append(view); await drain(view); return view;
}
beforeEach(() => {
  registerCoreStores();
  resetInspectorState();
  sessionStorage.clear(); localStorage.clear();
  __resetConversationListForTest(); __resetDraftProvidersForTest(); __resetDraftKeptForTest();
  __resetAiStateForTest();
  __feedForTest({ inference: { mode: 'online', available: true } as never,
    status: { worker: { core: { indexedDocuments: 42 } } } as unknown as StatusSnapshot });
  __feedContactForTest();
  fetchMock = vi.fn(async (url: unknown) => {
    if (!String(url).includes('/api/chat/dispatch')) return { ok: true, status: 200, json: async () => ({}) };
    const encoder = new TextEncoder();
    const frames = [
      ['rag.citations', { citations: [{ parentDocId: 'docs/a.md', chunkIndex: 0, excerpt: 'source' }] }],
      ['chunk', { text: '{rejected}' }],
      ['chunk', { text: answer }],
      ['done', { finalResponse: answer }],
    ].map(([event, data]) => ({ done: false, value: encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`) }));
    return { ok: true, status: 200, body: { getReader: () => ({
      read: async () => frames.shift() ?? { done: true }, releaseLock: () => {}, cancel: () => {},
    }) } };
  });
  vi.stubGlobal('fetch', fetchMock);
});
afterEach(async () => {
  deactivateProjection();
  takeSearchV3Handoff(); takePendingSelection(); takePendingForceShape(); takePendingAutoRun();
  document.body.replaceChildren();
  // Disconnect closes the window's subscriptions and stream; settle its outstanding promises.
  await new Promise<void>(resolve => setTimeout(resolve, 0));
  resetSearchState(); __resetAiStateForTest(); __resetAgentSessionStore();
  vi.unstubAllGlobals(); vi.restoreAllMocks();
});
async function navigate(state: Record<string, string | string[]>): Promise<void> {
  await createNavigationHandler({ isKnownSurface: () => true, setActiveSurface: () => {} })
    .handle({ kind: 'navigate', target: 'core.search-v3-surface', state });
}
async function field(view: SearchV3View): Promise<HTMLTextAreaElement> {
  return q<HTMLTextAreaElement>(await composer(view), 'sv3-composer-input')!;
}
function select(view: SearchV3View, id: string): void {
  view.shadowRoot!.querySelector('jf-sv3-sidebar')!.dispatchEvent(new CustomEvent('sv3-session-select', {
    bubbles: true, composed: true, detail: { id },
  }));
}
async function startStreaming(): Promise<{ view: SearchV3View; id: string; stream: ReadableStreamDefaultController<Uint8Array> }> {
  let stream!: ReadableStreamDefaultController<Uint8Array>;
  const previous = fetchMock.getMockImplementation()! as (url: unknown, init?: RequestInit) => Promise<unknown>;
  fetchMock.mockImplementation(async (url: unknown, init?: RequestInit) => {
    if (!String(url).includes('/api/chat/dispatch')) return previous(url, init);
    return new Response(new ReadableStream<Uint8Array>({ start(c) { stream = c; } }));
  });
  const view = await mount();
  await navigate({ mode: 'ask', docIds: ['chosen.md'] }); await drain(view);
  const input = await field(view);
  input.value = 'Original running question'; input.dispatchEvent(new Event('input')); await drain(view);
  q<HTMLButtonElement>(await composer(view), 'sv3-composer-send')!.click(); await drain(view);
  expect(dispatches()).toHaveLength(1);
  const body = JSON.parse((dispatches()[0]![1] as RequestInit).body as string);
  expect(body.docIds).toEqual(['chosen.md']);
  expect(q(await composer(view), 'sv3-composer-stop')).not.toBeNull();
  return { view, id: body.conversationId as string, stream };
}
async function arrivals(view: SearchV3View, count: number): Promise<void> {
  for (let i = 0; i < count; i++) {
    await navigate({ query: i ? 'Latest B' : 'Incoming A', mode: 'ask', docIds: [i ? 'chosen.md' : 'decoy.md'] });
    await drain(view);
  }
}
async function finish(view: SearchV3View, stream: ReadableStreamDefaultController<Uint8Array>): Promise<void> {
  stream.enqueue(new TextEncoder().encode('event: chunk\ndata: {"text":"still running"}\n\nevent: done\ndata: {}\n\n'));
  stream.close(); await drain(view);
}
const recoveryKey = 'justsearch.draft.attachment.v1.search-v3.main';
function recovery(): Sv3Recovery {
  const payload: unknown = JSON.parse(sessionStorage.getItem(recoveryKey)!).payload;
  expect(isSv3Recovery(payload)).toBe(true);
  return payload as Sv3Recovery;
}
function expectArrivalsRetained(): void {
  expect(recovery().drafts).toEqual(expect.arrayContaining([
    { place: null, text: 'Incoming A', preparation: { kind: 'ask', scope: { kind: 'files', paths: ['decoy.md'] } } },
    { place: null, text: 'Latest B', preparation: { kind: 'ask', scope: { kind: 'files', paths: ['chosen.md'] } } },
  ]));
  expect(recovery().drafts).toHaveLength(2);
}
it.each(['sidebar', 'Back'] as const)('HO8a two hand-offs keep the running Ask one %s navigation away', async via => {
  const { view, id, stream } = await startStreaming();
  await arrivals(view, 2);
  expect((await field(view)).value).toBe('Latest B');
  if (via === 'sidebar') select(view, id);
  else await navigate({ conversationId: id });
  await drain(view);
  expect(view.sessions.activeId).toBe(id);
  expect(q(await composer(view), 'sv3-composer-stop')).not.toBeNull();
  expect(view.shadowRoot!.textContent).not.toContain('could not be saved safely');
  expect((dispatches()[0]![1] as RequestInit).signal!.aborted).toBe(false);
  expect(dispatches()).toHaveLength(1);
  expect(fetchMock.mock.calls.filter(([url]) => /\/stop$/.test(String(url)))).toHaveLength(0);
  expectArrivalsRetained();
  window.dispatchEvent(new Event('pagehide')); await drain(view);
  expectArrivalsRetained();
  await finish(view, stream);
  expectArrivalsRetained();
});
it('HO8a preservation one hand-off restores a streaming follow-up and chosen files', async () => {
  const { view, id, stream } = await startStreaming();
  const input = await field(view);
  input.value = 'Unsent follow-up'; input.dispatchEvent(new Event('input')); await drain(view);
  await arrivals(view, 1);
  select(view, id); await drain(view);
  expect(view.sessions.activeId).toBe(id);
  expect((await field(view)).value).toBe('Unsent follow-up');
  expect(q(await composer(view), 'sv3-composer-stop')).not.toBeNull();
  expect((dispatches()[0]![1] as RequestInit).signal!.aborted).toBe(false);
  await finish(view, stream);
});

it('HO8a preservation a third unretained edited draft still blocks departure without losing inputs', async () => {
  // A raw draft can be newer than its two acknowledged preparation copies after an interrupted save.
  const drafts = [
    { place: null, text: 'Incoming A', preparation: { kind: 'ask', scope: { kind: 'files', paths: ['decoy.md'] } } },
    { place: null, text: 'Latest B', preparation: { kind: 'ask', scope: { kind: 'files', paths: ['chosen.md'] } } },
  ];
  sessionStorage.setItem(recoveryKey, JSON.stringify({ version: 1, incarnation: 'interrupted-save',
    payload: { version: 2, task: null, drafts } }));
  localStorage.setItem('justsearch.draft.search-v3.composer', 'Third edited follow-up');
  const before = sessionStorage.getItem(recoveryKey);
  const view = await mount();
  expect((await field(view)).value).toBe('Third edited follow-up');
  await navigate({ conversationId: 'existing-conversation' }); await drain(view);
  expect(view.sessions.activeId).toBeNull();
  expect((await field(view)).value).toBe('Third edited follow-up');
  expect(sessionStorage.getItem(recoveryKey)).toBe(before);
  expectArrivalsRetained();
  expect(view.shadowRoot!.textContent).toContain('could not be saved safely');
  expect(dispatches()).toHaveLength(0);
});
it('HO8a preservation an empty destination still requires an acknowledged recovery write', async () => {
  const { view, id, stream } = await startStreaming();
  await arrivals(view, 2);
  const before = sessionStorage.getItem(recoveryKey);
  const original = sessionStorage.setItem.bind(sessionStorage);
  vi.spyOn(sessionStorage, 'setItem').mockImplementation((key, value) => {
    if (key === recoveryKey) throw new Error('storage unavailable');
    original(key, value);
  });
  select(view, id); await drain(view);
  expect(view.sessions.activeId).toBeNull();
  expect((await field(view)).value).toBe('Latest B');
  expect(sessionStorage.getItem(recoveryKey)).toBe(before);
  expectArrivalsRetained();
  expect(dispatches()).toHaveLength(1);
  await finish(view, stream);
});
