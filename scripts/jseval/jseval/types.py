"""Shared dataclasses for cross-module result types."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Literal


@dataclass
class IngestConfig:
    """Configuration for the ingest/readiness phase of an eval run.

    Bundles the parameters shared across the ingest call chain
    (prepare_corpus → ingest_and_wait → wait_*) so that adding a
    new run-level parameter requires updating only this dataclass
    and the consumer, not every intermediate signature.
    """

    base_url: str
    dense_enabled: bool = False
    splade_enabled: bool = False
    pipeline: bool = False
    timeline_path: Path | None = None
    json_mode: bool = False
    process_check: Callable[[], bool] | None = None
    index_timeout_sec: float = 7200.0


@dataclass
class CorpusMeta:
    """Metadata about a loaded dataset.

    The ``contamination_class``/``closed_book_certification``/``fidelity``/
    ``type_axis``/``suite``/``generation_provenance``/``corpus_signature`` fields are
    the tempdoc-635 corpus-as-governed-artifact extension: a self-demonstration corpus
    carries its own contamination class, a closed-book certification result, a
    fidelity/difficulty label, its suite/type membership, how it was generated, and a
    content signature. All optional — absent on the public BEIR/`mixed` comparison
    corpora, populated on `golden/` clean corpora.
    """

    name: str
    source: Literal["beir", "golden", "mixed"]
    doc_count: int
    query_count: int
    has_expected_terms: bool = False
    version: str | None = None
    created_date: str | None = None
    # tempdoc 635 — corpus identity/provenance/certification (golden self-demo corpora)
    contamination_class: str | None = None
    closed_book_certification: dict | None = None
    fidelity: dict | None = None
    type_axis: str | None = None
    suite: str | None = None
    generation_provenance: dict | None = None
    corpus_signature: str | None = None


@dataclass
class AnnProofResult:
    """Result of ANN proof computation."""

    status: Literal["PASS", "FAIL", "NOT_APPLICABLE"]
    reasons: list[str] = field(default_factory=list)
    rates: dict[str, float] = field(default_factory=dict)


@dataclass
class ComparabilityResult:
    """Result of comparability determination."""

    comparable: bool
    reasons: list[str] = field(default_factory=list)


@dataclass
class QueryRecord:
    """A query with optional annotations from JSONL."""

    text: str
    annotations: dict = field(default_factory=dict)


@dataclass
class EvidenceSpec:
    """Expected evidence for a query (terms + units + source label)."""

    source: str
    terms: list[str]
    units: list[str] = field(default_factory=list)


@dataclass
class QueryCoverage:
    """Per-query context coverage result."""

    qid: str
    expected_term_source: str
    expected_term_count: int
    expected_unit_count: int
    relevant_doc_retrieved: bool
    relevant_excerpt_available: bool
    best_term_coverage: float | None
    top1_term_coverage: float | None
    best_unit_coverage: float | None
    top1_unit_coverage: float | None
    best_matched_term_count: int
    best_matched_unit_count: int
