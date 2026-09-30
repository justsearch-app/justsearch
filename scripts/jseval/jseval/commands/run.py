"""jseval run commands (split from cli.py — tempdoc 645)."""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path
import httpx
import logging

import click

from .._paths import DEFAULT_EVAL_RESULTS
from ..cadence import DEFAULT_BATCH_MIN_FILES
from ._common import _DEFAULT_BASE_URL, assert_run_capabilities

log = logging.getLogger(__name__)


@click.command("run")
@click.option("--dataset", default=None, help="Dataset name (e.g., scifact, golden/desktop-v1).")
@click.option("--modes", default=None, help="Comma-separated modes (e.g., lexical,hybrid).")
@click.option("--base-url", default=_DEFAULT_BASE_URL, show_default=True)
@click.option("--output-dir", type=click.Path(), default=str(DEFAULT_EVAL_RESULTS), show_default=True)
@click.option("--top-k", default=10, show_default=True)
@click.option("--embedding/--no-embedding", default=False, help="Enable dense/hybrid readiness checks.")
@click.option("--splade/--no-splade", default=False)
@click.option(
    "--query-syntax", "query_syntax",
    type=click.Choice(["simple", "lucene"]), default=None,
    help="Q-020 / F-046: forward this run's queries with the given `querySyntax` "
         "(`docs/reference/api-contract-map.md`). Default: omit the field entirely — "
         "byte-identical to every pre-Q-020 request, and the Head resolves its own SIMPLE "
         "default server-side. Recorded in summary.json's `query_syntax` either way.",
)
@click.option("--allow-errors", is_flag=True, help="Continue on query errors.")
@click.option("--max-queries", default=0, show_default=True, help="Cap queries for fast iteration (0 = all).")
@click.option("--lambdamart/--no-lambdamart", default=False)
@click.option("--ce/--no-ce", "cross_encoder", default=False, help="Enable cross-encoder reranking in all modes.")
@click.option("--context-coverage", is_flag=True, help="Compute excerpt coverage metrics.")
@click.option("--thresholds", default="0.25,0.5", show_default=True, help="Coverage threshold rates (comma-separated).")
@click.option("--history-db", type=click.Path(), default=None, help="Shared history database path.")
@click.option("--corpus-dir", type=click.Path(), default=None, help="Override corpus materialization directory.")
@click.option("--skip-ingest", is_flag=True, help="Skip materialization and ingestion (query only).")
@click.option("--pipeline", is_flag=True, help="Wait for ALL enrichments (embed, SPLADE, chunks, NER).")
@click.option("--timeline", "timeline_path", type=click.Path(), default=None, help="Record status snapshots to TSV.")
@click.option("--start-backend", is_flag=True, help="Start runHeadlessEval, run eval, stop when done.")
@click.option("--llm", is_flag=True, help="Enable LLM (Brain/llama-server) in the backend (requires --start-backend). Fails fast: eval-mode read-only settings make engine autostart impossible (tempdoc 782 §I) — run llama-server out of band and use the Head /v1 proxy.")
@click.option("--qu", is_flag=True, help="Enable Query Understanding (requires --llm).")
@click.option("--filter-norm", is_flag=True, help="Enable filter value normalization (requires --llm).")
@click.option("--clean", is_flag=True, help="Clean data dir before starting backend (requires --start-backend).")
@click.option("--reset", is_flag=True, help="Reset index via API before ingestion (requires running backend in eval mode).")
@click.option("--cpu", is_flag=True, help="Force CPU-only mode (disable GPU for all ONNX encoders). For testing CPU inference paths on GPU machines.")
@click.option("--allow-degraded", is_flag=True, help="Tempdoc 644 Axis 2: proceed even when an intended engine (e.g. the cross-encoder) is not loaded. Default OFF — a worktree run with the reranker silently absent refuses rather than emitting wrong-but-plausible numbers.")
@click.option("--index-cache/--fresh-index", "index_cache_flag", default=None,
              help="Tempdoc 751 WP3: adopt/publish an input-addressed cached index when the "
                   "corpus x engine identity matches (requires --start-backend --clean). Default: "
                   "fresh build (byte-identical to today). Env JUSTSEARCH_INDEX_CACHE_ADOPT=1 also "
                   "enables; an explicit flag wins (--fresh-index beats the env).")
@click.option("--pin-index-selector-key", "pin_index_selector_key", default=None,
              help="Tempdoc 768 item 5: adopt a SPECIFIC historical index-cache entry by its "
                   "selector key, bypassing compute_selector (which resolves the CURRENT key). "
                   "The 763 forensic-replay path needs this when HEAD has advanced past the "
                   "campaign commit. Requires --index-cache --start-backend --clean.")
@click.option("--config", "config_path", type=click.Path(exists=True), default=None, help="YAML run config file.")
@click.option(
    "--duplicate-prevalence-input-spec",
    "duplicate_prevalence_input_spec",
    type=click.Path(exists=True, dir_okay=False, path_type=Path),
    default=None,
    help=(
        "Privately capture production-extracted content after fresh ingest and "
        "decorate this run's result identity sidecar. CLI-only; the spec path is "
        "never persisted."
    ),
)
@click.option("--warmup", "warmup_count", type=int, default=0, show_default=True,
              help=(
                  "Run N warmup iterations of the full pipeline before the timed run (E-J-N10). "
                  "Warmup iterations share the same output-dir parent but land in _warmup_<N>/ "
                  "subdirs and do not print summaries. Use with --start-backend to warm up "
                  "OS page cache, CUDA kernel cache, and GPU graph cache on a cold dev box "
                  "before the measured run. "
                  "NOT RECOMMENDED AS DEFAULT: tempdoc 393 section 2.5 measured CV=4.0% "
                  "without warmup and found that outliers land on arbitrary runs (not "
                  "specifically run 1), so --warmup 1 doubles measurement time without a "
                  "validated CV improvement. Keep default=0 unless the 4.4%-to-1.5% claim "
                  "is validated with N=5+ matched pairs (currently deferred)."
              ))
