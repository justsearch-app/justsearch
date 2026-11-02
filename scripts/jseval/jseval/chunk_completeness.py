"""Eval-time chunk-completeness validity guard (tempdoc 718).

A fresh index build can silently ship with its chunk sub-system absent (tempdoc 717): a
degenerate build reads bit-for-bit identical to a legitimately chunk-free corpus at the
*pipeline output* layer -- ``chunkDocCount=0``, ``chunkVectorCoveragePercent=0.0`` both ways
(``LuceneRuntimeTypes.coveragePercent()`` returns ``0.0`` when ``total==0``, never a vacuous
100%). No attempted-vs-completed counter exists, so the *observed* signal alone cannot
distinguish "short docs, nothing to chunk" from "build degenerated."

The disambiguator is an OFFLINE signal the degeneracy can't touch: :func:`expected_chunk_docs`
computes, straight from the corpus TEXT, how many documents SHOULD have produced chunk docs --
before/independent of any ingest, so a broken enrichment pipeline can never move this number.
:func:`chunk_completeness_verdict` is the pure comparison of that offline expectation against
the post-enrichment observed counts; :mod:`jseval.ratchet_kernel` enforces it at the gate seam.
"""

from __future__ import annotations

import json
from collections.abc import Iterator
from dataclasses import dataclass, field
from pathlib import Path


def resolve_chunk_threshold_chars(status_payload: dict | None) -> int | None:
    """Read the chunk threshold the BACKEND published, or ``None`` when it did not publish one.

    Tempdoc 821 §3-C3 closed the DUAL-SOURCE-OF-TRUTH risk tempdoc 718 named: this module used to
    carry its own ``CHUNK_THRESHOLD_CHARS = 2000`` mirror of
    ``ChunkDocumentWriter.CHUNK_THRESHOLD_CHARS`` (applied identically by
    ``IndexingDocumentOps.java:395``) and would have drifted silently the day the Java value
    changed. The worker's enrichment auditor now OWNS the number and publishes it as
    ``worker.enrichment.chunkMinChars``, so this oracle reads it instead of mirroring it -- and
    there is deliberately NO local fallback constant, because a fallback is the mirror again.

    Accepts either a raw ``/api/status`` payload or one already flattened by
    ``jsharness.readiness.flatten_status`` (the flattener lifts ``worker``'s sub-records to the top
    level). ``None`` means "this backend does not publish the threshold" -- a payload predating
    821, or no status snapshot at all. Callers must treat that as "nothing to expect", the same
    never-blocks path a missing ``corpus.jsonl`` already takes; they must NOT substitute a guess.
    """
    if not isinstance(status_payload, dict):
        return None
    candidates = [status_payload]
    worker = status_payload.get("worker")
    if isinstance(worker, dict):
        candidates.append(worker)
        enrichment = worker.get("enrichment")
        if isinstance(enrichment, dict):
            candidates.append(enrichment)
    enrichment_flat = status_payload.get("enrichment")
    if isinstance(enrichment_flat, dict):
        candidates.append(enrichment_flat)
    for source in candidates:
        value = source.get("chunkMinChars")
        if isinstance(value, bool):
            continue
        if isinstance(value, int) and value > 0:
            return value
    return None


@dataclass
class CorpusDoc:
    """One BEIR-format corpus.jsonl row, with its materialized (index-time) content and length.

    ``content``/``length`` mirror the exact string ``ChunkDocumentWriter``/``IndexingDocumentOps``
    apply ``CHUNK_THRESHOLD_CHARS`` to at index time -- see :func:`iter_corpus_docs`.
    """

    doc_id: str
    title: str
    text: str
    content: str
    length: int


