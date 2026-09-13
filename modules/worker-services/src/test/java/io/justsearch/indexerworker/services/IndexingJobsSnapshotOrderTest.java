/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.IndexingJobChangeFeed;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.ipc.IndexingJobsFrame;
import io.justsearch.ipc.SubscribeIndexingJobsRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Forces the post-snapshot delta before subscribeWithSnapshot returns to the service. */
final class IndexingJobsSnapshotOrderTest {
  @Test
  void snapshotPrecedesDeltasAlreadyDeliveredDuringRegistration() throws Exception {
    Fixture fixture = new Fixture(2);
    List<IndexingJobsFrame> frames = new ArrayList<>();
    fixture.subscribe(frames::add);
    assertEquals(3, frames.size());
    assertTrue(frames.getFirst().hasSnapshot());
    assertEquals("PENDING", frames.getFirst().getSnapshot().getItems(0).getState());
    assertEquals(List.of("state-0", "state-1"), frames.subList(1, 3).stream()
        .map(frame -> frame.getDelta().getUpdate().getState()).toList());
    assertEquals(List.of(1L, 2L, 3L), frames.stream().map(IndexingJobsFrame::getSeq).toList());
    fixture.emit("live");
    assertEquals("live", frames.getLast().getDelta().getUpdate().getState());
    assertFalse(fixture.closed.get());
  }

