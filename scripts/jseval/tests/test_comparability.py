"""Tests for comparability.py — comparability determination."""

from __future__ import annotations

from jseval.comparability import determine_comparability
from jsharness.types import ReadinessResult
from jseval.types import AnnProofResult


def _passed_readiness() -> ReadinessResult:
    return ReadinessResult(passed=True)


def _failed_readiness(*reasons: str) -> ReadinessResult:
    return ReadinessResult(passed=False, failure_reasons=list(reasons))


def _passed_proof() -> AnnProofResult:
    return AnnProofResult(status="PASS")


def _failed_proof(*reasons: str) -> AnnProofResult:
    return AnnProofResult(status="FAIL", reasons=list(reasons))


def _na_proof() -> AnnProofResult:
    return AnnProofResult(status="NOT_APPLICABLE")


class TestDetermineComparability:
    def test_all_good(self):
        result = determine_comparability(
            _passed_readiness(), _passed_proof(), error_count=0, query_count=100,
        )
        assert result.comparable is True
        assert result.reasons == []

    def test_not_applicable_proof_is_ok(self):
        result = determine_comparability(
            _passed_readiness(), _na_proof(), error_count=0, query_count=100,
        )
        assert result.comparable is True

    def test_readiness_failed(self):
        result = determine_comparability(
            _failed_readiness("index_not_idle"),
            _passed_proof(),
            error_count=0, query_count=100,
        )
        assert result.comparable is False
        assert any("readiness_failed" in r for r in result.reasons)
        assert any("index_not_idle" in r for r in result.reasons)

    def test_ann_proof_failed(self):
        result = determine_comparability(
            _passed_readiness(),
            _failed_proof("hybrid_effective_rate=0.8 < 0.95"),
            error_count=0, query_count=100,
        )
        assert result.comparable is False
        assert any("ann_proof_failed" in r for r in result.reasons)

    def test_high_error_rate(self):
        result = determine_comparability(
            _passed_readiness(), _passed_proof(),
            error_count=10, query_count=100,
        )
        assert result.comparable is False
        assert any("error_rate" in r for r in result.reasons)

    def test_multiple_failures(self):
        result = determine_comparability(
            _failed_readiness("index_not_idle"),
            _failed_proof("low_rate"),
            error_count=20, query_count=100,
        )
        assert result.comparable is False
        assert len(result.reasons) == 3

    def test_error_rate_at_threshold(self):
        # Exactly at max_error_rate (0.05) should still be comparable
        result = determine_comparability(
            _passed_readiness(), _passed_proof(),
            error_count=5, query_count=100, max_error_rate=0.05,
        )
        assert result.comparable is True

    def test_error_rate_just_above_threshold(self):
        result = determine_comparability(
            _passed_readiness(), _passed_proof(),
            error_count=6, query_count=100, max_error_rate=0.05,
        )
        assert result.comparable is False

    def test_zero_queries(self):
        # No queries → no error rate check → comparable if readiness/proof ok
        result = determine_comparability(
            _passed_readiness(), _passed_proof(),
            error_count=0, query_count=0,
        )
        assert result.comparable is True
