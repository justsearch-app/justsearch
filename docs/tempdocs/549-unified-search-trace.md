---
title: "549 — Unified search trace: one canonical end-to-end ranking-decision record (G33 + G111)"
type: tempdocs
status: done
related-followups:
  - tempdoc 551 (wire-contract-searchtrace-gap — Part 1 shipped: SearchTrace in the gated wire contract + record↔proto conformance test)
  - tempdoc 552 (searchtrace-fe-barrel-migration — Part 2, opt-in)
created: 2026-05-25
category: substrate-design / search-quality / user-facing
related:
  - catalog: G33 "why was this slow / what happened" (per-query)
  - catalog: G111 "why this result" (per-hit)
  - tempdoc 517 (search-execution-design — decisions are values; SearchPlanner/SearchExecutor)
  - tempdoc 525 (search-introspection-aggregate — SearchIntrospection; privacy + always-on + approval corpus)
  - tempdoc 511 (aggregate-surfacing-substrate — WireAggregateKind × SurfaceContextKind renderer registry)
  - tempdoc 526 (selection-substrate — SelectionPayload + SelectionContextInjector LLM-inject path)
  - tempdoc 530 (discipline-gate kernel) + 531 (consumer-drift gate)
  - tempdoc 537 (post-substrate-eval-campaign — decision_kind stratification)
  - tempdoc 486 (catalog home for G33/G111; this slice takes both over)
---

# 549 — Unified search trace

> **Historical projections (2026-11-09):** `bootstrap_ci`, `rank_diff` and `stratified_metrics` generators/tests are retired; references below describe past implementations and captured evidence. **What this document is.** A theorization of the *correct long-term
> structure* for search explainability — not an implementation plan. It
> describes what the design should look like and the failure mode it must
> prevent. It deliberately omits implementation specifics (no code, no
> phasing, no file-by-file steps); those belong to a later slice once the
> target structure is agreed.

## Thesis

Search explainability should be served by **one canonical, end-to-end search
trace**: a single typed artifact that the pipeline *writes as it decides*,
carries *whole* across the wire, and that *every* consumer reads from the same
place. G33 ("why was this slow / what happened") and G111 ("why this result")
are the first user-visible consumers of that artifact — not the reason to
build it. The reason to build it is that the trace today is fragmented, and
that fragmentation will keep getting worse with every new pipeline stage until
the structure is corrected.

## The problem this corrects

The data needed to answer G33/G111 is already computed on every query. It is
not missing — it is **fragmented across three overlapping representations with
no single source of truth**, assembled differently in two processes, gated
inconsistently, and dropped at the UI boundary. Concretely:

- **The query-level model is good at the source but is not carried through.**
  The worker produces a clean value model — a `SearchDecision` (sealed sum,
  produced once before any retrieval IO) plus a `SearchOutcome` — exactly the
  "decisions are values, not control flow" architecture tempdoc 517
  established. That model never reaches the consumer intact.

- **It triplicates downstream.** On the wire and in the head response the same
  degradation/timing facts appear as (1) ~18 flat boolean + reason fields
  (`vectorBlocked`/`vectorBlockedReason`, `hybridFallback`/…,
  `spladeExecuted`/`spladeSkipReason`, `chunkMergeApplied`/…,
  `lambdaMartApplied`, `crossEncoderApplied`/…), (2) a structured
  `SearchIntrospection` record (tempdoc 525) with `Degradation` / `ChunkMerge`
  / `Correction` / `Timing` companions, and (3) a `PipelineExecution` /
  `ComponentTiming` record whose timing overlaps `Introspection.Timing`.
  Degradation lives two-to-three times; timing twice.

- **The per-hit level has no value model at all.** Per-hit "why it ranked
  here" is a stringly-typed `debugScores` map (`"sparse_rank"`, `"rrf"`,
  `"cc"`, …) that the head *reconstructs* post-hoc into `HitProvenance`. This
  is the per-hit instance of the very anti-pattern 517 rejected for the query
  level: reasoning recovered from scattered primitives rather than recorded as
  a decision.

- **Two processes, two non-models.** Stages that run in the head — LambdaMART,
  cross-encoder, query expansion, query-understanding, freshness decay — are
  not modeled as decisions anywhere. They are ad-hoc head-side booleans
  reassembled by pattern-matching into `PipelineExecution.components`, and they
  are **silently absent from `SearchIntrospection`** (which is worker-only).
  The "trace" therefore has a hole exactly where half the ranking happens.

- **Inconsistent gating.** Query introspection is always-on (525's decision);
  per-hit `debugScores`/`HitProvenance` exist only when the request sets
  `debug=true`. So G33 has data in production and G111 does not.

- **No consumer reads it.** The frontend already has the rendering pieces — a
  `JfSearchIntrospection` aggregate component and a `searchIntrospectionExplain`
  strategy built on tempdoc 511's surfacing substrate — but they are **never
  mounted**. Per-hit `provenance` is typed on the FE `SearchHit` and never
  read. The eval harness (`jseval`) is the only real consumer, and it must
  consult five different locations to reconstruct one picture.

### The failure mode to prevent (not just fix)

Every time a ranking-affecting stage is added, the path of least resistance is
to bolt on another flat boolean + reason string, maybe a `debugScores` key,
maybe a `components` entry — in whichever of the two processes the stage lives.
Nothing forces the new stage into a single trace, and nothing forces a renderer
to exist for it. That is *why* LambdaMART and the cross-encoder are invisible in
the introspection surface today. A point-fix that adds a sixth representation
("a `SearchTrace` selection payload") on top of the existing three would
entrench the pattern. The correct design removes the incentive: there is one
place a stage's trace can go, and the build fails if it isn't there.

## The correct structure

A single **SearchTrace**: an ordered, stage-keyed record of what the pipeline
did, produced at decision time, spanning both processes, carried once, with two
granularities (query and per-hit) sharing one stage vocabulary.

1. **One trace, projected once from authoritative typed values — never
   reconstructed from flattened primitives.** Generalize 517's principle the
   whole way down. The distinction that matters (confirmed by the de-risking
   pass, U1/U2) is *not* "write as you execute" vs "project at the end" — the
   codebase's projection-at-the-end pattern (`SearchIntrospectionProjector`,
   `buildPipelineExecution`) is already correct and should stay. The distinction
   is **what the projection reads from**: a typed decision + outcome + per-hit
   value (good) versus a lossy, stringly-typed `debugScores` map reconstructed
   post-hoc (`assembleProvenance` — bad). The fix is to make the authoritative
   values rich enough to project from directly — extend the worker's typed
   `SearchOutcome`, and carry a typed per-hit provenance on the worker's
   `SearchHit` instead of a flat score map — so `assembleProvenance` has nothing
   left to reconstruct. Threading a mutable trace accumulator through execution
   is explicitly *not* the design (U1 found it would churn ~6 worker signatures
   and 15 head locals for no semantic gain).

2. **Stage-keyed, not field-keyed.** The unit of the trace is a typed **Stage**
   node — query-understanding, expansion, correction, sparse / dense / SPLADE
   retrieval, fusion, chunk-merge, branch-fusion, LambdaMART, cross-encoder,
   freshness, … — each carrying its status (executed / skipped / failed), a
   reason, timing, and a stage-specific structured detail. The flat fields, the
   `Introspection` companions, `PipelineExecution`, and `debugScores` collapse
   into this one stage-indexed structure. *Adding a stage is adding one node
   type*, in one place, rather than threading a boolean and a string through
   four records and two processes.

3. **One schema, two contributors.** The worker projects its
   retrieval/fusion/chunk-merge stage nodes from its decision + outcome; the head
   projects its own stage nodes (rerank, expansion, query-understanding,
   freshness) from its post-processing state and composes them onto the
   worker-originated trace into one combined artifact. Each process owns the
   projection of its own stages from typed state — neither reconstructs the
   other's, and the head stops modeling its stages as loose booleans that a
   downstream projector has to pattern-match back into meaning.

4. **Query and per-hit are the same model at two granularities.** A document's
   "why this result" (G111) is precisely its *slice* of the stage nodes: which
   retrieval legs found it (rank + raw score), how fusion combined those legs,
   whether and how the reranker moved it, which fields/spans matched. The
   query-level trace (G33) is the whole-query view of the same nodes. This
   introduces the missing **per-hit ranking-decision value** as the direct
   analog of the query-level `SearchDecision` — recorded at ranking time, not
   recovered from string keys.

5. **Single projection; legacy representations are derived or retired.** The
   wire carries the trace once. The current flat fields, `PipelineExecution`,
   and the `Introspection` companions either become thin *views* computed from
   the trace (for any caller that still wants the convenience accessor) or are
   removed outright. There is exactly one source of truth; everything else is a
   projection of it.

6. **Always-on and privacy-safe, with a detail tier inside the structure.**
   Resolve the gating split rather than inherit it. The stage structure —
   including *which* stages touched each hit and their ranks — is always-on and
   privacy-safe by construction (carrying no query text or filter values, per
   525's posture), so G111 works on a normal production search. The heavyweight
   raw numeric scores become an optional *detail tier within the same trace*,
   requestable when wanted, rather than a separate `debug=true` universe with a
   different (reconstructed) shape.

7. **Consumer-uniform.** The one artifact serves all consumers without each
   reassembling it:
   - **FE explain surface** — rendered through tempdoc 511's aggregate renderer
     registry, finally mounting a *stage-complete* successor to the unused
     `JfSearchIntrospection` (complete because every stage, including the
     head-side ones, is now a node).
   - **LLM narration** — injected via a tempdoc 526 `SelectionPayload`
     "search-trace" kind whose `SelectionContextInjector` case formats the stage
     trace as natural-language context, so "explain this result in words" routes
     through the same selection→compose→`core.rag-ask` path that
     `explain-health-condition` already uses.
   - **Eval** — `jseval` stratifies from one stage-keyed structure instead of
     five locations, extending 525's `decision_kind` stratification (tempdoc
     537) to full per-stage features.

8. **Synthesis is a consumer, not the substrate.** Raw-versus-explained is not a
   property of the trace; it is two consumers of it. A deterministic panel
   renders the structured trace with no model dependency; an LLM narration
   reads the same trace through the injector path. G33/G111 ship the
   deterministic panel always and degrade gracefully to it when the Brain is
   offline; the narration is the richer-when-available layer.

9. **Completeness is governance-enforced — the long-term prevention.** This is
   the load-bearing piece that stops the fragmentation from recurring. Build on
   525's "a new decision variant compiler-forces FE renderer exhaustiveness + an
   approval-corpus scenario" and 530/531's consumer-drift gate: **a new pipeline
   stage must register a trace stage node and an aggregate renderer, or the
   build fails.** Stage-completeness becomes a checked invariant rather than a
   convention, which is exactly what would have prevented LambdaMART and the
   cross-encoder from being invisible today.

## What to reuse vs. collapse

**Reuse / extend (these are already the right shape):**

- Tempdoc 517's decision-as-value worker model — generalize it into the trace
  accumulator and add the per-hit analog.
- Tempdoc 511's aggregate-surfacing renderer registry — the FE explain surface
  rides it; the work is making the rendered trace stage-complete and actually
  mounting it.
- Tempdoc 526's selection substrate — the LLM-narration consumer rides it
  unchanged in shape (one new payload kind + injector case + producer).
- Tempdoc 525's discipline — privacy-by-construction, always-on,
  schema-versioned, approval-corpus-guarded — generalized from "decision" to
  "full stage trace."
- Tempdoc 530/531 governance — add stage-completeness and consumer rows.

**Collapse (these are the fragmentation):**

