#!/usr/bin/env python
"""Tempdoc 916 — chunk-size sweep driver (12 arms, every arm a full reindex).

Sibling of `916_collapse_ab.py`, and deliberately the same shape: argparse
`run`/`analyze`, a machine `signature()` before AND after every arm, `child_env()`,
an `arm()`-style subprocess wrapper writing a per-arm log, and the SAME
admissibility rule — an arm counts only if `ce_coverage.verdict == "ok"` AND
`per_mode.<mode>.comparable is true`. A void arm is printed `**VOID**`, never
averaged in. Above the 2% `ce_coverage` tolerance on `mixed/legal-clerc-200` a
degraded arm is biased **upward** (F-056 finding 4), so this is a hard filter.

**What differs from the A/B driver.** Chunk size is a fingerprint input, so there
is no shared index to hold still: `916_collapse_ab.py` builds once and runs every
arm `--skip-ingest`, whereas here every arm is a full `--clean --pipeline`
reindex. That makes the sweep long and interruptible, so the driver is resumable
(`ARM.done` per arm, `CORPUS.done`, `RUN.done`) and detached-driver friendly
(append-only `signatures.jsonl` + `progress.jsonl`).

**Arms.** `{128,256,384,500} x {0,25,50}` = 12, each with a scaled
`min_tokens = max(1, target // 5)`. Scaling is required, not cosmetic: with the
shipped `min_tokens = 100`, `ChunkSplitter` floors the stride at
`max(chunkLength - overlapChars, minChars)`, so at target 128 a requested
50-token overlap is silently suppressed and the 128/25 and 128/50 arms collapse
onto nearly the same boundaries. The campaign branch pinned that interaction before
the temporary policy test was removed during closeout.
At target 500, `500 // 5 == 100` reproduces the incumbent exactly, so the
incumbent arm is unchanged by the scaling.

The four `JUSTSEARCH_CHUNKING_SWEEP_*` keys existed only on the campaign branch and
were removed when the incumbent won.  `analyze` remains useful for the archived run
tree.  `run` has a source preflight and refuses to start on the shipping tree, so a
future invocation cannot label default 500/50 indexes as challenger arms.  Re-running
requires deliberately restoring the temporary binding in a throwaway experiment branch.

**CE deadline is an ARM-INVARIANT CAMPAIGN CONSTANT** (owner decision 2026-09-03,
after the §K.9 smoke arm voided on `degraded-ce` from 21/200 `DEADLINE_EXCEEDED`).
Every arm — including the incumbent replicates — runs with
`JUSTSEARCH_RERANK_DEADLINE_MS=2000`. Rationale is F-054: the "deadline" is a
CPU-side tokenize/prep PRE-CHECK, not a timeout, and a 2000 ms arm measured 0 drops
with unchanged nDCG. It is a constant and NOT an axis — an arm-varying deadline would
put a second lever inside the experiment. Consequence, recorded in F-057: every
campaign number is campaign-internal and the register's shipped-deadline (200 ms)
baseline rows are NOT directly comparable. One extra control arm re-measures that on
this cohort: the incumbent constants at the shipped 200 ms (`--deadline-ms 200`),
which gets its own arm tag so it cannot collide with the campaign-constant incumbent.

**Quiet-machine gate: WAIT, not void.** A game client ran on this box twice on
2026-09-03. Before each arm the driver blocks until no game process is running AND
GPU utilization has been under `--gpu-idle-pct` for `--quiet-sec` continuously; while
an arm runs, a monitor thread samples for game processes, and an arm whose window was
dirty is MOVED ASIDE and RE-RUN rather than counted. Waiting costs wall clock; a
contaminated arm costs the campaign its admissibility argument.

Usage:
  python 916_chunk_sweep.py run --out <dir> [--corpora a,b] [--arms 500/50,...] [--reps 1]
                                [--deadline-ms 2000] [--threshold-chars N]
  python 916_chunk_sweep.py analyze --out <dir> [--reps 1] [--floor 0.0068] [--mode hybrid]
"""
import argparse
import datetime
import glob
import io
import json
import math
import os
import shutil
import statistics
import subprocess
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
ENV_REGISTRY = os.path.join(
    REPO_ROOT, "modules", "configuration", "src", "main", "java", "io", "justsearch",
    "configuration", "EnvRegistry.java")
MODES = "lexical,vector,splade,hybrid"
DEFAULT_CORPORA = "mixed/enron-qa,mixed/legal-clerc-200"

TARGET_TOKENS = (128, 256, 384, 500)
OVERLAP_TOKENS = (0, 25, 50)

ARM_DONE = "ARM.done"
ARM_COMPLETION = "arm-completion.json"
CORPUS_DONE = "CORPUS.done"
RUN_DONE = "RUN.done"
EVIDENCE_NAME = "splade_truncation_evidence.json"
ARM_CORPORA = frozenset((
    "mixed/enron-qa", "mixed/legal-clerc-200", "mixed/ohr-bench-clean"))

KEY_TARGET = "JUSTSEARCH_CHUNKING_SWEEP_TARGET_TOKENS"
KEY_OVERLAP = "JUSTSEARCH_CHUNKING_SWEEP_OVERLAP_TOKENS"
KEY_MIN = "JUSTSEARCH_CHUNKING_SWEEP_MIN_TOKENS"
KEY_THRESHOLD = "JUSTSEARCH_CHUNKING_SWEEP_THRESHOLD_CHARS"
KEY_EVIDENCE = "JUSTSEARCH_SPLADE_EVIDENCE_PATH"
# `EnvRegistry.java:758` RERANK_DEADLINE_MS. Resolved on the HEAD
# (`ResolvedConfigBuilder.java:1351`), sent to the Worker on the wire in
# `RerankRequest.deadline_ms` (`SearchRpcOps.java:390` -> `WorkerSearchService.java:484`)
# AND carried into the worker snapshot at ordinal 450 -- both legs were proven live
# before the campaign's first arm and are recorded in F-057. The one-off probe was
# removed with the temporary binding.
KEY_DEADLINE = "JUSTSEARCH_RERANK_DEADLINE_MS"

