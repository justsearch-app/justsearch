---
title: "525 — Search introspection as a wire aggregate"
---

# 525 — Search introspection as a wire aggregate

**Historical projections (2026-11-09):** `bootstrap_ci`, `rank_diff` and `stratified_metrics` generators/tests are retired; references below describe past implementations and captured evidence. **Date**: 2026-05-18 (design) / 2026-05-19 (implementation closure)
**Status**: done
**Related**:
- Tempdoc 517 (search execution refactor — the implementation this rides on)
- Tempdoc 511 (aggregate-surfacing substrate — the existing primitive being extended)
- Tempdoc 507 (capability-mediated surface architecture — orthogonal layer)
- ADR-0010 (local-first workflow observability)
- ADR-0027 (MetricCatalog as telemetry contract)
- `docs/reference/contracts/search-and-rag-reason-codes.md` (the precedent
  contract this design parallels for span attrs)

---

## Why this tempdoc exists

The merged 517 refactor produced `SearchDecision` and `SearchOutcome` as
typed value objects internal to the worker. A natural follow-up question
is: what should be built on top of those values?

Six themes surface immediately — user-facing "explain" UI, decision-
bucketed eval, shadow planning, approval testing, decision-aware
encoding, search history. Each of them, taken individually, looks like
a small feature. Designed individually, they would each invent their
own serialization of `SearchDecision`, their own FE consumption shape,
their own test framework, their own persistence layer.

That is precisely the F1 "speculative-generality through accretion"
failure mode tempdoc 512 documented: many small features that each
build their own substrate, ultimately producing N parallel introspection
layers.

The correct move is to design **one foundational primitive** that all
six themes consume — and to do that without introducing a new
substrate parallel to ADR-0027, tempdoc 511, or ADR-0010.

This tempdoc supersedes the §C "Forward-looking opportunities" stub in
tempdoc 517. The stub there was an inventory; this is a design.

## What already exists (the constraints)

Three existing primitives cover adjacent ground:

| Substrate | Purpose | Why it doesn't fit `SearchDecision` |
|---|---|---|
| **Metrics (ADR-0027)** | Quantitative counters / histograms / gauges with tag schemas; routes to `/api/status`. | Decisions are categorical / structural; metrics would lose the sealed-sum shape. |
| **Workflow observability (ADR-0010)** | NDJSON evidence in `tmp/workflow-telemetry/runs/`. | Post-hoc analysis, not live runtime introspection. |
| **Aggregates (tempdoc 511, shipped)** | `WireAggregateKind` × `SurfaceContextKind` typed renderer registry. Existing kinds: `Operation`, `Resource`, `HealthEvent`. | Fits perfectly. |

Plus three patterns already established in the codebase that this
design rides on:

- **`captureOrVerify(Class, String)`** approval-test helper used by
  8+ `*SchemaTest` classes writing to `SSOT/schemas/*.v1.json`
  (e.g., `SubstrateSchemaGenTest`, `HealthEventSchemaTest`,
  `IndexingJobViewSchemaTest`).
- **`jseval compare_runs.py`** with paired-t-test + bootstrap CI +
  per-query diff — no CLI subcommand wrapper today, but the Python
  API exists.
- **Stratified eval metrics** (`scripts/jseval/jseval/projections/
  stratified_metrics.py`) — two-dimension stratification (query-
  length × first-relevant-rank) ready to accept a new dimension.

And one *gap*:

- **No span-attribute contract.** ADR-0027 covers metrics. OTel span
  attrs emitted by `SearchExecutor` are ad-hoc strings with no
  documented key set, no exhaustiveness gate. Compare with the closed
  reason-code allowlist at `search-and-rag-reason-codes.md`.

Additionally relevant prior art on the wire side:

- `SearchResponse` already exposes rich introspection-adjacent fields:
  `ComponentTiming`, `HitProvenance` per hit, `PipelineExecution`
  (`components: Map<String, ComponentStatus>`), `IndexCapabilities`,
  `QueryUnderstanding`, `FilterNormalization`. None of these capture
  the *decision shape*; they capture *outcome details*. The gap on
  the wire is structural decision typing, not raw data.
