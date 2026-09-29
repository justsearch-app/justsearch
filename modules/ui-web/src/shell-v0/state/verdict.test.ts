import { describe, it, expect } from 'vitest';
import {
  computeStability,
  computeVerdict,
  verdictBody,
  verdictHeadline,
  verdictTone,
  presentVerdict,
  type MigrationSource,
  type StabilityInput,
} from './verdict.js';
import { severityForCodes } from './readinessNotice.js';
import { known, UNKNOWN } from './known.js';
import type { ReadinessView } from './aiStateStore.js';

const settledInput: StabilityInput = {
  phase: 'connected',
  indexState: 'IDLE',
  migrationState: 'IDLE',
  activeGenerationId: 'g1',
  buildingGenerationId: '',
  servingSearchGenerationId: 'g1',
  servingIngestGenerationId: 'g1',
};

const readyReadiness: ReadinessView = {
  retrieval: 'ready',
  aiFeatures: 'ready',
  reasonCodes: [],
};

describe('computeStability (595 §4.1)', () => {
  it('is settled on a healthy idle system', () => {
    expect(computeStability(settledInput)).toEqual({ kind: 'settled' });
  });

  it('worker-restart dominates when the poll returns the UNAVAILABLE fallback (worker down)', () => {
    // Even with migration IDLE and phase connected — this is the §9.1 down window.
    expect(computeStability({ ...settledInput, indexState: 'UNAVAILABLE' })).toEqual({
      kind: 'provisional',
      cause: 'worker-restart',
    });
  });

  it('exact index recovery wins over the coarse UNAVAILABLE Worker fallback', () => {
    expect(
      computeStability({
        ...settledInput,
        indexComponentState: 'STARTING',
        indexComponentReasonCode: 'component.recovering',
        indexState: 'UNAVAILABLE',
      }),
    ).toEqual({ kind: 'provisional', cause: 'index-recovery' });
  });

  it('rebuilding when the worker is MIGRATING (building a new generation)', () => {
    expect(computeStability({ ...settledInput, migrationState: 'MIGRATING' })).toEqual({
      kind: 'provisional',
      cause: 'rebuilding',
    });
  });

  it('generation-switch when SWITCHING, or serving search≠ingest gen', () => {
    expect(computeStability({ ...settledInput, migrationState: 'SWITCHING' })).toEqual({
      kind: 'provisional',
      cause: 'generation-switch',
    });
    expect(
      computeStability({ ...settledInput, servingSearchGenerationId: 'g1', servingIngestGenerationId: 'g2' }),
    ).toEqual({ kind: 'provisional', cause: 'generation-switch' });
  });

  it('rebuilding when a building generation differs from the active one', () => {
    expect(
      computeStability({ ...settledInput, buildingGenerationId: 'g2', activeGenerationId: 'g1' }),
    ).toEqual({ kind: 'provisional', cause: 'rebuilding' });
  });

  it('generalizes ConnectionPhase: connecting→initial-load, stale(no contact)→channel-stale', () => {
    expect(computeStability({ ...settledInput, phase: 'connecting' })).toEqual({
      kind: 'provisional',
      cause: 'initial-load',
    });
    // Review 2026-08 (FE review-fix bundle, item 2) — RE-ENCODED. This used to omit
    // `reachableViaContact` entirely and pin `undefined ⇒ channel-stale`, which is what made the two
    // consumption sites disagree about the same value (`=== false` at the lost-contact guard, truthy
    // here). Lost contact is now a claim only an explicit `false` can license, so the assertion states
    // the contact fact it depends on instead of leaning on the UNKNOWN case.
    expect(computeStability({ ...settledInput, phase: 'stale', reachableViaContact: false })).toEqual({
      kind: 'provisional',
      cause: 'channel-stale',
    });
  });

  it('630: catchingUp ⇒ catching-up, but a real rebuild/worker-down still dominates', () => {
    expect(computeStability({ ...settledInput, catchingUp: true })).toEqual({
      kind: 'provisional',
      cause: 'catching-up',
    });
    // Dominated by a higher-severity flux.
    expect(
      computeStability({ ...settledInput, catchingUp: true, migrationState: 'MIGRATING' }),
    ).toEqual({ kind: 'provisional', cause: 'rebuilding' });
    expect(
      computeStability({ ...settledInput, catchingUp: true, indexState: 'UNAVAILABLE' }),
    ).toEqual({ kind: 'provisional', cause: 'worker-restart' });
    // More informative than a plain stale connection.
    expect(computeStability({ ...settledInput, catchingUp: true, phase: 'stale' })).toEqual({
      kind: 'provisional',
      cause: 'catching-up',
    });
  });

  it('649: stale poll BUT reachable via another channel ⇒ updating (not channel-stale)', () => {
    expect(
      computeStability({ ...settledInput, phase: 'stale', reachableViaContact: true }),
    ).toEqual({ kind: 'provisional', cause: 'updating' });
    // No contact ⇒ the genuine lost-channel case stays channel-stale.
    expect(
      computeStability({ ...settledInput, phase: 'stale', reachableViaContact: false }),
    ).toEqual({ kind: 'provisional', cause: 'channel-stale' });
    // A real higher-severity flux still dominates even when reachable.
    expect(
      computeStability({
        ...settledInput,
        phase: 'stale',
        reachableViaContact: true,
        indexState: 'UNAVAILABLE',
      }),
    ).toEqual({ kind: 'provisional', cause: 'worker-restart' });
  });
});