# Arm-invariant campaign constant (not an axis). See the module docstring.
CAMPAIGN_DEADLINE_MS = 2000
# `EnvRegistry.java:758`'s own default: the shipped-deadline neutrality control.
SHIPPED_DEADLINE_MS = 200
SHIPPED_THRESHOLD_CHARS = 2000

GAME_PROCESS_PATTERN = "League|Riot|VALORANT|cs2|TFT"
# Riot's launcher BACK-END services run from boot and idle for days with no game. Measured
# on this box 2026-09-03 with exactly these two up and nothing else: GPU 17% / 1161 MiB and
# working sets of 148 MB / 8 MB — this machine's idle baseline, rendering nothing. They are
# still RECORDED in every signature (`games`), they just do not BLOCK (`games_blocking`),
# because a gate that can never open measures nothing and would have consumed the whole
# campaign window. `RiotClientUx` (the launcher WINDOW) is deliberately NOT on this list: it
# renders, and its presence means someone is at the launcher.
GAME_PROCESS_NONBLOCKING = ("RiotClientServices", "RiotClientCrashHandler")
# See `machine_is_quiet` for the measurement these two come from.
DEFAULT_GPU_IDLE_PCT = 25
DEFAULT_GPU_IDLE_MEM_MIB = 3000

METRIC_NDCG = "nDCG@10"
METRIC_R10 = "R@10"
# NOT emitted by jseval: `scoring.DEFAULT_METRICS` is [nDCG@10, AP@10, RR@10, R@10, P@1]
# (`jseval/scoring.py:9`) and nothing overrides it, so `aggregate_metrics["R@50"]` is absent
# on every run today. It is read (and reported as `-`) rather than silently swapped for R@10.
METRIC_R50 = "R@50"
METRIC_P1 = "P@1"

# Statistic keys carried per replicate and averaged per arm.
STAT_KEYS = ("ndcg", "r50", "r10", "p1", "leak", "union", "trunc_rate", "index_bytes",
             "docs_s", "chunk_docs", "ce_coverage_frac", "ce_silent_drops")

# Columns that must not be null on an admissible arm. Checked by `decision_bearing_nulls`
# after the first sweep arm: a campaign that discovers a null decision column on arm 40 has
# already spent the machine time it cannot get back.
DECISION_BEARING = ("ndcg", "r10", "p1", "leak", "union", "index_bytes", "docs_s",
                    "trunc_rate", "chunk_docs")


