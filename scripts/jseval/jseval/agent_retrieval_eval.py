"""Agent retrieval eval — measure JustSearch retrieve-context quality on MultiHop-RAG.

Phase 1: Retrieval quality (no agent, deterministic, $0 cost).
  Calls retrieve-context REST API for each question, checks if ground truth
  evidence documents appear in the retrieved chunks.

Phase 2: Agent comparison (requires Claude Code CLI, costs API tokens).
  Runs Claude Code with/without JustSearch MCP tools, scores answers against
  ground truth. Three conditions: A (file tools only), B (file + JustSearch),
  C (JustSearch only).

``run_agent_eval`` (this module's former Phase-2 `claude -p` shell-out runner)
was RETIRED in tempdoc 675: the classic subprocess-based agent-eval runner is
dead, replaced by the in-process SDK executor
(``jseval.agent_utility_inspect.run_utility_eval``). Records intended for
publication come from that Inspect-AI path
(``jseval.agent_utility_inspect`` / ``jseval.agent_utility_run.eval_logs_to_summaries``).

This module now holds the surviving Tier-1/Tier-2 retrieval runners --
``run_retrieval_eval`` (Phase 1: deterministic retrieve-context quality, $0
cost) and ``run_tier2_eval`` (single-shot RAG eval against a local LLM, $0
cost) -- plus shared helpers used by the Inspect-AI runner
(``stage_corpus_dir``, ``build_disallowed_tools``,
``find_disallowed_tool_calls``, ``find_leak_suspect_tool_calls``,
``_score_answer``, ``load_queries``) and the console formatters for all of
the above.
"""

from __future__ import annotations

import copy
import hashlib
import json
import logging
import re
import shutil
import subprocess
import tempfile
import time
from collections import Counter
from dataclasses import dataclass, field, asdict
from pathlib import Path

import httpx

from jseval.chunk_completeness import iter_corpus_docs, resolve_chunk_threshold_chars
from jseval.judge_ceiling import served_model_name
from jseval.utility_calibrate import assert_watched_roots_scoped, base_url_from_mcp_config

log = logging.getLogger(__name__)

_DEFAULT_BASE_URL = "http://127.0.0.1:33221"

# Tempdoc 842 §2.5: quality numbers from a compact/dev-tier chat model must not
# be producible by accident -- they systematically poison quality baselines and
# are not comparable to standard-model results. Case-insensitive substring match
# against the served model name/path (jseval.judge_ceiling.served_model_name).
# Markers cover current + contemplated compact rungs (tempdoc 842 D1: "qwen3.5-2b"
# is the contemplated 2B fallback rung, not yet shipped).
COMPACT_MODEL_MARKERS = ("qwen3.5-4b", "qwen3-1.7b", "qwen3.5-2b")


class CompactModelNotAllowedError(RuntimeError):
    """Raised by :func:`run_tier2_eval` when the served chat model matches a
    ``COMPACT_MODEL_MARKERS`` entry (tempdoc 842 §2.5) and ``allow_compact_model``
    was not passed."""


class Tier2ComparisonError(ValueError):
    """Raised when two Tier-2 records cannot support a paired comparison."""


def stage_corpus_dir(corpus_dir: str, *, prefix: str = "jseval-corpus-stage-",
                     stable_key: str | None = None) -> str:
    """Copy `corpus_dir`'s contents into a fresh, answer-key-free temp directory.

    `--add-dir corpus_dir` hands the Claude Code CLI's Read/Glob tools a directory
    that is NOT sandboxed against `../` relative-path traversal: with `corpus_dir`
    scoped to `datasets/golden/<name>/corpus-dir/`, an agent can `Read
    ../queries.json` — the sibling gold answer key materialized by
    `corpus_build.build_golden` — and the CLI does not block it. This is a real,
    live-verified leak (tempdoc 624 §M.7a "As-built #7": real eval cells where the
    agent read the answer key directly).

    Staging a copy into its own isolated temp root instead of passing the
    persistent `corpus-dir/` makes `queries.json` structurally absent from
    anywhere reachable by traversal from the staged path — a construction-time
    guarantee, not a permission workaround. Used by the surviving Inspect-AI
    runner (`agent_utility_inspect.run_utility_eval`) -- the classic shell-out
    runner (`agent_retrieval_eval.run_agent_eval`) that formerly shared this
    helper was retired in tempdoc 675.

    `stable_key` (tempdoc 675 F0 resume fix): when given, stage to a DETERMINISTIC
    path keyed by a hash of `stable_key` (idempotent copy) instead of a random
    `mkdtemp`. The staged path is part of Inspect's `eval_set` task identity, so a
    resume must re-stage to the SAME path or the recomputed task-id won't match the
    persisted log (→ `PrerequisiteError`). Default (None) keeps the volatile per-call
    behavior for the other (Tier-1/2) callers.

    Callers own cleanup: `shutil.rmtree(Path(staged_dir).parent, ignore_errors=True)`.
    """
    if stable_key is not None:
        import hashlib
        digest = hashlib.sha256(str(stable_key).encode()).hexdigest()[:16]
        staging_root = str(Path(tempfile.gettempdir()) / f"{prefix}{digest}")
        staged_dir = str(Path(staging_root) / "corpus-dir")
        if not Path(staged_dir).exists():
            Path(staging_root).mkdir(parents=True, exist_ok=True)
            shutil.copytree(corpus_dir, staged_dir)
        return staged_dir
    staging_root = tempfile.mkdtemp(prefix=prefix)
    staged_dir = str(Path(staging_root) / "corpus-dir")
    shutil.copytree(corpus_dir, staged_dir)
    return staged_dir


@dataclass
class RetrievalResult:
    query: str
    answer: str
    question_type: str
    evidence_files: list[str]      # ground-truth article filenames
    retrieved_doc_ids: list[str] = field(default_factory=list)  # from chunk parent_doc_id
    context_tokens: int = 0
    chunks_included: int = 0
    chunks_considered: int = 0
    best_score: float = 0.0
    score_gap: float = 0.0
    coverage: float = 0.0
    retrieval_mode: str = ""
    evidence_found: int = 0
    evidence_total: int = 0
    answer_in_context: bool = False
    latency_ms: int = 0
    # Hits@K — was the first evidence doc found at rank <= K?
    hit_at_1: bool = False
    hit_at_3: bool = False
    hit_at_5: bool = False
    hit_at_10: bool = False
    reciprocal_rank: float = 0.0   # 1/rank of first evidence doc, 0 if not found


_ABSTENTION_PHRASES = [
    "insufficient information",
    "cannot find",
    "unable to find",
    "not found in",
    "no articles",
    "does not contain",
    "not present in",
    "could not find",
    "no relevant",
    "no documents",
    "doesn't exist",
    "does not exist",
    "not in the corpus",
    "not in the index",
]


def _score_answer(ground_truth: str, agent_answer: str) -> bool:
    """Score whether the agent's answer is correct.

    For null queries (ground truth = "Insufficient information."),
    accept any answer that indicates the agent correctly abstained
    (e.g., "I cannot find...", "unable to find...", "no articles...").
    For all other queries, use exact substring matching.
    """
    gt = ground_truth.lower().strip().rstrip(".")
    answer = agent_answer.lower()
    if gt == "insufficient information":
        return any(phrase in answer for phrase in _ABSTENTION_PHRASES)
    return gt in answer


@dataclass
class AgentResult:
    query: str
    answer: str
    question_type: str
    condition: str
    model: str
    agent_answer: str = ""
    correct: bool = False
    cost_usd: float = 0.0
    input_tokens: int = 0
    cache_creation_tokens: int = 0
    cache_read_tokens: int = 0
    output_tokens: int = 0
    num_turns: int = 0
    duration_ms: int = 0
    error: str = ""
    reflection: str = ""
    tool_calls: list = field(default_factory=list)  # [{tool, params_summary, response_summary}]
    # Empirical check of the CLI's own --disallowedTools enforcement (tempdoc 624
    # confidence pass): entries from tool_calls whose tool name was supposed to be
    # blocked for this condition but was invoked anyway. Empty = the flag held.
    disallowed_tool_calls: list = field(default_factory=list)
    # Answer-key leak backstop (tempdoc 624 §As-built #7): entries from tool_calls
    # whose path argument names the eval's own gold-answer file (queries.json).
    # A non-empty list means this cell's "correct" bit may reflect reading the
    # answer key rather than genuine retrieval, regardless of how clean the
    # accuracy/cost numbers otherwise look. Empty = no leak signal seen.
    leak_suspect_tool_calls: list = field(default_factory=list)


def load_queries(queries_path: Path) -> list[dict]:
    """Load MultiHop-RAG eval queries from JSON file."""
    with open(queries_path, encoding="utf-8") as f:
        return json.load(f)


def build_title_to_filename(corpus_dir: Path) -> dict[str, str]:
    """Build mapping from article title -> filename by reading frontmatter.

    Handles encoding quirks: smart quotes, trailing special chars, truncation.
    Returns dict like {"The FTX trial is bigger than Sam Bankman-Fried": "article_042.md"}.
    """
    import re
    title_to_file: dict[str, str] = {}
    for fn in sorted(corpus_dir.iterdir()):
        if fn.suffix != ".md":
            continue
        with open(fn, encoding="utf-8") as f:
            header = f.read(500)
        m = re.search(r'title:\s*"(.+?)"', header)
        if m:
            title_to_file[m.group(1).strip()] = fn.name
    log.info("Built title→filename mapping: %d articles", len(title_to_file))
    return title_to_file


def _normalize_title(title: str) -> str:
    """Normalize a title for fuzzy matching.

    Strips smart quotes, non-ASCII chars, and lowercases. Handles the
    MultiHop-RAG dataset's encoding quirks (curly quotes, ellipsis, etc.).
    """
    # Replace common smart quote variants with ASCII equivalents
    t = title.replace("\u201c", '"').replace("\u201d", '"')
    t = t.replace("\u2018", "'").replace("\u2019", "'")
    t = t.replace("\u2026", "...").replace("\ufffd", "")
    return t.encode("ascii", "ignore").decode().lower().strip()


def resolve_evidence_titles(query: dict) -> list[str]:
    """Extract unique evidence titles from a query's evidence_list.

    Returns normalized titles (lowercase, stripped) for matching against
    retrieved document parent_doc_id paths.
    """
    titles: list[str] = []
    for ev in query.get("evidence_list", []):
        title = ev.get("title", "").strip()
        if title and title not in titles:
            titles.append(title)
    return titles


