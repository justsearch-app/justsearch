"""Eval-time cross-encoder-coverage validity guard (register F-052).

The hole this closes: ``justsearch.rerank.deadline_ms`` defaults to 200 ms, and when the rerank
RPC reports a skip ``KnowledgeSearchEngine`` keeps the fusion order and carries on
(``KnowledgeSearchEngine.java:1078-1083`` — ``crossEncoderSkipReason`` from the worker's
``skip_reason``; register F-054 split that reason into ``DEADLINE_EXCEEDED`` for a budget
pre-check and ``INFERENCE_FAILED`` for an ORT failure, both silent drops here).
NOTHING a gate reads changes: ``comparable`` stays ``true`` with empty
``comparability_reasons``, ``ann_proof`` stays ``PASS``, ``error_count`` stays ``0``, and
``cross_encoder`` stays in ``pipeline_tracking.observed`` because *some* queries did rerank. The
run is a silent blend of two pipelines. A measured pair (2026-08-19, ``mixed/legal-clerc-200``)
had 102/200 queries deadline-dropped and scored nDCG@10 0.6255 against a clean 0.5788 — a 0.047
swing, more than twice the relevance ratchet's 0.02 default tolerance, reported as fully
comparable.

The signal that DOES move is per-query: whether the delivered hits carry a cross-encoder score.
This module is the pure classifier over that signal; :mod:`jseval.run` embeds its verdict in
every run's ``summary.json`` and :func:`jseval.ratchet_kernel.assert_ce_coverage` enforces it at
the gate seam — the tempdoc 644/718 idiom (an advisory embedded block AND a fail-closed gate
assertion), mirroring :mod:`jseval.chunk_completeness`.

Distinguishing a legitimate skip from a silent drop is the whole job: the engine skips the CE
deterministically for several query shapes, and those skips are reproducible across arms, so they
do not contaminate an A/B. A deadline drop is load-dependent and irreproducible, so it does. The
discriminator is the reason the engine itself recorded on the ``cross-encoder`` trace stage
(``SearchTraceMapper.buildHeadStages`` case ``CROSS_ENCODER``, wire id ``cross-encoder``,
``status`` ∈ {``executed``, ``skipped``}, ``reason`` = the raw skip-reason string).
"""

from __future__ import annotations

from dataclasses import dataclass, field

from . import provenance

# The unified-trace stage id the head emits for the cross-encoder (SearchTraceMapper.HeadStage
# CROSS_ENCODER). Same wire id provenance._COMPONENT_TO_STAGE maps "cross_encoder" to.
CE_STAGE_WIRE_ID = "cross-encoder"

# Deterministic, query-shape-determined skips (KnowledgeSearchEngine.java:948-956, 978-979). Each
# is a function of the query + the candidate set, so the same query skips the same way on every
# arm of a comparison: legitimate, not contamination.
DETERMINISTIC_SKIP_REASONS = frozenset({
    "NAVIGATIONAL_QUERY",    # :949 — queryType == NAVIGATIONAL
    "BELOW_MIN_THRESHOLD",   # :953 — results.size() < rerankConfig.minHitsThreshold()
    "DOCS_TOO_LONG",         # :956 — avgContentLengthChars > maxAvgDocLengthChars
    "FUSION_CONFIDENT",      # :979 — tempdoc 643 perf-skip, decided from leg agreement alone
})

# The CE was never in play for this query — a configuration state, not a skip decision. These do
# not count as CE-eligible at all: a run where EVERY query reports one of them is a CE-off run
# (`not-applicable`), and a lexical-only run must never be struck for not reranking.
CE_NOT_IN_PLAY_REASONS = frozenset({
    "DISABLED",               # KnowledgeSearchEngine.java:951 — rerankConfig.enabled() == false
    "PIPELINE_NOT_ELIGIBLE",  # :958 — pipeline.crossEncoderEnabled() == false for this preset
    "MODEL_NOT_CONFIGURED",   # :962 — head-side: no model configured
    "MODEL_NOT_LOADED",       # WorkerSearchService.java:470 — worker-side: reranker absent
})

# Load-dependent, irreproducible losses of the CE — the contamination class. Listed for legibility;
# classification is fail-closed, so ANY reason outside the two sets above counts as a silent drop
# whether or not it appears here.
NONDETERMINISTIC_SKIP_REASONS = frozenset({
    "DEADLINE_EXCEEDED",  # WorkerSearchService.wireSkipReason — a reranker budget PRE-CHECK only
    "INFERENCE_FAILED",   # register F-054 — inference ran and ORT threw (arena exhaustion, dead
                          # session). Split out of DEADLINE_EXCEEDED, which used to be stamped on
                          # every reranker skip: a measured campaign's 199/200 "deadline misses"
                          # were BFCArena OOM, fixed by JUSTSEARCH_RERANK_GPU_MEM_MB and by no
                          # deadline value. Listed explicitly so it is classified deliberately
                          # rather than by the fail-closed fallback.
    "RPC_FAILED",         # KnowledgeSearchEngine.java:1002
    "UNKNOWN",            # the head's normalisation of an unstated/unrecognised worker reason
})

