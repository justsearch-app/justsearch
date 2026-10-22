// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './HealthSurface.js';
import './BrainSurface.js';
import type { HealthSurface } from './HealthSurface.js';
import type { BrainSurface } from './BrainSurface.js';
import { setUiMode, __resetUiModeForTest } from '../state/uiModeState.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import { __feedForTest, __resetAiStateForTest, type StatusSnapshot } from '../state/aiStateStore.js';

vi.mock('../api/authorizedFetch.js', () => ({ authorizedFetch: (...args: Parameters<typeof fetch>) => fetch(...args) }));
const key = '0194f72c-0000-7000-8000-000000000001';
const message = 'The rebuild or migration could not safely start or resume the new index; check index health and current settings before starting a new rebuild.';
let terminal = false;
let reads = 0;

beforeEach(() => {
  __resetUiModeForTest(); setUiMode('advanced');
  vi.useFakeTimers(); terminal = false; reads = 0;
  vi.stubGlobal('fetch', vi.fn(async (path: string) => {
    if (path.endsWith(`/api/operation-history/${key}`)) {
      reads++;
      return new Response(JSON.stringify(terminal
        ? { state: 'failed', historySince: 0, message, reason: 'BULK_GENERATION_REFUSED', result: { code: 'BULK_GENERATION_REFUSED' } }
        : { state: 'running', historySince: 0 }));
    }
    return new Response('{}', { status: 503 });
  }));
  __feedForTest({ status: { worker: { core: { indexHealthy: true, indexState: 'IDLE' } },
    embedding: { compatState: 'BLOCKED_LEGACY' }, schema: { compatState: 'COMPATIBLE' },
  } as unknown as StatusSnapshot, inference: { mode: 'offline', available: false } });
});
afterEach(() => {
  document.body.replaceChildren(); __resetAiStateForTest();
  __resetUiModeForTest();
  vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers();
});
async function pump(el: HealthSurface | BrainSurface) {
  for (let i = 0; i < 30; i++) { await Promise.resolve(); await el.updateComplete; }
}
async function showsLaterFailure(el: HealthSurface | BrainSurface) {
  await pump(el);
  expect(reads, 'acceptance starts reading the recorded operation outcome').toBe(1);
  expect(el.shadowRoot!.querySelector('jf-operation-outcome-detail')?.shadowRoot?.textContent).not.toContain(message);
  terminal = true;
  await vi.advanceTimersByTimeAsync(4_000); await pump(el);
  const detail = el.shadowRoot!.querySelector('jf-operation-outcome-detail');
  expect(detail?.shadowRoot?.textContent).toContain(message);
  expect(detail?.shadowRoot?.textContent).toContain('Code: BULK_GENERATION_REFUSED');
  const finishedReads = reads;
  await vi.advanceTimersByTimeAsync(8_000);
  expect(reads, 'terminal outcomes stop polling').toBe(finishedReads);
}

it.each(['health', 'brain'] as const)('%s rebuild button displays failure after acceptance', async (surface) => {
  const el = document.createElement(`jf-${surface}-surface`) as HealthSurface | BrainSurface;
  if (surface === 'brain') vi.spyOn(el as unknown as { refreshAll(): Promise<void> }, 'refreshAll').mockResolvedValue();
  document.body.append(el); await pump(el);
  const button = el.shadowRoot!.querySelector('jf-operation[operation-id="core.rebuild-index"]');
  expect(button).not.toBeNull();
  button!.dispatchEvent(new CustomEvent('op-success', { bubbles: true, composed: true,
    detail: { structuredData: { operationKey: key } } }));
  await showsLaterFailure(el);
});

it('a model activation displays its later migration failure', async () => {
  const el = document.createElement('jf-brain-surface') as BrainSurface;
  const invoke = vi.fn().mockResolvedValue({ success: true, structuredData: { operationKey: key } });
  el.host_ = { platform: { capabilities: new Set() }, data: { invokeOperation: invoke } } as unknown as PluginHostApi;
  vi.spyOn(el as unknown as { refreshAll(): Promise<void> }, 'refreshAll').mockResolvedValue();
  document.body.append(el); await pump(el);
  await (el as unknown as { activateInstalledModels(): Promise<void> }).activateInstalledModels();
  expect(invoke).toHaveBeenCalledWith('core.activate-installed-models',
    { source: 'installer_model_activation' }, expect.any(Object));
  await showsLaterFailure(el);
});

it('Health recommended rebuild action observes the same later outcome', async () => {
  const el = document.createElement('jf-health-surface') as HealthSurface;
  document.body.append(el); await pump(el);
  el.recommendedActions = new Map([['index.embedding_legacy|index', { target: 'core.bulk-reindex', args: {} }]]);
  await pump(el);
  const button = el.shadowRoot!.querySelector('jf-operation[operation-id="core.bulk-reindex"]');
  expect(button).not.toBeNull();
  button!.dispatchEvent(new CustomEvent('op-success', { bubbles: true, composed: true,
    detail: { structuredData: { operationKey: key } } }));
  await showsLaterFailure(el);
});
