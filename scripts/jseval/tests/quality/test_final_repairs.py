"""Final readiness, admission-budget and standalone-clone regressions."""
from dataclasses import replace
import json
import os
from pathlib import Path
import subprocess
import sys
from unittest.mock import Mock

import pytest
from click.testing import CliRunner

from jseval import _paths, backend, index_identity
from jseval.cli import main
from jseval.quality import collections, index, run
from .helpers import config_for, make_collection
from .test_collections import _beir_dir
from .test_redesign import cached_handle


@pytest.mark.parametrize('reason', ['index_not_idle', 'index_queue_not_quiescent',
                                  'splade_not_complete; unused sparse stages are enabled'])
def test_cached_confirmation_waits_for_twenty_second_serving_start(tmp_path, monkeypatch, reason):
    handle = cached_handle(tmp_path, monkeypatch)
    now = [0.0]
    monkeypatch.setattr(index.time, 'monotonic', lambda: now[0])
    monkeypatch.setattr(index.time, 'sleep', lambda seconds: now.__setitem__(0, now[0]+seconds))
    def engine(*args):
        return index_identity.ConfirmResult(now[0] >= 20, [] if now[0] >= 20 else ['serving.error: '+reason],
                                            {'serving': {'transient': True}})
    monkeypatch.setattr(index, 'confirm', engine)
    try:
        assert handle.wait(prepared_only=True, deadline=30) is handle
        assert 20 <= now[0] < 30
    finally:
        handle.close()


def test_confirmation_deadline_keeps_last_serving_reason(monkeypatch):
    now = [0.0]
    monkeypatch.setattr(index.time, 'monotonic', lambda: now[0])
    monkeypatch.setattr(index.time, 'sleep', lambda seconds: now.__setitem__(0, now[0]+seconds))
    monkeypatch.setattr(index, '_confirm', lambda *a: index_identity.ConfirmResult(
        False, ['serving.error: index_not_idle'], {'serving': {'transient': True}}))
    with pytest.raises(index.TransientIndexRefused, match='not ready, transient:.*index_not_idle'):
        index._confirm_ready(Mock(), 25)
    assert now[0] == 25


def test_prepare_reports_complete_transient_reason_without_nonexistent_report(tmp_path, monkeypatch):
    c = make_collection()
    folder = collections.write_collection(c, tmp_path/'collection')
    (folder/'quality.config.json').write_text(json.dumps({
        'schema': collections.CONFIG_SCHEMA, 'collection': c.name, 'language': c.language,
        'source': {'kind': 'synthetic'}, 'judgments': 'official', 'expected': c.counts()}))
    # A failed worker must survive both wait() and context-manager teardown with
    # its typed reason, rather than a truncated traceback replacing the error.
    message = 'not ready, transient: serving confirmation deadline: index_not_idle; ' + 'detail '*220
    index._write(tmp_path/'failure.json', {'transient': True, 'reason': message})
    (tmp_path/'build.log').write_text('Traceback\n'+'noise '*1400)
    lock = (tmp_path/'data.lock').open('a+')
    worker = Mock(returncode=1)
    worker.poll.return_value = 1
    handle = index.IndexHandle(c, index.IndexConfig(), 'key', tmp_path, tmp_path/'data',
                               'cached', {}, {}, lock, _worker=worker)
    monkeypatch.setattr(index, 'prepare_index', lambda *a: handle)
    output = CliRunner().invoke(main, ['quality', 'prepare', str(folder)])
    assert output.exit_code == 2, output.output
    assert output.output.strip() == 'INVALID '+message.strip()
    assert 'report' not in output.output and 'Traceback' not in output.output
    assert lock.closed


def test_worker_records_typed_preparation_failure(tmp_path, monkeypatch):
    reason = 'not ready, transient: splade_not_complete; unused sparse stages are enabled'
    monkeypatch.setattr(index, '_build', Mock(side_effect=index.TransientIndexRefused(reason)))
    monkeypatch.setattr(sys, 'argv', ['index', 'build', str(tmp_path)])
    with pytest.raises(SystemExit) as exit:
        index.main()
    assert exit.value.code != 0
    assert json.loads((tmp_path/'failure.json').read_text()) == {'transient': True, 'reason': reason}


