// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// fm-15 (fm-rest-a) — the status panel's reading entry as drawn:
//  S1 one entry: its title, one line of numbers, the bar and one action "Pause reading"; no duration
//     picker, no Details, no "Open AI Brain", no separate entry per folder.
//  S2 "Pause reading" pauses until resumed through the operation Library's "Until I resume" uses, with
//     its confirmation and its error handling; while paused the entry offers "Resume reading".
//  S3 no native select is drawn in the panel.
//  S4 with developer tools on, Details, the folder's entry and "Open AI Brain" show as before.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './StatusPanel.js';
import type { StatusPanel } from './StatusPanel.js';
import { deriveFrameStatus } from '../state/frameStatus.js';
import type { AiState } from '../state/aiStateStore.js';
import type { IndexedRootView } from '../../api/generated/schema-types/indexed-root-view.js';
import { setAuthorizationPresenter } from '../operations/authorizationBroker.js';

const now = Date.parse('2026-11-17T10:00:00Z');
function ai(core: Record<string, unknown>, operating?: Record<string, unknown>): AiState {
  return { phase: 'connected', connection: { reachable: true, lastSuccessMs: now, lastContactMs: now, consecutiveFailures: 0 },
    verdict: { kind: 'operational', severity: 'ok', reasons: [] }, stability: { kind: 'settled' },
    aiEngine: { kind: 'not_installed', notRequested: true, stability: { kind: 'settled' }, installFailure: null },
    activity: { state: 'idle', shapeId: null, startedAtMs: null, canCancel: false, cancel: null },
    status: { ...(operating ? { operating } : {}), worker: { core, enrichment: {} } },
    installStatus: null, installObservationLive: false,
    indexingStatusLive: true, snapshotLive: true, episodeMaxPendingJobs: 16, enrichSettleSamples: [],
  } as unknown as AiState;
}
/** 8 of 16 files read, the Documents folder among them. */
const READING = ai({ indexState: 'INDEXING', pendingJobs: 8 });
const PAUSED = ai({ indexState: 'INDEXING', pendingJobs: 8 }, { indexing: { state: 'PAUSED', reasons: ['user_paused'], untilEpochMs: null, waitingForResume: true } });
const DOCS = { pathHash: 'docs', collection: 'default', status: 'scanning', inFlightCount: 8, failedCount: 0, walkCompleted: true, fileCount: 8 } as IndexedRootView;

type Mounted = HTMLElement & { updateComplete: Promise<unknown>; shadowRoot: ShadowRoot };
async function settle(...els: ({ updateComplete: Promise<unknown> } | null | undefined)[]): Promise<void> {
  for (let i = 0; i < 6; i++) {
    for (const el of els) await el?.updateComplete;
    await new Promise(resolve => setTimeout(resolve, 0));
  }
}
async function panel(state: AiState, developer = false): Promise<StatusPanel> {
  const el = document.createElement('jf-status-panel') as StatusPanel;
  el.model = deriveFrameStatus({ ai: state, now, contactLostAt: null, roots: [DOCS], rootPaths: new Map([['docs', '/home/me/Documents']]),
    rootsLive: true, developer });
  el.developer = developer;
  el.apiBase = '';
  document.body.append(el);
  await settle(el, controls(el));
  return el;
}
const entry = (el: StatusPanel, id = 'indexing') => el.shadowRoot!.querySelector<HTMLElement>(`article[data-fact="${id}"]`);
const controls = (el: StatusPanel) => el.shadowRoot?.querySelector<Mounted>('jf-indexing-controls') ?? null;
/** Every button an entry draws, its reading controls' own buttons included, by its words. */
function buttons(row: HTMLElement | null): string[] {
  const own = [...row?.querySelectorAll('button') ?? []];
  const inner = [...row?.querySelector<Mounted>('jf-indexing-controls')?.shadowRoot?.querySelectorAll('button') ?? []];
  return [...own, ...inner].map(button => button.textContent!.replace(/\s+/g, ' ').trim());
}
const pauseButton = (el: StatusPanel) => [...controls(el)!.shadowRoot.querySelectorAll('button')]
  .find(button => button.textContent!.includes('Pause reading'))!;

