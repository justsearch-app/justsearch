"""The installed eval launch boundary (Linux/Windows share the invoker and runner)."""
from pathlib import Path
from unittest.mock import MagicMock, patch
import os
import subprocess
import sys
import shutil
from concurrent.futures import ThreadPoolExecutor
import pytest
from jseval.backend import _install_distribution, _boot_and_wait


@pytest.fixture(autouse=True)
def stage_native_contract(tmp_path):
    source = Path(__file__).resolve().parents[3] / "contracts/native-targets/spec.v1.json"
    target = tmp_path / "contracts/native-targets/spec.v1.json"
    target.parent.mkdir(parents=True)
    shutil.copyfile(source, target)


@pytest.mark.skipif(sys.platform != "linux", reason="Linux parent-death binding is thread-specific")
def test_worker_thread_launch_is_refused_before_build_or_spawn(tmp_path):
    with patch("jseval.backend._install_distribution") as build, \
            patch("jseval.backend.subprocess.Popen") as popen, ThreadPoolExecutor(max_workers=1) as pool:
        future = pool.submit(_boot_and_wait, resolved_root=tmp_path, resolved_data=tmp_path / "data",
                             gradlew=tmp_path / "gradlew", env={}, port=33221, llm=False, health_timeout_sec=1)
        with pytest.raises(RuntimeError, match="process-lifetime main thread"):
            future.result()
    build.assert_not_called()
    popen.assert_not_called()


def test_install_distribution_uses_shared_invoker_and_literal_argv(tmp_path):
    root = tmp_path / "checkout with spaces ü"
    with patch("jseval.backend.subprocess.run", return_value=subprocess.CompletedProcess([], 0)) as run:
        _install_distribution(root, {"JUSTSEARCH_MODELS_DIR": "models space"})
    args, options = run.call_args
    assert args[0] == ["node", str(root / "scripts/lib/tool-invoker.cjs"), "gradle",
                       ":modules:ui:installDist", "-PskipWebBuild=true", "--quiet"]
    assert options["cwd"] == str(root)
    assert options["env"]["JUSTSEARCH_MODELS_DIR"] == "models space"
    assert not options.get("shell", False)


def test_failed_build_prevents_runner_launch(tmp_path):
    with patch("jseval.backend.subprocess.run", return_value=subprocess.CompletedProcess([], 17)), \
            patch("jseval.backend.subprocess.Popen") as popen:
        with pytest.raises(RuntimeError, match="installDist failed.*17"):
            _boot_and_wait(resolved_root=tmp_path, resolved_data=tmp_path / "data", gradlew=tmp_path / "gradlew",
                           env={}, port=33221, llm=False, health_timeout_sec=1)
    popen.assert_not_called()


@pytest.mark.parametrize("platform_name", sorted({sys.platform, "darwin"}))
def test_build_precedes_bound_runner_and_registration(tmp_path, platform_name):
    calls = []
    proc = MagicMock()
    with patch("jseval.backend.sys.platform", platform_name), \
            patch("jseval.backend._install_distribution", side_effect=lambda *_: calls.append("build")), \
            patch("jseval.backend.subprocess.Popen", side_effect=lambda *_a, **_k: (calls.append("spawn"), proc)[1]) as popen, \
            patch("jseval.backend.run_register.register_backend", side_effect=lambda **_: calls.append("register")), \
            patch("jseval.backend._wait_for_health", return_value=True):
        _boot_and_wait(resolved_root=tmp_path, resolved_data=tmp_path / "data", gradlew=tmp_path / "gradlew",
                       env={}, port=33221, llm=False, health_timeout_sec=1)
    assert calls == ["build", "spawn", "register"]
    command = popen.call_args.args[0]
    if platform_name == "linux":
        assert command[:5] == [str(tmp_path / "modules/ui/build/install/ui/native/linux-x86_64/justsearch-bound-exec"),
                               "exec", str(os.getpid()), "--", "node"]
    else:
        assert command[0] == "node"
    assert command[-1] == str(tmp_path / "scripts/dev/eval-runner.cjs")
    assert popen.call_args.kwargs["stdin"] == subprocess.PIPE
    assert popen.call_args.kwargs["stdout"] == subprocess.PIPE


def test_bound_runner_stops_through_control_pipe_before_retiring_registration():
    from jseval.backend import stop_backend
    proc = MagicMock()
    proc._justsearch_eval_runner = True
    proc.pid = 123
    proc.poll.return_value = None
    proc.returncode = 0
    with patch("jseval.backend.run_register.unregister_backend") as retire:
        stop_backend(proc)
    proc.stdin.write.assert_called_once_with(b"stop\n")
    proc.stdin.flush.assert_called_once()
    proc.wait.assert_called_once_with(timeout=30)
    proc.terminate.assert_not_called()
    retire.assert_called_once_with(123)


def test_failed_runner_cleanup_keeps_registration():
    from jseval.backend import stop_backend
    proc = MagicMock()
    proc._justsearch_eval_runner = True
    proc.poll.return_value = None
    proc.returncode = 1
    with patch("jseval.backend.run_register.unregister_backend") as retire:
        with pytest.raises(RuntimeError, match="cleanup failed.*retaining registration"):
            stop_backend(proc)
    retire.assert_not_called()
