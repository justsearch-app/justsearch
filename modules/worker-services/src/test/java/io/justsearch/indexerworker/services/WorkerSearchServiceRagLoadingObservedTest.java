/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.foreground;
import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.gpuHandle;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.indexerworker.embed.NoOpEmbeddingProvider;
import io.justsearch.indexerworker.services.WorkerSearchServiceRagBudgetTest.HandleBackedEmbeddings;
import io.justsearch.ipc.RetrieveContextRequest;
import io.justsearch.ipc.RetrieveContextResponse;
import io.justsearch.ort.SessionHandle;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Spec D (item 5a) R1, the second step of an open answer: a RAG retrieval whose request says the
 * answer's pre-search already waited out its semantic sub-budget on loading models ({@code
 * semantic_loading_observed}) does not wait for the models again while they are still loading,
 * and answers with BM25 context, the reason {@code SEMANTIC_BUDGET_EXCEEDED} and the cause {@code
 * loading} (S1, S2). Once the models are ready, the field changes nothing.
 *
 * <p>Real seam: {@link WorkerSearchService#retrieveContext}, the production entry point, over a real
 * Lucene index with the production RAG pipeline ({@code rag.retrieve.mode} auto).
 */
final class WorkerSearchServiceRagLoadingObservedTest
    extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  /** The request deadline; without the field a loading retrieval waits until D - 750 ms. */
  private static final Duration REQUEST_BUDGET = Duration.ofSeconds(3);

  private final Map<String, String> previousProperties = new HashMap<>();
  private final CountDownLatch releaseOnExit = new CountDownLatch(1);
  private RunningRuntime lifecycle;

  @AfterEach
  void closeIndex() throws Exception {
    releaseOnExit.countDown();
    if (lifecycle != null) lifecycle.close();
    WorkerSearchServiceRagBudgetTest.restoreProperties(previousProperties);
  }

  @Test
  void loadingObservedByThePreSearchSkipsTheModelsWait() throws Exception {
    lifecycle = openIndex();
    var service = new WorkerSearchService(lifecycle, NoOpEmbeddingProvider.INSTANCE);
    service.setModelReadyLatchSupplier(() -> releaseOnExit);
    RetrieveContextRequest request = loadingObserved();

    long start = System.nanoTime();
    RetrieveContextResponse response =
        assertTimeoutPreemptively(
            REQUEST_BUDGET, () -> service.retrieveContext(request, foreground(REQUEST_BUDGET)));
    long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

    // Without the field this retrieval waits for the models until D - 750 ms (2 250 ms here).
    assertTrue(elapsedMs < 1_000L, () -> "waited " + elapsedMs + " ms for the models again");
    assertEquals("SEMANTIC_BUDGET_EXCEEDED", response.getRetrievalModeReason());
    assertEquals("loading", response.getRetrievalModeCause());
    assertEquals("BM25", response.getRetrievalMode());
    assertTrue(response.getChunksCount() >= 1, "BM25 context remains available");
    assertTrue(response.getContext().contains("Hello"), response.getContext());
  }

  @Test
  void loadingObservedAfterTheModelsBecameReadyStillEmbedsTheQuestion() throws Exception {
    lifecycle = openIndex();
    try (SessionHandle embeddingHandle = gpuHandle("loading-observed-ready")) {
      var embeddings = new HandleBackedEmbeddings(embeddingHandle, true);
      var service = new WorkerSearchService(lifecycle, embeddings);
      service.setModelReadyLatchSupplier(() -> new CountDownLatch(0));
      RetrieveContextRequest request = loadingObserved();

      RetrieveContextResponse response =
          assertDoesNotThrow(() -> service.retrieveContext(request, foreground(REQUEST_BUDGET)));

      assertEquals(1, embeddings.queryCalls.get(), "the question was embedded");
      assertNotEquals("SEMANTIC_BUDGET_EXCEEDED", response.getRetrievalModeReason());
      assertEquals("", response.getRetrievalModeCause());
    }
  }

  private static RetrieveContextRequest loadingObserved() {
    return WorkerSearchServiceRagBudgetTest.question("Hello").toBuilder()
        .setSemanticLoadingObserved(true)
        .build();
  }

  private RunningRuntime openIndex() throws Exception {
    return WorkerSearchServiceRagBudgetTest.openIndex(
        previousProperties, testLuceneExecutors(), "position",
        "Hello world from the retrieval budget test");
  }
}