/**
 * Review 2026-08 (FE review-fix bundle, item 2) — `reachableViaContact` is OPTIONAL, so it has three
 * states, and the two consumption sites used to read the third one oppositely: the lost-contact guard
 * tests `=== false` while the stale-phase branch tested it truthily, so `undefined` (an older snapshot
 * shape, or the field not populated yet) was UNKNOWN at one site and "contact lost" at the other.
 * The unified rule: UNKNOWN never licenses a positive claim about contact IN EITHER DIRECTION — the
 * phase's own default stands. Asserting contact LOSS needs an explicit `false` (computeStability);
 * asserting contact ALIVE needs an explicit `true` (computeVerdict's disconnected arm).
 */
describe('reachableViaContact is three-state: true / false / undefined(UNKNOWN)', () => {
  it('computeStability(stale): true ⇒ updating, false ⇒ channel-stale, undefined ⇒ updating', () => {
    const at = (reachableViaContact?: boolean) =>
      computeStability({ ...settledInput, phase: 'stale', reachableViaContact });
    expect(at(true)).toEqual({ kind: 'provisional', cause: 'updating' });
    expect(at(false)).toEqual({ kind: 'provisional', cause: 'channel-stale' });
    // UNKNOWN is not evidence of a lost channel — the poll data is merely behind.
    expect(at(undefined)).toEqual({ kind: 'provisional', cause: 'updating' });
  });

  it('computeStability: UNKNOWN does not fire the lost-contact override either — retained causes stand', () => {
    // The override at the top of computeStability must not steal a retained cause on UNKNOWN: with no
    // explicit `false` there is nothing to say contact was lost, so `UNAVAILABLE` still reads
    // worker-restart (this is what the `=== false` guard already did — pinned so it stays symmetric).
    expect(
      computeStability({ ...settledInput, phase: 'stale', indexState: 'UNAVAILABLE' }),
    ).toEqual({ kind: 'provisional', cause: 'worker-restart' });
    expect(
      computeStability({
        ...settledInput,
        phase: 'stale',
        reachableViaContact: false,
        indexState: 'UNAVAILABLE',
      }),
    ).toEqual({ kind: 'provisional', cause: 'channel-stale' });
  });

  it('computeVerdict(disconnected): true ⇒ connecting; false and undefined ⇒ unreachable', () => {
    const at = (reachableViaContact?: boolean) =>
      computeVerdict({
        phase: 'disconnected',
        stability: { kind: 'settled' },
        readiness: UNKNOWN,
        reachableViaContact,
      }).kind;
    expect(at(true)).toBe('connecting');
    expect(at(false)).toBe('unreachable');
    // The SAME rule, pointing the other way: downgrading the unreachable alarm to a calm
    // "Connecting…" is a positive claim that contact is alive, so UNKNOWN must not license it.
    expect(at(undefined)).toBe('unreachable');
  });
});

