// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// Slice 7a preservation: behaviours the AI Brain page had before the first-minutes Overview and keeps
// after it. Each case passes on the page before slice 7a and on the page after it; elements are found
// by role, accessible name, visible text, test id or the step's data-feature, all of which both pages
// draw.
// (a) While pictures are being read and the status observation is lost, the page says the answers
//     state "when last observed" rather than as current (W4).
// (b) With AI installed but the answer model not downloaded, Answer questions is not lit (W4).
// (c) The page's own install, pause, cancel and genuine idle Repair flows, and the repair heading while
//     meaning's files are missing (AM3/AM4: genuine failure/repair headlines and idle Repair stay).
// (d) Recovery routes without developer tools (AM6): pack import when downloads are disabled, manual
//     repair links and destinations, removal outcomes, Settings › Reading memory.
// (e) Memory whatever the answer state (D1, AM7): losing readiness keeps Memory, its draft, an open
//     Forget question and a pending Forget; locked Memory reads as locked, not as empty; other tab hosts
//     keep their row.
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
import { requestReadingMemoryFocus } from '../../router/readingMemoryIntent.js';
import { requestMemberTab } from '../../router/memberTabIntent.js';
import { deepActiveElement } from '../../utils/keyboardHandler.js';
import { __seedForTest as seedSurfaces } from '../../../api/registry/SurfaceCatalogClient.js';

interface Brain extends HTMLElement { apiBase: string; host_: unknown; updateComplete: Promise<boolean> }
interface Control extends HTMLElement { updateComplete: Promise<boolean>; label?: string }
type Witness = { acceptedRevision: number; lastCommittedOperationKey: string | null };
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
const IDS = ['embedding', 'reranker', 'chat', 'splade', 'ner', 'citation-scorer'];
const BRAIN = 'core.brain-surface', MEMORY = 'core.memory-surface';
const component = (id: string, state = 'installed', extra: Record<string, unknown> = {}) => ({ id, label: id, state, declined: false, declinable: id !== 'embedding',
  necessity: id === 'embedding' ? 'required' : 'improves-results', totalBytes: 1024, downloadBytes: state === 'installed' ? 0 : 1024, ...extra });
const plan = (state: string): InstallPlanPreview => ({ totalDownloadBytes: state === 'installed' ? 0 : 3072,
  components: ['embedding', 'reranker', 'chat'].map(id => component(id, state)) });

let preview: InstallPlanPreview;
let presence: unknown;
let saved: { chatEnabled: boolean | null; rerankerEnabled?: boolean | null; pictureReadingEnabled?: boolean | null };
let witness: Witness;
let policy: Record<string, unknown>;
let downloaded: Record<string, unknown>;
let memory: { memories: Array<{ id: string; kind: string; content: string; revision: string }>; locked: boolean };
let heldDelete: Promise<void> | null;
let deletes: string[];
let operations: string[];
let posts: string[];
let confirms: string[];

