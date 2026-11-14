// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { Sv3Composer } from './Sv3Composer.js';
import * as store from '../../state/aiStateStore.js';
import { known, UNKNOWN } from '../../state/known.js';
import { setFirstUseRoots } from '../../state/firstUse.js';
import { setShowDeveloperTools } from '../../state/developerToolsState.js';
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
it('S4-14 live installed CUDA availability offers Search/Ask without losing text when readiness changes', async () => {
  const el = await mount(); el.draft = 'exact\ndraft'; await el.updateComplete;
  expect(modes(el)).toEqual(['search', 'ask']);
  snapshot = { ...snapshot, snapshotLive: false }; el.requestUpdate(); await el.updateComplete;
  expect(modes(el)).toEqual([]); expect(el.draft).toBe('exact\ndraft');
  snapshot = { ...snapshot, snapshotLive: true }; el.requestUpdate(); await el.updateComplete;
  expect(modes(el)).toEqual(['search','ask']); expect(el.draft).toBe('exact\ndraft');
});
for (const state of ['uninstalled','unknown-install','no-cuda','unknown-gpu','stale','off','failed','loading'] as const) it(`S4-14 ${state} offers no new Ask`, async () => {
  if (state === 'uninstalled') snapshot.runtime.installed = known(false);
  if (state === 'unknown-install') snapshot.runtime.installed = UNKNOWN;
  if (state === 'no-cuda') snapshot.runtime.gpu = { available: false, description: 'CPU' };
  if (state === 'unknown-gpu') snapshot.runtime.gpu = null;
  if (state === 'stale') snapshot.snapshotLive = false;
  if (state === 'off') snapshot.chatDisabledBySpec = true;
  if (state === 'failed') snapshot.chatFailureReason = 'inference.model_load_failed';
  if (state === 'loading') snapshot.runtime.mode = 'starting';
  expect(modes(await mount())).toEqual([]);
});
it('S4-14 developer tools reveal Structured/Delegate only with answers available', async () => {
  setShowDeveloperTools(true); const el = await mount();
  (el.shadowRoot!.querySelector('[data-testid="sv3-composer-tier"]') as HTMLElement).click(); await el.updateComplete;
  expect(modes(el)).toEqual(['search','ask','structured','delegate']);
});
