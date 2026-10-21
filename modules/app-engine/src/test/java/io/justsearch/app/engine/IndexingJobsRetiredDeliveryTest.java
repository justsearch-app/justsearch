/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.services.worker.RemoteIndexingJobsBridge;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.ipc.SubscribeIndexingJobsRequest;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(15)
class IndexingJobsRetiredDeliveryTest {
  @TempDir Path temp;

  @Test void retiredInFlightProcessingCannotOverwriteRecoveredStoreSnapshot() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var returned = new CountDownLatch(1);
    var reconnected = new CountDownLatch(1);
    var currentDone = new CountDownLatch(16);
    var attempts = new AtomicInteger();
    var flows = new CopyOnWriteArrayList<BoundedHandoff<?>>();
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"));
        var owners = new TestEngineExecutors();
        var delivery = Executors.newVirtualThreadPerTaskExecutor()) {
      queue.open();
      var path = temp.resolve("held.txt");
      queue.enqueue(List.of(path));
      var service = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
          null, null, null, null, null, 0L);
      var bridge = new RemoteIndexingJobsBridge(owners, () -> (frames, error, completed) -> {
        int attempt = attempts.incrementAndGet();
        var cancel = new FlowCancelSignal();
        var flow = EngineKnowledgeClient.indexingJobsHandoff(frame -> {
          boolean held = attempt == 1 && frame.hasDelta()
              && frame.getDelta().getUpdate().getState().equals("PROCESSING");
          if (held) {
            // The real handoff has passed its close check and entered its consumer. Hold it
            // immediately before the bridge callback, so another check in the drain is insufficient.
            entered.countDown();
            assertDoesNotThrow(() -> assertTrue(release.await(10, TimeUnit.SECONDS)));
          }
          try {
            frames.accept(frame);
            if (attempt > 1 && frame.hasSnapshot()) reconnected.countDown();
            if (attempt > 1 && frame.hasDelta()
                && frame.getDelta().getUpdate().getState().equals("DONE")) currentDone.countDown();
          } finally { if (held) returned.countDown(); }
        }, error, delivery);
        flows.add(flow);
        flow.onClose(cancel::cancel);
        var base = CallContext.none();
        service.subscribeIndexingJobs(SubscribeIndexingJobsRequest.getDefaultInstance(), flow::publish,
            new CallContext(null, null, cancel, base.engineContext(), base.provenance(), base.childLifetime()));
        return flow::close;
      });
      try {
        bridge.start().get(2, TimeUnit.SECONDS);
        assertEquals(1, queue.pollPending(1).size());
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        queue.markDone(path);
        queue.enqueue(java.util.stream.IntStream.range(0, 600)
            .mapToObj(i -> temp.resolve("overflow-" + i)).toList());
        assertTrue(reconnected.await(3, TimeUnit.SECONDS), "typed overflow must take a new snapshot");
        assertTrue(attempts.get() > 1);
        var claims = queue.pollPending(16);
        assertEquals(16, claims.size());
        for (var claim : claims) queue.markDone(claim.path());
        assertTrue(currentDone.await(3, TimeUnit.SECONDS));
        var store = queue.changeStream().subscribeWithSnapshot(change -> {});
        try { assertTrue(store.items().isEmpty(), "the store window has no processing or failures"); }
        finally { store.subscription().close(); }
        var recovered = bridge.latestSnapshotPair();
        assertTrue(recovered.items().isEmpty());
        long signal = io.justsearch.core.status.StatusChangeCounter.process().current();
        release.countDown();
        assertTrue(returned.await(2, TimeUnit.SECONDS));
        assertEquals(recovered, bridge.latestSnapshotPair(),
            "retired PROCESSING delivery must not resurrect a row or move the cursor backwards");
        assertEquals(signal, io.justsearch.core.status.StatusChangeCounter.process().current(),
            "retired presence must not invalidate current status");
      } finally {
        release.countDown();
        bridge.stop();
        flows.forEach(BoundedHandoff::close);
      }
    }
  }
}