@click.option("--search-load-qpm", "search_load_qpm", type=int, default=None,
              help="Tempdoc 885: drive N queries/minute (evenly spaced) against "
                   "POST /api/knowledge/search on a background thread DURING ingest + the "
                   "enrichment wait, then record a `search_load` block in summary.json. Each "
                   "request holds the Worker's foreground-load gauge up for its duration, which "
                   "is what drives the indexing duty cycle. Off by default; mutually exclusive "
                   "with --search-load.")
@click.option("--search-load", "search_load_mode", type=click.Choice(["continuous"]), default=None,
              help="Tempdoc 885: as --search-load-qpm but back-to-back with one request in "
                   "flight (the continuous MCP-style agent loop).")
@click.option("--first-search-probe", "first_search_probe", is_flag=True, default=False,
              help="Tempdoc 885 item 19: after every batch of --first-search-probe-files newly "
                   "indexed documents, issue ONE search and record its latency SEPARATELY from "
                   "--search-load* (reopen-on-demand moves the segment-open cost onto exactly "
                   "that first post-indexing query). Reported under summary.json "
                   "`cadence.first_search_after_indexing`. Off by default.")
@click.option("--first-search-probe-files", "first_search_probe_files",
              type=int, default=DEFAULT_BATCH_MIN_FILES, show_default=True,
              help="Newly indexed documents that must accumulate before --first-search-probe "
                   "issues its next query. Ignored unless --first-search-probe is set.")
@click.option("--settle-index", "settle_index", is_flag=True, default=False,
              help="Tempdoc 931 sec E item 10: purge deleted-but-unmerged documents from the "
                   "active index after the readiness gate and before the query phase, so two "
                   "arms of a paired comparison query indexes with equal merge state. Off by "
                   "default (it holds the writer for the duration of a force-merge).")
