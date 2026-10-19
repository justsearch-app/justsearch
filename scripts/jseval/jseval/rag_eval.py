"""RAG quality evaluation — orchestrate Gradle test + diff 7 metrics."""

from __future__ import annotations

import json
import logging
import subprocess
from pathlib import Path

from . import diff_gate
from ._paths import REPO_ROOT

log = logging.getLogger(__name__)

RAG_METRICS: list[tuple[str, str, float]] = [
    ("fact_coverage_mean", "min", 0.90),
    ("faithfulness_mean", "min", 0.90),
    ("retrieval_recall_mean", "min", 0.95),
    ("answer_similarity_mean", "min", 0.80),
    ("forbidden_fact_rate_mean", "max", 2.00),
    ("citation_precision_mean", "min", 0.80),
    ("citation_recall_mean", "min", 0.80),
]



def run_rag_eval(
    *,
    profile: str = "stub-jaccard",
    include_ai_tests: bool = True,
    timeout_sec: float = 3600,
) -> dict:
    """Run the RAG quality evaluation Gradle test and return the artifact."""
    gradlew = str(REPO_ROOT / "scripts" / "lib" / "tool-invoker.cjs")
    args = [
        "node", gradlew, "gradle",
        ":modules:system-tests:integrationTest",
        "--tests", "*RagQualityEvalTest",
    ]
    if include_ai_tests:
        args.append("-PincludeAiTests=true")
    if profile:
        args.append(f"-PragEvalProfile={profile}")

    log.info("Running RAG eval (profile=%s)...", profile)
    result = subprocess.run(
        args, capture_output=True, text=True,
        timeout=timeout_sec, cwd=str(REPO_ROOT),
    )
    if result.returncode != 0:
        log.error("RAG eval failed: %s", result.stderr[:500])
        return {"error": f"Gradle test failed (exit {result.returncode})"}

    # Find the artifact JSON
    artifact_dir = REPO_ROOT / "build" / "test-results" / "rag-eval"
    if not artifact_dir.is_dir():
        artifact_dir = (
            REPO_ROOT / "modules" / "system-tests" / "build"
            / "test-results" / "rag-eval"
        )

    artifact = _find_rag_artifact(artifact_dir)
    if artifact is None:
        return {"error": f"No RAG eval artifact found in {artifact_dir}"}
    return artifact


def _find_rag_artifact(search_dir: Path) -> dict | None:
    """Find and parse the RAG eval artifact JSON."""
    if not search_dir.is_dir():
        return None
    for f in sorted(search_dir.glob("*.json"), reverse=True):
        try:
            data = json.loads(f.read_text(encoding="utf-8"))
            if "aggregate" in data or "fact_coverage_mean" in data:
                return data
        except (json.JSONDecodeError, OSError):
            continue
    return None


def diff_rag_eval(baseline: dict, candidate: dict) -> dict:
    """Compare 7 RAG metrics using ratio-based gates."""
    b_agg = baseline.get("aggregate", baseline)
    c_agg = candidate.get("aggregate", candidate)

    comparisons: list[dict] = []
    for metric, direction, threshold in RAG_METRICS:
        b_val = b_agg.get(metric)
        c_val = c_agg.get(metric)
        if b_val is None or c_val is None:
            comparisons.append({
                "metric": metric, "status": "SKIP",
                "baseline": b_val, "candidate": c_val, "ratio": None,
            })
            continue

        lower_is_better = direction == "max"
        kwargs = {"lower_is_better": lower_is_better}
        if lower_is_better:
            kwargs["max_ratio"] = threshold
        else:
            kwargs["min_ratio"] = threshold
        comp = diff_gate.compare_ratio(b_val, c_val, **kwargs)
        comp["metric"] = metric
        comparisons.append(comp)

    return diff_gate.build_gate_decision(comparisons)


def format_console(decision: dict) -> str:
    """Human-readable output for RAG eval diff."""
    lines = [f"RAG Eval Gate: {decision['gate_status'].upper()}"]
    for c in decision.get("comparisons", []):
        status = c["status"]
        ratio = f"{c['ratio']:.4f}" if c.get("ratio") is not None else "N/A"
        lines.append(f"  {c.get('metric', '?'):<30s}  {status:<10s}  ratio={ratio}")
    return "\n".join(lines)
