// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * FC10a — the status pill's spoken text (its hidden live regions) agrees with what it shows in every
 * state, and adds no words it never shows. While an AI install the person started has failed, the
 * pill shows Needs you: through an Engine outage its search status is spoken as that, never
 * "Catching up…" or "Reconnecting…", until the pill itself says the engine cannot be reached; and in
 * no state is "Service degraded", "Not Installed" or "Install Failed" spoken (the search verdict's
 * and the AI engine's own headlines, which the pill never shows). The controller is driven with the
 * observed state, as in StatusPill.transitions.test.ts.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { StatusPill } from './StatusPill.js';
import { __resetAiStateForTest, type AiState } from '../state/aiStateStore.js';
import { __resetTasksForTest } from '../substrates/tasks/index.js';
vi.mock('../../utils/windowFocus.js', () => ({ isWindowFocused: vi.fn(async () => false) }));
vi.mock('../../utils/notify.js', () => ({ sendDesktopNotification: vi.fn(async () => {}) }));

let pill: StatusPill;

function ai(overrides: Partial<AiState> = {}): AiState {
  const now = Date.now();
  return { phase: 'connected', connection: { reachable: true, lastSuccessMs: now, lastContactMs: now, consecutiveFailures: 0 },
    verdict: { kind: 'operational', severity: 'ok', reasons: [] }, stability: { kind: 'settled' },
    aiEngine: { kind: 'offline', stability: { kind: 'settled' }, installFailure: null },
    activity: { state: 'idle', shapeId: null, startedAtMs: null, canCancel: false, cancel: null },
    status: { worker: { core: { indexState: 'IDLE', pendingJobs: 0 }, enrichment: {} } },
    indexingStatusLive: true, snapshotLive: true, installObservationLive: true, episodeMaxPendingJobs: 0, enrichSettleSamples: [], ...overrides,
  } as AiState;
}

/** An AI install the person started that failed: the AI verdict, and the search verdict it leaves. */
const FAILED_INSTALL: Partial<AiState> = {
  aiEngine: { kind: 'install_failed', stability: { kind: 'settled' }, installFailure: 'Could not write to the models folder',
    installFailureCode: 'INSTALL_IO_ERROR' } as AiState['aiEngine'],
  verdict: { kind: 'degraded', severity: 'warn', reasons: ['encoders.health.embedding_not_ready'] } as AiState['verdict'],
};

/** What the pill shows (its accessible name) and what each of its live regions says. */
async function observe(state: Partial<AiState>): Promise<{ shown: string; spoken: string[]; search: string | null }> {
  pill.status.ai = ai(state); pill.status.refresh();
  for (let i = 0; i < 4; i++) await Promise.resolve();
  await pill.updateComplete;
  const root = pill.shadowRoot!;
  return {
    shown: root.querySelector('.trigger')?.getAttribute('aria-label') ?? '',
    spoken: [...root.querySelectorAll('jf-system-notice, [aria-live]')].map((r) => (r.textContent ?? '').replace(/\s+/g, ' ').trim()).filter(Boolean),
    search: root.querySelector('[data-testid="verdict-announcer"]')?.textContent?.trim() ?? null,
  };
}

/** Words the pill never shows. */
const NEVER_SHOWN = /Catching up|Reconnecting|Service degraded|Search available|\bNot Installed\b|\bInstall Failed\b/;

beforeEach(async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 503 })));
  __resetAiStateForTest(); __resetTasksForTest();
  pill = new StatusPill(); document.body.append(pill); await pill.updateComplete;
});
afterEach(() => { pill.remove(); __resetAiStateForTest(); __resetTasksForTest(); vi.unstubAllGlobals(); vi.useRealTimers(); });

