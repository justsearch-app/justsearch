"""Capture a Popen's original handle at spawn; stop only that recorded Windows incarnation."""
from __future__ import annotations

import ctypes
import json
import os
import subprocess
import weakref
from pathlib import Path

_identities: weakref.WeakKeyDictionary = weakref.WeakKeyDictionary()
_terminator = Path(__file__).resolve().parents[2] / "dev" / "lib" / "terminate-process.ps1"


def capture(proc: subprocess.Popen, *, birth_reader=None) -> None:
    """Do this immediately after Popen, before readiness, through its original process handle."""
    if os.name != "nt":
        _identities[proc] = (proc.pid, None)
        return
    if birth_reader is None:
        def birth_reader(child):
            from ctypes import wintypes
            creation, ended, kernel, user = (wintypes.FILETIME() for _ in range(4))
            get_times = ctypes.WinDLL("kernel32", use_last_error=True).GetProcessTimes
            get_times.argtypes = [wintypes.HANDLE] + [ctypes.POINTER(wintypes.FILETIME)] * 4
            get_times.restype = wintypes.BOOL
            if not get_times(wintypes.HANDLE(int(child._handle)), ctypes.byref(creation),
                             ctypes.byref(ended), ctypes.byref(kernel), ctypes.byref(user)):
                return None
            return str((creation.dwHighDateTime << 32) | creation.dwLowDateTime)
    try:
        birth = birth_reader(proc)
    except (OSError, AttributeError, TypeError, ValueError):
        birth = None
    _identities[proc] = (proc.pid, birth)


def start(argv, **kwargs) -> subprocess.Popen:
    proc = subprocess.Popen(argv, **kwargs)
    capture(proc)
    return proc


def stop(proc: subprocess.Popen, *, run=None) -> dict:
    run = run or subprocess.run
    recorded = _identities.get(proc)
    if recorded is None or recorded[0] != proc.pid:
        raise RuntimeError("Process was not started by this script; no stop requested")
    if os.name == "nt":
        birth = recorded[1]
        if not birth:
            raise RuntimeError(f"PID {proc.pid}: spawn identity unavailable; inspect it manually; no stop requested")
        result = run(["powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                      "-File", str(_terminator), "-Pid", str(proc.pid), "-CreationFileTimeUtc", birth,
                      "-Code", "1", "-Tree"], capture_output=True, text=True, timeout=20)
        try:
            detail = json.loads(result.stdout.strip().splitlines()[-1])
        except (ValueError, IndexError):
            raise RuntimeError(f"PID {proc.pid}: identity terminator did not confirm exit") from None
        if detail.get("outcome") not in ("terminated", "gone", "mismatch"):
            raise RuntimeError(f"PID {proc.pid}: incomplete tree stop: {detail}")
        return detail
    if proc.poll() is None:
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=10)
    return {"outcome": "terminated"}


def stop_named_descendants(proc: subprocess.Popen, name: str, *, run=None) -> int:
    """Names filter an already identity-proven owned tree; they never discover a kill target."""
    run = run or subprocess.run
    identity = _identities.get(proc)
    if os.name != "nt" or not identity or not identity[1] or identity[0] != proc.pid:
        raise RuntimeError("Owned Windows root identity unavailable; no descendant stop requested")
    args = ["powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File",
            str(_terminator), "-Pid", str(proc.pid), "-CreationFileTimeUtc", identity[1], "-Code", "1", "-Tree"]
    snapshot = run([*args, "-InspectOnly"], capture_output=True, text=True, timeout=20)
    detail = json.loads(snapshot.stdout.strip().splitlines()[-1])
    if detail.get("outcome") != "inspected":
        raise RuntimeError("Owned tree could not be inspected; no descendant stop requested")
    targets = [row for row in detail.get("processes", []) if row.get("owned") is True
               and str(row.get("name", "")).lower() == name.lower() and row.get("creationFileTimeUtc")]
    if not targets:
        raise RuntimeError(f"No identity-proven {name} started by this backend; no stop requested")
    for target in targets:
        own_args = args.copy()
        own_args[own_args.index("-Pid") + 1] = str(target["pid"])
        own_args[own_args.index("-CreationFileTimeUtc") + 1] = target["creationFileTimeUtc"]
        result = run(own_args, capture_output=True, text=True, timeout=20)
        ended = json.loads(result.stdout.strip().splitlines()[-1])
        if ended.get("outcome") not in ("terminated", "gone", "mismatch"):
            raise RuntimeError(f"Incomplete owned descendant stop: {ended}")
    return len(targets)
