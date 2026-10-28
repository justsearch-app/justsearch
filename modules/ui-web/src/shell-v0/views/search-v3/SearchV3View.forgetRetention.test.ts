// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import type { Sv3SearchController } from './sv3-search.js';
import type { ConfirmDialog } from '../../components/ConfirmDialog.js';
import type { ResultsCard } from '../../components/searchResults/ResultsCard.js';
import { clearSelection, getSelection } from '../../state/selectionState.js';
import type { Sv3Recovery } from './sv3-recovery.js';
import { registerCoreStores } from '../../router/bootstrap.js';
import { createNavigationHandler } from '../../router/navigationHandler.js';
import { deactivateProjection } from '../../router/URLProjector.js';
import { resetSearchState } from '../../state/searchState.js';
import { __resetConversationListForTest, restoreActiveConversation } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';

type Mounted = HTMLElement & { updateComplete: Promise<unknown> };
const target = '/docs/sub/report\\[1].txt ';
const sibling = '/docs/sub/second.txt';
const other = '/docs/other.txt';
const json = (body: unknown, status = 200): Response => new Response(JSON.stringify(body), { status });
let finish: (() => void) | null = null;
let operationKey = '';
const recoveryKey = 'justsearch.draft.attachment.v1.search-v3.main';
beforeEach(() => {
  finish = null; operationKey = "";
  registerCoreStores();
  clearSelection(); sessionStorage.clear(); localStorage.clear(); resetSearchState();
  __resetConversationListForTest(); __resetDraftProvidersForTest(); __resetDraftKeptForTest();
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.includes('/api/operations/core.forget-indexed-path/invoke')) {
      const request = JSON.parse(String(init?.body)); operationKey = request.idempotencyKey;
      if (!request.confirmationToken) return json({ success: false, errorClass: 'CONFIRMATION_REQUIRED',
        pendingId: 'pending', gateBehavior: 'TYPED_CONFIRM', riskTier: 'HIGH', operationKey,
        preparationNonce: 'exact-preparation', argsSummary: `Forget exact file ${JSON.stringify(target)}` }, 428);
      return new Promise<Response>(resolve => { finish = () => resolve(json({ success: true, structuredData: {
        forget: { operationKey, path: target, scope: 'FILE', status: 'COMPLETE', removal: 'CONFIRMED',
          keepOut: 'INSTALLED', reason: 'NONE', explanation: 'Removal completed.' },
      } })); });
    }
    if (url.endsWith('/api/authorizations/approve')) return json({ capsule: 'one-use', operationKey, preparationNonce: 'exact-preparation' });
    if (url.includes('/api/knowledge/search')) return json({ results: [target, sibling, other]
      .map(path => ({ id: `doc:${path}`, fields: { path } })), totalHits: 3, matchCount: 3 });
    if (url.includes('/api/indexing/forgets?')) return json({ items: [], complete: true, nextId: null });
    if (url.includes('/api/chat/runs/live')) return json({ runs: [] });
    if (url.includes('/api/chat/conversations') && url.endsWith('/history')) return json({ sessionId: 'kept-conversation', messages: [] });
    if (url.includes('/api/chat/conversations')) return json({ sessions: [] });
    if (url.includes('/api/thread/')) return json({ conversationId: 'kept-conversation', events: [] });
    return json({});
  }));
});
afterEach(async () => {
  finish?.();
  deactivateProjection(); clearSelection(); document.body.replaceChildren(); resetSearchState();
  await new Promise(resolve => setTimeout(resolve, 220));
  vi.unstubAllGlobals(); vi.restoreAllMocks();
});

async function pump(el?: Mounted) {
  for (let i = 0; i < 10; i++) {
    await new Promise(resolve => setTimeout(resolve, 0));
    if (el?.isConnected) await el.updateComplete;
  }
}
async function region(el: Mounted, tag: string): Promise<Mounted> {
  const found = el.shadowRoot?.querySelector(tag) as Mounted;
  expect(found, `<${tag}> is rendered`).toBeTruthy(); await found.updateComplete; return found;
}
async function mount() {
  const view = document.createElement('jf-sv3-window') as SearchV3View & Mounted;
  document.body.append(view); await pump(view);
  const composer = await region(view, 'jf-sv3-composer');
  const field = composer.shadowRoot!.querySelector<HTMLTextAreaElement>('[data-testid="sv3-composer-input"]')!;
  field.value = 'report'; field.dispatchEvent(new Event('input'));
  await composer.updateComplete;
  field.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
  await pump(view);
  const main = await region(view, 'jf-sv3-main');
  const results = await region(main, 'jf-sv3-results');
  const card = await region(results, 'jf-results-card') as ResultsCard & Mounted;
  expect(card.snapshot?.results).toHaveLength(3);
  return { view, card, field };
}
function controller(view: SearchV3View) { return (view as unknown as { search: Sv3SearchController }).search; }
function recovery(): Sv3Recovery { return JSON.parse(sessionStorage.getItem(recoveryKey)!).payload; }
function complete(view: SearchV3View, path = target, scope: 'FILE' | 'FOLDER' = 'FILE') {
  view.reconcileConfirmedForget({ apiBase: '', owner: undefined, outcome: { operationKey: 'completed-operation', path, scope, status: 'COMPLETE',
    removal: 'CONFIRMED', keepOut: 'INSTALLED', reason: 'NONE', explanation: 'Removal completed.' } });
}
function seedRetained(paths: string[]) {
  const payload: Sv3Recovery = { version: 2, task: null, drafts: [
    { place: null, text: 'current question', preparation: { kind: 'ask', scope: { kind: 'all' } } },
    { place: 'kept-conversation', text: 'kept question', preparation: { kind: 'ask', scope: { kind: 'files', paths } } },
  ] };
  sessionStorage.setItem(recoveryKey, JSON.stringify({ version: 1, incarnation: 'earlier', payload }));
}
async function reload(view: SearchV3View) {
  window.dispatchEvent(new Event('pagehide')); view.remove(); await pump();
  __resetDraftProvidersForTest(); __resetConversationListForTest(); resetSearchState();
  const fresh = document.createElement('jf-sv3-window') as SearchV3View & Mounted;
  document.body.append(fresh); await pump(fresh); return fresh;
}

