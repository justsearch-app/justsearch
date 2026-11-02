#!/usr/bin/env python3
"""Explicit real Gradle/Engine admission acceptance (uses its own stack/data/ports).

Run after installDist and npm ci in modules/ui-web:
  python3 scripts/dev/test-machine-admission-integration.py
Builds use the machine Gradle slot; the candidate slot wrapper is tested directly.
"""
import fcntl
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time
import tempfile
import unittest
import test_admission as fixtures
CLI, PYTHON = fixtures.CLI, fixtures.PYTHON

REPO = CLI.parents[2]


class MachineIntegration(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.AdmissionTests()
        self.fixture.setUp()
        self.root, self.env = self.fixture.root, self.fixture.env

    def tearDown(self):
        self.fixture.tearDown()

    def test_tool_invoker_and_candidate_slot(self):
        log = open(self.root / "gradle.log", "w+")
        try:
            # The outer installed slot tool handles the real machine's queue.
            child = subprocess.Popen(["js-gradle-slot", "node", "scripts/lib/tool-invoker.cjs", "gradle", "help"],
                                     cwd=REPO, env=self.env, stdout=log, stderr=log)
            try:
                row = self.fixture.wait_for(lambda: next((r for r in self.fixture.status()
                                            if r["mode"] == "heavy" and r["purpose"] == "Gradle"), None), timeout=180)
                print("tool-invoker heavy: " + json.dumps(row), flush=True)
                self.assertEqual(child.wait(timeout=180), 0)
            finally:
                if child.poll() is None:
                    child.terminate()
                    child.wait(timeout=65)
            window_end = self.root / "window-end"
            holder = self.fixture.start("exclusive", [PYTHON, "-c",
                f"import time; from pathlib import Path\nwhile not Path({str(window_end)!r}).exists(): time.sleep(.01)"])
            self.fixture.wait_for(lambda: self.fixture.row("exclusive"))
            # Model installation: admission belongs beside the machine wrapper,
            # independently of the checkout from which Gradle is invoked.
            machine = self.root / "machine"
            machine.mkdir()
            wrapper = machine / "gradle-slot.sh"
            shutil.copy2(REPO / "tools/machine/gradle-slot.sh", wrapper)
            shutil.copy2(CLI, machine / "admission.py")
            began = time.monotonic()
            child = subprocess.Popen([str(wrapper), "./gradlew", "help"],
                                     cwd=REPO, env=self.env, stdout=log, stderr=log)
            try:
                # The shared machine queue can delay the slot. Keep our private
                # admission window open until the candidate actually reaches it.
                self.fixture.wait_for(lambda: self.fixture.row("heavy-waiter"), timeout=180)
                # The machine slot and worktree lock precede admission.
                with (REPO / ".gradle/agent-build.lock").open("rb") as stream:
                    with self.assertRaises(BlockingIOError):
                        fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
                time.sleep(1.5)
                self.assertIsNone(child.poll())
                window_end.touch()
                self.assertEqual(holder.wait(timeout=3), 0)
                self.assertEqual(child.wait(timeout=180), 0)
                elapsed = time.monotonic() - began
                self.assertGreater(elapsed, 1.3)
                print(f"candidate gradle-slot ./gradlew help: rc=0, {elapsed:.3f}s (includes 1.5s exclusive wait)", flush=True)
            finally:
                if child.poll() is None:
                    child.terminate()
                    child.wait(timeout=65)
        finally:
            log.seek(0)
            print(log.read(), flush=True)
            log.close()

    def test_real_stack_ready_releases_machine(self):
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            ui_port = listener.getsockname()[1]
        data_temp = tempfile.TemporaryDirectory(prefix="admission-stack-", dir=REPO / "tmp")
        self.addCleanup(data_temp.cleanup)
        models = self.root / "models"
        models.mkdir()
        env = {**self.env, "JUSTSEARCH_DEV_RUNNER_STATE_ROOT": str(self.root / "state"),
               "JUSTSEARCH_MODELS_DIR": str(models), "JUSTSEARCH_UI_SETTINGS_MODE": "IN_MEMORY",
               "JUSTSEARCH_GPU_ENABLED": "false",
               "JUSTSEARCH_HEAD_HEAP": "1g", "JAVA_OPTS": "-Djustsearch.eval.mode=true",
               "JS_ADMISSION_SESSION": "admission-real-stack"}
        output = self.root / "runner.log"
        with output.open("w+") as log:
            child = subprocess.Popen(["node", "scripts/dev/dev-runner.cjs", "start", "--json", "--skip-build",
                                      "--data-dir", data_temp.name, "--clean", "none",
                                      "--api-port", "0", "--ui-port", str(ui_port),
                                      "--session-id", "admission-real-stack"], cwd=REPO, env=env,
                                     stdin=subprocess.DEVNULL, stdout=log, stderr=log)
            try:
                row = self.fixture.wait_for(lambda: next((r for r in self.fixture.status() if
                    r["mode"] == "heavy" and r["purpose"] == "dev-runner engine start"), None), timeout=5)
                print("dev-runner heavy: " + json.dumps(row), flush=True)

                def ready():
                    self.assertIsNone(child.poll(), output.read_text())
                    for line in output.read_text().splitlines():
                        try:
                            row = json.loads(line)
                            if row.get("ok") and row.get("readiness", {}).get("ready_http"):
                                return row
                        except ValueError:
                            pass
                    return None

                result = self.fixture.wait_for(ready, timeout=90)
                admitted = self.fixture.status()
                self.assertFalse(any(r["mode"] == "heavy" for r in admitted))
                print("ready receipt: " + json.dumps(result), flush=True)
                print("admission after ready: " + json.dumps(admitted), flush=True)
                holder = self.fixture.start("exclusive", [PYTHON, "-c", "pass"], options=["--drain-timeout", ".5"])
                self.assertEqual(holder.wait(timeout=2), 0)
                self.assertIsNone(child.poll())
                print(f'idle stack {result["runId"]}: ready; exclusive rc=0 while supervisor remains alive', flush=True)
            finally:
                if child.poll() is None:
                    child.terminate()
                    try:
                        child.wait(timeout=45)
                    except subprocess.TimeoutExpired:
                        child.kill()
                        child.wait(timeout=5)
                        self.fail("own dev-runner did not complete shutdown")
                print("own dev-runner exit: " + str(child.returncode), flush=True)
                print(output.read_text(), flush=True)


if __name__ == "__main__":
    unittest.main()
