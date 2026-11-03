"""jseval bench commands (split from cli.py — tempdoc 645)."""
from __future__ import annotations

import json
import sys
from pathlib import Path
import httpx
import logging

import click

from .._paths import DEFAULT_EVAL_RESULTS
from ._common import _DEFAULT_BASE_URL, _write_bench_output

log = logging.getLogger(__name__)


@click.command("bench-concurrency")
@click.option("--dataset", required=True)
@click.option("--concurrency", type=int, required=True,
              help="Number of concurrent query streams (>=1).")
@click.option("--base-url", default=_DEFAULT_BASE_URL, show_default=True)
@click.option("--output-dir", type=click.Path(), default=str(DEFAULT_EVAL_RESULTS), show_default=True)
@click.option("--top-k", default=10, show_default=True)
@click.option("--max-queries", default=0, show_default=True, help="Cap queries (0 = all).")
@click.option("--mode", default="hybrid", show_default=True,
              help="Search mode for every stream (lexical/hybrid/vector/full).")
@click.option("--allow-errors", is_flag=True,
              help="Record failed queries as null-latency records instead of raising.")
@click.option("--timeout", type=float, default=90.0, show_default=True)
@click.option("--max-connections", type=int, default=None,
              help="httpx connection-pool size. Phase 6 / 6.10 default = "
                   "concurrency (1:1). Override when the backend can "
                   "handle more than `concurrency` sockets.")
@click.option("--warmup", type=int, default=0, show_default=True,
              help="Discarded warmup queries per stream before the timed "
                   "pass. Amortizes httpx connection setup + backend "
                   "JIT warmup so p95/p99 aren't cold-inflated.")
@click.pass_context
def cmd_bench_concurrency(ctx, dataset, concurrency, base_url, output_dir,
                          top_k, max_queries, mode, allow_errors, timeout,
                          max_connections, warmup):
    """Concurrent-query benchmark (tempdoc 400 LR5-c).

    Spawns ``--concurrency`` query streams against
    ``/api/knowledge/search`` using the configured dataset and writes
    ``<run_dir>/concurrency-<N>.json`` with per-stream timelines +
    aggregate p50/p95/p99 + QPS. Each run lives under its own
    ``<output_dir>/<ts>_<dataset>_concurrency<N>/`` directory so
    successive benchmark runs at different N do not collide.
    """
    from datetime import datetime, timezone

    from .. import bench_concurrency, corpora
    from ..retriever import MODE_PIPELINES

    if concurrency < 1:
        click.echo("Error: --concurrency must be >= 1", err=True)
        sys.exit(1)

    pipeline_cfg = MODE_PIPELINES.get(mode)
    base_data_dir = Path(output_dir)
    query_records, qrels, meta = corpora.load(dataset, base_data_dir)
    query_records = {qid: qr for qid, qr in query_records.items() if qid in qrels}
    if max_queries > 0:
        query_records = dict(list(query_records.items())[:max_queries])
    queries = {qid: qr.text for qid, qr in query_records.items()}

    if not queries:
        click.echo("Error: no queries to run", err=True)
        sys.exit(1)

    result = bench_concurrency.run_benchmark(
        queries,
        concurrency=concurrency,
        base_url=base_url,
        mode=mode,
        top_k=top_k,
        pipeline=pipeline_cfg,
        allow_errors=allow_errors,
        timeout=timeout,
        max_connections=max_connections,
        warmup=warmup,
    )
    result["dataset"] = dataset
    result["mode"] = mode

    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S")
    run_dir = base_data_dir / f"{ts}_{dataset}_concurrency{concurrency}"
    run_dir.mkdir(parents=True, exist_ok=True)
    path = bench_concurrency.write_benchmark(result, run_dir)

    if ctx.obj.get("json"):
        click.echo(json.dumps(result, indent=2, default=str))
    else:
        click.echo(f"Wrote {path}")
        agg = result["aggregate_latency_ms"]
        click.echo(
            f"  concurrency={concurrency} queries={result['query_count']}"
            f" ok={result['ok']} errors={result['errors']} qps={result['qps']:.2f}"
        )
        click.echo(
            f"  latency_ms p50={agg['p50']:.1f} p95={agg['p95']:.1f}"
            f" p99={agg['p99']:.1f} max={agg['max']:.1f}"
        )


