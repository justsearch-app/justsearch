// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { render } from 'lit';
import { renderWhyDisclosure } from '../../components/searchResults/whyThisResult.js';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import type { Sv3Composer } from './Sv3Composer.js';
import { __feedContactForTest, __feedForTest, __resetAiStateForTest } from '../../state/aiStateStore.js';
import { __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';
import { setLastViewedConversation } from '../../controllers/lastViewedConversation.js';
import { getInspectorState, setSelected, setOpen as setInspectorOpen, resetInspectorState } from '../../state/inspectorState.js';
import { resetSearchState } from '../../state/searchState.js';

import type { StatusSnapshot } from '../../utils/statusPoll.js';

import { getAgentSessionController, __resetAgentSessionStore } from '../../state/agentSessionStore.js';
import { registerCoreStores } from '../../router/bootstrap.js';
import { createNavigationHandler } from '../../router/navigationHandler.js';
import { createIntentRouter } from '../../router/intentRouter.js';
import { deactivateProjection } from '../../router/URLProjector.js';
import * as contextMenus from '../../components/ContextMenu.js';
import { setPendingSelection, takePendingSelection, } from '../../utils/compose.js';

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
async function navigate(state: Record<string, string | string[]>): Promise<void> {
  registerCoreStores();
  await createNavigationHandler({ isKnownSurface: () => true, setActiveSurface: () => {} })
    .handle({ kind: 'navigate', target: 'core.search-v3-surface', state });
}
async function send(view: SearchV3View): Promise<Record<string, unknown>> {
  const c = await composer(view); q<HTMLButtonElement>(c, 'sv3-composer-send')!.click();
  await drain(view);
  expect(dispatches()).toHaveLength(1);
  return JSON.parse((dispatches()[0]![1] as RequestInit).body as string);
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
  takePendingSelection(); document.body.replaceChildren();
  // Disconnect closes the window's subscriptions and stream; settle its outstanding promises.
  await new Promise<void>(resolve => setTimeout(resolve, 0));
  resetSearchState(); __resetAiStateForTest(); __resetAgentSessionStore();
  vi.unstubAllGlobals(); vi.restoreAllMocks();
});
it('HO1 HO2 a cold address query arrives intact and is consumed from the address', async () => {
  const text = '# & % "日本語" ' + 'long '.repeat(100);
  await navigate({ query: text, conversationId: 'ignored' });
  const view = await mount(); const c = await composer(view);
  expect(q<HTMLTextAreaElement>(c, 'sv3-composer-input')!.value).toBe(text);
  expect(c.getAttribute('tier')).toBe('search');
  expect(window.location.hash).not.toContain('query=');
  expect(dispatches()).toHaveLength(0);
});
it.each(['core.rag-ask', 'core.summarize', 'core.extract'])('HO3 HO4 HO5 %s prepares the chosen files and waits', async shape => {
  const view = await mount();
  await navigate({ query: 'Describe risks', forceShapeId: shape, docIds: ['chosen.md'] });
  await drain(view); const c = await composer(view);
  expect(q<HTMLTextAreaElement>(c, 'sv3-composer-input')!.value).toBe('Describe risks');
  expect(c.shadowRoot!.textContent).toContain('chosen.md');
  expect(dispatches()).toHaveLength(0);
  if (shape === 'core.extract') return; // schema must be supplied by the reader
  const body = await send(view);
  expect(body.shapeId).toBe(shape);
  if (shape === 'core.rag-ask') {
    expect(body.docIds).toEqual(['chosen.md']);
    expect(body.selection).toEqual({ kind: 'result-set', items: [{ id: 'chosen.md', kind: 'doc' }] });
  }
  else expect(body.selection).toEqual({ kind: 'result-set', items: [{ id: 'chosen.md', kind: 'doc' }] });
});
it('HO3 HO5 HO9 a selection is local to its prepared turn', async () => {
  const view = await mount();
  const selection = { kind: 'text-range', address: { coords: 'canonical', docId: 'chosen.md', startChar: 2, endChar: 7 }, selectionText: 'slice', hostEntity: { kind: 'doc', id: 'chosen.md' } } as const;
  setPendingSelection(selection); await navigate({ query: 'Summarize the passage', forceShapeId: 'core.summarize' });
  await drain(view);
  expect(takePendingSelection()).toBeNull();
  expect((await send(view)).selection).toEqual(selection);
});
it('HO3 HO5 the real Explain sender with empty docIds sends the ranking trace', async () => {
  const view = await mount(); const source = document.createElement('div'); document.body.append(source);
  let state: Record<string, string | string[]> | undefined;
  let navigation: Promise<void> | undefined;
  document.addEventListener('navigate-with-context', event => {
    state = (event as CustomEvent).detail.state as Record<string, string | string[]>;
    navigation = navigate(state);
  }, { once: true });
  render(renderWhyDisclosure({ title: 'chosen-orchard.md', trace: [
    { id: 'sparse-retrieval', rank: 1, score: 12.4 }, { id: 'cross-encoder', rank: 1, score: 0.98 },
  ] }), source);
  source.querySelector<HTMLButtonElement>('[data-testid="hit-why-explain"]')!.click();
  await navigation; await drain(view);
  const prompt = 'Explain why this result ("chosen-orchard.md") ranked where it did.\n\nNo document link is available for this result; the ranking trace is shown below.\n\nRanking trace:\nsparse-retrieval #1 12.40 · cross-encoder #1 0.98';
  expect(state).toEqual({ query: prompt, affordance: 'none', forceShapeId: 'core.summarize' });
  expect(dispatches()).toHaveLength(0);
  const body = await send(view);
  expect(body.shapeId).toBe('core.summarize'); expect(body.prompt).toBe(prompt);
  expect(body.selection).toEqual({ kind: 'search-trace', scope: 'hit', summary: 'sparse-retrieval #1 12.40 · cross-encoder #1 0.98' });
});
it('HO5 editing Explain instructions keeps its model trace inspectable in the submitted turn', async () => {
  const view = await mount();
  setPendingSelection({ kind: 'search-trace', scope: 'hit', summary: 'sparse-retrieval #2 3.50' });
  await navigate({ query: 'Explain this ranking', forceShapeId: 'core.summarize', docIds: [] });
  await drain(view);
  const c = await composer(view); const input = q<HTMLTextAreaElement>(c, 'sv3-composer-input')!;
  input.value = 'Explain in two sentences'; input.dispatchEvent(new Event('input')); await drain(view);
  const body = await send(view);
  expect(body.prompt).toBe('Explain in two sentences\n\nRanking trace:\nsparse-retrieval #2 3.50');
  const main = view.shadowRoot!.querySelector('jf-sv3-main') as ElementView;
  await main.updateComplete;
  expect(main.shadowRoot!.querySelector('[data-testid="sv3-turn-question"]')?.textContent).toBe(body.prompt);
});
it('HO4 HO13 a file-incompatible forced mode becomes a visibly file-limited Ask', async () => {
  const view = await mount();
  await navigate({ query: 'Who leads this project?', mode: 'ask', forceShapeId: 'core.free-chat', docIds: ['chosen.md'] });
  await drain(view); const notice = (await composer(view)).shadowRoot!.textContent;
  expect(dispatches()).toHaveLength(0);
  expect(view.shadowRoot!.querySelector('[data-testid=sv3-draft-review]')).not.toBeNull();
  view.shadowRoot!.querySelector<HTMLButtonElement>('[data-testid=sv3-draft-review-confirm]')!.click(); await drain(view);
  expect(dispatches()).toHaveLength(0);
  const body = await send(view);
  expect(body.shapeId).toBe('core.rag-ask'); expect(body.docIds).toEqual(['chosen.md']);
  expect(body.selection).toEqual({ kind: 'result-set', items: [{ id: 'chosen.md', kind: 'doc' }] });
  expect(notice).toMatch(/cannot read files.*Prepared in Ask/s);
});
it('HO8 two arrivals replace preparation without sending', async () => {
  const view = await mount();
  await navigate({ query: 'first', forceShapeId: 'core.summarize', docIds: ['first.md'] });
  await navigate({ query: 'later', forceShapeId: 'core.rag-ask', docIds: ['later.md'] });
  await drain(view); const c = await composer(view);
  expect(q<HTMLTextAreaElement>(c, 'sv3-composer-input')!.value).toBe('later');
  expect((await send(view)).docIds).toEqual(['later.md']);
});
it('HO8 an incoming query replaces typed Search without creating a conversation place', async () => {
  const view = await mount(); const c = await composer(view);
  const input = q<HTMLTextAreaElement>(c, 'sv3-composer-input')!;
  input.value = 'previous search'; input.dispatchEvent(new Event('input')); await drain(view);
  await navigate({ query: 'replacement search' }); await drain(view);
  expect(q<HTMLTextAreaElement>(await composer(view), 'sv3-composer-input')!.value).toBe('replacement search');
  expect((view as unknown as { sessions: { sessions: unknown[] } }).sessions.sessions).toHaveLength(0);
  expect(dispatches()).toHaveLength(0);
});
it('DS1 HO8 cold third-snapshot intake refuses without replacing the raw or typed draft', async () => {
  savedBackend(); const previous = await mount(); const oldComposer = await composer(previous);
  const input = q<HTMLTextAreaElement>(oldComposer, 'sv3-composer-input')!;
  input.value = 'draft before cold navigation'; input.dispatchEvent(new Event('input')); await drain(previous);
  window.dispatchEvent(new Event('pagehide')); previous.remove();
  __resetConversationListForTest();
  await navigate({ query: 'incoming cold task', forceShapeId: 'core.rag-ask', docIds: ['chosen.md'] });
  const view = await mount();
  expect(q<HTMLTextAreaElement>(await composer(view), 'sv3-composer-input')!.value).toBe('draft before cold navigation');
  expect(view.shadowRoot!.textContent).toContain('could not be saved safely');
  expect(sessionStorage.getItem('justsearch.draft.attachment.v1.search-v3.main')).toContain('draft before cold navigation');
  view.shadowRoot!.querySelector('jf-sv3-sidebar')!.dispatchEvent(new CustomEvent('sv3-session-select', {
    bubbles: true, composed: true, detail: { id: 'saved' },
  })); await drain(view);
  expect(q<HTMLTextAreaElement>(await composer(view), 'sv3-composer-input')!.value).toBe('draft before cold navigation');
  expect((await composer(view)).getAttribute('tier')).toBe('ask');
  expect(dispatches()).toHaveLength(0);
});
it('HO11 an answer link ignores agent affordance and conversation and waits', async () => {
  const view = await mount();
  const nav = createNavigationHandler({ isKnownSurface: () => true, setActiveSurface: () => {} });
  const router = createIntentRouter({ navigationHandler: nav, invocationHandler: { handle: vi.fn() },
    isKnownSurface: () => true, resolveSurface: id => ({ status: 'resolved', id }),
    resolveOperation: id => ({ status: 'resolved', id }), answerSurfaceId: 'core.search-v3-surface' });
  await router.dispatch({ transport: 'URL_DEEPLINK', address: { kind: 'answer', prompt: 'question', shape: 'core.agent-run', state: { affordance: 'agent', conversationId: 'unrelated' } } });
  await drain(view); const c = await composer(view);
  expect(c.getAttribute('tier')).toBe('ask');
  expect(dispatches()).toHaveLength(0);
  expect(fetchMock.mock.calls.some(c => String(c[0]).includes('/api/chat/agent'))).toBe(false);
  expect(c.shadowRoot!.textContent).toContain('Ask');
});
it('HO13 an empty file hand-off cannot widen to the whole index', async () => {
  const view = await mount();
  await navigate({ query: 'question', forceShapeId: 'core.rag-ask', docIds: [] });
  await drain(view); const c = await composer(view);
  expect(q<HTMLTextAreaElement>(c, 'sv3-composer-input')!.value).toBe('question');
  q<HTMLButtonElement>(c, 'sv3-composer-send')?.click(); await drain(view);
  expect(dispatches()).toHaveLength(0);
  expect(c.shadowRoot!.textContent).toMatch(/file|document/i);
});
const savedUserId = '11111111-2222-4333-8444-555555555550';
const savedAnswerId = '11111111-2222-4333-8444-555555555551';
function savedBackend(historyOverrides: Record<string, unknown> = {}, question = 'saved summary'): void {
  const previous = fetchMock.getMockImplementation()! as (url: unknown, init?: RequestInit) => Promise<unknown>;
  const messages = [
    { id: savedUserId, role: 'user', content: question, shapeId: 'core.summarize' },
    { id: savedAnswerId, role: 'assistant', content: answer, shapeId: 'core.summarize' },
  ];
  fetchMock.mockImplementation(async (url: unknown, init?: RequestInit) => {
    const href = String(url);
    const reply = (body: unknown): object => ({ ok: true, status: 200, json: async () => body });
    if (href.includes('/branch?')) return reply({ sessionId: 'fork' });
    if (href.includes('/history')) return reply({ sessionId: href.includes('/saved/') ? 'saved' : 'fork',
      messages: href.includes('/saved/') ? messages : [], ...historyOverrides });
    if (href.includes('/api/thread/')) return reply({ conversationId: 'saved', events: href.endsWith('/saved')
      ? messages.map((m, i) => ({ id: m.id, occurredAt: `2026-08-19T10:00:0${i}Z`,
          kind: i === 0 ? 'USER_MESSAGE' : 'ASSISTANT_MESSAGE', originator: m.role,
          content: m.content, attributes: { shapeId: m.shapeId, ...(question === savedExplainQuestion ? {selectionKind:'search-trace'} : {}) } })) : [] });
    if (href.endsWith('/api/chat/conversations') || href.includes('/api/chat/conversations?')) return reply({ sessions: [{ sessionId: 'saved',
      createdAtMs: 1, lastActiveAtMs: 2, messageCount: 2, firstUserMessage: question, shapeId: 'core.summarize' }] });
    return previous(url, init);
  });
  setLastViewedConversation('saved');
}


