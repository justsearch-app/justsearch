// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import type { Sv3SearchController } from './sv3-search.js';
import type { ResultsCard } from '../../components/searchResults/ResultsCard.js';
import type { ConfirmDialog } from '../../components/ConfirmDialog.js';
import { resetSearchState } from '../../state/searchState.js';
import { __resetConversationListForTest, restoreActiveConversation } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';

import { startPendingAuthorizationBridge } from '../../operations/pendingAuthorizationBridge.js';
import '../../components/AuthorizationHost.js';
import type { AuthorizationHost } from '../../components/AuthorizationHost.js';
import { MultiplexedStream } from '../../streaming/MultiplexedStream.js';
import { SHELL_EVENT_STREAM_IDS } from '../../streaming/shellEventStreamIds.js';
type Mounted = HTMLElement & { updateComplete: Promise<unknown> };
const target = '/docs/sub/report\\[1].txt ';
const sibling = '/docs/sub/second.txt';
const other = '/docs/other.txt';
const json = (body: unknown, status = 200): Response => new Response(JSON.stringify(body), { status });
let fetchMock: ReturnType<typeof vi.fn>;
let paths: string[];
let invocations: Record<string, unknown>[];
let effects: number;
let status: 'COMPLETE' | 'INCOMPLETE' | 'UNKNOWN' | 'REFUSED';
let supported: boolean;
let held: boolean;
let finish: (() => void) | null;
let operationKey: string;
let completeExplanation: string;

function observation() {
  return { operationKey, path: target, scope: 'FILE', status,
    removal: status === 'COMPLETE' ? 'CONFIRMED' : 'UNKNOWN', keepOut: 'INSTALLED',
    reason: status === 'COMPLETE' ? 'NONE' : 'OUTCOME_UNCONFIRMED',
    explanation: status === 'COMPLETE' ? completeExplanation : `${status}: removal is not confirmed.` };
}

