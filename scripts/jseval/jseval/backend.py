"""Backend lifecycle management — start, wait, stop (item 1)."""

from __future__ import annotations

import dataclasses
import datetime
import logging
import os
import shutil
import subprocess
import time
from pathlib import Path

import httpx
import psutil

from . import run_register
from ._paths import REPO_ROOT, shared_models_dir

log = logging.getLogger(__name__)

_DEFAULT_PORT = 33221
_HEALTH_POLL_SEC = 2.0
_HEALTH_TIMEOUT_SEC = 120.0
_LLM_HEALTH_TIMEOUT_SEC = 240.0  # 369: LLM model loading adds significant time

# Tempdoc 711 item 4: fail-closed --clean.
#
# History: at the time this was written, the Worker JVM was spawned by the Head's
# ProcessBuilder (WorkerSpawner.java) as a grandchild of the `gradlew.bat runHeadlessEval`
# process jseval starts. `taskkill /PID <head-pid> /T /F` kills the Gradle process tree, but
# the Worker JVM had been observed to survive it (orphaned rather than reparented into the
# killed tree), holding the Lucene index open and able to silently rewrite watched_roots.json —
# which then made the *next* run's ingest an idempotent no-op while stale docs serve.
# Since lane F item A6, index execution runs inside the Head JVM rather than a separate Worker
# JVM, so the grandchild-process/orphaning mechanism described above no longer applies as
# written; whether an equivalent stale-lock risk still exists for the merged process has not
# been verified here. `--clean` must therefore fail CLOSED: if a wipe cannot be verified
# complete, raise rather than let the run proceed on a dirty dir.
_LOCK_FILE_REL = Path("index") / "default.index.lock"
# Lane F stage A: the Worker's own log (worker.log, via its now-deleted
# logback.xml) no longer exists — index execution runs inside the merged
# Engine JVM, which writes logs/engine.log instead.
_ENGINE_LOG_REL = Path("logs") / "engine.log"
# IndexRootLock.writeOwnerMetadataBestEffort() (Java side) stamps started_at from
# Instant.now(); ProcessHandle.info().startInstant() and psutil's create_time() can
# each be off by OS scheduling/clock-resolution noise, so allow a generous skew
# before treating a PID match as a coincidental reuse.
_LOCK_PID_SKEW_SEC = 120.0
_ENGINE_LOG_TAIL_LINES = 50