@click.option("--json", "json_flag", is_flag=True, hidden=True, help="Alias for top-level --json.")
@click.option(
    "--skip-projection", "skip_projections", multiple=True,
    help="Skip named projection at end-of-run (repeatable). Tempdoc 400 "
         "post-implementation-critique 6.1 — useful when iterating on "
         "a single flaky projection without losing other signals.",
)
@click.pass_context
def cmd_run(ctx, dataset, modes, base_url, output_dir, top_k, embedding, splade, query_syntax, lambdamart, cross_encoder, allow_errors, max_queries, context_coverage, thresholds, history_db, corpus_dir, skip_ingest, pipeline, timeline_path, start_backend, llm, qu, filter_norm, clean, reset, cpu, allow_degraded, index_cache_flag, pin_index_selector_key, config_path, duplicate_prevalence_input_spec, warmup_count, search_load_qpm, search_load_mode, first_search_probe, first_search_probe_files, settle_index, json_flag, skip_projections):
    """Execute an evaluation run."""
    if json_flag:
        ctx.obj["json"] = True
    from .. import cadence as cadence_mod
    from .. import search_load as search_load_mod
    try:
        search_load_spec = search_load_mod.resolve_spec(
            search_load_qpm, search_load_mode == "continuous",
        )
        first_search_probe_spec = cadence_mod.resolve_probe_spec(
            first_search_probe, first_search_probe_files,
        )
    except ValueError as e:
        click.echo(f"Error: {e}", err=True)
        sys.exit(1)
    from .. import ingest as ingest_mod
    from .. import run as run_module

    # Apply YAML config if provided (item 11).
    backend_proc = None
    env_overrides: dict[str, str] = {}
    if config_path:
        from .. import run_config
        config = run_config.load_config(Path(config_path))
        cli_args = run_config.config_to_cli_args(config)
        dataset = cli_args.get("dataset", dataset)
        modes = cli_args.get("modes", modes)
        embedding = cli_args.get("embedding", embedding)
        splade = cli_args.get("splade", splade)
        query_syntax = cli_args.get("query_syntax", query_syntax)
        pipeline = cli_args.get("pipeline", pipeline)
        top_k = cli_args.get("top_k", top_k)
        max_queries = cli_args.get("max_queries", max_queries)
        output_dir = cli_args.get("output_dir", output_dir)
        context_coverage = cli_args.get("context_coverage", context_coverage)
        clean = cli_args.get("backend_clean", clean)
        env_overrides = run_config.apply_env_overrides(config)

    # Validate required args (either from CLI or config).
    if not dataset:
        click.echo("Error: --dataset is required (via CLI or --config)", err=True)
        sys.exit(1)
    # Tempdoc 787 item 4a: accept the register's catalog-qualified slug (`beir/scifact`) as well
    # as the bare registry key (`scifact`). Normalize once at the CLI boundary so the whole run
    # pipeline (load, ingest/materialize, corpus dirs, labels) keys on one canonical name.
    from .. import corpora
    dataset = corpora.normalize_dataset_name(dataset)
    if not modes and max_queries != 0:
        click.echo("Error: --modes is required (via CLI or --config) when --max-queries != 0", err=True)
        sys.exit(1)

    # Phase 6 / 6.1: forward --skip-projection through env so run.py can
    # pass it to run_all_discovered without plumbing another param
    # through _run_iteration's already-long signature.
    if skip_projections:
        os.environ["JUSTSEARCH_SKIP_PROJECTIONS"] = ",".join(skip_projections)

    # --pipeline implies --embedding --splade
    if pipeline:
        embedding = True
        splade = True

    # --reset and --start-backend are mutually exclusive (355).
    if reset and start_backend:
        click.echo("Error: --reset and --start-backend are mutually exclusive", err=True)
        sys.exit(1)

    # --llm requires --start-backend (369).
    if llm and not start_backend:
        click.echo("Error: --llm requires --start-backend", err=True)
        sys.exit(1)

    # 369: --llm injects autostart env var so the backend starts llama-server.
    # 366: also enable full GPU offload (99 layers) — CPU-only is 5-10x slower.
    if llm:
        env_overrides["JUSTSEARCH_AI_AUTOSTART_ENABLED"] = "true"
        env_overrides.setdefault("JUSTSEARCH_GPU_LAYERS", "99")

    # 366: --qu enables Query Understanding (experimental, requires --llm).
    if qu and not llm:
        click.echo("Error: --qu requires --llm", err=True)
        sys.exit(1)
    if qu:
        env_overrides["JUSTSEARCH_QU_ENABLED"] = "true"

    # 366: --filter-norm enables filter value normalization (experimental, requires --llm).
    if filter_norm and not llm:
        click.echo("Error: --filter-norm requires --llm", err=True)
        sys.exit(1)
    if filter_norm:
        env_overrides["JUSTSEARCH_FILTER_NORM_ENABLED"] = "true"

    # 381: --cpu disables GPU for all ONNX encoders (test CPU inference paths on GPU machines).
    if cpu:
        if llm:
            click.echo("Error: --cpu and --llm are mutually exclusive (LLM requires GPU)", err=True)
            sys.exit(1)
        env_overrides["JUSTSEARCH_GPU_ENABLED"] = "false"

    # Validate warmup count
    if warmup_count < 0:
        click.echo("Error: --warmup must be >= 0", err=True)
        sys.exit(1)

    # --- Index-cache opt-in gate (tempdoc 751 WP3). THE set-site. ------------
    # Default OFF => byte-identical fresh build (no cache seam is touched). The
    # SAME boolean gates BOTH adopt (backend.py) and publish (below). Flag wins
    # over env; an explicit --fresh-index (index_cache_flag is False) beats env=1.
    if index_cache_flag is not None:
        index_cache_enabled = index_cache_flag
    else:
        index_cache_enabled = os.environ.get("JUSTSEARCH_INDEX_CACHE_ADOPT") == "1"

    from ..raw_corpus_manifest import resolve_raw_corpus_context

    raw_context = resolve_raw_corpus_context(
        dataset,
        Path(corpus_dir) if corpus_dir else None,
        env_overrides=env_overrides,
    )
    duplicate_prevalence_request = None
    if duplicate_prevalence_input_spec is not None:
        from ..duplicate_prevalence_production import (
            ProductionDuplicatePrevalenceError,
            load_input_spec,
        )

        try:
            duplicate_prevalence_request = load_input_spec(
                duplicate_prevalence_input_spec
            )
            request_root = duplicate_prevalence_request.source.raw_root.resolve(
                strict=True
            )
        except (ProductionDuplicatePrevalenceError, OSError) as exc:
            raise click.UsageError(str(exc)) from exc
        if env_overrides.get("JUSTSEARCH_CORPUS_SIGNATURE") or os.environ.get(
            "JUSTSEARCH_CORPUS_SIGNATURE"
        ):
            raise click.UsageError(
                "--duplicate-prevalence-input-spec forbids JUSTSEARCH_CORPUS_SIGNATURE"
            )
        if raw_context is None:
            raise click.UsageError(
                "--duplicate-prevalence-input-spec requires a resolved raw corpus"
            )
        if not modes or not any(part.strip() for part in modes.split(",")):
            raise click.UsageError(
                "--duplicate-prevalence-input-spec requires nonempty --modes"
            )
        if request_root != raw_context.root:
            raise click.UsageError(
                "duplicate-prevalence source.raw_root must exactly match the resolved raw corpus"
            )
        if index_cache_enabled:
            raise click.UsageError(
                "--duplicate-prevalence-input-spec requires --fresh-index"
            )
        if not start_backend or not clean or skip_ingest:
            raise click.UsageError(
                "--duplicate-prevalence-input-spec requires --start-backend --clean "
                "with ingestion enabled"
            )

    # Run warmup iterations (if any), then the timed run.
    # Each iteration gets its own backend lifecycle when --start-backend is set,
    # so warmup runs genuinely exercise cold-start paths (OS cache, CUDA kernel
    # cache, GPU graph cache) before the timed iteration.
    total_iterations = warmup_count + 1
    base_output_dir = Path(output_dir)
    for iter_idx in range(total_iterations):
        is_warmup = iter_idx < warmup_count
        iter_output_dir = (
            base_output_dir / f"_warmup_{iter_idx + 1}" if is_warmup else base_output_dir
        )
        if warmup_count > 0:
            banner = (
                f"WARMUP iteration {iter_idx + 1}/{warmup_count} "
                f"(results → {iter_output_dir}; NOT reported)"
                if is_warmup
                else f"TIMED iteration (after {warmup_count} warmup{'s' if warmup_count > 1 else ''})"
            )
            log.info("=" * 72)
            log.info(banner)
            log.info("=" * 72)

        _run_iteration(
            ctx=ctx,
            dataset=dataset,
            modes=modes,
            base_url=base_url,
            output_dir=str(iter_output_dir),
            top_k=top_k,
            embedding=embedding,
            splade=splade,
            query_syntax=query_syntax,
            lambdamart=lambdamart,
            cross_encoder=cross_encoder,
            allow_errors=allow_errors,
            max_queries=max_queries,
            context_coverage=context_coverage,
            thresholds=thresholds,
            history_db=history_db,
            corpus_dir=corpus_dir,
            skip_ingest=skip_ingest,
            pipeline=pipeline,
            timeline_path=timeline_path,
            start_backend=start_backend,
            llm=llm,
            clean=clean,
            reset=reset,
            allow_degraded=allow_degraded,
            index_cache_enabled=index_cache_enabled,
            pin_index_selector_key=pin_index_selector_key,
            env_overrides=env_overrides,
            search_load_spec=search_load_spec,
            first_search_probe_spec=first_search_probe_spec,
            settle_index=settle_index,
            json_flag=json_flag,
            is_warmup=is_warmup,
            duplicate_prevalence_request=duplicate_prevalence_request,
            **({"raw_context": raw_context} if raw_context is not None else {}),
        )


