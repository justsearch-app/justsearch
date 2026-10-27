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
import type { Sv3RecoveryDraft } from './sv3-recovery.js';
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
import { peekSearchV3Handoff, takeSearchV3Handoff, takePendingForceShape, takePendingSelection, takePendingAutoRun } from '../../utils/compose.js';

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
  deactivateProjection(); window.history.replaceState(null, '', '/');
  registerCoreStores();
  resetInspectorState();
  sessionStorage.clear(); localStorage.clear();
  __resetConversationListForTest(); __resetDraftProvidersForTest(); __resetDraftKeptForTest();
  __resetAiStateForTest();
  __feedForTest({ inference: { mode: 'online', available: true } as never,
    status: { worker: { core: { indexedDocuments: 42 } } } as unknown as StatusSnapshot });
  __feedContactForTest();
  fetchMock = vi.fn(async (url: unknown) => {
    if (String(url).includes('/api/knowledge/search')) return { ok: true, status: 200, json: async () => ({ results: [], total: 0 }) };
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
  const address = parseUrl(`justsearch://surface/${target}?${params}`)!;
  try {
    await router.dispatch({ transport: 'URL_DEEPLINK', address });
    expect(activated).toHaveBeenCalledTimes(1);
  } finally { resetSurfaces(); }
}


const recoveryKey = 'justsearch.draft.attachment.v1.search-v3.main';
const incomingText = '  Explain # & % 日本語\nexactly  ';
function recovery(): { drafts: Sv3RecoveryDraft[] } {
  const payload = JSON.parse(sessionStorage.getItem(recoveryKey)!).payload;
  expect(isSv3Recovery(payload)).toBe(true);
  return payload;
}
async function field(view: SearchV3View): Promise<HTMLTextAreaElement> {
  return q<HTMLTextAreaElement>(await composer(view), 'sv3-composer-input')!;
}
function button(view: SearchV3View, id: string): HTMLButtonElement {
  return q<HTMLButtonElement>(view, id)!;
}


async function arrive(view: SearchV3View, text: string, files = ['/chosen.md']): Promise<void> {
  await openCompatibilityAddress('core.ask-surface', text, files); await drain(view);
}
async function refuseCapacity(): Promise<SearchV3View> {
  await openCompatibilityAddress('core.ask-surface', 'First draft', ['/decoy.md']);
  const view = await mount();
  await arrive(view, 'Second draft'); await arrive(view, incomingText);
  expect((await field(view)).value).toBe('Second draft');
  expect(view.shadowRoot!.textContent).toContain('Only two draft preparations');
  return view;
}
const bareSearch = { kind: 'navigate', target: 'core.search-v3-surface', state: {} } as const;
it.each([
  ['rail', undefined],
  ['fresh address', { push: false, taskOrigin: 'fresh' }],
  ['traversal', { push: false, taskOrigin: 'restore' }],
] as const)('S84 %s to bare Search retires refusal notice and its alert without changing drafts', async (_case, options) => {
  const view = await refuseCapacity();
  const retained = recovery().drafts;
  expect(q(view, 'sv3-refused-handoff')?.textContent).toContain(incomingText);
  const nav = createNavigationHandler({ isKnownSurface: () => true, setActiveSurface: () => {} });
  if (options?.push === false) window.history.replaceState(null, '', '#justsearch://surface/core.search-v3-surface');
  await nav.handle(bareSearch, options); await drain(view);
  expect(window.location.hash).toBe('#justsearch://surface/core.search-v3-surface');
  expect(peekSearchV3Handoff()).toBeNull();
  expect(q(view, 'sv3-refused-handoff')).toBeNull();
  expect(view.shadowRoot!.textContent).not.toContain('Only two draft preparations');
  expect((await field(view)).value).toBe('Second draft');
  expect(recovery().drafts).toEqual(retained);
  button(view, 'sv3-recovery-discard-copy').click(); await drain(view);
  expect(q(view, 'sv3-refused-handoff')).toBeNull();
  expect((await field(view)).value).toBe('Second draft');
  expect(dispatches()).toHaveLength(0);
});
it('S84 preservation another proposal still reports the genuine capacity limit after retirement', async () => {
  const view = await refuseCapacity();
  const nav = createNavigationHandler({ isKnownSurface: () => true, setActiveSurface: () => {} });
  await nav.handle(bareSearch); await drain(view);
  await arrive(view, 'Fourth proposal');
  expect(q(view, 'sv3-refused-handoff')?.textContent).toContain('Fourth proposal');
  expect(view.shadowRoot!.textContent).toContain('Only two draft preparations');
  expect((await field(view)).value).toBe('Second draft');
  expect(recovery().drafts.map(d => d.text)).toEqual(['Second draft', 'First draft']);
  expect(dispatches()).toHaveLength(0);
});
it('S84 preservation rejected unknown navigation keeps refusal available for explicit discard', async () => {
  const view = await refuseCapacity();
  const unknown = vi.fn();
  const nav = createNavigationHandler({ isKnownSurface: () => false, setActiveSurface: () => {}, onUnknownSurface: unknown });
  await nav.handle({ kind: 'navigate', target: 'missing', state: {} }); await drain(view);
  expect(unknown).toHaveBeenCalledWith('missing');
  expect(q(view, 'sv3-refused-handoff')?.textContent).toContain(incomingText);
  expect(view.shadowRoot!.textContent).toContain('Only two draft preparations');
  button(view, 'sv3-recovery-discard-copy').click(); await drain(view);
  expect((await field(view)).value).toBe(incomingText);
  expect(q(view, 'sv3-refused-handoff')).toBeNull();
  expect(dispatches()).toHaveLength(0);
});
it('S84 accepted modal navigation retires refusal while keeping the underlying preparation', async () => {
  const view = await refuseCapacity();
  const retained = recovery().drafts;
  const openModal = vi.fn();
  const nav = createNavigationHandler({ isKnownSurface: () => true, setActiveSurface: () => {},
    getPlacement: () => 'MODAL', openModal });
  await nav.handle({ kind: 'navigate', target: 'core.settings-surface', state: {} }); await drain(view);
  expect(openModal).toHaveBeenCalledTimes(1);
  expect(q(view, 'sv3-refused-handoff')).toBeNull();
  expect(view.shadowRoot!.textContent).not.toContain('Only two draft preparations');
  expect(peekSearchV3Handoff()).toBeNull();
  expect((await field(view)).value).toBe('Second draft');
  expect(recovery().drafts).toEqual(retained);
  expect(dispatches()).toHaveLength(0);
});
