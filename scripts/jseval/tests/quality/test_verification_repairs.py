"""Regressions reported by the independent core verification (2026-11-03)."""
from dataclasses import replace
import fcntl
import contextlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
from unittest.mock import Mock

import pytest

from .conftest import ready_distribution
from click.testing import CliRunner

from jseval import _paths, backend
from jseval.cli import main
from jseval.quality import collections, compare, index, report, run
from jseval.quality.types import Outcome
from .helpers import config_for, make_collection, make_run
from .test_index import collection, config, env_for


def test_pooled_judgment_requests_preview_output_and_name_every_pair_in_report(tmp_path):
    c = make_collection()
    base = make_run(c)
    cand = replace(base, results=tuple(replace(r, ranked=r.ranked[:2] + tuple(
        d for d in sorted(c.documents, reverse=True) if d not in c.judgments[r.qid])[:6])
        for r in base.results))
    cmp = compare.compare(c, config_for(c, judgments='pooled'), base, cand)
    assert cmp.judgment_requests
    line, text = report.outcome_line(cmp), report.render(cmp)
    for qid, doc in cmp.judgment_requests:
        for arm, record in [('baseline', base), ('candidate', cand)]:
            if doc in next(r for r in record.results if r.qid == qid).ranked[:10]:
                assert f'| {qid} | {doc} | {arm} |' in text
    assert '\n' not in line
    assert len(line) < 1500
    assert str(len(list(report.judgment_rows(cmp)))) in line
    official = compare.compare(c, config_for(c, judgments='official'), base, cand)
    assert 'unjudged@10' in report.outcome_line(official)
    assert 'needs judgment:' not in report.outcome_line(official)
    folder = collections.write_collection(c, tmp_path/'collection')
    config = {'schema': collections.CONFIG_SCHEMA, 'collection': c.name, 'language': c.language,
              'source': {'kind': 'synthetic'}, 'judgments': 'pooled', 'expected': c.counts(),
              'bootstrap': {'resamples': 1000, 'seed': 1}}
    (folder/'quality.config.json').write_text(json.dumps(config))
    base.write(tmp_path/'base.jsonl')
    compare.write_baseline(tmp_path/'base.jsonl', c, tmp_path/'base.baseline.json')
    cand.write(tmp_path/'candidate.jsonl')
    output = CliRunner().invoke(main, ['--json', 'quality', 'compare', str(folder),
        '--baseline', str(tmp_path/'base.baseline.json'), '--candidate', str(tmp_path/'candidate.jsonl'),
        '--expect-commit', base.header.commit])
    assert output.exit_code == 2, output.output
    data = json.loads(output.output)
    assert data['unjudged'] == cmp.unjudged
    assert {(row['qid'], row['doc_id'], row['arm']) for row in data['judgment_requests']} == set(
        report.judgment_rows(cmp))


@pytest.mark.parametrize('real_admission', [False, True])
def test_admission_refusal_is_the_command_outcome(tmp_path, monkeypatch, real_admission):
    c = make_collection()
    r = make_run(c, request_config=run.refined_request())
    monkeypatch.setattr(backend, 'source_identity', lambda _: (r.header.commit, None))
    monkeypatch.setattr(backend, 'require_prepared', lambda *a: None)
    ready_distribution(monkeypatch)
    monkeypatch.setattr(backend, 'bind_command', lambda _, command: command)
    monkeypatch.setattr(run.platform, 'processor', lambda: 'test CPU')
    monkeypatch.setattr(run.collections, 'resolve_collection', lambda _: (c, config_for(c)))
    monkeypatch.setattr(index.IndexConfig, 'environment', lambda _: {})
    monkeypatch.setattr(index, 'model_hashes', lambda _: dict(r.header.model_hashes))
    h = Mock(model_hashes=dict(r.header.model_hashes))
    h.__enter__ = Mock(return_value=h)
    h.__exit__ = Mock(return_value=False)
    monkeypatch.setattr(index, 'prepare_index', lambda *a, **kw: h)
    baseline = tmp_path/'baselines'/(run.sha256_text(c.name)+'.baseline.json')
    r.write(tmp_path/'baseline.jsonl')
    compare.write_baseline(tmp_path/'baseline.jsonl', c, baseline)
    def spawn(command, **kwargs):
        kwargs['stdout'].write('admission: drain timeout; holders: [{"purpose": "other owner"}]\n')
        kwargs['stdout'].flush()
        return Mock(wait=lambda: 75)
    admission = tmp_path/'admission'
    admission.mkdir()
    monkeypatch.setenv('JS_ADMISSION_DIR', str(admission))
    monkeypatch.delenv('JS_ADMISSION_TOKEN', raising=False)
    popen = subprocess.Popen
    def admit(command, **kwargs):
        command[command.index('--drain-timeout')+1] = '.1'
        return popen(command, **kwargs)
    monkeypatch.setattr(run.subprocess, 'Popen', admit if real_admission else spawn)
    with (admission/'work.lock').open('a+') as work:
        fcntl.flock(work, fcntl.LOCK_SH)
        outcome, line = run.execute('check', ('synthetic',), baseline_dir=baseline.parent,
                                    runs_dir=tmp_path/'runs')
    assert outcome is Outcome.INVALID
    assert 'drain timeout' in line and 'holders:' in line
    if not real_admission:
        assert 'other owner' in line
    assert 'absent run.jsonl' not in line
    assert len(line.splitlines()) == 1
    assert 'drain timeout' in next((tmp_path/'runs').glob('*/summary.md')).read_text()