// Tempdoc 807 §E.4 — the residual that document recorded and deliberately deferred: the VERDICT
// itself was still pinned by retained snapshot fields, so after contact was lost the status LINE
// projected a retained cause present-tense (probed: retained `UNAVAILABLE` + 41 s aged contact ⇒
// `worker-restart` / "Restarting…") while every liveness signal correctly read stale. These
// assertions SUPERSEDE the pre-807-§E.4 precedence (a retained cause winning over lost contact);
// the live-contact cases above are unchanged and pin that this is a lost-contact rule, not a
// retained-cause deletion.
describe('807 §E.4: lost contact dominates every retained-snapshot cause', () => {
  it('retained UNAVAILABLE + no contact ⇒ channel-stale, not the retained "Restarting…" cause', () => {
    expect(
      computeStability({
        ...settledInput,
        phase: 'stale',
        reachableViaContact: false,
        indexState: 'UNAVAILABLE',
      }),
    ).toEqual({ kind: 'provisional', cause: 'channel-stale' });
  });

  it('all seven retained-cause branches yield channel-stale once contact is lost', () => {
    const lost = { phase: 'stale' as const, reachableViaContact: false };
    const retained: ReadonlyArray<Partial<StabilityInput>> = [
      { indexState: 'UNAVAILABLE' },
      {
        indexComponentState: 'STARTING',
        indexComponentReasonCode: 'component.recovering',
      },
      { migrationState: 'SWITCHING' },
      { migrationState: 'MIGRATING' },
      { buildingGenerationId: 'g2', activeGenerationId: 'g1' },
      { servingSearchGenerationId: 'g1', servingIngestGenerationId: 'g2' },
      { catchingUp: true },
    ];
    for (const r of retained) {
      expect(computeStability({ ...settledInput, ...lost, ...r }), JSON.stringify(r)).toEqual({
        kind: 'provisional',
        cause: 'channel-stale',
      });
    }
  });

  it('live contact leaves every retained cause exactly as it was (this is a contact rule)', () => {
    expect(
      computeStability({ ...settledInput, reachableViaContact: true, migrationState: 'MIGRATING' }),
    ).toEqual({ kind: 'provisional', cause: 'rebuilding' });
    expect(computeStability({ ...settledInput, reachableViaContact: true, catchingUp: true })).toEqual({
      kind: 'provisional',
      cause: 'catching-up',
    });
    expect(computeStability({ ...settledInput, reachableViaContact: true })).toEqual({ kind: 'settled' });
  });

  it('the boot grace is untouched: connecting + no contact yet stays initial-load', () => {
    // Scoped to the POLL-STALE phase deliberately: `connecting` is the first-poll window (no poll has
    // ever succeeded), so there is no retained snapshot for a cause to be projected FROM, and
    // `disconnected` is already dominated by `computeVerdict`'s own `unreachable` arm.
    expect(
      computeStability({ ...settledInput, phase: 'connecting', reachableViaContact: false }),
    ).toEqual({ kind: 'provisional', cause: 'initial-load' });
  });

  it('the lost-contact verdict words itself "Reconnecting…", never a retained cause', () => {
    const stability = computeStability({
      ...settledInput,
      phase: 'stale',
      reachableViaContact: false,
      indexState: 'UNAVAILABLE',
    });
    const v = computeVerdict({
      phase: 'stale',
      stability,
      readiness: known(readyReadiness),
      reachableViaContact: false,
    });
    expect(v).toEqual({ kind: 'transitioning', severity: 'warn', reasons: ['channel-stale'] });
    expect(verdictHeadline(v)).toBe('Reconnecting…');
  });
});

