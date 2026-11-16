// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// Slice 7b — AI Brain's install flow (fm-11 consent, fm-12 progress, fm-12b a stopped download) and the
// "not installed" classification after a first install that stopped (G6'). The install statuses, model
// presence and plan previews are the ones the Engine published in InstallFlowStatusLoopbackTest
// (modules/app-services): the real install service, curl transport, resume records and placement
// against a loopback server. Elements are found by role, accessible name and visible text.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import type { AiState } from '../../state/aiStateStore.js';
import { computeAiEngineVerdict } from '../../state/aiVerdict.js';
import type { InstallPlanPreview } from '../../utils/aiInstallPoll.js';
const store = vi.hoisted(() => ({ listeners: new Set<(state: AiState) => void>(), current: {} as AiState }));
const toasts = vi.hoisted(() => [] as string[]);
vi.mock('../../state/aiStateStore.js', async original => ({
  ...await original<typeof import('../../state/aiStateStore.js')>(),
  subscribeAiState: (listener: (state: AiState) => void) => {
    store.listeners.add(listener); listener(store.current);
    return () => store.listeners.delete(listener);
  },
  getAiState: () => store.current,
}));
vi.mock('../../components/advisory/ephemeralToast.js', async original => ({
  ...await original<typeof import('../../components/advisory/ephemeralToast.js')>(),
  emitEphemeralToast: (toast: { message: string }) => { toasts.push(toast.message); },
}));
import '../BrainSurface.js';
import { createMockHostApi } from '../../plugin-api/testHostApi.js';
import { setShowDeveloperTools } from '../../state/developerToolsState.js';
import * as installFlow from '../../state/installFlow.js';
// The window's "Cancel install" of a stopped run starts empty in each test.
const resetStoppedRun = () => (installFlow as { __resetStoppedRunForTest?: () => void }).__resetStoppedRunForTest?.();

type Recorded = { status?: Record<string, unknown>; presence?: { packages: Array<Record<string, unknown>> }; preview?: InstallPlanPreview };
const ENGINE = JSON.parse(readFileSync(path.resolve('src/shell-v0/views/brain-install/__fixtures__/engine-install-statuses.json'), 'utf8')) as Record<string, Recorded>;
interface Brain extends HTMLElement { apiBase: string; host_: unknown; updateComplete: Promise<boolean> }
interface Control extends HTMLElement { updateComplete: Promise<boolean>; label?: string }
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
const PARTS = ['embedding', 'splade', 'reranker', 'ner', 'citation-scorer'];
// The manifest the Engine serves for the shipped registry: each part's licence and terms page.
const LICENCE: Record<string, string> = { embedding: 'Apache-2.0', splade: 'Apache-2.0', reranker: 'Apache-2.0', ner: 'AFL-3.0', 'citation-scorer': 'Apache-2.0' };
let manifest: unknown;
let preview: InstallPlanPreview | null;
let presence: unknown;
let operations: Array<{ id: string; args: unknown }>;
let posts: string[];

/** The page's store state for one recorded Engine status, with the verdict the store computes from it. */
function engineState(key: string, status = ENGINE[key]!.status!): AiState {
  const install = { ...status, updatedAtEpochMs: Date.now() } as never;
  const runtime = { mode: 'offline', installed: { known: true, value: false } };
  const aiEngine = computeAiEngineVerdict({ installStatus: install, runtimeStatus: { onnxFeatures: [] } as never, runtime: runtime as never,
    reachable: true, snapshotLive: true, engineState: 'Down' });
  return { phase: 'connected', installObservationLive: true, runtimeObservationLive: true, snapshotLive: true,
    index: { ownDocumentCount: { known: true, value: 8 } }, capabilities: { chat: false }, runtimeStatus: { onnxFeatures: [] },
    aiEngine, runtime, installStatus: install } as unknown as AiState;
}
function show(key: string) {
  const recorded = ENGINE[key]!;
  if (recorded.presence) presence = recorded.presence;
  if (recorded.preview) preview = recorded.preview;
  if (recorded.status) store.current = engineState(key);
}
function publish(key: string) {
  show(key);
  for (const listener of store.listeners) listener(store.current);
}
async function settle(el: Brain) {
  for (let i = 0; i < 8; ++i) { await new Promise(resolve => setTimeout(resolve, 0)); await el.updateComplete; }
}
async function mount() {
  const el = document.createElement('jf-brain-surface') as Brain;
  el.apiBase = '';
  el.host_ = createMockHostApi({
    data: { invokeOperation: async (id: string, args: unknown) => { operations.push({ id, args });
      return { success: true, structuredData: { state: id === 'core.cancel-ai-install' ? 'cancelled' : 'running' } }; } },
  });
  document.body.append(el); await settle(el); return el;
}
const words = (node: Element | ShadowRoot | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
const panel = (el: Brain) => el.shadowRoot!.querySelector('[data-testid="brain-simple-panel"]');
const headline = (el: Brain) => words(el.shadowRoot!.querySelector('h1'));
const buttons = (el: Brain) => [...el.shadowRoot!.querySelectorAll<Control>('jf-button')];
const labels = (el: Brain) => buttons(el).map(b => b.label);
/** The parts list under the progress: [name, state] per row. */
function partRows(el: Brain): string[][] {
  const list = el.shadowRoot!.querySelector('ul[aria-label="Parts"]');
  return [...(list?.querySelectorAll(':scope > li') ?? [])].map(li => {
    const [name, state] = [...li.children].map(c => words(c));
    return [name ?? '', state ?? ''];
  });
}
async function press(el: Brain, label: string) {
  const host = buttons(el).find(b => b.label === label);
  expect(host, `${label}; shown: ${labels(el).join(', ')}`).toBeTruthy();
  await host!.updateComplete;
  const control = host!.shadowRoot!.querySelector<Control>('jf-control')!; await control.updateComplete;
  control.shadowRoot!.querySelector('button')!.click(); await settle(el);
}
const dialog = (el: Brain) => el.shadowRoot!.querySelector<HTMLDialogElement>('dialog');

beforeEach(() => {
  setShowDeveloperTools(false);
  resetStoppedRun();
  operations = []; posts = []; toasts.length = 0;
  preview = ENGINE.firstRunPreview!.preview!; presence = ENGINE.restartAfterFailedNothingPlaced!.presence;
  manifest = { schemaVersion: 2, packages: PARTS.map(id => ({ id, label: `${id} registry label`, license: LICENCE[id],
    termsUrl: `https://example.invalid/terms/${id}`, variants: [], supportingFiles: [] })) };
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (init?.method === 'POST') posts.push(path);
    if (path.endsWith('/api/settings/v2')) return json({ ui: {}, llm: {}, witness: { acceptedRevision: 0, lastCommittedOperationKey: null } });
    if (path.includes('/plan-preview')) return preview ? json(preview) : json({}, 503);
    if (path.endsWith('/manifest')) return manifest ? json(manifest) : json({}, 503);
    if (path.endsWith('/api/ai/models/status')) return json(presence);
    if (path.endsWith('/api/ai/install/downloaded-files')) return json({ parts: [] });
    if (path.endsWith('/install/pause') || path.endsWith('/install/resume')) return json({ state: 'running' });
    return json({});
  }));
});
afterEach(() => { document.body.innerHTML = ''; store.listeners.clear(); vi.unstubAllGlobals(); });

