/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.extract.ContentExtractor;
import io.justsearch.indexerworker.extract.ExtractionArtifact;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.indexerworker.extract.TikaExtractionPolicy;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.ops.BatchStats;
import io.justsearch.indexerworker.loop.ops.IndexingDocumentOps;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.NoVolumeIdentityExtension;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.ipc.DeleteByCollectionRequest;
import io.justsearch.ipc.ScanRootRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@ExtendWith(NoVolumeIdentityExtension.class)
final class UntaggedScanCollectionRemovalTest extends LuceneExecutorTestBase {
  @TempDir Path temporary;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void C5UntaggedBufferedTailCannotRestoreInheritedOwnerButC7FreshIngestionPublishes(boolean declared)
      throws Exception {
    Path base = temporary.resolve("base");
    var layout = new IndexGenerationManager(base).initializeOrLoad();
    Path root = Files.createDirectory(temporary.resolve("source"));
    var files = new java.util.ArrayList<Path>();
    for (int i = 0; i < 100; i++) {
      files.add(Files.writeString(root.resolve("file-" + i + ".txt"), "source content"));
    }
    if (declared) {
      new tools.jackson.databind.ObjectMapper().writeValue(temporary.resolve("watched_roots.json").toFile(),
          java.util.Map.of("schemaVersion", 1, "roots", List.of(
              java.util.Map.of("path", root.toString(), "collection", "docs"))));
    }
    Path index = layout.activeGenerationPath();
    try (var queue = new SqliteJobQueue(temporary.resolve("jobs.db"));
        var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(index)
            .withExecutorRegistrations(testLuceneExecutors()).open()) {
      queue.open();
      runtime.commitOps().stopCommitTimer();
      var service = new WorkerIngestService(queue, mock(IndexingLoop.class), null,
          IndexingPacing.unthrottled(), base, index, runtime, runtime, null, 0L);
      service.setDocumentIdentityStore(DocumentIdentityStore.UNAVAILABLE);
      service.setDeclaredRootsDirectory(temporary);
      queue.enqueue(files, "docs");
      publish(queue, runtime, queue.pollPending(1).getFirst());
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      assertTrue(runtime.indexCountOps().docCount() > 0, "the collection initially has published content");
      var removed = new AtomicBoolean();
      var request = ScanRootRequest.newBuilder().setRootPath(root.toString()).setRootAdded(true).build();
      service.scanRoot(request, progress -> {
        if (!progress.getComplete() && removed.compareAndSet(false, true)) {
          assertEquals(100, progress.getFilesWalked(), "removal is interleaved before the tail flush");
          var result = service.deleteByCollection(DeleteByCollectionRequest.newBuilder()
              .setCollection("docs").build(), CallContext.none());
          assertEquals("", result.getError());
        }
      }, CallContext.none());
      assertTrue(removed.get());
      assertEquals(0, queue.queueDepth(), "an untagged scan must cancel when its inherited owner is removed");
      assertEquals(0, runtime.indexCountOps().docCount(), "the removed collection remains absent");

      service.scanRoot(request.toBuilder().setCollection("docs").build(), progress -> {}, CallContext.none());
      assertEquals(100, queue.queueDepth(), "fresh deliberate ingestion has new publication authority");
      var fresh = queue.pollPending(1).getFirst();
      assertTrue(queue.ownsClaimForPublication(fresh));
      publish(queue, runtime, fresh);
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals("docs", runtime.documentFieldOps().getDocumentField(
          PathNormalizer.normalizeKey(fresh.path()), io.justsearch.indexing.SchemaFields.COLLECTION));
    }
    try (var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(index)
        .withExecutorRegistrations(testLuceneExecutors()).open()) {
      assertTrue(runtime.indexCountOps().docCount() > 0, "fresh publication survives reopen");
    }
  }

  private static void publish(JobQueue queue, RunningRuntime runtime, JobQueue.IndexJob claim) throws Exception {
    var policy = TikaExtractionPolicy.defaults();
    var extractor = mock(TimeboxedContentExtractor.class);
    when(extractor.extractionPolicy()).thenReturn(policy);
    var writer = new JobBatchWriter(new WorkerIngestionAuthority(), runtime.indexingCoordinator(),
        mock(WorkerSignalBus.class), mock(EmbeddingProviderLifecycle.class), () -> null,
        mock(IngestionOutcomeJournal.class), queue, extractor, mock(OperationalMetrics.class),
        mock(BatchStats.class), mock(StaleSnapshotResolver.class), delta -> {},
        mock(IndexingDocumentOps.StageRecorder.class), () -> false, () -> false, () -> null,
        new FileMutationFence());
    Path file = claim.path();
    var artifact = ExtractionArtifact.full(new ContentExtractor.ExtractionResult(
        Files.readString(file), file.getFileName().toString(), "text/plain"),
        policy, "collection-removal-test", false).validate(policy, null);
    var envelope = new FileEnvelope(file, PathNormalizer.normalizeKey(file), "hash", null,
        Files.size(file), Files.getLastModifiedTime(file).toMillis(), true, System.currentTimeMillis());
    writer.write(new ExtractedJob(claim, artifact, System.currentTimeMillis(), envelope, null,
        "00000000-0000-4000-8000-000000003107"), null);
  }
}
