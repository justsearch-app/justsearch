#!/usr/bin/env python3
"""Self-tests for the coverage-register 'modes' filter (sandbox rounds 9+10,
tempdoc 734 round-9/round-10 sections + tempdoc 804 Section B10).

Both rounds asked for the same thing: the four upgrade-survival charter
questions (index survives, user data survives, embedding-compat continuity,
NSIS over-install) are answered from charter prose every round because
"the generated must-touch set is byte-identical to a fresh round's"
(tempdoc 734, round-9 harness note; repeated verbatim as round-10's H2 near-
miss root cause). This promotes them into governance/sandbox-coverage.v1.json
as mustWatch items scoped to `"modes": ["upgrade-from-release"]`, and adds the
OPTIONAL 'modes' field itself: a coverage-register item (cohortCoverage/
surfaceCoverage/shapeCoverage/claims/mustWatch row) with no 'modes' key stays
unconditional (existing behavior, unchanged); a 'modes' key scopes the item to
only the round(s) whose resolved validation mode is listed.

Covers, at minimum:

- mode_included(): absent 'modes' -> always True (mode=None, mode='x', any
  mode); present 'modes' + matching mode -> True; present 'modes' + mismatched
  mode -> False; present 'modes' + mode=None (the --check / no-round-context
  path) -> True (drift-checking must still see every declared item).
- build_manifest(..., mode=...): a mode-mismatched mustWatch item is EXCLUDED
  from the staged manifest's "mustWatch" list; a matching item is INCLUDED; an
  item with no 'modes' key is included regardless of mode.
- build_brief_markdown(...): the rendered Must-watch section reflects the same
  filtered set (never disagrees with the manifest a round's tooling reads).
- claims get the same optional filtering (generic 'modes' support extends to
  all coverage-item arrays, not just mustWatch).
- End-to-end through sandbox-launch.py's stage_coverage_brief(): staging with
  validation-mode.md resolved to 'upgrade-from-release' includes the real
  register's four upgrade-survival mustWatch items in the staged
  coverage-manifest.json; staging with 'fresh-install' excludes them (proves
  sandbox-launch.py actually threads the resolved mode through to
  gen_coverage_brief.py's --mode flag, not just that the flag exists).

BITE PROOF (documented here, not committed): temporarily inverting
mode_included()'s match condition (`return mode not in modes`) makes
test_matching_mode_is_included and the end-to-end
test_staging_with_matching_mode_includes_the_real_upgrade_items fail (a
matching mode gets excluded instead of included), and
test_mismatched_mode_is_excluded flips to failing the other way (a mismatched
mode gets included). Restored after confirming the failure.

Run: python scripts/sandbox/test_sandbox_coverage_mode_filter.py
"""

from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

from gen_coverage_brief import (  # noqa: E402
    build_brief_markdown,
    build_manifest,
    mode_included,
    run_drift_check,
)

# sandbox-launch.py's filename contains a hyphen, so it can't be `import`ed by
# name -- loaded via importlib.util, mirroring
# test_sandbox_launch_convergence_freshness.py's load pattern.
_spec = importlib.util.spec_from_file_location(
    "sandbox_launch_mode_filter_under_test", SCRIPT_DIR / "sandbox-launch.py"
)
sandbox_launch = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(sandbox_launch)  # type: ignore[union-attr]


# run_drift_check hard-errors if ANY of the three derived sets (cohort/
# surface/shape) comes back empty, so every fixture below carries one filler
# cohort + one filler exempt surface/shape row (same pattern as
# test_gen_coverage_brief_reach.py's _register()).
DUMMY_SURFACE_ID = "core.dummy-surface"
DUMMY_SHAPE_ID = "core.dummy-shape"


