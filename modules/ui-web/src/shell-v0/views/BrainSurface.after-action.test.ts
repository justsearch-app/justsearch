// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import initialStatus from '../../api/__fixtures__/status-response-live.json';
const send = vi.hoisted(() => vi.fn());
vi.mock('../api/authorizedFetch.js', async () => {
  const { observeStatusResponse } = await import('../../api/statusInvalidation.js');
  return { authorizedFetch: async (input: RequestInfo | URL, init?: RequestInit) => {
    const response = await send(input, init);
    await observeStatusResponse(input, response);
    return response;
  } };
});
import './BrainSurface.js';
import './HealthSurface.js';
import { render, type TemplateResult } from 'lit';
import { getAiState, __feedForTest, __resetAiStateForTest } from '../state/aiStateStore.js';
import { subscribeStatus, __resetStatusPollForTest } from '../utils/statusPoll.js';
import type { StatusSnapshot } from '../utils/statusPoll.js';

let unsubscribe: (() => void) | undefined;
let latest: StatusSnapshot | null | undefined;
let changed = false;
beforeEach(() => { vi.useFakeTimers(); send.mockReset(); latest = undefined; changed = false; });
afterEach(() => { unsubscribe?.(); __resetStatusPollForTest(); __resetAiStateForTest(); vi.useRealTimers(); });
function json(body: unknown) { return new Response(JSON.stringify(body), { status: 200, headers: {
  'X-JustSearch-Status-Epoch': 'test', 'X-JustSearch-Status-Change': String(Number(changed)),
  'X-JustSearch-Status-Reflected': String(Number(changed)),
} }); }
async function begin() {
  send.mockImplementation(async (url: string, init?: RequestInit) => {
    if (url.includes('/api/status')) {
      const body = structuredClone(initialStatus);
      body.operating.chat.state = 'READY';
      if (changed) body.operating.chat.state = 'OFF';
      return json(body);
    }
    if (url.endsWith('/api/settings/v2')) {
      if (!init?.method) return json({ witness: { acceptedRevision: 0, lastCommittedOperationKey: null } });
      const body = JSON.parse(String(init.body)); changed = true;
      return json({ state: 'COMPLETE', operationKey: body.operationKey,
        witness: { acceptedRevision: 1, lastCommittedOperationKey: body.operationKey }, llm: body.llm });
    }
    if (url.endsWith('/api/operations/core.set-chat-enabled/invoke')) {
      changed = true; return json({ success: true, structuredData: {} });
    }
    if (url.endsWith('/api/inference/status')) return json({ mode: 'OFF', available: false });
    throw Error('unexpected request ' + url);
  });
  unsubscribe = subscribeStatus(value => { latest = value; });
  await vi.advanceTimersByTimeAsync(0);
  expect(latest?.operating?.chat?.state, 'canonical fixture accepted before the action').toBe('READY');
  send.mockClear();
  return document.createElement('jf-brain-surface') as HTMLElement & {
    apiBase: string; llm: { gpuLayers: number }; runtimeError: string | null;
    patchLlm(patch: { gpuLayers: number }): Promise<void>;
    setChatEnabled(enabled: boolean): Promise<void>;
    invokeOp(id: string, args: Record<string, unknown>): Promise<unknown>;
  };
}
it('GPU setting completion refreshes shared canonical status before the next poll', async () => {
  const brain = await begin(); brain.apiBase = ''; brain.llm = { gpuLayers: 4 };
  await brain.patchLlm({ gpuLayers: 0 });
  expect(brain.runtimeError).toBeNull(); expect(brain.llm.gpuLayers).toBe(0);
  expect(send.mock.calls.map(([url]) => String(url)), 'successful GPU action refreshes canonical status')
    .toContain('/api/status?fresh=true');
  expect(latest?.operating?.chat?.state).toBe('OFF');
});
it('chat disable refreshes shared canonical status before the next poll', async () => {
  const brain = await begin(); brain.apiBase = '';
  const invoke = vi.spyOn(brain, 'invokeOp'); // Keep the actual operation completion path.
  await brain.setChatEnabled(false);
  expect(invoke).toHaveBeenCalledWith('core.set-chat-enabled', { enabled: false });
  const immediate = latest?.operating?.chat?.state;
  await vi.advanceTimersByTimeAsync(10000);
  expect(latest?.operating?.chat?.state, 'scheduled poll sees completed action').toBe('OFF');
  expect(immediate, 'canonical status shows completed action without waiting for next poll').toBe('OFF');
});

it('chat capability follows canonical READY even before the legacy inference poll catches up', () => {
  const status = structuredClone(initialStatus) as StatusSnapshot;
  status.operating!.chat!.state = 'READY';
  status.inference = { ...status.inference, chatEnabledSpec: true, engineState: 'Healthy' };
  __feedForTest({ status, inference: { mode: 'offline', available: false } });
  expect(getAiState().capabilities.chat).toBe(true);
});
it('Health engine label follows canonical Down while the legacy inference poll is still online', () => {
  const status = structuredClone(initialStatus) as StatusSnapshot;
  status.operating!.chat!.state = 'OFF';
  status.inference = { ...status.inference, chatEnabledSpec: false, engineState: 'Down' };
  __feedForTest({ status, inference: { mode: 'online', available: true } });
  const health = document.createElement('jf-health-surface') as HTMLElement & {
    aiState: ReturnType<typeof getAiState>; inference: { mode: string; available: boolean };
    renderAiEngine(): TemplateResult;
  };
  health.aiState = getAiState(); health.inference = { mode: 'online', available: true };
  const container = document.createElement('div'); render(health.renderAiEngine(), container);
  expect(container.textContent).toContain('Offline');
  expect(container.textContent).not.toContain('Online');
});