def _doc_id_matches_title(doc_id: str, evidence_title: str) -> bool:
    """Check if a parent_doc_id path contains the evidence title.

    parent_doc_id is a full path like:
      d:\\code\\...\\the ftx trial is bigger than sam bankman-fried.txt
    evidence_title is like:
      The FTX trial is bigger than Sam Bankman-Fried

    We extract the filename (without extension), lowercase both, and
    check if the evidence title is a prefix of the filename or vice versa.
    """
    # Extract filename without extension from path
    fn = doc_id.replace("\\", "/").split("/")[-1]
    fn_stem = fn.rsplit(".", 1)[0] if "." in fn else fn
    fn_lower = fn_stem.lower().strip()
    ev_lower = evidence_title.lower().strip()

    # Exact match or prefix match (titles may be truncated in filenames)
    if fn_lower == ev_lower:
        return True
    if fn_lower.startswith(ev_lower[:60]) or ev_lower.startswith(fn_lower[:60]):
        return True
    return False


def _extract_title_from_path(path: str) -> str:
    """Extract filename from a full path."""
    return path.replace("\\", "/").split("/")[-1]


# ============================================================
# Phase 1: Retrieval quality (no agent)
# ============================================================


def rag_reachability_probe(
    corpus_path: str | Path,
    api_base_or_session: str | httpx.Client = _DEFAULT_BASE_URL,
    n: int = 10,
    top_k: int = 5,
    threshold_chars: int | None = None,
) -> dict:
    """Fail-closed check of the retrieval-completeness invariant (tempdoc 749): every
    sub-threshold (chunkless-by-construction) doc must still be reachable via the PRIMARY
    RAG path, not silently dropped by the ``IS_CHUNK:true`` chunk-retrieval filter.

    Samples the ``n`` shortest docs in ``corpus_path`` (a BEIR-format ``corpus.jsonl`` --
    see :func:`jseval.chunk_completeness.iter_corpus_docs`) whose materialized content length
    is < the backend's published chunk threshold -- these are the docs
    ``ChunkDocumentWriter`` writes zero chunk documents for (tempdoc 749's root cause), so a
    silent regression of the doc-level-union fix would make them invisible to
    ``/api/knowledge/retrieve-context`` again. Sample order is deterministic: ascending
    length, then doc id (ties broken lexically) -- so a fixed ``n`` samples the same docs
    run-to-run for a given corpus.

    For each sampled doc, probes with a scoped ``retrieve-context`` call (``doc_ids:
    [docid]``) using the doc's title as the question (falling back to the first 12
    whitespace-separated words of its text when the title is blank -- titles are usually a
    much better retrieval query than an arbitrary text prefix, but not every corpus doc has
    one). A doc PASSES iff the response's ``quality.retrieval_mode`` is not
    ``"FULLTEXT_FALLBACK"`` AND some returned chunk's ``parent_doc_id`` equals the doc id --
    i.e. the doc was actually found via the primary chunk-retrieval path, not the whole-doc
    BM25 fallback tempdoc 749 diagnosed as blind to this doc class.

    ``api_base_or_session`` accepts either a base URL (a fresh, self-closed
    ``httpx.Client(base_url=..., timeout=30.0)`` is created and used exactly like
    :func:`run_retrieval_eval`'s own client) or an already-open ``httpx.Client`` to reuse
    (e.g. ``run_retrieval_eval`` passing its own client so the probe doesn't open a second
    connection) -- the caller-supplied client is never closed here.

    ``threshold_chars`` is the chunk threshold to classify against. ``None`` (the default) reads
    it live from the backend's ``/api/status`` via the same client -- tempdoc 821 §3-C3 made the
    worker's enrichment auditor publish ``chunkMinChars``, retiring the jseval-side mirror of
    ``ChunkDocumentWriter.CHUNK_THRESHOLD_CHARS``. There is deliberately no local fallback
    constant (a fallback is the mirror again): a backend that does not publish it yields
    ``"not-applicable"``, the same never-blocking path an unreadable corpus takes.

    Returns ``{"sampled": int, "passed": int, "failed": [docid, ...], "verdict": "ok"|"fail"|
    "not-applicable"}``. ``"not-applicable"`` (not a failure) means there is no sub-threshold doc
    to check -- either ``corpus_path`` doesn't exist/is unreadable (mirrors
    :func:`jseval.chunk_completeness.expected_chunk_docs`'s graceful degradation for corpora
    with no local ``corpus.jsonl``, e.g. BEIR datasets materialized via ``ir_datasets``), every
    doc in it reaches the chunk threshold on its own, or the threshold itself is unavailable.
    """
    owns_client = isinstance(api_base_or_session, str)
    client: httpx.Client = (
        httpx.Client(base_url=api_base_or_session, timeout=30.0)
        if owns_client
        else api_base_or_session
    )

    if threshold_chars is None:
        try:
            status_resp = client.get("/api/status")
            status_resp.raise_for_status()
            threshold_chars = resolve_chunk_threshold_chars(status_resp.json())
        except Exception as e:
            log.warning("rag_reachability_probe: could not read /api/status: %s", e)
            threshold_chars = None

    docs = list(iter_corpus_docs(corpus_path))
    chunkless = (
        sorted(
            (d for d in docs if d.length < threshold_chars),
            key=lambda d: (d.length, d.doc_id),
        )
        if threshold_chars
        else []
    )
    if not chunkless:
        if owns_client:
            client.close()
        return {"sampled": 0, "passed": 0, "failed": [], "verdict": "not-applicable"}

    sample = chunkless[:n]

    failed: list[str] = []
    passed = 0
    try:
        for d in sample:
            title = d.title.strip()
            question = title if title else " ".join(d.text.split()[:12])
            try:
                # UNSCOPED on purpose: reachability means the doc surfaces via the primary
                # RAG path on its own content — scoping to a doc_id (a) defeats the test and
                # (b) can't be expressed here anyway, since the backend keys docs by ingest
                # PATH, not the corpus doc id.
                resp = client.post("/api/knowledge/retrieve-context", json={
                    "query": question,
                    "top_k": top_k,
                })
                resp.raise_for_status()
                data = resp.json()
            except Exception as e:
                log.warning("rag_reachability_probe: doc %s failed: %s", d.doc_id, e)
                failed.append(d.doc_id)
                continue

            quality = data.get("quality", {})
            retrieval_mode = quality.get("retrieval_mode", "")
            chunks = data.get("chunks", [])
            # parent_doc_id is a full ingest path (…/<doc_id>.txt), not the bare corpus
            # doc id, so compare by filename stem via the same path↔id matcher the Tier-1
            # metrics use — an exact `==` never matches a corpus-dir-ingested doc.
            reachable = (
                retrieval_mode != "FULLTEXT_FALLBACK"
                and any(
                    _doc_id_matches_title(c.get("parent_doc_id", ""), d.doc_id)
                    for c in chunks
                )
            )
            if reachable:
                passed += 1
            else:
                failed.append(d.doc_id)
    finally:
        if owns_client:
            client.close()

    return {
        "sampled": len(sample),
        "passed": passed,
        "failed": failed,
        "verdict": "fail" if failed else "ok",
    }


def run_retrieval_eval(
    queries: list[dict],
    base_url: str = _DEFAULT_BASE_URL,
    top_k: int = 10,
    max_tokens: int = 8192,
    question_types: list[str] | None = None,
    max_queries: int | None = None,
    corpus_dir: Path | None = None,  # kept for CLI compat, not used for matching
    corpus_jsonl: str | Path | None = None,
    skip_reachability: bool = False,
    reachability_n: int = 10,
    # Test reachability at the same depth the eval/product retrieves (command --top-k
    # default is 10); a stricter hidden default made the guard over-strict — a doc reachable
    # at the product's top_k could false-fail on a smaller over-retrieve pool.
    reachability_top_k: int = 10,
) -> dict:
    """Run Tier 1 retrieval eval against the retrieve-context REST API.

    Calls retrieve-context for each query, then checks:
    - Whether ground-truth evidence documents appear in retrieved chunks
      (matched by comparing evidence titles against parent_doc_id paths)
    - Whether the ground-truth answer string appears in the context
    - Hits@K (K=1,3,5,10) and MRR for evidence document retrieval

    Unless ``skip_reachability``, also runs :func:`rag_reachability_probe` (tempdoc 749) after
    the main loop and embeds its result under ``result["rag_reachability"]``. FAIL-CLOSED: a
    ``"fail"`` verdict adds/appends to ``result["error"]`` -- the same dict-embedded failure
    signal :func:`_aggregate_retrieval` already uses for "no results"/"all queries failed", so
    ``cmd_retrieval_eval`` (mirroring its sibling ``cmd_rag_eval``'s ``if "error" in candidate:
    sys.exit(1)``) turns it into a non-zero exit without a second failure-signaling mechanism.
    """
    client = httpx.Client(base_url=base_url, timeout=30.0)
    results: list[RetrievalResult] = []

    # Filter by question type if specified
    filtered = queries
    if question_types:
        filtered = [q for q in queries if q["question_type"] in question_types]
    if max_queries:
        filtered = filtered[:max_queries]

    log.info("Running retrieval eval: %d queries (top_k=%d, max_tokens=%d)",
             len(filtered), top_k, max_tokens)

    for i, q in enumerate(filtered):
        # Resolve evidence titles for this query
        evidence_titles = resolve_evidence_titles(q)

        start = time.monotonic()
        try:
            resp = client.post("/api/knowledge/retrieve-context", json={
                "query": q["query"],
                "top_k": top_k,
                "max_tokens": max_tokens,
            })
            resp.raise_for_status()
            data = resp.json()
        except Exception as e:
            log.warning("Query %d failed: %s", i, e)
            results.append(RetrievalResult(
                query=q["query"], answer=q["answer"],
                question_type=q["question_type"],
                evidence_files=evidence_titles,
                evidence_total=len(evidence_titles),
            ))
            continue

        latency = int((time.monotonic() - start) * 1000)
        quality = data.get("quality", {})
        chunks = data.get("chunks", [])
        context = data.get("context", "")

        # Extract unique parent_doc_ids from chunks, preserving rank order
        retrieved_doc_ids: list[str] = []
        for c in chunks:
            doc_id = c.get("parent_doc_id", "")
            if doc_id and doc_id not in retrieved_doc_ids:
                retrieved_doc_ids.append(doc_id)

        # Match evidence titles against retrieved doc IDs (by title in path)
        evidence_found = 0
        for ev_title in evidence_titles:
            if any(_doc_id_matches_title(did, ev_title) for did in retrieved_doc_ids):
                evidence_found += 1

        # Compute Hits@K and MRR: find rank of first evidence doc
        hit_at_1 = hit_at_3 = hit_at_5 = hit_at_10 = False
        reciprocal_rank = 0.0
        for rank_idx, doc_id in enumerate(retrieved_doc_ids):
            if any(_doc_id_matches_title(doc_id, ev) for ev in evidence_titles):
                rank = rank_idx + 1  # 1-indexed
                hit_at_1 = rank <= 1
                hit_at_3 = rank <= 3
                hit_at_5 = rank <= 5
                hit_at_10 = rank <= 10
                reciprocal_rank = 1.0 / rank
                break  # MRR uses first relevant result

        # Check if ground truth answer appears in context
        answer_in_context = q["answer"].lower() in context.lower()

        results.append(RetrievalResult(
            query=q["query"],
            answer=q["answer"],
            question_type=q["question_type"],
            evidence_files=evidence_titles,
            retrieved_doc_ids=retrieved_doc_ids,
            context_tokens=len(context) // 4,  # rough estimate
            chunks_included=quality.get("chunks_included", 0),
            chunks_considered=quality.get("chunks_considered", 0),
            best_score=quality.get("best_score", 0.0),
            score_gap=quality.get("score_gap", 0.0),
            coverage=quality.get("coverage", 0.0),
            retrieval_mode=quality.get("retrieval_mode", ""),
            evidence_found=evidence_found,
            evidence_total=len(evidence_titles),
            answer_in_context=answer_in_context,
            latency_ms=latency,
            hit_at_1=hit_at_1,
            hit_at_3=hit_at_3,
            hit_at_5=hit_at_5,
            hit_at_10=hit_at_10,
            reciprocal_rank=reciprocal_rank,
        ))

        if (i + 1) % 50 == 0 or (i + 1) == len(filtered):
            log.info("  %d/%d queries processed", i + 1, len(filtered))

    result = _aggregate_retrieval(results)

    if not skip_reachability:
        # tempdoc 749: fail-closed guard for the retrieval-completeness invariant. Reuses this
        # function's already-open `client` (same base_url, same HTTP mechanism) rather than
        # opening a second connection. `corpus_dir` is the MultiHop-RAG .md article directory
        # (no corpus.jsonl inside it, so the probe naturally reports "not-applicable" there,
        # never a false failure) -- `corpus_jsonl` is how a caller with an actual BEIR-format
        # corpus.jsonl (golden/mixed self-demo corpora) opts the guard in for real.
        reachability_corpus = (
            Path(corpus_jsonl) if corpus_jsonl is not None
            else (Path(corpus_dir) / "corpus.jsonl" if corpus_dir is not None else None)
        )
        if reachability_corpus is not None:
            reachability = rag_reachability_probe(
                reachability_corpus, client, n=reachability_n, top_k=reachability_top_k,
            )
        else:
            reachability = {"sampled": 0, "passed": 0, "failed": [], "verdict": "not-applicable"}

        result["rag_reachability"] = reachability
        if reachability["verdict"] == "fail":
            msg = (
                f"rag_reachability guard: {len(reachability['failed'])}/{reachability['sampled']} "
                f"sub-threshold (chunkless-by-construction) doc(s) not reachable via the primary "
                f"RAG chunk path: {reachability['failed']}"
            )
            result["error"] = f"{result['error']}; {msg}" if "error" in result else msg

    client.close()
    return result


