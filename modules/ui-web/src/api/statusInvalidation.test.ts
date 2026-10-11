// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import fixture from './__fixtures__/status-response-live.json';
import { request } from './http.js';
import { saveAbsoluteSettings } from './settingsAttempt.js';
import { authorizedFetch } from '../shell-v0/api/authorizedFetch.js';
import { OperationClient } from '../shell-v0/operations/OperationClient.js';
import { requestLoadAnyway } from '../shell-v0/state/chatResidency.js';
import { subscribeStatus, __resetStatusPollForTest, type StatusSnapshot } from '../shell-v0/utils/statusPoll.js';

const send = vi.fn();
let latest: StatusSnapshot | null;
let changed = false;
const client = new OperationClient({ apiBase: '' });
const json = (value: unknown, status = 200, headers?: HeadersInit) =>
  new Response(JSON.stringify(value), { status, headers: { 'X-JustSearch-Status-Epoch': 'test', 'X-JustSearch-Status-Change': changed ? '1' : '0', 'X-JustSearch-Status-Reflected': changed ? '1' : '0', ...Object.fromEntries(new Headers(headers).entries()) } });
beforeEach(async () => {
  vi.useFakeTimers(); vi.stubGlobal('fetch', send); send.mockReset(); changed = false; latest = null;
  send.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.includes('/api/status')) {
      const body = structuredClone(fixture); body.operating.chat.state = changed ? 'OFF' : 'READY';
      return json(body);
    }
    if (url.endsWith('/api/settings/v2')) {
      if (!init?.method) return json({ witness: { acceptedRevision: 0, lastCommittedOperationKey: null } });
      changed = true; const body = JSON.parse(String(init.body));
      return json({ state: 'COMPLETE', operationKey: body.operationKey,
        witness: { acceptedRevision: 1, lastCommittedOperationKey: body.operationKey } });
    }
    if (!url.includes('search-index') && !url.includes('/preview')) changed = true;
    return json({ success: true });
  });
  subscribeStatus(value => { latest = value; });
  await vi.advanceTimersByTimeAsync(0);
  expect(chatState()).toBe('READY'); send.mockClear();
});
afterEach(() => { __resetStatusPollForTest(); vi.unstubAllGlobals(); vi.useRealTimers(); });
function freshReads() { return send.mock.calls.filter(([url]) => String(url).endsWith('/api/status?fresh=true')); }
function chatState() { return latest?.operating?.chat?.state; }

it.each(['core.set-chat-enabled', 'core.activate-runtime-variant', 'core.deactivate-runtime-variant',
  'core.activate-installed-models', 'core.reconfigure', 'core.recover-component', 'core.add-watched-root',
  'core.remove-watched-root', 'core.reindex', 'core.cancel-reindex', 'core.accept-gaps', 'core.reset-settings'])
  ('%s publishes canonical state from its actual operation receipt', async operationId => {
    await client.invoke(operationId);
    expect(latest?.operating?.chat?.state).toBe('OFF'); expect(freshReads()).toHaveLength(1);
  });
it('undo publishes canonical state from its actual receipt', async () => {
  await client.undo('core.remove-watched-root', 'execution');
  expect(latest?.operating?.chat?.state).toBe('OFF'); expect(freshReads()).toHaveLength(1);
});
it.each(['/api/ai/install/pause', '/api/ai/install/resume', '/api/ai/runtime/activate',
  '/api/indexing/migration/pause', '/api/indexing/migration/resume', '/api/indexing/roots',
  '/api/lifecycle/shutdown', '/api/upgrade/cancel', '/api/upgrade/reconcile', '/api/upgrade/commit-shutdown', '/api/upgrade/prepare', '/api/worker/restart'])('direct %s updates the production poller', async path => {
    await authorizedFetch(path, { method: 'POST' });
    expect(latest?.operating?.chat?.state).toBe('OFF'); expect(freshReads()).toHaveLength(1);
  });
it('the real load-anyway handler publishes canonical state after a successful load request', async () => {
  expect((await requestLoadAnyway('', authorizedFetch)).ok).toBe(true);
  expect(chatState()).toBe('OFF'); expect(freshReads()).toHaveLength(1);
});
it('domain HTTP root removal uses the same canonical read', async () => {
  await request('', '/api/indexing/roots', { method: 'DELETE' });
  expect(latest?.operating?.chat?.state).toBe('OFF'); expect(freshReads()).toHaveLength(1);
});
it('coalesces a concurrent burst of successful actions into one fresh read', async () => {
  await Promise.all(Array.from({ length: 30 }, () => client.invoke('core.set-chat-enabled')));
  expect(latest?.operating?.chat?.state).toBe('OFF'); expect(freshReads()).toHaveLength(1);
});
it('keeps a successful action behind real status admission until the shared read publishes', async () => {
  const normal = send.getMockImplementation()!;
  let refused = false;
  send.mockImplementation((input, init) => {
    if (String(input).endsWith('?fresh=true') && !refused) {
      refused = true;
      return Promise.resolve(json({ errorCode: 'ADMISSION_ENGINE_LIMIT', retrySafe: true }, 429, { 'Retry-After': '1' }));
    }
    return normal(input, init);
  });
  let completed = false;
  const action = client.invoke('core.set-chat-enabled').then(() => { completed = true; });
  await vi.advanceTimersByTimeAsync(0);
  expect(completed).toBe(false); expect(latest?.operating?.chat?.state).toBe('READY');
  await vi.advanceTimersByTimeAsync(1000); await action;
  expect(latest?.operating?.chat?.state).toBe('OFF'); expect(freshReads()).toHaveLength(2);
});
it('waits for COMPLETE settings before publishing GPU/profile state', async () => {
  const normal = send.getMockImplementation()!;
  let pending = true;
  send.mockImplementation((input, init) => {
    if (String(input).endsWith('/api/settings/v2') && init?.method && pending) {
      pending = false;
      return Promise.resolve(json({ state: 'RUNNING', operationKey: JSON.parse(String(init.body)).operationKey }, 202));
    }
    return normal(input, init);
  });
  const action = saveAbsoluteSettings(authorizedFetch, { llm: { gpuLayers: 0 } });
  await vi.advanceTimersByTimeAsync(0); expect(freshReads()).toHaveLength(0);
  await vi.advanceTimersByTimeAsync(250); await action;
  expect(latest?.operating?.chat?.state).toBe('OFF'); expect(freshReads()).toHaveLength(1);
});
it('failed operations and read-only operations do not request fresh status', async () => {
  const normal = send.getMockImplementation()!;
  send.mockImplementation((input, init) => String(input).includes('set-chat-enabled')
    ? Promise.resolve(json({ success: false, message: 'refused' })) : normal(input, init));
  await expect(client.invoke('core.set-chat-enabled')).rejects.toThrow('refused');
  await client.invoke('core.search-index');
  await authorizedFetch('/api/indexing-roots/preview', { method: 'POST' });
  expect(freshReads()).toHaveLength(0);
});

it('shows a change made by another client after this UI next makes a read-only request', async () => {
  changed = true; // another client already changed the server-owned state
  await authorizedFetch('/api/settings/v2');
  expect(chatState()).toBe('OFF'); expect(freshReads()).toHaveLength(1);
  send.mockClear(); await authorizedFetch('/api/settings/v2');
  expect(freshReads()).toHaveLength(0);
});
