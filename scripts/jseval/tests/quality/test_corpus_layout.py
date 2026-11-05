"""Opt-in realistic paths and safe source witnesses; historical defaults stay fixed."""
from dataclasses import replace
import json
import os
from pathlib import Path
import subprocess
import sys

import pytest

from jseval.quality import collections, index
from jseval.quality.types import Collection, QueryText, sha256_text


def test_as_is_preserves_path_identity_and_chunks(tmp_path):
    paths = ['Notes/Same.md', 'Mail/Same.md', 'Notes/same.md',
             'Notes/Übersicht %20 #? énergie.md']
    documents = {f'opaque-{i}': p for i, p in enumerate(paths)}
    content = {}
    source = tmp_path / 'source'
    for i, (doc, rel) in enumerate(documents.items()):
        text = f'Words in document {i}\n' * (i + 1)
        target = source / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)
        content[doc] = sha256_text(text)
    c = Collection('names', 'en', {'q': QueryText('overview energy')},
                   {'q': {'opaque-3': 2}}, documents, content, {'layout': 'as-is'})
    root = tmp_path / 'entry'
    root.mkdir()
    cfg = index.IndexConfig(documents_dir=source)
    m = index._materialize(c, cfg, root)
    assert m.documents == documents
    uris = [(root / 'documents' / p).as_uri() for p in paths]
    assert m.ranked_doc_ids([uris[3], uris[3], uris[0], uris[1], uris[2]], root / 'documents') == [
        'opaque-3', 'opaque-0', 'opaque-1', 'opaque-2']
    assert index._materialize(c, cfg, root).documents == documents
    assert replace(c, metadata={}, version='').compute_version() != c.compute_version()
    (root / 'documents' / 'external').symlink_to(source, target_is_directory=True)
    with pytest.raises(index.IndexRefused, match='population'):
        index._materialize(c, cfg, root)


def test_witnesses_never_read_binary_or_restricted_originals(tmp_path, monkeypatch):
    documents = {'binary': 'Downloads/Huge.pdf', 'govdocs': 'Downloads/Raw.txt',
                 'note': 'Notes/Wind.md', 'short': 'Notes/Solar.md'}
    sizes = {'binary': 5000, 'govdocs': 4000, 'note': 1000, 'short': 200}
    for doc, rel in documents.items():
        p = tmp_path / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(b'x' * sizes[doc])
    c = Collection('mixed', 'de', {}, {}, documents,
                   {d: sha256_text('unused') for d in documents},
                   {'layout': 'as-is', 'witness_formats': ['md']})
    cfg = collections.load_config('scifact')
    monkeypatch.setattr(Path, 'read_text', lambda *_a, **_k: pytest.fail('original interpreted'))
    assert index._witness_documents(c, c, tmp_path, 3) == ('note', ['note', 'short'])
    with pytest.raises(index.IndexRefused, match='no eligible'):
        index._witness_documents(replace(c, metadata={'witness_formats': ['eml']}, version=''), c, tmp_path, 3)
    from jseval.quality import corpus
    monkeypatch.setattr(corpus, 'load_recipe', lambda _: {'documents': [
        {'id': 'govdocs', 'source': 'govdocs1'}, {'id': 'binary', 'source': 'safedocs'}]})
    permissive = replace(c, metadata={'layout': 'as-is', 'witness_formats': ['txt', 'md'],
                         'source': {'kind': 'public-recipe', 'recipe': 'fixture'}}, version='')
    assert index._witness_documents(permissive, permissive, tmp_path, 3) == ('note', ['note', 'short'])
    # Renaming a binary original to an opaque .txt must not make it a text
    # witness; conversely a Markdown original remains eligible after renaming.
    opaque_source = replace(c, metadata={'witness_formats': ['md']}, version='')
    opaque_paths = {doc: f'{doc}.txt' for doc in documents}
    for doc, rel in opaque_paths.items():
        (tmp_path / rel).write_bytes(b'x' * sizes[doc])
    opaque = replace(opaque_source, documents=opaque_paths, version='')
    assert index._witness_documents(opaque_source, opaque, tmp_path, 3) == ('note', ['note', 'short'])
    assert cfg.layout == 'opaque' and cfg.witness_formats is None
    assert cfg.identity_metadata() == {}


def test_layout_and_witnesses_key_collection(tmp_path, monkeypatch):
    c = Collection('key', 'en', {}, {}, {'d': 'Notes/Name.md'}, {'d': sha256_text('words')})
    from .conftest import pinned_env
    monkeypatch.setattr(index.inputs, 'model_hashes', lambda *a, **kw: {'model': 'fixed model'})
    cfg = index.IndexConfig(repo_root=tmp_path, models_dir=tmp_path)
    env = pinned_env(cfg)
    opaque = index.compute_index_key(c, cfg, env)[0]
    for metadata in [{'layout': 'as-is'}, {'witness_formats': ['md']}, {'witness_formats': ['txt']}]:
        changed = replace(c, metadata=metadata, version='')
        assert index.compute_index_key(changed, cfg, env)[0] != opaque


def test_scifact_matches_before_under_fixed_tree(tmp_path):
    before = os.environ.get('JSEVAL_QUALITY_BEFORE')
    if not before:
        pytest.skip('set JSEVAL_QUALITY_BEFORE to a shared baseline clone for preservation check')
    # Run each implementation on the full actual SciFact collection, with its own
    # materialization folder. Hold tree/model/runtime inputs fixed explicitly.
    script = '''
import json,sys
from pathlib import Path
from jseval.quality import collections,index
cfg=collections.load_config('scifact'); c=collections.build(cfg)
out=Path(sys.argv[1]); out.mkdir()
(out/'salt').write_text('01'*32)
m=index._materialize(c,index.IndexConfig(),out)
eligible=list(m.documents)
longest=max(eligible,key=lambda d:((out/'documents'/m.documents[d]).stat().st_size,d))
samples=sorted(set([longest]+sorted(eligible)[:2]))
if hasattr(index,'_witness_documents'):
 assert index._witness_documents(c,m,out/'documents',3)==(longest,samples)
# The index key itself changed schema by design; the collection's own identity must not.
print(json.dumps({'version':c.version,'metadata':c.metadata,'counts':c.counts(),
 'manifest':m.documents,'samples':samples,'longest':longest,
 'documents':c.document_identity()},sort_keys=True))
'''
    roots = [Path(before), Path(index.REPO_ROOT)]
    snapshots = []
    for n, root in enumerate(roots):
        env = {**os.environ, 'PYTHONPATH': str(root / 'scripts/jseval')}
        result = subprocess.run([sys.executable, '-c', script, str(tmp_path / f'tree{n}')],
                                env=env, capture_output=True, text=True, check=True)
        snapshots.append(json.loads(result.stdout))
    assert snapshots[0] == snapshots[1]
