/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.services.worker.RemoteIndexingJobsBridge;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.ipc.IndexingJobsFrame;
import io.justsearch.ipc.SubscribeIndexingJobsRequest;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(10)
final class IndexingJobsWindowFlowTest {
  @TempDir Path temp;

  @Test void writesDuringSnapshotOverflowReconnectAndReloadConverge() throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db")); var executors = new TestEngineExecutors()) {
      queue.open();
      var service = service(queue);
      var first = new AtomicBoolean(true);
      io.justsearch.app.services.worker.IndexingJobsSource source = (frames, error, completed) -> {
        var cancel = new FlowCancelSignal();
        service.subscribeIndexingJobs(SubscribeIndexingJobsRequest.getDefaultInstance(), frame -> {
          frames.accept(frame);
          if (frame.hasSnapshot() && first.compareAndSet(true, false)) {
            queue.enqueue(java.util.stream.IntStream.range(0, 600).mapToObj(i -> temp.resolve("job-" + i)).toList());
            try {
              var field = SqliteJobQueue.class.getDeclaredField("connection"); field.setAccessible(true);
              try (var stmt = ((Connection) field.get(queue)).createStatement()) {
                stmt.executeUpdate("UPDATE jobs SET state='FAILED'");
              }
            } catch (Exception failure) { throw new AssertionError(failure); }
          }
        }, context(cancel));
        return cancel::cancel;
      };
      var bridge = new RemoteIndexingJobsBridge(executors, () -> source);
      try {
        bridge.start().get(2, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (bridge.latestSnapshot().size() != 200 && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(200, bridge.latestSnapshot().size(), "overflow must recover by taking a fresh bounded snapshot");
        var fresh = new RemoteIndexingJobsBridge(executors, () -> source);
        try {
          fresh.start().get(2, TimeUnit.SECONDS);
          assertEquals(bridge.latestSnapshot(), fresh.latestSnapshot(), "reload converges after >256 initial deltas");
          queue.clearAll();
          assertTrue(bridge.latestSnapshot().isEmpty());
          assertTrue(fresh.latestSnapshot().isEmpty(), "unknown deletes leave no phantom jobs");
        } finally { fresh.stop(); }
      } finally { bridge.stop(); }
    }
  }

