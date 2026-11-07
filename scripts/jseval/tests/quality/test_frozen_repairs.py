"""Independent-boundary neighbours for predecessor ownership and saved producer details."""
from dataclasses import replace
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import pytest
from click.testing import CliRunner
from jseval.cli import main
from jseval.quality import frozen_index as frozen, index, run
from jseval.quality.types import RunRecord
from .test_frozen_index import source, retained_pair


def rehash(candidate, filename, mutate):
    path = candidate.parent/frozen.EVIDENCE/filename
    doc = json.loads(path.read_text()); mutate(doc); index._write(path, doc)
    sidecar = candidate.parent/frozen.SIDECAR
    side = json.loads(sidecar.read_text())
    side['files'][f'{frozen.EVIDENCE}/{filename}'] = hashlib.sha256(path.read_bytes()).hexdigest()
    index._write(sidecar, side)
    record = RunRecord.loads(candidate.read_text())
    replace(record, header=replace(record.header, frozen_index={**record.header.frozen_index,
        'evidence_sha256': hashlib.sha256(sidecar.read_bytes()).hexdigest()})).write(candidate)


@pytest.mark.parametrize('phase', ['before.json', 'after.json'])
@pytest.mark.parametrize('case', ['empty-details', 'key', 'binding', 'generation', 'building', 'migration',
                                'count', 'coverage', 'roots', 'canary', 'failure', 'reason', 'transient'])
def test_rehashed_saved_producer_contradictions_refuse(retained_pair, phase, case):
    _, baseline, candidate, _, _ = retained_pair
    frozen.validated_pair(candidate, baseline)
    def mutate(row):
        checks = row['checks']
        if case == 'empty-details': row['checks'] = {k: {'ok': True} for k in checks}
        elif case == 'key': checks['identity']['key'] = 'f'*64
        elif case == 'binding': checks['binding']['data_dir'] = '/unrelated-clone'
        elif case == 'generation': checks['generation']['activeGenerationId'] = 'foreign-generation'
        elif case == 'building': checks['generation']['buildingGenerationId'] = 'building'
        elif case == 'migration': checks['generation']['migrationState'] = 'MIGRATING'
        elif case == 'count': checks['counts']['embeddingDocCount'] = 999
        elif case == 'coverage': checks['counts']['chunkVectorCoveragePercent'] = None
        elif case == 'roots': checks['watched_roots']['roots'] = ['/foreign-root']
        elif case == 'canary': checks['canary']['stages'] = {}
        elif case == 'failure': row['failures'] = ['not actually successful']
        elif case == 'reason': checks['identity']['reason'] = 'identity unavailable'
        else: checks['identity']['transient'] = True
    rehash(candidate, phase, mutate)
    with pytest.raises((ValueError, KeyError, OSError)):
        frozen.validated_pair(candidate, baseline)


