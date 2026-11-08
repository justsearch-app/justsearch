"""Ordered frozen admission through the public CLI; all workers/data here are synthetic.

The planner runs normally across serialization and result attribution. Only external
providers (datasets, frozen copies, installed runtime and admission) are replaced;
this is interface evidence, never a quality/performance verdict.
"""
from contextlib import contextmanager
from dataclasses import replace
import itertools
import json
import os
from pathlib import Path
import subprocess
import sys
from types import SimpleNamespace

import pytest
from click.testing import CliRunner
from jseval import backend
from jseval.cli import main
from jseval.quality import collections, compare, index, run
from jseval.quality.types import Outcome, RunRecord, sha256_text
from .helpers import config_for, make_collection, make_run

DEFAULTS = ('scifact', 'miracl-de', 'real-use', 'enron-email')
ORDER = ('real-use', 'scifact', 'miracl-de', 'enron-email')
MODELS = {'embed': 'fixture-embed', 'reranker': 'fixture-reranker'}


def install(tmp, monkeypatch):
    """Model-free providers usable also by the separate public CLI process."""
    configs = {a: collections.load_config(a) for a in DEFAULTS}
    members = {a: make_collection(name=a, groups={'en/vague': n+1}) for n, a in enumerate(DEFAULTS)}
    seen = SimpleNamespace(inspected=[], compared=[], retained=[], plans=[], totals={}, startups={},
                           failure=None, allowance=0, tick=0, footprint=100, free=10000)
    monkeypatch.setattr(backend, 'source_identity', lambda _: ('a'*40, None))
    monkeypatch.setattr(run.platform, 'processor', lambda: 'fixture CPU')
    monkeypatch.setattr(backend, 'bind_command', lambda _, cmd: cmd)
    monkeypatch.setattr(run.distribution, 'current', lambda *_: None)
    monkeypatch.setattr(collections, 'build', lambda cfg: members[cfg.collection])
    # Keep the real alias/path resolver and config loader to test canonical authority.
    # Ordinary canonical aliases still build through this isolated dataset provider.
    monkeypatch.setattr(run.known_answers, 'check', lambda *_: SimpleNamespace(pending=0))
    monkeypatch.setattr(collections, 'scored_collection', lambda c, _: c)
    monkeypatch.setattr(index.IndexConfig, 'environment', lambda _: {'JUSTSEARCH_GPU_ENABLED': 'true',
                                                                  'JUSTSEARCH_MODELS_DIR': str(tmp/'models')})
    monkeypatch.setattr(run, 'estimate_seconds', lambda c, *_: seen.totals.get(c.name, 30))
    monkeypatch.setattr(run, 'estimate_startup_seconds', lambda c, *_: seen.startups.get(c.name, 10))
    monkeypatch.setattr(run, '_history', lambda *_: [('now', 30, {'gpu_engine_mib': seen.footprint})])
    monkeypatch.setattr(run, 'gpu_memory', lambda _: (seen.free, None))
    monkeypatch.setattr(index, 'model_hashes', lambda *_a, **_kw: MODELS)
    # A local clock measures the same chosen inspect allowance for every member.
    def clock():
        return seen.tick
    monkeypatch.setattr(run.time, 'monotonic', clock)
    def inspect(root, c, cfg, baseline):
        seen.inspected.append(c.name)
        seen.tick += seen.allowance
        assert baseline.name == sha256_text(c.name)+'.baseline.json'
        return MODELS
    monkeypatch.setattr(run.frozen_index, 'inspect', inspect)
    monkeypatch.setattr(run.frozen_index, 'check_run_destination', lambda *_: None)
    @contextmanager
    def resources(root, loaded):
        seen.retained.append([c.name for c, *_ in loaded])
        yield ()
        seen.retained.append('released')
    monkeypatch.setattr(run.frozen_index, 'run_resources', resources)
    def baseline(path):
        alias = next(a for a in DEFAULTS if Path(path).stem == sha256_text(a)+'.baseline')
        return make_run(members[alias], model_hashes=MODELS), {}
    monkeypatch.setattr(compare, 'load_baseline', baseline)
    monkeypatch.setattr(compare, 'validate_run', lambda *_a, **_kw: [])
    # This fixture supplies worker output, not ownership/evidence validation;
    # unchanged frozen reader suites exercise real retained sidecars separately.
    monkeypatch.setattr(compare, 'load_run_or_reason', lambda path, arm:
        (RunRecord.loads(Path(path).read_text()), []) if Path(path).exists() else (None, ['absent '+arm]))
    monkeypatch.setattr(run.frozen_index, 'validated_pair',
        lambda path, base: (*baseline(base), RunRecord.loads(Path(path).read_text()), object()))
    def comparison(c, qc, b, record, **kw):
        assert qc == configs[c.name], 'stored config replaced canonical comparison authority'
        assert b.header.collection == c.name
        assert kw['expect_models'] == MODELS
        if record:
            assert record.header.collection_version == c.version
            assert record.header.collection == c.name
        seen.compared.append(c.name)
        return SimpleNamespace(verdict=SimpleNamespace(outcome=Outcome.INVALID if kw['invalid'] else Outcome.PASS),
                               name=c.name)
    monkeypatch.setattr(compare, 'compare', comparison)
    monkeypatch.setattr(run.report, 'render', lambda r: r.name)
    monkeypatch.setattr(run.report, 'outcome_line', lambda r: r.name)
    monkeypatch.setattr(run.report, 'run_measurements', lambda *_: {})
    monkeypatch.setattr(run.report, 'measurement_text', lambda *_: '')
    monkeypatch.setattr(run.report, 'timing_text', lambda *_: '')
    def launch(cmd, **kw):
        assert cmd[2] == 'exclusive' and cmd.count('--timeout') == 1
        assert float(cmd[cmd.index('--timeout')+1]) == 600
        assert kw['pass_fds'] == ()
        plan = json.loads(Path(cmd[-1]).read_text())
        seen.plans.append(plan)
        for item in plan['items']:
            c = collections.load_collection(item['collection'])
            n = DEFAULTS.index(c.name)
            if seen.failure == (c.name, 'missing'):
                continue
            record = make_run(c, commit='a'*40, request_config=run.refined_request(), model_hashes=MODELS,
                              run_kind='frozen-index', index_build='cached', index_cache_key='idx-1',
                              identity={'runtime':'a'*64}, runtime_identity={'runtime':'b'*64},
                              frozen_index={'baseline_run_sha256':'c'*64, 'evidence_sha256':'d'*64})
            record.write(Path(item['run']))
            if seen.failure != (c.name, 'unfinished'):
                run._write_json(Path(item['done']), {'elapsed_seconds': n+1,
                    'error': 'fixture '+seen.failure[1] if seen.failure and seen.failure[0] == c.name else None})
        return SimpleNamespace(wait=lambda: 0)
    monkeypatch.setattr(run.subprocess, 'Popen', launch)
    return seen, members