// The shipped registry's CPU footprints (model-registry.v2.json: the selected variant plus supporting files).
const SHIPPED: Record<string, number> = { embedding: 1_272_585_559, splade: 1_002_573_334, reranker: 357_942_777, ner: 138_036_009, 'citation-scorer': 23_724_624 };
function shippedPreview(staged = 0): InstallPlanPreview {
  const recorded = ENGINE.firstRunPreview!.preview!;
  const total = Object.values(SHIPPED).reduce((a, b) => a + b, 0);
  return { ...recorded, totalDownloadBytes: total - staged, resumableBytes: staged,
    components: recorded.components!.map(c => SHIPPED[c.id!] ? { ...c, totalBytes: SHIPPED[c.id!], downloadBytes: SHIPPED[c.id!] } : c) };
}

describe('S7b-1 consent (fm-11)', () => {
  it('names the five parts as the page does, with sizes, licences and Terms, under the pictured words', async () => {
    show('restartAfterFailedNothingPlaced'); preview = shippedPreview();
    const el = await mount();
    await press(el, labels(el).find(l => l?.startsWith('Install AI')) ?? 'Install AI');
    const consent = dialog(el);
    expect(consent?.open).toBe(true);
    expect(words(consent?.querySelector('h3, h2, [id$="title"]'))).toBe('Download AI models?');
    expect(words(consent)).toContain('2.6 GB will be downloaded to this computer. Each part comes from its own author, under the licence shown.');
    const rows = [...(consent?.querySelectorAll('li') ?? [])].map(li => [...li.children].map(c => words(c)));
    expect(rows).toEqual([
      ['Understand meaning', '1.19 GB', 'Apache-2.0', 'Terms'],
      ['Related wordshelps Understand meaning', '956 MB', 'Apache-2.0', 'Terms'],
      ['Best match first', '341 MB', 'Apache-2.0', 'Terms'],
      ['Names of people and places', '132 MB', 'AFL-3.0', 'Terms'],
      ['Checking answers against sources', '23 MB', 'Apache-2.0', 'Terms'],
    ]);
    expect([...(consent?.querySelectorAll('li a') ?? [])].map(a => a.getAttribute('href'))).toEqual(PARTS.map(id => `https://example.invalid/terms/${id}`));
    expect(words(consent)).not.toMatch(/resumed|not downloaded again|AI Home/);
    expect(labels(el)).toEqual(expect.arrayContaining(['Cancel', 'Accept and download']));
  });

  it('Cancel starts nothing; Accept starts the install', async () => {
    show('restartAfterFailedNothingPlaced'); preview = shippedPreview();
    const el = await mount();
    const install = labels(el).find(l => l?.startsWith('Install AI')) ?? 'Install AI';
    await press(el, install);
    await press(el, 'Cancel');
    expect(dialog(el)?.open).toBe(false);
    expect(operations).toEqual([]);
    await press(el, install);
    await press(el, 'Accept and download');
    expect(operations.map(o => o.id)).toEqual(['core.start-ai-install']);
  });

  it('with bytes an earlier download left, says "Up to" the whole download and promises no reuse', async () => {
    show('restartAfterCancelNothingPlaced'); preview = shippedPreview(400 * 1024 ** 2);
    const el = await mount();
    await press(el, labels(el).find(l => l?.startsWith('Install AI')) ?? 'Install AI');
    expect(words(dialog(el))).toContain('Up to 2.6 GB will be downloaded to this computer.');
    expect(words(dialog(el))).not.toMatch(/resumed|not downloaded again/);
  });
});

