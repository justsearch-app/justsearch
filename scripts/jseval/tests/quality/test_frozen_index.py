"""Model-free frozen boundaries: actual files, OS locks and stored-reader interfaces."""
from dataclasses import replace
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import sys
import time

from click.testing import CliRunner
import pytest

from jseval import backend
from jseval.cli import main
from jseval.quality import compare, distribution, frozen_index as frozen, index, run
from jseval.quality.types import RunRecord, RunRecordError, canonical_manifest_text, canonical_json, document_text, sha256_text, UnknownDocument
from .conftest import pinned_env, ready_distribution
from .helpers import make_collection, make_run, config_for


def test_cli_exposes_explicit_runtime_free_preparation_and_check():
    cli = CliRunner()
    prep = cli.invoke(main, ['quality', 'prepare-frozen', '--help'])
    assert prep.exit_code == 0 and 'without starting Engine' in ' '.join(prep.output.split()), prep.output
    check = cli.invoke(main, ['quality', 'check', '--help'])
    assert check.exit_code == 0 and '--frozen-indexes' in check.output, check.output
    for command in ('baseline', 'ablate', 'sensitivity', 'full-suite'):
        result = cli.invoke(main, ['quality', command, '--frozen-indexes', '/missing'])
        assert result.exit_code == 2 and 'No such option' in result.output


@pytest.fixture
def source(tmp_path, monkeypatch):
    c = make_collection({'en/vague': 40}, n_docs=8)
    c = replace(c, version='', metadata={**c.metadata, 'layout': 'as-is'}, documents={
        **c.documents, 'd000': 'nested/ü space/same name.txt', 'd001': 'other/same name.txt'})
    models = tmp_path/'models'
    models.mkdir()
    (models/'embed.onnx').write_bytes(b'model fixture')
    cfg = index.IndexConfig(models_dir=models, cache_root=tmp_path/'cache')
    ready_distribution(monkeypatch)
    monkeypatch.setattr(backend, 'source_identity', lambda *_: ('b'*40, None))
    env = pinned_env(cfg)
    distribution.publish(cfg.repo_root, ['b'*40, None], env[index.DISTRIBUTION_ENV])
    runtime = frozen.runtime_components(c, cfg, env)
    identity = {**index.identity_digests(runtime), 'distribution': 'a'*64, 'harness': 'c'*64}
    key = 'd'*64
    root = cfg.cache_root/sha256_text(c.name)/key
    data = root/'data'
    active = data/'index/default/indices/g-test'
    active.mkdir(parents=True)
    (active/'segments_1').write_bytes(b'index bytes')
    (active/'vectors').write_bytes(b'vector bytes')
    (active/'.justsearch-generation.sentinel').write_bytes(b'sentinel')
    (active.parent/'g-test.clean-shutdown').write_bytes(b'clean')
    index._write(data/'index/default/state.json', {'format_version': 2, 'active_generation': 'g-test',
                  'migration_state': 'IDLE', 'migration_paused': False, 'updated_at_ms': 100})
    for lock in (data/'app.lock', data/'index/default.index.lock', root/'data.quality.lock'):
        lock.write_bytes(b'fixture lock contents')
    con = sqlite3.connect(data/'documents.sqlite')
    con.execute('PRAGMA journal_mode=WAL')
    con.execute('CREATE TABLE documents(id TEXT PRIMARY KEY, title TEXT)')
    con.execute("INSERT INTO documents VALUES ('one', 'retained WAL')")
    con.commit()
    stored = {p.name: p.read_bytes() for p in data.glob('documents.sqlite*')}
    con.close()
    for name, raw in stored.items(): (data/name).write_bytes(raw)
    owner = index._population_owner(c, key)
    index._write(data/'quality-owner.json', owner)
    (root/'documents').mkdir()
    for doc, rel in c.documents.items():
        path = root/'documents'/rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(document_text('', f'text of {doc}'))
    index._write(root/'materialized.json', dict(c.documents))
    (root/'manifest.tsv').write_text(canonical_manifest_text(c.documents))
    (root/'salt').write_text('f'*64)
    witness = {'index_population': c.index_population(), 'content': dict(c.content),
               'extracted_content': dict(c.content), 'samples': {'d000': {'chunks': 1}},
               'generation': 'g-test', 'completeness': [{'stageId': 'embed', 'missing': 0}], 'probes': []}
    ident = {'index_key': key, 'fingerprint': 'fixture'}
    entry = {'schema': index.SCHEMA, 'owner': owner, 'witness': witness,
             'identity': {'key': frozen.digest(ident), 'components': ident},
             'attestation': {'generation_id': 'g-test', 'build_state': 'COMPLETE',
                             'watched_roots': [str(root/'documents')],
                             'canary': {'query': 'test canary', 'required_stages': list(index.CANARY_STAGES)}}}
    index._write(root/'entry.json', entry)
    b = make_run(c, index_key=key, index_cache_key=key, index_build='cached', identity=identity,
                 index_state={**witness, 'settled': True}, model_hashes=runtime['models'])
    baselines = tmp_path/'baselines'
    baselines.mkdir()
    b.write(baselines/'original.jsonl')
    baseline = baselines/(sha256_text(c.name)+'.baseline.json')
    compare.write_baseline(baselines/'original.jsonl', c, baseline)
    monkeypatch.setattr(run.collections, 'resolve_collection', lambda *_: (c, config_for(c)))
    monkeypatch.setattr(run.known_answers, 'check', lambda *_a, **_kw: None)
    monkeypatch.setattr(run.collections, 'scored_collection', lambda a, *_: a)
    return c, cfg, root, baseline, b


