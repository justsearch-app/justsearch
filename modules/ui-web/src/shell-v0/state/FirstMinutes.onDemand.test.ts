// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { Sv3Composer } from '../views/search-v3/Sv3Composer.js';
import * as store from './aiStateStore.js';
import { known } from './known.js';
import { setFirstUseRoots } from './firstUse.js';
import { setShowDeveloperTools } from './developerToolsState.js';
let snapshot: store.AiState;
beforeEach(() => {
  store.__resetAiStateForTest(); setFirstUseRoots(null, new Map()); setShowDeveloperTools(false);
  const original = store.getAiState();
  snapshot = { ...original, phase: 'connected', snapshotLive: true, chatDisabledBySpec: false, chatFailureReason: null,
    capabilities: { ...original.capabilities, chat: true },
    runtime: { ...original.runtime, mode: 'online', installed: known(true), gpu: { available: true, description: 'CUDA' } },
    index: { ...original.index, documentCount: known(2) }, verdict: { kind: 'operational', severity: 'ok', reasons: [] } as never };
  vi.spyOn(store, 'getAiState').mockImplementation(() => snapshot);
});
afterEach(() => { document.body.replaceChildren(); vi.restoreAllMocks(); });
const mount = async () => { const el = new Sv3Composer(); el.state = 'hero'; el.tier = 'search'; document.body.append(el); await el.updateComplete; return el; };
const modes = (el: Sv3Composer) => [...el.shadowRoot!.querySelectorAll('[data-tier]')].map(e => e.getAttribute('data-tier'));
it('S4-14 pressure-unloaded chat remains offerable through the existing on-demand availability', async () => {
  snapshot.capabilities.chat = false; snapshot.runtime.mode = 'offline';
  snapshot.chatUnloadedReason = 'inference.unloaded_gpu_pressure';
  snapshot.status = { operating: { chat: { state: 'UNLOADED' } } } as never;
  expect(modes(await mount())).toEqual(['search','ask']);
});