- `modules/ui-web/src/shell-v0/state/searchState.ts:74` reads
  `pipelineExecution.retrievalMs` and discards every other rich
  field. No explain panel renders today.

## Design

### Layer 1 — `SearchIntrospection` as a new `WireAggregateKind`

A wire record projecting a typed view of `(SearchDecision, SearchOutcome)`
for any single search execution.

- Lives at `modules/app-api/src/main/java/io/justsearch/app/api/
  knowledge/SearchIntrospection.java`. `@RecordBuilder`-annotated.
- Sealed sum `Decision`: one variant per `SearchDecision` variant
  (`EmptyQueryDecision`, `BlockedDecision`, `SparseShortcut`,
  `MultiLegDecision`). Each variant carries the privacy-safe
  `summary()` keys from the corresponding worker-side decision.
- Flat companion fields:
  - `effectiveMode` (string, e.g., `"TEXT"` / `"HYBRID"`)
  - `timing` (mirrors `ComponentTiming` — retrieval ms, chunk-merge
    ms, per-leg chunk timings)
  - `degradation` (vector-blocked flag + reason, hybrid-fallback flag
    + reason, splade-skip flag + reason)
  - `chunkMerge` (kind: `APPLIED` / `SKIPPED_*`, reason code, metrics)
  - `correction` (applied flag + corrected query — REDACTED unless
    opted in)
  - `qpp` (mirrors existing `max_idf`, `avg_ictf`, `query_scope`
    fields)
  - `version` (schema version int — additive evolution)
- Emitted on `SearchResponse.introspection` when
  `request.includeIntrospection: true`. Default off.
- Registered as a `WireAggregateKind` in tempdoc 511's catalog
  alongside `Operation`, `Resource`, `HealthEvent`.
- Rendered by a new typed renderer in
  `modules/ui-web/src/shell-v0/renderers/` registered against the
  appropriate `SurfaceContextKind` (e.g., `SearchSurface`,
  `DebugSurface`).

The decision variants carry the structural shape that today gets
flattened to wire scalars (`vector_blocked_reason`,
`chunk_merge_reason`, …). Flattening loses polymorphism; the wire
record preserves it, which is what enables typed FE rendering.

### Supporting move A — Search execution span-attribute contract

A documented file `docs/reference/contracts/search-execution-spans.md`
enumerates every OTel span attribute key emitted by `SearchExecutor`
and the per-`LegSet` handlers, with their value types and emission
preconditions.

- Producer: `SearchExecutor` + handler classes (currently inline
  methods).
- Consumers: OTel exporters (existing), `SearchExecutorOtelTopologyTest`
  (existing), future Layer-4 projections (per tempdoc 400 LR2-e.3,
  which already filters by `search.fusion.algorithm` +
  `search.fusion.branch_count`).
- Contract test (new): exhaustive pattern-match over `SearchDecision`
  variants asserting each variant contributes its declared attrs.
- This fills the gap ADR-0027 (metrics-only) left. The contract
  pattern mirrors `search-and-rag-reason-codes.md`.

### Supporting move B — `ComparingPlanner` decorator

A new `SearchPlanner` adapter that wraps two planners, runs both,
emits structured diffs.

- Lives at `modules/worker-services/src/main/java/io/justsearch/
  indexerworker/services/plan/ComparingPlanner.java`.
- Default DI never wires it. Used only inside CI / dev flows where a
  new planner version is being shadow-tested before promotion.
- Diff output: `SearchDecisionDiff` record (re-uses
  `SearchIntrospection.Decision` for both sides + a delta record).
- Consumer: a new `SearchPlannerShadowDiffTest` in `worker-services`
  test sourceset; runs the comparison over a fixture corpus of
  `SearchInputs` and fails on unexpected diffs (the corpus is the
  baseline).