def _aggregate_retrieval(results: list[RetrievalResult]) -> dict:
    """Aggregate retrieval results into summary metrics with Hits@K and MRR."""
    if not results:
        return {"error": "no results"}

    total = len(results)
    # Only compute evidence metrics on queries that have evidence files
    with_evidence = [r for r in results if r.evidence_total > 0]
    n_ev = len(with_evidence) if with_evidence else 1  # avoid div/0

    answer_in_context = sum(1 for r in results if r.answer_in_context)
    avg_tokens = sum(r.context_tokens for r in results) / total
    avg_chunks = sum(r.chunks_included for r in results) / total
    avg_latency = sum(r.latency_ms for r in results) / total
    avg_best_score = sum(r.best_score for r in results) / total
    avg_coverage = sum(r.coverage for r in results) / total

    # Standard retrieval metrics (only on queries with evidence)
    hits_at_1 = sum(1 for r in with_evidence if r.hit_at_1) / n_ev
    hits_at_3 = sum(1 for r in with_evidence if r.hit_at_3) / n_ev
    hits_at_5 = sum(1 for r in with_evidence if r.hit_at_5) / n_ev
    hits_at_10 = sum(1 for r in with_evidence if r.hit_at_10) / n_ev
    mrr = sum(r.reciprocal_rank for r in with_evidence) / n_ev
    avg_evidence_recall = (
        sum(r.evidence_found / r.evidence_total for r in with_evidence) / n_ev
    )

    # Per question type breakdown
    by_type: dict[str, list[RetrievalResult]] = {}
    for r in results:
        by_type.setdefault(r.question_type, []).append(r)

    type_summary = {}
    for qtype, typed_results in by_type.items():
        n = len(typed_results)
        typed_ev = [r for r in typed_results if r.evidence_total > 0]
        n_te = len(typed_ev) if typed_ev else 1
        type_summary[qtype] = {
            "count": n,
            "answer_in_context_rate": sum(1 for r in typed_results if r.answer_in_context) / n,
            "hits_at_1": sum(1 for r in typed_ev if r.hit_at_1) / n_te,
            "hits_at_5": sum(1 for r in typed_ev if r.hit_at_5) / n_te,
            "hits_at_10": sum(1 for r in typed_ev if r.hit_at_10) / n_te,
            "mrr": sum(r.reciprocal_rank for r in typed_ev) / n_te,
            "avg_evidence_recall": sum(r.evidence_found / r.evidence_total for r in typed_ev) / n_te if typed_ev else 0,
            "avg_context_tokens": sum(r.context_tokens for r in typed_results) / n,
            "avg_best_score": sum(r.best_score for r in typed_results) / n,
            "avg_latency_ms": sum(r.latency_ms for r in typed_results) / n,
        }

    return {
        "phase": "retrieval",
        "total_queries": total,
        "queries_with_evidence": len(with_evidence),
        "answer_in_context_rate": round(answer_in_context / total, 4),
        "hits_at_1": round(hits_at_1, 4),
        "hits_at_3": round(hits_at_3, 4),
        "hits_at_5": round(hits_at_5, 4),
        "hits_at_10": round(hits_at_10, 4),
        "mrr": round(mrr, 4),
        "avg_evidence_recall": round(avg_evidence_recall, 4),
        "avg_context_tokens": round(avg_tokens),
        "avg_chunks_included": round(avg_chunks, 1),
        "avg_latency_ms": round(avg_latency),
        "avg_best_score": round(avg_best_score, 3),
        "avg_coverage": round(avg_coverage, 3),
        "by_type": type_summary,
        "results": [asdict(r) for r in results],
    }


# ============================================================
# Tier 2: Single-shot RAG (retrieve + local LLM, $0 cost)
# ============================================================

_TIER2_SYSTEM_PROMPT = (
    "The context below is drawn from multiple documents and together contains "
    "the information needed to answer the question. Identify the relevant "
    "portions across all passages, synthesize them, and provide a direct answer. "
    "For yes/no questions, answer Yes or No. "
    "Only if after reviewing all passages the answer is genuinely absent, "
    "say \"Insufficient information\"."
)

# Paper's original prompt (from qa_llama.py in yixuantt/MultiHop-RAG repo).
# Used for ablation runs to isolate retrieval quality from prompt effects.
# Appended with structured format for observability (ANSWER/EVIDENCE/CONFIDENCE).
_TIER2_PAPER_PROMPT = (
    "Below is a question followed by some context from different sources. "
    "Please answer the question based on the context. The answer to the "
    "question is a word or entity. If the provided information is insufficient "
    "to answer the question, respond 'Insufficient Information'. "
    "Answer directly without explanation.\n\n"
    "Respond with EXACTLY this format:\n"
    "ANSWER: <your answer>\n"
    "EVIDENCE: <one sentence from the context that supports your answer>\n"
    "CONFIDENCE: high, medium, or low"
)

_TIER2_JSON_SCHEMA = {
    "type": "object",
    "properties": {
        "answer": {"type": "string"},
        "evidence_summary": {
            "type": "string",
            "description": "One sentence: what in the context supports this answer",
        },
        "confidence": {"type": "string", "enum": ["high", "medium", "low"]},
    },
    "required": ["answer", "evidence_summary", "confidence"],
    "additionalProperties": False,
}

_TIER2_PROTOCOL_ID = "tier2-evaluator.v1"
_TIER2_SCORING_VERSION = "exact-substring-has-intersection.v1"
_TIER2_RETRIEVE_PATH = "/api/knowledge/retrieve-context"
_TIER2_USER_MESSAGE_TEMPLATE_ID = "context-question.v1"
_TIER2_USER_MESSAGE_TEMPLATE = "Context:\n{context}\n\nQuestion: {question}"
_TIER2_LLM_MODEL = "local"
_TIER2_LLM_MAX_TOKENS = 512
_TIER2_LLM_TEMPERATURE = 0.1
_TIER2_LLM_LOGPROBS = True
_TIER2_LLM_TOP_LOGPROBS = 3
_TIER2_LLM_CHAT_TEMPLATE_KWARGS = {"enable_thinking": False}


