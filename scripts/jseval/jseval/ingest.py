"""Corpus ingestion with backpressure and queue-depth monitoring."""

from __future__ import annotations

import json
import logging
import os
import time
from pathlib import Path

import httpx

from .readiness import (
    flatten_status,
    wait_embed_compat_settled,
    wait_index_idle,
    wait_pipeline_complete,
)
from .types import IngestConfig

log = logging.getLogger(__name__)

# Backpressure defaults (from PS1 BeirEval.Indexing.psm1)
HIGH_WATERMARK = 90_000
LOW_WATERMARK = 70_000


def ingest_and_wait(
    config: IngestConfig,
    docs_dir: Path,
    *,
    corpus_doc_count: int = 0,
    poll_interval_sec: float = 2.0,
    stable_polls_required: int = 2,
) -> dict:
    """Add docs_dir as a watched root and wait for indexing to complete.

    Returns a summary dict with timing and throughput info.
    """
    start = time.monotonic()

    # Get initial doc count for floor calculation
    initial_count = _get_indexed_doc_count(config.base_url)
    if corpus_doc_count > 0:
        if _root_already_watched(config.base_url, docs_dir):
            # Tempdoc 751 sec Q sub-bug (a): the SAME root is being (re-)ingested
            # into a backend that already watches it -- e.g. after cache adoption
            # (the adopted index already indexed this corpus) or chain re-entry.
            # Re-adding an already-watched root indexes ZERO new docs (the Worker
            # dedups by path, IndexingDocumentOps.java), so the additive floor
            # below would demand an unmeetable 2x-corpus wall (the 1001+1001=2002
            # wedge). The floor is the UNION of watched content, not the SUM of
            # ingest requests: max() still requires the full corpus if the index
            # is somehow not yet complete, so this never weakens the gate for a
            # genuinely larger corpus -- it only refuses to double-count the same
            # root.
            expected_min = max(initial_count, corpus_doc_count)
        else:
            # New root: expect corpus to be ADDED to whatever already exists.
            # Use initial + corpus as the floor, not max(initial, corpus),
            # so a dirty index (with pre-existing data) doesn't pass prematurely.
            expected_min = initial_count + corpus_doc_count
    else:
        expected_min = -1

    # Add the watched root
    add_watched_root(config.base_url, docs_dir)

    # Wait for the file watcher to start scanning.  After add_watched_root()
    # returns, the watcher enumerates files asynchronously.  Without this
    # delay, wait_index_idle() can see IDLE/0-pending before any jobs are
    # queued and pass immediately.
    # [-1c] Scale timeout with corpus size — 30s is too short for large corpora.
    watcher_timeout = _watcher_settle_timeout(corpus_doc_count)
    watcher_active = _wait_for_watcher_activity(
        config.base_url, initial_count, poll_interval_sec,
        timeout_sec=watcher_timeout,
    )

    if not watcher_active and expected_min > initial_count:
        # A slow/temporarily unobservable watcher does not prove that a new root was indexed.
        # Already-watched roots have their union floor computed above; retain that authority.
        log.info(
            "No watcher activity observed yet; retaining required doc-count floor %d.",
            expected_min,
        )

    # Timeline recording (item 5).
    from . import timeline as tl_mod
    timeline_rows: list[dict] = []
    on_snapshot = None
    if config.timeline_path is not None or config.pipeline:
        def on_snapshot(elapsed_s: float, snap: dict) -> None:
            timeline_rows.append(tl_mod.snapshot_to_row(elapsed_s, snap))

    # Wait for indexing (and optionally all enrichments) to finish.
    # Per-stage enabled state is published by the Worker on the status snapshot
    # (tempdoc 394 follow-up); wait_pipeline_complete reads it directly and
    # skips checks for disabled stages.
    if config.pipeline:
        readiness = wait_pipeline_complete(
            config.base_url,
            expected_doc_count_min=expected_min,
            timeout_sec=config.index_timeout_sec,
            poll_interval_sec=poll_interval_sec,
            stable_polls_required=stable_polls_required,
            on_snapshot=on_snapshot,
            json_mode=config.json_mode,
            process_check=config.process_check,
        )
    else:
        readiness = wait_index_idle(
            config.base_url,
            expected_doc_count_min=expected_min,
            dense_enabled=config.dense_enabled,
            splade_enabled=config.splade_enabled,
            timeout_sec=config.index_timeout_sec,
            poll_interval_sec=poll_interval_sec,
            stable_polls_required=stable_polls_required,
            on_snapshot=on_snapshot,
            json_mode=config.json_mode,
            process_check=config.process_check,
        )

    elapsed = time.monotonic() - start
    final_count = readiness.snapshot.get("indexedDocuments", 0)
    docs_indexed = final_count - initial_count

    snapshot = readiness.snapshot
    summary: dict = {
        "readiness_passed": readiness.passed,
        "failure_reasons": readiness.failure_reasons,
        "initial_doc_count": initial_count,
        "final_doc_count": final_count,
        "docs_indexed": docs_indexed,
        "elapsed_sec": round(elapsed, 2),
        "docs_per_sec": round(docs_indexed / elapsed, 1) if elapsed > 0 else 0,
        "index_size_bytes": snapshot.get("indexSizeBytes"),
    }

    # tempdoc 715 defect 2: on a fast small-corpus run, indexing/enrichment readiness
    # (above) can be satisfied while the Worker's EmbeddingCompatibilityController is still
    # mid-transition -- /api/status briefly still reads embeddingCompatState=REBUILDING even
    # though nothing is actually pending. The run manifest's models_snapshot (`run._snapshot_
    # models`, read fresh right after this function returns) would then capture "REBUILDING",
    # splitting the release cohort key from a run of the same corpus/model that simply
    # finished ingest a little slower. Bounded settle-wait BEFORE returning to the caller
    # (which proceeds straight to eval + manifest snapshot) closes that race; computed AFTER
    # `elapsed`/`docs_per_sec` above so it never pollutes this function's own throughput
    # metrics (also read unmodified by ingest_bench.py's benchmark wrapper).
    if readiness.passed:
        settled_state = wait_embed_compat_settled(config.base_url)
        summary["embed_compat_state_settled"] = settled_state

    # Write timeline TSV (item 5).
    if config.timeline_path and timeline_rows:
        tl_mod.write_timeline_tsv(timeline_rows, config.timeline_path)

    # Compute pipeline summary (item 6).
    if config.pipeline and timeline_rows:
        summary["pipeline_summary"] = tl_mod.compute_pipeline_summary(timeline_rows)

    if readiness.passed:
        log.info(
            "Indexing complete: %d docs in %.1fs (%.1f docs/s)",
            docs_indexed, elapsed, summary["docs_per_sec"],
        )
    else:
        log.error(
            "Indexing did not complete: %s", readiness.failure_reasons,
        )

    return summary


