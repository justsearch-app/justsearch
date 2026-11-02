// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AiState, InstallStatus } from '../state/aiStateStore.js';
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
import { composeInstallConsent } from './BrainSurface.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import type { AiInstallManifest } from '../../api/domains/packs.js';

interface Brain extends HTMLElement { apiBase: string; host_: unknown; updateComplete: Promise<boolean> }
interface Button extends HTMLElement { updateComplete: Promise<boolean>; disabled: boolean; label: string }
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
const manifest: AiInstallManifest = { schemaVersion: 2, packages: ['embedding', 'reranker', 'chat', 'splade', 'ner', 'citation-scorer'].map(id => ({ id, label: id, license: 'Apache-2.0', termsUrl: 'https://example.invalid/terms', variants: [], supportingFiles: [] })) };
let preview: InstallPlanPreview;
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
  chatEnabled = true; settingsGet = null; previewGet = null; declineWait = null; operationWait = null; manifestFails = false; calls = []; declines = [];
  store.current = {
    aiEngine: { kind: 'online', stability: { kind: 'settled' }, installFailure: null }, installObservationLive: true, runtimeObservationLive: true, snapshotLive: true,
    runtime: { mode: 'online', modelId: 'answer-model', modelLabel: 'Answer model', installed: { known: true, value: true } },
    capabilities: { chat: true }, status: { gpu: { totalVramBytes: 8192, usedVramBytes: 4096 }, inference: { chatEnabledSpec: true }, operating: { gpu: { stale: false, adapter: 'Test adapter', adapterTotalBytes: 8192, adapterFreeBytes: 4096 } } },
    installStatus: { state: 'completed', phase: 'done', installedFully: true },
    runtimeStatus: { active: { mmprojActive: true }, onnxFeatures: [{ id: 'embed', modelActive: true }, { id: 'reranker', modelActive: true }] },
    index: { documentCount: { known: true, value: 24 } },
  } as unknown as AiState;
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path.endsWith('/api/settings/v2')) return settingsGet ? settingsGet() : json({ ui: { chatEnabled }, llm: {} });
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

