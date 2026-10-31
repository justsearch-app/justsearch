/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.extract.ContentExtractor;
import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import io.justsearch.indexerworker.extract.ExtractionArtifact;
import io.justsearch.indexerworker.extract.TikaExtractionPolicy;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import io.justsearch.indexerworker.loop.ops.BatchStats;
import io.justsearch.indexerworker.loop.ops.IndexingDocumentOps;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.path.PathResolutionStore;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.rag.ChunkDocumentWriter;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AsciiFirstUtf8QueuedRestartTest extends LuceneExecutorTestBase {
  private static final String HEBREW =
      "זה טקסט בעברית. היום אנחנו קוראים ספרים חדשים בספרייה העירונית.";
  private static final String TITLE = "ספרים בעברית";
  private static final String SOURCE = "---\n# " + "a".repeat(16_384)
      + "\ntitle: " + TITLE + "\nauthor: מרים\n---\n" + HEBREW.repeat(100);
  private static final List<String> STORED_FIELDS = List.of(
      SchemaFields.CONTENT, SchemaFields.CONTENT_SHA256, SchemaFields.SOURCE_SHA256,
      SchemaFields.EXTRACTION_STATUS, SchemaFields.CONTENT_PREVIEW, SchemaFields.TITLE,
      SchemaFields.MIME, SchemaFields.LANGUAGE, SchemaFields.PARENT_TOKEN_COUNT,
      SchemaFields.EXTRACTION_QUALITY_SCORE, SchemaFields.EXTRACTION_POLICY_ID, SchemaFields.CHUNK_TOTAL,
      SchemaFields.CHUNK_PARENT_CONTENT_SHA256, SchemaFields.CHUNK_INDEX, SchemaFields.CHUNK_START_CHAR,
      SchemaFields.CHUNK_END_CHAR, SchemaFields.DOC_UID, SchemaFields.META_AUTHOR);
  @TempDir Path tempDir;

  @Test
  void oldStoredFieldsAndChunksSurviveRestartWithUnchangedQueuedWork() throws Exception {
    restartScenario(false, true);
  }

  @Test
  void forcedReindexRepairsHebrewAndSurvivesAnotherRestart() throws Exception {
    restartScenario(true, true);
  }

  @Test
  void newlyIndexedAsciiFirstUtf8SurvivesRestart() throws Exception {
    restartScenario(false, false);
  }

  private RunningRuntime open() {
    return IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog())
        .atPath(tempDir.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open();
  }

  private void restartScenario(boolean force, boolean old) throws Exception {
    Path file = Files.writeString(tempDir.resolve("hebrew.md"), SOURCE);
    String id = PathNormalizer.normalizeKey(file);
    Path db = tempDir.resolve("queue.db");
    String legacy = new String(SOURCE.getBytes(StandardCharsets.UTF_8), Charset.forName("windows-1252"));
    Map<String, Map<String, String>> original;
    List<JobQueue.IngestionEventView> historical;
    String sourceSha = ChunkParentRevision.sha256Hex(SOURCE);
    try (var runtime = open(); var queue = new SqliteJobQueue(db); var parser = new ContentExtractor()) {
      queue.open();
      // A prior extraction with the actual mojibake, not a mismatched content/revision fixture.
      var extraction = old ? new ExtractionResult(legacy,
          new String(TITLE.getBytes(StandardCharsets.UTF_8), Charset.forName("windows-1252")),
          "text/plain; charset=windows-1252") : parser.extract(file);
      var fields = new HashMap<>(buildDocument(file, extraction).fields());
      fields.put(SchemaFields.DOC_UID, "stable-uid");
      fields.put(SchemaFields.EXTRACTION_POLICY_ID, old ? "tika-default-v5" : "tika-default-v8");
      fields.put(SchemaFields.CONTENT_SHA256, ChunkParentRevision.sha256Hex(extraction.content()));
      runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
      refresh(runtime);
      assertTrue(ChunkDocumentWriter.regenerateChunksFromExistingParent(runtime.documentFieldOps(),
          runtime.indexingCoordinator(), id, extraction.content(), false) > 0, "fixture must contain real chunks");
      refresh(runtime);
      assertEquals(ChunkParentRevision.sha256Hex(extraction.content()),
          runtime.documentFieldOps().getDocumentField(id, SchemaFields.CHUNK_PARENT_CONTENT_SHA256));
      assertTrue(runtime.documentFieldOps().isUnmodified(id, Files.getLastModifiedTime(file).toMillis()));
      original = snapshot(runtime, id);
      assertEquals(old ? "tika-default-v5" : "tika-default-v8",
          original.get(id).get(SchemaFields.EXTRACTION_POLICY_ID));
      if (!old) assertCorrect(runtime, id, sourceSha);
      queue.recordIngestionEvent(file, IngestionOutcome.of(IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS", IngestionRetryPolicy.NONE),
          new JobQueue.IngestionLedgerEntry("fixture-path-hash", null, Files.size(file),
              Files.getLastModifiedTime(file).toMillis(), "FILE", "SUCCESS_FULL",
              old ? "tika-default-v5" : "tika-default-v8", "restart-fixture"));
      historical = queue.recentIngestionEvents(100);
      assertEquals(1, historical.size());
      assertEquals(1, queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(tempDir))));
    }

    Map<String, Map<String, String>> repaired = null;
    try (var runtime = open(); var queue = new SqliteJobQueue(db);
        var owners = new TestEngineExecutors(); var parser = new ContentExtractor();
        var timebox = new TimeboxedContentExtractor(PipelineTestFixture.registration(owners,
            "utf8-restart", 1), parser, Duration.ofSeconds(20), null)) {
      queue.open();
      assertEquals(original, snapshot(runtime, id), "opening stores must preserve every stored field and chunk");
      assertEquals(historical, queue.recentIngestionEvents(100));
      var identity = mock(DocumentIdentityStore.class);
      when(identity.resolve(anyString(), anyLong()))
          .thenReturn(new DocumentIdentityStore.Identity("hash", "stable-uid", 1, 1));
      var journal = new IngestionOutcomeJournal(queue, mock(OperationalMetrics.class),
          mock(io.justsearch.telemetry.catalog.CounterMetric.class), () -> false);
      var authority = new WorkerIngestionAuthority();
      var stats = new BatchStats();
      var stale = mock(StaleSnapshotResolver.class);
      var fence = new FileMutationFence();
      var forced = new HashSet<String>();
      var extractor = new JobBatchExtractor(authority, journal, queue, timebox,
          runtime.documentFieldOps(), runtime.indexCountOps(), stats, stale,
          mock(StaleSourceHandler.class), IndexingPacing.unthrottled(), new AtomicBoolean(true), forced,
          () -> mock(PathResolutionStore.class), () -> identity, (stage, ms, reason) -> {}, () -> false, delta -> {}, fence);
      var writer = new JobBatchWriter(authority, runtime.indexingCoordinator(), null,
          mock(EmbeddingProviderLifecycle.class), () -> null, journal, queue, timebox,
          mock(OperationalMetrics.class), stats, stale, delta -> {}, (stage, ms, reason) -> {},
          () -> false, () -> false, () -> null, fence);
      var claims = queue.pollPending(1);
      assertEquals(1, claims.size(), "work must survive the restart");
      assertTrue(extractor.extractAll(claims).isEmpty(), "queued unchanged source must not be re-extracted");
      journal.drainPending();
      assertEquals(0, queue.queueDepth());
      assertEquals(original, snapshot(runtime, id), "unchanged queued work must preserve all fields and chunks");
      if (force) {
        forced.add(id);
        assertEquals(1, queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(tempDir))));
        var jobs = extractor.extractAll(queue.pollPending(1));
        assertEquals(1, jobs.size());
        writer.write(jobs.getFirst(), null);
        refresh(runtime);
        journal.drainPending();
        assertEquals(0, queue.queueDepth());
        assertEquals(SOURCE, runtime.documentFieldOps().getDocumentContent(id).strip());
        assertEquals(TITLE, runtime.documentFieldOps().getDocumentField(id, SchemaFields.TITLE));
        assertEquals("מרים", runtime.documentFieldOps().getDocumentField(id, SchemaFields.META_AUTHOR));
        assertEquals("tika-default-v8", runtime.documentFieldOps().getDocumentField(id, SchemaFields.EXTRACTION_POLICY_ID));
        assertCorrect(runtime, id, sourceSha);
        assertNotEquals(original.get(id).get(SchemaFields.CONTENT_SHA256),
            runtime.documentFieldOps().getDocumentField(id, SchemaFields.CONTENT_SHA256));
        assertNotEquals(original.get(id).get(SchemaFields.CHUNK_PARENT_CONTENT_SHA256),
            runtime.documentFieldOps().getDocumentField(id, SchemaFields.CHUNK_PARENT_CONTENT_SHA256));
        assertTrue(queue.recentIngestionEvents(100).containsAll(historical), "historical ledger rows remain");
        assertTrue(queue.recentIngestionEvents(100).stream().anyMatch(event ->
            "tika-default-v8".equals(event.policyId()) && "SUCCESS_FULL".equals(event.artifactStatus())));
        String content = runtime.documentFieldOps().getDocumentContent(id);
        String revision = ChunkParentRevision.sha256Hex(content);
        assertEquals(revision, runtime.documentFieldOps().getDocumentField(id, SchemaFields.CHUNK_PARENT_CONTENT_SHA256));
        List<String> children = runtime.documentFieldOps().queryDocIdsByField(SchemaFields.PARENT_DOC_ID, id, 10000);
        assertFalse(children.isEmpty(), "forced reindex must regenerate Hebrew chunks");
        for (String child : children) {
          assertEquals(revision, runtime.documentFieldOps().getDocumentField(child, SchemaFields.CHUNK_PARENT_CONTENT_SHA256));
        }
        repaired = snapshot(runtime, id);
      }
    }
    try (var runtime = open(); var queue = new SqliteJobQueue(db)) {
      queue.open();
      assertTrue(queue.recentIngestionEvents(100).containsAll(historical));
      assertEquals(force ? repaired : original, snapshot(runtime, id), "stored state must survive another restart");
    }
  }

  private static void assertCorrect(RunningRuntime runtime, String id, String sourceSha) {
    assertEquals(SOURCE, runtime.documentFieldOps().getDocumentContent(id).strip());
    assertEquals(TITLE, runtime.documentFieldOps().getDocumentField(id, SchemaFields.TITLE));
    assertEquals("מרים", runtime.documentFieldOps().getDocumentField(id, SchemaFields.META_AUTHOR));
    assertEquals(HEBREW.repeat(100).substring(0, 4096),
        runtime.documentFieldOps().getDocumentField(id, SchemaFields.CONTENT_PREVIEW));
    assertEquals("SUCCESS_FULL", runtime.documentFieldOps().getDocumentField(id, SchemaFields.EXTRACTION_STATUS));
    assertEquals("tika-default-v8", runtime.documentFieldOps().getDocumentField(id, SchemaFields.EXTRACTION_POLICY_ID));
    assertEquals(sourceSha, runtime.documentFieldOps().getDocumentField(id, SchemaFields.SOURCE_SHA256));
  }

  private static Map<String, Map<String, String>> snapshot(RunningRuntime runtime, String id) {
    var ids = new ArrayList<>(runtime.documentFieldOps().queryDocIdsByField(SchemaFields.PARENT_DOC_ID, id, 10000));
    ids.add(id);
    var result = new LinkedHashMap<String, Map<String, String>>();
    for (String document : ids) {
      var fields = new LinkedHashMap<String, String>();
      for (String field : STORED_FIELDS) fields.put(field, runtime.documentFieldOps().getDocumentField(document, field));
      fields.put("reconstructed_content", runtime.documentFieldOps().getDocumentContent(document));
      result.put(document, fields);
    }
    return result;
  }

  private static IndexDocument buildDocument(Path file, ExtractionResult extraction) throws Exception {
    var policy = TikaExtractionPolicy.defaults();
    var artifact = ExtractionArtifact.full(extraction, policy, "restart-fixture", false).validate(policy, null);
    return IndexingDocumentOps.buildDocument(file, artifact, null, null, null, false, null, null,
        (stage, ms, reason) -> {}, org.slf4j.LoggerFactory.getLogger(AsciiFirstUtf8QueuedRestartTest.class),
        null, new IndexingDocumentOps.SourceFileMetadata(Files.size(file),
            Files.getLastModifiedTime(file).toMillis(), ChunkParentRevision.sha256Hex(SOURCE)), "stable-uid");
  }

  private static void refresh(RunningRuntime runtime) {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }
}
