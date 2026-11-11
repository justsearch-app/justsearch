/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.extract.*;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.ingest.*;
import io.justsearch.indexerworker.loop.ops.BatchStats;
import io.justsearch.indexerworker.loop.ops.IndexingDocumentOps;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.path.PathResolutionStore;
import io.justsearch.indexerworker.queue.*;
import io.justsearch.indexerworker.rag.ChunkDocumentWriter;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import io.justsearch.ipc.SyncDirectoryRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Persisted legacy fixture support; independent installed old-binary evidence is still required. */
final class CaseDistinctPageEdgesQueuedRestartTest extends LuceneExecutorTestBase {
  private static final List<String> VARIANTS = List.of("Code", "CODE", "code");
  private static final String COLLECTION = "case-edges";
  private static final List<String> SNAPSHOT_FIELDS = List.of(
      SchemaFields.CONTENT_SHA256, SchemaFields.CONTENT_PREVIEW, SchemaFields.SOURCE_SHA256,
      SchemaFields.EXTRACTION_POLICY_ID, SchemaFields.EXTRACTION_PARSER_ID,
      SchemaFields.EXTRACTION_STATUS, SchemaFields.PATH, SchemaFields.COLLECTION,
      SchemaFields.DOC_UID, SchemaFields.CHUNK_TOTAL, SchemaFields.CHUNK_INDEX,
      SchemaFields.CHUNK_START_CHAR, SchemaFields.CHUNK_END_CHAR,
      SchemaFields.CHUNK_PARENT_CONTENT_SHA256);
  @TempDir Path temporary;

  @ParameterizedTest(name = "successful historical page-edge repair, configured={0}")
  @ValueSource(booleans = {false, true})
  @Timeout(120)
  void maintenanceRepairsSuccessfulOldPolicyAndReusesCompleteFamilyAfterReopen(boolean configured)
      throws Exception {
    Path root = Files.createDirectory(temporary.resolve("sources"));
    Path file = writePdf(root.resolve("case-edges.pdf"));
    String id = PathNormalizer.normalizeKey(file);
    String pathHash = DocumentIdentityStore.pathHash(id);
    Path db = temporary.resolve("queue.db");
    long size = Files.size(file);
    var mtime = Files.getLastModifiedTime(file);
    String sourceSha = SourceContentHash.sha256(file);
    var policy = configured ? TikaExtractionPolicy.fromWorkerLimits(new ResolvedConfig.Worker(100_000, 1_000_000))
        : TikaExtractionPolicy.defaults();
    String oldPolicy = configured ? "tika-config-v8-100000-1000000" : "tika-default-v8";
    String currentPolicy = configured ? "tika-config-v10-100000-1000000" : "tika-default-v10";
    DocumentIdentityStore.Identity originalIdentity;
    List<JobQueue.IngestionEventView> historical;
    Map<String, Map<String, String>> original;
    try (var runtime = open(); var queue = new SqliteJobQueue(db)) {
      queue.open();
      try (var identities = new SqliteDocumentIdentityStore(db)) {
        originalIdentity = identities.resolve(pathHash, 1234L);
      }
      // This deliberately models v8's lost edge text, not a relabelled current extraction.
      var legacyPolicy = new TikaExtractionPolicy(oldPolicy, policy.maxExtractedChars(), policy.maxInputBytes(),
          policy.maxOfficeInputBytes(), policy.maxMetadataEntries(), policy.maxMetadataKeyChars(),
          policy.maxMetadataValueChars(), policy.maxEmbeddedResources(), policy.maxEmbeddedDepth(),
          policy.maxCompressionRatio(), true, policy.allowedMimeTypes(), policy.excludedMimeTypes());
      var legacy = ExtractionArtifact.full(new ContentExtractor.ExtractionResult(expected(false), null, "application/pdf"),
          legacyPolicy, "tika-policy-structured", false).validate(legacyPolicy, null);
      runtime.indexingCoordinator().indexSingle(IndexingDocumentOps.buildDocument(file, legacy, COLLECTION,
          null, null, false, null, null, (stage, ms, reason) -> {},
          org.slf4j.LoggerFactory.getLogger(getClass()), null,
          new IndexingDocumentOps.SourceFileMetadata(size, mtime.toMillis(), sourceSha), originalIdentity.docUid()));
      refresh(runtime);
      assertTrue(ChunkDocumentWriter.regenerateChunksFromExistingParent(runtime.documentFieldOps(),
          runtime.indexingCoordinator(), id, expected(false), false) > 1);
      refresh(runtime);
      assertTrue(runtime.documentFieldOps().isUnmodified(id, mtime.toMillis(), size, sourceSha, oldPolicy));
      original = snapshot(runtime, id);
      assertEquals(1, queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(root)), COLLECTION));
      queue.markDone(file);
      queue.recordIngestionEvent(file, IngestionOutcome.of(IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS", IngestionRetryPolicy.NONE),
          new JobQueue.IngestionLedgerEntry(pathHash, COLLECTION, size, mtime.toMillis(), "FILE", "SUCCESS_FULL",
              oldPolicy, "tika-policy-structured"));
      historical = queue.recentIngestionEvents(100);
      assertEquals(1, historical.size());
      assertEquals(oldPolicy, historical.getFirst().policyId());
      assertDone(db);
    }