it('HO7 a reloaded summary Retry menu names the original summary inputs', async () => {
  savedBackend(); const menu = vi.spyOn(contextMenus, 'openContextMenu').mockResolvedValue(null);
  const view = await mount();
  const main = view.shadowRoot!.querySelector('jf-sv3-main') as ElementView; await main.updateComplete;
  const turnId = (q(main, 'sv3-turn-question')!.getAttribute('data-item-id') ?? '').replace(/:q$/, '');
  main.dispatchEvent(new CustomEvent('sv3-context-menu', { bubbles: true, composed: true, detail: { turnId, x: 1, y: 1 } }));
  await drain(view);
  const retry = menu.mock.calls[0]?.[0].actions.find(item => item.id === 'retry');
  expect(retry).toBeDefined();
  expect(retry!.label).toMatch(/Summarize.*original (files|input|passage)/i);
  expect(retry!.label).not.toContain('all files');
});
it.each(['retry', 'edit'])('HO7 a reloaded summary %s states its missing inputs and never sends an all-files Ask', async action => {
  savedBackend(); const view = await mount();
  const main = view.shadowRoot!.querySelector('jf-sv3-main') as ElementView; await main.updateComplete;
  const turnId = (q(main, 'sv3-turn-question')!.getAttribute('data-item-id') ?? '').replace(/:q$/, '');
  main.dispatchEvent(new CustomEvent('sv3-branch-action', { bubbles: true, composed: true,
    detail: { action, turnId, ...(action === 'edit' ? { text: 'edited summary' } : {}) } }));
  await drain(view); const c = await composer(view);
  expect(dispatches()).toHaveLength(0);
  expect(view.shadowRoot!.textContent).toMatch(/summary|summarize/i);
  expect(view.shadowRoot!.textContent).toMatch(/input|file|passage/i);
  expect(fetchMock.mock.calls.filter(c => String(c[0]).includes('/branch?'))).toHaveLength(0);
  expect(q<HTMLTextAreaElement>(c, 'sv3-composer-input')!.value).toBe('');
});
it('HO8 an outgoing conversation keeps its unsent draft and files one click away', async () => {
  const view = await mount(); await navigate({ conversationId: 'previous' }); await drain(view);
  const c = await composer(view); const input = q<HTMLTextAreaElement>(c, 'sv3-composer-input')!;
  input.value = 'unsent draft'; input.dispatchEvent(new Event('input'));
  (view as unknown as { search: { prepare(scope: { paths: string[] }): void } }).search.prepare({ paths: ['previous.md'] });
  await navigate({ query: 'incoming task', forceShapeId: 'core.summarize', docIds: ['incoming.md'] });
  await drain(view);
  expect(q<HTMLTextAreaElement>(await composer(view), 'sv3-composer-input')!.value).toBe('incoming task');
  const sidebar = view.shadowRoot!.querySelector('jf-sv3-sidebar')!;
  sidebar.dispatchEvent(new CustomEvent('sv3-session-select', { bubbles: true, composed: true, detail: { id: 'previous' } }));
  await drain(view);
  expect(q<HTMLTextAreaElement>(await composer(view), 'sv3-composer-input')!.value).toBe('unsent draft');
  expect((view as unknown as { search: { pendingScope: unknown } }).search.pendingScope).toEqual({ paths: ['previous.md'] });
  expect(dispatches()).toHaveLength(0);
});

