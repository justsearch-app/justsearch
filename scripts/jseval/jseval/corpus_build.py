"""Build a `golden/` self-demo corpus from a committed source (tempdoc 635).

`datasets/` is gitignored, so a clean corpus is committed as a *source* under
`scripts/jseval/635-corpora/<name>/` and *materialized* into
`datasets/golden/<name>/` at build time (the same pattern `util-smoke/` uses). This
realizes the design's **single source → two projections** (§D.2.1/§D.3d): one
annotated source projects to BOTH the retrieval-quality view (`corpus.jsonl` +
`queries.jsonl` + `qrels/test.tsv`) AND the agent-utility view (`queries.json` +
raw `corpus-dir`). The qrels view derives from each query's `evidence_ids` — the
only genuinely-new projector; everything else reuses `materialize`.

Source layout (`scripts/jseval/635-corpora/<name>/`):
- ``docs.jsonl``   — lines ``{"_id","title","text"}`` (the fabricated documents)
- ``queries.json`` — list ``{"query","answer","question_type","evidence_ids":[...]}``
                     (``evidence_ids`` reference doc ``_id``; ≥2 ⇒ genuinely multi-hop)
- ``meta.json``    — ``{"version","type_axis","suite","contamination_class",
                       "generation_provenance"}``
"""

from __future__ import annotations

import json
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

from jseval import corpus_generate, materialize
from jseval.corpus_identity import corpus_signature


def read_jsonl(path: Path) -> list[dict]:
    out: list[dict] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line:
            out.append(json.loads(line))
    return out


