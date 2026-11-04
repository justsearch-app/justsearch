"""Regressions from the four-collection round-two engine measurement."""
from dataclasses import replace
import json
from pathlib import Path
from unittest.mock import Mock

import httpx
import pytest

from jseval.quality import compare, index, report, run
from jseval.quality.types import Outcome, Served
from .helpers import make_collection, make_run, config_for


def test_syntax_refusal_is_a_scored_product_outcome_and_is_reported():
    c = make_collection()
    record = make_run(c)
    q = record.results[0]
    with httpx.Client(base_url='http://engine', transport=httpx.MockTransport(lambda _:
            httpx.Response(400, json={'errorCode': 'SEARCH_SYNTAX_INVALID', 'error': 'invalid syntax'}))) as client:
        result = run.search_query(client, Mock(), q.qid, c.queries[q.qid], run.refined_request(), 5)
    assert result.error is None
    assert result.ranked == () and result.served == Served((), 'skipped', 'SEARCH_SYNTAX_INVALID')
    refused = replace(record, results=(result, *record.results[1:]))
    comparison = compare.compare(c, config_for(c), refused, refused)
    assert comparison.verdict.outcome is Outcome.PASS
    assert comparison.per_query[q.qid]['candidate']['nDCG@10'] == 0
    rendered = report.render(comparison)
    assert 'syntax refusals (1)' in rendered and q.qid in rendered
    # A syntax-handling change still changes quality, rather than being a runtime confound.
    changed = compare.compare(c, config_for(c), record, refused)
    assert not changed.confounds


@pytest.mark.parametrize('status,body', [
    (400, {'error': 'invalid syntax'}),
    (400, {'errorCode': 'INVALID_REQUEST', 'error': 'invalid syntax'}),
    (500, {'errorCode': 'SEARCH_SYNTAX_INVALID'}),
    (401, {'errorCode': 'SEARCH_SYNTAX_INVALID'}),
])
def test_only_typed_http400_syntax_refusals_are_legitimate(status, body):
    c = make_collection()
    with httpx.Client(base_url='http://engine', transport=httpx.MockTransport(lambda _:
            httpx.Response(status, json=body))) as client:
        result = run.search_query(client, Mock(), 'q000', c.queries['q000'], run.refined_request(), 5)
    assert result.error and str(status) in result.error


def test_pipeline_eligible_reduction_is_comparable_only_when_identical():
    c = make_collection()
    record = make_run(c)
    phrase = replace(record, results=tuple(replace(q, served=Served(
        ('lexical',), 'skipped', 'PIPELINE_NOT_ELIGIBLE', {
            'lexical': {'status': 'executed', 'reason': None},
            'dense': {'status': 'skipped', 'reason': 'sparse-shortcut'}})) for q in record.results))
    assert compare.compare(c, config_for(c), phrase, phrase).verdict.outcome is Outcome.PASS
    assert compare.compare(c, config_for(c), record, phrase).verdict.outcome is Outcome.INCONCLUSIVE
    unproven = replace(phrase, results=tuple(replace(q, served=replace(q.served,
        reason='NAVIGATIONAL_QUERY', leg_details={})) for q in phrase.results))
    assert compare.compare(c, config_for(c), unproven, unproven).verdict.outcome is Outcome.INVALID
    vector = replace(unproven, header=replace(phrase.header, expected_legs=('dense',)))
    assert compare.compare(c, config_for(c), vector, vector).verdict.outcome is Outcome.INVALID


def test_quick_and_explicit_full_suite_surfaces(monkeypatch):
    from click.testing import CliRunner
    from jseval.cli import main
    assert run.DEFAULT_COLLECTIONS == ('scifact', 'miracl-de', 'enron-email')
    assert run.FULL_COLLECTIONS == ('scifact', 'miracl-de', 'miracl-fr', 'enron-email')
    calls = []
    def execute(action, refs, **kwargs):
        calls.append((action, refs, kwargs))
        return Outcome.PASS, 'PASS fixture'
    monkeypatch.setattr(run, 'execute', execute)
    cli = CliRunner()
    assert cli.invoke(main, ['quality', 'full-suite']).exit_code == 2
    result = cli.invoke(main, ['quality', 'full-suite', '--window-seconds', '720'])
    assert result.exit_code == 0, result.output
    assert calls[-1][2]['full_suite'] is True and calls[-1][2]['window_seconds'] == 720


def test_estimate_models_startup_overlap_once():
    # Three engines: 40s startup and 60s scoring/cleanup each.
    assert run.estimate_window([100, 100, 100], [40, 40, 40], False) == 345
    assert run.estimate_window([100, 100, 100], [40, 40, 40], True) == 265
    # Startup outlasts scoring: the next engine is still warming after the current stops.
    assert run.estimate_window([100, 100], [80, 80], True) == 225


