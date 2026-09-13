/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.ipc.IndexingJobView;
import io.justsearch.ipc.IndexingJobsDelta;
import io.justsearch.ipc.IndexingJobsFrame;
import io.justsearch.ipc.IndexingJobsSnapshot;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Contract tests for the indexing-jobs handoff's keyed coalescing policy. */
@Timeout(15)
final class IndexingJobsCoalescingTest {

  @Test
  void largeSinglePathBurstCoalescesIntoLatestInsert() throws Exception {
    var delivered = new CopyOnWriteArrayList<IndexingJobsFrame>();
    var deliveredOne = new CountDownLatch(1);
    var failure = new AtomicReference<Throwable>();
    var delivery = new DelayedDeliveryExecutor();
    var flow = EngineKnowledgeClient.indexingJobsHandoff(
        frame -> {
          delivered.add(frame);
          deliveredOne.countDown();
        }, failure::set, delivery);
    try {
      assertTrue(flow.publish(insert(1L, "same-path", "PENDING")));
      long lastSeq = 20_001L;
      for (long seq = 2L; seq <= lastSeq; seq++) {
        assertTrue(flow.publish(update(seq, "same-path", "DONE-" + seq)));
      }

      delivery.start();
      assertTrue(
          deliveredOne.await(2, TimeUnit.SECONDS), "the coalesced frame must be delivered");
      assertEquals(1, delivered.size(), "one pending path has one deliverable state");
      var frame = delivered.get(0);
      assertEquals(lastSeq, frame.getSeq());
      assertTrue(frame.getDelta().hasInsert(), "an unobserved insert must remain an insert");
      assertEquals("same-path", frame.getDelta().getInsert().getPathHash());
      assertEquals("DONE-" + lastSeq, frame.getDelta().getInsert().getState());
      assertNull(failure.get(), "the burst must not overflow the handoff");
    } finally {
      flow.close();
      delivery.close();
    }
  }

  @Test
  void mixedPathsKeepLatestReplacementOrderAndDeleteReinsertState() throws Exception {
    var delivered = new CopyOnWriteArrayList<IndexingJobsFrame>();
    var deliveredThree = new CountDownLatch(3);
    var delivery = new DelayedDeliveryExecutor();
    var flow = EngineKnowledgeClient.indexingJobsHandoff(
        frame -> {
          delivered.add(frame);
          deliveredThree.countDown();
        }, cause -> {}, delivery);
    try {
      assertTrue(flow.publish(insert(1L, "path-a", "PENDING")));
      assertTrue(flow.publish(insert(2L, "path-b", "PENDING")));
      assertTrue(flow.publish(update(3L, "path-a", "PROCESSING")));
      assertTrue(flow.publish(delete(4L, "path-b")));
      assertTrue(flow.publish(insert(5L, "path-b", "PENDING")));
      assertTrue(flow.publish(insert(6L, "path-c", "PENDING")));
      assertTrue(flow.publish(delete(7L, "path-c")));

      delivery.start();
      assertTrue(deliveredThree.await(2, TimeUnit.SECONDS));
      assertEquals(
          List.of(3L, 5L, 7L), delivered.stream().map(IndexingJobsFrame::getSeq).toList());

      var a = delivered.get(0);
      assertTrue(a.getDelta().hasInsert(), "insert plus update must expose the latest row as insert");
      assertEquals("path-a", a.getDelta().getInsert().getPathHash());
      assertEquals("PROCESSING", a.getDelta().getInsert().getState());

      var b = delivered.get(1);
      assertTrue(b.getDelta().hasInsert(), "delete followed by reinsert must expose the reinsert");
      assertEquals("path-b", b.getDelta().getInsert().getPathHash());
      assertEquals("PENDING", b.getDelta().getInsert().getState());

      var c = delivered.get(2);
      assertTrue(c.getDelta().hasDeletePathHash());
      assertEquals("path-c", c.getDelta().getDeletePathHash());
    } finally {
      flow.close();
      delivery.close();
    }
  }

  @Test
  void snapshotsAreOrderingBarriersAndDoNotMergeDeltasAcrossThem() throws Exception {
    var delivered = new CopyOnWriteArrayList<IndexingJobsFrame>();
    var deliveredFour = new CountDownLatch(4);
    var delivery = new DelayedDeliveryExecutor();
    var flow = EngineKnowledgeClient.indexingJobsHandoff(
        frame -> {
          delivered.add(frame);
          deliveredFour.countDown();
        }, cause -> {}, delivery);
    try {
      assertTrue(flow.publish(snapshot(1L)));
      assertTrue(flow.publish(insert(2L, "path-x", "PENDING")));
      assertTrue(flow.publish(update(3L, "path-x", "PROCESSING")));
      assertTrue(flow.publish(snapshot(4L, "path-x")));
      assertTrue(flow.publish(update(5L, "path-x", "DONE")));

      delivery.start();
      assertTrue(deliveredFour.await(2, TimeUnit.SECONDS));
      assertEquals(
          List.of(1L, 3L, 4L, 5L), delivered.stream().map(IndexingJobsFrame::getSeq).toList());
      assertTrue(delivered.get(0).hasSnapshot());
      assertTrue(delivered.get(1).getDelta().hasInsert());
      assertEquals("PROCESSING", delivered.get(1).getDelta().getInsert().getState());
      assertTrue(delivered.get(2).hasSnapshot());
      assertTrue(delivered.get(3).getDelta().hasUpdate());
      assertEquals("DONE", delivered.get(3).getDelta().getUpdate().getState());
    } finally {
      flow.close();
      delivery.close();
    }
  }

