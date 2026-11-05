"""Scoring-only arms: isolated indexes and HTTP/process fakes; never a real Engine.

Real-device placement, setting-caused writes and GPU/CPU/GPU residue still need
the orchestrator's Engine run. These tests exercise the tool's real key, adoption,
confirmation, publication and reload paths around those boundaries.
"""
from dataclasses import replace
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from types import SimpleNamespace
from unittest.mock import Mock

from click.testing import CliRunner
import httpx
import pytest

from jseval import backend, index_identity
from jseval.cli import main
from jseval.quality import collections, compare, index, inputs, report, run, sensitivity
from jseval.quality.types import Outcome, QueryResult, RunRecord, Served, sha256_text
from .conftest import pinned_env, ready_distribution
from .helpers import config_for, make_collection, make_run
from .test_report_and_cli import stored


ARMS = [{name: value} for name, value in (
    ('JUSTSEARCH_RERANK_ENABLED', 'false'),
    ('JUSTSEARCH_RERANK_GPU_ENABLED', 'false'),
    ('JUSTSEARCH_RERANK_TOP_K', '7'),
    ('JUSTSEARCH_EMBED_GPU_ENABLED', 'false'),
    ('JUSTSEARCH_ORT_INTRA_OP_THREADS', '4'))]
LAPTOP = {'JUSTSEARCH_RERANK_ENABLED': 'true', 'JUSTSEARCH_RERANK_GPU_ENABLED': 'false',
          'JUSTSEARCH_EMBED_GPU_ENABLED': 'false', 'JUSTSEARCH_ORT_INTRA_OP_THREADS': '4'}


def complete_quality_status(count=4):
    return {'indexState': 'IDLE', 'pendingJobs': 0, 'pendingJobsCount': 0,
            'processingJobsCount': 0, 'indexedDocuments': count,
            'embeddingEnabled': True, 'embeddingPendingCount': 0,
            'embeddingCoveragePercent': 100, 'chunkDocCount': count,
            'chunkEmbeddingPendingCount': 0, 'chunkVectorCoveragePercent': 100,
            'nerEnabled': True, 'completedNerCount': count, 'pendingNerCount': 0,
            'failedNerCount': 0, 'spladeEnabled': False, 'spladePendingCount': count,
            'chunkSpladeEnabled': False, 'chunkSpladePendingCount': count,
            'completenessKnown': True,
            'completeness': [{'stageId': stage, 'missing': 0, 'failed': 0}
                             for stage in ('embed', 'ner', 'chunk_embed')]}


def labelled(c, *, sample=None, seed=0, **kw):
    arm = sensitivity.declaration(c, {}, None, sample, seed)
    record = make_run(c, run_kind='sensitivity', sensitivity=arm, **kw)
    return replace(record, results=tuple(q for q in record.results if q.qid in arm['queries']))


@pytest.mark.parametrize('pair', ['PATH=x', 'JUSTSEARCH_UNKNOWN=x', 'JUSTSEARCH_DATA_DIR=x',
    'JUSTSEARCH_RERANK_ENABLED', 'JUSTSEARCH_RERANK_ENABLED=',
    'JUSTSEARCH_RERANK_ENABLED=false=x', '=false', 'JUSTSEARCH_RERANK_ENABLED=0',
    'JUSTSEARCH_ORT_INTRA_OP_THREADS=0', 'JUSTSEARCH_RERANK_TOP_K=-1'])
def test_set_refuses_before_engine_or_admission(monkeypatch, pair):
    launch = Mock(side_effect=AssertionError('no process before validation'))
    monkeypatch.setattr(run.subprocess, 'Popen', launch)
    result = CliRunner().invoke(main, ['quality', 'sensitivity', 'scifact', '--set', pair])
    assert result.exit_code == 2 and pair.split('=')[0] in result.output, result.output
    launch.assert_not_called()


def test_cli_options_and_label(monkeypatch):
    cli = CliRunner()
    help_result = cli.invoke(main, ['quality', 'sensitivity', '--help'])
    assert help_result.exit_code == 0
    for option in ('--set', '--cpus', '--sample', '--seed', '--window-seconds'):
        assert option in help_result.output
    execute = Mock(return_value=(Outcome.SENSITIVITY, 'SENSITIVITY fixture'))
    monkeypatch.setattr(run, 'execute', execute)
    result = cli.invoke(main, ['quality', 'sensitivity', 'scifact', '--set',
        'JUSTSEARCH_RERANK_ENABLED=false', '--cpus', '2-5', '--sample', '10', '--seed', '8'])
    assert result.exit_code == 3 and result.output == 'SENSITIVITY fixture\n'
    assert execute.call_args.args == ('sensitivity', ('scifact',))
    assert execute.call_args.kwargs['sets'] == ('JUSTSEARCH_RERANK_ENABLED=false',)
    for action in ('check', 'baseline', 'ablate', 'full-suite', 'prepare'):
        assert cli.invoke(main, ['quality', action, '--set', 'JUSTSEARCH_RERANK_ENABLED=false']).exit_code == 2


@pytest.mark.parametrize('overrides', ARMS + [LAPTOP])
def test_allowlisted_readback_and_core_refusals(overrides, monkeypatch):
    assert sensitivity.settings([f'{k}={v}' for k, v in overrides.items()]) == overrides
    prepared = {'JUSTSEARCH_GPU_ENABLED': 'false', 'JUSTSEARCH_RERANK_GPU_ENABLED': 'false'}
    launched = sensitivity.launch_environment(prepared, overrides)
    assert prepared == {'JUSTSEARCH_GPU_ENABLED': 'false', 'JUSTSEARCH_RERANK_GPU_ENABLED': 'false'}
    assert all(launched[k] == v for k, v in overrides.items())
    resolved = {sensitivity.ALLOWLIST[k]: v for k, v in overrides.items()}
    resolved.setdefault('justsearch.rerank.enabled', 'true')
    responses = {
        '/api/debug/effective-config': {'resolvedConfig': [{'key': k, 'value': v} for k, v in resolved.items()]},
        '/api/debug/session-policies': {'runtime': {'session': {'intraOpThreads': 4}},
            'models': {'RERANKER': {'variant': {'modelFile': '/secret/model.onnx', 'precision': 'FP32'}}}},
        '/api/inference/encoders': {'encoders': {'embed': {'currentAccelerator': 'cpu'},
                                               'reranker': {'currentAccelerator': 'cpu'}}}}
    client = httpx.Client
    monkeypatch.setattr(httpx, 'Client', lambda **kw: client(**kw,
        transport=httpx.MockTransport(lambda req: httpx.Response(200, json=responses[req.url.path]))))
    actual = sensitivity.effective_settings('http://engine', overrides)
    assert actual['query_embedder_execution_provider'] == 'cpu'
    assert actual['reranker_execution_provider'] == 'cpu'
    assert actual['ort_intra_op_threads'] == 4 and actual['reranker_precision'] == 'FP32'
    assert '/secret/' not in json.dumps(actual)
    assert actual['reranker_file'] == {'sha256': sha256_text('/secret/model.onnx')}
    for k in overrides:
        responses['/api/debug/effective-config']['resolvedConfig'] = []
        with pytest.raises(sensitivity.SensitivityRefused, match=k):
            sensitivity.effective_settings('http://engine', {k: overrides[k]})