it.each([{ chosen: [target] }, { chosen: [target, other] }])('confirmed Forget immediately republishes the live choice $chosen', async ({ chosen }) => {
  const { view, card } = await mount(); const search = controller(view);
  search.select(card.snapshot!.results.filter(hit => chosen.includes(hit.path)).map(hit => hit.id));
  expect(JSON.stringify(getSelection().items)).toContain(JSON.stringify(target).slice(1, -1));
  complete(view);
  const items = getSelection().items;
  expect(JSON.stringify(items)).not.toContain(JSON.stringify(target).slice(1, -1));
  if (chosen.length === 1) expect(items).toEqual([]);
  else expect(items).toMatchObject([{ kind: 'search-hit', path: other }]);
});

it.each(['FILE', 'FOLDER'] as const)('confirmed %s Forget prunes a kept draft across reload and navigation', async scope => {
  seedRetained([target, sibling, other]);
  const view = document.createElement('jf-sv3-window') as SearchV3View & Mounted;
  document.body.append(view); await pump(view);
  complete(view, scope === 'FILE' ? target : '/docs/sub', scope);
  const expected = scope === 'FILE' ? [sibling, other] : [other];
  const kept = recovery().drafts.find(draft => draft.place === 'kept-conversation')!;
  expect(kept.preparation).toEqual({ kind: 'ask', scope: { kind: 'files', paths: expected } });
  const fresh = await reload(view);
  await createNavigationHandler({ isKnownSurface: () => true, setActiveSurface: () => {} })
    .handle({ kind: 'navigate', target: 'core.search-v3-surface', state: { conversationId: 'kept-conversation' } });
  await pump(fresh);
  expect(recovery().drafts.find(draft => draft.text === 'kept question')?.preparation)
    .toEqual({ kind: 'ask', scope: { kind: 'files', paths: expected } });
  expect(fresh.shadowRoot!.textContent).not.toContain(target);
});

it('forgetting the last kept file requires reselection rather than granting all-file scope', async () => {
  seedRetained([target]);
  const view = document.createElement('jf-sv3-window') as SearchV3View & Mounted;
  document.body.append(view); await pump(view); complete(view);
  expect(recovery().drafts.find(draft => draft.place === 'kept-conversation')?.preparation)
    .toEqual({ kind: 'ask', scope: { kind: 'reselect', paths: [] } });
});

it('confirmed Forget preserves saved turn scopes while pruning a live prepared Ask', async () => {
  const { view } = await mount(); const search = controller(view);
  const saved = { paths: [target, other] };
  search.recordTurnScope('saved-answer', saved); search.prepare({ paths: [target, other] });
  complete(view);
  expect(search.pendingScope?.paths).toEqual([other]);
  expect(search.scopesByTurn.get('saved-answer')).toBe(saved);
  expect(search.scopesByTurn.get('saved-answer')?.paths).toEqual([target, other]);
});

it('confirmed Forget prunes a retained submission without rewriting its saved turn', async () => {
  seedRetained([target, other]);
  const payload = recovery();
  payload.task = { id: 'pending', place: null, target: null, shape: 'core.rag-ask', raw: 'pending question',
    schema: null, scope: [target, other], record: 'saved-answer', needsReselection: false, effort: 'standard', delivery: 'review' };
  sessionStorage.setItem(recoveryKey, JSON.stringify({ version: 1, incarnation: 'earlier', payload }));
  const view = document.createElement('jf-sv3-window') as SearchV3View & Mounted;
  document.body.append(view); await pump(view);
  const saved = { paths: [target, other] }; controller(view).recordTurnScope('saved-answer', saved);
  complete(view);
  expect(recovery().task).toMatchObject({ scope: [other], record: 'saved-answer', needsReselection: true });
  expect(controller(view).scopesByTurn.get('saved-answer')).toBe(saved);
  await reload(view);
  expect(recovery().task?.scope).toEqual([other]);
});

