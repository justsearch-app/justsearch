"""Synthetic collections and runs for the quality tests, plus the real-data locations."""

from __future__ import annotations

import os
from dataclasses import replace
from pathlib import Path

from jseval.quality.collections import QualityConfig
from jseval.quality.types import Collection, QueryResult, QueryText, RunHeader, RunRecord

HARNESS = Path("/home/elias/dev/justsearch/.claude/worktrees/eval-p1-corrections/scripts/jseval"
               "/tmp/eval-results")
SCIFACT_RUN = HARNESS / "20261102T142955_scifact"
DE_RUN = HARNESS / "20261102T133659_mixed_miracl-de-2k"
IR_HOME = Path(os.environ.get("IR_DATASETS_HOME", Path.home() / ".ir_datasets"))


def have_ir(*parts: str) -> bool:
    return IR_HOME.joinpath(*parts).exists()


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
    return Collection(name, "en", queries, judgments, documents, {"config": name})


def config_for(c: Collection, **kw) -> QualityConfig:
    base = dict(collection=c.name, language=c.language, source={"kind": "synthetic"},
                labels={}, expected=c.counts(), bootstrap_resamples=2000)
    base.update(kw)
    return QualityConfig(**base)


def ranking(c: Collection, qid: str, quality: str) -> list[str]:
    """``good``: positives first; ``bad``: four non-relevant documents ahead of the positives."""
    j = c.judgments[qid]
    pos = sorted((d for d, g in j.items() if g > 0), key=lambda d: -j[d])
    others = [d for d in sorted(c.documents) if j.get(d, 0) <= 0]
    if quality == "good":
        return pos + others[:8]
    return others[:4] + pos + others[4:8]


def header(c: Collection, **kw) -> RunHeader:
    base = dict(collection=c.name, collection_version=c.version, index_key="idx-1",
                index_population={"count": len(c.documents), "manifest_sha256": c.manifest_sha256},
                request_config={"mode": "hybrid", "k": 10}, commit="a" * 40, dirty_hash=None,
                model_hashes={"embed": "e1", "reranker": "r1"}, started="2026-11-02T10:00:00Z",
                ended="2026-11-02T10:05:00Z", index_state={"settled": True})
    base.update(kw)
    return RunHeader(**base)


def make_run(c: Collection, quality: dict[str, str] | str = "good", **header_kw) -> RunRecord:
    """``quality`` per query id (default for all: a string)."""
    results = []
    for qid in sorted(c.queries):
        q = quality if isinstance(quality, str) else quality.get(qid, "good")
        results.append(QueryResult(qid, c.queries[qid].sha256, tuple(ranking(c, qid, q))))
    return RunRecord(header(c, **header_kw), tuple(results))


def with_results(run: RunRecord, results) -> RunRecord:
    return replace(run, results=tuple(results))
