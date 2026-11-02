"""Tests for the recall-leak ratchet (tempdoc 636 / D-005)."""

from __future__ import annotations

import json

from jseval.leak_gate import (
    DEFAULT_TOLERANCE_ABS,
    derive_baselines,
    evaluate,
    project_release_to_baselines,
)


def _proj(leak_rate, status="ok"):
    return {"status": status, "aggregate": {"leak_rate": leak_rate}}


def _baselines(dataset, ceiling, tol=0.05):
    return {"baselines": {dataset: {"leak_rate_max": ceiling, "tolerance_abs": tol}}}


class TestLeakGate:
    def test_unpinned_dataset_does_not_gate(self):
        rep = evaluate({"baselines": {}}, _proj(0.9), "mixed/enron-qa")
        assert rep["exit_code"] == 0
        assert rep["checks"][0]["status"] == "skip"

    def test_under_ceiling_ok(self):
        rep = evaluate(_baselines("d", 0.10), _proj(0.12), "d")  # 0.12 <= 0.10+0.05
        assert rep["exit_code"] == 0
        assert rep["checks"][0]["status"] == "ok"

    def test_over_ceiling_regression(self):
        rep = evaluate(_baselines("d", 0.10), _proj(0.20), "d")  # 0.20 > 0.10+0.05
        assert rep["exit_code"] == 1
        assert rep["checks"][0]["status"] == "fail"

    def test_missing_projection_status_exit_2(self):
        rep = evaluate(_baselines("d", 0.10), _proj(None, status="insufficient-modes"), "d")
        assert rep["exit_code"] == 2

    def test_missing_leak_rate_exit_2(self):
        rep = evaluate(_baselines("d", 0.10), {"status": "ok", "aggregate": {}}, "d")
        assert rep["exit_code"] == 2

    def test_tolerance_default_used_when_absent(self):
        # ceiling 0.10, no per-corpus tolerance → default 0.05 → limit 0.15.
        bl = {"baselines": {"d": {"leak_rate_max": 0.10}}, "tolerance_default_abs": 0.05}
        assert evaluate(bl, _proj(0.15), "d")["exit_code"] == 0
        assert evaluate(bl, _proj(0.16), "d")["exit_code"] == 1


class TestDeriveBaselines:
    def test_measured_rate_becomes_ceiling(self):
        out = derive_baselines({"mixed/enron-qa": _proj(0.07)})
        row = out["baselines"]["mixed/enron-qa"]
        assert row["leak_rate_max"] == 0.07  # the *measured* rate, not measured+tol
        assert row["tolerance_abs"] == DEFAULT_TOLERANCE_ABS
        assert out["schema"] == "leak-gate-baseline.v1"
        assert out["derived_from_runs"] is True

    def test_per_corpus_tolerance_override(self):
        out = derive_baselines(
            {"d": _proj(0.10)}, tolerance_default_abs=0.05,
            per_corpus_tolerance={"d": 0.02})
        assert out["baselines"]["d"]["tolerance_abs"] == 0.02
        assert out["tolerance_default_abs"] == 0.05

    def test_skips_non_ok_or_missing_projection(self):
        out = derive_baselines({
            "ok": _proj(0.12),
            "bad": _proj(None, status="insufficient-modes"),
            "empty": {"status": "ok", "aggregate": {}},
        })
        assert set(out["baselines"]) == {"ok"}  # only the well-formed projection pins

    def test_roundtrip_measured_run_passes_regression_fires(self):
        # A run at the measured rate passes; a run beyond measured+tolerance fires.
        derived = derive_baselines({"d": _proj(0.10)}, tolerance_default_abs=0.05)
        assert evaluate(derived, _proj(0.10), "d")["exit_code"] == 0   # at baseline → pass
        assert evaluate(derived, _proj(0.15), "d")["exit_code"] == 0   # within tolerance → pass
        assert evaluate(derived, _proj(0.16), "d")["exit_code"] == 1   # beyond → regression


