"""Tempdoc 412 follow-up Path C — health-failure smoke gate.

Empirically verifies inference.health.failure_total fires when llama-server
dies mid-flight. Approach:
1. Cold-start backend with a real model (healthy)
2. Verify ONLINE
3. Terminate the fixture-owned llama-server through its recorded birth and held handle
4. Wait ~10s — periodic health check (500ms cadence) should hit
   CONNECTION_REFUSED rapidly; threshold is 3 consecutive failures.
5. Stop backend
6. Verify inference.health.failure_total fired
"""

import json
import ctypes
from ctypes import wintypes
import os
import subprocess
import sys
import time
import urllib.request
import urllib.error
from pathlib import Path

REPO_ROOT = Path(r"F:/JustSearch")
sys.path.insert(0, str(REPO_ROOT / "scripts" / "jseval"))
from jseval.backend import start_backend, stop_backend  # noqa: E402

DATA_DIR = REPO_ROOT / "tmp" / "diagnostics" / "inference" / "health-failure-data"
PORT = 33224

print("=" * 60)
print("INFERENCE SMOKE GATE - health failure (owned native handle)")
print("=" * 60)

env_overrides = {
    "JUSTSEARCH_SERVER_EXE": str(
        REPO_ROOT / "modules" / "ui" / "native-bin" / "llama-server" / "variants" / "cuda12" / "llama-server.exe"
    ),
    "JUSTSEARCH_MODELS_DIR": str(REPO_ROOT / "models"),
    "JUSTSEARCH_AI_ENABLED": "true",
    "JUSTSEARCH_LLM_MODEL_PATH": str(REPO_ROOT / "models" / "Qwen_Qwen3.5-9B-Q4_K_M.gguf"),
}

info = start_backend(
    repo_root=REPO_ROOT,
    data_dir=DATA_DIR,
    port=PORT,
    clean=True,
    llm=True,
    health_timeout_sec=180,
    env_overrides=env_overrides,
)
print(f"Backend up: pid={info.proc.pid}")

try:
    # jseval's start_backend with llm=True already verified mode=online via
    # /api/inference/status before returning. Trust that and proceed.
    try:
        req = urllib.request.Request(f"http://127.0.0.1:{PORT}/api/inference/status")
        with urllib.request.urlopen(req, timeout=10) as resp:
            body = json.loads(resp.read().decode())
            print(f"  /api/inference/status mode={body.get('mode')}, available={body.get('available')}")
    except Exception as ex:
        print(f"  inference status threw: {ex}")

    # The fixture's Engine wrote the child's identity at spawn. Match its owner to
    # the held backend this test started, then keep the same native helper handle
    # across birth verification and termination. No process-name or parent sweep.
    manifest = json.loads((DATA_DIR / "runtime/manifest.json").read_text(encoding="utf-8"))
    assert manifest["pid"] == info.proc.pid, "manifest belongs to another Engine"
    child = next(c for c in manifest["children"] if c["kind"] == "LLAMA_SERVER")
    records = []
    for ledger in (DATA_DIR / "runtime/exit-ledger").glob("children.v1*.jsonl"):
        for line in ledger.read_text(encoding="utf-8").splitlines():
            try:
                records.append(json.loads(line))
            except json.JSONDecodeError:
                pass
    start = next(r for r in records if r.get("type") == "child-start"
                 and r.get("pid") == child["pid"] and r.get("childId") == child["id"])
    spawn = next(r for r in records if r.get("type") == "child-spawn"
                 and r.get("childId") == child["id"])
    assert spawn["owner"]["pid"] == info.proc.pid
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel.OpenProcess.restype = wintypes.HANDLE
    kernel.GetProcessTimes.argtypes = [wintypes.HANDLE] + [ctypes.POINTER(wintypes.FILETIME)] * 4
    kernel.TerminateProcess.argtypes = [wintypes.HANDLE, wintypes.UINT]
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    engine_times = [wintypes.FILETIME() for _ in range(4)]
    if not kernel.GetProcessTimes(wintypes.HANDLE(int(info.proc._handle)),
                                 *(ctypes.byref(t) for t in engine_times)):
        raise OSError(ctypes.get_last_error(), "original fixture Engine birth unreadable")
    engine_birth = (engine_times[0].dwHighDateTime << 32) | engine_times[0].dwLowDateTime
    assert int(spawn["owner"]["creationFileTime"]) == engine_birth, "helper owner is another Engine identity"
    helper = kernel.OpenProcess(0x101001, False, child["pid"])
    if not helper:
        raise OSError(ctypes.get_last_error(), "fixture helper identity unreadable")
    try:
        times = [wintypes.FILETIME() for _ in range(4)]
        if not kernel.GetProcessTimes(helper, *(ctypes.byref(t) for t in times)):
            raise OSError(ctypes.get_last_error(), "fixture helper birth unreadable")
        actual = (times[0].dwHighDateTime << 32) | times[0].dwLowDateTime
        assert actual == int(start["creationFileTime"]), "fixture helper PID was reused"
        print(f"Terminating fixture helper {child['pid']}/{actual}")
        if not kernel.TerminateProcess(helper, 1):
            raise OSError(ctypes.get_last_error(), "held fixture helper termination failed")
    finally:
        kernel.CloseHandle(helper)

    print("\nWaiting 12s for periodic health checks to fire (500ms cadence, threshold=3)...")
    time.sleep(12)

    # Check that health check has fired by reading status
    try:
        req = urllib.request.Request(f"http://127.0.0.1:{PORT}/api/status")
        with urllib.request.urlopen(req, timeout=10) as resp:
            body = json.loads(resp.read().decode())
            inf = body.get("inference") or {}
            print(f"  post-kill phase: {inf.get('phase')}")
            if inf.get("lastFailure"):
                print(f"  lastFailure: {inf.get('lastFailure')}")
    except Exception as ex:
        print(f"  status threw: {ex}")
finally:
    print("\nStopping backend (triggers final flush)...")
    stop_backend(info.proc)

metrics_path = DATA_DIR / "telemetry" / "metrics.ndjson"
print(f"\nReading metrics from: {metrics_path}")
content = metrics_path.read_text(encoding="utf-8")
inference_lines = [ln for ln in content.splitlines() if '"name":"inference.' in ln]
health_lines = [
    ln for ln in inference_lines
    if '"name":"inference.health.failure_total"' in ln
       or '"name":"inference.health.recovered_total"' in ln
]

print(f"\ninference.* total lines: {len(inference_lines)}")
print(f"inference.health.* lines: {len(health_lines)}")
print()
for ln in health_lines[:20]:
    print(ln)

print()
print("=" * 60)
print("VERDICT")
print("=" * 60)
if health_lines:
    print("[OK] health-failure metric fired under llama-server-kill scenario")
    sys.exit(0)
else:
    print("[FAIL] no health.failure_total / health.recovered_total fired")
    print("\nAll inference.* lines for diagnosis:")
    for ln in inference_lines[:50]:
        print(ln)
    sys.exit(2)
