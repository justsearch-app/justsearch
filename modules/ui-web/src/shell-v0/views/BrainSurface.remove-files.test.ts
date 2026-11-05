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

// Advanced settings > Downloaded files (design D4): rows with measured sizes, Remove files with an
// inline confirmation, Download again through the existing consent, and switch-on paths that save
// nothing until the consented install starts.
interface Brain extends HTMLElement { apiBase: string; host_: unknown; installConsentOpen: boolean; updateComplete: Promise<boolean> }
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
const DIGEST = 'b'.repeat(64);
let view: Record<string, unknown>;
let preview: InstallPlanPreview;
let operations: Array<{ id: string; args: unknown }>;
let refusal: string | null;
let paths: string[];
let declines: string[];
let rerankerEnabled: boolean;

const chatRow = () => ({
  id: 'chat', label: 'Chat model', state: 'downloaded', sizeOnDiskBytes: 3 * 1024 * 1024, declined: false,
  removable: true, reason: null, previewDigest: DIGEST, unavailableAfter: 'Answers and Text in scans and images',
  delete: [{ path: 'answer.gguf', sizeBytes: 2 * 1024 * 1024 }, { path: 'mmproj-F16.gguf', sizeBytes: 1024 * 1024 }],
  kept: [{ path: 'custom.gguf', sizeBytes: 2048, reason: "kept - not verified as JustSearch's download" }],
});
const rerankerRow = () => ({
  id: 'reranker', label: 'Search reranker', state: 'downloaded', sizeOnDiskBytes: 4096, declined: false,
  removable: false, reason: 'Switch Best match first off first', previewDigest: 'c'.repeat(64),
  unavailableAfter: 'Best match first', delete: [{ path: 'onnx/reranker/model.onnx', sizeBytes: 4096 }], kept: [],
});
const spladeRow = () => ({
  id: 'splade', label: 'Sparse retrieval (SPLADE)', state: 'downloaded', sizeOnDiskBytes: 1024, declined: false,
  removable: false, reason: 'Removal not available yet', delete: [], kept: [],
});

async function settle(el: Brain) {
  for (let i = 0; i < 6; ++i) { await new Promise(resolve => setTimeout(resolve, 0)); await el.updateComplete; }
}
async function mount(openAdvanced = true) {
  const el = document.createElement('jf-brain-surface') as Brain;
  el.apiBase = '';
  el.host_ = createMockHostApi({ data: { invokeOperation: async (id: string, args: unknown) => {
    operations.push({ id, args });
    if (id === 'core.remove-ai-files') {
      if (refusal) throw new Error(refusal);
      view = { parts: [{ ...chatRow(), state: 'not-downloaded', sizeOnDiskBytes: 0, declined: true, removable: false,
        reason: 'No downloaded files to remove', delete: [], kept: [] }, rerankerRow(), spladeRow()],
      removal: { packageId: 'chat', label: 'Chat model', state: 'removed', message: '2 files removed',
        deleted: [{ path: 'answer.gguf' }, { path: 'mmproj-F16.gguf' }], kept: [] } };
    }
    return { success: true, structuredData: { state: 'running' } };
  } } });
  document.body.append(el); await settle(el);
  if (openAdvanced) {
    [...el.shadowRoot!.querySelectorAll<HTMLButtonElement>('button')]
      .find(b => b.textContent?.includes('Advanced settings'))!.click();
    await settle(el);
  }
  return el;
}
const section = (el: Brain) => el.shadowRoot!.querySelector<HTMLElement>('[data-testid="downloaded-files"]')!;
const row = (el: Brain, id: string) => section(el).querySelector<HTMLElement>(`[data-part="${id}"]`)!;
const text = (node: Element | null | undefined) => node?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
function switches(el: Brain, label: string) {
  return [...el.shadowRoot!.querySelectorAll<HTMLButtonElement>(`[role="switch"][aria-label="Use ${label}"]`)];
}

