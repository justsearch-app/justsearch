"""The real request, fail-closed trust gate and bounded scoring lifecycle."""
from dataclasses import replace
import json
from pathlib import Path
import subprocess
import sys
from unittest.mock import Mock

import httpx
import pytest

from jseval import backend
from jseval.quality import compare, run
from jseval.quality.types import Outcome, Served, sha256_text
from .helpers import make_collection, make_run, config_for


@pytest.mark.parametrize('reason', sorted(compare.BY_DESIGN_RERANK_SKIPS))
def test_identical_deterministic_skips_are_comparable(reason):
    c = make_collection()
    r = make_run(c)
    r = replace(r, results=tuple(replace(q, served=Served(('lexical', 'dense'), 'skipped', reason)) for q in r.results))
    assert compare.compare(c, config_for(c), r, r).verdict.outcome == Outcome.PASS


@pytest.mark.parametrize('reason', ['SEARCH_DEADLINE_EXCEEDED', 'MODEL_LOADING', 'INFERENCE_FAILED', 'ACQUIRE_BUDGET_EXCEEDED', None, 'future-reason'])
def test_reranker_drops_are_invalid_with_query(reason):
    c = make_collection()
    r = make_run(c)
    r = replace(r, results=(replace(r.results[0], served=Served(('lexical', 'dense'), 'skipped', reason)), *r.results[1:]))
    result = compare.compare(c, config_for(c), r, r)
    assert result.verdict.outcome == Outcome.INVALID
    assert any(r.results[0].qid in s for s in result.verdict.reasons)


def response(reason=None):
    return {'results': [{'fields': {'path': 'docs/a.txt'}}], 'searchTrace': {'stages': [
        {'id': 'sparse-retrieval', 'status': 'executed'},
        {'id': 'dense-retrieval', 'status': 'executed'},
        {'id': 'cross-encoder', 'status': 'skipped' if reason else 'executed', 'reason': reason}]}}


def test_refined_request_and_delivered_order():
    c = make_collection()
    qid = next(iter(c.queries))
    seen = []
    handle = Mock()
    handle.ranked_doc_ids.return_value = ['d001', 'd000']
    def reply(req):
        seen.append(json.loads(req.content))
        return httpx.Response(200, json=response())
    with httpx.Client(transport=httpx.MockTransport(reply), base_url='http://engine') as client:
        r = run.search_query(client, handle, qid, c.queries[qid], run.refined_request(), 5)
    body = seen[0]
    assert body['querySyntax'] == 'search' and body['limit'] == 50
    assert body['includeExcerpts'] and body['facets']['include']
    assert [f['field'] for f in body['facets']['fields']] == ['file_kind', 'mime_base', 'language', 'meta_author']
    assert set(body) == {'query', 'querySyntax', 'limit', 'includeExcerpts', 'facets'}
    assert r.ranked == ('d001', 'd000') and r.served == Served(('lexical', 'dense'), 'executed')


@pytest.mark.parametrize('wire,status,error', [(response('SEARCH_DEADLINE_EXCEEDED'), 200, False), ({'error':'bad syntax'}, 400, True), ({'results':[]}, 200, True)])
def test_errors_never_become_empty_success(wire, status, error):
    c = make_collection()
    with httpx.Client(transport=httpx.MockTransport(lambda _: httpx.Response(status, json=wire)), base_url='http://engine') as client:
        h = Mock(); h.ranked_doc_ids.return_value = []
        r = run.search_query(client, h, 'q000', c.queries['q000'], run.refined_request(), 5)
    assert bool(r.error) == error
    if not error:
        assert r.served.reason == 'SEARCH_DEADLINE_EXCEEDED'


def test_estimate_refuses_before_admission(tmp_path):
    with pytest.raises(run.RunRefused, match='estimate.*budget'):
        run.require_budget(601, 600)
    assert not list(tmp_path.iterdir())


def test_candidate_identity_covers_untracked_and_deletion(tmp_path):
    subprocess.run(['git','init','-q',str(tmp_path)], check=True)
    subprocess.run(['git','-c','user.name=Test','-c','user.email=test@example.com','commit','--allow-empty','-qm','fixture'], cwd=tmp_path, check=True)
    commit, dirt = backend.source_identity(tmp_path)
    assert dirt is None
    (tmp_path/'untracked').write_text('a')
    assert backend.source_identity(tmp_path)[1]
    (tmp_path/'untracked').write_text('b')
    assert backend.source_identity(tmp_path)[1] != dirt


