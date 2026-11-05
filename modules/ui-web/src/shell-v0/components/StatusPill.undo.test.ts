// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * DE6/L1 at the status pill: the undo notice of the person's own action shows for a few seconds,
 * held while it is hovered or focused, stays in Earlier with its action while the reversal is still
 * possible, and "Add <folder> again" from a removal record adds exactly that folder back, once.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { StatusPill } from './StatusPill.js';
import type { StatusPanel } from './StatusPanel.js';
import { AdvisoryStore } from './advisory/AdvisoryStore.js';
import { EPHEMERAL_TOAST_EVENT, type EphemeralToastSpec } from './advisory/ephemeralToast.js';
import { __resetAiStateForTest, type AiState } from '../state/aiStateStore.js';
import { __resetPendingForTest } from '../substrates/pending-effects/index.js';
import { offerReversal, __resetOwnReversalsForTest } from '../state/ownReversals.js';

let pill: StatusPill;
let store: AdvisoryStore;
const trigger = () => pill.shadowRoot!.querySelector<HTMLButtonElement>('.trigger')!;
const panel = () => pill.shadowRoot!.querySelector<StatusPanel>('jf-status-panel')!;
const action = () => pill.shadowRoot!.querySelector<HTMLButtonElement>('.transient-action');
const earlier = () => panel().model!.sections.earlier;
async function flush() { for (let i = 0; i < 4; i++) await Promise.resolve(); pill.status.refresh(); await pill.updateComplete; await panel().updateComplete; }
function ai(): AiState {
  const now = Date.now();
  return { phase: 'connected', connection: { reachable: true, lastSuccessMs: now, lastContactMs: now, consecutiveFailures: 0 },
    verdict: { kind: 'operational', severity: 'ok', reasons: [] }, stability: { kind: 'settled' },
    aiEngine: { kind: 'offline', stability: { kind: 'settled' }, installFailure: null },
    activity: { state: 'idle', shapeId: null, startedAtMs: null, canCancel: false, cancel: null },
    status: { worker: { core: { indexState: 'IDLE', pendingJobs: 0 }, enrichment: {} } },
    indexingStatusLive: true, snapshotLive: true, episodeMaxPendingJobs: 0, enrichSettleSamples: [],
  } as unknown as AiState;
}
let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(async () => {
  vi.useFakeTimers();
  fetchMock = vi.fn(async () => new Response('{}', { status: 503 }));
  vi.stubGlobal('fetch', fetchMock);
  __resetAiStateForTest(); __resetPendingForTest(); __resetOwnReversalsForTest();
  store = new AdvisoryStore(); store.start();
  pill = new StatusPill(); pill.store = store; document.body.append(pill);
  pill.status.ai = ai(); await flush();
});
afterEach(() => {
  pill.remove(); store.stop(); __resetAiStateForTest(); __resetPendingForTest(); __resetOwnReversalsForTest();
  vi.unstubAllGlobals(); vi.useRealTimers();
});

describe('DE6 the undo notice in the pill', () => {
  const offer = (reverse = vi.fn(async () => {})) => {
    offerReversal({ message: 'Skip rule added', reverse, reversed: 'Skip rules are back as they were' });
    return reverse;
  };

  it('shows "<what happened> · Undo" for a few seconds, then stays in Earlier with Undo while reversible', async () => {
    const reverse = offer(); await flush();
    expect(trigger().getAttribute('aria-label')).toBe('Skip rule added');
    expect(action()!.textContent).toContain('Undo');
    await vi.advanceTimersByTimeAsync(7_000); await flush();
    expect(action()).toBeNull();
    const row = earlier().find(fact => fact.title === 'Skip rule added')!;
    expect(row.actions!.map(a => a.label)).toEqual(['Undo']);
    panel().dispatchEvent(new CustomEvent('status-action', { detail: row.actions![0] }));
    await flush(); await flush();
    expect(reverse).toHaveBeenCalledTimes(1);
    expect(earlier().find(fact => fact.title === 'Skip rule added')!.actions).toEqual([]);
  });

  for (const how of ['hovered', 'focused'] as const) {
    it(`is held while ${how}, and leaves after the time it had left once released`, async () => {
      offer(); await flush();
      await vi.advanceTimersByTimeAsync(2_000); await flush();
      const target = action()!;
      target.dispatchEvent(new Event(how === 'hovered' ? 'mouseenter' : 'focus'));
      await vi.advanceTimersByTimeAsync(60_000); await flush();
      expect(action(), 'held').not.toBeNull();
      target.dispatchEvent(new Event(how === 'hovered' ? 'mouseleave' : 'blur'));
      await vi.advanceTimersByTimeAsync(3_000); await flush();
      expect(action(), 'still within its remaining time').not.toBeNull();
      await vi.advanceTimersByTimeAsync(1_500); await flush();
      expect(action()).toBeNull();
    });
  }

  it('the pill trigger holds the notice too', async () => {
    offer(); await flush();
    trigger().dispatchEvent(new MouseEvent('mouseenter'));
    await vi.advanceTimersByTimeAsync(30_000); await flush();
    expect(action()).not.toBeNull();
  });
});

describe('L1 "Add <folder> again" from Earlier', () => {
  function seen(): { list: EphemeralToastSpec[]; stop: () => void } {
    const list: EphemeralToastSpec[] = [];
    const listen = (event: Event) => list.push((event as CustomEvent<EphemeralToastSpec>).detail);
    document.addEventListener(EPHEMERAL_TOAST_EVENT, listen);
    return { list, stop: () => document.removeEventListener(EPHEMERAL_TOAST_EVENT, listen) };
  }
  const addAgain = { kind: 'add-folder-again' as const, target: 'C:\\Users\\Sam\\Notes', label: 'Add Notes again', collection: 'reports' };
  const invokes = () => fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/api/operations/core.add-watched-root/invoke'));

  it('adds exactly that folder back once, however often it is pressed, and says so', async () => {
    const notices = seen();
    try {
      let answer!: (response: Response) => void;
      fetchMock.mockImplementation(async (url: string) => String(url).includes('core.add-watched-root')
        ? new Promise<Response>(resolve => { answer = resolve; }) : new Response('{}', { status: 503 }));
      panel().dispatchEvent(new CustomEvent('status-action', { detail: addAgain }));
      panel().dispatchEvent(new CustomEvent('status-action', { detail: addAgain }));
      await flush();
      expect(invokes()).toHaveLength(1);
      expect(JSON.parse(String(invokes()[0]![1]!.body)).args).toEqual({ path: 'C:\\Users\\Sam\\Notes', collection: 'reports' });
      answer(new Response(JSON.stringify({ success: true }), { status: 200 }));
      await flush(); await flush();
      expect(notices.list.map(n => n.message)).toContain('Added Notes again. Reading its files.');
    } finally { notices.stop(); }
  });

  it('a refusal (the folder is already back, or gone from disk) is reported and can be tried again', async () => {
    const notices = seen();
    try {
      fetchMock.mockImplementation(async (url: string) => String(url).includes('core.add-watched-root')
        ? new Response(JSON.stringify({ success: false, errorClass: 'HANDLER_FAILURE', message: 'Already watched' }), { status: 400 })
        : new Response('{}', { status: 503 }));
      panel().dispatchEvent(new CustomEvent('status-action', { detail: addAgain })); await flush(); await flush();
      expect(notices.list.find(n => n.severity === 'error')?.message).toContain('Could not add Notes again.');
      panel().dispatchEvent(new CustomEvent('status-action', { detail: addAgain })); await flush(); await flush();
      expect(invokes()).toHaveLength(2);
    } finally { notices.stop(); }
  });
});
