"""Gradle-based benchmarks — engine indexing (Claim A) and filtered kNN (Track G)."""

from __future__ import annotations

import json
import logging
import subprocess
from pathlib import Path

from . import suite_stats
from ._paths import DEFAULT_BENCH_CLAIM_A, DEFAULT_BENCH_TRACK_G, REPO_ROOT

log = logging.getLogger(__name__)

_ENGINE_METRICS = ["time_to_searchable_ms", "docs_per_s", "mb_per_s"]


def run_gradle_task(
    task: str,
    props: dict[str, str],
    *,
    timeout_sec: float = 600,
    quiet: bool = True,
) -> dict:
    """Run a Gradle benchmark task and return the parsed result.json."""
    gradlew = str(REPO_ROOT / "scripts" / "lib" / "tool-invoker.cjs")
    args = ["node", gradlew, "gradle", task]
    for k, v in props.items():
        args.append(f"-P{k}={v}")
    if quiet:
        args.append("--quiet")

    log.info("Running: %s %s", task, " ".join(f"-P{k}={v}" for k, v in props.items()))
    result = subprocess.run(
        args, capture_output=True, text=True,
        timeout=timeout_sec, cwd=str(REPO_ROOT),
    )
    if result.returncode != 0:
        raise RuntimeError(
            f"Gradle {task} failed (exit {result.returncode}): "
            f"{result.stderr[:500]}"
        )

    out_dir = Path(props.get("benchOutDir", ""))
    result_json = out_dir / "result.json"
    if not result_json.is_file():
        raise FileNotFoundError(f"Gradle task did not produce {result_json}")
    return json.loads(result_json.read_text(encoding="utf-8"))


# ---------------------------------------------------------------------------
# Claim A — engine-only indexing
# ---------------------------------------------------------------------------

def execute_engine_bench(
    corpus: Path,
    *,
    runs: int = 5,
    out_dir: Path | None = None,
) -> dict:
    """Run Claim A engine-only indexing benchmark."""
    base = out_dir or DEFAULT_BENCH_CLAIM_A
    results: list[dict] = []
    for i in range(1, runs + 1):
        log.info("Engine bench run %d/%d", i, runs)
        run_out = base / f"run-{i}"
        run_out.mkdir(parents=True, exist_ok=True)
        result = run_gradle_task(
            ":modules:benchmarks:engineIndexBench",
            {
                "benchCorpus": str(corpus.resolve()),
                "benchOutDir": str(run_out.resolve()),
            },
        )
        results.append(result)

    return {
        "claim": "A",
        "total_runs": runs,
        "runs": results,
        "statistics": suite_stats.summarize_suite(results, _ENGINE_METRICS),
    }


# ---------------------------------------------------------------------------
# Track G — filtered kNN latency
# ---------------------------------------------------------------------------

def execute_knn_bench(
    *,
    doc_counts: list[int] | None = None,
    chunk_doc_counts: list[int] | None = None,
    vector_dim: int = 768,
    k: int = 10,
    warmup: int = 5,
    iterations: int = 50,
    repeats: int = 1,
    skip_quantization: bool = False,
    out_dir: Path | None = None,
) -> dict:
    """Run Track G filtered kNN benchmark suite."""
    doc_counts = doc_counts or [20000, 200000]
    chunk_doc_counts = chunk_doc_counts or [200000, 500000]
    base = out_dir or DEFAULT_BENCH_TRACK_G
    cases: list[dict] = []

    for n in doc_counts:
        case = _run_knn_case(
            f"doc-{n}", n, chunk_mode=False, repeats=repeats,
            vector_dim=vector_dim, k=k, warmup=warmup,
            iterations=iterations, base_dir=base,
        )
        cases.append(case)

    for n in chunk_doc_counts:
        case = _run_knn_case(
            f"chunk-{n}", n, chunk_mode=True, repeats=repeats,
            vector_dim=vector_dim, k=k, warmup=warmup,
            iterations=iterations, base_dir=base,
        )
        cases.append(case)

    if not skip_quantization:
        qg = _run_quantization_gate(vector_dim, k, base)
        cases.append(qg)

    return {"track": "G", "cases": cases}


