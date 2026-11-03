"""Pre-run calibration + readiness pass for the agent-utility eval (tempdoc 624).

The floor run committed $30 + 40 min blind: it ran on a degraded index, picked
timeout/concurrency from a guess (→ 26 % timeout), and labelled the search cohort
by hand. This module is the cheap pre-pass that *conditions* the expensive run —
the agent-eval instance of the `calibrate`/`readiness`/`preflight` seam:

1. **Readiness gate** — reuse `readiness.check_search_ready` (dense+sparse).
2. **Pin the real `config_cohort_key`** — reuse `manifest` snapshots +
   `release.config_cohort_key` (confidence pass #3 B3: `/api/debug/commit-metadata`,
   NOT `/api/status`, which carries the corpus-dependent fps).
3. **Pilot at the *target* concurrency** → `timeout ≈ 2× contended-p95`
   (confidence pass #3 B1: a *low*-concurrency pilot underestimates ~1.8×).
4. **Closed-book filter** — drop the memorizable queries (confidence pass #3 B2:
   the kept set ~doubles the C-beats-A signal).

The output is a `calibration` dict that `jseval utility-run --calibration` consumes.
"""

from __future__ import annotations

import concurrent.futures
import datetime as _dt
import json
import subprocess
import tempfile
from pathlib import Path

import httpx

from jsharness.types import ReadinessResult


def _normalize_root_path(path: str) -> str:
    """Normalize a filesystem path for watched-root scope comparison.

    Watched-root paths (from the Worker/Head) and the eval's own `corpus_dir` can differ
    in separator style, trailing separator, or case (Windows paths are case-insensitive) while
    still denoting the same directory. Resolve to an absolute path and case-fold so
    `C:\\foo\\bar`, `c:/foo/bar/`, and `C:\\Foo\\Bar\\` all compare equal.
    """
    return str(Path(path).resolve()).rstrip("\\/").lower()


def check_watched_roots_scoped(
    base_url: str, corpus_dir: str, *, timeout_sec: float = 15.0
) -> ReadinessResult:
    """Fail loudly if any currently-watched root is broader than the corpus's own `corpus_dir`.

    Watched roots are accretive and persistent (`RootLifecycleOps.addWatchedRoot`,
    `WatchedRootsState` — modules/app-services/.../worker/): once added, a root stays watched
    until explicitly removed via `DELETE /api/indexing/roots`; nothing auto-narrows or collapses
    overlapping roots. The Worker's file-tree walk only scans strictly downward from each watched
    root, so a *broader* root (e.g. a corpus's parent directory, added once and never removed)
    keeps indexing everything beneath it — including sibling gold-answer-key files — for the rest
    of that dataDir's life, invisibly. This was the confirmed root cause of a real answer-key leak
    into eval results (tempdoc 624 As-built #7, `golden/synth-scan-v1`).

    Queries `GET /api/indexing/roots` (`IndexingController.handleListRoots`,
    `{"roots": [{"collection", "path", "fileCount", "lastIndexed"?}, ...]}`) and compares each
    watched root's path against `corpus_dir`. Does NOT attempt to remove any stray root —
    fail-and-report only, so an operator can inspect and remove it deliberately.
    """
    expected = _normalize_root_path(corpus_dir)
    try:
        with httpx.Client(base_url=base_url, timeout=timeout_sec) as client:
            resp = client.get("/api/indexing/roots")
            resp.raise_for_status()
            payload = resp.json()
    except Exception as e:
        return ReadinessResult(
            passed=False,
            failure_reasons=[f"watched_roots_endpoint_unreachable: {type(e).__name__}: {e}"],
        )

    roots = payload.get("roots", [])
    stray = []
    seen_stray = set()
    for r in roots:
        raw_path = r.get("path")
        if not raw_path:
            continue
        if _normalize_root_path(raw_path) != expected and raw_path not in seen_stray:
            stray.append(raw_path)
            seen_stray.add(raw_path)

    if stray:
        stray_list = "\n".join(f"  - {p}" for p in stray)
        message = (
            f"Watched-roots scope check FAILED: {len(stray)} watched root(s) do not match the "
            f"corpus's own corpus-dir ({corpus_dir!r}). Watched roots are accretive and never "
            f"auto-narrowed (RootLifecycleOps.addWatchedRoot) — a broader/stale root keeps indexing "
            f"everything beneath it, including sibling gold-answer-key files, for the rest of this "
            f"dataDir's life. Remove the stray root(s) below via `DELETE /api/indexing/roots` before "
            f"running the eval, then re-run readiness:\n{stray_list}"
        )
        return ReadinessResult(
            passed=False,
            failure_reasons=["stray_watched_root", message],
            snapshot={"roots": roots},
        )

    return ReadinessResult(passed=True, snapshot={"roots": roots})


