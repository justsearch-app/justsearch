"""Identity boundary regressions: all OS actions are injected, never PID-only fixture kills."""
import ast
import importlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from jseval import backend

ROOT = Path(__file__).resolve().parents[3]
BIRTH = "134000000000000000"


class Child:
    pid = 12345
    returncode = None
    def poll(self):
        return None


class OwnedStopTest(unittest.TestCase):
    def test_exited_backend_without_identity_retires_record_without_stop(self):
        child = Child()
        child.returncode = 2
        child.poll = lambda: 2
        error = None
        with patch.object(backend.subprocess, "run") as stop, patch.object(backend.run_register, "unregister_backend") as retire:
            try:
                backend.stop_backend(child)
            except RuntimeError as exc:
                error = exc
            self.assertIsNone(error, "an exited backend needs no identity or termination")
            stop.assert_not_called()
            retire.assert_called_once_with(child.pid)

    def test_sweep_accepts_a_recorded_process_already_gone(self):
        source = (ROOT / "scripts/search/sweep-cc-weights.sh").read_text(encoding="utf-8")
        body = source.split("kill_backend() {", 1)[1].split("\n}", 1)[0]
        command = re.search(r"powershell.*?-Command ([\"'])([\s\S]*?)\1", body)[2]
        with tempfile.TemporaryDirectory() as tmp:
            record = Path(tmp) / "owned.json"
            record.write_text(json.dumps({"pid": 12345, "creationFileTimeUtc": BIRTH}), encoding="utf-8")
            # The run stop reports a run that had already ended as gone, with success (exit 0).
            helper = Path(tmp) / "gone.ps1"
            helper.write_text("param($RecordPath,$Code)\n'{\"outcome\":\"gone\",\"survivors\":[]}'; exit 0", encoding="utf-8")
            env = {**os.environ, "JUSTSEARCH_SWEEP_RECORD": str(record), "JUSTSEARCH_SWEEP_STOPPER": str(helper)}
            result = subprocess.run(["powershell.exe", "-NoProfile", "-Command", command], env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, "gone is a successful stop: " + result.stderr)

    def test_backend_stops_only_through_its_held_handle(self):
        # Identity-only stops, as on the integration side since 58a51a608: the backend's own
        # Popen handle is its identity, so no PID-addressed terminator and no tree is involved.
        child = Child()
        calls = []
        child.terminate = lambda: calls.append("terminate")
        child.wait = lambda timeout=None: calls.append("wait")
        child.kill = lambda: calls.append("kill")
        with patch.object(backend.subprocess, "run") as run, patch.object(backend.run_register, "unregister_backend") as retire:
            backend.stop_backend(child)
        run.assert_not_called()
        self.assertEqual(calls, ["terminate", "wait"], "only the held handle of the started backend is ended")
        retire.assert_called_once_with(child.pid)

    def test_owned_process_stop_without_spawn_record_never_requests_a_stop(self):
        owned = importlib.import_module("jseval.owned_process")
        requests = []
        with self.assertRaises(RuntimeError, msg="unstarted or legacy processes must never be stopped"):
            owned.stop(Child(), run=lambda *a, **k: requests.append(a))
        self.assertEqual(requests, [])

    def test_owned_process_passes_the_spawn_birth_and_never_a_tree(self):
        owned = importlib.import_module("jseval.owned_process")
        child = Child()
        owned.capture(child, birth_reader=lambda p: BIRTH)
        requests = []
        def run(args, **kwargs):
            requests.append(args)
            return SimpleNamespace(stdout=json.dumps({"outcome": "mismatch"}), returncode=3)
        if os.name == "nt":
            owned.stop(child, run=run)
            self.assertTrue(requests, "a recorded stop must reach the injected identity boundary")
            self.assertIn("-CreationFileTimeUtc", requests[0], "reuse at termination must be refused through a birth-bound handle")
            self.assertIn(BIRTH, requests[0])
            self.assertNotIn("-Tree", requests[0], "only the recorded process, never its tree")
        self.assertFalse(hasattr(owned, "stop_named_descendants"), "no descendant sweep through parent links")

    def test_health_failure_never_stops_a_llama_it_did_not_start(self):
        source = ROOT / "scripts/diagnostics/inference/health_failure.py"
        text = source.read_text(encoding="utf-8")
        tree = ast.parse(text)
        calls = [node for node in ast.walk(tree) if isinstance(node, ast.Call)]
        names = {getattr(c.func, "attr", getattr(c.func, "id", None)) for c in calls}
        # No name sweep, no descendant sweep and no external PID-addressed kill.
        self.assertNotIn("stop_named_descendants", names)
        self.assertNotIn("process_iter", names)
        self.assertNotIn("run", names, "a subprocess kill addresses a PID, not the recorded identity")
        self.assertNotIn("taskkill", text)
        # The helper is ended on the handle whose birth was matched to the Engine's recorded child.
        self.assertIn("TerminateProcess", names)
        self.assertLess(text.index('actual == int(start["creationFileTime"])'), text.index("kernel.TerminateProcess(helper"))
        self.assertLess(text.index('spawn["owner"]["creationFileTime"]'), text.index("kernel.TerminateProcess(helper"))

    def test_sweep_leaves_a_foreign_port_owner_alone(self):
        source = (ROOT / "scripts/search/sweep-cc-weights.sh").read_text(encoding="utf-8")
        body = source.split("kill_backend() {", 1)[1].split("\n}", 1)[0]
        match = re.search(r"powershell.*?-Command ([\"'])([\s\S]*?)\1", body)
        self.assertIsNotNone(match)
        command = match[2].replace("\\$", "$").replace("$PORT", "33221")
        with tempfile.TemporaryDirectory() as tmp:
            record = Path(tmp) / "owned.json"
            record.write_text(json.dumps({"pid": 12345, "creationFileTimeUtc": BIRTH}), encoding="utf-8")
            # The run stop is addressed by the run record alone, never by the port's owner.
            helper = Path(tmp) / "fake-stopper.ps1"
            helper.write_text("param($RecordPath,$Code)\nif ($RecordPath -ne $env:JUSTSEARCH_SWEEP_RECORD) {throw 'run record lost'}\nexit 0", encoding="utf-8")
            # No production process can be stopped: both discovery and termination are simulated.
            script = "$script:foreignKills=0; function Get-NetTCPConnection { [pscustomobject]@{OwningProcess=99999} }; function Stop-Process { $script:foreignKills++ }; " + command.replace("exit $LASTEXITCODE", "").replace("exit $status", "if ($status -ne 0) {exit $status}") + "; if ($script:foreignKills -gt 0) {exit 9}"
            env = {**os.environ, "JUSTSEARCH_SWEEP_RECORD": str(record), "JUSTSEARCH_SWEEP_STOPPER": str(helper)}
            result = subprocess.run(["powershell.exe", "-NoProfile", "-Command", script], env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, "a foreign listener must not be killed: " + result.stderr)

    def test_local_installer_cleanup_rechecks_its_spawn_identity(self):
        # Identity-only stops, as on the integration side since 58a51a608: the installer's
        # cleanup ends only the registered process handle after rechecking its start time.
        source = (ROOT / "scripts/ci/verify-installer-nsis-win.ps1").read_text(encoding="utf-8").replace("\r\n", "\n")
        self.assertNotIn("-Tree", source)
        fn = source.split("function Stop-HeadlessBackendAndWait", 1)[1].split("\n}\n", 1)[0]
        helper = str(ROOT / "scripts/testing/owned-process.ps1").replace("'", "''")
        def run(replaced, registered=True):
            script = ("$script:kills=0; function Add-BackoffSleep {}; . '" + helper + "'; "
                      + "function Stop-HeadlessBackendAndWait" + fn + "\n}; "
                      + "$p=[pscustomobject]@{Id=12345;HasExited=$false;StartTime=[DateTime]::new(2026,1,1,0,0,0,[DateTimeKind]::Utc)}; "
                      + "$p|Add-Member ScriptMethod Kill {$script:kills++}; $p|Add-Member ScriptMethod WaitForExit {param($ms) $true}; "
                      + ("Register-FixtureProcess $p; " if registered else "")
                      + ("$p.StartTime=[DateTime]::new(2026,1,2,0,0,0,[DateTimeKind]::Utc); " if replaced else "")
                      + "Stop-HeadlessBackendAndWait -Process $p -Det @{} -TimeoutSec 0|Out-Null; exit $script:kills")
            return subprocess.run(["powershell.exe", "-NoProfile", "-Command", script], capture_output=True, text=True)
        replaced = run(replaced=True)
        self.assertEqual(replaced.returncode, 0, "a replacement must survive delayed installer cleanup: " + replaced.stderr)
        foreign = run(replaced=False, registered=False)
        self.assertEqual(foreign.returncode, 0, "an unregistered process must never be killed: " + foreign.stderr)
        owned = run(replaced=False)
        self.assertEqual(owned.returncode, 1, "the registered, unchanged process is ended once: " + owned.stderr)


if __name__ == "__main__":
    unittest.main()
