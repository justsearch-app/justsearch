# Research

JustSearch is a local-first retrieval engine (keyword + vector + cross-encoder reranking in explicit hybrid mode;
learned-sparse is a separate ablation and is off in this preset),
exposed to Claude-based agents over MCP, with a reproducible evaluation harness (`jseval`) behind every
number it publishes. This page states the actual research position honestly: what's measured, what's
deferred, and what a collaborator would find useful to work on.

## What's measured, and how

Every retrieval-quality number JustSearch publishes traces to one canonical, reproducible release object
(`scripts/jseval/release.v1.json`) — a single sweep of the production-default pipeline across a fixed
corpus set, at one commit, on stated hardware, with cited external baselines shown honestly (not as a "we
beat X" claim — see the comparison-class notes in
[`docs/reference/benchmarks/methodology.md`](docs/reference/benchmarks/methodology.md)). The result numbers
in the README's benchmark table are hand-written from that object and CI-checked against it by
`scripts/ci/check-readme-benchmark-numbers.mjs`.

The current release is `linux-complete-qrels-2026-11-02`, measured on Linux 7.0 (x86_64),
NVIDIA RTX 4070 12 GB, driver 580.178.04, ORT 1.24.3 with host CUDA 12.9/cuDNN 9.
Hybrid nDCG@10: SciFact 0.759, Enron-QA 0.806, MIRACL-de 0.986, MIRACL-fr 0.990.
CE applied to 299/300, 277/300, 305/305 and 343/343 queries respectively; SciFact had one deadline
drop, Enron had 17 deterministic navigational skips and six deadline drops (2.0%, harness verdict ok).
Legal hybrid is withheld: 25/200 (12.5%) deadline misses under the default 200 ms budget for roughly
260-word queries, versus 60% under heavier load earlier; 174/200 reranked, one deterministic skip.
Its lexical/vector/splade ablations remain available with the caveat that this is a 198-document pool,
all documents relevant to at least one query. This Linux cohort is a new release, not a Windows rerun.

The multilingual research question is whether a locale-invariant pipeline (ICU + NFC + lowercase,
without per-language analyzers, stopwords, stemmers, or synonym lists) retains useful retrieval quality.
The MIRACL de/fr dev measurements quantify that question on our sampled document pools. Those pools
are far smaller than MIRACL's official multi-million-passage collections, so the results do not establish
a head-to-head win or general multilingual competitiveness. The earlier Windows release's claims are
superseded because its MIRACL judgments were truncated and its hybrid pipeline was mislabelled; see
`docs/reference/benchmarks/methodology.md` for the history and current measurement provenance.

## What's deferred, honestly

<!-- agent-utility:generated:start - run: node scripts/docs/gen-public-agent-utility.mjs -->

Accepted publication `agent-utility-hero-2026-07-28` (record `c5a75457b264`, policy `agent-utility-public-v4`). Agents adopted JustSearch, but the campaign did not establish an efficiency or accuracy improvement across every required stratum.

Accuracy intervals are recomputed from the published observations under v5 question-level semantics (95% question-cluster bootstrap; seeds are replicates within questions). Point estimates and the historical adoption-only outcome are unchanged. Token and cost intervals remain descriptive cell-level intervals. Provenance: `scripts/jseval/public-agent-utility/question-level.v1.json`; reproduce with `python -m jseval.utility_public_projection --check`. The hash-pinned v4 bundle is unchanged.

| Corpus stratum | Agent model | Outcome | Provider cache-creation token delta | Accuracy delta | Adoption | Paired n |
|---|---|---|---:|---:|---:|---:|
| en-email-enron-raw / mixed/en-email-enron-raw-10k-verbose / 10000 / verbose | sonnet | adoption-only | n/a (CI unavailable) | -11.7 pp (CI -26.7 pp to -1.7 pp) | 100.0% | 60 |
| en-email-enron-raw / mixed/en-email-enron-raw-1k-verbose / 1000 / verbose | sonnet | adoption-only | n/a (CI unavailable) | -18.3 pp (CI -38.3 pp to +5.0 pp) | 100.0% | 60 |
| en-legal-clerc / mixed/en-legal-clerc-1k-verbose / 1000 / verbose | sonnet | adoption-only | n/a (CI unavailable) | -1.7 pp (CI -23.3 pp to +13.3 pp) | 100.0% | 60 |

Provider cache-creation input tokens exclude cache reads and retain the provider-specific meaning of that counter. See the [agent-utility benchmark reference](docs/reference/benchmarks/agent-utility.md) for arm distributions, cost, loss, certification, and replay evidence.

<!-- agent-utility:generated:end -->

A related, smaller open question: JustSearch's retrieval-time context-sufficiency classifier (used by
agent-facing endpoints to signal whether retrieved context can actually answer a query) has never been
validated against a labeled dataset — its precision/recall are currently unknown. This is a bounded,
tractable research task (build ~20–30 labeled `(query, context) → answerable?` pairs, measure the classifier
against them) that hasn't been resourced yet.

## Reproducing and extending this work

The retrieval-quality release above is reproducible from a clone — see
[`docs/reference/benchmarks/methodology.md`](docs/reference/benchmarks/methodology.md#reproduce-it) for the
exact commands and corpus slugs. The corpora, fetch recipes, and evaluation harness are all in the repo;
nothing needs to be requested from us to start. Agent-utility evidence is separately replayable at zero
cost only after an accepted publication exists; a live rerun requires licensed corpus sources, model
credentials, a running backend, and explicit budget authorization. See
[`docs/reference/benchmarks/agent-utility.md`](docs/reference/benchmarks/agent-utility.md).

## Looking for a research or institutional collaborator

If you work on information retrieval, agentic evaluation methodology, or a document-heavy scientific/
scholarly workflow and think a reproducible, local-first retrieval backend would be useful to your own
research question — rather than the other way around — we'd like to hear from it. Open an issue, or reach
out directly. We're specifically interested in partners who already have a real document collection and
research question, not a generic "let's collaborate."