  @Test void stalledDeliveryFailsAtBoundWithoutBlockingClaimsAndReconnectRecovers() throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("stalled.db"))) {
      queue.open();
      var service = service(queue);
      var failure = new AtomicReference<Throwable>();
      var scheduled = new AtomicReference<Runnable>();
      var cancel = new FlowCancelSignal();
      var flow = EngineKnowledgeClient.indexingJobsHandoff(frame -> fail("delivery is stalled"), failure::set,
          task -> assertTrue(scheduled.compareAndSet(null, task)));
      flow.onClose(cancel::cancel);
      try {
        service.subscribeIndexingJobs(SubscribeIndexingJobsRequest.getDefaultInstance(), flow::publish, context(cancel));
        queue.enqueue(java.util.stream.IntStream.range(0, 600).mapToObj(i -> temp.resolve("pending-" + i)).toList());
        assertNotNull(failure.get(), "distinct-path overflow fails the stalled flow");
        assertTrue(cancel.isCancelled(), "overflow detaches the SQLite subscriber");
        assertFalse(flow.publish(IndexingJobsFrame.getDefaultInstance()));
        try (var writer = java.util.concurrent.Executors.newSingleThreadExecutor()) {
          var claimed = writer.submit(() -> queue.pollPending(16)).get(2, TimeUnit.SECONDS);
          assertEquals(16, claimed.size(), "stalled delivery must not hold the queue lock");
          var recovered = new ArrayList<IndexingJobsFrame>();
          var reconnectCancel = new FlowCancelSignal();
          try {
            service.subscribeIndexingJobs(SubscribeIndexingJobsRequest.getDefaultInstance(), recovered::add, context(reconnectCancel));
            assertEquals(16, recovered.getFirst().getSnapshot().getItemsList().stream()
                .filter(row -> row.getState().equals("PROCESSING")).count(), "reconnect includes every active claim");
            for (var claim : claimed) queue.markDone(claim.path());
            assertTrue(recovered.stream().filter(IndexingJobsFrame::hasDelta)
                .anyMatch(frame -> frame.getDelta().getUpdate().getState().equals("DONE")));
          } finally { reconnectCancel.cancel(); }
        }
      } finally { flow.close(); cancel.cancel(); }
    }
  }

  @Test void realWorkerCarriesPresenceOnSnapshotsAndEveryStateChangingCommit() throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("presence.db")); var executors = new TestEngineExecutors()) {
      queue.open();
      var worker = service(queue);
      var frames = new ArrayList<IndexingJobsFrame>();
      var bridge = new RemoteIndexingJobsBridge(executors, () -> (onFrame, onError, completed) -> {
        var cancel = new FlowCancelSignal();
        worker.subscribeIndexingJobs(SubscribeIndexingJobsRequest.getDefaultInstance(), frame -> {
          frames.add(frame);
          onFrame.accept(frame);
        }, context(cancel));
        return cancel::cancel;
      });
      try {
        bridge.start().get(2, TimeUnit.SECONDS);
        assertTrue(frames.getFirst().hasSnapshot());
        assertPresence(frames.getFirst(), java.util.Set.of());
        long counter = io.justsearch.core.status.StatusChangeCounter.process().current();
        queue.enqueue(List.of(temp.resolve("a")));
        assertPresence(frames.getLast(), java.util.Set.of("PENDING"));
        assertEquals(++counter, io.justsearch.core.status.StatusChangeCounter.process().current());
        assertTrue(bridge.latestSnapshot().isEmpty(), "outside-window state still reaches the head");
        queue.enqueue(List.of(temp.resolve("b")));
        assertFalse(frames.getLast().hasStatePresence(), "count-only commit omits unchanged presence");
        assertEquals(counter, io.justsearch.core.status.StatusChangeCounter.process().current());
        var claimed = queue.pollPending(1);
        assertEquals(1, claimed.size());
        assertPresence(frames.getLast(), java.util.Set.of("PENDING", "PROCESSING"));
        assertEquals(++counter, io.justsearch.core.status.StatusChangeCounter.process().current());
        queue.markDone(claimed.getFirst().path());
        assertPresence(frames.getLast(), java.util.Set.of("PENDING", "DONE"));
        assertEquals(++counter, io.justsearch.core.status.StatusChangeCounter.process().current());
        var reconnected = new ArrayList<IndexingJobsFrame>();
        var reconnectCancel = new FlowCancelSignal();
        try {
          worker.subscribeIndexingJobs(SubscribeIndexingJobsRequest.getDefaultInstance(), reconnected::add, context(reconnectCancel));
          assertTrue(reconnected.getFirst().hasSnapshot());
          assertTrue(reconnected.getFirst().getSnapshot().getItemsList().isEmpty());
          assertPresence(reconnected.getFirst(), java.util.Set.of("PENDING", "DONE"));
          assertEquals(counter, io.justsearch.core.status.StatusChangeCounter.process().current());
        } finally { reconnectCancel.cancel(); }
        queue.clearAll();
        assertTrue(frames.getLast().hasSnapshot(), "unknown deletes resynchronize the empty window");
        assertPresence(frames.getLast(), java.util.Set.of());
        assertEquals(++counter, io.justsearch.core.status.StatusChangeCounter.process().current());
      } finally { bridge.stop(); }
    }
  }

  private static void assertPresence(IndexingJobsFrame frame, java.util.Set<String> expected) {
    assertTrue(frame.hasStatePresence(), "real worker frame must carry all-queue state presence");
    assertEquals(expected, java.util.Set.copyOf(frame.getStatePresence().getStatesList()));
  }

  private static WorkerIngestService service(SqliteJobQueue queue) {
    return new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(), null, null, null, null, null, 0L);
  }

  private static CallContext context(FlowCancelSignal cancel) {
    var base = CallContext.none();
    return new CallContext(null, null, cancel, base.engineContext(), base.provenance(), base.childLifetime());
  }
}
