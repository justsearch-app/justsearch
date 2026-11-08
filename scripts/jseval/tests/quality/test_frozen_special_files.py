"""Actual local filesystem entry points; no Engine, production data or model inference."""
from dataclasses import replace
import hashlib
import builtins
import json
import multiprocessing
import os
from pathlib import Path
import shutil
import socket
import stat
import subprocess
import sys

import pytest

from jseval.quality import collections, compare, frozen_index as frozen, run
from jseval.quality.types import RunRecord, RunRecordError, canonical_json
from .helpers import config_for, make_collection, make_run
from .test_frozen_index import source, retained_pair, prepare_source


REFUSALS = (ValueError, OSError, shutil.Error)


def refused_child(root, body):
    """A timeout records the actual entry, cleans only our child, and is a failure."""
    code = '''
import os, sys, json
from pathlib import Path
from jseval.quality import frozen_index as f, collections, compare
from jseval.quality.types import RunRecord
root=Path(sys.argv[1])
print('ENTER', flush=True)
try:
''' + ''.join('    '+line+'\n' for line in body.splitlines()) + '''
except (ValueError, OSError) as error:
    print('REFUSED '+type(error).__name__+': '+str(error), flush=True)
else:
    raise AssertionError('unsupported input accepted')
'''
    child = subprocess.Popen([sys.executable, '-c', code, str(root)],
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        out, err = child.communicate(timeout=5)
    except subprocess.TimeoutExpired:
        child.kill()
        out, err = child.communicate(timeout=5)
        pytest.fail(f'actual API blocked; owned child {child.pid} killed/reaped: {out} {err}')
    assert child.returncode == 0 and 'ENTER' in out and 'REFUSED' in out, (out, err)
    assert 'ImportError' not in err and 'ModuleNotFoundError' not in err


def bounded_call(callback):
    """Run potentially blocking before behavior only in our bounded owned child."""
    def invoke():
        print('ENTER OWNED OS PROBE', flush=True)
        callback()
    child = multiprocessing.get_context('fork').Process(target=invoke)
    child.start()
    child.join(5)
    pid = child.pid
    if child.is_alive():
        child.kill(); child.join(5); child.close()
        pytest.fail(f'actual API blocked; owned child {pid} killed/reaped')
    result = child.exitcode
    child.close()
    assert result == 0, f'owned actual API probe exited {result}'


def expect_refusal(callback):
    with pytest.raises(REFUSALS): callback()


def locks(root):
    data = root/'data'
    (data/'index').mkdir(parents=True)
    for path in (root/'data.quality.lock', data/'app.lock', data/'index/default.index.lock'):
        path.write_bytes(b'lock contents')
    return data


@pytest.mark.parametrize('api', ['read', 'quality', 'app', 'index', 'inventory-swap'])
def test_actual_fifo_entry_refuses_without_peer_or_kill(tmp_path, api):
    data = locks(tmp_path)
    target = {'read': tmp_path/'fifo', 'quality': tmp_path/'data.quality.lock',
              'app': data/'app.lock', 'index': data/'index/default.index.lock',
              'inventory-swap': tmp_path/'regular'}[api]
    if api == 'inventory-swap':
        target.write_bytes(b'regular first')
        body = '''
real_open=os.open
def swapped(path, flags, *args, **kwargs):
    if Path(path)==root/'regular':
        Path(path).unlink(); os.mkfifo(path)
        print('SWAPPED_TO_FIFO', flush=True)
    return real_open(path, flags, *args, **kwargs)
f.os.open=swapped
f.inventory(root)
'''
    else:
        target.unlink(missing_ok=True); os.mkfifo(target)
        body = "f.read_bytes(root/'fifo')" if api == 'read' else "with f.source_locks(root/'data'): pass"
    refused_child(tmp_path, body)


@pytest.mark.parametrize('kind', ['fifo', 'peer-fifo', 'directory', 'socket', 'symlink', 'hardlink'])
def test_frozen_byte_kinds_refuse_and_local_descriptors_close(tmp_path, kind):
    path = tmp_path/'input'
    owned = None
    if kind in ('fifo', 'peer-fifo'):
        os.mkfifo(path)
        if kind == 'peer-fifo': owned = os.open(path, os.O_RDWR | os.O_NONBLOCK)
    elif kind == 'directory': path.mkdir()
    elif kind == 'socket':
        owned = socket.socket(socket.AF_UNIX)
        cwd = Path.cwd()
        try:
            os.chdir(tmp_path); owned.bind(path.name)
        finally:
            os.chdir(cwd)
    else:
        target = tmp_path/'target'; target.write_bytes(b'bytes')
        path.symlink_to(target) if kind == 'symlink' else os.link(target, path)
    before = len(list(Path('/proc/self/fd').iterdir()))
    try:
        def probe():
            count = len(list(Path('/proc/self/fd').iterdir()))
            for _ in range(3):
                with pytest.raises(REFUSALS): frozen.read_bytes(path)
            assert len(list(Path('/proc/self/fd').iterdir())) == count
        bounded_call(probe)
        assert len(list(Path('/proc/self/fd').iterdir())) == before
    finally:
        if isinstance(owned, int): os.close(owned)
        elif owned is not None: owned.close()


@pytest.mark.parametrize('api', ['bytes', 'inventory'])
@pytest.mark.parametrize('change', ['regular', 'symlink', 'directory', 'missing', 'hardlink'])
def test_preopen_replacement_is_not_accepted(tmp_path, monkeypatch, api, change):
    path = tmp_path/'input'; path.write_bytes(b'same bytes')
    other = tmp_path/'other'; other.write_bytes(b'same bytes')
    real_open = os.open
    swapped = []
    def opened(candidate, flags, *args, **kwargs):
        if Path(candidate) == path and not swapped:
            swapped.append(True); path.unlink()
            if change == 'regular': path.write_bytes(b'same bytes')
            elif change == 'symlink': path.symlink_to(other)
            elif change == 'directory': path.mkdir()
            elif change == 'hardlink': os.link(other, path)
        return real_open(candidate, flags, *args, **kwargs)
    monkeypatch.setattr(os, 'open', opened)
    with pytest.raises(REFUSALS):
        frozen.read_bytes(path) if api == 'bytes' else frozen.inventory(tmp_path)
    assert swapped


@pytest.mark.parametrize('change', ['bytes', 'mode', 'hardlink', 'name'])
def test_inventory_closes_opened_content_and_named_identity(tmp_path, monkeypatch, change):
    path = tmp_path/'input'; path.write_bytes(b'content unchanged length')
    real_digest = hashlib.file_digest
    changed = []
    def digest(stream, *args, **kwargs):
        value = real_digest(stream, *args, **kwargs)
        if not changed:
            changed.append(True); st = path.stat()
            if change == 'bytes':
                path.write_bytes(b'Content unchanged length'); os.utime(path, ns=(st.st_atime_ns, st.st_mtime_ns))
            elif change == 'mode': path.chmod(0o600 if stat.S_IMODE(st.st_mode) != 0o600 else 0o644)
            elif change == 'hardlink': os.link(path, tmp_path/'alias')
            else:
                path.rename(tmp_path/'old'); path.write_bytes(b'content unchanged length')
        return value
    monkeypatch.setattr(hashlib, 'file_digest', digest)
    with pytest.raises(REFUSALS): frozen.inventory(tmp_path)
    assert changed


@pytest.mark.parametrize('which', ['root', 'nested', 'ancestor'])
def test_inventory_observable_directory_replacement_refuses(tmp_path, monkeypatch, which):
    root = tmp_path/'ancestor'/'root'; (root/'nested').mkdir(parents=True)
    (root/'nested'/'input').write_bytes(b'payload')
    real_digest = hashlib.file_digest
    changed = []
    target = root if which == 'root' else root/'nested' if which == 'nested' else root.parent
    def digest(stream, *args, **kwargs):
        value = real_digest(stream, *args, **kwargs)
        if not changed:
            changed.append(True)
            moved = target.with_name(target.name+'-moved'); target.rename(moved); target.symlink_to(moved, target_is_directory=True)
        return value
    monkeypatch.setattr(hashlib, 'file_digest', digest)
    with pytest.raises(REFUSALS): frozen.inventory(root)
    assert changed


@pytest.mark.parametrize('name', ['data.quality.lock', 'data/app.lock', 'data/index/default.index.lock'])
@pytest.mark.parametrize('exceptional', [False, True])
def test_each_source_lock_name_is_bound_through_scope_exit(tmp_path, name, exceptional):
    data = locks(tmp_path); path = tmp_path/name
    with pytest.raises(frozen.FrozenRefused, match='lock replaced'):
        with frozen.source_locks(data):
            path.rename(path.with_name(path.name+'.original')); path.write_bytes(b'replacement')
            if exceptional: raise RuntimeError('body failed')
    # All locally owned holds were released despite the failed closure.
    with frozen.source_locks(data): pass


def test_source_locks_allow_operational_lock_content_changes(tmp_path):
    data = locks(tmp_path)
    with frozen.source_locks(data):
        for path in (tmp_path/'data.quality.lock', data/'app.lock', data/'index/default.index.lock'):
            path.write_bytes(b'changed content is not lock identity authority')


@pytest.mark.parametrize('name', ['quality', 'app', 'index'])
def test_guardian_special_file_first_probe_refuses(source, tmp_path, name):
    # Entry to each existing real probe; missing/wrong holds never become authority.
    data = locks(tmp_path)
    target = {'quality': tmp_path/'data.quality.lock', 'app': data/'app.lock',
              'index': data/'index/default.index.lock'}[name]
    with frozen.source_locks(data):
        target.rename(target.with_name(target.name+'.original')); os.mkfifo(target)
        try:
            refused_child(tmp_path, "f.require_guardian_hold(root/'data')")
        finally:
            target.unlink(); target.with_name(target.name+'.original').rename(target)


def test_guardian_probe_closes_only_and_keeps_real_holds(tmp_path):
    data = locks(tmp_path)
    with frozen.source_locks(data):
        frozen.require_guardian_hold(data)
        frozen.read_bytes(data/'app.lock')
        frozen.require_guardian_hold(data)
        with pytest.raises(BlockingIOError):
            with frozen.source_locks(data): pass
    with pytest.raises(frozen.FrozenRefused): frozen.require_guardian_hold(data)
    with frozen.source_locks(data): pass


@pytest.mark.parametrize('kind', ['fifo', 'hardlink', 'symlink', 'directory'])
def test_clone_lock_refuses_before_handle_grant(source, tmp_path, kind):
    c, cfg, _, baseline, _ = source
    output, clone = prepare_source(source, tmp_path)
    path = clone/'clone.quality.lock'
    if kind == 'fifo': os.mkfifo(path)
    elif kind == 'directory': path.mkdir()
    else:
        target = clone/'other'; target.write_bytes(b'lock')
        path.symlink_to(target) if kind == 'symlink' else os.link(target, path)
    (tmp_path/'runs').mkdir()
    with frozen.run_resources(output, [(c, cfg, baseline)]):
        bounded_call(lambda: expect_refusal(lambda: frozen.open_handle(
            output, c, cfg, baseline, tmp_path/'runs/run.jsonl')))


def test_clone_lock_named_identity_is_checked_at_close(source, tmp_path):
    c, cfg, _, baseline, _ = source
    output, clone = prepare_source(source, tmp_path)
    (tmp_path/'runs').mkdir()
    with frozen.run_resources(output, [(c, cfg, baseline)]):
        handle = frozen.open_handle(output, c, cfg, baseline, tmp_path/'runs/run.jsonl')
        path = clone/'clone.quality.lock'; path.rename(clone/'old-clone-lock'); path.write_bytes(b'new')
        with pytest.raises(frozen.FrozenRefused, match='lock replaced'): handle.stack.close()


@pytest.mark.parametrize('consumer', ['pointed-run', 'candidate', 'baseline-write'])
def test_generic_first_record_reads_cannot_wait_for_fifo(tmp_path, consumer):
    path = tmp_path/'input'; os.mkfifo(path)
    pointer = {'schema': 'jseval.quality.baseline.v1', 'run_file': 'input', 'run_sha256': '0'*64}
    (tmp_path/'baseline.json').write_text(json.dumps(pointer))
    body = {'pointed-run': "compare.load_baseline(root/'baseline.json')",
            'candidate': "RunRecord.read(root/'input')",
            'baseline-write': "compare.write_baseline(root/'input', None)"}[consumer]
    refused_child(tmp_path, body)


@pytest.mark.parametrize('name', ['collection.v1.json', 'qrels.tsv', 'manifest.tsv', 'content.tsv', 'queries.jsonl'])
def test_collection_actual_first_reads_refuse_fifo(tmp_path, name):
    c = make_collection({'en/vague': 2}, n_docs=3)
    collections.write_collection(c, tmp_path/'collection')
    path = tmp_path/'collection'/name; path.unlink(); os.mkfifo(path)
    refused_child(tmp_path, "collections.load_collection(root/'collection')")


@pytest.mark.parametrize('name', ['corpus.jsonl', 'queries.jsonl', 'qrels/test.tsv'])
def test_owned_beir_actual_first_reads_refuse_fifo(tmp_path, name):
    (tmp_path/'qrels').mkdir()
    (tmp_path/'corpus.jsonl').write_text('{"_id":"d","text":"document"}\n')
    (tmp_path/'queries.jsonl').write_text('{"_id":"q","text":"query"}\n')
    (tmp_path/'qrels/test.tsv').write_text('query-id\tcorpus-id\tscore\nq\td\t1\n')
    path = tmp_path/name; path.unlink(); os.mkfifo(path)
    refused_child(tmp_path, '''
from jseval.quality.collections import QualityConfig
cfg=QualityConfig('fixture','en',{}, {}, {})
collections.from_beir_dir(root,cfg)
''')


def test_selected_config_open_race_refuses(tmp_path, monkeypatch):
    path = tmp_path/'quality.config.json'
    path.write_text(json.dumps({'schema': collections.CONFIG_SCHEMA, 'collection': 'fixture',
        'language': 'en', 'source': {}, 'labels': {}, 'expected': {}, 'judgments': 'official'}))
    assert collections.load_config(path).collection == 'fixture'
    real_open, real_path_open = os.open, Path.open
    changed = []
    def swap(candidate, seam):
        if Path(candidate) == path and not changed:
            changed.append(True); path.unlink(); os.mkfifo(path)
            print('SELECTED_OPEN '+seam+' SWAPPED_TO_FIFO', flush=True)
    def opened(candidate, flags, *args, **kwargs):
        swap(candidate, 'os.open'); return real_open(candidate, flags, *args, **kwargs)
    def path_open(candidate, *args, **kwargs):
        swap(candidate, 'Path.open'); return real_path_open(candidate, *args, **kwargs)
    monkeypatch.setattr(os, 'open', opened)
    monkeypatch.setattr(Path, 'open', path_open)
    def probe():
        with pytest.raises(collections.CollectionError): collections.load_config(path)
        assert changed, 'the actual selected first open was not reached'
    bounded_call(probe)


@pytest.mark.parametrize('newline', ['\n', '\r\n', '\r'])
@pytest.mark.parametrize('link', ['none', 'symlink', 'hardlink'])
def test_ordinary_record_text_links_and_baseline_semantics_preserved(tmp_path, newline, link):
    c = make_collection({'en/vague': 2}, n_docs=3)
    record = make_run(c)
    path = tmp_path/'run.jsonl'; path.write_bytes(record.dumps().replace('\n', newline).encode())
    selected = path
    if link != 'none':
        selected = tmp_path/'linked.jsonl'
        selected.symlink_to(path) if link == 'symlink' else os.link(path, selected)
    assert RunRecord.read(selected) == record
    pointer = compare.write_baseline(selected, c)
    loaded, metadata = compare.load_baseline(pointer)
    assert loaded == record and metadata['run_sha256'] == hashlib.sha256(record.dumps().encode()).hexdigest()


def test_regular_control_z_unicode_and_decode_errors_preserved(tmp_path):
    c = make_collection({'en/vague': 1}, n_docs=2)
    record = make_run(c)
    # Literal U+001A in trailing JSON whitespace is invalid as before, never CRT EOF.
    path = tmp_path/'run.jsonl'; path.write_bytes(record.dumps().encode()+b'\x1a\n')
    with pytest.raises(RunRecordError): RunRecord.read(path)
    path.write_bytes(b'\xff\n')
    with pytest.raises(UnicodeDecodeError): RunRecord.read(path)
    raw = tmp_path/'raw'; raw.write_bytes(b'\x00\xff\x1a\r\n')
    assert frozen.read_bytes(raw) == b'\x00\xff\x1a\r\n'


@pytest.mark.parametrize('partial', [False, True])
def test_public_frozen_candidate_hardlink_is_refused(retained_pair, tmp_path, partial):
    _, _, path, _, record = retained_pair
    if partial:
        record = replace(record, header=replace(record.header, ended=None,
            frozen_index={'baseline_run_sha256': record.header.frozen_index['baseline_run_sha256']}))
        record.write(path)
    os.link(path, tmp_path/'candidate-alias')
    with pytest.raises(RunRecordError): RunRecord.read(path)


@pytest.mark.parametrize('mismatch', ['bytes', 'ordinary', 'partial', 'evidence-sha', 'results'])
def test_direct_validator_binds_full_actual_candidate_semantics(retained_pair, mismatch):
    _, _, path, baseline, record = retained_pair
    if mismatch == 'bytes': path.write_bytes(b'unrelated malformed bytes')
    else:
        changed = baseline if mismatch == 'ordinary' else replace(record,
            header=replace(record.header, ended=None) if mismatch == 'partial' else
            replace(record.header, frozen_index={**record.header.frozen_index, 'evidence_sha256': '1'*64})
            if mismatch == 'evidence-sha' else record.header,
            results=() if mismatch == 'results' else record.results)
        changed.write(path)
    with pytest.raises(REFUSALS): frozen.validate_record_evidence(path, record)


def test_independent_partial_is_inspectable_but_not_verdict_authority(retained_pair):
    c, _, path, _, record = retained_pair
    partial = replace(record, header=replace(record.header, ended=None,
        frozen_index={'baseline_run_sha256': record.header.frozen_index['baseline_run_sha256']}))
    partial.write(path)
    assert frozen.validate_record_evidence(path, partial) is None
    assert RunRecord.read(path) == partial
    assert compare.validate_run(c, partial, {c.version}, 'candidate')


@pytest.mark.parametrize('suffix', ['', '-wal', '-shm'])
def test_standalone_sqlite_selected_replacement_refuses_before_parser(source, monkeypatch, suffix):
    data = source[2]/'data'; path = data/('documents.sqlite'+suffix)
    real_scratch = frozen.tempfile.TemporaryDirectory
    calls = []
    def scratch(*args, **kwargs):
        moved = path.with_name(path.name+'.old'); path.rename(moved)
        path.write_bytes(moved.read_bytes())
        return real_scratch(*args, **kwargs)
    monkeypatch.setattr(frozen.tempfile, 'TemporaryDirectory', scratch)
    monkeypatch.setattr(frozen.sqlite3, 'connect', lambda *a, **kw: calls.append(a) or (_ for _ in ()).throw(AssertionError('parser entered')))
    with pytest.raises(REFUSALS): frozen.payload(data, 'g-test')
    assert not calls


def test_standalone_sqlite_unexpected_companion_refuses_before_parser(source, monkeypatch):
    data = source[2]/'data'; path = data/'documents.sqlite-shm'; path.unlink()
    real_scratch = frozen.tempfile.TemporaryDirectory
    calls = []
    def scratch(*args, **kwargs):
        path.write_bytes(b'new unexpected companion')
        return real_scratch(*args, **kwargs)
    monkeypatch.setattr(frozen.tempfile, 'TemporaryDirectory', scratch)
    monkeypatch.setattr(frozen.sqlite3, 'connect', lambda *a, **kw: calls.append(a))
    with pytest.raises(REFUSALS): frozen.payload(data, 'g-test')
    assert not calls


def test_preparation_data_copy_keeps_copy2_mode_time_and_bytes(source, tmp_path):
    original = source[2]/'data/index/default/indices/g-test/vectors'
    original.write_bytes(b'\x00\xff'+b'x'*(2*1024*1024))
    original.chmod(0o640); os.utime(original, ns=(123000000000, 456000000000))
    before = original.stat()
    _, clone = prepare_source(source, tmp_path)
    copied = clone/'data/index/default/indices/g-test/vectors'
    assert stat.S_IMODE(copied.stat().st_mode) == 0o640
    assert copied.stat().st_mtime_ns == before.st_mtime_ns
    assert copied.read_bytes() == original.read_bytes()
    assert copied.stat().st_ino != original.stat().st_ino


def test_existing_byte_copy_preserves_default_newfile_mode(tmp_path):
    source = tmp_path/'source'; source.write_bytes(b'\x00\xff'+b'x'*(2*1024*1024))
    source.chmod(0o600)
    target = tmp_path/'target'
    prior = os.umask(0o022)
    try:
        frozen._copy_file(source, target)
    finally:
        os.umask(prior)
    assert stat.S_IMODE(target.stat().st_mode) == 0o644
    assert target.read_bytes() == source.read_bytes()
    assert target.stat().st_ino != source.stat().st_ino


def test_preparation_late_copy_fifo_refuses_and_never_publishes_ready(source, tmp_path, monkeypatch):
    _, cfg, original, baseline, _ = source
    real_copytree = shutil.copytree
    changed = []
    def copytree(src, dst, *args, **kwargs):
        if Path(src) == original/'data' and not changed:
            changed.append(True)
            target = original/'data/index/default/indices/g-test/vectors'
            target.unlink(); os.mkfifo(target)
        return real_copytree(src, dst, *args, **kwargs)
    monkeypatch.setattr(shutil, 'copytree', copytree)
    output = tmp_path/'copies'
    with pytest.raises(REFUSALS): frozen.prepare(baseline.parent, cfg.cache_root, output, cfg, refs=[source[0].name])
    assert changed and not output.exists()
    assert not any(p.name == frozen.READY for p in tmp_path.glob('copies.partial-*/*'))


@pytest.mark.parametrize('input_kind', ['fifo', 'directory', 'hardlink'])
def test_partial_direct_validator_checks_candidate_before_early_return(retained_pair, tmp_path, input_kind):
    _, _, path, _, record = retained_pair
    partial = replace(record, header=replace(record.header, ended=None,
        frozen_index={'baseline_run_sha256': record.header.frozen_index['baseline_run_sha256']}))
    partial.write(path)
    if input_kind == 'hardlink': os.link(path, tmp_path/'alias')
    else:
        path.unlink()
        os.mkfifo(path) if input_kind == 'fifo' else path.mkdir()
    bounded_call(lambda: expect_refusal(lambda: frozen.validate_record_evidence(path, partial)))


def test_standalone_header_actual_open_fifo_race_refuses(source, monkeypatch):
    data = source[2]/'data'; target = data/'documents.sqlite'
    real_open, real_path_open = os.open, Path.open
    changed = []
    def swap(path):
        if Path(path) == target and not changed:
            changed.append(True); target.unlink(); os.mkfifo(target)
    def opened(path, flags, *args, **kwargs):
        swap(path); return real_open(path, flags, *args, **kwargs)
    def path_open(path, *args, **kwargs):
        swap(path); return real_path_open(path, *args, **kwargs)
    monkeypatch.setattr(os, 'open', opened)
    monkeypatch.setattr(Path, 'open', path_open)
    def probe():
        with pytest.raises(REFUSALS): frozen.payload(data, 'g-test')
        assert changed, 'real header open was not reached'
    bounded_call(probe)


@pytest.mark.parametrize('suffix', ['', '-wal', '-shm'])
def test_standalone_selected_fifo_before_copy_refuses_and_cleans(source, monkeypatch, suffix):
    data = source[2]/'data'; target = data/('documents.sqlite'+suffix)
    real_scratch = frozen.tempfile.TemporaryDirectory
    scratches, parsers = [], []
    def scratch(*args, **kwargs):
        target.unlink(); os.mkfifo(target)
        created = real_scratch(*args, **kwargs); scratches.append(Path(created.name))
        return created
    monkeypatch.setattr(frozen.tempfile, 'TemporaryDirectory', scratch)
    monkeypatch.setattr(frozen.sqlite3, 'connect', lambda *a, **kw: parsers.append(a))
    def probe():
        with pytest.raises(REFUSALS): frozen.payload(data, 'g-test')
        assert scratches and all(not p.exists() for p in scratches)
        assert not parsers
    bounded_call(probe)


def test_preparation_copy_actual_open_fifo_race_is_bounded(source, tmp_path, monkeypatch):
    c, cfg, original, baseline, _ = source
    target = original/'data/index/default/indices/g-test/vectors'
    real_copytree, real_open, real_builtin_open = shutil.copytree, os.open, builtins.open
    armed, changed = [], []
    def swap(path):
        if armed and Path(path) == target and not changed:
            changed.append(True); target.unlink(); os.mkfifo(target)
    def opened(path, flags, *args, **kwargs):
        swap(path); return real_open(path, flags, *args, **kwargs)
    def builtin_open(path, *args, **kwargs):
        if not isinstance(path, int): swap(path)
        return real_builtin_open(path, *args, **kwargs)
    def copytree(src, dst, *args, **kwargs):
        if Path(src) == original/'data': armed.append(True)
        return real_copytree(src, dst, *args, **kwargs)
    monkeypatch.setattr(shutil, 'copytree', copytree)
    monkeypatch.setattr(os, 'open', opened)
    monkeypatch.setattr(builtins, 'open', builtin_open)
    output = tmp_path/'copies'
    def probe():
        with pytest.raises(REFUSALS): frozen.prepare(baseline.parent, cfg.cache_root, output, cfg, refs=[c.name])
        assert changed, 'actual late copy source open was not reached'
        assert not output.exists()
        assert not any(p.name == frozen.READY for p in tmp_path.glob('copies.partial-*/*'))
    bounded_call(probe)


def test_existing_copy_interface_streams_bounded_chunks(tmp_path, monkeypatch):
    source = tmp_path/'source'; source.write_bytes(b'x'*(2*1024*1024+5))
    target = tmp_path/'target'
    real_fdopen = os.fdopen
    amounts = []
    class ObservedStream:
        def __init__(self, stream): self.stream = stream
        def __getattr__(self, name): return getattr(self.stream, name)
        def __enter__(self): self.stream.__enter__(); return self
        def __exit__(self, *args): return self.stream.__exit__(*args)
        def read(self, size=-1):
            amounts.append(size); return self.stream.read(size)
    def fdopen(fd, *args, **kwargs):
        stream = real_fdopen(fd, *args, **kwargs)
        return ObservedStream(stream) if Path(os.readlink('/proc/self/fd/'+str(fd))) == source else stream
    monkeypatch.setattr(os, 'fdopen', fdopen)
    frozen._copy_file(source, target)
    assert target.read_bytes() == source.read_bytes()
    assert amounts and all(0 < size <= 1024*1024 for size in amounts)


@pytest.mark.parametrize('fault', ['fstat', 'hash'])
def test_local_fds_close_after_actual_stat_and_hash_failures(tmp_path, monkeypatch, fault):
    target = tmp_path/'target'; target.write_bytes(b'payload')
    real_fstat, real_digest = os.fstat, hashlib.file_digest
    def target_fd(fd):
        return Path(os.readlink('/proc/self/fd/'+str(fd))) == target
    def fstat(fd):
        if target_fd(fd): raise OSError('injected actual fstat failure')
        return real_fstat(fd)
    def digest(stream, *args, **kwargs):
        if target_fd(stream.fileno()): raise OSError('injected actual hash failure')
        return real_digest(stream, *args, **kwargs)
    count = len(list(Path('/proc/self/fd').iterdir()))
    if fault == 'fstat': monkeypatch.setattr(os, 'fstat', fstat)
    else: monkeypatch.setattr(hashlib, 'file_digest', digest)
    for _ in range(3):
        with pytest.raises(OSError, match='injected actual'):
            frozen.inventory(tmp_path)
    assert len(list(Path('/proc/self/fd').iterdir())) == count


def test_directory_operational_timestamps_are_not_content_identity(tmp_path, monkeypatch):
    nested = tmp_path/'nested'; nested.mkdir(); (nested/'input').write_bytes(b'bytes')
    before = frozen.inventory(tmp_path)
    real_digest = hashlib.file_digest
    def digest(*args, **kwargs):
        value = real_digest(*args, **kwargs)
        os.utime(nested, ns=(123456789, 987654321))
        return value
    monkeypatch.setattr(hashlib, 'file_digest', digest)
    assert frozen.inventory(tmp_path) == before


@pytest.mark.parametrize('capability', ['platform', 'machine', 'ofd'])
def test_unsupported_lock_capability_refuses_without_creating_authority(tmp_path, monkeypatch, capability):
    data = locks(tmp_path)
    before = frozen.inventory(tmp_path)
    if capability == 'platform': monkeypatch.setattr(frozen.sys, 'platform', 'unsupported')
    elif capability == 'machine': monkeypatch.setattr(frozen.platform, 'machine', lambda: 'unsupported')
    else: monkeypatch.delattr(frozen.fcntl, 'F_OFD_SETLK')
    with pytest.raises(frozen.FrozenRefused, match='unsupported Linux OFD lock ABI'):
        with frozen.source_locks(data): pytest.fail('unsupported platform granted authority')
    assert frozen.inventory(tmp_path) == before


@pytest.mark.parametrize('spelling', ['absolute', 'relative', 'dot-relative', 'unicode-relative'])
@pytest.mark.parametrize('captured', [False, True])
def test_round3_relative_payload_preserves_actual_schema_and_source(source, tmp_path, spelling, captured):
    data = source[2]/'data'
    if spelling == 'unicode-relative':
        data = tmp_path/'nested ü space'/'data'
        shutil.copytree(source[2]/'data', data)
    database = data/'nested ü space'/'arbitrary storage'
    database.parent.mkdir(parents=True, exist_ok=True)
    con = frozen.sqlite3.connect(database)
    try:
        con.execute('CREATE TABLE relative_probe(value TEXT)'); con.commit()
    finally:
        con.close()
    before = frozen.inventory(data)
    expected = frozen.payload(data.absolute(), 'g-test')
    selected = data.absolute() if spelling == 'absolute' else Path(os.path.relpath(data, Path.cwd()))
    if spelling == 'dot-relative': selected = Path('.')/selected
    cwd = Path.cwd()
    if captured:
        capture = frozen._inventory(selected, capture_sqlite=True)[1]
        actual = frozen._payload(selected, 'g-test', capture)
    else:
        actual = frozen.payload(selected, 'g-test')
    assert actual == expected
    assert 'nested ü space/arbitrary storage' in actual['databases']
    assert frozen.inventory(selected) == before == frozen.inventory(data.absolute())
    assert Path.cwd() == cwd


@pytest.mark.parametrize('spelling', ['absolute', 'relative', 'dot-relative', 'unicode-relative'])
def test_round3_relative_guardian_preserves_genuine_holds_and_close_only(tmp_path, spelling):
    data = locks(tmp_path/'nested ü space' if spelling == 'unicode-relative' else tmp_path)
    selected = data.absolute() if spelling == 'absolute' else Path(os.path.relpath(data, Path.cwd()))
    if spelling == 'dot-relative': selected = Path('.')/selected
    cwd = Path.cwd(); before = frozen.inventory(data)
    with frozen.source_locks(selected):
        frozen.require_guardian_hold(selected)
        frozen.require_guardian_hold(data.absolute())
        for path in (data/'app.lock', data/'index/default.index.lock'):
            fd = os.open(path, os.O_RDWR | os.O_NOFOLLOW | os.O_NONBLOCK)
            try:
                with pytest.raises(BlockingIOError):
                    frozen.fcntl.fcntl(fd, frozen.fcntl.F_OFD_SETLK,
                        bytes(frozen._Flock(frozen.fcntl.F_WRLCK, os.SEEK_SET, 0, 0, 0)))
            finally:
                os.close(fd)
        fd = os.open(data.parent/(data.name+'.quality.lock'), os.O_RDONLY | os.O_NONBLOCK)
        try:
            with pytest.raises(BlockingIOError):
                frozen.fcntl.flock(fd, frozen.fcntl.LOCK_EX | frozen.fcntl.LOCK_NB)
        finally:
            os.close(fd)
        assert frozen.inventory(selected) == before
    with frozen.source_locks(data.absolute()): pass
    assert frozen.inventory(data) == before and Path.cwd() == cwd


@pytest.mark.parametrize('missing', ['quality', 'app', 'index'])
@pytest.mark.parametrize('relative', [False, True])
def test_round3_relative_missing_genuine_hold_stays_refused(tmp_path, missing, relative):
    from contextlib import ExitStack
    data = locks(tmp_path/'nested ü space')
    selected = Path(os.path.relpath(data, Path.cwd())) if relative else data.absolute()
    entries = [('quality', data.parent/(data.name+'.quality.lock')),
               ('app', data/'app.lock'), ('index', data/'index/default.index.lock')]
    with ExitStack() as stack:
        for name, path in entries:
            if name == missing: continue
            fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
            stack.callback(os.close, fd)
            if name == 'quality':
                frozen.fcntl.flock(fd, frozen.fcntl.LOCK_EX | frozen.fcntl.LOCK_NB)
            else:
                frozen.fcntl.fcntl(fd, frozen.fcntl.F_OFD_SETLK,
                    bytes(frozen._Flock(frozen.fcntl.F_RDLCK, os.SEEK_SET, 0, 0, 0)))
        with pytest.raises(frozen.FrozenRefused): frozen.require_guardian_hold(selected)
        with pytest.raises(frozen.FrozenRefused): frozen.require_guardian_hold(data.absolute())
    with frozen.source_locks(data): pass


@pytest.mark.parametrize('api', ['payload', 'guardian', 'inventory', 'source-locks'])
def test_round3_relative_linked_tree_remains_refused(source, tmp_path, api):
    data = source[2]/'data'; link = tmp_path/'linked data'; link.symlink_to(data, target_is_directory=True)
    selected = Path(os.path.relpath(link, Path.cwd()))
    with pytest.raises(REFUSALS):
        if api == 'payload': frozen.payload(selected, 'g-test')
        elif api == 'guardian': frozen.require_guardian_hold(selected)
        elif api == 'inventory': frozen.inventory(selected)
        else:
            with frozen.source_locks(selected): pass


@pytest.mark.parametrize('relative', [False, True])
def test_round3_invalid_generation_still_precedes_problematic_path(tmp_path, relative):
    missing = tmp_path/'not-present'
    selected = Path(os.path.relpath(missing, Path.cwd())) if relative else missing
    with pytest.raises(frozen.FrozenRefused, match='invalid active generation'):
        frozen.payload(selected, 'not/a/generation')
