// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// Slice 7a (S7a-5, S7a-6; D1, AM1, AM7, AM8) — AI Brain has a tab row only once answers work here, and
// then "Overview | Memory". Memory stays reachable by its routes in every answer state.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AiState } from '../../state/aiStateStore.js';
import type { InstallPlanPreview } from '../../utils/aiInstallPoll.js';
const store = vi.hoisted(() => ({ listeners: new Set<(state: AiState) => void>(), current: {} as AiState }));
const confirm = vi.hoisted(() => ({ pending: [] as Array<(ok: boolean) => void> }));
vi.mock('../../state/aiStateStore.js', async original => ({
  ...await original<typeof import('../../state/aiStateStore.js')>(),
  subscribeAiState: (listener: (state: AiState) => void) => {
    store.listeners.add(listener); listener(store.current);
    return () => store.listeners.delete(listener);
  },
}));
vi.mock('../../components/ConfirmDialog.js', async original => ({
  ...await original<typeof import('../../components/ConfirmDialog.js')>(),
  confirmAsync: () => new Promise<boolean>(resolve => confirm.pending.push(resolve)),
}));
import '../BrainSurface.js';
import '../MemorySurface.js';
import '../../components/SurfaceTabs.js';
import { createMockHostApi } from '../../plugin-api/testHostApi.js';
import { setShowDeveloperTools } from '../../state/developerToolsState.js';
import { requestMemberTab } from '../../router/memberTabIntent.js';
import { __seedForTest } from '../../../api/registry/SurfaceCatalogClient.js';
import { deepActiveElement } from '../../utils/keyboardHandler.js';

