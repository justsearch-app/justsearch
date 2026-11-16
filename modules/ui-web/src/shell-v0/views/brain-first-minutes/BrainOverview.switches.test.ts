// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// Slice 7a (S7a-4, AM2) — each switch sits under its step and shows the stored choice only; the step's
// light and words show what works now. Unknown choices read "Not known", unset answers stay unset.
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
const allSwitches = (el: Brain, label: string) => [...el.shadowRoot!.querySelectorAll<HTMLButtonElement>(`[role="switch"][aria-label="Use ${label}"]`)];
function stepSwitch(el: Brain, id: string) { return step(el, id)?.querySelector<HTMLButtonElement>('[role="switch"]') ?? null; }
const stateWord = (el: Brain, id: string) => words(step(el, id)?.querySelector('.switch-state'));

beforeEach(() => {
  setShowDeveloperTools(false);
  saved = { rerankerEnabled: true, pictureReadingEnabled: true, chatEnabled: true };
  witness = { acceptedRevision: 0, lastCommittedOperationKey: null };
  posts = []; operations = []; answerFiles = true; settingsFails = false; settingsGets = 0; heldPost = null; invalidReceipt = false; heldGets = null;
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
    if (path.endsWith('/plan-preview')) return json(preview);
    if (path.endsWith('/manifest')) return json({ schemaVersion: 2, packages: [] });
    if (path.endsWith('/api/ai/models/status')) return json({ packages: ['embedding', 'reranker', 'chat'].map(id => ({ id, complete: id !== 'chat' || answerFiles })) });
    if (path.endsWith('/api/ai/install/downloaded-files')) return json({ parts: [] });
    return json({});
  }));
});
afterEach(() => { document.body.innerHTML = ''; store.listeners.clear(); vi.unstubAllGlobals(); });

