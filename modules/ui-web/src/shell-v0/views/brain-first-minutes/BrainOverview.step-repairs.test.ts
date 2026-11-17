// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// Slice 7a repairs R2 and R4 (2026-11-15, real-app test of 2f670abba7): the answer size measures the
// model answers run with, and Advanced names the answer pause in read words.
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
const GB = 1024 ** 3, MB = 1024 ** 2;
const SIZES = { embedding: Math.round(1.2 * GB), reranker: 341 * MB, chat: Math.round(5.7 * GB), splade: 500 * MB, ner: 300 * MB, 'citation-scorer': 200 * MB } as const;
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
const NO_CARD_REASON = 'Chat model requires a CUDA-capable GPU (none detected on this system). CPU chat is not supported in this build.';
const LINUX_REASON = 'Chat model is unavailable: Linux GPU packs have not been published yet.';
let preview: InstallPlanPreview;
let settings: Record<string, unknown>;
let presence: { packages: Array<{ id: string; complete: boolean }> };
let declines: Array<{ id: string; method: string }>;
let mutations: string[];
let operations: string[];
let platform: string;
function component(id: keyof typeof SIZES, state: string, extra: Record<string, unknown> = {}) {
  return { id, label: id, state, declined: state === 'declined', declinable: id !== 'embedding',
    necessity: id === 'embedding' ? 'required' : id === 'chat' ? 'adds-feature' : 'improves-results',
    totalBytes: SIZES[id], downloadBytes: state === 'to-download' ? SIZES[id] : 0, ...extra };
}
function total(p: InstallPlanPreview) {
  return (p.components ?? []).filter(c => c.state === 'to-download' && !c.declined).reduce((sum, c) => sum + (c.downloadBytes ?? 0), 0);
}
function plan(installed: boolean, chat: 'available' | 'no-card' | 'linux') {
  const state = installed ? 'installed' : 'to-download';
  const components = (['embedding', 'reranker', 'splade', 'ner', 'citation-scorer'] as const).map(id => component(id, state));
  components.push(chat === 'available' ? component('chat', state)
    : component('chat', 'unavailable', { downloadBytes: 0, unavailableReason: chat === 'linux' ? LINUX_REASON : NO_CARD_REASON }));
  const p: InstallPlanPreview = { components };
  p.totalDownloadBytes = total(p);
  return p;
}
const live = { phase: 'connected', installObservationLive: true, runtimeObservationLive: true, snapshotLive: true, indexingStatusLive: true,
  episodeMaxPendingJobs: 0, enrichSettleSamples: [], connection: { reachable: true, lastSuccessMs: 1, lastContactMs: 1, consecutiveFailures: 0 },
  activity: { state: 'idle', shapeId: null, startedAtMs: null, canCancel: false, cancel: null }, stability: { kind: 'settled' },
  verdict: { kind: 'operational', severity: 'ok', reasons: [] } };
const noCardStatus = { gpu: { available: false, cudaFunctional: false, deviceCount: 0 }, inference: { chatEnabledSpec: false },
  embedding: { compatState: 'COMPATIBLE' },
  operating: { gpu: { stale: false, adapter: null, profile: 'NO_GPU', source: 'NONE' } },
  worker: { core: { indexState: 'IDLE', pendingJobs: 0, indexedDocuments: 8, searchableDocuments: 8 } } };
