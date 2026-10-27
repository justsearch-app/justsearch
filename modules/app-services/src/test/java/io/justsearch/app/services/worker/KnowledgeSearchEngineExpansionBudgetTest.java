/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequest;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.PipelineConfig;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.api.knowledge.QueryType;
import io.justsearch.core.execution.EngineFutures;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.ipc.SearchQuerySyntax;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchResult;
import io.justsearch.ipc.SearchTrace;
import io.justsearch.ipc.TraceDegradation;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Spec D §1.6 at the head seam (test H1, results half): an expansion re-search that loses a
 * semantic leg the base search had does not replace the base results; the expansion is recorded
 * with the existing expansion-timeout reason. A re-search that keeps its legs is applied as before.
 */
final class KnowledgeSearchEngineExpansionBudgetTest {

  private static final String SBE = "SEMANTIC_BUDGET_EXCEEDED";

  @TempDir Path modelsDir;
  private ConfigStore previousConfig;

  @BeforeEach
  void installConfig() {
    previousConfig = ConfigStore.globalOrNull();
    // An empty models directory: no reranker, so the only worker calls are the searches.
    ConfigStore.setGlobal(new ConfigStore(TestResolvedConfigHelper.fromEntries(Map.of(
        "justsearch.models.dir", modelsDir.toString(),
        "justsearch.search.query_classification.enabled", "false"))));
  }

  @AfterEach
  void restoreConfig() {
    TestResolvedConfigHelper.restoreGlobal(previousConfig);
  }

  @Test
  void aReSearchThatLosesASemanticLegKeepsTheBaseResults() {
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.search(any(SearchRequest.class), any())).thenAnswer(invocation -> {
      SearchRequest request = invocation.getArgument(0);
      if (isExpanded(request)) {
        // The re-search's shorter budget ran out before its dense leg: keyword results only.
        return hits("/expanded-keyword-only.txt").toBuilder()
            .setSearchTrace(SearchTrace.newBuilder().setDegradation(TraceDegradation.newBuilder()
                .setHybridFallback(true).setHybridFallbackReason(SBE)))
            .build();
      }
      return hits("/base-hybrid.txt");
    });

    KnowledgeSearchResponse response =
        engineOver(client).search(expandingRequest(), TestEngineContexts.internal());

