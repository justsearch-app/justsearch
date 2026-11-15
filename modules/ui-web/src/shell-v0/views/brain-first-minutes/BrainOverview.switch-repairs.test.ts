// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// Slice 7a repairs R1, R3 and R5 (2026-11-15, real-app test of 2f670abba7): each switch shows the
// Engine's effective state for this computer, and a step's words never contradict what the Engine reports.
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
type Witness = { acceptedRevision: number; lastCommittedOperationKey: string | null };
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
let saved: { rerankerEnabled: boolean | null; pictureReadingEnabled: boolean | null; pictureReadingOverrideEnabled?: boolean | null; chatEnabled: boolean | null };
let witness: Witness;
let posts: Array<Record<string, unknown>>;
let operations: Array<{ id: string; args: unknown }>;
let settingsFails: boolean;
let settingsGets: number;
let heldPost: Promise<void> | null;
let invalidReceipt: boolean;
let heldGets: Array<(r: Response) => void> | null;
let answerFiles: boolean;
let presence: unknown;
let planned: InstallPlanPreview | null;
const preview: InstallPlanPreview = { totalDownloadBytes: 0, components: ['embedding', 'reranker', 'chat'].map(id => ({
  id, label: id, state: 'installed', declinable: id !== 'embedding', totalBytes: 1024 ** 3, downloadBytes: 0 })) };
function ready(reranker = true): AiState {
  return { phase: 'connected', installObservationLive: true, runtimeObservationLive: true, snapshotLive: true,
    aiEngine: { kind: 'online', stability: { kind: 'settled' }, installFailure: null },
    runtime: { mode: 'online', modelLabel: 'Answer model', installed: { known: true, value: true }, gpu: { available: true, description: 'CUDA' } },
    capabilities: { chat: true, rag: true, extract: true, embedding: true },
    installStatus: { state: 'completed', installedFully: true },
    runtimeStatus: { active: { mmprojActive: true }, onnxFeatures: [{ id: 'embed', status: 'active', modelActive: true },
      { id: 'reranker', status: reranker ? 'active' : 'inactive', modelActive: reranker }] },
    status: { inference: { chatEnabledSpec: true } }, index: { ownDocumentCount: { known: true, value: 8 } } } as unknown as AiState;
}
async function settle(el: Brain) {
  for (let i = 0; i < 5; ++i) { await new Promise(resolve => setTimeout(resolve, 0)); await el.updateComplete; }
}
async function mount() {
  const el = document.createElement('jf-brain-surface') as Brain;
  el.apiBase = '';
  el.host_ = createMockHostApi({ data: { invokeOperation: async (id: string, args: unknown) => {
    operations.push({ id, args }); return { success: true, structuredData: null };
  } } });
  document.body.append(el); await settle(el); return el;
}
const words = (node: Element | ShadowRoot | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
// Feature steps are found as the page has always drawn them, by data-feature.
const step = (el: Brain, id: string) => el.shadowRoot!.querySelector<HTMLElement>(`.path-step[data-feature="${id}"]`);
// Lit is drawn as data-lit on the step (slice 7a) or data-active on its symbol (before it).
const litOf = (node: HTMLElement | null) => node?.dataset.lit ?? node?.querySelector<HTMLElement>('.step-symbol')?.dataset.active ?? 'missing';
// Switches are found by role and accessible name under their step, as the base page draws them too.
function stepSwitch(el: Brain, id: string, label: string) {
  return step(el, id)?.querySelector<HTMLButtonElement>(`[role="switch"][aria-label="Use ${label}"]`) ?? null;
}

beforeEach(() => {
  setShowDeveloperTools(false);
  saved = { rerankerEnabled: true, pictureReadingEnabled: true, chatEnabled: true };
  witness = { acceptedRevision: 0, lastCommittedOperationKey: null };
  posts = []; operations = []; answerFiles = true; presence = null; planned = null; settingsFails = false; settingsGets = 0; heldPost = null; invalidReceipt = false; heldGets = null;
  store.current = ready();
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path.endsWith('/api/settings/v2') && init?.method === 'POST') {
      const body = JSON.parse(String(init.body)) as Record<string, unknown>; posts.push(body); await heldPost;
      if ('rerankerEnabled' in body) saved.rerankerEnabled = body.rerankerEnabled as boolean;
      if ('pictureReadingEnabled' in body) saved.pictureReadingEnabled = body.pictureReadingEnabled as boolean;
      witness = { acceptedRevision: witness.acceptedRevision + 1, lastCommittedOperationKey: String(body.operationKey) };
      return json({ state: 'COMPLETE', operationKey: invalidReceipt ? 'unidentified' : body.operationKey, witness,
        rerankerEnabled: saved.rerankerEnabled, pictureReadingEnabled: saved.pictureReadingEnabled });
    }
    if (path.endsWith('/api/settings/v2')) {
      ++settingsGets;
      if (settingsFails) return json({ error: 'unavailable' }, 503);
      const body = json({ ui: { chatEnabled: saved.chatEnabled }, llm: {}, rerankerEnabled: saved.rerankerEnabled,
        pictureReadingEnabled: saved.pictureReadingEnabled, pictureReadingOverrideEnabled: saved.pictureReadingOverrideEnabled ?? null, witness });
      if (heldGets) return new Promise<Response>(resolve => heldGets!.push(() => resolve(body)));
      return body;
    }
    if (path.endsWith('/plan-preview')) return json(planned ?? preview);
    if (path.endsWith('/manifest')) return json({ schemaVersion: 2, packages: [] });
    if (path.endsWith('/api/ai/models/status')) return json(presence ?? { packages: ['embedding', 'reranker', 'chat'].map(id => ({ id, complete: id !== 'chat' || answerFiles })) });
    if (path.endsWith('/api/ai/install/downloaded-files')) return json({ parts: [] });
    return json({});
  }));
});
afterEach(() => { document.body.innerHTML = ''; store.listeners.clear(); vi.unstubAllGlobals(); });


