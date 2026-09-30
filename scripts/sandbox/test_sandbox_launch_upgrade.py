#!/usr/bin/env python3
"""Self-tests for sandbox-launch.py's upgrade-install round mode (tempdoc 750
Part C).

The launcher's mode set gains "upgrade-from-release": --upgrade-from
<path-to-previous-installer-exe> stages the previous public release's
installer into share/previous-release/ alongside the candidate, resolves
the round's mode to "upgrade-from-release" (combined internally with the
no-models resolution -- an upgrade round always exercises the real download
path, like fresh-install, never the pre-staged-models shortcut), and
records the previous installer's filename + SHA-256 plus the
install-then-upgrade instruction sequence in validation-mode.md. Every mode
also now writes a machine-readable "ExpectPriorInstall: true/false" marker
that collect-evidence.ps1 reads to decide whether a FOUND prior-install
signal is the round's expected state (upgrade-from-release) or a warning
(every other mode).

sandbox-launch.py's filename contains a hyphen, so it can't be `import`ed by
name -- loaded via importlib.util from its file path, mirroring
test_sandbox_launch_charter.py's and test_sandbox_launch_evidence_archive.py's
load pattern.

Run: python scripts/sandbox/test_sandbox_launch_upgrade.py
"""

from __future__ import annotations

import hashlib
import importlib.util
import sys
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent

_spec = importlib.util.spec_from_file_location(
    "sandbox_launch_upgrade_under_test", SCRIPT_DIR / "sandbox-launch.py"
)
sandbox_launch = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(sandbox_launch)  # type: ignore[union-attr]

resolve_upgrade_from = sandbox_launch.resolve_upgrade_from
stage_upgrade_installer = sandbox_launch.stage_upgrade_installer
write_validation_mode = sandbox_launch.write_validation_mode
main = sandbox_launch.main


