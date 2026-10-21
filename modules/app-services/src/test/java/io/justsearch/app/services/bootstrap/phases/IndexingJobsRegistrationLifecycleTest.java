/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.observability.indexing.IndexingJobsChangeRegistry;
import io.justsearch.app.observability.ledger.ActionEventJournal;
import io.justsearch.app.observability.ledger.ActionLedgerChangeRegistry;
import io.justsearch.app.observability.stream.SseStreamChannel;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.ipc.IndexingJobsFrame;
import io.justsearch.ipc.IndexingJobsSnapshot;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Registration may be delayed or cancelled; activation and shutdown must stay runnable. */
@Timeout(15)
final class IndexingJobsRegistrationLifecycleTest {
  @Test
  void activationReturnsWhileRegistrationIsPendingAndPreservesTheBarrier() throws Exception {
    try (var fixture = new Fixture()) {
      var ready = fixture.activate();
      assertFalse(ready.isDone(), "an unacknowledged registration is not ready");
      assertSame(ready, fixture.wired.bridge().start(), "repeated starts share the pending acknowledgement");
      assertEquals(0, fixture.rowStarts.get(), "rows cannot precede terminal registration");
      fixture.acknowledge();
      ready.get(2, TimeUnit.SECONDS);
      assertEquals(1, fixture.rowStarts.get());
    }
  }

  @Test
  void stopBeforeAcknowledgementReleasesReadinessAndPreventsALateRowSubscription() throws Exception {
    try (var fixture = new Fixture()) {
      var ready = fixture.activate();
      fixture.wired.bridge().stop();
      fixture.acknowledge();
      assertTrue(ready.isCompletedExceptionally(), "stopping cannot strand startup callers");
      assertEquals(0, fixture.rowStarts.get(), "a closed pending subscription must never open later");
    }
  }

  @Test
  void registrationFailureFailsReadinessWithoutOpeningRows() throws Exception {
    try (var fixture = new Fixture()) {
      var ready = fixture.activate();
      var failure = new IllegalStateException("registration refused");
      fixture.outcomeError.get().accept(failure);
      var reported = assertThrows(ExecutionException.class, () -> ready.get(2, TimeUnit.SECONDS));
      assertSame(failure, reported.getCause());
      assertEquals(0, fixture.rowStarts.get());
    }
  }

  @Test
  void producerCompletionBeforeAcknowledgementFailsReadinessWithoutOpeningRows() throws Exception {
    try (var fixture = new Fixture()) {
      var ready = fixture.activate();
      fixture.outcomeCompleted.get().run();
      assertThrows(ExecutionException.class, () -> ready.get(2, TimeUnit.SECONDS));
      assertEquals(0, fixture.rowStarts.get());
    }
  }

  @Test
  void cancellationClosesARowHandleThatReturnsAfterStop() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.holdRowReturn = true;
      fixture.activate();
      var acknowledged = CompletableFuture.runAsync(fixture::acknowledge, fixture.activation);
      assertTrue(fixture.rowEntered.await(2, TimeUnit.SECONDS));
      fixture.wired.bridge().stop();
      fixture.rowRelease.countDown();
      acknowledged.get(2, TimeUnit.SECONDS);
      assertEquals(1, fixture.rowCloses.get(), "the late handle belongs to the cancelled subscription");
    }
  }

  private static final class Fixture implements AutoCloseable {
    final TestEngineExecutors owners = new TestEngineExecutors();
    final ExecutorService activation = Executors.newSingleThreadExecutor(task ->
        Thread.ofPlatform().daemon().name("registration-lifecycle-test").unstarted(task));
    final AtomicReference<Consumer<IndexingJobsFrame>> outcomeFrames = new AtomicReference<>();
    final AtomicReference<Consumer<Throwable>> outcomeError = new AtomicReference<>();
    final AtomicReference<Runnable> outcomeCompleted = new AtomicReference<>();
    final AtomicInteger rowStarts = new AtomicInteger();
    final AtomicInteger rowCloses = new AtomicInteger();
    final CountDownLatch rowEntered = new CountDownLatch(1);
    final CountDownLatch rowRelease = new CountDownLatch(1);
    final IndexingJobsChangeRegistry rows;
    final ActionLedgerChangeRegistry ledger;
    final IndexingJobsBridgeWiring.Output wired;
    volatile boolean holdRowReturn;

    Fixture() {
      var client = org.mockito.Mockito.mock(KnowledgeClient.class);
      org.mockito.Mockito.doAnswer(call -> {
        outcomeFrames.set(call.getArgument(0));
        outcomeError.set(call.getArgument(1));
        outcomeCompleted.set(call.getArgument(2));
        return (KnowledgeClient.IndexingJobsStream) () -> {};
      }).when(client).subscribeIndexingJobOutcomes(org.mockito.ArgumentMatchers.any(),
          org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
      org.mockito.Mockito.doAnswer(call -> {
        rowStarts.incrementAndGet();
        call.<Consumer<IndexingJobsFrame>>getArgument(0).accept(snapshot());
        rowEntered.countDown();
        if (holdRowReturn) assertTrue(rowRelease.await(2, TimeUnit.SECONDS));
        return (KnowledgeClient.IndexingJobsStream) rowCloses::incrementAndGet;
      }).when(client).subscribeIndexingJobs(org.mockito.ArgumentMatchers.any(),
          org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
      var rowOwner = owners.register(EngineExecutorSpec.virtual("test.registration.rows",
          EngineExecutorSpec.Kind.BACKGROUND, 1));
      rows = new IndexingJobsChangeRegistry(new SseStreamChannel(IndexingJobsChangeRegistry.STREAM_ID,
          rowOwner.openVirtual(), rowOwner::close));
      var ledgerOwner = owners.register(EngineExecutorSpec.virtual("test.registration.ledger",
          EngineExecutorSpec.Kind.BACKGROUND, 1));
      ledger = new ActionLedgerChangeRegistry(ActionEventJournal.disabled(), new SseStreamChannel(
          ActionLedgerChangeRegistry.STREAM_ID, ledgerOwner.openVirtual(), ledgerOwner::close));
      wired = IndexingJobsBridgeWiring.wire(owners, () -> client, rows, ledger);
    }

    CompletableFuture<Void> activate() {
      var returned = CompletableFuture.supplyAsync(wired.bridge()::start, activation);
      return assertDoesNotThrow(() -> returned.get(2, TimeUnit.SECONDS),
          "activation must return without waiting for registration acknowledgement");
    }

    void acknowledge() {
      var sink = outcomeFrames.get();
      if (sink != null) sink.accept(snapshot());
    }

    private static IndexingJobsFrame snapshot() {
      return IndexingJobsFrame.newBuilder().setSeq(1)
          .setSnapshot(IndexingJobsSnapshot.getDefaultInstance()).build();
    }

    @Override
    public void close() throws Exception {
      try {
        rowRelease.countDown();
        // Also releases the old implementation's join after a fail-before assertion.
        acknowledge();
        wired.subscription().close();
        wired.bridge().stop();
      } finally {
        activation.shutdownNow();
        try {
          assertTrue(activation.awaitTermination(2, TimeUnit.SECONDS));
        } finally {
          try { rows.channel().close(); }
          finally {
            try { ledger.channel().close(); }
            finally { owners.close(); }
          }
        }
      }
    }
  }
}
