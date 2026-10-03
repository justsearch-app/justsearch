/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.server.ops.KnowledgeServerMigrationOps;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import io.justsearch.ipc.UpdateVduResultRequest;
import io.justsearch.ipc.UpdateVduResultResponse;
import io.justsearch.ipc.VduUpdateOutcome;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

@DisplayName("VDU result live/replay parity")
final class VduResultReplayParityTest extends LuceneExecutorTestBase {

  private static final String DOC_ID = "vdu-parity-parent";
  private static final String PARENT_UID = "vdu-parity-uid";
  private static final String BASELINE_CONTENT = "baseline Tika content";
  private static final String VDU_CONTENT =
      "VDU extracted content with enough words for several chunks. ".repeat(500);
  private static final String ENRICHMENT = "{\"source\":\"vdu-parity\"}";
  private static final FieldCatalogDef CATALOG = parityCatalog();

  @TempDir Path tempDir;

  @ParameterizedTest(name = "{0}")
  @MethodSource("outcomeCases")
  @DisplayName("live and replay apply the same committed parent and chunk state")
  void liveAndReplayProduceIdenticalCommittedState(OutcomeCase scenario) throws Exception {
    Path liveIndex = tempDir.resolve("live-" + scenario.name());
    Path replayIndex = tempDir.resolve("replay-" + scenario.name());
    Path queueDb = tempDir.resolve("queue-" + scenario.name() + ".db");

    try (RunningRuntime live = openRuntime(liveIndex);
        RunningRuntime replay = openRuntime(replayIndex)) {
      seed(live);
      seed(replay);
      UpdateVduResultRequest request = request(scenario);

      UpdateVduResultResponse liveResult = service(live).updateVduResult(request, CallContext.none());
      assertTrue(liveResult.getSuccess(), liveResult.getError());

      try (SqliteJobQueue queue = openQueue(queueDb)) {
        assertTrue(
            queue.putSwitchBuffer(
                LegacyVduBufferFixture.switchBufferVduUpdateKey(DOC_ID),
                "VDU_UPDATE",
                LegacyVduBufferFixture.updateVduSwitchBufferPayload(request, DOC_ID)));
      }
      try (SqliteJobQueue queue = openQueue(queueDb)) {
        drain(queue, replay);
        assertEquals(0, queue.switchBufferDepth());
      }

      ParentSnapshot liveSnapshot = readCommitted(liveIndex);
      ParentSnapshot replaySnapshot = readCommitted(replayIndex);
      assertEquals(liveSnapshot, replaySnapshot);
      assertExpected(scenario, replaySnapshot);
    }
  }

  @ParameterizedTest(name = "invalid outcome {0}")
  @MethodSource("invalidOutcomeValues")
  @DisplayName("invalid results are rejected live and retained for replay")
  void invalidResultIsRejectedLiveAndRetainedByReplay(int outcomeValue) throws Exception {
    Path liveIndex = tempDir.resolve("invalid-live");
    Path replayIndex = tempDir.resolve("invalid-replay");
    Path queueDb = tempDir.resolve("invalid-queue.db");
    UpdateVduResultRequest request =
        UpdateVduResultRequest.newBuilder()
            .setDocId(DOC_ID)
            .setOutcomeValue(outcomeValue)
            .setExtractedContent(" \t\n")
            .setVduEnrichment(ENRICHMENT)
            .setPageCount(9)
            .build();

    try (RunningRuntime live = openRuntime(liveIndex);
        RunningRuntime replay = openRuntime(replayIndex)) {
      seed(live);
      seed(replay);
      ParentSnapshot baseline = readCommitted(liveIndex);

      UpdateVduResultResponse liveResult = service(live).updateVduResult(request, CallContext.none());
      assertFalse(liveResult.getSuccess());
      assertEquals(baseline, readCommitted(liveIndex));

      try (SqliteJobQueue queue = openQueue(queueDb)) {
        assertTrue(
            queue.putSwitchBuffer(
                LegacyVduBufferFixture.switchBufferVduUpdateKey(DOC_ID),
                "VDU_UPDATE",
                invalidReplayPayload(request)));
      }
      try (SqliteJobQueue queue = openQueue(queueDb)) {
        drain(queue, replay);
        assertEquals(1, queue.switchBufferDepth());
        assertEquals(baseline, readCommitted(replayIndex));
      }
    }
  }

