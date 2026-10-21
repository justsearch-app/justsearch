/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.ipc.IndexingJobsFrame;
import io.justsearch.ipc.IndexingJobsSnapshot;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class IndexingJobsRecoveryTest {
  @Test void successfulSnapshotsResetTheDelayAndAttemptCount() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.bridge.start().get(1, TimeUnit.SECONDS);
      for (int i = 0; i < 8; i++) {
        fixture.error.get().accept(new IllegalStateException("disconnected"));
        var retry = fixture.next();
        assertEquals(250, retry.delayMs(), "every good snapshot resets backoff to attempt one");
        assertTrue(fixture.messages.list.getLast().getFormattedMessage().contains("attempt 1"));
        assertTrue(fixture.messages.list.getLast().getFormattedMessage().contains("250ms"));
        retry.task().run();
      }
    }
  }

  @Test void typedOverflowRecoversImmediatelyButSnapshotLoopsStayRateLimited() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.bridge.start().get(1, TimeUnit.SECONDS);
      for (int i = 0; i < 12; i++) {
        fixture.error.get().accept(new StreamOverflowException("arbitrary message, not a dispatch key"));
        var retry = fixture.next();
        assertEquals(i == 0 ? 0 : 250, retry.delayMs(), "sustained overflow must be prompt and paced");
        fixture.clock.addAndGet(retry.delayMs());
        retry.task().run();
      }
    }
  }

  @Test void firstOverflowIsImmediateEvenWithANegativeMonotonicClock() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.clock.set(-10000);
      fixture.bridge.start().get(1, TimeUnit.SECONDS);
      fixture.error.get().accept(new StreamOverflowException("overflow"));
      assertEquals(0, fixture.next().delayMs());
    }
  }

  private record Retry(Runnable task, long delayMs) {}

  private static final class Fixture implements AutoCloseable {
    final TestEngineExecutors owners = new TestEngineExecutors();
    final LinkedBlockingQueue<Retry> retries = new LinkedBlockingQueue<>();
    final AtomicReference<Consumer<Throwable>> error = new AtomicReference<>();
    final AtomicLong clock = new AtomicLong(10_000);
    final AtomicLong snapshots = new AtomicLong();
    final RemoteIndexingJobsBridge bridge;
    final ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(RemoteIndexingJobsBridge.class);
    final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> messages =
        new ch.qos.logback.core.read.ListAppender<>();

    Fixture() {
      messages.start(); logger.addAppender(messages);
      var observed = mock(EngineExecutorRegistry.class, org.mockito.AdditionalAnswers.delegatesTo(owners));
      doAnswer(call -> {
        EngineExecutorSpec spec = call.getArgument(0);
        var owner = owners.register(spec);
        if (!spec.name().equals("head.indexing-jobs-bridge-reconnect")) return owner;
        var registration = mock(EngineExecutorRegistry.Registration.class,
            org.mockito.AdditionalAnswers.delegatesTo(owner));
        var timer = mock(ScheduledExecutorService.class);
        doAnswer(scheduled -> {
          retries.add(new Retry(scheduled.getArgument(0),
              ((TimeUnit) scheduled.getArgument(2)).toMillis(scheduled.getArgument(1))));
          return mock(ScheduledFuture.class);
        }).when(timer).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        doReturn(timer).when(registration).openScheduled(any());
        return registration;
      }).when(observed).register(any());
      bridge = new RemoteIndexingJobsBridge(observed, () -> (frame, failed, completed) -> {
        error.set(failed);
        frame.accept(IndexingJobsFrame.newBuilder().setSeq(snapshots.incrementAndGet())
            .setSnapshot(IndexingJobsSnapshot.getDefaultInstance()).build());
        return () -> {};
      }, clock::get);
    }

    Retry next() throws InterruptedException {
      Retry retry = retries.poll(1, TimeUnit.SECONDS);
      assertNotNull(retry, "the retry obligation must reach its scheduler");
      return retry;
    }

    @Override public void close() {
      bridge.stop(); owners.close(); logger.detachAppender(messages); messages.stop();
    }
  }
}
