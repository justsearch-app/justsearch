/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.justsearch.app.api.runtime.containment.EngineContainment;
import io.justsearch.app.engine.EngineContainmentNotice;
import io.justsearch.app.engine.NewerDataNotice;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.OccurrenceLog;
import io.justsearch.app.observability.health.Source;
import io.justsearch.app.services.atrest.AtRestProtection;
import io.justsearch.app.services.observability.health.AtRestHealthTap;
import io.justsearch.app.services.observability.health.BootRecoveryEmitter;
import io.justsearch.app.services.observability.health.ConversationProtectionHealthTap;
import io.justsearch.app.services.observability.health.HeadHealthEventsEmitter;
import io.justsearch.app.services.worker.RecoveryContext;
import io.justsearch.app.services.worker.RecoveryOccurrence;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * B5-H1 through each production synchronous Health publisher: when an SSE client (or any other
 * subscriber) goes away while the publisher is delivering to it, the publishing thread (health
 * monitor recovery, sampler, boot or agent run thread) is not interrupted and its next file I/O
 * succeeds.
 */
@Timeout(120)
final class HealthProducerInterruptTest {
  private static final Source SOURCE = Source.forProcess("head", "b77", "1");
  private static final int RACES = 50;

  @TempDir Path dir;

  @ParameterizedTest(name = "{displayName}: {arguments}")
  @ValueSource(strings = {
      "recovery-occurrence", "boot-recovery", "engine-containment", "newer-data",
      "agent-session", "at-rest-tap", "conversation-protection-tap"})
  void subscriberLeavingDuringDeliveryNeverInterruptsTheProducer(String publisher) throws Exception {
    Path file = dir.resolve("producer-io.bin");
    Files.write(file, new byte[64]);
    int interrupted = 0;
    int ioFailures = 0;
    int published = 0;
    for (int i = 0; i < RACES; i++) {
      var changes = new HealthEventChangeRegistry();
      var entered = new CountDownLatch(1);
      var deliveries = new AtomicInteger();
      var sub = changes.subscribe(envelope -> {
        deliveries.incrementAndGet();
        entered.countDown();
        long end = System.nanoTime() + 200_000L;
        while (System.nanoTime() < end) Thread.onSpinWait();
      });
      Thread unsubscriber = Thread.ofPlatform().start(() -> {
        try {
          entered.await();
        } catch (InterruptedException ignored) {
          // unsubscribe anyway
        }
        sub.unsubscribe();
      });
      var outcome = new AtomicReference<String>();
      Thread producer = Thread.ofPlatform().name("b77-" + publisher).start(() -> {
        publication(publisher).accept(changes);
        boolean flag = Thread.currentThread().isInterrupted();
        String io = "read-ok";
        try (var channel = FileChannel.open(file)) {
          channel.read(ByteBuffer.allocate(8));
        } catch (Exception failure) {
          io = failure.getClass().getSimpleName();
        }
        outcome.set((flag ? "interrupted" : "clear") + "/" + io);
      });
      producer.join(20_000);
      assertFalse(producer.isAlive(), publisher + " returned");
      if (entered.getCount() > 0) entered.countDown();
      unsubscriber.join(10_000);
      assertNotNull(outcome.get(), publisher + " finished");
      if (outcome.get().startsWith("interrupted")) interrupted++;
      if (!outcome.get().endsWith("read-ok")) ioFailures++;
      if (deliveries.get() > 0) published++;
    }
    assertEquals(RACES, published, publisher + " must actually publish in every race");
    assertEquals(0, interrupted, publisher + ": producer left interrupted in races of " + RACES);
    assertEquals(0, ioFailures, publisher + ": producer's next FileChannel read failed in races of "
        + RACES);
  }

  private static Consumer<HealthEventChangeRegistry> publication(String publisher) {
    Clock clock = Clock.systemUTC();
    return switch (publisher) {
      case "recovery-occurrence" -> changes -> CapabilityHealthBridge.emitRecoveryOccurrence(
          new RecoveryOccurrence(RecoveryOccurrence.Kind.ATTEMPTED,
              new RecoveryContext(1, "worker.unresponsive", 100L)),
          new OccurrenceLog(16), changes, SOURCE);
      case "boot-recovery" -> changes -> BootRecoveryEmitter.emitUncleanShutdownRecovered(
          new OccurrenceLog(16), changes, SOURCE, clock, OptionalLong.of(42L));
      case "engine-containment" -> changes -> EngineContainmentNotice.publish(
          EngineContainment.Status.UNAVAILABLE, Optional.of("test"), new ConditionStore(), changes,
          SOURCE, clock);
      case "newer-data" -> changes -> NewerDataNotice.publish(Optional.of("newer data"),
          new ConditionStore(), changes, SOURCE, clock);
      case "agent-session" -> changes -> new HeadHealthEventsEmitter(
          new OccurrenceLog(16), changes, SOURCE, clock)
          .onSessionTerminated("s1", "ERRORED", "x", null, 1L, 1, 1, 1);
      case "at-rest-tap" -> changes -> new AtRestHealthTap(new ConditionStore(), changes, SOURCE,
          clock, () -> new AtRestProtection(AtRestProtection.State.NOT_ENCRYPTED, "test",
              AtRestProtection.Confidence.HIGH)).accept();
      case "conversation-protection-tap" -> changes -> new ConversationProtectionHealthTap(
          new ConditionStore(), changes, SOURCE, clock, () -> true).accept();
      default -> throw new AssertionError(publisher);
    };
  }
}
