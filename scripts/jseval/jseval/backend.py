"""Backend lifecycle management — start, wait, stop (item 1)."""

from __future__ import annotations

import dataclasses
import logging
import os
import shutil
import subprocess
import sys
import time
import threading
import io
import importlib.util
import contextlib
from pathlib import Path

import httpx

from jsharness import run_register
from ._paths import REPO_ROOT, shared_models_dir

log = logging.getLogger(__name__)

_DEFAULT_PORT = 33221
_HEALTH_POLL_SEC = 2.0
_HEALTH_TIMEOUT_SEC = 120.0
_LLM_HEALTH_TIMEOUT_SEC = 240.0  # 369: LLM model loading adds significant time

# Lane F: dev-runner owns registered-child cleanup. --clean still fails closed.


# Tempdoc 782 §I: `llm=True` cannot work through this entry point, and used to
# discover that only after burning the full inference deadline (~240s) and failing
# with the generic "inference stayed offline". The chain, verified at the source:
#
#   `_boot_and_wait` launches the installed eval Engine; its shared launch definition
#   also supplies `:modules:ui:runHeadlessEval` and pins settings to IN_MEMORY ->
#   `UiSettingsStore.save()` is a silent no-op and `load()` always returns fresh
#   defaults in that mode (`UiSettingsStore.java:43-68`) -> `-Pllm=true` only sets
#   `JUSTSEARCH_AI_AUTOSTART_ENABLED` (`build.gradle.kts:2200-2205`), which seeds
#   desired state via `InferenceWiring.seedAutostartSpec` ->
#   `RuntimeSpecStore.seedAutostartIfUnset()` -> `setChatEnabled(true)` -> the
#   no-op save. `RuntimeReconciler` then reads `chatEnabled` back as unset and
#   never converges the engine UP; the REST equivalent returns
#   `409 SETTINGS_READ_ONLY` (`SettingsController.java:108`).
#
# So this is a deterministic dead end, not a flake: fail CLOSED at the call, with
# the recipe the 782 campaign actually shipped on.
_EVAL_MODE_LLM_ERROR = (
    "start_backend(llm=True) cannot bring inference up: this entry point boots "
    "the installed eval Engine under the shared runHeadlessEval contract, which pins "
    "justsearch.ui.settings.mode=IN_MEMORY (read-only settings). -Pllm=true only "
    "seeds chatEnabled through RuntimeSpecStore, that write is silently discarded "
    "in IN_MEMORY mode, and RuntimeReconciler therefore never starts llama-server "
    "(the REST equivalent is 409 SETTINGS_READ_ONLY). Waiting for readiness would "
    "always end in 'inference stayed offline'.\n"
    "Use the out-of-band recipe instead (tempdoc 782 §I; "
    "scripts/jseval/782-run-2026-07-28-hero/incident-ledger.md):\n"
    "  1. start_backend(..., llm=False) as usual;\n"
    "  2. run llama-server yourself on the configured port 8081, mirroring "
    "LlamaServerOps.startLlamaServer: "
    "`llama-server --jinja --host 127.0.0.1 --metrics --port 8081 -c 4096 -ngl 99 "
    "-m <model.gguf>`;\n"
    "  3. reach it through the Head's unconditional /v1 proxy "
    "(OpenAiCompatController.proxy has no engine-mode gate), i.e. "
    "http://127.0.0.1:<api-port>/v1/chat/completions, and assert readiness on "
    "/v1/models before use (the serve-judge pattern)."
)


class EvalModeLlmUnsupportedError(RuntimeError):
    """Raised by :func:`start_backend` for the structurally impossible ``llm=True``
    request under the eval-mode contract (tempdoc 782 §I)."""


@dataclasses.dataclass
class BackendInfo:
    """Return value from start_backend() -- includes the data_dir for log access.

    ``cache_outcome`` (tempdoc 751 WP3) is ``None`` unless the input-addressed
    index cache was engaged for this boot; when engaged it carries the adopt-side
    outcome (``mode``/``entry``/``detail``/``selector_key``/
    ``would_have_hit_scoped_pin``) the caller threads into run provenance and the
    publish hook. ``spawn_env`` is the exact resolved env the backend was spawned
    with -- the publish step needs it to compute the live identity against the same
    models/repo roots the boot used.
    """

    proc: subprocess.Popen
    data_dir: Path
    cache_outcome: dict | None = None
    spawn_env: dict | None = None


