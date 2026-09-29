// @vitest-environment happy-dom
//
// Tempdoc 548 §1 / R1a — proves the genuine state-as-signal conversion of
// aiStateStore: inputs are signals, AiState is a `computed`, and
// `subscribeAiState` is a Signal.subtle.Watcher shim. These assertions pin
// the two properties that distinguish a real conversion from a tick-bolt:
//   1. the derived snapshot recomputes from inputs with NO manual notify
//      (read synchronously via getAiState);
//   2. subscribers still get a synchronous value on subscribe, and
//      subsequent changes fan out (batched) via the signal watcher.

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import {
  subscribeAiState,
  getAiState,
  setAiActivity,
  setInstallState,
  __resetAiStateForTest,
  __feedForTest,
  __feedContactForTest,
  __tickClockForTest,
  type AiState,
} from './aiStateStore.js';
import type { StatusSnapshot } from '../utils/statusPoll.js';
import type { InferenceSnapshot } from '../utils/inferencePoll.js';
import { known, UNKNOWN } from './known.js';
import { selectIndexingProgress } from './indexingProgress.js';
import { verdictHeadline, verdictTone } from './verdict.js';

const microtask = () => new Promise<void>((r) => queueMicrotask(() => r()));

describe('aiStateStore — R1a signal-core conversion', () => {
  beforeEach(() => __resetAiStateForTest());
  afterEach(() => __resetAiStateForTest());

  it('getAiState() reflects mutations synchronously (computed, no manual notify)', () => {
    expect(getAiState().activity.state).toBe('idle');
    setAiActivity({ state: 'streaming' });
    expect(getAiState().activity.state).toBe('streaming');
    setInstallState(true, true);
    // §2.B: install state is tri-state (Maybe<boolean>) — a fed value is Known.
    expect(getAiState().runtime.installed).toEqual(known(true));
    expect(getAiState().runtime.installing).toEqual(known(true));
  });

  it('delivers the current value synchronously on subscribe', () => {
    setAiActivity({ state: 'thinking' });
    let captured: AiState | null = null;
    const unsub = subscribeAiState((s) => {
      captured = s;
    });
    expect(captured).not.toBeNull();
    expect(captured!.activity.state).toBe('thinking');
    unsub();
  });

  it('fans out subsequent changes via the watcher (async, batched to latest)', async () => {
    const seen: string[] = [];
    const unsub = subscribeAiState((s) => seen.push(s.activity.state));
    expect(seen).toEqual(['idle']); // sync-on-subscribe only

    setAiActivity({ state: 'thinking' });
    setAiActivity({ state: 'streaming' });
    expect(seen).toEqual(['idle']); // not yet — fan-out is on a microtask

    await microtask();
    // Two synchronous mutations batch into ONE fan-out carrying the latest.
    expect(seen).toEqual(['idle', 'streaming']);
    unsub();
  });

  it('derived statusLabel recomputes from activity (computed dependency)', () => {
    setAiActivity({ state: 'extracting' });
    expect(getAiState().statusLabel).toBe('Extracting');
    setAiActivity({ state: 'idle' });
    // §2.B: with no successful poll the honest state is "Connecting…", not a
    // confident "offline" default (the no-data≠offline correction).
    expect(getAiState().statusLabel).toBe('Connecting…');
  });

  it('Design pass 3 — statusLabel gains install-awareness: known "not installed" now reads "Not Installed", not a generic/lowercase "offline" fallback', () => {
    // The search-verdict "Connecting…" check is checked FIRST (backend connectivity outranks AI-install
    // state), so a successful status poll must be established before the AI-specific branch is reached —
    // matching the precedence Design pass 3 confirmed should stay unchanged.
    __feedForTest({
      status: { worker: { core: { indexedDocuments: 5 } } } as unknown as StatusSnapshot,
    });
    __tickClockForTest();
    // §2.B: install state known-and-false (not merely absent) satisfies `computeAiEngineVerdict`'s
    // "have install data" check, so the settled state resolves to a DISTINCT label — previously this
    // fell through to the ad hoc, inconsistently-cased 'offline' string.
    setInstallState(false, false);
    expect(getAiState().statusLabel).toBe('Not Installed');
    expect(getAiState().statusTone).toBe('neutral');
  });

  it('§2.B: with no stream data, state is Unknown/connecting — never a concrete default', () => {
    // No poll has succeeded (beforeEach reset; no start). The store must NOT
    // seed concrete values — that is the "0 files / Not Installed" defect class.
    const s = getAiState();
    expect(s.phase).toBe('connecting');
    expect(s.runtime.installed.known).toBe(false); // Unknown, not known(false)
    expect(s.runtime.installing.known).toBe(false);
    expect(s.index.documentCount.known).toBe(false);
    expect(s.index.embeddingBlocked.known).toBe(false);
    expect(s.readiness.known).toBe(false);
    expect(s.statusLabel).toBe('Connecting…');
  });

  it('§2.B / B4 + 649: poll stale then truly unreachable — staged "Catching up…" → "Reconnecting…", last-known retained', () => {
    vi.useFakeTimers();
    try {
      const t0 = new Date('2026-01-01T00:00:00Z').getTime();
      vi.setSystemTime(t0);
      // One successful status poll: 42 docs (a poll success is positive contact, tempdoc 649).
      __feedForTest({
        status: { worker: { core: { indexedDocuments: 42 } } } as unknown as StatusSnapshot,
      });
      __tickClockForTest();
      expect(getAiState().phase).toBe('connected');
      expect(getAiState().connection.reachable).toBe(true);
      expect(getAiState().index.documentCount).toEqual(known(42));

      // 16s later: past the 15s poll-freshness threshold, but the last contact (the t0 poll) is only
      // 16s old — within the 40s reachability window. 649: data is behind but the backend is provably
      // reachable, so the calm "Catching up…", NOT the "Reconnecting…" alarm.
      vi.setSystemTime(t0 + 16_000);
      __tickClockForTest();
      let s = getAiState();
      expect(s.phase).toBe('stale');
      expect(s.connection.reachable).toBe(true);
      expect(s.statusLabel).toBe('Catching up…');
      expect(s.index.documentCount).toEqual(known(42)); // last-known retained, not wiped
      expect(s.statusTier).toBe('degraded'); // statusTier still 'degraded' (it no longer drives tone)
      // 649 tone fix: the calm "Catching up…" state is `info` (calm tint), NOT amber — so every surface
      // (status pill, liveness dot) renders it calm, matching the Health badge.
      expect(s.statusTone).toBe('info');
      expect(s.connection.lastContactMs).toBe(t0); // contact stamp surfaced (the t0 poll success)

      // 41s later: NO contact of any kind within the 40s window ⇒ genuinely unreachable ⇒ the alarm.
      vi.setSystemTime(t0 + 41_000);
      __tickClockForTest();
      s = getAiState();
      expect(s.connection.reachable).toBe(false);
      expect(s.statusLabel).toBe('Reconnecting…');
      // 649 ramp: lost contact ("Reconnecting…") is a WARNING (amber), distinct from the calm catch-up.
      expect(s.statusTone).toBe('warning');
      expect(s.index.documentCount).toEqual(known(42)); // still retained
    } finally {
      vi.useRealTimers();
    }
  });

  it('649: a stale poll stays calm "Catching up…" while an SSE frame keeps contact fresh', () => {
    vi.useFakeTimers();
    try {
      const t0 = new Date('2026-01-01T00:00:00Z').getTime();
      vi.setSystemTime(t0);
      __feedForTest({
        status: { worker: { core: { indexedDocuments: 7 } } } as unknown as StatusSnapshot,
      });
      __tickClockForTest();

      // 50s later: the poll is long stale (>40s) — WITHOUT a stream this would be "Reconnecting…".
      // But an SSE frame arrived at t0+45s (positive contact), so the origin is reachable.
      vi.setSystemTime(t0 + 45_000);
      __feedContactForTest(t0 + 45_000); // mirrors EnvelopeStream.handleFrame bumping the stamp
      vi.setSystemTime(t0 + 50_000);
      __tickClockForTest();
      const s = getAiState();
      expect(s.phase).toBe('stale'); // poll data is behind
      expect(s.connection.reachable).toBe(true); // but a recent SSE frame proves the backend is alive
      expect(s.statusLabel).toBe('Catching up…'); // calm, not the false "Reconnecting…"
      expect(s.statusTone).toBe('info'); // 649: calm tone, not amber
      expect(s.connection.lastContactMs).toBe(t0 + 45_000); // contact stamp = the SSE frame
    } finally {
      vi.useRealTimers();
    }
  });

  it('601 §19: model-load `starting` shows a live measured elapsed (>2s gate, minute-aware, clears on leave)', () => {
    vi.useFakeTimers();
    try {
      const t0 = new Date('2026-01-01T00:00:00Z').getTime();
      // A fresh poll (status keeps the connection live so the verdict stays settled, not
      // 'reconnecting'; inference carries the model 'starting' flag). Re-feeding while already
      // starting does NOT re-stamp loadStartedAt (the capture is edge-triggered), so elapsed
      // measures from t0 — exactly the real 5s-poll behavior during a load.
      const pollStarting = () =>
        __feedForTest({
          status: { worker: { core: { indexedDocuments: 5, pendingJobs: 0 } } } as unknown as StatusSnapshot,
          inference: { mode: 'transitioning', starting: true, available: false } as unknown as InferenceSnapshot,
        });

      vi.setSystemTime(t0);
      pollStarting();
      expect(getAiState().runtime.mode).toBe('starting');
      // <2s elapsed → bare label (no fabricated tiny number).
      expect(getAiState().statusLabel).toBe('Starting…');

      // 12s in → measured count-up (a count-UP, never a countdown).
      vi.setSystemTime(t0 + 12_000);
      pollStarting();
      expect(getAiState().statusLabel).toBe('Starting… 12s');

      // 90s in (a cold load) → minute-aware wording via humanizeSeconds.
      vi.setSystemTime(t0 + 90_000);
      pollStarting();
      expect(getAiState().statusLabel).toBe('Starting… 1m 30s');

      // Leaving 'starting' (→ online) clears the stamp: the count-up does not persist.
      __feedForTest({
        inference: { mode: 'online', starting: false, available: true } as unknown as InferenceSnapshot,
      });
      expect(getAiState().runtime.mode).toBe('online');
      expect(getAiState().statusLabel.startsWith('Starting')).toBe(false);
      expect(getAiState().statusTone).toBe('success'); // 649: settled-online is green
    } finally {
      vi.useRealTimers();
    }
  });

  it('unsubscribe stops delivery', async () => {
    const seen: string[] = [];
    const unsub = subscribeAiState((s) => seen.push(s.activity.state));
    unsub();
    setAiActivity({ state: 'thinking' });
    await microtask();
    expect(seen).toEqual(['idle']); // only the initial sync call survived
  });
});

