"""jseval utility commands (split from cli.py — tempdoc 645)."""
from __future__ import annotations

import json
import os
import sys
import time
from pathlib import Path
import logging

import click

from ._common import _attach_revision, _write_bench_output

log = logging.getLogger(__name__)


def _publication_root(value):
    if value:
        return Path(value)
    from .._paths import REPO_ROOT

    return REPO_ROOT / "scripts" / "jseval" / "public-agent-utility"


@click.command("utility-publication-build")
@click.option("--record", required=True, type=click.Path(exists=True, dir_okay=False))
@click.option("--evidence", required=True, type=click.Path(exists=True, dir_okay=False))
@click.option("--publication-id", required=True)
@click.option("--policy", default=None, type=click.Path(exists=True, dir_okay=False))
@click.option("--root", default=None, type=click.Path())
@click.option("--max-evidence-bytes", type=int, default=None,
              help="Refuse to build a bundle whose evidence file exceeds this size "
                   "(default 80 MiB; a bundle is committed and GitHub rejects blobs "
                   "over 100 MB).")
def cmd_utility_publication_build(record, evidence, publication_id, policy, root,
                                  max_evidence_bytes):
    """Build an immutable accepted publication bundle."""
    from ..utility_publication import DEFAULT_MAX_EVIDENCE_BYTES, build_publication

    path = build_publication(
        root=_publication_root(root), record_path=record, evidence_path=evidence,
        publication_id=publication_id, policy_path=policy,
        max_evidence_bytes=(
            DEFAULT_MAX_EVIDENCE_BYTES if max_evidence_bytes is None else max_evidence_bytes),
    )
    click.echo(f"Wrote {path}")


@click.command("utility-publication-select")
@click.option("--publication-id", default=None)
@click.option("--clear", is_flag=True)
@click.option("--reason", required=True)
@click.option("--root", default=None, type=click.Path())
def cmd_utility_publication_select(publication_id, clear, reason, root):
    """Explicitly select one accepted replayable bundle, or clear current."""
    from ..utility_publication import select_publication

    path = select_publication(
        root=_publication_root(root), publication_id=publication_id,
        clear=clear, reason=reason,
    )
    click.echo(f"Wrote {path}")


@click.command("utility-replay")
@click.option("--publication", required=True)
@click.option("--root", default=None, type=click.Path())
def cmd_utility_replay(publication, root):
    """Verify hashes, sanitize schema, recomposition, verdict, and semantic digest."""
    from ..utility_publication import replay_publication

    candidate = Path(publication)
    if not candidate.exists():
        candidate = _publication_root(root) / "publications" / publication / "publication.v1.json"
    result = replay_publication(candidate)
    click.echo(json.dumps(result, indent=2))


@click.command("utility-evidence-export")
@click.option("--log-dir", required=True, type=click.Path(exists=True, file_okay=False))
@click.option("--out", required=True, type=click.Path(dir_okay=False))
@click.option("--judge-overlay", "judge_overlay", default=None,
              type=click.Path(exists=True, dir_okay=False),
              help="Optional overlay; otherwise use LOG_DIR's judge-overlay.json if present "
                   "(same resolution as utility-recompose --log-dir).")
def cmd_utility_evidence_export(log_dir, out, judge_overlay):
    """Export every attempted cell through the strict public allowlist."""
    from ..utility_evidence import export_log_dir

    path = export_log_dir(log_dir, out, judge_overlay=judge_overlay)
    click.echo(f"Wrote {path}")


@click.command("utility-recompose")
@click.option("--log-dir", "log_dirs", multiple=True,
              type=click.Path(exists=True, file_okay=False),
              help="Repeatable completed Inspect log directory.")
@click.option("--evidence", "evidence_paths", multiple=True,
              type=click.Path(exists=True, dir_okay=False),
              help="Repeatable sanitized observation JSONL; requires no agent dependencies.")
@click.option("--judge-overlay", "judge_overlays", multiple=True,
              type=click.Path(exists=True, dir_okay=False),
              help="Optional, repeatable one-for-one overlay; otherwise use judge-overlay.json if present.")
@click.option("--contamination-class",
              type=click.Choice(["public-pre-cutoff", "post-cutoff", "private-synthetic", "unknown"]),
              default="unknown", show_default=True)
@click.option("--confidence-tier", type=click.Choice(["A", "B", "C"]),
              default="C", show_default=True)
@click.option("--output-dir", required=True, type=click.Path())
@click.pass_context
def cmd_utility_recompose(ctx, log_dirs, evidence_paths, judge_overlays, contamination_class,
                          confidence_tier, output_dir):
    """Pure zero-cost recomposition from completed Inspect evidence."""
    from ..utility_recompose import finalize_evidence, finalize_logs, write_record

    if bool(log_dirs) == bool(evidence_paths):
        raise click.UsageError("provide either --log-dir or --evidence (one or more), not both")
    if evidence_paths and judge_overlays:
        raise click.UsageError("--judge-overlay applies only to --log-dir input")
    if evidence_paths:
        record = finalize_evidence(
            evidence_paths,
            contamination_class=contamination_class,
            confidence_tier=confidence_tier,
        )
    else:
        record = finalize_logs(
            log_dirs,
            judge_overlays=(judge_overlays or None),
            contamination_class=contamination_class,
            confidence_tier=confidence_tier,
        )
    path = write_record(record, output_dir)
    if ctx.obj.get("json"):
        click.echo(json.dumps(record, indent=2))
    else:
        click.echo(f"Wrote {path}")
        click.echo(f"semantic_digest={record['semantic_digest']}")


@click.command("utility-policy-dryrun")
@click.option("--design", required=True, type=click.Path(exists=True, dir_okay=False),
              help="A frozen campaign design (782-hero-campaign-cells.v1 shape), "
                   "e.g. scripts/jseval/782-hero/cells.v1.json.")
@click.option("--policy", "policy_path", default=None,
              type=click.Path(exists=True, dir_okay=False),
              help="Claim policy to dry-run against; defaults to the ACTIVE policy.")
@click.option("--out", default=None, type=click.Path(dir_okay=False),
              help="Optional path for the full JSON report.")
@click.pass_context
def cmd_utility_policy_dryrun(ctx, design, policy_path, out):
    """Dry-run a claim policy against a campaign design BEFORE freezing it.

    Synthesizes a minimal structurally-valid composed record with the design's
    declared shape, evaluates every policy gate against it, and reports which
    gates can never pass. Exits non-zero on any structurally-impossible or
    undetermined gate. Both tempdoc 782 freeze defects were policy-vs-design
    incompatibilities reachable only at run/compose time; this makes them $0.
    """
    from .._paths import REPO_ROOT
    from ..utility_claim_policy import load_policy
    from ..utility_policy_dryrun import DryRunError, dryrun, format_report, load_design

    try:
        report = dryrun(
            load_design(design), load_policy(policy_path), repo_root=REPO_ROOT,
        )
    except DryRunError as error:
        raise click.ClickException(str(error)) from error
    if out:
        Path(out).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    if ctx.obj.get("json"):
        click.echo(json.dumps(report, indent=2))
    else:
        click.echo(format_report(report))
        if out:
            click.echo(f"Wrote {out}")
    ctx.exit(report["exit_code"])


