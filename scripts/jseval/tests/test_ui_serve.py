"""Tests for the FE-serve readiness contract (tempdoc 615 §27).

The fragility these pin (615 §26/§28): the auto-serve trusted HTTP-200 as readiness,
hardcoded :5174, discarded Vite stderr, and set inert VITE_MSW/--mode mock. The contract
is: FREE-PORT scan, --strictPort, NEUTRAL vite (no mock), stderr CAPTURED to a file,
provenance on the server-info, and a mount gate that fails LOUD with the best-available
reason. No real Vite/browser — these are pure-unit (mocked Popen / fake page).
"""
from __future__ import annotations

import asyncio
import datetime
import json
import socket
import subprocess

from jseval import ui_check, ui_shot


# --- free-port selection (the U6 cross-worktree-collision fix) ---------------

def test_pick_free_port_returns_at_or_after_start():
    p = ui_shot._pick_free_port(5174)
    assert p >= 5174
    assert ui_shot._is_free(p)


def test_pick_free_port_skips_an_occupied_port(monkeypatch):
    # Deterministic: mark 5174 + 5175 busy on either loopback stack; the scan must skip both.
    busy = {(5174, "127.0.0.1"), (5175, "::1")}
    monkeypatch.setattr(ui_shot, "_port_in_use", lambda p, host: (p, host) in busy)
    assert ui_shot._is_free(5174) is False  # busy on v4
    assert ui_shot._is_free(5175) is False  # busy on v6 (the Windows dual-stack gotcha)
    assert ui_shot._is_free(5176) is True
    assert ui_shot._pick_free_port(5174) == 5176


def test_port_in_use_real_socket_smoke():
    # One real-socket smoke that a listening port is detected on 127.0.0.1.
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", 0))
    srv.listen(1)
    busy = srv.getsockname()[1]
    try:
        assert ui_shot._port_in_use(busy, "127.0.0.1") is True
    finally:
        srv.close()


# --- _start_vite_server honors the serve contract (mocked, no real Vite) -----

def test_start_vite_server_honors_contract(tmp_path, monkeypatch):
    captured = {}

    class _FakeProc:
        pid = 4321
        def kill(self): pass

    def _fake_popen(cmd, **kw):
        captured["cmd"] = cmd
        captured["env"] = kw.get("env", {})
        captured["stderr"] = kw.get("stderr")
        return _FakeProc()

    def _fake_urlopen(url, timeout=1):
        return object()  # server "accepts connections" immediately

    ui_web = tmp_path / "modules" / "ui-web"
    ui_web.mkdir(parents=True)
    (ui_web / "node_modules").mkdir()
    info_path = tmp_path / "server.json"

    vite_entry = tmp_path / "vite" / "bin" / "vite.js"

    # Tempdoc 861 W3 — isolate the agent-spawns register so this test can never write into the
    # real main checkout's `tmp/dev-runner/agent-spawns/`, regardless of whether the fake pid
    # happens to collide with a real process on the test machine.
    monkeypatch.setenv("JUSTSEARCH_DEV_RUNNER_STATE_ROOT", str(tmp_path / "state"))
    monkeypatch.setattr(
        ui_shot.agent_spawn_register, "process_creation_file_time_utc", lambda _pid: "134320479841300350",
    )

    monkeypatch.setattr(ui_shot, "_find_ui_web_dir", lambda: ui_web)
    monkeypatch.setattr(ui_shot, "_ensure_node_modules_junction", lambda _u: True)
    monkeypatch.setattr(ui_shot, "_resolve_vite_entry", lambda _u: vite_entry)
    monkeypatch.setattr(ui_shot.shutil, "which", lambda _x: "C:/node/node.exe")
    monkeypatch.setattr(ui_shot, "_pick_free_port", lambda *a, **k: 5191)
    monkeypatch.setattr(ui_shot, "_git_provenance", lambda _c: {"branch": "x", "head": "abc"})
    monkeypatch.setattr(ui_shot, "_SERVER_INFO_PATH", info_path)
    monkeypatch.setattr(subprocess, "Popen", _fake_popen)
    monkeypatch.setattr("urllib.request.urlopen", _fake_urlopen)
    monkeypatch.chdir(tmp_path)  # so the relative tmp/ stderr-log lands here

    url = ui_shot._start_vite_server()

    assert url == "http://localhost:5191"
    # Launch via `node <vite-entry>` (615 §30) — NOT npx.cmd; NEUTRAL vite + strictPort, free port
    assert captured["cmd"] == ["C:/node/node.exe", str(vite_entry), "--port", "5191", "--strictPort"]
    assert "npx" not in captured["cmd"][0] and "npx.cmd" not in captured["cmd"]
    assert "--mode" not in captured["cmd"] and "mock" not in captured["cmd"]
    assert "VITE_MSW" not in captured["env"]
    # stderr CAPTURED to a file, not DEVNULL
    assert captured["stderr"] is not None and captured["stderr"] != subprocess.DEVNULL
    # server-info carries the reason channel + provenance + port
    info = json.loads(info_path.read_text(encoding="utf-8"))
    assert info["port"] == 5191
    assert info["stderr_log"].endswith("ui-shot-vite-5191.log")
    assert info["provenance"] == {"branch": "x", "head": "abc"}

    # Tempdoc 861 W3 — the SAME start also wrote a real agent-spawns register record.
    rec_path = ui_shot.agent_spawn_register.register_dir() / "ui-shot-5191.json"
    rec = json.loads(rec_path.read_text(encoding="utf-8"))
    assert rec["producer"] == "ui-shot"
    assert rec["pid"] == 4321
    assert rec["creationFileTimeUtc"] == "134320479841300350"
    assert rec["cmdlineFingerprint"] == "--port 5191"
    assert rec["ownership"] == "session-owned"
    assert rec["probe"] == {"kind": "port", "port": 5191}
    assert rec["lease"]["durationSec"] == ui_shot._AGENT_SPAWN_LEASE_DURATION_SEC


