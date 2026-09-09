package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.FolderBrowseResult;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.FolderFilesResult;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.FolderInfo;
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
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("FolderBrowseEngine")
class FolderBrowseEngineTest extends LuceneExecutorTestBase {

  // ========== Path Parsing Unit Tests ==========

  @Nested
  @DisplayName("extractChildFolder")
  class ExtractChildFolderTests {

    @Test
    @DisplayName("returns child folder name for nested path")
    void returnsChildFolder() {
      assertEquals(
          "reports",
          FolderBrowseEngine.extractChildFolder(
              "d:\\docs\\reports\\q1.pdf", "d:\\docs\\", '\\'));
    }

    @Test
    @DisplayName("returns null for direct child file")
    void returnsNullForDirectChild() {
      assertNull(
          FolderBrowseEngine.extractChildFolder("d:\\docs\\file.txt", "d:\\docs\\", '\\'));
    }

    @Test
    @DisplayName("returns null when prefix does not match")
    void returnsNullWhenPrefixMismatch() {
      assertNull(
          FolderBrowseEngine.extractChildFolder("d:\\other\\file.txt", "d:\\docs\\", '\\'));
    }

    @Test
    @DisplayName("handles deeply nested paths returning only immediate child")
    void handlesDeepNesting() {
      assertEquals(
          "a",
          FolderBrowseEngine.extractChildFolder(
              "d:\\root\\a\\b\\c\\file.txt", "d:\\root\\", '\\'));
    }

    @Test
    @DisplayName("works with forward slashes (Unix)")
    void worksWithForwardSlashes() {
      assertEquals(
          "reports",
          FolderBrowseEngine.extractChildFolder(
              "/home/user/docs/reports/q1.pdf", "/home/user/docs/", '/'));
    }
  }

  @Nested
  @DisplayName("isDirectChild")
  class IsDirectChildTests {

    @Test
    @DisplayName("returns true for direct child file")
    void trueForDirectChild() {
      assertTrue(
          FolderBrowseEngine.isDirectChild("d:\\docs\\file.txt", "d:\\docs\\", '\\'));
    }

    @Test
    @DisplayName("returns false for file in subfolder")
    void falseForSubfolderFile() {
      assertFalse(
          FolderBrowseEngine.isDirectChild(
              "d:\\docs\\sub\\file.txt", "d:\\docs\\", '\\'));
    }

    @Test
    @DisplayName("returns false when prefix does not match")
    void falseWhenPrefixMismatch() {
      assertFalse(
          FolderBrowseEngine.isDirectChild("d:\\other\\file.txt", "d:\\docs\\", '\\'));
    }

    @Test
    @DisplayName("returns false for empty remainder")
    void falseForEmptyRemainder() {
      assertFalse(FolderBrowseEngine.isDirectChild("d:\\docs\\", "d:\\docs\\", '\\'));
    }

    @Test
    @DisplayName("works with forward slashes (Unix)")
    void worksWithForwardSlashes() {
      assertTrue(
          FolderBrowseEngine.isDirectChild(
              "/home/user/docs/file.txt", "/home/user/docs/", '/'));
      assertFalse(
          FolderBrowseEngine.isDirectChild(
              "/home/user/docs/sub/file.txt", "/home/user/docs/", '/'));
    }
  }

  // ========== Integration Tests ==========

  @Nested
  @DisplayName("integration")
  class IntegrationTests {

    private RunningRuntime runtime;
    private Path indexDir;

    private static final CommitMetadataSource TEST_METADATA_SOURCE =
        () ->
            Map.of(
                "index_fingerprint", "browse-test-1.0.0",
                "schema_fp", "test-fingerprint",
                "boosts_fp", "test-boosts",
                "dag_hash", "test-dag-hash",
                "pipeline_budget_profile", "test-profile",
                "field_catalog_hash", "test-catalog-hash",
                "synonyms_hash", "test-synonyms-hash");

    private static final CommitMetadataValidator TEST_VALIDATOR = metadata -> {};

