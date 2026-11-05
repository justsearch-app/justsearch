"""Queries for the public real-use collection (S30): extraction, clusters, targets, writing, gates.

Every step is resumable. Derived document text stays under ``WORK`` and never enters Git; the
committed record (``recipes/real-use-queries/``) holds prompt templates, prompt hashes, validated
queries, document ids, cluster evidence and safe gate metadata only.

``prepare``   build or adopt the full index through the core's pausable background lifecycle,
              with a queue monitor that reports a stalled queue instead of waiting.
``extract``   read each document's extracted text and chunk count from the prepared index's
              live debug API (as the core witness does); report chunks per source and format.
``clusters``  near-duplicate clusters (exact + SimHash/Jaccard, ``duplicate_prevalence``), the
              recipe's natural families, and text-similarity topic groups.
``plan``      stratified known-item targets and topical groups, with recall levels and traits.
``subset``    shrink the collection to about 1,000 files: every target, its byte copies and
              clusters, every topical group, and a fixed-seed sample by source, format, language.
``generate``  write queries through ``claude -p`` (subscription, no tools); verbatim-leak gate
              with regeneration.
``gate``      drop queries failing the offline gates and repeated query texts (each drop
              recorded with its reason); measure and record, never gate, triviality: the
              keyword-search-alone rank (lexical leg; reranker and every model stage off) of
              every known-item target on the prepared index, per query and per recall level;
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
POLL_SECONDS = 15
MONITORED = ("indexState", "indexedDocuments", "pendingJobs", "pendingJobsCount",
             "processingJobsCount", "embeddingPendingCount", "chunkEmbeddingPendingCount",
             "pendingNerCount", "failedNerCount", "chunkDocCount", "embeddingDocCount",
             "spladeEnabled", "spladePendingCount", "chunkSpladePendingCount", "pendingVduCount",
             "batchTiming")
LEVELS = ("exact", "partial", "vague")
# Known-item targets per (source, language): about 180, half German, every source.
QUOTAS = {("bundestag", "de"): 30, ("eurlex", "de"): 15, ("eurlex", "en"): 15,
          ("safedocs", "de"): 20, ("govdata", "de"): 15, ("wikipedia", "de"): 10,
          ("wikipedia", "en"): 15, ("enron", "en"): 30, ("govdocs1", "en"): 30}
SPARE = 1.1  # planned per quota: leak drops leave about the quota
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


def _paused(root: Path) -> bool:
    """Whether admission paused the build worker (its process is stopped, ``SIGSTOP``)."""
    try:
        pid = json.loads((root / "worker.json").read_text())["pid"]
        return Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()[0] in ("T", "t")
    except (OSError, ValueError, KeyError, IndexError):
        return False


def _monitor(root: Path, log: Path, stop: threading.Event) -> None:
    """Poll the build engine every ``POLL_SECONDS``; report a queue that stopped shrinking.

    A paused (admission) build is logged as ``paused`` and never looks stalled. The build
    itself is left to the core lifecycle.
    """
    best, since, reported = None, time.monotonic(), False
    while not stop.wait(POLL_SECONDS):
        if _paused(root):
            since = time.monotonic()
            _log(log, {"paused": True})
            continue
        ready = root / "engine-ready"
        if not ready.exists():
            _log(log, {"starting": True})
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


STAGES = (
    # (stage, done when) over responsive monitor rows; stages overlap in the engine, so each is
    # reported as the window from the previous stage's end to its own end.
    ("extraction_ocr", lambda r, n: r.get("queue") == 0 and r.get("indexedDocuments") == n),
    ("embedding", lambda r, n: r.get("embeddingPendingCount") == 0 and r.get("chunkEmbeddingPendingCount") == 0),
    # Document SPLADE's pending count stays at the document count while it is disabled (the
    # core disables it); it counts only when the engine reports it enabled.
    ("other_enrichment", lambda r, n: r.get("pendingNerCount") == 0
     and (r.get("spladeEnabled") is not True or r.get("spladePendingCount") == 0)
     and (r.get("chunkSpladePendingCount") or 0) == 0),
)


def stage_timing(rows: list[dict], documents: int) -> dict:
    """Per-stage wall time of a build from its monitor log (``start`` row to ``done`` row).

    Each stage ends at the first poll that shows it complete (and every earlier stage
    complete); its window starts where the previous one ended. Paused polls are counted
    per window. ``engine_batch_ms`` is the engine's own enrichment batch timing.
    """
    start = [r["at"] for r in rows if "start" in r][-1]  # the latest preparation in this log
    end = next((r["at"] for r in rows if "done" in r and r["at"] >= start), rows[-1]["at"])
    polls = [r for r in rows if start <= r["at"] <= end]
    marks, at = {}, start
    first_engine = next((r["at"] for r in polls if "queue" in r), None)
    for name, done in STAGES:
        hit = next((r["at"] for r in polls if r["at"] >= at and "queue" in r and done(r, documents)), None)
        if hit is None:
            break
        marks[name] = hit
        at = hit
    windows, prev = {}, start
    edges = [("startup", first_engine)] if first_engine else []
    for name, mark in [*edges, *marks.items(), ("confirmation", end)]:
        if mark is None or mark < prev:
            continue
        paused = [r for r in polls if prev < r["at"] <= mark and r.get("paused")]
        windows[name] = {"seconds": round(mark - prev, 1),
                         "paused_seconds_approx": round(len(paused) * POLL_SECONDS, 1)}
        prev = mark
    last = next((r for r in reversed(polls) if "queue" in r), {})
    return {"documents": documents, "total_seconds": round(end - start, 1),
            "paused_seconds_approx": round(sum(1 for r in polls if r.get("paused")) * POLL_SECONDS, 1),
            "poll_seconds": POLL_SECONDS, "stages": windows,
            "incomplete": [name for name, _ in STAGES if name not in marks],
            "engine_batch_ms": (last.get("batchTiming") or {}).get("totalMs"),
            "pending_vdu_at_end": last.get("pendingVduCount")}


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
    if summary["index_build"] == "fresh":
        summary["timing"] = stage_timing(_jsonl(log), len(c.documents))
        _write_json(log.with_name(f"{handle.index_key[:16]}.timing.json"), summary["timing"])
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


# --------------------------------------------------------------------------- subset

SUBSET_SIZE = 1000
KEPT_CAP = 800  # kept sets beyond this would need proportional topical caps (not needed so far)
OCR_ROUTES = ("ocr_full", "ocr_selective")


def population(rows: dict[str, dict]) -> set[str]:
    """The recipe's files the engine held completely: the recipe minus ``excluded.json``."""
    excluded = json.loads((RECORD / "excluded.json").read_text(encoding="utf-8"))["documents"]
    return set(rows) - {e["doc"] for e in excluded}


