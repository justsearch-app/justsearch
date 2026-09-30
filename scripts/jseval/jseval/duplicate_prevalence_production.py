"""Strict production-extracted adapter for duplicate-prevalence analysis.

The Head remains a transport bridge: parent IDs and stored content come from
Worker RPCs.  Private paths, extracted text, and the run-local alias keys live
only in this process; the command persists the aggregate analyzer artifact.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import math
import os
import re
import secrets
import time
from dataclasses import dataclass
from pathlib import Path
from types import MappingProxyType
from typing import Any, Callable, Mapping, Protocol, Sequence
from urllib.parse import urlsplit

import httpx

from . import duplicate_prevalence as prevalence
from . import duplicate_prevalence_enron as request_schema
from . import result_identity
from .raw_corpus_manifest import (
    RawCorpusManifestError,
    build_raw_manifest,
    effective_admission_policy,
    validate_raw_manifest,
)
from . import ingest as ingest_module
from . import readiness
from .readiness import flatten_status


SOURCE_KIND = prevalence.PRODUCTION_EXTRACTED
MAX_DOCUMENTS = 50_000
PREVIEW_PAGE_CHARS = 200_000
COHORT_SETTLE_TIMEOUT_SECONDS = 30.0
COHORT_SETTLE_POLL_SECONDS = 0.5
SESSION_TOKEN_ENV = "JUSTSEARCH_SESSION_TOKEN"
_SOURCE_REQUIRED_FIELDS = frozenset({"kind", "raw_root", "base_url"})
_SOURCE_OPTIONAL_FIELDS = frozenset({"terminal_exclusions"})
_TERMINAL_EXCLUSION_FIELDS = frozenset(
    {"relative_path", "sha256", "expected_state", "expected_error_message", "reason"}
)
_TERMINAL_EXCLUSION_REASONS = frozenset({"corrupt-or-unsupported-parser-input"})
_LOOPBACK_HOSTS = frozenset({"127.0.0.1", "::1", "localhost"})
_SAFE_BUILD_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._@:+-]{0,255}\Z")
_SHA256_RE = re.compile(r"[0-9a-f]{64}\Z")
_SUCCESS_STATUSES = frozenset({"SUCCESS_FULL", "SUCCESS_PARTIAL", "SUCCESS_EMPTY"})
_FAILED_STATUSES = frozenset({"FAILED", "TIMED_OUT", "BUDGET_EXCEEDED"})
_STABLE_STATUS_FIELDS = (
    "buildStamp",
    "indexedDocuments",
    "commitCount",
    "activeGenerationId",
    "servingSearchGenerationId",
    "servingIngestGenerationId",
)
_STABLE_DEBUG_FIELDS = (
    "active_doc_count",
    "doc_count",
    "last_commit_timestamp",
    "serving_search_generation_id",
    "serving_ingest_generation_id",
)


class ProductionDuplicatePrevalenceError(ValueError):
    """The live production snapshot cannot be reconciled exactly."""


@dataclass(frozen=True)
class ProductionSourceSpec:
    raw_root: Path
    base_url: str
    terminal_exclusions: tuple["TerminalExclusionSpec", ...] = ()


@dataclass(frozen=True)
class TerminalExclusionSpec:
    relative_path: str
    sha256: str
    expected_state: str
    expected_error_message: str
    reason: str


@dataclass(frozen=True)
class ProductionAnalysisRequest:
    source: ProductionSourceSpec
    config: prevalence.AnalysisConfig


@dataclass(frozen=True, repr=False)
class ProductionSnapshot:
    """Private in-process capture plus its privacy-safe committed identity."""

    observations: tuple[prevalence.DocumentObservation, ...]
    aliases_by_opaque_id: Mapping[str, tuple[str, ...]]
    corpus_identity: Mapping[str, Any]
    extraction_identity: Mapping[str, Any]
    result_alias_commitment_key: bytes
    result_mapping_signing_key: bytes
    terminal_exclusions: Mapping[
        str, tuple[TerminalExclusionSpec, Path, Mapping[str, Any]]
    ]
    raw_root: Path
    raw_manifest: Mapping[str, Any]
    raw_manifest_digest: str
    base_url: str
    health_identity: tuple[Any, ...]
    status_identity: tuple[Any, ...]
    debug_identity: tuple[Any, ...]
    failed_jobs_identity: tuple[tuple[Any, ...], ...]


class SnapshotClient(Protocol):
    def health(self) -> Mapping[str, Any]: ...

    def status(self) -> Mapping[str, Any]: ...

    def debug_state(self) -> Mapping[str, Any]: ...

    def list_document_ids(self, offset: int, limit: int) -> Mapping[str, Any]: ...

    def failed_jobs(self, limit: int) -> Mapping[str, Any]: ...

    def preview(self, doc_id: str, offset_chars: int, max_chars: int) -> Mapping[str, Any]: ...


class ProductionHttpClient:
    """Thin HTTP transport for the existing Head-to-Worker bridge."""

    def __init__(
        self,
        base_url: str,
        *,
        session_token: str | None,
        timeout_seconds: float = 30.0,
    ) -> None:
        headers = (
            {"X-JustSearch-Session": session_token}
            if session_token is not None and session_token.strip()
            else {}
        )
        self._client = httpx.Client(
            base_url=base_url,
            headers=headers,
            timeout=timeout_seconds,
        )

    @staticmethod
    def _json(response: httpx.Response, endpoint: str) -> Mapping[str, Any]:
        try:
            response.raise_for_status()
        except httpx.HTTPStatusError as exc:
            raise ProductionDuplicatePrevalenceError(
                f"{endpoint} returned HTTP {response.status_code}"
            ) from exc
        value = response.json()
        if not isinstance(value, Mapping):
            raise ProductionDuplicatePrevalenceError(f"{endpoint} did not return a JSON object")
        return value

    def health(self) -> Mapping[str, Any]:
        return self._json(self._client.get("/api/health"), "/api/health")

    def status(self) -> Mapping[str, Any]:
        return self._json(self._client.get("/api/status"), "/api/status")

    def debug_state(self) -> Mapping[str, Any]:
        return self._json(self._client.get("/api/debug/state"), "/api/debug/state")

    def list_document_ids(self, offset: int, limit: int) -> Mapping[str, Any]:
        return self._json(
            self._client.post(
                "/api/debug/eval/document-ids",
                json={"offset": offset, "limit": limit},
            ),
            "/api/debug/eval/document-ids",
        )

    def failed_jobs(self, limit: int) -> Mapping[str, Any]:
        return self._json(
            self._client.get("/api/indexing-jobs/failed", params={"limit": limit}),
            "/api/indexing-jobs/failed",
        )

    def preview(self, doc_id: str, offset_chars: int, max_chars: int) -> Mapping[str, Any]:
        return self._json(
            self._client.get(
                "/api/preview",
                params={
                    "docId": doc_id,
                    "offsetChars": offset_chars,
                    "maxChars": max_chars,
                },
            ),
            "/api/preview",
        )

    def close(self) -> None:
        self._client.close()


def _canonical_sha256(value: object) -> str:
    try:
        encoded = json.dumps(
            value,
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=False,
            allow_nan=False,
        ).encode("utf-8")
    except (TypeError, ValueError, UnicodeEncodeError) as exc:
        raise ProductionDuplicatePrevalenceError("snapshot metadata is not canonical UTF-8 JSON") from exc
    return hashlib.sha256(encoded).hexdigest()


def _checked_base_url(value: object) -> str:
    if not isinstance(value, str) or not value or "\x00" in value:
        raise ProductionDuplicatePrevalenceError("source.base_url must be a non-empty loopback URL")
    parsed = urlsplit(value)
    try:
        port = parsed.port
    except ValueError as exc:
        raise ProductionDuplicatePrevalenceError("source.base_url has an invalid port") from exc
    if (
        parsed.scheme != "http"
        or parsed.hostname not in _LOOPBACK_HOSTS
        or port is None
        or parsed.username is not None
        or parsed.password is not None
        or parsed.path not in {"", "/"}
        or parsed.query
        or parsed.fragment
    ):
        raise ProductionDuplicatePrevalenceError(
            "source.base_url must be an explicit-port HTTP loopback origin"
        )
    return value.rstrip("/")


def _checked_source_spec(source: object) -> ProductionSourceSpec:
    """Validate the source contract for file-loaded and programmatic requests alike."""

    if not isinstance(source, ProductionSourceSpec):
        raise ProductionDuplicatePrevalenceError(
            "request.source must be a ProductionSourceSpec"
        )
    if not isinstance(source.raw_root, Path):
        raise ProductionDuplicatePrevalenceError("source.raw_root must be a local Path")
    try:
        str(source.raw_root).encode("utf-8")
    except UnicodeEncodeError as exc:
        raise ProductionDuplicatePrevalenceError(
            "source.raw_root must be valid UTF-8"
        ) from exc
    if not isinstance(source.terminal_exclusions, tuple):
        raise ProductionDuplicatePrevalenceError(
            "source.terminal_exclusions must be a tuple"
        )

    checked_exclusions: list[TerminalExclusionSpec] = []
    seen_paths: set[str] = set()
    for item in source.terminal_exclusions:
        if not isinstance(item, TerminalExclusionSpec):
            raise ProductionDuplicatePrevalenceError(
                "each terminal exclusion must be a TerminalExclusionSpec"
            )
        relative_path = item.relative_path
        expected_error = item.expected_error_message
        if (
            not isinstance(relative_path, str)
            or not relative_path
            or "\x00" in relative_path
            or "\\" in relative_path
            or relative_path.startswith("/")
            or any(part in {"", ".", ".."} for part in relative_path.split("/"))
        ):
            raise ProductionDuplicatePrevalenceError(
                "terminal exclusion relative_path must be canonical and relative"
            )
        try:
            relative_path.encode("utf-8")
        except UnicodeEncodeError as exc:
            raise ProductionDuplicatePrevalenceError(
                "terminal exclusion relative_path must be valid UTF-8"
            ) from exc
        if relative_path in seen_paths:
            raise ProductionDuplicatePrevalenceError(
                "terminal exclusion paths must be unique"
            )
        seen_paths.add(relative_path)
        if not isinstance(item.sha256, str) or _SHA256_RE.fullmatch(item.sha256) is None:
            raise ProductionDuplicatePrevalenceError(
                "terminal exclusion sha256 must be lowercase SHA-256"
            )
        if item.expected_state != "FAILED":
            raise ProductionDuplicatePrevalenceError(
                "terminal exclusions may acknowledge only FAILED source jobs"
            )
        if (
            not isinstance(expected_error, str)
            or not expected_error
            or len(expected_error) > 512
            or "\x00" in expected_error
        ):
            raise ProductionDuplicatePrevalenceError(
                "terminal exclusion expected_error_message must be bounded non-empty text"
            )
        try:
            expected_error.encode("utf-8")
        except UnicodeEncodeError as exc:
            raise ProductionDuplicatePrevalenceError(
                "terminal exclusion expected_error_message must be valid UTF-8"
            ) from exc
        if (
            not isinstance(item.reason, str)
            or item.reason not in _TERMINAL_EXCLUSION_REASONS
        ):
            raise ProductionDuplicatePrevalenceError(
                "terminal exclusion reason is not a supported aggregate category"
            )
        checked_exclusions.append(item)

    return ProductionSourceSpec(
        source.raw_root,
        _checked_base_url(source.base_url),
        tuple(checked_exclusions),
    )


def load_input_spec(path: Path | str) -> ProductionAnalysisRequest:
    """Load the shared request envelope and validate the production source block."""

    try:
        top = request_schema.load_input_payload(path)
        if top["schema"] != request_schema.INPUT_SCHEMA:
            raise ProductionDuplicatePrevalenceError(
                f"input spec schema must be {request_schema.INPUT_SCHEMA!r}"
            )
        source = top["source"]
        source_fields = set(source) if isinstance(source, Mapping) else set()
        if (
            not isinstance(source, Mapping)
            or not _SOURCE_REQUIRED_FIELDS <= source_fields
            or not source_fields <= _SOURCE_REQUIRED_FIELDS | _SOURCE_OPTIONAL_FIELDS
        ):
            raise ProductionDuplicatePrevalenceError(
                "source must contain kind/raw_root/base_url and only the supported optional fields"
            )
        if source["kind"] != SOURCE_KIND:
            raise ProductionDuplicatePrevalenceError(
                f"production adapter requires source.kind {SOURCE_KIND!r}"
            )
        raw_root = source["raw_root"]
        if not isinstance(raw_root, str) or not raw_root or "\x00" in raw_root:
            raise ProductionDuplicatePrevalenceError("source.raw_root must be a non-empty local path")
        raw_root.encode("utf-8")
        raw_exclusions = source.get("terminal_exclusions", [])
        if not isinstance(raw_exclusions, list):
            raise ProductionDuplicatePrevalenceError("source.terminal_exclusions must be an array")
        terminal_exclusions: list[TerminalExclusionSpec] = []
        for item in raw_exclusions:
            if not isinstance(item, Mapping) or set(item) != _TERMINAL_EXCLUSION_FIELDS:
                raise ProductionDuplicatePrevalenceError(
                    "each terminal exclusion must contain exactly relative_path, sha256, "
                    "expected_state, expected_error_message, and reason"
                )
            terminal_exclusions.append(
                TerminalExclusionSpec(
                    item["relative_path"],
                    item["sha256"],
                    item["expected_state"],
                    item["expected_error_message"],
                    item["reason"],
                )
            )
        config = request_schema.parse_analysis_config(top["analysis"])
    except request_schema.EnronDuplicatePrevalenceError as exc:
        raise ProductionDuplicatePrevalenceError(str(exc)) from exc
    except UnicodeEncodeError as exc:
        raise ProductionDuplicatePrevalenceError("source.raw_root must be valid UTF-8") from exc
    return ProductionAnalysisRequest(
        _checked_source_spec(
            ProductionSourceSpec(
                Path(raw_root),
                source["base_url"],
                tuple(terminal_exclusions),
            )
        ),
        config,
    )


def _require_int(mapping: Mapping[str, Any], key: str) -> int:
    value = mapping.get(key)
    if isinstance(value, bool) or not isinstance(value, int):
        raise ProductionDuplicatePrevalenceError(f"live state is missing integer field {key}")
    return value


def _require_str(mapping: Mapping[str, Any], key: str) -> str:
    value = mapping.get(key)
    if not isinstance(value, str) or not value:
        raise ProductionDuplicatePrevalenceError(f"live state is missing string field {key}")
    return value


def _validate_live_state(
    client: SnapshotClient,
    *,
    expected_indexed_count: int,
    expected_failed_count: int,
) -> tuple[tuple[Any, ...], dict[str, Any], dict[str, Any]]:
    health = client.health()
    lifecycle = health.get("lifecycle")
    components = health.get("components")
    api = components.get("api") if isinstance(components, Mapping) else None
    index = components.get("index") if isinstance(components, Mapping) else None
    generative = components.get("generative") if isinstance(components, Mapping) else None
    lifecycle_ready = (
        isinstance(lifecycle, Mapping)
        and lifecycle.get("state") == "LIFECYCLE_STATE_READY"
    )
    inference_offline = (
        isinstance(lifecycle, Mapping)
        and lifecycle.get("state") == "LIFECYCLE_STATE_DEGRADED"
        and lifecycle.get("reason_code") == "inference.offline"
        and isinstance(generative, Mapping)
        and generative.get("state") == "UNAVAILABLE"
        and generative.get("reason_code") == "inference.offline"
    )
    if (
        not isinstance(lifecycle, Mapping)
        or not (lifecycle_ready or inference_offline)
        or not isinstance(api, Mapping)
        or api.get("state") != "READY"
        or not isinstance(index, Mapping)
        or index.get("state") != "READY"
    ):
        raise ProductionDuplicatePrevalenceError(
            "/api/health does not report ready API/index components with an "
            "allowed inference disposition"
        )

    raw_status = client.status()
    status = flatten_status(dict(raw_status))
    zero_fields = (
        "pendingJobs",
        "pendingJobsCount",
        "processingJobsCount",
        "pendingReadyJobsCount",
        "pendingBackoffJobsCount",
        "buildingIndexedDocuments",
        "switchBufferDepth",
        "writerQueueDepth",
        "writerPendingDocs",
        "pendingVduCount",
    )
    expected_index_state = "ERROR" if expected_failed_count else "IDLE"
    if status.get("indexAvailable") is not True or status.get("indexState") != expected_index_state:
        raise ProductionDuplicatePrevalenceError("/api/status does not report an idle available index")
    if any(_require_int(status, field) != 0 for field in zero_fields):
        raise ProductionDuplicatePrevalenceError("/api/status reports pending index work")
    if _require_int(status, "failedJobs") != expected_failed_count:
        raise ProductionDuplicatePrevalenceError(
            "/api/status failed-job count does not match declared terminal exclusions"
        )
    if status.get("queueDbHealthy") is not True or status.get("migrationState") != "IDLE":
        raise ProductionDuplicatePrevalenceError("/api/status reports an unhealthy queue or migration")
    if _require_int(status, "indexedDocuments") != expected_indexed_count:
        raise ProductionDuplicatePrevalenceError(
            "indexed document count does not match the analyzable source cohort"
        )
    search_generation = _require_str(status, "servingSearchGenerationId")
    if search_generation != _require_str(status, "servingIngestGenerationId"):
        raise ProductionDuplicatePrevalenceError("search and ingest generations do not match")
    build = _require_str(status, "buildStamp")
    if _SAFE_BUILD_RE.fullmatch(build) is None:
        raise ProductionDuplicatePrevalenceError("Worker build stamp is not a safe snapshot identifier")
    if status.get("vduProcessing") not in {None, False}:
        raise ProductionDuplicatePrevalenceError("visual extraction is still processing")
    _require_int(status, "commitCount")
    _require_str(status, "activeGenerationId")

    raw_debug = client.debug_state()
    worker_debug = raw_debug.get("worker") if isinstance(raw_debug, Mapping) else None
    debug = dict(worker_debug if isinstance(worker_debug, Mapping) else raw_debug)
    debug_zero_fields = (
        "building_doc_count",
        "pending_backoff_jobs_count",
        "pending_jobs_count",
        "pending_ready_jobs_count",
        "processing_jobs_count",
        "queue_depth",
        "switch_buffer_depth",
    )
    if expected_failed_count == 0 and debug.get("is_healthy") is not True:
        raise ProductionDuplicatePrevalenceError("/api/debug/state does not report a healthy index")
    if expected_failed_count and not isinstance(debug.get("is_healthy"), bool):
        raise ProductionDuplicatePrevalenceError("/api/debug/state lacks a health disposition")
    if any(_require_int(debug, field) != 0 for field in debug_zero_fields):
        raise ProductionDuplicatePrevalenceError("/api/debug/state reports pending index work")
    if (
        _require_int(debug, "doc_count") != expected_indexed_count
        or _require_int(debug, "active_doc_count") != expected_indexed_count
    ):
        raise ProductionDuplicatePrevalenceError(
            "debug document count does not match the analyzable source cohort"
        )
    debug_search = _require_str(debug, "serving_search_generation_id")
    if debug_search != _require_str(debug, "serving_ingest_generation_id"):
        raise ProductionDuplicatePrevalenceError("debug search and ingest generations do not match")
    if debug_search != search_generation:
        raise ProductionDuplicatePrevalenceError("status and debug generation identities disagree")
    _require_int(debug, "last_commit_timestamp")
    return (
        lifecycle.get("state"),
        lifecycle.get("reason_code"),
        api.get("state"),
        api.get("reason_code"),
        index.get("state"),
        index.get("reason_code"),
        generative.get("state") if isinstance(generative, Mapping) else None,
        generative.get("reason_code") if isinstance(generative, Mapping) else None,
    ), status, debug


def _manifest_capture_inputs(
    request: ProductionAnalysisRequest,
) -> tuple[Any, Path, dict[str, Mapping[str, Any]], dict[str, tuple[TerminalExclusionSpec, Path, Mapping[str, Any]]]]:
    """Resolve the strict source manifest and declared terminal dispositions."""

    try:
        raw_identity = build_raw_manifest(request.source.raw_root)
    except RawCorpusManifestError as exc:
        raise ProductionDuplicatePrevalenceError("raw corpus validation failed") from exc
    if raw_identity.file_count > MAX_DOCUMENTS:
        raise ProductionDuplicatePrevalenceError(
            f"raw corpus exceeds the {MAX_DOCUMENTS}-document snapshot ceiling"
        )
    try:
        root = request.source.raw_root.resolve(strict=True)
    except OSError as exc:
        raise ProductionDuplicatePrevalenceError(
            "raw corpus changed after manifest validation"
        ) from exc
    expected = _expected_documents(root, raw_identity.manifest["files"])
    declared = _declared_exclusions(request.source, root=root, expected=expected)
    return raw_identity, root, expected, declared


def _required_production_readiness_reasons(
    status: Mapping[str, Any],
    *,
    expected_indexed_count: int,
    expected_failed_count: int,
) -> list[str]:
    """Extend the canonical pipeline predicate with production snapshot gates.

    The readiness module owns ordinary enrichment semantics.  This adapter adds
    only the extra production guarantees: VDU completion, writer quiescence,
    exact failed-job count, and an explicit index disposition.  Missing fields
    remain blocking reasons so an older or malformed status cannot look ready.
    """

    integer_fields = (
        "pendingVduCount",
        "visualTextNeededCount",
        "visualEnrichmentNeededCount",
        "chunkDocCount",
        "chunkEmbeddingPendingCount",
        "chunkEmbeddingFailedCount",
        "chunkSpladePendingCount",
        "chunkSpladeCompletedCount",
        "embeddingPendingCount",
        "embeddingFailedCount",
        "spladePendingCount",
        "spladeFailedCount",
        "pendingNerCount",
        "completedNerCount",
        "failedNerCount",
        "writerQueueDepth",
        "writerPendingDocs",
    )
    numeric_fields = (
        "embeddingCoveragePercent",
        "spladeCoveragePercent",
        "chunkVectorCoveragePercent",
        "chunkSpladeCoveragePercent",
    )
    for field in integer_fields:
        if field in status and (isinstance(status[field], bool) or not isinstance(status[field], int)
                                or status[field] < 0):
            raise ProductionDuplicatePrevalenceError(
                f"production readiness field {field} has an invalid schema type"
            )
    for field in numeric_fields:
        if field in status and (isinstance(status[field], bool) or not isinstance(status[field], (int, float))
                                or not math.isfinite(status[field]) or not 0 <= status[field] <= 100):
            raise ProductionDuplicatePrevalenceError(
                f"production readiness field {field} has an invalid schema type"
            )
    if "vduProcessing" in status and not isinstance(status["vduProcessing"], bool):
        raise ProductionDuplicatePrevalenceError(
            "production readiness field vduProcessing has an invalid schema type"
        )
    if "chunkSpladeEnabled" in status and not isinstance(status["chunkSpladeEnabled"], bool):
        raise ProductionDuplicatePrevalenceError(
            "production readiness field chunkSpladeEnabled has an invalid schema type"
        )

    reasons = list(
        readiness._check_pipeline_complete_conditions(  # noqa: SLF001 - canonical predicate
            dict(status), expected_indexed_count
        )
    )
    expected_index_state = "ERROR" if expected_failed_count else "IDLE"
    if status.get("indexState") == expected_index_state:
        reasons = [reason for reason in reasons if reason != "index_not_idle"]
    elif "index_not_idle" not in reasons:
        reasons.append("index_not_idle")

    required = (
        "pendingVduCount",
        "vduProcessing",
        "visualTextNeededCount",
        "visualEnrichmentNeededCount",
        "embeddingCoveragePercent",
        "embeddingPendingCount",
        "embeddingFailedCount",
        "spladeCoveragePercent",
        "spladePendingCount",
        "spladeFailedCount",
        "chunkDocCount",
        "chunkVectorCoveragePercent",
        "chunkEmbeddingPendingCount",
        "chunkEmbeddingFailedCount",
        "chunkSpladeEnabled",
        "chunkSpladePendingCount",
        "chunkSpladeCoveragePercent",
        "chunkSpladeCompletedCount",
        "pendingNerCount",
        "completedNerCount",
        "failedNerCount",
        "writerQueueDepth",
        "writerPendingDocs",
    )
    for field in required:
        if field not in status:
            reasons.append(f"missing_production_readiness_field:{field}")

    failed_jobs = status.get("failedJobs")
    if isinstance(failed_jobs, bool) or not isinstance(failed_jobs, int):
        reasons.append("failed_job_count_unavailable")
    elif failed_jobs > expected_failed_count:
        raise ProductionDuplicatePrevalenceError(
            "failed-job count exceeds declared terminal exclusions"
        )
    elif failed_jobs != expected_failed_count:
        reasons.append(
            f"failed_job_count_mismatch({failed_jobs}/{expected_failed_count})"
        )

    if status.get("pendingVduCount") not in {0} or status.get("vduProcessing") is not False:
        reasons.append("vdu_not_quiescent")
    for field in ("visualTextNeededCount", "visualEnrichmentNeededCount"):
        value = status.get(field)
        if isinstance(value, bool) or not isinstance(value, int) or value != 0:
            reasons.append(f"{field}_not_zero")
    for field in ("embeddingPendingCount", "embeddingFailedCount"):
        value = status.get(field)
        if isinstance(value, bool) or not isinstance(value, int) or value != 0:
            reasons.append(f"{field}_not_zero")
    for field in ("spladePendingCount", "spladeFailedCount"):
        value = status.get(field)
        if isinstance(value, bool) or not isinstance(value, int) or value != 0:
            reasons.append(f"{field}_not_zero")
    failed_ner = status.get("failedNerCount")
    if isinstance(failed_ner, bool) or not isinstance(failed_ner, int) or failed_ner != 0:
        reasons.append("failedNerCount_not_zero")
    for field in ("writerQueueDepth", "writerPendingDocs"):
        value = status.get(field)
        if isinstance(value, bool) or not isinstance(value, int) or value != 0:
            reasons.append(f"{field}_not_zero")
    if status.get("embeddingEnabled", True):
        for field in ("chunkEmbeddingPendingCount", "chunkEmbeddingFailedCount"):
            if status.get(field) != 0:
                reasons.append(f"{field}_not_zero")
    if status.get("chunkSpladeEnabled") and status.get("chunkSpladePendingCount") != 0:
        reasons.append("chunkSpladePendingCount_not_zero")
    if status.get("chunkSpladeEnabled") and (
        status.get("chunkSpladeCompletedCount") != status.get("chunkDocCount")
        or (status.get("chunkDocCount", 0) > 0 and status.get("chunkSpladeCoveragePercent") != 100.0)
    ):
        reasons.append("chunk_splade_not_fully_complete")
    return reasons


def _validate_timeout(timeout_seconds: float) -> float:
    if isinstance(timeout_seconds, bool) or not isinstance(timeout_seconds, (int, float)):
        raise ProductionDuplicatePrevalenceError("wait timeout must be a finite positive number")
    timeout = float(timeout_seconds)
    if not math.isfinite(timeout) or timeout <= 0:
        raise ProductionDuplicatePrevalenceError("wait timeout must be a finite positive number")
    return timeout


def wait_for_snapshot_ready(
    request: ProductionAnalysisRequest,
    *,
    timeout_seconds: float,
    on_snapshot: Callable[[float, dict[str, Any]], None] | None = None,
) -> readiness.ReadinessResult:
    """Wait for a production snapshot's VDU and enrichment state to quiesce."""

    checked_request = ProductionAnalysisRequest(_checked_source_spec(request.source), request.config)
    timeout = _validate_timeout(timeout_seconds)
    _raw_identity, _root, expected, declared = _manifest_capture_inputs(checked_request)
    expected_count = len(expected) - len(declared)

    poll_interval = min(2.0, max(0.01, timeout / 20.0))
    result = readiness._poll_until_stable(  # noqa: SLF001 - reuse canonical poll engine
        checked_request.source.base_url,
        lambda status: _required_production_readiness_reasons(
            status,
            expected_indexed_count=expected_count,
            expected_failed_count=len(declared),
        ),
        timeout,
        poll_interval,
        2,
        on_snapshot=on_snapshot,
        emit_stage_completions=False,
    )
    if not result.passed:
        reasons = ", ".join(result.failure_reasons[:3]) or "readiness predicate did not pass"
        if any(reason.startswith(("backend_unreachable", "backend_process_died"))
               for reason in result.failure_reasons):
            raise ProductionDuplicatePrevalenceError(
                f"production snapshot readiness failed before its {timeout:g}s deadline: {reasons}"
            )
        raise ProductionDuplicatePrevalenceError(
            f"production snapshot readiness timed out after {timeout:g}s: {reasons}"
        )
    return result


