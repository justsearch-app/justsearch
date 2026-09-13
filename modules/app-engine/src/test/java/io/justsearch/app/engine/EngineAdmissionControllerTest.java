/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.EngineAdmissionException;
import io.justsearch.app.api.OpCriticality;
import io.justsearch.app.api.OperationAdmissionClosedException;
import io.justsearch.core.context.EngineContext;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class EngineAdmissionControllerTest {
  @Test
  void invalidFreezeCannotSplitLeaseAndWorkAdmission() {
    var admission = new EngineAdmissionController(2, 2, 1);
    for (String reason : new String[] {"x".repeat(257), "invalid\nreason", " "}) {
      assertThrows(IllegalArgumentException.class, () -> admission.freezeAdmission(reason));
      assertFalse(admission.snapshot().admissionFrozen());
      try (var _ = admission.admit(context("a", EngineContext.Survival.INTERACTIVE), false)) {
        var lease = admission.register("test", OpCriticality.MUST_COMPLETE, 1, Map.of());
        lease.release(io.justsearch.app.api.OpLeaseOutcome.SUCCESS);
      }
    }
  }

  @Test
  void attachedViewCannotChangeWorkOwnedAxesAndBackgroundHasNoDetachTransition() {
    var admission = new EngineAdmissionController(2, 2, 1);
    try (var owner = admission.admit(context("a", EngineContext.Survival.INTERACTIVE), false)) {
      var original = owner.context();
      var forged = new EngineContext(original.clientKind(), original.clientId(), Optional.of("rebased"),
          original.grantReference(), original.sourceTier(), "AGENT_LOOP", EngineContext.Survival.DURABLE,
          EngineContext.Urgency.BACKGROUND, original.workId());
      try (var attached = admission.attach(forged)) {
        assertEquals(EngineContext.Survival.INTERACTIVE, attached.context().survival());
        assertEquals(EngineContext.Urgency.FOREGROUND, attached.context().urgency());
        assertEquals("AGENT_LOOP", attached.context().transport());
        assertEquals(Optional.of("rebased"), attached.context().sessionId());
      }
    }
    try (var background = admission.admit(context("a", EngineContext.Survival.DURABLE)
        .withUrgency(EngineContext.Urgency.BACKGROUND), false)) {
      var flips = new AtomicInteger();
      background.onBackground(flips::incrementAndGet);
      background.waitingClientGone();
      background.onBackground(flips::incrementAndGet);
      assertEquals(0, flips.get());
    }
  }

  private static EngineContext context(String client, EngineContext.Survival survival) {
    return new EngineContext(EngineContext.ClientKind.MCP_CLIENT, client, Optional.of("session"),
        Optional.empty(), "UNTRUSTED", "MCP", survival, EngineContext.Urgency.FOREGROUND);
  }

  @Test
  void fairnessAndAggregateReserveDistinctResources() {
    var admission = new EngineAdmissionController(2, 3, 1);
    var first = admission.admit(context("a", EngineContext.Survival.INTERACTIVE), false);
    var second = admission.admit(context("a", EngineContext.Survival.INTERACTIVE), false);
    assertNotEquals(first.context().workId(), second.context().workId());
    assertEquals(EngineAdmissionException.Reason.CONTEXT_LIMIT, assertThrows(EngineAdmissionException.class,
        () -> admission.admit(context("a", EngineContext.Survival.INTERACTIVE), false)).reason());
    var third = admission.admit(context("b", EngineContext.Survival.INTERACTIVE), false);
    assertEquals(EngineAdmissionException.Reason.ENGINE_LIMIT, assertThrows(EngineAdmissionException.class,
        () -> admission.admit(context("a", EngineContext.Survival.INTERACTIVE), false)).reason(),
        "aggregate refusal takes precedence when this client's budget is also full");
    assertEquals(EngineAdmissionException.Reason.ENGINE_LIMIT, assertThrows(EngineAdmissionException.class,
        () -> admission.admit(context("c", EngineContext.Survival.INTERACTIVE), false)).reason());
    first.close();
    first.close();
    var replacement = admission.admit(context("c", EngineContext.Survival.INTERACTIVE), false);
    assertThrows(EngineAdmissionException.class,
        () -> admission.admit(context("d", EngineContext.Survival.INTERACTIVE), false));
    replacement.close();
    second.close();
    third.close();
  }

  @Test
  void asynchronousOwnerKeepsSlotAndStaleIdentityCannotBecomeFreshWork() {
    var admission = new EngineAdmissionController(1, 1, 1);
    var front = admission.admit(context("a", EngineContext.Survival.INTERACTIVE), false);
    var worker = admission.attach(front.context());
    front.close();
    assertThrows(EngineAdmissionException.class,
        () -> admission.admit(context("a", EngineContext.Survival.INTERACTIVE), false));
    worker.close();
    assertEquals(EngineAdmissionException.Reason.WORK_FINISHED,
        assertThrows(EngineAdmissionException.class, () -> admission.attach(front.context())).reason());
    assertThrows(EngineAdmissionException.class,
        () -> admission.attach(context("a", EngineContext.Survival.INTERACTIVE).withWorkId(UUID.randomUUID())));
    try (var next = admission.admit(context("a", EngineContext.Survival.INTERACTIVE), false)) {
      assertNotEquals(front.context().workId(), next.context().workId());
    }
  }

  @Test
  void processLocalLeaseFreezeClosesBothAdmissionsButControlCanFinishUpgrade() {
    var admission = new EngineAdmissionController(2, 2, 1);
    var snapshot = admission.freezeAdmission("upgrade");
    assertTrue(snapshot.admissionFrozen());
    assertEquals(EngineAdmissionException.Reason.FROZEN,
        assertThrows(EngineAdmissionException.class,
            () -> admission.admit(context("a", EngineContext.Survival.INTERACTIVE), false)).reason());
    assertThrows(OperationAdmissionClosedException.class,
        () -> admission.register("mutation", OpCriticality.MUST_COMPLETE, 1, Map.of()));
    try (var control = admission.admit(context("a", EngineContext.Survival.INTERACTIVE), true)) {
      assertTrue(control.cancellationReason().isEmpty());
      assertThrows(IllegalArgumentException.class, () -> admission.releaseAdmission("wrong"));
      admission.releaseAdmission(snapshot.preparationId());
      assertFalse(admission.snapshot().admissionFrozen());
    }
    try (var resumed = admission.admit(context("a", EngineContext.Survival.INTERACTIVE), false)) {
      assertTrue(resumed.context().workId().isPresent());
    }
  }

  @Test
  void equalAttributionHasIndependentCancellationAndDurableDetachment() {
    var admission = new EngineAdmissionController(3, 3, 1);
    try (var first = admission.admit(context("a", EngineContext.Survival.INTERACTIVE), false);
        var second = admission.admit(context("a", EngineContext.Survival.INTERACTIVE), false);
        var durable = admission.admit(context("a", EngineContext.Survival.DURABLE), false)) {
      var reason = new AtomicReference<String>();
      first.cancel("agent_stop");
      first.onCancel(reason::set);
      first.cancel("quit");
      assertEquals("agent_stop", reason.get());
      assertTrue(second.cancellationReason().isEmpty());
      AtomicInteger background = new AtomicInteger();
      durable.onBackground(background::incrementAndGet);
      durable.waitingClientGone();
      durable.waitingClientGone();
      assertEquals(1, background.get());
      assertEquals(EngineContext.Urgency.BACKGROUND, durable.context().urgency());
      assertEquals(EngineContext.Urgency.FOREGROUND, second.context().urgency());
      admission.cancelInteractive("quit");
      assertEquals(Optional.of("quit"), second.cancellationReason());
      assertTrue(durable.cancellationReason().isEmpty());
    }
  }

  @Test
  void registrationRacingCancellationInvokesOnceAndCanCloseOwner() throws Exception {
    var admission = new EngineAdmissionController(1, 1, 1);
    var work = admission.admit(context("a", EngineContext.Survival.INTERACTIVE), false);
    var start = new CountDownLatch(1);
    var callbacks = new AtomicInteger();
    // Two explicitly owned threads; no common-pool dispatch in the admission contract test.
    try (var threads = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var registration = CompletableFuture.runAsync(() -> {
        await(start);
        work.onCancel(reason -> { callbacks.incrementAndGet(); work.close(); });
      }, threads);
      var cancellation = CompletableFuture.runAsync(() -> {
        await(start);
        admission.cancelInteractive("quit");
      }, threads);
      start.countDown();
      registration.get(5, TimeUnit.SECONDS);
      cancellation.get(5, TimeUnit.SECONDS);
    }
    assertEquals(1, callbacks.get());
    try (var next = admission.admit(context("a", EngineContext.Survival.INTERACTIVE), false)) {
      assertTrue(next.cancellationReason().isEmpty());
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Concurrent test start timed out");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }
}