@click.command("requery")
@click.option("--dataset", required=True)
@click.option("--modes", required=True)
@click.option("--base-url", default=_DEFAULT_BASE_URL, show_default=True)
@click.option("--output-dir", type=click.Path(), default=str(DEFAULT_EVAL_RESULTS), show_default=True)
@click.option("--top-k", default=10, show_default=True)
@click.option("--embedding/--no-embedding", default=False)
@click.option("--splade/--no-splade", default=False)
@click.option(
    "--query-syntax", "query_syntax",
    type=click.Choice(["simple", "lucene"]), default=None,
    help="Q-020 / F-046: see `jseval run --help`.",
)
@click.option("--allow-errors", is_flag=True)
@click.option("--max-queries", default=0, show_default=True, help="Cap queries (0 = all).")
@click.option("--context-coverage", is_flag=True, help="Compute excerpt coverage metrics.")
@click.option("--thresholds", default="0.25,0.5", show_default=True, help="Coverage threshold rates.")
@click.option("--history-db", type=click.Path(), default=None, help="Shared history database path.")
@click.pass_context
def cmd_requery(ctx, dataset, modes, base_url, output_dir, top_k, embedding, splade, query_syntax, allow_errors, max_queries, context_coverage, thresholds, history_db):
    """Re-run queries only (skip ingest/readiness wait)."""
    from .. import run as run_module

    summary = run_module.execute_run(
        dataset_name=dataset,
        base_url=base_url,
        modes=[m.strip() for m in modes.split(",")] if modes else [],
        top_k=top_k,
        max_queries=max_queries,
        embedding_enabled=embedding,
        splade_enabled=splade,
        skip_readiness=True,
        allow_errors=allow_errors,
        output_dir=Path(output_dir),
        context_coverage=context_coverage,
        coverage_thresholds=[float(t) for t in thresholds.split(",")],
        history_db=Path(history_db) if history_db else None,
        query_syntax=query_syntax,
    )
    if ctx.obj.get("json"):
        click.echo(json.dumps(summary, indent=2, default=str))
    else:
        _print_summary(summary)


