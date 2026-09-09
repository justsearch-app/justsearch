/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.IndexingService;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.search.SearchPort;
import io.justsearch.core.dto.Query;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.server.WorkerAppServices;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.indexerworker.services.WorkerSearchService;
import io.justsearch.ipc.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

final class EngineContextPortPropagationTest {
  @Test
  void unaryDispatchCapturesEnteringTraceAndRequestBeforeThreadHop() {
    var services = mock(WorkerAppServices.class);
    var search = mock(WorkerSearchService.class);
    when(services.searchService()).thenReturn(search);
    var enteringThread = Thread.currentThread();
    var seen = new AtomicReference<CallContext>();
    when(search.search(any(), any())).thenAnswer(call -> {
      assertNotSame(enteringThread, Thread.currentThread());
      seen.set(call.getArgument(1));
      return SearchResponse.getDefaultInstance();
    });
    String traceId = "1234567890abcdef1234567890abcdef";
    var span = io.opentelemetry.api.trace.Span.wrap(io.opentelemetry.api.trace.SpanContext.create(
        traceId, "1234567890abcdef", io.opentelemetry.api.trace.TraceFlags.getSampled(),
        io.opentelemetry.api.trace.TraceState.getDefault()));
    String previousRequest = org.slf4j.MDC.get("request_id");
    try (var _ = span.makeCurrent();
        var client = new EngineKnowledgeClient(
            new io.justsearch.core.execution.TestEngineExecutors(), () -> services,
            new ForegroundLoadGate(new ForegroundLoad()), 5_000, 100, IpcTelemetry.noop())) {
      org.slf4j.MDC.put("request_id", "entering-request");
      client.search("trace probe", 10, TestEngineContexts.FOREGROUND);
      assertEquals(traceId, seen.get().traceId());
      assertEquals("entering-request", seen.get().requestId());
    } finally {
      if (previousRequest == null) org.slf4j.MDC.remove("request_id");
      else org.slf4j.MDC.put("request_id", previousRequest);
    }
  }

  @Test
  void runningSubscriptionKeepsEnteringCorrelationAcrossLaterCancellationAndThreadHop()
      throws Exception {
    var admission = new EngineAdmissionController(1, 1, 1);
    var services = mock(WorkerAppServices.class);
    var ingest = mock(WorkerIngestService.class);
    when(services.ingestService()).thenReturn(ingest);
    var enteringThread = Thread.currentThread();
    var seen = new AtomicReference<CallContext>();
    var producerThread = new AtomicReference<Thread>();
    var producerEntered = new CountDownLatch(1);
    var producerTaskStarted = new CountDownLatch(1);
    var releaseProducer = new CountDownLatch(1);
    var cancellationObserved = new CountDownLatch(1);
    var workCompleted = new CountDownLatch(1);
    doAnswer(
            call -> {
              producerThread.set(Thread.currentThread());
              seen.set(call.getArgument(2));
              producerEntered.countDown();
              return null;
            })
        .when(ingest)
        .subscribeIndexingJobs(any(SubscribeIndexingJobsRequest.class), any(), any());
    String traceId = "2234567890abcdef1234567890abcdef";
    var span =
        io.opentelemetry.api.trace.Span.wrap(
            io.opentelemetry.api.trace.SpanContext.create(
                traceId,
                "2234567890abcdef",
                io.opentelemetry.api.trace.TraceFlags.getSampled(),
                io.opentelemetry.api.trace.TraceState.getDefault()));
    String previousRequest = org.slf4j.MDC.get("request_id");
    try (var _ = span.makeCurrent();
        var owner = admission.admit(TestEngineContexts.FOREGROUND, false);
        var client =
            new EngineKnowledgeClient(
                new io.justsearch.core.execution.TestEngineExecutors(),
                () -> {
                  producerTaskStarted.countDown();
                  try {
                    if (!releaseProducer.await(3, TimeUnit.SECONDS)) {
                      throw new AssertionError("producer was not released");
                    }
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                  }
                  return services;
                },
                new ForegroundLoadGate(new ForegroundLoad()),
                5_000,
                100,
                IpcTelemetry.noop(),
                () -> {},
                admission)) {
      org.slf4j.MDC.put("request_id", "subscription-request");
      owner.onCompletion(workCompleted::countDown);
      try (var _ =
          client.subscribeIndexingJobs(
              frame -> {},
              failure -> {
                org.slf4j.MDC.remove("request_id");
                cancellationObserved.countDown();
              },
              () -> {},
              owner.context())) {
        assertTrue(producerTaskStarted.await(3, TimeUnit.SECONDS));
        owner.cancel("cancelled_after_start");
        assertTrue(cancellationObserved.await(3, TimeUnit.SECONDS));
        owner.close();
        assertThrows(
            io.justsearch.app.api.EngineAdmissionException.class,
            () -> admission.attach(TestEngineContexts.BACKGROUND));
        releaseProducer.countDown();
        assertTrue(producerEntered.await(3, TimeUnit.SECONDS));
        assertTrue(workCompleted.await(3, TimeUnit.SECONDS));
        assertNotSame(enteringThread, producerThread.get());
        assertEquals(traceId, seen.get().traceId());
        assertEquals("subscription-request", seen.get().requestId());
      }
    } finally {
      releaseProducer.countDown();
      if (previousRequest == null) org.slf4j.MDC.remove("request_id");
      else org.slf4j.MDC.put("request_id", previousRequest);
    }
  }