describe('S7b-10 consent that cannot be accepted', () => {
  it('unknown sizes keep Accept blocked with the reason', async () => {
    show('restartAfterFailedNothingPlaced');
    const el = await mount();
    preview = { ...shippedPreview(), components: shippedPreview().components!.map(c => c.id === 'ner' ? { ...c, downloadBytes: undefined } : c) } as InstallPlanPreview;
    await press(el, labels(el).find(l => l?.startsWith('Install AI')) ?? 'Install AI');
    expect(words(dialog(el))).toContain('Every download size must be known before accepting.');
    const accept = buttons(el).find(b => b.label === 'Accept and download') as Control & { disabled?: boolean };
    expect(accept?.disabled).toBe(true);
  });

  it('a missing manifest blocks Accept and says the terms could not be listed; a missing licence says so', async () => {
    show('restartAfterFailedNothingPlaced'); preview = shippedPreview(); manifest = null;
    const el = await mount();
    await press(el, labels(el).find(l => l?.startsWith('Install AI')) ?? 'Install AI');
    expect(words(dialog(el))).toContain('The upstream model terms could not be listed right now.');
    expect((buttons(el).find(b => b.label === 'Accept and download') as Control & { disabled?: boolean })?.disabled).toBe(true);
    el.remove();
    manifest = { schemaVersion: 2, packages: PARTS.map(id => ({ id, license: id === 'ner' ? null : LICENCE[id], termsUrl: null, variants: [], supportingFiles: [] })) };
    const again = await mount();
    await press(again, labels(again).find(l => l?.startsWith('Install AI')) ?? 'Install AI');
    expect(words(dialog(again))).toContain('Names of people and places 132 MB licence not declared');
  });
});

describe('S7b-2 progress (fm-12)', () => {
  it('a run: share in the heading, amounts and time left, one row per part with Done / a of b / Waiting', async () => {
    show('runningRetrying');
    const el = await mount();
    expect(headline(el)).toBe('Installing AI · 24%');
    expect(words(panel(el))).toContain('Search by exact words keeps working while this runs.');
    expect(words(el.shadowRoot!.querySelector('[role="progressbar"]')?.parentElement)).toContain('226 of 927 KB · about 2 min left');
    expect(partRows(el)).toEqual([
      ['Understand meaning', 'Done'],
      ['Related words', '56 of 177 KB'],
      ['Best match first', 'Waiting'],
      ['Names of people and places', 'Waiting'],
      ['Checking answers against sources', 'Waiting'],
    ]);
    expect(labels(el)).toEqual(expect.arrayContaining(['Pause', 'Cancel']));
    expect(words(panel(el))).not.toMatch(/repair/i);
  });

  it('without an estimate there is no time left', async () => {
    show('running');
    const el = await mount();
    expect(words(panel(el))).toContain('170 of 927 KB');
    expect(words(panel(el))).not.toMatch(/left/);
  });

  it('Done never shows for a part between two of its files (the per-file "installed")', async () => {
    show('pausedBetweenFiles');
    const el = await mount();
    expect(partRows(el)[1]).toEqual(['Related words', '168 of 177 KB']);
    expect(partRows(el)[0]).toEqual(['Understand meaning', 'Done']);
  });

  it('Done follows the part\'s last file: model presence is read again once the part is reported installed again', async () => {
    show('pausedBetweenFiles');
    const el = await mount();
    expect(partRows(el)[1]).toEqual(['Related words', '168 of 177 KB']);
    const between = ENGINE.pausedBetweenFiles!.status! as { packages: Array<Record<string, unknown>> };
    const next = { ...between, paused: false, packages: between.packages.map(p => p.packageId === 'splade' ? { ...p, state: 'downloading' } : p) };
    store.current = engineState('pausedBetweenFiles', next);
    for (const listener of store.listeners) listener(store.current);
    await settle(el);
    expect(partRows(el)[1]).toEqual(['Related words', '168 of 177 KB']);
    presence = ENGINE.completed!.presence;
    const last = { ...next, packages: next.packages.map(p => p.packageId === 'splade' ? { ...p, state: 'installed', bytesDownloaded: p.bytesTotal } : p) };
    store.current = engineState('pausedBetweenFiles', last);
    for (const listener of store.listeners) listener(store.current);
    await settle(el);
    expect(partRows(el)[1]).toEqual(['Related words', 'Done']);
  });

  it('a stage the Engine refused says why instead of Waiting', async () => {
    const status = structuredClone(ENGINE.running!.status!) as { stages: Array<{ stage: string; blockedReason: string }> };
    status.stages.find(s => s.stage === 'enrichment')!.blockedReason = 'Not enough disk space: 0.9 GB needed, 0.2 GB free.';
    show('running'); store.current = engineState('running', status as never);
    const el = await mount();
    expect(partRows(el)[2]).toEqual(['Best match first', 'Not enough disk space: 0.9 GB needed, 0.2 GB free.']);
  });

  it('while the run sets up instead of downloading, the line says so without a time', async () => {
    const status = { ...ENGINE.runningRetrying!.status!, phase: 'apply' };
    show('runningRetrying'); store.current = engineState('runningRetrying', status);
    const el = await mount();
    expect(words(panel(el))).toContain('Setting up the downloaded parts');
    expect(words(panel(el))).not.toMatch(/min left/);
  });
});

describe('S7b-3 pause', () => {
  it('Pause asks the Engine; the paused run says the current file may still finish, with Resume and Cancel', async () => {
    show('running');
    const el = await mount();
    await press(el, 'Pause');
    expect(posts.filter(p => p.endsWith('/install/pause'))).toHaveLength(1);
    publish('pausedFileFinishing'); await settle(el);
    expect(words(panel(el))).toContain('Paused. The file being downloaded may still finish.');
    expect(labels(el)).toEqual(expect.arrayContaining(['Resume', 'Cancel']));
    expect(labels(el)).not.toContain('Pause');
    await press(el, 'Resume');
    expect(posts.filter(p => p.endsWith('/install/resume'))).toHaveLength(1);
  });

  it('Cancel stops the run and promises nothing about continuing', async () => {
    show('running');
    const el = await mount();
    await press(el, 'Cancel');
    expect(operations.map(o => o.id)).toEqual(['core.cancel-ai-install']);
    expect(toasts).toEqual(['Install cancelled. Completed parts stay on this computer.']);
    expect(toasts.join(' ')).not.toMatch(/resume|continue|where it stopped/i);
  });
});

