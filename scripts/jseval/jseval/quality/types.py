"""Shared types of the search-quality core (lanes A, B and C code against these).

Everything here is plain data plus its canonical serialization. Identity is content-addressed:

- a collection's ``judgments_sha256`` is the sha256 of its canonical TREC qrels text, its
  ``manifest_sha256`` the sha256 of its canonical manifest text, its ``content_sha256`` the sha256
  of its canonical per-document content text, and its ``version`` the sha256 over queries +
  judgments + manifest + content (so a dropped positive, an edited query or a document whose text
  changed under the same path changes it);
- a query is identified by its id *and* the sha256 of its exact text;
- a run is a :class:`RunRecord`: one header line, then one :class:`QueryResult` line per query.

Document identity (S14): ``Collection.documents`` maps an opaque ``doc_id`` to one canonical
relative path. Paths are compared after :func:`canonical_relpath` only (Unicode NFC, ``/``
separators); case and every other character are significant, so same basenames in different
folders and names differing only by case stay distinct documents.

Document content: ``Collection.content`` maps every ``doc_id`` to the sha256 of the exact text an
engine is given for it (:func:`document_text`), so two indexes over the same paths but different
text (a changed document format, a re-extraction) never pass as the same population.
"""

from __future__ import annotations

import enum
import hashlib
import json
import math
import ntpath
import os
import posixpath
import re
import tempfile
import unicodedata
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable, Mapping
from urllib.parse import unquote, urlsplit

import numpy

COLLECTION_SCHEMA = "jseval.quality.collection.v1"
RUN_SCHEMA = "jseval.quality.run.v1"
BASELINE_SCHEMA = "jseval.quality.baseline.v1"


# --------------------------------------------------------------------------- canonical forms


def sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def canonical_json(value: Any) -> str:
    """Key-sorted, compact, UTF-8 JSON: the one serialization every digest here is taken over."""
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False,
                      allow_nan=False)


def _check_token(kind: str, value: str) -> None:
    if not isinstance(value, str) or not value or any(c.isspace() for c in value):
        raise ValueError(f"{kind} must be a non-empty string without whitespace: {value!r}")


def canonical_qrels_text(judgments: Mapping[str, Mapping[str, int]]) -> str:
    """TREC qrels (``qid 0 doc_id grade``), tab separated, sorted by query id then doc id."""
    lines = []
    for qid in sorted(judgments):
        _check_token("query id", qid)
        for doc_id in sorted(judgments[qid]):
            _check_token("doc id", doc_id)
            grade = judgments[qid][doc_id]
            if isinstance(grade, bool) or not isinstance(grade, int):
                raise ValueError(f"grade for {qid}/{doc_id} must be an integer, got {grade!r}")
            lines.append(f"{qid}\t0\t{doc_id}\t{grade}\n")
    return "".join(lines)


def parse_qrels_text(text: str) -> dict[str, dict[str, int]]:
    """Parse TREC qrels; a repeated (query, doc) pair is an error, never a silent overwrite."""
    out: dict[str, dict[str, int]] = {}
    for n, line in enumerate(text.splitlines(), 1):
        if not line.strip():
            continue
        parts = line.split()
        if len(parts) != 4:
            raise ValueError(f"qrels line {n}: expected 4 fields, got {len(parts)}: {line!r}")
        qid, _, doc_id, grade = parts
        docs = out.setdefault(qid, {})
        if doc_id in docs:
            raise ValueError(f"qrels line {n}: duplicate judgment for {qid}/{doc_id}")
        docs[doc_id] = int(grade)
    return out


_WINDOWS = os.name == "nt"
_SHA256 = re.compile(r"[0-9a-f]{64}")


def _has_drive(p: str) -> bool:
    """A Windows drive (``C:``) or UNC prefix. On POSIX a colon is an ordinary file name
    character, so ``a:b.txt`` and ``C:/notes.txt`` are relative paths there."""
    return _WINDOWS and bool(ntpath.splitdrive(p)[0])