describe('649: connection truthfulness under load', () => {
  it('updating verdict is a calm transition headed "Catching up…"', () => {
    const v = computeVerdict({
      phase: 'stale',
      stability: { kind: 'provisional', cause: 'updating' },
      readiness: known(readyReadiness),
      reachableViaContact: true,
    });
    expect(v.kind).toBe('transitioning');
    expect(v.severity).toBe('busy');
    expect(verdictTone(v.severity)).toBe('info'); // calm, NOT the "Reconnecting…" alarm
    expect(verdictHeadline(v)).toBe('Catching up…');
  });

  it('disconnected phase but reachable via contact ⇒ Connecting…, not a false unreachable', () => {
    const v = computeVerdict({
      phase: 'disconnected',
      stability: { kind: 'settled' },
      readiness: UNKNOWN,
      reachableViaContact: true,
    });
    expect(v.kind).toBe('connecting');
    expect(verdictHeadline(v)).toBe('Connecting…');
  });

  it('disconnected with NO contact still escalates to unreachable (no false calm)', () => {
    const v = computeVerdict({
      phase: 'disconnected',
      stability: { kind: 'settled' },
      readiness: UNKNOWN,
      reachableViaContact: false,
    });
    expect(v.kind).toBe('unreachable');
    expect(verdictHeadline(v)).toBe('Backend disconnected');
  });
});

describe('630: catching-up verdict is a calm transition', () => {
  it('renders as a busy/info transition headed "Catching up…"', () => {
    const v = computeVerdict({
      phase: 'connected',
      stability: { kind: 'provisional', cause: 'catching-up' },
      readiness: known(readyReadiness),
    });
    expect(v.kind).toBe('transitioning');
    expect(v.severity).toBe('busy');
    expect(verdictTone(v.severity)).toBe('info'); // calm, not an alarm
    expect(verdictHeadline(v)).toBe('Catching up…');
  });
});

describe('computeVerdict (595 §4.2) — the ONE rollup', () => {
  const settled = { kind: 'settled' } as const;

  it('THE 1.1 BOUNDARY: settled + retrieval unknown ⇒ ONE verdict (checking), never split', () => {
    const v = computeVerdict({
      phase: 'connected',
      stability: settled,
      readiness: known({ ...readyReadiness, retrieval: 'unknown' }),
    });
    // Not 'operational' (the footer's old fall-through) and not 'degraded'
    // (the header's old over-claim) — a single non-green, non-alarming verdict.
    expect(v.kind).toBe('checking');
    expect(v.severity).toBe('info');
  });

  it('disconnected ⇒ unreachable/error with the binding.unreachable reason (637 #1)', () => {
    const v = computeVerdict({ phase: 'disconnected', stability: settled, readiness: UNKNOWN });
    expect(v.kind).toBe('unreachable');
    expect(v.severity).toBe('error');
    // 637 #1: the reason code makes the unreachable state word itself loudly (CAUSE_ROWS),
    // never a silent empty result one layer up.
    expect(v.reasons).toEqual(['binding.unreachable']);
  });

  it('provisional dominates: initial-load⇒connecting, others⇒transitioning(busy)', () => {
    expect(
      computeVerdict({ phase: 'connecting', stability: { kind: 'provisional', cause: 'initial-load' }, readiness: UNKNOWN }).kind,
    ).toBe('connecting');
    const t = computeVerdict({
      phase: 'connected',
      stability: { kind: 'provisional', cause: 'rebuilding' },
      readiness: known(readyReadiness), // healthy readiness must NOT override the transition
    });
    expect(t.kind).toBe('transitioning');
    expect(t.severity).toBe('busy');
    expect(t.reasons).toEqual(['rebuilding']);
  });

  it('settled + ready ⇒ operational/ok', () => {
    const v = computeVerdict({ phase: 'connected', stability: settled, readiness: known(readyReadiness) });
    expect(v.kind).toBe('operational');
    expect(v.severity).toBe('ok');
  });

  it('§10.3: a cosmetic degradation (LambdaMART) is degraded/INFO, not an alarm', () => {
    const v = computeVerdict({
      phase: 'connected',
      stability: settled,
      readiness: known({ ...readyReadiness, retrieval: 'degraded', reasonCodes: ['lambdamart.not_configured'] }),
    });
    expect(v.kind).toBe('degraded');
    expect(v.severity).toBe('info'); // calm — search still works
  });

  it('an impairing degradation (embedding not ready) is degraded/WARN', () => {
    const v = computeVerdict({
      phase: 'connected',
      stability: settled,
      readiness: known({ ...readyReadiness, retrieval: 'degraded', reasonCodes: ['worker.health.embedding_not_ready'] }),
    });
    expect(v.severity).toBe('warn');
  });

  it('optional AI recovery cannot promote an unrelated retrieval degradation to transitioning', () => {
    const v = computeVerdict({
      phase: 'connected',
      stability: settled,
      readiness: known({
        ...readyReadiness,
        retrieval: 'degraded',
        reasonCodes: ['lambdamart.not_configured', 'component.recovering'],
      }),
    });
    expect(v.kind).toBe('degraded');
    expect(v.severity).toBe('info');
  });

  it('a real spawn failure stays degraded/error', () => {
    const v = computeVerdict({
      phase: 'connected',
      stability: settled,
      readiness: known({ ...readyReadiness, retrieval: 'degraded', reasonCodes: ['worker.spawn.failed'] }),
    });
    expect(v.kind).toBe('degraded');
    expect(v.severity).toBe('error');
  });

  it('exact index recovery has recovery-specific calm wording', () => {
    const v = computeVerdict({
      phase: 'connected',
      stability: { kind: 'provisional', cause: 'index-recovery' },
      readiness: known(readyReadiness),
    });
    expect(v.kind).toBe('transitioning');
    expect(v.severity).toBe('busy');
    expect(verdictHeadline(v)).toBe('Recovering search…');
    expect(verdictBody(v)).toContain('search index is recovering');
  });

  it('compat-blocked index ⇒ degraded carrying the specific reindex cause code (600 Design A)', () => {
    const v = computeVerdict({
      phase: 'connected',
      stability: settled,
      readiness: known({
        ...readyReadiness,
        retrieval: 'degraded',
        reasonCodes: ['index.blocked_legacy'],
      }),
    });
    expect(v.kind).toBe('degraded');
    // The actionable cause is a real reason code (not a synthetic boolean-derived token).
    expect(v.reasons).toContain('index.blocked_legacy');
    expect(verdictHeadline(v)).toBe('Reindex required');
  });
});

