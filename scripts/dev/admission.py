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
UNAVAILABLE = 73
GRACE = 0.2
POLL = 0.05
# The agreed budget: drain + scoring window <= 20 minutes.
MAX_DRAIN = 600
MAX_WINDOW = 600
MAX_GATE_WAIT = MAX_DRAIN + MAX_WINDOW
MAX_HOOK = 120
DEFAULT_HOOK = 60


class Refused(Exception):
    """The gate stayed closed past the caller's deadline (exit 75)."""


def directory(env=None):
    env = os.environ if env is None else env
    return Path(env.get("JS_ADMISSION_DIR") or "/tmp/js-linux-port/admission")


def lock_file(root, name):
    root.mkdir(parents=True, exist_ok=True)
    # Sandbox policy permits flock on read-only descriptors, including LOCK_EX.
    return os.open(root / (name + ".lock"), os.O_RDONLY | os.O_CREAT, 0o644)


def warn(message):
    print(f"admission: {message}", file=sys.stderr, flush=True)


# Signal handling of the CLI: a SIGINT/SIGTERM that arrives while a child is being
# spawned is held until the child is recorded, so cleanup always knows about it.
_deferred = None


def interrupted(signum, _frame):
    if _deferred is not None:
        _deferred.append(signum)
        return
    # Do not let a second Ctrl-C interrupt cleanup and release locks too soon.
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, signal.SIG_IGN)
    raise SystemExit(128 + signum)


def spawn(command, **options):
    """Popen with signals deferred; returns (child, group). Call deliver() inside cleanup scope."""
    global _deferred
    _deferred = []
    try:
        child = subprocess.Popen(command, start_new_session=True, **options)
    except BaseException:
        deliver()
        raise
    return child, process(child.pid)


def deliver():
    global _deferred
    pending, _deferred = _deferred, None
    if pending:
        interrupted(pending[0], None)


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


def live_record(path):
    """The record's row while its owner holds its lock; a dead record is removed."""
    try:
        with path.open("rb") as stream:
            if try_lock(stream.fileno(), fcntl.LOCK_EX):
                path.unlink(missing_ok=True)
                return None
            row = json.load(stream)
            return row if isinstance(row, dict) else None
    except (OSError, ValueError):
        # A concurrent write may be incomplete; exclusion never reads records.
        return None


def records(root=None):
    root = directory() if root is None else root
    # Unpublished temporaries of a creator that died before renaming are pruned too.
    for path in root.glob("*.record"):
        live_record(path)
    return [row for path in root.glob("*.json") if (row := live_record(path))]


class Record:
    def __init__(self, root, mode, purpose, session=None, **extra):
        self.row = {"pid": os.getpid(), "mode": mode, "purpose": purpose,
                    "session": session or os.environ.get("JS_ADMISSION_SESSION", ""),
                    "start": datetime.datetime.now(datetime.timezone.utc).isoformat(), **extra}
        self.path = root / f"{uuid.uuid4().hex}.json"
        # Publish only after locking. Keep this inode throughout updates and pruning.
        temporary = self.path.with_suffix(".record")
        self.stream = temporary.open("w+")
        try:
            fcntl.flock(self.stream, fcntl.LOCK_EX)
            self.write()
            temporary.rename(self.path)
        except BaseException:
            self.stream.close()
            temporary.unlink(missing_ok=True)
            raise

    def write(self, **extra):
        self.row.update(extra)
        self.stream.seek(0)
        self.stream.write(json.dumps(self.row) + "\n")
        self.stream.truncate()
        self.stream.flush()

    def close(self):
        # A job that inherited the record (and the work lock) and outlives this tool
        # keeps it locked, and so visible in status; otherwise it is removed here.
        self.stream.close()
        live_record(self.path)


