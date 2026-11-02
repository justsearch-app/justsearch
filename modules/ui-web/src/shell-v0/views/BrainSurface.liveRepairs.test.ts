// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AiState } from '../state/aiStateStore.js';
import type { InstallPlanPreview } from '../utils/aiInstallPoll.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
const store = vi.hoisted(() => ({ listeners: new Set<(state: AiState) => void>(), current: {} as AiState }));
vi.mock('../state/aiStateStore.js', async original => ({
  ...await original<typeof import('../state/aiStateStore.js')>(),
  subscribeAiState: (listener: (state: AiState) => void) => {
    store.listeners.add(listener); listener(store.current);
    return () => store.listeners.delete(listener);
  },
}));
import './BrainSurface.js';
interface Brain extends HTMLElement { apiBase: string; host_: unknown; updateComplete: Promise<boolean>; llm: Record<string, number | null>; runtimeError: string | null }
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
let preview: InstallPlanPreview;
let previewGet: (() => Promise<Response>) | null;
let saveWait: Promise<void> | null;
let saveConflict: boolean;
let chatEnabled: boolean;
let llm: Record<string, number | null>;
let posts: Array<Record<string, number>>;
let operations: string[];
let declines: string[];
let witness: { acceptedRevision: number; lastCommittedOperationKey: string | null };
function emit(patch: Partial<AiState>) {
  store.current = { ...store.current, ...patch };
  for (const listener of store.listeners) listener(store.current);
}
async function settle(el: Brain) { await new Promise(resolve => setTimeout(resolve, 0)); await el.updateComplete; }
async function mount() {
  const el = document.createElement('jf-brain-surface') as Brain;
  el.apiBase = '';
  el.host_ = createMockHostApi({ data: { invokeOperation: async (id: string, args: unknown) => {
    operations.push(id);
    if (id === 'core.set-chat-enabled') chatEnabled = (args as { enabled: boolean }).enabled;
    return { success: true, structuredData: null };
  } } });
  document.body.append(el); await settle(el); return el;
}
function switches(el: Brain) { return [...el.shadowRoot!.querySelectorAll<HTMLButtonElement>('[role="switch"][aria-label="Use Answer questions"]')]; }
async function advanced(el: Brain) { el.shadowRoot!.querySelector<HTMLButtonElement>('.brain-links button')!.click(); await el.updateComplete; }
function change(slider: HTMLInputElement, value: number) {
  slider.value = String(value); slider.dispatchEvent(new Event('input')); slider.dispatchEvent(new Event('change'));
}
beforeEach(() => {
  preview = { totalDownloadBytes: 1024, components: ['embedding', 'reranker', 'chat'].map(id => ({ id, label: id, state: 'installed', declinable: id !== 'embedding', totalBytes: 1024, downloadBytes: 0 })) };
  previewGet = null; saveWait = null; saveConflict = false; chatEnabled = true; llm = {}; posts = []; operations = []; declines = []; witness = { acceptedRevision: 0, lastCommittedOperationKey: null };
  store.current = {
    installObservationLive: true, runtimeObservationLive: true, snapshotLive: true, aiEngine: { kind: 'online', stability: { kind: 'settled' }, installFailure: null },
    runtime: { mode: 'online', installed: { known: true, value: true }, modelId: 'answer-model' },
    installStatus: { state: 'completed', installedFully: true },
    runtimeStatus: { installedVariants: [{ variantId: 'cuda' }], active: { activeVariantId: 'cuda', modelPath: '/answer', mmprojActive: true }, onnxFeatures: [{ id: 'embed', status: 'active', modelActive: true }, { id: 'reranker', status: 'active', modelActive: true }] },
    capabilities: { chat: true }, status: { inference: { chatEnabledSpec: true } },
  } as unknown as AiState;
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path.endsWith('/api/settings/v2') && init?.method === 'POST') {
      const body = JSON.parse(String(init.body)); posts.push(body.llm); await saveWait;
      if (saveConflict) return json({ errorCode: 'VERSION_CONFLICT' }, 409);
      llm = { ...llm, ...body.llm };
      witness = { acceptedRevision: witness.acceptedRevision + 1, lastCommittedOperationKey: body.operationKey };
      return json({ state: 'COMPLETE', operationKey: body.operationKey, witness, llm });
    }
    if (path.endsWith('/api/settings/v2')) return json({ ui: { chatEnabled }, llm, witness });
    if (path.endsWith('/plan-preview')) return previewGet ? previewGet() : json(preview);
    if (path.endsWith('/manifest')) return json({ schemaVersion: 2, packages: [] });
    if (path.includes('/decline')) declines.push(path);
    return json({});
  }));
});
afterEach(() => { document.body.innerHTML = ''; store.listeners.clear(); vi.unstubAllGlobals(); vi.useRealTimers(); });

