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
import './BrainSurface.js';

interface Brain extends HTMLElement { apiBase: string; host_: unknown; updateComplete: Promise<boolean> }
interface Button extends HTMLElement { updateComplete: Promise<boolean> }
const json = (body: unknown) => new Response(JSON.stringify(body));
let run: InstallStatus;
let operations: string[];
let confirmations: string[];

async function settle(el: Brain) { await new Promise(resolve => setTimeout(resolve, 0)); await el.updateComplete; }
async function mount() {
  const el = document.createElement('jf-brain-surface') as Brain;
  el.apiBase = '';
  el.host_ = createMockHostApi({
    ui: { showConfirmDialog: async (_message, options) => { confirmations.push(options?.confirmLabel ?? ''); return true; } },
    data: { invokeOperation: async id => {
      operations.push(id);
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
function emit(installStatus: InstallStatus) {
  store.current = { ...store.current, installStatus };
  for (const listener of store.listeners) listener(store.current);
}

beforeEach(() => {
  operations = []; confirmations = [];
  run = {
    state: 'running', phase: 'download', installedFully: true, repairNeeded: true,
    startedAtEpochMs: 200, updatedAtEpochMs: Date.now(), downloadedBytes: 256, totalBytes: 1024,
    packages: [{ packageId: 'embedding', state: 'downloading', bytesDownloaded: 256, bytesTotal: 1024 }],
    stages: [{ stage: 'search', label: 'Search', state: 'running', downloadedBytes: 256, totalBytes: 1024 }],
  };
  store.current = {
    installObservationLive: true, runtimeObservationLive: true, snapshotLive: true,
    aiEngine: { kind: 'offline', stability: { kind: 'settled' }, installFailure: null },
    installStatus: { state: 'completed', installedFully: true, repairNeeded: true, startedAtEpochMs: 100 },
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
  it('Repair response shows installing progress, Pause and Cancel before the shared verdict catches up, and Cancel retains bytes', async () => {
    run = { ...run, updatedAtEpochMs: Date.now() - 120_000, readyCapabilities: ['retrieval-core'],
      stages: [...run.stages!, { stage: 'answers', label: 'Answers', state: 'blocked', blockedReason: 'Not enough disk space.' }] };
    const el = await mount();
    await click(el, 'Repair');
    expect(operations).toEqual(['core.repair-ai-install']);
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
    expect(el.shadowRoot!.querySelector('.brain-headline')!.textContent).toBe('Paused 25%');
    await click(el, 'Resume download');
    expect(el.shadowRoot!.querySelector('.brain-headline')!.textContent).toBe('Installing… 25%');
    await click(el, 'Cancel');
    expect(operations).toEqual(['core.repair-ai-install', 'core.cancel-ai-install']);
    expect(confirmations).toEqual(['Repair', 'Pause download']);
    expect(run.resumableBytes).toBe(256);
    expect(el.shadowRoot!.querySelector('[data-testid="brain-simple-action"][label="Cancel"]')).toBeNull();
    expect(el.shadowRoot!.textContent).not.toContain('Downloading 25%');
  });

  it.each(['preflight', 'plan'])('a new Repair in %s never displays the previous run percentage or ready stages', async phase => {
    run = { ...run, phase, downloadedBytes: 1004, readyCapabilities: ['retrieval-core'], packages: [{ packageId: 'embedding', state: 'downloading', bytesDownloaded: 1004, bytesTotal: 1024 }] };
    const el = await mount(); await click(el, 'Repair');
    expect(el.shadowRoot!.querySelector('[data-feature="meaning"]')!.textContent).toContain('Preparing download');
    expect(el.shadowRoot!.querySelector('.brain-headline')!.textContent).toBe('Installing…');
    expect(el.shadowRoot!.querySelector('.progress-bar')).toBeNull();
    expect(el.shadowRoot!.querySelector('[data-testid="install-staged-progress"]')).toBeNull();
    expect(el.shadowRoot!.querySelector('[label="Pause download"]')).not.toBeNull();
    expect(el.shadowRoot!.querySelector('[label="Cancel"]')).not.toBeNull();
    emit({ ...run, phase: 'download', downloadedBytes: 256, packages: [{ packageId: 'embedding', state: 'downloading', bytesDownloaded: 256, bytesTotal: 1024 }] });
    await el.updateComplete;
    expect(el.shadowRoot!.querySelector('[data-feature="meaning"]')!.textContent).toContain('Downloading 25%');
  });

  it('a pending package in the current run waits instead of claiming On from the old runtime', async () => {
    run = { ...run, packages: [{ packageId: 'embedding', state: 'pending', bytesDownloaded: 0, bytesTotal: 1024 }] };
    const el = await mount(); await click(el, 'Repair');
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
      expect(el.shadowRoot!.querySelector('[label="Cancel"]')).not.toBeNull();
    }
  });
});