    Map<String, Map<String, String>> repaired;
    try (var runtime = open(); var queue = new SqliteJobQueue(db);
        var owners = new TestEngineExecutors()) {
      queue.open();
      try (var identities = new SqliteDocumentIdentityStore(db);
          var sandbox = spy(new PersistentExtractionSandbox(
              PipelineTestFixture.registration(owners, "case-pdf-readers", 1),
              ExtractionSandboxCommand.defaultCommand(policy, null), policy,
              OcrRoutingConfig.disabled(), Duration.ofSeconds(30), 1, 128, null));
          var timebox = new TimeboxedContentExtractor(PipelineTestFixture.registration(owners,
              "case-pdf-timebox", 1), sandbox, Duration.ofSeconds(30), null)) {
        assertEquals(original, snapshot(runtime, id));
        assertEquals(historical, queue.recentIngestionEvents(100));
        assertEquals(1, reconcile(runtime, queue, root, policy),
            "successful v8 family must be admitted for genuine current-policy extraction");
        // A fresh request followed by the queued repair exercises a reused real parser child.
        var fresh = timebox.extractArtifact(file);
        assertEquals(currentPolicy, fresh.policyId());
        assertEquals(expected(true), fresh.result().content());
        assertFalse(fresh.result().content().contains("Repeated Header"), "a flat fallback is not proof");
        assertEquals("tika-policy-structured", fresh.parserId());
        assertEquals(ExtractionStatus.SUCCESS_FULL, fresh.status());
        process(runtime, queue, timebox, identities, true);
        assertEquals(expected(true), runtime.documentFieldOps().getDocumentContent(id));
        assertCorrectFamily(runtime, id, sourceSha, currentPolicy, originalIdentity.docUid());
        assertNotEquals(original.get(id).get(SchemaFields.CONTENT_SHA256),
            runtime.documentFieldOps().getDocumentField(id, SchemaFields.CONTENT_SHA256));
        assertTrue(queue.recentIngestionEvents(100).containsAll(historical));
        assertTrue(queue.recentIngestionEvents(100).stream().anyMatch(event -> currentPolicy.equals(event.policyId())
            && "SUCCESS_FULL".equals(event.artifactStatus()) && "tika-policy-structured".equals(event.parserId())));
        assertEquals(0, reconcile(runtime, queue, root, policy));
        assertEquals(1, queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(root)), COLLECTION));
        process(runtime, queue, timebox, identities, false);
        verify(sandbox, times(2)).extract(any(Path.class));
        repaired = snapshot(runtime, id);
        var identity = identities.lookup(pathHash).orElseThrow();
        assertEquals(originalIdentity.docUid(), identity.docUid());
        assertEquals(originalIdentity.firstSeenAtMs(), identity.firstSeenAtMs());
        assertNull(identity.deletedAtMs());
        assertDone(db);
      }
    }
    try (var runtime = open(); var queue = new SqliteJobQueue(db);
        var owners = new TestEngineExecutors()) {
      queue.open();
      try (var identities = new SqliteDocumentIdentityStore(db);
          var sandbox = spy(new PersistentExtractionSandbox(
              PipelineTestFixture.registration(owners, "case-pdf-reopen-readers", 1),
              ExtractionSandboxCommand.defaultCommand(policy, null), policy,
              OcrRoutingConfig.disabled(), Duration.ofSeconds(30), 1, 128, null));
          var timebox = new TimeboxedContentExtractor(PipelineTestFixture.registration(owners,
              "case-pdf-reopen-timebox", 1), sandbox, Duration.ofSeconds(30), null)) {
        assertEquals(repaired, snapshot(runtime, id));
        assertCorrectFamily(runtime, id, sourceSha, currentPolicy, originalIdentity.docUid());
        assertEquals(0, reconcile(runtime, queue, root, policy));
        assertEquals(1, queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(root)), COLLECTION));
        process(runtime, queue, timebox, identities, false);
        verify(sandbox, never()).extract(any(Path.class));
        assertEquals(repaired, snapshot(runtime, id));
        assertEquals(originalIdentity.firstSeenAtMs(), identities.lookup(pathHash).orElseThrow().firstSeenAtMs());
        assertTrue(queue.recentIngestionEvents(100).containsAll(historical));
        assertDone(db);
      }
    }
    assertEquals(size, Files.size(file));
    assertEquals(mtime, Files.getLastModifiedTime(file));
    assertEquals(sourceSha, SourceContentHash.sha256(file));
  }

  private static int reconcile(RunningRuntime runtime, SqliteJobQueue queue, Path root, TikaExtractionPolicy policy) {
    var service = new WorkerIngestService(queue, mock(IndexingLoop.class), null, IndexingPacing.unthrottled(),
        null, null, runtime, runtime, null, 0L);
    service.setExtractionPolicy(policy);
    service.useCreatorRootCollection(ignored -> COLLECTION);
    var result = service.syncDirectory(SyncDirectoryRequest.newBuilder().setRootPath(root.toString()).build(), CallContext.none());
    assertEquals("", result.getError());
    return result.getFilesAdded();
  }

  private static void process(RunningRuntime runtime, SqliteJobQueue queue, TimeboxedContentExtractor timebox,
      DocumentIdentityStore identity, boolean expectedExtraction) throws Exception {
    var journal = new IngestionOutcomeJournal(queue, mock(OperationalMetrics.class),
        mock(io.justsearch.telemetry.catalog.CounterMetric.class), () -> false);
    var authority = new WorkerIngestionAuthority();
    var stats = new BatchStats();
    var stale = mock(StaleSnapshotResolver.class);
    var fence = new FileMutationFence();
    var extractor = new JobBatchExtractor(authority, journal, queue, timebox, runtime.documentFieldOps(),
        runtime.indexCountOps(), stats, stale, mock(StaleSourceHandler.class), IndexingPacing.unthrottled(),
        new AtomicBoolean(true), new HashSet<>(), () -> mock(PathResolutionStore.class), () -> identity,
        (stage, ms, reason) -> {}, () -> false, delta -> {}, fence);
    var writer = new JobBatchWriter(authority, runtime.indexingCoordinator(), null,
        mock(EmbeddingProviderLifecycle.class), () -> null, journal, queue, timebox,
        mock(OperationalMetrics.class), stats, stale, delta -> {}, (stage, ms, reason) -> {},
        () -> false, () -> false, () -> null, fence);
    var claims = queue.pollPending(1);
    assertEquals(1, claims.size());
    assertFalse(claims.getFirst().forceReindex());
    var jobs = extractor.extractAll(claims);
    assertEquals(1, jobs.size());
    assertEquals(expectedExtraction, jobs.getFirst().artifact() != null);
    writer.write(jobs.getFirst(), null);
    refresh(runtime);
    journal.drainPending();
    assertEquals(0, queue.queueDepth());
  }

  private static void assertCorrectFamily(RunningRuntime runtime, String id, String sourceSha, String policy, String uid) {
    var fields = runtime.documentFieldOps();
    String content = fields.getDocumentContent(id);
    assertEquals(expected(true), content);
    assertEquals(content.substring(0, 4096), fields.getDocumentField(id, SchemaFields.CONTENT_PREVIEW));
    assertEquals(sourceSha, fields.getDocumentField(id, SchemaFields.SOURCE_SHA256));
    assertEquals(policy, fields.getDocumentField(id, SchemaFields.EXTRACTION_POLICY_ID));
    assertEquals("tika-policy-structured", fields.getDocumentField(id, SchemaFields.EXTRACTION_PARSER_ID));
    assertEquals("SUCCESS_FULL", fields.getDocumentField(id, SchemaFields.EXTRACTION_STATUS));
    assertEquals(uid, fields.getDocumentField(id, SchemaFields.DOC_UID));
    assertEquals(id, fields.getDocumentField(id, SchemaFields.PATH));
    assertEquals(COLLECTION, fields.getDocumentField(id, SchemaFields.COLLECTION));
    String revision = ChunkParentRevision.sha256Hex(content);
    assertEquals(revision, fields.getDocumentField(id, SchemaFields.CONTENT_SHA256));
    assertEquals(revision, fields.getDocumentField(id, SchemaFields.CHUNK_PARENT_CONTENT_SHA256));
    var children = fields.queryDocIdsByField(SchemaFields.PARENT_DOC_ID, id, 1000);
    assertTrue(children.size() > 1);
    assertEquals(Integer.toString(children.size()), fields.getDocumentField(id, SchemaFields.CHUNK_TOTAL));
    for (String child : children) {
      assertEquals(revision, fields.getDocumentField(child, SchemaFields.CHUNK_PARENT_CONTENT_SHA256));
      int start = Integer.parseInt(fields.getDocumentField(child, SchemaFields.CHUNK_START_CHAR));
      int end = Integer.parseInt(fields.getDocumentField(child, SchemaFields.CHUNK_END_CHAR));
      assertEquals(content.substring(start, end), fields.getDocumentContent(child));
    }
  }

  private static Map<String, Map<String, String>> snapshot(RunningRuntime runtime, String id) {
    var ids = new ArrayList<>(runtime.documentFieldOps().queryDocIdsByField(SchemaFields.PARENT_DOC_ID, id, 1000));
    ids.add(id);
    var result = new LinkedHashMap<String, Map<String, String>>();
    for (String doc : ids) {
      var fields = new LinkedHashMap<String, String>();
      for (String field : SNAPSHOT_FIELDS) fields.put(field, runtime.documentFieldOps().getDocumentField(doc, field));
      fields.put("reconstructed_content", runtime.documentFieldOps().getDocumentContent(doc));
      result.put(doc, fields);
    }
    return result;
  }

  private static void assertDone(Path db) throws Exception {
    try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
        var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT state FROM jobs")) {
      assertTrue(rows.next());
      assertEquals("DONE", rows.getString(1));
      assertFalse(rows.next());
    }
  }

  private RunningRuntime open() {
    return IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog())
        .atPath(temporary.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open();
  }

  private static void refresh(RunningRuntime runtime) {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  private static String body(int page) {
    return "Body page " + page + " records the unchanged physical source and preserves its readable text. ".repeat(100);
  }

  private static String expected(boolean corrected) {
    var paragraphs = new ArrayList<String>();
    for (int page = 0; page < 6; page++) {
      if (page > 0) paragraphs.add("---");
      if (corrected && page < 3) paragraphs.add("Header " + VARIANTS.get(page));
      paragraphs.add(body(page).strip());
      if (corrected && page < 3) paragraphs.add("Footer " + VARIANTS.get(page));
    }
    return String.join("\n\n", paragraphs);
  }

  private static Path writePdf(Path pdf) throws Exception {
    try (var document = new PDDocument()) {
      for (int page = 0; page < 6; page++) {
        var item = new PDPage(new PDRectangle(5000, 3000));
        document.addPage(item);
        try (var content = new PDPageContentStream(document, item)) {
          content.beginText();
          content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 1);
          content.newLineAtOffset(40, 2900);
          content.showText(page < 3 ? "Header " + VARIANTS.get(page) : "Repeated Header");
          content.newLineAtOffset(0, -1000);
          content.showText(body(page));
          content.newLineAtOffset(0, -1000);
          content.showText(page < 3 ? "Footer " + VARIANTS.get(page) : "Repeated Footer");
          content.endText();
        }
      }
      document.save(pdf.toFile());
    }
    return pdf;
  }
}
