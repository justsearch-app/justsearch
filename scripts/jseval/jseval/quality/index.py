"""Collection-exclusive, fail-closed index lifecycle.

``prepare_index`` returns once the checkout's distribution snapshot is selected
(one incremental installDist through the Gradle slot when its sources have none;
scoring never installs). ``handle.wait()`` joins a low-priority, pausable
preparation and reuses its live engine through confirmation and teardown.
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
from . import distribution, inputs
from jsharness import readiness
from .._paths import REPO_ROOT, shared_models_dir
from ..ingest import add_watched_root
from .collections import load_collection, write_collection, write_documents
from .types import (Collection, atomic_write_text, canonical_json,
                    canonical_manifest_text, sha256_text)

SCHEMA = "jseval.quality.index.v2"
CACHE_ROOT = Path.home() / ".cache/justsearch-quality/indexes"
# jseval/dev-tool bookkeeping, never read by extraction/encoding/index writers.
# In particular, registration is mutable on every boot and must not key its own index.
OPERATIONAL_ENV = frozenset({"JSEVAL_GRADLE_SLOT", "JUSTSEARCH_DEV_RUNNER_STATE_ROOT",
                             "JUSTSEARCH_JOB_RECORD_DIR", "JUSTSEARCH_DATASET_CACHE",
                             "JUSTSEARCH_INDEX_CACHE", "JUSTSEARCH_GH_BIN"})
# The ORT optimized-graph store is off for quality runs. Its cache hit loads a stored graph
# whose bytes it never verifies (OrtOptimizedModelStore.loadOrCreate/committed), so a shared
# graph would bypass the models component. With a 0 MiB cap loadOrCreate never reads or
# writes an entry: it loads the source graph and ORT optimizes it in memory, and initialize()
# returns before pruning anything. Its folder is the entry's own data folder regardless.
ORT_CACHE_CAP, ORT_CACHE_DIR = "JUSTSEARCH_ORT_OPTIMIZED_CACHE_MAX_MB", "JUSTSEARCH_ORT_OPTIMIZED_CACHE_DIR"
# The selected snapshot digest; the launch derives every code and resource path from it.
DISTRIBUTION_ENV = "JSEVAL_DISTRIBUTION"
# Pinned paths whose content is a key component of its own (never their location).
SEPARATELY_HASHED = frozenset({"JUSTSEARCH_REPO_ROOT", "JUSTSEARCH_MODELS_DIR", "JUSTSEARCH_TESSERACT_PATH",
                               "JUSTSEARCH_TESSDATA_PATH", "JUSTSEARCH_DEV_JDK_HOME", "JAVA_HOME",
                               "LD_LIBRARY_PATH", DISTRIBUTION_ENV, *inputs.JVM_OPTION_VARIABLES})


class IndexRefused(RuntimeError):
    """Inputs or live evidence do not authorize scoring/adoption."""


class TransientIndexRefused(IndexRefused):
    """Serving is unavailable; stored index content has not been shown corrupt."""


class InputUnavailable(TransientIndexRefused):
    """An effective input could not be resolved, hashed or validated, or changed.

    Refuses reuse and serving; it is never evidence against a stored index, so a
    previously valid cached entry is never quarantined for it.
    """


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
        """The pinned launch environment: every input explicit, nothing left to discovery."""
        # Fail-safe: hash all inherited JustSearch runtime inputs too. Never inherit
        # another run's paths, port, or eval launcher settings.
        excluded = {"JUSTSEARCH_DATA_DIR", "JUSTSEARCH_HOME", "JUSTSEARCH_INDEX_BASE_PATH",
                    "JUSTSEARCH_API_PORT", "JUSTSEARCH_REPO_ROOT", "JUSTSEARCH_CONFIG",
                    "JUSTSEARCH_SSOT_PATH", "JUSTSEARCH_STAGE_PLUGIN_MANIFEST", ORT_CACHE_CAP, ORT_CACHE_DIR}
        env = {k: v for k, v in os.environ.items()
               if k.startswith("JUSTSEARCH_") and k not in excluded}
        env.update(self.env)
        forbidden = (excluded | {DISTRIBUTION_ENV}) & self.env.keys()
        if forbidden:
            raise ValueError(f"lifecycle-owned environment overrides: {sorted(forbidden)}")
        # GPU selection is the caller's (inherited above), explicit, and part of the index key.
        env[ORT_CACHE_CAP] = "0"
        env.update({"JUSTSEARCH_SPLADE_ENABLED": "false",
                    "JUSTSEARCH_RAG_CHUNK_SPLADE_ENABLED": "false", "JUSTSEARCH_LLM_ENABLED": "false"})
        # An explicit sparse ablation alone opts in to these stages.
        if self.request.get("mode") == "splade":
            env["JUSTSEARCH_SPLADE_ENABLED"] = "true"
        models = self.models_dir or shared_models_dir()
        if models is None or not Path(models).is_dir():
            raise IndexRefused("models directory unavailable; pass --models-dir /path/to/shared/models")
        env["JUSTSEARCH_MODELS_DIR"] = str(Path(models).resolve())
        process = {**os.environ, **env}
        try:
            # Every JVM option variable is set, to its validated value or empty.
            env.update(inputs.jvm_options(process))
            env["LD_LIBRARY_PATH"] = process.get("LD_LIBRARY_PATH", "")
            inputs.native_selection(env)
            inputs.model_selection(env)
            env["JAVA_HOME"] = str(inputs.resolve_jdk(self.repo_root, process))
            # The engine's cwd is the snapshot; its bundle-root search starts there.
            exe, data = inputs.resolve_ocr(process, distribution.ROOT / "snapshots")
        except inputs.InputRefused as e:
            raise InputUnavailable(str(e)) from None
        env["JUSTSEARCH_TESSERACT_PATH"], env["JUSTSEARCH_TESSDATA_PATH"] = str(exe), str(data)
        env["JUSTSEARCH_REPO_ROOT"] = str(self.repo_root.resolve())
        slot = shutil.which("js-gradle-slot")
        if not slot:
            raise IndexRefused("js-gradle-slot unavailable")
        env["JSEVAL_GRADLE_SLOT"] = slot
        return env


def _file_hash(path: Path) -> str:
    with path.open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def model_hashes(models: Path, *, llm_off: bool) -> dict[str, str]:
    try:
        return inputs.model_hashes(models, llm_off=llm_off)
    except inputs.InputRefused as e:
        raise InputUnavailable(str(e)) from None


def compute_index_key(collection: Collection, config: IndexConfig,
                      env: Mapping[str, str] | None = None) -> tuple[str, dict]:
    """Key over everything that can change index contents, each by content, never location.

    Unrelated source edits (docs, web UI, CI, scoring code) leave the snapshot digest and
    so the key unchanged. Any input that cannot be resolved, hashed or validated refuses.
    """
    env = dict(env) if env is not None else config.environment()
    try:
        inputs.jvm_options(env)
        inputs.native_selection(env)
        inputs.model_selection(env)
        if env.get(ORT_CACHE_CAP) != "0":
            raise inputs.InputRefused(f"the ORT optimized-graph cache is not disabled ({ORT_CACHE_CAP}=0)")
        for name in (DISTRIBUTION_ENV, "JAVA_HOME", "JUSTSEARCH_MODELS_DIR",
                     "JUSTSEARCH_TESSERACT_PATH", "JUSTSEARCH_TESSDATA_PATH"):
            if not env.get(name):
                raise inputs.InputRefused(f"input not pinned: {name}")
        snapshot = distribution.verify(env[DISTRIBUTION_ENV])
        # The snapshot is hashed and immutable; what its engine configuration names must lie inside it.
        inputs.config_references(distribution.path(snapshot))
        components = {
            "schema": SCHEMA,
            "distribution": snapshot,
            "harness": inputs.harness_digest(),
            "jdk": inputs.jdk_digest(Path(env["JAVA_HOME"])),
            "jvm_options": {name: env.get(name, "") for name in inputs.JVM_OPTION_VARIABLES},
            "library_path": inputs.library_path(env.get("LD_LIBRARY_PATH", "")),
            "ocr": inputs.ocr_digest(Path(env["JUSTSEARCH_TESSERACT_PATH"]), Path(env["JUSTSEARCH_TESSDATA_PATH"])),
            "models": inputs.model_hashes(Path(env["JUSTSEARCH_MODELS_DIR"]), llm_off=inputs.llm_off(env)),
            # Ports/data folders are lifecycle-owned; everything else is conservatively shaping.
            "runtime": inputs.runtime(env, OPERATIONAL_ENV | SEPARATELY_HASHED),
            "collection": collection.name, "documents": collection.document_identity()}
    except (inputs.InputRefused, distribution.DistributionRefused) as e:
        raise InputUnavailable(f"not ready: {e}") from None
    finally:
        distribution.flush_digests()
    return sha256_text(canonical_json(components)), components


def identity_digests(components: Mapping[str, Any]) -> dict[str, str]:
    """One digest per key component: what a run header records instead of paths."""
    return {name: (value if name == "distribution" else sha256_text(canonical_json(value)))
            for name, value in components.items() if name not in ("schema", "collection")}


def pin_locations(env: dict) -> None:
    """Resolve every remaining location once against the selected snapshot, the engine's
    working directory; the key hashes and the launch receives these absolute paths."""
    try:
        env.update(inputs.pin_locations(env, distribution.path(env[DISTRIBUTION_ENV])))
    except inputs.InputRefused as e:
        raise InputUnavailable(f"not ready: {e}") from None


def _select_distribution(config: IndexConfig, env: Mapping[str, str], prepared_only: bool) -> str:
    """The snapshot built from the checkout's current sources; preparation installs one if needed.

    Scoring never installs: a checkout without its own snapshot is refused before any
    engine starts. An equal checkout elsewhere (same HEAD and working tree) shares one.
    """
    root = config.repo_root
    try:
        source = list(backend.source_identity(root))
    except (OSError, subprocess.CalledProcessError) as e:
        raise InputUnavailable(f"not ready: checkout source identity unavailable ({e})") from None
    try:
        if prepared_only:
            return distribution.current(root, source)
        digest = distribution.lookup(source)
        if digest is None:
            # The Gradle invoker queues in its slot and takes machine admission of its own; the
            # snapshot is taken and published inside that same hold (_snapshot_install).
            install_env = {k: v for k, v in {**os.environ, **env}.items() if k != DISTRIBUTION_ENV}
            try:
                backend._install_distribution(root, install_env, then=f"{__name__}:_snapshot_install")
            except distribution.DistributionRefused:
                raise
            except Exception as e:
                raise TransientIndexRefused(f"not ready, transient: distribution preparation failed: {e}") from e
            digest = distribution.lookup(source)
            if digest is None:
                raise TransientIndexRefused("not ready, transient: checkout changed during installDist or "
                                            "snapshot; no snapshot of its current sources was published")
        distribution.publish(root, source, digest)
        distribution.prune()
        return digest
    except distribution.DistributionRefused as e:
        raise InputUnavailable(str(e)) from None


def _snapshot_install(root: Path) -> None:
    """Snapshot and publish the install just made, still inside the installation's hold.

    Publishes nothing when the checkout changed: the caller then finds no snapshot for its
    sources. An incomplete install raises and is never published.
    """
    source = list(backend.source_identity(root))
    try:
        receipt = _json(root / distribution.INSTALL / distribution.RECEIPT)
    except (OSError, ValueError):
        receipt = None
    if receipt != source:
        return
    digest = distribution.create(root, source)
    if list(backend.source_identity(root)) == source:
        distribution.publish(root, source, digest)


def _launch_env(config: IndexConfig, data: Path, env: Mapping[str, str]) -> dict[str, str]:
    """Override lifecycle paths; code and repo resources come from the immutable snapshot."""
    snapshot = distribution.path(env[DISTRIBUTION_ENV])
    # Model discovery also tries <data>/models (data and home folder) and <snapshot>/models
    # (repo root; a snapshot never holds one): all must stay absent so only the hashed root serves.
    if (data / "models").exists() or (snapshot / "models").exists():
        raise InputUnavailable("not ready: a models folder outside the hashed models root would serve "
                               "model discovery (data or snapshot folder)")
    return {**env, ORT_CACHE_DIR: str(data / "ort-optimized"),
            "JUSTSEARCH_HOME": str(data), "JUSTSEARCH_DATA_DIR": str(data),
            "JUSTSEARCH_INDEX_BASE_PATH": str(data / "index/default"),
            "JUSTSEARCH_REPO_ROOT": str(snapshot),
            "JUSTSEARCH_CONFIG": str(snapshot / inputs.ENGINE_CONFIG),
            "JUSTSEARCH_SSOT_PATH": str(snapshot / "SSOT"),
            "JUSTSEARCH_STAGE_PLUGIN_MANIFEST": str(snapshot / "SSOT/manifests/plugins/pipeline-stage-plugins.v1.json")}


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
    return {"schema": SCHEMA, "collection": c.name, "documents": c.document_identity(), "index_key": key,
            "index_population": c.index_population()}


def _check_owner(owner: dict, c: Collection, key: str) -> None:
    if isinstance(owner, dict) and owner.get("schema") != SCHEMA:
        raise IndexRefused(f"data folder was prepared under an older index identity "
                           f"({owner.get('schema')}); it is never adopted: prepare a new index "
                           f"without --data-dir or choose an empty data folder")
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
    """Verify source bytes and materialize the collection's declared layout."""
    docs = root / "documents"
    as_is = c.metadata.get("layout", "opaque") == "as-is"
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
            name = rel if as_is else hashlib.sha256(bytes.fromhex(salt) + doc.encode()).hexdigest() + ".txt"
            target = opaque / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(src, target)
            manifest[doc] = name
        _write(root / "materialized.json", manifest)
        atomic_write_text(root / "manifest.tsv", canonical_manifest_text(manifest))
        opaque.rename(docs)
        shutil.rmtree(staging)
    manifest = _json(root / "materialized.json")
    if set(manifest) != set(c.documents) or len(set(manifest.values())) != len(manifest):
        raise IndexRefused("materialized manifest differs from collection")
    if as_is and manifest != c.documents:
        raise IndexRefused("materialized as-is paths differ from collection")
    population = list(docs.rglob("*"))
    if (any(p.is_symlink() or (not as_is and p.is_dir()) for p in population)
            or {p.relative_to(docs).as_posix() for p in population if p.is_file()} != set(manifest.values())):
        raise IndexRefused("materialized population differs from collection")
    for doc, rel in manifest.items():
        p = docs / rel
        if (not p.resolve().is_relative_to(docs.resolve()) or p.is_symlink()
                or (not as_is and Path(rel).name != rel) or _file_hash(p) != c.content[doc]):
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


