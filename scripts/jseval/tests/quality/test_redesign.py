"""Redesign regressions: cached content survives serving failures; owned work dies."""
from dataclasses import replace
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
from unittest.mock import Mock

import httpx
import pytest

from jseval import backend, index_identity
from jseval.quality import compare, index, report
from .helpers import config_for, make_collection, make_run


@pytest.fixture
def serving_clock(monkeypatch):
    now = [100.0]
    monkeypatch.setattr(index.time, 'monotonic', lambda: now[0])
    monkeypatch.setattr(index.time, 'sleep', lambda seconds: now.__setitem__(0, now[0]+seconds))
    return now


def cached_handle(tmp_path, monkeypatch):
    c = make_collection()
    data = tmp_path/'data'
    data.mkdir()
    owner = index._population_owner(c, 'key')
    index._write(data/'quality-owner.json', owner)
    index._write(tmp_path/'entry.json', {'owner': owner})
    lock = (tmp_path/'data.quality.lock').open('a+')
    h = index.IndexHandle(c, index.IndexConfig(), 'key', tmp_path, data, 'cached', {}, {}, lock)
    proc = Mock()
    proc.poll.return_value = None
    monkeypatch.setattr(backend, 'require_prepared', lambda *a: None)
    monkeypatch.setattr(backend, 'start_backend', Mock(return_value=backend.BackendInfo(proc, data)))
    monkeypatch.setattr(backend, 'stop_backend', Mock())
    monkeypatch.setattr(index, '_launch_env', lambda *a: {})
    return h


@pytest.mark.parametrize('reason', ['DEADLINE_EXCEEDED', 'ACQUIRE_BUDGET_EXCEEDED',
                                   'MODEL_LOADING', 'INFERENCE_FAILED'])
def test_transient_canary_failure_preserves_cache_and_retries(tmp_path, monkeypatch, reason, serving_clock):
    h = cached_handle(tmp_path, monkeypatch)
    # Exercise the real canary decoder and the quality confirmation, not a synthetic flag.
    response = {'totalHits': 2, 'searchTrace': {'stages': [
        {'id': 'cross-encoder', 'status': 'skipped', 'reason': reason}]}}
    failures, checks = [], {}
    with httpx.Client(base_url='http://engine', transport=httpx.MockTransport(
            lambda _: httpx.Response(200, json=response))) as client:
        index_identity._confirm_canary(client, {'attestation': {'canary': {
            'query': 'query words', 'required_stages': ['cross-encoder']}}}, failures.append, checks)
    monkeypatch.setattr(index, 'compute_index_key', lambda *a: ('key', {}))
    monkeypatch.setattr(index, '_materialize', lambda *a: h.collection)
    monkeypatch.setattr(index, 'witness', lambda *a: {})
    monkeypatch.setattr(index, '_adoption_agreement', lambda *a: [])
    index._write(h.root/'entry.json', {'owner': index._population_owner(h.collection, 'key'), 'witness': {}})
    adopted = index_identity.ConfirmResult(False, failures, checks)
    monkeypatch.setattr(index_identity, 'confirm_adoption', lambda *a, **kw: adopted)
    confirmation = Mock(wraps=index.confirm)
    monkeypatch.setattr(index, 'confirm', confirmation)
    with pytest.raises(index.IndexRefused, match='not ready, transient:') as error:
        h.wait(prepared_only=True, deadline=time.monotonic()+5)
    assert reason in str(error.value)
    assert confirmation.call_count == 3
    assert (h.root/'entry.json').exists() and h.data_dir.exists()
    assert not (h.root/'quarantine').exists()
    assert h._lock.closed


def test_transient_confirmation_recovers_without_quarantining(tmp_path, monkeypatch, serving_clock):
    h = cached_handle(tmp_path, monkeypatch)
    bad = index_identity.ConfirmResult(False, ['canary.stage.cross-encoder: status=skipped reason=DEADLINE_EXCEEDED'],
                                       {'canary': {'transient': True}})
    confirmation = Mock(side_effect=[bad, index_identity.ConfirmResult(True, [], {})])
    monkeypatch.setattr(index, 'confirm', confirmation)
    try:
        assert h.wait(prepared_only=True, deadline=time.monotonic()+5) is h
        assert confirmation.call_count == 2
        assert (h.root/'entry.json').exists()
    finally:
        h.close()


def test_health_failure_retries_without_quarantining(tmp_path, monkeypatch):
    h = cached_handle(tmp_path, monkeypatch)
    backend.start_backend.side_effect = RuntimeError('engine failed health timeout')
    monkeypatch.setattr(index.time, 'sleep', lambda _: None)
    with pytest.raises(index.IndexRefused, match='not ready, transient:'):
        h.wait(prepared_only=True, deadline=time.monotonic()+.1)
    assert backend.start_backend.call_count == 3
    assert (h.root/'entry.json').exists() and h.data_dir.exists()
    assert h._lock.closed


