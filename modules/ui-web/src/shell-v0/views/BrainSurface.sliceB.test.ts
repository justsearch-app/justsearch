// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AiState } from '../state/aiStateStore.js';
import type { InstallPlanPreview } from '../utils/aiInstallPoll.js';
const store = vi.hoisted(() => ({ listeners: new Set<(state: AiState) => void>(), current: {} as AiState }));
vi.mock('../state/aiStateStore.js', async original => ({
  ...await original<typeof import('../state/aiStateStore.js')>(),
  subscribeAiState: (listener: (state: AiState) => void) => {
    store.listeners.add(listener); listener(store.current);
    return () => store.listeners.delete(listener);
  },
}));
import './BrainSurface.js';

import { createMockHostApi } from '../plugin-api/testHostApi.js';
import type { AiInstallManifest } from '../../api/domains/packs.js';

interface Brain extends HTMLElement { apiBase: string; host_: unknown; llm: {contextWindow?: number; gpuLayers?: number | null; maxTokens?: number | null; modelPath?: string | null}; patchLlm(updates: {contextWindow: number}): Promise<void>; refreshSettings(): Promise<void>; updateComplete: Promise<boolean> }
interface Button extends HTMLElement { updateComplete: Promise<boolean>; disabled: boolean; label: string }
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
const manifest: AiInstallManifest = { schemaVersion: 2, packages: ['embedding', 'reranker', 'chat', 'splade', 'ner', 'citation-scorer'].map(id => ({ id, label: id, license: 'Apache-2.0', termsUrl: 'https://example.invalid/terms', variants: [], supportingFiles: [] })) };
let preview: InstallPlanPreview;
let presence: unknown;
let llmValues: Record<string, unknown>;
let settingsConflict: boolean;
let chatEnabled: boolean | null;
let settingsGet: (() => Promise<Response>) | null;
let previewGet: (() => Promise<Response>) | null;
let declineWait: Promise<void> | null;
let operationWait: Promise<void> | null;
let manifestFails: boolean;
let calls: Array<{ id: string; args: unknown }>;
let declines: string[];
const component = (id: string, state = 'installed', declined = false) => ({ id, label: id, state, declined, declinable: id !== 'embedding', necessity: id === 'embedding' ? 'required' : 'improves-results', totalBytes: 1024, downloadBytes: state === 'installed' ? 0 : 1024 });
function emit(patch: Partial<AiState> = {}) {
  store.current = { ...store.current, ...patch };
  for (const listener of store.listeners) listener(store.current);
}
async function settle(el: Brain) { await new Promise(resolve => setTimeout(resolve, 0)); await el.updateComplete; }
function switches(el: Brain, feature: string): HTMLButtonElement[] { return [...el.shadowRoot!.querySelectorAll<HTMLButtonElement>(`button[role="switch"][aria-label="Use ${feature}"]`)]; }
async function clickButton(el: Brain, label: string) {
  const host = [...el.shadowRoot!.querySelectorAll<Button>('jf-button')].find(button => button.label === label)!;
  expect(host, `button ${label}; rendered: ${[...el.shadowRoot!.querySelectorAll<Button>('jf-button')].map(button => button.label).join(', ')}`).toBeTruthy(); await host.updateComplete;
  const control = host.shadowRoot!.querySelector<Button>('jf-control')!; await control.updateComplete;
  control.shadowRoot!.querySelector<HTMLButtonElement>('button')!.click(); await settle(el);
  return host;
}
async function mount() {
  const el = document.createElement('jf-brain-surface') as Brain;
  el.apiBase = '';
  el.host_ = createMockHostApi({ data: { invokeOperation: async (id: string, args: unknown) => {
    calls.push({ id, args }); await operationWait;
    if (id === 'core.set-chat-enabled') chatEnabled = (args as { enabled: boolean }).enabled;
    return { success: true, structuredData: id === 'core.start-ai-install' ? { state: 'running', phase: 'download' } : null };
  } } });
  document.body.appendChild(el); await settle(el); return el;
}
beforeEach(() => {
  preview = { totalDownloadBytes: 0, components: ['embedding', 'reranker', 'chat', 'splade', 'ner', 'citation-scorer'].map(id => component(id)) };
  presence = {}; llmValues = {}; settingsConflict = false;
  chatEnabled = true; settingsGet = null; previewGet = null; declineWait = null; operationWait = null; manifestFails = false; calls = []; declines = [];
  store.current = {
    aiEngine: { kind: 'online', stability: { kind: 'settled' }, installFailure: null }, snapshotLive: true,
    runtime: { mode: 'online', modelId: 'answer-model', modelLabel: 'Answer model', installed: { known: true, value: true } },
    capabilities: { chat: true }, status: { inference: { chatEnabledSpec: true }, operating: { gpu: { stale: false, adapter: 'Test adapter', adapterTotalBytes: 8192, adapterFreeBytes: 4096 } } },
    installStatus: { state: 'completed', phase: 'done', installedFully: true },
    runtimeStatus: { active: { mmprojActive: true }, onnxFeatures: [{ id: 'embed', modelActive: true }, { id: 'reranker', modelActive: true }] },
    index: { documentCount: { known: true, value: 24 } },
  } as unknown as AiState;
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path.endsWith('/api/ai/models/status')) return json(presence);
    if (path.endsWith('/api/settings/v2') && init?.method === 'POST') {
      const body = JSON.parse(String(init.body));
      if (settingsConflict) return json({ errorCode: 'VERSION_CONFLICT' }, 409);
      llmValues = { ...llmValues, ...body.llm };
      return json({ state: 'COMPLETE', operationKey: body.operationKey, witness: { acceptedRevision: 1, lastCommittedOperationKey: body.operationKey }, llm: body.llm });
    }
    if (path.endsWith('/api/settings/v2')) return settingsGet ? settingsGet() : json({ ui: { chatEnabled }, llm: llmValues, witness: { acceptedRevision: 0, lastCommittedOperationKey: null } });
    if (path.endsWith('/plan-preview')) return previewGet ? previewGet() : json(preview);
    if (path.endsWith('/manifest')) return json(manifest, manifestFails ? 503 : 200);
    if (path.includes('/decline')) {
      declines.push(path); await declineWait;
      const id = path.split('/').at(-2)!;
      preview = { ...preview, components: preview.components!.map(c => c.id === id ? { ...c, declined: init?.method === 'POST', state: init?.method === 'POST' ? 'declined' : 'to-download' } : c), totalDownloadBytes: 1024 };
    }
    return json({});
  }));
});
afterEach(() => { document.body.innerHTML = ''; store.listeners.clear(); vi.unstubAllGlobals(); vi.useRealTimers(); });


