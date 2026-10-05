#!/usr/bin/env python3
"""Q-020 / F-046 — deterministic LUCENE-syntax query-variant derivation for beir/scifact.

F-046 (tempdoc 821 §P, register `docs/reference/search-quality-register.md`) made
`query_syntax: "lucene"` actually reach every retrieval leg. Q-020 asks the question that
fix left open: is LUCENE-syntax retrieval any GOOD? Every number the register has ever
published was measured with the default SIMPLE parse (jseval's retriever sent no
`querySyntax` until this tempdoc) — the LUCENE path was structurally untested, not just
unmeasured, because no LUCENE-syntax query set existed for any cataloged corpus. This
script is that missing artifact: a rule-based, LLM-free transform of beir/scifact's own
300 test queries into a LUCENE-syntax variant, scoreable against the IDENTICAL qrels the
SIMPLE baseline already uses (register F-046's 0.7543 nDCG@10 run).

Q-020's own suggested approach (`docs/reference/search-quality-register.md`): "quote the
multi-word key phrase, require the rare term." This script implements exactly that, as
two independently-citable, deterministic rules applied to the ORIGINAL query text in
place (nothing is discarded — every other query term still rides along as free/OR'd
text, so the LUCENE variant asks a superset of what SIMPLE gets, not a stripped-down
keyword soup):

  RULE quote_phrase — find the LONGEST contiguous run (>= 2 tokens) of non-stopword
    words in the query and wrap that exact substring in double quotes (an exact
    phrase-match clause). Ties broken leftmost. A short closed-class stopword list
    (~50 English function words, listed at `_STOPWORDS` below) marks a token
    non-content; this is a one-off ASCII heuristic for THIS harness's phrase-boundary
    detection on English BEIR queries, not a per-language search-engine artifact
    (ADR-0043, language-agnostic analysis, governs the ENGINE's
    analyzer pipeline — SSOT/catalogs, Lucene adapters — which this script never
    touches; it only shapes offline eval-harness INPUT text, same category as
    `corpus_query_variant.py`'s existing `keyword_variant`).

  RULE require_rare_term — among the query's tokens that are (a) not a stopword,
    (b) length >= 3, and (c) present in the corpus vocabulary, pick the ONE with the
    lowest corpus document frequency (rarest = most discriminative; ties broken by
    first-occurrence order — the same DF-ranking convention `corpus_query_variant.
    keyword_variant` already uses, and `document_frequencies` is imported from there
    unmodified so both tools agree on what a "token" and a "document frequency" are).
    Prefix that one token occurrence with `+` (Lucene MUST). If the rare term already
    falls inside the `quote_phrase` span, the requirement is already structurally
    satisfied by the exact-phrase match — no redundant second clause is added; the
    query's provenance instead records `require_rare_term_absorbed`, so the rule
    coverage stats never silently double-count.

  RULE escape (always applied, not a structural choice) — every Lucene special
    character (`+ - && || ! ( ) { } [ ] ^ " ~ * ? : \\ /`) present in the ORIGINAL
    query text is backslash-escaped BEFORE the two structural rules run, so any
    metacharacter that happens to occur in a biomedical sentence (colons, slashes,
    parens, percent signs are common in scifact) never becomes an accidental Lucene
    operator. This mirrors the engine's own escaping discipline for the same hazard
    (`KnowledgeSearchEngine#escapeLuceneSyntax`, cited in F-046's register entry).

Nothing here is an LLM call or a paid API call — `derive_lucene_query` is a pure
function of `(text, df)`, and `df` (corpus document frequency) is computed once from a
local, byte-for-byte materialized mirror of beir/scifact's own corpus (via ir_datasets,
already cached offline by every prior scifact eval run in this repo). Determinism:
docs/queries/qrels are all written sorted by id, so two runs against the same cached
source produce byte-identical output (pinned by `tests/test_lucene_query_derivation_q020.py`).

Usage (materializes BOTH a local SIMPLE mirror and the LUCENE variant, both scoreable
against byte-identical qrels):

    python scripts/jseval/experiments/lucene_query_derivation_q020.py

    # Baseline (SIMPLE, unchanged default -- also directly runnable as `--dataset scifact`,
    # this mirror exists only so both arms share one index-build recipe):
    jseval run --dataset mixed/scifact-mirror-q020 --modes hybrid --pipeline \\
        --start-backend --clean --json

    # Comparison arm (LUCENE syntax on the SAME corpus + SAME qrels):
    jseval run --dataset mixed/scifact-lucene-q020 --modes hybrid --pipeline \\
        --start-backend --clean --query-syntax lucene --json

Recipe + rationale write-up: `scripts/jseval/q020-corpora/scifact-lucene-v1/README.md`.
Output (gitignored, regenerate anytime with this script): `datasets/mixed/scifact-mirror-q020/`
and `datasets/mixed/scifact-lucene-q020/`.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from jseval._paths import REPO_ROOT  # noqa: E402
from jseval.corpus_query_variant import document_frequencies  # noqa: E402

TOOL_VERSION = "experiments.lucene_query_derivation_q020/1"

# --- Rule identifiers (cited in per-query provenance + coverage stats) -----------------
RULE_QUOTE_PHRASE = "quote_phrase"
RULE_REQUIRE_RARE_TERM = "require_rare_term"
RULE_REQUIRE_RARE_TERM_ABSORBED = "require_rare_term_absorbed"
ALL_RULE_IDS = (RULE_QUOTE_PHRASE, RULE_REQUIRE_RARE_TERM, RULE_REQUIRE_RARE_TERM_ABSORBED)

_MIN_PHRASE_TOKENS = 2
_MIN_RARE_TERM_LEN = 3

# Word-boundary token finder over the ORIGINAL (mixed-case) text -- deliberately a
# different literal than corpus_query_variant._TOKEN_RE (which only matches lowercase
# a-z0-9 and is meant to run AFTER `.lower()`): this one locates token SPANS in the
# original string so the quoted phrase/required-term substitutions can be inserted
# in place. Each matched span is lowercased before consulting `df`/`_STOPWORDS`, so
# both regexes agree on token IDENTITY even though only this one preserves position.
_TOKEN_SPAN_RE = re.compile(r"[A-Za-z0-9]+")

# Standard Lucene QueryParser special characters (see F-046's own
# `KnowledgeSearchEngine#escapeLuceneSyntax`, which escapes the same set).
_LUCENE_SPECIALS = frozenset('+-&|!(){}[]^"~*?:\\/')

# A short, fixed closed-class English stopword list -- NOT a search-engine analyzer
# artifact (see module docstring). Deliberately small and hand-listed rather than
# imported from an NLP library, so the rule set stays self-contained and auditable
# from this one file.
_STOPWORDS = frozenset({
    "a", "an", "the", "of", "in", "on", "at", "to", "for", "and", "or", "is", "are",
    "was", "were", "be", "been", "being", "with", "by", "from", "as", "that", "this",
    "these", "those", "it", "its", "which", "who", "whom", "has", "have", "had", "do",
    "does", "did", "not", "no", "but", "if", "than", "then", "so", "such", "can",
    "could", "will", "would", "should", "may", "might", "must", "shall", "there",
    "their", "they", "them", "into", "over", "under", "about", "between", "during",
})


def _escape_lucene(fragment: str) -> str:
    """Backslash-escape every Lucene special character in ``fragment`` (RULE escape)."""
    out = []
    for ch in fragment:
        if ch in _LUCENE_SPECIALS:
            out.append("\\")
        out.append(ch)
    return "".join(out)


def _find_token_spans(text: str) -> list[tuple[str, int, int]]:
    return [(m.group(0), m.start(), m.end()) for m in _TOKEN_SPAN_RE.finditer(text)]


def _longest_content_run(tokens: list[tuple[str, int, int]]) -> tuple[int, int] | None:
    """Longest contiguous run (>= `_MIN_PHRASE_TOKENS`) of non-stopword token indices.

    Returns ``(start_idx, end_idx)`` (end exclusive) into ``tokens``, or ``None`` when no
    run of the minimum length exists. Ties (equal-length runs) are broken leftmost --
    the first-encountered run of the max length wins, so re-running is deterministic.
    """
    best: tuple[int, int] | None = None
    run_start: int | None = None
    for i, (word, _s, _e) in enumerate(tokens):
        is_content = word.lower() not in _STOPWORDS
        if is_content:
            if run_start is None:
                run_start = i
            continue
        if run_start is not None:
            if i - run_start >= _MIN_PHRASE_TOKENS and (
                best is None or (i - run_start) > (best[1] - best[0])
            ):
                best = (run_start, i)
            run_start = None
    if run_start is not None:
        end = len(tokens)
        if end - run_start >= _MIN_PHRASE_TOKENS and (
            best is None or (end - run_start) > (best[1] - best[0])
        ):
            best = (run_start, end)
    return best


def _pick_rare_term(tokens: list[tuple[str, int, int]], df: "Counter[str]") -> int | None:
    """Index (into ``tokens``) of the rarest eligible token, or ``None`` if none eligible.

    Eligible: not a stopword, length >= `_MIN_RARE_TERM_LEN`, present in the corpus
    vocabulary (`df`). Rarest = lowest document frequency; ties broken by
    first-occurrence order in the query -- the same convention
    `corpus_query_variant.keyword_variant` uses for its own DF ranking.
    """
    best_idx: int | None = None
    best_key: tuple[int, int] | None = None
    for i, (word, _s, _e) in enumerate(tokens):
        lw = word.lower()
        if lw in _STOPWORDS or len(lw) < _MIN_RARE_TERM_LEN or lw not in df:
            continue
        key = (df[lw], i)
        if best_key is None or key < best_key:
            best_key = key
            best_idx = i
    return best_idx


def derive_lucene_query(text: str, df: "Counter[str]") -> tuple[str, dict]:
    """Apply the escape + quote_phrase + require_rare_term rules to one query.

    Pure function of ``(text, df)`` -- no randomness, no I/O, no LLM. Returns
    ``(derived_text, provenance)`` where ``provenance`` is
    ``{"rules_fired": [...], "escaped_chars": int}``. ``rules_fired`` is a subset of
    :data:`ALL_RULE_IDS`, in the fixed order (quote_phrase, then one of
    require_rare_term / require_rare_term_absorbed) -- empty when neither structural
    rule finds an eligible span (e.g. an all-stopword or single-content-word query);
    the query text is still run through the escape rule in that case.
    """
    tokens = _find_token_spans(text)
    phrase_range = _longest_content_run(tokens)
    rare_idx = _pick_rare_term(tokens, df)
    absorbed = (
        phrase_range is not None
        and rare_idx is not None
        and phrase_range[0] <= rare_idx < phrase_range[1]
    )

    edits: list[tuple[int, int, str, str]] = []
    rules_fired: list[str] = []
    if phrase_range is not None:
        start = tokens[phrase_range[0]][1]
        end = tokens[phrase_range[1] - 1][2]
        edits.append((start, end, '"', '"'))
        rules_fired.append(RULE_QUOTE_PHRASE)
    if rare_idx is not None:
        if absorbed:
            rules_fired.append(RULE_REQUIRE_RARE_TERM_ABSORBED)
        else:
            start, end = tokens[rare_idx][1], tokens[rare_idx][2]
            edits.append((start, end, "+", ""))
            rules_fired.append(RULE_REQUIRE_RARE_TERM)

    edits.sort(key=lambda e: e[0])
    escaped_chars = sum(1 for ch in text if ch in _LUCENE_SPECIALS)
    if not edits:
        derived = _escape_lucene(text)
    else:
        parts: list[str] = []
        pos = 0
        for start, end, prefix, suffix in edits:
            parts.append(_escape_lucene(text[pos:start]))
            parts.append(prefix + _escape_lucene(text[start:end]) + suffix)
            pos = end
        parts.append(_escape_lucene(text[pos:]))
        derived = "".join(parts)

    return derived, {"rules_fired": rules_fired, "escaped_chars": escaped_chars}


# ---------------------------------------------------------------------------
# BEIR materialization (the one part that touches ir_datasets / real corpus data)
# ---------------------------------------------------------------------------

def materialize_beir_mirror(beir_name: str, dest_dir: Path) -> dict:
    """Write a local, byte-deterministic `mixed/`-shaped mirror of a cataloged BEIR
    dataset: ``corpus.jsonl`` (`_id`/`title`/`text`, sorted by `_id`), ``queries.jsonl``
    (original SIMPLE query text, unchanged, sorted by `_id`), and ``qrels/test.tsv``
    (BEIR 3-column format, sorted by (int qid, doc id)).

    This mirror is the SOURCE both the SIMPLE baseline run and the LUCENE variant
    build from -- writing it once and deriving the LUCENE variant from IT (rather than
    each independently re-reading ir_datasets) is what makes "same corpus, same qrels"
    a structural guarantee instead of an assumption: `corpus_signature` over both
    directories can be compared to confirm the corpus+qrels halves are identical.
    """
    import ir_datasets

    from jseval import dataset_cache
    from jseval.corpora import BEIR_DATASETS

    if beir_name not in BEIR_DATASETS:
        raise ValueError(f"Unknown BEIR dataset: {beir_name!r}. Known: {sorted(BEIR_DATASETS)}")

    dataset_cache.apply_ir_datasets_home()
    dataset = ir_datasets.load(BEIR_DATASETS[beir_name])

    docs = sorted(
        ({"_id": d.doc_id, "title": getattr(d, "title", "") or "", "text": d.text}
         for d in dataset.docs_iter()),
        key=lambda d: d["_id"],
    )
    queries = sorted(
        ({"_id": q.query_id, "text": q.text} for q in dataset.queries_iter()),
        key=lambda q: q["_id"],
    )
    qrels = dataset.qrels_dict()

    dest_dir.mkdir(parents=True, exist_ok=True)
    with (dest_dir / "corpus.jsonl").open("w", encoding="utf-8", newline="\n") as f:
        for d in docs:
            f.write(json.dumps(d, ensure_ascii=False) + "\n")
    with (dest_dir / "queries.jsonl").open("w", encoding="utf-8", newline="\n") as f:
        for q in queries:
            f.write(json.dumps(q, ensure_ascii=False) + "\n")

    qrels_dir = dest_dir / "qrels"
    qrels_dir.mkdir(exist_ok=True)
    rows = sorted(
        ((qid, did, rel) for qid, docs_rel in qrels.items() for did, rel in docs_rel.items()),
        key=lambda r: (_int_or_str(r[0]), r[1]),
    )
    with (qrels_dir / "test.tsv").open("w", encoding="utf-8", newline="\n") as f:
        f.write("query-id\tcorpus-id\tscore\n")
        for qid, did, rel in rows:
            f.write(f"{qid}\t{did}\t{rel}\n")

    return {
        "beir_name": beir_name,
        "beir_slug": BEIR_DATASETS[beir_name],
        "doc_count": len(docs),
        "query_count": len(queries),
        "qrels_row_count": len(rows),
    }


def _int_or_str(qid: str):
    try:
        return (0, int(qid))
    except ValueError:
        return (1, qid)


# ---------------------------------------------------------------------------
# Variant derivation from a local mirror (pure I/O glue around `derive_lucene_query`)
# ---------------------------------------------------------------------------

def build_lucene_variant(source_dir: Path, dest_dir: Path) -> dict:
    """Derive the LUCENE-syntax variant of ``source_dir`` -> ``dest_dir``.

    Copies ``corpus.jsonl`` and ``qrels/`` byte-for-byte (verbatim -- the SAME corpus,
    the SAME qrels; only the queries move), rewrites ``queries.jsonl`` with
    :func:`derive_lucene_query` applied to every query, and writes ``metadata.json``
    with the rule descriptions, tool version, source corpus signature, and aggregate
    rule-coverage stats. Every derived query keeps its source `_id` unchanged (a 1:1
    id-preserving map), which is what makes it qrels-compatible: the qrels file is a
    verbatim copy, so it still keys on exactly the ids the derived queries carry.
    """
    import shutil

    from jseval.corpus_identity import corpus_signature

    corpus_path = source_dir / "corpus.jsonl"
    queries_path = source_dir / "queries.jsonl"
    qrels_src = source_dir / "qrels"
    for p in (corpus_path, queries_path, qrels_src):
        if not p.exists():
            raise FileNotFoundError(f"Source dataset incomplete, missing: {p}")

    df = document_frequencies(corpus_path)

    dest_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(corpus_path, dest_dir / "corpus.jsonl")
    qrels_dest = dest_dir / "qrels"
    if qrels_dest.exists():
        shutil.rmtree(qrels_dest)
    shutil.copytree(qrels_src, qrels_dest)

    source_ids: list[str] = []
    out_lines: list[str] = []
    coverage: Counter[str] = Counter()
    for line in queries_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        obj = json.loads(line)
        qid = str(obj["_id"])
        source_ids.append(qid)
        derived_text, provenance = derive_lucene_query(obj["text"], df)
        bucket = ",".join(provenance["rules_fired"]) or "none"
        coverage[bucket] += 1
        out_obj = {
            "_id": qid,
            "text": derived_text,
            "source_text": obj["text"],
            "lucene_rules": provenance["rules_fired"],
            "escaped_chars": provenance["escaped_chars"],
        }
        out_lines.append(json.dumps(out_obj, ensure_ascii=False))

    (dest_dir / "queries.jsonl").write_text(
        "".join(line + "\n" for line in out_lines), encoding="utf-8",
    )

    metadata = {
        "tool_version": TOOL_VERSION,
        "source_dataset": _dataset_display_name(source_dir),
        "source_corpus_signature": corpus_signature(source_dir),
        "dest_corpus_signature": corpus_signature(dest_dir),
        "rules": {
            RULE_QUOTE_PHRASE: (
                "Quote the longest contiguous run (>= 2 tokens) of non-stopword words "
                "as an exact Lucene phrase clause; ties broken leftmost."
            ),
            RULE_REQUIRE_RARE_TERM: (
                "Prefix the single rarest eligible token (lowest corpus document "
                "frequency, ties broken by first occurrence) with `+` (Lucene MUST)."
            ),
            RULE_REQUIRE_RARE_TERM_ABSORBED: (
                "Same selection as require_rare_term, but the chosen term already "
                "falls inside the quote_phrase span -- no separate `+` clause added."
            ),
            "escape": (
                "Always applied: every Lucene special character in the source text "
                "is backslash-escaped before the structural rules run."
            ),
        },
        "total_queries": len(source_ids),
        "rule_coverage": dict(sorted(coverage.items())),
    }
    (dest_dir / "metadata.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
    )
    return metadata


def _dataset_display_name(source_dir: Path) -> str:
    parts = source_dir.parts
    for prefix in ("golden", "mixed"):
        if prefix in parts:
            idx = parts.index(prefix)
            return "/".join(parts[idx:])
    return source_dir.name


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--beir-name", default="scifact",
                         help="Cataloged BEIR dataset key (jseval.corpora.BEIR_DATASETS).")
    parser.add_argument("--datasets-dir", type=Path, default=None,
                         help="Override the datasets/ root (default: <repo>/datasets).")
    parser.add_argument("--mirror-name", default=None,
                         help="Output name for the local SIMPLE mirror "
                              "(default: mixed/<beir-name>-mirror-q020).")
    parser.add_argument("--variant-name", default=None,
                         help="Output name for the LUCENE variant "
                              "(default: mixed/<beir-name>-lucene-q020).")
    args = parser.parse_args(argv)

    base = args.datasets_dir or (REPO_ROOT / "datasets")
    mirror_name = args.mirror_name or f"mixed/{args.beir_name}-mirror-q020"
    variant_name = args.variant_name or f"mixed/{args.beir_name}-lucene-q020"
    mirror_dir = base / mirror_name
    variant_dir = base / variant_name

    mirror_stats = materialize_beir_mirror(args.beir_name, mirror_dir)
    print(f"Mirrored {args.beir_name} -> {mirror_dir}: "
          f"{mirror_stats['doc_count']} docs, {mirror_stats['query_count']} queries, "
          f"{mirror_stats['qrels_row_count']} qrels rows")

    variant_meta = build_lucene_variant(mirror_dir, variant_dir)
    print(f"Derived LUCENE variant -> {variant_dir}: "
          f"{variant_meta['total_queries']} queries, "
          f"rule_coverage={variant_meta['rule_coverage']}")
    print()
    print("Future measurement campaign, one command per arm:")
    print(f"  jseval run --dataset {mirror_name} --modes hybrid --pipeline "
          f"--start-backend --clean --json")
    print(f"  jseval run --dataset {variant_name} --modes hybrid --pipeline "
          f"--start-backend --clean --query-syntax lucene --json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
