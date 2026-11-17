// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// Slice 7a — AI Brain's Overview as drawn in fm-10 (not installed), fm-13 (installed, no NVIDIA card):
// the heading says what works, each fact stands once, only working steps are lit (S7a-1, -2, -3, -9).
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
import { deriveFrameStatus } from '../../state/frameStatus.js';
import { readFileSync } from 'node:fs';
import { __seedForTest as seedOperations, __resetForTest as resetOperations } from '../../../api/registry/OperationCatalogClient.js';
import { __seedForTest as seedMessages, __resetForTest as resetMessages } from '../../../i18n/resourceCatalog.js';
import { bootstrapAggregateSubstrate, __resetBootstrap } from '../../aggregate-substrate/bootstrap.js';

interface Brain extends HTMLElement { apiBase: string; host_: unknown; updateComplete: Promise<boolean> }
const GB = 1024 ** 3, MB = 1024 ** 2;
const SIZES = { embedding: Math.round(1.2 * GB), reranker: 341 * MB, chat: Math.round(5.7 * GB), splade: 500 * MB, ner: 300 * MB, 'citation-scorer': 200 * MB } as const;
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
const NO_CARD_REASON = 'Chat model requires a CUDA-capable GPU (none detected on this system). CPU chat is not supported in this build.';
const LINUX_REASON = 'Chat model is unavailable: Linux GPU packs have not been published yet.';
let preview: InstallPlanPreview;
let settings: Record<string, unknown>;
let presence: { packages: Array<{ id: string; complete: boolean }> };
let declines: Array<{ id: string; method: string }>;
let mutations: string[];
let operations: string[];
let platform: string;
function component(id: keyof typeof SIZES, state: string, extra: Record<string, unknown> = {}) {
  return { id, label: id, state, declined: state === 'declined', declinable: id !== 'embedding',
    necessity: id === 'embedding' ? 'required' : id === 'chat' ? 'adds-feature' : 'improves-results',
    totalBytes: SIZES[id], downloadBytes: state === 'to-download' ? SIZES[id] : 0, ...extra };
}
function total(p: InstallPlanPreview) {
  return (p.components ?? []).filter(c => c.state === 'to-download' && !c.declined).reduce((sum, c) => sum + (c.downloadBytes ?? 0), 0);
}
function plan(installed: boolean, chat: 'available' | 'no-card' | 'linux') {
  const state = installed ? 'installed' : 'to-download';
  const components = (['embedding', 'reranker', 'splade', 'ner', 'citation-scorer'] as const).map(id => component(id, state));
  components.push(chat === 'available' ? component('chat', state)
    : component('chat', 'unavailable', { downloadBytes: 0, unavailableReason: chat === 'linux' ? LINUX_REASON : NO_CARD_REASON }));
  const p: InstallPlanPreview = { components };
  p.totalDownloadBytes = total(p);
  return p;
}
const live = { phase: 'connected', installObservationLive: true, runtimeObservationLive: true, snapshotLive: true, indexingStatusLive: true,
  episodeMaxPendingJobs: 0, enrichSettleSamples: [], connection: { reachable: true, lastSuccessMs: 1, lastContactMs: 1, consecutiveFailures: 0 },
  activity: { state: 'idle', shapeId: null, startedAtMs: null, canCancel: false, cancel: null }, stability: { kind: 'settled' },
  verdict: { kind: 'operational', severity: 'ok', reasons: [] } };
const noCardStatus = { gpu: { available: false, cudaFunctional: false, deviceCount: 0 }, inference: { chatEnabledSpec: false },
  embedding: { compatState: 'COMPATIBLE' },
  operating: { gpu: { stale: false, adapter: null, profile: 'NO_GPU', source: 'NONE' } },
  worker: { core: { indexState: 'IDLE', pendingJobs: 0, indexedDocuments: 8, searchableDocuments: 8 } } };
