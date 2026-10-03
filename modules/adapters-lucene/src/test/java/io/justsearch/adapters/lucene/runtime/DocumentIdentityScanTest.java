/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.util.BytesRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("DocumentFieldOps identity recovery scan")
final class DocumentIdentityScanTest extends LuceneExecutorTestBase {

  @Test
  @DisplayName("identity recovery scans parents and excludes their chunks")
  void scanReturnsOnlyParentIdentities(@TempDir Path tempDir) throws Exception {
    try (RunningRuntime runtime =
        IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(tempDir).withExecutorRegistrations(testLuceneExecutors()).open()) {
      String parentId = "parent.txt";
      String parentUid = "00000000-0000-4000-8000-000000000220";
      runtime
          .indexingCoordinator()
          .indexSingle(
              new IndexDocument(
                  Map.of(
                      SchemaFields.DOC_ID,
                      parentId,
                      SchemaFields.DOC_UID,
                      parentUid,
                      SchemaFields.CONTENT,
                      "parent")));
      for (int i = 0; i < 2; i++) {
        runtime
            .indexingCoordinator()
            .indexSingle(
                new IndexDocument(
                    Map.of(
                        SchemaFields.DOC_ID,
                        "chunk-" + i,
                        SchemaFields.DOC_UID,
                        parentUid + "#" + i,
                        SchemaFields.IS_CHUNK,
                        "true",
                        SchemaFields.PARENT_DOC_ID,
                        parentId,
                        SchemaFields.CHUNK_INDEX,
                        String.valueOf(i),
                        SchemaFields.CONTENT,
                        "chunk " + i)));
      }
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      List<DocumentFieldOps.StoredDocumentIdentity> identities = new ArrayList<>();
      var summary =
          runtime.documentFieldOps().scanParentDocumentIdentities(1000, identities::addAll);

      assertEquals(1, identities.size());
      assertEquals(parentId, identities.getFirst().docId());
      assertEquals(parentUid, identities.getFirst().docUid());
      assertEquals(1, summary.parentsSeen());
      assertEquals(1, summary.parentsEmitted());
      assertEquals(0, summary.parentsSkipped());
    }
  }

  @Test
  @DisplayName("a live parent missing doc_uid is counted and skipped, not fatal")
  void missingParentUidIsSkippedAndCountedInsteadOfFailingTheScan(@TempDir Path tempDir)
      throws Exception {
    try (RunningRuntime runtime =
        IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(tempDir).withExecutorRegistrations(testLuceneExecutors()).open()) {
      runtime
          .indexingCoordinator()
          .indexSingle(
              new IndexDocument(
                  Map.of(
                      SchemaFields.DOC_ID,
                      "healthy.txt",
                      SchemaFields.DOC_UID,
                      "00000000-0000-4000-8000-000000000221",
                      SchemaFields.CONTENT,
                      "healthy")));
      Document malformed = new Document();
      malformed.add(new StringField(SchemaFields.DOC_ID, "malformed-parent", Field.Store.YES));
      malformed.add(
          new SortedDocValuesField(SchemaFields.DOC_ID, new BytesRef("malformed-parent")));
      new LifecycleTestAccessor(runtime).addRawDocument(malformed);
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      List<DocumentFieldOps.StoredDocumentIdentity> identities = new ArrayList<>();
      var summary =
          runtime.documentFieldOps().scanParentDocumentIdentities(1000, identities::addAll);

      // A legacy parent without doc_uid re-mints at its next admission; taking the Worker down
      // instead loses the whole index it could still have imported (tempdoc 931 §C.2).
      assertEquals(2, summary.parentsSeen());
      assertEquals(1, summary.parentsEmitted());
      assertEquals(1, summary.parentsSkipped());
      assertEquals(1, identities.size());
      assertEquals("healthy.txt", identities.getFirst().docId());
    }
  }

  @Test
  @DisplayName("1001 parents stream as two batches, never one whole-index list")
  void parentsAreStreamedInBoundedBatches(@TempDir Path tempDir) throws Exception {
    try (RunningRuntime runtime =
        IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(tempDir).withExecutorRegistrations(testLuceneExecutors()).open()) {
      for (int i = 0; i < 1001; i++) {
        runtime
            .indexingCoordinator()
            .indexSingle(
                new IndexDocument(
                    Map.of(
                        SchemaFields.DOC_ID,
                        "batched-" + i,
                        SchemaFields.DOC_UID,
                        "uid-" + i,
                        SchemaFields.CONTENT,
                        "batched document " + i)));
      }
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      List<Integer> batchSizes = new ArrayList<>();
      var summary =
          runtime
              .documentFieldOps()
              .scanParentDocumentIdentities(
                  1000,
                  batch -> {
                    batchSizes.add(batch.size());
                    assertTrue(batch.size() <= 1000, "no batch may exceed the requested size");
                  });

      assertEquals(List.of(1000, 1), batchSizes);
      assertEquals(1001, summary.parentsSeen());
      assertEquals(1001, summary.parentsEmitted());
    }
  }
}
