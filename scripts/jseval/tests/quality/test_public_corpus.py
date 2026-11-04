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
    monkeypatch.setattr(corpus, '_document_open', open_request)
    with pytest.raises(collections.CollectionError, match='robots'):
        corpus.PublicHTTP().open('https://example.org/private/file')
    assert calls == ['https://example.org/robots.txt']
    with pytest.raises(HTTPError) as error:
        corpus.PublicHTTP().open('https://example.org/public/file')
    assert error.value.code == 429


@pytest.mark.parametrize('extension', ['Crawl-delay: 1', 'Request-rate: 1/10'])
def test_http_robots_extensions_do_not_split_access_group(corpus, monkeypatch, extension):
    from urllib.response import addinfourl
    calls = []
    text = ('User-agent: JustSearch-quality-corpus\n' + extension + '\n'
            'User-agent: OtherBot\nDisallow: /private\n')
    def open_request(request, **_):
        calls.append(request.full_url)
        data = text.encode() if request.full_url.endswith('robots.txt') else b'OK'
        return addinfourl(io.BytesIO(data), {}, request.full_url, 200)
    monkeypatch.setattr(corpus, '_document_open', open_request)
    monkeypatch.setattr(corpus.time, 'sleep', lambda _: None)
    with pytest.raises(collections.CollectionError, match='robots.txt forbids'):
        corpus.PublicHTTP().open('https://example.org/private/file')
    assert calls == ['https://example.org/robots.txt']


def test_redirect_checks_target_policy_before_request(corpus, monkeypatch):
    from urllib.response import addinfourl
    requests = []
    def robots(request, **_):
        return addinfourl(io.BytesIO(b'User-agent: *\nDisallow: /private\n'), {}, request.full_url, 200)
    def document(request, **_):
        requests.append(request.full_url)
        raise HTTPError(request.full_url, 302, 'redirect',
                        {'Location': 'https://other.example.org/private/file'}, None)
    monkeypatch.setattr(corpus, '_document_open', lambda request, **kw:
                        robots(request, **kw) if request.full_url.endswith('robots.txt')
                        else document(request, **kw))
    with pytest.raises(collections.CollectionError, match='robots'):
        corpus.PublicHTTP({'example.org': ['other.example.org']}).open('https://example.org/download')
    assert requests == ['https://example.org/download']


def test_robots_wildcards_specific_allow_and_terminal_rules(corpus):
    parser = corpus._RobotsPolicy(['User-agent: *', 'Disallow: /*?*', 'Disallow: /draft*.pdf$',
                                  'Allow: /public?download=1'])
    assert not corpus._robots_allowed(parser, 'https://example.org/data?download=1')
    assert not corpus._robots_allowed(parser, 'https://example.org/draft report.pdf')
    assert corpus._robots_allowed(parser, 'https://example.org/draft report.pdf.html')
    assert corpus._robots_allowed(parser, 'https://example.org/public?download=1')


