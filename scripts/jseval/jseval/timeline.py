"""Timeline recording and pipeline summary computation."""

from __future__ import annotations

import csv
import io
import logging
from pathlib import Path

log = logging.getLogger(__name__)

# Fields captured per snapshot.
TIMELINE_FIELDS = [
    "elapsed_s",
    "indexed",
    "pending",
    "embed_pct",
    "splade_pct",
    "ner_done",
    "ner_pending",
    "chunk_pct",
    "heap_mb",
    "throughput",
    "gpu_pct",
    "vram_used_mb",
    "enrich_embed",
    "enrich_splade",
    "enrich_ner",
    # 354: per-phase timing from batchTiming map
    "embed_batches",
    "embed_ms",
    "splade_batches",
    "splade_ms",
    "ner_batches",
    "ner_ms",
    "fetch_batches",
    "fetch_ms",
    "write_batches",
    "write_ms",
    "total_batches",
    "total_ms",
    # 406: Lucene runtime gauges from /api/status worker.core (auto-flattened).
    "writer_queue_depth",
    "writer_pending_docs",
    "commit_count",
    "refresh_lag_ms",
    "vdu_pending",
    "vdu_processing",
]

# 391/E-J-N9: tolerance for SPLADE coverage decreases between snapshots.
# A 150-doc embed-batch failure on a 5184-doc corpus produces a ~2.9 pp drop
# (real signal). Snapshot-cadence artifacts — denominator grows by N docs
# between updates while the numerator catches up — produce sub-pp dips.
# 0.5 pp filters the artifacts while keeping batch-failure cascades visible.
CHURN_DROP_TOLERANCE_PP = 0.5

# 354: mapping from TSV column name to (batchTiming sub-map, key)
_BATCH_TIMING_COLUMNS = {
    "embed_batches": ("batchCount", "embed"),
    "embed_ms": ("totalMs", "embed"),
    "splade_batches": ("batchCount", "splade"),
    "splade_ms": ("totalMs", "splade"),
    "ner_batches": ("batchCount", "ner"),
    "ner_ms": ("totalMs", "ner"),
    "fetch_batches": ("batchCount", "fetch"),
    "fetch_ms": ("totalMs", "fetch"),
    "write_batches": ("batchCount", "write"),
    "write_ms": ("totalMs", "write"),
    "total_batches": ("batchCount", "total"),
    "total_ms": ("totalMs", "total"),
}


def snapshot_to_row(elapsed_s: float, snapshot: dict) -> dict:
    """Extract a timeline row from a status snapshot.

    ``indexed`` is None (a blank TSV cell, the timeline's form for a value it does not have) when
    the Engine reports the document count unknown; its 0 is then a placeholder, not an empty index.
    """
    heap_bytes = snapshot.get("memoryUsedBytes", 0)

    # GPU metrics (335 §9: from GpuStatusView in /api/status)
    gpu = snapshot.get("gpu") or {}
    gpu_pct = gpu.get("gpuUtilizationPercent")
    vram_used = gpu.get("usedVramBytes")

    row = {
        "elapsed_s": round(elapsed_s, 1),
        "indexed": (
            None if snapshot.get("indexedDocumentsUnknown") is True
            else snapshot.get("indexedDocuments", 0)
        ),
        "pending": (
            snapshot.get("pendingJobsCount", 0)
            + snapshot.get("processingJobsCount", 0)
        ),
        "embed_pct": round(snapshot.get("embeddingCoveragePercent", 0), 1),
        "splade_pct": round(snapshot.get("spladeCoveragePercent", 0), 1),
        "ner_done": snapshot.get("completedNerCount", 0),
        "ner_pending": snapshot.get("pendingNerCount", 0),
        "chunk_pct": round(snapshot.get("chunkVectorCoveragePercent", 0), 1),
        "heap_mb": round(heap_bytes / (1024 * 1024), 0) if heap_bytes else 0,
        "throughput": round(snapshot.get("throughputDocsPerSec", 0), 1),
        "gpu_pct": gpu_pct if gpu_pct is not None else "",
        "vram_used_mb": round(vram_used / (1024 * 1024), 0) if vram_used else "",
    }
    # 354 Phase 2: enrichment doc counts from nested enrichmentCompleted map
    enrich = snapshot.get("enrichmentCompleted", {})
    row["enrich_embed"] = enrich.get("embed", 0)
    row["enrich_splade"] = enrich.get("splade", 0)
    row["enrich_ner"] = enrich.get("ner", 0)
    # 354 Phase 1: per-phase timing from nested batchTiming map
    timing = snapshot.get("batchTiming", {})
    for col, (map_name, key) in _BATCH_TIMING_COLUMNS.items():
        row[col] = timing.get(map_name, {}).get(key, 0)
    # 406: Lucene runtime gauges. Source = /api/status flattens worker.core to
    # top-level, so writerQueueDepth etc. surface as snapshot keys directly.
    row["writer_queue_depth"] = snapshot.get("writerQueueDepth", 0)
    row["writer_pending_docs"] = snapshot.get("writerPendingDocs", 0)
    row["commit_count"] = snapshot.get("commitCount", 0)
    row["refresh_lag_ms"] = snapshot.get("refreshLagMs", 0)
    row["vdu_pending"] = snapshot.get("pendingVduCount", "")
    vdu_processing = snapshot.get("vduProcessing")
    row["vdu_processing"] = int(vdu_processing) if isinstance(vdu_processing, bool) else ""
    # 357: encoder profiles (carried in row dict, not written to TSV).
    # Underscore prefix signals "not a TSV column" — excluded from
    # TIMELINE_FIELDS by extrasaction="ignore" on DictWriter.
    # Read by compute_pipeline_summary() from the last row.
    ep = snapshot.get("encoderProfiles")
    if ep:
        row["_encoder_profiles"] = ep
    return row


