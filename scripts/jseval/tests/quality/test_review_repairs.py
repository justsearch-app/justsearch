"""Core review regressions, including process ownership at the real flock boundary."""
from dataclasses import replace
import fcntl
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from unittest.mock import Mock

import pytest

from jseval import backend
from jseval.quality import collections, compare, index, run
from jseval.quality.types import Outcome
from .helpers import make_collection, make_run, config_for
from .test_index import collection, config, env_for


def test_preparation_confirmation_and_teardown_stay_admitted_and_reuse_engine(
        collection, config, tmp_path, monkeypatch):
    c, source = collection
    cfg = replace(config, repo_root=index.REPO_ROOT, documents_dir=source, cache_root=tmp_path/'cache')
    monkeypatch.setenv('JS_ADMISSION_DIR', str(tmp_path/'admission'))
    monkeypatch.delenv('JS_ADMISSION_TOKEN', raising=False)
    monkeypatch.setattr(index.IndexConfig, 'environment', lambda _: env_for(cfg))
    monkeypatch.setattr(index, '_under_cache', lambda p: p)
    # Boundaries use the real pausable supervisor and locks, with a tiny engine fixture.
    script = '''
import json,sys,subprocess
from pathlib import Path
from types import SimpleNamespace as NS
from jseval.quality import index as ix
root=Path(sys.argv[1])
admission=Path(sys.argv[2])
def admitted():
 rows=json.loads(subprocess.check_output([sys.executable,str(admission),'status']))
 assert any(r['mode']=='pausable' and r['state']=='working' for r in rows),rows
def event(name):
 admitted()
 with (root/'events').open('a') as out:out.write(name+'\\n')
def start(**kwargs):
 event('start')
 assert kwargs['startup_admission'] is False
 return NS(proc=NS(poll=lambda:None))
ix.backend.start_backend=start
ix.backend.require_prepared=lambda *a:None
ix.backend.stop_backend=lambda *a,**kw:event('stop')
ix.add_watched_root=lambda *a:ix._write(Path(ix._json(root/'build.json')['data'])/'watched_roots.json',{'roots':[{'path':'docs'}]})
ix.witness=lambda *a:{'probes':[{'query':'query words'}],'generation':'g','counts':{}}
ix._canary_query=lambda *a:'query words'
ix.index_identity.compute_live_identity=lambda *a,**kw:NS(to_doc=lambda:{})
def confirm(h,entry=None):
 event('confirm')
 h.index_state={'settled':True,'index_population':h.collection.index_population()}
 return NS(ok=True,failures=[])
ix._confirm=confirm
ix._build(root)
'''
    popen = subprocess.Popen
    admission = index.REPO_ROOT/'scripts/dev/admission.py'
    def launch(command, **kwargs):
        if 'pausable' in command:
            command = command[:-5] + [sys.executable, '-c', script, command[-1], str(admission)]
        return popen(command, **kwargs)
    monkeypatch.setattr(index.subprocess, 'Popen', launch)
    parent_start = Mock(side_effect=AssertionError('parent restarted the preparation engine outside admission'))
    monkeypatch.setattr(index.backend, 'start_backend', parent_start)
    root = None
    for mode in ('fresh', 'cached'):
        with index.prepare_index(c, cfg) as h:
            root = h.root
            assert h.index_build == mode
            h.wait(deadline=time.monotonic()+15)
            assert h.index_state['settled'] is True
            assert h._worker.poll() is None
        assert json.loads(subprocess.check_output([sys.executable, str(admission), 'status'])) == []
    assert (root/'events').read_text().splitlines() == ['start', 'confirm', 'stop'] * 2
    parent_start.assert_not_called()


def test_beir_source_is_materialized_with_verified_text(tmp_path):
    source = tmp_path/'source'
    (source/'qrels').mkdir(parents=True)
    (source/'corpus.jsonl').write_text('{"_id":"mail","title":"Subject","text":"Mail body"}\n')
    (source/'queries.jsonl').write_text('{"_id":"q","text":"which mail"}\n')
    (source/'qrels/test.tsv').write_text('q\tmail\t1\n')
    cfg = collections.load_config('enron-email')
    c = collections.build(cfg, path=source)
    out = tmp_path/'documents'
    assert collections.write_documents(c, out) == 1
    assert (out/c.documents['mail']).read_text() == 'Subject\n\nMail body'
    (source/'corpus.jsonl').write_text('{"_id":"mail","text":"changed"}\n')
    with pytest.raises(collections.CollectionError, match='content hash'):
        collections.write_documents(c, out)