def prepare_source(source, tmp_path):
    c, cfg, root, baseline, b = source
    out = tmp_path/'copies'
    frozen.prepare(baseline.parent, cfg.cache_root, out, cfg, refs=[c.name])
    return out, out/sha256_text(c.name)


def test_prepare_copies_all_bytes_without_booting_or_rewriting_original(source, tmp_path, monkeypatch):
    c, cfg, root, baseline, b = source
    before = frozen.inventory(root)
    monkeypatch.setattr(backend, 'start_backend', lambda **_: pytest.fail('Engine spawn'))
    monkeypatch.setattr(index, 'prepare_index', lambda *_a, **_k: pytest.fail('ordinary prepare'))
    out, clone = prepare_source(source, tmp_path)
    assert frozen.inventory(root) == before
    assert frozen.inventory(clone/'data') == frozen.inventory(root/'data')
    assert frozen.inspect(out, c, cfg, baseline) == b.header.model_hashes
    assert (clone/'data/documents.sqlite-wal').read_bytes() == (root/'data/documents.sqlite-wal').read_bytes()
    original_wal = (root/'data/documents.sqlite-wal').read_bytes()
    (clone/'data/documents.sqlite-wal').write_bytes(b'clone write')
    assert (root/'data/documents.sqlite-wal').read_bytes() == original_wal
    assert json.loads((clone/'copy.json').read_text())['documents_dir'] == str(root/'documents')
    with pytest.raises(frozen.FrozenRefused, match='changed before scoring'):
        frozen.inspect(out, c, cfg, baseline)


@pytest.mark.parametrize('case', ['link', 'fifo', 'hardlink', 'owner', 'witness', 'salt', 'baseline', 'locked'])
def test_invalid_source_refuses_before_install_or_engine(source, tmp_path, monkeypatch, case):
    c, cfg, root, baseline, b = source
    held = None
    if case == 'link': (root/'data/escape').symlink_to(tmp_path)
    elif case == 'fifo': os.mkfifo(root/'data/fifo')
    elif case == 'hardlink': os.link(root/'data/app.lock', root/'data/alias')
    elif case == 'owner': index._write(root/'data/quality-owner.json', {})
    elif case == 'witness':
        entry = json.loads((root/'entry.json').read_text()); entry['witness']['content'] = {}
        index._write(root/'entry.json', entry)
    elif case == 'salt': (root/'salt').write_text('')
    elif case == 'baseline': (baseline.parent/'original.jsonl').write_text(b.dumps()+'\n')
    else:
        import fcntl
        held = (root/'data/app.lock').open('r+')
        fcntl.lockf(held, fcntl.LOCK_EX | fcntl.LOCK_NB)
    before = {p: p.read_bytes() for p in (root/'entry.json', root/'data/quality-owner.json', baseline)}
    monkeypatch.setattr(index, '_select_distribution', lambda *_: pytest.fail('install/select on invalid source'))
    monkeypatch.setattr(backend, 'start_backend', lambda **_: pytest.fail('Engine spawn'))
    try:
        with pytest.raises((frozen.FrozenRefused, compare.BaselineError, OSError, index.IndexRefused)):
            frozen.prepare(baseline.parent, cfg.cache_root, tmp_path/'copies', cfg, refs=[c.name])
    finally:
        if held: held.close()
    assert {p: p.read_bytes() for p in before} == before
    assert not (tmp_path/'copies').exists()


@pytest.mark.parametrize('case', ['existing', 'ancestor', 'descendant', 'symlink'])
def test_output_ownership_and_overlap_refuse(source, tmp_path, case):
    c, cfg, root, baseline, b = source
    out = tmp_path/'copies'
    if case == 'existing': out.mkdir()
    elif case == 'ancestor': out = cfg.cache_root
    elif case == 'descendant': out = root/'copies'
    else: out.symlink_to(tmp_path/'foreign')
    before = frozen.inventory(root)
    with pytest.raises(frozen.FrozenRefused):
        frozen.prepare(baseline.parent, cfg.cache_root, out, cfg, refs=[c.name])
    assert frozen.inventory(root) == before


