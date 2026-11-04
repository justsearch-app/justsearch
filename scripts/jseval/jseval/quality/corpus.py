"""Checksum-pinned public file recipes. Document bytes never belong in Git.

Archive handling routes member bytes only; it never invokes a document parser.
Only Wikipedia's explicitly declared note/HTML derivations interpret content.
"""
from __future__ import annotations

from collections import Counter, defaultdict
import gzip
import hashlib
from html import escape
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import shutil
import tarfile
import tempfile
import time
from urllib.parse import quote, urljoin, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
from urllib.error import HTTPError
from urllib.robotparser import RobotFileParser
import zipfile

from .. import dataset_cache
from .types import Collection, canonical_relpath
from .collections import CollectionError

CACHE_ROOT = Path.home() / '.cache/justsearch-quality'
RECIPE_DIR = Path(__file__).with_name('recipes')
SCHEMA = 'jseval.quality.public-corpus.v1'
USER_AGENT = 'JustSearch-quality-corpus/1.0'
SHA = re.compile(r'[0-9a-f]{64}')
MAX_ROBOTS_BYTES = 512 * 1024
# The pinned Enron tar expands to 1,823,016,960 bytes, including tar metadata.
# A fixed 2 GiB ceiling leaves margin without trusting recipe-supplied limits.
MAX_TAR_EXPANDED_BYTES = 2 * 1024 ** 3
# Raw Wikipedia HTML is informational, but downloads must still be bounded.
MAX_WIKIPEDIA_HTML_BYTES = 16 * 1024 ** 2
MAX_WIKIPEDIA_NESTING = 256
WIKIPEDIA_TRANSFORMS = ('wikipedia-note', 'wikipedia-html')


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def _document_open(request, *, timeout):
    return build_opener(_NoRedirect()).open(request, timeout=timeout)


def _robots_path(value: str) -> str:
    # RFC 9309 §2.2.2: decode only unreserved ASCII octets. In particular,
    # an encoded slash must not become a path separator. Encode literal * and $
    # here; rule operators are separated before normalization.
    value = quote(value, safe="/?:@!&'()+,;=[]-._~%")
    def octet(match):
        char = chr(int(match[1], 16))
        return char if char.isascii() and (char.isalnum() or char in '-._~') else match[0].upper()
    return re.sub(r'%([0-9a-fA-F]{2})', octet, value)


class _RobotsPolicy:
    """RFC 9309 rules and pacing for this fetcher's product token.

    Access matching stays local: urllib.robotparser's representation and matching
    differ between supported Python versions. Matching groups are merged, with
    '*' used only when there is no explicit product-token group (§2.2.1).
    Standard-library pacing remains a minimum for compatibility with older fetches.
    """
    def __init__(self, lines):
        groups, pacing_groups = [], []
        agents, rules, pacing_agents, delays = [], [], [], []
        has_rules = False
        has_directive = False
        for line in lines:
            field, sep, value = line.lstrip('\ufeff').split('#', 1)[0].partition(':')
            if not sep:
                continue
            field, value = field.strip().lower(), value.strip()
            if field == 'user-agent':
                if has_rules:
                    groups.append((agents, rules))
                    agents, rules = [], []
                    has_rules = False
                if has_directive:
                    pacing_groups.append((pacing_agents, delays))
                    pacing_agents, delays = [], []
                    has_directive = False
                if value:
                    agents.append(value.lower())
                    pacing_agents.append(value.lower())
            elif agents and field in ('allow', 'disallow', 'crawl-delay', 'request-rate'):
                has_directive = True
                # RFC 9309 extensions must not split access-rule groups.
                if field in ('allow', 'disallow'):
                    has_rules = True
                if field in ('allow', 'disallow') and value:
                    terminal = value.endswith('$')
                    pattern = value[:-1] if terminal else value
                    pattern = '*'.join(_robots_path(part) for part in pattern.split('*'))
                    rules.append((pattern, terminal, field == 'allow'))
                elif field == 'crawl-delay' and re.fullmatch(r'[0-9]+', value):
                    delays.append(int(value))
                elif field == 'request-rate' and re.fullmatch(r'[0-9]+/[0-9]+', value):
                    requests, seconds = map(int, value.split('/'))
                    if requests:
                        delays.append(seconds / requests)
        groups.append((agents, rules))
        pacing_groups.append((pacing_agents, delays))
        token = USER_AGENT.split('/', 1)[0].lower()
        selected = [g for g in groups if token in g[0]]
        if not selected:
            selected = [g for g in groups if '*' in g[0]]
        self.rules = [rule for _, rules in selected for rule in rules]
        paced = [g for g in pacing_groups if token in g[0]]
        if not paced:
            paced = [g for g in pacing_groups if '*' in g[0]]
        # Use the former fetcher's actual parser for legacy pacing, never access.
        legacy = RobotFileParser()
        legacy.parse(lines)
        rate = legacy.request_rate(USER_AGENT)
        self.delay = max(
            max((delay for _, delays in paced for delay in delays), default=0),
            legacy.crawl_delay(USER_AGENT) or 0,
            rate.seconds / rate.requests if rate and rate.requests else 0,
        )


