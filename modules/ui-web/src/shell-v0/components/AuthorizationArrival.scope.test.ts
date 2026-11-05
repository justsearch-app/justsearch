// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { AuthorizationHost } from './AuthorizationHost.js';
import { StatusPill } from './StatusPill.js';
import type { StatusPanel } from './StatusPanel.js';
import { requestAuthorization, type AuthorizationPrompt } from '../operations/authorizationBroker.js';
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
interface RetainedRecord {
  liveArrival: boolean;
  event: { classId: string; classExtras: Record<string, unknown> };
}
let host: AuthorizationHost;
let pill: StatusPill;
let mux: MultiplexedStream;
let source: Source;
let stop: () => void;
let retained: (snapshot: { advisories: RetainedRecord[] }) => void;
let records: RetainedRecord[];
let seq: number;
let gate: string;
const panel = () => pill.shadowRoot!.querySelector<StatusPanel>('jf-status-panel')!;
async function flush() {
  for (let i = 0; i < 20; i++) await Promise.resolve();
  await host.updateComplete;
  pill.status.refresh();
  await pill.updateComplete;
  await panel().updateComplete;
}
function bridge() {
  return startPendingAuthorizationBridge('', { multiplex: mux, retained: {
    subscribe(listener) {
      retained = listener;
      listener({ advisories: records });
      return () => {};
    },
  } });
}
function advisory(id: string, liveArrival = true) {
  records.push({ liveArrival, event: { classId: 'authorization.pending',
    classExtras: { pendingId: id, operationId: 'core.ingest-files', gateBehavior: gate, riskTier: 'MEDIUM' } } });
  retained({ advisories: records });
}
function event(id: string, transport = 'MCP') {
  source.frame({ streamId: SHELL_EVENT_STREAM_IDS.PENDING_AUTHORIZATIONS, frameKind: 'UPDATE',
    seq: ++seq, ts: new Date().toISOString(), resumeToken: `rt-${seq}`,
    payload: { pendingId: id, operationId: 'core.ingest-files', transport, gateBehavior: gate, riskTier: 'MEDIUM' } });
}
beforeEach(async () => {
  vi.clearAllMocks();
  __resetAiStateForTest();
  gate = 'TYPED_CONFIRM'; records = []; seq = 0;
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes('/api/authorizations/pending/')) return new Response(JSON.stringify({
      pendingId: url.split('/').pop(), operationId: 'core.ingest-files', argsSummary: '/tmp/invoices',
      gateBehavior: gate, riskTier: 'MEDIUM', sourceTier: 'UNTRUSTED',
    }), { status: 200 });
    return new Response('{}', { status: 503 });
  }));
  host = new AuthorizationHost(); document.body.append(host);
  pill = new StatusPill(); pill.authorizationHost = () => host; document.body.append(pill);
  host.addEventListener('status-requests-changed', () => pill.status.refresh());
  source = new Source();
  mux = new MultiplexedStream({ url: 'http://test/events', eventSourceFactory: () => source as unknown as EventSource });
  mux.start(); source.dispatchEvent(new Event('open'));
  stop = bridge();
  await flush();
});
afterEach(() => {
  stop(); mux.stop(); pill.remove(); host.remove();
  __resetAiStateForTest(); vi.unstubAllGlobals();
});

it.each(['TYPED_CONFIRM', 'INLINE_CONFIRM'])('A five live MCP %s arrivals announce once each when their advisory arrives first', async behavior => {
  gate = behavior;
  for (let i = 1; i <= 5; i++) {
    const id = `mcp-${i}`;
    advisory(id); await flush();
    expect(pill.status.model.pill.count).toBe(i);
    const alert = pill.shadowRoot!.querySelector('[role="alert"][aria-live="assertive"]')!;
    expect(alert.textContent).toBe('Needs you: An agent requests Ingest Files.');
    expect(sendDesktopNotification).toHaveBeenCalledTimes(i);
    event(id); retained({ advisories: records }); await flush();
    expect(pill.shadowRoot!.querySelector('[role="alert"]'), 'duplicate routes do not replace the announcement').toBe(alert);
    expect(sendDesktopNotification).toHaveBeenCalledTimes(i);
    expect(host.shadowRoot!.querySelector('dialog')!.open).toBe(false);
  }
});