@pytest.mark.parametrize('change', ['vector', 'sentinel', 'extra-generation', 'state', 'rebuild-marker', 'schema'])
def test_payload_changes_refuse(source, change):
    _, _, root, _, _ = source
    data = root/'data'
    before = frozen.payload(data, 'g-test')
    active = data/'index/default/indices/g-test'
    if change == 'vector': (active/'vectors').write_bytes(b'new vectors')
    elif change == 'sentinel': (active/'.justsearch-generation.sentinel').write_bytes(b'repaired sentinel')
    elif change == 'extra-generation': (active.parent/'g-foreign').mkdir()
    elif change == 'state':
        state = json.loads((data/'index/default/state.json').read_text()); state['format_version'] += 1
        index._write(data/'index/default/state.json', state)
    elif change == 'rebuild-marker': (active.parent/'g-test.rebuild-pending').write_bytes(b'')
    else:
        con = sqlite3.connect(data/'documents.sqlite'); con.execute('CREATE TABLE migrated(id)'); con.close()
    try: observed = frozen.payload(data, 'g-test')
    except frozen.FrozenRefused: return
    assert observed != before


def test_only_exact_clean_marker_lock_and_timestamp_are_payload_exceptions(source):
    _, _, root, _, _ = source
    data = root/'data'
    before = frozen.payload(data, 'g-test')
    active = data/'index/default/indices/g-test'
    (active/'write.lock').touch()
    (active.parent/'g-test.clean-shutdown').unlink()
    path = data/'index/default/state.json'
    state = json.loads(path.read_text()); state['updated_at_ms'] += 1; index._write(path, state)
    assert frozen.payload(data, 'g-test') == before
    (active/'fake.clean-shutdown').write_bytes(b'not canonical')
    assert frozen.payload(data, 'g-test') != before


def test_actual_java_filechannel_ofd_exclusion_reopen_release_and_caller_death(source, tmp_path):
    _, _, root, _, _ = source
    java = shutil.which('java')
    javac = shutil.which('javac')
    assert java and javac, 'standard JDK required for model-free OS boundary proof'
    code = '''
import java.nio.channels.*;
import java.nio.file.*;
public class ToyLock {
 public static void main(String[] a) throws Exception {
  try (FileChannel c=FileChannel.open(Path.of(a[0]), StandardOpenOption.READ, StandardOpenOption.WRITE)) {
   try (FileLock l=c.tryLock()) {
    if(l==null) System.exit(75);
    if(a.length>1) { Files.writeString(Path.of(a[1]), "held"); Thread.sleep(30000); }
   }
  }
 }
}
'''
    (tmp_path/'ToyLock.java').write_text(code)
    compiled = subprocess.run([javac, '-d', str(tmp_path), str(tmp_path/'ToyLock.java')], capture_output=True, text=True, timeout=20)
    assert compiled.returncode == 0, compiled.stderr
    data = root/'data'
    before = frozen.inventory(root)
    def probe(lock):
        return subprocess.run([java, '-cp', str(tmp_path), 'ToyLock', str(lock)], capture_output=True, text=True, timeout=10)
    locks = (data/'app.lock', data/'index/default.index.lock')
    with frozen.source_locks(data):
        for lock in locks:
            assert probe(lock).returncode == 75
            # The regression: a POSIX lock on this process would be lost here.
            frozen.read_bytes(lock)
            with lock.open('rb') as stream: hashlib.file_digest(stream, 'sha256')
            assert probe(lock).returncode == 75
    for lock in locks: assert probe(lock).returncode == 0
    ready = tmp_path/'holder.ready'
    child = subprocess.Popen([sys.executable, '-c',
        'from pathlib import Path; import sys,time; from jseval.quality.frozen_index import source_locks; '
        'scope=source_locks(Path(sys.argv[1])); scope.__enter__(); Path(sys.argv[2]).touch(); time.sleep(30)',
        str(data), str(ready)])
    try:
        deadline = time.monotonic()+10
        while not ready.exists() and child.poll() is None and time.monotonic()<deadline: time.sleep(.02)
        assert ready.exists(), 'lock holder did not become ready'
        assert all(probe(lock).returncode == 75 for lock in locks)
        child.kill(); child.wait(timeout=5)
        assert all(probe(lock).returncode == 0 for lock in locks)
    finally:
        if child.poll() is None: child.kill(); child.wait(timeout=5)
    # A Java writer excludes even the read-only copier, and releases on death.
    ready.unlink()
    writer = subprocess.Popen([java, '-cp', str(tmp_path), 'ToyLock', str(locks[0]), str(ready)])
    try:
        deadline = time.monotonic()+10
        while not ready.exists() and writer.poll() is None and time.monotonic()<deadline: time.sleep(.02)
        assert ready.exists()
        with pytest.raises(BlockingIOError):
            with frozen.source_locks(data): pytest.fail('copier admitted a Java writer')
        writer.kill(); writer.wait(timeout=5)
        with frozen.source_locks(data): pass
    finally:
        if writer.poll() is None: writer.kill(); writer.wait(timeout=5)
    assert frozen.inventory(root) == before


