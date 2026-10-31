# Research

JustSearch is a local-first retrieval engine (hybrid BM25 + dense + learned-sparse + cross-encoder rerank),
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

**The one claim we think is genuinely defensible and hard to copy:** multilingual-competitive retrieval
*without any per-language tuning* — no per-language analyzers, stopwords, stemmers, or synonym lists. That's
an architectural stance (ICU + NFC + lowercase only), not a tuning trick, and it's falsifiable against the
MIRACL de/fr numbers in the release. Most retrieval systems add per-language machinery instead of avoiding
it; this is the finding worth someone else's scrutiny. **One honest caveat on that number:** our MIRACL
subsample uses a smaller sampled document pool than MIRACL's own official multi-million-passage collection,
on a different split (test vs. the published dev baselines) — so it is not a clean head-to-head win against
the official leaderboard, only evidence that dropping per-language tuning doesn't show a measurable quality
collapse on our own corpus. Full comparison-class detail: `docs/reference/benchmarks/methodology.md`.

## What's deferred, honestly

<!-- agent-utility:generated:start - run: node scripts/docs/gen-public-agent-utility.mjs -->

Accepted publication `agent-utility-hero-2026-07-28` (record `c5a75457b264`, policy `agent-utility-public-v4`). Agents adopted JustSearch, but the campaign did not establish an efficiency or accuracy improvement across every required stratum.

| Corpus stratum | Agent model | Outcome | Provider cache-creation token delta | Accuracy delta | Adoption | Paired n |
|---|---|---|---:|---:|---:|---:|
| en-email-enron-raw / mixed/en-email-enron-raw-10k-verbose / 10000 / verbose | sonnet | adoption-only | n/a (CI unavailable) | -11.7 pp (CI -25.0 pp to +0.0 pp) | 100.0% | 60 |
| en-email-enron-raw / mixed/en-email-enron-raw-1k-verbose / 1000 / verbose | sonnet | adoption-only | n/a (CI unavailable) | -18.3 pp (CI -35.0 pp to -1.7 pp) | 100.0% | 60 |
| en-legal-clerc / mixed/en-legal-clerc-1k-verbose / 1000 / verbose | sonnet | adoption-only | n/a (CI unavailable) | -1.7 pp (CI -16.7 pp to +13.3 pp) | 100.0% | 60 |

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
