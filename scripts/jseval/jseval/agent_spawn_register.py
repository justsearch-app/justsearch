"""Tempdoc 861 W3 -- the Python-side producer contract for the `agent-spawns/` register scope.

Sibling of `run_register.py` (the `foreign/` scope's Python producer): same atomic-write idiom,
same `JUSTSEARCH_DEV_RUNNER_STATE_ROOT` override, same "registration is best-effort and never
raises" failure policy -- but writing into `agent-spawns/`, the scope for a helper an AGENT
SESSION spawned (861 Section 6.1: a ui-shot auto-serve, a worktree Vite, the OTel sink), never
`foreign/` (a JustSearch BACKEND started outside the dev-runner).

**This module MIRRORS `scripts/dev/lib/agent-spawn-record.cjs` (861 W2). It does NOT and must
NEVER import it** -- Node and Python share no runtime, so the JS schema constant is a CONTRACT
this module reproduces field-for-field, not a dependency it can pull in. The two sides staying in
sync rests on two things:

  - `SCHEMA_VERSION` is bumped on BOTH sides together, same as `AGENT_SPAWN_RECORD_SCHEMA_VERSION`
    documents on its own side ([A8] -- this scope's own constant, independent of `foreign/`'s).
  - a shape-parity test on the JS side (`861-w3-ui-shot-shape-parity.test.mjs`) spawns THIS
    module for real and runs its output through `validateAgentSpawnRecord` -- the JS reader's own
    validator -- which is the one check that would catch either side silently drifting from the
    other.

Every field name below is copied verbatim from `agent-spawn-record.cjs`'s `buildAgentSpawnRecord`:
`schemaVersion`, `recordId`, `producer`, `pid`, `creationFileTimeUtc`, `cmdlineFingerprint`,
`ownership`, `probe.kind`/`probe.port`, `startedAt`, `lease.durationSec`/`renewedAt`/`expiresAt`,
and the optional `sessionId`/`owner`/`repoRoot`/`resourceRoots.worktreeRoot`/`resourceRoots.nodeModulesRealPath`.
"""

from __future__ import annotations

import datetime
import json
import logging
import os
import re
import subprocess
import tempfile
from pathlib import Path
from typing import Any

from . import agent_identity
from ._paths import main_repo_root

log = logging.getLogger(__name__)

#: [A8] THIS SCOPE'S OWN version constant -- mirrors, never imports,
#: `AGENT_SPAWN_RECORD_SCHEMA_VERSION` in `scripts/dev/lib/agent-spawn-record.cjs`. The two are `1`
#: today by coincidence, not coupling; bump both together, and let the JS-side shape-parity test
#: catch a bump that lands on only one.
SCHEMA_VERSION = 1

#: Directory name under the dev-runner state root -- a SIBLING of `foreign/`, never inside it,
#: exactly as `agent-spawn-record.cjs`'s `AGENT_SPAWNS_REGISTER_DIRNAME` documents.
REGISTER_DIRNAME = "agent-spawns"

#: The ownership dimension (861 Section 6.2, [A5]/[A6]). Mirrors `OWNERSHIP_MODES` on the JS side.
OWNERSHIP_SESSION_OWNED = "session-owned"
OWNERSHIP_OWNERLESS_SINGLETON = "ownerless-singleton"


#: Mirrors `OWNER_IDENTITY_VERSION` in `agent-spawn-record.cjs`: a record carrying it has an owner
#: block written by the repository's identity rule; a record without it is an unknown owner's.
OWNER_IDENTITY_VERSION = 1


def register_dir() -> Path:
    """[A9] Honors `JUSTSEARCH_DEV_RUNNER_STATE_ROOT` exactly as `run_register.register_dir()` and
    `dev-runner.cjs`'s own state-root resolution do, so an isolated dev-runner (integration tests,
    throwaway stacks) gets an isolated register instead of this producer confidently writing into
    the real main checkout."""
    override = os.environ.get("JUSTSEARCH_DEV_RUNNER_STATE_ROOT")
    if override:
        return Path(override).resolve() / REGISTER_DIRNAME
    return main_repo_root() / "tmp" / "dev-runner" / REGISTER_DIRNAME


_SAFE_RECORD_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")


