/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.TelemetryEvents;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 931 §C.3 (audit finding 3) — one degenerate embedding used to lose a whole batch.
 *
 * <p>Lucene's {@code KnnFloatVectorField} throws {@link IllegalArgumentException} on a non-finite
 * vector, and {@code IndexingCoordinator.indexBatch} calls {@code toDocument} in its batch loop with no
 * per-document catch — so a single bad vector aborted every other document in the batch before
 * anything reached the writer. The document is worth far more than the one field: the field is
 * dropped, the document is written and stays lexically searchable, its embedding status is
 * corrected to FAILED, and the drop is counted.
 */
class NonFiniteVectorDropTest extends LuceneExecutorTestBase {

  private static final float[] GOOD_A = {1.0f, 0.0f, 0.0f, 0.0f};
  private static final float[] GOOD_C = {0.0f, 1.0f, 0.0f, 0.0f};
  private static final float[] NAN = {1.0f, Float.NaN, 0.0f, 0.0f};
  private static final float[] INFINITE = {1.0f, 0.0f, Float.NEGATIVE_INFINITY, 0.0f};

  @Test
  void aNaNVectorInTheMiddleOfABatchLosesOnlyItsOwnVectorField() throws Exception {
    assertDegenerateVectorIsDroppedNotFatal(NAN);
  }

  @Test
  void anInfiniteVectorInTheMiddleOfABatchLosesOnlyItsOwnVectorField() throws Exception {
    assertDegenerateVectorIsDroppedNotFatal(INFINITE);
  }

  private void assertDegenerateVectorIsDroppedNotFatal(float[] degenerate) throws Exception {
    List<Integer> dropCounts = new ArrayList<>();
    TelemetryEvents spy =
        new TelemetryEvents() {
          @Override
          public void onVectorFieldDropped(int count) {
            dropCounts.add(count);
          }
        };

    RunningRuntime runtime =
        IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(4))
            .ephemeral().withExecutorRegistrations(testLuceneExecutors())
            .withTelemetry(spy)
            .open();
    try {
      runtime
          .indexingCoordinator()
          .indexBatch(
              List.of(
                  doc("doc-a", "alpha content", GOOD_A),
                  doc("doc-b", "bravo content", degenerate),
                  doc("doc-c", "charlie content", GOOD_C)));
      commit(runtime);

      assertEquals(
          3,
          (int) runtime.readPathOps().withSearcher(s -> s.getIndexReader().numDocs()),
          "every document in the batch is written — the bad vector costs a field, not the batch");

      assertNotNull(vectorOf(runtime, "doc-a"), "a healthy neighbour keeps its vector");
      assertNotNull(vectorOf(runtime, "doc-c"), "a healthy neighbour keeps its vector");
      assertNull(vectorOf(runtime, "doc-b"), "the degenerate vector was not indexed");

      List<String> knnHits =
          runtime.readPathOps().searchVector(GOOD_A, 10).hits().stream()
              .map(LuceneRuntimeTypes.SearchHit::docId)
              .toList();
      assertTrue(knnHits.contains("doc-a"), "the healthy neighbours stay KNN-searchable");
      assertTrue(knnHits.contains("doc-c"), "the healthy neighbours stay KNN-searchable");
      assertTrue(!knnHits.contains("doc-b"), "the vectorless document cannot be a KNN hit");

      assertEquals(
          1,
          (int) runtime.readPathOps().withSearcher(
              s -> s.count(new TermQuery(new Term(SchemaFields.CONTENT, "bravo")))),
          "the document that lost its vector is still reachable lexically");

      assertEquals(
          List.of(1),
          dropCounts,
          "one WARN-and-count per batch reporting exactly the one dropped vector field");

      assertEquals(
          SchemaFields.EMBEDDING_STATUS_FAILED,
          runtime.documentFieldOps().getDocumentField("doc-b", SchemaFields.EMBEDDING_STATUS),
          "the paired status is corrected so backfill accounting is not told a COMPLETED lie");
      assertEquals(
          SchemaFields.EMBEDDING_STATUS_COMPLETED,
          runtime.documentFieldOps().getDocumentField("doc-a", SchemaFields.EMBEDDING_STATUS),
          "a neighbour's status is untouched");
    } finally {
      runtime.close();
    }
  }

  private static IndexDocument doc(String id, String content, float[] vector) {
    Map<String, Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID, id);
    fields.put(SchemaFields.DOC_UID, id + "#0");
    fields.put(SchemaFields.PATH, "test/" + id + ".txt");
    fields.put(SchemaFields.CONTENT, content);
    fields.put(SchemaFields.VECTOR, vector);
    fields.put(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
    return new IndexDocument(fields);
  }

  private static void commit(RunningRuntime runtime) {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  private static float[] vectorOf(RunningRuntime runtime, String docId) throws Exception {
    return runtime
        .readPathOps()
        .withSearcher(
            searcher -> {
              var td = searcher.search(new TermQuery(new Term(SchemaFields.DOC_ID, docId)), 1);
              if (td.scoreDocs.length == 0) return null;
              int gid = td.scoreDocs[0].doc;
              var leaves = searcher.getIndexReader().leaves();
              var leaf = leaves.get(ReaderUtil.subIndex(gid, leaves));
              int docInLeaf = gid - leaf.docBase;
              FloatVectorValues values = leaf.reader().getFloatVectorValues(SchemaFields.VECTOR);
              if (values == null) return null;
              var iter = values.iterator();
              if (iter.advance(docInLeaf) != docInLeaf) return null;
              float[] v = values.vectorValue(iter.index());
              return v == null ? null : v.clone();
            });
  }
}
