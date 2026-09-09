/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.IndexingJobChangeFeed;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.ipc.SubscribeIndexingJobsRequest;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class WorkerIndexingFeedCancellationTest {
  @Test
  void cancellationBeforeDuringOrAfterInstallationClosesExactlyOneSubscription() throws Exception {
    for (String phase : List.of("before", "during", "after")) {
      var closed = new AtomicInteger();
      var cancelled = new AtomicBoolean(phase.equals("before"));
      var handler = new AtomicReference<Runnable>();
      Runnable cancel = () -> {
        cancelled.set(true);
        var callback = handler.getAndSet(null);
        if (callback != null) callback.run();
      };
      var signal = new CallContext.CancelSignal() {
        @Override public boolean isCancelled() { return cancelled.get(); }
        @Override public void onCancel(Runnable callback) {
          handler.set(callback);
          if (cancelled.get()) cancel.run();
        }
      };
      var feed = mock(IndexingJobChangeFeed.class);
      when(feed.subscribeWithSnapshot(any())).thenAnswer(call -> {
        if (phase.equals("during")) cancel.run();
        return new IndexingJobChangeFeed.SnapshotAndSubscription(0, List.of(), closed::incrementAndGet);
      });
      var none = CallContext.none();
      var context = new CallContext(null, null, signal, none.engineContext(), none.provenance());
      var delivered = new AtomicInteger();
      service(feed).subscribeIndexingJobs(SubscribeIndexingJobsRequest.getDefaultInstance(),
          frame -> delivered.incrementAndGet(), context);
      if (phase.equals("after")) {
        assertEquals(0, closed.get());
        assertEquals(1, delivered.get());
        cancel.run();
      } else assertEquals(0, delivered.get(), "a cancelled subscription must not emit its snapshot");
      cancel.run();
      assertEquals(1, closed.get(), phase);
    }
  }

  @Test
  void aSnapshotConsumerFailureCannotLeaveItsSubscriptionAlive() throws Exception {
    var closed = new AtomicInteger();
    var feed = mock(IndexingJobChangeFeed.class);
    when(feed.subscribeWithSnapshot(any())).thenReturn(
        new IndexingJobChangeFeed.SnapshotAndSubscription(0, List.of(), closed::incrementAndGet));
    assertThrows(IllegalStateException.class, () -> service(feed).subscribeIndexingJobs(
        SubscribeIndexingJobsRequest.getDefaultInstance(), frame -> {
          throw new IllegalStateException("consumer failed");
        }, CallContext.none()));
    assertEquals(1, closed.get());
  }

  private static WorkerIngestService service(IndexingJobChangeFeed feed) {
    var queue = mock(JobQueue.class);
    when(queue.indexingJobChangeFeed()).thenReturn(Optional.of(feed));
    return new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
        null, null, null, null, null, 0L);
  }
}
