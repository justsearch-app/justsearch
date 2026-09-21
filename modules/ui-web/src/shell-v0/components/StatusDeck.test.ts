// @vitest-environment happy-dom

import { describe, expect, it, beforeEach, vi } from 'vitest';
import './StatusDeck.js';
import type { StatusDeck } from './StatusDeck.js';
import { __resetStatusPollForTest } from '../utils/statusPoll.js';
import { __resetInferencePollForTest } from '../utils/inferencePoll.js';
import { __resetAiStateForTest, isSnapshotLive, type AiState } from '../state/aiStateStore.js';
import { known, UNKNOWN } from '../state/known.js';
import { updateShellContext, __resetShellContextForTest } from '../state/shellContextState.js';
import { EPHEMERAL_TOAST_EVENT } from './advisory/ephemeralToast.js';
import { formatCount } from '../display/format.js';

function makeAiState(overrides: Partial<AiState> = {}): AiState {
  const built: AiState = {
    phase: 'connected',
    readiness: UNKNOWN,
    capabilities: { chat: false, rag: false, extract: false, embedding: false },
    connection: { reachable: true, lastSuccessMs: Date.now(), lastContactMs: Date.now(), consecutiveFailures: 0 },
    runtime: { mode: 'offline', modelId: null, modelLabel: null, contextWindow: null, contextWindowDerived: null, gpu: null, installed: known(false), installing: known(false), loadStartedAtMs: null },
    activity: { state: 'idle', shapeId: null, startedAtMs: null, canCancel: false, cancel: null },
    index: { documentCount: known(0), searchableDocumentCount: known(0), pendingJobs: known(0), embeddingPending: known(0), embeddingBlocked: known(false), embeddingQueueSize: known(0), vduQueueSize: known(0) },
    realized: {
      reranker: { loaded: false, accelerator: null, failureReason: null },
      embed: { loaded: false, accelerator: null, failureReason: null },
      splade: { loaded: false, accelerator: null, failureReason: null },
    },
    statusLabel: 'offline',
    statusTier: 'offline',
    statusTone: 'neutral',
    stability: { kind: 'settled' },
    verdict: { kind: 'operational', severity: 'ok', reasons: [] },
    snapshotLive: true,
    status: null,
    inference: null,
    lastSettledIndex: null,
    episodeMaxPendingJobs: 0,
    enrichSettleSamples: [],
    installStatus: null,
    runtimeStatus: null,
    packStatus: null,
    aiEngine: { kind: 'offline', stability: { kind: 'settled' }, installFailure: null },
    ...overrides,
  };
  // Tempdoc 807 — keep the fixture COHERENT: a test that overrides `connection` to an unreachable
  // origin must not silently keep `snapshotLive: true` (production derives one from the other in
  // `buildSnapshot`). An explicit override still wins, so a test can pin the pair deliberately.
  return { ...built, snapshotLive: overrides.snapshotLive ?? isSnapshotLive(built.connection) };
}

function make(): StatusDeck {
  const el = document.createElement('jf-status-deck') as StatusDeck;
  document.body.appendChild(el);
  return el;
}