@pytest.mark.parametrize('recovers', [False, True])
def test_cached_worker_configuration_snapshot_is_serving_not_corruption(tmp_path, monkeypatch, recovers, serving_clock):
    h = cached_handle(tmp_path, monkeypatch)
    # Cached startup can answer health before the worker's resolved enable flags
    # arrive. This is the real readiness failure from the CPU acceptance run.
    status = {'indexState': 'IDLE', 'indexedDocuments': len(h.collection.documents),
              'embeddingEnabled': False, 'spladeEnabled': True, 'chunkSpladeEnabled': False,
              'nerEnabled': False, 'failedNerCount': 0, 'completenessKnown': True,
              'completeness': [{'stageId': 'splade', 'missing': 0, 'failed': 0}]}
    requests = []
    client_class = httpx.Client
    def transport(request):
        requests.append(request.url.path)
        assert request.url.path == '/api/status'
        return httpx.Response(200, json=status)
    monkeypatch.setattr(index.httpx, 'Client', lambda **kw:
        client_class(transport=httpx.MockTransport(transport), **kw))
    monkeypatch.setattr(index, 'compute_index_key', lambda *a: ('key', {}))
    monkeypatch.setattr(index, '_materialize', lambda *a: h.collection)
    monkeypatch.setattr(index, '_adoption_agreement', lambda *a: [])
    index._write(h.root/'entry.json', {'owner': index._population_owner(h.collection, 'key'), 'witness': {}})
    monkeypatch.setattr(index_identity, 'confirm_adoption', lambda *a, **kw:
        index_identity.ConfirmResult(True, [], {}))
    original = index.witness
    def witness(*args):
        if recovers and requests:
            return {}
        return original(*args)
    monkeypatch.setattr(index, 'witness', witness)
    confirmation = Mock(wraps=index.confirm)
    monkeypatch.setattr(index, 'confirm', confirmation)
    try:
        if recovers:
            assert h.wait(prepared_only=True, deadline=time.monotonic()+5) is h
        else:
            with pytest.raises(index.IndexRefused, match='not ready, transient:.*splade_not_complete; unused sparse stages are enabled'):
                h.wait(prepared_only=True, deadline=time.monotonic()+5)
        assert confirmation.call_count == (2 if recovers else 3)
        assert (h.root/'entry.json').exists() and h.data_dir.exists()
        assert not (h.root/'quarantine').exists()
    finally:
        h.close()


def test_identity_failure_is_not_hidden_by_transient_canary(tmp_path, monkeypatch):
    h = cached_handle(tmp_path, monkeypatch)
    confirmation = Mock(return_value=index_identity.ConfirmResult(False,
        ['population mismatch', 'canary.stage.cross-encoder: status=skipped'],
        {'canary': {'transient': True}}))
    monkeypatch.setattr(index, 'confirm', confirmation)
    with pytest.raises(index.IndexRefused, match='not ready'):
        h.wait(prepared_only=True, deadline=time.monotonic()+.1)
    assert confirmation.call_count == 1
    assert not (h.root/'entry.json').exists()
    assert 'population mismatch' in next((h.root/'quarantine').glob('*/reason.json')).read_text()


def test_prepare_gradle_uses_single_use_daemon(tmp_path, monkeypatch):
    launch = Mock(return_value=Mock(returncode=0))
    monkeypatch.setattr(backend.subprocess, 'run', launch)
    backend._install_distribution(tmp_path, {'JSEVAL_GRADLE_SLOT': 'js-gradle-slot'})
    command = launch.call_args.args[0]
    assert command[:3] == ['js-gradle-slot', '-w', '3600']
    assert '--no-daemon' in command


def test_outcome_caps_judgment_list_but_report_keeps_every_pair():
    c = make_collection()
    base = make_run(c)
    cand = replace(base, results=tuple(replace(r, ranked=r.ranked[:2] + tuple(
        d for d in sorted(c.documents, reverse=True) if d not in c.judgments[r.qid])[:6])
        for r in base.results))
    cmp = compare.compare(c, config_for(c, judgments='pooled'), base, cand)
    rows = list(report.judgment_rows(cmp))
    line, text = report.outcome_line(cmp), report.render(cmp)
    assert len(rows) > 100
    assert len(line) < 1500
    assert str(len(rows)) in line
    for qid, doc, arm in rows:
        assert f'| {qid} | {doc} | {arm} |' in text
    assert f'qid={rows[0][0]}' in line
    assert f'qid={rows[-1][0]}' not in line


