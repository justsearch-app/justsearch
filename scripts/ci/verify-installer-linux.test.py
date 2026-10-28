#!/usr/bin/env python3
"""Exercise the smoke with a recorded response, not a hypothetical health shape.

fixtures/installed-engine-health.json is the unmodified /api/health body from
the .deb built at b5c9c7e5f8 with package-installer-linux.sh on 2026-10-28.
It was captured by verify-installer-linux.py after launching the extracted
payload's bundled JVM with isolated state (no host package install).
"""
import copy
import json
from pathlib import Path
import runpy
import tempfile
import unittest
from unittest.mock import Mock, patch

HERE = Path(__file__).resolve().parent
smoke = runpy.run_path(str(HERE / "verify-installer-linux.py"))
health = json.loads((HERE / "fixtures/installed-engine-health.json").read_text())
status = json.loads((HERE.parent.parent / "modules/app-api/src/test/resources/contract/status-v1.json").read_text())


class InstalledEngineReadinessTest(unittest.TestCase):
    def exercise(self, response, index_available=True):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "runtime/bin").mkdir(parents=True)
            (root / "runtime/bin/java").touch()
            jar = root / "ui-headless.jar"
            jar.touch()
            evidence = root / "smoke.json"
            proc = Mock(pid=12345)
            proc.poll.return_value = None

            def launch(command, **kwargs):
                data = Path(next(arg.split("=", 1)[1] for arg in command if arg.startswith("-Djustsearch.data.dir=")))
                (data / "runtime").mkdir()
                (data / "runtime/manifest.json").write_text(json.dumps({"head": {"apiPort": 12345, "sessionToken": "test-only"}}))
                return proc

            current_status = {**status, "indexAvailable": index_available}
            request = Mock(side_effect=lambda port, endpoint, token, body=None: {
                "/api/status": current_status,
                "/api/health": response,
                "/api/knowledge/search": {"results": [{"path": "keyboard-shortcuts.md"}]},
            }[endpoint])
            with patch.dict(smoke["main"].__globals__, request=request), \
                    patch("sys.argv", ["verify-installer-linux.py", "test.deb", "--evidence", str(evidence)]), \
                    patch.dict("os.environ", GITHUB_SHA="b5c9c7e5f8", GITHUB_RUN_ID="test"), \
                    patch("subprocess.check_output", side_effect=["just-search", str(jar)]), \
                    patch("subprocess.Popen", side_effect=launch), \
                    patch("os.killpg"), patch("time.sleep"), \
                    patch("time.monotonic", side_effect=[0, 1, 241]):
                smoke["main"]()
            self.assertTrue(json.loads(evidence.read_text())["passed"])

    def test_recorded_current_health_without_worker_becomes_ready(self):
        self.assertNotIn("worker", health["components"])
        self.exercise(health)

    def test_nonready_or_missing_index_is_not_accepted(self):
        for state in ["STARTING", "ERROR", None]:
            with self.subTest(state=state):
                response = copy.deepcopy(health)
                if state is None:
                    del response["components"]["index"]
                else:
                    response["components"]["index"]["state"] = state
                with self.assertRaisesRegex(RuntimeError, "timed out"):
                    self.exercise(response)

    def test_available_index_is_required_even_with_ready_health(self):
        with self.assertRaisesRegex(RuntimeError, "timed out"):
            self.exercise(health, index_available=False)


if __name__ == "__main__":
    unittest.main()