describe('aiStateStore — runtime-authority engine axis (tempdoc 737 §12b/§12c)', () => {
  beforeEach(() => __resetAiStateForTest());
  afterEach(() => __resetAiStateForTest());

  // The authority fields ride on the /api/status inference block (statusSig), preferred over the
  // legacy /api/inference/status mode (inferenceSig).
  const feedAuthority = (inf: Record<string, unknown>, legacy?: Record<string, unknown>) =>
    __feedForTest({
      status: { inference: inf } as unknown as StatusSnapshot,
      ...(legacy ? { inference: legacy as unknown as InferenceSnapshot } : {}),
    });

  it('engineState is preferred over the legacy mode when resolving runtime.mode', () => {
    // Legacy mode says offline; the authority says Healthy → runtime.mode follows the authority.
    feedAuthority({ engineState: 'Healthy' }, { mode: 'offline', available: false });
    expect(getAiState().runtime.mode).toBe('online');
  });

  it('soft-off: Healthy + chatEnabledSpec=false + procedure → aiEngine "background", chat capability OFF', () => {
    feedAuthority(
      { engineState: 'Healthy', chatEnabledSpec: false, procedure: 'VDU_BATCH' },
      { mode: 'online', available: true },
    );
    const s = getAiState();
    expect(s.aiEngine.kind).toBe('background');
    // The soft-off guard: even though mode==='online' && available, chat is NOT offered.
    expect(s.capabilities.chat).toBe(false);
  });

  it('engineState Down + gpu-yielded-to-indexing → runtime.mode "indexing"', () => {
    feedAuthority({ engineState: 'Down', engineReason: 'gpu-yielded-to-indexing' });
    expect(getAiState().runtime.mode).toBe('indexing');
  });

  it('absent authority fields → legacy mode fallback still resolves runtime.mode', () => {
    __feedForTest({
      inference: { mode: 'online', available: true } as unknown as InferenceSnapshot,
    });
    expect(getAiState().runtime.mode).toBe('online');
    expect(getAiState().capabilities.chat).toBe(true); // no chatEnabledSpec → legacy behaviour
  });
});

