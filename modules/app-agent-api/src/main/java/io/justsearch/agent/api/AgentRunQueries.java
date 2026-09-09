/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.registry.OperationResult;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The agent capability's READ-TIME query/projection surface (tempdoc 584 §B.4): operation history,
 * persisted-session snapshots/lists, the resume bridge, and the unified-thread / lifecycle / presence
 * projections over the durable agent-run record.
 *
 * <p>Segregated out of {@link AgentService} (which now {@code extends} it) so a read-only consumer —
 * e.g. the interaction-thread controller — can depend on this narrow surface rather than the full
 * loop+control interface. Every method is a pure read; none mutate live agent-loop state (the resume
 * methods re-enter the loop through the implementation's own runner, not through this contract). This
 * is the §B.2 *breadth-axis* boundary: new read-projection features attach here, not on the
 * orchestrator.
 */
public interface AgentRunQueries {

  /**
   * List all available Operation entries (tempdoc 429 §E.4 substrate).
   *
   * <p>Per Phase 10 of tempdoc 429: replaces the legacy {@code availableTools()} method
   * which returned {@code List<ToolDefinition>}. The substrate's Operation type carries
   * the same data (id, presentation, parameter schema, risk policy, undo support) plus
   * provenance/executors metadata.
   *
   * <p><b>This is the FULL catalog, not the offering</b> (tempdoc 876 §A.0/§B.1). Its consumers —
   * {@code AgentSseWriter}, {@code ToolIteratingShapeRunner}, {@code ConversationApiAssembly} —
   * index it BY TOOL NAME to RESOLVE a tool the model has already emitted a call for, so it must
   * stay wide: narrowing it would make a call the model actually made unresolvable (an availability
   * flip mid-run, or a tool outside this run's selection, would render as an unknown tool). Ask
   * {@link #offeredOperations()} for what the model was shown.
   */
  List<io.justsearch.agent.api.registry.Operation> availableOperations();

  /**
   * Tempdoc 876 §B.1 — the operations the model is actually OFFERED: {@link #availableOperations()}
   * narrowed by the emitter's full filter chain (AGENT executor, audience allow-list, evaluated
   * availability). This is the read-side projection of
   * {@code AgentToolEmitter.offer(catalog, List.of())} — the same authority that decides the wire
   * tool list, never a second derivation from the raw declarations.
   *
   * <p>The distinction is load-bearing and the two reads are NOT interchangeable: this one answers
   * "what can the model see?" (the trust panel's question — a tool hidden by availability must be
   * absent here), {@link #availableOperations()} answers "what does this tool name refer to?" (the
   * resolvers' question — a name must resolve even if it is no longer offered).
   *
   * <p>Note this read applies no selection: it is the offering for a run with no
   * {@code selectedToolNames} restriction, which is what an inventory endpoint wants.
   */
  List<io.justsearch.agent.api.registry.Operation> offeredOperations();

  /** Undo a previous tool execution by its execution ID. */
  default OperationResult undoOperation(String toolName, String executionId, EngineContext engineContext) {
    throw new UnsupportedOperationException("Undo not supported");
  }

  /** List recent file operation batches (newest first). */
  default List<Map<String, Object>> operationHistory(int limit) {
    return List.of();
  }

  /** Get full detail of a specific operation batch. Returns null if not found. */
  default Map<String, Object> operationDetail(String batchId) {
    return null;
  }

  /** Returns the most recent persisted agent session snapshot for resume/debug flows. */
  default Map<String, Object> lastSessionSnapshot() {
    return null;
  }

  /** Resume the most recent persisted session. */
  default void resumeLastSession(Consumer<AgentEvent> eventConsumer, EngineContext engineContext) {
    throw new UnsupportedOperationException("Resume not supported");
  }

  /**
   * List recent persisted session summaries (newest first). Tempdoc 415 follow-up (C20). Default
   * returns empty so the unavailable service and lightweight test mocks compile unchanged.
   */
  default List<Map<String, Object>> listSessions(int limit) {
    return List.of();
  }

  /**
   * Tempdoc 859 slice C PR-2 — the CONVERSATIONS the agent-run record backs, newest first: the
   * run-side half of the two-store join {@code GET /api/chat/conversations} performs, so a delegate
   * conversation (which mints no {@code ConversationStore} row) is listable at all. Rows are
   * {@code {conversationId, createdAtMs, lastActiveAtMs, firstUserMessage, runCount}} — deliberately
   * NO message count: deriving one means projecting each conversation's whole event stream per row,
   * per request. Default empty for the unavailable service + test mocks.
   */
  default List<Map<String, Object>> conversationSummaries(int limit) {
    return List.of();
  }

  /**
   * Returns the full persisted snapshot for a specific session, or {@code null} if none exists.
   * Tempdoc 415 follow-up (C20). Mirrors {@link #lastSessionSnapshot()} but addressed by id.
   */
  default Map<String, Object> sessionSnapshot(String sessionId) {
    return null;
  }

  /**
   * Resume a specific persisted session by id. Tempdoc 415 follow-up (C20). Sibling of
   * {@link #resumeLastSession(Consumer, EngineContext)} — emits the same events and inherits the same
   * resume-state safety gate.
   */
  default void resumeSession(String sessionId, Consumer<AgentEvent> eventConsumer, EngineContext engineContext) {
    throw new UnsupportedOperationException("Resume not supported");
  }

  /**
   * Tempdoc 585 §D Phase 3 (C2) — time-travel FORK: branch a NEW run from a finished one, rewinding
   * to its last user turn and optionally editing that question ({@code editedMessage}; blank re-runs
   * the original). No resume-state gate — a fork is meaningful for a DONE/ERRORED run.
   */
  default void forkSession(
      String sessionId, String editedMessage, Consumer<AgentEvent> eventConsumer, EngineContext engineContext) {
    throw new UnsupportedOperationException("Fork not supported");
  }

  /** Returns the persisted event stream for a given session ID. */
  default List<Map<String, Object>> sessionEvents(String sessionId) {
    return List.of();
  }

  /**
   * Tempdoc 561 P-A/P-B (correction): the agent plane's contribution to the unified thread — the
   * thread events (user prompt, final responses, tool activity, errors, handoffs) of every agent run
   * belonging to {@code conversationId}, projected READ-TIME from {@code AgentRunStore} (no second
   * store). Default empty for the unavailable service + test mocks.
   */
  default List<io.justsearch.agent.api.interaction.InteractionEvent> threadEvents(
      String conversationId) {
    return List.of();
  }

  /**
   * Tempdoc 863 §4.A.5 (F2) — the same projection, told which runs' answers the ANSWER plane already
   * holds. A run stamped {@code recordsToThread} has its terminal answer suppressed here so one
   * delegate turn does not render twice; but the stamp is written when the run STARTS, and the
   * answer is appended when it ends, so a store that becomes locked or unwritable mid-run leaves a
   * stamped run whose answer reached neither plane. Passing the ids the caller can actually see on
   * the record makes the suppression conditional on the duplicate EXISTING — which also self-heals
   * every run that already failed that way, with no backfill.
   *
   * <p>{@code answeredRunIds} comes from the one place both planes are visible (the thread
   * controller), read off the {@code runId} the engine stamps onto each answer it records.
   *
   * <p>The default DELEGATES to the single-argument form rather than returning empty, so an
   * implementor that overrides only that one keeps answering this call with its own events —
   * degraded to the pre-F2 behaviour, never silently blanked. The refinement is opt-in; the
   * projection is not.
   */
  default List<io.justsearch.agent.api.interaction.InteractionEvent> threadEvents(
      String conversationId, java.util.Set<String> answeredRunIds) {
    return threadEvents(conversationId);
  }

  /**
   * Tempdoc 871 §3b — the same projection, plus the trailing reasoning whose carrier this plane
   * suppressed (see {@link io.justsearch.agent.api.interaction.ThreadProjection}). Callers that can
   * see BOTH planes should prefer this over {@link #threadEvents(String, java.util.Set)}: it is the
   * only form that lets the orphaned block reach the answer it was thought for, instead of being
   * re-homed onto an event that happened before it.
   *
   * <p>The default DELEGATES for the same reason the two-argument {@code threadEvents} does — an
   * implementor that overrides only the older form keeps answering with its own events, and simply
   * hands nothing across.
   */
  default io.justsearch.agent.api.interaction.ThreadProjection threadProjection(
      String conversationId, java.util.Set<String> answeredRunIds) {
    return io.justsearch.agent.api.interaction.ThreadProjection.of(
        threadEvents(conversationId, answeredRunIds));
  }

  /**
   * Tempdoc 561 P-A / P-A2: the typed {@link io.justsearch.agent.api.lifecycle.AgentLifecycle} loop
   * objects (Session ⊃ Turn ⊃ Iteration + state + budget) for every agent run of {@code
   * conversationId}, projected from the durable {@code AgentRunStore} record. Default empty for the
   * unavailable service + test mocks.
   */
  default List<io.justsearch.agent.api.lifecycle.AgentLifecycle> lifecycles(String conversationId) {
    return List.of();
  }

  /**
   * Tempdoc 561 P-D2 — the presence axis / render-on-return inbox source: the typed lifecycles of
   * BACKGROUND (non-interactive) agent runs that completed strictly after {@code since}, i.e. "what
   * the agent did while you were away". Projected from the durable {@code AgentRunStore} record (the
   * runs the {@code BackgroundRunService} produced + stamped {@code background=true}); never a second
   * store. {@code since == null} returns all background runs. Default empty for the unavailable
   * service + test mocks.
   */
  default List<io.justsearch.agent.api.lifecycle.AgentLifecycle> presenceSince(
      java.time.Instant since) {
    return List.of();
  }
}