@pytest.mark.parametrize('name', ['JUSTSEARCH_RERANK_GPU_ENABLED', 'JUSTSEARCH_EMBED_GPU_ENABLED'])
def test_gpu_overlay_still_requires_pinned_natives(name):
    with pytest.raises(sensitivity.SensitivityRefused, match='JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH'):
        sensitivity.launch_environment({'JUSTSEARCH_GPU_ENABLED': 'false'}, {name: 'true'})


@pytest.mark.parametrize('name', inputs.JVM_OPTION_VARIABLES)
def test_higher_priority_jvm_options_refuse(monkeypatch, name):
    monkeypatch.setenv(name, '-Djustsearch.rerank.enabled=true')
    with pytest.raises(sensitivity.SensitivityRefused, match=name):
        sensitivity.settings(['JUSTSEARCH_RERANK_ENABLED=false'])


def test_unreported_engine_settings_have_reasons(monkeypatch):
    client = httpx.Client
    monkeypatch.setattr(httpx, 'Client', lambda **kw: client(**kw,
        transport=httpx.MockTransport(lambda _: httpx.Response(200, json={}))))
    row = sensitivity.effective_settings('http://engine', {})
    assert all(v['value'] == 'unreported' and v['reason'] for v in row.values())


def test_cpu_list_is_allowed_and_exact(monkeypatch):
    monkeypatch.setattr(os, 'sched_getaffinity', lambda _: {2, 3, 4, 5})
    assert sensitivity.cpus('2-4,5,2') == [2, 3, 4, 5]
    for bad in ('0-3', '5-2', '', '2,,3', '-1', '2.0', '2-999999'):
        with pytest.raises(sensitivity.SensitivityRefused, match='--cpus'):
            sensitivity.cpus(bad)
    set_affinity = Mock()
    monkeypatch.setattr(os, 'sched_setaffinity', set_affinity)
    sensitivity.pin_cpus([2, 3, 4, 5])
    set_affinity.assert_called_once_with(0, [2, 3, 4, 5])
    with pytest.raises(sensitivity.SensitivityRefused, match='differs'):
        sensitivity.pin_cpus([2])
    java = Mock(pid=20); java.name.return_value = 'java'; java.cpu_affinity.return_value = [2, 3, 4, 5]
    parent = Mock(pid=10); parent.name.return_value = 'node'; parent.children.return_value = [java]
    parent.cpu_affinity.return_value = [2, 3, 4, 5]
    for process in (parent, java):
        process.threads.return_value = [SimpleNamespace(id=process.pid)]
    read_text = Path.read_text
    monkeypatch.setattr(Path, 'read_text', lambda path, *a, **kw:
        'Cpus_allowed_list:\t2-5\n' if str(path) in (
            '/proc/10/task/10/status', '/proc/20/task/20/status') else read_text(path, *a, **kw))
    monkeypatch.setattr(sensitivity.psutil, 'Process', lambda _: parent)
    assert sensitivity.engine_cpus(Mock(), [2, 3, 4, 5]) == [2, 3, 4, 5]
    java.cpu_affinity.return_value = [2]
    with pytest.raises(sensitivity.SensitivityRefused, match='differs'):
        sensitivity.engine_cpus(Mock(), [2, 3, 4, 5])
    parent.children.return_value = []
    with pytest.raises(sensitivity.SensitivityRefused, match='Engine JVM'):
        sensitivity.engine_cpus(Mock(), None)


def test_cpu_pin_inherits_in_real_child_without_touching_parent():
    allowed = sorted(os.sched_getaffinity(0))[:4]
    # Pin an isolated Python worker, which starts another process, just as the
    # scoring worker starts the backend. No machine-wide affinity is changed.
    code = ('import json, subprocess, sys; from jseval.quality.sensitivity import pin_cpus; '
            f'pin_cpus({allowed!r}); '
            'subprocess.run([sys.executable,"-c","import os,json; print(json.dumps(sorted(os.sched_getaffinity(0))))"],check=True)')
    result = subprocess.run([sys.executable, '-c', code], capture_output=True, text=True, check=True)
    assert json.loads(result.stdout) == allowed


@pytest.mark.parametrize('overrides', ARMS + [LAPTOP])
def test_prepared_key_adoption_postscore_and_residue(tmp_path, monkeypatch, overrides):
    c = make_collection({'en/vague': 4})
    models = tmp_path/'models'; models.mkdir(); (models/'model.onnx').write_bytes(b'model')
    source = tmp_path/'source'
    for doc, rel in c.documents.items():
        path = source/rel; path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(f'text of {doc}')
    cfg = index.IndexConfig(cache_root=tmp_path/'cache', models_dir=models, documents_dir=source)
    env = pinned_env(cfg)
    monkeypatch.setattr(index.IndexConfig, 'environment', lambda _: dict(env))
    monkeypatch.setattr(index, '_under_cache', lambda p: p)
    monkeypatch.setattr(backend, 'require_prepared_data', lambda _: None)
    key, components = index.compute_index_key(c, cfg, env)
    root = cfg.cache_root/sha256_text(c.name)/key; data = root/'data'; data.mkdir(parents=True)
    owner = index._population_owner(c, key)
    index._write(data/'quality-owner.json', owner)
    materialized = index._materialize(c, cfg, root)
    witness = {'index_population': c.index_population(), 'content': dict(c.content),
               'extracted_content': dict(c.content), 'samples': {}, 'probes': [],
               'generation': 'native', 'completeness': []}
    index._write(root/'entry.json', {'owner': owner, 'witness': witness, 'identity': {'key': 'live'}})
    original = {p.relative_to(root): p.read_bytes() for p in root.rglob('*') if p.is_file()}
    launches, confirmations = [], []
    proc = Mock(); proc.poll.return_value = None
    def start(config, path, launched, *a, **kw):
        assert kw == {'prepared_only': True, 'startup_admission': False}
        launches.append(dict(launched))
        return backend.BackendInfo(proc, data)
    monkeypatch.setattr(index, '_start_serving', start)
    monkeypatch.setattr(backend, 'stop_backend', Mock())
    monkeypatch.setattr(index, 'witness', lambda *a: witness)
    monkeypatch.setattr(index, '_materialize', lambda *a: materialized)
    monkeypatch.setattr(index, '_get', lambda *a: complete_quality_status(len(c.documents)))
    monkeypatch.setattr(sensitivity, 'confirm_identity', lambda h, entry:
        confirmations.append(index.compute_index_key(c, cfg, h.env)[0]) or index_identity.ConfirmResult(True, [], {}))
    normal = Mock(return_value=index_identity.ConfirmResult(True, [], {}))
    monkeypatch.setattr(index_identity, 'confirm_adoption', normal)
    # Normal -> labelled arm -> normal, on the same owned index and same key.
    for arm in (None, overrides, None):
        with index.prepare_index(c, cfg, prepared_only=True, sensitivity=arm) as h:
            assert h.index_key == key and h.components == components and h.env == env
            h.wait(prepared_only=True, deadline=time.monotonic()+5)
            assert index.confirm(h).ok
            assert h.index_key == index.compute_index_key(c, cfg, h.env)[0] == key
    assert confirmations == [key, key]
    assert normal.call_count == 4  # labelled wait and post-score never use the CE canary
    assert launches == [env, env, {**env, **overrides}, env]
    assert all((root/p).read_bytes() == value for p, value in original.items())
    assert (data/'quality-owner.json').read_bytes() == original[Path('data/quality-owner.json')]
    assert not (root/'quarantine').exists()


