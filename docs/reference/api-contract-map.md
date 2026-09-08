---
title: API Contract Map
type: reference
status: stable
description: "HTTP and in-process port contract sources, error sanitization."
---

# API Contract Map

This doc answers: "Where is the source of truth for JustSearch API schemas and stability rules?"

## HTTP (Local API)

### Route contract policy and SDK projection

`modules/ui/src/main/java/io/justsearch/ui/api/RouteContractPolicy.java` is the declarative route
contract overlay keyed by HTTP method and route pattern. It records response schemas and status
codes, stability, optional lifecycle metadata, and marks the small public subset eligible for
generated SDKs with a stable operation id. Route-manifest schema `2.0`, the full OpenAPI document,
the SDK OpenAPI document, and HTTP lifecycle headers all project from that one policy. Security
metadata is derived from `ApiSecurityFilters`, not copied into a parallel allowlist.

`SdkOpenApiProjection` applies that policy to routes discovered from the real `RuntimeApiRoutes`
and lifecycle route registrars. Its committed, byte-stable output is
`packages/runtime-client/openapi/runtime-client.openapi.json`; tests reject duplicate operation
ids, policy rows with no registered route, public routes without complete response mappings, and
internal routes leaking into the SDK. Canonical response schemas live in `SSOT/schemas/` and are
served by `SchemaController` as well as embedded into the SDK projection.