const live = { phase: 'connected', installObservationLive: true, runtimeObservationLive: true, snapshotLive: true };
function ready(): AiState {
  return { ...live, aiEngine: { kind: 'online', stability: { kind: 'settled' }, installFailure: null },
    runtime: { mode: 'online', modelId: 'answer-model', modelLabel: 'Answer model', installed: { known: true, value: true }, gpu: { available: true, description: 'CUDA' } },
    capabilities: { chat: true, rag: true, extract: true, embedding: true },
    status: { inference: { chatEnabledSpec: true }, embedding: { compatState: 'COMPATIBLE' },
      operating: { gpu: { stale: false, adapter: 'Test adapter', adapterTotalBytes: 8192, adapterFreeBytes: 4096 } } },
    installStatus: { state: 'completed', phase: 'done', installedFully: true },
    runtimeStatus: { active: { mmprojActive: true }, onnxFeatures: [{ id: 'embed', status: 'active', modelActive: true }, { id: 'reranker', status: 'active', modelActive: true }] },
    index: { documentCount: { known: true, value: 24 }, ownDocumentCount: { known: true, value: 24 } } } as unknown as AiState;
}
function notInstalled(): AiState {
  return { ...live, aiEngine: { kind: 'not_installed', stability: { kind: 'settled' }, installFailure: null },
    runtime: { mode: 'offline', installed: { known: true, value: false } }, capabilities: { chat: false },
    installStatus: { state: 'idle', installedFully: false }, runtimeStatus: { onnxFeatures: [] },
    index: { ownDocumentCount: { known: true, value: 8 } } } as unknown as AiState;
}
function firstInstallRunning(): AiState {
  return { ...live, aiEngine: { kind: 'installing', stability: { kind: 'provisional', cause: 'installing' }, installFailure: null },
    runtime: { mode: 'offline', installed: { known: true, value: false } }, capabilities: { chat: false }, runtimeStatus: { onnxFeatures: [] },
    installStatus: { state: 'running', phase: 'download', installedFully: false, updatedAtEpochMs: Date.now(), downloadedBytes: 1024, totalBytes: 3072, startedAtEpochMs: 1,
      packages: [{ packageId: 'embedding', state: 'installed', bytesDownloaded: 1024, bytesTotal: 1024 },
        { packageId: 'reranker', state: 'downloading', bytesDownloaded: 0, bytesTotal: 1024 },
        { packageId: 'chat', state: 'pending', bytesDownloaded: 0, bytesTotal: 1024 }] } } as unknown as AiState;
}
/** No answers on this computer (the answer model cannot run here). */
function answersUnavailable(): AiState {
  return { ...ready(), aiEngine: { kind: 'offline', stability: { kind: 'settled' }, installFailure: null },
    runtime: { mode: 'offline', modelLabel: 'Answer model', installed: { known: true, value: true }, gpu: { available: false, description: 'CPU' } },
    capabilities: { chat: false, rag: false, extract: false, embedding: true } } as unknown as AiState;
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
  el.host_ = createMockHostApi({
    data: { invokeOperation: async (id: string) => { operations.push(id); return { success: true, structuredData: id === 'core.start-ai-install' ? { state: 'running' } : null }; } },
    ui: { showConfirmDialog: async (message: string) => { confirms.push(message); return true; } },
  });
  document.body.appendChild(el); await settle(el); return el;
}
const words = (node: Element | ShadowRoot | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
const buttons = (el: Brain) => [...el.shadowRoot!.querySelectorAll<Control>('jf-button')];
async function press(el: Brain, label: string) {
  const host = buttons(el).find(b => b.label === label);
  expect(host, `${label}; shown: ${buttons(el).map(b => b.label).join(', ')}`).toBeTruthy();
  await host!.updateComplete;
  const control = host!.shadowRoot!.querySelector<Control>('jf-control')!; await control.updateComplete;
  control.shadowRoot!.querySelector('button')!.click(); await settle(el);
}
function advanced(el: Brain) {
  return [...el.shadowRoot!.querySelectorAll<HTMLButtonElement>('.brain-links button')].find(b => words(b) === 'Advanced settings') ?? null;
}
const tabsHost = (el: Brain) => el.shadowRoot!.querySelector<HTMLElement & { updateComplete: Promise<boolean> }>('jf-surface-tabs');
const tabs = (el: Brain) => [...(tabsHost(el)?.shadowRoot?.querySelectorAll<HTMLButtonElement>('[role="tab"]') ?? [])];
const memoryEl = (el: Brain) => tabsHost(el)?.shadowRoot?.querySelector<HTMLElement & { updateComplete: Promise<boolean> }>('jf-memory-surface') ?? null;
async function settleAll(el: Brain) {
  await settle(el); const host = tabsHost(el); if (host) await settle(host);
  const m = memoryEl(el); if (m) await settle(m);
}
const surface = (id: string, mountTag: string, extra: Record<string, unknown> = {}) => ({ id, mountTag,
  presentation: { labelKey: id === MEMORY ? 'Memory' : 'AI Brain', descriptionKey: '', iconHint: null, category: null },
  audience: 'USER', placement: id === MEMORY ? 'DEEPLINK' : 'RAIL', altitude: 'PRODUCT',
  consumes: { resources: [], operations: [], prompts: [], diagnosticChannels: [] },
  provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' }, ...extra });

beforeEach(() => {
  setShowDeveloperTools(false);
  seedSurfaces({ schemaVersion: '1.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface',
    entries: [surface(BRAIN, 'jf-brain-surface', { members: [MEMORY] }), surface(MEMORY, 'jf-memory-surface')] } as never);
  preview = { totalDownloadBytes: 0, components: IDS.map(id => component(id)) };
  presence = { packages: IDS.map(id => ({ id, complete: true })) };
  saved = { chatEnabled: true }; witness = { acceptedRevision: 0, lastCommittedOperationKey: null };
  policy = {}; downloaded = { parts: [] };
  memory = { memories: [{ id: 'm1', kind: 'fact', content: 'The cat is grey', revision: `"${'a'.repeat(64)}"` }], locked: false };
  heldDelete = null; deletes = []; operations = []; posts = []; confirms = []; confirm.pending = [];
  store.current = ready();
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (init?.method === 'POST') posts.push(path);
    if (path.endsWith('/api/ai/models/status')) return json(presence);
    if (path.endsWith('/api/settings/v2') && init?.method !== 'POST') {
      return json({ ui: { chatEnabled: saved.chatEnabled }, llm: {}, rerankerEnabled: saved.rerankerEnabled ?? null,
        pictureReadingEnabled: saved.pictureReadingEnabled ?? null, witness });
    }
    if (path.endsWith('/plan-preview')) return json(preview);
    if (path.endsWith('/manifest')) return json({ schemaVersion: 2, packages: (preview.components ?? []).map(c => ({ id: c.id, label: c.label, license: 'MIT',
      termsUrl: 'https://example.invalid/terms', variants: [], supportingFiles: [] })) });
    if (path.endsWith('/api/ai/install/downloaded-files')) return json(downloaded);
    if (path.endsWith('/api/policy/effective')) return json(policy);
    if (path.endsWith('/install/pause') || path.endsWith('/install/resume')) return json({ state: 'running' });
    if (path.includes('/api/memory/') && init?.method === 'DELETE') {
      deletes.push(path); await heldDelete;
      memory = { ...memory, memories: memory.memories.filter(m => !path.endsWith(`/${m.id}`)) };
      return json({ removed: true });
    }
    if (path.endsWith('/api/memory')) return json(memory);
    return json({});
  }));
});
afterEach(() => { document.body.innerHTML = ''; store.listeners.clear(); vi.unstubAllGlobals(); setShowDeveloperTools(false); });

