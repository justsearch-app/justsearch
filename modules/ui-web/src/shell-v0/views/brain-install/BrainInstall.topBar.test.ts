// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// Slice 7b (fm-12b, G5') — the top bar's status entry follows AI Brain's stopped view: while a failed
// run with parts missing is shown it says "Download stopped" in amber with its count (the picture's
// "1 Download stopped"), not the red failure; after "Cancel install" it returns to normal, and a later
// run's failure shows again. The real status pill and AI Brain page read one AI state, built from the
// install statuses the Engine published in InstallFlowStatusLoopbackTest.
// G3''' (fm-12) — while a run is active or stopped the bar shows it, also in first use before any folder
// was added: "Installing AI · N%", "Installing AI · N% · Paused", "Download stopped"; the rest of first
// use's hiding stays (calm and other work, such as "Starting AI models", stay hidden then).
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import type { AiState } from '../../state/aiStateStore.js';
import { computeAiEngineVerdict } from '../../state/aiVerdict.js';
const store = vi.hoisted(() => ({ listeners: new Set<(state: AiState) => void>(), current: {} as AiState }));
vi.mock('../../state/aiStateStore.js', async original => ({
  ...await original<typeof import('../../state/aiStateStore.js')>(),
  subscribeAiState: (listener: (state: AiState) => void) => {
    store.listeners.add(listener); listener(store.current);
    return () => store.listeners.delete(listener);
  },
  getAiState: () => store.current,
}));
vi.mock('../../../utils/windowFocus.js', () => ({ isWindowFocused: vi.fn(async () => false) }));
vi.mock('../../../utils/notify.js', () => ({ sendDesktopNotification: vi.fn(async () => {}) }));
import '../BrainSurface.js';
import { StatusPill } from '../../components/StatusPill.js';
import { createMockHostApi } from '../../plugin-api/testHostApi.js';
import * as installFlow from '../../state/installFlow.js';
// The window's "Cancel install" of a stopped run starts empty in each test.
const resetStoppedRun = () => (installFlow as { __resetStoppedRunForTest?: () => void }).__resetStoppedRunForTest?.();
import { __resetTasksForTest } from '../../substrates/tasks/index.js';
import { getFirstUse } from '../../state/firstUse.js';
import { readIndexedRoots } from '../../state/indexedRoots.js';

type Recorded = { status?: Record<string, unknown>; presence?: unknown; preview?: unknown };
const ENGINE = JSON.parse(readFileSync(path.resolve('src/shell-v0/views/brain-install/__fixtures__/engine-install-statuses.json'), 'utf8')) as Record<string, Recorded>;
interface Brain extends HTMLElement { apiBase: string; host_: unknown; updateComplete: Promise<boolean> }
interface Control extends HTMLElement { updateComplete: Promise<boolean>; label?: string }
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
let presence: unknown;
let preview: unknown;
/** The worker's own searchable count; 0 with no folders is first use (firstUse.ts `empty`). */
let searchable: number | undefined;
/** The Engine answers the folders list with none. */
let noFolders = false;