class StrayWatchedRootError(RuntimeError):
    """A live eval run detected a watched root broader than its own `corpus_dir`.

    Raised by `assert_watched_roots_scoped` — the automatic-prevention call site wired
    directly into `run_utility_eval` (agent_utility_inspect.py), the record-grade
    function that actually EXECUTEs an eval (the classic `run_agent_eval` that also
    shared this gate was retired in tempdoc 675). Before this,
    `check_watched_roots_scoped` was only reachable via the separate, optional
    `utility-calibrate` CLI — an eval could run (and silently leak) without ever going
    through it. See `check_watched_roots_scoped`'s docstring for the underlying
    mechanism (tempdoc 624 As-built #7).
    """


def assert_watched_roots_scoped(base_url: str, corpus_dir: str, *, timeout_sec: float = 15.0) -> None:
    """Run `check_watched_roots_scoped` and raise `StrayWatchedRootError` on failure.

    Unlike `utility-calibrate` (optional, report-only), any code path that is about to
    actually run an eval against `base_url` must abort here rather than warn-and-continue
    — a stray root silently serves leaked content for the rest of the run.
    """
    result = check_watched_roots_scoped(base_url, corpus_dir, timeout_sec=timeout_sec)
    if not result.passed:
        raise StrayWatchedRootError("; ".join(result.failure_reasons))


def base_url_from_mcp_config(mcp_config_path: str) -> str | None:
    """Derive the JustSearch backend's base_url from an eval's `--mcp-config` file.

    `run_utility_eval` does not take its own `--base-url` option — the backend address
    it actually needs for the watched-roots safety check is already
    carried by the `--mcp-config` file the agent session uses for its own MCP
    transport: `{"mcpServers":{"justsearch":{"type":"http","url":"http://127.0.0.1:PORT/mcp"}}}`
    (see `util-smoke/README.md`). **The `"type":"http"` field is mandatory** — a `url`-only
    entry is silently DROPPED by the `claude` CLI (see `assert_mcp_config_http_typed`), so an
    omitted `"type"` is not just a documentation nit here, it is the exact shape that produces
    a dead config. Deriving the base_url here — instead of adding a second, possibly divergent
    base-url flag — guarantees the roots check always targets the SAME backend the agent under
    test is configured to talk to.

    Returns None if the file is missing/malformed or doesn't carry a `justsearch` server
    `url` (e.g. condition A's `{"mcpServers":{}}` empty config) — callers should treat
    that as "no search backend is in play for this call," not "verified clean."
    """
    try:
        cfg = json.loads(Path(mcp_config_path).read_text(encoding="utf-8"))
        url = cfg["mcpServers"]["justsearch"]["url"]
    except Exception:
        return None
    if not isinstance(url, str) or not url:
        return None
    return url[: -len("/mcp")] if url.endswith("/mcp") else url.rstrip("/")


class McpConfigMissingTypeError(ValueError):
    """A `--mcp-config` file has an `mcpServers` entry with a `url` but no `type` field.

    Proven (2026-07-03 A/B probe, tempdoc 624 battlefield retrospective): the `claude` CLI
    SILENTLY DROPS an `mcpServers` entry shaped `{"url": "..."}` without `"type": "http"` —
    the init event reports `mcp_servers: []` and 0 `mcp__`-prefixed tools, with no warning
    and a clean exit code. Every condition B/C battlefield cell that used this shape ran with
    zero MCP tool calls, indistinguishable from a healthy run by exit code or stdout length
    alone. Fix: add `"type": "http"` to the server entry.
    """


