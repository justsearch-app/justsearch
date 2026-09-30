/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.api.lifecycle.RetentionClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 837 §D.1 — the generalized reason-retention rule, driven through both capabilities that
 * share it.
 *
 * <p>The rule keys on the HELD code's class, never on the incoming code's genericness. §1.4's first
 * draft keyed on the incoming code and was a wrong-gate: once the worker bootstrap started stamping
 * {@code worker.starting}, the literal rule retained a STARTING worker as the reported cause of a
 * spawn failure. {@link #transientNeverOutranksFault()} is that regression, and it is the
 * amendment's proof — it FAILS against §1.4's rule and passes against §D.1's.
 *
 * <p>The shipped corrupt-index latch is the STICKY case of this same rule and is pinned unchanged by
 * {@code WorkerCapabilityCorruptLatchTest} / {@code KnowledgeServerWorkerDownCodeTest} (§D.1 test 5,
 * "stickyLatchUnchanged"): if the generalization had broken the latch, one of those would have
 * needed editing. None did.
 */
final class ReasonRetentionTest {

  private static final String STARTING = LifecycleReasonCode.WORKER_STARTING.code();
  private static final String SPAWN_FAILED = LifecycleReasonCode.WORKER_SPAWN_FAILED.code();
  private static final String LOST = LifecycleReasonCode.WORKER_LOST.code();
  private static final String CORRUPT = LifecycleReasonCode.WORKER_INDEX_CORRUPT.code();
  private static final String RECOVERING_CODE = LifecycleReasonCode.WORKER_RECOVERING.code();
  private static final String RECOVERY_EXHAUSTED =
      LifecycleReasonCode.WORKER_SPAWN_RECOVERY_EXHAUSTED.code();
  private static final String MODEL_NOT_FOUND = LifecycleReasonCode.INFERENCE_MODEL_NOT_FOUND.code();
  private static final String OFFLINE = LifecycleReasonCode.INFERENCE_OFFLINE.code();
  private static final String CRASHED = LifecycleReasonCode.INFERENCE_CRASHED.code();
  private static final String DEACTIVATED = LifecycleReasonCode.INFERENCE_DEACTIVATED.code();

  @Test
  @DisplayName("D.1 #1: a specific FAULT outranks the generic fallback that follows it")
  void retainsSpecificFaultOverGenericOffline() {
    InferenceCapability cap = new InferenceCapability(true);
    // RuntimeActivationService.reportToCapability stamps the precise cause it detected.
    cap.transition(CapabilityHealth.OFFLINE, MODEL_NOT_FOUND, "no such file");

    // The failed activation attempt fires an OFFLINE mode change; deriveAndApply has no reason in
    // hand for a standing state, so it writes the generic code. Last-writer-wins would erase the one
    // cause the 656 slice worked to surface.
    cap.transition(CapabilityHealth.OFFLINE, OFFLINE);

    assertEquals(MODEL_NOT_FOUND, cap.pendingReason(), "the generic code cannot erase a fault");
    assertEquals("no such file", cap.pendingDetail(), "its detail is retained with it");
  }

  @Test
  @DisplayName("D.1 #1 (reverse ordering): the generic code lands first, the fault still overwrites it")
  void specificFaultLandsAfterAGenericHold() {
    InferenceCapability cap = new InferenceCapability(true);
    cap.transition(CapabilityHealth.OFFLINE, OFFLINE);
    cap.transition(CapabilityHealth.OFFLINE, MODEL_NOT_FOUND, "no such file");

    assertEquals(
        MODEL_NOT_FOUND, cap.pendingReason(), "retention never blocks better information landing");
  }

  @Test
  @DisplayName("D.1 #3 (THE C.3 REGRESSION): a TRANSIENT hold never outranks an arriving fault")
  void transientNeverOutranksFault() {
    WorkerCapability cap = new WorkerCapability();
    cap.transition(CapabilityHealth.PENDING, STARTING, "Worker starting");

    cap.transition(CapabilityHealth.DEGRADED, SPAWN_FAILED, "Start failed: lib dir not found");

    assertEquals(
        SPAWN_FAILED,
        cap.pendingReason(),
        "worker.starting is progress narration — stale the moment anything else happens. §1.4's "
            + "literal rule (retain whenever the held reason is 'a different known code') reported a "
            + "STARTING worker as the cause of a spawn failure.");
    assertEquals(CapabilityHealth.DEGRADED, cap.health());
  }

  @Test
  @DisplayName("D.1 #4: a newer FAULT overwrites an older one — better information, not noise")
  void newerFaultOverwritesOlderFault() {
    InferenceCapability cap = new InferenceCapability(true);
    cap.transition(CapabilityHealth.OFFLINE, MODEL_NOT_FOUND, "no such file");

    cap.transition(CapabilityHealth.OFFLINE, CRASHED, "health threshold tripped");

    assertEquals(
        CRASHED,
        cap.pendingReason(),
        "guards against over-correcting §D.1 into a sticky-everything rule");
    assertEquals("health threshold tripped", cap.pendingDetail());
  }

  @Test
  @DisplayName("D.1 #4b: STICKY evidence outranks a held FAULT (the case the latch depends on)")
  void stickyEvidenceOverwritesAHeldFault() {
    WorkerCapability cap = new WorkerCapability();
    cap.transition(CapabilityHealth.DEGRADED, LOST, "Health check failed");

    // The next down-transition reads the fatal-reason marker and learns WHY it was lost. If a held
    // FAULT outranked this, the corrupt cause would be rejected whenever a worker.lost tick happened
    // to land first — and the marker is already deleted, so it would be lost permanently.
    cap.transition(CapabilityHealth.DEGRADED, CORRUPT, "the remedy paragraph");

    assertEquals(CORRUPT, cap.pendingReason());
    assertEquals("the remedy paragraph", cap.pendingDetail());
  }

  @Test
  @DisplayName("D.1 #6: READY clears everything, including a STICKY hold")
  void readyClearsEverything() {
    WorkerCapability worker = new WorkerCapability();
    worker.transition(CapabilityHealth.DEGRADED, CORRUPT, "the remedy paragraph");
    worker.transition(CapabilityHealth.READY, null);
    assertNull(worker.pendingReason(), "recovery is the bound — no timer needed");
    assertNull(worker.pendingDetail());

    InferenceCapability inference = new InferenceCapability(true);
    inference.transition(CapabilityHealth.OFFLINE, CRASHED, "health threshold tripped");
    inference.transition(CapabilityHealth.READY, null);
    assertNull(inference.pendingReason());
    assertNull(inference.pendingDetail());

    // And no stale cause is resurrected by the next unrelated degradation.
    inference.transition(CapabilityHealth.OFFLINE, DEACTIVATED, null);
    assertEquals(DEACTIVATED, inference.pendingReason());
  }

  @Test
  @DisplayName("an intentional TRANSIENT state never outranks a held fault either")
  void deactivationDoesNotEraseACrash() {
    InferenceCapability cap = new InferenceCapability(true);
    cap.transition(CapabilityHealth.OFFLINE, CRASHED, "health threshold tripped");

    cap.transition(CapabilityHealth.OFFLINE, DEACTIVATED, null);

    assertEquals(CRASHED, cap.pendingReason(), "the crash is why it is down; the toggle is not");
  }

  @Test
  @DisplayName("prose in the reason slot has no precedence in either direction")
  void proseIsNeitherRetainedNorAbleToDisplaceAFault() {
    InferenceCapability cap = new InferenceCapability(true);
    cap.transition(CapabilityHealth.OFFLINE, "some legacy sentence");
    cap.transition(CapabilityHealth.OFFLINE, CRASHED, null);
    assertEquals(CRASHED, cap.pendingReason(), "prose is always overwritten");

    cap.transition(CapabilityHealth.OFFLINE, "another legacy sentence");
    assertEquals(
        CRASHED, cap.pendingReason(), "and prose can never displace a fault (it classifies TRANSIENT)");
  }

  @Test
  @DisplayName("a rejected reason with no health change fires no listener")
  void rejectedWriteDoesNotNotify() {
    InferenceCapability cap = new InferenceCapability(true);
    cap.transition(CapabilityHealth.OFFLINE, CRASHED, null);

    java.util.List<CapabilityHealth> observed = new java.util.ArrayList<>();
    cap.addListener((prev, next) -> observed.add(next));

    cap.transition(CapabilityHealth.OFFLINE, OFFLINE);
    assertEquals(
        java.util.List.of(),
        observed,
        "the 656 reason-only widening must not turn a rejected write into a manifest publish");

    cap.transition(CapabilityHealth.DEGRADED, OFFLINE);
    assertEquals(
        java.util.List.of(CapabilityHealth.DEGRADED), observed, "health changes always notify");
    assertEquals(CRASHED, cap.pendingReason(), "…while the reason is still retained");
  }

  @Test
  @DisplayName("825: boot recovery supersedes the spawn-failed pin — and nothing else")
  void recoverySupersedesOnlyTheSpawnFailedPin() {
    // The ONE exception (tempdoc 825 §D2 mechanism 4). worker.recovering is TRANSIENT and
    // worker.spawn.failed is a FAULT, so the general rule would drop this write — and pendingReason,
    // published raw on the runtime manifest and the 503 body, would keep saying "failed to start"
    // while the Head is actively re-attempting.
    WorkerCapability recovering = new WorkerCapability();
    recovering.transition(CapabilityHealth.DEGRADED, SPAWN_FAILED, "Start failed");
    recovering.transition(CapabilityHealth.RECOVERING, RECOVERING_CODE, "attempt 1 of 4");
    assertEquals(RECOVERING_CODE, recovering.pendingReason());
    assertEquals("attempt 1 of 4", recovering.pendingDetail(), "its detail rides with it");

    // Neither a fatal cause nor local budget exhaustion is superseded.
    WorkerCapability corrupt = new WorkerCapability();
    corrupt.transition(CapabilityHealth.DEGRADED, CORRUPT, "rebuild to recover");
    corrupt.transition(CapabilityHealth.RECOVERING, RECOVERING_CODE, "attempt 1 of 4");
    assertEquals(CORRUPT, corrupt.pendingReason(), "the unrepeatable cause still outranks everything");

    WorkerCapability recoveryExhausted = new WorkerCapability();
    recoveryExhausted.transition(CapabilityHealth.DEGRADED, RECOVERY_EXHAUSTED, "gave up");
    recoveryExhausted.transition(CapabilityHealth.RECOVERING, RECOVERING_CODE, "attempt 1 of 4");
    assertEquals(
        RECOVERY_EXHAUSTED,
        recoveryExhausted.pendingReason(),
        "once we have stopped trying, a stray narration cannot claim we are trying again");
  }

  @Test
  @DisplayName("825: the terminal recovery code lands over the in-flight narration it replaces")
  void terminalRecoveryCodeOverwritesTheRecoveringNarration() {
    WorkerCapability cap = new WorkerCapability();
    cap.transition(CapabilityHealth.DEGRADED, SPAWN_FAILED, "Start failed");
    cap.transition(CapabilityHealth.RECOVERING, RECOVERING_CODE, "attempt 4 of 4");

    cap.transition(CapabilityHealth.DEGRADED, RECOVERY_EXHAUSTED, "4 attempts did not bring it up");

    assertEquals(
        RECOVERY_EXHAUSTED,
        cap.pendingReason(),
        "the give-up must reach the wire; a TRANSIENT hold never blocks an arriving FAULT");
  }

  @Test
  @DisplayName("the classification is total and exhaustive over the vocabulary")
  void everyCodeIsClassified() {
    for (LifecycleReasonCode code : LifecycleReasonCode.values()) {
      assertEquals(
          code.retentionClass(),
          LifecycleReasonCode.retentionClassOf(code.code()),
          "the by-string lookup must agree with the enum's own class for " + code);
    }
    assertEquals(
        RetentionClass.STICKY,
        LifecycleReasonCode.WORKER_INDEX_CORRUPT.retentionClass(),
        "exactly one STICKY member: the marker is deleted as it is read");
    assertEquals(
        RetentionClass.GENERIC,
        LifecycleReasonCode.INFERENCE_OFFLINE.retentionClass(),
        "exactly one GENERIC member");
    assertEquals(
        RetentionClass.FAULT,
        LifecycleReasonCode.WORKER_SPAWN_FAILED.retentionClass(),
        "fallback-ness is a property of the CONSUMER — spawn-failed is only ever SET where it is true");
    assertEquals(
        RetentionClass.TRANSIENT,
        LifecycleReasonCode.retentionClassOf("a sentence, not a code"),
        "unclassifiable ⇒ never retained");
    assertEquals(RetentionClass.TRANSIENT, LifecycleReasonCode.retentionClassOf(null));
  }
}
