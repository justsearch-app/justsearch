#!/usr/bin/env python3
"""Read-only staging tests for the in-app updater Sandbox lane (tempdoc 617)."""

from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location(
    "sandbox_launch_in_app_under_test", SCRIPT_DIR / "sandbox-launch.py"
)
sandbox_launch = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(sandbox_launch)  # type: ignore[union-attr]

stage_in_app_updater_assets = sandbox_launch.stage_in_app_updater_assets
write_validation_mode = sandbox_launch.write_validation_mode


class InAppUpdaterClosedSetTests(unittest.TestCase):
    def test_candidate_digest_mismatch_fails_before_staging(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            root = Path(tmp_str)
            share = root / "share"
            release = root / "release"
            share.mkdir()
            release.mkdir()
            installer = root / "JustSearch_2.0.0_x64-setup.exe"
            installer.write_bytes(b"selected candidate")
            descriptor = {
                "schemaVersion": 2,
                "metadataKeyId": "sandbox-root",
                "version": "2.0.0",
                "sequence": 2,
                "artifact": {
                    "url": f"http://127.0.0.1:8765/{installer.name}",
                    "sha256": "0" * 64,
                    "signature": "signed",
                },
            }
            artifact = descriptor.pop("artifact")
            descriptor["targets"] = {"windows-x86_64": {"artifact": artifact, "compatibility": []}}
            latest = {
                "version": "2.0.0",
                "platforms": {
                    "windows-x86_64": {
                        "url": artifact["url"],
                        "signature": "signed",
                    }
                },
            }
            (release / "release.v2.json").write_text(json.dumps(descriptor))
            (release / "release.v2.json.sig").write_text("metadata-signature")
            (release / "latest.json").write_text(json.dumps(latest))
            (release / f"{installer.name}.sig").write_text("artifact-signature")
            public_key = root / "metadata-public.pem"
            public_key.write_text("not reached")

            with self.assertRaises(SystemExit) as ctx:
                stage_in_app_updater_assets(share, installer, release, public_key)

            self.assertIn("not a closed loopback Sandbox set", str(ctx.exception))
            self.assertFalse((share / "updater-release").exists())

    def test_production_https_descriptor_is_not_rewritten_for_the_test_lane(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            root = Path(tmp_str)
            share = root / "share"
            release = root / "release"
            share.mkdir()
            release.mkdir()
            installer = root / "JustSearch_2.0.0_x64-setup.exe"
            installer.write_bytes(b"candidate")
            import hashlib

            digest = hashlib.sha256(installer.read_bytes()).hexdigest()
            descriptor = {
                "schemaVersion": 2,
                "metadataKeyId": "production-root",
                "version": "2.0.0",
                "sequence": 2,
                "artifact": {
                    "url": f"https://example.invalid/{installer.name}",
                    "sha256": digest,
                    "signature": "signed",
                },
            }
            artifact = descriptor.pop("artifact")
            descriptor["targets"] = {"windows-x86_64": {"artifact": artifact, "compatibility": []}}
            latest = {
                "version": "2.0.0",
                "platforms": {
                    "windows-x86_64": {
                        "url": artifact["url"],
                        "signature": "signed",
                    }
                },
            }
            for name, value in {
                "release.v2.json": json.dumps(descriptor),
                "release.v2.json.sig": "metadata-signature",
                "latest.json": json.dumps(latest),
                f"{installer.name}.sig": "artifact-signature",
            }.items():
                (release / name).write_text(value)
            public_key = root / "metadata-public.pem"
            public_key.write_text("not reached")

            with self.assertRaises(SystemExit) as ctx:
                stage_in_app_updater_assets(share, installer, release, public_key)

            self.assertIn("not a closed loopback Sandbox set", str(ctx.exception))


class InAppUpdaterModeTests(unittest.TestCase):
    def test_mode_names_in_app_path_and_recovery_commands(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            share = Path(tmp_str)
            write_validation_mode(
                share,
                Path("JustSearch_2.0.0_x64-setup.exe"),
                models_dir=None,
                no_models=False,
                upgrade_info=("JustSearch_1.0.0_x64-setup.exe", "a" * 64),
                updater_info={"version": "2.0.0", "sequence": 9},
            )

            text = (share / "validation-mode.md").read_text(encoding="utf-8")
            self.assertIn("Mode: in-app-update-from-release", text)
            self.assertIn("authenticated in-app updater", text)
            self.assertIn("start-in-app-update-test.ps1", text)
            self.assertIn("collect-updater-evidence.ps1", text)
            self.assertIn("ExpectPriorInstall: true", text)


if __name__ == "__main__":
    unittest.main()

