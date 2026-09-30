/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.agent.api.memory.MemoryStore;
import io.justsearch.agent.api.registry.ContributionRegistry;
import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.conversation.CoreWorkflowCatalog;
import io.justsearch.app.services.conversation.WorkflowOperationProjection;
import io.justsearch.app.services.lifecycle.WorkerCapability;
import io.justsearch.app.services.mcphost.McpHostService;
import io.justsearch.app.services.registry.emitter.AgentOperationEmitter;
import io.justsearch.agent.tools.AgentToolsOperationCatalog;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import io.justsearch.app.services.registry.operations.handlers.NavigateToSurfaceHandler;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 876 §B.5 (W6 item 3) — the offered-implies-executable invariant, over the SAME
 * composition production assembles.
 *
 * <p><b>What this closes.</b> {@code core.remember} is declared with no availability expression
 * (offered to the model on every run) but was only registered by ONE of the two agent-tool
 * handler-registration paths ({@link AgentToolFactoryScanWiringTest} pins the registration
 * mechanism directly). This test closes the mirror-image gap: it asserts the invariant over the
 * ACTUAL composed offering an
 * {@link AgentOperationEmitter} hands the model — every {@code Operation} that survives
 * {@code offer(...)} either has a registered handler in the {@link HandlerRegistry}, or is a
 * projected workflow routed through {@code WorkflowToolRunner} rather than a handler
 * ({@code offered ⊆ (handler registry ∪ workflow-runner routes ∪ virtual store)} — the
 * tempdoc's stated invariant; the virtual store is FE-published and has no {@link Operation}
 * backing it, so it contributes no members to this composed set by construction).
 *
 * <p>Mirrors {@code SubstratePhase.runInternal}'s composition order exactly (base catalogs →
 * example plugin → MCP-host connect/registerHandlers → workflow projection → derive+partition —
 * see {@code SubstratePhase.java:179-232}), so a defect that only reproduces under the real
 * composition order (e.g. tempdoc 876 B.4's workflow-projection-must-run-after-MCP-connect
 * ordering requirement) is exercised here too, not just under a hand-simplified catalog.
 */
@DisplayName("Agent offering is executable — offered ⊆ (handlers ∪ workflow routes ∪ virtual store)")
final class AgentOfferingIsExecutableTest {

  private ConfigStore previousConfigStore;

  @BeforeEach
  void publishConfigStore() {
    previousConfigStore = ConfigStore.globalOrNull();
    TestResolvedConfigHelper.storeWithDefaults();
  }

  @AfterEach
  void restoreConfigStore() {
    TestResolvedConfigHelper.restoreGlobal(previousConfigStore);
  }

  @Test
  @DisplayName("every operation the agent offering surfaces is executable")
  void offeredOperationsAreAllExecutable(@TempDir Path dataDir) {
    KnowledgeClient client = mock(KnowledgeClient.class);
    WorkerCapability capability = mock(WorkerCapability.class);
    when(capability.available()).thenReturn(true);

    // ---- 1. Handler registry: both AgentToolHandlers paths, all prerequisites satisfied,
    // exactly as production runs them (SubstratePhase.run's eager call at construction time,
    // then HeadAssembly's Memoized field triggering the late-bound call once the worker
    // connects) — the SAME shape as
    // AgentToolFactoryScanWiringTest#eagerThenLateBoundRegistersAllSixOnTheSameRegistry.
    HandlerRegistry operationHandlers = new HandlerRegistry();

    AgentToolFactory.Output eagerTools =
        AgentToolFactory.build(
            mock(io.justsearch.app.services.worker.SearchPerSourceExecutor.class),
            dataDir,
            mock(KnowledgeServerBootstrap.class),
            client,
            client,
            OnlineAiService.unavailable(),
            null,
            mock(DocumentService.class));
    AgentToolHandlers.registerEager(operationHandlers, eagerTools);

    boolean lateBoundRan =
        AgentToolHandlers.registerLateBound(
            mock(io.justsearch.app.services.worker.SearchPerSourceExecutor.class),
            operationHandlers,
            mock(KnowledgeServerBootstrap.class),
            client,
            capability,
            dataDir,
            client,
            OnlineAiService.unavailable(),
            null,
            null,
            null,
            MemoryStore.noop(),
            null,
            null,
            mock(DocumentService.class));
    assertTrue(lateBoundRan, "late-bound registration must run with all prerequisites satisfied");

    // core.navigate-to-surface: registered by OperationSubstrateInit (a side effect of building
    // the BackendIntentRouter substrate — OperationSubstrateInit.java:293-295), never by
    // AgentToolHandlers. Building the FULL OperationSubstrateInit.Output here would drag in the
    // durable-grant store, the action-event journal (which picks a persistence mode from
    // UiSettingsStore.PersistenceMode.resolveMode() — a global this test should not perturb),
    // and the trust lattice for no benefit to this test's assertion (it never dispatches
    // through the handler), so register the same handler at the same ref production does,
    // matching OperationSubstrateInit.java:293-295 verbatim.
    operationHandlers.register(
        CoreOperationCatalog.NAVIGATE_TO_SURFACE, new NavigateToSurfaceHandler(() -> null));

    // ---- 2. The composed catalog: base catalogs + MCP-host (empty) + projected workflows,
    // derived and partitioned exactly as SubstratePhase.runInternal does.
    CoreOperationCatalog coreBase = new CoreOperationCatalog();
    AgentToolsOperationCatalog agentToolsBase = new AgentToolsOperationCatalog();
    McpHostService mcpHostService = new McpHostService(List.of());
    ContributionRegistry contributions = mcpHostService.contributionRegistry();
    OperationCatalogComposition.installBaseCatalogs(contributions, coreBase, agentToolsBase);
    ExamplePlugin.installIfEnabled(contributions);
    mcpHostService.connect();
    mcpHostService.registerHandlers(operationHandlers);
    OperationCatalogComposition.installWorkflowOps(
        contributions,
        WorkflowOperationProjection.project(
            CoreWorkflowCatalog.catalog(), contributions.operations()));
    OperationCatalogComposition.Result composed =
        OperationCatalogComposition.deriveAndPartition(
            contributions, coreBase.namespace(), agentToolsBase.namespace());
    OperationCatalog agentToolsCatalog = composed.agentToolsCatalog();

    // ---- 3. The offering itself — the one authority (876 B.9 P1): what AgentOperationEmitter
    // actually hands the model, not a re-derivation of it.
    List<Operation> offered = new AgentOperationEmitter().offer(agentToolsCatalog, List.of());
    assertFalse(offered.isEmpty(), "sanity: the composed offering must not be empty");

    // ---- 4. offered ⊆ (handler registry ∪ workflow-runner routes). Virtual-store operations are
    // FE-published OpenAI envelopes with no backing Operation (AgentOperationEmitter's own
    // javadoc: "Virtual operations are deliberately absent [from offer(...)]"), so they cannot
    // appear in `offered` and contribute nothing to check here.
    List<String> unexecutable = new ArrayList<>();
    for (Operation op : offered) {
      boolean hasHandler = operationHandlers.resolve(op.id()).isPresent();
      boolean isProjectedWorkflow = WorkflowOperationProjection.workflowRefFor(op.id()).isPresent();
      if (!hasHandler && !isProjectedWorkflow) {
        unexecutable.add(op.id().value());
      }
    }
    assertTrue(
        unexecutable.isEmpty(),
        () ->
            "offered but not executable (no registered handler, not a projected workflow): "
                + unexecutable);
  }
}
