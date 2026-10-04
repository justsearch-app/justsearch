"""Tests for backend.py — backend lifecycle management."""

from __future__ import annotations

import sys
import time
from pathlib import Path
from unittest.mock import MagicMock, patch

import httpx
import pytest

from jseval._paths import REPO_ROOT
from jseval.backend import (
    EvalModeLlmUnsupportedError,
    _wait_for_inference,
    start_backend,
    stop_backend,
)


class TestRepoRoot:
    def test_returns_path(self):
        assert REPO_ROOT.is_dir()
        # Should contain gradlew.bat (or gradlew on non-Windows)
        assert (REPO_ROOT / "gradlew.bat").is_file() or (REPO_ROOT / "gradlew").is_file()


class TestStopBackend:
    def test_already_exited(self):
        proc = MagicMock()
        proc.poll.return_value = 0
        proc.returncode = 0
        stop_backend(proc)
        # Should not call taskkill or terminate

    @patch("jseval.backend.os.name", "nt")
    @patch("jseval.backend.subprocess.run")
    def test_windows_taskkill(self, mock_run):
        proc = MagicMock()
        proc.poll.return_value = None
        proc.pid = 12345
        stop_backend(proc)
        mock_run.assert_called_once()
        args = mock_run.call_args[0][0]
        assert "taskkill" in args
        assert "/T" in args
        assert "/F" in args
        assert "12345" in args

    @patch("jseval.backend.os.name", "posix")
    def test_posix_terminate(self):
        proc = MagicMock()
        proc.poll.return_value = None
        proc.pid = 12345
        proc.wait.return_value = 0
        stop_backend(proc)
        proc.terminate.assert_called_once()

    @patch("jseval.backend.os.name", "nt")
    @patch("jseval.backend.subprocess.run")
    def test_data_dir_keeps_tree_stop_without_process_scan(self, mock_run, tmp_path):
        """Lane F F-4: only the owned tree is targeted, even with data_dir."""
        proc = MagicMock()
        proc.poll.return_value = None
        proc.pid = 12345
        with patch("psutil.process_iter", side_effect=AssertionError("unexpected scan")):
            stop_backend(proc, data_dir=tmp_path)
        mock_run.assert_called_once_with(
            ["taskkill", "/PID", "12345", "/T", "/F"], capture_output=True,
        )


class TestWaitForInference:
    """Tests for _wait_for_inference (369)."""

    def _make_proc(self, alive: bool = True):
        proc = MagicMock()
        proc.poll.return_value = None if alive else 1
        proc.returncode = 1
        return proc

    @patch("jseval.backend.httpx.Client")
    @patch("jseval.backend._HEALTH_POLL_SEC", 0.01)
    def test_returns_none_on_online(self, mock_client_cls):
        """Success case: inference is online immediately."""
        mock_resp = MagicMock()
        mock_resp.json.return_value = {"mode": "online"}
        mock_client = MagicMock()
        mock_client.__enter__ = MagicMock(return_value=mock_client)
        mock_client.__exit__ = MagicMock(return_value=False)
        mock_client.get.return_value = mock_resp
        mock_client_cls.return_value = mock_client

        result = _wait_for_inference("http://localhost:33221", time.monotonic() + 5, self._make_proc())
        assert result is None  # success

    @patch("jseval.backend.httpx.Client")
    @patch("jseval.backend._HEALTH_POLL_SEC", 0.01)
    def test_transitions_then_online(self, mock_client_cls):
        """Inference transitions from transitioning to online."""
        responses = [
            {"mode": "transitioning"},
            {"mode": "transitioning"},
            {"mode": "online"},
        ]
        call_count = 0

        mock_client = MagicMock()
        mock_client.__enter__ = MagicMock(return_value=mock_client)
        mock_client.__exit__ = MagicMock(return_value=False)

        def get_side_effect(url):
            nonlocal call_count
            resp = MagicMock()
            resp.json.return_value = responses[min(call_count, len(responses) - 1)]
            call_count += 1
            return resp

        mock_client.get.side_effect = get_side_effect
        mock_client_cls.return_value = mock_client

        result = _wait_for_inference("http://localhost:33221", time.monotonic() + 5, self._make_proc())
        assert result is None
        assert call_count >= 3

    @patch("jseval.backend.httpx.Client")
    @patch("jseval.backend._HEALTH_POLL_SEC", 0.01)
    def test_offline_timeout_gives_diagnostic(self, mock_client_cls):
        """Stays offline — returns diagnostic mentioning common causes."""
        mock_resp = MagicMock()
        mock_resp.json.return_value = {"mode": "offline"}
        mock_client = MagicMock()
        mock_client.__enter__ = MagicMock(return_value=mock_client)
        mock_client.__exit__ = MagicMock(return_value=False)
        mock_client.get.return_value = mock_resp
        mock_client_cls.return_value = mock_client

        # Deadline already passed
        result = _wait_for_inference("http://localhost:33221", time.monotonic() + 0.05, self._make_proc())
        assert result is not None
        assert "autostart may have failed" in result
        assert "JUSTSEARCH_SERVER_EXE" in result

    @patch("jseval.backend.httpx.Client")
    @patch("jseval.backend._HEALTH_POLL_SEC", 0.01)
    def test_transitioning_timeout_gives_diagnostic(self, mock_client_cls):
        """Stuck transitioning — returns diagnostic about model load."""
        mock_resp = MagicMock()
        mock_resp.json.return_value = {"mode": "transitioning"}
        mock_client = MagicMock()
        mock_client.__enter__ = MagicMock(return_value=mock_client)
        mock_client.__exit__ = MagicMock(return_value=False)
        mock_client.get.return_value = mock_resp
        mock_client_cls.return_value = mock_client

        result = _wait_for_inference("http://localhost:33221", time.monotonic() + 0.05, self._make_proc())
        assert result is not None
        assert "model load exceeded timeout" in result

    @patch("jseval.backend._HEALTH_POLL_SEC", 0.01)
    def test_process_exit_gives_diagnostic(self):
        """Backend process dies — returns diagnostic with exit code."""
        proc = self._make_proc(alive=False)
        result = _wait_for_inference("http://localhost:33221", time.monotonic() + 5, proc)
        assert result is not None
        assert "process exited" in result
        assert "rc=1" in result