@pytest.mark.parametrize('rules,path,allowed', [
    # RFC 9309 §5.1 and §5.2: prefix rules, longest match and case sensitivity.
    ('Disallow: /example/\nDisallow: *.gif$\nAllow: /publications/', '/example/page', False),
    ('Disallow: /example/\nDisallow: *.gif$\nAllow: /publications/', '/image.gif', False),
    ('Disallow: /example/\nDisallow: *.gif$\nAllow: /publications/', '/image.gif.html', True),
    ('Disallow: /example/\nDisallow: *.gif$\nAllow: /publications/', '/publications/image.gif', True),
    ('Allow: /example/page/\nDisallow: /example/page/disallowed.gif',
     '/example/page/disallowed.gif', False),
    ('Allow: /example/page/\nDisallow: /example/page/disallowed.gif',
     '/example/page/allowed.gif', True),
    ('Disallow: /Private', '/private', True),
    ('Disallow: /', '', False),
    ('Allow: /same\nDisallow: /same', '/same', True),
    ('Disallow: /same\nAllow: /same', '/same', True),
    ('Disallow: /same*\nAllow: /same$', '/same', True),
    ('Disallow: /exact$', '/exact', False),
    ('Disallow: /exact$', '/exact/more', True),
    ('Disallow: /exact$', '/exact?query=1', True),
    ('Disallow: /path/*/end$', '/path//end', False),
    ('Disallow: /path/*/end$', '/path/middle/other/end', False),
    ('Disallow: /path/*/end$', '/path/middle/end/more', True),
    ('Disallow: /a*b*c$', '/abc', False),
    ('Disallow: /a*b*c$', '/abcbxc', False),
    ('Disallow: /a*b*c$', '/acb', True),
    ('Disallow: /*?download=*', '/data?download=1', False),
    ('Disallow: /*?download=*', '/data#?download=1', True),
    ('Disallow: /data?$', '/data?', False),
    ('Disallow: /data?$', '/data', True),
    # RFC 9309 §2.2.2 and §2.2.3: percent-encoding examples and distinctions.
    ('Disallow: /foo/bar?baz=quz', '/foo/bar?baz=quz', False),
    ('Disallow: /foo/bar?baz=https%3A%2F%2Ffoo.bar',
     '/foo/bar?baz=https%3a%2f%2ffoo.bar', False),
    ('Disallow: /foo/bar/ツ', '/foo/bar/%E3%83%84', False),
    ('Disallow: /foo/bar/%E3%83%84', '/foo/bar/ツ', False),
    ('Disallow: /foo/bar/baz', '/foo/bar/%62%61%7A', False),
    ('Disallow: /foo/bar/%62%61%7A', '/foo/bar/baz', False),
    ('Disallow: /a%2Fb', '/a/b', True),
    ('Disallow: /a/b', '/a%2Fb', True),
    ('Disallow: /a%2Fb', '/a%2fb', False),
    ('Disallow: /a%3Fb', '/a?b', True),
    ('Disallow: /a%23b # comment', '/a%23b', False),
    ('Disallow: /path/file-with-a-%2A.html', '/path/file-with-a-*.html', False),
    ('Disallow: /path/file-with-a-%2A.html', '/path/file-with-a-name.html', True),
    ('Disallow: /path/foo-%24', '/path/foo-$', False),
    ('Disallow: /caf%C3%A9\nAllow: /café', '/café', True),
    ('Disallow: \nAllow:', '/anything', True),
])
def test_robots_rfc9309_path_examples(corpus, rules, path, allowed):
    policy = corpus._RobotsPolicy(('User-agent: *\n' + rules).splitlines())
    assert corpus._robots_allowed(policy, 'https://example.org' + path) is allowed


@pytest.mark.parametrize('text,path,allowed', [
    ('User-agent: OtherBot\nDisallow: /private\nUser-agent: *\nDisallow: /fallback',
     '/fallback', False),
    ('User-agent: OtherBot\nDisallow: /private', '/private', True),
    ('Disallow: /private\nUser-agent: *\nAllow: /', '/private', True),
    ('User-agent: *\nDisallow: /\nUser-agent: JustSearch-quality-corpus', '/private', True),
    ('User-agent: *\nDisallow: /\nUser-agent: JUSTSEARCH-QUALITY-CORPUS\nDisallow: /private',
     '/public', True),
    ('User-agent: OtherBot\nUser-agent: JustSearch-quality-corpus\nDisallow: /private',
     '/private', False),
    ('User-agent: JustSearch-quality-corpus\nDisallow: /one\n'
     'User-agent: OtherBot\nDisallow: /\n'
     'User-agent: JustSearch-quality-corpus\nDisallow: /two', '/two', False),
    ('User-agent: JustSearch-quality-corpus\nDisallow: /\n'
     'User-agent: JustSearch-quality-corpus\nAllow: /public', '/public', True),
    ('User-agent: *\nDisallow: /one\nUser-agent: *\nDisallow: /two', '/two', False),
    ('\ufeffuSeR-aGeNt: *\nSitemap: https://example.org/sitemap\n'
     'not a directive\nDiSaLlOw: /private # reason\n\nAllow: /private/public', '/private', False),
])
def test_robots_agent_groups(corpus, text, path, allowed):
    assert corpus._robots_allowed(corpus._RobotsPolicy(text.splitlines()),
                                  'https://example.org' + path) is allowed