class ResolveUpgradeFromTests(unittest.TestCase):
    """Unit-level tests for resolve_upgrade_from(), the --upgrade-from
    validation gate: must exist, must be a .exe, must differ in filename
    from the candidate installer."""

    def test_missing_path_aborts_nonzero(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            candidate = tmp / "Candidate-setup.exe"
            missing = tmp / "does-not-exist-setup.exe"
            with self.assertRaises(SystemExit) as ctx:
                resolve_upgrade_from(str(missing), candidate)
            self.assertNotEqual(ctx.exception.code, 0)
            self.assertIn("not found", str(ctx.exception.code))

    def test_non_exe_aborts_nonzero(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            candidate = tmp / "Candidate-setup.exe"
            not_exe = tmp / "previous-release.zip"
            not_exe.write_bytes(b"not an installer")
            with self.assertRaises(SystemExit) as ctx:
                resolve_upgrade_from(str(not_exe), candidate)
            self.assertNotEqual(ctx.exception.code, 0)
            self.assertIn(".exe", str(ctx.exception.code))

    def test_same_filename_as_candidate_aborts_nonzero(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            same_name_dir = tmp / "elsewhere"
            same_name_dir.mkdir()
            # Same filename as the candidate, but a different file/location --
            # must still be refused. Staging the "same file twice" is about
            # the label collision (candidate vs. previous release), not byte
            # identity.
            previous = same_name_dir / "JustSearch_0.2.0_x64-setup.exe"
            previous.write_bytes(b"previous installer bytes")
            candidate = tmp / "JustSearch_0.2.0_x64-setup.exe"
            with self.assertRaises(SystemExit) as ctx:
                resolve_upgrade_from(str(previous), candidate)
            self.assertNotEqual(ctx.exception.code, 0)
            message = str(ctx.exception.code)
            self.assertIn("same filename", message)

    def test_valid_upgrade_from_resolves(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            previous = tmp / "JustSearch_0.1.0_x64-setup.exe"
            previous.write_bytes(b"previous installer bytes")
            candidate = tmp / "JustSearch_0.2.0_x64-setup.exe"
            resolved = resolve_upgrade_from(str(previous), candidate)
            self.assertEqual(resolved, previous)


class StageUpgradeInstallerTests(unittest.TestCase):
    """Unit-level tests for stage_upgrade_installer(): copies the previous
    release into share/previous-release/ and returns its (filename, sha256)."""

    def test_staged_under_previous_release_with_recorded_sha256(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            share_dir = tmp / "share"
            share_dir.mkdir()
            content = b"previous public release installer bytes, 0.1.0"
            previous = tmp / "JustSearch_0.1.0_x64-setup.exe"
            previous.write_bytes(content)

            name, digest = stage_upgrade_installer(share_dir, previous)

            staged = share_dir / "previous-release" / "JustSearch_0.1.0_x64-setup.exe"
            self.assertTrue(staged.exists())
            self.assertEqual(staged.read_bytes(), content)
            self.assertEqual(name, "JustSearch_0.1.0_x64-setup.exe")
            self.assertEqual(digest, hashlib.sha256(content).hexdigest())


class WriteValidationModeUpgradeTests(unittest.TestCase):
    """Tests write_validation_mode()'s upgrade-from-release branch and the
    ExpectPriorInstall marker across all modes."""

    def test_upgrade_mode_records_name_sha256_and_instructions(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            share_dir = tmp / "share"
            share_dir.mkdir()
            digest = hashlib.sha256(b"whatever").hexdigest()
            upgrade_info = ("JustSearch_0.1.0_x64-setup.exe", digest)

            write_validation_mode(
                share_dir,
                Path("JustSearch_0.2.0_x64-setup.exe"),
                models_dir=None,
                no_models=False,
                upgrade_info=upgrade_info,
            )

            text = (share_dir / "validation-mode.md").read_text(encoding="utf-8")
            self.assertIn("Mode: upgrade-from-release", text)
            self.assertIn("ExpectPriorInstall: true", text)
            self.assertIn("JustSearch_0.1.0_x64-setup.exe", text)
            self.assertIn(digest, text)
            self.assertIn("PREVIOUS release", text)
            self.assertIn("CANDIDATE installer", text)
            self.assertIn("ADR-0024", text)

    def test_fresh_install_mode_writes_expect_prior_install_false(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            share_dir = tmp / "share"
            share_dir.mkdir()

            write_validation_mode(
                share_dir, Path("JustSearch-setup.exe"), None, no_models=True
            )

            text = (share_dir / "validation-mode.md").read_text(encoding="utf-8")
            self.assertIn("Mode: fresh-install", text)
            self.assertIn("ExpectPriorInstall: false", text)
            self.assertNotIn("ExpectPriorInstall: true", text)

    def test_pre_staged_models_mode_writes_expect_prior_install_false(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            share_dir = tmp / "share"
            share_dir.mkdir()
            fake_models_dir = tmp / "models"
            fake_models_dir.mkdir()

            write_validation_mode(
                share_dir, Path("JustSearch-setup.exe"), fake_models_dir, no_models=False
            )

            text = (share_dir / "validation-mode.md").read_text(encoding="utf-8")
            self.assertIn("Mode: pre-staged-models", text)
            self.assertIn("ExpectPriorInstall: false", text)


class MainArgvDrivenUpgradeTests(unittest.TestCase):
    """Proves the --upgrade-from/--models-dir mutual exclusion fires from
    main() itself, before any filesystem-dependent staging step, mirroring
    test_sandbox_launch_charter.py's MainArgvDrivenTests pattern. main()
    takes no argv parameter and reads sys.argv directly."""

    def _run_main_with_argv(self, argv_tail: list[str]):
        old_argv = sys.argv
        sys.argv = ["sandbox-launch.py"] + argv_tail
        try:
            main()
        finally:
            sys.argv = old_argv

    def test_upgrade_from_and_models_dir_mutually_exclusive_aborts_nonzero(self):
        with self.assertRaises(SystemExit) as ctx:
            self._run_main_with_argv(
                ["--upgrade-from", "some-previous-setup.exe", "--models-dir", "some-dir"]
            )
        self.assertNotEqual(ctx.exception.code, 0)
        message = str(ctx.exception.code)
        self.assertIn("--upgrade-from", message)
        self.assertIn("--models-dir", message)
        self.assertIn("mutually exclusive", message)

    def test_upgrade_from_alone_does_not_trip_the_mutual_exclusion_check(self):
        # Should get past the --upgrade-from/--models-dir mutual-exclusion
        # check and fail LATER (missing installer, or the --upgrade-from
        # path not resolving once staging reaches it) -- never on
        # "mutually exclusive".
        with self.assertRaises(SystemExit) as ctx:
            self._run_main_with_argv(
                [
                    "--no-models",
                    "--upgrade-from",
                    "does-not-exist-setup.exe",
                    "--no-charter",
                    "--installer",
                    "does-not-exist.exe",
                ]
            )
        message = str(ctx.exception.code)
        self.assertNotIn("mutually exclusive", message)


class DowngradeLaunchTests(unittest.TestCase):
    def test_resolver_rejects_invalid_paths_in_downgrade_direction(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            non_exe = tmp / "older.zip"
            non_exe.write_bytes(b"not exe")
            for path, message in ((tmp / "missing.exe", "not found"),
                                  (non_exe, "must be a .exe")):
                with self.subTest(path=path), self.assertRaises(SystemExit) as ctx:
                    resolve_upgrade_from(str(path), tmp / "candidate.exe", "downgrade")
                self.assertIn("--downgrade-from", str(ctx.exception.code))
                self.assertIn(message, str(ctx.exception.code))

    def test_downgrade_resolver_rejects_same_filename(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            older = Path(tmp_str) / "candidate.exe"
            older.write_bytes(b"older")
            with self.assertRaisesRegex(SystemExit, "same filename"):
                resolve_upgrade_from(str(older), Path("candidate.exe"), "downgrade")

    def test_upgrade_and_downgrade_flags_are_mutually_exclusive(self):
        with patch.object(sys, "argv", ["sandbox-launch.py", "--upgrade-from", "old.exe",
                                        "--downgrade-from", "older.exe"]):
            with self.assertRaises(SystemExit) as ctx:
                main()
            self.assertEqual(ctx.exception.code, 2)

    def test_downgrade_and_models_dir_are_mutually_exclusive(self):
        with patch.object(sys, "argv", ["sandbox-launch.py", "--downgrade-from", "old.exe",
                                        "--models-dir", "models"]):
            with self.assertRaisesRegex(SystemExit, "mutually exclusive"):
                main()

    def test_main_parses_downgrade_and_resolves_before_staging(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            candidate = tmp / "candidate.exe"
            older = tmp / "release.exe"
            candidate.write_bytes(b"candidate")
            older.write_bytes(b"release")
            argv = ["sandbox-launch.py", "--downgrade-from", str(older),
                    "--installer", str(candidate), "--no-charter", "--no-launch",
                    "--stage-dir", str(tmp / "stage")]
            with patch.object(sys, "argv", argv), patch.object(
                sandbox_launch, "resolve_upgrade_from", wraps=resolve_upgrade_from
            ) as resolver, patch.object(
                sandbox_launch, "archive_existing_evidence", side_effect=RuntimeError("staging boundary")
            ):
                with self.assertRaisesRegex(RuntimeError, "staging boundary"):
                    main()
                resolver.assert_called_once_with(str(older), candidate, "downgrade")

    def test_downgrade_mode_records_install_order_and_release_provenance(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            share = Path(tmp_str)
            older = share / "old.exe"
            older.write_bytes(b"older release")
            info = stage_upgrade_installer(share, older)
            write_validation_mode(share, Path("candidate.exe"), None, True,
                                  upgrade_info=info, direction="downgrade")
            text = (share / "validation-mode.md").read_text(encoding="utf-8")
            self.assertIn("Mode: downgrade-to-release", text)
            self.assertIn("ExpectPriorInstall: true", text)
            self.assertIn("previous-release/old.exe", text)
            self.assertIn(hashlib.sha256(b"older release").hexdigest(), text)
            self.assertLess(text.index("1. Install main's current release"),
                            text.index("2. Install the Lane F CANDIDATE"))
            self.assertLess(text.index("2. Install the Lane F CANDIDATE"),
                            text.index("3. Install main's release again"))
            for action in ("add a setting", "ingest", "run a durable operation"):
                self.assertIn(action, text)
            self.assertNotIn("Mode: fresh-install", text)


if __name__ == "__main__":
    unittest.main()