def min_tokens_for(target):
    """Per-arm chunk floor. `max(1, target // 5)`; at target 500 this is the incumbent 100."""
    return max(1, target // 5)


def arm_matrix():
    """The 12 `(target, overlap)` arms, target-major then overlap-ascending."""
    return [(t, o) for t in TARGET_TOKENS for o in OVERLAP_TOKENS]


def arm_label(target, overlap):
    return "%d/%d" % (target, overlap)


def arm_tag(target, overlap, rep, deadline_ms=CAMPAIGN_DEADLINE_MS):
    """Arm directory name.

    The deadline segment appears ONLY when the arm departs from the campaign constant,
    so the twelve sweep arms keep their pre-decision names and the shipped-deadline
    control (`t500-o50-d200-r0`) cannot collide with the campaign-constant incumbent
    (`t500-o50-r0`) in the same output tree.
    """
    base = "t%d-o%d" % (target, overlap)
    if int(deadline_ms) != CAMPAIGN_DEADLINE_MS:
        base += "-d%d" % int(deadline_ms)
    return "%s-r%d" % (base, rep)


def arm_contract(target, overlap, threshold_chars, deadline_ms, run_id=None, git_sha=None):
    """The exact requested/effective identity that makes an `ARM.done` reusable."""
    return {
        "schema_version": 1,
        "target_tokens": int(target),
        "overlap_tokens": int(overlap),
        "min_tokens": min_tokens_for(int(target)),
        "threshold_override": None if threshold_chars is None else int(threshold_chars),
        "threshold_chars": (SHIPPED_THRESHOLD_CHARS if threshold_chars is None
                            else int(threshold_chars)),
        "deadline_ms": int(deadline_ms),
        "run_id": run_id,
        "git_sha": git_sha,
    }


def arm_yaml(target, overlap, threshold_chars=None, evidence_path=None,
             deadline_ms=CAMPAIGN_DEADLINE_MS):
    """A jseval `--config` file whose `env:` block carries the arm's chunking keys."""
    lines = ["env:"]
    lines.append('  %s: "%d"' % (KEY_TARGET, target))
    lines.append('  %s: "%d"' % (KEY_OVERLAP, overlap))
    lines.append('  %s: "%d"' % (KEY_MIN, min_tokens_for(target)))
    # Always emitted, including on the incumbent arms: an arm-invariant constant that is
    # only written on SOME arms is an axis wearing a constant's name.
    lines.append('  %s: "%d"' % (KEY_DEADLINE, int(deadline_ms)))
    if threshold_chars is not None:
        lines.append('  %s: "%d"' % (KEY_THRESHOLD, int(threshold_chars)))
    if evidence_path:
        # Forward slashes: a Windows path in a double-quoted YAML scalar would eat backslashes.
        lines.append('  %s: "%s"' % (KEY_EVIDENCE, str(evidence_path).replace("\\", "/")))
    return "\n".join(lines) + "\n"


def split_csv(raw):
    return [x.strip() for x in (raw or "").split(",") if x.strip()]


def log(m):
    print("[%s] %s" % (datetime.datetime.now().strftime("%H:%M:%S"), m), flush=True)


def touch(path):
    io.open(path, "w", encoding="utf-8").write("done\n")


def require_sweep_channel(registry_path=ENV_REGISTRY):
    """Refuse `run` when the temporary Worker-bound experiment channel is absent.

    The selected policy was the shipped policy, so closeout deliberately removed these
    keys.  Without this preflight jseval still records the requested YAML overrides even
    though production ignores them, making twelve default 500/50 indexes look like a
    chunk-size matrix.  Analysis of existing evidence does not call this function.
    """
    try:
        source = io.open(registry_path, encoding="utf-8").read()
    except OSError as exc:
        raise RuntimeError(
            "chunk sweep run mode unavailable: cannot inspect temporary binding at %s (%s)"
            % (registry_path, exc)) from exc
    missing = [key for key in (KEY_TARGET, KEY_OVERLAP, KEY_MIN, KEY_THRESHOLD)
               if key not in source]
    if missing:
        raise RuntimeError(
            "chunk sweep run mode is historical on this checkout: the temporary Worker-bound "
            "keys were removed after the incumbent won (%s). Use `analyze` for archived evidence; "
            "restore and verify the experiment binding in a throwaway branch before re-running."
            % ", ".join(missing))


def child_env():
    e = dict(os.environ)
    # 300s, not jseval's 120s default: a cold worktree backend boot is ~150s and the shorter
    # ceiling lost three arms of a previous campaign to a timeout that measured nothing.
    e.setdefault("JSEVAL_HEALTH_TIMEOUT_SEC", "300")
    return e


def gpu_probe():
    """`(memory_used_mib, utilization_pct, raw)`; any component may be None."""
    try:
        raw = subprocess.run(
            ["nvidia-smi", "--query-gpu=memory.used,utilization.gpu", "--format=csv,noheader"],
            capture_output=True, text=True, timeout=30).stdout.strip()
    except Exception as exc:                                    # noqa: BLE001 - advisory only
        return (None, None, "ERR %s" % exc)
    mem = util = None
    parts = [p.strip() for p in raw.split(",")]
    if len(parts) == 2:
        try:
            mem = int(parts[0].split()[0])
            util = int(parts[1].split()[0])
        except (IndexError, ValueError):
            mem = util = None
    return (mem, util, raw)


def games_probe():
    """Comma-joined names of any running game client, `""` when clean.

    Returns the sentinel `"ERR ..."` when the probe itself failed. An unreadable probe
    is NOT read as clean -- `machine_is_quiet` treats it as dirty, because "we could not
    tell" and "nothing is running" are different states and only one of them is a green
    light for an eight-hour campaign.
    """
    try:
        return subprocess.run(
            ["powershell", "-NoProfile", "-Command",
             "(Get-Process | Where-Object {$_.ProcessName -match '%s'} "
             "| Select-Object -ExpandProperty ProcessName) -join ','" % GAME_PROCESS_PATTERN],
            capture_output=True, text=True, timeout=60).stdout.strip()
    except Exception as exc:                                    # noqa: BLE001 - advisory only
        return "ERR %s" % exc


def blocking_games(games):
    """The game processes that BLOCK an arm — `games` minus the non-rendering helpers.

    Returns `None` when the probe itself failed, which is a distinct state from "none
    running" and is never read as clean.
    """
    if games is None or games.startswith("ERR"):
        return None
    return [n for n in [p.strip() for p in games.split(",") if p.strip()]
            if n not in GAME_PROCESS_NONBLOCKING]


def machine_is_quiet(games, util, mem_mib, gpu_idle_pct=None, gpu_idle_mem_mib=None):
    """Quiet == no game process AND the GPU is at this machine's measured idle baseline.

    **Threshold provenance, and a named deviation from the brief's "GPU util < 10%".**
    Measured on this box 2026-09-03 with no game running, no backend running and no arm
    in flight: utilization oscillates **9-15%** and memory sits at **1107 MiB**, driven by
    `explorer.exe`, `msedgewebview2.exe`, `SearchHost.exe` and PowerToys (from
    `nvidia-smi --query-compute-apps`). A 10% ceiling is therefore BELOW this machine's
    floor and the gate could never open -- it would stall the campaign forever while
    measuring nothing. The ceiling is raised to `gpu_idle_pct` (default 25%: above the
    measured 15% peak of desktop noise, far below a game client, which pins 60-100%) and
    a VRAM ceiling is added, because memory is the sharper discriminator: a game holds
    GB, the idle desktop holds ~1.1 GB.

    The exact, non-threshold discriminator is still the game-process check; the GPU
    numbers are the backstop for a competing workload that is not on the name list.
    """
    gpu_idle_pct = DEFAULT_GPU_IDLE_PCT if gpu_idle_pct is None else gpu_idle_pct
    gpu_idle_mem_mib = (
        DEFAULT_GPU_IDLE_MEM_MIB if gpu_idle_mem_mib is None else gpu_idle_mem_mib)
    blocking = blocking_games(games)
    if blocking is None or blocking:
        return False
    if util is None or util >= gpu_idle_pct:
        return False
    return mem_mib is not None and mem_mib < gpu_idle_mem_mib


def signature(out, tag):
    mem, util, raw = gpu_probe()
    games = games_probe()
    # `games` is the full observation (the campaign's honest machine record); `games_blocking`
    # is the subset that voids an arm. Recording both keeps the non-blocking helpers visible
    # instead of filtered out of the evidence.
    sig = {"tag": tag, "at": datetime.datetime.now().isoformat(), "gpu": raw,
           "gpu_mem_mib": mem, "gpu_util_pct": util, "games": games,
           "games_blocking": blocking_games(games)}
    with io.open(os.path.join(out, "signatures.jsonl"), "a", encoding="utf-8") as fh:
        fh.write(json.dumps(sig) + "\n")
    log("sig %-30s gpu=%-16s games=%s blocking=%s"
        % (tag, raw, games or "none", sig["games_blocking"]))
    return sig


def wait_for_quiet(out, tag, quiet_sec=30, poll_sec=5, gpu_idle_pct=DEFAULT_GPU_IDLE_PCT,
                   gpu_idle_mem_mib=DEFAULT_GPU_IDLE_MEM_MIB, timeout_sec=10800):
    """Block until the machine has been quiet for `quiet_sec` CONTINUOUSLY.

    WAIT, not void (owner decision 2026-09-03): a game client ran on this box twice that
    day, and an arm started under one is contaminated in a way no post-hoc filter can
    price. A single quiet sample is not enough -- a game client's GPU use is bursty, so
    the gate requires `ceil(quiet_sec / poll_sec)` CONSECUTIVE quiet samples and resets
    the streak on any dirty one.

    Returns the number of seconds waited. Raises `RuntimeError` at `timeout_sec` rather
    than proceeding dirty: a campaign that silently measures a contaminated machine is
    worse than one that stops and says so.
    """
    need = max(1, int(math.ceil(float(quiet_sec) / float(poll_sec))))
    t0 = time.time()
    streak = 0
    announced = False
    while True:
        games = games_probe()
        mem, util, _ = gpu_probe()
        if machine_is_quiet(games, util, mem, gpu_idle_pct, gpu_idle_mem_mib):
            streak += 1
            if streak >= need:
                waited = round(time.time() - t0, 1)
                if waited > poll_sec:
                    log("QUIET-GATE %s: machine quiet after %.1fs" % (tag, waited))
                return waited
        else:
            if not announced or streak:
                log("QUIET-GATE %s: waiting (games=%s gpu_util=%s gpu_mem=%s)"
                    % (tag, games or "none", util, mem))
                announced = True
            streak = 0
        if time.time() - t0 > timeout_sec:
            with io.open(os.path.join(out, "signatures.jsonl"), "a", encoding="utf-8") as fh:
                fh.write(json.dumps({"tag": "%s:quiet-gate-timeout" % tag,
                                     "at": datetime.datetime.now().isoformat(),
                                     "games": games, "gpu_util_pct": util,
                                     "gpu_mem_mib": mem}) + "\n")
            raise RuntimeError(
                "quiet-machine gate timed out after %ds waiting for %s "
                "(games=%r gpu_util=%r gpu_mem=%r) -- campaign STOPPED rather than run dirty"
                % (timeout_sec, tag, games, util, mem))
        time.sleep(poll_sec)


class ArmMonitor(object):
    """Samples for game processes WHILE an arm runs; `dirty` latches true.

    A pre/post signature pair cannot see a game that started and exited inside a
    twelve-minute arm, which is precisely the window that matters.
    """

    def __init__(self, out, tag, interval_sec=30):
        self.out, self.tag, self.interval = out, tag, interval_sec
        self.dirty = False
        self.observed = []
        self._stop = threading.Event()
        self._thread = None

    def _loop(self):
        while not self._stop.wait(self.interval):
            games = games_probe()
            blocking = blocking_games(games)
            if blocking:
                self.dirty = True
                if games not in self.observed:
                    self.observed.append(games)
                mem, util, raw = gpu_probe()
                with io.open(os.path.join(self.out, "signatures.jsonl"), "a",
                             encoding="utf-8") as fh:
                    fh.write(json.dumps({"tag": "%s:mid-DIRTY" % self.tag,
                                         "at": datetime.datetime.now().isoformat(),
                                         "games": games, "gpu": raw,
                                         "gpu_mem_mib": mem, "gpu_util_pct": util}) + "\n")
                log("DIRTY %s: game process observed mid-arm: %s" % (self.tag, games))

    def __enter__(self):
        self._thread = threading.Thread(target=self._loop, daemon=True)
        self._thread.start()
        return self

    def __exit__(self, *exc):
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=5)
        return False