it('HO9 abandoning a prepared selection cannot attach it to another mode send', async () => {
  const view = await mount();
  setPendingSelection({ kind: 'item', itemKind: 'browse-node', itemId: 'old.md' });
  await navigate({ query: 'question', forceShapeId: 'core.summarize' }); await drain(view);
  const c = await composer(view);
  c.dispatchEvent(new CustomEvent('sv3-tier-change', { bubbles: true, composed: true, detail: { tier: 'search' } }));
  c.dispatchEvent(new CustomEvent('sv3-tier-change', { bubbles: true, composed: true, detail: { tier: 'ask' } }));
  await drain(view);
  const body = await send(view);
  expect(body.shapeId).toBe('core.rag-ask'); expect(body.selection).toBeUndefined(); expect(body.docIds).toEqual([]);
});
it('HO9 abandoning a hand-off for an unvisited conversation clears its file scope', async () => {
  savedBackend(); sessionStorage.clear();
  await navigate({ query: 'incoming summary', forceShapeId: 'core.summarize', docIds: ['chosen.md'] });
  const view = await mount();
  expect(q<HTMLTextAreaElement>(await composer(view), 'sv3-composer-input')!.value).toBe('incoming summary');
  expect((view as unknown as { search: { pendingScope: unknown } }).search.pendingScope).toEqual({ paths: ['chosen.md'] });
  view.shadowRoot!.querySelector('jf-sv3-sidebar')!.dispatchEvent(new CustomEvent('sv3-session-select', {
    bubbles: true, composed: true, detail: { id: 'saved' },
  })); await drain(view);
  expect((view as unknown as { sessions: { activeId: string } }).sessions.activeId).toBe('saved');
  expect((view as unknown as { search: { pendingScope: unknown } }).search.pendingScope).toBeNull();
  const input = q<HTMLTextAreaElement>(await composer(view), 'sv3-composer-input')!;
  input.value = 'different question'; input.dispatchEvent(new Event('input')); await drain(view);
  const body = await send(view);
  expect(body.shapeId).toBe('core.rag-ask'); expect(body.selection).toBeUndefined(); expect(body.docIds).toEqual([]);
});
it('HO9 leaving the window retires prepared context while a layout reparent keeps it', async () => {
  const view = await mount();
  await navigate({ query: 'incoming summary', forceShapeId: 'core.summarize', docIds: ['chosen.md'] }); await drain(view);
  expect(q<HTMLTextAreaElement>(await composer(view), 'sv3-composer-input')!.value).toBe('incoming summary');
  view.remove(); document.body.append(view); await drain(view);
  expect((view as unknown as { search: { pendingScope: unknown } }).search.pendingScope).toEqual({ paths: ['chosen.md'] });
  view.remove(); await drain(view); document.body.append(view); await drain(view);
  const input = q<HTMLTextAreaElement>(await composer(view), 'sv3-composer-input')!;
  input.value = 'unrelated question after return'; input.dispatchEvent(new Event('input')); await drain(view);
  const body = await send(view);
  expect(body.shapeId).toBe('core.rag-ask'); expect(body.selection).toBeUndefined(); expect(body.docIds).toEqual([]);
});
it('HO10 unavailable chat still shows the prepared task and chosen files', async () => {
  const view = await mount();
  __feedForTest({ inference: { mode: 'unavailable', available: false } as never });
  await navigate({ query: 'summarize the risks', forceShapeId: 'core.summarize', docIds: ['chosen.md'] });
  await drain(view); const c = await composer(view);
  expect(q<HTMLTextAreaElement>(c, 'sv3-composer-input')!.value).toBe('summarize the risks');
  expect(c.shadowRoot!.textContent).toContain('chosen.md');
  expect(q(c, 'sv3-composer-notice')?.textContent?.trim()).toBeTruthy();
  expect(dispatches()).toHaveLength(0);
});
it('HO12 an incoming task puts keyboard focus in the composer', async () => {
  const view = await mount(); const elsewhere = document.createElement('input'); document.body.append(elsewhere); elsewhere.focus();
  await navigate({ query: 'question', forceShapeId: 'core.rag-ask', docIds: ['chosen.md'] }); await drain(view);
  const c = await composer(view);
  expect(c.shadowRoot!.activeElement).toBe(q(c, 'sv3-composer-input'));
});
it('HO13 malformed selection data is refused visibly without a crash', async () => {
  const view = await mount(); setPendingSelection({ kind: 'text-range' } as never);
  await expect(navigate({ query: 'question', forceShapeId: 'core.summarize' })).resolves.toBeUndefined(); await drain(view);
  const c = await composer(view);
  expect(c.shadowRoot!.textContent).toContain('Invalid selection');
  expect(dispatches()).toHaveLength(0);
});