def _robots_match(pattern: str, terminal: bool, path: str) -> bool:
    # Ordered literal chunks implement '*' without regex backtracking on
    # untrusted patterns. Only the final '$' anchors the end of the path.
    first, *rest = pattern.split('*')
    if not path.startswith(first):
        return False
    offset = len(first)
    for n, part in enumerate(rest):
        if terminal and n == len(rest) - 1:
            return path.endswith(part) and len(path) - len(part) >= offset
        found = path.find(part, offset)
        if found < 0:
            return False
        offset = found + len(part)
    return not terminal or offset == len(path)


def _robots_allowed(parser: _RobotsPolicy, url: str) -> bool:
    parts = urlsplit(url)
    path = parts.path or '/'
    if '?' in url.split('#', 1)[0]:
        path += '?' + parts.query
    path = _robots_path(path)
    matches = []
    for pattern, terminal, allowance in parser.rules:
        if _robots_match(pattern, terminal, path):
            octets = re.sub(r'%[0-9A-F]{2}', '_', pattern.replace('*', ''))
            matches.append((len(octets), allowance))
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
        for doc in recipe['documents']:
            doc['license'] = _document_license(recipe, doc)
    except (OSError, ValueError, KeyError, TypeError) as error:
        raise CollectionError(f'{path}: {error}') from None
    return recipe


def _document_license(recipe: dict, doc: dict) -> dict:
    licence = doc['license']
    if 'ref' not in licence:
        return licence
    ref = licence['ref']
    if ref not in recipe.get('licenses', {}):
        raise ValueError(f'unknown licence reference: {ref}')
    return {**recipe['licenses'][ref], **{k: v for k, v in licence.items() if k != 'ref'}}


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
    for host, destinations in recipe.get('redirect_hosts', {}).items():
        if host not in {urlsplit(a['url']).hostname for a in artifacts.values()}:
            raise ValueError('redirect policy must name an artifact host')
        if not isinstance(destinations, list) or any(
                not isinstance(h, str) or not h or urlsplit('https://' + h).netloc != h
                or urlsplit('https://' + h).hostname != h for h in destinations):
            raise ValueError('redirect destinations must be hostnames')
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
        if not all(_document_license(recipe, d).get(k) for k in ('name', 'url', 'attribution')):
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
        if transform not in ('copy', *WIKIPEDIA_TRANSFORMS):
            raise ValueError('unknown derivation')
        if transform in WIKIPEDIA_TRANSFORMS:
            if d['source'] != 'wikipedia' or artifacts[d['artifact']].get('archive'):
                raise ValueError('only Wikipedia HTML may be derived')
            if not d.get('title'):
                raise ValueError('Wikipedia derivation needs a title')


class PublicHTTP:
    """Sequential HTTP, per-host spacing, robots and explicit rate-limit refusal."""
    def __init__(self, redirect_hosts=None):
        self.robots = {}
        self.last = {}
        self.redirect_hosts = redirect_hosts or {}

    def open(self, url, headers=None):
        host = urlsplit(url).hostname
        allowed = {host, *self.redirect_hosts.get(host, [])}
        return self._open(url, headers, allowed=allowed)

    def _open(self, url, headers=None, *, allowed, robots=False, redirects=0):
        parts = urlsplit(url)
        if (parts.scheme not in ('http', 'https') or not parts.hostname
                or parts.username is not None or parts.password is not None):
            raise CollectionError('document URL must be public HTTP(S)')
        if parts.hostname not in allowed:
            raise CollectionError(f'redirect host is not permitted: {parts.hostname}')
        if redirects > 5:
            raise CollectionError(f'too many redirects: {url}')
        host = f'{parts.scheme}://{parts.netloc}'
        if not robots and host not in self.robots:
            try:
                with self._open(host + '/robots.txt', allowed=allowed, robots=True) as r:
                    data = r.read(MAX_ROBOTS_BYTES + 1)
                    if len(data) > MAX_ROBOTS_BYTES:
                        raise CollectionError('robots.txt exceeds byte cap')
                    parser = _RobotsPolicy(data.decode('utf-8', errors='replace').splitlines())
            except HTTPError as e:
                # RFC 9309 §2.3.1.3: a 4xx robots response is unavailable,
                # not a directive. Rate limiting must still stop the fetch.
                if 400 <= e.code < 500 and e.code != 429:
                    parser = _RobotsPolicy([])
                else:
                    raise CollectionError(f'robots.txt fetch disallowed: HTTP {e.code}') from e
                e.close()
            except (OSError, ValueError, CollectionError) as e:
                raise CollectionError(f'robots.txt fetch disallowed: {e}') from e
            self.robots[host] = parser
        parser = self.robots.get(host) if not robots else None
        if parser is not None and not _robots_allowed(parser, url):
            raise CollectionError(f'robots.txt forbids fetching {url}')
        delay = max(.25, parser.delay if parser else 0)
        time.sleep(max(0, self.last.get(host, 0) + delay - time.monotonic()))
        self.last[host] = time.monotonic()
        try:
            response = _document_open(Request(url, headers={'User-Agent': USER_AGENT, **(headers or {})}),
                                      timeout=60 if robots else 120)
        except HTTPError as error:
            if error.code in (301, 302, 303, 307, 308) and error.headers.get('Location'):
                target = urljoin(url, error.headers['Location'])
                error.close()
                return self._open(target, headers, allowed=allowed, robots=robots, redirects=redirects + 1)
            raise
        if response.status != 200:
            response.close()
            raise CollectionError(f'expected document HTTP 200: {url}')
        return response


