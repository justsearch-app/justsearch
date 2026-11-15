// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// Slice 7a preservation: two behaviours the AI Brain page had before the first-minutes Overview and
// keeps after it. (a) While pictures are being read and the status observation is lost, the page
// says the answers state "when last observed" rather than as current. (b) With AI installed but the
// answer model not downloaded, the Answer questions step is not lit.
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

interface Brain extends HTMLElement { apiBase: string; host_: unknown; updateComplete: Promise<boolean> }
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
const IDS = ['embedding', 'reranker', 'chat', 'splade', 'ner', 'citation-scorer'];
const component = (id: string, state = 'installed') => ({ id, label: id, state, declined: false, declinable: id !== 'embedding',
  necessity: id === 'embedding' ? 'required' : 'improves-results', totalBytes: 1024, downloadBytes: state === 'installed' ? 0 : 1024 });
let preview: InstallPlanPreview;
let presence: unknown;
function emit(patch: Partial<AiState> = {}) {
  store.current = { ...store.current, ...patch };
  for (const listener of store.listeners) listener(store.current);
}
async function settle(el: Brain) {
  for (let i = 0; i < 4; ++i) { await new Promise(resolve => setTimeout(resolve, 0)); await el.updateComplete; }
}
async function mount() {
  const el = document.createElement('jf-brain-surface') as Brain;
  el.apiBase = '';
  el.host_ = createMockHostApi({ data: { invokeOperation: async () => ({ success: true, structuredData: null }) } });
  document.body.appendChild(el); await settle(el); return el;
}
const words = (node: Element | ShadowRoot | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
beforeEach(() => {
  preview = { totalDownloadBytes: 0, components: IDS.map(id => component(id)) };
  presence = { packages: IDS.map(id => ({ id, complete: true })) };
  store.current = {
    aiEngine: { kind: 'online', stability: { kind: 'settled' }, installFailure: null }, installObservationLive: true, runtimeObservationLive: true, snapshotLive: true,
    runtime: { mode: 'online', modelId: 'answer-model', modelLabel: 'Answer model', installed: { known: true, value: true } },
    capabilities: { chat: true }, status: { inference: { chatEnabledSpec: true }, operating: { gpu: { stale: false, adapter: 'Test adapter', adapterTotalBytes: 8192, adapterFreeBytes: 4096 } } },
    installStatus: { state: 'completed', phase: 'done', installedFully: true },
    runtimeStatus: { active: { mmprojActive: true }, onnxFeatures: [{ id: 'embed', status: 'active', modelActive: true }, { id: 'reranker', status: 'active', modelActive: true }] },
    index: { documentCount: { known: true, value: 24 } },
  } as unknown as AiState;
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input);
    if (path.endsWith('/api/ai/models/status')) return json(presence);
    if (path.endsWith('/api/settings/v2')) return json({ ui: { chatEnabled: true }, llm: {}, witness: { acceptedRevision: 0, lastCommittedOperationKey: null } });
    if (path.endsWith('/plan-preview')) return json(preview);
    if (path.endsWith('/manifest')) return json({ schemaVersion: 2, packages: [] });
    return json({});
  }));
});
afterEach(() => { document.body.innerHTML = ''; store.listeners.clear(); vi.unstubAllGlobals(); });

describe('AI Brain preservation (slice 7a)', () => {
  it('while pictures are being read, a lost observation says the answers state when last observed', async () => {
    emit({ status: { inference: { chatEnabledSpec: true, procedure: 'VDU_BATCH' } } as AiState['status'] });
    const el = await mount();
    expect(words(el.shadowRoot!)).not.toContain('when last observed');
    emit({ snapshotLive: false }); await settle(el);
    expect(words(el.shadowRoot!)).toContain('Pictures were being read when last observed');
  });

  it('with AI installed but the answer model not downloaded, Answer questions is not lit', async () => {
    preview = { totalDownloadBytes: 1024, components: IDS.map(id => component(id, id === 'chat' ? 'to-download' : 'installed')) };
    presence = { packages: IDS.map(id => ({ id, complete: id !== 'chat' })) };
    emit({ aiEngine: { kind: 'offline', stability: { kind: 'settled' }, installFailure: null },
      runtime: { mode: 'offline', modelId: null, installed: { known: true, value: true } } as AiState['runtime'],
      capabilities: { chat: false, rag: false, extract: false, embedding: true } as AiState['capabilities'],
      status: { inference: { chatEnabledSpec: false } } as AiState['status'],
      runtimeStatus: { onnxFeatures: [{ id: 'embed', status: 'active', modelActive: true }] } as AiState['runtimeStatus'] });
    const el = await mount();
    const chat = el.shadowRoot!.querySelector<HTMLElement>('[data-feature="chat"]');
    expect(chat, 'Answer questions step').not.toBeNull();
    expect(words(chat)).toContain('Answer questions');
    // Lit is drawn as data-lit on the step (slice 7a) or data-active on its symbol (before it).
    const lit = chat!.getAttribute('data-lit') === 'true' || chat!.querySelector('.step-symbol[data-active="true"]') != null;
    expect(lit, 'Answer questions lit').toBe(false);
  });
});