let broadcastFirst: boolean | null;
let emitPending: (operationId: string) => void;
let stopBridge: () => void;
let authorizationHost: AuthorizationHost;
class FakeEventSource extends EventTarget {
  readyState = 1;
  close() { this.readyState = 2; }
}
beforeEach(() => {
  broadcastFirst = null;
  authorizationHost = document.createElement('jf-authorization-host') as AuthorizationHost;
  document.body.append(authorizationHost);
  const events = new FakeEventSource();
  const multiplex = new MultiplexedStream({ url: 'http://test/stream', eventSourceFactory: () => events as unknown as EventSource });
  stopBridge = startPendingAuthorizationBridge('', { multiplex });
  multiplex.start(); events.dispatchEvent(new Event('open'));
  let seq = 0;
  emitPending = operationId => events.dispatchEvent(new MessageEvent('frame', { data: JSON.stringify({
    streamId: SHELL_EVENT_STREAM_IDS.PENDING_AUTHORIZATIONS, frameKind: 'UPDATE', seq: ++seq,
    ts: '2026-10-27T18:32:50Z', resumeToken: `forget-${seq}`, payload: {
      pendingId: 'owned-forget-pending', operationId, sourceTier: 'TRUSTED', riskTier: 'HIGH', gateBehavior: 'TYPED_CONFIRM',
    },
  }) }));
  const stop = stopBridge; stopBridge = () => { stop(); multiplex.stop(); };

  sessionStorage.clear(); localStorage.clear(); resetSearchState();
  __resetConversationListForTest(); __resetDraftProvidersForTest(); __resetDraftKeptForTest();
  paths = [target, sibling, other]; invocations = []; effects = 0; status = 'COMPLETE';
  supported = true; held = false; finish = null; operationKey = '';
  completeExplanation = 'Removal completed. Original files were not erased.';
  fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.includes('/api/knowledge/search')) return json({ results: paths.map(path => ({ id: `doc:${path}`, fields: { path } })),
      totalHits: paths.length, matchCount: paths.length });
    if (url.includes('/api/indexing/forgets?')) return supported
      ? json({ items: [], complete: true, nextId: null }) : json({ error: 'Unsupported' }, 404);
    if (url.includes('/api/indexing/forgets/')) return json(observation());
    if (url.includes('/api/operations/core.forget-indexed-path/invoke')) {
      const request = JSON.parse(String(init?.body)) as Record<string, unknown>;
      invocations.push(request); operationKey = String(request.idempotencyKey);
      if (!request.confirmationToken && broadcastFirst === true) emitPending('core.forget-indexed-path');
      if (!request.confirmationToken) return json({ success: false, errorClass: 'CONFIRMATION_REQUIRED',
        pendingId: 'forget-pending', gateBehavior: 'TYPED_CONFIRM', riskTier: 'HIGH',
        operationKey, preparationNonce: 'exact-preparation', argsSummary: `Forget exact file ${JSON.stringify(target)} (1 trailing whitespace character). Original files and retained answers, logs and backups are not erased.` }, 428);
      effects++;
      if (status === 'COMPLETE') paths = paths.filter(path => path !== target);
      const response = () => json(status === 'COMPLETE'
        ? { success: true, structuredData: { forget: observation() } }
        : { success: false, errorClass: 'HANDLER_FAILURE', errorCode: `FORGET_${status}`, errorDetails: { forget: observation() } });
      if (held) return new Promise<Response>(resolve => { finish = () => resolve(response()); });
      return response();
    }
    if (url.includes('/api/authorizations/pending/')) return json({ pendingId: 'owned-forget-pending',
      operationId: 'core.forget-indexed-path', argsSummary: 'Exact target', gateBehavior: 'TYPED_CONFIRM' });
    if (url.endsWith('/api/authorizations/approve')) return json({ capsule: 'one-use', operationKey, preparationNonce: 'exact-preparation' });
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
  stopBridge(); authorizationHost.remove();
  finish?.();
  document.body.replaceChildren(); resetSearchState();
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

it('S56 confirmed success refreshes real current search counts, selection, pending Ask and the matching pane', async () => {
  const { view, card } = await mount();
  const search = controller(view);
  search.select(card.snapshot!.results.map(hit => hit.id));
  search.prepare({ paths: [target, other] }); search.recordTurnScope('saved-answer', { paths: [target, other] });
  card.dispatchEvent(new CustomEvent('card-open', { bubbles: true, composed: true, detail: { id: `doc:${target}` } }));
  await pump(view); expect(view.paneDocPath).toBe(target);
  const dialog = await requestForget(view, card);
  expect(dialog.message.toString()).not.toBe('');
  expect(dialog.shadowRoot!.textContent).toContain(JSON.stringify(target));
  expect(dialog.typedConfirmWord).toBe('FORGET'); expect(effects).toBe(0);
  await pressDialog(dialog, true); await pump(view);
  expect(effects).toBe(1); expect(invocations).toHaveLength(2);
  expect(card.snapshot?.matchCount).toBe(2); expect(rows(card)).toHaveLength(2);
  expect([...search.selected]).not.toContain(`doc:${target}`);
  expect(search.pendingScope?.paths).toEqual([other]);
  expect(search.scopesByTurn.get('saved-answer')?.paths).toEqual([target, other]);
  expect(view.paneDocPath).toBeNull();
  expect(view.shadowRoot!.querySelector('[data-testid="sv3-forget-notice"]')?.textContent).toContain('Removal completed');
});

it('N56 cancellation and repeated activation create no second request or destructive effect', async () => {
  const { view, card } = await mount();
  const dialog = await requestForget(view, card);
  card.dispatchEvent(new CustomEvent('card-forget', { bubbles: true, composed: true,
    detail: { id: `doc:${target}`, path: target, scope: 'FILE', invoker: null } }));
  await pump(view); expect(invocations).toHaveLength(1);
  await pressDialog(dialog, false); await pump(view);
  expect(effects).toBe(0); expect(invocations).toHaveLength(1); expect(rows(card)).toHaveLength(3);
});

it.each(['REFUSED', 'INCOMPLETE', 'UNKNOWN'] as const)('S56 %s never removes rows, selection, Ask scope or pane optimistically', async result => {
  status = result;
  const { view, card } = await mount(); const search = controller(view);
  search.select([`doc:${target}`]); search.prepare({ paths: [target] });
  card.dispatchEvent(new CustomEvent('card-open', { bubbles: true, composed: true, detail: { id: `doc:${target}` } }));
  const dialog = await requestForget(view, card); await pressDialog(dialog, true); await pump(view);
  expect(rows(card)).toHaveLength(3); expect(card.snapshot?.matchCount).toBe(3);
  expect([...search.selected]).toContain(`doc:${target}`); expect(search.pendingScope?.paths).toEqual([target]);
  expect(view.paneDocPath).toBe(target);
  expect(view.shadowRoot!.querySelector('[data-testid="sv3-forget-notice"]')?.textContent).toContain(result);
});

it('S56 leaving a screen while consent is open aborts that dialog and a stale confirm cannot execute', async () => {
  const { view, card } = await mount();
  const dialog = await requestForget(view, card);
  view.remove(); dialog.dispatchEvent(new CustomEvent('confirm'));
  await pump(); expect(effects).toBe(0); expect(invocations).toHaveLength(1);
  expect(dialog.open).toBe(false);
});

it('S56 a late completed response cannot close another conversation pane or rewrite its new draft', async () => {
  held = true;
  const { view, card } = await mount();
  const dialog = await requestForget(view, card); await pressDialog(dialog, true);
  expect(effects).toBe(1); expect(finish).not.toBeNull();
  await restoreActiveConversation('other-conversation'); await pump(view);
  expect(view.sessions.activeId).toBe('other-conversation');
  view.paneDocPath = other;
  const composer = await region(view, 'jf-sv3-composer');
  const field = composer.shadowRoot!.querySelector<HTMLTextAreaElement>('[data-testid="sv3-composer-input"]')!;
  field.value = 'new conversation draft'; field.dispatchEvent(new Event('input'));
  finish!(); await pump(view);
  expect(view.sessions.activeId).toBe('other-conversation'); expect(view.paneDocPath).toBe(other);
  expect(field.value).toBe('new conversation draft');
  expect(view.shadowRoot!.querySelector('[data-testid="sv3-forget-notice"]')).toBeNull();
});

it('S56 remounting during an accepted forget does not replay the destructive operation', async () => {
  held = true;
  const { view, card } = await mount();
  const dialog = await requestForget(view, card); await pressDialog(dialog, true);
  expect(effects).toBe(1); view.remove();
  const fresh = document.createElement('jf-sv3-window') as SearchV3View & Mounted;
  document.body.append(fresh); await pump(fresh);
  finish!(); await pump(fresh);
  expect(effects).toBe(1); expect(invocations).toHaveLength(2);
  expect(fresh.shadowRoot!.querySelector('[data-testid="sv3-forget-notice"]')).toBeNull();
});

it('S56 a host without the owner keeps forget unavailable', async () => {
  supported = false;
  const { view, card } = await mount();
  expect(Boolean((card as ResultsCard & { forgetAvailable?: boolean }).forgetAvailable)).toBe(false);
  card.dispatchEvent(new CustomEvent('card-forget', { bubbles: true, composed: true,
    detail: { id: `doc:${target}`, path: target, scope: 'FILE', invoker: null } }));
  await pump(view); expect(invocations).toHaveLength(0); expect(document.querySelector('jf-confirm-dialog')).toBeNull();
});

it.each([true, false])('F1 a broadcast before the 428=%s leaves one ceremony; Escape changes nothing and returns focus', async first => {
  broadcastFirst = first;
  const { view, card } = await mount();
  const row = rows(card)[0] as HTMLElement; row.focus();
  const dialog = await requestForget(view, card);
  if (!first) { emitPending('core.forget-indexed-path'); await pump(view); }
  await authorizationHost.updateComplete;
  expect(authorizationHost.shadowRoot!.querySelector('dialog')?.open, 'the shared host must not present the pending owned by Forget').toBe(false);
  expect(document.querySelectorAll('jf-confirm-dialog[open]')).toHaveLength(1);
  expect(dialog.title).toBe('Forget indexed content?');
  expect(dialog.shadowRoot!.textContent).toContain(JSON.stringify(target));
  expect(dialog.shadowRoot!.textContent).not.toContain('"scope":"FILE"');
  dialog.shadowRoot!.querySelector('dialog')!.dispatchEvent(new Event('cancel', { cancelable: true }));
  await pump(view);
  expect(effects).toBe(0);
  expect(fetchMock.mock.calls.some(call => String(call[0]).endsWith('/api/authorizations/approve'))).toBe(false);
  expect(rows(card)).toHaveLength(3);
  expect(card.shadowRoot!.activeElement).toBe(row);
  expect(view.shadowRoot!.querySelector('[data-testid="sv3-forget-notice"]')?.textContent).toContain('cancelled before approval');
});

it('F3 refused preparation displays the specific Library pointer with no dialog or raw arguments', async () => {
  const { view, card } = await mount();
  const previous = fetchMock.getMockImplementation()! as typeof fetch;
  fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    if (String(input).includes('/api/operations/core.forget-indexed-path/invoke')) return json({
      success: false, errorClass: 'HANDLER_FAILURE', errorCode: 'FORGET_PREPARATION_REFUSED',
      message: 'A watched folder or a folder containing one cannot be forgotten. Remove the folder in Library instead.',
    });
    return previous(input, init);
  });
  card.dispatchEvent(new CustomEvent('card-forget', { bubbles: true, composed: true,
    detail: { id: `doc:${target}`, path: target, scope: 'FOLDER', invoker: rows(card)[0] } }));
  await pump(view);
  expect(document.querySelector('jf-confirm-dialog')).toBeNull();
  const notice = view.shadowRoot!.querySelector('[data-testid="sv3-forget-notice"]')!;
  expect(notice.getAttribute('role')).toBe('status');
  expect(notice.textContent).toContain('Remove the folder in Library instead');
  expect(notice.textContent).not.toContain('"scope":"FOLDER"');
  expect(rows(card)).toHaveLength(3); expect(effects).toBe(0);
});

