/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.observability.stream.SseStreamChannel;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * B5-H1: a thread publishing Health never leaves {@code broadcast} with an interrupt it did not
 * have on entry, is never interrupted because a subscriber went away, and keeps its own
 * interrupt. The first three cases are the verifier's OwnInterruptLeak probe with its assertions.
 */
@Timeout(60)
final class HealthPublisherInterruptTest {

  @TempDir Path dir;

  private static HealthEvent event(int i) {
    return new HealthEvent("worker.recovered", Instant.now(), Source.forProcess("head", "b77", "1"),
        Severity.INFO, Optional.of("health-events.worker.recovered.message"),
        new LifecycleEvent(Map.of("i", i), Optional.empty()));
  }

  /** What the producer saw right after broadcast: its interrupt flag and its next file read. */
  record Outcome(boolean interrupted, String io) {}

  private Path file() throws Exception {
    Path file = dir.resolve("producer-io.bin");
    if (!Files.exists(file)) Files.write(file, new byte[64]);
    return file;
  }

  private static Outcome produce(Runnable publish, Path file) throws Exception {
    var out = new AtomicReference<Outcome>();
    Thread producer = Thread.ofPlatform().name("b77-producer").start(() -> {
      publish.run();
      boolean flag = Thread.currentThread().isInterrupted();
      String io = "read-ok";
      try (var channel = FileChannel.open(file)) {
        channel.read(ByteBuffer.allocate(8));
      } catch (Exception failure) {
        io = failure.getClass().getSimpleName();
      }
      out.set(new Outcome(flag, io));
    });
    producer.join(20_000);
    assertFalse(producer.isAlive(), "producer must return from broadcast");
    return out.get();
  }

  private static Runnable broadcast(HealthEventChangeRegistry changes, int i) {
    return () -> changes.broadcast(HealthEventChangeRegistry.Kind.OCCURRENCE_APPENDED, event(i));
  }

  private static void spin(long nanos) {
    long end = System.nanoTime() + nanos;
    while (System.nanoTime() < end) Thread.onSpinWait();
  }

  @Test
  void slowObserverUnsubscribedElsewhereLeavesProducerUninterrupted() throws Exception {
    var changes = new HealthEventChangeRegistry();
    var entered = new CountDownLatch(1);
    var sub = changes.subscribe(envelope -> {
      entered.countDown();
      spin(300_000_000L);
    });
    var other = new CopyOnWriteArrayList<Long>();
    changes.subscribe(envelope -> other.add(envelope.seq()));
    ExecutorService unsubscriber = Executors.newSingleThreadExecutor();
    try {
      Future<?> unsubscribe = unsubscriber.submit(() -> {
        entered.await();
        Thread.sleep(50);
        sub.unsubscribe();
        return null;
      });
      Outcome outcome = produce(broadcast(changes, 1), file());
      unsubscribe.get(10, TimeUnit.SECONDS);
      assertFalse(outcome.interrupted(), "producer left interrupted after another thread unsubscribed");
      assertEquals("read-ok", outcome.io(), "producer's next FileChannel read must succeed");
      assertEquals(1, other.size(), "the remaining subscriber still receives the event");
    } finally {
      unsubscriber.shutdownNow();
    }
  }