def iter_corpus_docs(corpus_jsonl_path: Path | str) -> Iterator[CorpusDoc]:
    """Yield each doc in a BEIR-format ``corpus.jsonl`` with its materialized content length.

    Reads ``{"_id", "title", "text"}`` lines -- the format ``corpus_build.build_golden`` writes
    for every golden/mixed self-demo corpus -- and reconstructs each doc's ingested content
    exactly as ``jseval.materialize.materialize()`` does: ``f"{title}\\n\\n{text}"`` when a
    title is present, else bare ``text`` (materialize.py:57) -- the same string
    ``ChunkDocumentWriter``/``IndexingDocumentOps`` apply the chunk threshold to at index time.

    Yields nothing when ``corpus_jsonl_path`` doesn't exist -- e.g. a BEIR dataset materialized
    on the fly via ``ir_datasets`` (no local ``corpus.jsonl``, tempdoc 718 scope: golden/mixed
    self-demo corpora). Callers (``expected_chunk_docs``, ``rag_reachability_probe``) treat an
    empty iteration as "nothing to expect/sample," never an error.
    """
    path = Path(corpus_jsonl_path)
    if not path.is_file():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        doc = json.loads(line)
        doc_id = doc.get("_id") or ""
        title = doc.get("title") or ""
        text = doc.get("text") or ""
        content = f"{title}\n\n{text}" if title else text
        yield CorpusDoc(doc_id=doc_id, title=title, text=text, content=content, length=len(content))


def expected_chunk_docs(corpus_jsonl_path: Path | str, threshold_chars: int | None) -> int:
    """Offline (spoof-proof) count of corpus docs that SHOULD produce chunk docs.

    A doc counts when ``len(content) >= threshold_chars`` (see :func:`iter_corpus_docs` for the
    materialization rule). This is a pure read of the corpus TEXT, computed before/independent
    of any ingest -- the enrichment pipeline's own failure can never move it.
    ``expected_chunk_docs() > 0`` means "this corpus SHOULD produce chunk docs."

    ``threshold_chars`` comes from :func:`resolve_chunk_threshold_chars` -- the backend's own
    published value, never a local mirror. ``None`` (a backend that does not publish it) returns
    ``0``, the same "nothing to expect" outcome as a missing ``corpus.jsonl``: without the real
    threshold this oracle has no ground to gate on, so it must not gate. Returns ``0`` when
    ``corpus_jsonl_path`` doesn't exist too, matching :func:`iter_corpus_docs`'s empty-iteration
    behavior -- BEIR runs correctly get a ``chunk-free`` verdict (never gated) rather than a
    spurious "0 expected."
    """
    if threshold_chars is None or threshold_chars <= 0:
        return 0
    return sum(1 for d in iter_corpus_docs(corpus_jsonl_path) if d.length >= threshold_chars)


def corpus_doc_count(corpus_jsonl_path: Path | str) -> int:
    """Offline count of ALL docs in ``corpus.jsonl`` (not filtered by chunk-eligibility length).

    Cheap sibling of :func:`expected_chunk_docs` -- reuses :func:`iter_corpus_docs`, so it shares
    that function's contract: returns ``0`` when ``corpus_jsonl_path`` doesn't exist (a BEIR
    dataset with no local corpus.jsonl), never an error.

    Used as the denominator of a chunk-eligibility RATE (``expected_chunk_docs / corpus_doc_count``)
    by the tempdoc-715-defect-1 corpus-shape waiver in ``jseval.run._compute_chunk_completeness``,
    mirroring the engine's own short-corpus classification (``CorpusProfile.chunkRate() < 0.05``,
    ``modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/CorpusProfile.java``)
    rather than gating on an unscaled absolute ``expected`` count.
    """
    return sum(1 for _ in iter_corpus_docs(corpus_jsonl_path))


@dataclass
class ChunkCompletenessResult:
    """Verdict for the eval-time chunk-completeness validity guard (tempdoc 718).

    ``verdict`` is one of ``"ok"`` (expected>0, observed healthy), ``"chunk-free"``
    (expected==0 -- legitimately nothing to chunk, not a degeneracy), ``"degenerate"``
    (expected>0 but the observed signals say the chunk sub-system didn't run), or
    ``"unevaluable"`` (tempdoc 821 §3-C3 -- the backend published no chunk threshold, so the
    offline expectation could not be computed at all). ``reasons`` is NEVER boolean-only
    (matches :class:`jseval.types.ComparabilityResult` /
    :func:`jseval.ratchet_kernel.compare_engine_sets`) -- always a legible list, even on
    ``ok``/``chunk-free`` (documenting *why*, not just *that*).

    ``unevaluable`` exists because ``chunk-free`` is an affirmative claim -- "no corpus doc
    reaches the threshold" -- that a run with no threshold never established. Collapsing the
    two would let a genuinely degenerate build on a threshold-less backend read as a clean pass;
    :func:`jseval.ratchet_kernel.assert_chunk_completeness` treats this verdict as
    pass-with-warning so the stand-down is at least LOUD.
    """

    expected: int
    observed: int
    verdict: str
    reasons: list[str] = field(default_factory=list)


