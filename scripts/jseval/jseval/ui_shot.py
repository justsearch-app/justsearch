"""Single-step UI screenshot capture for agent development feedback loop.

Usage::

    jseval ui-shot search-results       # capture one step
    jseval ui-shot --list               # list all available steps
    jseval ui-shot --affected src/...   # capture steps affected by a file change

Reuses the step registry from ``ui_check`` but targets individual steps
for fast, targeted feedback during UI development.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import shutil
import signal
import socket
import subprocess
import time
from pathlib import Path
from typing import Any

from . import agent_spawn_register
from . import ui_check
from . import ui_selectors as S

log = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# File-to-step index  (loaded from ui_step_index.json — single source of truth)
# ---------------------------------------------------------------------------

def _load_step_index() -> dict[str, list[str]]:
    idx_path = Path(__file__).parent / "ui_step_index.json"
    return json.loads(idx_path.read_text(encoding="utf-8"))


FILE_TO_STEPS: dict[str, list[str]] = _load_step_index()


# ---------------------------------------------------------------------------
# Worktree-aware Vite dev server  (tempdoc 615 §27 — the FE-serve readiness contract)
# ---------------------------------------------------------------------------
# In a worktree, the main checkout's dev server doesn't watch the worktree's
# source files.  ui-shot auto-starts a Vite dev server from the worktree when
# needed, persisting it across calls via a PID file.
#
# ONE serve contract, shared with the human-facing `scripts/dev/serve-worktree-fe.cjs`
# (tempdoc 618 §7), drift-guarded by `tests/test_ui_serve.py`. The contract:
#   - FREE-PORT scan (never a hardcoded port → no cross-worktree collision, 615 §28 U6);
#   - `--strictPort` (fail fast, no silent drift);
#   - NEUTRAL vite (no `--mode mock` / `VITE_MSW` — those have no FE consumer; data is the
#     consumer's concern via the Playwright route-mock, 615 §28 U2);
#   - stderr CAPTURED to a file (never DEVNULL → a boot failure has a legible reason, the
#     §27 "fail loud" half; the readiness gate in ui_check tails it on a mount timeout);
#   - PROVENANCE on the server-info (branch/head → the served code is this worktree's).
# This module owns the AUTOMATED-consumer path; the .cjs owns the human path. They are two
# native paths bound by this contract — NOT one cross-language process (615 §29 as-built).

_SERVER_INFO_PATH = Path("tmp/ui-shot-server.json")
_VITE_PORT_START = 5174  # scan start; the actual port is the first FREE one (615 §28 U6)

# Tempdoc 861 W3 -- the `agent-spawns/` register scope's lease-on-use TTL for this producer,
# renewed on BOTH the start path and the reuse path (861 §6.2: "an actively used server keeps
# extending its own claim; an abandoned one lapses"). No supervisor process, no renewal daemon.
_AGENT_SPAWN_LEASE_DURATION_SEC = 30 * 60


def _register_agent_spawn(proc: subprocess.Popen, port: int, ui_web: Path) -> None:
    """Tempdoc 861 W3 -- write this freshly-started Vite's `agent-spawns/` register record.

    `proc.pid` IS the real listener here -- no [A3] cmd.exe-shim problem for this producer: the
    launch is a direct `subprocess.Popen([node, vite_entry, ...], ...)` with no shell wrapper
    (see `_start_vite_server` above), unlike `serve-worktree-fe.cjs`'s `shell: isWin` spawn (the
    `otlp-sink-ensure.mjs` hook carries the twin comment for its own no-shell producer).

    Best-effort and NEVER raises: registration bookkeeping must not fail a UI capture. Skipped
    entirely (not written half-formed) when the creation time cannot be read -- a record without
    it can never be identity-verified, so writing one anyway would only add an unactionable entry
    to a register whose whole product is a defensible kill decision.
    """
    try:
        creation = agent_spawn_register.process_creation_file_time_utc(proc.pid)
        if creation is None:
            log.debug(
                "agent-spawn registration skipped for pid %s: no readable creation time", proc.pid,
            )
            return
        checkout_root = ui_web.parent.parent
        record = agent_spawn_register.build_record(
            record_id=f"ui-shot-{port}",
            producer="ui-shot",
            pid=proc.pid,
            creation_file_time_utc=creation,
            cmdline_fingerprint=f"--port {port}",
            port=port,
            lease_duration_sec=_AGENT_SPAWN_LEASE_DURATION_SEC,
            repo_root=checkout_root,
            worktree_root=checkout_root,
            # The junction-lock field (861 §6.2): resolved HERE, through whatever
            # `_ensure_node_modules_junction` created, so a build error naming the MAIN checkout's
            # node_modules can still be attributed back to a WORKTREE session's Vite.
            node_modules_real_path=agent_spawn_register.resolve_node_modules_real_path(ui_web),
        )
        agent_spawn_register.write_record(record)
    except Exception as exc:  # noqa: BLE001 -- bookkeeping must never fail the actual capture
        log.debug("agent-spawn registration failed for pid %s (non-fatal): %s", proc.pid, exc)


def _renew_agent_spawn_lease(info: dict) -> None:
    """Tempdoc 861 W3 -- lease-on-use for the REUSE path (the start path renews at write time,
    via ``build_record``'s fresh lease). Never raises: a renewal failure must not turn a live
    reuse into a raised error for the caller."""
    port = info.get("port")
    if not port:
        return
    try:
        agent_spawn_register.renew_lease(f"ui-shot-{port}", _AGENT_SPAWN_LEASE_DURATION_SEC)
    except Exception:  # noqa: BLE001 -- see the module's failure policy
        log.debug("agent-spawn lease renewal failed for ui-shot-%s (non-fatal)", port)


def _port_in_use(port: int, host: str) -> bool:
    """True if something is listening on (host, port). A connect-probe — more reliable
    than a bind-probe on Windows, where a wildcard bind can coexist with a [::1] listener
    (mirrors serve-worktree-fe.cjs:69-77 portInUse)."""
    try:
        with socket.socket(
            socket.AF_INET6 if ":" in host else socket.AF_INET, socket.SOCK_STREAM
        ) as sock:
            sock.settimeout(0.4)
            return sock.connect_ex((host, port)) == 0
    except OSError:
        return False


def _is_free(port: int) -> bool:
    """Free only if NEITHER loopback stack answers (the Windows dual-stack gotcha)."""
    return not _port_in_use(port, "127.0.0.1") and not _port_in_use(port, "::1")


def _pick_free_port(start: int = _VITE_PORT_START) -> int:
    """First free port in ``start..start+50`` (mirrors serve-worktree-fe.cjs:84-89 pickPort)."""
    for p in range(start, start + 50):
        if _is_free(p):
            return p
    raise RuntimeError(f"no free port in {start}..{start + 50}")


def _git_provenance(cwd: Path) -> dict[str, str]:
    """branch + short HEAD of the served worktree — the provenance half of the contract."""
    prov: dict[str, str] = {}
    for key, args in (("branch", ["rev-parse", "--abbrev-ref", "HEAD"]),
                      ("head", ["rev-parse", "--short", "HEAD"])):
        try:
            out = subprocess.run(["git", *args], cwd=str(cwd), capture_output=True,
                                 text=True, timeout=5)
            if out.returncode == 0:
                prov[key] = out.stdout.strip()
        except Exception:
            pass
    return prov


def _resolve_vite_entry(ui_web: Path) -> Path | None:
    """Absolute path to Vite's JS bin entry under ``ui_web/node_modules`` (615 §30).

    We launch Vite via ``node <entry>``, NOT ``npx.cmd``/``npm.cmd``/``.bin/vite`` — a `.cmd`
    batch shim dies immediately when spawned DETACHED on Windows (615 §29; agent-lessons scoop
    note), whereas ``node.exe`` (a real PE) detaches cleanly with captured stderr (§28 U1). This
    is the detached-safe analog of the dev-runner's `npm run dev` → `node vite` (dev-runner.cjs).
    Reads vite's own ``package.json`` ``bin`` so we don't hardcode the entry; ``None`` signals a
    missing/incomplete install (the §30 actionable-reason trigger)."""
    vite_pkg = ui_web / "node_modules" / "vite" / "package.json"
    if not vite_pkg.exists():
        return None
    rel = "bin/vite.js"  # vite's canonical bin across v2..v8; read the manifest to be exact
    try:
        bin_field = json.loads(vite_pkg.read_text(encoding="utf-8")).get("bin")
        if isinstance(bin_field, str):
            rel = bin_field
        elif isinstance(bin_field, dict):
            rel = bin_field.get("vite", rel)
    except Exception:
        pass
    entry = (vite_pkg.parent / rel).resolve()
    return entry if entry.exists() else None


def _find_ui_web_dir() -> Path | None:
    """Walk up from CWD to find modules/ui-web/ with a vite.config."""
    cwd = Path.cwd()
    # Check if we're inside a worktree or main checkout
    for candidate in [cwd, *cwd.parents]:
        ui_web = candidate / "modules" / "ui-web"
        if (ui_web / "vite.config.js").exists() or (ui_web / "vite.config.ts").exists():
            return ui_web
    return None


def _find_node_modules_prefix() -> Path | None:
    """Find the nearest modules/ui-web with node_modules (usually main checkout)."""
    cwd = Path.cwd()
    for candidate in [cwd, *cwd.parents]:
        ui_web = candidate / "modules" / "ui-web"
        if (ui_web / "node_modules").exists():
            return ui_web
    return None


def _pid_alive(pid: int | None) -> bool:
    """True if a process with ``pid`` currently exists. The provenance half of the reuse
    gate (615 §34): a port that merely RESPONDS may be a foreign process that took our
    recorded port after our vite died — so reuse must also confirm OUR pid is still live."""
    if not pid:
        return False
    if os.name == "nt":
        # Mirror the canonical tasklist/taskkill pattern (backend.py). The PID filter prints
        # the process row if alive, else an "INFO: No tasks…" line.
        try:
            out = subprocess.run(
                ["tasklist", "/FI", f"PID eq {int(pid)}", "/NH"],
                capture_output=True, text=True, timeout=5)
            return str(int(pid)) in out.stdout
        except Exception:
            return False
    try:
        os.kill(int(pid), 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True  # exists but not signalable by us → alive
    except Exception:
        return False


def _process_cmdline(pid: int | None) -> str:
    """The full command line of ``pid`` (empty string if unknown/dead). Used to verify a
    reused server is actually OUR vite (615 §35 provenance hardening)."""
    if not pid:
        return ""
    if os.name == "nt":
        try:
            out = subprocess.run(
                ["powershell", "-NoProfile", "-Command",
                 f"(Get-CimInstance Win32_Process -Filter \"ProcessId={int(pid)}\").CommandLine"],
                capture_output=True, text=True, timeout=8)
            return (out.stdout or "").strip()
        except Exception:
            return ""
    try:  # POSIX: /proc is cheapest; fall back to ps
        return Path(f"/proc/{int(pid)}/cmdline").read_text().replace("\0", " ").strip()
    except Exception:
        try:
            out = subprocess.run(["ps", "-p", str(int(pid)), "-o", "args="],
                                 capture_output=True, text=True, timeout=5)
            return (out.stdout or "").strip()
        except Exception:
            return ""


def _pid_is_our_vite(pid: int | None, port: int) -> bool:
    """True if ``pid`` is a node-vite process serving ``port`` (615 §35). Closes the residual
    Fix-1 edge: pid-alive alone can false-accept if the OS recycled our recorded pid to an
    unrelated process (or a different-port vite) while a foreign server took our port. A
    vite serving the recorded port has both 'vite' and '--port <port>' in its command line —
    robust across main / prepared-worktree / junction-fallback (none depend on the root path,
    which would break the junction case)."""
    cmd = _process_cmdline(pid)
    if not cmd:
        return False
    low = cmd.lower()
    return "vite" in low and (f"--port {port}" in cmd or f"--port={port}" in cmd)


def _is_server_alive(info: dict) -> bool:
    """Check if a previously started server is still running and responsive."""
    port = info.get("port")
    root = info.get("root")
    if not port:
        return False
    # Check if root matches current worktree
    ui_web = _find_ui_web_dir()
    if ui_web and root and str(ui_web.resolve()) != root:
        return False
    # PROVENANCE (615 §34/§35): the recorded pid must still be alive (cheap tasklist gate)
    # AND be OUR vite serving the recorded port (cmdline gate). Without this, a stale
    # server-info whose port a FOREIGN process took — or whose pid the OS recycled — would
    # false-reuse it and capture against unknown code, violating §27 Predicate A. Port-
    # responds alone is not proof the responder is OUR vite.
    pid = info.get("pid")
    if not _pid_alive(pid):
        return False
    if not _pid_is_our_vite(pid, port):
        return False
    # Check if port responds (most reliable cross-platform check)
    import urllib.request
    try:
        urllib.request.urlopen(f"http://localhost:{port}", timeout=2)
    except Exception:
        return False
    # PROVENANCE, part 2 (tempdoc 814 §D8.4 / §R.2): the recorded `provenance.head` is written at
    # start (see `_start_vite`) but was never READ back, so a server started before a commit was
    # reused after it and the capture measured code that is no longer HEAD — a silently stale
    # measurement, the worst failure mode for a gate whose whole job is measuring. Vite's own HMR
    # does not cover a `git commit`/branch move, and the reuse path has no other freshness signal.
    # Kept LAST so the cheap gates (port/pid/cmdline) still short-circuit; a mismatch simply falls
    # through to starting a fresh server, which is the correct repair.
    recorded_head = (info.get("provenance") or {}).get("head")
    if recorded_head:
        current_head = _git_provenance(ui_web).get("head") if ui_web else None
        if current_head and current_head != recorded_head:
            log.debug("Vite server provenance stale (recorded head %s, current %s) — restarting",
                      recorded_head, current_head)
            return False
    return True


def _ensure_node_modules_junction(ui_web: Path) -> bool:
    """Ensure a node_modules junction exists in the worktree's ui-web dir.

    Vite's ESM config loader resolves ``import ... from 'vite'`` relative to
    the config file.  In a worktree there is no ``node_modules/``, so we
    create a junction (Windows) or symlink (Unix) pointing to the main
    checkout's ``node_modules/``.  Junctions are cheap, atomic, and don't
    require admin privileges on Windows.
    """
    nm = ui_web / "node_modules"
    if nm.exists():
        return True  # Already present (real or junction)

    source = _find_node_modules_prefix()
    if not source:
        return False
    source_nm = source / "node_modules"
    if not source_nm.exists():
        return False

    try:
        if os.name == "nt":
            # Windows junction — works without admin, follows through transparently
            subprocess.run(
                ["cmd", "/c", "mklink", "/J", str(nm), str(source_nm)],
                check=True, capture_output=True,
            )
        else:
            nm.symlink_to(source_nm)
        log.debug("Created node_modules junction: %s -> %s", nm, source_nm)
        return True
    except Exception as e:
        log.debug("Failed to create node_modules junction: %s", e)
        return False


class ServeStartError(Exception):
    """The worktree Vite server could not be brought up. Carries the best-available reason.

    tempdoc 615 §27 'fail loud': a start failure must NOT silently fall back to a foreign
    :5173 (whose served code is unknown — the §26 phantom-render-failed root cause). The
    capture surfaces this as 'cannot serve: <reason>'."""


def _start_vite_server() -> str:
    """Start (or reuse) a worktree Vite server. Returns the URL, or raises ServeStartError."""
    ui_web = _find_ui_web_dir()
    if not ui_web:
        raise ServeStartError("no modules/ui-web with a vite.config found from the CWD")

    # Check if we already have a running server for this worktree
    if _SERVER_INFO_PATH.exists():
        try:
            info = json.loads(_SERVER_INFO_PATH.read_text())
            if _is_server_alive(info):
                url = f"http://localhost:{info['port']}"
                log.debug("Reusing existing Vite server at %s (pid %s)", url, info["pid"])
                _renew_agent_spawn_lease(info)
                return url
        except Exception:
            pass

    # Ensure node_modules is available (junction for worktrees)
    if not _ensure_node_modules_junction(ui_web):
        raise ServeStartError(
            "no node_modules available (run `node scripts/dev/prepare-worktree.cjs`)")

    # Pre-flight (615 §30): resolve `node` + Vite's JS entry. A missing/incomplete install is the
    # dominant real failure here — name the remedy LOUD instead of letting a detached `.cmd` shim
    # die silently into "no stderr captured".
    node = shutil.which("node")
    if not node:
        raise ServeStartError("`node` not found on PATH — cannot launch Vite")
    vite_entry = _resolve_vite_entry(ui_web)
    if vite_entry is None:
        raise ServeStartError(
            f"FE deps missing/incomplete in {ui_web / 'node_modules'} (no runnable vite) — "
            "run `node scripts/dev/prepare-worktree.cjs` (or `npm ci` in modules/ui-web)")

    # FREE-PORT scan (no hardcoded port → no cross-worktree collision, 615 §28 U6).
    port = _pick_free_port()
    log.debug("Starting Vite dev server from %s on port %d", ui_web, port)

    # Launch via `node <vite-entry>` (615 §30): a detached `.cmd` shim (`npx.cmd`/`npm.cmd`) dies
    # immediately on Windows; `node.exe` detaches cleanly with captured stderr. NEUTRAL vite — no
    # `--mode mock` / `VITE_MSW` (615 §28 U2 — no FE consumer; data is the route-mock's concern).
    cmd = [node, str(vite_entry), "--port", str(port), "--strictPort"]

    # stderr CAPTURED to a file (never DEVNULL) so a boot/compile failure has a legible
    # reason the ui_check readiness gate can tail on a mount timeout (615 §27 "fail loud").
    _SERVER_INFO_PATH.parent.mkdir(parents=True, exist_ok=True)
    stderr_log = _SERVER_INFO_PATH.parent / f"ui-shot-vite-{port}.log"
    err_fh = open(stderr_log, "w", encoding="utf-8")

    proc = subprocess.Popen(
        cmd,
        cwd=str(ui_web),
        env={**os.environ},
        stdout=subprocess.DEVNULL,
        stderr=err_fh,
        # Detach so it survives the parent process (615 §28 U1 — detached + captured stderr).
        creationflags=subprocess.DETACHED_PROCESS | subprocess.CREATE_NO_WINDOW
        if os.name == "nt" else 0,
        start_new_session=os.name != "nt",
    )

    # Wait for server to be ready to ACCEPT CONNECTIONS (HTTP-200). NOTE: this is process
    # liveness, NOT app-mounted readiness — the mount gate is ui_check._await_app_ready
    # (615 §28 U4a: HTTP-200 returns in ~1s, a real mount needs up to 15s).
    import urllib.request
    url = f"http://localhost:{port}"
    # 60s: cold Vite start (~36s, §12) can exceed 15s under contention (a 2nd Vite running).
    # Subsequent calls reuse via the server-info, so this one-time ceiling is acceptable.
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        try:
            urllib.request.urlopen(url, timeout=1)
            break
        except Exception:
            time.sleep(0.3)
    else:
        tail = _tail_file(stderr_log, 800)
        proc.kill()
        err_fh.close()
        # 615 §31: the pre-flight only catches an ABSENT vite, not an INCOMPLETE install
        # (deps missing while bin present — the real §29 state). node then fails at runtime
        # with a module-resolution error; detect it here and NAME the remedy so the incomplete
        # case is as actionable as the absent case.
        remedy = ""
        if any(m in tail for m in ("ERR_MODULE_NOT_FOUND", "Cannot find module", "Cannot find package")):
            remedy = " → FE deps look incomplete; run `node scripts/dev/prepare-worktree.cjs` (or `npm ci`)"
        raise ServeStartError(
            f"Vite did not accept connections on :{port} within 60s"
            + (f"; stderr tail: {tail}" if tail else "; no stderr captured")
            + remedy)

    # Save server info (+ stderr_log + provenance — the contract's reason channel + provenance).
    info = {
        "pid": proc.pid,
        "port": port,
        "root": str(ui_web.resolve()),
        "stderr_log": str(stderr_log),
        "provenance": _git_provenance(ui_web),
        "started_at": time.time(),
    }
    _SERVER_INFO_PATH.write_text(json.dumps(info, indent=2))
    _register_agent_spawn(proc, port, ui_web)
    log.debug("Vite server started (pid %d, port %d, stderr %s)", proc.pid, port, stderr_log)
    return url


def _tail_file(path: Path, n_chars: int = 800) -> str:
    """Last ``n_chars`` of a (possibly missing) file — the best-available reason channel."""
    try:
        text = Path(path).read_text(encoding="utf-8", errors="replace")
        return text[-n_chars:].strip()
    except Exception:
        return ""


def _resolve_ui_url(ui_url: str) -> str:
    """Resolve the effective UI URL for an AUTOMATED capture (615 §27 provenance half).

    - An explicit ``--ui-url`` (anything but the default sentinel) is honored verbatim.
    - Otherwise we ALWAYS serve our OWN worktree Vite on a free port: provenance is
      guaranteed by construction (we started it from this worktree's source). We do NOT
      trust an already-listening ``:5173`` — it being up does not mean it serves THIS
      worktree's code (615 §28: observed ``:5173`` UP but owned by a foreign session's
      stack), the exact ambiguity that produced the §26 phantom "render-failed".
    """
    # Explicit override wins (e.g. pointing at a known-good server).
    if ui_url != "http://localhost:5173":
        return ui_url

    # Reuse OUR OWN tracked live server if it is still up and serves this worktree.
    if _SERVER_INFO_PATH.exists():
        try:
            info = json.loads(_SERVER_INFO_PATH.read_text())
            if _is_server_alive(info):
                _renew_agent_spawn_lease(info)
                return f"http://localhost:{info['port']}"
        except Exception:
            pass

    # Start a provenance-guaranteed worktree server on a free port. On failure this RAISES
    # ServeStartError (615 §27 fail-loud) — we never silently borrow a foreign :5173.
    return _start_vite_server()


# ---------------------------------------------------------------------------
# Dependency chain resolution
# ---------------------------------------------------------------------------

def _resolve_chain(step_name: str, steps: list[ui_check.Step]) -> list[ui_check.Step]:
    """Resolve transitive dependencies for a shared-chain step.

    Returns the target step plus all its transitive dependencies,
    preserving original registry order (critical for correct replay).
    """
    by_name = {s.name: s for s in steps}
    target = by_name.get(step_name)
    if not target:
        return []

    # Walk depends_on links to collect all needed step names
    needed: set[str] = {step_name}
    queue = [step_name]
    while queue:
        current = queue.pop()
        s = by_name.get(current)
        if s and s.depends_on and s.depends_on not in needed:
            needed.add(s.depends_on)
            queue.append(s.depends_on)

    # Filter steps preserving original registry order
    return [s for s in steps if s.name in needed]


# ---------------------------------------------------------------------------
# Single-step runner
# ---------------------------------------------------------------------------

async def _run_single_shot(
    step_name: str,
    ui_url: str,
    output_dir: Path,
    *,
    demo: bool = True,
    cooldown_ms: int = 250,
    timeout_ms: int = 30_000,
    measure: bool = True,
    fixtures: bool = False,
    trace: bool = False,
    record: bool = False,
) -> ui_check.ShotResult:
    """Capture a single named step screenshot."""
    from playwright.async_api import async_playwright

    all_steps = ui_check._build_steps(ui_url, cooldown_ms, timeout_ms)
    by_name = {s.name: s for s in all_steps}
    step = by_name.get(step_name)

    if not step:
        available = ", ".join(s.name for s in all_steps[:10])
        return ui_check.ShotResult(
            name=step_name, ok=False,
            error=f"unknown step '{step_name}'. Available: {available}...",
        )

    output_dir.mkdir(parents=True, exist_ok=True)

    async with async_playwright() as p:
        if step.isolated:
            # Tempdoc 669 — `--record` is scoped to shared-chain steps only (the intended
            # use case, capturing a full search→cited-answer sequence, is never isolated);
            # `record` is silently a no-op here rather than threaded through, matching
            # `ShotResult.video_path` staying unset when no video was captured.
            return await ui_check._run_isolated_step(
                step, ui_url, output_dir,
                demo=demo, cooldown_ms=cooldown_ms,
                timeout_ms=timeout_ms, playwright_module=p, measure=measure, fixtures=fixtures, trace=trace,
            )
        else:
            # Shared chain: replay the dependency chain up to target step
            chain = _resolve_chain(step_name, all_steps)
            base_url = ui_check._demo_url(ui_url) if demo else ui_url

            browser = await p.chromium.launch(headless=True, args=["--disable-gpu"])
            try:
                # Tempdoc 669 — `--record` spans the WHOLE chain replay in one video, since
                # this is one continuous context/page for the entire chain (confirmed: the
                # context is created once here and closed once below, after every chain step
                # has run against it). Playwright's own webm encoder; no post-processing here.
                video_dir = output_dir / "videos" if record else None
                ctx_kwargs = {"viewport": {"width": 1280, "height": 720}}
                if video_dir:
                    video_dir.mkdir(parents=True, exist_ok=True)
                    ctx_kwargs["record_video_dir"] = str(video_dir)
                ctx = await browser.new_context(**ctx_kwargs)
                if fixtures:
                    await ui_check.ui_fixtures.install_fixtures(ctx)
                await ctx.add_init_script(
                    "localStorage.setItem('justsearch-inspector-tab', 'ai');"
                )
                page = await ctx.new_page()
                # tempdoc 615 §6.2 — console sink for the shared chain page's measurement companion.
                console_sink = ui_check.ui_measure.ConsoleSink() if measure else None
                if console_sink:
                    console_sink.attach(page)
                await page.goto(base_url, wait_until="domcontentloaded", timeout=timeout_ms)
                # tempdoc 615 §27 readiness gate (shared chain): attribute a never-mount to
                # the serve layer with its reason, instead of a downstream step's setup timeout.
                try:
                    await ui_check._await_app_ready(page)
                except ui_check.AppNotMountedError as e:
                    return ui_check.ShotResult(
                        name=step_name, ok=False, error=f"cannot capture '{step_name}': {e}",
                    )

                results = await ui_check._run_shared_steps(
                    chain, page, output_dir,
                    cooldown_ms=cooldown_ms,
                    deadline=time.monotonic() + timeout_ms / 1000,
                    console_sink=console_sink, measure=measure,
                    trace_target=step_name if trace else None,
                )
                video = page.video
                await ctx.close()
                video_path = str(await video.path()) if video else None

                # Return only the requested step's result
                for r in results:
                    if r.name == step_name:
                        if video_path:
                            r.video_path = video_path
                        return r
                return ui_check.ShotResult(
                    name=step_name, ok=False,
                    error="step not found in chain results",
                )
            finally:
                await browser.close()


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

def _fixture_requirement_failures(
    step_names: list[str],
    *,
    ui_url: str,
    cooldown_ms: int,
    timeout_ms: int,
    fixtures: bool,
) -> list[dict[str, Any]]:
    """Reject fixture-only recipes before starting a server or browser."""
    if fixtures:
        return []
    steps = {
        step.name: step
        for step in ui_check._build_steps(ui_url, cooldown_ms, timeout_ms)
    }
    failures = []
    for step_name in step_names:
        step = steps.get(step_name)
        if step is not None and step.fixtures_variant != "default":
            failures.append({
                "name": step_name,
                "ok": False,
                "path": None,
                "elapsed_ms": 0.0,
                "error": (
                    f"step '{step_name}' requires --fixtures "
                    f"(fixture variant: {step.fixtures_variant})"
                ),
            })
    return failures


def execute_ui_shot(
    step_name: str,
    *,
    ui_url: str = "http://localhost:5173",
    output_dir: str | None = None,
    demo: bool = True,
    cooldown_ms: int = 250,
    timeout_ms: int = 30_000,
    measure: bool = True,
    fixtures: bool = False,
    trace: bool = False,
    record: bool = False,
) -> dict[str, Any]:
    """Capture a single step. Returns dict with name, ok, path, elapsed_ms, measure."""
    fixture_failures = _fixture_requirement_failures(
        [step_name], ui_url=ui_url, cooldown_ms=cooldown_ms,
        timeout_ms=timeout_ms, fixtures=fixtures,
    )
    if fixture_failures:
        return fixture_failures[0]
    try:
        ui_url = _resolve_ui_url(ui_url)
    except ServeStartError as e:
        return {"name": step_name, "ok": False, "path": None, "elapsed_ms": 0.0,
                "error": f"cannot serve: {e}"}
    out = Path(output_dir) if output_dir else Path("tmp/ui-shot")
    result = asyncio.run(
        _run_single_shot(
            step_name, ui_url, out,
            demo=demo, cooldown_ms=cooldown_ms, timeout_ms=timeout_ms, measure=measure,
            fixtures=fixtures, trace=trace, record=record,
        )
    )
    return {
        "name": result.name,
        "ok": result.ok,
        "path": result.path,
        "elapsed_ms": round(result.elapsed_ms, 1),
        **({"error": result.error} if result.error else {}),
        # tempdoc 615 §6.2 — the measurement companion: facts a correctness judgment can target
        # without opening the PNG (the screenshot demotes to a gestalt attachment).
        **({"measure": result.measure_summary} if result.measure_summary else {}),
        # Tempdoc 669 — the recorded video spanning this step's chain replay, when --record was set.
        **({"video_path": result.video_path} if result.video_path else {}),
    }


def execute_ui_shot_affected(
    file_path: str,
    *,
    ui_url: str = "http://localhost:5173",
    output_dir: str | None = None,
    demo: bool = True,
    cooldown_ms: int = 250,
    timeout_ms: int = 30_000,
    measure: bool = True,
    fixtures: bool = False,
    trace: bool = False,
    record: bool = False,
) -> list[dict[str, Any]]:
    """Capture all affected steps with the same options as an explicit single-step capture."""
    # Normalize path separators
    normalized = file_path.replace("\\", "/")

    matched_steps: list[str] = []
    for pattern, steps in FILE_TO_STEPS.items():
        if normalized.endswith(pattern) or pattern in normalized:
            matched_steps.extend(steps)

    if not matched_steps:
        return []

    # Deduplicate preserving order
    seen: set[str] = set()
    unique: list[str] = []
    for s in matched_steps:
        if s not in seen:
            seen.add(s)
            unique.append(s)

    fixture_failures = _fixture_requirement_failures(
        unique, ui_url=ui_url, cooldown_ms=cooldown_ms,
        timeout_ms=timeout_ms, fixtures=fixtures,
    )
    if fixture_failures:
        return fixture_failures
    try:
        ui_url = _resolve_ui_url(ui_url)
    except ServeStartError as e:
        return [{"name": "serve", "ok": False, "path": None, "elapsed_ms": 0.0,
                 "error": f"cannot serve: {e}"}]

    return [
        execute_ui_shot(
            step_name,
            ui_url=ui_url, output_dir=output_dir,
            demo=demo, cooldown_ms=cooldown_ms, timeout_ms=timeout_ms,
            measure=measure, fixtures=fixtures, trace=trace, record=record,
        )
        for step_name in unique
    ]


def execute_ui_shot_list(
    ui_url: str = "http://localhost:5173",
    cooldown_ms: int = 250,
    timeout_ms: int = 30_000,
) -> list[dict[str, Any]]:
    """List all available steps with metadata."""
    steps = ui_check._build_steps(ui_url, cooldown_ms, timeout_ms)
    return [
        {
            "name": s.name,
            "isolated": s.isolated,
            "required": s.required,
            **({"depends_on": s.depends_on} if s.depends_on else {}),
        }
        for s in steps
    ]


# ---------------------------------------------------------------------------
# Console formatters
# ---------------------------------------------------------------------------

def format_console_list(steps: list[dict]) -> str:
    chain = [s for s in steps if not s["isolated"]]
    isolated = [s for s in steps if s["isolated"]]
    lines = [f"Steps: {len(steps)} total ({len(chain)} chain, {len(isolated)} isolated)", ""]

    if chain:
        lines.append("Chain steps (sequential, shared browser):")
        for s in chain:
            dep = f"  <- {s['depends_on']}" if s.get("depends_on") else ""
            lines.append(f"  {s['name']}{dep}")
        lines.append("")

    if isolated:
        lines.append("Isolated steps (parallel, own browser):")
        for s in isolated:
            lines.append(f"  {s['name']}")

    return "\n".join(lines)


def format_console_shot(result: dict) -> str:
    if not result["ok"]:
        return f"FAIL: {result['name']} -- {result.get('error', 'unknown')}"
    # Tempdoc 669 — appended to every branch below when --record captured a video.
    video_line = f"\n  video: {result['video_path']}" if result.get("video_path") else ""
    # tempdoc 615 §6.2 — surface the measurement facts on stdout so a correctness judgment can be made
    # from data, not by opening the PNG (the screenshot is now a gestalt attachment).
    m = result.get("measure")
    if not m:
        return result["path"] + video_line
    if "error" in m:
        return f"{result['path']}\n  measure: (failed: {m['error']}){video_line}"
    overflow = ",".join(m["overflow"]) if m.get("overflow") else "none"
    flags = (" · flags: " + ",".join(m["flags"])) if m.get("flags") else ""
    # console split: real (app) defects vs env/dev noise (no-backend 502s, HMR) — §12 #1
    console = (
        f"{m.get('console_real', m.get('console_errors', 0))} real"
        f" (+{m.get('console_env', 0)} env)"
    )
    # axe: baseline-relative when this step has a baseline entry (§13 Move 2), else raw.
    axe_new = m.get("axe_new")
    if axe_new is not None:
        axe = (
            f"axe {len(axe_new)} NEW [{','.join(axe_new)}]" if axe_new
            else f"axe 0 NEW ({m.get('axe_known', 0)} known)"
        )
    else:
        axe = f"axe {m.get('axe_violations', 0)} violations ({m.get('axe_serious', 0)} serious)"
    return (
        f"{result['path']}\n"
        f"  measure: {m.get('measure_path')}\n"
        f"  a11y {m.get('a11y_landmarks', 0)} landmarks · geometry {m.get('geometry_elements', 0)} els "
        f"· {axe} "
        f"· console {console} · overflow {overflow}{flags}"
        f"{video_line}"
    )


def format_console_affected(results: list[dict]) -> str:
    if not results:
        return "No steps affected by this file."
    lines = []
    for r in results:
        if r["ok"]:
            lines.append(r["path"])
        else:
            lines.append(f"FAIL: {r['name']} -- {r.get('error', 'unknown')}")
    return "\n".join(lines)