describe('D1-14 — semantic pause during an in-place build', () => {
  const rebuilding = { kind: 'provisional', cause: 'rebuilding', source: 'embedding_model_change' } as const;
  const denseOff = known<ReadinessView>({
    ...readyReadiness,
    retrieval: 'degraded',
    reasonCodes: ['index.embedding_rebuilding'],
  });

  it('carries the measured dense outage through a current rebuild verdict', () => {
    const verdict = computeVerdict({ phase: 'connected', stability: rebuilding, readiness: denseOff });
    expect(verdict).toEqual({
      kind: 'transitioning',
      severity: 'warn',
      reasons: ['rebuilding', 'source:embedding_model_change', 'index.embedding_rebuilding'],
    });
    expect(verdictBody(verdict)).toContain('keyword search remains available');
  });

  it('does not infer a dense outage from model-change source alone or stale data', () => {
    const beside = computeVerdict({ phase: 'connected', stability: rebuilding, readiness: known(readyReadiness) });
    expect(beside.reasons).not.toContain('index.embedding_rebuilding');
    expect(verdictBody(beside)).not.toContain('resumes');
    const stale = computeVerdict({ phase: 'stale', stability: rebuilding, readiness: denseOff });
    expect(stale.reasons).not.toContain('index.embedding_rebuilding');
    const restarting = computeVerdict({ phase: 'connected', stability: { kind: 'provisional', cause: 'worker-restart' }, readiness: denseOff });
    expect(restarting.reasons).not.toContain('index.embedding_rebuilding');
  });

  it('keeps actionable paused and overdue wording ahead of the dense-pause detail', () => {
    const paused = computeVerdict({
      phase: 'connected', stability: rebuilding, readiness: denseOff, migrationPaused: true,
    });
    expect(paused.reasons).toContain('index.embedding_rebuilding');
    expect(verdictBody(paused)).toContain('open Health to resume or investigate');

    const overdue = computeVerdict({
      phase: 'connected', stability: rebuilding, readiness: denseOff,
      migrationSwitchingAgeMs: 10_000, migrationSwitchingMaxDurationMs: 5_000,
    });
    expect(overdue.reasons).toContain('index.embedding_rebuilding');
    expect(verdictBody(overdue)).toContain('taking longer than expected');
  });
});

