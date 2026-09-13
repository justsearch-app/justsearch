/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.agent.api.registry.IntentSourceCatalog;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.agent.api.registry.PromptCatalog;
import io.justsearch.agent.api.registry.RequiredCapability;
import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.agent.api.registry.TrustEvaluator;
import io.justsearch.app.observability.CapabilitiesChangeRegistry;
import io.justsearch.app.observability.advisory.AdvisoryChangeRegistry;
import io.justsearch.app.observability.advisory.AdvisoryClassId;
import io.justsearch.app.observability.advisory.AdvisoryClassRegistry;
import io.justsearch.app.observability.advisory.AdvisoryLog;
import io.justsearch.app.observability.advisory.AdvisoryResourceCatalog;
import io.justsearch.app.observability.advisory.HealthRecoveryProjector;
import io.justsearch.app.observability.advisory.OperationCompletionProjector;
import io.justsearch.app.observability.advisory.PendingAuthorizationAdvisoryProjector;
import io.justsearch.app.observability.intent.IntentEnvelopeChangeRegistry;
import io.justsearch.app.observability.operations.OperationHistoryChangeRegistry;
import io.justsearch.app.observability.operations.OperationHistoryResourceCatalog;
import io.justsearch.app.observability.navigation.NavigationHistoryStore;
import io.justsearch.app.observability.operations.OperationHistoryStore;
import io.justsearch.app.services.intent.BackendIntentRouterImpl;
import io.justsearch.app.services.intent.CoreIntentSourceCatalog;
import io.justsearch.agent.api.registry.ConsentCapsuleAuthority;
import io.justsearch.app.services.intent.ConsentCapsuleService;
import io.justsearch.app.services.intent.CoreTrustEvaluator;
import io.justsearch.app.services.registry.executor.OperationExecutorImpl;
import io.justsearch.agent.tools.AgentToolsOperationCatalog;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import io.justsearch.app.services.registry.operations.handlers.NavigateToSurfaceHandler;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Tempdoc 519 §7 / Step 7: static phase function that initializes the operation substrate —
 * operation history, advisory cluster, prompt catalog, intent catalog/router, operation executor,
 * and the navigate-to-surface handler. Replaces {@code HeadAssembly#initOperationSubstrate()}.
 *
 * <p>Returns an {@link Output} record bundling all assigned values. The caller assigns the
 * record fields onto the bootstrap's state.
 *
 * <p>Side effect: registers the navigate-to-surface handler into the supplied {@code
 * operationHandlers} registry. The handler reads {@link BackendIntentRouter} via a supplier so
 * the registry can be used before/after the router is constructed.
 */
public final class OperationSubstrateInit {

  private OperationSubstrateInit() {}

  /** Bundled output — all values written by the original {@code initOperationSubstrate()}. */
  public record Output(
      OperationHistoryResourceCatalog operationHistoryResourceCatalog,
      // Tempdoc 571 §4c: the action-ledger Resource — the TRUST-role authority that makes the
      // Activity surface derive Altitude.TRUST from consumption (closes the §8 R1 out-of-band gap).
      io.justsearch.app.observability.ledger.ActionLedgerResourceCatalog actionLedgerResourceCatalog,
      OperationHistoryStore operationHistoryStore,
      OperationHistoryChangeRegistry operationHistoryChangeRegistry,
      AdvisoryClassRegistry advisoryClassRegistry,
      AdvisoryChangeRegistry advisoryChangeRegistry,
      AdvisoryResourceCatalog advisoryResourceCatalog,
      Map<AdvisoryClassId, AdvisoryLog> advisoryLogs,
      PromptCatalog promptCatalog,
      IntentSourceCatalog intentSourceCatalog,
      OperationDispatcher operationExecutor,
      CapabilitiesChangeRegistry capabilitiesChangeRegistry,
      IntentEnvelopeChangeRegistry intentEnvelopeChangeRegistry,
      io.justsearch.agent.api.registry.BackendIntentRouter backendIntentRouter,
      NavigationHistoryStore navigationHistoryStore,
      ConsentCapsuleAuthority consentCapsuleService,
      HealthRecoveryProjector healthRecoveryProjector,
      io.justsearch.app.observability.operations.AuthorizationOutcomeStore
          authorizationOutcomeStore,
      io.justsearch.app.observability.ledger.ActionLedgerChangeRegistry
          actionLedgerChangeRegistry,
      // Tempdoc 812 D2: the scan-rollup aggregator over that log (one durable audit row per scan).
      io.justsearch.app.observability.ledger.ScanRollupLedger scanRollupLedger,
      io.justsearch.app.services.registry.executor.GlobalHardStop globalHardStop,
      // Tempdoc 550 thesis III: the ONE intent-gate evaluator, shared with the Preview endpoint.
      io.justsearch.app.services.intent.IntentGateEvaluator intentGateEvaluator,
      // Tempdoc 550 thesis IV: durable allow-always grants, exposed for the approve endpoint.
      io.justsearch.app.services.intent.DurableGrantStore durableGrantStore,
      // Tempdoc 875 C.3: the argument scope bounding those grants, exposed so HeadAssembly can bind
      // the live indexed-root supplier once the Worker-backed IndexingService exists.
      io.justsearch.app.services.intent.IndexedRootGrantScope durableGrantScope,
      // Tempdoc 655: shared across REST (OperationsController/AuthorizationController) and MCP
      // (McpToolSurface) so a gate fired from either transport creates a pending record either
      // caller's approve endpoint can consume — one store, not two independently-wired copies.
      io.justsearch.app.services.intent.PendingAuthorizationStore pendingAuthorizationStore,
      // Tempdoc 655: live announcement of new pending records, so the always-on shell can react
      // to an MCP-originated gate it had no in-flight request for.
      io.justsearch.app.observability.operations.PendingAuthorizationChangeRegistry
          pendingAuthorizationChangeRegistry) {}

  /**
   * Initializes the operation substrate and registers the navigate-to-surface handler.
   *
   * @param operationHandlers handler registry shared by the bootstrap; the navigate-to-surface
   *     handler is registered into it (read-write side effect).
   * @param operationCatalog admin-seed operation catalog (already assembled).
   * @param agentToolsCatalog agent-tool operation catalog (already assembled).
   * @param capabilityResolver resolves {@link RequiredCapability} → available boolean — typically
   *     reads from {@code WorkerCapability} + {@code InferenceCapability}.
   * @return bundled substrate values for the caller to assign into bootstrap state.
   */
  public static Output run(io.justsearch.app.api.operations.OperationAttemptRunner attempts,
      io.justsearch.app.api.EngineAdmissionService admission, io.justsearch.core.execution.EngineExecutorRegistry executors,
      HandlerRegistry operationHandlers,
      OperationCatalog operationCatalog,
      OperationCatalog agentToolsCatalog,
      Function<RequiredCapability, Boolean> capabilityResolver,
      io.justsearch.agent.api.registry.SurfaceCatalog coreSurfaceCatalog,
      io.justsearch.agent.api.encryption.StoreCipher preparationCipher) {
    OperationHistoryResourceCatalog operationHistoryResourceCatalog =
        new OperationHistoryResourceCatalog();
    // Tempdoc 571 §4c: the action-ledger Resource — the TRUST-role authority the Activity surface
    // consumes so its Altitude.TRUST is DERIVED, not declared (closes the §8 R1 out-of-band gap).
    io.justsearch.app.observability.ledger.ActionLedgerResourceCatalog actionLedgerResourceCatalog =
        new io.justsearch.app.observability.ledger.ActionLedgerResourceCatalog();
    OperationHistoryStore operationHistoryStore = new OperationHistoryStore();
    // Tempdoc 550 Slice F1 (Outcome face): the Navigation sibling ledger.
    NavigationHistoryStore navigationHistoryStore = new NavigationHistoryStore();
    OperationHistoryChangeRegistry operationHistoryChangeRegistry =
        new OperationHistoryChangeRegistry();
    OperationCompletionProjector operationCompletionProjector = new OperationCompletionProjector();
    HealthRecoveryProjector healthRecoveryProjector = new HealthRecoveryProjector();
    // Tempdoc 655 long-term design pass: the "a pending approval is waiting" advisory class —
    // REQUIRES_ACK, so it lands in the inbox/badge as a passive, discoverable complement to the
    // direct approval-ceremony dialog (see PendingAuthorizationAdvisoryProjector's doc comment).
    PendingAuthorizationAdvisoryProjector pendingAuthorizationAdvisoryProjector =
        new PendingAuthorizationAdvisoryProjector();
    AdvisoryClassRegistry advisoryClassRegistry =
        AdvisoryClassRegistry.builder()
            .register(operationCompletionProjector)
            .register(healthRecoveryProjector)
            .register(pendingAuthorizationAdvisoryProjector)
            .build();
    AdvisoryChangeRegistry advisoryChangeRegistry =
        new AdvisoryChangeRegistry(advisoryClassRegistry, Clock.systemUTC());
    AdvisoryResourceCatalog advisoryResourceCatalog = new AdvisoryResourceCatalog();
    Map<AdvisoryClassId, AdvisoryLog> advisoryLogs =
        Map.of(
            OperationCompletionProjector.CLASS_ID,
            new AdvisoryLog(),
            HealthRecoveryProjector.CLASS_ID,
            new AdvisoryLog(),
            PendingAuthorizationAdvisoryProjector.CLASS_ID,
            new AdvisoryLog());
    PromptCatalog promptCatalog = PromptCatalog.of("core", List.of());
    IntentSourceCatalog intentSourceCatalog = CoreIntentSourceCatalog.catalog();
    TrustEvaluator trustEvaluator = new CoreTrustEvaluator();
    // Tempdoc 550 Slice A1 (Authorize face): consent-capsule verifier. The lattice ALSO
    // accepts a valid bound capsule (additive to the legacy non-blank token path).
    ConsentCapsuleService consentCapsuleService = new ConsentCapsuleService();
    // Tempdoc 550 Outcome face: the gate-decision ledger sibling. The executor records every
    // non-AUTO trust-gate firing here so the action ledger / trust audit see gate decisions,
    // not only completed-dispatch outcomes.
    io.justsearch.app.observability.operations.AuthorizationOutcomeStore authorizationOutcomeStore =
        new io.justsearch.app.observability.operations.AuthorizationOutcomeStore();
    // Tempdoc 550 G3/G4/G5: the unified live change-stream. The three federated sources
    // (operation history, navigation, gate firings) fan in here; the controller's /stream
    // endpoint subscribes once so the receipt/timeline/undo/trust-audit are live read-views.
    // Tempdoc 812 D1: the durable audit journal for the actor kinds (grant / gate / operation),
    // written synchronously beside the ring at this one fan-in point. Mode-aware exactly like
    // DurableGrantStore above — READ_WRITE persists under <dataDir>/audit, IN_MEMORY (prod/CI
    // isolation, tests) creates no files at all. The ledger's READ path then serves ring ∪
    // journal-tail, so Activity is not empty after a restart.
    io.justsearch.app.observability.ledger.ActionEventJournal actionEventJournal =
        io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.resolveMode()
                .isWritable()
            ? io.justsearch.app.observability.ledger.ActionEventJournal.persistent()
            : io.justsearch.app.observability.ledger.ActionEventJournal.disabled();
    io.justsearch.app.observability.ledger.ActionLedgerChangeRegistry actionLedgerChangeRegistry =
        new io.justsearch.app.observability.ledger.ActionLedgerChangeRegistry(actionEventJournal);
    // Tempdoc 812 D2: the scan-rollup aggregator listens on that one log for terminal per-document
    // indexing outcomes and emits ONE durable `operation`-kind scan-completion row per directory
    // scan — which the D1 journal above then persists for free, because a scan rollup IS an
    // operation-kind event. Owned here (beside the log it projects) rather than on the API
    // composition root, so its quiescence sweeper's lifetime is the substrate's.
    io.justsearch.app.observability.ledger.ScanRollupLedger scanRollupLedger =
        new io.justsearch.app.observability.ledger.ScanRollupLedger(executors, actionLedgerChangeRegistry);
    // Tempdoc 550 E2: process-wide emergency stop the lattice consults (default released).
    io.justsearch.app.services.registry.executor.GlobalHardStop globalHardStop =
        new io.justsearch.app.services.registry.executor.GlobalHardStop();
    // Tempdoc 550 thesis IV + 560 §28 — the durable "allow-always" grant: the second Grant-model
    // member. `persistent()` survives restarts (mode-aware: IN_MEMORY under prod/CI isolation).
    io.justsearch.app.services.intent.DurableGrantStore durableGrantStore =
        io.justsearch.app.services.intent.DurableGrantStore.persistent();
    // Tempdoc 655: one PendingAuthorizationStore + one broadcast registry, shared by the REST
    // gate path (OperationsController/AuthorizationController) and the MCP gate path
    // (McpToolSurface) — mirrors the capsule/grant sharing above, closing the gap where MCP had
    // no way to surface a gate firing for human approval at all.
    io.justsearch.app.services.intent.PendingAuthorizationStore pendingAuthorizationStore =
        new io.justsearch.app.services.intent.PendingAuthorizationStore();
    io.justsearch.app.observability.operations.PendingAuthorizationChangeRegistry
        pendingAuthorizationChangeRegistry =
            new io.justsearch.app.observability.operations.PendingAuthorizationChangeRegistry();
    // Tempdoc 655 long-term design pass: one bootstrap-time subscription onto the registry both
    // transports already broadcast into — mirrors HealthRecoveryProjector's wiring shape below.
    // Both MCP and browser-originated gates get advisory coverage for free; no edits needed at
    // either call site (McpToolSurface / OperationsController).
    pendingAuthorizationChangeRegistry.subscribeTyped(
        event ->
            advisoryChangeRegistry
                .project(pendingAuthorizationAdvisoryProjector, event)
                .ifPresent(
                    record ->
                        advisoryLogs
                            .get(pendingAuthorizationAdvisoryProjector.classId())
                            .append(record)));
    // One audit: capsule + durable grants record their lifecycle into the one action-event log.
    consentCapsuleService.setGrantEventSink(actionLedgerChangeRegistry::broadcastActionEvent);
    durableGrantStore.setGrantEventSink(actionLedgerChangeRegistry::broadcastActionEvent);
    // One revocation path: engaging the hard stop revokes every non-user grant — single-use capsules
    // AND durable allow-always grants — matching the gate's UNTRUSTED hard-stop scope.
    globalHardStop.setOnEngage(
        () -> {
          consentCapsuleService.revokeNonUser();
          durableGrantStore.revokeNonUser();
        });
    // Tempdoc 550 F5: the per-kind stores fan every append into the one action-event log via an
    // append-listener — so an emit site cannot append-without-feeding-the-ledger (the divergence
    // the separate broadcast calls risked). The ledger is now downstream of the store, structurally.
    // Tempdoc 561 P-A/P-B: AGENT_LOOP operations are NOT fanned into the unified ledger here — their
    // ledger rows are projected from the ONE durable agent record (AgentRunStore) by
    // AgentRunLedgerProjector (wired in HeadAssembly), so the thread + History + Timeline derive from
    // one source and cannot disagree. OperationHistoryStore still APPENDS the agent entry (undo /
    // operation-detail stay whole); only its fan-in into the unified log is suppressed for agent rows.
    operationHistoryStore.addAppendListener(
        entry -> {
          if (entry.provenance().transport() != TransportTag.AGENT_LOOP) {
            actionLedgerChangeRegistry.broadcastOperation(entry);
          }
        });
    navigationHistoryStore.addAppendListener(actionLedgerChangeRegistry::broadcastNavigation);
    authorizationOutcomeStore.addAppendListener(actionLedgerChangeRegistry::broadcastGate);
    OperationExecutorImpl operationExecutorImpl =
        new OperationExecutorImpl(attempts, admission,
            operationHandlers,
            entry -> {
              // F5: append fans into the one log via the store's listener — no separate call here.
              operationHistoryStore.append(entry);
              operationHistoryChangeRegistry.broadcast(entry);
            },
            Map.of(
                new ResourceRef("core.advisory-operation-completed"),
                event ->
                    advisoryChangeRegistry
                        .project(operationCompletionProjector, event)
                        .ifPresent(
                            record ->
                                advisoryLogs.get(operationCompletionProjector.classId()).append(record))),
            Clock.systemUTC(),
            trustEvaluator,
            intentSourceCatalog,
            capabilityResolver,
            consentCapsuleService,
            // F5: append fans the gate firing into the one log via the store's listener.
            authorizationOutcomeStore::append, preparationCipher);
    operationExecutorImpl.setGlobalHardStop(globalHardStop);
    // Tempdoc 550 thesis IV: the gate consults the durable allow-always grants before requiring a
    // fresh capsule. Tempdoc 875 C.3: paired with the argument scope that bounds them — the wiring
    // API requires both, so grants can never be installed without a containment answer. The scope
    // governs `core.ingest-files` (the one agent tool that takes filesystem paths as arguments); the
    // governed set is injected so the scope carries no catalog knowledge. It stays UNBOUND (⇒ every
    // governed invocation confirms) until HeadAssembly binds the live indexed roots.
    io.justsearch.app.services.intent.IndexedRootGrantScope durableGrantScope =
        new io.justsearch.app.services.intent.IndexedRootGrantScope(
            java.util.Set.of(AgentToolsOperationCatalog.INGEST_FILES));
    operationExecutorImpl.setDurableGrantStore(durableGrantStore, durableGrantScope);
    // Tempdoc 550 thesis III: expose the executor's ONE intent-gate evaluator (built from the same
    // TrustEvaluator + IntentSourceCatalog, with the hard stop now forwarded) so the Preview
    // endpoint reads the SAME instance — preview and enforcement cannot drift.
    io.justsearch.app.services.intent.IntentGateEvaluator intentGateEvaluator =
        operationExecutorImpl.intentGateEvaluator();
    OperationDispatcher operationExecutor = operationExecutorImpl;
    CapabilitiesChangeRegistry capabilitiesChangeRegistry = new CapabilitiesChangeRegistry();
    IntentEnvelopeChangeRegistry intentEnvelopeChangeRegistry = new IntentEnvelopeChangeRegistry();
    List<Operation> mergedOps = new ArrayList<>(operationCatalog.definitions());
    mergedOps.addAll(agentToolsCatalog.definitions());
    OperationCatalog mergedCatalog = OperationCatalog.of("core", mergedOps);
    BackendIntentRouterImpl backendIntentRouterImpl =
        new BackendIntentRouterImpl(
            mergedCatalog,
            operationExecutor,
            intentSourceCatalog,
            intentEnvelopeChangeRegistry,
            navigationHistoryStore,
            // Tempdoc 550 WA-4: gate Navigation through the lattice keyed on the target
            // surface's RiskTier (all surfaces LOW today → AUTO → behavior-neutral).
            trustEvaluator,
            coreSurfaceCatalog);
    // Tempdoc 550 F5: navigations reach the one log via the navigationHistoryStore append-listener
    // (wired above), not a router-held ledger reference — so the store and ledger cannot diverge.
    io.justsearch.agent.api.registry.BackendIntentRouter backendIntentRouter =
        backendIntentRouterImpl;
    final io.justsearch.agent.api.registry.BackendIntentRouter routerForHandler = backendIntentRouter;
    operationHandlers.register(
        CoreOperationCatalog.NAVIGATE_TO_SURFACE,
        new NavigateToSurfaceHandler(() -> routerForHandler));
    return new Output(
        operationHistoryResourceCatalog,
        actionLedgerResourceCatalog,
        operationHistoryStore,
        operationHistoryChangeRegistry,
        advisoryClassRegistry,
        advisoryChangeRegistry,
        advisoryResourceCatalog,
        advisoryLogs,
        promptCatalog,
        intentSourceCatalog,
        operationExecutor,
        capabilitiesChangeRegistry,
        intentEnvelopeChangeRegistry,
        backendIntentRouter,
        navigationHistoryStore,
        consentCapsuleService,
        healthRecoveryProjector,
        authorizationOutcomeStore,
        actionLedgerChangeRegistry,
        scanRollupLedger,
        globalHardStop,
        intentGateEvaluator,
        durableGrantStore,
        durableGrantScope,
        pendingAuthorizationStore,
        pendingAuthorizationChangeRegistry);
  }
}
