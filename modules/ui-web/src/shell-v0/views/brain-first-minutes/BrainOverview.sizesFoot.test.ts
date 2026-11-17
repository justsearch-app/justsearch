// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// A3 — a step's size on AI Brain is the amount alone ("1.2 GB", "341 MB", "5.7 GB"), before and after
// installing. A4 — the foot of the page: with AI installed and answers not working (fm-13) "Advanced
// settings" and "Remove AI models", the second opening Advanced settings at the Downloaded files controls
// with focus there and removing nothing; with answers working (fm-14) "Advanced settings" and "Engine
// history in Health". A7 keeps: "Text in scans and images" shows no size; on Linux the answer step says
// "Not available on Linux yet". The AI states are BrainOverview.steps.test.ts's notInstalled() and
// installedNoCard() and BrainOverview.tabs.test.ts's state('ready'); the plan previews have the
// InstallPlanPreview shape (utils/aiInstallPoll.ts) with the pictures' sizes.
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
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

type Brain = HTMLElement & { apiBase: string; host_: unknown; updateComplete: Promise<boolean> };
const GB = 1024 ** 3, MB = 1024 ** 2;
const SIZES = { embedding: Math.round(1.2 * GB), reranker: 341 * MB, chat: Math.round(5.7 * GB) } as const;
const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'content-type': 'application/json' } });
const words = (node: Element | ShadowRoot | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
const live = { phase: 'connected', installObservationLive: true, runtimeObservationLive: true, snapshotLive: true, indexingStatusLive: true,
  episodeMaxPendingJobs: 0, enrichSettleSamples: [], connection: { reachable: true, lastSuccessMs: 1, lastContactMs: 1, consecutiveFailures: 0 },
  activity: { state: 'idle', shapeId: null, startedAtMs: null, canCancel: false, cancel: null }, stability: { kind: 'settled' },
  verdict: { kind: 'operational', severity: 'ok', reasons: [] }, index: { ownDocumentCount: { known: true, value: 8 } } };
const noCardStatus = { gpu: { available: false, cudaFunctional: false, deviceCount: 0 }, inference: { chatEnabledSpec: false },
  embedding: { compatState: 'COMPATIBLE' }, operating: { gpu: { stale: false, adapter: null, profile: 'NO_GPU', source: 'NONE' } } };
type Picture = 'fm-10' | 'fm-13' | 'fm-14' | 'fm-13-linux';
const STATES: Record<Picture, () => AiState> = {
  'fm-10': () => ({ ...live, aiEngine: { kind: 'not_installed', stability: { kind: 'settled' }, installFailure: null, notRequested: true },
    runtime: { mode: 'offline', installed: { known: true, value: false }, gpu: { available: false, description: 'CPU' } },
    capabilities: { chat: false, rag: false, extract: false, embedding: false },
    installStatus: { state: 'idle', installedFully: false }, runtimeStatus: { onnxFeatures: [] }, status: noCardStatus }) as unknown as AiState,
  'fm-13': () => ({ ...live, aiEngine: { kind: 'offline', stability: { kind: 'settled' }, installFailure: null },
    runtime: { mode: 'offline', installed: { known: true, value: true }, gpu: { available: false, description: 'CPU' } },
    capabilities: { chat: false, rag: false, extract: false, embedding: true }, installStatus: { state: 'idle', installedFully: true },
    runtimeStatus: { onnxFeatures: [{ id: 'embed', status: 'active', modelActive: true }, { id: 'reranker', status: 'inactive', modelActive: false }] },
    status: noCardStatus }) as unknown as AiState,
  'fm-13-linux': () => STATES['fm-13'](),
  'fm-14': () => ({ ...live, aiEngine: { kind: 'online', stability: { kind: 'settled' }, installFailure: null },
    runtime: { mode: 'online', modelLabel: 'Answer model', installed: { known: true, value: true }, gpu: { available: true, description: 'CUDA' } },
    capabilities: { chat: true, rag: true, extract: false, embedding: true }, installStatus: { state: 'completed', installedFully: true },
    runtimeStatus: { active: { mmprojActive: false, chatProfile: 'standard' }, onnxFeatures: [{ id: 'embed', status: 'active', modelActive: true }, { id: 'reranker', status: 'active', modelActive: true }] },
    status: { inference: { chatEnabledSpec: true }, embedding: { compatState: 'COMPATIBLE' },
      gpu: { totalVramBytes: 12 * GB, usedVramBytes: 5.8 * GB, available: true, cudaFunctional: true },
      operating: { gpu: { stale: false, adapter: 'RTX 4070', adapterTotalBytes: 12 * GB, adapterFreeBytes: 6.2 * GB } } } }) as unknown as AiState,
};
function plan(picture: Picture): InstallPlanPreview {
  const installed = picture !== 'fm-10';
  const c = (id: keyof typeof SIZES, extra: Record<string, unknown> = {}) => ({ id, label: id, state: installed ? 'installed' : 'to-download',
    declinable: id !== 'embedding', necessity: id === 'embedding' ? 'required' : 'improves-results', totalBytes: SIZES[id],
    downloadBytes: installed ? 0 : SIZES[id], ...extra });
  const chat = picture === 'fm-14' ? c('chat') : c('chat', { state: 'unavailable', downloadBytes: 0, unavailableReason: picture === 'fm-13-linux'
    ? 'Chat model is unavailable: Linux GPU packs have not been published yet.' : 'Chat model requires a CUDA-capable GPU (none detected on this system).' });
  return { totalDownloadBytes: installed ? 0 : SIZES.embedding + SIZES.reranker, components: [c('embedding'), c('reranker'), chat] } as InstallPlanPreview;
}

let picture: Picture;
let operations: string[];
let mutations: string[];
let platform: string;
beforeEach(() => {
  setShowDeveloperTools(false); operations = []; mutations = []; platform = 'Win32';
  Object.defineProperty(navigator, 'platform', { configurable: true, get: () => platform });
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (init?.method && init.method !== 'GET') mutations.push(`${init.method} ${path}`);
    if (path.endsWith('/api/settings/v2')) return json({ ui: { chatEnabled: picture === 'fm-14' }, llm: {}, rerankerEnabled: picture === 'fm-14',
      pictureReadingEnabled: false, witness: { acceptedRevision: 0, lastCommittedOperationKey: null } });
    if (path.endsWith('/plan-preview')) return json(plan(picture));
    if (path.endsWith('/manifest')) return json({ schemaVersion: 2, packages: [] });
    if (path.endsWith('/api/ai/models/status')) return json({ packages: (plan(picture).components ?? []).map(c => ({ id: c.id, complete: c.state === 'installed' })) });
    if (path.endsWith('/api/ai/install/downloaded-files')) return json({ parts: picture === 'fm-10' ? [] : [
      { id: 'embedding', label: 'Understand meaning', state: 'downloaded', sizeOnDiskBytes: SIZES.embedding, removable: false, reason: 'Needed for meaning search' },
      { id: 'reranker', label: 'Best match first', state: 'downloaded', sizeOnDiskBytes: SIZES.reranker, removable: true, previewDigest: 'sha256:preview' }] });
    return json({});
  }));
});
afterEach(() => { document.body.innerHTML = ''; store.listeners.clear(); vi.unstubAllGlobals(); delete (navigator as unknown as Record<string, unknown>).platform; });

