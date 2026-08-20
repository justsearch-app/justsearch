package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentEventPayloads;
import io.justsearch.agent.api.ToolCallRequest;
import io.justsearch.agent.api.TraceContext;
import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.registry.EventDescriptor;
import io.justsearch.agent.api.registry.EventField;
import io.justsearch.agent.api.registry.GateBehavior;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.RiskTier;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * tempdoc 565 §3.A follow-up — FIELD-level producer conformance for the agent event schema.
 *
 * <p>{@link AgentEventSchemaConformanceTest} pins event NAMES (declared ⇔ produced). This pins event
 * FIELDS: for every agent event, the emitter's payload key set must be a SUBSET of the declared
 * {@link EventDescriptor}'s field names. It closes the drift CLASS the 565 de-risk surfaced —
 * {@code done} EMITTED {@code sources}/{@code citations} but the descriptor DECLARED neither, so the
 * generated FE type lacked them and the controller survived only via a loose {@code Record<unknown>}
 * cast. That instance was fixed; this guarantees no event can re-introduce the class.
 *
 * <p>Each representative event populates EVERY field ({@code Map.of} rejects nulls) AND every
 * CONDITIONAL key the emitter can add ({@code gateBehavior}; error {@code errorClass}/{@code
 * retryAction}/{@code retryAttempt}/{@code i18nKey}; {@code structuredData}) so the emitter produces
 * its MAXIMAL key set — a conditional key that is emitted-but-undeclared would otherwise slip.
 */
final class AgentEventPayloadConformanceTest {

  private static final TraceContext NO_TRACE = TraceContext.none();

  /** One MAXIMALLY-populated representative of every {@link AgentEvent} sealed permit. */
  private static final List<AgentEvent> MAXIMAL_VARIANTS =
      List.of(
          new AgentEvent.TextChunk("t"),
          new AgentEvent.ReasoningChunk("t"),
          new AgentEvent.ToolCallProposed(new ToolCallRequest("id", "tool", "args"), RiskTier.LOW),
          new AgentEvent.ToolBatchProposed(List.of()),
          // gateBehavior populated → the conditional `gateBehavior` key is emitted.
          new AgentEvent.ToolCallPendingApproval(
              "id", "tool", "args", RiskTier.LOW, GateBehavior.AUTO),
          new AgentEvent.ToolCallApproved("id"),
          // Tempdoc 565 §30 — the DIRECTION authority's mid-run steer acknowledgement.
          new AgentEvent.DirectiveAcknowledged("Focus only on Q3."),
          new AgentEvent.ToolExecutionStarted("id", "tool"),
          // non-empty structuredData → the conditional `structuredData` key is emitted.
          new AgentEvent.ToolExecutionCompleted(
              "id", OperationResult.success("msg", Map.of("k", "v"))),
          new AgentEvent.ToolCallRejected("id", "reason"),
          new AgentEvent.ToolCallVirtual("id", "wire", "args"),
          // non-empty sources + citations → those keys are emitted.
          new AgentEvent.AgentDone(
              "resp",
              1,
              1,
              1,
              List.of(new AgentEvent.AgentSource("d", 0, "p", "ti", "ex", 1, 2, "h")),
              List.of(new AgentEvent.AgentSentenceCite("s", 0, 0.9)),
              // Tempdoc 859 §4 — the producer stamp is a payload key like any other, so the
              // conformance sweep must see a populated one.
              "CROSS_ENCODER",
              NO_TRACE),
          // all error fields populated → errorClass/retryAction/retryAttempt/i18nKey emitted.
          new AgentEvent.AgentError("err", "CODE", "CLASS", "ACTION", 1, NO_TRACE),
          new AgentEvent.AgentProgress("phase", "msg", 0, 1),
          new AgentEvent.AgentBudgetUpdate("phase", 0, 0),
          // Tempdoc 577 §2.12 Move 2 — the held budget gate (parked decision point).
          new AgentEvent.BudgetGatePending(100, 0, 2000),
          // Tempdoc 577 §2.14 Root II — the context-pressure gate + the compaction event.
          new AgentEvent.ContextGatePending(7800, 8192),
          new AgentEvent.ContextCompacted(4),
          new AgentEvent.SessionStarted("sid"),
          new AgentEvent.HandoffProposed("from", "to", "reason"),
          new AgentEvent.HandoffExecuted("from", "to"),
          // Tempdoc 585 §D Phase 2 (C4) — the state primer (all fields populated). Tempdoc 834 §6:
          // maximal means a held approval gate (so `pendingApprovals` is non-empty) AND an active
          // park (so the conditional `park` key is emitted).
          new AgentEvent.StateSnapshot(
              2,
              1500,
              3,
              7,
              "primary",
              List.of(
                  new AgentEvent.PendingApproval("id", "tool", "args", "high", "confirm")),
              "WATCH",
              new AgentEvent.ParkSnapshot("approval", 1_700_000_000_000L, "id"),
              NO_TRACE));