def test_inherited_unknown_and_threads_stay_keyed(tmp_path):
    models = tmp_path/'models'; models.mkdir(); (models/'model.onnx').write_bytes(b'model')
    cfg = index.IndexConfig(models_dir=models)
    c = make_collection()
    env = pinned_env(cfg)
    key = index.compute_index_key(c, cfg, env)[0]
    for name in ('JUSTSEARCH_FUTURE_UNKNOWN', 'JUSTSEARCH_ORT_INTRA_OP_THREADS'):
        assert index.compute_index_key(c, cfg, {**env, name: '4'})[0] != key


@pytest.mark.parametrize('failure', ['pending', 'identity', 'witness'])
def test_adoption_refusal_never_quarantines(tmp_path, monkeypatch, failure):
    from .test_redesign import cached_handle
    h = cached_handle(tmp_path, monkeypatch)
    h._sensitivity = {'JUSTSEARCH_RERANK_ENABLED': 'false'}
    h.env['JUSTSEARCH_GPU_ENABLED'] = 'false'
    index._write(h.root/'entry.json', {'owner': index._population_owner(h.collection, 'key'), 'witness': {}})
    monkeypatch.setattr(index, 'compute_index_key', lambda *a: ('key', {}))
    monkeypatch.setattr(index, '_materialize', lambda *a: h.collection)
    # Readiness polling is covered separately; isolate the strict idle/identity gates.
    monkeypatch.setattr(index, '_ready_reasons', lambda *a: [])
    monkeypatch.setattr(index, '_get', lambda *a: {**complete_quality_status(len(h.collection.documents)),
        'pendingJobs': int(failure == 'pending')})
    monkeypatch.setattr(index, 'witness', Mock(side_effect=ValueError('witness changed')) if failure == 'witness' else lambda *a: {})
    monkeypatch.setattr(index, '_adoption_agreement', lambda *a: [])
    monkeypatch.setattr(sensitivity, 'confirm_identity', lambda *a: index_identity.ConfirmResult(False, ['identity changed'], {}))
    quarantine = Mock(side_effect=AssertionError('sensitivity must never quarantine'))
    monkeypatch.setattr(index, '_quarantine', quarantine)
    monkeypatch.setattr(index_identity, 'confirm_adoption', Mock(side_effect=AssertionError('no CE canary')))
    with pytest.raises(index.IndexRefused, match='not ready'):
        h.wait(prepared_only=True, deadline=time.monotonic()+5)
    quarantine.assert_not_called()
    assert (h.root/'entry.json').exists() and h._lock.closed


@pytest.mark.parametrize('field', ['pendingJobs', 'pendingJobsCount', 'processingJobsCount',
    'embeddingPendingCount', 'pendingNerCount', 'chunkEmbeddingPendingCount', 'spladePendingCount', 'chunkSpladePendingCount'])
def test_every_pending_stage_refuses(field):
    status = {**complete_quality_status(), 'spladeEnabled': True, 'chunkSpladeEnabled': True,
              'spladePendingCount': 0, 'chunkSpladePendingCount': 0}
    sensitivity.require_idle(status)
    with pytest.raises(sensitivity.SensitivityRefused, match='pending'):
        sensitivity.require_idle({**status, field: 1})


@pytest.mark.parametrize('nested', [False, True])
def test_disabled_sparse_stages_do_not_refuse_complete_quality_index(nested):
    collection = make_collection({'en/vague': 4})
    status = complete_quality_status(len(collection.documents))
    assert index._ready_reasons(status, collection, False) == []
    if nested:
        status = {'worker': {'enrichment': status}}
    sensitivity.require_idle(status)


@pytest.mark.parametrize('stage,flags,pending', [
    ('embed', {'embeddingEnabled': False}, 'embeddingPendingCount'),
    ('ner', {'nerEnabled': False}, 'pendingNerCount'),
    ('chunk_embed', {'embeddingEnabled': False}, 'chunkEmbeddingPendingCount'),
    ('chunk_embed', {'chunkDocCount': 0}, 'chunkEmbeddingPendingCount'),
    ('splade', {}, 'spladePendingCount'),
    ('chunk_splade', {}, 'chunkSpladePendingCount'),
])
def test_disabled_stage_counts_and_audits_do_not_refuse(stage, flags, pending):
    status = {**complete_quality_status(), **flags, pending: 4}
    status['completeness'].append({'stageId': stage, 'missing': 4, 'failed': 1})
    sensitivity.require_idle(status)
    # A disabled stage must not hide work (or missing evidence) in enabled NER/embed.
    enabled_pending = 'embeddingPendingCount' if stage == 'ner' else 'pendingNerCount'
    for value in (1, None):
        with pytest.raises(sensitivity.SensitivityRefused, match='pending'):
            sensitivity.require_idle({**status, enabled_pending: value})


