"""Frozen startup polling with synthetic interfaces; no live Engine or quality claim."""
from contextlib import ExitStack
from copy import deepcopy
from types import SimpleNamespace
from unittest.mock import Mock

import httpx
import pytest

from jseval import index_identity
from jseval.quality import frozen_index as frozen, index, sensitivity
from .conftest import pinned_env
from .test_frozen_index import source
from .test_sensitivity import complete_quality_status


@pytest.fixture
def startup(source, tmp_path, monkeypatch):
    collection, config, root, _, baseline = source
    env = pinned_env(config)
    components = frozen.runtime_components(collection, config, env)
    run_path = tmp_path/'run'/'run.jsonl'
    (run_path.parent/frozen.EVIDENCE).mkdir(parents=True)
    process = SimpleNamespace(proc=Mock(pid=123, poll=Mock(return_value=None)))
    stack = ExitStack()
    handle = frozen.FrozenHandle(collection, config, root, root/'data', root, baseline,
        {'payload': frozen.payload(root/'data', 'g-test')}, env, components, stack, run_path,
        _backend=process, base_url='http://frozen.test')
    runtime = {'process': {'pid': 123, 'birth': '100'},
               'manifest': {'instanceId': 'engine-a'}}
    entry = frozen.read_json(root/'entry.json')
    witness = Mock(return_value=entry['witness'])
    checks = {name: {'ok': True} for name in
              ('identity', 'binding', 'generation', 'counts', 'canary', 'watched_roots')}
    adoption = Mock(return_value=index_identity.ConfirmResult(True, [], checks))
    statuses = Mock(return_value=complete_quality_status())
    monkeypatch.setattr(frozen, 'runtime_components', lambda *_: components)
    monkeypatch.setattr(frozen, 'runtime_process', lambda *_: deepcopy(runtime))
    monkeypatch.setattr(index, '_get', statuses)
    monkeypatch.setattr(index, 'witness', witness)
    monkeypatch.setattr(index_identity, 'confirm_adoption', adoption)
    monkeypatch.setattr(frozen, 'predecessor_ownership', lambda *_: None)
    launch = Mock(return_value=process)
    monkeypatch.setattr(frozen.backend, 'start_backend', launch)
    monkeypatch.setattr(index, '_free_port', lambda: 12345)
    now, sleeps = [100.0], []
    def sleep(seconds):
        assert 0 < seconds <= 1
        sleeps.append(seconds)
        now[0] += seconds
    monkeypatch.setattr(frozen.time, 'monotonic', lambda: now[0])
    monkeypatch.setattr(frozen.time, 'sleep', sleep)
    yield SimpleNamespace(handle=handle, runtime=runtime, statuses=statuses, witness=witness,
                          adoption=adoption, checks=checks, now=now, sleeps=sleeps,
                          launch=launch, stack=stack)
    stack.close()


def rejected_status(case):
    status = complete_quality_status()
    if case == 'state':
        status['indexState'] = 'PROCESSING'
    elif case.startswith('missing:'):
        status.update(spladeEnabled=True, chunkSpladeEnabled=True,
                      spladePendingCount=0, chunkSpladePendingCount=0)
        del status[case.split(':')[1]]
    elif case.startswith('audit:'):
        _, stage, field = case.split(':')
        status.update(spladeEnabled=True, chunkSpladeEnabled=True,
                      spladePendingCount=0, chunkSpladePendingCount=0)
        status['completeness'] = [{'stageId': stage, field: 1}]
    else:
        status.update(spladeEnabled=True, chunkSpladeEnabled=True,
                      spladePendingCount=0, chunkSpladePendingCount=0)
        status[case] = 7
    return status


REFUSALS = ['state', 'pendingJobs', 'pendingJobsCount', 'processingJobsCount',
    'embeddingPendingCount', 'pendingNerCount', 'chunkEmbeddingPendingCount',
    'spladePendingCount', 'chunkSpladePendingCount',
    *['missing:'+field for field in ('pendingJobs', 'processingJobsCount',
      'embeddingPendingCount', 'pendingNerCount', 'chunkEmbeddingPendingCount',
      'spladePendingCount', 'chunkSpladePendingCount')],
    *['audit:'+stage+':'+field for stage in
      ('embed', 'ner', 'chunk_embed', 'splade', 'chunk_splade')
      for field in ('missing', 'failed')]]


def test_delivery_waits_for_resolved_idle_then_full_witness(startup):
    pending = {**complete_quality_status(), 'spladeEnabled': True}
    statuses = iter([pending, pending, complete_quality_status()])
    def observe(*_):
        startup.witness.assert_not_called()
        return next(statuses)
    startup.statuses.side_effect = observe
    assert startup.handle.wait(deadline=105) is startup.handle
    startup.witness.assert_called_once()
    startup.adoption.assert_called_once()
    assert startup.sleeps == [1, 1]
    assert startup.handle.index_state['settled'] is True
    assert set(startup.handle.observations['before']['checks']) == set(startup.checks)
    saved = frozen.read_json(startup.handle.run_path.parent/frozen.EVIDENCE/'before.json')
    assert saved['failures'] == [] and 'idle_refusal' not in saved
    assert set(saved['checks']) == set(startup.checks)


