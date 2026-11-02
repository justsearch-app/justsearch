"""jseval release commands (split from cli.py — tempdoc 645)."""
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path
import logging

import click

from .._paths import DEFAULT_JSEVAL_DATA_DIR, REPO_ROOT

log = logging.getLogger(__name__)


def _repo_relative(p: Path) -> str:
    """Render ``p`` relative to the repo root, with forward slashes, else absolute.

    Release documents are committed and published, so a recorded run dir must not bake in
    one machine's absolute path (`release.v1.json` already carries a few under
    `cohort.model_identity` — this is the half that does not have to). Falls back to the
    absolute string when the path is outside the repo, since a wrong-but-tidy pointer is
    worse than an honest ugly one.
    """
    try:
        return p.resolve().relative_to(REPO_ROOT).as_posix()
    except ValueError:
        return str(p)


@click.command("release")
@click.option("--run", "runs", multiple=True, type=click.Path(exists=True, resolve_path=True),
              help="Run dir (with summary.json) to include. Repeatable. One or more per corpus. "
                   "All must share one config-cohort key (else compose refuses).")
@click.option("--latest-per-dataset", is_flag=True,
              help="Auto-discover: the most recent run per dataset under --data-dir's eval-results "
                   "(via the manifest index). compose() then validates they are one cohort.")
@click.option("--data-dir", type=click.Path(), default=lambda: str(DEFAULT_JSEVAL_DATA_DIR),
              help="Data dir whose eval-results/ holds runs (for --latest-per-dataset).")
@click.option("--default-mode", default="hybrid", show_default=True,
              help="Measured search mode whose metrics are the per-corpus headline (not the product default, AUTO).")
@click.option("--external-baselines", type=click.Path(exists=True, resolve_path=True), default=None,
              help="Path to external-baselines.v1.json (cited published baselines, side-by-side).")
@click.option("--release-id", default=None, help="Optional human label for this release.")
@click.option("--allow-incomparable", is_flag=True,
              help="Compose even if a default-mode run is not comparable (diagnostics only).")
@click.option("--withhold-default", multiple=True, metavar="DATASET=REASON",
              help="Explicitly withhold a dataset's default-mode claim and floor; retain its source and ablations.")
@click.option("--out", "out_path", type=click.Path(), default=None,
              help="Where to write release.v1.json (default: <data-dir>/release.v1.json).")