- The ~18 flat boolean + reason fields, `PipelineExecution`/`ComponentTiming`,
  and the `SearchIntrospection` companions → one stage-keyed trace, with the
  survivors becoming derived views.
- The stringly-typed `debugScores` map and its post-hoc `assembleProvenance` /
  `buildPipelineExecution` reconstruction → recorded per-hit stage
  contributions, no reconstruction.

## Scope

**In scope (the design target):**

- A single canonical SearchTrace artifact, query-level and per-hit, spanning
  both processes, produced at decision time.
- Retirement or derivation of the overlapping legacy representations so there
  is one source of truth on the wire.
- The three uniform consumers: FE explain surface (511), LLM narration (526),
  eval stratification (537) — with G33/G111 as the first user-visible ones.
- A governance invariant that a new pipeline stage cannot ship without its
  trace node + renderer.

**Out of scope:**

- Inventing *new* ranking signals the pipeline does not already compute. This is
  about structuring and surfacing what exists, not adding instrumentation. A
  genuinely missing signal discovered during design is a separate finding.
- Run-to-run comparison / replay (catalog G62) — adjacent; the unified trace is
  a prerequisite for doing it well, but it is its own consumer.
- The KV-cache / llama-server flags discussion and other concerns unrelated to
  the trace artifact.

## Open design questions (resolve before implementation, still "what" not "how")

> **Several of these were resolved by the 2026-05-25 de-risking pass — see the
> Confidence / risk appendix at the end.** Stage-boundary, trace-ownership, and
> migration are now answered; detail-tier and per-hit privacy remain open.

- **Boundary of "stage."** What is the canonical stage vocabulary, and is it a
  fixed enumeration or an open registry that plugins/new components extend? This
  determines whether stage-completeness is a closed compiler check or an
  open-registry governance check.
- **Trace ownership across the process boundary.** The worker authors the
  retrieval stages and the head authors the rerank/expansion stages. Which
  process owns the *schema* and the *assembly contract*, and how is the
  append-only contribution expressed so neither process reconstructs the other's
  nodes?
- **Detail-tier negotiation.** Always-on structural trace + optional numeric
  detail tier — is the detail tier requested per-query, derived from a global
  setting, or fetched on demand for a single hit being explained? Affects
  response weight and the privacy surface.
- **Migration of existing consumers.** `jseval` and any current readers of the
  flat fields / `PipelineExecution` must move to the trace. Are the legacy
  fields kept as derived views for a deprecation window, or cut over?
- **Privacy of per-hit detail.** Query-level introspection is privacy-safe by
  construction; per-hit trace is tied to a docId and carries scores. Confirm
  what the per-hit trace and its LLM-injected form are permitted to carry.

## Why this is the right long-term call

The minimal alternative — add a `SearchTrace` selection payload over today's
backend — would make G33/G111 *appear* to ship while leaving the trace
fragmented, the head-side stages invisible, the per-hit data debug-gated, and
the next pipeline stage free to fragment it further. The unified trace costs
more now (it is a cross-process refactor that collapses three representations
into one and introduces a per-hit value model), but it is the structure that
makes explainability *correct and complete* rather than partial, and the
governance invariant is what keeps it that way as the pipeline grows. G33 and
G111 are the proof that the structure is whole: when a real user can ask "why
this result?" and see *every* stage that touched it — including the ones that
run in the head — the trace is complete by construction.

## Dependencies

- Tempdoc 517 (decision-as-value worker model) — the foundation to generalize.
- Tempdoc 511 (aggregate renderer registry) — the FE explain surface substrate.
- Tempdoc 526 (selection substrate) — the LLM-narration consumer path.
- Tempdoc 525 (introspection discipline) — privacy / always-on / approval corpus
  to inherit and extend.
- Tempdoc 530/531 (discipline-gate + consumer-drift) — the completeness
  invariant.

No new external substrate is required; this consolidates and completes substrate
that already exists.

## ⚠ Implementation outcome vs. thesis (2026-05-26) — read before trusting "done"

The slices below shipped the **user-visible consumers** (G33/G111) and made real,
narrow structural improvements — but the **core architectural thesis of this
document was NOT achieved.** A theoretical re-evaluation against the nine
principles found:

- **Achieved:** per-hit provenance is now projected from typed values, not
  reconstructed from `debugScores` (principle 1, per-hit); `assembleProvenance`
  retired; per-hit provenance is always-on (principle 6, structural tier);
  deterministic panel + LLM narration both exist and degrade gracefully
  (principle 8); the 15 flat fields were removed (principle 5, partial).
- **Not achieved — the thesis itself:** there is **no single `SearchTrace`
  artifact.** The fragmentation was *reduced* (one of ~four representations
  removed) but **not eliminated**: `SearchIntrospection` (field-keyed
  `Degradation`/`ChunkMerge`/`Correction` companions **plus** a separate
  `headStages` list), `PipelineExecution`/`ComponentTiming`, and `debugScores`
  all still coexist on the wire alongside a *separate* per-hit provenance model.
  - **Principle 2 (stage-keyed, one vocabulary): not met.** Only the 5 head
    stages are `Stage` nodes; worker retrieval/fusion/chunk-merge remain
    field-keyed companions; `PipelineExecution` and `debugScores` were never
    collapsed.
  - **Principle 4 (query + per-hit are one model at two granularities): not
    met.** Per-hit (`HitProvenanceSignals`, leg-keyed) and query-level
    (`SearchIntrospection`, companion-keyed + head-stage list) are two different
    shapes; a hit is not "its slice of the query stage nodes."
  - **Principle 5 (single source of truth): partial.** Flat fields retired, but
    `PipelineExecution` and `debugScores` were neither derived from a trace nor
    removed (the Slice-6 plan intended `PipelineExecution` to become a derived
    view — that did not happen).
  - **Principle 9 (governance forces every stage in): partial.** Compile
    exhaustiveness covers only the closed `HeadStage` enum; worker stages are
    not a sealed stage vocabulary, so a new *worker* stage is not build-forced
    into the trace, and renderer-completeness is one panel-level slot, not
    per-stage.

This is, candidly, a more sophisticated instance of the failure mode this
document explicitly warns against ("a point-fix that... would make G33/G111
*appear* to ship while leaving the trace fragmented"). The storefront (the
consumers) was delivered; the rebuilt foundation (the single stage-keyed trace)
was not. **The document's status should be read as: user-facing goals met;
core unifying-artifact goal open.** The "Remaining work to achieve the thesis"
section below enumerates what is left.

## Remaining work to achieve the thesis (open)

To actually realize the unified trace, the following are still unbuilt:

1. **Define the single `SearchTrace` type** — an ordered, stage-keyed record
   (one `Stage` node vocabulary) covering *all* stages: query-understanding,
   expansion, correction, sparse/dense/SPLADE retrieval, fusion, chunk-merge,
   branch-fusion, LambdaMART, cross-encoder, freshness.
2. **Model worker stages as `Stage` nodes** (not `Degradation`/`ChunkMerge`/
   `Correction` companions), projected from the worker's typed
   `SearchDecision`/`SearchOutcome`.
3. **Collapse `PipelineExecution` + `debugScores`** into the trace (as the
   detail tier / derived views), so exactly one source of truth remains on the
   wire.
4. **Unify per-hit as a slice of the same stage vocabulary** — the per-hit
   provenance becomes the per-doc projection of the same `Stage` nodes, not a
   parallel leg-keyed model.
5. **Extend governance to all stages** — a sealed/closed stage vocabulary so a
   new *worker* stage compile-forces a trace node, plus per-stage renderer
   completeness.
6. **Detail-tier negotiation** (principle 6) — numeric detail as an optional
   tier *inside* the trace, replacing the `debug=true` split, rather than the
   current always-on-everything approach.

### De-risk gate result: `debugScores` ↔ LambdaMART (2026-05-26)

The mandatory pre-build probe (does collapsing `debugScores` break learning-to-rank?)
returns: **collapse is reachable — principle 5 is NOT blocked — but it raises the bar
on the detail tier, which is therefore a hard prerequisite for item 3, not an optional
follow-up (item 6).** Findings:

- **LTR inference** (`LambdaMartReranker` reads `sparse`/`vector`) maps 1:1 to
  `bm25.rawScore` / `dense.rawScore` already in `HitProvenanceSignals` — safe to source
  from the typed trace.
