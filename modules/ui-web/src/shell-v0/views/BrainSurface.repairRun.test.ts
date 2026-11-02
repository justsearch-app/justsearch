// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AiState, InstallStatus } from '../state/aiStateStore.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';

const store = vi.hoisted(() => ({ listeners: new Set<(state: AiState) => void>(), current: {} as AiState }));
vi.mock('../state/aiStateStore.js', async original => ({
  ...await original<typeof import('../state/aiStateStore.js')>(),
  subscribeAiState: (listener: (state: AiState) => void) => {
    store.listeners.add(listener); listener(store.current);
    return () => store.listeners.delete(listener);
  },
}));
const refreshInstall = vi.hoisted(() => vi.fn(async () => {}));
vi.mock('../utils/aiInstallPoll.js', async original => ({
  ...await original<typeof import('../utils/aiInstallPoll.js')>(),
  refreshAiInstallNow: refreshInstall,
}));
import './BrainSurface.js';

interface Brain extends HTMLElement { apiBase: string; host_: unknown; updateComplete: Promise<boolean> }
interface Button extends HTMLElement { updateComplete: Promise<boolean> }
const json = (body: unknown) => new Response(JSON.stringify(body));
let run: InstallStatus;
let operations: string[];
let confirmations: string[];
let repairReply: (() => Promise<InstallStatus>) | null;

async function settle(el: Brain) { await new Promise(resolve => setTimeout(resolve, 0)); await el.updateComplete; }
function expectPlanning(el: Brain) {
  expect(el.shadowRoot!.querySelector('.brain-headline')!.textContent).toBe('Installing…');
  const steps = el.shadowRoot!.querySelectorAll('.path-step');
  expect(steps.length).toBeGreaterThan(0);
  for (const step of steps) expect(step.textContent).not.toMatch(/Preparing download|Waiting to download|Downloading/);
}
async function mount() {
  const el = document.createElement('jf-brain-surface') as Brain;
  el.apiBase = '';
  el.host_ = createMockHostApi({
    ui: { showConfirmDialog: async (_message, options) => { confirmations.push(options?.confirmLabel ?? ''); return true; } },
    data: { invokeOperation: async id => {
      operations.push(id);
      if (id === 'core.repair-ai-install' && repairReply) return { success: true, structuredData: await repairReply() };
      if (id === 'core.cancel-ai-install') run = { ...run, state: 'cancelled', resumableBytes: run.downloadedBytes };
      return { success: true, structuredData: run };
    } },
  });
  document.body.append(el); await settle(el); return el;
}
// Click through jf-button and jf-control so availability and promise-busy behaviour are exercised.
async function click(el: Brain, label: string) {
  const button = el.shadowRoot!.querySelector<Button>(`jf-button[label="${label}"]`);
  expect(button, `visible ${label} action`).not.toBeNull();
  await button!.updateComplete;
  const control = button!.shadowRoot!.querySelector<Button>('jf-control')!;
  await control.updateComplete;
  control.shadowRoot!.querySelector<HTMLButtonElement>('button')!.click();
  await settle(el);
}
function notify(patch: Partial<AiState> = {}) {
  store.current = { ...store.current, ...patch };
  for (const listener of store.listeners) listener(store.current);
}

function emit(installStatus: InstallStatus) { notify({ installStatus }); }