describe('S7a-4 one switch per feature, under its step', () => {
  it('has exactly one switch for each choice, none in the sentence, each with its state in words', async () => {
    const el = await mount();
    for (const [id, label] of [['reranker', 'Best match first'], ['chat', 'Answer questions'], ['pictures', 'Text in scans and images']] as const) {
      expect(allSwitches(el, label), label).toHaveLength(1);
      expect(stepSwitch(el, id), `${label} under its step`).toBe(allSwitches(el, label)[0]);
      expect(stateWord(el, id)).toBe('On');
    }
    expect(el.shadowRoot!.querySelectorAll('.brain-sentence [role="switch"], .brain-sentence button')).toHaveLength(0);
  });

  it('saved Off while the runtime still runs it shows Off, lit by what works', async () => {
    saved.rerankerEnabled = false;
    const el = await mount();
    expect(stepSwitch(el, 'reranker'), 'Best match first switch').not.toBeNull();
    expect(stepSwitch(el, 'reranker')!.getAttribute('aria-checked')).toBe('false');
    expect(stateWord(el, 'reranker')).toBe('Off');
    expect(litOf(step(el, 'reranker'))).toBe('true');
  });

  it('saved On while the runtime does not run it shows On, and the step says it is not working', async () => {
    store.current = ready(false);
    const el = await mount();
    expect(stepSwitch(el, 'reranker'), 'Best match first switch').not.toBeNull();
    expect(stepSwitch(el, 'reranker')!.getAttribute('aria-checked')).toBe('true');
    expect(stateWord(el, 'reranker')).toBe('On');
    expect(litOf(step(el, 'reranker'))).toBe('false');
    expect(words(step(el, 'reranker')!.querySelector('.step-note'))).toMatch(/not working/i);
  });

  it('unknown saved choices read "Not known" instead of an unchecked switch, with a refresh reachable', async () => {
    settingsFails = true;
    const el = await mount();
    for (const id of ['reranker', 'chat', 'pictures']) {
      expect(stepSwitch(el, id), `${id}: no unchecked switch for an unknown choice`).toBeNull();
      expect(stateWord(el, id), id).toBe('Not known');
    }
    const refresh = [...el.shadowRoot!.querySelectorAll<HTMLElement & { label?: string }>('jf-button')].find(b => b.label === 'Refresh status');
    expect(refresh, 'refresh route without developer tools').toBeTruthy();
    const before = settingsGets; settingsFails = false;
    const control = refresh!.shadowRoot!.querySelector<HTMLElement & { updateComplete: Promise<boolean> }>('jf-control')!;
    await control.updateComplete; control.shadowRoot!.querySelector('button')!.click(); await settle(el);
    expect(settingsGets).toBeGreaterThan(before);
    expect(stateWord(el, 'reranker')).toBe('On');
  });

  it('unset answers read Automatic and nothing is written', async () => {
    saved.chatEnabled = null;
    const el = await mount();
    expect(stateWord(el, 'chat')).toBe('Automatic');
    expect(stepSwitch(el, 'chat'), 'Answer questions switch').not.toBeNull();
    expect(operations).toEqual([]); expect(posts).toEqual([]);
  });

  // X2/W2 — unset picture reading is drawn as the Engine's effective default (On), nothing is written,
  // and the step says why it is not working when the answer model's files are missing (W1 note).
  it('an unset picture choice with the answer files missing is drawn On, says its files are missing, and writes nothing', async () => {
    saved.pictureReadingEnabled = null; answerFiles = false;
    store.current = { ...ready(), aiEngine: { kind: 'offline', stability: { kind: 'settled' }, installFailure: null },
      runtime: { mode: 'offline', modelLabel: 'Answer model', installed: { known: true, value: true }, gpu: { available: true, description: 'CUDA' } },
      capabilities: { chat: false, rag: false, extract: false, embedding: true },
      runtimeStatus: { active: null, onnxFeatures: [{ id: 'embed', status: 'active', modelActive: true }, { id: 'reranker', status: 'active', modelActive: true }] } } as unknown as AiState;
    const el = await mount();
    expect(stepSwitch(el, 'pictures'), 'picture switch under its step').not.toBeNull();
    expect(stepSwitch(el, 'pictures')!.getAttribute('aria-checked')).toBe('true');
    expect(words(step(el, 'pictures'))).toContain('Not working — its files are missing');
    expect(allSwitches(el, 'Text in scans and images'), 'one picture switch').toHaveLength(1);
    expect(stateWord(el, 'pictures')).toBe('On');
    expect(posts).toEqual([]); expect(operations).toEqual([]);
  });

  it('an operator override is explained and locks the switch at the override', async () => {
    saved.pictureReadingOverrideEnabled = false; saved.pictureReadingEnabled = true;
    const el = await mount();
    const control = stepSwitch(el, 'pictures');
    expect(control, 'picture switch').not.toBeNull();
    expect(control!.getAttribute('aria-checked')).toBe('false');
    expect(control!.getAttribute('aria-disabled')).toBe('true');
    const described = (control!.getAttribute('aria-describedby') ?? '').split(/\s+/).filter(Boolean)
      .map(id => words(el.shadowRoot!.getElementById(id))).join(' ');
    expect(described).toMatch(/operator|administrator/i);
    expect(stateWord(el, 'pictures')).toBe('Off');
  });

  it('an interrupted change reads "Not known" until it is checked', async () => {
    invalidReceipt = true;
    const el = await mount();

    expect(stepSwitch(el, 'reranker'), 'Best match first switch').not.toBeNull();
    stepSwitch(el, 'reranker')!.click(); await settle(el);
    expect(posts.map(p => p.rerankerEnabled)).toEqual([false]);
    expect(stateWord(el, 'reranker')).toBe('Not known');
    expect(stepSwitch(el, 'reranker')).toBeNull();
  });

  it('a pending change stays locked and says so across navigation', async () => {
    let release!: () => void; heldPost = new Promise(resolve => { release = resolve; });
    const el = await mount();
    expect(stepSwitch(el, 'reranker'), 'Best match first switch').not.toBeNull();
    stepSwitch(el, 'reranker')!.click(); await settle(el);
    expect(stateWord(el, 'reranker')).toBe('Changing…');
    el.remove(); document.body.append(el); await settle(el);
    expect(stepSwitch(el, 'reranker'), 'Best match first switch').not.toBeNull();
    expect(stepSwitch(el, 'reranker')!.getAttribute('aria-disabled')).toBe('true');
    expect(stateWord(el, 'reranker')).toBe('Changing…');
    stepSwitch(el, 'reranker')!.click(); await settle(el);
    expect(posts).toHaveLength(1);
    release(); await settle(el);
    expect(stateWord(el, 'reranker')).toBe('Off');
  });

  it('another window’s choice arrives, and a delayed older read does not undo it', async () => {
    const el = await mount();
    expect(stepSwitch(el, 'reranker')?.getAttribute('aria-checked')).toBe('true');
    heldGets = [];
    window.dispatchEvent(new Event('focus')); await settle(el);
    const older = heldGets.splice(0);
    saved.rerankerEnabled = false; witness = { acceptedRevision: 1, lastCommittedOperationKey: '01993ba0-0000-7000-8000-000000000002' };
    heldGets = null;
    window.dispatchEvent(new Event('focus')); await settle(el);
    expect(stepSwitch(el, 'reranker')?.getAttribute('aria-checked'), 'the saved choice, not the running feature').toBe('false');
    expect(stateWord(el, 'reranker')).toBe('Off');
    saved.rerankerEnabled = true;
    for (const resolve of older) resolve(new Response());
    await settle(el);
    expect(stepSwitch(el, 'reranker')!.getAttribute('aria-checked')).toBe('false');
    expect(stateWord(el, 'reranker')).toBe('Off');
  });
});
