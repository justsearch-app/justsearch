"""Tests for agent_spawn_register.py -- the tempdoc-861 W3 `agent-spawns/` Python producer.

Mirrors `test_run_register.py`'s structure for the sibling `foreign/` scope. What is asserted
here is the producer's contract: where the record goes, what it declares (the identity triple,
the lease, the ownership mode), that it is written atomically, that a bookkeeping failure never
raises, and that lease-on-use renews in place without disturbing the rest of the record.

The shape-parity half -- that a record this module writes actually VALIDATES against the real JS
reader (`validateAgentSpawnRecord` in `scripts/dev/lib/agent-spawn-record.cjs`) -- is asserted on
the JS side (`scripts/agent-analytics/861-w3-ui-shot-shape-parity.test.mjs`), which spawns this
module for real. Nothing here talks to the JS side.
"""

from __future__ import annotations

import datetime
import json
import os
from pathlib import Path
from unittest.mock import patch

import pytest

from jseval import agent_spawn_register as reg


@pytest.fixture
def register_root(tmp_path, monkeypatch):
    """Point the register at an isolated state root, the way an isolated dev-runner does."""
    root = tmp_path / "state"
    monkeypatch.setenv("JUSTSEARCH_DEV_RUNNER_STATE_ROOT", str(root))
    monkeypatch.delenv("CLAUDE_CODE_SESSION_ID", raising=False)
    monkeypatch.delenv("JUSTSEARCH_AGENT_SESSION_ID", raising=False)
    return root / "agent-spawns"


T = "134320479841300350"  # a real-shaped FILETIME (see process-identity.cjs for the derivation)


def good_record(**over):
    defaults = dict(
        record_id="ui-shot-5174",
        producer="ui-shot",
        pid=4242,
        creation_file_time_utc=T,
        cmdline_fingerprint="--port 5174",
        port=5174,
        lease_duration_sec=1800,
    )
    defaults.update(over)
    return reg.build_record(**defaults)


class TestRegisterLocation:
    def test_honors_the_dev_runner_state_root_override(self, register_root):
        assert reg.register_dir() == register_root

    def test_defaults_beside_the_dev_runners_state_not_inside_runs(self, monkeypatch):
        monkeypatch.delenv("JUSTSEARCH_DEV_RUNNER_STATE_ROOT", raising=False)
        parts = reg.register_dir().parts
        assert parts[-3:] == ("tmp", "dev-runner", "agent-spawns")
        assert "runs" not in parts
        assert "foreign" not in parts  # a SIBLING scope, never inside `foreign/`

    def test_record_path_is_keyed_by_record_id(self, register_root):
        assert reg.record_path("ui-shot-5174").name == "ui-shot-5174.json"


class TestSafeRecordId:
    """Mirrors `assertSafeRecordId` in `agent-spawn-record.cjs` -- a record id is a FILE NAME, and
    a traversal-capable one must be refused LOUDLY (ValueError), never sanitized quietly."""

    def test_accepts_ordinary_ids(self):
        for good in ("ui-shot-5174", "otlp-sink", "serve-worktree-fe-5174-4242", "a", "a" * 128):
            assert reg.assert_safe_record_id(good) == good

    def test_rejects_path_traversal(self, register_root):
        for bad in ("../evil", "..\\evil", "a/../b", "..", "a/b", "a\\b"):
            with pytest.raises(ValueError, match="unsafe recordId"):
                reg.assert_safe_record_id(bad)

    def test_rejects_empty_and_non_string(self):
        for bad in ("", 123, None, [], "a" * 129):
            with pytest.raises(ValueError, match="unsafe recordId"):
                reg.assert_safe_record_id(bad)

    def test_record_path_refuses_before_touching_disk(self, register_root):
        with pytest.raises(ValueError, match="unsafe recordId"):
            reg.record_path("../escape")

    def test_build_record_refuses_an_unsafe_id_before_anything_else(self):
        with pytest.raises(ValueError, match="unsafe recordId"):
            good_record(record_id="../escape")