def invoke(tmp, refs=(), **options):
    return run.execute('check', refs, frozen_indexes=tmp/'copies', baseline_dir=tmp/'baselines',
                       runs_dir=tmp/'runs', **options)


@pytest.mark.parametrize('refs', [(*p, 'enron-email') for p in itertools.permutations(DEFAULTS[:3])])
def test_complete_order_public_cli_serialization_and_results(tmp_path, monkeypatch, refs):
    seen, members = install(tmp_path, monkeypatch)
    result = CliRunner().invoke(main, ['quality', 'check', *refs, '--frozen-indexes', str(tmp_path/'copies'),
        '--baseline-dir', str(tmp_path/'baselines'), '--runs-dir', str(tmp_path/'runs')])
    assert result.exit_code == 0, result.output or repr(result.exception)
    assert seen.inspected == list(refs) == seen.compared
    assert seen.retained == [list(refs), 'released']
    plan = seen.plans[0]
    assert plan['budget'] == 600 and plan['request'] == run.refined_request()
    for n, item in enumerate(plan['items']):
        assert item['number'] == n and item['name'] == refs[n]
        assert collections.load_collection(item['collection']).version == members[refs[n]].version
        assert Path(item['collection']).parent.name == str(n)
        for field in ('run', 'ready', 'go', 'done'):
            assert Path(item[field]).parent == Path(item['collection']).parent
        assert item['baseline'] == str((tmp_path/'baselines'/(sha256_text(refs[n])+'.baseline.json')).resolve())
        assert run._config(item['index_config']).documents_dir is None
        assert Path(item['run']).with_suffix('.md').read_text() == refs[n]
    summary = json.loads((Path(plan['path']).parent/'summary.json').read_text())
    assert summary['outcome'] == 'PASS'
    assert summary['collection_seconds'] == {a: DEFAULTS.index(a)+1 for a in refs}


