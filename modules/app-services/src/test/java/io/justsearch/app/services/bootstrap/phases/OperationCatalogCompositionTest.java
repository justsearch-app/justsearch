package io.justsearch.app.services.bootstrap.phases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.AuditPolicy;
import io.justsearch.agent.api.registry.Binding;
import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.ConsumerHook;
import io.justsearch.agent.api.registry.ContributionRegistry;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Interface;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationAvailability;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationLineage;
import io.justsearch.agent.api.registry.OperationPolicy;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.Plugin;
import io.justsearch.agent.api.registry.PluginContributions;
import io.justsearch.agent.api.registry.PluginRef;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.RequiredCapability;
import io.justsearch.agent.api.registry.RetryPolicy;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.TrustTier;
import io.justsearch.agent.api.registry.Workflow;
import io.justsearch.agent.api.registry.WorkflowCatalog;
import io.justsearch.agent.api.registry.WorkflowNode;
import io.justsearch.agent.api.registry.WorkflowRef;
import io.justsearch.agent.tools.AgentToolsOperationCatalog;
import io.justsearch.app.api.operations.RecordedIngestionService;
import io.justsearch.app.services.conversation.WorkflowOperationProjection;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import io.justsearch.core.context.EngineContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 560 WS4 — the operation-catalog collapse + two-phase composition.
 *
 * <p>The load-bearing regression (audit-driven-fixes-need-test): an operation contributed into the
 * registry AFTER the base catalogs — the position the MCP-host's contributions occupy — must still
 * pass through the single capability-availability derivation. Before WS4 the derivation ran over the
 * base catalogs before the MCP merge, so a post-merge op carrying a {@link RequiredCapability}
 * silently bypassed the gate.
 */
final class OperationCatalogCompositionTest {

  @Test
  void lateContributionStillGetsCapabilityDerivedAvailability() {
    ContributionRegistry registry = new ContributionRegistry();
    CoreOperationCatalog core = new CoreOperationCatalog();
    AgentToolsOperationCatalog agentTools = new AgentToolsOperationCatalog();

    // Phase 1: install the base catalogs.
    OperationCatalogComposition.installBaseCatalogs(registry, core, agentTools);

    // Phase 1b: a "late" contribution (the MCP-host occupies this slot) carrying WorkerOnline but
    // declaring NO explicit availability — exactly the shape that used to bypass the gate.
    OperationRef lateRef = new OperationRef("vendor.testmcp.do-thing");
    Operation lateOp =
        new Operation(
            lateRef,
            Presentation.of(new I18nKey("k"), new I18nKey("k")),
            Interface.of("{\"type\":\"object\"}", "{\"type\":\"object\"}"),
            new OperationPolicy(
                RiskTier.MEDIUM,
                ConfirmStrategy.Inline.INSTANCE,
                AuditPolicy.METADATA_ONLY,
                RetryPolicy.noRetry(),
                Set.of(RequiredCapability.WorkerOnline.INSTANCE),
                false),
            OperationAvailability.empty(),
            OperationLineage.empty(),
            Binding.of(lateRef),
            new Provenance(TrustTier.TRUSTED_PLUGIN, "vendor", "1.0"),
            Set.of(ExecutorTag.AGENT),
            Audience.AGENT,
            List.of(new ConsumerHook.Realized("agent-loop", Audience.AGENT)));
    PluginRef lateOwner = new PluginRef("vendor.testmcp.srv");
    registry.install(
        new ContributionRegistry.Installation(
            new Plugin(
                lateOwner,
                Presentation.of(new I18nKey("k"), new I18nKey("k")),
                new Provenance(TrustTier.TRUSTED_PLUGIN, "vendor", "1.0"),
                Audience.AGENT,
                PluginContributions.empty(),
                List.of(new ConsumerHook.Realized("agent-loop", Audience.AGENT))),
            List.of(lateOp),
            Map.of()));

    // Phase 2: derive once over the full set + partition.
    OperationCatalogComposition.Result composed =
        OperationCatalogComposition.deriveAndPartition(
            registry, core.namespace(), agentTools.namespace());

    Operation composedLate =
        composed.agentToolsCatalog().definitions().stream()
            .filter(op -> op.id().equals(lateRef))
            .findFirst()
            .orElseThrow(
                () -> new AssertionError("late contribution missing from the agent-tools partition"));
    // The fix: the late op now carries a derived availability expression (WorkerOnline → worker gate),
    // where before WS4 it would have stayed OperationAvailability.empty().
    assertTrue(
        composedLate.availability().expression().isPresent(),
        "a WorkerOnline op contributed after the base catalogs must still be capability-availability"
            + " derived (the post-merge gating gap WS4 closes)");
  }

