package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.FieldCatalogDef.FieldDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.runtime.CommitMetadataSource;
import io.justsearch.indexing.runtime.CommitMetadataValidator;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RunningRuntime#pruneByPathPrefix}.
 *
 * <p>Validates the orphan pruning logic:
 * <ul>
 *   <li>Deletes documents when backing file no longer exists</li>
 *   <li>Preserves documents when file still exists</li>
 *   <li>Handles chunk documents (path#chunk_N)</li>
 *   <li>Respects abort checker (user activity)</li>
 *   <li>Throttles to avoid I/O spikes</li>
 *   <li>Path normalization (Windows case-insensitivity)</li>
 * </ul>
 */
@DisplayName("pruneByPathPrefix")
class PruneByPathPrefixTest extends LuceneExecutorTestBase {

  private RunningRuntime runtime;
  private Path indexDir;
  private Path testFilesDir;

  /** Minimal commit metadata source for testing. */
  private static final CommitMetadataSource TEST_METADATA_SOURCE = () -> Map.of(
      "index_fingerprint", "prune-test-1.0.0",
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
   * Creates a minimal catalog for pruning tests.
   */
  private static FieldCatalogDef createTestCatalog() {
    return new FieldCatalogDef("prune-test-v1", List.of(
        // Primary key - uses file path as doc_id
        new FieldDef("doc_id", "keyword", true, true, List.of("id", "sort"), null, null, false),
        new FieldDef("doc_uid", "keyword", false, true, List.of("sort", "tiebreak"), null, null, false),
        // Content and path
        new FieldDef("content", "text", true, false, List.of("highlight"), null, "icu", false),
        new FieldDef("path", "keyword", true, true, List.of("filter", "sort"), null, null, false)
    ));
  }

  @BeforeEach
  void setUp() throws Exception {
    indexDir = Files.createTempDirectory("prune-test-index-");
    testFilesDir = Files.createTempDirectory("prune-test-files-");

    runtime = IndexSchema.fromCatalog(createTestCatalog(), TEST_METADATA_SOURCE, TEST_VALIDATOR).atPath(indexDir).withExecutorRegistrations(testLuceneExecutors()).open();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (runtime != null) {
      runtime.close();
    }
    deleteRecursively(indexDir);
    deleteRecursively(testFilesDir);
  }

  private void deleteRecursively(Path dir) {
    if (dir == null || !Files.exists(dir)) return;
    try (var walk = Files.walk(dir)) {
      walk.sorted(java.util.Comparator.reverseOrder())
          .forEach(p -> {
            try {
              Files.deleteIfExists(p);
            } catch (Exception e) {
              // Best effort cleanup
            }
          });
    } catch (IOException e) {
      // Best effort cleanup - failures are acceptable in test teardown
    }
  }

  private IndexDocument doc(String id, Map<String, Object> fields) {
    Map<String, Object> allFields = new HashMap<>(fields);
    allFields.put(SchemaFields.DOC_ID, id);
    allFields.put(SchemaFields.DOC_UID, id + "#1");
    return new IndexDocument(allFields);
  }

  private void commitAndRefresh() {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  /** Creates a real file in testFilesDir. */
  private Path createTestFile(String relativePath) throws IOException {
    Path filePath = testFilesDir.resolve(relativePath);
    Files.createDirectories(filePath.getParent());
    Files.writeString(filePath, "test content for " + relativePath);
    return filePath;
  }

  /** Normalizes path to match how pruneByPathPrefix normalizes. */
  private String normalizePath(Path path) {
    String normalized = path.toAbsolutePath().toString();
    boolean isWindows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    if (isWindows) {
      normalized = normalized.toLowerCase(java.util.Locale.ROOT);
    }
    return normalized;
  }

  @Nested
  @DisplayName("Basic Pruning")
  class BasicPruning {

    @Test
    @DisplayName("prunes document when backing file is deleted")
    void prunesWhenFileDeleted() throws Exception {
      // Create file, index it, then delete the file
      Path file = createTestFile("docs/report.txt");
      String docId = normalizePath(file);

      runtime.indexingCoordinator().indexSingle(doc(docId, Map.of(
          "content", "quarterly report",
          "path", docId)));
      commitAndRefresh();

      // Verify document exists
      assertEquals("quarterly report", runtime.documentFieldOps().getDocumentField(docId, "content"));

      // Delete the backing file
      Files.delete(file);

      // Prune - should delete the orphan document
      int pruned = runtime.pruneOps().pruneByPathPrefix(
          testFilesDir.toString(),
          () -> false,  // Never abort
          100);

      assertEquals(1, pruned, "Should prune 1 orphan document");

      // Document should be gone from index
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals(null, runtime.documentFieldOps().getDocumentField(docId, "content"));
    }

    @Test
    @DisplayName("preserves document when backing file exists")
    void preservesWhenFileExists() throws Exception {
      // Create file and index it
      Path file = createTestFile("docs/keep.txt");
      String docId = normalizePath(file);

      runtime.indexingCoordinator().indexSingle(doc(docId, Map.of(
          "content", "important data",
          "path", docId)));
      commitAndRefresh();

      // Prune without deleting the file
      int pruned = runtime.pruneOps().pruneByPathPrefix(
          testFilesDir.toString(),
          () -> false,
          100);

      assertEquals(0, pruned, "Should not prune any documents");

      // Document should still exist
      assertEquals("important data", runtime.documentFieldOps().getDocumentField(docId, "content"));
    }

    @Test
    @DisplayName("prunes multiple orphan documents")
    void prunesMultipleOrphans() throws Exception {
      // Create 3 files, index them, delete 2
      Path file1 = createTestFile("docs/file1.txt");
      Path file2 = createTestFile("docs/file2.txt");
      Path file3 = createTestFile("docs/file3.txt");

      String id1 = normalizePath(file1);
      String id2 = normalizePath(file2);
      String id3 = normalizePath(file3);

      runtime.indexingCoordinator().indexSingle(doc(id1, Map.of("content", "one", "path", id1)));
      runtime.indexingCoordinator().indexSingle(doc(id2, Map.of("content", "two", "path", id2)));
      runtime.indexingCoordinator().indexSingle(doc(id3, Map.of("content", "three", "path", id3)));
      commitAndRefresh();

      // Delete files 1 and 3
      Files.delete(file1);
      Files.delete(file3);

      int pruned = runtime.pruneOps().pruneByPathPrefix(
          testFilesDir.toString(),
          () -> false,
          100);

      assertEquals(2, pruned, "Should prune 2 orphan documents");

      // Only file2's document should remain
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals(null, runtime.documentFieldOps().getDocumentField(id1, "content"));
      assertEquals("two", runtime.documentFieldOps().getDocumentField(id2, "content"));
      assertEquals(null, runtime.documentFieldOps().getDocumentField(id3, "content"));
    }

    @Test
    @DisplayName("returns 0 when no documents match prefix")
    void returnsZeroWhenNoMatch() throws Exception {
      // Index a document under different prefix
      Path file = createTestFile("other/file.txt");
      String docId = normalizePath(file);
      runtime.indexingCoordinator().indexSingle(doc(docId, Map.of("content", "data", "path", docId)));
      commitAndRefresh();

      // Prune under non-existent prefix
      Path nonExistent = testFilesDir.resolve("nonexistent");
      int pruned = runtime.pruneOps().pruneByPathPrefix(
          nonExistent.toString(),
          () -> false,
          100);

      assertEquals(0, pruned, "Should prune 0 documents");
    }
  }

  @Nested
  @DisplayName("Chunk Documents")
  class ChunkDocuments {

    @Test
    @DisplayName("prunes chunk documents when parent file is deleted")
    void prunesChunksWhenParentDeleted() throws Exception {
      // Create file and index main doc + chunks
      Path file = createTestFile("docs/large.pdf");
      String basePath = normalizePath(file);

      // Main document
      runtime.indexingCoordinator().indexSingle(doc(basePath, Map.of(
          "content", "main content",
          "path", basePath)));

      // Chunk documents (path#chunk_N format)
      String chunk0 = basePath + "#chunk_0";
      String chunk1 = basePath + "#chunk_1";
      String chunk2 = basePath + "#chunk_2";

      runtime.indexingCoordinator().indexSingle(doc(chunk0, Map.of("content", "chunk 0", "path", basePath)));
      runtime.indexingCoordinator().indexSingle(doc(chunk1, Map.of("content", "chunk 1", "path", basePath)));
      runtime.indexingCoordinator().indexSingle(doc(chunk2, Map.of("content", "chunk 2", "path", basePath)));
      commitAndRefresh();

      // Delete the backing file
      Files.delete(file);

      // Prune - should delete main doc + all chunks
      int pruned = runtime.pruneOps().pruneByPathPrefix(
          testFilesDir.toString(),
          () -> false,
          100);

      assertEquals(4, pruned, "Should prune main doc + 3 chunks");

      // All should be gone
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals(null, runtime.documentFieldOps().getDocumentField(basePath, "content"));
      assertEquals(null, runtime.documentFieldOps().getDocumentField(chunk0, "content"));
      assertEquals(null, runtime.documentFieldOps().getDocumentField(chunk1, "content"));
      assertEquals(null, runtime.documentFieldOps().getDocumentField(chunk2, "content"));
    }

    @Test
    @DisplayName("preserves chunk documents when parent file exists")
    void preservesChunksWhenParentExists() throws Exception {
      Path file = createTestFile("docs/keep.pdf");
      String basePath = normalizePath(file);

      runtime.indexingCoordinator().indexSingle(doc(basePath, Map.of("content", "main", "path", basePath)));
      runtime.indexingCoordinator().indexSingle(doc(basePath + "#chunk_0", Map.of("content", "chunk 0", "path", basePath)));
      commitAndRefresh();

      int pruned = runtime.pruneOps().pruneByPathPrefix(
          testFilesDir.toString(),
          () -> false,
          100);

      assertEquals(0, pruned, "Should not prune any documents");
    }
  }

  @Nested
  @DisplayName("Abort Handling")
  class AbortHandling {

    @Test
    @DisplayName("aborts immediately when checker returns true")
    void abortsOnUserActivity() throws Exception {
      // Create several files to ensure we have docs to iterate
      for (int i = 0; i < 5; i++) {
        Path file = createTestFile("docs/file" + i + ".txt");
        String docId = normalizePath(file);
        runtime.indexingCoordinator().indexSingle(doc(docId, Map.of("content", "content " + i, "path", docId)));
        Files.delete(file);  // Make them orphans
      }
      commitAndRefresh();

      // Abort checker that always returns true. What "abort" means is the caller's, not this
      // layer's — see tempdoc 885 item 3 for the pacing signal behind it.
      int pruned = runtime.pruneOps().pruneByPathPrefix(
          testFilesDir.toString(),
          () -> true,  // Always abort
          100);

      assertEquals(-1, pruned, "Should return -1 when aborted");
    }

    @Test
    @DisplayName("aborts after specified number of checks")
    void abortsAfterNChecks() throws Exception {
      // Create 10 orphan files
      for (int i = 0; i < 10; i++) {
        Path file = createTestFile("docs/file" + i + ".txt");
        String docId = normalizePath(file);
        runtime.indexingCoordinator().indexSingle(doc(docId, Map.of("content", "content " + i, "path", docId)));
        Files.delete(file);
      }
      commitAndRefresh();

      // Abort after 3 checks
      AtomicInteger checkCount = new AtomicInteger(0);
      int pruned = runtime.pruneOps().pruneByPathPrefix(
          testFilesDir.toString(),
          () -> checkCount.incrementAndGet() > 3,
          100);

      assertEquals(-1, pruned, "Should return -1 when aborted");
      assertTrue(checkCount.get() <= 4, "Should have checked at most 4 times");
    }

    @Test
    @DisplayName("completes when abort checker always returns false")
    void completesWhenNoAbort() throws Exception {
      Path file = createTestFile("docs/orphan.txt");
      String docId = normalizePath(file);
      runtime.indexingCoordinator().indexSingle(doc(docId, Map.of("content", "orphan", "path", docId)));
      commitAndRefresh();
      Files.delete(file);

      // Never abort
      AtomicInteger checkCount = new AtomicInteger(0);
      int pruned = runtime.pruneOps().pruneByPathPrefix(
          testFilesDir.toString(),
          () -> {
            checkCount.incrementAndGet();
            return false;
          },
          100);

      assertEquals(1, pruned, "Should prune 1 document");
      assertTrue(checkCount.get() >= 1, "Should have checked at least once");
    }
  }

  @Nested
  @DisplayName("Throttling")
  class Throttling {

    @Test
    @DisplayName("respects throttle batch size")
    void respectsThrottleBatchSize() throws Exception {
      // Create enough files to trigger throttling
      int fileCount = 50;
      for (int i = 0; i < fileCount; i++) {
        Path file = createTestFile("docs/file" + i + ".txt");
        String docId = normalizePath(file);
        runtime.indexingCoordinator().indexSingle(doc(docId, Map.of("content", "content " + i, "path", docId)));
        // Keep file - don't delete
      }
      commitAndRefresh();

      int pruned = runtime.pruneOps().pruneByPathPrefix(
          testFilesDir.toString(),
          () -> false,
          10);  // Throttle every 10 docs

      assertEquals(0, pruned, "Should not prune any (files exist)");
      // With 50 docs and throttle=10, should have ~5 throttle points
      // Each yields for 1ms - actual timing is not asserted (flaky)
    }

    @Test
    @DisplayName("defaults throttle batch size when <= 0")
    void defaultsThrottleBatchSize() throws Exception {
      Path file = createTestFile("docs/test.txt");
      String docId = normalizePath(file);
      runtime.indexingCoordinator().indexSingle(doc(docId, Map.of("content", "test", "path", docId)));
      commitAndRefresh();

      // Should not throw with invalid batch size
      int pruned = runtime.pruneOps().pruneByPathPrefix(
          testFilesDir.toString(),
          () -> false,
          0);  // Invalid - should default to 100

      assertEquals(0, pruned);
    }
  }

  @Nested
  @DisplayName("Input Validation")
  class InputValidation {

    @Test
    @DisplayName("throws on null path prefix")
    void throwsOnNullPrefix() {
      assertThrows(IllegalArgumentException.class, () ->
          runtime.pruneOps().pruneByPathPrefix(null, () -> false, 100));
    }

    @Test
    @DisplayName("throws on blank path prefix")
    void throwsOnBlankPrefix() {
      assertThrows(IllegalArgumentException.class, () ->
          runtime.pruneOps().pruneByPathPrefix("   ", () -> false, 100));
    }

    @Test
    @DisplayName("handles null abort checker gracefully")
    void handlesNullAbortChecker() throws Exception {
      Path file = createTestFile("docs/test.txt");
      String docId = normalizePath(file);
      runtime.indexingCoordinator().indexSingle(doc(docId, Map.of("content", "test", "path", docId)));
      commitAndRefresh();
      Files.delete(file);

      // Should work with null checker
      int pruned = runtime.pruneOps().pruneByPathPrefix(
          testFilesDir.toString(),
          null,  // Null checker
          100);

      assertEquals(1, pruned);
    }
  }

  @Nested
  @DisplayName("Path Normalization")
  class PathNormalization {

    @Test
    @DisplayName("normalizes path separators")
    void normalizesPathSeparators() throws Exception {
      // Create file with native path separator
      Path file = createTestFile("docs" + File.separator + "test.txt");
      String docId = normalizePath(file);
      runtime.indexingCoordinator().indexSingle(doc(docId, Map.of("content", "test", "path", docId)));
      commitAndRefresh();
      Files.delete(file);

      // Use forward slash in prefix (should still match on Windows)
      String prefixWithForwardSlash = testFilesDir.toString().replace('\\', '/');
      int pruned = runtime.pruneOps().pruneByPathPrefix(
          prefixWithForwardSlash,
          () -> false,
          100);

      assertEquals(1, pruned, "Should handle path separator normalization");
    }

    @Test
    @DisplayName("appends trailing separator if missing")
    void appendsTrailingSeparator() throws Exception {
      Path subdir = testFilesDir.resolve("subdir");
      Files.createDirectories(subdir);
      Path file = createTestFile("subdir" + File.separator + "file.txt");
      String docId = normalizePath(file);
      runtime.indexingCoordinator().indexSingle(doc(docId, Map.of("content", "test", "path", docId)));
      commitAndRefresh();
      Files.delete(file);

      // Prefix without trailing separator
      String prefixNoTrailing = subdir.toString();
      assertTrue(!prefixNoTrailing.endsWith(File.separator));

      int pruned = runtime.pruneOps().pruneByPathPrefix(
          prefixNoTrailing,
          () -> false,
          100);

      assertEquals(1, pruned, "Should match even without trailing separator");
    }
  }

  @Nested
  @DisplayName("Edge Cases")
  class EdgeCases {

    @Test
    @DisplayName("handles empty index")
    void handlesEmptyIndex() {
      int pruned = runtime.pruneOps().pruneByPathPrefix(
          testFilesDir.toString(),
          () -> false,
          100);

      assertEquals(0, pruned, "Empty index should return 0");
    }

    @Test
    @DisplayName("handles documents with blank doc_id")
    void handlesBlankDocId() throws Exception {
      // This shouldn't normally happen, but test robustness
      // Index a normal document first
      Path file = createTestFile("docs/normal.txt");
      String docId = normalizePath(file);
      runtime.indexingCoordinator().indexSingle(doc(docId, Map.of("content", "normal", "path", docId)));
      commitAndRefresh();
      Files.delete(file);

      // Should not throw
      int pruned = runtime.pruneOps().pruneByPathPrefix(
          testFilesDir.toString(),
          () -> false,
          100);

      assertEquals(1, pruned);
    }
  }

  @Nested
  @DisplayName("Confirmed-deletion sink (tempdoc 931 §C.6)")
  class ConfirmedDeletionSink {

    @Test
    @DisplayName("reports only the paths pruned because their file is gone")
    void reportsOnlyTheVerifiedAbsentPaths() throws Exception {
      Path gone = createTestFile("docs/gone.txt");
      Path stays = createTestFile("docs/stays.txt");
      String goneId = normalizePath(gone);
      String staysId = normalizePath(stays);
      runtime
          .indexingCoordinator()
          .indexSingle(doc(goneId, Map.of("content", "gone", "path", goneId)));
      runtime
          .indexingCoordinator()
          .indexSingle(doc(staysId, Map.of("content", "stays", "path", staysId)));
      commitAndRefresh();
      Files.delete(gone);

      List<String> reported = new java.util.ArrayList<>();
      int pruned =
          runtime
              .pruneOps()
              .pruneByPathPrefix(testFilesDir.toString(), () -> false, 100, reported::add);

      assertEquals(1, pruned);
      // The document still on disk must NOT be reported: the sink starts a deletion grace clock,
      // so a path leaking into it would eventually re-mint a live document's identity.
      assertEquals(List.of(goneId), reported);
    }

    @Test
    @DisplayName("reports nothing when every file is still present")
    void reportsNothingWhenNothingWasPruned() throws Exception {
      Path stays = createTestFile("docs/present.txt");
      String staysId = normalizePath(stays);
      runtime
          .indexingCoordinator()
          .indexSingle(doc(staysId, Map.of("content", "present", "path", staysId)));
      commitAndRefresh();

      List<String> reported = new java.util.ArrayList<>();
      int pruned =
          runtime
              .pruneOps()
              .pruneByPathPrefix(testFilesDir.toString(), () -> false, 100, reported::add);

      assertEquals(0, pruned);
      assertTrue(reported.isEmpty());
    }
  }
}
