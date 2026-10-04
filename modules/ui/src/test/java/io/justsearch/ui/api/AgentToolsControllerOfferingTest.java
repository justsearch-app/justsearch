/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;
import io.justsearch.core.context.EngineContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import io.javalin.http.Context;
import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentRequest;
import io.justsearch.agent.api.AgentService;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.AuditPolicy;
import io.justsearch.agent.api.registry.AvailabilityExpression;
import io.justsearch.agent.api.registry.Binding;
import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Interface;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationAvailability;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationLineage;
import io.justsearch.agent.api.registry.OperationPolicy;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.RetryPolicy;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.app.services.registry.emitter.AgentOperationEmitter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 876 §B.1 — {@code GET /api/chat/agent/tools} must show the model's OFFERING, not the raw
 * catalog. The FE calls this payload the agent's authority space (`AgentSessionController.ts`), and
 * before 876 it read {@code availableOperations()} — the unfiltered agent-tools partition — so a
 * tool the emitter withheld for unavailability was still advertised to the user.
 *
 * <p>The double here does NOT hand-pick a narrower list: it runs the REAL
 * {@link AgentOperationEmitter} with a real availability probe, so the test fails if the controller
 * reads the wide surface AND if the emitter stops filtering.
 */
@DisplayName("AgentToolsController — the tools endpoint projects the offering (876 §B.1)")
final class AgentToolsControllerOfferingTest {

  private static final String GATING_CONDITION = "index.unavailable";

  private static Operation op(String id, Optional<AvailabilityExpression> availability) {
    return new Operation(
        new OperationRef(id),
        Presentation.of(new I18nKey("registry-operation." + id + ".label"),
            new I18nKey("registry-operation." + id + ".description")),
        Interface.of(
            "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}",
            "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.NONE,
            RetryPolicy.noRetry(),
            Set.of(),
            false),
        new OperationAvailability(availability, Optional.empty()),
        OperationLineage.empty(),
        Binding.of(new OperationRef(id)),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.AGENT),
        Audience.USER);
  }

  /** The catalog under test: one always-on op, one gated on the index being available. */
  private static OperationCatalog catalog() {
    return OperationCatalog.of(
        "core",
        List.of(
            op("core.always-on", Optional.empty()),
            op(
                "core.search-index",
                Optional.of(
                    new AvailabilityExpression.Not(
                        new AvailabilityExpression.ConditionMatches(GATING_CONDITION))))));
  }

  /**
   * An AgentService whose two reads are exactly the two the 876 §A.0 audit distinguishes:
   * {@code availableOperations()} is the whole catalog (what a resolver needs), and
   * {@code offeredOperations()} is the real emitter's filtered offering (what the model sees).
   */
  private static final class CatalogAgentService implements AgentService {
    private final OperationCatalog catalog;
    private final boolean indexUnavailable;

    CatalogAgentService(OperationCatalog catalog, boolean indexUnavailable) {
      this.catalog = catalog;
      this.indexUnavailable = indexUnavailable;
    }

    @Override
    public void runAgent(AgentRequest request, Consumer<AgentEvent> eventConsumer, EngineContext engineContext) {}

    @Override
    public void approveToolCall(String sessionId, String callId) {}

    @Override
    public void rejectToolCall(String sessionId, String callId, String reason) {}

    @Override
    public void cancelSession(String sessionId) {}

    @Override
    public List<Operation> availableOperations() {
      return List.copyOf(catalog.definitions());
    }

    @Override
    public List<Operation> offeredOperations() {
      return new AgentOperationEmitter()
          .withAvailabilityProbe(conditionId -> indexUnavailable && GATING_CONDITION.equals(conditionId))
          .offer(catalog, List.of());
    }

    @Override
    public boolean isAvailable() {
      return true;
    }
  }

  /** Invokes {@code handleListTools} against a mocked Context and returns the JSON body. */
  private static Map<?, ?> listTools(AgentService service) {
    AgentToolsController controller = new AgentToolsController(() -> service, null);
    Context ctx = mock(Context.class);
    AtomicReference<Object> captured = new AtomicReference<>();
    doAnswer(inv -> {
      captured.set(inv.getArgument(0));
      return ctx;
    })
        .when(ctx)
        .json(any(Object.class));
    controller.handleListTools(ctx);
    return (Map<?, ?>) captured.get();
  }

  private static List<String> toolNames(Map<?, ?> body) {
    List<?> tools = (List<?>) body.get("tools");
    return tools.stream().map(t -> ((Map<?, ?>) t).get("name").toString()).toList();
  }

  @Test
  void hidesAnOperationTheEmitterWithholdsForUnavailability() {
    // index.unavailable IS firing → Not(...) is false → core.search-index is not offered. The
    // panel must agree: the endpoint reads offeredOperations(), not the wider availableOperations().
    AgentService service = new CatalogAgentService(catalog(), true);

    Map<?, ?> body = listTools(service);

    assertEquals(
        List.of("core_always_on"),
        toolNames(body),
        "A tool the emitter withholds must be absent from the trust panel's inventory");
    assertEquals(
        2,
        service.availableOperations().size(),
        "availableOperations() must stay wide — the resolvers index it by name (876 §A.0)");
    assertTrue((Boolean) body.get("available"), "the `available` flag is the service's, unchanged");
  }

  @Test
  void showsTheOperationWhenTheGatingConditionIsNotFiring() {
    // The ready state — the same catalog, the same endpoint, the gate not firing. Without this
    // half, the test above would also pass if the endpoint had simply stopped returning tools.
    Map<?, ?> body = listTools(new CatalogAgentService(catalog(), false));

    assertEquals(
        List.of("core_always_on", "core_search_index"),
        toolNames(body),
        "With the gate clear both operations are offered, in catalog order");
  }

  @Test
  void keepsTheRichProjectionShapePerTool() {
    // The wire shape is unchanged by 876 (only the SET narrows): the FE's AgentToolInfo reads
    // these fields, and `description` stays an i18n KEY because the FE resolves it per-locale.
    Map<?, ?> body = listTools(new CatalogAgentService(catalog(), false));
    Map<?, ?> tool = (Map<?, ?>) ((List<?>) body.get("tools")).get(0);

    assertEquals(
        Set.of(
            "name",
            "description",
            "risk",
            "supportsUndo",
            "parameterSchema",
            "tier",
            "provenance",
            "kind"),
        tool.keySet(),
        "GET /api/chat/agent/tools keeps its per-tool field set");
    assertEquals("registry-operation.core.always-on.description", tool.get("description"));
    assertEquals("low", tool.get("risk"));
    assertEquals("operation", tool.get("kind"));
    assertFalse((Boolean) tool.get("supportsUndo"));
  }
}