  @Test
  void preCancelledSubscriptionReportsReasonWithoutDispatchingWorker() throws Exception {
    var admission = new EngineAdmissionController(1, 1, 1);
    var services = mock(WorkerAppServices.class);
    var ingest = mock(WorkerIngestService.class);
    when(services.ingestService()).thenReturn(ingest);
    var cancellation = new AtomicReference<Throwable>();
    var cancellationObserved = new CountDownLatch(1);
    var workCompleted = new CountDownLatch(1);

    try (var owner = admission.admit(TestEngineContexts.FOREGROUND, false);
        var client =
            new EngineKnowledgeClient(
                new io.justsearch.core.execution.TestEngineExecutors(),
                () -> services,
                new ForegroundLoadGate(new ForegroundLoad()),
                5_000,
                100,
                IpcTelemetry.noop(),
                () -> {},
                admission)) {
      owner.onCompletion(workCompleted::countDown);
      owner.cancel("already_cancelled");
      try (var _ =
          client.subscribeIndexingJobs(
              frame -> fail("unexpected frame"),
              failure -> {
                cancellation.set(failure);
                cancellationObserved.countDown();
              },
              () -> fail("unexpected completion"),
              owner.context())) {
        assertTrue(cancellationObserved.await(3, TimeUnit.SECONDS));
      }
      verify(ingest, never()).subscribeIndexingJobs(any(), any(), any());
      var reason = assertInstanceOf(
          io.justsearch.app.api.EngineWorkCancelledException.class, cancellation.get());
      assertEquals("already_cancelled", reason.reasonCode());
      owner.close();
      assertTrue(workCompleted.await(3, TimeUnit.SECONDS));
      try (var replacement = admission.attach(TestEngineContexts.BACKGROUND)) {
        assertTrue(replacement.context().workId().isPresent());
      }
    }
  }

  @Test
  void scanDispatchCarriesEnteringTraceAndRequest() {
    var services = mock(WorkerAppServices.class);
    var ingest = mock(WorkerIngestService.class);
    when(services.ingestService()).thenReturn(ingest);
    var seen = new AtomicReference<CallContext>();
    doAnswer(
            call -> {
              seen.set(call.getArgument(2));
              @SuppressWarnings("unchecked")
              java.util.function.Consumer<ScanRootProgress> progress = call.getArgument(1);
              progress.accept(ScanRootProgress.getDefaultInstance());
              return null;
            })
        .when(ingest)
        .scanRoot(any(ScanRootRequest.class), any(), any());
    String traceId = "3234567890abcdef1234567890abcdef";
    var span =
        io.opentelemetry.api.trace.Span.wrap(
            io.opentelemetry.api.trace.SpanContext.create(
                traceId,
                "3234567890abcdef",
                io.opentelemetry.api.trace.TraceFlags.getSampled(),
                io.opentelemetry.api.trace.TraceState.getDefault()));
    String previousRequest = org.slf4j.MDC.get("request_id");
    try (var _ = span.makeCurrent();
        var client =
            new EngineKnowledgeClient(
                new io.justsearch.core.execution.TestEngineExecutors(),
                () -> services,
                new ForegroundLoadGate(new ForegroundLoad()),
                5_000,
                100,
                IpcTelemetry.noop())) {
      org.slf4j.MDC.put("request_id", "scan-request");
      client.executeScanRoot(
          ScanRootRequest.getDefaultInstance(), null, event -> {}, TestEngineContexts.FOREGROUND);
      assertEquals(traceId, seen.get().traceId());
      assertEquals("scan-request", seen.get().requestId());
    } finally {
      if (previousRequest == null) org.slf4j.MDC.remove("request_id");
      else org.slf4j.MDC.put("request_id", previousRequest);
    }
  }

