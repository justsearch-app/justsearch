"""The calling agent session's identity, resolved by the repository's ONE rule.

The rule lives in ``scripts/dev/lib/agent-identity.cjs``: an explicit override, else the caller's
nearest harness process (Claude Code or Codex; its pid plus creation time is the owner key and its
own session variable the readable label), else ``ci`` / ``unknown``. Python does not re-implement
it -- a second implementation is how two registers come to disagree about who owns what. It runs
the tiny Node CLI ``scripts/dev/agent-identity.mjs --json`` instead, once per process, with a
timeout. The CLI's process walk starts at the CLI and climbs through this Python process to the
harness, so the answer is this process's own.

Failure policy: identity is bookkeeping. Any failure (no ``node``, a timeout, unparseable output)
is the unknown identity -- no owner, no label -- never a borrowed or stale one, and never an
exception. The retired shared pointer file ``tmp/agent-telemetry/current-session-id`` is never
read, and ``JUSTSEARCH_AGENT_SESSION_ID`` is not identity.
"""

from __future__ import annotations

import json
import logging
import os
import re
import shutil
import subprocess
from pathlib import Path
from typing import Any

log = logging.getLogger(__name__)

#: Generous for a cold PowerShell process-table query (about a second warm).
CLI_TIMEOUT_SEC = 15

#: Same shape as the Node side's ``SESSION_ID_RE``; a label that fails it is dropped.
SESSION_ID_RE = re.compile(r"^[A-Za-z0-9._-]{4,80}$")

UNKNOWN: dict[str, Any] = {"harness": "unknown", "owner": None, "sessionId": None, "source": "unavailable"}

_cached: dict[str, Any] | None = None


def _cli_path() -> Path:
    # The CLI that ships with THIS module's checkout (parents[3] is the repository root).
    return Path(__file__).resolve().parents[3] / "scripts" / "dev" / "agent-identity.mjs"


def _coerce(doc: Any) -> dict[str, Any]:
    if not isinstance(doc, dict) or doc.get("harness") not in ("claude", "codex", "ci", "unknown"):
        return dict(UNKNOWN)
    sid = doc.get("sessionId")
    if not (isinstance(sid, str) and SESSION_ID_RE.match(sid)):
        sid = None
    owner = doc.get("owner")
    if owner is not None:
        key = owner.get("key") if isinstance(owner, dict) else None
        if not (isinstance(key, str) and re.match(r"^[A-Za-z0-9._-]{4,160}$", key)):
            owner = None
        else:
            owner = {
                "harness": owner.get("harness", doc["harness"]),
                "pid": owner.get("pid"),
                "creationTime": owner.get("creationTime"),
                "key": key,
            }
    return {"harness": doc["harness"], "owner": owner, "sessionId": sid, "source": str(doc.get("source", ""))}


def resolve_identity(*, refresh: bool = False) -> dict[str, Any]:
    """The calling session's identity: ``{harness, owner, sessionId, source}``. Never raises."""
    global _cached
    if _cached is not None and not refresh:
        return dict(_cached)
    node = shutil.which("node") or "node"
    try:
        proc = subprocess.run(
            [node, str(_cli_path()), "--json"],
            capture_output=True,
            text=True,
            timeout=CLI_TIMEOUT_SEC,
            env=os.environ.copy(),
            check=False,
        )
        lines = [ln for ln in (proc.stdout or "").splitlines() if ln.strip()]
        result = _coerce(json.loads(lines[-1])) if proc.returncode == 0 and lines else dict(UNKNOWN)
    except Exception as exc:  # noqa: BLE001 -- identity must never fail a producer
        log.warning("agent identity unavailable: %s", exc)
        result = dict(UNKNOWN)
    _cached = result
    return dict(result)


def session_label() -> str | None:
    """The readable session label, or None."""
    return resolve_identity().get("sessionId")


def owner_block() -> dict[str, Any] | None:
    """``{harness, pid, creationTime, key}`` for records, or None for an unknown owner."""
    ident = resolve_identity()
    owner = ident.get("owner")
    if not owner:
        return None
    return {
        "harness": ident.get("harness"),
        "pid": owner.get("pid"),
        "creationTime": owner.get("creationTime"),
        "key": owner.get("key"),
    }