def test_order_at_public_cli_in_separate_process(tmp_path):
    code = '''
import sys,pytest
from pathlib import Path
from tests.quality.test_frozen_schedule import install
from jseval.cli import main
patch=pytest.MonkeyPatch()
install(Path(sys.argv.pop(1)),patch)
main()
'''
    env = dict(os.environ)
    env['PYTHONPATH'] = os.pathsep.join([str(Path(__file__).resolve().parents[2]), env.get('PYTHONPATH','')])
    proc = subprocess.run([sys.executable, '-c', code, str(tmp_path), 'quality', 'check', *ORDER,
        '--frozen-indexes', str(tmp_path/'copies'), '--baseline-dir', str(tmp_path/'baselines'),
        '--runs-dir', str(tmp_path/'runs')], env=env, capture_output=True, text=True, timeout=30)
    assert proc.returncode == 0, proc.stdout+proc.stderr
    plans = list((tmp_path/'runs').glob('*/plan.json'))
    assert len(plans) == 1 and [i['name'] for i in json.loads(plans[0].read_text())['items']] == list(ORDER)


@pytest.mark.parametrize('refs', [DEFAULTS[:-1], (*DEFAULTS, 'scifact'), (*DEFAULTS, 'unknown'),
    ('SCIFACT', *DEFAULTS[1:]), ('./scifact', *DEFAULTS[1:]), ('file:scifact', *DEFAULTS[1:]),
    ('miracl-fr', *DEFAULTS[1:]), ('real-use-slice', *DEFAULTS[1:]),
    ('enron-email', *DEFAULTS[:-1]), ('unknown', *DEFAULTS[1:]),
    ('scifact', 'scifact', 'real-use', 'enron-email')])
def test_preserve_incomplete_noncanonical_selections_refuse_early(tmp_path, monkeypatch, refs):
    monkeypatch.setattr(run.frozen_index, 'inspect', lambda *_: pytest.fail('invalid selection inspected'))
    monkeypatch.setattr(backend, 'source_identity', lambda *_: pytest.fail('invalid selection reached source/runtime'))
    with pytest.raises(run.RunRefused):
        invoke(tmp_path, refs)
    assert not list(tmp_path.iterdir())


@pytest.mark.parametrize('override', [{'full_suite': True}, {'mode': 'text'}, {'documents_dir': Path('/tmp/docs')},
    {'sets': ('JUSTSEARCH_GPU_ENABLED=false',)}, {'cpus': '0'}, {'sample': 1}, {'seed': 1}])
def test_preserve_other_frozen_overrides_refuse_early(tmp_path, monkeypatch, override):
    monkeypatch.setattr(backend, 'source_identity', lambda *_: pytest.fail('invalid override reached source/runtime'))
    with pytest.raises(run.RunRefused):
        invoke(tmp_path, (), **override)
    assert not list(tmp_path.iterdir())


@pytest.mark.parametrize('action', ['baseline', 'ablate', 'sensitivity'])
def test_preserve_other_actions_refuse(tmp_path, action):
    with pytest.raises(run.RunRefused):
        run.execute(action, (), frozen_indexes=tmp_path/'copies', baseline_dir=tmp_path, runs_dir=tmp_path)


@pytest.mark.parametrize('action', ['full-suite', 'baseline', 'ablate', 'sensitivity', 'prepare'])
def test_preserve_other_cli_actions_reject_frozen_option(action):
    result = CliRunner().invoke(main, ['quality', action, '--frozen-indexes', 'copies'])
    assert result.exit_code == 2 and 'No such option' in result.output


