"""Full production Search lists fit supported short windows (R1a/Y7a).

The optional root selects a baseline shared clone for fail-before evidence. All
services and browser profiles belong to this test; no installed app is reused.
"""
import asyncio
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.request

import pytest

from jseval.ui_check import _await_app_ready, _build_steps
from jseval.ui_fixtures import install_fixtures


@pytest.fixture(scope="module")
def recent_ui():
    pytest.importorskip("playwright.async_api")
    root = Path(os.environ.get("JSEVAL_RECENTS_UI_ROOT", Path(__file__).resolve().parents[3]))
    web = root / "modules/ui-web"
    if not (web / "node_modules/vite/bin/vite.js").exists():
        pytest.skip("UI dependencies are not installed")
    private = Path(tempfile.mkdtemp(prefix="jsv-", dir="/tmp"))
    previous_tmpdir = os.environ.get("TMPDIR")
    env = {k: v for k, v in os.environ.items() if not k.startswith("JUSTSEARCH_") and k not in (
        "DISPLAY", "WAYLAND_DISPLAY", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "JAVA_OPTS", "_JAVA_OPTIONS")}
    env.update(TMPDIR=str(private), CUDA_VISIBLE_DEVICES="-1", VITE_API_PORT="9")
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    with (private / "vite.log").open("w") as log:
        process = subprocess.Popen(["node", "node_modules/vite/bin/vite.js", "--host", "127.0.0.1",
                                    "--port", str(port), "--strictPort"], cwd=web, env=env,
                                   stdout=log, stderr=subprocess.STDOUT)
        try:
            url = f"http://127.0.0.1:{port}"
            deadline = time.monotonic() + 30
            while True:
                assert process.poll() is None, (private / "vite.log").read_text()
                try:
                    with urllib.request.urlopen(url, timeout=1):
                        break
                except OSError:
                    assert time.monotonic() < deadline, "private Vite did not start"
                    time.sleep(.1)
            os.environ["TMPDIR"] = str(private)
            yield url, private
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            with socket.socket() as sock:
                assert sock.connect_ex(("127.0.0.1", port)) != 0, "private Vite port remains open"
            if previous_tmpdir is None:
                os.environ.pop("TMPDIR", None)
            else:
                os.environ["TMPDIR"] = previous_tmpdir
            shutil.rmtree(private)


@pytest.mark.parametrize("state", ["hero", "docked"])
@pytest.mark.parametrize("viewport", [(1280, 720), (600, 400)])
def test_short_recents_heading_and_every_row_reachable(recent_ui, state, viewport):
    from playwright.async_api import async_playwright

    async def check():
        url, private = recent_ui
        async with async_playwright() as runtime:
            browser = await runtime.chromium.launch(headless=True, env={
                **{k: v for k, v in os.environ.items() if not k.startswith("JUSTSEARCH_") and k not in ("DISPLAY", "WAYLAND_DISPLAY", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "JAVA_OPTS", "_JAVA_OPTIONS")},
                "TMPDIR": str(private), "CUDA_VISIBLE_DEVICES": "-1"})
            try:
                context = await browser.new_context(viewport={"width": viewport[0], "height": viewport[1]})
                await install_fixtures(context)
                page = await context.new_page()
                await page.goto(url)
                await _await_app_ready(page)
                step = next(s for s in _build_steps(url, 100, 30000)
                            if s.name == f"sv3-recents-{state}-{viewport[0]}x{viewport[1]}-dark")
                await step.setup(page)
            finally:
                await browser.close()

    asyncio.run(check())
