// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * The pill as drawn in the frame design (design-system.md §7 and §9; ref-07, ref-09, ref-21, ref-22):
 * needs-you count in a badge, a progress ring for a known fraction, icon-only below 1000 px with
 * the same accessible name, and the undo notice that names Ctrl+Z and answers to it.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { StatusPill } from './StatusPill.js';
import type { StatusPanel } from './StatusPanel.js';
import { AdvisoryStore } from './advisory/AdvisoryStore.js';
import { emitEphemeralToast } from './advisory/ephemeralToast.js';
import { __resetAiStateForTest, type AiState } from '../state/aiStateStore.js';
import { proposeEffect, __resetPendingForTest } from '../substrates/pending-effects/index.js';
import { CORE_PROVENANCE } from '../primitives/provenance.js';

let pill: StatusPill;
let store: AdvisoryStore;
const trigger = () => pill.shadowRoot!.querySelector<HTMLButtonElement>('.trigger')!;
const panel = () => pill.shadowRoot!.querySelector<StatusPanel>('jf-status-panel')!;
async function flush() { for (let i = 0; i < 4; i++) await Promise.resolve(); pill.status.refresh(); await pill.updateComplete; await panel().updateComplete; }
function ai(overrides: Partial<AiState> = {}): AiState {
  const now = Date.now();
  return { phase: 'connected', connection: { reachable: true, lastSuccessMs: now, lastContactMs: now, consecutiveFailures: 0 },
    verdict: { kind: 'operational', severity: 'ok', reasons: [] }, stability: { kind: 'settled' },
    aiEngine: { kind: 'offline', stability: { kind: 'settled' }, installFailure: null },
    activity: { state: 'idle', shapeId: null, startedAtMs: null, canCancel: false, cancel: null },
    status: { worker: { core: { indexState: 'IDLE', pendingJobs: 0 }, enrichment: {} } },
    indexingStatusLive: true, snapshotLive: true, episodeMaxPendingJobs: 0, enrichSettleSamples: [], ...overrides,
  } as AiState;
}
const viewport = (width: number) => (window as unknown as { happyDOM: { setViewport(v: { width: number; height: number }): void } })
  .happyDOM.setViewport({ width, height: 800 });

beforeEach(async () => {
  viewport(1280);
  vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 503 })));
  __resetAiStateForTest(); __resetPendingForTest();
  store = new AdvisoryStore(); store.start();
  pill = new StatusPill(); pill.store = store; document.body.append(pill);
  pill.status.ai = ai(); await flush();
});
afterEach(() => { pill.remove(); store.stop(); __resetAiStateForTest(); __resetPendingForTest(); vi.unstubAllGlobals(); viewport(1024); });

describe('status pill drawing', () => {
  it('ref-09 needs you shows its count in a badge followed by the words, and names both', async () => {
    proposeEffect({ kind: 'navigate', to: '#one' }, CORE_PROVENANCE, 'agent'); await flush();
    expect(trigger().querySelector('.count')!.textContent).toBe('1');
    expect(trigger().querySelector('.label')!.textContent).toBe('Needs you');
    expect(trigger().getAttribute('aria-label')).toBe('1 needs you');
    proposeEffect({ kind: 'navigate', to: '#two' }, CORE_PROVENANCE, 'agent'); await flush();
    expect(trigger().querySelector('.count')!.textContent).toBe('2');
    expect(trigger().querySelector('.label')!.textContent).toBe('Need you');
    expect(trigger().getAttribute('aria-label')).toBe('2 need you');
    expect(trigger().textContent).not.toMatch(/\(\d\)/);
  });

  it('working with a known fraction draws a ring instead of "· N%" text; unknown fractions draw none', async () => {
    pill.status.ai = ai({ installStatus: { state: 'running', totalBytes: 100, downloadedBytes: 40 }, installObservationLive: true,
      aiEngine: { kind: 'installing', stability: { kind: 'settled' }, installFailure: null } } as unknown as Partial<AiState>);
    await flush();
    expect(pill.status.model.pill.kind).toBe('working');
    const ring = trigger().querySelector('svg.ring')!;
    expect(ring.getAttribute('data-percent')).toBe('40');
    expect(ring.querySelector('.arc')!.getAttribute('stroke-dasharray')).toBe('40 100');
    expect(trigger().querySelector('.label')!.textContent).toBe('Installing AI models');
    expect(trigger().textContent).not.toContain('%');
    expect(trigger().getAttribute('aria-label')).toBe('Installing AI models · 40%');
    pill.status.ai = ai({ installStatus: { state: 'running' }, installObservationLive: true,
      aiEngine: { kind: 'installing', stability: { kind: 'settled' }, installFailure: null } } as unknown as Partial<AiState>);
    await flush();
    expect(trigger().querySelector('svg.ring')).toBeNull();
    expect(trigger().getAttribute('aria-label')).toBe('Installing AI models');
  });

  it('below 1000 px the pill is an icon (badge or dot) with the same accessible name, without truncated text', async () => {
    expect(trigger().querySelector('.label')!.textContent).toBe('Up to date');
    viewport(900); await flush();
    expect(trigger().querySelector('.label')).toBeNull();
    expect(trigger().querySelector('.symbol')!.textContent).toBe('●');
    expect(trigger().getAttribute('aria-label')).toBe('Up to date');
    proposeEffect({ kind: 'navigate', to: '#one' }, CORE_PROVENANCE, 'agent'); await flush();
    expect(trigger().querySelector('.label')).toBeNull();
    expect(trigger().querySelector('.count')!.textContent).toBe('1');
    expect(trigger().getAttribute('aria-label')).toBe('1 needs you');
    // Own-action feedback stays readable at any width (C1).
    emitEphemeralToast({ message: 'Could not copy the URL.', severity: 'error' }); await flush();
    expect(trigger().querySelector('.label')!.textContent).toBe('Could not copy the URL.');
    viewport(1280); await flush();
    expect(trigger().querySelector('.label')).not.toBeNull();
  });

  it('ref-07 the undo notice names Ctrl+Z, and the shell undo command reverses that notice once', async () => {
    const undo = vi.fn();
    emitEphemeralToast({ message: 'Skip rule added', actionLabel: 'Undo', onAction: undo }); await flush();
    const action = pill.shadowRoot!.querySelector<HTMLButtonElement>('.transient-action')!;
    expect(action.querySelector('kbd')!.textContent).toBe('Ctrl+Z');
    expect(action.getAttribute('aria-keyshortcuts')).toBe('Control+Z');
    expect(pill.undoNotice()).toBe(true);
    await flush();
    expect(undo).toHaveBeenCalledTimes(1);
  });

  it('a notice whose action is not an undo neither claims Ctrl+Z nor takes the undo command', async () => {
    const reveal = vi.fn();
    emitEphemeralToast({ message: 'Saved', actionLabel: 'Show in folder', onAction: reveal }); await flush();
    const action = pill.shadowRoot!.querySelector<HTMLButtonElement>('.transient-action')!;
    expect(action.textContent).toBe('Show in folder');
    expect(action.querySelector('kbd')).toBeNull();
    expect(pill.undoNotice()).toBe(false);
    emitEphemeralToast({ message: 'Unlabelled', onAction: reveal }); await flush();
    // Unlabelled actions read as the panel's Earlier row does, never as an implied undo.
    expect(pill.shadowRoot!.querySelector('.transient-action')!.textContent).toBe('Review');
    expect(pill.undoNotice()).toBe(false);
    expect(reveal).not.toHaveBeenCalled();
  });
});