def _register(must_watch=None, claims=None) -> dict:
    return {
        "cohortCoverage": [{"cohort": "c1", "tier": "sandbox", "validateHow": "x"}],
        "surfaceCoverage": [
            {"surfaceId": DUMMY_SURFACE_ID, "tier": "exempt", "reason": "filler row, keeps the derived surface set non-empty"}
        ],
        "shapeCoverage": [
            {"shape": DUMMY_SHAPE_ID, "tier": "exempt", "reason": "filler row, keeps the derived shape set non-empty"}
        ],
        "cohortExempt": [],
        "surfaceExempt": [],
        "shapeExempt": [],
        "mustWatch": must_watch if must_watch is not None else [],
        "claims": claims if claims is not None else [],
    }


def _cohort_routes() -> dict:
    return {"c1": ["GET /a"]}


def _surface_placements() -> dict:
    return {DUMMY_SURFACE_ID: "RAIL"}


def _shapes() -> list:
    return [DUMMY_SHAPE_ID]


def _manifest_for(register: dict, mode: str | None) -> dict:
    errors, warnings, results = run_drift_check(
        register, _cohort_routes(), _surface_placements(), _shapes()
    )
    assert errors == [], errors
    return build_manifest(results, _cohort_routes(), register, mode=mode)


class ModeIncludedUnitTests(unittest.TestCase):
    """Direct tests of the filter primitive."""

    def test_absent_modes_key_is_always_included_with_no_mode(self):
        self.assertTrue(mode_included({"id": "x"}, None))

    def test_absent_modes_key_is_always_included_with_a_mode(self):
        self.assertTrue(mode_included({"id": "x"}, "upgrade-from-release"))
        self.assertTrue(mode_included({"id": "x"}, "fresh-install"))

    def test_empty_modes_list_is_treated_as_absent(self):
        self.assertTrue(mode_included({"id": "x", "modes": []}, "fresh-install"))

    def test_matching_mode_is_included(self):
        item = {"id": "x", "modes": ["upgrade-from-release"]}
        self.assertTrue(mode_included(item, "upgrade-from-release"))

    def test_mismatched_mode_is_excluded(self):
        item = {"id": "x", "modes": ["upgrade-from-release"]}
        self.assertFalse(mode_included(item, "fresh-install"))

    def test_mode_none_includes_a_modes_scoped_item(self):
        # The --check / no-round-context path: drift-checking must still see
        # every declared item regardless of any 'modes' scoping.
        item = {"id": "x", "modes": ["upgrade-from-release"]}
        self.assertTrue(mode_included(item, None))

    def test_multi_value_modes_list_matches_any_member(self):
        item = {"id": "x", "modes": ["upgrade-from-release", "in-app-update-from-release"]}
        self.assertTrue(mode_included(item, "in-app-update-from-release"))
        self.assertFalse(mode_included(item, "fresh-install"))


class BuildManifestMustWatchModeFilterTests(unittest.TestCase):
    """build_manifest(..., mode=...): the staged manifest's mustWatch list."""

    def _must_watch_fixture(self) -> list:
        return [
            {"id": "always-on", "reason": "r", "origin": "o", "observability": "sandbox"},
            {
                "id": "upgrade-only",
                "reason": "r",
                "origin": "o",
                "observability": "sandbox",
                "modes": ["upgrade-from-release"],
            },
        ]

    def test_modes_mismatch_excluded_from_staged_manifest(self):
        register = _register(must_watch=self._must_watch_fixture())
        manifest = _manifest_for(register, mode="fresh-install")
        ids = {i["id"] for i in manifest["mustWatch"]}
        self.assertNotIn("upgrade-only", ids)

    def test_match_included_in_staged_manifest(self):
        register = _register(must_watch=self._must_watch_fixture())
        manifest = _manifest_for(register, mode="upgrade-from-release")
        ids = {i["id"] for i in manifest["mustWatch"]}
        self.assertIn("upgrade-only", ids)

    def test_absent_modes_always_included_regardless_of_round_mode(self):
        register = _register(must_watch=self._must_watch_fixture())
        for mode in (None, "fresh-install", "upgrade-from-release", "pre-staged-models"):
            manifest = _manifest_for(register, mode=mode)
            ids = {i["id"] for i in manifest["mustWatch"]}
            self.assertIn("always-on", ids, f"failed for mode={mode!r}")

    def test_no_mode_given_includes_everything(self):
        register = _register(must_watch=self._must_watch_fixture())
        manifest = _manifest_for(register, mode=None)
        ids = {i["id"] for i in manifest["mustWatch"]}
        self.assertEqual(ids, {"always-on", "upgrade-only"})

    def test_default_mode_argument_preserves_prior_verbatim_passthrough_behavior(self):
        # build_manifest's pre-existing callers (test_gen_coverage_brief_reach.py)
        # call it with no mode argument at all -- the new `mode` kwarg must
        # default to the same "include everything" behavior, not break them.
        must_watch = [{"id": "install-trust-prompts", "reason": "r", "observability": "blocked-by-posture", "note": "n"}]
        register = _register(must_watch=must_watch)
        errors, warnings, results = run_drift_check(
            register, _cohort_routes(), _surface_placements(), _shapes()
        )
        self.assertEqual(errors, [])
        manifest = build_manifest(results, _cohort_routes(), register)
        self.assertEqual(manifest["mustWatch"], must_watch)


