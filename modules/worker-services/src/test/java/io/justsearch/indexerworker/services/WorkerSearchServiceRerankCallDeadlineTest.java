/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.core.execution.InferenceRequest;
import io.justsearch.ipc.RerankRequest;
import io.justsearch.ipc.RerankResponse;
import io.justsearch.ort.SessionAcquisitionRequest;
import io.justsearch.reranker.CrossEncoderReranker;
import io.justsearch.reranker.RerankSkipCause;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The worker's rerank RPC answers by its call's deadline (Spec D §1.5; verify d14b, finding F1). A
 * search abandons its rerank at the search's deadline; a rerank call that went on would hold the
 * search's engine admission until it returned. So the call neither waits for loading models past
 * its deadline nor lets its native run hold it there.
 */
class WorkerSearchServiceRerankCallDeadlineTest
    extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  private static final long CALL_BUDGET_MS = 400L;
  private static final long SLACK_MS = 1_000L;

  @TempDir Path tempDir;
  private RunningRuntime lifecycle;
  private WorkerSearchService service;

  @BeforeEach
  void setUp() throws Exception {
    System.clearProperty("justsearch.config");
    lifecycle = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(tempDir)
        .withExecutorRegistrations(testLuceneExecutors()).open();
    service = new WorkerSearchService(lifecycle);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (lifecycle != null) lifecycle.close();
  }

  @Test
  void aRerankWaitingForLoadingModelsAnswersByItsCallDeadline() {
    var reranker = mock(CrossEncoderReranker.class);
    service.setSearchReranker(reranker);
    CountDownLatch loading = new CountDownLatch(1);
    service.setModelReadyLatchSupplier(() -> loading);

    long startNs = System.nanoTime();
    RerankResponse response = assertTimeoutPreemptively(
        Duration.ofMillis(CALL_BUDGET_MS + 5_000L),
        () -> service.rerank(request(), callWithin(CALL_BUDGET_MS)),
        "the rerank call waited for the loading models past its deadline");
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);

    assertTrue(elapsedMs <= CALL_BUDGET_MS + SLACK_MS,
        "the rerank call answers by its deadline; took " + elapsedMs + " ms");
    assertTrue(response.getSkipped(), "no rerank ran while the models were loading");
    assertEquals("SEARCH_DEADLINE_EXCEEDED", response.getSkipReason());
  }

  @Test
  void theRerankAsksTheRerankerToReleaseItAtTheCallDeadline() {
    var reranker = mock(CrossEncoderReranker.class);
    AtomicReference<InferenceRequest> seen = new AtomicReference<>();
    when(reranker.rerank(anyString(), anyList(), anyLong(), any())).thenAnswer(invocation -> {
      seen.set(invocation.getArgument(3));
      return new CrossEncoderReranker.RerankedResult(
          List.of(0), List.of(0.5f), RerankSkipCause.NONE, 1);
    });
    service.setSearchReranker(reranker);
    CallContext call = callWithin(CALL_BUDGET_MS);

    RerankResponse response = service.rerank(request(), call);

    SessionAcquisitionRequest native_ = SessionAcquisitionRequest.from(seen.get());
    assertTrue(native_.releaseCallerAtDeadline(),
        "the reranker is asked to release the rerank call at its deadline");
    assertEquals(call.deadlineNanos(), native_.deadlineNanos(), "at the call's own deadline");
    assertEquals(List.of(0), response.getSortedIndicesList(), "a rerank in time is unchanged");
  }

  private static RerankRequest request() {
    return RerankRequest.newBuilder().setQuery("query").addDocumentTexts("doc")
        .setDeadlineMs(30_000).build();
  }

  private static CallContext callWithin(long budgetMs) {
    var none = CallContext.none();
    return new CallContext(null, null, () -> false, none.engineContext(), none.provenance(),
        none.childLifetime(), System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs));
  }
}
