/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide registry of pending approval gates for in-flight {@link WorkflowShapeRunner} runs
 * (tempdoc 560 Phase 2). When a workflow reaches a {@code GateStep} — or a {@code ToolStep} whose
 * operation requires approval — the runner thread blocks on a {@link CompletableFuture} created
 * here; the HTTP approve/reject endpoint completes it out-of-band, unblocking the runner.
 *
 * <p>Mirrors the role {@code AgentSession}'s approval-gate map plays for the agent loop, factored
 * out as a standalone collaborator because a workflow run has no persistent session object. Keyed
 * by {@code callId} alone, which the runner mints as a globally-unique value per gate.
 */
public final class WorkflowGateRegistry {

  private record Gate(CompletableFuture<Boolean> future, io.justsearch.agent.api.PendingToolApproval approval) {}

  private final Map<String, Gate> gates = new ConcurrentHashMap<>();

  /** Registers a new gate for {@code callId} and returns the future the runner blocks on. */
  public CompletableFuture<Boolean> create(String callId) {
    return create(callId, null);
  }

  /** Keep display on this live gate only; public events receive approval.detail(), never its preview. */
  public CompletableFuture<Boolean> create(String callId, io.justsearch.agent.api.PendingToolApproval approval) {
    CompletableFuture<Boolean> future = new CompletableFuture<>();
    gates.put(callId, new Gate(future, approval));
    return future;
  }

  public java.util.Optional<io.justsearch.agent.api.PendingToolApproval> pendingToolApproval(String callId) {
    if (callId == null || callId.isBlank()) return java.util.Optional.empty();
    var gate = gates.get(callId);
    return gate == null ? java.util.Optional.empty() : java.util.Optional.ofNullable(gate.approval());
  }

  /**
   * Completes the gate for {@code callId} with the user's decision, unblocking the runner.
   *
   * @return {@code true} if a gate was waiting (decision delivered); {@code false} if unknown/stale
   */
  public boolean complete(String callId, boolean approved) {
    var gate = gates.remove(callId);
    return gate != null && gate.future().complete(approved);
  }

  /** Drops a gate without completing it (cleanup on abort); harmless if already completed. */
  public void discard(String callId) {
    gates.remove(callId);
  }
}