def start_backend(
    *,
    repo_root: Path | None = None,
    data_dir: Path | None = None,
    port: int = _DEFAULT_PORT,
    clean: bool = False,
    env_overrides: dict[str, str] | None = None,
    health_timeout_sec: float | None = None,
    llm: bool = False,
    index_cache_mode: str = "off",
    corpus_dir: Path | None = None,
    dataset_name: str | None = None,
    pin_selector_key: str | None = None,
    raw_context=None,
    startup_admission: bool = True,
    prepared_only: bool = False,
) -> BackendInfo:
    """Build and launch the installed eval Engine, then wait for backend readiness.

    Returns a BackendInfo with the Popen handle and data directory path.
    llm=True is refused because the shared eval contract uses read-only settings.
    Uses a single deadline for all readiness checks.

    ``startup_admission=False`` is for a quality build whose engine startup and
    lifetime already belong to its pausable work lock. Nested build tools still
    acquire their own admission; no admission token is supplied to bypass them.

    health_timeout_sec=None resolves JSEVAL_HEALTH_TIMEOUT_SEC from the
    environment before falling back to the 120s default — the only lever that
    reaches call sites that don't thread the kwarg (707 gate runs died at the
    fixed boundary twice, cause undiagnosed; raise it without editing every CLI).

    ``index_cache_mode`` (tempdoc 751 WP3) is ``"off"`` by default -- the behavior
    is then byte-identical to today (no cache seam is touched). When ``"on"`` AND
    ``clean`` is set, an input-addressed cache entry may be adopted before the
    fresh build (two-phase adopt->confirm, sec M.2): a confirmed hit boots on a copied
    data dir, any doubt falls through to today's fresh build. ``corpus_dir`` /
    ``dataset_name`` (when known) are the corpus AXIS: they resolve through the one
    shared ``index_identity.resolve_corpus_axis`` so this adopter and the warm
    publisher bind identical corpus components (751 P.5 finding 2).

    ``llm=True`` raises :class:`EvalModeLlmUnsupportedError` immediately -- the eval
    contract this function boots under makes engine autostart structurally
    impossible (see :data:`_EVAL_MODE_LLM_ERROR` for the verified chain and the
    out-of-band recipe). ``llm=False`` (the default) is untouched.
    """
    if raw_context is not None:
        from .raw_corpus_manifest import validate_raw_corpus_context

        validate_raw_corpus_context(
            raw_context,
            env_overrides,
            expected_dataset=dataset_name,
            explicit_dir=corpus_dir,
        )
        if pin_selector_key is not None:
            raise ValueError(
                "--pin-index-selector-key cannot bypass strict raw corpus identity"
            )
    if llm:
        raise EvalModeLlmUnsupportedError(_EVAL_MODE_LLM_ERROR)
    if health_timeout_sec is None:
        raw_timeout = os.environ.get("JSEVAL_HEALTH_TIMEOUT_SEC", "")
        try:
            health_timeout_sec = float(raw_timeout) if raw_timeout else _HEALTH_TIMEOUT_SEC
        except ValueError as exc:
            raise ValueError(
                f"JSEVAL_HEALTH_TIMEOUT_SEC must be numeric, got {raw_timeout!r}"
            ) from exc
    resolved_root = repo_root or REPO_ROOT
    # Honor JUSTSEARCH_DATA_DIR pre-set by callers (e.g. a driver pointing every
    # sub-run at the same Worker data dir).
    # Only fall back to the default when neither arg nor env was supplied.
    if data_dir is None:
        env_data_dir = os.environ.get("JUSTSEARCH_DATA_DIR")
        data_dir = Path(env_data_dir) if env_data_dir else None
    # Tempdoc 400 §23.8 D-2: if the resulting path is relative, resolve it
    # against REPO_ROOT — that's the resolution frame the Gradle Java
    # subprocess uses (cwd=resolved_root). Historically, Python resolved
    # against its own CWD (which becomes scripts/jseval when calibrate
    # spawns jseval run), so rmtree targeted a different absolute path
    # than where Java actually wrote. Single frame, no mismatch.
    if data_dir is not None and not data_dir.is_absolute():
        data_dir = (resolved_root / data_dir).resolve()
    resolved_data = data_dir or (resolved_root / "tmp" / "headless-eval-data")
    gradlew_name = "gradlew.bat" if sys.platform == "win32" else "gradlew"
    gradlew = resolved_root / gradlew_name

    if prepared_only:
        if clean or index_cache_mode != "off":
            raise ValueError("prepared-only start cannot clean or build/adopt an index")
        require_prepared(resolved_root, resolved_data)
    if not prepared_only and not gradlew.is_file():
        raise FileNotFoundError(f"{gradlew_name} not found at {gradlew}")

    if clean and resolved_data.is_dir():
        # Tempdoc 716: nothing durable lives in the backend data dir — jseval's own
        # durable artifacts are filed under the jseval-owned data root
        # (_paths.DEFAULT_JSEVAL_DATA_DIR), so the tempdoc-400 protected-set
        # carve-out is retired and --clean wipes the whole dir. Fail-closed
        # semantics (verify + retry + hard error on survivors, tempdoc 711
        # item 4) are unchanged.
        log.info("Cleaning data directory: %s", resolved_data)
        _clean_data_dir(resolved_data)

    env = os.environ.copy()
    env["JUSTSEARCH_DATA_DIR"] = str(resolved_data)
    env["JUSTSEARCH_API_PORT"] = str(port)
    if env_overrides:
        env.update(env_overrides)

    # Tempdoc 644 Axis 1: when launched from a git worktree, the worktree's own models/
    # holds only LFS pointer files, so reranker/dense/SPLADE discovery silently fails and
    # the cross-encoder turns off → wrong-but-plausible hybrid numbers. Default
    # JUSTSEARCH_MODELS_DIR to the MAIN checkout's models (mirrors dev-runner.cjs:428-434).
    # Lowest precedence: a caller/env/run-config JUSTSEARCH_MODELS_DIR always wins.
    if not env.get("JUSTSEARCH_MODELS_DIR"):
        shared_models = shared_models_dir()
        if shared_models is not None:
            env["JUSTSEARCH_MODELS_DIR"] = str(shared_models)
            log.info("Resolved JUSTSEARCH_MODELS_DIR=%s (shared models)", shared_models)

    # Tempdoc 751 WP3: index-cache adopt path. The gate is `index_cache_mode ==
    # "on" AND clean` -- off (the default) means _run_with_cache is never entered
    # and the boot below is byte-identical to today. When on, _run_with_cache
    # owns the boot (adopt->confirm->maybe-fallthrough-to-fresh) and returns the
    # healthy proc + the adopt-side outcome.
    if index_cache_mode == "on" and clean:
        proc, cache_outcome = _run_with_cache(
            resolved_root=resolved_root,
            resolved_data=resolved_data,
            gradlew=gradlew,
            env=env,
            port=port,
            llm=llm,
            health_timeout_sec=health_timeout_sec,
            corpus_dir=corpus_dir,
            dataset_name=dataset_name,
            pin_selector_key=pin_selector_key,
            **({"raw_context": raw_context} if raw_context is not None else {}),
        )
        return BackendInfo(
            proc=proc, data_dir=resolved_data,
            cache_outcome=cache_outcome, spawn_env=env,
        )

    proc = _boot_and_wait(
        resolved_root=resolved_root,
        resolved_data=resolved_data,
        gradlew=gradlew,
        env=env,
        port=port,
        llm=llm,
        health_timeout_sec=health_timeout_sec,
        **({"startup_admission": False} if not startup_admission else {}),
        **({"prepared_only": True} if prepared_only else {}),
    )
    return BackendInfo(proc=proc, data_dir=resolved_data, spawn_env=env)


