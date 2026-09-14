/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.api.stream.StreamId;
import io.justsearch.app.observability.stream.FrameHistoryRingBuffer;
import io.justsearch.app.observability.stream.SseStreamChannel;
import io.justsearch.app.observability.stream.StreamSequenceTracker;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

final class SseConnectionTest {
  private static SseStreamChannel channel(String name) {
    return new SseStreamChannel(StreamId.surface(name), new StreamSequenceTracker(),
        new FrameHistoryRingBuffer(2), Clock.systemUTC());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void heartbeatRejectionReleasesEverySubscriptionAndCompletesRequest(boolean multiplex) {
    Harness h = new Harness();
    var first = channel("first");
    var second = channel("second");
    when(h.timer.scheduleAtFixedRate(any(), anyLong(), anyLong(), any()))
        .thenThrow(new RejectedExecutionException("timer stopped"));
    assertThrows(RejectedExecutionException.class, () -> h.attach(multiplex, first, second, Map::of));
    assertEquals(0, first.listenerCount());
    assertEquals(0, second.listenerCount());
    assertTrue(h.completion().isDone());
    assertTrue(h.terminated.get());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void closeDuringPrefixCannotLeakAcquisitionOrScheduleHeartbeat(boolean multiplex) {
    Harness h = new Harness();
    var first = channel("first");
    var second = channel("second");
    h.onSend = frame -> h.client.close();
    assertThrows(IllegalStateException.class, () -> h.attach(multiplex, first, second, Map::of));
    assertEquals(0, first.listenerCount());
    assertEquals(0, second.listenerCount());
    assertTrue(h.completion().isDone());
    verify(h.timer, never()).scheduleAtFixedRate(any(), anyLong(), anyLong(), any());
  }

  @Test
  void laterMultiplexSnapshotFailureReleasesEarlierAttachedSource() {
    Harness h = new Harness();
    var first = channel("first");
    var second = channel("second");
    var failure = new IllegalArgumentException("snapshot failed");
    assertSame(failure, assertThrows(IllegalArgumentException.class, () ->
        MultiplexedSseWriter.attachAll(h.client, List.of(
            new MultiplexedSseWriter.ChannelSource(first, Map::of),
            new MultiplexedSseWriter.ChannelSource(second, () -> { throw failure; })),
            channel("heartbeat"), Clock.systemUTC(), h.timer, 15)));
    assertEquals(0, first.listenerCount());
    assertEquals(0, second.listenerCount());
    assertTrue(h.completion().isDone());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @Timeout(10)
  void overflowClosesConnectionCancelsHeartbeatAndReleasesSibling(boolean multiplex) throws Exception {
    Harness h = new Harness();
    var first = channel("first");
    var second = channel("second");
    h.attach(multiplex, first, second, Map::of);
    CountDownLatch blocked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    h.onSend = frame -> {
      if ("UPDATE".equals(frame.get("frameKind"))) {
        blocked.countDown();
        try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { throw new AssertionError(failure); }
      }
    };
    try (var executor = Executors.newSingleThreadExecutor()) {
      var publisher = executor.submit(() -> first.publish(SseFrameKind.UPDATE, "first"));
      try {
        assertTrue(blocked.await(5, TimeUnit.SECONDS));
        for (int n = 0; n < 3; n++) first.publish(SseFrameKind.UPDATE, n);
        assertTrue(h.completion().isDone(), "overflow must terminate before the blocked send returns");
        assertTrue(h.terminated.get());
        assertEquals(0, first.listenerCount());
        assertEquals(0, second.listenerCount());
        verify(h.heartbeat).cancel(false);
      } finally {
        release.countDown();
        publisher.get(5, TimeUnit.SECONDS);
        h.client.close();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void expiredSnapshotRetriesBeforeEmittingAnyCandidate(boolean multiplex) {
    Harness h = new Harness();
    var first = channel("first");
    AtomicInteger reads = new AtomicInteger();
    h.attach(multiplex, first, channel("second"), () -> {
      int read = reads.incrementAndGet();
      if (read == 1) for (int n = 0; n < 3; n++) first.publish(SseFrameKind.UPDATE, n);
      return Map.of("read", read);
    });
    try {
      assertEquals(2, reads.get());
      var snapshots = h.sent.stream().filter(frame -> "surface:first".equals(frame.get("streamId")))
          .map(frame -> (Map<?, ?>) frame.get("payload"))
          .filter(payload -> "snapshot".equals(payload.get("kind"))).toList();
      assertEquals(1, snapshots.size());
      assertEquals(2, snapshots.getFirst().get("read"), "expired candidate must not appear on wire");
    } finally { h.client.close(); }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @Timeout(10)
  void replayOverflowClosesRequestBeforeInitialAttachmentReturns(boolean multiplex) throws Exception {
    Harness h = new Harness();
    var source = channel("initial-overflow");
    when(h.context.queryParam("since")).thenReturn(source.captureSnapshotBoundary().resumeToken());
    source.publish(SseFrameKind.UPDATE, "replay");
    CountDownLatch blocked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    h.onSend = frame -> {
      if ("UPDATE".equals(frame.get("frameKind"))) {
        blocked.countDown();
        try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { throw new AssertionError(failure); }
      }
    };
    try (var executor = Executors.newSingleThreadExecutor()) {
      var attaching = executor.submit(() -> h.attach(multiplex, source, channel("second"), Map::of));
      try {
        assertTrue(blocked.await(5, TimeUnit.SECONDS));
        for (int n = 0; n < 3; n++) source.publish(SseFrameKind.UPDATE, n);
        assertTrue(h.completion().isDone(), "ownership must exist before replay can block");
        assertFalse(attaching.isDone());
        assertEquals(0, source.listenerCount());
        verify(h.timer, never()).scheduleAtFixedRate(any(), anyLong(), anyLong(), any());
      } finally {
        release.countDown();
        var failed = assertThrows(java.util.concurrent.ExecutionException.class,
            () -> attaching.get(5, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, failed.getCause());
        h.client.close();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void repeatedSnapshotExpiryIsBoundedAndClosesRequest(boolean multiplex) {
    Harness h = new Harness();
    var first = channel("first");
    AtomicInteger reads = new AtomicInteger();
    assertThrows(IllegalStateException.class, () -> h.attach(multiplex, first, channel("second"), () -> {
      reads.incrementAndGet();
      for (int n = 0; n < 3; n++) first.publish(SseFrameKind.UPDATE, n);
      return Map.of();
    }));
    assertEquals(3, reads.get());
    assertTrue(h.sent.isEmpty());
    assertEquals(0, first.listenerCount());
    assertTrue(h.completion().isDone());
  }

  @Test
  void closeDuringFutureRegistrationAndLateResourceAcquisitionAreOneShot() {
    Harness h = new Harness();
    AtomicInteger cleanup = new AtomicInteger();
    SseConnection owner = new SseConnection(h.client, cleanup::incrementAndGet);
    doAnswer(call -> { h.client.close(); h.future = call.getArgument(0); return null; })
        .when(h.context).future(any());
    owner.start();
    var source = channel("late");
    owner.own(source.subscribe(frame -> {}));
    owner.own(h.heartbeat);
    owner.terminate();
    assertEquals(1, cleanup.get());
    assertEquals(0, source.listenerCount());
    assertTrue(h.completion().isDone());
    verify(h.heartbeat).cancel(false);
  }

  @Test
  void heartbeatAcknowledgesDeliveredDataInsteadOfGlobalControlSequence() {
    Harness h = new Harness();
    var source = channel("checkpoint");
    h.attach(false, source, channel("unused"), Map::of);
    try {
      source.publish(SseFrameKind.UPDATE, "delivered");
      String delivered = (String) h.sent.getLast().get("resumeToken");
      source.nextEnvelope(SseFrameKind.LIFECYCLE, Map.of("kind", "other-client-control"));
      var task = org.mockito.ArgumentCaptor.forClass(Runnable.class);
      verify(h.timer).scheduleAtFixedRate(task.capture(), anyLong(), anyLong(), any());
      task.getValue().run();
      assertEquals("heartbeat", ((Map<?, ?>) h.sent.getLast().get("payload")).get("kind"));
      assertEquals(delivered, h.sent.getLast().get("resumeToken"));
    } finally { h.client.close(); }
  }

  private static final class Harness {
    private final SseClient client = mock(SseClient.class);
    private final Context context = mock(Context.class);
    private final ScheduledExecutorService timer = mock(ScheduledExecutorService.class);
    private final ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
    private final AtomicBoolean terminated = new AtomicBoolean();
    private final List<Map<?, ?>> sent = new CopyOnWriteArrayList<>();
    private Runnable closed;
    private Supplier<? extends CompletableFuture<?>> future;
    private Consumer<Map<?, ?>> onSend = frame -> {};

    private Harness() {
      when(client.ctx()).thenReturn(context);
      when(client.terminated()).thenAnswer(call -> terminated.get());
      doAnswer(call -> { closed = call.getArgument(0); return null; }).when(client).onClose(any());
      doAnswer(call -> {
        if (terminated.compareAndSet(false, true) && closed != null) closed.run();
        return null;
      }).when(client).close();
      doAnswer(call -> { future = call.getArgument(0); return null; }).when(context).future(any());
      doAnswer(call -> {
        Map<?, ?> frame = JsonMapper.builder().build().readValue(call.getArgument(1, String.class), Map.class);
        sent.add(frame);
        onSend.accept(frame);
        return null;
      }).when(client).sendEvent(anyString(), anyString());
      doReturn(heartbeat).when(timer).scheduleAtFixedRate(any(), anyLong(), anyLong(), any());
    }

    private CompletableFuture<?> completion() { return future.get(); }

    private void attach(boolean multiplex, SseStreamChannel first, SseStreamChannel second,
        Supplier<Map<String, Object>> snapshot) {
      if (multiplex) MultiplexedSseWriter.attachAll(client, List.of(
          new MultiplexedSseWriter.ChannelSource(first, snapshot),
          new MultiplexedSseWriter.ChannelSource(second, Map::of)),
          channel("heartbeat"), Clock.systemUTC(), timer, 15);
      else SseEnvelopeWriter.attach(client, first, snapshot, Clock.systemUTC(), timer, 15);
    }
  }
}