class McpConfigInvalidAlwaysLoadError(ValueError):
    """A `--mcp-config` server entry's `alwaysLoad` key is present but not a JSON boolean.

    `alwaysLoad` (tempdoc 725 increment 2/4) is the harness-side signal
    `_derive_exposure_mode` reads to decide eager vs. deferred exposure —
    `agent_utility_inspect._capture_exposure_config` reads it straight off the parsed
    config with `bool(raw_always_load) if raw_always_load is not None else None`, and
    `_derive_exposure_mode`'s own check is `always_load is True` (an identity check, not
    a truthiness check upstream of that cast). A string `"true"`, an int `1`, or any
    other non-bool JSON value would silently take a DIFFERENT path through that logic
    than a real `true` literal — the exposure identity recorded for the campaign could
    disagree with what the config author intended, undetected. Fail closed instead of
    letting a typo mismeasure the eager/deferred arm.
    """


def assert_mcp_config_http_typed(mcp_config_path: str) -> None:
    """Raise `McpConfigMissingTypeError` if `mcp_config_path` carries an `mcpServers` entry
    with a `url` but no `type` — the exact shape the `claude` CLI silently drops (see
    `McpConfigMissingTypeError`'s docstring). Called up front by any code path about to
    actually hand this config to a `claude` subprocess (`run_utility_eval`), so a dead
    config aborts the run immediately instead of producing 0-tool-call cells that read as
    healthy.

    Also raises `McpConfigInvalidAlwaysLoadError` (tempdoc 725 increment 4) if any server
    entry carries an `alwaysLoad` key whose value is not a JSON boolean — see that error's
    docstring for why a non-bool value must fail closed rather than degrade.

    A missing/malformed config file, a config with no `mcpServers` key, an empty
    `mcpServers` (condition A's `{"mcpServers":{}}`), or a command-style entry
    (`{"command": ..., "args": [...]}`, no `url`) is NOT an error here — this guards only
    the specific silent-drop shape (`url` present, `type` absent) plus the `alwaysLoad`
    type check above.
    """
    try:
        cfg = json.loads(Path(mcp_config_path).read_text(encoding="utf-8"))
    except Exception:
        return  # missing/malformed config file: not this function's concern
    servers = cfg.get("mcpServers") if isinstance(cfg, dict) else None
    if not isinstance(servers, dict):
        return
    for name, entry in servers.items():
        if not isinstance(entry, dict):
            continue
        if "alwaysLoad" in entry and not isinstance(entry["alwaysLoad"], bool):
            raise McpConfigInvalidAlwaysLoadError(
                f"mcp_config {mcp_config_path!r} server {name!r} has a non-boolean "
                f"`alwaysLoad` ({entry['alwaysLoad']!r}, type "
                f"{type(entry['alwaysLoad']).__name__}) -- alwaysLoad must be a JSON "
                "boolean (true/false) or omitted entirely. Fix: set it to `true`/`false`, "
                f"e.g. {{\"mcpServers\":{{{name!r}:{{\"alwaysLoad\":true}}}}}}."
            )
        if "url" in entry and "type" not in entry:
            raise McpConfigMissingTypeError(
                f"mcp_config {mcp_config_path!r} server {name!r} has a `url` but no `type` "
                "-- the `claude` CLI silently DROPS this entry (proven: url-only -> init "
                "event mcp_servers=[], 0 tools, clean exit). Fix: add `\"type\": \"http\"` "
                f"to the server entry, e.g. "
                f'{{"mcpServers":{{{name!r}:{{"type":"http","url":{entry.get("url")!r}}}}}}}.'
            )


