// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// Slice 7a (S7a-8; D5, AM5, AM6) — what the pictures do not show sits behind Settings › Advanced › Show
// developer tools, live; recovery routes stay reachable without developer tools.
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
import { requestReadingMemoryFocus } from '../../router/readingMemoryIntent.js';
import { deepActiveElement } from '../../utils/keyboardHandler.js';

interface Brain extends HTMLElement { apiBase: string; host_: unknown; updateComplete: Promise<boolean> }
interface Control extends HTMLElement { updateComplete: Promise<boolean>; label?: string }
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
let preview: InstallPlanPreview;
let policy: Record<string, unknown>;
let downloaded: Record<string, unknown>;
const parts = ['embedding', 'reranker', 'chat'];
const plan = (state: string): InstallPlanPreview => ({ totalDownloadBytes: state === 'installed' ? 0 : 3072, components: parts.map(id => ({
  id, label: id, state, declinable: id !== 'embedding', totalBytes: 1024, downloadBytes: state === 'installed' ? 0 : 1024 })) });
const live = { phase: 'connected', installObservationLive: true, runtimeObservationLive: true, snapshotLive: true,
  index: { ownDocumentCount: { known: true, value: 8 } } };
function ready(): AiState {
  return { ...live, aiEngine: { kind: 'online', stability: { kind: 'settled' }, installFailure: null },
    runtime: { mode: 'online', installed: { known: true, value: true }, gpu: { available: true, description: 'CUDA' } },
    capabilities: { chat: true, rag: true, extract: true, embedding: true }, installStatus: { state: 'completed', installedFully: true },
    runtimeStatus: { active: { mmprojActive: false }, onnxFeatures: [{ id: 'embed', status: 'active', modelActive: true }, { id: 'reranker', status: 'active', modelActive: true }] },
    status: { inference: { chatEnabledSpec: true } } } as unknown as AiState;
}
function notInstalled(): AiState {
  return { ...live, aiEngine: { kind: 'not_installed', stability: { kind: 'settled' }, installFailure: null },
    runtime: { mode: 'offline', installed: { known: true, value: false } }, capabilities: { chat: false },
    installStatus: { state: 'idle', installedFully: false }, runtimeStatus: { onnxFeatures: [] } } as unknown as AiState;
}
async function settle(el: Brain) {
  for (let i = 0; i < 5; ++i) { await new Promise(resolve => setTimeout(resolve, 0)); await el.updateComplete; }
}
async function mount() {
  const el = document.createElement('jf-brain-surface') as Brain;
  el.apiBase = '';
  el.host_ = createMockHostApi({ data: { invokeOperation: async () => ({ success: true, structuredData: null }) } });
  document.body.append(el); await settle(el); return el;
}
const words = (node: Element | ShadowRoot | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
const labels = (el: Brain) => [...el.shadowRoot!.querySelectorAll<Control>('jf-button')].map(b => b.label ?? '');
function advanced(el: Brain) {
  return [...el.shadowRoot!.querySelectorAll<HTMLButtonElement>('.brain-links button')].find(b => words(b) === 'Advanced settings') ?? null;
}

beforeEach(() => {
  setShowDeveloperTools(false);
  preview = plan('installed'); policy = {}; downloaded = { parts: [] };
  store.current = ready();
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input);
    if (path.endsWith('/api/settings/v2')) return json({ ui: { chatEnabled: true }, llm: {}, rerankerEnabled: true, pictureReadingEnabled: false,
      witness: { acceptedRevision: 0, lastCommittedOperationKey: null } });
    if (path.endsWith('/plan-preview')) return json(preview);
    if (path.endsWith('/manifest')) return json({ schemaVersion: 2, packages: [] });
    if (path.endsWith('/api/ai/models/status')) return json({ packages: parts.map(id => ({ id, complete: preview.components!.find(c => c.id === id)!.state === 'installed' })) });
    if (path.endsWith('/api/ai/install/downloaded-files')) return json(downloaded);
    if (path.endsWith('/api/policy/effective')) return json(policy);
    return json({});
  }));
});
afterEach(() => { document.body.innerHTML = ''; store.listeners.clear(); vi.unstubAllGlobals(); setShowDeveloperTools(false); });

