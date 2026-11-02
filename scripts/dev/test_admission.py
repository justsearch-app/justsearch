"""Linux admission acceptance checks. Cross-namespace companion: test-admission-cross-session.py.

Run: python3 -m unittest discover -s scripts/dev -p test_admission.py -v
ADMISSION_CLI may point at a before checkout; no production service is used.
"""
import importlib.util
import json
import os
from pathlib import Path
import signal
import shlex
import subprocess
import sys
import tempfile
import time
import unittest

CLI = Path(os.environ.get("ADMISSION_CLI", Path(__file__).with_name("admission.py"))).resolve()
PYTHON = sys.executable
SLEEP = [PYTHON, "-c", "import time; time.sleep(60)"]


def proc(pid):
    try:
        row = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()
        return {"pid": pid, "state": row[0], "pgid": int(row[2]), "birth": row[19]}
    except FileNotFoundError:
        return None


@unittest.skipUnless(sys.platform == "linux", "Linux flock/process groups")
class AdmissionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="js-admission-test-")
        self.root = Path(self.temp.name)
        self.env = {**os.environ, "JS_ADMISSION_DIR": str(self.root / "admission")}
        self.env.pop("JS_ADMISSION_TOKEN", None)
        self.children = []
        self.logs = []
        self.groups = []

    def tearDown(self):
        # Only spawn-recorded identities, including groups recorded by our child fixtures.
        for child, identity in reversed(self.children):
            if child.poll() is None and (current := proc(child.pid)) and current["birth"] == identity["birth"]:
                child.terminate()
        for child, _ in reversed(self.children):
            try:
                child.wait(timeout=3)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=3)
        for identity in self.groups:
            current = proc(identity["pid"])
            if current and current["birth"] == identity["birth"]:
                os.killpg(identity["pgid"], signal.SIGCONT)
                os.killpg(identity["pgid"], signal.SIGKILL)
        for log in self.logs:
            log.close()
        self.temp.cleanup()

    def wait_for(self, predicate, timeout=3):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            value = predicate()
            if value:
                return value
            time.sleep(.01)
        self.fail("fixture deadline exceeded")

    def status(self):
        result = subprocess.run([PYTHON, str(CLI), "status"], env=self.env,
                                capture_output=True, text=True, timeout=3)
        self.assertEqual(result.returncode, 0, result.stderr)
        return json.loads(result.stdout)

    def start(self, mode, command=None, purpose=None, options=()):
        if mode == "pausable":
            pause, ack, beat = (self.root / name for name in ("pause", "ack", "beat"))
            command = [PYTHON, "-c", f"""from pathlib import Path
import time
pause,ack,beat=map(Path,{[str(pause),str(ack),str(beat)]!r})
while True:
    if pause.exists(): ack.touch()
    else:
        ack.unlink(missing_ok=True)
        beat.write_text(str(time.monotonic()))
    time.sleep(.01)
"""]
            pause_code = f"from pathlib import Path; import time; Path({str(pause)!r}).touch()\nwhile not Path({str(ack)!r}).exists(): time.sleep(.01)"
            resume_code = f"from pathlib import Path; Path({str(pause)!r}).unlink(missing_ok=True)"
            options = [*options, "--pause-hook", shlex.join([PYTHON, "-c", pause_code]),
                       "--resume-hook", shlex.join([PYTHON, "-c", resume_code])]
        log = open(self.root / f"child-{len(self.children)}.log", "w+")
        self.logs.append(log)
        child = subprocess.Popen([PYTHON, str(CLI), mode, "--purpose", purpose or mode,
                                  *options, "--", *(command or SLEEP)],
                                 env={**self.env, "JS_ADMISSION_SESSION": "test session"}, stdout=log, stderr=log)
        identity = proc(child.pid)
        self.children.append((child, identity))
        return child

    def row(self, mode, pid=None):
        return next((r for r in self.status() if r["mode"] == mode and (pid is None or r.get("holder_pid", r["pid"]) == pid)), None)

    def assert_unblocked(self):
        child = self.start("heavy", [PYTHON, "-c", "pass"])
        self.assertEqual(child.wait(timeout=2), 0)

    def test_shared_holders_coexist_and_new_work_waits_for_exclusive(self):
        a, b = self.start("heavy", purpose="build a"), self.start("heavy", purpose="build b")
        self.wait_for(lambda: len([r for r in self.status() if r["mode"] == "heavy"]) == 2)
        exclusive = self.start("exclusive", options=["--drain-timeout", "2"])
        self.wait_for(lambda: self.row("exclusive-draining"))
        marker = self.root / "entered"
        waiter = self.start("heavy", [PYTHON, "-c", f"from pathlib import Path; Path({str(marker)!r}).touch()"])
        time.sleep(.1)
        self.assertFalse(marker.exists())
        a.terminate(); b.terminate()
        self.assertEqual(a.wait(timeout=2), 143)
        self.assertEqual(b.wait(timeout=2), 143)
        self.wait_for(lambda: self.row("exclusive"))
        self.assertFalse(marker.exists())
        exclusive.terminate()
        self.assertEqual(exclusive.wait(timeout=2), 143)
        self.assertEqual(waiter.wait(timeout=2), 0)
        self.assertTrue(marker.exists())

    def test_drain_refusal_lists_holder_and_reopens_gate(self):
        heavy = self.start("heavy", purpose="ongoing build")
        row = self.wait_for(lambda: self.row("heavy"))
        exclusive = self.start("exclusive", options=["--drain-timeout", ".15"])
        self.assertEqual(exclusive.wait(timeout=2), 75)
        output = self.logs[-1]; output.seek(0)
        diagnostic = output.read()
        for text in ("ongoing build", str(heavy.pid), row["start"]):
            self.assertIn(text, diagnostic)
        self.assert_unblocked()

    def test_exclusive_releases_on_success_error_and_signals(self):
        for ending in (0, 17, signal.SIGINT, signal.SIGTERM, signal.SIGKILL):
            with self.subTest(ending=ending):
                marker = self.root / "holder-running"
                command = [PYTHON, "-c", f"from pathlib import Path; import time; Path({str(marker)!r}).touch(); time.sleep(.1); raise SystemExit({ending})"] if type(ending) is int else SLEEP
                holder = self.start("exclusive", command)
                if type(ending) is int:
                    self.assertEqual(holder.wait(timeout=2), ending)
                else:
                    self.wait_for(lambda: self.row("exclusive", holder.pid))
                    holder.send_signal(ending)
                    self.assertEqual(holder.wait(timeout=2), -ending if ending == signal.SIGKILL else 128 + ending)
                self.assert_unblocked()

    def test_deadline_cleans_term_ignoring_group_before_waiter_enters(self):
        marker = self.root / "group.json"
        script = ("import os,signal,time,json; from pathlib import Path; "
                  "signal.signal(signal.SIGTERM,signal.SIG_IGN); child=os.fork(); "
                  f"Path({str(marker)!r}).write_text(json.dumps([os.getpid(),child])) if child else None; "
                  "time.sleep(60)")
        start = time.monotonic()
        holder = self.start("exclusive", [PYTHON, "-c", script], options=["--timeout", "1"])
        self.wait_for(marker.exists)
        pids = self.wait_for(lambda: json.loads(marker.read_text()) if marker.stat().st_size else None)
        leader = proc(pids[0]); self.groups.append(leader)
        check = self.root / "waiter.json"
        command = [PYTHON, "-c", "import json; from pathlib import Path; "
                   f"Path({str(check)!r}).write_text(json.dumps([Path('/proc/'+str(p)+'/stat').read_text().rsplit(')',1)[1].split()[0] if Path('/proc/'+str(p)).exists() else None for p in {pids!r}]))"]
        waiter = self.start("heavy", command)
        self.assertEqual(holder.wait(timeout=3), 124)
        self.assertEqual(waiter.wait(timeout=2), 0)
        self.assertTrue(all(state is None for state in json.loads(check.read_text())))
        self.assertLess(time.monotonic() - start, 1.5)

    def test_nested_heavy_does_not_deadlock_while_exclusive_drains(self):
        ready, go, done = (self.root / x for x in ("ready", "go", "done"))
        script = f"""import subprocess,time; from pathlib import Path
Path({str(ready)!r}).touch()
while not Path({str(go)!r}).exists(): time.sleep(.01)
subprocess.run({[PYTHON, str(CLI), 'heavy', '--purpose', 'nested', '--', PYTHON, '-c', 'pass']!r}, check=True)
Path({str(done)!r}).touch()
"""
        holder = self.start("heavy", [PYTHON, "-c", script])
        self.wait_for(ready.exists)
        exclusive = self.start("exclusive", [PYTHON, "-c", "pass"], options=["--drain-timeout", "1"])
        self.wait_for(lambda: self.row("exclusive-draining"))
        go.touch()
        self.assertEqual(holder.wait(timeout=2), 0)
        self.assertEqual(exclusive.wait(timeout=2), 0)
        self.assertTrue(done.exists())

    def test_pausable_resumes_on_all_exits_including_holder_sigkill(self):
        background = self.start("pausable", purpose="index rebuild")
        self.wait_for(lambda: self.row("pausable"))
        group = self.wait_for(lambda: self.row("pausable").get("group"))
        self.groups.append(proc(group["pid"]))
        for ending in (0, 17, signal.SIGINT, signal.SIGTERM, signal.SIGKILL, "deadline", "drain"):
            with self.subTest(ending=ending):
                blocker = None
                if ending == "drain":
                    blocker = self.start("heavy")
                    self.wait_for(lambda: self.row("heavy", blocker.pid))
                end_file = self.root / f"end-{ending}"
                command = [PYTHON, "-c", f"import time; from pathlib import Path\nwhile not Path({str(end_file)!r}).exists(): time.sleep(.01)\nraise SystemExit({ending})"] if type(ending) is int else SLEEP
                holder = self.start("exclusive", command, options=["--timeout", "1", "--drain-timeout", ".3"])
                self.wait_for(lambda: self.row("pausable").get("state") == "paused")
                beat = self.root / "beat"
                frozen = beat.read_text()
                time.sleep(.1)
                self.assertEqual(beat.read_text(), frozen)
                waiter = self.start("heavy", [PYTHON, "-c", "pass"])
                resumed_at = time.monotonic()
                if type(ending) is int:
                    end_file.touch()
                elif isinstance(ending, signal.Signals):
                    holder.send_signal(ending)
                self.wait_for(lambda: beat.read_text() != frozen, timeout=2)
                self.assertEqual(waiter.wait(timeout=2), 0)
                self.assertLess(time.monotonic() - resumed_at, 2)
                holder.wait(timeout=2)
                if blocker:
                    blocker.terminate(); blocker.wait(timeout=2)
        background.terminate()
        self.assertEqual(background.wait(timeout=2), 143)

    def test_status_is_diagnostic_only_and_prunes_dead_registrants(self):
        background = self.start("pausable")
        row = self.wait_for(lambda: self.row("pausable"))
        group = self.wait_for(lambda: self.row("pausable").get("group"))
        self.groups.append(proc(group["pid"]))
        background.kill(); background.wait(timeout=2)
        self.assertFalse(any(r["pid"] == row["pid"] for r in self.status()))
        self.assertEqual(list(Path(self.env["JS_ADMISSION_DIR"]).glob("*.json")), [])
        # A forged diagnostic entry cannot keep a kernel-unlocked machine busy.
        fake = {**row, "pid": os.getpid(), "birth": proc(os.getpid())["birth"], "mode": "heavy"}
        (Path(self.env["JS_ADMISSION_DIR"]) / "forged.json").write_text(json.dumps(fake))
        holder = self.start("exclusive", [PYTHON, "-c", "pass"])
        self.assertEqual(holder.wait(timeout=2), 0)

    def test_read_only_lock_files_and_exclusive_waiter_status(self):
        lock_dir = Path(self.env["JS_ADMISSION_DIR"]); lock_dir.mkdir()
        for name in ("gate", "work"):
            (lock_dir / f"{name}.lock").touch(mode=0o444)
        holder = self.start("exclusive")
        self.wait_for(lambda: self.row("exclusive"))
        waiter = self.start("exclusive", purpose="waiting scorer")
        row = self.wait_for(lambda: self.row("exclusive-waiter", waiter.pid))
        self.assertEqual(row["purpose"], "waiting scorer")
        self.assertEqual(row["session"], "test session")
        self.assertTrue(row["start"])
        fds = Path(f'/proc/{self.row("exclusive")["pid"]}/fd')
        lock_fds = [p for p in fds.iterdir() if str(p.resolve()).endswith(".lock")]
        self.assertEqual(len(lock_fds), 2)
        for fd in lock_fds:
            flags = Path(f"{fds.parent}/fdinfo/{fd.name}").read_text().split("flags:\t")[1].splitlines()[0]
            self.assertEqual(int(flags, 8) & os.O_ACCMODE, os.O_RDONLY)
        waiter.terminate(); waiter.wait(timeout=2)
        holder.terminate(); holder.wait(timeout=2)


if __name__ == "__main__":
    unittest.main()