def stratum_of(d: dict) -> str:
    return f"{d['source']}/{d['format']}/{d['language']}"


def select_subset(rows: dict[str, dict], documents: set[str], content: dict[str, str],
                  planned: list[dict], clusters: dict, ocr: set[str], *,
                  size: int = SUBSET_SIZE, seed: int = SEED) -> dict:
    """The subset of ``documents`` the queries stay hardest on (S30), about ``size`` files.

    Kept: every known-item target, its byte-identical copies, every member of each cluster a
    target belongs to, and every member of each topical query's group. Then a fixed-seed sample
    per (source, format, language) stratum: first as many files as the stratum has targets,
    then a fill up to ``size`` that moves each stratum toward its share of ``documents``.
    OCR-routed PDFs are never sampled (only kept).
    """
    by_id = {c["id"]: c for c in clusters["clusters"] + clusters["topics"]}
    by_content = defaultdict(list)
    for doc in documents:
        by_content[content[doc]].append(doc)
    kept: dict[str, list[str]] = defaultdict(list)
    for q in in_collection(planned, documents):
        if q["kind"] == "known-item":
            kept[q["target"]].append(f"target:{q['qid']}")
            for doc in by_content[content[q["target"]]]:
                if doc != q["target"]:
                    kept[doc].append(f"byte-duplicate:{q['qid']}")
            for cid in q["clusters"]:
                for doc in by_id[cid]["members"]:
                    if doc in documents:
                        kept[doc].append(f"target-cluster:{cid}")
        else:
            for doc in q["members"]:
                kept[doc].append(f"topical:{q['qid']}")
    if len(kept) > KEPT_CAP:
        raise ValueError(f"kept sets hold {len(kept)} files (> {KEPT_CAP}); topical groups need caps")
    full = Counter(stratum_of(rows[d]) for d in documents)
    have = Counter(stratum_of(rows[d]) for d in kept)
    pools = {}
    for s in sorted(full):
        pool = sorted(d for d in documents if stratum_of(rows[d]) == s and d not in kept and d not in ocr)
        random.Random(f"{seed}/{s}").shuffle(pool)
        pools[s] = pool
    # Every stratum first gets as many sampled (non-target) files as it has targets, so each
    # source's targets keep others to be told apart from (distinguishability); then one file
    # at a time to the stratum furthest below its share (ties: stratum name).
    targets = Counter(stratum_of(rows[d]) for d, why in kept.items()
                      if any(r.startswith("target:") for r in why))
    take = Counter({s: min(targets[s], len(pools[s])) for s in full})
    for _ in range(max(0, size - len(kept) - sum(take.values()))):
        open_ = [s for s in full if take[s] < len(pools[s])]
        if not open_:
            break
        s = max(open_, key=lambda s: (size * full[s] / len(documents) - have[s] - take[s], s))
        take[s] += 1
    sampled = sorted(d for s in full for d in pools[s][:take[s]])
    chosen = set(kept) | set(sampled)
    strata = {s: {"full": full[s], "kept": have[s], "targets": targets[s], "sampled": take[s],
                  "subset": have[s] + take[s], "share_full": round(full[s] / len(documents), 4),
                  "share_subset": round((have[s] + take[s]) / len(chosen), 4)} for s in sorted(full)}
    return {"seed": seed, "size": size, "population": len(documents), "documents": len(chosen),
            "kept": {d: sorted(set(r)) for d, r in sorted(kept.items())}, "sampled": sampled,
            "ocr_routed_in_subset": sorted(chosen & ocr), "strata": strata}


