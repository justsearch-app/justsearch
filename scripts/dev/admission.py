#!/usr/bin/env python3
"""Cooperative Linux machine admission (Python 3.11+, standard library only)."""
from __future__ import annotations

import argparse
import contextlib
import ctypes
import datetime
import fcntl
import json
import math
import os
from pathlib import Path
import select
import shlex
import signal
import subprocess
import sys
import time
import uuid

TOKEN = "JS_ADMISSION_TOKEN"
DEADLINE = 124
REFUSED = 75
GRACE = 0.2
POLL = 0.05


def directory():
    return Path(os.environ.get("JS_ADMISSION_DIR", "/tmp/js-linux-port/admission"))


def lock_file(name):
    directory().mkdir(parents=True, exist_ok=True)
    # Sandbox policy permits flock on read-only descriptors, including LOCK_EX.
    return os.open(directory() / (name + ".lock"), os.O_RDONLY | os.O_CREAT, 0o644)


def try_lock(fd, kind):
    try:
        fcntl.flock(fd, kind | fcntl.LOCK_NB)
        return True
    except BlockingIOError:
        return False


def lock(fd, kind, until=None, check=lambda: None):
    while True:
        check()
        if try_lock(fd, kind):
            return True
        if until is not None and time.monotonic() >= until:
            return False
        time.sleep(POLL)


def records():
    result = []
    for path in directory().glob("*.json"):
        try:
            with path.open("rb") as stream:
                if try_lock(stream.fileno(), fcntl.LOCK_EX):
                    path.unlink(missing_ok=True)
                else:
                    row = json.load(stream)
                    if isinstance(row, dict):
                        result.append(row)
        except (OSError, ValueError):
            # A concurrent write may be incomplete; exclusion never reads records.
            continue
    return result


class Record:
    def __init__(self, mode, purpose, session=None, **extra):
        self.row = {"pid": os.getpid(), "mode": mode, "purpose": purpose,
                    "session": session or os.environ.get("JS_ADMISSION_SESSION", ""),
                    "start": datetime.datetime.now(datetime.timezone.utc).isoformat(), **extra}
        self.path = directory() / f"{uuid.uuid4().hex}.json"
        # Publish only after locking. Keep this inode throughout updates and pruning.
        temporary = self.path.with_suffix(".record")
        self.stream = temporary.open("w+")
        fcntl.flock(self.stream, fcntl.LOCK_EX)
        self.write()
        temporary.rename(self.path)

    def write(self, **extra):
        self.row.update(extra)
        self.stream.seek(0)
        self.stream.write(json.dumps(self.row) + "\n")
        self.stream.truncate()
        self.stream.flush()

    def close(self):
        try:
            self.path.unlink(missing_ok=True)
        finally:
            self.stream.close()


def nested(env):
    """An inherited marker is valid only while its original admission is alive."""
    try:
        token = json.loads(env.get(TOKEN, ""))
        if token["directory"] != str(directory().resolve()):
            return False
        name = token["record"]
        if Path(name).name != name or not name.endswith(".json"):
            return False
        with (directory() / name).open("rb") as stream:
            return not try_lock(stream.fileno(), fcntl.LOCK_EX)
    except (OSError, ValueError, KeyError, TypeError):
        return False


def admitted_env(record, env):
    return {**env, TOKEN: json.dumps({"directory": str(directory().resolve()), "record": record.path.name})}


@contextlib.contextmanager
def heavy(purpose, env=None, session=None):
    """Hold shared admission through a Python caller's launch/readiness scope."""
    env = dict(os.environ if env is None else env)
    if sys.platform != "linux" or nested(env):
        yield env
        return
    with contextlib.ExitStack() as stack:
        gate = lock_file("gate")
        stack.callback(os.close, gate)
        work = lock_file("work")
        stack.callback(os.close, work)
        record = Record("heavy-waiter", purpose, session)
        stack.callback(record.close)
        lock(gate, fcntl.LOCK_SH)
        lock(work, fcntl.LOCK_SH)
        record.write(mode="heavy")
        fcntl.flock(gate, fcntl.LOCK_UN)
        yield admitted_env(record, env)