class TestRecordContent:
    def test_declares_the_identity_triple_a_kill_path_needs(self):
        rec = good_record()
        assert rec["schemaVersion"] == reg.SCHEMA_VERSION
        assert rec["recordId"] == "ui-shot-5174"
        assert rec["producer"] == "ui-shot"
        assert rec["pid"] == 4242
        assert rec["creationFileTimeUtc"] == T  # carried as a STRING, never a JSON number
        assert isinstance(rec["creationFileTimeUtc"], str)
        assert rec["cmdlineFingerprint"] == "--port 5174"

    def test_defaults_to_session_owned(self):
        assert good_record()["ownership"] == reg.OWNERSHIP_SESSION_OWNED

    def test_ownerless_singleton_is_declared_not_inferred(self):
        rec = good_record(producer="otlp-sink", ownership=reg.OWNERSHIP_OWNERLESS_SINGLETON)
        assert rec["ownership"] == reg.OWNERSHIP_OWNERLESS_SINGLETON

    def test_probe_is_the_port_kind(self):
        assert good_record()["probe"] == {"kind": "port", "port": 5174}

    def test_lease_carries_duration_renewed_and_expiry(self):
        now = datetime.datetime(2026, 1, 1, tzinfo=datetime.timezone.utc)
        rec = good_record(lease_duration_sec=60, now=now)
        assert rec["lease"]["durationSec"] == 60
        assert rec["lease"]["renewedAt"] == "2026-01-01T00:00:00Z"
        assert rec["lease"]["expiresAt"] == "2026-01-01T00:01:00Z"

    def test_refuses_to_build_without_a_creation_time(self):
        with pytest.raises(ValueError, match="creationFileTimeUtc"):
            good_record(creation_file_time_utc="")

    def test_session_and_owner_come_from_the_harness_process(self, monkeypatch, tmp_path, simulate_identity):
        owner = simulate_identity(harness=True, label="abc123-label")
        rec = good_record()
        assert rec["sessionId"] == "abc123-label"
        assert rec["owner"] == owner
        assert rec["ownerIdentityVersion"] == reg.OWNER_IDENTITY_VERSION == 1

    def test_inherited_label_without_a_harness_is_not_identity(self, monkeypatch, tmp_path, simulate_identity):
        # A label inherited by a process with no harness ancestor (e.g. a detached tool) names nobody.
        simulate_identity(harness=False, label="inherited-label")
        monkeypatch.setenv("JUSTSEARCH_AGENT_SESSION_ID", "exported-by-retired-hook")
        rec = good_record()
        assert "sessionId" not in rec
        assert "owner" not in rec
        assert "ownerIdentityVersion" not in rec

    def test_leftover_pointer_file_is_never_read(self, monkeypatch, tmp_path, simulate_identity):
        simulate_identity(harness=False)
        pointer = tmp_path / "tmp" / "agent-telemetry"
        pointer.mkdir(parents=True)
        (pointer / "current-session-id").write_text("from-pointer-file\n", encoding="utf-8")
        monkeypatch.chdir(tmp_path)
        assert "sessionId" not in good_record()

    def test_resource_roots_are_optional_and_named_correctly(self, tmp_path):
        rec = good_record(worktree_root=tmp_path, node_modules_real_path=str(tmp_path / "nm"))
        assert rec["resourceRoots"] == {
            "worktreeRoot": str(tmp_path),
            "nodeModulesRealPath": str(tmp_path / "nm"),
        }

    def test_resource_roots_absent_when_nothing_supplied(self):
        assert "resourceRoots" not in good_record()


