"""Collection-exclusive, fail-closed index lifecycle.

``prepare_index`` returns immediately. ``handle.wait()`` joins a low-priority,
pausable preparation and reuses its live engine through confirmation and teardown.
``confirm`` reads the live index; a cache marker is never proof of validity.
Use ``with prepare_index(...) as handle: handle.wait()`` to own and stop the backend.

Build workers finish and stop their engine before publishing entry.json. They hold
no exclusive scoring reservation. Their data lock survives caller death through
writer teardown. An incomplete worker leaves an owner record,
never an adoptable entry. The next preparation discards only that owner's partial
index. A foreign owner/population is an error, including at an explicitly chosen path.
"""
from __future__ import annotations

import argparse
import dataclasses
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import signal
import socket
import subprocess
import sys
import time
import uuid
from typing import Any, Mapping

import httpx

from .. import backend, index_identity
from jsharness import readiness
from .._paths import REPO_ROOT, shared_models_dir
from ..ingest import add_watched_root
from .collections import load_collection, write_collection, write_documents
from .types import (Collection, atomic_write_text, canonical_json,
                    canonical_manifest_text, sha256_text)

# Individual files only. These assemble/run requests, never extraction or writes.
# New files (including new reranker classes) are index-shaping until reviewed here.
QUERY_TIME_ONLY = frozenset({
    "modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeSearchEngine.java",
    "modules/app-services/src/main/java/io/justsearch/app/services/worker/SearchPipelinePresets.java",
    "modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/HybridSearchOps.java",
    # Reranker owns scores/tokenization of request candidates, not stored encodings.
    *[f"modules/reranker/src/main/java/io/justsearch/reranker/{name}.java" for name in (
        "RerankerTokenizer", "AssemblyBuildFailure", "RerankerAssembly", "CitationScorer",
        "RerankSkipCause", "OnnxModelDiscovery", "WorkerModelDiscovery", "CitationScorerConfig",
        "CrossEncoderReranker", "NumericConsistency", "RerankerShape", "RerankerConfig")],
    # Scoring orchestration and request collection; never materializes/ingests documents.
    "scripts/jseval/jseval/quality/run.py",
    # Compares stored rankings/metrics; never starts an index writer.
    "scripts/jseval/jseval/quality/compare.py",
    # Renders stored evidence; no engine or document writes.
    "scripts/jseval/jseval/quality/report.py",
    # Pure ranking metric/statistics calculations.
    "scripts/jseval/jseval/quality/scoring.py",
    # Validates collection counts and constructed rankings, without ingesting.
    "scripts/jseval/jseval/quality/known_answers.py",
    # Dispatches actions; all materialization/ingestion remains in index.py and
    # collections.py, which (along with types.py) deliberately stay in the key.
    "scripts/jseval/jseval/commands/quality.py",
})
SCHEMA = "jseval.quality.index.v1"
CACHE_ROOT = Path.home() / ".cache/justsearch-quality/indexes"
# jseval/dev-tool bookkeeping, never read by extraction/encoding/index writers.
# In particular, registration is mutable on every boot and must not key its own index.
OPERATIONAL_ENV = frozenset({"JSEVAL_GRADLE_SLOT", "JUSTSEARCH_DEV_RUNNER_STATE_ROOT",
                             "JUSTSEARCH_JOB_RECORD_DIR", "JUSTSEARCH_DATASET_CACHE",
                             "JUSTSEARCH_INDEX_CACHE", "JUSTSEARCH_GH_BIN"})


class IndexRefused(RuntimeError):
    """Inputs or live evidence do not authorize scoring/adoption."""


@dataclasses.dataclass(frozen=True)
class IndexConfig:
    repo_root: Path = REPO_ROOT
    models_dir: Path | None = None
    # Source texts, at collection manifest paths; omitted for official ir_datasets.
    documents_dir: Path | None = None
    cache_root: Path = CACHE_ROOT
    data_dir: Path | None = None
    env: Mapping[str, str] = dataclasses.field(default_factory=dict)
    request: Mapping[str, Any] = dataclasses.field(default_factory=lambda: {
        "mode": "hybrid", "limit": 10, "debug": True})
    timeout_sec: float = 1800
    sample_size: int = 3

    def environment(self) -> dict[str, str]:
        # Fail-safe: hash all inherited JustSearch runtime inputs too. Never inherit
        # another run's paths, port, or eval launcher settings.
        excluded = {"JUSTSEARCH_DATA_DIR", "JUSTSEARCH_HOME", "JUSTSEARCH_INDEX_BASE_PATH",
                    "JUSTSEARCH_API_PORT", "JUSTSEARCH_REPO_ROOT", "JUSTSEARCH_CONFIG",
                    "JUSTSEARCH_SSOT_PATH", "JUSTSEARCH_STAGE_PLUGIN_MANIFEST"}
        env = {k: v for k, v in os.environ.items()
               if k.startswith("JUSTSEARCH_") and k not in excluded}
        env.update(self.env)
        forbidden = excluded & self.env.keys()
        if forbidden:
            raise ValueError(f"lifecycle-owned environment overrides: {sorted(forbidden)}")
        # GPU selection is the caller's (inherited above) and part of the index key.
        env.update({"JUSTSEARCH_SPLADE_ENABLED": "false",
                    "JUSTSEARCH_RAG_CHUNK_SPLADE_ENABLED": "false", "JUSTSEARCH_LLM_ENABLED": "false"})
        for name in ("JAVA_OPTS", "UI_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS"):
            if name in os.environ:
                env[name] = os.environ[name]
        # An explicit sparse ablation alone opts in to these stages.
        if self.request.get("mode") == "splade":
            env["JUSTSEARCH_SPLADE_ENABLED"] = "true"
        models = self.models_dir or shared_models_dir()
        if models is None or not Path(models).is_dir():
            raise IndexRefused("models directory unavailable")
        env["JUSTSEARCH_MODELS_DIR"] = str(Path(models).resolve())
        env["JUSTSEARCH_REPO_ROOT"] = str(self.repo_root.resolve())
        slot = shutil.which("js-gradle-slot")
        if not slot:
            raise IndexRefused("js-gradle-slot unavailable")
        env["JSEVAL_GRADLE_SLOT"] = slot
        return env