def _canonical_sha256(value: object) -> str:
    encoded = json.dumps(
        value, sort_keys=True, separators=(",", ":"), ensure_ascii=False,
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _tier2_protocol_digest(definition: dict) -> str:
    """Digest a protocol definition using its canonical JSON representation."""
    return _canonical_sha256(definition)


def _tier2_evaluator_protocol(*, structured: bool, use_paper_prompt: bool) -> dict:
    """Describe and fingerprint every decision-bearing Tier-2 evaluator constant."""
    prompt_variant = "paper" if use_paper_prompt else "standard"
    prompt = _TIER2_PAPER_PROMPT if use_paper_prompt else _TIER2_SYSTEM_PROMPT
    uses_json_schema = structured and not use_paper_prompt
    definition = {
        "id": _TIER2_PROTOCOL_ID,
        "prompt_variant": prompt_variant,
        "prompt_sha256": hashlib.sha256(prompt.encode("utf-8")).hexdigest(),
        "scoring_version": _TIER2_SCORING_VERSION,
        "retrieval_request": {
            "path": _TIER2_RETRIEVE_PATH,
            "query_field": "query",
            "top_k_field": "top_k",
            "max_context_tokens_field": "max_tokens",
        },
        "llm_request": {
            "model": _TIER2_LLM_MODEL,
            "max_tokens": _TIER2_LLM_MAX_TOKENS,
            "temperature": _TIER2_LLM_TEMPERATURE,
            "logprobs": _TIER2_LLM_LOGPROBS,
            "top_logprobs": _TIER2_LLM_TOP_LOGPROBS,
            "chat_template_kwargs": dict(_TIER2_LLM_CHAT_TEMPLATE_KWARGS),
            "user_message_template": {
                "id": _TIER2_USER_MESSAGE_TEMPLATE_ID,
                "sha256": hashlib.sha256(
                    _TIER2_USER_MESSAGE_TEMPLATE.encode("utf-8")).hexdigest(),
            },
            "response_mode": "json_schema" if uses_json_schema else "plain_text",
            "response_schema_sha256": (
                _canonical_sha256(_TIER2_JSON_SCHEMA) if uses_json_schema else None),
        },
    }
    return {
        "id": _TIER2_PROTOCOL_ID,
        "digest_sha256": _tier2_protocol_digest(definition),
        "definition": definition,
    }

_TIER2_ABSTENTION = [
    "insufficient information", "cannot find", "not found",
    "does not contain", "not present", "could not find",
    "no relevant", "cannot determine", "not mentioned",
    "no information", "not enough information", "unable to determine",
    "unable to find", "not available", "cannot answer",
]


@dataclass
class Tier2Result:
    query: str
    answer: str  # ground truth
    question_type: str
    llm_answer: str = ""
    evidence_summary: str = ""
    confidence: str = ""
    correct_exact: bool = False
    correct_substring: bool = False
    correct_has_intersection: bool = False  # paper's raw scoring
    retrieval_chunks: int = 0
    included_anchors: list[dict] = field(default_factory=list)
    included_anchor_count: int = 0
    anchor_evidence_error: str = "anchor evidence not captured"
    context_tokens: int = 0
    answer_in_context: bool = False
    context_truncated: bool = False
    latency_retrieve_ms: int = 0
    latency_llm_ms: int = 0
    completion_tokens: int = 0
    error: str = ""


def _extract_tier2_anchor_evidence(data: object) -> tuple[list[dict], int, int, str]:
    """Extract ordered anchor identities and validate their producer count.

    The structured pair stays structured throughout.  Joining a path and
    ordinal into one string would be ambiguous for Windows drive paths and is
    unnecessary because JSON already represents the identity directly.

    Returns ``(anchors, anchor_count, retrieval_chunks, error)``.  Callers keep
    the diagnostic record on malformed responses, but paired comparison fails
    closed whenever ``error`` is non-empty.
    """
    if not isinstance(data, dict):
        return [], 0, 0, "retrieve-context response is not an object"

    quality = data.get("quality")
    if not isinstance(quality, dict):
        return [], 0, 0, "retrieve-context response is missing quality evidence"
    retrieval_chunks = quality.get("chunks_included")
    if (not isinstance(retrieval_chunks, int) or isinstance(retrieval_chunks, bool)
            or retrieval_chunks < 0):
        return [], 0, 0, "quality.chunks_included must be a non-negative integer"

    raw_anchors = data.get("chunks")
    if not isinstance(raw_anchors, list):
        return [], 0, retrieval_chunks, "retrieve-context response is missing the chunks array"

    anchors: list[dict] = []
    for index, raw_anchor in enumerate(raw_anchors):
        if not isinstance(raw_anchor, dict):
            return [], 0, retrieval_chunks, f"chunks[{index}] must be an object"
        parent_doc_id = raw_anchor.get("parent_doc_id")
        chunk_index = raw_anchor.get("chunk_index")
        if not isinstance(parent_doc_id, str) or not parent_doc_id:
            return [], 0, retrieval_chunks, (
                f"chunks[{index}].parent_doc_id must be a non-empty string")
        if (not isinstance(chunk_index, int) or isinstance(chunk_index, bool)
                or chunk_index < -1):
            return [], 0, retrieval_chunks, (
                f"chunks[{index}].chunk_index must be -1 or a non-negative integer")
        anchors.append({"parent_doc_id": parent_doc_id, "chunk_index": chunk_index})

    anchor_count = len(anchors)
    if anchor_count != retrieval_chunks:
        return anchors, anchor_count, retrieval_chunks, (
            "included anchor count "
            f"{anchor_count} disagrees with quality.chunks_included {retrieval_chunks}")
    return anchors, anchor_count, retrieval_chunks, ""


def _append_evidence_error(current: str, violation: str) -> str:
    return f"{current}; {violation}" if current else violation


def _score_tier2(ground_truth: str, llm_answer: str) -> tuple[bool, bool, bool]:
    """Score with exact match, substring match, and paper's has_intersection.

    Returns (exact_match, substring_match, has_intersection).
    """
    gt = ground_truth.lower().strip().rstrip(".")
    ans = llm_answer.lower().strip().rstrip(".")

    if gt == "insufficient information":
        is_abstention = any(p in ans for p in _TIER2_ABSTENTION)
        return is_abstention, is_abstention, is_abstention

    exact = gt == ans
    substring = gt in ans

    # Paper's has_intersection: raw word split, any common word
    gt_words = set(ground_truth.lower().split())
    ans_words = set(llm_answer.lower().split())
    has_inter = len(gt_words & ans_words) > 0

    return exact, substring, has_inter


# ---- Source existence check (pre-retrieval abstention) ----

# Multi-word sources are safe to match as bare substrings (low collision risk).
# Single-word sources (nature, fortune, people) need publication-context patterns
# to avoid matching common English words.
_SINGLE_WORD_CONTEXT = re.compile(
    r"(?:article|report|piece|story|coverage|analysis)"
    r"(?:\s+(?:from|by|in|published\s+(?:by|in)))\s+([A-Z][A-Za-z]+)"
    r"|(?:from|by|according\s+to)\s+(?:an?\s+)?(?:article\s+(?:from|by|in)\s+)?"
    r"([A-Z][A-Za-z]+?)(?:\s+(?:article|report|,|and|about|detailing))"
    r"|'([A-Z][A-Za-z\s]+?)'\s*(?:article|report)"
)

# Non-corpus publications that appear in null queries (common news sources
# not in the MultiHop-RAG corpus). Extend as needed for other corpora.
_EXTRA_KNOWN_SOURCES = [
    "financial times", "the guardian", "the washington post",
    "al jazeera", "the telegraph", "daily mail", "sky news",
    "associated press", "usa today", "abc news", "nbc news",
    "politico", "the atlantic", "the economist", "rolling stone",
    "billboard", "tmz", "times of india", "hindustan times",
    "reuters", "bloomberg",
]


def _build_corpus_source_set(base_url: str, timeout: float = 30.0) -> set[str]:
    """Fetch available meta_source values from the search API facets."""
    import httpx as _hx
    try:
        # Use a broad query ("the") instead of "*" which fails Lucene's QueryParser.
        resp = _hx.post(
            f"{base_url}/api/knowledge/search",
            json={"query": "the", "limit": 0, "mode": "text",
                  "facets": {"include": True, "fields": [{"field": "meta_source", "size": 200}]}},
            timeout=timeout,
        )
        facets = resp.json().get("facets", {}).get("meta_source", {})
        return {k.lower().strip() for k in facets}
    except Exception as e:
        log.warning("Failed to fetch corpus sources for source check: %s", e)
        return set()


def _extract_mentioned_sources(query_text: str, corpus_sources: set[str]) -> list[str]:
    """Extract publication names mentioned in a query.

    Multi-word sources are matched as substrings (safe). Single-word sources
    are only matched in publication-context patterns to avoid false positives.
    """
    q_lower = query_text.lower()
    found: set[str] = set()

    # Multi-word sources: safe to match as substrings
    all_multi = [s for s in corpus_sources if len(s.split()) >= 2]
    all_multi += [s for s in _EXTRA_KNOWN_SOURCES if len(s.split()) >= 2]
    for src in all_multi:
        if src in q_lower:
            found.add(src)

    # Single-word sources: require publication context
    for m in _SINGLE_WORD_CONTEXT.finditer(query_text):
        for g in m.groups():
            if g:
                found.add(g.lower().strip())

    return list(found)


def _source_in_corpus(src: str, corpus_sources: set[str]) -> bool:
    """Check if a source name matches any corpus source (prefix match)."""
    return any(src in cs or cs.startswith(src) for cs in corpus_sources)


def _should_abstain_source_check(
    query_text: str, corpus_sources: set[str],
) -> tuple[bool, list[str]]:
    """Check if the query mentions sources absent from the corpus.

    Returns (should_abstain, absent_sources).
    """
    mentioned = _extract_mentioned_sources(query_text, corpus_sources)
    if not mentioned:
        return False, []
    absent = [s for s in mentioned if not _source_in_corpus(s, corpus_sources)]
    return len(absent) > 0, absent


def run_tier2_eval(
    queries: list[dict],
    base_url: str = _DEFAULT_BASE_URL,
    llm_url: str = _DEFAULT_BASE_URL,
    top_k: int = 10,
    max_context_tokens: int = 8192,
    question_types: list[str] | None = None,
    max_queries: int | None = None,
    structured: bool = True,
    use_paper_prompt: bool = False,
    source_check: bool = False,
    checkpoint_dir: Path | None = None,
    allow_compact_model: bool = False,
    comparison_baseline: object | None = None,
    require_served_model: bool = False,
) -> dict:
    """Run Tier 2 single-shot RAG eval: retrieve-context + local LLM answer.

    Calls retrieve-context for each query, sends context + question to
    the local chat model through the Engine's /v1 proxy (``llm_url``, the
    backend by default), scores the answer against ground truth. The proxy
    holds a generation lease per call, so a reconfigure drains the answer
    instead of cutting it. $0 cost (local LLM). Requires the backend with
    chat running.

    When source_check=True, queries mentioning sources absent from the
    corpus are answered with "Insufficient Information" without calling
    the LLM (deterministic pre-retrieval abstention).

    Tempdoc 842 §2.5: probes the served chat model's identity (best-effort;
    never raises) and records it as ``served_model`` in the result aggregate.
    If the served model matches ``COMPACT_MODEL_MARKERS``, raises
    :class:`CompactModelNotAllowedError` unless ``allow_compact_model=True`` --
    compact/dev-tier models poison quality baselines and must not be used for
    quality-sensitive tier-2 runs by accident.
    """
    import httpx as _httpx

    try:
        served_model = served_model_name(llm_url)
    except Exception as e:  # noqa: BLE001 — identity probe is advisory, never fatal
        served_model = None
        log.warning("served_model_name probe failed for %s: %s", llm_url, e)
    if served_model is None:
        log.warning("Could not determine served chat model identity at %s; "
                    "tier-2 result will record served_model=None.", llm_url)
        if require_served_model:
            raise Tier2ComparisonError(
                "served chat model identity is unavailable; canonical CLI runs require "
                "a non-empty model identity before processing queries")

    compact_model_allowed = False
    if served_model:
        matched_marker = next(
            (m for m in COMPACT_MODEL_MARKERS if m in served_model.lower()), None)
        if matched_marker:
            if not allow_compact_model:
                raise CompactModelNotAllowedError(
                    f"Tier-2 eval is running against served model '{served_model}', which "
                    f"matches compact/dev-tier marker '{matched_marker}'. Compact models "
                    "systematically poison quality baselines and results from them are not "
                    "comparable to standard-model baselines (tempdoc 842 §2.5). Pass "
                    "--allow-compact-model (CLI) or allow_compact_model=True (API) to run "
                    "anyway -- the result will be stamped compact_model_allowed=true.")
            compact_model_allowed = True

    filtered = queries
    if question_types:
        filtered = [q for q in queries if q["question_type"] in question_types]
    if max_queries:
        filtered = filtered[:max_queries]

    eval_config = {
        "top_k": top_k,
        "max_context_tokens": max_context_tokens,
        "structured": structured,
        "use_paper_prompt": use_paper_prompt,
        "source_check": source_check,
        "served_model": served_model,
    }
    evaluator_protocol = _tier2_evaluator_protocol(
        structured=structured, use_paper_prompt=use_paper_prompt)
    if comparison_baseline is not None:
        _preflight_tier2_baseline(
            comparison_baseline,
            filtered,
            eval_config=eval_config,
            evaluator_protocol=evaluator_protocol,
        )

    client = _httpx.Client(base_url=base_url, timeout=30.0)
    results: list[Tier2Result] = []

    # Build corpus source set for source existence check
    corpus_sources: set[str] = set()
    source_check_abstentions = 0
    if source_check:
        corpus_sources = _build_corpus_source_set(base_url)
        log.info("Source check enabled: %d sources in corpus", len(corpus_sources))

    log.info("Running Tier 2 eval: %d queries (top_k=%d, structured=%s, source_check=%s)",
             len(filtered), top_k, structured, source_check)

    for i, q in enumerate(filtered):
        result = Tier2Result(
            query=q["query"],
            answer=q["answer"],
            question_type=q["question_type"],
        )

        # Step 1: Retrieve context
        t0 = time.monotonic()
        try:
            resp = client.post(_TIER2_RETRIEVE_PATH, json={
                "query": q["query"],
                "top_k": top_k,
                "max_tokens": max_context_tokens,
            })
            resp.raise_for_status()
            data = resp.json()
        except Exception as e:
            result.error = f"retrieve failed: {e}"
            results.append(result)
            continue

        raw_context = data.get("context") if isinstance(data, dict) else None
        context = raw_context if isinstance(raw_context, str) else ""
        (result.included_anchors,
         result.included_anchor_count,
         result.retrieval_chunks,
         result.anchor_evidence_error) = _extract_tier2_anchor_evidence(data)
        quality = data.get("quality") if isinstance(data, dict) else None
        raw_truncated = quality.get("truncated") if isinstance(quality, dict) else None
        result.context_truncated = raw_truncated if isinstance(raw_truncated, bool) else False
        if not isinstance(raw_context, str):
            result.anchor_evidence_error = _append_evidence_error(
                result.anchor_evidence_error,
                "retrieve-context context must be a string",
            )
        if not isinstance(raw_truncated, bool):
            result.anchor_evidence_error = _append_evidence_error(
                result.anchor_evidence_error,
                "quality.truncated must be a boolean",
            )
        result.context_tokens = len(context) // 4
        result.answer_in_context = q["answer"].lower() in context.lower()
        result.latency_retrieve_ms = int((time.monotonic() - t0) * 1000)

        # Source existence check: if query mentions sources not in corpus, abstain
        if source_check and corpus_sources:
            should_abstain, absent = _should_abstain_source_check(q["query"], corpus_sources)
            if should_abstain:
                result.llm_answer = "Insufficient Information"
                result.evidence_summary = f"source_check: absent sources {absent}"
                result.confidence = "deterministic"
                result.correct_exact, result.correct_substring, result.correct_has_intersection = (
                    _score_tier2(q["answer"], result.llm_answer)
                )
                source_check_abstentions += 1
                results.append(result)
                continue

        # Step 2: Call local LLM
        system_prompt = _TIER2_PAPER_PROMPT if use_paper_prompt else _TIER2_SYSTEM_PROMPT
        user_msg = _TIER2_USER_MESSAGE_TEMPLATE.format(
            context=context, question=q["query"])
        llm_body: dict = {
            "model": _TIER2_LLM_MODEL,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_msg},
            ],
            "max_tokens": _TIER2_LLM_MAX_TOKENS,
            "temperature": _TIER2_LLM_TEMPERATURE,
            "chat_template_kwargs": dict(_TIER2_LLM_CHAT_TEMPLATE_KWARGS),
        }
        # Always request logprobs for observability
        llm_body["logprobs"] = _TIER2_LLM_LOGPROBS
        llm_body["top_logprobs"] = _TIER2_LLM_TOP_LOGPROBS

        if structured and not use_paper_prompt:
            llm_body["response_format"] = {
                "type": "json_schema",
                "json_schema": {
                    "name": "eval_answer",
                    "strict": True,
                    "schema": _TIER2_JSON_SCHEMA,
                },
            }

        t1 = time.monotonic()
        rdata = None
        for attempt in range(5):
            try:
                llm_resp = _httpx.post(
                    f"{llm_url}/v1/chat/completions",
                    json=llm_body,
                    timeout=120.0,
                )
                rdata = llm_resp.json()
                if "choices" in rdata:
                    break
                err_msg = rdata.get("error", {}).get("message", "")
                if "Loading model" in err_msg or "loading" in err_msg.lower():
                    log.warning("  Q%d attempt %d: model loading, waiting 10s...", i + 1, attempt + 1)
                    time.sleep(10)
                    continue
                # Non-recoverable LLM error
                break
            except Exception as e:
                if attempt < 4:
                    log.warning("  Q%d attempt %d failed: %s, retrying in 10s...", i + 1, attempt + 1, e)
                    time.sleep(10)
                    continue
                result.error = f"llm failed after 5 attempts: {e}"
                results.append(result)
                rdata = None
                break

        if rdata is None:
            if not result.error:
                result.error = "llm failed: no response after retries"
            results.append(result)
            continue
        if "choices" not in rdata:
            result.error = f"llm error: {rdata.get('error', {}).get('message', 'unknown')}"
            results.append(result)
            continue
        raw_content = rdata["choices"][0]["message"]["content"].strip()
        result.completion_tokens = rdata.get("usage", {}).get("completion_tokens", 0)

        result.latency_llm_ms = int((time.monotonic() - t1) * 1000)

        # Parse structured output (JSON schema or ANSWER: format)
        if "ANSWER:" in raw_content:
            # Parse ANSWER:/EVIDENCE:/CONFIDENCE: format
            for line in raw_content.split("\n"):
                line = line.strip()
                if line.upper().startswith("ANSWER:"):
                    result.llm_answer = line.split(":", 1)[1].strip()
                elif line.upper().startswith("EVIDENCE:"):
                    result.evidence_summary = line.split(":", 1)[1].strip()
                elif line.upper().startswith("CONFIDENCE:"):
                    result.confidence = line.split(":", 1)[1].strip().lower()
            if not result.llm_answer:
                result.llm_answer = raw_content
        elif structured and not use_paper_prompt:
            try:
                parsed = json.loads(raw_content)
                result.llm_answer = parsed.get("answer", "")
                result.evidence_summary = parsed.get("evidence_summary", "")
                result.confidence = parsed.get("confidence", "")
            except json.JSONDecodeError:
                result.llm_answer = raw_content
        else:
            result.llm_answer = raw_content

        # Score
        result.correct_exact, result.correct_substring, result.correct_has_intersection = (
            _score_tier2(q["answer"], result.llm_answer)
        )

        results.append(result)

        if (i + 1) % 50 == 0 or (i + 1) == len(filtered):
            correct_so_far = sum(1 for r in results if r.correct_substring)
            errors_so_far = sum(1 for r in results if r.error)
            log.info("  %d/%d processed (%d correct, %d errors)",
                     i + 1, len(filtered), correct_so_far, errors_so_far)

        # Checkpoint every 100 queries (resume-friendly for long runs)
        if checkpoint_dir and ((i + 1) % 100 == 0 or (i + 1) == len(filtered)):
            _save_checkpoint(checkpoint_dir, results, i + 1, len(filtered))

    client.close()
    if source_check:
        log.info("Source check abstentions: %d/%d", source_check_abstentions, len(filtered))
    return _aggregate_tier2(
        results,
        served_model=served_model,
        compact_model_allowed=compact_model_allowed,
        eval_config=eval_config,
        evaluator_protocol=evaluator_protocol,
        eval_provenance={"base_url": base_url, "llm_url": llm_url},
    )


