"""Backend integration with the shared foreign process register."""
import json
import os
from pathlib import Path
from unittest.mock import MagicMock, patch
import pytest
from jsharness import run_register
from jseval.backend import stop_backend

@pytest.fixture
def register_root(tmp_path, monkeypatch):
    """Point the register at an isolated state root, the way an isolated dev-runner does."""
    root = tmp_path / "state"
    monkeypatch.setenv("JUSTSEARCH_DEV_RUNNER_STATE_ROOT", str(root))
    return root / "foreign"


class TestStartBackendRegisters:
    @pytest.fixture(autouse=True)
    def installed_distribution_fixture(self, monkeypatch):
        # These producer tests mock the running backend; build behavior has its own launch tests.
        monkeypatch.setattr("jseval.backend._install_distribution", lambda *_args: None)

    """Wiring: the record is written at SPAWN, before health — the JVM holds ports, the data dir
    and the GPU from that moment, and that boot window is exactly what a neighbour's "is the
    machine free?" check must not miss."""

    @patch("jseval.backend.subprocess.Popen")
    @patch("jseval.backend._wait_for_health", return_value=True)
    def test_a_started_backend_registers_itself(
        self, _health, mock_popen, register_root, tmp_path,
    ):
        proc = MagicMock()
        proc.pid = 4242
        mock_popen.return_value = proc

        from jseval.backend import start_backend

        start_backend(data_dir=tmp_path / "data", port=33221)

        doc = json.loads((register_root / "jseval-4242.json").read_text(encoding="utf-8"))
        assert doc["pid"] == 4242
        assert doc["ports"]["api"] == 33221
        assert doc["producer"] == "jseval"
        assert str(tmp_path) in doc["dataDir"]

    @patch("jseval.backend.subprocess.Popen")
    @patch("jseval.backend._wait_for_health", return_value=True)
    def test_registration_happens_before_the_health_wait(
        self, mock_health, mock_popen, register_root, tmp_path,
    ):
        proc = MagicMock()
        proc.pid = 4242
        mock_popen.return_value = proc
        existed_at_health_time = []
        mock_health.side_effect = lambda *a, **k: (
            existed_at_health_time.append((register_root / "jseval-4242.json").exists()) or True
        )

        from jseval.backend import start_backend

        start_backend(data_dir=tmp_path / "data", port=33221)
        assert existed_at_health_time == [True]

    @patch("jseval.backend.subprocess.Popen")
    @patch("jseval.backend._wait_for_health", return_value=False)
    def test_a_backend_that_never_became_healthy_is_unregistered_again(
        self, _health, mock_popen, register_root, tmp_path,
    ):
        proc = MagicMock()
        proc.pid = 4242
        proc.poll.return_value = 0
        proc.returncode = 1
        mock_popen.return_value = proc

        from jseval.backend import start_backend

        with pytest.raises(RuntimeError, match="did not become healthy"):
            start_backend(data_dir=tmp_path / "data", port=33221)
        assert not (register_root / "jseval-4242.json").exists()

    @patch("jseval.backend.subprocess.Popen")
    @patch("jseval.backend._wait_for_health", return_value=True)
    def test_a_register_failure_does_not_fail_the_run(
        self, _health, mock_popen, register_root, tmp_path,
    ):
        proc = MagicMock()
        proc.pid = 4242
        mock_popen.return_value = proc

        from jseval.backend import start_backend

        with patch("jsharness.run_register.tempfile.mkstemp", side_effect=OSError("disk full")):
            info = start_backend(data_dir=tmp_path / "data", port=33221)
        assert info.proc is proc


class TestStopBackendRetiresTheRecord:
    """A crash leaks a record on purpose (the reader calls it stale); a clean stop must not."""

    def test_a_clean_stop_removes_the_record(self, register_root, tmp_path):
        run_register.register_backend(pid=4242, port=33221, repo_root=tmp_path, data_dir=tmp_path)
        proc = MagicMock()
        proc.poll.return_value = 0
        proc.returncode = 0
        proc.pid = 4242
        stop_backend(proc)
        assert not (register_root / "jseval-4242.json").exists()

    @patch("jseval.backend.os")
    def test_tree_stop_precedes_record_retirement(self, mock_os, register_root, tmp_path):
        """Lane F F-4 keeps the declaration until the owned tree stop completes."""
        # Select Windows cleanup without changing pathlib's platform on Linux.
        mock_os.name = "nt"
        run_register.register_backend(pid=4242, port=33221, repo_root=tmp_path, data_dir=tmp_path)
        assert (register_root / "jseval-4242.json").exists()
        proc = MagicMock()
        proc.poll.return_value = None
        proc.pid = 4242
        seen = []
        with patch.object(proc, "terminate", side_effect=lambda *a, **kw:
                   seen.append((register_root / "jseval-4242.json").exists())) as tree_stop:
            stop_backend(proc, data_dir=tmp_path)
        tree_stop.assert_called_once_with()
        proc.wait.assert_called_once_with(timeout=10)
        assert seen == [True]
        assert not (register_root / "jseval-4242.json").exists()

    def test_stopping_an_unregistered_backend_is_not_an_error(self, register_root):
        proc = MagicMock()
        proc.poll.return_value = 0
        proc.returncode = 0
        proc.pid = 4242
        stop_backend(proc)  # the boot-failure paths stop a proc that may never have registered

    def test_a_killed_producer_leaves_the_record_for_the_reader_to_call_stale(
        self, register_root, tmp_path,
    ):
        """No cleanup runs on SIGKILL — the record survives, which is the designed behaviour."""
        run_register.register_backend(pid=4242, port=33221, repo_root=tmp_path, data_dir=tmp_path)
        # (nothing runs here — that IS the crash)
        assert (register_root / "jseval-4242.json").exists()
