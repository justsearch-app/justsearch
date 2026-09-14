/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.agent.api.registry.ConversationShape;
import io.justsearch.agent.api.registry.ConversationShapeCatalog;
import io.justsearch.agent.api.registry.DiagnosticChannel;
import io.justsearch.agent.api.registry.DiagnosticChannelCatalog;
import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.Prompt;
import io.justsearch.agent.api.registry.PromptCatalog;
import io.justsearch.agent.api.registry.Resource;
import io.justsearch.agent.api.registry.ResourceCatalog;
import io.justsearch.agent.api.registry.Surface;
import io.justsearch.agent.api.registry.SurfaceCatalog;
import io.justsearch.app.services.bootstrap.SubstrateGraph;
import io.justsearch.app.services.observability.rules.RuleRunner;
import java.util.List;
import java.util.function.Function;

/**
 * Tempdoc 519 §10 final-push: extracted from {@code HeadAssembly.assembleSubstrateGraph}
 * (~60 LOC of {@link SubstrateGraph} construction). The bootstrap supplies its 4 held phase
 * Output records + 4 pre-substrate values (operation catalogs, handler registry, message
 * resolver) + the rule runner; this assembler does the typed-record construction.
 *
 * <p>Pure assembly — no side effects. Callable from the bootstrap's main + secondary
 * constructors with the same signature.
 */
public final class SubstrateGraphAssembler {

  private SubstrateGraphAssembler() {}