@pytest.mark.parametrize('stage,flags,pending', [
    ('embed', {}, 'embeddingPendingCount'), ('ner', {}, 'pendingNerCount'),
    ('chunk_embed', {}, 'chunkEmbeddingPendingCount'),
    ('splade', {'spladeEnabled': True}, 'spladePendingCount'),
    ('chunk_splade', {'chunkSpladeEnabled': True}, 'chunkSpladePendingCount'),
])
def test_enabled_stage_requires_zero_count_and_complete_audit(stage, flags, pending):
    status = {**complete_quality_status(), **flags, pending: 0}
    status.update(spladePendingCount=0, chunkSpladePendingCount=0)
    sensitivity.require_idle(status)
    for value in (1, None):
        with pytest.raises(sensitivity.SensitivityRefused, match='pending'):
            sensitivity.require_idle({**status, pending: value})
    del status[pending]
    with pytest.raises(sensitivity.SensitivityRefused, match='pending'):
        sensitivity.require_idle(status)
    status[pending] = 0
    for field in ('missing', 'failed'):
        status['completeness'] = [{'stageId': stage, field: 1}]
        with pytest.raises(sensitivity.SensitivityRefused, match='enrichment'):
            sensitivity.require_idle(status)


@pytest.mark.parametrize('recovers', [True, False])
def test_prepared_settings_wait_for_resolved_readiness(tmp_path, monkeypatch, recovers):
    from .test_redesign import cached_handle
    h = cached_handle(tmp_path, monkeypatch)
    h.env['JUSTSEARCH_GPU_ENABLED'] = 'false'
    h._sensitivity = {'JUSTSEARCH_RERANK_ENABLED': 'false'}
    now = [100.0]
    monkeypatch.setattr(time, 'monotonic', lambda: now[0])
    monkeypatch.setattr(time, 'sleep', lambda seconds: now.__setitem__(0, now[0]+seconds))
    ready = complete_quality_status(len(h.collection.documents))
    initial = {'indexState': 'IDLE', 'spladeEnabled': True, 'completenessKnown': False}
    observations, launches = [], []
    client_class = httpx.Client
    def transport(request):
        assert request.url.path == '/api/status'
        status = ready if recovers and observations else initial
        observations.append(status)
        return httpx.Response(200, json=status)
    monkeypatch.setattr(httpx, 'Client', lambda **kw:
        client_class(**kw, transport=httpx.MockTransport(transport)))
    def start(config, data, env, *a, **kw):
        if launches:
            assert recovers and observations == [initial, ready]
        launches.append(dict(env))
        return backend.BackendInfo(Mock(), data)
    monkeypatch.setattr(index, '_start_serving', start)
    monkeypatch.setattr(index, '_confirm_ready', lambda *a, **kw: index_identity.ConfirmResult(True, [], {}))
    quarantine = Mock(side_effect=AssertionError('no sensitivity quarantine'))
    monkeypatch.setattr(index, '_quarantine', quarantine)
    monkeypatch.setattr(index_identity, '_confirm_canary', Mock(side_effect=AssertionError('no CE canary')))
    try:
        if recovers:
            assert h.wait(prepared_only=True, deadline=105) is h
            assert launches == [h.env, {**h.env, **h._sensitivity}]
        else:
            with pytest.raises(index.TransientIndexRefused, match='deadline'):
                h.wait(prepared_only=True, deadline=105)
            assert launches == [h.env] and h._lock.closed
        quarantine.assert_not_called()
        assert (h.root/'entry.json').exists()
    finally:
        h.close()


def test_scoring_readiness_precedes_idle_gate(tmp_path, monkeypatch):
    from .test_redesign import cached_handle
    h = cached_handle(tmp_path, monkeypatch)
    h._backend = backend.BackendInfo(Mock(), h.data_dir)
    h._backend.proc.poll.return_value = None
    h.base_url = 'http://engine'
    h._sensitivity = {'JUSTSEARCH_RERANK_ENABLED': 'false'}
    index._write(h.root/'entry.json', {'owner': index._population_owner(h.collection, 'key'), 'witness': {}})
    monkeypatch.setattr(index, 'compute_index_key', lambda *a: ('key', {}))
    monkeypatch.setattr(index, '_materialize', lambda *a: h.collection)
    calls = []
    def witness(*a):
        calls.append('ready')
        return {}
    monkeypatch.setattr(index, 'witness', witness)
    monkeypatch.setattr(index, '_adoption_agreement', lambda *a: [])
    def status(*a):
        assert calls == ['ready'], 'idle was judged before ordinary adoption readiness'
        return complete_quality_status(len(h.collection.documents))
    monkeypatch.setattr(index, '_get', status)
    monkeypatch.setattr(sensitivity, 'confirm_identity', lambda *a: index_identity.ConfirmResult(True, [], {}))
    try:
        result = index._confirm(h)
        assert result.ok, result.failures
    finally:
        h.close()


@pytest.mark.parametrize('arm', [None, {'JUSTSEARCH_RERANK_ENABLED': 'false'}])
@pytest.mark.parametrize('remove_data', [False, True])
def test_prepared_entry_lost_before_lock_never_mutates_or_builds(tmp_path, monkeypatch, arm, remove_data):
    c = make_collection()
    cfg = index.IndexConfig(cache_root=tmp_path/'cache')
    monkeypatch.setattr(index.IndexConfig, 'environment', lambda _: {})
    monkeypatch.setattr(index, 'compute_index_key', lambda *a: ('key', {}))
    monkeypatch.setattr(index, '_under_cache', lambda path: path)
    root = cfg.cache_root/sha256_text(c.name)/'key'
    data = root/'data'; data.mkdir(parents=True)
    owner = index._population_owner(c, 'key')
    index._write(root/'entry.json', {'owner': owner})
    index._write(data/'quality-owner.json', owner)
    (data/'retained-index').write_bytes(b'prepared bytes')
    flock = index.fcntl.flock
    def acquire(lock, flags):
        # The entry passed preflight, but another holder retired it before this lock.
        if Path(lock.name) == root/'data.quality.lock':
            (root/'entry.json').unlink()
            if remove_data:
                index.shutil.rmtree(data)
        flock(lock, flags)
    monkeypatch.setattr(index.fcntl, 'flock', acquire)
    spawn = Mock(side_effect=AssertionError('prepared-only must never start preparation'))
    monkeypatch.setattr(index.subprocess, 'Popen', spawn)
    with pytest.raises(index.IndexRefused, match='not ready'):
        index.prepare_index(c, cfg, prepared_only=True, sensitivity=arm)
    spawn.assert_not_called()
    assert not (root/'build.json').exists()
    assert not (root/'collection').exists()
    assert data.exists() is (not remove_data)
    if not remove_data:
        assert (data/'retained-index').read_bytes() == b'prepared bytes'
        assert index._json(data/'quality-owner.json') == owner


