"""Execute the production Bash start/stop functions against real CPU-only fixture processes."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

import pytest
from jseval import owned_process

ROOT = Path(__file__).resolve().parents[3]
pytestmark = pytest.mark.skipif(os.name != "nt", reason="Windows sweep process boundary")


def sweep_fixture(tmp_path, mode):
    source = (ROOT / "scripts/search/sweep-cc-weights.sh").read_text(encoding="utf-8")
    # Load the actual function definitions, excluding distribution builds and evaluation.
    definitions = source.split("# Ensure distribution is built", 1)[0]
    go, leaf = tmp_path / "go", tmp_path / "leaf.json"
    fixture = tmp_path / "fixture.py"
    fixture.write_text("""import ctypes,json,os,sys,time
from ctypes import wintypes
from pathlib import Path
created,ended,kernel,user=(wintypes.FILETIME() for _ in range(4))
api=ctypes.WinDLL('kernel32');api.GetCurrentProcess.restype=wintypes.HANDLE
api.GetProcessTimes.argtypes=[wintypes.HANDLE]+[ctypes.POINTER(wintypes.FILETIME)]*4
api.GetProcessTimes(api.GetCurrentProcess(),ctypes.byref(created),ctypes.byref(ended),ctypes.byref(kernel),ctypes.byref(user))
Path(sys.argv[2]).write_text(json.dumps({'pid':os.getpid(),'creationFileTimeUtc':str((created.dwHighDateTime<<32)|created.dwLowDateTime)}))
while not Path(sys.argv[1]).exists():time.sleep(.01)
""", encoding="utf-8")
    command = tmp_path / "fixture.cmd"
    command.write_text(f'@echo off\r\n"{sys.executable}" "{fixture}" "{go}" "{leaf}"\r\n', encoding="utf-8")
    calls = """
INSTALL_BAT="$SWEEP_FIXTURE_COMMAND"
PORT=0
BASE_URL=http://127.0.0.1:0
start_backend 1 0 0
cp "$OWNED_RECORD" "$SWEEP_ROOT_COPY"
if [ "$SWEEP_FIXTURE_MODE" = gone ]; then
  touch "$SWEEP_GO"
  # Both our command and its leaf must exit before kill_backend is invoked.
  powershell -NoProfile -Command '$r=Get-Content -Raw -LiteralPath $env:SWEEP_ROOT_COPY|ConvertFrom-Json;$end=[DateTime]::UtcNow.AddSeconds(15);while(Get-Process -Id $r.pid -ErrorAction SilentlyContinue){if([DateTime]::UtcNow -gt $end){exit 9};Start-Sleep -Milliseconds 20}'
fi
kill_backend
if [ "$SWEEP_FIXTURE_MODE" = live ]; then
  # Before the fixture's own exit signal: the recorded root must already be ended by kill_backend.
  powershell -NoProfile -NonInteractive -Command '$r=Get-Content -Raw -LiteralPath $env:SWEEP_ROOT_COPY|ConvertFrom-Json; & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $env:JUSTSEARCH_SWEEP_TERMINATOR -Pid $r.pid -CreationFileTimeUtc $r.creationFileTimeUtc -Code 0 -InspectOnly | Select-Object -Last 1' > "$SWEEP_ROOT_COPY.after" || true
fi
"""
    env = {**os.environ, "JUSTSEARCH_DATA_DIR": str(tmp_path / "data"), "SWEEP_FIXTURE_COMMAND": str(command),
           "SWEEP_FIXTURE_MODE": mode, "SWEEP_ROOT_COPY": str(tmp_path / "owned.json"), "SWEEP_GO": str(go),
           "TMPDIR": str(tmp_path).replace("\\", "/")}
    proc = owned_process.start([shutil.which("bash"), "-c", definitions + calls,
                               str(ROOT / "scripts/search/sweep-cc-weights.sh")],
                              env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                              creationflags=subprocess.CREATE_NO_WINDOW)
    try:
        stdout, stderr = proc.communicate(timeout=90)
        return proc.returncode, stdout + stderr, leaf
    finally:
        go.touch()
        if proc.poll() is None:
            owned_process.stop(proc)
        # An interrupted harness retains its root identity; cleanup never uses PID alone.
        record = tmp_path / "owned.json"
        if record.exists():
            root = json.loads(record.read_text(encoding="utf-8"))
            result = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-File",
                                     str(ROOT / "scripts/dev/lib/terminate-process.ps1"), "-Pid", str(root["pid"]),
                                     "-CreationFileTimeUtc", root["creationFileTimeUtc"], "-Code", "1"],
                                    capture_output=True, text=True, timeout=30)
            outcome = json.loads(result.stdout.strip().splitlines()[-1])["outcome"]
            assert outcome in ("terminated", "gone", "mismatch"), result.stdout + result.stderr


def test_sweep_real_already_gone_is_success(tmp_path):
    status, output, leaf = sweep_fixture(tmp_path, "gone")
    assert leaf.exists(), "the script's own start function must actually launch the fixture"
    assert status == 0, "already-gone must be a successful real sweep stop: " + output


def test_sweep_real_live_stop_ends_only_the_recorded_root(tmp_path):
    # Identity-only stops, as on the integration side since 58a51a608: kill_backend ends the
    # recorded command and never follows parent links to its children. The fixture leaf is
    # ended by its creator's ordered signal instead (the go file), never by a tree sweep.
    status, output, leaf = sweep_fixture(tmp_path, "live")
    assert leaf.exists(), "the real leaf launched before stop"
    assert status == 0, output
    after = json.loads((tmp_path / "owned.json.after").read_text(encoding="utf-8-sig").strip())
    assert after["outcome"] in ("gone", "mismatch"), after
    identity = json.loads(leaf.read_text(encoding="utf-8"))
    deadline = time.monotonic() + 15
    while True:
        result = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-File",
                                 str(ROOT / "scripts/dev/lib/terminate-process.ps1"), "-Pid", str(identity["pid"]),
                                 "-CreationFileTimeUtc", identity["creationFileTimeUtc"], "-Code", "0", "-InspectOnly"],
                                capture_output=True, text=True, timeout=30)
        outcome = json.loads(result.stdout.strip().splitlines()[-1])["outcome"]
        if outcome in ("gone", "mismatch") or time.monotonic() > deadline:
            break
        time.sleep(0.1)
    assert outcome in ("gone", "mismatch"), "the leaf must end on its creator's ordered signal: " + result.stdout