@pytest.mark.parametrize('case', REFUSALS)
def test_delivery_before_refusal_is_bounded_and_retains_actual_evidence(startup, case):
    status = rejected_status(case)
    # Enable flags are part of the actual predicate, including missing sparse counts.
    if case.startswith('missing:'):
        status.update(spladeEnabled=True, chunkSpladeEnabled=True)
    startup.statuses.return_value = status
    with pytest.raises(frozen.FrozenRefused, match='frozen readiness deadline'):
        startup.handle.wait(deadline=102.5)
    assert startup.sleeps == [1, 1, .5]
    assert startup.statuses.call_count == 3
    startup.witness.assert_not_called()
    startup.adoption.assert_not_called()
    assert not startup.handle.index_state
    assert 'before' not in startup.handle.observations
    saved = frozen.read_json(startup.handle.run_path.parent/frozen.EVIDENCE/'before.json')
    assert saved['idle_refusal']['status'] == status
    with pytest.raises(sensitivity.SensitivityRefused) as refusal:
        sensitivity.require_idle(status)
    assert saved['idle_refusal']['reason'] == str(refusal.value)
    assert saved['checks']['serving']['transient'] is True
    assert saved['failures'][0].startswith('serving.')


@pytest.mark.parametrize('case', REFUSALS)
def test_preserve_after_idle_refusal_is_immediate_and_permanent(startup, case):
    assert frozen.confirm(startup.handle, 'before').ok
    status = rejected_status(case)
    if case.startswith('missing:'):
        status.update(spladeEnabled=True, chunkSpladeEnabled=True)
    startup.statuses.return_value = status
    result = frozen.confirm(startup.handle, 'after')
    assert not result.ok and result.transient_failures == []
    assert startup.sleeps == []
    assert startup.witness.call_count == startup.adoption.call_count == 1


def test_delivery_after_refusal_retains_actual_status(startup):
    status = rejected_status('audit:embed:failed')
    startup.statuses.return_value = status
    result = frozen.confirm(startup.handle, 'after')
    assert not result.ok and not result.transient_failures
    saved = frozen.read_json(startup.handle.run_path.parent/frozen.EVIDENCE/'after.json')
    assert saved['idle_refusal']['status'] == status


@pytest.mark.parametrize('status', [complete_quality_status(),
    {**complete_quality_status(), 'embeddingEnabled': False, 'embeddingPendingCount': 9,
     'completeness': [{'stageId': 'embed', 'missing': 2, 'failed': 1}]},
    {**complete_quality_status(), 'chunkDocCount': 0, 'chunkEmbeddingPendingCount': 9,
     'completeness': [{'stageId': 'chunk_embed', 'missing': 2, 'failed': 1}]}])
def test_preserve_already_idle_and_disabled_stage_semantics(startup, status):
    startup.statuses.return_value = status
    assert startup.handle.wait(deadline=105) is startup.handle
    assert startup.sleeps == []
    assert startup.statuses.call_count == 1


@pytest.mark.parametrize('change', ['process', 'instance'])
def test_delivery_first_observed_owner_stays_pinned_across_pending(startup, change):
    startup.statuses.return_value = {**complete_quality_status(), 'spladeEnabled': True}
    first = frozen.confirm(startup.handle, 'before')
    assert not first.ok and first.transient_failures == first.failures
    if change == 'process':
        startup.runtime['process']['birth'] = '200'
    else:
        startup.runtime['manifest']['instanceId'] = 'engine-b'
    startup.statuses.return_value = complete_quality_status()
    second = frozen.confirm(startup.handle, 'before')
    assert not second.ok and second.transient_failures == []
    assert 'Engine incarnation changed' in '; '.join(second.failures)
    startup.witness.assert_not_called()


@pytest.mark.parametrize('failure', ['mixed', 'payload', 'model', 'owner', 'non-idle-sensitivity'])
def test_preserve_non_idle_and_mixed_failures_do_not_retry(startup, monkeypatch, failure):
    handle = startup.handle
    if failure == 'mixed':
        startup.adoption.return_value = index_identity.ConfirmResult(False,
            ['canary.unavailable: retry', 'identity.key: changed'],
            {'canary': {'ok': False, 'transient': True}, 'identity': {'ok': False}})
    elif failure == 'payload':
        (handle.data_dir/'index/default/indices/g-test/vectors').write_bytes(b'changed')
    elif failure == 'model':
        monkeypatch.setattr(frozen, 'runtime_components', lambda *_: {**handle.components, 'models': {}})
    elif failure == 'owner':
        index._write(handle.data_dir/'quality-owner.json', {})
    else:
        startup.witness.side_effect = sensitivity.SensitivityRefused('other predicate')
    with pytest.raises(frozen.FrozenRefused):
        handle.wait(deadline=105)
    assert startup.sleeps == []
    assert not handle.index_state


def test_preserve_transport_recovers_on_original_deadline(startup):
    startup.statuses.side_effect = [httpx.ConnectError('startup transport'), complete_quality_status()]
    assert startup.handle.wait(deadline=101.5) is startup.handle
    assert startup.sleeps == [1]
    assert startup.launch.call_count == 1


@pytest.mark.parametrize('error', [KeyboardInterrupt, SystemExit])
def test_preserve_cancellation_escapes_and_cleanup_remains_available(startup, error):
    startup.statuses.side_effect = error('cancelled')
    with pytest.raises(error):
        startup.handle.wait(deadline=105)
    assert startup.sleeps == []
    closed = Mock()
    startup.stack.callback(closed)
    # Exercise close's finally even when postflight refuses after cancellation.
    startup.handle._backend = None
    startup.handle.item['payload']['generation'] = 'missing-generation'
    with pytest.raises((frozen.FrozenRefused, OSError)):
        startup.handle.close()
    closed.assert_called_once()
