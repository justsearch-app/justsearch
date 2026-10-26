// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './SearchV3View.js';
import '../LibrarySurface.js';
import '../HelpSurface.js';
import { Stage } from '../../chrome/Shell.js';
import type { LibrarySurface } from '../LibrarySurface.js';
import type { Surface } from '../../../api/types/surface.js';
import { getSurface, __seedForTest as seedSurfaces, __resetForTest as resetSurfaces } from '../../../api/registry/SurfaceCatalogClient.js';
import { createMockHostApi } from '../../plugin-api/testHostApi.js';
import type { SearchV3View } from './SearchV3View.js';
import type { Sv3SearchController } from './sv3-search.js';
import type { ResultsCard } from '../../components/searchResults/ResultsCard.js';
import type { ConfirmDialog } from '../../components/ConfirmDialog.js';
import { resetSearchState } from '../../state/searchState.js';
import { __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';

type Mounted = HTMLElement & { updateComplete: Promise<unknown> };
const target = '/docs/sub/report\\[1].txt ';
const sibling = '/docs/sub/second.txt';
const other = '/docs/other.txt';
const json = (body: unknown, status = 200): Response => new Response(JSON.stringify(body), { status });
type Fetcher = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;
let fetchMock: ReturnType<typeof vi.fn<Fetcher>>;
let paths: string[];
let invocations: Record<string, unknown>[];
let effects: number;
let status: 'COMPLETE' | 'INCOMPLETE' | 'UNKNOWN' | 'REFUSED';
let supported: boolean;
let held: boolean;
let finish: (() => void) | null;
let operationKey: string;
let wrongReceipt: false | 'path' | 'scope' | 'key' = false;
let invocationKey = '';
const surface = (id: string, mountTag: string): Surface => ({ id, mountTag,
  presentation: { labelKey: id, descriptionKey: id }, audience: 'USER', placement: 'RAIL',
  consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] },
  provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' } });
const SEARCH = surface('core.search-v3-surface', 'jf-sv3-window');
const LIBRARY = surface('core.library-surface', 'jf-library-surface');
const HELP = surface('core.help-surface', 'jf-help-surface');
let cacheLimit: number;

function observation() {
  return { operationKey: wrongReceipt === 'key' ? '01993ba0-0000-7000-8000-000000000002' : operationKey,
    path: wrongReceipt === 'path' ? sibling : target, scope: wrongReceipt === 'scope' ? 'FOLDER' : 'FILE', status,
    removal: status === 'COMPLETE' ? 'CONFIRMED' : 'UNKNOWN', keepOut: 'INSTALLED',
    reason: status === 'COMPLETE' ? 'NONE' : 'OUTCOME_UNCONFIRMED',
    explanation: status === 'COMPLETE' ? 'Removal completed. Original files were not erased.' : `${status}: removal is not confirmed.` };
}

beforeEach(() => {
  cacheLimit = Stage.MAX_RETAINED;
  resetSurfaces(); seedSurfaces({ schemaVersion: '1.0.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [SEARCH, LIBRARY, HELP] });
  sessionStorage.clear(); localStorage.clear(); resetSearchState();
  __resetConversationListForTest(); __resetDraftProvidersForTest(); __resetDraftKeptForTest();
  paths = [target, sibling, other]; invocations = []; effects = 0; status = 'COMPLETE';
  supported = true; held = false; finish = null; operationKey = '01993ba0-0000-7000-8000-000000000001'; wrongReceipt = false; invocationKey = '';
  fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.includes('/api/knowledge/search')) return json({ results: paths.map(path => ({ id: `doc:${path}`, fields: { path } })),
      totalHits: paths.length, matchCount: paths.length });
    if (url.includes('/api/indexing/forgets?')) return supported
      ? json({ items: effects > 0 && status === 'COMPLETE' ? [] : [{ ...observation(), operationKey, path: target, scope: 'FILE', status: 'INCOMPLETE', removal: 'UNKNOWN', reason: 'OLD_WORK' }], complete: true, nextId: null }) : json({ error: 'Unsupported' }, 404);
    if (url.includes('/api/indexing/forgets/')) return json(observation());
    if (url.includes('/api/operations/core.forget-indexed-path/invoke') || url.includes('/api/operations/core.retry-forget/invoke')) {
      const request = JSON.parse(String(init?.body)) as Record<string, unknown>;
      invocations.push(request); invocationKey = String(request.idempotencyKey);
      if (url.includes('core.forget-indexed-path')) operationKey = invocationKey;
      if (!request.confirmationToken) return json({ success: false, errorClass: 'CONFIRMATION_REQUIRED',
        pendingId: 'forget-pending', gateBehavior: 'TYPED_CONFIRM', riskTier: 'HIGH',
        operationKey: invocationKey, preparationNonce: 'exact-preparation', argsSummary: `Forget exact file ${JSON.stringify(target)} (1 trailing whitespace character). Original files and retained answers, logs and backups are not erased.` }, 428);
      effects++;
      if (status === 'COMPLETE' && !wrongReceipt) paths = paths.filter(path => path !== target);
      const response = () => json(status === 'COMPLETE'
        ? { success: true, structuredData: { forget: observation() } }
        : { success: false, errorClass: 'HANDLER_FAILURE', errorCode: `FORGET_${status}`, errorDetails: { forget: observation() } });
      if (held) return new Promise<Response>(resolve => { finish = () => resolve(response()); });
      return response();
    }
    if (url.endsWith('/api/authorizations/approve')) return json({ capsule: 'one-use', operationKey: invocationKey, preparationNonce: 'exact-preparation' });
    if (url.includes('/api/chat/runs/live')) return json({ runs: [] });
    if (url.includes('/api/chat/conversations') && url.endsWith('/history')) return json({ sessionId: 'other-conversation', messages: [] });
    if (url.includes('/api/chat/conversations')) return json({ sessions: [{ sessionId: 'other-conversation', title: 'Other conversation',
      createdAtMs: 1, lastActiveAtMs: 2, messageCount: 0, firstUserMessage: '' }] });
    if (url.includes('/api/thread/')) return json({ conversationId: 'other-conversation', events: [] });
    return json({});
  });
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(async () => {
  Stage.MAX_RETAINED = cacheLimit;
  finish?.();
  document.body.replaceChildren(); resetSearchState(); resetSurfaces();
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
  const stage = document.createElement('jf-stage') as Stage & Mounted;
  stage.surface = getSurface(SEARCH.id)!; stage.hostApi_ = createMockHostApi({ data: { fetch: (path, init) => fetchMock(path, { ...init, body: typeof init?.body === 'object' ? JSON.stringify(init.body) : init?.body }) } });
  document.body.append(stage); await pump(stage);
  const view = stage.shadowRoot!.querySelector('jf-sv3-window') as SearchV3View & Mounted;
  expect(view, 'actual Stage mounts the actual Search v3 surface').toBeTruthy(); await pump(view);
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
  return { stage, view, card, field };
}
function controller(view: SearchV3View) { return (view as unknown as { search: Sv3SearchController }).search; }
async function currentCard(view: Mounted): Promise<ResultsCard & Mounted> {
  const main = await region(view, 'jf-sv3-main'); const results = await region(main, 'jf-sv3-results');
  return await region(results, 'jf-results-card') as ResultsCard & Mounted;
}
function rows(card: ResultsCard) { return [...(card.shadowRoot?.querySelectorAll('[data-testid="search-result-row"]') ?? [])]; }
async function requestForget(view: Mounted, card: ResultsCard) {
  const hit = card.snapshot!.results.find(hit => hit.path === target)!;
  const invoker = card.shadowRoot!.querySelector<HTMLElement>('[data-testid="search-result-row"]')!;
  card.dispatchEvent(new CustomEvent('card-forget', { bubbles: true, composed: true,
    detail: { id: hit.id, path: hit.path, scope: 'FILE', invoker } }));
  await pump(view);
  const dialog = document.querySelector('jf-confirm-dialog') as ConfirmDialog;
  expect(dialog, 'the host must route forget through named consent before effects').toBeTruthy();
  await dialog.updateComplete;
  return dialog;
}
async function pressDialog(dialog: ConfirmDialog, confirm: boolean) {
  if (confirm && dialog.typedConfirmWord) {
    const input = dialog.shadowRoot!.querySelector<HTMLInputElement>('input')!;
    input.value = dialog.typedConfirmWord; input.dispatchEvent(new Event('input'));
    await dialog.updateComplete;
  }
  const button = await region(dialog, `jf-button.${confirm ? 'confirm' : 'cancel'}`);
  const control = await region(button, 'jf-control');
  control.shadowRoot!.querySelector<HTMLButtonElement>('button')!.click();
  await pump();
}

async function library(stage: Stage & Mounted, alongside = false) {
  if (alongside) { stage.splitAxis = 'horizontal'; stage.secondarySurface = getSurface(LIBRARY.id)!; }
  else stage.surface = getSurface(LIBRARY.id)!;
  await pump(stage);
  const view = stage.shadowRoot!.querySelector('jf-library-surface') as LibrarySurface & Mounted;
  expect(view, 'actual Stage mounts the actual Library surface').toBeTruthy(); await pump(view);
  return view;
}
async function retry(view: LibrarySurface & Mounted) {
  const section = view.shadowRoot!.querySelector('[data-testid="unfinished-forgets"]')!;
  const button = [...section.querySelectorAll<Mounted & { onActivate(): void }>('jf-button')]
    .find(button => button.getAttribute('label')?.startsWith('Retry forget for'))!;
  expect(button, 'recovery is reachable from the original saved intent').toBeTruthy();
  button.onActivate(); await pump(view);
  const dialog = document.querySelector('jf-confirm-dialog') as ConfirmDialog;
  expect(dialog.typedConfirmWord).toBe('RETRY'); await pressDialog(dialog, true); await pump(view);
}
function prepare(view: SearchV3View, card: ResultsCard) {
  const search = controller(view);
  search.select(card.snapshot!.results.map(hit => hit.id));
  search.prepare({ paths: [target, other] }); search.recordTurnScope('saved-answer', { paths: [target, other] });
  card.dispatchEvent(new CustomEvent('card-open', { bubbles: true, composed: true, detail: { id: `doc:${target}` } }));
  return search;
}
it.each([false, true])('LR56 Library Retry reconciles actual retained or alongside Search (alongside=%s)', async alongside => {
  const { stage, view, card } = await mount(); const search = prepare(view, card); await pump(view);
  expect(view.paneDocPath).toBe(target);
  const recovery = await library(stage, alongside); expect(view.isConnected).toBe(alongside);
  await retry(recovery); expect(effects).toBe(1);
  if (!alongside) { stage.surface = getSurface(SEARCH.id)!; await pump(stage); expect(stage.shadowRoot!.querySelector('jf-sv3-window')).toBe(view); }
  await pump(view);
  expect(card.snapshot?.matchCount).toBe(2); expect(rows(card)).toHaveLength(2);
  expect([...search.selected]).not.toContain(`doc:${target}`);
  expect([...search.selected]).toContain(`doc:${other}`);
  expect(search.pendingScope?.paths).toEqual([other]); expect(view.paneDocPath).toBeNull();
  expect(search.scopesByTurn.get('saved-answer')?.paths).toEqual([target, other]);
});
it.each(['REFUSED', 'INCOMPLETE', 'UNKNOWN'] as const)('LR56 Library %s never reconciles unconfirmed target state', async result => {
  status = result; const { stage, view, card } = await mount(); const search = prepare(view, card); await pump(view);
  const recovery = await library(stage, true); await retry(recovery); await pump(view);
  expect(card.snapshot?.matchCount).toBe(3); expect(rows(card)).toHaveLength(3);
  expect([...search.selected]).toContain(`doc:${target}`); expect(search.pendingScope?.paths).toEqual([target, other]);
  expect(view.paneDocPath).toBe(target);
});
it.each(['path', 'scope', 'key'] as const)('LR56 mismatched receipt %s cannot report completion or reconcile another target', async mismatch => {
  wrongReceipt = mismatch; const { stage, view, card } = await mount(); const search = prepare(view, card); await pump(view);
  const recovery = await library(stage, true); await retry(recovery); await pump(view);
  expect(card.snapshot?.matchCount).toBe(3); expect(rows(card)).toHaveLength(3);
  expect([...search.selected]).toEqual([`doc:${target}`, `doc:${sibling}`, `doc:${other}`]); expect(search.pendingScope?.paths).toEqual([target, other]);
  expect(recovery.shadowRoot!.querySelector('[data-testid="forget-decision-result"]')?.textContent).toContain('could not be confirmed');
  expect(view.paneDocPath).toBe(target);
});
it('LR56 initial card completion keeps its established target-specific behavior inside Stage', async () => {
  const { stage, view, card } = await mount(); const search = prepare(view, card); await pump(view);
  const dialog = await requestForget(view, card); await pressDialog(dialog, true); await pump(stage); await pump(view);
  expect(rows(card)).toHaveLength(2); expect(card.snapshot?.matchCount).toBe(2);
  expect(search.pendingScope?.paths).toEqual([other]); expect(view.paneDocPath).toBeNull();
});
it('LR56 late Library completion reaches its retained owner without clearing a new unrelated draft or scope', async () => {
  held = true; const { stage, view, card } = await mount(); const search = prepare(view, card); await pump(view);
  const recovery = await library(stage); await retry(recovery); expect(finish).not.toBeNull();
  stage.surface = getSurface(SEARCH.id)!; await pump(stage); search.prepare({ paths: [other] });
  card.dispatchEvent(new CustomEvent('card-open', { bubbles: true, composed: true, detail: { id: `doc:${other}` } }));
  view.tier = 'ask'; await pump(view);
  const currentComposer = await region(view, 'jf-sv3-composer');
  const draft = currentComposer.shadowRoot!.querySelector<HTMLTextAreaElement>('[data-testid="sv3-composer-input"]')!;
  draft.value = 'new unrelated draft'; draft.dispatchEvent(new Event('input')); await pump(view);
  finish!(); await pump(view);
  const current = await currentCard(view); expect(current.snapshot?.matchCount).toBe(2); expect(rows(current)).toHaveLength(2);
  expect(search.pendingScope?.paths).toEqual([other]); expect(view.paneDocPath).toBe(other);
  const composer = await region(view, 'jf-sv3-composer');
  expect(composer.shadowRoot!.querySelector<HTMLTextAreaElement>('[data-testid="sv3-composer-input"]')!.value).toBe('new unrelated draft'); expect(search.scopesByTurn.get('saved-answer')?.paths).toEqual([target, other]);
});

it('LR56 late old-API completion cannot regain authority after API-base ABA', async () => {
  held = true; const { stage, view, card } = await mount(); prepare(view, card); await pump(view);
  const recovery = await library(stage); await retry(recovery); expect(finish).not.toBeNull();
  stage.surface = getSurface(SEARCH.id)!; await pump(stage);
  stage.apiBase = 'http://new-api.invalid'; await pump(stage);
  stage.apiBase = ''; await pump(stage);
  paths = [target, sibling, other];
  const composer = await region(view, 'jf-sv3-composer');
  const field = composer.shadowRoot!.querySelector<HTMLTextAreaElement>('[data-testid="sv3-composer-input"]')!;
  field.value = 'new owner query'; field.dispatchEvent(new Event('input'));
  field.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true })); await pump(view);
  controller(view).prepare({ paths: [target] });
  const current = await currentCard(view);
  current.dispatchEvent(new CustomEvent('card-open', { bubbles: true, composed: true, detail: { id: `doc:${target}` } }));
  await pump(view); finish!(); await pump(view);
  expect((await currentCard(view)).snapshot?.matchCount).toBe(3);
  expect(controller(view).pendingScope?.paths).toEqual([target]); expect(view.paneDocPath).toBe(target);
  expect(controller(view).query).toBe('new owner query'); expect(field.value).toBe('new owner query');
});
it('LR56 evicted completion origin cannot remint itself or change another retained surface', async () => {
  held = true; const { stage, view, card } = await mount(); const search = prepare(view, card); await pump(view);
  const recovery = await library(stage); await retry(recovery); expect(finish).not.toBeNull();
  stage.surface = getSurface(SEARCH.id)!; await pump(stage); Stage.MAX_RETAINED = 2;
  stage.surface = getSurface(HELP.id)!; await pump(stage);
  stage.surface = getSurface(SEARCH.id)!; await pump(stage);
  expect(stage.shadowRoot!.querySelector('jf-sv3-window')).toBe(view);
  search.prepare({ paths: [other] });
  (await currentCard(view)).dispatchEvent(new CustomEvent('card-open', { bubbles: true, composed: true, detail: { id: `doc:${other}` } }));
  await pump(view);
  const reads = fetchMock.mock.calls.filter(call => String(call[0]).includes('/api/knowledge/search')).length;
  finish!(); await pump(view);
  expect(fetchMock.mock.calls.filter(call => String(call[0]).includes('/api/knowledge/search'))).toHaveLength(reads);
  expect(stage.shadowRoot!.querySelector('jf-library-surface')).toBeNull();
  expect(recovery.isConnected).toBe(false); expect(search.pendingScope?.paths).toEqual([other]);
  expect(view.paneDocPath).toBe(other);
});
it('LR56 duplicate observed completion preserves the new unrelated pane, query and prepared scope', async () => {
  const { stage, view, card } = await mount(); const search = prepare(view, card); await pump(view);
  const recovery = await library(stage, true); let observed: unknown;
  recovery.addEventListener('jf-indexed-forget-completed', event => { observed = (event as CustomEvent).detail; });
  await retry(recovery); await pump(view); expect((await currentCard(view)).snapshot?.matchCount).toBe(2);
  expect(observed).toBeTruthy(); search.prepare({ paths: [other] });
  (await currentCard(view)).dispatchEvent(new CustomEvent('card-open', { bubbles: true, composed: true, detail: { id: `doc:${other}` } }));
  await pump(view); const query = search.query;
  recovery.dispatchEvent(new CustomEvent('jf-indexed-forget-completed', { detail: observed })); await pump(view);
  expect((await currentCard(view)).snapshot?.matchCount).toBe(2);
  expect(search.query).toBe(query); expect(search.pendingScope?.paths).toEqual([other]); expect(view.paneDocPath).toBe(other);
  expect(search.scopesByTurn.get('saved-answer')?.paths).toEqual([target, other]);
});