def subset(size: int) -> int:
    """Shrink the stored collection to the selection; queries and judgments stay as they are.

    Refuses when ``subset.json`` exists: the selection depends on the stored queries, so a
    rerun after queries were dropped would silently change the corpus and its index.
    """
    if (RECORD / "subset.json").exists():
        print(f"subset refused: {RECORD / 'subset.json'} already fixes the collection's documents; "
              "selecting again would change the corpus. Remove it deliberately to reselect.",
              file=sys.stderr)
        return 2
    c, _ = _collection()
    rows = _recipe_rows()
    full = population(rows)
    evidence = json.loads((WORK / "build" / "second-build-ocr-evidence.json").read_text(encoding="utf-8"))
    ocr = {d for d, e in evidence.items() if e["route"] in OCR_ROUTES and d in full}
    planned = [q for q in json.loads((RECORD / "plan.json").read_text(encoding="utf-8"))["queries"]
               if q["qid"] in c.queries]  # the stored queries, not every planned one
    content = {d: rows[d]["sha256"] for d in full}
    chosen = select_subset(rows, full, content, planned, load_clusters(), ocr, size=size)
    chosen["ocr_routed_in_population"] = sorted(ocr)
    chosen["ocr_evidence"] = "second build visual-extraction route (ocr_full, ocr_selective)"
    keep = set(chosen["kept"]) | set(chosen["sampled"])
    missing = {q for q, judged in c.judgments.items() for d in judged if d not in keep}
    missing |= {q for q in c.queries if c.queries[q].groups.get("kind") == "topical"
                and q not in {p["qid"] for p in in_collection(planned, keep)}}
    if missing:
        raise ValueError(f"subset drops documents of stored queries: {sorted(missing)[:5]}")
    _write_json(RECORD / "subset.json", chosen)
    new = Collection(c.name, c.language, dict(c.queries), dict(c.judgments),
                     {d: rows[d]["path"] for d in keep}, {d: content[d] for d in keep}, dict(c.metadata))
    qcoll.write_collection(new, Path(__file__).with_name("recipes") / COLLECTION)
    config_path = qcoll.CONFIG_DIR / f"{COLLECTION}.json"
    raw = json.loads(config_path.read_text(encoding="utf-8"))
    raw["expected"]["documents"] = len(keep)
    atomic_write_text(config_path, json.dumps(raw, ensure_ascii=False, indent=2) + "\n")
    print(canonical_json({k: chosen[k] for k in ("population", "documents")}
                         | {"kept": len(chosen["kept"]), "sampled": len(chosen["sampled"]),
                            "ocr_routed_in_subset": len(chosen["ocr_routed_in_subset"])}))
    return 0


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