describe('S7b-3 G4\' bytes an earlier download left promise no resuming', () => {
  it('after a cancelled run the action is Install AI with the most it can download, never Resume', async () => {
    show('cancelledNothingPlaced'); preview = shippedPreview(400 * 1024 ** 2);
    const el = await mount();
    expect(partRows(el)).toEqual([]);
    expect(labels(el)).toContain('Install AI · 2.60 GB');
    expect(labels(el).filter(l => /resume|continue/i.test(l ?? ''))).toEqual([]);
    const action = buttons(el).find(b => b.label === 'Install AI · 2.60 GB')!;
    await action.updateComplete;
    const control = action.shadowRoot!.querySelector<Control>('jf-control')!; await control.updateComplete;
    const native = control.shadowRoot!.querySelector('button')!;
    expect(native.getAttribute('aria-label') ?? words(native)).toMatch(/^Install AI · 2\.60 GB$/);
    expect(words(panel(el))).not.toMatch(/Resume Download|resum|continues|where it stopped/i);
    await press(el, 'Install AI · 2.60 GB');
    expect(words(dialog(el))).toContain('Up to 2.6 GB will be downloaded to this computer.');
  });
});

describe('S7b-2/S7b-4 the install view stands alone (fm-12, fm-12b)', () => {
  const steps = (el: Brain) => el.shadowRoot!.querySelectorAll('.path-step').length;
  const rest = (el: Brain) => ({
    steps: steps(el),
    graphics: el.shadowRoot!.querySelector('.graphics-card') !== null,
    advanced: [...el.shadowRoot!.querySelectorAll('button')].some(b => words(b) === 'Advanced settings'),
  });
  it('during a run only the install view shows; the steps return when the run is over', async () => {
    show('runningRetrying');
    const el = await mount();
    expect(headline(el)).toBe('Installing AI · 24%');
    expect(rest(el)).toEqual({ steps: 0, graphics: false, advanced: false });
    publish('completed'); await settle(el);
    expect(partRows(el)).toEqual([]);
    expect(steps(el)).toBeGreaterThan(0);
  });
  it('while a download is stopped only the stopped view shows; Cancel install brings the steps back', async () => {
    show('failedPartsMissing'); preview = ENGINE.failedPartsMissing!.preview!;
    const el = await mount();
    expect(headline(el)).toBe('The download stopped');
    expect(rest(el)).toEqual({ steps: 0, graphics: false, advanced: false });
    await press(el, 'Cancel install');
    expect(steps(el)).toBeGreaterThan(0);
    expect([...el.shadowRoot!.querySelectorAll('button')].some(b => words(b) === 'Advanced settings')).toBe(true);
  });
  it('a paused run shows only the install view too', async () => {
    show('pausedFileFinishing');
    const el = await mount();
    expect(words(panel(el))).toContain('Paused. The file being downloaded may still finish.');
    expect(rest(el)).toEqual({ steps: 0, graphics: false, advanced: false });
  });
});

describe('S7b-4 a stopped download (fm-12b)', () => {
  it('a failed run with a part missing: amber stopped view, the parts, Try again and Cancel install', async () => {
    show('failedPartsMissing');
    const el = await mount();
    expect(headline(el)).toBe('The download stopped');
    expect(words(panel(el))).toContain('Completed parts stay on this computer, and search by exact words keeps working.');
    expect(words(panel(el))).toContain('759 of 927 KB');
    expect(partRows(el)).toEqual([
      ['Understand meaning', 'Done'],
      ['Related words', 'Stopped'],
      ['Best match first', 'Done'],
      ['Names of people and places', 'Done'],
      ['Checking answers against sources', 'Done'],
    ]);
    expect(labels(el)).toEqual(expect.arrayContaining(['Try again', 'Cancel install']));
    expect(words(panel(el))).not.toMatch(/connection|continue|resume|AI install failed/i);
    expect(el.shadowRoot!.querySelector('jf-error-alert[tone="error"]')).toBeNull();
    const detail = el.shadowRoot!.querySelector('details');
    expect(words(detail?.querySelector('summary'))).toBe('Detail for support');
    expect(words(detail)).toContain('curl exit 18');
  });

  it('a part that had bytes says how far it got, as history', async () => {
    const status = structuredClone(ENGINE.failedPartsMissing!.status!) as { packages: Array<{ packageId: string; bytesDownloaded: number }> };
    status.packages.find(p => p.packageId === 'splade')!.bytesDownloaded = 57_344;
    show('failedPartsMissing'); store.current = engineState('failedPartsMissing', status as never);
    const el = await mount();
    expect(partRows(el)[1]).toEqual(['Related words', 'Stopped (had 56 of 177 KB)']);
  });

  it('a failed first install with nothing placed shows the stopped view, every part Stopped', async () => {
    show('failedNothingPlaced');
    const el = await mount();
    expect(headline(el)).toBe('The download stopped');
    expect(partRows(el).map(r => r[1])).toEqual(['Stopped', 'Stopped', 'Stopped', 'Stopped', 'Stopped']);
  });
});

describe('S7b-5 Try again', () => {
  it('starts a new run; its total covers only what is missing and placed parts show Done', async () => {
    show('failedPartsMissing');
    const el = await mount();
    await press(el, 'Try again');
    expect(operations.map(o => o.id)).toEqual(['core.start-ai-install']);
    expect(operations[0]!.args).toMatchObject({ acceptTerms: true });
    publish('retryRunning'); await settle(el);
    expect(headline(el)).toBe('Installing AI · 33%');
    expect(words(panel(el))).toContain('56 of 168 KB');
    expect(partRows(el)).toEqual([
      ['Understand meaning', 'Done'],
      ['Related words', '56 of 168 KB'],
      ['Best match first', 'Done'],
      ['Names of people and places', 'Done'],
      ['Checking answers against sources', 'Done'],
    ]);
  });
});

