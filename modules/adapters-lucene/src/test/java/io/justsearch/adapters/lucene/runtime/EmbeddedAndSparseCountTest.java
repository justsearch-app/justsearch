/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * E-U9 (Spec E step E0b): {@link IndexCountOps#queryEmbeddedAndSparseCount()}, the joint "fully
 * embedded and sparse" count S5 is measured in. It is one count with both statuses, so it must
 * differ from every per-stage counter whenever the two stages are finished on different documents.
 */
@DisplayName("E-U9: the joint embedded-and-sparse count")
class EmbeddedAndSparseCountTest extends LuceneExecutorTestBase {

  private static final String SEP = File.separator;
  private static final String ROOT = SEP + "lib" + SEP + "joint";

  @TempDir Path tempDir;

  private RunningRuntime runtime;

  @BeforeEach
  void setUp() {
    runtime = openRuntime(tempDir.resolve("index"));
  }

  @AfterEach
  void tearDown() throws Exception {
    if (runtime != null) {
      runtime.close();
    }
  }

  @Test
  @DisplayName("counts only documents done in BOTH stages; COMPLETED_EMPTY is a sparse success")
  void countsDocumentsDoneInBothStages() {
    String done = SchemaFields.EMBEDDING_STATUS_COMPLETED;
    index(doc("both", done, SchemaFields.SPLADE_STATUS_COMPLETED, false));
    index(doc("both-empty", done, SchemaFields.SPLADE_STATUS_COMPLETED_EMPTY, false));
    index(doc("embedded-only", done, SchemaFields.SPLADE_STATUS_PENDING, false));
    index(doc("embedded-sparse-failed", done, SchemaFields.SPLADE_STATUS_FAILED, false));
    index(doc("sparse-only", SchemaFields.EMBEDDING_STATUS_PENDING,
        SchemaFields.SPLADE_STATUS_COMPLETED, false));
    index(doc("embedded-no-sparse-field", done, null, false));
    index(doc("neither", SchemaFields.EMBEDDING_STATUS_PENDING,
        SchemaFields.SPLADE_STATUS_PENDING, false));
    // A chunk done in both stages is not a whole document.
    index(doc("chunk", done, SchemaFields.SPLADE_STATUS_COMPLETED, true));
    commit();

    IndexCountOps counts = runtime.indexCountOps();
    assertEquals(2L, counts.queryEmbeddedAndSparseCount());
    // The per-stage counters cannot stand in for it: embedding says 5, sparse says 3.
    assertEquals(5, counts.queryEmbeddingCounts().completed());
    assertEquals(3, counts.querySpladeFeatureCounts().completed());
  }

  @Test
  @DisplayName("an empty index counts zero")
  void emptyIndexCountsZero() {
    assertEquals(0L, runtime.indexCountOps().queryEmbeddedAndSparseCount());
  }

  // ---- fixture ------------------------------------------------------------------

  private void index(IndexDocument doc) {
    runtime.indexingCoordinator().indexSingle(doc);
  }

  private void commit() {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  /** A {@code null} status omits the field. */
  private static IndexDocument doc(
      String id, String embeddingStatus, String spladeStatus, boolean chunk) {
    Map<String, Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID, id);
    fields.put(SchemaFields.DOC_UID, id + "#0");
    fields.put(SchemaFields.PATH, ROOT + SEP + id + ".txt");
    fields.put(SchemaFields.CONTENT, "content of " + id);
    if (chunk) {
      fields.put(SchemaFields.IS_CHUNK, "true");
    }
    if (embeddingStatus != null) {
      fields.put(SchemaFields.EMBEDDING_STATUS, embeddingStatus);
    }
    if (spladeStatus != null) {
      fields.put(SchemaFields.SPLADE_STATUS, spladeStatus);
    }
    return new IndexDocument(fields);
  }

  private RunningRuntime openRuntime(Path indexDir) {
    try {
      String json =
          """
          {
            "fields": [
              { "id": "doc_id", "type": "keyword", "stored": true, "docValues": true, "roles": ["id"] },
              { "id": "doc_uid", "type": "keyword", "stored": false, "docValues": true, "roles": ["tiebreak"] },
              { "id": "path", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "content", "type": "text", "stored": true, "docValues": false },
              { "id": "is_chunk", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "embedding_status", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "splade_status", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] }
            ]
          }
          """;
      var fieldMapper = new FieldMapper(new ObjectMapper().readTree(json));
      return new IndexSchema(
              fieldMapper,
              new io.justsearch.adapters.lucene.analyzers.SsotAnalyzerRegistry(),
              io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource::new,
              new io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator(),
              null)
          .atPath(indexDir).withExecutorRegistrations(testLuceneExecutors())
          .open();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
