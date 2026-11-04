"""Checksum-pinned public file recipes. Document bytes never belong in Git.

Archive handling routes member bytes only; it never invokes a document parser.
Only Wikipedia's explicitly declared HTML-to-note derivation interprets content.
"""
from __future__ import annotations

from collections import Counter, defaultdict
import hashlib
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import shutil
import tarfile
import tempfile
import time
from urllib.parse import unquote, urljoin, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener, urlopen
from urllib.robotparser import RobotFileParser
from urllib.error import HTTPError
import zipfile

from .. import dataset_cache
from .types import Collection, canonical_relpath
from .collections import CollectionError

CACHE_ROOT = Path.home() / '.cache/justsearch-quality'
RECIPE_DIR = Path(__file__).with_name('recipes')
SCHEMA = 'jseval.quality.public-corpus.v1'
USER_AGENT = 'JustSearch-quality-corpus/1.0'
SHA = re.compile(r'[0-9a-f]{64}')


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def _document_open(request, *, timeout):
    return build_opener(_NoRedirect()).open(request, timeout=timeout)


def _robots_allowed(parser: RobotFileParser, url: str) -> bool:
    # RobotFileParser handles groups and crawl-delay, but its matcher treats
    # RFC 9309 '*' and '$' literally. Apply the RFC longest-match rule here.
    entry = next((e for e in parser.entries if e.applies_to(USER_AGENT)), parser.default_entry)
    if entry is None:
        return True
    parts = urlsplit(url)
    path = unquote(parts.path + ('?' + parts.query if parts.query else ''))
    matches = []
    for rule in entry.rulelines:
        pattern = unquote(rule.path)
        terminal = pattern.endswith('$')
        if terminal:
            pattern = pattern[:-1]
        regex = re.escape(pattern).replace(r'\*', '.*') + ('$' if terminal else '')
        if re.match(regex, path):
            matches.append((len(pattern.replace('*', '')), rule.allowance))
    return max(matches, default=(0, True))[1]


def digest(path: Path) -> str:
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def cache_path(path: Path) -> Path:
    path = path.expanduser().resolve()
    if not path.is_relative_to(CACHE_ROOT.resolve()):
        raise CollectionError(f'corpus data must stay under {CACHE_ROOT}: {path}')
    return path


def load_recipe(ref='real-use') -> dict:
    path = Path(ref)
    if not path.suffix:
        path = RECIPE_DIR / f'{ref}.json'
    try:
        recipe = json.loads(path.read_text(encoding='utf-8'))
        validate_recipe(recipe)
    except (OSError, ValueError, KeyError, TypeError) as error:
        raise CollectionError(f'{path}: {error}') from None
    return recipe


def validate_recipe(recipe: dict) -> None:
    if recipe.get('schema') != SCHEMA:
        raise ValueError('not a public-corpus.v1 recipe')
    if not recipe['documents']:
        raise ValueError('empty corpus')
    artifacts = {}
    for a in recipe['artifacts']:
        if a['id'] in artifacts:
            raise ValueError(f'duplicate artifact: {a["id"]}')
        url = urlsplit(a['url'])
        if (url.scheme not in ('https', 'http') or not url.hostname
                or url.username is not None or url.password is not None):
            raise ValueError('artifact URL must be public HTTP(S)')
        if a.get('archive') not in (None, 'zip', 'tar.gz'):
            raise ValueError('unknown archive format')
        if not SHA.fullmatch(a['sha256']) or type(a['bytes']) is not int or a['bytes'] <= 0:
            raise ValueError('invalid artifact checksum/size')
        artifacts[a['id']] = a
    paths, ids = set(), set()
    # Match identifiers across the entire recipe, not just the file's own row.
    identifiers = {d['id'].casefold() for d in recipe['documents']}
    identifiers.update(i.casefold() for d in recipe['documents'] for i in d['internal_ids'])
    if '' in identifiers:
        raise ValueError('empty internal identifier')
    for d in recipe['documents']:
        rel = d['path']
        if (rel != canonical_relpath(rel) or any(c in rel for c in '\x00\t\r\n')
                or not isinstance(d['id'], str) or not d['id']):
            raise ValueError(f'noncanonical path: {rel!r}')
        if d['id'] in ids or rel in paths:
            raise ValueError('duplicate document id/path')
        ids.add(d['id']); paths.add(rel)
        if any(i in rel.casefold() for i in identifiers):
            raise ValueError(f'filename leaks a document/dataset identifier: {rel}')
        if d['artifact'] not in artifacts:
            raise ValueError('unknown artifact')
        if not SHA.fullmatch(d['sha256']) or type(d['bytes']) is not int or d['bytes'] <= 0:
            raise ValueError('invalid document checksum/size')
        if d['format'] != Path(rel).suffix.lstrip('.') or d['language'] not in ('de', 'en'):
            raise ValueError('invalid format/language')
        if not all(d['license'].get(k) for k in ('name', 'url', 'attribution')):
            raise ValueError('source licence and attribution are required per file')
        if not d['source'] or not d['source_id'] or not d['origin_url']:
            raise ValueError('source provenance required per file')
        if d.get('pages') is not None and not 0 < d['pages'] <= 40:
            raise ValueError('PDF exceeds page cap')
        if d.get('member'):
            if d['member'] != canonical_relpath(d['member']):
                raise ValueError('unsafe archive member')
        if bool(artifacts[d['artifact']].get('archive')) != bool(d.get('member')):
            raise ValueError('archive documents need a member, direct documents must not have one')
        transform = d.get('transform', 'copy')
        if transform not in ('copy', 'wikipedia-note'):
            raise ValueError('unknown derivation')
        if transform == 'wikipedia-note' and d['source'] != 'wikipedia':
            raise ValueError('only Wikipedia HTML may be converted to notes')
        if transform == 'wikipedia-note' and not d.get('title'):
            raise ValueError('Wikipedia note needs a title')