def _run_iteration(
    *,
    ctx,
    dataset,
    modes,
    base_url,
    output_dir,
    top_k,
    embedding,
    splade,
    lambdamart,
    cross_encoder,
    allow_errors,
    max_queries,
    context_coverage,
    thresholds,
    history_db,
    corpus_dir,
    skip_ingest,
    pipeline,
    timeline_path,
    start_backend,
    llm,
    clean,
    reset,
    allow_degraded,
    index_cache_enabled,
    pin_index_selector_key=None,
    query_syntax=None,
    env_overrides,
    search_load_spec=None,
    first_search_probe_spec=None,
    settle_index=False,
    json_flag,
    is_warmup,
    raw_context=None,
    duplicate_prevalence_request=None,
):
    """Run a single pipeline iteration (start backend → ingest → eval → stop backend).

    For warmup iterations, summaries/JSON are suppressed on stdout so that only
    the final timed iteration's result reaches consumers (e.g., CI parsers).
    """
    backend_proc = None
    effective_base_url = base_url

    from ..raw_corpus_manifest import (
        resolve_raw_corpus_context,
        validate_raw_corpus_context,
    )

    if raw_context is None:
        raw_context = resolve_raw_corpus_context(
            dataset,
            Path(corpus_dir) if corpus_dir else None,
            env_overrides=env_overrides,
        )
    if raw_context is not None:
        validate_raw_corpus_context(
            raw_context,
            env_overrides,
            expected_dataset=dataset,
            explicit_dir=Path(corpus_dir) if corpus_dir else None,
        )

    if start_backend:
        from .. import backend as backend_mod
        port = env_overrides.get(
            "JUSTSEARCH_API_PORT", os.environ.get("JUSTSEARCH_API_PORT", "33221")
        )
        # Thread the effective port into start_backend so its own readiness health-check
        # polls the port the JVM actually binds to (from env_overrides) rather than
        # backend.py's default 33221 — otherwise the check can false-positive against an
        # unrelated concurrent backend already listening on 33221.
        # Tempdoc 751 P.5: the selector's corpus AXIS must be known BEFORE the
        # backend starts. Thread the explicit --corpus-dir (if any) AND the dataset
        # name down; the shared index_identity.resolve_corpus_axis (invoked inside
        # start_backend -> _run_with_cache -> compute_selector) turns them into the
        # one canonical corpus_signature + watched-path binding both the publisher
        # and the adopter agree on (finding 2). Unresolvable (BEIR/unknown) -> the
        # backend disables the cache for this run, fail-quiet (finding 1's WARN).
        cache_corpus_dir = Path(corpus_dir) if corpus_dir else None
        backend_proc = backend_mod.start_backend(
            clean=clean, env_overrides=env_overrides or None, llm=llm, port=int(port),
            index_cache_mode=("on" if index_cache_enabled else "off"),
            corpus_dir=cache_corpus_dir,
            dataset_name=dataset,
            pin_selector_key=pin_index_selector_key,
            **({"raw_context": raw_context} if raw_context is not None else {}),
        )
        effective_base_url = f"http://127.0.0.1:{port}"

    # Tempdoc 751 WP3: the adopt-side outcome (None when the cache was not engaged)
    # threads into run provenance (summary["index_cache"]) and gates the publish hook.
    cache_outcome = backend_proc.cache_outcome if backend_proc is not None else None

    if reset:
        _reset_index(effective_base_url)

    if not start_backend:
        _check_build_freshness(effective_base_url)

    from ..types import IngestConfig
    backend_popen = backend_proc.proc if backend_proc else None
    process_check = (lambda: backend_popen.poll() is None) if backend_popen else None
    ingest_config = IngestConfig(
        base_url=effective_base_url,
        dense_enabled=embedding,
        splade_enabled=splade,
        pipeline=pipeline,
        timeline_path=Path(timeline_path) if timeline_path else None,
        json_mode=ctx.obj.get("json", False) or json_flag,
        process_check=process_check,
    )
    # Tempdoc 751 WP3 publish hook: capture the live identity + attestation WHILE
    # the backend is up (inside the try), then publish AFTER stop_backend closes
    # the files (sec O.2). Keyed off backend state only, never off the eval-results
    # run dir existing (sec O.8.2 trap).
    publish_inputs = None
    publish_selector_key = None
    published_entry = None
    try:
        # Tempdoc 644 Axis 2: instrument-integrity guard. Refuse to emit numbers when the
        # realized engine set diverges from the intended one (the worktree silent
        # cross-encoder-off trap), unless --allow-degraded. Model-present signals are
        # startup-stable (644 U4), so this runs before the expensive ingest. Inside the
        # try/finally so a started backend is still stopped if we exit.
        assert_run_capabilities(
            effective_base_url, modes, cross_encoder=cross_encoder,
            allow_degraded=allow_degraded,
        )
        _do_run(
            ctx, dataset, modes, effective_base_url, output_dir, top_k, embedding,
            splade, lambdamart, cross_encoder, allow_errors, max_queries,
            context_coverage, thresholds, history_db, corpus_dir,
            skip_ingest, ingest_config, env_overrides,
            suppress_stdout=is_warmup, index_cache=cache_outcome,
            query_syntax=query_syntax, search_load_spec=search_load_spec,
            first_search_probe_spec=first_search_probe_spec,
            settle_index=settle_index,
            duplicate_prevalence_request=duplicate_prevalence_request,
            **({"raw_context": raw_context} if raw_context is not None else {}),
        )
        # Publish only a fresh build (outcome != adopted) done under --clean, and
        # only when the selector key is available. Capture happens while up.
        if (
            index_cache_enabled and start_backend and clean
            and backend_proc is not None
        ):
            _oc = backend_proc.cache_outcome or {}
            publish_selector_key = _oc.get("selector_key")
            if _oc.get("mode") != "adopted" and publish_selector_key:
                if raw_context is not None:
                    validate_raw_corpus_context(
                        raw_context,
                        env_overrides,
                        expected_dataset=dataset,
                        explicit_dir=Path(corpus_dir) if corpus_dir else None,
                    )
                publish_inputs = _capture_publish_inputs(
                    effective_base_url, backend_proc.spawn_env or {}, dataset,
                    backend_proc.data_dir,
                )
    finally:
        if backend_proc is not None:
            from .. import backend as backend_mod
            backend_mod.stop_backend(backend_proc.proc, data_dir=backend_proc.data_dir)
            if publish_inputs is not None and publish_selector_key:
                published_entry = _publish_after_stop(
                    backend_proc.data_dir, publish_selector_key, publish_inputs,
                    raw_context=raw_context,
                    env_overrides=backend_proc.spawn_env or env_overrides,
                )

    # 751 P.5 WP-2: the warm helper reuses this exact lifecycle and needs the
    # adopt-side outcome + any freshly-published entry to report published vs
    # already-cached. cmd_run's loop ignores the return.
    return {"cache_outcome": cache_outcome, "published_entry": published_entry}


def _reset_index(base_url: str) -> None:
    """Reset index via POST /api/debug/reset-index (tempdoc 355)."""
    import httpx

    url = f"{base_url}/api/debug/reset-index"
    log.info("Resetting index via %s", url)
    try:
        resp = httpx.post(url, timeout=30)
    except httpx.ConnectError:
        click.echo(f"Error: cannot connect to backend at {base_url}", err=True)
        sys.exit(1)
    if resp.status_code == 404:
        click.echo("Error: backend not in eval mode (got 404). Use runHeadlessEval.", err=True)
        sys.exit(1)
    if resp.status_code != 200:
        click.echo(f"Error: index reset failed: {resp.status_code} {resp.text}", err=True)
        sys.exit(1)
    log.info("Index reset complete")