def nested(env):
    """An inherited marker is valid only while its original admission is alive."""
    try:
        root = directory(env)
        token = json.loads(env.get(TOKEN, ""))
        if token["directory"] != str(root.resolve()):
            return False
        name = token["record"]
        if Path(name).name != name or not name.endswith(".json"):
            return False
        with (root / name).open("rb") as stream:
            return not try_lock(stream.fileno(), fcntl.LOCK_EX)
    except (OSError, ValueError, KeyError, TypeError):
        return False


def admitted_env(root, record, env):
    return {**env, TOKEN: json.dumps({"directory": str(root.resolve()), "record": record.path.name})}


def without_token(env):
    return {key: value for key, value in env.items() if key != TOKEN}


@contextlib.contextmanager
def admission(purpose, env, session=None, gate_timeout=MAX_GATE_WAIT):
    """Shared admission; yields (env, descriptors a job must inherit to keep it).

    Fail-open: if admission is unavailable here (directory not creatable, read-only
    view), one warning is printed and the caller proceeds without admission.
    """
    env = dict(env)
    if sys.platform != "linux" or nested(env):
        yield env, ()
        return
    root = directory(env)
    with contextlib.ExitStack() as stack:
        try:
            gate = lock_file(root, "gate")
            stack.callback(os.close, gate)
            work = lock_file(root, "work")
            stack.callback(os.close, work)
            record = Record(root, "heavy-waiter", purpose, session or env.get("JS_ADMISSION_SESSION"))
            stack.callback(record.close)
        except OSError as error:
            unavailable = error
        else:
            unavailable = None
        if unavailable is not None:
            stack.close()
            warn(f"machine admission unavailable in {root} ({unavailable}); {purpose} runs without admission")
            yield env, ()
            return
        if not lock(gate, fcntl.LOCK_SH, time.monotonic() + gate_timeout):
            raise Refused(f"gate stayed closed for {gate_timeout:g} s; holders: {json.dumps(exclusive_rows(root))}")
        lock(work, fcntl.LOCK_SH)
        record.write(mode="heavy")
        fcntl.flock(gate, fcntl.LOCK_UN)
        yield admitted_env(root, record, env), (work, record.stream.fileno())


@contextlib.contextmanager
def heavy(purpose, env=None, session=None, gate_timeout=MAX_GATE_WAIT):
    """Hold shared admission through a Python caller's launch/readiness scope.

    Uses the admission directory of ``env`` (default os.environ); yields the env to pass on.
    """
    with admission(purpose, os.environ if env is None else env, session, gate_timeout) as (admitted, _):
        yield admitted


def exclusive_rows(root):
    return [r for r in records(root) if str(r.get("mode", "")).startswith("exclusive")
            and r.get("mode") != "exclusive-waiter"]


def process(pid):
    """Identity for cleanup of our OWN children, never participant liveness."""
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
        return True
    except ChildProcessError:
        return False


def descendants():
    """Only this guardian's command tree; process groups and sessions may change."""
    pending = [os.getpid()]
    result, seen = [], set()
    while pending:
        parent = pending.pop()
        # Include every thread's children without inspecting other participants.
        for path in Path(f"/proc/{parent}/task").glob("*/children"):
            try:
                children = path.read_text().split()
            except FileNotFoundError:
                continue
            for pid in map(int, children):
                if pid not in seen and (row := process(pid)):
                    seen.add(pid)
                    result.append(row)
                    pending.append(pid)
    return result


def signal_descendants(sig):
    for row in descendants():
        try:
            # Pin the identity before signalling: a recycled PID is never a target.
            fd = os.pidfd_open(row["pid"])
            try:
                current = process(row["pid"])
                if current and current["birth"] == row["birth"]:
                    signal.pidfd_send_signal(fd, sig)
            finally:
                os.close(fd)
        except ProcessLookupError:
            pass