# Tempdoc 782 §I: `llm=True` cannot work through this entry point, and used to
# discover that only after burning the full inference deadline (~240s) and failing
# with the generic "inference stayed offline". The chain, verified at the source:
#
#   `_boot_and_wait` always launches `:modules:ui:runHeadlessEval`, whose
#   `applyHeadlessEvalContract()` HARD-CODES `justsearch.ui.settings.mode=IN_MEMORY`
#   (`modules/ui/build.gradle.kts:2151`, not overridable by caller env) ->
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
    "`:modules:ui:runHeadlessEval`, whose eval contract pins "
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
) -> BackendInfo:
    """Start runHeadlessEval and wait for the backend to become healthy.

    Returns a BackendInfo with the Popen handle and data directory path.
    When llm=True, passes -Pllm=true to Gradle and waits for inference readiness.
    Uses a single deadline for all readiness checks.

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
    gradlew_name = "gradlew.bat" if os.name == "nt" else "gradlew"
    gradlew = resolved_root / gradlew_name

    if not gradlew.is_file():
        raise FileNotFoundError(f"{gradlew_name} not found at {gradlew}")

    if clean and resolved_data.is_dir():
        # Tempdoc 716: nothing durable lives in the backend data dir — jseval's own
        # durable artifacts are filed under the jseval-owned data root
        # (_paths.DEFAULT_JSEVAL_DATA_DIR), so the tempdoc-400 protected-set
        # carve-out is retired and --clean wipes the whole dir. Fail-closed
        # semantics (verify + orphan sweep + hard error on survivors, tempdoc 711
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
    )
    return BackendInfo(proc=proc, data_dir=resolved_data, spawn_env=env)


def _boot_and_wait(
    *,
    resolved_root: Path,
    resolved_data: Path,
    gradlew: Path,
    env: dict[str, str],
    port: int,
    llm: bool,
    health_timeout_sec: float,
) -> subprocess.Popen:
    """Spawn runHeadlessEval and wait for readiness; return the healthy Popen.

    Extracted verbatim from start_backend so the tempdoc 751 adopt path and its
    fresh-build fallthrough share ONE boot+health block (a confirm failure boots
    twice -- the fallthrough must reach the identical code a fresh build gets, not
    a divergent copy).
    """
    cmd = [
        str(gradlew),
        ":modules:ui:runHeadlessEval",
        "--no-configuration-cache",
        "--quiet",
    ]
    # 369: Pass -Pllm=true so Gradle enables autostart + longer health timeout.
    # Unreachable from `start_backend` since tempdoc 782 §I -- it fails closed on
    # `llm=True` (see `_EVAL_MODE_LLM_ERROR`) because the eval contract's read-only
    # settings store makes autostart structurally impossible. Kept intact so the
    # boot block stays correct for a caller that does not carry that contract.
    if llm:
        cmd.append("-Pllm=true")

    log.info("Starting backend: %s (port=%d, data=%s)", " ".join(cmd), port, resolved_data)

    proc = subprocess.Popen(
        cmd,
        cwd=str(resolved_root),
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        creationflags=subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0,
    )

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
    """Wipe resolved_data entirely, failing CLOSED.

    Tempdoc 711 item 4: attempts every deletion and collects failures rather
    than swallowing them. On any failure, runs the orphan-Worker sweep (the
    Worker JVM has been observed to survive the Head's process-tree kill —
    see ``_sweep_orphan_worker``) and retries. If an entry still exists
    afterward, raises rather than letting the caller proceed on a dirty
    data dir — the log line "Cleaning data directory..." must only be
    followed by a run when the postcondition actually holds.
    """
    # Capture forensics for a lock-file-identified orphan BEFORE any deletion
    # is attempted: a partially-successful first-pass rmtree can delete the
    # very lock file / engine.log this identification depends on (rmtree
    # aborts on the first unhandled OSError, but earlier siblings in the
    # same directory may already be gone by then) — "the wipe destroys the
    # forensics" (711 item 4 brief). The catch-all cmdline scan inside
    # _sweep_orphan_worker below does not depend on the lock file surviving,
    # but the log tail does, so it is captured here unconditionally.
    precheck = _find_orphan_worker_pid(resolved_data)
    if precheck is not None:
        _log_worker_forensics(precheck[0], precheck[1], resolved_data)

    failures = _attempt_wipe(resolved_data)
    swept: list[tuple[int, list[str]]] = []

    if failures:
        log.warning(
            "Clean wipe hit %d failure(s) on first pass (%s) — sweeping for an "
            "orphan Worker holding %s open",
            len(failures), ", ".join(p.name for p in failures), resolved_data,
        )
        swept = _sweep_orphan_worker(resolved_data)
        if precheck is not None and not any(pid == precheck[0] for pid, _ in swept):
            swept.append(precheck)
        failures = _attempt_wipe(resolved_data)

    survivors = sorted(child.name for child in resolved_data.iterdir())
    if survivors:
        holder = ""
        if swept:
            pid, cmdline = swept[0]
            holder = f" Likely holder before sweep: PID {pid} ({' '.join(cmdline)})."
        raise RuntimeError(
            f"jseval --clean failed to wipe {resolved_data}: survivor(s) remain "
            f"after wipe + orphan-Worker sweep: {', '.join(survivors)}."
            f"{holder} A process still holds a handle inside this directory; "
            "aborting rather than proceeding on a dirty data dir "
            "(tempdoc 711 item 4 — --clean is fail-closed)."
        )


def stop_backend(proc: subprocess.Popen, data_dir: Path | None = None) -> None:
    """Stop the backend by killing the process tree, then sweep for orphans.

    Uses taskkill /T /F on Windows (canonical pattern from dev-runner.cjs).
    The Worker JVM is spawned by the Head as a grandchild of the Gradle
    process this kills, and has been observed to survive it (tempdoc 711
    item 4) — when ``data_dir`` is given, also runs the double-keyed orphan
    sweep so the Worker's Lucene handle and watched_roots.json writer are
    actually gone, not just the head's process tree.

    Tempdoc 844 D3: also drops this backend's foreign-run record. Keyed by ``proc.pid`` alone so
    every existing call site is covered without threading a handle through; idempotent, so the
    failure paths inside ``_boot_and_wait``/``_run_with_cache`` (which stop a proc that may never
    have registered) are safe.
    """
    if proc.poll() is not None:
        log.info("Backend already exited (rc=%d)", proc.returncode)
    else:
        pid = proc.pid
        log.info("Stopping backend (PID=%d)...", pid)

        if os.name == "nt":
            # Windows: must kill process tree, not just the root.
            subprocess.run(
                ["taskkill", "/PID", str(pid), "/T", "/F"],
                capture_output=True,
            )
        else:
            proc.terminate()
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()

        log.info("Backend stopped")

    # Tempdoc 844 S2: sweep FIRST, then retire the record. The earlier ordering rested on a comment
    # ("the backend is already dead by here") that contradicts this function's own docstring: the
    # Worker JVM is a grandchild that has been observed to survive the tree kill (tempdoc 711
    # item 4), which is why the sweep exists at all. Unregistering first left a window — seconds,
    # the sweep waits up to 10 — in which a GPU-holding orphan was running with nothing on the
    # machine declaring it: the exact blind spot the register was added to close. In `finally`, so
    # a sweep that raises still retires the record instead of leaving one behind that names a
    # launcher pid which is already gone.
    try:
        if data_dir is not None:
            _sweep_orphan_worker(data_dir)
    finally:
        run_register.unregister_backend(proc.pid)


def _read_lock_metadata(lock_file: Path) -> tuple[int, str | None] | None:
    """Parse ``pid=``/``started_at=`` out of a Worker's IndexRootLock sibling file.

    Mirrors ``IndexRootLock.parsePidFromMetadata`` / ``parseStartedAtFromMetadata``
    (modules/indexer-worker/.../util/IndexRootLock.java) on the Python side.
    Returns None if the file is absent/unreadable or has no ``pid=`` line.
    """
    try:
        content = lock_file.read_text(encoding="utf-8")
    except OSError:
        return None
    pid: int | None = None
    started_at: str | None = None
    for line in content.splitlines():
        if line.startswith("pid="):
            try:
                pid = int(line[len("pid="):].strip())
            except ValueError:
                pass
        elif line.startswith("started_at="):
            started_at = line[len("started_at="):].strip()
    if pid is None:
        return None
    return pid, started_at


def _parse_java_instant(value: str) -> float | None:
    """Parse a ``java.time.Instant.toString()`` value into a POSIX timestamp.

    Format is always ``yyyy-MM-ddTHH:mm:ss[.SSSSSSSSS]Z`` (UTC, up to 9
    fractional digits/nanoseconds). Python's ``datetime.fromisoformat`` only
    accepts up to 6 (microseconds), so the fractional part is truncated.
    """
    if not value:
        return None
    text = value.strip()
    if not text.endswith("Z"):
        return None
    body = text[:-1]
    if "." in body:
        head, frac = body.split(".", 1)
        frac = (frac + "000000")[:6]
        body = f"{head}.{frac}"
    try:
        dt = datetime.datetime.fromisoformat(body).replace(tzinfo=datetime.timezone.utc)
    except ValueError:
        return None
    return dt.timestamp()


def _normalize_path(value: str) -> str:
    return os.path.normcase(os.path.normpath(value))


_DATA_DIR_SYSPROPS = ("-djustsearch.data.dir=",)


def _cmdline_matches_data_dir(cmdline: list[str], data_dir: Path) -> bool:
    """True if cmdline carries -Djustsearch.data.dir=<data_dir>,
    normalized-path-equal to the given data_dir (not a substring match — a data_dir
    that happens to prefix another path must not false-positive)."""
    target = _normalize_path(str(data_dir))
    for arg in cmdline:
        lowered = arg.lower()
        for prefix in _DATA_DIR_SYSPROPS:
            if lowered.startswith(prefix):
                value = arg[len(prefix):]
                if _normalize_path(value) == target:
                    return True
    return False


def _find_orphan_worker_pid(data_dir: Path) -> tuple[int, list[str]] | None:
    """Locate a Worker process still holding data_dir's index lock, if any.

    Double-keyed so this can never target a different session's process on
    a shared machine: (1) the PID recorded in the lock file must be a live
    process whose actual start time is consistent (within
    ``_LOCK_PID_SKEW_SEC``) with the ``started_at`` the Worker itself
    stamped into the lock, AND (2) the process's command line must carry
    ``-Djustsearch.data.dir=<this exact data_dir>``. If only one key
    matches, this logs and returns None rather than killing anything.
    """
    lock_file = data_dir / _LOCK_FILE_REL
    metadata = _read_lock_metadata(lock_file)
    if metadata is None:
        log.debug("No index lock file at %s — nothing to sweep via lock", lock_file)
        return None
    pid, started_at_raw = metadata

    try:
        proc = psutil.Process(pid)
    except psutil.NoSuchProcess:
        log.debug("Index lock references PID %d, which is no longer running", pid)
        return None

    lock_started_ts = _parse_java_instant(started_at_raw) if started_at_raw else None
    if lock_started_ts is not None:
        try:
            key_time_ok = abs(proc.create_time() - lock_started_ts) <= _LOCK_PID_SKEW_SEC
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            key_time_ok = False
    else:
        # No started_at in the lock file (older Worker build) — the cmdline
        # key below still has to match independently, so falling back to
        # liveness alone here doesn't weaken the double-key guarantee.
        key_time_ok = True

    try:
        cmdline = proc.cmdline()
    except (psutil.NoSuchProcess, psutil.AccessDenied):
        cmdline = []
    key_cmdline_ok = _cmdline_matches_data_dir(cmdline, data_dir)

    if key_time_ok and key_cmdline_ok:
        return pid, cmdline

    log.warning(
        "Index lock at %s names PID %d but it did not pass both identity checks "
        "(time_key=%s, cmdline_key=%s) — refusing to kill it (could belong to a "
        "different session on this machine)",
        lock_file, pid, key_time_ok, key_cmdline_ok,
    )
    return None


def _scan_orphan_worker_processes(data_dir: Path) -> list[tuple[int, list[str]]]:
    """Catch-all: find any java process whose cmdline carries this exact
    -Djustsearch.data.dir=<data_dir>, independent of the lock file (covers a
    Worker that died before writing the lock, or a lock file that was
    already deleted/rotated). Same double-key rule as the lock-file path:
    process name is java AND cmdline carries the exact data dir.
    """
    matches: list[tuple[int, list[str]]] = []
    for proc in psutil.process_iter(["pid", "name"]):
        try:
            name = (proc.info.get("name") or "").lower()
            if "java" not in name:
                continue
            cmdline = proc.cmdline()
        except (psutil.NoSuchProcess, psutil.AccessDenied, psutil.ZombieProcess):
            continue
        if _cmdline_matches_data_dir(cmdline, data_dir):
            matches.append((proc.pid, cmdline))
    return matches


def _log_worker_forensics(pid: int, cmdline: list[str], data_dir: Path) -> None:
    """Log the discovered orphan + the engine.log tail BEFORE any wipe/kill,
    since the wipe destroys the forensics."""
    log.warning("Orphan Worker detected: PID=%d cmdline=%s", pid, " ".join(cmdline))
    log_file = data_dir / _ENGINE_LOG_REL
    try:
        text = log_file.read_text(encoding="utf-8", errors="replace")
    except OSError:
        log.debug("No engine.log at %s to capture before sweep", log_file)
        return
    tail = text.splitlines()[-_ENGINE_LOG_TAIL_LINES:]
    log.warning("engine.log tail (%d lines) before sweep:\n%s", len(tail), "\n".join(tail))


def _kill_pid(pid: int) -> None:
    if os.name == "nt":
        subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"], capture_output=True)
        return
    try:
        proc = psutil.Process(pid)
        proc.terminate()
        proc.wait(timeout=10)
    except psutil.NoSuchProcess:
        pass
    except psutil.TimeoutExpired:
        try:
            psutil.Process(pid).kill()
        except psutil.NoSuchProcess:
            pass


def _sweep_orphan_worker(data_dir: Path) -> list[tuple[int, list[str]]]:
    """Find and kill any Worker JVM still holding data_dir's index open.

    Combines the lock-file-keyed lookup with the cmdline catch-all scan,
    de-duplicated by PID. Logs forensics (PID/cmdline + engine.log tail)
    before killing, since the caller's wipe will otherwise destroy them.
    Returns the list of (pid, cmdline) this swept (whether or not the kill
    is later confirmed) — used by ``_clean_data_dir`` to name a likely
    holder if survivors remain.
    """
    candidates: list[tuple[int, list[str]]] = []
    found = _find_orphan_worker_pid(data_dir)
    if found is not None:
        candidates.append(found)
    for pid, cmdline in _scan_orphan_worker_processes(data_dir):
        if not any(pid == existing_pid for existing_pid, _ in candidates):
            candidates.append((pid, cmdline))

    if not candidates:
        log.debug("Orphan sweep found no matching Worker process for %s", data_dir)
        return candidates

    for pid, cmdline in candidates:
        _log_worker_forensics(pid, cmdline, data_dir)
        log.warning("Killing orphan Worker PID=%d (data_dir=%s)", pid, data_dir)
        _kill_pid(pid)

    deadline = time.monotonic() + 10.0
    while time.monotonic() < deadline:
        if not any(psutil.pid_exists(pid) for pid, _ in candidates):
            break
        time.sleep(0.2)

    return candidates


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
