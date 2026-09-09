/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.agent.api.registry.ConversationShape;
import io.justsearch.agent.api.registry.DiagnosticChannel;
import io.justsearch.agent.api.registry.Prompt;
import io.justsearch.agent.api.registry.Resource;
import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.Surface;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.RequiredCapability;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.services.observability.rules.RuleRunner;
import io.justsearch.agent.tools.AgentToolsOperationCatalog;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import io.justsearch.app.services.registry.preview.CapabilityAvailability;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.RemoteIndexingJobsBridge;
import io.justsearch.app.services.mcphost.McpHostService;
import io.justsearch.app.services.mcphost.McpServerConfig;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.telemetry.Telemetry;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Tempdoc 519 §4 Phase 4 — substrate construction. Composes the 4 substrate-init phase
 * helpers (Resource, Metric, Operation, Health), the indexing-jobs bridge wiring, the
 * operation handlers registry + 2 catalogs, the eager agent-tool handler registration,
 * and the rule runner build into a single phase function.
 *
 * <p>Returns an {@link Output} record bundling all results — bootstrap holds it as one field.
 *
 * <p>Three lazy suppliers handle Worker late-binding: {@code knowledgeServerSupplier},
 * {@code knowledgeClientSupplier}, and {@code indexingServiceSupplier} are invoked at
 * handler-dispatch time after {@code connectKnowledgeServer} populates the bootstrap's Worker
 * fields. The substrate phase itself only stores the suppliers; it doesn't invoke them.
 */
public final class SubstratePhase {

  private SubstratePhase() {}

  /** Bundle returned by {@link #runWithOutcome}. */
  public record Output(
      HandlerRegistry operationHandlers,
      OperationCatalog operationCatalog,
      OperationCatalog agentToolsCatalog,
      ResourceSubstrateInit.Output resourceOut,
      MetricSubstrateInit.Output metricsOut,
      OperationSubstrateInit.Output operationOut,
      HealthSubstrateInit.Output healthOut,
      RemoteIndexingJobsBridge indexingJobsBridge,
      RemoteIndexingJobsBridge.Subscription indexingJobsBridgeRegistrySubscription,
      RuleRunner ruleRunner,
      McpHostService mcpHostService,
      // Tempdoc 560 §10.4: the composed plugin-contributed DiagnosticChannels (snapshot of the shared
      // ContributionRegistry's diagnosticChannels axis after all installs). Empty in the common case;
      // the example plugin (dev-gated) is the first contributor. Threaded to the ChannelSubstrate so
      // RegistryController serves plugin channels alongside core.engine-log.
      List<DiagnosticChannel> pluginDiagnosticChannels,
      // Tempdoc 560 §10.4: likewise the composed plugin Surfaces + ConversationShapes — served alongside
      // the core catalogs at /api/registry/{surfaces,shapes} (a plugin RAIL surface renders in the rail).
      List<Surface> pluginSurfaces,
      List<ConversationShape> pluginConversationShapes,
      // Tempdoc 560 §28 Phase 2: the composed plugin-contributed Resources + Prompts — served alongside
      // the core catalogs at /api/registry/{resources,prompts} (mirrors pluginSurfaces/Shapes/Channels).
      List<Resource> pluginResources,
      List<Prompt> pluginPrompts) {}