def ingest_and_wait_for_snapshot(
    request: ProductionAnalysisRequest,
    *,
    timeout_seconds: float,
    on_snapshot: Callable[[float, dict[str, Any]], None] | None = None,
) -> readiness.ReadinessResult:
    """Explicitly ingest the raw root and wait for strict production readiness.

    Reject unrelated existing identities before registration. A matching partial
    corpus may resume; complete source/index reconciliation remains the final
    :func:`capture_snapshot` identity and provenance gate after readiness.
    """

    checked_request = ProductionAnalysisRequest(_checked_source_spec(request.source), request.config)
    _validate_timeout(timeout_seconds)
    _raw_identity, root, expected, declared = _manifest_capture_inputs(checked_request)
    deadline = time.monotonic() + timeout_seconds
    session_token = os.environ.get(SESSION_TOKEN_ENV)
    try:
        inspection = ProductionHttpClient(
            checked_request.source.base_url,
            session_token=session_token,
            timeout_seconds=min(30.0, _validate_timeout(deadline - time.monotonic())),
        )
        try:
            _validate_pre_ingest_cohort(inspection, set(expected) - set(declared))
        finally:
            inspection.close()
        ingest_module.add_watched_root(
            checked_request.source.base_url, root,
            timeout_sec=_validate_timeout(deadline - time.monotonic()),
            session_token=session_token,
        )
        return wait_for_snapshot_ready(
            checked_request,
            timeout_seconds=_validate_timeout(deadline - time.monotonic()),
            on_snapshot=on_snapshot,
        )
    except httpx.HTTPError as exc:
        raise ProductionDuplicatePrevalenceError("production ingest HTTP request failed") from exc


