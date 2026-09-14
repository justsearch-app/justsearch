// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './BrainSurface.js';
import { authorizedFetch } from '../api/authorizedFetch.js';

vi.mock('../api/authorizedFetch.js', () => ({ authorizedFetch: vi.fn() }));
interface BrainSettings extends HTMLElement {
  apiBase: string;
  llm: { contextWindow?: number; modelPath?: string; maxTokens?: number; gpuLayers?: number };
  runtimeError: string | null;
  patchLlm(patch: BrainSettings['llm']): Promise<void>;
}
const zero = { acceptedRevision: 0, lastCommittedOperationKey: null };
function json(body: unknown, status = 200): Response { return new Response(JSON.stringify(body), { status }); }
function completion(init?: RequestInit, llm?: unknown): Response {
  const body = JSON.parse(String(init?.body));
  return json({ state: 'COMPLETE', operationKey: body.operationKey,
    witness: { acceptedRevision: 1, lastCommittedOperationKey: body.operationKey }, ...(llm ? { llm } : {}) });
}
beforeEach(() => vi.useFakeTimers());
afterEach(() => { vi.useRealTimers(); vi.resetAllMocks(); });

describe('Brain LLM settings completion', () => {
  it('preserves unrelated fields and applies only the completed narrow normalized intent', async () => {
    const brain = document.createElement('jf-brain-surface') as BrainSettings;
    brain.apiBase = '';
    brain.llm = { modelPath: 'existing', maxTokens: 200, contextWindow: 4096 };
    const send = vi.mocked(authorizedFetch).mockResolvedValueOnce(json({ witness: zero }))
      .mockImplementationOnce(async (_, init) => completion(init, { contextWindow: 512, modelPath: 'unrelated-old', maxTokens: 99 }));
    await brain.patchLlm({ contextWindow: 16 });
    expect(brain.llm).toEqual({ modelPath: 'existing', maxTokens: 200, contextWindow: 512 });
    expect(JSON.parse(String(send.mock.calls[1]?.[1]?.body)).llm).toEqual({ contextWindow: 512 });
  });

  it('keeps an open change uncommitted and confirms receipt-only replay without a second GET', async () => {
    const brain = document.createElement('jf-brain-surface') as BrainSettings;
    brain.apiBase = '';
    brain.llm = { modelPath: 'old', gpuLayers: 4 };
    const send = vi.mocked(authorizedFetch).mockResolvedValueOnce(json({ witness: zero }))
      .mockImplementationOnce(async (_, init) => json({ state: 'RUNNING', operationKey: JSON.parse(String(init?.body)).operationKey }, 202))
      .mockImplementationOnce(async (_, init) => completion(init));
    const result = brain.patchLlm({ modelPath: '' });
    await vi.advanceTimersByTimeAsync(249);
    expect(brain.llm.modelPath).toBe('old');
    await vi.advanceTimersByTimeAsync(1);
    await result;
    expect(brain.llm).toEqual({ modelPath: '', gpuLayers: 4 });
    expect(send).toHaveBeenCalledTimes(3);
    expect(send.mock.calls[1]?.[1]?.body).toBe(send.mock.calls[2]?.[1]?.body);
  });

  it('shows a conflict without pretending the changed field was persisted', async () => {
    const brain = document.createElement('jf-brain-surface') as BrainSettings;
    brain.apiBase = '';
    brain.llm = { gpuLayers: 4 };
    const send = vi.mocked(authorizedFetch).mockResolvedValueOnce(json({ witness: zero }))
      .mockResolvedValueOnce(json({ errorCode: 'VERSION_CONFLICT' }, 409));
    await brain.patchLlm({ gpuLayers: 0 });
    expect(brain.llm.gpuLayers).toBe(4);
    expect(brain.runtimeError).toContain('Reload and review');
    expect(send).toHaveBeenCalledTimes(2);
  });
});