  @Test
  void uniquePendingFramesFailFastAtTheBound() {
    var failure = new AtomicReference<Throwable>();
    var delivery = new DelayedDeliveryExecutor();
    var flow = EngineKnowledgeClient.indexingJobsHandoff(frame -> {}, failure::set, delivery);
    try {
      for (int i = 0; i < BoundedHandoff.DEFAULT_CAPACITY; i++) {
        assertTrue(flow.publish(insert(i + 1L, "path-" + i, "PENDING")));
      }

      long started = System.nanoTime();
      assertFalse(flow.publish(insert(257L, "path-256", "PENDING")));
      long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      assertTrue(elapsedMs < 1_000L, "FAIL_FAST must refuse promptly; took " + elapsedMs + "ms");
      assertNotNull(failure.get(), "the refused frame must report a terminal error");
    } finally {
      flow.close();
      delivery.close();
    }
  }

  @Test
  void drainAndCloseCountsCoalescedItemsRatherThanReplacedFrames() {
    var delivered = new CopyOnWriteArrayList<IndexingJobsFrame>();
    var delivery = new DelayedDeliveryExecutor();
    var flow = EngineKnowledgeClient.indexingJobsHandoff(delivered::add, cause -> {}, delivery);
    try {
      assertTrue(flow.publish(insert(1L, "path-a", "PENDING")));
      assertTrue(flow.publish(update(2L, "path-a", "PROCESSING")));
      assertTrue(flow.publish(update(3L, "path-a", "DONE")));
      assertTrue(flow.publish(insert(4L, "path-b", "PENDING")));
      assertTrue(flow.publish(update(5L, "path-b", "DONE")));

      delivery.start();
      assertTrue(flow.drainAndClose(2_000L), "the coalesced tail must drain successfully");
      assertEquals(
          List.of(3L, 5L), delivered.stream().map(IndexingJobsFrame::getSeq).toList());
    } finally {
      flow.close();
      delivery.close();
    }
  }

  private static IndexingJobsFrame insert(long seq, String pathHash, String state) {
    return IndexingJobsFrame.newBuilder()
        .setSeq(seq)
        .setDelta(IndexingJobsDelta.newBuilder().setInsert(view(pathHash, state)).build())
        .build();
  }

  private static IndexingJobsFrame update(long seq, String pathHash, String state) {
    return IndexingJobsFrame.newBuilder()
        .setSeq(seq)
        .setDelta(IndexingJobsDelta.newBuilder().setUpdate(view(pathHash, state)).build())
        .build();
  }

  private static IndexingJobsFrame delete(long seq, String pathHash) {
    return IndexingJobsFrame.newBuilder()
        .setSeq(seq)
        .setDelta(IndexingJobsDelta.newBuilder().setDeletePathHash(pathHash).build())
        .build();
  }

  private static IndexingJobsFrame snapshot(long seq, String... pathHashes) {
    var snapshot = IndexingJobsSnapshot.newBuilder();
    for (String pathHash : pathHashes) {
      snapshot.addItems(view(pathHash, "PENDING"));
    }
    return IndexingJobsFrame.newBuilder().setSeq(seq).setSnapshot(snapshot.build()).build();
  }

  private static IndexingJobView view(String pathHash, String state) {
    return IndexingJobView.newBuilder().setPathHash(pathHash).setState(state).build();
  }

  private static final class DelayedDeliveryExecutor implements Executor, AutoCloseable {
    private final ExecutorService owner = Executors.newSingleThreadExecutor(runnable -> {
      var thread = new Thread(runnable, "indexing-jobs-coalescing-test");
      thread.setDaemon(true);
      return thread;
    });
    private final AtomicReference<Runnable> captured = new AtomicReference<>();

    @Override
    public void execute(Runnable command) {
      if (!captured.compareAndSet(null, command)) {
        throw new IllegalStateException("delivery runnable submitted more than once");
      }
    }

    void start() {
      var command = captured.get();
      if (command == null) {
        throw new IllegalStateException("delivery runnable was not captured");
      }
      owner.execute(command);
    }

    @Override
    public void close() {
      owner.shutdownNow();
    }
  }
}