@pytest.mark.parametrize('status', [
    {'indexState': 'IDLE', 'pendingJobs': 1, 'processingJobsCount': 0,
     'embeddingPendingCount': 0, 'pendingNerCount': 0},
    {'indexState': 'IDLE', 'pendingJobs': 0, 'processingJobsCount': 0,
     'embeddingPendingCount': 1, 'pendingNerCount': 0},
    {},
])
def test_pending_or_unknown_work_refuses_before_override_launch(tmp_path, monkeypatch, status):
    from .test_redesign import cached_handle
    h = cached_handle(tmp_path, monkeypatch)
    h.env['JUSTSEARCH_GPU_ENABLED'] = 'false'
    h._sensitivity = {'JUSTSEARCH_RERANK_ENABLED': 'false'}
    launches = []
    def start(config, data, env, *a, **kw):
        launches.append(dict(env))
        assert not h._lock.closed
        return backend.BackendInfo(Mock(), data)
    monkeypatch.setattr(index, '_start_serving', start)
    monkeypatch.setattr(index, '_get', lambda *a: status)
    # Even if the ordinary readiness predicate passes, idle evidence is mandatory.
    monkeypatch.setattr(index, '_ready_reasons', lambda *a: [])
    monkeypatch.setattr(index, '_confirm_ready', lambda *a, **kw: index_identity.ConfirmResult(True, [], {}))
    quarantine = Mock(side_effect=AssertionError('sensitivity must never quarantine'))
    monkeypatch.setattr(index, '_quarantine', quarantine)
    try:
        with pytest.raises(index.IndexRefused, match='pending'):
            h.wait(prepared_only=True, deadline=time.monotonic()+5)
        assert launches == [h.env]
        backend.stop_backend.assert_called_once()
        assert h._lock.closed
        assert (h.root/'entry.json').exists()
        quarantine.assert_not_called()
    finally:
        h.close()


def test_sensitivity_identity_confirmation_keeps_every_non_ce_check(monkeypatch, tmp_path):
    h = Mock(base_url='http://engine', env={'prepared': 'env'}, index_key='key', data_dir=tmp_path)
    live = Mock(return_value=SimpleNamespace(key='live'))
    monkeypatch.setattr(index_identity, 'compute_live_identity', live)
    calls = []
    for name in ('_confirm_binding', '_confirm_generation', '_confirm_counts', '_confirm_watched_roots'):
        monkeypatch.setattr(index_identity, name, lambda *a, name=name: calls.append(name))
    monkeypatch.setattr(index_identity, '_confirm_canary', Mock(side_effect=AssertionError('no CE')))
    monkeypatch.setattr(index_identity, '_get_json', lambda *a: (complete_quality_status(), None))
    assert sensitivity.confirm_identity(h, {'identity': {'key': 'live'}}).ok
    assert calls == ['_confirm_binding', '_confirm_generation', '_confirm_counts', '_confirm_watched_roots']
    assert live.call_args.kwargs == {'index_key': 'key'}
    assert live.call_args.args[1] == h.env
    assert not sensitivity.confirm_identity(h, {'identity': {'key': 'different'}}).ok


@pytest.mark.parametrize('reason', sorted(sensitivity.SKIPS | compare.BY_DESIGN_RERANK_SKIPS))
def test_labelled_skips_only_are_valid_and_counts_reconcile(reason):
    c = make_collection({'en/vague': 4})
    record = labelled(c)
    record = replace(record, results=tuple(replace(q, served=Served(q.served.legs, 'skipped', reason)) for q in record.results))
    assert compare.validate_run(c, record, {c.version}, 'arm', sensitivity=True) == []
    counts = sensitivity.counts(c, record.results, ('lang', 'kind'))
    assert counts['all']['queries'] == 4 and counts['all']['reranker'] == {reason: 4}
    assert sum(v['queries'] for k, v in counts.items() if k != 'all') == 4
    assert counts['all']['longest_consecutive_deadline_skips'] == (4 if reason in sensitivity.DEADLINES else 0)
    normal = replace(record, header=replace(record.header, run_kind='standard', sensitivity={}))
    assert bool(compare.validate_run(c, normal, {c.version}, 'normal')) == (reason not in compare.BY_DESIGN_RERANK_SKIPS)


@pytest.mark.parametrize('defect', ['MODEL_LOADING', 'INFERENCE_FAILED', 'ACQUIRE_BUDGET_EXCEEDED', None,
    'unknown', 'http', 'leg', 'outside', 'duplicate', 'query_duplicate', 'missing', 'dirty', 'population'])
def test_sensitivity_keeps_other_validity_gates(defect):
    c = make_collection({'en/vague': 4}); record = labelled(c); first = record.results[0]
    if defect == 'http': first = replace(first, error='HTTP 503')
    elif defect == 'leg': first = replace(first, served=Served(('lexical',), 'executed'))
    elif defect == 'outside': first = replace(first, ranked=('outside',))
    elif defect == 'duplicate': first = replace(first, ranked=(first.ranked[0],)*2)
    elif defect == 'query_duplicate': record = replace(record, results=(first, *record.results))
    elif defect == 'missing': record = replace(record, results=record.results[:1])
    elif defect == 'dirty': record = replace(record, header=replace(record.header, dirty_hash='d'*64))
    elif defect == 'population': record = replace(record, header=replace(record.header, index_population={**record.header.index_population, 'count': 1}))
    else: first = replace(first, served=Served(first.served.legs, 'skipped', defect))
    record = replace(record, results=(first, *record.results[1:]))
    assert compare.validate_run(c, record, {c.version}, 'arm', sensitivity=True)


def test_deadline_streak_and_executed_counts():
    c = make_collection({'en/vague': 6}); record = labelled(c)
    reasons = ['DISABLED', 'DEADLINE_EXCEEDED', 'SEARCH_DEADLINE_EXCEEDED', None,
               'SEARCH_DEADLINE_EXCEEDED', 'NAVIGATIONAL_QUERY']
    rows = [replace(q, served=Served(q.served.legs, 'skipped', reason)) if reason else q
            for q, reason in zip(record.results, reasons)]
    counts = sensitivity.counts(c, rows)['all']
    assert counts['longest_consecutive_deadline_skips'] == 2
    assert counts['reranker']['executed'] == 1
    assert sum(counts['reranker'].values()) == counts['queries'] == len(rows)
    measurements = report.run_measurements(c, replace(record, results=tuple(rows)))
    text = report.measurement_text(c.name, measurements, True)
    assert 'model loading or saturation by earlier native runs' in text
    assert 'longest_consecutive_deadline_skips' in text and 'nDCG@10' in text


