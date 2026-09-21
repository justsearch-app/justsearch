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
import io.justsearch.agent.api.registry.ConsentCapsuleAuthority;
import io.justsearch.app.services.intent.ConsentCapsuleService;
import io.justsearch.app.services.registry.executor.OperationExecutorImpl;
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
      io.justsearch.app.services.registry.executor.GlobalHardStop globalHardStop,
      // Tempdoc 550 thesis III: the ONE intent-gate evaluator, shared with the Preview endpoint.
      io.justsearch.app.services.intent.IntentGateEvaluator intentGateEvaluator,
      // Tempdoc 550 thesis IV: durable allow-always grants, exposed for the approve endpoint.
      io.justsearch.app.services.intent.DurableGrantStore durableGrantStore,
      // The preloaded live roots scope is shared with recovery before the Worker exists.
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
   *     reads from registry-backed index and generative capability projections.
   * @return bundled substrate values for the caller to assign into bootstrap state.
   */
  public static Output run(io.justsearch.app.api.operations.OperationStore operations,
      io.justsearch.app.api.operations.OperationAttemptRunner attempts,
      io.justsearch.app.api.EngineAdmissionService admission, io.justsearch.core.execution.EngineExecutorRegistry executors,
      HandlerRegistry operationHandlers,
      OperationCatalog operationCatalog,
      OperationCatalog agentToolsCatalog,
      Function<RequiredCapability, Boolean> capabilityResolver,
      io.justsearch.agent.api.registry.SurfaceCatalog coreSurfaceCatalog,
      io.justsearch.agent.api.encryption.StoreCipher preparationCipher) {
    return run(operations, attempts, admission, executors, operationHandlers, operationCatalog,
        agentToolsCatalog, capabilityResolver, coreSurfaceCatalog, preparationCipher,
        io.justsearch.app.services.bootstrap.OperationAuthority.inMemory());
  }

  /** Consume the single pre-fork authority; this phase only adds projections and handlers. */
  public static Output run(io.justsearch.app.api.operations.OperationStore operations,
      io.justsearch.app.api.operations.OperationAttemptRunner attempts,
      io.justsearch.app.api.EngineAdmissionService admission, io.justsearch.core.execution.EngineExecutorRegistry executors,
      HandlerRegistry operationHandlers,
      OperationCatalog operationCatalog,
      OperationCatalog agentToolsCatalog,
      Function<RequiredCapability, Boolean> capabilityResolver,
      io.justsearch.agent.api.registry.SurfaceCatalog coreSurfaceCatalog,
      io.justsearch.agent.api.encryption.StoreCipher preparationCipher,
      io.justsearch.app.services.bootstrap.OperationAuthority authority) {
    java.util.stream.Stream.concat(operationCatalog.definitions().stream(), agentToolsCatalog.definitions().stream())
        .filter(operation -> operation.policy().declaredSurvival()
            .filter(survival -> survival == io.justsearch.core.context.EngineContext.Survival.DURABLE).isPresent())
        .forEach(operation -> attempts.requireRecoveryOwner(operation.policy().recordKind()));
    OperationHistoryResourceCatalog operationHistoryResourceCatalog =
        new OperationHistoryResourceCatalog();
    // Tempdoc 571 §4c: the action-ledger Resource — the TRUST-role authority the Activity surface
    // consumes so its Altitude.TRUST is DERIVED, not declared (closes the §8 R1 out-of-band gap).
    io.justsearch.app.observability.ledger.ActionLedgerResourceCatalog actionLedgerResourceCatalog =
        new io.justsearch.app.observability.ledger.ActionLedgerResourceCatalog();
    OperationHistoryStore operationHistoryStore = new OperationHistoryStore(operations);
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
    IntentSourceCatalog intentSourceCatalog = authority.sources();
    TrustEvaluator trustEvaluator = authority.trust();
    // Tempdoc 550 Slice A1 (Authorize face): consent-capsule verifier. The lattice ALSO
    // accepts a valid bound capsule (additive to the legacy non-blank token path).
    ConsentCapsuleService consentCapsuleService = authority.capsules();
    // Tempdoc 550 Outcome face: the gate-decision ledger sibling. The executor records every
    // non-AUTO trust-gate firing here so the action ledger / trust audit see gate decisions,
    // not only completed-dispatch outcomes.
    io.justsearch.app.observability.operations.AuthorizationOutcomeStore authorizationOutcomeStore =
        new io.justsearch.app.observability.operations.AuthorizationOutcomeStore();
    // Tempdoc 550 G3/G4/G5: the unified live change-stream. The three federated sources
    // (operation history, navigation, gate firings) fan in here; the controller's /stream
    // endpoint subscribes once so the receipt/timeline/undo/trust-audit are live read-views.
    // Tempdoc 812 D1: the durable audit journal for the actor kinds (grant / gate / operation),
    // accepted by the operation drainer after immediate live publication; other sources keep
    // synchronous fan-in. READ_WRITE persists under <dataDir>/audit; explicit IN_MEMORY creates
    // no journal files. The ledger's READ path then serves ring ∪
    // journal-tail, so Activity is not empty after a restart.
    io.justsearch.app.observability.ledger.ActionEventJournal actionEventJournal =
        io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.resolveMode()
                .isWritable()
            ? io.justsearch.app.observability.ledger.ActionEventJournal.persistent()
            : io.justsearch.app.observability.ledger.ActionEventJournal.disabled();
    io.justsearch.app.observability.ledger.ActionLedgerChangeRegistry actionLedgerChangeRegistry =
        new io.justsearch.app.observability.ledger.ActionLedgerChangeRegistry(actionEventJournal);
    // Pre-fork authority is shared with recovery; this phase only adds audit projections.
    io.justsearch.app.services.registry.executor.GlobalHardStop globalHardStop =
        authority.hardStop();
    // Tempdoc 550 thesis IV + 560 §28 — the durable "allow-always" grant: the second Grant-model
    // member. `persistent()` survives restarts (mode-aware: IN_MEMORY under prod/CI isolation).
    io.justsearch.app.services.intent.DurableGrantStore durableGrantStore =
        authority.grants();
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
    // Committed operation fan-in is attached from the durable source after Head acquires the
    // completed substrate. Navigation and authorization keep their existing source listeners.
    navigationHistoryStore.addAppendListener(actionLedgerChangeRegistry::broadcastNavigation);
    authorizationOutcomeStore.addAppendListener(actionLedgerChangeRegistry::broadcastGate);
    OperationExecutorImpl operationExecutorImpl =
        new OperationExecutorImpl(attempts, admission,
            operationHandlers,
            entry -> {
              // The source subscription owns keyed completions, including non-dispatched rows.
              // Preserve only uncommitted STORAGE_FAILED observations from the dispatcher.
              if (entry.operationKey().isPresent()) return;
              operationHistoryStore.append(entry);
              operationHistoryChangeRegistry.broadcast(entry);
              if (entry.provenance().transport() != TransportTag.AGENT_LOOP) {
                actionLedgerChangeRegistry.broadcastOperation(entry);
              }
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
            authority.evaluator(),
            capabilityResolver,
            consentCapsuleService,
            // F5: append fans the gate firing into the one log via the store's listener.
            authorizationOutcomeStore::append, preparationCipher);
    operationExecutorImpl.setGlobalHardStop(globalHardStop);
    io.justsearch.app.services.intent.IndexedRootGrantScope durableGrantScope = authority.scope();
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
        globalHardStop,
        intentGateEvaluator,
        durableGrantStore,
        durableGrantScope,
        pendingAuthorizationStore,
        pendingAuthorizationChangeRegistry);
  }

  /** Attach only after the complete substrate is acquired, so later bootstrap failures own cleanup. */
  public static io.justsearch.app.observability.operations.OperationHistoryProjector attachHistoryProjection(
      io.justsearch.app.api.operations.OperationStore operations,
      io.justsearch.core.execution.EngineExecutorRegistry executors, Output output) {
    return new io.justsearch.app.observability.operations.OperationHistoryProjector(operations,
        output.operationHistoryStore(), output.operationHistoryChangeRegistry(),
        output.actionLedgerChangeRegistry(), executors);
  }
}
