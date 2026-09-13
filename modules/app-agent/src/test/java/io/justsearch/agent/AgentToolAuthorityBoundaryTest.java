/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.core.context.EngineContext;
import io.justsearch.agent.EngineContextTestFixtures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.AgentLoopServiceTest.ScriptedAiService;
import io.justsearch.agent.AgentLoopServiceTest.ScriptedResponse;
import io.justsearch.agent.AgentLoopServiceTest.StubTool;
import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentProfile;
import io.justsearch.agent.api.AgentRequest;
import io.justsearch.agent.api.registry.AgentToolEmitter;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.AvailabilityExpression;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationAvailability;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.RiskTier;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 875 finding 3 / Move 3 — <strong>offering IS authorization</strong>.
 *
 * <p>Before this change {@code AgentStepRunner} resolved a model-named tool through
 * {@code OperationCatalog.resolveByWireName}, which iterates the RAW {@code definitions()} and
 * applies none of the filters the emitter applied when it decided what to offer. A tool the emitter
 * deliberately withheld — for audience, for availability, or because it was outside the run's
 * {@code selectedToolNames()} — was still dispatchable. Each test below names an operation that IS
 * in the catalog but was NOT emitted, and pins that it is refused with a typed
 * {@link AgentEvent.ToolCallRejected}, never reaches its handler, and does not kill the run.
 *
 * <p>The emitter used here is not the trivial one in {@code AgentLoopServiceTest}: it mirrors
 * {@code AgentOperationEmitter}'s actual filter chain (executor tag → audience allow-list →
 * availability expression → selection). Without that, every test in this file would pass
 * vacuously, because the withheld tool would never have been withheld.
 */
class AgentToolAuthorityBoundaryTest {

  // ---------------------------------------------------------------------------
  // 1. Withheld by AUDIENCE
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("an OPERATOR-audience AGENT-tagged operation is refused, not dispatched")
  void audienceWithheldTool_isRejectedNotDispatched() {
    var admin = new StubTool("admin_op", RiskTier.LOW, "admin ran");
    var search = new StubTool("search_index", RiskTier.LOW, "hits");
    // Both carry ExecutorTag.AGENT; only the audience differs. `core.admin-op` is exactly the
    // shape of a hidden-but-dispatchable MCP contribution (875 §B row 3c).
    var catalog =
        catalogOf(withAudience(admin.toOperation(), Audience.OPERATOR), search.toOperation());

    var events =
        runNamingTool(catalog, "core_admin_op", List.of(), alwaysFiring(), admin, search);

    assertRejectedNotDispatched(events, admin, "core_admin_op");
  }

  // ---------------------------------------------------------------------------
  // 2. Withheld by AVAILABILITY
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("an operation whose availability expression is false is refused, not dispatched")
  void availabilityWithheldTool_isRejectedNotDispatched() {
    var readDoc = new StubTool("read_document", RiskTier.LOW, "doc text");
    var search = new StubTool("search_index", RiskTier.LOW, "hits");
    // Mirrors core.read-document being withheld while `index.unavailable` fires: the operation
    // stays in the catalog, so resolveByWireName still finds it.
    var catalog =
        catalogOf(
            requiring(readDoc.toOperation(), "index.available"), search.toOperation());

    var events =
        runNamingTool(
            catalog,
            "core_read_document",
            List.of(),
            // "index.available" is NOT firing → the expression evaluates false → withheld.
            conditionId -> false,
            readDoc,
            search);

    assertRejectedNotDispatched(events, readDoc, "core_read_document");
  }

  // ---------------------------------------------------------------------------
  // 3. Withheld by SELECTION
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("an operation outside request.selectedToolNames() is refused, not dispatched")
  void selectionWithheldTool_isRejectedNotDispatched() {
    var fileOps = new StubTool("file_operations", RiskTier.LOW, "moved");
    var search = new StubTool("search_index", RiskTier.LOW, "hits");
    var catalog = catalogOf(fileOps.toOperation(), search.toOperation());

    var events =
        runNamingTool(
            catalog,
            "core_file_operations",
            // The run authorized search only.
            List.of("core_search_index"),
            alwaysFiring(),
            fileOps,
            search);

    assertRejectedNotDispatched(events, fileOps, "core_file_operations");
  }