def write_timeline_tsv(rows: list[dict], path: Path) -> None:
    """Write timeline rows to a TSV file."""
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=TIMELINE_FIELDS, delimiter="\t", extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)
    log.info("Timeline written to %s (%d rows)", path, len(rows))


def format_timeline_tsv(rows: list[dict]) -> str:
    """Format timeline rows as TSV string (for stdout)."""
    buf = io.StringIO()
    writer = csv.DictWriter(buf, fieldnames=TIMELINE_FIELDS, delimiter="\t", extrasaction="ignore")
    writer.writeheader()
    writer.writerows(rows)
    return buf.getvalue()


def compute_pipeline_summary(rows: list[dict]) -> dict:
    """Compute per-stage timing from timeline rows.

    Detects when each stage first reaches 100% (or completes) and
    reports the elapsed time at that point.
    """
    if not rows:
        return {}

    total_elapsed = rows[-1]["elapsed_s"] if rows else 0

    summary: dict = {
        "total_elapsed_s": total_elapsed,
    }

    # Primary indexing: time from first row with pending > 0 to first row
    # with pending == 0 AND indexed > initial (after indexing started).
    # Document counts come from the rows that have one (an unknown count is None).
    counted = [row["indexed"] for row in rows if row["indexed"] is not None]
    initial_indexed = counted[0] if counted else 0
    indexing_start = None
    indexing_end = None
    for row in rows:
        if indexing_start is None and row["pending"] > 0:
            indexing_start = row["elapsed_s"]
        if indexing_start is not None and indexing_end is None:
            if row["pending"] == 0 and row["indexed"] is not None and row["indexed"] > initial_indexed:
                indexing_end = row["elapsed_s"]

    if indexing_start is not None and indexing_end is not None:
        duration = indexing_end - indexing_start
        docs = counted[-1] - initial_indexed
        summary["primary_indexing"] = {
            "start_s": indexing_start,
            "end_s": indexing_end,
            "duration_s": round(duration, 1),
            "docs_indexed": docs,
            "docs_per_s": round(docs / duration, 1) if duration > 0 else 0,
        }

    # Per-stage completion times (first row at >= 100% / complete).
    # min_docs: ignore rows where indexed < 90% of final count — filters out
    # vacuously-100% readings when 1 doc is enriched before bulk ingestion starts.
    summary["stages"] = {}
    final_indexed = counted[-1] if counted else 0
    doc_floor = int(final_indexed * 0.9) if final_indexed > 10 else 0

    embed_done = _first_at_threshold(rows, "embed_pct", 100.0, min_docs=doc_floor)
    if embed_done is not None:
        summary["stages"]["embedding_100_pct_at_s"] = embed_done

    splade_done = _first_at_threshold(rows, "splade_pct", 100.0, min_docs=doc_floor)
    if splade_done is not None:
        summary["stages"]["splade_100_pct_at_s"] = splade_done

    chunk_done = _first_at_threshold(rows, "chunk_pct", 100.0, min_docs=doc_floor)
    if chunk_done is not None:
        summary["stages"]["chunk_100_pct_at_s"] = chunk_done

    # NER completion measurement.
    #
    # 391/follow-up: previously this used "first row where ner_pending == 0 and
    # ner_done > 0", but that snapshot can be a TRANSIENT drain: NER backfill
    # can process all currently-pending chunks before ingestion finishes creating
    # more chunks, producing a window with pending=0 at a partial ner_done value.
    # That window was being locked in as the reported ner_total_docs, causing
    # outlier readings like 5500 / 6300 on runs that actually completed at 7303.
    #
    # ner_done is monotonic across the run (docs go PENDING → COMPLETED / FAILED
    # and don't regress under --clean eval runs), so max across all rows gives
    # the true final count. ner_complete_at_s is the first row at that max with
    # pending=0 (i.e., the first time the pipeline was genuinely drained to the
    # terminal state, not a transient).
    ner_done_values = [row.get("ner_done", 0) for row in rows]
    if ner_done_values:
        max_ner_done = max(ner_done_values)
        if max_ner_done > 0:
            summary["stages"]["ner_total_docs"] = max_ner_done
            for row in rows:
                if row.get("ner_done", 0) == max_ner_done and row.get("ner_pending", 0) == 0:
                    summary["stages"]["ner_complete_at_s"] = row["elapsed_s"]
                    break

    # SPLADE churn: count rows where splade_pct decreased by more than
    # CHURN_DROP_TOLERANCE_PP (filters snapshot-cadence noise; preserves
    # visibility of real failure cascades). See constant for rationale.
    churn_drops = 0
    prev_pct = 0.0
    for row in rows:
        pct = row.get("splade_pct", 0)
        if pct < prev_pct - CHURN_DROP_TOLERANCE_PP:
            churn_drops += 1
        prev_pct = pct
    if churn_drops > 0:
        summary["splade_churn_drops"] = churn_drops

    # 350: Per-stage inference timing from backend cumulative counters.
    # Delta between first and last row gives run-scoped totals.
    inference = {}
    for stage, batches_key, ms_key in [
        ("embedding", "embed_batches", "embed_ms"),
        ("splade", "splade_batches", "splade_ms"),
        ("ner", "ner_batches", "ner_ms"),
    ]:
        first_batches = rows[0].get(batches_key, 0)
        last_batches = rows[-1].get(batches_key, 0)
        first_ms = rows[0].get(ms_key, 0)
        last_ms = rows[-1].get(ms_key, 0)
        delta_batches = last_batches - first_batches
        delta_ms = last_ms - first_ms
        if delta_batches > 0:
            inference[stage] = {
                "batches": delta_batches,
                "total_ms": delta_ms,
                "avg_ms_per_batch": round(delta_ms / delta_batches, 1),
            }
    if inference:
        summary["inference"] = inference

    # 354: Per-phase overhead timing (fetch, write, total) from backend.
    overhead = {}
    for phase, batches_key, ms_key in [
        ("fetch", "fetch_batches", "fetch_ms"),
        ("write", "write_batches", "write_ms"),
        ("total", "total_batches", "total_ms"),
    ]:
        first_batches = rows[0].get(batches_key, 0)
        last_batches = rows[-1].get(batches_key, 0)
        first_ms = rows[0].get(ms_key, 0)
        last_ms = rows[-1].get(ms_key, 0)
        delta_batches = last_batches - first_batches
        delta_ms = last_ms - first_ms
        if delta_batches > 0:
            overhead[phase] = {
                "batches": delta_batches,
                "total_ms": delta_ms,
                "avg_ms_per_batch": round(delta_ms / delta_batches, 1),
            }
    if overhead:
        summary["overhead"] = overhead

    # 357: encoder profiles — delta between first and last snapshot.
    # Calls and phaseTotalUs are cumulative counters, so delta gives run-scoped
    # values (important with --reset where prior counters are baked into rows[0]).
    # Percentiles are cumulative over the histogram lifetime — taken from last row.
    first_ep = rows[0].get("_encoder_profiles", {})
    last_ep = rows[-1].get("_encoder_profiles", {})
    encoder_profiles = {}
    for name in last_ep:
        last = last_ep[name]
        first = first_ep.get(name, {})
        delta_calls = last.get("calls", 0) - first.get("calls", 0)
        if delta_calls <= 0:
            continue
        delta_phases = {}
        for k, v in last.get("phaseTotalUs", {}).items():
            delta_phases[k] = v - first.get("phaseTotalUs", {}).get(k, 0)
        encoder_profiles[name] = {
            "calls": delta_calls,
            "phaseTotalUs": delta_phases,
            "ortMinUs": last.get("ortMinUs", 0),
            "ortMaxUs": last.get("ortMaxUs", 0),
            "ortP50Us": last.get("ortP50Us", 0),
            "ortP95Us": last.get("ortP95Us", 0),
            "ortP99Us": last.get("ortP99Us", 0),
        }
    if encoder_profiles:
        summary["encoder_profiles"] = encoder_profiles

    # [335 item 15] GPU summary stats.
    gpu_pcts = [r["gpu_pct"] for r in rows if r.get("gpu_pct") is not None]
    vram_mbs = [r["vram_used_mb"] for r in rows if r.get("vram_used_mb") is not None]
    if gpu_pcts:
        gpu_summary: dict = {
            "avg_pct": round(sum(gpu_pcts) / len(gpu_pcts), 1),
            "peak_pct": max(gpu_pcts),
            "idle_polls_pct": round(
                sum(1 for p in gpu_pcts if p < 5) * 100 / len(gpu_pcts), 1
            ),
        }
        if vram_mbs:
            gpu_summary["avg_vram_mb"] = round(sum(vram_mbs) / len(vram_mbs))
            gpu_summary["peak_vram_mb"] = max(vram_mbs)
        summary["gpu"] = gpu_summary

    return summary


