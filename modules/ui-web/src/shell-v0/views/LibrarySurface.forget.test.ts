// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './LibrarySurface.js';
import type { ConfirmDialog } from '../components/ConfirmDialog.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import { startPendingAuthorizationBridge } from '../operations/pendingAuthorizationBridge.js';
import '../components/AuthorizationHost.js';
import type { AuthorizationHost } from '../components/AuthorizationHost.js';
import { MultiplexedStream } from '../streaming/MultiplexedStream.js';
import { SHELL_EVENT_STREAM_IDS } from '../streaming/shellEventStreamIds.js';
type Mounted = HTMLElement & { updateComplete: Promise<unknown> };
interface Library extends Mounted {
  host_: PluginHostApi; apiBase: string; activeTab: string; excludesText: string;
  refresh(): Promise<void>;
}
const key = '01993ba0-0000-7000-8000-000000000001';
const exact = '/docs/report\\[1].txt ';
const json = (body: unknown, status = 200): Response => new Response(JSON.stringify(body), { status });
const item = (overrides: Record<string, unknown> = {}) => ({ operationKey: key, path: exact,
  scope: 'FILE', status: 'INCOMPLETE', removal: 'UNKNOWN', keepOut: 'INSTALLED',
  reason: 'OUTCOME_UNCONFIRMED', explanation: 'Removal is not confirmed.', ...overrides });