def test_quality_requests_ten_minute_drain(tmp_path, monkeypatch):
    c = make_collection()
    monkeypatch.setattr(backend, 'source_identity', lambda _: ('commit', None))
    monkeypatch.setattr(backend, 'require_prepared', lambda *a: None)
    monkeypatch.setattr(backend, 'bind_command', lambda _, cmd: cmd)
    monkeypatch.setattr(run.platform, 'processor', lambda: 'test CPU')
    monkeypatch.setattr(run.collections, 'resolve_collection', lambda _: (c, config_for(c)))
    monkeypatch.setattr(index.IndexConfig, 'environment', lambda _: {})
    h = Mock(model_hashes={'model': 'hash'})
    h.__enter__ = Mock(return_value=h)
    h.__exit__ = Mock(return_value=False)
    monkeypatch.setattr(index, 'prepare_index', lambda *a, **kw: h)
    launch = Mock(return_value=Mock(wait=lambda: 75))
    monkeypatch.setattr(run.subprocess, 'Popen', launch)
    run.execute('baseline', ('synthetic',), runs_dir=tmp_path/'runs', baseline_dir=tmp_path/'baselines')
    command = launch.call_args.args[0]
    assert command[command.index('--drain-timeout')+1] == '600'
    assert float(command[command.index('--timeout')+1]) <= 600


def test_admission_accepts_ten_minute_drain_but_bounds_twenty_minute_gate(tmp_path):
    admission = Path(os.environ.get('ADMISSION_CLI', index.REPO_ROOT/'scripts/dev/admission.py'))
    env = dict(os.environ, JS_ADMISSION_DIR=str(tmp_path/'admission'))
    env.pop('JS_ADMISSION_TOKEN', None)
    for option, value, expected in [('--drain-timeout', '600', 0), ('--drain-timeout', '601', 2),
                                     ('--gate-timeout', '1200', 0), ('--gate-timeout', '1201', 2)]:
        result = subprocess.run([sys.executable, str(admission), 'exclusive', '--purpose', 'cap test',
            option, value, '--timeout', '2', '--', sys.executable, '-c', 'pass'],
            env=env, capture_output=True, text=True, timeout=5)
        assert result.returncode == expected, result.stderr


def test_shared_clone_resolves_models_and_enron_like_worktree(tmp_path, monkeypatch):
    source = tmp_path/'main'
    source.mkdir()
    subprocess.run(['git', 'init', '-q', str(source)], check=True)
    (source/'scripts/jseval').mkdir(parents=True)
    (source/'models').mkdir()
    _beir_dir(source/'enron')
    clone = tmp_path/'clone'
    subprocess.run(['git', 'clone', '--quiet', '--shared', '--no-checkout', str(source), str(clone)], check=True)
    # Tracked LFS pointers in a clone must not hide binary-bearing shared assets.
    (clone/'models').mkdir()
    monkeypatch.setattr(_paths, 'REPO_ROOT', clone)
    monkeypatch.setattr(collections, 'REPO_ROOT', clone, raising=False)
    assert _paths.shared_models_dir() == source/'models'
    cfg = replace(collections.load_config('enron-email'), source={'kind': 'beir-dir', 'path': 'enron'})
    assert collections.build(cfg).counts()['documents'] == 4


def test_standalone_pointer_models_name_required_flag(tmp_path):
    models = tmp_path/'models'
    (models/'onnx').mkdir(parents=True)
    (models/'onnx/model.onnx').write_text('version https://git-lfs.github.com/spec/v1\noid sha256:abc\nsize 1234\n')
    with pytest.raises(index.IndexRefused, match='--models-dir'):
        index.model_hashes(models)


def test_missing_standalone_enron_names_collection_argument(tmp_path, monkeypatch):
    monkeypatch.setattr(_paths, 'REPO_ROOT', tmp_path)
    with pytest.raises(collections.CollectionError, match='quality check /path/to/stored/collection'):
        collections.build(collections.load_config('enron-email'))
