// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * Follow-up C1 amendments FC3a and FC3b: during an Engine outage, Diagnostics' Connection readout and
 * 'What you can do right now' say what the rows say (the status is unconfirmed), and no status line
 * ends with a dangling separator. The real-Engine check saw the readout take its words from the event
 * stream while status reads failed, 14-39 s after the Engine stopped; these cases rebuild that shape:
 * the status reads go through the real status poller and fail (or stop answering) while the stream
 * still beats, under the core declared presentation.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './HealthSurface.js';
// The declared body's root layout; the app registers it at start-up.
import '../renderers/layouts/VerticalLayout.js';
import type { HealthSurface } from './HealthSurface.js';
import { __feedContactForTest, __resetAiStateForTest, __tickClockForTest, getStatusReceivedAtMs, refreshStatusNow, startAiStateStore, type StatusSnapshot } from '../state/aiStateStore.js';
import { __resetEngineLossForTest } from '../state/engineLoss.js';
import { __resetPresentationForTest, applyPresentationBodies } from '../state/presentationRuntime.js';
import { CORE_DECLARED } from '../themes/builtinPresentations.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import { readIndexedRoots } from '../state/indexedRoots.js';

type Engine = 'up' | 'refused' | 'aborted' | 'server error' | 'hung';
let engine: Engine = 'up';
const json = (value: unknown, code = 200) => new Response(JSON.stringify(value), { status: code, headers: { 'Content-Type': 'application/json' } });
const status = (): StatusSnapshot => ({
  status: 'ok',
  worker: { core: { indexState: 'IDLE', indexHealthy: true, indexedDocuments: 5, searchableDocuments: 5, pendingJobs: 0 } },
  readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] }, aiFeatures: { state: 'READY', reasonCodes: [] } } },
  gpu: { available: true, cudaFunctional: true }, memoryUsedBytes: 5, memoryMaxBytes: 10,
} as unknown as StatusSnapshot);

/** The Engine as the browser sees it: answering, or each way a stopped Engine fails a read. */
async function answer(path: string): Promise<Response> {
  if (engine === 'refused') throw new TypeError('Failed to fetch');
  if (engine === 'aborted') throw new DOMException('The user aborted a request.', 'AbortError');
  if (engine === 'server error') return json({ error: 'Internal Server Error' }, 500);
  if (engine === 'hung') return new Promise<Response>(() => {});
  if (path.includes('/api/status')) return json(status());
  if (path.includes('/api/inference')) return json({ mode: 'online', starting: false, available: true });
  if (path.includes('/api/ai/install/status')) return json({ state: 'idle', installedFully: true });
  if (path.includes('/api/ai/runtime/status')) return json({ active: { modelPath: '/models/local', gpuLayers: 0 } });
  if (path.includes('indexing-roots')) return json({ items: [{ pathHash: 'notes', collection: 'default', status: 'indexed', walkCompleted: true, fileCount: 4, inFlightCount: 0, failedCount: 0 }], count: 1 });
  if (path === '/api/condition-recovery-index') return json({ entries: [] });
  if (path.includes('/stream')) return json({}, 404);
  return json({ jobs: [] });
}

let el: HealthSurface | undefined;
const row = (id: string) => el!.shadowRoot!.querySelector(`[data-health-row=${id}] dd`)?.textContent?.trim() ?? '';
async function settle(): Promise<void> {
  for (let i = 0; i < 20; i++) { await Promise.resolve(); await new Promise(resolve => setTimeout(resolve, 0)); }
  el!.requestUpdate(); await el!.updateComplete;
}
type Lit = HTMLElement & { updateComplete: Promise<unknown>; requestUpdate(): void; declaration?: { heading?: string } };
/** The live readout inside the declared Connection card: its words, its accessible name and its dot's tone. */
async function readout(): Promise<{ text: string; label: string; tone: string }> {
  for (let i = 0; i < 10; i++) { await Promise.resolve(); await el!.updateComplete; }
  const declared = [...el!.shadowRoot!.querySelectorAll<Lit>('jf-declared-surface')].find(node => node.declaration?.heading === 'Connection');
  expect(declared, 'the declared Connection card renders').toBeDefined();
  await declared!.updateComplete;
  const live = declared!.shadowRoot!.querySelector<Lit>('jf-liveness-readout');
  expect(live, 'its live readout renders').not.toBeNull();
  live!.requestUpdate(); await live!.updateComplete;
  const dot = live!.shadowRoot!.querySelector('.dot');
  return { text: live!.shadowRoot!.textContent?.replace(/\s+/g, ' ').trim() ?? '',
    label: dot?.getAttribute('aria-label') ?? '', tone: dot?.getAttribute('data-tone') ?? '' };
}
/** 'What you can do right now': each capability's label and the status it shows. */
async function capabilities(): Promise<Record<string, string>> {
  const map = el!.shadowRoot!.querySelector<Lit>('jf-capability-map')!;
  expect(map, 'the capability map renders').not.toBeNull();
  map.requestUpdate(); await map.updateComplete;
  return Object.fromEntries([...map.shadowRoot!.querySelectorAll('li')].map(item =>
    [item.querySelector('.cap-name')?.textContent?.trim() ?? '', item.querySelector('.cap-status')?.textContent?.trim() ?? '']));
}
/**
 * Every status line Health shows (its sentences, rows and headings) whose words end in a separator with
 * nothing after it. A data value's own "—" (no value) is not a status line.
 */
