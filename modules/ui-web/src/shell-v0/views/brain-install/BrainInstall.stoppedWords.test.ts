// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// A1, A2 (fm-12b) — a stopped download says what is kept without naming a cause the app does not know
// (the Engine reports only that downloads failed), in the same words on AI Brain and in the status
// panel's entry; the part that stopped says how far it got: "Stopped at 120 of 956 MB". The AI state is
// built as BrainInstall.topBar.test.ts builds it, from the install status the Engine published in
// InstallFlowStatusLoopbackTest (failedPartsMissing), with the store's verdict (computeAiEngineVerdict).
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
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
import { __resetStoppedRunForTest } from '../../state/installFlow.js';
import { __resetTasksForTest } from '../../substrates/tasks/index.js';

type Recorded = { status?: Record<string, unknown>; presence?: unknown; preview?: unknown };
type Pkg = { packageId: string; bytesDownloaded: number; bytesTotal: number };
const ENGINE = JSON.parse(readFileSync(path.resolve('src/shell-v0/views/brain-install/__fixtures__/engine-install-statuses.json'), 'utf8')) as Record<string, Recorded>;
type Brain = HTMLElement & { apiBase: string; host_: unknown; updateComplete: Promise<boolean> };
const MB = 1024 ** 2;
const SENTENCE = 'What is already here is kept, and search by exact words keeps working.';
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
const words = (node: Element | ShadowRoot | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();

function aiState(status: Record<string, unknown>): AiState {
  const now = Date.now();
  const install = { ...status, updatedAtEpochMs: now } as never;
  const runtime = { mode: 'offline', installed: { known: true, value: false } };
  const aiEngine = computeAiEngineVerdict({ installStatus: install, runtimeStatus: { onnxFeatures: [] } as never, runtime: runtime as never,
    reachable: true, snapshotLive: true, engineState: 'Down' });
  return { phase: 'connected', connection: { reachable: true, lastSuccessMs: now, lastContactMs: now, consecutiveFailures: 0 },
    verdict: { kind: 'operational', severity: 'ok', reasons: [] }, stability: { kind: 'settled' },
    activity: { state: 'idle', shapeId: null, startedAtMs: null, canCancel: false, cancel: null },
    status: { worker: { core: { indexState: 'IDLE', pendingJobs: 0 }, enrichment: {} } },
    indexingStatusLive: true, snapshotLive: true, installObservationLive: true, runtimeObservationLive: true,
    episodeMaxPendingJobs: 0, enrichSettleSamples: [], index: { ownDocumentCount: { known: true, value: 8 } },
    capabilities: { chat: false }, runtimeStatus: { onnxFeatures: [] }, aiEngine, runtime, installStatus: install } as unknown as AiState;
}
/** failedPartsMissing with "Related words" stopped at 120 of 956 MB (the picture's amounts). */
function stoppedAt120(): Record<string, unknown> {
  const status = structuredClone(ENGINE.failedPartsMissing!.status!) as { packages: Pkg[] };
  const splade = status.packages.find(p => p.packageId === 'splade')!;
  splade.bytesDownloaded = 120 * MB; splade.bytesTotal = 956 * MB;
  return status as never;
}
async function settle(...els: Array<{ updateComplete: Promise<boolean> }>) {
  for (let i = 0; i < 8; ++i) { await new Promise(resolve => setTimeout(resolve, 0)); for (const el of els) await el.updateComplete; }
}
let brain: Brain;
let pill: StatusPill;
beforeEach(async () => {
  __resetStoppedRunForTest(); __resetTasksForTest();
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const address = String(input);
    if (address.endsWith('/api/ai/models/status')) return json(ENGINE.failedPartsMissing!.presence);
    if (address.includes('/plan-preview')) return json(ENGINE.failedPartsMissing!.preview);
    if (address.endsWith('/api/settings/v2')) return json({ ui: {}, llm: {}, witness: { acceptedRevision: 0, lastCommittedOperationKey: null } });
    if (address.endsWith('/api/ai/install/downloaded-files')) return json({ parts: [] });
    return json({}, 503);
  }));
  store.current = aiState(stoppedAt120());
  pill = new StatusPill(); document.body.append(pill);
  brain = document.createElement('jf-brain-surface') as Brain;
  brain.apiBase = ''; brain.host_ = createMockHostApi({ data: { invokeOperation: async () => ({ success: true, structuredData: null }) } });
  document.body.append(brain);
  await settle(brain, pill);
});
afterEach(() => { brain.remove(); pill.remove(); store.listeners.clear(); __resetStoppedRunForTest(); __resetTasksForTest(); vi.unstubAllGlobals(); });

it('A1: under "The download stopped" one sentence says what is kept and names no cause', async () => {
  expect(words(brain.shadowRoot!.querySelector('h1'))).toBe('The download stopped');
  expect(words(brain.shadowRoot!.querySelector('.brain-sentence'))).toBe(SENTENCE);
  expect(words(brain.shadowRoot!.querySelector('[data-testid="brain-simple-panel"]'))).not.toMatch(/connection|Completed parts/i);
});

it('A1: the status panel\'s entry says the same fact in the same words', async () => {
  pill.status.refresh(); await settle(pill);
  const panel = pill.shadowRoot!.querySelector('jf-status-panel') as HTMLElement & { updateComplete: Promise<boolean> };
  await panel.updateComplete;
  const entry = panel.shadowRoot!.querySelector('article[data-fact="ai-install-failed"]');
  expect(words(entry?.querySelector('.title .text'))).toBe('Download stopped');
  expect([...(entry?.querySelectorAll('p.detail') ?? [])].map(p => words(p))).toContain(SENTENCE);
  expect(words(panel.shadowRoot)).not.toMatch(/connection|Completed parts/i);
});

it('A2: the part that stopped reads "Stopped at 120 of 956 MB", its own amounts', async () => {
  const rows = [...brain.shadowRoot!.querySelectorAll('ul[aria-label="Parts"] > li')]
    .map(li => [...li.children].slice(0, 2).map(c => words(c)));
  expect(rows).toContainEqual(['Related words', 'Stopped at 120 of 956 MB']);
  expect(words(brain.shadowRoot!.querySelector('ul[aria-label="Parts"]'))).not.toContain('had');
});
