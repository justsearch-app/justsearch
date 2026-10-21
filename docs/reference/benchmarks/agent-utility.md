---
title: "Agent-Utility Benchmark"
type: reference
status: draft
description: "Methodology, evidence contract, replay path, and current publication state for the paired agent-utility benchmark."
---

# Agent-Utility Benchmark

The agent-utility benchmark measures the marginal effect of giving the same agent access to
JustSearch in addition to ordinary file tools. Condition A uses file tools only. Condition B uses
the same file tools plus the captured JustSearch MCP surface. Conditions are paired by corpus,
query, seed, resolved model, prompt, runtime, and source identity.

## Evidence and verdict contract

Every attempted condition/seed/query cell is represented in the sanitized observation evidence.
The policy pre-registers the exact corpus member, dataset, size, query variant, requested model,
query count, and seed IDs for every required stratum. Missing, extra, or duplicate strata reject
promotion; an empty matrix is unresolved. The expected cell matrix, query-and-gold digest,
materialized corpus digest, source Git state, resolved provider model, search configuration, and
complete MCP `tools/list` surface are captured before or during the run and checked during replay.
Missing or corrupt campaign evidence is an error; replay cannot supply missing identity.

Each stratum must also capture a fully certified 707 member snapshot for the matching materialized
corpus bytes. That snapshot binds the complete 1k/10k by verbose/short-natural family certificate and
the exact query-and-gold bytes. Gate artifacts contain no selectable thresholds: the separate
checked-in 707 policy supplies them before measurement. The snapshot embeds and re-hashes that
policy, the certificate, each canonical measurement, and the backend run manifest/projection used by
retrieval-calibration, union-recall, and leak evaluation. A partial, stale, mismatched, or changed
certificate rejects resume and promotion.

The primary estimand is intention-to-treat: resource-exhausted cells count as incorrect.
Adoption is reported separately over shared attempted pairs.
The cleanly completed-cell view is retained only as a labeled secondary analysis under the record's
`measured` block. In the published `estimands.intention_to_treat` block, the field
`n_per_protocol_pairs` counts retained pairs, including resource-exhausted cells scored as incorrect;
it does not count only pairs where both arms completed cleanly. The checked-in
policy governs loss, paired retention, exclusion symmetry, adoption, uncertainty, per-stratum
promotion, and effect classification. Seed, paired-observation, uncertainty, adoption, and outcome
gates are reported for each required stratum; aggregate counts are descriptive. A valid outcome may
be benefit, harm, null, or adoption-only. Any required inconclusive stratum prevents publication.

Three reporting requirements also block promotion, so a favorable number cannot be published
stripped of the context that qualifies it. Closed-book-at-hero-tier: every required stratum must
carry a measured closed-book accuracy — the same question answered with no tools and no documents —
at or below the policy ceiling, because only a near-zero closed-book floor licenses attributing the
with-tool result to retrieval rather than to memorized corpus content. Completion-triple reporting:
the intention-to-treat headline must be published beside its per-protocol pair count and its per-arm
completion rate, so a result driven by budget exhaustion cannot read as an accuracy result. Schema-
strata reporting: every measured cell must publish its per-question-type breakdown covering every
known schema, and a schema whose observations collapse is reported as an explicit null rather than
dropped — an absent schema is a reporting failure, not a silent absence of data.

## Current result

<!-- agent-utility:generated:start - run: node scripts/docs/gen-public-agent-utility.mjs -->

Accepted publication `agent-utility-hero-2026-07-28` (record `c5a75457b264`, policy `agent-utility-public-v4`). Agents adopted JustSearch, but the campaign did not establish an efficiency or accuracy improvement across every required stratum.

### en-email-enron-raw / mixed/en-email-enron-raw-10k-verbose / 10000 / verbose / sonnet

Stratum outcome: **adoption-only**. Adoption was 100.0% (60/60 eligible cells).

| Measure | Condition A | Condition B (with JustSearch) | Paired delta and interval |
|---|---:|---:|---:|
| Accuracy | 40.0% | 28.3% | -11.7 pp (CI -25.0 pp to +0.0 pp) |
| Provider cache-creation input tokens | unavailable | unavailable | n/a (CI unavailable) |
| Cost (USD) | unavailable | unavailable | n/a (CI unavailable) |

Seeds: 3 (0, 1, 2); queries: 20; expected/observed cells: 120/120; ITT/per-protocol paired observations: 60/60; paired retention: 100.0%.

| Condition | Expected | Attempted | Completed | Excluded | Pending | Exclusion rate |
|---|---:|---:|---:|---:|---:|---:|
| A | 60 | 60 | 56 | 0 | 0 | 0.0% |
| B | 60 | 60 | 48 | 0 | 0 | 0.0% |

| Certification identity | Value |
|---|---|
| Member / dataset / size / query variant | en-email-enron-raw / mixed/en-email-enron-raw-10k-verbose / 10000 / verbose |
| Corpus signature | `6a80af3b5bdbb59d578eedfd00a4c35815ffcf07450afce2de73d4dd818399cb` |
| Certification SHA-256 | `c60d7f0c6434683c2e9ebcd3a3e648cbedbb7e80d37ef76ce1b184f864837a31` |
| Fully certified | yes |
| Scientific gate evidence | n/a |

