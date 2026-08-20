/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import io.justsearch.agent.api.conversation.ExecutionMode;
import io.justsearch.agent.api.conversation.IterationMode;
import io.justsearch.agent.api.conversation.PersistenceMode;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ConversationShape;
import io.justsearch.agent.api.registry.ConversationShapeRef;
import io.justsearch.agent.api.registry.EventDescriptor;
import io.justsearch.agent.api.registry.EventField;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import java.util.List;
import java.util.Optional;

/**
 * The {@link ConversationShape} manifest entry for the agent loop's encapsulated
 * {@link ExecutionMode#SHAPE_DRIVEN} {@link IterationMode#WITHIN_TURN_ITERATION} ×
 * {@link PersistenceMode#PERSISTENT} cell.
 *
 * <p>Per tempdoc 491 §5.3 + §8: the agent shape is one of the four cells in the
 * Iteration × Persistence partition. Its body — {@code AgentLoopService} + {@code AgentSession}
 * + {@code AgentRunStore} — is encapsulated unchanged behind {@link ToolIteratingShapeRunner}.
 *
 * <p>The manifest declares no per-SPI ids ({@code promptContributorIds},
 * {@code contextInjectorIds}, {@code streamConsumerIds}, {@code iterationControllerId} are
 * all empty / null) because the agent loop composes its own behavior internally. The shape
 * carries the same audience/provenance fields any other shape would; the engine validates
 * them before delegating to the runner.
 *
 * <p>{@link #ID} is the stable ref ({@code core.agent-run}). The same id is used by the
 * runner ({@link ToolIteratingShapeRunner#shapeId}) and by callers that look up the shape
 * from the catalog.
 */
public final class AgentRunShape {

  /** Stable {@link ConversationShapeRef} for the agent shape. */
  public static final ConversationShapeRef ID = new ConversationShapeRef("core.agent-run");

  /** I18n key for the shape's display label (FE-resolved per §C.E). */
  public static final I18nKey LABEL_KEY =
      new I18nKey("registry-conversation-shape.agent-run.label");

  /** I18n key for the shape's display description. */
  public static final I18nKey DESCRIPTION_KEY =
      new I18nKey("registry-conversation-shape.agent-run.description");

  /**
   * SSE event vocabulary the agent shape emits. Per tempdoc 491 §5.4: the agent loop's
   * existing event vocabulary is preserved unchanged (bare names, not {@code agent.}-prefixed)
   * for FE compatibility under the encapsulation contract. Future fresh shapes follow the
   * namespacing convention.
   */
  private static final List<String> RISK_VALUES = List.of("low", "medium", "high");