  @Test
  void ingestFilesIsIrreversibleSoTheC4AutoFloorBites() {
    // Tempdoc 561 §19/C-4: the AUTO+MEDIUM-write floor only confirms when the op is IRREVERSIBLE
    // (reversible == undoSupported || inverseOperationRef.isPresent()). If a future change made
    // core.ingest-files undoable/inversible it would silently auto-fire under AUTO again — the exact
    // hallucinated-path hazard the live audit caught (Appendix A). Lock it irreversible here so the
    // floor keeps biting.
    Operation ingest =
        new AgentToolsOperationCatalog()
            .definitions().stream()
                .filter(op -> op.id().equals(AgentToolsOperationCatalog.INGEST_FILES))
                .findFirst()
                .orElseThrow(() -> new AssertionError("core.ingest-files missing from the catalog"));
    assertEquals(RiskTier.MEDIUM, ingest.policy().risk(), "ingest-files is a MEDIUM write");
    assertFalse(
        ingest.policy().undoSupported(),
        "ingest-files must stay undo-unsupported so the C-4 AUTO floor confirms it");
    assertTrue(
        ingest.policy().inverseOperationRef().isEmpty(),
        "ingest-files must declare no inverse so the C-4 AUTO floor confirms it");
  }

  @Test
  void noAgentExecutableOperationDeclaresTypedConfirm() {
    // Tempdoc 879 — the agent authorization ceremony (AuthorizationHost.ts `renderAuthorizeBody`)
    // hardcodes the typed phrase to the OPERATION ID: it renders "Type <operationId> to confirm"
    // and enables Approve only on `this.typed === operationId`. Unlike the operations-palette path
    // (ActionButton.ts `requireConfirmText`), it cannot carry a backend-declared `confirmTextKey` —
    // nothing on AgentEvent.PendingApproval / ToolCallPendingApproval conveys one. So declaring
    // ConfirmStrategy.Typed on an AGENT-executable operation would SILENTLY substitute the operation
    // id for the declared phrase at runtime: the declaration would be quietly ignored, not honoured.
    //
    // Remedy if a future operation genuinely needs a typed phrase on the agent path: wire it, don't
    // relax this test — the phrase has to reach the renderer through AgentEvent.PendingApproval +
    // ToolCallPendingApproval, the generated `core-agent-run` shape handler, and AuthorizationHost.ts.
    ContributionRegistry registry = new ContributionRegistry();
    CoreOperationCatalog core = new CoreOperationCatalog();
    AgentToolsOperationCatalog agentTools = new AgentToolsOperationCatalog();
    OperationCatalogComposition.installBaseCatalogs(registry, core, agentTools);
    OperationCatalogComposition.Result composed =
        OperationCatalogComposition.deriveAndPartition(
            registry, core.namespace(), agentTools.namespace());

    List<Operation> all =
        Stream.concat(
                composed.operationCatalog().definitions().stream(),
                composed.agentToolsCatalog().definitions().stream())
            .toList();
    // Guard the guard: if the composed set ever went empty this test would pass vacuously.
    assertFalse(all.isEmpty(), "the composed catalogs must contain operations");
    assertTrue(
        all.stream().anyMatch(op -> op.executors().contains(ExecutorTag.AGENT)),
        "the composed catalogs must contain at least one AGENT-executable operation");

    for (Operation op : all) {
      if (!op.executors().contains(ExecutorTag.AGENT)) {
        continue;
      }
      assertFalse(
          op.policy().confirm() instanceof ConfirmStrategy.Typed,
          () ->
              "operation "
                  + op.id().value()
                  + " is AGENT-executable and declares ConfirmStrategy.Typed, but the agent"
                  + " authorization ceremony renders the OPERATION ID as the typed phrase and cannot"
                  + " carry a declared confirmTextKey — the declaration would be silently substituted"
                  + " at runtime. Either drop ExecutorTag.AGENT / use Inline, or wire the phrase onto"
                  + " AgentEvent.PendingApproval + ToolCallPendingApproval, the generated"
                  + " core-agent-run shape handler, and AuthorizationHost.ts first.");
    }
  }