describe('Brain live redesign repairs', () => {
  it.each(['no observation', 'retained negative', 'unconfirmed verdict'] as const)('D2 the whole page waits for live install and verdict: %s', async state => {
    emit(state === 'no observation' ? { installStatus: null, aiEngine: { kind: 'online', stability: { kind: 'settled' }, installFailure: null } }
      : state === 'retained negative' ? { snapshotLive: false, installStatus: { state: 'idle', installedFully: false }, aiEngine: { kind: 'not_installed', stability: { kind: 'settled' }, installFailure: null }, runtime: { installed: { known: false } } as AiState['runtime'] }
      : { aiEngine: { kind: 'connecting', stability: { kind: 'provisional', cause: 'checking' }, installFailure: null } });
    const el = await mount(); const root = el.shadowRoot!;
    expect(root.querySelector('.brain-headline')!.textContent).toContain('Checking AI');
    expect(root.querySelector('.brain-sentence')!.textContent).not.toMatch(/Installing AI|Answers are off|understands what/);
    expect([...root.querySelectorAll('.brain-path .path-step')].map(step => step.textContent)).toEqual(expect.arrayContaining([expect.stringContaining('Unknown')]));
    for (const step of root.querySelectorAll('.brain-path .path-step')) expect(step.textContent).toContain('Unknown');
    expect(root.querySelectorAll('[role="switch"]').length).toBeGreaterThan(0);
    expect([...root.querySelectorAll<HTMLButtonElement>('[role="switch"]')].every(button => button.getAttribute('aria-disabled') === 'true')).toBe(true);
    expect(root.querySelector('[label^="Install AI"], [label^="Install additions"]')).toBeNull();
    expect(root.querySelector('.status-row')).toBeNull();
    await advanced(el);
    for (const button of root.querySelectorAll('[label="Restart AI"], [label="Repair installation"]')) {
      expect((button as unknown as { availability: { kind: string; reason: string } }).availability.kind).toBe('unavailable');
      expect((button as unknown as { availability: { reason: string } }).availability.reason).toBeTruthy();
    }
  });
  it('D6 repair history is installed and explicit answers-off stays off with Repair as primary', async () => {
    chatEnabled = false;
    emit({ installStatus: { state: 'idle', installedFully: false, repairNeeded: true }, capabilities: { chat: false } as AiState['capabilities'], runtime: { mode: 'offline', installed: { known: true, value: false } } as AiState['runtime'], aiEngine: { kind: 'not_installed', stability: { kind: 'settled' }, installFailure: null } });
    const el = await mount();
    expect(el.shadowRoot!.querySelector('.brain-headline')!.textContent).toBe('Installed — repair available');
    expect(el.shadowRoot!.querySelector('.brain-sentence')!.textContent).toContain('Answers are off; background work can continue');
    expect(switches(el).map(b => b.getAttribute('aria-checked'))).toEqual(['false', 'false']);
    expect(el.shadowRoot!.querySelector('[role="alert"] [label="Repair"]')!.getAttribute('variant')).toBe('primary');
    expect(el.shadowRoot!.querySelector('[label^="Install AI"]')).toBeNull();
  });
  it('D8 a pending idle preview does not block the real answer buttons and cannot overwrite a newer choice', async () => {
    const el = await mount(); let release!: (response: Response) => void;
    previewGet = () => new Promise(resolve => { release = resolve; });
    window.dispatchEvent(new Event('focus')); await settle(el);
    expect(switches(el).map(b => b.getAttribute('aria-disabled'))).toEqual(['false', 'false']);
    switches(el)[0]!.click(); await settle(el);
    expect(operations).toEqual(['core.set-chat-enabled']);
    release(json({ components: [] })); await settle(el);
    expect(switches(el).map(b => b.getAttribute('aria-checked'))).toEqual(['false', 'false']);
    expect(el.shadowRoot!.querySelector('[data-feature="chat"]')!.textContent).toContain('1.0 KB');
  });
  it('D8 a preview begun before an acquisition save cannot overwrite its new choices', async () => {
    emit({ installStatus: { state: 'idle', installedFully: false }, runtime: { mode: 'offline', installed: { known: true, value: false } } as AiState['runtime'], aiEngine: { kind: 'not_installed', stability: { kind: 'settled' }, installFailure: null } });
    preview.components = preview.components!.map(c => ({ ...c, state: 'to-download', downloadBytes: 1024 }));
    const el = await mount(); let release!: (response: Response) => void;
    previewGet = () => new Promise(resolve => { release = resolve; });
    window.dispatchEvent(new Event('focus')); await settle(el);
    previewGet = null;
    el.shadowRoot!.querySelector<HTMLButtonElement>('[role="switch"][aria-label="Use Best match first"]')!.click(); await settle(el);
    expect(declines).toHaveLength(1);
    release(json({ totalDownloadBytes: 999999, components: [] })); await settle(el);
    expect(el.shadowRoot!.querySelector('[data-feature="reranker"]')!.textContent).toContain('1.0 KB');
  });
  it('D10 Automatic GPU layers starts at maximum so the left endpoint can save processor-only zero', async () => {
    const el = await mount(); await advanced(el);
    const slider = el.shadowRoot!.querySelector<HTMLInputElement>('#brain-gpuLayers')!;
    expect(slider.getAttribute('aria-valuetext')).toBe('Automatic');
    expect(slider.value).toBe(slider.max);
    change(slider, 0); await settle(el);
    expect(posts).toEqual([{ gpuLayers: 0 }]); expect(slider.getAttribute('aria-valuetext')).toBe('0 layers · processor only');
  });
  it('D10 sliders retain focus and serialize the latest changed value during a pending witnessed save', async () => {
    llm = { gpuLayers: 4, maxTokens: 200 }; const el = await mount(); await advanced(el);
    const slider = el.shadowRoot!.querySelector<HTMLInputElement>('#brain-gpuLayers')!;
    let release!: () => void; saveWait = new Promise(resolve => { release = resolve; });
    slider.focus(); change(slider, 5); await settle(el);
    expect(slider.disabled).toBe(false); expect(el.shadowRoot!.activeElement).toBe(slider);
    change(slider, 6); change(slider, 7); await settle(el);
    expect(posts).toEqual([{ gpuLayers: 5 }]); expect(slider.value).toBe('7');
    saveWait = null; release(); await vi.waitFor(() => expect(posts).toEqual([{ gpuLayers: 5 }, { gpuLayers: 7 }])); await settle(el);
    expect(el.llm).toEqual({ gpuLayers: 7, maxTokens: 200 }); expect(slider.value).toBe('7'); expect(el.shadowRoot!.activeElement).toBe(slider);
  });
  it('D10 a conflict stops queued slider saves and asks for review without changing the saved value', async () => {
    llm = { gpuLayers: 4 }; saveConflict = true;
    const el = await mount(); await advanced(el);
    const slider = el.shadowRoot!.querySelector<HTMLInputElement>('#brain-gpuLayers')!;
    let release!: () => void; saveWait = new Promise(resolve => { release = resolve; });
    change(slider, 5); await settle(el); change(slider, 6); await settle(el);
    expect(posts).toEqual([{ gpuLayers: 5 }]);
    saveWait = null; release(); await vi.waitFor(() => expect(el.runtimeError).toContain('Reload and review'));
    expect(posts).toEqual([{ gpuLayers: 5 }]); expect(el.llm.gpuLayers).toBe(4);
    expect(slider.value).toBe('6');
    saveConflict = false; slider.dispatchEvent(new Event('change')); await settle(el);
    expect(posts).toEqual([{ gpuLayers: 5 }, { gpuLayers: 6 }]); expect(el.llm.gpuLayers).toBe(6);
  });
  it('G5 Linux memory uses device zero status GPU figures and labels one of several devices', async () => {
    emit({ status: { gpu: { totalVramBytes: 12 * 1024 ** 3, usedVramBytes: 9 * 1024 ** 3, deviceCount: 2 } } as AiState['status'] });
    const el = await mount(); const card = () => el.shadowRoot!.querySelector('.graphics-card')!;
    expect(card().textContent).toContain('9.00 GB of 12.00 GB in use · all apps · one of 2 graphics devices');
    expect(card().querySelector('[role="meter"]')?.getAttribute('aria-valuenow')).toBe(String(9 * 1024 ** 3));
    emit({ snapshotLive: false }); await el.updateComplete;
    expect(card().textContent).toContain('Memory use unknown'); expect(card().querySelector('[aria-valuenow]')).toBeNull();
    emit({ installObservationLive: true, runtimeObservationLive: true, snapshotLive: true, status: { gpu: { totalVramBytes: 12 * 1024 ** 3 } } as AiState['status'] }); await el.updateComplete;
    expect(card().textContent).toContain('Memory use unknown');
  });
  it('D1 sentence boundaries keep whitespace outside phrase buttons and punctuation attached to preceding prose', async () => {
    const el = await mount(); const sentence = el.shadowRoot!.querySelector('.brain-sentence')!;
    expect(sentence.textContent).toContain('puts the best match first and answers');
    expect(sentence.textContent).not.toContain('firstand');
    for (const button of sentence.querySelectorAll('.feature-phrase:not([aria-label])')) expect(button.textContent).not.toMatch(/^\s|^[.,]/);
    expect([...sentence.childNodes].some(node => node.nodeType === Node.TEXT_NODE && node.textContent?.startsWith('. '))).toBe(false);
  });
  it('D3 a pointer gesture on another step opens it in one click without restoring the previous opener or scrolling focus', async () => {
    const el = await mount(); const root = el.shadowRoot!;
    const first = root.querySelector<HTMLButtonElement>('[data-feature="chat"] .step-info')!;
    const next = root.querySelector<HTMLButtonElement>('[data-feature="reranker"] .step-info')!;
    first.click(); await el.updateComplete;
    const focus = vi.spyOn(HTMLElement.prototype, 'focus');
    next.dispatchEvent(new Event('pointerdown', { bubbles: true, composed: true })); await el.updateComplete;
    expect(root.querySelector('.feature-panel')?.getAttribute('aria-label')).toBe('About Answer questions');
    expect(focus).not.toHaveBeenCalled();
    next.click(); await el.updateComplete;
    expect(root.querySelectorAll('.feature-panel')).toHaveLength(1);
    expect(root.querySelector('.feature-panel')?.getAttribute('aria-label')).toBe('About Best match first');
    expect(focus).toHaveBeenLastCalledWith({ preventScroll: true }); focus.mockRestore();
  });
  it('D4 cancelled steps show retained bytes even when the last package record still says downloading', async () => {
    emit({ installStatus: { state: 'cancelled', installedFully: false, resumableBytes: 850, packages: [{ packageId: 'embedding', state: 'downloading', bytesDownloaded: 850, bytesTotal: 1024 }] }, aiEngine: { kind: 'paused', stability: { kind: 'settled' }, installFailure: null }, runtime: { mode: 'offline', installed: { known: true, value: false } } as AiState['runtime'], runtimeStatus: {} });
    preview.components = preview.components!.map(c => ({ ...c, state: 'to-download' }));
    const el = await mount();
    expect(el.shadowRoot!.querySelector('[data-feature="meaning"]')!.textContent).toContain('Cancelled · 850 B kept');
    expect(el.shadowRoot!.querySelector('[data-feature="meaning"]')!.textContent).not.toContain('Downloading 83%');
  });
  it('D4 after a cancelled run, steps whose files are present or that never downloaded are not labelled cancelled', async () => {
    emit({ installStatus: { state: 'cancelled', installedFully: false, resumableBytes: 850, packages: [{ packageId: 'embedding', state: 'downloading', bytesDownloaded: 850, bytesTotal: 1024 }, { packageId: 'reranker', state: 'pending', bytesDownloaded: 0 }, { packageId: 'chat', state: 'skipped' }] }, aiEngine: { kind: 'paused', stability: { kind: 'settled' }, installFailure: null }, runtime: { mode: 'offline', installed: { known: true, value: false } } as AiState['runtime'], runtimeStatus: {} });
    preview.components = preview.components!.map(c => c.id === 'embedding' ? { ...c, state: 'to-download' } : c);
    const el = await mount();
    expect(el.shadowRoot!.querySelector('[data-feature="meaning"]')!.textContent).toContain('Cancelled · 850 B kept');
    for (const feature of ['reranker', 'chat']) expect(el.shadowRoot!.querySelector(`[data-feature="${feature}"]`)!.textContent).not.toContain('Cancelled');
  });
  it('D4 a cancelled run never leaves a step saying Downloading: zero-byte interruptions are not downloaded, present files keep their observed state', async () => {
    emit({ installStatus: { state: 'cancelled', installedFully: false, resumableBytes: 0, packages: [{ packageId: 'embedding', state: 'downloading', bytesDownloaded: 0, bytesTotal: 1024 }, { packageId: 'reranker', state: 'downloading', bytesDownloaded: 512, bytesTotal: 1024 }] }, aiEngine: { kind: 'paused', stability: { kind: 'settled' }, installFailure: null }, runtime: { mode: 'offline', installed: { known: true, value: false } } as AiState['runtime'], runtimeStatus: { onnxFeatures: [{ id: 'reranker', status: 'active', modelActive: true }] } });
    preview.components = preview.components!.map(c => c.id === 'embedding' ? { ...c, state: 'to-download' } : c);
    const el = await mount();
    const meaning = el.shadowRoot!.querySelector('[data-feature="meaning"]')!.textContent!;
    expect(meaning).not.toContain('Downloading'); expect(meaning).toContain('Not downloaded');
    const reranker = el.shadowRoot!.querySelector('[data-feature="reranker"]')!.textContent!;
    expect(reranker).not.toContain('Downloading'); expect(reranker).not.toContain('Cancelled'); expect(reranker).toContain('On');
  });
  it('B11 the repair remedy appears once, in the repair alert, not repeated by the status line', async () => {
    emit({ installStatus: { state: 'completed', installedFully: true, repairNeeded: true }, aiEngine: { kind: 'offline', stability: { kind: 'settled' }, installFailure: null } });
    const el = await mount();
    expect(el.shadowRoot!.textContent!.split('A required component is missing').length - 1).toBe(1);
    expect(el.shadowRoot!.querySelector('.brain-status-detail')!.textContent).not.toContain('A required component is missing');
  });
  it('D7 the picture branch participates in path layout instead of escaping into preceding alerts', async () => {
    const el = await mount(); const path = el.shadowRoot!.querySelector('.brain-path')!;
    expect(path.querySelector(':scope > .picture-branch')).not.toBeNull();
    expect(path.querySelector('.read-step .picture-branch')).toBeNull();
  });
  it('D9 repair instructions name the visible page locations', async () => {
    emit({ installStatus: { state: 'completed', installedFully: true, repairNeeded: true }, aiEngine: { kind: 'offline', stability: { kind: 'settled' }, installFailure: null } });
    const el = await mount(); expect(el.shadowRoot!.textContent).toContain('use Repair below');
    expect(el.shadowRoot!.textContent).not.toContain('in Detailed');
    emit({ installStatus: { state: 'completed', installedFully: true, repairNeeded: true, packages: [{ packageId: 'embedding', state: 'failed', terminalReason: 'TRANSPORT_UNAVAILABLE', attempts: 3, url: 'https://example.invalid/model', targetPath: '/model' }] } }); await el.updateComplete;
    expect(el.shadowRoot!.textContent).toContain('use the direct links below');
  });
});


