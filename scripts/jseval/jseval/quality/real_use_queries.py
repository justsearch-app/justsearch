"""Queries for the public real-use collection (S30): extraction, clusters, targets, writing, gates.

Every step is resumable. Derived document text stays under ``WORK`` and never enters Git; the
committed record (``recipes/real-use-queries/``) holds prompt templates, prompt hashes, model
outputs, document ids, cluster evidence and gate results only.

``prepare``   build or adopt the full index through the core's pausable background lifecycle,
              with a queue monitor that reports a stalled queue instead of waiting.
``extract``   read each document's extracted text and chunk count from the prepared index's
              live debug API (as the core witness does); report chunks per source and format.
``clusters``  near-duplicate clusters (exact + SimHash/Jaccard, ``duplicate_prevalence``), the
              recipe's natural families, and text-similarity topic groups.
``plan``      stratified known-item targets and topical groups, with recall levels and traits.
``generate``  write queries through ``claude -p`` (subscription, no tools); verbatim-leak gate
              with regeneration.
``gate``      lexical-only (``mode: text``) rank of every known-item target on the prepared
              index; regenerate rank-1 partial/vague queries until at most half are rank 1;
              distinguishability reports.
``write``     the stored collection: known-item judgments (target 2, byte-identical copy 1),
              topical queries pending labels.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import concurrent.futures
from dataclasses import replace
import hashlib
import json
import math
import os
from pathlib import Path
import random
import re
import statistics
import subprocess
import sys
import tempfile
import threading
import time

import httpx

from jsharness import readiness

from .. import corpus_leak
from .. import duplicate_prevalence as dp
from . import collections as qcoll
from . import index as ix
from .types import Collection, QueryText, atomic_write_text, canonical_json, sha256_text

WORK = Path.home() / ".cache/justsearch-quality/real-use-queries"
RECORD = Path(__file__).with_name("recipes") / "real-use-queries"
COLLECTION = "real-use"
MODEL = "claude-sonnet-5-5"
SEED = 20261104
STALL_SECONDS = 15 * 60
MONITORED = ("indexState", "indexedDocuments", "pendingJobs", "pendingJobsCount",
             "processingJobsCount", "embeddingPendingCount", "chunkEmbeddingPendingCount",
             "pendingNerCount", "failedNerCount", "chunkDocCount", "embeddingDocCount")
LEVELS = ("exact", "partial", "vague")
# Known-item targets per (source, language): about 180, half German, every source.
QUOTAS = {("bundestag", "de"): 30, ("eurlex", "de"): 15, ("eurlex", "en"): 15,
          ("safedocs", "de"): 20, ("govdata", "de"): 15, ("wikipedia", "de"): 10,
          ("wikipedia", "en"): 15, ("enron", "en"): 30, ("govdocs1", "en"): 30}
SPARE = 1.1  # planned per quota: leak/triviality drops leave about the quota
TOPICAL = {"de": 70, "en": 70}  # candidate groups; a group without a shared topic yields none
EXCERPT_CHARS = 2500
DEEP_MIN_WORDS = 1500
SIMHASH_TOKENS = 20000  # per-document token cap for SimHash/Jaccard (recorded in clusters.json)
NEAR_JACCARD = 0.5
SIMILAR_COSINE = 0.3


# --------------------------------------------------------------------------- small helpers


def _log(path: Path, row: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as f:
        f.write(canonical_json({"at": time.time(), **row}) + "\n")


def _jsonl(path: Path) -> list[dict]:
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def _write_json(path: Path, value) -> None:
    atomic_write_text(path, json.dumps(value, ensure_ascii=False, indent=1, sort_keys=True) + "\n")


def _collection():
    c, cfg = qcoll.resolve_collection(COLLECTION)
    from . import known_answers
    known_answers.check(c, cfg, require_scored=False)
    return c, cfg


def _recipe_rows() -> dict[str, dict]:
    from .corpus import load_recipe
    return {d["id"]: d for d in load_recipe("real-use")["documents"]}


def tokens(text: str) -> tuple[str, ...]:
    return dp.tokenize_near_duplicate(text)


def ngrams(toks, n: int = 3) -> set[tuple[str, ...]]:
    return {tuple(toks[i:i + n]) for i in range(len(toks) - n + 1)}


# --------------------------------------------------------------------------- prepare


def _monitor(root: Path, log: Path, stop: threading.Event) -> None:
    """Poll the build engine once a minute; report a queue that stopped shrinking for 15 min.

    Only responsive observations count: a paused (admission) engine does not answer and so
    never looks stalled. The build itself is left to the core lifecycle.
    """
    best, since, reported = None, time.monotonic(), False
    while not stop.wait(60):
        ready = root / "engine-ready"
        if not ready.exists():
            continue
        try:
            url = json.loads(ready.read_text())["url"]
            status = readiness.flatten_status(httpx.get(url + "/api/status", timeout=20).json())
        except (OSError, ValueError, KeyError, httpx.HTTPError) as e:
            _log(log, {"unavailable": f"{type(e).__name__}: {e}"})
            continue
        row = {k: status.get(k) for k in MONITORED}
        queue = sum(v for k in ("pendingJobs", "processingJobsCount") if isinstance(v := status.get(k), int))
        progress = (status.get("indexedDocuments") or 0, -queue)
        if best is None or progress > best:
            best, since = progress, time.monotonic()
        stalled = queue > 0 and time.monotonic() - since >= STALL_SECONDS
        _log(log, {**row, "queue": queue, "stalled": stalled})
        if stalled and not reported:
            reported = True
            atomic_write_text(log.with_name("STALLED.json"), canonical_json(
                {"at": time.time(), "seconds_without_progress": time.monotonic() - since, **row}) + "\n")


def _open(c: Collection, timeout_sec: float):
    return ix.prepare_index(c, replace(ix.IndexConfig(), timeout_sec=timeout_sec))


def prepare(ref: str, timeout_sec: float) -> int:
    c, cfg = qcoll.resolve_collection(ref)
    from . import known_answers
    known_answers.check(c, cfg, require_scored=False)
    started = time.monotonic()
    with _open(c, timeout_sec) as handle:
        log = WORK / "build" / f"{handle.index_key[:16]}.monitor.jsonl"
        _log(log, {"start": handle.index_build, "root": str(handle.root)})
        stop = threading.Event()
        thread = threading.Thread(target=_monitor, args=(handle.root, log, stop), daemon=True)
        thread.start()
        try:
            handle.wait()
        finally:
            stop.set()
        state = handle.index_state
        summary = {"index_key": handle.index_key, "root": str(handle.root),
                   "index_build": handle.index_build, "seconds": round(time.monotonic() - started, 1),
                   "counts": state.get("counts"), "generation": state.get("generation")}
    _log(log, {"done": summary})
    print(canonical_json(summary))
    return 0


# --------------------------------------------------------------------------- extract


def _live_ids(base_url: str, root: Path, c: Collection, client) -> dict[str, str]:
    m = Collection(c.name, c.language, c.queries, c.judgments,
                   ix._json(root / "materialized.json"), c.content, c.metadata)
    page = ix._post(client, "/api/debug/eval/document-ids", {"offset": 0, "limit": 50000})
    return {m.doc_id_for_location(live, root / "documents"): live for live in page["docIds"]}


PREVIEW_FIELDS = ("extractionStatus", "contentTruncated", "textProvenance", "vduStatus",
                  "extractionParserId", "extractionPolicyId", "totalChars")


def dump(base_url: str, root: Path, c: Collection) -> dict:
    """Extracted text, chunk count and extraction status of every document of a live index.

    A document the index does not hold is recorded as ``missing``; a truncated extraction as
    ``truncated`` (the core witness refuses both). Text that differs from an earlier dump is
    recorded as a ``changed`` document, so a rebuilt index is checked against the first one.
    """
    rows = _recipe_rows()
    out = WORK / "extracted"
    out.mkdir(parents=True, exist_ok=True)
    records = {r["doc"]: r for r in _jsonl(WORK / "extracted.jsonl")}
    changed = []
    with httpx.Client(base_url=base_url, timeout=600) as client:
        live = _live_ids(base_url, root, c, client)
        for n, doc in enumerate(sorted(c.documents), 1):
            d = rows[doc]
            rec = {"doc": doc, "source": d["source"], "format": d["format"],
                   "language": d["language"], "bytes": d["bytes"]}
            if doc not in live:
                rec.update(status="missing", chars=0, words=0, chunks=0, extracted_sha256=None)
            else:
                head = ix._get(client, "/api/preview", params={"docId": live[doc], "offsetChars": 0,
                                                               "maxChars": 1})
                rec["preview"] = {k: head.get(k) for k in PREVIEW_FIELDS}
                chunks = ix._get(client, "/api/debug/chunks", params={"docId": live[doc]})["chunksFound"]
                if head.get("contentTruncated") is True:
                    rec.update(status="truncated", chars=head.get("totalChars") or 0, words=0,
                               chunks=chunks, extracted_sha256=None)
                else:
                    text = ix._preview(client, live[doc], c.content[doc])
                    digest = sha256_text(text)
                    old = records.get(doc)
                    if old and old.get("extracted_sha256") and old["extracted_sha256"] != digest:
                        changed.append(doc)
                    atomic_write_text(out / f"{doc}.txt", text)
                    rec.update(status="ok", chars=len(text), words=len(text.split()), chunks=chunks,
                               extracted_sha256=digest)
            if records.get(doc) != rec:
                _log(WORK / "extracted.jsonl", rec)
            records[doc] = rec
            if n % 200 == 0:
                print(f"extracted {n}/{len(c.documents)}", file=sys.stderr, flush=True)
        status = readiness.flatten_status(ix._get(client, "/api/status"))
    report = chunk_report([r for r in records.values() if r["doc"] in c.documents],
                          status.get("chunkDocCount"))
    report["changed_since_previous_dump"] = sorted(changed)
    return report


def extract(timeout_sec: float, url: str | None = None, root: Path | None = None) -> int:
    """Dump from the prepared index (adopting it), or from an already running build engine."""
    c, _ = _collection()
    if url:
        report = dump(url, root, c)
    else:
        with _open(c, timeout_sec) as handle:
            handle.wait()
            report = dump(handle.base_url, handle.root, c)
            report["generation"] = handle.index_state.get("generation")
    _write_json(RECORD / "chunks.json", report)
    print(canonical_json({k: report[k] for k in ("documents", "engine_chunks_total", "status",
                                                 "changed_since_previous_dump")}))
    return 0


def _stats(values: list[int]) -> dict:
    s = sorted(values)
    return {"documents": len(s), "sum": sum(s), "mean": round(sum(s) / len(s), 2) if s else 0,
            "median": statistics.median(s) if s else 0,
            "p95": s[min(len(s) - 1, math.ceil(.95 * len(s)) - 1)] if s else 0, "max": s[-1] if s else 0}


def chunk_report(records: list[dict], engine_chunks: int | None = None) -> dict:
    """Extraction sizing per source and format (S29).

    ``chunksFound`` of ``/api/debug/chunks`` is what the core witness records: chunks retrieved
    for one broad query, at most 50 per document, not a document's chunk count. The engine
    publishes only the total (``chunkDocCount``); per group it is apportioned by extracted
    characters and labelled as an estimate.
    """
    total_chars = sum(r["chars"] for r in records) or 1
    by = {"source": defaultdict(list), "format": defaultdict(list), "source/format": defaultdict(list)}
    for r in records:
        for key, value in (("source", r["source"]), ("format", r["format"]),
                           ("source/format", f"{r['source']}/{r['format']}")):
            by[key][value].append(r)

    def group(rs):
        chars = sum(r["chars"] for r in rs)
        return {"documents": len(rs), "chars": _stats([r["chars"] for r in rs]),
                "debug_chunks_found_capped_50": _stats([r["chunks"] for r in rs]),
                **({"engine_chunks_estimated": round(engine_chunks * chars / total_chars)}
                   if engine_chunks else {})}
    return {"documents": len(records), "engine_chunks_total": engine_chunks,
            "debug_chunks_found_capped_50": sum(r["chunks"] for r in records),
            "extracted_chars": sum(r["chars"] for r in records),
            "status": dict(Counter(r.get("status", "ok") for r in records)),
            "not_ok": {r["doc"]: r.get("status") for r in records if r.get("status", "ok") != "ok"},
            "empty_extractions": sorted(r["doc"] for r in records if r["chars"] == 0),
            **{f"by_{key.replace('/', '_')}": {v: group(rs) for v, rs in sorted(groups.items())}
               for key, groups in by.items()}}


def load_texts() -> tuple[dict[str, dict], dict[str, str]]:
    """Extracted texts of the collection's documents (those the index holds completely)."""
    c, _ = _collection()
    records = {r["doc"]: r for r in _jsonl(WORK / "extracted.jsonl") if r["doc"] in c.documents}
    texts = {}
    for doc, r in records.items():
        if r.get("status", "ok") != "ok":
            continue
        text = (WORK / "extracted" / f"{doc}.txt").read_bytes().decode("utf-8")  # keep \r as extracted
        if sha256_text(text) != r["extracted_sha256"]:
            raise ValueError(f"extracted text of {doc} changed since extraction")
        texts[doc] = text
    return records, texts