def prepare_request(
    request: ProductionAnalysisRequest,
    *,
    ingest: bool = False,
    timeout_seconds: float = 0.0,
    on_snapshot: Callable[[float, dict[str, Any]], None] | None = None,
) -> ProductionAnalysisRequest:
    """Validate a request and optionally perform its explicit production ingest."""

    checked_request = ProductionAnalysisRequest(_checked_source_spec(request.source), request.config)
    if ingest:
        ingest_and_wait_for_snapshot(
            checked_request,
            timeout_seconds=timeout_seconds,
            on_snapshot=on_snapshot,
        )
    elif timeout_seconds not in (0, 0.0):
        wait_for_snapshot_ready(
            checked_request,
            timeout_seconds=timeout_seconds,
            on_snapshot=on_snapshot,
        )
    return checked_request


def _stable_projection(mapping: Mapping[str, Any], fields: Sequence[str]) -> tuple[Any, ...]:
    return tuple(mapping.get(field) for field in fields)


def _worker_path_key(value: str) -> str:
    normalized = os.path.normpath(value.replace("/", os.sep))
    # Match PathNormalizer.normalizePath's Locale.ROOT lowercase contract. Python casefold is
    # intentionally stronger (for example, it expands sharp-S to "ss") and would therefore hash
    # a different failed-job identity for valid Unicode Windows paths.
    return normalized.lower() if os.name == "nt" else normalized


