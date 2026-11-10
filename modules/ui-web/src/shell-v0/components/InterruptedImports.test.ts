// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './InterruptedImports.js';
import type { InterruptedImports } from './InterruptedImports.js';
import type { JfButton } from './Button.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import { authorizedFetch } from '../api/authorizedFetch.js';
import { setAuthorizationPresenter } from '../operations/authorizationBroker.js';

vi.mock('../api/authorizedFetch.js', () => ({ authorizedFetch: vi.fn() }));
const operationFetch = vi.mocked(authorizedFetch);
const item = (over = {}) => ({ operationKey: 'import-one', interruptionRevision: 3,
  reason: 'RECOVERY_AUTHORIZATION_REFUSED', folders: [{ path: '/original/private', collection: 'frozen',
    force: true, watch: false, excludePatterns: ['*.secret'], excludeLiterals: [] }], resumeAvailable: true, ...over });
const inventory = (imports = [item()]) => Response.json({ imports });
const gate = () => Response.json({ success: false, errorClass: 'CONFIRMATION_REQUIRED',
  pendingId: 'pending-one', gateBehavior: 'INLINE_CONFIRM', argsSummary: '/original/private',
  message: 'Approval required' }, { status: 428 });
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(done => { resolve = done; });
  return { promise, resolve };
}
async function pump(el: InterruptedImports) {
  for (let i = 0; i < 35; i++) { await Promise.resolve(); await el.updateComplete; }
}
async function mount(fetch: PluginHostApi['data']['fetch']) {
  const el = document.createElement('jf-interrupted-imports') as InterruptedImports;
  el.host_ = { data: { fetch } } as unknown as PluginHostApi;
  document.body.append(el); await pump(el);
  return el;
}
const buttons = (el: InterruptedImports) => [...el.shadowRoot!.querySelectorAll<JfButton>('jf-button')];
const content = (el: InterruptedImports) => el.shadowRoot!.textContent!;

beforeEach(() => { vi.resetAllMocks(); setAuthorizationPresenter(null); });
afterEach(() => { document.body.replaceChildren(); setAuthorizationPresenter(null); vi.useRealTimers(); });

it('fetches the backend envelope, shows every original folder, and respects resumeAvailable', async () => {
  const fetch = vi.fn(async () => inventory([item({ folders: [{ path: '/a' }, { path: 'C:\\b<private>' }] }),
    item({ operationKey: 'import-two', resumeAvailable: false })]));
  const el = await mount(fetch);
  expect(fetch).toHaveBeenCalledExactlyOnceWith('/api/indexing/imports/interrupted', { signal: expect.any(AbortSignal) });
  expect(content(el)).toContain('/a'); expect(content(el)).toContain('C:\\b<private>');
  expect(el.shadowRoot!.querySelectorAll('li')).toHaveLength(2);
  expect(buttons(el)).toHaveLength(1);
  expect(buttons(el)[0]!.textContent).toContain('Resume');
  expect(content(el)).not.toContain('RECOVERY_AUTHORIZATION_REFUSED');
});

it.each(['RECOVERY_AUTHORIZATION_REFUSED', 'RECOVERY_SCOPE_REFUSED', 'RECOVERY_BINDING_INVALID',
  'RECOVERY_GENERATION_MISMATCH', 'RECOVERY_ORIGIN_UNAVAILABLE', 'RECOVERY_WRITE_UNAVAILABLE',
  'RECOVERY_ORIGIN_WRITE_UNAVAILABLE', 'INGEST_UNIT_STATE_UNAVAILABLE', 'INGEST_RECOVERY_ATTEMPTS_EXHAUSTED',
  'ENGINE_LIMIT', 'CONTEXT_LIMIT'])('translates %s into plain language', async reason => {
  const el = await mount(async () => inventory([item({ reason })]));
  expect(el.shadowRoot!.querySelector('p')!.textContent).not.toBe('This import stopped before it finished');
  expect(content(el)).not.toContain(reason);
});

it('describes cancellation in plain words without offering Resume when unavailable', async () => {
  const el = await mount(async () => inventory([item({ reason: 'cancelled', resumeAvailable: false })]));
  expect(el.shadowRoot!.querySelector('p')!.textContent).toBe('This import was cancelled.');
  expect(buttons(el)).toHaveLength(0);
});

