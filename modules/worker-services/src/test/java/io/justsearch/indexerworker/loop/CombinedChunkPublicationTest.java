/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.BatchUpdateResult;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.loop.ops.CombinedEnrichmentBackfillOps;
import io.justsearch.indexerworker.loop.ops.WindowedEmbedProgress;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/** Consecutive combined batches through real snapshot reads and conditional Lucene publication. */
class CombinedChunkPublicationTest extends LuceneExecutorTestBase {
  @TempDir Path directory;

  @ParameterizedTest(name = "refresh after publication = {0}")
  @ValueSource(booleans = {false, true})
  void completedChunksAreNotEncodedAgain(boolean refreshAfterPublication) throws Exception {
    try (var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(4))
        .atPath(directory).withExecutorRegistrations(testLuceneExecutors()).open()) {
      runtime.commitOps().stopCommitTimer();
      runtime.commitOps().suspendNrtRefresh();
      String text = "parent chunk content";
      String revision = ChunkParentRevision.sha256Hex(text);
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "parent", SchemaFields.DOC_UID, "parent-uid",
          SchemaFields.CONTENT, text, SchemaFields.CONTENT_SHA256, revision)));
      for (int i = 0; i < 50; i++) {
        Map<String, Object> fields = new HashMap<>();
        fields.put(SchemaFields.DOC_ID, "chunk-" + i);
        fields.put(SchemaFields.DOC_UID, "chunk-uid-" + i);
        fields.put(SchemaFields.IS_CHUNK, "true");
        fields.put(SchemaFields.PARENT_DOC_ID, "parent");
        fields.put(SchemaFields.CHUNK_INDEX, i);
        fields.put(SchemaFields.CHUNK_START_CHAR, 0);
        fields.put(SchemaFields.CHUNK_END_CHAR, text.length());
        fields.put(SchemaFields.CHUNK_PARENT_CONTENT_SHA256, revision);
        fields.put(SchemaFields.CHUNK_EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
        runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
      }
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      var encoded = new AtomicInteger();
      var embedding = mock(EmbeddingProvider.class);
      when(embedding.isAvailable()).thenReturn(true);
      when(embedding.embedDocumentBatch(anyList())).thenAnswer(call -> {
        List<String> contents = call.getArgument(0);
        encoded.addAndGet(contents.size());
        return Collections.nCopies(contents.size(), new float[] {1, 0, 0, 0});
      });
      var coordinator = spy(runtime.indexingCoordinator());
      var written = new ArrayList<Integer>();
      doAnswer(call -> {
        BatchUpdateResult result = (BatchUpdateResult) call.callRealMethod();
        written.add(result.updatedCount());
        if (refreshAfterPublication) runtime.commitOps().maybeRefreshBlocking();
        return result;
      }).when(coordinator).updateDocumentsBatchIf(anyList(), anyMap());
      var context = new CombinedEnrichmentBackfillOps.BackfillContext(
          runtime.documentFieldOps(), coordinator, runtime.commitOps(), IndexingPacing.unthrottled(),
          () -> embedding, () -> null, () -> null, () -> true, () -> true, 100,
          LoggerFactory.getLogger(CombinedChunkPublicationTest.class), true, false, false, 50,
          new ArrayDeque<>(), new ArrayDeque<>(), new int[] {0}, () -> false, () -> false,
          () -> false, new WindowedEmbedProgress());

      for (int cycle = 0; cycle < 3; cycle++) {
        CombinedEnrichmentBackfillOps.processCombinedBackfill(context);
      }
      System.out.println("chunk publication: encoded=" + encoded + ", written=" + written);
      assertEquals(List.of(50), written, "completed chunks must leave the next batch selection");
      assertEquals(50, encoded.get(), "every encoded chunk must be published exactly once");
    }
  }
}