def process(pid):
    """Identity for cleanup of our OWN child group, never participant liveness."""
    try:
        fields = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()
        return {"pid": int(pid), "state": fields[0], "pgid": int(fields[2]),
                "session": int(fields[3]), "birth": fields[19]}
    except (OSError, ValueError, IndexError):
        return None


def members(group, include_zombies=False):
    if not group:
        return []
    leader = process(group["pid"])
    if leader and leader["birth"] != group["birth"]:
        return []
    result = []
    for entry in Path("/proc").iterdir():
        if entry.name.isdigit():
            row = process(int(entry.name))
            if (row and row["pgid"] == group["pgid"] and row["session"] == group["session"]
                    and int(row["birth"]) >= int(group["birth"])
                    and (include_zombies or row["state"] != "Z")):
                result.append(row)
    return result


def group_signal(group, sig):
    if members(group):
        try:
            os.killpg(group["pgid"], sig)
        except ProcessLookupError:
            pass


def clean_group(group, child):
    group_signal(group, signal.SIGTERM)
    until = time.monotonic() + GRACE
    while members(group) and time.monotonic() < until:
        child.poll()
        time.sleep(.01)
    group_signal(group, signal.SIGKILL)
    # Never release work while a live group member remains (including slow kernel exit).
    while members(group, include_zombies=True):
        child.poll()
        reap()
        time.sleep(.01)
    child.wait()
    reap()


def reap():
    try:
        while os.waitpid(-1, os.WNOHANG)[0]:
            pass
    except ChildProcessError:
        pass


def subreaper():
    if ctypes.CDLL(None, use_errno=True).prctl(36, 1, 0, 0, 0) != 0:
        raise OSError(ctypes.get_errno(), "cannot become child subreaper")


def run_command(command, env):
    child = subprocess.Popen(command, env=env, start_new_session=True)
    group = process(child.pid)
    try:
        # A successful build may leave its Gradle daemon running; it is not heavy work.
        return child.wait()
    except BaseException:
        clean_group(group, child)
        raise


def pausable(args):
    if nested(os.environ):
        raise ValueError("pausable cannot be nested inside an admission")
    with contextlib.ExitStack() as stack:
        gate = lock_file("gate")
        stack.callback(os.close, gate)
        work = lock_file("work")
        stack.callback(os.close, work)
        record = Record("pausable", args.purpose, args.session, state="waiting")
        stack.callback(record.close)
        lock(gate, fcntl.LOCK_SH)
        lock(work, fcntl.LOCK_SH)
        env = admitted_env(record, os.environ)
        child = subprocess.Popen(args.command, env=env, start_new_session=True)
        group = process(child.pid)
        record.write(state="working", group=group)
        fcntl.flock(gate, fcntl.LOCK_UN)

        def running():
            if child.poll() is not None:
                raise ChildProcessError

        try:
            while child.poll() is None:
                if try_lock(gate, fcntl.LOCK_SH):
                    fcntl.flock(gate, fcntl.LOCK_UN)
                else:
                    # Hooks control only the caller's job/API. No other participant is signalled.
                    subprocess.run(shlex.split(args.pause_hook), env=env, check=True)
                    record.write(state="paused")
                    fcntl.flock(work, fcntl.LOCK_UN)
                    lock(gate, fcntl.LOCK_SH, check=running)
                    lock(work, fcntl.LOCK_SH, check=running)
                    subprocess.run(shlex.split(args.resume_hook), env=env, check=True)
                    record.write(state="working")
                    fcntl.flock(gate, fcntl.LOCK_UN)
                time.sleep(POLL)
            return child.returncode
        except ChildProcessError:
            return child.wait()
        finally:
            clean_group(group, child)


