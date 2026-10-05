"""Tempdoc 844 D3 — the record a jseval-started backend leaves for the other lifecycle.

Two dev-stack lifecycles cannot see each other (844 §6.1). The MCP tools know only the runs
`scripts/dev/dev-runner.cjs` started (`tmp/dev-runner/active.json` + per-run `run.json`);
`jseval` starts its own backend on the hardcoded port 33221 and appears nowhere. That already
cost a measurement: a `runHeadlessEval` backend was invisible to `quick_health`, so a "free"
verdict preceded a 100%-GPU neighbour (session shard `bccfc163`, 2026-08-14).

`quick_health` now *probes* well-known ports (844 P5), which answers "something is listening"
but not "what, whose tree, holding what". This module supplies the missing half: the producer
declares its own identity, and the probe stays as the fallback that keeps the register honest
about everything that never registered.

**Location, and why it cannot confuse the dev-runner.** Records live in
``<main-checkout>/tmp/dev-runner/foreign/``. The dev-runner's state root is enumerated NOWHERE —
it only ever touches explicitly named children: ``runs/`` (`dev-runner.cjs:60`, the only directory
it globs, in `pruneHistoricRuns` at `:387`), ``active.json`` (`:61`), ``active.lock.json``
(`:1174`), ``op-leases.json`` (`:64`), ``sessions/`` (`:68`) and ``interference-events.ndjson``
(`:2271`). So a sibling ``foreign/`` directory is invisible to its lease and admission logic and
cannot be mistaken for one of its own runs — while still sitting where the MCP readers, which are
already main-checkout-scoped, can find it. The 271/542 ownership model is deliberately untouched:
nothing here writes ``active.json`` or an op-lease.

**Crash safety.** A killed `jseval` never runs its cleanup, so a leaked record must never
masquerade as a live backend. This module therefore records only *declared* facts (pid, port,
tree) and never a liveness claim; deciding whether a record is live is the reader's job, from the
pid and the port. See `probeForeignRuns` in `scripts/dev/justsearch-dev-mcp/server.mjs`.

**Failure policy.** Registration is best-effort and never raises: an eval run must not fail
because a bookkeeping file could not be written. A failed write is logged at WARNING and leaves no
record — which the reader reports as "not registered, only observed", i.e. today's P5 behaviour.
The inverse (a write that half-succeeds) is excluded by construction: every write is a temp file
plus an atomic ``os.replace``, so a torn record is never readable.
"""

from __future__ import annotations

import datetime
import json
import logging
import os
import tempfile
from pathlib import Path

from . import agent_identity
from ._paths import main_repo_root

log = logging.getLogger(__name__)

#: Bump only for a breaking change to the record shape; the reader refuses versions it does not
#: know rather than guessing at fields (an explicit unknown beats a confident default).
SCHEMA_VERSION = 1

PRODUCER = "jseval"

#: Directory name under the dev-runner state root. Deliberately NOT ``runs`` — see the module
#: docstring: ``runs/`` is the one directory the dev-runner globs and prunes.
REGISTER_DIRNAME = "foreign"


def register_dir() -> Path:
    """The directory foreign-run records live in.

    Honors ``JUSTSEARCH_DEV_RUNNER_STATE_ROOT`` exactly as ``dev-runner.cjs:57`` does, so an
    isolated dev-runner (integration tests, throwaway stacks) gets an isolated register too and a
    test run can never write into the real main checkout.
    """
    override = os.environ.get("JUSTSEARCH_DEV_RUNNER_STATE_ROOT")
    if override:
        return Path(override).resolve() / REGISTER_DIRNAME
    return main_repo_root() / "tmp" / "dev-runner" / REGISTER_DIRNAME


def record_id(pid: int) -> str:
    """One record per producer process. A pid cannot host two backends, so this is unique, and a
    re-used pid overwrites the dead record it would otherwise shadow."""
    return f"{PRODUCER}-{int(pid)}"


def record_path(pid: int) -> Path:
    return register_dir() / f"{record_id(pid)}.json"