describe('S7b-6 Cancel install', () => {
  it('closes the stopped view; the placed parts stay listed under their steps; the same failed poll stays closed', async () => {
    show('failedPartsMissing'); preview = ENGINE.failedPartsMissing!.preview!;
    const el = await mount();
    await press(el, 'Cancel install');
    expect(operations).toEqual([]);
    expect(headline(el)).not.toBe('The download stopped');
    expect(partRows(el)).toEqual([]);
    expect(headline(el)).not.toMatch(/failed/i);
    const meaning = el.shadowRoot!.querySelector('.path-step[data-feature="meaning"]');
    expect(words(meaning)).toContain('Downloaded');
    expect(words(meaning)).not.toMatch(/KB download|Not downloaded/);
    publish('failedPartsMissing'); await settle(el);
    expect(headline(el)).not.toBe('The download stopped');
  });

  it('a later run that fails shows its stopped view again', async () => {
    show('failedPartsMissing');
    const el = await mount();
    await press(el, 'Cancel install');
    publish('retryRunning'); await settle(el);
    const later = { ...ENGINE.failedPartsMissing!.status!, startedAtEpochMs: (ENGINE.failedPartsMissing!.status!.startedAtEpochMs as number) + 60_000 };
    store.current = engineState('failedPartsMissing', later);
    for (const listener of store.listeners) listener(store.current);
    await settle(el);
    expect(headline(el)).toBe('The download stopped');
  });
});

describe('S7b-7 a first install that stopped with nothing placed is not installed (G6\')', () => {
  const notInstalled = (el: Brain) => {
    expect(headline(el)).not.toMatch(/Installed —|repair/i);
    expect(labels(el)).not.toContain('Repair');
    expect(el.shadowRoot!.querySelector('[data-testid="install-repair-needed"]')).toBeNull();
    expect(labels(el).some(l => l?.startsWith('Install AI'))).toBe(true);
  };
  it('failed, after Cancel install', async () => {
    show('failedNothingPlaced');
    const el = await mount();
    await press(el, 'Cancel install');
    expect(headline(el)).toBe("AI isn't installed yet");
    notInstalled(el);
  });
  it('cancelled in this session', async () => {
    show('cancelledNothingPlaced');
    notInstalled(await mount());
  });
  it('after a restart, from the cancelled and the failed run', async () => {
    show('restartAfterCancelNothingPlaced');
    notInstalled(await mount());
    document.body.innerHTML = ''; store.listeners.clear();
    show('restartAfterFailedNothingPlaced');
    const failed = await mount();
    notInstalled(failed);
    expect(headline(failed)).toBe("AI isn't installed yet");
  });
  it('supporting files alone are not an installation; unknown presence says nothing either way', async () => {
    show('restartAfterCancelNothingPlaced');
    presence = { packages: PARTS.map(id => ({ id, complete: false, presentVariantFiles: [], missingFiles: id === 'splade' ? [] : ['tokenizer.json'] })) };
    notInstalled(await mount());
    document.body.innerHTML = ''; store.listeners.clear();
    show('restartAfterFailedNothingPlaced');
    presence = null;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.includes('/plan-preview')) return json(preview);
      if (path.endsWith('/api/ai/models/status')) return json({}, 503);
      return json({});
    }));
    const unknown = await mount();
    expect(headline(unknown)).not.toMatch(/Installed —|repair|isn't installed/i);
  });
});

describe('S7b-8 after a restart the page rebuilds from disk', () => {
  it('no stale stopped view, no false installed, the placed parts on their steps', async () => {
    show('restartAfterCancelPartsPlaced');
    const el = await mount();
    expect(headline(el)).not.toBe('The download stopped');
    expect(partRows(el)).toEqual([]);
    expect(headline(el)).not.toMatch(/^Installed|AI is installed|ready/i);
    expect(words(panel(el))).not.toMatch(/resuming continues|resumes where/i);
  });
  it('a run that completed shows no stopped view and no install progress', async () => {
    show('completed');
    const el = await mount();
    expect(partRows(el)).toEqual([]);
    expect(headline(el)).not.toMatch(/stopped|Installing/);
  });
});