  @Test
  @DisplayName("MAXIMAL_VARIANTS lists every AgentEvent permit")
  void variantsAreExhaustive() {
    assertTrue(
        AgentEvent.class.getPermittedSubclasses().length == MAXIMAL_VARIANTS.size(),
        "MAXIMAL_VARIANTS must list every AgentEvent permit (a new variant was added — add it here)");
  }

  @Test
  @DisplayName("every emitted payload key is declared in the event's descriptor (no undeclared-field drift)")
  void everyEmittedFieldIsDeclared() {
    Map<String, Set<String>> declaredByEvent =
        AgentRunShape.definition().eventSchema().stream()
            .collect(
                Collectors.toMap(
                    EventDescriptor::name,
                    d -> d.fields().stream().map(EventField::name).collect(Collectors.toSet())));

    for (AgentEvent ev : MAXIMAL_VARIANTS) {
      SseEvent emitted = AgentEventSseTranslator.translate(ev, null, Map.of());
      Set<String> declared = declaredByEvent.get(emitted.name());
      assertNotNull(
          declared, "event '" + emitted.name() + "' is emitted but not declared in eventSchema");
      Set<String> undeclared =
          emitted.payload().keySet().stream()
              .filter(k -> !declared.contains(k))
              .collect(Collectors.toCollection(TreeSet::new));
      assertTrue(
          undeclared.isEmpty(),
          "event '"
              + emitted.name()
              + "' emits undeclared field(s) "
              + undeclared
              + " — add them to AgentRunShape's '"
              + emitted.name()
              + "' EventDescriptor (this is the drift class that hid 565 §3.A sources/citations).");
    }
  }

  @Test
  @DisplayName("the PERSISTED base mapping is also declared (585 §D Phase 0 — pins all three reps)")
  void everyPersistedBaseFieldIsDeclared() {
    // The persistence path (AgentRunStore) and the wire translator both delegate to the ONE
    // AgentEventPayloads authority. The persisted payload (base + trace) must be a subset of the
    // declared schema exactly like the wire payload — so the durable record cannot drift from the
    // EventDescriptor either (585 §D closed the persist-only severity/i18nKey gap).
    Map<String, Set<String>> declaredByEvent =
        AgentRunShape.definition().eventSchema().stream()
            .collect(
                Collectors.toMap(
                    EventDescriptor::name,
                    d -> d.fields().stream().map(EventField::name).collect(Collectors.toSet())));

    for (AgentEvent ev : MAXIMAL_VARIANTS) {
      String name = AgentEventPayloads.name(ev);
      Map<String, Object> persisted = AgentEventPayloads.withTrace(AgentEventPayloads.base(ev), ev.trace());
      Set<String> declared = declaredByEvent.get(name);
      assertNotNull(declared, "event '" + name + "' base is emitted but not declared in eventSchema");
      Set<String> undeclared =
          persisted.keySet().stream()
              .filter(k -> !declared.contains(k))
              .collect(Collectors.toCollection(TreeSet::new));
      assertTrue(
          undeclared.isEmpty(),
          "event '" + name + "' persists undeclared field(s) " + undeclared
              + " — add them to AgentRunShape's '" + name + "' EventDescriptor.");
    }
  }
}
