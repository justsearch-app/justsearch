// @vitest-environment happy-dom

/**
 * Tempdoc 596 — projectAvailability tests. Pins the capability→availability projection:
 * one reason per gap, the loading window is transient, and a reranker-only "degraded"
 * (chat still up) never marks an affordance unavailable.
 */
import { describe, expect, it } from 'vitest';
import { projectAvailability, unavailableBecause, type Availability } from './availability.js';
import {
  AI_UNAVAILABLE_CAVEAT,
  KEYWORD_FALLBACK_CAVEAT,
  OPTIONAL_CAPABILITY_CAVEAT,
  PASSAGE_REDUCED_CAVEAT,
  reasonFor,
} from './readinessNotice.js';
import type { AiState } from './aiStateStore.js';
import { known, UNKNOWN } from './known.js';

/** Minimal AiState carrying only the fields projectAvailability reads. */
function aiState(opts: {
  phase?: AiState['phase'];
  chat?: boolean;
  docs?: number | 'unknown';
  mode?: AiState['runtime']['mode'];
  pendingJobs?: number;
  /** When set, builds a degraded verdict with this severity (the ONE authority projectAvailability reads). */
  degradedSeverity?: 'info' | 'warn';
  /**
   * Tempdoc 805 §G.2 — the degraded verdict's REASON CODES. The caveat is now classifier-derived, so
   * a fixture must carry the codes that actually produced the severity (a degraded verdict always
   * does); an empty list is the deliberately-conservative `unknown` class.
   */
  reasons?: string[];
  /** Tempdoc 601 — the last successful startup duration the model-load estimate reads (-1 ⇒ unknown). */
  lastStartupMs?: number;
  /**
   * Tempdoc 807 A.3 — is the snapshot still a LIVE observation? A fixture whose subject is NOT
   * liveness asserts a live snapshot (the honest default: these tests describe a backend that is
   * answering). The one test that IS about liveness passes `false`.
   */
  snapshotLive?: boolean;
}): AiState {
  const docs = opts.docs ?? 'unknown';
  const verdict =
    opts.degradedSeverity === undefined
      ? { kind: 'operational', severity: 'ok', reasons: [] }
      : { kind: 'degraded', severity: opts.degradedSeverity, reasons: opts.reasons ?? [] };
  return {
    phase: opts.phase ?? 'connected',
    snapshotLive: opts.snapshotLive ?? true,
    capabilities: { chat: opts.chat ?? true, rag: false, extract: false, embedding: false },
    runtime: { mode: opts.mode ?? 'online' },
    verdict,
    index: {
      documentCount: docs === 'unknown' ? UNKNOWN : known(docs),
      pendingJobs: opts.pendingJobs === undefined ? UNKNOWN : known(opts.pendingJobs),
    },
    inference: opts.lastStartupMs === undefined ? null : { lastStartupDurationMs: opts.lastStartupMs },
  } as unknown as AiState;
}

const isUnavailable = (a: Availability): a is Extract<Availability, { kind: 'unavailable' }> =>
  a.kind === 'unavailable';

