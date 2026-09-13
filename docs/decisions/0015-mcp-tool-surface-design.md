---
title: "ADR-0015: MCP Tool Surface Design"
type: decision
status: stable
description: "Keep a short task-oriented MCP surface; the accepted seven-tool list includes a read-only operation outcome query for recovery (amended 2026-09-13)."
date: 2026-04-01
probes:
  - adr-0015-seven-mcp-tools
last_reviewed: 2026-09-13
---

# ADR-0015: MCP Tool Surface Design

## Status
Accepted

## Context

JustSearch exposes its capabilities to AI agents via MCP (Model Context Protocol) tools. The original design had 7 capability-oriented tools that mapped directly to API endpoints: `search`, `preview`, `retrieve_context`, `match_citations`, `suggest`, `ingest`, and `status`. Agents (particularly small models like Haiku) struggled to compose these tools effectively — 41/50 agents in evaluation used a slow search-then-preview loop (70% accuracy) while only 4/50 discovered `retrieve_context` (100% accuracy, fewer turns, lower cost).

Industry evidence strongly favors fewer, task-oriented tools:
- Block Engineering reduced 30+ tools to 2 and saw significant accuracy improvements.
- AWS Prescriptive Guidance recommends task-oriented over capability-oriented tool design.
- "MCP Tool Descriptions Are Smelly" (arXiv 2602.14878) formalizes why fewer tools outperform many: reduced decision complexity, less schema confusion, and better description-to-behavior alignment.

Additional research on schema complexity (arXiv 2504.19277) showed that adding optional JSON schema parameters without proportional description investment causes 16.1% average degradation in small models. Position bias research (ToolTweak, arXiv 2510.02554) showed agents prefer the first-listed tool by 9.51%.

## Decision

Consolidate to 4 task-oriented MCP tools:

| Tool | Purpose | Maps to previous |
|------|---------|------------------|
| `justsearch_answer` | RAG retrieval + context for answering questions | `retrieve_context` + `match_citations` |
| `justsearch_search` | Search with facets, filters, and exploration | `search` + facets |
| `justsearch_ingest` | File ingestion into the knowledge index | `ingest` (unchanged) |
| `justsearch_status` | System health and enrichment coverage | `status` (unchanged) |

Removed tools: `preview` (agents have file access via their own tools), `suggest` (2/50 usage in eval, dead for QA), `match_citations` (0/50 standalone usage, absorbed into `answer` as `verify_citations` param).

Design principles:

1. **Schema-minimal:** Implement features in the backend, document behavior in description text, keep JSON schema parameters minimal. Only add schema parameters when eval proves the target model actually uses them.
2. **Position-bias exploitation:** Register `justsearch_answer` first because it produces the best results for QA tasks and agents naturally prefer the first-listed tool.
3. **Progressive disclosure:** Use response-level hints (zero results, high hit count, filter tips) rather than front-loading guidance in tool descriptions. Hints are contextual and appear only when relevant.

## Consequences

**Positive:**
- +20pp accuracy on 50-query Haiku eval (72% to 92%).
- Reduced schema complexity — agents spend fewer turns figuring out which tools to compose.
- Progressive disclosure via response hints keeps descriptions concise while still guiding agent behavior.
- Answer-first agents achieve 84% accuracy at $0.023 avg cost (vs 94% at $0.069 for search-explore agents — 3x cheaper for similar quality).

**Negative:**
- Less granular tool control for advanced agents — search and retrieval are separate tools but there is no standalone preview or citation-matching tool.
- Answer tool consolidates search+retrieval; agents wanting search-only exploration must use `justsearch_search` explicitly.
- Tool ordering creates a mild dependency on position bias research remaining valid for future models.

## Alternatives Considered

### Keep 7 capability-oriented tools
Direct API-to-tool mapping. Gives agents maximum flexibility but eval showed 70% accuracy with the dominant usage pattern. Agents spent turns composing tools instead of solving problems. Rejected due to lower accuracy and higher cost.

### Add optional schema parameters for advanced features
Expose filter syntax, facet requests, excerpt options as optional JSON schema params. Research (arXiv 2504.19277) showed 16.1% average degradation in small models when optional params are added without proportional description investment. Rejected — features are implemented in the backend and documented in description text instead.

### Use OpenAPI instead of MCP
OpenAPI is more established but MCP is the emerging standard for AI agent tool integration, supported by Claude, Cursor, and other agent frameworks. Rejected — MCP aligns with the target ecosystem.

## Amendment 2026-09-02: the surface is six tools, not four

Re-examined under decision-review lane B (tempdoc 884). The Decision table above lists
**four** task-oriented tools. Shipped code registers **six**.

`modules/ui/src/main/java/io/justsearch/ui/api/mcp/McpToolSurface.java:213` carries the
section comment *"tools/list — 6 curated tools, position-bias ordered"*, and `listTools()`
at `:216-257` registers, in position order:

| # | Tool | Present in the 2026-04-01 Decision table? |
|---|------|-------------------------------------------|
| 1 | `justsearch_answer` | yes |
| 2 | `justsearch_search` | yes |
| 3 | `justsearch_browse` | **no — added since** |
| 4 | `justsearch_ingest` | yes |
| 5 | `justsearch_status` | yes |
| 6 | `justsearch_runtime_manifest` | **no — added since** |

The same six names are pinned again as the dispatch allowlist at `:434-435`.

### Why the two additions are tools, not parameters

The ADR's frame is accuracy-versus-list-length: each extra entry costs decision complexity,
so an entry earns its place only if folding it into a neighbour would misdescribe it.