def canonical_relpath(path: str) -> str:
    """The comparable form of a document path relative to the collection root.

    Unicode is normalized to NFC (macOS file systems hand back NFD), ``\\`` becomes ``/`` and
    ``./`` segments are dropped. Nothing else changes: case, ``%``, ``#``, ``?`` and spaces are
    part of the name. Absolute paths and ``..`` escapes are refused.
    """
    if not isinstance(path, str) or not path:
        raise ValueError(f"document path must be a non-empty string: {path!r}")
    if "\t" in path or "\n" in path or "\r" in path:
        raise ValueError(f"document path contains a tab or newline: {path!r}")
    p = unicodedata.normalize("NFC", path).replace("\\", "/")
    if p.startswith("/") or _has_drive(p):
        raise ValueError(f"document path must be relative: {path!r}")
    parts = [s for s in p.split("/") if s not in ("", ".")]
    if not parts or ".." in parts:
        raise ValueError(f"document path escapes or is empty: {path!r}")
    return "/".join(parts)


def canonical_manifest_text(documents: Mapping[str, str]) -> str:
    """``relpath \\t doc_id`` lines sorted by relpath (``documents`` is doc_id -> relpath)."""
    rows = []
    for doc_id, rel in documents.items():
        _check_token("doc id", doc_id)
        rows.append((canonical_relpath(rel), doc_id))
    rows.sort()
    return "".join(f"{rel}\t{doc_id}\n" for rel, doc_id in rows)


def document_text(title: str | None, text: str) -> str:
    """The exact text an engine is given for one document: ``title``, a blank line, ``text``
    (just ``text`` without a title). Its sha256 is the document's content hash."""
    return f"{title}\n\n{text}" if title else text


def canonical_content_text(content: Mapping[str, str]) -> str:
    """``doc_id \\t content_sha256`` lines sorted by doc id (``content`` is doc_id -> sha256)."""
    lines = []
    for doc_id in sorted(content):
        _check_token("doc id", doc_id)
        h = content[doc_id]
        if not isinstance(h, str) or not _SHA256.fullmatch(h):
            raise ValueError(f"content hash of {doc_id} must be a lowercase sha256 hex: {h!r}")
        lines.append(f"{doc_id}\t{h}\n")
    return "".join(lines)


def parse_content_text(text: str) -> dict[str, str]:
    out: dict[str, str] = {}
    for n, line in enumerate(text.splitlines(), 1):
        parts = line.split("\t")
        if len(parts) != 2:
            raise ValueError(f"content line {n}: expected 'doc_id<TAB>sha256': {line!r}")
        if parts[0] in out:
            raise ValueError(f"content line {n}: doc id {parts[0]} appears twice")
        out[parts[0]] = parts[1]
    return out


# --------------------------------------------------------------------------- collection


@dataclass(frozen=True)
class QueryText:
    text: str
    groups: Mapping[str, str] = field(default_factory=dict)

    @property
    def sha256(self) -> str:
        return sha256_text(self.text)


class UnknownDocument(KeyError):
    """A delivered path or id that is not in the collection's manifest."""


