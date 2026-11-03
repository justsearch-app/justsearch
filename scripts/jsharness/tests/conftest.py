"""Harness test identity and state isolation."""
import json
import pytest

#: Every identity input agent-identity.cjs reads; cleared so tests do not depend on the harness running them.
IDENTITY_ENV_KEYS = (
    "JUSTSEARCH_AGENT_IDENTITY",
    "JUSTSEARCH_AGENT_IDENTITY_HANDOFF",
    "JUSTSEARCH_PROCESS_TABLE_FIXTURE",
    "CLAUDE_CODE_SESSION_ID",
    "CLAUDE_PID",
    "CODEX_THREAD_ID",
    "CODEX_SESSION_ID",
    "JUSTSEARCH_AGENT_SESSION_ID",
    "CI",
)


@pytest.fixture
def simulate_identity(monkeypatch, tmp_path):
    """Simulate the calling agent session through agent-identity.cjs's process-table fixture seam.

    ``simulate_identity(harness=True, label="...")`` writes a process table whose walk reaches a fake
    Claude Code process, resets the identity cache so the real Node CLI runs against it, and returns
    the owner block resolution must yield (None for ``harness=False``).
    """
    # Imported defensively so a checkout without the identity module reports each test's own
    # assertion instead of a setup error.
    try:
        from jsharness import agent_identity
    except ImportError:  # pragma: no cover - only on a checkout that predates the module
        agent_identity = None

    def simulate(*, harness: bool, label: str | None = None) -> dict | None:
        for key in IDENTITY_ENV_KEYS:
            monkeypatch.delenv(key, raising=False)
        pid, ct = 900_201, "134000000000900201"
        rows = [{"ProcessId": 4, "ParentProcessId": 0, "Name": "System", "CommandLine": "", "CreationFileTimeUtc": "133000000000000000"}]
        if harness:
            rows.append({"ProcessId": pid, "ParentProcessId": 4, "Name": "claude.exe", "CommandLine": "claude.exe", "CreationFileTimeUtc": ct})
        rows.append({"ProcessId": pid + 4, "ParentProcessId": pid if harness else 4, "Name": "node.exe", "CommandLine": "node", "CreationFileTimeUtc": "134000000000900301"})
        fixture = tmp_path / "process-table.json"
        fixture.write_text(json.dumps({"selfPid": pid + 4, "rows": rows}), encoding="utf-8")
        monkeypatch.setenv("JUSTSEARCH_PROCESS_TABLE_FIXTURE", str(fixture))
        if label:
            monkeypatch.setenv("CLAUDE_CODE_SESSION_ID", label)
        if agent_identity is not None:
            monkeypatch.setattr(agent_identity, "_cached", None)
        if not harness:
            return None
        return {"harness": "claude", "pid": pid, "creationTime": ct, "key": f"claude-{pid}-{ct}"}

    return simulate


@pytest.fixture(autouse=True)
def _isolate_foreign_run_register(monkeypatch, tmp_path):
    """Keep the tempdoc-844 foreign-run register out of the real main checkout.

    `run_register.register_dir()` defaults to `<main-checkout>/tmp/dev-runner/foreign` — exactly
    what a real `jseval` run wants, and never what a test run wants: `stop_backend` unregisters by
    pid on every call, and any test that reaches the spawn path would otherwise write into the
    shared machine state that `quick_health` reads. Isolated with the same env var
    `dev-runner.cjs:57` honors, so the isolation goes through the production code path rather than
    around it. Tests that assert on the register re-point this explicitly (monkeypatch's last
    write for a given test wins over this autouse fixture).
    """
    monkeypatch.setenv(
        "JUSTSEARCH_DEV_RUNNER_STATE_ROOT", str(tmp_path / "dev-runner-state"),
    )


