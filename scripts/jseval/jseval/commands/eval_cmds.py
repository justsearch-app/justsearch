"""jseval eval_cmds commands (split from cli.py — tempdoc 645)."""
from __future__ import annotations

import json
import sys
from pathlib import Path
import logging

import click
from ._common import _DEFAULT_BASE_URL_EVAL, _write_bench_output

log = logging.getLogger(__name__)


@click.command("rag-eval")
@click.option("--profile", default="stub-jaccard", show_default=True)
@click.option("--baseline", type=click.Path(exists=True), default=None)
@click.option("--output-dir", type=click.Path(), default=None)
def cmd_rag_eval(profile, baseline, output_dir):
    """RAG quality evaluation (Gradle test + metric comparison)."""
    from .. import rag_eval

    candidate = rag_eval.run_rag_eval(profile=profile)
    if "error" in candidate:
        click.echo(f"RAG eval failed: {candidate['error']}", err=True)
        sys.exit(1)

    if baseline:
        baseline_data = json.loads(Path(baseline).read_text(encoding="utf-8"))
        decision = rag_eval.diff_rag_eval(baseline_data, candidate)
        click.echo(rag_eval.format_console(decision))
        if decision["gate_status"] == "fail":
            sys.exit(1)
    else:
        click.echo(json.dumps(candidate, indent=2, default=str))

    _write_bench_output(candidate, output_dir, "rag-eval.json")


@click.command("extract-eval")
@click.option("--variant", required=True, type=click.Path(exists=True),
              help="Directory with extracted .txt files to evaluate")
@click.option("--ground-truth", required=True, type=click.Path(exists=True),
              help="Directory with ground truth .txt files")
@click.option("--manifest", type=click.Path(exists=True), default=None,
              help="Stratified manifest JSON for bracket breakdown (optional)")
@click.option("--reference", type=click.Path(exists=True), default=None,
              help="Reference extraction dir for comparison (e.g., Docling)")
@click.pass_context
def cmd_extract_eval(ctx, variant, ground_truth, manifest, reference):
    """Evaluate extraction quality via word overlap with ground truth."""
    from .. import extract_eval

    result = extract_eval.evaluate(
        variant_dir=Path(variant),
        ground_truth_dir=Path(ground_truth),
        manifest_path=Path(manifest) if manifest else None,
        reference_dir=Path(reference) if reference else None,
    )

    if ctx.obj.get("json"):
        click.echo(json.dumps(result, indent=2, default=str))
    else:
        click.echo(extract_eval.format_console(result))


# ---------------------------------------------------------------------------
# Output formatting
# ---------------------------------------------------------------------------


@click.command("ner-eval")
@click.option(
    "--model-dir",
    type=click.Path(exists=True),
    default="models/onnx/ner",
    show_default=True,
    help="Path to NER ONNX model directory (must contain model.onnx + tokenizer.json).",
)
@click.option("--max-sentences", default=0, show_default=True, help="Cap sentences (0 = all).")
@click.option("--data-dir", type=click.Path(exists=True, resolve_path=True), default=None,
              help="CoNLL-2003 data directory (default: jseval/data/conll2003/).")
@click.pass_context
def cmd_ner_eval(ctx, model_dir, max_sentences, data_dir):
    """Evaluate NER model quality against CoNLL-2003 (entity-level F1)."""
    from .. import ner_eval

    verbose = ctx.obj.get("json", False) is False
    results = ner_eval.run_ner_eval(
        model_dir=Path(model_dir),
        max_sentences=max_sentences,
        verbose=verbose,
        data_dir=Path(data_dir) if data_dir else None,
    )

    if ctx.obj.get("json"):
        click.echo(json.dumps(results, indent=2, default=str))
    else:
        ner_eval.print_ner_eval_results(results)


@click.command("retrieval-eval")
@click.option("--queries", type=click.Path(exists=True), required=True,
              help="Path to eval queries JSON (MultiHop-RAG format).")
@click.option("--corpus-dir", type=click.Path(exists=True), required=True,
              help="Path to corpus directory (for title→filename mapping).")
@click.option("--corpus-jsonl", type=click.Path(exists=True), default=None,
              help="Path to a BEIR-format corpus.jsonl for the rag_reachability guard "
                   "(tempdoc 749). Defaults to <corpus-dir>/corpus.jsonl; the guard reports "
                   "'not-applicable' when neither exists.")
@click.option("--base-url", default=_DEFAULT_BASE_URL_EVAL, show_default=True)
@click.option("--top-k", default=10, show_default=True)
@click.option("--max-tokens", default=8192, show_default=True)
@click.option("--types", default=None, help="Comma-separated question types to include.")
@click.option("--max-queries", default=None, type=int, help="Limit number of queries.")
@click.option("--skip-reachability", is_flag=True, default=False,
              help="Skip the rag_reachability guard (tempdoc 749) that fail-closes when a "
                   "sub-threshold (chunkless-by-construction) doc isn't reachable via the "
                   "primary RAG chunk path.")