def _readiness_refusal(status: dict, reasons: list[str]) -> IndexRefused:
    """Readiness alone proves bad content only from fresh, settled observations."""
    s = readiness.flatten_status(status)
    fresh = not (s.get("meta") or {}).get("workerRpcStale")
    observations = s.get("countObservations") or {}
    known_count = (s.get("indexedDocumentsUnknown") is False
                   and isinstance(s.get("indexedDocuments"), int)
                   and s["indexedDocuments"] >= 0
                   and all(observations.get(name, {}).get("state") not in ("unknown", "stale")
                           for name in ("active", "searchable")))
    bad_population = fresh and known_count and "population.count differs from collection" in reasons
    # Do not infer corruption from coverage alone, queued work, unavailable
    # audits, or the default enable flags published before the worker is ready.
    idle = (s.get("indexState") == "IDLE" and s.get("pendingJobs", 0) == 0
            and not readiness._check_index_idle_conditions(s, -1))
    pending_fields = {"embed": "embeddingPendingCount", "ner": "pendingNerCount",
                      "chunk_embed": "chunkEmbeddingPendingCount", "splade": "spladePendingCount",
                      "chunk_splade": "chunkSpladePendingCount"}
    # Readiness defaults absent pending counts to zero; quarantine needs an
    # explicitly observed zero for the affected stage.
    missing_content = any(f"enrichment_missing({stage})" in reasons and s.get(pending) == 0
                          for stage, pending in pending_fields.items())
    bad_content = (fresh and idle and s.get("completenessKnown") is True
                   and (missing_content or any(reason.startswith("enrichment_failed(") for reason in reasons)))
    refusal = IndexRefused if bad_population or bad_content else TransientIndexRefused
    return refusal("; ".join(reasons))


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


