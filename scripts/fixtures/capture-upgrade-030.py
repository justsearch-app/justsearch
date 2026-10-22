#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Linux-only release capture. Requires bubblewrap, Java 25, and js-gradle-slot.

Run from the repository root with TMPDIR set outside /tmp. The release checkout,
Engine homes, native extraction, and logs live there. Models are mounted read-only.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import sqlite3
import subprocess
import time
import urllib.request
import zipfile

REPO = Path(__file__).resolve().parents[2]
BASE = Path(os.environ["TMPDIR"]).resolve() / "release030-capture"
RELEASE = BASE / "release"
CAPTURE = BASE / "capture"
RESOURCES = BASE / "fixtures"
COMMON = Path(subprocess.check_output(["git", "rev-parse", "--path-format=absolute", "--git-common-dir"], cwd=REPO, text=True).strip())
MODELS = COMMON.parent / "models"
PROCESS = None


def namespace():
    return ["bwrap", "--tmpfs", "/", "--ro-bind", "/usr", "/usr", "--ro-bind", "/lib", "/lib",
            "--ro-bind", "/lib64", "/lib64", "--ro-bind", "/etc", "/etc", "--proc", "/proc",
            "--dev", "/dev", "--bind", str(CAPTURE), "/fixture", "--ro-bind", str(RELEASE), "/release",
            "--ro-bind", str(MODELS), "/models", "--ro-bind", str(REPO / "scripts/fixtures"), "/capture-tools",
            "--chdir", "/release", "--"]


def environment():
    env = {k: v for k, v in os.environ.items() if not k.startswith(("JUSTSEARCH_", "JAVA_", "JDK_"))}
    env.update(TMPDIR="/fixture", JDK_JAVA_OPTIONS="-Djava.io.tmpdir=/fixture/jvm-tmp",
               JUSTSEARCH_HOME="/fixture/home", JUSTSEARCH_DATA_DIR="/fixture/data", JUSTSEARCH_API_PORT="0",
               JUSTSEARCH_MODE="mcp-lite", JUSTSEARCH_AI_DISABLED="true", JUSTSEARCH_LLM_ENABLED="false",
               JUSTSEARCH_GPU_ENABLED="false", JUSTSEARCH_NER_ENABLED="false", JUSTSEARCH_SPLADE_ENABLED="false",
               JUSTSEARCH_AI_EMBED_ENABLED="false", JUSTSEARCH_MODELS_DIR="/models", JUSTSEARCH_REPO_ROOT="/release",
               JUSTSEARCH_WORKER_LIB_DIR="/release/modules/indexer-worker/build/install/indexer-worker/lib")
    return env