def _file_hash(path: Path) -> str:
    with path.open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def tree_hash(root: Path) -> str:
    """Hash current bytes/modes, including nonignored untracked files and deletions.

    Git object ids/HEAD are intentionally absent: identical index inputs across
    commits must reuse. Ignored build products are not source inputs.
    """
    raw = subprocess.check_output(["git", "ls-files", "-z", "--cached", "--others",
                                   "--exclude-standard"], cwd=root)
    rows = []
    for name in sorted(set(os.fsdecode(n) for n in raw.split(b"\0") if n)):
        if name in QUERY_TIME_ONLY:
            continue
        path = root / name
        if path.is_symlink():
            # Include target contents as well: external config links cannot escape identity.
            rows.append((name, "link", os.readlink(path), _file_hash(path)))
        elif path.is_file():
            rows.append((name, path.stat().st_mode & 0o777, _file_hash(path)))
        elif path.exists():
            raise IndexRefused(f"unhashable source input: {name}")
        else:
            rows.append((name, "deleted"))
    return sha256_text(canonical_json(rows))


def model_hashes(models: Path) -> dict[str, str]:
    # Actual model/tokenizer/config bytes, not unchecked sha sidecars or mtimes.
    # The engine fingerprints SPLADE model assets even when that stage is disabled.
    # Hashing assets does not enable/build the unused stage.
    roots = [models / "onnx", models / "splade"]
    files = sorted({p for root in roots for p in root.rglob("*") if p.is_file()})
    if not files:
        raise IndexRefused(f"no model inputs under {models}")
    return {p.relative_to(models).as_posix(): _file_hash(p) for p in files}


def compute_index_key(collection: Collection, config: IndexConfig,
                      env: Mapping[str, str] | None = None) -> tuple[str, dict]:
    env = dict(env) if env is not None else config.environment()
    # Ports/data folders are lifecycle-owned; everything else is conservatively shaping.
    components = {"schema": SCHEMA, "tree": tree_hash(config.repo_root),
                  "models": model_hashes(Path(env["JUSTSEARCH_MODELS_DIR"])),
                  "runtime": {k: v for k, v in env.items() if k not in OPERATIONAL_ENV},
                  "collection": collection.name, "collection_version": collection.compute_version()}
    # Checkout location is not a code input; materialized paths are stable under cache root.
    components["runtime"].pop("JUSTSEARCH_REPO_ROOT", None)
    # A mutable external model/config must not hide behind a stable pathname.
    references = {}
    for name, value in env.items():
        if (name.startswith("JUSTSEARCH_") and name not in OPERATIONAL_ENV
                and name not in ("JUSTSEARCH_REPO_ROOT", "JUSTSEARCH_MODELS_DIR")
                and Path(value).is_absolute()):
            p = Path(value)
            if p.is_file():
                references[name] = _file_hash(p)
            elif p.is_dir():
                references[name] = {f.relative_to(p).as_posix(): _file_hash(f)
                                    for f in sorted(p.rglob("*")) if f.is_file()}
    components["runtime_files"] = references
    return sha256_text(canonical_json(components)), components


def _launch_env(config: IndexConfig, data: Path, env: Mapping[str, str]) -> dict[str, str]:
    """Override lifecycle paths that start_backend otherwise inherits from the caller."""
    root = config.repo_root.resolve()
    return {**env, "JUSTSEARCH_HOME": str(data), "JUSTSEARCH_DATA_DIR": str(data),
            "JUSTSEARCH_INDEX_BASE_PATH": str(data / "index/default"),
            "JUSTSEARCH_CONFIG": str(root / "modules/ui/src/main/resources/headless-config/application.yaml"),
            "JUSTSEARCH_SSOT_PATH": str(root / "SSOT"),
            "JUSTSEARCH_STAGE_PLUGIN_MANIFEST": str(root / "SSOT/manifests/plugins/pipeline-stage-plugins.v1.json")}


def _json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _write(path: Path, obj: Any) -> None:
    atomic_write_text(path, canonical_json(obj) + "\n")


def _under_cache(path: Path) -> Path:
    path = path.expanduser().resolve()
    allowed = (Path.home() / ".cache/justsearch-quality").resolve()
    if not path.is_relative_to(allowed):
        raise ValueError(f"index resources must be under {allowed}: {path}")
    return path


def _population_owner(c: Collection, key: str) -> dict:
    return {"schema": SCHEMA, "collection": c.name, "collection_version": c.version, "index_key": key,
            "index_population": c.index_population()}


