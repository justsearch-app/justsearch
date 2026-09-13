/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.agent.api.RunObservation;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Tempdoc 584 §B.4 — the live-run session registry + per-run control surface, extracted from
 * {@code AgentLoopService} (the live-control cluster, ~16% of the pre-584 file). Owns the map of
 * in-flight {@code sessionId → AgentSession} and every operation that looks a session up and
 * mutates it: approve/reject a pending tool call, cancel, set the autonomy dial, inject a steering
 * directive, raise the token budget, resolve a held budget/context gate, attach an observer to a
 * live run, and complete a virtual (FE-executed) tool call.
 *
 * <p>This is the §B.2 *breadth-axis* cut for the control surface — the exact cluster that re-pinned
 * the file via 561 (autonomy), 565 (steer) and 577 (budget/context/attach). Each method is the
 * uniform "{@code get(sessionId)} then delegate to {@link AgentSession}" shape; relocating them here
 * means a new per-run control verb attaches to this collaborator (plus a thin delegating override on
 * the loop) instead of growing the orchestrator's body.
 *
 * <p>The loop owns the run lifecycle and uses {@link #register}/{@link #remove} at the run's start/end
 * chokepoints; everything else is an out-of-band control input the loop's session reads between steps.
 */
final class AgentSessionRegistry {

  private final ConcurrentHashMap<String, AgentSession> sessions = new ConcurrentHashMap<>();

  /** Register a starting run's session (called by the loop at run start). */
  void register(String sessionId, AgentSession session) {
    sessions.put(sessionId, session);
  }

  /** Evict a finished run's session (called by the loop at the run-end chokepoint). */
  void remove(String sessionId) {
    sessions.remove(sessionId);
  }

  /**
   * Tempdoc 415: feeds the {@code agent.session.active_count} gauge + {@code /api/status}.
   *
   * <p>{@link java.util.concurrent.ConcurrentHashMap#size()} is approximately consistent under
   * concurrent put/remove — it may briefly read N+1 during a session removal that races with
   * the gauge's async sample. For a desktop deployment with 0–1 concurrent sessions this is
   * irrelevant. If multi-conversation persistence (tempdoc 416) introduces meaningful
   * concurrency, switch to a dedicated {@code LongAdder} or {@code AtomicInteger} maintained
   * explicitly on session put/remove for stronger consistency.
   */
  int activeSessionCount() {
    return sessions.size();
  }

  void approveToolCall(String sessionId, String callId) {
    tryApproveToolCall(sessionId, callId);
  }

  void rejectToolCall(String sessionId, String callId, String reason) {
    tryRejectToolCall(sessionId, callId, reason);
  }

  boolean tryApproveToolCall(String sessionId, String callId) {
    // A null/blank sessionId is a legitimate "no agent session" input (e.g. a workflow-only approve):
    // skip the agent gate so the unified dispatch falls through to the workflow gate, never NPEs on
    // ConcurrentHashMap.get(null). Surfaced by a live null-sessionId probe (tempdoc 565 §15.J).
    if (sessionId == null || sessionId.isBlank()) {
      return false;
    }
    var session = sessions.get(sessionId);
    return session != null && session.approve(callId);
  }

  java.util.Optional<io.justsearch.agent.api.PendingToolApproval> pendingToolApproval(String sessionId, String callId) {
    if (sessionId == null || sessionId.isBlank()) return java.util.Optional.empty();
    var session = sessions.get(sessionId);
    return session == null ? java.util.Optional.empty() : session.pendingToolApproval(callId);
  }

  boolean tryRejectToolCall(String sessionId, String callId, String reason) {
    if (sessionId == null || sessionId.isBlank()) {
      return false;
    }
    var session = sessions.get(sessionId);
    return session != null && session.reject(callId);
  }

  void cancelSession(String sessionId) {
    var session = sessions.get(sessionId);
    if (session != null) {
      session.cancel();
    }
  }

  /**
   * Tempdoc 561 P-D — update the live autonomy dial for a running session (the FE POSTs this when the
   * user moves the Watch/Assist/Auto dial mid-run). Takes effect on the NEXT gated tool call. No-op
   * for an unknown/finished session.
   */
  void setSessionAutonomy(String sessionId, String autonomyLevelWire) {
    var session = sessions.get(sessionId);
    if (session != null) {
      session.setAutonomyLevel(
          io.justsearch.agent.api.registry.AutonomyLevel.fromWire(autonomyLevelWire));
    }
  }

  /**
   * Tempdoc 565 §30 — the DIRECTION authority's {@code interject}: queue a free-form human steering
   * directive for a running session (the FE POSTs this when the user types into the live-run steer
   * input). The loop drains it at the next step boundary and folds it into the next LLM call. Returns
   * false (404) for an unknown/finished session. Mirrors {@link #setSessionAutonomy} — both write a
   * per-run control input the loop reads between steps.
   */
  boolean injectSteeringDirective(String sessionId, String text) {
    var session = sessions.get(sessionId);
    if (session == null) {
      return false;
    }
    session.setInterject(text);
    return true;
  }

  /**
   * Tempdoc 577 Ext III — the over-budget remedy: grant a running session additional tokens (POST
   * /api/chat/agent/budget). Mirrors {@link #injectSteeringDirective} — a per-run control input the
   * loop reads between steps. Returns false (404) for an unknown/finished session.
   */
  boolean raiseSessionBudget(String sessionId, int addTokens) {
    var session = sessions.get(sessionId);
    if (session == null || addTokens <= 0) {
      return false;
    }
    session.addBudget(addTokens);
    // Tempdoc 577 Move 2 — raising the budget IS the continue decision: a run parked at the
    // budget gate resumes the moment the grant lands. No-op when no gate is held (mid-run raise).
    session.resolveBudgetGate(AgentSession.BudgetGateDecision.CONTINUE);
    return true;
  }

  /**
   * Tempdoc 577 §2.12 Move 2 — resolve a held budget gate with finalize/stop (the raise endpoint
   * carries CONTINUE). Returns false for an unknown session, an unparked run, or a bad decision.
   */
  boolean resolveBudgetGate(String sessionId, String decision) {
    var session = sessions.get(sessionId);
    if (session == null || decision == null) {
      return false;
    }
    AgentSession.BudgetGateDecision parsed =
        switch (decision.toLowerCase(java.util.Locale.ROOT)) {
          case "finalize" -> AgentSession.BudgetGateDecision.FINALIZE;
          case "stop" -> AgentSession.BudgetGateDecision.STOP;
          default -> null;
        };
    if (parsed == null) {
      return false;
    }
    return session.resolveBudgetGate(parsed);
  }

  /**
   * Tempdoc 577 §2.14 Root II (#14) — resolve a held context-pressure gate with
   * continue/summarize/stop. Returns false for an unknown session, an unparked run, or a bad decision.
   */
  boolean resolveContextGate(String sessionId, String decision) {
    var session = sessions.get(sessionId);
    if (session == null || decision == null) {
      return false;
    }
    AgentSession.ContextGateDecision parsed =
        switch (decision.toLowerCase(java.util.Locale.ROOT)) {
          case "continue" -> AgentSession.ContextGateDecision.CONTINUE;
          case "summarize" -> AgentSession.ContextGateDecision.SUMMARIZE;
          case "stop" -> AgentSession.ContextGateDecision.STOP;
          default -> null;
        };
    if (parsed == null) {
      return false;
    }
    return session.resolveContextGate(parsed);
  }

  /**
   * Tempdoc 577 §2.14 Root I (#13) / 834 §1.3 — attach an observer to a live run's OBSERVATION
   * CHANNEL: the primer, then the replay from {@code sinceSeq}, then ongoing frames, blocking until
   * the run terminates. Returns false when the session is not live (the controller falls back to
   * the persisted record).
   *
   * <p>Frames arrive already wire-projected ({@link RunObservation.WireFrame}), because the journal
   * carries the projection of the ONE payload authority rather than raw events (§1.3.2). That is
   * what lets a native attach write them straight to the socket and an AG-UI attach re-project them
   * through the map-input translator, with no second event vocabulary in between.
   *
   * <p>{@code sinceSeq} is the CHANNEL sequence, delivered as {@code ?sinceSeq=} on the run-stream
   * family. It is deliberately not the {@code Last-Event-ID} trace span the pre-834 attach used:
   * §1.6 closes that second, unvalidated resume channel by not emitting {@code id:} at all. Zero
   * replays everything and is the guaranteed path.
   */
  boolean attachToRun(String sessionId, long sinceSeq, Consumer<RunObservation.WireFrame> observer) {
    var session = sessions.get(sessionId);
    if (session == null) {
      return false; // not a live run — the caller replays events.ndjson instead
    }
    var done = new java.util.concurrent.CountDownLatch(1);
    // The run being retired IS the end of the stream, and the loop retires it immediately after
    // publishing the terminal event — so latching on retirement needs no terminal-event vocabulary
    // here, and cannot miss a run that ends without one.
    session.observation().onRetire(done::countDown);
    java.util.Optional<Runnable> unsubscribe =
        session.observation().observe(sinceSeq, observer);
    if (unsubscribe.isEmpty()) {
      // The cursor fell outside the retained window and NOTHING was registered. Falling back to a
      // full replay is the guaranteed path; silently returning an empty stream is the failure mode.
      unsubscribe = session.observation().observe(0L, observer);
      if (unsubscribe.isEmpty()) {
        return false;
      }
    }
    try {
      // Block the attach (SSE) handler thread while streaming. The timeout is a safety net against a
      // run that never terminates (it then falls back like a closed stream).
      done.await(AgentTimeouts.sessionAttachMs(), java.util.concurrent.TimeUnit.MILLISECONDS);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    } finally {
      unsubscribe.get().run();
    }
    return true;
  }

  /**
   * Tempdoc 508 §11.5 / §13.5 Phase B — deliver the FE's virtual
   * tool-call result back to the blocking agent loop. Returns false
   * (404) when no pending call exists for the given (session, callId)
   * tuple — the agent is no longer waiting (timed out / cancelled /
   * stale callback).
   */
  boolean completeVirtualToolCall(
      String sessionId, String callId, boolean success, String output, String errorDetail) {
    var session = sessions.get(sessionId);
    if (session == null) return false;
    AgentSession.VirtualToolResult result =
        success
            ? AgentSession.VirtualToolResult.success(output == null ? "" : output)
            : AgentSession.VirtualToolResult.failure(
                errorDetail == null ? "virtual tool failed (no detail)" : errorDetail);
    return session.completeVirtualTool(callId, result);
  }
}