def test_sensitivity_never_baseline_or_candidate_even_intended(stored):
    c, folder, tmp = stored
    arm = labelled(c, sample=10, seed=4); path = tmp/'arm.jsonl'; arm.write(path)
    loaded = RunRecord.read(path)
    assert loaded == arm and loaded.header.run_kind == 'sensitivity'
    cli = CliRunner()
    score = cli.invoke(main, ['quality', 'score', str(folder), str(path)])
    assert score.exit_code == 3 and score.output.startswith('SENSITIVITY '), score.output
    refused = cli.invoke(main, ['quality', 'score', str(folder), str(path), '--store-baseline', str(tmp/'bad.json')])
    assert refused.exit_code == 2 and 'sensitivity' in refused.output and not (tmp/'bad.json').exists()
    with pytest.raises(compare.BaselineError, match='sensitivity'):
        compare.write_baseline(path, c, tmp/'bad.json')
    compare.write_baseline(tmp/'base.jsonl', c, tmp/'base.baseline.json')
    for intended in ([], ['--intended', 'everything']):
        result = cli.invoke(main, ['quality', 'compare', str(folder), '--baseline', str(tmp/'base.baseline.json'),
            '--candidate', str(path), '--expect-commit', 'a'*40, *intended])
        assert result.exit_code == 2 and 'sensitivity' in result.output, result.output
    # Even an externally forged descriptor with the correct file digest is rejected.
    descriptor = json.loads((tmp/'base.baseline.json').read_text())
    descriptor['run_file'] = 'arm.jsonl'; descriptor['run_sha256'] = sha256_text(path.read_text())
    (tmp/'forged.baseline.json').write_text(json.dumps(descriptor))
    with pytest.raises(compare.BaselineError, match='sensitivity'):
        compare.load_baseline(tmp/'forged.baseline.json')


def test_sample_excludes_pending_and_is_order_independent():
    c = make_collection({'en/vague': 20, 'en/pending': 3})
    scored = collections.scored_collection(c, config_for(c, pending_kinds=('pending',)))
    ids = sensitivity.query_ids(scored, 8, 42)
    reordered = replace(scored, queries=dict(reversed(list(scored.queries.items()))), version='')
    assert sensitivity.query_ids(reordered, 8, 42) == ids
    assert sensitivity.query_ids(scored, 8, 43) != ids
    assert not set(ids) & set(collections.pending_queries(c, config_for(c, pending_kinds=('pending',))))
    assert scored.document_identity() == c.document_identity()
    assert sensitivity.declaration(scored, {}, None, 8, 42)['queries'] == {q: scored.queries[q].sha256 for q in ids}
    with pytest.raises(sensitivity.SensitivityRefused, match='only 20 eligible'):
        sensitivity.query_ids(scored, 21, 0)


def test_sensitivity_history_isolated_and_effective_profile_checked(tmp_path, monkeypatch):
    c = make_collection({'en/vague': 4}); arm = sensitivity.declaration(c, {}, None, 4, 0)
    record = labelled(c)
    profile = {'effective_settings': {'provider': 'cpu'}, 'effective_cpus': [2, 3, 4, 5]}
    record = replace(record, header=replace(record.header, sensitivity={**arm, **profile}))
    record = replace(record, results=tuple(replace(q, timings={'elapsed_seconds': i})
                                          for i, q in enumerate(record.results, 1)))
    monkeypatch.setattr(os, 'sched_getaffinity', lambda _: {2, 3, 4, 5})
    args = (c, dict(record.header.request_config), dict(record.header.model_hashes), tmp_path, arm)
    assert sensitivity.estimate(*args) == (150, 45, None)
    record.write(tmp_path/'old'/'run.jsonl')
    (tmp_path/'old'/'done.json').write_text(json.dumps({'error': None, 'phase_seconds': {'startup': 20, 'waiting': 100}}))
    assert sensitivity.estimate(*args) == (67.5, 25, profile)
    assert run.estimate_seconds(c, args[1], args[2], tmp_path) == 45+.35*4
    assert sensitivity.estimate(*args[:-1], sensitivity.declaration(c, {}, None, 2, 1)) == (61.25, 25, profile)
    for change in ({'settings': {'JUSTSEARCH_RERANK_ENABLED': 'false'}},
                   {'requested_cpus': [2, 3, 4, 5]}):
        assert sensitivity.estimate(*args[:-1], {**arm, **change}) == (150, 45, None)
    sensitivity.check_profile({**arm, **profile}, profile)
    with pytest.raises(sensitivity.SensitivityRefused, match='history'):
        sensitivity.check_profile({**arm, **profile, 'effective_cpus': [2]}, profile)


def write_timed_history(c, folder, *, sample=30, seed=0, seconds=(.1, .5),
                        startup=20, started='2026-11-02T10:00:00Z', **profile):
    record = labelled(c, sample=sample, seed=seed, started=started)
    record = replace(record, header=replace(record.header,
        sensitivity={**record.header.sensitivity,
                     'effective_settings': {'provider': 'cpu'}, 'effective_cpus': [2, 3, 4, 5],
                     **profile}),
        results=tuple(replace(q, timings={'elapsed_seconds': seconds[i % len(seconds)]})
                      for i, q in enumerate(record.results)))
    record.write(folder/'run.jsonl')
    # Scoring includes confirmation/readback, so it must never supply query time.
    (folder/'done.json').write_text(json.dumps({'error': None,
        'phase_seconds': {'startup': startup, 'waiting': 100, 'scoring': 70000}}))
    return record


@pytest.mark.parametrize('seconds, total, fits', [((.1, .5), 167.5, True), ((2,), 805, False)])
def test_sampled_history_scales_per_query_to_full_collection(tmp_path, monkeypatch, seconds, total, fits):
    c = make_collection({'en/vague': 300})
    record = write_timed_history(c, tmp_path/'sample', seconds=seconds)
    arm = sensitivity.declaration(c, {}, None, None, 42)
    monkeypatch.setattr(os, 'sched_getaffinity', lambda _: {2, 3, 4, 5})
    estimate, startup, profile = sensitivity.estimate(c, record.header.request_config,
        record.header.model_hashes, tmp_path, arm)
    assert estimate == pytest.approx(total)
    assert startup == 25
    assert profile == {k: record.header.sensitivity[k] for k in ('effective_settings', 'effective_cpus')}
    window = run.estimate_window([estimate], [startup], False)
    if fits:
        run.require_budget(window, 600)
    else:
        with pytest.raises(run.RunRefused, match='estimate 850.0s exceeds budget 600s'):
            run.require_budget(window, 600)
    # Default estimates never consume sensitivity history, including its samples.
    assert run.estimate_seconds(c, record.header.request_config, record.header.model_hashes,
                                tmp_path) == 45 + .35 * 300