function installedNoCard(embed: Record<string, unknown> = { status: 'active', modelActive: true }): AiState {
  return { ...live, aiEngine: { kind: 'offline', stability: { kind: 'settled' }, installFailure: null },
    runtime: { mode: 'offline', installed: { known: true, value: true }, gpu: { available: false, description: 'CPU' } },
    capabilities: { chat: false, rag: false, extract: false, embedding: true },
    installStatus: { state: 'idle', installedFully: true },
    runtimeStatus: { onnxFeatures: [{ id: 'embed', ...embed }, { id: 'reranker', status: 'inactive', modelActive: false }] },
    index: { ownDocumentCount: { known: true, value: 8 } }, status: noCardStatus } as unknown as AiState;
}
async function settle(el: Brain) {
  for (let i = 0; i < 4; ++i) { await new Promise(resolve => setTimeout(resolve, 0)); await el.updateComplete; }
}
async function mount() {
  const el = document.createElement('jf-brain-surface') as Brain;
  el.apiBase = '';
  el.host_ = createMockHostApi({ data: { invokeOperation: async (id: string) => {
    operations.push(id); return { success: true, structuredData: null };
  } } });
  document.body.append(el); await settle(el); return el;
}
const words = (node: Element | ShadowRoot | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
// Steps are found as the page has always drawn them: the files and read steps by their class, the
// feature steps by data-feature.
const step = (el: Brain, id: string) => el.shadowRoot!.querySelector<HTMLElement>(id === 'files' ? '.files-step' : id === 'read' ? '.read-step' : `.path-step[data-feature="${id}"]`);

beforeEach(() => {
  setShowDeveloperTools(false);
  platform = 'Win32';
  Object.defineProperty(navigator, 'platform', { configurable: true, get: () => platform });
  preview = plan(false, 'no-card');
  settings = { ui: {}, llm: {}, rerankerEnabled: null, witness: { acceptedRevision: 0, lastCommittedOperationKey: null } };
  presence = { packages: [] };
  declines = []; mutations = []; operations = [];
  store.current = installedNoCard();
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (init?.method && init.method !== 'GET' && !path.includes('/decline')) mutations.push(`${init.method} ${path}`);
    if (path.endsWith('/api/settings/v2')) return json(settings);
    if (path.endsWith('/plan-preview')) return json(preview);
    if (path.endsWith('/manifest')) return json({ schemaVersion: 2, packages: (preview.components ?? []).map(c => ({
      id: c.id, label: c.label, license: 'Apache-2.0', termsUrl: 'https://example.invalid/terms', variants: [], supportingFiles: [] })) });
    if (path.endsWith('/api/ai/models/status')) return json(presence);
    if (path.endsWith('/api/ai/install/downloaded-files')) return json({ parts: [] });
    if (path.includes('/decline')) {
      const id = decodeURIComponent(path.split('/').at(-2)!);
      declines.push({ id, method: init?.method ?? 'GET' });
      preview = { ...preview, components: preview.components!.map(c => c.id === id
        ? { ...c, declined: init?.method === 'POST', state: init?.method === 'POST' ? 'declined' : 'to-download' } : c) };
      preview.totalDownloadBytes = total(preview);
      return json({});
    }
    return json({});
  }));
});
afterEach(() => {
  document.body.innerHTML = ''; store.listeners.clear(); vi.unstubAllGlobals();
  delete (navigator as unknown as Record<string, unknown>).platform;
});