def _expected_documents(root: Path, files: Sequence[Mapping[str, Any]]) -> dict[str, Mapping[str, Any]]:
    expected: dict[str, Mapping[str, Any]] = {}
    for row in files:
        try:
            absolute = root.joinpath(*str(row["path"]).split("/")).resolve(strict=True)
        except OSError as exc:
            raise ProductionDuplicatePrevalenceError(
                "raw corpus changed while resolving manifest identities"
            ) from exc
        key = _worker_path_key(str(absolute))
        if key in expected:
            raise ProductionDuplicatePrevalenceError("raw manifest paths collide after Worker normalization")
        expected[key] = row
    return expected


def _path_hash(value: Path) -> str:
    try:
        # Worker derives FileEnvelope.pathHash from PathNormalizer.normalizeKey,
        # so the acknowledgement must hash the same separator/case-normalized key.
        return hashlib.sha256(_worker_path_key(str(value)).encode("utf-8")).hexdigest()
    except UnicodeEncodeError as exc:
        raise ProductionDuplicatePrevalenceError("source path is not valid UTF-8") from exc


def _declared_exclusions(
    source: ProductionSourceSpec,
    *,
    root: Path,
    expected: Mapping[str, Mapping[str, Any]],
) -> dict[str, tuple[TerminalExclusionSpec, Path, Mapping[str, Any]]]:
    by_relative = {str(row["path"]): row for row in expected.values()}
    resolved: dict[str, tuple[TerminalExclusionSpec, Path, Mapping[str, Any]]] = {}
    for exclusion in source.terminal_exclusions:
        row = by_relative.get(exclusion.relative_path)
        if row is None:
            raise ProductionDuplicatePrevalenceError(
                "declared terminal exclusion is absent from the raw manifest"
            )
        if not hmac.compare_digest(exclusion.sha256, str(row["sha256"])):
            raise ProductionDuplicatePrevalenceError(
                "declared terminal exclusion digest does not match the raw manifest"
            )
        try:
            absolute = root.joinpath(*exclusion.relative_path.split("/")).resolve(strict=True)
        except OSError as exc:
            raise ProductionDuplicatePrevalenceError(
                "declared terminal exclusion is unavailable"
            ) from exc
        key = _worker_path_key(str(absolute))
        if key not in expected or key in resolved:
            raise ProductionDuplicatePrevalenceError(
                "declared terminal exclusions do not map uniquely to the raw manifest"
            )
        resolved[key] = (exclusion, absolute, row)
    return resolved