def _save_checkpoint(checkpoint_dir: Path, results: list[Tier2Result], done: int, total: int) -> None:
    """Save intermediate results for crash recovery."""
    checkpoint_dir.mkdir(parents=True, exist_ok=True)
    cp_file = checkpoint_dir / "tier2-checkpoint.json"
    cp_data = {
        "done": done,
        "total": total,
        "results": [asdict(r) for r in results],
    }
    cp_file.write_text(json.dumps(cp_data, indent=2, default=str), encoding="utf-8")
    log.debug("  Checkpoint saved: %d/%d to %s", done, total, cp_file)


def _aggregate_tier2(results: list[Tier2Result], *, served_model: str | None = None,
                     compact_model_allowed: bool = False,
                     eval_config: dict | None = None,
                     evaluator_protocol: dict | None = None,
                     eval_provenance: dict | None = None) -> dict:
    """Aggregate Tier 2 results."""
    if not results:
        aggregate = {"error": "no results", "served_model": served_model}
        if eval_config is not None:
            aggregate["eval_config"] = dict(eval_config)
        if evaluator_protocol is not None:
            aggregate["evaluator_protocol"] = dict(evaluator_protocol)
        if eval_provenance is not None:
            aggregate["eval_provenance"] = dict(eval_provenance)
        return aggregate

    total = len(results)
    errors = sum(1 for r in results if r.error)
    valid = [r for r in results if not r.error]
    n = len(valid) if valid else 1

    exact_correct = sum(1 for r in valid if r.correct_exact)
    substr_correct = sum(1 for r in valid if r.correct_substring)
    hi_correct = sum(1 for r in valid if r.correct_has_intersection)
    avg_latency_ret = sum(r.latency_retrieve_ms for r in valid) / n
    avg_latency_llm = sum(r.latency_llm_ms for r in valid) / n
    avg_ctx_tokens = sum(r.context_tokens for r in valid) / n
    avg_compl_tokens = sum(r.completion_tokens for r in valid) / n

    # Confidence distribution
    conf_dist = {}
    for r in valid:
        conf_dist[r.confidence] = conf_dist.get(r.confidence, 0) + 1

    # Per question type
    by_type: dict[str, list[Tier2Result]] = {}
    for r in results:
        by_type.setdefault(r.question_type, []).append(r)

    type_summary = {}
    for qtype, typed in by_type.items():
        tv = [r for r in typed if not r.error]
        tn = len(tv) if tv else 1
        type_summary[qtype] = {
            "count": len(typed),
            "errors": sum(1 for r in typed if r.error),
            "accuracy_exact": sum(1 for r in tv if r.correct_exact) / tn,
            "accuracy_substring": sum(1 for r in tv if r.correct_substring) / tn,
            "accuracy_has_intersection": sum(1 for r in tv if r.correct_has_intersection) / tn,
            "answer_in_context_rate": round(
                sum(1 for r in tv if r.answer_in_context) / tn, 4),
            "context_truncated_rate": round(
                sum(1 for r in tv if r.context_truncated) / tn, 4),
            "avg_latency_ms": round(sum(r.latency_retrieve_ms + r.latency_llm_ms for r in tv) / tn),
        }

    agg = {
        "tier": "tier2_single_shot_rag",
        "served_model": served_model,
        "total_queries": total,
        "errors": errors,
        "anchor_evidence_errors": sum(1 for r in results if r.anchor_evidence_error),
        "accuracy_exact": round(exact_correct / n, 4),
        "accuracy_substring": round(substr_correct / n, 4),
        "accuracy_has_intersection": round(hi_correct / n, 4),
        "answer_in_context_rate": round(
            sum(1 for r in valid if r.answer_in_context) / n, 4),
        "context_truncated_rate": round(
            sum(1 for r in valid if r.context_truncated) / n, 4),
        "avg_latency_retrieve_ms": round(avg_latency_ret),
        "avg_latency_llm_ms": round(avg_latency_llm),
        "avg_context_tokens": round(avg_ctx_tokens),
        "avg_completion_tokens": round(avg_compl_tokens),
        "confidence_distribution": conf_dist,
        "by_type": type_summary,
        "results": [asdict(r) for r in results],
    }
    if eval_config is not None:
        agg["eval_config"] = dict(eval_config)
    if evaluator_protocol is not None:
        agg["evaluator_protocol"] = dict(evaluator_protocol)
    if eval_provenance is not None:
        agg["eval_provenance"] = dict(eval_provenance)
    if compact_model_allowed:
        agg["compact_model_allowed"] = True
    return agg


