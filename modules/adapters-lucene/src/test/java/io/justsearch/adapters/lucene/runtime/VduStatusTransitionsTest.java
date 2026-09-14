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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for VDU status transitions using {@link RunningRuntime#updateDocument}.
 *
 * <p>Verifies that VDU fields can be:
 * <ul>
 *   <li>Set during initial indexing</li>
 *   <li>Updated via read-modify-write pattern</li>
 *   <li>Retrieved after commit</li>
 * </ul>
 */
@DisplayName("VDU Status Transitions")
class VduStatusTransitionsTest extends LuceneExecutorTestBase {

  private RunningRuntime runtime;
  private Path tempDir;

  /** Minimal commit metadata source for testing. */
  private static final CommitMetadataSource TEST_METADATA_SOURCE = () -> Map.of(
      "index_fingerprint", "test-1.0.0",
      "schema_fp", "test-fingerprint",
      "boosts_fp", "test-boosts",
      "dag_hash", "test-dag-hash",
      "pipeline_budget_profile", "test-profile",
      "field_catalog_hash", "test-catalog-hash",
      "synonyms_hash", "test-synonyms-hash"
  );

  /** No-op validator for testing. */
  private static final CommitMetadataValidator TEST_VALIDATOR = metadata -> {};

  /**
   * Creates a test catalog including VDU fields.
   */
  private static FieldCatalogDef createVduCatalog() {
    return new FieldCatalogDef("vdu-test-v1", List.of(
        // Primary key (required)
        new FieldDef("doc_id", "keyword", true, true, List.of("id", "sort"), null, null, false),
        new FieldDef("doc_uid", "keyword", false, true, List.of("sort", "tiebreak"), null, null, false),
        // Content
        new FieldDef("content", "text", true, false, List.of("highlight"), null, "icu", false),
        new FieldDef("path", "keyword", true, true, List.of("filter", "sort"), null, null, false),
        // VDU fields
        new FieldDef("vdu_status", "keyword", true, true, List.of("filter"), null, null, false),
        new FieldDef("vdu_retry_count", "long", true, true, List.of(), null, null, false),
        new FieldDef("vdu_processed", "boolean", true, true, List.of(), null, null, false),
        new FieldDef("vdu_enrichment", "text", true, false, List.of(), null, null, false),
        new FieldDef("vdu_page_count", "long", true, true, List.of(), null, null, false),
        // Embedding status
        new FieldDef("embedding_status", "keyword", true, true, List.of("filter"), null, null, false)
    ));
  }

  @BeforeEach
  void setUp() throws Exception {
    tempDir = Files.createTempDirectory("vdu-status-test-");
    runtime = IndexSchema.fromCatalog(createVduCatalog(), TEST_METADATA_SOURCE, TEST_VALIDATOR).atPath(tempDir).withExecutorRegistrations(testLuceneExecutors()).open();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (runtime != null) {
      runtime.close();
    }
    // Clean up temp directory
    if (tempDir != null && Files.exists(tempDir)) {
      try (var walk = Files.walk(tempDir)) {
        walk.sorted(java.util.Comparator.reverseOrder())
            .forEach(p -> {
              try {
                Files.deleteIfExists(p);
              } catch (Exception e) {
                // Best effort cleanup - failures are acceptable in test teardown
              }
            });
      }
    }
  }

  private IndexDocument doc(String id, Map<String, Object> fields) {
    Map<String, Object> allFields = new HashMap<>(fields);
    allFields.put(SchemaFields.DOC_ID, id);
    allFields.put(SchemaFields.DOC_UID, id + "#1");
    return new IndexDocument(allFields);
  }

  /** Commits and refreshes the searcher so queries see new documents. */
  private void commitAndRefresh() {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  @Nested
  @DisplayName("Initial Indexing")
  class InitialIndexing {

    @Test
    @DisplayName("indexes document with vdu_status=PENDING")
    void indexesWithPendingStatus() {
      runtime.indexingCoordinator().indexSingle(doc("test-1", Map.of(
          "vdu_status", "PENDING",
          "path", "/test/image.png")));
      commitAndRefresh();

      assertEquals("PENDING", runtime.documentFieldOps().getDocumentField("test-1", "vdu_status"));
    }

    @Test
    @DisplayName("indexes document with all VDU fields")
    void indexesWithAllVduFields() {
      Map<String, Object> fields = new HashMap<>();
      fields.put("vdu_status", "PENDING");
      fields.put("vdu_retry_count", 0);
      fields.put("vdu_processed", false);
      fields.put("embedding_status", "NOT_APPLICABLE");
      fields.put("path", "/test/doc.pdf");

      runtime.indexingCoordinator().indexSingle(doc("test-2", fields));
      commitAndRefresh();

      assertEquals("PENDING", runtime.documentFieldOps().getDocumentField("test-2", "vdu_status"));
      assertEquals("0", runtime.documentFieldOps().getDocumentField("test-2", "vdu_retry_count"));
      assertEquals("/test/doc.pdf", runtime.documentFieldOps().getDocumentField("test-2", "path"));
    }
  }

  @Nested
  @DisplayName("Status Updates via updateDocument")
  class StatusUpdates {

    @Test
    @DisplayName("updates vdu_status from PENDING to PROCESSING")
    void updatesToProcessing() {
      // Initial index
      runtime.indexingCoordinator().indexSingle(doc("doc-1", Map.of(
          "vdu_status", "PENDING",
          "vdu_retry_count", 0,
          "path", "/test/image.png")));
      commitAndRefresh();

      // Update status
      boolean updated = runtime.indexingCoordinator().updateDocument("doc-1", Map.of(
          "vdu_status", "PROCESSING",
          "vdu_retry_count", 1));
      commitAndRefresh();

      assertTrue(updated, "updateDocument should return true");
      assertEquals("PROCESSING", runtime.documentFieldOps().getDocumentField("doc-1", "vdu_status"));
      assertEquals("1", runtime.documentFieldOps().getDocumentField("doc-1", "vdu_retry_count"));
      // Original field should be preserved
      assertEquals("/test/image.png", runtime.documentFieldOps().getDocumentField("doc-1", "path"));
    }

    @Test
    @DisplayName("updates vdu_status from PROCESSING to COMPLETED")
    void updatesToCompleted() {
      runtime.indexingCoordinator().indexSingle(doc("doc-2", Map.of(
          "vdu_status", "PROCESSING",
          "path", "/test/invoice.pdf")));
      commitAndRefresh();

      String enrichment = "{\"summary\":\"Invoice for $500\",\"doc_type\":\"invoice\"}";
      runtime.indexingCoordinator().updateDocument("doc-2", Map.of(
          "vdu_status", "COMPLETED",
          "vdu_processed", true,
          "vdu_enrichment", enrichment,
          "vdu_page_count", 2,
          "embedding_status", "PENDING"));
      commitAndRefresh();

      assertEquals("COMPLETED", runtime.documentFieldOps().getDocumentField("doc-2", "vdu_status"));
      assertEquals(enrichment, runtime.documentFieldOps().getDocumentField("doc-2", "vdu_enrichment"));
      assertEquals("2", runtime.documentFieldOps().getDocumentField("doc-2", "vdu_page_count"));
      // VDU completion should trigger embedding
      assertEquals("PENDING", runtime.documentFieldOps().getDocumentField("doc-2", "embedding_status"));
    }

    @Test
    @DisplayName("updates vdu_status from PROCESSING to FAILED")
    void updatesToFailed() {
      runtime.indexingCoordinator().indexSingle(doc("doc-3", Map.of(
          "vdu_status", "PROCESSING",
          "vdu_retry_count", 3,
          "path", "/test/corrupt.png")));
      commitAndRefresh();

      runtime.indexingCoordinator().updateDocument("doc-3", Map.of(
          "vdu_status", "FAILED",
          "vdu_processed", false));
      commitAndRefresh();

      assertEquals("FAILED", runtime.documentFieldOps().getDocumentField("doc-3", "vdu_status"));
      assertEquals("3", runtime.documentFieldOps().getDocumentField("doc-3", "vdu_retry_count"));
    }

    @Test
    @DisplayName("preserves other fields when updating VDU status")
    void preservesOtherFields() {
      runtime.indexingCoordinator().indexSingle(doc("doc-4", Map.of(
          "vdu_status", "PENDING",
          "path", "/important/doc.pdf",
          "content", "Original content here")));
      commitAndRefresh();

      // Only update VDU status
      runtime.indexingCoordinator().updateDocument("doc-4", Map.of("vdu_status", "PROCESSING"));
      commitAndRefresh();

      // Both VDU status and original fields should be present
      assertEquals("PROCESSING", runtime.documentFieldOps().getDocumentField("doc-4", "vdu_status"));
      assertEquals("/important/doc.pdf", runtime.documentFieldOps().getDocumentField("doc-4", "path"));
      assertEquals("Original content here", runtime.documentFieldOps().getDocumentField("doc-4", "content"));
    }

    @Test
    @DisplayName("returns false for non-existent document")
    void returnsFalseForMissingDoc() {
      boolean updated = runtime.indexingCoordinator().updateDocument("nonexistent", Map.of("vdu_status", "PROCESSING"));
      assertTrue(!updated, "updateDocument should return false for missing doc");
    }
  }

  @Nested
  @DisplayName("VDU → Embedding Handoff")
  class VduToEmbeddingHandoff {

    @Test
    @DisplayName("VDU completion sets embedding_status to PENDING")
    void vduCompletionTriggersEmbedding() {
      // Index with initial state
      runtime.indexingCoordinator().indexSingle(doc("handoff-1", Map.of(
          "vdu_status", "PENDING",
          "embedding_status", "NOT_APPLICABLE",
          "content", "Initial",
          "path", "/test/image.png")));
      commitAndRefresh();

      // Simulate VDU completion updating both status and content
      runtime.indexingCoordinator().updateDocument("handoff-1", Map.of(
          "vdu_status", "COMPLETED",
          "vdu_processed", true,
          "content", "Extracted text from image",
          "vdu_enrichment", "{\"summary\":\"Test image\"}",
          "embedding_status", "PENDING"));
      commitAndRefresh();

      assertEquals("COMPLETED", runtime.documentFieldOps().getDocumentField("handoff-1", "vdu_status"));
      assertEquals("PENDING", runtime.documentFieldOps().getDocumentField("handoff-1", "embedding_status"));
      assertEquals("Extracted text from image", runtime.documentFieldOps().getDocumentField("handoff-1", "content"));
    }

    @Test
    @DisplayName("VDU failure does not change embedding_status")
    void vduFailureKeepsEmbeddingStatus() {
      runtime.indexingCoordinator().indexSingle(doc("handoff-2", Map.of(
          "vdu_status", "PENDING",
          "embedding_status", "NOT_APPLICABLE",
          "path", "/test/corrupt.png")));
      commitAndRefresh();

      // VDU fails - should not trigger embedding
      runtime.indexingCoordinator().updateDocument("handoff-2", Map.of(
          "vdu_status", "FAILED",
          "vdu_retry_count", 3));
      commitAndRefresh();

      assertEquals("FAILED", runtime.documentFieldOps().getDocumentField("handoff-2", "vdu_status"));
      assertEquals("NOT_APPLICABLE", runtime.documentFieldOps().getDocumentField("handoff-2", "embedding_status"));
    }
  }

  @Nested
  @DisplayName("Retry Count Tracking")
  class RetryCountTracking {

    @Test
    @DisplayName("increments retry count on each processing attempt")
    void incrementsRetryCount() {
      runtime.indexingCoordinator().indexSingle(doc("retry-1", Map.of(
          "vdu_status", "PENDING",
          "vdu_retry_count", 0,
          "path", "/test/flaky.png")));
      commitAndRefresh();

      // First attempt
      runtime.indexingCoordinator().updateDocument("retry-1", Map.of(
          "vdu_status", "PROCESSING",
          "vdu_retry_count", 1));
      commitAndRefresh();
      assertEquals("1", runtime.documentFieldOps().getDocumentField("retry-1", "vdu_retry_count"));

      // Second attempt (after failure reset)
      runtime.indexingCoordinator().updateDocument("retry-1", Map.of(
          "vdu_status", "PROCESSING",
          "vdu_retry_count", 2));
      commitAndRefresh();
      assertEquals("2", runtime.documentFieldOps().getDocumentField("retry-1", "vdu_retry_count"));

      // Third attempt - max retries exceeded, mark failed
      runtime.indexingCoordinator().updateDocument("retry-1", Map.of(
          "vdu_status", "FAILED",
          "vdu_retry_count", 3));
      commitAndRefresh();
      assertEquals("FAILED", runtime.documentFieldOps().getDocumentField("retry-1", "vdu_status"));
      assertEquals("3", runtime.documentFieldOps().getDocumentField("retry-1", "vdu_retry_count"));
    }
  }

  @Nested
  @DisplayName("Recovery Scenarios")
  class RecoveryScenarios {

    @Test
    @DisplayName("resets PROCESSING documents to PENDING for recovery")
    void resetsProcessingToPending() {
      // Simulate crash scenario: document stuck in PROCESSING
      runtime.indexingCoordinator().indexSingle(doc("stuck-1", Map.of(
          "vdu_status", "PROCESSING",
          "vdu_retry_count", 1,
          "path", "/test/stuck.png")));
      commitAndRefresh();

      // Recovery: reset to PENDING without incrementing retry count
      runtime.indexingCoordinator().updateDocument("stuck-1", Map.of("vdu_status", "PENDING"));
      commitAndRefresh();

      assertEquals("PENDING", runtime.documentFieldOps().getDocumentField("stuck-1", "vdu_status"));
      // Retry count preserved (will be incremented on next actual attempt)
      assertEquals("1", runtime.documentFieldOps().getDocumentField("stuck-1", "vdu_retry_count"));
    }
  }

  @Nested
  @DisplayName("Field Retrieval")
  class FieldRetrieval {

    @Test
    @DisplayName("getDocumentField returns null for missing field")
    void returnsNullForMissingField() {
      runtime.indexingCoordinator().indexSingle(doc("field-1", Map.of(
          "vdu_status", "PENDING",
          "path", "/test/file.png")));
      commitAndRefresh();

      // Field not set
      assertNull(runtime.documentFieldOps().getDocumentField("field-1", "vdu_enrichment"));
      // Field exists
      assertNotNull(runtime.documentFieldOps().getDocumentField("field-1", "vdu_status"));
    }

    @Test
    @DisplayName("getDocumentField returns null for missing document")
    void returnsNullForMissingDoc() {
      assertNull(runtime.documentFieldOps().getDocumentField("nonexistent", "vdu_status"));
    }
  }
}
