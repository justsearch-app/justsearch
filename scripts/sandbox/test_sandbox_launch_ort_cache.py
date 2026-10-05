#!/usr/bin/env python3
"""Sandbox ORT store isolation and worker sidecar permissions (tempdoc 958)."""

import importlib.util
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


_spec = importlib.util.spec_from_file_location(
    "sandbox_launch_ort_cache_under_test",
    Path(__file__).resolve().parent / "sandbox-launch.py",
)
sandbox_launch = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(sandbox_launch)


class OrtCacheSandboxTests(unittest.TestCase):
    def test_cache_stays_in_guest_profile_with_and_without_mapped_models(self):
        for map_models in (False, True):
            with self.subTest(map_models=map_models), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                wsb = root / "sandbox.wsb"
                models = root / "models" if map_models else None
                sandbox_launch.generate_wsb(wsb, root / "share", 16384, models)
                config = ET.parse(wsb).getroot()
                command = config.findtext("LogonCommand/Command")
                cache = r"%LOCALAPPDATA%\JustSearch\cache\ort-optimized"
                self.assertIn(f'if not exist "{cache}" mkdir "{cache}"', command)
                self.assertIn(
                    f'setx JUSTSEARCH_ORT_OPTIMIZED_CACHE_DIR "{cache}" >nul', command
                )
                self.assertLess(command.index("mkdir"), command.index("setx JUSTSEARCH_ORT"))
                self.assertLess(command.index("setx JUSTSEARCH_ORT"), command.index("explorer.exe"))
                folders = config.findall("MappedFolders/MappedFolder")
                self.assertEqual(len(folders), 2 if map_models else 1)
                if map_models:
                    self.assertEqual(folders[1].findtext("HostFolder"), str(models))
                    self.assertEqual(
                        folders[1].findtext("SandboxFolder"), sandbox_launch.SANDBOX_MODELS_FOLDER
                    )
                    self.assertEqual(folders[1].findtext("ReadOnly"), "false")
                self.assertNotIn("setx JUSTSEARCH_MODELS_DIR", command)


if __name__ == "__main__":
    unittest.main()
