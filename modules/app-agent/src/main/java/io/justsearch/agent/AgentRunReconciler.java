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
   * Stamp every persisted run that is non-terminal and not already stamped. Returns how many runs
   * this pass stamped — {@code 0} when there was nothing to do AND when the store was unreadable
   * (a locked store is indistinguishable from an empty one here by design; the unlock listener
   * re-runs the pass rather than this method guessing).
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
      if (LifecycleState.parse(summary.get("state")).isTerminal()) {
        continue; // finished before the process died — no marker
      }
      if (summary.get("interruptedAt") instanceof String existing && !existing.isBlank()) {
        continue; // already stamped by an earlier pass
      }
      try {
        if (store.markInterrupted(sessionId, at)) {
          stamped++;
          reportUnappliedDirections(sessionId);
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

  /**
   * WP6.2 — a direction the dead process accepted but never reported applied or not applied did not
   * steer the run; say so in the run's record, once, as the live run's own terminal path would have.
   */
  private void reportUnappliedDirections(String sessionId) {
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
            AgentEvent.DirectiveAcknowledged.REASON_RUN_INTERRUPTED));
      }
    });
  }
}