@pytest.mark.parametrize('directives,delay', [
    ('Crawl-delay: 4', 4),
    ('Request-rate: 2/10', 5),
    ('Crawl-delay: 4\nRequest-rate: 2/10', 5),
    ('Crawl-delay: invalid\nRequest-rate: 0/10', .25),
    ('Crawl-delay: 20\nUser-agent: JustSearch-quality-corpus\nCrawl-delay: 2', 2),
    ('Crawl-delay: 20\nUser-agent: JustSearch-quality-corpus\nCrawl-delay: 2\n'
     'User-agent: JustSearch-quality-corpus\nRequest-rate: 1/6', 6),
    ('Allow: /\nUser-agent: JustSearch\nCrawl-delay: 8', 8),
    ('Allow: /\nUser-agent: quality-corpus\nRequest-rate: 1/10', 10),
    ('Allow: /\nUser-agent: JUSTSEARCH\nCrawl-delay: 8\nRequest-rate: 1/10', 10),
    ('Allow: /\nUser-agent: JustSearch\nCrawl-delay: 12\nDisallow: /private\n'
     'User-agent: JustSearch-quality-corpus\nCrawl-delay: 2', 12),
    ('Allow: /\nUser-agent: OtherJustSearch\nCrawl-delay: 8\nRequest-rate: 1/10', .25),
])
def test_http_robots_pacing_and_timeouts(corpus, monkeypatch, directives, delay):
    from urllib.response import addinfourl
    calls, sleeps = [], []
    def open_request(request, *, timeout):
        calls.append((request.full_url, timeout))
        data = ('User-agent: *\n' + directives).encode() if request.full_url.endswith('robots.txt') else b'OK'
        return addinfourl(io.BytesIO(data), {}, request.full_url, 200)
    monkeypatch.setattr(corpus, '_document_open', open_request)
    monkeypatch.setattr(corpus.time, 'monotonic', lambda: 100)
    monkeypatch.setattr(corpus.time, 'sleep', sleeps.append)
    client = corpus.PublicHTTP()
    for _ in range(2):
        with client.open('https://example.org/document') as response:
            assert response.read() == b'OK'
    assert calls == [('https://example.org/robots.txt', 60),
                     ('https://example.org/document', 120), ('https://example.org/document', 120)]
    assert sleeps == [0, delay, delay]


@pytest.mark.parametrize('text,delay', [
    pytest.param('User-agent: JustSearch\nRequest-rate: 1 / 10', 10,
                 id='spaced-request-rate'),
    pytest.param('User-agent: JustSearch\nCrawl-delay: ٨', 8,
                 id='arabic-indic-crawl-delay'),
    pytest.param('User-agent:\nCrawl-delay: 8', 8,
                 id='empty-user-agent'),
    pytest.param('User-agent: JustSearch\nDisallow: /document\nCrawl-delay: 8', 8,
                 id='legacy-pacing-does-not-control-access'),
])
def test_http_robots_legacy_pacing_minimum(corpus, monkeypatch, text, delay):
    from urllib.response import addinfourl
    calls, sleeps = [], []
    def open_request(request, **_):
        calls.append(request.full_url)
        data = text.encode() if request.full_url.endswith('robots.txt') else b'OK'
        return addinfourl(io.BytesIO(data), {}, request.full_url, 200)
    monkeypatch.setattr(corpus, '_document_open', open_request)
    monkeypatch.setattr(corpus.time, 'monotonic', lambda: 100)
    monkeypatch.setattr(corpus.time, 'sleep', sleeps.append)
    client = corpus.PublicHTTP()
    for _ in range(2):
        with client.open('https://example.org/document') as response:
            assert response.read() == b'OK'
    assert calls == ['https://example.org/robots.txt', 'https://example.org/document',
                     'https://example.org/document']
    assert sleeps[0] == 0
    assert len(sleeps) == 3
    assert all(wait >= delay for wait in sleeps[1:])


@pytest.mark.parametrize('failure', [OSError('connection failed'), ValueError('invalid response'),
                                   HTTPError('https://example.org/robots.txt', 503, 'unreachable', {}, None),
                                   HTTPError('https://example.org/robots.txt', 429, 'rate limited', {}, None)])
def test_http_failed_robots_check_refuses_document(corpus, monkeypatch, failure):
    calls = []
    def open_request(request, **_):
        calls.append(request.full_url)
        raise failure
    monkeypatch.setattr(corpus, '_document_open', open_request)
    with pytest.raises(collections.CollectionError, match='robots'):
        corpus.PublicHTTP().open('https://example.org/document')
    assert calls == ['https://example.org/robots.txt']