def _install_distribution(resolved_root: Path, env: dict[str, str]) -> None:
    """Build the distribution before the lifetime helper or runner is needed."""
    command = ["node", str(resolved_root / "scripts/lib/tool-invoker.cjs"), "gradle",
               ":modules:ui:installDist", "-PskipWebBuild=true", "--quiet", "--no-daemon"]
    if env.get("JSEVAL_GRADLE_SLOT"):
        # Queue without invalidating the usable distribution. The child takes
        # build admission and changes the receipt only after the slot is held.
        script = ("import os,sys; from pathlib import Path; from jseval.backend import "
                  "_install_distribution_admitted; "
                  "_install_distribution_admitted(Path(sys.argv[1]), dict(os.environ), sys.argv[2:])")
        result = subprocess.run([env["JSEVAL_GRADLE_SLOT"], "-w", "3600", sys.executable,
                                 "-c", script, str(resolved_root), *command],
                                cwd=str(resolved_root), env=env, check=False)
        if result.returncode != 0:
            raise RuntimeError(f"Backend installDist failed (exit {result.returncode})")
        return
    _install_distribution_admitted(resolved_root, env, command)


def _install_distribution_admitted(resolved_root: Path, env: dict[str, str], command: list[str]) -> None:
    identity = None
    if (resolved_root / ".git").exists():
        try:
            identity = source_identity(resolved_root)
        except (OSError, subprocess.CalledProcessError):
            log.warning("Cannot attest install distribution source; prepared-only start will refuse it")
    dist = resolved_root / "modules/ui/build/install/ui"
    receipt = dist / ".jseval-source.json"
    # Admission covers receipt invalidation, every distribution write, and the
    # replacement attestation. Abrupt death during Gradle must leave no receipt.
    with _machine_admission(env) as admitted:
        receipt.unlink(missing_ok=True)
        result = subprocess.run(command, cwd=str(resolved_root), env=admitted, check=False)
        if result.returncode != 0:
            raise RuntimeError(f"Backend installDist failed (exit {result.returncode})")
        if (identity is not None and source_identity(resolved_root) == identity
                and (dist / "bin" / ("ui.bat" if sys.platform == "win32" else "ui")).is_file()):
            import json
            import tempfile
            with tempfile.NamedTemporaryFile(mode="w", dir=dist, delete=False) as out:
                pending = Path(out.name)
                try:
                    out.write(json.dumps(identity))
                    out.close()
                    pending.replace(receipt)
                finally:
                    pending.unlink(missing_ok=True)
        else:
            log.warning("Install distribution source unavailable or changed; prepared-only start will refuse it")


