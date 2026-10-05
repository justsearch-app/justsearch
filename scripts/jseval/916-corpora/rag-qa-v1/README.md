# `rag-qa-v1` — RAG question / gold-doc / gold-span fixture (tempdoc 916 Part 1)

## Why it exists

The lane-E brief asks for "RAG answer quality on the 845/881 question sets". Tempdoc 916 §B.11
established that **those question sets do not exist as artefacts** — `845` runs inline prose probes
against a live corpus of JustSearch's own docs, and `881` has no question set at all. This is the
replacement instrument, built once in Part 1 and reusable by Part 3.

It exists specifically so a chunk-size arm can report a RAG column. Building it *after* the sweep
would mean re-running every arm to get that column, which is why 916 §C.4 calls it step 0.

## What is committed here

`recipe.json` and this README, plus `generate.py`. **No corpus, query, span or answer text.** That
is the convention every other real-external-dataset member follows
(`scripts/jseval/789-corpora/enron-qa-answers/recipe.json` states it outright) and it matches
`.gitignore`'s wholesale exclusion of `datasets/`.

## Rules (deterministic, rule-based, no LLM, no paid calls)

- **question** = the qrel's query text, verbatim.
- **gold document** = the lexicographically first corpus id the qrel marks relevant (`score >= 1`)
  whose document is at least `min_gold_chars` (2000, the shipped `CHUNK_THRESHOLD_CHARS`) long.
  Only gold documents long enough to be *chunked* are eligible — a fixture of un-chunked gold
  documents would be blind to the variable this campaign sweeps.
- **gold span** = the sentence of the gold document maximizing `|Q ∩ S| / sqrt(|S|)` over
  lowercased Unicode word tokens (sentences under 20 chars excluded, ties to the earliest
  sentence), located back into the raw document with `jseval.evidence_offset.locate_offset`.
- **sentence boundaries** = `(?<=[.!?])\s+`, which is `ChunkSplitter.SENTENCE_END`, so a gold span
  never straddles a boundary the chunker would not have chosen either.
- **no sampling, no seed** — the fixture is the first 50 eligible qrel ids in sorted order, so the
  recipe plus `generate.py` reproduce it byte-for-byte. Verified: two runs to different output
  directories produce identical digests.

## There is no gold ANSWER

None of the three corpora ships one — every `legal-clerc-200` `queries.json` `answer` field is
empty, and `enron-qa` and BEIR have no answer field at all. The gold **span** therefore occupies
the `answer` slot of the MultiHop-RAG shape `jseval tier2-eval` parses. Consequences, stated so
nobody reads the wrong column:

| column | meaningful here? |
| :--- | :--- |
| `correct_exact` | **no** — a whole sentence will not equal a generated answer |
| `correct_substring` | **no** — same reason |
| `correct_has_intersection` | yes (the paper's own raw scoring) |
| retrieval recall over `evidence_list` | yes, subject to the title-binding caveat below |
| AI-judge groundedness against the span | **yes — this is the intended reading** |

## Reconstruction

```bash
cd scripts/jseval/916-corpora/rag-qa-v1
python generate.py                       # → scripts/jseval/tmp/916-part1/rag-qa-v1/
python -m pytest ../../tests/test_916_rag_qa_fixture.py -q
```

The pytest validates the recipe's schema and self-consistency offline, and — when the corpora are
present — regenerates the fixture and asserts the digests still match the pinned ones.

## Scoring an arm (needs a backend; NOT part of the preparation window)

```bash
# 1. dev stack up for the arm under test, then:
#    ai_activate {chatProfile: "standard"}   <- REQUIRED, see below
python -m jseval tier2-eval \
  --queries scripts/jseval/tmp/916-part1/rag-qa-v1/mixed_enron-qa/queries.json \
  --base-url http://127.0.0.1:<api-port> \
  --llm-url  http://127.0.0.1:8080 \
  --top-k 10 --max-context-tokens 4096 \
  --output-dir scripts/jseval/tmp/916-part1/rag/<arm>
```

**Chat profile: `standard`, not the dev-default `compact`.** `tier2-eval` refuses a compact model
outright (`CompactModelNotAllowedError`), and quality-sensitive verification runs on `ai_activate {chatProfile:"standard"}`. Do not pass
`--allow-compact-model` for a reading that will be cited.

**Judge tier: Tier 4 — AI Judge (Semantic Eval)**, `docs/explanation/09-testing-strategy.md`. It is
agent-driven and on demand, not an automated pipeline; the gold span is what the judge grades
groundedness against.

`python -m jseval rag-eval` is **not** the instrument for this fixture: it is a Gradle wrapper
around `RagQualityEvalTest`, whose question set is a baked-in Java resource
(`rag-eval-truth.v1.json`), not a CLI argument.

## Known limit that must be closed before any recall number is believed

`tier2-eval` binds a gold document by matching `evidence_list[].title` against the retrieved
`parent_doc_id` **path**. This fixture writes the corpus id into `title` on the expectation that it
is the ingested filename stem. That expectation is **not verified offline**. The first sweep window
must check it — index one arm, run one query, and confirm the returned `parent_doc_id` contains the
gold id — before any retrieval-recall figure from this fixture is cited.

## License

`EnronQA` (`MichaelR207/enron_qa_0922`, arXiv 2505.00263) derives from the public Enron corpus;
`CLERC` (`jhu-clsp/CLERC`) derives from US federal case law; `beir/scifact` is covered by the BEIR
project's own terms. See each upstream dataset card. **No corpus content is committed to this
repository** — only this recipe and the generation script; the materialized fixture lands under the
gitignored `scripts/jseval/tmp/` root.