async function settle(el: Brain) {
  for (let i = 0; i < 6; ++i) { await new Promise(resolve => setTimeout(resolve, 0)); await el.updateComplete; }
}
async function show(p: Picture): Promise<Brain> {
  picture = p; if (p === 'fm-13-linux') platform = 'Linux x86_64';
  store.current = STATES[p]();
  const el = document.createElement('jf-brain-surface') as Brain;
  el.apiBase = '';
  el.host_ = createMockHostApi({ data: { invokeOperation: async (id: string) => { operations.push(id); return { success: true, structuredData: null }; } } });
  document.body.append(el); await settle(el); return el;
}
const step = (el: Brain, id: string) => el.shadowRoot!.querySelector<HTMLElement>(`.path-step[data-feature="${id}"]`);
const sizeOf = (el: Brain, id: string) => [...(step(el, id)?.querySelectorAll('.step-meta') ?? [])].map(m => words(m))
  .filter(text => /\d\s?(B|KB|MB|GB|TB)\b/.test(text));
const foot = (el: Brain) => [...el.shadowRoot!.querySelectorAll('.brain-links > button, .brain-links > a')].map(link => words(link));

it.each([
  ['fm-10', { meaning: ['1.2 GB'], reranker: ['341 MB'] }],
  ['fm-13', { meaning: ['1.2 GB'], reranker: ['341 MB'] }],
  ['fm-14', { meaning: ['1.2 GB'], reranker: ['341 MB'], chat: ['5.7 GB'] }],
] as const)('A3: %s shows each step\'s size as the amount alone', async (p, expected) => {
  const el = await show(p);
  for (const [id, size] of Object.entries(expected)) expect(sizeOf(el, id), id).toEqual(size);
  expect(words(el.shadowRoot!.querySelector('[data-testid="brain-simple-panel"]'))).not.toMatch(/B download/);
});