  // Typed, producer-bound projection of ToolIteratingShapeRunner.buildPayload/eventName
  // (tempdoc 564 facet 4b). Corrected vs the former name-only list: the phantom
  // navigate.url_* events (declared-but-never-produced, drift D4) are dropped, and
  // tool_call_virtual (D1) + tool_batch_proposed (D2) — produced-but-undeclared — are added.
  // AgentEventSchemaConformanceTest binds this to the producer so it cannot drift again.
  // Every agent event additionally carries the optional shared trace envelope (ofTraced).
  private static final List<EventDescriptor> EVENT_SCHEMA =
      List.of(
          EventDescriptor.ofTraced("session_started", EventField.string("sessionId")),
          EventDescriptor.ofTraced("chunk", EventField.string("text")),
          EventDescriptor.ofTraced("reasoning_chunk", EventField.string("text")),
          EventDescriptor.ofTraced(
              "tool_call_proposed",
              EventField.string("callId"),
              EventField.string("toolName"),
              EventField.string("arguments"),
              EventField.enumOf("risk", RISK_VALUES)),
          EventDescriptor.ofTraced(
              "tool_batch_proposed", EventField.arrayOfObject("calls", "ProposedCall")),
          EventDescriptor.ofTraced(
              "tool_call_pending",
              EventField.string("callId"),
              EventField.string("toolName"),
              EventField.string("arguments"),
              EventField.enumOf("risk", RISK_VALUES),
              EventField.string("gateBehavior").asOptional()),
          EventDescriptor.ofTraced("tool_call_approved", EventField.string("callId")),
          // Tempdoc 565 §30 — the DIRECTION authority's mid-run steer acknowledgement.
          EventDescriptor.ofTraced("directive_acknowledged", EventField.string("directiveText")),
          EventDescriptor.ofTraced(
              "tool_exec_started", EventField.string("callId"), EventField.string("toolName")),
          EventDescriptor.ofTraced(
              "tool_exec_completed",
              EventField.string("callId"),
              EventField.bool("success"),
              EventField.string("output"),
              EventField.string("executionId"),
              // Tempdoc 560 Phase 1 — present only when the operation produced structured content
              // (e.g. an MCP tool's image/resource blocks). Free-form map → Record<string, unknown>
              // on the FE (empty objectType). Declared here so the descriptor is a complete
              // projection of the emitter (the AgentEventPayloadConformanceTest invariant).
              EventField.object("structuredData", "").asOptional()),
          EventDescriptor.ofTraced(
              "tool_call_rejected", EventField.string("callId"), EventField.string("reason")),
          EventDescriptor.ofTraced(
              "tool_call_virtual",
              EventField.string("callId"),
              EventField.string("wireName"),
              EventField.string("arguments")),
          EventDescriptor.ofTraced(
              "done",
              EventField.string("finalResponse"),
              EventField.number("iterationsUsed"),
              EventField.number("toolCallsExecuted"),
              EventField.number("totalTokensUsed"),
              // Tempdoc 565 §3.A — the answer's grounding sources + per-sentence inline citations.
              // Optional: empty when the run was ungrounded or the matcher did not run/match.
              EventField.arrayOfObject("sources", "AgentSource").asOptional(),
              EventField.arrayOfObject("citations", "AgentSentenceCite").asOptional(),
              // Tempdoc 859 §4 — which producer scored the `citations` above (the `ScorerKind` wire
              // name). Optional because ABSENT is load-bearing on the READ side: a record persisted
              // before this field is a pre-stamp record, which the 836 §4 gate admits, whereas a
              // known non-cross-encoder producer fails closed. Every live emitter stamps it.
              EventField.string("citationScorer").asOptional()),
          EventDescriptor.ofTraced(
              "error",
              EventField.string("error"),
              EventField.string("errorCode"),
              EventField.string("errorClass").asOptional(),
              EventField.string("retryAction").asOptional(),
              EventField.number("retryAttempt").asOptional(),
              EventField.string("i18nKey").asOptional()),
          EventDescriptor.ofTraced(
              "progress",
              EventField.string("phase"),
              EventField.string("message"),
              EventField.number("iteration"),
              EventField.number("maxIterations"),
              // Tempdoc 577 Ext II — severity rides the dispatch path too (optional: legacy
              // persisted events lack it).
              EventField.string("severity").asOptional()),
          EventDescriptor.ofTraced(
              "budget_update",
              EventField.string("phase"),
              EventField.number("tokensConsumed"),
              EventField.number("tokensRemaining"),
              // Tempdoc 577 Ext III — run-cumulative consumption (optional: replayed old records
              // lack it; the FE falls back to the legacy derivation).
              EventField.number("totalTokensConsumed").asOptional(),
              // Tempdoc 577 §2.14 Root II (#14) — cognitive-headroom figures (context occupancy ÷
              // n_ctx); optional: only the llm_response phase carries them, 0/absent otherwise.
              EventField.number("promptTokens").asOptional(),
              EventField.number("contextWindow").asOptional()),
          // Tempdoc 577 Move 2 — the run PARKED at the budget boundary as a held decision.
          EventDescriptor.ofTraced(
              "budget_gate",
              EventField.number("tokensNeeded"),
              EventField.number("tokensRemaining"),
              EventField.number("totalTokensConsumed")),
          // Tempdoc 577 §2.14 Root II — the run PARKED at the context-pressure boundary.
          EventDescriptor.ofTraced(
              "context_gate",
              EventField.number("promptTokens"),
              EventField.number("contextWindow")),
          // Tempdoc 577 §2.14 Root II — the first-class compaction narratable event.
          EventDescriptor.ofTraced("context_compacted", EventField.number("droppedMessages")),
          EventDescriptor.ofTraced(
              "handoff_proposed",
              EventField.string("fromAgentId"),
              EventField.string("toAgentId"),
              EventField.string("reason")),
          EventDescriptor.ofTraced(
              "handoff_executed",
              EventField.string("fromAgentId"),
              EventField.string("toAgentId")),
          // Tempdoc 585 §D Phase 2 (C4) — the one-shot state primer for a (re)attaching observer.
          // Tempdoc 834 §6 enriches it into the RECOVERY authority: every fact required to ACT on a
          // run lives here, because the replay ring evicts and the primer never does.
          EventDescriptor.ofTraced(
              "state_snapshot",
              EventField.number("iteration"),
              EventField.number("budgetRemaining"),
              EventField.number("toolCallsExecuted"),
              EventField.number("messageCount"),
              EventField.string("activeAgentId"),
              // Optional NOT because the producer elides it — it always emits the key — but because
              // a legacy events.ndjson record predates it. Absent must therefore read as UNKNOWN on
              // the FE, never as "none pending" (834 §6.3.3).
              EventField.arrayOfObject("pendingApprovals", "PendingApproval").asOptional(),
              EventField.string("autonomyLevel").asOptional(),
              // Genuinely conditional: emitted only while the run IS parked.
              EventField.object("park", "ParkSnapshot").asOptional()),
          // Emitted by the composed URLExtractor StreamConsumer (not an AgentEvent variant, so
          // outside the AgentEvent conformance check). Payload fields typed in Phase 2.
          EventDescriptor.nameOnly("intent.resolution"));

  private AgentRunShape() {}

  /**
   * Build the {@link ConversationShape} manifest entry. Called by the catalog that registers
   * core shapes.
   */
  public static ConversationShape definition() {
    return new ConversationShape(
        ID,
        new Presentation(LABEL_KEY, DESCRIPTION_KEY, Optional.empty(), Optional.empty()),
        Audience.USER,
        Provenance.core("v1"),
        ExecutionMode.SHAPE_DRIVEN,
        IterationMode.WITHIN_TURN_ITERATION,
        PersistenceMode.PERSISTENT,
        List.of(),
        List.of(),
        // Slice 491 §9.D Phase E (C4 + F1) — URL emission as a capability on the agent
        // shape. As of F1, this list is LOAD-BEARING: ToolIteratingShapeRunner resolves
        // each id via the shared StreamConsumerRegistry (the same registry the
        // substrate-driven engine uses) and invokes onDone on AgentDone in declaration
        // order. Adding an id here automatically wires that consumer through the agent
        // shape with no code change in the runner — the §9.D plan's "compose by id"
        // framing made literal.
        List.of("core.url-extractor"),
        null,
        EVENT_SCHEMA);
  }
}
