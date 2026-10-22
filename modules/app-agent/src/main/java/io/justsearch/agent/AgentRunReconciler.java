/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.lifecycle.LifecycleState;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 834 §5.2 — stamps runs the previous process left mid-flight.
 *
 * <p>The gap: {@code AgentRunStore.startRun} writes {@code resumable: true} and
 * {@code updateCheckpoint} recomputes it from state, but NOTHING notices that the owning process is
 * gone. {@link LifecycleState}'s javadoc claims "no orphan-RUNNING hazard" — true within a process,
 * false across a restart. So a run killed by an app close still reads as live, forever.
 *
 * <p>This pass answers only the question "was this run still going when the process died?" and
 * records the answer in ONE additive field. It does not touch {@code state} (the resume seed) or
 * {@code resumable} — see {@link AgentRunStore#markInterrupted}.
 *
 * <p><b>Idempotent and re-runnable</b>, which is what lets it run at BOTH boot and key-unlock. With
 * at-rest encryption on and the store locked, {@code readMeta} returns null and {@code listSessions}
 * yields nothing, so a boot-only pass would be a silent no-op on exactly the encrypted installs —
 * the reason the unlock seam exists (834 R5).
 */
public final class AgentRunReconciler {

  private static final Logger LOG = LoggerFactory.getLogger(AgentRunReconciler.class);

  /** Bounds one pass; far above any plausible run count, and {@code listSessions} clamps below. */
  private static final int SCAN_LIMIT = 100_000;

  private final AgentRunStore store;

  public AgentRunReconciler(AgentRunStore store) {
    this.store = java.util.Objects.requireNonNull(store, "store");
  }

  /**
   * Stamp every persisted run that is non-terminal and not already stamped, and report every
   * direction a run left queued as not applied (WP6.2). A run a loop in this process still runs is
   * left alone ({@link AgentRunStore#isLive}). Returns how many runs this pass stamped —
   * {@code 0} when there was nothing to do AND when the store was unreadable (a locked store is
   * indistinguishable from an empty one here by design; the unlock listener re-runs the pass rather
   * than this method guessing).
   */
  public int reconcile() {
    return reconcile(Instant.now());
  }

  /** {@link #reconcile()} with an explicit stamp instant (tests pin it). */
  public int reconcile(Instant at) {
    List<Map<String, Object>> sessions;
    try {
      sessions = store.listSessions(SCAN_LIMIT);
    } catch (RuntimeException e) {
      // Must never propagate: one caller is a DataKeyManager listener, whose `fire` SWALLOWS
      // throws — a throw there would be invisible rather than loud.
      LOG.warn("Interrupted-run reconciliation could not list sessions", e);
      return 0;
    }
    int stamped = 0;
    for (Map<String, Object> summary : sessions) {
      if (!(summary.get("sessionId") instanceof String sessionId) || sessionId.isBlank()) {
        continue;
      }
      try {
        // WP6.2 — a run a loop in this process still owns was not interrupted: its loop reports its
        // directions itself. Ownership ends after the run's last write and a session id never runs
        // again, so a run not owned now is judged on a fresh read, not on the listing, which can
        // predate its finish.
        if (store.isLive(sessionId)) {
          continue;
        }
        Map<String, Object> run;
        io.justsearch.agent.api.conversation.ConversationWork.Lease writing;
        // Read ownership and acquire exclusion against the same monitor as run deletion. A stale
        // listing must never let recovery recreate a run that deletion has already removed.
        synchronized (store) {
          run = store.readSnapshot(sessionId);
          if (run == null) continue;
          writing = store.conversationWork().start(
              run.get("conversationId") instanceof String id && !id.isBlank() ? id : null);
        }
        try (writing) {
          LifecycleState state = LifecycleState.parse(run.get("state"));
          if (state.isTerminal()) {
            // Finished before the process died — no marker. The run's last write, its termination
            // reason, follows the outcomes of its directions; without it, finishing was cut short.
            if (run.get("terminationReason") == null) {
              reportUnappliedDirections(sessionId, notAppliedReason(state));
            }
            continue;
          }
          // Report before stamping, and also for a run an earlier pass stamped: the process can die
          // between the two writes, and the stamp alone does not say the directions were reported.
          reportUnappliedDirections(
              sessionId, AgentEvent.DirectiveAcknowledged.REASON_RUN_INTERRUPTED);
          if (store.markInterrupted(sessionId, at)) {
            stamped++;
          }
        }
      } catch (RuntimeException e) {
        LOG.warn("Could not mark run {} interrupted", sessionId, e);
      }
    }
    if (stamped > 0) {
      LOG.info("Marked {} agent run(s) interrupted (the owning process did not finish them)", stamped);
    }
    return stamped;
  }

  /** Why a direction a run left queued did not apply, for a run that reached {@code state}. */
  private static String notAppliedReason(LifecycleState state) {
    return switch (state) {
      case DONE -> AgentEvent.DirectiveAcknowledged.REASON_RUN_FINISHED;
      case CANCELLED -> AgentEvent.DirectiveAcknowledged.REASON_RUN_STOPPED;
      default -> AgentEvent.DirectiveAcknowledged.REASON_RUN_FAILED;
    };
  }

  /**
   * WP6.2 — a direction the dead process accepted but never reported applied or not applied did not
   * steer the run; say so in the run's record, once, as the live run's own terminal path would have.
   * A direction that already has a final outcome is left alone, so a repeated pass adds nothing.
   */
  private void reportUnappliedDirections(String sessionId, String reason) {
    var outstanding = new java.util.LinkedHashMap<String, String>();
    for (Map<String, Object> record : store.readEvents(sessionId)) {
      if (!"directive_acknowledged".equals(record.get("eventType"))
          || !(record.get("payload") instanceof Map<?, ?> payload)
          || !(payload.get("directiveId") instanceof String id)) {
        continue;
      }
      if (AgentEvent.DirectiveAcknowledged.QUEUED.equals(payload.get("outcome"))) {
        if (!outstanding.containsKey(id)) {
          outstanding.put(id, java.util.Objects.toString(payload.get("directiveText"), ""));
        }
      } else {
        outstanding.put(id, null);
      }
    }
    outstanding.forEach((id, text) -> {
      if (text != null) {
        store.appendEvent(sessionId, new AgentEvent.DirectiveAcknowledged(text, id,
            AgentEvent.DirectiveAcknowledged.NOT_APPLIED,
            reason));
      }
    });
  }
}
