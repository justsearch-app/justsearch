// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// A6 keep (fm-16) — the search field offers Search / Ask when AI Brain says answers work (its tab row
// shows) with an NVIDIA card, and offers no Ask where AI Brain says they do not: answers switched off in
// AI Brain, the answering model failed, installed without a card, AI not installed. Each case mounts both
// over the same AI state. The states are built as BrainOverview.tabs.test.ts builds them (its state()
// factory, the store's AiState shape), with chatDisabledBySpec as the store derives it from
// status.inference.chatEnabledSpec (aiStateStore.ts). Where the two still decide differently (processor
// answers, a starting or unloaded answer model, no file read yet, a non-live install observation) the
// field keeps its own readiness (BrainOverview.install.test.ts S7a-11); that is not checked here.
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { AiState } from '../../state/aiStateStore.js';
import type { InstallPlanPreview } from '../../utils/aiInstallPoll.js';
const store = vi.hoisted(() => ({ listeners: new Set<(state: AiState) => void>(), current: {} as AiState }));
vi.mock('../../state/aiStateStore.js', async original => ({
  ...await original<typeof import('../../state/aiStateStore.js')>(),
  getAiState: () => store.current,
  subscribeAiState: (listener: (state: AiState) => void) => {
    store.listeners.add(listener); listener(store.current);
    return () => store.listeners.delete(listener);
  },
}));
import '../BrainSurface.js';
import '../MemorySurface.js';
import '../../components/SurfaceTabs.js';
import { Sv3Composer } from '../search-v3/Sv3Composer.js';
import { createMockHostApi } from '../../plugin-api/testHostApi.js';
import { setShowDeveloperTools } from '../../state/developerToolsState.js';
import { setFirstUseRoots } from '../../state/firstUse.js';
import { __seedForTest } from '../../../api/registry/SurfaceCatalogClient.js';