def _validate_failed_jobs(
    payload: Mapping[str, Any],
    declared: Mapping[str, tuple[TerminalExclusionSpec, Path, Mapping[str, Any]]],
) -> tuple[tuple[Any, ...], ...]:
    if set(payload) != {"jobs", "count"}:
        raise ProductionDuplicatePrevalenceError("failed-job response has an unexpected shape")
    jobs = payload["jobs"]
    count = payload["count"]
    if (
        isinstance(count, bool)
        or not isinstance(count, int)
        or not isinstance(jobs, list)
        or count != len(jobs)
        or count != len(declared)
    ):
        raise ProductionDuplicatePrevalenceError(
            "failed-job response does not match declared terminal exclusions"
        )
    expected_by_hash = {
        _path_hash(absolute): exclusion
        for exclusion, absolute, _row in declared.values()
    }
    if len(expected_by_hash) != len(declared):
        raise ProductionDuplicatePrevalenceError("declared terminal exclusion path hashes collide")
    stable_rows: list[tuple[Any, ...]] = []
    seen_hashes: set[str] = set()
    required_fields = {
        "pathHash",
        "state",
        "attempts",
        "lastUpdatedMs",
        "errorMessage",
        "retryAfterMs",
        "collection",
        "scanId",
    }
    for row in jobs:
        if not isinstance(row, Mapping) or set(row) != required_fields:
            raise ProductionDuplicatePrevalenceError("failed-job row has an unexpected shape")
        path_hash = row["pathHash"]
        exclusion = expected_by_hash.get(path_hash) if isinstance(path_hash, str) else None
        if exclusion is None or path_hash in seen_hashes:
            raise ProductionDuplicatePrevalenceError(
                "failed-job identities do not match declared terminal exclusions"
            )
        seen_hashes.add(path_hash)
        if row["state"] != exclusion.expected_state:
            raise ProductionDuplicatePrevalenceError("failed-job state does not match its declaration")
        if row["errorMessage"] != exclusion.expected_error_message:
            raise ProductionDuplicatePrevalenceError(
                "failed-job error does not match its declaration"
            )
        if (
            isinstance(row["attempts"], bool)
            or not isinstance(row["attempts"], int)
            or row["attempts"] < 1
            or isinstance(row["lastUpdatedMs"], bool)
            or not isinstance(row["lastUpdatedMs"], int)
            or row["lastUpdatedMs"] <= 0
            or row["retryAfterMs"] != 0
            or not isinstance(row["collection"], str)
            or not row["collection"]
            or not isinstance(row["scanId"], str)
        ):
            raise ProductionDuplicatePrevalenceError("failed-job row has invalid terminal fields")
        stable_rows.append(
            (
                path_hash,
                row["state"],
                row["attempts"],
                row["lastUpdatedMs"],
                row["errorMessage"],
                row["retryAfterMs"],
                row["collection"],
                row["scanId"],
            )
        )
    return tuple(sorted(stable_rows))


def _listed_documents(payload: Mapping[str, Any], expected_count: int) -> tuple[str, ...]:
    if set(payload) != {"docIds", "totalCount", "tookMs"}:
        raise ProductionDuplicatePrevalenceError("document-ID response has an unexpected shape")
    total = payload["totalCount"]
    took_ms = payload["tookMs"]
    doc_ids = payload["docIds"]
    if (
        isinstance(total, bool)
        or not isinstance(total, int)
        or total != expected_count
        or isinstance(took_ms, bool)
        or not isinstance(took_ms, int)
        or took_ms < 0
        or not isinstance(doc_ids, list)
        or any(not isinstance(item, str) or not item for item in doc_ids)
        or len(doc_ids) != expected_count
        or len(set(doc_ids)) != expected_count
    ):
        raise ProductionDuplicatePrevalenceError("document-ID enumeration did not match expected count")
    normalized = [_worker_path_key(item) for item in doc_ids]
    if len(set(normalized)) != expected_count:
        raise ProductionDuplicatePrevalenceError("document IDs collide after Worker normalization")
    return tuple(doc_ids)


