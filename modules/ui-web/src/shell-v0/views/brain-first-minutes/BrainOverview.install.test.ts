// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// Slice 7a — a first install that runs is not an installed AI with a missing file (S7a-7, AM4); Ask
// stays as it was while Brain counts processor answers as working (S7a-11).
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
  getAiState: () => store.current,
}));
import '../BrainSurface.js';
import { Sv3Composer } from '../search-v3/Sv3Composer.js';
import { createMockHostApi } from '../../plugin-api/testHostApi.js';
import { setShowDeveloperTools } from '../../state/developerToolsState.js';
import { setFirstUseRoots } from '../../state/firstUse.js';
import { known } from '../../state/known.js';
import { __seedForTest } from '../../../api/registry/SurfaceCatalogClient.js';

interface Brain extends HTMLElement { apiBase: string; host_: unknown; updateComplete: Promise<boolean> }
interface Control extends HTMLElement { updateComplete: Promise<boolean>; label?: string }
const GB = 1024 ** 3;
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
let preview: InstallPlanPreview;
let operations: Array<{ id: string; args: unknown }>;
let posts: string[];
let confirms: string[];
const parts = ['embedding', 'reranker', 'chat'];
function plan(state: string): InstallPlanPreview {
  return { totalDownloadBytes: state === 'installed' ? 0 : 3 * GB, components: parts.map(id => ({ id, label: id, state,
    declinable: id !== 'embedding', necessity: id === 'embedding' ? 'required' : 'improves-results', totalBytes: GB, downloadBytes: state === 'installed' ? 0 : GB })) };
}
const live = { phase: 'connected', installObservationLive: true, runtimeObservationLive: true, snapshotLive: true,
  index: { ownDocumentCount: { known: true, value: 8 } }, capabilities: { chat: false }, runtimeStatus: { onnxFeatures: [] } };
function firstInstallRunning(): AiState {
  return { ...live, aiEngine: { kind: 'installing', stability: { kind: 'provisional', cause: 'installing' }, installFailure: null },
    runtime: { mode: 'offline', installed: { known: true, value: false } },
    installStatus: { state: 'running', phase: 'download', installedFully: false, repairNeeded: true, updatedAtEpochMs: Date.now(),
      downloadedBytes: GB, totalBytes: 3 * GB, startedAtEpochMs: 1,
      packages: [{ packageId: 'embedding', state: 'installed', bytesDownloaded: GB, bytesTotal: GB },
        { packageId: 'reranker', state: 'downloading', bytesDownloaded: 0, bytesTotal: GB },
        { packageId: 'chat', state: 'pending', bytesDownloaded: 0, bytesTotal: GB }] } } as unknown as AiState;
}
async function settle(el: Brain) {
  for (let i = 0; i < 5; ++i) { await new Promise(resolve => setTimeout(resolve, 0)); await el.updateComplete; }
}
async function mount() {
  const el = document.createElement('jf-brain-surface') as Brain;
  el.apiBase = '';
  el.host_ = createMockHostApi({
    data: { invokeOperation: async (id: string, args: unknown) => { operations.push({ id, args }); return { success: true, structuredData: { state: 'running' } }; } },
    ui: { showConfirmDialog: async (message: string) => { confirms.push(message); return true; } },
  });
  document.body.append(el); await settle(el); return el;
}
const words = (node: Element | ShadowRoot | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
const buttons = (el: Brain) => [...el.shadowRoot!.querySelectorAll<Control>('jf-button')];

beforeEach(() => {
  setShowDeveloperTools(false);
  operations = []; posts = []; confirms = []; preview = plan('to-download');
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (init?.method === 'POST') posts.push(path);
    if (path.endsWith('/api/settings/v2')) return json({ ui: {}, llm: {}, witness: { acceptedRevision: 0, lastCommittedOperationKey: null } });
    if (path.endsWith('/plan-preview')) return json(preview);
    if (path.endsWith('/manifest')) return json({ schemaVersion: 2, packages: parts.map(id => ({ id, label: id, license: 'MIT', termsUrl: 'https://example.invalid', variants: [], supportingFiles: [] })) });
    if (path.endsWith('/api/ai/models/status')) return json({ packages: parts.map(id => ({ id, complete: preview.components!.find(c => c.id === id)!.state === 'installed' })) });
    if (path.endsWith('/api/ai/install/downloaded-files')) return json({ parts: [] });
    if (path.endsWith('/install/pause') || path.endsWith('/install/resume')) return json({ state: 'running' });
    return json({});
  }));
});
afterEach(() => { document.body.innerHTML = ''; store.listeners.clear(); vi.unstubAllGlobals(); });