@pytest.mark.parametrize('receipt_present', [True, False])
def test_teardown_failure_cannot_publish_preparation_success(tmp_path, monkeypatch, receipt_present):
    c = make_collection()
    index._write(tmp_path/'prepared.json', {'url': 'http://engine'})
    (tmp_path/'build.log').write_text('writer teardown failed')
    lock = (tmp_path/'data.quality.lock').open('a+')
    worker = Mock(returncode=1)
    worker.poll.return_value = 1
    monkeypatch.setattr(backend, 'stop_backend', lambda *a, **kw: None)
    h = index.IndexHandle(c, index.IndexConfig(), 'key', tmp_path, tmp_path/'data',
                          'fresh', {}, {}, lock, _worker=worker, _backend=Mock())
    if not receipt_present:
        # wait() already observed readiness; the failed worker's finally removed it.
        (tmp_path/'prepared.json').unlink()
    with pytest.raises(index.IndexRefused, match='teardown failed'):
        h.close()
    assert lock.closed


def test_cached_preparation_refreshes_distribution_after_query_only_edit(
        collection, config, tmp_path, monkeypatch):
    c, source = collection
    cfg = replace(config, documents_dir=source, cache_root=tmp_path/'cache')
    (cfg.repo_root/'.gitignore').write_text('build/\n')
    env = env_for(cfg)
    query_code = cfg.repo_root / next(p for p in index.QUERY_TIME_ONLY
                                    if p.endswith('KnowledgeSearchEngine.java'))
    query_code.parent.mkdir(parents=True)
    query_code.write_text('// original query code\n')
    monkeypatch.setattr(backend, 'source_identity', lambda _: ('commit', index._file_hash(query_code)))
    dist = cfg.repo_root/'modules/ui/build/install/ui'
    (dist/'bin').mkdir(parents=True)
    (dist/'bin/ui').touch()
    receipt = dist/'.jseval-source.json'
    index._write(receipt, list(backend.source_identity(cfg.repo_root)))
    key, _ = index.compute_index_key(c, cfg, env)
    root = tmp_path/'entry'
    data = root/'data'
    data.mkdir(parents=True)
    owner = index._population_owner(c, key)
    index._write(data/'quality-owner.json', owner)
    entry = {'owner': owner, 'witness': {'generation': 'original'}}
    index._write(root/'entry.json', entry)
    index.write_collection(c, root/'collection')
    from jseval.quality.run import _serial_config
    index._write(root/'build.json', {'config': _serial_config(cfg), 'env': env,
                                    'key': key, 'data': str(data), 'adopted': True,
                                    'parent': index._process(os.getpid())})
    query_code.write_text('// changed query code\n')
    assert index.compute_index_key(c, cfg, env)[0] == key
    with pytest.raises(RuntimeError, match='stale'):
        backend.require_prepared(cfg.repo_root)
    events = []
    def install(repo, launch_env):
        events.append('install')
        assert repo == cfg.repo_root
        assert launch_env['JUSTSEARCH_MODELS_DIR'] == str(cfg.models_dir)
        index._write(receipt, list(backend.source_identity(repo)))
    def start(**kwargs):
        events.append('start')
        backend.require_prepared(cfg.repo_root)
        assert kwargs['prepared_only'] is True
        assert kwargs['startup_admission'] is False
        return backend.BackendInfo(Mock(), data)
    monkeypatch.setattr(backend, '_install_distribution', install)
    monkeypatch.setattr(backend, 'start_backend', start)
    monkeypatch.setattr(backend, 'stop_backend', lambda *a, **kw: events.append('stop'))
    monkeypatch.setattr(index, 'add_watched_root', Mock(side_effect=AssertionError('cached index reingested')))
    monkeypatch.setattr(index, 'witness', lambda *a: entry['witness'])
    def confirm(handle, adopted_entry):
        events.append('confirm')
        assert adopted_entry == entry
        assert handle.index_build == 'cached'
        return index.index_identity.ConfirmResult(True, [], {})
    monkeypatch.setattr(index, '_confirm', confirm)
    (root/'stop').touch()
    index._build(root)
    assert events == ['install', 'start', 'confirm', 'stop']
    assert index._json(root/'entry.json') == entry
    assert not (root/'prepared.json').exists()