def clean_descendants(child):
    # This isolated subreaper has no children except the exclusive command tree.
    # Orphans (including double forks/setsid and nested subreapers) are adopted here.
    signal_descendants(signal.SIGTERM)
    until = time.monotonic() + GRACE
    while True:
        if child is not None:
            child.poll()
        # ECHILD proves the whole tree is gone. An empty /proc snapshot alone does
        # not: a concurrent reparent/fork can move a child during the scan.
        if not reap():
            return
        if time.monotonic() >= until:
            signal_descendants(signal.SIGKILL)
        time.sleep(.01)


def subreaper():
    if ctypes.CDLL(None, use_errno=True).prctl(36, 1, 0, 0, 0) != 0:
        raise OSError(ctypes.get_errno(), "cannot become child subreaper")


def run_command(command, env, keep=()):
    # The job inherits the work lock and its record: if this tool is SIGKILLed, the
    # kernel lock lives as long as the job, so an exclusive window drains and refuses
    # instead of overlapping it. (Java closes inherited descriptors, so a Gradle daemon
    # left running after a successful build does not keep admission.)
    child, group = spawn(command, env=env, pass_fds=keep)
    try:
        deliver()
        return child.wait()
    except BaseException:
        clean_group(group, child)
        raise


def run_hook(command, env, timeout):
    # Hooks control only the caller's job/API. No other participant is signalled.
    # A hook that does not finish in time is a hook failure (exit 73).
    subprocess.run(shlex.split(command), env=env, check=True, timeout=timeout)


def pausable_window(args, check):
    if nested(os.environ):
        raise ValueError("pausable cannot be nested inside an admission")
    root = directory()
    # No admission marker: heavy work the job starts takes its own shared admission,
    # so it waits while the job is paused.
    env = without_token(os.environ)
    with contextlib.ExitStack() as stack:
        gate = lock_file(root, "gate")
        stack.callback(os.close, gate)
        work = lock_file(root, "work")
        stack.callback(os.close, work)
        record = Record(root, "pausable", args.purpose, args.session, state="waiting")
        stack.callback(record.close)
        if not lock(gate, fcntl.LOCK_SH, time.monotonic() + args.gate_timeout, check):
            raise Refused(f"gate stayed closed for {args.gate_timeout:g} s; holders: {json.dumps(exclusive_rows(root))}")
        lock(work, fcntl.LOCK_SH, check=check)
        # Unlocking on pause applies to the shared open file, so the job's copy pauses too.
        child, group = spawn(args.command, env=env, pass_fds=(work, record.stream.fileno()))

        def running():
            check()
            if child.poll() is not None:
                raise ChildProcessError

        try:
            deliver()
            record.write(state="working", group=group)
            fcntl.flock(gate, fcntl.LOCK_UN)
            while child.poll() is None:
                check()
                if try_lock(gate, fcntl.LOCK_SH):
                    fcntl.flock(gate, fcntl.LOCK_UN)
                else:
                    run_hook(args.pause_hook, env, args.hook_timeout)
                    record.write(state="paused")
                    fcntl.flock(work, fcntl.LOCK_UN)
                    # Paused for as long as the window holds the gate (itself capped).
                    lock(gate, fcntl.LOCK_SH, check=running)
                    lock(work, fcntl.LOCK_SH, check=running)
                    # Reopen the gate before resuming: a slow resume hook never holds it.
                    fcntl.flock(gate, fcntl.LOCK_UN)
                    run_hook(args.resume_hook, env, args.hook_timeout)
                    record.write(state="working")
                time.sleep(POLL)
            return child.returncode
        except ChildProcessError:
            return child.wait()
        finally:
            clean_descendants(child)