let fetchMock: ReturnType<typeof vi.fn>;
beforeEach(() => {
  fetchMock = vi.fn(async () => { throw new TypeError('No backend in this test'); });
  vi.stubGlobal('fetch', fetchMock);
});
afterEach(() => {
  document.body.replaceChildren();
  setAuthorizationPresenter(null);
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('S1 / S3 one reading entry with one action', () => {
  it('Running holds the reading entry only: its title, one line of numbers, the bar and "Pause reading"', async () => {
    const el = await panel(READING);
    const running = el.shadowRoot!.querySelector('section[aria-label="Running"]')!;
    expect([...running.querySelectorAll('article')].map(row => row.dataset.fact)).toEqual(['indexing']);
    const row = entry(el)!;
    expect(row.querySelector('.title .text')!.textContent!.trim()).toBe('Reading');
    expect([...row.querySelectorAll('p.detail')].map(line => line.textContent!.trim())).toEqual(['8 files remaining']);
    expect(row.querySelectorAll('progress')).toHaveLength(1);
    expect(buttons(row)).toEqual(['Pause reading']);
  });
  it('no native select and no "Pause duration" anywhere in the panel', async () => {
    const el = await panel(READING);
    expect(el.shadowRoot!.querySelector('select')).toBeNull();
    expect(controls(el)!.shadowRoot.querySelector('select')).toBeNull();
    expect(controls(el)!.shadowRoot.textContent).not.toContain('Pause duration');
  });
});

describe('S2 Pause reading pauses until resumed', () => {
  it('through core.indexing-pause without a deadline, with its approval, and then waits for the status', async () => {
    const el = await panel(READING);
    const presenter = vi.fn().mockResolvedValue({ approved: true, allowAlways: false });
    setAuthorizationPresenter(presenter);
    fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).endsWith('/authorizations/approve')) return Response.json({ capsule: 'approved' });
      if (String(input).endsWith('/core.indexing-pause/invoke')) {
        return JSON.parse(String(init?.body)).confirmationToken
          ? Response.json({ success: true, message: 'Accepted' })
          : Response.json({ success: false, errorClass: 'CONFIRMATION_REQUIRED', pendingId: 'pause-1', gateBehavior: 'INLINE_CONFIRM' }, { status: 428 });
      }
      throw new TypeError('No backend in this test');
    });
    pauseButton(el).click();
    await settle(el, controls(el));
    expect(presenter).toHaveBeenCalledWith(expect.objectContaining({ operationId: 'core.indexing-pause', pendingId: 'pause-1' }));
    const calls = fetchMock.mock.calls.filter(call => String(call[0]).endsWith('/core.indexing-pause/invoke'));
    expect(calls).toHaveLength(2);
    for (const call of calls) expect(JSON.parse(String(call[1]!.body)).args).toEqual({});
    expect(controls(el)!.shadowRoot.textContent).toContain('Waiting for status to confirm the request.');
  });
  it('a request that does not go through says so in the entry', async () => {
    const el = await panel(READING);
    pauseButton(el).click();
    await settle(el, controls(el));
    expect(fetchMock.mock.calls.some(call => String(call[0]).endsWith('/core.indexing-pause/invoke'))).toBe(true);
    expect(controls(el)!.shadowRoot.textContent).toContain('Could not confirm the reading request. Check the current status before trying again.');
  });
  it('while paused the entry offers "Resume reading"', async () => {
    const el = await panel(PAUSED);
    const row = entry(el, 'indexing-hold');
    expect(row).not.toBeNull();
    expect(buttons(row)).toContain('Resume reading');
    expect(el.shadowRoot!.querySelector('select')).toBeNull();
  });
});

describe('S4 developer tools', () => {
  it('Details, "Open AI Brain" and the folder\'s own entry show as before', async () => {
    const el = await panel(READING, true);
    const row = entry(el)!;
    expect(buttons(row)).toEqual(expect.arrayContaining(['Pause reading', 'Open AI Brain', 'Details']));
    expect(entry(el, 'folder-work:docs')).not.toBeNull();
    expect(buttons(entry(el, 'folder-work:docs'))).toEqual(['Show in Library']);
  });
});
