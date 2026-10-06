/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.api.chat.GenerationLease;
import io.justsearch.app.api.runtime.child.StopReason;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Spec A §5.2 generation tracker: U6 (closeIfIdle race), U7 (edges), and the admission gate. */
final class GenerationTrackerTest {

  private static final Duration BOUND = Duration.ofSeconds(5);

  // ==================== U7: edges, epoch, idleSince; AUX is not generating ====================

  @Test
  void generateLeasesDriveOneEdgePairPerBusyPeriodAndAuxNeverGenerates() {
    var clock = new AtomicLong(100);
    var edges = new ArrayList<String>();
    var tracker = new GenerationTracker(ignored -> () -> {}, Runnable::run, clock::get);
    tracker.onEdge((generating, epoch) -> edges.add(generating + "@" + epoch));

    assertFalse(tracker.generating());
    assertEquals(0, tracker.epoch());
    assertEquals(100, tracker.idleSinceNanos());

    GenerationLease aux = tracker.admit(GenerationTracker.Kind.AUX, null);
    assertFalse(tracker.generating(), "an AUX lease is not generating (F1)");
    assertEquals(0, tracker.inFlight(), "inFlight counts GENERATE leases only");
    assertEquals(List.of(), edges, "an AUX lease opens no edge");

    GenerationLease first = tracker.admit(GenerationTracker.Kind.GENERATE, null);
    GenerationLease second = tracker.admit(GenerationTracker.Kind.GENERATE, null);
    assertTrue(tracker.generating());
    assertEquals(2, tracker.inFlight());
    assertEquals(1, tracker.epoch(), "one idle-to-generating edge for overlapping leases");
    assertEquals(Long.MIN_VALUE, tracker.idleSinceNanos(), "no idle time while generating");

    clock.set(250);
    first.close();
    first.close();
    assertTrue(tracker.generating(), "a duplicate close does not end the busy period");
    second.close();
    assertFalse(tracker.generating());
    assertEquals(250, tracker.idleSinceNanos(), "idleSince is the generating-to-idle edge");
    aux.close();
    assertEquals(250, tracker.idleSinceNanos(), "an AUX close is no edge");

    try (GenerationLease again = tracker.admit(GenerationTracker.Kind.GENERATE, null)) {
      assertEquals(2, tracker.epoch(), "each busy period has its own epoch");
    }
    assertEquals(List.of("true@1", "false@1", "true@2", "false@2"), edges,
        "edges arrive in order, each closing edge carrying its busy period's epoch");
  }

  @Test
  void edgesRunOnTheDeliveryExecutorNotTheLeaseHoldersThread() throws Exception {
    var deliveries = new ArrayList<Runnable>();
    var seen = new ArrayList<String>();
    var tracker = new GenerationTracker(ignored -> () -> {}, deliveries::add);
    tracker.onEdge((generating, epoch) -> seen.add(generating + "@" + epoch));

    tracker.admit(GenerationTracker.Kind.GENERATE, null).close();
    assertEquals(List.of(), seen, "no listener runs on the caller's thread");
    assertEquals(1, deliveries.size(), "one pump drains the queued edges");
    deliveries.get(0).run();
    assertEquals(List.of("true@1", "false@1"), seen);
  }

  @Test
  void queuedTicketsCountSeparatelyAndADrainDoesNotWaitForThem() throws Exception {
    var tracker = new GenerationTracker(ignored -> () -> {}, Runnable::run);
    GenerationTracker.Ticket queued = tracker.queue(GenerationTracker.Kind.GENERATE, null);
    assertEquals(1, tracker.queued());
    assertEquals(0, tracker.inFlight());
    assertFalse(tracker.generating(), "a queued demand is not generating");

    var hold = tracker.drain(
        ChatDrainPolicy.forReason(StopReason.RECONFIGURE, Duration.ofMillis(50)));
    try {
      assertThrows(IllegalStateException.class, queued::admit,
          "a queued caller's admission fails fast while admission is closed");
      assertEquals(0, tracker.queued(), "a refused ticket stops counting as queued");
    } finally {
      hold.close();
      queued.close();
    }
    assertTrue(tracker.admissionOpen());
  }

  @Test
  void aGenerateTicketWithWorkEntersPacingOnQueueAndReleasesItOnce() {
    var entered = new AtomicInteger();
    var released = new AtomicInteger();
    var tracker = new GenerationTracker(work -> {
      entered.incrementAndGet();
      return released::incrementAndGet;
    }, Runnable::run);
    EngineWorkHandle work = work();

    GenerationTracker.Ticket ticket = tracker.queue(GenerationTracker.Kind.GENERATE, work);
    assertEquals(1, entered.get(), "pacing begins when the demand queues");
    GenerationLease lease = ticket.admit();
    lease.close();
    assertEquals(0, released.get(), "pacing ends with the exchange, not the lease");
    ticket.close();
    ticket.close();
    assertEquals(1, released.get());

    tracker.admit(GenerationTracker.Kind.AUX, work).close();
    assertEquals(1, entered.get(), "an AUX exchange does not pace indexing");
  }

