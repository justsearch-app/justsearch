/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api;

import io.justsearch.core.context.EngineContext;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Top-level service interface for the agent capability.
 *
 * <p>Tempdoc 584 §B.4: the READ-TIME query/projection surface (operation history, session
 * snapshots, resume, thread/lifecycle/presence projections) is segregated into the narrow
 * {@link AgentRunQueries} super-interface so read-only consumers can depend on it alone; this
 * interface adds the loop + live-run control surface on top.
 */
public interface AgentService extends AgentRunQueries {

  /**
   * Start an agent session. Events are emitted to the consumer as the agent loop progresses. This
   * method blocks until the agent loop completes or is cancelled.
   */
  void runAgent(AgentRequest request, Consumer<AgentEvent> eventConsumer, EngineContext engineContext);

  /**
   * Tempdoc 561 P-D — start an agent session marked as a BACKGROUND (non-interactive) run when
   * {@code background} is true: the safety gate becomes safe-by-default (no watcher → write/destructive
   * tool calls are rejected immediately, never silently auto-run). The default delegates to the
   * interactive {@link #runAgent(AgentRequest, Consumer, EngineContext)} so implementations without background support
   * compile unchanged; {@code AgentLoopService} overrides it to thread the flag onto the session.
   */
  default void runAgent(
      AgentRequest request, Consumer<AgentEvent> eventConsumer, boolean background, EngineContext engineContext) {
    runAgent(request, eventConsumer, engineContext);
  }

  /** Approve a pending tool call within a session. */
  void approveToolCall(String sessionId, String callId);

  /** Reject a pending tool call within a session. */
  void rejectToolCall(String sessionId, String callId, String reason);

  /**
   * Tempdoc 565 §15.C enforcement — approve a pending tool call and report whether a gate with that
   * {@code callId} actually existed in the session (and was completed). The unified approval dispatch
   * ({@code POST /api/chat/approve}) uses the boolean to decide whether to fall through to the workflow
   * gate registry. Default returns {@code false} (no gate found) so unavailable / mock services and the
   * Null Object compile unchanged; {@code AgentLoopService} overrides it with the real lookup.
   */
  default boolean tryApproveToolCall(String sessionId, String callId) {
    return false;
  }

  /** Reject sibling of {@link #tryApproveToolCall} — see its contract. */
  default boolean tryRejectToolCall(String sessionId, String callId, String reason) {
    return false;
  }

  /**
   * Tempdoc 561 P-D — update the live autonomy dial ({@code "watch"|"assist"|"auto"}) for a running
   * session, so a mid-run dial change takes effect on the next gated tool call. The default is a
   * no-op so legacy {@code AgentService} implementations compile unchanged.
   */
  default void setSessionAutonomy(String sessionId, String autonomyLevelWire) {
    // no-op default
  }

  /**
   * Tempdoc 565 §30 — the DIRECTION authority's {@code interject}: queue a free-form human steering
   * directive for a running session (POST /api/chat/agent/steer), drained at the next step boundary
   * and folded into the next LLM call. Returns true when the session was found and the directive
   * queued; false (404) for an unknown/finished session. Default no-op returns false so legacy
   * implementations compile unchanged.
   */
  default boolean injectSteeringDirective(String sessionId, String text) {
    return false;
  }

  /**
   * Tempdoc 577 Ext III — the over-budget remedy: grant a running session additional tokens (POST
   * /api/chat/agent/budget). Returns true when the session was found and the budget raised; false
   * (404) for an unknown/finished session or a non-positive grant. Default no-op returns false so
   * legacy implementations compile unchanged.
   */
  default boolean raiseSessionBudget(String sessionId, int addTokens) {
    return false;
  }

  /**
   * Tempdoc 577 §2.12 Move 2 — resolve a HELD budget gate (POST /api/chat/agent/budget-decision).
   * {@code decision} ∈ {@code "finalize"} (synthesize from what the run has) | {@code "stop"}
   * (cancel at the gate). CONTINUE is not a value here — it arrives via
   * {@link #raiseSessionBudget} (raising the budget IS the continue decision). Returns true when a
   * gate was held and resolved; false (404) when the run is not parked. Default no-op so legacy
   * implementations compile unchanged.
   */
  default boolean resolveBudgetGate(String sessionId, String decision) {
    return false;
  }

  /**
   * Tempdoc 577 §2.14 Root II (#14) — resolve a HELD context-pressure gate (POST
   * /api/chat/agent/context-decision). {@code decision} ∈ {@code "continue"} (proceed with the large
   * prompt) | {@code "summarize"} (compact older turns, then resume) | {@code "stop"} (cancel at the
   * gate). Returns true when a gate was held and resolved; false (404) when the run is not parked.
   * Default no-op so legacy implementations compile unchanged.
   */
  default boolean resolveContextGate(String sessionId, String decision) {
    return false;
  }

  /**
   * Tempdoc 834 §1.3 — installs the substrate a run is journaled and observed through. Late-bound
   * by the composition root because the agent is constructed lazily when AI activates; setting it
   * once at boot would miss the instance that is actually used. Default no-op so the null object
   * and legacy implementations compile unchanged.
   */
  default void setRunObservation(RunObservation observation) {}