function notInstalled(): AiState {
  return { ...live, aiEngine: { kind: 'not_installed', stability: { kind: 'settled' }, installFailure: null, notRequested: true },
    runtime: { mode: 'offline', installed: { known: true, value: false }, gpu: { available: false, description: 'CPU' } },
    capabilities: { chat: false, rag: false, extract: false, embedding: false },
    installStatus: { state: 'idle', installedFully: false }, runtimeStatus: { onnxFeatures: [] },
    index: { ownDocumentCount: { known: true, value: 8 } }, status: noCardStatus } as unknown as AiState;
}
function installedNoCard(embed: Record<string, unknown> = { status: 'active', modelActive: true }): AiState {
  return { ...live, aiEngine: { kind: 'offline', stability: { kind: 'settled' }, installFailure: null },
    runtime: { mode: 'offline', installed: { known: true, value: true }, gpu: { available: false, description: 'CPU' } },
    capabilities: { chat: false, rag: false, extract: false, embedding: true },
    installStatus: { state: 'idle', installedFully: true },
    runtimeStatus: { onnxFeatures: [{ id: 'embed', ...embed }, { id: 'reranker', status: 'inactive', modelActive: false }] },
    index: { ownDocumentCount: { known: true, value: 8 } }, status: noCardStatus } as unknown as AiState;
}
async function settle(el: Brain) {
  for (let i = 0; i < 4; ++i) { await new Promise(resolve => setTimeout(resolve, 0)); await el.updateComplete; }
}
async function mount() {
  const el = document.createElement('jf-brain-surface') as Brain;
  el.apiBase = '';
  el.host_ = createMockHostApi({ data: { invokeOperation: async (id: string) => {
    operations.push(id); return { success: true, structuredData: null };
  } } });
  document.body.append(el); await settle(el); return el;
}
const words = (node: Element | ShadowRoot | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
const panel = (el: Brain) => el.shadowRoot!.querySelector('[data-testid="brain-simple-panel"]');
// Steps are found as the page has always drawn them: the files and read steps by their class, the
// feature steps by data-feature.
const step = (el: Brain, id: string) => el.shadowRoot!.querySelector<HTMLElement>(id === 'files' ? '.files-step' : id === 'read' ? '.read-step' : `.path-step[data-feature="${id}"]`);
const headline = (el: Brain) => words(el.shadowRoot!.querySelector('.brain-headline'));
const sentence = (el: Brain) => el.shadowRoot!.querySelector('.brain-sentence');
function tablist(el: Brain) {
  return el.shadowRoot!.querySelector('jf-surface-tabs')?.shadowRoot?.querySelector('[role="tablist"]') ?? null;
}
function occurrences(haystack: string, needle: string) { return haystack.split(needle).length - 1; }
// Lit is drawn as data-lit on the step (slice 7a) or data-active on its symbol (before it).
const litOf = (node: HTMLElement | null) => node?.dataset.lit ?? node?.querySelector<HTMLElement>('.step-symbol')?.dataset.active ?? 'missing';
function lit(el: Brain) {
  return Object.fromEntries(['files', 'read', 'pictures', 'meaning', 'reranker', 'chat'].map(id => [id, litOf(step(el, id))]));
}

beforeEach(() => {
  setShowDeveloperTools(false);
  platform = 'Win32';
  Object.defineProperty(navigator, 'platform', { configurable: true, get: () => platform });
  preview = plan(false, 'no-card');
  settings = { ui: {}, llm: {}, rerankerEnabled: null, witness: { acceptedRevision: 0, lastCommittedOperationKey: null } };
  presence = { packages: [] };
  declines = []; mutations = []; operations = [];
  store.current = notInstalled();
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (init?.method && init.method !== 'GET' && !path.includes('/decline')) mutations.push(`${init.method} ${path}`);
    if (path.endsWith('/api/settings/v2')) return json(settings);
    if (path.endsWith('/plan-preview')) return json(preview);
    if (path.endsWith('/manifest')) return json({ schemaVersion: 2, packages: (preview.components ?? []).map(c => ({
      id: c.id, label: c.label, license: 'Apache-2.0', termsUrl: 'https://example.invalid/terms', variants: [], supportingFiles: [] })) });
    if (path.endsWith('/api/ai/models/status')) return json(presence);
    if (path.endsWith('/api/ai/install/downloaded-files')) return json({ parts: [] });
    if (path.includes('/decline')) {
      const id = decodeURIComponent(path.split('/').at(-2)!);
      declines.push({ id, method: init?.method ?? 'GET' });
      preview = { ...preview, components: preview.components!.map(c => c.id === id
        ? { ...c, declined: init?.method === 'POST', state: init?.method === 'POST' ? 'declined' : 'to-download' } : c) };
      preview.totalDownloadBytes = total(preview);
      return json({});
    }
    return json({});
  }));
});
afterEach(() => {
  document.body.innerHTML = ''; store.listeners.clear(); vi.unstubAllGlobals();
  delete (navigator as unknown as Record<string, unknown>).platform;
});

