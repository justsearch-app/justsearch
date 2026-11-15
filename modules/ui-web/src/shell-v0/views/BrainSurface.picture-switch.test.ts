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
let settingsQuarantined: boolean;
let witness: { acceptedRevision: number; lastCommittedOperationKey: string | null };
let preview: InstallPlanPreview;
let posts: Array<{ pictureReadingEnabled: boolean; pictureReadingSwitchIntent?: boolean; witness: typeof witness }>;
let operations: string[];
let operationArgs: unknown[];
let refusal: string | null;
let wait: Promise<void> | null;
let override: boolean | null = null;
let interrupt: 'before' | 'after' | null = null;
const receipts = new Map<string, unknown>();
function emit(active: boolean) {
  store.current = { ...store.current, runtimeStatus: { active: { mmprojActive: active }, onnxFeatures: [{ id: 'reranker',
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
// Slice 7a (D2, AM2): one switch under the step with its state in words; no switch in the sentence.
const word = (el: Brain) => el.shadowRoot!.querySelector('[data-step="pictures"] .switch-state')?.textContent;
function switches(el: Brain) {
  return [...el.shadowRoot!.querySelectorAll<HTMLButtonElement>('[role="switch"][aria-label="Use Text in scans and images"]')];
}
beforeEach(() => {
  interrupt = null; receipts.clear(); settingsQuarantined = false;
  enabled = null; override = null; witness = { acceptedRevision: 0, lastCommittedOperationKey: null };
  posts = []; operations = []; operationArgs = []; refusal = null; wait = null;
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
      if (interrupt === 'before') { interrupt = null; throw new Error('Delivery interrupted'); }
      if (receipts.has(body.operationKey)) return json(receipts.get(body.operationKey));
      if (refusal) return json({ errorCode: 'PICTURE_READING_OVERRIDDEN', error: refusal }, 409);
      if (body.witness.acceptedRevision !== witness.acceptedRevision) return json({ errorCode: 'VERSION_CONFLICT' }, 409);
      enabled = body.pictureReadingEnabled;
      witness = { acceptedRevision: witness.acceptedRevision + 1, lastCommittedOperationKey: body.operationKey };
      const receipt = { state: 'COMPLETE', operationKey: body.operationKey, witness, pictureReadingEnabled: enabled };
      receipts.set(body.operationKey, receipt);
      if (interrupt === 'after') { interrupt = null; throw new Error('Receipt interrupted'); }
      return json(receipt);
    }
    if (path.endsWith('/api/settings/v2') && settingsQuarantined) return json({ error: 'Settings recovery is required before a revision can be read', errorCode: 'SETTINGS_RECOVERY_REQUIRED', retryable: false }, 503);
    if (path.endsWith('/api/settings/v2')) return json({ ui: { chatEnabled: true }, pictureReadingEnabled: enabled, pictureReadingOverrideEnabled: override, witness });
    if (path.endsWith('/plan-preview')) return json(preview);
    if (path.endsWith('/manifest')) return json({ schemaVersion: 2, packages: [
      { id: 'chat', label: 'Answer model', license: 'Apache-2.0', termsUrl: 'https://example.invalid/ranking', variants: [], supportingFiles: [] },
      { id: 'ner', label: 'Entity model', license: 'MIT', termsUrl: 'https://example.invalid/entities', variants: [], supportingFiles: [] },
    ] });
    if (path.endsWith('/api/ai/models/status')) return json({ packages: preview.components?.map(c => ({ id: c.id, complete: c.state === 'installed' })) });
    return json({});
  }));
});
afterEach(() => { document.body.innerHTML = ''; store.listeners.clear(); vi.unstubAllGlobals(); });

describe('picture reading live switch', () => {
  it('shows the unknown saved choice as Not known in its step, never as an unchecked switch, after settings quarantine', async () => {
    settingsQuarantined = true;
    const el = await mount();
    expect(switches(el)).toHaveLength(0);
    const place = el.shadowRoot!.querySelector('[data-step="pictures"]')!;
    expect(word(el)).toBe('Not known');
    expect(place.textContent!.match(/Not known/g)).toHaveLength(1);
    expect(place.textContent).not.toMatch(/\bOn\b|\bOff\b/);
    expect(el.shadowRoot!.querySelector('.brain-sentence')?.textContent).not.toContain('picture');
    expect(posts).toEqual([]);
  });

  it('a saved Off reads Off under its step, unlit', async () => {
    enabled = false; emit(false);
    const el = await mount();
    expect(word(el)).toBe('Off');
    expect(el.shadowRoot!.querySelector('[data-step="pictures"]')!.getAttribute('data-lit')).toBe('false');
    expect(switches(el).map(b => b.getAttribute('aria-checked'))).toEqual(['false']);
  });

  // X2/W2: an unset choice is drawn as the Engine's effective default (On), nothing is written, and the
  // step says why it is not working (W1 note).
  it('answer files not downloaded yet: an unset choice reads On, says its files are missing, unlit', async () => {
    emit(false);
    preview = { totalDownloadBytes: 1024, components: [
      { id: 'chat', label: 'Answer model', state: 'to-download', totalBytes: 1024, downloadBytes: 1024 },
    ] };
    const el = await mount();
    expect(word(el)).toBe('On');
    expect(el.shadowRoot!.querySelector('[data-step="pictures"]')!.textContent).toContain('Not working — its files are missing');
    expect(el.shadowRoot!.querySelector('[data-step="pictures"]')!.getAttribute('data-lit')).toBe('false');
    expect(posts).toEqual([]);
  });

  it('reconciles an interrupted delivery with an unchanged witness using the original attempt', async () => {
    const el = await mount();
    interrupt = 'before';
    switches(el)[0]!.click(); await settle(el);
    expect(witness.acceptedRevision).toBe(0);
    expect(el.shadowRoot!.textContent).toContain('Checking after an interrupted change');
    window.dispatchEvent(new Event('focus')); await settle(el);
    expect(posts).toHaveLength(2);
    expect(posts[1]).toEqual(posts[0]);
    expect(switches(el).map(b => b.getAttribute('aria-checked'))).toEqual(['false']);
    expect(word(el)).toBe('Off');
    expect(el.shadowRoot!.textContent).not.toContain('Checking after an interrupted change');
  });

  it('reconciles an interrupted receipt with a changed witness by replaying the original attempt', async () => {
    const el = await mount();
    interrupt = 'after';
    switches(el)[0]!.click(); await settle(el);
    expect(witness.acceptedRevision).toBe(1);
    expect(el.shadowRoot!.textContent).toContain('Checking after an interrupted change');
    window.dispatchEvent(new Event('focus')); await settle(el);
    expect(posts).toHaveLength(2);
    expect(posts[1]).toEqual(posts[0]);
    expect(witness.acceptedRevision).toBe(1);
    expect(word(el)).toBe('Off');
    expect(el.shadowRoot!.textContent).not.toContain('Checking after an interrupted change');
  });

  it('clears interrupted uncertainty when the original attempt is refused with an unchanged witness', async () => {
    const el = await mount();
    interrupt = 'before';
    switches(el)[0]!.click(); await settle(el);
    expect(el.shadowRoot!.textContent).toContain('Checking after an interrupted change');
    refusal = 'Text in scans and images cannot be switched while using a user-run answer server';
    window.dispatchEvent(new Event('focus')); await settle(el);
    expect(posts).toHaveLength(2);
    expect(posts[1]).toEqual(posts[0]);
    expect(witness.acceptedRevision).toBe(0);
    expect(switches(el).map(b => b.getAttribute('aria-checked'))).toEqual(['true']);
    expect(el.shadowRoot!.textContent).not.toContain('Checking after an interrupted change');
    expect(el.shadowRoot!.textContent).toContain(refusal);
  });

  it('shares both switches and waits for the applied receipt before showing Off', async () => {
    const el = await mount();
    expect(switches(el).map(b => b.getAttribute('aria-checked'))).toEqual(['true']);
    let release!: () => void;
    wait = new Promise(resolve => { release = resolve; });
    switches(el)[0]!.click(); await settle(el);
    expect(posts.map(p => p.pictureReadingEnabled)).toEqual([false]);
    expect(word(el)).toBe('Changing…');
    expect(switches(el).map(b => b.getAttribute('aria-disabled'))).toEqual(['true']);
    expect(switches(el).map(b => b.getAttribute('aria-checked'))).toEqual(['true']);
    (el as unknown as { settleTransients(): void }).settleTransients(); await settle(el);
    expect(word(el)).toBe('Changing…');
    release(); await settle(el);
    expect(switches(el).map(b => b.getAttribute('aria-checked'))).toEqual(['false']);
    expect(word(el)).toBe('Off');
    expect(el.shadowRoot!.querySelector('[data-step="pictures"]')!.getAttribute('data-lit')).toBe('false');
    emit(false); await settle(el);
    switches(el)[0]!.click(); await settle(el);
    expect(posts.map(p => ({ enabled: p.pictureReadingEnabled, intent: p.pictureReadingSwitchIntent })))
      .toEqual([{ enabled: false, intent: true }, { enabled: true, intent: true }]);
    emit(true); await settle(el);
    expect(switches(el).map(b => b.getAttribute('aria-checked'))).toEqual(['true']);
    expect(operations).toEqual([]);
  });

  it('keeps the prior switch on a refused change', async () => {
    refusal = 'Text in scans and images cannot be switched while using a user-run answer server';
    const el = await mount();
    expect(switches(el)).toHaveLength(1);
    switches(el)[0]!.click(); await settle(el);
    expect(posts.map(p => p.pictureReadingEnabled)).toEqual([false]);
    expect(switches(el).map(b => b.getAttribute('aria-checked'))).toEqual(['true']);
    expect(el.shadowRoot!.textContent).toContain(refusal);
  });

  it('keeps the operator state after restart and explains why it cannot switch', async () => {
    enabled = false; override = true;
    store.current = { ...store.current, aiEngine: { ...store.current.aiEngine, kind: 'offline' }, runtime: { mode: 'offline', installed: { known: true, value: true } } } as AiState;
    const el = await mount();
    expect(switches(el).map(b => b.getAttribute('aria-checked'))).toEqual(['true']);
    expect(switches(el).every(b => b.getAttribute('aria-disabled') === 'true')).toBe(true);
    switches(el)[0]!.click(); await settle(el);
    expect(posts).toEqual([]);
    expect(el.shadowRoot!.textContent).toContain('controlled by an operator override');
  });

  it('rejects a stale second window, then refreshes its witness and converges', async () => {
    const first = await mount(); const second = await mount();
    expect(switches(first)).toHaveLength(1); expect(switches(second)).toHaveLength(1);
    switches(first)[0]!.click(); await settle(first);
    switches(second)[0]!.click(); await settle(second);
    expect(posts.map(p => p.witness.acceptedRevision)).toEqual([0, 0]);
    expect(second.shadowRoot!.textContent).toContain('Reload and review your edit');
    emit(false); window.dispatchEvent(new Event('focus')); await settle(second);
    expect(switches(second).map(b => b.getAttribute('aria-checked'))).toEqual(['false']);
    switches(second)[0]!.click(); await settle(second);
    expect(posts.map(p => p.witness.acceptedRevision)).toEqual([0, 0, 1]);
    expect(posts.at(-1)?.pictureReadingEnabled).toBe(true);
    emit(true); window.dispatchEvent(new Event('focus')); await settle(first); await settle(second);
    expect(switches(first).map(b => b.getAttribute('aria-checked'))).toEqual(['true']);
    expect(switches(second).map(b => b.getAttribute('aria-checked'))).toEqual(['true']);
  });

  it('where answers cannot work, says simple scans are read already and offers no switch or download', async () => {
    enabled = false; emit(false);
    store.current = { ...store.current, aiEngine: { ...store.current.aiEngine, kind: 'offline' }, capabilities: { chat: false },
      runtime: { mode: 'offline', installed: { known: true, value: true } } } as AiState;
    preview = { totalDownloadBytes: 0, components: [
      { id: 'chat', state: 'unavailable', declinable: true, unavailableReason: 'No supported GPU' },
    ] };
    const el = await mount();
    expect(switches(el)).toHaveLength(0);
    expect(posts).toEqual([]); expect(operations).toEqual([]);
    expect(el.shadowRoot!.querySelector('[data-step="pictures"]')!.textContent).toContain('Simple scans are read already');
  });

  it('shows the whole download consent and cancel leaves Off without transferring', async () => {
    enabled = false; emit(false);
    preview = { totalDownloadBytes: 3072, components: [
      { id: 'embedding', label: 'Meaning model', state: 'installed', totalBytes: 4096, downloadBytes: 0 },
      { id: 'chat', label: 'Answer model', state: 'to-download', declinable: true, totalBytes: 1024, downloadBytes: 1024 },
      { id: 'ner', label: 'Entity model', state: 'to-download', totalBytes: 2048, downloadBytes: 2048 },
    ] };
    const el = await mount();
    expect(switches(el)).toHaveLength(1);
    switches(el)[0]!.click(); await settle(el);
    const dialog = el.shadowRoot!.querySelector('[data-testid="install-consent-dialog"]');
    expect(dialog?.textContent).toContain('Answer model');
    expect(dialog?.textContent).toContain('Entity model');
    expect(dialog?.textContent).toContain('Apache-2.0');
    expect(dialog?.textContent).toContain('MIT');
    expect(dialog?.textContent).toContain('3.0 KB');
    expect(posts).toEqual([]); expect(operations).toEqual([]);
    (dialog!.querySelector('[label="Cancel"]') as unknown as { onActivate(): void }).onActivate();
    await settle(el);
    expect(switches(el).map(b => b.getAttribute('aria-checked'))).toEqual(['false']);
    expect(posts).toEqual([]); expect(operations).toEqual([]);
    switches(el)[0]!.click(); await settle(el);
    (el.shadowRoot!.querySelector('[label="Accept and download"]') as unknown as { onActivate(): void }).onActivate();
    await settle(el);
    expect(operationArgs).toEqual([{ acceptTerms: true, enablePictureReadingOnCompletion: true }]);
  });
});