// Tempdoc 595 — the derived verdict + stability axis + their status-bar projection.
describe('aiStateStore — system-health verdict (595)', () => {
  beforeEach(() => __resetAiStateForTest());
  afterEach(() => __resetAiStateForTest());

  function statusWith(
    retrieval: 'READY' | 'DEGRADED' | 'UNKNOWN',
    reasonCodes: string[] = [],
    over: {
      indexState?: string;
      migrationState?: string;
      building?: string;
      active?: string;
      docs?: number;
      sizeBytes?: number;
      indexComponentState?: 'ABSENT' | 'STARTING' | 'READY' | 'RELOADING' | 'FAILED' | 'UNAVAILABLE';
      indexComponentReasonCode?: string;
      aiReasonCodes?: string[];
      /** 811 C-4 — omitted means the backend does NOT report the field (the pre-811 shape). */
      searchable?: number;
    } = {},
  ): StatusSnapshot {
    return {
      worker: {
        core: {
          indexedDocuments: over.docs ?? 5,
          ...(over.searchable === undefined ? {} : { searchableDocuments: over.searchable }),
          indexSizeBytes: over.sizeBytes ?? 1024,
          pendingJobs: 0,
          indexState: over.indexState ?? 'IDLE',
          indexHealthy: true,
        },
        migration: {
          migrationState: over.migrationState ?? 'IDLE',
          activeGenerationId: over.active ?? 'g1',
          buildingGenerationId: over.building ?? '',
          servingSearchGenerationId: 'g1',
          servingIngestGenerationId: 'g1',
        },
      },
      readiness: {
        engineComponents: {
          index: {
            state: over.indexComponentState ?? 'READY',
            ...(over.indexComponentReasonCode === undefined
              ? {}
              : { reasonCode: over.indexComponentReasonCode }),
          },
        },
        composites: {
          retrieval: { state: retrieval, reasonCodes },
          aiFeatures: {
            state: over.aiReasonCodes?.length ? 'DEGRADED' : 'READY',
            reasonCodes: over.aiReasonCodes ?? [],
          },
        },
      },
      schema: { reindexRequired: false },
    } as unknown as StatusSnapshot;
  }

  function feed(s: StatusSnapshot): void {
    __feedForTest({ status: s });
    __tickClockForTest();
  }

  it('§10.1 fix: the status-bar tier reflects readiness — impairing degraded ⇒ degraded tier', () => {
    feed(statusWith('DEGRADED', ['worker.health.embedding_not_ready']));
    const s = getAiState();
    expect(s.verdict.kind).toBe('degraded');
    expect(s.verdict.severity).toBe('warn');
    expect(s.statusTier).toBe('degraded'); // was 'online' before 595 — the live split
    expect(s.statusTone).toBe('warning'); // 649: an impairing degrade is amber on every surface
  });

  it('600 Design A: a compat reindex code carries through to a degraded verdict (the specific cause)', () => {
    feed(statusWith('DEGRADED', ['index.blocked_legacy']));
    const s = getAiState();
    expect(s.verdict.kind).toBe('degraded');
    expect(s.verdict.severity).toBe('warn');
    // The new compat reason code reaches the verdict's reasons (so readinessNotice can name it).
    expect(s.verdict.reasons).toContain('index.blocked_legacy');
    expect(s.statusTier).toBe('degraded');
  });

  it('§10.3 fix: a COSMETIC degraded (LambdaMART) stays calm — verdict info, tier NOT degraded', () => {
    feed(statusWith('DEGRADED', ['lambdamart.not_configured']));
    const s = getAiState();
    expect(s.verdict.kind).toBe('degraded');
    expect(s.verdict.severity).toBe('info');
    expect(s.statusTier).not.toBe('degraded'); // no false alarm for an optional re-ranker
    expect(s.statusTone).toBe('info'); // 649: a cosmetic degrade stays calm (info), never amber
  });

  it('a worker-down fallback (indexState UNAVAILABLE) ⇒ transitioning, "Restarting…"', () => {
    feed(statusWith('UNKNOWN', [], { indexState: 'UNAVAILABLE' }));
    const s = getAiState();
    expect(s.stability).toEqual({ kind: 'provisional', cause: 'worker-restart' });
    expect(s.verdict.kind).toBe('transitioning');
    expect(s.statusLabel).toBe('Restarting…');
  });

  it('exact index recovery wins over the Worker fallback and uses recovery wording', () => {
    feed(statusWith('UNKNOWN', [], {
      indexState: 'UNAVAILABLE',
      indexComponentState: 'STARTING',
      indexComponentReasonCode: 'component.recovering',
    }));
    const s = getAiState();
    expect(s.stability).toEqual({ kind: 'provisional', cause: 'index-recovery' });
    expect(s.verdict.kind).toBe('transitioning');
    expect(s.statusLabel).toBe('Recovering search…');
  });

  it('optional AI recovery does not become retrieval recovery', () => {
    feed(statusWith('DEGRADED', ['lambdamart.not_configured'], {
      indexComponentState: 'READY',
      aiReasonCodes: ['component.recovering'],
    }));
    const s = getAiState();
    expect(s.stability).toEqual({ kind: 'settled' });
    expect(s.verdict.kind).toBe('degraded');
    expect(s.verdict.severity).toBe('info');
  });

  it('a rebuild (migration MIGRATING) ⇒ transitioning, "Rebuilding…"', () => {
    feed(statusWith('READY', [], { migrationState: 'MIGRATING' }));
    const s = getAiState();
    expect(s.verdict.kind).toBe('transitioning');
    expect(s.verdict.reasons).toEqual(['rebuilding']);
    expect(s.statusLabel).toBe('Rebuilding…');
  });

  it('a healthy settled system ⇒ operational/ok', () => {
    feed(statusWith('READY'));
    const s = getAiState();
    expect(s.verdict.kind).toBe('operational');
    expect(s.verdict.severity).toBe('ok');
    expect(s.stability).toEqual({ kind: 'settled' });
  });

  /**
   * Tempdoc 806 B.2 (round-12: "a dark-red dot beside 'Online' on Health", reproduced with no skin).
   * The dot (`statusTone`) and the words beside it (`statusLabel`) are ONE indicator — `LivenessReadout`
   * renders them together on the Health Connection card. Pre-fix the tone branch listed `degraded` and
   * the label branch did not, so the dot reported the system verdict while the text reported the AI
   * engine. This pins the derivation parity, not one wording.
   */
  it('806: for a degraded verdict the dot tone and the label beside it come from the SAME verdict', () => {
    for (const codes of [
      ['worker.health.embedding_not_ready'], // impairing -> warning dot
      ['lambdamart.not_configured'], // cosmetic -> calm info dot
    ]) {
      __resetAiStateForTest();
      feed(statusWith('DEGRADED', codes));
      // An ONLINE AI engine is what made the label say "Online" over a degraded system.
      __feedForTest({
        inference: { mode: 'online', starting: false, available: true } as unknown as InferenceSnapshot,
      });
      const s = getAiState();
      expect(s.verdict.kind).toBe('degraded');
      expect(s.statusTone).toBe(verdictTone(s.verdict.severity));
      expect(s.statusLabel).toBe(verdictHeadline(s.verdict));
      expect(s.statusLabel.startsWith('Online')).toBe(false);
    }
  });

  // 649 scope guard — the tone fix is CONNECTION-only; non-connection states keep their pre-649 tone.
  it('649: AI activity does NOT flatten statusTone — it follows the underlying health', () => {
    feed(statusWith('READY'));
    __feedForTest({
      inference: { mode: 'online', starting: false, available: true } as unknown as InferenceSnapshot,
    });
    expect(getAiState().statusTone).toBe('success');
    setAiActivity({ state: 'thinking' });
    expect(getAiState().statusLabel).toBe('Thinking…'); // activity overlays the LABEL…
    expect(getAiState().statusTone).toBe('success'); // …but NOT the tone (was flattened to 'info' pre-fix)
    // A real degradation must still show amber while thinking (tone follows underlying health).
    feed(statusWith('DEGRADED', ['worker.health.embedding_not_ready']));
    expect(getAiState().statusTone).toBe('warning');
    setAiActivity({ state: 'idle' });
  });

  it('649: indexing/starting keep the prior amber "in-flux" tone (connection-only scope)', () => {
    feed(statusWith('READY'));
    __feedForTest({
      inference: { mode: 'indexing', starting: false, available: true } as unknown as InferenceSnapshot,
    });
    expect(getAiState().runtime.mode).toBe('indexing');
    expect(getAiState().statusTone).toBe('warning');
    __feedForTest({
      inference: { mode: 'transitioning', starting: true, available: false } as unknown as InferenceSnapshot,
    });
    expect(getAiState().runtime.mode).toBe('starting');
    expect(getAiState().statusTone).toBe('warning');
  });

  it('649: a fresh never-connected store reads calm "Connecting…" (info), not a false alarm', () => {
    __resetAiStateForTest();
    // Startup grace: before the first poll lands, the verdict is the calm `connecting`, not a false
    // "Backend disconnected" — and its tone is `info`, not red. (The hard `unreachable→error` mapping is
    // covered by verdict.test's verdictTone + the uniform connection branch of computeStatusTone.)
    expect(getAiState().verdict.kind).toBe('connecting');
    expect(getAiState().statusTone).toBe('info');
  });

  // 595 §15.3 (E2) — last-settled index retention across a provisional window.
  it('E2: lastSettledIndex is null before any settled poll', () => {
    expect(getAiState().lastSettledIndex).toBeNull();
  });

  it('E2: a settled poll stamps lastSettledIndex; a provisional poll keeps it', () => {
    feed(statusWith('READY', [], { docs: 1234, sizeBytes: 4096 }));
    expect(getAiState().lastSettledIndex).toEqual({ documentCount: 1234, searchableDocumentCount: null, indexSizeBytes: 4096 });
    // Worker restarts: a *successful* poll returns the fallback (0 docs / UNAVAILABLE). The
    // retained settled value must NOT be overwritten by that transient zero.
    feed(statusWith('UNKNOWN', [], { indexState: 'UNAVAILABLE', docs: 0, sizeBytes: 0 }));
    const s = getAiState();
    expect(s.stability.kind).toBe('provisional');
    expect(s.lastSettledIndex).toEqual({ documentCount: 1234, searchableDocumentCount: null, indexSizeBytes: 4096 });
  });

  it('E2: a later settled poll refreshes lastSettledIndex', () => {
    feed(statusWith('READY', [], { docs: 10, sizeBytes: 100 }));
    feed(statusWith('READY', [], { docs: 20, sizeBytes: 200 }));
    expect(getAiState().lastSettledIndex).toEqual({
      documentCount: 20,
      searchableDocumentCount: null,
      indexSizeBytes: 200,
    });
  });

  it('E2: a settled poll with a doc count but NO size stamps indexSizeBytes=null (honesty)', () => {
    // A present doc count with an absent size retains null for size (renderers show "…", not "0 B").
    feed({
      worker: {
        core: { indexedDocuments: 77, indexState: 'IDLE', indexHealthy: true },
        migration: { migrationState: 'IDLE', activeGenerationId: 'g1', buildingGenerationId: '', servingSearchGenerationId: 'g1', servingIngestGenerationId: 'g1' },
      },
      readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } },
    } as unknown as StatusSnapshot);
    expect(getAiState().lastSettledIndex).toEqual({
      documentCount: 77,
      searchableDocumentCount: null,
      indexSizeBytes: null,
    });
  });

  // Tempdoc 811 C-4 — the default-search-scope population, distinct from the whole-index count.
  it('C-4: index.searchableDocumentCount is UNKNOWN when the backend omits the field', () => {
    feed(statusWith('READY', [], { docs: 40 }));
    const index = getAiState().index;
    expect(index.documentCount).toEqual(known(40));
    expect(index.searchableDocumentCount).toBe(UNKNOWN);
  });

  it('C-4: index.searchableDocumentCount carries the reported value, including a real 0', () => {
    feed(statusWith('READY', [], { docs: 40, searchable: 31 }));
    expect(getAiState().index.searchableDocumentCount).toEqual(known(31));
    // A default scope that excludes EVERY indexed document reports 0 — a known value, not absence.
    feed(statusWith('READY', [], { docs: 40, searchable: 0 }));
    expect(getAiState().index.searchableDocumentCount).toEqual(known(0));
  });

  it('C-4: a settled poll retains searchableDocumentCount; 0 is retained as 0, not as null', () => {
    feed(statusWith('READY', [], { docs: 40, sizeBytes: 4096, searchable: 31 }));
    expect(getAiState().lastSettledIndex).toEqual({
      documentCount: 40,
      searchableDocumentCount: 31,
      indexSizeBytes: 4096,
    });
    feed(statusWith('READY', [], { docs: 40, sizeBytes: 4096, searchable: 0 }));
    expect(getAiState().lastSettledIndex?.searchableDocumentCount).toBe(0);
  });

  // 813 §19 (W2) — the drain episode's high-water backlog. Mirrors the E2 cases above: an
  // imperative stamp on the poll callback, guarded against the hard-zeroed fallback snapshot.
  function withPendingJobs(pendingJobs: number, indexState = 'INDEXING'): StatusSnapshot {
    return {
      worker: {
        core: { indexedDocuments: 5, indexState, indexHealthy: true, pendingJobs },
        migration: {
          migrationState: 'IDLE',
          activeGenerationId: 'g1',
          buildingGenerationId: '',
          servingSearchGenerationId: 'g1',
          servingIngestGenerationId: 'g1',
        },
      },
    } as unknown as StatusSnapshot;
  }

  it('W2: episodeMaxPendingJobs is 0 before any poll', () => {
    expect(getAiState().episodeMaxPendingJobs).toBe(0);
  });

  it('W2: it rises to the episode PEAK and holds it while the queue drains', () => {
    feed(withPendingJobs(400));
    expect(getAiState().episodeMaxPendingJobs).toBe(400);
    feed(withPendingJobs(1600));
    expect(getAiState().episodeMaxPendingJobs).toBe(1600);
    // Draining must NOT lower the mark — it is the denominator the position is measured against.
    feed(withPendingJobs(900));
    expect(getAiState().episodeMaxPendingJobs).toBe(1600);
  });

  it('W2: a genuine drain to 0 ENDS the episode, so the next spike measures itself', () => {
    feed(withPendingJobs(1600));
    feed(withPendingJobs(0, 'IDLE'));
    expect(getAiState().episodeMaxPendingJobs).toBe(0);
    feed(withPendingJobs(50));
    expect(getAiState().episodeMaxPendingJobs).toBe(50);
  });

  it('W2: a hard-zeroed fallback snapshot is absence, not a drain — the mark survives it', () => {
    feed(withPendingJobs(1600));
    // Worker restart: a *successful* poll returns the fallback block (UNAVAILABLE + zeroed counts).
    // Reading its `pendingJobs: 0` as a drain would reset the denominator mid-episode.
    feed(withPendingJobs(0, 'UNAVAILABLE'));
    expect(getAiState().episodeMaxPendingJobs).toBe(1600);
  });

  // 813 §20 — the ENRICHMENT episode's settle trail, the second cross-poll memory. Same shape as
  // W2 above: an imperative stamp on the poll callback, cleared when the episode ends.
  function withEnrichment(pendingCount: number, docCount = 1000): StatusSnapshot {
    return {
      worker: {
        core: { indexedDocuments: 5, indexState: 'IDLE', indexHealthy: true, pendingJobs: 0 },
        enrichment: {
          backfillMode: 'running',
          embeddingDocCount: docCount,
          embeddingPendingCount: pendingCount,
          embeddingEnabled: true,
        },
        migration: {
          migrationState: 'IDLE',
          activeGenerationId: 'g1',
          buildingGenerationId: '',
          servingSearchGenerationId: 'g1',
          servingIngestGenerationId: 'g1',
        },
      },
    } as unknown as StatusSnapshot;
  }

  it('§20: the settle trail is empty before any poll', () => {
    expect(getAiState().enrichSettleSamples).toEqual([]);
  });

  it('§20: an enriching poll stamps the blend SETTLED sum against the wall clock', () => {
    vi.useFakeTimers();
    vi.setSystemTime(1_000);
    feed(withEnrichment(300));
    vi.setSystemTime(11_000);
    feed(withEnrichment(200));
    // 1,000 documents, 300 then 200 pending ⟹ 700 then 800 settled — the same quantity
    // `enrichingPercent` divides, not a private count.
    expect(getAiState().enrichSettleSamples).toEqual([
      { t: 1_000, settled: 700 },
      { t: 11_000, settled: 800 },
    ]);
    vi.useRealTimers();
  });

  it('§20: the trail is bounded — only the most recent samples are kept', () => {
    vi.useFakeTimers();
    for (let i = 0; i < 9; i += 1) {
      vi.setSystemTime((i + 1) * 10_000);
      feed(withEnrichment(300 - i * 10));
    }
    const samples = getAiState().enrichSettleSamples;
    expect(samples.length).toBe(6);
    // The window slid: the oldest sample is the 4th poll, not the 1st.
    expect(samples[0]).toEqual({ t: 40_000, settled: 730 });
    expect(samples[5]).toEqual({ t: 90_000, settled: 780 });
    vi.useRealTimers();
  });

  it('§20: leaving the enriching phase CLEARS the trail — a fresh episode measures itself', () => {
    vi.useFakeTimers();
    vi.setSystemTime(1_000);
    feed(withEnrichment(300));
    vi.setSystemTime(11_000);
    feed(withEnrichment(200));
    expect(getAiState().enrichSettleSamples.length).toBe(2);

    // Ingest resumes ⟹ the phase is `indexing`, and intervals spanning the two regimes would
    // compare rates nobody measured together.
    vi.setSystemTime(21_000);
    feed(withPendingJobs(400));
    expect(getAiState().enrichSettleSamples).toEqual([]);

    vi.setSystemTime(31_000);
    feed(withEnrichment(150));
    expect(getAiState().enrichSettleSamples).toEqual([{ t: 31_000, settled: 850 }]);
    vi.useRealTimers();
  });

  it('§20: a hard-zeroed fallback snapshot clears the trail rather than stamping its zeros', () => {
    vi.useFakeTimers();
    vi.setSystemTime(1_000);
    feed(withEnrichment(300));
    vi.setSystemTime(11_000);
    feed(withPendingJobs(0, 'UNAVAILABLE'));
    expect(getAiState().enrichSettleSamples).toEqual([]);
    vi.useRealTimers();
  });

  it('§20: four stamped polls are enough for the selector to render an estimate', () => {
    vi.useFakeTimers();
    // 100 documents settled per 10 s poll ⟹ 10/s; 0 pending would end the phase, so stop at 200.
    [300, 200, 100, 50].forEach((pending, i) => {
      vi.setSystemTime((i + 1) * 10_000);
      feed(withEnrichment(pending, 1000));
    });
    const s = getAiState();
    const p = selectIndexingProgress(s.status, true, s.episodeMaxPendingJobs, s.enrichSettleSamples);
    expect(p.phase).toBe('enriching');
    // Rates 10/s, 10/s, 5/s ⟹ median 10/s over 50 pending ⟹ 5 s.
    expect(p.enrichingEtaSeconds).toBe(5);
    vi.useRealTimers();
  });
});