# Placeholder reason for a CE-less query the engine recorded no reason for at all. Not an engine
# string — this module's own name for "unexplained", so the block's reason counts stay legible.
NO_REASON_RECORDED = "NO_REASON_RECORDED"

# A silent drop is irreproducible and uncorrected by a re-run, so its share of the CE-eligible
# queries is a direct fusion-order contamination of the run's mean. The measured campaign puts the
# induced bias near a tenth of a point of nDCG@10 per ten points of drop rate (0.047 nDCG at a
# 51% drop rate); 2% therefore bounds the induced bias at roughly 0.002 nDCG — an order of
# magnitude under the relevance ratchet's 0.02 default tolerance
# (`commands/gates.py` tolerance_default_abs), so a passing run cannot have been shifted by more
# than gate noise. It is deliberately NOT zero: one dropped query in a 50-query smoke run is a
# transient, not a regression.
DEFAULT_SILENT_DROP_TOLERANCE = 0.02


@dataclass(frozen=True)
class CeQueryState:
    """One query's cross-encoder outcome, normalized away from the artifact/response shape.

    ``applied`` is delivered-evidence-based: at least one delivered hit carries a non-null
    ``ce_score``. ``reason`` is the engine's own recorded skip reason (``None`` when the query
    reranked, or when nothing was recorded). The two ``*_channel`` flags say whether this record
    carries the evidence AT ALL — ``signals_channel`` for tempdoc 643's ``judgeSignals``,
    ``reason_channel`` for the CE-stage status/reason. Either one absent makes the run
    unevaluable rather than clean (see :func:`ce_coverage_verdict`).
    """

    applied: bool
    reason: str | None
    reason_channel: bool
    signals_channel: bool = True


def ce_stage_of(response: dict) -> dict:
    """The unified trace's ``cross-encoder`` stage node from a raw search response, or ``{}``.

    ``SearchTraceMapper.buildHeadStages`` iterates a closed enum with an exhaustive ``switch``, so
    the node is present on every traced response — ``{}`` here means no trace at all (an errored
    or pre-549 response), never "the CE stage was omitted".
    """
    trace = response.get("searchTrace") or {}
    if not isinstance(trace, dict):
        return {}
    for stage in trace.get("stages") or []:
        if isinstance(stage, dict) and stage.get("id") == CE_STAGE_WIRE_ID:
            return stage
    return {}


def state_from_response(response: dict) -> CeQueryState:
    """Normalize a RAW search response (the shape ``retriever.retrieve`` returns).

    Used by :mod:`jseval.run` at summary-build time. ``applied`` reuses
    :func:`jseval.provenance.extract_judge_signals` rather than re-reading the per-hit trace, so
    this and the artifact writer read the CE score through one function.
    """
    applied = any(
        provenance.extract_judge_signals(h).get("ce_score") is not None
        for h in (response.get("results") or [])
        if isinstance(h, dict)
    )
    stage = ce_stage_of(response)
    return CeQueryState(
        applied=applied,
        reason=stage.get("reason"),
        reason_channel=bool(stage),
    )


def state_from_per_query_record(record: dict) -> CeQueryState:
    """Normalize a ``{mode}_per_query.json`` entry (the persisted artifact shape).

    The post-hoc reader: lets a gate, a campaign script, or a test judge an archived run without
    the live backend. ``reason_channel`` keys off the recorded STATUS having a value, not the key
    being present: the writer emits the key unconditionally, so key-presence would read a
    trace-less (errored) record as a fully explained one. An artifact written before
    ``crossEncoderStatus``/``crossEncoderReason`` existed is unevaluable, not clean.
    """
    applied = any(
        s.get("ce_score") is not None
        for s in (record.get("judgeSignals") or [])
        if isinstance(s, dict)
    )
    return CeQueryState(
        applied=applied,
        reason=record.get("crossEncoderReason"),
        reason_channel=record.get("crossEncoderStatus") is not None,
        signals_channel="judgeSignals" in record,
    )


