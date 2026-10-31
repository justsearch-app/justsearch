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
import io.justsearch.indexerworker.extract.ExtractionArtifact;
import io.justsearch.indexerworker.extract.TikaExtractionPolicy;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The real persisted queue, admission, extractor, writer, parent/chunk search and reopen. */
final class RestoredMtimeQueuedRestartTest extends LuceneExecutorTestBase {
  @TempDir Path temporary;

  @Test
  void upgradedV25FailureBecomesRunnableThroughWatcherAfterSizeChange() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("sources"));
    Path file = Files.writeString(root.resolve("source.txt"), "broken");
    Path database = temporary.resolve("queue.db");
    try (var queue = new SqliteJobQueue(database)) {
      queue.open();
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(root)));
    }
    // V25 has exactly the current layout without the two V26 admission columns.
    try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
        var sql = connection.createStatement()) {
      sql.executeUpdate("UPDATE jobs SET state='FAILED',attempts=6");
      sql.executeUpdate("ALTER TABLE jobs DROP COLUMN force_reindex");
      sql.executeUpdate("ALTER TABLE jobs DROP COLUMN admitted_source_sha256");
      sql.executeUpdate("PRAGMA user_version=25");
    }
    Files.writeString(file, "repairedwatchertoken ".repeat(300));
    try (var queue = new SqliteJobQueue(database)) {
      queue.open();
      assertEquals(1, queue.jobStateCounts().failedCount(), "upgrade keeps the legacy job");
      var service = new io.justsearch.indexerworker.services.WorkerIngestService(queue,
          mock(IndexingLoop.class), null, IndexingPacing.unthrottled(), null, null, null, null, null, 0L);
      var registry = new io.justsearch.indexerworker.services.RootWatcherRegistry();
      service.setRootWatcherRegistry(registry);
      assertTrue(service.watchRoot(io.justsearch.ipc.WatchRootRequest.newBuilder()
          .setRootPath(root.toString()).setRootAdded(true).build(),
          io.justsearch.indexerworker.services.CallContext.none()).getWatching());
      var subscription = registry.getClass().getDeclaredMethod("subscription", Path.class);
      subscription.setAccessible(true);
      service.acceptWatcherUpsert((io.justsearch.indexerworker.services.RootWatcherRegistry.Subscription)
          subscription.invoke(registry, root), null, file);
      assertEquals(1, queue.jobStateCounts().pendingCount(), "changed legacy failure must be runnable");
      assertEquals(1, processAccepted(queue, false, true, new HashSet<>()));
    }
    try (var runtime = open()) {
      assertTrue(hits(runtime, SchemaFields.CONTENT, "repairedwatchertoken") > 0);
      assertTrue(hits(runtime, SchemaFields.CHUNK_CONTENT, "repairedwatchertoken") > 0);
    }
  }

  @Test
  void missingStoredSourceHashRepairsEqualMetadataEditOnce() throws Exception {
    Path file = seed("oldquokkatoken ".repeat(300), false, true);
    var stamp = Files.getLastModifiedTime(file);
    Files.writeString(file, "newbadgertoken ".repeat(300));
    Files.setLastModifiedTime(file, stamp);
    assertEquals(1, process(file), "missing source proof cannot certify unchanged bytes");
    try (var runtime = open()) {
      assertEquals(0, hits(runtime, SchemaFields.CONTENT, "oldquokkatoken"));
      assertTrue(hits(runtime, SchemaFields.CONTENT, "newbadgertoken") > 0);
      assertEquals(SourceContentHash.sha256(file), runtime.documentFieldOps()
          .getDocumentField(PathNormalizer.normalizeKey(file), SchemaFields.SOURCE_SHA256));
    }
    assertEquals(0, process(file), "persisted source proof skips the identical second admission");
  }

  @Test
  void shrinkAndSecondChangeAfterRestartReplaceParentAndChunkSearchResults() throws Exception {
    Path file = seed("obsoletequokka ".repeat(1500), false);
    FileTime stamp = Files.getLastModifiedTime(file);
    replaceAndProcess(file, stamp, "freshbadger ".repeat(300), "obsoletequokka", "freshbadger");
    replaceAndProcess(file, stamp, "latestwombat ".repeat(600), "freshbadger", "latestwombat");
  }

  @Test
  void missingStoredSizeIsReextractedOnce() throws Exception {
    Path file = seed("legacyotter ".repeat(300), true);
    try (var runtime = open()) {
      assertNull(runtime.documentFieldOps().getDocumentField(PathNormalizer.normalizeKey(file), SchemaFields.SIZE_BYTES));
    }
    assertEquals(1, process(file), "a missing stored size needs one extraction");
    assertEquals(0, process(file), "the refreshed size must make the next start skip extraction");
  }

  @Test
  void unchangedQueuedFileSkipsExtractionAcrossRestarts() throws Exception {
    Path file = seed("unchangedpanda ".repeat(300), false);
    assertEquals(0, process(file));
    assertEquals(0, process(file));
  }

  @Test
  void unchangedStampWithMissingChunksStillRepairsTheFile() throws Exception {
    Path file = seed("repairplatypus ".repeat(300), false);
    try (var runtime = open()) {
      runtime.indexingCoordinator().deleteChunksForParentDocId(PathNormalizer.normalizeKey(file));
      refresh(runtime);
    }
    assertEquals(1, process(file), "matching mtime and size cannot certify missing chunks");
    assertEquals(0, process(file));
  }

  @Test
  void equalLengthEditsWithRestoredTimestampsPublishAndPersistTwice() throws Exception {
    Path file = seed("oldquokkatoken ".repeat(300), false);
    FileTime stamp = Files.getLastModifiedTime(file);
    replaceAndProcess(file, stamp, "newbadgertoken ".repeat(300), "oldquokkatoken", "newbadgertoken");
    replaceAndProcess(file, stamp, "newwombattoken ".repeat(300), "newbadgertoken", "newwombattoken");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void forcedPendingAndProcessingAdmissionsSurviveRestart(boolean processing) throws Exception {
    Path file = seed("unchangedpanda ".repeat(300), false);
    try (var queue = new SqliteJobQueue(temporary.resolve("queue.db"))) {
      queue.open();
      submitForce(queue, file, new HashSet<>());
      if (processing) assertEquals(1, queue.pollPending(1).size());
    }
    try (var queue = new SqliteJobQueue(temporary.resolve("queue.db"))) {
      queue.open();
      queue.recoverStuckJobs();
      assertEquals(1, processAccepted(queue, false, true, new HashSet<>()),
          "accepted force must be carried by the recovered queue admission");
    }
    assertEquals(0, process(file), "successful force completion is no longer an obligation");
  }

  @Test
  void firstForcedExtractionFailureDoesNotConsumeForceOnRetry() throws Exception {
    Path file = seed("unchangedpanda ".repeat(300), false);
    Set<String> marks = new HashSet<>();
    try (var queue = new SqliteJobQueue(temporary.resolve("queue.db"))) {
      queue.open();
      submitForce(queue, file, marks);
      assertEquals(0, processAccepted(queue, true, true, marks));
      readyRetry();
      assertEquals(1, processAccepted(queue, false, true, marks),
          "retry must extract an unchanged file after the forced first attempt failed");
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void sourceHashFailureRetriesWithoutAcknowledgingUnchangedOrConsumingForce(boolean forced) throws Exception {
    Path file = seed("unchangedpanda ".repeat(300), false);
    Set<String> marks = new HashSet<>();
    try (var queue = new SqliteJobQueue(temporary.resolve("queue.db"))) {
      queue.open();
      if (forced) submitForce(queue, file, marks);
      else assertEquals(1, queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(temporary))));
      try (var hashes = mockStatic(SourceContentHash.class, CALLS_REAL_METHODS)) {
        hashes.when(() -> SourceContentHash.sha256(file)).thenThrow(new java.io.IOException("source read failed"));
        assertEquals(0, processAccepted(queue, false, true, marks));
      }
      readyRetry();
      assertEquals(forced ? 1 : 0, processAccepted(queue, false, true, marks));
    }
  }

  @Test
  void committedIndexBeforeQueueCompletionRetainsForceOnRecovery() throws Exception {
    Path file = seed("unchangedpanda ".repeat(300), false);
    Set<String> marks = new HashSet<>();
    try (var queue = new SqliteJobQueue(temporary.resolve("queue.db"))) {
      queue.open();
      submitForce(queue, file, marks);
      assertEquals(1, processAccepted(queue, false, false, marks));
    }
    try (var queue = new SqliteJobQueue(temporary.resolve("queue.db"))) {
      queue.open();
      queue.recoverStuckJobs();
      assertEquals(1, processAccepted(queue, false, true, new HashSet<>()));
    }
  }

  @Test
  void routineAdmissionCannotWeakenPendingForceAndOldCompletionCannotConsumeNewForce() throws Exception {
    Path file = seed("unchangedpanda ".repeat(300), false);
    try (var queue = new SqliteJobQueue(temporary.resolve("queue.db"))) {
      queue.open();
      submitForce(queue, file, new HashSet<>());
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(temporary)));
      var old = queue.pollPending(1).getFirst();
      submitForce(queue, file, new HashSet<>());
      assertFalse(queue.markClaimDone(old,
          io.justsearch.indexerworker.ingest.IngestionOutcome.of(
              io.justsearch.indexerworker.ingest.IngestionOutcomeClass.SKIPPED_POLICY,
              "UNCHANGED", io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE, "old"), null));
      assertEquals(1, processAccepted(queue, false, true, new HashSet<>()));
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"false,false", "true,false", "false,true", "true,true"})
  void reconciliationDuringSlowPublicationPreservesOnlyTheSameSource(boolean candidate, boolean changed)
      throws Exception {
    Path file = seed("originaltoken ".repeat(300), false);
    Files.writeString(file, "acceptedtoken ".repeat(300));
    var stamp = Files.getLastModifiedTime(file);
    var reasons = new java.util.ArrayList<String>();
    var generations = new io.justsearch.indexerworker.index.IndexGenerationManager(temporary.resolve("generations"));
    var initial = generations.initializeOrLoad();
    String generation = generations.startMigration("reconciliation-publication").building_generation();
    generations.updateMigrationState(io.justsearch.indexerworker.index.IndexGenerationManager.MigrationState.SWITCHING);
    try (var serving = IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog())
            .ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
        var queue = new SqliteJobQueue(temporary.resolve("queue.db"))) {
      queue.open();
      var entry = JobQueue.EnqueueEntry.stat(file).withinRoot(file.getParent());
      var candidateRuntime = mock(RunningRuntime.class);
      when(candidateRuntime.isAcceptingWrites()).thenReturn(true);
      var service = new io.justsearch.indexerworker.services.WorkerIngestService(queue,
          mock(IndexingLoop.class), null, IndexingPacing.unthrottled(), temporary.resolve("generations"),
          generations.resolveGenerationPathStrict(initial.activeGenerationId()), candidateRuntime, serving, null, 0L);
      var registry = new io.justsearch.indexerworker.services.RootWatcherRegistry();
      service.setRootWatcherRegistry(registry);
      assertTrue(service.watchRoot(io.justsearch.ipc.WatchRootRequest.newBuilder()
          .setRootPath(file.getParent().toString()).setRootAdded(true).build(),
          io.justsearch.indexerworker.services.CallContext.none()).getWatching());
      if (candidate) assertEquals(1, queue.enqueueEnumeratedFilesForGeneration(generation, List.of(entry), null));
      else assertEquals(1, queue.enqueueEntries(List.of(entry)));
      var original = new java.util.concurrent.atomic.AtomicReference<JobQueue.IndexJob>();
      processAccepted(queue, false, true, new HashSet<>(), extracted -> {
        original.set(extracted.claim());
        var receipt = queue.listSwitchBufferOpsStrictForGeneration(generation);
        if (changed) {
          try {
            Files.writeString(file, "replacedtoken ".repeat(300));
            Files.setLastModifiedTime(file, stamp);
          } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        }
        // Hold the fully extracted claim across two reconciliations, as a slow embedding does.
        for (int pass = 0; pass < 2; pass++) {
          if (candidate) assertEquals("", service.syncDirectory(io.justsearch.ipc.SyncDirectoryRequest.newBuilder()
              .setRootPath(file.getParent().toString()).build(),
              io.justsearch.indexerworker.services.CallContext.none()).getError());
          else assertEquals(changed && pass == 0 ? 1 : 0,
              queue.enqueueMaintenanceScanEntries(List.of(entry), null, null));
          assertEquals(!changed, queue.ownsClaimForPublication(extracted.claim()));
        }
        if (!changed) assertEquals(receipt, queue.listSwitchBufferOpsStrictForGeneration(generation),
            "rediscovery must preserve the exact journal obligation");
      }, (stage, ms, reason) -> { if (reason != null) reasons.add(reason); });
      try (var runtime = open()) {
        assertTrue(hits(runtime, SchemaFields.CONTENT, changed ? "originaltoken" : "acceptedtoken") > 0);
        assertEquals(0, hits(runtime, SchemaFields.CONTENT, changed ? "acceptedtoken" : "originaltoken"));
      }
      if (changed) {
        assertTrue(reasons.contains("LOST_CLAIM_OWNERSHIP"), "supersession must report its actual reason");
        assertFalse(reasons.contains("DELETED_AFTER_SNAPSHOT"));
        assertEquals(1, queue.queueDepth());
        assertEquals(1, processAccepted(queue, false, true, new HashSet<>()));
        try (var runtime = open()) { assertTrue(hits(runtime, SchemaFields.CONTENT, "replacedtoken") > 0); }
      } else {
        assertEquals(0, queue.queueDepth());
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + temporary.resolve("queue.db"));
            var statement = connection.createStatement();
            var row = statement.executeQuery("SELECT unit_revision,state FROM jobs")) {
          assertTrue(row.next());
          assertEquals(original.get().unitRevision(), row.getString(1));
          assertEquals("DONE", row.getString(2));
        }
      }
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void immediateConsumptionSeesForceAtTheAdmissionBoundary(boolean scan) throws Exception {
    Path file = seed("unchangedpanda ".repeat(300), false);
    var consumed = new java.util.concurrent.atomic.AtomicInteger(-1);
    Set<String> marks = new HashSet<>();
    try (var queue = spy(new SqliteJobQueue(temporary.resolve("queue.db")))) {
      queue.open();
      org.mockito.stubbing.Answer<Integer> consume = invocation -> {
        int accepted = (int) invocation.callRealMethod();
        consumed.set(processAccepted(queue, false, true, marks));
        return accepted;
      };
      if (scan) doAnswer(consume).when(queue).enqueueEntries(anyList(), nullable(String.class), nullable(String.class));
      else doAnswer(consume).when(queue).enqueueEntries(anyList(), nullable(String.class));
      var service = new io.justsearch.indexerworker.services.WorkerIngestService(queue,
          mock(IndexingLoop.class), null, IndexingPacing.unthrottled(), null, null, null, null, null, 0L);
      service.setForcedPathSink(marks::addAll);
      if (scan) service.scanRoot(io.justsearch.ipc.ScanRootRequest.newBuilder()
          .setRootPath(file.getParent().toString()).setMode(io.justsearch.ipc.ScanMode.SCAN_MODE_FORCE_REINDEX).build(),
          ignored -> {}, io.justsearch.indexerworker.services.CallContext.none());
      else submitForce(queue, file, marks);
      assertEquals(1, consumed.get(), "a consumer can run before the enqueue caller returns");
    }
  }

  @Test
  @org.junit.jupiter.api.Timeout(45)
  void liveWatcherPublishesTwoEqualLengthEditsWithRestoredTimestamps() throws Exception {
    Path file = seed("oldquokkatoken ".repeat(300), false);
    var stamp = Files.getLastModifiedTime(file);
    try (var queue = new SqliteJobQueue(temporary.resolve("queue.db")); var owners = new TestEngineExecutors()) {
      queue.open();
      var registration = owners.register(new io.justsearch.core.execution.EngineExecutorSpec(
          "live-watcher", io.justsearch.core.execution.EngineExecutorSpec.Kind.BACKGROUND,
          io.justsearch.core.execution.EngineExecutorSpec.Mode.SCHEDULED, 1, 64, 2));
      Path ready = file.resolveSibling("ready.txt");
      var readySignal = new java.util.concurrent.CountDownLatch(1);
      try (var watcher = new io.justsearch.indexerworker.services.WorkerMethvinWatcher(
          registration, queue, null, ignored -> {}, null, (witness, collection, path) -> {
            if (path.equals(ready)) readySignal.countDown();
            else if (path.equals(file)) queue.enqueueEntries(
                List.of(JobQueue.EnqueueEntry.stat(path).withinRoot(file.getParent())), collection);
          })) {
        var service = new io.justsearch.indexerworker.services.WorkerIngestService(queue,
            mock(IndexingLoop.class), null, IndexingPacing.unthrottled(), null, null, null, null, null, 0L);
        service.setRootWatcherRegistry(new io.justsearch.indexerworker.services.RootWatcherRegistry(watcher));
        assertTrue(service.watchRoot(io.justsearch.ipc.WatchRootRequest.newBuilder()
            .setRootPath(file.getParent().toString()).setRootAdded(true).build(),
            io.justsearch.indexerworker.services.CallContext.none()).getWatching());
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (readySignal.getCount() != 0 && System.nanoTime() < deadline) {
          Files.writeString(ready, "ready");
          Thread.sleep(30);
        }
        assertEquals(0, readySignal.getCount(), "the actual OS watcher must have finished registration");
        for (String token : List.of("newbadgertoken", "newwombattoken")) {
          Files.writeString(file, (token + " ").repeat(300));
          Files.setLastModifiedTime(file, stamp);
          deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
          boolean searchable = false;
          while (!searchable && System.nanoTime() < deadline) {
            if (queue.queueDepth() > 0) processAccepted(queue, false, true, new HashSet<>());
            try (var runtime = open()) {
              searchable = hits(runtime, SchemaFields.CONTENT, token) > 0
                  && hits(runtime, SchemaFields.CHUNK_CONTENT, token) > 0;
            }
            if (!searchable) Thread.sleep(20);
          }
          assertTrue(searchable, "the live watcher must publish " + token + " in parent and chunk search");
        }
      }
    }
  }

  private void submitForce(SqliteJobQueue queue, Path file, Set<String> marks) {
    var service = new io.justsearch.indexerworker.services.WorkerIngestService(queue,
        mock(IndexingLoop.class), null, IndexingPacing.unthrottled(), null, null, null, null, null, 0L);
    service.setForcedPathSink(marks::addAll);
    assertEquals(1, service.submitBatch(io.justsearch.ipc.BatchRequest.newBuilder()
        .addFilePaths(file.toString()).setForceReindex(true).build(),
        io.justsearch.indexerworker.services.CallContext.none()).getAcceptedCount());
  }

  private void readyRetry() throws Exception {
    try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + temporary.resolve("queue.db"));
        var statement = connection.createStatement()) {
      assertEquals(1, statement.executeUpdate("UPDATE jobs SET retry_after=0 WHERE state='PENDING' AND attempts>0"),
          "the failed attempt must persist a pending, counted retry");
    }
  }

  private Path seed(String content, boolean omitSize) throws Exception {
    return seed(content, omitSize, false);
  }

  private Path seed(String content, boolean omitSize, boolean omitSourceHash) throws Exception {
    Path file = Files.writeString(Files.createDirectories(temporary.resolve("sources")).resolve("source.txt"), content);
    try (var runtime = open(); var parser = new ContentExtractor()) {
      var policy = TikaExtractionPolicy.defaults();
      var artifact = ExtractionArtifact.full(parser.extract(file), policy, "stamp-fixture", false).validate(policy, null);
      var document = IndexingDocumentOps.buildDocument(file, artifact, null, null, null, false, null, null,
          (stage, ms, reason) -> {}, org.slf4j.LoggerFactory.getLogger(getClass()), null,
          new IndexingDocumentOps.SourceFileMetadata(Files.size(file), Files.getLastModifiedTime(file).toMillis(), SourceContentHash.sha256(file)), "stable-uid");
      var fields = new HashMap<>(document.fields());
      if (omitSize) fields.remove(SchemaFields.SIZE_BYTES);
      if (omitSourceHash) fields.remove(SchemaFields.SOURCE_SHA256);
      runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
      refresh(runtime);
      String id = PathNormalizer.normalizeKey(file);
      String indexedContent = runtime.documentFieldOps().getDocumentContent(id);
      assertTrue(ChunkDocumentWriter.regenerateChunksFromExistingParent(runtime.documentFieldOps(),
          runtime.indexingCoordinator(), id, indexedContent, false) > 0);
      refresh(runtime);
      assertTrue(runtime.documentFieldOps().isUnmodified(id, Files.getLastModifiedTime(file).toMillis()),
          "the seeded parent and chunks must describe the same complete revision");
    }
    return file;
  }

  private void replaceAndProcess(Path file, FileTime stamp, String content, String oldToken, String newToken) throws Exception {
    Files.writeString(file, content);
    Files.setLastModifiedTime(file, stamp);
    assertEquals(stamp.toMillis(), Files.getLastModifiedTime(file).toMillis());
    assertEquals(1, process(file), "changed size must reach extraction despite restored mtime");
    try (var runtime = open()) {
      String id = PathNormalizer.normalizeKey(file);
      assertEquals(content.strip(), runtime.documentFieldOps().getDocumentContent(id).strip());
      assertEquals(String.valueOf(Files.size(file)), runtime.documentFieldOps().getDocumentField(id, SchemaFields.SIZE_BYTES));
      assertEquals(String.valueOf(stamp.toMillis()), runtime.documentFieldOps().getDocumentField(id, SchemaFields.MODIFIED_AT));
      for (String field : List.of(SchemaFields.CONTENT, SchemaFields.CHUNK_CONTENT)) {
        assertEquals(0, hits(runtime, field, oldToken), "old token must disappear from " + field);
        assertTrue(hits(runtime, field, newToken) > 0, "new token must be searchable in " + field);
      }
    }
    assertEquals(0, process(file), "persisted stamp must skip unchanged work after restart");
  }

  private static long hits(RunningRuntime runtime, String field, String token) throws Exception {
    return runtime.readPathOps().search(new TermQuery(new Term(field, token)), 100,
        Set.of(SchemaFields.DOC_ID), null, null).totalHits();
  }

  private RunningRuntime open() {
    return IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog())
        .atPath(temporary.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open();
  }

  private int process(Path file) throws Exception {
    try (var queue = new SqliteJobQueue(temporary.resolve("queue.db"))) {
      queue.open();
      assertEquals(1, queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(temporary))));
      int extracted = processAccepted(queue, false, true, new HashSet<>());
      assertEquals(0, queue.queueDepth());
      return extracted;
    }
  }

  private int processAccepted(SqliteJobQueue queue, boolean failExtraction, boolean complete,
      Set<String> forceMarks) throws Exception {
    return processAccepted(queue, failExtraction, complete, forceMarks, ignored -> {}, (stage, ms, reason) -> {});
  }

  private int processAccepted(SqliteJobQueue queue, boolean failExtraction, boolean complete,
      Set<String> forceMarks, java.util.function.Consumer<ExtractedJob> beforeWrite,
      IndexingDocumentOps.StageRecorder stageRecorder) throws Exception {
    try (var runtime = open(); var owners = new TestEngineExecutors(); var parser = spy(new ContentExtractor());
        var timebox = new TimeboxedContentExtractor(PipelineTestFixture.registration(owners,
            "stamp-restart", 1), parser, Duration.ofSeconds(20), null)) {
      if (failExtraction) doThrow(new io.justsearch.indexerworker.extract.SandboxExtractionException(
          "transient sandbox failure", null))
          .when(parser).extract(any(Path.class));
      var identity = mock(DocumentIdentityStore.class);
      when(identity.resolve(anyString(), anyLong()))
          .thenReturn(new DocumentIdentityStore.Identity("hash", "stable-uid", 1, 1));
      var journal = new IngestionOutcomeJournal(queue, mock(OperationalMetrics.class),
          mock(io.justsearch.telemetry.catalog.CounterMetric.class), () -> false);
      var authority = new WorkerIngestionAuthority();
      var stats = new BatchStats();
      var stale = mock(StaleSnapshotResolver.class);
      var fence = new FileMutationFence();
      var extractor = new JobBatchExtractor(authority, journal, queue, timebox,
          runtime.documentFieldOps(), runtime.indexCountOps(), stats, stale,
          mock(StaleSourceHandler.class), IndexingPacing.unthrottled(), new AtomicBoolean(true), forceMarks,
          () -> mock(PathResolutionStore.class), () -> identity, (stage, ms, reason) -> {}, () -> false, delta -> {}, fence);
      var writer = new JobBatchWriter(authority, runtime.indexingCoordinator(), null,
          mock(EmbeddingProviderLifecycle.class), () -> null, journal, queue, timebox,
          mock(OperationalMetrics.class), stats, stale, delta -> {}, stageRecorder,
          () -> false, () -> false, () -> null, fence);
      var claims = queue.pollPending(1);
      assertEquals(1, claims.size());
      var jobs = extractor.extractAll(claims);
      for (var job : jobs) {
        beforeWrite.accept(job);
        writer.write(job, null);
      }
      refresh(runtime);
      if (complete) journal.drainPending();
      return jobs.size();
    }
  }

  private static void refresh(RunningRuntime runtime) {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }
}