@pytest.mark.parametrize('phases,expected', [
    ({'waiting': 100, 'startup': 20}, 255),
    ({}, 111.25),  # 40s measured query work + 45s cold overhead, with headroom
])
def test_history_does_not_charge_time_waiting_for_previous_collection(tmp_path, phases, expected):
    c = make_collection()
    record = make_run(c)
    record = replace(record, results=tuple(replace(q, timings={'elapsed_seconds': 1})
                                         for q in record.results))
    record.write(tmp_path/'old/run.jsonl')
    (tmp_path/'old/done.json').write_text(json.dumps({'phase_seconds': phases}))
    assert run.estimate_seconds(c, dict(record.header.request_config),
                                dict(record.header.model_hashes), tmp_path) == expected


@pytest.mark.parametrize('headroom', [False, True])
def test_window_prestarts_only_with_gpu_headroom(tmp_path, monkeypatch, headroom):
    monkeypatch.setattr(run, 'require_exclusive', lambda _: None)
    checks = []
    monkeypatch.setattr(run, 'can_overlap', lambda *a: checks.append(a) or headroom)
    items = [{k: str(tmp_path/f'{n}-{k}') for k in ('ready', 'go', 'done')}
             | {'number': n, 'name': str(n)} for n in range(2)]
    children = []
    class Child:
        pid = 123
        def __init__(self, n):
            self.n = n
        def poll(self):
            if not Path(items[self.n]['go']).exists():
                return None
            Path(items[self.n]['done']).write_text('{"error":null}')
            return 0
    def spawn(args):
        n = len(children)
        if n:
            # A prestart happens before scoring is released; sequential startup
            # happens only after the previous child has completed cleanup.
            assert Path(items[0]['done']).exists() is (not headroom)
        children.append(Child(n))
        Path(items[n]['ready']).touch()
        return children[-1]
    monkeypatch.setattr(run.subprocess, 'Popen', spawn)
    run._window({'budget': 100, 'path': 'plan', 'items': items, 'overlap': True,
                 'gpu_engine_mib': 5000})
    assert len(children) == 2 and checks


@pytest.mark.parametrize('enabled,free,footprint,allowed', [
    (False, 30000, 5000, False), (True, 8192, 5000, True),
    (True, 6000, 5000, False), (True, None, 5000, False),
    (True, 30000, None, False),
])
def test_overlap_requires_measured_gpu_headroom(monkeypatch, enabled, free, footprint, allowed):
    monkeypatch.setattr(run, 'gpu_memory', lambda *_: (free, footprint))
    assert run.can_overlap(enabled, 123) is allowed


def test_gpu_memory_counts_only_the_owned_engine_tree(monkeypatch):
    monkeypatch.setattr(run.psutil, 'Process', lambda _: Mock(children=lambda **kw: [Mock(pid=234)]))
    replies = iter(['GPU-one, 8192\n', 'GPU-one, 234, 5000\nGPU-one, 999, 7600\n'])
    monkeypatch.setattr(run.subprocess, 'check_output', lambda *a, **kw: next(replies))
    assert run.gpu_memory(123) == (8192, 5000)


def test_french_collection_is_refused_by_quick_check_before_reserving(tmp_path, monkeypatch):
    c = make_collection(name='miracl-fr')
    monkeypatch.setattr(run.backend, 'source_identity', lambda _: ('commit', None))
    monkeypatch.setattr(run.backend, 'require_prepared', lambda _: None)
    monkeypatch.setattr(run.collections, 'resolve_collection', lambda _: (c, config_for(c)))
    spawn = Mock(side_effect=AssertionError('not a quick-check collection'))
    monkeypatch.setattr(run.subprocess, 'Popen', spawn)
    with pytest.raises(run.RunRefused, match='full-suite'):
        run.execute('check', ('miracl-fr',), baseline_dir=tmp_path, runs_dir=tmp_path)
    spawn.assert_not_called()


@pytest.mark.parametrize('name', ['run.py', 'compare.py', 'report.py', 'scoring.py', 'known_answers.py', '../commands/quality.py'])
def test_harness_only_changes_reuse_index(tmp_path, name):
    import subprocess
    subprocess.run(['git', 'init', '-q', str(tmp_path)], check=True)
    before = index.tree_hash(tmp_path)
    path = tmp_path/'scripts/jseval/jseval/quality'/name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text('harness-only change')
    assert index.tree_hash(tmp_path) == before


@pytest.mark.parametrize('name', ['index.py', 'collections.py', 'types.py'])
def test_materialization_sources_still_key_index(tmp_path, name):
    import subprocess
    subprocess.run(['git', 'init', '-q', str(tmp_path)], check=True)
    before = index.tree_hash(tmp_path)
    path = tmp_path/'scripts/jseval/jseval/quality'/name
    path.parent.mkdir(parents=True)
    path.write_text('index-shaping change')
    assert index.tree_hash(tmp_path) != before