/** The one AI state both read: the recorded install status, a reachable Engine without AI, search working by words. */
function aiState(status: Record<string, unknown>): AiState {
  const now = Date.now();
  const install = { ...status, updatedAtEpochMs: now } as never;
  const runtime = { mode: 'offline', installed: { known: true, value: false } };
  const aiEngine = computeAiEngineVerdict({ installStatus: install, runtimeStatus: { onnxFeatures: [] } as never, runtime: runtime as never,
    reachable: true, snapshotLive: true, engineState: 'Down' });
  return { phase: 'connected', connection: { reachable: true, lastSuccessMs: now, lastContactMs: now, consecutiveFailures: 0 },
    verdict: { kind: 'operational', severity: 'ok', reasons: [] }, stability: { kind: 'settled' },
    activity: { state: 'idle', shapeId: null, startedAtMs: null, canCancel: false, cancel: null },
    status: { worker: { core: { indexState: 'IDLE', pendingJobs: 0, ...(searchable === undefined ? {} : { ownSearchableDocuments: searchable }) }, enrichment: {} } },
    indexingStatusLive: true, snapshotLive: true, installObservationLive: true, runtimeObservationLive: true,
    episodeMaxPendingJobs: 0, enrichSettleSamples: [], index: { ownDocumentCount: { known: true, value: 8 } },
    capabilities: { chat: false }, runtimeStatus: { onnxFeatures: [] }, aiEngine, runtime, installStatus: install } as unknown as AiState;
}
async function settle(...els: Array<{ updateComplete: Promise<boolean> }>) {
  for (let i = 0; i < 8; ++i) { await new Promise(resolve => setTimeout(resolve, 0)); for (const el of els) await el.updateComplete; }
}
async function publish(key: string, patch: Record<string, unknown> = {}) {
  const recorded = ENGINE[key]!;
  if (recorded.presence) presence = recorded.presence;
  if (recorded.preview) preview = recorded.preview;
  store.current = aiState({ ...recorded.status!, ...patch });
  for (const listener of store.listeners) listener(store.current);
  await settle(brain, pill);
}
const words = (node: Element | ShadowRoot | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
/** The top bar's status entry as shown: its accessible name, visible words, count badge and tone. */
function entry() {
  const root = pill.shadowRoot!;
  const trigger = root.querySelector('.trigger')!;
  return { name: trigger.getAttribute('aria-label'), words: words(root.querySelector('.label')),
    count: words(root.querySelector('.count')), tone: (root.querySelector('jf-status-badge') as HTMLElement & { tone?: string }).tone };
}
async function press(label: string) {
  const host = [...brain.shadowRoot!.querySelectorAll<Control>('jf-button')].find(b => b.label === label);
  expect(host, label).toBeTruthy();
  await host!.updateComplete;
  const control = host!.shadowRoot!.querySelector<Control>('jf-control')!; await control.updateComplete;
  control.shadowRoot!.querySelector('button')!.click(); await settle(brain, pill);
}
const headline = () => words(brain.shadowRoot!.querySelector('h1'));
let brain: Brain;
let pill: StatusPill;

beforeEach(async () => {
  resetStoppedRun(); __resetTasksForTest(); searchable = undefined;
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const address = String(input);
    if (address.endsWith('/api/ai/models/status')) return json(presence);
    if (address.includes('/plan-preview')) return json(preview);
    if (noFolders && address.includes('/api/indexing-roots/substrate')) return json({ items: [] });
    if (address.endsWith('/api/settings/v2')) return json({ ui: {}, llm: {}, witness: { acceptedRevision: 0, lastCommittedOperationKey: null } });
    if (address.endsWith('/api/ai/install/downloaded-files')) return json({ parts: [] });
    return json({}, 503);
  }));
  presence = ENGINE.failedPartsMissing!.presence; preview = ENGINE.failedPartsMissing!.preview;
  store.current = aiState(ENGINE.running!.status!);
  pill = new StatusPill(); document.body.append(pill);
  brain = document.createElement('jf-brain-surface') as Brain;
  brain.apiBase = ''; brain.host_ = createMockHostApi({ data: { invokeOperation: async () => ({ success: true, structuredData: { state: 'running' } }) } });
  document.body.append(brain);
  await settle(brain, pill);
});
afterEach(() => { brain.remove(); pill.remove(); store.listeners.clear(); resetStoppedRun(); __resetTasksForTest(); vi.unstubAllGlobals(); });