def test_estimate_uses_complete_compatible_history(tmp_path):
    c = make_collection()
    r = make_run(c)
    r.write(tmp_path/'old'/'run.jsonl')
    assert run.estimate_seconds(c, dict(r.header.request_config), dict(r.header.model_hashes), tmp_path) == 380
    assert run.estimate_seconds(c, run.refined_request(), dict(r.header.model_hashes), tmp_path) == 59
    replace(r, header=replace(r.header, ended=None)).write(tmp_path/'old'/'run.jsonl')
    assert run.estimate_seconds(c, dict(r.header.request_config), dict(r.header.model_hashes), tmp_path) == 59


def test_cli_quality_actions_and_errors(monkeypatch):
    from click.testing import CliRunner
    from jseval.cli import main
    calls = []
    def execute(action, refs, **kw):
        calls.append((action, refs, kw))
        return Outcome.PASS, 'PASS fixture'
    monkeypatch.setattr(run, 'execute', execute)
    cli = CliRunner()
    for action in ('check', 'baseline'):
        result = cli.invoke(main, ['quality', action, 'scifact'])
        assert result.exit_code == 0 and result.output == 'PASS fixture\n', result.output
    result = cli.invoke(main, ['quality', 'ablate', 'scifact', '--mode', 'vector'])
    assert result.exit_code == 0 and calls[-1][2]['mode'] == 'vector'
    for args in (['check', '--override'], ['check', '--window-seconds', '601'], ['ablate']):
        assert cli.invoke(main, ['quality', *args]).exit_code == 2
    def refuse(*a, **kw):
        raise run.RunRefused('not ready: index missing')
    monkeypatch.setattr(run, 'execute', refuse)
    result = cli.invoke(main, ['quality', 'check'])
    assert result.exit_code == 2 and result.output.count('\n') == 1 and 'not ready' in result.output


def test_different_legitimate_skip_reasons_remain_a_confound():
    c = make_collection()
    r = make_run(c)
    left = replace(r, results=tuple(replace(q, served=Served(q.served.legs, 'skipped', 'NAVIGATIONAL_QUERY')) for q in r.results))
    right = replace(r, results=tuple(replace(q, served=Served(q.served.legs, 'skipped', 'BELOW_MIN_THRESHOLD')) for q in r.results))
    assert compare.compare(c, config_for(c), left, right).verdict.outcome == Outcome.INCONCLUSIVE


def test_missing_prepared_index_never_spawns_or_creates_data(tmp_path, monkeypatch):
    from jseval.quality import index
    c = make_collection()
    cfg = index.IndexConfig(cache_root=tmp_path/'indexes')
    monkeypatch.setattr(index.IndexConfig, 'environment', lambda _: {})
    monkeypatch.setattr(index,'compute_index_key',lambda *args: ('key',{}))
    monkeypatch.setattr(index,'_under_cache',lambda p:p)
    monkeypatch.setattr(backend,'require_prepared',lambda *args:None)
    spawn = Mock(side_effect=AssertionError('prepared-only must never start a build'))
    monkeypatch.setattr(index.subprocess,'Popen',spawn)
    with pytest.raises(index.IndexRefused,match='not ready'):
        index.prepare_index(c,cfg,prepared_only=True)
    assert not cfg.cache_root.exists()
    spawn.assert_not_called()


def test_corrupt_prepared_entry_is_not_ready_and_never_rebuilds(tmp_path, monkeypatch):
    from jseval.quality import index
    c=make_collection()
    cfg=index.IndexConfig(cache_root=tmp_path)
    monkeypatch.setattr(index.IndexConfig,'environment',lambda _: {})
    monkeypatch.setattr(index,'compute_index_key',lambda *args:('key',{}))
    monkeypatch.setattr(index,'_under_cache',lambda p:p)
    monkeypatch.setattr(backend,'require_prepared',lambda *args:None)
    root=tmp_path/sha256_text(c.name)/'key'
    root.mkdir(parents=True)
    (root/'entry.json').write_text('{broken')
    spawn=Mock(side_effect=AssertionError('must never rebuild'))
    monkeypatch.setattr(index.subprocess,'Popen',spawn)
    with pytest.raises(index.IndexRefused,match='not ready'):
        index.prepare_index(c,cfg,prepared_only=True)
    spawn.assert_not_called()