  /**
   * Tempdoc 577 §2.14 Root I (#13) / 834 §1.3 — ATTACH a new observer to a LIVE run (POST
   * /api/chat/agent/{sessionId}/attach). The observer receives the act-on-the-run primer, then the
   * replay, then ongoing frames, and this call BLOCKS (streaming to the observer) until the run
   * terminates — exactly like the initiating run stream. N observers can attach to one run; a
   * dropped observer never affects the run or the others. Returns true when a LIVE session was
   * found and observed; false (404) when the run is not live (terminal/evicted — the caller falls
   * back to the persisted record).
   *
   * <p><strong>Contract change (834 §7-S3b).</strong> The observer now receives
   * {@link RunObservation.WireFrame} — the already-projected {@code (name, payload)} pair — rather
   * than a typed {@link AgentEvent}. The journal carries the projection of the ONE payload
   * authority; handing observers raw events would mean either a second serialization path or a
   * second vocabulary, which is the drift {@link AgentEventPayloads} exists to prevent.
   *
   * <p>Default returns false so legacy implementations compile unchanged.
   */
  default boolean attachToRun(String sessionId, Consumer<RunObservation.WireFrame> observer) {
    return attachToRun(sessionId, 0L, observer);
  }

  /**
   * ATTACH, replaying only the frames after {@code sinceSeq} (the run stream's {@code ?sinceSeq=}
   * cursor — the CHANNEL sequence, not the {@code Last-Event-ID} trace span the pre-834 attach
   * used; §1.6 closes that second, unvalidated resume channel by not emitting {@code id:} at all).
   * Zero replays everything and is the guaranteed path. Default returns false so legacy
   * implementations compile unchanged.
   */
  default boolean attachToRun(
      String sessionId, long sinceSeq, Consumer<RunObservation.WireFrame> observer) {
    return false;
  }

  /**
   * Tempdoc 508 §11.5 / §13.5 Phase B — deliver the result of a
   * frontend-executed virtual tool ({@code vop_*} prefix) back to
   * the agent loop. The FE listens for {@code tool_call_virtual}
   * SSE events, invokes the matching shell/plugin command, and
   * POSTs the captured success/output here via
   * {@code POST /api/chat/agent/tool-result}. Returns true when
   * the call was found and the agent's wait future resolved;
   * false when no pending call had that callId (404).
   *
   * <p>Default implementation returns false (no-op) so old
   * AgentService implementations without virtual-tool support
   * compile unchanged.
   */
  default boolean completeVirtualToolCall(
      String sessionId, String callId, boolean success, String output, String errorDetail) {
    return false;
  }

  /** Cancel an active agent session. */
  void cancelSession(String sessionId);

  /**
   * Tempdoc S4b (Search Thread) — persist a SEARCH interaction event for {@code conversationId}: a
   * manually-triggered search action performed OUTSIDE the agent loop (not a tool call), so a reload
   * of {@code GET /api/thread/{id}} still shows the committed search card (POST
   * {@code /api/thread/{id}/events}). Returns the persisted event's id, or {@code null} if {@code
   * conversationId} is blank or the capability is unavailable. Default no-op (null) so legacy
   * {@code AgentService} implementations compile unchanged.
   */
  default String appendSearchEvent(String conversationId, Map<String, Object> attributes) {
    return null;
  }

  /**
   * Tempdoc 859 slice C PR-2 — delete every persisted agent run belonging to {@code conversationId},
   * returning how many were removed. The write half of the two-store list join: a conversation the
   * list synthesizes from the run record has no {@code ConversationStore} session to delete, so
   * without this a delegate conversation would be listable and undeletable and the sidebar would only
   * ever grow. Irreversible (the runs' metas and event logs go with them) and fails CLOSED while the
   * run store is sealed + locked. Default 0 so unavailable services and test mocks compile unchanged.
   */
  default int deleteConversationRuns(String conversationId) {
    return 0;
  }

  // Tempdoc 584 §B.4: the READ-TIME query/projection surface (availableOperations, undoOperation,
  // operationHistory/Detail, lastSessionSnapshot, listSessions, sessionSnapshot, resume*,
  // sessionEvents, threadEvents, lifecycles, presenceSince) moved to the AgentRunQueries
  // super-interface this extends.

  /** Whether the agent capability is available (model loaded, tools registered). */
  boolean isAvailable();

  /**
   * Tempdoc 560 WS5 (the one window) — late-bind the streaming workflow-as-tool runner. Default
   * no-op so unavailable services and test mocks need not implement it; the live {@code
   * AgentLoopService} forwards it to its step runner so projected workflow tools stream through the
   * runner instead of the synchronous executor. Set by {@code LocalApiServer} once {@code
   * WorkflowShapeRunner} is constructed (it is wired later than the agent loop).
   */
  default void setWorkflowToolRunner(
      io.justsearch.agent.api.registry.WorkflowToolRunner runner) {
    // no-op by default
  }

  /**
   * Returns the number of currently-running agent sessions (tempdoc 415). Sources the
   * {@code agent.session.active_count} gauge and the {@code agentSessions.activeCount} field
   * on {@code /api/status}. Default implementation returns 0 — appropriate for the unavailable
   * service and for test mocks that don't track sessions.
   */
  default int activeSessionCount() {
    return 0;
  }

  /**
   * Null Object for environments where the agent capability is not configured (inference
   * disabled or unconfigured). Returns {@code false} from {@link #isAvailable()} and empty /
   * default values from the other interface defaults. Tempdoc 519 F2 (refined per §22): kept
   * as the Null Object pattern.
   */
  static AgentService unavailable() {
    return UnavailableAgentService.INSTANCE;
  }
}