def request(route, body=None):
    manifest = json.loads((CAPTURE / "data/runtime/manifest.json").read_text())
    port = manifest["head"]["apiPort"]
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(f"http://127.0.0.1:{port}{route}", data=data, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.load(response)


def start():
    global PROCESS
    with (BASE / f"engine-{time.time_ns()}.log").open("w") as log:
        PROCESS = subprocess.Popen(namespace() + ["java", "-Xmx768m", "-Duser.home=/fixture/home",
            "-Djustsearch.repo.root=/release", "-Djustsearch.ssot.path=/release/SSOT", "-cp",
            "/release/modules/ui/build/headless-dist/*:/release/modules/ui/build/headless-dist/lib/*",
            "io.justsearch.ui.HeadlessApp"], env=environment(), stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        if PROCESS.poll() is not None:
            raise RuntimeError("Release Engine exited; inspect the capture logs")
        try:
            if request("/api/knowledge/status").get("ready"):
                return
        except (OSError, ValueError, KeyError):
            pass
        time.sleep(0.1)
    raise TimeoutError("Release Engine readiness")


def kill():
    global PROCESS
    if PROCESS is not None:
        # The group is exclusively the Engine, worker, and extraction children started here.
        try:
            os.killpg(PROCESS.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        PROCESS.wait(timeout=10)
        PROCESS = None


def archive(name):
    files = []
    for prefix in ("data", "home", "roots", "orphan"):
        for file in (CAPTURE / prefix).rglob("*"):
            relative = file.relative_to(CAPTURE)
            if not file.is_file() or any(x in ("runtime", "logs", "telemetry") for x in relative.parts):
                continue
            if file.name in ("app.lock", "worker_signal.lock", "default.index.lock") or file.name.endswith("-shm"):
                continue
            files.append((file, str(relative)))
    files.append((CAPTURE / "explicit.txt", "explicit.txt"))
    files.extend((p, "release/SSOT/docs/help/" + p.name) for p in (RELEASE / "SSOT/docs/help").glob("*.md"))
    for file, relative in files:
        if re.search(r"token|secret|password|credential|\.pem$|\.key$|\.p12$|\.jks$", relative, re.I):
            raise AssertionError("credential-like capture filename: " + relative)
        for needle in (b"/home/", b"/Users/", b"-----BEGIN ", b"ghp_", b"github_pat_", b"sk-proj-"):
            if needle in file.read_bytes():
                raise AssertionError("sensitive capture bytes: " + relative)
    # Read-only mode prevents inspection from checkpointing the captured crash WAL.
    with sqlite3.connect(f"file:{CAPTURE / 'data/jobs.db'}?mode=ro", uri=True) as connection:
        manifest = dict(release="0.3.0", commit="76871d924", captured=time.strftime("%Y-%m-%d", time.gmtime()),
            state=json.loads((CAPTURE / "data/index/default/state.json").read_text()),
            user_version=connection.execute("PRAGMA user_version").fetchone()[0],
            jobs=connection.execute("SELECT state,count(*) FROM jobs GROUP BY state ORDER BY state").fetchall(),
            switch_buffer=connection.execute("SELECT count(*) FROM switch_buffer").fetchone()[0],
            files={relative: hashlib.sha256(file.read_bytes()).hexdigest() for file, relative in sorted(files, key=lambda x: x[1])})
    expected_state = {"small": "IDLE", "migrating": "MIGRATING", "switching": "SWITCHING"}[name]
    if manifest["state"]["migration_state"] != expected_state:
        raise AssertionError("Capture did not reach " + expected_state)
    RESOURCES.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(RESOURCES / (name + ".zip"), "w", zipfile.ZIP_DEFLATED, compresslevel=9) as output:
        for file, relative in sorted(files, key=lambda x: x[1]):
            output.writestr(relative, file.read_bytes())
        output.writestr("fixture.json", json.dumps(manifest, indent=2) + "\n")
    (BASE / (name + ".json")).write_text(json.dumps(manifest, indent=2) + "\n")


def main():
    if BASE.exists():
        raise FileExistsError("Capture output already exists: " + str(BASE))
    BASE.mkdir(parents=True)
    subprocess.run(["git", "worktree", "add", "--detach", str(RELEASE), "v0.3.0"], cwd=REPO, check=True)
    try:
        revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=RELEASE, text=True).strip()
        if not revision.startswith("76871d924"):
            raise AssertionError("v0.3.0 no longer points to the requested release commit")
        env = dict(os.environ)
        env.pop("JAVA_TOOL_OPTIONS", None)
        env["JDK_JAVA_OPTIONS"] = "-Djava.io.tmpdir=" + str(BASE)
        with (BASE / "build.log").open("w") as log:
            subprocess.run(["js-gradle-slot", "./gradlew", ":modules:ui:headlessDist", ":modules:indexer-worker:installDist",
                "-x", ":modules:ui:bundleSidecarResources", "-PskipWebBuild=true", "--no-configuration-cache", "--continue"], cwd=RELEASE,
                env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
        for directory in ("home", "data", "jvm-tmp", "roots/alpha", "roots/beta", "orphan"):
            (CAPTURE / directory).mkdir(parents=True, exist_ok=True)
        for root in ("alpha", "beta"):
            for index in range(15):
                (CAPTURE / f"roots/{root}/file-{index:02}.txt").write_text(
                    f"JustSearch authentic release fixture {root} {index}. Searchable nebula orchard.\n")
        (CAPTURE / "explicit.txt").write_text("Explicitly added file outside all watched roots: lunar apricot.\n")
        start()
        request("/api/settings/v2", {"ui": {"theme": "system"}})
        for root in ("alpha", "beta"):
            request("/api/indexing/roots", {"path": "/fixture/roots/" + root})
        request("/api/knowledge/ingest", {"paths": ["/fixture/explicit.txt"]})
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            status = request("/api/knowledge/status")
            if status.get("activeIndexedDocuments", 0) >= 36 and status.get("pendingJobs") == 0:
                break
            time.sleep(0.1)
        else:
            raise TimeoutError("Release root indexing")
        (BASE / "indexed-status.json").write_text(json.dumps(status, indent=2))
        os.killpg(PROCESS.pid, signal.SIGSTOP)
        subprocess.run(namespace() + ["java", "-cp", "/release/modules/indexer-worker/build/install/indexer-worker/lib/*",
            "/capture-tools/SeedRelease030Queue.java"], env=environment(), check=True)
        kill()
        archive("small")
        start()
        request("/api/indexing/migration/start", {"reason": "manual"})
        request("/api/indexing/migration/pause", {"reason": "fixture capture"})
        kill()
        archive("migrating")
        start()
        request("/api/indexing/migration/cutover?forceSwitching=true", {})
        request("/api/knowledge/ingest", {"paths": ["/fixture/roots/alpha/pending.txt"]})
        manifest = json.loads((CAPTURE / "data/runtime/manifest.json").read_text())
        subprocess.run(namespace() + ["java", "-cp", "/release/modules/indexer-worker/build/install/indexer-worker/lib/*",
            "/capture-tools/SeedRelease030Queue.java", str(manifest["worker"]["grpcPort"])], env=environment(), check=True)
        kill()
        archive("switching")
        shutil.copyfile(RELEASE / "SSOT/catalogs/fields.v1.json", RESOURCES / "fields.v1.json")
    finally:
        kill()
        subprocess.run(["git", "worktree", "remove", "--force", str(RELEASE)], cwd=REPO, check=True)
    print("Captured archives:", sum(p.stat().st_size for p in RESOURCES.glob("*.zip")), "bytes")
    print("Evidence/native scratch retained at", BASE)


if __name__ == "__main__":
    main()
