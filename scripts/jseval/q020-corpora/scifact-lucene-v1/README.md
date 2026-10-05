# `mixed/scifact-lucene-q020` — the LUCENE-syntax variant of `beir/scifact`'s queries

The missing artifact behind register **Q-020** (`docs/reference/search-quality-register.md`):
F-046 (tempdoc 821 §P) made `query_syntax: "lucene"` actually reach every retrieval leg, but
every number ever published against `beir/scifact` was measured under the default SIMPLE
parse — LUCENE-syntax retrieval QUALITY was never measured, because no LUCENE-syntax query
set existed for any cataloged corpus. This is that query set.

## Why it exists

The MCP tool description advertises exact phrases and AND/OR/NOT to agents, so an agent's
syntax choice is a live retrieval-quality decision with no evidence behind it (Q-020). Answering
it needs the SAME corpus and the SAME qrels as the SIMPLE baseline, with only the queries
rewritten — otherwise the comparison isn't syntax-only.

## Rules (deterministic, rule-based, no LLM, no paid calls)

Applied to each of `beir/scifact`'s 300 test queries **in place** — every original term still
rides along as free text, so the LUCENE variant is a superset of what SIMPLE gets, not a
stripped keyword soup:

1. **`quote_phrase`** — quote the longest contiguous run (>= 2 tokens) of non-stopword words
   as an exact Lucene phrase clause. Ties broken leftmost.
2. **`require_rare_term`** — prefix the single rarest eligible token (lowest corpus document
   frequency among the query's non-stopword, length >= 3, in-vocabulary tokens; ties broken by
   first occurrence) with `+` (Lucene MUST).
3. **`require_rare_term_absorbed`** — same rare-term selection as above, but the chosen term
   already falls inside the `quote_phrase` span, so no separate `+` clause is added (the
   requirement is already structurally satisfied by the exact-phrase match). Recorded
   separately so rule-coverage stats never double-count.
4. **escape** (always applied, not optional) — every Lucene special character
   (`+ - && || ! ( ) { } [ ] ^ " ~ * ? : \ /`) in the source text is backslash-escaped before
   the structural rules run, mirroring the engine's own `KnowledgeSearchEngine#escapeLuceneSyntax`
   discipline (F-046) — a scifact sentence's stray colon/percent/parenthesis must never become an
   accidental Lucene operator.

Full rule prose + rationale: the module docstring and per-function docstrings in
`scripts/jseval/experiments/lucene_query_derivation_q020.py` — this README is a pointer, not a
fork of that text.

The stopword list used by rules 1/2 is a short, fixed, hand-listed set of ~60 English function
words local to this script (`_STOPWORDS`). It is a one-off eval-harness heuristic for locating
phrase boundaries in English BEIR queries, **not** a search-engine analyzer artifact — it never
touches `SSOT/catalogs/`, the Lucene adapters, or any per-language engine lever (ADR-0043,
language-agnostic analysis, is unaffected).

Per-query provenance (`lucene_rules`, `escaped_chars`, `source_text`) is written directly into
each derived query's `queries.jsonl` record — no separate sidecar file to go stale.

## Reconstruction

```bash
python scripts/jseval/experiments/lucene_query_derivation_q020.py
```

Writes two directories under the gitignored `datasets/` root:

- `datasets/mixed/scifact-mirror-q020/` — a byte-deterministic local mirror of
  `beir/scifact/test` (corpus.jsonl + queries.jsonl [unchanged SIMPLE text] + qrels/test.tsv),
  materialized once via `ir_datasets` (already cached offline by every prior scifact eval run
  in this repo — no network fetch needed). This is the SOURCE both arms below build an index
  from, so "same corpus, same qrels" is a structural guarantee (`corpus_signature` over both
  directories is comparable), not an assumption.
- `datasets/mixed/scifact-lucene-q020/` — the LUCENE variant, derived from the mirror above.
  `corpus.jsonl` and `qrels/` are copied byte-for-byte from the mirror; only `queries.jsonl` is
  rewritten.

Deterministic: `queries.jsonl` in both directories, and `metadata.json` in the variant
directory, are byte-identical across repeated runs against the same cached `ir_datasets` source
(docs/queries/qrels are all written sorted by id; no wall-clock/random fields are recorded).

## Future measurement campaign — one command per arm

```bash
# SIMPLE baseline (functionally identical to the existing `--dataset scifact` runs; this
# mirror exists only so both arms share one index-build recipe and one corpus_signature):
jseval run --dataset mixed/scifact-mirror-q020 --modes hybrid --pipeline \
    --start-backend --clean --json

# LUCENE comparison arm (same corpus, same qrels, `--query-syntax lucene` on the wire):
jseval run --dataset mixed/scifact-lucene-q020 --modes hybrid --pipeline \
    --start-backend --clean --query-syntax lucene --json
```

Both runs' `summary.json` record `query_syntax` ("simple" / "lucene") for citation. Score both
against the SAME `qrels/test.tsv` (copied verbatim into both directories) — the nDCG@10 delta
between the two runs is then attributable to syntax alone, not corpus or qrels drift.

## License

`beir/scifact` (via `ir_datasets`) — see the BEIR project's own licensing terms. No corpus
content is committed to this repository — only this recipe and the generation script; the
materialized mirror and variant live under the wholesale-gitignored `datasets/` root.