def _check_owner(owner: dict, c: Collection, key: str) -> None:
    if owner != _population_owner(c, key):
        raise IndexRefused("data folder belongs to another collection, key or population")


def _quarantine(root: Path, data: Path, c: Collection, key: str, reason: str) -> Path | None:
    """Retire a refused, owned entry under its data lock. Never delete evidence.

    Keep the root and lock file in place: moving the lock would let a second
    preparer acquire a different inode while the first still owns this one.
    """
    entry = root / "entry.json"
    if not entry.exists() or root.is_relative_to(data):
        return None
    try:
        if data.exists():
            _check_owner(_json(data / "quality-owner.json"), c, key)
        else:
            _check_owner(_json(entry)["owner"], c, key)
    except (OSError, ValueError, KeyError, IndexRefused):
        return None  # Unknown/foreign state remains untouched.
    destination = root / "quarantine" / uuid.uuid4().hex
    destination.mkdir(parents=True)
    _write(destination / "reason.json", {"reason": reason, "at_unix": time.time()})
    entry.rename(destination / "entry.json")
    for name, path in [("data", data), *[(n, root / n) for n in (
            "documents", "materialized.json", "manifest.tsv", "salt",
            "documents.partial", "documents.opaque")]]:
        if path.exists():
            path.rename(destination / name)
    return destination


def _materialize(c: Collection, config: IndexConfig, root: Path) -> Collection:
    """Salted opaque filenames; preserve the collection's canonical identity separately."""
    docs = root / "documents"
    if not docs.exists():
        staging = root / "documents.partial"
        if staging.exists():
            shutil.rmtree(staging)
        staging.mkdir()
        if config.documents_dir:
            for doc, rel in c.documents.items():
                src = (config.documents_dir / rel).resolve()
                if not src.is_relative_to(config.documents_dir.resolve()):
                    raise IndexRefused(f"source path escapes documents root: {rel}")
                target = staging / rel
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(src, target)
        else:
            write_documents(c, staging)
        salt_path = root / "salt"
        if not salt_path.exists():
            atomic_write_text(salt_path, os.urandom(32).hex())
        salt = salt_path.read_text().strip()
        # Rename through a separate directory, including names that differ only by case.
        opaque = root / "documents.opaque"
        if opaque.exists():
            shutil.rmtree(opaque)
        opaque.mkdir(exist_ok=False)
        manifest = {}
        for doc, rel in c.documents.items():
            src = staging / rel
            if _file_hash(src) != c.content[doc]:
                raise IndexRefused(f"source content digest differs for {doc}")
            name = hashlib.sha256(bytes.fromhex(salt) + doc.encode()).hexdigest() + ".txt"
            shutil.copyfile(src, opaque / name)
            manifest[doc] = name
        _write(root / "materialized.json", manifest)
        atomic_write_text(root / "manifest.tsv", canonical_manifest_text(manifest))
        opaque.rename(docs)
        shutil.rmtree(staging)
    manifest = _json(root / "materialized.json")
    if set(manifest) != set(c.documents) or len(set(manifest.values())) != len(manifest):
        raise IndexRefused("materialized manifest differs from collection")
    if {p.name for p in docs.iterdir()} != set(manifest.values()):
        raise IndexRefused("materialized population differs from collection")
    for doc, rel in manifest.items():
        if Path(rel).name != rel or _file_hash(docs / rel) != c.content[doc]:
            raise IndexRefused(f"materialized content digest differs for {doc}")
    return Collection(c.name, c.language, c.queries, c.judgments, manifest, c.content, c.metadata)


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def _get(client: httpx.Client, path: str, **kw) -> dict:
    r = client.get(path, **kw)
    r.raise_for_status()
    return r.json()


def _post(client: httpx.Client, path: str, payload: dict) -> dict:
    r = client.post(path, json=payload)
    r.raise_for_status()
    return r.json()


def _preview(client: httpx.Client, doc: str, source_sha256: str | None = None) -> str:
    parts, offset, total = [], 0, None
    while True:
        page = _get(client, "/api/preview", params={"docId": doc, "offsetChars": offset,
                                                    "maxChars": 200000})
        if page.get("contentTruncated") is True:
            raise IndexRefused(f"truncated extraction: {doc}")
        if source_sha256 is not None and page.get("sourceSha256") != source_sha256:
            raise IndexRefused(f"live content digest differs for {doc}")
        if total is None:
            total = page["totalChars"]
        if total != page["totalChars"] or not isinstance(page.get("content"), str):
            raise IndexRefused("preview changed during content witness")
        parts.append(page["content"])
        if not page["truncated"]:
            break
        next_offset = page["nextOffsetChars"]
        if not isinstance(next_offset, int) or next_offset <= offset:
            raise IndexRefused("preview cursor did not advance")
        offset = next_offset
    # The Java cursor counts UTF-16 code units; a page can split an escaped
    # surrogate pair. Rejoin it before taking a UTF-8 content digest.
    return "".join(parts).encode("utf-16-le", "surrogatepass").decode("utf-16-le")


