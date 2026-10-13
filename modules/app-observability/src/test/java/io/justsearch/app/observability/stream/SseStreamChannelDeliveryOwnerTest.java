/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.api.stream.StreamId;
import io.justsearch.app.observability.health.HealthEvent;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.LifecycleEvent;
import io.justsearch.app.observability.health.Severity;
import io.justsearch.app.observability.health.Source;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * C1-5/C1-16: a channel never creates its own executor. Asynchronous and interruptible delivery
 * run only on the executor the channel's owner supplies, which closes with the channel.
 */
@Timeout(60)
final class SseStreamChannelDeliveryOwnerTest {

  private static HealthEvent event(int i) {
    return new HealthEvent("worker.recovered", Instant.now(), Source.forProcess("head", "b79", "1"),
        Severity.INFO, Optional.of("health-events.worker.recovered.message"),
        new LifecycleEvent(Map.of("i", i), Optional.empty()));
  }

  @Test
  void interruptibleDeliveryWithoutAnOwnerSuppliedExecutorIsRefused() {
    var channel = new SseStreamChannel(StreamId.system("no-delivery-owner"));
    assertThrows(IllegalStateException.class, channel::withInterruptibleDelivery,
        "an interruptible channel must not invent its own delivery executor");
    assertThrows(IllegalStateException.class,
        () -> new HealthEventChangeRegistry(new SseStreamChannel(HealthEventChangeRegistry.STREAM_ID)),
        "Health delivery must run on an executor its owner supplied");
  }

  @Test
  void asynchronousPublicationWithoutAnOwnerSuppliedExecutorIsRefusedBeforeSequencing() {
    var channel = new SseStreamChannel(StreamId.system("no-async-owner"));
    channel.subscribe(frame -> { });
    assertThrows(IllegalStateException.class,
        () -> channel.publishAsync(SseFrameKind.UPDATE, "frame"),
        "asynchronous publication must not invent its own delivery executor");
    assertEquals(0, channel.currentSeq(), "a refused publication allocates no sequence");
    assertTrue(channel.framesSince(0).isEmpty(), "a refused publication retains no frame");
  }

  @Test
  void healthDeliveryRunsOnTheOwnersThreadsAndEndsWithTheChannel() throws Exception {
    try (ExecutorService owner = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("b79-delivery-owner-", 0).factory())) {
      var released = new AtomicInteger();
      var changes = new HealthEventChangeRegistry(new SseStreamChannel(
          HealthEventChangeRegistry.STREAM_ID, owner, released::incrementAndGet));
      var threads = new CopyOnWriteArrayList<String>();
      changes.subscribe(envelope -> threads.add(Thread.currentThread().getName()));
      var sync = Thread.ofPlatform().name("b79-sync-publisher").start(() -> changes.broadcast(
          HealthEventChangeRegistry.Kind.OCCURRENCE_APPENDED, event(1)));
      sync.join(10_000);
      changes.broadcastAsync(
          HealthEventChangeRegistry.Kind.CONDITION_ADDED, event(2));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (threads.size() < 2 && System.nanoTime() < deadline) Thread.sleep(5);
      assertEquals(2, threads.size(), "both publications reach the listener");
      for (String name : threads) {
        assertTrue(name.startsWith("b79-delivery-owner-"),
            "listener ran on " + name + ", not on the owner's executor");
      }
      changes.channel().close();
      assertTrue(owner.isShutdown(), "closing the channel shuts the owner's executor down");
      assertTrue(owner.awaitTermination(10, TimeUnit.SECONDS), "no delivery thread outlives close");
      assertEquals(1, released.get(), "the owner's registration is released exactly once");
    }
  }
}