it('F4 an already absent completed target is described without claiming a removal', async () => {
  completeExplanation = 'The target is no longer in the search index. Original files were not erased.';
  const { view, card } = await mount();
  const dialog = await requestForget(view, card); await pressDialog(dialog, true); await pump(view);
  const notice = view.shadowRoot!.querySelector('[data-testid="sv3-forget-notice"]')!;
  expect(notice.textContent).toContain('The target is no longer in the search index.');
  expect(notice.textContent).not.toContain('Removal confirmed');
});

it('F1 cancellation restores a keyboard-focusable row before returning focus, without a broadcast', async () => {
  const { view, card } = await mount();
  const row = rows(card)[0] as HTMLElement; row.focus();
  const focus = row.focus.bind(row); const focusability: number[] = [];
  vi.spyOn(row, 'focus').mockImplementation(() => { focusability.push(row.tabIndex); focus(); });
  const dialog = await requestForget(view, card); await pressDialog(dialog, false); await pump(view);
  expect(focusability.at(-1), 'focus must return after the busy row becomes keyboard-focusable again').toBe(0);
  expect(card.shadowRoot!.activeElement).toBe(row);
  expect(effects).toBe(0); expect(rows(card)).toHaveLength(3);
  expect(view.shadowRoot!.querySelector('[data-testid="sv3-forget-notice"]')?.textContent).toContain('This request changed nothing.');
});