@pytest.fixture
def retained_pair(source, tmp_path):
    """Synthetic live observations for reader checks; this is not a live runtime proof."""
    c, cfg, origin, baseline_path, baseline = source
    out, clone = prepare_source(source, tmp_path)
    path = tmp_path/'runs/0/run.jsonl'
    path.parent.mkdir(parents=True)
    guard = frozen.run_resources(out, [(c, cfg, baseline_path)])
    guard.__enter__()
    handle = frozen.open_handle(out, c, cfg, baseline_path, path)
    try:
        checks = {k: {'ok': True} for k in ('identity', 'binding', 'generation', 'counts', 'canary', 'watched_roots')}
        runtime = {'process': {'pid': 123, 'pgid': 123, 'birth': '100', 'state': 'S'},
                   'cwd': '/retained/observed/'+handle.runtime_identity['distribution'],
                   'command': ['/retained/jdk/bin/java', '/retained/observed/'+handle.runtime_identity['distribution']+'/app.jar'],
                   'manifest': {'pid': 123, 'instanceId': 'fixture', 'dataDir': str(clone/'data')}}
        observation = {'runtime': runtime, 'witness': json.loads(canonical_json(baseline.header.index_state)),
                       'checks': checks, 'payload_sha256': frozen.digest(handle.item['payload'])}
        handle.observations.update(before=observation, after=observation)
        handle.index_state = dict(baseline.header.index_state)
        handle.close()
        candidate = replace(baseline, header=replace(baseline.header, commit='b'*40,
            run_kind='frozen-index', runtime_identity=handle.runtime_identity,
            frozen_index={'baseline_run_sha256': handle.item['baseline_run_sha256'], 'evidence_sha256': '0'*64}))
        evidence = handle.finalize(candidate)
        candidate = replace(candidate, header=replace(candidate.header, frozen_index=evidence))
        candidate.write(path)
    finally:
        handle.stack.close()
        guard.__exit__(None, None, None)
    return c, baseline_path, path, baseline, candidate


def test_strict_pair_reload_move_and_default_direct_refusal(retained_pair, tmp_path):
    c, baseline_path, candidate_path, baseline, candidate = retained_pair
    loaded = RunRecord.read(candidate_path)
    baseline, metadata, loaded, context = frozen.validated_pair(candidate_path, baseline_path)
    assert not compare.validate_run(c, loaded, {c.version}, 'candidate', frozen_context=context)
    assert compare.compare(c, config_for(c), baseline, loaded, baseline_metrics=metadata,
                           frozen_context=context).verdict.outcome.value == 'PASS'
    for untrusted in (candidate, RunRecord.loads(candidate.dumps()), loaded):
        result = compare.compare(c, config_for(c), baseline, untrusted)
        assert result.verdict.outcome.value == 'INVALID'
        assert 'validated' in ' '.join(result.verdict.reasons)
    # Readers never follow the old absolute clone/cache/runtime paths.
    destination = tmp_path/'retained-move'
    shutil.copytree(candidate_path.parent, destination)
    shutil.rmtree(tmp_path/'copies')
    moved = destination/'run.jsonl'
    b, metrics, moved_record, pair = frozen.validated_pair(moved, baseline_path)
    assert pair.matches(b, moved_record)
    assert compare.compare(c, config_for(c), b, moved_record, frozen_context=pair).verdict.outcome.value == 'PASS'


@pytest.mark.parametrize('change', ['ranking', 'nested-header', 'candidate-kind', 'baseline-ranking', 'baseline-nested', 'duck-context'])
def test_in_memory_mutation_and_direct_context_constructor_cannot_bypass(retained_pair, change):
    c, bp, cp, _, _ = retained_pair
    b, metrics, n, pair = frozen.validated_pair(cp, bp)
    if change == 'ranking': n = replace(n, results=(replace(n.results[0], ranked=()),)+n.results[1:])
    elif change == 'nested-header': n.header.index_state['samples']['d000']['chunks'] = 2
    elif change == 'candidate-kind': n = replace(n, header=replace(n.header, run_kind='standard', runtime_identity={}, frozen_index={}))
    elif change == 'baseline-ranking': b = replace(b, results=(replace(b.results[0], ranked=()),)+b.results[1:])
    elif change == 'baseline-nested': b.header.index_state['samples']['d000']['chunks'] = 2
    else:
        class Duck:
            def matches(self, *_): return True
            def matches_candidate(self, *_): return True
        pair = Duck()
    result = compare.compare(c, config_for(c), b, n, frozen_context=pair)
    # Downgrading a loaded frozen record must also fail with the original pair context.
    assert result.verdict.outcome.value == 'INVALID'
    assert not frozen.context_matches(frozen.FrozenPair(), n, b)