def test_refused_adoption_is_quarantined_then_next_prepare_builds(tmp_path, monkeypatch):
    c = make_collection()
    cfg = index.IndexConfig(cache_root=tmp_path)
    monkeypatch.setattr(index.IndexConfig, 'environment', lambda _: {})
    monkeypatch.setattr(index, 'compute_index_key', lambda *a: ('key', {}))
    monkeypatch.setattr(index, '_under_cache', lambda p: p)
    monkeypatch.setattr(index.backend, 'require_prepared', lambda *a: None)
    root = tmp_path/index.sha256_text(c.name)/'key'
    data = root/'data'
    data.mkdir(parents=True)
    index._write(data/'quality-owner.json', index._population_owner(c, 'key'))
    (data/'segments_corrupt').write_text('retain evidence')
    (root/'entry.json').write_text('{broken')
    spawn = Mock(side_effect=AssertionError('scoring must never build'))
    monkeypatch.setattr(index.subprocess, 'Popen', spawn)
    with pytest.raises(index.IndexRefused, match='not ready'):
        index.prepare_index(c, cfg, prepared_only=True)
    spawn.assert_not_called()
    assert not (root/'entry.json').exists()
    quarantines = list((root/'quarantine').iterdir())
    assert len(quarantines) == 1
    assert 'JSONDecodeError' in (quarantines[0]/'reason.json').read_text()
    assert (quarantines[0]/'data/segments_corrupt').read_text() == 'retain evidence'
    spawn.side_effect = None
    spawn.return_value.poll.return_value = 0
    with index.prepare_index(c, cfg) as handle:
        assert handle.index_build == 'fresh' and handle._worker is spawn.return_value
    assert 'pausable' in spawn.call_args.args[0]


def test_failed_live_adoption_is_quarantined_after_engine_stops(tmp_path, monkeypatch):
    import time
    c = make_collection()
    root = tmp_path/'entry'
    data = root/'data'
    data.mkdir(parents=True)
    index._write(data/'quality-owner.json', index._population_owner(c, 'key'))
    index._write(root/'entry.json', {'owner': index._population_owner(c, 'key')})
    lock = (root/'data.quality.lock').open('a+')
    cfg = index.IndexConfig(timeout_sec=.01)
    handle = index.IndexHandle(c, cfg, 'key', root, data, 'cached', {}, {}, lock)
    engine = Mock()
    engine.proc.poll.return_value = None
    monkeypatch.setattr(index.backend, 'start_backend', lambda **kw: engine)
    monkeypatch.setattr(index.backend, 'require_prepared', lambda *a: None)
    stopped = []
    def stop(*a, **kw):
        assert data.exists() and (root/'entry.json').exists()
        stopped.append(True)
    monkeypatch.setattr(index.backend, 'stop_backend', stop)
    monkeypatch.setattr(index, '_launch_env', lambda *a: {})
    monkeypatch.setattr(index, 'confirm', lambda _: Mock(ok=False, failures=['population mismatch']))
    with pytest.raises(index.IndexRefused, match='not ready'):
        handle.wait(prepared_only=True, deadline=time.monotonic()+.01)
    assert stopped and lock.closed
    assert not (root/'entry.json').exists()
    assert 'population mismatch' in next((root/'quarantine').glob('*/reason.json')).read_text()


def test_cleanup_failure_releases_index_lock(tmp_path, monkeypatch):
    c = make_collection()
    lock = (tmp_path/'data.quality.lock').open('a+')
    handle = index.IndexHandle(c, index.IndexConfig(), 'key', tmp_path, tmp_path/'data',
                               'cached', {}, {}, lock, _backend=Mock())
    monkeypatch.setattr(index.backend, 'stop_backend', Mock(side_effect=RuntimeError('cleanup failed')))
    with pytest.raises(RuntimeError, match='cleanup failed'):
        handle.close()
    assert lock.closed


def test_background_worker_uses_the_same_harness_as_its_parent(tmp_path, monkeypatch):
    c = make_collection()
    engine = tmp_path/'engine-candidate'
    cfg = index.IndexConfig(repo_root=engine, cache_root=tmp_path/'indexes')
    monkeypatch.setattr(index.IndexConfig, 'environment', lambda _: {})
    monkeypatch.setattr(index, 'compute_index_key', lambda *a: ('key', {}))
    monkeypatch.setattr(index, '_under_cache', lambda p: p)
    spawn = Mock()
    spawn.return_value.poll.return_value = 0
    monkeypatch.setattr(index.subprocess, 'Popen', spawn)
    with index.prepare_index(c, cfg):
        import os
        paths = spawn.call_args.kwargs['env']['PYTHONPATH'].split(os.pathsep)
        assert Path(paths[0]) == Path(index.__file__).resolve().parents[2]