describe('S7a-1 not installed (fm-10)', () => {
  it('says the picture’s words, lights only the steps that work, and has one filled action', async () => {
    const el = await mount();
    expect(panel(el), 'Brain panel').not.toBeNull();
    expect(headline(el)).toBe("AI isn't installed yet");
    expect(sentence(el), 'lead sentence').not.toBeNull();
    expect(words(sentence(el))).toBe('Search already works with exact words. Installing AI adds the steps that are not lit yet.');
    expect(sentence(el)!.querySelectorAll('button, [role="switch"]')).toHaveLength(0);
    const filled = [...panel(el)!.querySelectorAll('jf-button')].filter(b => b.getAttribute('variant') === 'primary');
    expect(filled.map(b => b.getAttribute('label'))).toEqual(['Install AI · 2.5 GB']);
    expect(words(panel(el))).toContain('You see each licence before anything downloads. Everything runs on this computer.');
    expect(lit(el)).toEqual({ files: 'true', read: 'true', pictures: 'false', meaning: 'false', reranker: 'false', chat: 'false' });
    expect(words(step(el, 'files'))).toContain('8 files');
    expect(words(step(el, 'read'))).toContain('Built in');
    expect(words(step(el, 'pictures'))).toContain('Simple scans are read already');
    expect(step(el, 'pictures')!.querySelectorAll('[role="switch"]')).toHaveLength(0);
    expect(step(el, 'meaning')!.querySelectorAll('[role="switch"]')).toHaveLength(0);
    expect(words(step(el, 'meaning'))).toContain('1.2 GB');
    expect(words(step(el, 'reranker'))).toContain('341 MB');
    const included = step(el, 'reranker')!.querySelectorAll<HTMLElement>('[role="switch"]');
    expect(included).toHaveLength(1);
    expect(included[0]!.getAttribute('aria-checked')).toBe('true');
    expect(words(step(el, 'reranker')!.querySelector('.switch-state'))).toBe('Included');
  });

  it('draws answers as not reachable here and says why once, in ordinary words', async () => {
    const el = await mount();
    expect(words(el.shadowRoot!)).not.toMatch(/CUDA|answers unavailable on this computer/i);
    expect(occurrences(words(el.shadowRoot!), 'Needs an NVIDIA graphics card')).toBe(1);
    const chat = step(el, 'chat');
    expect(chat, 'Answer questions step').not.toBeNull();
    expect(chat!.dataset.reach).toBe('na');
    expect(chat!.querySelectorAll('[role="switch"]')).toHaveLength(0);
    expect(words(chat)).toContain('Needs an NVIDIA graphics card');
    expect(words(chat)).not.toContain('5.7 GB');
    expect(tablist(el)).toBeNull();
  });

  it('on Linux, says the platform exclusion once without the planner’s technical words', async () => {
    platform = 'Linux x86_64'; preview = plan(false, 'linux');
    const el = await mount();
    expect(words(el.shadowRoot!)).not.toContain(LINUX_REASON);
    expect(words(el.shadowRoot!)).not.toMatch(/GPU packs|CUDA/);
    const chat = step(el, 'chat');
    expect(chat, 'Answer questions step').not.toBeNull();
    expect(chat!.dataset.reach).toBe('na');
    const reason = words(chat!.querySelector('.step-reason'));
    expect(reason).not.toBe('');
    expect(reason).not.toMatch(/GPU packs|CUDA|runtime|build|published/i);
    expect(reason).not.toBe('Needs an NVIDIA graphics card');
    expect(occurrences(words(el.shadowRoot!), reason)).toBe(1);
  });

  it('Included and Not included say the choice under its step; it changes only the decline and the plan total, and survives page return and restart', async () => {
    let el = await mount();
    expect(words(step(el, 'reranker')?.querySelector('.switch-state'))).toBe('Included');
    const included = step(el, 'reranker')?.querySelector<HTMLElement>('[role="switch"]');
    expect(included, 'Included switch').toBeTruthy();
    included!.click(); await settle(el);
    expect(declines).toEqual([{ id: 'reranker', method: 'POST' }]);
    expect(mutations).toEqual([]); expect(operations).toEqual([]);
    expect(step(el, 'reranker')!.querySelector('[role="switch"]')!.getAttribute('aria-checked')).toBe('false');
    expect(words(step(el, 'reranker')!.querySelector('.switch-state'))).toBe('Not included');
    const after = 'Install AI · 2.2 GB';
    expect([...panel(el)!.querySelectorAll('jf-button')].map(b => b.getAttribute('label'))).toContain(after);
    el.remove(); document.body.append(el); await settle(el);
    expect(step(el, 'reranker')!.querySelector('[role="switch"]')!.getAttribute('aria-checked')).toBe('false');
    el.remove(); el = await mount();
    expect(step(el, 'reranker')!.querySelector('[role="switch"]')!.getAttribute('aria-checked')).toBe('false');
    expect([...panel(el)!.querySelectorAll('jf-button')].map(b => b.getAttribute('label'))).toContain(after);
    step(el, 'reranker')!.querySelector<HTMLElement>('[role="switch"]')!.click(); await settle(el);
    expect(declines).toEqual([{ id: 'reranker', method: 'POST' }, { id: 'reranker', method: 'DELETE' }]);
    expect(words(step(el, 'reranker')!.querySelector('.switch-state'))).toBe('Included');
    expect(mutations).toEqual([]); expect(operations).toEqual([]);
  });
});