def source_identity(root: Path) -> tuple[str, str | None]:
    """Pin HEAD and all nonignored working bytes, including untracked files/deletions."""
    import hashlib
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root).decode().strip()
    diff = subprocess.check_output(["git", "diff", "HEAD", "--binary", "--no-ext-diff"], cwd=root)
    raw = subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard", "-z"], cwd=root)
    digest = hashlib.sha256(diff)
    for name in sorted(filter(None, raw.split(b"\0"))):
        p = root / os.fsdecode(name)
        digest.update(name + b"\0")
        digest.update(os.fsencode(os.readlink(p)) if p.is_symlink() else p.read_bytes())
    return commit, digest.hexdigest() if diff or raw else None


def require_prepared(root: Path, data: Path | None = None) -> None:
    """Read-only preflight. Never call Gradle or recover/rebuild a missing index."""
    import json
    dist = root / "modules/ui/build/install/ui"
    if not (dist / "bin" / ("ui.bat" if sys.platform == "win32" else "ui")).is_file():
        raise RuntimeError("not ready: install distribution missing; prepare the index outside the scoring window")
    try:
        receipt = json.loads((dist / ".jseval-source.json").read_text())
        if receipt != list(source_identity(root)):
            raise ValueError("candidate differs from installed distribution")
    except (OSError, ValueError) as e:
        raise RuntimeError(f"not ready: install distribution identity unavailable or stale ({e}); rebuild outside the scoring window") from e
    if data is not None and not any((data / "index/default").glob("**/segments_*")):
        raise RuntimeError("not ready: prepared index missing; prepare it outside the scoring window")


def _machine_admission(env: dict[str, str]):
    """Heavy machine admission for the launch, in the admission directory of ``env``.

    Fail-open: if the admission tool cannot be loaded (or its directory is unusable,
    handled inside ``heavy``) the launch proceeds with one warning.
    """
    if sys.platform != "linux":
        return contextlib.nullcontext(env)
    source = Path(__file__).resolve().parents[2] / "dev" / "admission.py"
    try:
        spec = importlib.util.spec_from_file_location("js_admission", source)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
    except OSError as error:
        print(f"admission: machine admission unavailable ({error}); jseval backend start runs without admission",
              file=sys.stderr, flush=True)
        return contextlib.nullcontext(env)
    return module.heavy("jseval backend start", env)


def _boot_and_wait(
    *,
    resolved_root: Path,
    resolved_data: Path,
    gradlew: Path,
    env: dict[str, str],
    port: int,
    llm: bool,
    health_timeout_sec: float,
    startup_admission: bool = True,
    prepared_only: bool = False,
) -> subprocess.Popen:
    scope = _machine_admission(env) if startup_admission else contextlib.nullcontext(env)
    with scope as admitted:
        return _boot_admitted(
            resolved_root=resolved_root, resolved_data=resolved_data, gradlew=gradlew,
            env=admitted, port=port, llm=llm, health_timeout_sec=health_timeout_sec,
            **({"prepared_only": True} if prepared_only else {}),
        )


