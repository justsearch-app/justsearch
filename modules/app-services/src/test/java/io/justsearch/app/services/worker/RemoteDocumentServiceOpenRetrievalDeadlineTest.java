/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.RetrieveContextParams;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.ipc.RetrieveContextRequest;
import io.justsearch.ipc.RetrieveContextResponse;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchResult;
import io.justsearch.ipc.SearchTrace;
import io.justsearch.ipc.TraceDegradation;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Spec D (item 5a) R1, the open retrieval as one answer: {@link RemoteDocumentService}'s
 * open-retrieval path (no document ids) is a pre-search and then a context retrieval, and both run
 * inside one request deadline {@code D} the head fixes when the answer starts, so the worker carves
 * each step's semantic sub-budget from what is left of it ({@link HeadSearchDeadline}). A pre-search
 * whose semantic leg missed with the cause {@code loading} tells the retrieval not to wait for the
 * models again ({@code RemoteDocumentServicePreSearchLoadingTest}). A scoped retrieval (document
 * ids given) is one step and keeps its own budget.
 *
 * <p>Real seam: the production {@link RemoteDocumentService} over the production {@link
 * KnowledgeClient} request builders ({@link TestKnowledgeClient}); the worker side records what each
 * call carried. Uses only API that exists before the change, so each check runs on the
 * before-state.
 */
final class RemoteDocumentServiceOpenRetrievalDeadlineTest {

  private ConfigStore previousConfig;
  private KnowledgeClient client;
  private final RecordingCalls calls = new RecordingCalls();

  @BeforeEach
  void setUp() {
    // The pre-search's hybrid preset reads the global ConfigStore (RerankerConfig.fromEnv).
    previousConfig = ConfigStore.globalOrNull();
    TestResolvedConfigHelper.storeWithDefaults();
    client = new TestKnowledgeClient(new io.justsearch.core.execution.TestEngineExecutors(), calls);
  }

  @AfterEach
  void tearDown() {
    if (client != null) client.close();
    TestResolvedConfigHelper.restoreGlobal(previousConfig);
  }

  @Test
  void bothStepsOfAnOpenRetrievalRunInsideOneHeadDeadline() throws Exception {
    long before = System.nanoTime();
    retrieve(RetrieveContextParams.of("what is the policy?", 5, 4096));
    long after = System.nanoTime();

    OptionalLong searchDeadline = calls.searchDeadline.get();
    OptionalLong retrievalDeadline = calls.retrievalDeadline.get();
    assertTrue(searchDeadline != null && searchDeadline.isPresent(),
        "the pre-search must run inside the answer's head deadline");
    assertTrue(retrievalDeadline != null && retrievalDeadline.isPresent(),
        "the retrieval must run inside the answer's head deadline");
    assertEquals(searchDeadline.getAsLong(), retrievalDeadline.getAsLong(),
        "both steps share one deadline");
    long budget = TimeUnit.MILLISECONDS.toNanos(client.requestDeadlineMs());
    long deadline = searchDeadline.getAsLong();
    assertTrue(deadline - (before + budget) >= 0 && (after + budget) - deadline >= 0,
        "the deadline is the client's request deadline from the start of the answer");
  }

  @Test
  void aScopedRetrievalIsOneStepAndKeepsItsOwnBudget() throws Exception {
    retrieve(
        RetrieveContextParams.of(
            "what is the policy?", 5, 4096, Set.of("doc-a"), List.of(), List.of()));

    assertEquals(null, calls.searchDeadline.get(), "a scoped retrieval runs no pre-search");
    assertFalse(calls.retrievalDeadline.get().isPresent(),
        "a single-step retrieval keeps its category budget");
  }

  private void retrieve(RetrieveContextParams params) throws Exception {
    retrieve(client, params);
  }

  static void retrieve(KnowledgeClient client, RetrieveContextParams params) throws Exception {
    new RemoteDocumentService(Runnable::run, Runnable::run, () -> client)
        .retrieveContext(params, TestEngineContexts.internal())
        .toCompletableFuture()
        .get(6, TimeUnit.SECONDS);
  }

  /** Records the head deadline each worker call ran inside, and what the retrieval carried. */
  static final class RecordingCalls extends TestKnowledgeClient.SearchCalls {
    final AtomicReference<OptionalLong> searchDeadline = new AtomicReference<>();
    final AtomicReference<OptionalLong> retrievalDeadline = new AtomicReference<>();
    final AtomicReference<RetrieveContextRequest> retrieval = new AtomicReference<>();
    volatile String preSearchCause = "";

    @Override
    public SearchResponse search(SearchRequest request) {
      searchDeadline.set(HeadSearchDeadline.current());
      var response =
          SearchResponse.newBuilder()
              .addResults(SearchResult.newBuilder().setId("doc-a").putFields("path", "doc-a"));
      if (!preSearchCause.isEmpty()) {
        response.setSearchTrace(
            SearchTrace.newBuilder()
                .setDegradation(
                    TraceDegradation.newBuilder()
                        .setHybridFallback(true)
                        .setHybridFallbackReason("SEMANTIC_BUDGET_EXCEEDED")
                        .setHybridFallbackCause(preSearchCause)));
      }
      return response.build();
    }

    @Override
    public RetrieveContextResponse retrieveContext(RetrieveContextRequest request) {
      retrievalDeadline.set(HeadSearchDeadline.current());
      retrieval.set(request);
      return RetrieveContextResponse.newBuilder().build();
    }
  }
}