  @Test
  void navigateToSurfaceIsTheSingleCanonicalAgentToolsOwnedDeclaration() {
    ContributionRegistry registry = new ContributionRegistry();
    CoreOperationCatalog core = new CoreOperationCatalog();
    AgentToolsOperationCatalog agentTools = new AgentToolsOperationCatalog();
    // No ref collision proves the navigate-to-surface duplicate was reconciled (install would throw).
    OperationCatalogComposition.installBaseCatalogs(registry, core, agentTools);

    OperationCatalogComposition.Result composed =
        OperationCatalogComposition.deriveAndPartition(
            registry, core.namespace(), agentTools.namespace());

    OperationRef nav = new OperationRef("core.navigate-to-surface");
    long inCore =
        composed.operationCatalog().definitions().stream().filter(op -> op.id().equals(nav)).count();
    long inAgent =
        composed.agentToolsCatalog().definitions().stream()
            .filter(op -> op.id().equals(nav))
            .count();
    assertEquals(0L, inCore, "navigate-to-surface must no longer live in the core partition");
    assertEquals(
        1L, inAgent, "navigate-to-surface is the single canonical agent-tools-owned declaration");

    // It carries the superset executor set so the UI registry path still emits it.
    Operation navOp =
        composed.agentToolsCatalog().definitions().stream()
            .filter(op -> op.id().equals(nav))
            .findFirst()
            .orElseThrow();
    assertTrue(navOp.executors().contains(ExecutorTag.UI), "superset must keep the UI executor");
    assertTrue(navOp.executors().contains(ExecutorTag.AGENT), "superset must keep the AGENT executor");

    // A representative core op stays in the core partition.
    assertTrue(
        composed.operationCatalog().definitions().stream()
            .anyMatch(op -> op.id().equals(CoreOperationCatalog.RESTART_WORKER)),
        "core ops partition to the core catalog");
    assertFalse(
        composed.agentToolsCatalog().definitions().stream()
            .anyMatch(op -> op.id().equals(CoreOperationCatalog.RESTART_WORKER)),
        "core ops must not leak into the agent-tools partition");
  }

  @Test
  void absentRecordedIngestionOwnerRemovesItsOperationsAndDependentWorkflow() {
    OperationCatalog core = new CoreOperationCatalog();
    OperationCatalog agentTools = new AgentToolsOperationCatalog();
    assertTrue(
        core.definitions().stream().anyMatch(op -> op.id().equals(CoreOperationCatalog.REINDEX)));
    assertTrue(
        agentTools.definitions().stream()
            .anyMatch(op -> op.id().equals(AgentToolsOperationCatalog.INGEST_FILES)));

    OperationCatalog availableCore =
        OperationCatalogComposition.forRecordedIngestionOwner(
            core, RecordedIngestionService.unavailable());
    OperationCatalog availableAgentTools =
        OperationCatalogComposition.forRecordedIngestionOwner(
            agentTools, RecordedIngestionService.unavailable());

    assertFalse(
        availableCore.definitions().stream()
            .anyMatch(op -> op.id().equals(CoreOperationCatalog.REINDEX)),
        "the tooling facade must not offer reindex without its recorded-ingestion owner");
    assertFalse(
        availableAgentTools.definitions().stream()
            .anyMatch(op -> op.id().equals(AgentToolsOperationCatalog.INGEST_FILES)),
        "the tooling facade must not offer ingest without its recorded-ingestion owner");
    assertTrue(
        availableCore.definitions().stream()
            .anyMatch(op -> op.id().equals(CoreOperationCatalog.RESTART_WORKER)),
        "unrelated core operations remain offered");

    Workflow reindexWorkflow =
        new Workflow(
            new WorkflowRef("core.reindex-workflow"),
            Presentation.of(
                new I18nKey("test.reindex-workflow"),
                new I18nKey("test.reindex-workflow.desc")),
            Provenance.core("1.0"),
            Audience.AGENT,
            List.of(new WorkflowNode.ToolStep("reindex", CoreOperationCatalog.REINDEX)),
            List.of(new ConsumerHook.Realized("test.workflow", Audience.AGENT)));
    List<Operation> offeredOperations =
        Stream.concat(
                availableCore.definitions().stream(), availableAgentTools.definitions().stream())
            .toList();
    assertTrue(
        WorkflowOperationProjection.project(
                WorkflowCatalog.of("core", List.of(reindexWorkflow)), offeredOperations)
            .isEmpty(),
        "a workflow depending on an omitted owner operation must not be projected as an agent tool");
  }

  @Test
  void aRealButTemporarilyOfflineRecordedIngestionOwnerKeepsItsCatalogs() {
    OperationCatalog core = new CoreOperationCatalog();
    OperationCatalog agentTools = new AgentToolsOperationCatalog();
    RecordedIngestionService temporarilyOffline =
        new RecordedIngestionService() {
          @Override
          public OperationExecution execute(OperationRecordHandle parent, EngineContext context) {
            throw new UnsupportedOperationException("fixture runtime is offline");
          }

          @Override
          public void maintain() {}
        };

    assertTrue(
        temporarilyOffline.hasOwner(), "runtime availability does not change owner presence");
    assertThrows(
        UnsupportedOperationException.class, () -> temporarilyOffline.execute(null, null));
    assertSame(
        core, OperationCatalogComposition.forRecordedIngestionOwner(core, temporarilyOffline));
    assertSame(
        agentTools,
        OperationCatalogComposition.forRecordedIngestionOwner(agentTools, temporarilyOffline));
  }
}