it.each(['FUTURE_PRIVATE_CODE', 'constructor', '__proto__'])('uses generic wording for unknown reason %s', async reason => {
  const el = await mount(async () => inventory([item({ reason })]));
  expect(content(el)).toContain('This import stopped before it finished');
  expect(content(el)).not.toContain(reason);
});

it.each([
  ['empty', () => Promise.resolve(inventory([]))],
  ['503', () => Promise.resolve(new Response('private error', { status: 503 }))],
  ['network', () => Promise.reject(new Error('private error'))],
  ['invalid JSON', () => Promise.resolve(new Response('<html>private error'))],
  ['invalid revision', () => Promise.resolve(inventory([item({ interruptionRevision: '3' })]))],
  ['invalid folders', () => Promise.resolve(inventory([item({ folders: [{}] })]))],
])('renders nothing for %s without claiming there are no interrupted imports', async (_name, fetch) => {
  const el = await mount(fetch);
  expect(el.shadowRoot!.querySelector('section')).toBeNull();
  expect(content(el).trim()).toBe('');
});

it('uses the real consent client and broker, freezes exact resume args, and refreshes after approval', async () => {
  const fetch = vi.fn().mockResolvedValueOnce(inventory()).mockResolvedValue(inventory([]));
  operationFetch.mockResolvedValueOnce(gate()).mockResolvedValueOnce(Response.json({ capsule: 'approved' }))
    .mockResolvedValueOnce(Response.json({ success: true }));
  const decision = deferred<{ approved: boolean; allowAlways: boolean }>();
  const presenter = vi.fn(() => decision.promise); setAuthorizationPresenter(presenter);
  const el = await mount(fetch);
  const resume = buttons(el)[0]!.onActivate!(); await pump(el);
  expect(presenter).toHaveBeenCalledOnce();
  expect(presenter.mock.calls[0]).toEqual([expect.objectContaining({ operationId: 'core.resume-import', argsSummary: '/original/private' })]);
  expect(buttons(el)[0]!.disabled).toBe(true);
  expect(buttons(el)[0]!.getAttribute('aria-busy')).toBe('true');
  await buttons(el)[0]!.onActivate!();
  expect(operationFetch).toHaveBeenCalledTimes(1);
  decision.resolve({ approved: true, allowAlways: false }); await resume; await pump(el);
  expect(operationFetch).toHaveBeenCalledTimes(3);
  const [url, request] = operationFetch.mock.calls[0]!;
  expect(url).toBe('/api/operations/core.resume-import/invoke');
  expect(JSON.parse(request!.body as string)).toEqual({ args: { operationKey: 'import-one', interruptionRevision: 3 } });
  expect(JSON.parse(operationFetch.mock.calls[2]![1]!.body as string)).toEqual({
    args: { operationKey: 'import-one', interruptionRevision: 3 }, confirmationToken: 'approved' });
  expect(fetch).toHaveBeenCalledTimes(2); expect(el.shadowRoot!.querySelector('section')).toBeNull();
});

it.each(['denied', 'stale', 'network'])('keeps a readable error and refreshes on %s', async failure => {
  const fetch = vi.fn(async () => inventory());
  if (failure === 'denied') {
    operationFetch.mockResolvedValueOnce(gate()).mockResolvedValueOnce(Response.json({ rejected: true }));
    setAuthorizationPresenter(async () => ({ approved: false, allowAlways: false }));
  } else if (failure === 'stale') operationFetch.mockResolvedValue(Response.json({ success: false,
    errorClass: 'HANDLER_FAILURE', errorCode: 'IMPORT_RESUME_STALE', message: 'private error' }, { status: 409 }));
  else operationFetch.mockRejectedValue(new Error('private error'));
  const el = await mount(fetch); await buttons(el)[0]!.onActivate!(); await pump(el);
  expect(fetch).toHaveBeenCalledTimes(2); expect(buttons(el)[0]!.disabled).toBe(false);
  const error = el.shadowRoot!.querySelector('[role="alert"]')!.textContent;
  expect(error).toContain(failure === 'denied' ? 'not approved' : failure === 'stale' ? 'changed' : 'Could not resume');
  expect(error).not.toContain('private'); expect(error).not.toContain('IMPORT_RESUME_STALE');
});