    private static FieldCatalogDef createTestCatalog() {
      return new FieldCatalogDef(
          "browse-test-v1",
          List.of(
              new FieldDef(
                  "doc_id", "keyword", true, true, List.of("id", "sort"), null, null, false),
              new FieldDef(
                  "doc_uid",
                  "keyword",
                  false,
                  true,
                  List.of("sort", "tiebreak"),
                  null,
                  null,
                  false),
              new FieldDef(
                  "content", "text", true, false, List.of("highlight"), null, "icu", false),
              new FieldDef(
                  "path", "keyword", true, true, List.of("filter", "sort"), null, null, false),
              new FieldDef(
                  "filename", "keyword", true, true, List.of("filter"), null, null, false),
              new FieldDef(
                  "file_kind", "keyword", true, true, List.of("filter"), null, null, false),
              new FieldDef(
                  "size_bytes", "long", true, true, List.of("sort"), null, null, false),
              new FieldDef(
                  "indexed_at", "long", true, true, List.of("sort"), null, null, false),
              new FieldDef(
                  "is_chunk", "keyword", true, true, List.of("filter"), null, null, false)));
    }

    @BeforeEach
    void setUp() throws Exception {
      indexDir = Files.createTempDirectory("browse-test-index-");
      runtime = IndexSchema.fromCatalog(createTestCatalog(), TEST_METADATA_SOURCE, TEST_VALIDATOR).atPath(indexDir).withExecutorRegistrations(testLuceneExecutors()).open();
    }

    @AfterEach
    void tearDown() throws Exception {
      if (runtime != null) {
        runtime.close();
      }
      deleteRecursively(indexDir);
    }

    private void deleteRecursively(Path dir) {
      if (dir == null || !Files.exists(dir)) return;
      try (var walk = Files.walk(dir)) {
        walk.sorted(java.util.Comparator.reverseOrder())
            .forEach(
                p -> {
                  try {
                    Files.deleteIfExists(p);
                  } catch (Exception e) {
                    // Best effort cleanup
                  }
                });
      } catch (IOException e) {
        // Best effort cleanup
      }
    }

    private IndexDocument doc(String docId, String path, long sizeBytes, long indexedAt) {
      Map<String, Object> fields = new HashMap<>();
      fields.put(SchemaFields.DOC_ID, docId);
      fields.put(SchemaFields.DOC_UID, docId + "#1");
      fields.put(SchemaFields.PATH, path);
      fields.put(SchemaFields.SIZE_BYTES, sizeBytes);
      fields.put(SchemaFields.INDEXED_AT, indexedAt);
      fields.put(SchemaFields.FILENAME, path.substring(path.lastIndexOf(File.separatorChar) + 1));
      fields.put(SchemaFields.FILE_KIND, "document");
      return new IndexDocument(fields);
    }

    private IndexDocument chunk(String parentDocId, int chunkIdx, String path) {
      Map<String, Object> fields = new HashMap<>();
      fields.put(SchemaFields.DOC_ID, parentDocId + "#chunk_" + chunkIdx);
      fields.put(SchemaFields.DOC_UID, parentDocId + "#chunk_" + chunkIdx + "#1");
      fields.put(SchemaFields.PATH, path);
      fields.put(SchemaFields.IS_CHUNK, "true");
      return new IndexDocument(fields);
    }

    private void indexAndRefresh(IndexDocument... docs) {
      for (IndexDocument d : docs) {
        runtime.indexingCoordinator().indexSingle(d);
      }
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
    }

    private void assertReaderStillContainsDeletedDocuments() throws IOException {
      var manager = runtime.session().snapshot.searcherManager();
      var searcher = manager.acquire();
      try {
        assertTrue(
            searcher.getIndexReader().maxDoc() > searcher.getIndexReader().numDocs(),
            "regression setup must retain a deleted Lucene document");
      } finally {
        manager.release(searcher);
      }
    }

    private String sep() {
      return String.valueOf(File.separatorChar);
    }