def states_from_per_query_records(records: list[dict]) -> list[CeQueryState]:
    """Read a whole ``{mode}_per_query.json`` file, dropping errored queries.

    The error filter mirrors ``run.execute_run``'s own ``query_evidences`` filter, so a post-hoc
    read of the artifact reaches the same verdict the run embedded: an errored query has no trace
    to read and is already counted by ``error_count`` — counting it as CE-less would strike a run
    for a failure another signal already reports.
    """
    return [state_from_per_query_record(r) for r in records if not r.get("error")]


@dataclass
class CeCoverageResult:
    """Verdict for the cross-encoder-coverage validity guard.

    ``verdict`` is one of ``"ok"`` (CE applied to all but a tolerated sliver of the eligible
    queries, every other CE-less query explained by a deterministic reason), ``"degraded-ce"``
    (silent drops above tolerance — the run's ranking is a blend of CE-reranked and fusion-only
    queries), ``"not-applicable"`` (the CE was not in play: no query was CE-eligible), or
    ``"unevaluable"`` (the run's artifacts cannot answer the question — they predate
    ``judgeSignals`` or the CE reason channel). ``reasons`` is never boolean-only — always a
    legible list, even on ``ok``/``not-applicable`` — matching
    :class:`jseval.chunk_completeness.ChunkCompletenessResult`.
    """

    applied: int
    eligible: int
    silent_drops: int
    legitimate_skips: int
    not_in_play: int
    coverage: float | None
    verdict: str
    legitimate_skip_reason_counts: dict[str, int] = field(default_factory=dict)
    silent_drop_reason_counts: dict[str, int] = field(default_factory=dict)
    reasons: list[str] = field(default_factory=list)


# Run-level precedence when a run has several modes. `degraded-ce` outranks everything (one
# contaminated mode contaminates the run's comparison); `unevaluable` outranks the two passes so a
# stand-down is never hidden behind a sibling mode's clean verdict; `not-applicable` is last, so a
# run is only "the CE was not in play" when that is true of EVERY mode.
_VERDICT_PRECEDENCE = ("degraded-ce", "unevaluable", "ok", "not-applicable")


def as_block(result: CeCoverageResult) -> dict:
    """JSON-serializable projection of a :class:`CeCoverageResult` for the run summary."""
    return {
        "verdict": result.verdict,
        "applied": result.applied,
        "eligible": result.eligible,
        "legitimate_skips": result.legitimate_skips,
        "legitimate_skip_reason_counts": dict(result.legitimate_skip_reason_counts),
        "silent_drops": result.silent_drops,
        "silent_drop_reason_counts": dict(result.silent_drop_reason_counts),
        "not_in_play": result.not_in_play,
        "coverage": result.coverage,
        "reasons": list(result.reasons),
    }


def combine_mode_verdicts(
    per_mode: dict[str, CeCoverageResult],
) -> tuple[str, list[str]]:
    """Fold per-mode verdicts into the run-level ``(verdict, reasons)`` the gate seam enforces."""
    if not per_mode:
        return ("not-applicable", ["this run evaluated no mode — nothing to check"])
    verdict = min(
        (r.verdict for r in per_mode.values()),
        key=lambda v: _VERDICT_PRECEDENCE.index(v) if v in _VERDICT_PRECEDENCE else 0,
    )
    reasons = [
        f"{mode}: {r.verdict} — {'; '.join(r.reasons)}"
        for mode, r in sorted(per_mode.items())
    ]
    return (verdict, reasons)


def _empty(verdict: str, reason: str) -> CeCoverageResult:
    return CeCoverageResult(
        applied=0, eligible=0, silent_drops=0, legitimate_skips=0, not_in_play=0,
        coverage=None, verdict=verdict, reasons=[reason],
    )


