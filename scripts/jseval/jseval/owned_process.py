"""Capture a Popen's original handle at spawn; stop only that recorded Windows incarnation.

Never its tree: children are covered by their creator's ordered stop and by the Engine's
manifest and exit ledger, not by a sweep through parent links.
"""
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
        raise RuntimeError(f"PID {proc.pid}: ownership unknown (no captured spawn identity); inspect it manually; no stop requested")
    if os.name == "nt":
        birth = recorded[1]
        if not birth:
            raise RuntimeError(f"PID {proc.pid}: spawn identity unavailable; inspect it manually; no stop requested")
        result = run(["powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                      "-File", str(_terminator), "-Pid", str(proc.pid), "-CreationFileTimeUtc", birth,
                      "-Code", "1"], capture_output=True, text=True, timeout=20)
        try:
            detail = json.loads(result.stdout.strip().splitlines()[-1])
        except (ValueError, IndexError):
            raise RuntimeError(f"PID {proc.pid}: identity terminator did not confirm exit") from None
        if detail.get("outcome") not in ("terminated", "gone", "mismatch"):
            raise RuntimeError(f"PID {proc.pid}: incomplete stop: {detail}")
        return detail
    if proc.poll() is None:
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=10)
    return {"outcome": "terminated"}