# tempdoc 664 (twelfth pass): `leak-gate-derive` CLI-level canonicalization -- the bare-name-vs-
# canonical-slug inconsistency (leak-gate-baselines.v1.json had "scifact" while relevance-/perf-gate
# use "beir/scifact") was traced to this command never canonicalizing its --datasets input.

def test_leak_gate_derive_canonicalizes_bare_beir_slug(tmp_path):
    import json as _json
    from click.testing import CliRunner
    from jseval.cli import main

    data_dir = tmp_path
    run_dir = data_dir / "eval-results" / "20260701_000000_scifact"
    (run_dir / "projections").mkdir(parents=True)
    (run_dir / "projections" / "staged_recall_accounting.json").write_text(
        _json.dumps({"status": "ok", "aggregate": {"leak_rate": 0.02}}), encoding="utf-8")
    out_path = tmp_path / "out.json"

    r = CliRunner().invoke(main, [
        "leak-gate-derive", "--data-dir", str(data_dir), "--datasets", "scifact",
        "--out", str(out_path),
    ])
    assert r.exit_code == 0, r.output
    derived = _json.loads(out_path.read_text(encoding="utf-8"))
    # the RAW input "scifact" is what locates the run directory (named from jseval run's literal
    # --dataset argument), but the OUTPUT key is canonicalized to match relevance-/perf-gate.
    assert "beir/scifact" in derived["baselines"]
    assert "scifact" not in derived["baselines"]
    assert derived["baselines"]["beir/scifact"]["leak_rate_max"] == 0.02


# --- tempdoc 683: current_release pointer + fallback_baselines ---------------

class TestReleaseProjection:
    def _release(self, leak=None, release_id="rel-test-2026-01-01"):
        doc = {"schema": "release.v1", "release_id": release_id, "cohort": {"git_sha": "abc123def4"}}
        if leak is not None:
            doc["leak"] = leak
        return doc

    def test_projects_release_leak_section(self):
        rel = self._release(leak={"beir/scifact": {"leak_rate": 0.02}})
        out = project_release_to_baselines(rel, tolerance_default_abs=0.05)
        row = out["baselines"]["beir/scifact"]
        assert row["leak_rate_max"] == 0.02
        assert row["tolerance_abs"] == 0.05
        assert row["src"] == "projected from release rel-test-2026-01-01"
        assert out["projected_from_release"] is True

    def test_projects_nothing_without_leak_section(self):
        out = project_release_to_baselines(self._release())
        assert out["baselines"] == {}

    def test_per_corpus_tolerance_override(self):
        rel = self._release(leak={"d": {"leak_rate": 0.1}})
        out = project_release_to_baselines(
            rel, tolerance_default_abs=0.05, per_corpus_tolerance={"d": 0.01})
        assert out["baselines"]["d"]["tolerance_abs"] == 0.01

    def test_pointer_falls_back_when_release_has_no_leak_section(self, tmp_path):
        """The tempdoc 683 migration invariant: with a pointer to a release that carries
        no `leak` section, the loaded baselines are exactly the fallback values —
        gate behavior unchanged vs the pre-migration inline file."""
        from jseval.ratchet_kernel import load_baselines_doc

        fallback = {"mixed/enron-qa": {"leak_rate_max": 0.0467, "tolerance_abs": 0.05}}
        (tmp_path / "release.v1.json").write_text(
            json.dumps({"schema": "release.v1", "measured": {}}), encoding="utf-8")
        bp = tmp_path / "leak-gate-baselines.v1.json"
        bp.write_text(json.dumps({
            "schema": "leak-gate-baseline.v1",
            "current_release": "release.v1.json",
            "tolerance_default_abs": 0.05,
            "fallback_baselines": fallback,
        }), encoding="utf-8")
        doc = load_baselines_doc(bp, project_release=lambda rel, base:
                                 project_release_to_baselines(rel, tolerance_default_abs=0.05))
        assert doc["baselines"] == fallback
        # And evaluate over the loaded doc behaves exactly like the old inline shape.
        assert evaluate(doc, _proj(0.09), "mixed/enron-qa")["exit_code"] == 0
        assert evaluate(doc, _proj(0.10), "mixed/enron-qa")["exit_code"] == 1

    def test_pointer_prefers_release_leak_section_over_fallback(self, tmp_path):
        from jseval.ratchet_kernel import load_baselines_doc

        (tmp_path / "release.v1.json").write_text(json.dumps({
            "schema": "release.v1", "release_id": "rel-test-2026-01-01",
            "leak": {"mixed/enron-qa": {"leak_rate": 0.01}},
        }), encoding="utf-8")
        bp = tmp_path / "leak-gate-baselines.v1.json"
        bp.write_text(json.dumps({
            "current_release": "release.v1.json",
            "tolerance_default_abs": 0.05,
            "fallback_baselines": {
                "mixed/enron-qa": {"leak_rate_max": 0.4, "tolerance_abs": 0.05},
                "beir/scifact": {"leak_rate_max": 0.0133, "tolerance_abs": 0.05},
            },
        }), encoding="utf-8")
        doc = load_baselines_doc(bp, project_release=lambda rel, base:
                                 project_release_to_baselines(rel, tolerance_default_abs=0.05))
        assert doc["baselines"]["mixed/enron-qa"]["leak_rate_max"] == 0.01  # release wins
        assert doc["baselines"]["beir/scifact"]["leak_rate_max"] == 0.0133  # fallback survives