def _validate_pre_ingest_cohort(client: SnapshotClient, expected_ids: set[str]) -> None:
    """Classify every existing parent before any root-registration mutation."""

    payload = client.list_document_ids(0, MAX_DOCUMENTS)
    count = payload.get("totalCount")
    if isinstance(count, bool) or not isinstance(count, int) or not 0 <= count <= MAX_DOCUMENTS:
        raise ProductionDuplicatePrevalenceError("existing document count is invalid or exceeds the snapshot ceiling")
    existing = _listed_documents(payload, count)
    if any(_worker_path_key(doc_id) not in expected_ids for doc_id in existing):
        raise ProductionDuplicatePrevalenceError(
            "existing index contains documents outside the declared corpus; use an isolated corpus runtime"
        )


def _await_expected_document_cohort(
    client: SnapshotClient,
    expected_count: int,
    *,
    timeout_seconds: float = COHORT_SETTLE_TIMEOUT_SECONDS,
    poll_seconds: float = COHORT_SETTLE_POLL_SECONDS,
) -> None:
    """Wait for the parent-ID NRT view to catch up with final enrichment writes.

    The strict snapshot below still captures and compares lifecycle, generation,
    commit, and document identities before/after paging. This bounded preflight
    only avoids starting that snapshot while the final disambiguation publish is
    still settling after pipeline readiness reports complete.
    """

    deadline = time.monotonic() + timeout_seconds
    while True:
        try:
            _listed_documents(client.list_document_ids(0, MAX_DOCUMENTS), expected_count)
            return
        except ProductionDuplicatePrevalenceError as exc:
            if str(exc) != "document-ID enumeration did not match expected count":
                raise
            if time.monotonic() >= deadline:
                raise
            time.sleep(poll_seconds)


def _utf16_chars(value: str) -> int:
    try:
        return len(value.encode("utf-16-le")) // 2
    except UnicodeEncodeError as exc:
        raise ProductionDuplicatePrevalenceError("preview returned invalid Unicode text") from exc


def _read_complete_document(
    client: SnapshotClient,
    doc_id: str,
) -> tuple[str | None, str, Mapping[str, Any]]:
    offset = 0
    total_chars: int | None = None
    chunks: list[str] = []
    stable: dict[str, Any] | None = None
    stable_fields = (
        "docId",
        "requestedDocId",
        "totalChars",
        "mime",
        "path",
        "source",
        "extractionStatus",
        "contentTruncated",
        "extractionPolicyId",
        "extractionParserId",
        "sourceSha256",
        "contentSha256",
    )
    while True:
        page = client.preview(doc_id, offset, PREVIEW_PAGE_CHARS)
        missing = {
            "docId",
            "requestedDocId",
            "offsetChars",
            "nextOffsetChars",
            "totalChars",
            "truncated",
            "content",
            "mime",
            "path",
            "source",
            "extractionStatus",
            "contentTruncated",
            "extractionPolicyId",
            "extractionParserId",
            "sourceSha256",
            "contentSha256",
        } - set(page)
        if missing:
            raise ProductionDuplicatePrevalenceError("preview response is missing snapshot fields")
        if page["docId"] != doc_id or page["requestedDocId"] != doc_id:
            raise ProductionDuplicatePrevalenceError("preview returned a different document identity")
        if page["offsetChars"] != offset:
            raise ProductionDuplicatePrevalenceError("preview offset does not match the requested page")
        content = page["content"]
        next_offset = page["nextOffsetChars"]
        current_total = page["totalChars"]
        truncated = page["truncated"]
        if (
            not isinstance(content, str)
            or isinstance(next_offset, bool)
            or not isinstance(next_offset, int)
            or isinstance(current_total, bool)
            or not isinstance(current_total, int)
            or current_total < 0
            or not isinstance(truncated, bool)
        ):
            raise ProductionDuplicatePrevalenceError("preview returned invalid paging fields")
        current_stable = {field: page[field] for field in stable_fields}
        if stable is None:
            stable = current_stable
            total_chars = current_total
        elif stable != current_stable:
            raise ProductionDuplicatePrevalenceError("preview metadata changed during document paging")
        expected_next = offset + _utf16_chars(content)
        if next_offset != expected_next or next_offset > current_total:
            raise ProductionDuplicatePrevalenceError("preview paging did not make exact forward progress")
        if truncated != (next_offset < current_total):
            raise ProductionDuplicatePrevalenceError("preview truncation flag disagrees with paging state")
        chunks.append(content)
        if not truncated:
            break
        if next_offset <= offset:
            raise ProductionDuplicatePrevalenceError("preview paging made no forward progress")
        offset = next_offset

    assert stable is not None and total_chars is not None
    extraction_status = stable["extractionStatus"]
    content_truncated = stable["contentTruncated"]
    if not isinstance(content_truncated, bool):
        raise ProductionDuplicatePrevalenceError("production extraction lacks truncation state")
    if content_truncated != (extraction_status == "SUCCESS_PARTIAL"):
        raise ProductionDuplicatePrevalenceError(
            "production extraction status disagrees with its truncation state"
        )
    if (
        not isinstance(stable["extractionPolicyId"], str)
        or not stable["extractionPolicyId"]
        or not isinstance(stable["extractionParserId"], str)
        or not stable["extractionParserId"]
    ):
        raise ProductionDuplicatePrevalenceError("preview lacks extraction policy/parser provenance")
    source_sha256 = stable["sourceSha256"]
    if not isinstance(source_sha256, str) or _SHA256_RE.fullmatch(source_sha256) is None:
        raise ProductionDuplicatePrevalenceError("preview lacks indexed source-byte provenance")
    joined = "".join(chunks)
    content_sha256 = stable["contentSha256"]
    if (
        not isinstance(content_sha256, str)
        or _SHA256_RE.fullmatch(content_sha256) is None
        or hashlib.sha256(joined.encode("utf-8")).hexdigest() != content_sha256
    ):
        raise ProductionDuplicatePrevalenceError("preview content does not match its stored-text revision")
    if _utf16_chars(joined) != total_chars:
        raise ProductionDuplicatePrevalenceError("complete preview length does not match totalChars")
    if extraction_status in _SUCCESS_STATUSES:
        if extraction_status == "SUCCESS_EMPTY" and joined:
            raise ProductionDuplicatePrevalenceError("SUCCESS_EMPTY preview returned non-empty content")
        if extraction_status != "SUCCESS_EMPTY" and not joined:
            raise ProductionDuplicatePrevalenceError(
                "non-empty extraction status returned empty content"
            )
        return joined, "success", stable
    if extraction_status in _FAILED_STATUSES:
        if joined:
            raise ProductionDuplicatePrevalenceError("failed extraction returned stored content")
        return None, "failed", stable
    raise ProductionDuplicatePrevalenceError("preview returned an unsupported extraction status")


def _format_id(relative_path: str) -> str | None:
    suffix = Path(relative_path).suffix.lower()
    return suffix[1:] if suffix.startswith(".") and len(suffix) > 1 else None


