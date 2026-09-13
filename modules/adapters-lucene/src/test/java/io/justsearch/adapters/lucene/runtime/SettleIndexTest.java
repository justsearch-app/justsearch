/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.FieldCatalogDef.FieldDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.runtime.CommitMetadataSource;
import io.justsearch.indexing.runtime.CommitMetadataValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 931 §E item 10 — settle purges deleted-but-unmerged documents from the active index.
 *
 * <p>Why this matters beyond disk: a tombstone is still counted in the collection statistics BM25
 * scores against, so two indexes built from the same corpus but carrying different tombstone counts
 * answer the same query differently (2,629 vs 222 tombstones moved observed hit counts 3-4% with no
 * code cause). These tests pin the property the settle exists to give a paired evaluation: after it,
 * {@code maxDoc == numDocs}, and every surviving document is still retrievable.
 */
@DisplayName("settle (purge deleted-but-unmerged documents)")
final class SettleIndexTest extends LuceneExecutorTestBase {

  private RunningRuntime runtime;
  private Path indexDir;

  private static final CommitMetadataSource TEST_METADATA_SOURCE =
      () ->
          Map.of(
              "index_fingerprint", "settle-test-1.0.0",
              "schema_fp", "test-fingerprint",
              "boosts_fp", "test-boosts",
              "dag_hash", "test-dag-hash",
              "pipeline_budget_profile", "test-profile",
              "field_catalog_hash", "test-catalog-hash",
              "synonyms_hash", "test-synonyms-hash");

  private static final CommitMetadataValidator TEST_VALIDATOR = metadata -> {};

  private static FieldCatalogDef createTestCatalog() {
    return new FieldCatalogDef(
        "settle-test-v1",
        List.of(
            new FieldDef("doc_id", "keyword", true, true, List.of("id", "sort"), null, null, false),
            new FieldDef(
                "doc_uid", "keyword", false, true, List.of("sort", "tiebreak"), null, null, false),
            new FieldDef("content", "text", true, false, List.of("highlight"), null, "icu", false),
            new FieldDef("path", "keyword", true, true, List.of("filter", "sort"), null, null, false),
            new FieldDef(
                "collection", "keyword", true, true, List.of("filter", "facet"), null, null, false),
            new FieldDef(
                "parent_doc_id", "keyword", true, true, List.of("filter"), null, null, false),
            new FieldDef("is_chunk", "keyword", true, true, List.of("filter"), null, null, false)));
  }

  @BeforeEach
  void setUp() throws Exception {
    indexDir = Files.createTempDirectory("settle-index-");
    runtime =
        IndexSchema.fromCatalog(createTestCatalog(), TEST_METADATA_SOURCE, TEST_VALIDATOR)
            .atPath(indexDir).withExecutorRegistrations(testLuceneExecutors())
            .open();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (runtime != null) {
      runtime.close();
    }
    if (indexDir != null && Files.exists(indexDir)) {
      try (var walk = Files.walk(indexDir)) {
        walk.sorted(java.util.Comparator.reverseOrder())
            .forEach(
                p -> {
                  try {
                    Files.deleteIfExists(p);
                  } catch (IOException e) {
                    // best-effort teardown
                  }
                });
      } catch (IOException e) {
        // best-effort teardown
      }
    }
  }