  // ---------------------------------------------------------------------------
  // 4. CONTROL — an offered tool still dispatches
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("control: an offered tool still resolves, is approved, and executes")
  void offeredTool_stillDispatches() {
    var admin = new StubTool("admin_op", RiskTier.LOW, "admin ran");
    var search = new StubTool("search_index", RiskTier.LOW, "hits");
    // Same catalog as the audience test — only the tool the model names changes. So a green here
    // proves the three rejections above are the filter biting, not the harness failing to dispatch
    // anything at all.
    var catalog =
        catalogOf(withAudience(admin.toOperation(), Audience.OPERATOR), search.toOperation());

    var events =
        runNamingTool(catalog, "core_search_index", List.of(), alwaysFiring(), admin, search);

    assertNull(
        firstOfType(events, AgentEvent.ToolCallRejected.class),
        "an offered tool must not be rejected");
    assertEquals(1, search.callCount.get(), "the offered tool's handler should have run");
    assertEquals(0, admin.callCount.get(), "the un-named tool must not run");
    var completed = firstOfType(events, AgentEvent.ToolExecutionCompleted.class);
    assertNotNull(completed, "the offered tool should reach execution");
    assertTrue(completed.result().success(), "the offered tool should succeed");
    assertNotNull(
        firstOfType(events, AgentEvent.AgentDone.class), "the run should still complete");
  }

  // ---------------------------------------------------------------------------
  // 5. A STEERING list that names a tool outside the run selection still dispatches
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("a tool offered by a per-iteration steering list is authorized even when the run "
      + "selection excludes it")
  void steeringOfferedTool_isNotRefused() {
    // Review finding S1. The per-iteration tool lists REPLACE the run selection rather than
    // intersect with it: a profile's toolSubset (and E0a's first-turn narrowing) can offer a tool
    // `request.selectedToolNames()` does not name. Refusing it would strand the run on a tool the
    // product itself just put in front of the model. Offering and then refusing is never right.
    var fileOps = new StubTool("file_operations", RiskTier.LOW, "moved");
    var search = new StubTool("search_index", RiskTier.LOW, "hits");
    var catalog = catalogOf(fileOps.toOperation(), search.toOperation());
    var profiles =
        List.of(
            new AgentProfile("primary", "Primary", null, List.of("core_file_operations")));

    var ai =
        new ScriptedAiService(
            ScriptedResponse.toolCall("call-1", "core_file_operations", "{}"),
            ScriptedResponse.textOnly("done"));
    var service =
        AgentLoopServiceTest.observed(
            new AgentLoopService(
                ai,
                catalog,
                AgentLoopServiceTest.stubExecutor(fileOps, search),
                filteringEmitter(alwaysFiring()),
                null,
                null));
    var events = new CopyOnWriteArrayList<AgentEvent>();
    service.runAgent(
        new AgentRequest(
            List.of(Map.of("role", "user", "content", "go")),
            // The run authorized search only — but the active profile's subset offers file-ops.
            List.of("core_search_index"),
            3,
            profiles,
            "primary"),
        events::add, EngineContextTestFixtures.AGENT_LOOP);

    assertNull(
        firstOfType(events, AgentEvent.ToolCallRejected.class),
        "a tool the model was actually offered this turn must not be refused");
    assertEquals(1, fileOps.callCount.get(), "the steering-offered tool's handler should have run");
    assertNotNull(
        firstOfType(events, AgentEvent.AgentDone.class), "the run should complete");
  }

