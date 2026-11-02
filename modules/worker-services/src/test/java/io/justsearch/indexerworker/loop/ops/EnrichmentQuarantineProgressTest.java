/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

final class EnrichmentQuarantineProgressTest extends LuceneExecutorTestBase {
  @TempDir Path directory;
  private static final String TEXT = "alpha beta";

  private RunningRuntime open() {
    var fields = new ArrayList<>(FieldCatalogDef.forChunkTesting(2).fields());
    fields.add(new FieldCatalogDef.FieldDef("splade", "splade", false, false, List.of(), null, null, false,
        "reset-status:splade_status"));
    fields.add(new FieldCatalogDef.FieldDef("splade_status", "keyword", false, true, List.of("filter"), null, null, false));
    fields.add(new FieldCatalogDef.FieldDef("splade_retry_count", "long", false, true, List.of(), null, null, false));
    var runtime = IndexSchema.fromCatalog(new FieldCatalogDef("quarantine-progress", fields))
        .atPath(directory.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open();
    runtime.commitOps().stopCommitTimer(); return runtime;
  }

  private void seed(RunningRuntime runtime, String id, String damage) {
    seed(runtime, id, damage, false);
  }

  private void seed(RunningRuntime runtime, String id, String damage, boolean terminal) {
    String parent = "/parent-" + id;
    if (!"missing-parent".equals(damage)) runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, parent, SchemaFields.DOC_UID, parent, SchemaFields.PATH, parent, SchemaFields.CONTENT, TEXT)));
    var fields = new HashMap<String, Object>();
    fields.put(SchemaFields.DOC_ID, id); fields.put(SchemaFields.DOC_UID, "uid-" + id);
    fields.put(SchemaFields.PATH, parent); fields.put(SchemaFields.PARENT_DOC_ID, parent);
    fields.put(SchemaFields.IS_CHUNK, "true"); fields.put(SchemaFields.CHUNK_CONTENT, "alpha");
    fields.put(SchemaFields.CHUNK_START_CHAR, "0"); fields.put(SchemaFields.CHUNK_END_CHAR, "5");
    fields.put(SchemaFields.CHUNK_PARENT_CONTENT_SHA256, ChunkParentRevision.sha256Hex(TEXT));
    fields.put(SchemaFields.CHUNK_EMBEDDING_STATUS, "PENDING");
    fields.put(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT, "0"); fields.put(SchemaFields.SPLADE_STATUS, "PENDING");
    fields.put(SchemaFields.SPLADE_RETRY_COUNT, "0");
    if ("missing-revision".equals(damage)) fields.remove(SchemaFields.CHUNK_PARENT_CONTENT_SHA256);
    if ("wrong-revision".equals(damage)) fields.put(SchemaFields.CHUNK_PARENT_CONTENT_SHA256, "f".repeat(64));
    if ("missing-offset".equals(damage)) fields.remove(SchemaFields.CHUNK_START_CHAR);
    if ("outside-offset".equals(damage)) fields.put(SchemaFields.CHUNK_END_CHAR, "999");
    if ("negative-offset".equals(damage)) fields.put(SchemaFields.CHUNK_START_CHAR, "-1");
    if ("reversed-offset".equals(damage)) fields.put(SchemaFields.CHUNK_START_CHAR, "6");
    if ("empty-span".equals(damage)) fields.put(SchemaFields.CHUNK_END_CHAR, "0");
    if ("missing-parent-id".equals(damage)) fields.remove(SchemaFields.PARENT_DOC_ID);
    if (terminal) {
      fields.put(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT, "2");
      fields.put(SchemaFields.CHUNK_VECTOR, new float[] {0f, 1f});
    }
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
  }

  @org.junit.jupiter.api.Test
  void terminalChunkFailureRemovesPriorVectorEvenWithoutParentProof() throws Exception {
    try (var runtime = open()) {
      seed(runtime, "broken", "wrong-revision", true);
      runtime.commitOps().commitAndTrack(); runtime.commitOps().maybeRefreshBlocking();
      var embedding = mock(EmbeddingProvider.class); when(embedding.isAvailable()).thenReturn(true);
      EmbeddingBackfillOps.processChunkEmbeddingBackfill(new EmbeddingBackfillOps.BackfillContext(
          runtime.documentFieldOps(), runtime.indexingCoordinator(), runtime.commitOps(), mock(WorkerSignalBus.class),
          IndexingPacing.unthrottled(), () -> embedding, () -> true, () -> true, 1, LoggerFactory.getLogger(getClass())));
      runtime.commitOps().commitAndTrack(); runtime.commitOps().maybeRefreshBlocking();
      assertEquals("FAILED", runtime.documentFieldOps().getDocumentField("broken", SchemaFields.CHUNK_EMBEDDING_STATUS));
      assertEquals("3", runtime.documentFieldOps().getDocumentField("broken", SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT));
      assertEquals(0, runtime.indexCountOps().countByField(SchemaFields.CHUNK_EMBEDDING_STATUS, "PENDING"));
    }
    try (var dir = org.apache.lucene.store.FSDirectory.open(directory.resolve("index"));
        var reader = org.apache.lucene.index.DirectoryReader.open(dir)) {
      var hits = new org.apache.lucene.search.IndexSearcher(reader).search(new org.apache.lucene.search.TermQuery(
          new org.apache.lucene.index.Term(SchemaFields.DOC_ID, "broken")), 1);
      int doc = hits.scoreDocs[0].doc;
      var leaf = reader.leaves().get(org.apache.lucene.index.ReaderUtil.subIndex(doc, reader.leaves()));
      var vectors = leaf.reader().getFloatVectorValues(SchemaFields.CHUNK_VECTOR);
      if (vectors != null) assertNotEquals(doc - leaf.docBase, vectors.iterator().advance(doc - leaf.docBase));
    }
    try (var runtime = open()) {
      assertEquals("FAILED", runtime.documentFieldOps().getDocumentField("broken", SchemaFields.CHUNK_EMBEDDING_STATUS));
    }
  }

  @ParameterizedTest
  @CsvSource({"missing-parent-id,false", "empty-span,false", "missing-revision,false", "wrong-revision,false", "missing-parent,false", "missing-offset,false", "outside-offset,false", "negative-offset,false", "reversed-offset,false",
      "missing-parent-id,true", "empty-span,true", "missing-revision,true", "wrong-revision,true", "missing-parent,true", "missing-offset,true", "outside-offset,true", "negative-offset,true", "reversed-offset,true"})
  void D63DamagedChunkIsQuarantinedWithoutStarvingHealthyEnrichmentAndStaysFailedAfterRestart(String damage, boolean sparse) throws Exception {
    try (var runtime = open()) {
      seed(runtime, "broken", damage); seed(runtime, "healthy", "none");
      runtime.commitOps().commitAndTrack(); runtime.commitOps().maybeRefreshBlocking();
      var embedding = mock(EmbeddingProvider.class); when(embedding.isAvailable()).thenReturn(true);
      when(embedding.embedDocumentBatch(anyList())).thenReturn(List.of(new float[] {1f, 0f}));
      var splade = mock(SpladeEncoder.class); when(splade.encodeBatch(anyList())).thenReturn(List.of(Map.of("alpha", 1f)));
      for (int pass = 0; pass < 2; pass++) {
        if (sparse) SpladeBackfillOps.processSpladeBackfill(new SpladeBackfillOps.BackfillContext(
            runtime.documentFieldOps(), runtime.indexingCoordinator(), runtime.commitOps(), IndexingPacing.unthrottled(),
            () -> splade, () -> true, 2, true, true, LoggerFactory.getLogger(getClass())));
        else EmbeddingBackfillOps.processChunkEmbeddingBackfill(new EmbeddingBackfillOps.BackfillContext(
            runtime.documentFieldOps(), runtime.indexingCoordinator(), runtime.commitOps(), mock(WorkerSignalBus.class),
            IndexingPacing.unthrottled(), () -> embedding, () -> true, () -> true, 2, LoggerFactory.getLogger(getClass())));
        runtime.commitOps().maybeRefreshBlocking();
      }
      assertEquals("FAILED", runtime.documentFieldOps().getDocumentField("broken", SchemaFields.CHUNK_EMBEDDING_STATUS));
      assertEquals("FAILED", runtime.documentFieldOps().getDocumentField("broken", SchemaFields.SPLADE_STATUS));
      assertEquals("COMPLETED", runtime.documentFieldOps().getDocumentField("healthy",
          sparse ? SchemaFields.SPLADE_STATUS : SchemaFields.CHUNK_EMBEDDING_STATUS));
    }
    try (var runtime = open()) {
      assertEquals("FAILED", runtime.documentFieldOps().getDocumentField("broken", SchemaFields.SPLADE_STATUS));
      assertNull(runtime.documentFieldOps().getEnrichmentInputs(List.of("broken")).get("broken").content());
      assertEquals("alpha", runtime.documentFieldOps().getEnrichmentInputs(List.of("healthy")).get("healthy").content());
    }
  }
}