class TestStartBackendDataDirResolution:
    """Regression: start_backend must honor JUSTSEARCH_DATA_DIR from env.

    Without this, Phase-3 artifacts.write_run's telemetry mirror reads
    from the caller's expected data_dir while the backend writes to a
    different default, producing "no-encoder-spans" LR4-g / empty LR4-f
    projections on live integration smokes.
    """

    @patch("jseval.backend.subprocess.Popen")
    @patch("jseval.backend._wait_for_health", return_value=True)
    def test_env_data_dir_overrides_default(self, _health, mock_popen, tmp_path, monkeypatch):
        target = tmp_path / "cohort-data"
        target.mkdir()
        monkeypatch.setenv("JUSTSEARCH_DATA_DIR", str(target))

        mock_proc = MagicMock()
        mock_popen.return_value = mock_proc

        info = start_backend()
        # Resolved data_dir should be the env-supplied path, NOT the
        # tmp/headless-eval-data fallback.
        assert info.data_dir == target
        # Popen invocation received the env var pointed at the target.
        env = mock_popen.call_args.kwargs["env"]
        assert env["JUSTSEARCH_DATA_DIR"] == str(target)

    @patch("jseval.backend.subprocess.Popen")
    @patch("jseval.backend._wait_for_health", return_value=True)
    def test_explicit_arg_overrides_env(self, _health, mock_popen, tmp_path, monkeypatch):
        env_path = tmp_path / "from-env"
        arg_path = tmp_path / "from-arg"
        env_path.mkdir()
        arg_path.mkdir()
        monkeypatch.setenv("JUSTSEARCH_DATA_DIR", str(env_path))

        mock_proc = MagicMock()
        mock_popen.return_value = mock_proc

        info = start_backend(data_dir=arg_path)
        # Explicit arg still wins when both are present.
        assert info.data_dir == arg_path

    @patch("jseval.backend.subprocess.Popen")
    @patch("jseval.backend._wait_for_health", return_value=True)
    def test_falls_back_to_default_when_neither_set(self, _health, mock_popen,
                                                     tmp_path, monkeypatch):
        monkeypatch.delenv("JUSTSEARCH_DATA_DIR", raising=False)
        mock_proc = MagicMock()
        mock_popen.return_value = mock_proc

        info = start_backend()
        # Default path under REPO_ROOT/tmp/headless-eval-data.
        assert info.data_dir.name == "headless-eval-data"

    @patch("jseval.backend.subprocess.Popen")
    @patch("jseval.backend._wait_for_health", return_value=True)
    def test_relative_env_path_resolves_against_repo_root(
        self, _health, mock_popen, monkeypatch,
    ):
        """Tempdoc 400 §23.8 D-2 regression.

        A relative ``JUSTSEARCH_DATA_DIR`` is ambiguous — Python resolves
        against its own CWD, Java resolves against Gradle's CWD
        (REPO_ROOT). Historically the two frames diverged when calibrate
        spawned sub-runs from ``scripts/jseval``: Python's rmtree hit
        ``scripts/jseval/tmp/...`` while Java wrote to
        ``REPO_ROOT/tmp/...``, so ``--clean`` never wiped the real index
        and run 2 stalled on ``indexed_doc_count_below_floor``. Fix: the
        Python side resolves relative paths against REPO_ROOT, matching
        Java's frame at the one boundary where disagreement mattered.
        """
        monkeypatch.setenv("JUSTSEARCH_DATA_DIR", "tmp/sub/cohort-data")
        # Simulate the calibrate-spawned subprocess where Python's CWD
        # is NOT REPO_ROOT (the historical trigger for the divergence).
        import os as _os
        original_cwd = _os.getcwd()
        try:
            _os.chdir(str(REPO_ROOT / "scripts" / "jseval"))
            mock_proc = MagicMock()
            mock_popen.return_value = mock_proc

            info = start_backend()
            # Must resolve against REPO_ROOT, not scripts/jseval.
            expected = (REPO_ROOT / "tmp" / "sub" / "cohort-data").resolve()
            assert info.data_dir == expected
            # The Popen env matches the Python-side resolved path, so a
            # Java subprocess reading JUSTSEARCH_DATA_DIR with cwd=REPO_ROOT
            # would land on the same absolute directory.
            env = mock_popen.call_args.kwargs["env"]
            assert env["JUSTSEARCH_DATA_DIR"] == str(expected)
        finally:
            _os.chdir(original_cwd)

    @patch("jseval.backend.subprocess.Popen")
    @patch("jseval.backend._wait_for_health", return_value=True)
    def test_absolute_env_path_is_not_rewritten(
        self, _health, mock_popen, tmp_path, monkeypatch,
    ):
        """Absolute paths must pass through untouched — the REPO_ROOT
        rewrite only applies to the relative case."""
        absolute = tmp_path / "absolute-data"
        absolute.mkdir()
        monkeypatch.setenv("JUSTSEARCH_DATA_DIR", str(absolute))
        mock_proc = MagicMock()
        mock_popen.return_value = mock_proc

        info = start_backend()
        assert info.data_dir == absolute