it('confirmed Forget prunes an incoming handoff selection and its kept file preparation', async () => {
  const view = document.createElement('jf-sv3-window') as SearchV3View & Mounted;
  document.body.append(view); await pump(view);
  await createNavigationHandler({ isKnownSurface: () => true, setActiveSurface: () => {} })
    .handle({ kind: 'navigate', target: 'core.search-v3-surface',
      state: { query: 'Incoming question', forceShapeId: 'core.summarize', docIds: [target, other] } });
  await pump(view);
  const initialHandoff = (view as unknown as { preparedHandoff: object }).preparedHandoff;
  complete(view, '/docs/unrelated.txt');
  expect((view as unknown as { preparedHandoff: object }).preparedHandoff).toBe(initialHandoff);
  expect(controller(view).pendingScope?.paths).toEqual([target, other]);
  complete(view);
  expect(controller(view).pendingScope?.paths).toEqual([other]);
  const handoff = (view as unknown as { preparedHandoff: { selection: unknown } }).preparedHandoff;
  expect(handoff.selection).toEqual({ kind: 'result-set', items: [{ id: other, kind: 'doc' }] });
  expect(recovery().drafts.find(draft => draft.text === 'Incoming question')?.preparation)
    .toEqual({ kind: 'summarize', scope: { kind: 'files', paths: [other] } });
  complete(view, other); await pump(view);
  const composer = await region(view, 'jf-sv3-composer');
  expect(composer.shadowRoot!.textContent).toContain('Choose files or a passage again before sending.');
});

it('a confirmed card Forget arriving after navigation prunes the kept draft without replacing current text', async () => {
  const { view, card } = await mount(); const search = controller(view);
  view.tier = 'ask'; search.prepare({ paths: [target, other] });
  card.dispatchEvent(new CustomEvent('card-forget', { bubbles: true, composed: true,
    detail: { id: `doc:${target}`, path: target, scope: 'FILE', invoker: null } }));
  await pump(view);
  const dialog = document.querySelector('jf-confirm-dialog') as ConfirmDialog;
  expect(dialog).toBeTruthy(); await dialog.updateComplete;
  const input = dialog.shadowRoot!.querySelector<HTMLInputElement>('input')!;
  input.value = 'FORGET'; input.dispatchEvent(new Event('input')); await dialog.updateComplete;
  const button = await region(dialog, 'jf-button.confirm'); const control = await region(button, 'jf-control');
  control.shadowRoot!.querySelector<HTMLButtonElement>('button')!.click(); await pump(view);
  expect(finish).not.toBeNull();
  await restoreActiveConversation('other-conversation'); await pump(view);
  expect(view.sessions.activeId).toBe('other-conversation');
  const composer = await region(view, 'jf-sv3-composer');
  const field = composer.shadowRoot!.querySelector<HTMLTextAreaElement>('[data-testid="sv3-composer-input"]')!;
  field.value = 'new unrelated draft'; field.dispatchEvent(new Event('input'));
  finish!(); await pump(view);
  expect(field.value).toBe('new unrelated draft');
  expect(recovery().drafts.find(draft => draft.text === 'report')?.preparation)
    .toEqual({ kind: 'ask', scope: { kind: 'files', paths: [other] } });
});

it('a live kept Ask losing its final file stays blocked when returning without reload', async () => {
  const { view } = await mount(); view.tier = 'ask'; controller(view).prepare({ paths: [target] });
  await restoreActiveConversation('other-conversation'); await pump(view);
  expect(view.sessions.activeId).toBe('other-conversation'); complete(view);
  const kept = (view as unknown as { placeDrafts: Map<string, { review: boolean; preparation: unknown }> }).placeDrafts.get('\u0000results');
  expect(kept).toEqual({ text: 'report', review: true, preparation: { kind: 'ask', scope: { kind: 'reselect', paths: [] } } });
  expect(controller(view).hasPlace).toBe(true);
  restoreActiveConversation(null); await pump(view);
  expect(view.sessions.activeId).toBeNull();
  expect(controller(view).showing).toBe(true);
  expect(view.shadowRoot!.querySelector('[data-testid="sv3-draft-review"]')).not.toBeNull();
  expect(view.shadowRoot!.querySelector<HTMLButtonElement>('[data-testid="sv3-draft-review-confirm"]')?.disabled).toBe(true);
});

it('a live Structured draft losing its final file keeps its schema and requires reselection', async () => {
  const { view } = await mount(); view.tier = 'structured'; controller(view).prepare({ paths: [target] });
  await pump(view);
  const schema = '{"type":"object","properties":{"summary":{"type":"string"}}}';
  const composer = await region(view, 'jf-sv3-composer');
  composer.dispatchEvent(new CustomEvent('sv3-schema-change', { bubbles: true, composed: true, detail: { text: schema } }));
  await pump(view);
  expect((view as unknown as { schemaDraft: string }).schemaDraft).toBe(schema);
  complete(view); await pump(view);
  expect(recovery().drafts[0]?.preparation)
    .toEqual({ kind: 'structured', schema, scope: { kind: 'reselect', paths: [] } });
  expect(view.shadowRoot!.querySelector('[data-testid="sv3-draft-review"]')).not.toBeNull();
});
