/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.Javalin;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.telemetry.Telemetry;
import io.justsearch.ui.runtime.RuntimeManifestPublisher;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 583 Stage 3: the substrate-Resource controller cohort, lifted out of {@link
 * LocalApiServer}'s constructor (§B.7 remedy).
 *
 * <p>This cohort — the registry / resource / metric / advisory / history / authorization /
 * hard-stop controllers — is constructed only when a {@link HeadAssembly} bootstrap is present
 * (it reads the composed substrate). In LocalApiServer it was ~250 LOC of {@code if
 * (b.HeadAssembly != null) { … }} construction + a 28-line {@code else} null-init mirror + ~120
 * LOC of route binding + ~110 LOC of SSE-shutdown blocks — three sites that grew with every new
 * Resource slice (the dominant pin-treadmill cohort per §B.3). Here it is ONE collaborator:
 * LocalApiServer constructs it iff a bootstrap is present and otherwise holds {@code null}, so the
 * test-only Builder path (no bootstrap) is handled by the single {@code module != null} check at
 * each call site rather than 26 field-level null guards.
 *
 * <p>Behaviour is identical: the construction, route, and shutdown blocks moved verbatim. Because
 * the module exists only when the bootstrap does, every controller field here is non-null (the
 * sole exception is {@link #runtimeApiRoutes}, which stays null when no runtime-manifest publisher
 * was supplied), so the per-controller null guards from setupRoutes collapse.
 */
final class ResourceApiModule implements ApiModule {
  private static final Logger log = LoggerFactory.getLogger(ResourceApiModule.class);

  // Tempdoc 583 §D.3a — the paths this module bound, self-captured during register() so the route
  // manifest's owningModule attribution derives from the REAL registration, not a parallel table.
  private final java.util.Set<String> ownedPaths = new java.util.HashSet<>();

  private final HeadAssembly headAssembly;

  private final RegistryController registryController;
  private final WitnessController witnessController;
  private final CapabilitiesStreamController capabilitiesStreamController;
  // Null when no runtime-manifest publisher was supplied (even with a bootstrap present).
  private final io.justsearch.ui.api.routes.RuntimeApiRoutes runtimeApiRoutes;
  private final HealthEventStreamController healthEventStreamController;
  private final IntentStreamController intentStreamController;
  private final DiagnosticChannelStreamController diagnosticChannelStreamController;
  private final IndexingJobsStreamController indexingJobsStreamController;
  private final RuntimeContextController runtimeContextController;
  private final OperationHistoryController operationHistoryController;
  private final AuthorizationController authorizationController;
  private final OperationPreviewController operationPreviewController;
  private final ActionLedgerController actionLedgerController;
  private final InteractionThreadController interactionThreadController;
  // Tempdoc 778 — the default-on local feedback-capture flag surface.
  private final FeedbackCaptureController feedbackCaptureController;
  private final MemoryController memoryController;
  private final HardStopController hardStopController;
  private final AdvisoryStreamController operationCompletedAdvisoryStreamController;
  private final AdvisoryStreamController healthRecoverableAdvisoryStreamController;
  private final AdvisoryStreamController authorizationPendingAdvisoryStreamController;
  // Tempdoc 662: cross-channel multiplexer over the 5 always-on streams above.
  private final ShellEventsStreamController shellEventsStreamController;
  private final ConditionRecoveryIndexController conditionRecoveryIndexController;
  private final DebugConditionController debugConditionController;
  private final JobQueueDepthMetricController jobQueueDepthMetricController;
  private final DocumentsIndexedRateMetricController documentsIndexedRateMetricController;
  private final GpuUtilizationMetricController gpuUtilizationMetricController;
  private final GpuMemoryUtilizationMetricController gpuMemoryUtilizationMetricController;
  private final OperationsController operationsController;

  ResourceApiModule(
      HeadAssembly headAssembly,
      Telemetry telemetry,
      RuntimeManifestPublisher runtimeManifestPublisher,
      Path indexBasePath, io.justsearch.app.api.EngineAdmissionService admission) {
    this.headAssembly = headAssembly;
    // Tempdoc 429 §F.11: multi-catalog wiring — CoreOperationCatalog (3 admin seeds)
    // + AgentToolsOperationCatalog (4 tool migrations). RegistryController aggregates
    // definitions across both when serving /api/registry/operations.
    this.registryController =
        new RegistryController(
            List.of(
                headAssembly.substrate().operations().operations(),
                headAssembly.substrate().operations().agentTools()),
            List.of(
                headAssembly.substrate().resources().resources(),
                headAssembly.substrate().resources().runtimeContext(),
                headAssembly.substrate().resources().serverCapabilities(),
                // Tempdoc 560 WS7b — the Brain's runtime capability as an OBSERVABLE registry entry.
                headAssembly.substrate().resources().coreInference(),
                headAssembly.substrate().resources().operationHistory(),
                // Slice 494: both advisory-class Resources (operation-completed +
                // health-recoverable) via the unified catalog.
                headAssembly.substrate().advisory().resources(),
                // Slice 445: TABULAR Resource entry for the worker indexing-jobs
                // queue. The first Category.TABULAR instance.
                headAssembly.substrate().resources().indexingJobs(),
                // Slice 3a.1.9 §B.B.D Stream A: Crash list as second TABULAR
                // Resource (TABULAR × ONE_SHOT, /api/indexing-jobs/failed).
                headAssembly.substrate().resources().failedIndexingJobs(),
                // Slice 449 phase 7a: Library calibration TABULAR Resource
                // (TABULAR × ONE_SHOT, /api/indexing-roots/substrate).
                headAssembly.substrate().resources().indexedRoots(),
                // Slice 447-impl-D: derived inverse Resource — Operation →
                // Conditions referencing it (STATE × ONE_SHOT,
                // /api/condition-recovery-index).
                headAssembly.substrate().resources().conditionRecoveryIndex(),
                // Tempdoc 571 §4c: the action-ledger Resource (TRUST role). RegistryController reads
                // its role to DERIVE the Activity surface's Altitude.TRUST onto the surfaces wire.
                headAssembly.substrate().resources().actionLedger(),
                // Tempdoc 575 §17 Face C: Brain install/pack OBSERVABLE polled-state Resources, now served
                // at the LIVE /api/registry/resources (not just the offline governance snapshot).
                headAssembly.substrate().resources().aiInstall(),
                headAssembly.substrate().resources().aiPackImport(),
                // Slice 3a.1.4 Phase 5: TIMESERIES Resource entry for the worker
                // job-queue-depth metric. The canonical first instance of the
                // typed-shape substrate's TIMESERIES Category.
                headAssembly.substrate().metrics().jobQueueDepthCatalog(),
                // Slice 3a.1.4b cohort: three additional TIMESERIES Resources.
                headAssembly.substrate().metrics().documentsIndexedRateCatalog(),
                headAssembly.substrate().metrics().gpuUtilizationCatalog(),
                headAssembly.substrate().metrics().gpuMemoryUtilizationCatalog(),
                // Tempdoc 560 §28 Phase 2: composed plugin-contributed Resources (empty unless a plugin
                // contributed one — the example plugin is dev-gated). handleResources flat-maps the list.
                headAssembly.substrate().resources().pluginResources()),
            // Slice 448 phase 2: DiagnosticChannel — the fourth registry primitive. Tempdoc 560 §10.4:
            // core engine-log + the composed plugin-contributed channels (empty unless a plugin
            // contributed one — the example plugin is dev-gated).
            List.of(
                headAssembly.substrate().channels().engineLogCatalog(),
                headAssembly.substrate().channels().pluginChannelCatalog()),
            // Slice 449 phase 4: Surface Manifest catalogs. Tempdoc 560 §10.4: core surfaces +
            // the composed plugin-contributed surfaces (empty unless a plugin contributed one —
            // a plugin RAIL surface renders in the rail; the example plugin is dev-gated).
            List.of(
                headAssembly.substrate().conversation().coreSurfaces(),
                headAssembly.substrate().conversation().pluginSurfaceCatalog()),
            // Slice 491 §9.D Phase E (C0): ConversationShape catalog. The single
            // CoreConversationShapeCatalog ships 6 shapes (agent, navigate, ask,
            // summarize, batch-summarize, hierarchical-summarize). Tempdoc 560 §10.4: core shapes +
            // the composed plugin-contributed shapes (empty unless a plugin contributed one).
            List.of(
                headAssembly.substrate().conversation().shapes(),
                headAssembly.substrate().conversation().pluginShapeCatalog()),
            // Tempdoc 565 §26.C — the CORE workflow catalog projected to the picker wire
            // (/api/registry/workflows), replacing the FE's hardcoded WORKFLOW_ID (§25.2).
            List.of(
                io.justsearch.app.services.conversation.CoreWorkflowCatalog.catalog()),
            // Tempdoc 560 §28 Phase 2: the served Prompt catalog (core + composed plugin Prompts) — the
            // assembler already merged them into OperationSubstrate.prompts().
            headAssembly.substrate().operations().prompts(),
            headAssembly.substrate().conversation().capabilitiesChanges(),
            telemetry);
    // Tempdoc 560 §28 Phase 3 — the run-tier witness reads the live composed registry.
    this.witnessController = new WitnessController(headAssembly.liveRegistry());
    this.capabilitiesStreamController =
        new CapabilitiesStreamController(headAssembly.executors(),
            headAssembly.substrate().conversation().capabilitiesChanges(), telemetry);
    // Tempdoc 501 Phase 2 / Phase 37 (F7): runtime axis wiring (manifest REST + SSE +
    // well-known + per-instance history + probes) extracted into RuntimeApiRoutes.
    this.runtimeApiRoutes =
        runtimeManifestPublisher == null
            ? null
            : new io.justsearch.ui.api.routes.RuntimeApiRoutes(headAssembly.executors(), runtimeManifestPublisher);
    // Tempdoc 430 Phase 2: SSE controller for the HealthEvent stream.
    this.healthEventStreamController =
        new HealthEventStreamController(headAssembly.executors(),
            headAssembly.substrate().health().conditionStore(),
            headAssembly.substrate().health().occurrenceLog(),
            headAssembly.substrate().health().changes(),
            telemetry);
    // Slice 487 §4.3: SSE controller for the always-on intent envelope stream.
    this.intentStreamController =
        new IntentStreamController(headAssembly.executors(), headAssembly.substrate().intent().changes());
    // Slice 448 phase 3: SSE controller for the DiagnosticChannel stream.
    this.diagnosticChannelStreamController =
        new DiagnosticChannelStreamController(headAssembly.executors(),
            headAssembly.substrate().channels().streams(), telemetry);
    // Slice 445: SSE controller for the indexing-jobs TABULAR stream.
    this.indexingJobsStreamController =
        new IndexingJobsStreamController(headAssembly.executors(),
            headAssembly.substrate().conversation().indexingJobsChanges(),
            headAssembly.indexingJobsBridge(),
            telemetry);
    // Slice 440: REST + SSE endpoints for the runtime-context STATE Resource.
    this.runtimeContextController =
        new RuntimeContextController(headAssembly.executors(),
            headAssembly.substrate().context().holder(),
            headAssembly.substrate().context().changes());
    // Slice 444b: REST + SSE endpoints for the operation-history HISTORY Resource.
    this.operationHistoryController =
        new OperationHistoryController(headAssembly.executors(),
            headAssembly.substrate().conversation().operationHistoryStore(),
            headAssembly.substrate().conversation().operationHistoryChanges(),
            headAssembly::operationOutcome);
    // Tempdoc 550 C3 / 655: shared pending-authorization registry — the backend records a
    // gated dispatch here and the FE approves by its id, so a capsule can only be minted
    // against an op the backend actually gated (WA-5). Shared between the invoke
    // controller (creates pendings on 428), the approve controller (consumes them), AND
    // (tempdoc 655) the MCP tool surface — sourced from the substrate now, not constructed
    // locally, so the MCP path reaches the exact same store rather than one it can't see.
    io.justsearch.app.services.intent.PendingAuthorizationStore pendingAuthorizationStore =
        headAssembly.substrate().conversation().pendingAuthorizationStore();
    // Tempdoc 550 Slice A1 (Authorize face) + C3: consent-capsule mint / approve endpoint.
    this.authorizationController =
        new AuthorizationController(
            headAssembly.substrate().conversation().consentCapsuleService(),
            pendingAuthorizationStore,
            // Tempdoc 550 thesis IV: "allow always" records a durable grant the gate later honors.
            headAssembly.substrate().conversation().durableGrantStore(),
            // Tempdoc 655: lets an approval with no client-side args to replay (an MCP-originated
            // pending) ask the server to complete the dispatch itself, using the SAME catalogs +
            // dispatcher OperationsController and McpToolSurface already use.
            headAssembly.substrate().operations().executor(),
            List.of(
                headAssembly.substrate().operations().operations(),
                headAssembly.substrate().operations().agentTools()),
            admission);
    // Slice 494: per-class advisory SSE controllers.
    this.operationCompletedAdvisoryStreamController =
        new AdvisoryStreamController(headAssembly.executors(),
            io.justsearch.app.observability.advisory.OperationCompletionProjector.CLASS_ID,
            headAssembly.substrate().advisory().logs().get(
                io.justsearch.app.observability.advisory.OperationCompletionProjector.CLASS_ID),
            headAssembly.substrate().advisory().changes(),
            telemetry);
    this.healthRecoverableAdvisoryStreamController =
        new AdvisoryStreamController(headAssembly.executors(),
            io.justsearch.app.observability.advisory.HealthRecoveryProjector.CLASS_ID,
            headAssembly.substrate().advisory().logs().get(
                io.justsearch.app.observability.advisory.HealthRecoveryProjector.CLASS_ID),
            headAssembly.substrate().advisory().changes(),
            telemetry);
    // Tempdoc 655 long-term design pass: the third advisory class (a pending approval waiting).
    // Fix: the AdvisoryLog ring buffer only ever APPENDS (creation-time projection); it is
    // never reconciled against PendingAuthorizationStore's live set when a pending is
    // approved/consumed or lazily swept as expired. Without a liveness filter, a late or
    // reconnecting subscriber's snapshot replays "Approval requested" for pendings that are
    // already resolved. PendingAuthorizationStore stays the single source of truth for
    // liveness — peek() already fails closed on consumed/unknown/expired ids (§DEFAULT_TTL),
    // so the filter needs no independent notion of "resolved."
    this.authorizationPendingAdvisoryStreamController =
        new AdvisoryStreamController(headAssembly.executors(),
            io.justsearch.app.observability.advisory.PendingAuthorizationAdvisoryProjector
                .CLASS_ID,
            headAssembly.substrate().advisory().logs().get(
                io.justsearch.app.observability.advisory.PendingAuthorizationAdvisoryProjector
                    .CLASS_ID),
            headAssembly.substrate().advisory().changes(),
            telemetry,
            (io.justsearch.app.observability.advisory.AdvisoryRecord record) -> {
              Object pendingId = record.classExtras().get("pendingId");
              return pendingId instanceof String id && pendingAuthorizationStore.peek(id).isPresent();
            });
    // Slice 447-impl-D: derived inverse Resource — Operation → Conditions referencing it.
    this.conditionRecoveryIndexController =
        new ConditionRecoveryIndexController(headAssembly.executors(),
            headAssembly.substrate().health().conditionStore(),
            headAssembly.substrate().health().recoveryIndexChanges());
    // Slice 447-followup-tier3-tooling §A: eval-mode-only synthetic-trip primitive.
    this.debugConditionController =
        new DebugConditionController(
            headAssembly.substrate().health().conditionStore(),
            headAssembly.substrate().health().changes(),
            headAssembly.substrate().health().headSource(),
            java.time.Clock.systemUTC());
    // Slice 3a.1.4 Phase 5: REST + SSE endpoints for the worker.job_queue.depth
    // TIMESERIES Resource (the canonical first instance proving the substrate).
    this.jobQueueDepthMetricController =
        new JobQueueDepthMetricController(headAssembly.executors(),
            headAssembly.substrate().metrics().jobQueueDepthHolder(),
            headAssembly.substrate().metrics().jobQueueDepthChanges());
    // Slice 3a.1.4b cohort: REST + SSE endpoints for the three follow-up TIMESERIES
    // Resources.
    this.documentsIndexedRateMetricController =
        new DocumentsIndexedRateMetricController(headAssembly.executors(),
            headAssembly.substrate().metrics().documentsIndexedRateHolder(),
            headAssembly.substrate().metrics().documentsIndexedRateChanges());
    this.gpuUtilizationMetricController =
        new GpuUtilizationMetricController(headAssembly.executors(),
            headAssembly.substrate().metrics().gpuUtilizationHolder(),
            headAssembly.substrate().metrics().gpuUtilizationChanges());
    this.gpuMemoryUtilizationMetricController =
        new GpuMemoryUtilizationMetricController(headAssembly.executors(),
            headAssembly.substrate().metrics().gpuMemoryUtilizationHolder(),
            headAssembly.substrate().metrics().gpuMemoryUtilizationChanges());
    // Slice 3a.1.2: OperationsController dispatches to the registered handler set
    // (CoreOperationCatalog admin seeds + AgentToolsOperationCatalog tool migrations)
    // via HeadAssembly.operationExecutor().
    this.operationsController =
        new OperationsController(
            List.of(
                headAssembly.substrate().operations().operations(),
                headAssembly.substrate().operations().agentTools()),
            headAssembly.substrate().operations().executor(),
            java.time.Clock.systemUTC(),
            pendingAuthorizationStore,
            headAssembly.substrate().conversation().pendingAuthorizationChanges());
    // Tempdoc 550 Slice F2 + thesis III (Preview face): evaluate availability per op, and read
    // the trust gate from the ONE shared IntentGateEvaluator the dispatcher enforces with — so
    // the prediction (incl. the live Global Hard Stop) cannot drift from enforcement (F1 gone).
    this.operationPreviewController =
        new OperationPreviewController(
            List.of(
                headAssembly.substrate().operations().operations(),
                headAssembly.substrate().operations().agentTools()),
            new io.justsearch.app.services.registry.preview.ConditionAvailabilityProbe(
                headAssembly.substrate().health().conditionStore()),
            headAssembly.substrate().conversation().intentGateEvaluator());
    // Tempdoc 550 Slice C1 (Outcome face): unified action-ledger read-view.
    this.actionLedgerController =
        new ActionLedgerController(headAssembly.executors(),
            headAssembly.substrate().conversation().operationHistoryStore(),
            headAssembly.substrate().conversation().navigationHistoryStore(),
            headAssembly.substrate().conversation().authorizationOutcomeStore(),
            // Tempdoc 550 G3/G4/G5: the unified live change-stream feeding /api/action-ledger/stream.
            headAssembly.substrate().conversation().actionLedgerChangeRegistry(),
            java.time.Clock.systemUTC());
    // Tempdoc 561 P-A/P-B (correction): the unified thread is a READ-TIME projection over the
    // records that already exist — ConversationStore (chat) + AgentRunStore (agent, via
    // AgentService) — joined by conversationId. No new store.
    this.interactionThreadController =
        new InteractionThreadController(headAssembly.operationAttempts(),
            // Tempdoc 727 (fix): the live cipher — a projection of StoreCatalog.CONVERSATIONS's
            // recoverability class (AUTHORED), same as ConversationApiAssembly's build (:204-209),
            // NOT the single-arg ctor's disabled() default. The single-arg ctor permanently disables
            // the cipher (key.enabled() always false), so StoreCipher.open throws KeyLockedException
            // on the FIRST sealed line unconditionally — once encryption is enabled this 500'd
            // GET /api/thread/{id} for EVERY read, unlock or not. ConversationApiAssembly builds its
            // own correctly-ciphered FileConversationStore instance over the same conversationsPath;
            // consolidating the two instances into one is a follow-up (bigger diff/risk than this
            // release-branch fix — see LocalApiServer :198/:242 construction order).
            new io.justsearch.app.services.conversation.FileConversationStore(
                indexBasePath.resolveSibling("conversations"),
                headAssembly.storeCipher(
                    io.justsearch.agent.api.encryption.StoreCatalog.CONVERSATIONS.recoverability())),
            headAssembly.core().agent() != null
                ? headAssembly.core().agent()
                : io.justsearch.agent.api.AgentService.unavailable(),
            headAssembly.executors(), admission);
    // Tempdoc 778 — the local feedback-capture flag surface, reading/writing the ONE settings
    // authority (nullable on the test-only path where HeadAssembly built none).
    this.feedbackCaptureController =
        new FeedbackCaptureController(headAssembly.feedbackCaptureSettings());
    // Tempdoc 561 P-E: the ONE memory authority — the same instance the core_remember agent tool
    // writes to (constructed in HeadAssembly), so the surface shows exactly what the assistant
    // learned (no second FileMemoryStore instance that would diverge from the producer).
    this.memoryController = new MemoryController(headAssembly.memoryStore());
    // Tempdoc 550 E2: operator control for the Global Hard Stop.
    this.hardStopController =
        new HardStopController(headAssembly.substrate().conversation().globalHardStop());
    // Tempdoc 662: the cross-channel multiplexer — aggregates the 5 always-on streams above
    // (intent, the two advisory classes, action-ledger, indexing-jobs) onto ONE physical
    // connection so the FE no longer holds 5 always-on EventSources against the browser's
    // ~6-per-host pool. Built last in this constructor because it depends on the controller
    // instances constructed above (reuses their channel()/snapshotExtras() accessors, not a
    // forked copy of their channel-lookup or projection logic).
    this.shellEventsStreamController =
        new ShellEventsStreamController(headAssembly.executors(),
            headAssembly.substrate().intent().changes(),
            operationCompletedAdvisoryStreamController,
            healthRecoverableAdvisoryStreamController,
            authorizationPendingAdvisoryStreamController,
            actionLedgerController,
            indexingJobsStreamController,
            headAssembly.substrate().conversation().pendingAuthorizationChanges());
  }

  /** Binds every cohort route. All controllers are non-null; only runtimeApiRoutes can be null. */
  @Override
  public void register(Javalin app) {
    java.util.Set<String> before = RouteManifestController.handlerPaths(app);
    // Slice 3a.1.2: Operation invocation boundary.
    app.post("/api/operations/{id}/invoke", operationsController::handleInvoke);
    app.post("/api/undo/{id}", operationsController::handleUndo);

    // Tempdoc 429 §E.8.a + §F.9 closure: registry catalog endpoints.
    app.get("/api/registry/operations", registryController::handleOperations);
    app.get("/api/registry/resources", registryController::handleResources);
    app.get("/api/registry/prompts", registryController::handlePrompts);
    // Slice 448 phase 2: fourth registry primitive endpoint.
    app.get("/api/registry/diagnostic-channels", registryController::handleDiagnosticChannels);
    // Slice 449 phase 2: Surface Manifest catalog endpoint.
    app.get("/api/registry/surfaces", registryController::handleSurfaces);
    // Slice 491 §9.D Phase E (C0): ConversationShape catalog endpoint.
    app.get("/api/registry/shapes", registryController::handleShapes);
    // Tempdoc 565 §26.C: the workflow catalog endpoint feeding the run-window's workflow picker.
    app.get("/api/registry/workflows", registryController::handleWorkflows);
    // Tempdoc 560 §28 Phase 3 — run-tier witness observability (NOT a gate).
    app.get("/api/registry/witness", witnessController::handle);

    // Tempdoc 521 §16.8 — /infra/capabilities GET + SSE stream variant.
    io.justsearch.ui.api.routes.InfraRoutes.register(app, headAssembly, capabilitiesStreamController);

    // Tempdoc 501 Phase 37 (F7): manifest REST + SSE + well-known + history + probes.
    if (runtimeApiRoutes != null) {
      runtimeApiRoutes.register(app);
    }

    // Tempdoc 430 Phase 2: HealthEvent SSE stream.
    app.sse("/api/health/events/stream", healthEventStreamController::handle);

    // Slice 487 §4.3: always-on intent envelope SSE stream.
    app.sse("/api/intent/stream", intentStreamController::handle);

    // Slice 448 phase 3: DiagnosticChannel SSE — V1 ships a single channel (core.engine-log).
    app.sse(
        "/api/diagnostic-channels/engine-log/stream",
        sseClient ->
            diagnosticChannelStreamController.handle(
                sseClient,
                io.justsearch.app.observability.diagnostic.EngineLogDiagnosticChannelCatalog
                    .ENGINE_LOG_ID));

    // Slice 445: indexing-jobs TABULAR SSE stream.
    app.sse("/api/indexing-jobs/stream", indexingJobsStreamController::handle);

    // Slice 440: runtime-context REST + SSE endpoints (STATE Resource: replace-only stream).
    app.get("/api/runtime-context", runtimeContextController::handleGet);
    app.sse("/api/runtime-context/stream", runtimeContextController::handleStream);

    // Slice 447-impl-D + §X.11.5 Phase 4: condition-recovery-index derived inverse Resource.
    app.get("/api/condition-recovery-index", conditionRecoveryIndexController::handle);
    app.sse("/api/condition-recovery-index/stream", conditionRecoveryIndexController::handleStream);

    // Slice 447-followup-tier3-tooling §A: synthetic condition-trip primitives (eval-mode gated).
    app.post("/api/debug/trip-condition", debugConditionController::handleTrip);
    app.post("/api/debug/clear-condition", debugConditionController::handleClear);

    // Tempdoc 550 Slice A1 (Authorize face): mint a consent capsule on user approval.
    app.post("/api/authorizations/approve", authorizationController::handleApprove);
    // Tempdoc 655 fix pass: point-to-point fetch of a pending's decision content by id — the
    // SSE broadcast deliberately omits it (privacy boundary); this is where a subscriber fetches
    // it before presenting the approval ceremony.
    app.get("/api/authorizations/pending/{id}", authorizationController::handlePeekPending);
    // Tempdoc 560 §28 (4d) — durable-grant management surface (list / grant / revoke).
    app.get("/api/authorizations/grants", authorizationController::handleListGrants);
    app.post("/api/authorizations/grants", authorizationController::handleGrant);
    app.delete("/api/authorizations/grants", authorizationController::handleRevokeGrant);

    // Tempdoc 550 Slice F2 (Preview face): evaluated availability + risk for an op.
    app.get("/api/operations/{id}/preview", operationPreviewController::handlePreview);

    // Tempdoc 561 P-A/P-B (Slice 1.2): GET /api/thread/{id} — the unified thread projection.
    app.get("/api/thread/{id}", interactionThreadController::handleGet);
    // Tempdoc 561 P-D2: GET /api/presence?since=<ISO> — the render-on-return inbox source.
    app.get("/api/presence", interactionThreadController::handlePresence);
    // Tempdoc 561 P-D2: POST /api/presence/run — the non-interactive background-run trigger.
    app.post("/api/presence/run", interactionThreadController::handlePresenceRun);
    // Tempdoc S4b (Search Thread): POST /api/thread/{id}/events — the FE write path for a thread
    // event kind that does not originate from the agent loop (today: kind="SEARCH").
    app.post("/api/thread/{id}/events", interactionThreadController::handlePostEvent);

    // Tempdoc 778 — the default-on local feedback-capture flag + privacy note.
    app.get("/api/feedback/capture", feedbackCaptureController::handleGet);
    app.post("/api/feedback/capture", feedbackCaptureController::handlePost);

    // Tempdoc 561 P-E: the learned-memory user surface — inspect / record / forget.
    app.get("/api/memory", memoryController::handleList);
    app.post("/api/memory", memoryController::handleRemember);
    app.delete("/api/memory/{id}", memoryController::handleForget);

    // Tempdoc 550 Slice C1 (Outcome face): unified action-ledger read-view.
    app.get("/api/action-ledger", actionLedgerController::handleGet);
    // Tempdoc 550 G3/G4/G5: live read-view — receipt/timeline/undo/trust-audit subscribe here.
    app.sse("/api/action-ledger/stream", actionLedgerController::handleStream);
    // Tempdoc 550 thesis I (process-spanning): the FE ingests local effects into the ONE log.
    app.post("/api/action-ledger/events", actionLedgerController::handlePostEvent);

    // Tempdoc 550 E2: Global Hard Stop operator control (lattice deny-all-non-user).
    app.get("/api/agent/hard-stop", hardStopController::handleGet);
    app.post("/api/agent/hard-stop", hardStopController::handlePost);

    // Slice 444b: operation-history REST + SSE endpoints (HISTORY Resource: append stream).
    app.get("/api/operation-history", operationHistoryController::handleGet);
    app.sse("/api/operation-history/stream", operationHistoryController::handleStream);
    app.get("/api/operation-history/{operationKey}", operationHistoryController::handleOutcome);

    // Slice 494: per-class advisory SSE endpoints.
    app.sse(
        "/api/advisory/operation-completed/stream",
        operationCompletedAdvisoryStreamController::handle);
    app.sse(
        "/api/advisory/health-recoverable/stream",
        healthRecoverableAdvisoryStreamController::handle);
    app.sse(
        "/api/advisory/authorization-pending/stream",
        authorizationPendingAdvisoryStreamController::handle);

    // Tempdoc 662: cross-channel multiplexer aggregating the 6 always-on streams above (intent,
    // the three advisory classes, action-ledger, indexing-jobs) onto ONE physical connection. The
    // individual routes above stay live (existing direct consumers, e.g. tooling, are
    // unaffected); the FE shell migrates onto this one instead of opening all of them.
    app.sse("/api/shell-events/stream", shellEventsStreamController::handle);

    // Slice 3a.1.4 Phase 5 + 3a.1.4b cohort: TIMESERIES Resource REST + SSE routes.
    app.get("/api/metrics/worker.job_queue.depth", jobQueueDepthMetricController::handleGet);
    app.sse(
        "/api/metrics/worker.job_queue.depth/stream", jobQueueDepthMetricController::handleStream);
    app.get(
        "/api/metrics/worker.documents.indexed.rate_per_sec",
        documentsIndexedRateMetricController::handleGet);
    app.sse(
        "/api/metrics/worker.documents.indexed.rate_per_sec/stream",
        documentsIndexedRateMetricController::handleStream);
    app.get("/api/metrics/gpu.utilization.percent", gpuUtilizationMetricController::handleGet);
    app.sse(
        "/api/metrics/gpu.utilization.percent/stream",
        gpuUtilizationMetricController::handleStream);
    app.get(
        "/api/metrics/gpu.memory.utilization.percent",
        gpuMemoryUtilizationMetricController::handleGet);
    app.sse(
        "/api/metrics/gpu.memory.utilization.percent/stream",
        gpuMemoryUtilizationMetricController::handleStream);
    java.util.Set<String> after = RouteManifestController.handlerPaths(app);
    after.removeAll(before);
    this.ownedPaths.addAll(after);
  }

  @Override
  public java.util.Set<String> ownedRoutePaths() {
    return java.util.Set.copyOf(ownedPaths);
  }

  @Override
  public String moduleName() {
    return "ResourceApiModule";
  }

  /** Stops every cohort SSE heartbeat scheduler. Best-effort; logs and continues on failure. */
  @Override
  public void shutdown() {
    shutdownQuietly("CapabilitiesStreamController", capabilitiesStreamController::shutdown);
    if (runtimeApiRoutes != null) {
      shutdownQuietly(
          "RuntimeManifestStreamController", runtimeApiRoutes.streamController()::shutdown);
    }
    shutdownQuietly("IndexingJobsStreamController", indexingJobsStreamController::shutdown);
    shutdownQuietly("HealthEventStreamController", healthEventStreamController::shutdown);
    shutdownQuietly("IntentStreamController", intentStreamController::shutdown);
    shutdownQuietly("InteractionThreadController", interactionThreadController::shutdown);
    shutdownQuietly("ConditionRecoveryIndexController", conditionRecoveryIndexController::shutdown);
    shutdownQuietly("DiagnosticChannelStreamController", diagnosticChannelStreamController::shutdown);
    shutdownQuietly("RuntimeContextController", runtimeContextController::shutdown);
    shutdownQuietly("OperationHistoryController", operationHistoryController::shutdown);
    shutdownQuietly("ActionLedgerController", actionLedgerController::shutdown);
    shutdownQuietly(
        "Advisory operation-completed stream", operationCompletedAdvisoryStreamController::shutdown);
    shutdownQuietly(
        "Advisory health-recoverable stream", healthRecoverableAdvisoryStreamController::shutdown);
    shutdownQuietly(
        "Advisory authorization-pending stream",
        authorizationPendingAdvisoryStreamController::shutdown);
    shutdownQuietly("ShellEventsStreamController", shellEventsStreamController::shutdown);
    shutdownQuietly("JobQueueDepthMetricController", jobQueueDepthMetricController::shutdown);
    shutdownQuietly(
        "DocumentsIndexedRateMetricController", documentsIndexedRateMetricController::shutdown);
    shutdownQuietly("GpuUtilizationMetricController", gpuUtilizationMetricController::shutdown);
    shutdownQuietly(
        "GpuMemoryUtilizationMetricController", gpuMemoryUtilizationMetricController::shutdown);
  }

  private static void shutdownQuietly(String name, Runnable shutdown) {
    try {
      shutdown.run();
    } catch (RuntimeException e) {
      log.warn("{}.shutdown failed: {}", name, e.getMessage());
    }
  }
}