@pytest.fixture
def http_site():
    """Exercise urllib's real redirect handling on an owned loopback server."""
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
    from threading import Thread
    routes, calls = {}, []
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            calls.append((self.headers['Host'], self.path))
            status, headers, body = routes.get(self.path, (200, {}, b'OK'))
            self.send_response(status)
            for key, value in headers.items():
                self.send_header(key, value)
            self.end_headers()
            self.wfile.write(body)
        def log_message(self, *_):
            pass
    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f'http://127.0.0.1:{server.server_port}', routes, calls
    finally:
        server.shutdown()
        thread.join()
        server.server_close()


def test_real_http_redirect_checks_robots_query_before_target_contact(corpus, http_site):
    url, routes, calls = http_site
    routes['/robots.txt'] = (200, {}, b'User-agent: *\nDisallow: /*?download=*\n')
    routes['/document'] = (302, {'Location': '/private?download=1'}, b'')
    with pytest.raises(collections.CollectionError, match='robots'):
        corpus.PublicHTTP().open(url + '/document')
    assert [path for _, path in calls] == ['/robots.txt', '/document']


@pytest.mark.parametrize('status', [403, 404])
def test_unavailable_robots_permits_document(corpus, http_site, status):
    url, routes, calls = http_site
    routes['/robots.txt'] = (status, {}, b'Unavailable')
    with corpus.PublicHTTP().open(url + '/document') as response:
        assert response.read() == b'OK'
    assert [path for _, path in calls] == ['/robots.txt', '/document']


def test_robots_download_is_capped(corpus, http_site):
    url, routes, calls = http_site
    routes['/robots.txt'] = (200, {}, b'#' * (512 * 1024 + 1))
    with pytest.raises(collections.CollectionError, match='robots'):
        corpus.PublicHTTP().open(url + '/document')
    assert [path for _, path in calls] == ['/robots.txt']


@pytest.mark.parametrize('redirect_robots', [False, True])
def test_cross_host_redirect_refused_before_target_contact(corpus, http_site, redirect_robots):
    url, routes, calls = http_site
    start = '/robots.txt' if redirect_robots else '/document'
    routes[start] = (302, {'Location': url.replace('127.0.0.1', 'localhost') + '/target'}, b'')
    with pytest.raises(collections.CollectionError):
        corpus.PublicHTTP().open(url + '/document')
    assert all(host.startswith('127.0.0.1:') for host, _ in calls)
    assert '/target' not in [path for _, path in calls]


@pytest.mark.parametrize('redirect_robots', [False, True])
def test_declared_redirect_host_is_allowed(corpus, http_site, redirect_robots):
    url, routes, calls = http_site
    start = '/robots.txt' if redirect_robots else '/document'
    routes[start] = (302, {'Location': url.replace('127.0.0.1', 'localhost') + '/target'}, b'')
    with corpus.PublicHTTP({'127.0.0.1': ['localhost']}).open(url + '/document') as response:
        assert response.read() == b'OK'
    assert any(host.startswith('localhost:') and path == '/target' for host, path in calls)


@pytest.mark.parametrize('redirect_robots', [False, True])
def test_redirect_limit_includes_robots(corpus, http_site, redirect_robots):
    url, routes, calls = http_site
    start = '/robots.txt' if redirect_robots else '/document'
    routes[start] = (302, {'Location': '/hop-1'}, b'')
    for n in range(1, 7):
        routes[f'/hop-{n}'] = (302, {'Location': f'/hop-{n + 1}'}, b'')
    with pytest.raises(collections.CollectionError, match='too many redirects'):
        corpus.PublicHTTP().open(url + '/document')
    assert '/hop-5' in [path for _, path in calls]
    assert '/hop-6' not in [path for _, path in calls]


@pytest.mark.parametrize('target', ['file:///tmp/private', 'https://user:pass@example.org/private'])
def test_redirect_url_checks_include_robots(corpus, http_site, target):
    url, routes, calls = http_site
    routes['/robots.txt'] = (302, {'Location': target}, b'')
    with pytest.raises(collections.CollectionError, match='robots'):
        corpus.PublicHTTP().open(url + '/document')
    assert [path for _, path in calls] == ['/robots.txt']


