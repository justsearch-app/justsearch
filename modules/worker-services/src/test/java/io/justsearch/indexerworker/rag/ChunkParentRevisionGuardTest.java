/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.IndexRuntimeIOException;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 931 §C.1, end to end across the two sides of the invariant: {@link ChunkDocumentWriter}
 * stamps the parent revision a chunk was cut from, and the adapter's RMW re-slice refuses any other
 * revision. Asserted together because a writer that stamps the wrong string and a reader that
 * compares correctly would each pass their own unit test while every chunk RMW in production failed
 * closed.
 */
final class ChunkParentRevisionGuardTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  @TempDir Path tempDir;
  private RunningRuntime runtime;

  @BeforeEach
  void setUp() {
    runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(tempDir).withExecutorRegistrations(testLuceneExecutors()).open();
  }

  @AfterEach
  void tearDown() {
    if (runtime != null) runtime.close();
  }

  @Test
  void chunksWrittenByTheProductionWriterSatisfyTheRmwRevisionGuard() {
    String parentDocId = "d:/docs/report.txt";
    String content = "lorem ipsum dolor sit amet ".repeat(400);

    indexParent(parentDocId, content);
    int written =
        ChunkDocumentWriter.regenerateChunksFromExistingParent(
            runtime.documentFieldOps(), runtime.indexingCoordinator(), parentDocId, content, true);
    assertTrue(written > 1, "precondition: the fixture produces several chunks");
    commit();

    String chunkId = firstChunkId(parentDocId);
    assertEquals(
        ChunkParentRevision.sha256Hex(content),
        runtime
            .documentFieldOps()
            .getDocumentField(chunkId, SchemaFields.CHUNK_PARENT_CONTENT_SHA256),
        "the writer stamps the revision of the very content it chunked");

    assertTrue(
        runtime.indexingCoordinator().updateDocument(chunkId, Map.of(SchemaFields.LANGUAGE, "de")),
        "an unrelated RMW on a freshly written chunk is allowed — the guard is armed, not blocking");
    commit();

    // The parent is rewritten and its chunks are NOT regenerated yet: exactly the window between
    // JobBatchWriter's two calls that the guard exists for.
    String rewritten = content.replace('o', 'a').replace('e', 'u');
    assertEquals(content.length(), rewritten.length(), "precondition: an equal-length rewrite");
    assertTrue(!rewritten.equals(content), "precondition: the bytes actually changed");
    assertTrue(
        runtime
            .indexingCoordinator()
            .updateDocument(parentDocId, Map.of(SchemaFields.CONTENT, rewritten)));
    commit();

    IndexRuntimeIOException thrown =
        assertThrows(
            IndexRuntimeIOException.class,
            () ->
                runtime
                    .indexingCoordinator()
                    .updateDocument(chunkId, Map.of(SchemaFields.LANGUAGE, "fr")));
    assertTrue(
        thrown.getCause().getMessage().contains("parent content revision mismatch"),
        thrown.getCause().getMessage());
    assertEquals(
        "de",
        runtime.documentFieldOps().getDocumentField(chunkId, SchemaFields.LANGUAGE),
        "the refused RMW wrote nothing");
  }

  private void indexParent(String parentDocId, String content) {
    Map<String, Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID, parentDocId);
    fields.put(SchemaFields.DOC_UID, "parent-uid");
    fields.put(SchemaFields.PATH, parentDocId);
    fields.put(SchemaFields.CONTENT, content);
    fields.put(SchemaFields.FILE_KIND, "text");
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
    commit();
  }

  private String firstChunkId(String parentDocId) {
    Query byParent =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")), BooleanClause.Occur.FILTER)
            .add(
                new TermQuery(new Term(SchemaFields.PARENT_DOC_ID, parentDocId)),
                BooleanClause.Occur.FILTER)
            .build();
    var result =
        runtime
            .readPathOps()
            .search(
                byParent,
                10,
                Set.of(SchemaFields.DOC_ID),
                LuceneRuntimeTypes.RuntimeSearchSort.RELEVANCE,
                null);
    assertNotNull(result);
    assertTrue(!result.hits().isEmpty(), "the parent must have chunks");
    return result.hits().get(0).docId();
  }

  private void commit() {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }
}