@click.command("utility-compose")
@click.option("--run", "runs", multiple=True, metavar="COND=PATH",
              help="Repeatable. An agent-eval result JSON tagged by condition, "
                   "e.g. --run A=out/agent-eval-A-haiku.json --run C=out/agent-eval-C-haiku.json. "
                   "Repeats of one condition are assigned successive seeds.")
@click.option("--dataset", required=True, help="Corpus slug, e.g. mixed/multihop-rag.")
@click.option("--corpus-signature", default=None, help="Corpus signature/sha256 (pairing identity).")
@click.option("--model", default="haiku", show_default=True)
@click.option("--search-config-key", default=None,
              help="623 config_cohort_key of the live search backend (the with-tool arm's identity).")
@click.option("--contamination-class",
              type=click.Choice(["public-pre-cutoff", "post-cutoff", "private-synthetic", "unknown"]),
              default="public-pre-cutoff", show_default=True)
@click.option("--confidence-tier", type=click.Choice(["A", "B", "C"]), default="C", show_default=True)
@click.option("--exclude-leaked", is_flag=True,
              help="Scan each run's agent_answer text for an answer-key-leak signature "
                   "(a queries.json/queries.jsonl mention) and exclude matched cells from "
                   "the paired statistics before composing (tempdoc 624 §As-built #7 "
                   "follow-up leak-free reanalysis).")
@click.option("--supersedes", default=None, type=click.Path(),
              help="Relative path to the prior record's JSON file this run corrects. "
                   "Must be given together with --revision-reason (tempdoc 624 Design 1). "
                   "When both are given, the composed record gets a `revision` block; "
                   "when neither is given, the record is unchanged (no `revision` field).")
@click.option("--revision-reason", default=None,
              type=click.Choice(sorted(["leak_correction", "judge_rescore", "reseed", "other"])),
              help="Why this record supersedes --supersedes. Must be given together with "
                   "--supersedes. The revision's `changed_fields` is left empty at the CLI "
                   "level -- the caller isn't expected to know exactly which fields changed "
                   "when composing from the command line; that's a deliberate, honest default.")
@click.option("--output-dir", type=click.Path(), default=None)
@click.pass_context
def cmd_utility_compose(ctx, runs, dataset, corpus_signature, model, search_config_key,
                        contamination_class, confidence_tier, exclude_leaked,
                        supersedes, revision_reason, output_dir):
    """Compose agent-eval results into a utility-comparison.v1 record (tempdoc 624).

    Attaches a cohort identity to each run (agent_manifest), pairs the with/without
    -tool arms on condition, aggregates seeds, and emits the canonical record plus
    an Inspect-EvalLog projection. Pure composition over existing run artifacts.

    DEPRECATION NOTE (tempdoc 675): this command composes result files produced by
    the now-retired classic `claude -p` shell-out runner (`agent_retrieval_eval.
    run_agent_eval`, no longer callable via the CLI). The record-grade path is now
    `utility-run` (`cmd_utility_run`), which drives the in-process SDK executor
    directly. Kept working for any pre-existing run artifacts still on disk.
    """
    import datetime as _dt

    from .. import agent_utility_run as aur
    from .. import utility_comparison as uc

    _attach_revision(None, supersedes, revision_reason)  # fail fast before reading run files

    corpus = {"dataset": dataset, "signature": corpus_signature or dataset}
    # Neutral prompt (tempdoc 624 §M.8 pre-registration, Step 0 item 1) -- kept in
    # sync with agent_utility_inspect._PROMPT / agent_retrieval_eval's inline
    # prompt (same wording, placeholder tokens instead of an f-string/`.format`
    # site since this copy only feeds prompt_template_hash, not a live argv).
    prompt_template = (
        "Answer the following question about the document collection at <corpus>. "
        "You may use any tools available to you. "
        "Do not use prior knowledge. Be concise. Question: <query>"
    )
    cli_version = aur.claude_cli_version()
    seed_counter: dict = {}
    summaries = []
    n_flagged = 0
    for spec in runs:
        if "=" not in spec:
            raise click.BadParameter(f"--run must be COND=PATH, got {spec!r}")
        cond, path = spec.split("=", 1)
        cond = cond.strip().upper()
        result = json.loads(Path(path).read_text(encoding="utf-8"))
        seed = result.get("seed")
        if seed is None:
            seed = seed_counter.get(cond, 0)
            seed_counter[cond] = seed + 1
        with_tool = cond in ("B", "C")
        summary = aur.build_compose_summary(
            result, condition=cond, model=model, corpus=corpus, seed=seed,
            prompt_template=prompt_template, cli_version=cli_version,
            search_config_cohort_key=(search_config_key if with_tool else None),
        )
        if exclude_leaked:
            leaked = aur.scan_leaked_answers(result.get("results", []), condition=cond, seed=seed)
            n_flagged += aur.apply_leak_flags([summary], leaked)
        summaries.append(summary)

    if exclude_leaked:
        click.echo(f"leak-scan: flagged {n_flagged} per-query entries (excluded from paired stats)")

    record = uc.compose_utility(
        summaries,
        composed_at=_dt.datetime.now(_dt.timezone.utc).isoformat(),
        external_baselines=uc.CITED_BASELINES,
        contamination_class=contamination_class,
        confidence_tier=confidence_tier,
    )
    _attach_revision(record, supersedes, revision_reason)
    from ..utility_claim_policy import evaluate_claim
    from ..utility_recompose import semantic_digest

    verdict = evaluate_claim(record)
    verdict["accepted"] = False
    verdict["status"] = "rejected"
    verdict["outcome"] = "inconclusive"
    if "legacy_executor_non_claim_grade" not in verdict["reasons"]:
        verdict["reasons"].insert(0, "legacy_executor_non_claim_grade")
    verdict["gates"].append({
        "name": "record_grade_executor",
        "observed": "legacy-agent-eval",
        "threshold": "inspect-ai",
        "passed": False,
        "reason": "legacy result-file composition is retained for forensic compatibility only",
    })
    record["claim_verdict"] = verdict
    record["semantic_digest"] = semantic_digest(record)

    if ctx.obj.get("json"):
        click.echo(json.dumps(record, indent=2, default=str))
    else:
        for slug, by_model in record["measured"].items():
            for m, cell in by_model.items():
                acc, tok = cell["accuracy"], cell["tokens_unique"]
                click.echo(
                    f"[{slug}/{m}] acc {acc['baseline']}->{acc['with_tool']} "
                    f"(d={acc['delta']:+}, McNemar p={acc['mcnemar_p']}); "
                    f"unique-tokens median {tok['baseline']['median']}->"
                    f"{tok['with_tool']['median']} (d_mean={tok['delta_mean']:+})")
        click.echo(f"seeds={record['seed_count']} tier={record['confidence_tier']} "
                   f"contamination={record['coverage']['contamination_class']}")

    _write_bench_output(record, output_dir, "utility-comparison.v1.json")


