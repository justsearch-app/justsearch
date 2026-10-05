"""Shared test fixtures for jseval tests."""

from __future__ import annotations

import json
from pathlib import Path

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
        from jseval import agent_identity
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


def _write_json(path: Path, doc) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(doc, indent=2, sort_keys=True, ensure_ascii=False),
        encoding="utf-8",
    )


def _write_ndjson(path: Path, records: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "\n".join(json.dumps(r, ensure_ascii=False) for r in records) + "\n",
        encoding="utf-8",
    )


class SyntheticRunDir:
    """Builder for a fake ``run_dir`` used by Layer-4 projection tests.

    Phase-3 projections read a fixed set of artifacts from a run
    directory; this builder lets each projection test focus on its
    own contract without re-implementing the file-layout boilerplate.
    Pass a ``tmp_path`` and chain the ``with_*`` methods; the built
    directory is just the fixture path, ready for a projection's
    ``produce(run_dir)`` call.
    """

    def __init__(self, run_dir: Path) -> None:
        self.run_dir = run_dir
        run_dir.mkdir(parents=True, exist_ok=True)

    def with_traces(self, spans: list[dict]) -> "SyntheticRunDir":
        _write_ndjson(self.run_dir / "traces.ndjson", spans)
        return self

    def with_metrics(self, records: list[dict]) -> "SyntheticRunDir":
        _write_ndjson(self.run_dir / "metrics.ndjson", records)
        return self

    def with_per_query(self, mode: str, entries: list[dict]) -> "SyntheticRunDir":
        _write_json(self.run_dir / f"{mode}_per_query.json", entries)
        return self

    def with_summary(self, summary: dict) -> "SyntheticRunDir":
        _write_json(self.run_dir / "summary.json", summary)
        return self

    def with_manifest(self, manifest: dict) -> "SyntheticRunDir":
        _write_json(self.run_dir / "manifest.json", manifest)
        return self

    def with_qrels(self, qrels: dict[str, dict[str, int]]) -> "SyntheticRunDir":
        _write_json(self.run_dir / "qrels.json", qrels)
        return self


@pytest.fixture
def synthetic_run_dir(tmp_path) -> SyntheticRunDir:
    """Create an empty :class:`SyntheticRunDir` rooted at ``tmp_path``."""
    return SyntheticRunDir(tmp_path / "run")


@pytest.fixture
def make_span():
    """Factory for synthetic span records (``traces.ndjson`` shape)."""

    def _factory(
        name: str,
        *,
        attrs: dict | None = None,
        events: list[dict] | None = None,
        duration_ms: float | None = None,
        trace_id: str | None = None,
        span_id: str | None = None,
        parent_span_id: str | None = None,
    ) -> dict:
        return {
            "name": name,
            "trace_id": trace_id or f"t-{name}",
            "span_id": span_id or f"s-{name}",
            "parent_span_id": parent_span_id,
            "attrs": attrs or {},
            "events": events or [],
            "duration_ms": duration_ms,
        }

    return _factory


@pytest.fixture(autouse=True)
def _isolate_worker_data_dir(monkeypatch, tmp_path):
    """Point the Worker-data-dir resolution away from the real machine.

    `run._worker_data_dir()` reads env `JUSTSEARCH_DATA_DIR` and falls back to
    `_paths.DEFAULT_BACKEND_DATA_DIR`; the `cadence` and `encoder_latency` summary
    blocks then read `<that>/telemetry/`. Un-isolated, a developer machine's real
    `tmp/headless-eval-data` (or an exported JUSTSEARCH_DATA_DIR) would leak real
    telemetry into tests that expect an empty block. Tests that exercise a populated
    telemetry dir re-point these explicitly (their monkeypatch writes win — same
    function-scoped monkeypatch, later write).
    """
    import jseval._paths as _paths

    monkeypatch.delenv("JUSTSEARCH_DATA_DIR", raising=False)
    monkeypatch.setattr(
        _paths, "DEFAULT_BACKEND_DATA_DIR", tmp_path / "no-legacy-root-in-tests",
    )


@pytest.fixture(autouse=True)
def _disable_shared_dataset_cache_by_default(monkeypatch):
    """Default every test to `dataset_cache`'s pre-709 (cache-disabled) behavior.

    `dataset_cache.cache_root()` defaults to a directory under the MAIN checkout when
    `JUSTSEARCH_DATASET_CACHE` is unset -- exactly what a real worktree run wants, but never
    what a test run wants: an un-opted-in test would otherwise write real cache-entry
    directories into the actual main checkout on disk merely by running the unit suite from
    a worktree. `tests/test_dataset_cache.py`'s own tests opt back in explicitly by setting
    `JUSTSEARCH_DATASET_CACHE` to a `tmp_path` (monkeypatch's last write for a given test wins
    over this autouse fixture, since both share the same function-scoped `monkeypatch`).
    """
    monkeypatch.setenv("JUSTSEARCH_DATASET_CACHE", "0")


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


@pytest.fixture
def clean_projection_registry():
    """Reset the projection registry around each test that opts in.

    Phase-3 projections self-register at module import; tests that
    need deterministic registry contents wrap their bodies with this
    fixture to reset + re-seed.
    """
    from jseval.projections.base import reset_registry_for_tests

    reset_registry_for_tests()
    yield
    reset_registry_for_tests()
