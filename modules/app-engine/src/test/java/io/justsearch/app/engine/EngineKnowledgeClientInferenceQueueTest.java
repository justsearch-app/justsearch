/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.EngineAdmissionException;
import io.justsearch.app.api.EngineWorkCancelledException;
import io.justsearch.app.api.knowledge.KnowledgeClientException;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.context.RetainedStateBudget;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.core.execution.InferenceRequest;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.server.KnowledgeServer;
import io.justsearch.indexerworker.server.WorkerAppServices;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.indexerworker.services.WorkerSearchService;
import io.justsearch.ipc.PipelineConfig;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Task t-20261005-b6d536, scenarios FP1 to FP10: the Engine's foreground inference lane lets a
 * request that finds every inference thread busy wait for one within its own deadline, and only a
 * full wait queue refuses, with its own code.
 *
 * <p>Real seam: the production {@link EngineKnowledgeClient} over the production executor registry
 * and admission controller. The worker is a mock whose "hold" searches block until released, the
 * shape of a native run that holds its thread; a request that may run inference is a search whose
 * pipeline enables the dense leg.
 *
 * <p>Uses only API that exists before the change, so each check runs on the before-state.
 */
@Timeout(30)
final class EngineKnowledgeClientInferenceQueueTest {

  private static final String INFERENCE = "engine-knowledge-inference-foreground";
  private static final String TEXT = "engine-knowledge-call-foreground";

  // ---------------------------------------------------------------------------------------------
  // FP1: requests beyond the threads wait and all run
  // ---------------------------------------------------------------------------------------------

  @ParameterizedTest(name = "concurrentInferenceRequestsBeyondTheThreadsWaitAndAllRun({arguments})")
  @ValueSource(ints = {9, 16})
  void concurrentInferenceRequestsBeyondTheThreadsWaitAndAllRun(int clients) throws Exception {
    var held = new HeldSearch();
    var admission = new EngineAdmissionController(16, 64, 1);
    try (var registry = registry(16, 48);
        var client = client(registry, held, admission, 10_000)) {
      try {
        var callers = new ArrayList<FutureTask<String>>();
        for (int i = 0; i < clients; i++) {
          SearchRequest request = inference("hold-" + i);
          EngineContext context = client(i);
          callers.add(start(() -> client.search(request, context)));
        }
        await(() -> (held.entered.get() == 8 && queued(registry, INFERENCE) == clients - 8)
            || callers.stream().anyMatch(FutureTask::isDone));

        for (var caller : callers) {
          assertFalse(caller.isDone(), () -> "a request ended at once: " + outcomeNow(caller));
        }
        assertEquals(8, held.entered.get(), "every inference thread runs a request");
        assertEquals(clients - 8, queued(registry, INFERENCE),
            "the requests beyond the threads wait for one");

        held.release.complete(null);
        for (var caller : callers) assertEquals("completed", caller.get(10, TimeUnit.SECONDS));
        assertEquals(clients, held.ran.size());
        assertEquals(0, admission.activeWorkCount());
      } finally {
        held.release.complete(null);
      }
    }
  }

  @ParameterizedTest(name = "inferenceLaneQueuesAsManyRequestsAsItHasThreadsWithinTheForegroundQueueBound({arguments})")
  @CsvSource({"16,48,8,8", "1,1,1,1", "2,1,1,1", "16,4,8,4"})
  void inferenceLaneQueuesAsManyRequestsAsItHasThreadsWithinTheForegroundQueueBound(
      int foregroundThreads, int foregroundQueue, int inferenceThreads, int inferenceQueue) {
    try (var registry = registry(foregroundThreads, foregroundQueue);
        var _ = client(registry, new HeldSearch(), new EngineAdmissionController(8, 8, 1),
            5_000)) {
      EngineExecutorSpec inference = spec(registry, INFERENCE);
      assertEquals(inferenceThreads, inference.threadCount());
      assertEquals(inferenceQueue, inference.queueCapacity());
      assertTrue(inference.queueCapacity() <= foregroundQueue);
      assertEquals(foregroundQueue, spec(registry, TEXT).queueCapacity(), "the text lane is unchanged");
    }
  }