def test_committed_pointer_file_projects_to_prior_pinned_values():
    """Pins the tempdoc 683 pointer semantics on the REAL committed files (tempdoc 715, then
    832 rebaseline). release.v1.json carries a `leak` section for its four non-withheld corpora, so
    the release projection must WIN for those, while the fallback baseline survives verbatim
    for the corpora absent from it (golden/needle-burial-v1, withheld legal-clerc-200). release_id is read
    live rather than pinned to a fixed string — `jseval release` re-cuts this file routinely,
    and a literal version pin would need re-editing on every unrelated rebaseline; the real
    regression coverage is the corpus-set and value assertions below, which already read live
    from the committed release."""
    from pathlib import Path

    from jseval.leak_gate import project_release_to_baselines as _project
    from jseval.ratchet_kernel import load_baselines_doc

    root = Path(__file__).resolve().parents[1]
    bp = root / "leak-gate-baselines.v1.json"
    raw = json.loads(bp.read_text(encoding="utf-8"))
    release = json.loads((root / "release.v1.json").read_text(encoding="utf-8"))
    assert isinstance(release.get("release_id"), str) and release["release_id"], \
        "release.v1.json must carry a non-empty release_id"
    # The withheld legal hybrid run sets no leak ceiling: leak is measured against the
    # default-mode final list that the harness judged not comparable.
    assert set(release["withheld"]) == {"mixed/legal-clerc-200"}
    assert set(release["leak"]) == {
        "beir/scifact", "mixed/enron-qa", "mixed/miracl-de-2k", "mixed/miracl-fr-2k"}
    doc = load_baselines_doc(bp, project_release=lambda rel, base: _project(
        rel, tolerance_default_abs=base.get("tolerance_default_abs", DEFAULT_TOLERANCE_ABS),
        per_corpus_tolerance=base.get("per_corpus_tolerance")))
    for corpus, entry in release["leak"].items():
        assert doc["baselines"][corpus]["leak_rate_max"] == entry["leak_rate"]  # release wins
    fallback_only = {"golden/needle-burial-v1", "mixed/legal-clerc-200"}
    for corpus in fallback_only:
        assert doc["baselines"][corpus] == raw["fallback_baselines"][corpus]  # fallback survives
    assert set(doc["baselines"]) == set(release["leak"]) | fallback_only