def _raw(a: dict, *, fetch: bool, client: PublicHTTP | None = None, documents=()) -> Path:
    root = dataset_cache.cache_root()
    if root is None:
        raise CollectionError('set JUSTSEARCH_DATASET_CACHE under the quality cache')
    cache_path(root)
    params = {k: a[k] for k in ('url', 'sha256')}
    if a.get('headers'):
        params['headers'] = a['headers']
    derived = bool(documents) and all(
        d['source'] == 'wikipedia' and d.get('transform') in WIKIPEDIA_TRANSFORMS
        for d in documents)

    def verify(path):
        if derived:
            html = path.read_text(encoding='utf-8')
            for d in documents:
                data = _wikipedia_derive(html, d)
                if len(data) != d['bytes'] or hashlib.sha256(data).hexdigest() != d['sha256']:
                    raise CollectionError(f'checksum mismatch: {d["path"]}')
        else:
            _verify(path, a)

    hit = dataset_cache.lookup('public-real-use', params, filenames=['payload'])
    if hit is None and fetch:
        def populate(dest):
            with client.open(a['url'], a.get('headers')) as response:
                _write_member(response, dest / 'payload', a, derived=derived)
            if derived:
                verify(dest / 'payload')
        hit = dataset_cache.store('public-real-use', params, filenames=['payload'], populate=populate)
    if hit is None:
        raise CollectionError(f'raw artifact missing or corrupt: {a["id"]}; run quality corpus fetch real-use')
    path = hit / 'payload'
    verify(path)
    return path


def _verify(path: Path, row: dict):
    if path.stat().st_size != row['bytes'] or digest(path) != row['sha256']:
        raise CollectionError(f'checksum mismatch: {path}')


def fetch(recipe: dict) -> dict:
    validate_recipe(recipe)
    client = PublicHTTP(recipe.get('redirect_hosts'))
    by_artifact = defaultdict(list)
    for d in recipe['documents']:
        by_artifact[d['artifact']].append(d)
    for a in recipe['artifacts']:
        _raw(a, fetch=True, client=client, documents=by_artifact[a['id']])
    return summary(recipe)


def _write_member(stream, target, row, *, derived=False):
    target.parent.mkdir(parents=True, exist_ok=True)
    # A bounded copy refuses decompression bombs and recipe size mistakes.
    remaining = MAX_WIKIPEDIA_HTML_BYTES if derived else row['bytes']
    with target.open('wb') as out:
        while block := stream.read(min(1024 * 1024, remaining + 1)):
            remaining -= len(block)
            if remaining < 0:
                limit = 'Wikipedia byte cap' if derived else 'recorded size'
                raise CollectionError(f'download/member exceeds {limit}: {row.get("path", row.get("url"))}')
            out.write(block)
    if not derived:
        _verify(target, row)


class _BoundedTar:
    """Count gzip output before tarfile can consume skipped data or metadata."""
    def __init__(self, stream):
        self.stream = stream
        self.remaining = MAX_TAR_EXPANDED_BYTES

    def read(self, size):
        data = self.stream.read(min(size, self.remaining + 1))
        self.remaining -= len(data)
        if self.remaining < 0:
            raise CollectionError('tar expanded bytes exceed fixed cap')
        return data


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
    if len(html.encode('utf-8')) > MAX_WIKIPEDIA_HTML_BYTES:
        raise CollectionError('Wikipedia byte cap exceeded')
    parser.feed(html)
    if not parser.parts:
        raise CollectionError('Wikipedia article body empty')
    return (title + '\n\n' + '\n\n'.join(parser.parts) + '\n').encode('utf-8')


