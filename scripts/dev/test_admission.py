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
        self.descendants = []

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
        for identity in self.descendants:
            current = proc(identity["pid"])
            if current and current["birth"] == identity["birth"]:
                os.kill(identity["pid"], signal.SIGKILL)
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

    def start(self, mode, command=None, purpose=None, options=(), hooks=None):
        if mode == "pausable" and command is None:
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
            pause_hook, resume_hook = hooks or ([PYTHON, "-c", pause_code], [PYTHON, "-c", resume_code])
            options = [*options, "--pause-hook", shlex.join(pause_hook), "--resume-hook", shlex.join(resume_hook)]
        elif mode == "pausable":
            options = [*options, "--pause-hook", shlex.join(hooks[0]), "--resume-hook", shlex.join(hooks[1])]
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

    def output(self, child):
        log = self.logs[[c for c, _ in self.children].index(child)]
        log.seek(0)
        return log.read()

    def job(self, name):
        """A command that reports its own PID, then sleeps; the caller records its identity."""
        path = self.root / f"{name}.pid"
        return path, [PYTHON, "-c", "import os,time; from pathlib import Path; "
                      f"Path({str(path)!r}).write_text(str(os.getpid())); time.sleep(60)"]

    def job_identity(self, path):
        identity = proc(int(self.wait_for(lambda: path.exists() and path.read_text())))
        self.descendants.append(identity)
        return identity

    @staticmethod
    def gone(identity):
        current = proc(identity["pid"])
        return not current or current["birth"] != identity["birth"] or current["state"] == "Z"

    def kill_recorded(self, identity):
        if not self.gone(identity):
            os.kill(identity["pid"], signal.SIGKILL)
        self.wait_for(lambda: self.gone(identity))

    def test_shared_holders_coexist_and_new_work_waits_for_exclusive(self):
        (a_pid, a_job), (b_pid, b_job) = self.job("build-a"), self.job("build-b")
        a, b = self.start("heavy", a_job, purpose="build a"), self.start("heavy", b_job, purpose="build b")
        jobs = [self.job_identity(a_pid), self.job_identity(b_pid)]
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
        self.assertTrue(all(self.gone(job) for job in jobs), "heavy cleanup left its job running")
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

    def test_exclusive_cleans_nested_heavy_and_setsid_descendants_on_every_exit(self):
        for ending in ("deadline", 0, 17, signal.SIGINT, signal.SIGTERM, signal.SIGKILL):
            with self.subTest(ending=ending):
                marker = self.root / f"descendants-{ending}.json"
                finish = self.root / f"finish-{ending}"
                worker = f"""import os,signal,time,json
from pathlib import Path
signal.signal(signal.SIGTERM, signal.SIG_IGN)
wrapper = os.getppid()
grandchild = os.fork()
if grandchild == 0:
    os.setsid()
    Path({str(marker)!r}).write_text(json.dumps([wrapper, os.getppid(), os.getpid()]))
while True: time.sleep(.01)
"""
                nested_command = [PYTHON, str(CLI), "heavy", "--purpose", "nested heavy", "--",
                                  PYTHON, "-c", worker]
                command = [PYTHON, "-c", f"""import subprocess,time
from pathlib import Path
child = subprocess.Popen({nested_command!r})
while not Path({str(finish)!r}).exists(): time.sleep(.01)
raise SystemExit({ending if type(ending) is int else 0})
"""]
                holder = self.start("exclusive", command, options=["--timeout", "1.2"])
                self.wait_for(lambda: marker.exists() and marker.stat().st_size)
                pids = json.loads(marker.read_text())
                identities = [proc(pid) for pid in pids]
                self.descendants.extend(identities)
                self.assertNotEqual(identities[1]["pgid"], identities[2]["pgid"])
                check = self.root / f"released-{ending}.json"
                waiter = self.start("heavy", [PYTHON, "-c",
                    "import json; from pathlib import Path; "
                    f"Path({str(check)!r}).write_text(json.dumps([Path('/proc/'+str(p)).exists() for p in {pids!r}]))"])
                if type(ending) is int:
                    finish.touch()
                elif isinstance(ending, signal.Signals):
                    holder.send_signal(ending)
                expected = (124 if ending == "deadline" else ending if type(ending) is int else
                            -9 if ending == signal.SIGKILL else 128 + ending)
                self.assertEqual(holder.wait(timeout=3), expected)
                self.assertEqual(waiter.wait(timeout=2), 0)
                self.assertEqual(json.loads(check.read_text()), [False] * len(pids),
                                 "admission released with escaped descendants still present")

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
                holder = self.start("exclusive", command, options=["--timeout", "1", "--drain-timeout", "1"])
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
        # The surviving job keeps its record (and work lock) locked: visible, not pruned.
        self.assertTrue(any(r["pid"] == row["pid"] for r in self.status()))
        self.kill_recorded(proc(group["pid"]))
        self.assertFalse(any(r["pid"] == row["pid"] for r in self.status()))
        self.assertEqual(list(Path(self.env["JS_ADMISSION_DIR"]).glob("*.json")), [])
        unpublished = Path(self.env["JS_ADMISSION_DIR"]) / "dead-creator.record"
        unpublished.write_text("{")
        self.status()
        self.assertFalse(unpublished.exists(), "an unlocked unpublished record is pruned")
        # A forged diagnostic entry cannot keep a kernel-unlocked machine busy.
        fake = {**row, "pid": os.getpid(), "birth": proc(os.getpid())["birth"], "mode": "heavy"}
        (Path(self.env["JS_ADMISSION_DIR"]) / "forged.json").write_text(json.dumps(fake))
        holder = self.start("exclusive", [PYTHON, "-c", "pass"])
        self.assertEqual(holder.wait(timeout=2), 0)

    def test_sigkilled_heavy_tool_keeps_admission_while_its_job_runs(self):
        pid_file, command = self.job("orphan")
        tool = self.start("heavy", command, purpose="orphaned build")
        job = self.job_identity(pid_file)
        self.wait_for(lambda: self.row("heavy"))
        tool.kill()
        self.assertEqual(tool.wait(timeout=2), -9)
        self.assertFalse(self.gone(job))
        refused = self.start("exclusive", [PYTHON, "-c", "pass"], options=["--drain-timeout", ".3"])
        self.assertEqual(refused.wait(timeout=3), 75)
        self.assertIn("orphaned build", self.output(refused))
        self.kill_recorded(job)
        holder = self.start("exclusive", [PYTHON, "-c", "pass"], options=["--drain-timeout", "1"])
        self.assertEqual(holder.wait(timeout=3), 0)

    def test_sigkilled_pausable_tool_keeps_admission_while_its_job_works(self):
        tool = self.start("pausable", purpose="orphaned index rebuild")
        group = self.wait_for(lambda: (self.row("pausable") or {}).get("group"))
        job = proc(group["pid"]); self.groups.append(job)
        tool.kill()
        self.assertEqual(tool.wait(timeout=2), -9)
        refused = self.start("exclusive", [PYTHON, "-c", "pass"], options=["--drain-timeout", ".3"])
        self.assertEqual(refused.wait(timeout=3), 75)
        self.assertIn("orphaned index rebuild", self.output(refused))
        self.kill_recorded(job)
        holder = self.start("exclusive", [PYTHON, "-c", "pass"], options=["--drain-timeout", "1"])
        self.assertEqual(holder.wait(timeout=3), 0)

    def test_heavy_work_started_by_a_paused_job_waits_for_the_window(self):
        go, entered = self.root / "go", self.root / "entered"
        command = [PYTHON, "-c", f"""import subprocess,time; from pathlib import Path
while not Path({str(go)!r}).exists(): time.sleep(.01)
subprocess.run({[PYTHON, str(CLI), 'heavy', '--purpose', 'nested in paused job', '--',
                 PYTHON, '-c', f'from pathlib import Path; Path({str(entered)!r}).touch()']!r}, check=True)
time.sleep(60)
"""]
        noop = [PYTHON, "-c", "pass"]
        background = self.start("pausable", command, purpose="controller", hooks=(noop, noop))
        group = self.wait_for(lambda: (self.row("pausable") or {}).get("group"))
        self.groups.append(proc(group["pid"]))
        holder = self.start("exclusive", options=["--drain-timeout", "2"])
        self.wait_for(lambda: self.row("exclusive") and self.row("pausable")["state"] == "paused")
        go.touch()
        waiting = self.wait_for(lambda: next((r for r in self.status() if r["purpose"] == "nested in paused job"), None))
        self.assertEqual(waiting["mode"], "heavy-waiter")
        time.sleep(.3)
        self.assertFalse(entered.exists(), "heavy work of a paused job ran inside the exclusive window")
        holder.terminate()
        self.assertEqual(holder.wait(timeout=2), 143)
        self.wait_for(entered.exists, timeout=2)
        background.terminate(); background.wait(timeout=3)

    def test_budget_caps_are_enforced(self):
        cases = [("exclusive", "--drain-timeout", "121"), ("exclusive", "--timeout", "601"),
                 ("exclusive", "--gate-timeout", "721"), ("heavy", "--gate-timeout", "721"),
                 ("pausable", "--hook-timeout", "121")]
        for mode, option, value in cases:
            with self.subTest(option=option, mode=mode):
                hooks = ["--pause-hook", "true", "--resume-hook", "true"] if mode == "pausable" else []
                result = subprocess.run([PYTHON, str(CLI), mode, "--purpose", "cap", option, value, *hooks,
                                         "--", PYTHON, "-c", "pass"], env=self.env, capture_output=True,
                                        text=True, timeout=5)
                self.assertEqual(result.returncode, 2, result.stderr)
                self.assertIn("at most", result.stderr)

    def test_gate_waits_have_deadlines(self):
        holder = self.start("exclusive", purpose="current scoring")
        self.wait_for(lambda: self.row("exclusive"))
        for mode in ("exclusive", "heavy"):
            with self.subTest(mode=mode):
                started = time.monotonic()
                waiter = self.start(mode, [PYTHON, "-c", "pass"], options=["--gate-timeout", ".3"])
                self.assertEqual(waiter.wait(timeout=3), 75)
                self.assertLess(time.monotonic() - started, 2)
                self.assertIn("current scoring", self.output(waiter))
        holder.terminate()
        self.assertEqual(holder.wait(timeout=2), 143)
        self.assert_unblocked()

    def test_hanging_hooks_fail_without_holding_the_gate(self):
        hang = [PYTHON, "-c", "import time; time.sleep(60)"]
        for phase in ("pause", "resume"):
            with self.subTest(phase=phase):
                for name in ("pause", "ack"):
                    (self.root / name).unlink(missing_ok=True)
                pause_code = (f"from pathlib import Path; import time; Path({str(self.root / 'pause')!r}).touch()\n"
                              f"while not Path({str(self.root / 'ack')!r}).exists(): time.sleep(.01)")
                hooks = (hang, [PYTHON, "-c", "pass"]) if phase == "pause" else ([PYTHON, "-c", pause_code], hang)
                background = self.start("pausable", purpose=f"{phase} hangs", hooks=hooks,
                                        options=["--hook-timeout", ".5"])
                group = self.wait_for(lambda: (self.row("pausable") or {}).get("group"))
                job = proc(group["pid"]); self.groups.append(job)
                first = self.start("exclusive", [PYTHON, "-c", "pass"], options=["--drain-timeout", "3"])
                self.assertEqual(first.wait(timeout=5), 0)
                # The resume hook runs after the gate reopened: the next window is not held back.
                second = self.start("exclusive", [PYTHON, "-c", "pass"], options=["--drain-timeout", "3"])
                self.assertEqual(second.wait(timeout=5), 0)
                self.assertEqual(background.wait(timeout=3), 73)
                self.assertIn("timed out", self.output(background))
                self.assertTrue(self.gone(job), "a failed hook ends the job")

    def test_waiting_pausable_is_not_listed_as_holder(self):
        heavy = self.start("heavy", purpose="ongoing build")
        self.wait_for(lambda: self.row("heavy"))
        exclusive = self.start("exclusive", [PYTHON, "-c", "pass"], options=["--drain-timeout", "1"])
        self.wait_for(lambda: self.row("exclusive-draining"))
        background = self.start("pausable", purpose="queued index rebuild")
        self.wait_for(lambda: (self.row("pausable") or {}).get("state") == "waiting")
        self.assertEqual(exclusive.wait(timeout=3), 75)
        diagnostic = self.output(exclusive)
        self.assertIn("ongoing build", diagnostic)
        self.assertNotIn("queued index rebuild", diagnostic)
        group = self.wait_for(lambda: (self.row("pausable") or {}).get("group"))
        self.groups.append(proc(group["pid"]))
        background.terminate(); background.wait(timeout=3)
        heavy.terminate(); heavy.wait(timeout=3)

    def test_python_heavy_uses_the_directory_of_the_passed_env(self):
        spec = importlib.util.spec_from_file_location("admission_under_test", CLI)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        passed = self.root / "passed-admission"
        saved = os.environ.get("JS_ADMISSION_DIR")
        os.environ["JS_ADMISSION_DIR"] = self.env["JS_ADMISSION_DIR"]
        try:
            with module.heavy("python caller", {**self.env, "JS_ADMISSION_DIR": str(passed)}) as admitted:
                self.assertTrue(admitted["JS_ADMISSION_TOKEN"])
                self.assertEqual(self.status(), [])
                rows = json.loads(subprocess.run([PYTHON, str(CLI), "status"], capture_output=True, text=True,
                                                 env={**self.env, "JS_ADMISSION_DIR": str(passed)}).stdout)
                self.assertEqual([(r["mode"], r["purpose"]) for r in rows], [("heavy", "python caller")])
                self.assertTrue(module.nested(admitted))
        finally:
            if saved is None:
                os.environ.pop("JS_ADMISSION_DIR", None)
            else:
                os.environ["JS_ADMISSION_DIR"] = saved

    @unittest.skipIf(os.geteuid() == 0, "root bypasses directory permissions")
    def test_unavailable_admission_fails_open_with_one_warning(self):
        blocked = self.root / "blocked"; blocked.mkdir()
        view = self.root / "view"; view.mkdir()
        for name in ("gate", "work"):
            (view / f"{name}.lock").touch(mode=0o444)
        for directory in (blocked, view):
            directory.chmod(0o555)
        try:
            self.check_fail_open(blocked, view)
        finally:
            for directory in (blocked, view):
                directory.chmod(0o755)

    def check_fail_open(self, blocked, view):
        for label, directory in (("not creatable", blocked / "admission"), ("read-only view", view)):
            with self.subTest(case=label):
                result = subprocess.run([PYTHON, str(CLI), "heavy", "--purpose", "Gradle", "--",
                                         PYTHON, "-c", "print('ran')"], capture_output=True, text=True, timeout=5,
                                        env={**self.env, "JS_ADMISSION_DIR": str(directory)})
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout, "ran\n")
                self.assertEqual(len(result.stderr.splitlines()), 1, result.stderr)
                self.assertIn("without admission", result.stderr)

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