def _witness_documents(c: Collection, m: Collection, docs: Path, sample_size: int):
    formats = c.metadata.get("witness_formats")
    prohibited = set()
    source = c.metadata.get("source", {})
    if source.get("kind") == "public-recipe":
        from .corpus import load_recipe
        prohibited = {d["id"] for d in load_recipe(source["recipe"])["documents"]
                      if d["source"] in ("govdocs1", "safedocs")}
    eligible = [doc for doc in m.documents
                if doc not in prohibited and (formats is None or
                    Path(c.documents[doc]).suffix.lstrip(".") in formats)]
    if not eligible:
        raise IndexRefused("collection has no eligible probe witness document")
    longest = max(eligible, key=lambda doc: ((docs / m.documents[doc]).stat().st_size, doc))
    return longest, sorted(set([longest] + sorted(eligible)[:max(0, sample_size - 1)]))


def witness(base_url: str, c: Collection, m: Collection, docs: Path,
            config: IndexConfig) -> dict:
    """Read full live population/content and deterministic first/late sample probes."""
    with httpx.Client(base_url=base_url, timeout=60) as client:
        status = _get(client, "/api/status")
        reasons = _ready_reasons(status, c, config.request.get("mode") == "splade")
        if reasons:
            # Health can precede the worker's resolved enable flags and audits
            # on cached startup. Keep the gate, but retry serving readiness;
            # it is not evidence that the stored index bytes are corrupt.
            raise _readiness_refusal(status, reasons)
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
        longest, samples = _witness_documents(c, m, docs, config.sample_size)
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
            raise TransientIndexRefused(f"index commit not COMPLETE: {meta.get('build_state')}")
        s = readiness.flatten_status(status)
        generation = index_identity._deep_find(status, "activeGenerationId")
        if not isinstance(generation, str) or not generation:
            raise TransientIndexRefused("serving generation unavailable")
        final_status = _get(client, "/api/status")
        final_reasons = _ready_reasons(final_status, c, config.request.get("mode") == "splade")
        if (final_reasons or generation != index_identity._deep_find(final_status, "activeGenerationId")
                or index_identity._deep_find(final_status, "buildingGenerationId") not in (None, "", index_identity._MISSING)):
            raise TransientIndexRefused(f"index changed during witness: {final_reasons}")
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
    # Shared hold on the snapshot the engine runs from; retention never removes it meanwhile.
    _lease: Any = dataclasses.field(default=None, repr=False)

    @property
    def identity(self) -> dict[str, str]:
        return identity_digests(self.components)

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
                    raise self._preparation_failure(f"background preparation exited {code}")
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
            # The checkout must still be the one this snapshot was installed from.
            # A stale distribution is not evidence against an owned cached index.
            if prepared_only and _select_distribution(self.config, self.env, True) != self.env[DISTRIBUTION_ENV]:
                raise InputUnavailable("not ready: stale distribution: the checkout's snapshot changed since preparation")
        except BaseException:
            self.close()
            raise
        failure = None
        try:
            self._backend = _start_serving(self.config, self.data_dir, self.env, port, deadline,
                prepared_only=prepared_only, startup_admission=not prepared_only)
            self.base_url = f"http://127.0.0.1:{port}"
            result = _confirm_ready(self, deadline, external=True)
            if result.ok:
                return self
            failure = "not ready: serving backend not confirmed: " + str(result.failures)
            raise IndexRefused(failure)
        except BaseException:
            try:
                # Stop the writer before moving proven-bad content; transient
                # serving failures leave the entry available for a later run.
                if self._backend:
                    backend.stop_backend(self._backend.proc, data_dir=self.data_dir)
                    self._backend = None
                if failure and self.index_build == "cached":
                    _quarantine(self.root, self.data_dir, self.collection, self.index_key, failure)
            finally:
                self.close()
            raise

    def close(self) -> None:
        try:
            if self._worker:
                # Admission owns, identifies and cleans this worker's whole process tree.
                ready = self._backend is not None or (self.root / "prepared.json").exists()
                (self.root / "stop").touch()
                # Give a ready worker time to stop its writer and attest completion before
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
                if self._worker.poll() != 0:
                    raise self._preparation_failure(f"preparation teardown failed: exit {self._worker.returncode}")
                # Only a successfully reaped admission supervisor can authorize
                # publication. An orphan worker can never publish a failed build.
                if ready:
                    completed = _json(self.root / "completed.json")
                    _check_owner(completed["owner"], self.collection, self.index_key)
                    _write(self.root / "entry.json", completed)
                    distribution.reference(self.env[DISTRIBUTION_ENV], self.root)
            if self._backend:
                backend.stop_backend(self._backend.proc, data_dir=self.data_dir)
                self._backend = None
        finally:
            if not self._lock.closed:
                self._lock.close()
            if self._lease is not None and not self._lease.closed:
                self._lease.close()

    def _preparation_failure(self, fallback: str) -> IndexRefused:
        try:
            failure = _json(self.root / "failure.json")
            reason = failure["reason"]
            if isinstance(reason, str) and reason:
                kind = TransientIndexRefused if failure.get("transient") is True else IndexRefused
                return kind(reason)
        except (OSError, ValueError, KeyError, TypeError):
            pass
        return IndexRefused(f"{fallback}: {(self.root / 'build.log').read_text()[-6000:]}")

    def __enter__(self) -> "IndexHandle":
        return self

    def __exit__(self, *_):
        self.close()


