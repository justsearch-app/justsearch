/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

import io.justsearch.adapters.lucene.runtime.AdaptiveWeightSelector;
import io.justsearch.adapters.lucene.runtime.HybridFusionUtils;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.rag.ChunkDocumentWriter;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.services.input.SearchInputCapture;
import io.justsearch.indexerworker.text.TextQualityAnalyzer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import io.justsearch.ipc.FetchDocumentSliceRequest;
import io.justsearch.ipc.MarkVduProcessingRequest;
import io.justsearch.ipc.PipelineConfig;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.UpdateVduResultRequest;
import io.justsearch.ipc.VduUpdateOutcome;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VduResultWriterCoherenceTest extends LuceneExecutorTestBase {
  private static final String DOC = "parent";
  private static final String COUNT = SchemaFields.PARENT_TOKEN_COUNT;
  private static final String REASON = SchemaFields.EXTRACTION_REASON_CODE;
  private static final String PENDING = IngestionReasonCodes.EXTRACTION_DROPOUT_PENDING_FALLBACK;
  // Exact fixture counts for the ordinary chars/3 fallback, independent of the repaired writer.
  private static final String SHORT = "Readable short text. ".repeat(3);
  private static final String LONG = "Readable long text. ".repeat(600);
  private static final Map<String, Object> PRESERVED = Map.ofEntries(
      Map.entry(SchemaFields.DOC_UID, "stable-uid"),
      Map.entry(SchemaFields.PATH, "/docs/report.pdf"),
      Map.entry(SchemaFields.COLLECTION, "documents"),
      Map.entry(SchemaFields.MIME, "application/pdf"),
      Map.entry(SchemaFields.MIME_BASE, "application/pdf"),
      Map.entry(SchemaFields.FILE_KIND, "pdf"),
      Map.entry(SchemaFields.SOURCE_SHA256, "a".repeat(64)),
      Map.entry(SchemaFields.EXTRACTION_STATUS, "SUCCESS_PARTIAL"),
      Map.entry(SchemaFields.CONTENT_TRUNCATED, true),
      Map.entry(SchemaFields.VISUAL_EXTRACTION_EVIDENCE, "{\"route\":\"baseline-dropout\",\"pages\":7}"),
      Map.entry(SchemaFields.VDU_DEMAND_KIND, "baseline_text"),
      Map.entry(SchemaFields.VDU_RETRY_COUNT, 2L),
      Map.entry(SchemaFields.EXTRACTION_POLICY_ID, "original-policy"),
      Map.entry(SchemaFields.EXTRACTION_PARSER_ID, "original-parser"),
      Map.entry(SchemaFields.PARSER_WARNINGS_COUNT, 3L),
      Map.entry(SchemaFields.EMBEDDED_RESOURCE_COUNT, 4L),
      Map.entry(SchemaFields.META_SOURCE, "source-system"),
      Map.entry(SchemaFields.META_AUTHOR, "original-author"));

  private static final Set<String> ALL_FIELDS = new JustSearchConfigurationLoader().loadFieldCatalog()
      .fields().stream().map(field -> field.id()).collect(java.util.stream.Collectors.toSet());

  @TempDir Path directory;

  @Test
  void vc1ShortToLongRefreshesParentChunksAndRemovesPendingReasonAcrossReopen() throws Exception {
    try (var runtime = open()) {
      seed(runtime, SHORT, 1L, PENDING);
      assertEquals(List.of(DOC), runtime.documentFieldOps().queryDocIdsByField(REASON, PENDING, 10));
      var before = preserved(runtime);
      replace(runtime, LONG, "explicit");
      assertAll(
          () -> assertReplacement(runtime, LONG, 4000L),
          () -> assertEquals(before, preserved(runtime)),
          () -> assertReasonAbsent(runtime),
          () -> assertFalse(chunks(runtime).isEmpty()));
    }
    try (var runtime = open()) {
      assertReplacement(runtime, LONG, 4000L);
      assertReasonAbsent(runtime);
      assertFalse(chunks(runtime).isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"chunked", "below-threshold"})
  void vc2LongToShortRefreshesNumericQueriesAndSortAndRetiresEveryOldChunk(String size) throws Exception {
    String replacement = size.equals("chunked") ? "Smaller readable text. ".repeat(120) : SHORT;
    long expectedCount = size.equals("chunked") ? 920L : 21L;
    try (var runtime = open()) {
      seed(runtime, LONG, 4000L, PENDING);
      Set<String> oldIds = chunks(runtime).keySet();
      assertFalse(oldIds.isEmpty());
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "control", SchemaFields.DOC_UID, "control", COUNT, 2000L)));
      refresh(runtime);
      assertNumericQueryAndSort(4000L, 2000L, "control");
      replace(runtime, replacement, "explicit");
      assertAll(
          () -> assertReplacement(runtime, replacement, expectedCount),
          () -> assertTrue(java.util.Collections.disjoint(oldIds, chunks(runtime).keySet())),
          () -> assertEquals(size.equals("below-threshold"), chunks(runtime).isEmpty()),
          () -> assertNumericQueryAndSort(expectedCount, 4000L, DOC));
    }
    try (var runtime = open()) {
      assertReplacement(runtime, replacement, expectedCount);
      assertNumericQueryAndSort(expectedCount, 4000L, DOC);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"explicit", "legacy", "unspecified"})
  void vc3EveryReplacementFormRefreshesCountReasonAndCompletesProcessing(String form) throws Exception {
    try (var runtime = open()) {
      seed(runtime, SHORT, 1L, PENDING);
      replace(runtime, LONG, form);
      assertAll(
          () -> assertEquals("COMPLETED", field(runtime, SchemaFields.VDU_STATUS)),
          () -> assertNull(field(runtime, REASON)),
          () -> assertReplacement(runtime, LONG, 4000L));
    }
    try (var runtime = open()) {
      assertEquals("COMPLETED", field(runtime, SchemaFields.VDU_STATUS));
      assertReplacement(runtime, LONG, 4000L);
      assertReasonAbsent(runtime);
    }
  }

  @Test
  void vc4CachedCorpusAndFusionInputsFollowShortToLongAndLongToShortAfterRefreshAndReopen() throws Exception {
    try (var runtime = open()) {
      seed(runtime, SHORT, 21L, PENDING);
      // Keep capture eligible even while this parent has no chunks. This unrelated chunk is
      // excluded from the candidate query and from the corpus parent-token histogram.
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "anchor-chunk", SchemaFields.DOC_UID, "anchor#0",
          SchemaFields.IS_CHUNK, "true", SchemaFields.PARENT_DOC_ID, "anchor")));
      refresh(runtime);
      var capture = capture(runtime);
      var cached = runtime.indexCountOps().getOrComputeCorpusProfile();
      assertSame(cached, runtime.indexCountOps().getOrComputeCorpusProfile());
      assertRanking(runtime, capture, 21L, 128L, true);
      replace(runtime, LONG, "explicit");
      assertNotSame(cached, runtime.indexCountOps().getOrComputeCorpusProfile());
      assertRanking(runtime, capture, 4000L, 3072L, false);
    }
    try (var runtime = open()) {
      var capture = capture(runtime);
      assertRanking(runtime, capture, 4000L, 3072L, false);
      var cached = runtime.indexCountOps().getOrComputeCorpusProfile();
      replace(runtime, SHORT, "unspecified");
      assertNotSame(cached, runtime.indexCountOps().getOrComputeCorpusProfile());
      assertRanking(runtime, capture, 21L, 128L, true);
    }
    try (var runtime = open()) {
      assertRanking(runtime, capture(runtime), 21L, 128L, true);
    }
  }

  @Test
  void vc5RepeatedIdenticalAndLegacyReplacementsDescribeTheFinalText() throws Exception {
    try (var runtime = open()) {
      seed(runtime, SHORT, null, null);
      replace(runtime, LONG, "explicit");
      assertReplacement(runtime, LONG, 4000L);
      assertReasonAbsent(runtime);
      replace(runtime, SHORT, "legacy");
      assertReplacement(runtime, SHORT, 21L);
      assertTrue(chunks(runtime).isEmpty());
      runtime.indexingCoordinator().updateDocument(DOC, Map.of(COUNT, 9999L));
      refresh(runtime);
      replace(runtime, SHORT, "unspecified");
      assertReplacement(runtime, SHORT, 21L);
      assertReasonAbsent(runtime);
    }
    try (var runtime = open()) {
      assertReplacement(runtime, SHORT, 21L);
      assertReasonAbsent(runtime);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"empty", "failed", "rejected", "blank"})
  void vcn1NonReplacingResultsPreserveCountsChunksAndBaselineDescriptors(String outcome) throws Exception {
    Map<String, Map<String, String>> oldChunks;
    Map<String, String> descriptors;
    try (var runtime = open()) {
      seed(runtime, LONG, 123L, PENDING);
      oldChunks = chunks(runtime);
      descriptors = preserved(runtime);
      var request = UpdateVduResultRequest.newBuilder().setSourceSha256("a".repeat(64)).setDocId(DOC).setExtractedContent(SHORT);
      switch (outcome) {
        case "empty" -> request.setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_EMPTY);
        case "failed" -> request.setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_FAILED);
        case "rejected" -> request.setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_REJECTED_SUSPECT_TEXT);
        case "blank" -> request.setExtractedContent("  ");
        default -> throw new AssertionError(outcome);
      }
      assertTrue(VduResultWriter.apply(runtime, request.build(), false, Map.of(SchemaFields.CONTENT, LONG), SchemaFields.VDU_MAX_RETRIES));
      refresh(runtime);
      assertNonReplacement(runtime, oldChunks, descriptors, (outcome.equals("blank") || outcome.equals("failed")) ? PENDING
          : IngestionReasonCodes.EXTRACTION_DROPOUT_UNRECOVERED);
    }
    try (var runtime = open()) {
      assertNonReplacement(runtime, oldChunks, descriptors, (outcome.equals("blank") || outcome.equals("failed")) ? PENDING
          : IngestionReasonCodes.EXTRACTION_DROPOUT_UNRECOVERED);
    }
  }

  @Test
  void vcn1PublicWorkerRefusesStaleSourceWithoutChangingParentOrChunks() throws Exception {
    Map<String, String> parent;
    Map<String, Map<String, String>> oldChunks;
    try (var runtime = open()) {
      seed(runtime, LONG, 123L, PENDING);
      runtime.indexingCoordinator().updateDocument(DOC, Map.of(SchemaFields.VDU_STATUS, "PENDING"));
      refresh(runtime);
      var service = new WorkerIngestService(null, null, null, IndexingPacing.unthrottled(), null,
          null, runtime, runtime, null, 0L);
      var base = CallContext.none();
      var ctx = new CallContext(null, null, null, base.engineContext().withWorkId(UUID.randomUUID()),
          base.provenance(), base.childLifetime());
      String attempt = UUID.randomUUID().toString();
      var claim = service.markVduProcessing(MarkVduProcessingRequest.newBuilder().setDocId(DOC)
          .setMaxRetries(5).setAttemptId(attempt).build(), ctx);
      assertTrue(claim.getSuccess(), claim.getError());
      runtime.indexingCoordinator().updateDocument(DOC, Map.of(SchemaFields.SOURCE_SHA256, "b".repeat(64)));
      refresh(runtime);
      parent = parent(runtime);
      oldChunks = chunks(runtime);
      var refused = service.updateVduResult(UpdateVduResultRequest.newBuilder().setSourceSha256("a".repeat(64)).setDocId(DOC)
          .setAttemptId(attempt).setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT)
          .setExtractedContent(SHORT).build(), ctx);
      assertFalse(refused.getSuccess());
      assertTrue(refused.getError().contains("revision changed"), refused.getError());
      refresh(runtime);
      assertEquals(parent, parent(runtime));
      assertEquals(oldChunks, chunks(runtime));
    }
    try (var runtime = open()) {
      assertEquals(parent, parent(runtime));
      assertEquals(oldChunks, chunks(runtime));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"OTHER_REASON", "absent"})
  void vcn2ReplacementPreservesOtherReasonsAndBaselineRoutingAndUntouchedDocuments(String reason) throws Exception {
    Map<String, String> original;
    try (var runtime = open()) {
      seed(runtime, SHORT, 123L, reason.equals("absent") ? null : reason);
      original = preserved(runtime);
      var untouched = new HashMap<String, Object>(PRESERVED);
      untouched.put(SchemaFields.DOC_ID, "untouched");
      untouched.put(SchemaFields.DOC_UID, "untouched-uid");
      untouched.put(SchemaFields.CONTENT, SHORT);
      untouched.put(COUNT, 77L);
      runtime.indexingCoordinator().indexSingle(new IndexDocument(untouched));
      refresh(runtime);
      var before = runtime.documentFieldOps().getDocumentFieldsBatch(List.of("untouched"), ALL_FIELDS);
      replace(runtime, LONG, "explicit");
      assertEquals(original, preserved(runtime));
      assertEquals(reason.equals("absent") ? null : reason, field(runtime, REASON));
      assertEquals(before, runtime.documentFieldOps().getDocumentFieldsBatch(List.of("untouched"), ALL_FIELDS));
    }
    try (var runtime = open()) {
      assertEquals(original, preserved(runtime));
      assertEquals(reason.equals("absent") ? null : reason, field(runtime, REASON));
      assertEquals("77", runtime.documentFieldOps().getDocumentField("untouched", COUNT));
    }
  }

  @Test
  void vcn3IdenticalContentPreservesSpladeAndNerStateAndSuppliedUnspecifiedStatus() throws Exception {
    try (var runtime = open()) {
      seed(runtime, SHORT, 21L, null);
      var statuses = Map.<String, Object>of(SchemaFields.SPLADE_STATUS, "FAILED",
          SchemaFields.SPLADE_RETRY_COUNT, 3L, SchemaFields.NER_STATUS, "FAILED", SchemaFields.NER_RETRY_COUNT, 4L);
      runtime.indexingCoordinator().updateDocument(DOC, statuses);
      refresh(runtime);
      var request = UpdateVduResultRequest.newBuilder().setSourceSha256("a".repeat(64)).setDocId(DOC).setExtractedContent(SHORT)
          .setVduStatus("CUSTOM_STATUS").build();
      assertTrue(VduResultWriter.apply(runtime, request, false, Map.of(SchemaFields.CONTENT, SHORT), SchemaFields.VDU_MAX_RETRIES));
      refresh(runtime);
      assertEquals("CUSTOM_STATUS", field(runtime, SchemaFields.VDU_STATUS));
      for (var entry : statuses.entrySet()) assertEquals(entry.getValue().toString(), field(runtime, entry.getKey()));
      replace(runtime, LONG, "explicit");
      assertEquals("PENDING", field(runtime, SchemaFields.SPLADE_STATUS));
      assertEquals("PENDING", field(runtime, SchemaFields.NER_STATUS));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void bc2ReplacementPreservesTruncationAndOcrWhilePreviewReportsProcessed(boolean stored) throws Exception {
    try (var runtime = open()) {
      seed(runtime, SHORT, 21L, PENDING);
      runtime.indexingCoordinator().updateDocument(DOC, Map.of(
          SchemaFields.CONTENT_TRUNCATED, stored, "ocr_present", stored));
      refresh(runtime);
      replace(runtime, LONG, "explicit");
      assertBooleanPreview(runtime, stored);
      runtime.indexingCoordinator().updateDocument(DOC, Map.of(SchemaFields.TITLE, "unrelated"));
      refresh(runtime);
      assertBooleanPreview(runtime, stored);
    }
    try (var runtime = open()) {
      assertBooleanPreview(runtime, stored);
    }
  }

  private static void assertBooleanPreview(RunningRuntime runtime, boolean truncated) throws Exception {
    String expected = truncated ? "1" : "0";
    assertEquals(expected, field(runtime, SchemaFields.CONTENT_TRUNCATED));
    assertEquals(expected, field(runtime, "ocr_present"));
    assertEquals("1", field(runtime, SchemaFields.VDU_PROCESSED));
    var preview = new WorkerSearchService(runtime).fetchDocumentSlice(
        FetchDocumentSliceRequest.newBuilder().setDocId(DOC).setMaxChars(100).build(), CallContext.none());
    assertTrue(preview.getFound());
    assertEquals(expected, preview.getMetadataOrThrow(SchemaFields.CONTENT_TRUNCATED));
    assertEquals(truncated, preview.getContentTruncated());
    assertEquals("1", preview.getMetadataOrThrow(SchemaFields.VDU_PROCESSED));
  }

  @ParameterizedTest
  @ValueSource(strings = {"explicit", "legacy", "unspecified"})
  void vcn2PublicReplacementLeavesIngestionLedgerAndFingerprintUnchanged(String form) throws Exception {
    String fingerprint;
    try (var runtime = open()) {
      seed(runtime, SHORT, 21L, PENDING);
      fingerprint = runtime.latestCommitUserDataBestEffort().get("index_fingerprint");
      assertNotNull(fingerprint);
      var queue = mock(JobQueue.class);
      var service = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(), null,
          null, runtime, runtime, null, 0L);
      clearInvocations(queue);
      var base = CallContext.none();
      var ctx = new CallContext(null, null, null, base.engineContext().withWorkId(UUID.randomUUID()),
          base.provenance(), base.childLifetime());
      String attempt = UUID.randomUUID().toString();
      var claim = service.markVduProcessing(MarkVduProcessingRequest.newBuilder().setDocId(DOC)
          .setMaxRetries(5).setAttemptId(attempt).build(), ctx);
      assertTrue(claim.getSuccess(), claim.getError());
      var request = UpdateVduResultRequest.newBuilder().setSourceSha256("a".repeat(64)).setDocId(DOC).setAttemptId(attempt).setExtractedContent(LONG);
      if (form.equals("explicit")) request.setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT);
      if (form.equals("legacy")) request.setVduStatus("COMPLETED");
      var result = service.updateVduResult(request.build(), ctx);
      assertTrue(result.getSuccess(), result.getError());
      refresh(runtime);
      // JobQueue owns ingestion outcomes and journal transitions; VDU must not rewrite that history.
      verifyNoInteractions(queue);
      assertEquals(fingerprint, runtime.latestCommitUserDataBestEffort().get("index_fingerprint"));
      assertReplacement(runtime, LONG, 4000L);
      assertReasonAbsent(runtime);
    }
    try (var runtime = open()) {
      assertEquals(fingerprint, runtime.latestCommitUserDataBestEffort().get("index_fingerprint"));
      assertReplacement(runtime, LONG, 4000L);
    }
  }

  private RunningRuntime open() {
    var runtime = IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog())
        .atPath(directory).withExecutorRegistrations(testLuceneExecutors()).open();
    runtime.commitOps().stopCommitTimer();
    return runtime;
  }

  private static void refresh(RunningRuntime runtime) {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  private static void seed(RunningRuntime runtime, String content, Long count, String reason) {
    var fields = new HashMap<String, Object>(PRESERVED);
    fields.put(SchemaFields.DOC_ID, DOC);
    fields.put(SchemaFields.CONTENT, content);
    fields.put(SchemaFields.CONTENT_SHA256, ChunkParentRevision.sha256Hex(content));
    fields.put(SchemaFields.EXTRACTION_QUALITY_SCORE, 0.1);
    fields.put(SchemaFields.EXTRACTION_METHOD, "NONE");
    fields.put(SchemaFields.VDU_STATUS, "PROCESSING");
    if (count != null) fields.put(COUNT, count);
    if (reason != null) fields.put(REASON, reason);
    var plan = ChunkDocumentWriter.plan(content, "application/pdf", "pdf");
    ChunkDocumentWriter.declare(fields, plan);
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
    refresh(runtime);
    // Match ordinary indexing: publish the declaration with the parent, then its chunks.
    // A separate parent RMW here would hide a truncation-preservation failure during setup.
    ChunkDocumentWriter.replaceDeclaredChunks(runtime.indexingCoordinator(), DOC, content,
        new ChunkDocumentWriter.ParentChunkMetadata("application/pdf", "application/pdf", "pdf",
            "en", count, "documents", "stable-uid"), plan, false);
    refresh(runtime);
    assertEquals("1", field(runtime, SchemaFields.CONTENT_TRUNCATED));
  }

  private static void replace(RunningRuntime runtime, String content, String form) throws Exception {
    var request = UpdateVduResultRequest.newBuilder().setSourceSha256("a".repeat(64)).setDocId(DOC).setExtractedContent(content);
    if (form.equals("explicit")) request.setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT);
    if (form.equals("legacy")) request.setVduStatus("COMPLETED");
    assertTrue(VduResultWriter.apply(runtime, request.build(), false,
        Map.of(SchemaFields.CONTENT, runtime.documentFieldOps().getDocumentContent(DOC)), SchemaFields.VDU_MAX_RETRIES));
    refresh(runtime);
  }

  private static String field(RunningRuntime runtime, String name) {
    return runtime.documentFieldOps().getDocumentField(DOC, name);
  }

  private static Map<String, String> preserved(RunningRuntime runtime) {
    return runtime.documentFieldOps().getDocumentFieldsBatch(List.of(DOC), PRESERVED.keySet()).get(DOC);
  }

  private static Map<String, String> parent(RunningRuntime runtime) {
    return runtime.documentFieldOps().getDocumentFieldsBatch(List.of(DOC), ALL_FIELDS).get(DOC);
  }

  private static Map<String, Map<String, String>> chunks(RunningRuntime runtime) {
    var ids = runtime.documentFieldOps().queryDocIdsByField(SchemaFields.PARENT_DOC_ID, DOC, 1000);
    return runtime.documentFieldOps().getDocumentFieldsBatch(ids, ALL_FIELDS);
  }

  private static void assertReplacement(RunningRuntime runtime, String content, long expectedCount) {
    assertEquals(content, runtime.documentFieldOps().getDocumentContent(DOC));
    assertAll(
        () -> assertEquals(Long.toString(expectedCount), field(runtime, COUNT)),
        () -> {
          for (var chunk : chunks(runtime).entrySet()) {
            assertEquals(Long.toString(expectedCount), chunk.getValue().get(COUNT), chunk.getKey());
          }
        });
    assertEquals(Double.toString(TextQualityAnalyzer.computeQualityScore(content)), field(runtime, SchemaFields.EXTRACTION_QUALITY_SCORE));
    assertEquals(ChunkParentRevision.sha256Hex(content), field(runtime, SchemaFields.CONTENT_SHA256));
    String preview = LanguageUtils.contentPreview(content, ChunkDocumentWriter.CONTENT_PREVIEW_MAX_CHARS);
    assertEquals(preview, field(runtime, SchemaFields.CONTENT_PREVIEW));
    assertEquals(LanguageUtils.resolveLanguage(preview), field(runtime, SchemaFields.LANGUAGE));
    assertEquals("VDU", field(runtime, SchemaFields.EXTRACTION_METHOD));
    assertEquals("PENDING", field(runtime, SchemaFields.EMBEDDING_STATUS));
    for (var chunk : chunks(runtime).entrySet()) {
      assertEquals(ChunkParentRevision.sha256Hex(content), chunk.getValue().get(SchemaFields.CHUNK_PARENT_CONTENT_SHA256));
    }
  }

  private static void assertReasonAbsent(RunningRuntime runtime) {
    var facets = runtime.facetingEngine().computeFacets(new MatchAllDocsQuery(), Map.of(REASON, 10), 100);
    var preview = new WorkerSearchService(runtime).fetchDocumentSlice(
        FetchDocumentSliceRequest.newBuilder().setDocId(DOC).setMaxChars(100).build(), CallContext.none());
    assertAll(
        () -> assertNull(field(runtime, REASON)),
        () -> assertTrue(runtime.documentFieldOps().getDocumentFieldValues(DOC, REASON).isEmpty()),
        () -> assertTrue(runtime.documentFieldOps().queryDocIdsByField(REASON, PENDING, 100).isEmpty()),
        () -> assertFalse(facets.truncated()),
        () -> assertTrue(facets.facets().containsKey(REASON), "reason field must actually be facetable"),
        () -> assertTrue(facets.facets().get(REASON).isEmpty()),
        () -> assertTrue(preview.getFound()),
        () -> assertFalse(preview.getMetadataMap().containsKey(REASON)),
        () -> assertEquals("SUCCESS_PARTIAL", preview.getMetadataOrThrow(SchemaFields.EXTRACTION_STATUS)),
        () -> assertEquals("1", preview.getMetadataOrThrow(SchemaFields.CONTENT_TRUNCATED)));
  }

  private void assertNumericQueryAndSort(long currentCount, long otherCount, String first) throws Exception {
    try (var fs = FSDirectory.open(directory); var reader = DirectoryReader.open(fs)) {
      var searcher = new IndexSearcher(reader);
      var parents = new BooleanQuery.Builder().add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST)
          .add(new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")), BooleanClause.Occur.MUST_NOT).build();
      var sorted = searcher.search(parents, 10, new Sort(new SortField(COUNT, SortField.Type.LONG)));
      var exact = new BooleanQuery.Builder().add(new TermQuery(new Term(SchemaFields.DOC_ID, DOC)), BooleanClause.Occur.FILTER)
          .add(LongPoint.newExactQuery(COUNT, currentCount), BooleanClause.Occur.FILTER).build();
      var stale = new BooleanQuery.Builder().add(new TermQuery(new Term(SchemaFields.DOC_ID, DOC)), BooleanClause.Occur.FILTER)
          .add(LongPoint.newExactQuery(COUNT, otherCount), BooleanClause.Occur.FILTER).build();
      assertAll(
          () -> assertEquals(first, searcher.storedFields().document(sorted.scoreDocs[0].doc).get(SchemaFields.DOC_ID)),
          () -> assertEquals(1, searcher.count(exact)),
          () -> assertEquals(0, searcher.count(stale)));
    }
  }

  private static void assertNonReplacement(RunningRuntime runtime, Map<String, Map<String, String>> oldChunks,
      Map<String, String> descriptors, String reason) {
    assertEquals(LONG, runtime.documentFieldOps().getDocumentContent(DOC));
    assertEquals("123", field(runtime, COUNT));
    assertEquals("0.1", field(runtime, SchemaFields.EXTRACTION_QUALITY_SCORE));
    assertEquals(oldChunks, chunks(runtime));
    assertEquals(descriptors, preserved(runtime));
    assertEquals(reason, field(runtime, REASON));
  }

  private static SearchInputCapture capture(RunningRuntime runtime) {
    var config = mock(ResolvedConfig.class);
    var search = mock(ResolvedConfig.Search.class);
    when(config.search()).thenReturn(search);
    when(search.chunkAwareEnabled()).thenReturn(true);
    return new SearchInputCapture(runtime.textQueryOps(), runtime.indexCountOps(), runtime.commitOps(),
        runtime.documentFieldOps(), () -> config, () -> null, Map::of,
        () -> new SearchInputCapture.EncoderSnapshot(null, null, () -> null, null, null, null));
  }

  private static void assertRanking(RunningRuntime runtime, SearchInputCapture capture,
      long tokens, long median, boolean shortCorpus) {
    var captured = capture.capture(SearchRequest.newBuilder().setQuery("Readable")
        .setPipeline(PipelineConfig.getDefaultInstance()).build(), false, null).corpus();
    assertTrue(captured.hasChunkDocs());
    assertEquals(median, captured.medianTokenCount());
    assertEquals(shortCorpus, captured.isShortCorpus());
    var profile = runtime.indexCountOps().getOrComputeCorpusProfile();
    assertEquals(tokens, profile.totalTokenCount());
    assertEquals(1L, profile.docsWithTokenCount());
    var candidates = new BooleanQuery.Builder()
        .add(new TermQuery(new Term(SchemaFields.DOC_ID, DOC)), BooleanClause.Occur.SHOULD)
        .add(new TermQuery(new Term(SchemaFields.PARENT_DOC_ID, DOC)), BooleanClause.Occur.SHOULD).build();
    var result = runtime.readPathOps().search(candidates, 1000, null,
        LuceneRuntimeTypes.RuntimeSearchSort.RELEVANCE, null);
    assertFalse(result.hits().isEmpty());
    assertEquals(1 + chunks(runtime).size(), result.hits().size());
    for (var hit : result.hits()) {
      assertEquals(Long.toString(tokens), hit.fields().get(COUNT));
      assertEquals(HybridFusionUtils.spladeParentLengthMultiplier(tokens),
          HybridFusionUtils.spladeParentLengthMultiplier(hit.fields()));
    }
    assertArrayEquals(shortCorpus ? new double[] {0.34, 0.33, 0.33} : new double[] {0.60, 0.20, 0.20},
        AdaptiveWeightSelector.selectWeights(result, null, null, new double[] {1, 0, 0}), 1e-9);
  }
}
