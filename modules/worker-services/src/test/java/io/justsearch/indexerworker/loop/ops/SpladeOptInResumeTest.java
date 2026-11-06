/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyList;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

final class SpladeOptInResumeTest extends LuceneExecutorTestBase {
  @TempDir Path temp;

  @Test void restartResumesPendingParentsAndRetainsTerminalStatesAndRetryBudgets() throws Exception {
    try (var runtime = open()) {
      seed(runtime, "pending", "PENDING", 1);
      seed(runtime, "empty-output", "PENDING", 0);
      seed(runtime, "complete", "COMPLETED", 0);
      seed(runtime, "complete-empty", "COMPLETED_EMPTY", 0);
      seed(runtime, "failed", "FAILED", SchemaFields.SPLADE_MAX_RETRIES);
      seed(runtime, "last-attempt", "PENDING", SchemaFields.SPLADE_MAX_RETRIES - 1);
      commit(runtime);
      process(runtime, null);
      assertEquals("PENDING", value(runtime, "pending", SchemaFields.SPLADE_STATUS));
      assertEquals("1", value(runtime, "pending", SchemaFields.SPLADE_RETRY_COUNT));
    }
    var encoder = mock(SpladeEncoder.class);
    when(encoder.encodeBatch(anyList())).thenAnswer(call -> {
      List<String> texts = call.getArgument(0);
      if (texts.contains("last-attempt")) throw new IllegalArgumentException("fixture inference failure");
      return texts.stream().map(text -> text.equals("empty-output") ? Map.<String, Float>of()
          : Map.of("term", 1f)).toList();
    });
    // Individual retry fallback uses the same controlled fixture.
    when(encoder.encode(anyString(), any(io.justsearch.core.execution.InferenceRequest.class))).thenAnswer(call -> {
      String text = call.getArgument(0);
      if (text.equals("last-attempt")) throw new IllegalArgumentException("fixture inference failure");
      return text.equals("empty-output") ? Map.<String, Float>of() : Map.of("term", 1f);
    });
    try (var runtime = open()) {
      process(runtime, encoder);
      commit(runtime);
      assertEquals("COMPLETED", value(runtime, "pending", SchemaFields.SPLADE_STATUS));
      assertEquals("COMPLETED_EMPTY", value(runtime, "empty-output", SchemaFields.SPLADE_STATUS));
      assertEquals("COMPLETED_EMPTY", value(runtime, "complete-empty", SchemaFields.SPLADE_STATUS));
      assertEquals("FAILED", value(runtime, "failed", SchemaFields.SPLADE_STATUS));
      assertEquals(Integer.toString(SchemaFields.SPLADE_MAX_RETRIES), value(runtime, "failed", SchemaFields.SPLADE_RETRY_COUNT));
      assertEquals("FAILED", value(runtime, "last-attempt", SchemaFields.SPLADE_STATUS));
      assertEquals(Integer.toString(SchemaFields.SPLADE_MAX_RETRIES), value(runtime, "last-attempt", SchemaFields.SPLADE_RETRY_COUNT));
      assertEquals("0", value(runtime, "pending", SchemaFields.SPLADE_RETRY_COUNT), "successful retry keeps the existing reset rule");
      assertEquals(4, runtime.indexCountOps().querySpladeFeatureCounts().completed());
      assertTrue(runtime.indexCountOps().querySpladeFeatureCounts().coveragePercent() < 100);
      assertEquals(1, runtime.indexCountOps().countByField(SchemaFields.SPLADE, "retained"),
          "completed rows reuse their existing sparse postings");
    }
    verify(encoder).encodeBatch(argThat(texts -> new java.util.HashSet<>(texts)
        .equals(java.util.Set.of("pending", "empty-output", "last-attempt"))));
    for (String terminal : List.of("complete", "complete-empty", "failed")) {
      verify(encoder, never()).encode(eq(terminal), any(io.justsearch.core.execution.InferenceRequest.class));
    }
  }

  @Test void successfulCorpusReachesFullCoverageAndReusesCompletedEmpty() throws Exception {
    var encoder = mock(SpladeEncoder.class);
    when(encoder.encodeBatch(anyList())).thenAnswer(call -> {
      List<String> texts = call.getArgument(0);
      return texts.stream().map(text -> text.equals("empty-output") ? Map.<String, Float>of()
          : Map.of("term", 1f)).toList();
    });
    try (var runtime = open()) {
      seed(runtime, "pending", "PENDING", 0);
      seed(runtime, "empty-output", "PENDING", 0);
      commit(runtime);
      process(runtime, encoder);
      commit(runtime);
      assertEquals(100.0, runtime.indexCountOps().querySpladeFeatureCounts().coveragePercent());
      assertEquals("COMPLETED_EMPTY", value(runtime, "empty-output", SchemaFields.SPLADE_STATUS));
      process(runtime, encoder);
      verify(encoder, times(1)).encodeBatch(anyList());
    }
  }

  private RunningRuntime open() {
    var runtime = IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog())
        .atPath(temp).withExecutorRegistrations(testLuceneExecutors()).open();
    runtime.commitOps().stopCommitTimer();
    return runtime;
  }

  private static void seed(RunningRuntime runtime, String id, String status, int retries) {
    var fields = new HashMap<String, Object>(Map.of(SchemaFields.DOC_ID, id, SchemaFields.DOC_UID,
        id + "-uid", SchemaFields.PATH, "/" + id, SchemaFields.CONTENT, id,
        SchemaFields.SPLADE_STATUS, status, SchemaFields.SPLADE_RETRY_COUNT, retries));
    if (status.equals("COMPLETED")) fields.put(SchemaFields.SPLADE, Map.of("retained", 1f));
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
  }

  private static String value(RunningRuntime runtime, String id, String field) {
    return runtime.documentFieldOps().getDocumentField(id, field);
  }

  private static void process(RunningRuntime runtime, SpladeEncoder encoder) {
    LaneBackfillTestSupport.runSparse(new SpladeBackfillOps.BackfillContext(runtime.documentFieldOps(),
        runtime.indexingCoordinator(),
        IndexingPacing.unthrottled(),
        () -> encoder,
        () -> true,
        100,
        false,
        LoggerFactory.getLogger(SpladeOptInResumeTest.class), () -> true));
  }

  private static void commit(RunningRuntime runtime) {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }
}
