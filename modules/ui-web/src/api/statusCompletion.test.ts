// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import fixture from './__fixtures__/status-response-live.json';
import { consumeShapeStream } from './streams.js';
import { request } from './http.js';
import { OperationClient } from '../shell-v0/operations/OperationClient.js';
import { startPendingAuthorizationBridge } from '../shell-v0/operations/pendingAuthorizationBridge.js';
import { setAuthorizationPresenter } from '../shell-v0/operations/authorizationBroker.js';
import { AuthorizationHost } from '../shell-v0/components/AuthorizationHost.js';
import '../shell-v0/components/AuthorizationHost.js';
import { MultiplexedStream } from '../shell-v0/streaming/MultiplexedStream.js';
import { SHELL_EVENT_STREAM_IDS } from '../shell-v0/streaming/shellEventStreamIds.js';
import { createPluginAI } from '../shell-v0/plugin-api/capabilities/ai.js';
import { createDataApi } from '../shell-v0/plugin-api/capabilities/data.js';
import { registerCommand, unregisterCommand, invokeCommandWithResult } from '../shell-v0/commands/CommandRegistry.js';
import { bootVirtualOperationCatalog, decorateCommandForAgent, clearAgentDecoration, commandIdToWireName } from '../shell-v0/commands/VirtualOperationCatalog.js';
import { dispatchVirtualToolCall } from '../shell-v0/commands/VirtualToolDispatcher.js';
import { CORE_PROVENANCE } from '../shell-v0/primitives/provenance.js';
import { subscribeStatus, __resetStatusPollForTest, type StatusSnapshot } from '../shell-v0/utils/statusPoll.js';

const send = vi.fn();
const json = (v: unknown, status = 200) => new Response(JSON.stringify(v), { status, headers: { 'X-JustSearch-Status-Epoch': 'test', 'X-JustSearch-Status-Change': changed ? '1' : '0', 'X-JustSearch-Status-Reflected': changed ? '1' : '0' } });
let latest: StatusSnapshot | null;
let changed: boolean;
let stopBridge: (() => void) | undefined;
let host: AuthorizationHost | undefined;
beforeEach(async () => {
  vi.useFakeTimers(); vi.stubGlobal('fetch', send); send.mockReset(); changed = false; latest = null;
  send.mockImplementation(async (input: RequestInfo | URL) => {
    if (String(input).includes('/api/status')) {
      const s = structuredClone(fixture); s.operating.chat.state = changed ? 'OFF' : 'READY';
      s.inference.chatEnabledSpec = !changed; return json(s);
    }
    changed = true; return json({ success: true, message: 'Settings committed' });
  });
  subscribeStatus(s => { latest = s; });
  await vi.advanceTimersByTimeAsync(0); expect(state()).toBe('READY'); send.mockClear();
});
afterEach(() => {
  stopBridge?.(); stopBridge = undefined; host?.remove(); host = undefined;
  setAuthorizationPresenter(null); __resetStatusPollForTest(); vi.unstubAllGlobals(); vi.useRealTimers();
});
const state = () => latest?.operating?.chat?.state;
const fresh = () => send.mock.calls.filter(([url]) => String(url).endsWith('/api/status?fresh=true'));

it('approved server execution publishes canonical OFF before the client returns', async () => {
  const client = new OperationClient({ apiBase: '', fetchImpl: async () => {
    changed = true; return json({ executed: true, executeSuccess: true, executeMessage: 'Settings committed' });
  } });
  expect(await client.approveAndExecutePending('pending')).toMatchObject({ executed: true, executeSuccess: true });
  expect(state(), 'successful approved execution must publish without a scheduled timer').toBe('OFF');
  expect(fresh()).toHaveLength(1);
});

it.each([{ executed: false, executeSuccess: true }, { executed: true, executeSuccess: false }, { executed: true }])
  ('does not refresh for skipped, failed or unconfirmed approval %j', async result => {
    const client = new OperationClient({ apiBase: '', fetchImpl: async () => json(result) });
    await client.approveAndExecutePending('pending'); expect(fresh()).toHaveLength(0); expect(state()).toBe('READY');
  });