    @Test
    @DisplayName("enumerateFolders returns child folders with correct counts")
    void enumerateFoldersReturnsChildFolders() {
      String root = "d:" + sep() + "docs" + sep();
      String reportsDir = root + "reports" + sep();
      String imagesDir = root + "images" + sep();

      indexAndRefresh(
          doc(reportsDir + "q1.pdf", reportsDir + "q1.pdf", 1000L, 100L),
          doc(reportsDir + "q2.pdf", reportsDir + "q2.pdf", 2000L, 200L),
          doc(imagesDir + "photo.jpg", imagesDir + "photo.jpg", 5000L, 300L),
          doc(root + "readme.txt", root + "readme.txt", 100L, 50L));

      FolderBrowseResult result = runtime.folderBrowseEngine().enumerateFolders(root, 0);

      assertEquals(2, result.folders().size());
      assertFalse(result.truncated());

      // Folders should be sorted alphabetically
      FolderInfo first = result.folders().get(0);
      FolderInfo second = result.folders().get(1);
      assertEquals("images", first.name());
      assertEquals("reports", second.name());

      // Reports should have 2 files
      assertEquals(2, second.fileCount());
      assertEquals(3000L, second.totalSizeBytes());
      assertEquals(200L, second.lastIndexedAt());

      // Images should have 1 file
      assertEquals(1, first.fileCount());
      assertEquals(5000L, first.totalSizeBytes());
    }

    @Test
    @DisplayName("enumerateFolders excludes chunk documents")
    void enumerateFoldersExcludesChunks() {
      String root = "d:" + sep() + "docs" + sep();
      String subDir = root + "sub" + sep();

      indexAndRefresh(
          doc(subDir + "file.txt", subDir + "file.txt", 500L, 100L),
          chunk(subDir + "file.txt", 0, subDir + "file.txt"),
          chunk(subDir + "file.txt", 1, subDir + "file.txt"));

      FolderBrowseResult result = runtime.folderBrowseEngine().enumerateFolders(root, 0);

      assertEquals(1, result.folders().size());
      // Only the original doc, not chunks
      assertEquals(1, result.folders().get(0).fileCount());
    }

    @Test
    @DisplayName("enumerateFolders respects maxFolders and sets truncated")
    void enumerateFoldersRespectsMaxFolders() {
      String root = "d:" + sep() + "docs" + sep();

      indexAndRefresh(
          doc(root + "a" + sep() + "f.txt", root + "a" + sep() + "f.txt", 100L, 1L),
          doc(root + "b" + sep() + "f.txt", root + "b" + sep() + "f.txt", 100L, 1L),
          doc(root + "c" + sep() + "f.txt", root + "c" + sep() + "f.txt", 100L, 1L));

      FolderBrowseResult result = runtime.folderBrowseEngine().enumerateFolders(root, 2);

      assertEquals(2, result.folders().size());
      assertTrue(result.truncated());
      assertEquals("a", result.folders().get(0).name());
      assertEquals("b", result.folders().get(1).name());
    }

    @Test
    @DisplayName("enumerateFolders throws on blank parent path")
    void enumerateFoldersThrowsOnBlankPath() {
      assertThrows(IllegalArgumentException.class, () -> runtime.folderBrowseEngine().enumerateFolders("", 0));
    }

    @Test
    @DisplayName("listFolderFiles returns only direct children")
    void listFolderFilesReturnsDirectChildren() {
      String folder = "d:" + sep() + "docs" + sep();
      String subDir = folder + "sub" + sep();

      indexAndRefresh(
          doc(folder + "file1.txt", folder + "file1.txt", 100L, 1L),
          doc(folder + "file2.txt", folder + "file2.txt", 200L, 2L),
          doc(subDir + "nested.txt", subDir + "nested.txt", 300L, 3L));

      FolderFilesResult result = runtime.folderBrowseEngine().listFolderFiles(folder, 0, Set.of());

      // Only the 2 direct children, not the nested file
      assertEquals(2, result.totalCount());
      assertEquals(2, result.files().size());
    }

    @Test
    @DisplayName("listFolderFiles respects limit while counting all")
    void listFolderFilesRespectsLimit() {
      String folder = "d:" + sep() + "docs" + sep();

      indexAndRefresh(
          doc(folder + "a.txt", folder + "a.txt", 100L, 1L),
          doc(folder + "b.txt", folder + "b.txt", 200L, 2L),
          doc(folder + "c.txt", folder + "c.txt", 300L, 3L));

      FolderFilesResult result = runtime.folderBrowseEngine().listFolderFiles(folder, 2, Set.of());

      assertEquals(2, result.files().size());
      assertEquals(3, result.totalCount());
    }