@pytest.mark.parametrize('location', ['manifest', 'ledger', 'archive', 'root', 'helper'])
@pytest.mark.parametrize('identity', ['matching', 'mismatched', 'unknown'])
def test_cli_preparation_refuses_live_external_predecessor_before_install(source, tmp_path, monkeypatch, location, identity):
    c, cfg, origin, baseline, _ = source
    helper = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)'])
    try:
        child = {'id': 'external-helper', 'kind': 'EXTRACTION', 'pid': helper.pid,
            'startedAt': datetime.fromtimestamp(__import__('psutil').Process(helper.pid).create_time(), timezone.utc).isoformat().replace('+00:00','Z'),
            'executable': str(Path(f'/proc/{helper.pid}/exe').readlink()),
            'processIdentity': {'kind': 'linux-proc-v1', 'pid': helper.pid,
                'bootId': Path('/proc/sys/kernel/random/boot_id').read_text().strip(),
                'startTicks': index._process(helper.pid)['birth']}}
        if identity == 'mismatched': child['processIdentity']['startTicks'] = str(int(child['processIdentity']['startTicks'])+1)
        if identity == 'unknown': child.pop('processIdentity')
        runtime = origin/'data/runtime'; runtime.mkdir()
        if location in ('manifest', 'root'):
            manifest = {'schemaVersion': 2, 'pid': 2147483646, 'startedAt': '2000-01-01T00:00:00Z', 'children': [child]}
            if location == 'root': manifest.update(pid=helper.pid, startedAt=child['startedAt'])
            index._write(runtime/'manifest.json', manifest)
        else:
            ledger = runtime/'exit-ledger'; ledger.mkdir()
            writer = {'role': 'engine', 'pid': 2147483646, 'startedAt': '2026-01-01T00:00:00Z'}
            rows = [{'schemaVersion': 1, 'type': 'child-spawn', 'seq': 1, 'writer': writer,
                     'childId': 'external-helper', 'kind': 'EXTRACTION'},
                    {'schemaVersion': 1, 'type': 'child-start', 'seq': 2, 'writer': writer,
                     'childId': 'external-helper', **{k: v for k,v in child.items() if k not in ('id','kind','startedAt')},
                     'processStartedAt': child['startedAt']}]
            if location == 'helper':
                rows[1]['type'] = 'helper-start'
                rows[1]['helperId'] = rows[1].pop('childId')
                rows[1]['purpose'] = 'fixture-helper'
                rows[1].pop('processStartedAt')
            path = ledger/('children.v1.19.jsonl' if location == 'archive' else 'children.v1.jsonl')
            path.write_text(''.join(json.dumps(row)+'\n' for row in rows))
        before = frozen.inventory(origin)
        installations = []
        select = index._select_distribution
        monkeypatch.setattr(index, '_select_distribution', lambda *a: installations.append('install') or select(*a))
        result = CliRunner().invoke(main, ['quality','prepare-frozen','--baseline-dir',str(baseline.parent),
            '--cache-root',str(cfg.cache_root),'--output',str(tmp_path/'refused'),'--models-dir',str(cfg.models_dir)])
        assert result.exit_code == 2, result.output
        assert not installations, 'live predecessor reached runtime installation before ownership refusal'
        assert 'predecessor' in result.output, result.output
        assert helper.poll() is None, 'foreign helper was signalled'
        assert frozen.inventory(origin) == before
    finally:
        helper.terminate(); helper.wait(timeout=5)


def test_dead_authentic_predecessor_is_observed_without_rewriting_source(source, tmp_path):
    c, cfg, origin, baseline, _ = source
    helper = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)'])
    try:
        child = {'id': 'ended-helper', 'kind': 'EXTRACTION', 'pid': helper.pid,
            'startedAt': datetime.fromtimestamp(__import__('psutil').Process(helper.pid).create_time(), timezone.utc).isoformat().replace('+00:00','Z'), 'executable': str(Path(f'/proc/{helper.pid}/exe').readlink()),
            'processIdentity': {'kind': 'linux-proc-v1', 'pid': helper.pid,
                'bootId': Path('/proc/sys/kernel/random/boot_id').read_text().strip(), 'startTicks': index._process(helper.pid)['birth']}}
    finally: helper.terminate(); helper.wait(timeout=5)
    runtime = origin/'data/runtime'; runtime.mkdir()
    index._write(runtime/'manifest.json', {'schemaVersion': 2, 'pid': 2147483646,
        'startedAt': '2000-01-01T00:00:00Z', 'children': [child]})
    before = frozen.inventory(origin)
    out = tmp_path/'copies'
    frozen.prepare(baseline.parent, cfg.cache_root, out, cfg, refs=[c.name])
    copy = json.loads((out/__import__('hashlib').sha256(c.name.encode()).hexdigest()/'copy.json').read_text())
    assert len(copy['predecessor_ownership']['processes']) == 2
    assert all(row['observation'] == 'gone' for row in copy['predecessor_ownership']['processes'])
    assert frozen.inventory(origin) == before


