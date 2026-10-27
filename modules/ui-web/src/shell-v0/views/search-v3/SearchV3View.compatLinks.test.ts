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

import type { StatusSnapshot } from '../../utils/statusPoll.js';

import { __resetAgentSessionStore } from '../../state/agentSessionStore.js';
import { registerCoreStores } from '../../router/bootstrap.js';
import { createNavigationHandler } from '../../router/navigationHandler.js';
import { createIntentRouter } from '../../router/intentRouter.js';
import { parseUrl } from '../../router/parser.js';
import { resolveSurface } from '../../router/catalogResolver.js';
import { __seedForTest as seedSurfaces, __resetForTest as resetSurfaces } from '../../../api/registry/SurfaceCatalogClient.js';
import type { Surface } from '../../../api/types/surface.js';
import { deactivateProjection } from '../../router/URLProjector.js';
import { takePendingForceShape, takePendingSelection, takePendingAutoRun } from '../../utils/compose.js';

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
  takePendingSelection(); takePendingForceShape(); takePendingAutoRun();
  document.body.replaceChildren();
  // Disconnect closes the window's subscriptions and stream; settle its outstanding promises.
  await new Promise<void>(resolve => setTimeout(resolve, 0));
  resetSearchState(); __resetAiStateForTest(); __resetAgentSessionStore();
  vi.unstubAllGlobals(); vi.restoreAllMocks();
});
// W3/C8/A7: use the public address parser, catalog resolver and navigation handler,
// rather than parking a synthetic hand-off directly in the receiving view.
async function openCompatibilityAddress(target: string, text: string, files: string[] | null): Promise<void> {
  const ids = ['core.search-v3-surface', 'core.ask-surface', 'core.free-chat-surface', 'core.extract-surface'];
  seedSurfaces({ schemaVersion: '1.0.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface',
    entries: ids.map(id => ({ id, presentation: { labelKey: id, descriptionKey: id }, audience: 'USER',
      placement: id === 'core.search-v3-surface' ? 'RAIL' : 'DEEPLINK',
      mountTag: id === 'core.search-v3-surface' ? 'jf-sv3-window' : 'jf-chat-shape-mount',
      consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] },
      provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' } } satisfies Surface)) });
  const activated = vi.fn();
  const nav = createNavigationHandler({ isKnownSurface: id => ids.includes(id), setActiveSurface: activated });
  const router = createIntentRouter({ navigationHandler: nav, invocationHandler: { handle: vi.fn() },
    isKnownSurface: id => ids.includes(id), resolveSurface, resolveOperation: id => ({ status: 'resolved', id }) });
  const params = new URLSearchParams({ query: text });
  files?.forEach(file => params.append('docIds', file));
  // The mode is part of the canonical/retired-address contract, not needed by shape addresses.
  if (!['core.ask-surface', 'core.free-chat-surface', 'core.extract-surface'].includes(target)) params.set('mode', 'ask');
  const address = parseUrl(`justsearch://surface/${target}?${params}`)!;
  try {
    await router.dispatch({ transport: 'URL_DEEPLINK', address });
    expect(activated).toHaveBeenCalledTimes(1);
  } finally { resetSurfaces(); }
}

it.each([
  ['core.ask-surface', 'core.rag-ask', 'ask', ['/chosen.md', '/second.md']],
  ['core.free-chat-surface', 'core.free-chat', 'ask', null],
  ['core.extract-surface', 'core.extract', 'structured', ['/chosen.md', '/second.md']],
] as const)('C8 W3 A7 compatibility address %s keeps text and files until Send', async (target, shape, tier, files) => {
  const text = '  Explain # & % 日本語\nexactly  ';
  await openCompatibilityAddress(target, text, files === null ? null : [...files]);
  const view = await mount(); const c = await composer(view);
  expect(q<HTMLTextAreaElement>(c, 'sv3-composer-input')!.value).toBe(text);
  expect(c.getAttribute('tier')).toBe(tier);
  for (const file of files ?? []) expect(c.shadowRoot!.textContent).toContain(file.split('/').pop());
  expect(dispatches()).toHaveLength(0);
  if (shape === 'core.extract') {
    const schema = q<HTMLTextAreaElement>(c, 'sv3-composer-schema')!;
    schema.value = '{"type":"object"}'; schema.dispatchEvent(new Event('input')); await drain(view);
  }
  const body = await send(view);
  expect(body.shapeId).toBe(shape);
  expect(shape === 'core.rag-ask' ? body.question : body.prompt).toBe(text.trim());
  if (files !== null) {
    expect(body.selection).toEqual({ kind: 'result-set', items: files.map(id => ({ id, kind: 'doc' })) });
    if (shape === 'core.rag-ask') expect(body.docIds).toEqual(files);
  } else expect(body.docIds).toBeUndefined();
});

it.each(['core.unified-chat-surface', 'core.search-surface', 'core.agent-surface', 'core.search-v3-surface'])(
  'C2 A7 preservation address %s keeps its exact chosen scope', async target => {
    const view = await mount();
    await openCompatibilityAddress(target, 'Review chosen files', ['/chosen.md', '/second.md']); await drain(view);
    expect(q<HTMLTextAreaElement>(await composer(view), 'sv3-composer-input')!.value).toBe('Review chosen files');
    expect(dispatches()).toHaveLength(0);
    const body = await send(view);
    expect(body.shapeId).toBe('core.rag-ask'); expect(body.question).toBe('Review chosen files');
    expect(body.docIds).toEqual(['/chosen.md', '/second.md']);
  });

it('C8 A7 a warm compatibility address replaces preparation and still waits', async () => {
  const view = await mount();
  await navigate({ query: 'old task', mode: 'ask', docIds: ['/old.md'] }); await drain(view);
  await openCompatibilityAddress('core.ask-surface', 'new task', ['/chosen.md']); await drain(view);
  expect(q<HTMLTextAreaElement>(await composer(view), 'sv3-composer-input')!.value).toBe('new task');
  expect(dispatches()).toHaveLength(0);
  expect((await send(view)).docIds).toEqual(['/chosen.md']);
});

it.each(['core.ask-surface', 'core.search-v3-surface'])('C8 A2 an invalid file scope through %s keeps text and refuses Send', async target => {
  await openCompatibilityAddress(target, 'Keep this text', ['']);
  const view = await mount(); const c = await composer(view);
  expect(q<HTMLTextAreaElement>(c, 'sv3-composer-input')!.value).toBe('Keep this text');
  expect(c.shadowRoot!.textContent).toContain('Invalid task data');
  q<HTMLButtonElement>(c, 'sv3-composer-send')!.click(); await drain(view);
  expect(dispatches()).toHaveLength(0);
});

it.each([
  ['core.ask-surface', 'missing'], ['core.ask-surface', 'unreadable'],
  ['core.search-v3-surface', 'missing'], ['core.search-v3-surface', 'unreadable'],
])('C8 A7 file link through %s with %s file refuses without widening scope', async (target, reason) => {
  const previous = fetchMock.getMockImplementation()! as (url: unknown, init?: RequestInit) => Promise<unknown>;
  fetchMock.mockImplementation(async (url: unknown, init?: RequestInit) => {
    if (!String(url).includes('/api/chat/dispatch')) return previous(url, init);
    return new Response(`event: error\ndata: {"errorCode":"DOC_UNAVAILABLE","error":"Cannot read selected file: ${reason}.md"}\n\n`);
  });
  await openCompatibilityAddress(target!, 'Keep the instruction', [`/${reason}.md`]);
  const view = await mount(); expect(dispatches()).toHaveLength(0);
  const body = await send(view); expect(body.docIds).toEqual([`/${reason}.md`]);
  const c = await composer(view);
  expect(q<HTMLTextAreaElement>(c, 'sv3-composer-input')!.value).toBe('Keep the instruction');
  expect(c.shadowRoot!.textContent).toContain(`${reason}.md`);
  const main = view.shadowRoot!.querySelector('jf-sv3-main') as ElementView; await main.updateComplete;
  expect(main.shadowRoot!.textContent).toContain('Cannot read selected file');
  await drain(view); expect(dispatches()).toHaveLength(1);
});