DEFAULT_COVERAGE_FLOOR = 99.9


def unevaluable_result(
    observed_chunk_doc_count: int, reason: str
) -> ChunkCompletenessResult:
    """The stand-down result for a run whose chunk threshold could not be resolved (821 §3-C3).

    A distinct constructor rather than a post-edit of
    :func:`chunk_completeness_verdict`'s output: that function's ``chunk-free`` branch states
    "no corpus doc reaches the chunk threshold", a fact this path never computed and which is
    affirmatively wrong on a degenerate build. ``expected`` is 0 because nothing was expected --
    not because nothing was expectable.
    """
    return ChunkCompletenessResult(
        expected=0,
        observed=observed_chunk_doc_count,
        verdict="unevaluable",
        reasons=[reason],
    )


def chunk_completeness_verdict(
    expected_chunk_docs: int,
    observed_chunk_doc_count: int,
    observed_coverage_pct: float | None,
    chunk_merge_observed: bool,
    *,
    coverage_floor: float = DEFAULT_COVERAGE_FLOOR,
) -> ChunkCompletenessResult:
    """Pure verdict comparing the offline expectation to the post-enrichment observation.

    ``expected_chunk_docs>0 AND (observed_chunk_doc_count==0 OR observed_coverage_pct<
    coverage_floor OR NOT chunk_merge_observed)`` -> ``degenerate`` (fail closed).
    ``expected_chunk_docs==0`` -> ``chunk-free`` (legitimately, pass) -- checked FIRST so a
    genuinely chunk-free corpus (e.g. all-short-doc golden set) never reads the observed
    signals at all, matching the anti-spoof design: the offline signal alone decides
    applicability. Otherwise (both signals present and consistent) -> ``ok``.

    ``observed_coverage_pct=None`` (the field absent from ``/api/status`` -- an older backend,
    or chunking disabled) is treated as ``0.0`` for the comparison, mirroring
    ``LuceneRuntimeTypes.coveragePercent()``'s own "never a vacuous 100%" contract when
    ``total==0``. ``chunk_merge_observed`` is a query-time corroborator from
    ``per_mode.vector.pipeline_tracking.observed`` -- callers that did not run ``vector`` mode
    this run should pass ``True`` (corroborator not applicable, never a strike on its own).
    """
    if expected_chunk_docs <= 0:
        return ChunkCompletenessResult(
            expected=expected_chunk_docs,
            observed=observed_chunk_doc_count,
            verdict="chunk-free",
            reasons=[
                "expected_chunk_docs == 0 -- no corpus doc reaches the chunk threshold, "
                "so 0 observed chunk docs is the correct outcome, not a degeneracy"
            ],
        )

    coverage = observed_coverage_pct if observed_coverage_pct is not None else 0.0
    reasons: list[str] = []
    if observed_chunk_doc_count <= 0:
        reasons.append(
            f"expected_chunk_docs={expected_chunk_docs} > 0 but observed chunk_doc_count == 0"
        )
    if coverage < coverage_floor:
        reasons.append(
            f"observed chunkVectorCoveragePercent={coverage} < floor {coverage_floor}"
        )
    if not chunk_merge_observed:
        reasons.append(
            "chunk_merge absent from vector mode's pipeline_tracking.observed "
            "(query-time corroborator)"
        )

    if reasons:
        return ChunkCompletenessResult(
            expected=expected_chunk_docs,
            observed=observed_chunk_doc_count,
            verdict="degenerate",
            reasons=reasons,
        )
    return ChunkCompletenessResult(
        expected=expected_chunk_docs,
        observed=observed_chunk_doc_count,
        verdict="ok",
        reasons=[
            f"expected_chunk_docs={expected_chunk_docs}, observed_chunk_doc_count="
            f"{observed_chunk_doc_count}, coverage={coverage} >= floor {coverage_floor}, "
            f"chunk_merge observed"
        ],
    )