@click.command("utility-run")
@click.option("--queries", required=True, type=click.Path(exists=True), help="MultiHop-RAG-format queries JSON.")
@click.option("--corpus-dir", required=True, type=click.Path(exists=True), help="Corpus dir (for condition-A file tools).")
@click.option("--corpus-root", default=None, type=click.Path(exists=True, file_okay=False),
              help="Dataset ROOT (corpus.jsonl + qrels/) whose signature the "
                   "--corpus-certification verifies against (tempdoc 624 confirmatory "
                   "pre-registration, 2026-07-17). --corpus-dir stays the leak-safe "
                   "staged/agent subdir and MUST be this root's immediate child. Omit = "
                   "today's single-axis behavior (identity from --corpus-dir).")
@click.option("--mcp-config", default=None, type=click.Path(exists=True), help="JustSearch MCP config (for B/C).")
@click.option("--model", default="haiku", show_default=True)
@click.option("--conditions", default="A,C", show_default=True, help="Comma list, e.g. A,C.")
@click.option("--seeds", default=3, show_default=True, type=int, help="Repeats per cell (Inspect epochs).")
@click.option("--concurrency", default=6, show_default=True, type=int, help="Inspect max_samples (B1: 8-way is safe).")
@click.option("--max-queries", default=None, type=int)
@click.option("--max-budget", default=0.50, show_default=True, help="Max USD per cell.")
@click.option("--timeout-s", default=180, show_default=True, type=int, help="Per-cell timeout (calibrate sets ~2x contended-p95).")
@click.option("--max-turns", default=100, show_default=True, type=int, help="Per-cell agent turn cap (safety net for the pathological tail; the wall-clock timeout is the primary bound).")
@click.option("--calibration", default=None, type=click.Path(exists=True), help="calibration.json from `utility-calibrate` (overrides timeout/concurrency/search-key + filters queries).")
@click.option("--agent-env", "agent_env_specs", multiple=True, metavar="KEY=VALUE",
              help="Repeatable. An env var threaded into every cell's child Agent SDK "
                   "session (tempdoc 725 increment 4 — exposure A/B wiring), e.g. "
                   "--agent-env ENABLE_TOOL_SEARCH=false for the eager arm. Empty by "
                   "default = today's behavior byte-for-byte (no env overlay).")
@click.option("--dataset", required=True, help="Corpus slug, e.g. mixed/multihop-rag.")
@click.option("--corpus-signature", default=None)
@click.option("--corpus-certification", default=None, type=click.Path(exists=True, dir_okay=False),
              help="Fully-certified 707 member record captured into claim-grade source identity.")
@click.option("--search-config-key", default=None, help="623 config_cohort_key of the with-tool backend.")
@click.option("--contamination-class",
              type=click.Choice(["public-pre-cutoff", "post-cutoff", "private-synthetic", "unknown"]),
              default="public-pre-cutoff", show_default=True)
@click.option("--confidence-tier", type=click.Choice(["A", "B", "C"]), default="C", show_default=True)
@click.option("--log-dir", required=True, type=click.Path(), help="Inspect log dir (re-run = resume).")
@click.option("--output-dir", default=None, type=click.Path())
@click.pass_context
def cmd_utility_run(ctx, queries, corpus_dir, corpus_root, mcp_config, model, conditions, seeds, concurrency,
                    max_queries, max_budget, timeout_s, max_turns, calibration, agent_env_specs,
                    dataset, corpus_signature,
                    corpus_certification,
                    search_config_key, contamination_class, confidence_tier,
                    log_dir, output_dir):
    """Run the agent-utility matrix THROUGH Inspect AI (resumable) and compose (tempdoc 624).

    Requires the `agent` extra: `pip install jseval[agent]`. condition is a sample
    field, seed=epoch, and cohort=task-args. `eval_set` makes re-runs resume
    (skip completed cells). Then reads the EvalLogs into `utility-comparison.v1`.
    """
    import datetime as _dt
    import os

    from .. import agent_utility_inspect as aui
    from .. import agent_utility_run as aur

    conds = tuple(c.strip().upper() for c in conditions.split(",") if c.strip())
    cli_version = aur.claude_cli_version()
    # --agent-env KEY=VALUE (repeatable, tempdoc 725 increment 4): threaded into every
    # cell's child Agent SDK session env. Empty by default = no overlay (today's
    # behavior byte-for-byte). Fails loudly on a malformed spec, mirroring --run
    # COND=PATH's own `click.BadParameter` idiom above.
    agent_env: dict[str, str] = {}
    for spec in agent_env_specs:
        if "=" not in spec:
            raise click.BadParameter(f"--agent-env must be KEY=VALUE, got {spec!r}")
        key, value = spec.split("=", 1)
        key = key.strip()
        if not key:
            raise click.BadParameter(f"--agent-env key must be non-empty, got {spec!r}")
        agent_env[key] = value
    # Calibration (from `utility-calibrate`) overrides timeout/concurrency/search-key
    # and filters the queries to the closed-book-retained (retrieval-relevant) set.
    calib_readiness = None  # threaded into the run's comparability verdict (readiness ∧ error_rate)
    # Per-arm timeout calibration (tempdoc 624 §Harness lessons): present only in newer
    # calibration files; None → the run uses the scalar `timeout_s` for every condition
    # (old calibration files keep working byte-for-byte).
    timeout_s_by_condition = None
    if calibration:
        from jsharness.types import ReadinessResult
        calib = json.loads(Path(calibration).read_text(encoding="utf-8"))
        # Provenance binding (tempdoc 758 §A/§B) — fail closed BEFORE spending if the banked
        # calibration was pinned at a different git checkout, or the `claude` CLI drifted, since
        # it was written. Both name the offending pair + the recalibrate remedy in the message.
        from .. import manifest as _mf
        from ..utility_calibrate import (
            assert_calibration_cli_version,
            assert_calibration_git_sha,
        )
        assert_calibration_git_sha(calib, current_git_sha=_mf._git_sha_full())
        assert_calibration_cli_version(calib, current_cli_version=cli_version)
        timeout_s = calib.get("timeout_s", timeout_s)
        timeout_s_by_condition = calib.get("timeout_s_by_condition")
        concurrency = calib.get("concurrency", concurrency)
        search_config_key = calib.get("config_cohort_key", search_config_key)
        if not calib.get("readiness_passed", True):
            click.echo(f"WARNING: backend readiness FAILED at calibration: {calib.get('readiness_reasons')}")
            calib_readiness = ReadinessResult(
                passed=False, failure_reasons=calib.get("readiness_reasons", []))
        idx = calib.get("retained_query_indices")
        if idx is not None:
            rows = json.loads(Path(queries).read_text(encoding="utf-8"))
            kept = [rows[i] for i in idx if i < len(rows)]
            # Rewrite ONLY when the closed-book filter actually dropped queries
            # (tempdoc 624 confirmatory launch, 2026-07-17): an unconditional
            # rewrite changes the queries file's BYTES even at zero drops, so a
            # root-mode certified run fails its query_gold_sha256 check against
            # the original committed queries ("query-and-gold digest disagrees").
            # Zero drops -> keep the original path (bytes match the certification;
            # path is equally stable for eval_set resume identity). When queries
            # ARE dropped, the rewrite proceeds -- and a certified run failing its
            # digest check in that case is CORRECT fail-closed behavior: the
            # certified query matrix no longer holds.
            if len(kept) == len(rows):
                click.echo(
                    f"calibration: timeout={timeout_s}s concurrency={concurrency} "
                    f"queries={len(kept)} (dropped 0 contaminated; original queries file kept)")
                idx = None
        if idx is not None:
            # STABLE path (next to the calibration) so queries_path — a task-identity
            # arg — is constant across re-runs and eval_set can resume (D2).
            stable_q = Path(calibration).parent / "_calibrated_queries.json"
            stable_q.write_text(json.dumps(kept), encoding="utf-8")
            queries = str(stable_q)
            per_arm = (f" per-arm={timeout_s_by_condition}" if timeout_s_by_condition else "")
            click.echo(f"calibration: timeout={timeout_s}s{per_arm} concurrency={concurrency} "
                       f"queries={len(kept)} (dropped {calib.get('n_dropped_contaminated', 0)} contaminated)")
    # corpus-dir + mcp-config must be ABSOLUTE (the solver runs claude from a temp cwd).
    aui.run_utility_eval(
        queries_path=queries, corpus_dir=os.path.abspath(corpus_dir),
        mcp_config=(os.path.abspath(mcp_config) if mcp_config else None),
        model=model, conditions=conds, seeds=seeds, concurrency=concurrency,
        log_dir=log_dir, max_queries=max_queries, max_budget=max_budget, timeout_s=timeout_s,
        max_turns=max_turns,
        cli_version=cli_version,
        corpus_dataset=dataset, corpus_signature=corpus_signature or dataset,
        corpus_root=(os.path.abspath(corpus_root) if corpus_root else None),
        search_config_cohort_key=search_config_key,
        corpus_certification=corpus_certification,
        agent_env=agent_env or None,
        timeout_s_by_condition=timeout_s_by_condition,
    )
    from ..utility_recompose import finalize_logs
    record = finalize_logs(
        [log_dir],
        composed_at=_dt.datetime.now(_dt.timezone.utc).isoformat(),
        contamination_class=contamination_class,
        confidence_tier=confidence_tier,
        readiness=calib_readiness,
    )
    comparability = record["comparability"]
    if ctx.obj.get("json"):
        click.echo(json.dumps(record, indent=2, default=str))
    else:
        for slug, by_model in record["measured"].items():
            for m, cell in by_model.items():
                acc = cell["accuracy"]
                tok = cell["provider_cache_creation_input_tokens"]
                click.echo(f"[{slug}/{m}] acc {acc['baseline']}->{acc['with_tool']} "
                           f"(d={acc['delta']:+}, McNemar p={acc['mcnemar_p']}); "
                           f"provider cache-creation tokens median {tok['baseline']['median']}->"
                           f"{tok['with_tool']['median']} (d_mean={tok['delta_mean']:+})")
        click.echo(f"COMPARABLE={comparability['comparable']}" + (
            "" if comparability["comparable"]
            else " — reasons: " + "; ".join(comparability["reasons"])))
        click.echo(f"seeds={record['seed_count']} tier={record['confidence_tier']} "
                   f"contamination={record['coverage']['contamination_class']}")
        # tempdoc 755 Track 1 item 3: surface the per-run MCP-surface verification rate live so
        # a campaign sees the residual `get_mcp_status()` miss rate (and how it was recovered)
        # without waiting for a downstream recompose.
        assertions = record.get("tool_call_assertions") or {}
        for cond in ("B", "C"):
            tca = assertions.get(cond)
            if not tca:
                continue
            unverified = tca.get("cells_mcp_surface_unverified", 0)
            by_kind = tca.get("cells_by_surface_evidence")
            extra = f" evidence={by_kind}" if by_kind else ""
            click.echo(f"mcp-surface[{cond}]: {unverified} unverified cell(s){extra}")
    if output_dir:
        _write_bench_output(record, output_dir, "utility-comparison.v1.json")
        click.echo(f"(Inspect logs in {log_dir})")