@dataclass
class Collection:
    """A frozen evaluation collection: queries, complete graded judgments, document manifest.

    ``version`` is derived, never trusted: :meth:`compute_version` recomputes it from content and
    the loader refuses a stored file whose recorded version differs.
    """

    name: str
    language: str
    queries: dict[str, QueryText]
    judgments: dict[str, dict[str, int]]
    documents: dict[str, str]  # doc_id -> relpath
    content: dict[str, str]  # doc_id -> sha256 of document_text(...) (exactly the documents)
    metadata: dict[str, Any] = field(default_factory=dict)
    version: str = ""

    def __post_init__(self) -> None:
        self.documents = {d: canonical_relpath(r) for d, r in self.documents.items()}
        by_path: dict[str, str] = {}
        for doc_id, rel in self.documents.items():
            if rel in by_path:
                raise ValueError(f"two documents share the path {rel!r}: {by_path[rel]}, {doc_id}")
            by_path[rel] = doc_id
        self._by_path = by_path
        if set(self.content) != set(self.documents):
            raise ValueError(f"collection {self.name}: content hashes cover "
                             f"{len(set(self.content) & set(self.documents))} of "
                             f"{len(self.documents)} documents and "
                             f"{len(set(self.content) - set(self.documents))} unknown ids")
        computed = self.compute_version()
        if self.version and self.version != computed:
            raise ValueError(f"collection {self.name}: recorded version {self.version} does not "
                             f"match its content ({computed})")
        self.version = computed

    @property
    def judgments_sha256(self) -> str:
        return sha256_text(canonical_qrels_text(self.judgments))

    @property
    def manifest_sha256(self) -> str:
        return sha256_text(canonical_manifest_text(self.documents))

    @property
    def content_sha256(self) -> str:
        return sha256_text(canonical_content_text(self.content))

    def index_population(self) -> dict[str, Any]:
        """The ``RunHeader.index_population`` an index of exactly this collection has."""
        return {"count": len(self.documents), "manifest_sha256": self.manifest_sha256,
                "content_sha256": self.content_sha256}

    def queries_payload(self) -> list:
        out = []
        for qid in sorted(self.queries):
            _check_token("query id", qid)
            q = self.queries[qid]
            out.append([qid, q.text, dict(sorted(q.groups.items()))])
        return out

    def compute_version(self) -> str:
        return sha256_text(canonical_json({
            "queries": self.queries_payload(),
            "judgments_sha256": self.judgments_sha256,
            "manifest_sha256": self.manifest_sha256,
            "content_sha256": self.content_sha256,
        }))

    def counts(self) -> dict[str, Any]:
        grades: dict[str, int] = {}
        for docs in self.judgments.values():
            for g in docs.values():
                grades[str(g)] = grades.get(str(g), 0) + 1
        return {
            "queries": len(self.queries),
            "judgments": sum(len(d) for d in self.judgments.values()),
            "positives": sum(1 for d in self.judgments.values() for g in d.values() if g > 0),
            "grades": dict(sorted(grades.items())),
            "documents": len(self.documents),
        }

    def doc_id_for_path(self, path: str) -> str:
        """Map a path relative to the collection root to its doc_id (S14)."""
        rel = canonical_relpath(path)
        try:
            return self._by_path[rel]
        except KeyError:
            raise UnknownDocument(rel) from None

    def doc_id_for_location(self, location: str, root: str | os.PathLike | None = None) -> str:
        """Map a delivered location (``file://`` URI, absolute path under ``root``, or relative
        path) to its doc_id. Percent-decoding applies to URIs only: a plain file name may itself
        contain ``%20``."""
        loc = location
        if loc.lower().startswith("file:"):
            parts = urlsplit(loc)
            loc = unquote(parts.path)
            if _WINDOWS and len(loc) > 2 and loc[0] == "/" and loc[2] == ":":  # file:///C:/...
                loc = loc[1:]
        if root is not None:
            r = unicodedata.normalize("NFC", os.fspath(root)).replace("\\", "/").rstrip("/") + "/"
            norm = unicodedata.normalize("NFC", loc).replace("\\", "/")
            if norm.startswith(r):
                loc = norm[len(r):]
            elif posixpath.isabs(norm) or _has_drive(norm):
                raise UnknownDocument(f"{location} is outside the collection root {root}")
        return self.doc_id_for_path(loc)

    def ranked_doc_ids(self, locations: Iterable[str],
                       root: str | os.PathLike | None = None) -> list[str]:
        """Delivered hits (one per chunk, in delivered order) -> doc_ids in delivered order, each
        document once at its first position, so chunks of one document never count twice."""
        seen: set[str] = set()
        out: list[str] = []
        for loc in locations:
            doc_id = self.doc_id_for_location(loc, root)
            if doc_id not in seen:
                seen.add(doc_id)
                out.append(doc_id)
        return out


# --------------------------------------------------------------------------- runs

RERANKER_STATUSES = ("executed", "skipped")
INDEX_BUILDS = ("fresh", "cached")  # built for this run, or adopted from a cache of indexes


def _need(ok: bool, message: str) -> None:
    if not ok:
        raise ValueError(message)


def _mapping(value: Any, name: str) -> Mapping:
    _need(isinstance(value, Mapping), f"{name} must be an object, got {type(value).__name__}")
    return value


def _text(value: Any, name: str, *, optional: bool = False) -> str | None:
    if optional and value is None:
        return None
    _need(isinstance(value, str) and value != "",
          f"{name} must be a non-empty string{' or null' if optional else ''}, got {value!r}")
    return value