def add_watched_root(
    base_url: str,
    docs_dir: Path,
    timeout_sec: float = 1800.0,
    *,
    session_token: str | None = None,
) -> None:
    """Add a directory as a watched root for JustSearch indexing."""
    abs_path = str(docs_dir.resolve())
    log.info("Adding watched root: %s", abs_path)

    headers = {"X-JustSearch-Session": session_token} if session_token and session_token.strip() else {}
    with httpx.Client(base_url=base_url, timeout=timeout_sec, headers=headers) as client:
        resp = client.post(
            "/api/indexing/roots",
            json={"path": abs_path},
        )
        resp.raise_for_status()
    log.info("Watched root added successfully")


def prepare_corpus(
    dataset_name: str,
    config: IngestConfig,
    corpus_dir: Path | None = None,
    raw_context=None,
    env_overrides=None,
) -> dict:
    """Materialize (if needed) and ingest a dataset.

    Returns an ingest summary dict with timing/throughput info.
    """
    from ._paths import default_corpus_dir
    from .corpora import normalize_dataset_name

    # Accept the register's catalog-qualified BEIR slug (`beir/scifact`) as well as the bare
    # registry key (`scifact`), matching corpora.load (tempdoc 787 item 4a). Normalize before any
    # corpus-dir resolution so cache/materialization paths key on the same canonical name.
    dataset_name = normalize_dataset_name(dataset_name) if dataset_name else dataset_name

    from .raw_corpus_manifest import (
        resolve_raw_corpus_context,
        validate_raw_corpus_context,
    )

    if raw_context is None:
        raw_context = resolve_raw_corpus_context(
            dataset_name, corpus_dir, env_overrides=env_overrides,
        )
    if raw_context is not None:
        validate_raw_corpus_context(
            raw_context,
            env_overrides,
            expected_dataset=dataset_name,
            explicit_dir=corpus_dir,
        )
        result = ingest_and_wait(
            config, raw_context.root, corpus_doc_count=raw_context.identity.file_count,
        )
        return {
            **result,
            "corpus_doc_count": raw_context.identity.file_count,
            "corpus_identity": raw_context.to_corpus_identity(),
        }

    resolved_dir = corpus_dir or default_corpus_dir(dataset_name)
    corpus_doc_count = _ensure_materialized(dataset_name, resolved_dir, corpus_dir)
    return ingest_and_wait(config, resolved_dir, corpus_doc_count=corpus_doc_count)