@pytest.mark.parametrize('change', ['missing', 'sidecar', 'reference', 'file', 'candidate', 'baseline-pointer', 'baseline-run', 'payload', 'runtime', 'owner'])
def test_strict_actual_file_binding_refuses_tampering(retained_pair, change):
    c, bp, cp, _, _ = retained_pair
    sibling = cp.parent/frozen.SIDECAR
    evidence = cp.parent/frozen.EVIDENCE
    if change == 'missing': sibling.unlink()
    elif change == 'sidecar': sibling.write_text(sibling.read_text()+' ')
    elif change == 'reference':
        doc = json.loads(sibling.read_text()); doc['files']['../escape'] = '0'*64
        index._write(sibling, doc)
        n = RunRecord.loads(cp.read_text()); replace(n, header=replace(n.header, frozen_index={
            **n.header.frozen_index, 'evidence_sha256': hashlib.sha256(sibling.read_bytes()).hexdigest()})).write(cp)
    elif change == 'file': (evidence/'before.json').write_text('{}')
    elif change == 'candidate':
        n = RunRecord.loads(cp.read_text()); replace(n, results=(replace(n.results[0], ranked=()),)+n.results[1:]).write(cp)
    elif change == 'baseline-pointer': bp.write_text(bp.read_text()+' ')
    elif change == 'baseline-run':
        b, meta = compare.load_baseline(bp); (bp.parent/meta['run_file']).write_text(b.dumps()+' ')
    else:
        name = {'payload': 'post_stop.json', 'runtime': 'after.json', 'owner': 'quality-owner.json'}[change]
        doc = json.loads((evidence/name).read_text())
        if change == 'payload': doc['payload']['indices']['g-test/vectors']['sha256'] = '0'*64
        elif change == 'runtime': doc['runtime']['process']['birth'] = '200'
        else: doc['index_key'] = 'e'*64
        index._write(evidence/name, doc)
        sidecar = json.loads(sibling.read_text()); sidecar['files'][f'{frozen.EVIDENCE}/{name}'] = hashlib.sha256((evidence/name).read_bytes()).hexdigest()
        index._write(sibling, sidecar)
        n = RunRecord.loads(cp.read_text()); replace(n, header=replace(n.header, frozen_index={
            **n.header.frozen_index, 'evidence_sha256': hashlib.sha256(sibling.read_bytes()).hexdigest()})).write(cp)
    with pytest.raises((ValueError, OSError, KeyError)):
        frozen.validated_pair(cp, bp)


def test_frozen_runs_cannot_be_replacement_baselines_or_normal_history(retained_pair, tmp_path):
    c, bp, cp, b, n = retained_pair
    with pytest.raises(compare.BaselineError, match='cannot be a baseline'):
        compare.write_baseline(cp, c)
    pointer = tmp_path/'forged.baseline.json'
    doc = json.loads(bp.read_text()); doc['run_file'] = os.path.relpath(cp, pointer.parent)
    doc['run_sha256'] = hashlib.sha256(cp.read_bytes()).hexdigest(); index._write(pointer, doc)
    with pytest.raises(compare.BaselineError, match='cannot be a baseline'):
        compare.load_baseline(pointer)
    # Raw historical evidence has an archival role, never a produced-run suffix.
    # Keep exact original bytes and the original pointer's run_file observation.
    archive = cp.parent/frozen.EVIDENCE/'baseline-run.txt'
    original_pointer = json.loads(bp.read_text())
    original_raw = (bp.parent/original_pointer['run_file']).read_bytes()
    assert archive.read_bytes() == original_raw
    assert hashlib.sha256(archive.read_bytes()).hexdigest() == original_pointer['run_sha256']
    assert json.loads((archive.parent/'baseline.json').read_text()) == original_pointer
    assert not list(archive.parent.glob('*.jsonl'))
    ready_archive = tmp_path/'copies'/sha256_text(c.name)/'baseline-run.txt'
    assert ready_archive.read_bytes() == original_raw
    assert not list(ready_archive.parent.glob('*.jsonl'))
    assert run._history(c, dict(n.header.request_config), dict(n.header.model_hashes), cp.parent.parent) == []
    assert not compare.validate_run(c, b, {c.version}, 'baseline')


def test_nested_unicode_spaces_file_uris_and_same_basename_use_original_manifest(source, tmp_path):
    c, cfg, root, baseline, b = source
    m, _ = frozen.materialization(c, root)
    assert m.doc_id_for_location((root/'documents'/c.documents['d000']).as_uri(), root/'documents') == 'd000'
    assert m.doc_id_for_location(str(root/'documents'/c.documents['d001']), root/'documents') == 'd001'
    for location in ('same name.txt', str(tmp_path/'foreign/same name.txt')):
        with pytest.raises(UnknownDocument): m.doc_id_for_location(location, root/'documents')