beforeEach(() => {
  operations = []; refusal = null; paths = []; declines = []; rerankerEnabled = true;
  view = { parts: [chatRow(), rerankerRow(), spladeRow()], removal: null };
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
    if (path.endsWith('/api/settings/v2')) return json({ ui: { chatEnabled: true }, rerankerEnabled,
      pictureReadingEnabled: false, witness: { acceptedRevision: 0, lastCommittedOperationKey: null } });
    if (path.includes('/api/ai/install/downloaded-files')) return json(view);
    if (path.includes('/plan-preview')) {
      if (!path.includes('include=')) return json(preview);
      // Download again: the included part is planned; nothing about the saved choice changed.
      return json({ ...preview, totalDownloadBytes: 1024, components: preview.components!.map(c =>
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

describe('Advanced settings - Downloaded files', () => {
  it('lists each part with its measured size, Remove files where allowed and the reason elsewhere', async () => {
    const el = await mount();

    expect(text(row(el, 'chat'))).toContain('3.0 MB on disk');
    expect(row(el, 'chat').querySelector('[data-remove="chat"]')).not.toBeNull();
    expect(text(row(el, 'reranker'))).toContain('Switch Best match first off first');
    expect(row(el, 'reranker').querySelector('[data-remove]')).toBeNull();
    expect(text(row(el, 'splade'))).toContain('Removal not available yet');
    expect(row(el, 'splade').querySelector('[data-remove]')).toBeNull();
  });

  it('confirms inline with exactly what is deleted, its size and what is kept; Cancel deletes nothing', async () => {
    const el = await mount();
    const remove = row(el, 'chat').querySelector<HTMLButtonElement>('[data-remove="chat"]')!;
    remove.click(); await settle(el);

    const confirm = section(el).querySelector<HTMLElement>('[data-testid="removal-confirm"]')!;
    expect(confirm.getAttribute('role')).toBe('group');
    expect(el.shadowRoot!.getElementById(confirm.getAttribute('aria-labelledby')!)!.textContent).toBe('Remove Chat model files?');
    expect(text(confirm)).toContain('This deletes 2 files (3.0 MB) from this computer.');
    expect(text(confirm)).toContain("Answers and Text in scans and images won't work until you download it again.");
    expect([...confirm.querySelectorAll('li')].map(text)).toEqual([
      'answer.gguf · 2.0 MB', 'mmproj-F16.gguf · 1.0 MB',
      "custom.gguf · 2.0 KB · kept - not verified as JustSearch's download"]);
    expect(remove.getAttribute('aria-expanded')).toBe('true');

    [...confirm.querySelectorAll<HTMLButtonElement>('button')].find(b => b.textContent === 'Cancel')!.click();
    await settle(el);
    expect(section(el).querySelector('[data-testid="removal-confirm"]')).toBeNull();
    expect(el.shadowRoot!.activeElement).toBe(row(el, 'chat').querySelector('[data-remove="chat"]'));
    expect(operations).toEqual([]);
  });

  it('removes through the HIGH-risk operation with the part id and preview, then offers Download again', async () => {
    const el = await mount();
    row(el, 'chat').querySelector<HTMLButtonElement>('[data-remove="chat"]')!.click(); await settle(el);
    section(el).querySelector<HTMLButtonElement>('.confirm-remove')!.click(); await settle(el);

    expect(operations).toEqual([{ id: 'core.remove-ai-files', args: { packageId: 'chat', previewDigest: DIGEST } }]);
    expect(text(section(el).querySelector('[data-testid="removal-outcome"]'))).toBe('Chat model: Files removed. 2 files removed');
    expect(text(row(el, 'chat'))).toContain('Not downloaded');
    expect(el.shadowRoot!.activeElement).toBe(row(el, 'chat').querySelector('[data-download-again="chat"]'));
    expect(declines).toEqual([]);
  });

  it('shows a refusal with its plain reason and keeps the row', async () => {
    refusal = 'Switch answers off first';
    const el = await mount();
    row(el, 'chat').querySelector<HTMLButtonElement>('[data-remove="chat"]')!.click(); await settle(el);
    section(el).querySelector<HTMLButtonElement>('.confirm-remove')!.click(); await settle(el);

    expect(text(row(el, 'chat').querySelector('[role="alert"]'))).toBe('Switch answers off first');
    expect(text(row(el, 'chat'))).toContain('3.0 MB on disk');
  });

  it('while a removal runs elsewhere, Remove files is disabled and the page says so', async () => {
    view = { ...view, removal: { packageId: 'chat', label: 'Chat model', state: 'running', message: 'Removing files' } };
    const el = await mount();

    expect(row(el, 'chat').querySelector<HTMLButtonElement>('[data-remove="chat"]')!.disabled).toBe(true);
    expect(text(section(el).querySelector('[role="status"]'))).toBe('Removing files…');
  });

  it('Download again asks first; closing the consent saves nothing and accepting starts the included download', async () => {
    view = { parts: [{ ...chatRow(), state: 'not-downloaded', declined: true, removable: false, delete: [], kept: [] }], removal: null };
    preview.components = preview.components!.map(c => c.id === 'chat' ? { ...c, state: 'declined', declined: true, downloadBytes: 0 } : c);
    const el = await mount();

    row(el, 'chat').querySelector<HTMLButtonElement>('[data-download-again="chat"]')!.click(); await settle(el);
    expect(el.installConsentOpen).toBe(true);
    expect(paths.some(p => p.endsWith('/api/ai/install/plan-preview?include=chat'))).toBe(true);
    const dialog = el.shadowRoot!.querySelector('[data-testid="install-consent-dialog"]')!;
    expect(text(dialog)).toContain('1.0 KB');
    (dialog.querySelector('[label="Cancel"]') as unknown as { onActivate(): void }).onActivate(); await settle(el);
    expect(el.installConsentOpen).toBe(false);
    expect(declines).toEqual([]);
    expect(operations).toEqual([]);

    row(el, 'chat').querySelector<HTMLButtonElement>('[data-download-again="chat"]')!.click(); await settle(el);
    (el.shadowRoot!.querySelector('[data-testid="install-consent-dialog"] [label="Accept and download"]') as unknown as { onActivate(): void }).onActivate();
    await settle(el);
    expect(operations).toEqual([{ id: 'core.start-ai-install', args: { acceptTerms: true, includePackages: ['chat'] } }]);
    expect(declines).toEqual([]);
  });
});

describe('switching on a removed part saves nothing until the consented install starts', () => {
  it('Best match first: Cancel leaves the decline, Accept carries it to the install start', async () => {
    rerankerEnabled = false;
    store.current = { ...store.current, runtimeStatus: { onnxFeatures: [{ id: 'reranker', status: 'inactive', reason: 'disabled', modelActive: false }] } } as AiState;
    preview.components = preview.components!.map(c => c.id === 'reranker'
      ? { ...c, state: 'declined', declined: true, downloadBytes: 0 } : c);
    const el = await mount(false);

    switches(el, 'Best match first')[0]!.click(); await settle(el);
    expect(el.installConsentOpen).toBe(true);
    expect(declines).toEqual([]);
    const dialog = el.shadowRoot!.querySelector('[data-testid="install-consent-dialog"]')!;
    (dialog.querySelector('[label="Cancel"]') as unknown as { onActivate(): void }).onActivate(); await settle(el);
    expect(declines).toEqual([]);
    expect(operations).toEqual([]);

    switches(el, 'Best match first')[0]!.click(); await settle(el);
    (el.shadowRoot!.querySelector('[data-testid="install-consent-dialog"] [label="Accept and download"]') as unknown as { onActivate(): void }).onActivate();
    await settle(el);
    expect(declines).toEqual([]);
    expect(operations).toEqual([{ id: 'core.start-ai-install',
      args: { acceptTerms: true, enableRerankerOnCompletion: true, includePackages: ['reranker'] } }]);
  });

  it('Answers and picture reading: a declined answer model is undeclined only by the accepted start', async () => {
    preview.components = preview.components!.map(c => c.id === 'chat'
      ? { ...c, state: 'declined', declined: true, downloadBytes: 0 } : c);
    store.current = { ...store.current, capabilities: { chat: false } as AiState['capabilities'],
      runtime: { mode: 'offline', modelId: null, installed: { known: true, value: true } } as AiState['runtime'] } as AiState;
    const el = await mount(false);

    for (const label of ['Answer questions', 'Text in scans and images']) {
      switches(el, label)[0]!.click(); await settle(el);
      expect(el.installConsentOpen, label).toBe(true);
      expect(declines, label).toEqual([]);
      expect(paths.some(p => p.endsWith('/api/ai/install/plan-preview?include=chat')), label).toBe(true);
      (el.shadowRoot!.querySelector('[data-testid="install-consent-dialog"] [label="Cancel"]') as unknown as { onActivate(): void }).onActivate();
      await settle(el);
      expect(declines, label).toEqual([]);
      paths = [];
    }
    switches(el, 'Text in scans and images')[0]!.click(); await settle(el);
    (el.shadowRoot!.querySelector('[data-testid="install-consent-dialog"] [label="Accept and download"]') as unknown as { onActivate(): void }).onActivate();
    await settle(el);
    expect(operations).toEqual([{ id: 'core.start-ai-install',
      args: { acceptTerms: true, enablePictureReadingOnCompletion: true, includePackages: ['chat'] } }]);
    expect(declines).toEqual([]);
  });
});