describe('AI Brain preservation (slice 7a)', () => {
  it('while pictures are being read, a lost observation says the answers state when last observed', async () => {
    emit({ ...ready(), status: { inference: { chatEnabledSpec: true, procedure: 'VDU_BATCH' } } } as unknown as AiState);
    const el = await mount();
    expect(words(el.shadowRoot!)).not.toContain('when last observed');
    emit({ ...store.current, snapshotLive: false } as AiState); await settle(el);
    expect(words(el.shadowRoot!)).toContain('Pictures were being read when last observed');
  });

  it('with AI installed but the answer model not downloaded, Answer questions is not lit', async () => {
    preview = { totalDownloadBytes: 1024, components: IDS.map(id => component(id, id === 'chat' ? 'to-download' : 'installed')) };
    presence = { packages: IDS.map(id => ({ id, complete: id !== 'chat' })) };
    emit({ ...ready(), aiEngine: { kind: 'offline', stability: { kind: 'settled' }, installFailure: null },
      runtime: { mode: 'offline', modelId: null, installed: { known: true, value: true } },
      capabilities: { chat: false, rag: false, extract: false, embedding: true },
      status: { inference: { chatEnabledSpec: false } },
      runtimeStatus: { onnxFeatures: [{ id: 'embed', status: 'active', modelActive: true }] } } as unknown as AiState);
    const el = await mount();
    const chat = el.shadowRoot!.querySelector<HTMLElement>('[data-feature="chat"]');
    expect(chat, 'Answer questions step').not.toBeNull();
    expect(words(chat)).toContain('Answer questions');
    // Lit is drawn as data-lit on the step (slice 7a) or data-active on its symbol (before it).
    const lit = chat!.getAttribute('data-lit') === 'true' || chat!.querySelector('.step-symbol[data-active="true"]') != null;
    expect(lit, 'Answer questions lit').toBe(false);
  });
});