beforeEach(() => {
  operations = []; confirmations = []; repairReply = null; refreshInstall.mockClear();
  run = {
    state: 'running', phase: 'download', installedFully: true, repairNeeded: true,
    startedAtEpochMs: 200, updatedAtEpochMs: Date.now(), downloadedBytes: 256, totalBytes: 1024,
    packages: [{ packageId: 'embedding', state: 'downloading', bytesDownloaded: 256, bytesTotal: 1024 }],
    stages: [{ stage: 'search', label: 'Search', state: 'running', downloadedBytes: 256, totalBytes: 1024 }],
  };
  store.current = {
    installObservationLive: true, runtimeObservationLive: true, snapshotLive: true,
    aiEngine: { kind: 'offline', stability: { kind: 'settled' }, installFailure: null },
    installStatus: { state: 'completed', installedFully: true, repairNeeded: true, startedAtEpochMs: 100, updatedAtEpochMs: 100 },
    runtime: { mode: 'offline', installed: { known: true, value: true } },
    runtimeStatus: { onnxFeatures: [{ id: 'embed', status: 'active', modelActive: true }] },
    capabilities: { chat: false },
  } as unknown as AiState;
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input);
    if (path.endsWith('/install/pause') || path.endsWith('/install/resume')) {
      run = { ...run, paused: path.endsWith('/pause') }; return json(run);
    }
    if (path.endsWith('/api/settings/v2')) return json({ ui: { chatEnabled: false } });
    if (path.endsWith('/plan-preview')) return json({ totalDownloadBytes: 1024, components: [{ id: 'embedding', label: 'Understand meaning', state: 'installed', totalBytes: 1024 }] });
    if (path.endsWith('/manifest')) return json({ schemaVersion: 2, packages: [] });
    return json({});
  }));
});
afterEach(() => { document.body.innerHTML = ''; store.listeners.clear(); vi.unstubAllGlobals(); });