  @Test
  void agentAdmissionSurvivesSwitchingAndQueueRestart(@TempDir Path directory) throws Exception {
    Path index = directory.resolve("index");
    Files.createDirectories(index.resolve("indices/g-building"));
    Files.writeString(index.resolve("state.json"), """
        {"format_version":2,"active_generation":"g-active","building_generation":"g-building",
         "previous_generation":null,"migration_state":"SWITCHING","migration_paused":false,
         "pause_reason":null,"paused_at_ms":null,"updated_at_ms":1}
        """);
    Path document = directory.resolve("agent-document.txt");
    Files.writeString(document, "agent-originated switching admission");
    Path database = directory.resolve("jobs.db");
    var caller = new EngineContext(EngineContext.ClientKind.MCP_CLIENT, "mcp-switch-test",
        java.util.Optional.of("agent-session"), java.util.Optional.empty(), "UNTRUSTED", "MCP",
        EngineContext.Survival.DURABLE, EngineContext.Urgency.FOREGROUND);
    try (var queue = new io.justsearch.indexerworker.queue.SqliteJobQueue(database)) {
      queue.open();
      var ingest = new WorkerIngestService(queue, null, null,
          io.justsearch.indexerworker.loop.pacing.IndexingPacing.unthrottled(), index,
          index.resolve("indices/g-building"), null, null, null, 0L);
      WorkerAppServices services = mock(WorkerAppServices.class);
      when(services.ingestService()).thenReturn(ingest);
      try (var client = new EngineKnowledgeClient(
          new io.justsearch.core.execution.TestEngineExecutors(), () -> services,
          new ForegroundLoadGate(new ForegroundLoad()), 5_000, 100, IpcTelemetry.noop())) {
        client.submitBatch(List.of(document), caller);
        assertEquals(1, queue.switchBufferDepth());
        assertEquals(0, queue.pollPending(1).size(), "SWITCHING must really buffer admission");
      }
    }
    try (var queue = new io.justsearch.indexerworker.queue.SqliteJobQueue(database)) {
      queue.open();
      io.justsearch.indexerworker.server.ops.KnowledgeServerMigrationOps.drainSwitchBufferBestEffort(
          new io.justsearch.indexerworker.server.ops.KnowledgeServerMigrationOps.DrainSwitchBufferContext(
              queue, null, null, null, index, index.resolve("indices/g-building"),
              tools.jackson.databind.json.JsonMapper.builder().build(), () -> false,
              org.slf4j.LoggerFactory.getLogger(getClass())));
      assertEquals(0, queue.switchBufferDepth());
      var claimed = queue.pollPending(1).getFirst();
      queue.markDone(document, io.justsearch.indexerworker.ingest.IngestionOutcome.of(
          io.justsearch.indexerworker.ingest.IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS",
          io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE, "Indexed"),
          io.justsearch.indexerworker.loop.LedgerEntryFactory.forPathOnly(
              claimed.path(), claimed.collection(), claimed.provenance()));
    }
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var statement = connection.createStatement();
        var row = statement.executeQuery("SELECT originator, transport FROM ingestion_ledger")) {
      assertTrue(row.next());
      assertEquals("agent", row.getString("originator"));
      assertEquals("MCP", row.getString("transport"));
      assertFalse(row.next());
    }
  }

  @Test
  void actualPortCallsCarryBothAxesToWorkerService() {
    WorkerAppServices services = mock(WorkerAppServices.class);
    WorkerSearchService search = mock(WorkerSearchService.class);
    WorkerIngestService ingest = mock(WorkerIngestService.class);
    when(services.searchService()).thenReturn(search);
    when(services.ingestService()).thenReturn(ingest);
    List<CallContext> searches = new ArrayList<>();
    List<CallContext> indexing = new ArrayList<>();
    when(search.search(any(), any())).thenAnswer(call -> {
      searches.add(call.getArgument(1));
      return SearchResponse.getDefaultInstance();
    });
    when(ingest.listFailedJobs(any(), any())).thenAnswer(call -> {
      indexing.add(call.getArgument(1));
      return ListFailedJobsResponse.getDefaultInstance();
    });
    when(ingest.clearFailedJobs(any(), any())).thenAnswer(call -> {
      indexing.add(call.getArgument(1));
      return ClearFailedJobsResponse.getDefaultInstance();
    });
    try (var client = new EngineKnowledgeClient(
        new io.justsearch.core.execution.TestEngineExecutors(), () -> services,
        new ForegroundLoadGate(new ForegroundLoad()), 5_000, 100, IpcTelemetry.noop())) {
      // The SearchPort signature is exercised directly, through KnowledgeClient and the bridge.
      SearchPort searchPort = client;
      searchPort.search(query("foreground"), TestEngineContexts.FOREGROUND);
      searchPort.search(query("background"), TestEngineContexts.BACKGROUND);
      IndexingService indexingPort = client;
      indexingPort.listFailedJobs(10, TestEngineContexts.BACKGROUND);
      indexingPort.clearFailedJobs(TestEngineContexts.FOREGROUND);
    }
    assertEquals(2, searches.size());
    assertAttachedContext(TestEngineContexts.FOREGROUND, searches.get(0).engineContext());
    assertAttachedContext(TestEngineContexts.BACKGROUND, searches.get(1).engineContext());
    assertEquals(2, indexing.size());
    assertAttachedContext(TestEngineContexts.BACKGROUND, indexing.get(0).engineContext());
    assertAttachedContext(TestEngineContexts.FOREGROUND, indexing.get(1).engineContext());
    for (CallContext call : searches) {
      assertEquals(call.engineContext().transport(), call.provenance().transport());
      assertEquals("system", call.provenance().originator());
    }
    assertEquals(EngineContext.Survival.DURABLE, searches.get(1).engineContext().survival());
  }

  private static Query query(String text) {
    return new Query(10, 0, false, null, List.of(),
        List.of(new Query.Clause("text", null, text, List.of())), null);
  }

  private static void assertAttachedContext(EngineContext caller, EngineContext actual) {
    assertTrue(actual.workId().isPresent(), "the port must admit exact work before dispatch");
    assertEquals(caller.withWorkId(actual.workId().orElseThrow()), actual);
  }
}
