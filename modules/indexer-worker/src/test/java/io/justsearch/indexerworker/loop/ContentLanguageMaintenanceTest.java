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
import io.justsearch.indexerworker.server.ops.KnowledgeServerMigrationOps;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.SyncDirectoryRequest;
import io.justsearch.ipc.VduUpdateOutcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/** Exercises the maintenance and replay entry points against private persisted Lucene/SQLite stores. */
@Timeout(120)
final class ContentLanguageMaintenanceTest extends LuceneExecutorTestBase {
  private static final String GERMAN = "Die öffentliche Bibliothek öffnet jeden Morgen und begrüßt Leser aus den umliegenden Dörfern. Die Sammlung enthält Bücher über Geschichte, Wissenschaft und die natürliche Welt. ";
  private static final String CONTENT = GERMAN.repeat(100);
  @TempDir Path temporary;

  @ParameterizedTest(name = "language repair converges after interruption, configured={0}")
  @ValueSource(booleans = {false, true})
  void maintenanceRepairsV9OnceAndPreservesFailureOwnership(boolean configured) throws Exception {
    Path root = Files.createDirectory(temporary.resolve("sources"));
    Path completed = Files.writeString(root.resolve("completed.txt"), CONTENT);
    Path terminal = Files.writeString(root.resolve("terminal.txt"), CONTENT);
    Path retry = Files.writeString(root.resolve("retry.txt"), CONTENT);
    Path db = temporary.resolve("jobs.db");
    var policy = configured ? TikaExtractionPolicy.fromWorkerLimits(new ResolvedConfig.Worker(100_000, 1_000_000))
        : TikaExtractionPolicy.defaults();
    String oldPolicy = configured ? "tika-config-v9-100000-1000000" : "tika-default-v9";
    Map<String, String> terminalRow;
    Map<String, String> retryRow;
    try (var runtime = open(); var queue = new SqliteJobQueue(db)) {
      queue.open();
      for (Path file : List.of(completed, terminal, retry)) {
        seed(runtime, file, oldPolicy, true);
        assertEquals(1, queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(root))));
      }
      queue.markDone(completed);
      queue.markFailed(terminal, IngestionOutcome.of(IngestionOutcomeClass.IO_FAILED, "fixture.terminal", IngestionRetryPolicy.NONE));
      queue.markFailed(retry, IngestionOutcome.of(IngestionOutcomeClass.IO_FAILED, "fixture.retry", IngestionRetryPolicy.RETRY_WITH_BACKOFF));
      terminalRow = queue.watcherReconciliationRow(PathNormalizer.normalizeKey(terminal));
      retryRow = queue.watcherReconciliationRow(PathNormalizer.normalizeKey(retry));
      assertEquals(1, reconcile(runtime, queue, root, policy));
      // Interrupt after extraction, before publication/ledger completion; restart must recover it.
      try (var owners = new TestEngineExecutors();
          var sandbox = sandbox(owners, policy);
          var timebox = timebox(owners, sandbox)) {
        process(runtime, queue, timebox, true, true);
      }
      assertEquals("en-US", runtime.documentFieldOps().getDocumentField(PathNormalizer.normalizeKey(completed), SchemaFields.LANGUAGE));
    }
    try (var runtime = open(); var queue = new SqliteJobQueue(db); var owners = new TestEngineExecutors();
        var sandbox = spy(sandbox(owners, policy)); var timebox = timebox(owners, sandbox)) {
      queue.open();
      assertEquals(1, queue.recoverStuckJobs());
      process(runtime, queue, timebox, true, false);
      assertFamily(runtime, completed, policy.policyId());
      verify(sandbox, times(1)).extract(completed);
      assertEquals(0, reconcile(runtime, queue, root, policy));
      assertEquals(terminalRow, queue.watcherReconciliationRow(PathNormalizer.normalizeKey(terminal)));
      assertEquals(retryRow, queue.watcherReconciliationRow(PathNormalizer.normalizeKey(retry)));
    }
    try (var runtime = open(); var queue = new SqliteJobQueue(db); var owners = new TestEngineExecutors();
        var sandbox = spy(sandbox(owners, policy)); var timebox = timebox(owners, sandbox)) {
      queue.open();
      assertFamily(runtime, completed, policy.policyId());
      assertEquals(0, reconcile(runtime, queue, root, policy));
      // Even a redundant ordinary queued event may not re-read the repaired source.
      assertEquals(1, queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(completed).withinRoot(root))));
      process(runtime, queue, timebox, false, false);
      verify(sandbox, never()).extract(any(Path.class));
      assertEquals(terminalRow, queue.watcherReconciliationRow(PathNormalizer.normalizeKey(terminal)));
      assertEquals(retryRow, queue.watcherReconciliationRow(PathNormalizer.normalizeKey(retry)));
      assertEquals("en-US", runtime.documentFieldOps().getDocumentField(PathNormalizer.normalizeKey(terminal), SchemaFields.LANGUAGE));
      assertEquals("en-US", runtime.documentFieldOps().getDocumentField(PathNormalizer.normalizeKey(retry), SchemaFields.LANGUAGE));
    }
  }

  @Test
  void durableLegacyVduReplayRetiresResultAndFreshExtractionLabelsTheSource() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("sources"));
    Path file = Files.writeString(root.resolve("legacy.txt"), CONTENT);
    String id = PathNormalizer.normalizeKey(file);
    Path db = temporary.resolve("jobs.db");
    var policy = TikaExtractionPolicy.defaults();
    try (var runtime = open(); var queue = new SqliteJobQueue(db)) {
      queue.open();
      seed(runtime, file, "tika-default-v9", false);
      assertTrue(queue.putSwitchBuffer("vdu_update:" + id, "VDU_UPDATE", new ObjectMapper().writeValueAsString(Map.of(
          "doc_id", id, "extracted_content", "English model text that must never replace the source.",
          "has_extracted_content", true, "outcome", VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT.getNumber()))));
    }
    try (var runtime = open(); var queue = new SqliteJobQueue(db); var owners = new TestEngineExecutors();
        var sandbox = sandbox(owners, policy); var timebox = timebox(owners, sandbox)) {
      queue.open();
      var publisher = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(), null, null, runtime, runtime, null, 0L);
      publisher.useExclusionRoots(() -> List.of(root));
      var context = new KnowledgeServerMigrationOps.DrainSwitchBufferContext(queue, runtime, null,
          IndexingPacing.unthrottled(), null, null, new ObjectMapper(), () -> false, () -> true,
          LoggerFactory.getLogger(getClass())).withPublisher(publisher);
      KnowledgeServerMigrationOps.drainSwitchBufferBestEffort(context);
      assertEquals(CONTENT, runtime.documentFieldOps().getDocumentContent(id));
      process(runtime, queue, timebox, true, false);
      assertFamily(runtime, file, policy.policyId());
    }
    try (var runtime = open()) {
      assertFamily(runtime, file, policy.policyId());
    }
  }

  private RunningRuntime open() {
    var runtime = IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog())
        .atPath(temporary.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open();
    runtime.commitOps().stopCommitTimer();
    return runtime;
  }

  private static PersistentExtractionSandbox sandbox(TestEngineExecutors owners, TikaExtractionPolicy policy) {
    return new PersistentExtractionSandbox(PipelineTestFixture.registration(owners, "language-parser", 1),
        ExtractionSandboxCommand.defaultCommand(policy, null), policy, OcrRoutingConfig.disabled(), Duration.ofSeconds(30), 1, 128, null);
  }

  private static TimeboxedContentExtractor timebox(TestEngineExecutors owners, ExtractionSandbox sandbox) {
    return new TimeboxedContentExtractor(PipelineTestFixture.registration(owners, "language-timebox", 1), sandbox, Duration.ofSeconds(30), null);
  }

  private static void seed(RunningRuntime runtime, Path file, String policyId, boolean sourceHash) throws Exception {
    var policy = TikaExtractionPolicy.defaults();
    var artifact = ExtractionArtifact.full(new ContentExtractor.ExtractionResult(CONTENT, null, "text/plain"), policy, "fixture", false).validate(policy, null);
    String id = PathNormalizer.normalizeKey(file);
    var document = IndexingDocumentOps.buildDocument(file, artifact, null, null, null, false, null, null,
        (stage, ms, reason) -> {}, LoggerFactory.getLogger(ContentLanguageMaintenanceTest.class), null,
        new IndexingDocumentOps.SourceFileMetadata(Files.size(file), Files.getLastModifiedTime(file).toMillis(),
            sourceHash ? SourceContentHash.sha256(file) : null), "stable-" + file.getFileName());
    var fields = new HashMap<>(document.fields());
    fields.put(SchemaFields.EXTRACTION_POLICY_ID, policyId);
    fields.put(SchemaFields.LANGUAGE, "en-US");
    if (!sourceHash) fields.remove(SchemaFields.SOURCE_SHA256);
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
    refresh(runtime);
    assertTrue(ChunkDocumentWriter.regenerateChunksFromExistingParent(runtime.documentFieldOps(), runtime.indexingCoordinator(), id, CONTENT, false) > 1);
    refresh(runtime);
    for (String child : runtime.documentFieldOps().queryDocIdsByField(SchemaFields.PARENT_DOC_ID, id, 1000)) {
      runtime.indexingCoordinator().updateDocument(child, Map.of(SchemaFields.LANGUAGE, "en-US"));
    }
    refresh(runtime);
  }

  private static int reconcile(RunningRuntime runtime, SqliteJobQueue queue, Path root, TikaExtractionPolicy policy) {
    var service = new WorkerIngestService(queue, mock(IndexingLoop.class), null, IndexingPacing.unthrottled(), null, null, runtime, runtime, null, 0L);
    service.setExtractionPolicy(policy);
    var result = service.syncDirectory(SyncDirectoryRequest.newBuilder().setRootPath(root.toString()).build(), CallContext.none());
    assertEquals("", result.getError());
    return result.getFilesAdded();
  }

  private static void process(RunningRuntime runtime, SqliteJobQueue queue, TimeboxedContentExtractor timebox,
      boolean expectedExtraction, boolean interrupt) throws Exception {
    var journal = new IngestionOutcomeJournal(queue, mock(OperationalMetrics.class), mock(io.justsearch.telemetry.catalog.CounterMetric.class), () -> false);
    var authority = new WorkerIngestionAuthority();
    var stats = new BatchStats();
    var stale = mock(StaleSnapshotResolver.class);
    var fence = new FileMutationFence();
    var identity = mock(DocumentIdentityStore.class);
    when(identity.resolve(anyString(), anyLong())).thenReturn(new DocumentIdentityStore.Identity("hash", "stable-uid", 1, 1));
    var extractor = new JobBatchExtractor(authority, journal, queue, timebox, runtime.documentFieldOps(), runtime.indexCountOps(), stats, stale,
        mock(StaleSourceHandler.class), IndexingPacing.unthrottled(), new AtomicBoolean(true), new HashSet<>(),
        () -> mock(PathResolutionStore.class), () -> identity, (stage, ms, reason) -> {}, () -> false, delta -> {}, fence);
    var writer = new JobBatchWriter(authority, runtime.indexingCoordinator(), null, mock(EmbeddingProviderLifecycle.class), () -> null,
        journal, queue, timebox, mock(OperationalMetrics.class), stats, stale, delta -> {}, (stage, ms, reason) -> {},
        () -> false, () -> false, () -> null, fence);
    var claims = queue.pollPending(1);
    assertEquals(1, claims.size());
    assertFalse(claims.getFirst().forceReindex());
    var jobs = extractor.extractAll(claims);
    assertEquals(1, jobs.size());
    assertEquals(expectedExtraction, jobs.getFirst().artifact() != null);
    if (interrupt) return;
    writer.write(jobs.getFirst(), null);
    refresh(runtime);
    journal.drainPending();
  }

  private static void assertFamily(RunningRuntime runtime, Path file, String policy) {
    String id = PathNormalizer.normalizeKey(file);
    assertEquals("de", runtime.documentFieldOps().getDocumentField(id, SchemaFields.LANGUAGE));
    assertEquals(policy, runtime.documentFieldOps().getDocumentField(id, SchemaFields.EXTRACTION_POLICY_ID));
    assertEquals(CONTENT.strip(), runtime.documentFieldOps().getDocumentContent(id).strip());
    var children = runtime.documentFieldOps().queryDocIdsByField(SchemaFields.PARENT_DOC_ID, id, 1000);
    assertTrue(children.size() > 1);
    for (String child : children) assertEquals("de", runtime.documentFieldOps().getDocumentField(child, SchemaFields.LANGUAGE));
  }

  private static void refresh(RunningRuntime runtime) {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }
}