class TestStartBackendCleanWipesEverything:
    """Tempdoc 716: the tempdoc-400 protected-set carve-out is retired.

    No durable jseval artifact lives in the backend data dir (they are filed under
    the jseval-owned root, `_paths.DEFAULT_JSEVAL_DATA_DIR`), so --clean wipes the
    WHOLE dir with nothing carved out."""

    @patch("jseval.backend.subprocess.Popen")
    @patch("jseval.backend._wait_for_health", return_value=True)
    def test_clean_wipes_entire_data_dir_no_protected_set(self, _health, mock_popen,
                                                          tmp_path):
        data_dir = tmp_path / "data"
        # Anything a prior run left behind here is wiped, whatever its name.
        (data_dir / "leftover-state" / "hash-a").mkdir(parents=True)
        (data_dir / "leftover-state" / "hash-a" / "keep-me.json").write_text(
            "{}", encoding="utf-8")
        (data_dir / "index").mkdir()
        (data_dir / "index" / "segments.json").write_text("{}", encoding="utf-8")
        (data_dir / "app.lock").write_text("", encoding="utf-8")

        mock_proc = MagicMock()
        mock_popen.return_value = mock_proc

        start_backend(data_dir=data_dir, clean=True)

        # Everything wiped; the dir itself remains for the new run.
        assert data_dir.is_dir()
        assert list(data_dir.iterdir()) == []


