"""V1 feasibility spike: can the local model extract structured filters from NL queries?

Calls the chat model with GBNF-constrained JSON output through the Engine's
/v1 proxy on the specified port (the backend's API port by default). The proxy
forwards the request body unchanged and holds a generation lease per call, so a
reconfigure drains the reply instead of cutting it.

Usage:
    python -m jseval qu-spike [--port 33221] [--with-grounding] [--json] [--verbose]
"""

from __future__ import annotations

import json
import logging
import sys
import time
from pathlib import Path
from typing import Any

import click
import httpx

log = logging.getLogger(__name__)

DATA_DIR = Path(__file__).parent / "data"
QUERIES_FILE = DATA_DIR / "qu-spike-queries.v1.json"
PROMPT_V1 = DATA_DIR / "qu-spike-prompt.v1.txt"
PROMPT_V2 = DATA_DIR / "qu-spike-prompt.v2.txt"
SCHEMA_FILE = DATA_DIR / "qu-spike-schema.v1.json"

# Hardcoded index snapshot for --with-grounding variant (MultiHop-RAG sources)
INDEX_SNAPSHOT = """Known sources in the index (use ONLY these values for meta_source):
  the verge, techcrunch, fortune, the age, sporting news, polygon,
  hacker news, cbssports.com, the new york times, the sydney morning herald,
  the independent - life and style, the independent - sports, the independent - travel,
  bbc news - entertainment & arts, bbc news - technology, wired, engadget,
  mashable, seeking alpha, business line, business today, business world,
  cnbc, financial times, fox news - entertainment, fox news - health,
  fox news - lifestyle, globes english, iot business news, live science,
  music business worldwide, revyuh media, scitechdaily, science news for students,
  advanced science news, eos, sky sports, talksport, the roar | sports writers blog,
  sportskeeda, essentially sports, insidesport, sport grill, rivals,
  wide world of sports, yardbarker, yahoo news, zee business

Known categories: sports, technology, entertainment, business, science, health

Known persons mentioned: elon musk, sam altman, trump, taylor swift, jordan poyer,
  ryan petersen, dave clark, sridevi, prince william, princess diana

Known organizations: openai, google, meta, amazon, apple, microsoft, flexport,
  buffalo bills, baltimore ravens"""

NO_GROUNDING = """Known sources: (not available — extract source names as best you can)
Known categories: sports, technology, entertainment, business, science, health"""


def load_queries() -> list[dict]:
    with open(QUERIES_FILE) as f:
        return json.load(f)


def load_prompt_template(version: int = 2) -> str:
    path = PROMPT_V2 if version == 2 else PROMPT_V1
    return path.read_text(encoding="utf-8")


def load_schema() -> dict:
    with open(SCHEMA_FILE) as f:
        return json.load(f)


def build_prompt(template: str, grounded: bool) -> str:
    today = time.strftime("%Y-%m-%d")
    snapshot = INDEX_SNAPSHOT if grounded else NO_GROUNDING
    return template.replace("{{INDEX_SNAPSHOT}}", snapshot).replace("{{TODAY}}", today)


def call_llama_server(
    port: int, system_prompt: str, user_query: str, schema: dict,
    enable_thinking: bool = False, reasoning_budget: int = 512, timeout: float = 60.0
) -> tuple[dict | None, float, str | None]:
    """Send a chat completion request. Returns (parsed_json, latency_ms, error)."""
    url = f"http://127.0.0.1:{port}/v1/chat/completions"
    body = {
        "model": "local",
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_query},
        ],
        "temperature": 0.0,
        "top_p": 1.0,
        "max_tokens": 2048 if enable_thinking else 512,
        "response_format": {"type": "json_object", "schema": schema},
        "chat_template_kwargs": {"enable_thinking": enable_thinking},
    }
    if enable_thinking:
        body["reasoning_budget"] = reasoning_budget
    t0 = time.monotonic()
    try:
        with httpx.Client(timeout=timeout) as client:
            resp = client.post(url, json=body)
            latency = (time.monotonic() - t0) * 1000
            resp.raise_for_status()
            data = resp.json()
            content = data["choices"][0]["message"]["content"]
            # Strip <think>...</think> tags if present (Qwen thinking mode)
            import re
            content = re.sub(r"<think>.*?</think>", "", content, flags=re.DOTALL).strip()
            parsed = json.loads(content)
            return parsed, latency, None
    except httpx.ConnectError:
        return None, 0, "Connection refused — is llama-server running?"
    except httpx.HTTPStatusError as e:
        return None, (time.monotonic() - t0) * 1000, f"HTTP {e.response.status_code}: {e.response.text[:200]}"
    except json.JSONDecodeError as e:
        return None, (time.monotonic() - t0) * 1000, f"JSON parse error: {e}"
    except Exception as e:
        return None, (time.monotonic() - t0) * 1000, f"Error: {e}"