describe('S7a-2 installed, meaning works, answers unavailable (fm-13)', () => {
  beforeEach(() => {
    preview = plan(true, 'no-card'); settings = { ...settings, rerankerEnabled: false };
    presence = { packages: ['embedding', 'reranker'].map(id => ({ id, complete: true })) };
    store.current = installedNoCard();
  });
  it('heads with what works, draws Off as off, and has no tab row', async () => {
    const el = await mount();
    expect(headline(el)).toBe('Search understands meaning');
    expect(words(sentence(el))).toBe('It finds files by what they are about, not only by their words.');
    expect(words(el.shadowRoot!)).not.toMatch(/AI Offline|Installed with limitations/);
    expect(lit(el)).toEqual({ files: 'true', read: 'true', pictures: 'false', meaning: 'true', reranker: 'false', chat: 'false' });
    const reranker = step(el, 'reranker')!.querySelectorAll('[role="switch"]');
    expect(reranker).toHaveLength(1);
    expect(reranker[0]!.getAttribute('aria-checked')).toBe('false');
    expect(words(step(el, 'reranker')!.querySelector('.switch-state'))).toBe('Off');
    expect(step(el, 'chat')!.dataset.reach).toBe('na');
    expect(occurrences(words(el.shadowRoot!), 'Needs an NVIDIA graphics card')).toBe(1);
    expect(words(step(el, 'pictures'))).toContain('Simple scans are read already');
    expect(tablist(el)).toBeNull();
    expect(el.shadowRoot!.querySelector('.graphics-card')).toBeNull();
    expect([...panel(el)!.querySelectorAll('jf-button')].filter(b => b.getAttribute('variant') === 'primary')).toHaveLength(0);
  });

  it('a lost observation is said once by the heading, not again under every step; an unknown choice stays Not known', async () => {
    settings = { ...settings, witness: undefined };
    const el = await mount();
    store.current = { ...store.current, snapshotLive: false };
    for (const listener of store.listeners) listener(store.current);
    await settle(el);
    expect(headline(el)).toBe('Checking AI status…');
    expect(words(el.shadowRoot!)).not.toContain('Unknown — checking AI status');
    for (const id of ['files', 'read', 'pictures', 'meaning', 'reranker', 'chat']) {
      expect(words(step(el, id)), `${id} step`).not.toMatch(/checking AI status/i);
    }
    expect(words(step(el, 'reranker')!.querySelector('.switch-state'))).toBe('Not known');
  });
});

