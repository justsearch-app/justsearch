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

// Answers whose answer model was removed (declined): every route that turns answers on asks first
// and saves nothing until the consented download starts, and that download carries the answers
// enable to its completion instead of switching answers on while the model is still missing.
interface Brain extends HTMLElement { apiBase: string; host_: unknown; installConsentOpen: boolean; updateComplete: Promise<boolean> }
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
/** The plan identity the included-plan preview carries; the accepted start sends it back. */
const PLAN = 'd'.repeat(64);
let preview: InstallPlanPreview;
let operations: Array<{ id: string; args: unknown }>;
let paths: string[];
let declines: string[];

/** Downloaded files after the answer model was removed: nothing on disk, declined. */
const removedChatRow = () => ({
  id: 'chat', label: 'Chat model', state: 'not-downloaded', sizeOnDiskBytes: 0, declined: true,
  removable: false, reason: 'No downloaded files to remove', delete: [], kept: [],
});

async function settle(el: Brain) {
  for (let i = 0; i < 6; ++i) { await new Promise(resolve => setTimeout(resolve, 0)); await el.updateComplete; }
}
async function mount() {
  const el = document.createElement('jf-brain-surface') as Brain;
  el.apiBase = '';
  el.host_ = createMockHostApi({ data: { invokeOperation: async (id: string, args: unknown) => {
    operations.push({ id, args });
    return { success: true, structuredData: { state: 'running' } };
  } } });
  document.body.append(el); await settle(el);
  return el;
}
function switches(el: Brain, label: string) {
  return [...el.shadowRoot!.querySelectorAll<HTMLButtonElement>(`[role="switch"][aria-label="Use ${label}"]`)];
}

beforeEach(() => {
  operations = []; paths = []; declines = [];
  preview = { totalDownloadBytes: 0, components: ['embedding', 'reranker', 'chat'].map(id => ({
    id, label: id, state: 'installed', declinable: id !== 'embedding', totalBytes: 1024, downloadBytes: 0,
  })) };
  store.current = {
    installObservationLive: true, runtimeObservationLive: true, snapshotLive: true,
    aiEngine: { kind: 'online', stability: { kind: 'settled' }, installFailure: null },
    runtime: { mode: 'online', installed: { known: true, value: true } },
    installStatus: { state: 'completed', installedFully: true }, capabilities: { chat: true },
    runtimeStatus: { onnxFeatures: [{ id: 'reranker', status: 'inactive', reason: 'disabled', modelActive: false }] },
  } as AiState;
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    paths.push(path);
    if (path.includes('/decline')) { declines.push(`${init?.method ?? 'GET'} ${path}`); return json({ state: 'completed' }); }
    // Removal required answers and picture reading to be off.
    if (path.endsWith('/api/settings/v2')) return json({ ui: { chatEnabled: false }, rerankerEnabled: false,
      pictureReadingEnabled: false, witness: { acceptedRevision: 0, lastCommittedOperationKey: null } });
    if (path.includes('/api/ai/install/downloaded-files')) return json({ parts: [removedChatRow()], removal: null });
    if (path.includes('/plan-preview')) {
      if (!path.includes('include=')) return json(preview);
      // The included part is planned; nothing about the saved choice changed.
      return json({ ...preview, totalDownloadBytes: 1024, planDigest: PLAN, components: preview.components!.map(c =>
        path.includes(`include=${c.id}`) ? { ...c, declined: false, state: 'to-download', downloadBytes: 1024 } : c) });
    }
    if (path.endsWith('/manifest')) return json({ schemaVersion: 2, packages: [
      { id: 'chat', label: 'Chat model', license: 'Apache-2.0', termsUrl: 'https://example.invalid/chat', variants: [], supportingFiles: [] },
      { id: 'reranker', label: 'Ranking model', license: 'Apache-2.0', termsUrl: 'https://example.invalid/rank', variants: [], supportingFiles: [] },
    ] });
    if (path.endsWith('/api/ai/models/status')) return json({ packages: preview.components?.map(c => ({ id: c.id, complete: c.state === 'installed' })) });
    return json({});
  }));
});
afterEach(() => { document.body.innerHTML = ''; store.listeners.clear(); vi.unstubAllGlobals(); });

describe('answers consented with a removed answer model are switched on when the download completes', () => {
  const removedAnswers = () => {
    preview.components = preview.components!.map(c => c.id === 'chat'
      ? { ...c, state: 'declined', declined: true, downloadBytes: 0 } : c);
    store.current = { ...store.current, capabilities: { chat: false } as AiState['capabilities'],
      aiEngine: { kind: 'offline', stability: { kind: 'settled' }, installFailure: null },
      runtime: { mode: 'offline', modelId: null, installed: { known: true, value: true } } as AiState['runtime'] } as AiState;
  };
  const accept = async (el: Brain) => {
    (el.shadowRoot!.querySelector('[data-testid="install-consent-dialog"] [label="Accept and download"]') as unknown as { onActivate(): void }).onActivate();
    await settle(el);
  };

  it('the answers switch: accepting starts the download with the completion enable and switches nothing on now', async () => {
    removedAnswers();
    const el = await mount();

    switches(el, 'Answer questions')[0]!.click(); await settle(el);
    expect(el.installConsentOpen).toBe(true);
    await accept(el);

    expect(operations).toEqual([{ id: 'core.start-ai-install',
      args: { acceptTerms: true, enableChatOnCompletion: true, includePackages: ['chat'], planDigest: PLAN } }]);
    expect(declines).toEqual([]);
  });

  it('Start answers: asks first, Cancel saves nothing, Accept starts the download without switching answers on', async () => {
    removedAnswers();
    const el = await mount();
    const start = () => el.shadowRoot!.querySelector('[data-testid="brain-simple-action"]') as unknown as { onActivate(): void; getAttribute(name: string): string | null };

    expect(start().getAttribute('label')).toBe('Start answers');
    start().onActivate(); await settle(el);
    expect(el.installConsentOpen).toBe(true);
    expect(paths.some(p => p.endsWith('/api/ai/install/plan-preview?include=chat'))).toBe(true);
    (el.shadowRoot!.querySelector('[data-testid="install-consent-dialog"] [label="Cancel"]') as unknown as { onActivate(): void }).onActivate();
    await settle(el);
    expect(operations).toEqual([]);
    expect(declines).toEqual([]);

    start().onActivate(); await settle(el);
    await accept(el);
    expect(operations).toEqual([{ id: 'core.start-ai-install',
      args: { acceptTerms: true, enableChatOnCompletion: true, includePackages: ['chat'], planDigest: PLAN } }]);
    expect(declines).toEqual([]);
  });
});
