"""Tests for jseval.gate (tempdoc 400 §26.6 D4; Phase 6 / 6.13 relocated).

Tempdoc 930 §18.1 row 7 removed the σ-band arm (it read a cohort envelope that was
never calibrated on any machine), leaving the manifest-readable + required-projections
assertions.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from jseval import gate as _gate


@pytest.fixture(scope="module")
def gate():
    """Expose the gate module as the fixture the layout tests expect.

    Phase 6 / 6.13: module moved from ``scripts/ci/phase3_observability_gate.py``
    into the jseval package. The fixture is kept so the body of the
    existing layout tests compiles unchanged — only the import source
    changes.
    """
    return _gate


def _build_layout(
    root: Path, *,
    cohort: str = "cohort-x",
    write_manifest: bool = True,
    projections: list[str] = None,
) -> Path:
    projections = projections if projections is not None else [
        "contract_violations",
        "rate_timeline",
        "cpu_fallback_counts",
    ]

    run_dir = root / "eval-results" / "20260422T060000_scifact"
    run_dir.mkdir(parents=True, exist_ok=True)
    (run_dir / "summary.json").write_text(json.dumps({"ok": True}),
                                          encoding="utf-8")
    if write_manifest:
        (run_dir / "manifest.json").write_text(
            json.dumps({"manifest_hash": cohort}), encoding="utf-8")

    proj_dir = run_dir / "projections"
    proj_dir.mkdir(parents=True, exist_ok=True)
    for name in projections:
        (proj_dir / f"{name}.json").write_text(
            json.dumps({"projection_name": name, "schema_version": 1}),
            encoding="utf-8")

    return root


class TestEvaluate:
    def test_passes_on_healthy_layout(self, gate, tmp_path):
        _build_layout(tmp_path)
        report = gate.evaluate(tmp_path)
        assert report["exit_code"] == 0
        check_names = {c["name"]: c["status"] for c in report["checks"]}
        assert check_names["run-manifest-present"] == "ok"
        assert check_names["required-projections-present"] == "ok"
        assert report["cohort_hash"] == "cohort-x"

    def test_infra_exit_when_no_run_dir(self, gate, tmp_path):
        report = gate.evaluate(tmp_path)
        assert report["exit_code"] == 2
        assert report["checks"][0]["name"] == "run-dir-present"

    def test_fails_when_manifest_is_absent(self, gate, tmp_path):
        _build_layout(tmp_path, write_manifest=False)
        report = gate.evaluate(tmp_path)
        assert report["exit_code"] == 1
        manifest_check = next(c for c in report["checks"]
                              if c["name"] == "run-manifest-present")
        assert manifest_check["status"] == "fail"

    def test_fails_when_manifest_is_unparseable(self, gate, tmp_path):
        _build_layout(tmp_path)
        run_dir = tmp_path / "eval-results" / "20260422T060000_scifact"
        (run_dir / "manifest.json").write_text("{not json", encoding="utf-8")
        report = gate.evaluate(tmp_path)
        assert report["exit_code"] == 1

    @pytest.mark.parametrize("missing_projection", [
        "contract_violations", "rate_timeline",
    ])
    def test_fails_when_required_projection_missing(
        self, gate, tmp_path, missing_projection,
    ):
        _build_layout(tmp_path,
                      projections=[name for name in
                                   ["contract_violations", "rate_timeline"]
                                   if name != missing_projection])
        report = gate.evaluate(tmp_path)
        assert report["exit_code"] == 1
        missing = next(c for c in report["checks"]
                       if c["name"] == "required-projections-present")
        assert missing["status"] == "fail"
        assert missing_projection in missing["detail"]

    def test_passes_with_extra_projections(self, gate, tmp_path):
        _build_layout(tmp_path,
                      projections=list(gate.REQUIRED_PROJECTIONS) +
                                  ["future_projection_x"])
        report = gate.evaluate(tmp_path)
        assert report["exit_code"] == 0


class TestReportOutput:
    def test_report_is_written(self, gate, tmp_path):
        _build_layout(tmp_path)
        out = tmp_path / "gate-report.json"
        report = gate.evaluate(tmp_path)
        out.write_text(json.dumps(report, indent=2), encoding="utf-8")
        reloaded = json.loads(out.read_text(encoding="utf-8"))
        assert reloaded["exit_code"] == 0
        assert reloaded["cohort_hash"] == "cohort-x"