describe('AI Brain preservation: the page’s install flows (slice 7a)', () => {
  it('a genuine idle missing file still offers Repair', async () => {
    preview = plan('installed');
    store.current = { ...ready(), aiEngine: { kind: 'offline', stability: { kind: 'settled' }, installFailure: null },
      runtime: { mode: 'offline', installed: { known: true, value: true } }, capabilities: { chat: false }, runtimeStatus: { onnxFeatures: [] },
      installStatus: { state: 'idle', installedFully: true, repairNeeded: true,
        packages: [{ packageId: 'reranker', state: 'failed', functionalStatus: 'inactive' }] } } as unknown as AiState;
    const el = await mount();
    expect(el.shadowRoot!.querySelector('.brain-headline')?.textContent?.trim()).toBe('Installed — repair available');
    await press(el, 'Repair');
    expect(confirms).toHaveLength(1);
    expect(operations).toEqual(['core.repair-ai-install']);
  });

  it('meaning absent with a missing file keeps the repair heading, meaning not lit', async () => {
    preview = plan('installed');
    presence = { packages: [{ id: 'embedding', complete: false }, { id: 'reranker', complete: true }, { id: 'chat', complete: true }] };
    store.current = { ...answersUnavailable(), runtimeStatus: { onnxFeatures: [] },
      installStatus: { state: 'idle', installedFully: true, repairNeeded: true } } as unknown as AiState;
    const el = await mount();
    expect(el.shadowRoot!.querySelector('.brain-headline')?.textContent?.trim()).toBe('Installed — repair available');
    const meaning = el.shadowRoot!.querySelector<HTMLElement>('.path-step[data-feature="meaning"]');
    expect(meaning, 'Understand meaning step').not.toBeNull();
    // Lit is drawn as data-lit on the step (slice 7a) or data-active on its symbol (before it).
    const lit = meaning!.getAttribute('data-lit') === 'true' || meaning!.querySelector('.step-symbol[data-active="true"]') != null;
    expect(lit, 'Understand meaning lit').toBe(false);
  });

  it('Install opens the licences, then installs; pause and cancel act on the run', async () => {
    preview = plan('to-download');
    presence = { packages: ['embedding', 'reranker', 'chat'].map(id => ({ id, complete: false })) };
    store.current = notInstalled();
    let el = await mount();
    await press(el, buttons(el).find(b => b.label?.startsWith('Install AI'))?.label ?? 'Install AI');
    expect(el.shadowRoot!.querySelector<HTMLDialogElement>('dialog.consent')?.open).toBe(true);
    await press(el, 'Accept and download');
    expect(operations).toEqual(['core.start-ai-install']);
    el.remove(); store.current = firstInstallRunning(); el = await mount();
    await press(el, 'Pause'); // slice 7b fm-12 names it Pause
    expect(posts.filter(p => p.endsWith('/install/pause'))).toHaveLength(1);
    await press(el, 'Cancel');
    expect(operations).toEqual(['core.start-ai-install', 'core.cancel-ai-install']);
  });
});

describe('AI Brain preservation: recovery routes without developer tools (slice 7a, AM6)', () => {
  it('pack import is reachable when downloads are disabled before install', async () => {
    store.current = notInstalled(); preview = plan('to-download'); policy = { downloadsEnabled: false };
    presence = { packages: ['embedding', 'reranker', 'chat'].map(id => ({ id, complete: false })) };
    const el = await mount();
    const open = advanced(el);
    expect(open, 'Advanced settings').not.toBeNull();
    open!.click(); await settle(el);
    expect(words(el.shadowRoot!)).toContain('Import an offline package');
  });

  it('manual repair links and destinations stay on the page', async () => {
    preview = plan('installed');
    store.current = { ...ready(), installStatus: { state: 'idle', installedFully: true, repairNeeded: true, packages: [{ packageId: 'reranker',
      label: 'Search reranker', terminalReason: 'transport', attempts: 3, url: 'https://example.invalid/reranker.onnx', targetPath: 'C:/models/reranker.onnx', error: 'timed out' }] } } as unknown as AiState;
    const el = await mount();
    const manual = el.shadowRoot!.querySelector('[data-testid="install-manual-fallback"]');
    expect(words(manual)).toContain('https://example.invalid/reranker.onnx');
    expect(words(manual)).toContain('C:/models/reranker.onnx');
  });

  it('an interrupted removal outcome is reachable under Advanced settings', async () => {
    preview = plan('installed');
    downloaded = { parts: [{ id: 'chat', label: 'Chat model', state: 'downloaded', sizeOnDiskBytes: 10, removable: true, previewDigest: 'd' }],
      removal: { packageId: 'chat', label: 'Chat model', state: 'interrupted', message: 'The app closed during removal.', deleted: [], kept: [] } };
    const el = await mount();
    advanced(el)!.click(); await settle(el);
    expect(words(el.shadowRoot!.querySelector('[data-testid="removal-outcome"]'))).toContain('The removal was interrupted');
  });

  it('Settings › Reading memory opens Advanced and focuses its control', async () => {
    store.current = notInstalled(); preview = plan('to-download');
    presence = { packages: ['embedding', 'reranker', 'chat'].map(id => ({ id, complete: false })) };
    const el = await mount();
    requestReadingMemoryFocus(); await settle(el);
    await new Promise(resolve => requestAnimationFrame(() => resolve(null))); await settle(el);
    expect(deepActiveElement()?.id).toBe('brain-contextWindow');
  });
});

