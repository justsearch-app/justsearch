"""Synthetic collections and runs for the quality tests, the old-harness converter, and the
real-data locations.

Real data is optional and never assumed: ``JSEVAL_QUALITY_REAL_RUNS`` names the directory holding
the saved harness outputs of 2026-11-02 (``20261102T142955_scifact``,
``20261102T133659_mixed_miracl-de-2k``); ``IR_DATASETS_HOME`` (default ``~/.ir_datasets``) the
ir_datasets cache. Tests that need either skip with the reason when it is absent.
"""

from __future__ import annotations

import json
import os
from dataclasses import replace
from pathlib import Path

from jseval.quality.collections import QualityConfig
from jseval.quality.types import (
    Collection,
    QueryResult,
    QueryText,
    RunHeader,
    RunRecord,
    Served,
    document_text,
    sha256_text,
)

REAL_RUNS_ENV = "JSEVAL_QUALITY_REAL_RUNS"
REAL_RUNS = Path(os.environ[REAL_RUNS_ENV]) if os.environ.get(REAL_RUNS_ENV) else None
SCIFACT_RUN_NAME = "20261102T142955_scifact"
DE_RUN_NAME = "20261102T133659_mixed_miracl-de-2k"
IR_HOME = Path(os.environ.get("IR_DATASETS_HOME", Path.home() / ".ir_datasets"))

LEGS = ("lexical", "dense")
SERVED = Served(LEGS, "executed")


def real_run(name: str) -> tuple[Path | None, str]:
    """(the saved run directory, or None) and the reason it is unavailable."""
    if REAL_RUNS is None:
        return None, f"{REAL_RUNS_ENV} is not set"
    d = REAL_RUNS / name
    return (d, "") if d.is_dir() else (None, f"{d} does not exist")


def have_ir(*parts: str) -> bool:
    return IR_HOME.joinpath(*parts).exists()


def content_of(documents) -> dict[str, str]:
    return {d: sha256_text(document_text("", f"text of {d}")) for d in documents}


def make_collection(groups: dict[str, int] | None = None, n_docs: int = 60,
                    name: str = "synthetic") -> Collection:
    """Queries per group label (``"de/known-item": 40``); each query has positives of grade 2 and
    1 and two grade-0 judgments, drawn from a shared pool of ``n_docs`` documents."""
    groups = groups or {"en/vague": 40}
    queries, judgments = {}, {}
    i = 0
    for label, n in groups.items():
        lang, kind = label.split("/")
        for _ in range(n):
            qid = f"q{i:03d}"
            queries[qid] = QueryText(f"question {i} ({label})", {"lang": lang, "kind": kind})
            d = [f"d{(i * 7 + k) % n_docs:03d}" for k in range(4)]
            judgments[qid] = {d[0]: 2, d[1]: 1, d[2]: 0, d[3]: 0}
            i += 1
    documents = {f"d{k:03d}": f"docs/d{k:03d}.txt" for k in range(n_docs)}
    return Collection(name, "en", queries, judgments, documents, content_of(documents),
                      {"config": name})


def config_for(c: Collection, **kw) -> QualityConfig:
    base = dict(collection=c.name, language=c.language, source={"kind": "synthetic"},
                labels={}, expected=c.counts(), bootstrap_resamples=2000)
    base.update(kw)
    return QualityConfig(**base)


def ranking(c: Collection, qid: str, quality: str) -> list[str]:
    """``good``: positives first; ``bad``: four non-relevant documents ahead of the positives;
    ``slight``: the grade-1 positive pushed from rank 2 to rank 3 (nDCG@10 -0.0498)."""
    j = c.judgments[qid]
    pos = sorted((d for d, g in j.items() if g > 0), key=lambda d: -j[d])
    others = [d for d in sorted(c.documents) if j.get(d, 0) <= 0]
    if quality == "good":
        return pos + others[:8]
    if quality == "slight":
        return pos[:1] + others[:1] + pos[1:] + others[1:8]
    return others[:4] + pos + others[4:8]