def _legs(value: Any, name: str) -> tuple[str, ...]:
    _need(isinstance(value, (list, tuple)) and all(isinstance(x, str) and x for x in value),
          f"{name} must be a list of leg names, got {value!r}")
    return tuple(value)


@dataclass(frozen=True)
class Served:
    """Delivered legs and their trace evidence, plus reranker status/reason.

    Older records can lack ``leg_details``; they cannot prove a legitimate leg reduction.
    """

    legs: tuple[str, ...] = ()
    reranker: str = "skipped"
    reason: str | None = None
    leg_details: Mapping[str, Mapping[str, str | None]] = field(default_factory=dict)

    def __post_init__(self) -> None:
        object.__setattr__(self, "legs", _legs(self.legs, "served.legs"))
        _need(self.reranker in RERANKER_STATUSES,
              f"served.reranker must be 'executed' or 'skipped': {self.reranker!r}")
        _text(self.reason, "served.reason", optional=True)
        details = _mapping(self.leg_details, "served.leg_details")
        for leg, value in details.items():
            _text(leg, "served.leg_details leg")
            value = _mapping(value, f"served.leg_details.{leg}")
            _need(value.get("status") in ("executed", "skipped", "disabled"),
                  f"served.leg_details.{leg}: invalid status")
            _text(value.get("reason"), f"served.leg_details.{leg}.reason", optional=True)
        object.__setattr__(self, "leg_details", {
            leg: {"status": value["status"], "reason": value.get("reason")}
            for leg, value in details.items()})

    def to_dict(self) -> dict:
        return {"legs": list(self.legs), "reranker": self.reranker, "reason": self.reason,
                "leg_details": {k: dict(v) for k, v in self.leg_details.items()}}

    @classmethod
    def from_dict(cls, d: Mapping) -> "Served":
        d = _mapping(d, "served")
        return cls(_legs(d.get("legs") or [], "served.legs"), d.get("reranker"), d.get("reason"),
                   d.get("leg_details", {}))


@dataclass(frozen=True)
class QueryResult:
    qid: str
    query_text_sha256: str
    ranked: tuple[str, ...]  # doc_ids in delivered order (S13)
    served: Served = Served()
    error: str | None = None  # S11: a failed query is recorded, never dropped
    timings: Mapping[str, float] = field(default_factory=dict)

    def to_dict(self) -> dict:
        return {"qid": self.qid, "query_text_sha256": self.query_text_sha256,
                "ranked": list(self.ranked), "served": self.served.to_dict(),
                "error": self.error, "timings": dict(self.timings)}

    @classmethod
    def from_dict(cls, d: Mapping) -> "QueryResult":
        d = _mapping(d, "query result")
        qid = _text(d.get("qid"), "qid")
        ranked = d.get("ranked")
        if not isinstance(ranked, list) or not all(isinstance(x, str) for x in ranked):
            raise ValueError(f"query {qid}: ranked must be a list of doc ids")
        timings = _mapping(d.get("timings") or {}, f"query {qid}: timings")
        _need(all(is_finite_number(v) for v in timings.values()),
              f"query {qid}: timings must be finite numbers")
        return cls(qid, _text(d.get("query_text_sha256"), f"query {qid}: query_text_sha256"),
                   tuple(ranked), Served.from_dict(d.get("served") or {"reranker": "skipped"}),
                   _text(d.get("error"), f"query {qid}: error", optional=True), dict(timings))


