// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * FC11a — AI Brain after a failed install, also when the install status says files are missing
 * (`repairNeeded`, as the Engine reports after a failed fresh install): its heading says the install
 * failed, never "Installed — repair available"; its words say what went wrong and what to do, from
 * the shared AI verdict, so they are the status panel's words for the same install. Advice follows
 * the cause only where the Engine's error code names one (a models folder that cannot be written);
 * a code that only says the downloads failed guesses no cause. The Engine's own message is there
 * once, as detail for support.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
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

/** What the Engine said when it could not prepare the models folder (AcquisitionStage), verbatim in shape. */
const RAW = 'Failed to prepare download directory: /home/someone/.justsearch/models/onnx';

function failed(errorCode: string): InstallStatus {
  return { state: 'failed', phase: 'failed', errorCode, installedFully: false, repairNeeded: true,
    lastError: `AI installation failed (0/5 packages installed; 5 failed). embedding: ${RAW}; splade: ${RAW}`,
    message: '', startedAtEpochMs: 1, downloadedBytes: 0, totalBytes: 0, paused: false, packages: [] } as unknown as InstallStatus;
}

function failedState(install: InstallStatus): AiState {
  const aiEngine = computeAiEngineVerdict({ installStatus: install, runtimeStatus: null, runtime: null as never, reachable: true,
    snapshotLive: true, engineState: 'Down', chatEnabledSpec: false, procedure: '', engineReason: 'engine-down', semanticEnabled: false });
  return {
    phase: 'connected', snapshotLive: true, installObservationLive: true, runtimeObservationLive: true, indexingStatusLive: true,
    connection: { reachable: true, lastContactMs: Date.now(), lastSuccessMs: Date.now() },
    verdict: { kind: 'operational', severity: 'info', reasons: [] }, stability: { kind: 'settled' },
    activity: { state: 'idle' }, aiEngine, installStatus: install,
    runtime: { mode: 'offline', installed: { known: true, value: false } },
    capabilities: { chat: false }, status: { inference: { chatEnabledSpec: false } },
  } as unknown as AiState;
}

const words = (node: Node | null | undefined): string => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();

afterEach(() => { document.body.innerHTML = ''; store.listeners.clear(); vi.unstubAllGlobals(); });

/** The status panel's row for the failed install: its title and its words. */
async function panelRow(state: AiState): Promise<{ title: string; detail: string }> {
  const pill = new StatusPill();
  document.body.append(pill);
  await pill.updateComplete;
  pill.status.ai = state; pill.status.refresh();
  pill.toggle();
  for (let i = 0; i < 4; i++) await Promise.resolve();
  await pill.updateComplete;
  const panel = pill.shadowRoot!.querySelector('jf-status-panel') as HTMLElement & { updateComplete: Promise<unknown> };
  await panel.updateComplete;
  const row = panel.shadowRoot!.querySelector<HTMLElement>('article[data-fact="ai-install-failed"]');
  expect(row, 'the status panel lists the failed install').not.toBeNull();
  const fact = pill.status.model.sections.needs.find((r) => r.id === 'ai-install-failed')!;
  expect(words(row), 'the row shows its title').toContain(fact.title);
  expect(words(row), 'the row shows its words').toContain(fact.detail);
  pill.remove();
  return { title: fact.title, detail: fact.detail ?? '' };
}

/** AI Brain with the failed install observed: its heading, its words and its whole text. */
async function brain(install: InstallStatus): Promise<{ heading: string; detail: string; text: string }> {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input);
    if (path.endsWith('/api/settings/v2')) return Response.json({ ui: { chatEnabled: false }, llm: {}, witness: { acceptedRevision: 0, lastCommittedOperationKey: null } });
    if (path.endsWith('/manifest')) return Response.json({ schemaVersion: 2, packages: [] });
    if (path.includes('/api/ai/install/status')) return Response.json(install);
    return Response.json({});
  }));
  const el = document.createElement('jf-brain-surface') as HTMLElement & { apiBase: string; host_: unknown; updateComplete: Promise<boolean> };
  el.apiBase = '';
  el.host_ = createMockHostApi({ data: { invokeOperation: async () => ({ success: true, structuredData: null }) } });
  document.body.append(el);
  for (let i = 0; i < 3; i++) { await new Promise((resolve) => setTimeout(resolve, 0)); await el.updateComplete; }
  const root = el.shadowRoot!;
  const result = { heading: words(root.querySelector('h1.brain-headline')), detail: words(root.querySelector('[data-testid="brain-simple-panel"] > .brain-status-detail')), text: words(root) };
  el.remove();
  return result;
}

describe('FC11a AI Brain after a failed install', () => {
  it.each([
    ['ALL_DOWNLOADS_FAILED', /could be downloaded and saved/, /internet|connection|free space/i],
    ['INSTALL_IO_ERROR', /folder for AI models could not be created or written to/, /internet|connection/i],
    ['SOMETHING_NEW', /stopped before it finished/, /internet|connection|free space/i],
  ] as const)('%s: says it failed, what went wrong and what to do, as the status panel does', async (code, cause, guess) => {
    const install = failed(code);
    store.current = failedState(install);
    const shown = await brain(install);
    expect(shown.heading, 'the heading says the install failed').toBe('AI install failed');
    expect(shown.text).not.toContain('Installed — repair available');
    expect(shown.text).not.toContain('A required component is missing');
    expect(shown.detail, 'what went wrong').toMatch(cause);
    expect(shown.detail, 'what to do').toMatch(/install again/i);
    expect(shown.detail, 'no cause the error code does not name').not.toMatch(guess);
    expect(shown.text.split(RAW).length - 1, 'the Engine\'s message is there').toBeGreaterThan(0);
    expect(shown.text.split(String(install.lastError)).length - 1, 'the Engine\'s message, once').toBe(1);
    expect(shown.text).toContain(`Detail for support: ${install.lastError}`);

    const panel = await panelRow(failedState(install));
    expect(panel.title, 'the status panel and AI Brain name it alike').toBe(shown.heading);
    expect(panel.detail, 'the status panel and AI Brain say the same words').toBe(shown.detail);
  });
});