@pytest.mark.parametrize('change', ['settings', 'requested_cpus', 'effective_cpus',
                                   'collection_version', 'request_config', 'model_hashes', 'run_kind'])
def test_per_query_history_requires_matching_inputs(tmp_path, monkeypatch, change):
    c = make_collection({'en/vague': 300})
    record = write_timed_history(c, tmp_path/'sample')
    header = record.header
    if change in ('settings', 'requested_cpus', 'effective_cpus'):
        value = {'JUSTSEARCH_RERANK_ENABLED': 'false'} if change == 'settings' else [2, 3]
        header = replace(header, sensitivity={**header.sensitivity, change: value})
    else:
        header = replace(header, request_config_sha256='', **{change: {
            'collection_version': 'other', 'request_config': {'mode': 'other'},
            'model_hashes': {'embed': 'other'}, 'run_kind': 'standard'}[change]})
    replace(record, header=header).write(tmp_path/'sample'/'run.jsonl')
    monkeypatch.setattr(os, 'sched_getaffinity', lambda _: {2, 3, 4, 5})
    assert sensitivity.estimate(c, record.header.request_config, record.header.model_hashes,
        tmp_path, sensitivity.declaration(c, {}, None, None, 42)) == (4590, 45, None)


def test_per_query_history_uses_last_five_of_the_latest_effective_profile(tmp_path, monkeypatch):
    c = make_collection({'en/vague': 300})
    for i, seconds in enumerate((100, .1, .2, .3, .4, .5)):
        record = write_timed_history(c, tmp_path/str(i), seconds=(seconds,),
                                    started=f'2026-11-02T10:0{i}:00Z')
    monkeypatch.setattr(os, 'sched_getaffinity', lambda _: {2, 3, 4, 5})
    args = (c, record.header.request_config, record.header.model_hashes, tmp_path,
            sensitivity.declaration(c, {}, None, None, 42))
    assert sensitivity.estimate(*args)[:2] == (242.5, 25)
    newer = write_timed_history(c, tmp_path/'new-profile', seconds=(.2,),
        effective_settings={'provider': 'other'}, started='2026-11-02T10:06:00Z')
    total, startup, expected = sensitivity.estimate(*args)
    assert total == 130 and startup == 25
    assert expected['effective_settings'] == newer.header.sensitivity['effective_settings']
    with pytest.raises(sensitivity.SensitivityRefused, match='history'):
        sensitivity.check_profile(record.header.sensitivity, expected)


@pytest.mark.parametrize('seconds', [None, -1, float('nan'), float('inf'), '0.3'])
def test_missing_or_invalid_query_timing_keeps_cold_per_query_allowance(tmp_path, monkeypatch, seconds):
    c = make_collection({'en/vague': 300})
    record = write_timed_history(c, tmp_path/'sample')
    # Malformed external JSONL must also fail closed at RunRecord's read boundary.
    lines = [json.loads(line) for line in record.dumps().splitlines()]
    lines[1]['timings'] = {} if seconds is None else {'elapsed_seconds': seconds}
    (tmp_path/'sample'/'run.jsonl').write_text(''.join(json.dumps(line)+'\n' for line in lines))
    monkeypatch.setattr(os, 'sched_getaffinity', lambda _: {2, 3, 4, 5})
    total, startup, _ = sensitivity.estimate(c, record.header.request_config,
        record.header.model_hashes, tmp_path, sensitivity.declaration(c, {}, None, None, 42))
    assert total >= 15 * 300
    assert startup == (25 if seconds is None or seconds == -1 else 45)


def test_cold_sensitivity_window_refuses_before_reserving(tmp_path, monkeypatch):
    c = make_collection()
    monkeypatch.setattr(backend, 'source_identity', lambda _: ('a'*40, None))
    ready_distribution(monkeypatch)
    monkeypatch.setattr(collections, 'resolve_collection', lambda _: (c, config_for(c)))
    monkeypatch.setattr(index.IndexConfig, 'environment', lambda _: {'JUSTSEARCH_GPU_ENABLED': 'false'})
    handle = Mock(model_hashes={'model': 'hash'})
    handle.__enter__ = Mock(return_value=handle); handle.__exit__ = Mock(return_value=False)
    monkeypatch.setattr(index, 'prepare_index', lambda *a, **kw: handle)
    launch = Mock(side_effect=AssertionError('must refuse before admission'))
    monkeypatch.setattr(run.subprocess, 'Popen', launch)
    with pytest.raises(run.RunRefused, match='estimate 735.0s exceeds budget 600s'):
        run.execute('sensitivity', ('fixture',), baseline_dir=tmp_path/'base', runs_dir=tmp_path/'runs')
    launch.assert_not_called()
    assert not list(tmp_path.iterdir())


@pytest.mark.parametrize('fault', [None, 'startup', 'malformed', 'timeout', 'interrupt', 'postscore',
                                 'affinity_before', 'affinity_after'])