let hostFetch: ReturnType<typeof vi.fn<PluginHostApi['data']['fetch']>>;
let fetchMock: ReturnType<typeof vi.fn>;
let page: unknown; let readStatus: number; let requests: Record<string, unknown>[];
let effects: number; let receipt: ReturnType<typeof item>; let hold: boolean; let finish: (() => void) | null;
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

  page = { items: [item()], complete: true, nextId: null }; readStatus = 200;
  requests = []; effects = 0; receipt = item(); hold = false; finish = null;
  hostFetch = vi.fn(async path => {
    if (path.startsWith('/api/indexing/forgets?')) return json(page, readStatus);
    if (path === '/api/settings') return json({ ui: { excludePatterns: ['*.log'] },
      witness: { acceptedRevision: 4, lastCommittedOperationKey: key } });
    if (path === '/api/indexing/excludes') return json({ patterns: ['*.log'], source: 'settings.json', sourceDetail: null });
    return json({});
  });
  fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.includes('/api/operations/')) {
      const request = JSON.parse(String(init?.body)) as Record<string, unknown>; requests.push(request);
      if (!request.confirmationToken && broadcastFirst === true) emitPending(url.includes('withdraw') ? 'core.withdraw-forget' : 'core.retry-forget');
      if (!request.confirmationToken) return json({ success: false, errorClass: 'CONFIRMATION_REQUIRED',
        pendingId: 'saved-decision', gateBehavior: 'TYPED_CONFIRM', riskTier: 'HIGH',
        operationKey: request.idempotencyKey, preparationNonce: 'frozen-target',
        argsSummary: `Exact saved file ${JSON.stringify(exact)} (1 trailing whitespace character). Prior removal may already have happened.` }, 428);
      effects++;
      const response = () => json({ success: true, structuredData: { forget: receipt } });
      if (hold) return new Promise<Response>(resolve => { finish = () => resolve(response()); });
      return response();
    }
    if (url.includes('/api/authorizations/pending/')) return json({ pendingId: 'owned-forget-pending',
      operationId: 'core.forget-indexed-path', argsSummary: 'Exact target', gateBehavior: 'TYPED_CONFIRM' });
    if (url.endsWith('/api/authorizations/approve')) return json({ capsule: 'one-use',
      operationKey: requests[0]?.idempotencyKey, preparationNonce: 'frozen-target' });
    if (url.includes('/api/indexing/forgets/')) return json(receipt);
    return json({});
  }); vi.stubGlobal('fetch', fetchMock);
});
afterEach(async () => {
  stopBridge(); authorizationHost.remove();
  finish?.(); document.body.replaceChildren(); await new Promise(resolve => setTimeout(resolve, 220));
  vi.unstubAllGlobals(); vi.restoreAllMocks();
});
async function pump(el?: Mounted) {
  for (let i = 0; i < 8; i++) { await new Promise(resolve => setTimeout(resolve, 0)); if (el?.isConnected) await el.updateComplete; }
}
async function mount(): Promise<Library> {
  const el = document.createElement('jf-library-surface') as Library;
  el.host_ = createMockHostApi({ data: { fetch: hostFetch } });
  el.refresh = vi.fn().mockResolvedValue(undefined); document.body.append(el); await pump(el); return el;
}
function section(el: Library) { return el.shadowRoot?.querySelector('[data-testid="unfinished-forgets"]'); }
function button(el: Library, prefix: string): Mounted & { onActivate(): void; disabled: boolean } {
  const found = [...(section(el)?.querySelectorAll('jf-button') ?? [])].find(node => node.getAttribute('label')?.startsWith(prefix));
  expect(found, `named ${prefix} action exists`).toBeTruthy();
  return found as Mounted & { onActivate(): void; disabled: boolean };
}
async function openDecision(el: Library, prefix: string) {
  button(el, prefix).onActivate(); await pump(el);
  const dialog = document.querySelector('jf-confirm-dialog') as ConfirmDialog;
  expect(dialog, 'saved decisions require fresh exact-target consent').toBeTruthy(); await dialog.updateComplete; return dialog;
}
async function press(dialog: ConfirmDialog, approved: boolean) {
  if (approved) {
    const input = dialog.shadowRoot!.querySelector<HTMLInputElement>('input')!;
    input.value = dialog.typedConfirmWord!; input.dispatchEvent(new Event('input')); await dialog.updateComplete;
  }
  const action = dialog.shadowRoot!.querySelector<Mounted>(`jf-button.${approved ? 'confirm' : 'cancel'}`)!; await action.updateComplete;
  const control = action.shadowRoot!.querySelector<Mounted>('jf-control')!; await control.updateComplete;
  control.shadowRoot!.querySelector<HTMLButtonElement>('button')!.click(); await pump();
}
it('S56 recovery remains available without the original result and remount never retries', async () => {
  const el = await mount();
  expect(section(el)?.textContent).toContain('"/docs/report\\\\[1].txt " (1 trailing whitespace character)');
  expect(section(el)?.textContent).toContain('prior removal may already have happened');
  expect(section(el)?.textContent).toContain('Removal outcome unknown.');
  expect(button(el, 'Retry forget for').disabled).toBe(false); expect(requests).toHaveLength(0);
  el.remove(); await mount(); expect(requests).toHaveLength(0); expect(effects).toBe(0);
});
it.each(['Retry', 'Withdraw'])('S56 %s freezes exact saved target and sends only original key', async action => {
  const el = await mount(); const dialog = await openDecision(el, `${action} forget for`);
  expect(dialog.typedConfirmWord).toBe(action.toUpperCase()); expect(dialog.shadowRoot!.textContent).toContain(JSON.stringify(exact));
  expect(effects).toBe(0); expect(requests).toHaveLength(1); expect(requests[0]?.args).toEqual({ forgetKey: key });
  await press(dialog, true); await pump(el);
  expect(effects).toBe(1); expect(requests).toHaveLength(2);
  expect(requests.map(request => request.args)).toEqual([{ forgetKey: key }, { forgetKey: key }]);
  expect(requests[0]?.idempotencyKey).toBe(requests[1]?.idempotencyKey); expect(requests[0]?.idempotencyKey).not.toBe(key);
  expect(fetchMock.mock.calls.filter(call => String(call[0]).includes('/operations/'))
    .every(call => String(call[0]).includes(`/core.${action.toLowerCase()}-forget/invoke`))).toBe(true);
});
it('N56 cancellation and Library departure abort consent before approval or effects', async () => {
  const el = await mount(); let dialog = await openDecision(el, 'Retry forget for');
  await press(dialog, false); expect(effects).toBe(0);
  dialog = await openDecision(el, 'Withdraw forget for'); el.activeTab = 'other'; await pump(el);
  expect(dialog.open).toBe(false); expect(effects).toBe(0);
  expect(fetchMock.mock.calls.some(call => String(call[0]).includes('/authorizations/approve'))).toBe(false);
});
it('WD55 unresolved withdrawal disables destructive Retry and names retry of withdrawal', async () => {
  page = { items: [item({ reason: 'WITHDRAWAL_PENDING', keepOut: 'UNKNOWN' })], complete: true, nextId: null };
  const el = await mount(); expect(button(el, 'Retry forget for').disabled).toBe(true);
  button(el, 'Retry forget for').onActivate(); await pump(el); expect(requests).toHaveLength(0);
  expect(button(el, 'Retry withdrawal of forget for').disabled).toBe(false);
  await openDecision(el, 'Retry withdrawal of forget for');
  expect(String(fetchMock.mock.calls.find(call => String(call[0]).includes('/api/operations/'))?.[0]))
    .toContain('/core.withdraw-forget/invoke');
});
it('S56 explicit pagination and failed refresh cannot claim an empty successful observation', async () => {
  page = { items: [item()], complete: false, nextId: 23 }; const el = await mount();
  page = { items: [item({ path: '/docs/second' })], complete: true, nextId: null };
  button(el, 'Next unfinished forgets').onActivate(); await pump(el);
  expect(hostFetch.mock.calls.some(([path]) => path === '/api/indexing/forgets?afterId=23&limit=100')).toBe(true);
  expect(section(el)?.textContent).toContain('/docs/second'); expect(button(el, 'First page of unfinished forgets')).toBeTruthy();
  readStatus = 503; button(el, 'Refresh unfinished forgets').onActivate(); await pump(el);
  expect(section(el)?.textContent).toContain('Their outcomes are unknown.');
  expect(section(el)?.textContent).not.toContain('No unfinished forgets on this page.');
  expect(button(el, 'Retry forget for').disabled).toBe(true); expect(effects).toBe(0);
});
it('N56 unknown targets disable decisions and unsupported hosts hide recovery controls', async () => {
  page = { items: [item({ path: null, scope: null, status: 'UNKNOWN' })], complete: true, nextId: null };
  let el = await mount(); expect(section(el)?.textContent).toContain('Exact target unavailable');
  expect(button(el, 'Retry forget for').disabled).toBe(true); expect(button(el, 'Withdraw forget for').disabled).toBe(true);
  el.remove(); readStatus = 404; el = await mount(); expect(section(el)).toBeNull(); expect(requests).toHaveLength(0);
});
it('WD55 late withdrawal preserves a new draft after leaving this Library placement', async () => {
  const el = await mount(); hold = true; receipt = item({ status: 'WITHDRAWN', keepOut: 'RELEASED' });
  const dialog = await openDecision(el, 'Withdraw forget for'); await press(dialog, true); expect(effects).toBe(1);
  el.activeTab = 'other'; await pump(el); el.excludesText = '*.log\nmy-new-draft'; finish!(); await pump(el);
  expect(el.excludesText).toBe('*.log\nmy-new-draft');
  expect(el.shadowRoot?.querySelector('[data-testid="forget-decision-result"]')?.textContent?.trim()).toBe('');
});

