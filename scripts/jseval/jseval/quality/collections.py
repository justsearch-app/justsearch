"""Collections: stored format, per-collection config, loaders and builders.

Stored format (one directory per collection)::

    queries.jsonl       {"qid", "text", "groups"} per line, sorted by qid
    qrels.tsv           canonical TREC qrels (graded, complete: unjudged = non-relevant)
    manifest.tsv        relpath \\t doc_id, sorted by relpath
    collection.v1.json  name, language, version, the three digests, counts, metadata

Builders (all deterministic, offline once ``ir_datasets`` has its files cached — it reads
``IR_DATASETS_HOME``, default ``~/.ir_datasets``):

- ``ir_datasets``: an official set unchanged (beir/scifact: every query, every judgment, the full
  corpus as the document pool);
- ``miracl-pool``: MIRACL rebuilt as every judged passage, relevant *and* non-relevant, plus
  seeded random filler drawn from the rest of the corpus (needs the ``ir_datasets`` docstore);
- ``beir-dir``: a materialized BEIR-layout directory (``corpus.jsonl``, ``queries.jsonl``,
  ``qrels/test.tsv``) — the recipe path for Enron cells and, later, the real-use set.

Labels are frozen: :func:`with_added_judgments` only adds judgments for new pairs, producing a new
version whose lineage records the additions, so an older version can be re-derived and verified.
"""

from __future__ import annotations

import json
import os
import random
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable, Mapping
from urllib.parse import quote

from .types import (
    COLLECTION_SCHEMA,
    Collection,
    QueryText,
    atomic_write_text,
    canonical_manifest_text,
    canonical_qrels_text,
    parse_qrels_text,
    sha256_text,
)

CONFIG_SCHEMA = "jseval.quality.config.v1"
CONFIG_DIR = Path(__file__).with_name("config")


class CollectionError(ValueError):
    """A collection that cannot be loaded as stored (corrupt, altered or unknown)."""


# --------------------------------------------------------------------------- config


@dataclass(frozen=True)
class QualityConfig:
    """Per-collection settings: where it comes from, what it must contain, how it is judged."""

    collection: str
    language: str
    source: Mapping[str, Any]
    labels: Mapping[str, str]
    expected: Mapping[str, Any]
    groups: tuple[str, ...] = ("lang", "kind")
    metric: str = "nDCG@10"
    margins: Mapping[str, float] = field(default_factory=lambda: {"default": 0.01})
    alpha: float = 0.05
    bootstrap_resamples: int = 10000
    seed: int = 20261102
    max_differing_unjudged_share: float = 0.10
    most_changed: int = 10
    top: int = 5

    def margin(self, group: str) -> float:
        return float(self.margins.get(group, self.margins["default"]))

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> "QualityConfig":
        if d.get("schema") != CONFIG_SCHEMA:
            raise CollectionError(f"not a {CONFIG_SCHEMA} file: schema={d.get('schema')!r}")
        margins = dict(d.get("margins") or {"default": 0.01})
        if "default" not in margins:
            raise CollectionError("config margins need a 'default' entry")
        boot = d.get("bootstrap") or {}
        unj = d.get("unjudged") or {}
        rep = d.get("report") or {}
        return cls(collection=d["collection"], language=d["language"], source=d["source"],
                   labels=d.get("labels") or {}, expected=d["expected"],
                   groups=tuple(d.get("groups") or ("lang", "kind")),
                   metric=d.get("metric", "nDCG@10"), margins=margins,
                   alpha=float(d.get("alpha", 0.05)),
                   bootstrap_resamples=int(boot.get("resamples", 10000)),
                   seed=int(boot.get("seed", 20261102)),
                   max_differing_unjudged_share=float(unj.get("max_differing_share", 0.10)),
                   most_changed=int(rep.get("most_changed", 10)), top=int(rep.get("top", 5)))


def known_aliases() -> list[str]:
    return sorted(p.stem for p in CONFIG_DIR.glob("*.json"))


def load_config(alias_or_path: str | os.PathLike) -> QualityConfig:
    """A config by alias (``jseval/quality/config/<alias>.json``) or by file path."""
    p = Path(alias_or_path)
    if not p.suffix:
        p = CONFIG_DIR / f"{alias_or_path}.json"
        if not p.is_file():
            raise CollectionError(f"unknown collection alias {str(alias_or_path)!r} "
                                  f"(known: {', '.join(known_aliases())})")
    try:
        return QualityConfig.from_dict(json.loads(p.read_text(encoding="utf-8")))
    except (OSError, ValueError, KeyError) as e:
        raise CollectionError(f"{p}: {e}") from None