def test_shared_license_references_preserve_attribution_and_overrides(corpus, fixture_recipe, tmp_path):
    expected = deepcopy(fixture_recipe['documents'])
    fixture_recipe['licenses'] = {'shared': expected[0]['license']}
    for d in fixture_recipe['documents']:
        d['license'] = {'ref': 'shared'}
    fixture_recipe['documents'][1]['license'].update(name='Specific terms', attribution='Article revision 123')
    expected[1]['license'].update(name='Specific terms', attribution='Article revision 123')
    path = tmp_path / 'recipe.json'
    path.write_text(json.dumps(fixture_recipe))
    assert corpus.load_recipe(path)['documents'] == expected
    fixture_recipe['documents'][0]['license']['ref'] = 'missing'
    with pytest.raises(ValueError, match='unknown licence reference'):
        corpus.validate_recipe(fixture_recipe)


@pytest.mark.parametrize('kind', ['skipped', 'metadata'])
def test_tar_aggregate_expansion_is_capped(corpus, fixture_recipe, tmp_path, monkeypatch, kind):
    # A selected two-byte file after a compressible skipped member or PAX header.
    # Neither expansion passes through the selected-member size check.
    recipe = deepcopy(fixture_recipe)
    artifact, doc = recipe['artifacts'][2], recipe['documents'][2]
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode='w:gz') as tar:
        info = tarfile.TarInfo('skipped')
        info.size = 2 * 1024 * 1024 if kind == 'skipped' else 0
        if kind == 'metadata':
            info.pax_headers = {'comment': 'x' * (2 * 1024 * 1024)}
        tar.addfile(info, io.BytesIO(b'x' * info.size))
        info = tarfile.TarInfo(doc['member'])
        info.size = 2
        tar.addfile(info, io.BytesIO(b'OK'))
    payload = stream.getvalue()
    artifact.update(bytes=len(payload), sha256=hashlib.sha256(payload).hexdigest())
    doc.update(bytes=2, sha256=hashlib.sha256(b'OK').hexdigest())
    dataset_cache.store('public-real-use', {k: artifact[k] for k in ('url', 'sha256')},
                        filenames=['payload'], populate=lambda dest: (dest / 'payload').write_bytes(payload))
    monkeypatch.setattr(corpus, 'MAX_TAR_EXPANDED_BYTES', 64 * 1024, raising=False)
    with pytest.raises(collections.CollectionError, match='expanded'):
        corpus.materialize(recipe, tmp_path / 'limited')
    assert not (tmp_path / 'limited').exists()


def test_tar_stops_after_selection(corpus, fixture_recipe, tmp_path):
    import gzip
    recipe = deepcopy(fixture_recipe)
    artifact, doc = recipe['artifacts'][2], recipe['documents'][2]
    original = b'Subject: Meeting\n\nThe original message.\n'
    info = tarfile.TarInfo(doc['member'])
    info.size = len(original)
    # An incomplete PAX header after the selected member must never be parsed.
    trailing = tarfile.TarInfo('truncated metadata')
    trailing.type, trailing.size = tarfile.XHDTYPE, 1024
    payload = gzip.compress(info.tobuf() + original.ljust(512, b'\0') + trailing.tobuf())
    artifact.update(bytes=len(payload), sha256=hashlib.sha256(payload).hexdigest())
    dataset_cache.store('public-real-use', {k: artifact[k] for k in ('url', 'sha256')},
                        filenames=['payload'], populate=lambda dest: (dest / 'payload').write_bytes(payload))
    root = corpus.materialize(recipe, tmp_path / 'selected')
    assert (root / doc['path']).read_bytes() == original


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
    from urllib.parse import parse_qs, urlsplit
    artifacts = {a['id']: a for a in recipe['artifacts']}
    for d in recipe['documents']:
        if d['source'] == 'wikipedia':
            url = urlsplit(artifacts[d['artifact']]['url'])
            assert url.path.startswith('/wiki/')
            assert parse_qs(url.query) == {'oldid': [d['revision']]}
            assert d['transform'] == ('wikipedia-html' if d['format'] == 'html' else 'wikipedia-note')
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


