package io.justsearch.app.services.bootstrap.phases;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.indexing.IndexingJobView;
import io.justsearch.app.observability.ledger.ActionEvent;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 550 thesis I — the indexing-jobs bridge's terminal-outcome translator. The decision that
 * matters (which job transitions become a {@code kind=index} action-event) is the pure
 * {@link IndexingJobsBridgeWiring#terminalIndexEvent} function: TERMINAL states (DONE/FAILED) emit
 * exactly one Index event; in-flight states (PENDING/PROCESSING) emit none — that live state is the
 * rail's Resource projection, and per-transition emission would flood the bounded action-event ring.
 */
@DisplayName("IndexingJobsBridgeWiring.terminalIndexEvent")
final class IndexingJobsBridgeWiringTest {

  private static IndexingJobView job(String pathHash, String state, int attempts, String err) {
    return job(pathHash, state, attempts, err, "");
  }

  private static IndexingJobView job(
      String pathHash, String state, int attempts, String err, String scanId) {
    // IndexingJobView(pathHash, state, attempts, lastUpdatedMs, errorMessage, retryAfterMs,
    // collection, scanId)
    return new IndexingJobView(
        pathHash, state, attempts, 1_700_000_000_000L, err, 0L, "default", scanId);
  }

  @Test void liveTerminalOutcomesOutsideWindowKeepScanAttributionWithoutSnapshotReplay() throws Exception {
    try (var executors = new io.justsearch.core.execution.TestEngineExecutors()) {
      var client = org.mockito.Mockito.mock(io.justsearch.app.services.worker.KnowledgeClient.class);
      var sink = new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<io.justsearch.ipc.IndexingJobsFrame>>();
      var sinks = new java.util.concurrent.CopyOnWriteArrayList<java.util.function.Consumer<io.justsearch.ipc.IndexingJobsFrame>>();
      sink.set(frame -> sinks.forEach(consumer -> consumer.accept(frame)));
      org.mockito.stubbing.Answer<io.justsearch.app.services.worker.KnowledgeClient.IndexingJobsStream> stream = call -> {
        java.util.function.Consumer<io.justsearch.ipc.IndexingJobsFrame> consumer = call.getArgument(0);
        sinks.add(consumer);
        consumer.accept(io.justsearch.ipc.IndexingJobsFrame.newBuilder().setSeq(1)
            .setSnapshot(io.justsearch.ipc.IndexingJobsSnapshot.getDefaultInstance()).build());
        return () -> sinks.remove(consumer);
      };
      org.mockito.Mockito.doAnswer(stream).when(client).subscribeIndexingJobOutcomes(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
          org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
      org.mockito.Mockito.doAnswer(stream).when(client).subscribeIndexingJobs(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
          org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
      var ledger = new io.justsearch.app.observability.ledger.ActionLedgerChangeRegistry(
          io.justsearch.app.observability.ledger.ActionEventJournal.disabled(),
          new io.justsearch.app.observability.stream.SseStreamChannel(
              io.justsearch.app.observability.ledger.ActionLedgerChangeRegistry.STREAM_ID,
              executors.register(io.justsearch.core.execution.EngineExecutorSpec.virtual("test.ledger-delivery",
                  io.justsearch.core.execution.EngineExecutorSpec.Kind.BACKGROUND, 1)).openVirtual()));
      var wired = IndexingJobsBridgeWiring.wire(executors, () -> client,
          new io.justsearch.app.observability.indexing.IndexingJobsChangeRegistry(
              new io.justsearch.app.observability.stream.SseStreamChannel(
                  io.justsearch.app.observability.indexing.IndexingJobsChangeRegistry.STREAM_ID,
                  executors.register(io.justsearch.core.execution.EngineExecutorSpec.virtual("test.jobs-delivery",
                      io.justsearch.core.execution.EngineExecutorSpec.Kind.BACKGROUND, 1)).openVirtual())), ledger);
      try {
        wired.bridge().start().get(2, java.util.concurrent.TimeUnit.SECONDS);
        long seq = 2;
        for (String state : java.util.List.of("DONE", "PENDING", "FAILED", "PENDING", "RETRY_EXHAUSTED", "DONE")) {
          var row = io.justsearch.ipc.IndexingJobView.newBuilder().setPathHash("a".repeat(64))
              .setState(state).setLastUpdatedMs(seq).setCollection("default").setScanId("scan-42");
          sink.get().accept(io.justsearch.ipc.IndexingJobsFrame.newBuilder().setSeq(seq++)
              .setDelta(io.justsearch.ipc.IndexingJobsDelta.newBuilder().setUpdate(row)).build());
        }
        assertEquals(4, ledger.store().recent().size(), "later outcomes after retry are still recorded");
        assertTrue(ledger.store().recent().stream().allMatch(event -> ((ActionEvent.Index) event).scanId().equals("scan-42")));
        sink.get().accept(io.justsearch.ipc.IndexingJobsFrame.newBuilder().setSeq(seq)
            .setSnapshot(io.justsearch.ipc.IndexingJobsSnapshot.newBuilder().addItems(
                io.justsearch.ipc.IndexingJobView.newBuilder().setPathHash("historic").setState("FAILED"))).build());
        assertEquals(4, ledger.store().recent().size(), "reconnect snapshots never replay ledger outcomes");
      } finally { wired.subscription().close(); wired.bridge().stop(); }
    }
  }

  @Test
  @DisplayName("DONE emits one index event (originator=system) with the terminal state")
  void doneEmits() {
    Optional<ActionEvent> evt = IndexingJobsBridgeWiring.terminalIndexEvent(job("h1", "DONE", 0, ""));
    assertTrue(evt.isPresent());
    ActionEvent.Index idx = (ActionEvent.Index) evt.get();
    assertEquals("system", idx.originator());
    assertEquals("WORKER_INDEXER", idx.transport());
    assertEquals("h1", idx.pathHash());
    assertEquals("DONE", idx.state());
  }

  @Test
  @DisplayName("FAILED emits one index event carrying the failure detail")
  void failedEmits() {
    Optional<ActionEvent> evt =
        IndexingJobsBridgeWiring.terminalIndexEvent(job("h2", "FAILED", 3, "boom"));
    assertTrue(evt.isPresent());
    ActionEvent.Index idx = (ActionEvent.Index) evt.get();
    assertEquals("FAILED", idx.state());
    assertEquals(3, idx.attempts());
    assertEquals("boom", idx.errorMessage());
  }

  @Test
  @DisplayName("PENDING and PROCESSING (in-flight) emit NO ledger event")
  void inFlightEmitsNothing() {
    assertTrue(IndexingJobsBridgeWiring.terminalIndexEvent(job("h3", "PENDING", 0, "")).isEmpty());
    assertTrue(
        IndexingJobsBridgeWiring.terminalIndexEvent(job("h4", "PROCESSING", 0, "")).isEmpty());
  }

  @Test
  @DisplayName("tempdoc 812 D2 — the scan key rides the terminal event; absent stays empty")
  void carriesScanId() {
    ActionEvent.Index tagged =
        (ActionEvent.Index)
            IndexingJobsBridgeWiring.terminalIndexEvent(job("h7", "DONE", 0, "", "scan-42")).get();
    assertEquals("scan-42", tagged.scanId());
    ActionEvent.Index keyless =
        (ActionEvent.Index) IndexingJobsBridgeWiring.terminalIndexEvent(job("h8", "DONE", 0, "")).get();
    assertEquals("", keyless.scanId());
  }

  @Test
  @DisplayName("state is matched case-insensitively; null/unknown rows are safely ignored")
  void caseInsensitiveAndDefensive() {
    assertTrue(IndexingJobsBridgeWiring.terminalIndexEvent(job("h5", "done", 0, "")).isPresent());
    assertTrue(IndexingJobsBridgeWiring.terminalIndexEvent(job("h6", "queued", 0, "")).isEmpty());
    assertTrue(IndexingJobsBridgeWiring.terminalIndexEvent(null).isEmpty());
  }
  @Test void terminalLedgerObserversCannotBlockJobDeliveryAndSnapshotsNeverReplayThem() throws Exception {
    try (var executors = new io.justsearch.core.execution.TestEngineExecutors()) {
      var client = org.mockito.Mockito.mock(io.justsearch.app.services.worker.KnowledgeClient.class);
      var sink = new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<io.justsearch.ipc.IndexingJobsFrame>>();
      var sinks = new java.util.concurrent.CopyOnWriteArrayList<java.util.function.Consumer<io.justsearch.ipc.IndexingJobsFrame>>();
      sink.set(frame -> sinks.forEach(consumer -> consumer.accept(frame)));
      org.mockito.stubbing.Answer<io.justsearch.app.services.worker.KnowledgeClient.IndexingJobsStream> stream = call -> {
        java.util.function.Consumer<io.justsearch.ipc.IndexingJobsFrame> consumer = call.getArgument(0);
        sinks.add(consumer);
        consumer.accept(io.justsearch.ipc.IndexingJobsFrame.newBuilder().setSeq(1)
            .setSnapshot(io.justsearch.ipc.IndexingJobsSnapshot.getDefaultInstance()).build());
        return () -> sinks.remove(consumer);
      };
      org.mockito.Mockito.doAnswer(stream).when(client).subscribeIndexingJobOutcomes(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
          org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
      org.mockito.Mockito.doAnswer(stream).when(client).subscribeIndexingJobs(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
          org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
      var ledger = new io.justsearch.app.observability.ledger.ActionLedgerChangeRegistry(
          io.justsearch.app.observability.ledger.ActionEventJournal.disabled(), testChannel(executors,
              io.justsearch.app.observability.ledger.ActionLedgerChangeRegistry.STREAM_ID, "test.ledger"));
      var jobs = new io.justsearch.app.observability.indexing.IndexingJobsChangeRegistry(testChannel(executors,
          io.justsearch.app.observability.indexing.IndexingJobsChangeRegistry.STREAM_ID, "test.jobs"));
      var wired = IndexingJobsBridgeWiring.wire(executors, () -> client, jobs, ledger);
      var entered = new java.util.concurrent.CountDownLatch(1);
      var release = new java.util.concurrent.CountDownLatch(1);
      var subscriber = ledger.subscribe(frame -> {
        entered.countDown();
        try { release.await(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
      });
      try {
        wired.bridge().start().get(1, java.util.concurrent.TimeUnit.SECONDS);
        for (int i = 2; i <= 3; i++) {
          int seq = i;
          var row = io.justsearch.ipc.IndexingJobView.newBuilder().setPathHash("terminal-" + i)
              .setState("DONE").setLastUpdatedMs(i).setScanId("scan-outside-window");
          var frame = io.justsearch.ipc.IndexingJobsFrame.newBuilder().setSeq(i)
              .setDelta(io.justsearch.ipc.IndexingJobsDelta.newBuilder().setUpdate(row)).build();
          assertDoesNotThrow(() -> java.util.concurrent.CompletableFuture.runAsync(() -> sink.get().accept(frame),
              executors.register(io.justsearch.core.execution.EngineExecutorSpec.virtual("test.commit-" + seq,
                  io.justsearch.core.execution.EngineExecutorSpec.Kind.BACKGROUND, 1)).openVirtual())
              .get(250, java.util.concurrent.TimeUnit.MILLISECONDS));
          assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(2, ledger.store().recent().size());
        assertTrue(ledger.store().recent().stream().allMatch(event -> ((ActionEvent.Index) event).scanId().equals("scan-outside-window")));
        sink.get().accept(io.justsearch.ipc.IndexingJobsFrame.newBuilder().setSeq(4)
            .setSnapshot(io.justsearch.ipc.IndexingJobsSnapshot.getDefaultInstance()).build());
        assertEquals(2, ledger.store().recent().size(), "snapshots never replay terminal activity");
      } finally {
        release.countDown(); subscriber.unsubscribe(); wired.subscription().close(); wired.bridge().stop();
        jobs.channel().close(); ledger.channel().close();
      }
    }
  }

  private static io.justsearch.app.observability.stream.SseStreamChannel testChannel(
      io.justsearch.core.execution.TestEngineExecutors executors,
      io.justsearch.app.api.stream.StreamId id, String name) {
    var owner = executors.register(io.justsearch.core.execution.EngineExecutorSpec.virtual(name,
        io.justsearch.core.execution.EngineExecutorSpec.Kind.BACKGROUND, 1));
    return new io.justsearch.app.observability.stream.SseStreamChannel(id, owner.openVirtual(), owner::close);
  }

}
