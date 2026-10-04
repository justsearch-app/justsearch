package io.justsearch.app.services.registry.emitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration test for slice 484 §3.1 closure (C1) — verifies the audience filter
 * against the real {@link CoreOperationCatalog}, not synthetic test fixtures.
 *
 * <p>Closes the "end-to-end LLM verification" gap from slice 481 §E.6.4 / slice 484
 * §3.5. The unit tests in {@link AgentOperationEmitterTest} pin the filter logic
 * with synthetic Operations; this test pins it against the production catalog so
 * that:
 *
 * <ul>
 *   <li>the catalog actually contains the OPERATOR-with-AGENT-executor hazards C1
 *       was designed to fix
 *   <li>the emitter actually filters them out from the LLM-facing tool list
 * </ul>
 *
 * <p>The "LLM-driven" framing is unnecessary: the LLM is the receiver of the
 * emitter's output, not part of the filter. Once the emitter is verified against
 * the real catalog, the LLM adds nothing to the verification — it just receives
 * what the emitter sends.
 */
final class AgentOperationEmitterCoreCatalogIntegrationTest {

  private static final String BULK_REINDEX_WIRE = "core_bulk_reindex";
  private static final String REBUILD_INDEX_WIRE = "core_rebuild_index";
  private static final String RECOVER_COMPONENT_WIRE = "core_recover_component";
  private static final String RESET_SETTINGS_WIRE = "core_reset_settings";
  private static final String EXPORT_DIAGNOSTICS_WIRE = "core_export_diagnostics";
  private static final String CLEAR_FAILED_JOBS_WIRE = "core_clear_failed_jobs";

  @Test
  @DisplayName("Hazards exist in the real catalog (precondition)")
  void hazardsExistInRealCatalog() {
    // Pre-condition: the policy hazards C1 was designed to fix actually live in the
    // production catalog. If this assertion fails, either the catalog changed or the
    // audience-lift work didn't ship — both worth surfacing.
    OperationCatalog catalog = new CoreOperationCatalog();

    Operation bulkReindex =
        catalog.findByIdValue("core.bulk-reindex").orElseThrow();
    assertEquals(Audience.OPERATOR, bulkReindex.audience());
    assertTrue(bulkReindex.executors().contains(ExecutorTag.AGENT),
        "bulk-reindex should declare AGENT executor (the hazard C1 fixes)");

    // Tempdoc 598 reopen (B-2): core.rebuild-index is now USER self-service recovery with a UI-ONLY
    // executor (AGENT dropped) — so it is NOT an OPERATOR+AGENT hazard anymore. It must still NOT reach
    // the agent tool list, now via the missing AGENT executor rather than the OPERATOR audience
    // (verified in filterExcludesOperatorAgentHazards / specificHazardsAndCounterCases below).
    Operation rebuildIndex =
        catalog.findByIdValue("core.rebuild-index").orElseThrow();
    assertEquals(Audience.USER, rebuildIndex.audience());
    assertFalse(rebuildIndex.executors().contains(ExecutorTag.AGENT),
        "rebuild-index must NOT declare AGENT executor (UI-only self-service, tempdoc 598 B-2)");
  }

  @Test
  @DisplayName("C1 filter excludes OPERATOR-with-AGENT-executor hazards from real catalog")
  void filterExcludesOperatorAgentHazards() {
    AgentOperationEmitter emitter = new AgentOperationEmitter();
    OperationCatalog catalog = new CoreOperationCatalog();

    List<Map<String, Object>> tools = emitter.emit(catalog, List.of());
    Set<String> emittedNames = extractNames(tools);

    assertFalse(
        emittedNames.contains(BULK_REINDEX_WIRE),
        "core.bulk-reindex (audience=OPERATOR) must be filtered from agent tool list");
    assertFalse(
        emittedNames.contains(REBUILD_INDEX_WIRE),
        "core.rebuild-index must be filtered from agent tool list (tempdoc 598 B-2: now via its UI-only"
            + " executor — no AGENT — rather than the former OPERATOR audience)");
  }

