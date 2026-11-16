// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// Slice 7a (S7a-8; D5, AM5, AM6) — what the pictures do not show sits behind Settings › Advanced › Show
// developer tools, live, including Brain's own Advanced legacy panels (X6).
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AiState } from '../../state/aiStateStore.js';
import type { InstallPlanPreview } from '../../utils/aiInstallPoll.js';
const store = vi.hoisted(() => ({ listeners: new Set<(state: AiState) => void>(), current: {} as AiState }));
vi.mock('../../state/aiStateStore.js', async original => ({
  ...await original<typeof import('../../state/aiStateStore.js')>(),
  subscribeAiState: (listener: (state: AiState) => void) => {
    store.listeners.add(listener); listener(store.current);
    return () => store.listeners.delete(listener);
  },
}));
import '../BrainSurface.js';
import { createMockHostApi } from '../../plugin-api/testHostApi.js';
import { setShowDeveloperTools } from '../../state/developerToolsState.js';

interface Brain extends HTMLElement { apiBase: string; host_: unknown; updateComplete: Promise<boolean> }
interface Control extends HTMLElement { updateComplete: Promise<boolean>; label?: string }
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
let preview: InstallPlanPreview;
let policy: Record<string, unknown>;
let downloaded: Record<string, unknown>;
const parts = ['embedding', 'reranker', 'chat'];
const plan = (state: string): InstallPlanPreview => ({ totalDownloadBytes: state === 'installed' ? 0 : 3072, components: parts.map(id => ({
  id, label: id, state, declinable: id !== 'embedding', totalBytes: 1024, downloadBytes: state === 'installed' ? 0 : 1024 })) });
const live = { phase: 'connected', installObservationLive: true, runtimeObservationLive: true, snapshotLive: true,
  index: { ownDocumentCount: { known: true, value: 8 } } };
function ready(): AiState {
  return { ...live, aiEngine: { kind: 'online', stability: { kind: 'settled' }, installFailure: null },
    runtime: { mode: 'online', installed: { known: true, value: true }, gpu: { available: true, description: 'CUDA' } },
    capabilities: { chat: true, rag: true, extract: true, embedding: true }, installStatus: { state: 'completed', installedFully: true },
    runtimeStatus: { active: { mmprojActive: false }, onnxFeatures: [{ id: 'embed', status: 'active', modelActive: true }, { id: 'reranker', status: 'active', modelActive: true }] },
    status: { inference: { chatEnabledSpec: true } } } as unknown as AiState;
}
function notInstalled(): AiState {
  return { ...live, aiEngine: { kind: 'not_installed', stability: { kind: 'settled' }, installFailure: null },
    runtime: { mode: 'offline', installed: { known: true, value: false } }, capabilities: { chat: false },
    installStatus: { state: 'idle', installedFully: false }, runtimeStatus: { onnxFeatures: [] } } as unknown as AiState;
}
async function settle(el: Brain) {
  for (let i = 0; i < 5; ++i) { await new Promise(resolve => setTimeout(resolve, 0)); await el.updateComplete; }
}
async function mount() {
  const el = document.createElement('jf-brain-surface') as Brain;
  el.apiBase = '';
  el.host_ = createMockHostApi({ data: { invokeOperation: async () => ({ success: true, structuredData: null }) } });
  document.body.append(el); await settle(el); return el;
}
const words = (node: Element | ShadowRoot | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
const labels = (el: Brain) => [...el.shadowRoot!.querySelectorAll<Control>('jf-button')].map(b => b.label ?? '');
function advanced(el: Brain) {
  return [...el.shadowRoot!.querySelectorAll<HTMLButtonElement>('.brain-links button')].find(b => words(b) === 'Advanced settings') ?? null;
}

beforeEach(() => {
  setShowDeveloperTools(false);
  preview = plan('installed'); policy = {}; downloaded = { parts: [] };
  store.current = ready();
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input);
    if (path.endsWith('/api/settings/v2')) return json({ ui: { chatEnabled: true }, llm: {}, rerankerEnabled: true, pictureReadingEnabled: false,
      witness: { acceptedRevision: 0, lastCommittedOperationKey: null } });
    if (path.endsWith('/plan-preview')) return json(preview);
    if (path.endsWith('/manifest')) return json({ schemaVersion: 2, packages: [] });
    if (path.endsWith('/api/ai/models/status')) return json({ packages: parts.map(id => ({ id, complete: preview.components!.find(c => c.id === id)!.state === 'installed' })) });
    if (path.endsWith('/api/ai/install/downloaded-files')) return json(downloaded);
    if (path.endsWith('/api/policy/effective')) return json(policy);
    return json({});
  }));
});
afterEach(() => { document.body.innerHTML = ''; store.listeners.clear(); vi.unstubAllGlobals(); setShowDeveloperTools(false); });

