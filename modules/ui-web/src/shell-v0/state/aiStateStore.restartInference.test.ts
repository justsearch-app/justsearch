// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// b82 S5: a restart signal revokes everything the previous process reported. Inference data from
// the previous head must not keep the Brain Online, nor offer Shut Down AI, while the new head has
// confirmed nothing.
import { afterEach, expect, it, vi } from 'vitest';
import { render, type TemplateResult } from 'lit';
import fixture from '../../api/__fixtures__/status-response-live.json';
import { observeStatusResponse } from '../../api/statusInvalidation.js';
import { startAiStateStore, stopAiStateStore, subscribeAiState, getAiState, __resetAiStateForTest } from './aiStateStore.js';
import { __resetStatusPollForTest } from '../utils/statusPoll.js';
import '../views/BrainSurface.js';

afterEach(() => { stopAiStateStore(); __resetAiStateForTest(); __resetStatusPollForTest(); vi.useRealTimers(); vi.unstubAllGlobals(); });

const headers = (epoch: string, change: number) => ({
  'X-JustSearch-Status-Epoch': epoch, 'X-JustSearch-Status-Change': String(change),
  'X-JustSearch-Status-Reflected': String(change),
});

it('a restarted head that confirms nothing cannot inherit the previous head Online state', async () => {
  vi.useFakeTimers();
  const healthy = structuredClone(fixture);
  healthy.operating.chat.state = 'READY';
  const send = vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes('/api/status')) return new Response(JSON.stringify(healthy), { headers: headers('A', 3) });
    if (url.includes('/api/inference/status')) return new Response(JSON.stringify(
      { mode: 'online', available: true, activeModelId: 'model-a.gguf' }), { headers: headers('A', 3) });
    return new Response('{}', { status: 404, headers: headers('A', 3) });
  });
  vi.stubGlobal('fetch', send);
  startAiStateStore('');
  const brain = document.createElement('jf-brain-surface') as HTMLElement & {
    apiBase: string; busy: Record<string, boolean>; policy: unknown; installStatus: unknown;
    _unifiedAiState: unknown; renderSimplePanel(): TemplateResult;
  };
  brain.apiBase = ''; brain.busy = {}; brain.installStatus = null;
  brain.policy = { downloadsEnabled: true, onlineAiEnabled: true };
  // The Brain reads the store exactly as it does when connected.
  const unsubscribe = subscribeAiState(state => { brain._unifiedAiState = state; });
  const panel = () => { const c = document.createElement('div'); render(brain.renderSimplePanel(), c); return c.textContent ?? ''; };
  await vi.advanceTimersByTimeAsync(0);
  expect(getAiState().runtime.mode, 'head A is online').toBe('online');
  expect(getAiState().capabilities.chat).toBe(true);
  expect(panel()).toContain('Shut Down AI');

  // Head B: every status and inference read is refused.
  send.mockImplementation(async () => new Response('{}', { status: 500, headers: headers('B', 1) }));
  await observeStatusResponse('/api/read-only', new Response('{}', { headers: headers('B', 1) }));
  await vi.advanceTimersByTimeAsync(10000);
  const state = getAiState();
  const text = panel();
  unsubscribe();
  console.log(`B82-RESTART-INFERENCE mode=${state.runtime.mode} chat=${state.capabilities.chat} engine=${state.aiEngine.kind}`);
  expect(state.capabilities.chat, 'chat is not available on a head that confirmed nothing').toBe(false);
  expect(state.runtime.mode, 'the Brain is not Online from the previous head').not.toBe('online');
  expect(state.aiEngine.kind).not.toBe('online');
  expect(text, 'no Shut Down AI for an engine nothing confirmed').not.toContain('Shut Down AI');
});