def wikipedia_html(html: str, title: str) -> bytes:
    """Minimal saved article: semantic blocks and text, without page chrome.

    Keep no source attributes, links or active content. Inline wrappers are
    flattened; headings, paragraphs, lists and tables retain their structure.
    """
    blocks = {'h2', 'h3', 'h4', 'h5', 'h6', 'p', 'ul', 'ol', 'li', 'dl', 'dt', 'dd',
              'table', 'caption', 'thead', 'tbody', 'tfoot', 'tr', 'th', 'td'}
    excluded_tags = {'script', 'style', 'noscript', 'nav', 'figure'}
    excluded_classes = {'reference', 'mw-editsection', 'navbox', 'vertical-navbox', 'navigation',
                        'sidebar', 'ambox', 'tmbox', 'fmbox', 'ombox', 'cmbox',
                        'mbox-small', 'hatnote', 'metadata', 'noprint', 'toc',
                        'sistersitebox', 'shortdescription', 'mw-empty-elt'}

    class Article(HTMLParser):
        def __init__(self):
            super().__init__()
            self.root = None
            self.stack = []
            self.open_tags = Counter()
            self.content_root = False

        def handle_starttag(self, tag, attrs):
            attrs = dict(attrs)
            classes = set((attrs.get('class') or '').split())
            if not self.stack:
                # Coordinate indicators can precede the article and also use
                # mw-parser-output. Prefer the language-marked content root.
                content_root = bool(classes & {'mw-content-ltr', 'mw-content-rtl'})
                if ('mw-parser-output' not in classes or self.content_root
                        or (self.root is not None and not content_root)):
                    return
                self.content_root = content_root
            node = [tag, classes, []]
            if self.stack:
                self.stack[-1][2].append(node)
            else:
                self.root = node
            if tag not in {'area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input',
                           'link', 'meta', 'param', 'source', 'track', 'wbr'}:
                if len(self.stack) >= MAX_WIKIPEDIA_NESTING:
                    raise CollectionError('Wikipedia nesting exceeds fixed cap')
                self.stack.append(node)
                self.open_tags[tag] += 1

        def handle_startendtag(self, tag, attrs):
            self.handle_starttag(tag, attrs)
            self.handle_endtag(tag)

        def handle_endtag(self, tag):
            # Unmatched closes cost O(1); matched closes pop each node once.
            if not self.open_tags.get(tag):
                return
            while self.stack:
                name = self.stack.pop()[0]
                self.open_tags[name] -= 1
                if not self.open_tags[name]:
                    del self.open_tags[name]
                if name == tag:
                    break

        def handle_data(self, data):
            if self.stack:
                self.stack[-1][2].append(data)

    def render(node, in_block=False):
        if isinstance(node, str):
            return escape(re.sub(r'\s+', ' ', node), quote=False) if in_block else ''
        tag, classes, children = node
        if tag in excluded_tags or classes & excluded_classes:
            return ''
        if tag == 'br':
            return ' ' if in_block else ''
        text = ''.join(render(child, in_block or tag in blocks) for child in children)
        if tag in blocks:
            text = text.strip()
            return f'<{tag}>{text}</{tag}>' if text else ''
        return text

    parser = Article()
    if len(html.encode('utf-8')) > MAX_WIKIPEDIA_HTML_BYTES:
        raise CollectionError('Wikipedia byte cap exceeded')
    parser.feed(html)
    if parser.root is None:
        raise CollectionError('Wikipedia article body missing')
    article = render(parser.root)
    if not re.sub(r'<[^>]*>', '', article).strip():
        raise CollectionError('Wikipedia article body empty')
    title = escape(title)
    return (f'<!doctype html>\n<html><head><meta charset="utf-8"><title>{title}</title>'
            f'</head><body><h1>{title}</h1>{article}</body></html>\n').encode('utf-8')


def _wikipedia_derive(html: str, doc: dict) -> bytes:
    transform = wikipedia_note if doc['transform'] == 'wikipedia-note' else wikipedia_html
    return transform(html, doc['title'])


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
            path = _raw(a, fetch=False, documents=rows)
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
                with gzip.open(path, 'rb') as expanded, tarfile.open(
                        fileobj=_BoundedTar(expanded), mode='r|') as archive:
                    for member in archive:
                        if member.name not in wanted:
                            continue
                        d = wanted.pop(member.name)
                        if not member.isfile() or member.size != d['bytes']:
                            raise CollectionError('invalid tar member')
                        with archive.extractfile(member) as stream:
                            _write_member(stream, staging / d['path'], d)
                        if not wanted:
                            break
                if wanted:
                    raise CollectionError('missing tar members')
            else:
                for d in rows:
                    target = staging / d['path']
                    target.parent.mkdir(parents=True, exist_ok=True)
                    if d.get('transform') in WIKIPEDIA_TRANSFORMS:
                        target.write_bytes(_wikipedia_derive(path.read_text(encoding='utf-8'), d))
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