describe('S7a-3 meaning not working keeps its remedy and claims nothing', () => {
  beforeEach(() => {
    preview = plan(true, 'no-card');
    presence = { packages: ['embedding', 'reranker'].map(id => ({ id, complete: true })) };
  });
  const positive = /Search understands meaning|AI is ready/;
  it.each([
    ['inactive', { status: 'inactive', modelActive: false }],
    ['unknown', { status: 'unknown', modelActive: false }],
  ])('embedding %s: no positive heading, no "AI Offline", meaning not lit', async (_name, embed) => {
    store.current = installedNoCard(embed);
    const el = await mount();
    expect(headline(el)).not.toMatch(/AI Offline|Installed with limitations/);
    expect(headline(el)).not.toMatch(positive);
    expect(step(el, 'meaning'), 'meaning step').not.toBeNull();
    expect(litOf(step(el, 'meaning'))).toBe('false');
  });
  // X1 — meaning works only while the Worker accepts meaning queries (embedding compatState COMPATIBLE).
  it('meaning being read again (REBUILDING): says so in read words, meaning not lit, no positive heading', async () => {
    store.current = { ...installedNoCard(), status: { ...noCardStatus, embedding: { compatState: 'REBUILDING' } } } as unknown as AiState;
    const el = await mount();
    expect(headline(el)).not.toMatch(positive);
    expect(headline(el), 'the rebuilding state in read words').toMatch(/\bread/i);
    expect(words(panel(el))).not.toMatch(/index|rebuil|semantic|embedding/i);
    expect(step(el, 'meaning'), 'meaning step').not.toBeNull();
    expect(litOf(step(el, 'meaning'))).toBe('false');
  });
  it('meaning compatibility not reported: no positive heading, meaning not lit', async () => {
    store.current = { ...installedNoCard(), status: { ...noCardStatus, embedding: {} } } as unknown as AiState;
    const el = await mount();
    expect(headline(el)).not.toMatch(positive);
    expect(headline(el)).not.toMatch(/AI Offline|Installed with limitations/);
    expect(litOf(step(el, 'meaning'))).toBe('false');
  });
  it('activation required keeps its heading', async () => {
    store.current = { ...installedNoCard(), installStatus: { state: 'idle', installedFully: true, phase: 'activation_required' } } as unknown as AiState;
    const el = await mount();
    expect(litOf(step(el, 'meaning'))).toBe('false');
    expect(headline(el)).toBe('Downloaded — activation required');
  });
  it('incompatible embeddings keep a read-again remedy, in read words', async () => {
    store.current = { ...installedNoCard(), status: { ...noCardStatus, embedding: { compatState: 'BLOCKED_MISMATCH' } } } as unknown as AiState;
    const el = await mount();
    expect(headline(el)).not.toMatch(/index|rebuild/i);
    expect(headline(el)).not.toMatch(positive);
    expect(litOf(step(el, 'meaning'))).toBe('false');
    expect(el.shadowRoot!.querySelector('jf-operation[operation-id="core.rebuild-index"]')).not.toBeNull();
    expect(words(el.shadowRoot!)).not.toContain('Rebuild the index');
  });
  it('answers working while meaning is not makes no positive claim', async () => {
    preview = plan(true, 'available');
    presence = { packages: ['embedding', 'reranker', 'chat'].map(id => ({ id, complete: true })) };
    store.current = { ...installedNoCard({ status: 'inactive', modelActive: false }),
      aiEngine: { kind: 'online', stability: { kind: 'settled' }, installFailure: null },
      capabilities: { chat: true, rag: true, extract: true, embedding: false } } as unknown as AiState;
    const el = await mount();
    expect(headline(el)).not.toMatch(positive);
    expect(litOf(step(el, 'meaning'))).toBe('false');
    expect(litOf(step(el, 'chat'))).toBe('true');
  });
});

