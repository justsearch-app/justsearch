"""Benchmark-release composition (tempdoc 623).

A *release* is a **projection over cohort-identical eval runs** — one config /
commit / hardware, ranged over N corpora — not a new authority. Every measured
number derives from a ``jseval run`` ``summary.json``; the search-quality
register headline and the relevance-ratchet floors become projections of a
release (tempdoc 623 T-5), which removes the hand-maintained-fork drift class
the tempdoc opened with.

Key design points (tempdoc 623, verified against on-disk artifacts):

- **The config-cohort key is the *config-global subset only*** (T-1 / resolved
  ledger U1). A naive "hash every non-dataset manifest field" key would *never*
  group ``beir/scifact`` with the ``mixed/*`` corpora, because three
  ``commit_metadata`` fingerprints (``field_catalog_hash``, ``index_fingerprint``,
  ``synonyms_hash``) are **corpus-dependent** — they describe
  which fields a corpus populates, not a configuration choice. So
  :func:`config_cohort_key` hashes an explicit allow-list and excludes those.

- **The metrics map is family-keyed, NOT nDCG-hard-wired** (T-3 / §F favor 1).
  A retrieval release populates ``{nDCG@10, P@1, ...}``; an extraction-quality
  sibling reuses this exact object with ``{WER, CER, route_accuracy, ...}``. The
  release object is "a config-cohort's scorecard over a corpus set," metric
  family parametric — so the sibling extends 623 instead of forking it.

- **The reproduction tolerance is the run's within-machine ±2σ envelope**, scoped
  to "equivalent setup" (decision F-α). ``None`` when the cohort is uncalibrated.

- ``mode`` (hybrid / full / lexical) is a per-run eval parameter, **not** part of
  cohort identity, so one cohort can carry several modes; a release picks one
  ``default_mode`` per corpus and records the rest as labelled ablations.
"""

from __future__ import annotations

import hashlib
import json
import re
from typing import Any

RELEASE_SCHEMA = "release.v1"
RELEASE_SCHEMA_VERSION = 1

# release_id contract (tempdoc 683, mirrored in release.v1.schema.json): a required,
# date-suffixed human label, e.g. "667-external-baselines-2026-07-01".
RELEASE_ID_PATTERN = r"^[a-z0-9][a-z0-9-]*-\d{4}-\d{2}-\d{2}$"
_RELEASE_ID_RE = re.compile(RELEASE_ID_PATTERN)


def validate_release_id(release_id: str | None) -> str | None:
    """Return an error message when ``release_id`` is missing/malformed, else ``None``.

    The compose CLI refuses to write a release without a conformant id (the id is what
    outward docs cite — see scripts/ci/check-outward-number-citations.mjs), so it must
    exist and be stable-format from day one.
    """
    if not release_id:
        return (
            "release_id is required: pass --release-id <label>-<YYYY-MM-DD> "
            f"(pattern {RELEASE_ID_PATTERN})"
        )
    if not _RELEASE_ID_RE.match(release_id):
        return (
            f"release_id {release_id!r} does not match {RELEASE_ID_PATTERN} "
            "(lowercase label, '-' separators, date suffix — e.g. "
            "667-external-baselines-2026-07-01)"
        )
    return None

# --- The config-cohort key field-set (tempdoc 623 T-1 / U1) -----------------
#
# commit_metadata fingerprints that are CONFIG-global (verified identical across
# scifact / courtlistener-200 / cord19-qddf on disk):
_CONFIG_GLOBAL_COMMIT_METADATA = (
    "schema_fp",
    "similarity_fp",
    "boosts_fp",
    "grammar_hash",
)
# commit_metadata fingerprints that are CORPUS-dependent (differ scifact vs
# mixed) — documented here so the exclusion is legible, never hashed into the key.
# tempdoc 915: index_schema_fp/analyzer_fp were folded into index_fingerprint.
_CORPUS_DEPENDENT_COMMIT_METADATA = (
    "field_catalog_hash",
    "index_fingerprint",
    "synonyms_hash",
)
# model_fingerprints keys that are execution-context (vary GPU vs CPU), excluded
# from model identity:
_MODEL_EXECUTION_FLAGS = ("embed_gpu", "splade_gpu", "ner_gpu", "reranker_gpu")


