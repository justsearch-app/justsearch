"""Tests for run_register.py — the tempdoc-844 D3 foreign-run record.

The consumer half (liveness, staleness, the merge with the port probe) is tested on the Node side
in ``scripts/dev/test-dev-mcp-surface-honesty.mjs``. What is asserted here is the producer's
contract: where the record goes, what it declares, that it is written atomically, that
``stop_backend`` retires it, and that a bookkeeping failure never fails an eval run.

No backend is started; every state is produced by writing (or corrupting) a file.
"""

from __future__ import annotations

import json
import os
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from jsharness import run_register


@pytest.fixture
def register_root(tmp_path, monkeypatch):
    """Point the register at an isolated state root, the way an isolated dev-runner does."""
    root = tmp_path / "state"
    monkeypatch.setenv("JUSTSEARCH_DEV_RUNNER_STATE_ROOT", str(root))
    return root / "foreign"


class TestRegisterLocation:
    def test_honors_the_dev_runner_state_root_override(self, register_root):
        assert run_register.register_dir() == register_root

    def test_defaults_beside_the_dev_runners_state_not_inside_runs(self, monkeypatch):
        monkeypatch.delenv("JUSTSEARCH_DEV_RUNNER_STATE_ROOT", raising=False)
        parts = run_register.register_dir().parts
        assert parts[-3:] == ("tmp", "dev-runner", "foreign")
        # `runs/` is the ONE directory dev-runner.cjs globs (pruneHistoricRuns, :387) and prunes.
        # Landing there would let the dev-runner delete another lifecycle's records.
        assert "runs" not in parts

    def test_one_record_per_pid(self, register_root):
        assert run_register.record_path(4242).name == "jseval-4242.json"


class TestRecordContent:
    def test_declares_identity_the_reader_needs(self, tmp_path):
        rec = run_register.build_record(
            pid=4242,
            port=33221,
            repo_root=Path("F:/wt/round14"),
            data_dir=tmp_path / "data",
            inference_requested=False,
        )
        assert rec["schemaVersion"] == run_register.SCHEMA_VERSION
        assert rec["producer"] == "jseval"
        assert rec["recordId"] == "jseval-4242"
        assert rec["pid"] == 4242
        assert rec["ports"] == {"api": 33221}
        assert "round14" in rec["repoRoot"]
        assert rec["workload"] == "eval-backend"
        assert rec["inferenceRequested"] is False
        assert rec["startedAt"].endswith("Z")

    def test_owner_and_session_come_from_the_harness_process(self, tmp_path, simulate_identity):
        owner = simulate_identity(harness=True, label="run-reg-label")
        rec = run_register.build_record(
            pid=7, port=33221, repo_root=tmp_path, data_dir=tmp_path, inference_requested=False,
        )
        assert rec["owner"] == owner
        assert rec["sessionId"] == "run-reg-label"

    def test_no_harness_means_no_owner_and_no_borrowed_label(self, tmp_path, simulate_identity, monkeypatch):
        simulate_identity(harness=False, label="inherited-label")
        monkeypatch.setenv("JUSTSEARCH_AGENT_SESSION_ID", "exported-by-retired-hook")
        rec = run_register.build_record(
            pid=7, port=33221, repo_root=tmp_path, data_dir=tmp_path, inference_requested=False,
        )
        assert rec["owner"] is None
        assert rec["sessionId"] is None

    def test_gpu_is_an_explicit_unknown_not_a_convenient_default(self, tmp_path):
        """The producer does not measure GPU residency, so it must not claim it either way."""
        rec = run_register.build_record(
            pid=1, port=33221, repo_root=tmp_path, data_dir=tmp_path,
            inference_requested=False,
        )
        assert rec["gpuBound"] == "unverified"

    def test_makes_no_liveness_claim(self, tmp_path):
        """Crash safety rests on this: a record says "I started this", never "this is up"."""
        rec = run_register.build_record(
            pid=1, port=33221, repo_root=tmp_path, data_dir=tmp_path,
            inference_requested=False,
        )
        for forbidden in ("live", "alive", "healthy", "ready", "running"):
            assert forbidden not in rec, f"the record must not assert liveness ({forbidden})"

    def test_inference_requested_is_recorded_when_asked_for(self, tmp_path):
        rec = run_register.build_record(
            pid=1, port=33221, repo_root=tmp_path, data_dir=tmp_path,
            inference_requested=True,
        )
        assert rec["inferenceRequested"] is True


class TestRegisterWrite:
    def test_writes_a_readable_record_and_creates_the_directory(self, register_root, tmp_path):
        written = run_register.register_backend(
            pid=4242, port=33221, repo_root=tmp_path, data_dir=tmp_path / "data",
        )
        assert written == register_root / "jseval-4242.json"
        doc = json.loads(written.read_text(encoding="utf-8"))
        assert doc["ports"]["api"] == 33221
        assert doc["schemaVersion"] == 1

    def test_leaves_no_temp_file_behind(self, register_root, tmp_path):
        run_register.register_backend(pid=4242, port=33221, repo_root=tmp_path, data_dir=tmp_path)
        assert sorted(p.name for p in register_root.iterdir()) == ["jseval-4242.json"]

    def test_write_is_atomic_so_a_torn_record_is_never_readable(self, register_root, tmp_path):
        """os.replace is the only thing that ever names the target path."""
        seen = []
        real_replace = os.replace

        def spy(src, dst):
            # Before the rename, the destination must not exist at all — no partial file.
            seen.append(Path(dst).exists())
            return real_replace(src, dst)

        with patch("jsharness.run_register.os.replace", spy):
            run_register.register_backend(pid=4242, port=33221, repo_root=tmp_path, data_dir=tmp_path)
        assert seen == [False]

    def test_a_failed_write_never_raises_and_leaves_no_record(self, register_root, tmp_path):
        with patch("jsharness.run_register.tempfile.mkstemp", side_effect=OSError("disk full")):
            assert run_register.register_backend(
                pid=4242, port=33221, repo_root=tmp_path, data_dir=tmp_path,
            ) is None
        assert not (register_root / "jseval-4242.json").exists()

    def test_a_failed_write_leaves_no_temp_turd(self, register_root, tmp_path):
        with patch("jsharness.run_register.os.replace", side_effect=OSError("nope")):
            assert run_register.register_backend(
                pid=4242, port=33221, repo_root=tmp_path, data_dir=tmp_path,
            ) is None
        assert list(register_root.iterdir()) == []


class TestUnregister:
    def test_removes_the_record(self, register_root, tmp_path):
        run_register.register_backend(pid=4242, port=33221, repo_root=tmp_path, data_dir=tmp_path)
        assert run_register.unregister_backend(4242) is True
        assert not (register_root / "jseval-4242.json").exists()

    def test_is_idempotent(self, register_root, tmp_path):
        assert run_register.unregister_backend(4242) is False

    def test_only_removes_its_own_pid(self, register_root, tmp_path):
        run_register.register_backend(pid=1, port=33221, repo_root=tmp_path, data_dir=tmp_path)
        run_register.register_backend(pid=2, port=33222, repo_root=tmp_path, data_dir=tmp_path)
        run_register.unregister_backend(1)
        assert sorted(p.name for p in register_root.iterdir()) == ["jseval-2.json"]