# --- Query Understanding spike (363) ---


@click.command("utility-calibrate")
@click.option("--queries", required=True, type=click.Path(exists=True))
@click.option("--corpus-dir", required=True, type=click.Path(exists=True))
@click.option("--mcp-config", default=None, type=click.Path(exists=True), help="JustSearch MCP config (for the C pilot).")
@click.option("--base-url", required=True, help="Live backend, e.g. http://127.0.0.1:59423 (readiness + config_cohort_key).")
@click.option("--model", default="haiku", show_default=True)
@click.option("--concurrency", default=8, show_default=True, type=int, help="The TARGET concurrency (pilot runs at it — B1).")
@click.option("--seeds", default=3, show_default=True, type=int, help="For the cost/time estimate.")
@click.option("--conditions", default="A,C", show_default=True)
@click.option("--require-dense/--no-require-dense", default=True, show_default=True, help="Gate on dense+sparse readiness.")
@click.option("--pilot-n", default=5, show_default=True, type=int)
@click.option("--no-closed-book", is_flag=True, help="Skip the closed-book contamination filter.")
@click.option("--output", required=True, type=click.Path(), help="calibration.json (feed to `utility-run --calibration`).")
@click.pass_context
def cmd_utility_calibrate(ctx, queries, corpus_dir, mcp_config, base_url, model, concurrency,
                          seeds, conditions, require_dense, pilot_n, no_closed_book, output):
    """Pre-run calibration: readiness gate + config_cohort_key pin + target-concurrency pilot
    + closed-book filter (tempdoc 624 §Run-governance). Needs `jseval[agent]` + a live backend."""
    import os

    from .. import utility_calibrate as ucal

    rows = json.loads(Path(queries).read_text(encoding="utf-8"))
    calib = ucal.calibrate(
        base_url=base_url, queries=rows, corpus_dir=os.path.abspath(corpus_dir),
        mcp_config=(os.path.abspath(mcp_config) if mcp_config else None),
        model=model, concurrency=concurrency, seeds=seeds,
        conditions=tuple(c.strip().upper() for c in conditions.split(",") if c.strip()),
        require_dense=require_dense, pilot_n=pilot_n, do_closed_book=not no_closed_book,
    )
    Path(output).write_text(json.dumps(calib, indent=2), encoding="utf-8")
    if not calib["readiness_passed"]:
        click.echo(f"READINESS FAILED: {calib['readiness_reasons']} (record/refuse before a full run)")
    click.echo(f"timeout={calib['timeout_s']}s concurrency={calib['concurrency']} "
               f"retained={len(calib['retained_query_indices'])} dropped={calib['n_dropped_contaminated']} "
               f"est=${calib['cost_estimate_usd']} / {calib['time_estimate_min']}min")
    click.echo(f"config_cohort_key={calib['config_cohort_key']}")
    click.echo(f"Written calibration to {output}")
    if not calib["readiness_passed"]:
        # Non-zero exit — a failed calibration must not be mistaken for a passing one by
        # a caller/script that doesn't itself inspect `readiness_passed` in the output
        # file (matches the `sys.exit(1)` convention used across jseval's other commands
        # for a failed readiness/capability gate, e.g. `_common.assert_run_capabilities`).
        sys.exit(1)