  private void index(String docId, String collection) {
    Map<String, Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID, docId);
    fields.put(SchemaFields.DOC_UID, docId + "#1");
    fields.put("content", "content of " + docId);
    fields.put(SchemaFields.PATH, docId);
    fields.put(SchemaFields.COLLECTION, collection);
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
  }

  private void commitAndRefresh() {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  /** The composition {@code IndexSettleOps} performs: settle, attributed commit, blocking refresh. */
  private void settle(boolean expungeDeletesOnly, int maxSegments) {
    runtime.indexingCoordinator().settle(expungeDeletesOnly, maxSegments);
    runtime.commitOps().commitAndTrack(CommitReason.SETTLE);
    runtime.commitOps().maybeRefreshBlocking();
  }

  private long hitsFor(String collection) {
    return runtime
        .readPathOps()
        .search(
            new TermQuery(new Term(SchemaFields.COLLECTION, collection)),
            100,
            Set.of(SchemaFields.DOC_ID),
            null,
            null)
        .totalHits();
  }

  /**
   * Indexes 20 documents and deletes 3 of them. The 15% ratio is deliberate: TieredMergePolicy
   * reclaims deletes on its own above {@code deletesPctAllowed} (20%), so a bigger deletion would
   * leave nothing for the settle to do and the test would pass without exercising it.
   */
  private static final List<String> DROPPED =
      List.of("c:/corpus/doc-1.txt", "c:/corpus/doc-3.txt", "c:/corpus/doc-5.txt");

  private void indexTwentyAndDeleteThree() {
    for (int i = 0; i < 20; i++) {
      String docId = "c:/corpus/doc-" + i + ".txt";
      index(docId, DROPPED.contains(docId) ? "drop" : "keep");
    }
    commitAndRefresh();
    assertEquals(20, runtime.indexCountOps().maxDoc());
    assertEquals(20, runtime.indexCountOps().docCount());

    DROPPED.forEach(docId -> runtime.indexingCoordinator().deleteById(docId));
    commitAndRefresh();
  }

  @Test
  @DisplayName("collapses maxDoc onto numDocs and leaves the survivors retrievable")
  void purgesTombstonesAndKeepsSurvivors() {
    indexTwentyAndDeleteThree();

    assertEquals(
        20,
        runtime.indexCountOps().maxDoc(),
        "the deleted documents must still be present as tombstones before the settle");
    assertEquals(17, runtime.indexCountOps().docCount());
    assertTrue(
        runtime.indexCountOps().maxDoc() > runtime.indexCountOps().docCount(),
        "precondition: the index carries merge debt");

    settle(true, 0);

    assertEquals(
        17,
        runtime.indexCountOps().maxDoc(),
        "after the settle every tombstone must be gone: maxDoc == numDocs");
    assertEquals(17, runtime.indexCountOps().docCount());
    assertEquals(17, hitsFor("keep"), "every surviving document must still be retrievable");
    assertEquals(0, hitsFor("drop"), "no deleted document may come back");
    assertNotNull(runtime.documentFieldOps().getDocumentField("c:/corpus/doc-0.txt", "content"));
    assertNull(runtime.documentFieldOps().getDocumentField("c:/corpus/doc-1.txt", "content"));
  }

  @Test
  @DisplayName("a second settle is a no-op — nothing left to expunge")
  void secondSettleIsANoOp() {
    indexTwentyAndDeleteThree();

    settle(true, 0);
    long maxDocAfterFirst = runtime.indexCountOps().maxDoc();
    assertEquals(17, maxDocAfterFirst);
    settle(true, 0);

    assertEquals(maxDocAfterFirst, runtime.indexCountOps().maxDoc());
    assertEquals(17, runtime.indexCountOps().docCount());
  }

  @Test
  @DisplayName("the force-merge variant collapses the segment layout to maxSegments")
  void forceMergeVariantCollapsesSegments() {
    for (int batch = 0; batch < 5; batch++) {
      for (int i = 0; i < 4; i++) {
        index("c:/corpus/batch-" + batch + "-doc-" + i + ".txt", "keep");
      }
      commitAndRefresh();
    }
    assertTrue(
        runtime.indexCountOps().segmentCount() > 1,
        "precondition: five committed batches must leave more than one segment, got "
            + runtime.indexCountOps().segmentCount());

    settle(false, 1);

    assertEquals(
        1, runtime.indexCountOps().segmentCount(), "force-merge must collapse to one segment");
    assertEquals(20, runtime.indexCountOps().docCount());
    assertEquals(20, hitsFor("keep"));
  }
}