class TestStartBackendEvalModeLlmFailsFast:
    """Tempdoc 782 §I: `start_backend(llm=True)` is structurally impossible through
    this entry point -- it boots `:modules:ui:runHeadlessEval`, whose eval contract
    hard-codes `justsearch.ui.settings.mode=IN_MEMORY`, so the `-Pllm=true` chatEnabled
    seed is silently discarded and the reconciler never starts llama-server. Before
    this guard the request burned the whole ~240s inference deadline and died with the
    generic "inference stayed offline" (twice, mid-campaign)."""

    @patch("jseval.backend.subprocess.Popen")
    @patch("jseval.backend._wait_for_health", return_value=True)
    def test_llm_true_raises_before_spawning_anything(self, _health, mock_popen, tmp_path):
        with pytest.raises(EvalModeLlmUnsupportedError) as excinfo:
            start_backend(llm=True, data_dir=tmp_path / "data")
        message = str(excinfo.value)
        # Names the cause...
        assert "IN_MEMORY" in message
        assert "runHeadlessEval" in message
        # ...and the out-of-band recipe (llama-server on 8081 behind the Head /v1 proxy).
        assert "8081" in message
        assert "/v1/models" in message
        assert "782" in message
        # Fail FAST: no Gradle process was spawned and no readiness wait happened.
        mock_popen.assert_not_called()

    @patch("jseval.backend.subprocess.Popen")
    @patch("jseval.backend._wait_for_health", return_value=True)
    def test_llm_true_does_not_clean_the_data_dir(self, _health, _popen, tmp_path):
        # The guard runs BEFORE --clean, so a rejected call cannot destroy an index.
        data_dir = tmp_path / "data"
        data_dir.mkdir()
        (data_dir / "keep.txt").write_text("x", encoding="utf-8")
        with pytest.raises(EvalModeLlmUnsupportedError):
            start_backend(llm=True, clean=True, data_dir=data_dir)
        assert (data_dir / "keep.txt").is_file()

    @patch("jseval.backend.subprocess.Popen")
    @patch("jseval.backend._wait_for_health", return_value=True)
    def test_llm_false_path_is_untouched(self, _health, mock_popen, tmp_path):
        # Adverse control: the default path still boots normally, with no -Pllm=true.
        mock_popen.return_value = MagicMock()
        info = start_backend(llm=False, data_dir=tmp_path / "data")
        assert info.proc is mock_popen.return_value
        assert "-Pllm=true" not in mock_popen.call_args[0][0]


class TestStartBackendModelsDirResolution:
    """Tempdoc 644 Axis 1: when JUSTSEARCH_MODELS_DIR is unset, start_backend defaults it
    to the shared (main-checkout) models dir so worktree eval discovers the real models
    instead of the worktree's LFS-pointer-only copy."""

    @patch("jseval.backend.subprocess.Popen")
    @patch("jseval.backend._wait_for_health", return_value=True)
    @patch("jseval.backend.shared_models_dir")
    def test_defaults_models_dir_when_unset(self, mock_shared, _health, mock_popen,
                                            tmp_path, monkeypatch):
        monkeypatch.delenv("JUSTSEARCH_MODELS_DIR", raising=False)
        main_models = tmp_path / "main" / "models"
        main_models.mkdir(parents=True)
        mock_shared.return_value = main_models
        mock_popen.return_value = MagicMock()

        start_backend()

        env = mock_popen.call_args.kwargs["env"]
        assert env["JUSTSEARCH_MODELS_DIR"] == str(main_models)

    @patch("jseval.backend.subprocess.Popen")
    @patch("jseval.backend._wait_for_health", return_value=True)
    @patch("jseval.backend.shared_models_dir")
    def test_caller_env_models_dir_wins(self, mock_shared, _health, mock_popen,
                                        tmp_path, monkeypatch):
        explicit = tmp_path / "explicit-models"
        explicit.mkdir()
        monkeypatch.setenv("JUSTSEARCH_MODELS_DIR", str(explicit))
        mock_popen.return_value = MagicMock()

        start_backend()

        env = mock_popen.call_args.kwargs["env"]
        assert env["JUSTSEARCH_MODELS_DIR"] == str(explicit)
        # Short-circuit: an already-set value means we never resolve the shared dir.
        mock_shared.assert_not_called()

    @patch("jseval.backend.subprocess.Popen")
    @patch("jseval.backend._wait_for_health", return_value=True)
    @patch("jseval.backend.shared_models_dir", return_value=None)
    def test_no_models_dir_set_when_none_resolvable(self, _shared, _health, mock_popen,
                                                    monkeypatch):
        monkeypatch.delenv("JUSTSEARCH_MODELS_DIR", raising=False)
        mock_popen.return_value = MagicMock()

        start_backend()

        env = mock_popen.call_args.kwargs["env"]
        assert "JUSTSEARCH_MODELS_DIR" not in env

    @patch("jseval.backend.subprocess.Popen")
    @patch("jseval.backend._wait_for_health", return_value=True)
    def test_clean_on_empty_data_dir_is_noop(self, _health, mock_popen, tmp_path):
        data_dir = tmp_path / "data"
        data_dir.mkdir()
        mock_proc = MagicMock()
        mock_popen.return_value = mock_proc
        start_backend(data_dir=data_dir, clean=True)
        assert data_dir.is_dir()