@pytest.mark.parametrize('changed_note', [False, True])
def test_wikipedia_identity_is_derived_before_cache_publication(
        corpus, fixture_recipe, tmp_path, monkeypatch, changed_note):
    recipe = deepcopy(fixture_recipe)
    recipe['artifacts'] = recipe['artifacts'][-1:]
    recipe['documents'] = recipe['documents'][-1:]
    artifact = recipe['artifacts'][0]
    monkeypatch.setenv('JUSTSEARCH_DATASET_CACHE', str(tmp_path / 'fresh-wikipedia'))
    content = b'Changed article.' if changed_note else b'Clean energy &amp; storage.'
    payload = b'<body><p>' + content + b'</p></body>'
    monkeypatch.setattr(corpus.PublicHTTP, 'open', lambda *_a, **_k: io.BytesIO(payload))
    if changed_note:
        with pytest.raises(collections.CollectionError, match='checksum mismatch'):
            corpus.fetch(recipe)
        assert dataset_cache.lookup('public-real-use', {k: artifact[k] for k in ('url', 'sha256')},
                                    filenames=['payload']) is None
    else:
        assert corpus.fetch(recipe)['files'] == 1
        root = corpus.materialize(recipe, tmp_path / 'derived-note')
        assert (root / recipe['documents'][0]['path']).read_bytes() == (
            b'Energy storage\n\nClean energy & storage.\n')


def test_wikipedia_html_keeps_article_structure_without_chrome(corpus):
    article = '''<div class="mw-parser-output"><div class="hatnote"><p>Banner</p></div>
      <p>Energy <b>storage</b> &amp; supply.<sup class="reference">[1]</sup></p>
      <p>Area in m<sup>2</sup>; CO<sub>2</sub> emissions.</p>
      <div class="mw-heading"><h2 id="volatile">Options</h2>
      <span class="mw-editsection">[edit]</span></div>
      <ul><li>Heat</li><li>Electricity</li></ul>
      <table class="wikitable"><caption>Values</caption><tr><th>Power</th><td>20<br>MW</td></tr></table>
      <div class="navbox"><ul><li>Navigation</li></ul></div>
      <script>bad()</script><style>p {color: red}</style></div>'''
    first = corpus.wikipedia_html('<nav><p>Menu</p></nav>' + article, 'Energy & storage')
    second = corpus.wikipedia_html('<script>new skin</script>' + article.replace('volatile', 'other'),
                                   'Energy & storage')
    assert first == second == (
        '<!doctype html>\n<html><head><meta charset="utf-8"><title>Energy &amp; storage</title>'
        '</head><body><h1>Energy &amp; storage</h1><p>Energy storage &amp; supply.</p>'
        '<p>Area in m2; CO2 emissions.</p>'
        '<h2>Options</h2><ul><li>Heat</li><li>Electricity</li></ul>'
        '<table><caption>Values</caption><tr><th>Power</th><td>20 MW</td></tr></table>'
        '</body></html>\n').encode()
    with pytest.raises(collections.CollectionError, match='body missing'):
        corpus.wikipedia_html('<html><p>Error page</p></html>', 'Energy')


def test_wikipedia_html_prefers_article_to_coordinate_indicator(corpus):
    article = '<div class="mw-content-ltr mw-parser-output"><p>Article text.</p></div>'
    indicator = ('<div class="mw-indicators"><div class="mw-parser-output">'
                 '<span>Coordinates</span></div></div>')
    assert corpus.wikipedia_html(indicator + article, 'Energy') == corpus.wikipedia_html(
        article, 'Energy')


@pytest.mark.parametrize('transform', ['wikipedia-note', 'wikipedia-html'])
@pytest.mark.parametrize('body', ['', '<p> \n </p>', '<script>active()</script>'])
def test_wikipedia_empty_body_refused_before_cache_publication(
        corpus, fixture_recipe, tmp_path, monkeypatch, transform, body):
    recipe = deepcopy(fixture_recipe)
    recipe['artifacts'] = recipe['artifacts'][-1:]
    recipe['documents'] = recipe['documents'][-1:]
    doc, artifact = recipe['documents'][0], recipe['artifacts'][0]
    doc['transform'] = transform
    # Even a pin for the old title-only result must not publish an empty body.
    title = doc['title']
    empty = (title + '\n\n\n').encode() if transform == 'wikipedia-note' else (
        f'<!doctype html>\n<html><head><meta charset="utf-8"><title>{title}</title>'
        f'</head><body><h1>{title}</h1></body></html>\n').encode()
    doc.update(bytes=len(empty), sha256=hashlib.sha256(empty).hexdigest())
    monkeypatch.setenv('JUSTSEARCH_DATASET_CACHE', str(tmp_path / 'empty-wikipedia'))
    payload = f'<div class="mw-parser-output">{body}</div>'.encode()
    monkeypatch.setattr(corpus.PublicHTTP, 'open', lambda *_a, **_k: io.BytesIO(payload))
    with pytest.raises(collections.CollectionError, match='body empty'):
        corpus.fetch(recipe)
    assert dataset_cache.lookup('public-real-use', {k: artifact[k] for k in ('url', 'sha256')},
                                filenames=['payload']) is None