def _session_id() -> str | None:
    """The agent session that owns this backend: its readable label, by the repository's one
    identity rule (`agent_identity.py` -> `scripts/dev/agent-identity.mjs`), the same rule the MCP
    server and the dev-runner use, so "who owns it" means the same thing on both sides of the
    register. Never the retired shared pointer file; unknown is None.
    """
    return agent_identity.session_label()


def build_record(
    *,
    pid: int,
    port: int,
    repo_root: Path,
    data_dir: Path,
    inference_requested: bool,
) -> dict:
    """The v1 record, as a plain dict. Pure — separated from the write so it is testable.

    Every field is something the producer actually knows at spawn time. Notably absent is any
    claim about liveness or health: the record says "I started this", not "this is up".

    ``gpuBound`` is a deliberate explicit unknown rather than a convenient default. jseval boots
    ``:modules:ui:runHeadlessEval``, whose Worker loads the ONNX encoder stack, and this repo has
    no CPU fallback — so a neighbour SHOULD assume GPU contention. But the producer does not
    measure GPU residency, so writing ``true`` would be reporting state it did not verify
    (844 §12.2). ``"unverified"`` says what is true; the reader carries the standing warning.
    """
    return {
        "schemaVersion": SCHEMA_VERSION,
        "producer": PRODUCER,
        "recordId": record_id(pid),
        "pid": int(pid),
        "ports": {"api": int(port)},
        "repoRoot": str(repo_root),
        "dataDir": str(data_dir),
        "workload": "eval-backend",
        "inferenceRequested": bool(inference_requested),
        "gpuBound": "unverified",
        "sessionId": _session_id(),
        # The owning session's harness process; null for an unknown owner (no agent, no evidence).
        "owner": agent_identity.owner_block(),
        "startedAt": datetime.datetime.now(datetime.timezone.utc)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z"),
    }


def register_backend(
    *,
    pid: int,
    port: int,
    repo_root: Path,
    data_dir: Path,
    inference_requested: bool = False,
) -> Path | None:
    """Write this backend's record. Returns the path written, or ``None`` on any failure.

    Never raises (see the module docstring's failure policy).
    """
    try:
        target = record_path(pid)
        target.parent.mkdir(parents=True, exist_ok=True)
        payload = json.dumps(
            build_record(
                pid=pid,
                port=port,
                repo_root=repo_root,
                data_dir=data_dir,
                inference_requested=inference_requested,
            ),
            indent=2,
            sort_keys=True,
            ensure_ascii=False,
        )
        # Temp file in the SAME directory + os.replace: an atomic rename on both NTFS and POSIX,
        # so a reader never observes a half-written record even if this process dies mid-write.
        fd, tmp_name = tempfile.mkstemp(
            dir=str(target.parent), prefix=f".{target.stem}-", suffix=".tmp",
        )
        try:
            with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
                handle.write(payload)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(tmp_name, target)
        except BaseException:
            # A failed write must not leave a .tmp turd next to the real records.
            try:
                os.unlink(tmp_name)
            except OSError:
                pass
            raise
        log.info("Registered foreign run record: %s", target)
        return target
    except Exception as exc:  # noqa: BLE001 — bookkeeping must never fail an eval run
        log.warning("Could not write foreign run record for pid=%s: %s", pid, exc)
        return None


def unregister_backend(pid: int) -> bool:
    """Remove this backend's record. Returns True iff a record was removed.

    Keyed by pid alone so every `stop_backend` call site is covered without threading a handle
    through. Idempotent and never raises — a crash that skips this leaves a record the reader
    reports as stale rather than as live, which is the whole point of the design.
    """
    try:
        target = record_path(pid)
    except Exception:  # noqa: BLE001 — a non-integer pid is not worth failing a teardown over
        return False
    try:
        target.unlink()
        log.info("Removed foreign run record: %s", target)
        return True
    except FileNotFoundError:
        return False
    except Exception as exc:  # noqa: BLE001 — teardown must never raise; see the failure policy
        log.warning("Could not remove foreign run record %s: %s", target, exc)
        return False
