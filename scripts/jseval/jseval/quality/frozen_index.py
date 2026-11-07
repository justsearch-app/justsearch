"""Explicit, baseline-bound copies of stopped indexes for a current-runtime check.

No cache publication, materialization, rebuild or quarantine is available here.
Historical absolute paths are producer observations; stored readers use only the
fixed evidence sibling and its self-contained, hash-bound relative files.
"""
from __future__ import annotations

from contextlib import ExitStack, contextmanager
import ctypes
from dataclasses import dataclass, field, replace
import fcntl
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import sqlite3
import stat
import sys
import tempfile
import time
import uuid

import httpx
import psutil

from .. import backend, index_identity
from . import compare, distribution, index, inputs, sensitivity
from .types import (Collection, RunRecord, atomic_write_text, canonical_json,
                    canonical_manifest_text, canonical_relpath, sha256_text)

SCHEMA = 'jseval.quality.frozen-index.v1'
READY = 'frozen-indexes.json'
SIDECAR = 'run.frozen-index.json'
EVIDENCE = 'frozen-evidence'
METADATA = ('entry.json', 'materialized.json', 'manifest.tsv', 'salt')
RETAINED = (*METADATA, 'quality-owner.json', 'baseline.json', 'baseline-run.txt', 'copy.json')
CHECKS = ('before', 'after', 'post_stop')
IDENTITY_FIELDS = {'distribution', 'harness', 'jdk', 'jvm_options', 'library_path',
                   'gpu', 'ocr', 'models', 'runtime', 'documents'}
LIMITATION = ('Payload hashes bind the present stopped cache and this copy; the historical '
              'baseline did not inventory every payload byte.')


class FrozenRefused(index.IndexRefused, ValueError):
    pass


def require(ok, message):
    if not ok:
        raise FrozenRefused(message)


def digest(value):
    return sha256_text(canonical_json(value))


def read_json(path):
    return json.loads(read_bytes(path))


def checked_path(path: Path, *, missing=False) -> Path:
    """Reject links in every existing ancestor, even if resolve would hide them."""
    path = Path(os.path.abspath(path))
    for part in [*reversed(path.parents), path]:
        try:
            mode = part.lstat().st_mode
        except FileNotFoundError:
            if missing:
                continue
            raise FrozenRefused(f'missing frozen input: {part}') from None
        require(not stat.S_ISLNK(mode), f'symlink in frozen path: {part}')
    return path


def read_bytes(path):
    path = checked_path(Path(path))
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        before = os.fstat(fd)
        require(stat.S_ISREG(before.st_mode) and before.st_nlink == 1,
                f'not an independent regular file: {path}')
        with os.fdopen(fd, 'rb', closefd=False) as stream:
            data = stream.read()
        after = os.fstat(fd)
        require((before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns, before.st_ctime_ns)
                == (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns, after.st_ctime_ns)
                and path.stat().st_ino == before.st_ino, f'input raced while reading: {path}')
        return data
    finally:
        os.close(fd)


def inventory(root: Path) -> dict:
    """Complete path/mode/byte inventory; links, special files and inode aliases refuse."""
    root = checked_path(root)
    require(root.is_dir(), f'not a directory: {root}')
    result, inodes = {'.': {'mode': stat.S_IMODE(root.stat().st_mode), 'kind': 'dir'}}, set()
    for parent, dirs, files in os.walk(root, followlinks=False):
        for name in sorted(dirs + files):
            path = Path(parent)/name
            st = path.lstat()
            rel = path.relative_to(root).as_posix()
            require(stat.S_ISDIR(st.st_mode) or stat.S_ISREG(st.st_mode),
                    f'link or special file in frozen tree: {path}')
            row = {'mode': stat.S_IMODE(st.st_mode), 'kind': 'dir' if stat.S_ISDIR(st.st_mode) else 'file'}
            if stat.S_ISREG(st.st_mode):
                inode = (st.st_dev, st.st_ino)
                require(st.st_nlink == 1 and inode not in inodes, f'mutable inode alias: {path}')
                inodes.add(inode)
                # Stream large index files rather than retaining them in Python memory.
                fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
                try:
                    a = os.fstat(fd)
                    require((a.st_dev, a.st_ino) == inode, f'file raced: {path}')
                    with os.fdopen(fd, 'rb', closefd=False) as stream:
                        sha = hashlib.file_digest(stream, 'sha256').hexdigest()
                    b = os.fstat(fd)
                    require((a.st_size, a.st_mtime_ns, a.st_ctime_ns) ==
                            (b.st_size, b.st_mtime_ns, b.st_ctime_ns), f'file changed: {path}')
                finally:
                    os.close(fd)
                row.update(size=st.st_size, sha256=sha)
            result[rel] = row
    return result


def disjoint(a: Path, b: Path):
    a, b = checked_path(a, missing=True), checked_path(b, missing=True)
    require(not (a.is_relative_to(b) or b.is_relative_to(a)), f'overlapping frozen paths: {a}, {b}')


class _Flock(ctypes.Structure):
    _fields_ = [('type', ctypes.c_short), ('whence', ctypes.c_short),
                ('start', ctypes.c_longlong), ('length', ctypes.c_longlong), ('pid', ctypes.c_int)]