it('HO8 a streaming Ask stays live and keeps its draft and files across a hand-off', async () => {
  let stream!: ReadableStreamDefaultController<Uint8Array>;
  const previous = fetchMock.getMockImplementation()! as (url: unknown, init?: RequestInit) => Promise<unknown>;
  fetchMock.mockImplementation(async (url: unknown, init?: RequestInit) => {
    if (!String(url).includes('/api/chat/dispatch')) return previous(url, init);
    return new Response(new ReadableStream<Uint8Array>({ start(c) { stream = c; } }));
  });
  const view = await mount();
  const c = await composer(view);
  c.dispatchEvent(new CustomEvent('sv3-tier-change', { bubbles: true, composed: true, detail: { tier: 'ask' } }));
  await drain(view);
  const input = q<HTMLTextAreaElement>(c, 'sv3-composer-input')!;
  input.value = 'first question'; input.dispatchEvent(new Event('input')); await drain(view);
  const body = await send(view); const id = body.conversationId as string;
  input.value = 'unfinished follow-up'; input.dispatchEvent(new Event('input'));
  (view as unknown as { search: { prepare(scope: { paths: string[] }): void } }).search.prepare({ paths: ['kept.md'] });
  await navigate({ query: 'new task', forceShapeId: 'core.summarize', docIds: ['new.md'] }); await drain(view);
  expect(q(await composer(view), 'sv3-composer-stop')).toBeNull();
  const signal = (dispatches()[0]![1] as RequestInit).signal!;
  expect(signal.aborted).toBe(false);
  view.shadowRoot!.querySelector('jf-sv3-sidebar')!.dispatchEvent(new CustomEvent('sv3-session-select', {
    bubbles: true, composed: true, detail: { id },
  })); await drain(view);
  expect(q<HTMLTextAreaElement>(await composer(view), 'sv3-composer-input')!.value).toBe('unfinished follow-up');
  expect(q(await composer(view), 'sv3-composer-stop')).not.toBeNull();
  expect((view as unknown as { search: { pendingScope: unknown } }).search.pendingScope).toEqual({ paths: ['kept.md'] });
  expect(signal.aborted).toBe(false);
  stream.enqueue(new TextEncoder().encode('event: chunk\ndata: {"text":"still running"}\n\nevent: done\ndata: {}\n\n')); stream.close();
  await drain(view);
  expect(q(await composer(view), 'sv3-composer-stop')).toBeNull();
  expect(dispatches()).toHaveLength(1);
});

it('HO8 a held real controller keeps its decision and reopens from the sidebar', async () => {
  const view = await mount();
  const ctrl = getAgentSessionController('');
  ctrl.isStreaming = true; ctrl.sessionId = 'run-held'; ctrl.conversationId = 'held';
  ctrl.budgetGate = { tokensNeeded: 100, tokensRemaining: 0, totalTokensConsumed: 100 };
  const resolve = vi.spyOn(ctrl, 'resolveBudgetGate'); const stop = vi.spyOn(ctrl, 'cancelSession');
  (view as unknown as { runDomain: { onAgentUpdate(): void } }).runDomain.onAgentUpdate();
  await navigate({ conversationId: 'held' }); await drain(view);
  await navigate({ query: 'prepared task', forceShapeId: 'core.rag-ask', docIds: ['chosen.md'] }); await drain(view);
  const c = await composer(view);
  expect(q<HTMLTextAreaElement>(c, 'sv3-composer-input')!.value).toBe('prepared task');
  expect(q(c, 'sv3-composer-answer')).toBeNull();
  expect(ctrl.budgetGate).not.toBeNull(); expect(resolve).not.toHaveBeenCalled(); expect(stop).not.toHaveBeenCalled();
  view.shadowRoot!.querySelector('jf-sv3-sidebar')!.dispatchEvent(new CustomEvent('sv3-session-select', {
    bubbles: true, composed: true, detail: { id: 'held' },
  })); await drain(view);
  const main = view.shadowRoot!.querySelector('jf-sv3-main') as ElementView; await main.updateComplete;
  expect(q(main, 'sv3-run-prompt')).not.toBeNull();
  expect(ctrl.budgetGate).not.toBeNull(); expect(resolve).not.toHaveBeenCalled();
});
it('HO8 a locked conversation is left intact by incoming preparation', async () => {
  const view = await mount(); await navigate({ conversationId: 'locked' }); await drain(view);
  __feedForTest({ status: { conversationProtection: { state: 'locked' } } as unknown as StatusSnapshot });
  await navigate({ query: 'keep this instruction', forceShapeId: 'core.summarize', docIds: ['chosen.md'] }); await drain(view);
  const c = await composer(view);
  expect(q<HTMLTextAreaElement>(c, 'sv3-composer-input')!.value).toBe('keep this instruction');
  expect(c.shadowRoot!.textContent).toContain('chosen.md'); expect(dispatches()).toHaveLength(0);
  expect(fetchMock.mock.calls.filter(call => (call[1] as RequestInit | undefined)?.method === 'POST')).toHaveLength(0);
});
it.each(['unchanged', 'identical assignment', 'A-B-A', 'newer input', 'composition start'])(
  'CP5 a failed Ask handoff restores only the unchanged post-send draft: %s', async change => {
    const previous = fetchMock.getMockImplementation()! as (url: unknown, init?: RequestInit) => Promise<unknown>;
    let release!: (response: Response) => void;
    fetchMock.mockImplementation(async (url: unknown, init?: RequestInit) =>
      String(url).includes('/api/chat/dispatch') ? new Promise<Response>(resolve => { release = resolve; }) : previous(url, init));
    const view = await mount();
    await navigate({ query: 'Only summarize risks', forceShapeId: 'core.summarize', docIds: ['deleted.md'] });
    await drain(view); await send(view);
    const c = await composer(view) as Sv3Composer;
    const input = q<HTMLTextAreaElement>(c, 'sv3-composer-input')!;
    expect(input.value).toBe('');
    if (change === 'identical assignment') c.draft = '';
    if (change === 'A-B-A') { c.draft = 'B'; c.draft = ''; }
    if (change === 'newer input') { input.value = 'newer B'; input.dispatchEvent(new Event('input')); }
    if (change === 'composition start') input.dispatchEvent(new Event('compositionstart'));
    release(new Response('event: error\ndata: {"errorCode":"DOC_UNAVAILABLE","error":"Cannot read selected file"}\n\n'));
    await drain(view); await c.updateComplete;
    expect(input.value).toBe(change === 'unchanged' ? 'Only summarize risks' : change === 'newer input' ? 'newer B' : '');
  });

