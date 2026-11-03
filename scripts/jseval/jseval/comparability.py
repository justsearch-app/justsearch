"""Comparability determination from readiness, ANN proof, and error rates."""

from __future__ import annotations

from jsharness.types import ReadinessResult

from .types import AnnProofResult, ComparabilityResult


def determine_comparability(
    readiness: ReadinessResult,
    ann_proof: AnnProofResult,
    error_count: int,
    query_count: int,
    max_error_rate: float = 0.05,
) -> ComparabilityResult:
    """Aggregate readiness + ANN proof + error rates into comparability.

    A run is comparable only if readiness passed, ANN proof passed (or N/A),
    and error rate is below the threshold.
    """
    reasons: list[str] = []

    if not readiness.passed:
        reasons.append(
            f"readiness_failed: {', '.join(readiness.failure_reasons)}"
        )

    if ann_proof.status == "FAIL":
        reasons.append(
            f"ann_proof_failed: {', '.join(ann_proof.reasons)}"
        )

    if query_count > 0:
        error_rate = error_count / query_count
        if error_rate > max_error_rate:
            reasons.append(f"error_rate={error_rate:.3f} > {max_error_rate}")

    return ComparabilityResult(comparable=len(reasons) == 0, reasons=reasons)