### en-email-enron-raw / mixed/en-email-enron-raw-1k-verbose / 1000 / verbose / sonnet

Stratum outcome: **adoption-only**. Adoption was 100.0% (60/60 eligible cells).

| Measure | Condition A | Condition B (with JustSearch) | Paired delta and interval |
|---|---:|---:|---:|
| Accuracy | 46.7% | 28.3% | -18.3 pp (CI -35.0 pp to -1.7 pp) |
| Provider cache-creation input tokens | unavailable | unavailable | n/a (CI unavailable) |
| Cost (USD) | unavailable | unavailable | n/a (CI unavailable) |

Seeds: 3 (0, 1, 2); queries: 20; expected/observed cells: 120/120; ITT/per-protocol paired observations: 60/60; paired retention: 100.0%.

| Condition | Expected | Attempted | Completed | Excluded | Pending | Exclusion rate |
|---|---:|---:|---:|---:|---:|---:|
| A | 60 | 60 | 58 | 0 | 0 | 0.0% |
| B | 60 | 60 | 57 | 0 | 0 | 0.0% |

| Certification identity | Value |
|---|---|
| Member / dataset / size / query variant | en-email-enron-raw / mixed/en-email-enron-raw-1k-verbose / 1000 / verbose |
| Corpus signature | `3391fc9781c6dafc4881322337558810b739b1a1d55a4eac5e1f035bec18cff7` |
| Certification SHA-256 | `c60d7f0c6434683c2e9ebcd3a3e648cbedbb7e80d37ef76ce1b184f864837a31` |
| Fully certified | yes |
| Scientific gate evidence | n/a |

### en-legal-clerc / mixed/en-legal-clerc-1k-verbose / 1000 / verbose / sonnet

Stratum outcome: **adoption-only**. Adoption was 100.0% (60/60 eligible cells).

| Measure | Condition A | Condition B (with JustSearch) | Paired delta and interval |
|---|---:|---:|---:|
| Accuracy | 28.3% | 26.7% | -1.7 pp (CI -16.7 pp to +13.3 pp) |
| Provider cache-creation input tokens | unavailable | unavailable | n/a (CI unavailable) |
| Cost (USD) | unavailable | unavailable | n/a (CI unavailable) |

Seeds: 3 (0, 1, 2); queries: 20; expected/observed cells: 120/120; ITT/per-protocol paired observations: 60/60; paired retention: 100.0%.

| Condition | Expected | Attempted | Completed | Excluded | Pending | Exclusion rate |
|---|---:|---:|---:|---:|---:|---:|
| A | 60 | 60 | 51 | 0 | 0 | 0.0% |
| B | 60 | 60 | 52 | 0 | 0 | 0.0% |

| Certification identity | Value |
|---|---|
| Member / dataset / size / query variant | en-legal-clerc / mixed/en-legal-clerc-1k-verbose / 1000 / verbose |
| Corpus signature | `6df707031abcd296773a0bf8c6a7750bb0b8704ce4ab4035105cf88b8df01fae` |
| Certification SHA-256 | `0006c8e58072278c934828af9c167d2b8eecb59c7b8a7e88014bb7572e890fe6` |
| Fully certified | yes |
| Scientific gate evidence | n/a |

### Immutable evidence and replay

- Publication manifest: `scripts/jseval/public-agent-utility/publications/agent-utility-hero-2026-07-28/publication.v1.json`
- Canonical record: `scripts/jseval/agent-utility-records/c5a75457b264e0cfdecf5ab1ac552d3430a93300f3241766b9c72a49be1560bb/utility-comparison-cross-corpus.v1.json`
- Sanitized observation evidence: `scripts/jseval/public-agent-utility/publications/agent-utility-hero-2026-07-28/observations.v1.jsonl`
- Captured policy: `scripts/jseval/public-agent-utility/publications/agent-utility-hero-2026-07-28/policy.v1.json`
- Replay: `python -m jseval utility-replay --publication agent-utility-hero-2026-07-28`

<!-- agent-utility:generated:end -->

## Replay and live rerun

`python -m jseval utility-replay` is a zero-cost operation. It verifies the publication, observation,
record, and policy hashes, recomposes the record from the observation evidence, re-evaluates the
captured policy, and compares the semantic digest.

A live rerun is separate. It requires the licensed corpus sources, a running JustSearch backend,
model credentials, a pre-registered active policy, and explicit budget authorization. Replaying an
accepted publication never contacts those systems.

## Limitations

- No result is public while the checked-in pointer is `current: null`.
- Historical records without complete query, corpus, model, search, and MCP identities are
  permanently pre-contract and cannot be upgraded during replay.
- Publication establishes reproducibility of the captured campaign; it does not generalize beyond
  the recorded corpora, model snapshot, query strata, and tool surface.
