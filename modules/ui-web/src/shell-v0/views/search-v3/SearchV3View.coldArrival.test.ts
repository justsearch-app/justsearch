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


const rawKey = 'justsearch.draft.search-v3.composer';
const recoveryKey = 'justsearch.draft.attachment.v1.search-v3.main';
const incomingText = '  Explain # & % 日本語\nexactly  ';
const oldText = 'Old task on unrelated decoy';
function recovery(): { drafts: Sv3RecoveryDraft[] } {
  return JSON.parse(sessionStorage.getItem(recoveryKey)!).payload;
}
function seedRecovery(drafts: Sv3RecoveryDraft[], version: 2 | null = 2): void {
  sessionStorage.setItem(recoveryKey, JSON.stringify({ version: 1, incarnation: 'before-change',
    payload: { ...(version === null ? {} : { version }), task: null, drafts } }));
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

it.each([
  ['core.ask-surface', 'ask'], ['core.search-v3-surface', 'ask'],
  ['core.free-chat-surface', 'ask'], ['core.extract-surface', 'structured'],
])('S67 cold %s arrival replaces one retained preparation without losing either draft', async (target, tier) => {
  __feedForTest({ inference: { mode: 'unavailable', available: false } as never });
  await openCompatibilityAddress('core.ask-surface', oldText, ['/decoy.md']);
  const first = await mount(); expect((await field(first)).value).toBe(oldText);
  await reload();
  expect(localStorage.getItem(rawKey)).toBe(oldText);
  expect(recovery().drafts).toHaveLength(1);
  const files = target === 'core.free-chat-surface' ? null : ['/chosen.md'];
  await openCompatibilityAddress(target!, incomingText, files);
  const view = await mount();
  expect((await field(view)).value).toBe(incomingText);
  const c = await composer(view); expect(c.getAttribute('tier')).toBe(tier);
  expect(c.shadowRoot!.textContent).not.toContain('decoy.md');
  if (files) expect(c.shadowRoot!.textContent).toContain('chosen.md');
  const next = recovery(); expect(isSv3Recovery(next)).toBe(true); expect(next.drafts).toHaveLength(2);
  expect(next.drafts[0]).toMatchObject({ text: incomingText, preparation: target === 'core.free-chat-surface'
    ? { kind: 'model' } : { kind: tier === 'structured' ? 'structured' : 'ask', scope: { kind: 'files', paths: ['/chosen.md'] } } });
  expect(next.drafts[1]).toEqual({ place: null, text: oldText,
    preparation: { kind: 'ask', scope: { kind: 'files', paths: ['/decoy.md'] } } });
  expect(view.shadowRoot!.textContent).not.toContain('does not fit the limits');
  button(view, 'sv3-recovery-review-copy').click(); await drain(view);
  expect((await field(view)).value).toBe(oldText);
  expect(q(view, 'sv3-draft-review')).not.toBeNull();
  expect(view.shadowRoot!.textContent).toContain('/decoy.md');
  expect(recovery().drafts.some(d => d.text === incomingText)).toBe(true);
  expect(dispatches()).toHaveLength(0);
});

it.each(['core.ask-surface', 'core.search-v3-surface'])(
  'S67 %s genuine capacity refusal retains the incoming address and both existing drafts', async target => {
    const typed = { place: null, text: 'typed A', preparation: { kind: 'ask' as const,
      scope: { kind: 'files' as const, paths: ['/old.md'] } } };
    seedRecovery([typed]); localStorage.setItem(rawKey, 'newer raw B');
    await openCompatibilityAddress(target, incomingText, ['/chosen.md']);
    const view = await mount(); await new Promise<void>(resolve => setTimeout(resolve, 100));
    expect((await field(view)).value).toBe('newer raw B');
    expect(new URLSearchParams(window.location.hash.split('?')[1]).get('query')).toBe(incomingText);
    expect(view.shadowRoot!.textContent).toContain('Only two draft preparations can be retained');
    expect(recovery().drafts).toEqual(expect.arrayContaining([typed]));
    expect(localStorage.getItem(rawKey)).toBe('newer raw B');
    expect(dispatches()).toHaveLength(0);
  });

it('S67 text limit refusal retains the incoming address and the earlier draft', async () => {
  localStorage.setItem(rawKey, oldText);
  const oversized = '界'.repeat(21846);
  await openCompatibilityAddress('core.search-v3-surface', oversized, ['/chosen.md']);
  const view = await mount(); await new Promise<void>(resolve => setTimeout(resolve, 100));
  expect((await field(view)).value).toBe(oldText);
  expect(new URLSearchParams(window.location.hash.split('?')[1]).get('query')).toBe(oversized);
  expect(view.shadowRoot!.textContent).toContain('65536-byte recovery limit');
  expect(dispatches()).toHaveLength(0);
});

it('S67 old text-only recovery remains reviewable after an incoming task', async () => {
  seedRecovery([{ place: null, text: oldText }], null); localStorage.setItem(rawKey, oldText);
  await openCompatibilityAddress('core.search-v3-surface', incomingText, ['/chosen.md']);
  const view = await mount(); expect((await field(view)).value).toBe(incomingText);
  button(view, 'sv3-recovery-review-copy').click(); await drain(view);
  expect((await field(view)).value).toBe(oldText);
  expect(q(view, 'sv3-draft-review')).not.toBeNull();
  expect(button(view, 'sv3-draft-review-confirm').disabled).toBe(true);
  expect(dispatches()).toHaveLength(0);
});

it('S67 preservation same raw bytes at a different place do not merge draft ownership', async () => {
  const typed = { place: 'another-conversation', text: oldText,
    preparation: { kind: 'ask' as const, scope: { kind: 'files' as const, paths: ['/old.md'] } } };
  seedRecovery([typed]); localStorage.setItem(rawKey, oldText);
  await openCompatibilityAddress('core.search-v3-surface', incomingText, ['/chosen.md']);
  const view = await mount();
  expect((await field(view)).value).toBe(oldText);
  expect(view.shadowRoot!.textContent).toContain('Recovery could not be saved safely');
  expect(recovery().drafts).toEqual(expect.arrayContaining([typed]));
  expect(dispatches()).toHaveLength(0);
});

it('S67 preservation old text-only recovery loads without an incoming task and requires review', async () => {
  seedRecovery([{ place: null, text: oldText }], null);
  const view = await mount(); expect((await field(view)).value).toBe(oldText);
  expect(q(view, 'sv3-draft-review')).not.toBeNull();
  expect(button(view, 'sv3-draft-review-confirm').disabled).toBe(true);
  expect(dispatches()).toHaveLength(0);
});

it.each([
  ['file count', Array.from({ length: 129 }, (_, n) => `/chosen-${n}.md`), '128-file recovery limit'],
  ['path bytes', '/' + '界'.repeat(1366), '4096-byte recovery limit'],
])('S67 file limit refusal retains incoming text in the address with the real reason: %s', async (_case, files, reason) => {
  localStorage.setItem(rawKey, oldText);
  await openCompatibilityAddress('core.search-v3-surface', incomingText, typeof files === 'string' ? [files] : files);
  const view = await mount();
  expect((await field(view)).value).toBe(oldText);
  expect(new URLSearchParams(window.location.hash.split('?')[1]).get('query')).toBe(incomingText);
  expect(view.shadowRoot!.textContent).toContain(reason);
  expect(localStorage.getItem(rawKey)).toBe(oldText); expect(dispatches()).toHaveLength(0);
});
