// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it, vi } from 'vitest';
import fixture from '../../api/__fixtures__/status-response-live.json';
import { __resetAiStateForTest, getAiState, startAiStateStore } from '../state/aiStateStore.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import './BrainSurface.js';

interface Brain extends HTMLElement {
  apiBase: string; host_: unknown; updateComplete: Promise<boolean>;
  expanded: Record<string, boolean>;
}
interface Button extends HTMLElement {
  updateComplete: Promise<boolean>;
  availability: { kind: string; reason?: unknown };
}
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
afterEach(() => { document.body.innerHTML = ''; __resetAiStateForTest(); vi.useRealTimers(); vi.unstubAllGlobals(); });

describe('pack observations through the poller, store and Brain page', () => {
  it.each(['timeout', 'HTTP failure'])('T1 a pack %s shows retained history as checking and recovers the Import control', async failure => {
    vi.useFakeTimers(); __resetAiStateForTest();
    let packReads = 0;
    let recover = false;
    let finish!: (response: Response) => void;
    let heldSignal: AbortSignal | null | undefined;
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith('/api/status')) return Promise.resolve(json(fixture));
      if (url.endsWith('/api/inference/status')) return Promise.resolve(json({ mode: 'online', available: true }));
      if (url.endsWith('/install/status')) return Promise.resolve(json({ state: 'idle' }));
      if (url.endsWith('/runtime/status')) return Promise.resolve(json({ activation: { state: 'idle' } }));
      if (url.endsWith('/packs/status')) {
        packReads++;
        if (packReads > 1 && !recover) {
          if (packReads === 2) heldSignal = init?.signal;
          return failure === 'timeout' ? new Promise<Response>(resolve => { if (packReads === 2) finish = resolve; }) : Promise.resolve(json({}, 500));
        }
        return Promise.resolve(json({ state: packReads === 1 ? 'running' : 'idle', phase: packReads === 1 ? 'importing' : 'idle' }));
      }
      if (url.endsWith('/api/policy/effective')) return Promise.resolve(json({ packAllowlistConfigured: true }));
      if (url.endsWith('/plan-preview')) return Promise.resolve(json({ components: [], totalDownloadBytes: 0 }));
      return Promise.resolve(json({}));
    }));
    startAiStateStore('');
    const brain = document.createElement('jf-brain-surface') as Brain;
    brain.apiBase = ''; brain.host_ = createMockHostApi({ platform: { capabilities: new Set(['file-picker']) } });
    brain.expanded = { advanced: true }; document.body.append(brain);
    const advance = async (ms: number) => { await vi.advanceTimersByTimeAsync(ms); await brain.updateComplete; };
    await advance(0);
    const button = () => brain.shadowRoot!.querySelector<Button>('jf-button[label="Import pack"]')!;
    const packSection = () => button().closest('.section')!;
    expect(packSection().textContent).toMatch(/Pack status:\s*running/);
    expect(JSON.stringify(button().availability)).toContain('A pack import is already in progress.');
    await advance(1000);
    if (failure === 'timeout') {
      await advance(5000);
      expect(heldSignal?.aborted).toBe(true);
    }
    expect(packSection().textContent).toContain('Checking pack status…');
    expect(packSection().textContent).toMatch(/Pack status — last observed:\s*running/);
    expect(JSON.stringify(button().availability)).toContain('Checking pack status before importing.');
    expect(JSON.stringify(button().availability)).not.toContain('already in progress');
    expect(getAiState().packStatus?.state).toBe('running');
    // Observe through the public page first so the before-state fails on the missing wording.
    expect(getAiState().packObservationLive).toBe(false);
    recover = true;
    await advance(1000);
    expect(packReads).toBe(3);
    expect(packSection().textContent).toMatch(/Pack status:\s*idle/);
    expect(packSection().textContent).not.toMatch(/Checking pack status|last observed/);
    expect(button().availability).toEqual({ kind: 'available' });
    expect(getAiState().packObservationLive).toBe(true);
    if (failure === 'timeout') {
      finish(json({ state: 'running', phase: 'late' })); await advance(0);
      expect(packSection().textContent).toMatch(/Pack status:\s*idle/);
      expect(getAiState().packStatus?.state).toBe('idle');
    }
  });
});
