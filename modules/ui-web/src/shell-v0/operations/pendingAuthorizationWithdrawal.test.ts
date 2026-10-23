// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { AuthorizationHost } from '../components/AuthorizationHost.js';
import '../components/AuthorizationHost.js';
import { OperationClient } from './OperationClient.js';
import { requestAuthorization } from './authorizationBroker.js';
import { startPendingAuthorizationBridge } from './pendingAuthorizationBridge.js';
import { MultiplexedStream } from '../streaming/MultiplexedStream.js';
import { SHELL_EVENT_STREAM_IDS } from '../streaming/shellEventStreamIds.js';

const status = vi.hoisted(() => ({ listeners: new Set<(snapshot: unknown) => void>() }));
vi.mock('../utils/statusPoll.js', () => ({
  subscribeStatus: (listener: (snapshot: unknown) => void) => {
    status.listeners.add(listener);
    return () => status.listeners.delete(listener);
  },
}));

const detail = { pendingId: 'pa-shared', operationId: 'core.apply-excludes',
  gateBehavior: 'TYPED_CONFIRM', riskTier: 'HIGH', sourceTier: 'UNTRUSTED' };
let host: AuthorizationHost;
let es: EventTarget;
let mux: MultiplexedStream;
let stop: () => void;
let pending: boolean;
let writes: number;
let approveStatus: number;
let lookupStatus: number;
const goneIds = new Set<string>();
const toasts: string[] = [];
const onToast = (event: Event) => toasts.push((event as CustomEvent).detail.message);
function frame(streamId: string, payload: unknown, seq = 1) {
  es.dispatchEvent(new MessageEvent('frame', { data: JSON.stringify({ streamId,
    frameKind: 'UPDATE', seq, ts: new Date().toISOString(), resumeToken: `r${seq}`, payload }) }));
}
async function settle() {
  await vi.advanceTimersByTimeAsync(0);
  await host.updateComplete;
}
async function approve(denied = false) {
  const input = host.shadowRoot!.querySelector('input')!;
  input.value = detail.operationId;
  input.dispatchEvent(new Event('input'));
  await host.updateComplete;
  const button = host.shadowRoot!.querySelector(denied ? '[data-testid="authorization-deny"]' : '[data-testid="authorization-approve"]')!;
  await (button as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  const control = button.shadowRoot!.querySelector('jf-control')!;
  await (control as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  control.shadowRoot!.querySelector('button')!.click();
  await settle();
}
const dialog = () => host.shadowRoot!.querySelector('dialog')!;
beforeEach(async () => {
  vi.useFakeTimers(); pending = true; writes = 0; toasts.length = 0;
  approveStatus = 200; lookupStatus = 200; goneIds.clear();
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    if (String(input).includes('/api/authorizations/pending/')) {
      const id = decodeURIComponent(String(input).split('/').at(-1)!);
      const exists = (id !== detail.pendingId || pending) && !goneIds.has(id);
      return new Response(JSON.stringify(exists ? { ...detail, pendingId: id } : {}), { status: exists ? lookupStatus : 404 });
    }
    if (String(input).endsWith('/api/authorizations/approve')) {
      writes++;
      pending = false;
      return new Response(JSON.stringify({ capsule: 'capsule' }), { status: approveStatus });
    }
    throw new Error(`Unexpected request ${String(input)}`);
  }));
  document.addEventListener('jf-advisory-ephemeral', onToast);
  host = document.createElement('jf-authorization-host') as AuthorizationHost;
  document.body.append(host); await host.updateComplete;
  es = Object.assign(new EventTarget(), { close() {}, readyState: 1 });
  mux = new MultiplexedStream({ url: '/api/shell-events/stream', eventSourceFactory: () => es as EventSource });
  stop = startPendingAuthorizationBridge('', { multiplex: mux });
  mux.start(); es.dispatchEvent(new Event('open'));
  frame(SHELL_EVENT_STREAM_IDS.PENDING_AUTHORIZATIONS, detail);
  await settle();
  expect(dialog().open).toBe(true);
});
afterEach(async () => {
  stop(); mux.stop(); host.remove(); await settle();
  document.removeEventListener('jf-advisory-ephemeral', onToast);
  expect(status.listeners.size).toBe(0);
  vi.unstubAllGlobals(); vi.useRealTimers();
});