# --- tempdoc 861 W3: lease-on-use renewed on the REUSE path, both call sites -------------------

def test_start_vite_server_reuse_renews_agent_spawn_lease(tmp_path, monkeypatch):
    monkeypatch.setenv("JUSTSEARCH_DEV_RUNNER_STATE_ROOT", str(tmp_path / "state"))
    info_path = tmp_path / "server.json"
    info_path.write_text(json.dumps({"port": 5192, "pid": 4321, "root": None}), encoding="utf-8")

    # Pre-seed a record with a lease that has already lapsed.
    stale = ui_shot.agent_spawn_register.build_record(
        record_id="ui-shot-5192", producer="ui-shot", pid=4321,
        creation_file_time_utc="134320479841300350", cmdline_fingerprint="--port 5192",
        port=5192, lease_duration_sec=1,
        now=datetime.datetime(2020, 1, 1, tzinfo=datetime.timezone.utc),
    )
    ui_shot.agent_spawn_register.write_record(stale)

    monkeypatch.setattr(ui_shot, "_SERVER_INFO_PATH", info_path)
    monkeypatch.setattr(ui_shot, "_is_server_alive", lambda _info: True)

    url = ui_shot._start_vite_server()
    assert url == "http://localhost:5192"

    rec_path = ui_shot.agent_spawn_register.register_dir() / "ui-shot-5192.json"
    renewed = json.loads(rec_path.read_text(encoding="utf-8"))
    assert renewed["lease"]["expiresAt"] > stale["lease"]["expiresAt"]


def test_resolve_ui_url_reuse_renews_agent_spawn_lease(tmp_path, monkeypatch):
    monkeypatch.setenv("JUSTSEARCH_DEV_RUNNER_STATE_ROOT", str(tmp_path / "state"))
    info_path = tmp_path / "server.json"
    info_path.write_text(json.dumps({"port": 5193, "pid": 4321, "root": None}), encoding="utf-8")

    stale = ui_shot.agent_spawn_register.build_record(
        record_id="ui-shot-5193", producer="ui-shot", pid=4321,
        creation_file_time_utc="134320479841300350", cmdline_fingerprint="--port 5193",
        port=5193, lease_duration_sec=1,
        now=datetime.datetime(2020, 1, 1, tzinfo=datetime.timezone.utc),
    )
    ui_shot.agent_spawn_register.write_record(stale)

    monkeypatch.setattr(ui_shot, "_SERVER_INFO_PATH", info_path)
    monkeypatch.setattr(ui_shot, "_is_server_alive", lambda _info: True)

    url = ui_shot._resolve_ui_url("http://localhost:5173")
    assert url == "http://localhost:5193"

    rec_path = ui_shot.agent_spawn_register.register_dir() / "ui-shot-5193.json"
    renewed = json.loads(rec_path.read_text(encoding="utf-8"))
    assert renewed["lease"]["expiresAt"] > stale["lease"]["expiresAt"]


# --- vite-entry resolution + actionable broken-install reason (615 §30) ------