_TIER2_COMPARISON_CONFIG_TYPES = {
    "top_k": int,
    "max_context_tokens": int,
    "structured": bool,
    "use_paper_prompt": bool,
    "source_check": bool,
    "served_model": str,
}


def _validate_tier2_comparison_record(
    record: object, arm: str,
) -> tuple[dict, dict, dict, list[dict]]:
    """Return normalized comparison inputs or reject an unevaluable record."""
    if not isinstance(record, dict):
        raise Tier2ComparisonError(f"{arm} Tier-2 record must be an object")
    if record.get("tier") != "tier2_single_shot_rag":
        raise Tier2ComparisonError(
            f"{arm} tier must be 'tier2_single_shot_rag'")

    config = record.get("eval_config")
    if not isinstance(config, dict):
        raise Tier2ComparisonError(f"{arm} Tier-2 record is missing eval_config")
    missing_config = [key for key in _TIER2_COMPARISON_CONFIG_TYPES if key not in config]
    if missing_config:
        raise Tier2ComparisonError(
            f"{arm} eval_config is missing: {', '.join(missing_config)}")
    for key, expected_type in _TIER2_COMPARISON_CONFIG_TYPES.items():
        value = config[key]
        if expected_type is int:
            lower_bound = 1 if key == "top_k" else 0
            valid = (
                isinstance(value, int) and not isinstance(value, bool)
                and value >= lower_bound)
        elif expected_type is bool:
            valid = isinstance(value, bool)
        else:
            valid = isinstance(value, expected_type) and bool(value)
        if not valid:
            raise Tier2ComparisonError(
                f"{arm} eval_config.{key} has an invalid value")

    served_model = record.get("served_model")
    if served_model != config["served_model"]:
        raise Tier2ComparisonError(
            f"{arm} served_model disagrees with eval_config.served_model")

    protocol = record.get("evaluator_protocol")
    if not isinstance(protocol, dict):
        raise Tier2ComparisonError(f"{arm} Tier-2 record is missing evaluator_protocol")
    protocol_id = protocol.get("id")
    protocol_digest = protocol.get("digest_sha256")
    protocol_definition = protocol.get("definition")
    if not isinstance(protocol_id, str) or not protocol_id:
        raise Tier2ComparisonError(f"{arm} evaluator_protocol.id is invalid")
    if not isinstance(protocol_definition, dict) or protocol_definition.get("id") != protocol_id:
        raise Tier2ComparisonError(
            f"{arm} evaluator_protocol definition/id is invalid")
    if (not isinstance(protocol_digest, str) or len(protocol_digest) != 64
            or protocol_digest != _tier2_protocol_digest(protocol_definition)):
        raise Tier2ComparisonError(
            f"{arm} evaluator_protocol digest does not match its definition")

    provenance = record.get("eval_provenance")
    if not isinstance(provenance, dict):
        raise Tier2ComparisonError(f"{arm} Tier-2 record is missing eval_provenance")
    for key in ("base_url", "llm_url"):
        if not isinstance(provenance.get(key), str) or not provenance[key]:
            raise Tier2ComparisonError(f"{arm} eval_provenance.{key} is invalid")

    raw_results = record.get("results")
    if not isinstance(raw_results, list) or not raw_results:
        raise Tier2ComparisonError(f"{arm} Tier-2 record has no per-query results")
    reported_evidence_errors = record.get("anchor_evidence_errors")
    if (not isinstance(reported_evidence_errors, int)
            or isinstance(reported_evidence_errors, bool)
            or reported_evidence_errors < 0):
        raise Tier2ComparisonError(
            f"{arm} anchor_evidence_errors must be a non-negative integer")
    actual_evidence_errors = sum(
        1
        for raw_result in raw_results
        if isinstance(raw_result, dict)
        and isinstance(raw_result.get("anchor_evidence_error"), str)
        and bool(raw_result["anchor_evidence_error"])
    )
    if reported_evidence_errors != actual_evidence_errors:
        raise Tier2ComparisonError(
            f"{arm} anchor_evidence_errors {reported_evidence_errors} disagrees with "
            f"{actual_evidence_errors} per-query evidence errors")
    if actual_evidence_errors:
        raise Tier2ComparisonError(
            f"{arm} has invalid anchor evidence for {actual_evidence_errors} "
            f"quer{'y' if actual_evidence_errors == 1 else 'ies'}")
    total_queries = record.get("total_queries")
    if (not isinstance(total_queries, int) or isinstance(total_queries, bool)
            or total_queries != len(raw_results)):
        raise Tier2ComparisonError(
            f"{arm} total_queries disagrees with the per-query result count")

    normalized_results: list[dict] = []
    for index, raw_result in enumerate(raw_results):
        prefix = f"{arm} results[{index}]"
        if not isinstance(raw_result, dict):
            raise Tier2ComparisonError(f"{prefix} must be an object")
        for key in ("query", "answer", "question_type", "error"):
            if not isinstance(raw_result.get(key), str):
                raise Tier2ComparisonError(f"{prefix}.{key} must be a string")
        for key in ("answer_in_context", "correct_has_intersection", "context_truncated"):
            if not isinstance(raw_result.get(key), bool):
                raise Tier2ComparisonError(f"{prefix}.{key} must be a boolean")
        latency = raw_result.get("latency_retrieve_ms")
        if (not isinstance(latency, int) or isinstance(latency, bool) or latency < 0):
            raise Tier2ComparisonError(
                f"{prefix}.latency_retrieve_ms must be a non-negative integer")

        evidence_error = raw_result.get("anchor_evidence_error")
        if not isinstance(evidence_error, str):
            raise Tier2ComparisonError(f"{prefix} is missing anchor_evidence_error")
        if evidence_error:
            raise Tier2ComparisonError(
                f"{prefix} has invalid anchor evidence: {evidence_error}")

        raw_anchors = raw_result.get("included_anchors")
        if not isinstance(raw_anchors, list):
            raise Tier2ComparisonError(f"{prefix} is missing included_anchors")
        anchors: list[dict] = []
        for anchor_index, raw_anchor in enumerate(raw_anchors):
            anchor_prefix = f"{prefix}.included_anchors[{anchor_index}]"
            if not isinstance(raw_anchor, dict):
                raise Tier2ComparisonError(f"{anchor_prefix} must be an object")
            parent_doc_id = raw_anchor.get("parent_doc_id")
            chunk_index = raw_anchor.get("chunk_index")
            if not isinstance(parent_doc_id, str) or not parent_doc_id:
                raise Tier2ComparisonError(
                    f"{anchor_prefix}.parent_doc_id must be a non-empty string")
            if (not isinstance(chunk_index, int) or isinstance(chunk_index, bool)
                    or chunk_index < -1):
                raise Tier2ComparisonError(
                    f"{anchor_prefix}.chunk_index must be -1 or a non-negative integer")
            anchors.append({"parent_doc_id": parent_doc_id, "chunk_index": chunk_index})

        anchor_count = raw_result.get("included_anchor_count")
        retrieval_chunks = raw_result.get("retrieval_chunks")
        for key, value in (("included_anchor_count", anchor_count),
                           ("retrieval_chunks", retrieval_chunks)):
            if (not isinstance(value, int) or isinstance(value, bool) or value < 0):
                raise Tier2ComparisonError(
                    f"{prefix}.{key} must be a non-negative integer")
        if anchor_count != len(anchors):
            raise Tier2ComparisonError(
                f"{prefix}.included_anchor_count disagrees with included_anchors")
        if retrieval_chunks != anchor_count:
            raise Tier2ComparisonError(
                f"{prefix}.retrieval_chunks disagrees with included_anchor_count")

        normalized_results.append({
            "query": raw_result["query"],
            "answer": raw_result["answer"],
            "question_type": raw_result["question_type"],
            "error": raw_result["error"],
            "answer_in_context": raw_result["answer_in_context"],
            "correct_has_intersection": raw_result["correct_has_intersection"],
            "context_truncated": raw_result["context_truncated"],
            "latency_retrieve_ms": latency,
            "included_anchors": anchors,
            "included_anchor_count": anchor_count,
        })
    return (
        dict(config), copy.deepcopy(protocol), dict(provenance), normalized_results,
    )


def validate_tier2_result(record: object) -> None:
    """Fail unless ``record`` is a canonical, comparison-ready Tier-2 result."""
    _validate_tier2_comparison_record(record, "result")