- **`browse` is navigation, not retrieval.** `BROWSE_DESC` (`:101-105`) describes listing the
  indexed folder structure — subfolders with file counts and sizes, files when a folder has no
  subfolders, top-level indexed roots when called with no `parent_path`. That is traversal of the
  corpus's *structure*; it takes no query and returns no ranked hits. Expressed as an argument of
  `justsearch_search` it would be a mode that ignores every other argument the search schema
  advertises — precisely the "optional schema parameters without proportional description
  investment" degradation this ADR rejected in its Alternatives.
- **`runtime_manifest` answers a different question than `status`.** `RUNTIME_MANIFEST_DESC`
  (`:260-266`) returns the redacted runtime manifest: the backend's *identity* (instanceId, pid,
  dataDir), lifecycle projection, head/worker state and AI runtime state, for identity-aware
  caching and cross-restart detection. `STATUS_DESC` answers *how healthy is the index* (document
  count, queue depth, readiness, enrichment coverage). An agent asking "am I still talking to the
  same backend?" and an agent asking "is indexing done?" are not the same call.

### What the headline number does and does not attest

The **+20pp (72% → 92%) 50-query Haiku eval in Consequences measured the 4-tool surface.** It is
not evidence about the 6-tool surface, and this amendment does not claim it is. The decision
(short, task-oriented list; position-bias ordering; progressive disclosure via response hints)
stands; its measured effect size is dated 2026-04-01 and applies to the list it was run against.

Probe `adr-0015-six-mcp-tools` pins the count at 6, so a seventh tool fails the gate and forces
this ADR to be re-read before the surface grows again.

## Reassess When

- **The 6-tool surface has not been measured.** Re-run tool-selection accuracy on the shipped
  six-tool surface with the compact chat profile; if it falls below the 92% the 4-tool surface
  measured, consolidate.
- A seventh tool is proposed — the probe fails by construction; decide here, not in the surface.

## Amendment 2026-09-04: the six tools are defined by a typed registry

Re-examined during tempdoc 899 publication after `adr-0015-six-mcp-tools` correctly failed: the
surface no longer contained the inline `tool("justsearch_…")` calls that the probe counted. The
load-bearing product premise is still true. `McpToolSurface.PRODUCTION_TOOL_DEFINITIONS`
(`modules/ui/src/main/java/io/justsearch/ui/api/mcp/McpToolSurface.java:373`) is now the canonical,
position-ordered registry and contains exactly six `ToolDefinition` entries at `:375-414`.
`PRODUCTION_TOOL_NAMES` derives the dispatch allowlist from that same registry at `:495-496`, so
the list and dispatch paths no longer maintain separate name sets.

This is a representation change, not a seventh tool or a narrowing of the decision. The probe now
counts entries in the typed production registry. The six names and their order remain `answer`,
`search`, `browse`, `ingest`, `status`, and `runtime_manifest`; any seventh registry entry still
fails the count premise and forces another decision review.

## Amendment 2026-09-13: a separate operation-outcome recovery query

**Classification: narrowed.** The short task-oriented surface remains the decision; the
six-entry cardinality no longer describes the lane F candidate. At review, origin/main
3a3e8e489 still has six entries in `McpToolSurface.PRODUCTION_TOOL_DEFINITIONS`; lane F
15bac5f90 has seven. Hosted CI34775117498 and the local ADR premise probe correctly rejected
that drift. The accepted target is the existing six tools, in their existing order, followed
by `justsearch_operation_outcome`. This amendment records the decision under delegated lane F
authority before changing the probe; it does not claim the candidate has merged into main.

The additional task is to determine whether an earlier mutation was accepted or completed
after the caller lost its response or the Engine restarted. `McpToolSurface.java:385-387`
defines one required argument, the original UUIDv7 `operationKey`. The registry entry at
`:434-444` is read-only and states that the query never starts or retries work; dispatch at
`:498` reaches `callOperationOutcome` at `:547-560`, which calls the existing operation-outcome
read projection. Its metadata answer distinguishes accepted, running, complete, failed,
unknown and expired. That distinction is needed before deciding whether another mutation is
safe: missing retained history cannot be treated as proof that nothing happened.

The alternatives were re-examined against the existing schemas. `justsearch_status` has no
arguments and answers index health/readiness; an optional operation-key mode would introduce
a different subject and result contract into that tool. Putting this query into a write tool
would blur the guaranteed no-effect recovery check with execution. Omitting the query leaves
MCP callers without the same recovery observation available to HTTP callers. A dedicated final
entry keeps its one-key schema explicit and preserves answer-first ordering for retrieval.
It adds one selection choice; the cardinality remains a deliberate bounded decision.

The original four-tool Haiku results remain evidence only for that four-tool surface.
Neither the six-tool nor this seven-tool list has a new comparative tool-selection accuracy
measurement in this amendment. Existing operation-outcome tests, including
`modules/app-engine/src/test/java/io/justsearch/app/engine/OperationOutcomeQueryTest.java`,
prove outcome/restart/key semantics, not model selection quality. Lane F C2 retains its live
MCP and real-model behavior verification obligations; no earlier accuracy number is reused.

Probe `adr-0015-seven-mcp-tools` now pins exactly seven entries in the same canonical typed
registry. Removing a required tool or adding an eighth fails the premise. Reassess this
amendment if tool-selection evidence favors consolidation, if outcome querying acquires
mutation behavior, or before any eighth entry. A broader tool inventory is not authorized.