    @Test
    @DisplayName("folder browsing and ID enumeration exclude deleted-but-unmerged parents")
    void browseAndIdEnumerationExcludeDeletedParents() throws IOException {
      String root = "d:" + sep() + "docs" + sep();
      String deletedFolder = root + "deleted" + sep();
      String deletedPath = deletedFolder + "gone.txt";
      runtime
          .session()
          .snapshot
          .writer()
          .getConfig()
          .setMergePolicy(org.apache.lucene.index.NoMergePolicy.INSTANCE);
      String survivorPath = "d:" + sep() + "outside" + sep() + "survivor.txt";
      indexAndRefresh(
          doc(deletedPath, deletedPath, 100L, 1L),
          doc(survivorPath, survivorPath, 100L, 1L));

      runtime.indexingCoordinator().deleteById(deletedPath);
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      assertReaderStillContainsDeletedDocuments();

      FolderBrowseResult folders = runtime.folderBrowseEngine().enumerateFolders(root, 0);
      FolderFilesResult files =
          runtime.folderBrowseEngine().listFolderFiles(deletedFolder, 0, Set.of());
      var ids = runtime.folderBrowseEngine().listAllDocumentIds(0, 10);

      assertEquals(0, folders.folders().size());
      assertEquals(0, files.totalCount());
      assertEquals(0, files.files().size());
      assertEquals(1, ids.totalCount());
      assertEquals(List.of(survivorPath), ids.docIds());
    }

    @Test
    @DisplayName("document ID enumeration has no hidden 50000-document scan cap")
    void documentIdEnumerationExceedsFormerScanCap() {
      int total = FolderBrowseEngine.DEFAULT_MAX_DOCS_SCANNED + 1;
      int batchSize = 1_000;
      for (int start = 0; start < total; start += batchSize) {
        List<IndexDocument> batch = new java.util.ArrayList<>(batchSize);
        for (int i = start; i < Math.min(start + batchSize, total); i++) {
          String path = "d:" + sep() + "bulk" + sep() + "doc-" + i + ".txt";
          batch.add(doc(path, path, 1L, i));
        }
        runtime.indexingCoordinator().indexBatch(batch);
      }
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      var result =
          runtime
              .folderBrowseEngine()
              .listAllDocumentIds(FolderBrowseEngine.DEFAULT_MAX_DOCS_SCANNED, 10);

      assertEquals(total, result.totalCount());
      assertEquals(1, result.docIds().size());
    }

    @Test
    @DisplayName("document ID enumeration fails closed when a live parent lacks doc_id")
    void documentIdEnumerationRejectsMissingDocumentId() throws IOException {
      runtime
          .session()
          .snapshot
          .writer()
          .addDocument(
              runtime
                  .session()
                  .fieldMapper
                  .toDocument(
                      Map.of(
                          SchemaFields.DOC_UID, "missing-id#0",
                          SchemaFields.PATH, "d:" + sep() + "broken.txt",
                          SchemaFields.CONTENT, "broken"),
                      null));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      assertThrows(
          IllegalStateException.class,
          () -> runtime.folderBrowseEngine().listAllDocumentIds(0, 10));
    }

    @Test
    @DisplayName("listFolderFiles throws on blank folder path")
    void listFolderFilesThrowsOnBlankPath() {
      assertThrows(IllegalArgumentException.class,
          () -> runtime.folderBrowseEngine().listFolderFiles("", 0, Set.of()));
    }

    @Test
    @DisplayName("enumerateFolders on empty index returns empty list")
    void enumerateFoldersOnEmptyIndex() {
      // No documents indexed -- just call with any path
      FolderBrowseResult result = runtime.folderBrowseEngine().enumerateFolders("d:" + sep() + "docs" + sep(), 0);

      assertEquals(0, result.folders().size());
      assertFalse(result.truncated());
    }

    @Test
    @DisplayName("enumerateFolders at root level finds top-level folders")
    void enumerateFoldersAtRoot() {
      String root = "d:" + sep();

      indexAndRefresh(
          doc(root + "docs" + sep() + "file.txt",
              root + "docs" + sep() + "file.txt", 100L, 1L),
          doc(root + "code" + sep() + "main.java",
              root + "code" + sep() + "main.java", 200L, 2L),
          doc(root + "rootfile.txt", root + "rootfile.txt", 50L, 3L));

      FolderBrowseResult result = runtime.folderBrowseEngine().enumerateFolders(root, 0);

      assertEquals(2, result.folders().size());
      assertEquals("code", result.folders().get(0).name());
      assertEquals("docs", result.folders().get(1).name());
    }

