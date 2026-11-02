"""jseval runs the jsharness beside it, never one found elsewhere on the path."""
from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

JSEVAL_ROOT = Path(__file__).resolve().parents[1]
OWN_JSHARNESS = JSEVAL_ROOT.parent / "jsharness" / "jsharness"


def _run(code: str, *path_entries: Path) -> subprocess.CompletedProcess:
    env = {**os.environ, "PYTHONPATH": os.pathsep.join(str(p) for p in path_entries)}
    return subprocess.run([sys.executable, "-c", code], env=env, capture_output=True,
                          text=True, cwd=str(JSEVAL_ROOT.parent), timeout=60)


def _decoy(tmp_path: Path) -> Path:
    package = tmp_path / "other" / "jsharness"
    package.mkdir(parents=True)
    (package / "__init__.py").write_text("")
    (package / "run_register.py").write_text("DECOY = True\n")
    return package.parent


def test_only_jseval_on_path_binds_sibling_jsharness(tmp_path):
    decoy = _decoy(tmp_path)
    result = _run("import jseval, jsharness, jsharness.run_register as r\n"
                  "print(jsharness.__file__); print(r.__file__)", JSEVAL_ROOT, decoy)
    assert result.returncode == 0, result.stderr
    lines = [Path(line) for line in result.stdout.split()]
    assert lines == [OWN_JSHARNESS / "__init__.py", OWN_JSHARNESS / "run_register.py"]


def test_foreign_jsharness_already_imported_is_refused(tmp_path):
    decoy = _decoy(tmp_path)
    result = _run("import jsharness, jseval", decoy, JSEVAL_ROOT)
    assert result.returncode != 0
    assert "ImportError" in result.stderr
    assert str(decoy / "jsharness") in result.stderr
    assert str(OWN_JSHARNESS) in result.stderr
