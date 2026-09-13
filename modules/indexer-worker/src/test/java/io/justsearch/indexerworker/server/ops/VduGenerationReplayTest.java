/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferCapableQueue;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import io.justsearch.ipc.VduUpdateOutcome;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

@DisplayName("VDU generation replay")
final class VduGenerationReplayTest extends LuceneExecutorTestBase {

  private static final String DOC_ID = "vdu-generation-parent";
  private static final String PARENT_UID = "vdu-generation-parent-uid";
  private static final String DELETE_PATH = "vdu-generation-delete";
  private static final String VDU_CONTENT =
      "Deferred VDU content must replace the re-enumerated parent. ".repeat(500);

  @TempDir Path tempDir;

  @Test
  @DisplayName("ineligible replay drains DELETE and preserves the exact VDU version across reopen")
  void ineligibleReplayDrainsDeleteAndRetainsVduVersion() throws Exception {
    Path dbPath = tempDir.resolve("mixed.db");
    String vduPayload =
        vduPayload(DOC_ID, VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_EMPTY, null);
    SwitchBufferCapableQueue.SwitchBufferOp deferred;
    RunningRuntime runtime = mockRuntime(true);

    try (SqliteJobQueue queue = openQueue(dbPath)) {
      put(queue, "vdu_update:" + DOC_ID, "VDU_UPDATE", vduPayload);
      put(queue, "delete:" + DELETE_PATH, "DELETE", DELETE_PATH);
      deferred = find(queue, "vdu_update:" + DOC_ID);

      drain(queue, runtime, () -> false);

      assertEquals(1, queue.switchBufferDepth());
      assertEquals(deferred, find(queue, deferred.key()));
      verify(runtime.indexingCoordinator()).deleteByIdAndChunks(DELETE_PATH);
      verify(runtime.indexingCoordinator(), never()).updateDocument(anyString(), anyMap());
      verify(runtime.commitOps()).commitAndTrack(CommitReason.SWITCH_BUFFER_REPLAY);
    }

    try (SqliteJobQueue reopened = openQueue(dbPath)) {
      assertEquals(deferred, find(reopened, deferred.key()));
      when(runtime.indexingCoordinator().updateDocument(eq(DOC_ID), anyMap())).thenReturn(true);

      drain(reopened, runtime, () -> true);

      assertEquals(0, reopened.switchBufferDepth());
      verify(runtime.indexingCoordinator()).updateDocument(eq(DOC_ID), anyMap());
      verify(runtime.commitOps(), org.mockito.Mockito.times(2))
          .commitAndTrack(CommitReason.SWITCH_BUFFER_REPLAY);
    }
  }