def test_default_canonical_authority_survives_same_name_stored_shadow(tmp_path, monkeypatch):
    seen, members = install(tmp_path, monkeypatch)
    monkeypatch.chdir(tmp_path)
    shadow = collections.write_collection(members['scifact'], tmp_path/'scifact')
    cfg = json.loads((collections.CONFIG_DIR/'scifact.json').read_text())
    cfg['margins'] = {'default': .99}
    cfg['groups'] = ['kind']
    cfg['pending_kinds'] = ['vague']
    (shadow/'quality.config.json').write_text(json.dumps(cfg))
    # Ordinary paths keep stored config authority, independently of frozen admission.
    stored, qc = collections.resolve_collection(str(shadow))
    assert stored.version == members['scifact'].version and qc.margins == {'default': .99}
    outcome, _ = invoke(tmp_path)
    assert outcome == Outcome.PASS and seen.inspected == list(DEFAULTS)
    with pytest.raises(run.RunRefused):
        invoke(tmp_path, (str(shadow), *DEFAULTS[1:]))


def test_noargument_and_explicit_default_plan_meaning(tmp_path, monkeypatch):
    seen, _ = install(tmp_path, monkeypatch)
    invoke(tmp_path)
    # Explicit support is new; the noargument ordering/estimate is preserved.
    invoke(tmp_path, DEFAULTS)
    a, b = seen.plans
    assert [i['name'] for i in a['items']] == [i['name'] for i in b['items']] == list(DEFAULTS)
    assert a['estimate_seconds'] == b['estimate_seconds'] and a['overlap'] == b['overlap']


def test_default_snapshot_is_captured_once(tmp_path, monkeypatch):
    seen, _ = install(tmp_path, monkeypatch)
    calls = []
    def defaults(full=False):
        calls.append(full)
        return DEFAULTS if len(calls) == 1 else DEFAULTS[:-1]
    monkeypatch.setattr(run, 'default_collections', defaults)
    invoke(tmp_path)
    assert calls == [False] and seen.inspected == list(DEFAULTS)


def test_preserve_pending_default_meaning(tmp_path, monkeypatch):
    seen, _ = install(tmp_path, monkeypatch)
    load = collections.load_config
    monkeypatch.setattr(collections, 'load_config', lambda ref: replace(load(ref), pending_kinds=('topical',))
                        if ref == 'real-use' else load(ref))
    invoke(tmp_path)
    assert seen.inspected == list(run.DEFAULT_COLLECTIONS)
    with pytest.raises(run.RunRefused):
        invoke(tmp_path, ORDER)


@pytest.mark.parametrize('position', [0, 1, 3])
@pytest.mark.parametrize('failure', ['startup', 'query', 'cleanup', 'evidence', 'deadline', 'missing', 'unfinished'])
def test_ordered_member_failure_cannot_be_successful_partial(tmp_path, monkeypatch, position, failure):
    seen, _ = install(tmp_path, monkeypatch)
    seen.failure = (ORDER[position], failure)
    outcome, _ = invoke(tmp_path, ORDER)
    assert outcome == Outcome.INVALID
    assert seen.compared == list(ORDER)
    assert json.loads((Path(seen.plans[0]['path']).parent/'summary.json').read_text())['outcome'] == 'INVALID'


@pytest.mark.parametrize('budget', [45, 601, float('nan'), float('inf')])
def test_preserve_invalid_window_refuses_without_output(tmp_path, monkeypatch, budget):
    seen, _ = install(tmp_path, monkeypatch)
    with pytest.raises(run.RunRefused, match='window'):
        invoke(tmp_path, window_seconds=budget)
    assert not seen.inspected and not (tmp_path/'runs').exists()


@pytest.mark.parametrize('free,footprint,total,expected', [(10000,100,30,True), (None,100,30,False),
    (1,100,30,False), (10000,None,30,False), (10000,0,30,False), (None,100,200,None),
    (10000,100,float('inf'),None), (10000,100,float('nan'),None)])