it.each(['MCP', 'AGENT_LOOP', 'WORKFLOW', 'LLM_EMISSION', 'HTTP_API', 'URL_BAR', 'URL_DEEPLINK',
  'PLUGIN_EMITTED', 'SCHEDULED', 'RULE_ENGINE', 'SYSTEM_INTERNAL', 'NOT_YET_KNOWN'])('A a live %s typed arrival announces once when the event arrives first', async transport => {
  event('live', transport); await flush();
  expect(pill.status.assertive).toBe('Needs you: An agent requests Ingest Files.');
  expect(sendDesktopNotification).toHaveBeenCalledTimes(1);
  advisory('live'); await flush();
  expect(sendDesktopNotification).toHaveBeenCalledTimes(1);
});

it('A reload, reconnect snapshots and a later window restore requests silently', async () => {
  advisory('restored', false); await flush();
  expect(pill.status.model.pill.count).toBe(1);
  expect(pill.status.assertive).toBe('');
  retained({ advisories: records }); await flush();
  expect(sendDesktopNotification).not.toHaveBeenCalled();
  stop(); await flush();
  // A newly mounted window can subscribe to a store that has already seen live updates.
  records[0]!.liveArrival = true;
  stop = bridge(); await flush();
  expect(pill.status.model.pill.count).toBe(1);
  expect(pill.status.assertive).toBe('');
  expect(sendDesktopNotification).not.toHaveBeenCalled();
});

const scope = (operation: string) => `Always allow “${operation}” without asking, for any agent with this access`;
it.each([undefined, 'Claude Desktop'])('C panel consent names operation and access scope for requester %s', async requestedBy => {
  void requestAuthorization({ pendingId: 'scope', operationId: 'core.ingest-files', gateBehavior: 'INLINE_CONFIRM',
    riskTier: 'MEDIUM', argsSummary: '/tmp/invoices', requestedBy, arrival: 'needs-you', live: true });
  await flush(); pill.open = true; await flush();
  const row = panel().shadowRoot!.querySelector('[data-fact="authorization:scope"]')!;
  [...row.querySelectorAll('button')].find(button => button.textContent!.trim() === 'Review')!.click(); await flush();
  const label = panel().shadowRoot!.querySelector('label.always')!;
  const box = label.querySelector<HTMLInputElement>('input')!;
  expect(label.textContent!.trim()).toBe(scope('Ingest Files'));
  expect(box.labels?.[0], 'the visible scope is also the accessible name').toBe(label);
});

it.each([undefined, 'Claude Desktop'])('C protected dialog consent names operation and access scope for requester %s', async requestedBy => {
  void requestAuthorization({ pendingId: 'scope', operationId: 'core.ingest-files', gateBehavior: 'TYPED_CONFIRM',
    riskTier: 'MEDIUM', argsSummary: '/tmp/invoices', requestedBy });
  await flush();
  const label = host.shadowRoot!.querySelector('[data-testid="authorization-allow-always"]')!;
  const box = label.querySelector<HTMLInputElement>('input')!;
  expect(label.textContent!.trim()).toBe(scope('core.ingest-files'));
  expect(box.labels?.[0], 'the visible scope is also the accessible name').toBe(label);
});

it.each([{ riskTier: 'HIGH' }, { riskTier: 'MEDIUM', allowAlwaysSupported: false }])('C durable offering remains suppressed for %j', async extra => {
  const prompt: AuthorizationPrompt = { pendingId: 'no-durable', operationId: 'core.ingest-files',
    gateBehavior: 'TYPED_CONFIRM', argsSummary: '/tmp/invoices', ...extra };
  void requestAuthorization(prompt); await flush();
  expect(host.shadowRoot!.querySelector('[data-testid="authorization-allow-always"]')).toBeNull();
  expect(host.statusRequestState('no-durable')?.durable).toBe(false);
});