@pytest.mark.parametrize('ending', ['success', 'error', 'deadline', 'caller-death', 'worker-death'])
def test_actual_bound_admission_guardian_retains_source_and_snapshot_until_detached_writer_ends(source, tmp_path, ending):
    """No Engine: actual unchanged native binding/admission, Java writer and detached Python tree."""
    import fcntl
    import select
    import signal
    import psutil
    c, cfg, origin, baseline, b = source
    repo = Path(__file__).resolve().parents[4]
    fixture = tmp_path/'bound-checkout'
    spec = json.loads((repo/'contracts/native-targets/spec.v1.json').read_text())
    specfile = fixture/'contracts/native-targets/spec.v1.json'
    specfile.parent.mkdir(parents=True)
    specfile.write_text(json.dumps(spec))
    helper = fixture/'modules/ui/build/install/ui'/spec['targets']['linux-x86_64']['paths']['bound-exec']
    helper.parent.mkdir(parents=True)
    compiled = subprocess.run(['cc', '-O2', '-std=c11', '-o', str(helper), str(repo/'native/bound-exec/bound-exec.c')],
                              capture_output=True, text=True, timeout=20)
    assert compiled.returncode == 0, compiled.stderr
    java_code = '''
import java.nio.channels.*; import java.nio.file.*; import java.io.*;
public class LockProbe {
 public static void main(String[] args) throws Exception {
  BufferedReader in=new BufferedReader(new InputStreamReader(System.in)); String line;
  while((line=in.readLine())!=null) {
   try(FileChannel c=FileChannel.open(Path.of(line),StandardOpenOption.READ,StandardOpenOption.WRITE)) {
    try(FileLock lock=c.tryLock()) { System.out.println(lock==null?75:0); System.out.flush(); }
   }
  }
 }
}
'''
    (tmp_path/'LockProbe.java').write_text(java_code)
    compiled = subprocess.run(['javac', '-d', str(tmp_path), str(tmp_path/'LockProbe.java')], capture_output=True, text=True, timeout=20)
    assert compiled.returncode == 0, compiled.stderr
    probe = subprocess.Popen(['java', '-cp', str(tmp_path), 'LockProbe'], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE, text=True, bufsize=1)
    def java_lock(path):
        probe.stdin.write(str(path)+'\n'); probe.stdin.flush()
        assert select.select([probe.stdout], [], [], 3)[0], 'Java lock probe stalled'
        return int(probe.stdout.readline().strip())
    writer_script = tmp_path/'writer.py'
    writer_script.write_text('''import os,signal,time,json,sys
from pathlib import Path
signal.signal(signal.SIGTERM,signal.SIG_IGN)
Path(sys.argv[1]).write_text(json.dumps({'pid':os.getpid()}))
while True: time.sleep(.01)
''')
    worker_script = tmp_path/'worker.py'
    worker_script.write_text('''import os,subprocess,sys,time,json
from pathlib import Path
p=subprocess.Popen([sys.executable,sys.argv[1],sys.argv[2]],start_new_session=True)
Path(sys.argv[3]).write_text(json.dumps({'pid':os.getpid()}))
while not Path(sys.argv[4]).exists(): time.sleep(.01)
sys.exit(7 if sys.argv[5]=='error' else 0)
''')
    caller_script = tmp_path/'caller.py'
    caller_script.write_text('''import os,sys,json,subprocess
from pathlib import Path
from jseval import backend
from jseval.quality import frozen_index as frozen,distribution
root,data,dists,snapshot,admission,worker,writer,writerpid,workerpid,finish,ending=map(str,sys.argv[1:])
distribution.ROOT=Path(dists)
with frozen.source_locks(Path(data)) as descriptors, distribution.lease(snapshot) as lease:
 command=[sys.executable,admission,'exclusive','--purpose','isolated-frozen-lifetime','--gate-timeout','2',
          '--drain-timeout','2','--timeout','2' if ending=='deadline' else '10','--',
          sys.executable,worker,writer,writerpid,workerpid,finish,ending]
 child=subprocess.Popen(backend.bind_command(Path(root),command),pass_fds=(*descriptors,lease.fileno()))
 sys.exit(child.wait())
''')
    env = os.environ.copy()
    env.pop('JS_ADMISSION_TOKEN', None)
    env['JS_ADMISSION_DIR'] = str(tmp_path/'private-admission')
    dists, snapshot = distribution.ROOT, index._select_distribution()
    writerpid, workerpid, finish = tmp_path/'writer.json', tmp_path/'worker.json', tmp_path/'finish'
    source_before = frozen.inventory(origin)
    foreign = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)'])
    log = (tmp_path/'guardian.log').open('w')
    caller = subprocess.Popen([sys.executable, str(caller_script), str(fixture), str(origin/'data'), str(dists), snapshot,
        str(repo/'scripts/dev/admission.py'), str(worker_script), str(writer_script), str(writerpid), str(workerpid),
        str(finish), ending], env=env, stdout=log, stderr=subprocess.STDOUT)
    writer = worker = None
    leasefile = dists/'snapshots'/(snapshot+'.lock')
    def prune_available():
        fd = os.open(leasefile, os.O_RDONLY)
        try:
            try: fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError: return False
            return True
        finally: os.close(fd)
    def live(pid):
        try: return psutil.Process(pid).is_running() and psutil.Process(pid).status() != psutil.STATUS_ZOMBIE
        except psutil.NoSuchProcess: return False
    try:
        until = time.monotonic()+10
        while not (writerpid.exists() and workerpid.exists()) and caller.poll() is None and time.monotonic()<until:
            time.sleep(.01)
        assert writerpid.exists() and workerpid.exists(), (tmp_path/'guardian.log').read_text()
        writer, worker = json.loads(writerpid.read_text())['pid'], json.loads(workerpid.read_text())['pid']
        # Record incarnation before any signal. No foreign/name-based signalling.
        writer_birth, worker_birth = index._process(writer)['birth'], index._process(worker)['birth']
        for lock in (origin/'data/app.lock', origin/'data/index/default.index.lock'):
            assert java_lock(lock) == 75
            frozen.read_bytes(lock)
            assert java_lock(lock) == 75
        assert not prune_available()
        if ending == 'caller-death': caller.kill()
        elif ending == 'worker-death':
            assert index._process(worker)['birth'] == worker_birth
            fd = os.pidfd_open(worker)
            try: signal.pidfd_send_signal(fd, signal.SIGKILL)
            finally: os.close(fd)
        elif ending in ('success', 'error'): finish.touch()
        # The persistent Java probe is already running before death, avoiding JVM startup races.
        until = time.monotonic()+12
        saw_guardian_hold = False
        while time.monotonic()<until:
            was_alive = live(writer)
            locked = java_lock(origin/'data/app.lock') == 75
            remains_alive = live(writer)
            if was_alive and remains_alive:
                assert locked, 'source exclusion released while detached owned writer survives'
                assert not prune_available(), 'snapshot lease released while writer survives'
                saw_guardian_hold = True
            if caller.poll() is not None and not remains_alive and not live(worker) and not locked and prune_available():
                break
            time.sleep(.005)
        else: pytest.fail('guardian failed to reap/release by its bounded fixture deadline')
        assert caller.wait(timeout=2) == {'success': 0, 'error': 7, 'deadline': 124,
                                        'caller-death': -signal.SIGKILL, 'worker-death': 137}[ending]
        if ending == 'caller-death': assert saw_guardian_hold, 'did not observe guardian as final resource holder'
        assert not live(writer) and not live(worker)
        assert java_lock(origin/'data/index/default.index.lock') == 0
        assert prune_available()
        assert foreign.poll() is None
        assert frozen.inventory(origin) == source_before
    finally:
        if caller.poll() is None: caller.kill()
        caller.wait(timeout=5)
        for pid in (writer, worker):
            if pid is not None and live(pid):
                # Only this test's recorded process identity may be stopped on a failed assertion.
                born = writer_birth if pid == writer else worker_birth
                if index._process(pid)['birth'] == born:
                    fd = os.pidfd_open(pid)
                    try: signal.pidfd_send_signal(fd, signal.SIGKILL)
                    finally: os.close(fd)
        foreign.terminate(); foreign.wait(timeout=5)
        probe.terminate(); probe.wait(timeout=5)
        log.close()