describe('severityForCodes (595 §10.5)', () => {
  it('maps the cosmetic codes to info and hard failures to error', () => {
    expect(severityForCodes(['lambdamart.not_configured'])).toBe('info');
    expect(severityForCodes(['worker.spawn.failed'])).toBe('error');
  });
  it('defaults an unknown or empty code set to warn (never silently info)', () => {
    expect(severityForCodes(['some.future.code'])).toBe('warn');
    expect(severityForCodes([])).toBe('warn');
  });
  it('takes the worst-of across mixed codes', () => {
    expect(severityForCodes(['lambdamart.not_configured', 'worker.health.embedding_not_ready'])).toBe('warn');
  });
});

describe('verdictTone (595 §10.5)', () => {
  it('projects severity to a calm-vs-alarm tone', () => {
    expect(verdictTone('ok')).toBe('success');
    expect(verdictTone('info')).toBe('info');
    expect(verdictTone('busy')).toBe('info');
    expect(verdictTone('warn')).toBe('warning');
    expect(verdictTone('error')).toBe('error');
  });
});

describe('verdictHeadline', () => {
  it('words each kind, distinguishing cosmetic degraded from a hard one', () => {
    expect(verdictHeadline({ kind: 'operational', severity: 'ok', reasons: [] })).toContain('operational');
    expect(verdictHeadline({ kind: 'checking', severity: 'info', reasons: [] })).toBe('Checking…');
    expect(verdictHeadline({ kind: 'transitioning', severity: 'busy', reasons: ['rebuilding'] })).toBe('Rebuilding…');
    expect(verdictHeadline({ kind: 'degraded', severity: 'info', reasons: ['lambdamart.not_configured'] })).toBe('Reduced capability');
    expect(verdictHeadline({ kind: 'degraded', severity: 'warn', reasons: ['x'] })).toBe('Service degraded');
  });
});

/**
 * Tempdoc 837 §2.3 — the rebuild's SOURCE, carried as an additive facet of the transition after the
 * reason-code authority for it (`index.rebuilding`) was retired as unreachable.
 *
 * <p>The two regression tests at the end are the point: a flat `rebuilding-corrupt`-style cause token
 * would have compiled clean and silently disabled the stuck-rebuild escalation and the progress bar,
 * both of which narrow on the cause by EQUALITY.
 */
