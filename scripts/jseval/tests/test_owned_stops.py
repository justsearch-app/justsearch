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
            helper = Path(tmp) / "gone.ps1"
            helper.write_text("param([Alias('Pid')]$TargetPid,$CreationFileTimeUtc,$Code,[switch]$Tree)\n'{\"outcome\":\"gone\"}'; $global:LASTEXITCODE=3", encoding="utf-8")
            env = {**os.environ, "JUSTSEARCH_SWEEP_RECORD": str(record), "JUSTSEARCH_SWEEP_TERMINATOR": str(helper)}
            result = subprocess.run(["powershell.exe", "-NoProfile", "-Command", command], env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, "gone is a successful stop: " + result.stderr)

    def test_backend_without_spawn_record_never_requests_a_stop(self):
        requests = []
        with patch.object(backend.subprocess, "run", side_effect=lambda *a, **k: requests.append(a) or SimpleNamespace(stdout='{"outcome":"mismatch"}')), patch.object(backend.run_register, "unregister_backend"):
            with self.assertRaises(RuntimeError, msg="unstarted or legacy processes must never be stopped"):
                backend.stop_backend(Child())
        self.assertEqual(requests, [])

    def test_backend_passes_the_spawn_birth_to_the_termination_handle(self):
        child = Child()
        try:
            owned = importlib.import_module("jseval.owned_process")
        except ModuleNotFoundError:
            owned = None
        if owned:
            owned.capture(child, birth_reader=lambda p: BIRTH)
        requests = []
        def run(args, **kwargs):
            requests.append(args)
            return SimpleNamespace(stdout=json.dumps({"outcome": "mismatch"}), returncode=3)
        with patch.object(backend.subprocess, "run", side_effect=run), patch.object(backend.run_register, "unregister_backend"):
            backend.stop_backend(child)
        self.assertTrue(requests, "a recorded stop must reach the injected identity boundary")
        self.assertIn("-CreationFileTimeUtc", requests[0], "reuse at termination must be refused through a birth-bound handle")
        self.assertIn(BIRTH, requests[0])
        self.assertIn("-Tree", requests[0])

    def test_health_failure_never_stops_a_llama_it_did_not_start(self):
        source = ROOT / "scripts/diagnostics/inference/health_failure.py"
        tree = ast.parse(source.read_text(encoding="utf-8"))
        boundary = next(node for node in ast.walk(tree) if isinstance(node, ast.Assign) and isinstance(node.value, ast.Call)
                        and (isinstance(node.value.func, ast.Name) and node.value.func.id == "stop_named_descendants"
                             or isinstance(node.value.func, ast.Attribute) and node.value.func.attr == "run"))
        requests = []
        fake_subprocess = SimpleNamespace(run=lambda *a, **k: requests.append(a) or SimpleNamespace(stdout="", stderr="", returncode=0))
        try:
            owned = importlib.import_module("jseval.owned_process")
        except ModuleNotFoundError:
            owned = None
        context = {"subprocess": fake_subprocess, "info": SimpleNamespace(proc=Child()),
                   "stop_named_descendants": owned.stop_named_descendants if owned else None}
        try:
            exec(compile(ast.Module(body=[boundary], type_ignores=[]), str(source), "exec"), context)
        except RuntimeError:
            pass
        self.assertEqual(requests, [], "global executable-name termination can spend someone else's llama")

    def test_sweep_leaves_a_foreign_port_owner_alone(self):
        source = (ROOT / "scripts/search/sweep-cc-weights.sh").read_text(encoding="utf-8")
        body = source.split("kill_backend() {", 1)[1].split("\n}", 1)[0]
        match = re.search(r"powershell.*?-Command ([\"'])([\s\S]*?)\1", body)
        self.assertIsNotNone(match)
        command = match[2].replace("\\$", "$").replace("$PORT", "33221")
        with tempfile.TemporaryDirectory() as tmp:
            record = Path(tmp) / "owned.json"
            record.write_text(json.dumps({"pid": 12345, "creationFileTimeUtc": BIRTH}), encoding="utf-8")
            helper = Path(tmp) / "fake-terminator.ps1"
            helper.write_text("param([Alias('Pid')]$TargetPid,$CreationFileTimeUtc,$Code,[switch]$Tree)\nif ($TargetPid -ne 12345 -or $CreationFileTimeUtc -ne '" + BIRTH + "') {throw 'identity lost'}\n$global:LASTEXITCODE=0", encoding="utf-8")
            # No production process can be stopped: both discovery and termination are simulated.
            script = "$script:foreignKills=0; function Get-NetTCPConnection { [pscustomobject]@{OwningProcess=99999} }; function Stop-Process { $script:foreignKills++ }; " + command.replace("exit $LASTEXITCODE", "") + "; if ($script:foreignKills -gt 0) {exit 9}"
            env = {**os.environ, "JUSTSEARCH_SWEEP_RECORD": str(record), "JUSTSEARCH_SWEEP_TERMINATOR": str(helper)}
            result = subprocess.run(["powershell.exe", "-NoProfile", "-Command", script], env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, "a foreign listener must not be killed: " + result.stderr)

    def test_local_installer_cleanup_rechecks_its_spawn_identity(self):
        source = (ROOT / "scripts/ci/verify-installer-nsis-win.ps1").read_text(encoding="utf-8")
        fn = source.split("function Stop-HeadlessBackendAndWait", 1)[1].split("\n}\n", 1)[0]
        prefix = ""
        if "function Stop-RecordedInstallerChild" in source:
            prefix = "function Stop-RecordedInstallerChild" + source.split("function Stop-RecordedInstallerChild", 1)[1].split("\n}\n", 1)[0] + "\n}\n"
        with tempfile.TemporaryDirectory() as tmp:
            lib = Path(tmp) / "scripts/dev/lib"; lib.mkdir(parents=True)
            (lib / "terminate-process.ps1").write_text("param([Alias('Pid')]$TargetPid,$CreationFileTimeUtc,$Code,[switch]$Tree)\nif($CreationFileTimeUtc -ne '" + BIRTH + "'){throw 'birth lost'}\n$global:LASTEXITCODE=3", encoding="utf-8")
            script = "$script:kills=0; function taskkill {$script:kills++}; function Add-BackoffSleep {}; $repoRoot='" + tmp.replace("'", "''") + "'; " + prefix + "function Stop-HeadlessBackendAndWait" + fn + "\n}; $p=[pscustomobject]@{Id=12345;HasExited=$false;JustSearchRecordedBirth='" + BIRTH + "'}; $p|Add-Member ScriptMethod Kill {$script:kills++}; Stop-HeadlessBackendAndWait -Process $p -Det @{} -TimeoutSec 0|Out-Null; if($script:kills -gt 0){exit 9}"
            result = subprocess.run(["powershell.exe", "-NoProfile", "-Command", script], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, "a replacement must survive delayed installer cleanup: " + result.stderr)


if __name__ == "__main__":
    unittest.main()
