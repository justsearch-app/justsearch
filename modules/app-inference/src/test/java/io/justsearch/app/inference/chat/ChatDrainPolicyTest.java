/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.chat.GenerationLease;
import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.inference.chat.ChatDrainPolicy.Expiry;
import io.justsearch.app.inference.chat.ChatDrainPolicy.Waiting;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** U5: the Spec A §3.4 drain row per stop reason, and what each row does to an open lease. */
final class ChatDrainPolicyTest {

  private static final Duration BOUND = Duration.ofSeconds(30);

  @Test
  void everyStopReasonHasItsSpecRow() {
    Set<StopReason> now = EnumSet.of(StopReason.QUIT, StopReason.UPGRADE,
        StopReason.ENGINE_RESTART, StopReason.DEVICE_LOST, StopReason.TIMEOUT, StopReason.ROLLBACK,
        StopReason.CRASH_RECOVERY, StopReason.CANCELLED);
    Set<StopReason> refuse = EnumSet.of(StopReason.RECONFIGURE, StopReason.MODEL_SWITCH,
        StopReason.VDU_ENTER, StopReason.SELF_TEST, StopReason.RECYCLE);
    Set<StopReason> ifIdle = EnumSet.of(StopReason.RESIDENCY_PRESSURE, StopReason.IDLE_UNLOAD);
    for (StopReason reason : StopReason.values()) {
      ChatDrainPolicy policy = ChatDrainPolicy.forReason(reason, BOUND);
      assertEquals(reason, policy.reason());
      assertFalse(policy.onlyWhileServing(), reason + ": rows wait regardless of mode by default");
      if (now.contains(reason)) {
        assertRow(policy, Waiting.NOW, Duration.ZERO, Expiry.PROCEED_NOW);
      } else if (refuse.contains(reason)) {
        assertRow(policy, Waiting.AFTER_IDLE, BOUND, Expiry.REFUSE_BUSY);
      } else if (ifIdle.contains(reason)) {
        assertRow(policy, Waiting.IF_IDLE, Duration.ZERO, Expiry.DEFER);
      } else if (reason == StopReason.DEACTIVATE) {
        assertRow(policy, Waiting.AFTER_IDLE, BOUND, Expiry.PROCEED_NOW);
      } else if (reason == StopReason.VDU_EXIT) {
        assertRow(policy, Waiting.AFTER_IDLE, null, Expiry.DEFER);
      } else {
        throw new AssertionError("no Spec A §3.4 row asserted for " + reason);
      }
    }
  }

  @Test
  void nowStopsAtOnceWithAReplyOpen() throws Exception {
    var tracker = tracker();
    GenerationLease reply = tracker.admit(GenerationTracker.Kind.GENERATE, null);
    try (reply) {
      var hold = tracker.drain(ChatDrainPolicy.forReason(StopReason.QUIT, BOUND));
      assertFalse(tracker.admissionOpen(), "QUIT closes admission");
      assertTrue(tracker.generating(), "and stops without waiting for the reply");
      hold.close();
    }
  }

  @Test
  void refuseBusyReopensAdmissionWhenTheBoundRunsOut() {
    var tracker = tracker();
    GenerationLease reply = tracker.admit(GenerationTracker.Kind.GENERATE, null);
    try (reply) {
      var refused = assertThrows(IllegalStateException.class, () -> tracker.drain(
          ChatDrainPolicy.forReason(StopReason.MODEL_SWITCH, Duration.ofMillis(50))));
      assertTrue(refused.getMessage().contains("did not drain"), refused.getMessage());
      assertTrue(tracker.admissionOpen(), "a refused change leaves chat serving");
      assertTrue(tracker.generating(), "the reply keeps running");
    }
  }

  @Test
  void deactivateProceedsWhenTheBoundRunsOutWithAdmissionStillClosed() throws Exception {
    var tracker = tracker();
    GenerationLease reply = tracker.admit(GenerationTracker.Kind.GENERATE, null);
    try (reply) {
      var hold = tracker.drain(
          ChatDrainPolicy.forReason(StopReason.DEACTIVATE, Duration.ofMillis(50)));
      assertFalse(tracker.admissionOpen(), "DEACTIVATE proceeds with admission closed");
      assertTrue(tracker.generating(), "after waiting its bound for the reply");
      hold.close();
    }
  }

  @Test
  void residencyPressureNeverStopsWithALeaseOpen() throws Exception {
    for (StopReason reason : EnumSet.of(StopReason.RESIDENCY_PRESSURE, StopReason.IDLE_UNLOAD)) {
      for (GenerationTracker.Kind kind : GenerationTracker.Kind.values()) {
        var tracker = tracker();
        GenerationLease open = tracker.admit(kind, null);
        try (open) {
          assertThrows(IllegalStateException.class,
              () -> tracker.drain(ChatDrainPolicy.forReason(reason, BOUND)),
              reason + " defers with a " + kind + " lease open");
          assertTrue(tracker.admissionOpen(), reason + ": nothing changes when it defers");
        }
        var hold = tracker.drain(ChatDrainPolicy.forReason(reason, BOUND));
        assertFalse(tracker.admissionOpen(), reason + " closes admission once idle");
        assertThrows(IllegalStateException.class,
            () -> tracker.admit(GenerationTracker.Kind.GENERATE, null));
        hold.close();
      }
    }
  }

  @Test
  void vduExitWaitsWithoutABoundForTheReply() throws Exception {
    var tracker = tracker();
    GenerationLease reply = tracker.admit(GenerationTracker.Kind.GENERATE, null);
    var drained = CompletableFuture.supplyAsync(() -> {
      try {
        return tracker.drain(ChatDrainPolicy.forReason(StopReason.VDU_EXIT, BOUND));
      } catch (InterruptedException interrupted) {
        throw new AssertionError(interrupted);
      }
    });
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (tracker.admissionOpen() && System.nanoTime() < deadline) Thread.onSpinWait();
    assertFalse(tracker.admissionOpen());
    Thread.sleep(200);
    assertFalse(drained.isDone(), "VDU_EXIT keeps waiting for the reply");
    reply.close();
    drained.get(5, TimeUnit.SECONDS).close();
    assertTrue(tracker.admissionOpen());
  }

  @Test
  void whileServingKeepsTheRowAndOnlyMarksIt() {
    ChatDrainPolicy row = ChatDrainPolicy.forReason(StopReason.DEACTIVATE, BOUND);
    ChatDrainPolicy serving = row.whileServing();
    assertTrue(serving.onlyWhileServing());
    assertRow(serving, row.waiting(), row.bound(), row.onExpiry());
  }

  private static GenerationTracker tracker() {
    return new GenerationTracker(ignored -> () -> {}, Runnable::run);
  }

  private static void assertRow(
      ChatDrainPolicy policy, Waiting waiting, Duration bound, Expiry onExpiry) {
    assertEquals(waiting, policy.waiting(), policy.reason() + " waiting");
    if (bound == null) {
      assertNull(policy.bound(), policy.reason() + " is unbounded");
    } else {
      assertEquals(bound, policy.bound(), policy.reason() + " bound");
    }
    assertEquals(onExpiry, policy.onExpiry(), policy.reason() + " on expiry");
  }
}