@click.command("utility-status")
@click.argument("log_dir", type=click.Path(exists=True))
@click.pass_context
def cmd_utility_status(ctx, log_dir):
    """Live-status projection over PARTIAL Inspect logs (tempdoc 624 §Run-governance):
    completion %, per-arm exclusion (exposes the timeouts Inspect swallows), emerging delta."""
    from ..utility_recompose import partial_status_projection

    status = partial_status_projection(log_dir)
    for condition, loss in sorted(status["per_arm_loss"].items()):
        click.echo(f"  {condition}: completed={loss['n_completed']} excluded={loss['n_excluded']} "
                   f"({loss['exclusion_rate']:.0%} of {loss['n_attempted']} attempted) "
                   f"pending~{loss['n_pending']} of {loss['n_planned']} planned")
    comparability = status["comparability"]
    click.echo(
        f"  comparable(so far)={comparability['comparable']}  {comparability['metrics']}")
    if not comparability["comparable"]:
        for r in comparability["reasons"]:
            click.echo(f"    - {r}")
    try:
        if status["measured"]:
            for slug, by_model in status["measured"].items():
                for mdl, cell in by_model.items():
                    acc, tok = cell["accuracy"], cell["tokens_unique"]
                    click.echo(f"  [{slug}/{mdl}] acc {acc['baseline']}->{acc['with_tool']} "
                               f"(d={acc['delta']:+}); tokens median "
                               f"{tok['baseline']['median']}->{tok['with_tool']['median']}")
    except Exception as e:
        click.echo(f"  (no paired cells yet: {type(e).__name__})")


@click.command("utility-judge")
@click.argument("log_dir", type=click.Path(exists=True))
@click.option("--judge-url", default="http://127.0.0.1:33221", show_default=True,
              help="The JustSearch Head API's OWN base URL (its OpenAI-compat proxy — "
                   "OpenAiCompatController.java forwards /v1/chat/completions and /v1/models "
                   "to whatever port llama-server actually bound), NOT llama-server's raw "
                   "ephemeral port. Default matches jseval's own eval-backend port "
                   "(_DEFAULT_BASE_URL_EVAL in jseval/commands/_common.py) — override with the "
                   "live backend's base URL if it was started on a different port. A different "
                   "model family than the claude agent under test is the self-preference "
                   "control (C-6).")
@click.option("--judge-model", default=None, help="Override the served model id (else auto-probed).")
@click.option("--contamination-class",
              type=click.Choice(["public-pre-cutoff", "post-cutoff", "private-synthetic", "unknown"]),
              default="public-pre-cutoff", show_default=True)
@click.option("--confidence-tier", type=click.Choice(["A", "B", "C"]), default="C", show_default=True)
@click.option("--output-dir", default=None, type=click.Path(), help="Re-compose the JUDGED record here.")
@click.option("--calibrate", is_flag=True,
              help="Also run the human-calibration DRY RUN (tempdoc 624 §M.4/§T.3) and attach it "
                   "to the overlay as `human_calibration`. Uses two deterministic AGENT-SUBSTITUTE "
                   "raters, NOT real human raters — proves the sampling + Cohen's-kappa machinery "
                   "end-to-end, not a validated judge-accuracy figure.")
@click.option("--calibration-n", default=40, show_default=True, type=int,
              help="Calibration sample size (only with --calibrate).")
@click.option("--calibration-seed", default=0, show_default=True, type=int,
              help="Calibration sample RNG seed (only with --calibrate).")
@click.option("--exclude-leaked", is_flag=True,
              help="Scan the Inspect log dir for an answer-key-leak signature "
                   "(a queries.json/queries.jsonl mention in each sample's completion "
                   "text) and exclude matched cells from the paired statistics before "
                   "composing (tempdoc 624 §As-built #7 follow-up leak-free reanalysis).")
@click.option("--supersedes", default=None, type=click.Path(),
              help="Relative path to the prior record's JSON file this run corrects. "
                   "Must be given together with --revision-reason (tempdoc 624 Design 1). "
                   "When both are given, the re-composed record gets a `revision` block; "
                   "when neither is given, the record is unchanged (no `revision` field). "
                   "Only takes effect with --output-dir (no re-composed record otherwise).")
@click.option("--revision-reason", default=None,
              type=click.Choice(sorted(["leak_correction", "judge_rescore", "reseed", "other"])),
              help="Why this record supersedes --supersedes. Must be given together with "
                   "--supersedes. The revision's `changed_fields` is left empty at the CLI "
                   "level -- the caller isn't expected to know exactly which fields changed "
                   "when composing from the command line; that's a deliberate, honest default.")
@click.pass_context
def cmd_utility_judge(ctx, log_dir, judge_url, judge_model,
                      contamination_class, confidence_tier, output_dir,
                      calibrate, calibration_n, calibration_seed, exclude_leaked,
                      supersedes, revision_reason):
    """Hybrid EM->LLM-judge re-score over EvalLogs, post-hoc (tempdoc 624 C-6/E-5).

    EM auto-passes; the EM-misses are judged by the local model (different family
    than the agent), dual-order, abstaining on disagreement. Writes a judge-overlay
    + (optionally) re-composes the JUDGED `utility-comparison.v1`, and (with
    --calibrate) attaches the human-calibration dry run. Requires the `agent`
    extra + a running judge model (`ai_activate`)."""
    from .. import agent_utility_run as aur
    from .. import utility_judge as uj

    _attach_revision(None, supersedes, revision_reason)  # fail fast before judging

    overlay = uj.judge_logs(log_dir, judge_url=judge_url, judge_model=judge_model)
    if calibrate:
        uj.attach_human_calibration(overlay, log_dir, n=calibration_n, seed=calibration_seed)
    path = uj.write_overlay(log_dir, overlay)
    st = overlay["stats"]
    click.echo(f"judge: EM-pass={st['em_auto_pass']} judged-misses={st['judged_misses']} "
               f"flips={st['judge_flips']} disagreements={st['judge_disagreements']} "
               f"agreement={st['agreement_rate']} kind={overlay['judge_identity']['kind']}")
    if st["degraded_to_em"]:
        click.echo("WARNING: judge endpoint unreachable — overlay is EM-only (no LLM verdicts).")
    elif st["call_failures"] > 0:
        click.echo(f"NOTE: {st['call_failures']} of {st['judged_misses'] + st['call_failures']} "
                   f"judge calls failed and fell back to EM for those cases.")
    if calibrate:
        hc = overlay["human_calibration"]
        jvr, rvr = hc["judge_vs_rater_agreement"], hc["rater_vs_rater_agreement"]
        click.echo(f"calibration (rater_kind={hc['rater_kind']!r}, n={hc['n']}): "
                   f"judge-vs-rater kappa={jvr['value']} degenerate_pe={jvr['degenerate_pe']}; "
                   f"rater-vs-rater kappa={rvr['value']} degenerate_pe={rvr['degenerate_pe']}")
    click.echo(f"Written overlay to {path}")

    if output_dir:
        from ..utility_recompose import finalize_logs, semantic_digest, write_record

        leaked_sets = None
        if exclude_leaked:
            leaked = aur.scan_leaked_cells(log_dir)
            leaked_sets = [leaked]
            n_flagged = len(leaked)
            click.echo(f"leak-scan: {len(leaked)} leaked cell(s), "
                       f"{n_flagged} unique cells flagged (excluded from paired stats)")
        record = finalize_logs(
            [log_dir],
            judge_overlays=[path],
            contamination_class=contamination_class,
            confidence_tier=confidence_tier,
            leaked_cells_by_log=leaked_sets,
        )
        _attach_revision(record, supersedes, revision_reason)
        record["semantic_digest"] = semantic_digest(record)
        write_record(record, output_dir)
        for slug, by_model in record["measured"].items():
            for m, cell in by_model.items():
                acc = cell["accuracy"]
                click.echo(f"  JUDGED [{slug}/{m}] acc {acc['baseline']}->{acc['with_tool']} "
                           f"(d={acc['delta']:+}, McNemar p={acc['mcnemar_p']})")


