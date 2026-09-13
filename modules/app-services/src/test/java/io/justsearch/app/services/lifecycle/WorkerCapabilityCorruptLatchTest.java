/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 837 §1.4/§3.1 — the corrupt-index latch.
 *
 * <p>{@code WorkerFatalReasonMarker.readAndClear} DELETES the marker file as it reads it, so "the
 * worker died because the index is corrupt" is observable EXACTLY ONCE per crash. Whichever caller
 * wins the race gets it; any later overwrite destroys it permanently, because a restart cannot
 * re-derive what is no longer on disk. Without the latch the highest-value code in the tempdoc is a
 * race — and the happy-path test (set corrupt, read corrupt) would still pass.
 *
 * <p>Both orderings are asserted, per the design: corrupt-then-generic and generic-then-corrupt.
 */
final class WorkerCapabilityCorruptLatchTest {

  private static final String CORRUPT = LifecycleReasonCode.WORKER_INDEX_CORRUPT.code();
  private static final String LOST = LifecycleReasonCode.WORKER_LOST.code();
  private static final String SPAWN_FAILED = LifecycleReasonCode.WORKER_SPAWN_FAILED.code();
  private static final String REMEDY = "Set index.recovery.policy=BACKUP_REBUILD to rebuild it.";

  @Test
  @DisplayName("ordering 1: corrupt observed first — a later generic transition cannot destroy it")
  void corruptSurvivesLaterGenericTransition() {
    WorkerCapability cap = new WorkerCapability();
    cap.transition(CapabilityHealth.DEGRADED, CORRUPT, REMEDY);

    // The supervisor restarts, fails again, and gives up. Each of these would overwrite the reason
    // slot under last-writer-wins — and the marker is already gone, so the cause would be lost.
    cap.transition(CapabilityHealth.RECOVERING, LifecycleReasonCode.WORKER_RECOVERING.code(), "a1");
    assertEquals(CORRUPT, cap.pendingReason(), "the latched cause survives the restart narration");
    assertEquals(
        CapabilityHealth.RECOVERING, cap.health(), "the new HEALTH is always applied; only the reason is retained");

    cap.transition(CapabilityHealth.DEGRADED, SPAWN_FAILED, "Start failed: index open failed");
    assertEquals(CORRUPT, cap.pendingReason(), "a generic worker-down code cannot overwrite it");
    assertEquals(REMEDY, cap.pendingDetail(), "the remedy sentence is retained with the code");

    cap.transition(
        CapabilityHealth.DEGRADED, LifecycleReasonCode.WORKER_SPAWN_RECOVERY_EXHAUSTED.code(), "gave up");
    assertEquals(
        CORRUPT,
        cap.pendingReason(),
        "restart-exhausted is the SYMPTOM of the corruption, not a competing cause");
  }

  @Test
  @DisplayName("ordering 2: a generic cause is held first — corrupt still lands when it arrives")
  void corruptOverwritesAnEarlierGenericCause() {
    WorkerCapability cap = new WorkerCapability();
    cap.transition(CapabilityHealth.DEGRADED, LOST, "Health check failed");
    assertEquals(LOST, cap.pendingReason());

    // The next health tick reads the marker and learns WHY it was lost.
    cap.transition(CapabilityHealth.DEGRADED, CORRUPT, REMEDY);
    assertEquals(CORRUPT, cap.pendingReason(), "the latch does not block the corrupt cause landing");
    assertEquals(REMEDY, cap.pendingDetail());
  }

  @Test
  @DisplayName("READY clears the latch — the bound is recovery, not a timer")
  void readyClearsTheLatch() {
    WorkerCapability cap = new WorkerCapability();
    cap.transition(CapabilityHealth.DEGRADED, CORRUPT, REMEDY);

    cap.transition(CapabilityHealth.READY, null);
    assertNull(cap.pendingReason(), "READY clears the reason outright");
    assertNull(cap.pendingDetail(), "and the detail with it");

    // A subsequent unrelated failure must NOT resurrect the stale corruption cause.
    cap.transition(CapabilityHealth.DEGRADED, LOST, "Health check failed");
    assertEquals(LOST, cap.pendingReason(), "no stale cause survives a worker that came back");
  }

  @Test
  @DisplayName("a rejected reason write with no health change fires no listener")
  void rejectedReasonDoesNotFireListeners() {
    WorkerCapability cap = new WorkerCapability();
    cap.transition(CapabilityHealth.DEGRADED, CORRUPT, REMEDY);

    List<CapabilityHealth> observed = new ArrayList<>();
    cap.addListener((prev, next) -> observed.add(next));

    // Same health, rejected reason: nothing effectively changed, so the tempdoc 656 reason-only
    // widening must not turn this into a spurious runtime-manifest publish.
    cap.transition(CapabilityHealth.DEGRADED, SPAWN_FAILED, "Start failed");
    assertEquals(List.of(), observed, "a write that changed nothing must not notify");

    // A health change still notifies, even while the reason is retained.
    cap.transition(CapabilityHealth.OFFLINE, LifecycleReasonCode.WORKER_SHUT_DOWN.code(), null);
    assertEquals(List.of(CapabilityHealth.OFFLINE), observed, "health changes always notify");
    assertEquals(CORRUPT, cap.pendingReason());
  }

  @Test
  @DisplayName("prose in the reason slot has no precedence — it is always overwritten")
  void proseNeverLatches() {
    WorkerCapability cap = new WorkerCapability();
    cap.transition(CapabilityHealth.DEGRADED, "Health check failed after 4200ms");
    cap.transition(CapabilityHealth.DEGRADED, SPAWN_FAILED, "Start failed");
    assertEquals(SPAWN_FAILED, cap.pendingReason(), "only the corrupt CODE latches, never a sentence");
  }
}
