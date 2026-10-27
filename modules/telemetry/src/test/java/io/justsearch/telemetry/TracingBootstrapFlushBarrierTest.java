/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

@Timeout(5)
class TracingBootstrapFlushBarrierTest {
  @TempDir Path dir;
  private OpenTelemetry previous;
  private TracingBootstrap bootstrap;

  @BeforeEach
  void createBootstrap() {
    previous = GlobalOpenTelemetry.get();
    GlobalOpenTelemetry.resetForTest();
    bootstrap = new TracingBootstrap(dir, null, Sampler.alwaysOn(), Map.of());
  }

  @AfterEach
  void restoreGlobal() {
    try {
      bootstrap.close();
    } finally {
      GlobalOpenTelemetry.resetForTest();
      GlobalOpenTelemetry.set(previous);
    }
  }

  @Test
  void staleThenSharedSuccessRequiresAwaitingFreshResult() throws Exception {
    var first = new CompletableResultCode().succeed();
    var fresh = new CompletableResultCode();
    var requests = new AtomicInteger();
    var freshRequested = new CountDownLatch(1);
    var result = new FutureTask<>(() -> bootstrap.awaitFreshLocalFlush(
        () -> switch (requests.getAndIncrement()) {
          case 0, 1 -> first;
          case 2 -> CompletableResultCode.ofSuccess();
          case 3 -> {
            freshRequested.countDown();
            yield fresh;
          }
          default -> throw new AssertionError("unexpected extra request");
        }, System.nanoTime() + TimeUnit.SECONDS.toNanos(2)));
    Thread caller = new Thread(result, "fake-flush-barrier");
    caller.setDaemon(true);
    caller.start();
    try {
      assertTrue(freshRequested.await(1, TimeUnit.SECONDS), "must request fresh B after A, A, success");
      assertThrows(TimeoutException.class, () -> result.get(30, TimeUnit.MILLISECONDS),
          "must await B rather than acknowledge its request");
      fresh.succeed();
      assertTrue(result.get(1, TimeUnit.SECONDS));
      assertEquals(4, requests.get());
    } finally {
      fresh.succeed();
      caller.interrupt();
      caller.join(1000);
      assertFalse(caller.isAlive(), "barrier caller must terminate");
    }
  }

  @Test
  void staleResultsExpireWithoutSpinning() throws Exception {
    assertRetryExpires(false);
  }

  @Test
  void sharedSuccessResultsExpireWithoutSpinning() throws Exception {
    assertRetryExpires(true);
  }

  private void assertRetryExpires(boolean sharedSuccess) throws Exception {
    var first = new CompletableResultCode().succeed();
    var requests = new AtomicInteger();
    long started = System.nanoTime();
    long allowance = Duration.ofMillis(100).toNanos();
    boolean flushed = bootstrap.awaitFreshLocalFlush(() -> {
      int count = requests.incrementAndGet();
      assertTrue(count <= 1000, "retries must sleep, not spin or ignore the deadline");
      return count == 1 || !sharedSuccess ? first : CompletableResultCode.ofSuccess();
    }, started + allowance);
    long elapsed = System.nanoTime() - started;
    assertFalse(flushed, "stale or shared success cannot acknowledge fresh coverage");
    assertTrue(requests.get() > 1, "must exercise retries");
    assertTrue(elapsed >= allowance, "must retry until the deadline");
    assertTrue(elapsed < Duration.ofSeconds(1).toNanos(), "must return promptly after the deadline");
  }

  @Test
  void failedFreshResultReturnsFalseAndLogsFailure() throws Exception {
    var first = new CompletableResultCode().succeed();
    var failed = new CompletableResultCode().fail();
    var requests = new AtomicInteger();
    var logger = (Logger) LoggerFactory.getLogger(TracingBootstrap.class);
    var events = new ListAppender<ILoggingEvent>();
    events.start();
    logger.addAppender(events);
    try {
      assertFalse(bootstrap.awaitFreshLocalFlush(
          () -> requests.getAndIncrement() == 0 ? first : failed,
          System.nanoTime() + TimeUnit.SECONDS.toNanos(1)));
      assertEquals(2, requests.get());
      assertTrue(events.list.stream().anyMatch(event -> event.getFormattedMessage()
          .equals("Explicit span flush failed")));
    } finally {
      logger.detachAppender(events);
      events.stop();
    }
  }
}
