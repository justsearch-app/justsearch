/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import io.justsearch.indexing.chunking.ChunkSplitter;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.junit.jupiter.api.Test;

/** Work counters, rather than elapsed time, enforce the unchanged admission budgets. */
final class ChunkAdmissionCostTest extends LuceneExecutorTestBase {
  @Test
  void repeatedUnchangedAdmissionReadsNoChildren() throws Exception {
    try (var runtime = open()) {
      seed(runtime, "large", 5000, false);
      refresh(runtime);
      assertTrue(runtime.documentFieldOps().isUnmodified("large", 123));
      var childReads = new AtomicInteger();
      var parentReads = new AtomicInteger();
      try (var formatter = mockStatic(SearchResultFormatter.class, invocation -> {
        if (invocation.getMethod().getName().equals("extractFromStoredFields")
            && invocation.getArguments().length == 4
            && ((Set<?>) invocation.getArgument(3)).contains(SchemaFields.CHUNK_INDEX)) {
          childReads.incrementAndGet();
        }
        if (invocation.getMethod().getName().equals("extractFromStoredFields")
            && invocation.getArguments().length == 4
            && ((Set<?>) invocation.getArgument(3)).contains(SchemaFields.CONTENT_SHA256))
          parentReads.incrementAndGet();
        return invocation.callRealMethod();
      })) {
        for (int i = 0; i < 10; i++)
          assertTrue(runtime.documentFieldOps().isUnmodified("large", 123));
        assertEquals(0, childReads.get(), "unchanged admission must not revisit child rows");
        assertEquals(0, parentReads.get(), "unchanged admission must not decompress parent content blocks");
      }
    }
  }

  @Test
  void firstLegacyScanDoesNotSplitOrOpenAWriterReaderPerParent() throws Exception {
    try (var runtime = open()) {
      int total = ChunkSplitter.splitWithMetadata("alpha beta gamma delta ".repeat(400)).size();
      for (int i = 0; i < 32; i++) seed(runtime, "legacy-" + i, total, true);
      refresh(runtime);
      var opens = new AtomicInteger();
      var splits = new AtomicInteger();
      var childReads = new AtomicInteger();
      try (var formatter = mockStatic(SearchResultFormatter.class, invocation -> {
        if (invocation.getMethod().getName().equals("extractFromStoredFields")
            && invocation.getArguments().length == 4
            && ((Set<?>) invocation.getArgument(3)).contains(SchemaFields.CHUNK_INDEX))
          childReads.incrementAndGet();
        return invocation.callRealMethod();
      }); var readers = mockStatic(DirectoryReader.class, invocation -> {
        if (invocation.getMethod().getName().equals("open")
            && invocation.getArguments().length == 1
            && invocation.getArgument(0) instanceof IndexWriter) opens.incrementAndGet();
        return invocation.callRealMethod();
      }); var splitter = mockStatic(ChunkSplitter.class, invocation -> {
        if (invocation.getMethod().getName().startsWith("split")) splits.incrementAndGet();
        return invocation.callRealMethod();
      })) {
        for (int i = 0; i < 32; i++)
          assertTrue(runtime.documentFieldOps().isUnmodified("legacy-" + i, 123));
        assertEquals(0, splits.get(), "complete legacy children already declare their intended total");
        assertTrue(opens.get() <= 1,
            "legacy migration must use one writer snapshot for the scan, observed " + opens.get());
        assertTrue(childReads.get() <= 2 * 32 * total,
            "legacy child work is bounded by two corpus passes, observed " + childReads.get());
      }
      for (int i = 0; i < 32; i++)
        assertEquals(String.valueOf(total), runtime.documentFieldOps().getDocumentField("legacy-" + i, SchemaFields.CHUNK_TOTAL));
    }
  }