  // ---------------------------------------------------------------------------
  // 5b. A tool whose availability flips AFTER it was offered still dispatches (876 x 875)
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("a tool already handed to the model, whose availability flips before it is called, "
      + "reaches the executor instead of being refused as unresolvable")
  void availabilityFlippedAfterOffer_isNotRefused() {
    // Where 876 and 875 compose. 875's authority is a UNION: the emitter's CURRENT offering, or the
    // list the model was actually handed this turn. 876 §B.2b re-evaluates availability within a
    // run, so those two arms can now disagree — a tool offered at t=0 whose backing subsystem goes
    // down before the model calls it is in arm 2 but not arm 1.
    //
    // Arm 2 must win, and 876's asymmetry is the reason: offering fails open, EXECUTION fails
    // closed. A dispatch reaches the executor and returns a typed result the model reads and adapts
    // to; refusing it as unresolvable tells the model its own tool does not exist, which is what
    // makes a model improvise (868 §C.3). Offering and then refusing is never right.
    //
    // The flip is driven off the emitter itself: the first emit (the run's t=0 offering) sees the
    // condition clear, every later evaluation sees it firing. That is the live sequence, without a
    // timer or a sleep.
    var search = new StubTool("search_index", RiskTier.LOW, "hits");
    var catalog = catalogOf(gatedOn(search, "index.unavailable"));

    java.util.concurrent.atomic.AtomicBoolean firing =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    java.util.concurrent.atomic.AtomicBoolean firstEmitDone =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    AgentToolEmitter delegate = filteringEmitter(id -> firing.get());
    AgentToolEmitter flipping =
        new AgentToolEmitter() {
          @Override
          public List<Operation> offer(OperationCatalog c, Collection<String> selected) {
            return delegate.offer(c, selected);
          }

          @Override
          public List<Map<String, Object>> emit(OperationCatalog c, Collection<String> selected) {
            List<Map<String, Object>> out = delegate.emit(c, selected);
            if (firstEmitDone.compareAndSet(false, true)) {
              firing.set(true); // the subsystem goes down right after the run's opening offer
            }
            return out;
          }
        };

    var ai =
        new ScriptedAiService(
            ScriptedResponse.toolCall("call-1", "core_search_index", "{}"),
            ScriptedResponse.textOnly("done"));
    var service =
        AgentLoopServiceTest.observed(
            new AgentLoopService(
                ai, catalog, AgentLoopServiceTest.stubExecutor(search), flipping, null, null));
    var events = new CopyOnWriteArrayList<AgentEvent>();
    service.runAgent(
        new AgentRequest(
            List.of(Map.of("role", "user", "content", "go")), List.of(), 3, List.of(), null),
        events::add, EngineContextTestFixtures.AGENT_LOOP);

    assertTrue(firing.get(), "precondition: availability must actually have flipped mid-run");
    assertNull(
        firstOfType(events, AgentEvent.ToolCallRejected.class),
        "a tool the model was already handed must reach the executor once availability flips —"
            + " execution decides, not resolution");
    assertEquals(1, search.callCount.get(), "the handler should have run");
    assertNotNull(firstOfType(events, AgentEvent.AgentDone.class), "the run should complete");
  }

  /** {@code op} re-declared with a Not(ConditionMatches(conditionId)) availability gate. */
  private static Operation gatedOn(StubTool tool, String conditionId) {
    Operation base = tool.toOperation();
    return new Operation(
        base.id(),
        base.presentation(),
        base.intf(),
        base.policy(),
        new OperationAvailability(
            Optional.of(
                new AvailabilityExpression.Not(
                    new AvailabilityExpression.ConditionMatches(conditionId))),
            Optional.empty()),
        base.lineage(),
        base.binding(),
        base.provenance(),
        base.executors(),
        base.audience(),
        base.consumers());
  }

  // ---------------------------------------------------------------------------
  // 6. offeredWireNames is a projection of emit, not a second list
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("offeredWireNames() returns exactly the function names emit() produced")
  void offeredWireNames_projectsEmit() {
    var admin = new StubTool("admin_op", RiskTier.LOW, "x");
    var search = new StubTool("search_index", RiskTier.LOW, "y");
    var catalog =
        catalogOf(withAudience(admin.toOperation(), Audience.OPERATOR), search.toOperation());
    AgentToolEmitter emitter = filteringEmitter(alwaysFiring());

    Set<String> offered = emitter.offeredWireNames(catalog, List.of());

    assertEquals(
        emitter.emit(catalog, List.of()).stream()
            .map(AgentToolAuthorityBoundaryTest::functionName)
            .toList(),
        List.copyOf(offered),
        "the offered set must be derived from emit(), in emission order");
    assertEquals(Set.of("core_search_index"), offered);
  }

