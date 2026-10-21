/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.ipc.IndexingJobStatePresence;
import io.justsearch.ipc.IndexingJobsDelta;
import io.justsearch.ipc.IndexingJobsFrame;
import io.justsearch.ipc.IndexingJobsSnapshot;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class IndexingJobsSubscriptionFenceTest {
  @Test void retiredHigherSequenceFramesAndTerminalCallbacksCannotTouchTheReplacement() throws Exception {
    try (var fixture = new Fixture(20)) {
      var old = fixture.first();
      old.failed.accept(new StreamOverflowException("typed overflow"));
      var current = fixture.next();
      var before = fixture.bridge.latestSnapshotPair();
      int deliveries = fixture.deliveries.get();
      old.frames.accept(update(100, "retired", "PROCESSING"));
      assertEquals(before, fixture.bridge.latestSnapshotPair(), "generation ownership also fences higher old sequences");
      assertEquals(deliveries, fixture.deliveries.get(), "no retired delta reaches SSE or the ledger");
      old.completed.run();
      old.failed.accept(new StreamOverflowException("late old error"));
      current.frames.accept(update(21, "current", "FAILED"));
      assertEquals(List.of("current"), fixture.bridge.latestSnapshot().stream().map(row -> row.pathHash()).toList());
      assertEquals(0, current.closed.get(), "late terminal callbacks cannot close the replacement");
    }
  }

  @Test void olderActiveSnapshotsDeltasAndPresenceNeverMoveTheCursorOrReplayActivity() throws Exception {
    try (var fixture = new Fixture(20)) {
      var current = fixture.first();
      current.frames.accept(update(12, "running", "PROCESSING"));
      var before = fixture.bridge.latestSnapshotPair();
      int deliveries = fixture.deliveries.get();
      long signal = io.justsearch.core.status.StatusChangeCounter.process().current();
      current.frames.accept(snapshot(11));
      current.frames.accept(update(10, "running", "DONE"));
      assertEquals(before, fixture.bridge.latestSnapshotPair(), "ignore old frames before any mutation");
      assertEquals(deliveries, fixture.deliveries.get(), "no out-of-order terminal replay");
      assertEquals(signal, io.justsearch.core.status.StatusChangeCounter.process().current());
    }
  }

  @Test void restartingWorkerCursorKeepsTheHeadMonotonicAndAcceptsFollowingDeltas() throws Exception {
    try (var fixture = new Fixture(1)) {
      var old = fixture.first();
      old.frames.accept(update(11, "old", "PROCESSING"));
      old.failed.accept(new StreamOverflowException("overflow"));
      var current = fixture.next();
      assertTrue(fixture.bridge.latestSnapshot().isEmpty(), "the fresh snapshot replaces old running rows");
      assertEquals(11, fixture.bridge.latestSnapshotSeq(), "a worker-local cursor reset never moves the head backwards");
      current.frames.accept(update(2, "new", "PROCESSING"));
      assertEquals(12, fixture.bridge.latestSnapshotSeq());
      var before = fixture.bridge.latestSnapshotPair();
      current.frames.accept(update(1, "new", "DONE"));
      assertEquals(before, fixture.bridge.latestSnapshotPair());
    }
  }

  @Test void synchronousOverflowCannotInstallItsLateHandleOverTheNewSubscription() throws Exception {
    var second = new CountDownLatch(1);
    var calls = new AtomicInteger();
    var oldClosed = new AtomicInteger();
    var newClosed = new AtomicInteger();
    var replacementClosed = new CountDownLatch(1);
    try (var owners = new TestEngineExecutors()) {
      var bridge = new RemoteIndexingJobsBridge(owners, () -> (frames, failed, completed) -> {
        if (calls.incrementAndGet() == 1) {
          frames.accept(snapshot(10));
          failed.accept(new StreamOverflowException("synchronous overflow"));
          assertDoesNotThrow(() -> assertTrue(second.await(2, TimeUnit.SECONDS)));
          return oldClosed::incrementAndGet;
        }
        frames.accept(snapshot(20));
        second.countDown();
        return () -> { newClosed.incrementAndGet(); replacementClosed.countDown(); };
      });
      try {
        bridge.start().get(2, TimeUnit.SECONDS);
        assertEquals(1, oldClosed.get(), "the handle returned by a retired opening must be closed");
      } finally { bridge.stop(); }
      assertTrue(replacementClosed.await(2, TimeUnit.SECONDS));
      assertEquals(1, newClosed.get(), "stop must close the actual current handle");
      assertEquals(1, oldClosed.get());
    }
  }

  @Test void completedSubscriptionCannotDeliverAnyMoreFrames() throws Exception {
    try (var fixture = new Fixture(20)) {
      var current = fixture.first();
      var before = fixture.bridge.latestSnapshotPair();
      current.completed.run();
      current.frames.accept(update(100, "closed", "PROCESSING"));
      assertEquals(before, fixture.bridge.latestSnapshotPair());
      assertEquals(1, current.closed.get());
    }
  }

  private record Attempt(Consumer<IndexingJobsFrame> frames, Consumer<Throwable> failed,
      Runnable completed, AtomicInteger closed) {}

  private static final class Fixture implements AutoCloseable {
    final TestEngineExecutors owners = new TestEngineExecutors();
    final LinkedBlockingQueue<Attempt> attempts = new LinkedBlockingQueue<>();
    final AtomicInteger deliveries = new AtomicInteger();
    final AtomicInteger calls = new AtomicInteger();
    final RemoteIndexingJobsBridge bridge;
    Fixture(long reconnectSeq) {
      bridge = new RemoteIndexingJobsBridge(owners, () -> (frames, failed, completed) -> {
        var attempt = new Attempt(frames, failed, completed, new AtomicInteger());
        frames.accept(snapshot(calls.incrementAndGet() == 1 ? 10 : reconnectSeq));
        attempts.add(attempt);
        return attempt.closed::incrementAndGet;
      });
      bridge.subscribe(delta -> deliveries.incrementAndGet());
    }
    Attempt first() throws Exception { bridge.start().get(2, TimeUnit.SECONDS); return next(); }
    Attempt next() throws Exception {
      var attempt = attempts.poll(2, TimeUnit.SECONDS);
      assertNotNull(attempt, "subscription must open promptly");
      return attempt;
    }
    @Override public void close() { bridge.stop(); owners.close(); }
  }

  private static IndexingJobsFrame snapshot(long seq) {
    return IndexingJobsFrame.newBuilder().setSeq(seq).setSnapshot(IndexingJobsSnapshot.getDefaultInstance())
        .setStatePresence(IndexingJobStatePresence.getDefaultInstance()).build();
  }
  private static IndexingJobsFrame update(long seq, String hash, String state) {
    return IndexingJobsFrame.newBuilder().setSeq(seq).setDelta(IndexingJobsDelta.newBuilder().setUpdate(
        io.justsearch.ipc.IndexingJobView.newBuilder().setPathHash(hash).setState(state)))
        .setStatePresence(IndexingJobStatePresence.newBuilder().addStates(state)).build();
  }
}