  @Test
  void legacyMigrationPreservesCompletedSparseFeatures() throws Exception {
    var base = FieldCatalogDef.forChunkTesting(0);
    var fields = new java.util.ArrayList<>(base.fields());
    fields.add(new FieldCatalogDef.FieldDef(SchemaFields.SPLADE, "splade", false, false,
        java.util.List.of(), null, null, false, "reset-status:splade_status"));
    fields.add(new FieldCatalogDef.FieldDef(SchemaFields.SPLADE_STATUS, "keyword", false, true,
        java.util.List.of("filter"), null, null, false));
    fields.add(new FieldCatalogDef.FieldDef(SchemaFields.SPLADE_RETRY_COUNT, "long", false, true,
        java.util.List.of(), null, null, false));
    try (var runtime = IndexSchema.fromCatalog(new FieldCatalogDef(base.version(), fields)).ephemeral()
        .withExecutorRegistrations(testLuceneExecutors()).open()) {
      String content = "alpha beta gamma delta ".repeat(400);
      int total = ChunkSplitter.splitWithMetadata(content).size();
      seed(runtime, "sparse-legacy", total, true);
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "sparse-legacy", SchemaFields.DOC_UID, "sparse-legacy",
          SchemaFields.PATH, "sparse-legacy", SchemaFields.CONTENT, content,
          SchemaFields.CONTENT_SHA256, ChunkParentRevision.sha256Hex(content), SchemaFields.MODIFIED_AT, 123L,
          SchemaFields.SPLADE, Map.of("alpha", 2.01f, "beta", 1.21f),
          SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED,
          SchemaFields.SPLADE_RETRY_COUNT, 2L)));
      refresh(runtime);
      float before = sparseScore(runtime);
      assertTrue(before > 0, "the legacy sparse artifact must exist before migration");
      assertTrue(runtime.documentFieldOps().isUnmodified("sparse-legacy", 123));
      assertEquals(before, sparseScore(runtime), "migration must preserve the existing sparse weights");
      assertEquals(SchemaFields.SPLADE_STATUS_COMPLETED,
          runtime.documentFieldOps().getDocumentField("sparse-legacy", SchemaFields.SPLADE_STATUS),
          "metadata migration must not schedule unchanged content for re-encoding");
      assertEquals("2", runtime.documentFieldOps().getDocumentField("sparse-legacy", SchemaFields.SPLADE_RETRY_COUNT),
          "migration must retain the doc-values-only retry counter");

    }
  }

  private static float sparseScore(RunningRuntime runtime) throws Exception {
    return runtime.readPathOps().withSearcher(searcher -> {
      var hits = searcher.search(org.apache.lucene.document.FeatureField.newLinearQuery(
          SchemaFields.SPLADE, "alpha", 1f), 1);
      return hits.scoreDocs.length == 0 ? 0f : hits.scoreDocs[0].score;
    });
  }

  private RunningRuntime open() throws Exception {
    return IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).ephemeral()
        .withExecutorRegistrations(testLuceneExecutors()).open();
  }

  private static void seed(RunningRuntime runtime, String id, int total, boolean legacy) {
    String content = "alpha beta gamma delta ".repeat(400);
    String revision = ChunkParentRevision.sha256Hex(content);
    var parent = new java.util.HashMap<String, Object>(Map.of(
        SchemaFields.DOC_ID, id, SchemaFields.DOC_UID, id, SchemaFields.PATH, id,
        SchemaFields.CONTENT, content, SchemaFields.CONTENT_SHA256, revision,
        SchemaFields.MODIFIED_AT, 123L));
    if (!legacy) {
      parent.put(SchemaFields.CHUNK_TOTAL, total);
      parent.put(SchemaFields.CHUNK_PARENT_CONTENT_SHA256, revision);
    }
    runtime.indexingCoordinator().indexSingle(new IndexDocument(parent));
    for (int i = 0; i < total; i++)
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, id + "#" + i, SchemaFields.DOC_UID, id + "#" + i,
          SchemaFields.PATH, id, SchemaFields.IS_CHUNK, "true", SchemaFields.PARENT_DOC_ID, id,
          SchemaFields.CHUNK_INDEX, i, SchemaFields.CHUNK_TOTAL, total,
          SchemaFields.CHUNK_PARENT_CONTENT_SHA256, revision)));
  }

  private static void refresh(RunningRuntime runtime) {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }
}