describe('AI Brain redesign behaviour', () => {
  it('duplicates the saved answer switch with distinct ids and one busy state; stopping keeps files and background work', async () => {
    const el = await mount(); const controls = switches(el, 'Answer questions');
    expect(controls).toHaveLength(2); expect(new Set(controls.map(button => button.id)).size).toBe(2);
    expect(controls.map(button => button.getAttribute('aria-checked'))).toEqual(['true', 'true']);
    let release!: () => void; operationWait = new Promise(resolve => { release = resolve; });
    controls[0]!.click(); await el.updateComplete;
    expect(switches(el, 'Answer questions').map(button => button.getAttribute('aria-disabled'))).toEqual(['true', 'true']);
    controls[1]!.click(); await el.updateComplete;
    expect(calls).toEqual([{ id: 'core.set-chat-enabled', args: { enabled: false } }]);
    release(); await settle(el);
    expect(switches(el, 'Answer questions').map(button => button.getAttribute('aria-checked'))).toEqual(['false', 'false']);
    expect(el.shadowRoot!.textContent).toContain('Answers are off; background work can continue');
    expect(preview.components!.find(c => c.id === 'chat')?.state).toBe('installed');
    switches(el, 'Answer questions')[1]!.click(); await settle(el);
    expect(calls.at(-1)).toEqual({ id: 'core.set-chat-enabled', args: { enabled: true } });
  });
  it('shows the runtime projector observation, not downloaded files, including model change and offline unknown', async () => {
    const el = await mount();
    expect(el.shadowRoot!.querySelector('[data-feature="pictures"]')!.textContent).toContain('On');
    emit({ runtimeStatus: { active: { mmprojActive: false } } }); await el.updateComplete;
    expect(el.shadowRoot!.textContent).toContain("It doesn't read text in scans and images yet");
    emit({ snapshotLive: false }); await el.updateComplete;
    expect(el.shadowRoot!.querySelector('[data-feature="pictures"]')!.textContent).toContain('Unknown');
    expect(switches(el, 'Best match first')).toHaveLength(0);
    expect(switches(el, 'Text in scans and images')).toHaveLength(0);
  });
  it('preserves null answer intent as automatic rather than saving false', async () => {
    chatEnabled = null; const el = await mount();
    expect(el.shadowRoot!.querySelector('[data-feature="chat"]')!.textContent).toContain('automatic (no saved answer choice)');
    expect(switches(el, 'Answer questions')[0]!.getAttribute('aria-checked')).toBe('true');
    expect(calls).toEqual([]);
  });
  it('opens information without toggling, keeps one panel through updates, and restores focus on Escape and outside click', async () => {
    const el = await mount();
    const opener = el.shadowRoot!.querySelector<HTMLButtonElement>('button[aria-label="About Answer questions"]')!;
    opener.click(); await vi.waitFor(() => expect(el.shadowRoot!.activeElement?.className).toBe('feature-panel'));
    expect(calls).toEqual([]); expect(el.shadowRoot!.textContent).toContain('You get'); expect(el.shadowRoot!.textContent).toContain('It costs');
    emit({ runtimeStatus: { active: { mmprojActive: false } } }); await el.updateComplete;
    expect(el.shadowRoot!.querySelectorAll('.feature-panel')).toHaveLength(1);
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await vi.waitFor(() => expect(el.shadowRoot!.activeElement).toBe(opener));
    opener.click(); await el.updateComplete;
    document.body.dispatchEvent(new Event('pointerdown', { bubbles: true, composed: true })); await el.updateComplete;
    expect(el.shadowRoot!.querySelector('.feature-panel')).toBeNull();
  });
  it('blocks concurrent acquisition choices and converges another window on focus', async () => {
    emit({ installStatus: { state: 'idle', installedFully: false }, runtime: { mode: 'offline', modelId: null, installed: { known: true, value: false } } as AiState['runtime'], aiEngine: { kind: 'not_installed', stability: { kind: 'settled' }, installFailure: null } });
    preview.components = preview.components!.map(c => ({ ...c, state: 'to-download', downloadBytes: 1024 }));
    const first = await mount(); const second = await mount();
    let release!: () => void; declineWait = new Promise(resolve => { release = resolve; });
    switches(first, 'Best match first')[0]!.click(); await first.updateComplete;
    switches(first, 'Answer questions')[1]!.click(); await first.updateComplete;
    expect(declines).toHaveLength(1);
    expect(switches(first, 'Answer questions')[0]!.getAttribute('aria-describedby')).toBeTruthy();
    release(); await settle(first);
    window.dispatchEvent(new Event('focus')); await settle(second);
    expect(switches(second, 'Best match first').map(button => button.getAttribute('aria-checked'))).toEqual(['false', 'false']);
  });
  it('a retained surface cannot start a newer choice while its earlier request is still unanswered', async () => {
    emit({ installStatus: { state: 'idle', installedFully: false }, runtime: { mode: 'offline', modelId: null, installed: { known: true, value: false } } as AiState['runtime'], aiEngine: { kind: 'not_installed', stability: { kind: 'settled' }, installFailure: null } });
    preview.components = preview.components!.map(c => ({ ...c, state: 'to-download', downloadBytes: 1024 }));
    const el = await mount(); let release!: () => void;
    declineWait = new Promise(resolve => { release = resolve; });
    switches(el, 'Best match first')[0]!.click(); await el.updateComplete;
    el.remove(); document.body.appendChild(el); await settle(el);
    const second = switches(el, 'Answer questions')[0]!;
    expect(second.getAttribute('aria-disabled')).toBe('true');
    second.click(); expect(declines).toHaveLength(1);
    release(); await settle(el);
    expect(switches(el, 'Best match first')[0]!.getAttribute('aria-checked')).toBe('false');
    expect(switches(el, 'Answer questions')[0]!.getAttribute('aria-disabled')).toBe('false');
  });
  it('ignores late settings and plan responses from before a newer refresh', async () => {
    const el = await mount(); let settingsRelease!: (value: Response) => void; let previewRelease!: (value: Response) => void;
    settingsGet = () => new Promise(resolve => { settingsRelease = resolve; });
    previewGet = () => new Promise(resolve => { previewRelease = resolve; });
    window.dispatchEvent(new Event('focus')); await Promise.resolve();
    settingsGet = null; previewGet = null; chatEnabled = false;
    preview = { ...preview, components: preview.components!.map(c => c.id === 'reranker' ? { ...c, totalBytes: 8192 } : c) };
    window.dispatchEvent(new Event('focus')); await settle(el);
    settingsRelease(json({ ui: { chatEnabled: true }, llm: {} })); previewRelease(json({ components: [] })); await settle(el);
    expect(switches(el, 'Answer questions')[0]!.getAttribute('aria-checked')).toBe('false');
    expect(el.shadowRoot!.querySelector('[data-feature="reranker"]')!.textContent).toContain('8.0 KB');
  });
  it('a missing declined answer package asks for its real size and licence and downloads after consent', async () => {
    preview.components = preview.components!.map(c => c.id === 'chat' ? component('chat', 'declined', true) : c);
    emit({ capabilities: { chat: false } as AiState['capabilities'], runtime: { mode: 'offline', modelId: null, installed: { known: true, value: true } } as AiState['runtime'] });
    const el = await mount(); switches(el, 'Answer questions')[0]!.click(); await settle(el); await settle(el);
    expect(el.shadowRoot!.querySelector('dialog')!.textContent).toContain('1.0 KB');
    expect(el.shadowRoot!.querySelector('dialog')!.textContent).toContain('Apache-2.0');
    expect(calls).toEqual([]);
    expect(switches(el, 'Answer questions')[1]!.getAttribute('aria-disabled')).toBe('true');
    await clickButton(el, 'Accept and download');
    expect(calls.map(call => call.id)).toEqual(['core.start-ai-install', 'core.set-chat-enabled']);
  });
  it('accept is disabled until the manifest and every download size are known, with retry', async () => {
    preview.components = [component('embedding', 'to-download')]; preview.totalDownloadBytes = 1024;
    emit({ installStatus: { state: 'idle', installedFully: false }, runtime: { mode: 'offline', modelId: null, installed: { known: true, value: false } } as AiState['runtime'], aiEngine: { kind: 'not_installed', stability: { kind: 'settled' }, installFailure: null } });
    manifestFails = true; const el = await mount(); await clickButton(el, 'Install AI');
    let accept = el.shadowRoot!.querySelector<Button>('[label="Accept and download"]')!; await accept.updateComplete;
    expect(accept.disabled).toBe(true);
    await clickButton(el, 'Accept and download'); expect(calls).toEqual([]);
    await vi.waitFor(() => expect(el.shadowRoot!.querySelector('[label="Retry download preview"]')).not.toBeNull(), { timeout: 5000 });
    manifestFails = false; preview.components[0]!.downloadBytes = -1;
    await clickButton(el, 'Retry download preview'); await settle(el);
    accept = el.shadowRoot!.querySelector<Button>('[label="Accept and download"]')!; await accept.updateComplete;
    expect(accept.disabled).toBe(true);
    preview.components[0]!.downloadBytes = 1024;
    await clickButton(el, 'Retry download preview'); await settle(el); await accept.updateComplete;
    expect(accept.disabled).toBe(false);
  });
  it('retains progress across a tab switch and remount and blocks plan changes while paused', async () => {
    const running: InstallStatus = { state: 'running', paused: true, phase: 'download', totalBytes: 1024, downloadedBytes: 512 };
    emit({ installStatus: running, aiEngine: { kind: 'installing', stability: { kind: 'provisional', cause: 'installing' }, installFailure: null } });
    const el = await mount();
    expect(el.shadowRoot!.textContent).toContain('50%');
    expect(switches(el, 'Answer questions')[0]!.getAttribute('aria-disabled')).toBe('true');
    el.shadowRoot!.querySelector('jf-surface-tabs')!.dispatchEvent(new CustomEvent('tab-change', { detail: { id: 'memory' } })); await el.updateComplete;
    el.shadowRoot!.querySelector('jf-surface-tabs')!.dispatchEvent(new CustomEvent('tab-change', { detail: { id: 'runtime' } })); await el.updateComplete;
    expect(el.shadowRoot!.textContent).toContain('50%');
    el.remove(); const reloaded = await mount(); expect(reloaded.shadowRoot!.textContent).toContain('50%');
    emit({ installStatus: { state: 'completed', phase: 'done', installedFully: true }, aiEngine: { kind: 'online', stability: { kind: 'settled' }, installFailure: null } }); await settle(reloaded);
    expect(reloaded.shadowRoot!.querySelector('.brain-headline')!.textContent).toBe('AI is ready');
  });
  it('graphics memory uses all-app device data and stale or absent values remain unknown', async () => {
    const el = await mount(); const card = () => el.shadowRoot!.querySelector('.graphics-card')!;
    expect(card().textContent).toContain('4.0 KB of 8.0 KB in use · all apps · Test adapter');
    emit({ status: { operating: { gpu: { stale: true, adapterTotalBytes: 8192, adapterFreeBytes: 4096 } } } as AiState['status'] }); await el.updateComplete;
    expect(card().textContent).toContain('Memory use unknown'); expect(card().querySelector('[aria-valuenow]')).toBeNull();
  });
  it('consent lists only planned packages, excluding declined siblings and already installed files', () => {
    const content = composeInstallConsent(manifest, { totalDownloadBytes: 1024, components: [component('embedding'), component('chat', 'to-download'), component('reranker', 'declined', true), component('splade', 'unavailable')] });
    expect(content.packages.map(p => p.id)).toEqual(['chat']);
  });
});
