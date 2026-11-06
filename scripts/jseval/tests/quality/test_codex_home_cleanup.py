"""Credential-home cleanup through real process signals and interprocess locks."""
import contextlib
import fcntl
import json
import os
from pathlib import Path
import select
import signal
import subprocess
import sys

import pytest

from jseval.quality import topical_labels as tl


@contextlib.contextmanager
def setup_process(tmp_path, phase="setup"):
    auth = tmp_path / "auth.json"
    auth.write_text(json.dumps({"auth_mode": "chatgpt", "tokens": {
        "access_token": "synthetic-access", "refresh_token": "synthetic-refresh"}}))
    script = """
import signal, sys
from pathlib import Path
from jseval.quality import topical_labels as tl
tl.WORK = Path(sys.argv[1])
tl._codex_auth_path = lambda: Path(sys.argv[2])
def wait(home, *args):
    print(home, flush=True)
    while True:
        signal.pause()
tl._verify_codex_home = wait if sys.argv[3] == 'setup' else lambda *args: None
with tl.codex_home() as home:
    wait(home)
"""
    env = {k: v for k, v in os.environ.items()
           if not k.startswith(tl.CODEX_OVERRIDE_PREFIXES) and k not in tl.CODEX_OVERRIDES}
    process = subprocess.Popen([sys.executable, "-u", "-c", script, str(tmp_path / "work"),
                                str(auth), phase], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               text=True, env=env)
    try:
        assert select.select([process.stdout], [], [], 10)[0], "setup did not become ready"
        line = process.stdout.readline().strip()
        assert line, process.communicate(timeout=10)[1]
        home = Path(line)
        assert (home / "auth.json").is_file()
        yield process, home
    finally:
        if process.poll() is None:
            process.kill()
        process.communicate(timeout=10)


@pytest.mark.parametrize("termination", [signal.SIGTERM, signal.SIGINT])
@pytest.mark.parametrize("phase", ["setup", "calls"])
def test_termination_removes_the_home_even_during_setup(tmp_path, termination, phase):
    with setup_process(tmp_path, phase) as (process, home):
        process.send_signal(termination)
        process.wait(timeout=10)
        assert not home.exists()
        assert not list(home.parent.glob("codex-home-*"))


def start_step(monkeypatch, step):
    """Stop at the first ordinary step input, after its startup credential sweep."""
    def stop(*args, **kwargs):
        raise tl.LabelError("test stops before reading label records")
    monkeypatch.setattr(tl, "_revision_after", stop)
    monkeypatch.setattr(tl, "_state", stop)
    monkeypatch.setattr(tl, "_check_revision", stop)
    with pytest.raises(tl.LabelError, match="test stops"):
        if step == "second_judge":
            tl.second_judge(max_calls=0)
        else:
            getattr(tl, step)()


@pytest.mark.parametrize("step", ["second_judge", "accept", "freeze"])
def test_start_removes_abandoned_homes_after_sigkill(tmp_path, monkeypatch, step):
    with setup_process(tmp_path) as (process, home):
        process.kill()
        process.wait(timeout=10)
        assert (home / "auth.json").is_file()  # SIGKILL cannot run cleanup.
        legacy = home.parent / "codex-home-before-locks"
        legacy.mkdir()
        (legacy / "auth.json").write_text("synthetic old credential")
        record = home.parent / "judgments.jsonl"
        record.write_text("existing record\n")
        monkeypatch.setattr(tl, "WORK", home.parent)
        start_step(monkeypatch, step)
        assert not home.exists()
        assert not legacy.exists()
        assert record.read_text() == "existing record\n"


@pytest.mark.parametrize("step", ["second_judge", "accept", "freeze"])
def test_start_preserves_a_home_locked_by_another_process(tmp_path, monkeypatch, step):
    with setup_process(tmp_path) as (_, home):
        with (home / "lock").open("a+b") as lock:
            with pytest.raises(BlockingIOError):
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        monkeypatch.setattr(tl, "WORK", home.parent)
        start_step(monkeypatch, step)
        assert (home / "auth.json").is_file()


@pytest.mark.parametrize("step", ["extract", "pool", "excerpts", "judge", "project", "sample"])
def test_other_labelling_commands_also_sweep_before_work(tmp_path, monkeypatch, step):
    monkeypatch.setattr(tl, "WORK", tmp_path)
    home = tmp_path / "codex-home-abandoned"
    home.mkdir()
    (home / "auth.json").write_text("synthetic credential")

    def run(*args, **kwargs):
        assert not home.exists()
        raise tl.LabelError("test stops before doing work")

    monkeypatch.setattr(tl, "projection" if step == "project" else step, run)
    assert tl.main([step, *(["--max-calls", "0"] if step == "judge" else [])]) == 2