  private static String invalidReplayPayload(UpdateVduResultRequest request) throws Exception {
    // Unknown outcomes can only be persisted by a newer producer; current admission refuses them.
    String payload = LegacyVduBufferFixture.updateVduSwitchBufferPayload(
        request.toBuilder().setOutcomeValue(0).build(), DOC_ID);
    var mapper = new ObjectMapper();
    var node = (tools.jackson.databind.node.ObjectNode) mapper.readTree(payload);
    node.put("outcome", request.getOutcomeValue());
    return mapper.writeValueAsString(node);
  }

  private static Stream<Integer> invalidOutcomeValues() {
    return Stream.of(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT.getNumber(), 999);
  }

  private static Stream<Arguments> outcomeCases() {
    return Stream.of(
        Arguments.of(
            new OutcomeCase(
                "explicit-success-text",
                VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT,
                null,
                VDU_CONTENT,
                "COMPLETED",
                VDU_CONTENT,
                SchemaFields.EXTRACTION_METHOD_VDU,
                IngestionReasonCodes.EXTRACTION_DROPOUT_PENDING_FALLBACK)),
        Arguments.of(
            new OutcomeCase(
                "explicit-success-empty",
                VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_EMPTY,
                null,
                null,
                SchemaFields.VDU_STATUS_COMPLETED_EMPTY,
                BASELINE_CONTENT,
                SchemaFields.EXTRACTION_METHOD_NONE,
                IngestionReasonCodes.EXTRACTION_DROPOUT_UNRECOVERED)),
        Arguments.of(
            new OutcomeCase(
                "explicit-failed",
                VduUpdateOutcome.VDU_UPDATE_OUTCOME_FAILED,
                null,
                null,
                SchemaFields.VDU_STATUS_FAILED,
                BASELINE_CONTENT,
                SchemaFields.EXTRACTION_METHOD_NONE,
                IngestionReasonCodes.EXTRACTION_DROPOUT_UNRECOVERED)),
        Arguments.of(
            new OutcomeCase(
                "explicit-rejected",
                VduUpdateOutcome.VDU_UPDATE_OUTCOME_REJECTED_SUSPECT_TEXT,
                null,
                VDU_CONTENT,
                SchemaFields.VDU_STATUS_REJECTED,
                BASELINE_CONTENT,
                SchemaFields.EXTRACTION_METHOD_NONE,
                IngestionReasonCodes.EXTRACTION_DROPOUT_UNRECOVERED)),
        Arguments.of(
            new OutcomeCase(
                "legacy-completed",
                null,
                SchemaFields.VDU_STATUS_COMPLETED,
                VDU_CONTENT,
                "COMPLETED",
                VDU_CONTENT,
                SchemaFields.EXTRACTION_METHOD_VDU,
                IngestionReasonCodes.EXTRACTION_DROPOUT_PENDING_FALLBACK)),
        Arguments.of(
            new OutcomeCase(
                "legacy-completed-empty",
                null,
                SchemaFields.VDU_STATUS_COMPLETED_EMPTY,
                null,
                SchemaFields.VDU_STATUS_COMPLETED_EMPTY,
                BASELINE_CONTENT,
                SchemaFields.EXTRACTION_METHOD_NONE,
                IngestionReasonCodes.EXTRACTION_DROPOUT_UNRECOVERED)),
        Arguments.of(
            new OutcomeCase(
                "legacy-failed",
                null,
                SchemaFields.VDU_STATUS_FAILED,
                null,
                SchemaFields.VDU_STATUS_FAILED,
                BASELINE_CONTENT,
                SchemaFields.EXTRACTION_METHOD_NONE,
                IngestionReasonCodes.EXTRACTION_DROPOUT_UNRECOVERED)),
        Arguments.of(
            new OutcomeCase(
                "unspecified-with-content",
                null,
                null,
                VDU_CONTENT,
                SchemaFields.VDU_STATUS_PROCESSING,
                VDU_CONTENT,
                SchemaFields.EXTRACTION_METHOD_VDU,
                IngestionReasonCodes.EXTRACTION_DROPOUT_PENDING_FALLBACK)),
        Arguments.of(
            new OutcomeCase(
                "unspecified-no-legacy",
                null,
                null,
                null,
                SchemaFields.VDU_STATUS_PROCESSING,
                BASELINE_CONTENT,
                "TIKA_STRUCTURED",
                IngestionReasonCodes.EXTRACTION_DROPOUT_PENDING_FALLBACK)));
  }

