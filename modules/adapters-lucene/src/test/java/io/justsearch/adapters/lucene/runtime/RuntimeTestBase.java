package io.justsearch.adapters.lucene.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import io.justsearch.core.execution.TestEngineExecutors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared base for runtime integration tests. Provides config/lifecycle helpers and the
 * SystemPropertyExtension that saves/restores common system properties.
 */
abstract class RuntimeTestBase {

  private final TestEngineExecutors testExecutors = new TestEngineExecutors();
  private final LuceneExecutorRegistrations luceneExecutors =
      new LuceneExecutorRegistrations(testExecutors);

  @AfterEach
  void closeTestExecutors() {
    luceneExecutors.close();
    testExecutors.close();
  }

  LuceneRuntimeBuilder withTestExecutors(LuceneRuntimeBuilder builder) {
    return builder.withExecutorRegistrations(luceneExecutors);
  }

  @TempDir Path tempDir;

  @RegisterExtension
  SystemPropertyExtension sysprops =
      new SystemPropertyExtension(
          "justsearch.config",
          "index.hybrid.text_candidate_multiplier",
          "index.hybrid.vector_candidate_multiplier",
          "index.hybrid.fusion_strategy");

  Path writeConfig(String yaml) throws IOException {
    Path f = tempDir.resolve("config.yaml");
    Files.writeString(f, yaml);
    return f;
  }

  Path dataDir() throws IOException {
    Path d = tempDir.resolve("data");
    Files.createDirectories(d);
    return d;
  }

  RunningRuntime createRuntimeWithDim(int dim) {
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
              { "id": "vector", "type": "vector", "stored": false, "docValues": false, "rmwPolicy": "preserve-reread", "vector": { "dimension": %d } }
            ]
          }
          """
              .formatted(dim);
      var mapper = new ObjectMapper();
      var fieldMapper = new FieldMapper(mapper.readTree(json));

      LuceneRuntimeBuilder builder = new IndexSchema(
              fieldMapper,
              new io.justsearch.adapters.lucene.analyzers.SsotAnalyzerRegistry(),
              io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource::new,
              new io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator(),
              null)
          .ephemeral();
      return withTestExecutors(builder).open();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /** Build an IndexSchema with the standard test fields. Useful for holder-swap tests where two runtimes share a path. */
  IndexSchema buildSchemaWithDim(int dim) {
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
              { "id": "vector", "type": "vector", "stored": false, "docValues": false, "rmwPolicy": "preserve-reread", "vector": { "dimension": %d } }
            ]
          }
          """
              .formatted(dim);
      var mapper = new ObjectMapper();
      var fieldMapper = new FieldMapper(mapper.readTree(json));

      return new IndexSchema(
          fieldMapper,
          new io.justsearch.adapters.lucene.analyzers.SsotAnalyzerRegistry(),
          io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource::new,
          new io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator(),
          null);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  RunningRuntime createRuntimeWithDimAndMultiValued(int dim) {
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
              { "id": "mime", "type": "keyword", "stored": false, "docValues": true, "roles": ["filter", "facet"] },
              { "id": "entity_persons_raw", "type": "keyword", "stored": true, "docValues": true, "multiValued": true, "roles": ["filter", "facet"] },
              { "id": "vector", "type": "vector", "stored": false, "docValues": false, "rmwPolicy": "preserve-reread", "vector": { "dimension": %d } }
            ]
          }
          """
              .formatted(dim);
      var mapper = new ObjectMapper();
      var fieldMapper = new FieldMapper(mapper.readTree(json));

      LuceneRuntimeBuilder builder = new IndexSchema(
              fieldMapper,
              new io.justsearch.adapters.lucene.analyzers.SsotAnalyzerRegistry(),
              io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource::new,
              new io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator(),
              null)
          .ephemeral();
      return withTestExecutors(builder).open();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
