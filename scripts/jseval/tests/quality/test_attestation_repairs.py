"""Review regressions at distribution admission and live cache confirmation."""
import contextlib
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
from jseval.quality import index
from .test_redesign import cached_handle


@pytest.mark.parametrize('termination', [signal.SIGTERM, signal.SIGKILL])
def test_interrupted_install_cannot_keep_old_receipt(tmp_path, monkeypatch, termination):
    dist = tmp_path/'modules/ui/build/install/ui'
    (dist/'bin').mkdir(parents=True)
    (dist/'bin/ui').touch()
    (tmp_path/'.git').mkdir()
    (dist/'.jseval-source.json').write_text(json.dumps(['candidate', None]))
    monkeypatch.setattr(backend, 'source_identity', lambda _: ('candidate', None))
    backend.require_prepared(tmp_path)
    script = '''
import os,time
from pathlib import Path
from jseval import backend
root=Path(os.environ['BUILD_ROOT'])
backend.source_identity=lambda _: ('candidate',None)
def build(*a,**kw):
 (root/'modules/ui/build/install/ui/bin/ui').write_text('partially rewritten')
 (root/'writing').touch()
 while True:time.sleep(.05)
backend.subprocess.run=build
backend._install_distribution(root,dict(os.environ))
'''
    env = {**os.environ, 'JS_ADMISSION_DIR': str(tmp_path/'admission'), 'BUILD_ROOT': str(tmp_path)}
    env.pop('JS_ADMISSION_TOKEN', None)
    proc = subprocess.Popen([sys.executable, '-c', script], env=env)
    try:
        until = time.monotonic()+10
        while not (tmp_path/'writing').exists():
            assert proc.poll() is None
            assert time.monotonic() < until
            time.sleep(.02)
        proc.send_signal(termination)
        proc.wait(timeout=5)
        with pytest.raises(RuntimeError, match='not ready.*identity'):
            backend.require_prepared(tmp_path)
    finally:
        if proc.poll() is None:
            proc.kill()
            proc.wait(timeout=5)


def test_receipt_changes_only_inside_build_admission(tmp_path, monkeypatch):
    dist = tmp_path/'modules/ui/build/install/ui'
    (dist/'bin').mkdir(parents=True)
    (dist/'bin/ui').touch()
    (tmp_path/'.git').mkdir()
    receipt = dist/'.jseval-source.json'
    receipt.write_text('["old", null]')
    monkeypatch.setattr(backend, 'source_identity', lambda _: ('new', None))
    @contextlib.contextmanager
    def admission(env):
        assert json.loads(receipt.read_text()) == ['old', None]
        yield {**env, 'ADMITTED_BUILD': 'yes'}
        assert json.loads(receipt.read_text()) == ['new', None]
    monkeypatch.setattr(backend, '_machine_admission', admission)
    def build(*a, **kw):
        assert not receipt.exists()
        assert kw['env']['ADMITTED_BUILD'] == 'yes'
        return subprocess.CompletedProcess([], 0)
    monkeypatch.setattr(backend.subprocess, 'run', build)
    backend._install_distribution(tmp_path, {})


def test_refused_gradle_slot_preserves_prepared_distribution(tmp_path, monkeypatch):
    dist = tmp_path/'modules/ui/build/install/ui'
    (dist/'bin').mkdir(parents=True)
    (dist/'bin/ui').touch()
    (dist/'.jseval-source.json').write_text(json.dumps(['candidate', None]))
    monkeypatch.setattr(backend, 'source_identity', lambda _: ('candidate', None))
    monkeypatch.setattr(backend.subprocess, 'run', Mock(return_value=subprocess.CompletedProcess([], 75)))
    with pytest.raises(RuntimeError, match='installDist failed.*75'):
        backend._install_distribution(tmp_path, {'JSEVAL_GRADLE_SLOT': 'refused-slot'})
    backend.require_prepared(tmp_path)


@pytest.mark.parametrize('observation', ['unknown', 'stale', 'worker-stale', 'absent', 'fresh'])
def test_population_mismatch_requires_known_fresh_count(tmp_path, monkeypatch, observation):
    h = cached_handle(tmp_path, monkeypatch)
    status = {'indexState': 'IDLE', 'indexedDocuments': 0,
              'indexedDocumentsUnknown': observation == 'unknown',
              'countObservations': {'active': {'state': 'stale' if observation == 'stale' else 'fresh'}},
              'meta': {'workerRpcStale': observation == 'worker-stale'}}
    if observation == 'absent':
        status.pop('indexedDocumentsUnknown')
    _wire_confirmation(h, status, monkeypatch)
    with pytest.raises(index.IndexRefused, match='not ready'):
        h.wait(prepared_only=True, deadline=time.monotonic()+.05)
    assert (h.root/'entry.json').exists() is (observation != 'fresh')
    assert (h.root/'quarantine').exists() is (observation == 'fresh')


def _wire_confirmation(h, status, monkeypatch):
    client_class = httpx.Client
    def transport(request):
        assert request.url.path == '/api/status'
        return httpx.Response(200, json=status)
    monkeypatch.setattr(index.httpx, 'Client', lambda **kw:
        client_class(transport=httpx.MockTransport(transport), **kw))
    monkeypatch.setattr(index, 'compute_index_key', lambda *a: ('key', {}))
    monkeypatch.setattr(index, '_materialize', lambda *a: h.collection)
    monkeypatch.setattr(index_identity, 'confirm_adoption', Mock(return_value=
        index_identity.ConfirmResult(True, [], {})))
    index._write(h.root/'entry.json', {'owner': index._population_owner(h.collection, 'key'), 'witness': {}})


@pytest.mark.parametrize('observation', ['failed', 'pending', 'unavailable', 'stale', 'busy',
                                       'disabled', 'queued', 'count-stale', 'unknown-count'])
def test_known_failed_content_audit_quarantines_only_when_settled(tmp_path, monkeypatch, observation):
    h = cached_handle(tmp_path, monkeypatch)
    status = {'indexState': 'BUILDING' if observation == 'busy' else 'IDLE',
              'indexedDocuments': len(h.collection.documents),
              'indexedDocumentsUnknown': observation == 'unknown-count',
              'countObservations': {'active': {'state': 'stale' if observation == 'count-stale' else 'fresh'}},
              'pendingJobs': 1 if observation == 'queued' else 0,
              'embeddingEnabled': observation != 'disabled', 'spladeEnabled': False,
              'chunkSpladeEnabled': False, 'nerEnabled': False, 'failedNerCount': 0,
              'meta': {'workerRpcStale': observation == 'stale'},
              'completenessKnown': observation != 'unavailable',
              'completeness': [{'stageId': 'embed', 'missing': 1,
                                'failed': 0 if observation == 'pending' else 1}]}
    if observation == 'disabled':
        status['spladeEnabled'] = True  # Refuse configuration readiness, not disabled embed content.
    _wire_confirmation(h, status, monkeypatch)
    with pytest.raises(index.IndexRefused, match='not ready'):
        h.wait(prepared_only=True, deadline=time.monotonic()+.05)
    assert (h.root/'entry.json').exists() is (observation != 'failed')
    assert (h.root/'quarantine').exists() is (observation == 'failed')