class ComposeError(ValueError):
    """Raised when a candidate run-set cannot form one coherent release."""


def select_dominant_cohort(dataset_to_key: dict[str, str]) -> tuple[str | None, list[str]]:
    """Pick the config-cohort covering the most datasets; report the excluded split (P1, 623).

    ``--latest-per-dataset`` can pick runs spanning commits when the shared ``main`` moves
    mid-sweep (the V5 split). Rather than feed a mixed set to :func:`compose` (which refuses
    the whole thing), choose the dominant cohort and surface the rest. Deterministic tie-break
    (count desc, then key desc).

    :param dataset_to_key: ``{dataset_slug: config_cohort_key}`` for the latest run per dataset.
    :returns: ``(chosen_key | None, sorted excluded dataset slugs)``.
    """
    if not dataset_to_key:
        return None, []
    counts: dict[str, int] = {}
    for key in dataset_to_key.values():
        counts[key] = counts.get(key, 0) + 1
    chosen = max(counts, key=lambda k: (counts[k], k))
    excluded = sorted(ds for ds, k in dataset_to_key.items() if k != chosen)
    return chosen, excluded


def canonical_dataset_slug(dataset: str | None) -> str | None:
    """Normalize a run's ``summary.dataset`` to the register/ratchet slug form.

    ``jseval run --dataset`` uses the short BEIR key (``scifact``), but the
    search-quality register, the relevance-ratchet pointer, and the external
    baselines all key on the ``beir/<name>`` slug. A BEIR short name (no ``/``)
    is prefixed with ``beir/``; ``mixed/*`` and ``golden/*`` are already slugged.
    Keeping the release keyed on the canonical slug is what lets the projected
    floors line up with ``jseval relevance-gate --dataset beir/scifact``.
    """
    if dataset is None:
        return None
    return dataset if "/" in dataset else f"beir/{dataset}"


def _canonical_sha(obj: Any) -> str:
    """SHA-256 of canonical JSON (sorted keys, compact). Mirrors manifest.py."""
    blob = json.dumps(
        obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False,
    )
    return hashlib.sha256(blob.encode("utf-8")).hexdigest()


def _model_identity(model_fingerprints: dict | None) -> dict:
    """Model identity minus execution-context flags (T-1 exclusion)."""
    mf = model_fingerprints or {}
    return {k: v for k, v in mf.items() if k not in _MODEL_EXECUTION_FLAGS}


def config_cohort_key(manifest: dict) -> str:
    """Equivalence key over the *config-global* manifest subset (T-1 / U1).

    Two runs sharing this key are the *same configuration on the same hardware*,
    regardless of corpus. Hashes ONLY: ``git_sha``, ``eval_protocol_hash``,
    ``policy_hash``, the four config-global ``commit_metadata`` fps, and model
    identity (minus the ``*_gpu`` flags). Excludes the dataset family and the
    three corpus-dependent ``commit_metadata`` fps — including those would refuse
    every multi-corpus release (the U1 finding).
    """
    cm = manifest.get("commit_metadata") or {}
    key_surface = {
        "git_sha": manifest.get("git_sha"),
        "eval_protocol_hash": manifest.get("eval_protocol_hash"),
        "policy_hash": manifest.get("policy_hash"),
        "commit_metadata_global": {
            k: cm.get(k) for k in _CONFIG_GLOBAL_COMMIT_METADATA
        },
        "model_identity": _model_identity(manifest.get("model_fingerprints")),
    }
    return _canonical_sha(key_surface)


# --- Per-run projections ----------------------------------------------------