it('HO13 a refused unreadable-document hand-off keeps the instruction and files', async () => {
  const previous = fetchMock.getMockImplementation()! as (url: unknown, init?: RequestInit) => Promise<unknown>;
  fetchMock.mockImplementation(async (url: unknown, init?: RequestInit) => {
    if (!String(url).includes('/api/chat/dispatch')) return previous(url, init);
    return new Response('event: error\ndata: {"errorCode":"DOC_UNAVAILABLE","error":"Cannot read selected file: deleted.md"}\n\n');
  });
  const view = await mount(); await navigate({ query: 'Only summarize risks', forceShapeId: 'core.summarize', docIds: ['deleted.md'] });
  await drain(view); await send(view); const c = await composer(view);
  expect(q<HTMLTextAreaElement>(c, 'sv3-composer-input')!.value).toBe('Only summarize risks');
  expect(c.shadowRoot!.textContent).toContain('deleted.md');
  const main = view.shadowRoot!.querySelector('jf-sv3-main') as ElementView; await main.updateComplete;
  expect(main.shadowRoot!.textContent).toContain('Cannot read selected file');
});
it('HO3 D7 a Structured hand-off with no files uses typed text and waits for send', async () => {
  const view = await mount(); await navigate({ query: 'Extract owner: Lin', forceShapeId: 'core.extract', docIds: [] }); await drain(view);
  const c = await composer(view); const schema = q<HTMLTextAreaElement>(c, 'sv3-composer-schema')!;
  expect(schema).not.toBeNull();
  schema.value = '{"type":"object"}'; schema.dispatchEvent(new Event('input')); await drain(view);
  expect(c.shadowRoot!.textContent).toContain('typed text'); expect(dispatches()).toHaveLength(0);
  const body = await send(view); expect(body.shapeId).toBe('core.extract'); expect(body.selection).toBeUndefined();
});
it('HO12 keyboard palette search after a hand-off returns focus to the composer', async () => {
  const view = await mount(); await navigate({ query: 'Northfield', forceShapeId: 'core.summarize', docIds: ['chosen.md'] }); await drain(view);
  const c = await composer(view); const input = q<HTMLTextAreaElement>(c, 'sv3-composer-input')!; input.focus();
  input.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', ctrlKey: true, bubbles: true, composed: true })); await drain(view);
  const palette = view.shadowRoot!.querySelector('jf-sv3-palette') as ElementView;
  const field = q<HTMLInputElement>(palette, 'sv3-palette-input'); expect(field).not.toBeNull();
  field!.value = 'Search everything'; field!.dispatchEvent(new Event('input')); await drain(view);
  field!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, composed: true })); await drain(view);
  const current = await composer(view);
  expect(current.getAttribute('tier')).toBe('search');
  expect(current.shadowRoot!.activeElement).toBe(q(current, 'sv3-composer-input'));
  expect(dispatches()).toHaveLength(0);
});


const savedExplainQuestion = 'Explain why this result ("chosen.md") ranked where it did.\n\nRanking trace:\nsparse #1 0.75';
it('HO5 a reloaded Explain Retry menu states its actual restoration limit', async () => {
  savedBackend({}, savedExplainQuestion);
  const menu = vi.spyOn(contextMenus, 'openContextMenu').mockResolvedValue(null);
  const view = await mount();
  const main = view.shadowRoot!.querySelector('jf-sv3-main') as ElementView;await main.updateComplete;
  const turnId = (q(main, 'sv3-turn-question')!.getAttribute('data-item-id') ?? '').replace(/:q$/, '');
  main.dispatchEvent(new CustomEvent('sv3-context-menu', {bubbles: true, composed: true, detail: {turnId, x: 1, y: 1}}));
  await drain(view);
  const retry = menu.mock.calls[0]?.[0].actions.find(item => item.id === 'retry');
  expect(retry?.label).toContain('After reload, start Explain from the result again.');
  expect(retry?.label).not.toContain('Summarize');
});
it.each(['retry', 'edit'])('DS1 HO5 a reloaded Explain %s refuses before fork and preserves unrelated B', async action => {
  savedBackend({}, savedExplainQuestion);const view = await mount();
  const main = view.shadowRoot!.querySelector('jf-sv3-main') as ElementView;await main.updateComplete;
  const turnId = (q(main, 'sv3-turn-question')!.getAttribute('data-item-id') ?? '').replace(/:q$/, '');
  const input = q<HTMLTextAreaElement>(await composer(view), 'sv3-composer-input')!;
  input.value = 'unrelated B'; input.dispatchEvent(new Event('input')); await drain(view);
  const text = action === 'edit' ? savedExplainQuestion.replace('Explain why', 'Explain briefly why') : savedExplainQuestion;
  main.dispatchEvent(new CustomEvent('sv3-branch-action', {bubbles: true, composed: true, detail: {action, turnId, ...(action === 'edit' ? {text} : {})}}));
  await drain(view);const c = await composer(view);
  expect(view.shadowRoot!.textContent).toContain('Retry/Edit cannot restore the original Explain input after reload. Start Explain from the result again.');
  expect(q<HTMLTextAreaElement>(c, 'sv3-composer-input')!.value).toBe('unrelated B');
  expect(fetchMock.mock.calls.filter(c => String(c[0]).includes('/branch?'))).toHaveLength(0);
  expect(dispatches()).toHaveLength(0);
});

it('merge model action after a no-match hand-off preserves the prepared draft and excludes its selection', async () => {
  const previous = fetchMock.getMockImplementation()! as (...args: unknown[]) => Promise<unknown>;
  let first = true;
  fetchMock.mockImplementation(async (...args: unknown[]) => {
    if (String(args[0]).includes('/api/chat/dispatch') && first) {
      first = false;
      return new Response('event: error\ndata: {"errorCode":"NO_CONTENT","error":"No matching documents found in the index"}\n\n');
    }
    return previous(...args);
  });
  const selected = {kind: 'search-trace', scope: 'hit', summary: 'sparse #1 0.75'} as const;
  setPendingSelection(selected);await navigate({query: 'no matching files', forceShapeId: 'core.rag-ask'});
  const view = await mount();await send(view);
  const c = await composer(view);const draft = q<HTMLTextAreaElement>(c, 'sv3-composer-input')!.value;
  const main = view.shadowRoot!.querySelector('jf-sv3-main') as ElementView;await main.updateComplete;
  const model = q<HTMLButtonElement>(main, 'sv3-answer-without-files');expect(model).not.toBeNull();
  model!.click();await drain(view);
  expect(dispatches()).toHaveLength(2);
  const body = JSON.parse((dispatches()[1]![1] as RequestInit).body as string);
  expect(body.shapeId).toBe('core.free-chat');expect(body).not.toHaveProperty('selection');
  expect(body).not.toHaveProperty('context');expect(body).not.toHaveProperty('docIds');
  expect(q<HTMLTextAreaElement>(await composer(view), 'sv3-composer-input')!.value).toBe(draft);
  expect((view as unknown as { draftDomain: {preparedHandoff: unknown} }).draftDomain.preparedHandoff).toEqual({shapeId: 'core.rag-ask', selection: selected});
});