def test_wikipedia_html_refuses_deep_nesting(corpus):
    at_limit = ('<div class="mw-parser-output"><p>' + '<span>' * 254
                + 'Article.' + '</span>' * 254 + '</p></div>')
    assert b'<p>Article.</p>' in corpus.wikipedia_html(at_limit, 'Energy')
    html = '<div class="mw-parser-output"><p>Article.</p>' + '<span>' * 256
    with pytest.raises(collections.CollectionError, match='nesting'):
        corpus.wikipedia_html(html, 'Energy')


def test_wikipedia_unmatched_closes_have_bounded_work(corpus):
    import sys
    # Count executed handler lines instead of relying on machine speed. A scan
    # of the 128 open nodes per unmatched close exceeds this fixed work budget.
    closes = 4096
    html = ('<div class="mw-parser-output"><p>Article.</p>' + '<span>' * 128
            + '</unmatched>' * closes + '</div>')
    lines = 0
    def budget(frame, event, arg):
        nonlocal lines
        if (event == 'line' and frame.f_code.co_filename == corpus.__file__
                and frame.f_code.co_name == 'handle_endtag'):
            lines += 1
            assert lines < closes * 32, 'closing-tag work exceeds fixed per-tag budget'
        return budget
    previous = sys.gettrace()
    try:
        sys.settrace(budget)
        assert b'<p>Article.</p>' in corpus.wikipedia_html(html, 'Energy')
    finally:
        sys.settrace(previous)


@pytest.mark.parametrize('change', ['raw', 'article'])
def test_wikipedia_html_identity_and_download_bound(corpus, fixture_recipe, tmp_path, monkeypatch, change):
    recipe = deepcopy(fixture_recipe)
    recipe['artifacts'] = recipe['artifacts'][-1:]
    recipe['documents'] = recipe['documents'][-1:]
    doc = recipe['documents'][0]
    original = '<div class="mw-parser-output"><p>Energy storage.</p></div>'
    expected = corpus.wikipedia_html(original, doc['title'])
    doc.update(transform='wikipedia-html', path='Personal/Reference.html', format='html',
               bytes=len(expected), sha256=hashlib.sha256(expected).hexdigest())
    monkeypatch.setenv('JUSTSEARCH_DATASET_CACHE', str(tmp_path / 'fresh-html'))
    payload = ('<script>new skin</script>' + original.replace(
        'Energy storage.', 'Changed content.' if change == 'article' else 'Energy storage.')).encode()
    monkeypatch.setattr(corpus.PublicHTTP, 'open', lambda *_a, **_k: io.BytesIO(payload))
    if change == 'article':
        with pytest.raises(collections.CollectionError, match='checksum mismatch'):
            corpus.fetch(recipe)
    else:
        assert corpus.fetch(recipe)['files'] == 1
        root = corpus.materialize(recipe, tmp_path / 'derived-html')
        assert (root / doc['path']).read_bytes() == expected
    monkeypatch.setenv('JUSTSEARCH_DATASET_CACHE', str(tmp_path / 'bounded-html'))
    monkeypatch.setattr(corpus, 'MAX_WIKIPEDIA_HTML_BYTES', len(payload) - 1)
    with pytest.raises(collections.CollectionError, match='Wikipedia byte cap'):
        corpus.fetch(recipe)


@pytest.mark.parametrize('transform', ['wikipedia-note', 'wikipedia-html'])
def test_wikipedia_derivations_cannot_relax_other_sources(corpus, fixture_recipe, transform):
    doc = fixture_recipe['documents'][0]
    doc.update(transform=transform, title='Other source')
    with pytest.raises(ValueError, match='only Wikipedia'):
        corpus.validate_recipe(fixture_recipe)