# Reviewed against Claude Code 2.1.316 and https://code.claude.com/docs/en/env-vars.
# Reject presence, including empty values; provider/auth selection must not be ambiguous.
# ANTHROPIC_ includes API keys, custom headers/endpoints, profiles and federation credentials.
AUTH_OVERRIDE_PREFIXES = ("ANTHROPIC_", "CLAUDE_CODE_USE_")
AUTH_OVERRIDES = frozenset({"AWS_BEARER_TOKEN_BEDROCK", "CLAUDE_CODE_API_BASE_URL",
                            "CLAUDE_CODE_API_KEY_FILE_DESCRIPTOR", "CLAUDE_CODE_CUSTOM_OAUTH_URL"})


class SubscriptionRequired(RuntimeError):
    pass


def ask_claude(prompt: str, *, timeout: float = 180) -> tuple[dict, str]:
    """One tool-less, session-less ``claude -p`` call on the subscription model."""
    env = dict(os.environ)
    forbidden = {name for name in env
                 if name.startswith(AUTH_OVERRIDE_PREFIXES) or name in AUTH_OVERRIDES}
    if forbidden:
        raise SubscriptionRequired("Claude subscription required; remove API/provider overrides")
    env = {k: v for k, v in env.items()
           if not k.startswith(AUTH_OVERRIDE_PREFIXES) and k not in AUTH_OVERRIDES}
    env["CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC"] = "1"
    WORK.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="ruq-", dir=WORK) as cwd:
        # Even inherited debug settings must never direct prompt-bearing logs into Git.
        env["CLAUDE_CODE_DEBUG_LOGS_DIR"] = str(Path(cwd) / "claude.debug.log")
        # This CLI's JSON status path reads cached credentials, without refreshing or querying
        # the network. Use the same settings, environment and cwd as generation. Unknown or
        # unavailable status fails closed; a console OAuth login is not a subscription.
        try:
            status = subprocess.run(["claude", "--setting-sources", "", "auth", "status", "--json"],
                                    capture_output=True, text=True, timeout=30, cwd=cwd, env=env,
                                    stdin=subprocess.DEVNULL)
            auth = json.loads(status.stdout)
            subscribed = (status.returncode == 0 and isinstance(auth, dict)
                          and auth.get("loggedIn") is True and auth.get("authMethod") == "claude.ai"
                          and auth.get("apiProvider") == "firstParty"
                          and auth.get("subscriptionType") in ("pro", "max", "ultra", "team", "enterprise"))
        except (OSError, subprocess.SubprocessError, ValueError):
            subscribed = False
        if not subscribed:
            raise SubscriptionRequired("Claude subscription authentication could not be verified") from None
        command = ["claude", "-p", prompt, "--model", MODEL, "--output-format", "json",
                   "--tools", "", "--strict-mcp-config", "--setting-sources", "",
                   "--no-session-persistence"]
        r = subprocess.run(command,
                           capture_output=True, text=True, timeout=timeout, cwd=cwd,
                           stdin=subprocess.DEVNULL, env=env)
    if r.returncode != 0 and not r.stdout:
        raise subprocess.CalledProcessError(r.returncode, command, output=r.stdout, stderr=r.stderr)
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