@dataclass(frozen=True)
class RunHeader:
    """Effective inputs and index state of one arm (S18/S19).

    ``expected_legs`` / ``expected_reranker`` declare what every query is meant to be served by;
    a query served by fewer legs without explicit by-design trace evidence makes the run INVALID.
    Identical by-design reductions/skips in both arms remain comparable. ``index_population`` is
    ``{"count", "manifest_sha256", "content_sha256"}`` of what was actually indexed (compare
    :meth:`Collection.index_population`). ``index_build`` says whether the index was built
    ``fresh`` for this run or ``cached`` (adopted from a cache, under ``index_cache_key``).
    """

    collection: str
    collection_version: str
    index_key: str
    index_population: Mapping[str, Any]  # {"count": int, "manifest_sha256", "content_sha256"}
    request_config: Mapping[str, Any]
    commit: str
    dirty_hash: str | None  # None = clean tree
    model_hashes: Mapping[str, str]
    started: str
    ended: str | None  # None = the run did not finish
    expected_legs: tuple[str, ...]  # e.g. ("lexical", "dense"): legs every query must be served by
    expected_reranker: str  # "executed" (on every query) or "skipped" (not used)
    index_build: str  # "fresh" or "cached"
    index_cache_key: str | None = None  # the cache entry a cached index came from
    index_state: Mapping[str, Any] = field(default_factory=dict)  # freshness, enrichment, merges
    hardware: Mapping[str, Any] = field(default_factory=dict)
    budget: Mapping[str, Any] = field(default_factory=dict)
    request_config_sha256: str = ""

    def __post_init__(self) -> None:
        for name in ("collection", "collection_version", "index_key", "commit", "started"):
            _text(getattr(self, name), name)
        _text(self.dirty_hash, "candidate.dirty_hash", optional=True)
        _text(self.ended, "ended", optional=True)
        pop = _mapping(self.index_population, "index_population")
        _need(isinstance(pop.get("count"), int) and not isinstance(pop.get("count"), bool)
              and pop["count"] >= 0, f"index_population.count must be a count: {pop.get('count')!r}")
        for key in ("manifest_sha256", "content_sha256"):
            _text(pop.get(key), f"index_population.{key}")
        for name in ("request_config", "model_hashes", "index_state", "hardware", "budget"):
            _mapping(getattr(self, name), name)
        _need(all(isinstance(k, str) and isinstance(v, str) for k, v in self.model_hashes.items()),
              "model_hashes must map model names to hash strings")
        legs = _legs(self.expected_legs, "expected_served.legs")
        _need(bool(legs), "expected_served.legs must name at least one leg")
        object.__setattr__(self, "expected_legs", legs)
        _need(self.expected_reranker in RERANKER_STATUSES,
              f"expected_served.reranker must be 'executed' or 'skipped': {self.expected_reranker!r}")
        _need(self.index_build in INDEX_BUILDS,
              f"index_build must be 'fresh' or 'cached': {self.index_build!r}")
        _text(self.index_cache_key, "index_cache_key", optional=self.index_build == "fresh")
        computed = sha256_text(canonical_json(dict(self.request_config)))
        if self.request_config_sha256 and self.request_config_sha256 != computed:
            raise ValueError("request_config_sha256 does not match request_config")
        object.__setattr__(self, "request_config_sha256", computed)

    def to_dict(self) -> dict:
        return {"schema": RUN_SCHEMA,
                "collection": {"name": self.collection, "version": self.collection_version},
                "index_key": self.index_key,
                "index_population": dict(self.index_population),
                "index_build": self.index_build,
                "index_cache_key": self.index_cache_key,
                "index_state": dict(self.index_state),
                "request_config": dict(self.request_config),
                "request_config_sha256": self.request_config_sha256,
                "expected_served": {"legs": list(self.expected_legs),
                                    "reranker": self.expected_reranker},
                "candidate": {"commit": self.commit, "dirty_hash": self.dirty_hash},
                "model_hashes": dict(self.model_hashes),
                "hardware": dict(self.hardware),
                "started": self.started, "ended": self.ended,
                "budget": dict(self.budget)}

    @classmethod
    def from_dict(cls, d: Mapping) -> "RunHeader":
        d = _mapping(d, "header")
        if d.get("schema") != RUN_SCHEMA:
            raise ValueError(f"not a {RUN_SCHEMA} header: schema={d.get('schema')!r}")
        col = _mapping(d["collection"], "collection")
        cand = _mapping(d["candidate"], "candidate")
        served = _mapping(d["expected_served"], "expected_served")
        return cls(collection=col["name"], collection_version=col["version"],
                   index_key=d["index_key"], index_population=d["index_population"],
                   index_build=d["index_build"], index_cache_key=d["index_cache_key"],
                   index_state=d["index_state"] if "index_state" in d else {},
                   request_config=d["request_config"],
                   request_config_sha256=d.get("request_config_sha256", ""),
                   expected_legs=served["legs"], expected_reranker=served["reranker"],
                   commit=cand["commit"], dirty_hash=cand["dirty_hash"],
                   model_hashes=d["model_hashes"],
                   hardware=d["hardware"] if "hardware" in d else {},
                   started=d["started"], ended=d.get("ended"),
                   budget=d["budget"] if "budget" in d else {})