class PublicHTTP:
    """Sequential HTTP, per-host spacing, robots and explicit rate-limit refusal."""
    def __init__(self):
        self.robots = {}
        self.last = {}

    def open(self, url, headers=None, *, _redirects=0):
        parts = urlsplit(url)
        if (parts.scheme not in ('http', 'https') or not parts.hostname
                or parts.username is not None or parts.password is not None):
            raise CollectionError('document URL must be public HTTP(S)')
        if _redirects > 5:
            raise CollectionError(f'too many redirects: {url}')
        host = f'{parts.scheme}://{parts.netloc}'
        if host not in self.robots:
            parser = RobotFileParser(host + '/robots.txt')
            try:
                with urlopen(Request(parser.url, headers={'User-Agent': USER_AGENT}), timeout=60) as r:
                    parser.parse(r.read().decode('utf-8', errors='replace').splitlines())
            except HTTPError as e:
                # RFC 9309 §2.3.1.3: a 4xx robots response is unavailable,
                # not a directive. Rate limiting must still stop the fetch.
                if 400 <= e.code < 500 and e.code != 429:
                    parser.parse([])
                else:
                    raise
            self.robots[host] = parser
        parser = self.robots[host]
        if not _robots_allowed(parser, url):
            raise CollectionError(f'robots.txt forbids fetching {url}')
        rate = parser.request_rate(USER_AGENT)
        delay = max(.25, parser.crawl_delay(USER_AGENT) or 0,
                    rate.seconds / rate.requests if rate and rate.requests else 0)
        time.sleep(max(0, self.last.get(host, 0) + delay - time.monotonic()))
        self.last[host] = time.monotonic()
        try:
            response = _document_open(Request(url, headers={'User-Agent': USER_AGENT, **(headers or {})}), timeout=120)
        except HTTPError as error:
            if error.code in (301, 302, 303, 307, 308) and error.headers.get('Location'):
                target = urljoin(url, error.headers['Location'])
                error.close()
                return self.open(target, headers, _redirects=_redirects + 1)
            raise
        if response.status != 200:
            response.close()
            raise CollectionError(f'expected document HTTP 200: {url}')
        return response


def _raw(a: dict, *, fetch: bool, client: PublicHTTP | None = None) -> Path:
    root = dataset_cache.cache_root()
    if root is None:
        raise CollectionError('set JUSTSEARCH_DATASET_CACHE under the quality cache')
    cache_path(root)
    params = {k: a[k] for k in ('url', 'sha256')}
    if a.get('headers'):
        params['headers'] = a['headers']
    hit = dataset_cache.lookup('public-real-use', params, filenames=['payload'])
    if hit is None and fetch:
        def populate(dest):
            with client.open(a['url'], a.get('headers')) as response:
                _write_member(response, dest / 'payload', a)
        hit = dataset_cache.store('public-real-use', params, filenames=['payload'], populate=populate)
    if hit is None:
        raise CollectionError(f'raw artifact missing or corrupt: {a["id"]}; run quality corpus fetch real-use')
    path = hit / 'payload'
    _verify(path, a)
    return path


def _verify(path: Path, row: dict):
    if path.stat().st_size != row['bytes'] or digest(path) != row['sha256']:
        raise CollectionError(f'checksum mismatch: {path}')


def fetch(recipe: dict) -> dict:
    validate_recipe(recipe)
    client = PublicHTTP()
    for a in recipe['artifacts']:
        _raw(a, fetch=True, client=client)
    return summary(recipe)


def _write_member(stream, target, row):
    target.parent.mkdir(parents=True, exist_ok=True)
    # A bounded copy refuses decompression bombs and recipe size mistakes.
    remaining = row['bytes']
    with target.open('wb') as out:
        while block := stream.read(min(1024 * 1024, remaining + 1)):
            remaining -= len(block)
            if remaining < 0:
                raise CollectionError(f'download/member exceeds recorded size: {row.get("path", row.get("url"))}')
            out.write(block)
    _verify(target, row)