def _generation_record(row: dict) -> dict:
    """Allow-listed public evidence; unrestricted replies and retry hints belong in WORK."""
    out = {k: row[k] for k in ("at", "qid", "attempt_id", "round", "model", "template",
                               "template_sha256", "prompt_sha256", "accepted") if k in row}
    out["params"] = {k: row["params"][k] for k in (
        "qid", "kind", "language", "clusters", "focus", "format", "level", "offset",
        "source", "target", "traits", "group", "group_kind", "sources") if k in row["params"]}
    feedback = row.get("feedback")
    out["feedback"] = feedback if isinstance(feedback, str) and re.fullmatch(
        r"Your previous query had [0-9]+ words; use [0-9]+ to [0-9]+\.", feedback) else None
    if row.get("accepted"):
        out["query"] = row["query"]
        out["output"] = {"query": row["query"]}  # the model's topic text stays in WORK
    if "gates" in row:
        out["gates"] = {"words": row["gates"]["words"]}
        if row.get("accepted"):
            out["gates"]["verbatim_leak"] = []
        else:
            out["gates"]["verbatim_leak_count"] = row["gates"].get(
                "verbatim_leak_count", len(row["gates"].get("verbatim_leak", [])))
    if "rejected" in row:
        reason = row["rejected"]
        out["rejected"] = reason if reason in ("no shared topic", "verbatim leak", "invalid query") \
            or re.fullmatch(r"[0-9]+ words", reason) else "generation failed"
    if "error" in row:
        error = row["error"]
        out["error"] = {k: error[k] for k in ("type", "returncode", "elapsed_seconds")
                        if isinstance(error, dict) and k in error}
        out["error"]["reason"] = "generation failed"
    return out


def _record_generation(row: dict) -> dict:
    public = _generation_record(row)
    with _GEN_LOCK:
        _log(WORK / "generation.jsonl", row)
        _log(RECORD / "generation.jsonl", public)
    return public


def generate_one(q: dict, rows: dict, texts: dict, *, feedback: str = "", round_: str = "initial",
                 max_attempts: int = 4) -> dict | None:
    """Generate until the verbatim-leak gate passes (partial/vague), recording every attempt.

    A rejection adds its reason to the caller's feedback (which stays in force)."""
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
        started = time.monotonic()
        try:
            reply, raw = ask_claude(prompt)
            row["output"] = reply
            row["raw"] = raw
            query = reply.get("query")
            if query is None:
                row.update(accepted=False, rejected="no shared topic")
                _record_generation(row)
                return None
            if not isinstance(query, str):
                row.update(accepted=False, rejected="invalid query")
                _record_generation(row)
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
        except (subprocess.SubprocessError, OSError, RuntimeError, ValueError) as e:
            # Subprocess exceptions include the prompt in cmd/args and may carry document
            # text in stdout/stderr. No exception message or subprocess output enters Git.
            row.update(accepted=False, error={"type": type(e).__name__,
                       "returncode": e.returncode if isinstance(e, subprocess.CalledProcessError) else None,
                       "elapsed_seconds": round(time.monotonic() - started, 3),
                       "reason": "generation failed"})
            row["error_detail"] = str(e)
            if isinstance(e, SubscriptionRequired):
                _record_generation(row)
                raise
            if isinstance(e, (RuntimeError, ValueError)) and "limit" in str(e).lower():
                _record_generation(row)
                raise UsageLimit("Claude usage limit reached") from None
        public = _record_generation(row)
        if row.get("accepted"):
            return public
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


# Keyword search alone (S30 triviality): the lexical leg, with the cross-encoder reranker,
# LambdaMART, query expansion and freshness off, so a rank depends only on the engine and the
# documents. Each response's trace must show it (``KEYWORD_OFF`` stages never executed),
# and query understanding must not have applied model-derived boosts.
KEYWORD_PIPELINE = {"sparseEnabled": True, "denseEnabled": False, "spladeEnabled": False,
                    "fusionAlgorithm": "none", "lambdamartEnabled": False,
                    "crossEncoderEnabled": False, "crossEncoderWindow": 0,
                    "expansionEnabled": False, "freshnessEnabled": False}
KEYWORD_OFF = ("dense-retrieval", "splade-retrieval", "expansion", "lambdamart", "cross-encoder",
               "freshness")


def keyword_stages(body: dict) -> dict[str, str | None]:
    """The trace stage statuses of a keyword-alone response; refuses any other pipeline."""
    stages = {st.get("id"): st.get("status") for st in (body.get("searchTrace") or {}).get("stages", [])}
    if stages.get("sparse-retrieval") != "executed" or any(stages.get(st) == "executed" for st in KEYWORD_OFF):
        raise ValueError(f"search was not keyword search alone: {stages}")
    # The stage also covers rule-based classification. Applied boosts imply LLM influence,
    # but their absence is insufficient: date-only boosts are omitted from this metadata.
    if (body.get("queryUnderstanding") or {}).get("appliedBoosts"):
        raise ValueError("search was not keyword search alone: query-understanding boosts")
    return stages