class TestCleanFailsClosedOnStuckHandle:
    """Real failure-path (711 item 4): a locked file must produce a hard
    error, not a silent no-op wipe. Windows share-mode locking makes an
    open file handle a native way to force os.unlink/shutil.rmtree to fail —
    no mocking of the filesystem layer needed."""

    @pytest.mark.skipif(sys.platform != "win32", reason="relies on Windows share-mode file locking")
    @patch("jseval.backend.subprocess.Popen")
    @patch("jseval.backend._wait_for_health", return_value=True)
    def test_open_handle_blocks_wipe_raises(self, _health, mock_popen, tmp_path):
        data_dir = tmp_path / "data"
        (data_dir / "index").mkdir(parents=True)
        stuck_file = data_dir / "index" / "somefile"
        stuck_file.write_text("locked", encoding="utf-8")

        mock_popen.return_value = MagicMock()

        handle = open(stuck_file, "r+b")
        try:
            with pytest.raises(RuntimeError) as excinfo:
                start_backend(data_dir=data_dir, clean=True)
            message = str(excinfo.value)
            assert "index" in message
            assert str(data_dir) in message
            mock_popen.assert_not_called()
        finally:
            handle.close()

    def test_clean_data_dir_retries_and_rejects_survivors(self, tmp_path):
        """Lane F F-4 retains two attempts and fail-closed verification."""
        from jseval.backend import _clean_data_dir

        data_dir = tmp_path / "data"
        stuck_dir = data_dir / "stuck"
        stuck_dir.mkdir(parents=True)
        with patch("jseval.backend.shutil.rmtree", side_effect=OSError("file in use")) as delete:
            with pytest.raises(RuntimeError, match="survivor.*stuck"):
                _clean_data_dir(data_dir)
        assert delete.call_count == 2
        assert stuck_dir.is_dir()

    def test_clean_data_dir_retry_can_complete(self, tmp_path):
        from jseval.backend import _clean_data_dir
        import shutil

        stuck_dir = tmp_path / "stuck"
        stuck_dir.mkdir()
        real_delete = shutil.rmtree
        calls = []

        def transient_delete(path):
            calls.append(path)
            if len(calls) == 1:
                raise OSError("transient file handle")
            real_delete(path)

        with patch("jseval.backend.shutil.rmtree", side_effect=transient_delete):
            _clean_data_dir(tmp_path)
        assert len(calls) == 2
        assert list(tmp_path.iterdir()) == []


class TestHealthTimeoutEnvOverride:
    """707 gate-run unblocker: JSEVAL_HEALTH_TIMEOUT_SEC must reach start_backend
    without threading a kwarg through every CLI call site — the two prior 707
    gate attempts died at the fixed 120s boundary (tempdoc 719 deferred-checks),
    and corpus-fidelity/corpus-probe don't pass health_timeout_sec."""

    def _remaining(self, mock_health):
        deadline = mock_health.call_args.args[1]
        return deadline - time.monotonic()

    @patch("jseval.backend.subprocess.Popen")
    @patch("jseval.backend._wait_for_health", return_value=True)
    def test_env_var_raises_boundary(self, mock_health, mock_popen, monkeypatch):
        monkeypatch.setenv("JSEVAL_HEALTH_TIMEOUT_SEC", "300")
        mock_popen.return_value = MagicMock()
        start_backend()
        assert 290 < self._remaining(mock_health) <= 300.5

    @patch("jseval.backend.subprocess.Popen")
    @patch("jseval.backend._wait_for_health", return_value=True)
    def test_default_without_env(self, mock_health, mock_popen, monkeypatch):
        monkeypatch.delenv("JSEVAL_HEALTH_TIMEOUT_SEC", raising=False)
        mock_popen.return_value = MagicMock()
        start_backend()
        assert 110 < self._remaining(mock_health) <= 120.5

    @patch("jseval.backend.subprocess.Popen")
    @patch("jseval.backend._wait_for_health", return_value=True)
    def test_explicit_kwarg_beats_env(self, mock_health, mock_popen, monkeypatch):
        monkeypatch.setenv("JSEVAL_HEALTH_TIMEOUT_SEC", "300")
        mock_popen.return_value = MagicMock()
        start_backend(health_timeout_sec=77)
        assert 67 < self._remaining(mock_health) <= 77.5

    def test_garbage_env_fails_closed(self, monkeypatch):
        monkeypatch.setenv("JSEVAL_HEALTH_TIMEOUT_SEC", "soon")
        with pytest.raises(ValueError, match="JSEVAL_HEALTH_TIMEOUT_SEC"):
            start_backend()
