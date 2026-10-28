// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import { __feedContactForTest, __feedForTest, __resetAiStateForTest } from '../../state/aiStateStore.js';
import { __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';
import { resetSearchState } from '../../state/searchState.js';

import { resetInspectorState } from '../../state/inspectorState.js';
import { __resetAgentSessionStore } from '../../state/agentSessionStore.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';

import { registerCoreStores } from '../../router/bootstrap.js';
import { createNavigationHandler } from '../../router/navigationHandler.js';
import { deactivateProjection } from '../../router/URLProjector.js';
import { takePendingSelection, } from '../../utils/compose.js';

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

async function prepared(): Promise<SearchV3View> {
  const view = await mount();
  await navigate({ query: 'Only this incoming question', forceShapeId: 'core.summarize', docIds: ['chosen.md'] });
  await drain(view);
  return view;
}
async function leaveAndReturn(view: SearchV3View): Promise<void> {
  view.remove(); await drain(view); document.body.append(view); await drain(view);
}
function input(c: ElementView): HTMLTextAreaElement { return q<HTMLTextAreaElement>(c, 'sv3-composer-input')!; }
function type(c: ElementView, value: string): void { input(c).value = value; input(c).dispatchEvent(new Event('input')); }

it('HO9 return shows why unchanged incoming text needs a new file or mode choice', async () => {
  const view = await prepared(); await leaveAndReturn(view);
  const c = await composer(view);
  expect(c.shadowRoot!.textContent).toMatch(/files and mode.*not kept/i);
  expect(c.shadowRoot!.textContent).toMatch(/choose files or a mode.*change the question/i);
  expect(q<HTMLButtonElement>(c, 'sv3-composer-send')!.getAttribute('data-unavailable')).toBe('true');
  expect(input(c).value).toBe('Only this incoming question');
  expect(dispatches()).toHaveLength(0);
});
it.each(['Enter', 'button', 'submit event'])('HO9 unchanged incoming text cannot send all files after return through %s', async action => {
  const view = await prepared(); await leaveAndReturn(view);
  const c = await composer(view);
  if (action === 'Enter') input(c).dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, composed: true }));
  else if (action === 'button') q<HTMLButtonElement>(c, 'sv3-composer-send')!.click();
  else c.dispatchEvent(new CustomEvent('sv3-composer-submit', { bubbles: true, composed: true, detail: { query: input(c).value, tier: 'ask' } }));
  await drain(view);
  expect(dispatches()).toHaveLength(0);
});
it('HO9 choosing files after return sends exactly that new scope as Ask', async () => {
  const view = await prepared(); await leaveAndReturn(view);
  // The real result-card event is handled on the column inside the view.
  q(view, 'sv3-column')!.dispatchEvent(new CustomEvent('card-scope-file', { detail: { path: 'replacement.md' }, bubbles: true, composed: true }));
  await drain(view);
  const body = await send(view);
  expect(body.shapeId).toBe('core.rag-ask'); expect(body.docIds).toEqual(['replacement.md']); expect(body.selection).toBeUndefined();
});
it('HO9 carrying unchanged returned text into a conversation keeps the guard', async () => {
  const view = await prepared(); await leaveAndReturn(view);
  await navigate({ conversationId: 'unvisited' }); await drain(view);
  const c = await composer(view);
  expect(input(c).value).toBe('Only this incoming question');
  expect(c.shadowRoot!.textContent).toMatch(/files and mode.*not kept/i);
  input(c).dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, composed: true }));
  await drain(view); expect(dispatches()).toHaveLength(0);
});
it('HO9 replacing returned text sends an ordinary all-files Ask', async () => {
  const view = await prepared(); await leaveAndReturn(view);
  const c = await composer(view); type(c, 'My unrelated question'); await drain(view);
  const body = await send(view);
  expect(body.shapeId).toBe('core.rag-ask'); expect(body.docIds).toEqual([]); expect(body.selection).toBeUndefined();
});
it('HO9 choosing a mode after return permits ordinary sending', async () => {
  const view = await prepared(); await leaveAndReturn(view);
  const c = await composer(view);
  q<HTMLButtonElement>(c, 'sv3-composer-tier')!.click(); await c.updateComplete;
  c.shadowRoot!.querySelector<HTMLButtonElement>('[data-testid="sv3-composer-tier-option"][data-tier="ask"]')!.click();
  await drain(view);
  const body = await send(view);
  expect(body.shapeId).toBe('core.rag-ask'); expect(body.docIds).toEqual([]); expect(body.selection).toBeUndefined();
});
it('HO9 a layout reparent keeps the exact prepared hand-off', async () => {
  const view = await prepared(); view.remove(); document.body.append(view); await drain(view);
  const body = await send(view);
  expect(body.shapeId).toBe('core.summarize');
  expect(body.selection).toEqual({ kind: 'result-set', items: [{ id: 'chosen.md', kind: 'doc' }] });
});