it('withdraws the second client prompt when the first client answers and activity arrives (XA1)', async () => {
  await new OperationClient({ apiBase: '' }).approveByPendingId(detail.pendingId);
  frame(SHELL_EVENT_STREAM_IDS.ACTION_LEDGER, { id: 'completed', kind: 'operation' });
  await settle();
  expect(dialog().open, 'answered elsewhere must release the native modal').toBe(false);
  expect(document.body.style.overflow).not.toBe('hidden');
  expect(writes).toBe(1);
});

it('withdraws an unknown request on the next shared status update (XA1)', async () => {
  pending = false;
  for (const listener of status.listeners) listener({});
  await settle();
  expect(dialog().open, 'Engine 404 must close without a click').toBe(false);
  expect(writes).toBe(0);
});

it('answering a stale prompt closes it with a neutral note and sends no approval (XA1)', async () => {
  pending = false;
  await approve();
  expect(dialog().open).toBe(false);
  expect(writes).toBe(0);
  expect(toasts.some(message => /no longer pending/i.test(message))).toBe(true);
  expect(toasts.some(message => /failed/i.test(message))).toBe(false);
});

it('keeps a still-pending prompt usable after activity and status checks (XA1)', async () => {
  frame(SHELL_EVENT_STREAM_IDS.ACTION_LEDGER, { id: 'unrelated', kind: 'operation' });
  for (const listener of status.listeners) listener({});
  await settle();
  expect(dialog().open).toBe(true);
  await approve();
  expect(writes).toBe(1);
  expect(dialog().open).toBe(false);
});

it('denying a stale prompt closes with a neutral note and no server change (XA1)', async () => {
  pending = false;
  await approve(true);
  expect(dialog().open).toBe(false);
  expect(writes).toBe(0);
  expect(toasts.some(message => /no longer pending/i.test(message))).toBe(true);
  expect(toasts.some(message => /failed/i.test(message))).toBe(false);
});

it('handles a 410 race after the final peek with a neutral note (XA1)', async () => {
  approveStatus = 410;
  await approve();
  expect(dialog().open).toBe(false);
  expect(toasts.some(message => /no longer pending/i.test(message))).toBe(true);
  expect(toasts.some(message => /failed/i.test(message))).toBe(false);
});

it('removes queued stale prompts without resetting the live prompt or showing the stale one (XA1)', async () => {
  frame(SHELL_EVENT_STREAM_IDS.PENDING_AUTHORIZATIONS, { ...detail, pendingId: 'pa-queued' }, 2);
  await settle();
  const input = host.shadowRoot!.querySelector('input')!;
  input.value = 'core.apply'; input.dispatchEvent(new Event('input')); await host.updateComplete;
  goneIds.add('pa-queued');
  for (const listener of status.listeners) listener({});
  await settle();
  expect(input.value).toBe('core.apply');
  await approve();
  expect(dialog().open, 'a withdrawn queued copy must never become the active modal').toBe(false);
  expect(writes).toBe(1);
});

it('does not dismiss a pending prompt on a failed state lookup (XA1)', async () => {
  lookupStatus = 503;
  for (const listener of status.listeners) listener({});
  await settle();
  expect(dialog().open).toBe(true);
  expect(writes).toBe(0);
  lookupStatus = 200;
  await approve();
  expect(writes).toBe(1);
});

it('withdraws an original caller prompt after the bridge detail lookup failed (XA1)', async () => {
  await approve();
  const client = new OperationClient({ apiBase: '' });
  const prompt = { ...detail, pendingId: 'pa-origin' };
  const decision = requestAuthorization(prompt,
    async () => await client.peekPending(prompt.pendingId) !== null);
  lookupStatus = 503;
  frame(SHELL_EVENT_STREAM_IDS.PENDING_AUTHORIZATIONS, prompt, 2);
  await settle();
  expect(dialog().open).toBe(true);
  for (const listener of status.listeners) listener({});
  await settle();
  expect(dialog().open, 'an unavailable state read must leave the original prompt usable').toBe(true);
  lookupStatus = 200;
  goneIds.add(prompt.pendingId);
  for (const listener of status.listeners) listener({});
  await settle();
  expect(dialog().open, 'the original prompt must recover from a failed bridge detail read').toBe(false);
  await expect(decision).resolves.toMatchObject({ approved: false, superseded: true });
  expect(writes).toBe(1);
});