    @Test
    @DisplayName("listFolderFiles on empty folder returns empty list")
    void listFolderFilesOnEmptyFolder() {
      String folder = "d:" + sep() + "empty" + sep();

      // Index a file in a different folder
      indexAndRefresh(
          doc("d:" + sep() + "other" + sep() + "file.txt",
              "d:" + sep() + "other" + sep() + "file.txt", 100L, 1L));

      FolderFilesResult result = runtime.folderBrowseEngine().listFolderFiles(folder, 0, Set.of());

      assertEquals(0, result.files().size());
      assertEquals(0, result.totalCount());
    }

    @Test
    @DisplayName("listFolderFiles excludes chunks and returns only parent doc")
    void listFolderFilesExcludesChunks() {
      String folder = "d:" + sep() + "docs" + sep();

      indexAndRefresh(
          doc(folder + "file.txt", folder + "file.txt", 500L, 100L),
          chunk(folder + "file.txt", 0, folder + "file.txt"),
          chunk(folder + "file.txt", 1, folder + "file.txt"));

      FolderFilesResult result = runtime.folderBrowseEngine().listFolderFiles(folder, 0, Set.of());

      assertEquals(1, result.files().size());
      assertEquals(1, result.totalCount());
    }

    @Test
    @DisplayName("enumerateFolders returns nothing for folder with only chunks")
    void enumerateFoldersFolderWithOnlyChunks() {
      String root = "d:" + sep() + "docs" + sep();
      String subDir = root + "sub" + sep();

      indexAndRefresh(
          chunk(subDir + "file.txt", 0, subDir + "file.txt"),
          chunk(subDir + "file.txt", 1, subDir + "file.txt"));

      FolderBrowseResult result = runtime.folderBrowseEngine().enumerateFolders(root, 0);

      assertEquals(0, result.folders().size());
    }

    @Test
    @DisplayName("listFolderFiles with projection returns requested fields")
    void listFolderFilesRespectsProjection() {
      String folder = "d:" + sep() + "docs" + sep();

      indexAndRefresh(
          doc(folder + "file.txt", folder + "file.txt", 500L, 100L));

      // Request specific fields including one that doesn't exist
      FolderFilesResult result = runtime.folderBrowseEngine().listFolderFiles(
          folder, 0, Set.of(SchemaFields.PATH, SchemaFields.FILENAME, "nonexistent_field"));

      assertEquals(1, result.files().size());
      // Valid fields should be present
      assertTrue(result.files().get(0).fields().containsKey(SchemaFields.PATH));
      // Nonexistent field silently omitted (no exception)
    }

    @Test
    @DisplayName("enumerateFolders aggregates size and timestamp correctly")
    void enumerateFoldersAggregatesCorrectly() {
      String root = "d:" + sep() + "docs" + sep();
      String subDir = root + "reports" + sep();

      indexAndRefresh(
          doc(subDir + "a.txt", subDir + "a.txt", 100L, 10L),
          doc(subDir + "b.txt", subDir + "b.txt", 250L, 30L),
          doc(subDir + "c.txt", subDir + "c.txt", 50L, 20L));

      FolderBrowseResult result = runtime.folderBrowseEngine().enumerateFolders(root, 0);

      assertEquals(1, result.folders().size());
      FolderInfo folder = result.folders().get(0);
      assertEquals(3, folder.fileCount());
      assertEquals(400L, folder.totalSizeBytes()); // 100 + 250 + 50
      assertEquals(30L, folder.lastIndexedAt()); // max(10, 30, 20)
    }

    @Test
    @DisplayName("extractChildFolder handles paths with spaces and special characters")
    void extractChildFolderWithSpecialChars() {
      assertEquals(
          "my folder",
          FolderBrowseEngine.extractChildFolder(
              "d:" + sep() + "docs" + sep() + "my folder" + sep() + "file.txt",
              "d:" + sep() + "docs" + sep(),
              File.separatorChar));

      assertEquals(
          "report (2025)",
          FolderBrowseEngine.extractChildFolder(
              "d:" + sep() + "docs" + sep() + "report (2025)" + sep() + "q1.pdf",
              "d:" + sep() + "docs" + sep(),
              File.separatorChar));
    }
  }
}