@pytest.mark.parametrize('result', [0, 1])
def test_waiting_install_keeps_receipt_until_build_admitted(tmp_path, monkeypatch, result):
    (tmp_path/'.git').mkdir()
    dist = tmp_path/'modules/ui/build/install/ui'
    (dist/'bin').mkdir(parents=True)
    (dist/'bin/ui').touch()
    receipt = dist/'.jseval-source.json'
    receipt.write_text(json.dumps(['commit', None]))
    monkeypatch.setattr(backend, 'source_identity', lambda _: ('commit', None))
    @contextlib.contextmanager
    def admission(env):
        # A queued builder has not acquired permission to modify the install.
        backend.require_prepared(tmp_path)
        yield env
    monkeypatch.setattr(backend, '_machine_admission', admission)
    def build(*a, **kw):
        # Once admitted, even abrupt termination must leave no attestation.
        assert not receipt.exists()
        return Mock(returncode=result)
    monkeypatch.setattr(backend.subprocess, 'run', build)
    if result:
        with pytest.raises(RuntimeError, match='installDist failed'):
            backend._install_distribution(tmp_path, {})
        with pytest.raises(RuntimeError, match='not ready'):
            backend.require_prepared(tmp_path)
    else:
        backend._install_distribution(tmp_path, {})
        backend.require_prepared(tmp_path)


def test_enron_relative_recipe_resolves_from_current_checkout(tmp_path, monkeypatch):
    cfg = collections.load_config('enron-email')
    source = tmp_path/cfg.source['path']
    (source/'qrels').mkdir(parents=True)
    (source/'corpus.jsonl').write_text('{"_id":"mail","text":"mail body"}\n')
    (source/'queries.jsonl').write_text('{"_id":"q","text":"which mail"}\n')
    (source/'qrels/test.tsv').write_text('q\tmail\t1\n')
    monkeypatch.setattr(_paths, 'REPO_ROOT', tmp_path)
    monkeypatch.setattr(_paths, 'main_repo_root', lambda: tmp_path/'another-checkout')
    c, _ = collections.resolve_collection('enron-email')
    assert c.documents == {'mail': 'docs/mail.txt'}
    assert Path(c.metadata['source']['path']) == source


