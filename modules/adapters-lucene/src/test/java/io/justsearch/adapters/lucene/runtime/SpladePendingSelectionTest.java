/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 931 — the chunk-excluding variant of the status-driven work selection.
 *
 * <p>A chunk carries {@code splade_status=PENDING} from creation regardless of the chunk-SPLADE
 * flag (ChunkDocumentWriter). With the flag off no lane will ever advance that status, so a
 * selection that returns the chunk hands the backfill batch slots it can only rewrite: the pass
 * reports no progress, the population never shrinks, and the cycle budget burns on documents that
 * cannot move. {@code queryNonChunkDocIdsByField} is the selection that keeps the population
 * shrinkable; this pins that it filters on the structural marker and nothing else.
 */
@DisplayName("splade-pending work selection")
class SpladePendingSelectionTest extends LuceneExecutorTestBase {

  @TempDir Path tempDir;

  @RegisterExtension
  SystemPropertyExtension sysprops = new SystemPropertyExtension("justsearch.config");

  private RunningRuntime runtime;

  @BeforeEach
  void setUp() throws Exception {
    Path dataDir = tempDir.resolve("data");
    Files.createDirectories(dataDir);
    Path config = tempDir.resolve("config.yaml");
    Files.writeString(
        config,
        "app:\n  data_dir: "
            + dataDir.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: spladeselection\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n");
    System.setProperty("justsearch.config", config.toString());
    runtime = openRuntime();

    indexParent("parent-pending", "parent alpha body", SchemaFields.SPLADE_STATUS_PENDING);
    indexParent("parent-done", "parent beta body", SchemaFields.SPLADE_STATUS_COMPLETED_EMPTY);
    indexChunk("chunk-pending", "parent-pending", 0, 6, SchemaFields.SPLADE_STATUS_PENDING);
    indexChunk("chunk-done", "parent-pending", 7, 12, SchemaFields.SPLADE_STATUS_COMPLETED_EMPTY);
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (runtime != null) {
      runtime.close();
    }
  }

  @Test
  @DisplayName("the plain selection returns the chunk too — the flag-on population")
  void plainSelectionIncludesChunkDocs() {
    List<String> pending =
        runtime
            .documentFieldOps()
            .queryDocIdsByField(
                SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING, 100);

    assertTrue(
        pending.contains("parent-pending") && pending.contains("chunk-pending"),
        "flag ON, both are genuine SPLADE work: " + pending);
    assertEquals(2, pending.size(), pending.toString());
  }

  @Test
  @DisplayName("the chunk-excluding selection returns only the pending PARENT")
  void nonChunkSelectionExcludesChunkDocs() {
    List<String> pending =
        runtime
            .documentFieldOps()
            .queryNonChunkDocIdsByField(
                SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING, 100);

    assertEquals(
        List.of("parent-pending"),
        pending,
        "a flag-off chunk must not occupy a batch slot; the parent backlog must still be found");
  }

  @Test
  @DisplayName("the exclusion filters on is_chunk, not on the status value")
  void nonChunkSelectionStillHonoursTheStatusValue() {
    assertEquals(
        List.of("parent-done"),
        runtime
            .documentFieldOps()
            .queryNonChunkDocIdsByField(
                SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED_EMPTY, 100),
        "excluding chunks must not widen or narrow the status predicate");
  }

  @Test
  @DisplayName("countNonChunkByField matches the selection it gates, so parked chunks read as 0")
  void nonChunkCountMatchesTheSelection() {
    assertEquals(
        2,
        runtime
            .indexCountOps()
            .countByField(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING),
        "the plain count sees the parked chunk");
    assertEquals(
        1,
        runtime
            .indexCountOps()
            .countNonChunkByField(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING),
        "a permanently-parked chunk population must not read as outstanding work");
  }

  private void indexParent(String docId, String content, String spladeStatus) {
    Map<String, Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID, docId);
    fields.put(SchemaFields.DOC_UID, docId + "#0");
    fields.put(SchemaFields.PATH, docId);
    fields.put(SchemaFields.CONTENT, content);
    fields.put(SchemaFields.SPLADE_STATUS, spladeStatus);
    fields.put(SchemaFields.SPLADE_RETRY_COUNT, "0");
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
  }

  private void indexChunk(
      String chunkId, String parentId, int startChar, int endChar, String spladeStatus) {
    Map<String, Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID, chunkId);
    fields.put(SchemaFields.DOC_UID, chunkId + "#0");
    fields.put(SchemaFields.PATH, parentId);
    fields.put(SchemaFields.IS_CHUNK, "true");
    fields.put(SchemaFields.PARENT_DOC_ID, parentId);
    fields.put(SchemaFields.CHUNK_START_CHAR, String.valueOf(startChar));
    fields.put(SchemaFields.CHUNK_END_CHAR, String.valueOf(endChar));
    fields.put(SchemaFields.CHUNK_CONTENT, "slice");
    fields.put(SchemaFields.SPLADE_STATUS, spladeStatus);
    fields.put(SchemaFields.SPLADE_RETRY_COUNT, "0");
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
  }

  private RunningRuntime openRuntime() {
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
              { "id": "parent_doc_id", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "chunk_content", "type": "text", "stored": false, "docValues": false, "rmwPolicy": "rederive-parent-slice" },
              { "id": "chunk_parent_content_sha256", "type": "keyword", "stored": true, "docValues": false, "roles": [] },
              { "id": "chunk_start_char", "type": "long", "stored": true, "docValues": true },
              { "id": "chunk_end_char", "type": "long", "stored": true, "docValues": true },
              { "id": "splade_status", "type": "keyword", "stored": false, "docValues": true, "roles": ["filter"] },
              { "id": "splade_retry_count", "type": "long", "stored": false, "docValues": true },
              { "id": "splade", "type": "splade", "stored": false, "docValues": false, "rmwPolicy": "reset-status:splade_status" }
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
          .ephemeral().withExecutorRegistrations(testLuceneExecutors())
          .open();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