  @Test
  @DisplayName("offeredWireNames() skips malformed entries instead of throwing")
  void offeredWireNames_skipsMalformedEntries() {
    // Tempdoc 876 §B.1 made AgentToolEmitter two-faced (offer/emit), so these stubs are anonymous
    // classes rather than lambdas. offer() returns empty deliberately: this test is about
    // offeredWireNames' projection of emit over MALFORMED entries, which have no backing Operation
    // to return — and offeredWireNames reads emit, never offer.
    AgentToolEmitter malformed =
        new AgentToolEmitter() {
          @Override
          public List<Operation> offer(OperationCatalog catalog, Collection<String> selected) {
            return List.of();
          }

          @Override
          public List<Map<String, Object>> emit(
              OperationCatalog catalog, Collection<String> selected) {
          List<Map<String, Object>> out = new ArrayList<>();
          out.add(Map.<String, Object>of("type", "function")); // no `function` object
          out.add(Map.<String, Object>of("type", "function", "function", "not-a-map"));
          out.add(
              Map.<String, Object>of(
                  "type", "function", "function", Map.of("description", "no name")));
          out.add(
              Map.<String, Object>of("type", "function", "function", Map.of("name", "core_ok")));
          return out;
          }
        };

    assertEquals(Set.of("core_ok"), malformed.offeredWireNames(catalogOf(), List.of()));
  }

  // ===========================================================================
  // Harness
  // ===========================================================================

  /**
   * Drives one run in which the model's single tool call names {@code toolName}, then answers with
   * text. Returns every emitted event.
   */
  private static List<AgentEvent> runNamingTool(
      OperationCatalog catalog,
      String toolName,
      List<String> selectedToolNames,
      Predicate<String> conditionFiring,
      StubTool... handlers) {
    var ai =
        new ScriptedAiService(
            ScriptedResponse.toolCall("call-1", toolName, "{}"),
            ScriptedResponse.textOnly("done"));
    var service =
        AgentLoopServiceTest.observed(
            new AgentLoopService(
                ai, catalog, AgentLoopServiceTest.stubExecutor(handlers),
                filteringEmitter(conditionFiring), null, null));
    var request =
        new AgentRequest(
            List.of(Map.of("role", "user", "content", "go")), selectedToolNames, 3);
    var events = new CopyOnWriteArrayList<AgentEvent>();
    service.runAgent(request, events::add, EngineContextTestFixtures.AGENT_LOOP);
    return events;
  }

  private static void assertRejectedNotDispatched(
      List<AgentEvent> events, StubTool withheld, String wireName) {
    var rejected = firstOfType(events, AgentEvent.ToolCallRejected.class);
    assertNotNull(rejected, "an un-offered tool call must emit ToolCallRejected");
    assertEquals("call-1", rejected.callId(), "the rejection must name the model's call id");
    assertTrue(
        rejected.reason().contains(wireName)
            && rejected.reason().contains("not available in this session"),
        "the rejection reason should name the tool and say it is unavailable; actual: "
            + rejected.reason());

    assertEquals(
        0, withheld.callCount.get(), "the withheld operation's handler must never be invoked");
    assertNull(
        firstOfType(events, AgentEvent.ToolExecutionStarted.class),
        "a refused tool call must not reach execution");

    // Not terminal: the run continues and finishes normally — a model mis-step is not fatal.
    assertNull(
        firstOfType(events, AgentEvent.AgentError.class),
        "an un-offered tool call must not error the run");
    assertNotNull(
        firstOfType(events, AgentEvent.AgentDone.class),
        "the run should complete after the refusal");
    assertFalse(
        events.stream()
            .anyMatch(e -> e instanceof AgentEvent.ToolCallApproved),
        "a refused tool call must never pass the safety gate");
  }