def test_prepared_check_works_while_another_prepare_waits_for_gradle(tmp_path, monkeypatch):
    """Real Gradle slot boundary, with a harmless gradlew standing in for compilation."""
    from .conftest import REAL
    slot = REAL['which']('js-gradle-slot')  # the real wrapper, not the quality tests' stand-in
    if slot is None:
        pytest.skip('js-gradle-slot is not installed')
    root = tmp_path/'repo'
    subprocess.run(['git', 'init', '-q', str(root)], check=True)
    subprocess.run(['git', '-c', 'user.name=Test', '-c', 'user.email=t@example.com',
                    'commit', '--allow-empty', '-qm', 'fixture'], cwd=root, check=True)
    (root/'.gitignore').write_text('.gradle/\nmodules/\nqueued\n')
    for relative in ('scripts/lib/tool-invoker.cjs', 'scripts/dev/admission.py'):
        destination = root/relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(index.REPO_ROOT/relative, destination)
    (root/'gradlew').write_text('#!/bin/sh\nexit 0\n')
    (root/'gradlew').chmod(0o755)
    subprocess.run(['git', 'add', '.'], cwd=root, check=True)
    subprocess.run(['git', '-c', 'user.name=Test', '-c', 'user.email=t@example.com',
                    'commit', '-qm', 'prepared engine fixture'], cwd=root, check=True)
    assert backend.source_identity(root)[1] is None
    dist = root/'modules/ui/build/install/ui'
    (dist/'bin').mkdir(parents=True)
    (dist/'bin/ui').touch()
    index._write(dist/'.jseval-source.json', list(backend.source_identity(root)))
    monkeypatch.setenv('JS_ADMISSION_DIR', str(tmp_path/'admission'))
    monkeypatch.setenv('JS_GRADLE_SLOT_DIR', str(tmp_path/'slots'))
    monkeypatch.setenv('JSEVAL_GRADLE_SLOT', slot)
    monkeypatch.delenv('JS_ADMISSION_TOKEN', raising=False)
    script = '''
import os,subprocess,sys
from pathlib import Path
from jseval import backend
root=Path(sys.argv[1])
original=subprocess.run
def launch(*a,**kw):
 if a[0][0]==os.environ['JSEVAL_GRADLE_SLOT']: (root/'queued').touch()
 return original(*a,**kw)
backend.subprocess.run=launch
backend._install_distribution(root,dict(os.environ))
'''
    (root/'.gradle').mkdir()
    with (root/'.gradle/agent-build.lock').open('a+') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        child = subprocess.Popen([sys.executable, '-c', script, str(root)],
                                 stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        try:
            until = time.monotonic()+10
            while not (root/'queued').exists():
                assert child.poll() is None
                assert time.monotonic()<until
                time.sleep(.02)
            backend.require_prepared(root)
        finally:
            fcntl.flock(lock, fcntl.LOCK_UN)
            output, _ = child.communicate(timeout=15)
        assert child.returncode == 0, output
    backend.require_prepared(root)


def test_documented_default_set_matches_command_help():
    text = (index.REPO_ROOT/'scripts/jseval/README.md').read_text()
    default = text.split('Omit the collection arguments')[1].split('Each needs')[0]
    assert 'SciFact, MIRACL de and Enron' in ' '.join(default.split())
    assert 'MIRACL fr' not in default
    help_text = CliRunner().invoke(main, ['quality', 'check', '--help'])
    assert help_text.exit_code == 0
    assert 'default: SciFact, MIRACL de, Enron' in help_text.output
    assert 'full-suite --window-seconds' in text


def test_killed_prepare_supervisor_cannot_publish(collection, config, tmp_path, monkeypatch):
    c, source = collection
    cfg = replace(config, repo_root=index.REPO_ROOT, documents_dir=source, cache_root=tmp_path/'cache')
    monkeypatch.setenv('JS_ADMISSION_DIR', str(tmp_path/'admission'))
    monkeypatch.delenv('JS_ADMISSION_TOKEN', raising=False)
    pinned = env_for(cfg)
    monkeypatch.setattr(index.IndexConfig, 'environment', lambda _: dict(pinned))
    monkeypatch.setattr(index, '_under_cache', lambda p: p)
    # Keep the real pausable supervisor and worker, replacing only engine calls.
    script = '''
import sys,signal
from pathlib import Path
from types import SimpleNamespace as NS
from jseval.quality import index as ix
def terminate(*_): raise SystemExit(143)
signal.signal(signal.SIGTERM,terminate)
root=Path(sys.argv[1])
ix.backend.start_backend=lambda **kw:NS(proc=NS(poll=lambda:None))
ix.backend.require_prepared=lambda *a:None
ix.backend.stop_backend=lambda *a,**kw:None
ix.add_watched_root=lambda *a:ix._write(Path(ix._json(root/'build.json')['data'])/'watched_roots.json',{'roots':[{'path':'docs'}]})
ix.witness=lambda *a:{'probes':[{'query':'query words'}],'generation':'g','counts':{}}
ix._canary_query=lambda *a:'query words'
ix.index_identity.compute_live_identity=lambda *a,**kw:NS(to_doc=lambda:{})
def confirm(h,entry=None):
 h.index_state={'settled':True,'index_population':h.collection.index_population()}
 return NS(ok=True,failures=[])
ix._confirm=confirm
try: ix._build(root)
finally: (root/'worker-ended').touch()
'''
    popen = subprocess.Popen
    def launch(command, **kw):
        if 'pausable' in command:
            command = command[:-5] + [sys.executable, '-c', script, command[-1]]
        return popen(command, **kw)
    monkeypatch.setattr(index.subprocess, 'Popen', launch)
    h = index.prepare_index(c, cfg)
    try:
        h.wait(deadline=time.monotonic()+15)
        h._worker.kill()
        h._worker.wait(timeout=5)
        with pytest.raises(index.IndexRefused, match='teardown failed'):
            h.close()
        until = time.monotonic()+5
        while not (h.root/'worker-ended').exists() and time.monotonic()<until:
            time.sleep(.02)
        assert (h.root/'worker-ended').exists(), 'orphan worker did not exit'
        assert not (h.root/'entry.json').exists(), 'failed prepare published an adoptable index'
    finally:
        (h.root/'stop').touch()
        if h._worker.poll() is None:
            h._worker.terminate()
            h._worker.wait(timeout=10)
        if not h._lock.closed:
            h._lock.close()