def test_killed_pausable_supervisor_reaps_worker_and_detached_engine(tmp_path):
    admission = index.REPO_ROOT/'scripts/dev/admission.py'
    env = dict(os.environ, JS_ADMISSION_DIR=str(tmp_path/'admission'))
    env.pop('JS_ADMISSION_TOKEN', None)
    script = '''
import os,subprocess,sys,time
from pathlib import Path
engine=subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)'],start_new_session=True)
Path(sys.argv[1]).write_text(str(os.getpid())+' '+str(engine.pid))
time.sleep(60)
'''
    proc = subprocess.Popen([sys.executable,str(admission),'pausable','--purpose','lifetime regression',
        '--pause-hook','true','--resume-hook','true','--',sys.executable,'-c',script,str(tmp_path/'pids')], env=env)
    pids = []
    def live(pid):
        try:
            return Path(f'/proc/{pid}/stat').read_text().rsplit(')',1)[1].split()[0] != 'Z'
        except (FileNotFoundError, ProcessLookupError):
            return False
    try:
        until = time.monotonic()+10
        while not (tmp_path/'pids').exists():
            assert proc.poll() is None
            assert time.monotonic() < until
            time.sleep(.02)
        pids = list(map(int, (tmp_path/'pids').read_text().split()))
        proc.kill()
        proc.wait(timeout=5)
        until = time.monotonic()+3
        while any(live(pid) for pid in pids) and time.monotonic()<until:
            time.sleep(.02)
        assert not any(live(pid) for pid in pids), 'worker or detached engine outlived admission owner'
        result = subprocess.run([sys.executable,str(admission),'exclusive','--purpose','waiting work',
            '--drain-timeout','.5','--timeout','2','--',sys.executable,'-c','pass'], env=env, capture_output=True, timeout=5)
        assert result.returncode == 0, result.stderr.decode()
    finally:
        if proc.poll() is None:
            proc.terminate()
            proc.wait(timeout=5)
        for pid in pids:
            if live(pid):
                os.kill(pid, signal.SIGKILL)


@pytest.mark.parametrize('failure', ['health', 'canary', 'content', 'queued-start'])
def test_background_adoption_quarantines_only_content_failure(tmp_path, monkeypatch, failure, serving_clock):
    from .test_index import env_for
    from jseval.quality.run import _serial_config
    c = make_collection(n_docs=4)
    root = tmp_path/'entry'
    data = root/'data'
    data.mkdir(parents=True)
    cfg = index.IndexConfig(repo_root=tmp_path, models_dir=tmp_path/'models', timeout_sec=30)
    owner = index._population_owner(c, 'key')
    index._write(data/'quality-owner.json', owner)
    entry = {'owner': owner, 'witness': {}}
    index._write(root/'entry.json', entry)
    index.write_collection(c, root/'collection')
    index._write(root/'build.json', {'config': _serial_config(cfg), 'env': env_for(cfg),
        'key': 'key', 'data': str(data), 'adopted': True, 'parent': index._process(os.getpid())})
    monkeypatch.setattr(index, '_materialize', lambda *a: c)
    monkeypatch.setattr(index, 'compute_index_key', lambda *a: ('key', {}))
    monkeypatch.setattr(backend, 'require_prepared', lambda *a: None)
    proc = Mock()
    proc.poll.return_value = None
    launch = Mock(return_value=backend.BackendInfo(proc, data))
    monkeypatch.setattr(backend, 'start_backend', launch)
    monkeypatch.setattr(backend, 'stop_backend', Mock())
    if failure == 'health':
        launch.side_effect = RuntimeError('health timeout')
    bad = (index_identity.ConfirmResult(False, ['canary.stage.cross-encoder: reason=DEADLINE_EXCEEDED'],
                                        {'canary': {'transient': True}}) if failure == 'canary' else
           index_identity.ConfirmResult(False, ['population.count mismatch']))
    confirmation = Mock(return_value=bad)
    if failure == 'queued-start':
        now = [100.0]
        monkeypatch.setattr(index.time, 'monotonic', lambda: now[0])
        def queued(**kwargs):
            now[0] += 120  # Outside the exclusive window; longer than readiness timeout.
            return backend.BackendInfo(proc, data)
        launch.side_effect = queued
        confirmation.return_value = index_identity.ConfirmResult(True, [])
        (root/'stop').touch()
    monkeypatch.setattr(index, '_confirm', confirmation)
    # On the old tree this is reached before confirmation; it must not become
    # the reason the regression test fails.
    monkeypatch.setattr(index, 'witness', lambda *a: {})
    if failure == 'queued-start':
        index._build(root)
        assert (root/'completed.json').exists()
    else:
        with pytest.raises(index.IndexRefused, match='not ready'):
            index._build(root)
    assert (root/'entry.json').exists() is (failure != 'content')
    assert data.exists() is (failure != 'content')
    if failure == 'health':
        assert launch.call_count == 3
    else:
        if failure == 'canary':
            assert serving_clock[0] == 130  # Full readiness deadline, not a retry count.
            assert confirmation.call_count > 3
        else:
            assert confirmation.call_count == 1
    assert not (root/'prepared.json').exists()
