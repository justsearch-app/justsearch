"""Real-process checks for the machine wrapper, with private slots and admission.

SLOT_WRAPPER selects a before/installed wrapper for comparisons. No Gradle build is
needed here; test-machine-admission-integration.py covers real Gradle launches.
"""
import fcntl
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import unittest

REPO = Path(__file__).resolve().parents[2]
CLI = REPO / "scripts/dev/admission.py"
WRAPPER = Path(os.environ.get("SLOT_WRAPPER", REPO / "tools/machine/gradle-slot.sh"))


class SlotFixture(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="gradle-slot-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        machine = self.root / "machine"
        machine.mkdir()
        self.wrapper = machine / "gradle-slot.sh"
        shutil.copy2(WRAPPER, self.wrapper)
        shutil.copy2(CLI, machine / "admission.py")
        self.env = {**os.environ, "JS_ADMISSION_DIR": str(self.root / "admission"),
                    "JS_GRADLE_SLOT_DIR": str(self.root / "slots"),
                    "JS_GRADLE_SLOTS": "2", "JS_GRADLE_MAX_WORKERS": "6"}
        for name in ("JS_ADMISSION_TOKEN", "JS_GRADLE_SLOT_HOLDER", "JS_GRADLE_ADMISSION_ENTERED"):
            self.env.pop(name, None)
        self.children = []
        self.addCleanup(self.stop_children)

    def stop_children(self):
        for child in reversed(self.children):
            if child.poll() is None:
                child.terminate()
                try:
                    child.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait(timeout=3)

    def checkout(self, name):
        path = self.root / name
        path.mkdir()
        subprocess.run(["git", "init", "--quiet", str(path)], check=True)
        # Let the before wrapper find its checkout-relative module so ordering and
        # timeout regressions fail for their own reasons, rather than missing files.
        scripts = path / "scripts/dev"
        scripts.mkdir(parents=True)
        shutil.copy2(CLI, scripts / "admission.py")
        return path

    def start(self, command, cwd=None, env=None):
        log = (self.root / f"child-{len(self.children)}.log").open("w+")
        self.addCleanup(log.close)
        child = subprocess.Popen(command, cwd=cwd or self.root, env=env or self.env,
                                 stdout=log, stderr=log)
        child.log = log
        self.children.append(child)
        return child

    def output(self, child):
        child.log.seek(0)
        return child.log.read()

    def wait_for(self, predicate, timeout=4):
        until = time.monotonic() + timeout
        while time.monotonic() < until:
            if value := predicate():
                return value
            time.sleep(.01)
        self.fail("fixture deadline exceeded")

    def status(self):
        result = subprocess.run([sys.executable, str(CLI), "status"], env=self.env,
                                capture_output=True, text=True, timeout=3, check=True)
        return json.loads(result.stdout)

    def exclusive(self):
        holder = self.start([sys.executable, str(CLI), "exclusive", "--purpose", "scoring",
                             "--drain-timeout", ".25", "--", sys.executable, "-c",
                             "import time; time.sleep(30)"])
        self.wait_for(lambda: any(r["mode"] == "exclusive" for r in self.status()))
        return holder

    def work(self, name, cwd=None, options=(), body="", env=None):
        entered, release = self.root / (name + ".entered"), self.root / (name + ".release")
        code = ("from pathlib import Path; import time; "
                f"Path({str(entered)!r}).touch()\n"
                f"while not Path({str(release)!r}).exists(): time.sleep(.01)\n" + body)
        child = self.start([str(self.wrapper), *options, sys.executable, "-c", code], cwd, env)
        return child, entered, release


class SlotPreservation(SlotFixture):
    def test_location_independent_and_worker_cap(self):
        for cwd in (self.root, self.checkout("checkout")):
            # Neither a checkout module nor the caller's current directory is required.
            shutil.rmtree(cwd / "scripts", ignore_errors=True)
            gradlew = cwd / "gradlew"
            gradlew.write_text('#!/bin/sh\nprintf "%s\\n" "$@"\n')
            gradlew.chmod(0o755)
            child = self.start([str(self.wrapper), "./gradlew", "help"], cwd)
            self.assertEqual(child.wait(timeout=3), 0, self.output(child))
            self.assertIn("--max-workers=6\nhelp", self.output(child))

    def test_two_slots_fifo_and_release(self):
        jobs = [self.work(str(i), self.checkout(str(i))) for i in range(2)]
        self.wait_for(jobs[0][1].exists)
        self.wait_for(jobs[1][1].exists)
        jobs.append(self.work("2", self.checkout("2")))
        self.wait_for(lambda: len(list((self.root / "slots/queue").glob("[0-9]*"))) == 1)
        jobs.append(self.work("3", self.checkout("3")))
        self.wait_for(lambda: len(list((self.root / "slots/queue").glob("[0-9]*"))) == 2)
        self.assertFalse(jobs[2][1].exists())
        self.assertFalse(jobs[3][1].exists())
        order = jobs[2:]
        jobs[0][2].touch()
        self.assertEqual(jobs[0][0].wait(timeout=3), 0)
        self.wait_for(order[0][1].exists)
        self.assertFalse(order[1][1].exists())
        order[0][2].touch()
        self.assertEqual(order[0][0].wait(timeout=3), 0)
        self.wait_for(order[1][1].exists)
        for child, _, release in jobs:
            release.touch()
            self.assertEqual(child.wait(timeout=3), 0, self.output(child))

    def test_usage_lock_errors_and_command_exit(self):
        cwd = self.checkout("checkout")
        cases = [(["-w", "bad", "true"], self.env, 64),
                 (["-w", "0", "true"], self.env, 0),
                 (["./gradlew", "-p", "elsewhere"], self.env, 64),
                 (["true"], {**self.env, "JS_GRADLE_SLOTS": "0"}, 64),
                 (["true"], {**self.env, "JS_GRADLE_SLOT_HOLDER": str(os.getpid())}, 64),
                 (["true"], {**self.env, "JS_GRADLE_SLOT_DIR": "/dev/null/no-slots"}, 73),
                 ([sys.executable, "-c", "raise SystemExit(19)"], self.env, 19)]
        for args, env, expected in cases:
            with self.subTest(args=args, expected=expected):
                child = self.start([str(self.wrapper), *args], cwd, env)
                self.assertEqual(child.wait(timeout=3), expected, self.output(child))

    def test_worktree_and_slot_wait_timeout(self):
        cwd = self.checkout("checkout")
        holder, entered, release = self.work("holder", cwd)
        self.wait_for(entered.exists)
        child = self.start([str(self.wrapper), "-w", "1", "true"], cwd)
        self.assertEqual(child.wait(timeout=3), 75, self.output(child))
        other, entered2, _ = self.work("other", self.checkout("other"))
        self.wait_for(entered2.exists)
        child = self.start([str(self.wrapper), "-w", "1", "true"], self.checkout("queued"))
        self.assertEqual(child.wait(timeout=3), 75, self.output(child))
        release.touch()
        self.assertEqual(holder.wait(timeout=3), 0)
        other.terminate()
        self.assertEqual(other.wait(timeout=4), 143)

    def test_sigterm_stops_own_command_and_releases(self):
        cwd = self.checkout("checkout")
        identity = self.root / "pid"
        child = self.start([str(self.wrapper), sys.executable, "-c",
                            "import os,time; from pathlib import Path; "
                            f"Path({str(identity)!r}).write_text(str(os.getpid())); time.sleep(30)"], cwd)
        self.wait_for(lambda: identity.exists() and identity.stat().st_size)
        pid = int(identity.read_text())
        child.terminate()
        self.assertEqual(child.wait(timeout=4), 143, self.output(child))
        self.assertFalse(Path(f"/proc/{pid}").exists())
        next_child = self.start([str(self.wrapper), "-w", "1", "true"], cwd)
        self.assertEqual(next_child.wait(timeout=3), 0, self.output(next_child))


class SlotAdmission(SlotFixture):
    def test_missing_sibling_warns_once_and_runs(self):
        self.wrapper.with_name("admission.py").unlink()
        child = self.start([str(self.wrapper), "true"])
        self.assertEqual(child.wait(timeout=3), 0, self.output(child))
        warnings = [line for line in self.output(child).splitlines() if "warning:" in line]
        self.assertEqual(len(warnings), 1, self.output(child))
        self.assertIn(str(self.wrapper.with_name("admission.py")), warnings[0])

    def test_queued_calls_do_not_block_exclusive(self):
        slots = self.root / "slots"
        slots.mkdir()
        with (slots / "gradle.lock").open("w") as a, (slots / "gradle.lock.2").open("w") as b:
            fcntl.flock(a, fcntl.LOCK_EX)
            fcntl.flock(b, fcntl.LOCK_EX)
            jobs = [self.work(str(i), self.checkout(str(i))) for i in range(2)]
            self.wait_for(lambda: len(list((slots / "queue").glob("[0-9]*"))) == 2)
            scorer = self.start([sys.executable, str(CLI), "exclusive", "--purpose", "scoring",
                                 "--drain-timeout", ".25", "--", "true"])
            self.assertEqual(scorer.wait(timeout=3), 0, self.output(scorer))
            self.assertFalse(any(r["mode"].startswith("heavy") for r in self.status()))
        for child, entered, release in jobs:
            self.wait_for(entered.exists)
            release.touch()
            self.assertEqual(child.wait(timeout=3), 0, self.output(child))

    def test_window_waiter_holds_slot_and_worktree_then_runs(self):
        holder = self.exclusive()
        cwd = self.checkout("checkout")
        child, entered, release = self.work("waiter", cwd)
        self.wait_for(lambda: any(r["mode"] == "heavy-waiter" for r in self.status()))
        for path in (cwd / ".gradle/agent-build.lock", self.root / "slots/gradle.lock"):
            with path.open("rb") as stream:
                with self.assertRaises(BlockingIOError):
                    fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        self.assertFalse(entered.exists())
        holder.terminate()
        self.assertEqual(holder.wait(timeout=3), 143)
        self.wait_for(entered.exists, timeout=2)
        release.touch()
        self.assertEqual(child.wait(timeout=3), 0, self.output(child))

    def test_total_wait_includes_admission_and_does_not_limit_work(self):
        holder = self.exclusive()
        # Spend part of the same budget waiting for the worktree first.
        cwd = self.checkout("checkout")
        (cwd / ".gradle").mkdir()
        began = time.monotonic()
        with (cwd / ".gradle/agent-build.lock").open("w") as worktree:
            fcntl.flock(worktree, fcntl.LOCK_EX)
            child = self.start([str(self.wrapper), "-w", "5", "true"], cwd)
            time.sleep(1.3)
        self.assertEqual(child.wait(timeout=7), 75, self.output(child))
        elapsed = time.monotonic() - began
        self.assertGreater(elapsed, 3.8)
        self.assertLess(elapsed, 5.5)
        print(f"-w 5 during exclusive: rc=75 in {elapsed:.3f}s", flush=True)
        holder.terminate()
        self.assertEqual(holder.wait(timeout=3), 143)
        self.assertEqual(self.status(), [])
        # Once admitted, -w bounds waiting only, not the command's runtime.
        child = self.start([str(self.wrapper), "-w", "1", sys.executable, "-c",
                            "import time; time.sleep(1.2)"], self.checkout("long-work"))
        self.assertEqual(child.wait(timeout=4), 0, self.output(child))


if __name__ == "__main__":
    unittest.main()