def _check_build_freshness(base_url: str) -> None:
    """371: Warn if the running backend's build stamp doesn't match the on-disk distribution."""
    from .._paths import REPO_ROOT

    # Lane F stage A item A13: the ADR-0021 stamp moved with its producing task from the deleted
    # Worker distribution to the one surviving Engine distribution.
    stamp_path = (
        REPO_ROOT / "modules" / "ui" / "build" / "install" / "ui" / "build-stamp.txt"
    )
    if not stamp_path.exists():
        log.debug("No build-stamp.txt found at %s — skipping freshness check", stamp_path)
        return

    disk_stamp = stamp_path.read_text().strip()
    if not disk_stamp:
        return

    try:
        from ..readiness import flatten_status
        resp = httpx.get(f"{base_url}/api/status", timeout=5)
        resp.raise_for_status()
        running_stamp = flatten_status(resp.json()).get("buildStamp")
    except Exception:
        log.debug("Could not fetch /api/status for freshness check")
        return

    if not running_stamp:
        log.debug("Running backend has no build stamp — skipping freshness check")
        return

    if running_stamp != disk_stamp:
        click.echo(
            f"WARNING: Running backend build stamp ({running_stamp}) does not match "
            f"on-disk distribution ({disk_stamp}). The backend may be serving stale code. "
            f"Use --start-backend for a clean run, or restart the dev stack.",
            err=True,
        )


def _foreground_queries(dataset: str, option: str) -> list[str]:
    """The dataset's own queries, used as the pool for foreground traffic during ingest."""
    from .. import corpora

    try:
        query_records, _qrels, _meta = corpora.load(dataset)
    except Exception as e:
        click.echo(f"Error: {option} needs the dataset's queries: {e}", err=True)
        sys.exit(1)
    queries = [qr.text for qr in query_records.values() if getattr(qr, "text", "").strip()]
    if not queries:
        click.echo(f"Error: dataset {dataset!r} has no queries for {option}", err=True)
        sys.exit(1)
    return queries


def _start_search_load(spec, base_url: str, dataset: str):
    """Start the tempdoc-885 background query loop, or return None when not requested.

    Queries come from the dataset's own query file, in hybrid (server-resolved) mode.
    """
    if spec is None:
        return None
    from .. import search_load as search_load_mod

    runner = search_load_mod.SearchLoadRunner(
        base_url, _foreground_queries(dataset, "--search-load*"), spec,
    )
    runner.start()
    return runner


def _start_first_search_probe(spec, base_url: str, dataset: str):
    """Start the tempdoc-885 item-19 post-batch probe, or None when not requested."""
    if spec is None:
        return None
    from .. import cadence as cadence_mod

    probe = cadence_mod.FirstSearchProbe(
        base_url, _foreground_queries(dataset, "--first-search-probe"), spec,
    )
    probe.start()
    return probe


def _do_run(ctx, dataset, modes, base_url, output_dir, top_k, embedding,
            splade, lambdamart, cross_encoder, allow_errors, max_queries,
            context_coverage, thresholds, history_db, corpus_dir,
            skip_ingest, ingest_config, env_overrides=None,
            suppress_stdout=False, index_cache=None, query_syntax=None,
            search_load_spec=None, first_search_probe_spec=None,
            settle_index=False, raw_context=None, duplicate_prevalence_request=None):
    """Inner run logic (extracted for backend lifecycle try/finally).

    When suppress_stdout is True (used by warmup iterations of --warmup N), the
    per-iteration summary + JSON emissions are suppressed so that only the
    final timed run reaches consumers. Artifact files are still written to
    output_dir for post-hoc inspection.
    """
    from .. import ingest as ingest_mod
    from .. import run as run_module

    if raw_context is not None:
        from ..raw_corpus_manifest import validate_raw_corpus_context

        validate_raw_corpus_context(
            raw_context,
            env_overrides,
            expected_dataset=dataset,
            explicit_dir=Path(corpus_dir) if corpus_dir else None,
        )

    ingest_summary = None
    pipeline_summary = None
    # Tempdoc 751 sec Q sub-bug (b): an ADOPTED cache entry is a complete,
    # confirmed index -- the corpus is already indexed. Re-ingesting the same
    # corpus is redundant (it re-does the ~50-min build the cache exists to skip)
    # AND, because it re-adds an already-watched root, it triggered the additive
    # readiness-floor wedge (sec Q). So on adoption, skip ingest entirely; eval
    # still runs against the adopted index. Only a fresh build (miss / disabled)
    # ingests -- that single pass is what warm publishes and what the floor is for.
    adopted = bool(index_cache) and index_cache.get("mode") == "adopted"
    search_load_block = None
    first_search_block = None
    if not skip_ingest and not adopted:
        # Tempdoc 885: foreground search traffic runs for exactly the ingest + readiness/
        # pipeline wait window, which is the interval the throughput numbers cover.
        load_runner = _start_search_load(search_load_spec, base_url, dataset)
        probe = _start_first_search_probe(first_search_probe_spec, base_url, dataset)
        try:
            ingest_summary = ingest_mod.prepare_corpus(
                dataset_name=dataset,
                config=ingest_config,
                corpus_dir=Path(corpus_dir) if corpus_dir else None,
                **(
                    {"raw_context": raw_context, "env_overrides": env_overrides}
                    if raw_context is not None else {}
                ),
            )
        finally:
            if load_runner is not None:
                search_load_block = load_runner.stop()
            if probe is not None:
                first_search_block = probe.stop()
        if not ingest_summary.get("readiness_passed"):
            click.echo("Warning: readiness gate did not pass after ingestion", err=True)
            click.echo(f"  Reasons: {ingest_summary.get('failure_reasons')}", err=True)
        pipeline_summary = ingest_summary.get("pipeline_summary")
    elif search_load_spec is not None or first_search_probe_spec is not None:
        log.warning(
            "--search-load* / --first-search-probe ignored: no ingest/readiness wait in "
            "this run (skip_ingest=%s, adopted=%s)", skip_ingest, adopted,
        )
    elif adopted:
        log.info(
            "Index cache adopted (%s) -- corpus already indexed; skipping ingest "
            "(tempdoc 751 sec Q).",
            (index_cache or {}).get("entry"),
        )

    summary = run_module.execute_run(
        dataset_name=dataset,
        base_url=base_url,
        modes=[m.strip() for m in modes.split(",")] if modes else [],
        top_k=top_k,
        max_queries=max_queries,
        embedding_enabled=embedding,
        splade_enabled=splade,
        lambdamart_enabled=lambdamart,
        cross_encoder_enabled=cross_encoder,
        allow_errors=allow_errors,
        output_dir=Path(output_dir),
        context_coverage=context_coverage,
        coverage_thresholds=[float(t) for t in thresholds.split(",")],
        history_db=Path(history_db) if history_db else None,
        ingest_summary=ingest_summary,
        pipeline_summary=pipeline_summary,
        env_overrides=env_overrides,
        index_cache=index_cache,
        query_syntax=query_syntax,
        search_load=search_load_block,
        first_search_probe=first_search_block,
        settle_index=settle_index,
        duplicate_prevalence_request=duplicate_prevalence_request,
        **({"raw_context": raw_context} if raw_context is not None else {}),
    )
    if suppress_stdout:
        return
    if ctx.obj.get("json"):
        click.echo(json.dumps(summary, indent=2, default=str))
    else:
        _print_summary(summary)
        if pipeline_summary:
            from .. import timeline as tl
            click.echo(tl.format_pipeline_summary(pipeline_summary))