def test_resolve_vite_entry_reads_bin_field(tmp_path):
    vite_dir = tmp_path / "node_modules" / "vite"
    (vite_dir / "bin").mkdir(parents=True)
    (vite_dir / "bin" / "vite.js").write_text("// vite", encoding="utf-8")
    (vite_dir / "package.json").write_text('{"bin": {"vite": "bin/vite.js"}}', encoding="utf-8")
    entry = ui_shot._resolve_vite_entry(tmp_path)
    assert entry is not None and entry.name == "vite.js" and entry.exists()


def test_resolve_vite_entry_none_when_missing(tmp_path):
    # No node_modules/vite at all (the broken/incomplete-install case).
    assert ui_shot._resolve_vite_entry(tmp_path) is None


def test_start_vite_server_actionable_reason_on_broken_install(tmp_path, monkeypatch):
    ui_web = tmp_path / "modules" / "ui-web"
    ui_web.mkdir(parents=True)
    monkeypatch.setattr(ui_shot, "_find_ui_web_dir", lambda: ui_web)
    monkeypatch.setattr(ui_shot, "_ensure_node_modules_junction", lambda _u: True)
    monkeypatch.setattr(ui_shot.shutil, "which", lambda _x: "C:/node/node.exe")
    monkeypatch.setattr(ui_shot, "_resolve_vite_entry", lambda _u: None)  # incomplete install
    monkeypatch.setattr(ui_shot, "_SERVER_INFO_PATH", tmp_path / "server.json")
    try:
        ui_shot._start_vite_server()
        assert False, "expected ServeStartError"
    except ui_shot.ServeStartError as e:
        msg = str(e)
        assert "FE deps missing" in msg
        assert "prepare-worktree" in msg or "npm ci" in msg  # names the remedy (§30)


def test_timeout_reason_names_remedy_on_module_error(tmp_path, monkeypatch):
    # 615 §31: incomplete-deps (bin present, deps missing) passes the pre-flight, then node
    # fails at runtime with a module-resolution error — the timeout reason must name the remedy.
    ui_web = tmp_path / "modules" / "ui-web"
    ui_web.mkdir(parents=True)
    info = tmp_path / "server.json"
    vite_entry = tmp_path / "vite" / "bin" / "vite.js"

    class _FakeProc:
        pid = 1
        def kill(self): pass

    monkeypatch.setattr(ui_shot, "_find_ui_web_dir", lambda: ui_web)
    monkeypatch.setattr(ui_shot, "_ensure_node_modules_junction", lambda _u: True)
    monkeypatch.setattr(ui_shot, "_resolve_vite_entry", lambda _u: vite_entry)
    monkeypatch.setattr(ui_shot.shutil, "which", lambda _x: "node")
    monkeypatch.setattr(ui_shot, "_pick_free_port", lambda *a, **k: 5199)
    monkeypatch.setattr(ui_shot, "_SERVER_INFO_PATH", info)
    monkeypatch.setattr(subprocess, "Popen", lambda *a, **k: _FakeProc())
    # never "accepts connections" → falls to the timeout path
    monkeypatch.setattr("urllib.request.urlopen", lambda *a, **k: (_ for _ in ()).throw(OSError()))
    # advance time: 1st call sets deadline (=0+60); next call (loop check) is past it → immediate timeout
    _t = {"n": 0}
    def _mono():
        _t["n"] += 1
        return 0.0 if _t["n"] == 1 else 1000.0
    monkeypatch.setattr(ui_shot.time, "monotonic", _mono)
    # the captured stderr shows node's module-resolution failure
    monkeypatch.setattr(ui_shot, "_tail_file", lambda *a, **k: "Error [ERR_MODULE_NOT_FOUND]: Cannot find package 'rollup'")

    try:
        ui_shot._start_vite_server()
        assert False, "expected ServeStartError"
    except ui_shot.ServeStartError as e:
        msg = str(e)
        assert "ERR_MODULE_NOT_FOUND" in msg          # the raw legible cause
        assert "prepare-worktree" in msg or "npm ci" in msg  # AND the named remedy (§31)


# --- fail-loud on start failure (no silent fallback to a foreign :5173) ------

def test_start_vite_server_raises_when_no_ui_web(monkeypatch):
    monkeypatch.setattr(ui_shot, "_find_ui_web_dir", lambda: None)
    try:
        ui_shot._start_vite_server()
        assert False, "expected ServeStartError"
    except ui_shot.ServeStartError as e:
        assert "vite.config" in str(e)


def test_execute_ui_shot_surfaces_serve_failure(monkeypatch):
    # A serve failure must read as a loud 'cannot serve', NOT a phantom render-failed.
    def _boom(_u):
        raise ui_shot.ServeStartError("Vite did not accept connections on :5175 within 60s")
    monkeypatch.setattr(ui_shot, "_resolve_ui_url", _boom)
    res = ui_shot.execute_ui_shot("home", fixtures=True)
    assert res["ok"] is False
    assert res["error"].startswith("cannot serve:")
    assert "did not accept connections" in res["error"]