# --------------------------------------------------------------------------- clusters

_RE_PREFIX = re.compile(r"^\s*((re|aw|fw|fwd|wg)\s*[:_ ]\s*)+", re.I)


def thread_key(title: str) -> str:
    """Enron thread: the subject without reply/forward prefixes, case- and space-folded."""
    return " ".join(_RE_PREFIX.sub("", title).casefold().split())


def eurlex_act(celex: str) -> str | None:
    """Base act of a CELEX number: 32013R0099 and 02013R0099-20200101 are act 2013R0099."""
    m = re.fullmatch(r"[03](\d{4}[A-Z]\d{4})(-\d{8})?", celex)
    return m.group(1) if m else None


def series_key(path: str) -> str:
    """A GovData series: the file name with its digits removed (yearly/monthly issues)."""
    return " ".join(re.sub(r"\d+", "#", Path(path).stem).split())


def drucksache_runs(numbers: dict[str, str]) -> list[list[str]]:
    """Runs of consecutive Drucksache numbers within one Wahlperiode (doc -> 'WP/N')."""
    parsed = sorted((int(wp), int(n), doc) for doc, v in numbers.items()
                    for wp, n in [v.split("/")])
    runs, current = [], []
    for wp, n, doc in parsed:
        if current and (wp, n - 1) == current[-1][:2]:
            current.append((wp, n, doc))
        else:
            if len(current) > 1:
                runs.append([d for *_, d in current])
            current = [(wp, n, doc)]
    if len(current) > 1:
        runs.append([d for *_, d in current])
    return runs