class RunRecordError(ValueError):
    """A stored run that cannot be read completely (interrupted output, bad line)."""


@dataclass(frozen=True)
class RunRecord:
    header: RunHeader
    results: tuple[QueryResult, ...]

    def dumps(self) -> str:
        lines = [canonical_json(self.header.to_dict())]
        lines += [canonical_json(r.to_dict()) for r in self.results]
        return "\n".join(lines) + "\n"

    @classmethod
    def loads(cls, text: str, source: str = "<run>") -> "RunRecord":
        if not text.endswith("\n"):
            raise RunRecordError(f"{source}: interrupted output (last line is unterminated)")
        lines = text.splitlines()
        if not lines:
            raise RunRecordError(f"{source}: empty run record")
        try:
            header = RunHeader.from_dict(json.loads(lines[0]))
        except (ValueError, KeyError, TypeError, AttributeError) as e:
            raise RunRecordError(f"{source}: bad header: {e}") from None
        results = []
        for n, line in enumerate(lines[1:], 2):
            try:
                results.append(QueryResult.from_dict(json.loads(line)))
            except (ValueError, KeyError, TypeError, AttributeError) as e:
                raise RunRecordError(f"{source}: line {n}: {e}") from None
        return cls(header, tuple(results))

    def write(self, path: str | os.PathLike) -> None:
        """Atomic write: the file either holds the whole record or does not change."""
        atomic_write_text(Path(path), self.dumps())

    @classmethod
    def read(cls, path: str | os.PathLike) -> "RunRecord":
        p = Path(path)
        return cls.loads(p.read_text(encoding="utf-8"), str(p))


def atomic_write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=f".{path.name}.", suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
        os.replace(tmp, path)
    except BaseException:
        if os.path.exists(tmp):
            os.unlink(tmp)
        raise


# --------------------------------------------------------------------------- verdicts


class Outcome(str, enum.Enum):
    PASS = "PASS"
    REGRESSION = "REGRESSION"
    INCONCLUSIVE = "INCONCLUSIVE"
    INVALID = "INVALID"

    @property
    def exit_code(self) -> int:
        return {"PASS": 0, "REGRESSION": 1, "INCONCLUSIVE": 2, "INVALID": 2}[self.value]


# Per-group outcomes (the overall Outcome is derived from these plus the validity checks).
GROUP_IMPROVED = "improved"
GROUP_NON_INFERIOR = "non-inferior"
GROUP_REGRESSED = "regressed"
GROUP_INCONCLUSIVE = "inconclusive"


@dataclass(frozen=True)
class GroupVerdict:
    group: str
    n: int
    metric: str
    baseline_mean: float
    candidate_mean: float
    mean_delta: float
    ci_low: float
    ci_high: float
    effect_size_dz: float | None  # mean delta / sd of deltas; None when every delta is equal
    p_value: float
    p_adjusted: float  # Holm over the collection's groups
    margin: float
    outcome: str

    def to_dict(self) -> dict:
        return dict(self.__dict__)


@dataclass(frozen=True)
class Verdict:
    outcome: Outcome
    reasons: tuple[str, ...]
    groups: tuple[GroupVerdict, ...] = ()
    numpy_version: str = numpy.__version__  # the bootstrap and sign-flip draws depend on it

    @property
    def exit_code(self) -> int:
        return self.outcome.exit_code

    def to_dict(self) -> dict:
        return {"outcome": self.outcome.value, "exit_code": self.exit_code,
                "reasons": list(self.reasons), "groups": [g.to_dict() for g in self.groups],
                "numpy_version": self.numpy_version}


def is_finite_number(x: Any) -> bool:
    return isinstance(x, (int, float)) and not isinstance(x, bool) and math.isfinite(x)