def _run_knn_case(
    case_id: str,
    doc_count: int,
    chunk_mode: bool,
    repeats: int,
    **kwargs,
) -> dict:
    """Run a single kNN case with optional repeats, pick median."""
    repeat_results: list[dict] = []
    for r in range(repeats):
        run_dir = kwargs["base_dir"] / case_id / f"repeat-{r}"
        run_dir.mkdir(parents=True, exist_ok=True)
        props: dict[str, str] = {
            "benchDocCount": str(doc_count),
            "benchVectorDim": str(kwargs["vector_dim"]),
            "benchK": str(kwargs["k"]),
            "benchWarmup": str(kwargs["warmup"]),
            "benchIterations": str(kwargs["iterations"]),
            "benchDocIdInSizes": "10,100,1000,10000",
            "benchPathPrefix": "d:/bench/folder0/",
            "benchOutDir": str(run_dir.resolve()),
        }
        if chunk_mode:
            props["benchChunkMode"] = "true"
            props["benchChunksPerParent"] = "10"

        log.info("kNN case %s repeat %d/%d", case_id, r + 1, repeats)
        result = run_gradle_task(":modules:benchmarks:filteredKnnBench", props)
        repeat_results.append(result)

    return _select_median_repeat(case_id, repeat_results)


def _run_quantization_gate(
    vector_dim: int, k: int, base_dir: Path,
) -> dict:
    """Run scalar quantization compatibility gate."""
    run_dir = base_dir / "quantization-gate"
    run_dir.mkdir(parents=True, exist_ok=True)
    log.info("Running quantization gate")
    result = run_gradle_task(
        ":modules:benchmarks:quantizationGate",
        {
            "benchDocCount": "5000",
            "benchVectorDim": str(vector_dim),
            "benchK": str(k),
            "benchKeepIndex": "false",
            "benchOutDir": str(run_dir.resolve()),
        },
    )
    return {"case_id": "quantization-gate", "ok": True, "result": result}


def _select_median_repeat(case_id: str, results: list[dict]) -> dict:
    """Select the repeat closest to the median worst_p95_ms."""
    if len(results) == 1:
        return {"case_id": case_id, "ok": True, "result": results[0]}

    # Extract worst_p95_ms from each repeat's scenario results
    p95_values: list[float] = []
    for r in results:
        scenarios = r.get("scenarios", [])
        if scenarios:
            worst = max(s.get("p95_ms", 0) for s in scenarios)
        else:
            worst = r.get("worst_p95_ms", 0)
        p95_values.append(worst)

    median_p95 = sorted(p95_values)[len(p95_values) // 2]
    best_idx = min(range(len(p95_values)), key=lambda i: abs(p95_values[i] - median_p95))

    return {
        "case_id": case_id,
        "ok": True,
        "result": results[best_idx],
        "selected_repeat": best_idx,
        "repeat_count": len(results),
    }


def format_engine_console(result: dict) -> str:
    """Human-readable output for engine bench."""
    stats = result.get("statistics", {})
    lines = [f"Engine Bench (Claim A): {result.get('total_runs', 0)} runs"]
    dps = stats.get("docs_per_s", {})
    if dps.get("median") is not None:
        lines.append(f"  docs/s:             median={dps['median']}  "
                     f"min={dps['min']}  max={dps['max']}")
    tts = stats.get("time_to_searchable_ms", {})
    if tts.get("median") is not None:
        lines.append(f"  time_to_searchable: median={tts['median']}ms  "
                     f"min={tts['min']}ms  max={tts['max']}ms")
    return "\n".join(lines)


def format_knn_console(result: dict) -> str:
    """Human-readable output for kNN bench."""
    lines = [f"kNN Bench (Track G): {len(result.get('cases', []))} cases"]
    for case in result.get("cases", []):
        r = case.get("result", {})
        worst = r.get("worst_p95_ms", "?")
        lines.append(f"  {case['case_id']}: worst_p95={worst}ms")
    return "\n".join(lines)