def prepare_index(collection: Collection, config: IndexConfig, *, prepared_only: bool = False) -> IndexHandle:
    if (collection.version != collection.compute_version() or not collection.documents
            or len(collection.documents) > 50000):
        raise IndexRefused("empty or modified collection")
    env = config.environment()
    # Before any lock or engine: resolution/hashing failures never touch a cached entry.
    env[DISTRIBUTION_ENV] = _select_distribution(config, env, prepared_only)
    pin_locations(env)
    try:
        lease = distribution.lease(env[DISTRIBUTION_ENV])
    except distribution.DistributionRefused as e:
        raise InputUnavailable(f"not ready: {e}") from None
    try:
        key, components = compute_index_key(collection, config, env)
        root = _under_cache(config.cache_root) / sha256_text(collection.name) / key
        data = _under_cache(config.data_dir) if config.data_dir else root / "data"
        if prepared_only and not (root / "entry.json").is_file():
            raise IndexRefused("not ready: no complete prepared index; run quality prepare outside the scoring window")
        root.mkdir(parents=True, exist_ok=True)
        # Lock the actual data path, including explicit paths shared by different keys.
        data.parent.mkdir(parents=True, exist_ok=True)
        lock = (data.parent / (data.name + ".quality.lock")).open("a+")
    except BaseException:
        lease.close()
        raise
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
                backend.require_prepared_data(data)
            # Retention keeps the snapshot of every adopted entry.
            distribution.reference(env[DISTRIBUTION_ENV], root)
        else:
            # Only our exact owner's interrupted data may be replaced.
            if data.exists():
                shutil.rmtree(data)
            data.mkdir()
            _write(owner_path, _population_owner(collection, key))
        handle = IndexHandle(collection, config, key, root, data,
                             "cached" if adopted else "fresh", components, env, lock, _lease=lease)
        if adopted:
            _materialize(collection, config, root)  # rehash the source files before boot
            if prepared_only:
                return handle
        write_collection(collection, root / "collection")
        (root / "worker.json").unlink(missing_ok=True)
        (root / "engine-ready").unlink(missing_ok=True)
        (root / "prepared.json").unlink(missing_ok=True)
        (root / "completed.json").unlink(missing_ok=True)
        (root / "failure.json").unlink(missing_ok=True)
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
            if not isinstance(e, (BlockingIOError, TransientIndexRefused, httpx.HTTPError)):
                _quarantine(root, data, collection, key, f"{type(e).__name__}: {e}")
        finally:
            lock.close()
            lease.close()
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
    # An unresolvable or changed input refuses at once and is not evidence against the
    # entry: InputUnavailable propagates instead of becoming a (retried) failure.
    key, _ = compute_index_key(handle.collection, handle.config, handle.env)
    if key != handle.index_key:
        raise InputUnavailable("not ready: index-shaping inputs changed since preparation")
    try:
        if not handle.base_url or not handle._backend or handle._backend.proc.poll() is not None:
            raise TransientIndexRefused("no live serving backend")
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
    except (httpx.HTTPError, TransientIndexRefused) as e:
        failures.append(f"serving.error: {type(e).__name__}: {e}")
        checks["serving"] = {"ok": False, "transient": True}
    except Exception as e:
        failures.append(f"{type(e).__name__}: {e}")
    if failures:
        handle.index_state["confirmation_failures"] = failures
    return index_identity.ConfirmResult(not failures, failures, checks)


