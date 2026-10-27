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
import { loadConversations, restoreActiveConversation } from '../../state/conversationListStore.js';
import { createURLSource } from '../../router/sources/URLSource.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';

import { __resetAgentSessionStore } from '../../state/agentSessionStore.js';
import { registerCoreStores } from '../../router/bootstrap.js';
import { createNavigationHandler } from '../../router/navigationHandler.js';
import { createIntentRouter } from '../../router/intentRouter.js';
import { parseUrl } from '../../router/parser.js';
import { resolveSurface } from '../../router/catalogResolver.js';
import { __seedForTest as seedSurfaces, __resetForTest as resetSurfaces } from '../../../api/registry/SurfaceCatalogClient.js';
import type { Surface } from '../../../api/types/surface.js';
import { deactivateProjection, flushPendingProjection } from '../../router/URLProjector.js';
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
const oldText = 'Old task on unrelated decoy';
function recovery(): { drafts: Sv3RecoveryDraft[] } {
  const payload = JSON.parse(sessionStorage.getItem(recoveryKey)!).payload;
  expect(isSv3Recovery(payload)).toBe(true);
  return payload;
}
async function reload(): Promise<void> {
  window.dispatchEvent(new Event('pagehide'));
  document.body.replaceChildren(); await new Promise<void>(resolve => setTimeout(resolve, 0));
  deactivateProjection(); __resetDraftProvidersForTest(); __resetConversationListForTest(); resetSearchState();
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
async function refuseFiles(): Promise<SearchV3View> {
  await openCompatibilityAddress('core.ask-surface', oldText, ['/decoy.md']);
  const view = await mount();
  await arrive(view, incomingText, Array.from({ length: 129 }, (_, i) => `/scope-${i}.md`));
  expect(view.shadowRoot!.textContent).toContain('128-file recovery limit');
  return view;
}
async function refuseCapacity(): Promise<SearchV3View> {
  await openCompatibilityAddress('core.ask-surface', 'First draft', ['/decoy.md']);
  const view = await mount();
  await arrive(view, 'Second draft'); await arrive(view, incomingText);
  expect((await field(view)).value).toBe('Second draft');
  expect(view.shadowRoot!.textContent).toContain('Only two draft preparations');
  return view;
}
function mode(view: SearchV3View, tier: 'ask' | 'search'): void {
  view.shadowRoot!.querySelector('jf-sv3-composer')!.dispatchEvent(new CustomEvent('sv3-tier-change', {
    bubbles: true, composed: true, detail: { tier },
  }));
}
it('S79 refusal allows conversation addresses and subsequent traversal', async () => {
  const view = await refuseFiles();
  const prior = fetchMock.getMockImplementation()! as (url: unknown) => Promise<unknown>;
  fetchMock.mockImplementation(async (url: unknown) => String(url).includes('/api/chat/conversations?')
    ? { ok: true, status: 200, json: async () => ({ sessions: ['a', 'b'].map(id => ({ sessionId: id, createdAtMs: 1, lastActiveAtMs: 1, firstUserMessage: id })) }) }
    : prior(url));
  await loadConversations();
  await drain(view);
  for (const id of ['a', 'b']) {
    view.shadowRoot!.querySelector('jf-sv3-sidebar')!.dispatchEvent(new CustomEvent('sv3-session-select', {
      bubbles: true, composed: true, detail: { id },
    }));
    await drain(view); flushPendingProjection();
    expect(view.sessions.activeId).toBe(id);
    expect(new URLSearchParams(window.location.hash.split('?')[1]).get('conversationId')).toBe(id);
  }
  expect(peekSearchV3Handoff()).toBeNull();
  // The store restore is the real traversal receiver; browser history is checked separately.
  restoreActiveConversation('a'); await drain(view); flushPendingProjection();
  expect(view.sessions.activeId).toBe('a');
  expect(new URLSearchParams(window.location.hash.split('?')[1]).get('conversationId')).toBe('a');
  expect(dispatches()).toHaveLength(0);
});
it('S80 S81 Search after refusal projects its current address', async () => {
  const view = await refuseFiles(); mode(view, 'search'); await drain(view);
  const input = await field(view); input.value = 'Cedar'; input.dispatchEvent(new Event('input'));
  await new Promise(resolve => setTimeout(resolve, 700)); await drain(view); flushPendingProjection();
  expect(fetchMock.mock.calls.some(c => String(c[0]).includes('/api/knowledge/search'))).toBe(true);
  expect(window.location.hash).not.toContain('query=');
  expect(peekSearchV3Handoff()).toBeNull(); expect(dispatches()).toHaveLength(0);
});
it('S82 refusal notice shows exact incoming text and file count', async () => {
  const view = await refuseFiles();
  const notice = q(view, 'sv3-residency-notice')!;
  expect(notice.querySelector('pre')?.textContent).toBe(incomingText);
  expect(notice.textContent).toContain('129 files');
  expect((await field(view)).value).toBe(oldText);
  expect(dispatches()).toHaveLength(0);
});
it('S77 discard prepares parked hand-off immediately with mode and files', async () => {
  const view = await refuseCapacity(); button(view, 'sv3-recovery-discard-copy').click(); await drain(view);
  expect((await field(view)).value).toBe(incomingText);
  expect((await composer(view)).getAttribute('tier')).toBe('ask');
  expect((await composer(view)).shadowRoot!.textContent).toContain('chosen.md');
  expect(recovery().drafts.map(d => d.text)).toEqual([incomingText, 'Second draft']);
  expect(view.shadowRoot!.textContent).not.toContain('Only two draft preparations');
  expect(window.location.hash).not.toContain('query=');
  expect(peekSearchV3Handoff()).toBeNull(); expect(dispatches()).toHaveLength(0);
});
it('S83 navigation retires refusal so later discard cannot resurrect it', async () => {
  const view = await refuseCapacity(); mode(view, 'search'); await drain(view);
  button(view, 'sv3-recovery-discard-copy').click(); await drain(view);
  expect((await field(view)).value).toBe('Second draft');
  expect(peekSearchV3Handoff()).toBeNull(); expect(dispatches()).toHaveLength(0);
});
it('S78 accepted address and edited text survive Library traversal and reload without delivery', async () => {
  await openCompatibilityAddress('core.ask-surface', 'Accepted text', ['/chosen.md']);
  let view = await mount();
  expect(window.location.hash).not.toContain('query=');
  const input = await field(view); input.value = 'Edited after acceptance'; input.dispatchEvent(new Event('input')); await drain(view);
  window.dispatchEvent(new Event('pagehide'));
  const nav = createNavigationHandler({ isKnownSurface: () => true, setActiveSurface: () => {} });
  await nav.handle({ kind: 'navigate', target: 'core.library-surface', state: {} });
  await nav.handle({ kind: 'navigate', target: 'core.search-v3-surface', state: {} }, { push: false, taskOrigin: 'restore' });
  await drain(view); expect((await field(view)).value).toBe('Edited after acceptance');
  await reload(); await nav.handle({ kind: 'navigate', target: 'core.search-v3-surface', state: {} }, { taskOrigin: 'restore' });
  view = await mount(); expect((await field(view)).value).toBe('Edited after acceptance');
  expect(peekSearchV3Handoff()).toBeNull(); expect(dispatches()).toHaveLength(0);
});
it('S67 preservation cold valid arrival retains earlier draft without a false refusal', async () => {
  await openCompatibilityAddress('core.ask-surface', oldText, ['/decoy.md']); await mount(); await reload();
  await openCompatibilityAddress('core.ask-surface', incomingText, ['/chosen.md']);
  const view = await mount(); expect((await field(view)).value).toBe(incomingText);
  expect(recovery().drafts.map(d => d.text)).toEqual([incomingText, oldText]);
  expect(view.shadowRoot!.textContent).not.toContain('could not be saved safely'); expect(dispatches()).toHaveLength(0);
});
it('S76 preservation genuine refusal names the file limit and keeps existing draft', async () => {
  const view = await refuseFiles();
  expect((await field(view)).value).toBe(oldText);
  expect(view.shadowRoot!.textContent).toContain('128-file recovery limit'); expect(dispatches()).toHaveLength(0);
});

it.each([
  ['canonical accepted reload', 'reload', true, false],
  ['original pre-boot input entry', 'reload', false, true],
  ['owned input history traversal', 'back_forward', true, true],
] as const)('S78 preservation %s keeps human edits without delivering old inputs', async (_case, type, owned, inputs) => {
  await openCompatibilityAddress('core.ask-surface', 'Accepted original', ['/chosen.md']);
  let view = await mount();
  const input = await field(view); input.value = 'Human edit'; input.dispatchEvent(new Event('input')); await drain(view);
  await reload();
  window.history.replaceState(owned ? { surfaceId: 'core.search-v3-surface' } : null, '',
    '#justsearch://surface/core.search-v3-surface' + (inputs ? '?query=Accepted+original&mode=ask&docIds=%2Fchosen.md' : ''));
  const win = { location: window.location, history: window.history,
    performance: { getEntriesByType: () => [{ type }] },
    addEventListener: () => {}, removeEventListener: () => {},
  } as unknown as Window;
  const nav = createNavigationHandler({ isKnownSurface: () => true, setActiveSurface: () => {} });
  const pending: Promise<void>[] = [];
  const stop = createURLSource({ windowImpl: win }).start((intent, options) => {
    if (intent.address.kind === 'navigate') pending.push(nav.handle(intent.address, {
      taskOrigin: options?.taskOrigin, push: options?.pushHistory,
    }));
  });
  await Promise.all(pending); if (typeof stop === 'function') stop();
  view = await mount(); expect((await field(view)).value).toBe('Human edit');
  expect(peekSearchV3Handoff()).toBeNull(); expect(dispatches()).toHaveLength(0);
});