@click.option("--output-dir", type=click.Path(), default=None)
@click.pass_context
def cmd_retrieval_eval(ctx, queries, corpus_dir, corpus_jsonl, base_url, top_k, max_tokens,
                        types, max_queries, skip_reachability, output_dir):
    """Tier 1: Evaluate retrieval quality with Hits@K, MRR, and evidence recall ($0 cost)."""
    from .. import agent_retrieval_eval as are

    qa = are.load_queries(Path(queries))
    question_types = [t.strip() for t in types.split(",")] if types else None
    result = are.run_retrieval_eval(
        qa, base_url=base_url, top_k=top_k, max_tokens=max_tokens,
        question_types=question_types, max_queries=max_queries,
        corpus_dir=Path(corpus_dir),
        corpus_jsonl=Path(corpus_jsonl) if corpus_jsonl else None,
        skip_reachability=skip_reachability,
    )

    if ctx.obj.get("json"):
        click.echo(json.dumps(result, indent=2, default=str))
    else:
        click.echo(are.format_retrieval_console(result))

    if output_dir:
        out = Path(output_dir)
        out.mkdir(parents=True, exist_ok=True)
        (out / "retrieval-eval.json").write_text(
            json.dumps(result, indent=2, default=str), encoding="utf-8")
        click.echo(f"Written to {out / 'retrieval-eval.json'}")

    if "error" in result:
        click.echo(f"Retrieval eval failed: {result['error']}", err=True)
        sys.exit(1)


@click.command("tier2-eval")
@click.option("--queries", type=click.Path(exists=True), required=True,
              help="Path to eval queries JSON (MultiHop-RAG format).")
@click.option("--base-url", default=_DEFAULT_BASE_URL_EVAL, show_default=True)
@click.option("--llm-url", default=_DEFAULT_BASE_URL_EVAL, show_default=True,
              help="Chat completions base URL: the Engine's /v1 proxy, which holds a generation "
                   "lease per call, so a reconfigure drains these replies instead of cutting them.")
@click.option("--top-k", default=10, show_default=True)
@click.option("--max-context-tokens", default=8192, show_default=True)
@click.option("--types", default=None, help="Comma-separated question types to include.")
@click.option("--max-queries", default=None, type=int, help="Limit number of queries.")
@click.option("--no-structured", is_flag=True, help="Disable structured JSON output (plain text answers).")
@click.option("--paper-prompt", is_flag=True, help="Use the original MultiHop-RAG paper prompt (ablation for fair comparison).")
@click.option("--source-check", is_flag=True, help="Enable pre-retrieval source existence check (deterministic abstention for absent sources).")
@click.option("--allow-compact-model", is_flag=True, default=False,
              help="Permit tier-2 quality evals against the compact dev-tier chat model; "
                   "results are marked and NOT comparable to standard-model baselines.")
@click.option("--baseline-results", type=click.Path(exists=True, dir_okay=False), default=None,
              help="Saved tier2-eval.json to compare query-by-query with this run.")
@click.option("--output-dir", type=click.Path(), default=None)
@click.pass_context
def cmd_tier2_eval(ctx, queries, base_url, llm_url, top_k, max_context_tokens,
                   types, max_queries, no_structured, paper_prompt, source_check,
                   allow_compact_model, baseline_results, output_dir):
    """Tier 2: Single-shot RAG eval (retrieve + local LLM, $0 cost)."""
    from .. import agent_retrieval_eval as are

    qa = are.load_queries(Path(queries))
    question_types = [t.strip() for t in types.split(",")] if types else None
    cp_dir = Path(output_dir) if output_dir else None
    baseline = None
    if baseline_results:
        try:
            baseline = json.loads(Path(baseline_results).read_text(encoding="utf-8"))
            are.validate_tier2_result(baseline)
        except (OSError, json.JSONDecodeError, are.Tier2ComparisonError) as exc:
            raise click.ClickException(
                f"Tier-2 baseline is not canonical: {exc}") from None

    try:
        result = are.run_tier2_eval(
            qa, base_url=base_url, llm_url=llm_url, top_k=top_k,
            max_context_tokens=max_context_tokens,
            question_types=question_types, max_queries=max_queries,
            structured=not no_structured,
            use_paper_prompt=paper_prompt,
            source_check=source_check,
            checkpoint_dir=cp_dir,
            allow_compact_model=allow_compact_model,
            comparison_baseline=baseline,
            require_served_model=True,
        )
    except are.Tier2ComparisonError as exc:
        raise click.ClickException(f"Tier-2 preflight failed: {exc}") from None

    try:
        are.validate_tier2_result(result)
    except are.Tier2ComparisonError as exc:
        raise click.ClickException(
            f"Tier-2 result is not canonical: {exc}") from None

    if baseline is not None:
        try:
            result["paired_comparison"] = are.compare_tier2_results(baseline, result)
        except are.Tier2ComparisonError as exc:
            raise click.ClickException(f"Tier-2 comparison is incompatible: {exc}") from None

    if ctx.obj.get("json"):
        click.echo(json.dumps(result, indent=2, default=str))
    else:
        click.echo(are.format_tier2_console(result))

    if output_dir:
        out = Path(output_dir)
        out.mkdir(parents=True, exist_ok=True)
        (out / "tier2-eval.json").write_text(
            json.dumps(result, indent=2, default=str), encoding="utf-8")
        click.echo(f"Written to {out / 'tier2-eval.json'}")


COMMANDS = [cmd_rag_eval, cmd_extract_eval, cmd_ner_eval, cmd_retrieval_eval, cmd_tier2_eval]
