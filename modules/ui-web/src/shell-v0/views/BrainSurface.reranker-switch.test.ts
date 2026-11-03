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
interface Brain extends HTMLElement { apiBase: string; host_: unknown; installConsentOpen: boolean; updateComplete: Promise<boolean> }
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
let enabled: boolean | null;
let witness: { acceptedRevision: number; lastCommittedOperationKey: string | null };
let preview: InstallPlanPreview;
let posts: Array<{ rerankerEnabled: boolean; rerankerSwitchIntent?: boolean; witness: typeof witness }>;
let operations: string[];
let operationArgs: unknown[];
let refusal: string | null;
let wait: Promise<void> | null;
let invalidReceipt: boolean;
function emit(active: boolean) {
  store.current = { ...store.current, runtimeStatus: { onnxFeatures: [{ id: 'reranker',
    status: active ? 'active' : 'inactive', reason: active ? 'explicit_path' : 'disabled', modelActive: active }] } };
  for (const listener of store.listeners) listener(store.current);
}
async function settle(el: Brain) {
  for (let i = 0; i < 4; ++i) { await new Promise(resolve => setTimeout(resolve, 0)); await el.updateComplete; }
}
async function mount() {
  const el = document.createElement('jf-brain-surface') as Brain;
  el.apiBase = '';
  el.host_ = createMockHostApi({ data: { invokeOperation: async (id: string, args: unknown) => {
    operations.push(id); operationArgs.push(args); return { success: true, structuredData: { state: 'running' } };
  } } });
  document.body.append(el); await settle(el); return el;
}
function switches(el: Brain) {
  return [...el.shadowRoot!.querySelectorAll<HTMLButtonElement>('[role="switch"][aria-label="Use Best match first"]')];
}
beforeEach(() => {
  enabled = null; witness = { acceptedRevision: 0, lastCommittedOperationKey: null };
  posts = []; operations = []; operationArgs = []; refusal = null; wait = null; invalidReceipt = false;
  preview = { totalDownloadBytes: 0, components: ['embedding', 'reranker', 'chat'].map(id => ({
    id, label: id, state: 'installed', declinable: id !== 'embedding', totalBytes: 1024, downloadBytes: 0,
  })) };
  store.current = {
    installObservationLive: true, runtimeObservationLive: true, snapshotLive: true,
    aiEngine: { kind: 'online', stability: { kind: 'settled' }, installFailure: null },
    runtime: { mode: 'online', installed: { known: true, value: true } },
    installStatus: { state: 'completed', installedFully: true }, capabilities: { chat: true },
  } as AiState;
  emit(true);
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path.endsWith('/api/settings/v2') && init?.method === 'POST') {
      const body = JSON.parse(String(init.body)); posts.push(body); await wait;
      if (refusal) return json({ errorCode: 'QUERY_ROLE_PATH_OVERRIDDEN', error: refusal }, 409);
      if (body.witness.acceptedRevision !== witness.acceptedRevision) return json({ errorCode: 'VERSION_CONFLICT' }, 409);
      enabled = body.rerankerEnabled;
      witness = { acceptedRevision: witness.acceptedRevision + 1, lastCommittedOperationKey: body.operationKey };
      return json({ state: 'COMPLETE', operationKey: invalidReceipt ? 'unidentified' : body.operationKey, witness, rerankerEnabled: enabled });
    }
    if (path.endsWith('/api/settings/v2')) return json({ ui: { chatEnabled: true }, rerankerEnabled: enabled, witness });
    if (path.endsWith('/plan-preview')) return json(preview);
    if (path.endsWith('/manifest')) return json({ schemaVersion: 2, packages: [
      { id: 'reranker', label: 'Ranking model', license: 'Apache-2.0', termsUrl: 'https://example.invalid/ranking', variants: [], supportingFiles: [] },
      { id: 'ner', label: 'Entity model', license: 'MIT', termsUrl: 'https://example.invalid/entities', variants: [], supportingFiles: [] },
    ] });
    if (path.endsWith('/api/ai/models/status')) return json({ packages: preview.components?.map(c => ({ id: c.id, complete: c.state === 'installed' })) });
    return json({});
  }));
});
afterEach(() => { document.body.innerHTML = ''; store.listeners.clear(); vi.unstubAllGlobals(); });