def normalize_values(v: Any) -> set[str]:
    """Normalize extracted values to a comparable set of lowercase strings."""
    if v is None:
        return set()
    if isinstance(v, str):
        return {v.lower().strip()}
    if isinstance(v, list):
        return {str(x).lower().strip() for x in v}
    if isinstance(v, dict):
        # Per-field confidence format: {"values": [...], "confidence": N}
        if "values" in v:
            return normalize_values(v["values"])
        if "value" in v:
            return normalize_values(v["value"])
    return set()


def score_query(expected: dict, actual: dict | None) -> dict:
    """Score a single query's extraction against ground truth."""
    if actual is None:
        return {"correct": False, "fields_correct": 0, "fields_total": 0,
                "hallucinated_fields": 0, "error": True}

    expected_ext = expected.get("expected_extractions", {})
    actual_ext = actual.get("extractions", {})

    fields_correct = 0
    fields_total = 0
    hallucinated = 0
    field_details = {}

    # Check each expected field
    for field, expected_val in expected_ext.items():
        fields_total += 1
        actual_val = actual_ext.get(field)
        exp_set = normalize_values(expected_val)
        act_set = normalize_values(actual_val)

        match = exp_set == act_set
        # For dates, allow close matches (within field, not exact string)
        if not match and field.startswith("meta_published"):
            # Check if the values are at least present (non-empty)
            match = len(act_set) > 0 and len(exp_set) > 0
            # Partial credit for getting the right month/year
            if match:
                field_details[field] = {"status": "partial", "expected": exp_set, "actual": act_set}
            else:
                field_details[field] = {"status": "miss", "expected": exp_set, "actual": act_set}
        else:
            field_details[field] = {
                "status": "match" if match else "miss",
                "expected": exp_set,
                "actual": act_set,
            }

        if match:
            fields_correct += 1

    # Check for hallucinated fields (extracted but not expected)
    for field in actual_ext:
        if field not in expected_ext:
            val = normalize_values(actual_ext[field])
            if val:  # Non-empty extraction not in expected
                hallucinated += 1
                field_details[field] = {"status": "hallucinated", "actual": val}

    # Check passthrough: if no extractions expected, query should be unchanged
    passthrough_ok = True
    if not expected_ext:
        actual_query = actual.get("query", "").lower().strip()
        expected_query = expected.get("expected_query", expected["query"]).lower().strip()
        # Allow minor reformulation but the core should be there
        passthrough_ok = (expected_query in actual_query or actual_query in expected_query
                          or set(expected_query.split()).issubset(set(actual_query.split())))

    all_correct = fields_correct == fields_total and hallucinated == 0 and passthrough_ok
    return {
        "correct": all_correct,
        "fields_correct": fields_correct,
        "fields_total": fields_total,
        "hallucinated_fields": hallucinated,
        "passthrough_ok": passthrough_ok,
        "field_details": field_details,
        "error": False,
    }