def _build_snapshot(
    request: ProductionAnalysisRequest,
    client: SnapshotClient,
    *,
    env: Mapping[str, str] | None,
) -> ProductionSnapshot:
    try:
        raw_identity = build_raw_manifest(request.source.raw_root)
    except RawCorpusManifestError as exc:
        raise ProductionDuplicatePrevalenceError("raw corpus validation failed") from exc
    if raw_identity.file_count > MAX_DOCUMENTS:
        raise ProductionDuplicatePrevalenceError(
            f"raw corpus exceeds the {MAX_DOCUMENTS}-document snapshot ceiling"
        )
    try:
        root = request.source.raw_root.resolve(strict=True)
    except OSError as exc:
        raise ProductionDuplicatePrevalenceError(
            "raw corpus changed after manifest validation"
        ) from exc
    files = raw_identity.manifest["files"]
    expected = _expected_documents(root, files)
    declared_exclusions = _declared_exclusions(
        request.source,
        root=root,
        expected=expected,
    )
    expected_indexed_count = raw_identity.file_count - len(declared_exclusions)

    before_health, before_status, before_debug = _validate_live_state(
        client,
        expected_indexed_count=expected_indexed_count,
        expected_failed_count=len(declared_exclusions),
    )
    before_failed_jobs = _validate_failed_jobs(
        client.failed_jobs(MAX_DOCUMENTS), declared_exclusions
    )
    doc_ids = _listed_documents(
        client.list_document_ids(0, MAX_DOCUMENTS),
        expected_indexed_count,
    )
    listed_by_key = {_worker_path_key(item): item for item in doc_ids}
    if set(listed_by_key) != set(expected) - set(declared_exclusions):
        raise ProductionDuplicatePrevalenceError(
            "Worker document identities do not exactly match the declared analyzable cohort"
        )

    alias_key = secrets.token_bytes(32)
    signing_key = secrets.token_bytes(32)
    observations: list[prevalence.DocumentObservation] = []
    aliases: dict[str, tuple[str, ...]] = {}
    provenance_rows: list[dict[str, Any]] = []
    partial_success_count = 0
    exclusion_reason_counts: dict[str, int] = {}
    for key in sorted(expected):
        row = expected[key]
        declared = declared_exclusions.get(key)
        doc_id = str(declared[1]) if declared is not None else listed_by_key[key]
        opaque_id = hmac.new(
            alias_key,
            b"jseval.production-observation-id.v1\0" + doc_id.encode("utf-8"),
            hashlib.sha256,
        ).hexdigest()
        if declared is not None:
            exclusion = declared[0]
            text = None
            extraction_status = "failed"
            exclusion_reason_counts[exclusion.reason] = (
                exclusion_reason_counts.get(exclusion.reason, 0) + 1
            )
            provenance_row = {
                "opaque_id": opaque_id,
                "format_id": _format_id(str(row["path"])),
                "extraction_status": "TERMINAL_EXCLUDED",
                "content_truncated": False,
                "exclusion_reason": exclusion.reason,
            }
        else:
            text, extraction_status, metadata = _read_complete_document(client, doc_id)
            preview_path = metadata["path"]
            if not isinstance(preview_path, str) or _worker_path_key(preview_path) != key:
                raise ProductionDuplicatePrevalenceError(
                    "preview path does not match the enumerated identity"
                )
            if not hmac.compare_digest(metadata["sourceSha256"], str(row["sha256"])):
                raise ProductionDuplicatePrevalenceError(
                    "indexed source-byte provenance does not match the raw manifest"
                )
            if metadata["extractionStatus"] == "SUCCESS_PARTIAL":
                partial_success_count += 1
            provenance_row = {
                "opaque_id": opaque_id,
                "format_id": _format_id(str(row["path"])),
                "extraction_status": metadata["extractionStatus"],
                "content_truncated": metadata["contentTruncated"],
                "extraction_policy_id": metadata["extractionPolicyId"],
                "extraction_parser_id": metadata["extractionParserId"],
            }
        observation = prevalence.DocumentObservation(
            opaque_id=opaque_id,
            raw_sha256=row["sha256"],
            extracted_text=text,
            format_id=_format_id(row["path"]),
            source_kind=prevalence.PRODUCTION_EXTRACTED,
            extraction_status=extraction_status,
        )
        observations.append(observation)
        aliases[opaque_id] = (f"path\0{doc_id}", f"hit-id\0{doc_id}")
        provenance_rows.append(provenance_row)

    after_health, after_status, after_debug = _validate_live_state(
        client,
        expected_indexed_count=expected_indexed_count,
        expected_failed_count=len(declared_exclusions),
    )
    after_failed_jobs = _validate_failed_jobs(
        client.failed_jobs(MAX_DOCUMENTS), declared_exclusions
    )
    if before_health != after_health or _stable_projection(
        before_status, _STABLE_STATUS_FIELDS
    ) != _stable_projection(
        after_status, _STABLE_STATUS_FIELDS
    ) or _stable_projection(before_debug, _STABLE_DEBUG_FIELDS) != _stable_projection(
        after_debug, _STABLE_DEBUG_FIELDS
    ) or before_failed_jobs != after_failed_jobs:
        raise ProductionDuplicatePrevalenceError("index identity changed during production snapshot")
    try:
        validate_raw_manifest(request.source.raw_root, raw_identity.manifest)
    except RawCorpusManifestError as exc:
        raise ProductionDuplicatePrevalenceError("raw corpus changed during production snapshot") from exc

    ordered = tuple(sorted(observations, key=lambda item: item.opaque_id))
    alias_view = MappingProxyType({key: aliases[key] for key in sorted(aliases)})
    private_corpus_signature = hmac.new(
        alias_key,
        b"jseval.private-raw-manifest.v1\0" + raw_identity.digest.encode("ascii"),
        hashlib.sha256,
    ).hexdigest()
    corpus_identity = raw_identity.to_corpus_identity()
    corpus_identity["signature"] = private_corpus_signature
    corpus_identity["manifest_pointer"] = None
    corpus_identity["admission_policy"] = effective_admission_policy(env)
    unsigned_identity: dict[str, Any] = {
        "schema": prevalence.EXTRACTION_SNAPSHOT_SCHEMA_V2,
        "corpus_signature": private_corpus_signature,
        "observations_digest": prevalence.observation_commitment(ordered),
        "source_kind": prevalence.PRODUCTION_EXTRACTED,
        "extractor_build": before_status["buildStamp"],
        "extraction_policy_digest": _canonical_sha256(
            {
                "schema": "jseval.production-extraction-provenance.v1",
                "documents": sorted(provenance_rows, key=lambda row: row["opaque_id"]),
            }
        ),
        "result_aliases_hmac_sha256": result_identity.result_alias_commitment(
            alias_view,
            key=alias_key,
        ),
        "result_mapping_public_key_ed25519": result_identity.result_mapping_public_key(signing_key),
        "document_count": len(ordered),
        "reconciliation": {
            "status": "matched-with-disposition-accounting",
            "expected_count": len(ordered),
            "exported_count": len(ordered),
            "unique_opaque_ids": len(ordered),
            "indexed_count": expected_indexed_count,
            "terminal_excluded_count": len(declared_exclusions),
            "partial_success_count": partial_success_count,
            "terminal_exclusion_reasons": {
                key: exclusion_reason_counts[key] for key in sorted(exclusion_reason_counts)
            },
        },
    }
    extraction_identity = {"digest": _canonical_sha256(unsigned_identity), **unsigned_identity}
    prevalence.validate_extraction_identity(
        extraction_identity,
        ordered,
        corpus_signature=private_corpus_signature,
    )
    return ProductionSnapshot(
        ordered,
        alias_view,
        MappingProxyType(corpus_identity),
        MappingProxyType(extraction_identity),
        alias_key,
        signing_key,
        MappingProxyType(dict(declared_exclusions)),
        root,
        raw_identity.manifest,
        raw_identity.digest,
        request.source.base_url,
        before_health,
        _stable_projection(before_status, _STABLE_STATUS_FIELDS),
        _stable_projection(before_debug, _STABLE_DEBUG_FIELDS),
        before_failed_jobs,
    )