  /**
   * Tempdoc 541 §5.3 + fix-pass Tier 5: sealed-sum entry. Wraps {@link #runInternal}; reports
   * {@link io.justsearch.app.services.bootstrap.PhaseOutcome.Failed} on construction throw;
   * otherwise Ready. No clear Degraded scenario at the surface today.
   */
  public static io.justsearch.app.services.bootstrap.PhaseOutcome<Output> runWithOutcome(
      io.justsearch.core.execution.EngineExecutorRegistry executors,
      Telemetry telemetry,
      Supplier<KnowledgeServerBootstrap> knowledgeServerSupplier,
      Supplier<KnowledgeClient> knowledgeClientSupplier,
      Supplier<IndexingService> indexingServiceSupplier,
      Supplier<io.justsearch.app.api.ExcludesService> excludesServiceSupplier,
      Supplier<io.justsearch.app.api.SettingsService> settingsServiceSupplier,
      Supplier<io.justsearch.app.api.DiagnosticsService> diagnosticsServiceSupplier,
      Supplier<io.justsearch.app.api.BrainRuntimeService> brainRuntimeServiceSupplier,
      Supplier<io.justsearch.app.api.RuntimeVariantService> runtimeVariantServiceSupplier,
      Supplier<io.justsearch.app.api.PackImportService> packImportServiceSupplier,
      Supplier<io.justsearch.app.api.BrainInstallService> brainInstallServiceSupplier,
      Supplier<io.justsearch.app.api.PolicyService> policyServiceSupplier,
      Supplier<io.justsearch.app.services.runtimestate.RuntimeSpecStore> runtimeSpecStoreSupplier,
      Supplier<io.justsearch.app.services.runtimestate.RuntimeReconciler> runtimeReconcilerSupplier,
      AgentToolFactory.Output agentTools,
      Function<RequiredCapability, Boolean> capabilityResolver,
      io.justsearch.app.api.OperationLeaseService operationLeaseService,
      List<McpServerConfig> mcpServers) {
    try {
      return new io.justsearch.app.services.bootstrap.PhaseOutcome.Ready<>(
          runInternal(
              executors,
              telemetry,
              knowledgeServerSupplier,
              knowledgeClientSupplier,
              indexingServiceSupplier,
              excludesServiceSupplier,
              settingsServiceSupplier,
              diagnosticsServiceSupplier,
              brainRuntimeServiceSupplier,
              runtimeVariantServiceSupplier,
              packImportServiceSupplier,
              brainInstallServiceSupplier,
              policyServiceSupplier,
              runtimeSpecStoreSupplier,
              runtimeReconcilerSupplier,
              agentTools,
              capabilityResolver,
              operationLeaseService,
              mcpServers));
    } catch (RuntimeException e) {
      return io.justsearch.app.services.bootstrap.PhaseOutcome.Failed.of(e);
    }
  }

