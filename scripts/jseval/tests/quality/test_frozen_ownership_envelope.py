"""Portable raw authority coverage: actual files/process receipts and rehashed saved mutations."""
from dataclasses import replace
from datetime import datetime, timezone
import base64
import copy
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

import psutil
import pytest
from jseval.quality import frozen_index as frozen, index
from .test_frozen_index import source, retained_pair as retain
from .test_frozen_repairs import rehash


def authority_inputs(source, case):
    """Capture one real native birth, then end/reap it before preparing any copy."""
    c, cfg, origin, bp, b = source
    if case == 'none': return 0
    runtime = origin/'data/runtime'; runtime.mkdir()
    if case == 'no-selected':
        (runtime/'notes.txt').write_text('not stop authority')
        return 0
    helper = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)'])
    try:
        pid = helper.pid
        child = {'id': 'ended-ä-helper', 'kind': 'EXTRACTION', 'pid': pid,
            'startedAt': datetime.fromtimestamp(psutil.Process(pid).create_time(), timezone.utc).isoformat().replace('+00:00','Z'),
            'executable': str(Path(f'/proc/{pid}/exe').readlink()),
            'processIdentity': {'kind':'linux-proc-v1', 'pid':pid,
                'bootId':Path('/proc/sys/kernel/random/boot_id').read_text().strip(), 'startTicks':index._process(pid)['birth']}}
    finally: helper.terminate(); helper.wait(timeout=5)
    if case == 'old-boot': child['processIdentity']['bootId'] = '00000000-0000-0000-0000-000000000000'
    ledger = runtime/'exit-ledger'; ledger.mkdir()
    writer = {'role':'engine','pid':pid,'startedAt':'2026-01-01T00:00:00Z'}
    start = {'schemaVersion':1,'type':'child-start','seq':1,'writer':writer,
        'childId':child['id'], 'pid':pid, 'processStartedAt':child['startedAt'],
        'executable':child['executable'], 'processIdentity':child['processIdentity']}
    if case == 'empty':
        (ledger/'children.v1.jsonl').write_bytes(b'')
        return 0
    if case == 'non-start':
        (ledger/'children.v1.jsonl').write_text(json.dumps({**start,'type':'child-spawn'})+'\n')
        return 0
    if case == 'helper-no-time':
        row = {**start,'type':'helper-start'}; row.pop('processStartedAt')
        (ledger/'children.v1.jsonl').write_text(json.dumps(row)+'\n')
        return 1
    manifest = {'schemaVersion':2,'pid':pid,'startedAt':child['startedAt'],
                'children':[] if case == 'owner' else [child]}
    (runtime/'manifest.json').write_text('  '+json.dumps(manifest, ensure_ascii=False)+'\r\n')
    if case == 'owner': return 1
    # Two intentional identical starts; arbitrary whitespace/CRLF remains hashed verbatim.
    raw = (' '+json.dumps(start)+'\r\n').encode()
    (ledger/'children.v1.jsonl').write_bytes(raw+raw)
    (ledger/'children.v1.19.jsonl').write_bytes(raw)
    (ledger/'children.v1.lost.jsonl').write_text(json.dumps({**start,'type':'ledger-gap'})+'\n')
    (ledger/'engine.v1.jsonl').write_text(json.dumps({**start,'type':'engine-start'})+'\n')
    (ledger/'children.v1.jsonl.pending').write_bytes(b'{not selected or parseable}')
    return 6  # manifest owner+child, archive, two current starts, engine start


@pytest.mark.parametrize('case', ['none','no-selected','empty','non-start','owner','multi','helper-no-time','old-boot'])
def test_valid_authority_capture_preserves_inputs_and_observation_multiplicity(source, tmp_path, case):
    expected = authority_inputs(source, case)
    before = frozen.inventory(source[2])
    c, bp, cp, b, n = retain.__wrapped__(source, tmp_path)
    item = json.loads((cp.parent/frozen.EVIDENCE/'copy.json').read_text())
    assert len(item['predecessor_ownership']['processes']) == expected
    assert frozen.inventory(source[2]) == before
    frozen.validated_pair(cp,bp)
    if case == 'old-boot':
        rows = item['predecessor_ownership']['processes']
        assert rows[1]['recorded']['processIdentity']['bootId'] != rows[1]['boot_id']


@pytest.fixture
def bundle(source,tmp_path):
    authority_inputs(source,'multi')
    return retain.__wrapped__(source,tmp_path)


@pytest.mark.parametrize('mutation', ['delete-all','delete-one','gone-only','native-pid','wrong-source','extra-observation',
    'duplicate-observation','missing-source','missing-recorded','missing-boot','invalid-boot','inconsistent-boot',
    'spare-field','raw-bytes','raw-hash','raw-size','missing-envelope','missing-archive','missing-loss',
    'extra-envelope','duplicate-envelope','invalid-encoding'])