describe('S7a-9 sizes and words', () => {
  it('writes package sizes as the amount alone, never as measured disk use, and never leads with a separator', async () => {
    preview = plan(true, 'available');
    presence = { packages: ['embedding', 'reranker', 'chat'].map(id => ({ id, complete: true })) };
    store.current = { ...installedNoCard(), aiEngine: { kind: 'online', stability: { kind: 'settled' }, installFailure: null },
      capabilities: { chat: true, rag: true, extract: true, embedding: true },
      runtimeStatus: { active: { chatProfile: 'standard' }, onnxFeatures: [{ id: 'embed', status: 'active', modelActive: true }] } } as unknown as AiState;
    const el = await mount();
    for (const [id, bytes] of [['meaning', '1.2 GB'], ['reranker', '341 MB'], ['chat', '5.7 GB']] as const) {
      const size = [...(step(el, id)?.querySelectorAll('.step-meta') ?? [])].map(words).find(t => t.includes(bytes));
      expect(size, `${id} size`).toBeTruthy();
      expect(size).toBe(bytes);
      expect(size).not.toMatch(/on disk|registry|footprint/i);
    }
    const metas = [...el.shadowRoot!.querySelectorAll('.path-step .step-meta')].map(words);
    expect(metas.length).toBeGreaterThan(0);
    expect(metas.filter(t => /^[·\-—,]/.test(t))).toEqual([]);
    expect(words(el.shadowRoot!)).not.toMatch(/registry footprint/);
  });
  it.each([
    ['compact answer model', { active: { chatProfile: 'compact' } }, {}],
    ['custom answer model', {}, { modelPath: 'D:/models/mine.gguf' }],
    // X5 — an operator's model: no saved model path here, and the runtime names no profile (null is not "standard").
    ['operator-selected answer model', { active: { chatProfile: null, modelPath: 'D:/operator/answer.gguf' } }, {}],
  ])('omits the answer size for a %s', async (_name, runtimeStatus, llm) => {
    preview = plan(true, 'available'); settings = { ...settings, llm };
    presence = { packages: ['embedding', 'reranker', 'chat'].map(id => ({ id, complete: true })) };
    store.current = { ...installedNoCard(), aiEngine: { kind: 'online', stability: { kind: 'settled' }, installFailure: null },
      capabilities: { chat: true, rag: true, extract: true, embedding: true },
      runtimeStatus: { ...runtimeStatus, onnxFeatures: [{ id: 'embed', status: 'active', modelActive: true }] } } as unknown as AiState;
    const el = await mount();
    expect(words(el.shadowRoot!)).not.toContain('5.7 GB');
    expect(step(el, 'chat'), 'Answer questions step').not.toBeNull();
    expect(words(step(el, 'meaning'))).toContain('1.2 GB');
  });
  it('keeps one kept-files wording at most and no CUDA reason on the default page', async () => {
    preview = plan(true, 'no-card'); settings = { ...settings, rerankerEnabled: false };
    presence = { packages: ['embedding', 'reranker'].map(id => ({ id, complete: true })) };
    store.current = installedNoCard();
    const el = await mount();
    expect(words(el.shadowRoot!)).not.toMatch(/CUDA/);
    expect(occurrences(words(el.shadowRoot!).toLowerCase(), 'kept')).toBeLessThanOrEqual(1);
    expect(litOf(step(el, 'reranker'))).toBe('false');
  });
  it.each([
    ['answers wait while files are read', { aiEngine: { kind: 'indexing', stability: { kind: 'settled' }, installFailure: null, awaitingChatEnable: false } }],
    ['answers are off while files are read', { aiEngine: { kind: 'indexing', stability: { kind: 'settled' }, installFailure: null, awaitingChatEnable: true } }],
    ['answers paused while files are read', { inference: { answersPausedWhileIndexing: true } }],
    ['search must read files again', { status: { ...noCardStatus, embedding: { compatState: 'BLOCKED_LEGACY' } } }],
  ])('%s: read and answers words, never indexing ones', async (_name, patch) => {
    preview = plan(true, 'available');
    presence = { packages: ['embedding', 'reranker', 'chat'].map(id => ({ id, complete: true })) };
    store.current = { ...installedNoCard(), capabilities: { chat: false, rag: false, extract: false, embedding: true }, ...patch } as unknown as AiState;
    const el = await mount();
    expect(words(el.shadowRoot!)).not.toMatch(/indexing|Resume Chat AI|Rebuild the index|Chat AI/i);
    expect(step(el, 'chat'), 'Answer questions step').not.toBeNull();
  });
});

