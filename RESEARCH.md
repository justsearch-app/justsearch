# Research

**Agent-utility campaign freeze (2026-11-09):** Fresh and resumed live agent-utility campaigns are frozen. Their commands and runbooks remain available for historical reproduction and offline analysis, along with published evidence and offline replay. Neighboring experiments retain their existing status.

JustSearch is also a research project, with two lines of work.

- **Uses.** What can be done with what JustSearch learns about a collection of files when it
  indexes it, beyond answering a search, and which of those things are worth anything to a
  person or an AI assistant. Improving search itself is part of it.
- **Foundation.** Which of the things JustSearch is built on are worth replacing, with what,
  and what that would gain or cost. In particular, whether AI agents can write replacements
  for long-established libraries that hold up on real files.

Questions are tried in JustSearch itself; a finding does not by itself change what the
product is for (see [NON-GOALS](NON-GOALS.md)).

Findings stay internal by default; "this did not help" is a result too. A finding is
published only once it is confirmed (checked against a baseline, repeated, and tested on
more than one collection), only when it matters beyond JustSearch, and only by a separate
decision each time.

Published search-quality evidence is in the [benchmark methodology](docs/reference/benchmarks/methodology.md),
with the numbers in the [README](README.md#benchmarks).
Everything needed to rerun the search-quality measurements is in the repository;
see the [methodology's reproduction instructions](docs/reference/benchmarks/methodology.md#reproduce-it).

Published results for AI agents using JustSearch are in the
[agent-utility reference](docs/reference/benchmarks/agent-utility.md), summarised below.

## Agent results

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