describe('projectAvailability (tempdoc 596)', () => {
  it('null store → transient loading reason (the obs #420 loading window)', () => {
    const a = projectAvailability('agent', null);
    expect(a.kind).toBe('unavailable');
    expect(isUnavailable(a) && a.transient).toBe(true);
    // §17: wording comes from the shared reason vocabulary (`inference.starting`).
    expect(isUnavailable(a) && /start|load/i.test(a.reason)).toBe(true);
  });

  it("phase==='connecting' → transient (still loading, not offline)", () => {
    const a = projectAvailability('documents', aiState({ phase: 'connecting' }));
    expect(isUnavailable(a) && a.transient).toBe(true);
  });

  // Tempdoc 601 — model-load time-estimate on the actively-loading (runtime.mode==='starting') gap.
  it("runtime.mode==='starting' with a prior duration → transient 'still starting' + '~Ns' estimate", () => {
    const a = projectAvailability('documents', aiState({ chat: false, mode: 'starting', lastStartupMs: 11000 }));
    expect(a.kind).toBe('unavailable');
    expect(isUnavailable(a) && a.transient).toBe(true);
    expect(isUnavailable(a) && /still starting/i.test(a.reason)).toBe(true);
    // The estimate arm: the suffix carries the rounded seconds, never a countdown.
    expect(isUnavailable(a) && a.reason).toContain('usually ready in ~11s');
  });

  it("runtime.mode==='starting' without a prior duration (-1) → 'still starting' with NO number (unknown arm)", () => {
    const a = projectAvailability('documents', aiState({ chat: false, mode: 'starting', lastStartupMs: -1 }));
    expect(a.kind).toBe('unavailable');
    expect(isUnavailable(a) && a.transient).toBe(true);
    expect(isUnavailable(a) && /still starting/i.test(a.reason)).toBe(true);
    // No fabricated number on the unknown arm.
    expect(isUnavailable(a) && /~|usually ready/i.test(a.reason)).toBe(false);
  });

  it("runtime.mode==='starting' takes precedence over the settled offline reason (keyed on load state)", () => {
    // chat:false would otherwise yield the settled 'offline'; the starting branch must win.
    const a = projectAvailability('agent', aiState({ chat: false, mode: 'starting', lastStartupMs: 8000 }));
    expect(isUnavailable(a) && a.transient).toBe(true);
    expect(isUnavailable(a) && /offline/i.test(a.reason)).toBe(false);
  });

  it('chat offline → settled offline reason + the shared-vocabulary remedy (§17)', () => {
    const a = projectAvailability('agent', aiState({ chat: false }));
    expect(a.kind).toBe('unavailable');
    expect(isUnavailable(a) && a.transient).toBeFalsy();
    expect(isUnavailable(a) && /offline/i.test(a.reason)).toBe(true);
    // §17 Move A — the affordance now carries the SAME remedy the banner does (no fork).
    expect(isUnavailable(a) && a.remedy).toEqual({
      kind: 'navigate', target: 'core.brain-surface', label: 'Open Brain',
    });
  });

  it('documents with zero indexed docs (idle) → settled "No documents indexed" + onboarding remedy', () => {
    const a = projectAvailability('documents', aiState({ chat: true, docs: 0 }));
    expect(a.kind).toBe('unavailable');
    expect(isUnavailable(a) && a.transient).toBeFalsy();
    expect(isUnavailable(a) && /document/i.test(a.reason)).toBe(true);
    // §16.5 remedy-driven onboarding: the dead-end carries the "Add documents" → Library navigate remedy.
    expect(isUnavailable(a) && a.remedy).toEqual({
      kind: 'navigate',
      target: 'core.library-surface',
      label: 'Add documents',
    });
  });

  it('zero docs WHILE indexing (runtime.mode) → transient forward-looking reason (§16.4 availableWhen)', () => {
    const a = projectAvailability('documents', aiState({ chat: true, docs: 0, mode: 'indexing' }));
    expect(a.kind).toBe('unavailable');
    expect(isUnavailable(a) && a.transient).toBe(true);
    expect(isUnavailable(a) && /indexing|once/i.test(a.reason)).toBe(true);
  });

  it('zero docs WHILE jobs pending → transient forward-looking reason (§16.4 availableWhen)', () => {
    const a = projectAvailability('documents', aiState({ chat: true, docs: 0, pendingJobs: 3 }));
    expect(isUnavailable(a) && a.transient).toBe(true);
    expect(isUnavailable(a) && /indexing|once/i.test(a.reason)).toBe(true);
  });

  it('documents with docs>0 and chat up → available', () => {
    expect(projectAvailability('documents', aiState({ chat: true, docs: 5 })).kind).toBe('available');
  });

  it('documents usable but verdict degraded (cosmetic) → calm caveat, still operable', () => {
    // chat up, docs present, the ONE verdict is degraded because an optional re-ranker is off.
    // RE-PINNED, tempdoc 805 §G.2: the fixture now carries the REASON CODE that produced the info
    // severity, because the caveat is derived from the cause class, not from severity.
    const a = projectAvailability(
      'documents',
      aiState({ chat: true, docs: 5, degradedSeverity: 'info', reasons: ['lambdamart.not_configured'] }),
    );
    expect(a.kind).toBe('degraded');
    expect(a.kind === 'degraded' && a.caveat).toBe(OPTIONAL_CAPABILITY_CAVEAT);
    // Round-14 finding 8 — the info-tier availability row must NAME the specific feature. The
    // pre-fix wording ("an optional ranking model") named none, and a reader with full API access
    // resolved it to the cross-encoder reranker, which was measured healthy at the time.
    expect(a.kind === 'degraded' && a.caveat).toContain('Learned re-ranking');
    expect(a.kind === 'degraded' && a.caveat).toContain('LambdaMART');
    // …and it routes to the same detail the warn tier routes to (it carried no remedy before).
    expect(a.kind === 'degraded' && a.remedy).toEqual({
      kind: 'navigate',
      target: 'core.health-surface',
      label: 'Open Health',
    });
  });

  it('805 §G.2: a RETRIEVAL-impairing cause → the keyword-fallback caveat (imported, not re-authored)', () => {
    const a = projectAvailability(
      'documents',
      aiState({ chat: true, docs: 5, degradedSeverity: 'warn', reasons: ['index.dense_unavailable'] }),
    );
    expect(a.kind).toBe('degraded');
    expect(a.kind === 'degraded' && a.caveat).toBe(KEYWORD_FALLBACK_CAVEAT);
  });

  it('805 §G.2: a PASSAGE-only gap → the passage caveat, never the keyword-fallback claim', () => {
    // Round 11's live state: warn severity, but the trace showed dense retrieval + the cross-encoder
    // executing. Severity is why this projection used to claim a keyword fallback here.
    const a = projectAvailability(
      'documents',
      aiState({
        chat: true,
        docs: 5,
        degradedSeverity: 'warn',
        reasons: ['chunk_embedding.not_ready', 'lambdamart.not_configured'],
      }),
    );
    expect(a.kind).toBe('degraded');
    expect(a.kind === 'degraded' && a.caveat).toBe(PASSAGE_REDUCED_CAVEAT);
    expect(a.kind === 'degraded' && a.caveat.toLowerCase()).not.toContain('keyword');
  });

  it('805 §G.2: an AI-model cause → the calm caveat (retrieval is untouched)', () => {
    const a = projectAvailability(
      'documents',
      aiState({ chat: true, docs: 5, degradedSeverity: 'warn', reasons: ['inference.offline'] }),
    );
    expect(a.kind).toBe('degraded');
    // Round-14 finding 8 — this class gets its OWN wording: now that the cosmetic caveat names
    // learned re-ranking, borrowing it here would claim a feature this cause is not about.
    expect(a.kind === 'degraded' && a.caveat).toBe(AI_UNAVAILABLE_CAVEAT);
    expect(a.kind === 'degraded' && a.caveat).not.toContain('LambdaMART');
  });

  it('805 §G.2: an UNCLASSIFIED cause stays conservative (the keyword-fallback caveat)', () => {
    const a = projectAvailability(
      'documents',
      aiState({ chat: true, docs: 5, degradedSeverity: 'warn', reasons: ['some.future.code'] }),
    );
    expect(a.kind === 'degraded' && a.caveat).toBe(KEYWORD_FALLBACK_CAVEAT);
  });

  it('documents with an operational verdict → available (no caveat)', () => {
    expect(projectAvailability('documents', aiState({ chat: true, docs: 5 })).kind).toBe('available');
  });

  it('extract/agent only need chat (zero docs is irrelevant to them)', () => {
    expect(projectAvailability('extract', aiState({ chat: true, docs: 0 })).kind).toBe('available');
    expect(projectAvailability('agent', aiState({ chat: true, docs: 0 })).kind).toBe('available');
  });

  it('docs unknown (not yet reported) does not fabricate a zero-docs block', () => {
    // chat up, docs unknown → available (we do not assert "no documents" without data).
    expect(projectAvailability('documents', aiState({ chat: true, docs: 'unknown' })).kind).toBe(
      'available',
    );
  });

  /**
   * Tempdoc 807 A.3 (round-13 R13-F2) — a dead backend leaves `capabilities.chat` TRUE, because it is
   * computed off the retained inference snapshot. Every gate below it therefore reads "available" for
   * a control that would POST into the void. Liveness is checked first, and says so in the same words
   * the verdict and the "Backend disconnected." banner use.
   */
  it('807: a not-live snapshot blocks every affordance, in the shared vocabulary', () => {
    for (const affordance of ['documents', 'extract', 'agent'] as const) {
      const a = projectAvailability(affordance, aiState({ chat: true, docs: 5, snapshotLive: false }));
      expect(a.kind, affordance).toBe('unavailable');
      expect(isUnavailable(a) && a.reason).toBe(reasonFor('binding.unreachable').wording);
      // NOT transient (round-13 review): `transient` means "queue the intent and auto-fire it on the
      // next operable render" (Control.activate/resolveQueued). An unbounded outage must refuse the
      // click with its reason, not defer it into a command that fires unwatched on reconnect.
      expect(isUnavailable(a) && a.transient, affordance).toBe(false);
    }
  });

  it('807 ANTI-REGRESSION: the SAME state with a live snapshot is available (the gate is liveness, nothing else)', () => {
    expect(projectAvailability('documents', aiState({ chat: true, docs: 5 })).kind).toBe('available');
  });
});

describe('unavailableBecause — literal local-gap reason (tempdoc 596 §16.2 a11y-debt close)', () => {
  it('builds a settled unavailable carrying the verbatim reason, no remedy', () => {
    const a = unavailableBecause('No unread advisories');
    expect(a.kind).toBe('unavailable');
    expect(isUnavailable(a) && a.reason).toBe('No unread advisories');
    expect(isUnavailable(a) && a.transient).toBeFalsy();
    expect(isUnavailable(a) && a.remedy).toBeUndefined();
  });

  it('transient=true marks a self-clearing gap (an in-flight refresh)', () => {
    const a = unavailableBecause('Refreshing…', true);
    expect(isUnavailable(a) && a.transient).toBe(true);
  });
});
