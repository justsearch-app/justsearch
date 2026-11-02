"""Convert the old harness's per-query output (``<mode>_per_query.json``) into a RunRecord.

Read-only on the source directory. Query ids are mapped onto the collection by exact query text
(repeated texts take the collection's ids in order), so a harness that renumbered queries
(``q0001``...) still lines up with the official ids. The old run's index was not built from the
collection manifest, so its header records the old corpus signature: such a record scores, but
never validates as a comparison arm.
"""

from __future__ import annotations

import json
from pathlib import Path

from .types import Collection, QueryResult, RunHeader, RunRecord, Served


def run_from_harness(result_dir: str | Path, c: Collection, mode: str = "hybrid") -> RunRecord:
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
        legs = ("dense",) if str(row.get("denseStatus")).lower() == "executed" else ()
        results.append(QueryResult(
            qid=qid, query_text_sha256=c.queries[qid].sha256,
            ranked=tuple(row["predictedDocIds"]),
            served=Served(legs, "executed" if str(row.get("crossEncoderStatus")).lower() == "executed"
                          else "skipped", row.get("crossEncoderReason")),
            error=row.get("error"), timings={"took_ms": float(row.get("tookMs") or 0)}))
    header = RunHeader(
        collection=c.name, collection_version=c.version,
        index_key=f"legacy:{manifest.get('run_id')}",
        index_population={"count": manifest.get("doc_count"),
                          "manifest_sha256": (manifest.get("corpus_identity") or {}).get("signature")},
        request_config={"legacy_mode": mode, "policy_hash": manifest.get("policy_hash")},
        commit=manifest.get("git_sha", ""), dirty_hash=None,
        model_hashes={k: str(v) for k, v in (manifest.get("model_fingerprints") or {}).items()},
        started=manifest.get("timestamp", ""), ended=manifest.get("timestamp", ""))
    return RunRecord(header, tuple(results))