def test_execute_estimate_guard_runs_before_reservation(tmp_path, monkeypatch):
    c=make_collection()
    monkeypatch.setattr(backend,'source_identity',lambda _:('a'*40,None))
    monkeypatch.setattr(backend,'require_prepared',lambda *_:None)
    monkeypatch.setattr(run.collections,'resolve_collection',lambda _: (c,config_for(c)))
    handle=Mock()
    handle.__enter__=Mock(return_value=handle)
    handle.__exit__=Mock(return_value=False)
    handle.model_hashes={'model':'pinned'}
    monkeypatch.setattr(run.index,'prepare_index',lambda *a,**kw:handle)
    monkeypatch.setattr(run,'estimate_seconds',lambda *a:601)
    spawn=Mock(side_effect=AssertionError('over-budget work must never reserve'))
    monkeypatch.setattr(run.subprocess,'Popen',spawn)
    with pytest.raises(run.RunRefused,match='estimate.*budget'):
        run.execute('baseline',('fixture',),baseline_dir=tmp_path/'base',runs_dir=tmp_path/'runs')
    spawn.assert_not_called()
    assert not list(tmp_path.iterdir())


@pytest.mark.skipif(sys.platform != 'linux',reason='Linux admission guardian')
def test_real_admission_deadline_reaps_hung_worker_and_releases(tmp_path,monkeypatch):
    import os,time
    admission=Path(__file__).resolve().parents[4]/'scripts/dev/admission.py'
    monkeypatch.setenv('JS_ADMISSION_DIR',str(tmp_path/'admission'))
    monkeypatch.delenv('JS_ADMISSION_TOKEN',raising=False)
    item={'number':0,'name':'hung-fixture','ready':str(tmp_path/'ready'),
          'go':str(tmp_path/'go'),'done':str(tmp_path/'done')}
    plan={'budget':45.3,'items':[item],'path':str(tmp_path/'plan.json')}
    (tmp_path/'plan.json').write_text(json.dumps(plan))
    fixture='''
import os,sys,time
from pathlib import Path
Path(sys.argv[1]).write_text(str(os.getpid()))
Path(sys.argv[2]).touch()
time.sleep(600)
'''
    (tmp_path/'fixture.py').write_text(fixture)
    wrapper='''
import json,sys
from pathlib import Path
from jseval.quality import run
plan=json.loads(Path(sys.argv[1]).read_text())
spawn=run.subprocess.Popen
run.subprocess.Popen=lambda *a,**kw:spawn([sys.executable,sys.argv[2],sys.argv[3],plan['items'][0]['ready']])
try: run._window(plan)
except run.RunRefused as e:
 print('INVALID',e)
 sys.exit(2)
'''
    def status():
        return json.loads(subprocess.check_output([sys.executable,str(admission),'status'],text=True))
    assert status()==[]
    start=time.monotonic()
    proc=subprocess.Popen([sys.executable,str(admission),'exclusive','--purpose','quality deadline fixture',
        '--timeout','3','--',sys.executable,'-c',wrapper,str(tmp_path/'plan.json'),str(tmp_path/'fixture.py'),str(tmp_path/'pid')],
        stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
    seen=False
    while proc.poll() is None:
        seen |= any(r.get('mode')=='exclusive' for r in status())
        time.sleep(.02)
    output=proc.communicate(timeout=5)[0]
    assert proc.returncode==2 and 'window deadline' in output,output
    assert time.monotonic()-start < 3
    assert seen and status()==[]
    assert not Path('/proc', (tmp_path/'pid').read_text()).exists()
    # Waiting work can take the same directory once the run has reaped its child.
    assert subprocess.run([sys.executable,str(admission),'heavy','--purpose','next fixture','--',sys.executable,'-c','pass'],timeout=5).returncode==0


def test_forged_or_missing_admission_token_is_refused(tmp_path,monkeypatch):
    root=Path(__file__).resolve().parents[4]
    monkeypatch.setenv('JS_ADMISSION_DIR',str(tmp_path))
    for token in ('', 'not-json',json.dumps({'directory':str(tmp_path),'record':'missing.json'})):
        monkeypatch.setenv('JS_ADMISSION_TOKEN',token)
        with pytest.raises(run.RunRefused,match='exclusive machine admission'):
            run.require_exclusive(root)