function notInstalled() {
  emit({ capabilities: { chat: false } as AiState['capabilities'],
    installStatus: { state: 'idle', installedFully: false },
    runtime: { mode: 'offline', modelId: null, installed: { known: true, value: false } } as AiState['runtime'],
    aiEngine: { kind: 'not_installed', stability: { kind: 'settled' }, installFailure: null } });
}
async function advanced(el: Brain) {
  el.shadowRoot!.querySelector<HTMLButtonElement>('.brain-links button')!.click(); await el.updateComplete;
}
describe('Brain slice A independent review repairs', () => {
  it('R1 acquisition decline does not hide existing answer files or replace their runtime switch, including after remount', async () => {
    preview.components = preview.components!.map(c => c.id === 'chat' ? component('chat', 'declined', true) : c);
    presence = { packages: [{ id: 'chat', complete: false, presentVariantFiles: ['answers.gguf'] }] };
    emit({ capabilities: { chat: false } as AiState['capabilities'], runtime: { mode: 'offline', modelId: null, installed: { known: true, value: true } } as AiState['runtime'] });
    chatEnabled = false;
    const first = await mount();
    expect(first.shadowRoot!.querySelector('[data-feature="chat"]')!.textContent).not.toContain('Not downloaded');
    switches(first, 'Answer questions')[0]!.click(); await settle(first);
    expect(calls).toEqual([{ id: 'core.set-chat-enabled', args: { enabled: true } }]);
    expect(declines).toEqual([]); first.remove();
    const second = await mount();
    expect(switches(second, 'Answer questions').map(b => b.getAttribute('aria-checked'))).toEqual(['true', 'true']);
    expect(second.shadowRoot!.querySelector('[data-feature="chat"]')!.textContent).not.toContain('Not downloaded');
  });
  it.each(['unavailable', 'not-in-mode'])('R2 %s answers are not promised, and pictures use explicit availability', async state => {
    notInstalled();
    preview.components = preview.components!.map(c => ({ ...c, state: c.id === 'chat' ? state : 'to-download', unavailableReason: c.id === 'chat' ? 'No supported graphics card' : '' }));
    const el = await mount();
    expect(el.shadowRoot!.querySelector('.brain-sentence')!.textContent).not.toContain('and answer your questions with sources');
    expect(el.shadowRoot!.querySelector('.brain-sentence')!.textContent).toContain('answers unavailable on this computer');
    expect(el.shadowRoot!.querySelector('[data-feature="pictures"]')!.textContent).toContain('unavailable on this computer');
    expect(switches(el, 'Answer questions')).toHaveLength(0);
  });
  it('R3 live installed packages replace stale not-downloaded paths before the staged run ends', async () => {
    notInstalled(); preview.components = preview.components!.map(c => ({ ...c, state: 'to-download', downloadBytes: 1024 }));
    const el = await mount();
    emit({ installStatus: { state: 'running', phase: 'download', packages: [{ packageId: 'embedding', state: 'installed' }, { packageId: 'reranker', state: 'installed' }, { packageId: 'chat', state: 'downloading', bytesTotal: 1024, bytesDownloaded: 512 }] } });
    await el.updateComplete;
    expect(el.shadowRoot!.querySelector('[data-feature="meaning"]')!.textContent).not.toContain('Not downloaded');
    expect(el.shadowRoot!.querySelector('[data-feature="reranker"]')!.textContent).not.toContain('Not downloaded');
    expect(el.shadowRoot!.querySelector('[data-feature="chat"]')!.textContent).toContain('Downloading 50%');
  });
  it('R4 an unknown runtime observation stays unconfirmed in both sentence and path', async () => {
    emit({ runtimeStatus: { onnxFeatures: [{ id: 'embed', status: 'unknown', modelActive: false }, { id: 'reranker', status: 'unknown', modelActive: false }] } });
    const el = await mount();
    expect(el.shadowRoot!.querySelector('.brain-sentence')!.textContent).toContain('Understanding meaning is unconfirmed');
    expect(el.shadowRoot!.querySelector('.brain-sentence')!.textContent).toContain('best match first unconfirmed');
    expect(el.shadowRoot!.querySelector('[data-feature="meaning"]')!.textContent).toContain('Unknown');
    expect(el.shadowRoot!.querySelector('[data-feature="reranker"]')!.textContent).toContain('Unknown');
  });
  it('R5 a settings GET begun before a witnessed LLM save cannot revert the saved value', async () => {
    const el = await mount();
    let release!: (value: Response) => void;
    settingsGet = () => new Promise(resolve => { release = resolve; });
    window.dispatchEvent(new Event('focus')); await Promise.resolve();
    settingsGet = null;
    await el.patchLlm({ contextWindow: 8192 });
    expect(el.llm.contextWindow).toBe(8192);
    release(json({ ui: { chatEnabled }, llm: { contextWindow: 2048 } })); await settle(el);
    expect(el.llm.contextWindow).toBe(8192);
  });
  it('R6 the processor-fallback repair hint remains an accessible alert with its action', async () => {
    emit({ installStatus: { state: 'completed', installedFully: true, repairNeeded: true }, runtimeStatus: { onnxFeatures: [{ id: 'embed', modelActive: true, gpuFallback: true }] } });
    const el = await mount(); const alert = el.shadowRoot!.querySelector('[data-testid="brain-gpu-fallback-hint"]')!;
    expect(alert.getAttribute('role')).toBe('alert'); expect(alert.querySelector('jf-button')?.textContent).toContain('Repair');
  });
});
describe('Brain refresh lifetime', () => {
  it('a settings refresh resolves when focus moves into a sibling shadow root', async () => {
    const el = await mount();
    const sibling = document.createElement('div');
    const root = sibling.attachShadow({ mode: 'open' });
    root.innerHTML = '<button>Health</button>';
    document.body.append(sibling); root.querySelector('button')!.focus();
    await expect(el.refreshSettings()).resolves.toBeUndefined();
  });
  it.each(['resolve', 'reject'])('a pending settings read can %s after disconnect without rejecting or replacing values', async outcome => {
    const el = await mount();
    let release!: (value: Response) => void;
    let reject!: (error: Error) => void;
    settingsGet = () => new Promise((resolve, fail) => { release = resolve; reject = fail; });
    const refresh = el.refreshSettings();
    await vi.waitFor(() => expect(release).toBeTypeOf('function'));
    el.remove();
    if (outcome === 'resolve') release(json({ llm: { contextWindow: 512 } }));
    else reject(new Error('Request aborted after disconnect'));
    await expect(refresh).resolves.toBeUndefined();
    expect(el.llm.contextWindow).toBeUndefined();
  });
});
describe('Brain Advanced controls', () => {
  it('renders the four groups in place, keeps optional part choices, and never writes on open', async () => {
    const el = await mount(); await advanced(el);
    for (const label of ['Answer settings', 'Graphics card settings', 'Downloaded files', 'Maintenance']) expect(el.shadowRoot!.querySelector(`section[aria-label="${label}"]`)).not.toBeNull();
    for (const id of ['splade', 'ner', 'citation-scorer']) expect(el.shadowRoot!.querySelector(`[data-testid="component-toggle-${id}"]`)?.getAttribute('role')).toBe('switch');
    expect(el.shadowRoot!.querySelector('.accordion-header')).toBeNull();
    expect(calls).toEqual([]); expect(vi.mocked(fetch).mock.calls.filter(([, init]) => init?.method === 'POST')).toEqual([]);
  });
  it('sliders keep an unfocused draft across refresh, save once on change with witnesses, and preserve untouched values', async () => {
    const el = await mount(); await advanced(el);
    const slider = el.shadowRoot!.querySelector<HTMLInputElement>('#brain-contextWindow')!;
    slider.value = '8192'; slider.dispatchEvent(new Event('input'));
    slider.value = '12288'; slider.dispatchEvent(new Event('input'));
    await el.updateComplete;
    window.dispatchEvent(new Event('focus')); await settle(el);
    expect(slider.value).toBe('12288');
    expect(vi.mocked(fetch).mock.calls.filter(([,init]) => init?.method === 'POST')).toEqual([]);
    slider.dispatchEvent(new Event('change')); await settle(el);
    const posts = vi.mocked(fetch).mock.calls.filter(([,init]) => init?.method === 'POST');
    expect(posts).toHaveLength(1); const body = JSON.parse(String(posts[0]![1]!.body));
    expect(body.llm).toEqual({ contextWindow: 12288 }); expect(body.witness).toBeTruthy();
    expect(el.llm.contextWindow).toBe(12288);
  });
  it('Automatic stays distinct from processor-only, and changing one slider preserves the other five settings', async () => {
    llmValues = { gpuLayers: null, contextWindow: 0, maxTokens: null, modelPath: '/model', serverExecutable: null, llamaLibPath: '/libs' };
    const el = await mount(); await advanced(el);
    const gpu = el.shadowRoot!.querySelector<HTMLInputElement>('#brain-gpuLayers')!;
    expect(gpu.getAttribute('aria-valuetext')).toBe('Automatic');
    gpu.value = '0'; gpu.dispatchEvent(new Event('input')); gpu.dispatchEvent(new Event('change')); await settle(el);
    expect(el.llm.gpuLayers).toBe(0);
    expect(gpu.getAttribute('aria-valuetext')).toBe('0 layers · processor only');
    expect(el.llm.maxTokens).toBeNull(); expect(el.llm.contextWindow).toBe(0); expect(el.llm.modelPath).toBe('/model');
    await clickButton(el, 'Automatic graphics card use');
    expect(el.llm.gpuLayers).toBeNull();
    expect(gpu.getAttribute('aria-valuetext')).toBe('Automatic');
    expect(vi.mocked(fetch).mock.calls.filter(([,init]) => init?.method === 'POST').map(([,init]) => JSON.parse(String(init!.body)).llm)).toEqual([{ gpuLayers: 0 }, { gpuLayers: null }]);
  });
  it('a slider conflict shows review guidance, keeps the saved value and allows an explicit retry', async () => {
    llmValues = { gpuLayers: 8 }; settingsConflict = true;
    const el = await mount(); await advanced(el);
    const gpu = el.shadowRoot!.querySelector<HTMLInputElement>('#brain-gpuLayers')!;
    gpu.value = '0'; gpu.dispatchEvent(new Event('input')); gpu.dispatchEvent(new Event('change')); await settle(el);
    expect(el.llm.gpuLayers).toBe(8); expect(gpu.value).toBe('8');
    expect(el.shadowRoot!.textContent).toContain('Reload and review');
    settingsConflict = false;
    gpu.value = '0'; gpu.dispatchEvent(new Event('change')); await settle(el);
    expect(el.llm.gpuLayers).toBe(0);
  });
  it('a second window sees a committed slider value on focus, while a draft survives tab switching and refresh', async () => {
    llmValues = { contextWindow: 2048 };
    const first = await mount(); const second = await mount(); await advanced(first); await advanced(second);
    const slider = first.shadowRoot!.querySelector<HTMLInputElement>('#brain-contextWindow')!;
    slider.value = '8192'; slider.dispatchEvent(new Event('input'));
    first.shadowRoot!.querySelector('jf-surface-tabs')!.dispatchEvent(new CustomEvent('tab-change', { detail: { id: 'memory' } })); await first.updateComplete;
    window.dispatchEvent(new Event('focus')); await settle(first);
    first.shadowRoot!.querySelector('jf-surface-tabs')!.dispatchEvent(new CustomEvent('tab-change', { detail: { id: 'runtime' } })); await first.updateComplete;
    expect(slider.value).toBe('8192');
    slider.dispatchEvent(new Event('change')); await settle(first);
    window.dispatchEvent(new Event('focus')); await settle(second);
    expect(second.shadowRoot!.querySelector<HTMLInputElement>('#brain-contextWindow')!.value).toBe('8192');
  });
  it('an installed inactive variant can be activated from the real endpoint fields', async () => {
    emit({ runtimeStatus: { installedVariants: [{ variantId: 'cuda12', exePath: '/ai/cuda12' }], active: { activeVariantId: null } } });
    const el = await mount(); await advanced(el);
    [...el.shadowRoot!.querySelectorAll<HTMLButtonElement>('button')].find(b => b.textContent?.includes('Other options'))!.click(); await el.updateComplete;
    await clickButton(el, 'Activate');
    expect(calls).toEqual([{ id: 'core.activate-runtime-variant', args: { variantId: 'cuda12' } }]);
  });
});