def ce_coverage_verdict(
    states: list[CeQueryState],
    *,
    ce_requested: bool,
    tolerance: float = DEFAULT_SILENT_DROP_TOLERANCE,
) -> CeCoverageResult:
    """Pure verdict over one mode's per-query cross-encoder outcomes.

    ``ce_requested`` is the run-level "was the CE part of this pipeline at all" signal (``True``
    when ``cross_encoder`` appears in the mode's ``pipeline_tracking.observed``, i.e. at least one
    query reranked). It is only ever used to stand DOWN, never to stand up: a run where the CE was
    dropped on 100% of queries has no observed leg either, so the per-query reasons — which are
    recorded on the drop path too — still decide. That asymmetry is deliberate; reading
    ``observed`` as the gate's applicability test would make total contamination look like
    "CE not requested."

    Classification per query, fail-closed: applied (a delivered hit carries a ``ce_score``) →
    covered; skipped with a reason in :data:`CE_NOT_IN_PLAY_REASONS` → not eligible; skipped with
    a reason in :data:`DETERMINISTIC_SKIP_REASONS` → legitimate; anything else, INCLUDING an
    unrecognized reason and a recorded-but-empty reason → silent drop.
    """
    if not states:
        return _empty("not-applicable", "no per-query records for this mode — nothing to check")

    # An artifact predating tempdoc 643's judgeSignals cannot answer "did this hit carry a CE
    # score", and one predating the CE reason channel cannot tell a deterministic skip from a
    # deadline drop. Either way the guard has no ground to stand on. Checked before anything is
    # counted, so a partially-migrated shape can never be judged on half its evidence.
    signal_less = [s for s in states if not s.signals_channel]
    if signal_less:
        return _empty(
            "unevaluable",
            f"{len(signal_less)}/{len(states)} per-query records carry no judgeSignals "
            f"(an artifact predating tempdoc 643), so whether a delivered hit was cross-encoder "
            f"scored cannot be read — this run is NOT gated on CE coverage",
        )

    # Only a CE-LESS query needs explaining, so the channel is only load-bearing for those: a run
    # whose every query reranked is judgeable on any artifact vintage.
    blind = [s for s in states if not s.applied and not s.reason_channel]
    if blind:
        return _empty(
            "unevaluable",
            f"{len(blind)}/{len(states)} cross-encoder-less queries carry no cross-encoder stage "
            f"(crossEncoderStatus/crossEncoderReason), so a deterministic skip cannot be told "
            f"apart from a deadline drop — this run is NOT gated on CE coverage",
        )

    applied = 0
    not_in_play = 0
    legit: dict[str, int] = {}
    drops: dict[str, int] = {}
    for state in states:
        if state.applied:
            applied += 1
            continue
        reason = state.reason
        if reason in CE_NOT_IN_PLAY_REASONS:
            not_in_play += 1
        elif reason in DETERMINISTIC_SKIP_REASONS:
            legit[reason] = legit.get(reason, 0) + 1
        else:
            key = reason if reason else NO_REASON_RECORDED
            drops[key] = drops.get(key, 0) + 1

    legitimate_skips = sum(legit.values())
    silent_drops = sum(drops.values())
    eligible = applied + legitimate_skips + silent_drops

    if eligible == 0:
        return CeCoverageResult(
            applied=0, eligible=0, silent_drops=0, legitimate_skips=0, not_in_play=not_in_play,
            coverage=None, verdict="not-applicable",
            reasons=[
                f"no CE-eligible query in {len(states)} — every one reports a cross-encoder-off "
                f"state ({sorted(CE_NOT_IN_PLAY_REASONS)}), so there is no coverage to gate"
            ],
        )
    if not ce_requested and silent_drops == 0:
        # The CE was never observed AND nothing looks dropped: a pipeline that simply does not
        # rerank. Stand down rather than report a vacuous 0% coverage.
        return CeCoverageResult(
            applied=applied, eligible=eligible, silent_drops=0,
            legitimate_skips=legitimate_skips, not_in_play=not_in_play,
            coverage=applied / eligible, verdict="not-applicable",
            legitimate_skip_reason_counts=dict(sorted(legit.items())),
            reasons=["cross_encoder is absent from this mode's observed legs and no query looks "
                     "dropped — the pipeline did not request the cross-encoder"],
        )

    coverage = applied / eligible
    drop_rate = silent_drops / eligible
    reasons = [
        f"cross-encoder applied to {applied}/{eligible} CE-eligible queries "
        f"(coverage={coverage:.4f}); {legitimate_skips} deterministic skip(s) "
        f"{dict(sorted(legit.items()))}; {silent_drops} silent drop(s) "
        f"{dict(sorted(drops.items()))}"
    ]
    if drop_rate > tolerance:
        reasons.append(
            f"silent-drop rate {drop_rate:.4f} > tolerance {tolerance} — these queries were "
            f"delivered in pure fusion order with no deterministic reason, so this run's ranking "
            f"is a blend of two pipelines and its metrics are not comparable"
        )
        verdict = "degraded-ce"
    else:
        reasons.append(f"silent-drop rate {drop_rate:.4f} <= tolerance {tolerance}")
        verdict = "ok"

    return CeCoverageResult(
        applied=applied,
        eligible=eligible,
        silent_drops=silent_drops,
        legitimate_skips=legitimate_skips,
        not_in_play=not_in_play,
        coverage=coverage,
        verdict=verdict,
        legitimate_skip_reason_counts=dict(sorted(legit.items())),
        silent_drop_reason_counts=dict(sorted(drops.items())),
        reasons=reasons,
    )