- **LTR training-data collection** is the constraint: `GplJobCoordinator.buildFeaturePayload`
  (`modules/app-services/.../gpl/GplJobCoordinator.java:553-626`) reads **~22** `debugScores`
  keys and **persists all of them** to the NDJSON triple store as forward-looking training
  signal (V1's schema uses only 2). Several persisted keys are **not** carried by today's
  `HitProvenanceSignals`: `branch_merge_cc_weight_*`, `*_effective_weight_*`, `*_modifier_*`,
  `parent_token_count` variants, `*_rrf`, `*_boost`, `cc_alpha`, norm values.
- **Implication for the design:** the trace's per-hit **detail tier must be a lossless
  superset** of the GPL-collected `debugScores` keys, and it must be carried on the **gRPC
  wire** (GplJobCoordinator runs head-side and reads `SearchResult.debugScoresMap`). Migration
  consumers beyond the explainability path: `GplJobCoordinator`, the GPL analysis reports
  (`GplStage3aAnalysisReport`, `GplStage3bBranchFusionReport`), and white-box `debugScores`
  tests. **Do not collapse `debugScores` until the detail tier carries every GPL-collected
  signal**, or the LTR training corpus silently loses forward-looking features.

## CLOSURE (2026-05-27)

**The full thesis is implemented, merged, verified, and closed.** Every phase below (A, B, C,
D0–D2, E1–E5, F) is DONE; the four legacy representations (`debug_scores`, `HitProvenance`,
`PipelineExecution`/`ComponentTiming`, `SearchIntrospection`) plus the ~16 flat `SearchResponse`
fields are physically gone from proto/app-api/FE/jseval/GPL, and the single stage-keyed
`SearchTrace` is the sole search-explainability artifact on the wire. Merged to `main` in
`a105cc97f` (wire VERSION 0.2.0→1.0.0, `remove` changeset folded in); a live-caught Phase-D1 NPE
(hybrid + cross-encoder disabled) was fixed in `46cbe682b` and pinned against regression in
`d5ecc05e6` (the `ceScoreFor` guard + 3 tests); the post-merge class-size pins were realigned in
`2e12b17a0`. The `stage-completeness` gate guards principle 9 against regression. Independent
reviewer confirmed the thesis on the live wire (single artifact / legacy gone / consumers
trace-only / gate guards).

**Wire-contract follow-up — RESOLVED into tempdocs 551 + 552 (was: "the sole remaining work").**
The trace had been added to `ipc-common/indexing.proto` (Head↔Worker gRPC) + the frozen FE
`wire-types.ts` barrel, but NOT to `contracts/wire/knowledge.proto` (the buf-gated, VERSION-tracked
contract) — so the `wire` gate didn't guard the trace's shape. **Tempdoc 551 Part 1 (SHIPPED,
`386c4f0ef`)** promoted `SearchTrace`/`TraceStage`/`HitStage` into `contracts/wire/knowledge.proto`
(with a record↔proto conformance test that prevents recurrence) — the governance gap is closed.
**Tempdoc 552** owns the opt-in Part 2 (migrate the FE consumers off the frozen barrel onto the
generated `knowledge_pb`). See those tempdocs for the full investigation (incl. the capability-vs-
mandate framing that resolved the "is the protobuf-es migration alive?" question).

**Enhancement backlog — see §"Future directions" (below).** A 2026-05-27 autonomous research pass
documented what the now-shipped trace enables (trace↔OTel-span convergence, waterfall/per-hit/diff
UX, a relevance regression gate, an LTR feature-logging loop). Research only — nothing committed.

## Future directions — what the trace enables (research, 2026-05-27)

> **→ Superseded in framing by tempdoc 553** (`553-canonical-search-execution-record.md`), the
> *correct long-term structure*: one canonical search-execution record with all surfaces (explain,
> observability spans, eval, LTR, narration, wire) as governed projections. Under 553 the trace is
> just the human-explain projection, and every idea below **falls out as a projection or consumer**
> of the canonical record rather than a bespoke feature. The concrete catalog + source links below
> remain useful as the enumeration; read 553 for the structure that unifies them.

> Autonomous research pass (no implementation). Grounded in the current code (the FE explain
> panel renders a flat `stage: status (detail) · Nms` chip list — `searchTraceExplain.ts`; the
> per-hit `HitStage` slice carries `rank`/`score`/`detail`; the trace is the sole search-explain
> artifact, consumed by FE explain, jseval, GPL, and `core.summarize`) and a field scan. The trace
> is structurally **a per-query span tree + a per-hit feature vector** — which is exactly what
> distributed-tracing and learning-to-rank tooling consume. App is pre-production / no users, so
> these are ranked by leverage × thesis-fit, not urgency. Nothing here is committed.

### A. Converge the trace with the OTel span tree (the natural "549 v2" — highest thesis-fit)
The search path **already emits a parallel OTel span tree** for the same execution: `search`,
`search/retrieval`, `search/fuse`, `search/chunk_merge`, `search/branch` (worker),
`search/lambdamart`, `search/cross_encoder`, `search/rerank` (head), exported via
`modules/telemetry/.../NdjsonSpanExporter.java`. So two records describe the same pipeline — the
`SearchTrace` (explainability) and the spans (telemetry) — built and maintained separately. This is
the *same fragmentation 549 set out to kill*, one layer over: 549 unified the explainability
representations but left telemetry as a parallel track.
- **A1 — derive one from the other** (single source → both surfaces). The stage `ms` already
  duplicates span durations; the `StageId` vocabulary already parallels the `search/*` span names.
- **A2 — emit the trace as OpenInference-conventioned OTel spans.** OpenInference (Arize, an
  OTel-aligned semantic-convention spec) defines `retriever` spans (documents carry `id` + `score`)
  and `reranker` spans (`input_documents`/`output_documents`/`query`/`model_name`/`top_k`). The
  JustSearch stages map ~1:1 — sparse/dense/splade-retrieval → retriever spans, cross-encoder →
  reranker span — and `HitStage` already carries per-doc `id`/`score`/`detail`. Payoff: **free
  interop with mature trace UIs (Jaeger/Tempo) and RAG-eval platforms (Arize Phoenix) without
  building any of it.** ([OpenInference semconv](https://github.com/Arize-ai/openinference/blob/main/spec/semantic_conventions.md))

### B. Cheap data extensions that unlock the UX in C
- **B1 — re-add the dropped chunk sub-timings** (`chunkBm25Ms`/`chunkKnnMs`/`chunkSpladeMs`) into the
  CHUNK_MERGE detail. They were dropped at E3 for "no consumer"; the waterfall in C1 is the consumer.
- **B2 — add stage nesting + funnel counts.** The trace is a flat list; a flame-graph/funnel wants a
  parent/child tree (fusion ← sparse/dense/splade) and per-stage candidate-in/out counts. Neither is
  carried today.
- **B3 — structure `TraceStage.detail`** (currently a free-form `String`; the fusion method / leg
  set / corrected query are stuffed into it) into a typed/structured shape so the UI renders it
  instead of dumping a string. Polish + extend.
- **B4 — close `decisionKind` into an enum** with the same `stage-completeness`-style gate (today a
  free string + an FE `switch` in `decisionHeadline`).

### C. New UX the trace already has the data for (current panel wastes most of it)
- **C1 — latency waterfall / flame-graph** replacing the flat chip list: span bars sized by `ms`,
  critical-path highlight, **parallel legs (sparse/dense/splade) shown overlapping** (they run
  concurrently). This is the standard trace-waterfall pattern (Jaeger/Tempo/Vespa Trace Visualizer);
  the `ms` data is already on every stage. ([trace waterfall UX](https://oneuptime.com/blog/post/2026-02-06-read-interpret-opentelemetry-trace-waterfalls/view))
- **C2 — per-hit "why this result" score-contribution breakdown** from `HitStage` rank/score/detail
  across stages — the Splainer/Quepid drill-down pattern (top-down "strength of each match", expand
  to a human-readable per-signal breakdown). The per-hit data already ships; the current "Why" shows
  flat text. ([Splainer](https://opensourceconnections.com/blog/2016/05/03/splainer-elasticsearch-relevance-sandbox/))
- **C3 — trace comparison / diff**: two queries, or one query before/after a config change, side by
  side (decisionKind shift, stage status flips, score deltas) — answers "why did this query go
  `sparse_shortcut` instead of `multi_leg`."

### D. Quality loop (the trace as more than a display)
- **D1 — trace-backed relevance regression gate** (Quepid pattern: stored query set + correctness
  validation that blocks ranking-damaging changes). Snapshot traces for a fixed query set, diff on
  change, fail the build on regressions. Fits the existing discipline-gate kernel + `jseval`.
  ([Quepid](https://github.com/o19s/quepid))
- **D2 — LTR feature-logging loop.** `HitStage.detail` *is* a per-hit feature vector, and LambdaMART
  is already a pipeline stage. Pair the logged per-hit features with judgments (manual, or
  click-derived with position-bias debiasing) → training data for the very LambdaMART model the
  trace traces. The trace closes the loop on the reranker it describes — the Vespa "dump rankfeatures
  → build LTR dataset" pattern. ([Elasticsearch LTR](https://www.elastic.co/search-labs/blog/elasticsearch-learning-to-rank-introduction))

### E. AI/agent leverage
- **E1 — trace-driven diagnosis / NL explanation.** `core.summarize` already reads the trace; extend
  to diagnostic narration ("your query degraded to BM25 because the vector index was blocked —
  try …") and let the agent self-triage from the trace.

### Suggested first moves (if/when picked up)
1. **C1 (waterfall)** — pure FE, immediate visible payoff, data already present; the most satisfying
   demo for a pre-production app. (B1/B2 enrich it but aren't blockers.)
2. **A2 (OpenInference spans)** — highest thesis-fit and buys mature external tooling for free;
   needs care (it's wire/telemetry work) but the spans + exporter already exist to converge.
3. **D1 (regression gate)** — protects the quality the campaign exists to serve; reuses jseval + the
   gate kernel.

## Full-thesis campaign progress (2026-05-26)

The approved target structure is being built in phases (each: compile + unit + live where
observable + commit). Status:

- **Phase A — additive types: DONE** (`bc01e49c0`). `StageId`/`StageStatus`/`SearchTrace`/
  `TraceStage`/`HitStage` across proto + app-api + FE wire-types; fixtures exemplify the shape.
- **Phase B — worker projection: DONE, live-verified** (`1b15f0389`, `e9d2e148f`, `7f36709b5`).
  `SearchTraceProjector` emits the worker's query-level stages from the typed decision/outcome;
  `buildHitStages` emits the per-hit slice with a numeric detail tier that is a **lossless
  partition of `debugScores`** (the GPL-safety invariant, unit-tested + **live-confirmed**: union
  of per-hit stage `detail` == `debugScores` on a real query). Head `mapSearchTrace` composes the
  query-level trace (worker + head stages) onto the response; `mapHitStages` carries the per-hit
  slice. All dual-emitted alongside the legacy representations (retire in Phase E).
- **Phase C — head stages: DONE, live-verified** (`acf4dde4b`). `mapSearchTrace` composes the
  query-level head stages; `mapHitStages` appends the head's per-hit cross-encoder `HitStage`.
  Two contributors at both granularities (principle 3). Live: per-hit `trace` carries
  `[sparse-retrieval, cross-encoder]` with the CE score matching `provenance.crossEncoder`.
- **SearchTrace is now structurally COMPLETE** (`f392164e3`, `b7408eea0`): it carries all stages
  (worker + head, query + per-hit) plus the query-level metadata the consumers read —
  `effectiveMode`, `decisionKind`, `qpp`, and `degradation` (trace-level, per the two approved
  structure decisions). introspection has no signal the trace lacks; it can retire in Phase E.
- **Phase D — consumer migration: DONE.** All four consumers read the unified trace.
  - **GPL** (`d6b236346`): reads `unifiedDetailTier` = union of `HitStage.detail`; byte-identical
    26-field `FeaturePayload` (de-risk gate cleared).
  - **526 narration** + **537 eval**: read the trace (canonical-first).
  - **D0 — trace signal gaps closed** (`53bb8cae5`): the FUSION stage now carries `retrievalMs`
    (the last legacy timing signal with a live consumer that lacked a trace home); chunk-merge /
    branch-fusion / cross-encoder / lambdamart `ms` were already on their stages. Chunk sub-timings
    (`chunkBm25Ms`/`chunkKnnMs`/`chunkSpladeMs`) + `chunkCount`/`chunkRetry` have NO consumer →
    dropped at E3. The trace is now a lossless superset of the legacy timing record.
  - **D1 — FE explain consumer** (`55cae8a0f`): the `<jf-search-trace>` aggregate (new `SearchTrace`
    WireAggregateKind + `searchTraceExplain` strategy with the closed `STAGE_LABELS` map) replaces
    the `<jf-search-introspection>` mount; `searchState` captures query-level `searchTrace` + per-hit
    `trace`; per-hit "Why" now reads `hit.trace` (previously read-but-never-captured). The legacy
    SearchIntrospection aggregate kind/strategy/component remain registered-but-unmounted → deleted E4.
  - **D2 — detail-tier negotiation + privacy** (`1a82ca577`): `include_detail` (proto field 16)
    replaces the `debug=true` universe (principle 6). Structural per-hit slice (id+rank+score)
    always-on; numeric `HitStage.detail` gated on `include_detail` (the BM25 sparse-only shortcut
    gets a synthesized structural sparse score so its slice stays non-empty). `debug` deprecated,
    honored as a transitional alias. Per-hit privacy confirmed by a type-level test: HitStage's only
    string scalar is the closed-vocabulary stage id; `detail` is `map<string,float>` — no field can
    echo query/filter text.
- **Phase E — retire legacy: IN PROGRESS** (staged 4 commits).
  - **E1 — debug_scores DONE**: the wire field is retired from proto (`SearchResult.debug_scores`
    reserved), `contracts/wire/knowledge.proto`, app-api `Hit.debugScores`, and FE wire-types. The
    internal worker score map still feeds `HitStage.detail` (gated by include_detail). Consumers
    migrated off it: GPL `unifiedDetailTier` reads only the trace; LambdaMART *inference* now reads
    sparse/dense from the always-on structural trace stages (`protoStageScore`) — which also fixes a
    latent D2 gap where gating would have starved LTR inference; jseval `extract_hit_evidence` falls
    back to `hit.trace` (not debug_scores). Schemas regenerated; tests migrated.
  - **E2 — HitProvenance DONE**: the leg-keyed per-hit HitProvenance is retired across the stack —
    proto `SearchResult.hit_provenance` (field 8) + the `HitProvenance` message reserved/removed;
    `contracts/wire` `Hit.provenance` reserved; app-api `HitProvenance` record removed; worker
    `toProto`/`retrieverProto` + `setHitProvenance` removed; head `mapWorkerProvenance` removed
    (the cross-encoder stage already flows via `mapHitStages`); FE `provenance` removed everywhere
    (wire-types, domains/search, schemas, searchState, plugin-types, SearchSurface
    `provenanceParts`); jseval reads the trace only (`_extract_from_provenance` removed). The
    internal `HitProvenanceSignals`/`HitProvenanceProjector`/`HybridSearchOps` stay — they feed
    `buildHitStages`. Two obsolete seam tests (`SearchResponseBuilderProvenanceTest`,
    `KnowledgeHttpApiAdapterProvenanceTest`) deleted; the per-hit trace is covered by
    `SearchResponseBuilderHitStageTest` + `HitProvenanceProjectorTest`. KnowledgeHttpApiAdapter
    shrank 2330→2260.
  - **E3 — PipelineExecution/ComponentTiming DONE**: proto `SearchResponse.component_timing`
    (field 22) + the `ComponentTiming` message reserved/removed; `contracts/wire` PipelineExecution
    removed; app-api `PipelineExecution`/`ComponentStatus` records + `pipelineExecution` field
    removed; worker `setComponentTiming` removed; head `buildPipelineExecution` (~145 LOC) removed
    + the builder call + controller `out.put`; FE removed everywhere (wire-types, domains/search +
    mapper, schemas, searchState — `processingTimeMs` now derives from the trace FUSION stage ms,
    plugin-types, mocks). jseval `_extract_stage_timing` reads trace stage `ms`. Per-stage timing
    is now SOLELY on `TraceStage.ms` (D0). KnowledgeHttpApiAdapter 2260→2099 (below pin).
  - **E4 — SearchIntrospection DONE**: the structured introspection representation is retired
    end-to-end. Deleted: `SearchIntrospectionProjector` (worker), `KnowledgeIntrospectionMapper`
    (head), the app-api `SearchIntrospection` record, the FE `JfSearchIntrospection` component +
    `searchIntrospectionExplain` strategy. Proto `SearchResponse.introspection` (field 25) + the
    `SearchIntrospection` message + all `Introspection*` companions reserved/removed. `buildHeadStages`
    now returns `SearchTrace.TraceStage` directly (no `SearchIntrospection.Stage` intermediary); the
    trace is always-on (worker emits it unconditionally). Consumers migrated: SearchTool reads the
    correction from the trace's CORRECTION stage; jseval reads query signals/decision_kind/components
    SOLELY from the trace (the introspection + flat-field fallbacks removed); FE aggregate registry +
    bootstrap + wire-types + schemas + searchState + plugin-types all drop introspection.
    KnowledgeHttpApiAdapter shrank 2099→2083 (net 549 SHRINK below its pre-549 baseline).
  - **Criterion #3/#5 ("exactly one source of truth"): MET** — the four legacy representations
    (debug_scores, HitProvenance, PipelineExecution, SearchIntrospection) are physically gone from
    proto/app-api/FE/jseval; SearchTrace is the sole search-explainability artifact on the wire.
  - **E5 — flat `SearchResponse` fields DONE** (the thesis's third named fragment — "the ~18 flat
    boolean + reason fields"). Retired the ~16 flat fields (effective_mode, vector_blocked*,
    hybrid_fallback*, chunk_merge_applied/reason, branch_fusion_*, correction_applied/corrected_query,
    splade_executed/skip_reason, max_idf/avg_ictf/query_scope) from proto `SearchResponse` (reserved)
    + `contracts/wire` + the worker setters. Consumers re-pointed to the trace: the head telemetry
    span + DEBUG-qpp log read `resp.getSearchTrace().getEffectiveMode()/getQpp()`; the dead
    degradation-reason locals (orphaned when buildPipelineExecution retired in E3) removed; GPL's LTR
    FeaturePayload QPP reads `qppOf(resp)` (trace); the per-source multi-query merge OR-merges trace
    degradation + clears trace QPP. **Projector fix the reason-code contract test caught:** the
    chunk-merge stage now carries the decision-derived skip reason (SKIPPED_VECTOR_BLOCKED /
    SKIPPED_EMPTY_QUERY) for blocked/empty decisions — the flat field had set this from the decision,
    the trace previously read it only from the (empty) outcome. 9 worker/app-services behavior tests
    migrated to trace assertions (degradation/qpp/reason-code/legset/facet/otel/per-source). With E5,
    "exactly one source of truth on the wire" is now LITERALLY true — no flat duplicate remains.
- **Live-stack verification (worktree build): CONFIRMED.** Ran this worktree's dists via the
  worktree `dev-runner.cjs` (apiPort 55789, own .dev-data index) and direct loopback fetch to
  `/api/knowledge/search` (the MCP `search_query` strips top-level fields, so curl is used). A real
  query returned top-level keys `{indexCapabilities, searchTrace, totalHits, results, tookMs}` —
  **no `introspection`, no `pipelineExecution`, no per-hit `provenance`/`debugScores`**. The
  `searchTrace` carried `effectiveMode`/`decisionKind`/`qpp`/`degradation` + all 12 stages from both
  processes (correction, sparse/dense/splade-retrieval, fusion (1ms = retrievalMs, D0), chunk-merge,
  branch-fusion, query-understanding, expansion, lambdamart, cross-encoder (140ms), freshness). Per-hit
  `trace` carried the structural slice (sparse-retrieval rank/score + cross-encoder score). Detail-tier
  gating verified: `debug:true` → `HitStage.detail` populated; `debug:false` → structural slice present
  but numeric detail elided (principle 6). End-to-end single-source confirmed on the live wire.
- **Independent review (goal closure): CONFIRMED.** A fresh reviewer (not the implementer) verified
  the thesis on the live wire path: single stage-keyed `SearchTrace` exists (A); the four legacy
  representations are physically gone from proto/app-api/FE — proto fields reserved, messages +
  records + projector/mapper + FE component/strategy deleted, the internal `HitProvenanceSignals`
  correctly retained as the `HitStage.detail` source (B); all four consumers read only the trace
  (C); the stage-completeness gate guards regression (D); gates green modulo the documented
  pre-existing ts-any/ui-bundle drift (E). Three non-blocking findings followed up: the
  contracts/wire `PipelineExecution` was swept in this pass; the `SearchExecutor` pin-realign + the
  contracts/wire `SearchTrace`-absence (wire-gen-path drift) are logged to `docs/observations.md`.
- **Phase F — stage-completeness governance gate: DONE.** New `stage-completeness` discipline gate
  (`scripts/governance/gates/stage-completeness/`, registered in `governance/registry.v1.json`)
  mechanizes principle 9 as a cross-file structural invariant over the closed `SearchTrace.StageId`
  vocabulary: (A) **renderer completeness** — the FE `STAGE_LABELS` map must cover EXACTLY the Java
  StageId wireIds (a new stage with no label, or a phantom label, fails); (B) **produced coverage**
  — every StageId wireId must appear in ≥1 producer (no declared-but-never-produced stage). The
  producer "every stage emits a node" half is already compile-enforced by the default-free
  `HeadStage`/`LegSet` switches; the gate adds the cross-language renderer half the compiler can't
  see. Self-test fixtures (positive → pass; negative with a missing label → fail) verify the gate
  catches divergence. Full governance suite: 15 gates, stage-completeness pass.

### Design refinement found in Phase D (decide before migrating FE / jseval)

`SearchTrace` is currently `{version, List<Stage> stages}` — it carries the per-stage view but
**not the query-level scalars** the FE explain strategy and jseval read from `introspection`:
`effectiveMode`, the `decision.kind` (empty_query/blocked/sparse_shortcut/multi_leg, used by
`stratified_metrics` decision_kind stratification), and `qpp` (maxIdf/avgIctf/queryScope). For the
FE + eval consumers to read ONLY the trace (principle 7) and for `introspection` to be retired
(principle 5 / Phase E), the trace must carry or derive these. Two options to decide:
(a) add trace-level fields `effectiveMode` / `decisionKind` / `qpp` to `SearchTrace`; or
(b) fold them into stages — `qpp` → `QUERY_UNDERSTANDING` stage detail, `effectiveMode` + decision
kind derived from which retrieval/decision stages executed. Option (b) is purer to the stage-keyed
thesis but requires the consumers to derive; (a) is a smaller consumer change. This is a
`SearchTrace`-type decision and should be made (with user agreement, per the goal's precondition)
before the FE/jseval migration, not rushed. **RESOLVED: option (a)** — `effectiveMode`/
`decisionKind`/`qpp` are trace-level fields (`f392164e3`). eval `decision_kind` migrated to read
`trace.decisionKind` (canonical-first; jseval `767` green).

**Second refinement — degradation booleans.** `vectorBlocked`/`hybridFallback` (distinct
booleans the FE/eval read) fold into the dense/splade-retrieval stage **status + reason** per the
thesis (a blocked dense leg = `DENSE_RETRIEVAL SKIPPED` + reason). But a consumer can't cleanly
recover the *boolean* (blocked vs. deliberately-not-selected) from `status==skipped` alone — it
must read the reason, and the reason taxonomy (the `VectorEncoding.Failed` names + `dense-only` /
`not-selected` / `sparse-shortcut`) isn't consumer-friendly. Decide before the FE/eval degradation
migration: (a) derive in consumers from a small machine-checkable reason vocabulary on the stages,
or (b) carry `vectorBlocked`/`hybridFallback`/`spladeExecuted` as trace-level booleans too (like
the scalars). Until decided, FE/eval degradation reads stay on `introspection` (deferring that
slice of Phase E).

Note on live-observability: the query-level `searchTrace` is not observable through the
`search_query` MCP tool (it strips top-level response fields, same limitation noted for
`introspection` in Probe C); it is verified via unit tests + the FE consumer. The per-hit
`trace` IS observable via `search_query verbose` and is live-verified above.

## Confidence / risk (de-risking pass 2026-05-25)

Before committing to this design, six load-bearing assumptions were probed
against source (and the dev stack was held in reserve for two confirmatory
measurements). Findings, highest-risk first. Net effect: the design direction
holds; one mechanism claim was corrected (U1) and the migration is re-framed as
staged (U4).

- **U1 — execution seams → CORRECTED (mechanism, not direction).** Claim probed:
  "each stage *writes* its trace node as it runs." Finding: feasible but wrong
  shape. The worker (`SearchExecutor`) runs stages as linear calls returning
  local results, and the head (`KnowledgeHttpApiAdapter.doSearch`) is one
  sequential method with ~15 scattered stage locals; both already use a
  *projection-at-the-end* pattern (`SearchIntrospectionProjector`,
  `buildPipelineExecution`). Threading a mutable accumulator would churn ~6
  worker signatures + 15 head locals for no gain. **Design updated** (principle
  1 & 3): keep projection-at-the-end; the real fix is to make the authoritative
  typed values (`SearchOutcome`, a typed per-hit provenance on `SearchHit`) rich
  enough to project from directly. Evidence: `SearchExecutor.java:94-353`,
  `KnowledgeHttpApiAdapter.java:417-1160`, `SearchIntrospectionProjector.java:38-73`.

- **U2 — per-hit origin → CONFIRMED (and stronger than assumed).** Claim probed:
  per-hit provenance can be recorded structurally rather than reconstructed from
  `debugScores`. Finding: yes — legs emit typed `SearchHit(docId, score,
  fields)`, and fusion (`HybridFusionUtils`) *always* maintains per-leg
  scores/ranks internally; `debug=true` gates only their **flattening into the
  string map**, not their computation. So a typed per-hit provenance can be built
  unconditionally at fusion time and carried on `SearchHit` → proto → head,
  retiring the lossy `assembleProvenance`. Evidence:
  `LuceneRuntimeTypes.java:111-116`, `HybridFusionUtils.java:119-232`,
  `KnowledgeHttpApiAdapter.java:1856-1943`.

- **U3 — always-on cost → LARGELY DE-RISKED.** Because per-hit data is *always
  computed* (U2), making it always-on costs ~zero compute — only response
  payload. The "always-on structure + optional numeric detail tier" split is
  therefore about wire weight/privacy, not CPU. A live payload-size measurement
  (Probe C) remains optional and confirmatory; it cannot change the design.

- **U4 — blast radius → CONFIRMED LARGE; design re-framed as staged.** The 14
  flat fields are critically baked: manual HashMap in
  `KnowledgeSearchController` (~L306), pinned by `KnowledgeSearchResponseContractTest`
  + `StatusRecordSchemaTest`, generated into `wire-types.ts`, and read by
  `jseval/provenance.py`. So "retire/derive" must be a **multi-step deprecation
  with dual-emission**, not a single-release collapse. Mitigant: the codebase
  *already* dual-emits (flat fields and `SearchIntrospection` carry the same
  data) and the FE already reads the structured form — so this is *finishing a
  convergence already in motion* (designate the trace canonical → derive flat
  fields from it → migrate jseval → remove flat), not inventing one.

- **U5 — governance enforceability → FEASIBLE; mechanism clarified.** The
  `consumer-drift` gate kind is **already implemented on the 530 kernel** (count
  + grace + changeset escape; only production slot population deferred) — it
  covers the *consumer* side (a trace renderer/selection-kind must have ≥1
  consumer). The *producer* side ("every stage emits a node") maps to 525's
  sealed-sum compiler-exhaustiveness, which works because the stage vocabulary is
  **core-owned / closed** — plugins (521) contribute conversation shapes and host
  APIs, not Lucene retrieval legs. **Resolves the "closed vs open stage set" open
  question: closed.** Evidence: tempdoc 531 status line + §"the idea"; 525's
  exhaustiveness discipline.

- **U6 — "never mounted" → NOT A BLOCKER.** `JfSearchIntrospection` +
  `searchIntrospectionExplain` landed in one commit (`0b7bdaf66 feat(525)`),
  are registered and imported in `aggregate-substrate/bootstrap.ts`, and are
  simply not mounted by `SearchSurface` (zero references there). This is
  deliberate substrate-ahead-of-consumer from 525 — the FE explain surface is
  built and registry-ready; mounting it + adding a producer *is* the G33
  consumer. No hidden technical wall.

**Residual / optional:** Probe C (live payload delta debug=false vs true) and
Probe G (live end-to-end confirmation that a non-debug search populates the
expected fields, and that the 526 explain→`core.rag-ask` template fires) are
confirmatory only and require taking the shared dev stack. They would add
measured numbers but, given U1–U6, cannot change the design. Recommend running
them at the start of implementation (when the stack is owned anyway) rather than
seizing it now.

### Probe C/G live grounding (2026-05-25, implementation start)

Ran against the running dev stack (apiPort 50495, 109-doc JustSearch corpus).

- **Confirmed:** per-hit `provenance` flows in the live search response and is
  populated, including per-hit `crossEncoder.score`. G111's data is real and
  reachable.
- **New finding — provenance is path-sparse.** For a chunk-merge/branch-fusion
  query, only the stages that ran appear (`chunkMerge` + `branchFusion` +
  `crossEncoder` present; top-level `bm25`/`splade`/`dense` null). **Design
  implication:** the per-hit value model and the FE renderer must treat absent
  stages as "did not participate," not "missing data" — the stage list is
  inherently partial per hit. (The existing `@JsonInclude(NON_NULL)` record
  already behaves this way; the renderer must too.)
- **Tooling limit (blocker for the raw-payload half of Probe C):** the
  `search_query` MCP tool projects the response down to hits (+ `provenance` +
  `debugScores`) and drops `introspection` / `pipelineExecution` / the flat
  fields; and `/api/knowledge/search` is not on the `api_call` allowlist. So the
  raw debug=false-vs-true payload delta cannot be measured through MCP read
  tools. Non-blocking: U2 already established the gating is serialization-only
  (compute is unconditional), and the contract-test fixtures pin the full
  on-wire shape. The introspection-on-wire claim rests on
  `KnowledgeIntrospectionMapper` (always wired unless `includeIntrospection=false`).
- **Probe G (LLM narration) deferred into Slice 4**, which needs `ai_activate`
  anyway — matches the appendix recommendation.

## Implementation plan (slices)

Each slice ships a production consumer + tests + live-stack verification, and is
independently valuable. Ordered smallest-risk-first so the verification loop is
proven early. All work stays in this tempdoc; progress is logged per-slice below.

- **Slice 1 — Mount the query-level explain panel (G33).** FE-only consumer over
  already-shipped `SearchIntrospection`. Mount the registered-but-unmounted
  `JfSearchIntrospection` / `searchIntrospectionExplain` in `SearchSurface`.
  Delivers G33's per-query view and surfaces the head-stage hole with a real
  consumer in hand. Verify: live search shows the decision/degradation/timing
  panel (ui-shot).
- **Slice 2 — Make the query trace stage-complete (head stages).** Extend the
  query-level trace to include the head-side stages (expansion, LambdaMART,
  cross-encoder, freshness, query-understanding) that are invisible today.
  *Architectural choice point (flagged): evolve `SearchIntrospection` into the
  canonical stage-keyed trace vs introduce a new `SearchTrace` type.* Working
  assumption: **evolve `SearchIntrospection`** (reuses 525's proto + schema
  version + approval corpus + privacy posture; avoids adding a transient 4th
  representation). Verify: panel shows head stages.
- **Slice 3 — Per-hit typed provenance, always-on (G111).** Build typed per-hit
  provenance unconditionally in fusion (`HybridFusionUtils`), carry it on the
  worker `SearchHit` → proto → head, retire the lossy `assembleProvenance`
  reconstruction. Add the per-hit "Why this result?" panel. Verify: non-debug
  search carries provenance; per-hit panel renders (handling path-sparse stages).
- **Slice 4 — LLM narration.** Add `SelectionPayload.SearchTrace` kind +
  `SelectionContextInjector` case + per-hit/per-query "Explain in words" producer
  routing through `compose → core.rag-ask`. Verify with `ai_activate`: click
  explain → LLM narrates the trace (completes Probe G).
- **Slice 5 — Governance (long-term prevention).** Sealed-sum compiler
  exhaustiveness for stage producers + `consumer-drift` slot rows (530/531) for
  the trace renderer and the selection kind, so a new pipeline stage can't ship
  without a trace node + renderer. Verify: gate fails on a synthetic missing
  node/renderer.
- **Slice 6 — Legacy convergence (staged, U4).** Make the unified trace the
  single source; derive the flat fields + `PipelineExecution` as views; migrate
  `jseval/provenance.py` to read the trace; deprecate duplicates without breaking
  contract/schema tests (update them deliberately, dual-emit during the window).
  Verify: contract tests green, jseval reads the trace, eval stratification works.

## Slice progress log

> ## ⚠ VERIFICATION-INTEGRITY CORRECTION (2026-05-26) — read first
>
> **The MCP dev-runner runs the MAIN repo build, not this worktree.** Proof:
> every dev-stack start returned `dataDir: F:/JustSearch/modules/ui-web/.dev-data`
> (main path, not `.claude/worktrees/549-search-trace/...`); the runner's
> `active.json` lives under `F:/JustSearch/tmp/dev-runner/`; and a live search on
> the latest stack shows `introspection` with the **8 pre-549 keys and no
> `headStages`**, although the head record + controller (`out.put("introspection",
> response.introspection())`, whole-record) would serialize it if my build were
> running. So the worktree head/worker dists I built were **never executed** by
> the stack.
>
> **Consequence — prior "live-verified" claims for head-side changes are INVALID
> (they exercised main's code, not mine):**
> - **Slice 3b:** the "byte-identical provenance" match was main's
>   `assembleProvenance` (the fallback) — my worker proto path + head
>   `mapWorkerProvenance` never ran. Behavior-identical output cannot distinguish
>   my path from the fallback, so the match proved nothing about my code.
> - **Slice 4:** the narration came from main's `rag-ask` **ignoring** an unknown
>   `search-trace` selection kind (main's `SelectionPayload` has no such subtype),
>   not from my `injectSearchTrace`. This — not "rag-ask retrieval competing" — is
>   the real reason the narration was RAG-flavored; the Slice-4 "LLM-tier finding"
>   above is therefore also based on main's behavior, not mine.
> - **Slice 6 (headStages):** confirmed not running (absent on the wire).
>
> **FE-only slices (1, 2, 3a):** the data they consume (`introspection` /
> `pipelineExecution` / per-hit `provenance`) genuinely exists in main (525/250),
> so the data-dependency is real and the FE code is unit-tested (tsc + vitest) —
> but the FE *rendering* against my code was not live-verified (ui-shot is
> infra-blocked; the dev-runner serves main's FE).
>
> **Accurate status of every slice:** compiled + unit-tested + (where noted)
> schema-regenerated; **none is live-verified against a running build of this
> worktree's code.** The goal's "no slice `implemented` without live-stack
> verification against a running backend" bar is therefore **not met** for the
> head-side changes — my earlier claims were wrong.
>
> **Root blocker (specific):** the MCP `justsearch-dev` server is rooted at the
> main checkout (`F:/JustSearch`); `dev_start` builds/launches main regardless of
> this session's worktree CWD, and exposes no repo-root/worktree parameter. Live
> verification of this worktree's code requires either (a) launching the worktree
> dists manually (java -jar the worktree `:modules:ui` + `:modules:indexer-worker`
> installDist outputs against a dedicated dataDir, outside the MCP runner), or
> (b) merging to main and verifying there. Both are follow-ups, not done here.
>
> The committed code compiles and passes unit tests; treat its runtime behavior
> as **unverified** until run against the worktree build.
>
> ### ✅ UPGRADE (2026-05-26) — worktree build run method found; Slices 6 + 3b now live-verified against MY code
>
> **Resolution of the run blocker:** `scripts/dev/dev-runner.cjs` resolves
> `repoRoot` from its own `__dirname/../..` (line 21), so invoking **the
> worktree's copy** (`node scripts/dev/dev-runner.cjs start --skip-build
> --takeover force`) builds/launches **this worktree's dists** while sharing
> main's `stateRoot` for coordination. The MCP `justsearch-dev` server is the
> thing rooted at main; the CLI run from the worktree is not. So worktree live
> verification **is** possible — I just wasn't using it.
>
> Ran the worktree build (apiPort 49465, my session owns it; dataDir = the
> worktree's own `.dev-data`, a small help-docs index):
> - **Slice 6 (headStages) — LIVE-VERIFIED on my build.** A search returns
>   `introspection.headStages` populated with the five head stages
>   (query-classification `executed`/`INFORMATIONAL`; expansion `skipped`/`AI_UNAVAILABLE`;
>   lambdamart `skipped`/`MODEL_NOT_LOADED`; cross-encoder `skipped`/`BELOW_MIN_THRESHOLD`;
>   freshness `executed`). The field is **absent** on main's build — definitive proof
>   my head code is the one running.
> - **Slice 3b (per-hit provenance) — flows on my build.** With `debug=true`, hits
>   carry `provenance` (`crossEncoder` leg populated). Build-runs (proven by
>   headStages) + provenance-flows + the `mapWorkerProvenance` unit tests. (The
>   tiny worktree index doesn't trigger the worker-leg/chunk-merge path, so the
>   proto-path-vs-fallback discrimination for worker legs wasn't isolated here;
>   needs a richer index — a follow-up, not a correctness gap.)
> - **Slice 4 (LLM narration) — still NOT live-verified.** `ai_activate` on the
>   worktree stack fails with "Variant not installed: cuda12" — the AI runtime
>   variant isn't installed for the worktree dataDir (main had it). The injector
>   is compiled, unit-tested, and its decode + sealed-switch exhaustiveness are
>   compiler-enforced, but the live LLM narration needs the AI variant installed
>   for the worktree (follow-up: install the variant pack, or verify post-merge).
>
> **Corrected status:** Slices 6 + 3b **are now live-verified against this
> worktree's running build**; FE slices 1/2/3a remain unit-tested with data-deps
> present in main; Slice 4 is code-complete + unit-tested with the AI-narration
> tier blocked on the worktree AI-variant install. The earlier blanket "nothing
> live-verified" is superseded for the non-AI backend changes.
>
> ### ✅✅ POST-MERGE LIVE VERIFICATION (on main, AI active, 2026-05-26)
>
> The branch was merged to main (merge `412434780`; one conflict in
> `gates/consumer-drift/slots.json` resolved by keeping both the 543 §32 autonomy
> slots and the 549 search-explain slot). Ran the **merged main build** with the
> 109-doc index and AI activated (apiPort 52217, cuda12). All three head-side
> changes are now **live-verified against the merged code**:
> - **Slice 6 (headStages):** present, 5 stages populated. ✓
> - **Slice 3b (per-hit provenance via proto path):** on the richer index a hit
>   carries worker-leg provenance — `chunkMerge{sparseRank:56,sparseScore:1.61}` +
>   `branchFusion{2.68/0.52}` + `crossEncoder{0.099}` — produced by the worker's
>   `buildHitProvenanceProto` → `hit_provenance` proto → head `mapWorkerProvenance`.
>   Both my worker + head builds run (headStages proves it), so the proto path is
>   active and behavior-preserving. ✓
> - **Slice 4 (LLM narration) — FIXED + verified.** Live test exposed that
>   `core.rag-ask` is the **wrong shape**: with corpus matches it answers from
>   retrieved docs and ignores the injected trace; with no matches it emits
>   **nothing**. This is a correctness issue, and it **vindicates the original
>   Slice-4 finding** (the integrity-correction had wrongly retracted it as "main
>   ignored the kind"). **Fix:** route `search-trace` to **`core.summarize`**
>   (`SummarizeShape` runs `SelectionContextInjector` without RAG retrieval).
>   Verified on main — the model produces a faithful per-signal explanation:
>   *"This result ranked at position #56 because it initially scored 1.61 using
>   the BM25 search method. After a cross-encoder re-ranking step its score
>   decreased to 0.099. The chunk-merge process was also applied … combining a
>   whole-document score of 2.68 and a chunk-based score of 0.52 through
>   branch-fusion."* `SearchSurface.handleExplainWhy` now composes
>   `operation: 'core.summarize'`. ✓
>
> **Net (corrected, final):** all six 549 slices are now **live-verified against
> the merged main build** (the user surface, the backend U1/U2 provenance, the
> U4 jseval migration, the U5 guard, and the G111 LLM narration). Remaining: the
> pre-existing `ts-any` / `ui-bundle` gate drift on main (NOT introduced by 549 —
> the failing files are untouched by this work; logged to observations), the
> class-size growth of `KnowledgeHttpApiAdapter` (549-introduced — needs a
> changeset or extraction), U4's final flat-field removal window, and Slice 5b
> producer exhaustiveness.

### Slice 1 — query-level explain panel (G33) — DONE (2026-05-25)

**Verification:** compile (tsc) clean; unit green (`searchState` 12 incl. 4 new,
`SearchSurface.searchTrace` 4 new render-gating tests, full FE suite 2035 pass);
**live-stack (A)** — direct loopback fetch to the running backend confirms
`introspection` is on the wire in a non-debug search (data dependency real).
FE capture (searchState tests) + render-given-data (render test) + live data
present (A) close the loop.

**ui-shot visual — blocked (specific infra, not the feature).** `jseval ui-shot
search-results` times out waiting for `search-result-row` in **both** demo mode
and `--no-demo` (worktree vite on 5174, proxy pinned to backend 50495 via
`VITE_JUSTSEARCH_API_PORT`). Demo mode needs no backend and doesn't touch this
slice's data path, yet fails identically — so the failure is the worktree
`ui-shot` auto-serve/proxy infra (the `dev-runner-vite-proxy-cdp-hang` area,
tempdoc 545), not this change: tsc + the unit suites import and render
`SearchSurface` without error. Retry the pixel capture opportunistically once a
worktree-owned dev stack is up for Slice 3's backend work.

Original wiring notes:

- **FE wiring done:** `searchState` now captures `introspection` from the
  response into a first-class `SearchState.introspection` field (cleared on
  empty query / stale responses); `SearchSnapshot` carries it (opaque) across
  the plugin-api boundary; `SearchSurface.renderExplainPanel()` mounts the
  already-registered `<jf-search-introspection>` when a completed search has a
  trace. Files: `state/searchState.ts`, `plugin-api/plugin-types.ts`,
  `views/SearchSurface.ts`. Compile + unit green (12 `searchState` tests incl. 4
  new; aggregate-substrate + multiSelect suites green).
- **Live-stack verification (A):** direct loopback fetch to the running backend
  (`POST /api/knowledge/search`, **no `debug` flag**) returns `introspection` on
  the wire — full `decision`/`timing`/`degradation`/`chunkMerge`/`correction`/`qpp`.
  G33's data dependency is confirmed against a real backend.
- **⚠ Interrogation finding — corrects the appendix's U2/U3 gating claim.** The
  same non-debug response *also* carried per-hit `provenance` **and**
  `debugScores`. The appendix (and 549's "Inconsistent gating" problem
  statement) asserted per-hit data exists only under `debug=true`. Live evidence
  contradicts that: on the chunk-merge/branch-fusion path, per-doc scores are
  written unconditionally (Probe B: `HybridFusionUtils.fuseWithCC` writes
  sparse/rank keys outside the `if (debug)` guard; only `cc`/`cc_alpha`/rrf
  detail keys are gated). **Implication:** G111's premise is weaker than stated —
  per-hit data is *partially always-on and path-dependent*, not absent. The real
  G111 gaps are (a) cross-path *consistency/completeness* of per-hit provenance,
  (b) the lossy `assembleProvenance` reconstruction, (c) no FE consumer — not
  "no data." **Action:** Slice 3 must pin the exact per-path gating before
  claiming "always-on"; do not repeat the debug-gated framing unverified.

### Slice 2 — stage-complete query trace (head stages) — DONE, FE-only (2026-05-25)

**Landed approach.** The explain panel now renders the head-side stages
(LambdaMART / cross-encoder / expansion / query-classification / chunk-merge /
branch-fusion) from `pipelineExecution.components` (+ `crossEncoderMs` /
`lambdaMartMs`), which is **already on every response and already typed in the
FE** (`api/domains/search.ts → PipelineExecution`). `searchState` now captures
`pipelineExecution`; `SearchSurface.renderPipelineStages()` renders the
component statuses below the worker `introspection` panel, making "what
happened" stage-complete. Files: `state/searchState.ts`,
`plugin-api/plugin-types.ts`, `views/SearchSurface.ts`.

**Verification.** tsc clean; unit green (`searchState` 14 incl. 2 new,
`SearchSurface.searchTrace` 7 incl. 3 new, multiSelect 7). **Live-stack:**
loopback fetch to the running backend (non-debug) returns
`pipelineExecution.components` with `query_classification` (INFORMATIONAL),
`expansion` (skipped/AI_UNAVAILABLE), `cross_encoder` (executed),
`lambdamart` (skipped/MODEL_NOT_LOADED), `branch_fusion`, `chunk_merge`, plus
`crossEncoderMs: 276` — the real head-stage data the panel renders, the stages
the worker-only `introspection` omits. (ui-shot pixel capture remains infra-
blocked per Slice 1; data→render is unit-proven + data live-confirmed.)

**Correction of an earlier mis-diagnosis (verify-don't-guess).** An initial
backend spike added a head-only `headStages` field to the Java
`SearchIntrospection`; it compiled but the FE wire type didn't pick it up. I
*twice mis-diagnosed* the generation pipeline (guessed "proto-derived", then
"stale Java-record build") before reading the source. **Truth:** `wire-types.ts`
is generated from `contracts/wire/*.proto` via `./gradlew :wireGenerate`
(typescript-generator retired, slice 3a-1-8 Phase 4; ADR `09a-wire-contract-format`);
the `WireTypesTsGenerationTest` header is stale; and `SearchIntrospection` is not
even present in `contracts/wire/`. So adding a *new* field to the FE
`SearchIntrospection` type is non-trivial and entangled with a mid-migration
wire-contract substrate. Rather than chase that (a rabbit hole) or ship an
unconsumed backend field (a C-018 substrate-without-consumer violation), I
reverted the spike and delivered the user-visible stage-completeness from
`pipelineExecution`, which is already typed and consumed. The earlier
"flagged Alt A/B/C — needs user buy-in" framing is **withdrawn**: it rested on
the wrong proto-derived diagnosis.

**Deferred to Slice 6 (convergence).** Reading two sources (worker
`introspection` + head `pipelineExecution`) in the panel is transitional. The
single canonical stage-keyed trace — and the decision of *how its FE wire type
is generated* given the `contracts/wire` + `:wireGenerate` substrate — belongs
to Slice 6, where the legacy representations are unified/retired anyway. That is
the right home for the wire-generation question, not a blocker for Slice 2's
user value.

**Slice 6 must resolve** the FE wire-type generation path for any new
canonical-trace fields (`contracts/wire` + `:wireGenerate`; confirm whether the
Java records or the wire protos are the source of truth for head-emitted JSON
types).

### Slice 3 — per-hit "Why this result?" (G111) — DONE for the FE consumer (3a); backend cleanup (3b) deferred (2026-05-25)

**Landed (3a — the user-visible consumer).** Each search row now renders a
native `<details>` "Why this result?" disclosure summarizing the hit's ranking
provenance (which retrieval legs found it + ranks/scores, fusion method, chunk-
merge, branch-fusion, reranker), read from the per-hit `provenance` already on
the wire and already typed (`api/domains/search.ts → HitProvenance`).
`searchState` captures per-hit `provenance`; `SearchHitSnapshot` carries it
(opaque); `SearchSurface.renderWhy()` renders it (native disclosure → no
component state; click `stopPropagation` so toggling doesn't select the row).
Path-sparse handled: only stages that touched the hit are shown; renders nothing
if none. Files: `state/searchState.ts`, `plugin-api/plugin-types.ts`,
`views/SearchSurface.ts`.

**Verification.** tsc clean; unit green (`searchState` 15 incl. per-hit capture,
`SearchSurface.searchTrace` 10 incl. 3 `renderWhy` gating tests; full FE suite
2044). **Live-stack:** non-debug loopback fetch confirms `results[0].provenance`
present with `chunkMerge` + `branchFusion` + `crossEncoder` (score 0.099) — real
renderable signals. This also **confirms the Slice-1 interrogation finding**:
per-hit provenance is on the wire without `debug=true` (the appendix's "debug-
gated" claim was wrong; it is path-dependent but present on the chunk-merge
path). The FE consumer renders whatever is present, so it is robust to the
gating regardless.

**Deferred (3b — backend structural cleanup, U2).** Recording provenance
unconditionally in fusion (`HybridFusionUtils`), carrying a typed per-hit
provenance on the worker `SearchHit` → proto → head, and retiring the lossy
`assembleProvenance` reconstruction is an **internal** refactor (the external
JSON `HitProvenance` shape is unchanged, so the FE consumer above is unaffected).
It needs a worker+head rebuild + live re-verify that provenance still matches.
Sequenced with Slice 6's convergence (and gated by first pinning the exact per-
path gating per the Slice-1 finding) rather than blocking G111's user value.

### Slice 5 (partial) — consumer-drift guard for the G33 panel mount (U5 / principle 9) — DONE (2026-05-25)

Registered a `consumer-drift` slot (the **shipped** 531 gate on the 530 kernel)
for `jf-search-introspection`: `gates/consumer-drift/slots.json` now declares the
G33 explain panel must retain ≥1 production consumer outside its own declaration
+ the aggregate bootstrap. This codifies that the panel must stay mounted — the
exact 525 "registered-but-unmounted" C-018 hollow that Slice 1 closed cannot
silently reopen. **Verified:** `node scripts/governance/run.mjs --gate
consumer-drift --mode gate` → `pass` (count ≥ expectedMin=1; the enforcer counts
production files referencing the symbol, so SearchSurface's mount is the
consumer). Adding a *new* slot needs no changeset (the tampering guard only
fires on lowering/removing existing slots). No rebuild required.

The **other half of Slice 5** — sealed-sum compiler exhaustiveness for trace
*producers* (a new pipeline stage must emit a node) — depends on the canonical
stage vocabulary existing, which is Slice 6's convergence; deferred to there.

### Slice 3b — worker-side typed per-hit provenance (U1/U2) — DONE, staged dual-emit, live-verified (2026-05-25)

The head no longer reconstructs per-hit provenance from the stringly-typed
`debug_scores` map for current workers. **Worker** (`SearchResponseBuilder`)
builds a typed `HitProvenance` proto from its own per-leg scores
(`buildHitProvenanceProto`, porting the retrieval/fusion legs of the former head
`assembleProvenance`) and ships it on `SearchResult.hit_provenance` (new gRPC
proto message). **Head** (`KnowledgeHttpApiAdapter`) prefers the worker's typed
provenance (`mapWorkerProvenance` → app-api record + adds the head-side
cross-encoder leg) and **falls back to `assembleProvenance` only when the worker
omitted it** — staged dual-emit per U4, so nothing breaks for a pre-3b worker.
The external `KnowledgeSearchResponse.HitProvenance` JSON shape is unchanged
(internal worker↔head proto change only) — no FE/schema/wire-types impact.

Files: `ipc-common/.../indexing.proto` (new `HitProvenance` message + field 8 on
`SearchResult`), `worker-services/.../SearchResponseBuilder.java` (build + thread
`spladeExecuted`/`chunkMergeApplied`), `app-services/.../KnowledgeHttpApiAdapter.java`
(`mapWorkerProvenance` + prefer-proto/fallback).

**Verification.** ipc-common/worker-services/app-services compile green;
`KnowledgeHttpApiAdapterProvenanceTest` green incl. 2 new `mapWorkerProvenance`
cases. **Live-stack (worktree build, apiPort 62990):** a non-debug search returns
per-hit provenance **byte-identical** to the prior `assembleProvenance` output
(doc 477: `chunkMerge.sparseRank=56, sparseScore=1.6061065`, `branchFusion`
`wholeBranchScore=2.681556/chunkBranchScore=0.51994395`, `crossEncoder=0.099243164`)
— proving the worker→gRPC→head typed path works and preserves behavior exactly.

**Final retirement of `assembleProvenance`** — DONE 2026-05-26, see the dedicated
entry below. The literal "build in fusion (HybridFusionUtils)" placement (vs.
centralized in `SearchResponseBuilder` from the worker's own debug-scores) is a
within-worker refinement noted for Slice 6.

### Slice 3b-retire — `assembleProvenance` retired (U2 part b) — DONE, live-verified (2026-05-26)

U2's second half ("retire the lossy `assembleProvenance` reconstruction") is now
complete. There is no longer a dual path: the head **always** projects per-hit
provenance from the worker's typed `hit_provenance` proto via `mapWorkerProvenance`
— passing `HitProvenance.getDefaultInstance()` when the worker emitted no legs, so a
cross-encoder-only hit still gets its CE leg (the only behavior the former fallback
provided). The ~88-LOC `assembleProvenance` method (which rebuilt provenance by
parsing the stringly-typed `debug_scores` map head-side) is **deleted**.

Files: `app-services/.../KnowledgeHttpApiAdapter.java` (deleted `assembleProvenance`;
hit-loop now unconditionally calls `mapWorkerProvenance(... default-instance fallback)`);
tests re-homed — head `KnowledgeHttpApiAdapterProvenanceTest` keeps only the
`mapWorkerProvenance` cases (+ 2 new default-instance/CE-only cases); the
debug-scores→provenance parsing cases moved to worker-services'
`SearchResponseBuilderProvenanceTest` (asserting on the typed `HitProvenance` proto
that `buildHitProvenanceProto` produces).

**Verification.** `spotlessApply` + app-services/worker-services compile green; both
provenance suites green (`SearchResponseBuilderProvenanceTest`,
`KnowledgeHttpApiAdapterProvenanceTest`). Class-size gate **pass** (file shrank
2271→2190; declared-growth changeset updated). **Live-stack (main rebuilt + restarted,
apiPort 62595):** `GET /api/knowledge/search?...&verbose` returns per-hit `provenance`
with `chunkMerge` + `branchFusion` + `crossEncoder` legs, values consistent with
`debugScores` (`sparseRank=9 ↔ chunk_sparse_rank=9`, `wholeBranchScore=6.412996 ↔
whole_branch=6.412996`, `crossEncoder=-0.036376953`) — proving the head's
worker-proto-only path preserves behavior after the fallback's removal.

Remaining U2 refinement (now done — see Slice 3c below): move the typed build off the
`debug_scores` map onto a typed carrier on the worker `SearchHit`.

### Slice 3c — typed provenance carrier built at the orchestrator (U1/U2) — DONE, live-verified (2026-05-26)

U2's "build typed, not reconstructed" intent, completed. The user-approved design
(over the literal "build in `HybridFusionUtils`", which is unsound — the fuser is a
generic N-leg RRF/CC utility with no retrieval semantics; the same `fuseWithRRFNamed`
builds top-level, chunk-merge, and branch-merge fusions, distinguished only by a
caller-supplied key prefix): build a typed `HitProvenanceSignals` carrier **at the
orchestrator** (`SearchExecutor`), where the leg semantics are known, from the typed
pre-fusion leg results; leave `HybridFusionUtils` untouched.

- **New types** (`LuceneRuntimeTypes`, adapters-lucene, no proto dep): `RetrieverSignal`,
  `FusionSignal`, `ChunkMergeSignal`, `BranchFusionSignal`, `HitProvenanceSignals`, plus a
  5th nullable `provenance` field + `withProvenance` on `SearchHit` (existing ctors default
  it null — the 9 callsites compile unchanged).
- **`HitProvenanceProjector`** (adapters-lucene): indexes typed leg results (rank = 1-based
  position, rawScore = `hit.score()`) and re-maps the resulting provenance onto the
  post-fusion hits **by docId** — because the fuser creates fresh hits and copies forward only
  `debugScores`, never the typed field. `attachRetrieval` / `attachChunkMerge` /
  `attachBranchFusion` + `attachSingleLeg`.
- **`SearchExecutor`** attaches per path: `runThreeWay` (bm25+splade+dense + cc),
  `runMultiLeg` leg-set cases, `runSparseShortcut` (bm25), chunk-merge (`attachChunkMerge`
  before collapse; collapse helpers preserve `provenance()`) + branch fusion
  (`attachBranchFusion` re-maps whole-doc retriever legs + chunk-merge leg + branch scores).
  `Bm25Dense`'s legs live inside `HybridSearchOps.executeHybrid`, so that path attaches there
  (no behavior-changing re-route).
- **`SearchResponseBuilder.toProto`** maps `hit.provenance()` → wire proto directly; the
  `buildHitProvenanceProto` debug_scores reconstruction is **deleted** (and its
  `spladeExecuted`/`chunkMergeApplied` params dropped from `toGrpcResponseBuilder`).

**Two correctness wins over the string path** (not cosmetic): (1) **bm25-vs-splade mislabel
fixed** — a `ThreeWay` result's lexical key is just `"sparse"`, which the parser routed to
SPLADE whenever `spladeExecuted` was true; the typed legs go to distinct fields. (2)
**always-on fusion leg** — the fused score is `hit.score()` (always present), so
`provenance.fusion` / `branchFusion.fusionScore` no longer require `debug=true`.

**Verification.** adapters-lucene/worker-services/app-services compile + full module test
suites green; new `HitProvenanceProjectorTest` (adapters-lucene) + rewritten
`SearchResponseBuilderProvenanceTest` (now tests `toProto`) + retained
`KnowledgeHttpApiAdapterProvenanceTest`. Full `build -x test` green; class-size gate pass
(`SearchExecutor` 990→1031 grandfathered + declared-growth changeset; decomposition logged to
observations). **Live-stack (main rebuilt + restarted, apiPort 50896), non-debug search:**
per-hit `provenance` now carries `bm25:{rank:3,rawScore:6.412996}` (correctly labeled, not
splade), `chunkMerge:{...,ccScore:0.6282355}` (ccScore now populated), and
`branchFusion:{...,fusionScore:0.68916285,method:"cc"}` — the fusionScore/method were ABSENT
pre-3c (debug-gated), proving the always-on win end to end.

### Slice 5 — governance: stage-completeness invariant (U5) — DONE (2026-05-26)

Both halves of U5's "a new pipeline stage can't ship without a trace node + renderer."

- **Producer side (compiler-enforced).** The head-process stage vocabulary is closed
  (appendix U5: plugins contribute conversation shapes / host APIs, not Lucene retrieval
  legs), so it is modeled as the closed enum `KnowledgeHttpApiAdapter.HeadStage`
  (`query-classification` / `expansion` / `lambdamart` / `cross-encoder` / `freshness`) and
  consumed by an **exhaustive `switch` with no `default`** in `buildHeadStages`. Adding a stage
  forces a compile error until it emits a node — the producer-side "every stage emits a node"
  invariant. Backstop: `KnowledgeHttpApiAdapterHeadStagesTest` asserts every `HeadStage` yields
  exactly one node and pins the wire IDs (a rename is a deliberate FE-breaking change).
- **Consumer side (consumer-drift gate, 530/531).** Two slots in
  `gates/consumer-drift/slots.json`: `search-explain-panel-mount` (Slice 5a — the
  `<jf-search-introspection>` renderer must stay mounted, floor 1 = `SearchSurface.ts`) and the
  new `search-trace-selection-kind` (the `search-trace` `SelectionPayload` kind must retain ≥1
  producer outside its declaration, floor 1 = `SearchSurface.ts`). So the explain renderer and
  the narration selection-kind can't drift back to dormant/never-mounted.

**Verification.** Producer: exhaustive-switch compiles (5 cases, no `default`);
`KnowledgeHttpApiAdapterHeadStagesTest` green. Consumer: `consumer-drift` gate **pass** with
the new slot evaluated (floor met). Full `build -x test` green (class-size: the `HeadStage`
enum's +~30 LOC on `KnowledgeHttpApiAdapter` is covered by the declared-growth changeset,
2118→2221). The gate-fails-on-synthetic-missing case is proven by the consumer-drift self-test
fixtures (slots.json `$comment`).

### Slice 4 — LLM narration of the trace (G111 "explain in words") — DONE, live-verified incl. LLM tier (2026-05-25)

Added a `SelectionPayload.SearchTrace` kind (Java `SelectionPayload` sealed sum +
Jackson subtype + FE `selection.ts` union) and a `SelectionContextInjector`
`injectSearchTrace` case (frames the trace as a meta-message, mirroring
`injectHealthCondition`). Producer: an **"Explain in words"** button on the
per-hit "Why this result?" disclosure (`SearchSurface.handleExplainWhy`) builds a
`search-trace` selection from the hit's provenance signals and routes it to a
**non-RAG** explain shape (`core.summarize` / `SummarizeShape`, which composes
`SelectionContextInjector` without corpus retrieval — see the resolved finding below). Files:
`app-api/.../selection/SelectionPayload.java`, `app-services/.../SelectionContextInjector.java`,
`ui-web/.../api/types/selection.ts`, `ui-web/.../views/SearchSurface.ts`.

**Verification.** app-api/app-services compile (sealed-switch exhaustiveness
forces the injector case); FE typecheck + SearchSurface tests green (17).
**Live-stack incl. the LLM tier** (worktree head rebuilt, apiPort 64375,
`ai_activate` GPU): `POST /api/chat/dispatch` with a `search-trace` selection
returned a 1440-char narration, no error — proving the full chain
producer→`compose`→`core.rag-ask`→`injectSearchTrace`→LLM works end to end.

**✓ LLM-tier finding — RESOLVED (2026-05-26).** The initial routing to `core.rag-ask`
made the shape **retrieve corpus docs** on the question, competing with the injected
trace — the narration explained the ranking mechanism from retrieved docs rather than
the injected trace. Fixed by routing `handleExplainWhy` to `core.summarize`
(`SummarizeShape`), which runs `SelectionContextInjector` (so `injectSearchTrace` fires)
**without** RAG retrieval, so the model narrates the trace itself. Verified on main with
`ai_activate`: faithful per-signal narration. (`SearchSurface.ts:805` `operation:
'core.summarize'`.)

### Slice 6 (partial) — U4 convergence: designate the trace canonical, migrate the jseval consumer (2026-05-25)

First staged U4 step. `jseval/provenance.py:extract_query_evidence` now reads the
query-level degradation / chunk-merge / effective-mode signals from the
**canonical `introspection` trace first**, falling back to the (dual-emitted,
deprecated) flat fields only when the trace is absent — exactly U4's "designate
the trace canonical; derive the rest; staged, no single-release delete." One of
U4's four named consumers (`jseval`) is migrated; the flat fields stay emitted
(no contract break).

**Verification.** Deterministic check: introspection wins over a conflicting flat
field; flat-field fallback works when the trace is absent. **Live:** real search
response (apiPort 64375) carries `introspection`; `extract_query_evidence`
reads it and **agrees with the flat fields** (no regression — e.g.
`chunkMerge.applied:false`/`SKIPPED_QUERY_SYNTAX` matches flat `chunkMergeApplied:false`).
jseval `test_provenance` suite green (21 passed).

**Critical-analysis reframe (2026-05-25) — U4 consumer-migration is essentially
complete; the remainder is U4's *explicitly-deferred* removal stage.** A source
audit of all consumers of the 14 flat fields found:
- **jseval** → migrated to the canonical `introspection` (Slice 6a).
- **FE** → all *meaningful* consumers already read the canonical trace: the
  `searchIntrospectionExplain` strategy reads `introspection.degradation` /
  `.decision` / `.correction` (not the flat fields), and per-hit/`pipelineExecution`
  consumers read the typed structures. The only flat-field references left in the
  FE (`correctionApplied` / `correctedQuery` in `domains/search.ts` + Zod) are
  **vestigial** — defined and mapped but rendered by *no component*.
- **Controller / contract-schema tests** → still emit/pin the flat fields, which
  is **correct for the staged state**: U4 mandates *dual-emit*, "**not** a
  single-release delete." Keeping the flat fields emitted while consumers read the
  canonical trace is precisely the staged deprecation U4 prescribes.

So the trace **is** the designated single source that consumers read; the flat
fields are a deprecated, dual-emitted compatibility layer. **Removing** them (and
retiring the `assembleProvenance` fallback) is U4's *final* stage — explicitly a
later removal window, not this pass. Principle #1 ("single source of truth") is
met in U4's intended sense: one canonical trace, read by all consumers, with a
sunset-pending shim. The remaining genuine work:

- **Final flat-field removal window** (U4's last stage): delete the flat fields
  from the record/controller/proto + update the pinned `KnowledgeSearchResponseContractTest`
  / `StatusRecordSchemaTest` + regenerate wire-types/fixtures. Contract-baked; do
  as one coherent deprecation-window pass.
- **Head-side single-source dependency:** the head-only flat fields (LambdaMART /
  cross-encoder / expansion) can only be *derived* from the canonical trace once
  the head stages live in it — gated on the Slice-2 `contracts/wire`/`:wireGenerate`
  resolution (they ride `pipelineExecution.components` today).
- **Slice 5b** (sealed-sum producer exhaustiveness): needs a canonical stage
  *enum* (sealed sum), which the head-stage-in-trace work establishes. Gated on
  the same resolution.
- **Slice-4 non-RAG narration-shape refinement** (route `search-trace` → a
  non-retrieval explain shape).

These are interdependent and contract-baked — best done in one coherent pass with
fresh budget. Designs + mechanics are in the Implementation-plan section and the
Slice-2/3b findings above.

### Slice 6 — final flat-field removal (U4's last stage) — DONE (2026-05-26)

The deprecation window is closed: all 15 flat query-trace fields are **removed** from
`KnowledgeSearchResponse`. The canonical `introspection` trace (+ `headStages`, established
by Slice 5) is now the sole source — no dual-emit shim. Principle #1 ("single source of
truth") is met in the strong sense.

- **Record + emit:** removed the 15 fields from `KnowledgeSearchResponse`; deleted the
  controller HashMap puts (`KnowledgeSearchController`) and the adapter builder calls
  (`KnowledgeHttpApiAdapter`). The worker proto is **untouched** — it still carries the raw
  signals (effective_mode, vector_blocked, …); the head folds them into `introspection` via
  `KnowledgeIntrospectionMapper` + `buildHeadStages`. So the change is head-only.
- **Consumer migration completed:** `SearchTool` (agent tool — the one non-vestigial Java
  consumer the audit missed) now reads `introspection.correction` for the "(corrected to: …)"
  display. FE: removed the vestigial `correctionApplied`/`correctedQuery` from
  `SearchResponse` + the raw DTO + the mapper + the Zod schema + `wire-types.ts`; the explain
  panel already read `introspection.correction`. jseval already canonical-first (Slice 6a).
  Demo mocks (`handlers.ts`) migrated to carry `introspection` instead of flat fields.
- **Contract/schema tests updated:** `KnowledgeSearchResponseContractTest`
  `CONTROLLER_MAPPED_FIELDS` trimmed; `StatusRecordSchemaTest` fixture builder trimmed +
  regenerated via `:modules:app-api:updateSchemas` (FE `search-response-live.json` fixture
  regenerated too).

**Verification.** Full `build -x test` green; class-size pass. Java suites green:
`KnowledgeSearchResponseContractTest`, `StatusRecordSchemaTest` (the serialization fixture —
serializes a real `KnowledgeSearchResponse` through the production Jackson path and asserts
the JSON now has **no flat fields** and **carries `introspection`** — the authoritative tier
for a wire-shape change), `SearchToolTest`, plus `ui`/`app-agent`/`app-services` suites. FE:
typecheck clean + **2049 unit tests pass** (consume the new shape).

**Live verification — DONE (2026-05-26, apiPort 65506, AI active).** After the cold-start
environment issue was resolved, the full live check ran. Per-hit `provenance` flows with **no
flat fields anywhere on the response**, across all three path-sparse shapes: a whole-doc-only hit
(`bm25` + `branchFusion`, no `chunkMerge`), a chunk-only hit (`chunkMerge` + `branchFusion` with
`wholeBranchScore:0`, no `bm25`), and a mixed hit (`bm25` + `chunkMerge` + `branchFusion`). The
`bm25` leg's rank/rawScore match the `debugScores` (divergence guard holds live too);
`branchFusion.fusionScore`+`method` are present on a non-debug search (always-on win); and with
AI active the head-side `crossEncoder` leg is added (`mapWorkerProvenance`). The top-level
multi-leg `fusion` leg could not be exercised — this dev index has unbackfilled embeddings
(`embedding_status:PENDING`), so the whole-doc branch routes sparse-only and the dense leg is
empty regardless of `mode=HYBRID`; that leg is unit-tested (`HitProvenanceProjectorTest`) and
uses the identical always-on `hit.score()` `FusionSignal` mechanism confirmed live for
`branchFusion.fusionScore` and `chunkMerge.ccScore`. (Pre-existing, unrelated:
`SubstrateSchemaGenTest` fails on clean main — logged to observations.)

### Wire-gen question — RESOLVED (2026-05-26), no longer a user-buy-in blocker

The Slice-2 blocker (how a new canonical-trace field reaches the FE
`SearchIntrospection` type) is resolved by source audit, correcting my earlier
over-cautious "needs user buy-in" framing (the goal: proceed on a working
assumption for reversible, pre-release choices):

- `wire-types.ts` is a **frozen, hand-editable snapshot**. Its "Generated… do not
  edit… run updateSchemas" header is **stale**: `WireTypesTsGenerationTest` and
  the typescript-generator dep were **retired** (slice 3a-1-8 Phase 4); the
  `:modules:app-observability:updateSchemas` task now regenerates only
  `HealthEventSchemaTest`, not this file. **No test/check asserts its content.**
- `SearchIntrospection` exists **only** in this frozen barrel (not in the
  protobuf-es `*_pb` files); the aggregate substrate consumes it from the barrel.

**Working assumption (decided, flag for review):** add `headStages` + a `Stage`
interface to `SearchIntrospection` by **hand-editing the frozen barrel** — it
won't be regenerated/overwritten and no check fails. This is consistent with how
`SearchIntrospection` already lives there, and unblocks: re-applying the Slice-2
head-stage spike (Java `SearchIntrospection.headStages` + `buildHeadStages`),
rendering head stages from `introspection.headStages` (+ the
`SEARCH_INTROSPECTION_EXPLAIN_ROLES` exhaustiveness role), and a sealed-stage
vocabulary that unblocks **Slice 5b**. **Structural follow-up (cleaner, larger):**
migrate `SearchIntrospection` into `contracts/wire` + protobuf-es `*_pb` and move
FE consumers off the transitional barrel — its own slice, since the barrel is
explicitly transitional.

**Net effect:** the remainder is now *execution on a decided path*, not a blocked
decision. It is **not started here** because it concentrates in the
wire-generation area where this session made three mis-diagnoses (proto-derived →
Java-record → frozen-barrel) at extreme context depth; a precision-critical
wire/governance change there is best executed with fresh budget to avoid
introducing defects — a correctness/quality judgment (not deferral-for-
inconvenience), consistent with the verification discipline.

**Net confidence after pass:** design direction ~90% (up from ~85); structural
implementability ~75% (up from ~45) — the two assumptions that could have forced
a pivot (U1 mechanism, U2 feasibility) are resolved, and the largest remaining
risk (U4) is a known, staged migration rather than an unknown. **Slice-2
addendum:** principle #3 ("one schema, two contributors") gained a concrete
open sub-question — the head-only-field → FE-wire-type generation path — which
must be resolved before the cross-process trace can be FE-consumed.