describe('AI Brain preservation: Memory whatever the answer state (slice 7a, D1, AM7)', () => {
  it('losing readiness keeps Memory mounted with its draft, an open Forget question and a pending Forget', async () => {
    requestMemberTab(BRAIN, MEMORY);
    const el = await mount(); await settleAll(el);
    const mem = memoryEl(el);
    expect(mem, 'Memory mounted').not.toBeNull();
    const draft = mem!.shadowRoot!.querySelector<HTMLInputElement>('input.draft')!;
    draft.value = 'Remember this'; draft.dispatchEvent(new Event('input')); await settle(mem!);
    const forget = mem!.shadowRoot!.querySelector<HTMLElement & { updateComplete: Promise<boolean> }>('.list jf-button');
    expect(forget, 'Forget action').not.toBeNull();
    await forget!.updateComplete;
    const control = forget!.shadowRoot!.querySelector<HTMLElement & { updateComplete: Promise<boolean> }>('jf-control')!;
    await control.updateComplete; control.shadowRoot!.querySelector('button')!.click(); await settle(mem!);
    expect(confirm.pending).toHaveLength(1);
    emit(answersUnavailable()); await settleAll(el);
    expect(memoryEl(el)).toBe(mem);
    expect(tabs(el), 'the tab row stays while Memory is open').toHaveLength(2);
    expect(mem!.shadowRoot!.querySelector<HTMLInputElement>('input.draft')!.value).toBe('Remember this');
    let release!: () => void; heldDelete = new Promise(resolve => { release = resolve; });
    confirm.pending[0]!(true); await settle(mem!);
    expect(deletes).toHaveLength(1);
    emit({ ...answersUnavailable(), snapshotLive: false } as AiState); await settleAll(el);
    release(); await settleAll(el);
    expect(memoryEl(el)).toBe(mem);
    expect(words(mem!.shadowRoot!.querySelector('.announcement'))).toBe('Forgotten.');
  });

  it('locked Memory reads as locked, never as empty, whatever the answer state', async () => {
    memory = { memories: [], locked: true }; store.current = answersUnavailable();
    requestMemberTab(BRAIN, MEMORY);
    const el = await mount(); await settleAll(el);
    const mem = memoryEl(el);
    expect(mem, 'Memory mounted').not.toBeNull();
    // Today's locked wording (readinessNotice.ts memory.locked, unchanged by slice 7a).
    expect(words(mem!.shadowRoot!)).toContain('What the AI has learned is encrypted and locked');
    expect(words(mem!.shadowRoot!)).not.toContain('No learned memory yet.');
  });

  it('other tab hosts keep their row', async () => {
    const host = document.createElement('jf-surface-tabs') as HTMLElement & { items: unknown; updateComplete: Promise<boolean> };
    host.items = [{ id: 'a', label: 'First', content: () => 'one' }, { id: 'b', label: 'Second', content: () => 'two' }];
    document.body.append(host); await host.updateComplete;
    expect([...host.shadowRoot!.querySelectorAll('[role="tab"]')].map(words)).toEqual(['First', 'Second']);
  });
});