  @Test
  void callbackThreadCanFinishBeforeSubscribeReturnsWithoutLockInversion() throws Exception {
    Fixture fixture = new Fixture(0);
    CountDownLatch snapshotCaptured = new CountDownLatch(1);
    CountDownLatch callbackDone = new CountDownLatch(1);
    try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
      var writer = tasks.submit(() -> {
        assertTrue(snapshotCaptured.await(5, TimeUnit.SECONDS));
        try {
          fixture.emit("concurrent");
        } finally {
          callbackDone.countDown();
        }
        return true;
      });
      fixture.beforeReturn = () -> {
        snapshotCaptured.countDown();
        try {
          assertTrue(callbackDone.await(5, TimeUnit.SECONDS),
              "callback thread must not wait on an emitter lock held across subscribe");
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new AssertionError(e);
        }
      };
      List<IndexingJobsFrame> frames = new ArrayList<>();
      fixture.subscribe(frames::add);
      assertTrue(writer.get(5, TimeUnit.SECONDS));
      assertEquals(2, frames.size());
      assertTrue(frames.getFirst().hasSnapshot());
      assertEquals("concurrent", frames.getLast().getDelta().getUpdate().getState());
      assertEquals(List.of(1L, 2L), frames.stream().map(IndexingJobsFrame::getSeq).toList());
      assertFalse(fixture.closed.get());
    }
  }

  @Test
  void reentrantSnapshotSinkCannotOvertakeBufferedDeltas() throws Exception {
    Fixture fixture = new Fixture(2);
    List<IndexingJobsFrame> frames = new ArrayList<>();
    fixture.subscribe(frame -> {
      frames.add(frame);
      if (frame.hasSnapshot()) fixture.emit("reentrant-snapshot");
      if (frame.hasDelta() && frame.getDelta().getUpdate().getState().equals("state-0")) {
        fixture.emit("reentrant-delta");
      }
    });
    assertTrue(frames.getFirst().hasSnapshot());
    assertEquals(List.of("state-0", "state-1", "reentrant-snapshot", "reentrant-delta"),
        frames.subList(1, 5).stream().map(frame -> frame.getDelta().getUpdate().getState()).toList());
    assertEquals(List.of(1L, 2L, 3L, 4L, 5L), frames.stream().map(IndexingJobsFrame::getSeq).toList());
  }

  @Test
  void initialHandoffOverflowClosesAndFailsInsteadOfDropping() throws Exception {
    Fixture fixture = new Fixture(257);
    List<IndexingJobsFrame> frames = new ArrayList<>();
    var failure = assertThrows(WorkerServiceException.class, () -> fixture.subscribe(frames::add));
    assertTrue(failure.getMessage().contains("snapshot handoff overflow"));
    assertTrue(fixture.closed.get(), "the subscription returned after overflow must still be closed");
    assertTrue(frames.isEmpty(), "no partial snapshot/delta stream can masquerade as synchronized");
    fixture.emit("late");
    assertTrue(frames.isEmpty());
  }

  @Test
  void fullInitialHandoffIsDeliveredWithoutOverflow() throws Exception {
    Fixture fixture = new Fixture(256);
    List<IndexingJobsFrame> frames = new ArrayList<>();
    fixture.subscribe(frames::add);
    assertEquals(257, frames.size());
    assertTrue(frames.getFirst().hasSnapshot());
    assertEquals("state-255", frames.getLast().getDelta().getUpdate().getState());
    assertFalse(fixture.closed.get());
  }

  @Test
  void snapshotSinkFailureClosesWithoutFlushingBufferedDeltas() throws Exception {
    Fixture fixture = new Fixture(2);
    List<IndexingJobsFrame> frames = new ArrayList<>();
    var expected = new IllegalStateException("fixture sink");
    var actual = assertThrows(IllegalStateException.class, () -> fixture.subscribe(frame -> {
      frames.add(frame);
      throw expected;
    }));
    assertEquals(expected, actual);
    assertEquals(1, frames.size());
    assertTrue(frames.getFirst().hasSnapshot());
    assertTrue(fixture.closed.get());
    fixture.emit("late");
    assertEquals(1, frames.size());
  }

  @Test
  void bufferedDeltaSinkFailureClosesAndDiscardsTheRemainingBuffer() throws Exception {
    Fixture fixture = new Fixture(2);
    List<IndexingJobsFrame> frames = new ArrayList<>();
    fixture.subscribe(frame -> {
      frames.add(frame);
      if (frame.hasDelta()) throw new IllegalStateException("fixture delta sink");
    });
    assertEquals(2, frames.size());
    assertTrue(frames.getFirst().hasSnapshot());
    assertEquals("state-0", frames.getLast().getDelta().getUpdate().getState());
    assertTrue(fixture.closed.get());
    fixture.emit("late");
    assertEquals(2, frames.size());
  }

  @Test
  void overflowDuringSnapshotEmissionFailsThePartiallyOpenedStream() throws Exception {
    Fixture fixture = new Fixture(2);
    List<IndexingJobsFrame> frames = new ArrayList<>();
    var failure = assertThrows(WorkerServiceException.class, () -> fixture.subscribe(frame -> {
      frames.add(frame);
      if (frame.hasSnapshot()) {
        for (int i = 0; i < 255; i++) fixture.emit("during-snapshot");
      }
    }));
    assertTrue(failure.getMessage().contains("snapshot handoff overflow"));
    assertEquals(1, frames.size());
    assertTrue(frames.getFirst().hasSnapshot());
    assertTrue(fixture.closed.get());
  }

  @Test
  void cancellationBeforeSubscriptionReturnClosesTheLateHandle() throws Exception {
    Fixture fixture = new Fixture(2);
    TestCancel signal = new TestCancel();
    fixture.beforeReturn = signal::cancel;
    List<IndexingJobsFrame> frames = new ArrayList<>();
    fixture.subscribe(frames::add, context(signal));
    assertTrue(fixture.closed.get());
    assertTrue(frames.isEmpty());
    fixture.emit("late");
    assertTrue(frames.isEmpty());
  }

  @Test
  void cancellationFromSnapshotSinkPreventsBufferedDelivery() throws Exception {
    Fixture fixture = new Fixture(2);
    TestCancel signal = new TestCancel();
    List<IndexingJobsFrame> frames = new ArrayList<>();
    fixture.subscribe(frame -> {
      frames.add(frame);
      signal.cancel();
    }, context(signal));
    assertTrue(fixture.closed.get());
    assertEquals(1, frames.size());
    assertTrue(frames.getFirst().hasSnapshot());
  }

  private static CallContext context(TestCancel signal) {
    var base = CallContext.none();
    return new CallContext(null, null, signal, base.engineContext(), base.provenance(), base.childLifetime());
  }

  private static final class TestCancel implements CallContext.CancelSignal {
    private boolean cancelled;
    private Runnable callback = () -> {};

    @Override
    public boolean isCancelled() { return cancelled; }

    @Override
    public void onCancel(Runnable handler) { callback = handler; }

    void cancel() {
      cancelled = true;
      callback.run();
    }
  }

  private static final class Fixture {
    private final WorkerIngestService service;
    private final AtomicReference<Consumer<IndexingJobChangeFeed.Delta>> subscriber = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private Runnable beforeReturn = () -> {};

    Fixture(int earlyDeltas) throws Exception {
      JobQueue queue = mock(JobQueue.class);
      IndexingJobChangeFeed feed = mock(IndexingJobChangeFeed.class);
      when(queue.indexingJobChangeFeed()).thenReturn(Optional.of(feed));
      when(feed.subscribeWithSnapshot(any())).thenAnswer(invocation -> {
        subscriber.set(invocation.getArgument(0));
        // The snapshot is already captured; a writer commits before the caller sees the return.
        var snapshot = new IndexingJobChangeFeed.SnapshotAndSubscription(
            1L, List.of(row("PENDING")), () -> closed.set(true));
        for (int i = 0; i < earlyDeltas; i++) emit("state-" + i);
        beforeReturn.run();
        return snapshot;
      });
      service = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
          null, null, null, null, null, 0L);
    }

    void emit(String state) {
      subscriber.get().accept(new IndexingJobChangeFeed.Delta.Update(row(state)));
    }

    void subscribe(Consumer<IndexingJobsFrame> sink) {
      subscribe(sink, CallContext.none());
    }

    void subscribe(Consumer<IndexingJobsFrame> sink, CallContext context) {
      service.subscribeIndexingJobs(SubscribeIndexingJobsRequest.newBuilder().build(), sink, context);
    }
  }

  private static IndexingJobChangeFeed.JobRow row(String state) {
    return new IndexingJobChangeFeed.JobRow("a".repeat(64), state, 0, 1L, null, 0L, "default", "scan");
  }
}
