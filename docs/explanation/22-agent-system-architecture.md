---
title: Agent System Architecture
type: explanation
status: stable
description: "Agent loop, operation-substrate tool dispatch, token budget, durability, and MCP tool surface."
---

# Agent System Architecture

JustSearch includes an agentic assistant that can search the knowledge base, browse indexed folders, request ingestion, and perform approved file operations. The agent runs in the Head process, delegates online inference to the app inference runtime, and delegates Lucene index I/O through service/Worker abstractions.

## Conversation Substrate

The agent loop does not own the HTTP/LLM surface directly — it is one *shape* within a shared conversation substrate (tempdoc 491). `ConversationEngine` (in `modules/app-services`) is the one runtime for every model-driven interaction, dispatching a request to a registered `ConversationShape` by its `ConversationShapeRef`. Shapes are declared in `ConversationShapeCatalog` (`modules/app-agent-api`); examples include `AgentRunShape` (the tool-using agent), `FreeChatShape`, `RAGAskShape`, the summarize family (`SummarizeShape` / `BatchSummarizeShape` / `HierarchicalSummarizeShape`), `ExtractShape`, `NavigateChatShape`, and `WorkflowRunShape`.

The engine runs a shape in one of two execution modes:

| Mode | Behavior |
|------|----------|
| `SHAPE_DRIVEN` | The engine delegates the entire conversation lifecycle to a registered `ShapeRunner`. This encapsulates existing implementations — the **agent loop is the canonical example**: `ToolIteratingShapeRunner` is the `AgentRunShape` (`core.agent-run`) runner, parsing the request body into an `AgentRequest` and delegating to `AgentService.runAgent`. |
| `SUBSTRATE_DRIVEN` | The engine controls the per-iteration loop itself and invokes the shape's declared SPIs in order — `PromptContributor`s (assemble the system prompt by priority), `ContextInjector`s (prepend injected messages), `OnlineAiService.streamChat`, `StreamConsumer`s (collect message deltas), then `IterationController` (decide whether to loop). |

HTTP/SSE chat endpoints route through `ChatController.dynamicHandler`, which calls `ConversationEngine.run` with an SSE sink. Before invoking either mode the engine validates the request's invocation `Audience` against the shape's declared audience (trust gating). The sections below describe the body of the `AgentRunShape` — the agent loop.

## Agent Loop

Primary entry point: `AgentLoopService.runAgent()` in `modules/app-agent` (the `AgentRunShape` body, reached via `ToolIteratingShapeRunner`).

`runAgent` is a thin driver (tempdoc 240): it builds the session, then loops over `AgentStepRunner.executeIteration(...)`, which runs one iteration and returns a typed `IterationOutcome` (`cont` / `terminated(success)`). The loop's responsibilities are split across package-private collaborators rather than one method.

`AgentLoopService` is the sole implementor of the `AgentService` interface, so over time the *non-loop* surface (read-time projections, per-run control, prompt assembly) accreted there alongside the loop. Tempdoc 584 re-decomposed that surface along the breadth axis: the read-query and live-control and prompt clusters moved to dedicated collaborators, and the read surface was segregated into a narrow `AgentRunQueries` super-interface (`AgentService extends AgentRunQueries`) so read-only consumers can depend on it alone. `AgentLoopService` is now the `runAgent` driver plus thin delegating overrides.

| Collaborator | Owns |
|---|---|
| `AgentStepRunner` | one loop iteration — per-iteration tool selection, dispatch orchestration, handoff, virtual tools |
| `AgentLlmCaller` | the LLM round-trip: `callLlmWithTools` (one entry point — sampling is always an argument, never resolved implicitly), the per-call completion cap (`AgentContextBudgets.forCall`), the `EMPTY_RESPONSE` retry (attempt 2 suppresses thinking), `finish_reason` capture, and leaked-tool-call recovery |
| `AgentToolDispatcher` | tool execution + policy + `handleSafetyGate` approval gate (the sole direct `OperationDispatcher.dispatch` site) |
| `AgentContextCompressor` | tool-result truncation/compression (Layer-2 cut at `toolResultCapChars()`, derived per call) |
| `AgentEventTracing` | `TraceContext` / OTel span decoration |
| `AgentHandoff` | multi-agent handoff tools + research-brief history pruning |
| `AgentTurnPolicy` | PRIMARY→DECIDING state machine + force-tool-call decisions |
| `AgentSessionFinalizer` | end-of-run telemetry + health-event + run-store reason emit |
| `AgentPromptComposer` | system-prompt assembly — `DEFAULT_SYSTEM_PROMPT`, the indexed-root preamble, and the condition-recovery context (tempdoc 584) |
| `AgentSessionRegistry` | the live-run session map + per-run control surface — approve/reject, cancel, autonomy dial, steering interject, budget/context-gate resolution, attach, virtual-tool completion (tempdoc 584) |
| `AgentRunQueryService` | the read-time query/projection surface behind the `AgentRunQueries` interface — session snapshots/lists, event/thread/lifecycle/presence projections over `AgentRunStore`, operation history, and resume (tempdoc 584) |