def _boot_admitted(
    *, resolved_root: Path, resolved_data: Path, gradlew: Path,
    env: dict[str, str], port: int, llm: bool, health_timeout_sec: float,
    prepared_only: bool = False,
) -> subprocess.Popen:
    """Build installDist, start the bound persistent runner and wait for readiness.

    Fresh boots and index-cache adoption share this one launch/health boundary.
    """
    if sys.platform == "linux" and threading.current_thread() is not threading.main_thread():
        raise RuntimeError("Linux eval backends must be launched from the process-lifetime main thread")
    if prepared_only:
        require_prepared(resolved_root, resolved_data)
    else:
        _install_distribution(resolved_root, env)
    cmd = ["node", str(resolved_root / "scripts" / "dev" / "eval-runner.cjs")]
    cmd = bind_command(resolved_root, cmd)

    log.info("Starting backend: %s (port=%d, data=%s)", " ".join(cmd), port, resolved_data)

    proc = subprocess.Popen(
        cmd,
        cwd=str(resolved_root),
        env=env,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        creationflags=subprocess.CREATE_NEW_PROCESS_GROUP if sys.platform == "win32" else 0,
    )

    # Drain continuously during health polling (and afterwards); a full pipe must not freeze boot.
    def drain_output():
        for line in iter(proc.stdout.readline, b""):
            log.debug("backend: %s", line.decode("utf-8", errors="replace").rstrip())
    if isinstance(proc.stdout, io.BufferedIOBase):
        threading.Thread(target=drain_output, name="jseval-backend-output", daemon=True).start()
        proc._justsearch_eval_runner = True

    # Tempdoc 844 D3: declare this backend to the other dev-stack lifecycle, which otherwise
    # cannot see it (§6.1 — an invisible runHeadlessEval already contaminated a measurement).
    # Registered at SPAWN, not after health: the JVM holds ports, the data dir and the GPU from
    # this moment, and that is exactly the window a neighbour's "is the machine free?" check must
    # not miss. The record makes no liveness claim, so a still-booting backend reads as
    # "registered, process alive, not yet answering" rather than as up. Best-effort by design —
    # see run_register's failure policy.
    run_register.register_backend(
        pid=proc.pid,
        port=port,
        repo_root=resolved_root,
        data_dir=resolved_data,
        inference_requested=llm,
    )

    # 369: Single deadline for all readiness checks (index health + inference).
    effective_timeout = max(health_timeout_sec, _LLM_HEALTH_TIMEOUT_SEC) if llm else health_timeout_sec
    deadline = time.monotonic() + effective_timeout
    base_url = f"http://127.0.0.1:{port}"
    log.info("Waiting for backend to become healthy (timeout=%ds, llm=%s)...", effective_timeout, llm)

    if not _wait_for_health(base_url, deadline, proc):
        stop_backend(proc, data_dir=resolved_data)
        raise RuntimeError(
            f"Backend did not become healthy within {effective_timeout}s"
        )

    log.info("Backend healthy on port %d (PID=%d)", port, proc.pid)

    # 369: When LLM is requested, also wait for inference to come online.
    # Shares the same deadline — no independent second timeout.
    if llm:
        remaining = deadline - time.monotonic()
        log.info("Waiting for LLM inference (%.0fs remaining)...", remaining)
        diag = _wait_for_inference(base_url, deadline, proc)
        if diag is not None:
            stop_backend(proc, data_dir=resolved_data)
            raise RuntimeError(
                f"LLM inference did not become available: {diag}"
            )
        log.info("LLM inference available")

    return proc


def bind_command(root: Path, command: list[str]) -> list[str]:
    """Bind an owned command to this caller's lifetime (Linux, main thread only)."""
    if sys.platform != "linux":
        return command
    import json
    import platform
    architecture = {"amd64": "x86_64", "x64": "x86_64"}.get(platform.machine().lower(), platform.machine().lower())
    spec = json.loads((root / "contracts/native-targets/spec.v1.json").read_text())
    helper = root / "modules/ui/build/install/ui" / spec["targets"][f"linux-{architecture}"]["paths"]["bound-exec"]
    return [str(helper), "exec", str(os.getpid()), "--", *command]