  @Test
  @DisplayName("All OPERATOR-audience Operations are filtered, regardless of executor membership")
  void filterExcludesAllOperatorAudience() {
    AgentOperationEmitter emitter = new AgentOperationEmitter();
    OperationCatalog catalog = new CoreOperationCatalog();
    List<Map<String, Object>> tools = emitter.emit(catalog, List.of());
    Set<String> emittedNames = extractNames(tools);

    // Sweep the catalog for every OPERATOR-audience Operation; none should appear in
    // the agent tool list. Future OPERATOR Operations added to the catalog get covered
    // by this assertion automatically.
    List<Operation> operatorOps =
        catalog.definitions().stream()
            .filter(op -> op.audience() == Audience.OPERATOR)
            .toList();

    assertFalse(operatorOps.isEmpty(), "Catalog should contain at least one OPERATOR-audience Operation");

    for (Operation op : operatorOps) {
      String wireName = OperationCatalog.toWireName(op.id());
      assertFalse(
          emittedNames.contains(wireName),
          "OPERATOR-audience Operation " + op.id().value() + " (wire: " + wireName
              + ") must not appear in agent tool list");
    }
  }

  @Test
  @DisplayName("USER-audience Operations with AGENT executor remain in the agent tool list")
  void userAudienceWithAgentExecutorIsRetained() {
    // Counter-test: the filter must not be too restrictive. USER-audience Operations
    // are user-facing features the agent legitimately invokes on the user's behalf.
    AgentOperationEmitter emitter = new AgentOperationEmitter();
    OperationCatalog catalog = new CoreOperationCatalog();
    List<Map<String, Object>> tools = emitter.emit(catalog, List.of());
    Set<String> emittedNames = extractNames(tools);

    List<Operation> userOpsWithAgent =
        catalog.definitions().stream()
            .filter(op -> op.audience() == Audience.USER)
            .filter(op -> op.executors().contains(ExecutorTag.AGENT))
            .toList();

    assertFalse(
        userOpsWithAgent.isEmpty(),
        "Catalog should contain at least one USER-audience Operation with AGENT executor");

    for (Operation op : userOpsWithAgent) {
      String wireName = OperationCatalog.toWireName(op.id());
      assertTrue(
          emittedNames.contains(wireName),
          "USER-audience Operation " + op.id().value() + " (wire: " + wireName
              + ") with AGENT executor must remain in agent tool list");
    }
  }

  @Test
  @DisplayName("Specific hazards bulk-reindex + rebuild-index are excluded; user-search-like ops retained")
  void specificHazardsAndCounterCases() {
    AgentOperationEmitter emitter = new AgentOperationEmitter();
    OperationCatalog catalog = new CoreOperationCatalog();
    List<Map<String, Object>> tools = emitter.emit(catalog, List.of());
    Set<String> emittedNames = extractNames(tools);

    // Excluded (the verified hazards from slice 481 §C.5 + slice 484 §6.2):
    assertFalse(emittedNames.contains(BULK_REINDEX_WIRE));
    assertFalse(emittedNames.contains(REBUILD_INDEX_WIRE));
    assertFalse(emittedNames.contains(RECOVER_COMPONENT_WIRE));
    assertFalse(emittedNames.contains(RESET_SETTINGS_WIRE));
    assertFalse(emittedNames.contains(EXPORT_DIAGNOSTICS_WIRE));
    assertFalse(emittedNames.contains(CLEAR_FAILED_JOBS_WIRE));

    // Retained (USER-audience Operations the agent legitimately invokes):
    assertTrue(emittedNames.contains("core_ping_backend"));
    assertTrue(emittedNames.contains("core_reindex"));
  }

  private static Set<String> extractNames(List<Map<String, Object>> tools) {
    return tools.stream()
        .map(
            tool -> {
              @SuppressWarnings("unchecked")
              Map<String, Object> function = (Map<String, Object>) tool.get("function");
              return (String) function.get("name");
            })
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }
}