class StaleCalibrationError(RuntimeError):
    """A banked `calibration.json` was pinned to a different git checkout than the one about
    to spend against it — or carries no git stamp at all (tempdoc 758 §A, incident #5).

    `utility-calibrate` writes a `calibration.json` that pins a `config_cohort_key` snapshot of
    the live backend at a specific commit. Chains skip recalibration when the file already
    exists, so a leftover from an aborted launch attempt silently imports a *stale* cohort key
    into a later run at a different HEAD (v4 confirmatory: a 23:33 calibration at `92ec2e6d`
    adopted into the `079e63e5` chain → recompose refused, $12.92 voided). The run side must
    fail closed on a SHA mismatch (or a missing stamp) BEFORE spending, naming both SHAs and the
    remedy, rather than discovering the stale identity only after the money is gone.
    """


class HarnessVersionDriftError(RuntimeError):
    """The `claude` CLI version changed between calibration and run (tempdoc 758 §B, incident #6).

    The CLI auto-updated 2.1.212→2.1.214 mid-night, splitting `agent_cohort_key` (`cli_version`
    is hashed into it) between the main campaign and a same-night rerun, which could not rejoin
    the cohort without downgrading the founder's shared global CLI. `utility-calibrate` records
    the live `claude --version` string; the run side fails closed when it no longer matches,
    naming the version pair + `DISABLE_AUTOUPDATER=1` + the recalibrate remedy, so a silent
    cohort tear becomes a legible pre-spend failure.
    """


def assert_calibration_git_sha(calib: dict, *, current_git_sha: str | None) -> None:
    """Fail closed (`StaleCalibrationError`) unless `calib`'s `git_sha` stamp matches the current
    checkout HEAD (tempdoc 758 §A). A missing stamp (legacy calibration) also fails closed —
    an un-stamped calibration cannot be proven to match this checkout, so it must be recalibrated
    rather than trusted. See `StaleCalibrationError` for the incident this prevents.
    """
    pinned = calib.get("git_sha")
    if not pinned:
        raise StaleCalibrationError(
            "legacy calibration without git_sha stamp -- recalibrate: this calibration.json "
            "predates SHA-binding (tempdoc 758 A) and cannot be proven to match the current "
            "checkout, so its pinned config_cohort_key is not trustworthy. Delete it and re-run "
            "`jseval utility-calibrate` against the live backend at this HEAD."
        )
    if current_git_sha is None:
        raise StaleCalibrationError(
            f"cannot resolve the current git SHA to validate a banked calibration pinned at "
            f"{pinned} -- refusing to spend. Ensure `git rev-parse HEAD` works in this checkout, "
            "then recalibrate if the checkout has moved."
        )
    if pinned != current_git_sha:
        raise StaleCalibrationError(
            f"recalibrate: banked calibration pinned at {pinned}, checkout is {current_git_sha}. "
            "A leftover calibration.json from an aborted launch imports a stale config_cohort_key "
            "into this run (tempdoc 758 A, incident #5). Delete it and re-run "
            "`jseval utility-calibrate` against the live backend at this HEAD."
        )


def assert_calibration_cli_version(calib: dict, *, current_cli_version: str | None) -> None:
    """Fail closed (`HarnessVersionDriftError`) unless `calib`'s `cli_version` stamp matches the
    live `claude --version` (tempdoc 758 §B). A missing stamp (legacy calibration) also fails
    closed — an un-stamped calibration cannot prove the harness didn't drift under it, and a
    silent CLI auto-update tears `agent_cohort_key`. See `HarnessVersionDriftError`.
    """
    pinned = calib.get("cli_version")
    if not pinned:
        raise HarnessVersionDriftError(
            "legacy calibration without cli_version stamp -- recalibrate: this calibration.json "
            "predates harness-pinning (tempdoc 758 B) and cannot prove the `claude` CLI version "
            "is unchanged since it was banked. Set DISABLE_AUTOUPDATER=1 for the chain and re-run "
            "`jseval utility-calibrate`."
        )
    if pinned != current_cli_version:
        raise HarnessVersionDriftError(
            f"recalibrate: banked calibration recorded claude CLI {pinned!r}, current CLI is "
            f"{current_cli_version!r}. The CLI changed between calibration and run (tempdoc 758 "
            "B, incident #6), which tears agent_cohort_key so a rerun cannot rejoin the cohort. "
            "Set DISABLE_AUTOUPDATER=1 for the whole chain lifetime and re-run "
            "`jseval utility-calibrate`."
        )