class TestRegisterWrite:
    def test_writes_a_readable_record_and_creates_the_directory(self, register_root):
        written = reg.write_record(good_record())
        assert written == register_root / "ui-shot-5174.json"
        doc = json.loads(written.read_text(encoding="utf-8"))
        assert doc["pid"] == 4242
        assert doc["schemaVersion"] == reg.SCHEMA_VERSION

    def test_leaves_no_temp_file_behind(self, register_root):
        reg.write_record(good_record())
        assert sorted(p.name for p in register_root.iterdir()) == ["ui-shot-5174.json"]

    def test_write_is_atomic_so_a_torn_record_is_never_readable(self, register_root):
        seen = []
        real_replace = os.replace

        def spy(src, dst):
            seen.append(Path(dst).exists())
            return real_replace(src, dst)

        with patch("jseval.agent_spawn_register.os.replace", spy):
            reg.write_record(good_record())
        assert seen == [False]

    def test_a_failed_write_never_raises_and_leaves_no_record(self, register_root):
        with patch("jseval.agent_spawn_register.tempfile.mkstemp", side_effect=OSError("disk full")):
            assert reg.write_record(good_record()) is None
        assert not (register_root / "ui-shot-5174.json").exists()

    def test_a_failed_write_leaves_no_temp_turd(self, register_root):
        with patch("jseval.agent_spawn_register.os.replace", side_effect=OSError("nope")):
            assert reg.write_record(good_record()) is None
        assert list(register_root.iterdir()) == []

    def test_a_second_record_does_not_destroy_the_first(self, register_root):
        """The direct regression test for the six-leak (861 §3b/§7.1 Phase 3 acceptance):
        starting a second server must create a second record, not clobber the first."""
        reg.write_record(good_record(record_id="ui-shot-5174", port=5174))
        reg.write_record(good_record(record_id="ui-shot-5175", port=5175, pid=4243))
        assert sorted(p.name for p in register_root.iterdir()) == [
            "ui-shot-5174.json", "ui-shot-5175.json",
        ]


class TestLeaseOnUse:
    def test_renew_extends_the_lease_without_touching_other_fields(self, register_root):
        original = good_record(lease_duration_sec=1)
        reg.write_record(original)
        assert reg.renew_lease("ui-shot-5174", 1800) is True
        doc = json.loads(reg.record_path("ui-shot-5174").read_text(encoding="utf-8"))
        assert doc["lease"]["durationSec"] == 1800
        assert doc["lease"]["expiresAt"] > original["lease"]["expiresAt"]
        # everything else is untouched
        assert doc["pid"] == original["pid"]
        assert doc["cmdlineFingerprint"] == original["cmdlineFingerprint"]

    def test_renew_is_false_and_never_raises_when_the_record_is_missing(self, register_root):
        assert reg.renew_lease("no-such-record", 1800) is False

    def test_renew_is_false_and_never_raises_on_a_corrupt_record(self, register_root):
        register_root.mkdir(parents=True)
        (register_root / "ui-shot-5174.json").write_text("{not json", encoding="utf-8")
        assert reg.renew_lease("ui-shot-5174", 1800) is False


class TestRemoveRecord:
    def test_removes_the_record(self, register_root):
        reg.write_record(good_record())
        assert reg.remove_record("ui-shot-5174") is True
        assert not (register_root / "ui-shot-5174.json").exists()

    def test_is_idempotent(self, register_root):
        assert reg.remove_record("ui-shot-5174") is False


class TestNodeModulesResolution:
    def test_resolves_through_a_real_junction(self, tmp_path):
        import subprocess
        import sys

        target = tmp_path / "main" / "node_modules"
        target.mkdir(parents=True)
        (target / "marker.txt").write_text("x", encoding="utf-8")
        junction_root = tmp_path / "wt"
        junction_root.mkdir()
        junction = junction_root / "node_modules"
        if sys.platform == "win32":
            subprocess.run(
                ["cmd", "/c", "mklink", "/J", str(junction), str(target)],
                check=True, capture_output=True,
            )
            resolved = reg.resolve_node_modules_real_path(junction_root)
            assert resolved is not None
            assert Path(resolved) == target.resolve()
        else:
            junction.symlink_to(target)
            resolved = reg.resolve_node_modules_real_path(junction_root)
            assert Path(resolved) == target.resolve()

    def test_none_when_absent(self, tmp_path):
        assert reg.resolve_node_modules_real_path(tmp_path / "nope") is None