describe('computeRealized — tempdoc 644 realized engine projection', () => {
  beforeEach(() => __resetAiStateForTest());
  afterEach(() => __resetAiStateForTest());

  it('projects loaded + accelerator + failure per engine from worker.gpu', () => {
    __feedForTest({
      status: {
        worker: {
          gpu: {
            rerankerModelPath: '/models/onnx/reranker',
            rerankerOrtCuda: { available: true, attempted: true },
            embedBackend: 'onnx',
            embedOrtCuda: { available: false, attempted: true },
            spladeModelPath: '/models/splade',
            spladeOrtCuda: { available: false, attempted: false, failureReason: 'lazy' },
          },
        },
      } as unknown as StatusSnapshot,
    });
    const r = getAiState().realized;
    expect(r.reranker).toEqual({ loaded: true, accelerator: 'gpu', failureReason: null });
    expect(r.embed).toEqual({ loaded: true, accelerator: 'cpu', failureReason: null });
    // present but device not yet probed (attempted false) → accelerator null, NOT a false "CPU".
    expect(r.splade).toEqual({ loaded: true, accelerator: null, failureReason: 'lazy' });
  });

  it('reports an absent reranker as not loaded (the silent CE-off worktree trap)', () => {
    __feedForTest({
      status: { worker: { gpu: { embedBackend: 'onnx' } } } as unknown as StatusSnapshot,
    });
    const r = getAiState().realized;
    expect(r.reranker.loaded).toBe(false);
    expect(r.reranker.accelerator).toBe(null);
    expect(r.embed.loaded).toBe(true);
  });
});

