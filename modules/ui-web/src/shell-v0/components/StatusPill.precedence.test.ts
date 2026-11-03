// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * Round 7 repairs: a passing notice never takes the pill from a more urgent state (R1), the page dim
 * leaves the bar the pill sits in undimmed (R2), and the panel keeps a margin from the window edges
 * with its arrow on the pill (R4).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { StatusPill } from './StatusPill.js';
import type { StatusPanel } from './StatusPanel.js';
import { AdvisoryStore } from './advisory/AdvisoryStore.js';
import { emitEphemeralToast } from './advisory/ephemeralToast.js';
import { reportAdmissionWait } from '../state/admissionWaitNotice.js';
import { __resetAiStateForTest, type AiState } from '../state/aiStateStore.js';
import { proposeEffect, __resetPendingForTest } from '../substrates/pending-effects/index.js';
import { CORE_PROVENANCE } from '../primitives/provenance.js';

let pill: StatusPill;
let bar: HTMLDivElement;
let store: AdvisoryStore;
const trigger = () => pill.shadowRoot!.querySelector<HTMLButtonElement>('.trigger')!;
const panel = () => pill.shadowRoot!.querySelector<StatusPanel>('jf-status-panel')!;
const notice = () => pill.shadowRoot!.querySelector('[data-testid="status-notice"]');
const header = () => panel().shadowRoot!.querySelector('.header .detail')!.textContent!;
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
const rect = (left: number, top: number, width: number, height: number) => ({ left, top, width, height, right: left + width,
  bottom: top + height, x: left, y: top, toJSON: () => ({}) }) as DOMRect;
const viewport = (width: number) => (window as unknown as { happyDOM: { setViewport(v: { width: number; height: number }): void } })
  .happyDOM.setViewport({ width, height: 800 });

beforeEach(async () => {
  vi.useFakeTimers();
  viewport(1280);
  vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 503 })));
  __resetAiStateForTest(); __resetPendingForTest();
  store = new AdvisoryStore(); store.start();
  bar = document.createElement('div'); document.body.append(bar);
  pill = new StatusPill(); pill.store = store; bar.append(pill);
  pill.status.ai = ai(); await flush();
});
afterEach(() => {
  bar.remove(); store.stop(); __resetAiStateForTest(); __resetPendingForTest();
  vi.unstubAllGlobals(); vi.useRealTimers(); viewport(1024);
});

describe('R1 notice precedence', () => {
  it('needs you keeps the pill while a notice shows beside it; hovering the open pill cannot hold the notice past its window', async () => {
    proposeEffect({ kind: 'navigate', to: '#one' }, CORE_PROVENANCE, 'agent'); await flush();
    emitEphemeralToast({ message: 'Search is up to date', classId: 'core.verdict.settled' }); await flush();
    expect(trigger().getAttribute('aria-label')).toBe('1 needs you');
    expect(trigger().querySelector('.label')!.textContent).toBe('Needs you');
    expect(notice()?.textContent).toBe('Search is up to date');
    // Opened by mouse; the pointer stays on the pill.
    trigger().click(); await flush();
    trigger().dispatchEvent(new MouseEvent('mouseenter')); await flush();
    expect(header()).toContain('1 need you');
    await vi.advanceTimersByTimeAsync(7_000); await flush();
    expect(notice()).toBeNull();
    await vi.advanceTimersByTimeAsync(60_000); await flush();
    expect(notice()).toBeNull();
    expect(trigger().getAttribute('aria-label')).toBe('1 needs you');
    // Still recoverable in Earlier.
    expect(panel().model!.sections.earlier.map(row => row.title)).toContain('Search is up to date');
  });

  it('an own-action undo keeps its action beside an urgent pill and the pill keeps its state', async () => {
    proposeEffect({ kind: 'navigate', to: '#one' }, CORE_PROVENANCE, 'agent'); await flush();
    const undo = vi.fn();
    emitEphemeralToast({ message: 'Skip rule added', actionLabel: 'Undo', onAction: undo }); await flush();
    expect(trigger().getAttribute('aria-label')).toBe('1 needs you');
    expect(notice()!.textContent).toBe('Skip rule added');
    pill.shadowRoot!.querySelector<HTMLButtonElement>('.transient-action')!.click(); await flush();
    expect(undo).toHaveBeenCalledTimes(1);
  });

  it('an unreachable engine keeps the pill through repeated update-wait notices, and the panel header agrees', async () => {
    const lost = Date.now();
    pill.status.ai = ai({ phase: 'stale', connection: { reachable: false, lastSuccessMs: lost, lastContactMs: lost, consecutiveFailures: 3 } } as Partial<AiState>);
    await flush();
    await vi.advanceTimersByTimeAsync(11_000); await flush();
    pill.toggle(); await flush();
    for (let minute = 0; minute < 7; minute++) {
      reportAdmissionWait(true, 30_000); await flush();
      expect(trigger().getAttribute('aria-label')).toBe("Can't reach the engine");
      expect(notice()).toBeNull();
      expect(header()).toContain("Can't reach the engine");
      await vi.advanceTimersByTimeAsync(60_000); await flush();
    }
    expect(trigger().textContent).not.toContain('preparing an update');
  });
});

describe('R2 R4 panel placement', () => {
  it('R2 the page dim starts below the bar the pill sits in', async () => {
    vi.spyOn(bar, 'getBoundingClientRect').mockReturnValue(rect(0, 0, 1280, 56));
    vi.spyOn(trigger(), 'getBoundingClientRect').mockReturnValue(rect(1000, 12, 120, 32));
    pill.open = true; await flush(); await flush();
    expect(pill.style.getPropertyValue('--frame-status-dim-top')).toBe('56px');
    const overlay = pill.shadowRoot!.querySelector<HTMLElement>('.overlay')!;
    expect(getComputedStyle(overlay).top).toBe('56px');
  });

  it('R4 at 600 px the panel keeps an 8 px margin from both edges and the arrow stays on its top edge, pointing at the pill', async () => {
    viewport(600);
    pill.style.setProperty('--jf-space-2', '8px'); pill.style.setProperty('--frame-panel-radius', '16px');
    const triggerRect = vi.spyOn(trigger(), 'getBoundingClientRect').mockReturnValue(rect(540, 8, 50, 32));
    vi.spyOn(panel(), 'getBoundingClientRect').mockReturnValue(rect(8, 48, 584, 352));
    pill.open = true; await flush(); await flush();
    const right = parseFloat(pill.style.getPropertyValue('--frame-status-panel-right'));
    expect(right).toBeGreaterThanOrEqual(8);
    expect(600 - right - 584).toBeGreaterThanOrEqual(8);
    // Pill centre 565 px, panel right edge 592 px: the arrow is 27 px in from that edge.
    expect(pill.style.getPropertyValue('--frame-status-arrow-right')).toBe('calc(27px - var(--jf-space-2) / 2)');
    pill.close(); await flush();
    // A pill at the very edge: the arrow stops where the rounded corner begins instead of leaving the panel.
    triggerRect.mockReturnValue(rect(590, 8, 10, 32));
    pill.open = true; await flush(); await flush();
    expect(pill.style.getPropertyValue('--frame-status-arrow-right')).toBe('calc(20px - var(--jf-space-2) / 2)');
  });
});