describe('StatusDeck (slice 461)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    __resetStatusPollForTest();
    __resetInferencePollForTest();
    __resetAiStateForTest();
    vi.restoreAllMocks();
    // Mock fetch to avoid network during tests
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: false }) as unknown as typeof fetch,
    );
  });

  it('renders default groups when status null (everything muted)', async () => {
    const el = make();
    await el.updateComplete;
    // No status data — connection dot should be muted
    const dot = el.shadowRoot?.querySelector('.dot');
    expect(dot?.classList.contains('muted')).toBe(true);
  });

  it('connection dot turns healthy when api + index READY', async () => {
    const el = make();
    // B2: status now comes from the ONE observed-state authority (aiState.status).
    el.aiState = makeAiState({
      status: {
        readiness: { engineComponents: { api: { state: 'READY' }, index: { state: 'READY' } } },
      } as unknown as AiState['status'],
    });
    await el.updateComplete;
    const dot = el.shadowRoot?.querySelector('.dot');
    expect(dot?.classList.contains('healthy')).toBe(true);
  });

  // Tempdoc 807 A.3 (round-13 R13-F2) — the CONN dot reflects REACHABILITY, not the last good poll.
  // `readiness.engineComponents.api/index.state` are fields off the retained snapshot: they said READY/READY forever
  // after both java processes died, so the dot stayed green beside a "Backend disconnected." banner.
  it('807: the connection dot goes red when the snapshot is no longer live — READY/READY notwithstanding', async () => {
    const el = make();
    const READY_SNAPSHOT = {
      readiness: { engineComponents: { api: { state: 'READY' }, index: { state: 'READY' } } },
    } as unknown as AiState['status'];
    // The exact round-13 state: a fully-healthy retained snapshot + contact aged out mid-session
    // (verdict `transitioning`/`channel-stale`, NOT `unreachable` — the poll had succeeded once).
    // Liveness comes from CONTACT (round-13 review), so the fixture states the contact fact.
    el.aiState = makeAiState({
      status: READY_SNAPSHOT,
      connection: { reachable: false, lastSuccessMs: Date.now() - 60_000, lastContactMs: Date.now() - 60_000, consecutiveFailures: 1 },
      verdict: { kind: 'transitioning', severity: 'warn', reasons: ['channel-stale'] },
    });
    await el.updateComplete;
    const dot = el.shadowRoot?.querySelector('.dot');
    expect(dot?.classList.contains('healthy')).toBe(false);
    expect(dot?.classList.contains('error')).toBe(true);
    expect(el.shadowRoot?.querySelector('.dot')?.parentElement?.getAttribute('aria-label')).toContain(
      'disconnected',
    );

    // Anti-regression: the SAME snapshot with a live verdict is still green (the fix must not
    // permanently red-line a healthy UI).
    el.aiState = makeAiState({ status: READY_SNAPSHOT });
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.dot')?.classList.contains('healthy')).toBe(true);
  });

  it('807: the never-contacted `unreachable` verdict reddens the dot too', async () => {
    const el = make();
    el.aiState = makeAiState({
      status: {
        readiness: { engineComponents: { api: { state: 'READY' }, index: { state: 'READY' } } },
      } as unknown as AiState['status'],
      connection: { reachable: false, lastSuccessMs: null, lastContactMs: null, consecutiveFailures: 1 },
      verdict: { kind: 'unreachable', severity: 'error', reasons: ['binding.unreachable'] },
    });
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.dot')?.classList.contains('error')).toBe(true);
  });

  it.each(['api', 'index'])('connection dot fails closed for invalid %s states', async (component) => {
    for (const state of ['ABSENT', 'RELOADING', 'FAILED', 'UNAVAILABLE', 'UNKNOWN', undefined]) {
      const el = make();
      el.aiState = makeAiState({ status: {
        readiness: { engineComponents: { api: { state: 'READY' }, index: { state: 'READY' },
          [component]: { state } } },
      } as unknown as AiState['status'] });
      await el.updateComplete;
      expect(el.shadowRoot?.querySelector('.dot')?.classList.contains('error')).toBe(true);
      el.remove();
    }
  });

  it.each([{}, { readiness: {} }, { readiness: { engineComponents: {} } }])(
    'connection dot fails closed for missing component data %j', async (status) => {
      const el = make();
      el.aiState = makeAiState({ status: status as unknown as AiState['status'] });
      await el.updateComplete;
      expect(el.shadowRoot?.querySelector('.dot')?.classList.contains('error')).toBe(true);
    },
  );

  it.each(['api', 'index'])('connection dot shows starting for %s', async (component) => {
    const el = make();
    el.aiState = makeAiState({ status: {
      readiness: { engineComponents: { api: { state: 'READY' }, index: { state: 'READY' },
        [component]: { state: 'STARTING' } } },
    } as unknown as AiState['status'] });
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.dot')?.classList.contains('warn')).toBe(true);
  });

  it('memory dot turns warn at >80% utilization', async () => {
    const el = make();
    el.aiState = makeAiState({
      status: {
        memoryUsedBytes: 8500,
        memoryMaxBytes: 10000,
      } as unknown as AiState['status'],
    });
    await el.updateComplete;
    const memDot = el.shadowRoot?.querySelectorAll('.dot')[1];
    expect(memDot?.classList.contains('warn')).toBe(true);
  });

  it('system pill tone follows the verdict-derived statusTone (tempdoc 649)', async () => {
    const badgeTone = (el: StatusDeck) =>
      el.shadowRoot?.querySelector('jf-status-badge')?.getAttribute('tone');
    const el = make();
    el.aiState = makeAiState({ statusTone: 'success', statusLabel: 'Online' });
    await el.updateComplete;
    expect(badgeTone(el)).toBe('success');
    // 649: the calm "Catching up…" (busy) state must render calm `info`, NOT amber.
    el.aiState = makeAiState({ statusTone: 'info', statusLabel: 'Catching up…' });
    await el.updateComplete;
    expect(badgeTone(el)).toBe('info');
    el.aiState = makeAiState({ statusTone: 'warning', statusLabel: 'Service degraded' });
    await el.updateComplete;
    expect(badgeTone(el)).toBe('warning');
    el.aiState = makeAiState({ statusTone: 'neutral', statusLabel: 'offline' });
    await el.updateComplete;
    expect(badgeTone(el)).toBe('neutral');
    el.aiState = makeAiState({ statusTone: 'error', statusLabel: 'Backend disconnected' });
    await el.updateComplete;
    expect(badgeTone(el)).toBe('error');
  });

  // Tempdoc 813 §3b — the chip's NUMBERS now come from the one indexing-progress projection over the
  // `/api/status` snapshot, so these fixtures carry the wire fields that projection reads
  // (`worker.core.indexState` + `pendingJobs`) alongside the store's `index` slice. Same scenario,
  // same assertions — only the seed now matches how the number actually reaches the chip.
  it('queue group renders when pendingJobs > 0', async () => {
    const el = make();
    el.aiState = makeAiState({
      index: { documentCount: known(0), searchableDocumentCount: known(0), pendingJobs: known(5), embeddingPending: known(0), embeddingBlocked: known(false), embeddingQueueSize: known(0), vduQueueSize: known(0) },
      status: {
        worker: { core: { indexState: 'INDEXING', pendingJobs: 5 } },
      } as unknown as AiState['status'],
    });
    await el.updateComplete;
    const groups = el.shadowRoot?.querySelectorAll('.group');
    const queueText = Array.from(groups ?? [])
      .map((g) => g.textContent ?? '')
      .find((t) => t.includes('queue:'));
    expect(queueText).toBeTruthy();
  });

  const queueText = (el: StatusDeck) =>
    Array.from(el.shadowRoot?.querySelectorAll('.group') ?? [])
      .map((g) => g.textContent ?? '')
      .find((t) => t.includes('queue:'));

  it('630: queue reads "paused" on the main-surface bar when energy saver is active', async () => {
    const el = make();
    el.aiState = makeAiState({
      index: { documentCount: known(0), searchableDocumentCount: known(0), pendingJobs: known(5), embeddingPending: known(0), embeddingBlocked: known(false), embeddingQueueSize: known(0), vduQueueSize: known(0) },
      status: {
        power: { energyReduced: true },
        worker: { core: { indexState: 'INDEXING', pendingJobs: 5 } },
      } as unknown as AiState['status'],
    });
    await el.updateComplete;
    expect(queueText(el)).toContain('paused');
  });

  it('630: queue stays the plain active count when not energy-reduced', async () => {
    const el = make();
    el.aiState = makeAiState({
      index: { documentCount: known(0), searchableDocumentCount: known(0), pendingJobs: known(5), embeddingPending: known(0), embeddingBlocked: known(false), embeddingQueueSize: known(0), vduQueueSize: known(0) },
      status: {
        power: { energyReduced: false },
        worker: { core: { indexState: 'INDEXING', pendingJobs: 5 } },
      } as unknown as AiState['status'],
    });
    await el.updateComplete;
    expect(queueText(el)).toBeTruthy();
    expect(queueText(el)).not.toContain('paused');
  });

  // Tempdoc 813 §4 — during the enrichment window the chip's second number is the coverage FRACTION,
  // not a raw backlog count: the files are already indexed; the semantic layers are catching up.
  it('813: jobs drained but enrichment outstanding ⇒ the chip reads the enriching percent', async () => {
    const el = make();
    el.aiState = makeAiState({
      index: { documentCount: known(100), searchableDocumentCount: known(100), pendingJobs: known(0), embeddingPending: known(40), embeddingBlocked: known(false), embeddingQueueSize: known(0), vduQueueSize: known(0) },
      status: {
        worker: {
          core: { indexState: 'IDLE', pendingJobs: 0 },
          enrichment: {
            backfillMode: 'combined',
            embeddingEnabled: true,
            embeddingDocCount: 100,
            embeddingPendingCount: 40,
          },
        },
      } as unknown as AiState['status'],
    });
    await el.updateComplete;
    expect(queueText(el)).toContain('enriching: 60%');
    expect(queueText(el)).not.toContain('embed:');
  });

  // 813 review objection 2 — the count fallback under the "embed" label is the EMBEDDING stage's
  // own pending number, never the multi-stage rawPending sum (which counts a document once per
  // pending stage plus its chunks — ~10x embedding on a chunked corpus, under an embedding label).
  it('813: the embed-count fallback shows the embedding number, not the multi-stage sum', async () => {
    const el = make();
    el.aiState = makeAiState({
      index: { documentCount: known(100), searchableDocumentCount: known(100), pendingJobs: known(0), embeddingPending: known(40), embeddingBlocked: known(false), embeddingQueueSize: known(0), vduQueueSize: known(0) },
      status: {
        worker: {
          core: { indexState: 'IDLE', pendingJobs: 0 },
          enrichment: {
            backfillMode: 'combined',
            embeddingEnabled: true,
            // No doc counts anywhere ⇒ no faithful denominator ⇒ percent arm suppressed, the
            // count fallback renders. rawPending here is 40 + 400 = 440; the label says "embed".
            embeddingPendingCount: 40,
            chunk: { chunkEmbeddingPendingCount: 400 },
          },
        },
      } as unknown as AiState['status'],
    });
    await el.updateComplete;
    expect(queueText(el)).toContain('embed: 40');
    expect(queueText(el)).not.toContain('440');
  });

  it('813: a blocked embedding stage claims no enrichment progress', async () => {
    const el = make();
    el.aiState = makeAiState({
      index: { documentCount: known(100), searchableDocumentCount: known(100), pendingJobs: known(0), embeddingPending: known(40), embeddingBlocked: known(true), embeddingQueueSize: known(0), vduQueueSize: known(0) },
      status: {
        worker: {
          core: { indexState: 'IDLE', pendingJobs: 0 },
          enrichment: {
            backfillMode: 'combined',
            embeddingEnabled: true,
            embeddingDocCount: 100,
            embeddingPendingCount: 40,
          },
        },
      } as unknown as AiState['status'],
    });
    await el.updateComplete;
    // Nothing to report and nothing to work off ⇒ the chip stays hidden rather than showing a
    // fraction that will never advance.
    expect(queueText(el)).toBeUndefined();
  });

  it('§17.2 — files/size/memory values come from the projectFact authority (one formatter)', async () => {
    const el = make();
    el.aiState = makeAiState({
      index: { documentCount: known(605), searchableDocumentCount: known(605), pendingJobs: known(0), embeddingPending: known(0), embeddingBlocked: known(false), embeddingQueueSize: known(0), vduQueueSize: known(0) },
      status: {
        worker: { core: { indexSizeBytes: 53_477_376 } },
        memoryUsedBytes: 238_026_752,
      } as unknown as AiState['status'],
    });
    await el.updateComplete;
    const vals = Array.from(el.shadowRoot?.querySelectorAll('.val') ?? []).map((v) => v.textContent?.trim());
    expect(vals).toContain('605'); // files
    expect(vals).toContain('51.0 MB'); // size — the shared formatter
    expect(vals).toContain('227.0 MB'); // memory
  });

  it('endpoint label shows api-base attribute', async () => {
    const el = make();
    el.apiBase = 'http://127.0.0.1:33221';
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.endpoint')?.textContent).toBe('http://127.0.0.1:33221');
  });

  it('595 §15.1 (E1): a visually-hidden aria-live announcer mirrors the verdict, politely', async () => {
    const announcer = (el: StatusDeck) =>
      el.shadowRoot?.querySelector('[data-testid="verdict-announcer"]');
    const el = make();
    el.aiState = makeAiState({
      verdict: { kind: 'transitioning', severity: 'busy', reasons: ['rebuilding'] },
    });
    await el.updateComplete;
    const a = announcer(el);
    expect(a).not.toBeNull();
    expect(a?.classList.contains('visually-hidden')).toBe(true);
    expect(a?.textContent?.trim()).toBe('Rebuilding…');
    // jf-system-notice reflects live → role=status + aria-live=polite for non-error.
    expect(a?.getAttribute('live')).toBe('status');
  });

  it('595 §15.1 (E1): an error verdict announces assertively', async () => {
    const el = make();
    el.aiState = makeAiState({
      verdict: { kind: 'unreachable', severity: 'error', reasons: [] },
    });
    await el.updateComplete;
    const a = el.shadowRoot?.querySelector('[data-testid="verdict-announcer"]');
    expect(a?.textContent?.trim()).toBe('Backend disconnected');
    expect(a?.getAttribute('live')).toBe('alert');
  });

  // 595 §15.3 (N1) — completion toast on the transitioning → operational edge.
  it('N1: a transitioning → operational edge fires one completion toast', async () => {
    const toasts: string[] = [];
    const onToast = (e: Event) => toasts.push((e as CustomEvent).detail.message);
    document.addEventListener(EPHEMERAL_TOAST_EVENT, onToast);
    try {
      const el = make();
      el.aiState = makeAiState({ verdict: { kind: 'transitioning', severity: 'busy', reasons: ['rebuilding'] } });
      await el.updateComplete;
      expect(toasts).toEqual([]); // entering a transition does not toast
      el.aiState = makeAiState({ verdict: { kind: 'operational', severity: 'ok', reasons: [] } });
      await el.updateComplete;
      expect(toasts).toEqual(['Index ready — all systems operational']);
    } finally {
      document.removeEventListener(EPHEMERAL_TOAST_EVENT, onToast);
    }
  });

  it('N1: first-load (connecting → operational) and reconnect (unreachable → operational) do NOT toast', async () => {
    const toasts: string[] = [];
    const onToast = (e: Event) => toasts.push((e as CustomEvent).detail.message);
    document.addEventListener(EPHEMERAL_TOAST_EVENT, onToast);
    try {
      const el = make();
      el.aiState = makeAiState({ verdict: { kind: 'connecting', severity: 'info', reasons: [] } });
      await el.updateComplete;
      el.aiState = makeAiState({ verdict: { kind: 'operational', severity: 'ok', reasons: [] } });
      await el.updateComplete;
      el.aiState = makeAiState({ verdict: { kind: 'unreachable', severity: 'error', reasons: [] } });
      await el.updateComplete;
      el.aiState = makeAiState({ verdict: { kind: 'operational', severity: 'ok', reasons: [] } });
      await el.updateComplete;
      expect(toasts).toEqual([]);
    } finally {
      document.removeEventListener(EPHEMERAL_TOAST_EVENT, onToast);
    }
  });

  it('N1: a transitioning → checking → operational recovery still fires exactly one toast', async () => {
    // A settled poll whose readiness is momentarily `unknown` resolves to `checking` before
    // `operational`, so the toast must survive the intermediate kind (not require a direct edge).
    const toasts: string[] = [];
    const onToast = (e: Event) => toasts.push((e as CustomEvent).detail.message);
    document.addEventListener(EPHEMERAL_TOAST_EVENT, onToast);
    try {
      const el = make();
      el.aiState = makeAiState({ verdict: { kind: 'transitioning', severity: 'busy', reasons: ['rebuilding'] } });
      await el.updateComplete;
      el.aiState = makeAiState({ verdict: { kind: 'checking', severity: 'info', reasons: [] } });
      await el.updateComplete;
      expect(toasts).toEqual([]); // not yet operational
      el.aiState = makeAiState({ verdict: { kind: 'operational', severity: 'ok', reasons: [] } });
      await el.updateComplete;
      expect(toasts).toEqual(['Index ready — all systems operational']);
      // A later degraded → operational with no preceding transition must stay silent.
      el.aiState = makeAiState({ verdict: { kind: 'degraded', severity: 'warn', reasons: ['x'] } });
      await el.updateComplete;
      el.aiState = makeAiState({ verdict: { kind: 'operational', severity: 'ok', reasons: [] } });
      await el.updateComplete;
      expect(toasts).toEqual(['Index ready — all systems operational']); // still just one
    } finally {
      document.removeEventListener(EPHEMERAL_TOAST_EVENT, onToast);
    }
  });

  // 595 §15.3 (N3) — the system pill is an operable control that opens Health.
  it('N3: the inference-mode pill is a jf-control that navigates to Health on activate', async () => {
    const el = make();
    el.aiState = makeAiState({ statusTier: 'online', statusLabel: 'Online' });
    await el.updateComplete;
    const control = el.shadowRoot?.querySelector('jf-control.status-pill') as
      | (HTMLElement & { onActivate: (() => void) | null })
      | null;
    expect(control).not.toBeNull();
    expect(control?.getAttribute('label')).toContain('Open Health');
    let navTarget: string | null = null;
    el.addEventListener('navigate-with-context', (e) => {
      navTarget = (e as CustomEvent).detail.target;
    });
    control?.onActivate?.();
    expect(navTarget).toBe('core.health-surface');
  });

  // 595 §15.3 (E2) — Files/Size show the last-settled value (dimmed) while provisional.
  it('E2: provisional with a retained last-settled value shows it dimmed, not "…"', async () => {
    const el = make();
    el.aiState = makeAiState({
      stability: { kind: 'provisional', cause: 'worker-restart' },
      lastSettledIndex: { documentCount: 1234, searchableDocumentCount: 1234, indexSizeBytes: 4096 },
    });
    await el.updateComplete;
    const vals = Array.from(el.shadowRoot?.querySelectorAll('.val') ?? []);
    const texts = vals.map((v) => v.textContent?.trim());
    // Files (.val[0]) + Size (.val[1]) show the last-known value, not "…". (Memory is a
    // separate Head-process metric and keeps its own provisional treatment.)
    expect(texts[0]).toBe(formatCount(1234));
    expect(texts[0]).not.toBe('…');
    expect(texts[1]).not.toBe('…');
    expect(vals.some((v) => v.classList.contains('stale'))).toBe(true);
  });

  it('E2: provisional with NO retained value falls back to "…"', async () => {
    const el = make();
    el.aiState = makeAiState({
      stability: { kind: 'provisional', cause: 'worker-restart' },
      lastSettledIndex: null,
    });
    await el.updateComplete;
    const texts = Array.from(el.shadowRoot?.querySelectorAll('.val') ?? []).map((v) => v.textContent?.trim());
    expect(texts).toContain('…');
  });

  it('E2: a retained value with a null size shows last-known Files but "…" for Size (honesty)', async () => {
    const el = make();
    el.aiState = makeAiState({
      stability: { kind: 'provisional', cause: 'worker-restart' },
      lastSettledIndex: { documentCount: 1234, searchableDocumentCount: 1234, indexSizeBytes: null },
    });
    await el.updateComplete;
    const texts = Array.from(el.shadowRoot?.querySelectorAll('.val') ?? []).map((v) => v.textContent?.trim());
    expect(texts[0]).toBe(formatCount(1234)); // Files last-known
    expect(texts[1]).toBe('…'); // Size: no observed size → not a fake "0 B"
  });

  describe('Tempdoc 663 Design pass 3 — AI-engine toast tracker + click-routing', () => {
    function captureToasts(): { specs: Array<{ classId?: string; message: string }>; stop: () => void } {
      const specs: Array<{ classId?: string; message: string }> = [];
      const listener = (e: Event) => specs.push((e as CustomEvent).detail);
      document.addEventListener(EPHEMERAL_TOAST_EVENT, listener);
      return { specs, stop: () => document.removeEventListener(EPHEMERAL_TOAST_EVENT, listener) };
    }

    it('installing → online fires exactly one "core.ai-engine.settled" toast', async () => {
      const el = make();
      const { specs, stop } = captureToasts();
      el.aiState = makeAiState({ aiEngine: { kind: 'installing', stability: { kind: 'provisional', cause: 'installing' }, installFailure: null } });
      await el.updateComplete;
      el.aiState = makeAiState({ aiEngine: { kind: 'online', stability: { kind: 'settled' }, installFailure: null } });
      await el.updateComplete;
      // A second, unrelated re-render at the SAME settled kind must not re-fire (one-shot, not per-render).
      el.aiState = makeAiState({ aiEngine: { kind: 'online', stability: { kind: 'settled' }, installFailure: null } });
      await el.updateComplete;
      stop();
      const ai = specs.filter((s) => s.classId === 'core.ai-engine.settled');
      expect(ai).toHaveLength(1);
    });

    it('installing → install_failed fires exactly one "core.ai-engine.failed" toast', async () => {
      const el = make();
      const { specs, stop } = captureToasts();
      el.aiState = makeAiState({ aiEngine: { kind: 'installing', stability: { kind: 'provisional', cause: 'installing' }, installFailure: null } });
      await el.updateComplete;
      el.aiState = makeAiState({ aiEngine: { kind: 'install_failed', stability: { kind: 'settled' }, installFailure: 'disk full' } });
      await el.updateComplete;
      stop();
      const ai = specs.filter((s) => s.classId === 'core.ai-engine.failed');
      expect(ai).toHaveLength(1);
    });

    it('reaching "online" WITHOUT a preceding "installing" (e.g. first load already online) does not toast', async () => {
      const el = make();
      const { specs, stop } = captureToasts();
      el.aiState = makeAiState({ aiEngine: { kind: 'online', stability: { kind: 'settled' }, installFailure: null } });
      await el.updateComplete;
      stop();
      expect(specs.filter((s) => s.classId === 'core.ai-engine.settled')).toHaveLength(0);
    });

    it('the inference-mode pill routes to the AI Brain surface when not_installed/install_failed', async () => {
      const el = make();
      el.aiState = makeAiState({
        aiEngine: { kind: 'not_installed', stability: { kind: 'settled' }, installFailure: null },
      });
      await el.updateComplete;
      const control = el.shadowRoot?.querySelector('[label*="AI Brain"]');
      expect(control).not.toBeNull();
    });

    it('the inference-mode pill still routes to Health for ordinary states (e.g. online)', async () => {
      const el = make();
      el.aiState = makeAiState({
        aiEngine: { kind: 'online', stability: { kind: 'settled' }, installFailure: null },
      });
      await el.updateComplete;
      const control = el.shadowRoot?.querySelector('[label*="Open Health"]');
      expect(control).not.toBeNull();
    });
  });

  // ── Tempdoc 814 §D5 — the chip yields the degradation fact to a surface banner ──
  //
  // Finding 12's measured duplication: "Reduced capability"/"Service degraded" rendered twice, ~660px
  // apart, in two components (this bar is Shell chrome; the banner is UnifiedChatView's) neither of
  // which could see the other. The rule is per-SURFACE, not global — these four cases pin both halves
  // of it, including the two ways over-suppressing would be a truthfulness regression.
  describe('814 §D5 — capability-degradation arbitration with the surface banner', () => {
    const CHAT_SURFACE = 'core.unified-chat-surface';
    const DEGRADED_WARN = {
      kind: 'degraded',
      severity: 'warn',
      reasons: ['worker.health.embedding_not_ready'],
    } as AiState['verdict'];

    function degradedDeck(): StatusDeck {
      const el = make();
      el.aiState = makeAiState({
        verdict: DEGRADED_WARN,
        // The production projections of that verdict (aiStateStore.computeStatusLabel/Tone).
        statusLabel: 'Service degraded',
        statusTone: 'warning',
        // …while the AI engine itself is up: the mode readout the chip falls back to.
        runtime: { mode: 'online', modelId: 'm', modelLabel: 'Qwen', contextWindow: null, contextWindowDerived: null, gpu: null, installed: known(true), installing: known(false), loadStartedAtMs: null },
        aiEngine: { kind: 'online', stability: { kind: 'settled' }, installFailure: null },
      });
      return el;
    }

    function pillText(el: StatusDeck): string {
      return (el.shadowRoot?.querySelector('.status-pill jf-status-badge')?.textContent ?? '').trim();
    }

    beforeEach(() => {
      __resetShellContextForTest();
    });

    it('yields on the chat surface when the verdict warrants that surface a banner', async () => {
      updateShellContext({ activeSurface: CHAT_SURFACE });
      const el = degradedDeck();
      await el.updateComplete;
      // The banner (UnifiedChatView.renderDegradationBanner) is the ONE persistent home of the fact
      // here — it carries the 600 wording and the remedy — so the chip reports the AI mode instead.
      expect(pillText(el)).not.toContain('degraded');
      expect(pillText(el)).toBe('Online — Qwen');
      expect(el.shadowRoot?.querySelector('.status-pill jf-status-badge')?.getAttribute('tone')).toBe(
        'success',
      );
    });

    it('does NOT yield on another surface — the chip is the global indicator there', async () => {
      updateShellContext({ activeSurface: 'core.library-surface' });
      const el = degradedDeck();
      await el.updateComplete;
      expect(pillText(el)).toBe('Service degraded');
    });

    it('does NOT yield on the chat surface when the verdict warrants NO banner (info tier)', async () => {
      // Post-finding-9 an info-severity verdict is bannerless (`warrantsSearchDegradationBanner`),
      // so yielding here would suppress the ONLY indicator of the fact — the load-bearing case.
      updateShellContext({ activeSurface: CHAT_SURFACE });
      const el = make();
      el.aiState = makeAiState({
        verdict: { kind: 'degraded', severity: 'info', reasons: ['lambdamart.not_configured'] },
        statusLabel: 'Reduced capability',
        statusTone: 'info',
        aiEngine: { kind: 'online', stability: { kind: 'settled' }, installFailure: null },
      });
      await el.updateComplete;
      expect(pillText(el)).toBe('Reduced capability');
    });

    it('does NOT yield an UNREACHABLE verdict — that is a connection fact, not this register row', async () => {
      // `unreachable` also warrants a banner, but yielding it would let the bar report the AI engine's
      // "Online" while the backend is gone: the regression this rule exists to prevent.
      updateShellContext({ activeSurface: CHAT_SURFACE });
      const el = make();
      el.aiState = makeAiState({
        verdict: { kind: 'unreachable', severity: 'error', reasons: ['binding.unreachable'] },
        statusLabel: 'Backend disconnected',
        statusTone: 'error',
        connection: { reachable: false, lastSuccessMs: null, lastContactMs: null, consecutiveFailures: 3 },
        aiEngine: { kind: 'online', stability: { kind: 'settled' }, installFailure: null },
      });
      await el.updateComplete;
      expect(pillText(el)).toBe('Backend disconnected');
    });
  });
});
