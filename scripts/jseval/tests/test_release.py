"""Unit tests for the benchmark-release composer (tempdoc 623).

Pure-function tests over summary.json-shaped dicts (mirrors the
``test_relevance_gate.py`` inline-fixture style — no live run needed).
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest
from click.testing import CliRunner

from jseval import release
from jseval.commands.release import cmd_release


# --- fixtures ---------------------------------------------------------------

# config-global commit_metadata fps — identical across corpora for one config.
_CONFIG_GLOBAL = {
    "schema_fp": "schema-AAA",
    "similarity_fp": "sim-AAA",
    "boosts_fp": "boost-AAA",
    "grammar_hash": "gram-AAA",
}
# corpus-dependent fps — DIFFER between scifact (BEIR) and the mixed corpora.
_SCIFACT_CORPUS_FPS = {
    "field_catalog_hash": "fc-scifact",
    "index_fingerprint": "is-scifact",
    "synonyms_hash": "syn-empty",
}
_MIXED_CORPUS_FPS = {
    "field_catalog_hash": "fc-mixed",
    "index_fingerprint": "is-mixed",
    "synonyms_hash": "syn-mixed",
}


def _manifest(*, git_sha="commitAAA", corpus_fps=None, embed_gpu=True,
              dataset="beir/scifact", reranker_gpu=True,
              realized_engines=("dense", "reranker", "splade"), gpu_name="RTX 4070"):
    cm = dict(_CONFIG_GLOBAL)
    cm.update(corpus_fps or _SCIFACT_CORPUS_FPS)
    return {
        "git_sha": git_sha,
        "dataset": dataset,
        "eval_protocol_hash": "evalproto-AAA",
        "policy_hash": "policy-AAA",
        "commit_metadata": cm,
        "model_fingerprints": {
            "embed_fingerprint": "embed-AAA",
            "splade_model_path": "/models/splade",
            "embed_gpu": embed_gpu,      # execution flag — must NOT affect key
            "splade_gpu": embed_gpu,
            "reranker_gpu": reranker_gpu,  # tempdoc 644: execution flag — must NOT affect key
            # tempdoc 644: the realized engine SET — startup-stable identity; CE-on vs CE-off
            # must form distinct cohorts so a degraded run can't be averaged with a CE-on baseline.
            "realized_engines": list(realized_engines),
        },
        "env_fingerprint": {"gpu": {"name": gpu_name}},
        "inference_status_snapshot": {
            "tier": "gpu_12gb_plus",
            "gpu": {
                "totalVramBytes": 12884901120,
                "nvmlDriverVersion": "610.47",
                "cudaVersion": "12",  # Head-side constant (no ORT init)
            },
        },
        # ORT version rides the worker effective_config (debug-only WorkerDebugView, tempdoc 623 U7),
        # retained un-hashed in /api/debug/state.
        "debug_state_snapshot": {
            "worker": {"effective_config": {"ort.version": "1.20.0"}}
        },
    }


def _summary(*, dataset="beir/scifact", git_sha="commitAAA", corpus_fps=None,
             modes=None, query_count=300, embed_gpu=True, gpu_name="RTX 4070",
             run_metrics=None):
    """modes = {mode_name: {metric: value, ...}}"""
    modes = modes or {"hybrid": {"nDCG@10": 0.758, "P@1": 0.62, "R@10": 0.89}}
    per_mode = {
        m: {
            "aggregate_metrics": metrics,
            "comparable": True,
            "ann_proof_status": "PASS",
        }
        for m, metrics in modes.items()
    }
    return {
        "dataset": dataset,
        "query_count": query_count,
        "doc_count": 5183,
        "qrels_summary": {"relevance_mode": "binary", "query_count": query_count},
        "corpus_identity": {"signature": None},
        "per_mode": per_mode,
        "run_metrics": run_metrics or {},
        "manifest": _manifest(
            git_sha=git_sha, corpus_fps=corpus_fps, embed_gpu=embed_gpu,
            dataset=dataset, gpu_name=gpu_name,
        ),
    }


_AT = "2026-06-21T00:00:00+00:00"


# --- config_cohort_key ------------------------------------------------------

def test_config_key_ignores_corpus_dependent_fields_U1():
    """The U1 regression guard: scifact and a mixed corpus at the same config
    MUST share a config_cohort_key, even though their corpus-dependent
    commit_metadata fps differ. Otherwise no multi-corpus release composes."""
    scifact = _manifest(corpus_fps=_SCIFACT_CORPUS_FPS, dataset="beir/scifact")
    courtlistener = _manifest(corpus_fps=_MIXED_CORPUS_FPS, dataset="mixed/courtlistener-200")
    assert release.config_cohort_key(scifact) == release.config_cohort_key(courtlistener)


def test_config_key_ignores_gpu_execution_flags():
    """A GPU run and a CPU run of the same config share the key (flags excluded)."""
    gpu = _manifest(embed_gpu=True)
    cpu = _manifest(embed_gpu=False)
    assert release.config_cohort_key(gpu) == release.config_cohort_key(cpu)


def test_config_key_ignores_reranker_gpu_execution_flag():
    """tempdoc 644: the reranker DEVICE bit is execution-context (flaky pre-query) and is
    stripped from the key, exactly like embed_gpu/splade_gpu — a GPU reranker run and a CPU
    reranker run of the same config share the key."""
    gpu = _manifest(reranker_gpu=True)
    cpu = _manifest(reranker_gpu=False)
    assert release.config_cohort_key(gpu) == release.config_cohort_key(cpu)


def test_config_key_changes_on_realized_engine_set():
    """tempdoc 644: the realized engine SET (presence) IS cohort identity — a CE-on run and a
    CE-off (cross-encoder silently absent) run of the same commit MUST form distinct cohorts so
    the degraded run cannot be averaged with a CE-on baseline."""
    ce_on = _manifest(realized_engines=("dense", "reranker", "splade"))
    ce_off = _manifest(realized_engines=("dense", "splade"))
    assert release.config_cohort_key(ce_on) != release.config_cohort_key(ce_off)


def test_config_key_changes_on_real_config_change():
    """A different commit (or a config-global fp change) MUST change the key."""
    base = _manifest(git_sha="commitAAA")
    other = _manifest(git_sha="commitBBB")
    assert release.config_cohort_key(base) != release.config_cohort_key(other)
    changed_sim = _manifest()
    changed_sim["commit_metadata"]["similarity_fp"] = "sim-DIFFERENT"
    assert release.config_cohort_key(base) != release.config_cohort_key(changed_sim)


# --- compose ----------------------------------------------------------------

def test_compose_single_run():
    r = release.compose([_summary()], default_mode="hybrid", composed_at=_AT)
    assert r["schema"] == "release.v1"
    assert r["schema_version"] == 1
    assert "beir/scifact" in r["measured"]
    assert r["measured"]["beir/scifact"]["metrics"]["nDCG@10"] == 0.758
    assert r["measured"]["beir/scifact"]["confidence_tier"] == "A"
    assert "does_not_measure" in r["coverage"]


def test_compose_multi_corpus_same_config_U1():
    """scifact + a mixed corpus at one config compose into one release."""
    runs = [
        _summary(dataset="beir/scifact", corpus_fps=_SCIFACT_CORPUS_FPS,
                 modes={"hybrid": {"nDCG@10": 0.758}}),
        _summary(dataset="mixed/courtlistener-200", corpus_fps=_MIXED_CORPUS_FPS,
                 modes={"hybrid": {"nDCG@10": 0.620}}, query_count=200),
    ]
    r = release.compose(runs, default_mode="hybrid", composed_at=_AT)
    assert set(r["measured"]) == {"beir/scifact", "mixed/courtlistener-200"}
    assert r["measured"]["mixed/courtlistener-200"]["metrics"]["nDCG@10"] == 0.620
    # one cohort key
    assert len({release.config_cohort_key(s["manifest"]) for s in runs}) == 1


def test_compose_refuses_non_cohort_set():
    """Runs at different commits are not one cohort → ComposeError."""
    runs = [
        _summary(dataset="beir/scifact", git_sha="commitAAA"),
        _summary(dataset="mixed/courtlistener-200", git_sha="commitBBB"),
    ]
    with pytest.raises(release.ComposeError, match="not cohort-identical"):
        release.compose(runs, default_mode="hybrid", composed_at=_AT)


def test_compose_refuses_incomparable_default():
    s = _summary()
    s["per_mode"]["hybrid"]["comparable"] = False
    with pytest.raises(release.ComposeError, match="not comparable"):
        release.compose([s], default_mode="hybrid", composed_at=_AT)


def test_compose_metric_family_pluggable():
    """The metrics map is NOT nDCG-specific — an extraction-style run composes."""
    s = _summary(modes={"hybrid": {"WER": 0.08, "CER": 0.03, "route_accuracy": 0.94}})
    r = release.compose([s], default_mode="hybrid", composed_at=_AT)
    metrics = r["measured"]["beir/scifact"]["metrics"]
    assert metrics["WER"] == 0.08 and metrics["route_accuracy"] == 0.94
    assert "nDCG@10" not in metrics


def test_compose_records_ablations():
    """Non-default modes become labelled ablations, not the headline."""
    s = _summary(dataset="mixed/courtlistener-200", corpus_fps=_MIXED_CORPUS_FPS,
                 query_count=200,
                 modes={"hybrid": {"nDCG@10": 0.620}, "full": {"nDCG@10": 0.925}})
    r = release.compose([s], default_mode="hybrid", composed_at=_AT)
    assert r["measured"]["mixed/courtlistener-200"]["metrics"]["nDCG@10"] == 0.620
    abls = r["ablations"]["mixed/courtlistener-200"]
    assert any(a["config_mode"] == "full" and a["metrics"]["nDCG@10"] == 0.925 for a in abls)


def test_compose_carries_no_tolerance_band():
    # Tempdoc 930 §18.1 row 7: the +/-2 sigma band was projected from a cohort envelope
    # that was never calibrated, so every composed release recorded `tolerance_band: null`.
    # The key is gone rather than always-null.
    r = release.compose([_summary()], default_mode="hybrid", composed_at=_AT)
    assert "tolerance_band" not in r["measured"]["beir/scifact"]


def test_compose_hardware_projection():
    r = release.compose([_summary()], default_mode="hybrid", composed_at=_AT)
    hw = r["cohort"]["hardware"]
    assert hw["gpu_name"] == "RTX 4070"
    assert hw["ort_version"] == "1.20.0"  # from worker health effective_config (U7)
    assert hw["cuda_version"] == "12"     # Head-side pinned constant
    assert hw["gpu_driver_version"] == "610.47"


def test_compose_hardware_homogeneous_true_when_all_members_match():
    """tempdoc 664: all members ran on the same GPU -> homogeneous."""
    runs = [
        _summary(dataset="beir/scifact", corpus_fps=_SCIFACT_CORPUS_FPS),
        _summary(dataset="mixed/courtlistener-200", corpus_fps=_MIXED_CORPUS_FPS, query_count=200),
    ]
    r = release.compose(runs, default_mode="hybrid", composed_at=_AT)
    assert r["cohort"]["hardware_homogeneous"] is True


def test_compose_hardware_homogeneous_false_when_gpu_differs():
    """tempdoc 664: a release composed from mixed-GPU runs is flagged, not silently mixed —
    `config_cohort_key` itself must stay unaffected (still one cohort; hardware is
    deliberately excluded from that key, guarded by
    `test_config_key_ignores_gpu_execution_flags`), but the additive `hardware_homogeneous`
    flag lets a hardware-SENSITIVE consumer (perf_gate.py) refuse to trust the composed release."""
    runs = [
        _summary(dataset="beir/scifact", corpus_fps=_SCIFACT_CORPUS_FPS, gpu_name="RTX 4070"),
        _summary(dataset="mixed/courtlistener-200", corpus_fps=_MIXED_CORPUS_FPS,
                 query_count=200, gpu_name="RTX 3090"),
    ]
    r = release.compose(runs, default_mode="hybrid", composed_at=_AT)
    assert r["cohort"]["hardware_homogeneous"] is False
    # config_cohort_key is untouched by the hardware difference — still one cohort.
    assert len({release.config_cohort_key(s["manifest"]) for s in runs}) == 1


def test_compose_empty_raises():
    with pytest.raises(release.ComposeError):
        release.compose([], default_mode="hybrid", composed_at=_AT)


def test_canonical_dataset_slug():
    # BEIR short name (the form `jseval run --dataset` + summary.dataset use) → slug.
    assert release.canonical_dataset_slug("scifact") == "beir/scifact"
    # already-slugged forms are untouched.
    assert release.canonical_dataset_slug("mixed/courtlistener-200") == "mixed/courtlistener-200"
    assert release.canonical_dataset_slug("beir/scifact") == "beir/scifact"
    assert release.canonical_dataset_slug(None) is None


def test_select_dominant_cohort():
    # P1 (tempdoc 623): a mid-sweep commit move splits the latest-per-dataset set across cohorts;
    # pick the cohort covering the most datasets, report the rest as excluded.
    chosen, excluded = release.select_dominant_cohort({
        "beir/scifact": "cohortA",
        "mixed/courtlistener-200": "cohortA",
        "mixed/miracl-de-2k": "cohortA",
        "mixed/enron-qa": "cohortB",      # landed at a different commit (the split)
    })
    assert chosen == "cohortA"
    assert excluded == ["mixed/enron-qa"]


def test_select_dominant_cohort_no_split_and_empty():
    chosen, excluded = release.select_dominant_cohort({"a": "k", "b": "k"})
    assert chosen == "k" and excluded == []
    assert release.select_dominant_cohort({}) == (None, [])


def test_compose_normalizes_short_beir_name_to_slug():
    """A real BEIR run emits summary.dataset='scifact'; the release must key it as
    'beir/scifact' so the projected ratchet floor matches `--dataset beir/scifact`."""
    s = _summary(dataset="scifact", modes={"hybrid": {"nDCG@10": 0.755}})
    r = release.compose([s], default_mode="hybrid", composed_at=_AT)
    assert "beir/scifact" in r["measured"]
    assert "scifact" not in r["measured"]


# --- tempdoc 664 (post-review fix): cmd_release's baseline-shift guard must also cover
# `run_metrics` (throughput/footprint), not just the per-mode `metrics` family ---

def _write_run(run_dir, summary):
    run_dir.mkdir(parents=True, exist_ok=True)
    (run_dir / "summary.json").write_text(json.dumps(summary), encoding="utf-8")
    return run_dir


def test_cmd_release_refuses_run_metrics_relaxation_without_changeset(tmp_path):
    out = tmp_path / "release.v1.json"
    run1 = _write_run(tmp_path / "run1", _summary(run_metrics={"primary_docs_s": 100.0}))
    runner = CliRunner()
    r1 = runner.invoke(cmd_release, ["--run", str(run1), "--out", str(out), "--release-id", "rel-test-2026-01-01"])
    assert r1.exit_code == 0, r1.output

    # Second compose: throughput drops 100 -> 50 (a relaxation; primary_docs_s is
    # lower_is_better=False, so a decrease is worse) with no changeset present.
    run2 = _write_run(tmp_path / "run2", _summary(run_metrics={"primary_docs_s": 50.0}))
    r2 = runner.invoke(cmd_release, ["--run", str(run2), "--out", str(out), "--release-id", "rel-test-2026-01-01"])
    assert r2.exit_code == 1
    assert "release refused" in r2.output
    # The out file must NOT have been overwritten with the unjustified relaxation.
    assert json.loads(out.read_text())["measured"]["beir/scifact"]["run_metrics"]["primary_docs_s"] == 100.0


def test_cmd_release_allows_run_metrics_relaxation_with_justified_changeset(tmp_path):
    out = tmp_path / "release.v1.json"
    run1 = _write_run(tmp_path / "run1", _summary(run_metrics={"primary_docs_s": 100.0}))
    runner = CliRunner()
    assert runner.invoke(cmd_release, ["--run", str(run1), "--out", str(out), "--release-id", "rel-test-2026-01-01"]).exit_code == 0

    changesets_dir = out.resolve().parent / ".changesets"
    changesets_dir.mkdir(parents=True, exist_ok=True)
    (changesets_dir / "accept.md").write_text(
        '---\nclassification: "baseline-relaxation"\ngate: "release"\n'
        'dataset: "beir/scifact:primary_docs_s"\ntempdoc: "664"\n---\naccepted\n',
        encoding="utf-8",
    )

    run2 = _write_run(tmp_path / "run2", _summary(run_metrics={"primary_docs_s": 50.0}))
    r2 = runner.invoke(cmd_release, ["--run", str(run2), "--out", str(out), "--release-id", "rel-test-2026-01-01"])
    assert r2.exit_code == 0, r2.output
    assert json.loads(out.read_text())["measured"]["beir/scifact"]["run_metrics"]["primary_docs_s"] == 50.0


def test_cmd_release_allows_run_metrics_improvement_without_changeset(tmp_path):
    out = tmp_path / "release.v1.json"
    run1 = _write_run(tmp_path / "run1", _summary(run_metrics={"primary_docs_s": 100.0}))
    runner = CliRunner()
    assert runner.invoke(cmd_release, ["--run", str(run1), "--out", str(out), "--release-id", "rel-test-2026-01-01"]).exit_code == 0

    run2 = _write_run(tmp_path / "run2", _summary(run_metrics={"primary_docs_s": 150.0}))
    r2 = runner.invoke(cmd_release, ["--run", str(run2), "--out", str(out), "--release-id", "rel-test-2026-01-01"])
    assert r2.exit_code == 0, r2.output


# --- tempdoc 683: release_id contract (required + pattern) -------------------

def test_validate_release_id_accepts_conformant_ids():
    assert release.validate_release_id("667-external-baselines-2026-07-01") is None
    assert release.validate_release_id("rel-test-2026-01-01") is None


@pytest.mark.parametrize("bad", [
    None, "", "no-date-suffix", "Upper-Case-2026-07-01", "-leading-dash-2026-07-01",
    "spaces here-2026-07-01", "rel_underscore-2026-07-01", "rel-2026-7-1",
])
def test_validate_release_id_rejects_missing_or_malformed(bad):
    assert release.validate_release_id(bad) is not None


def test_cmd_release_refuses_missing_release_id(tmp_path):
    out = tmp_path / "release.v1.json"
    run1 = _write_run(tmp_path / "run1", _summary())
    r = CliRunner().invoke(cmd_release, ["--run", str(run1), "--out", str(out)])
    assert r.exit_code == 2
    assert "release_id is required" in r.output
    assert not out.is_file()


def test_cmd_release_refuses_malformed_release_id(tmp_path):
    out = tmp_path / "release.v1.json"
    run1 = _write_run(tmp_path / "run1", _summary())
    r = CliRunner().invoke(
        cmd_release, ["--run", str(run1), "--out", str(out), "--release-id", "NoPattern"])
    assert r.exit_code == 2
    assert "does not match" in r.output
    assert not out.is_file()


# --- tempdoc 683: optional leak section + corpus_source.upstream_revision ----

def test_compose_writes_leak_section_from_measured_rates():
    s = _summary(dataset="scifact")
    r = release.compose([s], default_mode="hybrid", composed_at=_AT,
                        leak_by_dataset={"scifact": 0.0133})
    assert r["leak"] == {
        "beir/scifact": {"leak_rate": 0.0133, "src": "staged_recall_accounting projection"},
    }


def test_compose_omits_leak_section_when_unsourced():
    r = release.compose([_summary()], default_mode="hybrid", composed_at=_AT)
    assert "leak" not in r


# --- tempdoc 701: optional union_recall section (leak's recall-survival sibling) --

def test_compose_writes_union_recall_section_from_measured_rates():
    s = _summary(dataset="scifact")
    r = release.compose([s], default_mode="hybrid", composed_at=_AT,
                        union_recall_by_dataset={"scifact": 0.97})
    assert r["union_recall"] == {
        "beir/scifact": {"leg_union_recall": 0.97, "src": "staged_recall_accounting projection"},
    }


def test_compose_omits_union_recall_section_when_unsourced():
    r = release.compose([_summary()], default_mode="hybrid", composed_at=_AT)
    assert "union_recall" not in r


def test_corpus_source_upstream_revision_passthrough_null():
    r = release.compose([_summary()], default_mode="hybrid", composed_at=_AT)
    src = r["measured"]["beir/scifact"]["corpus_source"]
    assert "upstream_revision" in src
    assert src["upstream_revision"] is None  # corpus_identity carries no revision today


def test_cmd_release_sources_leak_from_run_projection(tmp_path):
    out = tmp_path / "release.v1.json"
    run1 = _write_run(tmp_path / "run1", _summary(dataset="scifact"))
    proj_dir = run1 / "projections"
    proj_dir.mkdir()
    (proj_dir / "staged_recall_accounting.json").write_text(
        json.dumps({"status": "ok", "aggregate": {"leak_rate": 0.021}}), encoding="utf-8")
    r = CliRunner().invoke(
        cmd_release,
        ["--run", str(run1), "--out", str(out), "--release-id", "rel-test-2026-01-01"])
    assert r.exit_code == 0, r.output
    doc = json.loads(out.read_text(encoding="utf-8"))
    assert doc["release_id"] == "rel-test-2026-01-01"
    assert doc["leak"]["beir/scifact"]["leak_rate"] == 0.021


def test_cmd_release_refuses_leak_relaxation_without_changeset(tmp_path):
    out = tmp_path / "release.v1.json"

    def _run_with_leak(name, rate):
        rd = _write_run(tmp_path / name, _summary(dataset="scifact"))
        proj_dir = rd / "projections"
        proj_dir.mkdir()
        (proj_dir / "staged_recall_accounting.json").write_text(
            json.dumps({"status": "ok", "aggregate": {"leak_rate": rate}}), encoding="utf-8")
        return rd

    runner = CliRunner()
    r1 = runner.invoke(cmd_release, ["--run", str(_run_with_leak("run1", 0.02)),
                                     "--out", str(out), "--release-id", "rel-test-2026-01-01"])
    assert r1.exit_code == 0, r1.output
    # Second compose measures a HIGHER leak rate (a relaxation of the projected ceiling).
    r2 = runner.invoke(cmd_release, ["--run", str(_run_with_leak("run2", 0.30)),
                                     "--out", str(out), "--release-id", "rel-test-2026-01-01"])
    assert r2.exit_code == 1
    assert "release refused" in r2.output
    assert json.loads(out.read_text())["leak"]["beir/scifact"]["leak_rate"] == 0.02


def test_cmd_release_sources_union_recall_from_run_projection(tmp_path):
    out = tmp_path / "release.v1.json"
    run1 = _write_run(tmp_path / "run1", _summary(dataset="scifact"))
    proj_dir = run1 / "projections"
    proj_dir.mkdir()
    (proj_dir / "staged_recall_accounting.json").write_text(
        json.dumps({"status": "ok", "aggregate": {"leg_union_recall": 0.981}}), encoding="utf-8")
    r = CliRunner().invoke(
        cmd_release,
        ["--run", str(run1), "--out", str(out), "--release-id", "rel-test-2026-01-01"])
    assert r.exit_code == 0, r.output
    doc = json.loads(out.read_text(encoding="utf-8"))
    assert doc["release_id"] == "rel-test-2026-01-01"
    assert doc["union_recall"]["beir/scifact"]["leg_union_recall"] == 0.981


def test_cmd_release_refuses_union_recall_relaxation_without_changeset(tmp_path):
    out = tmp_path / "release.v1.json"

    def _run_with_union_recall(name, rate):
        rd = _write_run(tmp_path / name, _summary(dataset="scifact"))
        proj_dir = rd / "projections"
        proj_dir.mkdir()
        (proj_dir / "staged_recall_accounting.json").write_text(
            json.dumps({"status": "ok", "aggregate": {"leg_union_recall": rate}}),
            encoding="utf-8")
        return rd

    runner = CliRunner()
    r1 = runner.invoke(cmd_release, ["--run", str(_run_with_union_recall("run1", 0.98)),
                                     "--out", str(out), "--release-id", "rel-test-2026-01-01"])
    assert r1.exit_code == 0, r1.output
    # Second compose measures a LOWER union-recall (a relaxation of the projected floor).
    r2 = runner.invoke(cmd_release, ["--run", str(_run_with_union_recall("run2", 0.80)),
                                     "--out", str(out), "--release-id", "rel-test-2026-01-01"])
    assert r2.exit_code == 1
    assert "release refused" in r2.output
    assert json.loads(out.read_text())["union_recall"]["beir/scifact"]["leg_union_recall"] == 0.98


# --- tempdoc 802: artifact provenance (`sources`) -------------------------------------
# A release recorded its CONFIG provenance but never pointed at the ARTIFACTS it projected
# from, so once the run dirs were cleaned up the published numbers could not be re-scored.
# `715-rebaseline-2026-07-16` is the case: its runs exist nowhere on disk.

def test_compose_records_run_sources():
    s = _summary(dataset="scifact")
    r = release.compose(
        [s], default_mode="hybrid", composed_at=_AT,
        run_sources=[{"run_dir": "tmp/eval/run1", "summary_sha256": "abc123"}],
    )
    assert r["sources"] == [
        # dataset is the CANONICAL slug even though the summary said "scifact" — compose
        # supplies it so the CLI and the composer cannot disagree about naming.
        {"dataset": "beir/scifact", "run_dir": "tmp/eval/run1", "summary_sha256": "abc123"},
    ]


def test_compose_omits_sources_when_not_supplied():
    """Older callers (and every release composed before 802) stay valid: no empty section."""
    r = release.compose([_summary()], default_mode="hybrid", composed_at=_AT)
    assert "sources" not in r


def test_compose_refuses_misaligned_run_sources():
    """Fail CLOSED: a pointer to the wrong run is worse than none, because it looks authoritative."""
    with pytest.raises(release.ComposeError) as e:
        release.compose(
            [_summary(dataset="scifact"), _summary(dataset="mixed/enron-qa")],
            default_mode="hybrid", composed_at=_AT,
            run_sources=[{"run_dir": "tmp/only-one", "summary_sha256": "abc"}],
        )
    assert "parallel" in str(e.value)


def test_cmd_release_records_artifact_provenance(tmp_path):
    """End-to-end through the CLI: the recorded sha256 must be over the real summary.json bytes.

    This is the assertion that would have caught the original gap — it fails if `sources` is
    absent, and it fails for the right reason if the digest is of anything but the file.
    """
    import hashlib

    out = tmp_path / "release.v1.json"
    run1 = _write_run(tmp_path / "run1", _summary(dataset="scifact"))
    runner = CliRunner()
    res = runner.invoke(cmd_release, ["--run", str(run1), "--out", str(out),
                                      "--release-id", "prov-test-2026-01-01"])
    assert res.exit_code == 0, res.output

    doc = json.loads(out.read_text(encoding="utf-8"))
    assert len(doc["sources"]) == 1
    entry = doc["sources"][0]
    assert entry["dataset"] == "beir/scifact"

    expected = hashlib.sha256((run1 / "summary.json").read_bytes()).hexdigest()
    assert entry["summary_sha256"] == expected

    # The pointer must name this run — and must not be an empty/None placeholder that would
    # satisfy a laxer assertion while carrying no information.
    assert entry["run_dir"] and "run1" in entry["run_dir"].replace("\\", "/")


def test_repo_relative_renders_in_repo_paths_relative_and_posix():
    """The repo-relative branch of `_repo_relative`, which the CLI test does NOT reach.

    `tmp_path` lives outside the repo, so `test_cmd_release_records_artifact_provenance`
    only ever exercises the absolute-path fallback. This covers the branch that actually
    fires in production, where run dirs sit under the repo's tmp/.
    """
    from jseval._paths import REPO_ROOT
    from jseval.commands.release import _repo_relative

    inside = REPO_ROOT / "tmp" / "eval-results" / "run-abc"
    rendered = _repo_relative(inside)
    assert rendered == "tmp/eval-results/run-abc"
    assert "\\" not in rendered           # posix separators, so the value is portable
    assert not Path(rendered).is_absolute()


def test_repo_relative_falls_back_to_absolute_outside_repo(tmp_path):
    """Outside the repo there is no honest relative form — keep the absolute path."""
    from jseval.commands.release import _repo_relative

    outside = tmp_path / "somewhere" / "run1"
    assert _repo_relative(outside) == str(outside)


def test_withheld_default_keeps_ablations_and_source_without_a_floor():
    from jseval.relevance_gate import project_release_to_baselines

    summary = _summary(dataset='mixed/legal-clerc-200', modes={
        'hybrid': {'nDCG@10': 0.59}, 'lexical': {'nDCG@10': 0.69},
    })
    summary['ce_coverage'] = {'per_mode': {'hybrid': {
        'verdict': 'degraded-ce', 'applied': 174, 'silent_drops': 25,
    }}}
    source = {'run_dir': 'legal-run', 'summary_sha256': 'a' * 64}
    with pytest.raises(release.ComposeError, match='not comparable'):
        release.compose([summary], composed_at='2026-11-02')
    doc = release.compose(
        [summary], composed_at='2026-11-02', run_sources=[source],
        withheld_default={'mixed/legal-clerc-200': '25 deadline misses'},
    )
    assert doc['measured'] == {}
    assert doc['withheld']['mixed/legal-clerc-200']['ce_coverage']['silent_drops'] == 25
    assert doc['ablations']['mixed/legal-clerc-200'][0]['config_mode'] == 'lexical'
    assert doc['sources'][0]['summary_sha256'] == source['summary_sha256']
    assert project_release_to_baselines(doc)['baselines'] == {}


@pytest.mark.parametrize('withheld', [
    {'mixed/absent': 'deadline misses'}, {'beir/scifact': ' '},
])
def test_withholding_requires_an_included_dataset_and_reason(withheld):
    with pytest.raises(release.ComposeError, match='included datasets'):
        release.compose([_summary()], composed_at='2026-11-02', withheld_default=withheld)