def _run_with_cache(
    *,
    resolved_root: Path,
    resolved_data: Path,
    gradlew: Path,
    env: dict[str, str],
    port: int,
    llm: bool,
    health_timeout_sec: float,
    corpus_dir: Path | None,
    dataset_name: str | None = None,
    pin_selector_key: str | None = None,
    raw_context=None,
) -> tuple[subprocess.Popen, dict]:
    """Two-phase adopt (tempdoc 751 sec M.2); returns (healthy proc, cache_outcome).

    Fail-closed for adoption: a null selector, a lookup miss, an adopt error, or a
    confirm failure all fall through to the identical fresh build today's callers
    get (via :func:`_boot_and_wait`). Only a confirmed hit skips the rebuild.

    ``pin_selector_key`` (tempdoc 768 item 5): when set, bypass ``compute_selector``
    and look the pinned key up directly — the 763 §F forensic-replay path needs to
    adopt a SPECIFIC historical index-cache entry even after HEAD advances past the
    campaign commit (``compute_selector`` would resolve a different, current key,
    which 763 §F had to monkeypatch around). All miss/adopt/confirm handling below
    is identical; only the key derivation changes.
    """
    from . import index_cache, index_identity

    if raw_context is not None:
        from .raw_corpus_manifest import validate_raw_corpus_context

        validate_raw_corpus_context(
            raw_context,
            env,
            expected_dataset=dataset_name,
            explicit_dir=corpus_dir,
        )

    if raw_context is not None and pin_selector_key is not None:
        raise ValueError(
            "--pin-index-selector-key cannot bypass strict raw corpus identity"
        )

    # Pin the live-identity repo root to the checkout the backend actually boots
    # from (cwd=resolved_root) so the selector (uses resolved_root) and the live
    # identity (uses spawn_env's JUSTSEARCH_REPO_ROOT) agree. Only mutated on the
    # opt-in on-path -- the off-path env stays byte-identical to today.
    env["JUSTSEARCH_REPO_ROOT"] = str(resolved_root)

    def _boot_fresh() -> subprocess.Popen:
        return _boot_and_wait(
            resolved_root=resolved_root, resolved_data=resolved_data,
            gradlew=gradlew, env=env, port=port, llm=llm,
            health_timeout_sec=health_timeout_sec,
        )

    # tempdoc 768 item 5: an explicit historical selector-key pin bypasses the
    # corpus-axis resolution and compute_selector entirely (the 763 §F replay
    # need). Otherwise resolve the key from the corpus axis as before.
    if pin_selector_key is not None:
        selector_key = pin_selector_key
    elif corpus_dir is None and not dataset_name:
        # Without a corpus axis the selector key would collide across corpora that
        # share a config: adoption stays safe (confirm's count/canary checks catch
        # it) but every cross-corpus run would thrash the same entry slot. Fail
        # closed when there is no axis intent at all (no --corpus-dir AND no dataset).
        # A resolvable-but-bad axis (subdir garbage, missing corpus.jsonl) is reported
        # by compute_selector's resolve_corpus_axis below.
        log.warning(
            "Index cache requested but no corpus axis (no --corpus-dir, no dataset) "
            "-- fresh build."
        )
        return _boot_fresh(), {
            "mode": "disabled:no-corpus-axis",
            "entry": None,
            "detail": {"reason": "no corpus dir and no dataset name before backend start"},
            "selector_key": None,
            "would_have_hit_scoped_pin": None,
        }
    else:
        selector = index_identity.compute_selector(
            resolved_root, corpus_dir, env, dataset_name=dataset_name,
            **({"raw_context": raw_context} if raw_context is not None else {}),
        )
        if selector.key is None:
            # Finding 1: this used to log at INFO -- a chain that passed the exploded
            # corpus-dir subdir lost ALL caching with only an easily-missed INFO line.
            # Requested-but-disabled is a WARNING now, naming the remedy verbatim.
            log.warning(
                "Index cache requested but disabled for this run (%s) -- fresh build.",
                selector.unavailable_reason,
            )
            return _boot_fresh(), {
                "mode": f"disabled:{selector.unavailable_reason}",
                "entry": None,
                "detail": {"reason": selector.unavailable_reason},
                "selector_key": None,
                "would_have_hit_scoped_pin": None,
            }
        selector_key = selector.key

    entry = index_cache.lookup(selector_key)
    if entry is None:
        log.info("Index cache miss (no entry for selector) -- fresh build.")
        return _boot_fresh(), {
            "mode": "miss:selector",
            "entry": None,
            "detail": {},
            "selector_key": selector_key,
            "would_have_hit_scoped_pin": None,
        }

    # Candidate hit: wipe, ensure an empty dir, adopt the entry's data into it.
    try:
        if resolved_data.is_dir():
            _clean_data_dir(resolved_data)
        resolved_data.mkdir(parents=True, exist_ok=True)
        index_cache.adopt(entry, resolved_data)
    except Exception as exc:
        log.warning(
            "Index cache adopt of %s failed (%s) -- wiping and falling through to "
            "a fresh build.", entry.dir.name, exc,
        )
        if resolved_data.is_dir():
            _clean_data_dir(resolved_data)
        return _boot_fresh(), {
            "mode": "miss:adopt-error",
            "entry": entry.dir.name,
            "detail": {"error": f"{type(exc).__name__}: {exc}"},
            "selector_key": selector_key,
            "would_have_hit_scoped_pin": None,
        }

    # Boot on the adopted dir, then let the running backend confirm its identity.
    proc = _boot_and_wait(
        resolved_root=resolved_root, resolved_data=resolved_data,
        gradlew=gradlew, env=env, port=port, llm=llm,
        health_timeout_sec=health_timeout_sec,
    )
    base_url = f"http://127.0.0.1:{port}"
    confirm = index_identity.confirm_adoption(base_url, entry.doc, resolved_data, spawn_env=env)
    if confirm.ok:
        index_cache.touch(entry)
        log.info("Index cache adopted entry %s (confirmed live).", entry.dir.name)
        return proc, {
            "mode": "adopted",
            "entry": entry.dir.name,
            "detail": {"checks": confirm.checks},
            "selector_key": selector_key,
            "would_have_hit_scoped_pin": None,
        }

    # Confirm failed: stop the wrongly-booted backend, wipe, fresh build.
    log.warning(
        "Index cache adoption of %s NOT confirmed (%s) -- stopping, wiping, fresh build.",
        entry.dir.name, confirm.failures,
    )
    stop_backend(proc, data_dir=resolved_data)
    if resolved_data.is_dir():
        _clean_data_dir(resolved_data)
    return _boot_fresh(), {
        "mode": "miss:confirm",
        "entry": entry.dir.name,
        "detail": {"failures": confirm.failures, "checks": confirm.checks},
        "selector_key": selector_key,
        "would_have_hit_scoped_pin": _scoped_pin_would_hit(confirm),
    }


