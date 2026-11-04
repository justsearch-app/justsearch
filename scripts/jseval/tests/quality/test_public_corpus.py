"""Pinned recipes are sufficient offline; originals are treated as opaque bytes."""
from copy import deepcopy
import hashlib
import io
import json
from pathlib import Path
import tarfile
from urllib.error import HTTPError
import zipfile

import pytest

from jseval import dataset_cache
from jseval.quality import collections


@pytest.fixture
def corpus():
    from jseval.quality import corpus
    return corpus


@pytest.fixture
def fixture_recipe(corpus, tmp_path, monkeypatch):
    monkeypatch.setattr(corpus, 'CACHE_ROOT', tmp_path)
    monkeypatch.setenv('JUSTSEARCH_DATASET_CACHE', str(tmp_path / 'raw'))
    payloads = [b'unchanged raw PDF \x00\xff', b'original office document \x00\xfe',
                b'Subject: Meeting\n\nThe original message.\n',
                b'<html><p>Clean energy &amp; storage.</p></html>']
    artifacts, docs = [], []
    for n, (archive, ext) in enumerate([('zip', 'pdf'), ('zip', 'doc'),
                                       ('tar.gz', 'eml'), (None, 'md')]):
        member = f'original/member{n}.{ext}'
        stream = io.BytesIO()
        if archive == 'zip':
            with zipfile.ZipFile(stream, 'w') as z:
                z.writestr(member, payloads[n])
        elif archive == 'tar.gz':
            with tarfile.open(fileobj=stream, mode='w:gz') as tar:
                info = tarfile.TarInfo(member)
                info.size = len(payloads[n])
                tar.addfile(info, io.BytesIO(payloads[n]))
        else:
            stream.write(payloads[n])
        data = stream.getvalue()
        a = {'id': f'artifact-{n}', 'url': f'https://example.org/archive{n}',
             'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
        if archive:
            a['archive'] = archive
        artifacts.append(a)
        params = {k: a[k] for k in ('url', 'sha256')}
        dataset_cache.store('public-real-use', params, filenames=['payload'],
                            populate=lambda dest, data=data: (dest / 'payload').write_bytes(data))
        content = (corpus.wikipedia_note(payloads[n].decode(), 'Energy storage')
                   if n == 3 else payloads[n])
        d = {'id': f'opaque-document-{n}', 'path': f'Personal/Reference {n}.{ext}',
             'artifact': a['id'], 'format': ext, 'language': 'de' if n == 3 else 'en',
             'source': 'wikipedia' if n == 3 else 'govdocs1', 'source_id': member,
             'origin_url': a['url'], 'internal_ids': [f'internal-member-{n}'],
             'bytes': len(content), 'sha256': hashlib.sha256(content).hexdigest(),
             'license': {'name': 'test licence', 'url': 'https://example.org/licence',
                         'attribution': 'Test attribution'}}
        if archive:
            d['member'] = member
        if n == 3:
            d.update(transform='wikipedia-note', title='Energy storage')
        docs.append(d)
    return {'schema': corpus.SCHEMA, 'artifacts': artifacts, 'documents': docs}


def test_fetch_materialize_offline_twice_and_corruption_refusal(corpus, fixture_recipe, tmp_path, monkeypatch):
    def no_network(*_):
        pytest.fail('verified raw cache must be sufficient')
    monkeypatch.setattr(corpus.PublicHTTP, 'open', no_network)
    assert corpus.fetch(fixture_recipe)['files'] == 4
    first = corpus.materialize(fixture_recipe, tmp_path / 'first')
    second = corpus.materialize(fixture_recipe, tmp_path / 'second')
    assert {p.relative_to(first): p.read_bytes() for p in first.rglob('*') if p.is_file()} == {
        p.relative_to(second): p.read_bytes() for p in second.rglob('*') if p.is_file()}
    assert corpus.materialize(fixture_recipe, first) == first
    path = first / fixture_recipe['documents'][0]['path']
    path.write_bytes(b'changed')
    with pytest.raises(collections.CollectionError, match='checksum mismatch'):
        corpus.materialize(fixture_recipe, first)


def test_raw_cache_corruption_and_wrong_member_checksum_refused(corpus, fixture_recipe, tmp_path):
    broken = deepcopy(fixture_recipe)
    broken['documents'][0]['sha256'] = 'f' * 64
    with pytest.raises(collections.CollectionError, match='checksum mismatch'):
        corpus.materialize(broken, tmp_path / 'wrong-member')
    assert not (tmp_path / 'wrong-member').exists()
    artifact = fixture_recipe['artifacts'][0]
    path = corpus._raw(artifact, fetch=False)
    path.write_bytes(b'wrong raw artifact')
    with pytest.raises(collections.CollectionError, match='missing or corrupt'):
        corpus.materialize(fixture_recipe, tmp_path / 'wrong-artifact')


def test_download_is_verified_before_cache_publication(corpus, fixture_recipe, tmp_path, monkeypatch):
    a = fixture_recipe['artifacts'][0]
    original = corpus._raw(a, fetch=False).read_bytes()
    monkeypatch.setenv('JUSTSEARCH_DATASET_CACHE', str(tmp_path / 'fresh-raw'))
    monkeypatch.setattr(corpus.PublicHTTP, 'open', lambda *_a, **_k: io.BytesIO(b'changed upstream'))
    with pytest.raises(collections.CollectionError, match='checksum mismatch'):
        corpus._raw(a, fetch=True, client=corpus.PublicHTTP())
    assert dataset_cache.lookup('public-real-use', {k: a[k] for k in ('url', 'sha256')},
                                filenames=['payload']) is None
    monkeypatch.setattr(corpus.PublicHTTP, 'open', lambda *_a, **_k: io.BytesIO(original + b'excess'))
    with pytest.raises(collections.CollectionError, match='exceeds recorded size'):
        corpus._raw(a, fetch=True, client=corpus.PublicHTTP())
    monkeypatch.setattr(corpus.PublicHTTP, 'open', lambda *_a, **_k: io.BytesIO(original))
    assert corpus._raw(a, fetch=True, client=corpus.PublicHTTP()).read_bytes() == original


@pytest.mark.parametrize('change,reason', [
    (lambda r: r['documents'][0].update(path='../../outside.pdf'), 'escapes'),
    (lambda r: r['documents'][0].update(path='Folder/opaque-document-1.pdf'), 'identifier'),
    (lambda r: r['documents'][0].update(path='Folder/internal-member-2.pdf'), 'identifier'),
    (lambda r: r['documents'][0].update(path='Folder/OPAQUE-DOCUMENT-1.pdf'), 'identifier'),
    (lambda r: r['documents'][0].update(member='../outside.pdf'), 'escapes'),
    (lambda r: r['documents'][0].update(pages=41), 'page cap'),
    (lambda r: r['documents'][0]['license'].pop('attribution'), 'licence'),
    (lambda r: r['documents'][0].update(sha256='wrong'), 'checksum'),
    (lambda r: r['artifacts'][0].update(url='file:///private/archive.zip'), 'HTTP'),
    (lambda r: r['documents'][0].update(transform='wikipedia-note'), 'only Wikipedia'),
])
def test_recipe_validation(corpus, fixture_recipe, change, reason):
    change(fixture_recipe)
    with pytest.raises(ValueError, match=reason):
        corpus.validate_recipe(fixture_recipe)


def test_symlinks_and_extra_population_refused(corpus, fixture_recipe, tmp_path):
    root = corpus.materialize(fixture_recipe, tmp_path / 'documents')
    extra = root / 'extra.txt'
    extra.write_text('extra')
    with pytest.raises(collections.CollectionError, match='population'):
        corpus.materialize(fixture_recipe, root)
    extra.unlink()
    target = root / fixture_recipe['documents'][0]['path']
    backup = tmp_path / 'outside.pdf'
    target.rename(backup)
    target.symlink_to(backup)
    with pytest.raises(collections.CollectionError, match='population'):
        corpus.materialize(fixture_recipe, root)


def test_http_honours_robots_and_rate_limits(corpus, monkeypatch):
    from urllib.response import addinfourl
    calls = []
    def open_request(request, **_):
        calls.append(request.full_url)
        if request.full_url.endswith('robots.txt'):
            return addinfourl(io.BytesIO(b'User-agent: *\nDisallow: /private\n'), {}, request.full_url, 200)
        raise HTTPError(request.full_url, 429, 'rate limited', {'Retry-After': '60'}, None)
    monkeypatch.setattr(corpus, 'urlopen', open_request)
    monkeypatch.setattr(corpus, '_document_open', open_request)
    with pytest.raises(collections.CollectionError, match='robots'):
        corpus.PublicHTTP().open('https://example.org/private/file')
    assert calls == ['https://example.org/robots.txt']
    with pytest.raises(HTTPError) as error:
        corpus.PublicHTTP().open('https://example.org/public/file')
    assert error.value.code == 429


def test_redirect_checks_target_policy_before_request(corpus, monkeypatch):
    from urllib.response import addinfourl
    requests = []
    def robots(request, **_):
        return addinfourl(io.BytesIO(b'User-agent: *\nDisallow: /private\n'), {}, request.full_url, 200)
    def document(request, **_):
        requests.append(request.full_url)
        raise HTTPError(request.full_url, 302, 'redirect',
                        {'Location': 'https://other.example.org/private/file'}, None)
    monkeypatch.setattr(corpus, 'urlopen', robots)
    monkeypatch.setattr(corpus, '_document_open', document)
    with pytest.raises(collections.CollectionError, match='robots'):
        corpus.PublicHTTP().open('https://example.org/download')
    assert requests == ['https://example.org/download']


def test_robots_wildcards_specific_allow_and_terminal_rules(corpus):
    from urllib.robotparser import RobotFileParser
    parser = RobotFileParser()
    parser.parse(['User-agent: *', 'Disallow: /*?*', 'Disallow: /draft*.pdf$',
                  'Allow: /public?download=1'])
    assert not corpus._robots_allowed(parser, 'https://example.org/data?download=1')
    assert not corpus._robots_allowed(parser, 'https://example.org/draft report.pdf')
    assert corpus._robots_allowed(parser, 'https://example.org/draft report.pdf.html')
    assert corpus._robots_allowed(parser, 'https://example.org/public?download=1')


def test_committed_recipe_and_slice(corpus):
    recipe = corpus.load_recipe()
    assert 2000 <= len(recipe['documents']) <= 3900
    from collections import Counter
    counts = Counter(d['source'] for d in recipe['documents'])
    for source, target in {'govdocs1': 1000, 'enron': 500, 'bundestag': 450,
                           'eurlex': 400, 'govdata': 200, 'wikipedia': 250,
                           'safedocs': 200}.items():
        assert .7 * target <= counts[source] <= 1.3 * target
    parliamentary = [d for d in recipe['documents'] if d['source'] == 'bundestag']
    assert any('/Drucksachen/' in d['path'] for d in parliamentary)
    assert any('/Plenarprotokolle/' in d['path'] for d in parliamentary)
    assert {d['language'] for d in recipe['documents']} == {'en', 'de'}
    assert len({d['path'].casefold() for d in recipe['documents']}) == len(recipe['documents'])
    assert {'pdf', 'doc', 'xls', 'ppt', 'html', 'txt', 'csv', 'eml', 'md'} <= {
        d['format'] for d in recipe['documents']}
    from urllib.parse import urlsplit
    for d in recipe['documents']:
        if d['source'] == 'govdata':
            assert d['license']['name'] in ('dl-de/by-2.0', 'dl-de/zero-2.0')
            assert d['bytes'] <= 512000
        if d['source'] == 'safedocs':
            assert d['language_evidence']['tika_eval_lang'] == 'deu'
            assert 1 <= d['pages'] <= 40
            assert urlsplit(d['origin_url']).hostname.endswith(('.de', '.at', '.ch'))
            assert d['sha256'] == d['provenance']['fetched_digest']
            assert d['language_evidence']['url_id'] == d['provenance']['url_id']
    manifest = (corpus.RECIPE_DIR / 'real-use.manifest.tsv').read_text()
    from jseval.quality.types import canonical_manifest_text
    assert manifest == canonical_manifest_text({d['id']: d['path'] for d in recipe['documents']})
    c, cfg = collections.resolve_collection('real-use-slice')
    assert cfg.layout == 'as-is' and cfg.witness_formats == ('md',)
    assert c.counts() == {'documents': 50, 'queries': 50, 'judgments': 50,
                          'positives': 50, 'grades': {'2': 50}}
    rows = {d['id']: d for d in recipe['documents']}
    assert {rows[d]['source'] for d in c.documents} == {
        'govdocs1', 'enron', 'bundestag', 'eurlex', 'govdata', 'wikipedia', 'safedocs'}
    for qid, query in c.queries.items():
        assert 2 <= len(query.text.split()) <= 4
        assert set(query.groups) == {'lang', 'kind'}
        assert query.groups['kind'] == 'file-name'
        doc, = c.judgments[qid]
        assert Path(c.documents[doc]).stem.casefold() != query.text.casefold()