describe('RS1 Try again keeps what the accepted consent asked to switch on', () => {
  // An installed AI without Best match first: its switch opens the consent for that part alone.
  let reranker: boolean | null;
  const failedAddition = { state: 'failed', phase: 'download', installedFully: true, repairNeeded: false, startedAtEpochMs: 1_794_800_000_000,
    downloadedBytes: 0, totalBytes: 189_843, errorCode: 'REQUIRED_DOWNLOADS_FAILED', lastError: 'reranker: curl exit 18', message: 'AI installation failed',
    packages: [{ packageId: 'reranker', label: 'Search reranker', stage: 'enrichment', state: 'failed', bytesDownloaded: 0, bytesTotal: 189_843 }], stages: [] };
  function installed(status: Record<string, unknown>): AiState {
    return { phase: 'connected', installObservationLive: true, runtimeObservationLive: true, snapshotLive: true,
      index: { ownDocumentCount: { known: true, value: 8 } }, capabilities: { chat: false },
      runtimeStatus: { onnxFeatures: [{ id: 'embed', status: 'active', modelActive: true }, { id: 'reranker', status: 'inactive', reason: 'disabled', modelActive: false }] },
      aiEngine: computeAiEngineVerdict({ installStatus: status as never, runtimeStatus: { onnxFeatures: [{ id: 'embed', status: 'active', modelActive: true }] } as never,
        runtime: { mode: 'offline', installed: { known: true, value: true } } as never, reachable: true, snapshotLive: true, engineState: 'Down' }),
      runtime: { mode: 'offline', installed: { known: true, value: true } }, installStatus: { ...status, updatedAtEpochMs: Date.now() } } as unknown as AiState;
  }
  async function acceptBestMatchFirst(): Promise<Brain> {
    reranker = false;
    preview = { totalDownloadBytes: 189_843, components: [
      { id: 'embedding', label: 'Embedding model', state: 'installed', totalBytes: 173_860, downloadBytes: 0 },
      { id: 'reranker', label: 'Search reranker', state: 'to-download', declinable: true, totalBytes: 189_843, downloadBytes: 189_843 },
    ] } as InstallPlanPreview;
    presence = { packages: [{ id: 'embedding', complete: true, presentVariantFiles: ['model.onnx'] }, { id: 'reranker', complete: false, presentVariantFiles: [] }] };
    const stub = (globalThis.fetch as ReturnType<typeof vi.fn>).getMockImplementation() as (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => String(input).endsWith('/api/settings/v2') && init?.method !== 'POST'
      ? json({ ui: {}, llm: {}, rerankerEnabled: reranker, witness: { acceptedRevision: 0, lastCommittedOperationKey: null } })
      : stub(input, init)));
    store.current = installed({ state: 'completed', installedFully: true, startedAtEpochMs: 1_794_799_000_000 });
    const el = await mount();
    el.shadowRoot!.querySelector<HTMLButtonElement>('[role="switch"][aria-label="Use Best match first"]')!.click(); await settle(el);
    await press(el, 'Accept and download');
    expect(operations.map(o => o.args)).toEqual([{ acceptTerms: true, enableRerankerOnCompletion: true }]);
    return el;
  }
  it('a failed Best match first download: Try again asks the Engine to switch it on when done, as accepted', async () => {
    const el = await acceptBestMatchFirst();
    store.current = installed(failedAddition);
    for (const listener of store.listeners) listener(store.current);
    await settle(el);
    expect(headline(el)).toBe('The download stopped');
    await press(el, 'Try again');
    expect(operations.map(o => o.id)).toEqual(['core.start-ai-install', 'core.start-ai-install']);
    expect(operations[1]!.args).toEqual({ acceptTerms: true, enableRerankerOnCompletion: true });
  });
  it('a choice changed since the consent wins: Try again then does not switch it on', async () => {
    const el = await acceptBestMatchFirst();
    reranker = true; // saved elsewhere after the consent (another window, Settings)
    store.current = installed(failedAddition);
    for (const listener of store.listeners) listener(store.current);
    await settle(el);
    await press(el, 'Try again');
    expect(operations[1]!.args).toEqual({ acceptTerms: true });
  });
});

describe('RS7 the completion intent belongs to the run it was accepted for', () => {
  // The same installed AI and Best match first consent as RS1; the earlier run is the completed one.
  let reranker: boolean | null;
  const EARLIER = 1_794_799_000_000;
  const additions = (state: string, startedAtEpochMs: number) => ({ state, phase: 'download', installedFully: true, repairNeeded: false, startedAtEpochMs,
    downloadedBytes: 0, totalBytes: 189_843, errorCode: state === 'failed' ? 'REQUIRED_DOWNLOADS_FAILED' : '', lastError: state === 'failed' ? 'reranker: curl exit 18' : '',
    message: '', packages: [{ packageId: 'reranker', label: 'Search reranker', stage: 'enrichment', state: state === 'completed' ? 'installed' : state === 'failed' ? 'failed' : 'downloading',
      bytesDownloaded: 0, bytesTotal: 189_843 }], stages: [] });
  function installed(status: Record<string, unknown>): AiState {
    return { phase: 'connected', installObservationLive: true, runtimeObservationLive: true, snapshotLive: true,
      index: { ownDocumentCount: { known: true, value: 8 } }, capabilities: { chat: false },
      runtimeStatus: { onnxFeatures: [{ id: 'embed', status: 'active', modelActive: true }, { id: 'reranker', status: 'inactive', reason: 'disabled', modelActive: false }] },
      aiEngine: computeAiEngineVerdict({ installStatus: status as never, runtimeStatus: { onnxFeatures: [{ id: 'embed', status: 'active', modelActive: true }] } as never,
        runtime: { mode: 'offline', installed: { known: true, value: true } } as never, reachable: true, snapshotLive: true, engineState: 'Down' }),
      runtime: { mode: 'offline', installed: { known: true, value: true } }, installStatus: { ...status, updatedAtEpochMs: Date.now() } } as unknown as AiState;
  }
  async function publishStatus(el: Brain, status: Record<string, unknown>) {
    store.current = installed(status);
    for (const listener of store.listeners) listener(store.current);
    await settle(el);
  }
  async function acceptBestMatchFirst(): Promise<Brain> {
    reranker = false;
    preview = { totalDownloadBytes: 189_843, components: [
      { id: 'embedding', label: 'Embedding model', state: 'installed', totalBytes: 173_860, downloadBytes: 0 },
      { id: 'reranker', label: 'Search reranker', state: 'to-download', declinable: true, totalBytes: 189_843, downloadBytes: 189_843 },
    ] } as InstallPlanPreview;
    presence = { packages: [{ id: 'embedding', complete: true, presentVariantFiles: ['model.onnx'] }, { id: 'reranker', complete: false, presentVariantFiles: [] }] };
    const stub = (globalThis.fetch as ReturnType<typeof vi.fn>).getMockImplementation() as (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => String(input).endsWith('/api/settings/v2') && init?.method !== 'POST'
      ? json({ ui: {}, llm: {}, rerankerEnabled: reranker, witness: { acceptedRevision: 0, lastCommittedOperationKey: null } })
      : stub(input, init)));
    store.current = installed({ state: 'completed', installedFully: true, startedAtEpochMs: EARLIER });
    const el = await mount();
    el.shadowRoot!.querySelector<HTMLButtonElement>('[role="switch"][aria-label="Use Best match first"]')!.click(); await settle(el);
    await press(el, 'Accept and download');
    expect(operations.map(o => o.args)).toEqual([{ acceptTerms: true, enableRerankerOnCompletion: true }]);
    return el;
  }
  it('a completed status the store keeps from the earlier run, then a failure: Try again still switches it on', async () => {
    const el = await acceptBestMatchFirst();
    // The store publishes again before the Engine reports the new run: the earlier run's completed status.
    await publishStatus(el, { state: 'completed', installedFully: true, startedAtEpochMs: EARLIER });
    await publishStatus(el, additions('failed', EARLIER + 60_000));
    expect(headline(el)).toBe('The download stopped');
    await press(el, 'Try again');
    expect(operations[1]!.args).toEqual({ acceptTerms: true, enableRerankerOnCompletion: true });
  });
  it('the intent follows Try again to its new run, past that run\'s stale failed status, until a failure of the new run', async () => {
    const el = await acceptBestMatchFirst();
    await publishStatus(el, additions('failed', EARLIER + 60_000));
    await press(el, 'Try again');
    await publishStatus(el, additions('failed', EARLIER + 60_000));
    await publishStatus(el, additions('running', EARLIER + 120_000));
    await publishStatus(el, additions('failed', EARLIER + 120_000));
    await press(el, 'Try again');
    expect(operations.map(o => o.args)).toEqual([1, 2, 3].map(() => ({ acceptTerms: true, enableRerankerOnCompletion: true })));
  });
  it('its own run completing clears it: a later failed run\'s Try again switches nothing on', async () => {
    const el = await acceptBestMatchFirst();
    await publishStatus(el, additions('running', EARLIER + 60_000));
    await publishStatus(el, additions('completed', EARLIER + 60_000));
    await publishStatus(el, additions('failed', EARLIER + 120_000));
    await press(el, 'Try again');
    expect(operations[1]!.args).toEqual({ acceptTerms: true });
  });
});