def exclusive_window(args, check):
    root = directory()
    with contextlib.ExitStack() as stack:
        gate = lock_file(root, "gate")
        stack.callback(os.close, gate)
        work = lock_file(root, "work")
        stack.callback(os.close, work)
        record = Record(root, "exclusive-waiter", args.purpose, args.session, holder_pid=os.getppid())
        stack.callback(record.close)
        if not lock(gate, fcntl.LOCK_EX, time.monotonic() + args.gate_timeout, check):
            warn(f"gate stayed closed for {args.gate_timeout:g} s; holders: {json.dumps(exclusive_rows(root))}")
            return REFUSED
        record.write(mode="exclusive-draining")
        if not lock(work, fcntl.LOCK_EX, time.monotonic() + args.drain_timeout, check):
            holders = [r for r in records(root) if r.get("mode") == "heavy" or
                       (r.get("mode") == "pausable" and r.get("state") == "working")]
            warn("drain timeout; holders: " + json.dumps(holders))
            return REFUSED
        acquired = time.monotonic()
        record.write(mode="exclusive", deadline_seconds=args.timeout)
        child = None
        try:
            child = subprocess.Popen(args.command, env=admitted_env(root, record, os.environ), start_new_session=True)
            # Reserve TERM grace plus KILL/reaping allowance inside the budget.
            until = acquired + args.timeout - 2 * GRACE
            while child.poll() is None and time.monotonic() < until:
                check()
                time.sleep(.01)
            code = child.poll()
            return DEADLINE if code is None else code
        finally:
            clean_descendants(child)


def exclusive(args):
    if nested(os.environ):
        raise ValueError("exclusive cannot be nested inside an admission")
    return guarded(args, exclusive_window)


def pausable(args):
    if nested(os.environ):
        raise ValueError("pausable cannot be nested inside an admission")
    return guarded(args, pausable_window)


def guarded(args, window):
    # The guardian owns the locks and the child. EOF from the CLI, even on SIGKILL,
    # triggers descendant cleanup BEFORE it closes the locks. No timestamp/PID reclaim.
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
            code = window(args, check)
        except Refused as error:
            warn(str(error))
            code = REFUSED
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


def seconds(maximum):
    def parse(value):
        number = float(value)
        if not math.isfinite(number) or not 0 < number <= maximum:
            raise argparse.ArgumentTypeError(f"must be greater than 0 and at most {maximum} seconds")
        return number
    return parse


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_subparsers(dest="mode", required=True)
    modes.add_parser("status")
    for mode in ("heavy", "exclusive", "pausable"):
        sub = modes.add_parser(mode)
        sub.add_argument("--purpose", required=True)
        sub.add_argument("--session")
        sub.add_argument("--gate-timeout", type=seconds(MAX_GATE_WAIT), default=MAX_GATE_WAIT)
        if mode == "exclusive":
            sub.add_argument("--drain-timeout", type=seconds(MAX_DRAIN), default=MAX_DRAIN)
            sub.add_argument("--timeout", type=seconds(MAX_WINDOW), default=MAX_WINDOW)
        if mode == "pausable":
            sub.add_argument("--pause-hook", required=True)
            sub.add_argument("--resume-hook", required=True)
            sub.add_argument("--hook-timeout", type=seconds(MAX_HOOK), default=DEFAULT_HOOK)
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
    if args.mode == "exclusive" and not 2 * GRACE < args.timeout:
        parser.error(f"--timeout must be greater than {2 * GRACE:g} seconds")
    if args.mode == "pausable" and any(not shlex.split(hook) for hook in (args.pause_hook, args.resume_hook)):
        parser.error("pause and resume hooks must be nonempty commands")
    subreaper()
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, interrupted)
    try:
        if args.mode == "exclusive":
            return exclusive(args)
        if args.mode == "pausable":
            return pausable(args)
        with admission(args.purpose, os.environ, args.session, args.gate_timeout) as (env, keep):
            return run_command(args.command, env, keep)
    except Refused as error:
        warn(str(error))
        return REFUSED


if __name__ == "__main__":
    try:
        rc = main()
        sys.exit(128 - rc if rc < 0 else rc)
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        warn(str(error))
        sys.exit(UNAVAILABLE)