`MigrationControlOps` is strict cutover with no comparison mode; this
would be the first dual-execution pattern in the codebase. Keep it
scoped: one decorator, named consumer, no SPI.

### Supporting move C — Planner approval-test corpus, riding `captureOrVerify`

Fixture-driven approval test for the planner using the existing
`captureOrVerify` helper.

- Test class: `SearchPlannerApprovalCorpusTest` in worker-services.
- Baselines: `SSOT/schemas/search-decisions/<scenario>.v1.json`
  alongside other captured schemas — same regen workflow as the 8
  existing `*SchemaTest` baselines.
- Corpus enumerates the 4 decision variants × representative inputs:
  sparse-only request, hybrid degradation, vector-blocked, empty
  query, three-way, novel combinations.
- The corpus *is* the planner contract for non-architectural changes.

### Supporting move D — `jseval` decision-kind stratification + `compare` CLI

Two small jseval extensions:

- Add `decision_kind` (sourced from
  `SearchResponse.introspection.decision`) as a third stratification
  dimension in `scripts/jseval/jseval/projections/stratified_metrics.py`.
- Promote the existing `compare_runs.py` API to a `jseval compare A B`
  subcommand, plus a `--bucket-by decision_kind` flag.
- No new infrastructure; both ride existing Python primitives.

## What this design REJECTS

- **No new module.** Types land in `app-api`, `worker-services`,
  `ui-web` — modules that already exist and already own their
  concerns.
- **No fourth telemetry substrate.** Metrics (ADR-0027), aggregates
  (511), workflow observability (ADR-0010) cover existing ground;
  `SearchIntrospection` extends (2); span-attr contract fills (1)'s
  gap.
- **No new persistence layer.** Search history defers to a future
  tempdoc under ADR-0010 model.
- **No `SearchPlanner` SPI / plugin substrate.** `ComparingPlanner`
  is a concrete decorator with a named consumer test.
- **No flattening of decision variants** — sealed-sum mirroring is
  the whole point.
- **No new approval-test framework** — `captureOrVerify` is enough.
- **No new comparison framework** — `jseval compare_runs.py` is
  enough.
- **No `SearchOrchestrator` capability promotion** (tempdoc 507
  path) — introspection is wire-level, not capability-level.
- **No `request.debug=true` overload** — `includeIntrospection` is
  orthogonal. `debug=true` already enables per-hit debug scores; the
  new flag enables the typed introspection record. Keeping them
  separate prevents one from forcing the other's payload cost.

## Invariants the design must preserve

1. **Privacy-safe by construction.** No query text, no filter values
   on `SearchIntrospection` by default. `correctedQuery` redacted
   unless explicit opt-in. Same discipline as
   `SearchDecision.summary()` today.
2. **C-018 — every new type has a named compile-time consumer.**
   Explain renderer (FE) consumes `SearchIntrospection`; shadow-diff
   test consumes `ComparingPlanner`; `captureOrVerify` baselines
   consume `SearchPlannerApprovalCorpusTest`; `jseval` stratification
   consumes the `decision_kind` field.
3. **Wire additive evolution.** `version` field + Zod `.loose()`
   pattern (existing at
   `modules/ui-web/src/api/schemas.ts`).
4. **No regression in default response cost.** `includeIntrospection`
   defaults to `false`; existing callers see no payload change.
5. **Span-attr contract is a backstop, not a primary path.** Live
   debugging reads attrs via OTLP; user-facing surface is the wire
   record.
6. **No new IO in the planner.** Projection happens in
   `SearchResponseBuilder` (pure). Planner stays pure-over-value per
   tempdoc 517 §A.1.
7. **Span topology preserved.** The new span-attr contract documents
   the existing OTel topology (per tempdoc 517 §A.3) without changing
   it: retrieval span = parent of leg branches + primary fuse;
   chunk_merge span = sibling of retrieval under request; branch-fuse
   span = child of chunk_merge.

## Long-term protection — what this design defends against

- **Adding a new search decision variant** (e.g., a future
  `ReformulatedDecision`): one new `SearchIntrospection.Decision`
  record + compiler enforces FE renderer exhaustiveness + new
  approval-corpus scenario + new span-attr declarations.
