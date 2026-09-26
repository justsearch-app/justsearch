package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import tools.jackson.databind.ObjectMapper;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

class StatusCountsIntegrationTest extends LuceneExecutorTestBase {

  @TempDir Path tempDir;

  @RegisterExtension
  SystemPropertyExtension sysprops = new SystemPropertyExtension("justsearch.config");

  @Test
  void wholeDocumentEmbeddingAndSpladeCountsExcludeChunks() throws Exception {
    Path dataDir = tempDir.resolve("data");
    Files.createDirectories(dataDir);
    Path config =
        writeConfig(
            "app:\n  data_dir: "
                + dataDir.toString().replace("\\", "\\\\")
                + "\n"
                + "index:\n"
                + "  collections:\n"
                + "    - name: statuscounts\n"
                + "      roots: ['ignored']\n"
                + "  vector:\n"
                + "    dimension: 4\n");
    System.setProperty("justsearch.config", config.toString());

    var runtime = createRuntimeWithStatusFields(4);
    try {
      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID,
                  "parent-1",
                  SchemaFields.DOC_UID,
                  "parent-1#0",
                  SchemaFields.PATH,
                  "d:\\docs\\parent-1.pdf",
                  SchemaFields.FILENAME,
                  "parent-1.pdf",
                  SchemaFields.CONTENT,
                  "parent content",
                  SchemaFields.EMBEDDING_STATUS,
                  SchemaFields.EMBEDDING_STATUS_COMPLETED,
                  SchemaFields.SPLADE_STATUS,
                  SchemaFields.SPLADE_STATUS_COMPLETED)));

      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID,
                  "chunk-1",
                  SchemaFields.DOC_UID,
                  "chunk-1#0",
                  SchemaFields.PATH,
                  "d:\\docs\\parent-1.pdf",
                  SchemaFields.FILENAME,
                  "parent-1.pdf",
                  SchemaFields.PARENT_DOC_ID,
                  "parent-1",
                  SchemaFields.IS_CHUNK,
                  "true",
                  SchemaFields.CONTENT,
                  "chunk content",
                  SchemaFields.EMBEDDING_STATUS,
                  SchemaFields.EMBEDDING_STATUS_PENDING,
                  SchemaFields.SPLADE_STATUS,
                  SchemaFields.SPLADE_STATUS_FAILED,
                  SchemaFields.CHUNK_EMBEDDING_STATUS,
                  SchemaFields.EMBEDDING_STATUS_COMPLETED)));

      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      var embeddingCounts = runtime.indexCountOps().queryEmbeddingCounts();
      assertEquals(1, embeddingCounts.total(), "Whole-doc embedding total should exclude chunks");
      assertEquals(
          1, embeddingCounts.completed(), "Whole-doc embedding completed should count the parent");
      assertEquals(0, embeddingCounts.pending(), "Chunk embedding status must not leak into parent counts");
      assertEquals(0, embeddingCounts.failed(), "Chunk embedding status must not leak into parent counts");

      var spladeCounts = runtime.indexCountOps().querySpladeFeatureCounts();
      assertEquals(1, spladeCounts.total(), "Whole-doc SPLADE total should exclude chunks");
      assertEquals(1, spladeCounts.completed(), "Whole-doc SPLADE completed should count the parent");
      assertEquals(0, spladeCounts.pending(), "Chunk SPLADE status must not leak into parent counts");
      assertEquals(0, spladeCounts.failed(), "Chunk SPLADE status must not leak into parent counts");

      var chunkCounts = runtime.indexCountOps().queryChunkEmbeddingCounts();
      assertEquals(1, chunkCounts.total(), "Chunk embedding total should count only chunk docs");
      assertEquals(1, chunkCounts.completed(), "Chunk embedding completed should count the chunk");
      assertEquals(0, chunkCounts.pending(), "Only the explicit chunk status should count");
      assertEquals(0, chunkCounts.failed(), "Only the explicit chunk status should count");
    } finally {
      runtime.close();
    }
  }

  private Path writeConfig(String yaml) throws Exception {
    Path config = tempDir.resolve("config.yaml");
    Files.writeString(config, yaml);
    return config;
  }

  private RunningRuntime createRuntimeWithStatusFields(int dim) {
    try {
      String json =
          """
          {
            "fields": [
              { "id": "doc_id", "type": "keyword", "stored": true, "docValues": true, "roles": ["id"] },
              { "id": "doc_uid", "type": "keyword", "stored": false, "docValues": true, "roles": ["tiebreak"] },
              { "id": "path", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "filename", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "parent_doc_id", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "content", "type": "text", "stored": true, "docValues": false },
              { "id": "is_chunk", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "embedding_status", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "chunk_embedding_status", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "splade_status", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
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
}