def format_pipeline_summary(summary: dict) -> str:
    """Format pipeline summary for console output."""
    lines: list[str] = []
    total = summary.get("total_elapsed_s", 0)
    mins = total / 60
    lines.append(f"Pipeline complete in {total:.0f}s ({mins:.1f} min):")

    pi = summary.get("primary_indexing", {})
    if pi:
        lines.append(
            f"  Primary indexing:  {pi['duration_s']:.0f}s  "
            f"({pi['docs_per_s']:.0f} docs/sec, {pi['docs_indexed']} docs)"
        )

    stages = summary.get("stages", {})
    for key in ("embedding_100_pct_at_s", "splade_100_pct_at_s",
                "chunk_100_pct_at_s", "ner_complete_at_s"):
        if key in stages:
            label = key.replace("_at_s", "").replace("_", " ").title()
            lines.append(f"  {label}:  t={stages[key]:.0f}s")

    churn = summary.get("splade_churn_drops", 0)
    if churn > 0:
        lines.append(f"  SPLADE churn:  {churn} drops")

    inference = summary.get("inference", {})
    if inference:
        lines.append("  Inference timing (from backend):")
        for stage in ("embedding", "splade", "ner"):
            if stage in inference:
                s = inference[stage]
                lines.append(
                    f"    {stage}:  {s['total_ms']}ms total, "
                    f"{s['batches']} batches, "
                    f"{s['avg_ms_per_batch']}ms/batch"
                )

    overhead = summary.get("overhead", {})
    if overhead:
        lines.append("  Overhead timing (from backend):")
        for phase in ("fetch", "write", "total"):
            if phase in overhead:
                s = overhead[phase]
                lines.append(
                    f"    {phase}:  {s['total_ms']}ms total, "
                    f"{s['batches']} batches, "
                    f"{s['avg_ms_per_batch']}ms/batch"
                )

    # 357: encoder profiling from /api/status
    enc_profiles = summary.get("encoder_profiles", {})
    if enc_profiles:
        lines.append("  Encoder profiling (from /api/status):")
        for name in ("embed", "splade", "ner"):
            ep = enc_profiles.get(name)
            if not ep:
                continue
            calls = ep.get("calls", 0)
            if calls == 0:
                continue
            phases = ep.get("phaseTotalUs", {})
            phase_strs = [
                f"{k}={v // calls}us" for k, v in phases.items()
            ]
            total_avg = sum(v // calls for v in phases.values())
            phase_strs.append(f"total={total_avg}us")
            lines.append(
                f"    {name} ({calls} calls):  {', '.join(phase_strs)}"
                f"  ort=[p50={ep.get('ortP50Us', 0)}us"
                f" p95={ep.get('ortP95Us', 0)}us"
                f" p99={ep.get('ortP99Us', 0)}us]"
            )

    return "\n".join(lines)


def _first_at_threshold(
    rows: list[dict], field: str, threshold: float,
    min_docs: int = 0,
) -> float | None:
    """Return elapsed_s of first row where field >= threshold, or None.

    When min_docs > 0, ignores rows where indexed doc count is below the floor
    or unknown. This filters out vacuously-100% readings when only 1 doc is
    enriched before bulk ingestion starts.
    """
    for row in rows:
        indexed = row.get("indexed", 0)
        if min_docs > 0 and (indexed is None or indexed < min_docs):
            continue
        if row.get(field, 0) >= threshold:
            return row["elapsed_s"]
    return None