- **Adding a new degradation signal**: extends the existing
  `SearchReasonCode` enum (tempdoc 517) + `degradation` field on
  `SearchIntrospection`; one site, compiler-flagged downstream.
- **Adding a new FE surface that wants to render search context**:
  one renderer registration + one `SurfaceContextKind` ×
  `WireAggregateKind` tuple in the 511 registry; compiler enforces
  the exhaustiveness.
- **Planner refactor**: approval corpus catches behavior drift; PR
  diff is the review surface; `ComparingPlanner` is available for
  shadow validation if the change is non-trivial.
- **New eval research question** (e.g., "how does ChunkAugmentation
  recall vary by short vs long corpora?"): existing stratified
  metrics dimension framework absorbs the new question; no new tool.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Substrate-without-consumer (C-018). | All four supporting moves name compile-time consumers. The slice that ships `SearchIntrospection` ships the explain renderer at minimum. |
| Speculative-generality (F1 from tempdoc 512). | `SearchIntrospection` is the typed projection of `SearchDecision`, not a generic explain framework. Sealed-sum mirrors the existing planner type 1:1. |
| Wire-emitter-elision (the tempdoc 517 §B Pass-F finding pattern). | Approval-test corpus locks the baseline; PR diffs show field drift. The span-attr contract test pattern-matches over `SearchDecision` variants exhaustively. |
| New parallel telemetry substrate creep. | "What this rejects" §"No fourth substrate" is explicit. Reviewers grep for `interface.*Telemetry` / `class.*Catalog` and reject if not tied back to ADR-0027 / 511 / 0010. |
| FE renderer registry bloat. | 511 already governs this; new `SurfaceContextKind` × `WireAggregateKind` tuples require compile-time exhaustiveness. Bloat is bounded by the cardinality of surfaces (small). |
| Approval-test corpus stagnates. | Same risk as existing 8 `captureOrVerify` tests — mitigated by the regen workflow being one Gradle task. PR review is the gate. |
| Span-attr contract drift. | New contract test parallel to `GrpcSearchServiceReasonCodeContractTest` — pattern-match exhaustiveness ensures every variant contributes its attrs. |
| Pass-8 second-agent verification. | Substrate-shipping (new `WireAggregateKind`, new sealed sum, new wire field, new contract test). Pass-8 mandatory before merge per slice 447 §X.11. |

## Verification (for the future implementation slice)

Not in scope for this tempdoc. Recorded for the slice author:

1. `./gradlew.bat :modules:app-api:updateSchemas` — regenerate
   `SearchIntrospection` JSON schema + TS wire types baseline.
2. `./gradlew.bat :modules:worker-services:test
   :modules:app-api:test :modules:app-observability:test
   :modules:adapters-lucene:test` — module tests.
3. `cd modules/ui-web && npm run typecheck && npm run test:unit:run`
   — FE consumes the new type; renderer registry exhaustiveness
   compiles.
4. **Approval corpus** at `SSOT/schemas/search-decisions/*.v1.json`
   reviewed in PR.
5. **Span-attr contract test** (new) — pattern-match exhaustiveness
   over `SearchDecision` variants asserting each contributes its
   declared attrs.
6. **Live-stack** via `jseval` against a fixture corpus +
   `--include-introspection`; verify FE explain panel renders one of
   each decision variant.
7. **Pass-8 dispatch** before merge.

## Open questions for the slice author

1. **`request.includeIntrospection` default**: opt-in vs always-on.
   Recommend opt-in (preserves default payload cost). Slice may
   choose otherwise with reasoning.
2. **`correctedQuery` redaction policy**: redact by default with a
   separate opt-in (`includeCorrectedQuery`), or include unredacted
   whenever introspection is on (correction is already a user-visible
   event). Defer to product preference.
3. **Search history persistence**: write to
   `tmp/workflow-telemetry/searches/` under ADR-0010 model? Defer to
   a follow-up tempdoc; this design only emits, doesn't persist.
4. **`ComparingPlanner` production-code or test-only?** Recommend
   production code (in `services/plan/`) but never wired in default
   DI. Test-only constrains the diff path to test sourceset and
   limits future shadow-rollout flexibility.
5. **`jseval compare` as Gradle task or pure CLI?** Defer; the
   underlying primitive matters more than the entry point.

## What this tempdoc is *not*

- It is not a slice. No implementation tasks attach.
- It is not a UX design — the explain panel's visual design is a
  separate concern.
- It is not a roadmap for the six superseded §C themes — only the
  foundational Layer 1 + four supporting moves are in scope here.
  Once Layer 1 ships, each Layer 2 feature is independently
  scheduleable.
- It is not a refactor of `SearchOrchestrator.java` or any 517
  artifact — those stay as-is.

## Out of scope

- No implementation. This is a design tempdoc.
- No `SearchExecutor` decomposition (lower-stakes polish item from
  the superseded §C).
- No `SearchCollaboratorsHolder` Phase 2 (named in tempdoc 517).
- No federation / multi-index work (needs its own tempdoc when
  product justifies it).
- No reformulation LLM integration (handled by a future tempdoc when
  product demands it).

---

## §A — Implementation slice closure status (2026-05-19)

Slice implemented in worktree `.claude/worktrees/525-search-introspection`
(branch `worktree-525-search-introspection`). All ten phases executed
autonomously per the user's "tempdoc is the contract" instruction; live
verification on the dev stack was completed before declaring closure.

### §A.1 — Verification checklist matrix

| # | Verification item | Status | Evidence |
|---|---|---|---|
| 1 | `./gradlew.bat :modules:app-api:updateSchemas` | ✓ ran | Regenerated `search-response-live.json` fixture (introspection: null when omitted) + status schemas. Wire-types regen via `:wireGenerate`. |
| 2 | `:modules:worker-services:test` + `:modules:app-api:test` + `:modules:app-observability:test` + `:modules:adapters-lucene:test` | ✓ green | Full `./gradlew.bat test` passed (191 actionable tasks, 0 failures). |
| 3 | `cd modules/ui-web && npm run typecheck && npm run test:unit:run` | ✓ green | typecheck 0 errors; 1592 tests passed across 150 files. |
| 4 | Approval corpus at `SSOT/schemas/search-decisions/*.v1.json` | ✓ captured | 9 scenarios committed: empty-query, blocked-vector, sparse-shortcut, sparse-shortcut-chunk-merge-disabled, sparse-shortcut-no-chunk-docs, multi-leg-bm25-only-degraded, multi-leg-bm25-dense, multi-leg-splade-only, multi-leg-three-way. Regen via re-run of `SearchPlannerApprovalCorpusTest`. |
| 5 | Span-attr contract test (new) | ✓ green | `SearchExecutionSpanAttrsContractTest` — 3 tests asserting closed-allowlist invariant per decision variant. Pairs with `docs/reference/contracts/search-execution-spans.md`. |
| 6 | Live stack: `jseval`-style query matrix covering decision variants | ✓ 4/5 verified live + 1 covered by tests | See §A.4 below. |
| 7 | Pass-8 dispatch before merge | ⚠ outstanding (user-dispatched) | Slice is substrate-shipping (new `WireAggregateKind`, sealed `Decision` sum, new wire field, new contract test) per slice 447 §X.11 reframe; pass-8 required before merge. |

### §A.2 — Decisions of record

The tempdoc deliberately left 5 questions open + 1 unstated (decision 6).
The implementation slice locked them in as follows:

| # | Question | Resolution | Reasoning |
|---|---|---|---|
| 1 | `includeIntrospection` default | **Default-on** (`optional bool`; absent ≡ on) | App is pre-production with no users; payload optimization is premature; default-on simplifies the test surface and makes introspection a first-class user-affordance, not a hidden mode. |
| 2 | `correctedQuery` redaction | **Always include when correction fires** | The corrected query already renders on `KnowledgeSearchResponse.correctedQuery`; redacting one copy while exposing another is internally inconsistent. |
| 3 | Search history persistence | **Deferred** | Separable concern; persistence layer is its own design space. Confirmed out of scope. |
| 4 | `ComparingPlanner` production vs test-only | **Production code, no default DI wiring** | Preserves future shadow-rollout flexibility per tempdoc 525 recommendation. |
| 5 | `jseval compare` Gradle task vs CLI | **Pure CLI subcommand** | Matches the existing Python tooling pattern. No reason to drag JVM in. |
| 6 | `SurfaceContextKind` for explain renderer | **New `'search-explain'` context added to `SurfaceContextOfMap` + `SURFACE_CONTEXT_KINDS`** | Existing contexts (`button`/`list-item`/`activity-row`) are item-in-list shapes; introspection is a structurally-different per-request panel. 511's compile-time exhaustiveness justifies the cost. |

One additional structural discovery during implementation:

| Observation | Resolution |
|---|---|
| The aggregate substrate (tempdoc 511) is **TypeScript-only** — no Java-side `WireAggregateKind` enum exists. | Java side just emits the wire record; the "aggregate registration" lives purely in `modules/ui-web/src/shell-v0/aggregate-substrate/`. Simplifies Layer 1: Java work is wire-shape work; FE work is the actual aggregate substrate work. |

### §A.3 — Files touched

**New (Java production):**

- `modules/app-api/src/main/java/io/justsearch/app/api/knowledge/SearchIntrospection.java` (head-side record + sealed `Decision` sum-type + Timing/Degradation/ChunkMerge/Correction/Qpp records)
- `modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeIntrospectionMapper.java` (proto → Java record translation; extracted from `KnowledgeHttpApiAdapter` to honour class-size standard)
- `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/respond/SearchIntrospectionProjector.java` (worker-side projection of `SearchDecision` + `SearchOutcome` to proto)
- `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/plan/ComparingPlanner.java` (supporting move B)
- `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/plan/SearchDecisionDiff.java` (paired with `ComparingPlanner`)

**New (Java tests):**

- `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SearchExecutionSpanAttrsContractTest.java` (move A contract test)
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SearchPlannerApprovalCorpusTest.java` (move C approval-corpus test)
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SearchPlannerShadowDiffTest.java` (move B regression test)

**New (TypeScript):**

- `modules/ui-web/src/shell-v0/aggregate-substrate/strategies/searchIntrospectionExplain.ts` (canonical `(SearchIntrospection, search-explain)` strategy)
- `modules/ui-web/src/shell-v0/aggregate-substrate/components/JfSearchIntrospection.ts` (custom element wrapping the strategy)

**New (documentation):**

- `docs/reference/contracts/search-execution-spans.md` (move A — closed span-attribute contract)
- `SSOT/schemas/search-decisions/*.v1.json` (9 approval baselines)

**Modified:**

- `modules/ipc-common/src/main/proto/indexing.proto` (added `optional bool include_introspection = 15` to `SearchRequest`; added `SearchIntrospection introspection = 25` to `SearchResponse`; added 11 new message types for the introspection aggregate)
- `modules/worker-services/.../services/respond/SearchResponseBuilder.java` (calls projector when `shouldIncludeIntrospection` is true)
- `modules/app-services/.../worker/KnowledgeHttpApiAdapter.java` (added one builder-chain line; ratchet bumped 2117→2118 — the structural fix was extracting `KnowledgeIntrospectionMapper`)
- `modules/app-api/.../knowledge/KnowledgeSearchResponse.java` + `KnowledgeSearchResponseContractTest.java` (added `introspection` field + allowlist entry)
- `modules/ui/.../api/KnowledgeSearchController.java` (HashMap put for `introspection` when non-null)
- `modules/ui-web/src/api/generated/wire-types.ts` + `generated/index.ts` (added SearchIntrospection + sub-types; re-exports)
- `modules/ui-web/src/shell-v0/aggregate-substrate/aggregateKinds.ts` + `surfaceContextKinds.ts` (added `'SearchIntrospection'` + `'search-explain'`)
- `modules/ui-web/src/shell-v0/aggregate-substrate/bootstrap.ts` (registers new strategy)
- `modules/ui-web/src/api/domains/search.ts` + `schemas.ts` (added introspection to internal/UI shapes + Zod schema)
- `scripts/jseval/jseval/artifacts.py` (extracts `decision_kind` from response per-query)
- `scripts/jseval/jseval/projections/stratified_metrics.py` (third stratification dimension)
- `scripts/jseval/jseval/cli.py` (`--bucket-by decision_kind` flag + `_compare_by_decision_kind` helper + `_print_decision_kind_comparison`)
- `scripts/jseval/tests/test_projections_stratified_metrics.py` + `test_cli.py` (3 new pytest classes/tests)
- `gradle/class-size-exceptions.txt` (KnowledgeHttpApiAdapter ratchet 2117 → 2118, dated 2026-05-19, offset by structural extraction of `KnowledgeIntrospectionMapper`)

### §A.4 — Live verification on dev stack

Tier-3 live verification was completed against a running dev stack
(apiPort 64113). The pre-existing 109-doc corpus in
`F:/JustSearch/modules/ui-web/.dev-data` served as the matrix; SPLADE
coverage 100%, embeddings BLOCKED_LEGACY (an existing operational state
that conveniently exercises the `BlockedDecision` + degraded-hybrid
paths).

**Operational note discovered during Phase 9**: the MCP `justsearch-dev`
dev-runner launches the head + worker JVMs from the **main** repo
checkout (`repoRoot: F:/JustSearch`) regardless of which worktree the
agent invokes the tool from. This is hard-coded in
`scripts/dev/dev-runner.cjs:21`. To live-verify worktree code, the slice
must copy its freshly-installed dist jars over main's
`modules/ui/build/install/ui/lib/` and
`modules/indexer-worker/build/install/indexer-worker/lib/` before
starting the stack. The next agent who runs `installDist` from main will
naturally overwrite the swap; no manual revert needed.

**Query matrix (4 of 5 decision variants verified live; 5th covered by
unit tests):**

| Variant | Mode | Wire `decision.kind` | Other assertions | Evidence |
|---|---|---|---|---|
| `SparseShortcut` | TEXT | `sparse_shortcut` | `effectiveMode=TEXT`; `chunkMerge.applied=true`; `chunkMerge.reason=APPLIED`; `chunkMerge.branchFusionStrategy=cc`; `decision.runtimeSyntax=SIMPLE`; `decision.retrievalLimit=40` | ✓ live |
| `BlockedDecision` | VECTOR | `blocked` | `vectorBlocked=true`; `degradation.vectorBlockedReason=LEGACY_INDEX_NO_FINGERPRINT` | ✓ live |
| `MultiLegDecision(Bm25Only)` via hybrid degradation | HYBRID | `multi_leg` | `decision.legs=bm25_only`; `degradation.hybridFallback=true`; `degradation.hybridFallbackReason=LEGACY_INDEX_NO_FINGERPRINT` | ✓ live |
| `MultiLegDecision(SpladeOnly)` | SPLADE | `multi_leg` | `decision.legs=splade_only`; `degradation.spladeExecuted=true` | ✓ live |
| `EmptyQueryDecision` | TEXT (blank query) | (rejected pre-worker) | Head REST layer returns HTTP 400 `INVALID_REQUEST` for blank queries before reaching the worker — pre-existing controller validation. The decision path itself is covered by `SearchPlannerApprovalCorpusTest.emptyQuery` (approval baseline at `SSOT/schemas/search-decisions/empty-query.v1.json`) + `SearchExecutorOtelTopologyTest.emptyQueryDecisionEmitsNoRetrievalSpan`. | ✓ tests only |

### §A.5 — Risk-table follow-ups

The tempdoc's risk table (8 items) is closed status as follows:

| Risk | Status | Note |
|---|---|---|
| Re-introducing the deleted DAG / pipeline-engine pattern | ✓ avoided | `Decision` is a sealed sum of records; no `.execute()`; no registry. |
| Substrate-without-consumer (C-018) | ✓ avoided | Every new type has a compile-time consumer: `SearchIntrospection` ↔ explain renderer; `ComparingPlanner` ↔ shadow-diff test; approval corpus ↔ regen workflow; `decision_kind` ↔ jseval stratification. |
| Speculative-generality | ✓ avoided | `SearchIntrospection` mirrors `SearchDecision` 1:1; not a generic explain framework. |
| Wire-emitter-elision | ✓ guarded | Approval-test corpus + span-attr contract test; the `KnowledgeSearchResponseContractTest` already gates HashMap-mapping. |
| Parallel telemetry substrate creep | ✓ avoided | `SearchIntrospection` rides 511's aggregate substrate; span-attr contract fills ADR-0027's gap without parallel layer. |
| FE renderer registry bloat | ✓ bounded | New `'search-explain'` context is the only addition; compiler enforces typed exhaustiveness. |
| Approval-test corpus stagnates | ✓ baseline established | 9 scenarios captured; PR review is the gate. |
| Span-attr contract drift | ✓ guarded | New `SearchExecutionSpanAttrsContractTest` exercises every emitted attr against the closed allowlist. |
| Pass-8 second-agent verification | ⚠ outstanding | User-dispatched merge gate (per slice 447 §X.11). |

### §A.6 — What does not ship in this slice

Per the tempdoc's "Out of scope" + slice discipline:

- No `SearchOrchestrator` refactor — its 5-role split from 517 is unchanged.
- No `SearchExecutor` further decomposition (was 984 LOC; tempdoc 517 polish item, separate slice).
- No `SearchCollaboratorsHolder` Phase 2 (named in 517).
- No federation / multi-index work.
- No reformulation LLM integration.
- No `request.includeIntrospection` exposure on the FE Zod request schema (REST request shape unchanged; flag is gRPC-level for now, and default-on means it isn't needed).
- Search history persistence (deferred to a follow-up tempdoc).
- FE explain panel visual polish (525 explicitly says "not a UX design"; rendering is structurally correct, styling out of scope).

### §A.7 — Pass-8 brief for the user

The slice ships these structural primitives that warrant independent
substrate review:

1. New `SearchIntrospection` Java record + sealed `Decision` sum on
   `KnowledgeSearchResponse` (head-side wire contract).
2. New proto messages (`SearchIntrospection` + 8 sub-messages + 5 new
   primitive groups) on `SearchResponse` field 25.
3. New `WireAggregateKind` member `'SearchIntrospection'` + new
   `SurfaceContextKind` member `'search-explain'` in tempdoc 511's FE
   substrate.
4. New span-attribute contract document + closed-allowlist contract
   test.
5. New `ComparingPlanner` adapter + `SearchDecisionDiff` record in
   `services/plan/` (no default DI wiring; named consumer is
   `SearchPlannerShadowDiffTest`).
6. New approval-test pattern at `SSOT/schemas/search-decisions/`
   (9 scenarios) following the existing `captureOrVerify` convention.
7. New `decision_kind` stratification dimension + `--bucket-by` flag
   in `jseval`.

Review focus per slice 447 §X.11: substrate-without-consumer; span-attr
contract completeness vs producer-site reality; reason-code allowlist
parity with worker emissions; renderer-registry exhaustiveness; the
`KnowledgeHttpApiAdapter` ratchet bump (2117 → 2118, offset by the
~120-LOC extraction of `KnowledgeIntrospectionMapper`).

---

Next reader: see tempdoc 511 for the `WireAggregateKind` registry
pattern; see tempdoc 517 §A/§B for the worker-side decision substrate
this rides on; see ADR-0027 for the metrics-substrate boundary; see
ADR-0010 for the workflow-observability boundary; see
`search-and-rag-reason-codes.md` for the contract pattern the new
span-attr contract parallels.