def _echo_local_serial_progress(event: str, detail: dict) -> None:
    """The CLI-layer half of `LocalSerialRater`'s progress hook (tempdoc 674
    remaining-work slice) -- `utility_judge.py` imports no `click` (the
    established `commands/*.py`-thin-wrapper boundary, tempdoc 645), so
    translating its `on_progress` events into visible CLI text is this file's
    job, not the rater's."""
    if event == "swap_start":
        click.echo(f"  [swap] loading {detail.get('model_path')}...")
    elif event == "swap_complete":
        click.echo(f"  [swap] ready ({detail.get('elapsed_sec')}s)")
    elif event == "item_labeled":
        click.echo(f"  [grade] {detail.get('index')}/{detail.get('total')}")
    elif event == "restore_start":
        click.echo(f"  [restore] reverting to {detail.get('model_path')}...")
    elif event == "restore_complete":
        click.echo("  [restore] done")
    elif event == "restore_skipped":
        click.echo(f"  [restore] skipped ({detail.get('reason')})")


@click.command("utility-judge-cross-family")
@click.argument("log_dir", type=click.Path(exists=True))
@click.option("--graders-config", required=True, type=click.Path(exists=True),
              help="JSON file: a list of grader configs, each an object with "
                   '"name" and "kind" ("endpoint" [default] or "local-serial", '
                   'tempdoc 674). "endpoint" configs also need "endpoint_url", '
                   '"model", and optionally "headers" (auth), "price_per_call_usd" '
                   '(default 0.0), "timeout_sec", "system_prompt", "max_tokens", '
                   '"temperature". "local-serial" configs instead need "model_path" '
                   "(a local GGUF) and optionally \"backend_base_url\" (default the "
                   "eval backend, http://127.0.0.1:33221), \"timeout_sec\", and "
                   "\"keep_loaded_between_raters\" (default false — restore the original "
                   "model after this grader's turn; set true to skip that restore as a "
                   "speed tradeoff, e.g. for all but the last local grader in a panel) "
                   "— these swap the locally-served chat model in place (GPU-serial, "
                   "$0 cost) rather than calling a remote endpoint. Must define >= 2 "
                   "configs from DIFFERENT provider families than BOTH the agent-under-test "
                   "(Claude Haiku) and the local judge (Qwen) — e.g. a GPT-class and "
                   "a Gemini-class grader, or two local GGUFs of different lineages "
                   "(tempdoc 624 §M.9 \"U-Founder-4 revised\"). No real endpoint/"
                   "model is ever hardcoded — this file is entirely caller-supplied.")
@click.option("--calibration-n", default=40, show_default=True, type=int,
              help="Calibration sample size (same stratified sampler as --calibrate).")
@click.option("--calibration-seed", default=0, show_default=True, type=int,
              help="Calibration sample RNG seed.")
@click.option("--max-calls", default=None, type=int,
              help="Hard cap on total external grader HTTP calls this run may make. "
                   "Defaults to exactly the printed cost estimate's call_count (no "
                   "slack) if omitted.")
@click.option("--yes", is_flag=True,
              help="Actually make the real network calls. WITHOUT --yes this command "
                   "only loads the graders, prints the cost estimate, and exits — it "
                   "NEVER calls out to a real endpoint by default.")
@click.pass_context
def cmd_utility_judge_cross_family(ctx, log_dir, graders_config, calibration_n,
                                   calibration_seed, max_calls, yes):
    """Cross-family LLM grader panel calibration (tempdoc 624 §M.9 "U-Founder-4 revised").

    Replaces the (unstaffed) human-calibration session with a stratified sample
    independently graded by >= 2 frontier models from provider families different
    from BOTH the agent-under-test and the local judge, reporting their MUTUAL
    cross-family agreement (kappa + CI) — weaker than human calibration on exactly
    the hard/ambiguous cases, honestly labelled as such via the unconditional
    `rater_kind: "cross-family-llm, NOT human"` stamp.

    Reads LOG_DIR's existing `judge-overlay.json` (produced by `utility-judge`),
    prints a cost estimate computed from --graders-config's `price_per_call_usd`
    fields, and — ONLY with --yes — runs the real calibration and attaches
    `cross_family_calibration` to the overlay. Without --yes, no network call is
    ever made; the command is safe to run repeatedly to check pricing/sample size.
    """
    from .. import external_grader as eg
    from .. import utility_judge as uj

    overlay_path = Path(log_dir) / "judge-overlay.json"
    if not overlay_path.exists():
        raise click.ClickException(
            f"{overlay_path} not found — run `jseval utility-judge {log_dir}` first "
            "to produce a judge-overlay.json.")
    overlay = json.loads(overlay_path.read_text(encoding="utf-8"))

    raw_configs = json.loads(Path(graders_config).read_text(encoding="utf-8"))
    if not isinstance(raw_configs, list) or len(raw_configs) < 2:
        raise click.ClickException(
            "--graders-config must be a JSON list of >= 2 grader config objects.")
    price_table = {c["name"]: c.get("price_per_call_usd", 0.0) for c in raw_configs}

    graders = []
    local_grader_paths = []  # [(name, model_path), ...] -- feeds the local-serial preflight report
    for c in raw_configs:
        kind = c.get("kind", "endpoint")
        if kind == "local-serial":
            if "model_path" not in c:
                raise click.ClickException(
                    f"--graders-config: grader {c.get('name')!r} has kind='local-serial' but is "
                    "missing required field 'model_path'.")
            if c.get("price_per_call_usd", 0.0) != 0.0:
                raise click.ClickException(
                    f"--graders-config: grader {c.get('name')!r} has kind='local-serial' (a $0, "
                    f"GPU-serial local swap) but a nonzero price_per_call_usd="
                    f"{c['price_per_call_usd']!r} — a local grader can never actually incur this "
                    "cost; fix the config (omit the field or set it to 0) rather than report a "
                    "misleading estimate.")
            local_grader_paths.append((c["name"], c["model_path"]))
            graders.append(uj.LocalSerialRater(
                name=c["name"], model_path=c["model_path"],
                backend_base_url=c.get("backend_base_url", "http://127.0.0.1:33221"),
                timeout_sec=c.get("timeout_sec", 60.0),
                keep_loaded_between_raters=c.get("keep_loaded_between_raters", False),
                on_progress=_echo_local_serial_progress))
        elif kind == "endpoint":
            fields = {k: v for k, v in c.items() if k not in ("price_per_call_usd", "kind")}
            graders.append(eg.GraderConfig(**fields))
        else:
            raise click.ClickException(
                f"--graders-config: unknown kind {kind!r} for grader {c.get('name')!r} "
                "(must be 'endpoint' or 'local-serial').")

    estimate = eg.estimate_cross_family_cost(calibration_n, graders, price_table)
    click.echo(
        f"cross-family calibration cost estimate: {estimate['call_count']} calls, "
        f"${estimate['cost_estimate_usd']:.4f} (n={estimate['n_samples']}, "
        f"graders={estimate['n_graders']}, dual_order={estimate['dual_order']})")
    for name, cost in estimate["per_grader"].items():
        click.echo(f"  {name}: ${cost:.4f}")

    if local_grader_paths:
        preflight = uj.estimate_local_serial_preflight(local_grader_paths)
        click.echo(
            f"local-serial preflight: {preflight['n_local_graders']} local grader(s), "
            f"~{preflight['estimated_swap_count']} swaps, "
            f"~{preflight['estimated_time_sec']}s estimated ({preflight['note']})")
        for entry in preflight["per_grader"]:
            size_gb = round(entry["size_bytes"] / (1024 ** 3), 1) if entry["size_bytes"] else "?"
            click.echo(
                f"  {entry['name']}: {entry['architecture'] or 'unknown'} "
                f"{entry['size_label'] or 'unknown size'} ({size_gb} GB on disk), "
                f"vram_fit={entry['vram_fit']}")

    if not yes:
        click.echo(
            "Dry run only (no --yes given) — NO network call was made. Re-run with "
            "--yes once the cost estimate above is acceptable.")
        return

    cap = max_calls if max_calls is not None else estimate["call_count"]
    result = uj.run_cross_family_calibration(
        log_dir, overlay, graders=graders, n=calibration_n, seed=calibration_seed,
        max_calls=cap)
    overlay["cross_family_calibration"] = result
    path = uj.write_overlay(log_dir, overlay)
    jvr, rvr = result["judge_vs_rater_agreement"], result["rater_vs_rater_agreement"]
    click.echo(
        f"cross-family calibration (rater_kind={result['rater_kind']!r}, "
        f"n={result['n']}, n_abstained={result['n_abstained']}): "
        f"judge-vs-rater kappa={jvr['value']} degenerate_pe={jvr['degenerate_pe']}; "
        f"rater-vs-rater kappa={rvr['value']} degenerate_pe={rvr['degenerate_pe']}")
    click.echo(f"Written overlay to {path}")


