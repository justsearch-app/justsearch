package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.databind.ObjectMapper;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.junit.jupiter.api.Test;

class PathUpdateIntegrationTest extends LuceneExecutorTestBase {

  @Test
  void updateDocumentPathsRenamesParentAndChunks() throws Exception {
    String prev = System.getProperty("justsearch.config");
    Path base;
    Path cfg;
    try {
      base = Files.createTempDirectory("justsearch-pathupdate-test-");
      String yaml =
          "app:\n  data_dir: "
              + base.toString().replace("\\", "\\\\")
              + "\n"
              + "index:\n  collections:\n    - name: pathupdatetest\n      roots: ['ignored']\n"
              + "  vector:\n    dimension: 4\n";
      cfg = Files.createTempFile("justsearch-config-", ".yaml");
      Files.writeString(cfg, yaml);
      System.setProperty("justsearch.config", cfg.toString());

      var runtime = createRuntimeWithDim(4);

      String oldPath = "d:\\docs\\report.pdf";
      String newPath = "d:\\docs\\renamed-report.pdf";

      // Parent document
      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, oldPath,
                  SchemaFields.DOC_UID, oldPath + "#0",
                  SchemaFields.PATH, oldPath,
                  SchemaFields.FILENAME, "report.pdf",
                  SchemaFields.CONTENT, "quarterly earnings summary",
                  SchemaFields.MIME, "application/pdf")));

      // Chunk 0
      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, "chunk:abc-001",
                  SchemaFields.DOC_UID, "chunk:abc-001#0",
                  SchemaFields.PATH, oldPath,
                  SchemaFields.CONTENT, "chunk zero content",
                  SchemaFields.IS_CHUNK, "true",
                  SchemaFields.PARENT_DOC_ID, oldPath)));

      // Chunk 1
      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, "chunk:abc-002",
                  SchemaFields.DOC_UID, "chunk:abc-002#0",
                  SchemaFields.PATH, oldPath,
                  SchemaFields.CONTENT, "chunk one content",
                  SchemaFields.IS_CHUNK, "true",
                  SchemaFields.PARENT_DOC_ID, oldPath)));

      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      // Update paths
      int updated = runtime.indexingCoordinator().updateDocumentPaths(oldPath, newPath);
      assertEquals(3, updated, "Should update parent + 2 chunks");

      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      // Verify parent: DOC_ID changed, PATH changed, FILENAME changed
      var searchResult = runtime.textQueryOps().searchText("earnings", 10, null);
      assertNotNull(searchResult);
      assertEquals(1, searchResult.hits().size());
      assertEquals(newPath, searchResult.hits().get(0).docId(), "Parent DOC_ID should be new path");

      // Verify old path no longer found
      var allDocs =
          runtime.readPathOps().search(
              new MatchAllDocsQuery(),
              10,
              Set.of(
                  SchemaFields.PATH,
                  SchemaFields.FILENAME,
                  SchemaFields.PARENT_DOC_ID,
                  SchemaFields.IS_CHUNK),
              LuceneRuntimeTypes.RuntimeSearchSort.RELEVANCE,
              null);
      assertEquals(3, allDocs.hits().size(), "Should still have 3 documents total");

      // Check all docs have new path
      for (var hit : allDocs.hits()) {
        var path = hit.fields().get(SchemaFields.PATH);
        assertEquals(newPath, path, "All docs should have new path, got: " + path);
      }

      // Verify parent has new filename
      var parentHit =
          allDocs.hits().stream()
              .filter(h -> h.fields().get(SchemaFields.IS_CHUNK) == null
                  || !"true".equals(h.fields().get(SchemaFields.IS_CHUNK)))
              .findFirst()
              .orElseThrow();
      assertEquals(
          "renamed-report.pdf",
          parentHit.fields().get(SchemaFields.FILENAME),
          "Parent filename should be updated");

      // Verify chunks have new PARENT_DOC_ID
      var chunkHits =
          allDocs.hits().stream()
              .filter(h -> "true".equals(h.fields().get(SchemaFields.IS_CHUNK)))
              .toList();
      assertEquals(2, chunkHits.size(), "Should have 2 chunks");
      for (var chunk : chunkHits) {
        assertEquals(
            newPath,
            chunk.fields().get(SchemaFields.PARENT_DOC_ID),
            "Chunk PARENT_DOC_ID should be updated");
      }

      runtime.close();
    } finally {
      if (prev == null) {
        System.clearProperty("justsearch.config");
      } else {
        System.setProperty("justsearch.config", prev);
      }
    }
  }

  @Test
  void updateDocumentPathsReturnsZeroForMissingPath() throws Exception {
    String prev = System.getProperty("justsearch.config");
    Path base;
    try {
      base = Files.createTempDirectory("justsearch-pathupdate-miss-test-");
      String yaml =
          "app:\n  data_dir: "
              + base.toString().replace("\\", "\\\\")
              + "\n"
              + "index:\n  collections:\n    - name: pathupdatemisstest\n      roots: ['ignored']\n"
              + "  vector:\n    dimension: 4\n";
      Path cfg = Files.createTempFile("justsearch-config-", ".yaml");
      Files.writeString(cfg, yaml);
      System.setProperty("justsearch.config", cfg.toString());

      var runtime = createRuntimeWithDim(4);

      // Index a document
      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, "d:\\docs\\exists.pdf",
                  SchemaFields.DOC_UID, "d:\\docs\\exists.pdf#0",
                  SchemaFields.PATH, "d:\\docs\\exists.pdf",
                  SchemaFields.FILENAME, "exists.pdf",
                  SchemaFields.CONTENT, "content")));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      // Update a path that doesn't exist
      int updated = runtime.indexingCoordinator().updateDocumentPaths("d:\\docs\\not-found.pdf", "d:\\docs\\new.pdf");
      assertEquals(0, updated, "Should return 0 for non-existent path");

      // Original doc should be unchanged
      var result = runtime.textQueryOps().searchText("content", 10, null);
      assertEquals(1, result.hits().size());
      assertEquals("d:\\docs\\exists.pdf", result.hits().get(0).docId());

      runtime.close();
    } finally {
      if (prev == null) {
        System.clearProperty("justsearch.config");
      } else {
        System.setProperty("justsearch.config", prev);
      }
    }
  }

  @Test
  void updateDocumentPathsHandlesNullAndBlankInputs() throws Exception {
    String prev = System.getProperty("justsearch.config");
    Path base;
    try {
      base = Files.createTempDirectory("justsearch-pathupdate-null-test-");
      String yaml =
          "app:\n  data_dir: "
              + base.toString().replace("\\", "\\\\")
              + "\n"
              + "index:\n  collections:\n    - name: pathupdatenulltest\n      roots: ['ignored']\n"
              + "  vector:\n    dimension: 4\n";
      Path cfg = Files.createTempFile("justsearch-config-", ".yaml");
      Files.writeString(cfg, yaml);
      System.setProperty("justsearch.config", cfg.toString());

      var runtime = createRuntimeWithDim(4);

      assertEquals(0, runtime.indexingCoordinator().updateDocumentPaths(null, "new"), "Null old path should return 0");
      assertEquals(0, runtime.indexingCoordinator().updateDocumentPaths("old", null), "Null new path should return 0");
      assertEquals(0, runtime.indexingCoordinator().updateDocumentPaths("", "new"), "Empty old path should return 0");
      assertEquals(0, runtime.indexingCoordinator().updateDocumentPaths("old", "  "), "Blank new path should return 0");

      runtime.close();
    } finally {
      if (prev == null) {
        System.clearProperty("justsearch.config");
      } else {
        System.setProperty("justsearch.config", prev);
      }
    }
  }

  @Test
  void updateDocumentPathsWithNoChunks() throws Exception {
    String prev = System.getProperty("justsearch.config");
    Path base;
    try {
      base = Files.createTempDirectory("justsearch-pathupdate-nochunk-test-");
      String yaml =
          "app:\n  data_dir: "
              + base.toString().replace("\\", "\\\\")
              + "\n"
              + "index:\n  collections:\n    - name: pathupdatenochunktest\n      roots: ['ignored']\n"
              + "  vector:\n    dimension: 4\n";
      Path cfg = Files.createTempFile("justsearch-config-", ".yaml");
      Files.writeString(cfg, yaml);
      System.setProperty("justsearch.config", cfg.toString());

      var runtime = createRuntimeWithDim(4);

      String oldPath = "d:\\docs\\simple.txt";
      String newPath = "d:\\docs\\moved.txt";

      // Only parent document, no chunks
      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, oldPath,
                  SchemaFields.DOC_UID, oldPath + "#0",
                  SchemaFields.PATH, oldPath,
                  SchemaFields.FILENAME, "simple.txt",
                  SchemaFields.CONTENT, "simple file content")));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      int updated = runtime.indexingCoordinator().updateDocumentPaths(oldPath, newPath);
      assertEquals(1, updated, "Should update only the parent document");

      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      var result = runtime.textQueryOps().searchText("simple", 10, null);
      assertEquals(1, result.hits().size());
      assertEquals(newPath, result.hits().get(0).docId());

      runtime.close();
    } finally {
      if (prev == null) {
        System.clearProperty("justsearch.config");
      } else {
        System.setProperty("justsearch.config", prev);
      }
    }
  }

  @Test
  void updateDocumentPathsPreservesEmbeddingStatus() throws Exception {
    String prev = System.getProperty("justsearch.config");
    Path base;
    try {
      base = Files.createTempDirectory("justsearch-pathupdate-embed-test-");
      String yaml =
          "app:\n  data_dir: "
              + base.toString().replace("\\", "\\\\")
              + "\n"
              + "index:\n  collections:\n    - name: pathupdateembedtest\n      roots: ['ignored']\n"
              + "  vector:\n    dimension: 4\n";
      Path cfg = Files.createTempFile("justsearch-config-", ".yaml");
      Files.writeString(cfg, yaml);
      System.setProperty("justsearch.config", cfg.toString());

      var runtime = createRuntimeWithDim(4);

      String oldPath = "d:\\docs\\embed-test.pdf";
      String newPath = "d:\\docs\\embed-moved.pdf";

      // Index with COMPLETED embedding status
      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, oldPath,
                  SchemaFields.DOC_UID, oldPath + "#0",
                  SchemaFields.PATH, oldPath,
                  SchemaFields.FILENAME, "embed-test.pdf",
                  SchemaFields.CONTENT, "embeddings test content",
                  SchemaFields.EMBEDDING_STATUS, "COMPLETED")));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      // Update paths — a MOVE/RENAME does not change content, so the vector is preserved by the
      // RMW engine (tempdoc 711) and embedding_status must NOT be reset to PENDING.
      int updated = runtime.indexingCoordinator().updateDocumentPaths(oldPath, newPath);
      assertEquals(1, updated);

      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      // Verify embedding_status stayed COMPLETED (no loss-compensation re-queue).
      var result =
          runtime.readPathOps().search(
              new MatchAllDocsQuery(),
              10,
              Set.of(SchemaFields.EMBEDDING_STATUS),
              LuceneRuntimeTypes.RuntimeSearchSort.RELEVANCE,
              null);
      assertEquals(1, result.hits().size());
      assertEquals(
          "COMPLETED",
          result.hits().get(0).fields().get(SchemaFields.EMBEDDING_STATUS),
          "embedding_status should be preserved (not reset) after a path update — the vector is kept");

      runtime.close();
    } finally {
      if (prev == null) {
        System.clearProperty("justsearch.config");
      } else {
        System.setProperty("justsearch.config", prev);
      }
    }
  }

  @Test
  void readModifyWritePreservesMultiValuedFields() throws Exception {
    String prev = System.getProperty("justsearch.config");
    Path base;
    try {
      base = Files.createTempDirectory("justsearch-pathupdate-multi-test-");
      String yaml =
          "app:\n  data_dir: "
              + base.toString().replace("\\", "\\\\")
              + "\n"
              + "index:\n  collections:\n    - name: pathupdatemultitest\n      roots: ['ignored']\n"
              + "  vector:\n    dimension: 4\n";
      Path cfg = Files.createTempFile("justsearch-config-", ".yaml");
      Files.writeString(cfg, yaml);
      System.setProperty("justsearch.config", cfg.toString());

      var runtime = createRuntimeWithExtendedCatalog();

      String oldPath = "d:\\docs\\multi-entity.pdf";
      String newPath = "d:\\docs\\multi-entity-moved.pdf";

      // Index with multi-valued entity field
      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, oldPath,
                  SchemaFields.DOC_UID, oldPath + "#0",
                  SchemaFields.PATH, oldPath,
                  SchemaFields.FILENAME, "multi-entity.pdf",
                  SchemaFields.CONTENT, "Alice and Bob at Acme Corp",
                  "entity_persons_raw", java.util.List.of("Alice", "Bob"))));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      // Update paths — should preserve multi-valued fields
      int updated = runtime.indexingCoordinator().updateDocumentPaths(oldPath, newPath);
      assertEquals(1, updated);

      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      // Verify both entity values survive via TermQuery on their StringField entries
      var facetResult = runtime.readPathOps().search(
          new org.apache.lucene.search.TermQuery(
              new org.apache.lucene.index.Term("entity_persons_raw", "Alice")),
          10,
          Set.of(SchemaFields.DOC_ID),
          LuceneRuntimeTypes.RuntimeSearchSort.RELEVANCE,
          null);
      assertEquals(1, facetResult.hits().size(),
          "Multi-valued field 'Alice' should survive readModifyWrite");
      assertEquals(newPath, facetResult.hits().get(0).docId());

      var facetResult2 = runtime.readPathOps().search(
          new org.apache.lucene.search.TermQuery(
              new org.apache.lucene.index.Term("entity_persons_raw", "Bob")),
          10,
          Set.of(SchemaFields.DOC_ID),
          LuceneRuntimeTypes.RuntimeSearchSort.RELEVANCE,
          null);
      assertEquals(1, facetResult2.hits().size(),
          "Multi-valued field 'Bob' should survive readModifyWrite");

      runtime.close();
    } finally {
      if (prev == null) {
        System.clearProperty("justsearch.config");
      } else {
        System.setProperty("justsearch.config", prev);
      }
    }
  }

  @Test
  void updateDocumentPathsDoesNotResetNerStatus() throws Exception {
    String prev = System.getProperty("justsearch.config");
    Path base;
    try {
      base = Files.createTempDirectory("justsearch-pathupdate-ner-test-");
      String yaml =
          "app:\n  data_dir: "
              + base.toString().replace("\\", "\\\\")
              + "\n"
              + "index:\n  collections:\n    - name: pathupdatenrtest\n      roots: ['ignored']\n"
              + "  vector:\n    dimension: 4\n";
      Path cfg = Files.createTempFile("justsearch-config-", ".yaml");
      Files.writeString(cfg, yaml);
      System.setProperty("justsearch.config", cfg.toString());

      // Use extended catalog which includes ner_status
      var runtime = createRuntimeWithExtendedCatalog();

      String oldPath = "d:\\docs\\ner-test.pdf";
      String newPath = "d:\\docs\\ner-moved.pdf";

      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, oldPath,
                  SchemaFields.DOC_UID, oldPath + "#0",
                  SchemaFields.PATH, oldPath,
                  SchemaFields.FILENAME, "ner-test.pdf",
                  SchemaFields.CONTENT, "Alice works at Acme Corp")));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      int updated = runtime.indexingCoordinator().updateDocumentPaths(oldPath, newPath);
      assertEquals(1, updated);

      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      // NER entity fields are stored, so a MOVE preserves them; the loss-compensation reset was
      // removed (tempdoc 711). A path update must NOT stamp ner_status=PENDING.
      var result =
          runtime.readPathOps().search(
              new org.apache.lucene.search.TermQuery(
                  new org.apache.lucene.index.Term(
                      SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_PENDING)),
              10,
              Set.of(SchemaFields.DOC_ID),
              LuceneRuntimeTypes.RuntimeSearchSort.RELEVANCE,
              null);
      assertEquals(
          0, result.hits().size(), "ner_status must NOT be reset to PENDING after a path update");

      runtime.close();
    } finally {
      if (prev == null) {
        System.clearProperty("justsearch.config");
      } else {
        System.setProperty("justsearch.config", prev);
      }
    }
  }

  @Test
  void updateDocumentPathsPreservesChunkEmbeddingStatus() throws Exception {
    String prev = System.getProperty("justsearch.config");
    Path base;
    try {
      base = Files.createTempDirectory("justsearch-pathupdate-chunkembed-test-");
      String yaml =
          "app:\n  data_dir: "
              + base.toString().replace("\\", "\\\\")
              + "\n"
              + "index:\n  collections:\n    - name: chunkembedtest\n      roots: ['ignored']\n"
              + "  vector:\n    dimension: 4\n";
      Path cfg = Files.createTempFile("justsearch-config-", ".yaml");
      Files.writeString(cfg, yaml);
      System.setProperty("justsearch.config", cfg.toString());

      var runtime = createRuntimeWithDim(4);

      String oldPath = "d:\\docs\\chunk-embed.pdf";
      String newPath = "d:\\docs\\chunk-embed-moved.pdf";

      // Parent with COMPLETED status
      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, oldPath,
                  SchemaFields.DOC_UID, oldPath + "#0",
                  SchemaFields.PATH, oldPath,
                  SchemaFields.FILENAME, "chunk-embed.pdf",
                  SchemaFields.CONTENT, "parent document",
                  SchemaFields.EMBEDDING_STATUS, "COMPLETED")));

      // Chunk with COMPLETED status
      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, "chunk:xyz-001",
                  SchemaFields.DOC_UID, "chunk:xyz-001#0",
                  SchemaFields.PATH, oldPath,
                  SchemaFields.CONTENT, "chunk content",
                  SchemaFields.IS_CHUNK, "true",
                  SchemaFields.PARENT_DOC_ID, oldPath,
                  SchemaFields.EMBEDDING_STATUS, "COMPLETED")));

      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      int updated = runtime.indexingCoordinator().updateDocumentPaths(oldPath, newPath);
      assertEquals(2, updated, "Should update parent + 1 chunk");

      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      // Verify ALL documents (parent + chunk) keep embedding_status=COMPLETED — the vectors are
      // preserved across the move (tempdoc 711), so the loss-compensation re-queue was removed.
      var result =
          runtime.readPathOps().search(
              new MatchAllDocsQuery(),
              10,
              Set.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.IS_CHUNK),
              LuceneRuntimeTypes.RuntimeSearchSort.RELEVANCE,
              null);
      assertEquals(2, result.hits().size());
      for (var hit : result.hits()) {
        assertEquals(
            "COMPLETED",
            hit.fields().get(SchemaFields.EMBEDDING_STATUS),
            "All docs should keep embedding_status=COMPLETED, is_chunk="
                + hit.fields().get(SchemaFields.IS_CHUNK));
      }

      runtime.close();
    } finally {
      if (prev == null) {
        System.clearProperty("justsearch.config");
      } else {
        System.setProperty("justsearch.config", prev);
      }
    }
  }

  private RunningRuntime createRuntimeWithDim(int dim) {
    try {
      String json =
          """
          {
            "fields": [
              { "id": "doc_id", "type": "keyword", "stored": true, "docValues": true, "roles": ["id"] },
              { "id": "doc_uid", "type": "keyword", "stored": false, "docValues": true, "roles": ["tiebreak"] },
              { "id": "path", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "filename", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "title", "type": "text", "stored": true, "docValues": false },
              { "id": "mime", "type": "keyword", "stored": false, "docValues": true, "roles": ["filter"] },
              { "id": "language", "type": "keyword", "stored": false, "docValues": true, "roles": ["filter"] },
              { "id": "modified_at", "type": "long", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "size_bytes", "type": "long", "stored": false, "docValues": true, "roles": ["filter"] },
              { "id": "is_chunk", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "parent_doc_id", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "content", "type": "text", "stored": true, "docValues": false },
              { "id": "embedding_status", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "vector", "type": "vector", "stored": false, "docValues": false, "rmwPolicy": "preserve-reread", "vector": { "dimension": %d } }
            ]
          }
          """
              .formatted(dim);
      var mapper = new ObjectMapper();
      var fieldMapper = new FieldMapper(mapper.readTree(json));

      return new IndexSchema(fieldMapper, new io.justsearch.adapters.lucene.analyzers.SsotAnalyzerRegistry(), io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource::new, new io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator(), null).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private RunningRuntime createRuntimeWithExtendedCatalog() {
    try {
      String json =
          """
          {
            "fields": [
              { "id": "doc_id", "type": "keyword", "stored": true, "docValues": true, "roles": ["id"] },
              { "id": "doc_uid", "type": "keyword", "stored": false, "docValues": true, "roles": ["tiebreak"] },
              { "id": "path", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "filename", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "content", "type": "text", "stored": true, "docValues": false },
              { "id": "embedding_status", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "ner_status", "type": "keyword", "stored": false, "docValues": true, "roles": ["filter"] },
              { "id": "embedding_retry_count", "type": "long", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "ner_retry_count", "type": "long", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "entity_persons_raw", "type": "keyword", "stored": true, "docValues": true, "multiValued": true, "roles": ["filter"] },
              { "id": "vector", "type": "vector", "stored": false, "docValues": false, "rmwPolicy": "preserve-reread", "vector": { "dimension": 4 } }
            ]
          }
          """;
      var mapper = new ObjectMapper();
      var fieldMapper = new FieldMapper(mapper.readTree(json));

      return new IndexSchema(fieldMapper, new io.justsearch.adapters.lucene.analyzers.SsotAnalyzerRegistry(), io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource::new, new io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator(), null).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