it('merge an unscoped Summarize input failure is not a no-matching-files result', async () => {
  const previous = fetchMock.getMockImplementation()! as (...args: unknown[]) => Promise<unknown>;
  fetchMock.mockImplementation(async (...args: unknown[]) => String(args[0]).includes('/api/chat/dispatch')
    ? new Response('event: error\ndata: {"errorCode":"NO_CONTENT","error":"Document has no content"}\n\n') : previous(...args));
  setPendingSelection({kind: 'search-trace', scope: 'hit', summary: 'sparse #1 0.75'});
  await navigate({query: 'Explain ranking', forceShapeId: 'core.summarize'});
  const view = await mount();await send(view);
  const main = view.shadowRoot!.querySelector('jf-sv3-main') as ElementView;await main.updateComplete;
  expect(q(main, 'sv3-no-evidence')).toBeNull();expect(q(main, 'sv3-answer-without-files')).toBeNull();
  expect(q(main, 'sv3-turn')?.getAttribute('data-status')).toBe('failed');
});

it.each([{label:'inline trace',instruction:'Explain in one sentence. Trace: sparse #2 12.40'},{label:'shortened',instruction:'Briefly.'},{label:'different question',instruction:'Why did this rank first?'},{label:'emptied',instruction:''}])('HO5 edited Explain keeps selection identity before send: $label', async ({instruction}) => {
  const selection = {kind: 'search-trace' as const, scope: 'hit' as const, summary: 'sparse #2 12.40', docId: 'docs/a.md'};
  setPendingSelection(selection);await navigate({query:'Explain this result.\n\nRanking trace:\nsparse #2 12.40',forceShapeId:'core.summarize',docIds:[]});
  const view=await mount();const c=await composer(view);const input=q<HTMLTextAreaElement>(c,'sv3-composer-input')!;
  input.value=instruction;input.dispatchEvent(new Event('input',{bubbles:true,composed:true}));await drain(view);
  if(instruction==='') {
    q<HTMLButtonElement>(c,'sv3-composer-send')!.click();await drain(view);
    expect(dispatches()).toHaveLength(0);expect((view as unknown as { draftDomain: {preparedHandoff:{selection:unknown}} }).draftDomain.preparedHandoff.selection).toEqual(selection);
  } else {
    const body=await send(view);expect(body).toMatchObject({shapeId:'core.summarize',selection});
    const main=view.shadowRoot!.querySelector('jf-sv3-main') as HTMLElement & {turns: readonly {selectionKind?:string}[]};
    expect(main.turns[0]?.selectionKind).toBe('search-trace');
  }
});

it('C4 A1 transfers the legacy draft durably once and never resurrects it after Clear', async () => {
  localStorage.setItem('justsearch.draft.unified-chat.composer', 'legacy unsent');
  const view = await mount();
  expect(q<HTMLTextAreaElement>(await composer(view), 'sv3-composer-input')!.value).toBe('legacy unsent');
  expect(localStorage.getItem('justsearch.draft.search-v3.composer')).toBe('legacy unsent');
  expect(localStorage.getItem('justsearch.draft.unified-chat.composer')).toBeNull();
  const input = q<HTMLTextAreaElement>(await composer(view), 'sv3-composer-input')!;
  input.value = ''; input.dispatchEvent(new Event('input')); await drain(view); view.remove();
  // A stale previous-version tab cannot make a disposed draft return on restart.
  localStorage.setItem('justsearch.draft.unified-chat.composer', 'legacy unsent');
  const next = await mount();
  expect(q<HTMLTextAreaElement>(await composer(next), 'sv3-composer-input')!.value).toBe('');
});
it('C4 A1 keeps an existing Search draft and permanently leaves the legacy value untouched', async () => {
  localStorage.setItem('justsearch.draft.unified-chat.composer', 'legacy unsent');
  localStorage.setItem('justsearch.draft.search-v3.composer', 'Search draft');
  const view = await mount();
  expect(q<HTMLTextAreaElement>(await composer(view), 'sv3-composer-input')!.value).toBe('Search draft');
  const input = q<HTMLTextAreaElement>(await composer(view), 'sv3-composer-input')!;
  input.value = ''; input.dispatchEvent(new Event('input')); await drain(view); view.remove();
  const next = await mount();
  expect(q<HTMLTextAreaElement>(await composer(next), 'sv3-composer-input')!.value).toBe('');
  expect(localStorage.getItem('justsearch.draft.unified-chat.composer')).toBe('legacy unsent');
});
it('C4 A1 defers legacy transfer for an explicit arrival until a later clean mount', async () => {
  localStorage.setItem('justsearch.draft.unified-chat.composer', 'legacy unsent');
  await navigate({ query: 'incoming task', mode: 'ask' }); const view = await mount();
  expect(q<HTMLTextAreaElement>(await composer(view), 'sv3-composer-input')!.value).toBe('incoming task');
  expect(localStorage.getItem('justsearch.draft.unified-chat.composer')).toBe('legacy unsent');
  const input = q<HTMLTextAreaElement>(await composer(view), 'sv3-composer-input')!;
  input.value = ''; input.dispatchEvent(new Event('input')); await drain(view); view.remove();
  const next = await mount();
  expect(q<HTMLTextAreaElement>(await composer(next), 'sv3-composer-input')!.value).toBe('legacy unsent');
});
it('C4 A1 a failed durable write leaves the legacy draft recoverable', async () => {
  localStorage.setItem('justsearch.draft.unified-chat.composer', 'legacy unsent');
  const original = localStorage.setItem.bind(localStorage);
  const write = vi.spyOn(localStorage, 'setItem').mockImplementation((key, value) => {
    if (key === 'justsearch.draft.search-v3.composer') throw new Error('quota');
    original(key, value);
  });
  const view = await mount();
  expect(localStorage.getItem('justsearch.draft.unified-chat.composer')).toBe('legacy unsent');
  write.mockRestore(); view.remove();
  const next = await mount();
  expect(q<HTMLTextAreaElement>(await composer(next), 'sv3-composer-input')!.value).toBe('legacy unsent');
});
it.each([
  ['core.rag-ask', 'ask'], ['core.free-chat', 'ask'], ['core.extract', 'structured'],
])('C8 a compatibility mount for %s prepares its mode without sending', async (shape, tier) => {
  const view = document.createElement('jf-sv3-window') as SearchV3View;
  view.setAttribute('shape-id', shape); document.body.append(view); await drain(view);
  expect((await composer(view)).getAttribute('tier')).toBe(tier);
  expect(dispatches()).toHaveLength(0);
});
it('C8 a workflow compatibility mount announces its unavailable disposition', async () => {
  const view = document.createElement('jf-sv3-window') as SearchV3View;
  view.setAttribute('shape-id', 'core.workflow-run'); document.body.append(view); await drain(view);
  expect((await composer(view)).shadowRoot!.textContent).toContain('Workflow runs are not available here');
  expect((await composer(view)).getAttribute('tier')).toBe('search');
  expect(dispatches()).toHaveLength(0);
});
const invalidCutoverTasks: Record<string, string | string[]>[] = [
  { query: 'keep text', mode: ['ask', 'search'] },
  { query: 'keep text', forceShapeId: [] },
  { query: 'keep text', docIds: ['', 'chosen.md'] },
];
it.each(invalidCutoverTasks)('C2 A2 visibly refuses invalid task data %j without losing its text or widening scope', async state => {
  const view = await mount(); await navigate(state); await drain(view); const c = await composer(view);
  expect(q<HTMLTextAreaElement>(c, 'sv3-composer-input')!.value).toBe('keep text');
  expect(c.shadowRoot!.textContent).toMatch(/Invalid|Unknown|No readable/);
  q<HTMLButtonElement>(c, 'sv3-composer-send')!.click(); await drain(view);
  expect(dispatches()).toHaveLength(0);
});

