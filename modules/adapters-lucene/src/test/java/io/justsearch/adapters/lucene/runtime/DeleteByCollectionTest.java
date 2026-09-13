/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 811 (C-2a) — the removal route for collection-tagged ad-hoc ingests.
 *
 * <p>{@code deleteByPathPrefix} is watched-root-prefix driven, so a document ingested from a path
 * under no watched root had NO removal route at all. These tests pin the two properties that make
 * the new route honest: it deletes the named collection's documents (parents AND chunks), and it
 * leaves every other collection — including the untagged default bucket — untouched.
 */
@DisplayName("deleteByCollection")
final class DeleteByCollectionTest extends LuceneExecutorTestBase {

  private RunningRuntime runtime;
  private Path indexDir;

  private static final CommitMetadataSource TEST_METADATA_SOURCE =
      () ->
          Map.of(
              "index_fingerprint", "delete-by-collection-test-1.0.0",
              "schema_fp", "test-fingerprint",
              "boosts_fp", "test-boosts",
              "dag_hash", "test-dag-hash",
              "pipeline_budget_profile", "test-profile",
              "field_catalog_hash", "test-catalog-hash",
              "synonyms_hash", "test-synonyms-hash");

  private static final CommitMetadataValidator TEST_VALIDATOR = metadata -> {};

  private static FieldCatalogDef createTestCatalog() {
    return new FieldCatalogDef(
        "delete-by-collection-test-v1",
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
    indexDir = Files.createTempDirectory("delete-by-collection-index-");
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
    if (collection != null) {
      fields.put(SchemaFields.COLLECTION, collection);
    }
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
  }

  private void indexChunk(String chunkId, String parentDocId, String collection) {
    Map<String, Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID, chunkId);
    fields.put(SchemaFields.DOC_UID, chunkId + "#1");
    fields.put("content", "chunk of " + parentDocId);
    fields.put(SchemaFields.PATH, parentDocId);
    fields.put(SchemaFields.PARENT_DOC_ID, parentDocId);
    fields.put(SchemaFields.IS_CHUNK, "true");
    if (collection != null) {
      fields.put(SchemaFields.COLLECTION, collection);
    }
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
  }

  private void commitAndRefresh() {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  private String contentOf(String docId) {
    return runtime.documentFieldOps().getDocumentField(docId, "content");
  }

  @Test
  @DisplayName("deletes exactly the named collection's documents; other collections survive")
  void deletesOnlyTheNamedCollection() {
    index("c:/loose/a.txt", "mcp-ingest");
    index("c:/loose/b.txt", "mcp-ingest");
    index("c:/watched/keep.txt", "work-notes");
    index("c:/watched/untagged.txt", null); // the default bucket — must survive
    commitAndRefresh();

    int deleted = runtime.indexingCoordinator().deleteByCollection("mcp-ingest");
    commitAndRefresh();

    assertEquals(2, deleted, "both mcp-ingest documents must be reported");
    assertNull(contentOf("c:/loose/a.txt"), "target collection document must be gone");
    assertNull(contentOf("c:/loose/b.txt"), "target collection document must be gone");
    assertNotNull(contentOf("c:/watched/keep.txt"), "a different collection must survive");
    assertNotNull(contentOf("c:/watched/untagged.txt"), "the untagged default bucket must survive");
  }

  @Test
  @DisplayName("chunk documents carrying the collection are deleted too")
  void deletesChunks() {
    index("c:/loose/parent.txt", "mcp-ingest");
    indexChunk("c:/loose/parent.txt#chunk_0", "c:/loose/parent.txt", "mcp-ingest");
    indexChunk("c:/loose/parent.txt#chunk_1", "c:/loose/parent.txt", "mcp-ingest");
    index("c:/other/keep.txt", "other");
    commitAndRefresh();

    int deleted = runtime.indexingCoordinator().deleteByCollection("mcp-ingest");
    commitAndRefresh();

    assertEquals(3, deleted, "parent + 2 chunks");
    assertNull(contentOf("c:/loose/parent.txt#chunk_0"));
    assertNull(contentOf("c:/loose/parent.txt#chunk_1"));
    assertNotNull(contentOf("c:/other/keep.txt"));
  }

  @Test
  @DisplayName("a second call is retry-safe and reports 0")
  void secondCallIsRetrySafe() {
    index("c:/loose/a.txt", "mcp-ingest");
    commitAndRefresh();

    assertEquals(1, runtime.indexingCoordinator().deleteByCollection("mcp-ingest"));
    commitAndRefresh();
    assertEquals(0, runtime.indexingCoordinator().deleteByCollection("mcp-ingest"));
  }

  @Test
  @DisplayName("a blank collection is refused rather than matching everything")
  void blankCollectionRefused() {
    assertThrows(
        IllegalArgumentException.class,
        () -> runtime.indexingCoordinator().deleteByCollection("  "));
    assertThrows(
        IllegalArgumentException.class, () -> runtime.indexingCoordinator().deleteByCollection(null));
  }
}
