/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static io.justsearch.indexerworker.services.WorkerSearchServiceRagBudgetTest.REQUEST_BUDGET;
import static io.justsearch.indexerworker.services.WorkerSearchServiceRagBudgetTest.openIndex;
import static io.justsearch.indexerworker.services.WorkerSearchServiceRagBudgetTest.question;
import static io.justsearch.indexerworker.services.WorkerSearchServiceRagBudgetTest.restoreProperties;
import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.foreground;
import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.gpuHandle;
import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.holdBackground;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.indexerworker.embed.NoOpEmbeddingProvider;
import io.justsearch.indexerworker.services.WorkerSearchServiceRagBudgetTest.HandleBackedEmbeddings;
import io.justsearch.ipc.RetrieveContextResponse;
import io.justsearch.ort.SessionHandle;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Spec D §5.1: the RAG retrieval carries the cause of a semantic budget miss in {@code
 * retrieval_mode_cause}, exactly when its reason is {@code SEMANTIC_BUDGET_EXCEEDED} (S2: the
 * reason per leg reaches the RAG metadata). Same seam as {@link WorkerSearchServiceRagBudgetTest}.
 */
final class RagRetrievalCauseTest
    extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  private final Map<String, String> previousProperties = new HashMap<>();
  private final CountDownLatch releaseOnExit = new CountDownLatch(1);
  private RunningRuntime lifecycle;

  @AfterEach
  void closeIndex() throws Exception {
    releaseOnExit.countDown();
    if (lifecycle != null) lifecycle.close();
    restoreProperties(previousProperties);
  }

  @Test
  void aHeldEmbeddingDeviceIsContended() throws Exception {
    lifecycle = openIndex(previousProperties, testLuceneExecutors(), "position", "Hello world, a contended retrieval");
    try (SessionHandle embeddingHandle = gpuHandle("rag-cause-embedding");
        SessionHandle.Lease _ = holdBackground(embeddingHandle)) {
      var service =
          new WorkerSearchService(lifecycle, new HandleBackedEmbeddings(embeddingHandle, false));

      RetrieveContextResponse response =
          service.retrieveContext(question("Hello"), foreground(REQUEST_BUDGET));

      assertEquals("SEMANTIC_BUDGET_EXCEEDED", response.getRetrievalModeReason());
      assertEquals("contended", response.getRetrievalModeCause());
    }
  }

  @Test
  void modelsStillLoadingIsLoading() throws Exception {
    lifecycle = openIndex(previousProperties, testLuceneExecutors(), "position", "Hello world, a loading retrieval");
    var service = new WorkerSearchService(lifecycle, NoOpEmbeddingProvider.starting());
    service.setModelReadyLatchSupplier(() -> releaseOnExit);

    RetrieveContextResponse response =
        service.retrieveContext(question("Hello"), foreground(REQUEST_BUDGET));

    assertEquals("SEMANTIC_BUDGET_EXCEEDED", response.getRetrievalModeReason());
    assertEquals("loading", response.getRetrievalModeCause());
  }

  @Test
  void anyOtherReasonCarriesNoCause() throws Exception {
    lifecycle = openIndex(previousProperties, testLuceneExecutors(), "position", "Hello world, a ready retrieval");
    var service = new WorkerSearchService(lifecycle, NoOpEmbeddingProvider.INSTANCE);

    RetrieveContextResponse response =
        service.retrieveContext(question("Hello"), foreground(REQUEST_BUDGET));

    assertEquals("NO_EMBEDDING_SERVICE", response.getRetrievalModeReason());
    assertEquals("", response.getRetrievalModeCause());
  }
}