// X7 — a populated reason code and the rebuild action speak read words on Brain (owner: "the app reads
// files (not \"indexes\")"); the action's served label is the catalog's.
describe('S7a-9 search data that must be read again, with its reason codes', () => {
  const properties = readFileSync('../app-api/src/main/resources/messages/registry-operation.en.properties', 'utf8');
  const messages = Object.fromEntries(properties.split('\n').filter(line => line.includes('=')).map(line => {
    const at = line.indexOf('='); return [line.slice(0, at), line.slice(at + 1).trim()];
  }));
  beforeEach(() => {
    resetOperations(); resetMessages(); __resetBootstrap(); bootstrapAggregateSubstrate();
    seedMessages(messages);
    seedOperations({ schemaVersion: '1.0', catalogVersion: 1, namespace: 'core', primitive: 'Operation', entries: [{
      id: 'core.rebuild-index', presentation: { labelKey: 'ops.rebuild-index.label', descriptionKey: 'ops.rebuild-index.description', iconHint: null, category: null },
      intf: { errors: [], inputs: {}, result: {}, uiHints: {} },
      policy: { risk: 'HIGH', confirm: { kind: 'TYPED', confirmTextKey: 'ops.rebuild-index.confirm' }, audit: 'METADATA_ONLY', undoSupported: false },
      availability: {}, lineage: { affects: [], supersedes: [] }, provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' },
      executors: ['UI'], audience: 'USER', consumers: [] }] } as never);
    preview = plan(true, 'no-card'); presence = { packages: ['embedding', 'reranker'].map(id => ({ id, complete: true })) };
  });
  afterEach(() => { resetOperations(); resetMessages(); __resetBootstrap(); });
  const deepWords = (root: Element | ShadowRoot): string => [...root.childNodes].map(node => node.nodeType === Node.TEXT_NODE ? node.textContent ?? ''
    : node instanceof Element ? `${node.shadowRoot ? deepWords(node.shadowRoot) : ''} ${deepWords(node)}` : '').join(' ').replace(/\s+/g, ' ').trim();
  it.each([
    ['index.embedding_mismatch', { embedding: { compatState: 'BLOCKED_MISMATCH' } }],
    ['index.embedding_legacy', { embedding: { compatState: 'BLOCKED_LEGACY' } }],
    ['index.blocked_legacy', { embedding: { compatState: 'BLOCKED_LEGACY' } }],
    ['index.rebuild_brake_exhausted', { embedding: { compatState: 'BLOCKED_MISMATCH' } }],
    ['index.rebuild_brake_unreadable', { embedding: { compatState: 'BLOCKED_MISMATCH' } }],
    ['index.schema_mismatch', { embedding: { compatState: 'COMPATIBLE' }, schema: { compatState: 'BLOCKED_MISMATCH', reindexRequiredReason: 'schema_mismatch' } }],
  ])('%s: the callout says it in read words and its action reads Rebuild all search data', async (code, status) => {
    store.current = { ...installedNoCard(), status: { ...noCardStatus, ...status },
      verdict: { kind: 'degraded', severity: 'warn', reasons: [code] } } as unknown as AiState;
    const el = await mount();
    const callout = el.shadowRoot!.querySelector('.warning-section');
    expect(callout, 'compatibility callout').not.toBeNull();
    const headings = [...callout!.querySelectorAll('.warning-heading')].map(words);
    expect(headings).toHaveLength(1);
    expect(headings[0], 'reason in read words').toMatch(/\bread/i);
    expect(headings[0]).not.toMatch(/index|rebuil|semantic|embedding/i);
    if (code.startsWith('index.embedding') || code === 'index.blocked_legacy') expect(headings[0]).toBe('Read everything again to restore full search.');
    const action = callout!.querySelector('jf-operation[operation-id="core.rebuild-index"]');
    expect(action, 'rebuild action').not.toBeNull();
    for (let i = 0; i < 3; ++i) { await new Promise(resolve => setTimeout(resolve, 0)); }
    expect(deepWords(action!)).toBe('Rebuild all search data');
    expect(deepWords(el.shadowRoot!)).not.toMatch(/Rebuild search index|rebuild the index/i);
  });
});

describe('S7a-9 the page heading and the status panel agree', () => {
  const statusRows = (state: AiState) => {
    const model = deriveFrameStatus({ ai: state, now: 2, contactLostAt: null, gettingStarted: { visible: false, known: true, currentStep: 3 } });
    return Object.values(model.sections).flat();
  };
  it('a positive heading never stands beside a search problem; install rows and headings say the same thing', async () => {
    preview = plan(true, 'no-card'); presence = { packages: ['embedding', 'reranker'].map(id => ({ id, complete: true })) };
    store.current = installedNoCard();
    let el = await mount();
    expect(headline(el)).toBe('Search understands meaning');
    expect(statusRows(store.current).filter(row => ['search-limited', 'search-reduced', 'indexing'].includes(row.id))).toEqual([]);
    el.remove();
    store.current = { ...installedNoCard(), aiEngine: { kind: 'install_failed', stability: { kind: 'settled' }, installFailure: 'disk full', installFailureCode: 'INSTALL_IO_ERROR' },
      installStatus: { state: 'failed', installedFully: false, lastError: 'disk full', errorCode: 'INSTALL_IO_ERROR' } } as unknown as AiState;
    el = await mount();
    const failed = statusRows(store.current).find(row => row.id === 'ai-install-failed');
    expect(failed, 'status row').toBeTruthy();
    expect(headline(el)).toBe(failed!.title);
    expect(headline(el), 'the base failure heading words (D3)').toBe('AI install failed');
  });
});