@pytest.mark.parametrize('failure', ['short-copy', 'source-race', 'interrupted'])
def test_interrupted_short_or_raced_copy_is_never_published(source, tmp_path, monkeypatch, failure):
    c, cfg, origin, baseline, b = source
    original = shutil.copytree
    before = frozen.inventory(origin)
    def copy(src, dst, *args, **kwargs):
        outer = Path(src) == origin/'data'
        if outer and failure == 'interrupted': raise KeyboardInterrupt('copy interrupted')
        result = original(src, dst, *args, **kwargs)
        if outer:
            if failure == 'short-copy': (Path(dst)/'index/default/indices/g-test/vectors').unlink()
            else: (origin/'data/index/default/indices/g-test/vectors').write_bytes(b'concurrent fixture mutation')
        return result
    monkeypatch.setattr(shutil, 'copytree', copy)
    monkeypatch.setattr(backend, 'start_backend', lambda **_: pytest.fail('Engine spawn'))
    with pytest.raises((frozen.FrozenRefused, KeyboardInterrupt)):
        frozen.prepare(baseline.parent, cfg.cache_root, tmp_path/'copies', cfg, refs=[c.name])
    assert not (tmp_path/'copies').exists()
    assert all(not (p/frozen.READY).exists() for p in tmp_path.glob('copies.partial-*'))
    if failure != 'source-race': assert frozen.inventory(origin) == before