def families(rows: dict[str, dict]) -> list[dict]:
    out = []
    groups: dict[tuple, list[str]] = defaultdict(list)
    for doc, d in rows.items():
        if d["source"] == "eurlex" and d.get("celex") and eurlex_act(d["celex"]):
            groups[("eurlex-act", eurlex_act(d["celex"]))].append(doc)
        elif d["source"] == "enron":
            groups[("enron-thread", thread_key(d.get("title") or Path(d["path"]).stem))].append(doc)
        elif d["source"] == "govdata":
            groups[("govdata-series", series_key(d["path"]))].append(doc)
        elif d["source"] == "bundestag" and "Plenarprotokolle" in d["path"]:
            groups[("plenarprotokolle", d["printed_number"].split("/")[0])].append(doc)
    for (kind, key), members in sorted(groups.items()):
        if len(members) > 1 and key:
            out.append({"kind": kind, "members": sorted(members), "evidence": {"key": key}})
    drucksachen = {doc: d["printed_number"] for doc, d in rows.items()
                   if d["source"] == "bundestag" and "Drucksachen" in d["path"]}
    for run in drucksache_runs(drucksachen):
        out.append({"kind": "drucksachen-run", "members": sorted(run),
                    "evidence": {"numbers": [drucksachen[d] for d in run]}})
    return out


def _fingerprint(item):
    doc, text = item
    toks = tokens(text)[:SIMHASH_TOKENS]
    if not toks:
        return doc, None, frozenset()
    sh = (frozenset({("\0short", *toks)}) if len(toks) < 5 else
          frozenset(tuple(toks[i:i + 5]) for i in range(len(toks) - 4)))
    return doc, dp.simhash64(sh), sh