describe('S7a-8 unpictured controls are developer tools, live', () => {
  it('Start/Stop answers, Refresh status and the You get / It costs panels appear only with developer tools', async () => {
    const el = await mount();
    expect(labels(el)).not.toEqual(expect.arrayContaining(['Stop answers']));
    expect(labels(el)).not.toContain('Start answers');
    expect(labels(el)).not.toContain('Refresh status');
    expect(el.shadowRoot!.querySelectorAll('.path-step button.step-info, button.feature-phrase')).toHaveLength(0);
    setShowDeveloperTools(true); await settle(el);
    expect(labels(el)).toContain('Stop answers');
    expect(labels(el)).toContain('Refresh status');
    const info = el.shadowRoot!.querySelector<HTMLButtonElement>('.path-step[data-feature="reranker"] button.step-info');
    expect(info, 'feature disclosure').not.toBeNull();
    info!.click(); await settle(el);
    expect(words(el.shadowRoot!.querySelector('.feature-panel'))).toMatch(/You get.*It costs/);
    setShowDeveloperTools(false); await settle(el);
    expect(labels(el)).not.toContain('Stop answers');
    expect(el.shadowRoot!.querySelector('.feature-panel')).toBeNull();
  });

  it('keeps only Advanced settings and Engine history in the links row', async () => {
    const el = await mount();
    const links = el.shadowRoot!.querySelector('.brain-links');
    expect(links, 'links row').not.toBeNull();
    expect([...links!.querySelectorAll('button, a, jf-button')].map(n => words(n) || (n as Control).label)).toEqual(['Advanced settings', 'Engine history in Health']);
  });
});

// X6 (AM5) — Brain's Advanced settings is not the place for unpictured panels: the component list,
// the tier breakdown, the package history and the observed search features need developer tools, live.
describe('S7a-8 Advanced settings keeps its legacy panels behind developer tools', () => {
  it('component list, tiers, package history and observed features appear only with developer tools', async () => {
    preview = { ...plan('installed'), tiers: [{ tier: 'core', label: 'Core search tier', totalBytes: 2048, downloadBytes: 0, includedByIntent: true }] } as InstallPlanPreview;
    store.current = { ...ready(), installStatus: { state: 'completed', installedFully: true,
      packages: [{ packageId: 'reranker', label: 'Reranker package', state: 'installed', bytesTotal: 1024 }] } } as unknown as AiState;
    const el = await mount();
    const open = advanced(el);
    expect(open, 'Advanced settings').not.toBeNull();
    open!.click(); await settle(el);
    const legacy = () => ({
      components: el.shadowRoot!.querySelector('[data-testid="install-component-list"]') !== null,
      tiers: words(el.shadowRoot!).includes('Core search tier'),
      packages: el.shadowRoot!.querySelector('[data-testid="package-row-reranker"]') !== null,
      observed: el.shadowRoot!.querySelector('[data-testid="brain-observed-features"]') !== null,
    });
    expect(legacy()).toEqual({ components: false, tiers: false, packages: false, observed: false });
    expect(el.shadowRoot!.querySelector('[aria-label="Advanced settings"] [aria-label="Downloaded files"]'), 'Downloaded files stays').not.toBeNull();
    setShowDeveloperTools(true); await settle(el);
    expect(legacy()).toEqual({ components: true, tiers: true, packages: true, observed: true });
    setShowDeveloperTools(false); await settle(el);
    expect(legacy()).toEqual({ components: false, tiers: false, packages: false, observed: false });
  });
});

// The recovery routes that work without developer tools (AM6) are preservation checks
// (BrainOverview.preservation.test.ts); here only what slice 7a changes.
describe('S7a-8 fm-10 without a recovery need', () => {
  it('a not-installed page shows no Advanced settings link otherwise (fm-10)', async () => {
    store.current = notInstalled(); preview = plan('to-download');
    const el = await mount();
    expect(advanced(el)).toBeNull();
    expect(el.shadowRoot!.querySelector('.path-step[data-feature="meaning"]'), 'Understand meaning step').not.toBeNull();
  });
});

// PH4 (fm-10) — before AI is installed AI Brain shows no graphics card memory (System › Health keeps it);
// once AI is installed the reading shows as before.
describe('PH4 the graphics card waits for an installed AI', () => {
  const gpu = { gpu: { totalVramBytes: 12 * 1024 ** 3, usedVramBytes: 3 * 1024 ** 3, available: true, cudaFunctional: true },
    operating: { gpu: { stale: false, adapter: 'RTX 4070' } } };
  const card = (el: Brain) => el.shadowRoot!.querySelector('.graphics-card');
  it('a not-installed page with a current reading shows no graphics card; installed, it does', async () => {
    store.current = { ...notInstalled(), status: gpu } as unknown as AiState; preview = plan('to-download');
    const el = await mount();
    expect(el.shadowRoot!.querySelector('.path-step[data-feature="meaning"]'), 'the page rendered its steps').not.toBeNull();
    expect(card(el)).toBeNull();
    el.remove();
    store.current = { ...ready(), status: { ...(ready().status as object), ...gpu } } as unknown as AiState; preview = plan('installed');
    const installed = await mount();
    expect(words(card(installed))).toContain('3.00 GB of 12.00 GB in use · RTX 4070');
  });
});