def test_unsettled_runs_cannot_pass_or_be_stored(tmp_path):
    c = make_collection()
    record = make_run(c, index_state={'settled': False})
    result = compare.compare(c, config_for(c), record, record)
    assert result.verdict.outcome is Outcome.INVALID
    assert any('settled' in reason for reason in result.verdict.reasons)
    record.write(tmp_path/'run.jsonl')
    with pytest.raises(compare.BaselineError, match='settled'):
        compare.write_baseline(tmp_path/'run.jsonl', c)


@pytest.mark.parametrize('substantive', [None, 'completeness', 'content'])
def test_intended_index_change_covers_only_derived_provenance(substantive):
    c = make_collection()
    before = make_run(c, index_key='old', index_state={
        'settled': True, 'index_cache_key': 'old', 'generation': 'old-gen',
        'completeness': [], 'content': dict(c.content)})
    state = {**before.header.index_state, 'index_cache_key': 'new', 'generation': 'new-gen'}
    if substantive:
        state[substantive] = ['changed'] if substantive == 'completeness' else {'wrong': 'content'}
    after = replace(before, header=replace(before.header, index_key='new', index_state=state))
    result = compare.compare(c, config_for(c), before, after, intended=('index_key',))
    assert result.verdict.outcome is (Outcome.INCONCLUSIVE if substantive else Outcome.PASS)
    if substantive:
        assert any(s.startswith(f'index_state.{substantive}:') for s in result.confounds)
    else:
        assert not result.confounds
    assert compare.compare(c, config_for(c), before, after).verdict.outcome is Outcome.INCONCLUSIVE


def test_default_suites_resolve_enron_materialized_source(tmp_path, monkeypatch):
    # Resolve every default alias through the actual dispatcher, without downloading datasets.
    monkeypatch.setattr(collections, 'build_ir_datasets', lambda cfg: cfg.collection)
    monkeypatch.setattr(collections, 'build_miracl_pool', lambda cfg: cfg.collection)
    from jseval import _paths
    monkeypatch.setattr(_paths, 'main_repo_root', lambda: tmp_path)
    materialized = tmp_path/'tmp/781-v2-datasets/mixed/en-email-enron-raw-1k-short-natural'
    (materialized/'qrels').mkdir(parents=True)
    (materialized/'corpus.jsonl').write_text('{"_id":"mail","text":"Enron mail"}\n')
    (materialized/'queries.jsonl').write_text('{"_id":"q","text":"which mail"}\n')
    (materialized/'qrels/test.tsv').write_text('q\tmail\t1\n')
    for ref in set(run.DEFAULT_COLLECTIONS + run.FULL_COLLECTIONS):
        c = collections.resolve_collection(ref)[0]
        if ref == 'enron-email':
            assert c.name == ref and c.documents == {'mail': 'docs/mail.txt'}
            assert c.metadata['source']['path'] == str(materialized)
        else:
            assert c == ref


def test_gradle_slot_keeps_its_own_machine_admission(tmp_path, monkeypatch):
    # Fake Gradle represents an existing daemon: pausing its controller cannot stop its work.
    from jseval import backend
    admission = index.REPO_ROOT/'scripts/dev/admission.py'
    root = tmp_path/'repo'
    root.mkdir()
    # This slot fixture intentionally has no admission. The checked-in invoker
    # must still protect the actual Gradle call, independent of installed wrapper version.
    slot = tmp_path/'slot'
    slot.write_text('#!/bin/sh\nshift 2\nexec "$@"\n')
    slot.chmod(0o755)
    (root/'scripts/lib').mkdir(parents=True)
    (root/'scripts/dev').mkdir(parents=True)
    import shutil
    shutil.copyfile(index.REPO_ROOT/'scripts/lib/tool-invoker.cjs', root/'scripts/lib/tool-invoker.cjs')
    shutil.copyfile(admission, root/'scripts/dev/admission.py')
    wrapper = root/'gradlew'
    wrapper.write_text('#!/bin/sh\ntouch running\nwhile [ ! -f release ]; do sleep .05; done\n')
    wrapper.chmod(0o755)
    monkeypatch.setenv('JS_ADMISSION_DIR', str(tmp_path/'admission'))
    monkeypatch.setenv('JS_GRADLE_SLOT_DIR', str(tmp_path/'slots'))
    monkeypatch.delenv('JS_ADMISSION_TOKEN', raising=False)
    env = dict(os.environ, JSEVAL_GRADLE_SLOT=str(slot))
    script = 'from pathlib import Path; from jseval.backend import _install_distribution; import os; _install_distribution(Path(os.environ["BUILD_ROOT"]), dict(os.environ))'
    proc = subprocess.Popen([sys.executable, '-c', script], env={**env, 'BUILD_ROOT': str(root)})
    try:
        until = time.monotonic()+10
        while not (root/'running').exists():
            assert proc.poll() is None
            assert time.monotonic() < until
            time.sleep(.02)
        rows = json.loads(subprocess.check_output([sys.executable, str(admission), 'status'], env=env))
        assert any(row['mode'] == 'heavy' for row in rows), rows
        refused = subprocess.run([sys.executable, str(admission), 'exclusive', '--purpose', 'build overlap regression',
            '--drain-timeout', '.1', '--timeout', '5', '--', sys.executable, '-c', 'pass'], env=env, capture_output=True)
        assert refused.returncode == 75, refused.stderr.decode()
    finally:
        (root/'release').touch()
        proc.wait(timeout=10)