function danglingLines(): string[] {
  const lines = [...el!.shadowRoot!.querySelectorAll('p, dd, li, h2, h3, [role=status]')]
    .map(node => node.textContent?.replace(/\s+/g, ' ').trim() ?? '');
  return lines.filter(line => /\S\s*[—–:·]$/.test(line));
}

/** The app with the Engine answering and Health on screen, Diagnostics open, every source current. */
async function healthy(): Promise<void> {
  startAiStateStore('');
  await vi.waitFor(() => expect(getStatusReceivedAtMs()).not.toBeNull());
  await readIndexedRoots(path => answer(path));
  el = document.createElement('jf-health-surface') as HealthSurface;
  el.host_ = createMockHostApi({ data: { fetch: answer } });
  document.body.append(el);
  await vi.waitFor(() => expect(el!.loading).toBe(false));
  await settle();
  el.shadowRoot!.querySelector('details')!.open = true;
  await settle();
  expect(['engine', 'folders', 'index', 'gpu'].map(row), 'every row is quiet while the Engine answers').toEqual(['', '', '', '']);
  expect((await capabilities())['Search (keyword)'], 'keyword search is available while the Engine answers').toBe('Available');
}
/** One status read through the poller, then let Health's own reads finish. */
async function statusRead(): Promise<void> {
  await refreshStatusNow();
  await (el as unknown as { refresh(): Promise<void> }).refresh();
  await settle();
}

beforeEach(() => {
  __resetAiStateForTest(); __resetEngineLossForTest(); __resetPresentationForTest(); engine = 'up';
  vi.useFakeTimers({ toFake: ['Date'] }); vi.setSystemTime(new Date('2026-11-12T12:00:00Z'));
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => answer(new URL(String(input), 'http://localhost').pathname)));
  expect(applyPresentationBodies(CORE_DECLARED).ok).toBe(true);
});
afterEach(() => {
  el?.remove(); el = undefined;
  __resetAiStateForTest(); __resetEngineLossForTest(); __resetPresentationForTest(); vi.unstubAllGlobals(); vi.useRealTimers();
});

/** What the readout and the map must say once the rows read unconfirmed. */
async function expectSaidLikeTheRows(): Promise<void> {
  expect(row('engine'), 'the Engine row (the rule both must follow)').toMatch(/unconfirmed/i);
  const live = await readout();
  expect(live.text, `readout words ${JSON.stringify(live)}`).not.toMatch(/Catching up|Online|Ready|connected/i);
  expect(live.text, `readout words ${JSON.stringify(live)}`).toMatch(/Engine/);
  expect(live.text, `readout words ${JSON.stringify(live)}`).toMatch(/unconfirmed|can.t be reached|last known/i);
  expect(live.label, 'the accessible name says the same').toMatch(/unconfirmed|can.t be reached|last known/i);
  expect(['live', 'info'], `no healthy or in-progress dot ${JSON.stringify(live)}`).not.toContain(live.tone);
  const map = await capabilities();
  for (const [name, said] of Object.entries(map)) expect(said, `${name} ${JSON.stringify(map)}`).not.toMatch(/^Available/);
}

describe('FC3a the Connection readout and "What you can do right now" follow the rows during an outage', () => {
  it.each(['refused', 'aborted', 'server error'] as const)('status read %s while the event stream beats, 16 s after the last answer: the readout says the Engine status is unconfirmed, nothing listed as available', async failure => {
    await healthy();
    vi.setSystemTime(Date.now() + 16_000);
    __feedContactForTest();
    engine = failure;
    await statusRead();
    await expectSaidLikeTheRows();
  });

  it('status reads stop answering while the event stream beats, 25 s after the last answer: the same by expiry', async () => {
    await healthy();
    vi.setSystemTime(Date.now() + 25_000);
    __feedContactForTest();
    engine = 'hung';
    __tickClockForTest();
    await settle();
    await expectSaidLikeTheRows();
  });

  it('reads answer again: the readout and keyword search return without a reload', async () => {
    await healthy();
    vi.setSystemTime(Date.now() + 16_000);
    __feedContactForTest();
    engine = 'refused';
    await statusRead();
    await expectSaidLikeTheRows();
    vi.setSystemTime(Date.now() + 2_000);
    engine = 'up';
    await statusRead();
    const live = await readout();
    expect(live.text).not.toMatch(/unconfirmed/i);
    expect((await capabilities())['Search (keyword)']).toBe('Available');
  });
});

describe('FC3b no status line ends with a dangling separator', () => {
  it.each(['refused', 'server error'] as const)('status read %s: no line ends in "—" with nothing after it', async failure => {
    await healthy();
    vi.setSystemTime(Date.now() + 3_000);
    engine = failure;
    await statusRead();
    expect(row('index'), 'the outage is showing').toMatch(/unconfirmed/i);
    expect(danglingLines()).toEqual([]);
  });
});
