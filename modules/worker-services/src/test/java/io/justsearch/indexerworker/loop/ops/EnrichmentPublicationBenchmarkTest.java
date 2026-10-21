/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** Fixed real-Lucene fixture: measure only the publication call, not inference or commits. */
final class EnrichmentPublicationBenchmarkTest extends LuceneExecutorTestBase {
  @TempDir Path directory;

  @Test void fixedBatchPublication() {
    try (var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(384))
        .atPath(directory).withExecutorRegistrations(testLuceneExecutors()).open()) {
      runtime.commitOps().stopCommitTimer();
      var coordinator = mock(IndexingCoordinator.class, withSettings().spiedInstance(runtime.indexingCoordinator())
          .defaultAnswer(invocation -> {
            if (!invocation.getMethod().getName().startsWith("updateDocumentsBatch")) return invocation.callRealMethod();
            long start = System.nanoTime();
            Object result = invocation.callRealMethod();
            times.add(System.nanoTime() - start);
            return result;
          }));
      var provider = mock(EmbeddingProvider.class);
      when(provider.embedDocumentBatch(anyList())).thenAnswer(invocation -> {
        List<String> contents = invocation.getArgument(0);
        return contents.stream().map(text -> { float[] vector = new float[384]; vector[0] = 1; return vector; }).toList();
      });
      var context = new EmbeddingBackfillOps.BackfillContext(runtime.documentFieldOps(), coordinator,
          runtime.commitOps(), mock(WorkerSignalBus.class), IndexingPacing.unthrottled(),
          () -> provider, () -> true, () -> true, 128, LoggerFactory.getLogger(getClass()));
      for (int pass = 0; pass < 16; pass++) {
        for (int i = 0; i < 128; i++) runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
            SchemaFields.DOC_ID, "fixture-" + i, SchemaFields.DOC_UID, "uid-" + i,
            SchemaFields.CONTENT, "fixed body " + i + " abcdefghijklmnopqrstuvwxyz".repeat(64),
            SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING)));
        runtime.commitOps().commitAndTrack(CommitReason.TIMER);
        runtime.commitOps().maybeRefreshBlocking();
        long batchStart = System.nanoTime();
        var result = EmbeddingBackfillOps.processEmbeddingBackfill(context);
        endToEnd.add(System.nanoTime() - batchStart);
        assertEquals(128, result.docsProcessed());
      }
      assertEquals(16, times.size());
      var measured = times.subList(4, times.size()).stream().sorted().toList();
      System.out.println("PUBLICATION_MEDIAN_NS=" + (measured.get(5) + measured.get(6)) / 2.0);
      System.out.println("PUBLICATION_SAMPLES_NS=" + measured);
      var batchTimes = endToEnd.subList(4, endToEnd.size()).stream().sorted().toList();
      System.out.println("END_TO_END_MEDIAN_NS=" + (batchTimes.get(5) + batchTimes.get(6)) / 2.0);
      System.out.println("END_TO_END_SAMPLES_NS=" + batchTimes);
    }
  }

  private final List<Long> times = new ArrayList<>();
  private final List<Long> endToEnd = new ArrayList<>();
}
