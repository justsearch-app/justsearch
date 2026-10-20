/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.justsearch.app.observability.indexing.IndexingJobsChangeRegistry;
import io.justsearch.app.observability.ledger.ActionLedgerChangeRegistry;
import io.justsearch.app.observability.stream.SseStreamChannel;
import io.justsearch.app.services.bootstrap.phases.IndexingJobsBridgeWiring;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.StreamOverflowException;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.ipc.IndexingJobsDelta;
import io.justsearch.ipc.IndexingJobsFrame;
import io.justsearch.ipc.IndexingJobsSnapshot;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class IndexingJobsSseIsolationTest {
  @Test void stalledSseObserverCannotOverflowTheHeadHandoffDuringTwoThousandRowBurst() throws Exception {
    try (var owners = new TestEngineExecutors()) {
      var delivery = owners.register(EngineExecutorSpec.virtual("test.sse", EngineExecutorSpec.Kind.BACKGROUND, 1));
      var executor = delivery.openVirtual();
      var jobs = new IndexingJobsChangeRegistry(new SseStreamChannel(IndexingJobsChangeRegistry.STREAM_ID, executor));
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var observerThread = new AtomicReference<Thread>();
      var drainThread = new AtomicReference<Thread>();
      var error = new AtomicReference<Throwable>();
      var flow = new AtomicReference<BoundedHandoff<IndexingJobsFrame>>();
      var client = mock(KnowledgeClient.class);
      doAnswer(call -> {
        java.util.function.Consumer<IndexingJobsFrame> onFrame = call.getArgument(0);
        java.util.function.Consumer<Throwable> onError = call.getArgument(1);
        var handoff = EngineKnowledgeClient.indexingJobsHandoff(frame -> {
          drainThread.set(Thread.currentThread()); onFrame.accept(frame);
        }, failure -> { error.set(failure); onError.accept(failure); }, executor);
        flow.set(handoff);
        handoff.publish(IndexingJobsFrame.newBuilder().setSeq(1)
            .setSnapshot(IndexingJobsSnapshot.getDefaultInstance()).build());
        return (KnowledgeClient.IndexingJobsStream) handoff::close;
      }).when(client).subscribeIndexingJobs(any(), any(), any(), any());
      doAnswer(call -> {
        java.util.function.Consumer<IndexingJobsFrame> onFrame = call.getArgument(0);
        onFrame.accept(IndexingJobsFrame.newBuilder().setSeq(1)
            .setSnapshot(IndexingJobsSnapshot.getDefaultInstance()).build());
        return (KnowledgeClient.IndexingJobsStream) () -> {};
      }).when(client).subscribeIndexingJobOutcomes(any(), any(), any(), any());
      // A single owned executor instance is enough; handoff shares its virtual owner above.
      var wired = IndexingJobsBridgeWiring.wire(owners, () -> client, jobs, new ActionLedgerChangeRegistry());
      wired.bridge().start().get(1, TimeUnit.SECONDS);
      var subscription = jobs.subscribe(envelope -> {
        observerThread.set(Thread.currentThread()); entered.countDown();
        boolean interrupted = false;
        while (true) {
          try { release.await(); break; }
          catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
      });
      try {
        flow.get().publish(IndexingJobsFrame.newBuilder().setSeq(2)
            .setDelta(IndexingJobsDelta.newBuilder().setInsert(io.justsearch.ipc.IndexingJobView.newBuilder()
                .setPathHash("first").setState("PENDING"))).build());
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        for (int i = 0; i < 2000; i++) {
          flow.get().publish(IndexingJobsFrame.newBuilder().setSeq(i + 3)
              .setDelta(IndexingJobsDelta.newBuilder().setInsert(io.justsearch.ipc.IndexingJobView.newBuilder()
                  .setPathHash(String.format("%064x", i)).setState("PENDING"))).build());
          if (i % 8 == 0) Thread.sleep(1);
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (wired.bridge().latestSnapshotSeq() < 2002 && System.nanoTime() < deadline) Thread.sleep(1);
        assertNull(error.get(), "a stalled SSE observer must never overflow the worker-to-head handoff");
        assertEquals(2002, wired.bridge().latestSnapshotSeq());
        assertNotSame(drainThread.get(), observerThread.get(), "serialization/sending cannot run on the drain thread");
      } finally {
        release.countDown(); subscription.unsubscribe(); wired.subscription().close(); wired.bridge().stop(); jobs.channel().close();
      }
    }
  }

  @Test void failFastOverflowHasATypedCause() {
    var error = new AtomicReference<Throwable>();
    try (var flow = EngineKnowledgeClient.indexingJobsHandoff(frame -> {}, error::set, ignored -> {})) {
      for (int i = 0; i < 257; i++) flow.publish(IndexingJobsFrame.newBuilder().setSeq(i)
          .setSnapshot(IndexingJobsSnapshot.getDefaultInstance()).build());
      assertInstanceOf(StreamOverflowException.class, error.get());
    }
  }
}