# --- _tail_file (the best-available reason channel) --------------------------

def test_tail_file_returns_tail_and_is_missing_safe(tmp_path):
    log = tmp_path / "v.log"
    log.write_text("line1\nERROR: boom at Foo.ts:12\n", encoding="utf-8")
    assert "ERROR: boom" in ui_shot._tail_file(log, 800)
    assert ui_shot._tail_file(tmp_path / "nope.log") == ""


# --- provenance: reuse gate requires the recorded pid to be ALIVE (615 §34) -------

def test_pid_alive_true_for_self_false_for_dead():
    import os, sys, subprocess
    assert ui_shot._pid_alive(os.getpid()) is True
    assert ui_shot._pid_alive(None) is False
    # spawn-and-reap → a freshly-dead pid is not alive
    p = subprocess.Popen([sys.executable, "-c", "pass"])
    p.wait()
    assert ui_shot._pid_alive(p.pid) is False


def test_is_server_alive_false_when_pid_dead(monkeypatch):
    # A port that RESPONDS is not enough — a dead pid means a foreign process took our port.
    monkeypatch.setattr(ui_shot, "_find_ui_web_dir", lambda: None)  # skip root check
    monkeypatch.setattr(ui_shot, "_pid_alive", lambda _p: False)
    monkeypatch.setattr("urllib.request.urlopen", lambda *a, **k: object())  # port "responds"
    assert ui_shot._is_server_alive({"port": 5174, "pid": 4242, "root": None}) is False


def test_is_server_alive_true_when_pid_alive_and_port_responds(monkeypatch):
    monkeypatch.setattr(ui_shot, "_find_ui_web_dir", lambda: None)
    monkeypatch.setattr(ui_shot, "_pid_alive", lambda _p: True)
    monkeypatch.setattr(ui_shot, "_pid_is_our_vite", lambda _p, _port: True)
    monkeypatch.setattr("urllib.request.urlopen", lambda *a, **k: object())
    assert ui_shot._is_server_alive({"port": 5174, "pid": 4242, "root": None}) is True


# --- provenance HARDENING: the alive pid must be OUR vite on the recorded port (615 §35) ---

def test_pid_is_our_vite_matches_cmdline(monkeypatch):
    # a node-vite serving the recorded port → ours; non-vite / different-port → not ours.
    monkeypatch.setattr(ui_shot, "_process_cmdline",
                        lambda _p: r"C:\node.exe F:\repo\node_modules\vite\bin\vite.js --port 5174 --strictPort")
    assert ui_shot._pid_is_our_vite(123, 5174) is True
    assert ui_shot._pid_is_our_vite(123, 5999) is False          # different port → not ours
    monkeypatch.setattr(ui_shot, "_process_cmdline", lambda _p: r"C:\some\other-app.exe --port 5174")
    assert ui_shot._pid_is_our_vite(123, 5174) is False          # not a vite → not ours
    monkeypatch.setattr(ui_shot, "_process_cmdline", lambda _p: "")
    assert ui_shot._pid_is_our_vite(123, 5174) is False          # dead/unknown → not ours


def test_is_server_alive_false_when_pid_recycled_to_non_vite(monkeypatch):
    # pid ALIVE + port responds, but the alive pid is NOT our vite (OS recycled our pid).
    monkeypatch.setattr(ui_shot, "_find_ui_web_dir", lambda: None)
    monkeypatch.setattr(ui_shot, "_pid_alive", lambda _p: True)
    monkeypatch.setattr(ui_shot, "_pid_is_our_vite", lambda _p, _port: False)
    monkeypatch.setattr("urllib.request.urlopen", lambda *a, **k: object())
    assert ui_shot._is_server_alive({"port": 5174, "pid": 4242, "root": None}) is False


# --- the mount gate fails loud with the best-available reason (615 §27/U3) ---

class _FakeLocator:
    def __init__(self, raises: bool):
        self._raises = raises
        self.first = self

    async def wait_for(self, **kw):
        if self._raises:
            raise RuntimeError("Timeout 15000ms exceeded")


class _FakePage:
    def __init__(self, rail_raises: bool, overlay=None, url="http://localhost:5174",
                 failed_resources=()):
        self._rail_raises = rail_raises
        self._overlay = overlay
        self.url = url
        self._failed_resources = failed_resources

    def locator(self, _sel):
        return _FakeLocator(self._rail_raises)

    async def evaluate(self, _js):
        return {"overlay": self._overlay, "failedResources": self._failed_resources}