describe('Best match first live switch', () => {
  it('shares the sentence and path choice, waits for completion, and switches back on', async () => {
    const el = await mount();
    expect(switches(el).map(b => b.getAttribute('aria-checked'))).toEqual(['true', 'true']);
    let release!: () => void;
    wait = new Promise(resolve => { release = resolve; });
    switches(el)[0]!.click(); await settle(el);
    expect(posts.map(p => p.rerankerEnabled)).toEqual([false]);
    expect(el.shadowRoot!.querySelector('[data-feature="reranker"]')!.textContent).toContain('Changing…');
    expect(switches(el).map(b => b.getAttribute('aria-disabled'))).toEqual(['true', 'true']);
    release(); await settle(el);
    expect(switches(el).map(b => b.getAttribute('aria-checked'))).toEqual(['false', 'false']);
    expect(el.shadowRoot!.querySelector('[data-feature="reranker"]')!.textContent).toContain('Off — files kept');
    emit(false); await settle(el);
    switches(el)[1]!.click(); await settle(el);
    expect(posts.map(p => ({ enabled: p.rerankerEnabled, intent: p.rerankerSwitchIntent })))
      .toEqual([{ enabled: false, intent: true }, { enabled: true, intent: true }]);
    emit(true); await settle(el);
    expect(switches(el).map(b => b.getAttribute('aria-checked'))).toEqual(['true', 'true']);
    expect(el.shadowRoot!.querySelectorAll('[data-feature="reranker"] > .step-meta')[1]!.textContent?.trim()).toBe('On');
    expect(operations).toEqual([]);
  });

  it('keeps On and explains an operator refusal', async () => {
    refusal = 'Best match first is controlled by an operator override; change that override first';
    const el = await mount(); switches(el)[0]!.click(); await settle(el);
    expect(posts.map(p => p.rerankerEnabled)).toEqual([false]);
    expect(switches(el).map(b => b.getAttribute('aria-checked'))).toEqual(['true', 'true']);
    expect(el.shadowRoot!.textContent).toContain(refusal);
    expect(enabled).toBeNull();
  });

  it('shows an uncertain receipt as Unknown until settings and runtime agree', async () => {
    invalidReceipt = true;
    const el = await mount(); switches(el)[0]!.click(); await settle(el);
    expect(posts.map(p => p.rerankerEnabled)).toEqual([false]);
    expect(el.shadowRoot!.querySelector('[data-feature="reranker"]')!.textContent)
      .toContain('Unknown — checking Best match first after an interrupted change');
    window.dispatchEvent(new Event('focus')); await settle(el);
    emit(false); await settle(el);
    expect(el.shadowRoot!.querySelector('[data-feature="reranker"]')!.textContent).toContain('Off — files kept');
  });

  it('refuses a stale second window and converges on its next settings observation', async () => {
    const first = await mount(); const second = await mount();
    switches(first)[0]!.click(); await settle(first);
    switches(second)[0]!.click(); await settle(second);
    expect(posts.map(p => p.witness.acceptedRevision)).toEqual([0, 0]);
    expect(witness.acceptedRevision).toBe(1);
    expect(second.shadowRoot!.textContent).toContain('Reload and review your edit');
    emit(false); window.dispatchEvent(new Event('focus')); await settle(second);
    expect(switches(second).map(b => b.getAttribute('aria-checked'))).toEqual(['false', 'false']);
    expect(second.shadowRoot!.querySelector('[data-feature="reranker"]')!.textContent).toContain('Off — files kept');
  });

  it('accepts and completes a consented enable across installer revisions without a follow-up save', async () => {
    enabled = false; emit(false);
    preview = { totalDownloadBytes: 1024, components: [
      { id: 'embedding', state: 'installed', totalBytes: 4096, downloadBytes: 0 },
      { id: 'reranker', label: 'Ranking model', state: 'to-download', declinable: true, totalBytes: 1024, downloadBytes: 1024 },
    ] };
    const el = await mount(); switches(el)[0]!.click(); await settle(el);
    const dialog = el.shadowRoot!.querySelector('[data-testid="install-consent-dialog"]');
    (dialog!.querySelector('[label="Accept and download"]') as unknown as { onActivate(): void }).onActivate();
    await settle(el);
    expect(operations).toEqual(['core.start-ai-install']);
    witness = { acceptedRevision: 1, lastCommittedOperationKey: 'installer-intermediate-write' };
    // The installer owns the final enable batch at its current revision.
    if ((operationArgs[0] as { enableRerankerOnCompletion?: boolean }).enableRerankerOnCompletion) {
      enabled = true; witness = { acceptedRevision: 2, lastCommittedOperationKey: 'installer-completion' };
    }
    preview.components![1]!.state = 'installed';
    store.current = { ...store.current, installStatus: { state: 'completed', phase: 'done',
      installedFully: true, packages: [{ packageId: 'reranker', state: 'installed' }] } };
    emit(true); window.dispatchEvent(new Event('focus')); await settle(el);
    expect(posts).toEqual([]);
    expect(operationArgs).toEqual([{ acceptTerms: true, enableRerankerOnCompletion: true }]);
    expect(enabled).toBe(true);
    expect(switches(el).map(b => b.getAttribute('aria-checked'))).toEqual(['true', 'true']);
    expect(el.shadowRoot!.querySelectorAll('[data-feature="reranker"] > .step-meta')[1]!.textContent?.trim()).toBe('On');
  });

  it('keeps enabled but unavailable when a completed save has no serving reranker observation', async () => {
    enabled = false; emit(false);
    const el = await mount(); switches(el)[0]!.click(); await settle(el);
    expect(enabled).toBe(true);
    expect(el.shadowRoot!.querySelectorAll('[data-feature="reranker"] > .step-meta')[1]!.textContent?.trim())
      .toBe('Unavailable — Best match first is enabled');
    emit(true); await settle(el);
    expect(el.shadowRoot!.querySelectorAll('[data-feature="reranker"] > .step-meta')[1]!.textContent?.trim()).toBe('On');
  });

  it('consents to every missing selected part and cancellation leaves Off without a transfer', async () => {
    enabled = false; emit(false);
    preview = { totalDownloadBytes: 3072, components: [
      { id: 'embedding', state: 'installed', totalBytes: 4096, downloadBytes: 0 },
      { id: 'reranker', label: 'Ranking model', state: 'to-download', declinable: true, totalBytes: 1024, downloadBytes: 1024 },
      { id: 'ner', label: 'Entity model', state: 'to-download', declinable: true, totalBytes: 2048, downloadBytes: 2048 },
    ] };
    const el = await mount(); switches(el)[0]!.click(); await settle(el);
    const dialog = el.shadowRoot!.querySelector('[data-testid="install-consent-dialog"]');
    expect(el.installConsentOpen).toBe(true);
    expect(dialog?.textContent).toContain('3.0 KB');
    expect([...dialog!.querySelectorAll('.consent-terms li')].map(li => li.textContent?.replace(/\s+/g, ' ').trim()))
      .toEqual(['Ranking model 1.0 KB Apache-2.0 Terms ↗', 'Entity model 2.0 KB MIT Terms ↗']);
    (dialog!.querySelector('[label="Cancel"]') as unknown as { onActivate(): void }).onActivate();
    await settle(el);
    expect(el.installConsentOpen).toBe(false);
    expect(operations).toEqual([]); expect(posts).toEqual([]);
    expect(enabled).toBe(false);
    expect(switches(el).map(b => b.getAttribute('aria-checked'))).toEqual(['false', 'false']);
  });
});