def _confirm_ready(handle: IndexHandle, deadline: float, *, entry: dict | None = None,
                   external: bool = False) -> index_identity.ConfirmResult:
    """Retry serving readiness until the deadline; settle content/identity failures immediately."""
    last = None
    while time.monotonic() < deadline:
        result = confirm(handle) if external else _confirm(handle, entry)
        if result.ok:
            return result
        # Every failure must be classified as transient. A simultaneous content
        # mismatch is still evidence against the cached entry.
        transient = result.transient_failures
        if not isinstance(transient, list) or set(result.failures) - set(transient):
            return result
        last = result.failures
        time.sleep(min(2, max(0, deadline-time.monotonic())))
    raise TransientIndexRefused(f"not ready, transient: serving confirmation deadline expired: {last}")


def _start_serving(config: IndexConfig, data: Path, env: dict, port: int,
                   deadline: float, *, prepared_only: bool, startup_admission: bool):
    last = None
    for attempt in range(3):
        if time.monotonic() >= deadline:
            break
        try:
            return backend.start_backend(repo_root=config.repo_root, data_dir=data, port=port,
                env_overrides=_launch_env(config, data, env),
                distribution=distribution.path(env[DISTRIBUTION_ENV]),
                health_timeout_sec=max(.001, min(180, deadline-time.monotonic())),
                startup_admission=startup_admission, prepared_only=prepared_only)
        except Exception as e:
            # A start/health/admission failure proves nothing about stored bytes.
            # start_backend tears down each unsuccessful engine before raising.
            last = f"{type(e).__name__}: {e}"
            if attempt < 2:
                time.sleep(min(2**attempt, max(0, deadline-time.monotonic())))
    raise TransientIndexRefused(f"not ready, transient: engine startup failed: {last}")


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
    invalid_content = False
    lease = None
    try:
        # The engine runs from the snapshot the key was computed over, never the build folder.
        try:
            lease = distribution.lease(spec["env"][DISTRIBUTION_ENV])
        except distribution.DistributionRefused as e:
            raise InputUnavailable(f"not ready: {e}") from None
        deadline = time.monotonic() + cfg.timeout_sec
        info = _start_serving(cfg, data, spec["env"], port, deadline,
                              startup_admission=False, prepared_only=adopted)
        # Preparation is outside scoring. A queued Gradle slot must not consume
        # the index-readiness timeout; scoring's wait() keeps its single deadline.
        deadline = time.monotonic() + cfg.timeout_sec
        url = f"http://127.0.0.1:{port}"
        _write(root / "engine-ready", {"url": url})
        if not adopted:
            add_watched_root(url, root / "documents")
        last = None
        while not adopted and time.monotonic() < deadline:
            try:
                w = witness(url, c, m, root / "documents", cfg)
                break
            except (IndexRefused, httpx.HTTPError) as e:
                last = str(e)
            if info.proc.poll() is not None:
                raise IndexRefused(f"build engine exited: {info.proc.returncode}")
            time.sleep(2)
        else:
            if not adopted:
                raise TransientIndexRefused(f"not ready, transient: build readiness deadline: {last}")
        key, components = compute_index_key(c, cfg, spec["env"])
        if key != spec["key"]:
            # Not evidence against an adopted entry; a fresh build is never published.
            raise InputUnavailable("not ready: index-shaping inputs changed during build")
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
        result = _confirm_ready(handle, deadline, entry=entry)
        if not result.ok:
            invalid_content = True
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
                raise TransientIndexRefused("not ready, transient: preparation engine exited before close")
            time.sleep(.05)
        # Close the writer before attesting completion. The caller publishes only
        # after the admission supervisor confirms successful teardown by exiting 0.
        backend.stop_backend(info.proc, data_dir=data)
        info = None
        _write(root / "completed.json", entry)
    except BaseException as e:
        if info:
            backend.stop_backend(info.proc, data_dir=data)
            info = None
        if adopted and invalid_content:
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
        try:
            _build(args.root)
        except IndexRefused as error:
            _write(args.root / "failure.json", {"reason": str(error),
                "transient": isinstance(error, TransientIndexRefused)})
            print(str(error), file=sys.stderr, flush=True)
            raise SystemExit(1) from None
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
