"""Tests for suite_stats.py — multi-run statistics."""

from __future__ import annotations

import math

from jsharness.suite_stats import compute_stats, percentile, summarize_suite


class TestComputeStats:
    def test_empty(self):
        s = compute_stats([])
        assert s["median"] is None
        assert s["stddev"] is None
        assert s["ci_lower_95"] is None

    def test_single_value(self):
        s = compute_stats([42.0])
        assert s["median"] == 42.0
        assert s["min"] == 42.0
        assert s["max"] == 42.0
        assert s["stddev"] is None
        assert s["ci_lower_95"] is None

    def test_multiple_values(self):
        s = compute_stats([10.0, 20.0, 30.0, 40.0, 50.0])
        assert s["median"] == 30.0
        assert s["min"] == 10.0
        assert s["max"] == 50.0
        assert s["stddev"] is not None
        assert s["stddev"] > 0
        assert s["ci_lower_95"] is not None
        assert s["ci_upper_95"] is not None
        assert s["ci_lower_95"] < s["ci_upper_95"]

    def test_two_values_uses_t_table(self):
        # df=1, t=12.706 — very wide CI
        s = compute_stats([100.0, 200.0])
        assert s["median"] == 150.0
        assert s["ci_lower_95"] is not None
        # With df=1 the CI should be very wide
        assert s["ci_upper_95"] - s["ci_lower_95"] > 100

    def test_decimals(self):
        s = compute_stats([1.11111, 2.22222, 3.33333], decimals=1)
        assert s["median"] == 2.2

    def test_even_count_median(self):
        s = compute_stats([10.0, 20.0, 30.0, 40.0])
        assert s["median"] == 25.0


class TestPercentile:
    def test_p50(self):
        assert percentile([1, 2, 3, 4, 5], 50) == 3

    def test_p95_small(self):
        assert percentile([1, 2, 3, 4, 5], 95) == 5

    def test_single(self):
        assert percentile([42], 99) == 42


class TestSummarizeSuite:
    def test_basic(self):
        results = [
            {"docs_per_s": 100, "elapsed_sec": 5.0},
            {"docs_per_s": 120, "elapsed_sec": 4.2},
            {"docs_per_s": 110, "elapsed_sec": 4.5},
        ]
        s = summarize_suite(results, ["docs_per_s", "elapsed_sec"])
        assert s["docs_per_s"]["median"] == 110.0
        assert s["elapsed_sec"]["median"] == 4.5

    def test_missing_key(self):
        results = [{"a": 1}, {"a": 2}, {"b": 3}]
        s = summarize_suite(results, ["a", "b"])
        assert s["a"]["median"] == 1.5
        assert s["b"]["median"] == 3.0