@click.command("utility-judge-local-swap-smoketest")
@click.option("--model-path", required=True, type=click.Path(exists=True),
              help="Local GGUF to swap the dev-stack's served chat model to, grade one hardcoded "
                   "synthetic question/reference/candidate triple with, then restore the original "
                   "model. Exercises exactly LocalSerialRater's swap/assert/label/restore cycle "
                   "(tempdoc 674) with no judge-overlay.json and no calibration sample needed -- a "
                   "standalone mechanism check, not a calibration run. Requires the dev-stack running "
                   "with an active AI runtime variant (see `ai_activate`).")
@click.option("--backend-base-url", default="http://127.0.0.1:33221", show_default=True,
              help="The JustSearch Head API's own base URL (same default used elsewhere in this file).")
@click.option("--timeout-sec", default=60.0, show_default=True, type=float,
              help="Per-HTTP-call timeout for the grading request.")
@click.pass_context
def cmd_utility_judge_local_swap_smoketest(ctx, model_path, backend_base_url, timeout_sec):
    """Standalone swap-mechanism smoke test for a local-serial grader (tempdoc 674).

    Proves the model-swap mechanism (settings + activate + served-model assertion +
    restore) actually works against a real running dev-stack without needing a full
    calibration run staged first -- the gap this tempdoc's own live-validation step
    used to be blocked on. Exit code is nonzero on any failure, including a failed
    or unconfirmed swap.
    """
    from .. import utility_judge as uj

    rater = uj.LocalSerialRater(
        name="smoketest", model_path=model_path, backend_base_url=backend_base_url,
        timeout_sec=timeout_sec, on_progress=_echo_local_serial_progress)
    texts = {
        "smoketest-item": {
            "question": "What color is the sky on a clear day?",
            "reference": "blue",
            "candidate": "The sky is blue.",
        },
    }
    started_at = time.monotonic()
    try:
        result = rater.label_sample(texts)
    except Exception as e:
        elapsed = time.monotonic() - started_at
        raise click.ClickException(
            f"swap smoke test FAILED after {elapsed:.1f}s ({type(e).__name__}): {e}")
    elapsed = time.monotonic() - started_at
    verdict = result.get("smoketest-item")
    if verdict is None:
        click.echo(f"SMOKETEST INCONCLUSIVE in {elapsed:.1f}s -- the model's dual-order calls "
                   "disagreed on the synthetic item (mechanism worked; verdict abstained).")
    else:
        click.echo(f"SMOKETEST PASSED in {elapsed:.1f}s -- swap, grade, and restore all succeeded "
                   f"(verdict={verdict}, expected True for this synthetic item).")


@click.command("utility-compose-cross-corpus")
@click.option("--log-dir", "log_dirs", multiple=True, required=True, type=click.Path(exists=True),
              help="Repeatable. One completed `jseval utility-run --log-dir` Inspect log dir per "
                   "corpus (e.g. one per language/battlefield-dimension). Need 2+.")
@click.option("--contamination-class",
              type=click.Choice(["public-pre-cutoff", "post-cutoff", "private-synthetic", "unknown"]),
              default="public-pre-cutoff", show_default=True)
@click.option("--confidence-tier", type=click.Choice(["A", "B", "C"]), default="C", show_default=True)
@click.option("--exclude-leaked", is_flag=True,
              help="Scan each --log-dir for an answer-key-leak signature (a "
                   "queries.json/queries.jsonl mention in each sample's completion text) "
                   "and exclude matched cells from the paired statistics before pooling "
                   "(tempdoc 624 §As-built #7 follow-up leak-free reanalysis).")
@click.option("--supersedes", default=None, type=click.Path(),
              help="Relative path to the prior record's JSON file this run corrects. "
                   "Must be given together with --revision-reason (tempdoc 624 Design 1). "
                   "When both are given, the pooled record gets a `revision` block; "
                   "when neither is given, the record is unchanged (no `revision` field).")
@click.option("--revision-reason", default=None,
              type=click.Choice(sorted(["leak_correction", "judge_rescore", "reseed", "other"])),
              help="Why this record supersedes --supersedes. Must be given together with "
                   "--supersedes. The revision's `changed_fields` is left empty at the CLI "
                   "level -- the caller isn't expected to know exactly which fields changed "
                   "when composing from the command line; that's a deliberate, honest default.")