# --------------------------------------------------------------------------- stored format


def write_collection(c: Collection, out_dir: str | os.PathLike) -> Path:
    out = Path(out_dir)
    queries = "".join(
        json.dumps({"qid": qid, "text": text, "groups": groups}, ensure_ascii=False,
                   sort_keys=True) + "\n"
        for qid, text, groups in c.queries_payload())
    atomic_write_text(out / "queries.jsonl", queries)
    atomic_write_text(out / "qrels.tsv", canonical_qrels_text(c.judgments))
    atomic_write_text(out / "manifest.tsv", canonical_manifest_text(c.documents))
    meta = {"schema": COLLECTION_SCHEMA, "name": c.name, "language": c.language,
            "version": c.version, "judgments_sha256": c.judgments_sha256,
            "manifest_sha256": c.manifest_sha256, "counts": c.counts(), "metadata": c.metadata}
    atomic_write_text(out / "collection.v1.json",
                      json.dumps(meta, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
    return out


def load_collection(path: str | os.PathLike) -> Collection:
    """Load a stored collection, refusing any file that disagrees with the recorded digests."""
    d = Path(path)
    try:
        meta = json.loads((d / "collection.v1.json").read_text(encoding="utf-8"))
        qrels_text = (d / "qrels.tsv").read_text(encoding="utf-8")
        manifest_text = (d / "manifest.tsv").read_text(encoding="utf-8")
        query_lines = (d / "queries.jsonl").read_text(encoding="utf-8").splitlines()
    except OSError as e:
        raise CollectionError(f"{d}: incomplete collection: {e}") from None
    if meta.get("schema") != COLLECTION_SCHEMA:
        raise CollectionError(f"{d}: not a {COLLECTION_SCHEMA} collection")
    try:
        judgments = parse_qrels_text(qrels_text)
        documents: dict[str, str] = {}
        for n, line in enumerate(manifest_text.splitlines(), 1):
            rel, sep, doc_id = line.partition("\t")
            if not sep or not doc_id:
                raise ValueError(f"manifest.tsv line {n}: expected 'relpath<TAB>doc_id'")
            if doc_id in documents:
                raise ValueError(f"manifest.tsv line {n}: doc id {doc_id} appears twice")
            documents[doc_id] = rel
        queries: dict[str, QueryText] = {}
        for n, line in enumerate(query_lines, 1):
            if not line.strip():
                continue
            row = json.loads(line)
            if row["qid"] in queries:
                raise ValueError(f"queries.jsonl line {n}: duplicate query id {row['qid']}")
            queries[row["qid"]] = QueryText(row["text"], dict(row.get("groups") or {}))
    except (ValueError, KeyError) as e:
        raise CollectionError(f"{d}: {e}") from None

    recorded_counts = meta.get("counts") or {}
    if sha256_text(canonical_qrels_text(judgments)) != meta.get("judgments_sha256"):
        raise CollectionError(
            f"{d}: qrels.tsv does not match the recorded judgments sha256 (judgments altered: "
            f"recorded {_count_text(recorded_counts)}, found {_count_text(_judgment_counts(judgments))})")
    if sha256_text(canonical_manifest_text(documents)) != meta.get("manifest_sha256"):
        raise CollectionError(f"{d}: manifest.tsv does not match the recorded manifest sha256")
    try:
        return Collection(meta["name"], meta["language"], queries, judgments, documents,
                          dict(meta.get("metadata") or {}), meta["version"])
    except ValueError as e:
        raise CollectionError(f"{d}: {e}") from None


def _judgment_counts(judgments: Mapping[str, Mapping[str, int]]) -> dict:
    return {"judgments": sum(len(v) for v in judgments.values()),
            "positives": sum(1 for v in judgments.values() for g in v.values() if g > 0)}


def _count_text(c: Mapping) -> str:
    return f"{c.get('judgments')} judgments / {c.get('positives')} positives"


def resolve_collection(ref: str | os.PathLike) -> tuple[Collection, QualityConfig]:
    """A stored collection directory, or a config alias built from its official source.

    A stored directory uses its own ``quality.config.json`` when it has one (recipe sets such as
    Enron or the real-use set), else the packaged config of the alias it records.
    """
    p = Path(ref)
    if (p / "collection.v1.json").is_file():
        c = load_collection(p)
        if (p / "quality.config.json").is_file():
            return c, load_config(p / "quality.config.json")
        alias = c.metadata.get("config")
        if not alias:
            raise CollectionError(f"{p}: collection records no config alias")
        return c, load_config(alias)
    if p.exists():
        raise CollectionError(f"{p}: not a stored collection (no collection.v1.json)")
    cfg = load_config(ref)
    return build(cfg), cfg


# --------------------------------------------------------------------------- builders


def doc_relpath(doc_id: str) -> str:
    """Deterministic, collision-free file name for an official document id."""
    return "docs/" + quote(doc_id, safe="-_.,()") + ".txt"


def build(cfg: QualityConfig, *, path: str | os.PathLike | None = None) -> Collection:
    kind = cfg.source.get("kind")
    if kind == "ir_datasets":
        return build_ir_datasets(cfg)
    if kind == "miracl-pool":
        return build_miracl_pool(cfg)
    if kind == "beir-dir":
        src = path or cfg.source.get("path")
        if not src:
            raise CollectionError(f"{cfg.collection}: a beir-dir collection needs its directory")
        return from_beir_dir(src, cfg)
    raise CollectionError(f"{cfg.collection}: unknown source kind {kind!r}")


def _metadata(cfg: QualityConfig, **extra: Any) -> dict:
    import ir_datasets

    return {"config": cfg.collection, "source": dict(cfg.source),
            "ir_datasets_version": ir_datasets.__version__, **extra}


def _load_queries_and_qrels(dataset_id: str, labels: Mapping[str, str]):
    import ir_datasets

    from ..corpus_fetch import _utf8_default_text_io  # Windows: the TSV reader's codepage bug

    with _utf8_default_text_io():
        ds = ir_datasets.load(dataset_id)
        queries: dict[str, QueryText] = {}
        for q in ds.queries_iter():
            if q.query_id in queries:
                raise CollectionError(f"{dataset_id}: duplicate query id {q.query_id}")
            queries[q.query_id] = QueryText(q.text, dict(labels))
        judgments: dict[str, dict[str, int]] = {}
        for r in ds.qrels_iter():
            docs = judgments.setdefault(r.query_id, {})
            if r.doc_id in docs and docs[r.doc_id] != r.relevance:
                raise CollectionError(f"{dataset_id}: conflicting grades for {r.query_id}/{r.doc_id}")
            docs[r.doc_id] = int(r.relevance)
    return ds, queries, judgments


def build_ir_datasets(cfg: QualityConfig) -> Collection:
    """An official set unchanged: every query, every judgment, the whole corpus as the pool.
    Queries without any judgment (none in beir/scifact/test) are kept out, recorded by count."""
    ds, queries, judgments = _load_queries_and_qrels(cfg.source["dataset"], cfg.labels)
    unjudged = sorted(set(queries) - set(judgments))
    queries = {q: t for q, t in queries.items() if q in judgments}
    documents = {d.doc_id: doc_relpath(d.doc_id) for d in ds.docs_iter()}
    return Collection(cfg.collection, cfg.language, queries, judgments, documents,
                      _metadata(cfg, queries_without_judgments=len(unjudged)))


def build_miracl_pool(cfg: QualityConfig) -> Collection:
    """MIRACL as every judged passage (relevant and non-relevant) plus ``filler`` passages drawn
    by ``random.Random(seed)`` from the corpus positions not judged for any query."""
    ds, queries, judgments = _load_queries_and_qrels(cfg.source["dataset"], cfg.labels)
    judged = {d for docs in judgments.values() for d in docs}
    n_filler, seed = int(cfg.source["filler"]), int(cfg.source["seed"])
    docs = ds.docs_iter()  # indexable once the docstore exists
    total = ds.docs_count()
    rng = random.Random(seed)
    filler: list[str] = []
    seen_pos: set[int] = set()
    while len(filler) < n_filler:
        pos = rng.randrange(total)
        if pos in seen_pos:
            continue
        seen_pos.add(pos)
        doc_id = docs[pos].doc_id
        if doc_id not in judged:
            filler.append(doc_id)
    pool = sorted(judged) + sorted(filler)
    return Collection(cfg.collection, cfg.language,
                      {q: t for q, t in queries.items() if q in judgments}, judgments,
                      {d: doc_relpath(d) for d in pool},
                      _metadata(cfg, judged_documents=len(judged), filler_documents=len(filler),
                                docs_count=total))


def from_beir_dir(dataset_dir: str | os.PathLike, cfg: QualityConfig) -> Collection:
    """A materialized BEIR-layout directory (the Enron recipe cells, later the real-use set).

    Per-query ``groups`` in ``queries.jsonl`` (e.g. ``{"kind": "vague"}``) override the config
    labels, so a mixed set keeps its own known-item / vague membership.
    """
    d = Path(dataset_dir)
    try:
        documents = {}
        for line in (d / "corpus.jsonl").read_text(encoding="utf-8").splitlines():
            if line.strip():
                row = json.loads(line)
                documents[row["_id"]] = row.get("relpath") or doc_relpath(row["_id"])
        queries = {}
        for line in (d / "queries.jsonl").read_text(encoding="utf-8").splitlines():
            if line.strip():
                row = json.loads(line)
                queries[row["_id"]] = QueryText(row["text"],
                                                {**cfg.labels, **(row.get("groups") or {})})
        judgments: dict[str, dict[str, int]] = {}
        for n, line in enumerate((d / "qrels" / "test.tsv").read_text(encoding="utf-8")
                                 .splitlines()):
            parts = line.split("\t")
            if n == 0 and parts[0] == "query-id":
                continue
            if len(parts) == 3:
                judgments.setdefault(parts[0], {})[parts[1]] = int(parts[2])
    except (OSError, ValueError, KeyError) as e:
        raise CollectionError(f"{d}: {e}") from None
    return Collection(cfg.collection, cfg.language,
                      {q: t for q, t in queries.items() if q in judgments}, judgments, documents,
                      {"config": cfg.collection, "source": {**cfg.source, "path": str(d)}})


def write_documents(c: Collection, out_root: str | os.PathLike) -> int:
    """Write every pool document's text (``title`` + blank line + ``text``) at its manifest path
    under ``out_root``, so an engine can index exactly the collection. Official sources only."""
    import ir_datasets

    dataset = c.metadata.get("source", {}).get("dataset")
    if not dataset:
        raise CollectionError(f"{c.name}: no ir_datasets source to read document text from")
    store = ir_datasets.load(dataset).docs_store()
    root = Path(out_root)
    n = 0
    for doc in store.get_many_iter(c.documents.keys()):
        p = root / c.documents[doc.doc_id]
        p.parent.mkdir(parents=True, exist_ok=True)
        title = getattr(doc, "title", "") or ""
        p.write_text(f"{title}\n\n{doc.text}" if title else doc.text, encoding="utf-8")
        n += 1
    if n != len(c.documents):
        raise CollectionError(f"{c.name}: wrote {n} of {len(c.documents)} documents")
    return n


# --------------------------------------------------------------------------- label updates


def with_added_judgments(c: Collection, added: Mapping[str, Mapping[str, int]],
                         note: str) -> Collection:
    """A new frozen version with judgments for previously unjudged pairs (S17).

    Existing labels never change; the lineage entry records exactly what was added, so
    :func:`ancestor_versions` can re-derive and verify each earlier version.
    """
    judgments = {q: dict(docs) for q, docs in c.judgments.items()}
    for qid, docs in added.items():
        if qid not in c.queries:
            raise CollectionError(f"added judgment for unknown query {qid}")
        for doc_id, grade in docs.items():
            if doc_id not in c.documents:
                raise CollectionError(f"added judgment for {qid}/{doc_id}: not in the manifest")
            if doc_id in judgments.get(qid, {}):
                raise CollectionError(f"{qid}/{doc_id} is already judged; labels are frozen")
            judgments.setdefault(qid, {})[doc_id] = int(grade)
    lineage = list(c.metadata.get("label_lineage") or [])
    lineage.append({"from_version": c.version, "added_qrels": canonical_qrels_text(added),
                    "note": note})
    return Collection(c.name, c.language, dict(c.queries), judgments, dict(c.documents),
                      {**c.metadata, "label_lineage": lineage})


def ancestor_versions(c: Collection) -> list[str]:
    """Every earlier label version of ``c``, newest first, each verified by re-derivation."""
    out: list[str] = []
    judgments = {q: dict(d) for q, d in c.judgments.items()}
    for entry in reversed(c.metadata.get("label_lineage") or []):
        for qid, docs in parse_qrels_text(entry["added_qrels"]).items():
            for doc_id in docs:
                del judgments[qid][doc_id]
            if not judgments[qid]:
                del judgments[qid]
        prior = Collection(c.name, c.language, dict(c.queries), judgments, dict(c.documents))
        if prior.version != entry["from_version"]:
            raise CollectionError(f"{c.name}: label lineage does not re-derive "
                                  f"{entry['from_version']}")
        out.append(prior.version)
        judgments = {q: dict(d) for q, d in judgments.items()}
    return out


def query_group(c: Collection, qid: str, by: Iterable[str]) -> str:
    """The fixed group key of a query, e.g. ``lang=de kind=vague`` (from its labels only)."""
    groups = c.queries[qid].groups
    return " ".join(f"{k}={groups.get(k, '?')}" for k in by)