def exclusive_window(args, check):
    with contextlib.ExitStack() as stack:
        gate = lock_file("gate")
        stack.callback(os.close, gate)
        work = lock_file("work")
        stack.callback(os.close, work)
        record = Record("exclusive-waiter", args.purpose, args.session, holder_pid=os.getppid())
        stack.callback(record.close)
        lock(gate, fcntl.LOCK_EX, check=check)
        record.write(mode="exclusive-draining")
        if not lock(work, fcntl.LOCK_EX, time.monotonic() + args.drain_timeout, check):
            holders = [r for r in records() if r.get("mode") == "heavy" or
                       (r.get("mode") == "pausable" and r.get("state") != "paused")]
            print("admission: drain timeout; holders: " + json.dumps(holders), file=sys.stderr, flush=True)
            return REFUSED
        acquired = time.monotonic()
        record.write(mode="exclusive", deadline_seconds=args.timeout)
        child = subprocess.Popen(args.command, env=admitted_env(record, os.environ), start_new_session=True)
        group = process(child.pid)
        try:
            # Reserve TERM grace plus KILL/reaping allowance inside the budget.
            until = acquired + args.timeout - 2 * GRACE
            while child.poll() is None and time.monotonic() < until:
                check()
                time.sleep(.01)
            code = child.poll()
            return DEADLINE if code is None else code
        finally:
            clean_group(group, child)


def exclusive(args):
    if nested(os.environ):
        raise ValueError("exclusive cannot be nested inside an admission")
    # The guardian owns the locks and the child. EOF from the CLI, even on SIGKILL,
    # triggers group cleanup BEFORE it closes the locks. No timestamp/PID reclaim.
    lifetime_read, lifetime_write = os.pipe()
    guardian = os.fork()
    if guardian == 0:
        os.close(lifetime_write)
        os.setsid()

        def check():
            if select.select([lifetime_read], [], [], 0)[0] and not os.read(lifetime_read, 1):
                raise SystemExit(0)

        try:
            subreaper()
            code = exclusive_window(args, check)
        except (OSError, ValueError) as error:
            print(f"admission: {error}", file=sys.stderr, flush=True)
            code = 73
        except SystemExit as error:
            code = error.code
        finally:
            os.close(lifetime_read)
        os._exit(128 - code if code < 0 else code)
    os.close(lifetime_read)
    try:
        _, status = os.waitpid(guardian, 0)
        return os.waitstatus_to_exitcode(status)
    finally:
        os.close(lifetime_write)
        # A signal to this CLI closes the lifetime pipe, then waits for safe cleanup.
        try:
            os.waitpid(guardian, 0)
        except ChildProcessError:
            pass


def positive(value):
    number = float(value)
    if not math.isfinite(number) or number <= 0:
        raise argparse.ArgumentTypeError("must be finite and positive")
    return number


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_subparsers(dest="mode", required=True)
    modes.add_parser("status")
    for mode in ("heavy", "exclusive", "pausable"):
        sub = modes.add_parser(mode)
        sub.add_argument("--purpose", required=True)
        sub.add_argument("--session")
        if mode == "exclusive":
            sub.add_argument("--drain-timeout", type=positive, default=120)
            sub.add_argument("--timeout", type=positive, default=600)
        if mode == "pausable":
            sub.add_argument("--pause-hook", required=True)
            sub.add_argument("--resume-hook", required=True)
        sub.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if sys.platform != "linux":
        parser.error("machine admission requires Linux")
    if args.mode == "status":
        print(json.dumps(records(), indent=2))
        return 0
    if args.command[:1] == ["--"]:
        args.command.pop(0)
    if not args.command:
        parser.error("a command is required after --")
    if args.mode == "exclusive" and not 2 * GRACE < args.timeout <= 600:
        parser.error("--timeout must be greater than 0.4 and at most 600 seconds")
    if args.mode == "pausable" and any(not shlex.split(hook) for hook in (args.pause_hook, args.resume_hook)):
        parser.error("pause and resume hooks must be nonempty commands")
    subreaper()

    def interrupted(signum, _frame):
        # Do not let a second Ctrl-C interrupt cleanup and release locks too soon.
        for sig in (signal.SIGINT, signal.SIGTERM):
            signal.signal(sig, signal.SIG_IGN)
        raise SystemExit(128 + signum)

    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, interrupted)
    if args.mode == "exclusive":
        return exclusive(args)
    if args.mode == "pausable":
        return pausable(args)
    with heavy(args.purpose, session=args.session) as env:
        return run_command(args.command, env)


if __name__ == "__main__":
    try:
        rc = main()
        sys.exit(128 - rc if rc < 0 else rc)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"admission: {error}", file=sys.stderr)
        sys.exit(73)