def _hardware_projection(manifest: dict) -> dict:
    """Stable hardware identity projected from the run's (volatile) snapshots.

    GPU name from ``env_fingerprint.gpu``; driver/VRAM from
    ``inference_status_snapshot.gpu``; ORT/CUDA version from the same gpu object
    when present (tempdoc 623 U7 — added worker-side). All best-effort: a missing
    probe yields ``None``, never an error.
    """
    gpu_fp = (manifest.get("env_fingerprint") or {}).get("gpu") or {}
    inf = manifest.get("inference_status_snapshot") or {}
    gpu_inf = inf.get("gpu") or {}
    # ORT version comes from the WORKER (where ORT is initialized), surfaced via the worker
    # effective_config map (debug-only WorkerDebugView) into /api/debug/state — retained, un-hashed
    # (tempdoc 623 U7). The Head cannot initialize ORT, so it is NOT read from inference-status.
    # cudaVersion is the pinned toolkit major, captured Head-side (a constant, no ORT init).
    worker = (manifest.get("debug_state_snapshot") or {}).get("worker") or {}
    effective_config = worker.get("effective_config") or {}
    platform = (manifest.get("env_fingerprint") or {}).get("platform") or {}
    return {
        "os": " ".join(str(platform[k]) for k in ("system", "release", "machine") if platform.get(k)) or None,
        "gpu_name": gpu_fp.get("name"),
        "gpu_vram_bytes": gpu_inf.get("totalVramBytes") or gpu_fp.get("mem_total_mb"),
        "gpu_driver_version": gpu_inf.get("nvmlDriverVersion") or gpu_fp.get("driver_version"),
        "ort_version": effective_config.get("ort.version"),
        "cuda_version": gpu_inf.get("cudaVersion"),
        "tier": inf.get("tier"),
    }


def _corpus_source(summary: dict) -> dict:
    """Pointer reference for a corpus (pointer-only distribution, F-ε).

    Records the dataset id, the per-corpus signature seam (``corpus_identity``),
    and counts. The ``sha256`` slot is the checksum-pinning seam (reuse the
    ``SSOT/manifests/repro`` digest model) — populated when a corpus signature
    exists, ``None`` otherwise (honest: not yet checksummed).
    """
    ci = summary.get("corpus_identity") or {}
    dataset = summary.get("dataset")
    src: dict = {
        "dataset": dataset,
        "doc_count": summary.get("doc_count"),
        "query_count": summary.get("query_count"),
        "signature": ci.get("signature"),
        "sha256": ci.get("signature"),  # the digest seam; None until populated
        # Record-if-available pass-through (tempdoc 683): corpus_identity today carries
        # only `signature` (corpus_identity.py), so this stays None until a fetcher
        # records an upstream revision — no fetcher rebuild here.
        "upstream_revision": ci.get("upstream_revision"),
    }
    if isinstance(dataset, str) and dataset.startswith("beir/"):
        # BEIR corpora are fetched by ir_datasets — the id IS the pointer.
        src["ir_datasets_id"] = dataset.split("beir/", 1)[1]
    return src


