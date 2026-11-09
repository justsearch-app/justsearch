"""Same-call discovery work and freshness on owned real filesystem/SQLite fixtures.

No production indexes, models, input scans or Engine are used. Work observations
count actual filesystem operations, never wall time or helper invocations.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import stat
import subprocess
import sys

import pytest
from jseval.quality import frozen_index as frozen
from .test_frozen_index import source


def receipt(root):
    return {'source_data': frozen.inventory(root/'data'),
            'source_documents': frozen.inventory(root/'documents'),
            'source_metadata': {n: hashlib.sha256(frozen.read_bytes(root/n)).hexdigest() for n in frozen.METADATA}}


def check(source, item):
    c, _, root, _, b = source
    return frozen._check_source(c, b, root, item)


def database(path, *, wal=False):
    path.parent.mkdir(parents=True, exist_ok=True)
    con = sqlite3.connect(path)
    if wal:
        con.execute('PRAGMA journal_mode=WAL')
    con.execute('PRAGMA application_id=12345')
    con.execute('PRAGMA user_version=17')
    con.execute('CREATE TABLE chosen(value TEXT)')
    con.execute("INSERT INTO chosen VALUES ('retained WAL schema')")
    con.commit()
    saved = {p: p.read_bytes() for p in path.parent.glob(path.name+'*') if p.is_file()}
    con.close()
    for p, raw in saved.items():
        p.write_bytes(raw)


def test_delivery_check_source_eliminates_header_only_opens_but_keeps_full_closure(source, monkeypatch):
    root = source[2]
    data = root/'data'
    boring = data/'unrelated small file'
    boring.write_bytes(b'not a database '*20)
    item = receipt(root)
    opened, headers, traversals = [], [], []
    real_open, real_path_open, real_rglob = os.open, Path.open, Path.rglob
    def opened_fd(path, *args, **kw):
        opened.append(Path(path))
        return real_open(path, *args, **kw)
    def path_open(path, mode='r', *args, **kw):
        if path.is_relative_to(data) and mode == 'rb':
            headers.append(path)
        return real_path_open(path, mode, *args, **kw)
    def rglob(path, *args, **kw):
        if path == data:
            traversals.append(path)
        return real_rglob(path, *args, **kw)
    monkeypatch.setattr(os, 'open', opened_fd)
    monkeypatch.setattr(Path, 'open', path_open)
    monkeypatch.setattr(Path, 'rglob', rglob)
    assert check(source, item) is None
    assert not headers, 'duplicate header-only filesystem opens remain in _check_source'
    assert not traversals, 'duplicate SQLite-discovery traversal remains in _check_source'
    assert opened.count(boring) == 2, 'initial and late full-byte source checks must both remain'
    assert opened.count(data/'index/default/indices/g-test/vectors') == 3
    assert opened.count(root/'documents'/next(iter(source[0].documents.values()))) == 2


@pytest.mark.parametrize('name', ['arbitrary', 'nested/ü space/db', 'other/db', 'file.LOG', 'extension.weird'])
def test_preserve_public_shapes_hashes_and_arbitrary_sqlite_wal(source, name):
    root = source[2]
    path = root/'data'/name
    database(path, wal=True)
    for rel, raw in [('empty', b''), ('short', b'SQLite'), ('not.db', b'not sqlite')]:
        (root/'data'/rel).write_bytes(raw)
    database(root/'data'/'excluded.log')
    before = frozen.inventory(root)
    item = receipt(root)
    observed = frozen.payload(root/'data', 'g-test')
    assert set(observed) == {'generation', 'state', 'indices', 'controls', 'databases'}
    assert name in observed['databases'] and 'excluded.log' not in observed['databases']
    details = observed['databases'][name]
    assert details['application_id'] == 12345 and details['user_version'] == 17
    assert any(row[1] == 'chosen' for row in details['schema'])
    assert 'documents' in {r[1] for r in observed['databases']['documents.sqlite']['schema']}
    assert frozen.validate_origin(source[0], source[4], root) == json.loads((root/'entry.json').read_text())
    assert check(source, item) is None
    assert frozen.inventory(root) == before
    for rel, row in item['source_data'].items():
        if row['kind'] == 'file':
            assert row['sha256'] == hashlib.sha256((root/'data'/rel).read_bytes()).hexdigest()
            assert row['size'] == (root/'data'/rel).stat().st_size
    # Standalone callers must discover freshly, without prior source captures.
    database(root/'data'/'new arbitrary DB')
    assert 'new arbitrary DB' in frozen.payload(root/'data', 'g-test')['databases']


def test_preserve_duplicate_database_basenames_have_separate_schema(source):
    data = source[2]/'data'
    database(data/'one/same')
    database(data/'two/same')
    con = sqlite3.connect(data/'two/same')
    con.execute('CREATE TABLE second(value)'); con.commit(); con.close()
    observed = frozen.payload(data, 'g-test')['databases']
    assert not any(row[1] == 'second' for row in observed['one/same']['schema'])
    assert any(row[1] == 'second' for row in observed['two/same']['schema'])
    assert check(source, receipt(source[2])) is None


def edit(path, how):
    if how == 'remove':
        path.unlink()
    elif how == 'mode':
        path.chmod(stat.S_IMODE(path.stat().st_mode) ^ stat.S_IXUSR)
    elif how == 'replace':
        temp = path.with_name(path.name+'.replacement')
        temp.write_bytes(path.read_bytes()+b'changed')
        os.replace(temp, path)
    elif how == 'symlink':
        target = path.with_name(path.name+'.target')
        target.write_bytes(path.read_bytes()); path.unlink(); path.symlink_to(target)
    elif how == 'hardlink':
        target = path.with_name(path.name+'.target')
        path.rename(target); os.link(target, path)
    elif how == 'fifo':
        path.unlink(); os.mkfifo(path)
    elif how == 'same-inode-replace':
        temp = path.with_name(path.name+'.replacement')
        temp.write_bytes(path.read_bytes()); temp.chmod(stat.S_IMODE(path.stat().st_mode)); os.replace(temp, path)
    else:
        st = path.stat()
        raw = bytearray(path.read_bytes())
        raw[-1] ^= 1
        path.write_bytes(raw)
        os.utime(path, ns=(st.st_atime_ns, st.st_mtime_ns))


@pytest.mark.parametrize('suffix', ['', '-wal', '-shm'])
@pytest.mark.parametrize('how', ['content', 'replace', 'same-inode-replace', 'symlink', 'hardlink', 'remove', 'mode'])
def test_delivery_selected_store_refuses_before_sqlite_interpretation(source, monkeypatch, suffix, how):
    data = source[2]/'data'
    target = data/('documents.sqlite'+suffix)
    assert target.is_file()
    item = receipt(source[2])
    real_scratch, real_connect = frozen.tempfile.TemporaryDirectory, sqlite3.connect
    parser_calls = []
    mutated = []
    def scratch(*args, **kw):
        if not mutated:
            edit(target, how)
            mutated.append(True)
        return real_scratch(*args, **kw)
    def connect(*args, **kw):
        parser_calls.append(args[0])
        return real_connect(*args, **kw)
    monkeypatch.setattr(frozen.tempfile, 'TemporaryDirectory', scratch)
    monkeypatch.setattr(sqlite3, 'connect', connect)
    refused = False
    try:
        check(source, item)
    except (frozen.FrozenRefused, OSError, sqlite3.Error):
        refused = True
    assert mutated and refused
    assert not parser_calls, 'selected source drift reached SQLite before initial-inventory binding'


@pytest.mark.parametrize('suffix', ['', '-wal', '-shm'])
def test_preserve_selected_special_swap_refuses_and_cleans_scratch(source, monkeypatch, suffix):
    data = source[2]/'data'
    item = receipt(source[2])
    real_scratch = frozen.tempfile.TemporaryDirectory
    scratches = []
    def scratch(*args, **kw):
        created = real_scratch(*args, **kw)
        scratches.append(Path(created.name))
        edit(data/('documents.sqlite'+suffix), 'fifo')
        return created
    monkeypatch.setattr(frozen.tempfile, 'TemporaryDirectory', scratch)
    with pytest.raises((frozen.FrozenRefused, OSError, shutil.SpecialFileError)):
        check(source, item)
    assert scratches and all(not p.exists() for p in scratches)


@pytest.mark.parametrize('which', ['wal', 'shm'])
def test_delivery_unexpected_companions_refuse_before_parser(source, monkeypatch, which):
    data = source[2]/'data'
    target = data/('documents.sqlite-'+which)
    target.unlink()
    raw = b'unexpected companion'
    item = receipt(source[2])
    original_materialization = frozen.materialization
    parser_calls = []
    def materialization(*args):
        result = original_materialization(*args)
        target.write_bytes(raw)
        return result
    real_connect = sqlite3.connect
    monkeypatch.setattr(frozen, 'materialization', materialization)
    monkeypatch.setattr(sqlite3, 'connect', lambda *a, **kw: parser_calls.append(a[0]) or real_connect(*a, **kw))
    with pytest.raises((frozen.FrozenRefused, OSError, sqlite3.Error)):
        check(source, item)
    assert not parser_calls


@pytest.mark.parametrize('how', ['content', 'replace', 'remove', 'mode'])
def test_preserve_between_calls_are_fresh(source, how):
    data = source[2]/'data'
    target = data/'unrelated'
    target.write_bytes(b'same length before changes')
    item = receipt(source[2])
    assert check(source, item) is None
    edit(target, how)
    with pytest.raises((frozen.FrozenRefused, OSError)):
        check(source, item)


@pytest.mark.parametrize('kind', ['new-db', 'non-db-header', 'document', 'vector', 'control', 'metadata'])
def test_preserve_late_complete_source_document_metadata_closure(source, monkeypatch, kind):
    root = source[2]
    boring = root/'data'/'zz-non-db'
    boring.write_bytes(b'ordinary bytes'*1000)
    item = receipt(root)
    real_connect = sqlite3.connect
    changed = []
    def connect(*args, **kw):
        con = real_connect(*args, **kw)
        if not changed:
            changed.append(True)
            if kind == 'new-db':
                shutil.copyfile(root/'data/documents.sqlite', root/'data/new-db')
            elif kind == 'non-db-header':
                boring.write_bytes((root/'data/documents.sqlite').read_bytes())
            elif kind == 'document':
                edit(root/'documents'/next(iter(source[0].documents.values())), 'content')
            elif kind == 'vector':
                edit(root/'data/index/default/indices/g-test/vectors', 'content')
            elif kind == 'control':
                (root/'data/index/default/added-control').write_bytes(b'control')
            else:
                edit(root/'salt', 'content')
        return con
    monkeypatch.setattr(sqlite3, 'connect', connect)
    with pytest.raises(frozen.FrozenRefused):
        check(source, item)
    assert changed


@pytest.mark.parametrize('unsafe', ['symlink', 'hardlink', 'fifo'])
def test_preserve_initial_safety_gate_precedes_ownership_and_schema(source, monkeypatch, unsafe):
    data = source[2]/'data'
    item = receipt(source[2])
    target = data/'a-unsafe'
    if unsafe == 'symlink': target.symlink_to(data/'documents.sqlite')
    elif unsafe == 'hardlink': os.link(data/'documents.sqlite', target)
    else: os.mkfifo(target)
    entered = []
    predecessor = frozen.predecessor_ownership
    monkeypatch.setattr(frozen, 'predecessor_ownership', lambda *a: entered.append(True) or predecessor(*a))
    with pytest.raises(frozen.FrozenRefused):
        check(source, item)
    assert not entered


def test_preserve_mutation_during_initial_byte_inventory_refuses_before_ownership(source, monkeypatch):
    data = source[2]/'data'
    target = data/'a-regular'
    target.write_bytes(b'full initial bytes'*100)
    item = receipt(source[2])
    original = hashlib.file_digest
    touched, entered = [], []
    def file_digest(stream, *a, **kw):
        result = original(stream, *a, **kw)
        if not touched and Path(os.readlink('/proc/self/fd/'+str(stream.fileno()))) == target:
            edit(target, 'content'); touched.append(True)
        return result
    predecessor = frozen.predecessor_ownership
    monkeypatch.setattr(hashlib, 'file_digest', file_digest)
    monkeypatch.setattr(frozen, 'predecessor_ownership', lambda *a: entered.append(True) or predecessor(*a))
    with pytest.raises(frozen.FrozenRefused, match='file changed'):
        check(source, item)
    assert touched and not entered


def test_preserve_parser_failure_cleans_scratch_and_leaves_originals(source, monkeypatch):
    root = source[2]
    item = receipt(root)
    before = frozen.inventory(root)
    scratches = []
    real_scratch = frozen.tempfile.TemporaryDirectory
    def scratch(*a, **kw):
        context = real_scratch(*a, **kw); scratches.append(Path(context.name)); return context
    monkeypatch.setattr(frozen.tempfile, 'TemporaryDirectory', scratch)
    monkeypatch.setattr(sqlite3, 'connect', lambda *_a, **_kw: (_ for _ in ()).throw(sqlite3.OperationalError('fixture parser failure')))
    with pytest.raises(sqlite3.OperationalError, match='fixture parser failure'):
        check(source, item)
    assert scratches and all(not p.exists() for p in scratches)
    assert frozen.inventory(root) == before


def test_preserve_freshness_in_later_process(source, tmp_path):
    from jseval.quality import collections
    root = source[2]
    target = root/'data'/'ordinary'
    target.write_bytes(b'initial bytes')
    item = receipt(root)
    folder = collections.write_collection(source[0], tmp_path/'collection-input')
    baseline = tmp_path/'baseline-input.jsonl'; baseline.write_text(source[4].dumps())
    declaration = tmp_path/'source-input.json'; declaration.write_text(json.dumps(item))
    code = '''
import json,sys
from pathlib import Path
from jseval.quality import collections,frozen_index as frozen
from jseval.quality.types import RunRecord
c=collections.load_collection(Path(sys.argv[1])); b=RunRecord.loads(Path(sys.argv[2]).read_text())
frozen._check_source(c,b,Path(sys.argv[3]),json.loads(Path(sys.argv[4]).read_text()))
'''
    command = [sys.executable, '-c', code, str(folder), str(baseline), str(root), str(declaration)]
    before = subprocess.run(command, capture_output=True, text=True, timeout=20)
    assert before.returncode == 0, before.stdout+before.stderr
    edit(target, 'content')
    after = subprocess.run(command, capture_output=True, text=True, timeout=20)
    assert after.returncode != 0 and 'frozen source drifted' in after.stderr, after.stdout+after.stderr


def test_preserve_scratch_write_error_cleans_and_leaves_originals(source, monkeypatch):
    root = source[2]
    item = receipt(root)
    before = frozen.inventory(root)
    real_scratch = frozen.tempfile.TemporaryDirectory
    scratches = []
    def scratch(*args, **kw):
        created = real_scratch(*args, **kw)
        path = Path(created.name)
        scratches.append(path)
        path.chmod(0o500)
        return created
    monkeypatch.setattr(frozen.tempfile, 'TemporaryDirectory', scratch)
    with pytest.raises(PermissionError):
        check(source, item)
    assert scratches and all(not p.exists() for p in scratches)
    assert frozen.inventory(root) == before


def test_preserve_invalid_sqlite_prefix_is_not_treated_as_non_database(source):
    data = source[2]/'data'
    (data/'invalid database').write_bytes(b'SQLite format 3\0'+b'\0'*84)
    item = receipt(source[2])
    with pytest.raises(sqlite3.DatabaseError):
        frozen.payload(data, 'g-test')
    with pytest.raises(sqlite3.DatabaseError):
        check(source, item)
