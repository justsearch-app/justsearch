// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * Follow-up C1 FC4: Diagnostics' Connection card, under the core declared presentation, names what its
 * live readout reports. Keyword search works and AI chat is off: the card must not say retrieval is offline.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './HealthSurface.js';
// The declared body's root layout; the app registers it at start-up.
import '../renderers/layouts/VerticalLayout.js';
import type { HealthSurface } from './HealthSurface.js';
import { __resetAiStateForTest, getAiState, startAiStateStore, type StatusSnapshot } from '../state/aiStateStore.js';
import { __resetPresentationForTest, applyPresentationBodies } from '../state/presentationRuntime.js';
import { CORE_DECLARED } from '../themes/builtinPresentations.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';

const json = (value: unknown, code = 200) => new Response(JSON.stringify(value), { status: code, headers: { 'Content-Type': 'application/json' } });
const status = (over: Record<string, unknown> = {}): StatusSnapshot => ({
  status: 'ok',
  worker: { core: { indexState: 'IDLE', indexHealthy: true, indexedDocuments: 5, searchableDocuments: 5, pendingJobs: 0 } },
  readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] }, aiFeatures: { state: 'READY', reasonCodes: [] } } },
  gpu: { available: true, cudaFunctional: true }, memoryUsedBytes: 5, memoryMaxBytes: 10, ...over,
} as unknown as StatusSnapshot);

let el: HealthSurface | undefined;
async function mount(): Promise<HealthSurface> {
  el = document.createElement('jf-health-surface') as HealthSurface;
  el.host_ = createMockHostApi({ data: { fetch: async path => path === '/api/condition-recovery-index'
    ? json({ entries: [] }) : json({ jobs: [] }, path.includes('/stream') ? 404 : 200) } });
  document.body.append(el);
  await el.updateComplete;
  return el;
}
/** The live readout inside the declared Connection card: its visible words and its accessible name. */
async function readout(): Promise<{ text: string; label: string }> {
  for (let i = 0; i < 10; i++) { await Promise.resolve(); await el!.updateComplete; }
  type Lit = HTMLElement & { updateComplete: Promise<unknown>; declaration?: { heading?: string } };
  const declared = [...el!.shadowRoot!.querySelectorAll<Lit>('jf-declared-surface')].find(node => node.declaration?.heading === 'Connection');
  expect(declared, 'the declared Connection card renders').toBeDefined();
  await declared!.updateComplete;
  const live = declared!.shadowRoot!.querySelector<Lit>('jf-liveness-readout');
  expect(live, 'its live readout renders').not.toBeNull();
  await live!.updateComplete;
  return { text: live!.shadowRoot!.textContent?.replace(/\s+/g, ' ').trim() ?? '',
    label: live!.shadowRoot!.querySelector('.dot')?.getAttribute('aria-label') ?? '' };
}

let aiMode = 'offline';
/** The app's own reads: a current status, installed AI models, and the chat engine in `aiMode`. */
async function engine(kind: string): Promise<void> {
  aiMode = kind;
  startAiStateStore('');
  await vi.waitFor(() => expect(getAiState().aiEngine.kind).toBe(kind));
}

beforeEach(() => {
  __resetAiStateForTest(); __resetPresentationForTest();
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const path = new URL(String(input), 'http://localhost').pathname;
    if (path === '/api/status') return json(status());
    if (path.startsWith('/api/inference')) return json({ mode: aiMode, starting: false, available: aiMode === 'online' });
    if (path === '/api/ai/install/status') return json({ state: 'idle', installedFully: true });
    if (path === '/api/ai/packs/status') return json({ state: 'idle', phase: '' });
    return json({});
  }));
  expect(applyPresentationBodies(CORE_DECLARED).ok).toBe(true);
});
afterEach(() => {
  el?.remove(); el = undefined;
  __resetAiStateForTest(); __resetPresentationForTest(); vi.unstubAllGlobals();
});

describe('FC4 the Connection card names what it reports', () => {
  it('keyword search works and AI chat is off: no "Retrieval: Offline", the readout names the AI', async () => {
    await engine('offline');
    await mount();
    const { text, label } = await readout();
    expect(text, 'visible words').not.toMatch(/Retrieval/i);
    expect(label, 'accessible name').not.toMatch(/Retrieval/i);
    expect(text, 'visible words').toMatch(/\bAI\b/);
    expect(label, 'accessible name').toMatch(/\bAI\b.*Offline/);
  });

  it('with the AI online the readout still names the AI as its subject', async () => {
    await engine('online');
    await mount();
    const { label } = await readout();
    expect(label).toMatch(/^AI models: Online/);
  });
});