interface Brain extends HTMLElement { apiBase: string; host_: unknown; updateComplete: Promise<boolean> }
const GB = 1024 ** 3;
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
const BRAIN = 'core.brain-surface', MEMORY = 'core.memory-surface';
let chatEnabled: boolean | null;
let memory: { memories: Array<{ id: string; kind: string; content: string; revision: string }>; locked: boolean };
let heldDelete: Promise<void> | null;
let deletes: string[];
const surface = (id: string, mountTag: string, extra: Record<string, unknown> = {}) => ({ id, mountTag,
  presentation: { labelKey: id === MEMORY ? 'Memory' : 'AI Brain', descriptionKey: '', iconHint: null, category: null },
  audience: 'USER', placement: id === MEMORY ? 'DEEPLINK' : 'RAIL', altitude: 'PRODUCT',
  consumes: { resources: [], operations: [], prompts: [], diagnosticChannels: [] },
  provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' }, ...extra });
const preview = (chat: 'installed' | 'unavailable'): InstallPlanPreview => ({ totalDownloadBytes: 0, components: [
  { id: 'embedding', label: 'embedding', state: 'installed', necessity: 'required', totalBytes: GB, downloadBytes: 0 },
  { id: 'reranker', label: 'reranker', state: 'installed', declinable: true, totalBytes: GB / 4, downloadBytes: 0 },
  chat === 'installed' ? { id: 'chat', label: 'chat', state: 'installed', declinable: true, totalBytes: 5 * GB, downloadBytes: 0 }
    : { id: 'chat', label: 'chat', state: 'unavailable', declinable: true, totalBytes: 5 * GB, downloadBytes: 0, unavailableReason: 'needs CUDA' }] });
let plan: InstallPlanPreview;
type Answers = 'ready' | 'off' | 'unavailable' | 'starting' | 'unloaded' | 'cpu' | 'failed';
function state(answers: Answers, patch: Record<string, unknown> = {}): AiState {
  const kind = answers === 'ready' || answers === 'cpu' ? 'online' : answers === 'starting' ? 'starting'
    : answers === 'off' ? 'background' : 'offline';
  return { phase: 'connected', installObservationLive: true, runtimeObservationLive: true, snapshotLive: true,
    aiEngine: { kind, stability: kind === 'starting' ? { kind: 'provisional', cause: 'starting' } : { kind: 'settled' }, installFailure: null },
    runtime: { mode: kind === 'online' ? 'online' : 'offline', modelLabel: 'Answer model', installed: { known: true, value: true },
      gpu: answers === 'cpu' ? { available: false, description: 'CPU' } : { available: true, description: 'CUDA' } },
    capabilities: { chat: kind === 'online', rag: kind === 'online', extract: false, embedding: true },
    chatUnloadedReason: answers === 'unloaded' ? 'inference.unloaded_gpu_pressure' : null,
    chatFailureReason: answers === 'failed' ? 'inference.model_load_failed' : null,
    installStatus: { state: 'completed', installedFully: true },
    runtimeStatus: { active: { mmprojActive: true }, onnxFeatures: [{ id: 'embed', status: 'active', modelActive: true }, { id: 'reranker', status: 'active', modelActive: true }] },
    status: answers === 'cpu' ? { inference: { chatEnabledSpec: true }, embedding: { compatState: 'COMPATIBLE' } } : { inference: { chatEnabledSpec: answers !== 'off' },
      embedding: { compatState: 'COMPATIBLE' },
      gpu: { totalVramBytes: 12 * GB, usedVramBytes: 5.8 * GB, available: true, cudaFunctional: true },
      operating: { gpu: { stale: false, adapter: 'RTX 4070', adapterTotalBytes: 12 * GB, adapterFreeBytes: 6.2 * GB } } },
    index: { ownDocumentCount: { known: true, value: 8 } }, ...patch } as unknown as AiState;
}
function emit(next: AiState) {
  store.current = next;
  for (const listener of store.listeners) listener(store.current);
}
async function settle(el: HTMLElement & { updateComplete: Promise<boolean> }) {
  for (let i = 0; i < 6; ++i) { await new Promise(resolve => setTimeout(resolve, 0)); await el.updateComplete; }
}
async function mount() {
  const el = document.createElement('jf-brain-surface') as Brain;
  el.apiBase = '';
  el.host_ = createMockHostApi({ data: { invokeOperation: async () => ({ success: true, structuredData: null }) } });
  document.body.append(el); await settle(el); return el;
}
const words = (node: Element | ShadowRoot | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
const tabsHost = (el: Brain) => el.shadowRoot!.querySelector<HTMLElement & { updateComplete: Promise<boolean> }>('jf-surface-tabs');
const tabs = (el: Brain) => [...(tabsHost(el)?.shadowRoot?.querySelectorAll<HTMLButtonElement>('[role="tab"]') ?? [])];
const tabNames = (el: Brain) => tabs(el).map(words);
const memoryEl = (el: Brain) => tabsHost(el)?.shadowRoot?.querySelector<HTMLElement & { updateComplete: Promise<boolean> }>('jf-memory-surface') ?? null;
async function settleAll(el: Brain) {
  await settle(el); const host = tabsHost(el); if (host) await settle(host);
  const m = memoryEl(el); if (m) await settle(m);
}

beforeEach(() => {
  setShowDeveloperTools(false);
  __seedForTest({ schemaVersion: '1.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface',
    entries: [surface(BRAIN, 'jf-brain-surface', { members: [MEMORY] }), surface(MEMORY, 'jf-memory-surface')] } as never);
  chatEnabled = true; plan = preview('installed'); heldDelete = null; deletes = []; confirm.pending = [];
  memory = { memories: [{ id: 'm1', kind: 'fact', content: 'The cat is grey', revision: `"${'a'.repeat(64)}"` }], locked: false };
  store.current = state('ready');
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path.endsWith('/api/settings/v2')) return json({ ui: { chatEnabled }, llm: {}, rerankerEnabled: true, pictureReadingEnabled: false,
      witness: { acceptedRevision: 0, lastCommittedOperationKey: null } });
    if (path.endsWith('/plan-preview')) return json(plan);
    if (path.endsWith('/manifest')) return json({ schemaVersion: 2, packages: [] });
    if (path.endsWith('/api/ai/models/status')) return json({ packages: (plan.components ?? []).map(c => ({ id: c.id, complete: c.state === 'installed' })) });
    if (path.endsWith('/api/ai/install/downloaded-files')) return json({ parts: [] });
    if (path.includes('/api/memory/') && init?.method === 'DELETE') {
      deletes.push(path); await heldDelete;
      memory = { ...memory, memories: memory.memories.filter(m => !path.endsWith(`/${m.id}`)) };
      return json({ removed: true });
    }
    if (path.endsWith('/api/memory')) return json(memory);
    return json({});
  }));
});
afterEach(() => { document.body.innerHTML = ''; store.listeners.clear(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

describe('S7a-5 answers work: Overview | Memory', () => {
  it('shows the tab row, the graphics card reading and the two pictured links', async () => {
    const el = await mount();
    expect(tabNames(el)).toEqual(['Overview', 'Memory']);
    const meter = el.shadowRoot!.querySelector('.graphics-card');
    expect(meter, 'graphics card meter').not.toBeNull();
    expect(words(meter)).toContain('5.80 GB of 12.00 GB in use · RTX 4070');
    const links = words(el.shadowRoot!.querySelector('.brain-links'));
    expect(links).toContain('Advanced settings'); expect(links).toContain('Engine history in Health');
    expect(el.shadowRoot!.querySelector('.brain-headline')?.textContent?.trim()).toBe('AI is ready');
    expect(words(el.shadowRoot!.querySelector('.brain-sentence'))).toBe('Everything below runs on this computer.');
  });
  it.each([
    ['ready with zero files', state('ready', { index: { ownDocumentCount: { known: true, value: 0 } } })],
    ['unloaded but startable', state('unloaded')],
    ['on the processor', state('cpu')],
    ['starting', state('starting')],
  ])('%s counts as answers work', async (_name, next) => {
    store.current = next;
    const el = await mount();
    expect(tabNames(el)).toEqual(['Overview', 'Memory']);
  });
  // X3 — an unloaded answer model the runtime will not load again (operating.chat.reloadAllowed=false)
  // refuses ordinary questions, so answers do not work: no tab row and Answer questions unlit.
  it('unloaded with its reload not allowed: no tab row, Answer questions not lit', async () => {
    const base = state('unloaded');
    store.current = { ...base, status: { ...(base.status as object), operating: { ...((base.status as { operating: object }).operating),
      chat: { state: 'UNLOADED', reloadAllowed: false, blockedBy: 'GPU_BUSY' } } } } as unknown as AiState;
    const el = await mount();
    expect(tabNames(el)).toEqual([]);
    const chat = el.shadowRoot!.querySelector<HTMLElement>('.path-step[data-feature="chat"]');
    expect(chat, 'Answer questions step').not.toBeNull();
    expect(chat!.dataset.lit ?? chat!.querySelector<HTMLElement>('.step-symbol')?.dataset.active).toBe('false');
  });
  it('unloaded with its reload allowed still counts as answers work', async () => {
    const base = state('unloaded');
    store.current = { ...base, status: { ...(base.status as object), operating: { ...((base.status as { operating: object }).operating),
      chat: { state: 'UNLOADED', reloadAllowed: true, blockedBy: null } } } } as unknown as AiState;
    const el = await mount();
    expect(tabNames(el)).toEqual(['Overview', 'Memory']);
    expect(el.shadowRoot!.querySelector<HTMLElement>('.path-step[data-feature="chat"]')?.dataset.lit).toBe('true');
  });
  it.each([
    ['answers off', 'off'], ['answers unavailable', 'unavailable'], ['answers failed', 'failed'],
  ] as const)('%s: no tab row', async (_name, answers) => {
    if (answers === 'off') chatEnabled = false;
    if (answers === 'unavailable') plan = preview('unavailable');
    store.current = state(answers);
    const el = await mount();
    expect(tabsHost(el), 'tab host').not.toBeNull();
    expect(tabNames(el)).toEqual([]);
    expect(el.shadowRoot!.querySelector('.brain-headline')).not.toBeNull();
  });
  it('a stale or unknown observation keeps the row as last shown', async () => {
    store.current = { ...state('ready'), snapshotLive: false } as AiState;
    let el = await mount();
    expect(tabNames(el)).toEqual([]);
    el.remove();
    store.current = state('ready'); el = await mount();
    expect(tabNames(el)).toEqual(['Overview', 'Memory']);
    emit({ ...state('unavailable'), snapshotLive: false } as AiState); await settleAll(el);
    expect(tabNames(el)).toEqual(['Overview', 'Memory']);
    emit({ ...state('ready'), aiEngine: { kind: 'connecting', stability: { kind: 'provisional', cause: 'stale-poll' }, installFailure: null } } as AiState);
    await settleAll(el);
    expect(tabNames(el)).toEqual(['Overview', 'Memory']);
  });
  it('shows the graphics meter only with a current reading and never says "no card" without one', async () => {
    store.current = state('cpu');
    let el = await mount();
    expect(el.shadowRoot!.querySelector('.graphics-card')).toBeNull();
    expect(words(el.shadowRoot!)).not.toMatch(/Needs an NVIDIA graphics card|Memory use unknown/);
    expect(tabNames(el)).toEqual(['Overview', 'Memory']);
    el.remove();
    store.current = state('ready', { status: { ...(state('ready').status as object), operating: { gpu: { stale: true, adapter: 'RTX 4070' } } } });
    el = await mount();
    expect(el.shadowRoot!.querySelector('.graphics-card')).toBeNull();
    el.remove();
    store.current = state('ready', { installStatus: { state: 'running', phase: 'download', installedFully: true },
      aiEngine: { kind: 'installing', stability: { kind: 'provisional', cause: 'installing' }, installFailure: null } });
    el = await mount();
    expect(el.shadowRoot!.querySelector('.graphics-card')).toBeNull();
  });
});

describe('S7a-6 Memory routes in every answer state', () => {
  it.each(['ready', 'off', 'unavailable', 'starting'] as const)('answers %s: deep link, address tab and reload open Memory with Overview reachable', async answers => {
    if (answers === 'off') chatEnabled = false;
    if (answers === 'unavailable') plan = preview('unavailable');
    store.current = state(answers);
    requestMemberTab(BRAIN, MEMORY); // the redirect of a deep link / palette entry before the area mounts
    let el = await mount(); await settleAll(el);
    expect(tabNames(el)).toEqual(['Overview', 'Memory']);
    expect(tabs(el)[1]!.getAttribute('aria-selected')).toBe('true');
    expect(memoryEl(el), 'Memory mounted').not.toBeNull();
    requestMemberTab(BRAIN, 'runtime'); await settleAll(el); // Back to Overview
    expect(memoryEl(el)).toBeNull();
    requestMemberTab(BRAIN, MEMORY); await settleAll(el); // Forward / ?tab= while mounted
    expect(memoryEl(el), 'Memory mounted again').not.toBeNull();
    expect(tabNames(el)).toEqual(['Overview', 'Memory']);
    el.remove(); requestMemberTab(BRAIN, MEMORY); el = await mount(); await settleAll(el); // reload
    expect(memoryEl(el), 'Memory after reload').not.toBeNull();
  });


  it('returning to Overview syncs the address, and focus lands on the heading when the row leaves', async () => {
    // X4 — the heading may take focus only in the tab host's final drawing: a slot the host then
    // replaces takes the heading out of the rendered tree, and a browser drops focus with it.
    const realFocus = HTMLElement.prototype.focus;
    let headingSlot: Element | null | undefined;
    vi.spyOn(HTMLElement.prototype, 'focus').mockImplementation(function (this: HTMLElement, options?: FocusOptions) {
      if (this.classList.contains('brain-headline')) {
        const slotted = this.closest('[slot]');
        headingSlot = slotted?.parentElement?.shadowRoot?.querySelector(`slot[name="${slotted.getAttribute('slot')}"]`) ?? null;
      }
      realFocus.call(this, options);
    });
    plan = preview('unavailable'); store.current = state('unavailable');
    requestMemberTab(BRAIN, MEMORY);
    const el = await mount(); await settleAll(el);
    const changes: Array<{ id: string; previous?: string }> = [];
    document.addEventListener('tab-change', e => changes.push((e as CustomEvent).detail), { once: true });
    expect(tabs(el)).toHaveLength(2);
    tabs(el)[0]!.focus(); tabs(el)[0]!.click(); await settleAll(el);
    expect(changes).toEqual([{ id: 'runtime', previous: MEMORY }]);
    expect(tabNames(el)).toEqual([]);
    const active = deepActiveElement();
    expect(active?.classList.contains('brain-headline')).toBe(true);
    expect(headingSlot, 'the heading was focused where it is drawn').toBeTruthy();
    expect(headingSlot!.isConnected, 'focus was not placed in a drawing the tab host then removed').toBe(true);
  });
});