describe('Brain running repair controls (B2/B8)', () => {
  it('Repair requests a read immediately and only store observations change progress, Pause, Resume and Cancel', async () => {
    run = { ...run, updatedAtEpochMs: Date.now() - 120_000, readyCapabilities: ['retrieval-core'],
      stages: [...run.stages!, { stage: 'answers', label: 'Answers', state: 'blocked', blockedReason: 'Not enough disk space.' }] };
    const el = await mount();
    await click(el, 'Repair');
    expect(operations).toEqual(['core.repair-ai-install']);
    expect(refreshInstall).toHaveBeenCalledTimes(1);
    expect(el.shadowRoot!.querySelector('.brain-headline')!.textContent).not.toContain('Installing');
    emit(run); await settle(el);
    expect(store.current.aiEngine.kind).toBe('offline');
    expect(el.shadowRoot!.querySelector('.brain-headline')!.textContent).toBe('Installing… 25%');
    expect(el.shadowRoot!.querySelector<HTMLElement>('.progress-bar')!.style.width).toBe('25%');
    expect(el.shadowRoot!.querySelector('[data-feature="meaning"]')!.textContent).toContain('Downloading 25%');
    expect(el.shadowRoot!.querySelector('[data-testid="install-staged-progress"]')).not.toBeNull();
    expect(el.shadowRoot!.querySelector('[data-testid="install-stage-blocked-answers"]')!.textContent).toContain('Not enough disk space.');
    expect(el.shadowRoot!.querySelector('[data-testid="install-search-ready"]')!.textContent).toContain('Search is ready');
    expect(el.shadowRoot!.textContent).toContain('Download stalled');
    expect(el.shadowRoot!.querySelector('[label="Pause download"]')).not.toBeNull();
    await click(el, 'Pause download');
    expect(refreshInstall).toHaveBeenCalledTimes(2);
    expect(el.shadowRoot!.querySelector('.brain-headline')!.textContent).toBe('Installing… 25%');
    emit(run); await settle(el);
    expect(el.shadowRoot!.querySelector('.brain-headline')!.textContent).toBe('Paused 25%');
    await click(el, 'Resume download');
    expect(refreshInstall).toHaveBeenCalledTimes(3);
    expect(el.shadowRoot!.querySelector('.brain-headline')!.textContent).toBe('Paused 25%');
    emit(run); await settle(el);
    expect(el.shadowRoot!.querySelector('.brain-headline')!.textContent).toBe('Installing… 25%');
    await click(el, 'Cancel');
    expect(refreshInstall).toHaveBeenCalledTimes(4);
    expect(el.shadowRoot!.querySelector('[data-testid="brain-simple-action"][label="Cancel"]')).not.toBeNull();
    emit(run); await settle(el);
    expect(operations).toEqual(['core.repair-ai-install', 'core.cancel-ai-install']);
    expect(confirmations).toEqual(['Repair', 'Pause download']);
    expect(run.resumableBytes).toBe(256);
    expect(el.shadowRoot!.querySelector('[data-testid="brain-simple-action"][label="Cancel"]')).toBeNull();
    expect(el.shadowRoot!.textContent).not.toContain('Downloading 25%');
  });

  it('Repair does not override unchanged store notifications while its requested read is pending', async () => {
    const el = await mount(); await click(el, 'Repair');
    expect(refreshInstall).toHaveBeenCalledTimes(1);
    notify({ runtimeStatus: { onnxFeatures: [] } }); await settle(el);
    expect(el.shadowRoot!.querySelector('.brain-headline')!.textContent).not.toContain('Installing');
    expect(el.shadowRoot!.querySelector('[label="Pause download"]')).toBeNull();
    emit({ ...run, downloadedBytes: 512 }); await settle(el);
    expect(el.shadowRoot!.querySelector('.brain-headline')!.textContent).toBe('Installing… 50%');
    emit({ ...run, updatedAtEpochMs: run.updatedAtEpochMs! + 1, state: 'completed' }); await settle(el);
    expect(el.shadowRoot!.querySelector('[label="Pause download"]')).toBeNull();
    expect(el.shadowRoot!.querySelector('[data-testid="brain-simple-action"][label="Cancel"]')).toBeNull();
  });

  it('a late Repair response never replaces the store install status with an older timestamp', async () => {
    let resolve!: (status: InstallStatus) => void;
    repairReply = () => new Promise(done => { resolve = done; });
    const el = await mount(); await click(el, 'Repair');
    emit({ ...run, updatedAtEpochMs: run.updatedAtEpochMs! + 1, downloadedBytes: 768 }); await settle(el);
    resolve(run); await settle(el);
    expect(el.shadowRoot!.querySelector('.brain-headline')!.textContent).toBe('Installing… 75%');
    expect(el.shadowRoot!.querySelector('[label="Pause download"]')).not.toBeNull();
  });

  it.each(['Pause', 'Resume'])('%s requests an immediate read and waits for the store even at an equal timestamp', async action => {
    run = { ...run, paused: action === 'Resume' };
    emit({ ...run });
    const el = await mount();
    await click(el, `${action} download`);
    expect(refreshInstall).toHaveBeenCalledTimes(1);
    const previous = action === 'Pause' ? 'Installing… 25%' : 'Paused 25%';
    expect(el.shadowRoot!.querySelector('.brain-headline')!.textContent).toBe(previous);
    notify({ runtimeStatus: { onnxFeatures: [] } }); await settle(el);
    expect(el.shadowRoot!.querySelector('.brain-headline')!.textContent).toBe(previous);
    emit({ ...run }); await settle(el);
    expect(el.shadowRoot!.querySelector('.brain-headline')!.textContent).toBe(action === 'Pause' ? 'Paused 25%' : 'Installing… 25%');
    expect(el.shadowRoot!.querySelector(`[label="${action === 'Pause' ? 'Resume' : 'Pause'} download"]`)).not.toBeNull();
  });

  it('the shared store alone decides which install observation follows Pause regardless of timestamp', async () => {
    emit({ ...run });
    const el = await mount();
    await click(el, 'Pause download');
    emit({ ...run, paused: false, updatedAtEpochMs: run.updatedAtEpochMs! - 1 }); await settle(el);
    expect(el.shadowRoot!.querySelector('.brain-headline')!.textContent).toBe('Installing… 25%');
    expect(el.shadowRoot!.querySelector('[label="Pause download"]')).not.toBeNull();
    emit({ ...run }); await settle(el);
    expect(el.shadowRoot!.querySelector('.brain-headline')!.textContent).toBe('Paused 25%');
    expect(el.shadowRoot!.querySelector('[label="Resume download"]')).not.toBeNull();
  });

  it.each(['declined', 'unavailable', 'not-in-mode'])('planning ignores retained downloading records for an excluded %s package', async state => {
    const fetch = globalThis.fetch;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).endsWith('/plan-preview')) return json({ totalDownloadBytes: 1024, components: [
        { id: 'embedding', label: 'Understand meaning', state: 'installed', totalBytes: 1024 },
        { id: 'reranker', label: 'Best match first', state, declined: state === 'declined', unavailableReason: 'Not available here.', totalBytes: 2048 },
      ] });
      return fetch(input, init);
    }));
    run = { ...run, phase: 'plan', packages: [
      ...run.packages!, { packageId: 'reranker', state: 'downloading', bytesDownloaded: 1024, bytesTotal: 2048 },
    ] }; emit({ ...run });
    const el = await mount(); await settle(el);
    expectPlanning(el);
    const reranker = el.shadowRoot!.querySelector('[data-feature="reranker"]')!.textContent!;
    expect(reranker).not.toMatch(/Downloading|Waiting to download|Preparing download/);
    expect(reranker).toContain(state === 'declined' ? 'Not downloaded' : 'Unavailable on this computer');
  });

  it.each(['download', 'plan'])('a failed install read gates a retained running %s observation and recovery restores it', async phase => {
    run = { ...run, phase }; emit(run);
    const el = await mount();
    notify({ installObservationLive: false, snapshotLive: false }); await settle(el);
    expect(el.shadowRoot!.querySelector('.brain-headline')!.textContent).toBe('Checking AI status…');
    expect(el.shadowRoot!.querySelector('.progress-bar')).toBeNull();
    expect(el.shadowRoot!.querySelector('[data-testid="install-staged-progress"]')).toBeNull();
    expect(el.shadowRoot!.querySelector('[label="Pause download"]')).toBeNull();
    expect(el.shadowRoot!.querySelector('[data-testid="brain-simple-action"][label="Cancel"]')).toBeNull();
    expect(el.shadowRoot!.querySelector('[data-feature="meaning"]')!.textContent).toContain('Unknown — checking AI status');
    expect(el.shadowRoot!.textContent).not.toMatch(/Downloading|Preparing download|Download stalled/);
    notify({ installObservationLive: true, snapshotLive: true }); await settle(el);
    expect(el.shadowRoot!.querySelector('[label="Pause download"]')).not.toBeNull();
    expect(el.shadowRoot!.querySelector('[data-testid="brain-simple-action"][label="Cancel"]')).not.toBeNull();
  });

  it('a successful Repair cannot reopen a failed install observation gate before recovery', async () => {
    const el = await mount(); await click(el, 'Repair');
    notify({ installObservationLive: false, snapshotLive: false }); await settle(el);
    expect(el.shadowRoot!.querySelector('.brain-headline')!.textContent).toBe('Checking AI status…');
    expect(el.shadowRoot!.querySelector('[label="Pause download"]')).toBeNull();
    expect(el.shadowRoot!.querySelector('[data-testid="brain-simple-action"][label="Cancel"]')).toBeNull();
    emit(run); await settle(el);
    expect(el.shadowRoot!.querySelector('.brain-headline')!.textContent).toBe('Checking AI status…');
    notify({ installObservationLive: true, snapshotLive: true }); await settle(el);
    expect(el.shadowRoot!.querySelector('[label="Pause download"]')).not.toBeNull();
  });

  it('while a Repair run is being planned, a package unavailable on this computer is not said to be preparing', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith('/api/settings/v2')) return json({ ui: { chatEnabled: false } });
      if (path.endsWith('/plan-preview')) return json({ totalDownloadBytes: 1024, components: [{ id: 'embedding', label: 'Understand meaning', state: 'installed', totalBytes: 1024 }, { id: 'chat', label: 'Answer questions', state: 'unavailable', unavailableReason: 'Needs the answer runtime.', totalBytes: 2048 }] });
      if (path.endsWith('/manifest')) return json({ schemaVersion: 2, packages: [] });
      return json({});
    }));
    run = { ...run, phase: 'plan', packages: [] }; emit(run);
    const el = await mount(); await settle(el);
    expectPlanning(el);
    const chat = el.shadowRoot!.querySelector('[data-feature="chat"]')!.textContent!;
    expect(chat).not.toMatch(/Preparing download|Waiting to download|Downloading/); expect(chat).toContain('Unavailable on this computer');
  });
  it('planning an unavailable answer runtime with complete answer files keeps the observed step state', async () => {
    const fetch = globalThis.fetch;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith('/plan-preview')) return json({ totalDownloadBytes: 1024, components: [
        { id: 'embedding', label: 'Understand meaning', state: 'installed', totalBytes: 1024 },
        { id: 'chat', label: 'Answer questions', state: 'unavailable', unavailableReason: 'Answer runtime not supplied.', totalBytes: 2048 },
      ] });
      if (path.endsWith('/api/ai/models/status')) return json({ packages: [{ id: 'chat', complete: true }] });
      return fetch(input, init);
    }));
    run = { ...run, phase: 'plan', packages: [] }; emit(run);
    const el = await mount(); await settle(el);
    const chat = el.shadowRoot!.querySelector('[data-feature="chat"]')!.textContent!;
    expect(chat).not.toMatch(/Preparing download|Waiting to download|Downloading/);
    expect(chat).toContain('Off — background work can continue');
    expectPlanning(el);
  });

  it('a runtime-only read failure keeps live install progress and controls', async () => {
    emit(run); const el = await mount();
    notify({ runtimeObservationLive: false, snapshotLive: false,
      aiEngine: { kind: 'connecting', stability: { kind: 'provisional', cause: 'checking' }, installFailure: null } });
    await settle(el);
    expect(el.shadowRoot!.querySelector('.brain-headline')!.textContent).toBe('Installing… 25%');
    expect(el.shadowRoot!.querySelector('[data-feature="meaning"]')!.textContent).toContain('Downloading 25%');
    expect(el.shadowRoot!.querySelector('[label="Pause download"]')).not.toBeNull();
    expect(el.shadowRoot!.querySelector('[data-testid="brain-simple-action"][label="Cancel"]')).not.toBeNull();
  });

  it.each(['preflight', 'plan'])('a new Repair in %s never displays the previous run percentage or ready stages', async phase => {
    run = { ...run, phase, downloadedBytes: 1004, readyCapabilities: ['retrieval-core'], packages: [{ packageId: 'embedding', state: 'downloading', bytesDownloaded: 1004, bytesTotal: 1024 }] };
    const el = await mount(); await click(el, 'Repair');
    emit(run); await settle(el);
    expectPlanning(el);
    expect(el.shadowRoot!.querySelector('.brain-headline')!.textContent).toBe('Installing…');
    expect(el.shadowRoot!.querySelector('.progress-bar')).toBeNull();
    expect(el.shadowRoot!.querySelector('[data-testid="install-staged-progress"]')).toBeNull();
    expect(el.shadowRoot!.querySelector('[label="Pause download"]')).not.toBeNull();
    expect(el.shadowRoot!.querySelector('[data-testid="brain-simple-action"][label="Cancel"]')).not.toBeNull();
    emit({ ...run, phase: 'download', downloadedBytes: 256, packages: [{ packageId: 'embedding', state: 'downloading', bytesDownloaded: 256, bytesTotal: 1024 }] });
    await el.updateComplete;
    expect(el.shadowRoot!.querySelector('[data-feature="meaning"]')!.textContent).toContain('Downloading 25%');
  });

  it('a pending package in the current run waits instead of claiming On from the old runtime', async () => {
    run = { ...run, packages: [{ packageId: 'embedding', state: 'pending', bytesDownloaded: 0, bytesTotal: 1024 }] };
    const el = await mount(); await click(el, 'Repair');
    emit(run); await settle(el);
    expect(el.shadowRoot!.querySelector('[data-feature="meaning"]')!.textContent).toContain('Waiting to download');
    emit({ ...run, packages: [{ packageId: 'embedding', state: 'downloading', bytesDownloaded: 128, bytesTotal: 1024 }] }); await el.updateComplete;
    expect(el.shadowRoot!.querySelector('[data-feature="meaning"]')!.textContent).toContain('Downloading 12%');
  });

  it('an additions run keeps controls through a missing runtime observation, a second window and remount', async () => {
    emit({ ...run, repairNeeded: false });
    store.current = { ...store.current, runtimeObservationLive: false, aiEngine: { kind: 'connecting', stability: { kind: 'provisional', cause: 'checking' }, installFailure: null } };
    const first = await mount(); const second = await mount(); first.remove(); const reloaded = await mount();
    for (const el of [second, reloaded]) {
      expect(el.shadowRoot!.querySelector('.brain-headline')!.textContent).toBe('Installing… 25%');
      expect(el.shadowRoot!.querySelector('[data-feature="meaning"]')!.textContent).toContain('Downloading 25%');
      expect(el.shadowRoot!.querySelector('[label="Pause download"]')).not.toBeNull();
      expect(el.shadowRoot!.querySelector('[data-testid="brain-simple-action"][label="Cancel"]')).not.toBeNull();
    }
  });
});
