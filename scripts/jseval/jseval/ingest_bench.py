"""Ingest throughput benchmark — multi-run wrapper around ingest_and_wait."""

from __future__ import annotations

import logging
from pathlib import Path

from . import ingest

from jsharness import suite_stats
from .types import IngestConfig

log = logging.getLogger(__name__)

_INGEST_METRICS = ["docs_per_sec", "elapsed_sec", "docs_indexed"]


def execute_ingest_bench(
    base_url: str,
    corpus_dir: Path,
    *,
    runs: int = 5,
    dense_enabled: bool = False,
    splade_enabled: bool = False,
    corpus_doc_count: int = 0,
    index_timeout_sec: float = 7200.0,
) -> dict:
    """Run ingest benchmark N times, return suite summary.

    Note: between runs the caller is responsible for resetting the index
    (e.g., restarting the backend with --clean hard). This matches the
    legacy Claim B behavior.
    """
    config = IngestConfig(
        base_url=base_url,
        dense_enabled=dense_enabled,
        splade_enabled=splade_enabled,
        index_timeout_sec=index_timeout_sec,
    )
    results: list[dict] = []
    for i in range(1, runs + 1):
        log.info("Ingest bench run %d/%d", i, runs)
        summary = ingest.ingest_and_wait(
            config,
            corpus_dir,
            corpus_doc_count=corpus_doc_count,
        )
        if not summary["readiness_passed"]:
            log.warning("Run %d failed: %s", i, summary["failure_reasons"])
        results.append(summary)

    successful = [r for r in results if r.get("readiness_passed")]
    return {
        "claim": "B",
        "total_runs": runs,
        "successful_runs": len(successful),
        "runs": results,
        "statistics": suite_stats.summarize_suite(successful, _INGEST_METRICS),
    }


def format_console(result: dict) -> str:
    """Human-readable output for ingest bench."""
    lines = [
        f"Ingest Bench (Claim B): {result['successful_runs']}/{result['total_runs']} "
        f"runs succeeded",
    ]
    stats = result.get("statistics", {})
    dps = stats.get("docs_per_sec", {})
    if dps.get("median") is not None:
        lines.append(f"  docs/s:   median={dps['median']}  "
                     f"min={dps['min']}  max={dps['max']}")
    elapsed = stats.get("elapsed_sec", {})
    if elapsed.get("median") is not None:
        lines.append(f"  elapsed:  median={elapsed['median']}s  "
                     f"min={elapsed['min']}s  max={elapsed['max']}s")
    return "\n".join(lines)
