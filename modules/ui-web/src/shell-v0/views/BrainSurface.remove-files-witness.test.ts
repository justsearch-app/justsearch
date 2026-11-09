// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AiState } from '../state/aiStateStore.js';
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

// A removal saves settings: the decline when it starts, and once more when it ends so the runtime
// sees what is on disk. The page's next switch must be saved against the revision those left.
interface Brain extends HTMLElement { apiBase: string; host_: unknown; updateComplete: Promise<boolean> }
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
const DIGEST = 'b'.repeat(64);
const AFTER_REMOVAL = { acceptedRevision: 7, lastCommittedOperationKey: '00000000-0000-7000-8000-000000000007' };
let witness: { acceptedRevision: number; lastCommittedOperationKey: string | null };
let saves: Array<{ witness: unknown }>;
let removalRow: Record<string, unknown>;
let removal: Record<string, unknown> | null;
let onRemove: () => void;

const chatRow = () => ({
  id: 'chat', label: 'Chat model', state: 'downloaded', sizeOnDiskBytes: 2048, declined: false, removable: true,
  reason: null, previewDigest: DIGEST, unavailableAfter: 'Answers and Text in scans and images',
  delete: [{ path: 'answer.gguf', sizeBytes: 2048 }], kept: [],
});
const removedRow = () => ({ ...chatRow(), state: 'not-downloaded', sizeOnDiskBytes: 0, declined: true, removable: false,
  reason: 'No downloaded files to remove', delete: [] });
const finished = () => ({ packageId: 'chat', label: 'Chat model', state: 'removed', message: '1 file removed',
  deleted: [{ path: 'answer.gguf', sizeBytes: 2048 }], kept: [] });

async function settle(el: Brain) {
  for (let i = 0; i < 6; ++i) { await new Promise(resolve => setTimeout(resolve, 0)); await el.updateComplete; }
}
async function mount() {
  const el = document.createElement('jf-brain-surface') as Brain;
  el.apiBase = '';
  el.host_ = createMockHostApi({ data: { invokeOperation: async (id: string) => {
    if (id === 'core.remove-ai-files') onRemove();
    return { success: true, structuredData: { state: 'running' } };
  } } });
  document.body.append(el); await settle(el);
  [...el.shadowRoot!.querySelectorAll<HTMLButtonElement>('button')]
    .find(b => b.textContent?.includes('Advanced settings'))!.click();
  await settle(el);
  return el;
}
const section = (el: Brain) => el.shadowRoot!.querySelector<HTMLElement>('[data-testid="downloaded-files"]')!;
async function confirmRemoval(el: Brain) {
  section(el).querySelector<HTMLButtonElement>('[data-remove="chat"]')!.click(); await settle(el);
  section(el).querySelector('jf-confirm-band')!.shadowRoot!
    .querySelector<HTMLElement & { onActivate(): void }>('jf-button.confirm')!.onActivate(); await settle(el);
}
async function saveBestMatchFirst(el: Brain) {
  el.shadowRoot!.querySelector<HTMLButtonElement>('[role="switch"][aria-label="Use Best match first"]')!.click();
  await settle(el);
}

beforeEach(() => {
  witness = { acceptedRevision: 0, lastCommittedOperationKey: null };
  saves = []; removalRow = chatRow(); removal = null; onRemove = () => {};
  store.current = {
    installObservationLive: true, runtimeObservationLive: true, snapshotLive: true,
    aiEngine: { kind: 'online', stability: { kind: 'settled' }, installFailure: null },
    runtime: { mode: 'online', installed: { known: true, value: true } },
    installStatus: { state: 'completed', installedFully: true }, capabilities: { chat: true },
    runtimeStatus: { onnxFeatures: [{ id: 'reranker', status: 'inactive', reason: 'disabled', modelActive: false }] },
  } as AiState;
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path.endsWith('/api/settings/v2') && init?.body) {
      saves.push(JSON.parse(String(init.body)) as { witness: unknown });
      return json({ error: 'not saved in this test', errorCode: 'VERSION_CONFLICT' }, 409);
    }
    if (path.endsWith('/api/settings/v2')) {
      return json({ ui: { chatEnabled: false }, rerankerEnabled: true, pictureReadingEnabled: false, witness });
    }
    if (path.includes('/api/ai/install/downloaded-files')) return json({ parts: [removalRow], removal });
    if (path.includes('/plan-preview')) {
      return json({ totalDownloadBytes: 0, components: ['embedding', 'reranker', 'chat'].map(id => ({
        id, label: id, state: 'installed', declinable: id !== 'embedding', totalBytes: 1024, downloadBytes: 0 })) });
    }
    return json({});
  }));
});
afterEach(() => { vi.useRealTimers(); document.body.innerHTML = ''; store.listeners.clear(); vi.unstubAllGlobals(); });

describe('the switch after Remove files', () => {
  it('a removal that already ended: saved against the revision it left behind', async () => {
    onRemove = () => { witness = AFTER_REMOVAL; removalRow = removedRow(); removal = finished(); };
    const el = await mount();

    await confirmRemoval(el);
    await saveBestMatchFirst(el);

    expect(saves.map(save => save.witness)).toEqual([AFTER_REMOVAL]);
  });

  it('a removal still running when the page returned: saved against the revision it left when it ended', async () => {
    onRemove = () => { removal = { packageId: 'chat', label: 'Chat model', state: 'running', message: 'Removing files' }; };
    const el = await mount();
    await confirmRemoval(el);
    witness = AFTER_REMOVAL; removalRow = removedRow(); removal = finished();

    await new Promise(resolve => setTimeout(resolve, 1100)); // the page's one-second removal poll
    await settle(el);
    await saveBestMatchFirst(el);

    expect(saves.map(save => save.witness)).toEqual([AFTER_REMOVAL]);
  });
});