def _scoped_pin_would_hit(confirm) -> bool:
    """sec M.5 instrumentation: would a scoped pin (ignoring git_sha/dirt) have hit?

    True iff the ONLY differing live-key components are ``git_sha`` /
    ``dirty_state_hash`` (a within-chain edit that a scoped index-shaping pin
    would have tolerated); False if any model/config/corpus component differed or
    the diff is empty/unavailable.
    """
    ident = confirm.checks.get("identity") or {}
    diffs = ident.get("diffs")
    if not diffs:
        return False
    scoped_ignorable = {"git_sha", "dirty_state_hash"}
    for d in diffs:
        comp = d.split(":", 1)[0]
        if comp.startswith("identity."):
            comp = comp[len("identity."):]
        if comp not in scoped_ignorable:
            return False
    return True


def _attempt_wipe(resolved_data: Path) -> list[Path]:
    """Delete every top-level entry of resolved_data.

    Returns the children that failed to delete instead of swallowing the
    error — tempdoc 711 item 4's root cause was a bare ``except OSError:
    pass`` here, which let a Worker holding index/ open produce a silent
    no-op wipe. (The tempdoc-400 protected-set parameter is retired —
    tempdoc 716: calibration state no longer lives in this dir.)
    """
    failures: list[Path] = []
    for child in resolved_data.iterdir():
        try:
            if child.is_dir():
                shutil.rmtree(child)
            else:
                child.unlink()
        except OSError as exc:
            log.debug("Failed to delete %s during clean wipe: %s", child, exc)
            failures.append(child)
    return failures