  /**
   * §12.F: internal body (formerly public {@code run(...)}). Visibility narrowed to private;
   * the single entry point is the sealed-sum {@code runWithOutcome(...)} above.
   */
  private static Output runInternal(
      io.justsearch.core.execution.EngineExecutorRegistry executors,
      Telemetry telemetry,
      Supplier<KnowledgeServerBootstrap> knowledgeServerSupplier,
      Supplier<KnowledgeClient> knowledgeClientSupplier,
      Supplier<IndexingService> indexingServiceSupplier,
      Supplier<io.justsearch.app.api.ExcludesService> excludesServiceSupplier,
      Supplier<io.justsearch.app.api.SettingsService> settingsServiceSupplier,
      Supplier<io.justsearch.app.api.DiagnosticsService> diagnosticsServiceSupplier,
      Supplier<io.justsearch.app.api.BrainRuntimeService> brainRuntimeServiceSupplier,
      Supplier<io.justsearch.app.api.RuntimeVariantService> runtimeVariantServiceSupplier,
      Supplier<io.justsearch.app.api.PackImportService> packImportServiceSupplier,
      Supplier<io.justsearch.app.api.BrainInstallService> brainInstallServiceSupplier,
      Supplier<io.justsearch.app.api.PolicyService> policyServiceSupplier,
      Supplier<io.justsearch.app.services.runtimestate.RuntimeSpecStore> runtimeSpecStoreSupplier,
      Supplier<io.justsearch.app.services.runtimestate.RuntimeReconciler> runtimeReconcilerSupplier,
      AgentToolFactory.Output agentTools,
      Function<RequiredCapability, Boolean> capabilityResolver,
      io.justsearch.app.api.OperationLeaseService operationLeaseService,
      List<McpServerConfig> mcpServers) {
    // Operation registry inputs (consumed by OperationSubstrateInit).
    HandlerRegistry operationHandlers = new HandlerRegistry();
    OperationHandlerRegistrations.registerWorker(
        operationHandlers,
        knowledgeServerSupplier,
        indexingServiceSupplier,
        excludesServiceSupplier,
        settingsServiceSupplier,
        diagnosticsServiceSupplier,
        brainRuntimeServiceSupplier,
        runtimeVariantServiceSupplier,
        packImportServiceSupplier,
        brainInstallServiceSupplier,
        policyServiceSupplier,
        runtimeSpecStoreSupplier,
        runtimeReconcilerSupplier,
        operationLeaseService);
    AgentToolHandlers.registerEager(operationHandlers, agentTools);
    // Tempdoc 560 WS4 — the operation-catalog collapse + two-phase composition. Core + agent-tools +
    // the MCP-host's contributions compose into the ONE ContributionRegistry, and capability-derived
    // availability is applied ONCE over the full merged set (tempdoc 550 E3), closing the pre-WS4 gap
    // where MCP (or any post-merge) ops bypassed the gate because derivation ran before the merge.
    CoreOperationCatalog coreBase = new CoreOperationCatalog();
    AgentToolsOperationCatalog agentToolsBase = new AgentToolsOperationCatalog();

    // MCP-host first consumer (tempdoc 560 §6): connect to any configured external MCP servers and
    // project their tools onto EXECUTABLE Operation declarations — Path B, through the executor +
    // trust lattice + consent gate (NOT the VirtualOperationStore bypass). Off by default (empty
    // config) so normal startup is unaffected; opt in via -Djustsearch.mcp.host.config=<file>. The
    // MCP-host owns the ONE ContributionRegistry the base catalogs install into.
    McpHostService mcpHostService =
        new McpHostService(mcpServers == null ? List.of() : mcpServers);
    io.justsearch.agent.api.registry.ContributionRegistry contributions =
        mcpHostService.contributionRegistry();
    // Phase 1: install the base catalogs (collision-free after the navigate-to-surface reconcile).
    OperationCatalogComposition.installBaseCatalogs(contributions, coreBase, agentToolsBase);
    // Tempdoc 560 §10.4 declaration-completeness demo: optionally install the example vendor plugin
    // that contributes a DiagnosticChannel + a RAIL Surface + a ConversationShape through the new axes
    // (proves plugin→endpoint→UI; the plugin surface renders in the rail). Dev-gated
    // (-Djustsearch.demo.plugin=true); a no-op + zero vendor.example contributions in production.
    ExamplePlugin.installIfEnabled(contributions);
    // Phase 1b: connect the MCP-host — installs each server's contributions into the SAME registry.
    mcpHostService.connect();
    mcpHostService.registerHandlers(operationHandlers);
    if (!mcpHostService.operations().isEmpty()) {
      mcpHostService.installShutdownHook();
    }
    // Phase 1c (tempdoc 560 WS5): project declared workflows onto agent-facing tools and install them
    // into the SAME registry, so the model sees workflows in the one merged tool list. The agent loop
    // routes their invocation through the streaming WorkflowToolRunner (wired in LocalApiServer).
    //
    // ORDER IS LOAD-BEARING (tempdoc 876 B.4): this runs AFTER the MCP-host connect and the example
    // plugin, because a projected op now inherits the availability of the operations its ToolSteps
    // compose — and a workflow composing an operation absent from the registry is not projected at
    // all. Projecting before the MCP-host installed its contributions would make every vendor.* ref
    // look unresolvable and silently drop those workflows from the agent offering. It still runs
    // BEFORE deriveAndPartition, so the projected ops pass through the one capability-derivation.
    // Ref-uniqueness is unaffected: core.workflow-* cannot collide with vendor.mcphost.*.
    OperationCatalogComposition.installWorkflowOps(
        contributions,
        io.justsearch.app.services.conversation.WorkflowOperationProjection.project(
            io.justsearch.app.services.conversation.CoreWorkflowCatalog.catalog(),
            contributions.operations()));
    // Phase 2: derive availability once over the full composed set, then partition by owner back into
    // the dual-catalog shape every downstream consumer still reads.
    OperationCatalogComposition.Result composed =
        OperationCatalogComposition.deriveAndPartition(
            contributions, coreBase.namespace(), agentToolsBase.namespace());
    OperationCatalog operationCatalog = composed.operationCatalog();
    OperationCatalog agentToolsCatalog = composed.agentToolsCatalog();

    // Resource + Metric substrates (no cross-deps; independent).
    ResourceSubstrateInit.Output resourceOut =
        ResourceSubstrateInit.run(BootstrapHelpers.initialRuntimeContext());
    MetricSubstrateInit.Output metricsOut = MetricSubstrateInit.run(executors, telemetry);

    // Operation substrate — needs handlers + 2 catalogs + capability resolver.
    // Runs BEFORE the indexing-jobs bridge so its ActionLedgerChangeRegistry is available to the
    // bridge's terminal-outcome translator (tempdoc 550 thesis I); neither depends on the other.
    OperationSubstrateInit.Output operationOut =
        OperationSubstrateInit.run(executors,
            operationHandlers,
            operationCatalog,
            agentToolsCatalog,
            capabilityResolver,
            // Tempdoc 550 WA-4: surface catalog (built above) keys navigation gating.
            resourceOut.coreSurfaceCatalog());

    // Indexing-jobs bridge — needs Worker client (lazy) + resource change registry + the unified
    // action-ledger registry (terminal indexing outcomes fan into the ONE log; tempdoc 550 thesis I).
    var bridgeOut =
        IndexingJobsBridgeWiring.wire(
            executors,
            knowledgeClientSupplier,
            resourceOut.indexingJobsChangeRegistry(),
            operationOut.actionLedgerChangeRegistry());

    // Health substrate — needs healthRecoveryProjector + advisoryChangeRegistry + advisoryLogs
    // (from operationOut) + conditionRecoveryIndexChangeRegistry (from resourceOut).
    HealthSubstrateInit.Output healthOut =
        HealthSubstrateInit.run(executors,
            BootstrapHelpers.resolveOccurrenceBufferSize(),
            operationOut.healthRecoveryProjector(),
            operationOut.advisoryChangeRegistry(),
            operationOut.advisoryLogs(),
            resourceOut.conditionRecoveryIndexChangeRegistry());

    // Rule runner — needs telemetry + health condition store/registry/source.
    RuleRunner ruleRunner =
        RuleRunnerBuilder.build(executors,
            telemetry,
            healthOut.conditionStore(),
            healthOut.healthEventChangeRegistry(),
            healthOut.headSource());

    // Tempdoc 560 §10.4: snapshot the composed plugin axes after every install (base + workflows + the
    // dev-gated example + MCP). Empty unless a plugin contributed an instance of that kind.
    List<DiagnosticChannel> pluginDiagnosticChannels = contributions.diagnosticChannels();
    List<Surface> pluginSurfaces = contributions.surfaces();
    List<ConversationShape> pluginConversationShapes = contributions.conversationShapes();
    List<Resource> pluginResources = contributions.resources();
    List<Prompt> pluginPrompts = contributions.prompts();

    return new Output(
        operationHandlers,
        operationCatalog,
        agentToolsCatalog,
        resourceOut,
        metricsOut,
        operationOut,
        healthOut,
        bridgeOut.bridge(),
        bridgeOut.subscription(),
        ruleRunner,
        mcpHostService,
        pluginDiagnosticChannels,
        pluginSurfaces,
        pluginConversationShapes,
        pluginResources,
        pluginPrompts);
  }
}