def _capture_publish_inputs(base_url, spawn_env, dataset, data_dir):
    """Capture the live identity + attestation while the backend is up (751 WP3).

    Returns ``(identity_doc, attestation)`` or ``None`` when the live identity is
    unavailable (fail-quiet: publish is skipped, the run is untouched). The canary
    is run ONCE here and its executed stages recorded as the required set.
    """
    from .. import index_identity

    try:
        identity = index_identity.compute_live_identity(base_url, spawn_env)
    except index_identity.IdentityUnavailable as exc:
        log.warning("Index cache publish skipped: live identity unavailable (%s).", exc.reason)
        return None
    attestation = _build_attestation(base_url, spawn_env, dataset, data_dir)
    if attestation is None:
        return None
    return identity.to_doc(), attestation


def _read_watched_roots(data_dir):
    """Root paths from ``<data_dir>/watched_roots.json`` (review fix F-A).

    Recorded verbatim (un-normalized) in the entry attestation so
    ``confirm_adoption``'s check 6 can compare the adopted copy's roots against
    what the publisher actually watched. Unreadable file -> None (publish is
    then skipped by the caller -- an entry without recorded roots would recreate
    the pre-F-A blind spot).
    """
    import json as _json

    p = Path(data_dir) / "watched_roots.json"
    if not p.is_file():
        return []
    try:
        raw = _json.loads(p.read_text(encoding="utf-8"))
        return [r["path"] for r in raw.get("roots", []) if r.get("path")]
    except (OSError, ValueError, KeyError, TypeError) as exc:
        log.warning("Index cache publish: watched_roots.json unreadable (%s).", exc)
        return None


def _build_attestation(base_url, spawn_env, dataset, data_dir):
    """Assemble the entry attestation from live surfaces (751 M.2 publish)."""
    from .. import index_identity

    try:
        with httpx.Client(base_url=base_url, timeout=10) as client:
            status = _get_json_or_none(client, "/api/status")
            commit_meta = _get_json_or_none(client, "/api/debug/commit-metadata")
            canary = _run_canary(client, dataset)
    except Exception as exc:  # pragma: no cover - defensive
        log.warning("Index cache publish skipped: attestation capture failed (%s).", exc)
        return None
    if status is None:
        log.warning("Index cache publish skipped: /api/status unavailable for attestation.")
        return None

    def _find(doc, key):
        v = index_identity._deep_find(doc, key)
        return None if v is index_identity._MISSING else v

    counts = {
        f: _find(status, f)
        for f in (
            "embeddingDocCount", "spladeDocCount",
            "chunkDocCount", "chunkVectorCoveragePercent",
        )
    }
    watched_roots = _read_watched_roots(data_dir)
    if watched_roots is None:
        log.warning("Index cache publish skipped: cannot record watched roots (F-A).")
        return None
    return {
        "build_state": (commit_meta or {}).get("build_state"),
        "commit_time": (commit_meta or {}).get("commit_time"),
        "generation_id": _find(status, "activeGenerationId"),
        "counts": counts,
        "canary": canary,
        # Operational inspectability only (which corpus does this entry serve?);
        # correctness never reads this -- the selector key embeds the corpus axis.
        "dataset": dataset,
        # Review fix F-A: confirm_adoption check 6 compares the adopted copy's
        # watched_roots.json against these publisher-recorded roots.
        "watched_roots": watched_roots,
    }