  @Test
  void interruptibleObserverUnsubscribedElsewhereLeavesProducerUninterrupted() throws Exception {
    var changes = new HealthEventChangeRegistry();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var sub = changes.subscribe(envelope -> {
      entered.countDown();
      try {
        release.await(2, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    });
    ExecutorService unsubscriber = Executors.newSingleThreadExecutor();
    try {
      Future<?> unsubscribe = unsubscriber.submit(() -> {
        entered.await();
        Thread.sleep(50);
        sub.unsubscribe();
        return null;
      });
      Outcome outcome = produce(broadcast(changes, 2), file());
      unsubscribe.get(10, TimeUnit.SECONDS);
      assertFalse(outcome.interrupted(), "producer left interrupted after another thread unsubscribed");
      assertEquals("read-ok", outcome.io(), "producer's next FileChannel read must succeed");
    } finally {
      release.countDown();
      unsubscriber.shutdownNow();
    }
  }

  @Test
  void concurrentUnsubscribeRacesNeverInterruptProducer() throws Exception {
    int races = 400;
    int stray = 0;
    int ioFailures = 0;
    Path file = file();
    for (int i = 0; i < races; i++) {
      var changes = new HealthEventChangeRegistry();
      var entered = new CountDownLatch(1);
      var sub = changes.subscribe(envelope -> {
        entered.countDown();
        spin(200_000L);
      });
      Thread unsubscriber = Thread.ofPlatform().start(() -> {
        try {
          entered.await();
        } catch (InterruptedException ignored) {
          // fall through to unsubscribe
        }
        sub.unsubscribe();
      });
      Outcome outcome = produce(broadcast(changes, 3), file);
      unsubscriber.join(10_000);
      if (outcome.interrupted()) stray++;
      if (!outcome.io().equals("read-ok")) ioFailures++;
    }
    assertEquals(0, stray, "races of " + races + " that left the producer interrupted");
    assertEquals(0, ioFailures, "races of " + races + " whose next producer read failed");
  }

  @Test
  void channelCloseDuringDeliveryLeavesProducerUninterrupted() throws Exception {
    var changes = new HealthEventChangeRegistry();
    var entered = new CountDownLatch(1);
    changes.subscribe(envelope -> {
      entered.countDown();
      spin(300_000_000L);
    });
    Thread closer = Thread.ofPlatform().start(() -> {
      try {
        entered.await();
        Thread.sleep(50);
      } catch (InterruptedException ignored) {
        // close anyway
      }
      changes.channel().close();
    });
    Outcome outcome = produce(broadcast(changes, 4), file());
    closer.join(10_000);
    assertFalse(outcome.interrupted(), "closing the Health channel must not interrupt a publisher");
    assertEquals("read-ok", outcome.io(), "producer's next FileChannel read must succeed");
  }

  @Test
  void producerInterruptedBeforePublishStaysInterrupted() throws Exception {
    var changes = new HealthEventChangeRegistry();
    var delivered = new CountDownLatch(1);
    // A careless observer that swallows InterruptedException. On the producer's thread it would
    // consume the producer's own interrupt; it must never run there.
    changes.subscribe(envelope -> {
      try {
        Thread.sleep(5);
      } catch (InterruptedException swallowed) {
        // deliberately not restored
      }
      delivered.countDown();
    });
    var flagAfter = new AtomicReference<Boolean>();
    Thread producer = Thread.ofPlatform().start(() -> {
      Thread.currentThread().interrupt();
      changes.broadcast(HealthEventChangeRegistry.Kind.OCCURRENCE_APPENDED, event(5));
      flagAfter.set(Thread.currentThread().isInterrupted());
    });
    producer.join(10_000);
    assertNotNull(flagAfter.get(), "producer returned");
    assertTrue(flagAfter.get(), "a producer interrupted before broadcast keeps its own interrupt");
    assertTrue(delivered.await(5, TimeUnit.SECONDS), "the event is still delivered");
  }

  @Test
  void producerInterruptedWhileWaitingReturnsPromptlyWithItsInterrupt() throws Exception {
    var changes = new HealthEventChangeRegistry();
    var entered = new CountDownLatch(1);
    var hold = new AtomicBoolean(true);
    changes.subscribe(envelope -> {
      entered.countDown();
      while (hold.get()) Thread.onSpinWait();
    });
    var flagAfter = new AtomicReference<Boolean>();
    try {
      Thread producer = Thread.ofPlatform().start(() -> {
        changes.broadcast(HealthEventChangeRegistry.Kind.OCCURRENCE_APPENDED, event(6));
        flagAfter.set(Thread.currentThread().isInterrupted());
      });
      assertTrue(entered.await(5, TimeUnit.SECONDS), "observer entered");
      producer.interrupt();
      producer.join(500);
      assertFalse(producer.isAlive(), "an interrupted producer is not held by a blocked observer");
      assertNotNull(flagAfter.get(), "producer returned");
      assertTrue(flagAfter.get(), "the owner's interrupt is kept");
    } finally {
      hold.set(false);
    }
  }

  @Test
  void blockedObserverHoldsPublisherOnlyBrieflyAndOthersKeepOrder() throws Exception {
    var changes = new HealthEventChangeRegistry();
    var entered = new CountDownLatch(1);
    var hold = new AtomicBoolean(true);
    var stubborn = changes.subscribe(envelope -> {
      entered.countDown();
      while (hold.get()) Thread.onSpinWait();
    });
    var healthy = new CopyOnWriteArrayList<Long>();
    changes.subscribe(envelope -> healthy.add(envelope.seq()));
    try {
      Thread first = Thread.ofPlatform().start(
          () -> changes.broadcast(HealthEventChangeRegistry.Kind.OCCURRENCE_APPENDED, event(7)));
      assertTrue(entered.await(5, TimeUnit.SECONDS), "observer entered");
      first.join(3_000);
      assertFalse(first.isAlive(), "a blocked observer holds the publisher at most the bound");
      var later = Thread.ofPlatform().start(() -> {
        for (int i = 0; i < 5; i++) {
          changes.broadcast(HealthEventChangeRegistry.Kind.OCCURRENCE_APPENDED, event(8 + i));
        }
      });
      later.join(1_000);
      assertFalse(later.isAlive(), "a stalled observer is not waited for again");
      assertEquals(6, healthy.size(), "the healthy subscriber received every event before return");
      for (int i = 1; i < healthy.size(); i++) {
        assertTrue(healthy.get(i) > healthy.get(i - 1), "healthy subscriber order " + healthy);
      }
      stubborn.unsubscribe();
    } finally {
      hold.set(false);
    }
  }

  @Test
  void attachingThreadLeavesWithoutTheInterruptItsRetirementRaised() throws Exception {
    var changes = new HealthEventChangeRegistry();
    var entered = new CountDownLatch(1);
    var registration = new AtomicReference<SseStreamChannel.Subscription>();
    var flagAfter = new AtomicReference<Boolean>();
    Thread attacher = Thread.ofPlatform().start(() -> {
      changes.channel().subscribeAndReplay(envelope -> {}, 0, () -> {
        entered.countDown();
        try {
          new CountDownLatch(1).await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      }, registration::set);
      flagAfter.set(Thread.currentThread().isInterrupted());
    });
    assertTrue(entered.await(5, TimeUnit.SECONDS), "prefix entered");
    registration.get().unsubscribe();
    attacher.join(10_000);
    assertFalse(attacher.isAlive(), "retirement releases the attaching prefix");
    assertNotNull(flagAfter.get(), "attacher returned");
    assertFalse(flagAfter.get(),
        "the interrupt that released the subscriber's own prefix must not outlive the attachment");
  }
}
