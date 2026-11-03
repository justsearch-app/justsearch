"""Suite statistics — median, stddev, 95% CI for multi-run benchmarks."""

from __future__ import annotations

import logging
import math
import statistics as stats_mod
from typing import Callable

log = logging.getLogger(__name__)

# t-distribution critical values for 95% two-tailed CI (df 1-10)
_T_TABLE = {
    1: 12.706, 2: 4.303, 3: 3.182, 4: 2.776, 5: 2.571,
    6: 2.447, 7: 2.365, 8: 2.306, 9: 2.262, 10: 2.228,
}


def compute_stats(values: list[float], decimals: int = 2) -> dict:
    """Compute median, min, max, stddev, and 95% CI for the mean."""
    if not values:
        return {
            "median": None, "min": None, "max": None,
            "stddev": None, "ci_lower_95": None, "ci_upper_95": None,
        }
    n = len(values)
    med = round(stats_mod.median(values), decimals)
    mn = round(min(values), decimals)
    mx = round(max(values), decimals)
    if n < 2:
        return {
            "median": med, "min": mn, "max": mx,
            "stddev": None, "ci_lower_95": None, "ci_upper_95": None,
        }
    sd = stats_mod.stdev(values)
    mean = stats_mod.mean(values)
    t = _T_TABLE.get(n - 1, 1.96)
    margin = t * (sd / math.sqrt(n))
    return {
        "median": med, "min": mn, "max": mx,
        "stddev": round(sd, decimals),
        "ci_lower_95": round(mean - margin, decimals),
        "ci_upper_95": round(mean + margin, decimals),
    }


def percentile(sorted_values: list[float], p: float) -> float:
    """Nearest-rank percentile from a pre-sorted list."""
    n = len(sorted_values)
    idx = int(p / 100 * (n - 1) + 0.5)
    return sorted_values[min(idx, n - 1)]


def summarize_suite(
    results: list[dict],
    metric_keys: list[str],
    decimals: int = 2,
) -> dict:
    """Compute stats for each metric key across a list of run results."""
    summary: dict = {}
    for key in metric_keys:
        values = [r[key] for r in results if r.get(key) is not None]
        summary[key] = compute_stats(values, decimals)
    return summary


def run_suite(run_fn: Callable[[], dict], runs: int = 5) -> list[dict]:
    """Execute run_fn N times, return list of result dicts."""
    results: list[dict] = []
    for i in range(1, runs + 1):
        log.info("Suite run %d/%d", i, runs)
        results.append(run_fn())
    return results
