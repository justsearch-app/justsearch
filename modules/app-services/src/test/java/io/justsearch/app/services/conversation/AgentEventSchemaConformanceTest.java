package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentEventPayloads;
import io.justsearch.agent.api.registry.EventDescriptor;
import io.justsearch.agent.api.registry.RiskTier;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * tempdoc 564 — producer-conformance gate for the agent shape's event schema.
 *
 * <p>Binds the declared {@link AgentRunShape} {@link EventDescriptor}s to the actual producer
 * ({@link ToolIteratingShapeRunner#eventName}) so the typed schema is a projection that cannot
 * drift from what the agent loop emits. This is the structural fix for the drift class the 564
 * de-risk pass found in the agent-event contract: {@code tool_batch_proposed} / {@code
 * tool_call_virtual} were produced but undeclared (D1/D2), and {@code navigate.url_*} were
 * declared but never produced (D4). Because {@code eventName} switches on type only, the
 * variants below are constructed type-only (field values are irrelevant).
 */
final class AgentEventSchemaConformanceTest {

  /** One representative of every {@link AgentEvent} sealed permit (type-only construction). */
  private static final List<AgentEvent> ALL_VARIANTS =
      List.of(
          new AgentEvent.TextChunk((String) null),
          new AgentEvent.ReasoningChunk((String) null),
          new AgentEvent.ToolCallProposed(null, (RiskTier) null),
          new AgentEvent.ToolBatchProposed(List.of()),
          new AgentEvent.ToolCallPendingApproval(null, null, null, (RiskTier) null),
          new AgentEvent.ToolCallApproved((String) null),
          new AgentEvent.DirectiveAcknowledged(null, null, null, null),
          new AgentEvent.ToolExecutionStarted(null, null),
          new AgentEvent.ToolExecutionCompleted(null, null),
          new AgentEvent.ToolCallRejected(null, null),
          new AgentEvent.ToolCallVirtual(null, null, null),
          new AgentEvent.AgentDone(null, 0, 0, 0),
          new AgentEvent.AgentError(null, (String) null),
          new AgentEvent.AgentProgress(null, null, 0, 0),
          new AgentEvent.AgentBudgetUpdate(null, 0, 0),
          new AgentEvent.BudgetGatePending(0, 0, 0),
          new AgentEvent.ContextGatePending(0, 0),
          new AgentEvent.ContextCompacted(0),
          new AgentEvent.SessionStarted((String) null),
          new AgentEvent.HandoffProposed(null, null, null),
          new AgentEvent.HandoffExecuted(null, null),
          new AgentEvent.StateSnapshot(0, 0, 0, 0, null));

  @Test
  @DisplayName("agent-run eventSchema matches the producer's emitted event names exactly")
  void schemaMatchesProducer() {
    // ALL_VARIANTS must cover every sealed permit, so the producer mapping below is exhaustive.
    assertEquals(
        AgentEvent.class.getPermittedSubclasses().length,
        ALL_VARIANTS.size(),
        "ALL_VARIANTS must list every AgentEvent permit (a new variant was added — add it here)");

    Set<String> produced =
        ALL_VARIANTS.stream()
            .map(AgentEventPayloads::name)
            .collect(Collectors.toCollection(TreeSet::new));

    // intent.resolution is emitted by the composed URLExtractor StreamConsumer, not an AgentEvent
    // variant, so it is outside this producer-conformance check.
    Set<String> declared =
        AgentRunShape.definition().eventSchema().stream()
            .map(EventDescriptor::name)
            .filter(name -> !name.equals("intent.resolution"))
            .collect(Collectors.toCollection(TreeSet::new));

    assertEquals(
        produced,
        declared,
        "agent-run eventSchema must equal the producer's emitted names: a name in 'declared' but "
            + "not 'produced' is phantom drift (D4); a name in 'produced' but not 'declared' is "
            + "missing drift (D1/D2). Update AgentRunShape.EVENT_SCHEMA to match the producer.");
  }
}
