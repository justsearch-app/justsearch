#!/usr/bin/env python3
"""Smoke the installed Debian payload, using isolated state and only its bundled JVM.

Run after apt installs the candidate on a disposable hosted Ubuntu runner.
Like installer_verify on Windows, this launches the installed Engine directly.
"""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time
import urllib.error
import urllib.request


def request(port, endpoint, token, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(f"http://127.0.0.1:{port}{endpoint}", data=data,
                                 headers={"X-JustSearch-Session": token,
                                          "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as response:
        return json.load(response)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("package")
    parser.add_argument("--evidence", required=True)
    args = parser.parse_args()
    name = subprocess.check_output(["dpkg-deb", "-f", args.package, "Package"], text=True).strip()
    installed = subprocess.check_output(["dpkg-query", "-L", name], text=True).splitlines()
    jars = [Path(p) for p in installed if p.endswith("/ui-headless.jar")]
    if len(jars) != 1:
        raise RuntimeError("installed package has no unique ui-headless.jar")
    root = jars[0].parent
    java = root / "runtime/bin/java"
    if not java.is_file():
        raise RuntimeError("installed package lacks bundled JVM")
    evidence = Path(args.evidence).resolve()
    evidence.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="justsearch-package-smoke-") as temp:
        data = Path(temp) / "data"
        data.mkdir()
        stdout = evidence.with_suffix(".stdout.log")
        stderr = evidence.with_suffix(".stderr.log")
        command = [str(java), "-Djustsearch.prod=true", f"-Djustsearch.data.dir={data}",
                   f"-Djustsearch.home={data}", "-Djustsearch.ui.settings.mode=IN_MEMORY",
                   f"-Djustsearch.config={root / 'config/application.yaml'}",
                   f"-Djustsearch.repo.root={root}", f"-Djustsearch.ssot.path={root / 'SSOT'}",
                   f"-Djustsearch.plugins.manifest={root / 'plugins/manifest.v1.json'}",
                   "-cp", f"{jars[0]}:{root / 'lib/*'}", "io.justsearch.ui.HeadlessApp"]
        proc = None
        with stdout.open("wb") as out, stderr.open("wb") as err:
            try:
                proc = subprocess.Popen(command, cwd=root, stdout=out, stderr=err,
                                        start_new_session=True,
                                        env={**os.environ, "JUSTSEARCH_DATA_DIR": str(data)})
                deadline = time.monotonic() + 240
                last_error = "no runtime manifest"
                while time.monotonic() < deadline:
                    if proc.poll() is not None:
                        raise RuntimeError(f"installed Engine exited {proc.returncode}; see {stderr}")
                    try:
                        manifest = json.loads((data / "runtime/manifest.json").read_text())
                        port = manifest["head"]["apiPort"]
                        token = manifest["head"]["sessionToken"]
                        if not token or not isinstance(port, int) or not 1 <= port <= 65535:
                            raise ValueError("invalid installed Engine binding")
                        status = request(port, "/api/status", token)
                        if status.get("knowledgeServerStartError"):
                            raise RuntimeError(status["knowledgeServerStartError"])
                        health = request(port, "/api/health", token)
                        worker = health.get("components", {}).get("worker", {}).get("state") or health.get("worker", {}).get("status")
                        if status.get("indexAvailable") is True and worker in ("UP", "READY", "LIFECYCLE_STATE_READY"):
                            result = request(port, "/api/knowledge/search", token,
                                             {"query": "keyboard shortcuts", "mode": "text", "limit": 10})
                            hits = result.get("results", [])
                            if any("keyboard-shortcuts.md" in json.dumps(hit) for hit in hits):
                                evidence.write_text(json.dumps({"platform": "linux", "passed": True,
                                    "commit": os.environ["GITHUB_SHA"], "runId": os.environ["GITHUB_RUN_ID"],
                                    "package": Path(args.package).name,
                                    "checks": ["dpkg install", "installed Engine ready", "bundled help search"],
                                    "status": status, "health": health, "search": result}, indent=2) + "\n")
                                print("PASS: installed Engine ready; bundled keyboard-shortcuts.md returned by search")
                                return
                        last_error = "Engine or bundled help not ready"
                    except (OSError, KeyError, ValueError, urllib.error.URLError) as exc:
                        last_error = str(exc)
                    time.sleep(1)
                raise RuntimeError(f"installed package smoke timed out: {last_error}; see {stdout} and {stderr}")
            finally:
                if proc is not None:
                    # Only this recorded process group, including any Engine-owned helpers.
                    try:
                        os.killpg(proc.pid, signal.SIGTERM)
                        proc.wait(timeout=15)
                    except ProcessLookupError:
                        pass
                    except subprocess.TimeoutExpired:
                        os.killpg(proc.pid, signal.SIGKILL)
                        proc.wait(timeout=10)


if __name__ == "__main__":
    main()