The projection currently contains exactly six read-only operations: runtime manifest and mirror,
readiness, liveness, health, and status. See [Runtime Contract](runtime-contract.md#generated-node-client)
for package scope and regeneration commands.

### Lifecycle schema v1 (minimum stable subset)

**Source of truth:** `modules/ui/src/test/java/io/justsearch/ui/api/LifecycleContractTest.java`

> This subset is one of the three **Runtime Contract** public surfaces (tempdoc 654). Which
> surfaces are promised (public-contract) vs reference-client vs internal, plus the version
> matrix and stability policy, is defined in [Runtime Contract](runtime-contract.md).

This contract test defines the minimum stable subset shared by:

- `GET /api/health` (lifecycle gate; HTTP `200` for `READY|DEGRADED`, `503` otherwise)
- `GET /api/status` (includes the minimum subset plus additional fields)

Minimum stable fields:

- `schema_version` (must be `1`)
- `observed_at` (ISO-8601 timestamp)
- `lifecycle.state`
- `lifecycle.reason_code` (optional; allowlisted by `io.justsearch.app.api.lifecycle.LifecycleReasonCode`)
- `components.{head,worker,inference}.state`
- `components.{head,worker,inference}.reason_code` (optional; allowlisted)

Minimal example (schema v1):

```json
{
  "schema_version": 1,
  "observed_at": "2026-01-18T00:00:00Z",
  "lifecycle": { "state": "READY" },
  "components": {
    "head": { "state": "UP" },
    "worker": { "state": "READY" },
    "inference": { "state": "DEGRADED" }
  }
}
```

### `/api/status` extended fields

**Canonical field map:** `docs/explanation/08-observability.md` (section "Status as the primary health signal (`/api/status`)")

**Proto structure (post tempdoc 341):** `StatusResponse` uses 10 nested sub-messages instead of flat fields:
`CoreStatus`, `FailureStatus`, `MigrationStatus`, `CompatibilityStatus`, `QueueDbHealth`,
`EnrichmentCoverage`, `GpuDiagnostics`, `VectorQuantization`, `TelemetryStatus`, and
`commit_user_data` map. Each sub-message owns its own field number space.

**Search config (post tempdoc 343):** `StatusResponse` includes `SearchConfig` (field 11) with
the active search pipeline configuration: `chunk_aware_enabled`, CC weights (sparse/dense/splade),
branch weights (whole/chunk), `branch_chunk_min_weight_multiplier`, `title_boost`, and the retired
always-zero `entity_boost` compatibility tombstone,
and `query_classification_enabled`. jseval snapshots this at run start for eval provenance.

**Encoder profiling (post tempdoc 357):** `EnrichmentCoverage` includes `EncoderProfile` sub-messages
(field 11) for embed, splade, and ner. Each profile contains ORT call counts, sub-phase timing
totals (tokenize, tensor, ort, extract/postProcess), and latency percentiles (p50/p95/p99).
Data is always available when `--pipeline` is used — no separate `--profile` flag needed.

**Health-event evidence (post tempdoc 419 C3 V1):** `/api/status` exposes additional fields used
by the frontend's `deriveHealthEvents` taxonomy for evidence-rich rendering when a HealthEvent
fires. The pattern is named-question, not generic-dashboard (419 C3 explicit non-goal).
- `worker.core.recentJobQueueDepth: long[]` — 30-min RRD trend of `worker.job_queue.depth`
  (curated metric). Backs the sparkline next to firing `index-throughput-stalled` /
  `index-throughput-degraded` events. Empty array when the worker-side RRD store hasn't
  accumulated data in the window yet.
- `gpu.recentUtilizationPercent: double[]`, `gpu.recentMemoryUtilizationPercent: double[]` —
  30-min trends of the curated GPU gauges from the head-side RRD store. Consumed by V2's
  `gpu-saturated` event sparkline.
- `telemetryHealth.{flushFailureCount, gaugeCallbackFailureCount}: long` — counters from
  `TelemetryHealthState.snapshot()`.

**V2 event surfaces (post tempdoc 419 C3 V2, 2026-04-28):**
- `worker.core.recentDocsPerSec: double[]` — 30-min trend of
  `worker.documents.indexed.rate_per_sec` (curated metric, computed by
  `OperationalMetrics.ThroughputMonitor`). Complementary sparkline next to V1's
  `recentJobQueueDepth` for the same firing throughput events: depth answers "is backlog
  draining," rate answers "is indexer making progress."
- `readiness.components.telemetry: ReadinessComponentView` — backed by the shared
  `TelemetryHealthClassifier` static helper consumed by both `/api/telemetry/health` and the
  readiness envelope (single source of truth). DEGRADED with reason
  `telemetry.metrics.{stale,high_failure_rate,disk_space_low}`. Frontend
  `deriveHealthEvents.ts` emits the `telemetry-degraded` event. New composite `"telemetry"`
  keeps subsystem hiccups out of `aiFeatures`.
- `readiness.components.gpu: ReadinessComponentView` — backed by head-side
  `GpuSaturationMonitor` (180s rolling window mirroring `ThroughputMonitor`) fed by a
  daemon-thread `GpuSaturationSampler` (15s cadence; short-circuits on
  NVML-unavailable). DEGRADED with reason `gpu.saturated` when sustained > 80% utilization
  with the activity gate (LLM queueDepth + processingJobsCount + GPL_RUNNING +
  onlineAi.isAvailable()) at 0. Frontend `deriveHealthEvents.ts` emits `gpu-saturated`;
  the existing `gpu.recentUtilizationPercent` sparkline renders next to it.

**Corpus counts — two distinct numbers (post tempdoc 811 C-4, 2026-08-06):**
- `worker.core.indexedDocuments: long` — every non-chunk document in the index (total docs minus
  chunk docs). Describes the INDEX. Consumed by Health (`core.files`), jseval readiness, and
  sandbox evidence; its meaning is unchanged and must stay unchanged.
- `worker.core.searchableDocuments: long` — the population a DEFAULT-scope search can actually
  return: `indexedDocuments` minus the collections the default scope excludes (today exactly
  `agent-history`). Derived in `IndexStatusOps#countDefaultScopeDocs` from
  `QueryFilterBuilder.buildFilterQueryOnly(null)` — the production default-scope filter itself, so
  it cannot drift from what search does. Bundled help docs and `mcp-ingest` documents ARE counted:
  a default search returns them. **Any user-facing "Searching N documents" string must read this
  field, not `indexedDocuments`** — the latter counts a corpus the user can neither see nor
  enumerate. A consumer must treat an ABSENT field (older backend) as "fall back to
  `indexedDocuments`" and a reported `0` as a real value.

**Enrichment completeness (post tempdoc 821 §3-C3, 2026-08-13):** the coverage percentages under
`worker.enrichment` divide by every document, so a stage that silently lost a *sub-population*
reads the same as a stage with nothing to do. Four additive fields close that:
- `worker.enrichment.completeness: StageCompletenessView[]` — one entry per stage
  (`stageId` ∈ `embed`|`splade`|`ner`|`chunk_embed`) with `{expected, present, missing, failed}`,
  plus an honesty `tier`: **`ARTIFACT`** when `present` counted the artifact itself (a lying status
  field cannot inflate it) and **`STATUS`** when only the bookkeeping field was countable — SPLADE's
  feature field is `docValues:false` and NER writes no per-document artifact, so those two declare
  the weaker tier rather than implying a verification they cannot perform. `expected` is scoped to
  the documents that carry the stage's status field (absent = not applicable), so `present` and
  `failed` are drawn from the same denominator; `missing` is `expected - present - failed` floored
  at 0. Derived in `IndexCountOps` (adapters-lucene) — a projection over counts the worker already
  holds, not a second authority.
- `worker.enrichment.failedNerCount: long` — NER reported only pending/completed; the other three
  stages have carried a failure count since 354, so a stalled NER sub-population was invisible here.
- `worker.enrichment.chunkMinChars: int`, `worker.enrichment.vectorReadyPercent: double` — the two
  thresholds the auditor OWNS, published so off-process oracles read them instead of mirroring the
  Java constants. jseval's chunk-completeness guard consumes `chunkMinChars` and its local 2000-char
  mirror is deleted (`docs/reference/jseval-pipeline-reference.md` §Chunk-completeness validity
  guard). A consumer must treat a non-positive/absent `chunkMinChars` (older backend — proto3
  scalars arrive as `0`, never absent) as "cannot evaluate", not as a licence to guess.

Each metric-backed field declares `surfacedAt(StatusEndpoint, fieldName)` on its
`MetricDefinition`; `MetricSurfaceContractTest` (worker-services) and
`HeadMetricSurfaceContractTest` (app-services) fail CI if the catalog declaration drifts
from the API record. Pattern: `docs/explanation/08-observability.md` "Health explanations".

**Java view:** `WorkerOperationalView` is decomposed into 11 sub-records mirroring the proto
structure. Callers access grouped data via sub-records (e.g., `view.enrichment().embeddingCoveragePercent()`).
Both grouped sub-objects and flat `@JsonUnwrapped` fields were previously emitted in JSON for
backward compatibility; the flat fields have been removed (364 T3-A).

If you need to change `/api/status` semantics, update docs + contract tests together.

### Telemetry health schema v1

**Source of truth:** `modules/ui/src/test/java/io/justsearch/ui/api/TelemetryHealthContractTest.java`

`GET /api/telemetry/health` provides meta-observability of the telemetry stack:

- `schema_version` (must be `1`)
- `observed_at` (ISO-8601 timestamp)
- `state` (`READY|DEGRADED|ERROR`)
- `reason_code` (optional; allowlisted in `LifecycleReasonCode`: `telemetry.*`)
- `counters` (export success/failure counts)
- `rates` (success rates 0.0-1.0)
- `timestamps` (last successful operation times)

HTTP semantics: `200` for any state (meta-endpoint reports its own health, not a lifecycle gate).

### Governance state API — REMOVED (tempdoc 930 §19.3 F9(d))

`GET /api/governance/state` and the governance dashboard surface it fed (`GovernanceView`,
`GovernanceStateController`, `scripts/governance/lib/dashboard.mjs` and the `governance-state.json`
resource it emitted) are removed. The discipline-gate kernel itself is unaffected — its verdicts are
read from the SARIF report (`tmp/governance-report.sarif`) and the gate runner's console output, not
from an HTTP surface. Do not re-add the route.

### Boot phases API (tempdoc 541)

**Source of truth:** `modules/ui/src/main/java/io/justsearch/ui/api/routes/BootRoutes.java` + FE consumer `modules/ui-web/src/shell-v0/components/BootPhasesPanel.ts`.

`GET /api/boot/phases?process={head|worker|brain}` — Composition-substrate boot trace. Default discriminator is `head` if omitted.

**Head envelope** (200 once HeadAssembly construction completes):

```json
{ "boot": {
    "process": "head",
    "bootStartedAtMs": ..., "bootCompletedAtMs": ..., "totalDurationMs": ...,
    "phases": [
      { "name": "infra|capability|service|substrate|orchestration|agent-tools-registration",
        "eagerness": "EAGER|LAZY",
        "startedAtMs": ..., "completedAtMs": ..., "durationMs": ...,
        "outcome": "READY|DEGRADED|FAILED|PENDING",
        "reasonCode": "<typed reason or null>",
        "spanId": "<OTel span id or null>" },
      ...
    ],
    "rebuilds": [ { ...PhaseRecord shape... } ],
    "rebuildHistoryCapacity": 20,
    "rebuildHistoryTotal": <long>
  } }
```

- `phases` is the sealed once-per-process trace; `agent-tools-registration` is the LAZY entry that transitions `PENDING → READY/resolved` once Worker connects (synthesized at render time from `Memoized.isResolved()`; the sealed `BootTrace` is not mutated).
- `rebuilds` is the post-boot ring buffer (capacity 20) of `worker-connect` events with READY/DEGRADED outcome reflecting current `CapabilityHealth`.

**Brain envelope** (200, co-resident projection from Head):

```json
{ "boot": {
    "process": "brain",
    "projection": true,
    "projectionNote": "Brain is co-resident with Head; this trace is projected from the ILM-construction window inside Head's ServicePhase. When Brain splits to its own JVM, projection becomes false.",
    "bootStartedAtMs": ..., "bootCompletedAtMs": ..., "totalDurationMs": ...,
    "phases": [
      { "name": "ilm-construction", "outcome": "READY|DEGRADED", "reasonCode": "inference.not_configured" if Degraded, ... }
    ] } }
```

`503` if HeadAssembly is still in pre-ServicePhase territory (BrainAssembly null).

**Worker envelope**: returns `501 NOT_SUPPORTED` (reason: WorkerAssembly is the future tempdoc 546). Invalid discriminator returns `400 INVALID_REQUEST`.

### Operation Substrate API (tempdoc 429)

**Source of truth:**

- `modules/ui/src/main/java/io/justsearch/ui/api/RegistryController.java` (Operation/Resource/Prompt catalog endpoints)
- `modules/ui/src/main/java/io/justsearch/ui/api/CapabilitiesStreamController.java` (capability change SSE)
- `modules/app-observability/src/main/java/io/justsearch/app/observability/CapabilitiesController.java` (LSP-shape handshake)
- `modules/app-agent-api/src/main/java/io/justsearch/agent/api/registry/Operation.java` (Operation primitive record)
- `modules/app-services/src/main/java/io/justsearch/app/services/registry/operations/CoreOperationCatalog.java` (admin seed catalog)
- `modules/app-agent/src/main/java/io/justsearch/agent/tools/AgentToolsOperationCatalog.java` (agent-tool catalog)

Substrate endpoints:

- `GET /infra/capabilities` — LSP-shape capability handshake. Returns `serverCapabilities` declaring the three primitive types (Operation/Resource/Prompt) with `dynamicRegistration`, `messageCatalogUrl`, `endpoint`, and `current` schema version. Includes monotonic `catalogVersion` (long) + `protocolVersion: "1.0"` and per-sub-API `contractVersions` (per tempdoc 521-followup §γ2/§ε1, `host.selection: "1.0"`, `host.ai: "1.0"`, plus all other `host.*` sub-interfaces). Existing fields (`schema_versions`, `prompt_templates`, `plugins`, `source`) preserved (additive change). Returns 503 if `appFacadeBootstrap.capabilitiesHandler()` hasn't completed initialization yet.
- `GET /infra/capabilities/stream` (SSE) — Capability change stream. Initial `snapshot` event carries current `catalogVersion`; `capability_changed` events emit on broadcast; heartbeat ticks the FE's lastSeen every 30s. No replay buffer — disconnect/reconnect requires fresh snapshot.
- `GET /api/registry/operations` — Operation catalog (admin seeds + agent tools when knowledgeClient is wired). Returns `{$schema, schemaVersion, catalogVersion, namespace, primitive, entries[]}`. Each entry is a generated single-authority projection of the `UIOperationView` wire record (record → JSON Schema `operation-wire.v1.json` → {TS, Zod}, precise/required per tempdoc 560 §4c); `consumers` is a flat `{consumerId, audience}` list.
- `core.copy-diagnostic-summary` — Head-local, LOW-risk/NONE-confirmation, USER-audience operation. It returns one transient `summary` string built by `DiagnosticsService` from an explicit typed allowlist (build/runtime-contract versions, platform, lifecycle reason codes, safe GPU capability, and parseable crash timestamp/process/exception type). It has no Worker or Inference capability requirement and uses `METADATA_ONLY` audit policy, so operation history records identity/outcome but never the summary payload.
- `POST /api/undo/{id}` — Undo a reversible Operation dispatch. Body: `{executionId: string}`. Returns `OperationInvocationResponse`. Fails with `HANDLER_FAILURE` if `!op.policy().undoSupported()`. The `executionId` is the handler-opaque batch key returned in `OperationResult.executionId` from the original dispatch. Emits an `UNDONE` history entry on success. Per G157 (slice g157-suggested-action-primitive).
- `GET /api/registry/resources` — Resource catalog. Entries are a generated single-authority projection of the `UIResourceView` wire record (`resource.v1.json` → {TS, Zod}, precise/required; tempdoc 560 §4c), with the same flat `{consumerId, audience}` consumer shape. V1 ships core OBSERVABLE resources (health-events, runtime-context, indexing/failed-jobs, capabilities, etc.) — not empty. Tempdoc 560 §29 Phase 2: plugin-contributed Resources (from a TRUSTED plugin's `Installation`) are composed in and served here too.
- `GET /api/registry/prompts` — Prompt catalog. Tempdoc 560 §29 Phase 2: serves core + plugin-contributed Prompts (a TRUSTED plugin's `Installation` prompts are merged into the served catalog by `SubstrateGraphAssembler`); core prompts are empty in V1, so the catalog is plugin-only until a core prompt ships.
- `GET /api/registry/workflows` — Workflow catalog (tempdoc 565 §26.C — the run-window's workflow PICKER wire). Same envelope `{$schema?, schemaVersion, catalogVersion, namespace, primitive, entries[]}`; each entry is the lean picker projection from `UIWorkflowEmitter` (`{id, type:"workflow", presentation:{labelKey, descriptionKey}, audience, nodes:[{nodeId, kind}]}`) — deliberately lighter than the operations/resources wire (no PreciseWire/generated Zod; a picker needs no fail-closed parse boundary). The FE `WorkflowCatalogClient` does a light parse; the launcher projects this catalog instead of a hardcoded `WORKFLOW_ID`.
- `GET /api/registry/witness` — Run-tier witness OBSERVABILITY (tempdoc 560 §29 Phase 3; NOT a CI gate — the §5 witness gate stays deferred). Reads the LIVE composed `ContributionRegistry` (via `HeadAssembly.liveRegistry()`) and returns `{schemaVersion, namespace:"registry-witness", entries:[{kind, id, owner, buildWitnessed}]}`. Scope: every `operation` from all sources (core/agent/workflow/MCP/plugin) + plugin-contributed surfaces/resources/prompts/channels/shapes — core surfaces/resources live in their own catalogs (served above) and are NOT mirrored here. `buildWitnessed` is a meaningful runtime-only signal ONLY for the three kinds the build-time snapshot (`RegistrySnapshotExporter`) covers — `operation` / `resource` / `prompt`: a `false` there flags a runtime-composed contribution the snapshot is blind to (e.g. the `core_workflow_*_compose` workflow ops, MCP tools, plugin contributions). For `surface` / `diagnostic-channel` / `conversation-shape` the snapshot has no coverage, so `buildWitnessed` is ALWAYS `false` (a structural constant for those kinds, not a runtime-only indicator). Surfaced read-only in the Settings "Delivered contributions" panel.
- `GET /api/messages/registry-operation/{locale}` — i18n catalog for Operation primitive (label, description, confirm prose). Served with ETag + `Cache-Control: public, max-age=3600`.
- `GET /api/messages/registry-workflow/{locale}` — i18n catalog for Workflow labels/descriptions (tempdoc 565 §27.4; same `MessageCatalogController` namespace pattern). The picker falls back to a derived title when a key is absent.
- `GET /api/messages/registry-resource/{locale}` — i18n catalog for Resource primitive (empty in V1; well-formed response, no 404).
- `GET /api/messages/registry-prompt/{locale}` — i18n catalog for Prompt primitive (empty in V1).

Soft-fail discipline (per LSP 3.17): unknown client capability declarations are ignored; `serverCapabilities` is unchanged whether or not the client declares dynamic-registration intentions. Verified by `CapabilitiesSoftFailTest`.

Wire-format projection (per tempdoc 429 §F.21 C1): `AgentOperationEmitter` projects Operations into the OpenAI function-calling tools array consumed by the agent loop. Names are deterministic transliterations of `OperationId` via `OperationCatalog.toWireName(id)` (e.g., `core.search-index` → `core_search_index`); descriptions resolve via `registry-operation.en.properties`. The `OperationId` is the single identity for any invocable surface; the wire form is pure projection. `AgentOperationEmitterRegressionTest` pins the emitter's projection algorithm against a captured legacy baseline built from four hand-written stub operations (deep-equal). The **real** agent-tool catalog's projection — plus its declared policy, lineage, and executor tags — is separately pinned by `AgentToolCatalogBaselineTest` against `modules/app-services/src/test/resources/agent-tools-wire-baseline.json`.

### Agent Action Lifecycle API (tempdoc 550)

The five faces of one agent action — Authorize, Outcome, Preview — over one action-event log, one intent verdict, and one grant model. See `docs/explanation/22-agent-system-architecture.md` for the spine.

**Source of truth:**

- `modules/ui/src/main/java/io/justsearch/ui/api/LocalApiServer.java` (route registration, ~line 1814–1843)
- `modules/ui/src/main/java/io/justsearch/ui/api/ActionLedgerController.java` (the one log: snapshot + live SSE + FE-effect ingest)
- `modules/ui/src/main/java/io/justsearch/ui/api/OperationPreviewController.java` (Preview face)
- `modules/ui/src/main/java/io/justsearch/ui/api/AuthorizationController.java` (Authorize face: capsule mint + durable allow-always grant)
- `modules/ui/src/main/java/io/justsearch/ui/api/HardStopController.java` (Global Hard Stop operator control)
- `modules/ui/src/main/java/io/justsearch/ui/api/OperationHistoryController.java` (per-kind Outcome read-view; the sibling `GET /api/navigation-history` snapshot endpoint was torn down — tempdoc 689 — superseded by `GET /api/action-ledger` kind:'navigation', which reads the same `NavigationHistoryStore` in-process via `ActionLedgerProjection`)
- `modules/app-observability/src/main/java/io/justsearch/app/observability/ledger/{ActionEvent,ActionEventStore,ActionEventJournal,ActionLedgerProjection}.java` (the one log + its durable audit journal + projection)
- `modules/app-services/src/main/java/io/justsearch/app/services/intent/{IntentGateEvaluator,ConsentCapsuleService,DurableGrantStore,Grant}.java` (one verdict + one grant model)

Endpoints:

- `GET /api/action-ledger` — Snapshot of the one action-event log (Outcome face, Slice C1). Returns `{entries: ActionLedgerRow[]}` projected by `ActionLedgerProjection.toWireRow`. Each row: `id` (deterministic, stable across snapshot + stream), `kind` (`operation` | `navigation` | `gate` | `grant` | `effect` | `index`), `occurredAt`, plus kind-specific fields — operation: `operationId`/`outcome`/`executionId`; navigation: `targetSurface`/`sourceId`; gate: `operationId`/`disposition`/`gateBehavior`/`sourceTier`/`outcome`; grant: `grantId`/`action`/`subject`/`outcome`; effect: `effectKind`/`subject`; index: `pathHash`/`collection`/`state`/`outcome`/`attempts`/`errorMessage?`. Receipt, timeline, undo, and trust-audit are projections over this one feed, never re-joins.
  - **Retention (tempdoc 812 D1).** Two tiers with different guarantees. `grant`, `gate` and `operation` rows are **durable**: they are written synchronously to an append-only JSONL audit journal under `<dataDir>/audit/action-ledger.jsonl` (rotated at 4 MB × 8 generations, oldest dropped) as they fan into the log, so they survive a Head restart. `navigation`, `effect` and `index` rows are **ring-only ephemera** — process-lifetime telemetry, deliberately not journaled (the authoritative live indexing view is the indexing-jobs Resource, and a 5k-document ingest would otherwise write 5k audit lines). This endpoint serves the in-memory ring **∪** the journal's tail, deduped by `id`; before 812 the whole feed was ring-only and Activity started empty after every restart. The journal is a write-behind copy for audit reads — the per-kind stores stay authoritative (550:468's rejection of re-sourcing the rail stands). Deep history beyond the served tail is the journal files themselves; there is no cursor pagination (deliberately deferred, 812 §D3).
  - **Query params (all optional and additive — a request with none behaves exactly as before).** `?correlationId=<id>` (561 P-B1) keeps rows carrying that loop/session join key; `?originator=<user|agent|system>` keeps that attribution; `?kind=<kind>` (repeatable, 812 D3) keeps only the named kinds; `?limit=<n>` (812 D3) returns the **newest** `n` rows after filtering — default 500, capped at 2000, with an unparseable or non-positive value falling back to the default. Sources: `ActionLedgerController.handleGet` + `ActionEventJournal`.
- `GET /api/action-ledger/stream` (SSE) — Live read-view of the same projection (G3/G4/G5). Stream-only (no journal fold); rows dedup by `id`. "Snapshot" and "stream" are two reads of one projection, never two code paths. **Tempdoc 662: also reachable multiplexed via `/api/shell-events/stream` below** — the FE shell subscribes there by default; this dedicated endpoint stays live for direct/tooling consumers.
- `POST /api/action-ledger/events` — Process-spanning ingest (thesis I): the FE folds local effects into the ONE log. Idempotent by event `id` (re-ingest on reload does not duplicate). Body: an effect event (`id`, `effectKind`, `subject`, `occurredAt`).
- `GET /api/shell-events/stream` (SSE, tempdoc 662) — **The managed connection budget's multiplexer.** Aggregates 7 of the shell's previously-independent always-on streams onto ONE physical connection: `/api/intent/stream`, `/api/advisory/operation-completed/stream`, `/api/advisory/health-recoverable/stream`, `/api/advisory/authorization-pending/stream` (tempdoc 655's long-term design pass — a `REQUIRES_ACK`-classed advisory for a pending MCP/UI approval, complementing the direct ceremony dialog below with a discoverable inbox/toast signal for when the user wasn't looking), `/api/action-ledger/stream`, `/api/indexing-jobs/stream`, and (tempdoc 655) the pending-authorization announcement channel (`system:pending-authorizations` — routing info only: `pendingId`/`operationId`/`sourceTier`/`riskTier`/`gateBehavior`; no args-derived content, see `GET /api/authorizations/pending/{id}` above) — collapsing the browser's HTTP/1.1 ~6-per-host connection-pool consumption that previously starved the cheap `/api/status`/`/api/inference/status` polls under load (tempdoc 649). Every forwarded frame still carries its origin `streamId` (the universal `SseEnvelope` discriminator, unchanged); the FE `MultiplexedStream` demuxes by `streamId` to each consumer's existing reducer. Resume is a **bundle** of the per-channel `?since=<streamId:seq>` tokens (existing `ResumeTokenCodec`), comma-joined — no new resume protocol. The individual endpoints above stay live for non-shell consumers (tooling, direct API use); see `MultiplexedSseWriter.java` / `ShellEventsStreamController.java` (backend) and `streaming/MultiplexedStream.ts` (FE). Governed by `governance/live-channels.v1.json` + `check-live-channels.mjs` (the connection-budget register/gate).
- `GET /api/operations/{id}/preview` — Preview face (Slice F2). Returns evaluated `availability` (capability-derived, structural) + `risk` + the predicted gate behavior, computed from the SAME `IntentGateEvaluator` instance the enforcement chokepoint uses — including the live Global Hard Stop (S2/F1). Args-bound consent-capsule verification is correctly deferred to dispatch (the preview has no args/token), so the preview is the structural-prediction read of the one verdict. Unknown operation id → 404.
- `POST /api/authorizations/approve` — Authorize face (Slice A1). On a user approval of a backend-gated action, mints a single-use, args-bound consent capsule (the max-attenuated Grant). Body: `{pendingId: string, allowAlways?: boolean, execute?: boolean}`. When `allowAlways` is true (thesis IV), also records a durable allow-always grant in `DurableGrantStore` keyed `(operationId, sourceTier)`, so future invocations of that op from that tier auto-approve without re-prompting. Both grant lifecycles are recorded as `grant` ActionEvents (ISSUED/CONSUMED/REVOKED, GRANTED_ALWAYS/REVOKED) in the one log → one audit, one revocation path. Tempdoc 655: `execute: true` additionally dispatches the pending's operation server-side, immediately, using its own stored args — for an approval whose origin (e.g. an MCP tool call) never gave the browser the full args to replay itself.
- `GET /api/authorizations/pending/{id}` — Tempdoc 655 fix pass. Point-to-point fetch of a pending's decision content (`operationId`, `argsSummary`, `sourceTier`, `riskTier`, `gateBehavior`, `rationale`, and — tempdoc 655's long-term design pass — an optional `requestedBy`: the calling MCP client's self-reported `clientInfo.name`, present only when the gate fired via MCP and the client supplied it; display-only, never a trust input per ADR-0030's hint-vs-enforced-policy line) plus — tempdoc 807 item 3 — `expiresAt` (ISO-8601 UTC; pendings carry a 5-minute TTL and are refused after it, but until 807 no surface said when, so no client could tell the user how long the request stays valid and no sandbox round could induce or verify expiry) by id, non-mutating (`peek`, not `consume`). Exists because the pending-authorization SSE broadcast (below) deliberately carries none of this content — a privacy boundary (tempdoc 444b): args-derived content is scoped to a point-to-point response to the one human deciding that one action, never to a multicast channel. 404 for an unknown/expired/already-consumed id.
- `GET /api/agent/hard-stop` — Global Hard Stop state (E2). <!-- drift-allow:/api/agent -->
- `POST /api/agent/hard-stop` — Engage/disengage the Global Hard Stop. Engaging is a global revocation over all NON-USER (UNTRUSTED) grants — it revokes non-user capsules + durable grants and makes the lattice deny-all-non-user; a user-mediated approval (MEDIUM/TRUSTED) survives an emergency stop, matching the gate's hard-stop scope. <!-- drift-allow:/api/agent -->
- `GET /api/operation-history` + `GET /api/operation-history/stream` (SSE) — Operation Outcome read-view snapshot + append stream (Slice 444b).

`GET /api/navigation-history` (Slice F1) was removed (tempdoc 689 teardown): superseded by `GET /api/action-ledger` kind:'navigation' — the FE reads Navigation entries there now, and the backend `ActionLedgerProjection` still consumes `NavigationHistoryStore` in-process (the store itself is unchanged, only the standalone REST snapshot was torn down for having zero consumers).

### Agent API

**Source of truth:**

- `modules/ui/src/main/java/io/justsearch/ui/api/AgentRoutes.java` (route registration)
- `modules/ui/src/main/java/io/justsearch/ui/api/AgentController.java` (HTTP + SSE payload mapping)
- `modules/app-agent-api/src/main/java/io/justsearch/agent/api/AgentEvent.java` (event model)
- `modules/ui/src/test/java/io/justsearch/ui/api/AgentSseContractTest.java` (frozen SSE payload fields)

Agent endpoints:

> Consolidated under the `/api/chat` namespace in tempdoc 491. Run/SSE requests accept an optional
> `X-JustSearch-Audience` header (`USER` default | `PLUGIN`) and an optional `shapeId` body field
> (defaults to the agent run shape).

- `POST /api/chat/agent` (SSE stream; starts a run from `messages[]`, optional `tools[]`, `maxIterations`, `shapeId`)
- `POST /api/chat/approve` (approve a pending gate: `callId`, optional `sessionId`) — the ONE approval endpoint (tempdoc 565 §15.C). Dispatches the agent tool-call gate (`sessionId`+`callId`) → the workflow GateStep/ToolStep gate (`callId`) → 404. The forked `/api/chat/agent/{approve,reject}` + `/api/chat/workflow/{approve,reject}` routes were retired ("a run is a run" all the way down).
- `POST /api/chat/reject` (reject a pending gate: `callId`, optional `sessionId`, optional `reason`) — reject sibling of `/api/chat/approve` (same unified dispatch)
- `POST /api/chat/agent/autonomy` (set the live autonomy dial for a running session: `{sessionId, level: "watch"|"assist"|"auto"}`) — tempdoc 561 P-D; takes effect at the next gated tool call (the `set-posture` directive of the 565 §30 DIRECTION authority)
- `POST /api/chat/agent/steer` (queue a mid-run human steering directive: `{sessionId, text}`) — tempdoc 565 §30, the DIRECTION authority's `interject`: the agent loop drains it at the next step boundary and folds it into the next LLM call; echoed back as a `directive_acknowledged` SSE event. 404 if the session is unknown/finished
- `GET /api/chat/agent/tools` (envelope `{ tools, available }`; each tool: `name`, `description` (i18n key), `risk` (`low`/`medium`/`high`), `supportsUndo`, `parameterSchema`, `tier`, `provenance`, `kind`) — the tools the model is **offered**, i.e. `AgentToolEmitter.offer(...)`'s set (executor + audience + evaluated availability), not the raw catalog (tempdoc 876 §B.1)
- `POST /api/chat/agent/undo` (undo reversible tool execution: `toolName`, `executionId`)
- `GET /api/chat/agent/history?limit=<1..100>` (recent operation batches)
- `GET /api/chat/agent/history/{batchId}` (batch detail)
- `GET /api/chat/sessions` (list) · `GET /api/chat/sessions/last` (last snapshot)
- `GET /api/chat/sessions/{id}` (detail) · `GET /api/chat/sessions/{id}/events` (replay) · `GET /api/chat/sessions/{id}/transcript`
- `POST /api/chat/sessions/resume-last` · `POST /api/chat/sessions/{id}/resume` (SSE resume)
- `DELETE /api/chat/sessions/{id}` (cancel active session)

Interaction / memory surfaces (tempdoc 561 P-A/P-B + 565):

- `GET /api/thread/{conversationId}` — the ONE plane-neutral interaction record projected per conversation (561 P-A/P-B): `{conversationId, events[], lifecycles[]}`. Every agent/workflow/background run keyed to that conversation projects here (read-time over `AgentRunStore`; no second store). Tempdoc 565 §26.A — a workflow run's node boundaries (`node_started`/`node_completed` → PROGRESS) + per-node output (`node_output` → ASSISTANT_MESSAGE) and a background run's `origin=background` boundary markers ride this wire so the FE brackets them into run segments.
- `GET /api/presence?since=<ISO-8601>` — the cross-conversation background-run inbox: `{runs[]}` (the `AgentRunStore` projection filtered to `background=true`). Surfaced in the retrospective drawer's Inbox tab (565 §26.D).
- `POST /api/presence/run` — schedule a detached background agent run (write/destructive tools rejected without a watcher). Body `{prompt, conversationId?}` — tempdoc 565 §26.D: when `conversationId` is supplied the run JOINS that conversation's `/api/thread` history (rendering as a `background` segment) as well as the inbox.
- `POST /api/thread/{id}/events` — tempdoc S4b (Search Thread): the FE write path for a thread event kind that does not originate from the agent loop. Today only `kind: "SEARCH"` (a manually-triggered search action) is supported. Body `{kind: "SEARCH", query, mode, matchCount, resultCount, docIds[], executedAt?}`; response `{id}` (201). Persisted via a small dedicated `core.search-event`-shaped run (`AgentRunStore.appendSearchEvent`) that joins the conversation exactly like a workflow run — no new store.
- `GET /api/memory` · `POST /api/memory` · `DELETE /api/memory/{id}` — the durable learned-facts store ("what it knows"), the `core.memory-surface` peer surface (561 P-E). Distinct from "what it did" (the runs), which the §26.D fold moved to the thread/inbox.

`POST /api/chat/agent` SSE event types (unchanged by the 491 rename):

- `session_started`
- `progress`
- `chunk`
- `tool_call_proposed`
- `tool_call_pending`
- `tool_call_approved`
- `directive_acknowledged` (565 §30 — the agent loop drained a human steering directive at a step boundary; payload `{directiveText}`)
- `tool_exec_started`
- `tool_exec_completed` — optional `errorCode` + `retryable` carry the failure classification `AgentToolErrors` put on the `OperationResult` (tempdoc 877); both keys are ABSENT when the executor did not classify (a success, or a pre-877 emitter), so a consumer can tell "not classified" from "classified as not retryable"
- `tool_call_rejected`
- `budget_update` (`phase`, `tokensConsumed`, `tokensRemaining`)
- `done` (`finalResponse`, `iterationsUsed`, `toolCallsExecuted`, `totalTokensUsed`, optional `sources[]`, optional `citations[]`) — note: `toolCallsExecuted` counts tool calls from the primary agent only; sub-agent calls (via handoff) are not included in this count. **Grounding (tempdoc 565 §3.A):** `sources[]` is the one citation authority — each `AgentSource` is a chunk-identified local passage (`parentDocId`, `chunkIndex`, `path`, `title`, `excerpt`, `startLine`, `endLine`, `headingText`); `citations[]` are the per-sentence inline-mark links (`AgentSentenceCite`: `sentenceText`, `sourceIndex`, `similarity`), present only when the answer↔source matcher ran. Both are declared on the `core.agent-run` shape's `done` `EventDescriptor` (so the generated FE type is truthful — §13.8) and emitted by `AgentController`/`ToolIteratingShapeRunner`. Empty/absent ⇒ ungrounded answer.
- `error` (`error`, `errorCode`, `errorClass`, `retryable`, optional `retryAction`, optional `retryAttempt`)

Resume contract notes:

- Supported persisted resume states: `WAITING_APPROVAL`, `READY_FOR_LLM`, `AFTER_TOOL_RESULT`.
- Unsupported states return typed `UNSUPPORTED_RESUME_STATE` with remediation guidance.
- For resumed sessions, pending write/destructive actions require fresh approval.

### Settings API

**Source of truth:** `modules/ui/src/main/java/io/justsearch/ui/api/SettingsController.java`

`GET /api/settings/v2`:

- Returns the current settings object including a `settingsMode` field (`"read_write"` | `"in_memory"`) that signals whether saves will persist.

`POST /api/settings/v2`:

- Persists updated settings. Returns 409 `SETTINGS_READ_ONLY` when `settingsMode` is `in_memory` (eval mode) — saves are silently discarded in this mode without the 409.
- Shell mode writes include `X-JustSearch-UI-Mode-Intent: <client-id>:<sequence>`. The client ID and
  Web-Lock-allocated sequence are durable in origin storage, putting reloads and concurrent shell
  windows in one monotonic ordering domain. The server ignores only the `ui.mode` field of an older
  intent while still applying unrelated fields in that partial patch. The header is optional for
  compatibility.
- Contract test: `SettingsV2ContractTest` validates round-trip (save → load → read) with no field loss.

Source: tempdoc 368 (RC6).

### Retrieve-Context API

**Source of truth:**

- `modules/ui/src/main/java/io/justsearch/ui/api/RetrieveContextController.java`
- `modules/ipc-common/src/main/proto/indexing.proto` (`RetrieveContextRequest`)

`POST /api/knowledge/retrieve-context` — RAG context retrieval.

Request fields:

- `query` (required)
- `return_full_documents` (bool, proto field 23) — when true, skips chunk search and returns full document content (366)
- Filter fields: same as search (entity + metadata filters scoped via two-stage parent-doc pre-filter) (362)
- `filters.collection` (string array, proto field 25) — scope retrieval to Lucene collection tag(s), same wire key and semantics as the search endpoint's `filters.collection`. Non-empty = a positive include of exactly those collections; absent/empty = the default scope (the `agent-history` MUST_NOT exclusion of 585 D4b), never "match nothing". Applied on the chunk branch by `QueryFilterBuilder#buildChunkFilterQuery`, so a collection-only request does **not** route through the doc-level parent pre-filter (821 §3-C2)

Response includes:

- `context_sufficient: boolean` (in quality object) — LLM-assessed context sufficiency signal. Returns `null` on timeout/error/LLM unavailable (366)
- Chunk content, retrieval mode, quality metrics

Source: tempdoc 366.

### Chat / Conversation API

**Source of truth:**

- `modules/ui/src/main/java/io/justsearch/ui/api/routes/AiRoutes.java` (route registration)
- `modules/ui/src/main/java/io/justsearch/ui/api/ChatController.java` (generic substrate-driven handler)
- `modules/app-services/src/main/java/io/justsearch/app/services/conversation/ConversationEngine.java` (runtime orchestrator)
- `modules/app-services/src/main/java/io/justsearch/app/services/conversation/CoreConversationShapeCatalog.java` (shape registry)

All chat endpoints return SSE streams. Each routes through `ConversationEngine` via a registered `ConversationShape`.

Per-shape endpoints (static `shapeId`):

- `POST /api/chat/ask` — RAG Q&A (`core.rag-ask`). Body: `question`, optional `docIds[]`
- `POST /api/chat/free` — Plain persistent conversation (`core.free-chat`). Body: `prompt`, `sessionId`
- `POST /api/chat/extract` — Structured JSON output (`core.extract`). Body: `prompt`, `jsonSchema`
- `POST /api/chat/summarize` — Single-doc summary (`core.summarize`). Body: `docId`, optional `startChar`/`endChar`
- `POST /api/chat/batch-summarize` — Multi-doc batch (`core.batch-summarize`). Body: `docIds[]`
- `POST /api/chat/hierarchical-summarize` — Hierarchical multi-doc (`core.hierarchical-summarize`). Body: `docIds[]`
- `POST /api/chat/url-emit` — URL-emission chat (`core.navigate-chat`). Body: `prompt`, `sessionId`

Dynamic dispatch endpoint (reads `shapeId` from body):

- `POST /api/chat/dispatch` — Unified surface dispatch. Body: `shapeId` (required), plus shape-specific fields. Validates shape exists and is USER-audience. Used by `UnifiedChatView` for affordance-routed per-message shape selection.

Conversation management (slice 513 + 515):

- `GET /api/chat/conversations?shapeId=<id>&limit=<N>` — List recent sessions (most-recent first, capped at 100). Each row: `sessionId`, `shapeId`, `createdAtMs`, `lastActiveAtMs`, `messageCount`, `firstUserMessage`, optional `parentSessionId` + `branchPointMessageId` (slice 513 branch metadata), optional `title` + `titleSource` (`"user"` | `"auto"`, tempdoc 838). `title` is a sealed content field: it is omitted while the conversation store is encrypted and locked, exactly like `firstUserMessage`; `titleSource` is structural and stays present.
  - **Two records, one list (tempdoc 859 slice C).** The endpoint joins `ConversationStore` with the agent-run record, because a delegate run persists a whole conversation and no store row (the shape-driven dispatch appends no messages) — so before the join an agent conversation had a complete record, served in full by `GET /api/thread/{id}`, and no listing anywhere. `limit` applies **per store** and the merged list is re-sorted by `lastActiveAtMs` and re-limited, so the response never exceeds `limit`. `shapeId` filters both sides: a query naming any shape other than `core.agent-run` excludes run-backed rows entirely. A conversation present in both records is listed once, as its store row — membership is resolved by a direct store lookup per run-backed candidate, not against the limited window, so a mixed conversation whose last chat turn has aged out of that window is still listed as its (renameable) store row.
  - A **run-backed** row carries `storeBacked: false` (a conditional key — absent means store-backed, like `title`/`parentSessionId`) and **omits `messageCount`** (absent = not told: deriving one means projecting the conversation's whole event stream per row, per request). Its `sessionId` is a real conversation id; what it lacks is the store session that `rename` / `branch` / `context-floor` / `compact` / `exclude` write to.
- `GET /api/chat/conversations/{sessionId}/history` — Load message history. Response: `messages[{role, content, id, hash}]`; for branched sessions also includes `parentSessionId`, `branchPointMessageId`, and (slice 515 FIX-8) `parentFirstUserMessage` preview. `loadHistory` walks parent chain lazily.
  - It does **not** perform the two-store join the list above does (tempdoc 859 slice C): the unified transcript's one authority is `GET /api/thread/{id}`, which already merges the answer and action planes. A run-backed conversation therefore answers here with an empty message list, which is the true thing about it.
- `DELETE /api/chat/conversations/{sessionId}` — Delete a session. **409 Conflict** with `childSessionIds[]` body if the session has child branches (slice 515 FIX-3); the client must delete branches first or implement cascade deletion.
  - It also deletes the conversation's **agent runs** (tempdoc 859 slice C), which is what makes a run-backed row removable rather than a sidebar that only grows; the response carries `agentRunsDeleted`. Every run joined on that `conversationId` goes, whatever its shape — deleting a conversation destroys the conversation, and leaving other-shape runs alive would keep `GET /api/thread/{id}` serving content for a conversation the reader deleted. The store deletion runs first, so a 409 aborts before any run is destroyed, and the run deletion fails closed while the run store is sealed + locked.
- `POST /api/chat/conversations/{sessionId}/branch?fromMsgId=<id>` — Create a branch from an existing session at the given message id. Response: `{sessionId, parentSessionId, branchPointMessageId}`. **400** if `fromMsgId` doesn't exist in the parent's resolved history (slice 515 FIX-2). The branch carries no messages of its own; loadHistory resolves the parent prefix on every call.
- `POST /api/chat/conversations/{sessionId}/title` — Name a conversation durably (tempdoc 838). Body: `{title, source?}` where `source` is `"user"` (default) or `"auto"`. Response: `{ok, title, titleSource}` — `title` is what was STORED, trimmed and capped at 200 characters. **400** on a blank title (clearing is `DELETE`) or an unrecognised `source`; **404** when the session has no `meta.json` (a rename must not mint a zero-message conversation); **423** when the conversation store's data key is locked, via the global `KeyLockedException` mapping.
- `DELETE /api/chat/conversations/{sessionId}/title` — Clear the name (tempdoc 838). Idempotent: a conversation with no name, or no such conversation, is a no-op `{ok: true}`.

Header: `X-JustSearch-Audience` (optional; defaults to `USER`).

SSE event vocabulary varies per shape (see `modules/ui-web/src/api/generated/shape-handlers/` for typed event interfaces). Common events: `chunk`, `done`, `error`. Shape-specific events: `rag.meta`, `rag.citations`, `rag.citation_delta`, `rag.citation_matches`, `progress`, `navigate.url_extracted`, `navigate.url_dispatched`.

Source: slices 491 (substrate), 496 (FreeChat + Extract), 497 (dynamic dispatch).

### MCP Server API

**Source of truth:** `modules/ui/src/main/java/io/justsearch/ui/api/mcp/McpToolSurface.java`

**Transport:** Streamable HTTP at `/mcp` on the existing Javalin server (loopback-only) — `POST` carries the whole JSON-RPC surface, `DELETE` ends the session named by `Mcp-Session-Id`, and `GET` returns `405` with `Allow: POST, DELETE, OPTIONS` (no server-initiated SSE stream is offered; see [MCP production server](mcp-production-server.md#transport)). Protocol version `2025-11-25` (single-sourced in `io.justsearch.app.api.mcp.McpContractVersions`). No separate process. The `/mcp` endpoint + curated tool set is one of the three **Runtime Contract** public surfaces (tempdoc 654); `serverInfo.version` carries the BUILD version and `serverInfo._meta["io.justsearch/toolSurfaceVersion"]` the SemVer tool-surface version — see [Runtime Contract](runtime-contract.md).

`initialize` also advertises the experimental, versioned
`io.justsearch/tool-lifecycle` capability. A validated closed catalog projects deprecation data into
namespaced tool `_meta` plus a description fallback without changing standard annotations. The
production catalog is empty, so no current tool is deprecated and the tool-surface version does not
change.

6-tool curated surface (tempdoc 500, adapted from eval-validated 4-tool TS server in tempdoc 366):

| # | Tool | Purpose | Backend |
|---|------|---------|---------|
| 1 | `justsearch_answer` | RAG retrieval — assembled passages | `DocumentService.retrieveContext()` (in-process) |
| 2 | `justsearch_search` | Exploratory search with facets | `KnowledgeHttpApiAdapter.search()` |
| 3 | `justsearch_browse` | Folder structure exploration | `core.browse-folders` Operation |
| 4 | `justsearch_ingest` | File indexing (`paths[]`, optional `collection` — tempdoc 811 C-2a) | `core.ingest-files` Operation |
| 5 | `justsearch_status` | Index health + enrichment | `KnowledgeHttpApiAdapter.status()` |
| 6 | `justsearch_runtime_manifest` | Redacted runtime manifest for identity-aware caching | `RuntimeManifestPublisher` |

`justsearch_search`'s `structuredContent` evidence tier (projected by `McpEvidenceProjection`)
carries the same `appliedFilters` echo the REST response does — see
[Knowledge Search API](#knowledge-search-api) for its shape. It is present only when the request
carried filters, is emitted on both evidence overloads, and survives the delivery governor's
tail truncation. As on REST, it echoes the filters the request **sent**; since the tempdoc-811 D-1
null-scope fix a null/empty filter set runs the same code path as an explicit one, so the engine
applies the requested filters verbatim and the echo is not a claim about anything else.

MCP Prompts: 3 onboarding templates (`search_files`, `answer_question`, `index_folder`) with live system context.

MCP Resources: 4 proposed URIs (`justsearch://index/summary`, `roots`, `top-sources`, `top-entities`) + 9 catalog-driven resources.

Trust: MCP clients registered as `SourceTier.UNTRUSTED` in `CoreIntentSourceCatalog`. Trust lattice gates destructive ops.

**Legacy:** The old TypeScript server (`scripts/prod/justsearch-mcp/server.mjs`) is deprecated. Its eval-validated tool descriptions informed the Java handler's design.

Source: tempdoc 500, ADR-0015, tempdoc 366.

### Knowledge Search API

**Source of truth:**

- `modules/ui/src/main/java/io/justsearch/ui/api/KnowledgeSearchController.java` (HTTP request parsing + JSON response shaping)
- `modules/app-api/src/main/java/io/justsearch/app/api/knowledge/KnowledgeSearchRequest.java` (request DTO)
- `modules/app-api/src/main/java/io/justsearch/app/api/knowledge/KnowledgeSearchResponse.java` (response DTO)
- `modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeHttpApiAdapter.java` (DTO <-> proto mapping)
- `modules/ipc-common/src/main/proto/indexing.proto` (`SearchRequest`, `SearchResponse`)

`POST /api/knowledge/search` accepts a JSON body with required `query` and optional fields:

- `limit`, `mode`, `sort`, `cursor`
- `querySyntax` (or `query_syntax` alias) — since tempdoc 821 §P / register F-046, `lucene` is
  honoured on **every** retrieval path (previously only the sparse-only one; multi-leg legs escaped
  the operators and retrieved a SIMPLE parse), so a malformed `lucene` query now fails the request
  with HTTP `400` / `INVALID_REQUEST` (`KnowledgeClientException.Status.INVALID_ARGUMENT` from the
  index half) instead of being silently
  parsed as plain text.
- `projection[]`
- `filters` (`mime`, `mimeBase`, `fileKind`, `language`, `pathPrefix`, `includeChunks`, `modifiedAt`)
- Entity filter fields: `entityPersons`, `entityOrganizations`, `entityLocations` (repeated string) (362)
- Metadata filter fields: `metaSource`, `metaAuthor`, `metaCategory` (repeated string), `metaPublishedAt` (long range) (362)
- `doc_ids` (repeated string, TermInSetQuery on PATH field — scopes search to specific documents) (366)
- `boostFilters` (soft boost clauses, same fields as filters — additive SHOULD clauses) (363/366)
- `facets` (`include`, `maxDocsScanned`, `fields[]`)
- `includeExcerpts`
- `debug` (tempdoc 549 Phase D2: requests the optional numeric per-hit detail tier on
  `searchTrace`'s per-hit `HitStage.detail`; alias for `include_detail`. The structural per-hit
  trace slice is always-on regardless.)

Response fields (additive/optional-by-presence):

- `totalHits`, `matchCount`, `tookMs`, `results[]`
  - `matchCount` is the **true matched-document total** — an exact, filter-respecting
    `IndexSearcher.count` over the same chunk-excluded query the facets scan (tempdoc 597). The FE
    headline binds to this ("Top N of M matches").
  - `totalHits` is exact on the single-leg sparse path, but on the multi-leg (HYBRID/VECTOR) path it
    is the **cardinality of the bounded fused candidate union**, not a match count. It is therefore
    not monotonic under filtering — narrowing the corpus makes each leg dig deeper, so the legs'
    top-K lists overlap less and the union can grow. Treat it as telemetry, not as "how many
    documents matched"; use `matchCount` for that.
- `nextCursor` (TEXT mode pagination)
- `facets`, `facetsTruncated`
- `entityFacetVariants`
- `queryUnderstanding` (object: `appliedBoosts` map, `latencyMs`) — present when QU is enabled (363/366)
- `filterNormalization` (object: `original`, `normalized`, `latencyMs`, `source`) — present when filter normalization fires (366)
- `searchTrace` (object) — **tempdoc 549: the single canonical stage-keyed search-explainability
  artifact**, the SOLE source for what the pipeline did. Carries query-level scalars
  (`effectiveMode`, `decisionKind`, `qpp`, `degradation` with vector-blocked/hybrid-fallback/
  splade-skip reasons) + an ordered `stages[]` over the closed StageId vocabulary
  (query-understanding, expansion, correction, sparse/dense/splade-retrieval, fusion, chunk-merge,
  branch-fusion, lambdamart, cross-encoder, freshness), each with `status`/`reason`/`ms`/`detail`.
  **Subsumes and replaces** the retired `effectiveMode`/`vectorBlocked`/`hybridFallback`/
  `chunkMerge*`/`correction*`/`splade*`/qpp flat fields (E5), `pipelineExecution`/`ComponentTiming`
  (E3), and `introspection`/`SearchIntrospection` (E4).
- `appliedFilters` (object, echoed when filters active) — mirrors the filters/boostFilters sent in the
  request (366). Shape: `{ "filters"?: <Filters>, "boostFilters"?: <Filters> }`; each half is present
  only when that half carried at least one value, and each `Filters` object omits its unset members
  (no empty arrays). Absent entirely when the request carried no filters. This echoes the REQUEST —
  it says which filters were sent, not which index fields every retrieval leg can enforce them on.
- `indexCapabilities` — index-level capability flags (362)

Current hit shape in `results[]`:

- `id`, `score`, `fields` (metadata map)
- optional `matchedFields`, `matchSpans[]`
- optional `excerptRegions[]` (`text`, `startChar`, `endChar`, `approxLine`, `matchSpans[]`)
- optional `trace` (`HitStage[]`) — **tempdoc 549: the per-doc slice of the same stage vocabulary**
  (per-hit ranking provenance): which stages touched this hit (`id`, `rank`, `score`) + an optional
  numeric `detail` map (gated by `include_detail`/`debug`). **Subsumes and replaces** the retired
  per-hit `debugScores` map (E1) and leg-keyed `provenance` (E2).
- optional `meta_source`, `meta_author`, `meta_category`, `meta_published_at` (from stored fields) (362)

Controller behavior notes:

- Optional fields are omitted when null/blank.
- `correctionApplied` and `expansionApplied` are emitted only when `true`.
- `excerptRegions` are returned only when requested and available.

**SIMPLE query mode behavior:**
- Prefix expansion: the last whitespace-delimited term is expanded via `PrefixQuery` (e.g., `"justsearc"` matches `"justsearch"`). Minimum prefix length is 3 characters. Exact matches are boosted 2x over prefix-only matches. Uses `SCORING_BOOLEAN_REWRITE` for proper BM25 relevance ranking.
- Prefix expansion applies consistently across all search paths (unfiltered, filtered, and hybrid).

Frontend compatibility note: `modules/ui-web/src/api/domains/search.ts` maps this DTO-shaped response into the UI-facing flattened `SearchHit` shape (`doc_id`, `title`, `path`, etc.).

### Suggest API

**Source of truth:** `modules/ui/src/main/java/io/justsearch/ui/api/KnowledgeSearchController.java`

`GET /api/knowledge/suggest?query=<text>&limit=<n>` returns autocomplete suggestions (filenames and titles matching the query prefix).

### Knowledge Status and Ingest APIs

**Source of truth:** `modules/ui/src/main/java/io/justsearch/ui/api/KnowledgeSearchController.java`

`GET /api/knowledge/status`:

- Returns readiness/liveness for the Knowledge Server bridge.
- Always returns a full `KnowledgeStatusView` record (consistent shape regardless of Worker state). When the index half is unreachable, serves the last-known-good cached view with `statusStale: true` and `statusStaleMs: <elapsed>` (120s cap, then falls back to defaults).
- Key fields: `state`, `ready`, `indexState`, `healthy`, `indexedDocuments`, `embeddingCoveragePercent`, `spladeCoveragePercent`, `chunkEmbeddingReady` (chunk-level vector queryability, independent from parent-doc `embeddingCoveragePercent`), `statusStale`, `statusStaleMs`.
- When `statusStale: true`, `healthy` is overridden to `false` and `indexState` to `"UNKNOWN"` — other enrichment fields reflect the last-known-good state.

`POST /api/knowledge/ingest`:

- Request body: `paths[]` (required root/file paths), `collection` (optional string).
- Directory inputs dispatch to the Worker's `ScanRoot` RPC (the Worker owns the walk); regular-file inputs go through `submitBatch`.
- **Collection tagging (tempdoc 811 C-2a).** Every ingest carries an addressable collection, resolved by `IngestCollectionPolicy` (`modules/app-api/.../knowledge/IngestCollectionPolicy.java`): an explicit `collection` wins; otherwise a path under a registered watched root inherits that root's collection; otherwise the path is out-of-root and gets `mcp-ingest`. A supplied `collection` must be a non-empty string and must not be one of the reserved app-internal collections (`justsearch-help`, `agent-history`) — either violation is a `400`. One request may mix in-root and out-of-root paths; single files are grouped by resolved collection. Pre-811 documents ingested here carry no `collection` field and are not backfilled; they acquire a tag on re-index.
- Response: `accepted` (count accepted by Worker queue), `error` (best-effort error message), `scanId` (worker-allocated UUID for the scan; empty when no progress was emitted, e.g. inputs that weren't directories). Use the `scanId` to subscribe to live progress via the SSE endpoint below (tempdoc 419 / T4).

`DELETE /api/indexing/collections`:

- Request body: `collection` (required string). Removal route for collection-tagged ad-hoc ingests — `removeWatchedRoot` and `PruneOps.pruneByPathPrefix` are both watched-root-prefix driven, so before 811 an out-of-root ingest had no removal route at all.
- Deletes every parent and chunk document carrying the collection term (port call `IngestServiceCalls#deleteByCollection`), then commits.
- Refuses (`400`) the reserved app-internal collections (`justsearch-help`, `agent-history`) and the untagged `default` bucket — the latter would be a whole-index wipe wearing a collection's clothes.
- Response: `status: "ok"`, `collection`, `deletedDocs` (documents matched and submitted for deletion).

### Live Scan Progress (SSE)

**Source of truth:** `modules/ui/src/main/java/io/justsearch/ui/api/ScanProgressController.java`
+ `modules/app-services/src/main/java/io/justsearch/app/services/worker/ScanProgressRegistry.java`
+ `modules/app-api/src/main/java/io/justsearch/app/api/scan/ScanProgressEvent.java`

`GET /api/scans/{scanId}/progress` — Server-Sent Events stream backed by an in-memory `ScanProgressRegistry`. Bridges the synchronous in-process scan-progress consumer to UI subscribers.

- Path param: `scanId` — the value returned in `KnowledgeIngestResponse.scanId`.
- Response: `text/event-stream`. Events:
  - `event: progress` payload `{scanId, filesWalked, filesAdmitted, filesSkipped, bytesWalked, currentDirectory, complete: false}` per `ScanRootProgress` from the worker. `currentDirectory` is privacy-hashed (matches the tempdoc 410 / 418 path-hash contract — never a raw path).
  - `event: complete` payload `{scanId, ..., complete: true, terminalReasonCode}` once when the scan ends. `terminalReasonCode` is empty on clean completion or one of `CLIENT_CANCELLED`, `IO_ERROR`, `RPC_FAILED`, `ROOT_NOT_DIRECTORY`, `UNKNOWN_SCAN_OR_RETENTION_EXPIRED`.
  - `event: error` payload `{message}` on RPC-level failure during streaming.
- **Cancel:** closing the SSE connection (`EventSource.close()`) propagates a cancel to the index half via the `CancelToken` substrate (T3 — a plain observable boolean since lane F item A10 re-homed it off `io.grpc.Context`); the scan terminates with `CLIENT_CANCELLED` within the next batch.
- **Replay window:** subscribers that connect after the scan completes still see the full event sequence as long as the buffer is in memory (default retention `30s`, controlled by the registry — not env-configurable yet).

### Health Event Stream API (tempdoc 430)

**Source of truth:**

- `modules/ui/src/main/java/io/justsearch/ui/api/HealthEventStreamController.java` (SSE endpoint)
- `modules/app-observability/src/main/java/io/justsearch/app/observability/health/HealthResourceCatalog.java` (Resource entry)
- `modules/app-observability/src/main/java/io/justsearch/app/observability/health/HealthEvent.java` (wire payload record)
- `SSOT/schemas/health-event.v1.json` (generated schema)
- `modules/app-api/src/main/resources/messages/health-events.en.properties` (i18n catalog content)

Substrate endpoints:

- `GET /api/health/events/stream` (SSE) — Persistent-state Conditions + recent Lifecycle Occurrences + Threshold rule outcomes (memory.pressure et al). Initial `snapshot` event carries `{catalogVersion, conditions[], occurrences[]}`; `delta` events emit on transitions (`condition-added` / `condition-modified` / `condition-removed` / `occurrence-appended`); `heartbeat` ticks every 15s with `{catalogVersion}`. Reconnect via `Last-Event-ID` carries the last observed `catalogVersion`; the controller compares to current and replays a fresh snapshot when significantly behind. No persistent replay buffer (V1 — V2 may add one per §"Out of scope").
- `GET /api/messages/health-events/{locale}` — i18n catalog for HealthEvent IDs (label / message / per-reason override / remediation / runbook keys per §A.7). ETag + `Cache-Control: max-age=3600` per the parameterized `MessageCatalogController` defaults (per tempdoc 434).
- `GET /api/registry/resources` — already documented in the Operation Substrate API section above; after Phase 2 of tempdoc 430 it includes the `health.events` Resource entry with `subscriptionMode: "SSE_STREAM"`, `endpoint: "/api/health/events/stream"`, `kind: "health-event-stream"`.

Wire payload (`HealthEvent` record):

- `id` — stable catalog identifier (e.g., `index.unavailable`, `agent.session.completed`); 27 known IDs per §A.2 of tempdoc 430.
- `timestamp` — ISO-8601 instant.
- `source` — OTel Resource semconv: `{serviceName, serviceInstanceId, serviceVersion}`. The head's `serviceInstanceId` is process-stable (assigned once at `HeadAssembly` construction).
- `severity` — per-occurrence wire field: `INFO | WARNING | ERROR`. Not a catalog-static field (per §B.A; matches k8s + LSP + CloudEvents consensus).
- `i18nKey` — optional pointer into the i18n catalog above.
- `body` — sealed Jackson discriminator (`@JsonTypeInfo` on `kind`): `lifecycle` | `condition` | `threshold`. ConditionStore (rev 3.11 §B.X.2 generalization) holds both `condition` and `threshold` bodies as persistent state; `lifecycle` bodies go to OccurrenceLog (default 200-entry ring buffer; `JUSTSEARCH_HEALTH_OCCURRENCE_BUFFER` env override).

Coverage invariant: `HealthEventEmitCoverageTest` (in `modules/app-services` test) asserts every catalog ID has an emit site (LifecycleSnapshotTap mapping, WorkerSnapshotTap mapping, HeadHealthEventsEmitter, rule file) or is in the 3-ID FE-only allowlist (`api.unreachable`, `ai.not-configured`, `embedding.not-configured`; `schema.rebuilding` was retired 2026-09-07 as a phantom with no emitter, tempdoc 948).

### Library Resolve-Hash (ADR-0028, scoped exemption)

**Source of truth:** `modules/ui/src/main/java/io/justsearch/ui/api/IndexingController.java` (`handleResolvePathHash`)
+ `modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeClient.java` (`resolvePathHash`)
+ `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/queue/SqlitePathResolutionStore.java`

`POST /api/library/resolve-hash`:

- **Privacy contract:** the ONLY HTTP endpoint allowed to convert a `pathHash` back to a filename. Diagnostic export endpoints (`/api/diagnostics/ingestion/recent`, `/api/diagnostics/ingestion/summary`, `/api/diagnostics/export` — POST, optional `{ feTelemetry }` body embedded as `frontend/fe-telemetry.json` in the export zip, tempdoc 683) MUST NOT call this endpoint or its underlying `IndexingService.resolvePathHash`. Enforced by ArchUnit pin `LibraryResolveHashOnlyCallerPin` in `modules/app-launcher`.
- Request body: `{"pathHash": "<64-char SHA-256 hex>"}`.
- Response (found): `{"found": true, "path": "<normalized absolute path>", "lastSeenAtMs": <epoch>, "removedAtMs": <epoch or 0>}` — the file is still under a watched root and within the `JUSTSEARCH_PATH_RESOLUTION_RETENTION_DAYS` window (default 90).
- Response (not found): `{"found": false}` — hash never recorded, retention expired, or the watched root was unwatched (immediate prune).
- See `docs/decisions/0028-scoped-reverse-path-lookup.md` for the contract refinement rationale + lifecycle.

### Folder Browse APIs

**Source of truth:**

- `modules/ui/src/main/java/io/justsearch/ui/api/KnowledgeSearchController.java`
- `modules/app-api/src/main/java/io/justsearch/app/api/knowledge/FolderBrowseRequest.java`
- `modules/app-api/src/main/java/io/justsearch/app/api/knowledge/FolderBrowseResponse.java`
- `modules/app-api/src/main/java/io/justsearch/app/api/knowledge/FolderFilesRequest.java`
- `modules/app-api/src/main/java/io/justsearch/app/api/knowledge/FolderFilesResponse.java`

`POST /api/knowledge/folders`:

- Request body: `parentPath` (required), `maxFolders` (optional)
- Response: `folders[]` (`path`, `name`, `fileCount`, `totalSizeBytes`, `lastIndexedAt`), `tookMs`, `truncated`

`POST /api/knowledge/folder-files`:

- Request body: `folderPath` (required), `limit` (optional), `projection[]` (optional)
- Response: `files[]` (`docId`, `fields` map), `totalCount`, `tookMs`

### Watched Roots API

**Source of truth:** `modules/ui/src/main/java/io/justsearch/ui/api/IndexingController.java` (`handleListRoots`, `handleAddRoot` at IndexingController.java:156-175, `handleRemoveRoot`) + `modules/ui/src/main/java/io/justsearch/ui/api/routes/IndexingRoutes.java` (route registration)

`GET /api/indexing/roots` lists the Library's watched roots.

- Query parameter: `counts=true` -- includes a per-root bounded file count (capped scan, `-1` if the count itself failed).
- Response: `{"roots": [{collection, path, fileCount, lastIndexed?}]}` -- `collection` defaults to `"default"` when the root was added without one; `lastIndexed` is omitted (not empty-string) when the root has never completed a scan.

`POST /api/indexing/roots` adds a folder to the Library.

- Request body: `{"path": string, "collection"?: string}` -- `path` is required and must resolve (after `Path.of(path).toAbsolutePath().normalize()`) to an **existing directory**; `collection` is optional and defaults to `"default"` when omitted.
- **400** when `path` is missing/blank: `{"error": "path is required", "errorCode": "INVALID_REQUEST", ...}`.
- **400** when `path` does not exist or is not a directory: `{"error": "Path does not exist or is not a directory: <resolved path>", "errorCode": "INVALID_PATH", ...}`.
- Response (success): `{"status": "ok"}` (200). The root is registered and indexing begins asynchronously -- this call does not itself return a `scanId`; watch `/api/knowledge/status` or the per-root `fileCount` above for progress.

`DELETE /api/indexing/roots` removes a watched root and its indexed documents.

- Request body: `{"path": string, "collection"?: string}` -- same shape and the same **400** `path is required` (`INVALID_REQUEST`) validation as the POST above; no directory-existence check (the root may already be gone from disk).
- Response: `{"status": "ok", "deletedJobs": <count>}` (200) -- `deletedJobs` is the number of queued/failed jobs purged for the removed root.

### Indexing Excludes API

**Source of truth:** `modules/ui/src/main/java/io/justsearch/ui/api/IndexingController.java` (`handleApplyExcludes`)

`POST /api/indexing/excludes/apply` walks watched roots and deletes already-indexed documents matching the configured exclude patterns (from `ui.excludePatterns` setting).

**Query parameters:**
- `dryRun=true` â€” counts matches per-pattern without deleting (preview mode)

**Response fields:**
- `status`: `"ok"`
- `dryRun`: boolean
- `patterns`: number of expanded patterns
- `rootsProcessed`: number of watched roots walked
- `deletedByPathJobs`: docs deleted via path-prefix optimization (directory patterns)
- `deletedById`: docs deleted individually (file patterns)
- `matchedFiles`: total matched files/directories
- `perPattern`: `[{pattern: string, matches: number}]` â€” per-pattern match counts (expanded patterns, not raw user input)
- `capped`: boolean â€” `true` if the walk was terminated early (500k entry limit)

**Contract test:** `IndexingControllerExcludesApplyTest` (apply + dry-run paths)

### Suggested Roots API

**Source of truth:** `modules/ui/src/main/java/io/justsearch/ui/api/IndexingController.java` (`handleSuggestedRoots`)

`GET /api/indexing/suggested-roots` returns platform-aware folder suggestions for the Library empty-state CTA. Resolves `Documents`, `Desktop`, `Downloads` under `System.getProperty("user.home")`, filtered by directory existence and already-watched roots.

**Response fields:**
- `suggestions`: `[{label: string, path: string}]` â€” folders that exist and are not already watched

### Index Settle API

**Source of truth:** `modules/ui/src/main/java/io/justsearch/ui/api/IndexingController.java` (`handleSettleIndex`)

`POST /api/indexing/settle` purges deleted-but-unmerged documents from the ACTIVE index (port call
`IngestServiceCalls#settleIndex`). Tempdoc 931 section E item 10: a tombstone still counts in the BM25
collection statistics, so two indexes of the same corpus carrying different tombstone counts answer
the same query differently. A paired evaluation calls this between the indexing phase and the query
phase so both arms compare with equal merge state.

**Request body (all optional):**
- `expungeDeletesOnly`: boolean, default `true` -- purge tombstones only. When `false` the Worker
  ALSO force-merges the segment layout.
- `maxSegments`: integer, default `0` -- target segment count for the force-merge branch
  (`0` means the Worker default of 1). Only read when `expungeDeletesOnly` is `false`.

**Response (202):** `{status: "settle completed", expungeDeletesOnly, maxDocBefore, numDocsBefore,
maxDocAfter, numDocsAfter, segmentsAfter, elapsedMs}`.

**Response (409):** `{status: "settle rejected by worker", error}` -- the Worker refuses when the
index runtime is unavailable, a blue/green migration is in flight (`MIGRATING`/`SWITCHING`/`UNKNOWN`),
or an upgrade quiescence preparation owns the Worker barrier.

**Substrate equivalent:** the `core.settle-index` Operation (`SettleIndexHandler`), same arguments and
the same structured output.

**Contract test:** `IndexingControllerSettleTest` (202 counts, defaults, force-merge args, 409 refusal)

### OpenAI-compatible API (`/v1/*`)

**Source of truth:** `modules/ui/src/main/java/io/justsearch/ui/api/OpenAiCompatController.java`

JustSearch's loopback HTTP server exposes a minimal OpenAI-compatible surface that proxies to the locally-running `llama-server`. This lets third-party agents and CLI tools target JustSearch's documented loopback port (published in the runtime manifest at `<dataDir>/runtime/manifest.json#head.apiPort` per tempdoc 501; also served at `GET /api/runtime/manifest` and `GET /.well-known/justsearch/manifest.json`) using the standard OpenAI client SDKs without first discovering the internal `llama-server` port. Tempdoc 374 alpha.17 R5 added this surface; tempdoc 501 made the runtime manifest the canonical discovery channel.

| Method | Path | Forwards to |
|--------|------|-------------|
| `POST` | `/v1/chat/completions` | `llama-server` `/v1/chat/completions` (SSE streaming preserved) |
| `GET`  | `/v1/models`           | `llama-server` `/v1/models` |

**Behaviour:**
- Method, body, and most request headers are forwarded. Hop-by-hop headers (`Host`, `Connection`, `Transfer-Encoding`, etc.) are dropped per RFC 7230.
- Status code, response headers, and body are streamed back unchanged.
- When `llama-server` is offline (port unset, connect refused, or no chat package installed), the proxy responds `503 SERVICE_UNAVAILABLE` using the project's standard error envelope.
- Token enforcement: `POST /v1/chat/completions` is subject to the same session-token requirement as the rest of the non-GET API surface in prod mode. Third-party agents read the token from JustSearch's stdout `JUSTSEARCH_SESSION_TOKEN=...` line on launch (or via the Tauri bridge for in-app agents).

**Out of scope:**
- `/v1/embeddings` — JustSearch's embedding encoder is in-process in the Worker; no HTTP server hosts it.
- Loopback-only. The proxy does not apply rate limiting, billing, or quota checks; those would belong on a Javalin `before` handler if needed.

## Protobuf messages (in-process port DTOs)

**Source of truth:** `modules/ipc-common/src/main/proto/indexing.proto` — the one remaining
`.proto` file, and the request/response vocabulary of the Engine's in-process ports.

It declares **messages only**. Lane F stage A deleted the Head↔index gRPC channel (items A9-A11)
and then, at item A14, the last `service` blocks (`SearchService`, `IngestService`,
`HealthService`), the separate `io/justsearch/ipc/v1/infra_diagnostics.proto` with its
`InfraDiagnosticsService`, and the `protoc-gen-grpc-java` generator in
`modules/ipc-common/build.gradle.kts`. `protoc` still runs, so the generated message classes
remain; nothing generates or serves a stub. See [ADR-0049](../decisions/0049-one-engine-jvm-and-the-boundaries-that-survive.md).
These proto DTOs at the port signatures are transitional
(`modules/app-services/src/main/java/io/justsearch/app/services/worker/SearchServiceCalls.java`
records the follow-up that replaces them with `app-api` records).

### Port-call TCK coverage gaps

`GET /api/preview` delegates paged stored-text reads to the index half's `fetchDocumentSlice`
port call (`SearchServiceCalls#fetchDocumentSlice`, bound in-process by
`modules/app-engine/src/main/java/io/justsearch/app/engine/WorkerSearchCalls.java`).
The Worker reads content and extraction provenance through one searcher. Its metadata map carries
the canonical `content_sha256`, projected by Head as nullable `contentSha256`; this identifies the
complete stored UTF-8 text, including VDU replacements. `sourceSha256` identifies the extracted source
bytes and has a separate meaning. Pages use UTF-16 character offsets, preserve Unicode scalar boundaries,
and include `totalChars`. Strict measurement clients require an unchanged content revision across pages
and verify the assembled text against it. `SUCCESS_EMPTY` remains a found document with empty content.

The following port calls are covered by integration tests but lack isolated TCK-style contract tests in `app-api-tck`. Highest-value additions are marked. (They were gRPC RPCs until lane F stage A item A6 re-homed the same call surface onto `SearchServiceCalls`; the names and signatures are unchanged.)

| Port | Call | Priority |
|---------|-----|----------|
| `SearchServiceCalls` | `suggest` | **High** — user-facing autocomplete hot path |
| `SearchServiceCalls` | `retrieveContext` | **High** — RAG context retrieval hot path |
| `SearchServiceCalls` | `fetchDocuments` | Medium |
| `SearchServiceCalls` | `fetchDocumentSlice` | Medium |
| `SearchServiceCalls` | `rerank` | Medium — cross-encoder reranking on the index half's GPU (360) |
| `SearchServiceCalls` | `matchCitations` | Low |
| `SearchServiceCalls` | `listFolders` | Low |
| `SearchServiceCalls` | `listFolderFiles` | Low |

### Why not Pact / consumer-driven contracts

Head and Body are co-shipped in the same Tauri bundle â€” there is no independent deployment or version skew. Consumer-driven contract testing (Pact) is designed for microservices where consumer and provider deploy independently and is not applicable here.

## Error Response Format

**Source of truth:** `modules/app-api/src/main/java/io/justsearch/app/api/ApiErrorCode.java` (enum registry), `modules/ui/src/test/java/io/justsearch/ui/api/ApiErrorCodeContractTest.java` (bidirectional contract)

All API error responses use a unified error type system. Every error carries a machine-readable code from `ApiErrorCode` (~92 codes) and a classification from `ErrorClass` (in `modules/app-api/`).

### REST error responses

All REST endpoints emit:

```json
{
  "error": "The request timed out",
  "errorCode": "TIMEOUT",
  "errorClass": "TRANSIENT",
  "retryable": true,
  "i18nKey": "errors.TIMEOUT",
  "requestId": "abc-123"
}
```

The `i18nKey` field (added by tempdoc 431, slice 1.1.d) is `"errors." + errorCode`. Frontends look up the user-facing English message by this key in the boot-fetched catalog from `GET /api/messages/errors/en`.

### Summary SSE error events

Summary and RAG streaming endpoints emit error events with the same classification fields plus `i18nKey`:

```text
event: error
data: {"error": "...", "errorCode": "AI_UNAVAILABLE", "errorClass": "TRANSIENT", "retryable": true, "i18nKey": "errors.AI_UNAVAILABLE"}
```

### Agent SSE error events

Agent SSE error events use the {@link io.justsearch.agent.api.AgentErrorCode} enum. The envelope shares `i18nKey` with the REST + summary SSE surfaces but uses `retryAction` instead of `retryable`:

```text
event: error
data: {"error": "...", "errorCode": "BUDGET_EXHAUSTED", "errorClass": "BUDGET", "retryAction": "ABORT", "retryAttempt": 0, "i18nKey": "errors.BUDGET_EXHAUSTED"}
```

Agent SSE intentionally does NOT emit `retryable` — `retryAction` (`RETRY`/`ABORT`/`FALLBACK`) is a richer signal than a boolean. The `i18nKey` field is on the wire as of the tempdoc 431 Option A followup (D.2.d), so the frontend no longer derives it locally for any error surface.

### ErrorClass values

| Value | Meaning | Frontend behavior |
|-------|---------|-------------------|
| `TRANSIENT` | Temporary failure, retry may succeed | Show retry prompt |
| `PERMANENT` | Unrecoverable, do not retry | Show error, no retry |
| `POLICY` | Blocked by user policy | Show policy explanation |
| `VALIDATION` | Invalid request data | Show validation feedback |

Frontend retry logic is data-driven: read `retryable: boolean` directly from the error envelope (REST + summary SSE). For agent SSE, read `retryAction` (`RETRY` → safe to retry; `ABORT`/`FALLBACK` → don't retry). The legacy hardcoded retry-eligibility list in `utils/aiErrors.ts` was removed by tempdoc 431.

### Agent-domain errors

Agent SSE errors use a separate `AgentErrorCode` enum (in `modules/app-agent-api/`) with finer-grained classifications (`BUDGET`, `TOOL_CONTRACT`, `CANCELLED`) and explicit `retryAction` (`RETRY`, `ABORT`, `FALLBACK`). The two enums are intentionally separate — agent error semantics are domain-specific.

### Error message catalog

`GET /api/messages/errors/{locale}` (added by tempdoc 431, URL aligned with tempdoc 434 §"Catalog endpoint" by Option A followup) returns the canonical error message catalog:

```json
{
  "$schema": "https://ssot.justsearch/v1/schemas/i18n-catalog.json",
  "schemaVersion": "1.0",
  "locale": "en",
  "namespace": "errors",
  "messages": {
    "errors.TIMEOUT": "The request timed out. Please try again.",
    "errors.AI_STARTING": "AI is starting up. Please wait a moment.",
    ...
  }
}
```

V1 ships English only (`/api/messages/errors/en`); non-en locales return 404 with a hint that translation is V1.5+ work (per tempdoc 434 §"What it does NOT support"). The catalog source of truth is `modules/app-api/src/main/resources/messages/errors.en.properties`; the SSOT JSON artifact `SSOT/messages/errors.en.json` is regenerated via `./gradlew :modules:app-api:updateSchemas`.

The endpoint sets HTTP cache headers (`Cache-Control: public, max-age=3600` and a strong `ETag` computed at controller construction from a SHA-256 of the response body). Conditional GETs (`If-None-Match: <etag>`) that match the cached ETag receive a 304 Not Modified — the catalog content only changes on backend restart, so the ETag is stable for the lifetime of the process.

This URL pattern (`/api/messages/<catalog-name>/<locale>`) is the precedent for the multi-catalog system per tempdoc 434: future catalogs (shell strings, per-shape labels, plugin and theme catalogs) follow the same shape.

### Contract enforcement

The frontend-vs-enum sync test (`ApiErrorCodeContractTest` tests #1 and #2) was retired by tempdoc 431 alongside the deletion of `modules/ui-web/src/utils/errorMessages.ts`. The replacement contract test is `ErrorMessagePropertiesContractTest` in `modules/app-api`, which asserts every `ApiErrorCode` and `AgentErrorCode` value has a matching key in `errors.en.properties` (and no orphan keys). The remaining tests (`ApiErrorCodeInvariantTest` #3–#5) assert backend invariants on `ErrorClass` consistency.

**Cross-language contract tests (368/370):**

- `StatusRecordSchemaTest.CrossLanguageContract` — serializes a deterministic `StatusResponse` in Java, validates against `SystemStatusSchema` (Zod) in TypeScript. Any field change fails the Java test with regeneration instructions.
- `SettingsV2ContractTest` — round-trips `SettingsV2` through frontend save → backend load → frontend read, verifying no field loss. Includes `settingsMode` validation.
- Search fixture → TypeScript `SearchResponseSchema` — `KnowledgeSearchResponse` serialized with populated hits, provenance, metadata, facets, pipeline execution, and entity variants. TypeScript test maps through `mapKnowledgeSearchResponse()` (production path) and validates against the Zod schema.
- Controller HashMap contract test — verifies that every `KnowledgeSearchResponse` record component has a corresponding key in the controller's manually-built `HashMap`. Catches silently omitted fields on addition (370 P1).

All cross-language contract tests use `ORDER_MAP_ENTRIES_BY_KEYS` for deterministic JSON output. Fixtures represent the sorted wire format. Regenerate with `./gradlew.bat :modules:app-api:updateSchemas`.

### Debug APIs

**Source of truth:** `modules/ui/src/main/java/io/justsearch/ui/api/routes/DebugRoutes.java`

`GET /api/debug/state` — detailed internal state dump (config, worker, LLM, GPU).

`POST /api/debug/reset-index` — wipes all index state for pipeline profiling
(tempdoc 355). Gated on `justsearch.eval.mode=true` (set by
`runHeadlessEval`); returns 404 in production. Sequence: index-half reset via
the `resetIndex` port call (stop loop → delete all docs → commit + refresh → clear queue +
clusters + metrics → restart loop), then Head clears watched roots +
persists empty state. Response: `{"reset": true}` on success, 500 on
failure.

`GET /api/debug/session-policies` — resolved `RuntimePolicy` + per-encoder
`ModelSessionPolicy` snapshots as JSON. **Not gated on eval mode** —
available in production (unlike `/api/debug/reset-index`). Proxied from
the index half's live `InferenceSurface` via the `getSessionPolicies` port
call (tempdoc 397 §14.28 U4); Head does not re-resolve. Response shape:
`{configStatus, runtime, models}` where `configStatus ∈ {ok,
config-unavailable, surface-unavailable, worker-unreachable}`
(`config-unavailable` = no `ResolvedConfig` on Head; `surface-unavailable`
= Worker hasn't composed yet; `worker-unreachable` = the port call failed or
there is no client). Controller: `modules/ui/src/main/java/io/justsearch/ui/api/SessionPoliciesController.java`.

Other debug endpoints: `/api/debug/commit-metadata`, `/api/debug/effective-config`,
`/api/debug/events`, `/api/debug/engine-log`, `/api/debug/dashboard`,
`/api/debug/chunks`, `/api/debug/logging` (GET/POST),
`/api/debug/metrics/timeseries`, `/api/debug/metrics/timeseries/available`.

### AI Runtime Status

`GET /api/ai/runtime/status` returns ONNX feature status including a `modelActive: boolean` field per feature entry, derived from the actual ORT session state (not file discovery). This is the canonical source of truth for "is this model loaded and running". Both reranker (via `OrtCudaStatus`) and citation-scorer (via `CitationScorer.isAvailable()`) report runtime session state through this field. See [ADR-0023](../decisions/0023-api-responses-declare-runtime-context.md) for the general principle: endpoints whose behavior varies by runtime mode must declare that mode in the response.

### AI install (`/api/ai/install/*`)

**Source of truth:** `modules/ui/src/main/java/io/justsearch/ui/api/routes/AiRoutes.java` (routes),
`modules/ui/src/main/java/io/justsearch/ui/api/AiInstallController.java` (handlers),
`modules/ui/src/test/java/io/justsearch/ui/api/AiInstallApiContractTest.java` (error-body contract).

| Route | Method | Request | Success | Errors |
|---|---|---|---|---|
| `/api/ai/install/manifest` | GET | – | `ModelRegistry` | 500 `MANIFEST_UNAVAILABLE` |
| `/api/ai/install/plan-preview` | GET | – | `InstallPlanPreview` | 500 `MANIFEST_UNAVAILABLE` |
| `/api/ai/install/status` | GET | – | `AiInstallStatus` | – |
| `/api/ai/install/start` | POST | `{"acceptTerms": true}` | `AiInstallStatus` | 400 `TERMS_REQUIRED`, 403 `DOWNLOADS_DISABLED`, 409 `INSTALL_ALREADY_RUNNING`, 500 `INSTALL_START_FAILED` |
| `/api/ai/install/cancel` | POST | – | `AiInstallStatus` | 500 `INSTALL_CANCEL_FAILED` |
| `/api/ai/install/repair` | POST | `{"acceptTerms": true}` | `AiInstallStatus` | same set as `start`, plus 500 `INSTALL_REPAIR_FAILED` |
| `/api/ai/install/pause` | POST | – | `AiInstallStatus` (`paused: true`, `state` still `running`) | 409 `INSTALL_NOT_RUNNING`, 500 `AI_INSTALL_ERROR` |
| `/api/ai/install/resume` | POST | – | `AiInstallStatus` (`paused: false`) | 409 `INSTALL_NOT_RUNNING`, 500 `AI_INSTALL_ERROR` |
| `/api/ai/install/packages/{packageId}/decline` | POST | – | `AiInstallStatus` | 400 `INVALID_REQUEST`, 400 `PACKAGE_NOT_DECLINABLE`, 404 `PACKAGE_NOT_FOUND`, 409 `SETTINGS_READ_ONLY`, 503 `SETTINGS_UNAVAILABLE`, 500 `AI_INSTALL_ERROR` |
| `/api/ai/install/packages/{packageId}/decline` | DELETE | – | `AiInstallStatus` | 400 `INVALID_REQUEST`, 404 `PACKAGE_NOT_FOUND`, 409 `SETTINGS_READ_ONLY`, 503 `SETTINGS_UNAVAILABLE`, 500 `AI_INSTALL_ERROR` |

Every error body on this family is the project's standard envelope —
`{"error": string, "errorCode": string, "errorClass": string, "retryable": boolean, "requestId"?: string}`
(`ApiErrorHandler.toResponse`). A `400` here is **not** body-less: `AiInstallApiContractTest`
asserts the raw bytes of a `POST … {}` carry `errorCode: "TERMS_REQUIRED"`.

**`repair` IS `start`.** `AiInstallService.repair` delegates straight to `startInstall`: it re-plans
against disk and downloads only what is missing, and it therefore requires `acceptTerms` exactly
like a first install. Calling it with an empty body is the 400 above, not a server fault.

`AiInstallStatus` carries two truth claims that answer different questions and must not be
collapsed: `installedFully` (no **required** file is missing) and `repairNeeded` (a **required**
file is missing). `optionalGaps: [{packageId, fileName}]` reports registry files marked
`"required": false` that are absent — surfaced, never a reason to offer Repair. Per package,
`functionalStatus` (`active` | `inactive` | `unknown`) projects what `GET /api/ai/runtime/status`
observes, and `terminalReason: "TRANSPORT_UNAVAILABLE"` with `attempts`/`url`/`targetPath` says that
automatic repair has stopped working and names the manual fallback.

**Pause is not cancel.** A paused run keeps its op-lease, its plan and its place in the set and
halts *between* files, so `state` stays `running` and `paused` is the bit that changed; `cancel` is
the terminal one. Both endpoints refuse with `409 INSTALL_NOT_RUNNING` when no run is in flight —
the pause gate outlives any one run, so arming it while idle would halt the *next* install before
its first byte.

**Declining a component.** `POST …/packages/{packageId}/decline` records the choice in
`UiSettings.declinedAiPackages`; `DELETE` on the same path withdraws it. `Necessity.userDeclinable()`
is the authority: a `required` or `infrastructure` package (`embedding`, `cuda-runtime`) is refused
with `400 PACKAGE_NOT_DECLINABLE` rather than silently ignored. The `DELETE` direction is never
refused on necessity grounds — "install this after all" cannot be an invalid request. Both are
idempotent (a no-change request writes nothing and answers `200`).

**Rate and remaining time are `-1` when unknown, never `0`.** `bytesPerSecond` and
`remainingSeconds` carry `AcquisitionRate`'s explicit unknown sentinel straight through: too few
samples, a window too short to measure, a transfer that stopped reporting, or a window over which no
bytes arrived all publish `-1`. `remainingSeconds: 0` is a real value (nothing left to move);
`bytesPerSecond: 0` is never published. Both reset to `-1` on every non-`running` state. Consumers
must test `>= 0` before rendering — a `0 B/s` on a young transfer is the plausible-looking lie this
convention exists to prevent (same rule as `startupEstimate.ts`'s `< 0` arm).

**Per-component metadata on `packages[]`.** `description` and `necessity`
(`required` | `improves-results` | `adds-feature` | `infrastructure`) are registry facts stamped
when the row is built; `declinable` projects `Necessity.userDeclinable()`; `declined` is the live
`UiSettings` preference, re-read on every status read so a decline made between two polls shows on
the next one.

### Install plan preview (tempdoc 657)

`GET /api/ai/install/plan-preview` returns a **side-effect-free** projection of the download plan grouped by capability tier, for the current hardware and install intent — the honest first-run weight breakdown shown before the user commits (realizes tempdoc 381 §F). Shape: `{ intent, downloadProfile, totalDownloadBytes, resumableBytes, tiers: [{ tier, label, includedByIntent, totalBytes, downloadBytes }], components: [{ id, label, description, tier, necessity, declinable, declined, totalBytes, downloadBytes, state, unavailableReason }] }`. Computing it runs no downloads (reuses the pure `InstallPlanner`).

`components[]` is the per-component list the install UI is built from, and it lives here rather than on `GET /api/ai/install/status` deliberately: `status.packages` is run bookkeeping and is EMPTY on an idle machine that is not fully installed, so a list sourced from it would show a first-run user nothing — the user for whom it matters most. `state` is `installed | to-download | declined | unavailable | not-in-mode`, derived from the same plan the tier totals come from, so the list cannot disagree with what an install would actually do. `declined` (the user's standing preference) and `unavailable` (hardware this machine lacks) are deliberately distinct — presenting an unavailable component as an unticked box would imply an option that does not exist. `declinable` is derived from `necessity`, never stored twice. The install/runtime **mode** itself is reported on the runtime manifest (`GET /api/runtime/manifest#mode`, with `intent` + coarse `realized`) per the tempdoc 501 closure rule.

## Error Response Sanitization

All error responses pass through `ApiErrorHandler.sanitizeMessage()` which:

- Replaces file paths with `[path]`
- Replaces internal class names with `[internal]`
- Preserves semantic error information for debugging

This ensures no sensitive path information leaks to clients while maintaining debuggability via server logs.

**Source of truth:** `modules/ui/src/main/java/io/justsearch/ui/api/ApiErrorHandler.java`

## Error Telemetry

All controllers use auto-recording `ApiErrorHandler.toResponse()` overloads that emit a `api.error.total` counter on every error response. Tags:

| Tag | Source | Example |
|-----|--------|---------|
| `error_code` | `ApiErrorCode.name()` | `TIMEOUT` |
| `error_class` | `ApiErrorCode.errorClass().name()` | `TRANSIENT` |
| `route` | `ApiErrorHandler.routeOf(ctx)` | `/api/knowledge/search` |

Route tags are extracted at runtime via `ApiErrorHandler.routeOf(ctx)`, which calls Javalin's `endpointHandlerPath()` to get the matched route pattern (e.g. `/api/chat/sessions/{id}/events`, not the actual request path with expanded params). All three tags are in the NDJSON exporter allowlist.

All 15 controllers receive `Telemetry` via constructor injection from `LocalApiServer` and use auto-recording `toResponse()` overloads. All `toResponse()` overloads are null-safe.

**Test coverage:** `ApiErrorHandlerResolveTest` covers `resolve()` (all exception branches), `toResponse()` null-safety, `sanitizeMessage()`, and `resolveByName()`.

## Typed Exceptions

Three typed exception classes replace fragile message-sniffing patterns:

| Exception | Module | Replaces |
|-----------|--------|----------|
| `LlmServerException` | `app-inference` | `RuntimeException("Server returned status ...")` |
| `KnowledgeServerNotConnectedException` | `app-services` | `IllegalStateException("Not connected to Knowledge Server")` |
| `ModeTransitionException.Reason.EXTERNAL_SERVER_POLICY_BLOCKED` | `app-inference` | `IOException("External inference servers are disallowed...")` |

`LlmServerException` carries the HTTP status code from llama-server, enabling `instanceof` + `httpStatus()` switch instead of `msg.contains("status 400")`. Mapped in both `ApiErrorHandler.resolve()` and `SummaryErrorUtils.resolveErrorCode()`: 400 â†’ `CONTEXT_TOO_LARGE`, 429/503 â†’ `LLM_OVERLOADED`. `SummaryErrorUtils.resolveErrorMessage()` retains a defensive fallback for non-`LlmServerException` messages containing "status 503" or "Service Unavailable".

`KnowledgeServerNotConnectedException` extends `IllegalStateException` for binary compatibility and is caught directly in `IndexingController` instead of message-sniffing.

`ModeTransitionException.Reason.EXTERNAL_SERVER_POLICY_BLOCKED` is thrown directly from `LlamaServerOps.adoptExistingServerIfPresent()` and propagated through `InferenceLifecycleManager.asModeTransition()` â€” no message-sniffing in the exception propagation chain.

**Test coverage:** `SummaryErrorUtilsTest` covers `resolveErrorCode()`, `resolveErrorMessage()` (including defensive fallbacks), and `buildSseErrorPayload()`.

## Reason codes

- Search + RAG reason codes: `docs/reference/contracts/search-and-rag-reason-codes.md`
- Lifecycle reason codes: `io.justsearch.app.api.lifecycle.LifecycleReasonCode` (validated by `LifecycleContractTest`)