def check_readiness(
    base_url: str, corpus_dir: str, *, require_dense: bool = True, timeout_sec: float = 15.0
) -> ReadinessResult:
    """Reuse `readiness.check_search_ready` (dense+sparse) AND verify watched-roots scope.

    A dense+sparse-ready index that is scoped to a stray/broader watched root is not actually
    ready for a clean eval run — it can silently serve leaked content (tempdoc 624 As-built #7).
    """
    from jsharness.readiness import check_search_ready
    search_rd = check_search_ready(
        base_url, dense_enabled=require_dense, splade_enabled=True, timeout_sec=timeout_sec)
    roots_rd = check_watched_roots_scoped(base_url, corpus_dir, timeout_sec=timeout_sec)
    return ReadinessResult(
        passed=search_rd.passed and roots_rd.passed,
        failure_reasons=[*search_rd.failure_reasons, *roots_rd.failure_reasons],
        snapshot={**search_rd.snapshot, **roots_rd.snapshot},
    )


def pin_config_cohort_key(base_url: str) -> tuple[str | None, dict]:
    """The *real* search backend `config_cohort_key` (B3) — reuse the manifest seam.

    Builds the config-global slice of a manifest from the live `/api/debug/commit-metadata`
    + session-policies + model snapshots and hashes it with `release.config_cohort_key`.
    """
    from jseval import manifest as mf
    from jseval import release
    from jseval.run import _snapshot_models

    snaps = mf.capture_state_snapshots(base_url)
    manifest = {
        "git_sha": mf._git_sha_full(),
        "commit_metadata": mf._normalise_commit_metadata(
            snaps.get("/api/debug/commit-metadata") or {}),
        "policy_hash": mf._sha256_canonical(snaps.get("/api/debug/session-policies") or {}),
        "model_fingerprints": _snapshot_models(base_url) or {},
        "eval_protocol_hash": mf._sha256_canonical({"agent_utility_eval": "v1"}),
    }
    return release.config_cohort_key(manifest), manifest["commit_metadata"]


def _read_pilot_sample_times(pilot_log_dir: str) -> list[tuple[str | None, float]]:
    """Every non-errored pilot sample's ``(condition, total_time)``.

    ``condition`` comes from ``sample.metadata["condition"]`` (the single-pool sample field
    set by ``agent_utility_task``); it is ``None`` only for a malformed/legacy sample that
    carries no condition. Errored or time-less cells are skipped (they carry no usable
    wall-clock signal)."""
    from inspect_ai.log import read_eval_log

    out: list[tuple[str | None, float]] = []
    for lf in sorted(Path(pilot_log_dir).glob("*.json")) + sorted(Path(pilot_log_dir).glob("*.eval")):
        if lf.name in ("eval-set.json", "logs.json"):
            continue
        try:
            log = read_eval_log(lf.as_posix())
        except Exception:
            continue
        for s in (log.samples or []):
            if (s.metadata or {}).get("error") or not s.total_time:
                continue
            cond = (s.metadata or {}).get("condition")
            out.append((str(cond) if cond is not None else None, s.total_time))
    return out


def _p95_timeout(times: list[float], *, multiplier: float, floor_s: int, ceil_s: int) -> int:
    """``multiplier × p95(times)`` clamped to ``[floor_s, ceil_s]`` — the shared 2× rule."""
    s = sorted(times)
    p95 = s[min(len(s) - 1, int(len(s) * 0.95))]
    return int(max(floor_s, min(ceil_s, multiplier * p95)))


def calibrate_timeout(pilot_log_dir: str, *, multiplier: float = 2.0,
                      floor_s: int = 120, ceil_s: int = 600) -> int:
    """timeout ≈ multiplier × the pilot's *contended* p95 (B1), clamped to [floor, ceil].

    Pooled across ALL conditions — kept as the backward-compatible scalar and as the
    per-condition fallback (see `calibrate_timeout_by_condition`)."""
    times = [t for _cond, t in _read_pilot_sample_times(pilot_log_dir)]
    if not times:
        return ceil_s
    return _p95_timeout(times, multiplier=multiplier, floor_s=floor_s, ceil_s=ceil_s)