def test_rehashed_portable_ownership_mutations_refuse(bundle,mutation):
    _,bp,cp,_,_ = bundle
    frozen.validated_pair(cp,bp)
    def mutate(item):
        ownership=item['predecessor_ownership']; rows=ownership['processes']; inputs=ownership['inputs']
        if mutation == 'delete-all': ownership['processes']=[]
        elif mutation == 'delete-one': rows.pop(2)
        elif mutation == 'gone-only': rows[1]={'observation':'gone'}
        elif mutation == 'native-pid': rows[1]['recorded']['processIdentity']['pid']+=1
        elif mutation == 'wrong-source': rows[1]['source']='children.v1.19.jsonl'
        elif mutation in ('extra-observation','duplicate-observation'): rows.append(copy.deepcopy(rows[-1]))
        elif mutation.startswith('missing-') and mutation[8:] in ('source','recorded','boot'): rows[1].pop('boot_id' if mutation=='missing-boot' else mutation[8:])
        elif mutation == 'invalid-boot': rows[1]['boot_id']='not-a-boot'
        elif mutation == 'inconsistent-boot': rows[1]['boot_id']='11111111-1111-1111-1111-111111111111'
        elif mutation == 'spare-field': rows[1]['pid']=1
        elif mutation == 'raw-bytes': inputs[0][1]=base64.b64encode(b'!'+base64.b64decode(inputs[0][1])[1:]).decode()
        elif mutation == 'raw-hash': ownership['files'][inputs[0][0]]='0'*64
        elif mutation == 'raw-size': item['source_data'][inputs[0][0]]['size']+=1
        elif mutation == 'missing-envelope': ownership.pop('inputs')
        elif mutation in ('missing-archive','missing-loss'):
            name='runtime/exit-ledger/children.v1.19.jsonl' if mutation=='missing-archive' else 'runtime/exit-ledger/children.v1.lost.jsonl'
            ownership['inputs']=[pair for pair in inputs if pair[0]!=name]
        elif mutation == 'extra-envelope': inputs.append(['runtime/exit-ledger/children.v1.jsonl.pending',''])
        elif mutation == 'duplicate-envelope': inputs.append(copy.deepcopy(inputs[0]))
        else: inputs[0][1]='#'+inputs[0][1][1:]
    rehash(cp,'copy.json',mutate)
    with pytest.raises((ValueError,OSError,KeyError)):
        frozen.validated_pair(cp,bp)


def test_lossless_selected_files_and_stable_receipt_order(bundle):
    _,bp,cp,_,_=bundle
    item=json.loads((cp.parent/frozen.EVIDENCE/'copy.json').read_text())
    ownership=item['predecessor_ownership']
    decoded=dict((name,base64.b64decode(value)) for name,value in ownership['inputs'])
    origin=Path(item['original_root'])/'data'
    assert all(raw==(origin/name).read_bytes() for name,raw in decoded.items())
    assert len(decoded)==5 and 'runtime/exit-ledger/children.v1.jsonl.pending' not in decoded
    assert b'\r\n' in decoded['runtime/exit-ledger/children.v1.jsonl']
    assert [row['source'] for row in ownership['processes']]==[
        'manifest owner','manifest child','children.v1.19.jsonl','children.v1.jsonl','children.v1.jsonl','engine.v1.jsonl']
    assert ownership['processes'][3]['recorded']==ownership['processes'][4]['recorded']
    frozen.validated_pair(cp,bp)


def test_complete_bundle_moves_after_original_and_clone_paths_are_removed(bundle,tmp_path):
    _,bp,cp,_,_=bundle
    item=json.loads((cp.parent/frozen.EVIDENCE/'copy.json').read_text())
    destination=tmp_path/'portable'
    shutil.copytree(cp.parent,destination)
    shutil.rmtree(Path(item['original_root']))
    shutil.rmtree(tmp_path/'copies')
    frozen.validated_pair(destination/'run.jsonl',bp)


@pytest.mark.parametrize('case',['native-kind','native-pid','native-boot','native-ticks','executable','child-time',
    'writer-time','malformed','torn','future','duplicate-json','nonselected'])
def test_shared_pure_extractor_refuses_invalid_raw_authority(source,case):
    authority_inputs(source,'multi')
    data=source[2]/'data'
    path=data/'runtime/exit-ledger/children.v1.jsonl'
    raw=path.read_bytes()
    row=json.loads(raw.splitlines()[0])
    if case=='native-kind': row['processIdentity']['kind']='other'
    elif case=='native-pid': row['processIdentity']['pid']+=1
    elif case=='native-boot': row['processIdentity']['bootId']='unknown'
    elif case=='native-ticks': row['processIdentity']['startTicks']=str(2**64)
    elif case=='executable': row['executable']='relative/helper'
    elif case=='child-time': row['processStartedAt']='unknown'
    elif case=='writer-time': row['writer']['startedAt']='2000-01-01T00:00:00Z'
    elif case=='future': row['schemaVersion']=2
    if case=='malformed': raw=b'{bad}\n'
    elif case=='torn': raw=raw.rstrip(b'\r\n')
    elif case=='duplicate-json': raw=b'{"schemaVersion":1,"schemaVersion":2}\n'
    else: raw=(json.dumps(row)+'\n').encode()
    name='runtime/exit-ledger/children.v1.jsonl.pending' if case=='nonselected' else path.relative_to(data).as_posix()
    with pytest.raises((ValueError,KeyError)):
        frozen.ownership_receipts({name:raw})