describe('fm-12b the top bar follows the stopped view', () => {
  it('a failed run with a part missing: "1 Download stopped" in amber, never the red failure', async () => {
    await publish('failedPartsMissing');
    expect(headline()).toBe('The download stopped');
    expect(entry()).toEqual({ name: 'Download stopped', words: 'Download stopped', count: '1', tone: 'warning' });
    pill.status.refresh(); await settle(pill);
    const panel = pill.shadowRoot!.querySelector('jf-status-panel')!;
    await (panel as HTMLElement & { updateComplete: Promise<boolean> }).updateComplete;
    expect(words(panel.shadowRoot)).toContain('Download stopped');
    expect(words(panel.shadowRoot)).toContain('Completed parts stay on this computer, and search by exact words keeps working.');
    expect(words(panel.shadowRoot)).not.toMatch(/AI install failed|Install again to continue/);
  });

  it('a failed first install with nothing placed is a stopped download in the bar too', async () => {
    await publish('failedNothingPlaced');
    expect(headline()).toBe('The download stopped');
    expect(entry()).toMatchObject({ words: 'Download stopped', count: '1', tone: 'warning' });
  });

  it('after Cancel install the bar returns to normal; the same failed poll stays closed; a later run\'s failure shows again', async () => {
    await publish('failedPartsMissing');
    expect(entry().words).toBe('Download stopped');
    await press('Cancel install');
    expect(headline()).not.toBe('The download stopped');
    const closed = entry();
    expect(closed.name).not.toMatch(/Download stopped|needs? you|AI install failed/i);
    expect(closed.count).toBe('');
    await publish('failedPartsMissing');
    expect(entry().name).not.toMatch(/Download stopped|needs? you/i);
    await publish('retryRunning');
    expect(entry().name).not.toMatch(/Download stopped/);
    await publish('failedPartsMissing', { startedAtEpochMs: (ENGINE.failedPartsMissing!.status!.startedAtEpochMs as number) + 60_000 });
    expect(headline()).toBe('The download stopped');
    expect(entry()).toMatchObject({ words: 'Download stopped', count: '1', tone: 'warning' });
  });

  it('without the AI Brain page the bar decides from the install status alone', async () => {
    brain.remove();
    await publish('failedPartsMissing');
    expect(entry()).toMatchObject({ words: 'Download stopped', count: '1', tone: 'warning' });
  });
});

describe("G3''' the bar shows an install run in first use, before any folder was added", () => {
  /** The bar's status entry as shown, or null while first use hides it. */
  const shown = () => pill.shadowRoot!.querySelector('.trigger') ? entry() : null;
  beforeEach(async () => {
    searchable = 0;
    // No folders, confirmed by the Engine's own roots answer (the real roots observation).
    noFolders = true;
    await readIndexedRoots(async () => json({ items: [] }));
    await publish('restartAfterFailedNothingPlaced');
    expect(getFirstUse().empty).toBe(true);
  });
  afterEach(async () => { noFolders = false; await readIndexedRoots(async () => json({}, 503)); });

  it('while the first install runs: "Installing AI · 18%" (fm-12), with its ring', async () => {
    await publish('running');
    expect(shown()).toMatchObject({ name: 'Installing AI · 18%', words: 'Installing AI · 18%' });
    expect(pill.shadowRoot!.querySelector('svg.ring')!.getAttribute('data-percent')).toBe('18');
  });

  it('while it is paused: "Installing AI · 18% · Paused"; Resume shows the running words again', async () => {
    await publish('pausedBetweenFiles');
    expect(shown()).toMatchObject({ name: 'Installing AI · 18% · Paused', words: 'Installing AI · 18% · Paused' });
    await publish('pausedFileFinishing');
    expect(shown()?.words).toBe('Installing AI · 18% · Paused');
    await publish('running');
    expect(shown()?.words).toBe('Installing AI · 18%');
  });

  it('a stopped download still says "Download stopped" in amber', async () => {
    await publish('failedNothingPlaced');
    expect(shown()).toMatchObject({ words: 'Download stopped', count: '1', tone: 'warning' });
  });

  it('preserved: without an install run first use still hides the calm status and other work', async () => {
    // After a restart with nothing downloaded: AI not installed, no run.
    await publish('restartAfterFailedNothingPlaced');
    expect(pill.status.model.pill.kind).toBe('calm');
    expect(shown()).toBeNull();
    store.current = { ...aiState(ENGINE.restartAfterFailedNothingPlaced!.status!),
      aiEngine: { kind: 'starting', stability: { kind: 'provisional', cause: 'starting' }, installFailure: null } } as unknown as AiState;
    for (const listener of store.listeners) listener(store.current);
    await settle(brain, pill);
    expect(pill.status.model.pill).toMatchObject({ kind: 'working', label: 'Starting AI models' });
    expect(shown()).toBeNull();
  });
});