    assertEquals(List.of("/base-hybrid.txt"), ids(response),
        "the base results, which kept their semantic leg, are returned");
    assertNotEquals(SBE, response.searchTrace().degradation() == null
        ? null : response.searchTrace().degradation().hybridFallbackReason());
    assertEquals("TIMEOUT", expansionReason(response),
        "the expansion that did not fit records the existing expansion-timeout reason");
  }

  @Test
  void aReSearchThatKeepsItsSemanticLegsIsApplied() {
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.search(any(SearchRequest.class), any())).thenAnswer(invocation ->
        isExpanded(invocation.getArgument(0)) ? hits("/expanded.txt") : hits("/base.txt"));

    KnowledgeSearchResponse response =
        engineOver(client).search(expandingRequest(), TestEngineContexts.internal());

    assertEquals(List.of("/expanded.txt"), ids(response));
    assertNull(expansionReason(response), "the expansion executed");
  }


  @Test
  void stalledQuLeavesReservedTimeForResponsiveBaseAndAbandonsQueuedRoot() throws Exception {
    checkQuDeadline(false);
  }

  @Test
  void stalledQuReturnsBaseWhileRunningProducerRetainsWorkUntilExit() throws Exception {
    checkQuDeadline(true);
  }

  private void checkQuDeadline(boolean running) throws Exception {
    optionalConfig(true);
    try (var completion = new OwnedCompletion(running)) {
      OnlineAiService ai = aiReturning(completion.root);
      var searches = new AtomicInteger();
      KnowledgeClient client = responsiveClient(searches, 80);
      var engine = engineOver(client, ai, mock(SearchPerSourceExecutor.class));
      assertEquals(QueryType.INFORMATIONAL, QueryClassifier.classify("climate policy"));
      long d = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(3200);
      var response = HeadSearchDeadline.within(d,
          () -> engine.search(optionalRequest(false), TestEngineContexts.internal()));

      assertEquals(List.of("/base.txt"), ids(response));
      org.mockito.Mockito.verify(ai).chatCompletion(any(), anyInt(), any(), any());
      assertEquals(1, searches.get());
      assertTrue(System.nanoTime() < d, "stalled QU must leave the responsive base inside caller D");
      assertTrue(completion.root.isCompletedExceptionally(), "the original completion is bounded");
      completion.assertAbandoned(running, false);
    }
  }

  @Test
  void stalledExpansionReturnsResponsiveBaseInsideShortCallerDeadline() throws Exception {
    checkExpansionDeadline(false);
  }

  @Test
  void stalledExpansionRetainsRunningProducerUntilActualExit() throws Exception {
    checkExpansionDeadline(true);
  }

  private void checkExpansionDeadline(boolean running) throws Exception {
    optionalConfig(false);
    try (var completion = new OwnedCompletion(running)) {
      OnlineAiService ai = aiReturning(completion.root);
      var searches = new AtomicInteger();
      KnowledgeClient client = responsiveClient(searches, 80);
      var engine = engineOver(client, ai, mock(SearchPerSourceExecutor.class));
      long d = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1100);
      var response = HeadSearchDeadline.within(d,
          () -> engine.search(optionalRequest(true), TestEngineContexts.internal()));

      assertEquals(List.of("/base.txt"), ids(response));
      org.mockito.Mockito.verify(ai).chatCompletion(any(), anyInt(), any(), any());
      assertEquals(1, searches.get(), "a stalled expansion cannot enter re-search");
      assertTrue(System.nanoTime() < d - TimeUnit.MILLISECONDS.toNanos(400),
          "completion wait ends at D minus 500ms, allowing 100ms for response mapping/scheduling");
      assertEquals("TIMEOUT", expansionReason(response));
      completion.assertAbandoned(running, true);
    }
  }

  @Test
  void spentCallerBudgetDoesNotSubmitExpansion() {
    optionalConfig(false);
    OnlineAiService ai = aiReturning(CompletableFuture.completedFuture("climates"));
    var engine = engineOver(responsiveClient(new AtomicInteger(), 0), ai,
        mock(SearchPerSourceExecutor.class));
    var response = HeadSearchDeadline.within(System.nanoTime() - 1,
        () -> engine.search(optionalRequest(true), TestEngineContexts.internal()));
    assertEquals(0, completionCalls(ai), "no optional completion may be submitted");
    assertEquals("TIMEOUT", expansionReason(response));
  }

  @Test
  void quWithNoPositiveAssistBudgetDoesNotSubmit() {
    optionalConfig(true);
    OnlineAiService ai = aiReturning(CompletableFuture.completedFuture("{\"query\":\"climate policy\"}"));
    var engine = engineOver(responsiveClient(new AtomicInteger(), 0), ai,
        mock(SearchPerSourceExecutor.class));
    HeadSearchDeadline.within(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1900),
        () -> engine.search(optionalRequest(false), TestEngineContexts.internal()));
    assertEquals(0, completionCalls(ai), "no optional completion may be submitted");
  }

  @Test
  void quRootTimeoutUsesOriginalCallerMinusBaseReserveAndResponseMargin() {
    optionalConfig(true);
    var timeoutBy = new java.util.concurrent.atomic.AtomicLong();
    var root = new CompletableFuture<String>() {
      @Override public CompletableFuture<String> orTimeout(long timeout, TimeUnit unit) {
        timeoutBy.set(System.nanoTime() + unit.toNanos(timeout));
        complete("{\"query\":\"climate policy\"}");
        return super.orTimeout(timeout, unit);
      }
    };
    OnlineAiService ai = aiReturning(root);
    var engine = engineOver(responsiveClient(new AtomicInteger(), 0), ai,
        mock(SearchPerSourceExecutor.class));
    long d = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(4000);
    HeadSearchDeadline.within(d,
        () -> engine.search(optionalRequest(false), TestEngineContexts.internal()));
    org.mockito.Mockito.verify(ai).chatCompletion(any(), anyInt(), any(), any());
    long expected = d - TimeUnit.MILLISECONDS.toNanos(1500 + 500);
    assertTrue(Math.abs(timeoutBy.get() - expected) < TimeUnit.MILLISECONDS.toNanos(25),
        "the ORIGINAL completion receives D minus the provisional base reserve and response margin");
  }

  @Test
  void quRecomputesRemainingBudgetAfterSynchronousSubmission() throws Exception {
    optionalConfig(true);
    try (var completion = new OwnedCompletion(false)) {
      OnlineAiService ai = mock(OnlineAiService.class);
      when(ai.isAvailable()).thenReturn(true);
      long d = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2400);
      when(ai.chatCompletion(any(), anyInt(), any(), any())).thenAnswer(ignored -> {
        // Spend the entire optional budget inside setup, leaving the reserved base window.
        TimeUnit.NANOSECONDS.sleep(Math.max(0,
            d - TimeUnit.MILLISECONDS.toNanos(1950) - System.nanoTime()));
        return completion.root;
      });
      var searches = new AtomicInteger();
      var engine = engineOver(responsiveClient(searches, 80), ai, mock(SearchPerSourceExecutor.class));
      var response = HeadSearchDeadline.within(d,
          () -> engine.search(optionalRequest(false), TestEngineContexts.internal()));
      org.mockito.Mockito.verify(ai).chatCompletion(any(), anyInt(), any(), any());
      assertEquals(List.of("/base.txt"), ids(response));
      assertTrue(System.nanoTime() < d, "submission time must not start a fresh QU budget");
      completion.assertAbandoned(false, false);
    }
  }

  @Test
  void expansionRecomputesWaitAfterSynchronousSubmissionSpendsItsCutoff() throws Exception {
    optionalConfig(false);
    try (var completion = new OwnedCompletion(false)) {
      OnlineAiService ai = mock(OnlineAiService.class);
      when(ai.isAvailable()).thenReturn(true);
      long d = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1100);
      when(ai.chatCompletion(any(), anyInt(), any(), any())).thenAnswer(ignored -> {
        TimeUnit.NANOSECONDS.sleep(Math.max(0,
            d - TimeUnit.MILLISECONDS.toNanos(450) - System.nanoTime()));
        return completion.root;
      });
      var searches = new AtomicInteger();
      var engine = engineOver(responsiveClient(searches, 50), ai, mock(SearchPerSourceExecutor.class));
      var response = HeadSearchDeadline.within(d,
          () -> engine.search(optionalRequest(true), TestEngineContexts.internal()));
      org.mockito.Mockito.verify(ai).chatCompletion(any(), anyInt(), any(), any());
      assertEquals(List.of("/base.txt"), ids(response));
      assertEquals(1, searches.get());
      assertTrue(System.nanoTime() < d, "synchronous submission cannot extend the completion cutoff");
      completion.assertAbandoned(false, true);
    }
  }

  @Test
  void expansionCannotLaunchAfterItsAssistCutoffEvenBeforeCallerDeadline() {
    optionalConfig(false);
    OnlineAiService ai = aiReturning(CompletableFuture.completedFuture("climates"));
    var engine = engineOver(responsiveClient(new AtomicInteger(), 0), ai,
        mock(SearchPerSourceExecutor.class));
    var response = HeadSearchDeadline.within(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(400),
        () -> engine.search(optionalRequest(true), TestEngineContexts.internal()));
    assertEquals(0, completionCalls(ai), "no optional completion may be submitted");
    assertEquals("TIMEOUT", expansionReason(response));
  }

  @Test
  void baseRetrievalFailureAbandonsPendingExpansionBeforeItCanDispatch() throws Exception {
    optionalConfig(false);
    try (var completion = new OwnedCompletion(false)) {
      OnlineAiService ai = aiReturning(completion.root);
      KnowledgeClient client = mock(KnowledgeClient.class);
      var failure = new IllegalStateException("base Worker failed");
      when(client.search(any(SearchRequest.class), any())).thenThrow(failure);
      var engine = engineOver(client, ai, mock(SearchPerSourceExecutor.class));
      assertSame(failure, assertThrows(IllegalStateException.class,
          () -> engine.search(optionalRequest(true), TestEngineContexts.internal())));
      org.mockito.Mockito.verify(ai).chatCompletion(any(), anyInt(), any(), any());
      completion.assertAbandoned(false, true);
    }
  }

  @Test
  void interruptedExpansionCollectorAbandonsRootAndPreservesInterruption() throws Exception {
    optionalConfig(false);
    try (var completion = new OwnedCompletion(false)) {
      OnlineAiService ai = aiReturning(completion.root);
      KnowledgeClient client = responsiveClient(new AtomicInteger(), 0);
      // Interrupt after the optional submission, immediately before its collector runs.
      when(client.search(any(SearchRequest.class), any())).thenAnswer(ignored -> {
        Thread.currentThread().interrupt();
        return hits("/base.txt");
      });
      var engine = engineOver(client, ai, mock(SearchPerSourceExecutor.class));
      try {
        var failure = assertThrows(java.util.concurrent.CompletionException.class,
            () -> engine.search(optionalRequest(true), TestEngineContexts.internal()));
        assertInstanceOf(InterruptedException.class, failure.getCause());
        assertTrue(Thread.currentThread().isInterrupted());
        completion.assertAbandoned(false, true);
      } finally { Thread.interrupted(); }
    }
  }

  @Test
  void interruptedQuCollectorAbandonsOriginalQueuedRoot() throws Exception {
    optionalConfig(true);
    try (var completion = new OwnedCompletion(false)) {
      OnlineAiService ai = mock(OnlineAiService.class);
      when(ai.isAvailable()).thenReturn(true);
      when(ai.chatCompletion(any(), anyInt(), any(), any())).thenAnswer(ignored -> {
        Thread.currentThread().interrupt();
        return completion.root;
      });
      var searches = new AtomicInteger();
      var engine = engineOver(responsiveClient(searches, 0), ai, mock(SearchPerSourceExecutor.class));
      try {
        var failure = assertThrows(java.util.concurrent.CompletionException.class,
            () -> engine.search(optionalRequest(false), TestEngineContexts.internal()));
        assertInstanceOf(InterruptedException.class, failure.getCause());
        assertTrue(Thread.currentThread().isInterrupted());
        assertEquals(0, searches.get(), "interrupted QU cannot dispatch more work");
        completion.assertAbandoned(false, false);
      } finally { Thread.interrupted(); }
    }
  }

  @ParameterizedTest(name = "optionalCallerTerminationAndExecutorRefusalNeverBecomeOrdinaryFallback({0})")
  @ValueSource(strings = {"qu-cancel", "qu-interrupt", "qu-refusal",
      "expansion-cancel", "expansion-interrupt", "expansion-refusal"})
  void optionalCallerTerminationAndExecutorRefusalNeverBecomeOrdinaryFallback(String termination) {
    boolean qu = termination.startsWith("qu-");
    optionalConfig(qu);
    Throwable cause = switch (termination.substring(termination.indexOf('-') + 1)) {
      case "cancel" -> new java.util.concurrent.CancellationException("caller left");
      case "interrupt" -> new InterruptedException("caller interrupted");
      case "refusal" -> new io.justsearch.core.execution.EngineExecutorRejectedException(
          io.justsearch.core.execution.EngineExecutorRejectedException.Reason.QUEUE_LIMIT,
          "optional-test", 1);
      default -> throw new AssertionError(termination);
    };
    OnlineAiService ai = aiReturning(CompletableFuture.failedFuture(cause));
    var searches = new AtomicInteger();
    var engine = engineOver(responsiveClient(searches, 0), ai, mock(SearchPerSourceExecutor.class));
    try {
      RuntimeException failure = assertThrows(RuntimeException.class,
          () -> engine.search(optionalRequest(!qu), TestEngineContexts.internal()));
      if (cause instanceof InterruptedException) {
        assertInstanceOf(java.util.concurrent.CompletionException.class, failure);
        assertSame(cause, failure.getCause());
        assertTrue(Thread.currentThread().isInterrupted());
      } else if (termination.equals("expansion-cancel")) {
        assertInstanceOf(java.util.concurrent.CancellationException.class, failure);
        Throwable representedCause = failure;
        while (representedCause != cause && representedCause.getCause() != null) {
          representedCause = representedCause.getCause();
        }
        assertSame(cause, representedCause, "the original cancellation remains in the cause chain");
      } else {
        assertSame(cause, failure, "the caller/refusal signal retains its identity");
      }
      assertEquals(1, completionCalls(ai));
      assertEquals(qu ? 0 : 1, searches.get(), "termination cannot dispatch additional searches");
    } finally { Thread.interrupted(); }
  }

  @Test
  void perSourceTextRetrievalDoesNotLaunchUnconsumedExpansion() throws Exception {
    checkPerSource(null, "compare financial times sky news", "PER_SOURCE_RETRIEVAL");
  }

  @Test
  void perSourceCursorKeepsPaginationReasonPrecedence() throws Exception {
    checkPerSource("controlled-cursor", "compare financial times sky news", "PAGINATED");
  }

  @Test
  void perSourceFilenameKeepsNavigationalReasonPrecedence() throws Exception {
    checkPerSource(null, "compare financial times sky news report.pdf", "QUERY_TYPE_NAVIGATIONAL");
  }

  private void checkPerSource(String cursor, String query, String reason) throws Exception {
    optionalConfig(false);
    try (var completion = new OwnedCompletion(false)) {
      OnlineAiService ai = aiReturning(completion.root);
      KnowledgeClient client = mock(KnowledgeClient.class);
      SearchPerSourceExecutor perSource = mock(SearchPerSourceExecutor.class);
      when(perSource.execute(any(), any(), any(), anyInt(), any())).thenReturn(hits("/balanced.txt"));
      var engine = engineOver(client, ai, perSource);
      var request = new KnowledgeSearchRequest(query, 10, "text", null, cursor, List.of(),
          null, null, null, null, false, true, null);
      var response = engine.search(request, TestEngineContexts.internal());
      assertEquals(List.of("/balanced.txt"), ids(response));
      org.mockito.Mockito.verify(perSource).execute(org.mockito.ArgumentMatchers.same(client), any(),
          org.mockito.ArgumentMatchers.argThat(sources -> sources.size() == 2), anyInt(), any());
      assertEquals(0, completionCalls(ai), "the per-source branch cannot consume expansion");
      assertEquals(reason, expansionReason(response));
    }
  }

  @Test
  void fastCompletionMayUseExistingReSearchBudgetBeyondCompletionCutoffWithBaseFallback() {
    optionalConfig(false);
    OnlineAiService ai = aiReturning(CompletableFuture.completedFuture("climates"));
    KnowledgeClient client = mock(KnowledgeClient.class);
    long d = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1100);
    var searches = new AtomicInteger();
    when(client.search(any(SearchRequest.class), any())).thenAnswer(invocation -> {
      searches.incrementAndGet();
      if (!isExpanded(invocation.getArgument(0))) return hits("/base.txt");
      assertEquals(d, HeadSearchDeadline.current().orElseThrow(), "re-search retains its original D bound");
      TimeUnit.NANOSECONDS.sleep(Math.max(0,
          d - TimeUnit.MILLISECONDS.toNanos(200) - System.nanoTime()));
      return hits("/expanded.txt").toBuilder().setSearchTrace(SearchTrace.newBuilder()
          .setDegradation(TraceDegradation.newBuilder().setHybridFallbackReason(SBE))).build();
    });
    var response = HeadSearchDeadline.within(d,
        () -> engineOver(client, ai, mock(SearchPerSourceExecutor.class))
            .search(optionalRequest(true), TestEngineContexts.internal()));
    assertEquals(2, searches.get());
    assertTrue(System.nanoTime() > d - TimeUnit.MILLISECONDS.toNanos(500),
        "successful completion may enter re-search extending into the response margin");
    assertTrue(System.nanoTime() < d);
    assertEquals(List.of("/base.txt"), ids(response));
    assertEquals("TIMEOUT", expansionReason(response));
  }

  private void optionalConfig(boolean qu) {
    // 1500ms base reserve is provisional; these timings do not calibrate real profiles.
    ConfigStore.setGlobal(new ConfigStore(TestResolvedConfigHelper.fromEntries(Map.of(
        "justsearch.models.dir", modelsDir.toString(),
        "justsearch.search.query_classification.enabled", "true",
        "justsearch.qu.enabled", Boolean.toString(qu)))));
  }

  private static KnowledgeSearchRequest optionalRequest(boolean expansion) {
    return new KnowledgeSearchRequest("climate policy", 10, "text", null, null, List.of(),
        null, null, null, null, false, true,
        new PipelineConfig(true, false, false, "none", false, false, 0, expansion, false));
  }

  private static KnowledgeClient responsiveClient(AtomicInteger searches, long delayMs) {
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.search(any(SearchRequest.class), any())).thenAnswer(ignored -> {
      searches.incrementAndGet();
      TimeUnit.MILLISECONDS.sleep(delayMs);
      return hits("/base.txt");
    });
    return client;
  }

  private static long completionCalls(OnlineAiService ai) {
    return org.mockito.Mockito.mockingDetails(ai).getInvocations().stream()
        .filter(invocation -> invocation.getMethod().getName().equals("chatCompletion")).count();
  }

  private static OnlineAiService aiReturning(CompletableFuture<String> root) {
    OnlineAiService ai = mock(OnlineAiService.class);
    when(ai.isAvailable()).thenReturn(true);
    when(ai.chatCompletion(any(), anyInt(), any(), any())).thenReturn(root);
    return ai;
  }

  /** Uses the production producer owner; completion and actual-exit counters remain distinct. */
  private static final class OwnedCompletion implements AutoCloseable {
    private final AtomicInteger started = new AtomicInteger();
    private final AtomicInteger released = new AtomicInteger();
    private final AtomicInteger abandoned = new AtomicInteger();
    private final AtomicInteger interrupted = new AtomicInteger();
    private final AtomicReference<Runnable> queued = new AtomicReference<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch exited = new CountDownLatch(1);
    private final CountDownLatch sawInterrupt = new CountDownLatch(1);
    private final CompletableFuture<String> root;
    private final Thread producer;

    private OwnedCompletion(boolean running) throws Exception {
      root = EngineFutures.supplyAsync(() -> {
        started.incrementAndGet();
        entered.countDown();
        while (release.getCount() != 0) {
          try { release.await(); }
          catch (InterruptedException cancelled) { interrupted.incrementAndGet(); sawInterrupt.countDown(); }
        }
        return "{\"query\":\"climate policy\"}";
      }, queued::set, () -> { released.incrementAndGet(); exited.countDown(); }, abandoned::incrementAndGet);
      producer = running ? new Thread(queued.get(), "optional-budget-producer") : null;
      if (producer != null) {
        producer.start();
        assertTrue(entered.await(3, TimeUnit.SECONDS));
      }
    }

    private void assertAbandoned(boolean running, boolean interrupt) throws Exception {
      assertTrue(root.isCompletedExceptionally(), "original optional completion is terminal");
      assertEquals(1, abandoned.get(), "one abandonment of the original retained work");
      if (running) {
        assertEquals(1, started.get());
        assertEquals(0, released.get(), "the producer still holds its retained reference");
        if (interrupt) assertTrue(sawInterrupt.await(3, TimeUnit.SECONDS), "expansion retains cancel(true)");
        else assertEquals(0, interrupted.get(), "QU abandons without interrupting the running producer");
      } else {
        queued.get().run();
        assertEquals(0, started.get(), "queued abandoned work must not later dispatch");
        assertEquals(1, released.get());
      }
    }

    @Override public void close() throws Exception {
      release.countDown();
      root.cancel(false);
      if (producer != null) producer.join(3000);
      else queued.get().run();
      assertTrue(exited.await(3, TimeUnit.SECONDS));
      assertEquals(1, released.get(), "the one retained reference releases exactly once at actual exit");
    }
  }

  static boolean isExpanded(SearchRequest request) {
    return request.getQuerySyntax() == SearchQuerySyntax.SEARCH_QUERY_SYNTAX_LUCENE;
  }

  static SearchResponse hits(String id) {
    return SearchResponse.newBuilder().setTotalHits(1)
        .addResults(SearchResult.newBuilder().setId(id).setScore(1)).build();
  }

  @SuppressWarnings("unchecked")
  static KnowledgeSearchEngine engineOver(KnowledgeClient client) {
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    var lease = mock(KnowledgeServerBootstrap.ClientLease.class);
    when(bootstrap.publicationLock()).thenReturn(ConfigStore.global().publicationLock());
    when(bootstrap.acquireClientLease()).thenReturn(lease);
    when(lease.withClient(any())).thenAnswer(invocation ->
        ((Function<KnowledgeClient, ?>) invocation.getArgument(0)).apply(client));
    // The expansion model answers at once with a valid variant, so the re-search always runs.
    OnlineAiService ai = mock(OnlineAiService.class);
    when(ai.isAvailable()).thenReturn(true);
    when(ai.chatCompletion(any(), anyInt(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture("quokkas"));
    return new KnowledgeSearchEngine(bootstrap, mock(SearchPerSourceExecutor.class), ai, null,
        ConfigStore.global());
  }

  @SuppressWarnings("unchecked")
  private static KnowledgeSearchEngine engineOver(
      KnowledgeClient client, OnlineAiService ai, SearchPerSourceExecutor perSource) {
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    var lease = mock(KnowledgeServerBootstrap.ClientLease.class);
    when(bootstrap.publicationLock()).thenReturn(ConfigStore.global().publicationLock());
    when(bootstrap.acquireClientLease()).thenReturn(lease);
    when(lease.withClient(any())).thenAnswer(invocation ->
        ((Function<KnowledgeClient, ?>) invocation.getArgument(0)).apply(client));
    return new KnowledgeSearchEngine(bootstrap, perSource, ai, null, ConfigStore.global());
  }

  /** Sparse plus dense, expansion on, no rerankers: a SIMPLE query that expands. */
  static KnowledgeSearchRequest expandingRequest() {
    return new KnowledgeSearchRequest("quokka", 10, null, null, null, List.of(), null, null, null,
        null, false, true, new PipelineConfig(true, true, false, "rrf", false, false, 0, true, false));
  }

  private static List<String> ids(KnowledgeSearchResponse response) {
    return response.results().stream().map(KnowledgeSearchResponse.Hit::id).toList();
  }

  private static String expansionReason(KnowledgeSearchResponse response) {
    return response.searchTrace().stages().stream()
        .filter(stage -> stage.id()
            == io.justsearch.app.api.knowledge.SearchTrace.StageId.EXPANSION)
        .findFirst().orElseThrow().reason();
  }
}