def _clean_data_dir(resolved_data: Path) -> None:
    """Wipe every entry, retry deletion failures, and fail closed on survivors.

    Lane F F-4 removes the separate process sweep; registered-child cleanup
    belongs to dev-runner. A failed wipe must still prevent the next run.
    """
    failures = _attempt_wipe(resolved_data)
    if failures:
        log.warning(
            "Clean wipe hit %d failure(s) on first pass (%s) - retrying %s",
            len(failures), ", ".join(p.name for p in failures), resolved_data,
        )
        _attempt_wipe(resolved_data)

    survivors = sorted(child.name for child in resolved_data.iterdir())
    if survivors:
        raise RuntimeError(
            f"jseval --clean failed to wipe {resolved_data}: survivor(s) remain "
            f"after wipe + retry: {', '.join(survivors)}. "
            "A process may still hold a handle inside this directory; "
            "aborting rather than proceeding on a dirty data dir "
            "(tempdoc 711 item 4 - --clean is fail-closed)."
        )


def stop_backend(proc: subprocess.Popen, data_dir: Path | None = None) -> None:
    """Stop the Engine process tree and retire its foreign-run record.

    Requests ordered cleanup from the persistent runner. data_dir is retained for caller
    compatibility; the runner's recorded job, rather than a process scan, owns helper cleanup.
    Unregistering by proc.pid also covers boot failures before registration.
    """
    if proc.poll() is not None:
        log.info("Backend already exited (rc=%d)", proc.returncode)
        if getattr(proc, "_justsearch_eval_runner", False) is True and proc.returncode != 0:
            raise RuntimeError(f"Eval runner cleanup failed (exit {proc.returncode}); retaining registration")
    else:
        pid = proc.pid
        log.info("Stopping backend (PID=%d)...", pid)

        # Popen retains the Windows process handle opened at this fixture's start.
        # The Engine's job owns helper cleanup; no PID tree or name-based kill.
        if getattr(proc, "_justsearch_eval_runner", False) is True:
            try:
                proc.stdin.write(b"stop\n")
                proc.stdin.flush()
                proc.wait(timeout=30)
            except (BrokenPipeError, subprocess.TimeoutExpired):
                proc.terminate()
                proc.wait(timeout=10)
            if proc.returncode != 0:
                raise RuntimeError(f"Eval runner cleanup failed (exit {proc.returncode}); retaining registration")
        else:
            proc.terminate()
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait(timeout=10)

        log.info("Backend stopped")

    run_register.unregister_backend(proc.pid)


def _wait_for_health(
    base_url: str,
    deadline: float,
    proc: subprocess.Popen,
) -> bool:
    """Poll /api/status until the backend responds with indexAvailable=true.

    Args:
        deadline: absolute monotonic time after which to give up.
    """
    while time.monotonic() < deadline:
        # Check if process died
        if proc.poll() is not None:
            log.error("Backend process exited prematurely (rc=%d)", proc.returncode)
            return False

        try:
            with httpx.Client(base_url=base_url, timeout=5) as client:
                resp = client.get("/api/status")
                resp.raise_for_status()
                data = resp.json()
                if data.get("indexAvailable"):
                    return True
                w = data.get("worker") or {}
                c = w.get("core") or {}
                log.debug("Backend responding but not ready: indexState=%s",
                          c.get("indexState"))
        except Exception:
            pass

        time.sleep(_HEALTH_POLL_SEC)

    return False


def _wait_for_inference(
    base_url: str,
    deadline: float,
    proc: subprocess.Popen,
) -> str | None:
    """Poll /api/inference/status until inference mode is 'online' (369).

    Args:
        deadline: absolute monotonic time after which to give up.

    Returns:
        None on success, or a diagnostic string explaining why it failed.
    """
    last_mode = "unknown"
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            return f"backend process exited (rc={proc.returncode})"

        try:
            with httpx.Client(base_url=base_url, timeout=5) as client:
                resp = client.get("/api/inference/status")
                resp.raise_for_status()
                data = resp.json()
                last_mode = data.get("mode", "offline")
                if last_mode == "online":
                    return None  # success
                if last_mode == "transitioning":
                    log.debug("Inference mode transitioning (model loading)...")
                else:
                    log.debug("Inference mode: %s", last_mode)
        except Exception:
            pass

        time.sleep(_HEALTH_POLL_SEC)

    if last_mode == "offline":
        return (
            "inference stayed offline (autostart may have failed silently). "
            "Check app.log for 'Failed to start llama-server' warnings. "
            "Common causes: JUSTSEARCH_SERVER_EXE not set, missing DLLs, "
            "or no .gguf model in models/"
        )
    if last_mode == "transitioning":
        return (
            "inference still transitioning (model load exceeded timeout). "
            "The model may be too large for available resources, or the "
            "health check timeout needs to be increased"
        )
    return f"inference mode was '{last_mode}' at timeout"