class BuildBriefMarkdownModeFilterTests(unittest.TestCase):
    """The rendered Must-watch (and Claims cross-check) sections must never
    disagree with the manifest a round's tooling reads."""

    def test_rendered_brief_excludes_mode_mismatched_mustwatch_item(self):
        must_watch = [
            {"id": "always-on", "reason": "r", "origin": "o", "observability": "sandbox"},
            {
                "id": "upgrade-only-marker-xyz",
                "reason": "r",
                "origin": "o",
                "observability": "sandbox",
                "modes": ["upgrade-from-release"],
            },
        ]
        register = _register(must_watch=must_watch)
        manifest = _manifest_for(register, mode="fresh-install")
        brief = build_brief_markdown(manifest, register, mode="fresh-install")
        self.assertIn("always-on", brief)
        self.assertNotIn("upgrade-only-marker-xyz", brief)

    def test_rendered_brief_includes_mode_matched_mustwatch_item(self):
        must_watch = [
            {
                "id": "upgrade-only-marker-xyz",
                "reason": "r",
                "origin": "o",
                "observability": "sandbox",
                "modes": ["upgrade-from-release"],
            },
        ]
        register = _register(must_watch=must_watch)
        manifest = _manifest_for(register, mode="upgrade-from-release")
        brief = build_brief_markdown(manifest, register, mode="upgrade-from-release")
        self.assertIn("upgrade-only-marker-xyz", brief)

    def test_claims_section_respects_modes_field_too(self):
        claims = [
            {"claim": "always claim", "source": "s", "coversId": "cohort:c1"},
            {
                "claim": "upgrade-only claim marker",
                "source": "s",
                "coversId": "cohort:c1",
                "modes": ["upgrade-from-release"],
            },
        ]
        register = _register(claims=claims)
        manifest = _manifest_for(register, mode="fresh-install")
        brief = build_brief_markdown(manifest, register, mode="fresh-install")
        self.assertIn("always claim", brief)
        self.assertNotIn("upgrade-only claim marker", brief)