describe('837 §2.3 — the migration-source facet on a rebuild transition', () => {
  const rebuildInput = (migrationSource: string | null | undefined): StabilityInput => ({
    ...settledInput,
    migrationState: 'MIGRATING',
    migrationSource,
  });

  it('the cause is UNCHANGED — the source rides beside it, never inside it', () => {
    const s = computeStability(rebuildInput('corrupt_index_rebuild'));
    expect(s).toEqual({
      kind: 'provisional',
      cause: 'rebuilding',
      source: 'corrupt_index_rebuild',
    });
  });

  it('an absent source is absent, not `unknown` (nothing rebuilding ≠ rebuilding for reasons unknown)', () => {
    const blank = computeStability(rebuildInput(''));
    expect(blank.kind === 'provisional' ? blank.source : 'not-provisional').toBeUndefined();
    const undef = computeStability(rebuildInput(undefined));
    expect(undef.kind === 'provisional' ? undef.source : 'not-provisional').toBeUndefined();
  });

  it('a label outside the vocabulary narrows to `unknown` rather than being trusted', () => {
    const s = computeStability(rebuildInput('system_test'));
    expect(s.kind === 'provisional' && s.source).toBe('unknown');
  });

  it('computeVerdict carries it as an extra token, with reasons[0] still the cause', () => {
    const v = computeVerdict({
      phase: 'connected',
      stability: { kind: 'provisional', cause: 'rebuilding', source: 'corrupt_index_rebuild' },
      readiness: UNKNOWN,
    });
    expect(v.reasons[0]).toBe('rebuilding');
    expect(v.reasons).toContain('source:corrupt_index_rebuild');
    expect(v.kind).toBe('transitioning');
  });

  it('BOTH wording surfaces read the facet — headline and body cannot disagree (§D.3)', () => {
    const withSource = (source: MigrationSource) =>
      computeVerdict({
        phase: 'connected',
        stability: { kind: 'provisional', cause: 'rebuilding', source },
        readiness: UNKNOWN,
      });

    const corrupt = withSource('corrupt_index_rebuild');
    expect(verdictHeadline(corrupt)).toBe('Repairing index…');
    expect(verdictBody(corrupt)).toBe(
      // Word-for-word the wording the retired index.rebuilding CAUSE_ROWS row carried.
      'The index was corrupted and is being rebuilt from your files — results are temporarily incomplete.',
    );

    const embedding = withSource('embedding_model_change');
    expect(verdictHeadline(embedding)).toBe('Rebuilding for a new AI model…');
    expect(verdictBody(embedding)).toContain('embedding model changed');

    const schema = withSource('schema_mismatch');
    expect(verdictHeadline(schema)).toBe('Rebuilding for a new index format…');
    expect(verdictBody(schema)).toContain('index format changed');
  });

  it('a user-requested or unknown source keeps the plain headline and names only what it knows', () => {
    const user = computeVerdict({
      phase: 'connected',
      stability: { kind: 'provisional', cause: 'rebuilding', source: 'user_requested_rebuild' },
      readiness: UNKNOWN,
    });
    expect(verdictHeadline(user)).toBe('Rebuilding…');
    expect(verdictBody(user)).toContain('rebuild you requested');

    const unknown = computeVerdict({
      phase: 'connected',
      stability: { kind: 'provisional', cause: 'rebuilding', source: 'unknown' },
      readiness: UNKNOWN,
    });
    expect(verdictHeadline(unknown)).toBe('Rebuilding…');
    expect(verdictBody(unknown)).toBe(
      'The index is being rebuilt; document counts and results will settle when it finishes.',
    );
  });

  it('REGRESSION: the stuck-rebuild escalation still fires with a source present (verdict.ts:213)', () => {
    const paused = computeVerdict({
      phase: 'connected',
      stability: { kind: 'provisional', cause: 'rebuilding', source: 'corrupt_index_rebuild' },
      readiness: UNKNOWN,
      migrationPaused: true,
    });
    expect(paused.severity).toBe('warn');
    expect(paused.reasons).toEqual(['rebuilding', 'paused', 'source:corrupt_index_rebuild']);
    expect(verdictHeadline(paused)).toBe('Rebuild paused');

    const overdue = computeVerdict({
      phase: 'connected',
      stability: { kind: 'provisional', cause: 'generation-switch', source: 'schema_mismatch' },
      readiness: UNKNOWN,
      migrationSwitchingAgeMs: 10_000,
      migrationSwitchingMaxDurationMs: 5_000,
    });
    expect(overdue.severity).toBe('warn');
    expect(overdue.reasons).toEqual(['generation-switch', 'overdue', 'source:schema_mismatch']);
  });

  it('REGRESSION: the progress-bar gate still narrows (HealthSurface.ts:815-818 reads cause only)', () => {
    // The surface asks exactly this question; a renamed cause would blank the bar with tsc green.
    const s = computeStability(rebuildInput('corrupt_index_rebuild'));
    expect(s.kind === 'provisional' && (s.cause === 'rebuilding' || s.cause === 'generation-switch'))
      .toBe(true);
  });
});

/**
 * Tempdoc 837 §1.5 (UR-4) — `verdictBody`'s degraded arm answered off `v.severity` alone, so a
 * `warn` AI-only cause made the Health footer say "Retrieval is degraded" while the banner, from the
 * SAME verdict, correctly said search was fully working and only chat was off. Two surfaces, one
 * verdict, opposite claims.
 */
