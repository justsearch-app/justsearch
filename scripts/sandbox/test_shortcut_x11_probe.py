#!/usr/bin/env python3
"""Xvfb startup regressions; discovered by CI's Public claims sandbox unittest step.

Uses real child processes, but no X server, GPU, or shared X11 lock/socket files.
Run: python3 -m unittest discover -s scripts/sandbox -p test_shortcut_x11_probe.py -v
"""

import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


PROBE = (Path(__file__).resolve().parents[2]
         / "modules/shell/src-tauri/tests/shortcut_x11_probe.py")
MESA = "/usr/share/glvnd/egl_vendor.d/50_mesa.json"
VENDOR = "__EGL_VENDOR_LIBRARY_FILENAMES"


@unittest.skipUnless(sys.platform == "linux", "Linux X11 harness")
class XvfbStartupTests(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location("shortcut_x11_probe", PROBE)
        self.probe = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.probe)
        temporary = self.enterContext(tempfile.TemporaryDirectory(prefix="fake-xvfb-"))
        self.root = Path(temporary)
        self.starts = self.root / "starts.jsonl"
        self.mesa_exists = True
        self.enterContext(patch.dict(os.environ, {
            "PATH": str(self.root) + os.pathsep + os.environ.get("PATH", ""),
            "FAKE_XVFB_ROOT": str(self.root),
            "TMPDIR": str(self.root),
        }))
        self.enterContext(patch.object(self.probe, "Path", side_effect=self.path))
        self.connection = self.enterContext(patch.object(
            self.probe, "connection", side_effect=RuntimeError("startup reached connection")))

    def path(self, value):
        # Redirect only X11 coordination paths; all other file operations are real.
        if str(value) == MESA:
            mesa = self.root / "mesa.json"
            if self.mesa_exists:
                mesa.touch()
            return mesa
        if str(value).startswith(("/tmp/.X11-unix/", "/tmp/.X")):
            return self.root / Path(value).name
        return Path(value)

    def fake(self, body):
        executable = self.root / "Xvfb"
        executable.write_text(f"#!{sys.executable}\n" + '''
import ctypes
import json
import os
from pathlib import Path
import resource
import signal
import sys
import time

# Avoid creating coredumps even on hosts with a piped core_pattern.
ctypes.CDLL(None).prctl(4, 0, 0, 0, 0)  # PR_SET_DUMPABLE
resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
root = Path(os.environ["FAKE_XVFB_ROOT"])
with (root / "starts.jsonl").open("a") as log:
    log.write(json.dumps({"display": sys.argv[1], "env": dict(os.environ)}) + "\\n")
''' + body)
        executable.chmod(0o700)

    def recorded(self):
        return [json.loads(line) for line in self.starts.read_text().splitlines()]

    def run_probe(self):
        with self.assertRaises(RuntimeError) as raised:
            self.probe.run("unused-native-test", "unused-executable")
        return str(raised.exception)

    def test_abort_stops_after_one_start_and_names_signal(self):
        self.fake('os.kill(os.getpid(), signal.SIGABRT)\n')
        error = self.run_probe()
        self.assertEqual(len(self.recorded()), 1, error)
        self.assertIn("SIGABRT", error)
        self.connection.assert_not_called()

    def test_signal_overrides_already_active_error(self):
        self.fake('sys.stderr.write("Server is already active\\n")\n'
                  'sys.stderr.flush()\nos.kill(os.getpid(), signal.SIGTERM)\n')
        error = self.run_probe()
        self.assertEqual(len(self.recorded()), 1, error)
        self.assertIn("SIGTERM", error)

    def test_mesa_vendor_is_selected_only_when_file_exists(self):
        self.fake('sys.exit(1)\n')
        for exists in (True, False):
            for inherited in (None, "/inherited/vendor.json"):
                with self.subTest(mesa_exists=exists, inherited=inherited):
                    self.mesa_exists = exists
                    (self.root / "mesa.json").unlink(missing_ok=True)
                    self.starts.unlink(missing_ok=True)
                    with patch.dict(os.environ):
                        os.environ.pop(VENDOR, None)
                        if inherited is not None:
                            os.environ[VENDOR] = inherited
                        self.run_probe()
                    starts = self.recorded()
                    self.assertEqual(starts[0]["env"].get(VENDOR), MESA if exists else inherited)

    def test_noncollision_exit_stops_and_preserves_error(self):
        for status in (0, 1):
            with self.subTest(status=status):
                self.starts.unlink(missing_ok=True)
                self.fake('sys.stderr.write("extension initialization failed\\n")\n'
                          f'sys.exit({status})\n')
                error = self.run_probe()
                self.assertEqual(len(self.recorded()), 1, error)
                self.assertIn(f"exited with status {status}", error)
                self.assertIn("extension initialization failed", error)

    def test_live_startup_timeout_stops_after_one_start(self):
        self.fake('time.sleep(30)\n')
        error = self.run_probe()
        self.assertEqual(len(self.recorded()), 1, error)
        self.assertIn("startup timed out", error)

    def test_existing_lock_or_socket_is_skipped_without_removal(self):
        for marker in (".X191-lock", "X191"):
            with self.subTest(marker=marker):
                occupied = self.root / marker
                occupied.write_text("other owner's resource")
                self.starts.unlink(missing_ok=True)
                self.fake('sys.exit(1)\n')
                self.run_probe()
                self.assertEqual(self.recorded()[0]["display"], ":192")
                self.assertEqual(occupied.read_text(), "other owner's resource")
                occupied.unlink()

    def test_collision_exit_retries_then_reaches_connection(self):
        for collision in ("active", "lock", "socket"):
            with self.subTest(collision=collision):
                self.starts.unlink(missing_ok=True)
                self.fake('''
number = sys.argv[1][1:]
if number == "191":
    if COLLISION == "active":
        sys.stderr.write("Server is already active for display 191\\n")
    else:
        (root / (".X191-lock" if COLLISION == "lock" else "X191")).touch()
    sys.exit(1)
(root / (".X" + number + "-lock")).write_text(str(os.getpid()))
(root / ("X" + number)).touch()
time.sleep(30)
'''.replace("COLLISION", repr(collision)))
                error = self.run_probe()
                self.assertEqual([start["display"] for start in self.recorded()], [":191", ":192"])
                self.assertEqual(error, "startup reached connection")
                for marker in (".X191-lock", "X191", ".X192-lock", "X192"):
                    (self.root / marker).unlink(missing_ok=True)


if __name__ == "__main__":
    unittest.main(verbosity=2)