class StageCoverageBriefEndToEndModeTests(unittest.TestCase):
    """End-to-end through the real staging entry point sandbox-launch.py's
    stage_coverage_brief() uses, against the REAL repo register
    (governance/sandbox-coverage.v1.json) -- proves sandbox-launch.py actually
    resolves the round's mode from validation-mode.md and threads it into
    gen_coverage_brief.py's --mode flag, not just that the flag exists."""

    UPGRADE_ONLY_IDS = {
        "upgrade-index-survives",
        "upgrade-user-data-survives",
        "upgrade-embedding-compat-continuity",
        "upgrade-nsis-over-install",
    }

    def _stage(self, mode_line: str) -> dict:
        with tempfile.TemporaryDirectory() as tmp_str:
            share_dir = Path(tmp_str)
            (share_dir / "validation-mode.md").write_text(
                "# Sandbox Validation Mode\n\n"
                f"- Mode: {mode_line}\n"
                "- Installer: whatever.exe\n",
                encoding="utf-8",
            )
            sandbox_launch.stage_coverage_brief(share_dir)
            manifest = json.loads((share_dir / "coverage-manifest.json").read_text(encoding="utf-8"))
            return manifest

    def test_staging_with_matching_mode_includes_the_real_upgrade_items(self):
        manifest = self._stage("upgrade-from-release")
        ids = {i["id"] for i in manifest["mustWatch"]}
        self.assertTrue(
            self.UPGRADE_ONLY_IDS.issubset(ids),
            f"missing: {self.UPGRADE_ONLY_IDS - ids!r}",
        )
        # and an always-on item survives alongside them
        self.assertIn("ui-api-truthfulness-under-load", ids)

    def test_staging_with_a_different_mode_excludes_the_real_upgrade_items(self):
        manifest = self._stage("fresh-install")
        ids = {i["id"] for i in manifest["mustWatch"]}
        self.assertEqual(
            self.UPGRADE_ONLY_IDS & ids,
            set(),
            f"unexpectedly present: {self.UPGRADE_ONLY_IDS & ids!r}",
        )
        # always-on items are unaffected by mode
        self.assertIn("ui-api-truthfulness-under-load", ids)


class DowngradeCoverageEndToEndTests(unittest.TestCase):
    def test_downgrade_round_brief_plan_and_checker_cover_every_outcome(self):
        from check_coverage import check_mustwatch_verdicts

        with tempfile.TemporaryDirectory() as tmp_str:
            share = Path(tmp_str)
            sandbox_launch.write_validation_mode(
                share, Path("candidate.exe"), None, False,
                upgrade_info=("main-release.exe", "a" * 64), direction="downgrade"
            )
            sandbox_launch.stage_coverage_brief(share)
            sandbox_launch.stage_round_plan(share)
            manifest = json.loads((share / "coverage-manifest.json").read_text(encoding="utf-8"))
            ids = {row["id"] for row in manifest["mustWatch"]}
            self.assertIn("downgrade-after-lane-f", ids)
            self.assertNotIn("upgrade-index-survives", ids)
            row = next(row for row in manifest["mustWatch"]
                       if row["id"] == "downgrade-after-lane-f")
            self.assertEqual(row["modes"], ["downgrade-to-release"])
            self.assertEqual(row["observability"], "sandbox")
            for filename in ("coverage-brief.md", "round-plan.md"):
                text = (share / filename).read_text(encoding="utf-8")
                for outcome in (
                    "install main's current release, then the Lane F candidate",
                    "add a setting, ingest, run a durable operation",
                    "install main's release again", "the app boots",
                    "settings are reset", ".bak and .corrupt-* files exist",
                    "following the documented restore brings them back",
                    "jobs.db rebuilds", "the index opens or rebuilds",
                    "operations.db is left untouched", "byte-identical operations.db",
                ):
                    with self.subTest(filename=filename, outcome=outcome):
                        self.assertIn(outcome, text)
            verdict_path = share / "mustwatch-verdicts.v1.json"
            verdicts = [{"id": item_id, "verdict": "observed-pass"} for item_id in sorted(ids)]
            verdict_path.write_text(json.dumps({"items": verdicts}), encoding="utf-8")
            ok, reason, failures = check_mustwatch_verdicts(str(share), manifest)
            self.assertTrue(ok, reason)
            self.assertEqual(failures, [])
            verdict_path.write_text(json.dumps({"items": [v for v in verdicts
                if v["id"] != "downgrade-after-lane-f"]}), encoding="utf-8")
            ok, reason, _ = check_mustwatch_verdicts(str(share), manifest)
            self.assertFalse(ok)
            self.assertIn("downgrade-after-lane-f", reason)

    def test_downgrade_row_is_excluded_from_other_modes(self):
        helper = StageCoverageBriefEndToEndModeTests()
        for mode in ("fresh-install", "upgrade-from-release", "in-app-update-from-release"):
            with self.subTest(mode=mode):
                manifest = helper._stage(mode)
                self.assertNotIn("downgrade-after-lane-f",
                                 {row["id"] for row in manifest["mustWatch"]})


if __name__ == "__main__":
    unittest.main()