describe('S7a-8 unpictured controls are developer tools, live', () => {
  it('Start/Stop answers, Refresh status and the You get / It costs panels appear only with developer tools', async () => {
    const el = await mount();
    expect(labels(el)).not.toEqual(expect.arrayContaining(['Stop answers']));
    expect(labels(el)).not.toContain('Start answers');
    expect(labels(el)).not.toContain('Refresh status');
    expect(el.shadowRoot!.querySelectorAll('[data-step] button.step-info, button.feature-phrase')).toHaveLength(0);
    setShowDeveloperTools(true); await settle(el);
    expect(labels(el)).toContain('Stop answers');
    expect(labels(el)).toContain('Refresh status');
    const info = el.shadowRoot!.querySelector<HTMLButtonElement>('[data-step="reranker"] button.step-info');
    expect(info, 'feature disclosure').not.toBeNull();
    info!.click(); await settle(el);
    expect(words(el.shadowRoot!.querySelector('.feature-panel'))).toMatch(/You get.*It costs/);
    setShowDeveloperTools(false); await settle(el);
    expect(labels(el)).not.toContain('Stop answers');
    expect(el.shadowRoot!.querySelector('.feature-panel')).toBeNull();
  });

  it('keeps only Advanced settings and Engine history in the links row', async () => {
    const el = await mount();
    const links = el.shadowRoot!.querySelector('.brain-links');
    expect(links, 'links row').not.toBeNull();
    expect([...links!.querySelectorAll('button, a, jf-button')].map(n => words(n) || (n as Control).label)).toEqual(['Advanced settings', 'Engine history in Health']);
  });
});

describe('S7a-8 recovery routes without developer tools', () => {
  it('pack import is reachable when downloads are disabled before install', async () => {
    store.current = notInstalled(); preview = plan('to-download'); policy = { downloadsEnabled: false };
    const el = await mount();
    expect(el.shadowRoot!.querySelector('[data-step="meaning"]'), 'Understand meaning step').not.toBeNull();
    const open = advanced(el);
    expect(open, 'Advanced settings').not.toBeNull();
    open!.click(); await settle(el);
    expect(words(el.shadowRoot!)).toContain('Import an offline package');
  });

  it('a not-installed page shows no Advanced settings link otherwise (fm-10)', async () => {
    store.current = notInstalled(); preview = plan('to-download');
    const el = await mount();
    expect(advanced(el)).toBeNull();
    expect(el.shadowRoot!.querySelector('[data-step="meaning"]'), 'Understand meaning step').not.toBeNull();
  });

  it('manual repair links stay on the page', async () => {
    store.current = { ...ready(), installStatus: { state: 'idle', installedFully: true, repairNeeded: true, packages: [{ packageId: 'reranker',
      label: 'Search reranker', terminalReason: 'transport', attempts: 3, url: 'https://example.invalid/reranker.onnx', targetPath: 'C:/models/reranker.onnx', error: 'timed out' }] } } as unknown as AiState;
    const el = await mount();
    expect(el.shadowRoot!.querySelector('[data-step="reranker"]'), 'Best match first step').not.toBeNull();
    const manual = el.shadowRoot!.querySelector('[data-testid="install-manual-fallback"]');
    expect(words(manual)).toContain('https://example.invalid/reranker.onnx');
    expect(words(manual)).toContain('C:/models/reranker.onnx');
  });

  it('an interrupted or refused removal outcome is reachable under Advanced settings', async () => {
    downloaded = { parts: [{ id: 'chat', label: 'Chat model', state: 'downloaded', sizeOnDiskBytes: 10, removable: true, previewDigest: 'd' }],
      removal: { packageId: 'chat', label: 'Chat model', state: 'interrupted', message: 'The app closed during removal.', deleted: [], kept: [] } };
    const el = await mount();
    expect(el.shadowRoot!.querySelector('[data-step="chat"]'), 'Answer questions step').not.toBeNull();
    advanced(el)!.click(); await settle(el);
    expect(words(el.shadowRoot!.querySelector('[data-testid="removal-outcome"]'))).toContain('The removal was interrupted');
  });

  it('Settings › Reading memory opens Advanced and focuses its control', async () => {
    store.current = notInstalled(); preview = plan('to-download');
    const el = await mount();
    expect(el.shadowRoot!.querySelector('[data-step="meaning"]'), 'Understand meaning step').not.toBeNull();
    requestReadingMemoryFocus(); await settle(el);
    await new Promise(resolve => requestAnimationFrame(() => resolve(null))); await settle(el);
    expect(deepActiveElement()?.id).toBe('brain-contextWindow');
  });
});