  // ==================== U6: closeIfIdle never takes a lease's server ====================

  @Test
  void closeIfIdleAndAdmissionRaceWithoutEverClosingOverAnOpenLease() throws Exception {
    for (int i = 0; i < 1_000; i++) {
      var tracker = new GenerationTracker(ignored -> () -> {}, Runnable::run);
      var barrier = new CyclicBarrier(2);
      var lease = new AtomicReference<GenerationLease>();
      var admitter = CompletableFuture.runAsync(() -> {
        await(barrier);
        try {
          lease.set(tracker.admit(GenerationTracker.Kind.GENERATE, null));
        } catch (IllegalStateException refused) {
          // The unload closed admission first.
        }
      });
      await(barrier);
      ChatActuator.DrainHold hold = tracker.closeIfIdle();
      admitter.get(5, TimeUnit.SECONDS);

      if (hold != null) {
        assertNull(lease.get(), "iteration " + i + ": an unload hold never coexists with a lease");
        assertEquals(0, tracker.inFlight());
        hold.close();
      } else {
        assertNotNull(lease.get(), "iteration " + i + ": the unload only defers for a lease");
        assertTrue(tracker.generating());
        lease.get().close();
      }
      assertTrue(tracker.admissionOpen(), "iteration " + i + ": admission reopens");
    }
  }

  // ==================== the admission gate (ported from GenerativeRequestGateTest) ==========

  @Test
  void replacementRefusesNewUsersAndWaitsForIssuedLease() throws Exception {
    var tracker = new GenerationTracker(ignored -> () -> {}, Runnable::run);
    var issued = tracker.admit(GenerationTracker.Kind.GENERATE, null);
    var started = new CountDownLatch(1);
    var replacement = CompletableFuture.runAsync(() -> {
      started.countDown();
      try {
        var hold = tracker.drain(ChatDrainPolicy.forReason(StopReason.RECONFIGURE, BOUND));
        try {
          assertThrows(IllegalStateException.class,
              () -> tracker.admit(GenerationTracker.Kind.GENERATE, null));
        } finally {
          hold.close();
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(interrupted);
      }
    });
    assertTrue(started.await(5, TimeUnit.SECONDS));
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (tracker.admissionOpen() && System.nanoTime() < deadline) Thread.onSpinWait();
    assertFalse(tracker.admissionOpen());
    assertFalse(replacement.isDone(), "an issued lease holds the replacement");
    issued.close();
    replacement.get(5, TimeUnit.SECONDS);
    tracker.admit(GenerationTracker.Kind.GENERATE, null).close();
    tracker.requireOpen();
  }

  @Test
  void drainTimeoutReopensAdmissionWithoutDestroyingHolder() {
    var tracker = new GenerationTracker(ignored -> () -> {}, Runnable::run);
    try (var issued = tracker.admit(GenerationTracker.Kind.GENERATE, null)) {
      assertThrows(IllegalStateException.class, () -> tracker.drain(
          ChatDrainPolicy.forReason(StopReason.RECONFIGURE, Duration.ZERO)));
      assertTrue(tracker.generating(), "the reply keeps running");
      tracker.admit(GenerationTracker.Kind.GENERATE, null).close();
      tracker.requireOpen();
    }
  }

  @Test
  void recoveryFenceRemainsClosedAfterAnExistingCandidateHoldCloses() throws Exception {
    var tracker = new GenerationTracker(ignored -> () -> {}, Runnable::run);
    var hold = tracker.drain(ChatDrainPolicy.forReason(StopReason.RECONFIGURE, BOUND));
    tracker.fenceForRecovery();
    hold.close();
    assertThrows(IllegalStateException.class,
        () -> tracker.admit(GenerationTracker.Kind.GENERATE, null));
    assertThrows(IllegalStateException.class, tracker::requireOpen);
  }

  @Test
  void nestedHoldsKeepAdmissionClosedUntilTheLastCloses() {
    var tracker = new GenerationTracker(ignored -> () -> {}, Runnable::run);
    var outer = tracker.closeNow();
    var inner = tracker.closeNow();
    outer.close();
    assertFalse(tracker.admissionOpen(), "the inner hold still closes admission");
    inner.close();
    inner.close();
    assertTrue(tracker.admissionOpen());
  }

  // ==================== helpers ====================

  static EngineWorkHandle work() {
    return (EngineWorkHandle) Proxy.newProxyInstance(
        EngineWorkHandle.class.getClassLoader(), new Class<?>[] {EngineWorkHandle.class},
        (proxy, method, args) -> {
          throw new UnsupportedOperationException(method.getName());
        });
  }

  private static void await(CyclicBarrier barrier) {
    try {
      barrier.await(5, TimeUnit.SECONDS);
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }
}