def _ready_reasons(status: dict, c: Collection, sparse: bool) -> list[str]:
    s = readiness.flatten_status(status)
    reasons = readiness._check_pipeline_complete_conditions(s, len(c.documents))
    if (s.get("meta") or {}).get("workerRpcStale"):
        reasons.append("worker status is stale")
    if s.get("indexedDocuments") != len(c.documents):
        reasons.append("population.count differs from collection")
    if s.get("completenessKnown") is not True or not s.get("completeness"):
        reasons.append("completeness audits unavailable")
    stages = {a.get("stageId") for a in s.get("completeness", [])}
    enabled_stages = {"embed": s.get("embeddingEnabled", True),
                      "ner": s.get("nerEnabled", True),
                      "chunk_embed": s.get("embeddingEnabled", True) and s.get("chunkDocCount", 0) > 0,
                      "splade": sparse, "chunk_splade": s.get("chunkSpladeEnabled", False)}
    for stage, enabled in enabled_stages.items():
        if enabled and stage not in stages:
            reasons.append(f"completeness audit unavailable: {stage}")
    if s.get("failedNerCount") != 0:
        reasons.append("NER failed count is not zero")
    for audit in s.get("completeness", []):
        if enabled_stages.get(audit.get("stageId"), False) and (audit.get("missing", 0) or audit.get("failed", 0)):
            reasons.append(f"incomplete audit: {audit.get('stageId')}")
    if not sparse and (s.get("spladeEnabled") is not False or s.get("chunkSpladeEnabled") is not False):
        reasons.append("unused sparse stages are enabled")
    return reasons


def _probe(client: httpx.Client, query: str, request: Mapping, materialized: Collection,
           docs: Path, expected: str) -> dict:
    # Adoption proves stored retrieval, independently of fusion/reranker scores.
    # Search phrase syntax escapes punctuation from the original source passage;
    # its advanced-query pipeline also disables expansion and reranking.
    phrase = '"' + query.replace('\\', '\\\\').replace('"', '\\"') + '"'
    body = _post(client, "/api/knowledge/search", {**request, "query": phrase, "debug": True})
    trace = body.get("searchTrace") or {}
    stages = {s["id"]: s.get("status") for s in trace.get("stages", [])}
    if (stages.get("sparse-retrieval") != "executed"
            or stages.get("dense-retrieval") != "skipped"
            or stages.get("cross-encoder") != "skipped"):
        raise IndexRefused(f"probe did not use lexical retrieval only: {stages}")
    locations = []
    for hit in body.get("results", []):
        # The Search REST surface delivers raw hits, not MCP's display projection.
        location = (hit.get("fields") or {}).get("path") or hit.get("id")
        if not isinstance(location, str) or not location:
            raise IndexRefused("search hit has no full document identity")
        locations.append(location)
    ranked = materialized.ranked_doc_ids(locations, docs)
    if expected not in ranked[:3]:
        raise IndexRefused(f"probe did not return expected document {expected} within top 3")
    return {"query": query, "expected": expected, "ranked": list(ranked), "stages": stages}


CANARY_STAGES = ("sparse-retrieval", "dense-retrieval", "cross-encoder")


def _canary_query(base_url: str, candidates) -> str:
    """The first candidate whose live hybrid search executes every canary stage.

    Adoption replays exactly this request (index_identity._confirm_canary). A probe
    passage can be mail boilerplate the engine routes as navigational, skipping the
    reranker by design; such a query cannot attest that the reranker serves.
    """
    seen = []
    with httpx.Client(base_url=base_url, timeout=60) as client:
        for query in dict.fromkeys(candidates):
            body = _post(client, "/api/knowledge/search",
                         {"query": query, "mode": "hybrid", "limit": 5, "debug": True})
            stages = {s.get("id"): (s.get("status"), s.get("reason"))
                      for s in (body.get("searchTrace") or {}).get("stages", [])}
            if all(stages.get(stage, (None,))[0] == "executed" for stage in CANARY_STAGES):
                return query
            seen.append({stage: stages.get(stage) for stage in CANARY_STAGES})
    raise IndexRefused(f"no canary query executes {', '.join(CANARY_STAGES)}: {seen[:3]}")


