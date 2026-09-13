/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.agent.AgentLoopService;
import io.justsearch.agent.AgentRunStore;
import io.justsearch.agent.api.AgentService;
import io.justsearch.agent.api.registry.BackendIntentRouter;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.app.services.registry.emitter.AgentOperationEmitter;
import io.justsearch.agent.tools.FileOperationLog;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.observability.health.HeadHealthEventsEmitter;
import io.justsearch.telemetry.Telemetry;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Tempdoc 519 F5 follow-up: extracted from the {@code HeadAssembly} main constructor body
 * (~35 inline LOC including the holder-array circular-ref pattern). Constructs the
 * {@link AgentLoopService} when inference is configured; returns the
 * {@link AgentService#unavailable()} Null Object otherwise.
 *
 * <p>The holder-array pattern (tempdoc 415) lets the {@code agent.session.active_count} gauge
 * supplier capture a reference to the service before it exists. The supplier is invoked at
 * gauge-collection time (post-construction) so the array slot is always populated when read.
 *
 * <p>Inputs are typed explicitly to make the cross-phase dependencies visible: this wiring
 * depends on {@link OnlineAiService} (from {@code ServicePhase}), the operation catalog +
 * executor + message resolver (from {@code initOperationSubstrate}), the file-operation log
 * + agent root paths supplier + run store (constructor locals), and the
 * {@link HeadHealthEventsEmitter} + condition-context supplier + intent router (from
 * {@code initHealthSubstrate}).
 */
public final class AgentLoopWiring {

  private AgentLoopWiring() {}

  /** Compatibility wiring for isolated callers that do not compose Engine admission. */
  public static AgentService wire(
      boolean inferenceConfigured,
      OnlineAiService onlineAiService,
      OperationCatalog agentToolsCatalog,
      OperationDispatcher operationExecutor,
      Function<String, String> operationMessageResolver,
      FileOperationLog fileOperationLog,
      Function<io.justsearch.core.context.EngineContext, List<String>> agentRootPaths,
      AgentRunStore agentRunStore,
      Telemetry telemetry,
      HeadHealthEventsEmitter headHealthEventsEmitter,
      Supplier<String> conditionContextSupplier,
      BackendIntentRouter backendIntentRouter,
      io.justsearch.agent.api.registry.ConsentCapsuleAuthority consentCapsuleAuthority,
      io.justsearch.agent.api.registry.IntentPreviewer intentPreviewer,
      java.util.function.Predicate<String> availabilityProbe,
      io.justsearch.app.api.DocumentService citationDocumentService) {
    return wire(
        inferenceConfigured,
        onlineAiService,
        agentToolsCatalog,
        operationExecutor,
        operationMessageResolver,
        fileOperationLog,
        agentRootPaths,
        agentRunStore,
        telemetry,
        headHealthEventsEmitter,
        conditionContextSupplier,
        backendIntentRouter,
        consentCapsuleAuthority,
        intentPreviewer,
        availabilityProbe,
        citationDocumentService,
        null);
  }

  /**
   * Constructs the head's {@link AgentService}.
   *
   * @param inferenceConfigured whether the bootstrap decided inference is configured. When
   *     {@code false}, returns {@link AgentService#unavailable()} (the F2 Null Object).
   * @return either the live {@link AgentLoopService} or the Null Object.
   */
  public static AgentService wire(
      boolean inferenceConfigured,
      OnlineAiService onlineAiService,
      OperationCatalog agentToolsCatalog,
      OperationDispatcher operationExecutor,
      Function<String, String> operationMessageResolver,
      FileOperationLog fileOperationLog,
      Function<io.justsearch.core.context.EngineContext, List<String>> agentRootPaths,
      AgentRunStore agentRunStore,
      Telemetry telemetry,
      HeadHealthEventsEmitter headHealthEventsEmitter,
      Supplier<String> conditionContextSupplier,
      BackendIntentRouter backendIntentRouter,
      io.justsearch.agent.api.registry.ConsentCapsuleAuthority consentCapsuleAuthority,
      // Tempdoc 561 P-D1: read-only window onto the ONE intent-gate authority, late-bound into the
      // loop so the pending-approval event carries the backend GateBehavior.
      io.justsearch.agent.api.registry.IntentPreviewer intentPreviewer,
      java.util.function.Predicate<String> availabilityProbe,
      // Tempdoc 565 §3.A — the document service that backs the answer↔source citation matcher.
      io.justsearch.app.api.DocumentService citationDocumentService,
      io.justsearch.app.api.EngineAdmissionService engineAdmission) {
    if (!inferenceConfigured) {
      return AgentService.unavailable();
    }
    // Tempdoc 550 Preview face: the emitter filters its tool list by EVALUATED availability so
    // the model is not offered an operation it provably cannot run in the current state. A null
    // probe (test wiring) preserves the legacy full-list behavior.
    AgentOperationEmitter agentOperationEmitter =
        new AgentOperationEmitter(operationMessageResolver)
            .withAvailabilityProbe(availabilityProbe);
    AgentLoopService[] agentHolder = new AgentLoopService[1];
    agentHolder[0] =
        new AgentLoopService(
            onlineAiService,
            agentToolsCatalog,
            operationExecutor,
            agentOperationEmitter,
            fileOperationLog,
            agentRootPaths,
            agentRunStore,
            telemetry,
            // Tempdoc 415: gauge supplier reads from the array slot at collection time.
            () -> agentHolder[0] != null ? agentHolder[0].activeSessionCount() : 0,
            // Tempdoc 430 Phase 7: emit agent.session.* lifecycle Occurrences alongside the
            // existing AgentTelemetry counter at the AgentLoopService finally{} chokepoint.
            headHealthEventsEmitter);
    // Slice 447 §X.11.5 Phase 5: agent retrospection.
    agentHolder[0].setConditionContextSupplier(conditionContextSupplier);
    // Slice 487 Phase 1.7: agent tool-call dispatch flows through the intent layer.
    agentHolder[0].setBackendIntentRouter(backendIntentRouter);
    // Tempdoc 550 C2 step 2: the agent loop mints a bound capsule for approved tool calls.
    agentHolder[0].setConsentCapsuleAuthority(consentCapsuleAuthority);
    // Tempdoc 561 P-D1: the pending-approval event carries the backend gate verdict.
    agentHolder[0].setIntentPreviewer(intentPreviewer);
    // The agent and LocalApiServer must share the EngineRoot admission owner.
    agentHolder[0].setEngineAdmission(engineAdmission);
    // Tempdoc 565 §3.A: the terminal answer carries verifiable local-passage citations.
    // Tempdoc 799 §N.2: the cutoff comes from justsearch.citation.match_threshold. This MUST read
    // the same key as ConversationApiAssembly's StreamingCitationMatcher — 565 §15.A unified the
    // agent and RAG cutoffs precisely because a divergent local value was a defect.
    io.justsearch.configuration.resolved.ConfigStore citationConfigStore =
        io.justsearch.configuration.resolved.ConfigStore.globalOrNull();
    if (citationConfigStore == null || citationConfigStore.get() == null) {
      agentHolder[0].setCitationDocumentService(citationDocumentService);
    } else {
      agentHolder[0].setCitationDocumentService(
          citationDocumentService, citationConfigStore.get().rag().citationMatchThreshold());
    }
    return agentHolder[0];
  }
}
