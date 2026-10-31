/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

/** Readiness changes during real Lucene snapshot reads must not become bad-chunk retries. */
class BlankChunkReadinessIntegrationTest extends LuceneExecutorTestBase {
  @TempDir Path directory;

  enum ReadinessChange { NONE, RETIRED, REPLACED, WRITES_BLOCKED }

  static Stream<Arguments> readinessChanges() {
    return Stream.of(false, true).flatMap(combined -> Stream.of(ReadinessChange.values())
        .map(change -> Arguments.of(combined, change)));
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @MethodSource("readinessChanges")
  void blankChunkChargesRetryOnlyWhileSelectedProviderRemainsReady(
      boolean combined, ReadinessChange change) throws Exception {
    try (var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(2))
        .atPath(directory).withExecutorRegistrations(testLuceneExecutors()).open()) {
      runtime.commitOps().stopCommitTimer();
      runtime.commitOps().suspendNrtRefresh();
      String text = "   \t\n";
      String revision = ChunkParentRevision.sha256Hex(text);
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "parent", SchemaFields.DOC_UID, "parent-uid",
          SchemaFields.CONTENT, text, SchemaFields.CONTENT_SHA256, revision)));
      Map<String, Object> chunk = new HashMap<>();
      chunk.put(SchemaFields.DOC_ID, "chunk");
      chunk.put(SchemaFields.DOC_UID, "chunk-uid");
      chunk.put(SchemaFields.IS_CHUNK, "true");
      chunk.put(SchemaFields.PARENT_DOC_ID, "parent");
      chunk.put(SchemaFields.CHUNK_INDEX, 0);
      chunk.put(SchemaFields.CHUNK_START_CHAR, 0);
      chunk.put(SchemaFields.CHUNK_END_CHAR, text.length());
      chunk.put(SchemaFields.CHUNK_PARENT_CONTENT_SHA256, revision);
      chunk.put(SchemaFields.CHUNK_EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
      chunk.put(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT, "0");
      runtime.indexingCoordinator().indexSingle(new IndexDocument(chunk));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      var embedding = mock(EmbeddingProvider.class);
      when(embedding.isAvailable()).thenReturn(true);
      var selected = new AtomicReference<>(embedding);
      var writesAllowed = new AtomicBoolean(true);
      var snapshotRead = new AtomicBoolean();
      var fields = spy(runtime.documentFieldOps());
      doAnswer(call -> {
        var result = call.callRealMethod();
        var input = assertInstanceOf(DocumentFieldOps.EnrichmentInput.class,
            assertInstanceOf(Map.class, result).get("chunk"));
        assertEquals(text, input.content(), "the real snapshot must reconstruct the whitespace slice");
        snapshotRead.set(true);
        switch (change) {
          case RETIRED -> when(embedding.isAvailable()).thenReturn(false);
          case REPLACED -> selected.set(mock(EmbeddingProvider.class));
          case WRITES_BLOCKED -> writesAllowed.set(false);
          case NONE -> { }
        }
        return result;
      }).when(fields).getEnrichmentInputs(anyList());
      var log = LoggerFactory.getLogger(BlankChunkReadinessIntegrationTest.class);
      if (combined) {
        var context = new CombinedEnrichmentBackfillOps.BackfillContext(
            fields, runtime.indexingCoordinator(), runtime.commitOps(), IndexingPacing.unthrottled(),
            selected::get, () -> null, () -> null, () -> true, writesAllowed::get, 10, log,
            true, false, false, 10, new ArrayDeque<>(), new ArrayDeque<>(), new int[] {0},
            () -> false, () -> false, () -> false, new WindowedEmbedProgress());
        CombinedEnrichmentBackfillOps.processCombinedBackfill(context);
      } else {
        var context = new EmbeddingBackfillOps.BackfillContext(
            fields, runtime.indexingCoordinator(), runtime.commitOps(), mock(WorkerSignalBus.class),
            IndexingPacing.unthrottled(), selected::get, () -> true, writesAllowed::get, 10, log);
        EmbeddingBackfillOps.processChunkEmbeddingBackfill(context);
      }
      runtime.commitOps().maybeRefreshBlocking();

      assertTrue(snapshotRead.get(), "the selected chunk must reach the real Lucene snapshot read");
      assertEquals(change == ReadinessChange.NONE ? "1" : "0",
          runtime.documentFieldOps().getDocumentField("chunk", SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT));
      assertEquals(SchemaFields.EMBEDDING_STATUS_PENDING,
          runtime.documentFieldOps().getDocumentField("chunk", SchemaFields.CHUNK_EMBEDDING_STATUS));
      verify(embedding, never()).embedDocumentBatch(anyList());
    }
  }
}