def calibrate_timeout_by_condition(pilot_log_dir: str, conditions, *, pooled_timeout_s: int,
                                   multiplier: float = 2.0, floor_s: int = 120,
                                   ceil_s: int = 600) -> dict[str, int]:
    """Per-condition `timeout ≈ multiplier × that condition's OWN contended p95`, the same
    2× rule as the pooled `calibrate_timeout` but computed per arm.

    On a large corpus the A arm (grep/file-tools) tail runs much longer than B/C's, so a
    single pooled timeout under-budgets A → A-arm exhaustion (tempdoc 624 §Harness lessons:
    32% A exhaustion at 10k). A condition with NO usable pilot cells (all errored, or the
    condition never appeared in the pilot) falls back to `pooled_timeout_s`, so the returned
    map is always fully populated for every requested condition."""
    by_cond: dict[str, list[float]] = {}
    for cond, t in _read_pilot_sample_times(pilot_log_dir):
        if cond is None:
            continue
        by_cond.setdefault(cond, []).append(t)
    result: dict[str, int] = {}
    for cond in conditions:
        times = by_cond.get(str(cond))
        result[str(cond)] = (
            _p95_timeout(times, multiplier=multiplier, floor_s=floor_s, ceil_s=ceil_s)
            if times else pooled_timeout_s
        )
    return result


def closed_book_filter(queries: list[dict], *, model: str = "haiku",
                       concurrency: int = 8) -> tuple[list[int], int]:
    """Drop the memorizable (closed-book-correct) queries (B2).

    Returns ``(retained_indices, n_dropped)`` — the retained set is exactly the
    *retrieval-relevant* queries (closed-book-WRONG), where C-beats-A is sharpest.
    """
    from jseval.agent_retrieval_eval import _score_answer

    def _cb(i_q):
        i, q = i_q
        prompt = f"Answer this question concisely from your own knowledge. Question: {q['query']}"
        try:
            r = subprocess.run(
                ["claude", "-p", prompt, "--model", model, "--output-format", "json",
                 "--max-budget-usd", "0.10", "--permission-mode", "bypassPermissions"],
                capture_output=True, text=True, timeout=90, cwd=tempfile.mkdtemp())
            d = json.loads(r.stdout or "{}")
            return i, _score_answer(q["answer"], d.get("result", ""))
        except Exception:
            return i, False  # treat failures as retrieval-relevant (keep)

    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as ex:
        res = dict(ex.map(_cb, list(enumerate(queries))))
    retained = [i for i in range(len(queries)) if not res.get(i)]
    return retained, len(queries) - len(retained)


def equalize_timeouts_across_conditions(
    measured: dict[str, int], *, fallback: int
) -> dict[str, int]:
    """Apply max() across conditions to every condition's budget.

    The exhaustion-as-failure outcome rule is only fair under IDENTICAL per-arm
    budgets; sizing that shared budget to the SLOWEST arm's calibrated tail keeps
    the Step-2 lesson (a pooled budget starves the slow arm) without the phase-2
    email-10k artifact (a naive per-arm budget starves whichever arm calibrates
    tight). Empty measurement -> {} (callers fall back to the pooled scalar)."""
    if not measured:
        return {}
    equalized = max(measured.values(), default=fallback)
    return {condition: int(equalized) for condition in measured}