@click.pass_context
def cmd_release(ctx, runs, latest_per_dataset, data_dir, default_mode, external_baselines,
                release_id, allow_incomparable, withhold_default, out_path):
    """Compose cohort-identical eval runs into one publishable benchmark release (tempdoc 623).

    A release is a PROJECTION over runs that share one config/commit/hardware — not a new
    authority. The relevance-ratchet floors and the register headline read from it (see
    `jseval relevance-gate` re-rooting and scripts/docs/register-headline-sync.mjs).
    """
    from datetime import datetime, timezone

    from .. import release as _release
    from .. import bisection as _bisection

    # tempdoc 683: a release without a conformant release_id is un-citable (outward docs
    # cite the id; the outward-number lint keys on it) — refuse before any work.
    _id_error = _release.validate_release_id(release_id)
    if _id_error:
        click.echo(f"Error: {_id_error}", err=True)
        sys.exit(2)

    run_dirs: list[Path] = [Path(r) for r in runs]
    if latest_per_dataset:
        idx_root = Path(data_dir) / "eval-results"
        rows = _bisection.load_index(idx_root)
        latest: dict[str, dict] = {}
        for row in rows:
            ds = row.get("dataset")
            if ds is None:
                continue
            prev = latest.get(ds)
            if prev is None or (row.get("timestamp") or "") > (prev.get("timestamp") or ""):
                latest[ds] = row
        # P1 (tempdoc 623): the latest run per dataset can span commits — the shared `main` moves
        # mid-sweep (the V5 split). Group the latest runs by config_cohort_key and pick the cohort
        # covering the MOST datasets, then WARN + EXCLUDE the split, rather than feeding a mixed set
        # to compose() (which would just refuse the whole thing with an opaque error).
        dataset_to_key: dict[str, str] = {}
        dataset_to_rd: dict[str, Path] = {}
        for ds, row in latest.items():
            rd = Path(row["run_dir"])
            sp = rd / "summary.json"
            if not sp.is_file():
                continue
            manifest = json.loads(sp.read_text(encoding="utf-8")).get("manifest") or {}
            dataset_to_key[ds] = _release.config_cohort_key(manifest)
            dataset_to_rd[ds] = rd
        chosen_key, excluded = _release.select_dominant_cohort(dataset_to_key)
        if excluded:
            click.echo(
                f"WARNING: {len(excluded)} dataset(s) are on a different config-cohort "
                f"(likely a mid-sweep commit move) and are EXCLUDED from this release: "
                f"{', '.join(excluded)}. Re-run them at the chosen cohort's commit to include them.",
                err=True,
            )
        run_dirs.extend(rd for ds, rd in dataset_to_rd.items() if dataset_to_key[ds] == chosen_key)

    if not run_dirs:
        click.echo("Error: provide --run (repeatable) or --latest-per-dataset", err=True)
        sys.exit(2)

    withheld = {}
    for entry in withhold_default:
        dataset, sep, reason = entry.partition("=")
        if not sep or not reason.strip() or dataset in withheld:
            raise click.BadParameter("use DATASET=REASON once per dataset", param_hint="--withhold-default")
        withheld[dataset] = reason

    # tempdoc 802: record WHICH artifacts this release projects from, alongside parsing them.
    # `compose()` sees only summaries; the run dirs are known here and nowhere downstream, so a
    # release composed without this step is un-rescoreable the moment the dirs are cleaned up
    # (which is what happened to `715-rebaseline-2026-07-16`). The sha256 is over the exact
    # summary.json bytes, so it still identifies the run after the path stops resolving.
    summaries: list[dict] = []
    run_sources: list[dict] = []
    for rd in run_dirs:
        sp = rd / "summary.json"
        if not sp.is_file():
            click.echo(f"Error: no summary.json in {rd}", err=True)
            sys.exit(2)
        raw = sp.read_bytes()
        summaries.append(json.loads(raw.decode("utf-8")))
        run_sources.append({
            "run_dir": _repo_relative(rd),
            "summary_sha256": hashlib.sha256(raw).hexdigest(),
        })

    # tempdoc 683: source the optional per-corpus leak section from the runs'
    # staged_recall_accounting projections (compose() only sees summaries; the run
    # dirs — and thus projections/ — are only known here). Best-effort: a run
    # without an ok projection simply contributes no leak row.
    # tempdoc 701: same loop also sources the union_recall (leg_union_recall) sibling —
    # the recall-survival floor counterpart of leak's ceiling, read from the same projection.
    leak_by_dataset: dict[str, float] = {}
    union_recall_by_dataset: dict[str, float] = {}
    for rd, s in zip(run_dirs, summaries):
        pp = rd / "projections" / "staged_recall_accounting.json"
        if not pp.is_file():
            continue
        try:
            proj = json.loads(pp.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if proj.get("status") != "ok":
            continue
        aggregate = proj.get("aggregate") or {}
        rate = aggregate.get("leak_rate")
        union_recall = aggregate.get("leg_union_recall")
        ds = _release.canonical_dataset_slug(s.get("dataset"))
        if ds and isinstance(rate, (int, float)):
            leak_by_dataset[ds] = float(rate)
        if ds and isinstance(union_recall, (int, float)):
            union_recall_by_dataset[ds] = float(union_recall)

    ext = None
    if external_baselines:
        ext_doc = json.loads(Path(external_baselines).read_text(encoding="utf-8"))
        ext = ext_doc.get("baselines", ext_doc)

    try:
        release_doc = _release.compose(
            summaries,
            default_mode=default_mode,
            composed_at=datetime.now(timezone.utc).isoformat(),
            release_id=release_id,
            external_baselines=ext,
            require_comparable=not allow_incomparable,
            leak_by_dataset=leak_by_dataset or None,
            union_recall_by_dataset=union_recall_by_dataset or None,
            run_sources=run_sources,
            withheld_default=withheld,
        )
    except _release.ComposeError as e:
        click.echo(f"compose refused: {e}", err=True)
        sys.exit(1)

    out = Path(out_path) if out_path else Path(data_dir) / "release.v1.json"

    # tempdoc 664: recomposing over a prior release silently changes every projected ratchet
    # floor (relevance-gate, perf-gate both read `current_release`) — a comment in
    # perf-ratchet-baselines.v1.json already documents one such relaxation being absorbed this
    # way. Refuse a per-corpus, per-metric relaxation vs. the prior release without a classified,
    # justified changeset.
    from .. import baseline_shift as _bshift
    from .. import metric_families as _mf
    lower_is_better = {k: v for fam in _mf.REGISTRY for k, v in fam.lower_is_better.items()}
    old_doc = json.loads(out.read_text(encoding="utf-8")) if out.is_file() else {}
    old_measured = old_doc.get("measured") or {}
    changesets_dir = out.resolve().parent / ".changesets"
    try:
        for ds, entry in release_doc.get("measured", {}).items():
            old_entry = old_measured.get(ds) or {}
            old_metrics = old_entry.get("metrics") or {}
            for metric, new_value in (entry.get("metrics") or {}).items():
                old_value = old_metrics.get(metric)
                if not isinstance(old_value, (int, float)) or not isinstance(new_value, (int, float)):
                    continue
                _bshift.assert_baseline_not_relaxed(
                    old_value, new_value,
                    lower_is_better=lower_is_better.get(metric, False),
                    gate="release", dataset=f"{ds}:{metric}",
                    changesets_dir=changesets_dir,
                )
            # tempdoc 664 (post-review fix): `run_metrics` (throughput/footprint) is a sibling
            # family to `metrics` on the same measured entry — perf_gate.py's own floor
            # projection reads BOTH (project_release_to_perf_baselines falls back to run_metrics
            # for primary_docs_s/enrich_docs_s/resident_bytes). The metrics-only loop above missed
            # it, leaving 3 of 4 perf-gate metrics unguarded against a release-recompose regression.
            old_run_metrics = old_entry.get("run_metrics") or {}
            for metric, new_value in (entry.get("run_metrics") or {}).items():
                old_value = old_run_metrics.get(metric)
                if not isinstance(old_value, (int, float)) or not isinstance(new_value, (int, float)):
                    continue
                _bshift.assert_baseline_not_relaxed(
                    old_value, new_value,
                    lower_is_better=lower_is_better.get(metric, False),
                    gate="release", dataset=f"{ds}:{metric}",
                    changesets_dir=changesets_dir,
                )
        # tempdoc 683: the leak section is a projected ratchet source too (leak-gate's
        # current_release pointer) — a recompose raising a corpus's leak_rate is a
        # relaxation (leak is lower-is-better) and needs the same justified changeset.
        old_leak = old_doc.get("leak") or {}
        for ds, entry in (release_doc.get("leak") or {}).items():
            old_value = (old_leak.get(ds) or {}).get("leak_rate")
            new_value = entry.get("leak_rate")
            if not isinstance(old_value, (int, float)) or not isinstance(new_value, (int, float)):
                continue
            _bshift.assert_baseline_not_relaxed(
                old_value, new_value, lower_is_better=True,
                gate="release", dataset=f"{ds}:leak_rate",
                changesets_dir=changesets_dir,
            )
        # tempdoc 701: the union_recall section is a projected ratchet source too
        # (union-recall-gate's current_release pointer) — a recompose LOWERING a corpus's
        # union-recall floor is a relaxation (union-recall is higher-is-better) and needs the
        # same justified changeset.
        old_union_recall = old_doc.get("union_recall") or {}
        for ds, entry in (release_doc.get("union_recall") or {}).items():
            old_value = (old_union_recall.get(ds) or {}).get("leg_union_recall")
            new_value = entry.get("leg_union_recall")
            if not isinstance(old_value, (int, float)) or not isinstance(new_value, (int, float)):
                continue
            _bshift.assert_baseline_not_relaxed(
                old_value, new_value, lower_is_better=False,
                gate="release", dataset=f"{ds}:leg_union_recall",
                changesets_dir=changesets_dir,
            )
        # A measured or leak row that disappears removes that corpus's floor or ceiling, which is a
        # relaxation too. The loops above only see rows present in the new release, so check the
        # old rows here: an explicit withhold reason recorded in the new release justifies the
        # removal; otherwise the same justified changeset is required.
        new_withheld = release_doc.get("withheld") or {}
        for section, old_rows, suffix in (("measured", old_measured, "measured"),
                                          ("leak", old_leak, "leak_rate")):
            new_rows = release_doc.get(section) or {}
            for ds in sorted(set(old_rows) - set(new_rows)):
                reason = (new_withheld.get(ds) or {}).get("reason")
                if isinstance(reason, str) and reason.strip():
                    continue
                _bshift.assert_baseline_not_removed(
                    gate="release", dataset=f"{ds}:{suffix}", changesets_dir=changesets_dir,
                )
    except _bshift.BaselineRelaxedWithoutJustificationError as e:
        click.echo(f"release refused: {e}", err=True)
        sys.exit(1)

    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(
        json.dumps(release_doc, indent=2, sort_keys=True, ensure_ascii=False),
        encoding="utf-8",
    )
    click.echo(json.dumps({
        "release": str(out),
        "config_cohort_key": release_doc["cohort"]["config_cohort_key"][:12],
        "git_sha": (release_doc["cohort"].get("git_sha") or "")[:10],
        "default_mode": default_mode,
        "corpora": sorted(release_doc["measured"]),
        "missing_default_mode": release_doc["notes"]["missing_default_mode"],
    }, indent=2))


@click.command("suite-profile")
@click.option("--suite", required=True, help="Suite tag, e.g. 635-self-demo-v1.")
@click.option("--datasets-dir", default=None, type=click.Path())
@click.option("--records-root", default=None, type=click.Path(),
              help="Dir holding per-member 635-<name>/{release,utility-comparison}.v1.json (optional).")
@click.option("--output", default=None, type=click.Path(),
              help="Write the profile JSON here (the committed, engine-SHA-stamped ceiling snapshot).")
@click.pass_context
def cmd_suite_profile(ctx, suite, datasets_dir, records_root, output):
    """Project the suite's certified members into ONE profile (tempdoc 635 §R-3: a profile, not a number).

    Per-member: type, contamination class, closed-book + fidelity verdicts, retrieval nDCG/difficulty, and
    (where present) the agent delta — exposing where the engine is strong/weak across document/query types.
    With --output, writes a durable snapshot stamped with the engine git SHA (datasets/ is gitignored, so the
    committed snapshot is the durable ceiling record; the levers are default-on, so the SHA pins behaviour)."""
    import datetime as _dt
    import subprocess as _sp

    from .. import suite_profile as sp
    from .._paths import REPO_ROOT

    base = Path(datasets_dir) if datasets_dir else (REPO_ROOT / "datasets")
    sha = None
    try:
        _p = _sp.run(["git", "rev-parse", "HEAD"], cwd=str(REPO_ROOT),
                     capture_output=True, text=True, timeout=10)
        # Only trust stdout on a clean exit (a non-zero exit with partial stdout would stamp garbage).
        sha = _p.stdout.strip() if _p.returncode == 0 else None
        sha = sha or None
    except Exception:
        sha = None
    prof = sp.build_profile(
        suite, base, Path(records_root) if records_root else None,
        engine_git_sha=sha, generated_date=_dt.datetime.now(_dt.timezone.utc).date().isoformat())
    if output:
        Path(output).write_text(json.dumps(prof, ensure_ascii=False, indent=2), encoding="utf-8")
        click.echo(f"Wrote suite profile -> {output} (engine_git_sha={sha[:12] if sha else '?'})", err=True)
    if ctx.obj.get("json"):
        click.echo(json.dumps(prof, indent=2))
    else:
        click.echo(f"Suite '{suite}' — {prof['n_members']} members (profile, not a number):")
        for r in prof["members"]:
            agent = (f" agent_acc_d={r['agent_acc_delta']:+}" if r.get("agent_acc_delta") is not None else "")
            click.echo(f"  [{r['type_axis']:>9}] {r['member']}: clean={r['closed_book_passed']} "
                       f"fidelity={r['fidelity_passed']} nDCG={r['retrieval_ndcg']} "
                       f"({r['retrieval_difficulty']}) leaks={r['shortcut_leak_rate']}{agent}")


COMMANDS = [cmd_release, cmd_suite_profile]
