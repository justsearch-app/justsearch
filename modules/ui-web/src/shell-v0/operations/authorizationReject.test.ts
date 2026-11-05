// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { AuthorizationHost } from '../components/AuthorizationHost.js';
import '../components/AuthorizationHost.js';
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
let rejectMode: 'ok' | 'network' | 'lost';
let rejects: number;
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
  vi.advanceTimersByTime(400); // the prompt arms before it accepts a decision
  control.shadowRoot!.querySelector('button')!.click();
  await settle();
}
const dialog = () => host.shadowRoot!.querySelector('dialog')!;
beforeEach(async () => {
  vi.useFakeTimers(); pending = true; writes = 0; toasts.length = 0;
  approveStatus = 200; lookupStatus = 200; rejects = 0; rejectMode = 'ok'; goneIds.clear();
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    if (String(input).includes('/api/authorizations/pending/')) {
      const id = decodeURIComponent(String(input).split('/').at(-1)!);
      const exists = (id !== detail.pendingId || pending) && !goneIds.has(id);
      return new Response(JSON.stringify(exists ? { ...detail, pendingId: id } : {}), { status: exists ? lookupStatus : 404 });
    }
    if (String(input).endsWith('/api/authorizations/reject')) {
      rejects++;
      if (rejectMode === 'network') throw new Error('Engine unreachable');
      const wasPending = pending;
      pending = false;
      if (rejectMode === 'lost') throw new Error('Response lost');
      return new Response(JSON.stringify(wasPending ? { rejected: true } : { error: 'denied by the user' }),
        { status: wasPending ? 200 : 410 });
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

it('Deny posts cancellation before removing the request and never approves', async () => {
  await approve(true);
  expect(rejects).toBe(1);
  expect(pending).toBe(false);
  expect(host.statusRequests()).toHaveLength(0);
  expect(writes).toBe(0);
});

it('an unreachable Engine keeps the ceremony answerable and reports cancellation failure', async () => {
  rejectMode = 'network';
  await approve(true);
  expect(host.statusRequests()).toHaveLength(1);
  expect(dialog().open).toBe(true);
  expect(host.shadowRoot!.querySelector('[role="alert"]')?.textContent).toContain('could not be cancelled');
  expect(pending).toBe(true);
  rejectMode = 'ok';
  await approve(true);
  expect(rejects).toBe(2);
  expect(host.statusRequests()).toHaveLength(0);
});

it('a second window drops every copy immediately on a terminal pending stream event', async () => {
  frame(SHELL_EVENT_STREAM_IDS.PENDING_AUTHORIZATIONS, { ...detail, disposition: 'DENIED' }, 2);
  await settle();
  expect(host.statusRequests()).toHaveLength(0);
  expect(dialog().open).toBe(false);
  expect(writes).toBe(0);
  // A late replay of the arrival cannot resurrect the cancelled ceremony.
  frame(SHELL_EVENT_STREAM_IDS.PENDING_AUTHORIZATIONS, detail, 3);
  await settle();
  expect(host.statusRequests()).toHaveLength(0);
});

it('a lost rejection response is safely retried and a terminal refusal completes cancellation', async () => {
  rejectMode = 'lost';
  await approve(true);
  expect(host.statusRequests()).toHaveLength(1);
  expect(pending).toBe(false);
  rejectMode = 'ok';
  await approve(true);
  expect(rejects).toBe(2);
  expect(host.statusRequests()).toHaveLength(0);
  expect(writes).toBe(0);
});