def _raw_corpus_dir(dataset_name: str) -> Path | None:
    """`corpus-dir` of a raw binary-file dataset, else None.

    A local (golden/mixed) dataset is "raw" when its `metadata.json` carries
    ``{"raw_files": true}`` — introduced for `mixed/realdocs-v1` (tempdoc 686), the
    first corpus of real binary documents. Such datasets have no corpus.jsonl and are
    never materialized; ingest points the watched root at the real files directly.
    """
    # dataset_name may be None: `index-cache warm --corpus-dir X` (751 sec P.5)
    # drives prepare_corpus with an explicit dir and no dataset identity.
    if not dataset_name:
        return None
    if not (dataset_name.startswith("golden/") or dataset_name.startswith("mixed/")):
        return None
    from .corpora import _default_base_dir
    root = _default_base_dir() / dataset_name
    meta = root / "metadata.json"
    if not meta.is_file():
        return None
    try:
        data = json.loads(meta.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    if not data.get("raw_files"):
        return None
    return root / "corpus-dir"


# The materialization cache is a VERIFIED PROJECTION of the source (tempdoc 635
# verification-binding): for a local (golden/mixed) corpus the cache is reused only when its
# `.source_signature` sidecar still matches the source's canonical `corpus_signature`; on any
# identity change it is cleared and re-materialized. This closes the stale-cache class — a
# regenerated corpus silently re-ingesting the previous cache (→ nDCG 0.0 / a cert that lies
# about which corpus it measured) — structurally, at the source→materialization boundary.
_SIDECAR = ".source_signature"


def _source_signature(dataset_name: str) -> str | None:
    """Canonical `corpus_signature` of a LOCAL (golden/mixed) source, else None.

    Returns None for BEIR / unknown datasets — those caches come from immutable
    ir-datasets versions and cannot go stale via regeneration, so they keep the plain
    materialize-if-empty behaviour.
    """
    # dataset_name may be None: `index-cache warm --corpus-dir X` (751 sec P.5)
    # drives prepare_corpus with an explicit dir and no dataset identity.
    if not dataset_name:
        return None
    if not (dataset_name.startswith("golden/") or dataset_name.startswith("mixed/")):
        return None
    from .corpora import _default_base_dir
    from .corpus_identity import corpus_signature
    return corpus_signature(_default_base_dir() / dataset_name)


def _materialize_into(dataset_name: str, resolved_dir: Path) -> None:
    from . import materialize as mat_mod
    from .corpora import BEIR_DATASETS
    if dataset_name in BEIR_DATASETS:
        import ir_datasets
        ds = ir_datasets.load(BEIR_DATASETS[dataset_name])
        mat_mod.materialize(ds.docs_iter(), resolved_dir, skip_existing=True)
    elif dataset_name.startswith("golden/") or dataset_name.startswith("mixed/"):
        # Route through the SAME axis-aware per-doc decision `corpus_build.build_golden`
        # uses (`corpus_generate.materialize_doc_entry`) — not a second, independent
        # `type_axis == "scan"` check. This is the fix for the bug where this path read
        # plain `corpus.jsonl` and called `mat_mod.materialize()` directly, silently
        # skipping scan-page rendering: a `golden/`-prefixed `axis="scan"` dataset
        # materialized here got `.txt` ground-truth substitutes instead of the degraded
        # `.png` scans it was supposed to test (tempdoc 624 follow-up).
        from . import corpus_generate
        corpus_jsonl = _find_corpus_jsonl(dataset_name)
        type_axis = _read_type_axis(dataset_name)
        docs = (
            corpus_generate.materialize_doc_entry(d, type_axis)
            for d in _iter_corpus_jsonl(corpus_jsonl)
        )
        mat_mod.materialize(docs, resolved_dir, skip_existing=True)
    else:
        valid = ", ".join(sorted(BEIR_DATASETS))
        raise ValueError(
            f"Cannot materialize unknown dataset: {dataset_name!r}. "
            f"Expected a BEIR name ({valid}), its catalog slug (e.g. 'beir/scifact'), "
            f"or a name starting with 'golden/' or 'mixed/'."
        )


_MATERIALIZED_EXTS = ("*.txt", "*.png")  # kept in sync with materialize.py's two output kinds


def _count_materialized(resolved_dir: Path) -> int:
    """Count materialized doc files: ``.txt`` (plain) + ``.png`` (``axis="scan"``,
    tempdoc 624 follow-up). A single-extension count silently undercounts a scan
    dataset (which materializes almost entirely to ``.png``), corrupting the
    ``corpus_doc_count`` floor `ingest_and_wait` uses to decide indexing completion.
    """
    if not resolved_dir.is_dir():
        return 0
    return sum(len(list(resolved_dir.glob(pat))) for pat in _MATERIALIZED_EXTS)


def _ensure_materialized(
    dataset_name: str, resolved_dir: Path, corpus_dir: Path | None
) -> int:
    """Ensure ``resolved_dir`` holds the CURRENT corpus; return its materialized doc count.

    Reuses the cache only when it is a verified projection of the source (sidecar signature
    matches); otherwise clears the stale materialized files + sidecar and re-materializes.
    """
    doc_count = _count_materialized(resolved_dir)

    if corpus_dir is not None:
        # Explicit --corpus-dir: use as-is, never materialize into it.
        if doc_count == 0:
            raise FileNotFoundError(
                f"--corpus-dir {resolved_dir} has no .txt/.png files. "
                f"Materialize first with: jseval materialize "
                f"--dataset {dataset_name} --output-dir {resolved_dir}"
            )
        log.info("Using explicit corpus dir: %s (%d files)", resolved_dir, doc_count)
        return doc_count

    src_sig = _source_signature(dataset_name)
    sidecar = resolved_dir / _SIDECAR
    cached_sig = sidecar.read_text(encoding="utf-8").strip() if sidecar.is_file() else None

    fresh = doc_count == 0
    stale = src_sig is not None and cached_sig != src_sig  # changed source, or never recorded
    if fresh or stale:
        if stale and not fresh:
            log.info(
                "Corpus %s changed (cache sig %s != source %s) — re-materializing %s",
                dataset_name, (cached_sig or "none")[:12], (src_sig or "")[:12], resolved_dir,
            )
            for pat in _MATERIALIZED_EXTS:
                for p in resolved_dir.glob(pat):
                    p.unlink()
            sidecar.unlink(missing_ok=True)
        else:
            log.info("Materializing corpus for %s to %s", dataset_name, resolved_dir)
        _materialize_into(dataset_name, resolved_dir)
        if src_sig is not None:
            (resolved_dir / _SIDECAR).write_text(src_sig, encoding="utf-8")
    else:
        log.info("Corpus already materialized at %s (%d files; identity verified)",
                 resolved_dir, doc_count)

    return _count_materialized(resolved_dir)


def _find_corpus_jsonl(dataset_name: str) -> Path:
    """Locate corpus.jsonl for a golden/mixed dataset."""
    from .corpora import _default_base_dir
    path = _default_base_dir() / dataset_name / "corpus.jsonl"
    if not path.is_file():
        raise FileNotFoundError(f"corpus.jsonl not found at {path}")
    return path


def _read_type_axis(dataset_name: str) -> str | None:
    """`type_axis` of a golden/mixed dataset, from the `metadata.json`
    `corpus_build.build_golden()` writes alongside `corpus.jsonl` (same `src_meta.type_axis`
    field the build step itself reads — this is a read of that same recorded value, not a
    second guess at it).

    Returns None when `metadata.json` is absent/unreadable — e.g. a `corpus.jsonl` seeded
    directly without going through `build_golden()` (as jseval's own unit-test fixtures do).
    `materialize_doc_entry(doc, None)` degrades to the pre-existing plain-text behavior in
    that case, so untouched call sites are unaffected.
    """
    from .corpora import _default_base_dir
    meta_path = _default_base_dir() / dataset_name / "metadata.json"
    if not meta_path.is_file():
        return None
    try:
        return json.loads(meta_path.read_text(encoding="utf-8")).get("type_axis")
    except (json.JSONDecodeError, OSError):
        return None


def _iter_corpus_jsonl(path: Path):
    """Iterate dicts from a BEIR-format corpus.jsonl file."""
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line:
            yield json.loads(line)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

_WATCHER_SETTLE_MIN_SEC = 30.0
_WATCHER_SETTLE_MAX_SEC = 300.0


def _watcher_settle_timeout(corpus_doc_count: int) -> float:
    """Compute watcher settle timeout proportional to corpus size.

    Small corpora (< 3000 docs): 30s (original default).
    Large corpora: scales up to 300s max.  Heuristic: 1s per 100 docs,
    clamped to [30, 300].
    """
    if corpus_doc_count <= 0:
        return _WATCHER_SETTLE_MIN_SEC
    scaled = max(_WATCHER_SETTLE_MIN_SEC, corpus_doc_count / 100.0)
    return min(scaled, _WATCHER_SETTLE_MAX_SEC)


def _wait_for_watcher_activity(
    base_url: str,
    initial_doc_count: int,
    poll_sec: float,
    *,
    timeout_sec: float = _WATCHER_SETTLE_MIN_SEC,
) -> bool:
    """Wait until the file watcher starts queueing jobs or indexing new docs.

    After add_watched_root(), the watcher scans files asynchronously.  If we
    start the readiness poll immediately, we can see IDLE + 0 pending and
    pass before any files are queued.  This helper waits until we see either
    pendingJobs > 0, indexState != IDLE, or doc count has grown — any of
    which means the watcher has started working.

    Returns True if activity was detected, False on timeout (the corpus dir
    may already be fully indexed, in which case no new activity appears).
    """
    deadline = time.monotonic() + timeout_sec
    log.debug("Waiting up to %.0fs for watcher activity", timeout_sec)
    with httpx.Client(base_url=base_url, timeout=10) as client:
        while time.monotonic() < deadline:
            time.sleep(poll_sec)
            try:
                resp = client.get("/api/status")
                resp.raise_for_status()
                data = flatten_status(resp.json())
            except Exception:
                continue
            pending = (
                data.get("pendingJobs", 0)
                + data.get("pendingJobsCount", 0)
                + data.get("processingJobsCount", 0)
            )
            if pending > 0:
                log.debug("Watcher active: %d pending jobs", pending)
                return True
            if data.get("indexState") != "IDLE":
                log.debug("Watcher active: indexState=%s", data.get("indexState"))
                return True
            if data.get("indexedDocuments", 0) > initial_doc_count:
                log.debug("Watcher active: doc count grew")
                return True

    log.debug("Watcher settle timeout (%.0fs) — corpus may already be indexed",
              timeout_sec)
    return False


def _get_indexed_doc_count(base_url: str) -> int:
    try:
        with httpx.Client(base_url=base_url, timeout=10) as client:
            resp = client.get("/api/status")
            resp.raise_for_status()
            return flatten_status(resp.json()).get("indexedDocuments", 0)
    except Exception:
        return 0


def _watched_root_paths(base_url: str) -> list[str]:
    """Absolute paths of the backend's current watched roots.

    Reads ``GET /api/indexing/roots`` (payload ``{"roots": [{"path": ...}, ...]}``,
    IndexingController.handleListRoots). Returns ``[]`` on any error -- an empty
    list makes :func:`_root_already_watched` report "not watched", which falls the
    caller back to today's additive floor (fail-SAFE: a lost signal can only
    over-count the floor, never under-count it).
    """
    try:
        with httpx.Client(base_url=base_url, timeout=10) as client:
            resp = client.get("/api/indexing/roots")
            resp.raise_for_status()
            roots = resp.json().get("roots", [])
        return [r["path"] for r in roots if isinstance(r, dict) and r.get("path")]
    except Exception:
        return []


def _root_already_watched(base_url: str, docs_dir: Path) -> bool:
    """True iff ``docs_dir`` is already among the backend's watched roots.

    Comparison is on the OS-normalized real path of both sides, so a recorded
    root and a freshly-resolved ``docs_dir`` match regardless of case / symlink /
    trailing-separator noise. Drives the union-not-sum readiness floor (tempdoc
    751 sec Q sub-bug a): re-ingesting an already-watched root adds no new docs.
    """
    target = os.path.normcase(os.path.realpath(str(docs_dir)))
    for p in _watched_root_paths(base_url):
        if os.path.normcase(os.path.realpath(p)) == target:
            return True
    return False


def _wait_for_backpressure(
    client: httpx.Client,
    high_watermark: int,
    low_watermark: int,
    poll_sec: float,
) -> None:
    """Block if the queue depth exceeds the high watermark."""
    while True:
        try:
            resp = client.get("/api/status")
            resp.raise_for_status()
            depth = flatten_status(resp.json()).get("pendingJobs", 0)
        except Exception:
            time.sleep(poll_sec)
            continue

        if depth < high_watermark:
            return

        log.debug(
            "Backpressure: queue depth %d >= %d, waiting for <= %d",
            depth, high_watermark, low_watermark,
        )
        while depth > low_watermark:
            time.sleep(poll_sec)
            try:
                resp = client.get("/api/status")
                resp.raise_for_status()
                depth = flatten_status(resp.json()).get("pendingJobs", 0)
            except Exception:
                continue
        return  # Queue drained below low watermark