def header(c: Collection, **kw) -> RunHeader:
    base = dict(collection=c.name, collection_version=c.version, index_key="idx-1",
                index_population=c.index_population(),
                request_config={"mode": "hybrid", "k": 10}, commit="a" * 40, dirty_hash=None,
                model_hashes={"embed": "e1", "reranker": "r1"}, started="2026-11-02T10:00:00Z",
                ended="2026-11-02T10:05:00Z", expected_legs=LEGS, expected_reranker="executed",
                index_build="fresh", index_state={"settled": True})
    base.update(kw)
    return RunHeader(**base)


def make_run(c: Collection, quality: dict[str, str] | str = "good", **header_kw) -> RunRecord:
    """``quality`` per query id (default for all: a string)."""
    results = []
    for qid in sorted(c.queries):
        q = quality if isinstance(quality, str) else quality.get(qid, "good")
        results.append(QueryResult(qid, c.queries[qid].sha256, tuple(ranking(c, qid, q)), SERVED))
    return RunRecord(header(c, **header_kw), tuple(results))


def with_results(run: RunRecord, results) -> RunRecord:
    return replace(run, results=tuple(results))


def run_from_harness(result_dir: str | Path, c: Collection, mode: str = "hybrid") -> RunRecord:
    """The old harness's per-query output (``<mode>_per_query.json``) as a RunRecord (read-only).

    Query ids are mapped onto the collection by exact query text (repeated texts take the
    collection's ids in order), so a harness that renumbered queries still lines up. The old
    index was not built from the collection, so the header records the old corpus signature as
    its population: such a record scores, but never validates as a comparison arm. Each query is
    recorded as served by the ``mode`` leg (plus ``dense`` where the harness says it executed);
    the old harness declared no reranker use, so ``expected_reranker`` is ``skipped``.
    """
    d = Path(result_dir)
    rows = json.loads((d / f"{mode}_per_query.json").read_text(encoding="utf-8"))
    manifest = json.loads((d / "manifest.json").read_text(encoding="utf-8"))
    by_text: dict[str, list[str]] = {}
    for qid, q in c.queries.items():
        by_text.setdefault(q.text, []).append(qid)
    results = []
    for row in rows:
        ids = by_text.get(row["query"])
        if not ids:
            raise ValueError(f"harness query {row['qid']} has no collection query with its text")
        qid = ids.pop(0)
        dense = str(row.get("denseStatus")).lower() == "executed" and mode != "dense"
        results.append(QueryResult(
            qid=qid, query_text_sha256=c.queries[qid].sha256,
            ranked=tuple(row["predictedDocIds"]),
            served=Served((mode, "dense") if dense else (mode,),
                          "executed" if str(row.get("crossEncoderStatus")).lower() == "executed"
                          else "skipped", row.get("crossEncoderReason")),
            error=row.get("error"), timings={"took_ms": float(row.get("tookMs") or 0)}))
    signature = str((manifest.get("corpus_identity") or {}).get("signature") or "unknown")
    header = RunHeader(
        collection=c.name, collection_version=c.version,
        index_key=f"legacy:{manifest.get('run_id')}",
        index_population={"count": int(manifest.get("doc_count") or 0),
                          "manifest_sha256": signature, "content_sha256": signature},
        request_config={"legacy_mode": mode, "policy_hash": manifest.get("policy_hash")},
        commit=manifest.get("git_sha") or "unknown", dirty_hash=None,
        model_hashes={k: str(v) for k, v in (manifest.get("model_fingerprints") or {}).items()},
        started=manifest.get("timestamp") or "unknown", ended=manifest.get("timestamp") or "unknown",
        expected_legs=(mode,), expected_reranker="skipped", index_build="fresh")
    return RunRecord(header, tuple(results))