it('C9 A3 the shared inspector opens one reader, keeps the passage and follows close and return', async () => {
  const view = await mount();
  setSelected({ id: 'read.md', title: 'Read', path: 'read.md', highlightStartLine: 3, highlightEndLine: 5 });
  await drain(view);
  const pane = q<ElementView>(view, 'sv3-pane');
  expect(pane).not.toBeNull();
  await pane!.updateComplete;
  const reader = pane!.shadowRoot!.querySelector('jf-document-pane') as HTMLElement & { docPath: string; highlightRange: unknown };
  expect(reader.docPath).toBe('read.md');
  expect(reader.highlightRange).toEqual({ startLine: 3, endLine: 5 });
  expect(view.shadowRoot!.querySelectorAll('jf-sv3-pane')).toHaveLength(1);
  view.remove(); document.body.append(view); await drain(view);
  expect(q(view, 'sv3-pane')).not.toBeNull();
  q(view, 'sv3-pane')!.dispatchEvent(new CustomEvent('sv3-pane-close', { bubbles: true, composed: true }));
  await drain(view);
  expect(getInspectorState().isOpen).toBe(false);
  expect(q(view, 'sv3-pane')).toBeNull();
  setInspectorOpen(true); await drain(view);
  expect(q(view, 'sv3-pane')).not.toBeNull();
});

it('C8 W3 a workflow answer link stays unprepared and announces its unavailable disposition', async () => {
  const view = await mount();
  await navigate({ query: 'keep my instruction', forceShapeId: 'core.workflow-run' });
  await drain(view); const c = await composer(view);
  expect(c.getAttribute('tier')).toBe('search');
  expect(q<HTMLTextAreaElement>(c, 'sv3-composer-input')!.value).toBe('keep my instruction');
  expect(c.shadowRoot!.textContent).toContain('Workflow runs are not available here');
  expect(fetchMock.mock.calls.filter(call => String(call[0]).includes('/api/knowledge/search'))).toHaveLength(0);
  expect(dispatches()).toHaveLength(0);
});

it('C7 C8 an outgoing compatibility view cannot consume the main window arrival', async () => {
  const compatibility = document.createElement('jf-sv3-window') as SearchV3View;
  compatibility.setAttribute('shape-id', 'core.extract');
  document.body.append(compatibility); await drain(compatibility);
  await navigate({ query: 'keep my incoming task', forceShapeId: 'core.workflow-run' });
  compatibility.remove();
  const view = await mount(); const c = await composer(view);
  expect(q<HTMLTextAreaElement>(c, 'sv3-composer-input')!.value).toBe('keep my incoming task');
  expect(c.shadowRoot!.textContent).toContain('Workflow runs are not available here');
  expect(dispatches()).toHaveLength(0);
});

it('C9 A3 a shared line passage is highlighted without inventing a missing citation position', async () => {
  fetchMock.mockResolvedValue({ ok: true, status: 200, json: async () => ({ content: '# Heading\nSelected passage\n' }) });
  const view = await mount();
  setSelected({ id: 'read.md', title: 'Read', path: 'read.md', highlightStartLine: 1, highlightEndLine: 1 });
  await drain(view);
  const pane = q<ElementView>(view, 'sv3-pane'); expect(pane).not.toBeNull(); await pane!.updateComplete;
  const reader = pane!.shadowRoot!.querySelector('jf-document-pane') as ElementView; await drain(reader);
  expect(reader.shadowRoot!.querySelector('.hl-strong, .hl-weak')?.textContent).toContain('Selected passage');
  expect(reader.shadowRoot!.textContent).not.toContain('nothing is highlighted');
});

it('C9 A3 changing the address place keeps the shared reader until explicitly closed', async () => {
  const view = await mount();
  setSelected({ id: 'read.md', title: 'Read', path: 'read.md' }); await drain(view);
  expect(q(view, 'sv3-pane')).not.toBeNull();
  await navigate({ conversationId: 'other-conversation' }); await drain(view);
  expect(getInspectorState().isOpen).toBe(true);
  expect(q(view, 'sv3-pane')).not.toBeNull();
});

it('C4 A1 a first clean mount with no legacy text cannot later revive a stale old tab draft', async () => {
  const view = await mount(); view.remove();
  localStorage.setItem('justsearch.draft.unified-chat.composer', 'late stale legacy draft');
  const next = await mount();
  expect(q<HTMLTextAreaElement>(await composer(next), 'sv3-composer-input')!.value).toBe('');
  expect(localStorage.getItem('justsearch.draft.unified-chat.composer')).toBe('late stale legacy draft');
});

it('C4 A1 C7 a retained window cannot lose its migrated draft when disconnected before its next render', async () => {
  localStorage.setItem('justsearch.draft.unified-chat.composer', 'recover once');
  await navigate({ query: 'incoming task', mode: 'ask' });
  const view = await mount();
  const input = q<HTMLTextAreaElement>(await composer(view), 'sv3-composer-input')!;
  input.value = ''; input.dispatchEvent(new Event('input')); await drain(view); view.remove();
  document.body.append(view); // retained composer exists; migration must own it synchronously
  view.remove(); // lifecycle flush before the scheduled render
  const next = await mount();
  expect(q<HTMLTextAreaElement>(await composer(next), 'sv3-composer-input')!.value).toBe('recover once');
  expect(localStorage.getItem('justsearch.draft.search-v3.composer')).toBe('recover once');
});