@pytest.mark.parametrize('case', ['manifest-json', 'children-shape', 'future-schema', 'ledger-json', 'ledger-torn', 'ledger-future', 'unreadable-runtime'])
def test_uncertain_predecessor_sources_refuse_before_install(source, tmp_path, monkeypatch, case):
    c, cfg, origin, baseline, _ = source
    runtime = origin/'data/runtime'; runtime.mkdir()
    if case.startswith('ledger'):
        ledger = runtime/'exit-ledger'; ledger.mkdir()
        raw = b'{bad}\n' if case == 'ledger-json' else b'{"schemaVersion":2}\n' if case == 'ledger-future' else b'{"schemaVersion":1}'
        (ledger/'children.v1.23.jsonl').write_bytes(raw)
    else:
        doc = {'schemaVersion': 2, 'pid': 2147483646, 'startedAt': '2000-01-01T00:00:00Z', 'children': []}
        if case == 'children-shape': doc['children'] = {}
        if case == 'future-schema': doc['schemaVersion'] = 3
        (runtime/'manifest.json').write_text('{bad}' if case == 'manifest-json' else json.dumps(doc))
    before = frozen.inventory(origin)
    if case == 'unreadable-runtime': runtime.chmod(0)
    installations = []
    monkeypatch.setattr(index, '_select_distribution', lambda *_: installations.append('install'))
    try:
        with pytest.raises((ValueError, OSError)):
            frozen.prepare(baseline.parent, cfg.cache_root, tmp_path/'refused', cfg, refs=[c.name])
        assert not installations
        assert not (tmp_path/'refused').exists()
    finally:
        if case == 'unreadable-runtime': runtime.chmod(before['data/runtime']['mode'])
    assert frozen.inventory(origin) == before


@pytest.mark.parametrize('field', ['writer', 'process'])
@pytest.mark.parametrize('timestamp', ['2000-01-01T00:00:00Z', '2026-01-01T00:00:00+00:00', 'not-a-time'])
def test_ledger_known_time_uncertainty_refuses(source, tmp_path, monkeypatch, field, timestamp):
    c, cfg, origin, baseline, _ = source
    ledger = origin/'data/runtime/exit-ledger'; ledger.mkdir(parents=True)
    identity = {'kind': 'linux-proc-v1', 'pid': 2147483646,
        'bootId': Path('/proc/sys/kernel/random/boot_id').read_text().strip(), 'startTicks': '100'}
    row = {'schemaVersion': 1, 'type': 'child-start', 'seq': 1,
        'writer': {'role': 'engine', 'pid': 2147483646, 'startedAt': '2026-01-01T00:00:00Z'},
        'pid': 2147483646, 'processIdentity': identity, 'executable': '/recorded/ended/helper',
        'processStartedAt': '2026-01-01T00:00:00Z'}
    if field == 'writer': row['writer']['startedAt'] = timestamp
    else: row['processStartedAt'] = timestamp
    (ledger/'children.v1.jsonl').write_text(json.dumps(row)+'\n')
    before = frozen.inventory(origin)
    monkeypatch.setattr(index, '_select_distribution', lambda *_: pytest.fail('invalid time reached install'))
    with pytest.raises(ValueError):
        frozen.prepare(baseline.parent, cfg.cache_root, tmp_path/'refused', cfg, refs=[c.name])
    assert frozen.inventory(origin) == before


def test_dead_native_helper_start_without_wall_clock_birth_is_valid(source, tmp_path):
    c, cfg, origin, baseline, _ = source
    ledger = origin/'data/runtime/exit-ledger'; ledger.mkdir(parents=True)
    helper = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)'])
    try:
        native = {'kind': 'linux-proc-v1', 'pid': helper.pid,
            'bootId': Path('/proc/sys/kernel/random/boot_id').read_text().strip(),
            'startTicks': index._process(helper.pid)['birth']}
        executable = str(Path(f'/proc/{helper.pid}/exe').readlink())
    finally:
        helper.terminate(); helper.wait(timeout=5)
    row = {'schemaVersion': 1, 'type': 'helper-start', 'seq': 1,
        'writer': {'role': 'engine', 'pid': 2147483646, 'startedAt': '2026-01-01T00:00:00Z'},
        'helperId': 'ended-helper', 'purpose': 'fixture-helper', 'pid': helper.pid,
        'processIdentity': native, 'executable': executable}
    (ledger/'children.v1.jsonl').write_text(json.dumps(row)+'\n')
    before = frozen.inventory(origin)
    frozen.prepare(baseline.parent, cfg.cache_root, tmp_path/'copies', cfg, refs=[c.name])
    assert frozen.inventory(origin) == before
