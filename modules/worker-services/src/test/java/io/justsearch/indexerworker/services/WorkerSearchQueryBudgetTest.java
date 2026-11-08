/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.google.protobuf.UnknownFieldSet;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.ipc.RerankRequest;
import io.justsearch.ipc.RerankResponse;
import io.justsearch.reranker.CrossEncoderReranker;
import io.justsearch.reranker.RerankSkipCause;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** C5 H3/P1/P2/P3. Requests use the existing protobuf wire interface for assertion fail-before. */
final class WorkerSearchQueryBudgetTest
    extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {
  @TempDir Path temp;
  private RunningRuntime runtime;
  private WorkerSearchService service;

  @BeforeEach
  void open() throws Exception {
    runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(temp)
        .withExecutorRegistrations(testLuceneExecutors()).open();
    service = new WorkerSearchService(runtime);
  }

  @AfterEach
  void close() throws Exception {
    if (runtime != null) runtime.close();
  }

  @Test
  void optedInLongQuerySkipsOnceWithoutScoringAndNextShortQueryStillRuns() throws Exception {
    var counts = new AtomicInteger();
    var runs = new AtomicInteger();
    service.setSearchReranker(reranker(counts, runs, () -> {}));

    RerankResponse skipped = assertDoesNotThrow(() -> service.rerank(request("long", true, true), CallContext.none()));
    assertTrue(skipped.getSkipped());
    assertEquals("QUERY_TOO_LONG", skipped.getSkipReason());
    assertEquals(0, skipped.getScoresCount());
    assertEquals(0, skipped.getSortedIndicesCount());
    assertEquals(1, counts.get());
    assertEquals(0, runs.get(), "the policy runs before session acquisition/inference");

    var shortResult = service.rerank(request("short", true, true), CallContext.none());
    assertFalse(shortResult.getSkipped());
    assertEquals(List.of(0.25f, 0.75f), shortResult.getScoresList());
    assertEquals(List.of(1, 0), shortResult.getSortedIndicesList());
    assertEquals(2, counts.get());
    assertEquals(1, runs.get());
  }

  @Test
  void defaultOffAndEmptyCandidatesDoNotCountAndRetainExistingRerankBehavior() throws Exception {
    var counts = new AtomicInteger();
    var runs = new AtomicInteger();
    service.setSearchReranker(reranker(counts, runs, () -> {}));
    assertFalse(service.rerank(request("long", false, true), CallContext.none()).getSkipped());
    assertFalse(service.rerank(request("long", true, false), CallContext.none()).getSkipped());
    assertEquals(0, counts.get());
    assertEquals(2, runs.get());
  }

  @Test
  void cancellationDuringCountCannotBecomePolicySkipOrInference() throws Exception {
    var cancelled = new AtomicBoolean();
    var runs = new AtomicInteger();
    var counts = new AtomicInteger();
    service.setSearchReranker(reranker(counts, runs, () -> cancelled.set(true)));
    var none = CallContext.none();
    var call = new CallContext(null, null, cancelled::get, none.engineContext(), none.provenance(),
        none.childLifetime(), none.inferenceRequest().deadlineNanos());
    assertThrows(CancellationException.class, () -> service.rerank(request("long", true, true), call));
    assertEquals(1, counts.get());
    assertEquals(0, runs.get());
  }

  @Test
  void expiryDuringCountCannotBecomeSuccessfulPolicySkip() throws Exception {
    var expired = new AtomicBoolean();
    var counts = new AtomicInteger();
    var runs = new AtomicInteger();
    service.setSearchReranker(reranker(counts, runs, () -> expired.set(true)));
    var none = CallContext.none();
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(200);
    var call = new CallContext(null, null, () -> {
      if (expired.get()) {
        while (System.nanoTime() - deadline <= 0) java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
      }
      return false;
    }, none.engineContext(), none.provenance(), none.childLifetime(), deadline);
    var failure = assertThrows(WorkerServiceException.class,
        () -> service.rerank(request("long", true, true), call));
    assertEquals(WorkerServiceException.Status.DEADLINE_EXCEEDED, failure.status());
    assertEquals(1, counts.get());
    assertEquals(0, runs.get());
  }

  @Test
  void selectedRerankerDoesNotChangeBetweenCountAndInference() throws Exception {
    var aCounts = new AtomicInteger();
    var aRuns = new AtomicInteger();
    var bCounts = new AtomicInteger();
    var bRuns = new AtomicInteger();
    var b = reranker(bCounts, bRuns, () -> {});
    service.setSearchReranker(reranker(aCounts, aRuns, () -> service.setSearchReranker(b)));
    assertFalse(service.rerank(request("short", true, true), CallContext.none()).getSkipped());
    assertEquals(1, aCounts.get());
    assertEquals(1, aRuns.get());
    assertEquals(0, bCounts.get());
    assertEquals(0, bRuns.get());
  }

  @Test
  void coldOwnerKeepsLoadingReasonAndLaterWarmOwnerEnablesPolicy() throws Exception {
    var counts = new AtomicInteger();
    var reranker = reranker(counts, new AtomicInteger(), () -> {});
    org.mockito.Mockito.when(reranker.isQueryWarm()).thenReturn(false, true);
    org.mockito.Mockito.doReturn(new CrossEncoderReranker.RerankedResult(
        List.of(), List.of(), RerankSkipCause.MODEL_LOADING, 0))
        .when(reranker).rerank(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.any());
    service.setSearchReranker(reranker);
    assertEquals("MODEL_LOADING", service.rerank(request("long", true, true), CallContext.none()).getSkipReason());
    assertEquals(0, counts.get());
    assertEquals("QUERY_TOO_LONG", service.rerank(request("long", true, true), CallContext.none()).getSkipReason());
    assertEquals(1, counts.get());
    org.mockito.Mockito.verify(reranker).rerank(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void genuineCountingFailureKeepsWorkerFailureSemantics() throws Exception {
    var runs = new AtomicInteger();
    service.setSearchReranker(reranker(new AtomicInteger(), runs, () -> {
      throw new IllegalStateException("native tokenizer failed");
    }));
    var failure = assertThrows(WorkerServiceException.class,
        () -> service.rerank(request("long", true, true), CallContext.none()));
    assertEquals(WorkerServiceException.Status.INTERNAL, failure.status());
    assertEquals(0, runs.get());
  }

  private static CrossEncoderReranker reranker(AtomicInteger counts, AtomicInteger runs, Runnable duringCount) {
    // Matching the method name lets the same public wire checks run on the committed before tree,
    // where the read-only primitive does not exist yet. No production API is substituted there.
    return mock(CrossEncoderReranker.class, invocation -> {
      if (invocation.getMethod().getName().equals("isQueryWarm")) {
        return true;
      }
      if (invocation.getMethod().getName().equals("exceedsSearchQueryBudget")) {
        counts.incrementAndGet();
        duringCount.run();
        return invocation.getArgument(0).equals("long");
      }
      if (invocation.getMethod().getName().equals("rerank")) {
        runs.incrementAndGet();
        return new CrossEncoderReranker.RerankedResult(List.of(1, 0), List.of(0.25f, 0.75f), RerankSkipCause.NONE, 1);
      }
      return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
    });
  }

  private static RerankRequest request(String query, boolean policy, boolean candidates) throws Exception {
    var builder = RerankRequest.newBuilder().setQuery(query).setDeadlineMs(5000);
    if (candidates) builder.addAllDocumentTexts(List.of("first", "second"));
    if (policy) builder.setUnknownFields(UnknownFieldSet.newBuilder().addField(5,
        UnknownFieldSet.Field.newBuilder().addVarint(1).build()).build());
    return RerankRequest.parseFrom(builder.build().toByteArray());
  }
}