def test_worker_completion_and_cleanup_include_sensitivity(tmp_path, monkeypatch, fault):
    c = make_collection({'en/vague': 6}); fixture = make_run(c)
    arm = sensitivity.declaration(c, LAPTOP, [2, 3, 4, 5], 3, 42)
    handle = Mock(index_key='prepared', index_population=c.index_population(), index_build='cached',
        index_cache_key='prepared', model_hashes=dict(fixture.header.model_hashes),
        components={'runtime': {}}, identity={}, index_state={'settled': True}, base_url='http://engine')
    if fault == 'startup': handle.wait.side_effect = RuntimeError('startup failed')
    monkeypatch.setattr(run, 'require_exclusive', lambda _: None)
    monkeypatch.setattr(collections, 'load_collection', lambda _: c)
    prepare = Mock(return_value=handle); monkeypatch.setattr(index, 'prepare_index', prepare)
    pin = Mock(); monkeypatch.setattr(sensitivity, 'pin_cpus', pin)
    readback = Mock(return_value={'provider': 'cpu'}); monkeypatch.setattr(sensitivity, 'effective_settings', readback)
    def cpu_check(handle, requested, check=None):
        failed = ((fault == 'affinity_before' and affinity.call_count == 1) or
                  (fault == 'affinity_after' and affinity.call_count == 2))
        if check is not None:
            check.update(ok=not failed, threads=[{'pid': 20, 'tid': 21,
                                                'mask': '2-6' if failed else '2-5'}])
        if failed:
            raise sensitivity.SensitivityRefused('thread 21 pid 20 mask 2-6 outside --cpus')
        return [2, 3, 4, 5]
    affinity = Mock(side_effect=cpu_check); monkeypatch.setattr(sensitivity, 'engine_cpus', affinity)
    monkeypatch.setattr(index, 'confirm', lambda _: SimpleNamespace(ok=fault != 'postscore', failures=['changed']))
    def query(client, h, qid, *a):
        if fault == 'timeout': raise TimeoutError('deadline')
        if fault == 'interrupt': raise KeyboardInterrupt()
        result = next(q for q in fixture.results if q.qid == qid)
        return replace(result, error='malformed response' if fault == 'malformed' else None,
            timings={'elapsed_seconds': 15}, served=Served(result.served.legs, 'skipped', 'DISABLED'))
    search = Mock(side_effect=query); monkeypatch.setattr(run, 'search_query', search)
    item = {'index_config': run._serial_config(index.IndexConfig()), 'sensitivity': arm,
        **{k: str(tmp_path/k) for k in ('collection', 'run', 'ready', 'go', 'done')}}
    Path(item['go']).touch()
    plan = {'request': run.refined_request(), 'commit': fixture.header.commit, 'dirty_hash': None,
            'mode': None, 'hardware': {}, 'budget': 600}
    run._collection_worker(plan, item, time.monotonic()+30)
    pin.assert_called_once_with([2, 3, 4, 5])
    assert prepare.call_args.kwargs['sensitivity'] == LAPTOP and prepare.call_args.kwargs['prepared_only'] is True
    handle.close.assert_called_once()
    done = json.loads(Path(item['done']).read_text())
    if fault in ('startup', 'timeout', 'interrupt', 'postscore', 'affinity_before', 'affinity_after'):
        assert done['error'] is not None
        if Path(item['run']).exists(): assert RunRecord.read(item['run']).header.ended is None
        if fault in ('affinity_before', 'affinity_after'):
            assert 'thread 21 pid 20 mask 2-6' in done['error']
            record = RunRecord.read(item['run'])
            checks = record.header.sensitivity['cpu_affinity_checks']
            phase = 'before_scoring' if fault == 'affinity_before' else 'after_scoring'
            assert checks[phase] == {'ok': False, 'threads': [{'pid': 20, 'tid': 21, 'mask': '2-6'}]}
            assert len(record.results) == (0 if fault == 'affinity_before' else 3)
            assert search.call_count == len(record.results)
    else:
        record = RunRecord.read(item['run'])
        assert done['error'] is None and record.header.ended
        assert record.header.run_kind == 'sensitivity' and record.header.index_key == 'prepared'
        assert set(q.qid for q in record.results) == set(arm['queries'])
        assert record.header.sensitivity['effective_cpus'] == [2, 3, 4, 5]
        assert record.header.sensitivity['effective_settings'] == {'provider': 'cpu'}
        assert record.header.sensitivity['cpu_affinity_checks'] == {
            phase: {'ok': True, 'threads': [{'pid': 20, 'tid': 21, 'mask': '2-5'}]}
            for phase in ('before_scoring', 'after_scoring')}
        assert bool(compare.validate_run(c, record, {c.version}, 'arm', sensitivity=True)) == (fault == 'malformed')
        assert readback.call_count == affinity.call_count == 2


@pytest.mark.parametrize('action', ['sensitivity', 'baseline', 'ablate', 'check', 'full-suite'])
def test_execute_reports_timings_through_existing_exclusive_path(tmp_path, monkeypatch, action):
    c = make_collection({'en/vague': 4}); fixture = make_run(c)
    monkeypatch.setattr(backend, 'source_identity', lambda _: (fixture.header.commit, None))
    ready_distribution(monkeypatch)
    monkeypatch.setattr(backend, 'bind_command', lambda _, cmd: cmd)
    monkeypatch.setattr(run.platform, 'processor', lambda: 'fixture CPU')
    monkeypatch.setattr(collections, 'resolve_collection', lambda _: (c, config_for(c)))
    models = dict(fixture.header.model_hashes)
    monkeypatch.setattr(index, 'model_hashes', lambda *a, **kw: models)
    monkeypatch.setattr(index.IndexConfig, 'environment', lambda _: {'JUSTSEARCH_GPU_ENABLED': 'false',
                                                                 'JUSTSEARCH_MODELS_DIR': str(tmp_path)})
    h = Mock(model_hashes=models); h.__enter__ = Mock(return_value=h); h.__exit__ = Mock(return_value=False)
    monkeypatch.setattr(index, 'prepare_index', lambda *a, **kw: h)
    request = run.request_for('vector' if action == 'ablate' else None)
    base = tmp_path/'base'; base.mkdir()
    make_run(c, request_config=request).write(tmp_path/'baseline.jsonl')
    compare.write_baseline(tmp_path/'baseline.jsonl', c, base/(sha256_text(c.name)+'.baseline.json'))
    launches = []
    def launch(command, **kw):
        launches.append(command)
        plan = json.loads(Path(command[-1]).read_text())
        item = plan['items'][0]
        header_kw = {'request_config': plan['request']}
        if action == 'sensitivity':
            header_kw.update(run_kind='sensitivity', sensitivity={**item['sensitivity'],
                'effective_settings': {'provider': 'cpu'}, 'effective_cpus': [2, 3, 4, 5]})
        record = make_run(c, **header_kw)
        record = replace(record, results=tuple(replace(q, timings={'elapsed_seconds': 2.0}) for q in record.results))
        record.write(item['run'])
        Path(item['done']).write_text(json.dumps({'error': None, 'elapsed_seconds': 10}))
        return SimpleNamespace(wait=lambda: 0)
    monkeypatch.setattr(run.subprocess, 'Popen', launch)
    result, text = run.execute('check' if action == 'full-suite' else action, (c.name,),
        baseline_dir=base, runs_dir=tmp_path/'runs',
        **({'mode': 'vector'} if action == 'ablate' else {'full_suite': True} if action == 'full-suite' else {}))
    assert result == (Outcome.SENSITIVITY if action == 'sensitivity' else Outcome.PASS)
    assert 'p50 2.000s, p95 2.000s' in text
    command = launches[0]
    assert command[1:3] == [str(index.REPO_ROOT/'scripts/dev/admission.py'), 'exclusive']
    assert '--drain-timeout' in command and '--timeout' in command
    summary = json.loads(next((tmp_path/'runs').glob('*/summary.json')).read_text())
    assert summary['measurements'][c.name]['p95_seconds'] == 2.0
