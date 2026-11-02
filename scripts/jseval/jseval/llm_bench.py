"""LLM inference benchmark — TTFT, E2E latency, token throughput via SSE."""

from __future__ import annotations

import json
import logging
import time

import httpx

from jsharness import suite_stats

log = logging.getLogger(__name__)

PROFILES: dict[str, dict] = {
    "smoke":      {"doc_count": 5,  "warmup": 1, "timed": 2,  "timeout_ms": 30000},
    "regression": {"doc_count": 10, "warmup": 2, "timed": 5,  "timeout_ms": 30000},
    "full":       {"doc_count": 20, "warmup": 3, "timed": 10, "timeout_ms": 60000},
}


def discover_doc_ids(client: httpx.Client, limit: int) -> list[str]:
    """Discover indexed document IDs via search."""
    body = {"query": "*:*", "limit": limit * 2}
    resp = client.post("/api/knowledge/search", json=body)
    resp.raise_for_status()
    hits = resp.json().get("results", [])
    return [h["id"] for h in hits if "id" in h][:limit]


def stream_summarize(
    client: httpx.Client,
    doc_ids: list[str],
    timeout_ms: int,
) -> dict:
    """POST /api/chat/batch-summarize, parse SSE, return timing.

    Tempdoc 491 C2.2 migrated /api/summarize/batch/stream → /api/chat/batch-summarize.
    The substrate-driven BatchSummarizeShape concatenates docs and streams the summary.
    Body field renamed: documentIds → docIds (matches BatchDocAccess injector schema).
    """
    body = {"docIds": doc_ids}
    timeout_sec = timeout_ms / 1000

    start = time.perf_counter()
    ttft_ms = None
    chunk_count = 0
    success = False
    error_msg = None
    completion_tokens = None
    prompt_tokens = None
    total_tokens = None

    try:
        with client.stream(
            "POST", "/api/chat/batch-summarize",
            json=body,
            headers={"Accept": "text/event-stream"},
            timeout=timeout_sec,
        ) as response:
            response.raise_for_status()
            current_event = None
            for line in response.iter_lines():
                if line.startswith("event:"):
                    current_event = line[6:].strip()
                elif line.startswith("data:") and current_event:
                    data_str = line[5:].strip()
                    if current_event == "chunk":
                        chunk_count += 1
                        if ttft_ms is None:
                            ttft_ms = (time.perf_counter() - start) * 1000
                    elif current_event == "done":
                        success = True
                        try:
                            payload = json.loads(data_str)
                            # The chat `done` event emits promptTokens/totalTokens FLAT (ConversationEngine
                            # mergedDoneEntries, tempdoc 640 D); some shapes nest them under `usage`. Read
                            # flat first, fall back to usage, then derive completion = total - prompt (the
                            # done-event carries prompt+total but not completion).
                            usage = payload.get("usage") or {}
                            prompt_tokens = payload.get("promptTokens", usage.get("promptTokens"))
                            total_tokens = payload.get("totalTokens", usage.get("totalTokens"))
                            completion_tokens = payload.get("completionTokens", usage.get("completionTokens"))
                            if (completion_tokens is None
                                    and isinstance(total_tokens, (int, float))
                                    and isinstance(prompt_tokens, (int, float))):
                                completion_tokens = total_tokens - prompt_tokens
                        except (json.JSONDecodeError, AttributeError):
                            pass
                        break
                    elif current_event == "error":
                        error_msg = data_str
                        break
    except (httpx.HTTPError, httpx.TimeoutException) as e:
        error_msg = str(e)

    e2e_ms = (time.perf_counter() - start) * 1000
    stream_duration_ms = (e2e_ms - ttft_ms) if ttft_ms is not None else None

    token_rate = None
    if success and completion_tokens and stream_duration_ms and stream_duration_ms > 0:
        token_rate = completion_tokens / (stream_duration_ms / 1000)

    return {
        "e2e_ms": round(e2e_ms, 1),
        "ttft_ms": round(ttft_ms, 1) if ttft_ms is not None else None,
        "chunk_count": chunk_count,
        "success": success,
        "error": error_msg,
        "completion_tokens": completion_tokens,
        "prompt_tokens": prompt_tokens,
        "total_tokens": total_tokens,
        "token_rate_tps": round(token_rate, 1) if token_rate is not None else None,
    }