def progress(out, record):
    with io.open(os.path.join(out, "progress.jsonl"), "a", encoding="utf-8") as fh:
        fh.write(json.dumps(record) + "\n")


def _subprocess_runner(cmd, cwd, log_path, env):
    with io.open(log_path, "w", encoding="utf-8", errors="replace") as fh:
        return subprocess.run(cmd, cwd=cwd, stdout=fh, stderr=subprocess.STDOUT,
                              text=True, env=env).returncode


def _move_aside(armdir):
    """Preserve a contaminated attempt's evidence next to the re-run, never delete it."""
    for n in range(1, 100):
        dest = "%s.dirty%d" % (armdir, n)
        if not os.path.exists(dest):
            shutil.move(armdir, dest)
            return dest
    raise RuntimeError("too many dirty attempts for %s" % armdir)


def run_arm(out, corpus, target, overlap, rep, threshold_chars=None, runner=None,
            deadline_ms=CAMPAIGN_DEADLINE_MS, quiet_gate=True, max_dirty_retries=2,
            quiet_kwargs=None):
    """Run one arm. Returns the exit code, or None when the arm was already done.

    Resumable: an arm dir holding `ARM.done` is skipped without spawning anything,
    and `ARM.done` is written only after a zero exit — so a killed overnight driver
    restarts on the first arm that did not finish.

    Quiet-machine gate (owner decision 2026-09-03): the arm BLOCKS until the machine is
    quiet, and an arm during which a game client appeared is moved aside and re-run
    rather than counted. `ARM.done` is never written for a dirty attempt, so a dirty arm
    that exhausts its retries stays resumable instead of poisoning the roll-up.
    """
    slug = corpus.replace("/", "_")
    tag = arm_tag(target, overlap, rep, deadline_ms)
    armdir = os.path.join(out, slug, tag)
    os.makedirs(armdir, exist_ok=True)
    started = datetime.datetime.now()
    if os.path.exists(os.path.join(armdir, ARM_DONE)):
        log("SKIP (done) %s / %s" % (slug, tag))
        progress(out, {"arm": tag, "corpus": corpus, "rc": None,
                       "started": started.isoformat(), "finished": started.isoformat(),
                       "seconds": 0.0, "skipped": True})
        return None

    for attempt in range(max_dirty_retries + 1):
        os.makedirs(armdir, exist_ok=True)
        cfg = os.path.join(armdir, "arm.yaml")
        # ABSOLUTE. The evidence path is resolved inside the WORKER process, whose working
        # directory is not jseval's, so a relative path silently writes nothing anywhere the
        # driver looks — measured on the 2026-09-03 smoke arm, which produced
        # `trunc_available: false` with the key correctly forwarded.
        io.open(cfg, "w", encoding="utf-8", newline="").write(
            arm_yaml(target, overlap, threshold_chars,
                     os.path.abspath(os.path.join(armdir, EVIDENCE_NAME)), deadline_ms))
        # Every arm is a full reindex: chunk size is a fingerprint input, so there is no
        # `--skip-ingest` trick and no shared index to hold still.
        cmd = [sys.executable, "-m", "jseval", "run", "--dataset", corpus, "--modes", MODES,
               "--start-backend", "--clean", "--pipeline", "--json",
               "--output-dir", armdir, "--config", cfg]

        waited = 0.0
        if quiet_gate:
            waited = wait_for_quiet(out, "%s/%s" % (slug, tag), **(quiet_kwargs or {}))
        pre = signature(out, "%s/%s:pre" % (slug, tag))
        log("ARM %s / %s target=%d overlap=%d min_tokens=%d deadline=%dms attempt=%d"
            % (slug, tag, target, overlap, min_tokens_for(target), deadline_ms, attempt + 1))
        t0 = time.time()
        with ArmMonitor(out, "%s/%s" % (slug, tag)) as mon:
            rc = (runner or _subprocess_runner)(
                cmd, HERE, os.path.join(armdir, "arm.log"), child_env())
        seconds = round(time.time() - t0, 1)
        post = signature(out, "%s/%s:post" % (slug, tag))
        dirty = (bool(mon.dirty) or bool(pre.get("games_blocking"))
                 or bool(post.get("games_blocking")))
        log("ARM %s / %s exit=%s in %.1fs dirty=%s" % (slug, tag, rc, seconds, dirty))

        metrics = capture_arm_metrics(
            armdir, target, overlap, threshold_chars=threshold_chars,
            deadline_ms=deadline_ms, machine_dirty=dirty)
        progress(out, {"arm": tag, "corpus": corpus, "rc": rc, "attempt": attempt + 1,
                       "deadline_ms": deadline_ms, "quiet_wait_sec": waited,
                       "machine_dirty": dirty,
                       "started": started.isoformat(),
                       "finished": datetime.datetime.now().isoformat(),
                       "seconds": seconds, "skipped": False})
        if not dirty:
            if rc == 0:
                completion = arm_contract(
                    target, overlap, threshold_chars, deadline_ms,
                    run_id=metrics.get("run_id"), git_sha=metrics.get("git_sha"))
                io.open(os.path.join(armdir, ARM_COMPLETION), "w", encoding="utf-8").write(
                    json.dumps(completion, indent=2, sort_keys=True) + "\n")
                touch(os.path.join(armdir, ARM_DONE))
            return rc
        if attempt >= max_dirty_retries:
            log("ARM %s / %s DIRTY and out of retries — left resumable, NOT counted"
                % (slug, tag))
            return rc
        aside = _move_aside(armdir)
        log("ARM %s / %s DIRTY (%s) — moved to %s, re-running"
            % (slug, tag, mon.observed or "pre/post signature", os.path.basename(aside)))
        started = datetime.datetime.now()
    return rc


