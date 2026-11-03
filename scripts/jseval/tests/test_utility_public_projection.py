"""The question-level projection must refuse tampered inputs even under ``python -O``.

``python -O`` strips ``assert`` statements, so the manifest and record hash checks have to be
real errors. Each case runs the projection in an optimised subprocess against a copy of the
published bundle with one file altered.
"""
from __future__ import annotations

import json
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

from jseval._paths import REPO_ROOT

PUBLIC = Path('scripts/jseval/public-agent-utility')


def _copy_bundle(tmp_path: Path) -> tuple[Path, Path]:
    root = tmp_path / 'root'
    shutil.copytree(REPO_ROOT / PUBLIC, root / PUBLIC)
    pointer = json.loads((root / PUBLIC / 'current.v1.json').read_text())
    manifest_path = root / PUBLIC / pointer['current']['path']
    manifest = json.loads(manifest_path.read_text())
    for name in ('record', 'observations'):
        source = (REPO_ROOT / PUBLIC / pointer['current']['path']).parent / manifest[name]['path']
        dest = manifest_path.parent / manifest[name]['path']
        if not dest.exists():
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, dest)
    policy = Path('scripts/jseval/utility-claim-policy.v5.json')
    shutil.copyfile(REPO_ROOT / policy, root / policy)
    return root, manifest_path


def _project_optimised(root: Path) -> subprocess.CompletedProcess:
    code = (
        'import sys; from pathlib import Path; '
        'from jseval.utility_public_projection import projection; '
        'projection(Path(sys.argv[1]))'
    )
    return subprocess.run(
        [sys.executable, '-O', '-c', code, str(root)],
        cwd=REPO_ROOT / 'scripts/jseval', capture_output=True, text=True, check=False,
    )


@pytest.mark.parametrize('target', ['manifest', 'record'])
def test_tampered_bundle_is_refused_under_python_O(tmp_path, target):
    root, manifest_path = _copy_bundle(tmp_path)
    if target == 'manifest':
        path = manifest_path
    else:
        manifest = json.loads(manifest_path.read_text())
        path = manifest_path.parent / manifest['record']['path']
    path.write_bytes(path.read_bytes() + b'\n')

    result = _project_optimised(root)

    assert result.returncode != 0
    assert 'ValueError' in result.stderr
    assert f'{target} sha256 mismatch' in result.stderr