def test_preserve_overlap_evidence_and_serial_budget(tmp_path, monkeypatch, free, footprint, total, expected):
    seen, _ = install(tmp_path, monkeypatch)
    seen.free, seen.footprint = free, footprint
    seen.totals = dict.fromkeys(DEFAULTS, total)
    if expected is None:
        with pytest.raises(run.RunRefused, match='estimate'):
            invoke(tmp_path)
        assert not seen.plans and not (tmp_path/'runs').exists()
    else:
        invoke(tmp_path)
        assert seen.plans[0]['overlap'] is expected
        assert seen.plans[0]['estimate_seconds'] == (155 if expected else 185)


def test_same_history_and_measured_allowances_fit_only_chosen_order(tmp_path, monkeypatch):
    seen, _ = install(tmp_path, monkeypatch)
    seen.totals = {'scifact':219.59106951875074, 'miracl-de':211.56785927375128,
                   'enron-email':60.66988024125337, 'real-use':244.92322448125063}
    seen.startups = {'scifact':129.9676277774961, 'miracl-de':123.50600128374936,
                    'enron-email':38.60209421374748, 'real-use':68.50391849375228}
    seen.allowance = 2  # measured 3*2=6 seconds, above the unchanged 5s minimum
    with pytest.raises(run.RunRefused, match='estimate.*budget'):
        invoke(tmp_path, DEFAULTS)
    assert not seen.plans and not (tmp_path/'runs').exists()
    invoke(tmp_path, ORDER)
    plan = seen.plans[0]
    assert plan['estimate_seconds'] == pytest.approx(541.5588697825078)
    assert plan['estimate_seconds'] == run.estimate_window([seen.totals[a]+6 for a in ORDER],
                                                         [seen.startups[a] for a in ORDER], True)


WORKER = r'''
import json,sys,time
from pathlib import Path
item=json.loads(sys.argv[1]); deadline=float(sys.argv[2]); event=Path(sys.argv[3]); failure=sys.argv[4]
def emit(phase):
 with event.open('a') as f: f.write(json.dumps([item['name'],phase,deadline])+'\n')
emit('start')
if failure == item['name']+':startup': sys.exit(1)
Path(item['ready']).touch()
limit=time.monotonic()+3
while not Path(item['go']).exists():
 if time.monotonic()>limit: sys.exit(2)
 time.sleep(.005)
emit('go')
Path(item['done']).write_text(json.dumps({'error': 'fixture failure' if failure.startswith(item['name']+':') else None}))
emit('done')
'''


@pytest.mark.parametrize('failure', ['', 'real-use:startup', 'scifact:query', 'enron-email:cleanup'])
def test_preserve_real_model_free_driver_order_single_deadline_and_overlap_loss(tmp_path, monkeypatch, failure):
    children = []
    original = subprocess.Popen
    event = tmp_path/'events.jsonl'
    items = [{'number': n, 'name': a, **{k:str(tmp_path/(str(n)+'-'+k)) for k in ('ready','go','done')}}
             for n,a in enumerate(ORDER)]
    def launch(command, **kw):
        n = int(command[-2])
        child = original([sys.executable, '-c', WORKER, json.dumps(items[n]), command[-1], str(event), failure])
        children.append(child)
        return child
    monkeypatch.setattr(run, 'require_exclusive', lambda _: None)
    monkeypatch.setattr(run.subprocess, 'Popen', launch)
    # Planning allowed overlap, but live GPU evidence is lost before each next startup.
    monkeypatch.setattr(run, 'gpu_memory', lambda _: (None, None))
    try:
        plan = {'path':str(tmp_path/'plan.json'), 'budget':50, 'items':items,
                'overlap':True, 'gpu_engine_mib':100}
        if failure:
            with pytest.raises(run.RunRefused, match='backend did not become ready|fixture failure'):
                run._window(plan)
        else:
            run._window(plan)
        rows = [json.loads(s) for s in event.read_text().splitlines()]
        assert len({row[2] for row in rows}) == 1, rows
        starts = [row[0] for row in rows if row[1] == 'start']
        assert starts == list(ORDER[:len(starts)])
        if not failure:
            assert [(r[0],r[1]) for r in rows] == [(a,p) for a in ORDER for p in ('start','go','done')]
            assert all(Path(i['done']).exists() for i in items)
    finally:
        for child in children:
            if child.poll() is None:
                child.terminate()
            child.wait(timeout=5)