describe('RS2 supporting files alone never establish an installation (G6\')', () => {
  it('after a restart, a complete GPU runtime package with no model file is not an installation', async () => {
    show('restartAfterFailedNothingPlaced');
    // The Engine reports a package of supporting files only as complete with no variant file.
    presence = { ...ENGINE.restartAfterFailedNothingPlaced!.presence!, packages: ENGINE.restartAfterFailedNothingPlaced!.presence!.packages
      .map(p => p.id === 'cuda-runtime' ? { ...p, complete: true, presentVariantFiles: [], missingFiles: [] } : p) };
    const el = await mount();
    expect(headline(el)).toBe("AI isn't installed yet");
    expect(labels(el)).not.toContain('Repair');
    expect(el.shadowRoot!.querySelector('[data-testid="install-repair-needed"]')).toBeNull();
  });
});

describe('RS3 an existing installation is known from disk, not from what this page saw', () => {
  // After a restart: the Engine's status says nothing of a run, model presence shows four parts' final
  // files and Related words missing. Nothing on this page saw an installation before.
  const damaged = () => { show('restartAfterFailedNothingPlaced'); presence = ENGINE.restartAfterFailed!.presence; preview = ENGINE.failedPartsMissing!.preview!; };
  const steps = (el: Brain) => el.shadowRoot!.querySelectorAll('.path-step').length;
  const repairRun = ENGINE.retryRunning!.status! as { startedAtEpochMs: number; packages: Array<Record<string, unknown>> } & Record<string, unknown>;
  // The repair run as the Engine reports its end: the parts it found on disk stay installed (no bytes).
  const ended = (state: 'failed' | 'cancelled') => ({ ...repairRun, state, phase: 'download', errorCode: state === 'failed' ? 'REQUIRED_DOWNLOADS_FAILED' : '',
    lastError: state === 'failed' ? 'splade: curl exit 18' : '', packages: repairRun.packages.map(p => p.packageId === 'splade' ? { ...p, state: state === 'failed' ? 'failed' : 'downloading' } : p) });
  const publishStatus = async (el: Brain, status: Record<string, unknown>) => {
    store.current = engineState('retryRunning', status as never);
    for (const listener of store.listeners) listener(store.current);
    await settle(el);
  };
  it('Repair is offered from disk; its run keeps the steps, which say what still works', async () => {
    damaged();
    const el = await mount();
    expect(headline(el)).toBe('Installed — repair available');
    expect(el.shadowRoot!.querySelector('[data-testid="install-repair-needed"]')).not.toBeNull();
    await publishStatus(el, repairRun);
    expect(headline(el)).toMatch(/^Installing AI/);
    expect(steps(el)).toBeGreaterThan(0);
  });
  it('a repair run that fails keeps the repair classification once its stopped view is closed', async () => {
    damaged();
    const el = await mount();
    await publishStatus(el, repairRun);
    await publishStatus(el, ended('failed'));
    expect(headline(el)).toBe('The download stopped');
    await press(el, 'Cancel install');
    expect(headline(el)).toBe('Installed — repair available');
    expect(el.shadowRoot!.querySelector('[data-testid="install-repair-needed"]')).not.toBeNull();
  });
  it('a repair run that is cancelled keeps the repair classification', async () => {
    damaged();
    const el = await mount();
    await publishStatus(el, repairRun);
    await publishStatus(el, ended('cancelled'));
    expect(headline(el)).not.toMatch(/isn't installed/);
    expect(el.shadowRoot!.querySelector('[data-testid="install-repair-needed"]')).not.toBeNull();
  });
});

describe('RS8 a model file present before the run is installation evidence, whatever the run downloads', () => {
  // After a restart: Understand meaning's model file is on disk, its tokenizer is missing; nothing else
  // of the AI is there. Repair plans the tokenizer for Understand meaning and the other parts whole.
  const steps = (el: Brain) => el.shadowRoot!.querySelectorAll('.path-step').length;
  const repairRun = ENGINE.retryRunning!.status! as { startedAtEpochMs: number; packages: Array<Record<string, unknown>> } & Record<string, unknown>;
  const TOKENIZER = 711_396;
  const run = (state: 'running' | 'failed' | 'cancelled') => ({ ...repairRun, state, phase: 'download', downloadedBytes: 0,
    errorCode: state === 'failed' ? 'REQUIRED_DOWNLOADS_FAILED' : '', lastError: state === 'failed' ? 'embedding: curl exit 18' : '',
    packages: repairRun.packages.map(p => p.packageId === 'chat' || p.packageId === 'cuda-runtime' ? p
      : p.packageId === 'embedding' ? { ...p, state: state === 'failed' ? 'failed' : 'downloading', bytesDownloaded: 0, bytesTotal: TOKENIZER }
        : { ...p, state: 'pending', bytesDownloaded: 0, bytesTotal: 172_032 }) });
  const damaged = () => {
    show('restartAfterFailedNothingPlaced');
    presence = { packages: (ENGINE.restartAfterFailedNothingPlaced!.presence!.packages)
      .map(p => p.id === 'embedding' ? { ...p, complete: false, presentVariantFiles: ['model.onnx'], missingFiles: ['tokenizer.json'] } : p) };
    preview = ENGINE.failedPartsMissing!.preview!;
  };
  const publishStatus = async (el: Brain, status: Record<string, unknown>) => {
    store.current = engineState('retryRunning', status as never);
    for (const listener of store.listeners) listener(store.current);
    await settle(el);
  };
  it('before the run, Repair is offered', async () => {
    damaged();
    const el = await mount();
    expect(headline(el)).toBe('Installed — repair available');
    expect(el.shadowRoot!.querySelector('[data-testid="install-repair-needed"]')).not.toBeNull();
  });
  it('while the repair downloads the tokenizer, the steps stay', async () => {
    damaged();
    const el = await mount();
    await publishStatus(el, run('running'));
    expect(headline(el)).toMatch(/^Installing AI/);
    expect(steps(el)).toBeGreaterThan(0);
  });
  it('a cancelled repair keeps the repair classification', async () => {
    damaged();
    const el = await mount();
    await publishStatus(el, run('running'));
    await publishStatus(el, run('cancelled'));
    expect(headline(el)).not.toMatch(/isn't installed/);
    expect(el.shadowRoot!.querySelector('[data-testid="install-repair-needed"]')).not.toBeNull();
  });
  it('a failed repair keeps the repair classification once its stopped view is closed', async () => {
    damaged();
    const el = await mount();
    await publishStatus(el, run('running'));
    await publishStatus(el, run('failed'));
    expect(headline(el)).toBe('The download stopped');
    await press(el, 'Cancel install');
    expect(headline(el)).toBe('Installed — repair available');
    expect(el.shadowRoot!.querySelector('[data-testid="install-repair-needed"]')).not.toBeNull();
  });
  it('a model file the first install placed is still not an installation once that run failed', async () => {
    show('restartAfterFailedNothingPlaced');
    const el = await mount();
    expect(headline(el)).toBe("AI isn't installed yet");
    // The first install starts; the page sees it before any file is placed.
    await publishStatus(el, { ...run('running'), repairNeeded: false });
    // It places Understand meaning's model file, then fails on the tokenizer.
    presence = { packages: (ENGINE.restartAfterFailedNothingPlaced!.presence!.packages)
      .map(p => p.id === 'embedding' ? { ...p, complete: false, presentVariantFiles: ['model.onnx'], missingFiles: ['tokenizer.json'] } : p) };
    window.dispatchEvent(new Event('focus')); await settle(el);
    await publishStatus(el, { ...run('failed'), repairNeeded: true });
    expect(headline(el)).toBe('The download stopped');
    await press(el, 'Cancel install');
    expect(headline(el)).not.toBe('Installed — repair available');
    expect(el.shadowRoot!.querySelector('[data-testid="install-repair-needed"]')).toBeNull();
  });
});

describe('RS4 after Cancel install, unknown disk evidence stays unknown', () => {
  it('model presence unreadable: neutral words, not "AI isn\'t installed yet", until presence resolves', async () => {
    show('failedNothingPlaced');
    const stub = (globalThis.fetch as ReturnType<typeof vi.fn>).getMockImplementation() as (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;
    let readable = false;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) =>
      String(input).endsWith('/api/ai/models/status') && !readable ? json({}, 503) : stub(input, init)));
    const el = await mount();
    expect(headline(el)).toBe('The download stopped');
    await press(el, 'Cancel install');
    expect(headline(el)).toBe('Checking AI status…');
    expect(words(panel(el))).not.toMatch(/isn't installed/);
    readable = true;
    window.dispatchEvent(new Event('focus')); await settle(el);
    expect(headline(el)).toBe("AI isn't installed yet");
  });
});

describe('RS5 a part being verified shows what is happening and no time left (G3\')', () => {
  it('a package the Engine reports verifying, in the download phase: "Checking the downloaded files", no estimate', async () => {
    const status = structuredClone(ENGINE.runningRetrying!.status!) as { phase: string; remainingSeconds: number; packages: Array<{ packageId: string; state: string }> };
    status.packages.find(p => p.packageId === 'splade')!.state = 'verifying';
    expect(status.phase).toBe('download');
    expect(status.remainingSeconds).toBeGreaterThan(0);
    show('runningRetrying'); store.current = engineState('runningRetrying', status as never);
    const el = await mount();
    expect(words(el.shadowRoot!.querySelector('.flow-line'))).toBe('Checking the downloaded files');
    expect(words(panel(el))).not.toMatch(/min left/);
  });
});