describe('aiStateStore — derived context window (tempdoc 883 decision 1 / ADR-0047)', () => {
  beforeEach(() => __resetAiStateForTest());
  afterEach(() => __resetAiStateForTest());

  const feedInference = (inf: Record<string, unknown>) =>
    __feedForTest({ inference: inf as unknown as InferenceSnapshot });

  it('projects the wire rung/reason/slots/kvType beside the OBSERVED llmContextTokens', () => {
    feedInference({
      mode: 'online',
      available: true,
      llmContextTokens: 32768,
      contextWindow: { rung: 32768, reason: 'top-rung', slots: 2, kvType: 'q8_0', freeVramBytes: 9573388288 },
    });
    const r = getAiState().runtime;
    // Two distinct facts, kept distinct: observation first, intent alongside it.
    expect(r.contextWindow).toBe(32768);
    expect(r.contextWindowDerived).toEqual({ rung: 32768, reason: 'top-rung', slots: 2, kvType: 'q8_0' });
  });

  it('no contextWindow block (adopted/external engine) → null, with the observation untouched', () => {
    feedInference({ mode: 'online', available: true, llmContextTokens: 8192 });
    expect(getAiState().runtime.contextWindow).toBe(8192);
    expect(getAiState().runtime.contextWindowDerived).toBeNull();
  });

  it('a rung of 0 is NOT a derived window — it is the absent block, and must not render as "0"', () => {
    // A `contextWindow: {}` (or an explicit 0) reaches the FE as `rung: undefined | 0`. Passing it
    // through would put a rung of zero on the Brain/Settings readout as if it were a real launch.
    feedInference({ mode: 'online', available: true, llmContextTokens: 8192, contextWindow: { rung: 0 } });
    expect(getAiState().runtime.contextWindowDerived).toBeNull();
  });
});