describe('repairs R2 and R4: sizes measure the running model; Advanced speaks read words', () => {
  // R2/X5 — answers run on the compact model. models/status drops dev-only packages, so the standard
  // answer package reads incomplete; the manifest (the model registry) names chat-compact with one
  // model file and its projector: 2740937888 + 672423616 bytes. The standard package is 6.3 GB.
  it('shows the compact answer model’s own package size, never the standard one', async () => {
    preview = plan(true, 'available'); settings = { ...settings, ui: { chatEnabled: true } };
    preview = { ...preview, components: preview.components!.map(c => c.id === 'chat' ? { ...c, state: 'to-download', totalBytes: 6807977504, downloadBytes: 6807977504 } : c) };
    presence = { packages: [...['embedding', 'reranker'].map(id => ({ id, complete: true })),
      { id: 'chat', complete: false, presentVariantFiles: [], missingSupportingFiles: ['mmproj-F16.gguf'] } as { id: string; complete: boolean }] };
    const registry = { schemaVersion: 2, packages: [
      { id: 'chat', label: 'Chat model', targetDir: '', license: 'Apache-2.0', termsUrl: 'https://example.invalid/terms',
        variants: [{ filename: 'Qwen_Qwen3.5-9B-Q4_K_M.gguf', precision: 'GGUF', targetEP: 'LLAMA_SERVER', sha256: 'A', sizeBytes: 5889811552, downloadUrl: 'https://example.invalid/a' }],
        supportingFiles: [{ filename: 'mmproj-F16.gguf', sha256: 'B', sizeBytes: 918165952, downloadUrl: 'https://example.invalid/b', extract: false, required: true }] },
      { id: 'chat-compact', label: 'Chat model (compact)', targetDir: 'compact', license: 'Apache-2.0', termsUrl: 'https://example.invalid/terms', devOnly: true,
        variants: [{ filename: 'Qwen3.5-4B-Q4_K_M.gguf', precision: 'GGUF', targetEP: 'LLAMA_SERVER', sha256: 'C', sizeBytes: 2740937888, downloadUrl: 'https://example.invalid/c' }],
        supportingFiles: [{ filename: 'mmproj-F16.gguf', sha256: 'D', sizeBytes: 672423616, downloadUrl: 'https://example.invalid/d', extract: false, required: true }] }] };
    const base = vi.mocked(fetch).getMockImplementation()!;
    vi.mocked(fetch).mockImplementation(async (input, init) => String(input).endsWith('/manifest') ? json(registry) : base(input, init));
    store.current = { ...installedNoCard(), aiEngine: { kind: 'online', stability: { kind: 'settled' }, installFailure: null },
      runtime: { mode: 'online', modelLabel: 'Answer model', installed: { known: true, value: true }, gpu: { available: false, description: 'CPU' } },
      capabilities: { chat: true, rag: true, extract: true, embedding: true },
      runtimeStatus: { active: { chatProfile: 'compact', modelPath: '/models/compact/Qwen3.5-4B-Q4_K_M.gguf', mmprojActive: true },
        onnxFeatures: [{ id: 'embed', status: 'active', modelActive: true }] } } as unknown as AiState;
    const el = await mount();
    expect(step(el, 'chat'), 'Answer questions step').not.toBeNull();
    // A3: sizes are the amount alone; the standard package's 6.3 GB stays in the licence dialog only.
    expect([...el.shadowRoot!.querySelectorAll('.step-meta')].map(words).join(' | ')).not.toContain('6.3 GB');
    expect(words(step(el, 'chat'))).toContain('3.2 GB');
    expect(words(step(el, 'chat'))).not.toMatch(/not working|not applied/i);
  });
  // R4 — Advanced settings › Answers names the pause setting in the read words the page already uses
  // for it ("Paused while files are read"); its meaning is unchanged.
  it('Advanced names the answer pause in read words, never indexing ones', async () => {
    preview = plan(true, 'available');
    presence = { packages: ['embedding', 'reranker', 'chat'].map(id => ({ id, complete: true })) };
    store.current = { ...installedNoCard(), aiEngine: { kind: 'online', stability: { kind: 'settled' }, installFailure: null },
      capabilities: { chat: true, rag: true, extract: true, embedding: true } } as unknown as AiState;
    const el = await mount();
    const advanced = [...el.shadowRoot!.querySelectorAll<HTMLButtonElement>('button')].find(b => b.textContent?.includes('Advanced settings'));
    expect(advanced, 'Advanced settings opener').toBeTruthy();
    advanced!.click(); await settle(el);
    const answers = el.shadowRoot!.querySelector('[aria-label="Answer settings"]');
    expect(answers, 'Answer settings').not.toBeNull();
    const pause = [...answers!.querySelectorAll<HTMLElement>('.advanced-row')].find(row => /Pause answers/.test(words(row)));
    expect(pause, 'answer pause row').toBeTruthy();
    expect(words(answers)).not.toMatch(/index/i);
    expect(pause!.querySelector('[role="switch"]')!.getAttribute('aria-label')).toBe('Pause answers while files are read');
    expect(words(pause)).toContain('Pause answers while files are read');
    expect(words(pause)).toContain('Wait while many files are being read (a large import, reading everything again); keep the answer model loaded');
    });
});