@click.option("--output-dir", type=click.Path(), default=None)
@click.pass_context
def cmd_utility_compose_cross_corpus(ctx, log_dirs, contamination_class,
                                     confidence_tier, exclude_leaked, supersedes, revision_reason,
                                     output_dir):
    """Pool multiple corpora's Inspect logs into ONE cross-corpus stratified record (tempdoc 624).

    `utility-compose`/`utility-run` always produce one SEPARATE top-level record per
    corpus (dataset slug) — so a real cross-corpus split (e.g. English/German/scan)
    can only ever be read off as three individually-pooled records, never as one
    stratified breakdown. This command reuses the exact same EvalLog -> summary
    projection (`eval_logs_to_summaries`) as `utility-run`, one call per --log-dir,
    then pools every corpus's summaries into `compose_utility_cross_corpus`, which
    stratifies the pooled McNemar+bootstrap-CI comparison by corpus — reusing
    `_arm_comparison`'s existing `stratify_by` machinery, not a new statistics path.
    """
    from .. import agent_utility_run as aur
    from ..utility_recompose import finalize_logs, semantic_digest, write_record

    _attach_revision(None, supersedes, revision_reason)  # fail fast before pooling logs

    n_flagged = 0
    leaked_sets = []
    for ld in log_dirs:
        if exclude_leaked:
            leaked = aur.scan_leaked_cells(ld)
            n_flagged += len(leaked)
            leaked_sets.append(leaked)

    if exclude_leaked:
        click.echo(f"leak-scan: {n_flagged} per-query entries flagged across "
                   f"{len(log_dirs)} log dir(s) (excluded from paired stats)")

    record = finalize_logs(
        log_dirs,
        contamination_class=contamination_class,
        confidence_tier=confidence_tier,
        leaked_cells_by_log=(leaked_sets if exclude_leaked else None),
    )
    _attach_revision(record, supersedes, revision_reason)
    record["semantic_digest"] = semantic_digest(record)

    if ctx.obj.get("json"):
        click.echo(json.dumps(record, indent=2, default=str))
    else:
        click.echo(f"corpora={record['corpora']}")
        for m, cell in record["measured"].items():
            acc = cell["accuracy"]
            tok = cell["provider_cache_creation_input_tokens"]
            click.echo(
                f"[pooled/{m}] acc {acc['baseline']}->{acc['with_tool']} "
                f"(d={acc['delta']:+}, McNemar p={acc['mcnemar_p']}); n={cell['n_paired_observations']}; "
                f"provider cache-creation tokens median {tok['baseline']['median']}->{tok['with_tool']['median']} "
                f"(d_mean={tok['delta_mean']:+})")
            for slabel, strat in (cell.get("stratified") or {}).get("by_stratum", {}).items():
                sacc = strat["accuracy"]
                click.echo(
                    f"    stratum[{slabel}] acc {sacc['baseline']}->{sacc['with_tool']} "
                    f"(d={sacc['delta']:+}, McNemar p={sacc['mcnemar_p']}, n={strat['n_paired_observations']})")
        governance = record["comparability"]
        click.echo(f"COMPARABLE={governance['comparable']}" + ("" if governance["comparable"]
                   else " — reasons: " + "; ".join(governance["reasons"])))
        click.echo(f"seeds={record['seed_count']} tier={record['confidence_tier']} "
                   f"contamination={record['coverage']['contamination_class']}")

    if output_dir:
        write_record(record, output_dir)


@click.command("utility-payload-decompose")
@click.option("--log-dir", "log_dirs", multiple=True, required=True,
              type=click.Path(exists=True, file_okay=False),
              help="Repeatable completed Inspect log directory.")
@click.option("--payload-dir", default=None, type=click.Path(exists=True, file_okay=False),
              help="Optional directory of RAW tool-result payload files (one payload "
                   "per file, UTF-8). Each is SHA256-verified against the log's own "
                   "digest index before use; unmatched files are counted and discarded.")
@click.option("--out", default=None, type=click.Path(dir_okay=False),
              help="Write the full aggregate as JSON.")
@click.pass_context
def cmd_utility_payload_decompose(ctx, log_dirs, payload_dir, out):
    """Decompose MCP tool-result payload bytes by component (tempdoc 770 §D).

    Reports, per component (per-hit `trace`/`legScores`/`excerpts`/`id`/`path`,
    `results[]`, and each top-level key), the median and aggregate share of the
    delivered payload, plus the `id == path` duplicate-hit count. Every statistic
    reports its own N. No dev stack, no model, no spend.

    HONESTY BOUNDARY. Campaign Inspect logs store digests only (`content_sha256` /
    `content_len`) -- raw content is deliberately never persisted -- so payloads
    are decomposable only when either (a) the log was written after the
    `component_bytes` capture landed, or (b) the raw payloads are supplied with
    `--payload-dir`. Structured deliveries covered by neither are reported as
    `decomposition_unavailable` with their count; they are never estimated.

    The tempdoc 770 §D measurement used route (b): the 1,078 v5 payloads were
    recovered from Claude Code CLI session transcripts
    (`~/.claude/projects/<project-slug>/*.jsonl`, which do persist tool_result
    content), written one-per-file into a scratch directory, and passed here --
    each one SHA256-verified against the campaign digest index before it counted.
    That transcript path is machine-specific and not reproducible from this repo
    alone, so route (b) is bring-your-own-payloads by construction.
    """
    from ..agent_utility_inspect import decompose_payload_shares

    reports = []
    for log_dir in log_dirs:
        report = decompose_payload_shares(log_dir, payload_dir)
        report["log_dir"] = str(log_dir)
        reports.append(report)
        if ctx.obj.get("json"):
            click.echo(json.dumps(report, indent=2))
            continue
        click.echo(f"[{log_dir}]")
        click.echo(f"  digests={report['tool_result_digests']} "
                   f"structured={report['structured_deliveries']} "
                   f"decomposed={report['decomposed']} "
                   f"(log={report['decomposed_from_log_component_bytes']}, "
                   f"verified-payloads={report['decomposed_from_verified_payloads']}) "
                   f"unavailable={report['decomposition_unavailable']}")
        if payload_dir:
            click.echo(f"  payloads: sha256-verified={report['payloads_sha256_verified']} "
                       f"unmatched-discarded={report['payloads_sha256_unmatched']}")
        if not report["decomposed"]:
            click.echo("  no decomposable payload -- component table omitted (not estimated)")
            continue
        click.echo(f"  {'component':<24}{'median share':>14}{'agg share':>12}{'N':>8}")
        for name, stat in sorted(report["components"].items(),
                                 key=lambda kv: -(kv[1]["aggregate_share"] or 0)):
            med = "n/a" if stat["median_share"] is None else f"{stat['median_share']:.1%}"
            agg = "n/a" if stat["aggregate_share"] is None else f"{stat['aggregate_share']:.1%}"
            click.echo(f"  {name:<24}{med:>14}{agg:>12}{stat['n']:>8}")
        hits = report["hits"]
        click.echo(f"  hits/call: median={hits['median']} mean={hits['mean']} "
                   f"max={hits['max']} (N={hits['n']})")
        dup = report["id_equals_path"]
        click.echo(f"  id == path: {dup['hits_id_equals_path']}/{dup['hits_with_id_and_path']} hits "
                   f"(N={dup['n_calls']} calls)")

    if out:
        payload = reports[0] if len(reports) == 1 else reports
        Path(out).write_text(json.dumps(payload, indent=2), encoding="utf-8")
        click.echo(f"Wrote {out}")


COMMANDS = [cmd_utility_publication_build, cmd_utility_publication_select, cmd_utility_replay,
           cmd_utility_evidence_export, cmd_utility_recompose, cmd_utility_policy_dryrun,
           cmd_utility_compose, cmd_utility_run, cmd_utility_calibrate, cmd_utility_status,
           cmd_utility_judge, cmd_utility_judge_cross_family, cmd_utility_judge_local_swap_smoketest,
           cmd_utility_compose_cross_corpus, cmd_utility_payload_decompose]
