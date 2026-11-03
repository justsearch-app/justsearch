"""Prepared-only launch reproductions: neither absence nor success runs Gradle."""
import json
from pathlib import Path
import shutil
from unittest.mock import Mock, MagicMock

import pytest

from jseval import backend


def test_missing_prepared_distribution_never_builds(tmp_path,monkeypatch):
    (tmp_path/'gradlew').touch()
    build=Mock(side_effect=AssertionError('unexpected installDist inside scoring'))
    monkeypatch.setattr(backend,'_install_distribution',build)
    with pytest.raises(RuntimeError,match='not ready'):
        backend.start_backend(repo_root=tmp_path,data_dir=tmp_path/'data',prepared_only=True)
    build.assert_not_called()


def test_prepared_success_launches_without_install_or_gradle(tmp_path,monkeypatch):
    root=Path(__file__).resolve().parents[4]
    spec=tmp_path/'contracts/native-targets/spec.v1.json'
    spec.parent.mkdir(parents=True)
    shutil.copyfile(root/'contracts/native-targets/spec.v1.json',spec)
    dist=tmp_path/'modules/ui/build/install/ui'
    (dist/'bin').mkdir(parents=True)
    (dist/'bin/ui').touch()
    (dist/'bin/ui.bat').touch()
    (dist/'.jseval-source.json').write_text(json.dumps(['a'*40,None]))
    data=tmp_path/'data'
    index=data/'index/default'
    index.mkdir(parents=True)
    (index/'segments_1').touch()
    monkeypatch.setattr(backend,'source_identity',lambda _:('a'*40,None))
    build=Mock(side_effect=AssertionError('unexpected installDist inside scoring'))
    monkeypatch.setattr(backend,'_install_distribution',build)
    proc=MagicMock()
    monkeypatch.setattr(backend.subprocess,'Popen',Mock(return_value=proc))
    monkeypatch.setattr(backend,'_wait_for_health',lambda *args:True)
    monkeypatch.setattr(backend.run_register,'register_backend',Mock())
    info=backend.start_backend(repo_root=tmp_path,data_dir=data,prepared_only=True,startup_admission=False)
    assert info.proc is proc
    build.assert_not_called()