def _run_canary(client, dataset):
    """Run the behavioral canary once live; record executed stages as required (751 M.2)."""
    query = _canary_query(dataset)
    required = ["sparse-retrieval", "dense-retrieval", "fusion"]
    try:
        resp = client.post(
            "/api/knowledge/search",
            json={"query": query, "mode": "hybrid", "limit": 5, "debug": True},
        )
        body = resp.json() if resp.status_code == 200 else {}
    except Exception:  # pragma: no cover - defensive; a bad canary only lowers hit rate
        body = {}
    trace = body.get("searchTrace") or {}
    executed = {
        s.get("id") for s in (trace.get("stages") or [])
        if s.get("status") == "executed"
    }
    # chunk-merge is query-conditional: require it only if it actually ran now.
    if "chunk-merge" in executed:
        required = required + ["chunk-merge"]
    return {"query": query, "required_stages": required}


def _canary_query(dataset):
    """First ~4 alnum tokens of the dataset name (fixed generic fallback)."""
    import re

    tokens = [t for t in re.split(r"[^A-Za-z0-9]+", dataset or "") if t][:4]
    return " ".join(tokens) if tokens else "search"


def _get_json_or_none(client, path):
    try:
        resp = client.get(path)
    except Exception:
        return None
    if resp.status_code != 200:
        return None
    try:
        return resp.json()
    except Exception:
        return None


def _publish_after_stop(
    data_dir, selector_key, publish_inputs, *, raw_context=None, env_overrides=None,
):
    """Publish the fresh-built data dir AFTER stop_backend (751 WP3; fail-quiet).

    Returns the published entry dir name (751 P.5 WP-2 -- the warm helper reports
    it), or ``None`` when publish was skipped/failed.
    """
    from .. import index_cache

    if raw_context is not None:
        from ..raw_corpus_manifest import validate_raw_corpus_context

        validate_raw_corpus_context(raw_context, env_overrides)

    identity_doc, attestation = publish_inputs
    try:
        entry_dir = index_cache.publish(data_dir, selector_key, identity_doc, attestation)
    except Exception as exc:  # publish is fail-quiet internally; never fail the run
        log.warning("Index cache publish raised unexpectedly (%s) -- ignored.", exc)
        return None
    if entry_dir is not None:
        log.info("Index cache published entry %s.", entry_dir.name)
        return entry_dir.name
    return None


def warm_index_cache(dataset, corpus_dir, port):
    """Publish (or confirm already-cached) the index-cache entry for one corpus
    axis, reusing the exact ``jseval run ... --index-cache`` lifecycle (751 P.5 WP-2).

    Drives ``jseval run (--dataset X | --corpus-dir DIR) --max-queries 0 --pipeline
    --start-backend --clean --index-cache`` via :func:`_run_iteration` (the boot +
    adopt + publish lifecycle is NOT duplicated).

    Returns ``{"status": "published"|"already-cached"|"disabled", "entry": <prefix
    or None>, "reason": <str or None>}``. Resolves the axis FIRST and fails LOUD
    (``status`` ``disabled``) on an unresolvable axis: warm exists to cache, so a
    chain-config error that would silently skip caching must surface -- unlike the
    run path's fail-quiet disable.
    """
    from types import SimpleNamespace

    from .. import index_identity

    axis = index_identity.resolve_corpus_axis(
        dataset, Path(corpus_dir) if corpus_dir else None,
    )
    if axis.reason is not None:
        return {"status": "disabled", "entry": None, "reason": axis.reason}

    import tempfile

    ctx = SimpleNamespace(obj={"json": False})
    out_dir = Path(tempfile.mkdtemp(prefix="jseval-warm-"))
    result = _run_iteration(
        ctx=ctx, dataset=dataset, modes=None,
        base_url=f"http://127.0.0.1:{port}", output_dir=str(out_dir), top_k=10,
        embedding=False, splade=False, lambdamart=False, cross_encoder=False,
        allow_errors=False, max_queries=0, context_coverage=False,
        thresholds="0.25,0.5", history_db=None, corpus_dir=corpus_dir,
        skip_ingest=False, pipeline=True, timeline_path=None, start_backend=True,
        llm=False, clean=True, reset=False, allow_degraded=False,
        index_cache_enabled=True, env_overrides={"JUSTSEARCH_API_PORT": str(port)},
        json_flag=False, is_warmup=False,
    )
    result = result or {}
    outcome = result.get("cache_outcome") or {}
    published = result.get("published_entry")
    if published:
        return {"status": "published", "entry": published[:16], "reason": None}
    if outcome.get("mode") == "adopted":
        return {
            "status": "already-cached",
            "entry": (outcome.get("entry") or "")[:16],
            "reason": None,
        }
    # Neither published nor adopted: the cache was disabled mid-lifecycle or the
    # publish was skipped -- surface loudly rather than pretend success.
    return {
        "status": "disabled",
        "entry": None,
        "reason": outcome.get("mode") or "no cache outcome (publish skipped)",
    }


def _print_summary(summary: dict) -> None:
    click.echo(f"\nDataset: {summary['dataset']}  ({summary['query_count']} queries)")
    click.echo(f"Git SHA: {summary.get('git_sha', 'unknown')}")
    click.echo()
    for mode, info in summary.get("per_mode", {}).items():
        metrics = info["aggregate_metrics"]
        click.echo(f"  {mode}:")
        for k, v in sorted(metrics.items()):
            click.echo(f"    {k}: {v:.4f}")
        lat = info.get("latency_stats", {})
        if lat.get("query_count"):
            click.echo(
                f"    latency: p50={lat['p50_ms']}ms  p95={lat['p95_ms']}ms  "
                f"p99={lat['p99_ms']}ms  mean={lat['mean_ms']}ms"
            )
        click.echo(f"    comparable: {info['comparable']}")
        if not info["comparable"]:
            click.echo(f"    reasons: {info['comparability_reasons']}")
        click.echo()


COMMANDS = [cmd_run, cmd_requery]