def _run_dir(armdir):
    """The jseval run directory jseval created under `--output-dir <armdir>` (latest wins)."""
    ps = sorted(glob.glob(os.path.join(armdir, "*", "summary.json")))
    return os.path.dirname(ps[-1]) if ps else None


def _read_json(path):
    if not path or not os.path.exists(path):
        return {}
    try:
        return json.load(io.open(path, encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def _first_not_none(*values):
    for v in values:
        if v is not None:
            return v
    return None


def _chunk_branch(summary, mode):
    """Did the chunk branch actually run? Tri-state, never a silent false.

    An arm whose chunk branch never fired measured the doc-level legs only, and no chunk-size
    conclusion may rest on it — so the roll-up has to be able to say "it ran", "it did not" and
    "unknown" as three different things.

    Read from `per_mode.<mode>.pipeline_tracking.observed` plus `stage_timing_stats.chunk_merge_ms`,
    NOT from the per-query `chunkMergeApplied` field. Measured on the 2026-09-03 smoke arms: that
    field is `None` on every row of both arms (`artifacts.py:248` copies it straight from the search
    response, which does not carry it in this run mode), so counting its truthiness reported
    `applied: 0` for two arms whose `pipeline_tracking.observed` contained `chunk_merge` and whose
    `stage_timing_stats` carried a `chunk_merge_ms` — absent read as false, which is exactly the
    tri-state conflation this project has a named rule about.
    """
    pm = ((summary.get("per_mode") or {}).get(mode)) or {}
    tracking = pm.get("pipeline_tracking")
    timing = pm.get("stage_timing_stats") or {}
    if tracking is None and not timing:
        return {"ran": None, "evidence": "no pipeline_tracking and no stage_timing_stats"}
    observed = (tracking or {}).get("observed")
    if observed is None:
        return {"ran": None, "evidence": "pipeline_tracking present but carries no `observed` list"}
    ran = "chunk_merge" in observed
    return {
        "ran": ran,
        "evidence": "pipeline_tracking.observed",
        "chunk_merge_ms_present": "chunk_merge_ms" in timing,
    }


def load(armdir, mode="hybrid"):
    """One replicate's record, or None when the arm produced no `summary.json`."""
    rd = _run_dir(armdir)
    if not rd:
        return None
    d = _read_json(os.path.join(rd, "summary.json"))
    pm = (d.get("per_mode") or {}).get(mode) or {}
    am = pm.get("aggregate_metrics") or {}
    acc = _read_json(
        os.path.join(rd, "projections", "staged_recall_accounting.json")).get("aggregate") or {}
    trunc = _read_json(os.path.join(rd, "projections", "splade_truncation.json"))
    ingest = d.get("ingest") or {}
    run_metrics = d.get("run_metrics") or {}
    prim = (ingest.get("pipeline_summary") or {}).get("primary_indexing") or {}
    ce = d.get("ce_coverage") or {}
    cev = ce.get("verdict")
    ce_mode = (ce.get("per_mode") or {}).get(mode) or {}
    cc = d.get("chunk_completeness") or {}
    env = d.get("env_overrides") or {}
    cmp_ = pm.get("comparable")
    return {
        "run_id": os.path.basename(rd),
        "run_dir": rd,
        "git_sha": d.get("git_sha"),
        "ndcg": am.get(METRIC_NDCG),
        "r10": am.get(METRIC_R10),
        "p1": am.get(METRIC_P1),
        # Read back from the run's OWN record of what it was given, not from the driver's
        # intent: `run.py:222` writes `env_overrides` into the summary, so a campaign
        # constant that failed to reach an arm is visible in that arm's metrics rather
        # than assumed from the yaml the driver wrote.
        "env_deadline_ms": env.get(KEY_DEADLINE),
        "env_target_tokens": env.get(KEY_TARGET),
        "env_overlap_tokens": env.get(KEY_OVERLAP),
        "env_min_tokens": env.get(KEY_MIN),
        "env_threshold_chars": env.get(KEY_THRESHOLD),
        # The deadline control's whole point is this pair: coverage and the silent-drop
        # count are what `degraded-ce` is computed from, so they are recorded per arm.
        "ce_coverage_frac": ce_mode.get("coverage"),
        "ce_silent_drops": ce_mode.get("silent_drops"),
        "ce_eligible": ce_mode.get("eligible"),
        "chunk_docs": cc.get("observed"),
        "chunk_parents_expected": cc.get("expected"),
        "chunk_completeness_verdict": cc.get("verdict"),
        "chunk_threshold_chars": cc.get("threshold_chars"),
        # Absent on every run today (see METRIC_R50): recorded as null, never back-filled from R@10.
        "r50": am.get(METRIC_R50),
        "leak": acc.get("leak_rate"),
        "union": acc.get("leg_union_recall"),
        "trunc_rate": trunc.get("truncation_rate"),
        "trunc_available": bool(trunc.get("available")),
        "trunc_reason": trunc.get("reason"),
        # Reported by the backend on /api/status as `indexSizeBytes` and carried into the run
        # summary by `jseval/ingest.py:140` -- so it is read, not summed off disk or guessed.
        "index_bytes": ingest.get("index_size_bytes"),
        # `pipeline_summary.primary_indexing` is NOT emitted on every run shape -- the 2026-09-03
        # smoke arm on mixed/legal-clerc-200 had `stages` (phase completion times) and no
        # `primary_indexing` block at all, so this read is null there. `ingest.docs_per_sec`
        # (docs_indexed / elapsed_sec) is always present and is the end-to-end indexing rate the
        # decision rule's throughput clause actually needs. Both are recorded, and `docs_s_source`
        # names which one the `docs_s` column came from -- an unlabelled fallback would make a 10%
        # throughput clause compare two different quantities across arms.
        "primary_docs_s": run_metrics.get("primary_docs_s", prim.get("docs_per_s")),
        "enrich_docs_s": run_metrics.get("enrich_docs_s", ingest.get("docs_per_sec")),
        "docs_s": _first_not_none(
            run_metrics.get("primary_docs_s"), prim.get("docs_per_s"), ingest.get("docs_per_sec")),
        "docs_s_source": (
            "run_metrics.primary_docs_s"
            if run_metrics.get("primary_docs_s") is not None
            else "pipeline_summary.primary_indexing.docs_per_s"
            if prim.get("docs_per_s") is not None
            else "ingest.docs_per_sec"
            if ingest.get("docs_per_sec") is not None
            else None),
        "docs_indexed": ingest.get("docs_indexed"),
        "elapsed_sec": ingest.get("elapsed_sec"),
        # Whether the chunk branch actually ran, per query. A chunk-size arm whose chunk branch never
        # fired is measuring the doc-level legs only, which no chunk-size conclusion may rest on.
        "chunk_branch": _chunk_branch(d, mode),
        "ce_cov": cev,
        "comparable": cmp_,
        "admissible": cev == "ok" and cmp_ is True,
    }


def capture_arm_metrics(armdir, target=None, overlap=None, mode="hybrid",
                        threshold_chars=None, deadline_ms=None, machine_dirty=None):
    """Write `<armdir>/arm-metrics.json`. Returns the document."""
    rec = load(armdir, mode=mode)
    doc = {
        "target_tokens": target,
        "overlap_tokens": overlap,
        "min_tokens": min_tokens_for(target) if target is not None else None,
        "threshold_override": threshold_chars,
        "threshold_chars": (SHIPPED_THRESHOLD_CHARS if threshold_chars is None
                            else int(threshold_chars)),
        "deadline_ms": deadline_ms,
        "rerank_deadline_ms": deadline_ms,
        # The arm's own window, not a property of the run: an arm whose window saw a game
        # client is re-run, so this must be readable per attempt and never silently absent.
        "machine_dirty": machine_dirty,
        "mode": mode,
        "captured_at": datetime.datetime.now().isoformat(),
    }
    if rec is None:
        doc["note"] = "no summary.json under %s -- arm produced no run" % armdir
        doc.update({k: None for k in STAT_KEYS})
    else:
        doc.update(rec)
    io.open(os.path.join(armdir, "arm-metrics.json"), "w", encoding="utf-8").write(
        json.dumps(doc, indent=2, sort_keys=True) + "\n")
    return doc


def parse_arms(raw):
    """`"500/50,128/0"` -> `[(500, 50), (128, 0)]`; empty/None -> the full 12-arm matrix.

    The selector exists because the campaign is NOT one uniform matrix: §K.6's order runs
    incumbent replicates first, then the full matrix per arm corpus, then the multilingual
    and scifact CONTROLS at incumbent+winner only. Encoding that as separate driver
    invocations into one `--out` tree keeps every arm resumable and keeps the arm matrix
    itself unchanged.
    """
    if not raw:
        return arm_matrix()
    arms = []
    for item in split_csv(raw):
        t, _, o = item.partition("/")
        arms.append((int(t), int(o)))
    return arms


def decision_bearing_nulls(armdir, mode="hybrid"):
    """Names of the decision-bearing columns that came back null for an arm.

    An admissible arm with a null in one of these is a campaign stop: the roll-up would
    print `-` for a clause the decision rule reads, and a rule evaluated over `-` is not
    the rule that was pre-registered.
    """
    rec = load(armdir, mode=mode)
    if rec is None:
        return list(DECISION_BEARING)
    return [k for k in DECISION_BEARING if rec.get(k) is None]


def completed_arm_problems(armdir, expected=None, mode="hybrid", require_chunk_branch=None):
    """Return reasons an arm must stop the phase instead of earning completion.

    ``run_arm`` owns process execution and contamination retrying.  This second boundary owns the
    evidence contract: the marker is reusable only when its structured completion identity matches
    the requested arm, the source run/build are named, the machine window is clean, pipeline
    execution is known, and the admissibility/projection gates hold.
    """
    problems = []
    if not os.path.exists(os.path.join(armdir, ARM_DONE)):
        problems.append("ARM.done missing")
    rec = load(armdir, mode=mode)
    metrics = _read_json(os.path.join(armdir, "arm-metrics.json"))
    completion = _read_json(os.path.join(armdir, ARM_COMPLETION))
    if rec is None:
        problems.append("arm metrics missing")
    else:
        if not rec.get("admissible"):
            problems.append("arm inadmissible (CE coverage/comparability)")
        nulls = [k for k in DECISION_BEARING if rec.get(k) is None]
        if nulls:
            problems.append("decision-bearing nulls: %s" % ", ".join(nulls))
        ran = (rec.get("chunk_branch") or {}).get("ran")
        if ran is None:
            problems.append("chunk branch execution unknown")
        elif require_chunk_branch is True and ran is not True:
            problems.append("chunk branch did not run on an arm corpus")
        if not rec.get("git_sha"):
            problems.append("source build identity missing")
    if not metrics:
        problems.append("arm-metrics.json missing or unreadable")
    elif metrics.get("machine_dirty") is not False:
        problems.append("machine window is not proven clean")
    if not completion:
        problems.append("structured arm completion missing or unreadable")

    if expected:
        identity_keys = (
            "target_tokens", "overlap_tokens", "min_tokens", "threshold_override",
            "threshold_chars", "deadline_ms")
        for key in identity_keys:
            if metrics and metrics.get(key) != expected.get(key):
                problems.append("arm metrics %s mismatch: expected %r, got %r"
                                % (key, expected.get(key), metrics.get(key)))
            if completion and completion.get(key) != expected.get(key):
                problems.append("completion %s mismatch: expected %r, got %r"
                                % (key, expected.get(key), completion.get(key)))
        if rec is not None:
            env_expected = {
                "env_target_tokens": str(expected["target_tokens"]),
                "env_overlap_tokens": str(expected["overlap_tokens"]),
                "env_min_tokens": str(expected["min_tokens"]),
                "env_deadline_ms": str(expected["deadline_ms"]),
                "env_threshold_chars": (None if expected["threshold_override"] is None
                                        else str(expected["threshold_override"])),
            }
            for key, value in env_expected.items():
                if rec.get(key) != value:
                    problems.append("run summary %s mismatch: expected %r, got %r"
                                    % (key, value, rec.get(key)))
            if rec.get("chunk_threshold_chars") != expected["threshold_chars"]:
                problems.append("effective chunk threshold mismatch: expected %r, got %r"
                                % (expected["threshold_chars"],
                                   rec.get("chunk_threshold_chars")))
            if completion:
                for key in ("run_id", "git_sha"):
                    if completion.get(key) != rec.get(key):
                        problems.append("completion %s does not bind the selected run"
                                        % key)
    return problems


def do_run(a):
    # Closeout removes the four temporary key consumers.  Refuse before creating or deleting any
    # output marker, otherwise twelve default-policy indexes could be mislabeled as a sweep.
    require_sweep_channel()
    os.makedirs(a.out, exist_ok=True)
    arms = parse_arms(getattr(a, "arms", None))
    quiet_kwargs = {"quiet_sec": a.quiet_sec, "gpu_idle_pct": a.gpu_idle_pct,
                    "gpu_idle_mem_mib": a.gpu_idle_mem_mib}
    run_marker = os.path.join(a.out, "%s%s" % (a.phase, RUN_DONE))
    if os.path.exists(run_marker):
        os.remove(run_marker)
    log("PLAN corpora=%s arms=%s reps=%d deadline=%dms"
        % (split_csv(a.corpora), [arm_label(t, o) for t, o in arms], a.reps, a.deadline_ms))
    for corpus in split_csv(a.corpora):
        slug = corpus.replace("/", "_")
        os.makedirs(os.path.join(a.out, slug), exist_ok=True)
        corpus_marker = os.path.join(a.out, slug, "%s%s" % (a.phase, CORPUS_DONE))
        if os.path.exists(corpus_marker):
            os.remove(corpus_marker)
        for target, overlap in arms:
            for rep in range(a.reps):
                rc = run_arm(a.out, corpus, target, overlap, rep, a.threshold_chars,
                             deadline_ms=a.deadline_ms, quiet_gate=not a.no_quiet_gate,
                             quiet_kwargs=quiet_kwargs)
                tag = arm_tag(target, overlap, rep, a.deadline_ms)
                armdir = os.path.join(a.out, slug, tag)
                if rc not in (None, 0):
                    raise RuntimeError(
                        "campaign stopped: %s/%s exited %s" % (slug, tag, rc))
                expected = arm_contract(
                    target, overlap, a.threshold_chars, a.deadline_ms)
                problems = completed_arm_problems(
                    armdir, expected=expected, mode=a.mode,
                    require_chunk_branch=corpus in ARM_CORPORA)
                if problems:
                    # A zero-exit arm can have incomplete projections.  Leaving ARM.done would
                    # make every resume skip the broken attempt, so retract only this marker; all
                    # logs and run artifacts remain for diagnosis.
                    arm_marker = os.path.join(armdir, ARM_DONE)
                    if os.path.exists(arm_marker):
                        os.remove(arm_marker)
                    completion_marker = os.path.join(armdir, ARM_COMPLETION)
                    if os.path.exists(completion_marker):
                        os.remove(completion_marker)
                    raise RuntimeError(
                        "campaign stopped: %s/%s: %s"
                        % (slug, tag, "; ".join(problems)))
        touch(corpus_marker)
    # Phase-prefixed: the campaign is several `run` invocations into ONE `--out` tree
    # (§K.6's order), so a single `RUN.done` at the root would be written by the first
    # phase and read by the watcher as "the campaign finished".
    touch(run_marker)
    log("RUN COMPLETE (phase=%s)" % a.phase)


def mean_sigma(values, floor):
    """`(mean, sigma, sigma_is_floor)` over the non-null values.

    Sigma is the SAMPLE standard deviation (`statistics.stdev`) when n>=2. With a
    single replicate there is no observed spread, so the run-level noise floor is
    reported rather than a fake sigma=0.
    """
    vals = [v for v in values if isinstance(v, (int, float)) and not isinstance(v, bool)]
    if not vals:
        return (None, None, False)
    mean = sum(vals) / len(vals)
    if len(vals) >= 2:
        return (mean, statistics.stdev(vals), False)
    return (mean, floor, True)


def summarize_arm(records, floor):
    """Roll one arm's replicates up. Void replicates are excluded from every mean AND sigma."""
    adm = [r for r in records if r and r.get("admissible")]
    out = {"n_total": len([r for r in records if r]), "n_admissible": len(adm),
           "void": not adm}
    for key in STAT_KEYS:
        mean, sigma, from_floor = mean_sigma([r.get(key) for r in adm], floor)
        out[key] = {"mean": mean, "sigma": sigma, "sigma_is_floor": from_floor}
    src = (adm or [r for r in records if r] or [None])[0]
    out["ce_cov"] = src.get("ce_cov") if src else None
    out["comparable"] = src.get("comparable") if src else None
    return out


def fmt_ms(stat, digits=4):
    if stat["mean"] is None:
        return "-"
    if stat["sigma"] is None:
        return "%.*f" % (digits, stat["mean"])
    return "%.*f +/- %.*f%s" % (digits, stat["mean"], digits, stat["sigma"],
                                "*" if stat["sigma_is_floor"] else "")


def fmt_one(stat, digits=4):
    return "-" if stat["mean"] is None else "%.*f" % (digits, stat["mean"])


def fmt_mb(stat):
    return "-" if stat["mean"] is None else "%.1f" % (stat["mean"] / (1024.0 * 1024.0))


def do_analyze(a):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    corpora = split_csv(a.corpora)
    arms = parse_arms(getattr(a, "arms", None))
    print("| corpus | arm | ms | n adm | nDCG@10 | R@10 | P@1 | union | leak | trunc "
          "| chunks | index MB | docs/s | ce_cov | drops | admissible |")
    print("| :-- | :-- | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: "
          "| :-- | --: | :-- |")
    for corpus in corpora:
        slug = corpus.replace("/", "_")
        for target, overlap in arms:
            records = []
            for rep in range(a.reps):
                records.append(load(
                    os.path.join(a.out, slug, arm_tag(target, overlap, rep, a.deadline_ms)),
                    mode=a.mode))
            if not any(records):
                continue
            s = summarize_arm(records, a.floor)
            admissible = "**VOID**" if s["void"] else "YES"
            print("| %s | %s | %d | %d | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s "
                  "| %s | %s |" % (
                      corpus, arm_label(target, overlap), a.deadline_ms, s["n_admissible"],
                      fmt_ms(s["ndcg"]), fmt_ms(s["r10"]), fmt_one(s["p1"]),
                      fmt_one(s["union"]), fmt_one(s["leak"]), fmt_one(s["trunc_rate"]),
                      fmt_one(s["chunk_docs"], 0), fmt_mb(s["index_bytes"]),
                      fmt_one(s["docs_s"], 1), s["ce_cov"] or "-",
                      fmt_one(s["ce_silent_drops"], 0), admissible))
    print("\n`*` = sigma is the --floor noise floor (%.4f), not an observed replicate spread "
          "(n=1)." % a.floor)
    print("`ms` = the arm's `%s`. It is an ARM-INVARIANT campaign constant (%d) for every "
          "sweep arm; the %d row is the shipped-deadline neutrality control. Campaign "
          "numbers are campaign-internal and are NOT comparable to the register's "
          "shipped-deadline baseline rows." % (KEY_DEADLINE, CAMPAIGN_DEADLINE_MS,
                                               SHIPPED_DEADLINE_MS))
    print("`R@50` is not reported anywhere: jseval emits [nDCG@10, AP@10, RR@10, R@10, P@1] "
          "(`jseval/scoring.py:9`) and never R@50. The decision rule uses R@10 + "
          "`leg_union_recall` (the `union` column) instead — 916 §K.5's named deviation.")
    print("A `**VOID**` arm has no admissible replicate; void replicates are excluded from "
          "every mean and sigma above.")
    print("`docs/s` is whichever source `docs_s_source` names in each arm's arm-metrics.json; on "
          "runs without a `pipeline_summary.primary_indexing` block it is `ingest.docs_per_sec` "
          "(docs_indexed / elapsed_sec), which is the end-to-end indexing rate.")


def main():
    ap = argparse.ArgumentParser(
        description="Tempdoc 916 chunk-size sweep: 12 (target, overlap) arms, each a full reindex.")
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("run", "analyze"):
        s = sub.add_parser(name)
        s.add_argument("--out", required=True)
        s.add_argument("--corpora", default=DEFAULT_CORPORA,
                       help="the two chunked English corpora by default")
        s.add_argument("--reps", type=int, default=1,
                       help="replicates per arm; every arm is a full reindex, so 1 by default")
        s.add_argument("--threshold-chars", type=int, default=None,
                       help="%s; HELD FIXED at its default across the sweep -- not an arm axis"
                            % KEY_THRESHOLD)
        s.add_argument("--floor", type=float, default=0.0068,
                       help="run-level noise floor reported as sigma when an arm has n=1")
        s.add_argument("--mode", default="hybrid", help="per_mode key the roll-up reads")
        s.add_argument("--arms", default=None,
                       help="`500/50,128/0`; default = the full 12-arm matrix")
        s.add_argument("--deadline-ms", type=int, default=CAMPAIGN_DEADLINE_MS,
                       help="%s; ARM-INVARIANT campaign constant, not an axis. %d is the "
                            "shipped-deadline control." % (KEY_DEADLINE, SHIPPED_DEADLINE_MS))
        s.add_argument("--phase", default="",
                       help="prefix for the CORPUS.done / RUN.done markers so several "
                            "invocations can share one --out tree")
        s.add_argument("--quiet-sec", type=int, default=30,
                       help="consecutive quiet seconds required before an arm starts")
        s.add_argument("--gpu-idle-pct", type=int, default=DEFAULT_GPU_IDLE_PCT,
                       help="GPU utilization ceiling for `quiet` (measured desktop "
                            "baseline on this box is 9-15%%; see machine_is_quiet)")
        s.add_argument("--gpu-idle-mem-mib", type=int, default=DEFAULT_GPU_IDLE_MEM_MIB,
                       help="GPU memory ceiling for `quiet` (idle baseline ~1107 MiB)")
        s.add_argument("--no-quiet-gate", action="store_true",
                       help="skip the quiet-machine gate (tests and dry runs only)")
    a = ap.parse_args()
    (do_run if a.cmd == "run" else do_analyze)(a)


if __name__ == "__main__":
    main()