it('mounted authorization host and real pending bridge refresh after the typed approval', async () => {
  host = document.createElement('jf-authorization-host') as AuthorizationHost;
  document.body.appendChild(host); await host.updateComplete;
  const es = Object.assign(new EventTarget(), { close() {}, readyState: 1 });
  const mux = new MultiplexedStream({ url: '/api/shell-events/stream', eventSourceFactory: () => es as EventSource });
  mux.start(); es.dispatchEvent(new Event('open'));
  stopBridge = () => { stop(); mux.stop(); };
  const stop = startPendingAuthorizationBridge('', { multiplex: mux });
  const normal = send.getMockImplementation()!;
  send.mockImplementation(async (input, init) => {
    if (String(input).includes('/api/authorizations/pending/')) return json({ pendingId: 'pending',
      operationId: 'core.switch-inference-mode', argsSummary: 'Disable chat AI', sourceTier: 'UNTRUSTED',
      riskTier: 'MEDIUM', gateBehavior: 'TYPED_CONFIRM', rationale: 'Approval required' });
    if (String(input).endsWith('/api/authorizations/approve')) {
      expect(JSON.parse(String(init?.body))).toMatchObject({ pendingId: 'pending', execute: true });
      changed = true; return json({ executed: true, executeSuccess: true, executeMessage: 'Settings committed' });
    }
    return normal(input, init);
  });
  es.dispatchEvent(new MessageEvent('frame', { data: JSON.stringify({ streamId: SHELL_EVENT_STREAM_IDS.PENDING_AUTHORIZATIONS,
    frameKind: 'UPDATE', seq: 1, ts: new Date().toISOString(), resumeToken: 'r1', payload: {
      pendingId: 'pending', operationId: 'core.switch-inference-mode', gateBehavior: 'TYPED_CONFIRM' } }) }));
  await vi.advanceTimersByTimeAsync(0); await host.updateComplete;
  const input = host.shadowRoot!.querySelector('[data-testid="authorization-typed-input"]') as HTMLInputElement;
  expect(input).not.toBeNull(); input.value = 'core.switch-inference-mode'; input.dispatchEvent(new Event('input'));
  await host.updateComplete;
  const approve = host.shadowRoot!.querySelector('[data-testid="authorization-approve"]')!;
  await (approve as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  const control = approve.shadowRoot!.querySelector('jf-control')!;
  await (control as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  (control.shadowRoot!.querySelector('button') as HTMLButtonElement).click();
  await vi.advanceTimersByTimeAsync(0); await host.updateComplete;
  expect(state(), 'the real approval bridge must reach canonical completion').toBe('OFF'); expect(fresh()).toHaveLength(1);
});

const frames = (operation: string | null, success: boolean) => [
  ...(operation ? [{ event: 'tool_exec_started', payload: { callId: 'call', toolName: operation } }] : []),
  { event: 'tool_exec_completed', payload: { callId: 'call', success, executionId: 'executed', output: 'Settings committed' } },
  { event: 'done', payload: {} },
];
function stream(operation: string | null, success = true) {
  changed = success && (operation === null || operation === 'core_set_chat_enabled');
  return new Response(frames(operation, success).map(f => `event: ${f.event}\ndata: ${JSON.stringify({ ...f.payload, statusChangeEpoch: 'test', statusChangeCounter: changed ? 1 : 0 })}\n\n`).join(''),
    { headers: { 'Content-Type': 'text/event-stream' } });
}
it.each(['host', 'direct', 'resume', 'reattach'] as const)('%s stream publishes successful execution before delivering completion', async path => {
  const normal = send.getMockImplementation()!;
  send.mockImplementation((input, init) => String(input).includes('/api/status') ? normal(input, init) : stream('core_set_chat_enabled'));
  const seen: Array<string | undefined> = [];
  if (path === 'host') {
    for await (const f of createPluginAI('CORE', '').streamShape('core.agent-run', {})) {
      if (f.name === 'tool_exec_completed') seen.push(state());
    }
  } else {
    await consumeShapeStream(path === 'direct' ? '/api/chat/dispatch' : `/api/chat/runs/run/${path === 'resume' ? 'resume' : 'stream'}`,
      {}, event => { if (event === 'tool_exec_completed') seen.push(state()); }, undefined, path === 'reattach' ? { sinceSeq: 20 } : undefined);
  }
  expect(seen, 'completion renders must use the fresh canonical observation').toEqual(['OFF']); expect(fresh()).toHaveLength(1);
});
it.each(['host', 'direct'] as const)('%s stream refreshes when reconnect omitted the operation name', async path => {
  const normal = send.getMockImplementation()!;
  send.mockImplementation((input, init) => String(input).includes('/api/status') ? normal(input, init) : stream(null));
  if (path === 'host') { for await (const _ of createPluginAI('CORE', '').streamShape('core.agent-run', {})) { /* drain */ } }
  else await consumeShapeStream('/api/chat/runs/run/stream', {}, () => {}, undefined, { sinceSeq: 20 });
  expect(state()).toBe('OFF'); expect(fresh()).toHaveLength(1);
});
it.each(['host', 'direct'] as const)('%s stream ignores failed and known read-only tools', async path => {
  const normal = send.getMockImplementation()!;
  for (const [operation, success] of [['core_set_chat_enabled', false], ['core.search-index', true], ['core_search_index', true], ['vop_shell_navigate', true]] as const) {
    send.mockImplementation((input, init) => String(input).includes('/api/status') ? normal(input, init) : stream(operation, success));
    if (path === 'host') { for await (const _ of createPluginAI('CORE', '').streamShape('core.agent-run', {})) { /* drain */ } }
    else await consumeShapeStream('/api/chat/dispatch', {}, () => {});
  }
  expect(fresh()).toHaveLength(0); expect(state()).toBe('READY');
});
it('plugin operation capability reaches the same canonical completion owner', async () => {
  await createDataApi('CORE', '').invokeOperation('core.set-chat-enabled', { enabled: false });
  expect(state()).toBe('OFF'); expect(fresh()).toHaveLength(1);
});
it('trusted plugin raw operation fetch observes the execution receipt without consuming it', async () => {
  const response = await createDataApi('CORE', '').fetch('/api/operations/core.set-chat-enabled/invoke', { method: 'POST' });
  expect(await response.json()).toMatchObject({ success: true }); expect(state()).toBe('OFF'); expect(fresh()).toHaveLength(1);
});
it.each(['command', 'virtual'] as const)('%s invocation completing through plugin data observes status', async path => {
  const id = 'status-command'; const data = createDataApi('CORE', '');
  registerCommand({ id, label: 'Change chat', source: 'shell', provenance: CORE_PROVENANCE,
    handler: async () => { await data.invokeOperation('core.set-chat-enabled', { enabled: false }); } });
  const stop = bootVirtualOperationCatalog(); decorateCommandForAgent(id, { agentVisible: true });
  try {
    const result = path === 'command' ? await invokeCommandWithResult(id) : await dispatchVirtualToolCall({
      sessionId: 'session', callId: 'call', wireName: commandIdToWireName(id), arguments: '{}' }, { apiBase: '' });
    expect(result.ok).toBe(true); expect(state()).toBe('OFF'); expect(fresh()).toHaveLength(1);
  } finally { clearAgentDecoration(id); unregisterCommand(id); stop(); }
});
it('consented retry waits for execution rather than capsule minting', async () => {
  let invocations = 0;
  const client = new OperationClient({ apiBase: '', fetchImpl: async input => {
    if (String(input).endsWith('/api/authorizations/approve')) {
      expect(fresh()).toHaveLength(0); return json({ capsule: 'approved' });
    }
    if (++invocations === 1) return json({ success: false, errorClass: 'CONFIRMATION_REQUIRED', pendingId: 'pending' }, 428);
    changed = true; return json({ success: true });
  } });
  await client.invokeWithConsent('core.set-chat-enabled', {}, { consented: true });
  expect(invocations).toBe(2); expect(state()).toBe('OFF'); expect(fresh()).toHaveLength(1);
});
it('fifty approved executions coalesce into one canonical observation', async () => {
  const client = new OperationClient({ apiBase: '', fetchImpl: async () => {
    changed = true; return json({ executed: true, executeSuccess: true });
  } });
  await Promise.all(Array.from({ length: 50 }, (_, i) => client.approveAndExecutePending(`pending-${i}`)));
  expect(state()).toBe('OFF'); expect(fresh()).toHaveLength(1);
});
it.each(['core.ingest-files', 'core.file-operations', 'plugin.change-runtime'])('%s execution reaches canonical completion', async operation => {
  await new OperationClient({ apiBase: '' }).invoke(operation);
  expect(state()).toBe('OFF'); expect(fresh()).toHaveLength(1);
});
it('domain HTTP operation request observes its already-decoded receipt', async () => {
  expect(await request('', '/api/operations/core.set-chat-enabled/invoke', { method: 'POST' })).toMatchObject({ success: true });
  expect(state()).toBe('OFF'); expect(fresh()).toHaveLength(1);
});