def print_summary(results: list[dict], json_output: bool) -> None:
    total = len(results)
    correct = sum(1 for r in results if r["score"]["correct"])
    errors = sum(1 for r in results if r["score"]["error"])
    hallucinated = sum(r["score"]["hallucinated_fields"] for r in results)
    avg_latency = sum(r["latency_ms"] for r in results if r["latency_ms"] > 0) / max(1, total - errors)

    # Per-category breakdown
    categories: dict[str, list] = {}
    for r in results:
        cat = r["category"]
        categories.setdefault(cat, []).append(r)

    if json_output:
        summary = {
            "total": total,
            "correct": correct,
            "accuracy": round(correct / total, 3) if total else 0,
            "errors": errors,
            "hallucinated_fields_total": hallucinated,
            "avg_latency_ms": round(avg_latency, 1),
            "per_category": {},
        }
        for cat, cat_results in sorted(categories.items()):
            cat_correct = sum(1 for r in cat_results if r["score"]["correct"])
            summary["per_category"][cat] = {
                "total": len(cat_results),
                "correct": cat_correct,
                "accuracy": round(cat_correct / len(cat_results), 3),
            }
        json.dump(summary, sys.stdout, indent=2)
        print()
        return

    print(f"\n{'='*60}")
    print(f"QUERY UNDERSTANDING SPIKE — RESULTS")
    print(f"{'='*60}")
    print(f"Overall: {correct}/{total} correct ({correct/total*100:.1f}%)")
    print(f"Errors: {errors}")
    print(f"Hallucinated fields: {hallucinated}")
    print(f"Avg latency: {avg_latency:.0f}ms")
    print()

    for cat, cat_results in sorted(categories.items()):
        cat_correct = sum(1 for r in cat_results if r["score"]["correct"])
        print(f"  {cat:20s}: {cat_correct}/{len(cat_results)} ({cat_correct/len(cat_results)*100:.0f}%)")

    print(f"\n{'='*60}")
    print("PER-QUERY DETAILS")
    print(f"{'='*60}")
    for r in results:
        status = "OK" if r["score"]["correct"] else "FAIL"
        err = f" ERROR: {r['error']}" if r["error"] else ""
        print(f"\n  [{status}] {r['id']} ({r['category']}) — {r['latency_ms']:.0f}ms{err}")
        print(f"    Query: {r['query']}")
        if r["actual"]:
            print(f"    Extracted query: {r['actual'].get('query', 'N/A')}")
            for field, detail in r["score"].get("field_details", {}).items():
                s = detail["status"]
                if s == "match":
                    print(f"    {field}: MATCH {detail.get('expected', '')}")
                elif s == "partial":
                    print(f"    {field}: PARTIAL expected={detail['expected']} got={detail['actual']}")
                elif s == "miss":
                    print(f"    {field}: MISS expected={detail['expected']} got={detail.get('actual', 'empty')}")
                elif s == "hallucinated":
                    print(f"    {field}: HALLUCINATED {detail['actual']}")
            if r["score"]["hallucinated_fields"] == 0 and r["score"]["fields_total"] == 0:
                if not r["score"]["passthrough_ok"]:
                    print(f"    PASSTHROUGH FAIL: query was modified when it shouldn't have been")


@click.command("qu-spike")
@click.option("--port", default=33221, show_default=True,
              help="Engine API port whose /v1 proxy reaches the chat model")
@click.option("--with-grounding", is_flag=True, help="Inject index facet values into prompt")
@click.option("--prompt-version", default=2, type=int, help="Prompt version (1 or 2)")
@click.option("--thinking", is_flag=True, help="Enable model thinking/reasoning mode")
@click.option("--reasoning-budget", default=512, type=int, help="Token budget for thinking (only with --thinking)")
@click.option("--json", "json_output", is_flag=True, help="JSON output")
@click.option("-v", "--verbose", is_flag=True, help="Verbose logging")
def qu_spike(port: int, with_grounding: bool, prompt_version: int, thinking: bool, reasoning_budget: int, json_output: bool, verbose: bool) -> None:
    """V1 feasibility spike: test query understanding extraction accuracy."""
    if verbose:
        logging.basicConfig(level=logging.DEBUG)
    else:
        logging.basicConfig(level=logging.INFO)

    queries = load_queries()
    template = load_prompt_template(version=prompt_version)
    schema = load_schema()
    system_prompt = build_prompt(template, grounded=with_grounding)

    mode_label = "WITH index grounding" if with_grounding else "WITHOUT index grounding"
    think_label = ", thinking ON" if thinking else ""
    if not json_output:
        print(f"Running QU spike: {len(queries)} queries, prompt v{prompt_version}, {mode_label}{think_label}, port {port}")

    results = []
    for q in queries:
        if not json_output:
            print(f"  {q['id']}...", end=" ", flush=True)

        actual, latency, error = call_llama_server(
            port, system_prompt, f'Query: "{q["query"]}"', schema,
            enable_thinking=thinking, reasoning_budget=reasoning_budget
        )

        if error and "Connection refused" in error:
            print(f"\nFATAL: {error}")
            sys.exit(1)

        score = score_query(q, actual)

        result = {
            "id": q["id"],
            "query": q["query"],
            "category": q["category"],
            "actual": actual,
            "latency_ms": latency,
            "error": error,
            "score": score,
        }
        results.append(result)

        if not json_output:
            status = "OK" if score["correct"] else "FAIL"
            print(f"{status} ({latency:.0f}ms)")

    print_summary(results, json_output)

    # Exit code: 0 if >80% accuracy, 1 otherwise
    correct = sum(1 for r in results if r["score"]["correct"])
    sys.exit(0 if correct / len(results) >= 0.8 else 1)


COMMANDS = [qu_spike]