type Brain = HTMLElement & { apiBase: string; host_: unknown; updateComplete: Promise<boolean> };
const GB = 1024 ** 3;
const BRAIN = 'core.brain-surface', MEMORY = 'core.memory-surface';
const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'content-type': 'application/json' } });
const surface = (id: string, mountTag: string, extra: Record<string, unknown> = {}) => ({ id, mountTag,
  presentation: { labelKey: id === MEMORY ? 'Memory' : 'AI Brain', descriptionKey: '', iconHint: null, category: null },
  audience: 'USER', placement: id === MEMORY ? 'DEEPLINK' : 'RAIL', altitude: 'PRODUCT',
  consumes: { resources: [], operations: [], prompts: [], diagnosticChannels: [] },
  provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' }, ...extra });
const preview = (chat: 'installed' | 'unavailable' | 'to-download'): InstallPlanPreview => ({ totalDownloadBytes: 0, components: [
  { id: 'embedding', label: 'embedding', state: chat === 'to-download' ? 'to-download' : 'installed', necessity: 'required', totalBytes: GB, downloadBytes: 0 },
  { id: 'chat', label: 'chat', state: chat, declinable: true, totalBytes: 5 * GB, downloadBytes: 0,
    ...(chat === 'unavailable' ? { unavailableReason: 'needs CUDA' } : {}) }] });

type Answers = 'ready' | 'cpu' | 'starting' | 'unloaded' | 'off' | 'failed' | 'no-card' | 'not-installed';
function state(answers: Answers, patch: Record<string, unknown> = {}): AiState {
  const kind = answers === 'ready' || answers === 'cpu' ? 'online' : answers === 'starting' ? 'starting'
    : answers === 'off' ? 'background' : answers === 'not-installed' ? 'not_installed' : 'offline';
  const spec = answers !== 'off';
  return { phase: 'connected', installObservationLive: true, runtimeObservationLive: true, snapshotLive: true,
    aiEngine: { kind, stability: kind === 'starting' ? { kind: 'provisional', cause: 'starting' } : { kind: 'settled' }, installFailure: null },
    runtime: { mode: kind === 'online' ? 'online' : kind === 'starting' ? 'starting' : 'offline', modelLabel: 'Answer model',
      installed: { known: true, value: answers !== 'not-installed' },
      gpu: answers === 'cpu' || answers === 'no-card' ? { available: false, description: 'CPU' } : { available: true, description: 'CUDA' } },
    capabilities: { chat: kind === 'online', rag: kind === 'online', extract: false, embedding: answers !== 'not-installed' },
    chatDisabledBySpec: !spec,
    chatUnloadedReason: answers === 'unloaded' ? 'inference.unloaded_gpu_pressure' : null,
    chatFailureReason: answers === 'failed' ? 'inference.model_load_failed' : null,
    installStatus: { state: answers === 'not-installed' ? 'idle' : 'completed', installedFully: answers !== 'not-installed' },
    runtimeStatus: { active: { mmprojActive: false }, onnxFeatures: [{ id: 'embed', status: 'active', modelActive: true }] },
    status: { inference: { chatEnabledSpec: spec }, embedding: { compatState: 'COMPATIBLE' },
      ...(answers === 'cpu' || answers === 'no-card' ? {} : { gpu: { totalVramBytes: 12 * GB, usedVramBytes: 5.8 * GB, available: true, cudaFunctional: true } }) },
    index: { ownDocumentCount: { known: true, value: 8 }, documentCount: { known: true, value: 8 } },
    verdict: { kind: 'operational', severity: 'ok', reasons: [] }, ...patch } as unknown as AiState;
}

let chatEnabled: boolean | null;
let plan: InstallPlanPreview;
beforeEach(() => {
  setShowDeveloperTools(false); setFirstUseRoots(null, new Map());
  __seedForTest({ schemaVersion: '1.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface',
    entries: [surface(BRAIN, 'jf-brain-surface', { members: [MEMORY] }), surface(MEMORY, 'jf-memory-surface')] } as never);
  chatEnabled = true; plan = preview('installed');
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input);
    if (path.endsWith('/api/settings/v2')) return json({ ui: { chatEnabled }, llm: {}, rerankerEnabled: true, pictureReadingEnabled: false,
      witness: { acceptedRevision: 0, lastCommittedOperationKey: null } });
    if (path.endsWith('/plan-preview')) return json(plan);
    if (path.endsWith('/manifest')) return json({ schemaVersion: 2, packages: [] });
    if (path.endsWith('/api/ai/models/status')) return json({ packages: (plan.components ?? []).map(c => ({ id: c.id, complete: c.state === 'installed' })) });
    if (path.endsWith('/api/ai/install/downloaded-files')) return json({ parts: [] });
    if (path.endsWith('/api/memory')) return json({ memories: [], locked: false });
    return json({});
  }));
});
afterEach(() => { document.body.innerHTML = ''; store.listeners.clear(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

async function settle(el: HTMLElement & { updateComplete: Promise<boolean> }) {
  for (let i = 0; i < 6; ++i) { await new Promise(resolve => setTimeout(resolve, 0)); await el.updateComplete; }
}
/** AI Brain says answers work: it shows its tab row, "Overview | Memory". */
async function brainSaysAnswersWork(): Promise<boolean> {
  const el = document.createElement('jf-brain-surface') as Brain;
  el.apiBase = '';
  el.host_ = createMockHostApi({ data: { invokeOperation: async () => ({ success: true, structuredData: null }) } });
  document.body.append(el); await settle(el);
  const tabs = el.shadowRoot!.querySelector<HTMLElement & { updateComplete: Promise<boolean> }>('jf-surface-tabs');
  if (tabs) await settle(tabs);
  const names = [...(tabs?.shadowRoot?.querySelectorAll('[role="tab"]') ?? [])].map(t => (t.textContent ?? '').trim());
  el.remove();
  return names.length > 0;
}
/** The search field offers Ask: its Search / Ask control lists the Ask mode. */
async function fieldOffersAsk(): Promise<boolean> {
  const el = new Sv3Composer(); el.state = 'hero'; el.tier = 'search';
  document.body.append(el); await el.updateComplete;
  const modes = [...el.shadowRoot!.querySelectorAll('[data-tier]')].map(e => e.getAttribute('data-tier'));
  el.remove();
  return modes.includes('ask');
}

const cases: Array<[string, () => void, boolean]> = [
  ['answers work with an NVIDIA card (fm-14)', () => { store.current = state('ready'); }, true],
  ['answers switched off in AI Brain', () => { chatEnabled = false; store.current = state('off'); }, false],
  ['the answering model failed', () => { store.current = state('failed'); }, false],
  ['installed without a graphics card (fm-13)', () => { plan = preview('unavailable'); store.current = state('no-card'); }, false],
  ['AI not installed', () => { plan = preview('to-download'); store.current = state('not-installed'); }, false],
];
it.each(cases)('%s: the field offers Ask exactly when AI Brain shows its tab row', async (_name, arrange, expected) => {
  arrange();
  const brain = await brainSaysAnswersWork();
  const field = await fieldOffersAsk();
  expect({ brain, field }).toEqual({ brain: expected, field: expected });
});