it('discards a late inventory after a newer refresh, and clears visible content on a read failure', async () => {
  const late = deferred<Response>();
  const fetch = vi.fn().mockResolvedValueOnce(inventory()).mockReturnValueOnce(late.promise)
    .mockResolvedValueOnce(inventory([item({ operationKey: 'new', folders: [{ path: '/new' }] })]))
    .mockResolvedValueOnce(new Response('', { status: 503 }));
  const el = await mount(fetch);
  window.dispatchEvent(new FocusEvent('focus')); await pump(el);
  window.dispatchEvent(new FocusEvent('focus')); await pump(el);
  late.resolve(inventory()); await pump(el);
  expect(content(el)).toContain('/new'); expect(content(el)).not.toContain('/original/private');
  window.dispatchEvent(new FocusEvent('focus')); await pump(el);
  expect(el.shadowRoot!.querySelector('section')).toBeNull();
});

it('does not attach a late resume refusal to a newer revision and ignores responses from a prior host', async () => {
  const result = deferred<Response>(); operationFetch.mockReturnValueOnce(result.promise);
  const fetch = vi.fn().mockResolvedValueOnce(inventory()).mockResolvedValue(inventory([item({ interruptionRevision: 4 })]));
  const el = await mount(fetch); const resume = buttons(el)[0]!.onActivate!();
  window.dispatchEvent(new FocusEvent('focus')); await pump(el);
  result.resolve(Response.json({ success: false, errorClass: 'HANDLER_FAILURE' })); await resume; await pump(el);
  expect(el.shadowRoot!.querySelector('[role="alert"]')).toBeNull();
  const late = deferred<Response>(); fetch.mockReturnValueOnce(late.promise);
  window.dispatchEvent(new FocusEvent('focus')); await pump(el);
  el.host_ = { data: { fetch: async () => inventory([]) } } as unknown as PluginHostApi;
  await pump(el); late.resolve(inventory()); await pump(el);
  expect(el.shadowRoot!.querySelector('section')).toBeNull();
});

it('keeps an in-flight action busy across disconnect and reconnect and refreshes when it settles', async () => {
  const result = deferred<Response>(); operationFetch.mockReturnValueOnce(result.promise);
  const fetch = vi.fn(async () => inventory());
  const el = await mount(fetch); const resume = buttons(el)[0]!.onActivate!();
  el.remove(); document.body.append(el); await pump(el);
  expect(buttons(el)[0]!.disabled).toBe(true);
  await buttons(el)[0]!.onActivate!(); expect(operationFetch).toHaveBeenCalledTimes(1);
  fetch.mockResolvedValue(inventory([])); result.resolve(Response.json({ success: true }));
  await resume; await pump(el); expect(el.shadowRoot!.querySelector('section')).toBeNull();
});

it('aborts inventory on leaving and never renders a detached late response after reconnect', async () => {
  const late = deferred<Response>();
  const fetch = vi.fn().mockResolvedValueOnce(inventory()).mockReturnValueOnce(late.promise).mockResolvedValue(inventory([]));
  const el = await mount(fetch); window.dispatchEvent(new FocusEvent('focus')); await pump(el);
  const signal = fetch.mock.calls[1]![1].signal as AbortSignal;
  el.remove(); expect(signal.aborted).toBe(true); document.body.append(el); await pump(el);
  late.resolve(inventory()); await pump(el); expect(el.shadowRoot!.querySelector('section')).toBeNull();
});

it('hides a timed-out inventory even if its transport ignores abort, then recovers on focus', async () => {
  vi.useFakeTimers();
  const late = deferred<Response>();
  const fetch = vi.fn().mockResolvedValueOnce(inventory()).mockReturnValueOnce(late.promise).mockResolvedValue(inventory());
  const el = await mount(fetch); window.dispatchEvent(new FocusEvent('focus')); await pump(el);
  vi.advanceTimersByTime(15000); await pump(el);
  expect(fetch.mock.calls[1]![1].signal.aborted).toBe(true);
  expect(el.shadowRoot!.querySelector('section')).toBeNull();
  late.resolve(inventory()); await pump(el); expect(el.shadowRoot!.querySelector('section')).toBeNull();
  window.dispatchEvent(new FocusEvent('focus')); await pump(el);
  expect(content(el)).toContain('/original/private');
});
