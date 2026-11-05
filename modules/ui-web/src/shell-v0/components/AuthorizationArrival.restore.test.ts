// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// L12/L15 — outside requests through the real host, pill and bridge on one multiplexed stream.
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { AuthorizationHost } from './AuthorizationHost.js';
import { StatusPill } from './StatusPill.js';
import { startPendingAuthorizationBridge } from '../operations/pendingAuthorizationBridge.js';
import { MultiplexedStream } from '../streaming/MultiplexedStream.js';
import type { SseEnvelope } from '../streaming/envelope-types.js';
import { SHELL_EVENT_STREAM_IDS } from '../streaming/shellEventStreamIds.js';
import { __resetAiStateForTest } from '../state/aiStateStore.js';
import { sendDesktopNotification } from '../../utils/notify.js';

vi.mock('../../utils/windowFocus.js', () => ({ isWindowFocused: vi.fn(async () => false) }));
vi.mock('../../utils/notify.js', () => ({ sendDesktopNotification: vi.fn(async () => {}) }));
vi.mock('../utils/statusPoll.js', () => ({ subscribeStatus: () => () => {} }));

class Source extends EventTarget {
  close() {}
  frame(envelope: SseEnvelope) {
    this.dispatchEvent(new MessageEvent('frame', { data: JSON.stringify(envelope) }));
  }
}
let host: AuthorizationHost;
let pill: StatusPill;
let mux: MultiplexedStream;
let source: Source;
let stop: () => void;
let seq: number;
/** Requests the Engine still holds, by id. */
let held: Set<string>;
async function flush() {
  for (let i = 0; i < 20; i++) await Promise.resolve();
  await host.updateComplete;
  pill.status.refresh();
  await pill.updateComplete;
}
const frame = (streamId: string, frameKind: 'UPDATE' | 'LIFECYCLE', payload: unknown) => source.frame({ streamId, frameKind,
  seq: ++seq, ts: new Date().toISOString(), resumeToken: `rt-${seq}`, payload } as SseEnvelope);
/** An action-ledger gate row as ActionLedgerProjection.toWireRow writes it. */
const gateRow = (pendingId: string, disposition: string, transport: string, occurredAt: string) => ({
  id: `gate:${occurredAt}:core.ingest-files:${disposition}:${pendingId}`, kind: 'gate', occurredAt, originator: 'agent', transport,
  operationId: 'core.ingest-files', disposition, gateBehavior: 'INLINE_CONFIRM', sourceTier: 'UNTRUSTED', pendingId });
const needs = () => pill.status.model.sections.needs.map(row => row.id);
const earlier = () => pill.status.model.sections.earlier.map(row => row.title);
beforeEach(async () => {
  vi.clearAllMocks();
  __resetAiStateForTest();
  seq = 0; held = new Set();
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes('/api/authorizations/pending/')) {
      const id = decodeURIComponent(url.split('/').pop()!);
      return held.has(id) ? new Response(JSON.stringify({ pendingId: id, operationId: 'core.ingest-files', argsSummary: '/tmp/invoices',
        gateBehavior: 'INLINE_CONFIRM', riskTier: 'MEDIUM', sourceTier: 'UNTRUSTED',
        expiresAt: new Date(Date.now() + 240_000).toISOString() }), { status: 200 }) : new Response('{}', { status: 404 });
    }
    return new Response('{}', { status: 503 });
  }));
  host = new AuthorizationHost(); document.body.append(host);
  source = new Source();
  mux = new MultiplexedStream({ url: 'http://test/events', eventSourceFactory: () => source as unknown as EventSource });
  pill = new StatusPill(); pill.authorizationHost = () => host; pill.multiplex = mux; document.body.append(pill);
  host.addEventListener('status-requests-changed', () => pill.status.refresh());
  mux.start(); source.dispatchEvent(new Event('open'));
  stop = startPendingAuthorizationBridge('', { multiplex: mux });
  await flush();
});
afterEach(() => {
  stop(); mux.stop(); pill.remove(); host.remove();
  __resetAiStateForTest(); vi.unstubAllGlobals();
});

it('L15 after a reload an HTTP request the Engine still holds stays in Needs you, silently, until its outcome', async () => {
  // The request was made before this window opened; the window only has the ledger's open row.
  held.add('http-1');
  const asked = new Date(Date.now() - 60_000).toISOString();
  frame(SHELL_EVENT_STREAM_IDS.ACTION_LEDGER, 'LIFECYCLE', { kind: 'snapshot', entries: [gateRow('http-1', 'GATED', 'HTTP_API', asked)] });
  await flush();
  expect(needs()).toEqual(['authorization:http-1']);
  expect(earlier().join(' | '), 'never "no longer pending" while it waits').not.toMatch(/no longer pending/i);
  // Restored, not arrived: no announcement and no desktop notification.
  expect(pill.status.assertive).toBe('');
  expect(sendDesktopNotification).not.toHaveBeenCalled();
  expect(host.shadowRoot!.querySelector('dialog')!.open).toBe(false);
  // Status refreshes keep it while the Engine holds it.
  for (let i = 0; i < 3; i++) { pill.status.refresh(); await flush(); }
  expect(needs()).toEqual(['authorization:http-1']);
  // Its outcome moves it to Earlier.
  held.delete('http-1');
  frame(SHELL_EVENT_STREAM_IDS.PENDING_AUTHORIZATIONS, 'UPDATE', { pendingId: 'http-1', operationId: 'core.ingest-files',
    transport: 'HTTP_API', disposition: 'DENIED' });
  frame(SHELL_EVENT_STREAM_IDS.ACTION_LEDGER, 'UPDATE', gateRow('http-1', 'DENIED', 'HTTP_API', new Date().toISOString()));
  await flush();
  expect(needs()).toEqual([]);
  expect(earlier()).toEqual(['Denied by you: Ingest Files']);
});

it('L15 an open ledger row the Engine no longer holds reads "no longer pending" once it says so', async () => {
  const asked = new Date(Date.now() - 60_000).toISOString();
  frame(SHELL_EVENT_STREAM_IDS.ACTION_LEDGER, 'LIFECYCLE', { kind: 'snapshot', entries: [gateRow('gone-1', 'GATED', 'HTTP_API', asked)] });
  await flush();
  expect(needs()).toEqual([]);
  expect(earlier()).toEqual(['Approval no longer pending: Ingest Files']);
});

it('L12 a live request that names no source waits in Needs you with one announcement; no dialog opens', async () => {
  held.add('unnamed');
  frame(SHELL_EVENT_STREAM_IDS.PENDING_AUTHORIZATIONS, 'UPDATE', { pendingId: 'unnamed', operationId: 'core.ingest-files',
    gateBehavior: 'TYPED_CONFIRM', riskTier: 'MEDIUM', createdAt: new Date().toISOString() });
  await flush();
  expect(host.shadowRoot!.querySelector('dialog')!.open, 'no dialog on arrival').toBe(false);
  expect(needs()).toEqual(['authorization:unnamed']);
  expect(pill.status.assertive).toBe('Needs you: An agent requests Ingest Files.');
  const key = pill.status.assertiveKey;
  for (let i = 0; i < 3; i++) { pill.status.refresh(); await flush(); }
  expect(pill.status.assertiveKey, 'announced once').toBe(key);
  expect(sendDesktopNotification).toHaveBeenCalledTimes(1);
});
