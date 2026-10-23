/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.indexerworker.services.CallContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ipc.BatchRequest;
import io.justsearch.ipc.QueryPendingVduRequest;
import io.justsearch.ipc.ScanMode;
import io.justsearch.ipc.ScanRootProgress;
import io.justsearch.ipc.ScanRootRequest;
import io.justsearch.ipc.WatchRootRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerUserExclusionsEngineTest extends LuceneExecutorTestBase {
  private static final String KEY = "01994180-0000-7000-8000-000000003101";
  private static final String PLAN = "b".repeat(64);
  @Test
  void AX1ExplicitIngestOfWatchedPathCannotBypassPolicy() throws Exception {
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> {
      Path root = Files.createDirectory(temp.resolve("source"));
      Path file = Files.writeString(Files.createDirectory(root.resolve("private")).resolve("file.txt"), "private");
      ConfigStore previous = ConfigStore.globalOrNull();
      ConfigStore installed = install("[\"[legacy\",\"{broken\",\"private/**\"]");
      try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"))) {
        queue.open();
        var service = new WorkerIngestService(queue, mock(IndexingLoop.class), null,
            IndexingPacing.unthrottled(), null, null, null, null, null, 0L);
        assertTrue(service.watchRoot(WatchRootRequest.newBuilder()
            .setRootPath(root.toString()).setRootAdded(true).build(), CallContext.none()).getWatching());
        service.submitBatch(BatchRequest.newBuilder().addFilePaths(file.toString())
            .setRootAdded(true).build(), CallContext.none());
        assertEquals(0, queue.queueDepth(), "EX1: explicit watched files use the saved root policy");
      } finally {
        ConfigStore.restoreGlobal(installed, previous);
      }
    }, "AX1: invalid saved entries must never stop this admission path");
  }

  @Test
  void AX1RecordedEnumerationReplayedAfterRestartUsesCurrentPolicy() throws Exception {
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> {
      Path root = Files.createDirectory(temp.resolve("source"));
      Files.writeString(root.resolve("private.txt"), "private");
      Path keep = Files.writeString(root.resolve("public.txt"), "public");
      Path base = temp.resolve("index");
      var layout = new IndexGenerationManager(base).initializeOrLoad();
      long epoch;
      try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"))) {
        queue.open();
        epoch = queue.beginRecordedWalk(KEY, PLAN, true).enumerationEpoch();
      }
      var prepared = new WorkerIngestService.RecordedRootScan(ScanRootRequest.newBuilder()
          .setRootPath(root.toString()).setMode(ScanMode.SCAN_MODE_FORCE_REINDEX).build(),
          KEY, epoch, layout.activeGenerationId(), false, List.of());
      ConfigStore previous = ConfigStore.globalOrNull();
      ConfigStore installed = install("[\"[legacy\",\"{broken\",\"private.txt\"]");
      try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"), ignored -> JobQueue.RecordedClaimDecision.ALLOW)) {
        queue.open();
        var runtime = mock(RunningRuntime.class);
        var service = new WorkerIngestService(queue, mock(IndexingLoop.class), null,
            IndexingPacing.unthrottled(), base, layout.activeGenerationPath(), runtime, runtime, null, 0L);
        assertTrue(service.watchRoot(WatchRootRequest.newBuilder()
            .setRootPath(root.toString()).setRootAdded(true).build(), CallContext.none()).getWatching());
        List<ScanRootProgress> frames = new ArrayList<>();
        service.scanRecordedRoot(prepared, frames::add, CallContext.none());
        assertEquals("", frames.getLast().getTerminalReasonCode());
        assertEquals(List.of(keep), queue.pollPending(10).stream().map(JobQueue.IndexJob::path).toList(),
            "EX2: frozen empty globs cannot override current policy after reopening jobs.db");
      } finally {
        ConfigStore.restoreGlobal(installed, previous);
      }
    }, "AX1: invalid saved entries must never stop this admission path");
  }

  @TempDir Path temp;

  @Test
  void EX1ExplicitIngestOfWatchedPathCannotBypassPolicy() throws Exception {
    Path root = Files.createDirectory(temp.resolve("source"));
    Path file = Files.writeString(Files.createDirectory(root.resolve("private")).resolve("file.txt"), "private");
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore installed = install("[\"private/**\"]");
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"))) {
      queue.open();
      var service = new WorkerIngestService(queue, mock(IndexingLoop.class), null,
          IndexingPacing.unthrottled(), null, null, null, null, null, 0L);
      assertTrue(service.watchRoot(WatchRootRequest.newBuilder()
          .setRootPath(root.toString()).setRootAdded(true).build(), CallContext.none()).getWatching());
      service.submitBatch(BatchRequest.newBuilder().addFilePaths(file.toString())
          .setRootAdded(true).build(), CallContext.none());
      assertEquals(0, queue.queueDepth(), "EX1: explicit watched files use the saved root policy");
    } finally {
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  @Test
  void EX2RecordedEnumerationReplayedAfterRestartUsesCurrentPolicy() throws Exception {
    Path root = Files.createDirectory(temp.resolve("source"));
    Files.writeString(root.resolve("private.txt"), "private");
    Path keep = Files.writeString(root.resolve("public.txt"), "public");
    Path base = temp.resolve("index");
    var layout = new IndexGenerationManager(base).initializeOrLoad();
    long epoch;
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"))) {
      queue.open();
      epoch = queue.beginRecordedWalk(KEY, PLAN, true).enumerationEpoch();
    }
    var prepared = new WorkerIngestService.RecordedRootScan(ScanRootRequest.newBuilder()
        .setRootPath(root.toString()).setMode(ScanMode.SCAN_MODE_FORCE_REINDEX).build(),
        KEY, epoch, layout.activeGenerationId(), false, List.of());
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore installed = install("[\"private.txt\"]");
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"), ignored -> JobQueue.RecordedClaimDecision.ALLOW)) {
      queue.open();
      var runtime = mock(RunningRuntime.class);
      var service = new WorkerIngestService(queue, mock(IndexingLoop.class), null,
          IndexingPacing.unthrottled(), base, layout.activeGenerationPath(), runtime, runtime, null, 0L);
      assertTrue(service.watchRoot(WatchRootRequest.newBuilder()
          .setRootPath(root.toString()).setRootAdded(true).build(), CallContext.none()).getWatching());
      List<ScanRootProgress> frames = new ArrayList<>();
      service.scanRecordedRoot(prepared, frames::add, CallContext.none());
      assertEquals("", frames.getLast().getTerminalReasonCode());
      assertEquals(List.of(keep), queue.pollPending(10).stream().map(JobQueue.IndexJob::path).toList(),
          "EX2: frozen empty globs cannot override current policy after reopening jobs.db");
    } finally {
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  @Test
  void EX5SavedPolicyRetainsIndexedTextButStopsFurtherEnrichment() throws Exception {
    Path root = Files.createDirectory(temp.resolve("source"));
    Path file = Files.writeString(Files.createDirectory(root.resolve("private")).resolve("file.txt"), "private");
    Path keep = Files.writeString(root.resolve("public.txt"), "public");
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore installed = install("[]");
    try (RunningRuntime runtime = IndexSchema.fromCatalog(enrichmentCatalog())
        .atPath(temp.resolve("lucene")).withExecutorRegistrations(testLuceneExecutors()).open()) {
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, key(file), SchemaFields.DOC_UID, "00000000-0000-4000-8000-000000003103",
          SchemaFields.PATH, key(file), SchemaFields.CONTENT, "already searchable private text",
          SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_PENDING,
          SchemaFields.EMBEDDING_STATUS, "PENDING", SchemaFields.NER_STATUS, "PENDING", SchemaFields.SPLADE_STATUS, "PENDING")));
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, key(keep), SchemaFields.DOC_UID, "00000000-0000-4000-8000-000000003104",
          SchemaFields.PATH, key(keep), SchemaFields.CONTENT, "public",
          SchemaFields.VDU_STATUS, "PENDING", SchemaFields.EMBEDDING_STATUS, "PENDING",
          SchemaFields.NER_STATUS, "PENDING", SchemaFields.SPLADE_STATUS, "PENDING")));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      var service = new WorkerIngestService(mock(JobQueue.class), mock(IndexingLoop.class), null,
          IndexingPacing.unthrottled(), null, null, runtime, runtime, null, 0L);
      service.seedRootVolumesAfterUpgrade(List.of(root));
      service.useExclusionRoots(() -> List.of(root));
      assertEquals(java.util.Set.of(key(file), key(keep)), java.util.Set.copyOf(service.queryPendingVdu(
          QueryPendingVduRequest.newBuilder().build(), CallContext.none()).getDocIdsList()));
      for (String status : List.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.NER_STATUS, SchemaFields.SPLADE_STATUS)) {
        assertEquals(java.util.Set.of(key(file), key(keep)),
            java.util.Set.copyOf(runtime.documentFieldOps().queryDocIdsByField(status, "PENDING", 100)),
            "Precondition: the fixture has pending work for " + status);
      }
      installed.update(config("[\"private/**\"]"));
      assertEquals("already searchable private text", runtime.documentFieldOps().getDocumentContent(key(file)),
          "EX5: saving alone never removes existing text");
      assertEquals(List.of(key(keep)), service.queryPendingVdu(
          QueryPendingVduRequest.newBuilder().setLimit(1).build(), CallContext.none()).getDocIdsList(),
          "EX5: excluded indexed files must not be selected for picture reading");
      for (String status : List.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.NER_STATUS, SchemaFields.SPLADE_STATUS)) {
        assertEquals(List.of(key(keep)), runtime.documentFieldOps().queryDocIdsByField(status, "PENDING", 1),
            "EX5: eligible files must not be starved by an excluded selection head: " + status);
      }
      installed.update(config("[]"));
      assertEquals(java.util.Set.of(key(file), key(keep)), java.util.Set.copyOf(service.queryPendingVdu(
          QueryPendingVduRequest.newBuilder().build(), CallContext.none()).getDocIdsList()));
    } finally {
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  @Test
  void EN2VerifiedDeletionStillRemovesParentAndChunks() throws Exception {
    Path root = Files.createDirectory(temp.resolve("source"));
    Path file = Files.writeString(root.resolve("public.txt"), "public");
    String parent = key(file);
    String chunk = parent + "#0";
    try (RunningRuntime runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0))
        .atPath(temp.resolve("lucene")).withExecutorRegistrations(testLuceneExecutors()).open()) {
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, parent, SchemaFields.DOC_UID, "00000000-0000-4000-8000-000000003105",
          SchemaFields.PATH, parent, SchemaFields.CONTENT, "parent")));
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, chunk, SchemaFields.DOC_UID, "00000000-0000-4000-8000-000000003105#0",
          SchemaFields.PARENT_DOC_ID, parent,
          SchemaFields.PATH, parent, SchemaFields.IS_CHUNK, "true", SchemaFields.CONTENT, "child")));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      var service = new WorkerIngestService(mock(JobQueue.class), mock(IndexingLoop.class), null,
          IndexingPacing.unthrottled(), null, null, runtime, runtime, null, 0L);
      assertTrue(service.watchRoot(WatchRootRequest.newBuilder()
          .setRootPath(root.toString()).setRootAdded(true).build(), CallContext.none()).getWatching());
      Files.delete(file);
      var response = service.syncDirectory(io.justsearch.ipc.SyncDirectoryRequest.newBuilder()
          .setRootPath(root.toString()).build(), CallContext.none());
      assertEquals("", response.getError());
      assertEquals(null, runtime.documentFieldOps().getDocumentField(parent, SchemaFields.DOC_ID));
      assertEquals(null, runtime.documentFieldOps().getDocumentField(chunk, SchemaFields.DOC_ID));
    }
  }

  @Test
  void EX2PolicySavedAfterExtractionPreventsPublication() throws Exception {
    Path root = Files.createDirectory(temp.resolve("source"));
    Path file = Files.writeString(root.resolve("private.txt"), "private");
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore installed = install("[]");
    try (RunningRuntime runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0))
        .atPath(temp.resolve("lucene")).withExecutorRegistrations(testLuceneExecutors()).open()) {
      var queue = mock(JobQueue.class);
      org.mockito.Mockito.when(queue.ownsClaimForPublication(org.mockito.ArgumentMatchers.any())).thenReturn(true);
      var extractor = mock(io.justsearch.indexerworker.extract.TimeboxedContentExtractor.class);
      var extractionPolicy = io.justsearch.indexerworker.extract.TikaExtractionPolicy.defaults();
      org.mockito.Mockito.when(extractor.extractionPolicy()).thenReturn(extractionPolicy);
      var writer = new JobBatchWriter(runtime.indexingCoordinator(),
          mock(io.justsearch.indexerworker.coordination.WorkerSignalBus.class),
          mock(EmbeddingProviderLifecycle.class), () -> null,
          mock(IngestionOutcomeJournal.class), queue, extractor,
          mock(io.justsearch.indexerworker.metrics.OperationalMetrics.class),
          mock(io.justsearch.indexerworker.loop.ops.BatchStats.class),
          mock(StaleSnapshotResolver.class), delta -> {},
          mock(io.justsearch.indexerworker.loop.ops.IndexingDocumentOps.StageRecorder.class),
          () -> false, () -> false, () -> null, new FileMutationFence());
      var artifact = io.justsearch.indexerworker.extract.ExtractionArtifact.full(
          new io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult("private", "private.txt", "text/plain"),
          extractionPolicy, "exclusions-test", false).validate(extractionPolicy, null);
      var claim = new JobQueue.IndexJob(file, null, null, null, null, null, false, null, root);
      var envelope = new FileEnvelope(file,
          io.justsearch.indexerworker.util.PathNormalizer.normalizeKey(file), "hash", null, 7, 1, true, 1);
      var extracted = new ExtractedJob(claim, artifact, System.currentTimeMillis(),
          envelope, null, "00000000-0000-4000-8000-000000003102");
      installed.update(config("[\"private.txt\"]"));
      org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> writer.write(extracted, null));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals(null, runtime.documentFieldOps().getDocumentField(key(file), SchemaFields.DOC_ID),
          "EX2: extraction prepared under older policy cannot publish after the saved list changes");
    } finally {
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  @Test
  void EX1AppliedMatchesStayAbsentThroughMaintenanceScansExplicitIngestAndBootWalk() throws Exception {
    Path root = Files.createDirectory(temp.resolve("source"));
    Path file = Files.writeString(root.resolve("private.txt"), "private");
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore installed = install("[]");
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"));
        RunningRuntime runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0))
            .atPath(temp.resolve("lucene")).withExecutorRegistrations(testLuceneExecutors()).open()) {
      queue.open();
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, key(file), SchemaFields.DOC_UID, "00000000-0000-4000-8000-000000003106",
          SchemaFields.PATH, key(file), SchemaFields.CONTENT, "private")));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      var service = new WorkerIngestService(queue, mock(IndexingLoop.class), null,
          IndexingPacing.unthrottled(), null, null, runtime, runtime, null, 0L);
      assertTrue(service.watchRoot(WatchRootRequest.newBuilder().setRootPath(root.toString())
          .setRootAdded(true).build(), CallContext.none()).getWatching());
      service.submitBatch(BatchRequest.newBuilder().addFilePaths(file.toString()).build(), CallContext.none());
      assertEquals(1, queue.queueDepth(), "Precondition: work was queued before the save");
      installed.update(config("[\"private.txt\"]"));
      assertEquals("private", runtime.documentFieldOps().getDocumentContent(key(file)));
      var indexing = mock(io.justsearch.app.api.IndexingService.class);
      var context = CallContext.none().engineContext();
      org.mockito.Mockito.when(indexing.getWatchedRoots(context)).thenReturn(
          List.of(new io.justsearch.app.api.IndexingService.WatchedRoot("docs", root)));
      org.mockito.Mockito.when(indexing.deleteDocByIdWithOutcome(org.mockito.ArgumentMatchers.anyString(),
          org.mockito.ArgumentMatchers.eq(context))).thenAnswer(call -> {
            var response = service.deleteById(
                io.justsearch.ipc.DeleteByIdRequest.newBuilder().setDocId(call.getArgument(0)).build(),
                CallContext.none());
            return new io.justsearch.app.api.IndexingService.DeletionOutcome(
                response.getSuccess() && response.getError().isEmpty() && !response.getBuffered(),
                response.getError());
          });
      var applied = new io.justsearch.app.services.excludes.ExcludesServiceImpl(() -> indexing)
          .applyExcludes(false, context);
      assertEquals(1, applied.matchedFiles());
      assertEquals(null, runtime.documentFieldOps().getDocumentField(key(file), SchemaFields.DOC_ID));
      assertEquals(0, queue.queueDepth());
      var synced = service.syncDirectory(io.justsearch.ipc.SyncDirectoryRequest.newBuilder()
          .setRootPath(root.toString()).build(), CallContext.none());
      assertEquals("", synced.getError());
      assertEquals(0, queue.queueDepth(), "EX1: the periodic check cannot restore an applied match");
      service.reconcileRootStrict(root, true);
      assertEquals(0, queue.queueDepth(), "EX1: overflow/reconcile cannot restore an applied match");
      for (ScanMode mode : List.of(ScanMode.SCAN_MODE_INITIAL, ScanMode.SCAN_MODE_RESCAN,
          ScanMode.SCAN_MODE_FORCE_REINDEX)) {
        List<ScanRootProgress> frames = new ArrayList<>();
        service.scanRoot(ScanRootRequest.newBuilder().setRootPath(root.toString()).setMode(mode).build(),
            frames::add, CallContext.none());
        assertEquals("", frames.getLast().getTerminalReasonCode());
        assertEquals(0, queue.queueDepth(), "EX1: an applied match stays out on " + mode);
      }
      service.submitBatch(BatchRequest.newBuilder().addFilePaths(file.toString()).build(), CallContext.none());
      assertEquals(0, queue.queueDepth(), "EX1: explicit ingest cannot restore an applied match");
      var boot = new WorkerIngestService(queue, mock(IndexingLoop.class), null,
          IndexingPacing.unthrottled(), null, null, runtime, runtime, null, 0L);
      assertTrue(boot.watchRoot(WatchRootRequest.newBuilder().setRootPath(root.toString()).build(),
          CallContext.none()).getWatching());
      boot.scanRoot(ScanRootRequest.newBuilder().setRootPath(root.toString()).build(), frame -> {}, CallContext.none());
      assertEquals(0, queue.queueDepth(), "EX1: a fresh worker boot walk cannot restore an applied match");
      assertEquals(null, runtime.documentFieldOps().getDocumentField(key(file), SchemaFields.DOC_ID));
    } finally {
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  @Test
  void EN2DeclaredNoFileProjectionKeepsEnrichmentEligibility() throws Exception {
    Path root = Files.createDirectory(temp.resolve("source"));
    var fields = new ArrayList<>(FieldCatalogDef.forChunkTesting(0).fields());
    fields.add(new FieldCatalogDef.FieldDef(SchemaFields.PROJECTION_SOURCE_ID, "keyword", true, false,
        List.of(), null, null, false));
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore installed = install("[\"private/**\"]");
    try (RunningRuntime runtime = IndexSchema.fromCatalog(new FieldCatalogDef("no-file-policy-fixture", fields))
        .atPath(temp.resolve("lucene")).withExecutorRegistrations(testLuceneExecutors()).open()) {
      String id = "memory:record-7";
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, id, SchemaFields.DOC_UID, "00000000-0000-4000-8000-000000003107",
          SchemaFields.PATH, key(root.resolve("private/notes.txt")), SchemaFields.PROJECTION_SOURCE_ID, "source-7",
          SchemaFields.CONTENT, "logical source", SchemaFields.EMBEDDING_STATUS, "PENDING")));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      var service = new WorkerIngestService(mock(JobQueue.class), mock(IndexingLoop.class), null,
          IndexingPacing.unthrottled(), null, null, runtime, runtime, null, 0L);
      service.seedRootVolumesAfterUpgrade(List.of(root));
      service.useExclusionRoots(() -> List.of(root));
      assertEquals(List.of(id), runtime.documentFieldOps().queryDocIdsByField(
          SchemaFields.EMBEDDING_STATUS, "PENDING", 10),
          "EN2: a declared no-file source stays eligible even when its provenance path matches a file exclusion");
    } finally {
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  private static String key(Path file) {
    return io.justsearch.indexerworker.util.PathNormalizer.normalizeKey(file);
  }

  @Test
  void EX1RenameCannotPublishExistingRowsAtExcludedDestination() throws Exception {
    Path root = Files.createDirectory(temp.resolve("source"));
    Path oldFile = Files.writeString(root.resolve("public.txt"), "public");
    Path destination = Files.createDirectory(root.resolve("private")).resolve("moved.txt");
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore installed = install("[\"private/**\"]");
    try (RunningRuntime runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0))
        .atPath(temp.resolve("lucene")).withExecutorRegistrations(testLuceneExecutors()).open()) {
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, key(oldFile), SchemaFields.DOC_UID, "00000000-0000-4000-8000-000000003108",
          SchemaFields.PATH, key(oldFile), SchemaFields.CONTENT, "public")));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      var service = new WorkerIngestService(mock(JobQueue.class), mock(IndexingLoop.class), null,
          IndexingPacing.unthrottled(), null, null, runtime, runtime, null, 0L);
      service.seedRootVolumesAfterUpgrade(List.of(root));
      service.setDocumentIdentityStore(mock(io.justsearch.indexerworker.identity.DocumentIdentityStore.class));
      Files.move(oldFile, destination);
      var response = service.updateDocumentPaths(io.justsearch.ipc.UpdatePathsRequest.newBuilder()
          .addMappings(io.justsearch.ipc.PathMapping.newBuilder()
              .setOldPath(oldFile.toString()).setNewPath(destination.toString())).build(), CallContext.none());
      assertEquals(0, response.getUpdatedCount(), "EX1: rename must not publish at an excluded destination");
      assertEquals(List.of(oldFile.toString()), response.getFailedPathsList());
      assertEquals(null, runtime.documentFieldOps().getDocumentField(key(destination), SchemaFields.DOC_ID));
      assertEquals("public", runtime.documentFieldOps().getDocumentContent(key(oldFile)),
          "EX5: refusing admission does not authorise deletion of the old indexed content");
    } finally {
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  private static FieldCatalogDef enrichmentCatalog() {
    var base = FieldCatalogDef.forChunkTesting(0);
    var fields = new ArrayList<>(base.fields());
    for (String status : List.of(SchemaFields.NER_STATUS, SchemaFields.SPLADE_STATUS)) {
      fields.add(new FieldCatalogDef.FieldDef(status, "keyword", true, true,
          List.of("filter"), null, null, false));
    }
    return new FieldCatalogDef("exclusion-enrichment-fixture", fields);
  }

  private static ConfigStore install(String patterns) {
    var installed = new ConfigStore(config(patterns));
    ConfigStore.setGlobal(installed);
    return installed;
  }

  private static ResolvedConfig config(String patterns) {
    return ResolvedConfig.builder().putDefault("justsearch.ui.exclude_patterns", patterns).build();
  }
}