def test_runtime_model_drift_and_missing_parent_hold_refuse_before_boot(source, tmp_path, monkeypatch):
    c, cfg, origin, baseline, b = source
    out, clone = prepare_source(source, tmp_path)
    monkeypatch.setattr(backend, 'start_backend', lambda **_: pytest.fail('Engine spawn'))
    with pytest.raises(frozen.FrozenRefused, match='retained OFD'):
        frozen.open_handle(out, c, cfg, baseline, tmp_path/'run/run.jsonl')
    (cfg.models_dir/'embed.onnx').write_bytes(b'different fixture model')
    with pytest.raises(frozen.FrozenRefused, match='non-code inputs differ'):
        frozen.inspect(out, c, cfg, baseline)


def test_launch_and_shutdown_failures_cannot_finalize_evidence(source, tmp_path, monkeypatch):
    from types import SimpleNamespace
    c, cfg, origin, baseline, b = source
    out, clone = prepare_source(source, tmp_path)
    before = frozen.inventory(origin)
    out2 = tmp_path/'shutdown-copies'
    frozen.prepare(baseline.parent, cfg.cache_root, out2, cfg, refs=[c.name])
    with frozen.run_resources(out, [(c, cfg, baseline)]):
        path = tmp_path/'runs/launch/run.jsonl'; path.parent.mkdir(parents=True)
        handle = frozen.open_handle(out, c, cfg, baseline, path)
        monkeypatch.setattr(backend, 'start_backend', lambda **_: (_ for _ in ()).throw(RuntimeError('fixture launch failure')))
        try:
            with pytest.raises(RuntimeError, match='launch failure'):
                handle.wait(deadline=time.monotonic()+5)
        finally: handle.close()
        assert (path.parent/frozen.EVIDENCE/'runtime-inputs.json').exists()
        assert not (path.parent/frozen.SIDECAR).exists()
        with pytest.raises(frozen.FrozenRefused, match='incomplete frozen lifecycle'):
            handle.finalize(b)
        path = tmp_path/'runs/shutdown/run.jsonl'; path.parent.mkdir(parents=True)
        handle = frozen.open_handle(out2, c, cfg, baseline, path)
        handle._backend = SimpleNamespace(proc=SimpleNamespace(pid=99999999, poll=lambda: None))
        monkeypatch.setattr(backend, 'stop_backend', lambda *_a, **_k: (_ for _ in ()).throw(RuntimeError('fixture shutdown failure')))
        with pytest.raises(RuntimeError, match='shutdown failure'): handle.close()
        with pytest.raises(frozen.FrozenRefused, match='incomplete frozen lifecycle'): handle.finalize(b)
        assert not (path.parent/frozen.SIDECAR).exists()
    assert frozen.inventory(origin) == before


def test_run_and_evidence_destination_overlap_refuses_before_creation(source, tmp_path):
    c, cfg, origin, baseline, b = source
    out, _ = prepare_source(source, tmp_path)
    before = frozen.inventory(origin)
    for destination in (origin/'new-runs', out/'new-runs', baseline.parent/'new-runs', origin.parent):
        with pytest.raises(frozen.FrozenRefused):
            frozen.check_run_destination(out, destination, [(c, cfg, baseline)])
        assert not (destination/'new-run.jsonl').exists()
    assert frozen.inventory(origin) == before


def test_real_cli_score_compare_require_original_pair_and_refuse_baseline_store(retained_pair, tmp_path):
    c, bp, cp, b, n = retained_pair
    cli = CliRunner()
    scored = cli.invoke(main, ['quality', 'score', c.name, str(cp), '--baseline', str(bp)])
    assert scored.exit_code == 0 and scored.output.startswith('VALID'), scored.output
    refused = cli.invoke(main, ['quality', 'score', c.name, str(cp)])
    assert refused.exit_code == 2 and 'requires --baseline' in refused.output, refused.output
    refused = cli.invoke(main, ['quality', 'score', c.name, str(cp), '--baseline', str(bp),
                                '--store-baseline', str(tmp_path/'forbidden.baseline.json')])
    assert refused.exit_code == 2 and not (tmp_path/'forbidden.baseline.json').exists()
    compared = cli.invoke(main, ['quality', 'compare', c.name, '--candidate', str(cp), '--baseline', str(bp),
                                '--expect-commit', 'b'*40, '--report', str(tmp_path/'comparison.md')])
    assert compared.exit_code == 0 and compared.output.startswith('PASS'), compared.output
    assert 'original index-build snapshot' in (tmp_path/'comparison.md').read_text()
    refused = cli.invoke(main, ['quality', 'compare', c.name, '--candidate', str(cp), '--baseline', str(bp),
                                '--expect-commit', 'b'*40, '--intended', 'index_key'])
    assert refused.exit_code == 2 and 'permits only intended=commit' in refused.output, refused.output