def _preflight_tier2_baseline(
    baseline: object,
    queries: list[dict],
    *,
    eval_config: dict,
    evaluator_protocol: dict,
) -> None:
    """Reject a known-incompatible baseline before any query is processed."""
    baseline_config, baseline_protocol, _, baseline_results = (
        _validate_tier2_comparison_record(baseline, "baseline")
    )
    if baseline_config != eval_config:
        raise Tier2ComparisonError(
            "Tier-2 eval configuration/model differs between baseline and planned run")
    if baseline_protocol != evaluator_protocol:
        raise Tier2ComparisonError(
            "Tier-2 evaluator protocol differs between baseline and planned run")
    if len(baseline_results) != len(queries):
        raise Tier2ComparisonError(
            "Tier-2 baseline query count differs from the planned run")
    for index, (baseline_result, query) in enumerate(zip(baseline_results, queries)):
        planned_identity = (
            query.get("query"), query.get("answer"), query.get("question_type"))
        baseline_identity = (
            baseline_result["query"], baseline_result["answer"],
            baseline_result["question_type"])
        if baseline_identity != planned_identity:
            raise Tier2ComparisonError(
                "Tier-2 ordered query/answer/question_type sequence differs from the "
                f"planned run at results[{index}]")


def _bool_transition(before: bool, after: bool) -> str:
    return f"{str(before).lower()}_to_{str(after).lower()}"


def _error_transition(before: str, after: str) -> str:
    return f"{'error' if before else 'none'}_to_{'error' if after else 'none'}"


def _increment(counter: dict[str, int], key: str) -> None:
    counter[key] += 1


def _anchor_key(anchor: dict) -> tuple[str, int]:
    return anchor["parent_doc_id"], anchor["chunk_index"]


def _ordered_anchor_difference(left: list[dict], right: list[dict]) -> list[dict]:
    """Return the unmatched multiset difference in ``left`` order."""
    remaining = Counter(_anchor_key(anchor) for anchor in right)
    difference: list[dict] = []
    for anchor in left:
        key = _anchor_key(anchor)
        if remaining[key]:
            remaining[key] -= 1
        else:
            difference.append(dict(anchor))
    return difference


def compare_tier2_results(baseline: object, candidate: object) -> dict:
    """Build a deterministic, fail-closed paired Tier-2 comparison.

    Pairing is positional and is admitted only when both records prove the same
    ordered query/answer/type sequence, exact evaluation configuration, served
    model, and valid included-anchor evidence for every query.

    ``base_url`` and ``llm_url`` remain per-arm provenance, not compatibility
    inputs: isolated baseline/candidate stacks can legitimately use different
    ports.  The evaluator protocol digest and served-model/config equality own
    the semantic comparison boundary.
    """
    (baseline_config, baseline_protocol,
     baseline_provenance, baseline_results) = _validate_tier2_comparison_record(
        baseline, "baseline")
    (candidate_config, candidate_protocol,
     candidate_provenance, candidate_results) = _validate_tier2_comparison_record(
        candidate, "candidate")
    if baseline_config != candidate_config:
        raise Tier2ComparisonError(
            "Tier-2 eval configuration/model differs between baseline and candidate")
    if baseline_protocol != candidate_protocol:
        raise Tier2ComparisonError(
            "Tier-2 evaluator protocol differs between baseline and candidate")
    if len(baseline_results) != len(candidate_results):
        raise Tier2ComparisonError(
            "Tier-2 per-query result counts differ between baseline and candidate")

    bool_transition_keys = (
        "false_to_false", "false_to_true", "true_to_false", "true_to_true")
    transitions = {
        "answer_in_context": {key: 0 for key in bool_transition_keys},
        "correct_has_intersection": {key: 0 for key in bool_transition_keys},
        "errors": {
            key: 0 for key in
            ("none_to_none", "none_to_error", "error_to_none", "error_to_error")
        },
        "context_truncated": {key: 0 for key in bool_transition_keys},
        "anchor_count": {"decreased": 0, "unchanged": 0, "increased": 0},
        "anchor_identity": {"unchanged": 0, "changed": 0},
        "anchor_order": {"unchanged": 0, "changed": 0, "not_comparable": 0},
    }
    per_query: list[dict] = []
    baseline_latency_total = 0
    candidate_latency_total = 0
    additions_total = 0
    removals_total = 0

    for index, (before, after) in enumerate(zip(baseline_results, candidate_results)):
        identity = (before["query"], before["answer"], before["question_type"])
        candidate_identity = (after["query"], after["answer"], after["question_type"])
        if identity != candidate_identity:
            raise Tier2ComparisonError(
                "Tier-2 ordered query/answer/question_type sequence differs at "
                f"results[{index}]")

        answer_transition = _bool_transition(
            before["answer_in_context"], after["answer_in_context"])
        intersection_transition = _bool_transition(
            before["correct_has_intersection"], after["correct_has_intersection"])
        error_transition = _error_transition(before["error"], after["error"])
        truncation_transition = _bool_transition(
            before["context_truncated"], after["context_truncated"])
        _increment(transitions["answer_in_context"], answer_transition)
        _increment(transitions["correct_has_intersection"], intersection_transition)
        _increment(transitions["errors"], error_transition)
        _increment(transitions["context_truncated"], truncation_transition)

        before_anchors = before["included_anchors"]
        after_anchors = after["included_anchors"]
        before_keys = [_anchor_key(anchor) for anchor in before_anchors]
        after_keys = [_anchor_key(anchor) for anchor in after_anchors]
        identity_changed = Counter(before_keys) != Counter(after_keys)
        order_comparable = not identity_changed
        order_changed = order_comparable and before_keys != after_keys
        additions = _ordered_anchor_difference(after_anchors, before_anchors)
        removals = _ordered_anchor_difference(before_anchors, after_anchors)
        additions_total += len(additions)
        removals_total += len(removals)

        count_delta = after["included_anchor_count"] - before["included_anchor_count"]
        count_transition = (
            "increased" if count_delta > 0 else "decreased" if count_delta < 0 else "unchanged")
        _increment(transitions["anchor_count"], count_transition)
        _increment(
            transitions["anchor_identity"], "changed" if identity_changed else "unchanged")
        if not order_comparable:
            order_transition = "not_comparable"
        else:
            order_transition = "changed" if order_changed else "unchanged"
        _increment(transitions["anchor_order"], order_transition)

        baseline_latency = before["latency_retrieve_ms"]
        candidate_latency = after["latency_retrieve_ms"]
        latency_delta = candidate_latency - baseline_latency
        baseline_latency_total += baseline_latency
        candidate_latency_total += candidate_latency
        per_query.append({
            "index": index,
            "query": before["query"],
            "answer": before["answer"],
            "question_type": before["question_type"],
            "baseline_anchors": [dict(anchor) for anchor in before_anchors],
            "candidate_anchors": [dict(anchor) for anchor in after_anchors],
            "baseline_anchor_count": before["included_anchor_count"],
            "candidate_anchor_count": after["included_anchor_count"],
            "anchor_count_delta": count_delta,
            "anchor_additions": additions,
            "anchor_removals": removals,
            "anchor_identity_changed": identity_changed,
            "anchor_order_comparable": order_comparable,
            "anchor_order_changed": order_changed,
            "answer_in_context_transition": answer_transition,
            "correct_has_intersection_transition": intersection_transition,
            "error_transition": error_transition,
            "context_truncated_transition": truncation_transition,
            "baseline_retrieval_latency_ms": baseline_latency,
            "candidate_retrieval_latency_ms": candidate_latency,
            "retrieval_latency_delta_ms": latency_delta,
        })

    query_count = len(per_query)
    baseline_mean = baseline_latency_total / query_count
    candidate_mean = candidate_latency_total / query_count
    mean_delta = candidate_mean - baseline_mean
    percent_delta = None if baseline_mean == 0 else mean_delta / baseline_mean * 100
    return {
        "schema_version": "tier2-paired-comparison.v2",
        "evaluator_compatible": True,
        "experimental_admissibility": {
            "status": "external-check-required",
            "checked_by_comparator": False,
            "required_checks": [
                "baseline and candidate use the same logical index identity",
                "enrichment state is stable within and equal across both arms",
            ],
        },
        "query_count": query_count,
        "eval_config": dict(baseline_config),
        "evaluator_protocol": dict(baseline_protocol),
        # Endpoint addresses identify where each arm ran, but are transport
        # provenance rather than evaluator semantics.  Separate baseline and
        # candidate stacks routinely use different ports; the protocol digest
        # and served-model/config equality above own semantic compatibility.
        "eval_provenance": {
            "affects_semantic_compatibility": False,
            "baseline": dict(baseline_provenance),
            "candidate": dict(candidate_provenance),
        },
        "transitions": transitions,
        "anchor_changes": {
            "additions": additions_total,
            "removals": removals_total,
            "baseline_total": sum(r["included_anchor_count"] for r in baseline_results),
            "candidate_total": sum(r["included_anchor_count"] for r in candidate_results),
        },
        "retrieval_latency_ms": {
            "baseline_mean": round(baseline_mean, 4),
            "candidate_mean": round(candidate_mean, 4),
            "mean_delta": round(mean_delta, 4),
            "mean_delta_percent": None if percent_delta is None else round(percent_delta, 4),
        },
        "per_query": per_query,
    }


def format_tier2_console(result: dict) -> str:
    """Format Tier 2 results for console output."""
    lines = [
        "=== Tier 2: Single-Shot RAG ===",
        f"Queries: {result['total_queries']} (errors: {result.get('errors', 0)})",
        "",
        "--- Accuracy ---",
        f"  has_intersection (paper): {result.get('accuracy_has_intersection', 0):.1%}",
        f"  Substring:               {result.get('accuracy_substring', 0):.1%}",
        f"  Exact match:             {result.get('accuracy_exact', 0):.1%}",
        f"  Answer in context:       {result.get('answer_in_context_rate', 0):.1%}",
        f"  Context truncated:       {result.get('context_truncated_rate', 0):.1%}",
        f"  Anchor evidence errors:  {result.get('anchor_evidence_errors', 0)}",
        "",
        "--- Latency ---",
        f"  Retrieve: {result.get('avg_latency_retrieve_ms', 0)}ms",
        f"  LLM:      {result.get('avg_latency_llm_ms', 0)}ms",
        f"  Total:    {result.get('avg_latency_retrieve_ms', 0) + result.get('avg_latency_llm_ms', 0)}ms",
        "",
        f"Avg context tokens: {result.get('avg_context_tokens', 0)}",
        f"Avg completion tokens: {result.get('avg_completion_tokens', 0)}",
        f"Confidence: {result.get('confidence_distribution', {})}",
        "",
        "--- By Question Type ---",
    ]
    for qtype, stats in sorted(result.get("by_type", {}).items()):
        lines.append(
            f"  {qtype}: n={stats['count']} "
            f"hi={stats.get('accuracy_has_intersection', 0):.0%} "
            f"substr={stats.get('accuracy_substring', 0):.0%} "
            f"exact={stats.get('accuracy_exact', 0):.0%} "
            f"avg={stats.get('avg_latency_ms', 0)}ms"
        )
    comparison = result.get("paired_comparison")
    if isinstance(comparison, dict):
        transitions = comparison.get("transitions", {})
        answer = transitions.get("answer_in_context", {})
        intersection = transitions.get("correct_has_intersection", {})
        errors = transitions.get("errors", {})
        truncation = transitions.get("context_truncated", {})
        anchor_count = transitions.get("anchor_count", {})
        anchor_identity = transitions.get("anchor_identity", {})
        anchor_order = transitions.get("anchor_order", {})
        latency = comparison.get("retrieval_latency_ms", {})
        admissibility = comparison.get("experimental_admissibility", {})
        mean_delta_percent = latency.get("mean_delta_percent")
        mean_delta_percent_text = (
            "n/a" if mean_delta_percent is None else f"{mean_delta_percent}%"
        )
        lines.extend([
            "",
            "--- Paired Comparison ---",
            f"  Evaluator compatible: {comparison.get('evaluator_compatible', False)}",
            "  Experimental admissibility: "
            f"{admissibility.get('status', 'unknown')}",
            "  Gold-span context wins/losses: "
            f"{answer.get('false_to_true', 0)}/{answer.get('true_to_false', 0)}",
            "  Answer-intersection wins/losses: "
            f"{intersection.get('false_to_true', 0)}/{intersection.get('true_to_false', 0)}",
            "  New/recovered errors: "
            f"{errors.get('none_to_error', 0)}/{errors.get('error_to_none', 0)}",
            "  New/recovered truncations: "
            f"{truncation.get('false_to_true', 0)}/{truncation.get('true_to_false', 0)}",
            "  Anchor counts decreased/unchanged/increased: "
            f"{anchor_count.get('decreased', 0)}/"
            f"{anchor_count.get('unchanged', 0)}/"
            f"{anchor_count.get('increased', 0)}",
            f"  Anchor identities changed: {anchor_identity.get('changed', 0)}",
            "  Anchor order changed/not-comparable: "
            f"{anchor_order.get('changed', 0)}/"
            f"{anchor_order.get('not_comparable', 0)}",
            "  Mean retrieval latency delta: "
            f"{latency.get('mean_delta', 0)}ms "
            f"({mean_delta_percent_text})",
        ])
    return "\n".join(lines)