describe('Brain review repairs', () => {
  it.each(['install', 'runtime'] as const)('G1 failed %s reads override a reachable origin and settled verdict', async endpoint => {
    const el = await mount();
    emit(endpoint === 'install' ? { installObservationLive: false } : { runtimeObservationLive: false });
    await el.updateComplete;
    expect(store.current.snapshotLive).toBe(true);
    expect(el.shadowRoot!.querySelector('.brain-headline')!.textContent).toContain('Checking AI');
    expect(switches(el).every(b => b.getAttribute('aria-disabled') === 'true')).toBe(true);
    await advanced(el);
    expect(el.shadowRoot!.querySelector('[data-testid="brain-observed-features"]')!.textContent).toContain('when last observed');
    expect(el.shadowRoot!.querySelector('[data-testid="component-row-chat"]')!.textContent).not.toContain('in use');
  });
  it.each(['connecting', 'missing install'] as const)('G2 Advanced shares the page gate for %s observations', async state => {
    emit(state === 'connecting' ? { aiEngine: { kind: 'connecting', stability: { kind: 'provisional', cause: 'checking' }, installFailure: null } } : { installStatus: null });
    const el = await mount(); await advanced(el); const root = el.shadowRoot!;
    expect(root.querySelector('[data-testid="brain-observed-features"]')!.textContent).toContain('when last observed');
    expect(root.querySelector('[data-testid="component-row-chat"]')!.textContent).toContain('last observed');
    expect(root.querySelector('[data-testid="component-row-chat"]')!.textContent).not.toContain('in use');
    expect(root.querySelector('[aria-label="Graphics card settings"]')!.textContent).toContain('cuda · active when last observed');
  });
  it('G3 selected missing answer files use saved intent while Repair owns acquisition', async () => {
    emit({ installStatus: { state: 'idle', installedFully: false, repairNeeded: true }, capabilities: { chat: false } as AiState['capabilities'], runtime: { mode: 'offline', installed: { known: true, value: false } } as AiState['runtime'], runtimeStatus: {}, aiEngine: { kind: 'offline', stability: { kind: 'settled' }, installFailure: null } });
    preview.components = preview.components!.map(c => c.id === 'chat' ? { ...c, state: 'to-download', downloadBytes: 1024 } : c);
    const el = await mount();
    expect(switches(el).map(b => b.getAttribute('aria-checked'))).toEqual(['true', 'true']);
    switches(el)[0]!.click(); await settle(el);
    expect(operations).toEqual(['core.set-chat-enabled']); expect(declines).toEqual([]);
    expect(switches(el).map(b => b.getAttribute('aria-checked'))).toEqual(['false', 'false']);
    switches(el)[1]!.click(); await settle(el);
    expect(operations).toEqual(['core.set-chat-enabled', 'core.set-chat-enabled']); expect(declines).toEqual([]);
    expect(switches(el).map(b => b.getAttribute('aria-checked'))).toEqual(['true', 'true']);
    expect(el.shadowRoot!.querySelector('[role="alert"] [label="Repair"]')).not.toBeNull();
    expect(el.shadowRoot!.querySelector<HTMLDialogElement>('dialog')!.open).toBe(false);
  });
  it('G4 passive previews complete before another batch starts and converge other windows without locking choices', async () => {
    vi.useFakeTimers(); const mounting = mount(); await vi.advanceTimersByTimeAsync(0); const el = await mounting;
    let release!: (response: Response) => void;
    const slow = vi.fn(() => new Promise<Response>(resolve => { release = resolve; })); previewGet = slow;
    await vi.advanceTimersByTimeAsync(5000);
    expect(slow).toHaveBeenCalledTimes(1);
    expect(switches(el).every(b => b.getAttribute('aria-disabled') === 'false')).toBe(true);
    await vi.advanceTimersByTimeAsync(15000);
    expect(slow).toHaveBeenCalledTimes(1);
    previewGet = null;
    release(json({ ...preview, components: preview.components!.map(c => ({ ...c, totalBytes: 2048 })) }));
    await vi.advanceTimersByTimeAsync(0); await el.updateComplete;
    expect(el.shadowRoot!.querySelector('[data-feature="reranker"]')!.textContent).toContain('2.0 KB');
    await vi.advanceTimersByTimeAsync(4999); expect(slow).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1); await el.updateComplete;
    expect(el.shadowRoot!.querySelector('[data-feature="reranker"]')!.textContent).toContain('1.0 KB');
    el.remove(); await vi.advanceTimersByTimeAsync(20000); expect(slow).toHaveBeenCalledTimes(1);
  });
});