@click.command("llm-bench")
@click.option("--profile", type=click.Choice(["smoke", "regression", "full"]),
              default="regression", show_default=True)
@click.option("--runs", default=1, show_default=True, help="Suite iterations.")
@click.option("--base-url", default=_DEFAULT_BASE_URL, show_default=True)
@click.option("--output-dir", type=click.Path(), default=None)
@click.pass_context
def cmd_llm_bench(ctx, profile, runs, base_url, output_dir):
    """LLM inference benchmark (TTFT, E2E latency, token rate)."""
    from .. import llm_bench
    from jsharness import suite_stats

    if runs == 1:
        result = llm_bench.execute_llm_bench(base_url, profile=profile)
    else:
        suite_results = suite_stats.run_suite(
            lambda: llm_bench.execute_llm_bench(base_url, profile=profile),
            runs=runs,
        )
        result = {
            "runs": suite_results,
            "statistics": suite_stats.summarize_suite(
                suite_results,
                ["e2e_latency_p50_ms", "ttft_p50_ms", "token_rate_median_tps"],
            ),
        }
    if ctx.obj.get("json"):
        click.echo(json.dumps(result, indent=2, default=str))
    else:
        click.echo(llm_bench.format_console(result))
    _write_bench_output(result, output_dir, "llm-bench.json")


@click.command("ingest-bench")
@click.option("--corpus-dir", required=True, type=click.Path(exists=True))
@click.option("--runs", default=1, show_default=True)
@click.option("--base-url", default=_DEFAULT_BASE_URL, show_default=True)
@click.option("--output-dir", type=click.Path(), default=None)
def cmd_ingest_bench(corpus_dir, runs, base_url, output_dir):
    """Ingest throughput benchmark (Claim B)."""
    from .. import ingest_bench

    result = ingest_bench.execute_ingest_bench(
        base_url, Path(corpus_dir), runs=runs,
    )
    click.echo(ingest_bench.format_console(result))
    _write_bench_output(result, output_dir, "ingest-bench.json")


@click.command("engine-bench")
@click.option("--corpus", required=True, type=click.Path(exists=True))
@click.option("--runs", default=5, show_default=True)
@click.option("--output-dir", type=click.Path(), default=None)
def cmd_engine_bench(corpus, runs, output_dir):
    """Engine-only indexing benchmark (Claim A)."""
    from .. import gradle_bench

    result = gradle_bench.execute_engine_bench(
        Path(corpus), runs=runs, out_dir=Path(output_dir) if output_dir else None,
    )
    click.echo(gradle_bench.format_engine_console(result))
    _write_bench_output(result, output_dir, "engine-bench.json")


@click.command("knn-bench")
@click.option("--doc-counts", default="20000,200000", show_default=True)
@click.option("--chunk-doc-counts", default="200000,500000", show_default=True)
@click.option("--repeats", default=1, show_default=True)
@click.option("--output-dir", type=click.Path(), default=None)
def cmd_knn_bench(doc_counts, chunk_doc_counts, repeats, output_dir):
    """Filtered kNN latency benchmark (Track G)."""
    from .. import gradle_bench

    result = gradle_bench.execute_knn_bench(
        doc_counts=[int(x) for x in doc_counts.split(",")],
        chunk_doc_counts=[int(x) for x in chunk_doc_counts.split(",")],
        repeats=repeats,
        out_dir=Path(output_dir) if output_dir else None,
    )
    click.echo(gradle_bench.format_knn_console(result))
    _write_bench_output(result, output_dir, "knn-bench.json")


COMMANDS = [cmd_bench_concurrency, cmd_llm_bench, cmd_ingest_bench, cmd_engine_bench, cmd_knn_bench]