def assert_safe_record_id(record_id: str) -> str:
    """Mirrors `assertSafeRecordId` in `agent-spawn-record.cjs`: a record id is a FILE NAME, and
    anything that could escape the register directory is refused LOUDLY rather than sanitized
    quietly -- a silently-rewritten id would make a record unfindable by the producer that wrote
    it. Same pattern, same `..` check, same 128-char cap, on both sides of the register."""
    if (
        not isinstance(record_id, str)
        or not _SAFE_RECORD_ID_RE.match(record_id)
        or ".." in record_id
    ):
        raise ValueError(
            f"unsafe recordId {record_id!r}: expected [A-Za-z0-9][A-Za-z0-9._-]{{0,127}} with no '..'",
        )
    return record_id


def record_path(record_id: str) -> Path:
    return register_dir() / f"{assert_safe_record_id(record_id)}.json"


def _session_id() -> str | None:
    """The calling agent session's readable label, by the repository's one identity rule
    (`agent_identity.py` -> `scripts/dev/agent-identity.mjs`): the nearest harness process names
    the session and its own variable is the label. Never the retired shared pointer file and never
    `JUSTSEARCH_AGENT_SESSION_ID`; unknown is None.
    """
    return agent_identity.session_label()


def _owner() -> dict[str, Any] | None:
    """The owning session's harness process (`{harness, pid, creationTime, key}`), or None.
    The reaper judges "own" and "ended" by it, mirroring `buildAgentSpawnRecord`'s `owner`."""
    return agent_identity.owner_block()


def process_creation_file_time_utc(pid: int) -> str | None:
    """`.ToFileTimeUtc()` of `pid`'s OS creation time, as a decimal STRING -- same field, same
    normalization as `process-identity.cjs`'s `PROCESS_TABLE_PS_COMMAND` ([A2]): a FILETIME does
    not fit a JSON/float number without losing precision, so it travels as a string on both sides
    of the register and both producers.

    Returns `None` on ANY failure (no PowerShell, unknown pid, a kernel-owned row with no
    `CreationDate`) -- an absent value is read as evidence-unavailable by every downstream reader
    (identity verification REFUSES on a missing creation time), never as zero or "old".

    Windows-only, mirroring this module's own `_process_cmdline` precedent in `ui_shot.py`.
    """
    if os.name != "nt":
        return None
    try:
        out = subprocess.run(
            [
                "powershell", "-NoProfile", "-NonInteractive", "-Command",
                f'(Get-CimInstance Win32_Process -Filter "ProcessId={int(pid)}" '
                "-ErrorAction SilentlyContinue).CreationDate.ToFileTimeUtc().ToString()",
            ],
            capture_output=True, text=True, timeout=8,
        )
        val = (out.stdout or "").strip()
        return val or None
    except Exception:
        return None


def resolve_node_modules_real_path(root: Path) -> str | None:
    """The junction-lock field (861 Section 6.2): `<root>/node_modules`, resolved through any
    junction, for a producer to record. `None` when it does not exist -- an absent directory is
    reported as absent, not guessed at. Mirrors `resolveNodeModulesRealPath` in
    `agent-spawn-record.cjs`, independently, since the two producers never share a runtime."""
    try:
        return str((Path(root) / "node_modules").resolve(strict=True))
    except OSError:
        return None