it('DS1 allows two whole same-text proposals, refusing a third before replacement or a request', async () => {
  const view = await mount();
  await navigate({ query: 'same text', mode: 'ask', docIds: ['alpha.md'] }); await drain(view);
  await navigate({ query: 'same text', mode: 'ask', docIds: ['beta.md'] }); await drain(view);
  const key = 'justsearch.draft.attachment.v1.search-v3.main';
  const before = sessionStorage.getItem(key)!;
  const drafts = JSON.parse(before).payload.drafts;
  expect(drafts).toHaveLength(2);
  expect(drafts.map((d: { preparation: { scope: { paths: string[] } } }) => d.preparation.scope.paths[0]).sort()).toEqual(['alpha.md', 'beta.md']);
  await navigate({ query: 'third C', mode: 'ask', docIds: ['gamma.md'] }); await drain(view);
  expect(sessionStorage.getItem(key)).toBe(before);
  expect(q<HTMLTextAreaElement>(await composer(view), 'sv3-composer-input')!.value).toBe('same text');
  expect((view as unknown as { search: { pendingScope: unknown } }).search.pendingScope).toEqual({ paths: ['beta.md'] });
  expect(view.shadowRoot!.textContent).toContain('alpha.md');
  expect(view.shadowRoot!.textContent).toContain('could not be saved safely');
  expect(dispatches()).toHaveLength(0);
  expect(fetchMock.mock.calls.filter(c => String(c[0]).includes('/branch?'))).toHaveLength(0);
});

it('DS1 ordinary compatibility model tier changes retain the actual no-retrieval proposal before any leave', async () => {
  const view = document.createElement('jf-sv3-window') as SearchV3View;
  view.setAttribute('shape-id', 'core.free-chat'); document.body.append(view); await drain(view);
  const c = await composer(view); const input = q<HTMLTextAreaElement>(c, 'sv3-composer-input')!;
  input.value = 'typed model A'; input.dispatchEvent(new Event('input')); await drain(view);
  for (const tier of ['search', 'ask']) {
    c.dispatchEvent(new CustomEvent('sv3-tier-change', { bubbles: true, composed: true, detail: { tier } })); await drain(view);
  }
  const bytes = sessionStorage.getItem('justsearch.draft.attachment.v1.search-v3.core.free-chat')!;
  expect(JSON.parse(bytes).payload.drafts[0].preparation).toEqual({ kind: 'model' });
  expect(input.value).toBe('typed model A'); expect(dispatches()).toHaveLength(0);
  const body = await send(view); expect(body.shapeId).toBe('core.free-chat'); expect(body.docIds).toBeUndefined();
});

it('DS1 rejected fresh hash intake preserves live conversation B and both snapshots before traversal clearing', async () => {
  const view = await mount(); await navigate({ conversationId: 'previous' }); await drain(view);
  const input = q<HTMLTextAreaElement>(await composer(view), 'sv3-composer-input')!;
  input.value = 'live conversation B'; input.dispatchEvent(new Event('input')); await drain(view);
  await navigate({ query: 'retained A', mode: 'ask', docIds: ['alpha.md'] }); await drain(view);
  view.shadowRoot!.querySelector('jf-sv3-sidebar')!.dispatchEvent(new CustomEvent('sv3-session-select', { bubbles: true, composed: true, detail: { id: 'previous' } })); await drain(view);
  expect(input.value).toBe('live conversation B');
  window.dispatchEvent(new Event('pagehide')); await drain(view);
  const key = 'justsearch.draft.attachment.v1.search-v3.main'; const before = sessionStorage.getItem(key);
  await createNavigationHandler({ isKnownSurface: () => true, setActiveSurface: () => {} }).handle(
    { kind: 'navigate', target: 'core.search-v3-surface', state: { query: 'third C', mode: 'ask', docIds: ['gamma.md'] } },
    { push: false, taskOrigin: 'fresh' }); await drain(view);
  expect(input.value).toBe('live conversation B'); expect(sessionStorage.getItem(key)).toBe(before);
  expect((view as unknown as { sessions: { activeId: string } }).sessions.activeId).toBe('previous');
  expect(dispatches()).toHaveLength(0); expect(fetchMock.mock.calls.filter(c => String(c[0]).includes('/branch?'))).toHaveLength(0);
});

it.each(['live', 'review'])('DS1 bare history restoration cannot retire %s B when both existing recovery slots are needed', async review => {
  const view = await mount(); await navigate({ conversationId: 'previous' }); await drain(view);
  const input = q<HTMLTextAreaElement>(await composer(view), 'sv3-composer-input')!;
  input.value = 'live conversation B'; input.dispatchEvent(new Event('input')); await drain(view);
  await navigate({ query: 'retained A', mode: 'ask', docIds: ['alpha.md'] }); await drain(view);
  view.shadowRoot!.querySelector('jf-sv3-sidebar')!.dispatchEvent(new CustomEvent('sv3-session-select', { bubbles: true, composed: true, detail: { id: 'previous' } })); await drain(view);
  window.dispatchEvent(new Event('pagehide')); await drain(view);
  const key = 'justsearch.draft.attachment.v1.search-v3.main'; const before = sessionStorage.getItem(key);
  await createNavigationHandler({ isKnownSurface: () => true, setActiveSurface: () => {} }).handle(
    { kind: 'navigate', target: 'core.search-v3-surface', state: review === 'review' ? { query: 'old task A' } : {} }, { push: false, taskOrigin: 'restore' }); await drain(view);
  expect(input.value).toBe('live conversation B'); expect(sessionStorage.getItem(key)).toBe(before);
  expect(dispatches()).toHaveLength(0);
});


it('DS1 one-slot automatic hero navigation retains B at its origin while explicit New retains current B', async () => {
  const view = await mount(); await navigate({ conversationId: 'previous' }); await drain(view);
  const input = q<HTMLTextAreaElement>(await composer(view), 'sv3-composer-input')!;
  input.value = 'origin B'; input.dispatchEvent(new Event('input')); await drain(view);
  await createNavigationHandler({ isKnownSurface: () => true, setActiveSurface: () => {} }).handle(
    { kind: 'navigate', target: 'core.search-v3-surface', state: {} }, { push: false, taskOrigin: 'restore' }); await drain(view);
  expect(input.value).toBe('');
  const key = 'justsearch.draft.attachment.v1.search-v3.main';
  expect(JSON.parse(sessionStorage.getItem(key)!).payload.drafts).toEqual(expect.arrayContaining([expect.objectContaining({ place: 'previous', text: 'origin B' })]));
  const sidebar = view.shadowRoot!.querySelector('jf-sv3-sidebar')!;
  sidebar.dispatchEvent(new CustomEvent('sv3-session-select', { bubbles: true, composed: true, detail: { id: 'previous' } })); await drain(view);
  expect(input.value).toBe('origin B');
  sidebar.dispatchEvent(new CustomEvent('sv3-session-new', { bubbles: true, composed: true })); await drain(view);
  expect(input.value).toBe(''); expect(sessionStorage.getItem(key)).toContain('origin B');
  sidebar.dispatchEvent(new CustomEvent('sv3-session-select', { bubbles: true, composed: true, detail: { id: 'previous' } })); await drain(view);
  expect(input.value).toBe('origin B'); expect(dispatches()).toHaveLength(0);
});