  /** Assemble the SubstrateGraph from the 4 held substrate Output records + bootstrap state. */
  public static SubstrateGraph assemble(
      OperationCatalog operationCatalog,
      OperationCatalog agentToolsCatalog,
      HandlerRegistry operationHandlers,
      Function<String, String> operationMessageResolver,
      OperationSubstrateInit.Output operationOut,
      ResourceSubstrateInit.Output resourceOut,
      MetricSubstrateInit.Output metricsOut,
      HealthSubstrateInit.Output healthOut,
      RuleRunner ruleRunner,
      List<DiagnosticChannel> pluginDiagnosticChannels,
      List<Surface> pluginSurfaces,
      List<ConversationShape> pluginConversationShapes,
      List<Resource> pluginResources,
      List<Prompt> pluginPrompts) {
    return new SubstrateGraph(
        new SubstrateGraph.OperationSubstrate(
            operationCatalog,
            agentToolsCatalog,
            // Tempdoc 560 §28 Phase 2: the served Prompt catalog = core prompts + composed plugin prompts
            // (vendor-namespaced ids never collide). Merged here so the wiring layer stays untouched.
            PromptCatalog.of(
                "registry-prompts",
                java.util.stream.Stream.concat(
                        operationOut.promptCatalog().definitions().stream(), pluginPrompts.stream())
                    .toList()),
            operationOut.operationExecutor(),
            operationHandlers,
            operationMessageResolver),
        new SubstrateGraph.ResourceSubstrate(
            resourceOut.resourceCatalog(),
            resourceOut.runtimeContextResourceCatalog(),
            resourceOut.serverCapabilitiesResourceCatalog(),
            resourceOut.coreInferenceResourceCatalog(),
            operationOut.operationHistoryResourceCatalog(),
            resourceOut.indexingJobsResourceCatalog(),
            resourceOut.failedIndexingJobsResourceCatalog(),
            resourceOut.indexedRootsResourceCatalog(),
            resourceOut.conditionRecoveryIndexResourceCatalog(),
            // Tempdoc 571 §4c: the action-ledger Resource (created in OperationSubstrateInit alongside
            // the ledger change-registry) — the TRUST-role authority Activity consumes.
            operationOut.actionLedgerResourceCatalog(),
            // Tempdoc 575 §17 Face C: Brain install/pack OBSERVABLE polled-state Resources (now LIVE-served).
            resourceOut.aiInstallResourceCatalog(),
            resourceOut.aiPackImportResourceCatalog(),
            // Tempdoc 560 §28 Phase 2: composed plugin resources (vendor-namespaced ids never collide).
            ResourceCatalog.of("composed", pluginResources)),
        new SubstrateGraph.AdvisorySubstrate(
            operationOut.advisoryResourceCatalog(),
            operationOut.advisoryChangeRegistry(),
            operationOut.advisoryClassRegistry(),
            operationOut.advisoryLogs()),
        new SubstrateGraph.IntentSubstrate(
            operationOut.intentSourceCatalog(),
            operationOut.backendIntentRouter(),
            operationOut.intentEnvelopeChangeRegistry()),
        new SubstrateGraph.MetricSubstrate(
            metricsOut.jobQueueDepthMetricResourceCatalog(),
            metricsOut.jobQueueDepthMetricChangeRegistry(),
            metricsOut.jobQueueDepthMetricHolder(),
            metricsOut.documentsIndexedRateMetricResourceCatalog(),
            metricsOut.documentsIndexedRateMetricChangeRegistry(),
            metricsOut.documentsIndexedRateMetricHolder(),
            metricsOut.gpuUtilizationMetricResourceCatalog(),
            metricsOut.gpuUtilizationMetricChangeRegistry(),
            metricsOut.gpuUtilizationMetricHolder(),
            metricsOut.gpuMemoryUtilizationMetricResourceCatalog(),
            metricsOut.gpuMemoryUtilizationMetricChangeRegistry(),
            metricsOut.gpuMemoryUtilizationMetricHolder()),
        new SubstrateGraph.HealthSubstrate(
            healthOut.conditionStore(),
            healthOut.occurrenceLog(),
            healthOut.healthEventChangeRegistry(),
            healthOut.headSource(),
            resourceOut.conditionRecoveryIndexChangeRegistry(),
            healthOut.lifecycleSnapshotTap(),
            healthOut.workerSnapshotTap(),
            healthOut.headHealthEventsEmitter(),
            ruleRunner,
            healthOut.readinessReconciliationTrigger()),
        new SubstrateGraph.ChannelSubstrate(
            resourceOut.engineLogDiagnosticChannelCatalog(),
            // Tempdoc 560 §10.4: the composed plugin-contributed channels as one catalog. Empty unless
            // a plugin (e.g. the dev-gated example) contributed a channel; the channel ids carry their
            // own vendor namespace, so the "composed" grouping label never collides with core.engine-log.
            DiagnosticChannelCatalog.of("composed", pluginDiagnosticChannels),
            resourceOut.diagnosticChannelStreamRegistry()),
        new SubstrateGraph.ContextSubstrate(
            resourceOut.runtimeContextHolder(),
            resourceOut.runtimeContextChangeRegistry(),
            resourceOut.runtimeContextConfigBridge()),
        new SubstrateGraph.ConversationSubstrate(
            resourceOut.coreSurfaceCatalog(),
            io.justsearch.app.services.conversation.CoreConversationShapeCatalog.catalog(),
            // Tempdoc 560 §10.4: the composed plugin-contributed surfaces + shapes as catalogs. Empty
            // unless a plugin contributed one; the entry ids carry their own vendor namespace.
            SurfaceCatalog.of("composed", pluginSurfaces),
            ConversationShapeCatalog.of("composed", pluginConversationShapes),
            resourceOut.indexingJobsChangeRegistry(),
            operationOut.operationHistoryStore(),
            operationOut.operationHistoryChangeRegistry(),
            operationOut.capabilitiesChangeRegistry(),
            operationOut.navigationHistoryStore(),
            operationOut.consentCapsuleService(),
            operationOut.authorizationOutcomeStore(),
            operationOut.actionLedgerChangeRegistry(),
            operationOut.scanRollupLedger(),
            operationOut.globalHardStop(),
            operationOut.intentGateEvaluator(),
            operationOut.durableGrantStore(),
            operationOut.pendingAuthorizationStore(),
            operationOut.pendingAuthorizationChangeRegistry()));
  }
}