it.each(['fm-10', 'fm-13', 'fm-14'] as const)('A7 keep: %s shows no size under "Text in scans and images"', async p => {
  const el = await show(p);
  expect(step(el, 'pictures'), 'Text in scans and images').not.toBeNull();
  expect(sizeOf(el, 'pictures')).toEqual([]);
});

it('A7 keep: on Linux the answer step keeps "Not available on Linux yet"', async () => {
  const el = await show('fm-13-linux');
  expect(words(step(el, 'chat'))).toContain('Not available on Linux yet');
});

it('A4: installed without a card, the foot offers Advanced settings and Remove AI models', async () => {
  const el = await show('fm-13');
  expect(words(el.shadowRoot!.querySelector('.brain-headline'))).toBe('Search understands meaning');
  expect(foot(el)).toEqual(['Advanced settings', 'Remove AI models']);
});

it('A4: Remove AI models opens Advanced settings at the Downloaded files controls with focus there, removing nothing', async () => {
  const el = await show('fm-13');
  expect(el.shadowRoot!.querySelector('.brain-advanced')).toBeNull();
  const link = [...el.shadowRoot!.querySelectorAll<HTMLElement>('.brain-links > button')].find(b => words(b) === 'Remove AI models');
  expect(link, 'Remove AI models').toBeDefined();
  link!.click(); await settle(el);
  expect(el.shadowRoot!.querySelector('.brain-advanced')).not.toBeNull();
  const advanced = [...el.shadowRoot!.querySelectorAll('.brain-links > button')].find(b => words(b) === 'Advanced settings');
  expect(advanced?.getAttribute('aria-expanded')).toBe('true');
  const focused = el.shadowRoot!.activeElement;
  expect(focused?.closest('section')?.getAttribute('aria-label')).toBe('Downloaded files');
  expect(focused?.querySelector('[data-remove="reranker"]') ?? focused?.closest('section')?.querySelector('[data-remove="reranker"]')).not.toBeNull();
  expect(operations).toEqual([]);
  expect(mutations).toEqual([]);
  expect(el.shadowRoot!.querySelector('[data-testid="removal-confirm"]')).toBeNull();
});

it('A4 keep: with answers working the foot is Advanced settings and Engine history in Health', async () => {
  const el = await show('fm-14');
  expect(words(el.shadowRoot!.querySelector('.brain-headline'))).toBe('AI is ready');
  expect(foot(el)).toEqual(['Advanced settings', 'Engine history in Health']);
});
