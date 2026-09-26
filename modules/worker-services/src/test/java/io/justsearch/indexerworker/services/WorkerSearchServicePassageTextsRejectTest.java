/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.ipc.MatchCitationsRequest;
import io.justsearch.ipc.MatchCitationsResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tempdoc 836 §5.9 — a {@code passage_texts} length that is neither 0 nor {@code
 * chunk_doc_ids.size()} is rejected, never absorbed.
 *
 * <p>The failure this forbids is the {@code Math.min} idiom already present for the other two
 * parallel arrays being extended to a third: a silently-shortened passage list would mis-align text
 * to sources, which is the F-049 mis-targeting class re-entering through the back door.
 */
@DisplayName("WorkerSearchService — passage_texts length contract")
class WorkerSearchServicePassageTextsRejectTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  @TempDir Path tempDir;
  private RunningRuntime lifecycle;

  @BeforeEach
  void setUp() throws Exception {
    System.clearProperty("justsearch.config");
    lifecycle = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(tempDir).withExecutorRegistrations(testLuceneExecutors()).open();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (lifecycle != null) {
      lifecycle.close();
    }
  }

  @ParameterizedTest(name = "{0} sources, {1} passage_texts")
  @CsvSource({"2, 1", "2, 3", "1, 2", "3, 2"})
  @DisplayName("a mismatched passage_texts length fails with INVALID_ARGUMENT")
  void mismatchedLengthIsRejected(int sourceCount, int passageCount) {
    var builder = MatchCitationsRequest.newBuilder().setAnswerText("A sentence about pagination.");
    for (int i = 0; i < sourceCount; i++) {
      builder.addChunkDocIds("doc-" + i).addChunkIndices(0);
    }
    for (int i = 0; i < passageCount; i++) {
      builder.addPassageTexts("passage " + i);
    }

    Outcome outcome = call(builder.build());

    assertNull(outcome.response, "a mis-aligned request must not be scored at all");
    assertNotNull(outcome.error, "the caller must be told, not silently given a truncated result");
    assertEquals(WorkerServiceException.Status.INVALID_ARGUMENT, outcome.error.status());
  }

  @Test
  @DisplayName("an exactly-matching length is accepted")
  void matchingLengthIsAccepted() {
    MatchCitationsRequest request =
        MatchCitationsRequest.newBuilder()
            .setAnswerText("A sentence about pagination.")
            .addChunkDocIds("doc-0")
            .addChunkIndices(0)
            .addChunkDocIds("doc-1")
            .addChunkIndices(4)
            .addPassageTexts("passage zero")
            .addPassageTexts("")
            .build();

    Outcome outcome = call(request);

    assertNull(outcome.error, "a blank entry is a valid 'look this one up', not a length error");
    assertNotNull(outcome.response);
  }

  @Test
  @DisplayName("an empty passage_texts list is accepted for any source count")
  void emptyListIsAccepted() {
    MatchCitationsRequest request =
        MatchCitationsRequest.newBuilder()
            .setAnswerText("A sentence about pagination.")
            .addChunkDocIds("doc-0")
            .addChunkIndices(0)
            .addChunkDocIds("doc-1")
            .addChunkIndices(4)
            .build();

    Outcome outcome = call(request);

    assertNull(outcome.error);
    assertNotNull(outcome.response);
  }

  private record Outcome(MatchCitationsResponse response, WorkerServiceException error) {}

  private Outcome call(MatchCitationsRequest request) {
    WorkerSearchService service = new WorkerSearchService(lifecycle);
    try {
      return new Outcome(service.matchCitations(request, CallContext.none()), null);
    } catch (WorkerServiceException e) {
      return new Outcome(null, e);
    }
  }
}