  private RunningRuntime openRuntime(Path indexPath) {
    RunningRuntime runtime =
        IndexSchema.fromCatalog(CATALOG)
            .atPath(indexPath)
            .withExecutorRegistrations(testLuceneExecutors())
            .open();
    runtime.commitOps().stopCommitTimer();
    return runtime;
  }

  private WorkerIngestService service(RunningRuntime runtime) {
    return new WorkerIngestService(
        null,
        null,
        null,
        IndexingPacing.unthrottled(),
        null,
        null,
        runtime,
        runtime,
        null,
        0L);
  }

  private void seed(RunningRuntime runtime) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put(SchemaFields.DOC_ID, DOC_ID);
    fields.put(SchemaFields.DOC_UID, PARENT_UID);
    fields.put(SchemaFields.PATH, DOC_ID);
    fields.put(SchemaFields.CONTENT, BASELINE_CONTENT);
    fields.put(SchemaFields.CONTENT_SHA256, ChunkParentRevision.sha256Hex(BASELINE_CONTENT));
    fields.put(SchemaFields.CONTENT_PREVIEW, BASELINE_CONTENT);
    fields.put(SchemaFields.MIME, "text/plain");
    fields.put(SchemaFields.MIME_BASE, "text/plain");
    fields.put(SchemaFields.FILE_KIND, "text");
    fields.put(SchemaFields.LANGUAGE, "en");
    fields.put(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_PROCESSING);
    fields.put(SchemaFields.VDU_PROCESSED, "false");
    fields.put(SchemaFields.VDU_ENRICHMENT, "baseline-enrichment");
    fields.put(SchemaFields.VDU_PAGE_COUNT, "1");
    fields.put(SchemaFields.EXTRACTION_METHOD, "TIKA_STRUCTURED");
    fields.put(
        SchemaFields.EXTRACTION_REASON_CODE,
        IngestionReasonCodes.EXTRACTION_DROPOUT_PENDING_FALLBACK);
    fields.put(SchemaFields.INDEXED_AT, System.currentTimeMillis());
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  private UpdateVduResultRequest request(OutcomeCase scenario) {
    UpdateVduResultRequest.Builder builder =
        UpdateVduResultRequest.newBuilder()
            .setDocId(DOC_ID)
            .setVduEnrichment(ENRICHMENT)
            .setPageCount(9);
    if (scenario.outcome() != null) {
      builder.setOutcome(scenario.outcome());
    }
    if (scenario.legacyStatus() != null) {
      builder.setVduStatus(scenario.legacyStatus());
    }
    if (scenario.content() != null) {
      builder.setExtractedContent(scenario.content());
    }
    return builder.build();
  }

  private void drain(SqliteJobQueue queue, RunningRuntime runtime) {
    KnowledgeServerMigrationOps.drainSwitchBufferBestEffort(
        new KnowledgeServerMigrationOps.DrainSwitchBufferContext(
            queue,
            runtime,
            null,
            IndexingPacing.unthrottled(),
            tempDir.resolve("index-base"),
            tempDir.resolve("active-index"),
            new ObjectMapper(),
            () -> false, () -> true,
            LoggerFactory.getLogger(VduResultReplayParityTest.class)));
  }