def witness(base_url: str, c: Collection, m: Collection, docs: Path,
            config: IndexConfig) -> dict:
    """Read full live population/content and deterministic first/late sample probes."""
    with httpx.Client(base_url=base_url, timeout=60) as client:
        status = _get(client, "/api/status")
        reasons = _ready_reasons(status, c, config.request.get("mode") == "splade")
        if reasons:
            raise IndexRefused("; ".join(reasons))
        page = _post(client, "/api/debug/eval/document-ids", {"offset": 0, "limit": 50000})
        ids = page["docIds"]
        if page["totalCount"] != len(c.documents) or len(ids) != len(c.documents) or len(set(ids)) != len(ids):
            raise IndexRefused("live population count differs from collection")
        by_doc = {}
        for live_id in ids:
            doc = m.doc_id_for_location(live_id, docs)
            if doc in by_doc:
                raise IndexRefused("duplicate live document identity")
            by_doc[doc] = live_id
        if set(by_doc) != set(c.documents):
            raise IndexRefused("live manifest differs from collection")
        # B1 checks every per-document digest, not just sampled content.
        texts, hashes = {}, {}
        for doc in sorted(by_doc):
            # The collection hashes the bytes given to extraction. The engine's
            # source hash proves those bytes; extracted text can legitimately
            # normalize whitespace and has a separate, repeatable witness.
            texts[doc] = _preview(client, by_doc[doc], c.content[doc])
            hashes[doc] = sha256_text(texts[doc])
        # Longest document is always included, independent of input/dict order.
        longest = max(texts, key=lambda doc: ((docs / m.documents[doc]).stat().st_size, doc))
        samples = sorted(set([longest] + sorted(texts)[:max(0, config.sample_size - 1)]))
        sample, probes = {}, []
        normalized = {doc: " ".join(text.split()).casefold() for doc, text in texts.items()}
        for doc in samples:
            chunks = _get(client, "/api/debug/chunks", params={"docId": by_doc[doc]})
            sample[doc] = {"extracted_sha256": hashes[doc], "chunks": chunks["chunksFound"]}
            # The oracle comes from the verified original, not the extractor's
            # output: a shortened extraction must not select its own easier probe.
            words = (docs / m.documents[doc]).read_text(encoding="utf-8").split()
            if not words:
                raise IndexRefused(f"empty probe document: {doc}")
            positions = [0, max(0, len(words) * 9 // 10)] if doc == longest else [0]
            for start in positions:
                query = " ".join(words[start:start + 12])
                if query not in " ".join(texts[doc].split()):
                    raise IndexRefused(f"source passage missing from extracted text: {doc} at word {start}")
                # Extend shared boilerplate until this passage names only its source.
                end = min(len(words), start + 12)
                while any(query.casefold() in text for other, text in normalized.items() if other != doc):
                    if end == len(words):
                        raise IndexRefused(f"no unique source passage for {doc} at word {start}")
                    end = min(len(words), end + 12)
                    query = " ".join(words[start:end])
                if query.casefold() not in normalized[doc]:
                    raise IndexRefused(f"source passage missing from extracted text: {doc} at word {start}")
                probe = _probe(client, query, {"mode": "text", "querySyntax": "search", "limit": 3},
                               m, docs, doc)
                probe.update(source_word_offset=start, source_word_count=len(words))
                probes.append(probe)
        meta = _get(client, "/api/debug/commit-metadata")
        if meta.get("build_state") != "COMPLETE":
            raise IndexRefused(f"index commit not COMPLETE: {meta.get('build_state')}")
        s = readiness.flatten_status(status)
        generation = index_identity._deep_find(status, "activeGenerationId")
        if not isinstance(generation, str) or not generation:
            raise IndexRefused("serving generation unavailable")
        final_status = _get(client, "/api/status")
        final_reasons = _ready_reasons(final_status, c, config.request.get("mode") == "splade")
        if (final_reasons or generation != index_identity._deep_find(final_status, "activeGenerationId")
                or index_identity._deep_find(final_status, "buildingGenerationId") not in (None, "", index_identity._MISSING)):
            raise IndexRefused(f"index changed during witness: {final_reasons}")
        return {"index_population": c.index_population(), "content": dict(c.content),
                "extracted_content": hashes,
                "samples": sample, "probes": probes, "generation": generation,
                "counts": {k: s[k] for k in ("embeddingDocCount", "spladeDocCount", "chunkDocCount")},
                "completeness": s["completeness"]}


def _agreement(a: dict, b: dict) -> list[str]:
    """Same-code lifecycle agreement also compares the full delivered rankings."""
    return _adoption_agreement(a, b) + (["witness differs: probes"] if a["probes"] != b["probes"] else [])


def _adoption_agreement(a: dict, b: dict) -> list[str]:
    # Each live lexical probe already proved its expected document in the top 3.
    # Query-time ranking is deliberately absent from the reusable index key.
    return [f"witness differs: {k}" for k in ("index_population", "content", "extracted_content", "samples",
                                             "generation", "completeness") if a[k] != b[k]]


@dataclasses.dataclass
class IndexHandle:
    collection: Collection
    config: IndexConfig
    index_key: str
    root: Path
    data_dir: Path
    index_build: str
    components: dict
    env: dict
    _lock: Any = dataclasses.field(repr=False)
    _worker: subprocess.Popen | None = dataclasses.field(default=None, repr=False)
    _backend: Any = dataclasses.field(default=None, repr=False)
    base_url: str | None = None
    index_state: dict = dataclasses.field(default_factory=dict)

    @property
    def index_population(self) -> dict:
        return self.index_state.get("index_population", {})

    @property
    def index_cache_key(self) -> str | None:
        return self.index_key if self.index_build == "cached" else None

    @property
    def model_hashes(self) -> dict[str, str]:
        return self.components["models"]

    @property
    def documents_dir(self) -> Path:
        return self.root / "documents"

    def ranked_doc_ids(self, locations) -> list[str]:
        """Translate delivered full paths through the salted materialization manifest.

        Scoring callers must use this manifest, never a filename/basename heuristic.
        Unknown paths fail; multiple chunks keep their first delivered position.
        """
        c = self.collection
        materialized = Collection(c.name, c.language, c.queries, c.judgments,
                                  _json(self.root / "materialized.json"), c.content, c.metadata)
        return materialized.ranked_doc_ids(locations, self.documents_dir)

    def wait(self, *, prepared_only: bool = False, deadline: float | None = None) -> "IndexHandle":
        if self._backend:
            return self
        if prepared_only and self._worker:
            raise IndexRefused("not ready: index is building in the background")
        if self._worker:
            until = deadline if deadline is not None else time.monotonic() + self.config.timeout_sec + 3720
            while time.monotonic() < until:
                code = self._worker.poll()
                if code is not None:
                    raise IndexRefused(f"background preparation exited {code}: "
                                       f"{(self.root / 'build.log').read_text()[-6000:]}")
                ready = self.root / "prepared.json"
                if ready.exists():
                    prepared = _json(ready)
                    self.base_url = prepared["url"]
                    self.index_state = prepared["index_state"]
                    # Only the supervisor stops this engine. Its admission and
                    # inherited data lock cover the complete writer lifetime.
                    self._backend = backend.BackendInfo(self._worker, self.data_dir)
                    return self
                time.sleep(.05)
            self.close()
            raise IndexRefused("background preparation deadline expired")
        entry = _json(self.root / "entry.json")
        _check_owner(entry["owner"], self.collection, self.index_key)
        deadline = deadline if deadline is not None else time.monotonic() + self.config.timeout_sec
        port = _free_port()
        try:
            # Distribution absence is not evidence against an owned cached index.
            if prepared_only:
                backend.require_prepared(self.config.repo_root)
        except BaseException:
            self.close()
            raise
        try:
            self._backend = backend.start_backend(repo_root=self.config.repo_root, data_dir=self.data_dir,
                                             port=port, env_overrides=_launch_env(self.config, self.data_dir, self.env),
                                             health_timeout_sec=max(.001, min(180, deadline - time.monotonic())),
                                             **({"prepared_only": True, "startup_admission": False}
                                                if prepared_only else {}))
        except BaseException as e:
            # start_backend tears down an unsuccessful start before raising.
            try:
                if self.index_build == "cached":
                    _quarantine(self.root, self.data_dir, self.collection, self.index_key, str(e))
            finally:
                self.close()
            raise
        self.base_url = f"http://127.0.0.1:{port}"
        # Warm-up/readiness is behavioral, not the launcher's health check.
        last = None
        while time.monotonic() < deadline:
            result = confirm(self)
            if result.ok:
                return self
            last = result.failures
            if self._backend.proc.poll() is not None:
                break
            time.sleep(2)
        reason = f"not ready: serving backend not confirmed: {last}; run quality prepare outside the scoring window"
        try:
            # The engine must release its files before quarantine, and the data
            # lock stays held until all refused state has been moved aside.
            if self._backend:
                backend.stop_backend(self._backend.proc, data_dir=self.data_dir)
                self._backend = None
            if self.index_build == "cached":
                _quarantine(self.root, self.data_dir, self.collection, self.index_key, reason)
        finally:
            self.close()
        raise IndexRefused(reason)

    def close(self) -> None:
        try:
            if self._worker:
                # Admission owns, identifies and cleans this worker's whole process tree.
                ready = (self.root / "prepared.json").exists()
                (self.root / "stop").touch()
                # Give a ready worker time to stop its writer and publish before
                # asking admission to cancel an unfinished preparation.
                if ready:
                    try:
                        self._worker.wait(timeout=60)
                    except subprocess.TimeoutExpired:
                        self._worker.terminate()
                elif self._worker.poll() is None:
                    self._worker.terminate()
                if self._worker.poll() is None:
                    self._worker.wait(timeout=150)
                self._backend = None
                if ready and self._worker.poll() != 0:
                    raise IndexRefused(f"preparation teardown failed: exit {self._worker.returncode}; "
                                       f"{(self.root / 'build.log').read_text()[-6000:]}")
            if self._backend:
                backend.stop_backend(self._backend.proc, data_dir=self.data_dir)
                self._backend = None
        finally:
            if not self._lock.closed:
                self._lock.close()

    def __enter__(self) -> "IndexHandle":
        return self

    def __exit__(self, *_):
        self.close()


def prepare_index(collection: Collection, config: IndexConfig, *, prepared_only: bool = False) -> IndexHandle:
    if (collection.version != collection.compute_version() or not collection.documents
            or len(collection.documents) > 50000):
        raise IndexRefused("empty or modified collection")
    env = config.environment()
    key, components = compute_index_key(collection, config, env)
    root = _under_cache(config.cache_root) / sha256_text(collection.name) / key
    data = _under_cache(config.data_dir) if config.data_dir else root / "data"
    if prepared_only:
        backend.require_prepared(config.repo_root)
        if not (root / "entry.json").is_file():
            raise IndexRefused("not ready: no complete prepared index; run quality prepare outside the scoring window")
    root.mkdir(parents=True, exist_ok=True)
    # Lock the actual data path, including explicit paths shared by different keys.
    data.parent.mkdir(parents=True, exist_ok=True)
    lock = (data.parent / (data.name + ".quality.lock")).open("a+")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        owner_path = data / "quality-owner.json"
        if owner_path.exists():
            _check_owner(_json(owner_path), collection, key)
        elif data.exists() and any(data.iterdir()):
            raise IndexRefused("nonempty data folder has no collection owner")
        entry_path = root / "entry.json"
        adopted = entry_path.exists()
        if adopted:
            _check_owner(_json(entry_path)["owner"], collection, key)
            if not owner_path.exists():
                raise IndexRefused("cache entry has no owned index")
            if prepared_only:
                backend.require_prepared(config.repo_root, data)
        else:
            # Only our exact owner's interrupted data may be replaced.
            if data.exists():
                shutil.rmtree(data)
            data.mkdir()
            _write(owner_path, _population_owner(collection, key))
        handle = IndexHandle(collection, config, key, root, data,
                             "cached" if adopted else "fresh", components, env, lock)
        if adopted:
            _materialize(collection, config, root)  # rehash the source files before boot
            if prepared_only:
                return handle
        write_collection(collection, root / "collection")
        (root / "worker.json").unlink(missing_ok=True)
        (root / "engine-ready").unlink(missing_ok=True)
        (root / "prepared.json").unlink(missing_ok=True)
        (root / "stop").unlink(missing_ok=True)
        serial = dataclasses.asdict(config)
        for k in ("repo_root", "models_dir", "documents_dir", "cache_root", "data_dir"):
            serial[k] = str(serial[k]) if serial[k] is not None else None
        _write(root / "build.json", {"config": serial, "env": env, "key": key, "data": str(data),
                                    "adopted": adopted, "parent": _process(os.getpid())})
        # __name__ is __main__ when the agreement command runs via python -m.
        hook = [sys.executable, "-m", "jseval.quality.index", "hook", str(root)]
        command = [sys.executable, str(config.repo_root / "scripts/dev/admission.py"), "pausable",
                   "--purpose", f"quality index {collection.name}",
                   "--pause-hook", shlex.join(hook + ["pause"]),
                   "--resume-hook", shlex.join(hook + ["resume"]), "--",
                   "nice", "-n", "19", "ionice", "-c3", sys.executable, "-m", "jseval.quality.index", "build", str(root)]
        worker_env = os.environ.copy()
        worker_env.pop("JS_ADMISSION_TOKEN", None)
        # Use the orchestrating harness version in the worker too. repo_root is
        # the engine candidate; CPU acceptance may point it at a clean engine
        # clone while running this uncommitted harness from another checkout.
        worker_env["PYTHONPATH"] = os.pathsep.join(filter(None, (
            str(Path(__file__).resolve().parents[2]), worker_env.get("PYTHONPATH"))))
        with (root / "build.log").open("w") as out:
            handle._worker = subprocess.Popen(command, cwd=config.repo_root, env=worker_env,
                                              stdout=out, stderr=subprocess.STDOUT,
                                              pass_fds=(lock.fileno(),))
        return handle
    except BaseException as e:
        try:
            # A held data lock is required; lock contention must not mutate
            # another preparer's cache. Only our exact owned data can move.
            if not isinstance(e, BlockingIOError):
                _quarantine(root, data, collection, key, f"{type(e).__name__}: {e}")
        finally:
            lock.close()
        if prepared_only and isinstance(e, Exception):
            raise IndexRefused(f"not ready: prepared index refused ({e}); run quality prepare outside the scoring window") from e
        raise


def confirm(handle: IndexHandle) -> index_identity.ConfirmResult:
    if handle._worker:
        # A caller-requested extra probe cannot race an exclusive pause of the
        # preparation worker. Internal confirmation already runs in that worker.
        with backend._machine_admission({**os.environ, **handle.env}):
            return _confirm(handle)
    return _confirm(handle)


def _confirm(handle: IndexHandle, entry: dict | None = None) -> index_identity.ConfirmResult:
    handle.index_state = {"settled": False}
    failures = []
    checks = {}
    try:
        if not handle.base_url or not handle._backend or handle._backend.proc.poll() is not None:
            raise IndexRefused("no live serving backend")
        key, _ = compute_index_key(handle.collection, handle.config, handle.env)
        if key != handle.index_key:
            raise IndexRefused("index-shaping inputs changed since preparation")
        _check_owner(_json(handle.data_dir / "quality-owner.json"), handle.collection, handle.index_key)
        if entry is None:
            entry = (_json(handle.root / "prepared.json")["entry"] if handle._worker
                     else _json(handle.root / "entry.json"))
        _check_owner(entry["owner"], handle.collection, handle.index_key)
        m = _materialize(handle.collection, handle.config, handle.root)
        current = witness(handle.base_url, handle.collection, m, handle.root / "documents", handle.config)
        failures.extend(_adoption_agreement(entry["witness"], current))
        adopted = index_identity.confirm_adoption(handle.base_url, entry, handle.data_dir,
                                                  handle.env, index_key=handle.index_key)
        failures.extend(adopted.failures)
        checks.update(adopted.checks)
        if not failures:
            handle.index_state = {**current, "settled": True, "index_cache_key": handle.index_cache_key}
    except Exception as e:
        failures.append(f"{type(e).__name__}: {e}")
    if failures:
        handle.index_state["confirmation_failures"] = failures
    return index_identity.ConfirmResult(not failures, failures, checks)


def _process(pid: int) -> dict:
    stat = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()
    return {"pid": pid, "pgid": int(stat[2]), "birth": stat[19], "state": stat[0]}


def _hook(root: Path, action: str) -> None:
    # Admission's child starts its own session; this is the group containing ONLY
    # this build controller, its eval runner, engine and engine-owned descendants.
    deadline = time.monotonic() + 50
    while not (root / "worker.json").exists():
        if time.monotonic() >= deadline:
            raise IndexRefused("build process identity unavailable")
        time.sleep(.05)
    owner = _json(root / "worker.json")
    current = _process(owner["pid"])
    if current["birth"] != owner["birth"] or current["pgid"] != owner["pid"]:
        raise IndexRefused("build process identity changed")
    os.killpg(owner["pid"], signal.SIGSTOP if action == "pause" else signal.SIGCONT)
    if action == "pause":
        while time.monotonic() < deadline:
            members = []
            for path in Path("/proc").iterdir():
                if path.name.isdigit():
                    try:
                        p = _process(int(path.name))
                    except (OSError, IndexError):
                        continue
                    if p["pgid"] == owner["pid"]:
                        members.append(p)
            if members and all(p["state"] in ("T", "t", "Z") for p in members):
                return
            time.sleep(.05)
        raise IndexRefused("build process group did not stop")


def _build(root: Path) -> None:
    spec = _json(root / "build.json")
    raw = spec["config"]
    for k in ("repo_root", "models_dir", "documents_dir", "cache_root", "data_dir"):
        raw[k] = Path(raw[k]) if raw[k] is not None else None
    cfg = IndexConfig(**raw)
    c = load_collection(root / "collection")
    data = Path(spec["data"])
    _write(root / "worker.json", _process(os.getpid()))
    m = _materialize(c, cfg, root)
    port = _free_port()
    info = None
    adopted = spec.get("adopted", False)
    attempted = False
    try:
        if adopted:
            backend.require_prepared(cfg.repo_root)
        attempted = True
        info = backend.start_backend(repo_root=cfg.repo_root, data_dir=data, port=port,
                                     env_overrides=_launch_env(cfg, data, spec["env"]), health_timeout_sec=180,
                                     startup_admission=False, prepared_only=adopted)
        url = f"http://127.0.0.1:{port}"
        _write(root / "engine-ready", {"url": url})
        if not adopted:
            add_watched_root(url, root / "documents")
        deadline = time.monotonic() + cfg.timeout_sec
        last = None
        while time.monotonic() < deadline:
            try:
                w = witness(url, c, m, root / "documents", cfg)
                break
            except (IndexRefused, httpx.HTTPError) as e:
                last = str(e)
            if info.proc.poll() is not None:
                raise IndexRefused(f"build engine exited: {info.proc.returncode}")
            time.sleep(2)
        else:
            raise IndexRefused(f"build readiness deadline: {last}")
        key, components = compute_index_key(c, cfg, spec["env"])
        if key != spec["key"]:
            raise IndexRefused("index-shaping inputs changed during build")
        if adopted:
            entry = _json(root / "entry.json")
        else:
            identity = index_identity.compute_live_identity(url, spec["env"], index_key=spec["key"])
            canary = _canary_query(url, [p["query"] for p in w["probes"]]
                                   + [q.text for _, q in sorted(c.queries.items())])
            roots = _json(data / "watched_roots.json")
            entry = {"schema": SCHEMA, "owner": _population_owner(c, spec["key"]),
                     "identity": identity.to_doc(), "witness": w,
                     "attestation": {"build_state": "COMPLETE", "generation_id": w["generation"],
                                     "counts": w["counts"],
                                     "watched_roots": [r["path"] for r in roots["roots"]],
                                     "canary": {"query": canary, "required_stages": list(CANARY_STAGES)}}}
        handle = IndexHandle(c, cfg, key, root, data, "cached" if adopted else "fresh",
                             components, spec["env"], None, _backend=info, base_url=url)
        result = _confirm(handle, entry)
        if not result.ok:
            raise IndexRefused(f"not ready: preparation confirmation failed: {result.failures}")
        _write(root / "prepared.json", {"url": url, "index_state": handle.index_state, "entry": entry})
        # Keep this same engine under pausable admission until its caller closes
        # it or dies. The supervisor retains the data lock throughout teardown.
        parent = spec["parent"]
        while not (root / "stop").exists():
            try:
                current = _process(parent["pid"])
            except (OSError, IndexError):
                break
            if current["birth"] != parent["birth"] or current["state"] == "Z":
                break
            if info.proc.poll() is not None:
                raise IndexRefused("preparation engine exited before close")
            time.sleep(.05)
        # Close the writer before publishing; failed/interrupted close never publishes.
        backend.stop_backend(info.proc, data_dir=data)
        info = None
        _write(root / "entry.json", entry)
    except BaseException as e:
        if info:
            backend.stop_backend(info.proc, data_dir=data)
            info = None
        if adopted and attempted:
            _quarantine(root, data, c, spec["key"], str(e))
        raise
    finally:
        (root / "prepared.json").unlink(missing_ok=True)
        if info:
            backend.stop_backend(info.proc, data_dir=data)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for cmd in ("build", "hook"):
        p = commands.add_parser(cmd)
        p.add_argument("root", type=Path)
        if cmd == "hook":
            p.add_argument("action", choices=("pause", "resume"))
    p = commands.add_parser("agreement", help="S23: fresh build, adoption and restart")
    p.add_argument("collection", type=Path)
    p.add_argument("--documents-dir", type=Path)
    p.add_argument("--cache-root", type=Path, required=True, help="a new directory under ~/.cache/justsearch-quality")
    args = parser.parse_args()
    if args.command == "build":
        _build(args.root)
    elif args.command == "hook":
        _hook(args.root, args.action)
    else:
        if args.cache_root.exists():
            parser.error("agreement needs a new cache directory to prove a fresh build")
        c = load_collection(args.collection)
        cfg = IndexConfig(documents_dir=args.documents_dir, cache_root=args.cache_root)
        states, times = [], {}
        for mode in ("build", "adopt", "restart"):
            start = time.monotonic()
            with prepare_index(c, cfg) as handle:
                handle.wait()
                states.append(handle.index_state)
                times[mode] = round(time.monotonic() - start, 3)
        failures = _agreement(states[0], states[1]) + _agreement(states[0], states[2])
        print(canonical_json({"ok": not failures, "failures": failures, "seconds": times,
                              "witness": states[0]}))
        raise SystemExit(2 if failures else 0)


if __name__ == "__main__":
    main()