def capture_snapshot(
    request: ProductionAnalysisRequest,
    *,
    client: SnapshotClient | None = None,
    env: Mapping[str, str] | None = None,
) -> ProductionSnapshot:
    """Capture and reconcile one private production extracted-content snapshot."""

    if not isinstance(request, ProductionAnalysisRequest):
        raise ProductionDuplicatePrevalenceError("request must be a ProductionAnalysisRequest")
    checked_request = ProductionAnalysisRequest(
        _checked_source_spec(request.source),
        request.config,
    )
    effective_env = os.environ if env is None else env
    if client is not None:
        return _build_snapshot(checked_request, client, env=effective_env)
    owned = ProductionHttpClient(
        checked_request.source.base_url,
        session_token=effective_env.get(SESSION_TOKEN_ENV),
    )
    try:
        try:
            raw_identity = build_raw_manifest(checked_request.source.raw_root)
        except RawCorpusManifestError as exc:
            raise ProductionDuplicatePrevalenceError(
                "raw corpus cannot produce a strict manifest"
            ) from exc
        _await_expected_document_cohort(
            owned,
            raw_identity.file_count - len(checked_request.source.terminal_exclusions),
        )
        return _build_snapshot(checked_request, owned, env=effective_env)
    except httpx.HTTPError as exc:
        raise ProductionDuplicatePrevalenceError("production snapshot HTTP request failed") from exc
    finally:
        owned.close()


def _revalidate_snapshot(snapshot: ProductionSnapshot, client: SnapshotClient) -> None:
    """Revalidate the private source/index binding after query execution."""

    try:
        validate_raw_manifest(snapshot.raw_root, snapshot.raw_manifest)
    except RawCorpusManifestError as exc:
        raise ProductionDuplicatePrevalenceError(
            "raw corpus changed after production snapshot"
        ) from exc
    health, status, debug = _validate_live_state(
        client,
        expected_indexed_count=len(snapshot.observations) - len(snapshot.terminal_exclusions),
        expected_failed_count=len(snapshot.terminal_exclusions),
    )
    if (
        health != snapshot.health_identity
        or _stable_projection(status, _STABLE_STATUS_FIELDS) != snapshot.status_identity
        or _stable_projection(debug, _STABLE_DEBUG_FIELDS) != snapshot.debug_identity
    ):
        raise ProductionDuplicatePrevalenceError(
            "index identity changed after production snapshot"
        )
    failed_jobs = _validate_failed_jobs(
        client.failed_jobs(MAX_DOCUMENTS), snapshot.terminal_exclusions
    )
    if failed_jobs != snapshot.failed_jobs_identity:
        raise ProductionDuplicatePrevalenceError(
            "terminal failure identity changed after production snapshot"
        )
    expected = _expected_documents(snapshot.raw_root, snapshot.raw_manifest["files"])
    doc_ids = _listed_documents(
        client.list_document_ids(0, MAX_DOCUMENTS),
        len(snapshot.observations) - len(snapshot.terminal_exclusions),
    )
    if {_worker_path_key(item) for item in doc_ids} != set(expected) - set(
        snapshot.terminal_exclusions
    ):
        raise ProductionDuplicatePrevalenceError(
            "Worker document identities changed after production snapshot"
        )


def revalidate_snapshot(
    snapshot: ProductionSnapshot,
    request: ProductionAnalysisRequest,
    *,
    client: SnapshotClient | None = None,
    env: Mapping[str, str] | None = None,
) -> None:
    """Fail closed if the private raw/index cohort drifted after capture."""

    if not isinstance(snapshot, ProductionSnapshot):
        raise ProductionDuplicatePrevalenceError("snapshot must be a ProductionSnapshot")
    if not isinstance(request, ProductionAnalysisRequest):
        raise ProductionDuplicatePrevalenceError("request must be a ProductionAnalysisRequest")
    checked_source = _checked_source_spec(request.source)
    try:
        request_root = checked_source.raw_root.resolve(strict=True)
    except OSError as exc:
        raise ProductionDuplicatePrevalenceError(
            "raw corpus is unavailable during snapshot revalidation"
        ) from exc
    snapshot_exclusion_specs = {
        value[0].relative_path: value[0] for value in snapshot.terminal_exclusions.values()
    }
    request_exclusion_specs = {
        value.relative_path: value for value in checked_source.terminal_exclusions
    }
    if (
        request_root != snapshot.raw_root
        or checked_source.base_url != snapshot.base_url
        or request_exclusion_specs != snapshot_exclusion_specs
    ):
        raise ProductionDuplicatePrevalenceError(
            "production request does not match the captured snapshot source"
        )
    effective_env = os.environ if env is None else env
    if client is not None:
        _revalidate_snapshot(snapshot, client)
        return
    owned = ProductionHttpClient(
        checked_source.base_url,
        session_token=effective_env.get(SESSION_TOKEN_ENV),
    )
    try:
        _revalidate_snapshot(snapshot, owned)
    except httpx.HTTPError as exc:
        raise ProductionDuplicatePrevalenceError(
            "production snapshot revalidation HTTP request failed"
        ) from exc
    finally:
        owned.close()


def analyze_request(
    request: ProductionAnalysisRequest,
    *,
    client: SnapshotClient | None = None,
    env: Mapping[str, str] | None = None,
) -> dict[str, Any]:
    """Capture production content and return only its aggregate duplicate artifact."""

    artifact, _observations = analyze_request_with_observations(
        request, client=client, env=env
    )
    return artifact


def analyze_request_with_observations(
    request: ProductionAnalysisRequest,
    *,
    client: SnapshotClient | None = None,
    env: Mapping[str, str] | None = None,
) -> tuple[dict[str, Any], tuple[prevalence.DocumentObservation, ...]]:
    """Capture once and retain private observations for a local review packet."""

    snapshot = capture_snapshot(request, client=client, env=env)
    artifact = prevalence.analyze(
        snapshot.observations,
        corpus_identity=snapshot.corpus_identity,
        extraction_identity=snapshot.extraction_identity,
        config=request.config,
    )
    return artifact, snapshot.observations


def analyze_input_spec(
    path: Path | str,
    *,
    client: SnapshotClient | None = None,
    env: Mapping[str, str] | None = None,
) -> dict[str, Any]:
    """Load, capture, and analyze a production-extracted request."""

    return analyze_request(load_input_spec(path), client=client, env=env)


def analyze_input_spec_with_observations(
    path: Path | str,
    *,
    client: SnapshotClient | None = None,
    env: Mapping[str, str] | None = None,
) -> tuple[dict[str, Any], tuple[prevalence.DocumentObservation, ...]]:
    """Load, capture, and analyze once for aggregate plus local review output."""

    return analyze_request_with_observations(
        load_input_spec(path), client=client, env=env
    )


__all__ = [
    "MAX_DOCUMENTS",
    "PREVIEW_PAGE_CHARS",
    "ProductionAnalysisRequest",
    "ProductionDuplicatePrevalenceError",
    "ProductionSnapshot",
    "ProductionSourceSpec",
    "SOURCE_KIND",
    "SESSION_TOKEN_ENV",
    "SnapshotClient",
    "TerminalExclusionSpec",
    "analyze_input_spec",
    "analyze_input_spec_with_observations",
    "analyze_request",
    "analyze_request_with_observations",
    "capture_snapshot",
    "ingest_and_wait_for_snapshot",
    "load_input_spec",
    "prepare_request",
    "revalidate_snapshot",
    "wait_for_snapshot_ready",
]
