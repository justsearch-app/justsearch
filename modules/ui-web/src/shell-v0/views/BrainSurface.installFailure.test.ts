// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * FC11 — a failed AI install, in the status panel and on AI Brain, says in words what went wrong and
 * what to do. The Engine's own message (a path, an exception) appears at most once on each, labelled
 * as detail for support, never repeated. The install status is the shape AiInstallService writes when
 * every download failed because the models folder could not be prepared; the AI verdict comes from
 * the one derivation (computeAiEngineVerdict).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AiState, InstallStatus } from '../state/aiStateStore.js';
import { computeAiEngineVerdict } from '../state/aiVerdict.js';
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
import { StatusPill } from '../components/StatusPill.js';

/** What the Engine said, verbatim in shape: AiInstallService.fail(ALL_DOWNLOADS_FAILED, …). */
const RAW = 'Failed to prepare download directory: /home/someone/.justsearch/models/embedding';
const FAILED: InstallStatus = { state: 'failed', phase: 'failed', errorCode: 'ALL_DOWNLOADS_FAILED', installedFully: false,
  lastError: `AI installation failed (0/3 packages installed; 3 failed). embedding: ${RAW}; reranker: ${RAW}`,
  message: '', startedAtEpochMs: 1794450000000, downloadedBytes: 0, totalBytes: 0, paused: false, packages: [] } as unknown as InstallStatus;

function failedState(): AiState {
  const aiEngine = computeAiEngineVerdict({ installStatus: FAILED, runtimeStatus: null, runtime: null as never, reachable: true,
    snapshotLive: true, engineState: 'Down', chatEnabledSpec: false, procedure: '', engineReason: 'engine-down', semanticEnabled: false });
  return {
    phase: 'connected', snapshotLive: true, installObservationLive: true, runtimeObservationLive: true, indexingStatusLive: true,
    connection: { reachable: true, lastContactMs: Date.now(), lastSuccessMs: Date.now() },
    verdict: { kind: 'operational', severity: 'info', reasons: [] }, stability: { kind: 'settled' },
    activity: { state: 'idle' }, aiEngine, installStatus: FAILED,
    runtime: { mode: 'offline', installed: { known: true, value: false } },
    capabilities: { chat: false }, status: { inference: { chatEnabledSpec: false } },
  } as unknown as AiState;
}

/** How many times the Engine's own message is in `text`. */
const occurrences = (text: string): number => text.split(String(FAILED.lastError)).length - 1;
const words = (node: Node | null | undefined): string => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();

beforeEach(() => {
  store.current = failedState();
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input);
    if (path.endsWith('/api/settings/v2')) return Response.json({ ui: { chatEnabled: false }, llm: {}, witness: { acceptedRevision: 0, lastCommittedOperationKey: null } });
    if (path.endsWith('/manifest')) return Response.json({ schemaVersion: 2, packages: [] });
    if (path.includes('/api/ai/install/status')) return Response.json(FAILED);
    return Response.json({});
  }));
});
afterEach(() => { document.body.innerHTML = ''; store.listeners.clear(); vi.unstubAllGlobals(); });

describe('FC11 the status panel\'s failed AI install', () => {
  it('says what went wrong and what to do, with the Engine\'s message once, under Details, labelled for support', async () => {
    const pill = new StatusPill();
    document.body.append(pill);
    await pill.updateComplete;
    pill.status.ai = failedState(); pill.status.refresh();
    pill.toggle();
    for (let i = 0; i < 4; i++) await Promise.resolve();
    await pill.updateComplete;
    const panel = pill.shadowRoot!.querySelector('jf-status-panel') as HTMLElement & { updateComplete: Promise<unknown> };
    await panel.updateComplete;
    const row = panel.shadowRoot!.querySelector<HTMLElement>('article[data-fact="ai-install-failed"]');
    expect(row, 'the failed install is listed').not.toBeNull();
    expect(words(row)).toContain('AI install failed');
    expect(words(row), 'what went wrong').toMatch(/could not be downloaded or saved/);
    expect(words(row), 'what to do').toMatch(/Check the internet connection and the free space on the disk, then install again/);
    expect(occurrences(words(panel.shadowRoot)), 'the Engine\'s message is not the row\'s words').toBe(0);
    const details = [...row!.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.trim() === 'Details');
    expect(details, 'Details are offered').toBeDefined();
    details!.click();
    await panel.updateComplete;
    const text = words(panel.shadowRoot);
    expect(occurrences(text), `the Engine's message once: ${text}`).toBe(1);
    const support = [...panel.shadowRoot!.querySelectorAll('[data-detail]')].filter((node) => (node.textContent ?? '').includes(RAW));
    expect(support).toHaveLength(1);
    expect(words(support[0]), 'labelled as detail for support').toMatch(/^Detail for support: /);
    pill.remove();
  });
});

describe('FC11 AI Brain\'s failed install', () => {
  it('says what went wrong and what to do, with the Engine\'s message once, labelled for support', async () => {
    const el = document.createElement('jf-brain-surface') as HTMLElement & { apiBase: string; host_: unknown; updateComplete: Promise<boolean> };
    el.apiBase = '';
    el.host_ = createMockHostApi({ data: { invokeOperation: async () => ({ success: true, structuredData: null }) } });
    document.body.append(el);
    for (let i = 0; i < 3; i++) { await new Promise((resolve) => setTimeout(resolve, 0)); await el.updateComplete; }
    const text = words(el.shadowRoot);
    expect(text, 'what went wrong').toMatch(/could not be downloaded or saved/);
    expect(text, 'what to do').toMatch(/then install again/);
    expect(occurrences(text), `the Engine's message once: ${text}`).toBe(1);
    const support = [...el.shadowRoot!.querySelectorAll('jf-error-alert')].filter((node) => (node.textContent ?? '').includes(RAW));
    expect(support).toHaveLength(1);
    expect(words(support[0]), 'labelled as detail for support').toMatch(/^Detail for support: /);
  });
});
