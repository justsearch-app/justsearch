/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.EngineAdmissionException;
import io.justsearch.core.context.EngineContext;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * A published-state stream slot is ordinary work for limits, freeze, closing and drain, but is
 * not counted as retaining the index; physical work that joins it is counted until released.
 */
final class PublishedStateAdmissionTest {
  @Test
  void publishedStateSlotIsOrdinaryWorkButDoesNotRetainTheIndex() {
    var admission = new EngineAdmissionController(2, 3, 1);
    var stream = admission.admitPublishedStateRequest(context("ui"), false);
    assertEquals(1, admission.activeWorkCount());
    assertEquals(0, admission.indexRetainingWorkCount());
    var status = admission.reserveStatusAdmission(context("ui"));
    var work = admission.admit(context("ui"), false);
    assertEquals(3, admission.activeWorkCount());
    assertEquals(1, admission.indexRetainingWorkCount(), "only the ordinary work slot uses the index");
    // The stream still occupies its per-context work slot: the bucket is full.
    assertEquals(EngineAdmissionException.Reason.CONTEXT_LIMIT, assertThrows(
        EngineAdmissionException.class,
        () -> admission.admitPublishedStateRequest(context("ui"), false)).reason());
    work.close();
    assertEquals(0, admission.indexRetainingWorkCount());
    status.close();
    stream.close();
    stream.close();
    assertEquals(0, admission.activeWorkCount(), "the mark is released once with its slot");
    assertEquals(0, admission.indexRetainingWorkCount());
    // Aggregate limit counts stream slots as work.
    var a = admission.admitPublishedStateRequest(context("a"), false);
    var b = admission.admitPublishedStateRequest(context("b"), false);
    var c = admission.admit(context("c"), false);
    assertEquals(EngineAdmissionException.Reason.ENGINE_LIMIT, assertThrows(
        EngineAdmissionException.class,
        () -> admission.admitPublishedStateRequest(context("d"), false)).reason());
    a.close();
    b.close();
    c.close();
    assertEquals(0, admission.activeWorkCount());
  }

  @Test
  void physicalWorkJoiningAPublishedStateSlotIsCountedUntilItIsReleased() {
    var admission = new EngineAdmissionController(4, 4, 1);
    var stream = admission.admitPublishedStateRequest(context("ui"), false);
    var attached = admission.attach(stream.context());
    assertEquals(1, admission.activeWorkCount(), "the port call joins the stream's own slot");
    assertEquals(1, admission.indexRetainingWorkCount(), "attaching physical work retains the index");
    var child = attached.retain();
    var inference = admission.attachInference(stream.context());
    attached.close();
    inference.close();
    assertEquals(1, admission.indexRetainingWorkCount(), "a retained physical child still counts");
    // The physical owner outlives the stream's HTTP request.
    stream.close();
    assertEquals(1, admission.activeWorkCount());
    assertEquals(1, admission.indexRetainingWorkCount());
    assertThrows(EngineAdmissionException.class, stream::retain);
    child.close();
    child.close();
    assertEquals(0, admission.activeWorkCount(), "the slot and its mark are released once");
    assertEquals(0, admission.indexRetainingWorkCount());

    // The stream's own handle does not become physical when it is retained by its owner.
    var other = admission.admitPublishedStateRequest(context("ui"), false);
    var own = other.retain();
    assertEquals(0, admission.indexRetainingWorkCount());
    try (var _ = admission.attach(context("ui"))) {
      assertEquals(1, admission.indexRetainingWorkCount(), "unattached physical work counts as usual");
    }
    other.close();
    own.close();
    assertEquals(0, admission.activeWorkCount());
    assertEquals(0, admission.indexRetainingWorkCount());
  }

  @Test
  void publishedStateAdmissionRespectsFreezeAndProcessClosing() {
    var admission = new EngineAdmissionController(4, 4, 1);
    var held = admission.admitPublishedStateRequest(context("ui"), false);
    var snapshot = admission.freezeAdmission("upgrade");
    assertEquals(EngineAdmissionException.Reason.FROZEN, assertThrows(
        EngineAdmissionException.class,
        () -> admission.admitPublishedStateRequest(context("ui"), false)).reason());
    admission.releaseAdmission(snapshot.preparationId());
    admission.beginClosing();
    assertEquals(EngineAdmissionException.Reason.FROZEN, assertThrows(
        EngineAdmissionException.class,
        () -> admission.admitPublishedStateRequest(context("ui"), false)).reason());
    assertEquals(0, admission.indexRetainingWorkCount());
    assertTrue(!admission.awaitDrained(java.time.Duration.ZERO), "shutdown still drains streams");
    held.close();
    assertTrue(admission.awaitDrained(java.time.Duration.ZERO));
  }

  @Test
  void concurrentStreamChurnNeverMasksOrUnderflowsTheIndexRetainingCount() throws Exception {
    var admission = new EngineAdmissionController(8, 32, 1);
    var held = admission.admit(context("held"), false);
    var start = new CountDownLatch(1);
    var violations = new java.util.concurrent.ConcurrentLinkedQueue<Integer>();
    var running = new java.util.concurrent.atomic.AtomicBoolean(true);
    try (var threads = java.util.concurrent.Executors.newFixedThreadPool(6)) {
      var churn = new java.util.ArrayList<CompletableFuture<Void>>();
      for (int t = 0; t < 4; t++) {
        String client = "stream-" + t;
        churn.add(CompletableFuture.runAsync(() -> {
          await(start);
          for (int i = 0; i < 10_000; i++) {
            admission.admitPublishedStateRequest(context(client), false).close();
            admission.reserveStatusAdmission(context(client)).close();
          }
        }, threads));
      }
      var reader = CompletableFuture.runAsync(() -> {
        await(start);
        while (running.get()) {
          int count = admission.indexRetainingWorkCount();
          if (count != 1) violations.add(count);
        }
      }, threads);
      start.countDown();
      CompletableFuture.allOf(churn.toArray(CompletableFuture[]::new)).get(60, TimeUnit.SECONDS);
      running.set(false);
      reader.get(5, TimeUnit.SECONDS);
    }
    assertTrue(violations.isEmpty(), "observed index-retaining counts " + violations);
    held.close();
    assertEquals(0, admission.indexRetainingWorkCount());
    assertEquals(0, admission.activeWorkCount());
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(10, TimeUnit.SECONDS));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private static EngineContext context(String client) {
    return new EngineContext(EngineContext.ClientKind.MCP_CLIENT, client, Optional.of("session"),
        Optional.empty(), "UNTRUSTED", "MCP", EngineContext.Survival.INTERACTIVE,
        EngineContext.Urgency.FOREGROUND);
  }
}