  // ---------------------------------------------------------------------------------------------
  // FP4: threads and queue full refuse the next inference request with its own code
  // ---------------------------------------------------------------------------------------------

  @Test
  void fullInferenceThreadsAndQueueRefuseTheNextRequestWithInferenceBusy() throws Exception {
    var held = new HeldSearch();
    var admission = new EngineAdmissionController(8, 8, 1);
    try (var registry = registry(2, 1);
        var client = client(registry, held, admission, 10_000)) {
      try {
        List<FutureTask<String>> occupants = fillInferenceLane(registry, client, held);
        int occupying = occupants.size();

        var refusal = assertThrows(EngineAdmissionException.class,
            () -> client.search(inference("hold-next"), client(99)));
        assertEquals("INFERENCE_BUSY", refusal.reason().name());
        assertEquals(1, refusal.retryAfterSeconds());
        assertEquals(occupying, admission.activeWorkCount(), "the refusal released its admission");
        client.search("keyword", 10, client(98));

        held.release.complete(null);
        for (var occupant : occupants) assertEquals("completed", occupant.get(5, TimeUnit.SECONDS));
      } finally {
        held.release.complete(null);
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // FP2: the deadline a queued request runs under is the one it was submitted with
  // ---------------------------------------------------------------------------------------------

  @ParameterizedTest(name = "queuedRequestRunsUnderTheDeadlineItWasSubmittedWith({arguments})")
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
  void queuedRequestRunsUnderTheDeadlineItWasSubmittedWith(int kind) throws Exception {
    var held = new HeldSearch();
    var admission = new EngineAdmissionController(8, 8, 1);
    long budgetMs = 3_000;
    try (var registry = registry(2, 1);
        var client = client(registry, held, admission, budgetMs)) {
      try {
        var holder = start(() -> client.search(inference("hold-0"), client(0)));
        await(() -> held.entered.get() == 1);
        long submitted = System.nanoTime();
        var probe = start(() -> client.search(inference("probe", kind), client(1)));
        await(() -> queued(registry, INFERENCE) == 1 || probe.isDone());
        TimeUnit.MILLISECONDS.sleep(600);
        held.release.complete(null);

        assertEquals("completed", probe.get(5, TimeUnit.SECONDS));
        CallContext call = held.contexts.get("probe");
        assertNotNull(call, "the queued request never ran");
        long slackNanos = TimeUnit.MILLISECONDS.toNanos(100);
        assertTrue(call.deadlineNanos() - submitted <= TimeUnit.MILLISECONDS.toNanos(budgetMs) + slackNanos,
            "the deadline restarted when the request left the queue");
        assertEquals(call.deadlineNanos(), call.inferenceRequest().deadlineNanos());
        assertEquals("completed", holder.get(5, TimeUnit.SECONDS));
      } finally {
        held.release.complete(null);
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // FP3: a whole deadline spent in the queue ends the request without running it
  // ---------------------------------------------------------------------------------------------

  @ParameterizedTest(name = "requestWhoseDeadlinePassesInTheQueueNeverRunsAndReleasesItsOwnersAtOnce({arguments})")
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
  void requestWhoseDeadlinePassesInTheQueueNeverRunsAndReleasesItsOwnersAtOnce(int kind)
      throws Exception {
    var held = new HeldSearch();
    var admission = new EngineAdmissionController(8, 8, 1);
    var leases = new CopyOnWriteArrayList<KnowledgeServer.ServingLease>();
    try (var registry = registry(2, 1);
        var client = new EngineKnowledgeClient(registry, () -> held.services,
            new ForegroundLoadGate(new ForegroundLoad()), 800, 100, IpcTelemetry.noop(),
            () -> {}, admission, io.justsearch.app.services.worker.WatchedRootsState.inMemory(),
            () -> {
              var lease = mock(KnowledgeServer.ServingLease.class);
              when(lease.services()).thenReturn(held.services);
              leases.add(lease);
              return lease;
            })) {
      try {
        var holder = start(() -> client.search(inference("hold-0"), client(0)));
        await(() -> held.entered.get() == 1);
        long submitted = System.nanoTime();
        var queued = start(() -> client.search(inference("queued", kind), client(1)));
        await(() -> queued(registry, INFERENCE) == 1 || queued.isDone());

        assertEquals("deadline", queued.get(5, TimeUnit.SECONDS));
        assertTrue(System.nanoTime() - submitted < TimeUnit.MILLISECONDS.toNanos(1_500),
            "the queued request outlived its deadline");
        assertEquals(0, queued(registry, INFERENCE), "the expired request left the queue");
        assertEquals(1, admission.activeWorkCount(), "only the running holder keeps its slot");
        assertEquals(2, leases.size());
        org.mockito.Mockito.verify(leases.get(1)).close();
        assertFalse(held.release.isDone());

        held.release.complete(null);
        holder.get(5, TimeUnit.SECONDS);
        await(() -> admission.activeWorkCount() == 0);
        assertFalse(held.ran.contains("queued"), "the expired request ran");
      } finally {
        held.release.complete(null);
      }
    }
  }

  @Test
  void queuedSearchPastItsSemanticCapAnswersKeywordOnlyWithoutStartingItsEncoder()
      throws Exception {
    var held = new HeldSearch();
    var embeddings = new GpuEmbeddings();
    var runtime = mock(io.justsearch.adapters.lucene.runtime.RunningRuntime.class,
        org.mockito.Mockito.RETURNS_DEEP_STUBS);
    when(runtime.resolvedConfig()).thenReturn(
        io.justsearch.configuration.resolved.ResolvedConfig.builder().build());
    when(runtime.indexCountOps().getOrComputeCorpusProfile()).thenReturn(
        io.justsearch.adapters.lucene.runtime.CorpusProfile.EMPTY);
    var worker = new WorkerSearchService(runtime, embeddings,
        new io.justsearch.indexerworker.server.EncoderBindings());
    held.delegate = worker;
    var admission = new EngineAdmissionController(8, 8, 1);
    try (var registry = registry(2, 1);
        var client = client(registry, held, admission, 10_000)) {
      try {
        var holder = start(() -> client.search(inference("hold-0"), client(0)));
        await(() -> held.entered.get() == 1);
        long submitted = System.nanoTime();
        var queued = start(() -> client.search(SearchRequest.newBuilder().setQuery("queued")
            .setLimit(10).setPipeline(PipelineConfig.newBuilder().setSparseEnabled(true)
                .setDenseEnabled(true)).build(), client(1)));
        await(() -> queued(registry, INFERENCE) == 1 || queued.isDone());
        // Hold the only thread past the 2 000 ms GPU semantic cap the queued call began with.
        long until = submitted + TimeUnit.MILLISECONDS.toNanos(2_300);
        while (System.nanoTime() - until < 0) TimeUnit.MILLISECONDS.sleep(20);
        held.release.complete(null);

        String outcome = queued.get(5, TimeUnit.SECONDS);
        assertEquals(0, embeddings.queryCalls.get(), "the dense leg started its encoder");
        assertEquals("completed", outcome);
        var degradation = held.responses.get("queued").getSearchTrace().getDegradation();
        assertEquals("SEMANTIC_BUDGET_EXCEEDED", degradation.getHybridFallbackReason());
        assertEquals("contended", degradation.getHybridFallbackCause());
        holder.get(5, TimeUnit.SECONDS);
      } finally {
        held.release.complete(null);
      }
    }
  }

  @Test
  void queuedVectorOnlySearchPastItsSemanticBudgetIsTheTypedDeadline() throws Exception {
    var held = new HeldSearch();
    var embeddings = new GpuEmbeddings();
    var runtime = mock(io.justsearch.adapters.lucene.runtime.RunningRuntime.class,
        org.mockito.Mockito.RETURNS_DEEP_STUBS);
    when(runtime.resolvedConfig()).thenReturn(
        io.justsearch.configuration.resolved.ResolvedConfig.builder().build());
    when(runtime.indexCountOps().getOrComputeCorpusProfile()).thenReturn(
        io.justsearch.adapters.lucene.runtime.CorpusProfile.EMPTY);
    held.delegate = new WorkerSearchService(runtime, embeddings,
        new io.justsearch.indexerworker.server.EncoderBindings());
    var admission = new EngineAdmissionController(8, 8, 1);
    long budgetMs = 2_000;
    try (var registry = registry(2, 1);
        var client = client(registry, held, admission, budgetMs)) {
      try {
        var holder = start(() -> client.search(inference("hold-0"), client(0)));
        await(() -> held.entered.get() == 1);
        long submitted = System.nanoTime();
        var queued = start(() -> client.search(inference("queued"), client(1)));
        await(() -> queued(registry, INFERENCE) == 1 || queued.isDone());
        // VECTOR-only waits within D - 500 ms; hold the only thread past it, inside D.
        long until = submitted + TimeUnit.MILLISECONDS.toNanos(budgetMs - 300);
        while (System.nanoTime() - until < 0) TimeUnit.MILLISECONDS.sleep(10);
        held.release.complete(null);

        String outcome = queued.get(5, TimeUnit.SECONDS);
        assertTrue(outcome.startsWith("deadline:") && outcome.contains("SEMANTIC_BUDGET_EXCEEDED"),
            outcome);
        assertEquals(0, embeddings.queryCalls.get(), "the dense leg started its encoder");
        holder.get(5, TimeUnit.SECONDS);
      } finally {
        held.release.complete(null);
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // FP6: a closed pool is the Engine going away, never a capacity refusal
  // ---------------------------------------------------------------------------------------------

  @ParameterizedTest(name = "closedExecutorsAreReportedAsClosedNotAsCapacity({arguments})")
  @ValueSource(booleans = {true, false})
  void closedExecutorsAreReportedAsClosedNotAsCapacity(boolean inference) {
    var held = new HeldSearch();
    var admission = new EngineAdmissionController(8, 8, 1);
    try (var registry = registry(16, 48);
        var client = client(registry, held, admission, 5_000)) {
      // The client closes its call pools before its deadline timer; a call can meet a closed pool.
      lanePool(client, inference).shutdown();
      RuntimeException failure = assertThrows(RuntimeException.class,
          () -> {
            if (inference) client.search(inference("after-close"), client(0));
            else client.search("after-close", 10, client(0));
          });
      assertEquals("CLOSED", failure instanceof EngineExecutorRejectedException refused
          ? refused.reason().name() : failure.toString());
      assertEquals(0, admission.activeWorkCount());
      assertFalse(held.ran.contains("after-close"));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // FP7: shutdown and client cancellation of queued requests
  // ---------------------------------------------------------------------------------------------

  @ParameterizedTest(name = "shutdownCancelsQueuedRequestsReleasesTheirSlotsAndDrains({arguments})")
  @ValueSource(booleans = {true, false})
  void shutdownCancelsQueuedRequestsReleasesTheirSlotsAndDrains(boolean inferenceLane)
      throws Exception {
    var held = new HeldSearch();
    var admission = new EngineAdmissionController(8, 8, 1);
    var registry = registry(2, 1);
    var client = client(registry, held, admission, 10_000);
    FutureTask<Void> closer = null;
    try {
      String lane = inferenceLane ? INFERENCE : TEXT;
      var holder = start(() -> search(client, inferenceLane, "hold-0", client(0)));
      await(() -> held.entered.get() == 1);
      var queued = start(() -> search(client, inferenceLane, "queued", client(1)));
      await(() -> queued(registry, lane) == 1 || queued.isDone());
      assertEquals(1, queued(registry, lane), "the second request waits");

      admission.beginClosing();
      closer = new FutureTask<>(() -> {
        client.close();
        return null;
      });
      Thread.ofVirtual().start(closer);

      assertEquals("cancelled:engine_shutdown", queued.get(5, TimeUnit.SECONDS));
      await(() -> admission.activeWorkCount() == 1);
      assertEquals(1, admission.activeWorkCount(), "the cancelled request released its slot");

      held.release.complete(null);
      assertTrue(admission.awaitDrained(Duration.ofSeconds(5)), "drain hung");
      closer.get(5, TimeUnit.SECONDS);
      holder.get(5, TimeUnit.SECONDS);
      assertFalse(held.ran.contains("queued"));
    } finally {
      held.release.complete(null);
      if (closer == null) client.close();
      registry.close();
    }
  }

  @ParameterizedTest(name = "clientCancellationRemovesAQueuedRequestWithoutRunningIt({arguments})")
  @ValueSource(booleans = {true, false})
  void clientCancellationRemovesAQueuedRequestWithoutRunningIt(boolean inferenceLane)
      throws Exception {
    var held = new HeldSearch();
    var admission = new EngineAdmissionController(8, 8, 1);
    try (var registry = registry(2, 1);
        var client = client(registry, held, admission, 10_000);
        var owner = admission.admit(client(1), false)) {
      try {
        String lane = inferenceLane ? INFERENCE : TEXT;
        var holder = start(() -> search(client, inferenceLane, "hold-0", client(0)));
        await(() -> held.entered.get() == 1);
        var queued = start(() -> search(client, inferenceLane, "queued", owner.context()));
        await(() -> queued(registry, lane) == 1 || queued.isDone());
        assertEquals(1, queued(registry, lane), "the second request waits");

        owner.cancel("queued_cancelled");
        assertEquals("cancelled:queued_cancelled", queued.get(5, TimeUnit.SECONDS));
        assertEquals(0, queued(registry, lane), "the cancelled request left the queue");

        held.release.complete(null);
        holder.get(5, TimeUnit.SECONDS);
        assertFalse(held.ran.contains("queued"), "the cancelled request ran");
      } finally {
        held.release.complete(null);
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // FP8, FP9, FP10, FP5: preservation
  // ---------------------------------------------------------------------------------------------

  @Test
  void fullInferenceLaneLeavesTextSearchAndIngestAdmittedAndTheTextSlotReserved()
      throws Exception {
    var held = new HeldSearch();
    var ingest = mock(WorkerIngestService.class);
    when(ingest.deleteById(any(), any()))
        .thenReturn(io.justsearch.ipc.DeleteByIdResponse.getDefaultInstance());
    when(held.services.ingestService()).thenReturn(ingest);
    int capacity;
    try (var probe = registry(2, 1);
        var _ = client(probe, new HeldSearch(), new EngineAdmissionController(8, 8, 1), 5_000)) {
      EngineExecutorSpec lane = spec(probe, INFERENCE);
      capacity = lane.threadCount() + lane.queueCapacity();
    }
    try (var registry = registry(2, 1)) {
      // One aggregate slot beyond the full inference lane: the slot admission reserves for text.
      var admission = new EngineAdmissionController(16, capacity + 1, 1);
      try (var client = client(registry, held, admission, 10_000)) {
        try {
          List<FutureTask<String>> occupants = fillInferenceLane(registry, client, held);
          assertEquals(capacity, occupants.size());

          long started = System.nanoTime();
          client.search("keyword", 10, client(50));
          client.fetchDocuments(List.of("doc"), client(51));
          client.deleteById("doc", client(52));
          assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2),
              "text work waited behind queued inference");

          var refusal = assertThrows(EngineAdmissionException.class,
              () -> client.search(inference("hold-extra"), client(53)));
          assertEquals(EngineAdmissionException.Reason.ENGINE_LIMIT, refusal.reason(),
              "admission's own inference limit keeps its code");
          assertEquals(capacity, admission.activeWorkCount());

          held.release.complete(null);
          for (var occupant : occupants) assertEquals("completed", occupant.get(5, TimeUnit.SECONDS));
        } finally {
          held.release.complete(null);
        }
      }
    }
  }

  @Test
  void queuedRequestIsNotForegroundLoadUntilItStarts() throws Exception {
    var held = new HeldSearch();
    var load = new ForegroundLoad();
    var admission = new EngineAdmissionController(8, 8, 1);
    try (var registry = registry(2, 1);
        var client = new EngineKnowledgeClient(registry, () -> held.services,
            new ForegroundLoadGate(load), 10_000, 100, IpcTelemetry.noop(), () -> {}, admission,
            io.justsearch.app.services.worker.WatchedRootsState.inMemory(), null)) {
      try {
        var holder = start(() -> client.search(inference("hold-0"), client(0)));
        await(() -> held.entered.get() == 1);
        var second = start(() -> client.search(inference("hold-1"), client(1)));
        await(() -> queued(registry, INFERENCE) == 1 || second.isDone());

        assertEquals(1, load.inFlight(), "a request that has not started counts as load");

        held.release.complete(null);
        holder.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);
        await(() -> load.inFlight() == 0);
      } finally {
        held.release.complete(null);
      }
    }
  }

  @Test
  void queuedSearchBehindExpiredNativeWorkEndsByItsOwnDeadline() throws Exception {
    var held = new HeldSearch();
    var admission = new EngineAdmissionController(8, 8, 1);
    try (var registry = registry(2, 1);
        var client = client(registry, held, admission, 600)) {
      try {
        var native0 = start(() -> client.search(inference("hold-0"), client(0)));
        await(() -> held.entered.get() == 1);
        assertEquals("deadline", native0.get(3, TimeUnit.SECONDS));
        assertEquals(1, admission.activeWorkCount(), "the expired native work keeps its thread");

        long submitted = System.nanoTime();
        var probe = start(() -> client.search(inference("probe"), client(1)));
        String outcome = probe.get(3, TimeUnit.SECONDS);
        assertTrue(System.nanoTime() - submitted < TimeUnit.MILLISECONDS.toNanos(1_600),
            () -> "the queued search outlived its deadline: " + outcome);
        assertTrue(outcome.equals("deadline") || outcome.startsWith("refused:"), outcome);
        assertEquals(1, admission.activeWorkCount(), "the ended search released its slot");
        assertFalse(held.ran.contains("probe"));
      } finally {
        held.release.complete(null);
      }
    }
  }

  @Test
  void admissionLimitsKeepTheirCodesWhileTheInferenceLaneHasRoom() throws Exception {
    var held = new HeldSearch();
    try (var registry = registry(16, 48)) {
      // Per client: one inference request per context below a per-context limit of two.
      var perClient = new EngineAdmissionController(2, 8, 1);
      try (var client = client(registry, held, perClient, 10_000)) {
        try {
          var first = start(() -> client.search(inference("hold-0"), client(0)));
          await(() -> held.entered.get() == 1);
          var refusal = assertThrows(EngineAdmissionException.class,
              () -> client.search(inference("hold-1"), client(0)));
          assertEquals(EngineAdmissionException.Reason.CONTEXT_LIMIT, refusal.reason());
          held.release.complete(null);
          assertEquals("completed", first.get(5, TimeUnit.SECONDS));
        } finally {
          held.release.complete(null);
        }
      }
    }
    var lowered = new HeldSearch();
    try (var registry = registry(16, 48)) {
      // A lowered aggregate limit of three: two inference requests, one slot kept for text.
      var aggregate = new EngineAdmissionController(16, 3, 1);
      try (var client = client(registry, lowered, aggregate, 10_000)) {
        try {
          var a = start(() -> client.search(inference("hold-0"), client(0)));
          var b = start(() -> client.search(inference("hold-1"), client(1)));
          await(() -> lowered.entered.get() == 2);
          var refusal = assertThrows(EngineAdmissionException.class,
              () -> client.search(inference("hold-2"), client(2)));
          assertEquals(EngineAdmissionException.Reason.ENGINE_LIMIT, refusal.reason());
          client.search("keyword", 10, client(3));
          lowered.release.complete(null);
          assertEquals("completed", a.get(5, TimeUnit.SECONDS));
          assertEquals("completed", b.get(5, TimeUnit.SECONDS));
        } finally {
          lowered.release.complete(null);
        }
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Fixture
  // ---------------------------------------------------------------------------------------------

  /** A worker whose "hold" searches block until released, like a native run holding its thread. */
  private static final class HeldSearch {
    final WorkerSearchService search = mock(WorkerSearchService.class);
    final WorkerAppServices services = mock(WorkerAppServices.class);
    final AtomicInteger entered = new AtomicInteger();
    final CompletableFuture<Void> release = new CompletableFuture<>();
    final List<String> ran = new CopyOnWriteArrayList<>();
    final Map<String, CallContext> contexts = new ConcurrentHashMap<>();
    final Map<String, SearchResponse> responses = new ConcurrentHashMap<>();
    volatile WorkerSearchService delegate;

    HeldSearch() {
      when(search.search(any(), any())).thenAnswer(invocation -> {
        SearchRequest request = invocation.getArgument(0);
        CallContext call = invocation.getArgument(1);
        ran.add(request.getQuery());
        contexts.put(request.getQuery(), call);
        if (request.getQuery().startsWith("hold")) {
          entered.incrementAndGet();
          release.join(); // A native run ignores cancellation and interruption.
          return SearchResponse.getDefaultInstance();
        }
        WorkerSearchService real = delegate;
        SearchResponse response = real == null ? SearchResponse.getDefaultInstance()
            : real.search(request, call);
        responses.put(request.getQuery(), response);
        return response;
      });
      when(search.fetchDocuments(any(), any()))
          .thenReturn(io.justsearch.ipc.FetchDocumentsResponse.getDefaultInstance());
      when(services.searchService()).thenReturn(search);
    }
  }

  /** Occupies every inference thread and queue place, whatever the lane's shape. */
  private static List<FutureTask<String>> fillInferenceLane(DefaultEngineExecutorRegistry registry,
      EngineKnowledgeClient client, HeldSearch held) throws Exception {
    EngineExecutorSpec lane = spec(registry, INFERENCE);
    var occupants = new ArrayList<FutureTask<String>>();
    for (int i = 0; i < lane.threadCount(); i++) {
      SearchRequest request = inference("hold-" + i);
      EngineContext context = client(i);
      occupants.add(start(() -> client.search(request, context)));
    }
    await(() -> held.entered.get() == lane.threadCount());
    for (int i = 0; i < lane.queueCapacity(); i++) {
      SearchRequest request = inference("hold-q" + i);
      EngineContext context = client(100 + i);
      occupants.add(start(() -> client.search(request, context)));
    }
    await(() -> queued(registry, INFERENCE) == lane.queueCapacity());
    for (var occupant : occupants) {
      assertFalse(occupant.isDone(), () -> "an occupant ended: " + outcomeNow(occupant));
    }
    return occupants;
  }

  private static Object search(EngineKnowledgeClient client, boolean inference, String query,
      EngineContext context) {
    return inference ? client.search(inference(query), context) : client.search(query, 10, context);
  }

  /** The executor of one foreground lane, as the client's close leaves it before its timer. */
  private static java.util.concurrent.ExecutorService lanePool(EngineKnowledgeClient client,
      boolean inference) {
    try {
      var field = EngineKnowledgeClient.class.getDeclaredField(
          inference ? "foregroundInferenceThreads" : "foregroundCallThreads");
      field.setAccessible(true);
      return (java.util.concurrent.ExecutorService) field.get(client);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  /** Every kind of search that may run inference, as EngineKnowledgeClientExecutorTest lists. */
  private static SearchRequest inference(String query, int kind) {
    var request = SearchRequest.newBuilder().setQuery(query).setLimit(10);
    var pipeline = PipelineConfig.newBuilder();
    switch (kind) {
      case 0 -> request.setPipeline(pipeline.setDenseEnabled(true));
      case 1 -> request.setPipeline(pipeline.setDenseAuto(true));
      case 2 -> request.setPipeline(pipeline.setSpladeEnabled(true));
      case 3 -> request.setPipeline(pipeline.setSparseEnabled(true).setCrossEncoderEnabled(true));
      case 4 -> request.setMode(io.justsearch.ipc.SearchMode.SEARCH_MODE_HYBRID);
      case 5 -> request.setMode(io.justsearch.ipc.SearchMode.SEARCH_MODE_VECTOR);
      case 6 -> request.setMode(io.justsearch.ipc.SearchMode.SEARCH_MODE_SPLADE);
      default -> throw new IllegalArgumentException("Unknown kind " + kind);
    }
    return request.build();
  }

  private static SearchRequest inference(String query) {
    return SearchRequest.newBuilder().setQuery(query).setLimit(10)
        .setPipeline(PipelineConfig.newBuilder().setDenseEnabled(true)).build();
  }

  /** A distinct caller, so per-client limits never decide these checks. */
  private static EngineContext client(int i) {
    return EngineProvenance.internal("inference-queue-" + i,
        EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.FOREGROUND);
  }

  /** Runs a call on its own thread and reports how it ended. */
  private static FutureTask<String> start(Callable<?> call) {
    var task = new FutureTask<>(() -> {
      try {
        call.call();
        return "completed";
      } catch (EngineAdmissionException refused) {
        return "refused:" + refused.reason().name();
      } catch (EngineWorkCancelledException cancelled) {
        return "cancelled:" + cancelled.reasonCode();
      } catch (KnowledgeClientException failure) {
        if (failure.status() == KnowledgeClientException.Status.DEADLINE_EXCEEDED) {
          return failure.getMessage() != null
              && failure.getMessage().contains("SEMANTIC_BUDGET_EXCEEDED")
              ? "deadline:" + failure.getMessage() : "deadline";
        }
        return "failed:" + failure.status() + ":" + failure.getMessage();
      } catch (RuntimeException failure) {
        return "failed:" + failure;
      }
    });
    Thread.ofVirtual().start(task);
    return task;
  }

  private static String outcomeNow(FutureTask<String> task) {
    try {
      return task.get(0, TimeUnit.MILLISECONDS);
    } catch (Exception e) {
      return String.valueOf(e);
    }
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (!condition.getAsBoolean() && System.nanoTime() - deadline < 0) {
      TimeUnit.MILLISECONDS.sleep(2);
    }
  }

  private static int queued(DefaultEngineExecutorRegistry registry, String name) {
    return registry.snapshot().registrations().stream()
        .filter(row -> row.spec().name().equals(name))
        .findFirst().orElseThrow().queuedTasks();
  }

  private static EngineExecutorSpec spec(DefaultEngineExecutorRegistry registry, String name) {
    return registry.snapshot().registrations().stream()
        .filter(row -> row.spec().name().equals(name))
        .findFirst().orElseThrow().spec();
  }

  private static EngineKnowledgeClient client(DefaultEngineExecutorRegistry registry,
      HeldSearch held, EngineAdmissionController admission, long deadlineMs) {
    return new EngineKnowledgeClient(registry, () -> held.services,
        new ForegroundLoadGate(new ForegroundLoad()), deadlineMs, 100, IpcTelemetry.noop(),
        () -> {}, admission, io.justsearch.app.services.worker.WatchedRootsState.inMemory(), null);
  }

  private static DefaultEngineExecutorRegistry registry(int foregroundThreads, int foregroundQueue) {
    Map<String, Integer> execution = new LinkedHashMap<>();
    execution.put("perContextLimit", 64);
    execution.put("aggregateLimit", 64);
    execution.put("retryAfterSeconds", 1);
    execution.put("foregroundThreads", foregroundThreads);
    execution.put("foregroundQueue", foregroundQueue);
    execution.put("backgroundThreads", 4);
    execution.put("backgroundQueue", 64);
    execution.put("timerRegistrations", 64);
    execution.put("directMemoryMiB", 1);
    return new DefaultEngineExecutorRegistry(
        new EngineResourcePolicy(Map.copyOf(execution), new RetainedStateBudget()),
        Duration.ofSeconds(1));
  }

  /** A query embedder on the GPU that answers at once and counts its query embeds. */
  private static final class GpuEmbeddings
      implements io.justsearch.indexerworker.embed.EmbeddingProvider {
    final AtomicInteger queryCalls = new AtomicInteger();

    @Override
    public float[] embedQuery(String text, InferenceRequest request) {
      queryCalls.incrementAndGet();
      return new float[] {1f, 0f, 0f, 0f};
    }

    @Override
    public float[] embedDocument(String text, InferenceRequest request) {
      throw new AssertionError("no document embeds in a search");
    }

    @Override
    public float[] embedQuery(String text) {
      throw new AssertionError("a query embed must carry its request");
    }

    @Override
    public float[] embedDocument(String text) {
      throw new AssertionError("no document embeds in a search");
    }

    @Override
    public List<float[]> embedDocumentBatch(List<String> texts) {
      throw new AssertionError("no document embeds in a search");
    }

    @Override
    public io.justsearch.indexerworker.embed.EmbeddingService.ChunkedEmbedding embedWithSpans(
        String content, int[][] charSpans) {
      return null;
    }

    @Override
    public int dimension() {
      return 4;
    }

    @Override
    public Availability availability() {
      return Availability.READY;
    }

    @Override
    public boolean isUsingGpu() {
      return true;
    }

    @Override
    public io.justsearch.ort.QueryDevice queryDevice() {
      return io.justsearch.ort.QueryDevice.GPU;
    }
  }
}