# ============================================================
# Phase 2: Agent comparison (requires Claude Code CLI)
# ============================================================

# Disallowed in every condition — no condition should let the agent silently
# answer via a live web lookup (the opposite failure mode from the "does the
# model already know this" contamination this eval controls for), and a
# blocked WebSearch was observed being routed around by spawning a subagent
# (Agent/Task) that pursued the same blocked capability indirectly (tempdoc
# 624 confidence pass, live probe). Skill is also disallowed: a locally
# installed "deep-research" skill was observed reaching the same live-web
# outcome by internally orchestrating its own multi-agent workflow, invisible
# to the Agent/Task block.
_ALWAYS_DISALLOWED_TOOLS = ["WebFetch", "WebSearch", "Agent", "Task", "Skill"]

# Additionally disallowed for condition C ("JustSearch-only"): its own premise
# is no native file access, but Bash was left open as a shell-based file-read
# backdoor around the original Read/Grep/Glob-only list. ReadMcpResourceTool /
# ReadMcpResourceDirTool / ListMcpResourcesTool are a THIRD corpus-file-access
# channel outside the retrieval surface -- a condition-C cell was observed
# attempting exactly this on 2026-07-07 (tempdoc 624 §M.8 amendment, Step 0
# item 3) and it was not flagged by any existing check.
_CONDITION_C_EXTRA_DISALLOWED_TOOLS = [
    "Read", "Grep", "Glob", "Bash",
    "ReadMcpResourceTool", "ReadMcpResourceDirTool", "ListMcpResourcesTool",
]


def build_disallowed_tools(condition: str) -> list[str]:
    """Build the --disallowedTools list for a given eval condition.

    Every condition (A, B, C) disallows WebFetch/WebSearch/Agent/Task.
    Condition C additionally disallows Read/Grep/Glob/Bash.
    """
    if condition == "C":
        return _CONDITION_C_EXTRA_DISALLOWED_TOOLS + _ALWAYS_DISALLOWED_TOOLS
    return list(_ALWAYS_DISALLOWED_TOOLS)


def find_disallowed_tool_calls(tool_calls: list[dict], disallowed: list[str]) -> list[dict]:
    """Scan parsed tool_calls for any that used a tool disallowed for this condition.

    This is an empirical check, not a trust-the-config assumption: the CLI's
    --disallowedTools flag is *supposed* to block these tools, but condition C's
    original Read/Grep/Glob-only list silently left Bash open as a file-read
    backdoor — so this surfaces any such gap as data in the result record
    rather than letting clean-looking numbers hide it.
    """
    disallowed_set = set(disallowed)
    return [tc for tc in tool_calls if tc.get("tool") in disallowed_set]


# The eval's own gold-answer key filename (tempdoc 624 §As-built #7): a real
# leak was found where an agent under a file-tool condition (A/B) read/globbed
# this file directly instead of the corpus, producing a leaked-but-correct
# answer indistinguishable from a genuine one by every existing check.
_LEAK_SUSPECT_NEEDLE = "queries.json"
_LEAK_SUSPECT_TOOLS = {"Read", "Glob"}


def find_leak_suspect_tool_calls(
    tool_calls: list[dict], needle: str = _LEAK_SUSPECT_NEEDLE,
) -> list[dict]:
    """Scan parsed tool_calls for a Read/Glob call whose path argument names the
    eval's gold-answer key (tempdoc 624 §As-built #7 defense-in-depth backstop).

    This is a detection BACKSTOP, not a prevention mechanism — the corpus-directory
    isolation fix (elsewhere in this effort) is what should keep queries.json out of
    an agent's reachable filesystem in the first place. This scan exists in case that
    fix regresses or a future condition reintroduces the same class of exposure: it
    flags the cell as suspect using the SAME tool_calls capture
    ``find_disallowed_tool_calls`` already reads, not a new capture mechanism.

    Substring match (case-insensitive) over every string-valued ``input`` argument,
    since the path-bearing key differs by tool (``Read``: ``file_path``; ``Glob``:
    ``pattern``) and separators may vary by OS.
    """
    needle_lower = needle.lower()
    flagged = []
    for tc in tool_calls:
        if tc.get("tool") not in _LEAK_SUSPECT_TOOLS:
            continue
        values = tc.get("input") or {}
        haystack = " ".join(str(v) for v in values.values()).lower()
        if needle_lower in haystack:
            flagged.append(tc)
    return flagged


def _aggregate_agent(results: list[AgentResult], condition: str, model: str) -> dict:
    """Aggregate agent results into summary metrics."""
    if not results:
        return {"error": "no results"}

    total = len(results)
    errors = sum(1 for r in results if r.error)
    valid = [r for r in results if not r.error]
    n_valid = len(valid)

    if n_valid == 0:
        return {
            "phase": "agent", "condition": condition, "model": model,
            "total_queries": total, "errors": errors, "error": "all queries failed",
            "results": [asdict(r) for r in results],
        }

    correct = sum(1 for r in valid if r.correct)
    avg_cost = sum(r.cost_usd for r in valid) / n_valid
    avg_turns = sum(r.num_turns for r in valid) / n_valid
    avg_duration = sum(r.duration_ms for r in valid) / n_valid
    avg_cache_creation = sum(r.cache_creation_tokens for r in valid) / n_valid

    return {
        "phase": "agent",
        "condition": condition,
        "model": model,
        "total_queries": total,
        "errors": errors,
        "accuracy": correct / n_valid,
        "avg_cost_usd": round(avg_cost, 4),
        "avg_turns": round(avg_turns, 1),
        "avg_duration_ms": round(avg_duration),
        "avg_unique_content_tokens": round(avg_cache_creation),
        "total_cost_usd": round(sum(r.cost_usd for r in valid), 4),
        "results": [asdict(r) for r in results],
    }


# ============================================================
# Console formatting
# ============================================================


def format_retrieval_console(result: dict) -> str:
    """Format Phase 1 retrieval results for console output."""
    lines = [
        "=== Retrieval Quality (Tier 1) ===",
        f"Queries: {result['total_queries']} ({result.get('queries_with_evidence', '?')} with evidence)",
        "",
        "--- Standard Metrics ---",
        f"  Hits@1:  {result.get('hits_at_1', 0):.1%}",
        f"  Hits@3:  {result.get('hits_at_3', 0):.1%}",
        f"  Hits@5:  {result.get('hits_at_5', 0):.1%}",
        f"  Hits@10: {result.get('hits_at_10', 0):.1%}",
        f"  MRR:     {result.get('mrr', 0):.4f}",
        f"  Evidence recall: {result.get('avg_evidence_recall', 0):.1%}",
        f"  Answer in context: {result['answer_in_context_rate']:.1%}",
        "",
        "--- Pipeline Stats ---",
        f"  Avg context tokens: {result['avg_context_tokens']}",
        f"  Avg chunks included: {result['avg_chunks_included']}",
        f"  Avg latency: {result['avg_latency_ms']}ms",
        f"  Avg best score: {result['avg_best_score']}",
        f"  Avg coverage: {result['avg_coverage']:.1%}",
        "",
        "--- By Question Type ---",
    ]
    for qtype, stats in sorted(result.get("by_type", {}).items()):
        lines.append(
            f"  {qtype}: n={stats['count']} "
            f"Hits@1={stats.get('hits_at_1', 0):.0%} "
            f"Hits@10={stats.get('hits_at_10', 0):.0%} "
            f"MRR={stats.get('mrr', 0):.3f} "
            f"answer_in_ctx={stats['answer_in_context_rate']:.0%}"
        )
    reachability = result.get("rag_reachability")
    if reachability is not None:
        lines += [
            "",
            "--- RAG Reachability Guard (tempdoc 749) ---",
            f"  Verdict: {reachability['verdict']} "
            f"({reachability['passed']}/{reachability['sampled']} sub-threshold docs reachable)",
        ]
        if reachability["failed"]:
            lines.append(f"  Unreachable: {reachability['failed']}")
    return "\n".join(lines)


def format_agent_console(result: dict) -> str:
    """Format Phase 2 agent results for console output."""
    condition = result.get('condition', '?')
    model = result.get('model', '?')
    lines = [
        f"=== Phase 2: Agent Comparison (condition={condition}, model={model}) ===",
        f"Queries: {result.get('total_queries', result.get('total', 0))} "
        f"(errors: {result.get('errors', 0)})",
        f"Accuracy: {result.get('accuracy', 0):.1%}",
        f"Avg cost: ${result.get('avg_cost_usd', 0):.4f}",
        f"Avg turns: {result.get('avg_turns', 0)}",
        f"Avg duration: {result.get('avg_duration_ms', 0)}ms",
        f"Avg unique content tokens: {result.get('avg_unique_content_tokens', 0)}",
        f"Total cost: ${result.get('total_cost_usd', 0):.4f}",
    ]
    return "\n".join(lines)