describe('FC10a the pill\'s spoken text agrees with what it shows', () => {
  it('Needs you during an Engine outage: spoken as Needs you, then as the engine that cannot be reached', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    const before = await observe(FAILED_INSTALL);
    expect(before.shown).toMatch(/^1 needs you$/);
    expect(before.search).toBe('1 needs you');
    for (const text of before.spoken) expect(text, `spoken beside "${before.shown}"`).not.toMatch(NEVER_SHOWN);

    // The Engine stops answering: the store keeps its last observation and says it is checking.
    const lost = (verdict: AiState['verdict']): Partial<AiState> => ({ ...FAILED_INSTALL, verdict, phase: 'stale', snapshotLive: false,
      indexingStatusLive: false, installObservationLive: false,
      connection: { reachable: false, lastSuccessMs: Date.now(), lastContactMs: Date.now(), consecutiveFailures: 2 } });
    const checking = await observe(lost({ kind: 'checking', severity: 'info', reasons: [] } as unknown as AiState['verdict']));
    expect(checking.shown).toMatch(/^1 needs you · /);
    expect(checking.search, `the search status spoken beside "${checking.shown}"`).toBe('1 needs you');
    for (const text of checking.spoken) expect(text, `spoken beside "${checking.shown}"`).not.toMatch(NEVER_SHOWN);

    vi.setSystemTime(Date.now() + 11_000);
    const gone = await observe(lost({ kind: 'unreachable', severity: 'error', reasons: [] } as unknown as AiState['verdict']));
    expect(gone.shown).toBe("Can't reach the engine");
    expect(gone.search).toBe("Can't reach the engine");
    for (const text of gone.spoken) expect(text, `spoken beside "${gone.shown}"`).not.toMatch(NEVER_SHOWN);
  });

  it.each([
    ['AI not installed, nobody asked', { aiEngine: { kind: 'not_installed', stability: { kind: 'settled' }, installFailure: null, notRequested: true } as AiState['aiEngine'] }],
    ['an AI install failed', FAILED_INSTALL],
    ['search degraded', { verdict: { kind: 'degraded', severity: 'warn', reasons: ['index.dense_unavailable'] } as AiState['verdict'] }],
    ['AI online', { aiEngine: { kind: 'online', stability: { kind: 'settled' }, installFailure: null } as AiState['aiEngine'] }],
  ] as const)('%s: nothing spoken that the pill never shows', async (_case, state) => {
    const { shown, spoken, search } = await observe(state as Partial<AiState>);
    for (const text of spoken) expect(text, `spoken beside "${shown}"`).not.toMatch(NEVER_SHOWN);
    expect(pill.shadowRoot!.querySelector('[data-testid="ai-engine-announcer"]'), 'no AI-engine headline region').toBeNull();
    if (search !== null) expect(shown.toLowerCase(), 'the search status spoken is the pill\'s').toContain(search.toLowerCase());
  });

  it('each change of status is heard once, through calm, indexing, needs you and an outage', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    const root = pill.shadowRoot!;
    const regions = (): Element[] => [...root.querySelectorAll('jf-system-notice, [aria-live]')];
    const key = (region: Element): string => (region as HTMLElement).dataset.testid ?? region.getAttribute('aria-live') ?? '';
    // Every live region of the pill, as a screen reader is told of it: each new non-empty text it takes.
    const last = new Map<string, string>();
    let heard: string[] = [];
    const listen = (): void => {
      for (const region of regions()) {
        const text = (region.textContent ?? '').replace(/\s+/g, ' ').trim();
        if (last.get(key(region)) === text) continue;
        last.set(key(region), text);
        if (text) heard.push(`${key(region)}: ${text}`);
      }
    };
    const observer = new MutationObserver(listen);
    observer.observe(root, { subtree: true, childList: true, characterData: true });
    const step = async (state: Partial<AiState>): Promise<{ shown: string; heard: string[] }> => {
      const { shown } = await observe(state);
      listen();
      const said = heard; heard = [];
      return { shown, heard: said };
    };
    const lost = (verdict: AiState['verdict']): Partial<AiState> => ({ ...FAILED_INSTALL, verdict, phase: 'stale', snapshotLive: false,
      indexingStatusLive: false, installObservationLive: false,
      connection: { reachable: false, lastSuccessMs: Date.now(), lastContactMs: Date.now(), consecutiveFailures: 2 } });
    const INDEXING: Partial<AiState> = { status: { worker: { core: { indexState: 'INDEXING', pendingJobs: 40 }, enrichment: {} } } as AiState['status'] };
    try {
      // What the regions say before the first observation is not heard as a change.
      listen(); heard = [];
      for (const [name, state] of [['the first observation, calm', {}], ['indexing', INDEXING], ['calm again', {}],
        ['needs you', FAILED_INSTALL]] as const) {
        const { shown, heard: said } = await step(state);
        expect(said, `${name}: one announcement beside "${shown}"`).toHaveLength(1);
      }
      const checking = await step(lost({ kind: 'checking', severity: 'info', reasons: [] } as unknown as AiState['verdict']));
      expect(checking.heard, `the outage begins: the pill still says "${checking.shown}", nothing new is heard`).toEqual([]);
      vi.setSystemTime(Date.now() + 11_000);
      const gone = await step(lost({ kind: 'unreachable', severity: 'error', reasons: [] } as unknown as AiState['verdict']));
      expect(gone.heard, `unreachable: one announcement beside "${gone.shown}"`).toEqual(["verdict-announcer: Can't reach the engine"]);
    } finally { observer.disconnect(); }
  });
});