def _iso(dt: datetime.datetime) -> str:
    return dt.astimezone(datetime.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def build_record(
    *,
    record_id: str,
    producer: str,
    pid: int,
    creation_file_time_utc: str,
    cmdline_fingerprint: str,
    port: int,
    lease_duration_sec: int,
    ownership: str = OWNERSHIP_SESSION_OWNED,
    repo_root: Path | str | None = None,
    worktree_root: Path | str | None = None,
    node_modules_real_path: str | None = None,
    now: datetime.datetime | None = None,
) -> dict[str, Any]:
    """The v1 agent-spawns record, as a plain dict. Pure -- separated from the write so it is
    testable and so the shape-parity test can inspect it without touching disk.

    Field-for-field mirror of `buildAgentSpawnRecord` in `agent-spawn-record.cjs`. Raises
    `ValueError` on an unusable creation time, the same "fail fast at the write site" discipline
    that side's `buildAgentSpawnRecord` documents -- a producer must not leave an invalid record
    for a reader to report as `unreadable` hours later.
    """
    assert_safe_record_id(record_id)
    if not creation_file_time_utc or not str(creation_file_time_utc).strip():
        raise ValueError(
            "refusing to build an agent-spawn record with no creationFileTimeUtc: "
            "pid alone cannot survive pid reuse",
        )
    now = now or datetime.datetime.now(datetime.timezone.utc)
    started_at = _iso(now)
    expires_at = _iso(now + datetime.timedelta(seconds=lease_duration_sec))
    record: dict[str, Any] = {
        "schemaVersion": SCHEMA_VERSION,
        "recordId": record_id,
        "producer": producer,
        "pid": int(pid),
        "creationFileTimeUtc": str(creation_file_time_utc).strip(),
        "cmdlineFingerprint": cmdline_fingerprint,
        "ownership": ownership,
        "probe": {"kind": "port", "port": int(port)},
        "startedAt": started_at,
        "lease": {
            "durationSec": int(lease_duration_sec),
            "renewedAt": started_at,
            "expiresAt": expires_at,
        },
    }
    sid = _session_id()
    if sid:
        record["sessionId"] = sid
    owner = _owner()
    if owner:
        # Mirrors OWNER_IDENTITY_VERSION in agent-spawn-record.cjs: the owner block is trusted.
        record["ownerIdentityVersion"] = OWNER_IDENTITY_VERSION
        record["owner"] = owner
    if repo_root:
        record["repoRoot"] = str(repo_root)
    roots: dict[str, str] = {}
    if worktree_root:
        roots["worktreeRoot"] = str(worktree_root)
    if node_modules_real_path:
        roots["nodeModulesRealPath"] = node_modules_real_path
    if roots:
        record["resourceRoots"] = roots
    return record


def write_record(record: dict[str, Any]) -> Path | None:
    """One record file, written atomically -- temp file in the SAME directory plus `os.replace`,
    mirroring `run_register.register_backend`'s idiom and `writeRecordAtomic` in
    `process-record.cjs` (861 Section 7.5: the Python writer cannot be unified with the JS one;
    this is the Python-side implementation of the same envelope, not a third independent one).

    Never raises (see the module docstring's failure policy): a bookkeeping failure must not fail
    the caller's actual job (starting a UI capture).
    """
    try:
        target = record_path(record["recordId"])
        target.parent.mkdir(parents=True, exist_ok=True)
        payload = json.dumps(record, indent=2, sort_keys=True, ensure_ascii=False)
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
        log.info("Registered agent-spawn record: %s", target)
        return target
    except Exception as exc:  # noqa: BLE001 -- bookkeeping must never fail the caller's real job
        log.warning("Could not write agent-spawn record for recordId=%s: %s", record.get("recordId"), exc)
        return None


def renew_lease(record_id: str, lease_duration_sec: int, *, now: datetime.datetime | None = None) -> bool:
    """Lease-on-use (861 Section 6.2): refresh an existing record's lease WITHOUT rewriting
    anything else. Called by `ui_shot.py` on BOTH the start path and the REUSE path -- an actively
    used server keeps extending its own claim, an abandoned one lapses, and no supervisor process
    or renewal daemon is involved.

    Returns `False` (never raises) when the record is missing or unreadable -- the caller's reuse
    decision must never depend on this succeeding.
    """
    try:
        target = record_path(record_id)
        record = json.loads(target.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return False
    now = now or datetime.datetime.now(datetime.timezone.utc)
    renewed_at = _iso(now)
    expires_at = _iso(now + datetime.timedelta(seconds=lease_duration_sec))
    record["lease"] = {
        "durationSec": int(lease_duration_sec),
        "renewedAt": renewed_at,
        "expiresAt": expires_at,
    }
    return write_record(record) is not None


def remove_record(record_id: str) -> bool:
    """Clean-exit retirement -- mirrors `run_register.unregister_backend`. Deletes a FILE; never
    signals a process. Idempotent and never raises."""
    try:
        target = record_path(record_id)
    except Exception:  # noqa: BLE001 -- a malformed id is not worth failing a teardown over
        return False
    try:
        target.unlink()
        log.info("Removed agent-spawn record: %s", target)
        return True
    except FileNotFoundError:
        return False
    except Exception as exc:  # noqa: BLE001 -- teardown must never raise; see the failure policy
        log.warning("Could not remove agent-spawn record %s: %s", record_id, exc)
        return False