def near_duplicates(texts: dict[str, str], *, max_hamming: int = 6) -> list[dict]:
    """Content-exact groups plus SimHash candidates verified by 5-shingle Jaccard."""
    out = []
    exact: dict[str, list[str]] = defaultdict(list)
    for doc, text in texts.items():
        norm = dp.normalize_content(text)
        if norm:
            exact[hashlib.sha256(norm.encode()).hexdigest()].append(doc)
    for digest, members in sorted(exact.items()):
        if len(members) > 1:
            out.append({"kind": "content-exact", "members": sorted(members),
                        "evidence": {"normalized_sha256": digest}})
    with concurrent.futures.ProcessPoolExecutor(max_workers=max(1, (os.cpu_count() or 2) // 2)) as ex:
        prints = list(ex.map(_fingerprint, sorted(texts.items()), chunksize=8))
    fingerprints = {doc: fp for doc, fp, _ in prints if fp is not None}
    shingles = {doc: sh for doc, _, sh in prints}
    pairs = dp.simhash_candidate_pairs(fingerprints, max_hamming=max_hamming,
                                       max_candidate_pairs=2_000_000)
    edges, evidence = [], {}
    for a, b in sorted(pairs):
        if dp.hamming_distance(fingerprints[a], fingerprints[b]) > max_hamming:
            continue
        j = dp.jaccard_similarity(shingles[a], shingles[b])
        if j >= NEAR_JACCARD:
            edges.append((a, b))
            evidence[(a, b)] = round(j, 4)
    for members in dp.connected_components(sorted(fingerprints), edges):
        if len(members) > 1:
            inside = {f"{a} {b}": j for (a, b), j in evidence.items() if a in members and b in members}
            out.append({"kind": "near-duplicate", "members": sorted(members),
                        "evidence": {"jaccard_5shingle": inside, "max_hamming": max_hamming}})
    return out


_STOP = frozenset("""der die das und oder ein eine einer eines einem einen nicht mit von zu zum zur
im in am an auf für fur ist sind war wird werden wurde wurden auch als bei aus dem den des sich
nach über uber unter vor durch dass daß wie so es er sie wir ihr sein seine ihre bis kann können
the a an and or of to in on at for is are was were be been by with from that this as it its not
which have has had will would can may shall also any all such other their there these those""".split())


def topic_groups(texts: dict[str, str], rows: dict[str, dict]) -> list[dict]:
    """Groups of 3-6 same-language documents with TF-IDF cosine >= SIMILAR_COSINE to a seed."""
    import numpy
    from scipy import sparse

    out = []
    for lang in ("de", "en"):
        docs = sorted(d for d in texts if rows[d]["language"] == lang and texts[d].strip())
        vocab: dict[str, int] = {}
        rows_i, cols, vals = [], [], []
        for i, doc in enumerate(docs):
            counts = Counter(t for t in tokens(texts[doc])[:3000]
                             if len(t) > 2 and not t.isdigit() and t not in _STOP)
            for term, n in counts.items():
                rows_i.append(i)
                cols.append(vocab.setdefault(term, len(vocab)))
                vals.append(1 + math.log(n))
        x = sparse.csr_matrix((vals, (rows_i, cols)), shape=(len(docs), len(vocab)))
        df = numpy.bincount(x.indices, minlength=len(vocab))
        x = x.multiply(numpy.log((1 + len(docs)) / (1 + df)) + 1).tocsr()
        norms = numpy.sqrt(x.multiply(x).sum(axis=1)).A1
        x = sparse.diags(1 / numpy.where(norms == 0, 1, norms)) @ x
        sim = (x @ x.T).toarray()
        numpy.fill_diagonal(sim, 0)
        order = sorted(range(len(docs)), key=lambda i: (-(sim[i] >= SIMILAR_COSINE).sum(), docs[i]))
        used: set[int] = set()
        for i in order:
            if i in used:
                continue
            near = [j for j in numpy.argsort(-sim[i]) if sim[i][j] >= SIMILAR_COSINE and j not in used][:5]
            if len(near) < 2:
                continue
            members = [i, *near]
            used.update(members)
            out.append({"kind": "topic", "members": sorted(docs[j] for j in members),
                        "evidence": {"language": lang, "seed": docs[i],
                                     "cosine_to_seed": {docs[j]: round(float(sim[i][j]), 4) for j in near}}})
    return out


def clusters() -> int:
    records, texts = load_texts()
    rows = {d: r for d, r in _recipe_rows().items() if d in records}
    found = near_duplicates(texts) + families(rows)
    topics = topic_groups(texts, rows)
    for n, cl in enumerate(found, 1):
        cl["id"] = f"cl-{n:04d}"
    for n, cl in enumerate(topics, 1):
        cl["id"] = f"tg-{n:04d}"
    membership = defaultdict(list)
    for cl in found:
        for doc in cl["members"]:
            membership[doc].append(cl["id"])
    summary = {"documents": len(texts),
               "clusters": dict(Counter(cl["kind"] for cl in found)),
               "documents_in_clusters": len(membership),
               "documents_in_near_duplicate_clusters": len({d for cl in found if cl["kind"] in
                   ("near-duplicate", "content-exact") for d in cl["members"]}),
               "topic_groups": len(topics),
               "parameters": {"shingle_width": 5, "simhash_token_cap": SIMHASH_TOKENS,
                              "max_hamming": 6, "jaccard": NEAR_JACCARD,
                              "topic_cosine": SIMILAR_COSINE, "topic_tokens": 3000}}
    _write_json(RECORD / "clusters.json", {"summary": summary, "clusters": found, "topics": topics})
    print(canonical_json(summary))
    return 0


def load_clusters() -> dict:
    return json.loads((RECORD / "clusters.json").read_text(encoding="utf-8"))


# --------------------------------------------------------------------------- plan


def _kind_of(d: dict) -> str:
    return {"eml": "e-mail message", "pdf": "PDF document", "html": "web page or HTML document",
            "md": "Markdown note", "txt": "plain-text file", "csv": "CSV table",
            "xls": "Excel spreadsheet", "xlsx": "Excel spreadsheet", "doc": "Word document",
            "ppt": "PowerPoint presentation"}[d["format"]]


def _eligible(rec: dict) -> bool:
    return rec["chars"] >= 300 and rec["words"] >= 40


def plan() -> int:
    records, texts = load_texts()
    rows = _recipe_rows()
    cl = load_clusters()
    member_of = defaultdict(list)
    for c in cl["clusters"]:
        for doc in c["members"]:
            member_of[doc].append(c["id"])
    rng = random.Random(SEED)
    strata = {}
    for (source, lang), quota in sorted(QUOTAS.items()):
        pool = sorted(d for d, r in records.items()
                      if r["source"] == source and r["language"] == lang and _eligible(r))
        rng.shuffle(pool)
        strata[(source, lang)] = {"want": round(quota * SPARE), "pool": pool,
                                  "capacity": len({cid for d in pool for cid in member_of[d][:1]})}
    allocation = allocate_in_cluster({k: (v["want"], v["capacity"]) for k, v in strata.items()})
    targets, used_clusters = [], set()
    for key, stratum in strata.items():
        pool = stratum["pool"]
        # Cluster members first up to the allocation, singletons after; both cycle formats.
        clustered = _format_cycle([d for d in pool if member_of[d]], records)
        singles = _format_cycle([d for d in pool if not member_of[d]], records)
        picked = []
        for source_list, limit in ((clustered, allocation[key]), (singles, stratum["want"]),
                                   (clustered, stratum["want"])):
            for doc in list(source_list):
                if len(picked) >= limit:
                    break
                source_list.remove(doc)
                # One target per cluster keeps a family from repeating one known item.
                if not set(member_of[doc]) & used_clusters:
                    picked.append(doc)
                    used_clusters.update(member_of[doc])
        targets.extend(picked)
    queries = []
    rng2 = random.Random(SEED + 1)
    for n, doc in enumerate(targets):
        r, d = records[doc], rows[doc]
        level = LEVELS[n % 3]
        deep = level != "exact" and r["words"] >= DEEP_MIN_WORDS and rng2.random() < .7
        traits = []
        if level != "exact":
            traits.append("not-in-document")
        if rng2.random() < .15:
            traits.append("misspelling")
        if rng2.random() < .1:
            traits.append("other-language")
        if d["format"] == "eml":
            traits.append("mail")
        queries.append({"qid": f"ki-{n + 1:03d}", "kind": "known-item", "target": doc,
                        "level": level, "focus": "deep" if deep else "start",
                        "offset": _deep_offset(texts[doc], rng2) if deep else 0,
                        "traits": traits, "source": d["source"], "format": d["format"],
                        "language": d["language"], "clusters": member_of[doc]})
    topics = _topical_groups(cl, records, rng)
    for n, t in enumerate(topics):
        queries.append({"qid": f"tp-{n + 1:03d}", "kind": "topical", "group": t["id"],
                        "members": t["members"], "group_kind": t["kind"],
                        "language": t["language"], "sources": t["sources"]})
    _write_json(RECORD / "plan.json", {"seed": SEED, "quotas": {f"{s}/{l}": q for (s, l), q in QUOTAS.items()},
                                       "spare": SPARE, "queries": queries})
    ki = [q for q in queries if q["kind"] == "known-item"]
    print(canonical_json({"known_item": len(ki), "topical": len(topics),
                          "in_clusters": sum(1 for q in ki if q["clusters"]),
                          "deep": sum(1 for q in ki if q["focus"] == "deep"),
                          "levels": dict(Counter(q["level"] for q in ki)),
                          "languages": dict(Counter(q["language"] for q in ki))}))
    return 0


def in_collection(planned: list[dict], documents) -> list[dict]:
    """The planned queries the collection still holds the documents of.

    A known-item query whose target left the collection (an exclusion after planning) is out;
    a topical query keeps its members still present and needs two of them (never one document).
    """
    out = []
    for q in planned:
        if q["kind"] == "known-item":
            if q["target"] in documents:
                out.append(q)
        elif len(members := [d for d in q["members"] if d in documents]) > 1:
            out.append({**q, "members": members})
    return out


def load_plan(documents) -> list[dict]:
    return in_collection(json.loads((RECORD / "plan.json").read_text(encoding="utf-8"))["queries"],
                         documents)


def allocate_in_cluster(strata: dict, share: float = .5) -> dict:
    """In-cluster targets per stratum (``key -> (want, capacity)``): about ``share`` overall.

    Each stratum first takes up to its own share; the shortfall of strata with few clusters
    moves to strata with spare cluster capacity, largest spare first.
    """
    alloc = {k: min(cap, round(want * share)) for k, (want, cap) in strata.items()}
    short = round(share * sum(want for want, _ in strata.values())) - sum(alloc.values())
    while short > 0:
        spare = sorted((min(cap, want) - alloc[k], k) for k, (want, cap) in strata.items())
        gap, key = spare[-1]
        if gap <= 0:
            break
        alloc[key] += 1
        short -= 1
    return alloc


def _format_cycle(docs: list[str], records: dict) -> list[str]:
    """Interleave documents by format (formats in sorted order, documents in given order)."""
    by = defaultdict(list)
    for doc in docs:
        by[records[doc]["format"]].append(doc)
    out, formats = [], sorted(by)
    while any(by.values()):
        for fmt in formats:
            if by[fmt]:
                out.append(by[fmt].pop(0))
    return out


def _deep_offset(text: str, rng: random.Random) -> int:
    """A character offset 40-85% into the text, at a word boundary."""
    at = int(len(text) * (.4 + .45 * rng.random()))
    space = text.find(" ", at)
    return space + 1 if space >= 0 else at


def _topical_groups(cl: dict, records: dict, rng: random.Random) -> list[dict]:
    """Topic candidates: text-similarity groups and coherent families, never a single document."""
    candidates = []
    for c in cl["topics"] + [c for c in cl["clusters"] if c["kind"] in
                             ("eurlex-act", "govdata-series", "enron-thread", "near-duplicate")]:
        members = [d for d in c["members"] if d in records]
        if len(members) < 2:
            continue
        langs = Counter(records[d]["language"] for d in members)
        lang = langs.most_common(1)[0][0]
        candidates.append({"id": c["id"], "kind": c["kind"], "members": members, "language": lang,
                           "sources": sorted({records[d]["source"] for d in members})})
    rng.shuffle(candidates)
    out, per_lang, per_source = [], Counter(), Counter()
    # Spread over sources: at most a third of a language's groups lead with one source.
    for c in sorted(candidates, key=lambda c: (c["kind"] != "topic", 0)):
        lead = c["sources"][0]
        if per_lang[c["language"]] >= TOPICAL[c["language"]]:
            continue
        if per_source[(c["language"], lead)] >= TOPICAL[c["language"]] // 3:
            continue
        out.append(c)
        per_lang[c["language"]] += 1
        per_source[(c["language"], lead)] += 1
    return sorted(out, key=lambda c: c["id"])


# --------------------------------------------------------------------------- generate

LEVEL_TEXT = {
    "exact": "The person remembers the title, the number or the exact name (for example a "
             "document or regulation number, the subject line of a mail, or a table title) and "
             "types it, possibly shortened or with words left out.",
    "partial": "The person remembers one or two named entities (people, organisations, places, "
               "numbers or dates) and roughly what the file was about, but not its title. Combine "
               "an entity with a topic word.",
    "vague": "The person only half-remembers what the file was about, weeks later. Describe it "
             "in their own everyday words; use at most one name, and prefer words that do not "
             "appear in the excerpt.",
}
TRAIT_TEXT = {
    "not-in-document": "Use at least one word that does not appear in the excerpt (a synonym, a "
                       "broader term or how a person would call it).",
    "misspelling": "Include one natural typing error in one word (a swapped, missing or doubled "
                   "letter).",
    "other-language": "Write one or two of the words in {other}, as a bilingual person might.",
    "mail": "This is a mail: the person may remember the sender's or recipient's name or the "
            "subject wording.",
}
LANG_NAME = {"de": "German", "en": "English"}


def _template(name: str) -> str:
    return (RECORD / "prompts" / f"{name}.txt").read_text(encoding="utf-8")


def _excerpt(text: str, offset: int) -> str:
    part = text[offset:offset + EXCERPT_CHARS]
    return " ".join(part.split())


def known_item_prompt(q: dict, row: dict, text: str, feedback: str = "") -> str:
    traits = [TRAIT_TEXT[t].format(other=LANG_NAME["en" if q["language"] == "de" else "de"])
              for t in q["traits"]]
    rules = ("Do not copy any three consecutive words from the excerpt."
             if q["level"] != "exact" else "Titles and numbers may be typed as remembered.")
    return _template("known-item").format(
        path=row["path"], kind=_kind_of(row), language=LANG_NAME[q["language"]],
        where="from the middle or end of the file, not its beginning" if q["focus"] == "deep"
              else "from the beginning of the file",
        excerpt=_excerpt(text, q["offset"]), level=LEVEL_TEXT[q["level"]],
        traits="\n".join(f"- {t}" for t in traits) or "- none", copy_rule=rules,
        query_language=LANG_NAME[q["language"]], feedback=feedback)


def topical_prompt(q: dict, rows: dict, texts: dict, feedback: str = "") -> str:
    members = []
    for n, doc in enumerate(q["members"][:6], 1):
        members.append(f"File {n}: {rows[doc]['path']}\n<<<\n{_excerpt(texts[doc], 0)[:600]}\n>>>")
    return _template("topical").format(members="\n\n".join(members),
                                       query_language=LANG_NAME[q["language"]], feedback=feedback)


def parse_reply(stdout: str) -> dict:
    """The JSON object a ``claude -p --output-format json`` reply carries in ``result``."""
    outer = json.loads(stdout)
    if outer.get("is_error"):
        raise ValueError(f"claude error: {str(outer.get('result'))[:300]}")
    result = outer.get("result") or ""
    start = result.find("{")
    if start < 0:
        raise ValueError(f"no JSON object in reply: {result[:200]!r}")
    value, _ = json.JSONDecoder().raw_decode(result[start:])  # the first object only
    if not isinstance(value, dict):
        raise ValueError(f"reply is not a JSON object: {result[:200]!r}")
    return value


def ask_claude(prompt: str, *, timeout: float = 180) -> tuple[dict, str]:
    """One tool-less, session-less ``claude -p`` call on the subscription model."""
    with tempfile.TemporaryDirectory(prefix="ruq-") as cwd:
        r = subprocess.run(["claude", "-p", prompt, "--model", MODEL, "--output-format", "json",
                            "--tools", "", "--strict-mcp-config", "--setting-sources", "",
                            "--no-session-persistence"],
                           capture_output=True, text=True, timeout=timeout, cwd=cwd,
                           stdin=subprocess.DEVNULL)
    if r.returncode != 0 and not r.stdout:
        raise RuntimeError(f"claude exited {r.returncode}: {r.stderr[-500:]}")
    return parse_reply(r.stdout), r.stdout


def clean_query(text: str) -> str:
    return " ".join(str(text).replace('"', " ").replace("„", " ").replace("“", " ").split())


WORD_BOUNDS = {"exact": (1, 5), "partial": (2, 4), "vague": (2, 4), "topical": (2, 5)}


def word_bounds(q: dict) -> tuple[int, int]:
    """Real personal search: 2-4 terms; an exact title or number may be 1-5 words."""
    return WORD_BOUNDS[q["level"] if q["kind"] == "known-item" else "topical"]


def leak_ngrams(query: str, target_text: str) -> list[str]:
    """Every 3-token n-gram of the query that also occurs in the target's extracted text."""
    q = tokens(query)
    t = ngrams(tokens(target_text))
    return sorted(" ".join(g) for g in ngrams(q) if g in t)


def _accepted() -> dict[str, dict]:
    """The latest accepted generation per query id (a later round supersedes an earlier one)."""
    return {row["qid"]: row for row in _jsonl(RECORD / "generation.jsonl") if row.get("accepted")}


def _attempts(qid: str) -> int:
    return sum(1 for row in _jsonl(RECORD / "generation.jsonl") if row["qid"] == qid)


_GEN_LOCK = threading.Lock()


def generate_one(q: dict, rows: dict, texts: dict, *, feedback: str = "", round_: str = "initial",
                 max_attempts: int = 4) -> dict | None:
    """Generate until the verbatim-leak gate passes (partial/vague), recording every attempt.

    A rejection adds its reason to the caller's feedback (a triviality hint stays in force)."""
    base = feedback
    for attempt in range(max_attempts):
        if q["kind"] == "known-item":
            template = "known-item"
            prompt = known_item_prompt(q, rows[q["target"]], texts[q["target"]], feedback)
        else:
            template = "topical"
            prompt = topical_prompt(q, rows, texts, feedback)
        row = {"qid": q["qid"], "attempt_id": f"{q['qid']}#{_attempts(q['qid']) + 1}", "round": round_,
               "model": MODEL, "template": template,
               "template_sha256": sha256_text(_template(template)),
               "prompt_sha256": sha256_text(prompt), "params": {k: q[k] for k in q if k not in ("members",)},
               "feedback": feedback or None}
        try:
            reply, raw = ask_claude(prompt)
            row["output"] = reply
            query = reply.get("query")
            if query is None:
                row.update(accepted=False, rejected="no shared topic")
                with _GEN_LOCK:
                    _log(RECORD / "generation.jsonl", row)
                return None
            query = clean_query(query)
            row["query"] = query
            words = len(query.split())
            leak = []
            if q["kind"] == "known-item" and q["level"] != "exact":
                leak = leak_ngrams(query, texts[q["target"]])
            elif q["kind"] == "topical":
                leak = sorted({g for d in q["members"] for g in leak_ngrams(query, texts[d])})
            row["gates"] = {"verbatim_leak": leak, "words": words}
            low, high = word_bounds(q)
            if not low <= words <= high:
                row.update(accepted=False, rejected=f"{words} words")
                feedback = " ".join(filter(None, (base, (
                    f"Your previous query \"{query}\" had {words} words ("
                    + ", ".join(f"{n}. {w}" for n, w in enumerate(query.split(), 1))
                    + f"). It must have {low} to {high} words: "
                    + ("drop words until at most " + str(high) + " remain." if words > high
                       else "add a word.")))))
            elif leak:
                row.update(accepted=False, rejected="verbatim leak")
                feedback = " ".join(filter(None, (base, "Your previous query copied these word "
                                               "sequences from the file: " + "; ".join(leak)
                                               + ". Rephrase without them.")))
            else:
                row["accepted"] = True
        except (subprocess.TimeoutExpired, RuntimeError, ValueError) as e:
            row.update(accepted=False, error=f"{type(e).__name__}: {e}")
            if "limit" in str(e).lower():
                with _GEN_LOCK:
                    _log(RECORD / "generation.jsonl", row)
                raise UsageLimit(str(e)) from e
        with _GEN_LOCK:
            _log(RECORD / "generation.jsonl", row)
        if row.get("accepted"):
            return row
    return None


def _retry_feedback(q: dict, row: dict | None) -> str:
    if row is None:
        return ""
    low, high = word_bounds(q)
    return (f"An earlier query for this was \"{row['query']}\". Write a new one with {max(2, low)} "
            f"to {high} words, like a real search box entry.")


class UsageLimit(RuntimeError):
    pass


def meets_gates(q: dict, row: dict, texts: dict) -> bool:
    """An accepted generation that still meets the current offline gates."""
    low, high = word_bounds(q)
    if not low <= len(row["query"].split()) <= high:
        return False
    if q["kind"] == "known-item":
        return q["level"] == "exact" or not leak_ngrams(row["query"], texts[q["target"]])
    return not any(leak_ngrams(row["query"], texts[d]) for d in q["members"])


def generate(concurrency: int, retry: bool = False) -> int:
    """First generation of every planned query; ``retry`` regenerates every query without an
    accepted generation that meets the current gates (earlier attempts stay recorded)."""
    records, texts = load_texts()
    rows = _recipe_rows()
    planned = load_plan(records)
    done = _accepted()
    attempted = {row["qid"] for row in _jsonl(RECORD / "generation.jsonl")}
    if retry:
        todo = [q for q in planned if q["qid"] not in done or not meets_gates(q, done[q["qid"]], texts)]
    else:
        todo = [q for q in planned if q["qid"] not in done and q["qid"] not in attempted]
    print(f"{len(todo)} queries to generate ({len(done)} accepted)", file=sys.stderr, flush=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as ex:
        futures = {ex.submit(generate_one, q, rows, texts, round_="retry" if retry else "initial",
                             feedback=_retry_feedback(q, done.get(q["qid"])) if retry else ""): q
                   for q in todo}
        for n, f in enumerate(concurrent.futures.as_completed(futures), 1):
            f.result()
            if n % 20 == 0:
                print(f"generated {n}/{len(todo)}", file=sys.stderr, flush=True)
    done = _accepted()
    print(canonical_json({"planned": len(planned), "accepted": len(done)}))
    return 0


# --------------------------------------------------------------------------- gate


class _Ranker:
    """Delivered hit locations -> doc ids through an index entry's materialization manifest."""

    def __init__(self, root: Path, c: Collection):
        self.m = Collection(c.name, c.language, c.queries, c.judgments,
                            ix._json(root / "materialized.json"), c.content, c.metadata)
        self.documents = root / "documents"

    def ranked_doc_ids(self, locations) -> list[str]:
        return self.m.ranked_doc_ids(locations, self.documents)


def lexical_ranks(handle, client, items: list[tuple[str, str, str]]) -> dict[str, int | None]:
    """Lexical-only (``mode: text``, the refined request otherwise) rank of each target."""
    from .run import request_for
    request = request_for("text")
    out = {}
    for qid, text, target in items:
        for attempt in range(5):
            try:
                body = ix._post(client, "/api/knowledge/search", {**request, "query": text})
                break
            except httpx.HTTPError:
                if attempt == 4:
                    raise
                time.sleep(10)
        locations = [(h.get("fields") or {}).get("path") or h.get("id") for h in body.get("results", [])]
        ranked = handle.ranked_doc_ids(locations)
        out[qid] = ranked.index(target) + 1 if target in ranked else None
    return out


def gate(timeout_sec: float, rounds: int, url: str | None = None, root: Path | None = None) -> int:
    """Triviality loop on the prepared index (adopting it) or on a running build engine."""
    c, _ = _collection()
    if url:
        report = _gate(c, url, _Ranker(root, c), rounds)
        report["engine"] = "build engine (lexical index complete)"
    else:
        with _open(c, timeout_sec) as handle:
            handle.wait()
            report = _gate(c, handle.base_url, handle, rounds)
            report["generation"] = handle.index_state.get("generation")
    _write_json(RECORD / "gates.json", report)
    print(canonical_json(report["summary"]))
    return 0


def _gate(c: Collection, base_url: str, handle, rounds: int) -> dict:
    records, texts = load_texts()
    rows = _recipe_rows()
    planned = {q["qid"]: q for q in load_plan(c.documents)}
    df = Counter(t for text in texts.values() for t in set(tokens(text)))
    results = {}
    with httpx.Client(base_url=base_url, timeout=600) as client:
        for rnd in range(rounds + 1):
            done = {q: r for q, r in _accepted().items() if q in planned and meets_gates(planned[q], r, texts)}
            ki = sorted(qid for qid in done if planned[qid]["kind"] == "known-item")
            ranks = lexical_ranks(handle, client, [(qid, done[qid]["query"], planned[qid]["target"])
                                                   for qid in ki if qid not in results
                                                   or results[qid]["query"] != done[qid]["query"]])
            for qid, rank in ranks.items():
                results[qid] = {"query": done[qid]["query"], "rank": rank}
            over = {}
            for level in ("partial", "vague"):
                ids = [q for q in ki if planned[q]["level"] == level]
                first = sorted(q for q in ids if results[q]["rank"] == 1)
                excess = len(first) - len(ids) // 2
                if excess > 0:
                    # Twice the excess per round (each regeneration may stay rank 1).
                    over[level] = random.Random(SEED + rnd).sample(first, min(len(first), 2 * excess))
            print(canonical_json({"round": rnd, "rank1": {lv: sum(1 for q in ki if planned[q]["level"] == lv
                                                                  and results[q]["rank"] == 1) for lv in LEVELS},
                                  "excess": {k: len(v) for k, v in over.items()}}), file=sys.stderr, flush=True)
            if not over or rnd == rounds:
                break
            for level, qids in over.items():
                for qid in qids:
                    # An accepted regeneration supersedes the earlier query; a failed one keeps it.
                    target = planned[qid]["target"]
                    generate_one(planned[qid], rows, texts, round_=f"triviality-{rnd + 1}",
                                 feedback=triviality_feedback(planned[qid], done[qid]["query"], rare_words(
                                     done[qid]["query"], texts[target], df)))
    done = {q: r for q, r in _accepted().items() if q in planned and meets_gates(planned[q], r, texts)}
    return gate_report(planned, done, results, records, texts, rows)


RARE_DF = 5  # a query word found in at most this many documents points straight at its file


def rare_words(query: str, target_text: str, df: Counter) -> list[str]:
    """Query words that occur in the target and in at most ``RARE_DF`` documents."""
    present = set(tokens(target_text))
    return [t for t in dict.fromkeys(tokens(query)) if t in present and df[t] <= RARE_DF]


def triviality_feedback(q: dict, query: str, rare: list[str]) -> str:
    """Regeneration hint for a partial/vague query whose target plain keyword search ranks first."""
    if rare:
        hint = ("These words occur in almost no other file and point straight at it: "
                + ", ".join(rare) + ". Keep at most one of them; replace the others with what a "
                "person would recall instead (a broader term, a synonym, the organisation or "
                "place, or a half-remembered spelling).")
    else:
        hint = ("Write how a person would recall it weeks later, without its most distinctive "
                "literal words.")
    return (f"Your previous query \"{query}\" finds the file with plain keyword search alone. "
            f"{hint} Do not repeat the previous query.")


def gate_report(planned, done, results, records, texts, rows) -> dict:
    per_query = {}
    for qid, row in sorted(done.items()):
        q = planned[qid]
        entry = {"kind": q["kind"], "query": row["query"], "verbatim_leak": row["gates"]["verbatim_leak"],
                 "words": row["gates"]["words"]}
        if q["kind"] == "known-item":
            toks = set(tokens(texts[q["target"]]))
            qt = tokens(row["query"])
            entry.update(level=q["level"], lexical_rank=results.get(qid, {}).get("rank"),
                         absent_word_share=round(sum(1 for t in qt if t not in toks) / max(1, len(qt)), 3))
        per_query[qid] = entry
    ki = [qid for qid in per_query if planned[qid]["kind"] == "known-item"]

    def stratum(key):
        groups = defaultdict(list)
        for qid in ki:
            q = planned[qid]
            value = {"in_cluster": "in-cluster" if q["clusters"] else "singleton"}.get(key) or q[key]
            groups[value].append(per_query[qid])
        return {v: {"queries": len(es), "rank1": sum(1 for e in es if e["lexical_rank"] == 1),
                    "rank1_share": round(sum(1 for e in es if e["lexical_rank"] == 1) / len(es), 3),
                    "not_in_top50": sum(1 for e in es if e["lexical_rank"] is None),
                    "median_rank": statistics.median([e["lexical_rank"] or 51 for e in es]),
                    "verbatim_leaks": sum(1 for e in es if e["verbatim_leak"] and e["level"] != "exact")}
                for v, es in sorted(groups.items())}

    strata = {k: stratum(k) for k in ("level", "source", "format", "language", "in_cluster", "focus")}
    triviality = {lv: {**strata["level"].get(lv, {}),
                       "passed": strata["level"].get(lv, {}).get("rank1_share", 0) <= .5 if lv != "exact" else None}
                  for lv in LEVELS}
    leak_failures = [qid for qid in ki if planned[qid]["level"] != "exact" and per_query[qid]["verbatim_leak"]]
    leak_failures += [qid for qid in per_query if planned[qid]["kind"] == "topical" and per_query[qid]["verbatim_leak"]]
    distinguish = distinguishability([planned[q]["target"] for q in ki], records, texts)
    summary = {"accepted": len(per_query), "known_item": len(ki),
               "topical": len(per_query) - len(ki), "verbatim_leak_failures": len(leak_failures),
               "triviality": {lv: {k: triviality[lv].get(k) for k in ("queries", "rank1", "rank1_share", "passed")}
                              for lv in LEVELS},
               "distinguishability_passed": {s: v["passed"] for s, v in distinguish.items()}}
    return {"summary": summary, "strata": strata, "distinguishability": distinguish, "queries": per_query}


def distinguishability(targets: list[str], records: dict, texts: dict, *, cap_tokens: int = 2000) -> dict:
    """Targets vs. the other documents of their source: length (full text) and boilerplate
    n-grams (``corpus_leak`` length profile; 5-gram selectivity over the first ``cap_tokens``)."""
    out = {}
    chosen = set(targets)
    for source in sorted({records[t]["source"] for t in targets}):
        ids = [d for d in sorted(records) if records[d]["source"] == source and d in texts]
        queries = [{"evidence_ids": [t]} for t in sorted(chosen) if records[t]["source"] == source]
        length = corpus_leak.length_profile_report([{"_id": d, "text": texts[d]} for d in ids], queries)
        grams = corpus_leak.ngram_selectivity_report(
            [{"_id": d, "text": " ".join(texts[d].split()[:cap_tokens])} for d in ids], queries, n=5, top_k=3)
        out[source] = {"targets": len(queries), "others": length["n_native"],
                       "length_separability": length["separability"],
                       "target_median_words": length["gold"]["median"],
                       "other_median_words": length["native"]["median"],
                       "max_target_5gram_coverage": grams["max_gold_coverage"],
                       "native_base_rate": grams["native_base_rate"],
                       "passed": bool(length["passed"]) and bool(grams["passed"]),
                       "token_cap": cap_tokens}
    return out


# --------------------------------------------------------------------------- write


def write() -> int:
    c, cfg = _collection()
    planned = {q["qid"]: q for q in load_plan(c.documents)}
    done = {q: r for q, r in _accepted().items() if q in planned}
    gates = json.loads((RECORD / "gates.json").read_text(encoding="utf-8"))["queries"]
    seen, queries, judgments = set(), {}, {}
    by_content = defaultdict(list)
    for doc, h in c.content.items():
        by_content[h].append(doc)
    for qid in sorted(done):
        q, text = planned[qid], done[qid]["query"]
        if qid not in gates or gates[qid]["query"] != text or text.casefold() in seen:
            continue
        seen.add(text.casefold())
        if q["kind"] == "known-item":
            groups = {"lang": q["language"], "kind": "known-item", "level": q["level"],
                      "source": q["source"], "format": q["format"], "focus": q["focus"],
                      "cluster": "in-cluster" if q["clusters"] else "singleton"}
            judgments[qid] = {q["target"]: 2, **{d: 1 for d in by_content[c.content[q["target"]]]
                                                 if d != q["target"]}}
        else:
            groups = {"lang": q["language"], "kind": "topical", "group": q["group"]}
        queries[qid] = QueryText(text, groups)
    new = Collection(c.name, c.language, queries, judgments, dict(c.documents), dict(c.content),
                     dict(c.metadata))
    if new.document_identity() != c.document_identity():
        raise ValueError("documents changed while writing queries")
    qcoll.write_collection(new, Path(__file__).with_name("recipes") / COLLECTION)
    pending = len(qcoll.pending_queries(new, cfg))
    counts = {**new.counts(), "pending": pending}
    config_path = qcoll.CONFIG_DIR / f"{COLLECTION}.json"
    raw = json.loads(config_path.read_text(encoding="utf-8"))
    raw["expected"] = {k: counts[k] for k in ("queries", "pending", "judgments", "positives", "grades", "documents")}
    atomic_write_text(config_path, json.dumps(raw, ensure_ascii=False, indent=2) + "\n")
    print(canonical_json(counts))
    return 0


# --------------------------------------------------------------------------- cli


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    steps = parser.add_subparsers(dest="step", required=True)
    p = steps.add_parser("prepare")
    p.add_argument("collection", nargs="?", default=COLLECTION)
    p.add_argument("--timeout-sec", type=float, default=12 * 3600)
    p = steps.add_parser("extract")
    p.add_argument("--timeout-sec", type=float, default=3 * 3600)
    p.add_argument("--url", help="a running build engine of this collection (with --root)")
    p.add_argument("--root", type=Path, help="that engine's index entry root")
    steps.add_parser("clusters")
    steps.add_parser("plan")
    p = steps.add_parser("generate")
    p.add_argument("--concurrency", type=int, default=4)
    p.add_argument("--retry", action="store_true", help="regenerate queries failing the current gates")
    p = steps.add_parser("gate")
    p.add_argument("--timeout-sec", type=float, default=3 * 3600)
    p.add_argument("--rounds", type=int, default=3)
    p.add_argument("--url", help="a running build engine of this collection (with --root)")
    p.add_argument("--root", type=Path, help="that engine's index entry root")
    steps.add_parser("write")
    args = parser.parse_args(argv)
    if args.step == "prepare":
        return prepare(args.collection, args.timeout_sec)
    if args.step == "extract":
        return extract(args.timeout_sec, args.url, args.root)
    if args.step == "generate":
        return generate(args.concurrency, args.retry)
    if args.step == "gate":
        return gate(args.timeout_sec, args.rounds, args.url, args.root)
    return {"clusters": clusters, "plan": plan, "write": write}[args.step]()


if __name__ == "__main__":
    sys.exit(main())