def calibrate(*, base_url: str, queries: list[dict], corpus_dir: str, mcp_config: str | None,
              model: str, concurrency: int, seeds: int, conditions=("A", "C"),
              require_dense: bool = True, pilot_n: int = 5, max_budget: float = 0.50,
              do_closed_book: bool = True) -> dict:
    """Orchestrate the pre-run calibration. Needs the live backend (readiness/pin/pilot)."""
    from jseval import agent_utility_inspect as aui
    from jseval import agent_utility_run as aur
    from jseval import manifest as mf

    # Provenance stamps for the banked calibration.json (tempdoc 758 §A/§B): the git SHA of the
    # checkout this calibration pinned its config_cohort_key against, and the `claude` CLI version
    # whose hash feeds agent_cohort_key. `utility-run --calibration` fails closed if either has
    # drifted before it spends (assert_calibration_git_sha / assert_calibration_cli_version).
    git_sha = mf._git_sha_full()
    cli_version = aur.claude_cli_version()

    rd = check_readiness(base_url, corpus_dir, require_dense=require_dense)
    cck, commit_meta = pin_config_cohort_key(base_url)

    # Pilot at the TARGET concurrency (a few queries, both arms) → contended p95 → timeout.
    pilot_dir = tempfile.mkdtemp(prefix="util-pilot-").replace("\\", "/")
    pq = tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8")
    json.dump(queries[:pilot_n], pq); pq.close()
    aui.run_utility_eval(
        queries_path=pq.name, corpus_dir=corpus_dir, mcp_config=mcp_config, model=model,
        conditions=conditions, seeds=1, concurrency=concurrency, log_dir=pilot_dir,
        max_queries=pilot_n, max_budget=max_budget, cli_version=cli_version,
        corpus_dataset="pilot", corpus_signature="pilot")
    timeout_s = calibrate_timeout(pilot_dir)
    # Per-condition MEASUREMENT, EQUALIZED-MAX application (tempdoc 624 Phase-2
    # amendment, 2026-07-17): the resource-exhaustion-as-failure outcome rule is
    # only fair when both arms run under IDENTICAL budgets, and the pooled pilot
    # under-budgets the slow arm's tail (Step-2: 32% A exhaustion at 10k) while a
    # naive per-arm application under-budgets whichever arm calibrates tight
    # (phase-2 email-10k: B floor-clamped to 120s below its own p95 = 26/60 B
    # exhaustions -- an artifact, not a capability limit). So: calibrate each
    # condition's tail, then apply max() across conditions to EVERY condition --
    # budgets equal by construction, sized so no arm is tail-starved. The raw
    # per-condition measurement is kept alongside for the record.
    timeout_s_by_condition_measured = calibrate_timeout_by_condition(
        pilot_dir, conditions, pooled_timeout_s=timeout_s)
    timeout_s_by_condition = equalize_timeouts_across_conditions(
        timeout_s_by_condition_measured, fallback=timeout_s)

    retained, n_dropped = ([list(range(len(queries))), 0])
    if do_closed_book:
        retained, n_dropped = closed_book_filter(queries, model=model, concurrency=concurrency)

    # Per-cell cost estimate from the pilot → project the full matrix.
    from jseval.utility_governance import compute_loss_accounting
    pilot_arms = compute_loss_accounting(pilot_dir)
    per_cell_cost = 0.12  # fallback
    summ = aur.eval_logs_to_summaries(pilot_dir)
    costs = [v["cost_usd"] for s in summ for v in s["per_query"].values() if v.get("cost_usd")]
    if costs:
        per_cell_cost = sum(costs) / len(costs)
    n_cells = len(retained) * len(conditions) * seeds
    return {
        # Provenance binding (tempdoc 758 §A/§B) — the run side fails closed if either drifts.
        "git_sha": git_sha,
        "cli_version": cli_version,
        "created_at": _dt.datetime.now(_dt.timezone.utc).isoformat(),
        "readiness_passed": rd.passed,
        "readiness_reasons": rd.failure_reasons,
        "config_cohort_key": cck,
        "timeout_s": timeout_s,
        "timeout_s_by_condition": timeout_s_by_condition,
        "timeout_s_by_condition_measured": timeout_s_by_condition_measured,
        "concurrency": concurrency,
        "retained_query_indices": retained,
        "n_dropped_contaminated": n_dropped,
        "n_cells": n_cells,
        "cost_estimate_usd": round(per_cell_cost * n_cells, 2),
        "time_estimate_min": round(n_cells * (timeout_s / 2) / max(concurrency, 1) / 60, 1),
    }
