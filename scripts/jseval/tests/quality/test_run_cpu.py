"""Real CPU acceptance. Each tree builds in its own folder, never the shared stack.

JSEVAL_QUALITY_RUN_REAL=1 enables the expensive check. JSEVAL_QUALITY_ENGINE_ROOT
may select a clean shared before-clone, to exercise an unchanged engine while the
runner itself is uncommitted. It must have its own prepared installDist receipt.
"""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
from dataclasses import replace

import httpx
import pytest

from jseval import backend
from jseval.quality import collections, compare, index, run
from jseval.quality.types import Collection, QueryText, Outcome, RunRecord, Served, document_text, sha256_text

pytestmark = pytest.mark.skipif(os.environ.get('JSEVAL_QUALITY_RUN_REAL') != '1',
                                reason='set JSEVAL_QUALITY_RUN_REAL=1 for isolated CPU acceptance')


def test_real_cpu_baseline_check_errors_and_window(monkeypatch):
    import ir_datasets
    from jseval._paths import shared_models_dir
    engine = Path(os.environ.get('JSEVAL_QUALITY_ENGINE_ROOT', index.REPO_ROOT))
    assert backend.source_identity(engine)[1] is None, 'real acceptance needs a clean engine checkout'
    backend.require_prepared(engine)
    # The clean checkout supplies engine binaries; exercise this harness's
    # admission implementation even when that checkout predates the new caps.
    bind_command = backend.bind_command
    admission_under_test = Path(__file__).resolve().parents[4]/'scripts/dev/admission.py'
    def bind_current_admission(repo, command):
        command = list(command)
        if len(command) > 1 and command[1] == str(engine/'scripts/dev/admission.py'):
            command[1] = str(admission_under_test)
        return bind_command(repo, command)
    monkeypatch.setattr(backend, 'bind_command', bind_current_admission)
    root = Path(tempfile.mkdtemp(prefix='lane-c-real-', dir=Path.home()/'.cache/justsearch-quality'))
    monkeypatch.setenv('JS_ADMISSION_DIR', str(root/'admission'))
    monkeypatch.delenv('JS_ADMISSION_TOKEN', raising=False)
    monkeypatch.setenv('JUSTSEARCH_DEV_RUNNER_STATE_ROOT', str(root/'register'))
    monkeypatch.setenv('JUSTSEARCH_JOB_RECORD_DIR', str(root/'jobs'))
    monkeypatch.setenv('JUSTSEARCH_GPU_ENABLED', 'false')
    models = shared_models_dir()
    ds = ir_datasets.load('beir/scifact/test')
    docs = sorted(ds.docs_iter(), key=lambda d: (-len(d.text), d.doc_id))[:8]
    queries = {f'q{i}': QueryText(q, {'lang':'en','kind':'vague'}) for i,q in enumerate([
        'scientific evidence', 'immune response to human infection', 'cell growth cancer genes',
        '"unterminated', '"immune response"'])}
    c = Collection('scifact-cpu-quick', 'en', queries,
        {q:{docs[0].doc_id:1} for q in queries},
        {d.doc_id:f'docs/{d.doc_id}.txt' for d in docs},
        {d.doc_id:sha256_text(document_text(d.title,d.text)) for d in docs},
        {'source':{'dataset':'beir/scifact/test'}})
    folder = collections.write_collection(c, root/'collection')
    (folder/'quality.config.json').write_text(json.dumps({
        'schema':'jseval.quality.config.v1','collection':c.name,'language':'en',
        'source':{'kind':'ir_datasets','dataset':'beir/scifact/test'},
        'judgments':'official','expected':c.counts(),'bootstrap':{'resamples':1000,'seed':1}}))
    cfg = index.IndexConfig(repo_root=engine, models_dir=models, cache_root=root/'indexes', timeout_sec=600)
    # Preparation is outside the exclusive window; only its private pausable worker builds.
    preparation_times = {}
    for kind in ('fresh', 'cached'):
        start = time.monotonic()
        with index.prepare_index(c,cfg) as handle:
            assert handle.index_build == kind
            handle.wait()
            assert index.confirm(handle).ok
        preparation_times[kind] = time.monotonic()-start
    admission = engine/'scripts/dev/admission.py'
    def status():
        return json.loads(subprocess.check_output([sys.executable,str(admission),'status'],text=True))
    assert status() == []
    # Observe real admission from each backend start, via a separate process watcher.
    watcher_script = '''
import json,subprocess,sys,time
from pathlib import Path
while not Path(sys.argv[3]).exists():
 rows=json.loads(subprocess.check_output([sys.executable,sys.argv[1],'status'],text=True))
 if any(r.get('mode')=='exclusive' for r in rows): Path(sys.argv[2]).touch()
 time.sleep(.05)
'''
    watcher = subprocess.Popen([sys.executable,'-c',watcher_script,str(admission),str(root/'seen-exclusive'),str(root/'stop-watcher')])
    options = dict(baseline_dir=root/'baselines',runs_dir=root/'runs',cache_root=cfg.cache_root,
                   models_dir=models,repo_root=engine,window_seconds=180)
    try:
        outcome,line = run.execute('baseline',(str(folder),),**options)
        assert outcome == Outcome.PASS,line
        assert status() == []
        outcome,line = run.execute('check',(str(folder),),**options)
        assert outcome == Outcome.PASS,line
        assert status() == []
        assert (root/'seen-exclusive').exists()
        record_path = sorted((root/'runs').glob('*/0/run.jsonl'))[-1]
        record = RunRecord.read(record_path)
        assert record.header.request_config == run.refined_request()
        assert all(r.error is None for r in record.results)
        syntax = next(r for r in record.results if r.qid == 'q3')
        phrase = next(r for r in record.results if r.qid == 'q4')
        assert compare.syntax_refused(syntax), syntax
        # With no matching candidates the engine names the rerank threshold,
        # while dense retrieval was independently skipped as sparse-shortcut.
        assert phrase.served.legs == ('lexical',), phrase
        assert (phrase.served.reranker, phrase.served.reason) == ('skipped', 'BELOW_MIN_THRESHOLD'), phrase
        assert phrase.served.leg_details['dense'] == {'status': 'skipped', 'reason': 'sparse-shortcut'}, phrase
        summary = json.loads(record_path.parents[1].joinpath('summary.json').read_text())
        assert summary['syntax_refusals'][c.name] == ['q3']
        qc = collections.resolve_collection(folder)[1]
        # An actual HTTP 400 from the CPU engine, passed through the same collector.
        # Inject at the request boundary, not by editing the resulting RunRecord.
        # Injection command runs its own real prepared backend within the reservation.
        script = '''
import json,sys
from pathlib import Path
from dataclasses import replace
import httpx
from jseval.quality import run,index,collections
from jseval.quality.types import QueryResult,Served
cfg=run._config(json.loads(Path(sys.argv[1]).read_text()))
c=collections.load_collection(Path(sys.argv[2]))
with index.prepare_index(c,cfg,prepared_only=True) as h:
 h.wait(prepared_only=True)
 with httpx.Client(base_url=h.base_url) as client:
  r=run.search_query(client,h,'q0',replace(c.queries['q0'],text=''),run.refined_request(),20)
  assert r.error and '400' in r.error,r
  Path(sys.argv[3]).write_text(json.dumps(r.to_dict()))
  # A response-boundary deadline injection preserves a real engine result/trace.
  wire=client.post('/api/knowledge/search',json={**run.refined_request(),'query':c.queries['q1'].text}).json()
  for stage in wire['searchTrace']['stages']:
   if stage['id']=='cross-encoder': stage.update(status='skipped',reason='SEARCH_DEADLINE_EXCEEDED')
  with httpx.Client(base_url=h.base_url,transport=httpx.MockTransport(lambda _:httpx.Response(200,json=wire))) as injected:
   r=run.search_query(injected,h,'q1',c.queries['q1'],run.refined_request(),20)
  Path(sys.argv[4]).write_text(json.dumps(r.to_dict()))
'''
        (root/'cfg.json').write_text(json.dumps(run._serial_config(cfg)))
        code = subprocess.run([sys.executable,str(admission),'exclusive','--purpose','quality CPU error injection',
            '--timeout','180','--',sys.executable,'-c',script,str(root/'cfg.json'),str(folder),str(root/'400.json'),str(root/'deadline.json')],
            capture_output=True,text=True,timeout=310)
        assert code.returncode == 0,code.stdout+code.stderr
        from jseval.quality.types import QueryResult
        for filename,qid in [('400.json','q0'),('deadline.json','q1')]:
            injected = QueryResult.from_dict(json.loads((root/filename).read_text()))
            broken = replace(record,results=tuple(injected if r.qid==qid else r for r in record.results))
            result=compare.compare(c,qc,record,broken)
            assert result.verdict.outcome == Outcome.INVALID
            assert any(qid in s for s in result.verdict.reasons)
        assert status() == []
        # Deliberate absent prepared population: no launcher/build is reached.
        with pytest.raises((index.IndexRefused,RuntimeError),match='not ready'):
            index.prepare_index(replace(c,name='missing-prepared-index',version=''),cfg,prepared_only=True)
        with pytest.raises(run.RunRefused,match='estimate.*budget'):
            run.require_budget(181,180)
        print('CPU_ACCEPTANCE',root,line)
        print('CPU_PREPARATION_SECONDS',preparation_times)
        for summary in sorted((root/'runs').glob('*/summary.json')):
            print(summary.read_text())
    finally:
        (root/'stop-watcher').touch()
        watcher.wait(timeout=10)
    assert status() == []