def _measured_for_mode(summary: dict, mode: str) -> dict | None:
    """Family-keyed metrics for ``mode`` from a run summary, or ``None``.

    The ``metrics`` map is NOT nDCG-specific: it copies whatever
    ``aggregate_metrics`` the run reported (T-3 / §F favor 1).
    """
    per_mode = (summary.get("per_mode") or {}).get(mode)
    if not isinstance(per_mode, dict):
        return None
    metrics = per_mode.get("aggregate_metrics")
    if not isinstance(metrics, dict) or not metrics:
        return None
    manifest = summary.get("manifest") or {}
    qsum = summary.get("qrels_summary") or {}
    # Per-run metric family (tempdoc 640): throughput from the summary's `run_metrics` + a derived
    # resident footprint from the manifest's model fingerprints. Carried as a sibling to `metrics`
    # (the per-mode family) so the perf gate can project a per-run floor from the release like
    # relevance projects nDCG. Best-effort — keys are omitted when their source is unresolvable.
    run_metrics = dict(summary.get("run_metrics") or {})
    from . import perf_gate as _pg
    _footprint = _pg.derive_resident_model_bytes(manifest)
    if isinstance(_footprint, (int, float)):
        run_metrics["resident_bytes"] = float(_footprint)
    # tempdoc 647: the per-component footprint allocation (embed/SPLADE/reranker/NER/LLM) projects the
    # same way, so the perf gate can guard each component's resident bytes from bloating (best-effort —
    # AI-offline runs omit `llm_bytes`).
    _components = _pg.derive_resident_component_bytes(manifest)
    if _components:
        for _ckey, _cval in _components.items():
            run_metrics[_ckey] = float(_cval)
    return {
        "config_mode": mode,
        "metrics": dict(metrics),
        "run_metrics": run_metrics,
        "comparable": bool(per_mode.get("comparable")),
        "ann_proof_status": per_mode.get("ann_proof_status"),
        "confidence_tier": _confidence_tier(summary, qsum),
        "corpus_source": _corpus_source(summary),
        "qrels_id": qsum.get("relevance_mode"),
        **({"ce_coverage": summary["ce_coverage"]["per_mode"][mode]}
           if mode in (summary.get("ce_coverage") or {}).get("per_mode", {}) else {}),
    }


def _confidence_tier(summary: dict, qrels_summary: dict) -> str:
    """A/B/C tier mirroring the register convention (≥200 q = A, else B; C unused here)."""
    qc = summary.get("query_count") or qrels_summary.get("query_count") or 0
    try:
        qc = int(qc)
    except (TypeError, ValueError):
        qc = 0
    return "A" if qc >= 200 else "B"


# --- Composition ------------------------------------------------------------