def wikipedia_note(html: str, title: str) -> bytes:
    """Paragraph-only Markdown note from a pinned Wikipedia HTML response."""
    class Paragraphs(HTMLParser):
        def __init__(self):
            super().__init__()
            self.parts, self.current = [], None

        def handle_starttag(self, tag, attrs):
            if tag == 'p':
                self.current = []

        def handle_data(self, data):
            if self.current is not None:
                self.current.append(data)

        def handle_endtag(self, tag):
            if tag == 'p' and self.current is not None:
                paragraph = ' '.join(''.join(self.current).split())
                if paragraph:
                    self.parts.append(paragraph)
                self.current = None

    parser = Paragraphs()
    parser.feed(html)
    return (title + '\n\n' + '\n\n'.join(parser.parts) + '\n').encode('utf-8')


def materialize(recipe: dict, out: Path | None = None) -> Path:
    validate_recipe(recipe)
    out = cache_path(out or CACHE_ROOT / 'sources/real-use-v1')
    if out.exists():
        verify_materialized(recipe, out)
        return out
    out.parent.mkdir(parents=True, exist_ok=True)
    by_artifact = defaultdict(list)
    for d in recipe['documents']:
        by_artifact[d['artifact']].append(d)
    staging = Path(tempfile.mkdtemp(prefix='.real-use-', dir=out.parent))
    try:
        for a in recipe['artifacts']:
            rows = by_artifact[a['id']]
            if not rows:
                continue
            path = _raw(a, fetch=False)
            if a.get('archive') == 'zip':
                with zipfile.ZipFile(path) as archive:
                    for d in rows:
                        info = archive.getinfo(d['member'])
                        if info.file_size != d['bytes']:
                            raise CollectionError('archive member size differs from recipe')
                        with archive.open(info) as stream:
                            _write_member(stream, staging / d['path'], d)
            elif a.get('archive') == 'tar.gz':
                wanted = {d['member']: d for d in rows}
                with tarfile.open(path, 'r|gz') as archive:
                    for member in archive:
                        if member.name not in wanted:
                            continue
                        d = wanted.pop(member.name)
                        if not member.isfile() or member.size != d['bytes']:
                            raise CollectionError('invalid tar member')
                        with archive.extractfile(member) as stream:
                            _write_member(stream, staging / d['path'], d)
                if wanted:
                    raise CollectionError('missing tar members')
            else:
                for d in rows:
                    target = staging / d['path']
                    target.parent.mkdir(parents=True, exist_ok=True)
                    if d.get('transform') == 'wikipedia-note':
                        target.write_bytes(wikipedia_note(path.read_text(encoding='utf-8'), d['title']))
                    else:
                        shutil.copyfile(path, target)
                    _verify(target, d)
        verify_materialized(recipe, staging)
        staging.rename(out)
    finally:
        if staging.exists():
            shutil.rmtree(staging)
    return out


def verify_materialized(recipe: dict, root: Path):
    expected = {d['path'] for d in recipe['documents']}
    actual = {p.relative_to(root).as_posix() for p in root.rglob('*') if p.is_file()}
    if expected != actual or any(p.is_symlink() for p in root.rglob('*')):
        raise CollectionError('materialized population differs from recipe')
    for d in recipe['documents']:
        _verify(root / d['path'], d)


def summary(recipe: dict) -> dict:
    docs = recipe['documents']
    return {'files': len(docs), 'bytes': sum(d['bytes'] for d in docs),
            **{key: dict(sorted(Counter(d[key] for d in docs).items()))
               for key in ('source', 'format', 'language')},
            'shortfalls': recipe.get('shortfalls', [])}


def build_collection(cfg):
    from .collections import load_collection
    recipe = load_recipe(cfg.source['recipe'])
    rows = {d['id']: d for d in recipe['documents']}
    slice_dir = RECIPE_DIR / cfg.source['slice']
    c = load_collection(slice_dir)
    if (c.name != cfg.collection or c.language != cfg.language
            or c.metadata != {'config': cfg.collection, 'source': dict(cfg.source),
                              **cfg.identity_metadata()}):
        raise CollectionError('slice metadata differs from config')
    for doc, rel in c.documents.items():
        if (doc not in rows or rows[doc]['path'] != rel
                or rows[doc]['sha256'] != c.content[doc]):
            raise CollectionError('slice manifest differs from recipe')
    return c


def copy_documents(c: Collection, out: Path) -> int:
    recipe = load_recipe(c.metadata['source']['recipe'])
    root = CACHE_ROOT / 'sources/real-use-v1'
    rows = {d['id']: d for d in recipe['documents']}
    for doc, rel in c.documents.items():
        d = rows[doc]
        if d['path'] != rel or d['sha256'] != c.content[doc]:
            raise CollectionError('collection differs from recipe')
        src = root / rel
        _verify(src, d)
        target = out / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src, target)
    return len(c.documents)
