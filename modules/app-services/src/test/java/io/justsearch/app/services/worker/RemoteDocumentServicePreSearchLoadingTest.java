/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.RetrieveContextParams;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Spec D (item 5a) R1: an open retrieval whose pre-search's semantic leg missed its sub-budget with
 * the cause {@code loading} sends its retrieval with {@code semantic_loading_observed}, so the
 * worker does not wait for the models a second time. Any other cause, or none, leaves the field
 * unset: a contended device may be free by the time the retrieval runs.
 *
 * <p>Real seam: the production {@link RemoteDocumentService} over the production {@link
 * KnowledgeClient} request builders ({@link TestKnowledgeClient}); the worker side records the
 * request it received.
 */
final class RemoteDocumentServicePreSearchLoadingTest {

  private ConfigStore previousConfig;
  private KnowledgeClient client;
  private final RemoteDocumentServiceOpenRetrievalDeadlineTest.RecordingCalls calls =
      new RemoteDocumentServiceOpenRetrievalDeadlineTest.RecordingCalls();

  @BeforeEach
  void setUp() {
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
  void aPreSearchThatMissedOnLoadingModelsTellsTheRetrievalNotToWaitAgain() throws Exception {
    calls.preSearchCause = "loading";

    openRetrieval();

    assertTrue(calls.retrieval.get().getSemanticLoadingObserved(),
        "the retrieval request must say the pre-search already waited on loading models");
  }

  @Test
  void aPreSearchThatMissedForAnotherCauseLetsTheRetrievalWait() throws Exception {
    calls.preSearchCause = "contended";

    openRetrieval();

    assertFalse(calls.retrieval.get().getSemanticLoadingObserved(),
        "a contended device may be free for the retrieval");
  }

  @Test
  void aPreSearchWithoutAMissLetsTheRetrievalWait() throws Exception {
    openRetrieval();

    assertFalse(calls.retrieval.get().getSemanticLoadingObserved());
  }

  private void openRetrieval() throws Exception {
    RemoteDocumentServiceOpenRetrievalDeadlineTest.retrieve(
        client, RetrieveContextParams.of("what is the policy?", 5, 4096));
  }
}