def leg_response(reason='sparse-shortcut'):
    return {'results': [], 'searchTrace': {'stages': [
        {'id': 'sparse-retrieval', 'status': 'executed'},
        {'id': 'dense-retrieval', 'status': 'skipped', 'reason': reason},
        {'id': 'splade-retrieval', 'status': 'skipped', 'reason': 'not-selected'},
        {'id': 'cross-encoder', 'status': 'skipped', 'reason': 'BELOW_MIN_THRESHOLD'}]}}


def collect_leg_result(c, reason='sparse-shortcut'):
    handle = Mock()
    handle.ranked_doc_ids.return_value = []
    with httpx.Client(base_url='http://engine', transport=httpx.MockTransport(
            lambda _: httpx.Response(200, json=leg_response(reason)))) as client:
        return run.search_query(client, handle, 'q000', c.queries['q000'], run.refined_request(), 5)


def test_leg_trace_is_retained_and_identical_sparse_shortcuts_pass():
    from jseval.quality.types import QueryResult
    c = make_collection()
    record = make_run(c)
    result = collect_leg_result(c)
    assert result.served.to_dict()['leg_details'] == {
        'lexical': {'status': 'executed', 'reason': None},
        'dense': {'status': 'skipped', 'reason': 'sparse-shortcut'},
        'splade': {'status': 'skipped', 'reason': 'not-selected'}}
    assert QueryResult.from_dict(result.to_dict()) == result
    reduced = replace(record, results=(result, *record.results[1:]))
    assert compare.compare(c, config_for(c), reduced, reduced).verdict.outcome is Outcome.PASS
    assert compare.compare(c, config_for(c), record, reduced).verdict.outcome is Outcome.INCONCLUSIVE


@pytest.mark.parametrize('reason', ['INFERENCE_FAILED', 'MODEL_LOADING', 'SEARCH_DEADLINE_EXCEEDED',
                                  'ACQUIRE_BUDGET_EXCEEDED', 'future-reason', None])
def test_leg_drops_stay_invalid_even_with_legitimate_reranker_skip(reason):
    c = make_collection()
    record = make_run(c)
    result = collect_leg_result(c, reason)
    assert result.served.leg_details['dense'] == {'status': 'skipped', 'reason': reason}
    reduced = replace(record, results=(result, *record.results[1:]))
    comparison = compare.compare(c, config_for(c), reduced, reduced)
    assert comparison.verdict.outcome is Outcome.INVALID
    assert any('q000' in s and 'dense' in s for s in comparison.verdict.reasons)


def test_leg_reasons_differ_even_when_executed_leg_set_is_identical():
    c = make_collection()
    record = make_run(c)
    left = collect_leg_result(c)
    right = collect_leg_result(c, 'not-selected')
    a = replace(record, results=(left, *record.results[1:]))
    b = replace(record, results=(right, *record.results[1:]))
    assert compare.compare(c, config_for(c), a, b).verdict.outcome is Outcome.INCONCLUSIVE


def test_reranker_reason_is_not_evidence_for_missing_leg():
    c = make_collection()
    r = make_run(c)
    r = replace(r, results=tuple(replace(q, served=Served(
        ('lexical',), 'skipped', 'PIPELINE_NOT_ELIGIBLE')) for q in r.results))
    assert compare.compare(c, config_for(c), r, r).verdict.outcome is Outcome.INVALID


@pytest.mark.parametrize('details', [None, [], {'dense': None},
    {'dense': {'status': 'unknown'}}, {'dense': {'status': 'skipped', 'reason': 123}}])
def test_malformed_leg_trace_is_refused_when_loading(details):
    with pytest.raises(ValueError):
        Served.from_dict({'legs': [], 'reranker': 'skipped', 'leg_details': details})


def test_optional_leg_inference_failure_is_invalid_even_with_expected_legs_executed():
    c = make_collection()
    record = make_run(c)
    q = record.results[0]
    bad = replace(q, served=replace(q.served, leg_details={
        'splade': {'status': 'skipped', 'reason': 'INFERENCE_FAILED'}}))
    r = replace(record, results=(bad, *record.results[1:]))
    verdict = compare.compare(c, config_for(c), r, r).verdict
    assert verdict.outcome is Outcome.INVALID
    assert any('q000' in s and 'splade' in s and 'INFERENCE_FAILED' in s for s in verdict.reasons)


def test_leg_trace_cannot_disagree_with_executed_legs():
    c = make_collection()
    record = make_run(c)
    q = record.results[0]
    bad = replace(q, served=replace(q.served, leg_details={
        'dense': {'status': 'skipped', 'reason': 'sparse-shortcut'}}))
    r = replace(record, results=(bad, *record.results[1:]))
    assert compare.compare(c, config_for(c), r, r).verdict.outcome is Outcome.INVALID