  private SqliteJobQueue openQueue(Path dbPath) throws Exception {
    SqliteJobQueue queue = new SqliteJobQueue(dbPath);
    queue.open();
    return queue;
  }

  private ParentSnapshot readCommitted(Path indexPath) throws IOException {
    try (var directory = FSDirectory.open(indexPath);
        var reader = DirectoryReader.open(directory)) {
      IndexSearcher searcher = new IndexSearcher(reader);
      var parentHits = searcher.search(new TermQuery(new Term(SchemaFields.DOC_ID, DOC_ID)), 1);
      assertEquals(1, parentHits.scoreDocs.length);
      Document parent = reader.storedFields().document(parentHits.scoreDocs[0].doc);

      var chunkHits =
          searcher.search(new TermQuery(new Term(SchemaFields.PARENT_DOC_ID, DOC_ID)), 10_000);
      List<String> chunkRevisions = new ArrayList<>();
      for (var hit : chunkHits.scoreDocs) {
        chunkRevisions.add(
            reader.storedFields().document(hit.doc).get(SchemaFields.CHUNK_PARENT_CONTENT_SHA256));
      }
      chunkRevisions.sort(Comparator.nullsFirst(String::compareTo));
      return new ParentSnapshot(
          parent.get(SchemaFields.DOC_UID),
          parent.get(SchemaFields.CONTENT),
          parent.get(SchemaFields.CONTENT_SHA256),
          parent.get(SchemaFields.VDU_STATUS),
          parent.get(SchemaFields.VDU_PROCESSED),
          parent.get(SchemaFields.EXTRACTION_METHOD),
          parent.get(SchemaFields.EXTRACTION_REASON_CODE),
          parent.get(SchemaFields.VDU_ENRICHMENT),
          parent.get(SchemaFields.VDU_PAGE_COUNT),
          chunkRevisions.size(),
          chunkRevisions);
    }
  }

  private void assertExpected(OutcomeCase scenario, ParentSnapshot snapshot) {
    assertEquals(PARENT_UID, snapshot.docUid());
    assertEquals(scenario.expectedStatus(), snapshot.vduStatus());
    assertEquals("1", snapshot.vduProcessed()); // FieldMapper stores booleans as numeric 0/1.
    assertEquals(scenario.expectedContent(), snapshot.content());
    assertEquals(ChunkParentRevision.sha256Hex(scenario.expectedContent()), snapshot.contentSha256());
    assertEquals(scenario.expectedExtractionMethod(), snapshot.extractionMethod());
    assertEquals(scenario.expectedReasonCode(), snapshot.extractionReasonCode());
    assertEquals(ENRICHMENT, snapshot.vduEnrichment());
    assertEquals("9", snapshot.vduPageCount());
    if (scenario.expectedContent().equals(VDU_CONTENT)) {
      assertTrue(snapshot.chunkCount() > 1);
      assertTrue(
          snapshot.chunkRevisions().stream()
              .allMatch(ChunkParentRevision.sha256Hex(VDU_CONTENT)::equals));
    } else {
      assertEquals(0, snapshot.chunkCount());
      assertTrue(snapshot.chunkRevisions().isEmpty());
    }
  }

  private static FieldCatalogDef parityCatalog() {
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
    return new FieldCatalogDef("vdu-parity", fields);
  }

  private record OutcomeCase(
      String name,
      VduUpdateOutcome outcome,
      String legacyStatus,
      String content,
      String expectedStatus,
      String expectedContent,
      String expectedExtractionMethod,
      String expectedReasonCode) {
    @Override
    public String toString() {
      return name;
    }
  }

  private record ParentSnapshot(
      String docUid,
      String content,
      String contentSha256,
      String vduStatus,
      String vduProcessed,
      String extractionMethod,
      String extractionReasonCode,
      String vduEnrichment,
      String vduPageCount,
      int chunkCount,
      List<String> chunkRevisions) {}
}