def execute_llm_bench(
    base_url: str,
    profile: str = "regression",
    timeout: float = 60.0,
) -> dict:
    """Run LLM inference benchmark with warmup + timed requests."""
    cfg = PROFILES[profile]
    doc_count = cfg["doc_count"]
    warmup_count = cfg["warmup"]
    timed_count = cfg["timed"]
    timeout_ms = cfg["timeout_ms"]

    with httpx.Client(base_url=base_url, timeout=timeout) as client:
        # Check inference availability
        try:
            status = client.get("/api/inference/status").json()
            if not status.get("available", False):
                log.warning("AI inference not available: %s", status)
        except Exception as e:
            log.warning("Could not check inference status: %s", e)

        # Discover doc IDs
        doc_ids = discover_doc_ids(client, doc_count)
        if not doc_ids:
            return {"error": "No documents found for summarization"}
        log.info("Discovered %d doc IDs for summarization", len(doc_ids))

        # Warmup
        for i in range(warmup_count):
            log.info("Warmup %d/%d", i + 1, warmup_count)
            stream_summarize(client, doc_ids[:1], timeout_ms)

        # Timed runs
        timings: list[dict] = []
        for i in range(timed_count):
            log.info("Timed request %d/%d", i + 1, timed_count)
            result = stream_summarize(client, doc_ids, timeout_ms)
            timings.append(result)

    # Compute aggregate metrics
    return _compute_metrics(timings, cfg)


def _compute_metrics(timings: list[dict], cfg: dict) -> dict:
    """Compute percentile metrics from timed request results."""
    successful = [t for t in timings if t["success"]]
    e2e_values = sorted(t["e2e_ms"] for t in successful)
    ttft_values = sorted(t["ttft_ms"] for t in successful if t["ttft_ms"] is not None)
    token_rates = [t["token_rate_tps"] for t in successful if t["token_rate_tps"] is not None]

    metrics: dict = {
        "profile": cfg,
        "request_count": len(timings),
        "success_count": len(successful),
        "success_rate": len(successful) / len(timings) if timings else 0,
        "error_count": sum(1 for t in timings if not t["success"]),
    }

    if e2e_values:
        metrics["e2e_latency_p50_ms"] = suite_stats.percentile(e2e_values, 50)
        metrics["e2e_latency_p95_ms"] = suite_stats.percentile(e2e_values, 95)
        metrics["e2e_latency_p99_ms"] = suite_stats.percentile(e2e_values, 99)

    if ttft_values:
        metrics["ttft_p50_ms"] = suite_stats.percentile(ttft_values, 50)
        metrics["ttft_p95_ms"] = suite_stats.percentile(ttft_values, 95)
        metrics["ttft_p99_ms"] = suite_stats.percentile(ttft_values, 99)

    if token_rates:
        metrics["token_rate_median_tps"] = suite_stats.percentile(sorted(token_rates), 50)

    return metrics


def format_console(result: dict) -> str:
    """Human-readable output for LLM bench results."""
    lines: list[str] = []
    if "error" in result:
        return f"LLM Bench error: {result['error']}"

    lines.append(f"LLM Bench: {result.get('request_count', 0)} requests, "
                 f"{result.get('success_count', 0)} successful")

    if "e2e_latency_p50_ms" in result:
        lines.append(f"  E2E latency: p50={result['e2e_latency_p50_ms']:.0f}ms  "
                     f"p95={result.get('e2e_latency_p95_ms', 0):.0f}ms  "
                     f"p99={result.get('e2e_latency_p99_ms', 0):.0f}ms")
    if "ttft_p50_ms" in result:
        lines.append(f"  TTFT:        p50={result['ttft_p50_ms']:.0f}ms  "
                     f"p95={result.get('ttft_p95_ms', 0):.0f}ms  "
                     f"p99={result.get('ttft_p99_ms', 0):.0f}ms")
    if "token_rate_median_tps" in result:
        lines.append(f"  Token rate:  {result['token_rate_median_tps']:.1f} tok/s (median)")

    lines.append(f"  Success rate: {result.get('success_rate', 0):.1%}")
    return "\n".join(lines)