it.each(['Retry', 'Withdraw'])('F1 %s has one owned ceremony in both broadcast orders; Cancel preserves focus and state', async action => {
  const el = await mount();
  for (const first of [true, false]) {
    broadcastFirst = first;
    const invoker = button(el, `${action} forget for`); await invoker.updateComplete;
    invoker.focus();
    expect((invoker.shadowRoot!.querySelector('jf-control') as Mounted).shadowRoot!.activeElement).not.toBeNull();
    const dialog = await openDecision(el, `${action} forget for`);
    if (!first) { emitPending(`core.${action.toLowerCase()}-forget`); await pump(el); }
    await authorizationHost.updateComplete;
    expect(authorizationHost.shadowRoot!.querySelector('dialog')?.open).toBe(false);
    expect(document.querySelectorAll('jf-confirm-dialog[open]')).toHaveLength(1);
    expect(dialog.shadowRoot!.textContent).toContain(JSON.stringify(exact));
    await press(dialog, false); await pump(el);
    expect(effects).toBe(0);
    expect((invoker.shadowRoot!.querySelector('jf-control') as Mounted).shadowRoot!.activeElement).not.toBeNull();
    expect(fetchMock.mock.calls.some(call => String(call[0]).endsWith('/api/authorizations/approve'))).toBe(false);
    expect(section(el)?.textContent).toContain('Decision cancelled before approval.');
    await new Promise(resolve => setTimeout(resolve, 220));
  }
});

it('F1 Library Cancel returns focus to its enabled originating control without a broadcast', async () => {
  const el = await mount();
  const invoker = button(el, 'Retry forget for'); await invoker.updateComplete; invoker.focus();
  const dialog = await openDecision(el, 'Retry forget for');
  await press(dialog, false); await pump(el);
  expect(invoker.disabled).toBe(false);
  expect((invoker.shadowRoot!.querySelector('jf-control') as Mounted).shadowRoot!.activeElement).not.toBeNull();
  expect(effects).toBe(0);
  expect(section(el)?.textContent).toContain('This decision changed nothing.');
});