describe('verdictBody — AI-only degradations do not claim retrieval is degraded (837 UR-4)', () => {
  it('a crashed AI model scopes the sentence to chat, not retrieval', () => {
    const body = verdictBody({ kind: 'degraded', severity: 'warn', reasons: ['inference.crashed'] });
    expect(body).toBe('Chat and answer features are unavailable; search itself is unaffected.');
    expect(body).not.toContain('Retrieval is degraded');
  });

  it('a genuinely retrieval-impairing cause still says retrieval is degraded', () => {
    expect(
      verdictBody({ kind: 'degraded', severity: 'warn', reasons: ['index.dense_unavailable'] }),
    ).toBe('Retrieval is degraded. See recent events for detail.');
  });

  it('an unclassifiable cause keeps the conservative sentence (never the calmer AI claim)', () => {
    expect(
      verdictBody({ kind: 'degraded', severity: 'warn', reasons: ['something.unrecognized'] }),
    ).toBe('Retrieval is degraded. See recent events for detail.');
  });

  it('the reindex and cosmetic arms are untouched', () => {
    expect(
      verdictBody({ kind: 'degraded', severity: 'warn', reasons: ['index.embedding_mismatch'] }),
    ).toBe('A reindex is required to restore full search quality.');
    expect(
      verdictBody({ kind: 'degraded', severity: 'info', reasons: ['lambdamart.not_configured'] }),
    ).toBe('An optional capability is unavailable; search still works.');
  });
});

describe('computeVerdict — stuck-transition escalation (595 §15.2 / E4)', () => {
  const provisional = (cause: 'rebuilding' | 'generation-switch') =>
    ({ phase: 'connected', stability: { kind: 'provisional', cause }, readiness: UNKNOWN } as const);

  it('within the switch budget, a rebuild stays calm (busy)', () => {
    const v = computeVerdict({ ...provisional('rebuilding'), migrationSwitchingAgeMs: 1000, migrationSwitchingMaxDurationMs: 60000 });
    expect(v.severity).toBe('busy');
    expect(v.reasons).toEqual(['rebuilding']);
  });

  it('a PAUSED migration escalates to warn + "paused"', () => {
    const v = computeVerdict({ ...provisional('rebuilding'), migrationPaused: true });
    expect(v.severity).toBe('warn');
    expect(v.reasons).toEqual(['rebuilding', 'paused']);
    expect(verdictHeadline(v)).toBe('Rebuild paused');
  });

  it('an OVERDUE migration (age > max) escalates to warn + "overdue"', () => {
    const v = computeVerdict({ ...provisional('generation-switch'), migrationSwitchingAgeMs: 90000, migrationSwitchingMaxDurationMs: 60000 });
    expect(v.severity).toBe('warn');
    expect(v.reasons).toEqual(['generation-switch', 'overdue']);
    expect(verdictHeadline(v)).toBe('Rebuilding… (taking longer than expected)');
  });

  it('migration escalation flags do not attach to channel-stale (it has its own 649 severity)', () => {
    const v = computeVerdict({
      phase: 'connected',
      stability: { kind: 'provisional', cause: 'channel-stale' },
      readiness: UNKNOWN,
      migrationPaused: true, // irrelevant to a connection-stale transition
    });
    // 649: channel-stale (lost contact, "Reconnecting…") is its OWN warning — distinct from the calm
    // `updating`/`busy` catch-up. The `reasons` prove the migration `paused` flag did NOT attach (no
    // 'paused' reason), so the E4 escalation path is still scoped to rebuild/switch only.
    expect(v.severity).toBe('warn');
    expect(v.reasons).toEqual(['channel-stale']);
  });
});

describe('presentVerdict (595 §15.1) — the ONE verdict-presentation projection', () => {
  it('bundles tone + headline + body for a cosmetic degradation (calm)', () => {
    const p = presentVerdict({ kind: 'degraded', severity: 'info', reasons: ['lambdamart.not_configured'] });
    expect(p.tone).toBe('info');
    expect(p.headline).toBe('Reduced capability');
    expect(p.body).toContain('search still works');
  });

  it('announces politely for non-error verdicts, assertively for an error verdict', () => {
    expect(presentVerdict({ kind: 'operational', severity: 'ok', reasons: [] }).announce).toEqual({
      text: 'All systems operational',
      politeness: 'status',
    });
    expect(presentVerdict({ kind: 'unreachable', severity: 'error', reasons: [] }).announce.politeness).toBe('alert');
  });

  it('the announce text is the concise headline (WCAG 4.1.3 brevity)', () => {
    const p = presentVerdict({ kind: 'transitioning', severity: 'busy', reasons: ['rebuilding'] });
    expect(p.announce.text).toBe('Rebuilding…');
    expect(p.announce.text).toBe(p.headline);
  });
});