@contextmanager
def source_locks(data: Path):
    """Read-only OFD locks conflict with Java FileChannel's process-owned writer locks.

    An OFD lock survives reopening/hashing/closing the same inode. Never substitute
    lockf/F_SETLK: closing an unrelated FD would release that process-owned lock.
    """
    require(sys.platform == 'linux' and platform.machine() == 'x86_64'
            and ctypes.sizeof(ctypes.c_void_p) == ctypes.sizeof(ctypes.c_long) == 8
            and ctypes.sizeof(_Flock) == 32 and _Flock.start.offset == 8
            and _Flock.length.offset == 16 and _Flock.pid.offset == 24
            and hasattr(fcntl, 'F_OFD_SETLK'), 'unsupported Linux OFD lock ABI')
    data = checked_path(data)
    with ExitStack() as stack:
        quality = data.parent/(data.name+'.quality.lock')
        qfd = os.open(checked_path(quality), os.O_RDONLY | os.O_NOFOLLOW)
        stack.callback(os.close, qfd)
        require(stat.S_ISREG(os.fstat(qfd).st_mode) and os.fstat(qfd).st_nlink == 1, 'invalid data quality lock')
        fcntl.flock(qfd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        descriptors = []
        for path in (data/'app.lock', data/'index/default.index.lock'):
            fd = os.open(checked_path(path), os.O_RDONLY | os.O_NOFOLLOW)
            stack.callback(os.close, fd)
            st = os.fstat(fd)
            require(stat.S_ISREG(st.st_mode) and st.st_nlink == 1, f'invalid Engine lock: {path}')
            fcntl.fcntl(fd, fcntl.F_OFD_SETLK, bytes(_Flock(fcntl.F_RDLCK, os.SEEK_SET, 0, 0, 0)))
            descriptors.append((path, fd, st.st_dev, st.st_ino))
        # Verify every locked name still denotes the locked inode after acquisition.
        for path, fd, dev, ino in descriptors:
            require((path.stat().st_dev, path.stat().st_ino) == (dev, ino), f'lock replaced: {path}')
        yield (qfd, *(row[1] for row in descriptors))
        for path, fd, dev, ino in descriptors:
            require((path.stat().st_dev, path.stat().st_ino) == (dev, ino), f'lock replaced: {path}')



def require_guardian_hold(data):
    """The worker observes real retained locks; no plan boolean grants authority."""
    for path in (data/'app.lock', data/'index/default.index.lock'):
        fd = os.open(checked_path(path), os.O_RDONLY | os.O_NOFOLLOW)
        try:
            reply = fcntl.fcntl(fd, fcntl.F_OFD_GETLK, bytes(_Flock(fcntl.F_WRLCK, os.SEEK_SET, 0, 0, 0)))
            held = _Flock.from_buffer_copy(reply)
            require(held.type == fcntl.F_RDLCK and held.pid == -1,
                    f'original source has no retained OFD read hold: {path}')
        finally:
            os.close(fd)
    fd = os.open(checked_path(data.parent/(data.name+'.quality.lock')), os.O_RDONLY | os.O_NOFOLLOW)
    try:
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return
        raise FrozenRefused('original source has no retained quality hold')
    finally:
        # CLOSE ONLY: no explicit unlock of any shared inherited description.
        os.close(fd)


@contextmanager
def run_resources(output, selections):
    """The caller and admission's existing forked guardian share every lifetime FD."""
    with ExitStack() as stack:
        entries = []
        for c, cfg, baseline in selections:
            manifest, root, item, b = _ready(output, c, cfg, baseline)
            entries.append((Path(item['original_root']), c, cfg, baseline, manifest, item, b))
        require(len({str(row[0]) for row in entries}) == len(entries), 'aliased original sources')
        keep = []
        for origin, c, cfg, baseline, manifest, item, b in sorted(entries, key=lambda row: str(row[0])):
            keep.extend(stack.enter_context(source_locks(origin/'data')))
            _check_source(c, b, origin, item)
        for snapshot in sorted({row[4]['distribution'] for row in entries}):
            keep.append(stack.enter_context(distribution.lease(snapshot)).fileno())
        try:
            yield tuple(keep)
        finally:
            # The caller reaches here after admission's safe wait, report finalization
            # or a pre-spawn failure. Caller death leaves the guardian as last owner.
            for origin, c, cfg, baseline, manifest, item, b in entries:
                _ready(output, c, cfg, baseline)
                _check_source(c, b, origin, item)
                env = cfg.environment()
                env[index.DISTRIBUTION_ENV] = manifest['distribution']
                index.pin_locations(env)
                require(index.identity_digests(runtime_components(c, cfg, env)) == item['runtime_identity'],
                        'runtime input pins changed during admitted window')

def materialization(c: Collection, root: Path):
    manifest = read_json(root/'materialized.json')
    require(isinstance(manifest, dict) and set(manifest) == set(c.documents)
            and len(set(manifest.values())) == len(manifest), 'materialized population mismatch')
    m = replace(c, version='', documents=manifest)
    require(read_bytes(root/'manifest.tsv').decode() == canonical_manifest_text(manifest),
            'materialized manifest bytes mismatch')
    require(bool(read_bytes(root/'salt').strip()), 'missing materialization salt')
    if c.metadata.get('layout', 'opaque') == 'as-is':
        require(manifest == c.documents, 'original as-is paths changed')
    else:
        try:
            salt = bytes.fromhex(read_bytes(root/'salt').decode().strip())
        except ValueError:
            raise FrozenRefused('invalid original materialization salt') from None
        require(len(salt) == 32 and manifest == {doc: hashlib.sha256(salt+doc.encode()).hexdigest()+'.txt'
                                               for doc in c.documents}, 'original salted names changed')
    tree = inventory(root/'documents')
    require({k for k, v in tree.items() if v['kind'] == 'file'} == set(manifest.values()),
            'original materialized document population mismatch')
    for doc, rel in manifest.items():
        require(tree[rel]['sha256'] == c.content[doc], f'original document bytes changed: {doc}')
    return m, tree


def payload(data: Path, generation: str) -> dict:
    require(isinstance(generation, str) and re.fullmatch(r'[A-Za-z0-9_-]+', generation),
            'invalid active generation')
    base = data/'index/default'
    state = read_json(base/'state.json')
    require(state.get('active_generation') == generation and state.get('migration_state') == 'IDLE'
            and state.get('migration_paused') is False and isinstance(state.get('format_version'), int),
            'generation missing, migrated or rebuilding')
    state.pop('updated_at_ms', None)
    # Exact native lock and canonical clean marker are the only payload exceptions.
    tree = inventory(base/'indices')
    require(not any('rebuild' in name.casefold() for name in tree), 'rebuild marker present')
    require(generation+'/.justsearch-generation.sentinel' in tree, 'generation sentinel missing')
    transient = {generation+'/write.lock', generation+'.clean-shutdown'}
    require(all(tree[k]['kind'] == 'file' for k in transient if k in tree), 'invalid transient marker/lock kind')
    tree = {k: v for k, v in tree.items() if k not in transient}
    controls = {}
    for parent, dirs, files in os.walk(base):
        dirs[:] = [d for d in dirs if d != 'indices']
        for name in files:
            path = Path(parent)/name
            rel = path.relative_to(base).as_posix()
            if rel == 'state.json':
                continue
            controls[rel] = hashlib.sha256(read_bytes(path)).hexdigest()
    # Schema/header identities are checked separately from SQLite's operational pages/WAL.
    databases = {}
    for path in sorted(data.rglob('*')):
        if not path.is_file() or path.suffix in ('.log',):
            continue
        with path.open('rb') as stream:
            header = stream.read(100)
        if header.startswith(b'SQLite format 3\0'):
            # SQLite must see retained WAL schema while originals and byte-identical
            # clones remain untouched. A tiny isolated database copy is the first rung;
            # SQLite may create/rewrite only its own temporary SHM bookkeeping there.
            with tempfile.TemporaryDirectory(prefix='frozen-sqlite-') as scratch:
                copied = Path(scratch)/path.name
                for suffix in ('', '-wal', '-shm'):
                    original = path.with_name(path.name+suffix)
                    if original.exists():
                        shutil.copyfile(original, Path(str(copied)+suffix))
                con = sqlite3.connect(copied.as_uri()+'?mode=ro', uri=True)
                try:
                    databases[path.relative_to(data).as_posix()] = {
                        'schema': con.execute('SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name').fetchall(),
                        'application_id': con.execute('PRAGMA application_id').fetchone()[0],
                        'user_version': con.execute('PRAGMA user_version').fetchone()[0]}
                finally:
                    con.close()
    # JSON normalization keeps the SQLite tuples stable after storage/reload.
    return json.loads(canonical_json({'generation': generation, 'state': state,
                                     'indices': tree, 'controls': controls, 'databases': databases}))


def floor(c: Collection, baseline: Path, cache: Path):
    b, metadata = compare.load_baseline(checked_path(baseline))
    h = b.header
    require(hashlib.sha256(read_bytes(baseline.parent/metadata['run_file'])).hexdigest() == metadata['run_sha256'],
            'original baseline raw bytes changed')
    require(not compare.validate_run(c, b, {c.version}, 'frozen baseline'), 'invalid original baseline')
    require(metadata.get('collection') == c.name and metadata.get('collection_version') == c.version,
            'baseline metadata collection mismatch')
    require(h.index_build == 'cached' and h.index_key == h.index_cache_key
            and re.fullmatch(r'[0-9a-f]{64}', h.index_key), 'baseline does not name an exact cached index')
    root = checked_path(cache/sha256_text(c.name)/h.index_key)
    return b, metadata, root


def validate_origin(c, b, root):
    inventory(root/'data')
    entry = read_json(root/'entry.json')
    require(entry.get('schema') == index.SCHEMA, 'unsupported frozen entry schema')
    index._check_owner(entry['owner'], c, b.header.index_key)
    index._check_owner(read_json(root/'data/quality-owner.json'), c, b.header.index_key)
    identity = entry['identity']
    require(identity['components']['index_key'] == b.header.index_key
            and identity['key'] == digest(identity['components']), 'frozen entry identity mismatch')
    witness = entry['witness']
    require(not index._adoption_agreement(witness, b.header.index_state), 'frozen baseline witness mismatch')
    require(all(witness.get(k) == b.header.index_state.get(k)
                for k in ('completeness', 'index_population', 'generation', 'content', 'extracted_content', 'samples')),
            'incomplete frozen witness')
    require(witness.get('content') == c.content and witness.get('index_population') == c.index_population(),
            'frozen witness population mismatch')
    require(entry['attestation']['generation_id'] == witness['generation']
            and entry['attestation']['build_state'] == 'COMPLETE'
            and entry['attestation']['watched_roots'] == [str(root/'documents')], 'frozen generation/root mismatch')
    require(entry['attestation']['canary']['required_stages'] == list(index.CANARY_STAGES),
            'missing original canary stages')
    materialization(c, root)
    payload(root/'data', witness['generation'])
    return entry


def runtime_components(c, cfg, env):
    components = index.effective_components(c, cfg, env)
    for name in (*inputs.HARNESS_MODULES, __name__):
        module = sys.modules.get(name)
        family = name.split('.')[0]
        expected = cfg.repo_root/'scripts'/family/Path(*name.split('.')).with_suffix('.py')
        require(module is not None and Path(module.__file__).resolve() == expected.resolve(),
                f'loaded harness is outside the current source: {name}')
    components['harness'] = {**components['harness'], __name__: distribution.file_sha256(Path(__file__).resolve())}
    return components


def runtime_agreement(build, current):
    require(set(build) == set(current) == IDENTITY_FIELDS and all(build[k] == current[k]
            for k in build if k not in ('distribution', 'harness')), 'current runtime non-code inputs differ from frozen build')


def _copy_file(source, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(read_bytes(source))


def prepare(baseline_dir: Path, cache_root: Path, output: Path, cfg: index.IndexConfig,
            refs=None) -> Path:
    """Install/publish only through the normal receipt callback, then copy; never boot."""
    from . import collections, known_answers, run
    output = checked_path(output, missing=True)
    require(not output.exists(), 'frozen output must be a fresh owned path')
    require(backend.source_identity(cfg.repo_root)[1] is None, 'frozen preparation requires a clean committed source')
    selected = []
    for ref in refs or run.default_collections():
        c, qc = collections.resolve_collection(ref)
        known_answers.check(c, qc)
        c = collections.scored_collection(c, qc)
        baseline = baseline_dir/(sha256_text(c.name)+'.baseline.json')
        b, metadata, root = floor(c, baseline, cache_root)
        for original in (root, checked_path(baseline_dir)):
            disjoint(output, original)
        with source_locks(root/'data'):
            validate_origin(c, b, root)
        selected.append((c, b, metadata, root, baseline, read_bytes(baseline)))
    env = cfg.environment()
    disjoint(output, Path(env['JUSTSEARCH_MODELS_DIR']))
    partial = output.with_name(output.name+'.partial-'+uuid.uuid4().hex)
    disjoint(output, partial)
    partial.mkdir(parents=True, exist_ok=False)
    token = uuid.uuid4().hex
    index._write(partial/'owner.json', {'schema': SCHEMA, 'token': token})
    # Failures retain only this explicitly recorded owned partial directory.
    env[index.DISTRIBUTION_ENV] = index._select_distribution(cfg, env, False)
    index.pin_locations(env)
    source = list(backend.source_identity(cfg.repo_root))
    receipt = read_json(distribution._source_file(source))
    require(receipt == {'schema': distribution.SCHEMA, 'source': source, 'digest': env[index.DISTRIBUTION_ENV]},
            'actual runtime source publication missing or contradictory')
    manifest = {'schema': SCHEMA, 'token': token, 'source': source,
                'distribution': env[index.DISTRIBUTION_ENV], 'runtime_receipt': receipt, 'items': {}, 'limitation': LIMITATION}
    with distribution.lease(env[index.DISTRIBUTION_ENV]):
        for c, b, metadata, root, baseline, original_pointer in selected:
            current = runtime_components(c, cfg, env)
            runtime_agreement(b.header.identity, index.identity_digests(current))
            target = partial/sha256_text(c.name)
            target.mkdir()
            with source_locks(root/'data'):
                require(read_bytes(baseline) == original_pointer, 'baseline pointer changed while preparing')
                entry = validate_origin(c, b, root)
                before = inventory(root/'data')
                documents = inventory(root/'documents')
                source_metadata = {name: hashlib.sha256(read_bytes(root/name)).hexdigest() for name in METADATA}
                shutil.copytree(root/'data', target/'data', copy_function=shutil.copy2)
                require(inventory(target/'data') == before, 'short or changed frozen copy')
                original_inodes = {(p.stat().st_dev, p.stat().st_ino) for p in (root/'data').rglob('*') if p.is_file()}
                require(not any((p.stat().st_dev, p.stat().st_ino) in original_inodes
                                for p in (target/'data').rglob('*') if p.is_file()), 'copy shares mutable inodes')
                for name in METADATA:
                    _copy_file(root/name, target/name)
                _copy_file(root/'data/quality-owner.json', target/'quality-owner.json')
                _copy_file(baseline, target/'baseline.json')
                raw_run = baseline.parent/metadata['run_file']
                _copy_file(raw_run, target/'baseline-run.txt')
                require(hashlib.sha256(read_bytes(target/'baseline-run.txt')).hexdigest() == metadata['run_sha256'],
                        'baseline raced during frozen preparation')
                original_payload = payload(root/'data', entry['witness']['generation'])
                require(payload(target/'data', entry['witness']['generation']) == original_payload,
                        'copy payload differs')
                require(inventory(root/'data') == before and inventory(root/'documents') == documents
                        and source_metadata == {name: hashlib.sha256(read_bytes(root/name)).hexdigest() for name in METADATA},
                        'source changed during frozen preparation')
                item = {'collection': c.name, 'collection_version': c.version, 'original_root': str(root),
                        'documents_dir': str(root/'documents'), 'index_key': b.header.index_key,
                        'baseline_run_sha256': metadata['run_sha256'], 'baseline_sha256': hashlib.sha256(read_bytes(baseline)).hexdigest(),
                        'source_data': before, 'source_documents': documents, 'source_metadata': source_metadata,
                        'payload': original_payload, 'runtime_identity': index.identity_digests(current)}
                index._write(target/'copy.json', item)
                manifest['items'][c.name] = {'folder': target.name, 'sha256': hashlib.sha256(read_bytes(target/'copy.json')).hexdigest()}
        require(list(backend.source_identity(cfg.repo_root)) == source, 'current source changed during preparation')
        index._write(partial/READY, manifest)
        require(not output.exists(), 'frozen output was claimed while preparing')
        partial.rename(output)
    return output


def _ready(output: Path, c, cfg, baseline):
    output = checked_path(output)
    manifest = read_json(output/READY)
    require(manifest.get('schema') == SCHEMA and read_json(output/'owner.json') ==
            {'schema': SCHEMA, 'token': manifest['token']}, 'incomplete/foreign frozen copy')
    require(manifest['source'] == list(backend.source_identity(cfg.repo_root)) and manifest['source'][1] is None,
            'frozen copies belong to another current source')
    snapshot = distribution.current(cfg.repo_root, manifest['source'])
    require(manifest.get('runtime_receipt') == {'schema': distribution.SCHEMA,
            'source': manifest['source'], 'digest': snapshot}, 'prepared runtime receipt changed')
    require(snapshot == manifest['distribution'], 'current snapshot differs from frozen preparation')
    row = manifest['items'][c.name]
    require(row['folder'] == sha256_text(c.name), 'invalid frozen copy location')
    root = output/row['folder']
    require(hashlib.sha256(read_bytes(root/'copy.json')).hexdigest() == row['sha256'], 'changed frozen copy record')
    item = read_json(root/'copy.json')
    b, meta = compare.load_baseline(baseline)
    require(item['collection'] == c.name and item['collection_version'] == c.version
            and item['index_key'] == b.header.index_key and item['baseline_run_sha256'] == meta['run_sha256']
            and item['baseline_sha256'] == hashlib.sha256(read_bytes(baseline)).hexdigest(), 'frozen floor changed')
    require(read_bytes(root/'baseline-run.txt') == read_bytes(baseline.parent/meta['run_file'])
            and read_bytes(root/'baseline.json') == read_bytes(baseline), 'frozen baseline copy differs')
    origin = checked_path(Path(item['original_root']))
    require(item['documents_dir'] == str(origin/'documents'), 'frozen document-root changed')
    disjoint(origin, output)
    return manifest, root, item, b


def _check_source(c, b, root, item):
    validate_origin(c, b, root)
    require(inventory(root/'data') == item['source_data'] and inventory(root/'documents') == item['source_documents']
            and {name: hashlib.sha256(read_bytes(root/name)).hexdigest() for name in METADATA} == item['source_metadata'],
            'frozen source drifted since copying')



def check_run_destination(output, runs_dir, selections):
    """Refuse containment/alias hazards before the planner creates any run files."""
    runs_dir = checked_path(runs_dir, missing=True)
    disjoint(runs_dir, checked_path(output))
    for c, cfg, baseline in selections:
        _, root, item, _ = _ready(output, c, cfg, baseline)
        disjoint(runs_dir, Path(item['original_root']))
        disjoint(runs_dir, checked_path(baseline.parent))

def inspect(output: Path, c, cfg, baseline: Path):
    """Runtime-free planner check. Reacquisition inside the window closes the race."""
    manifest, root, item, b = _ready(output, c, cfg, baseline)
    origin = Path(item['original_root'])
    with source_locks(origin/'data'):
        _check_source(c, b, origin, item)
        require(inventory(root/'data') == item['source_data'], 'owned clone changed before scoring')
    env = cfg.environment()
    env[index.DISTRIBUTION_ENV] = manifest['distribution']
    index.pin_locations(env)
    current = runtime_components(c, cfg, env)
    runtime_agreement(b.header.identity, index.identity_digests(current))
    require(index.identity_digests(current) == item['runtime_identity'], 'runtime inputs changed since preparation')
    return dict(b.header.model_hashes)


@dataclass
class FrozenHandle:
    collection: Collection
    config: index.IndexConfig
    root: Path
    data_dir: Path
    original_root: Path
    baseline: RunRecord
    item: dict
    env: dict
    components: dict
    stack: ExitStack = field(repr=False)
    run_path: Path
    _backend: object = None
    base_url: str | None = None
    index_state: dict = field(default_factory=dict)
    observations: dict = field(default_factory=dict)
    stopped: bool = False

    @property
    def index_key(self): return self.baseline.header.index_key
    @property
    def index_cache_key(self): return self.index_key
    @property
    def index_build(self): return 'cached'
    @property
    def identity(self): return dict(self.baseline.header.identity)
    @property
    def runtime_identity(self): return index.identity_digests(self.components)
    @property
    def model_hashes(self): return dict(self.baseline.header.model_hashes)
    @property
    def index_population(self): return self.index_state.get('index_population', {})
    @property
    def documents_dir(self): return self.original_root/'documents'

    def wait(self, *, prepared_only=True, deadline=None):
        require(prepared_only and deadline is not None, 'frozen execution needs the admitted deadline')
        port = index._free_port()
        self._backend = backend.start_backend(repo_root=self.config.repo_root, data_dir=self.data_dir,
            port=port, env_overrides=index._launch_env(self.config, self.data_dir, self.env),
            distribution=distribution.path(self.env[index.DISTRIBUTION_ENV]),
            health_timeout_sec=max(.001, min(180, deadline-time.monotonic())),
            clean=False, index_cache_mode='off', startup_admission=False, prepared_only=True)
        self.base_url = f'http://127.0.0.1:{port}'
        while time.monotonic() < deadline:
            result = confirm(self, 'before')
            if result.ok:
                return self
            if set(result.failures) - set(result.transient_failures):
                raise FrozenRefused('; '.join(result.failures))
            time.sleep(min(1, max(0, deadline-time.monotonic())))
        raise FrozenRefused('frozen readiness deadline')

    def close(self):
        try:
            writers = []
            if self._backend:
                try:
                    owned = psutil.Process(self._backend.proc.pid)
                    for proc in [owned, *owned.children(recursive=True)]:
                        try:
                            writers.append((proc.pid, proc.create_time()))
                        except psutil.NoSuchProcess:
                            pass
                except psutil.NoSuchProcess:
                    pass
                backend.stop_backend(self._backend.proc, data_dir=self.data_dir)
                require(self._backend.proc.poll() is not None, 'frozen launcher still running')
                self._backend = None
            for pid, born in writers:
                try:
                    proc = psutil.Process(pid)
                    require(proc.create_time() != born or not proc.is_running() or proc.status() == psutil.STATUS_ZOMBIE,
                            f'owned frozen descendant still alive: {pid}')
                except psutil.NoSuchProcess:
                    pass
            # Postflight also runs after refusals; no failure grants source repair.
            observed_payload = payload(self.data_dir, self.item['payload']['generation'])
            require(observed_payload == self.item['payload'], 'frozen payload changed after stop')
            _check_source(self.collection, self.baseline, self.original_root, self.item)
            current = runtime_components(self.collection, self.config, self.env)
            require(index.identity_digests(current) == self.runtime_identity, 'runtime changed after scoring')
            require(list(backend.source_identity(self.config.repo_root)) == read_json(self.root.parent/READY)['source'],
                    'source changed during scoring')
            self.observations['post_stop'] = {'payload': observed_payload,
                'source_data_sha256': digest(self.item['source_data']),
                'source_documents_sha256': digest(self.item['source_documents']),
                'runtime_identity': self.runtime_identity, 'processes_ended': True,
                'cleanup': 'retained-owned-clone'}
            index._write(checked_path(self.run_path.parent/EVIDENCE)/'post_stop.json', self.observations['post_stop'])
            self.stopped = True
        finally:
            # If shutdown raises, admission still owns/reaps descendants before verdict publication.
            self.stack.close()

    def finalize(self, record):
        require(self.stopped and all(k in self.observations for k in CHECKS), 'incomplete frozen lifecycle')
        evidence = self.run_path.parent/EVIDENCE
        checked_path(evidence)
        references = {}
        for name in (*RETAINED, 'runtime-inputs.json'):
            references[f'{EVIDENCE}/{name}'] = hashlib.sha256(read_bytes(evidence/name)).hexdigest()
        for name, observation in self.observations.items():
            index._write(evidence/(name+'.json'), observation)
            references[f'{EVIDENCE}/{name}.json'] = hashlib.sha256(read_bytes(evidence/(name+'.json'))).hexdigest()
        doc = {'schema': SCHEMA, 'candidate_semantic': candidate_binding(record), 'baseline_run_sha256': self.item['baseline_run_sha256'],
               'baseline_sha256': self.item['baseline_sha256'], 'source': list(backend.source_identity(self.config.repo_root)),
               'runtime_identity': self.runtime_identity, 'identity': self.identity,
               'runtime_receipt': read_json(self.root.parent/READY)['runtime_receipt'],
               'index_key': self.index_key, 'files': references, 'limitation': LIMITATION}
        index._write(self.run_path.parent/SIDECAR, doc)
        return {'baseline_run_sha256': self.item['baseline_run_sha256'],
                'evidence_sha256': hashlib.sha256(read_bytes(self.run_path.parent/SIDECAR)).hexdigest()}


def open_handle(output, c, cfg, baseline, run_path):
    manifest, root, item, b = _ready(output, c, cfg, baseline)
    run_path = checked_path(run_path, missing=True)
    for source in (output, Path(item['original_root']), baseline.parent):
        disjoint(source, run_path.parent)
    stack = ExitStack()
    try:
        require_guardian_hold(Path(item['original_root'])/'data')
        _check_source(c, b, Path(item['original_root']), item)
        require(inventory(root/'data') == item['source_data'], 'clone changed before boot')
        # Separate lifecycle lock; the byte-identical index owner remains historical.
        fd = os.open(root/'clone.quality.lock', os.O_RDONLY | os.O_CREAT | os.O_NOFOLLOW, 0o600)
        stack.callback(os.close, fd)
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        env = cfg.environment()
        env[index.DISTRIBUTION_ENV] = manifest['distribution']
        index.pin_locations(env)
        stack.enter_context(distribution.lease(manifest['distribution']))
        components = runtime_components(c, cfg, env)
        runtime_agreement(b.header.identity, index.identity_digests(components))
        require(index.identity_digests(components) == item['runtime_identity'], 'runtime changed before boot')
        evidence = checked_path(run_path.parent/EVIDENCE, missing=True)
        evidence.mkdir(exist_ok=False)
        for name in RETAINED:
            _copy_file(root/name, evidence/name)
        index._write(evidence/'runtime-inputs.json', components)
        return FrozenHandle(c, cfg, root, root/'data', Path(item['original_root']), b, item, env,
                            components, stack, run_path)
    except BaseException:
        stack.close()
        raise


def runtime_process(handle, client):
    manifest = index._get(client, '/api/runtime/manifest')
    # The manifest producer names the actual Engine JVM, not the Node launcher.
    require(isinstance(manifest.get('instanceId'), str) and bool(manifest['instanceId']),
            'runtime manifest has no process incarnation')
    pid = manifest.get('pid')
    require(isinstance(pid, int) and pid > 0, 'runtime manifest has no Engine pid')
    proc = psutil.Process(pid)
    launcher = psutil.Process(handle._backend.proc.pid)
    require(pid in {launcher.pid, *(p.pid for p in launcher.children(recursive=True))},
            'runtime Engine is outside the owned launcher tree')
    snapshot = distribution.path(handle.env[index.DISTRIBUTION_ENV]).resolve()
    require(Path(proc.cwd()).resolve() == snapshot, 'Engine cwd differs from current snapshot')
    command = proc.cmdline()
    require(command and Path(command[0]).resolve() == (Path(handle.env['JAVA_HOME'])/'bin/java').resolve(),
            'runtime process is not the pinned JVM')
    require(any(str(snapshot) in arg for arg in command[1:]), 'runtime command is not bound to snapshot')
    incarnation = index._process(pid)
    incarnation.pop('state')
    return {'manifest': manifest, 'process': incarnation, 'cwd': str(snapshot), 'command': command}


def confirm(handle, phase='after'):
    require(phase in ('before', 'after'), 'invalid frozen confirmation phase')
    failures, checks = [], {}
    process, observed = None, None
    try:
        require(handle._backend and handle._backend.proc.poll() is None, 'no live frozen backend')
        current = runtime_components(handle.collection, handle.config, handle.env)
        require(index.identity_digests(current) == handle.runtime_identity, 'runtime inputs changed')
        entry = read_json(handle.root/'entry.json')
        index._check_owner(read_json(handle.data_dir/'quality-owner.json'), handle.collection, handle.index_key)
        with httpx.Client(base_url=handle.base_url, timeout=30) as client:
            process = runtime_process(handle, client)
            sensitivity.require_idle(index._get(client, '/api/status'))
        if 'before' in handle.observations:
            require(process['process'] == handle.observations['before']['runtime']['process']
                    and process['manifest']['instanceId'] == handle.observations['before']['runtime']['manifest']['instanceId'],
                    'Engine incarnation changed')
        m = replace(handle.collection, version='', documents=read_json(handle.root/'materialized.json'))
        observed = index.witness(handle.base_url, handle.collection, m, handle.documents_dir, handle.config)
        failures.extend(index._adoption_agreement(entry['witness'], observed))
        adopted = index_identity.confirm_adoption(handle.base_url, entry, handle.data_dir, handle.env,
                                                  index_key=handle.index_key)
        failures.extend(adopted.failures)
        checks.update(adopted.checks)
        require(payload(handle.data_dir, handle.item['payload']['generation']) == handle.item['payload'],
                'frozen generation payload/control/schema drift')
        if not failures:
            handle.index_state = {**observed, 'settled': True, 'index_cache_key': handle.index_key}
            handle.observations[phase] = {'runtime': process, 'witness': observed, 'checks': checks,
                                         'payload_sha256': digest(handle.item['payload'])}
    except (httpx.HTTPError, index.TransientIndexRefused) as e:
        failures.append(f'serving.error: {type(e).__name__}: {e}')
        checks['serving'] = {'ok': False, 'transient': True}
    except Exception as e:
        failures.append(f'{type(e).__name__}: {e}')
    # Always retain partial interface observations, including refusal evidence.
    index._write(checked_path(handle.run_path.parent/EVIDENCE)/(phase+'.json'), {
        'runtime': process, 'witness': observed, 'checks': checks,
        'payload_sha256': digest(handle.item['payload']), 'failures': failures})
    return index_identity.ConfirmResult(not failures, failures, checks)


def candidate_binding(record):
    header = record.header.to_dict()
    header['frozen_index'] = dict(header['frozen_index'])
    header['frozen_index'].pop('evidence_sha256', None)
    return digest({'header': header, 'results': [r.to_dict() for r in record.results]})


def _evidence_file(parent, relative):
    require(isinstance(relative, str) and relative.startswith(EVIDENCE+'/')
            and len(Path(relative).parts) == 2 and '..' not in Path(relative).parts,
            'invalid frozen evidence reference')
    return checked_path(parent/relative)


def validate_record_evidence(path: Path, record: RunRecord):
    h = record.header
    require(h.run_kind == 'frozen-index', 'not a frozen record')
    checked_path(path)
    if h.ended is None:
        # Partial records are inspectable; validate_run always refuses them.
        require('evidence_sha256' not in h.frozen_index, 'partial frozen record claims finalized evidence')
        return None
    sidecar = read_bytes(path.parent/SIDECAR)
    require(hashlib.sha256(sidecar).hexdigest() == h.frozen_index['evidence_sha256'], 'frozen sidecar digest mismatch')
    doc = json.loads(sidecar)
    require(doc.get('candidate_semantic') == candidate_binding(record), 'frozen candidate/evidence binding mismatch')
    require(doc.get('schema') == SCHEMA and doc.get('source') == [h.commit, h.dirty_hash]
            and h.dirty_hash is None and doc.get('runtime_identity') == h.runtime_identity
            and doc.get('identity') == h.identity and doc.get('index_key') == h.index_key
            and doc.get('baseline_run_sha256') == h.frozen_index['baseline_run_sha256'],
            'frozen sidecar identity mismatch')
    runtime_agreement(h.identity, h.runtime_identity)
    require(doc.get('runtime_receipt') == {'schema': distribution.SCHEMA,
            'source': [h.commit, h.dirty_hash], 'digest': h.runtime_identity['distribution']},
            'missing current source/snapshot receipt')
    expected = {f'{EVIDENCE}/{name}' for name in (*RETAINED, 'runtime-inputs.json', 'before.json', 'after.json', 'post_stop.json')}
    require(isinstance(doc.get('files'), dict) and set(doc['files']) == expected, 'incomplete frozen evidence files')
    for relative, sha in doc['files'].items():
        require(hashlib.sha256(read_bytes(_evidence_file(path.parent, relative))).hexdigest() == sha,
                f'frozen evidence digest mismatch: {relative}')
    folder = path.parent/EVIDENCE
    runtime_inputs = read_json(folder/'runtime-inputs.json')
    require(index.identity_digests(runtime_inputs) == h.runtime_identity,
            'runtime input receipt does not match runtime identity')
    braw = read_bytes(folder/'baseline-run.txt')
    b = RunRecord.loads(braw.decode(), 'frozen original baseline')
    require(b.header.run_kind == 'standard' and hashlib.sha256(braw).hexdigest() == h.frozen_index['baseline_run_sha256'],
            'wrong original baseline bytes')
    metadata_raw = read_bytes(folder/'baseline.json')
    metadata = json.loads(metadata_raw)
    require(hashlib.sha256(metadata_raw).hexdigest() == doc['baseline_sha256']
            and metadata['run_sha256'] == h.frozen_index['baseline_run_sha256']
            and metadata['collection'] == h.collection and metadata['collection_version'] == h.collection_version,
            'original baseline pointer mismatch')
    require(len(record.results) == len(b.results) == len({r.qid for r in b.results})
            and len(record.results) == len({r.qid for r in record.results})
            and {r.qid: r.query_text_sha256 for r in record.results} == {r.qid: r.query_text_sha256 for r in b.results}
            and all(r.error is None for r in (*record.results, *b.results)), 'incomplete frozen query evidence')
    for name in ('collection', 'collection_version', 'index_key', 'index_population', 'identity',
                 'index_build', 'index_cache_key', 'model_hashes', 'request_config', 'expected_legs', 'expected_reranker'):
        require(getattr(b.header, name) == getattr(h, name), f'changed frozen authority: {name}')
    item = read_json(folder/'copy.json')
    entry = read_json(folder/'entry.json')
    owner = read_json(folder/'quality-owner.json')
    require(item['baseline_run_sha256'] == h.frozen_index['baseline_run_sha256'] and item['runtime_identity'] == h.runtime_identity
            and item['baseline_sha256'] == doc['baseline_sha256'] and item['index_key'] == h.index_key
            and entry['schema'] == owner['schema'] == index.SCHEMA
            and owner == entry['owner'] and owner['index_key'] == h.index_key
            and owner['collection'] == h.collection and owner['documents'] == runtime_inputs['documents']
            and owner['index_population'] == h.index_population
            and entry['identity']['components']['index_key'] == h.index_key
            and entry['identity']['key'] == digest(entry['identity']['components']), 'frozen owner/copy authority mismatch')
    require(item['source_data']['quality-owner.json']['sha256'] == hashlib.sha256(read_bytes(folder/'quality-owner.json')).hexdigest(),
            'original owner inventory mismatch')
    manifest = read_json(folder/'materialized.json')
    require(set(manifest) == set(entry['witness']['content'])
            and len(set(manifest.values())) == len(manifest)
            and all(canonical_relpath(rel) == rel for rel in manifest.values())
            and {rel for rel, row in item['source_documents'].items() if row['kind'] == 'file'} == set(manifest.values())
            and all(item['source_documents'][rel]['sha256'] == entry['witness']['content'][doc] for doc, rel in manifest.items()),
            'original materialization/content evidence mismatch')
    require(read_bytes(folder/'manifest.tsv').decode() == canonical_manifest_text(manifest), 'original manifest bytes mismatch')
    require(item['payload']['generation'] == entry['attestation']['generation_id'] == entry['witness']['generation']
            and item['payload']['state']['active_generation'] == item['payload']['generation']
            and item['payload']['state']['migration_state'] == 'IDLE'
            and item['payload']['state']['migration_paused'] is False, 'original generation evidence mismatch')
    for name in METADATA:
        require(hashlib.sha256(read_bytes(folder/name)).hexdigest() == item['source_metadata'][name], 'changed original metadata')
    require(not index._adoption_agreement(entry['witness'], b.header.index_state), 'stored original witness mismatch')
    observations = {phase: read_json(folder/(phase+'.json')) for phase in CHECKS}
    for phase in ('before', 'after'):
        row = observations[phase]
        require(not index._adoption_agreement(entry['witness'], row['witness'])
                and row['payload_sha256'] == digest(item['payload']), 'stored live frozen witness mismatch')
        require(set(row['checks']) >= {'identity', 'binding', 'generation', 'counts', 'canary', 'watched_roots'}
                and all(check.get('ok') is True for check in row['checks'].values()), 'failed/missing live frozen checks')
        runtime = row['runtime']
        require(Path(runtime['cwd']).is_absolute() and Path(runtime['cwd']).name == h.runtime_identity['distribution']
                and runtime['process']['pid'] > 0 and runtime['process']['pid'] == runtime['manifest']['pid']
                and runtime['manifest'].get('instanceId')
                and runtime['command'] and Path(runtime['command'][0]).is_absolute()
                and runtime['command'][0].endswith('/bin/java')
                and any(runtime['cwd'] in arg for arg in runtime['command'][1:]), 'missing current runtime process evidence')
    require(observations['before']['runtime']['process'] == observations['after']['runtime']['process']
            and observations['before']['runtime']['manifest']['instanceId'] == observations['after']['runtime']['manifest']['instanceId'],
            'stored Engine incarnation changed')
    require(not index._adoption_agreement(observations['after']['witness'], h.index_state), 'header/live witness mismatch')
    post = observations['post_stop']
    require(post.get('payload') == item['payload'] and post.get('processes_ended') is True
            and post.get('runtime_identity') == h.runtime_identity
            and post.get('source_data_sha256') == digest(item['source_data'])
            and post.get('source_documents_sha256') == digest(item['source_documents'])
            and post.get('cleanup') == 'retained-owned-clone', 'incomplete frozen shutdown/postflight')
    return doc


_PAIR_SEAL = object()


@dataclass(frozen=True, init=False)
class FrozenPair:
    """Immutable raw and normalized semantic anchors, granted only by strict file validation."""
    _seal: object
    candidate_sha256: str
    baseline_sha256: str
    pointer_sha256: str
    evidence_sha256: str
    candidate_semantic: str
    baseline_semantic: str

    def matches_candidate(self, candidate):
        return (getattr(self, '_seal', None) is _PAIR_SEAL and candidate.header.run_kind == 'frozen-index'
                and sha256_text(candidate.dumps()) == self.candidate_semantic)

    def matches(self, baseline, candidate):
        return self.matches_candidate(candidate) and sha256_text(baseline.dumps()) == self.baseline_semantic


def validated_pair(candidate_path: Path, baseline_path: Path):
    """Bind actual candidate, evidence and actual original pointer/run bytes; no caller hashes."""
    candidate_raw = read_bytes(candidate_path)
    candidate = RunRecord.loads(candidate_raw.decode(), str(candidate_path))
    evidence = validate_record_evidence(candidate_path, candidate)
    require(evidence is not None and candidate.header.ended is not None, 'incomplete frozen candidate')
    baseline, metadata = compare.load_baseline(checked_path(baseline_path))
    raw_baseline = read_bytes(baseline_path.parent/metadata['run_file'])
    pointer = read_bytes(baseline_path)
    require(raw_baseline == read_bytes(candidate_path.parent/EVIDENCE/'baseline-run.txt')
            and pointer == read_bytes(candidate_path.parent/EVIDENCE/'baseline.json'), 'supplied original baseline differs from frozen evidence')
    context = object.__new__(FrozenPair)
    for key, value in {'_seal': _PAIR_SEAL, 'candidate_sha256': hashlib.sha256(candidate_raw).hexdigest(),
            'baseline_sha256': hashlib.sha256(raw_baseline).hexdigest(), 'pointer_sha256': hashlib.sha256(pointer).hexdigest(),
            'evidence_sha256': candidate.header.frozen_index['evidence_sha256'],
            'candidate_semantic': sha256_text(candidate.dumps()), 'baseline_semantic': sha256_text(baseline.dumps())}.items():
        object.__setattr__(context, key, value)
    return baseline, metadata, candidate, context


def context_matches(context, candidate, baseline=None):
    return (type(context) is FrozenPair and context.matches_candidate(candidate)
            and (baseline is None or context.matches(baseline, candidate)))