### Recovering a tool call the model put in the wrong channel

A local model does not always put its tool call in the structured `tool_calls` channel. It leaks
into the assistant text as JSON, or — measured at 40 % of tool-planning turns on the standard 9B
profile (tempdoc 881) — into an unterminated *thinking* block as
`<tool_call><function=NAME><parameter=K>V</parameter></function></tool_call>`, which
`--reasoning-format deepseek` routes wholly to `reasoning_content` where llama-server's own parser
cannot reach it. `AgentLlmCaller` recovers such calls rather than discarding the turn, under two
deliberately different rules:

- **Text channel** — permissive: every recognised grammar is recovered and the span is *stripped*,
  because an unrecovered leak there renders as the answer.
- **Reasoning channel** — strict: only a span inside the model's own `<tool_call>` wrapper, only
  when the turn has no structured call and no text, only the LAST such wrapper, and nothing is
  stripped. Thinking routinely weighs calls it then retracts; the wrapper is the commit marker, the
  empty-turn gate means the only alternative is discarding the turn, and taking the last wrapper
  defers a batch by one iteration rather than executing something the model changed its mind about.

Recovery cannot widen the tool surface: names are checked against the list actually offered that
iteration, and a recovered call goes through `isAuthorizedThisIteration` and the safety gate like any
other. Arguments from the XML grammar are untyped and are coerced against the tool's declared JSON
schema. If a turn is still empty afterwards, the error names the runtime's `finish_reason` — it does
not guess at a cause.

The loop follows a tool-using ReAct-style flow:

```text
1. Build the default system prompt plus indexed-root context.
2. Call the online model with the current conversation and emitted tool definitions.
3. If the model returns final text, emit completion and stop.
4. If the model calls tools:
   a. Apply loop guards before execution.
   b. Require approval for write/destructive operations.
   c. Dispatch operations through the operation substrate.
   d. Append tool results to the conversation.
5. Stop on completion, cancellation, safety limits, budget exhaustion, or unsupported state.
```

### Run-honesty invariants

Two invariants bind how a run reports itself. Both are structural: they hold whatever the model
says, because the model's own text is never the thing that carries them.

**An involuntary terminal owes the run one synthesis attempt.** When the loop walks into a limit —
the token budget, or the iteration ceiling — it makes one final LLM call with no tools, instructing
the model to write a partial answer from what it already gathered and to disclose that the run was
cut short. `AgentLlmCaller` holds one shared obligation block for both, and a per-terminal sentence
naming the limit that actually fired: a run stopped by the step ceiling routinely has budget to
spare, so telling it (or the reader) that tokens stopped it is a specific false statement about the
remedy. The attempt is best-effort — a compact model can decline — so the fail-closed guarantee is
`AgentEvent.AgentDone.disposition`, which is decided by the call site and written independently of
the answer text. A *voluntary* terminal owes nothing: a cancelled run is deliberately answerless.

**Every layer that removes information leaves a mark, addressed to a named consumer.** A run has
two consumers reading two different artifacts — the MODEL reads the prompt, the READER reads the
wire and the persisted record — and a mark aimed at one is not a mark for the other. The layers and
their marks:

| Layer | What it removes | The mark it leaves | Consumer |
|---|---|---|---|
| Layer-2 per-result cut (`AgentContextCompressor.truncate`) | the tail of one tool output | `[... truncated, N chars omitted]` in the prompt; `outputCharsToModel` / `truncatedForModel` on `tool_exec_completed` | model + reader |
| Layer-3 strip (`stripSearchExcerpts`) | excerpt and read-page carrier lines | one `Elided:` line naming how much went and that re-calling the tool will not return it | model |
| Layer-3 compression (`compressToolOutput`) | most of an older tool message | `[compressed-tool-output originalChars=… keptChars=…]` | model |
| Compaction (`AgentSession.compactOlderTurns`) | whole messages | the dropped calls enter the inclusion ledger, so their sources report `dropped` | reader |
| A truncating terminal | the rest of the run | `AgentDone.disposition` | reader |

`tool_exec_completed.output` is deliberately the tool's WHOLE answer — the reader is not
context-bound — with `outputCharsToModel` beside it saying how much reached the prompt. Both keys
are absent when no emitter measured, and absent means *unknown*, never *nothing was truncated*.

Current defaults are intentionally modest:

| Constant / setting | Current behavior |
|--------------------|------------------|
| Conversation-shape `maxIterations` default | Omitted conversation-shape requests default to `1` in `ToolIteratingShapeRunner.parseRequest`; resume/fallback paths in `AgentLoopService` can use a larger internal limit. |
| Completion token cap | Derived per call by `AgentContextBudgets.forCall`: `min(cap, n_ctx / 4)`, where `cap` is `justsearch.agent.max_completion_tokens` when set and `1024` otherwise, floored at `256`. Unchanged at `n_ctx >= 4096`; smaller only at a window too small to afford `1024`. |
| Approval timeout | `300` seconds. |
| Tool result truncation | Derived per call: `ContextBudget.toolResultCapChars()` = `min(inputBudget / 4, 2048)` tokens as chars (2304 chars at `n_ctx` 4096, 8192 at 32768), floored at `100`. An explicit `justsearch.agent.max_tool_result_chars` overrides it verbatim; the default is `0` = derive. |
| Thinking control | Forced tool/commit turns use `SamplingParams.AGENT.withEnableThinking(false)` and the service strips `<think>` blocks when needed. The default prompt does not rely on a literal `/no_think` line. |

## Operation-Substrate Tool System

The current tool system is operation-based. Do not add new agent tools through the legacy registry path.

Key classes:

| Class | Role |
|-------|------|
| `OperationCatalog` | Canonical catalog of available operations and metadata. |
| `OperationDispatcher` | Dispatches operation calls to registered handlers. |
| `AgentToolEmitter` | Projects catalog operations into model-visible tool definitions. `offer(...)` is the one authority on *which* operations the model is shown; `emit(...)` is its wire projection. |
| `AgentToolsOperationCatalog` | Registers the built-in agent operations, in `app-agent` (`modules/app-agent/src/main/java/io/justsearch/agent/tools/AgentToolsOperationCatalog.java`), next to the tools it declares. |
| Operation handlers | The five knowledge/file agent tools (`core.search-index`, `core.read-document`, `core.browse-folders`, `core.ingest-files`, `core.file-operations`) are themselves `OperationHandler` implementations in `modules/app-agent/src/main/java/io/justsearch/agent/tools/`. Two agent operations — `core.remember` and `core.navigate-to-surface` — keep dedicated handlers under `modules/app-services/src/main/java/io/justsearch/app/services/registry/operations/handlers/`, alongside the non-agent operation handlers that also live there. |

Wire-name projection is deliberate. Dotted operation IDs such as `core.search-index` are projected to model-visible tool names such as `core_search_index`.

### The offering

The set of tools a run puts in front of the model — the *offering* — is produced in exactly one place, `AgentToolEmitter.offer(...)`, by filtering the composed catalog through executor tag, an audience allow-list (`USER`/`AGENT`), the caller's optional tool selection, and each operation's evaluated availability. `GET /api/chat/agent/tools` and the build-time registry snapshot are projections of that same call, not independent re-derivations, so the trust panel and the governance witness cannot disagree with what the model was sent.

Two properties are deliberate:

- **Availability filtering is live, and the reconciliation behind it no longer requires a request.** An operation may declare an availability expression over health conditions (`core.search-index` and `core.read-document` are offered only while the index is serving). Those conditions are reconciled on worker and inference capability transitions as well as on `/api/status`, so a client that never polls is no longer stuck with whatever the last request left behind. It is a second *trigger*, not full coverage: a condition whose only input is the Worker-reported operational view (rather than a capability transition) still moves on the next readiness snapshot, whenever that is taken.
- **Within a single-agent run the offering grows but never shrinks.** It is re-evaluated each iteration and adopted only when it gained a tool, so a subsystem that recovers mid-run becomes usable, while a tool the model has already been shown never vanishes underneath it. A multi-agent run (one carrying agent profiles) instead re-derives its list per *active profile*, because a handoff is meant to change which tools are on offer; monotonicity there would have to be per-profile and is not yet implemented. The asymmetry is intentional: an offered-but-broken tool returns an error the model can read and adapt to, whereas a withheld tool is not experienced as a missing capability at all — it is experienced as a reason to improvise.

Every offered tool is executable: the offering is a subset of the registered operation handlers, the workflow-runner routes, and the FE-published virtual tools.

Current built-in agent-facing tool names include:

| Tool | Safety | Purpose |
|------|--------|---------|
| `core_search_index` | Read-only | Search indexed knowledge. Accepts `path_prefix` to restrict results to a folder, given as `core_browse_folders` returns it (root-relative or absolute — the tool resolves a relative prefix against the indexed roots before validating it). |
| `core_read_document` | Read-only | Read one indexed document's extracted text, paged by character offset (`path`, `offset_chars`, `max_chars`). Served by the Worker's `FetchDocumentSlice`, so the readable universe is exactly the indexed corpus and the Head never opens the file. A page is capped below the per-tool-result context cap; the result names the span read, the document's total length when the Worker reports one, and the offset to continue from. One page per document is the declared default — at a small context window, paging one document to the end costs the steps the other documents needed. |
| `core_browse_folders` | Read-only | Discover indexed folders and paths. Optional `list_files`, `max_folders`, `max_files`. |
| `core_file_operations` | Destructive | Move, rename, copy, or create directories (`FileOperation.OpType`: MOVE / RENAME / MKDIR / COPY — there is no delete), as a batch with an explanation and a conflict strategy. Requires approval, and the batch is undoable. |
| `core_ingest_files` | Write | Request ingestion of files or folders. Takes an optional `collection` tag; omitted, a path inherits its containing indexed root's collection, or `mcp-ingest` when it is under no indexed root (tempdoc 811 C-2a). |
| `core_remember` | Write (low risk, no approval) | Persist a durable fact or user preference to the single-authority memory record, inspectable and forgettable via the Memory surface / `/api/memory`. |
| `core_navigate_to_surface` | Low risk, no approval | Open a named app surface for the user. Presentation-layer only; also exposed to the UI executor. |

Beyond the built-ins, the offering also carries anything else composed into the agent partition of the catalog: tools contributed by connected MCP servers, and declared workflows projected onto agent-callable operations (`core.<name>` becomes `core_workflow_<name>`). A projected workflow inherits the availability of the operations it composes, and one whose steps reference an operation the running install does not have is not projected at all — so a workflow is never offered as a tool the model cannot actually run.

Safety metadata lives with the operation definitions and handlers. The approval posture is the operation's `ConfirmStrategy`, not its read/write character: `core_ingest_files` and `core_file_operations` pause for explicit user approval before execution; every other tool above runs unattended.

**Offering is authorization.** The emitter withholds operations from the model's tool list for three reasons — audience (admin/operator surfaces), availability (a declared condition is firing), and the request's tool selection. Those filters are not presentation-only: `AgentStepRunner` resolves a model-named tool against the *offered* set, derived from the same `AgentToolEmitter.emit` call rather than recomputed, so a tool that was withheld is refused with a typed rejection instead of dispatched (tempdoc 875). The per-iteration narrowings that steer the model — the handoff-only DECIDING list, the Organizer's first-turn list — are deliberately *not* part of that authority set: steering nudges, authority binds.

### What an operation's declared policy actually decides

`OperationPolicy` is enforced, not advisory — that is the premise of
[ADR-0030](../decisions/0030-policy-on-operations-vs-mcp-hints.md)'s divergence from MCP's
"annotations are hints" discipline. Tempdoc 879 closed the gap between that claim and the code, so
each surviving axis now has a consumer whose behaviour the declaration changes:

| axis | what changes when you change the declaration |
|---|---|
| `risk` | the `(SourceTier × RiskTier)` lattice verdict, the agent gate's floors, and whether a durable allow-always grant may satisfy the gate at all — `HIGH` never can (tempdoc 875) |
| `confirm` | a floor on the agent gate. The lattice still decides the *baseline* verdict from source × risk × the autonomy dial (see `GateBehavior`'s javadoc); the declaration may only tighten it — so an operation declaring `Inline` or `Typed` can force a confirmation the dial would have auto-approved, but can never suppress one the lattice required. An engaged hard stop's `DENY` short-circuits before the floor applies. No `ExecutorTag.AGENT` operation may declare `Typed`: the agent authorization ceremony renders the operation id as the phrase and cannot carry a declared one, so a registry test fails the build rather than letting the phrase be silently substituted at runtime. |
| `audit` | `NONE` emits no operation-history record; `METADATA_ONLY` emits one. Advisory emission is independent of this and still fires either way |
| `retry` | whether the agent dispatcher may transparently re-issue the call, and how many times. The axis declares *permission* to replay (hence `RetryPolicy`'s refusal of `allowAutoRetry` without an `idempotencyKey`); the caller's loop supplies the timing |
| `requiredCapabilities` | the executor's capability gate and the derived availability expression |
| `undoSupported` / `inverseOperationRef` | the executor's undo path, and the reversibility signal that stops an irreversible MEDIUM write auto-firing under the `AUTO` dial. `undoSupported=false` is a fail-fast refusal *ahead of* the gate; `true` admits the reversal to the same lattice the forward operation met (tempdoc 875 §C.7) |
| `capabilityFamily` | which durable allow-always grants cover the operation — bounded by the risk ceiling above and by `DurableGrantScope`, so a family grant never reaches a `HIGH` member or an invocation whose path arguments leave the indexed roots (tempdoc 875) |
| `advisoryClass` | which typed advisory Resource the completion emits into |

`scripts/ci/check-policy-axis-liveness.mjs` fails the build if an axis loses its consumer, or if an
`Optional` axis is declared by no operation. There is no opt-out: an axis that cannot pass is wired
to a real consumer or deleted outright, because a declaration nothing can contradict reads as a
constraint while being a comment.

## Query Pre-Processing

Search operations can use backend query helpers before retrieval:

- `FilterNormalizationService` normalizes approximate filter values to indexed vocabulary where enabled.
- `QueryUnderstandingService` can extract soft `boostFilters` from natural-language queries when `JUSTSEARCH_QU_ENABLED=true`.

Explicit caller-provided filters and boost filters take precedence over inferred values.

## MCP Tool Surface

The production MCP server exposes four task-oriented tools:

| Tool | Purpose |
|------|---------|
| `justsearch_answer` | Primary QA tool over local indexed content. |
| `justsearch_search` | Search with filters, boost filters, facets, pagination, and excerpts. |
| `justsearch_ingest` | Index files or directories. |
| `justsearch_status` | Inspect index and ingestion health. |

ADR-0015 records the design rationale for a compact task-oriented MCP surface. Local JustSearch evaluation evidence and external prompting/tool-use research should be treated separately:

- Local evidence supports the current product decision for this repository and eval setup.
- External research is directional evidence for prompt/tool-surface design, not a direct proof that every future schema change will help.

Progressive disclosure is response-driven: `facets`, contextual `hints`, citations, filters, and full-document options guide follow-up calls without adding more top-level tools.

## Token Budget and Compression

`AgentSession` tracks token usage and emits budget events. Before additional model calls, the loop checks whether enough budget remains and can attempt a final synthesis from gathered tool results.

Older tool outputs may be compressed deterministically to keep useful context while reducing prompt size. Compression is extractive and preserves recent results according to configuration.

Important environment settings include:

| Setting | Purpose |
|---------|---------|
| `JUSTSEARCH_AGENT_CONTEXT_COMPRESSION_ENABLED` | Enables/disables tool-result compression. |
| `JUSTSEARCH_AGENT_CONTEXT_COMPRESSION_MIN_CHARS` | Minimum result size before compression is attempted. |
| `JUSTSEARCH_AGENT_CONTEXT_COMPRESSION_KEEP_LAST_RESULTS` | Number of newest tool results left uncompressed. |
| `JUSTSEARCH_AGENT_MAX_TOOL_RESULT_CHARS` | Maximum emitted tool result size. `0` (default) derives it from the live context window. |

## Loop Detection and Errors

The agent blocks repeated identical tool calls before execution and escalates after repeated blocked calls. Tool call identity is based on tool name and normalized JSON arguments.

Errors are classified with `AgentErrorCode` and `AgentErrorClass` so UI, SSE, persistence, and retry policy can make consistent decisions. Transient model/tool failures may retry with bounded backoff; policy, contract, cancellation, and permanent errors abort.

## Durability

Agent sessions are persisted by `AgentRunStore` as an append-only event log with checkpoints. Supported resume states include waiting for approval, ready for the model, and after a tool result. Unsupported states return `UNSUPPORTED_RESUME_STATE`.

Event persistence supports replay through the agent session event endpoint and schema upcasting for compatible older records.

## Agent Run Grounding (tempdoc 565 §3.A)

A grounded agent answer carries **one** citation authority: `AgentEvent.AgentSource` (a chunk-identified local passage — `parentDocId`, `chunkIndex`, `path`/`title`/`excerpt`, `startLine`/`endLine`). At end-of-run, `AgentSession.collectGroundingSources()` collects these from the run's executed search-tool results (the `searchResults` structured payload, keyed by chunk identity and deduped), and `AgentStepRunner.groundedDone()` attaches them — plus the per-sentence inline-citation links `AgentEvent.AgentSentenceCite` resolved by `AgentCitationResolver` (the *same* answer↔source matcher the RAG path uses) — to the terminal `AgentDone` event. The sources stand alone even when the matcher does not run; the inline marks are an enrichment layer on top, never a second authority.

Only **chunk-identified** hits are citable: a hit lacking `parentDocId` (a document-level hit, or a search over an index whose chunk-enrichment is not yet ready) is skipped, and grounding degrades to empty — observably (a `WARN` fires when search hits existed but none were citable, so an operational issue never masquerades as a dead feature). The FE renders this one list as the evidence rail + the collapsible "Sources · N" chips + the inline `[n]` marks (the "one tool-call render + one ordered run projection" governance in `governance/run-renderers.v1.json`); a reloaded conversation rehydrates the same grounding from the persisted record, so live and record renders cannot diverge.

`SearchTool` carries a separate, non-rendered `feedbackFeatures` projection for learning feedback.
Each entry pairs the path-oriented `docId` used by `AgentDone.sources` with the stable parent
`docUid` supplied by the Worker. `AgentDispositionWiring` resolves citations through that pair and
persists only UID-keyed dispositions; it never changes the visible source, citation, or tool-card
shape. A missing or ambiguous UID drops the best-effort feedback row rather than creating a new
path-keyed record.

**One run-STRUCTURE authority (tempdoc 565 §26).** The same one ordered run projection carries a typed `RunSegmentRef` facet — *which group/origin each timeline item belongs to* — computed by one `assignRunSegments` pass both the live and record projectors call. This completed the "a run is a run" unification at the run's *structure* (the facet the §15.C flatten dropped into an untyped escape hatch): a **workflow** run renders its node graph as labelled segments (the `node_started`/`node_completed` boundaries bracket each node's `node_output` content; the spine marks node boundaries), and a **background** run launched with a `conversationId` renders inline in that conversation's thread as an `origin=background` segment. The workflow shape is a *mode* of the one window, not a second surface (the `interaction-surface` gate makes a second one a build failure), reached through a **picker** that projects `/api/registry/workflows` rather than a hardcoded id. The **memory** surface split accordingly: the durable facts ("what it knows") stay the `core.memory-surface` peer, while the *activity* half ("what it did" — the presence inbox + the run-in-background launcher) folded into the retrospective drawer's Inbox tab. `RunSegmentRef` is *branded* (only `assignRunSegments` mints it) and the `run-renderers` register covers the segmentation pass, so a second run-structure renderer is caught the same way the grounding/answer leaves are.

**One DIRECTION (steering) authority — the proactive peer of Consent (tempdoc 565 §30).** Human-in-the-loop over a run has two axes: *Consent* (reactive — the agent proposes, the human authorizes; the mature `IntentGateEvaluator` lattice + ceremony + hard-stop) and *Direction* (proactive — the human sets/changes what the agent does). The latter used to be forked across the prompt, the autonomy dial, and the kill button, with the continuous case missing. §30 unifies it: the human's direction over a run is ONE control-intent channel — `initiate` (the composer prompt) · `set-posture` (the `watch/assist/auto` dial) · `interject` (a free-form *mid-run* steer) · `halt` (the stop) — and every run-control affordance dispatches through the one `dispatchRunControl` seam. The genuinely-new value is `interject`: the FE POSTs `/api/chat/agent/steer`, which queues a `volatile pendingInterject` on the live `AgentSession`; the agent loop *drains* it at the next step boundary (mirroring the `isCancelled` poll + the approval-gate mailbox), folds the text into the next LLM call as a system steering note, and emits a `directive_acknowledged` event the FE renders as a human-origin run-spine landmark + a "Your direction" chip. Consent is untouched (Direction owns the dial; the gate *consumes* its posture). The anti-drift is the `steering-surfaces` register + `check-steering-arbitration` gate: a run-control affordance that bypasses the seam — a second steer input, a hand-rolled stop — is a build failure. The run-level `halt` is the per-run `cancelSession`; the GLOBAL hard-stop stays a separate emergency circuit-breaker, not a per-run directive.

**One grounding-semantics authority, gate-locked (tempdoc 565 §15.A/§15.D.1/§15.J).** On the FE, the score→tier→grounding-class/label mapping lives once in `evidenceProjection.ts`; the tier is a *branded* `GroundingTier` only `evidenceTier` can mint (the typed seam), and every grounding surface (the rail chips, the inline-mark colouring, the hover label) derives from it — so one answer↔source similarity classifies identically everywhere. This is enforced, not just conventional: the `groundingSemantics` section of `governance/run-renderers.v1.json` + the `check-run-renderers.mjs` gate fail the build on (i) a tier symbol imported outside the registered consumer sites or (ii) a numeric-threshold re-derivation of a grounding class (`score >= 0.X ? 'grounded'`) anywhere but the authority. Combined with the build-pinned `AgentDone.sources/citations` carrier (`AgentEventPayloadConformanceTest`) and the answer-renderer/weave gate, this realises tempdoc 565 §6's "evidence-surface gate": a *second* grounding classifier is now unrepresentable by construction. The one residual that is genuinely not build-gateable — a *rendered* grounded answer that silently dropped its evidence — stays the runtime `WARN` above plus the deferred grounding-readiness signal, named honestly rather than faked as a gate.

## Action Lifecycle (tempdoc 550)

Every action an actor takes — whether the user clicked it, the agent proposed it, or a plugin requested it — flows through one spine: **one record of what was done, one judgement about whether it may, and one grant model for durable consent.** The principle: receipt, timeline, undo, trust-audit, and plan-preview are *projections* over one log, never ad-hoc re-joins; and a read-view that is plumbed but not mounted is a build failure, not a silent gap.

**One action-event log.** `ActionEvent` (in `app-observability`) is a sealed union — `Operation` / `Navigation` / `Gate` / `Grant` / `Effect` — with an explicit, deterministic id. `ActionEventStore` is the one authoritative store: id-keyed, idempotent (re-ingest on reload does not duplicate), and bounded. `ActionLedgerProjection.toWireRow` is the single projection layer; `/api/action-ledger` (snapshot) and `/api/action-ledger/stream` (SSE) are two reads of that one projection. The FE folds its local effects into the same log via `POST /api/action-ledger/events`, so the log spans the process boundary. Per-kind Outcome read-views fan into the one log on append, so they cannot diverge from it: `operation-history` keeps its own standalone REST snapshot (`GET /api/operation-history`), while the Navigation kind's standalone `GET /api/navigation-history` snapshot was torn down (tempdoc 689 — zero consumers once the FE moved to reading Navigation entries off the unified `GET /api/action-ledger` kind:'navigation'); `NavigationHistoryStore` itself is unchanged and still feeds `ActionLedgerProjection` in-process.

**One intent verdict.** `IntentGateEvaluator` (in `app-services`) computes `(sourceTier × riskTier) → gateBehavior`, the lattice, and the Global Hard-Stop state into one `IntentVerdict`. The enforcement chokepoint (`OperationExecutorImpl.enforceTrustLattice`) and the Preview face (`/api/operations/{id}/preview`) read the *same* evaluator instance — the preview is the structural-prediction read of the one verdict (no args/token; args-bound capsule verification stays enforcement-only). A consumer cannot disagree with enforcement because there is one computation. **Undoing is dispatching.** A reversal is an operation and inherits the risk class of its forward form, so `OperationExecutorImpl.undo` runs the same chokepoint over the reversal's own canonical arguments (`OperationDispatcher.undoArguments`, `{"executionId":…}`) — same lattice cell, same risk ceiling on durable grants, same args-bound capsule. Consequences that follow from that and are not softened: the forward invocation's capsule does not authorize the reversal (capsules are args-bound), a standing grant does not cover it (a reversal names no path, so `DurableGrantScope` cannot prove containment and fails closed), and an engaged hard stop denies it. Before tempdoc 875 §C.7 this path checked `undoSupported` and the capability set only, so the reverse of a HIGH-risk write dispatched with no gate at all — an agent that could not perform an action could still undo one.

The process loads one `OperationAuthority` before launching asynchronous index
startup. It owns the durable grant store, capsule service, hard stop, evaluator and
indexed-root scope. The existing `WatchedRootsState` loads from the configured data
directory and supplies both that scope and the later `KnowledgeClient`/root lifecycle
operations. API composition consumes these same objects and attaches audit sinks.
A failed roots migration or corrupt roots/grants file stops this preload; client
construction does not read a second roots snapshot.

Prepared filesystem grant scope reads the strict frozen root plan. Every frozen path
must resolve to a real path inside a current watched root; missing paths and link
escapes refuse coverage. The scope takes one current roots snapshot per decision.
The existing preparation codec and resolver validate invocation identity, while
the trusted producer owns the original input-to-plan mapping.

**Recorded authorization evidence.** After the shared lattice authorizes a dispatch,
acceptance replaces the caller's grant reference with a versioned server-selected
basis in the operations row: structural AUTO, one-time capsule, exact operation
grant, or exact family grant. This reference is evidence for later revalidation;
it is never authority supplied by a request header. Ungated test wiring clears the
reference. Prepared retries retain their frozen context and provenance for the gate;
acceptance changes only the grant reference in that context. The exact-grant query
refuses substitution when the selected entry was revoked, even if another grant
could cover a fresh invocation. The shared authority also supplies a pure recorded
ingestion decision using its canonical operation catalogs: invalid binding, current
scope or exact-basis authority refuses before generation/capability readiness can
wait. A capsule never authorizes restart. This decision writes nothing and grants
no queue permission; recorded ingestion still requires its pre-poll authority
integration before activation.

Durable grant writes serialize a candidate snapshot and force its atomic file
replacement before publishing the new immutable set to authorization readers.
Failed issuance or revocation throws and leaves the last committed file and view
unchanged. Audit callbacks run after publication and outside the mutation lock;
they describe successful changes and are not a source of current grant authority.

**One grant model.** A `Grant` is a caveat-bearing, attenuable, revocable token. Two members exist: the single-use, args-bound, short-TTL **consent capsule** (`ConsentCapsuleService`, an HMAC token minted on user approval) and the **durable allow-always grant** (`DurableGrantStore`, keyed `(operationId, sourceTier)` or `(capabilityFamily, sourceTier)`). A durable grant carries two caveats, because it is a standing consent and may only cover invocations the user could foresee when granting it (tempdoc 875): a **risk ceiling** — it never satisfies a gate on a HIGH-risk operation, so destructive work always costs a fresh args-bound gesture, which is the same floor `IntentGateEvaluator.agentGate` already applies on the issuance side; and an **argument scope** (`DurableGrantScope`) — it never covers an invocation whose path arguments reach outside the indexed roots, so an out-of-root ingest still happens, but behind a confirm that names the path rather than behind a blanket grant. Both caveats are enforced at the one durable short-circuit in `OperationExecutorImpl.enforceTrustLattice`, the only place that knows a confirmation was skipped; the `file-operations` capability family (tempdoc 560 §28) is unchanged and still auto-approves its MEDIUM member. The autonomy dial is the *issuance policy* (which grants auto-issue per source×risk), and the **Global Hard Stop** is a *global revocation* over all non-user (UNTRUSTED) grants — a user-mediated approval survives an emergency stop. Grant lifecycle is recorded as `Grant` ActionEvents → one audit, one revocation path, one ceremony (`<jf-authorization-host>` on the FE). That one ceremony posts its verdict to **one** backend endpoint — `POST /api/chat/{approve,reject}` (tempdoc 565 §15.C) — which dispatches the agent tool-call gate (`AgentSession.approvalGates`, keyed by `sessionId`+`callId`) → the workflow GateStep/ToolStep gate (`WorkflowGateRegistry`, keyed by `callId`) → 404. "A run is a run" all the way down: the FE no longer branches the approval URL by run shape, and the forked `/api/chat/agent/{approve,reject}` + `/api/chat/workflow/{approve,reject}` routes were retired. The run-substrate differences that remain (session cancel/resume, the autonomy dial) are legitimately agent-only — workflows are stateless/deterministic — so the unification stops at the genuinely-shared *approval* concept.

**Structural verification.** Per thesis V, a substrate-shipping change is best treated as "done" only with an independent reviewer (≠ implementer) and live verification, not just compile + unit tests. This was briefly encoded as the `independent-review` discipline gate, since retired (tempdoc 530 §Remediation; the audit-dependent gates were judged not worth their cost) — it remains recommended honor-system practice rather than gate-enforced.

## Prompt Design

The default system prompt is kept compact and operational:

- Search before answering factual questions about indexed content.
- Use folder browsing when path discovery is needed, not as a mandatory first step.
- Treat paths as absolute.
- Do not retry the same failed tool call with the same arguments.
- Respect approval boundaries for write/destructive actions.
- Use indexed-root context appended at runtime.

Prompt changes should be evaluated against current tool behavior. Expanding the prompt with long implementation detail is usually worse than routing the agent to current canonical docs and compact skills.

## Key Modules

| Module | Purpose |
|--------|---------|
| `modules/app-agent-api` | Public agent API, events, error types, trace context, and operation-facing contracts. |
| `modules/app-agent` | Agent loop, session state, retry policy, run store, telemetry, and prompt assembly. |
| `modules/app-services` | Operation catalog composition/dispatch and non-agent operation handlers, plus dedicated handlers for two agent operations (`core.remember`, `core.navigate-to-surface`). The agent-tool catalog and the five knowledge/file agent tools themselves live in `modules/app-agent`. |
| `modules/app-inference` | Online AI runtime lifecycle used by the agent. |
| `modules/ui` | HTTP/SSE agent routes. |

## Safe Extension Path

To add or change an agent capability:

1. Add or update the operation entry (including its `OperationPolicy` — risk tier, confirm strategy, audit policy) and its `OperationHandler` implementation together. For a new knowledge/file-shaped agent tool, these are the same file pair in the same package, `modules/app-agent/src/main/java/io/justsearch/agent/tools/`. When the work isn't tool-shaped — as `core.remember` and `core.navigate-to-surface` show — the operation entry stays in that catalog but the handler instead goes under `modules/app-services/src/main/java/io/justsearch/app/services/registry/operations/handlers/`.
2. Confirm `AgentToolEmitter` projects the intended model-visible wire name and schema; `AgentToolCatalogBaselineTest` (`modules/app-services/src/test/java/io/justsearch/app/services/registry/emitter/AgentToolCatalogBaselineTest.java`) pins the real catalog's projection against a checked-in baseline (`modules/app-services/src/test/resources/agent-tools-wire-baseline.json`), so a deliberate schema or policy change requires updating that baseline.
3. Register any REST endpoint through route classes in `modules/ui` when the capability also needs HTTP exposure.
4. Update canonical docs and generated skills after code behavior is verified.
