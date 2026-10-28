// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { expect, it, vi } from 'vitest';
import type { AiState } from '../state/aiStateStore.js';
const observed = vi.hoisted(() => ({ listener: null as ((state: AiState) => void) | null }));
vi.mock('../state/aiStateStore.js', async importOriginal => ({
  ...await importOriginal<typeof import('../state/aiStateStore.js')>(),
  subscribeAiState: (listener: (state: AiState) => void) => {
    observed.listener = listener;
    return () => { observed.listener = null; };
  },
}));
import './BrainSurface.js';

it('refreshes the standing plan when installation or activation finishes', async () => {
  const el = document.createElement('jf-brain-surface') as HTMLElement & {
    connectedCallback(): void;
    disconnectedCallback(): void;
    refreshAll(): Promise<void>;
    startDiagnosticsPolling(): void;
    refreshInstallPlanPreview(): Promise<void>;
  };
  const refreshAll = vi.spyOn(el, 'refreshAll').mockResolvedValue();
  const diagnostics = vi.spyOn(el, 'startDiagnosticsPolling').mockImplementation(() => {});
  const preview = vi.spyOn(el, 'refreshInstallPlanPreview').mockResolvedValue();
  try {
    el.connectedCallback();
    const emit = (state: string, phase: string) => observed.listener?.({
      installStatus: { state, phase },
    } as AiState);
    emit('running', 'download');
    expect(preview).not.toHaveBeenCalled();
    emit('completed', 'activation_required');
    expect(preview).toHaveBeenCalledTimes(1);
    emit('completed', 'done');
    expect(preview).toHaveBeenCalledTimes(2);
    emit('completed', 'done');
    expect(preview).toHaveBeenCalledTimes(2);
  } finally {
    el.disconnectedCallback();
    preview.mockRestore(); diagnostics.mockRestore(); refreshAll.mockRestore();
  }
});