  /**
   * A test emitter mirroring {@code AgentOperationEmitter}'s filter chain — executor tag, audience
   * allow-list, availability expression, selection — because a trivial emitter would make the
   * withheld-tool tests vacuous. (app-agent tests deliberately do not depend on app-services; see
   * {@code AgentLoopServiceTest.stubEmitter}.)
   */
  private static AgentToolEmitter filteringEmitter(Predicate<String> conditionFiring) {
    Set<Audience> allowed = EnumSet.of(Audience.USER, Audience.AGENT);
    return new AgentToolEmitter() {
      /**
       * Tempdoc 876 §B.1: offer() is the membership authority and emit() its wire projection, so
       * this stub applies ONE filter chain to both faces. Returning something different here would
       * make the stub disagree with itself and quietly invalidate every test built on it.
       */
      @Override
      public List<Operation> offer(OperationCatalog catalog, Collection<String> selectedNames) {
        List<Operation> offered = new ArrayList<>();
        for (Operation op : catalog.definitions()) {
          if (!op.executors().contains(ExecutorTag.AGENT)) continue;
          if (!allowed.contains(op.audience())) continue;
          if (!evaluate(op.availability().expression().orElse(null), conditionFiring)) continue;
          String wire = OperationCatalog.toWireName(op.id());
          if (selectedNames != null
              && !selectedNames.isEmpty()
              && !selectedNames.contains(wire)
              && !selectedNames.contains(op.id().value())) {
            continue;
          }
          offered.add(op);
        }
        return List.copyOf(offered);
      }

      @Override
      public List<Map<String, Object>> emit(
          OperationCatalog catalog, Collection<String> selectedNames) {
      var mapper = new tools.jackson.databind.ObjectMapper();
      List<Map<String, Object>> result = new ArrayList<>();
      for (Operation op : catalog.definitions()) {
        if (!op.executors().contains(ExecutorTag.AGENT)) continue;
        if (!allowed.contains(op.audience())) continue;
        if (!evaluate(op.availability().expression().orElse(null), conditionFiring)) continue;
        String wire = OperationCatalog.toWireName(op.id());
        if (selectedNames != null
            && !selectedNames.isEmpty()
            && !selectedNames.contains(wire)
            && !selectedNames.contains(op.id().value())) {
          continue;
        }
        try {
          var function = mapper.createObjectNode();
          function.put("name", wire);
          function.put("description", op.presentation().descriptionKey().value());
          function.set("parameters", mapper.readTree(op.intf().inputs()));
          var toolObj = mapper.createObjectNode();
          toolObj.put("type", "function");
          toolObj.set("function", function);
          @SuppressWarnings("unchecked")
          Map<String, Object> entry = mapper.convertValue(toolObj, Map.class);
          result.add(new java.util.LinkedHashMap<>(entry));
        } catch (Exception e) {
          throw new IllegalStateException("Failed to emit " + op.id(), e);
        }
      }
      return List.copyOf(result);
      }
    };
  }

  /** Minimal stand-in for {@code AvailabilityEvaluator} (which lives in app-services). */
  private static boolean evaluate(AvailabilityExpression expr, Predicate<String> firing) {
    if (expr == null) {
      return true;
    }
    return switch (expr) {
      case AvailabilityExpression.Always ignored -> true;
      case AvailabilityExpression.ConditionMatches cm -> firing.test(cm.conditionId());
      case AvailabilityExpression.AllOf allOf ->
          allOf.children().stream().allMatch(child -> evaluate(child, firing));
      case AvailabilityExpression.AnyOf anyOf ->
          anyOf.children().stream().anyMatch(child -> evaluate(child, firing));
      case AvailabilityExpression.Not not -> !evaluate(not.child(), firing);
    };
  }

  private static Predicate<String> alwaysFiring() {
    return conditionId -> true;
  }

  private static Operation withAudience(Operation op, Audience audience) {
    return new Operation(
        op.id(),
        op.presentation(),
        op.intf(),
        op.policy(),
        op.availability(),
        op.lineage(),
        op.binding(),
        op.provenance(),
        op.executors(),
        audience,
        op.consumers());
  }

  private static Operation requiring(Operation op, String conditionId) {
    return op.withAvailability(
        new OperationAvailability(
            Optional.of(new AvailabilityExpression.ConditionMatches(conditionId)),
            Optional.empty()));
  }

  private static OperationCatalog catalogOf(Operation... ops) {
    return OperationCatalog.of("core", List.of(ops));
  }

  private static String functionName(Map<String, Object> tool) {
    return String.valueOf(((Map<?, ?>) tool.get("function")).get("name"));
  }

  private static <T extends AgentEvent> T firstOfType(List<AgentEvent> events, Class<T> type) {
    for (AgentEvent e : events) {
      if (type.isInstance(e)) {
        return type.cast(e);
      }
    }
    return null;
  }
}