describe('S7a-7 a first install that runs', () => {
  it('shows no Repair and no installed wording, and is not projected as installed', async () => {
    store.current = firstInstallRunning();
    const el = await mount();
    const page = words(el.shadowRoot!.querySelector('[data-testid="brain-simple-panel"]'));
    expect(page).toMatch(/^Installing/);
    expect(page).not.toMatch(/repair|already installed|\binstalled\b/i);
    expect(buttons(el).map(b => b.label)).not.toContain('Repair');
    expect(el.shadowRoot!.querySelector('[data-testid="install-repair-needed"]')).toBeNull();
    expect(el.shadowRoot!.querySelector('.path-step[data-feature="reranker"]'), 'Best match first step').not.toBeNull();
    expect(el.shadowRoot!.querySelectorAll('.path-step [role="switch"]:not([aria-disabled="true"])')).toHaveLength(0);
    expect(buttons(el).map(b => b.label)).toEqual(expect.arrayContaining(['Pause download', 'Cancel']));
  });
});

// The page's install, pause, cancel and genuine Repair flows are preservation checks
// (BrainOverview.preservation.test.ts); here only what slice 7a changes.
describe('S7a-11 Ask in Search', () => {
  it('Ask in Search keeps its own readiness while Brain counts processor answers as working', async () => {
    setFirstUseRoots(null, new Map());
    const entry = (id: string, mountTag: string, extra = {}) => ({ id, mountTag, presentation: { labelKey: id, descriptionKey: '', iconHint: null, category: null },
      audience: 'USER', placement: 'RAIL', altitude: 'PRODUCT', consumes: { resources: [], operations: [], prompts: [], diagnosticChannels: [] },
      provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' }, ...extra });
    __seedForTest({ schemaVersion: '1.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [
      entry('core.brain-surface', 'jf-brain-surface', { members: ['core.memory-surface'] }), entry('core.memory-surface', 'jf-memory-surface')] } as never);
    store.current = { ...live, aiEngine: { kind: 'online', stability: { kind: 'settled' }, installFailure: null },
      chatDisabledBySpec: false, chatFailureReason: null, verdict: { kind: 'operational', severity: 'ok', reasons: [] },
      capabilities: { chat: true, rag: true, extract: true, embedding: true },
      runtime: { mode: 'online', installed: known(true), gpu: { available: false, description: 'CPU' } },
      installStatus: { state: 'completed', installedFully: true }, index: { documentCount: known(2), ownDocumentCount: known(2) },
      runtimeStatus: { onnxFeatures: [{ id: 'embed', status: 'active', modelActive: true }] } } as unknown as AiState;
    preview = plan('installed');
    const brain = await mount();
    const tabs = brain.shadowRoot!.querySelector('jf-surface-tabs')?.shadowRoot?.querySelectorAll('[role="tab"]') ?? [];
    expect([...tabs].map(t => t.textContent?.trim())).toHaveLength(2);
    expect(tabs[0]?.textContent?.trim()).toBe('Overview');
    const composer = new Sv3Composer(); composer.state = 'hero'; composer.tier = 'search';
    document.body.append(composer); await composer.updateComplete;
    expect([...composer.shadowRoot!.querySelectorAll('[data-tier]')].map(e => e.getAttribute('data-tier'))).toEqual([]);
  });
});