describe('repairs R1, R3, R5: switches follow the Engine and the running model', () => {
  // R1 — a fresh install with nothing saved on a computer without a usable card: the Engine keeps Best
  // match first off on the processor (its observation: inactive, reason cpu_default_off, session
  // loaded). The unset switch shows that effective state, and a click saves the opposite of it.
  it('an unset Best match first that the Engine keeps off on the processor reads Off, and a click saves On', async () => {
    saved.rerankerEnabled = null;
    store.current = { ...ready(), runtime: { mode: 'online', modelLabel: 'Answer model', installed: { known: true, value: true }, gpu: { available: false, description: 'CPU' } },
      runtimeStatus: { active: { mmprojActive: true }, onnxFeatures: [
        { id: 'embed', label: 'Semantic embedding', status: 'active', reason: 'auto_discovered', modelPath: '/models/embedding/model.onnx', modelActive: true, executionProvider: 'cpu', gpuFallback: false, fallbackReason: null },
        { id: 'reranker', label: 'Search reranking', status: 'inactive', reason: 'cpu_default_off', modelPath: '/models/reranker/model.onnx', modelActive: true, executionProvider: 'cpu', gpuFallback: false, fallbackReason: null }] } } as unknown as AiState;
    const el = await mount();
    const toggle = stepSwitch(el, 'reranker', 'Best match first');
    expect(toggle, 'Best match first switch under its step').not.toBeNull();
    expect(toggle!.getAttribute('aria-checked'), 'drawn as the Engine keeps it: off').toBe('false');
    expect(words(step(el, 'reranker')), 'its state in words').toMatch(/\bOff\b/);
    expect(words(step(el, 'reranker'))).not.toMatch(/\bOn\b/);
    expect(words(step(el, 'reranker'))).not.toMatch(/not working/i);
    expect(litOf(step(el, 'reranker'))).toBe('false');
    expect(posts).toEqual([]);
    toggle!.click(); await settle(el);
    expect(posts.map(p => p.rerankerEnabled)).toEqual([true]);
  });

  // R3 — answers run on the compact model with its vision files active: models/status covers only the
  // standard answer package, whose files are absent, but the Engine reports this part's files active.
  it('a lit picture step with the vision files active never says its files are missing', async () => {
    store.current = { ...ready(), runtimeStatus: { ...ready().runtimeStatus,
      active: { chatProfile: 'compact', modelPath: '/models/compact/Qwen3.5-4B-Q4_K_M.gguf', mmprojActive: true } } } as unknown as AiState;
    presence = { runtimeInstalled: true, canActivateDefault: false, packages: [
      { id: 'embedding', label: 'Embedding model', complete: true, blockingIncomplete: false, presentVariantFiles: ['model.onnx'], missingSupportingFiles: [] },
      { id: 'reranker', label: 'Reranker', complete: true, blockingIncomplete: false, presentVariantFiles: ['model.onnx'], missingSupportingFiles: [] },
      { id: 'chat', label: 'Chat model', complete: false, blockingIncomplete: false, presentVariantFiles: [], missingSupportingFiles: ['mmproj-F16.gguf'] }] };
    const el = await mount();
    expect(step(el, 'pictures'), 'Text in scans and images step').not.toBeNull();
    expect(litOf(step(el, 'pictures')), 'lit: the Engine reads pictures').toBe('true');
    expect(words(step(el, 'pictures'))).not.toMatch(/files are missing/i);
    expect(words(step(el, 'pictures'))).not.toMatch(/not working/i);
    expect(stepSwitch(el, 'pictures', 'Text in scans and images')!.getAttribute('aria-checked')).toBe('true');
    expect(words(step(el, 'pictures')), 'its state in words').toMatch(/\bOn\b/);
  });

  // R5 — answers run on the compact model with picture reading saved Off, so the Engine drops the
  // projector (mmprojActive false). models/status and the plan leave the dev-only compact package out
  // and say only that the standard answer package is absent. The files of the model answers run with
  // are on disk: a click saves On and opens no install of the standard model.
  it('with answers on the compact model, a click on picture reading Off saves On and installs nothing', async () => {
    saved.pictureReadingEnabled = false;
    store.current = { ...ready(), runtimeStatus: { ...ready().runtimeStatus,
      active: { chatProfile: 'compact', modelPath: '/models/compact/Qwen3.5-4B-Q4_K_M.gguf', mmprojActive: false } } } as unknown as AiState;
    presence = { runtimeInstalled: true, canActivateDefault: false, packages: [
      { id: 'embedding', label: 'Embedding model', complete: true, blockingIncomplete: false, presentVariantFiles: ['model.onnx'], missingSupportingFiles: [] },
      { id: 'reranker', label: 'Reranker', complete: true, blockingIncomplete: false, presentVariantFiles: ['model.onnx'], missingSupportingFiles: [] },
      { id: 'chat', label: 'Chat model', complete: false, blockingIncomplete: false, presentVariantFiles: [], missingSupportingFiles: ['mmproj-F16.gguf'] }] };
    planned = { ...preview, totalDownloadBytes: 6807977504, components: preview.components!.map(c => c.id === 'chat'
      ? { ...c, state: 'to-download', totalBytes: 6807977504, downloadBytes: 6807977504 } : c) };
    const el = await mount();
    const toggle = stepSwitch(el, 'pictures', 'Text in scans and images');
    expect(toggle, 'picture switch').not.toBeNull();
    expect(toggle!.getAttribute('aria-checked')).toBe('false');
    toggle!.click(); await settle(el);
    expect(el.shadowRoot!.querySelector('[data-testid="install-consent-dialog"][open]'), 'no install of the standard answer model').toBeNull();
    expect(posts.map(p => p.pictureReadingEnabled)).toEqual([true]);
  });
});