def build_golden(source_dir: Path | str, dataset_dir: Path | str, *, now: str | None = None) -> dict:
    """Materialize a committed corpus source into a `golden/` BEIR-layout dataset.

    Writes ``corpus.jsonl`` + ``queries.jsonl`` + ``qrels/test.tsv`` (retrieval view),
    ``queries.json`` (agent answers view), a raw ``corpus-dir/`` (agent file-tools view,
    via ``materialize.materialize``), and a ``metadata.json`` carrying the tempdoc-635
    identity/provenance fields + the computed ``corpus_signature``. Returns the metadata.

    Carries the source's ``evidence_offsets.json`` sidecar forward when present (tempdoc 783
    §B.1b) so the metadata tier of ``jseval offset-recall`` resolves on a materialized cell.
    """
    source_dir = Path(source_dir)
    dataset_dir = Path(dataset_dir)
    dataset_dir.mkdir(parents=True, exist_ok=True)

    docs = read_jsonl(source_dir / "docs.jsonl")
    queries = json.loads((source_dir / "queries.json").read_text(encoding="utf-8"))
    src_meta = json.loads((source_dir / "meta.json").read_text(encoding="utf-8"))

    doc_ids = {d["_id"] for d in docs}

    # --- retrieval view: corpus.jsonl ({_id, title, text}) ---
    with (dataset_dir / "corpus.jsonl").open("w", encoding="utf-8") as f:
        for d in docs:
            f.write(json.dumps(
                {"_id": d["_id"], "title": d.get("title", ""), "text": d["text"]},
                ensure_ascii=False) + "\n")

    # --- retrieval view: queries.jsonl + qrels/test.tsv ---
    # qrels mark ONLY the FIRST-hop (query-targeted, retrievable) evidence doc — review
    # Issue A. Full-chain qrels confound the metric with hop count (hop-2+ docs aren't
    # single-shot-retrievable, so nDCG is capped at ~1/n_hops regardless of retrieval
    # quality). The retrieval metric's job is "does retrieval find the doc the query
    # targets, among distractors"; the full chain stays the AGENT's evidence (queries.json).
    # Consequence: a verbatim-token query → head trivially found (nDCG ~1.0 → the fidelity
    # gate flags it trivial, correctly); a semantic query → head requires real retrieval
    # (non-trivial). The metric now measures retrieval difficulty cleanly, hop-independent.
    qrels_dir = dataset_dir / "qrels"
    qrels_dir.mkdir(exist_ok=True)
    with (dataset_dir / "queries.jsonl").open("w", encoding="utf-8") as qf, \
         (qrels_dir / "test.tsv").open("w", encoding="utf-8") as tf:
        tf.write("query-id\tcorpus-id\tscore\n")
        for i, q in enumerate(queries, 1):
            qid = f"q{i:04d}"
            query_row = {"_id": qid, "text": q["query"]}
            for field in ("query_variant", "query_family_id"):
                if field in q:
                    query_row[field] = q[field]
            qf.write(json.dumps(query_row, ensure_ascii=False) + "\n")
            evidence = q.get("evidence_ids", [])
            unknown = [e for e in evidence if e not in doc_ids]
            if unknown:
                raise ValueError(f"{qid} references unknown evidence doc(s): {unknown}")
            # Fetched public judgments are complete graded relevance sets, not a
            # synthetic reasoning chain. Keep the bridge/agent default below intact.
            if "relevance_grades" in q:
                grades = q["relevance_grades"]
                if not isinstance(grades, dict) or set(grades) != set(evidence):
                    raise ValueError(f"{qid} relevance_grades must cover exactly its evidence_ids")
                for ev, grade in grades.items():
                    if type(grade) is not int or grade <= 0:
                        raise ValueError(f"{qid} invalid positive relevance grade: {grade!r}")
                    tf.write(f"{qid}\t{ev}\t{grade}\n")
                continue
            if evidence:
                # Bridge/single-fact: first-hop only (the query-targeted entry point) — the
                # full chain stays the agent's evidence in queries.json. Aggregation (tempdoc
                # 776 §A.1) has no single entry point: every member doc is relevant, so all
                # evidence ids are marked. Guarded on question_type == "aggregation", so every
                # pre-776 single-schema cell writes exactly `evidence[0]` and its qrels bytes
                # are unchanged.
                relevant = evidence if q.get("question_type") == "aggregation" else evidence[:1]
                for ev in relevant:
                    tf.write(f"{qid}\t{ev}\t1\n")

    # --- agent view: queries.json (query+answer+question_type) ---
    (dataset_dir / "queries.json").write_text(
        json.dumps(
            [{
                "query": q["query"], "answer": q["answer"],
                "question_type": q.get("question_type", "two_hop"),
                "evidence_ids": q.get("evidence_ids", []),
                # tempdoc 776 §A.1: gold_kind selects the deterministic answer comparator
                # (jseval.corpus_comparators). Absent for pre-776 single-schema cells, so their
                # materialized queries.json bytes — and query_gold_sha256 — are unchanged.
                **({"gold_kind": q["gold_kind"]} if "gold_kind" in q else {}),
                **({"query_variant": q["query_variant"]} if "query_variant" in q else {}),
                **({"query_family_id": q["query_family_id"]} if "query_family_id" in q else {}),
            } for q in queries],
            ensure_ascii=False, indent=1),
        encoding="utf-8")

    # --- agent view: raw corpus-dir (reuse materialize: title+body → .txt files, or,
    # for the axis="scan" member, a degraded-scan PNG rendered HERE at materialize time
    # from the doc's own ground-truth `text` -- tempdoc 624 §T.2. The committed source
    # (`docs.jsonl`) never carries image bytes: `render_scan_page` is deterministic per
    # doc id, so the artifact is always reconstructable from source + this build step,
    # the same "single source -> projections" contract every other axis already keeps.
    # `datasets/` is gitignored precisely because materialized output is regenerable.
    corpus_dir = dataset_dir / "corpus-dir"
    type_axis = src_meta.get("type_axis")

    materialize.materialize(
        (corpus_generate.materialize_doc_entry(d, type_axis) for d in docs),
        corpus_dir, skip_existing=False)

    # --- evidence-offset sidecar carry-forward (tempdoc 783 §B.1b) ---
    # An injected source carries `evidence_offsets.json` (corpus_inject.build_source), but that
    # source dir is a TemporaryDirectory that dies with `corpus-inject-real` — so unless the
    # sidecar is carried HERE, no materialized cell ever has one and `jseval offset-recall`'s
    # metadata tier can only ever resolve on fixtures. Copied verbatim: `corpus.jsonl` above
    # reproduces each doc's `text` byte for byte, which is the string the recorded offsets index
    # into, and `offset_recall.load_corpus` reads that same `text` field. Purely additive — the
    # corpus signature hashes only `corpus.jsonl` + `qrels/test.tsv` (corpus_identity), and a
    # non-injected source has no sidecar, so this is a no-op there.
    sidecar_src = source_dir / "evidence_offsets.json"
    if sidecar_src.is_file():
        (dataset_dir / "evidence_offsets.json").write_bytes(sidecar_src.read_bytes())

    # --- identity + provenance metadata ---
    qtypes = Counter(q.get("question_type", "two_hop") for q in queries)
    stamped = now or datetime.now(timezone.utc).date().isoformat()
    metadata = {
        "version": src_meta.get("version", "1.0"),
        "created_date": stamped,
        "source": "golden",
        "corpus_size": len(docs),
        "query_count": len(queries),
        "type_axis": src_meta.get("type_axis"),
        "suite": src_meta.get("suite"),
        "contamination_class": src_meta.get("contamination_class"),
        "generation_provenance": src_meta.get("generation_provenance"),
        "query_type_distribution": dict(qtypes),
        "corpus_signature": corpus_signature(dataset_dir),
    }
    (dataset_dir / "metadata.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
    return metadata