def lexical_ranks(handle, client, items: list[tuple[str, str, str]],
                  stages: Counter | None = None) -> dict[str, int | None]:
    """Keyword-alone (``KEYWORD_PIPELINE``, the refined request otherwise) rank of each target.

    ``stages`` counts the ``stage:status`` pairs the responses' traces report.
    """
    from .run import request_for
    request = {**request_for("text"), "pipeline": KEYWORD_PIPELINE}
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
        seen = keyword_stages(body)
        if stages is not None:
            stages.update(f"{k}:{v}" for k, v in seen.items())
        locations = [(h.get("fields") or {}).get("path") or h.get("id") for h in body.get("results", [])]
        ranked = handle.ranked_doc_ids(locations)
        out[qid] = ranked.index(target) + 1 if target in ranked else None
    return out


KEYWORD_JVM_OPTIONS_ALLOWED = frozenset({"-Djava.awt.headless=true"})


def require_keyword_environment(env: dict) -> None:
    """Refuse before engine startup: response metadata omits date-only QU boosts.

    QueryUnderstandingService.extractIfAvailable returns before inference when the search
    config's queryUnderstandingEnabled is false (lines 172, 223-227). Its default is false
    (ResolvedConfigBuilder:1442); no UI setting contributes this key. JVM properties outrank
    environment variables (ResolvedConfigBuilder:205-206), so check those inputs as well.
    """
    if (env.get("JUSTSEARCH_QU_ENABLED", "false").strip().lower() != "false"
            or env.get("JUSTSEARCH_LLM_ENABLED", "").strip().lower() != "false"):
        raise ValueError("keyword measurement requires JUSTSEARCH_QU_ENABLED absent or false "
                         "and JUSTSEARCH_LLM_ENABLED=false")
    # No parsing at all: launchers and the JVM split and unquote these texts differently
    # (escapes, embedded quotes), so each variable must be empty or exactly an allowed value.
    for name in ("JAVA_OPTS", "UI_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        text = env.get(name, os.environ.get(name, "")).strip()
        if text and text not in KEYWORD_JVM_OPTIONS_ALLOWED:
            raise ValueError(f"keyword measurement allows {name} only empty or one of "
                             f"{sorted(KEYWORD_JVM_OPTIONS_ALLOWED)}")


def gate(timeout_sec: float, cache_root: Path | None = None) -> int:
    """Gates and the keyword-alone measurement on a core-started prepared index.

    On the prepared index this is scoring: it runs only inside the core's exclusive window
    (``scripts/dev/admission.py exclusive``) and never builds; ``quality prepare`` builds.
    """
    c, _ = _collection()
    from .run import require_exclusive
    require_exclusive(ix.REPO_ROOT)
    config = replace(ix.IndexConfig(), timeout_sec=timeout_sec)
    config = replace(config, cache_root=cache_root) if cache_root else config
    with ix.prepare_index(c, config, prepared_only=True) as handle:
        # prepared_only returns an unstarted handle; wait() starts the isolated engine with
        # exactly handle.env. Never measure on an engine whose QU state we cannot control.
        require_keyword_environment(handle.env)
        handle.wait(prepared_only=True)
        report = _gate(c, handle.base_url, handle)
        report["generation"] = handle.index_state.get("generation")
        # The index key holds machine-specific hashes; it stays out of committed records.
    _write_json(RECORD / "gates.json", report)
    print(canonical_json(report["summary"]))
    return 0


def _gate(c: Collection, base_url: str, handle) -> dict:
    """S30 gates: drops only queries without an accepted generation that meets the offline
    gates (verbatim leak, word limit) and repeated query texts. Triviality (keyword search
    alone ranks the target first) is measured once per known-item query and recorded, never
    gated: no query is dropped or regenerated for it."""
    records, texts = load_texts()
    rows = _recipe_rows()
    planned = {q["qid"]: q for q in load_plan(c.documents)}
    done = {q: r for q, r in _accepted().items() if q in planned and meets_gates(planned[q], r, texts)}
    ki = sorted(qid for qid in done if planned[qid]["kind"] == "known-item")
    stages = Counter()
    with httpx.Client(base_url=base_url, timeout=600) as client:
        ranks = lexical_ranks(handle, client, [(qid, done[qid]["query"], planned[qid]["target"]) for qid in ki],
                              stages)
    results = {qid: {"query": done[qid]["query"], "rank": rank} for qid, rank in ranks.items()}
    print(canonical_json({"rank1": {lv: sum(1 for q in ki if planned[q]["level"] == lv and results[q]["rank"] == 1)
                                    for lv in LEVELS}}), file=sys.stderr, flush=True)
    dropped = {}
    attempts = defaultdict(list)
    for row in _jsonl(RECORD / "generation.jsonl"):
        attempts[row["qid"]].append(row)
    for qid in sorted(set(planned) - set(done)):
        # The planned leak/word-limit drops (SPARE): no accepted generation meets the gates.
        dropped[qid] = {"reason": "gates", "attempts": len(attempts[qid]),
                        "last_rejection": next((r.get("rejected") or r.get("error") or "no longer meets the gates"
                                                for r in reversed(attempts[qid])), "never generated")}
    kept = dict(done)
    for qid, first in duplicates(kept).items():
        dropped[qid] = {"reason": "duplicate", "kind": planned[qid]["kind"], "query": kept[qid]["query"],
                        "duplicate_of": first}
        del kept[qid]
    report = gate_report(planned, kept, results, records, texts, rows)
    report["ranking"] = {"pipeline": KEYWORD_PIPELINE, "trace_stages": dict(sorted(stages.items()))}
    report["dropped"] = dict(sorted(dropped.items()))
    report["summary"]["dropped"] = dict(sorted(Counter(d["reason"] for d in dropped.values()).items()))
    return report


def duplicates(done: dict) -> dict[str, str]:
    """Queries whose text (case-folded) repeats an earlier query id's: qid -> that first qid."""
    first, out = {}, {}
    for qid in sorted(done):
        key = done[qid]["query"].casefold()
        if key in first:
            out[qid] = first[key]
        else:
            first[key] = qid
    return out


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
    leak_failures = [qid for qid in ki if planned[qid]["level"] != "exact" and per_query[qid]["verbatim_leak"]]
    leak_failures += [qid for qid in per_query if planned[qid]["kind"] == "topical" and per_query[qid]["verbatim_leak"]]
    distinguish = distinguishability([planned[q]["target"] for q in ki], records, texts)
    summary = {"accepted": len(per_query), "known_item": len(ki),
               "topical": len(per_query) - len(ki), "verbatim_leak_failures": len(leak_failures),
               # Measured, not gated (S30): keyword search alone ranks the target first.
               "triviality": {lv: {k: strata["level"].get(lv, {}).get(k) for k in ("queries", "rank1", "rank1_share")}
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
    queries, judgments = {}, {}
    by_content = defaultdict(list)
    for doc, h in c.content.items():
        by_content[h].append(doc)
    for qid in sorted(done):
        q, text = planned[qid], done[qid]["query"]
        if qid not in gates or gates[qid]["query"] != text:
            continue  # dropped by the gate (gates.json records why)
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
    if dup := duplicates({q: {"query": t.text} for q, t in queries.items()}):
        raise ValueError(f"repeated query texts the gate did not drop: {sorted(dup)}")
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
    p = steps.add_parser("subset")
    p.add_argument("--size", type=int, default=SUBSET_SIZE)
    p = steps.add_parser("generate")
    p.add_argument("--concurrency", type=int, default=4)
    p.add_argument("--retry", action="store_true", help="regenerate queries failing the current gates")
    p = steps.add_parser("gate")
    p.add_argument("--timeout-sec", type=float, default=3 * 3600)
    p.add_argument("--cache-root", type=Path, help="index cache root (default: the quality cache)")
    steps.add_parser("write")
    args = parser.parse_args(argv)
    if args.step == "prepare":
        return prepare(args.collection, args.timeout_sec)
    if args.step == "extract":
        return extract(args.timeout_sec, args.url, args.root)
    if args.step == "subset":
        return subset(args.size)
    if args.step == "generate":
        return generate(args.concurrency, args.retry)
    if args.step == "gate":
        return gate(args.timeout_sec, args.cache_root)
    return {"clusters": clusters, "plan": plan, "write": write}[args.step]()


if __name__ == "__main__":
    sys.exit(main())