def test_data_lock_survives_abrupt_preparer_death(tmp_path, monkeypatch):
    # Use prepare_index's actual Popen kwargs and a real pausable supervisor.
    script = '''
import os,sys,subprocess,time
from pathlib import Path
from jseval.quality import index,collections
root=Path(sys.argv[1])
c=collections.load_collection(root/'collection')
index.IndexConfig.environment=lambda _: {}
index.compute_index_key=lambda *a: ('key',{})
index._under_cache=lambda p:p
original=subprocess.Popen
admission=Path(index.__file__).resolve().parents[3]/'dev/admission.py'
os.environ['JS_ADMISSION_DIR']=str(root/'admission')
os.environ.pop('JS_ADMISSION_TOKEN',None)
def launch(command,**kw):
 return original([sys.executable,str(admission),'pausable','--purpose','caller death fixture','--pause-hook','true','--resume-hook','true','--',sys.executable,'-c',"import time; from pathlib import Path; Path('writer-ready').touch(); time.sleep(2); Path('writer-done').touch()"],**kw)
index.subprocess.Popen=launch
h=index.prepare_index(c,index.IndexConfig(repo_root=root,cache_root=root/'cache'))
(root/'pid').write_text(str(h._worker.pid))
while not (root/'writer-ready').exists():time.sleep(.01)
os._exit(0)
'''
    c = make_collection()
    collections.write_collection(c, tmp_path/'collection')
    subprocess.run([sys.executable, '-c', script, str(tmp_path)], check=True, timeout=10)
    monkeypatch.setattr(index.IndexConfig, 'environment', lambda _: {})
    monkeypatch.setattr(index, 'compute_index_key', lambda *a: ('key', {}))
    monkeypatch.setattr(index, '_under_cache', lambda p: p)
    cfg = index.IndexConfig(repo_root=tmp_path, cache_root=tmp_path/'cache')
    try:
        with pytest.raises(BlockingIOError):
            index.prepare_index(c, cfg)
    finally:
        until = time.monotonic()+5
        while not (tmp_path/'writer-done').exists():
            assert time.monotonic() < until
            time.sleep(.02)
        lock_path = next((tmp_path/'cache').glob('*/*/data.quality.lock'))
        with lock_path.open('a+') as lock:
            while True:
                try:
                    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    break
                except BlockingIOError:
                    assert time.monotonic() < until
                    time.sleep(.02)
    # Writer teardown released the lock, so waiting preparation can proceed.
    spawn = Mock()
    spawn.return_value.poll.return_value = 0
    monkeypatch.setattr(index.subprocess, 'Popen', spawn)
    with index.prepare_index(c, cfg):
        pass


@pytest.mark.parametrize('distribution_missing', [False, True])
def test_owned_startup_failure_preserves_index_on_health_failure(tmp_path, monkeypatch, distribution_missing):
    c = make_collection()
    root = tmp_path/'entry'
    data = root/'data'
    data.mkdir(parents=True)
    owner = index._population_owner(c, 'key')
    index._write(data/'quality-owner.json', owner)
    index._write(root/'entry.json', {'owner': owner})
    lock = (root/'data.quality.lock').open('a+')
    h = index.IndexHandle(c, index.IndexConfig(), 'key', root, data, 'cached', {}, {}, lock)
    missing = RuntimeError('not ready: install distribution missing')
    monkeypatch.setattr(backend, 'require_prepared', Mock(side_effect=missing if distribution_missing else None))
    monkeypatch.setattr(backend, 'start_backend', Mock(side_effect=RuntimeError('engine failed health')))
    with pytest.raises((RuntimeError, index.IndexRefused)):
        h.wait(prepared_only=True)
    assert (root/'entry.json').exists()
    assert lock.closed
