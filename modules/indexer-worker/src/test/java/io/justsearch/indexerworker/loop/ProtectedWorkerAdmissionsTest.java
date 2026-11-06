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

@org.junit.jupiter.api.extension.ExtendWith(io.justsearch.indexerworker.services.NoVolumeIdentityExtension.class)
final class ProtectedWorkerAdmissionsTest extends LuceneExecutorTestBase {
  @TempDir Path temp;
  @Test
  void S1ProtectedFilesStayOutOfInitialForcedPeriodicExplicitAndRecoveryAdmissions() throws Exception {
    Path root = Files.createDirectories(temp.resolve(".notes"));
    Path key = Files.writeString(root.resolve("password.pem"), "private");
    Files.writeString(root.resolve(".env"), "private");
    Files.writeString(Files.createDirectories(root.resolve(".hidden")).resolve("private.txt"), "private");
    Path ordinary = Files.writeString(root.resolve("notes.txt"), "public");
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore installed = install("[]");
    try {
      for (String arm : List.of("INITIAL", "RESCAN", "FORCE_REINDEX", "SYNC", "RECONCILE", "BATCH")) {
        try (var queue = new SqliteJobQueue(temp.resolve(arm + ".db"));
            RunningRuntime runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0))
                .atPath(temp.resolve("lucene-" + arm)).withExecutorRegistrations(testLuceneExecutors()).open()) {
          queue.open();
          var service = new WorkerIngestService(queue, mock(IndexingLoop.class), null,
              IndexingPacing.unthrottled(), null, null, runtime, runtime, null, 0L);
          assertTrue(service.watchRoot(WatchRootRequest.newBuilder().setRootPath(root.toString())
              .setRootAdded(true).build(), CallContext.none()).getWatching());
          switch (arm) {
            case "SYNC" -> assertEquals("", service.syncDirectory(io.justsearch.ipc.SyncDirectoryRequest.newBuilder()
                .setRootPath(root.toString()).build(), CallContext.none()).getError());
            case "RECONCILE" -> service.reconcileRootStrict(root, true);
            case "BATCH" -> service.submitBatch(BatchRequest.newBuilder().setRootAdded(true)
                .addFilePaths(key.toString()).addFilePaths(ordinary.toString()).build(), CallContext.none());
            default -> service.scanRoot(ScanRootRequest.newBuilder().setRootPath(root.toString())
                .setMode(ScanMode.valueOf("SCAN_MODE_" + arm)).build(), frame -> {}, CallContext.none());
          }
          assertEquals(List.of(ordinary), queue.pollPending(20).stream().map(JobQueue.IndexJob::path).toList(),
              "S1/P2: only ordinary files from a dot-directory root are admitted by " + arm);
        }
      }
    } finally { ConfigStore.restoreGlobal(installed, previous); }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void S10ProtectedLegacyExtractionCannotPublishAfterUpgrade(boolean aliasedAncestor) throws Exception {
    Path root;
    Path file;
    if (aliasedAncestor) {
      Path credentials = Files.createDirectories(temp.resolve(".aws/nested"));
      Files.writeString(credentials.resolve("notes.txt"), "private");
      root = Files.createSymbolicLink(temp.resolve("source"), credentials);
      file = root.resolve("notes.txt");
    } else {
      root = Files.createDirectory(temp.resolve("source"));
      file = Files.writeString(root.resolve("private.pem"), "private");
    }
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore installed = install("[]");
    try (RunningRuntime runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0))
        .atPath(temp.resolve("lucene")).withExecutorRegistrations(testLuceneExecutors()).open()) {
      var queue = mock(JobQueue.class);
      org.mockito.Mockito.when(queue.ownsClaimForPublication(org.mockito.ArgumentMatchers.any())).thenReturn(true);
      var extractor = mock(io.justsearch.indexerworker.extract.TimeboxedContentExtractor.class);
      var extractionPolicy = io.justsearch.indexerworker.extract.TikaExtractionPolicy.defaults();
      org.mockito.Mockito.when(extractor.extractionPolicy()).thenReturn(extractionPolicy);
      var writer = new JobBatchWriter(new WorkerIngestionAuthority(),runtime.indexingCoordinator(),
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
      var envelope = FileEnvelope.fromSnapshot(new FileFreshnessSnapshot(file,
          io.justsearch.indexerworker.util.PathNormalizer.normalizeKey(file), "hash", null,
          7, java.nio.file.attribute.FileTime.fromMillis(1), null, true, 1));
      var extracted = new ExtractedJob(claim, artifact, System.currentTimeMillis(),
          envelope, null, "00000000-0000-4000-8000-000000003102");
      org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> writer.write(extracted, null));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals(null, runtime.documentFieldOps().getDocumentField(key(file), SchemaFields.DOC_ID),
          "S10: a protected artifact extracted before upgrade cannot publish after upgrade");
    } finally {
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  private static String key(Path file) {
    return io.justsearch.indexerworker.util.PathNormalizer.normalizeKey(file);
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
