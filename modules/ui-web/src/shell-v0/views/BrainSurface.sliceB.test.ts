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

interface Brain extends HTMLElement { installConsentOpen: boolean; apiBase: string; host_: unknown; llm: {contextWindow?: number; gpuLayers?: number | null; maxTokens?: number | null; modelPath?: string | null}; patchLlm(updates: {contextWindow: number}): Promise<void>; refreshSettings(): Promise<void>; updateComplete: Promise<boolean> }
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
    runtimeStatus: { active: { mmprojActive: true }, onnxFeatures: [{ id: 'embed', status: 'active', modelActive: true }, { id: 'reranker', status: 'active', modelActive: true }] },
    index: { documentCount: { known: true, value: 24 } },
  } as unknown as AiState;
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path.endsWith('/api/ai/models/status')) return json(presence);
    if (path.endsWith('/api/settings/v2') && init?.method === 'POST') {
      const body = JSON.parse(String(init.body));
      if (settingsConflict) return json({ errorCode: 'VERSION_CONFLICT' }, 409);
      // SettingsPatch.merge only applies non-null members; null retains saved values.
      llmValues = { ...llmValues, ...Object.fromEntries(Object.entries(body.llm ?? {}).filter(([, value]) => value != null)) };
      return json({ state: 'COMPLETE', operationKey: body.operationKey, witness: { acceptedRevision: 1, lastCommittedOperationKey: body.operationKey }, llm: llmValues });
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
    expect([...el.shadowRoot!.querySelectorAll<Button>('jf-button')].map(b => b.label)).not.toContain('Automatic graphics card use');
    expect(vi.mocked(fetch).mock.calls.filter(([,init]) => init?.method === 'POST').map(([,init]) => JSON.parse(String(init!.body)).llm)).toEqual([{ gpuLayers: 0 }]);
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

// Each repair is observed through rendered controls and the endpoint contract.
describe('Brain step 1 review repairs', () => {
  it('S1 only reading memory can reset automatically and persists zero through backend merge', async () => {
    llmValues = { gpuLayers: 0, contextWindow: 8192, maxTokens: 2048 };
    const el = await mount(); await advanced(el);
    const labels = [...el.shadowRoot!.querySelectorAll<Button>('jf-button')].map(b => b.label);
    expect(labels).not.toContain('Automatic graphics card use');
    expect(labels).not.toContain('Automatic longest answer');
    await clickButton(el, 'Automatic reading memory');
    expect(llmValues).toEqual({ gpuLayers: 0, contextWindow: 0, maxTokens: 2048 });
    window.dispatchEvent(new Event('focus')); await settle(el);
    expect(el.shadowRoot!.querySelector('#brain-contextWindow')!.getAttribute('aria-valuetext')).toBe('Automatic');
    expect(vi.mocked(fetch).mock.calls.filter(([, init]) => init?.method === 'POST').map(([, init]) => JSON.parse(String(init!.body)).llm)).toEqual([{ contextWindow: 0 }]);
  });
  it('S1 reading memory Automatic sends zero rather than a null no-op', async () => {
    llmValues = { contextWindow: 8192 };
    const el = await mount(); await advanced(el);
    await clickButton(el, 'Automatic reading memory');
    expect(llmValues.contextWindow).toBe(0);
    expect(el.llm.contextWindow).toBe(0);
    await el.refreshSettings(); await settle(el);
    expect(el.shadowRoot!.querySelector('#brain-contextWindow')!.getAttribute('aria-valuetext')).toBe('Automatic');
  });
  it('S2 inactive loaded sessions are off and never promised, while unknown remains unknown', async () => {
    emit({ runtimeStatus: { onnxFeatures: [{ id: 'embed', status: 'active', modelActive: false }, { id: 'reranker', status: 'inactive', modelActive: true }] } });
    const el = await mount();
    expect(el.shadowRoot!.querySelector('[data-feature="reranker"]')!.textContent).toContain('Off');
    expect(el.shadowRoot!.querySelector('.brain-sentence')!.textContent).toContain('without best match first');
    expect(el.shadowRoot!.querySelector('[data-feature="meaning"]')!.textContent).toContain('Off');
    emit({ runtimeStatus: { onnxFeatures: [{ id: 'reranker', status: 'unknown', modelActive: true }] } }); await el.updateComplete;
    expect(el.shadowRoot!.querySelector('[data-feature="reranker"]')!.textContent).toContain('Unknown');
  });
  it.each(['pending', 'failed'])('S3 saved answer intent is unknown and blocked while initial settings are %s', async outcome => {
    let release!: (value: Response) => void;
    settingsGet = outcome === 'pending' ? () => new Promise(resolve => { release = resolve; }) : async () => json({}, 503);
    const el = await mount();
    const path = el.shadowRoot!.querySelector('[data-feature="chat"]')!;
    expect(path.textContent).toContain('Saved answer choice is not known');
    expect(path.textContent).not.toContain('automatic (no saved answer choice)');
    for (const toggle of switches(el, 'Answer questions')) {
      expect(toggle.getAttribute('aria-disabled')).toBe('true');
      expect(el.shadowRoot!.getElementById(toggle.getAttribute('aria-describedby')!)!.textContent).toContain('Saved answer choice is not known');
      toggle.click();
    }
    await settle(el); expect(calls).toEqual([]);
    if (outcome === 'pending') { release(json({ ui: { chatEnabled: null } })); await settle(el); }
    else { settingsGet = null; await el.refreshSettings(); await settle(el); }
    expect(switches(el, 'Answer questions')[0]!.getAttribute('aria-disabled')).toBe('false');
    if (outcome === 'pending') expect(path.textContent).toContain('automatic (no saved answer choice)');
  });
  it('S4 installed systems retain an additions Install action with selected size and exact consent', async () => {
    preview = { totalDownloadBytes: 1024, components: [component('embedding'), component('chat'), component('reranker', 'to-download'), component('splade', 'declined', true)] };
    emit({ installStatus: { state: 'completed', installedFully: true, pendingRegistryAdditions: ['reranker'] } });
    const el = await mount(); await advanced(el);
    expect(el.shadowRoot!.querySelector('[data-testid="install-pending-registry-additions"]')!.textContent).toContain('run Install');
    await clickButton(el, 'Install additions · 1.0 KB');
    expect(el.installConsentOpen).toBe(true);
    expect(el.shadowRoot!.querySelector('dialog')!.textContent).toContain('reranker');
    expect(el.shadowRoot!.querySelector('dialog')!.textContent).not.toContain('splade');
    expect(calls).toEqual([]);
    await clickButton(el, 'Accept and download');
    expect(calls).toContainEqual({ id: 'core.start-ai-install', args: { acceptTerms: true } });
  });
  it('S5 disconnect during picture reading uses last-observed wording until a live completion', async () => {
    emit({ status: { inference: { chatEnabledSpec: true, procedure: 'VDU_BATCH' } } as AiState['status'] });
    const el = await mount();
    expect(el.shadowRoot!.querySelector('.brain-sentence')!.textContent).toContain('while pictures are being read');
    emit({ snapshotLive: false }); await el.updateComplete;
    expect(el.shadowRoot!.querySelector('.brain-sentence')!.textContent).not.toContain('while pictures are being read');
    expect(el.shadowRoot!.querySelector('.brain-sentence')!.textContent).toContain('Pictures were being read when last observed');
    emit({ snapshotLive: true, status: { inference: { chatEnabledSpec: true, procedure: 'NONE' } } as AiState['status'] }); await el.updateComplete;
    expect(el.shadowRoot!.querySelector('.brain-sentence')!.textContent).toContain('answers your questions');
    expect(el.shadowRoot!.querySelector('.brain-sentence')!.textContent).not.toContain('last observed');
  });
  it('S7 every phrase, step and About opener shares expanded state and panel association', async () => {
    const el = await mount();
    const openers = [...el.shadowRoot!.querySelectorAll<HTMLButtonElement>('.feature-phrase, .step-info')];
    expect(openers.length).toBeGreaterThan(8);
    for (const opener of openers) expect(opener.getAttribute('aria-expanded')).toBe('false');
    const chat = openers.filter(b => b.getAttribute('aria-controls') === 'brain-feature-panel-chat');
    expect(chat).toHaveLength(4);
    for (const opener of chat) {
      opener.click(); await el.updateComplete;
      const panel = el.shadowRoot!.getElementById('brain-feature-panel-chat')!;
      expect(panel).not.toBeNull();
      for (const button of chat) expect(button.getAttribute('aria-expanded')).toBe('true');
      panel.querySelector<HTMLButtonElement>('button')!.click(); await el.updateComplete;
      for (const button of chat) expect(button.getAttribute('aria-expanded')).toBe('false');
    }
    expect(calls).toEqual([]);
  });
});