def compose(
    run_summaries: list[dict],
    *,
    default_mode: str = "hybrid",
    composed_at: str,
    release_id: str | None = None,
    external_baselines: dict | None = None,
    require_comparable: bool = True,
    leak_by_dataset: dict | None = None,
    union_recall_by_dataset: dict | None = None,
    run_sources: list[dict] | None = None,
    withheld_default: dict[str, str] | None = None,
) -> dict:
    """Compose cohort-identical run summaries into one benchmark release.

    :param run_summaries: parsed ``summary.json`` dicts (each carries an embedded
        ``manifest`` — verified present, U4), one or more per corpus.
    :param default_mode: the production-default search mode whose metrics are the
        per-corpus headline; other modes become labelled ablations.
    :param composed_at: ISO timestamp (passed in — scripts can't call ``Date.now``).
    :param external_baselines: optional ``{dataset: [{model, value, source, ...}]}``
        cited (immutable) references shown side-by-side; never a projection of ours.
    :param leak_by_dataset: optional ``{dataset_slug: leak_rate}`` measured from the
        runs' ``staged_recall_accounting`` projections (sourced by the compose CLI,
        which has the run dirs; tempdoc 683). When non-empty, written as the
        release's ``leak`` section so leak-gate's ``current_release`` pointer can
        project ceilings from it.
    :param union_recall_by_dataset: optional ``{dataset_slug: leg_union_recall}`` measured
        from the runs' ``staged_recall_accounting`` projections (sourced by the compose CLI,
        which has the run dirs; recall-survival sibling of ``leak_by_dataset``, tempdoc 701).
        When non-empty, written as the release's ``union_recall`` section so
        union-recall-gate's ``current_release`` pointer can project floors from it.
    :param run_sources: optional list PARALLEL to ``run_summaries`` — one
        ``{"run_dir": str, "summary_sha256": str}`` per run, supplied by the compose CLI
        (only it knows the run dirs). Recorded verbatim as the release's ``sources``
        section, with this function supplying the canonical dataset slug so the CLI and
        the composer cannot disagree about naming.

        Why this exists (tempdoc 802): a release used to record its *config* provenance
        (git sha, cohort key, hardware, model fingerprints) but nothing pointing at the
        ARTIFACTS it was projected from. When the run directories were later cleaned up,
        the published numbers became impossible to re-score or re-derive — discovered on
        `715-rebaseline-2026-07-16`, whose runs no longer exist anywhere on disk. The
        ``summary_sha256`` is the durable half: paths rot, but it still answers "is this
        candidate directory the one this release was built from?".
    :param withheld_default: included dataset slugs mapped to publication-withholding reasons.
        Their default-mode metrics supply neither claims nor floors; sources, CE coverage
        and non-default ablations remain visible.
    :raises ComposeError: if the runs don't all share one ``config_cohort_key``,
        or ``run_sources`` is present but not the same length as ``run_summaries``,
        or (when ``require_comparable``) a default-mode run isn't ``comparable``.
    :returns: the release document (``release.v1`` schema).
    """
    if not run_summaries:
        raise ComposeError("no run summaries provided")
    withheld_default = withheld_default or {}
    datasets = {canonical_dataset_slug(s.get("dataset")) for s in run_summaries}
    if set(withheld_default) - datasets or any(not reason.strip() for reason in withheld_default.values()):
        raise ComposeError("withheld defaults must name included datasets and provide a non-empty reason")

    # Fail CLOSED on a mismatched sources list rather than recording provenance that points
    # at the wrong run — a silently misaligned pointer is worse than none, because it looks
    # authoritative (tempdoc 802; the class 742 names).
    if run_sources is not None and len(run_sources) != len(run_summaries):
        raise ComposeError(
            f"run_sources has {len(run_sources)} entries but there are "
            f"{len(run_summaries)} run summaries; they must be parallel",
        )

    # 1. All members must share one config-cohort key (the equivalence class).
    keyed: list[tuple[str, dict]] = []
    for s in run_summaries:
        manifest = s.get("manifest")
        if not isinstance(manifest, dict):
            raise ComposeError(
                f"run for dataset {s.get('dataset')!r} has no embedded manifest",
            )
        keyed.append((config_cohort_key(manifest), s))

    keys = {k for k, _ in keyed}
    if len(keys) != 1:
        # Build a legible diff so the operator sees WHY they're not one cohort.
        by_key: dict[str, list] = {}
        for k, s in keyed:
            by_key.setdefault(k[:12], []).append(s.get("dataset"))
        raise ComposeError(
            "runs are not cohort-identical (differ on the config-global key); "
            f"groups: {by_key}",
        )
    cohort_key = keys.pop()

    # 2. Reference cohort tuple (from the first member's manifest — all equal).
    ref_manifest = run_summaries[0]["manifest"]
    # tempdoc 664: `config_cohort_key` deliberately excludes GPU/hardware execution flags
    # (`_MODEL_EXECUTION_FLAGS`) — intentional, and guarded by
    # `test_config_key_ignores_gpu_execution_flags`, so relevance-style comparisons aren't
    # needlessly split by hardware. That leaves a real gap for hardware-SENSITIVE metric
    # families (perf: latency/throughput): `cohort["hardware"]` below was silently sourced from
    # only the FIRST member, with no check the rest agree. Record that agreement explicitly here
    # — additive, does not change `config_cohort_key` or cohort membership — so a
    # hardware-sensitive projection (perf_gate.py) can refuse to trust a mixed-hardware release.
    hardware_snapshots = [_hardware_projection(s["manifest"]) for s in run_summaries]
    cohort = {
        "config_cohort_key": cohort_key,
        "git_sha": ref_manifest.get("git_sha"),
        "eval_protocol_hash": ref_manifest.get("eval_protocol_hash"),
        "policy_hash": ref_manifest.get("policy_hash"),
        "model_identity": _model_identity(ref_manifest.get("model_fingerprints")),
        # tempdoc 644: the realized engine set this release was measured under (also inside
        # model_identity; surfaced here as the legible read-path the homogeneity gate compares a
        # HEAD run against). All members share it — engine-set differences split config_cohort_key.
        "realized_engines": (ref_manifest.get("model_fingerprints") or {}).get("realized_engines"),
        "hardware": _hardware_projection(ref_manifest),
        "hardware_homogeneous": all(h == hardware_snapshots[0] for h in hardware_snapshots),
    }

    # 3. Per-corpus measured projection (one row per dataset) + ablations.
    measured: dict[str, dict] = {}
    ablations: dict[str, list] = {}
    missing_default: list[str] = []
    withheld: dict[str, dict] = {}
    for s in run_summaries:
        ds = canonical_dataset_slug(s.get("dataset"))
        head = _measured_for_mode(s, default_mode)
        if ds in withheld_default:
            if head is None:
                raise ComposeError(f"cannot withhold absent default-mode run for {ds!r}")
            withheld[ds] = {
                "config_mode": default_mode,
                "reason": withheld_default[ds],
                "corpus_source": head["corpus_source"],
                "ce_coverage": head.get("ce_coverage"),
            }
        elif head is not None:
            if require_comparable and (not head["comparable"] or
                    (head.get("ce_coverage") or {}).get("verdict") == "degraded-ce"):
                raise ComposeError(
                    f"default-mode run for {ds!r} is not comparable "
                    f"(reasons in summary); refusing to publish",
                )
            measured[ds] = head
        else:
            missing_default.append(ds)
        # Any non-default modes present become labelled ablations.
        for mode in (s.get("per_mode") or {}):
            if mode == default_mode:
                continue
            abl = _measured_for_mode(s, mode)
            if abl is not None:
                ablations.setdefault(ds, []).append(abl)

    leak_section = {
        canonical_dataset_slug(ds): {
            "leak_rate": float(rate),
            "src": "staged_recall_accounting projection",
        }
        for ds, rate in (leak_by_dataset or {}).items()
        if isinstance(rate, (int, float))
    }

    union_recall_section = {
        canonical_dataset_slug(ds): {
            "leg_union_recall": float(rate),
            "src": "staged_recall_accounting projection",
        }
        for ds, rate in (union_recall_by_dataset or {}).items()
        if isinstance(rate, (int, float))
    }

    # Artifact provenance: which runs this projection was built from (tempdoc 802). One entry
    # per member, in `run_summaries` order, so a corpus contributing several runs stays legible.
    sources_section = [
        {
            "dataset": canonical_dataset_slug(s.get("dataset")),
            "run_dir": src.get("run_dir"),
            "summary_sha256": src.get("summary_sha256"),
        }
        for s, src in zip(run_summaries, run_sources or [])
    ]

    return {
        "schema": RELEASE_SCHEMA,
        "schema_version": RELEASE_SCHEMA_VERSION,
        "release_id": release_id,
        "composed_at": composed_at,
        "default_mode": default_mode,
        "cohort": cohort,
        "measured": measured,
        "ablations": ablations,
        **({"withheld": withheld} if withheld else {}),
        **({"sources": sources_section} if sources_section else {}),
        **({"leak": leak_section} if leak_section else {}),
        **({"union_recall": union_recall_section} if union_recall_section else {}),
        "external_baselines": external_baselines or {},
        # First-class negative-space statement (tempdoc 623 T-3 / §F): a release
        # must never imply the extraction front-half is measured.
        "coverage": {
            "measures": "retrieval ranking quality (per-corpus metrics above)",
            "does_not_measure": (
                "document extraction / OCR / VDU routing quality "
                "(see tempdoc 623 §F — extraction-quality sibling)"
            ),
        },
        "notes": {
            "missing_default_mode": missing_default,
            "tolerance_semantics": (
                "reproduction tolerance is the within-machine ±2sigma envelope, "
                "scoped to equivalent hardware/setup (tempdoc 623 F-alpha)"
            ),
        },
    }
