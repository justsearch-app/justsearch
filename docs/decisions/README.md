---
title: Architecture Decision Records
type: decision
status: stable
description: "Index of architectural decisions with MADR-lite template."
---

# Architecture Decision Records

This directory captures significant architectural decisions using a lightweight [MADR](https://adr.github.io/madr/) template. Each record documents the context, decision, consequences, and alternatives considered.

ADRs complement the explanation docs — they capture *why not* and *what else was considered*, which explanation docs typically omit.

## Conventions

- **Numbering:** `NNNN-short-title.md` (zero-padded, sequential)
- **Append-only:** Don't modify Context, Decision, or Consequences after acceptance. Superseded decisions get a status change and a link to the replacement ADR; the original reasoning stays intact.
- **Cross-references:** ADRs link to the explanation doc that covers the topic in depth.
- **`probes:`** — every ADR names the mechanical probes that fail when its load-bearing premise drifts (see below).
- **`last_reviewed:`** — the date the decision was last re-read against the code, `YYYY-MM-DD`.
- **`status:` is the *doc-lifecycle* field, not the ADR-vocabulary one.** Write `stable` for a
  live decision; the ADR words (Accepted / Superseded by ADR-XXXX / Rejected) belong in the body's
  `## Status` section. This matters mechanically: `scripts/docs/llmstxt-generate.mjs:149` includes
  a doc only when `status` is **exactly** `stable`, `in-progress` or `advisory`, so
  `status: accepted` silently drops the ADR out of `docs/llms.txt` — the index agents actually
  read. A retired decision (`superseded`, `rejected - …`) dropping out is intended; a live one
  dropping out is not. The `adr-coverage` gate is unaffected either way: it prefix-matches
  `accepted*`/`stable*` as live (`enforcer.mjs:77`), so the gate will not catch this for you.

### Frontmatter: `probes:` and `last_reviewed:`

A decision nobody re-reads becomes prose that outlives its reason. Two frontmatter keys,
checked by the `adr-coverage` kernel gate, make that mechanical instead of remembered:

```yaml
probes:
  - adr-0015-six-mcp-tools
last_reviewed: 2026-09-02
```

- **`probes:`** is a YAML list of ids in [`governance/adr-probes.v1.json`](../../governance/adr-probes.v1.json).
  Each register entry carries the ADR's `premise` in prose plus one mechanical restatement
  of it. Kinds, in preference order: `test` / `gate` → `grep-absent` / `grep-present`
  (a symbol, flag or file must / must not exist) → `json-path` (a value in a register is the
  premise) → `file-set` (every self-declared hand-written mirror under a tree is generated,
  registered, or a reasoned exception). A **count** (`expect: N`) is legitimate only where
  the premise *is* the count — ADR-0015's six MCP tools is the instance — never as a general
  growth ratchet.
- **What a `test` / `gate` probe actually checks is existence, not correctness.** It asserts
  that the named test file still declares the pinned member, that the kernel gate id is still
  registered, or that the `scripts/ci` check still exists *and is still named* by the
  pre-merge table or a `.github/workflows/` file (a textual reference — the probe reads those
  files, it does not trace execution). It does **not** run the test or the check — a disabled
  or gutted test satisfies it. Its job is to notice that an ADR's enforcement was deleted or
  renamed out from under it; running the enforcement is the enforcement's own job.
- When a premise has no cheap mechanical form, say so instead of leaving the key off:
  `probes: none - <reason>`. A live (`accepted…` / `stable…`) ADR with neither — and a bare
  `probes: none` with no reason counts as neither — raises `adr-coverage/no-probe` (warning).
- **`last_reviewed:`** older than 183 days raises `adr-coverage/review-stale` (warning), and
  so does a *missing* `last_reviewed`. Update it when you re-read the decision, not when you
  edit the file.

**When a probe fails**, the code has drifted away from the decision. The fix is to
re-examine and amend the ADR (below) — never to edit the probe until it goes green. That
inversion is the whole point of the register: `adr-coverage/probe-failed` is a prompt to
re-decide, not a lint to satisfy.

Run it with `node scripts/governance/run.mjs --gate adr-coverage --mode gate`.

## Template

```markdown
---
title: "Decision Title"
type: decision
status: stable
description: "One-line summary."
date: YYYY-MM-DD
probes:
  - adr-NNNN-<premise-slug>
last_reviewed: YYYY-MM-DD
---

# ADR-NNNN: Decision Title

## Status
Accepted | Superseded by ADR-XXXX | Deprecated

## Context
[Problem statement and forces at play]

## Decision
[What was decided and why]

## Consequences
[Positive and negative outcomes]

## Alternatives Considered
### Alternative A
[Description, pros, cons, why rejected]
```

## ADR Lifecycle

ADRs should be reviewed when any of these triggers occur:

- A technology described in the ADR is replaced (mark `status: Superseded`)
- A module referenced in the ADR is deleted from the codebase
- A follow-up ADR contradicts or narrows a prior decision

Superseded ADRs are retained for historical context but must include a note directing readers to the current approach.

The triggers above are events you have to notice. The two frontmatter keys are the part
that does not depend on noticing: a failing probe or a stale `last_reviewed` says the
decision is due for the procedure below. There is no `/adr-review` skill and no cron — the
gate plus the review window *are* the schedule.

## How to re-examine an ADR

The procedure below is tempdoc 269's, compressed. It is what "re-examine" means when a
probe fails, a lifecycle trigger fires, or `last_reviewed` goes stale.

1. **Read the ADR as written**, not as remembered. Name its load-bearing premise in one
   sentence — the claim about the world that, if false, makes the decision wrong. Most
   drift is a premise that quietly stopped being true, not a decision that was wrong.
2. **Verify the premise against `main`**, primary source only (`file:line`), never a
   tempdoc's summary and never the ADR's own prose. If a probe exists, its failure detail
   already names the drift; confirm it by reading the code it points at.
3. **Classify the outcome** — exactly one of:
   - *still true* — update `last_reviewed`, and add a probe if the check you just ran by
     hand can be written down;
   - *narrowed* — the decision holds for less than it claims. Amend the description and
     body to what is actually true, keep the original reasoning intact (append-only), and
     tighten the probe to the narrower claim;
   - *superseded* — a later decision replaced it. Set `status`, name the successor, and
     retire the probe;
   - *never built* — the decision was accepted and nothing shipped. Retire it rather than
     leaving an aspiration with a green status; an absence probe (`grep-absent`) keeps it
     honest if someone starts building it later.
4. **Amend, don't rewrite.** Context / Decision / Consequences are append-only after
   acceptance. Corrections go in a dated amendment section at the end of the ADR, with the
   evidence that forced them.
5. **Update the register in the same change**: the ADR's `probes:` list, the premise text in
   `governance/adr-probes.v1.json`, `last_reviewed`, and this file's Decision Log row.
6. **Route what you found and will not fix here.** A defect the re-examination surfaced goes
   to the owning tempdoc or its domain register at discovery — see the
   `log-pre-existing-issues` rule in `CLAUDE.md`. A note nobody is scheduled to read is the
   failure this mechanism exists to end.

## Decision Log

| ADR | Title | Status | Date |
|-----|-------|--------|------|
| [0001](0001-three-process-architecture.md) | Use three separate OS processes | Superseded by [0049](0049-one-engine-jvm-and-the-boundaries-that-survive.md) | 2026-02-03 |
| [0002](0002-grpc-mmf-hybrid-ipc.md) | Use gRPC + MMF hybrid for IPC | Superseded by [0049](0049-one-engine-jvm-and-the-boundaries-that-survive.md) | 2026-02-03 |
| [0003](0003-direct-lucene-no-elasticsearch.md) | Use Lucene directly without search platform | Accepted | 2026-02-03 |
| [0004](0004-single-tenant-gpu-policy.md) | Single-tenant GPU policy | Accepted | 2026-02-03 |
| [0005](0005-manual-ffm-bindings.md) | Manual FFM bindings for llama.cpp | Accepted | 2026-02-03 |
| [0006](0006-two-pronged-citation-strategy.md) | Two-pronged citation strategy | Accepted | 2026-02-07 |
| [0007](0007-entity-faceting-over-knowledge-graph.md) | Entity faceting over full knowledge graph | Accepted | 2026-01-22 |
| [0008](0008-settings-ephemeral-defaults-safe.md) | Settings are ephemeral, defaults are safe | Accepted | 2026-02-10 |
| [0009](0009-custom-dag-engine-ci-orchestration.md) | Custom DAG engine for CI orchestration | Accepted | 2026-02-23 |
| [0010](0010-local-first-workflow-quality-observability.md) | Local-first workflow quality observability | Superseded (tempdoc 638) | 2026-03-07 |
| [0011](0011-distributed-readiness-spi.md) | Distributed Readiness — Remote Shard SPI | Rejected - never built (retired 2026-09-02) | 2026-03-16 |
| [0012](0012-ui-stack-and-doc-tooling.md) | UI Stack and Documentation Tooling | Superseded | 2026-03-16 |
| [0013](0013-synonyms-fst-placeholder.md) | Synonyms FST Placeholder | Accepted (partially superseded by ADR-0043) | 2025-10-15 |
| [0014](0014-pipeline-definition-removal.md) | Pipeline Definition Removal | Accepted | 2026-03-16 |
| [0015](0015-mcp-tool-surface-design.md) | MCP tool surface design | Accepted (re-examined 2026-09-04) | 2026-04-01 |
| [0016](0016-query-understanding-soft-boost.md) | Query understanding soft-boost over hard-filter | Accepted | 2026-03-28 |
| [0017](0017-ai-bridge-module-decomposition.md) | ai-bridge module decomposition | Accepted | 2026-04-06 |
| [0018](0018-vlm-pdf-extraction-via-chat-model.md) | VLM PDF extraction via chat model | Accepted | 2026-03-23 |
| [0019](0019-cpu-gpu-model-selection-strategy.md) | CPU vs GPU model selection strategy | Accepted | 2026-04-06 |
| [0020](0020-structured-metadata-filterable-facets.md) | Structured metadata fields as filterable facets | Accepted | 2026-03-27 |
| [0021](0021-build-stamp-content-hash.md) | Build-stamp content-hash design | Accepted | 2026-04-06 |
| [0022](0022-recordbuilder-annotation-processor.md) | RecordBuilder annotation processor for API records | Accepted | 2026-04-07 |
| [0023](0023-api-responses-declare-runtime-context.md) | API responses declare their runtime context | Accepted | 2026-03-30 |
| [0024](0024-app-packaging-nsis-per-user-download.md) | App packaging: NSIS, per-user install, download-on-demand | Accepted | 2026-04-06 |
| [0025](0025-core-dto-dual-type-layering.md) | Core DTO dual-type layering (gRPC vs REST) | Accepted | 2026-04-06 |
| [0026](0026-manual-ci-triggering.md) | Manual-Only CI Triggering | Accepted (narrowed by ADR-0044) | 2026-04-22 |
| [0027](0027-metric-catalog-as-telemetry-contract.md) | MetricCatalog as the Telemetry Contract | Accepted | 2026-04-25 |
| [0028](0028-scoped-reverse-path-lookup.md) | Scoped Reverse Path-Hash Lookup | Accepted (amended 2026-09-03) | 2026-04-26 |
| [0029](0029-telemetry-events-bridge-vs-direct-emit.md) | TelemetryEvents Bridge vs Direct-Emit Façade | Accepted | 2026-04-27 |
| [0030](0030-policy-on-operations-vs-mcp-hints.md) | Policy on Operations vs MCP-style hints | Accepted | 2026-04-30 |
| [0031](0031-fe-three-primitives.md) | Frontend three primitives — Operation, Resource, Prompt | Accepted | 2026-06-09 |
| [0032](0032-fe-lit-web-components.md) | Frontend rendering — Lit web components | Accepted | 2026-06-09 |
| [0033](0033-fe-framework-not-product.md) | Frontend as a framework, not a product | Accepted | 2026-06-09 |
| [0034](0034-fe-backend-owned-truth.md) | Backend-owned truth — frontend renders, never owns | Accepted | 2026-06-09 |
| [0035](0035-fe-plugin-boundary.md) | Plugin boundary — truth vs presentation | Accepted | 2026-06-09 |
| [0036](0036-fe-resource-category.md) | Resource Category axis | Accepted | 2026-06-09 |
| [0037](0037-universal-sse-envelope.md) | Universal SSE envelope | Accepted | 2026-06-09 |
| [0038](0038-wire-contract-source-of-truth.md) | Wire contract as a first-class artifact | Accepted (mechanism superseded by tempdoc 564) | 2026-06-09 |
| [0039](0039-contract-substrate.md) | Contract substrate — every published contract is first-class | Accepted (format superseded by tempdoc 564) | 2026-06-09 |
| [0040](0040-wire-contract-format.md) | Wire contract format — protobuf + protovalidate | Superseded by tempdoc 564 | 2026-06-09 |
| [0041](0041-catalog-category-format.md) | Catalog Category format — protobuf enums + metadata | Accepted (format superseded in part by tempdoc 564) | 2026-06-09 |
| [0042](0042-runtime-witness-consumer-presence.md) | Live-registry witness — consumer-presence over the live ContributionRegistry | Accepted | 2026-06-11 |
| [0043](0043-multilingual-by-construction-no-per-language-levers.md) | Multilingual by construction — no per-language levers | Accepted | 2026-06-15 |
| [0044](0044-public-hosted-ci-fact-lanes.md) | Public hosted CI fact lanes | Accepted, amended 2026-09-04 | 2026-06-27 |
| [0045](0045-public-main-history-publication.md) | Public main history publication (validated agent enqueue, commit-safe PR body, managed review comment) | Accepted, amended 2026-09-05 | 2026-06-28 |
| [0046](0046-local-api-trust-boundary.md) | Local API trust boundary | Accepted | 2026-09-02 |
| [0047](0047-context-window-is-a-derived-resource.md) | Context window is a derived resource | Accepted | 2026-09-02 |
| [0048](0048-extraction-isolation-and-indexing-pacing.md) | Extraction isolation and indexing pacing | Accepted (amended 2026-09-07: foreground-gauge premise narrowed) | 2026-09-02 |
| [0049](0049-one-engine-jvm-and-the-boundaries-that-survive.md) | One Engine JVM, and the process boundaries that survive | Accepted | 2026-09-07 |

> ADRs 0031–0041 were graduated on 2026-06-09 from the retired `421` frontend-rewrite kernel
> draft's `50-decisions/` set (authored ~2026-05; the rewrite shipped per tempdoc 563). The
> wire-contract decisions (0038–0041) are retained for historical context but their protobuf-format
> mechanism was superseded by tempdoc 564 (record-as-IDL).