def test_await_app_ready_passes_when_rail_visible():
    # No raise expected.
    asyncio.run(ui_check._await_app_ready(_FakePage(rail_raises=False), timeout_ms=10))


def test_await_app_ready_reason_includes_vite_stderr(tmp_path, monkeypatch):
    log = tmp_path / "vite.log"
    log.write_text("ERROR: Transform failed: Unexpected token in Shell.ts:88\n", encoding="utf-8")
    info = tmp_path / "server.json"
    # 615 §34/§35: the page must target THIS server (port match) AND the server-info must be
    # LIVE (pid alive) for its stderr to be the reason.
    info.write_text(json.dumps({"stderr_log": str(log), "port": 5174, "pid": 4242}), encoding="utf-8")
    monkeypatch.setattr(ui_shot, "_SERVER_INFO_PATH", info)
    monkeypatch.setattr(ui_shot, "_pid_alive", lambda _p: True)

    try:
        asyncio.run(ui_check._await_app_ready(
            _FakePage(rail_raises=True, url="http://localhost:5174"), timeout_ms=10))
        assert False, "expected AppNotMountedError"
    except ui_check.AppNotMountedError as e:
        assert "never mounted" in str(e)
        assert "Transform failed" in str(e)  # the captured reason is surfaced


def test_await_app_ready_reason_skips_stale_dead_pid_server(tmp_path, monkeypatch):
    # 615 §35: same port, but the server-info is STALE (pid dead) — its stderr must NOT be used.
    log = tmp_path / "vite.log"
    log.write_text("ERROR: Transform failed in StaleServer.ts:1\n", encoding="utf-8")
    info = tmp_path / "server.json"
    info.write_text(json.dumps({"stderr_log": str(log), "port": 5174, "pid": 4242}), encoding="utf-8")
    monkeypatch.setattr(ui_shot, "_SERVER_INFO_PATH", info)
    monkeypatch.setattr(ui_shot, "_pid_alive", lambda _p: False)  # stale

    try:
        asyncio.run(ui_check._await_app_ready(
            _FakePage(rail_raises=True, url="http://localhost:5174"), timeout_ms=10))
        assert False, "expected AppNotMountedError"
    except ui_check.AppNotMountedError as e:
        assert "Transform failed" not in str(e)
        assert "no Vite stderr or error overlay" in str(e)


def test_await_app_ready_reason_guard_skips_mismatched_server(tmp_path, monkeypatch):
    # 615 §34: a stale/external server-info (port A) must NOT supply the reason when the page
    # targets a DIFFERENT server (port B) — else an unrelated server's stderr misleads.
    log = tmp_path / "vite.log"
    log.write_text("ERROR: Transform failed in SomeOther.ts:1\n", encoding="utf-8")
    info = tmp_path / "server.json"
    info.write_text(json.dumps({"stderr_log": str(log), "port": 5174}), encoding="utf-8")
    monkeypatch.setattr(ui_shot, "_SERVER_INFO_PATH", info)

    try:
        asyncio.run(ui_check._await_app_ready(
            _FakePage(rail_raises=True, url="http://localhost:5199"), timeout_ms=10))
        assert False, "expected AppNotMountedError"
    except ui_check.AppNotMountedError as e:
        assert "Transform failed" not in str(e)        # the mismatched stderr is NOT used
        assert "no Vite stderr or error overlay" in str(e)  # falls back honestly


def test_await_app_ready_falls_back_when_no_signal(tmp_path, monkeypatch):
    monkeypatch.setattr(ui_shot, "_SERVER_INFO_PATH", tmp_path / "absent.json")
    try:
        asyncio.run(ui_check._await_app_ready(_FakePage(rail_raises=True, overlay=None), timeout_ms=10))
        assert False, "expected AppNotMountedError"
    except ui_check.AppNotMountedError as e:
        assert "never mounted" in str(e)
        assert "no Vite stderr or error overlay" in str(e)


def test_await_app_ready_reports_optimizer_failure_without_overlay(tmp_path, monkeypatch):
    monkeypatch.setattr(ui_shot, "_SERVER_INFO_PATH", tmp_path / "absent.json")
    page = _FakePage(rail_raises=True, failed_resources=["504 /node_modules/.vite/deps/lit.js"])
    try:
        asyncio.run(ui_check._await_app_ready(page, timeout_ms=10))
        assert False, "expected AppNotMountedError"
    except ui_check.AppNotMountedError as error:
        assert "failed same-origin resources: 504 /node_modules/.vite/deps/lit.js" in str(error)
        assert "no Vite stderr" not in str(error)