  @Test
  @DisplayName("a serving eligibility transition during commit retains the VDU version")
  void eligibilityFlipDuringCommitRetainsVduVersion() throws Exception {
    Path dbPath = tempDir.resolve("eligibility-flip.db");
    RunningRuntime runtime = mockRuntime(true);
    when(runtime.indexingCoordinator().updateDocument(eq(DOC_ID), anyMap())).thenReturn(true);
    AtomicBoolean eligible = new AtomicBoolean(true);
    CommitOps commits = runtime.commitOps();
    doAnswer(
            ignored -> {
              eligible.set(false);
              return null;
            })
        .when(commits)
        .commitAndTrack(CommitReason.SWITCH_BUFFER_REPLAY);

    try (SqliteJobQueue queue = openQueue(dbPath)) {
      put(
          queue,
          "vdu_update:" + DOC_ID,
          "VDU_UPDATE",
          vduPayload(DOC_ID, VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_EMPTY, null));
      SwitchBufferCapableQueue.SwitchBufferOp before = find(queue, "vdu_update:" + DOC_ID);

      drain(queue, runtime, eligible::get);

      assertEquals(1, queue.switchBufferDepth());
      assertEquals(before, find(queue, before.key()));
      verify(runtime.indexingCoordinator()).updateDocument(eq(DOC_ID), anyMap());
      verify(runtime.commitOps()).commitAndTrack(CommitReason.SWITCH_BUFFER_REPLAY);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("retainedLegacyRows")
  @DisplayName("legacy marks, recovery without a runtime, and unknown kinds remain durable")
  void invalidLegacyRowsCannotClear(
      String name,
      String kind,
      String payload,
      boolean runtimeAvailable,
      boolean updateSucceeds,
      boolean expectUpdate,
      boolean nullIndexPaths)
      throws Exception {
    Path dbPath = tempDir.resolve(name + ".db");
    RunningRuntime runtime = runtimeAvailable ? mockRuntime(updateSucceeds) : null;

    try (SqliteJobQueue queue = openQueue(dbPath)) {
      put(queue, "legacy:" + name, kind, payload);
      SwitchBufferCapableQueue.SwitchBufferOp before = find(queue, "legacy:" + name);

      drain(queue, runtime, () -> true, nullIndexPaths);

      assertEquals(1, queue.switchBufferDepth());
      assertEquals(before, find(queue, before.key()));
      if (runtime != null && expectUpdate) {
        verify(runtime.indexingCoordinator()).updateDocument(anyString(), anyMap());
      } else if (runtime != null) {
        verify(runtime.indexingCoordinator(), never()).updateDocument(anyString(), anyMap());
      }
      if ("recovery-malformed".equals(name)) {
        verify(runtime.documentFieldOps(), never())
            .queryDocIdsByFieldOrThrow(anyString(), anyString(), anyInt());
      }
    }
  }

  @Test
  @DisplayName("deferred replay regenerates VDU chunks after a parent replacement")
  void deferredReplayRegeneratesAgainstReplacementParent() throws Exception {
    Path dbPath = tempDir.resolve("replacement.db");
    Path indexPath = tempDir.resolve("replacement-index");
    try (RunningRuntime runtime = openRuntime(indexPath);
        SqliteJobQueue queue = openQueue(dbPath)) {
      seedParent(runtime, "enumerated baseline");
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      put(
          queue,
          "vdu_update:" + DOC_ID,
          "VDU_UPDATE",
          vduPayload(DOC_ID, VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT, VDU_CONTENT));

      drain(queue, runtime, () -> false);
      assertEquals(1, queue.switchBufferDepth());

      runtime.indexingCoordinator().deleteByIdAndChunks(DOC_ID);
      seedParent(runtime, "replacement from resumed source enumeration");
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals(
          "replacement from resumed source enumeration",
          runtime.documentFieldOps().getDocumentField(DOC_ID, SchemaFields.CONTENT));

      drain(queue, runtime, () -> true);
      runtime.commitOps().maybeRefreshBlocking();

      assertEquals(0, queue.switchBufferDepth());
      assertEquals(
          VDU_CONTENT,
          runtime.documentFieldOps().getDocumentField(DOC_ID, SchemaFields.CONTENT));
      List<LuceneRuntimeTypes.SearchHit> chunks = findChunks(runtime, DOC_ID);
      assertTrue(chunks.size() > 1);
      String expectedRevision = ChunkParentRevision.sha256Hex(VDU_CONTENT);
      assertTrue(
          chunks.stream()
              .allMatch(
                  hit ->
                      expectedRevision.equals(
                          hit.fields().get(SchemaFields.CHUNK_PARENT_CONTENT_SHA256))));
    }
  }

  private static Stream<Arguments> retainedLegacyRows() {
    return Stream.of(
        Arguments.of(
            "processing-missing-parent",
            "VDU_MARK_PROCESSING",
            "{\"doc_id\":\"missing-parent\",\"retry_count\":1}",
            true,
            false,
            true,
            false),
        Arguments.of(
            "failed-missing-parent",
            "VDU_MARK_FAILED",
            "{\"doc_id\":\"missing-parent\"}",
            true,
            false,
            true,
            false),
        Arguments.of(
            "processing-missing-retry-count",
            "VDU_MARK_PROCESSING",
            "{\"doc_id\":\"parent\"}",
            true,
            true,
            false,
            false),
        Arguments.of(
            "processing-invalid-retry-count",
            "VDU_MARK_PROCESSING",
            "{\"doc_id\":\"parent\",\"retry_count\":0}",
            true,
            true,
            false,
            false),
        Arguments.of(
            "processing-non-integral-retry-count",
            "VDU_MARK_PROCESSING",
            "{\"doc_id\":\"parent\",\"retry_count\":1.5}",
            true,
            true,
            false,
            false),
        Arguments.of("processing-blank", "VDU_MARK_PROCESSING", "", true, true, false, false),
        Arguments.of(
            "processing-blank-doc-id",
            "VDU_MARK_PROCESSING",
            "{\"doc_id\":\"\",\"retry_count\":1}",
            true,
            true,
            false,
            false),
        Arguments.of("failed-malformed", "VDU_MARK_FAILED", "{", true, true, false, false),
        Arguments.of(
            "recovery-malformed", "VDU_RECOVER_PROCESSING", "not-json", true, true, false, true),
        Arguments.of(
            "recovery-runtime-absent", "VDU_RECOVER_PROCESSING", "{}", false, true, false, true),
        Arguments.of("unknown-kind", "UNKNOWN_KIND", "payload", true, true, false, false));
  }

  private RunningRuntime mockRuntime(boolean updateSucceeds) {
    RunningRuntime runtime = mock(RunningRuntime.class);
    IndexingCoordinator indexing = mock(IndexingCoordinator.class);
    CommitOps commits = mock(CommitOps.class);
    DocumentFieldOps fields = mock(DocumentFieldOps.class);
    when(runtime.indexingCoordinator()).thenReturn(indexing);
    when(runtime.commitOps()).thenReturn(commits);
    when(runtime.documentFieldOps()).thenReturn(fields);
    when(indexing.updateDocument(anyString(), anyMap())).thenReturn(updateSucceeds);
    return runtime;
  }

  private RunningRuntime openRuntime(Path indexPath) {
    return IndexSchema.fromCatalog(replayCatalog())
        .atPath(indexPath)
        .withExecutorRegistrations(testLuceneExecutors())
        .open();
  }

  private void seedParent(RunningRuntime runtime, String content) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put(SchemaFields.DOC_ID, DOC_ID);
    fields.put(SchemaFields.DOC_UID, PARENT_UID);
    fields.put(SchemaFields.PATH, DOC_ID);
    fields.put(SchemaFields.CONTENT, content);
    fields.put(SchemaFields.CONTENT_SHA256, ChunkParentRevision.sha256Hex(content));
    fields.put(SchemaFields.CONTENT_PREVIEW, content);
    fields.put(SchemaFields.MIME, "text/plain");
    fields.put(SchemaFields.MIME_BASE, "text/plain");
    fields.put(SchemaFields.FILE_KIND, "text");
    fields.put(SchemaFields.LANGUAGE, "en");
    fields.put(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_PROCESSING);
    fields.put(SchemaFields.VDU_PROCESSED, "false");
    fields.put(SchemaFields.VDU_ENRICHMENT, "baseline");
    fields.put(SchemaFields.VDU_PAGE_COUNT, "1");
    fields.put(SchemaFields.EXTRACTION_METHOD, "TIKA_STRUCTURED");
    fields.put(
        SchemaFields.EXTRACTION_REASON_CODE,
        io.justsearch.indexerworker.ingest.IngestionReasonCodes.EXTRACTION_DROPOUT_PENDING_FALLBACK);
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
  }

  private List<LuceneRuntimeTypes.SearchHit> findChunks(RunningRuntime runtime, String parentDocId) {
    BooleanQuery.Builder query = new BooleanQuery.Builder();
    query.add(new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")), BooleanClause.Occur.FILTER);
    query.add(
        new TermQuery(new Term(SchemaFields.PARENT_DOC_ID, parentDocId)),
        BooleanClause.Occur.FILTER);
    Query built = query.build();
    return new ArrayList<>(
        runtime
            .readPathOps()
            .search(
                built,
                10_000,
                java.util.Set.of(
                    SchemaFields.CHUNK_PARENT_CONTENT_SHA256, SchemaFields.PARENT_DOC_ID),
                LuceneRuntimeTypes.RuntimeSearchSort.RELEVANCE,
                null)
            .hits());
  }

  private void drain(
      SqliteJobQueue queue, RunningRuntime runtime, java.util.function.BooleanSupplier eligible) {
    drain(queue, runtime, eligible, false);
  }

  private void drain(
      SqliteJobQueue queue,
      RunningRuntime runtime,
      java.util.function.BooleanSupplier eligible,
      boolean nullIndexPaths) {
    KnowledgeServerMigrationOps.drainSwitchBufferBestEffort(
        new KnowledgeServerMigrationOps.DrainSwitchBufferContext(
            queue,
            runtime,
            null,
            IndexingPacing.unthrottled(),
            nullIndexPaths ? null : tempDir.resolve("index-base"),
            nullIndexPaths ? null : tempDir.resolve("active-index"),
            new ObjectMapper(),
            () -> false,
            eligible,
            LoggerFactory.getLogger(VduGenerationReplayTest.class)));
  }

  private void put(SqliteJobQueue queue, String key, String kind, String payload) {
    assertTrue(queue.putSwitchBuffer(key, kind, payload));
  }

  private SwitchBufferCapableQueue.SwitchBufferOp find(SqliteJobQueue queue, String key) {
    return queue.listSwitchBufferOps().stream()
        .filter(op -> key.equals(op.key()))
        .findFirst()
        .orElseThrow();
  }

  private SqliteJobQueue openQueue(Path dbPath) throws Exception {
    SqliteJobQueue queue = new SqliteJobQueue(dbPath);
    queue.open();
    return queue;
  }

  private String vduPayload(String docId, VduUpdateOutcome outcome, String content)
      throws Exception {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("doc_id", docId);
    payload.put("extracted_content", content);
    payload.put("has_extracted_content", content != null);
    payload.put("vdu_status", "");
    payload.put("vdu_enrichment", "");
    payload.put("page_count", 0);
    payload.put("outcome", outcome.getNumber());
    return new ObjectMapper().writeValueAsString(payload);
  }

  private static FieldCatalogDef replayCatalog() {
    List<FieldCatalogDef.FieldDef> fields =
        new ArrayList<>(FieldCatalogDef.forChunkTesting(0).fields());
    fields.add(
        new FieldCatalogDef.FieldDef(
            SchemaFields.EXTRACTION_METHOD,
            "keyword",
            true,
            true,
            List.of("filter"),
            null,
            null,
            false));
    fields.add(
        new FieldCatalogDef.FieldDef(
            SchemaFields.EXTRACTION_REASON_CODE,
            "keyword",
            true,
            true,
            List.of("filter", "facet"),
            null,
            null,
            false));
    return new FieldCatalogDef("vdu-generation-replay", fields);
  }
}
