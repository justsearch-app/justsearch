/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.embed.EmbeddingCompatibilityController;
import io.justsearch.indexerworker.embed.EmbeddingFingerprint;
import io.justsearch.indexerworker.embed.EmbeddingMetadataOverlay;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.runtime.CommitMetadataSource;
import io.justsearch.ipc.BatchRequest;
import io.justsearch.ipc.ScanMode;
import io.justsearch.ipc.ScanRootProgress;
import io.justsearch.ipc.ScanRootRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Real queue, index and ECC; the later selected document rewrite simulates extraction. */
final class WorkerSelectedForceCompatibilityTest extends LuceneExecutorTestBase {
  private static final String CURRENT_FP = "c".repeat(64);
  private static final String OLD_FP = "b".repeat(64);
  private static final String KEY = "01994180-0000-7000-8000-000000000921";
  private static final String COLLECTION = "selected-force";
  @TempDir Path temp;

  @AfterEach
  void clearFingerprint() { EmbeddingFingerprint.invalidate(); }

  @ParameterizedTest
  @CsvSource({"false,recorded", "true,recorded", "false,ordinary", "true,ordinary", "false,refused", "true,refused"})
  void selectedForceCannotAttestToUntouchedVectors(boolean mismatch, String kind) throws Exception {
    EmbeddingFingerprint.setForTesting(CURRENT_FP);
    Path base = temp.resolve("index");
    var layout = new IndexGenerationManager(base).initializeOrLoad();
    Path source = Files.writeString(temp.resolve("selected.txt"), "selected source");
    Optional<String> stored = mismatch ? Optional.of(OLD_FP) : Optional.empty();
    try (var seed = openRuntime(layout.activeGenerationPath(), () -> stored)) {
      float[] vector = new float[768];
      java.util.Arrays.fill(vector, 0.05f);
      for (int i = 0; i < 2; i++) {
        seed.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
            SchemaFields.DOC_ID, "parent-" + i, SchemaFields.DOC_UID, "parent-" + i + "#0",
            SchemaFields.VECTOR, vector, SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED)));
      }
      seed.commitOps().commitAndTrack();
    }

    var stamp = new AtomicReference<Supplier<Optional<String>>>(Optional::empty);
    var expected = mismatch ? EmbeddingCompatibilityController.State.BLOCKED_MISMATCH
        : EmbeddingCompatibilityController.State.BLOCKED_LEGACY;
    try (var runtime = openRuntime(layout.activeGenerationPath(), () -> stamp.get().get());
        var queue = new SqliteJobQueue(base.resolve("jobs.db"), ignored -> JobQueue.RecordedClaimDecision.ALLOW_FORCE)) {
      queue.open();
      var ecc = realEcc(runtime);
      ecc.refresh();
      stamp.set(ecc::fingerprintForCommit);
      assertEquals(expected, ecc.state());
      assertEquals(2, count(runtime, SchemaFields.EMBEDDING_STATUS_COMPLETED));
      assertEquals(0, count(runtime, SchemaFields.EMBEDDING_STATUS_PENDING));
      var loop = mock(IndexingLoop.class);
      JobQueue admissionQueue = queue;
      if (kind.equals("refused")) {
        admissionQueue = spy(queue);
        doThrow(WorkerServiceException.unavailable("QUEUE_ADMISSION_FAILED"))
            .when(admissionQueue).enqueueEntries(anyList(), eq(COLLECTION));
      }
      var service = new WorkerIngestService(admissionQueue, loop, null,
          IndexingPacing.unthrottled(), base, layout.activeGenerationPath(), runtime, runtime, null, 0L);
      service.setEmbeddingCompatController(ecc);
      admit(kind, source, service, queue, layout.activeGenerationId(), loop);

      assertEquals(expected, ecc.state(), "selected admission or refusal must not transition compatibility");
      assertFalse(ecc.checkRebuildCompletion(queue.queueDepth(), count(runtime, SchemaFields.EMBEDDING_STATUS_PENDING)),
          "pending zero plus old completed-vector evidence cannot certify selected force");
      assertTrue(ecc.fingerprintToStamp().isEmpty());
      if (!kind.equals("refused")) {
        // Simulate only the selected primary document rewrite; no extraction/model run is claimed.
        runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
            SchemaFields.DOC_ID, "parent-0", SchemaFields.DOC_UID, "parent-0#0",
            SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING)));
      }
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals(kind.equals("refused") ? 2 : 1, count(runtime, SchemaFields.EMBEDDING_STATUS_COMPLETED));
      assertEquals(kind.equals("refused") ? 0 : 1, count(runtime, SchemaFields.EMBEDDING_STATUS_PENDING));
      assertEquals(stored.orElse(null), runtime.latestCommitUserDataBestEffort().get(EmbeddingCompatibilityController.COMMIT_META_KEY));
    }
    try (var reopened = openRuntime(layout.activeGenerationPath(), Optional::empty)) {
      var ecc = realEcc(reopened);
      ecc.refresh();
      assertEquals(expected, ecc.state());
      assertEquals(stored.orElse(null), reopened.latestCommitUserDataBestEffort().get(EmbeddingCompatibilityController.COMMIT_META_KEY));
    }
  }

  private static void admit(String kind, Path file, WorkerIngestService service, SqliteJobQueue queue,
      String generation, IndexingLoop loop) throws IOException {
    var batch = BatchRequest.newBuilder().addFilePaths(file.toString())
        .setTargetCollection(COLLECTION).setForceReindex(true).build();
    if (kind.equals("refused")) {
      var failure = assertThrows(WorkerServiceException.class, () -> service.submitBatch(batch, CallContext.none()));
      assertEquals(WorkerServiceException.Status.UNAVAILABLE, failure.status());
      assertEquals(0, queue.queueDepth());
      verify(loop, never()).markForced(anyCollection());
      return;
    }
    if (kind.equals("recorded")) {
      long epoch = queue.beginRecordedWalk(KEY, "a".repeat(64), true).enumerationEpoch();
      List<ScanRootProgress> frames = new ArrayList<>();
      service.scanRecordedRoot(new WorkerIngestService.RecordedRootScan(
          ScanRootRequest.newBuilder().setRootPath(file.toString()).setCollection(COLLECTION)
              .setMode(ScanMode.SCAN_MODE_FORCE_REINDEX).build(), KEY, epoch, generation, true, List.of()),
          frames::add, CallContext.none());
      assertTrue(frames.getLast().getComplete());
      assertEquals("", frames.getLast().getTerminalReasonCode());
      assertEquals(1, frames.getLast().getFilesAdmitted());
      verify(loop, never()).markForced(anyCollection());
    } else {
      assertEquals(1, service.submitBatch(batch, CallContext.none()).getAcceptedCount());
      verify(loop).markForced(List.of(PathNormalizer.normalizeKey(file)));
    }
    var claims = queue.pollPending(2);
    assertEquals(1, claims.size());
    var claim = claims.getFirst();
    assertEquals(file, claim.path());
    assertEquals(kind.equals("recorded"), claim.recordedForce());
    assertEquals(kind.equals("recorded") ? KEY : null, claim.scanId());
  }

  private RunningRuntime openRuntime(Path generation, Supplier<Optional<String>> fingerprint) {
    Supplier<CommitMetadataSource> metadata = EmbeddingMetadataOverlay.createSupplier(fingerprint);
    return io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(
        FieldCatalogDef.forTesting(768), metadata, ignored -> {})
        .atPath(generation).withExecutorRegistrations(testLuceneExecutors()).open();
  }

  private static EmbeddingCompatibilityController realEcc(RunningRuntime runtime) {
    return new EmbeddingCompatibilityController(runtime::latestCommitUserDataBestEffort,
        () -> {
          try { return runtime.indexCountOps().docCountOrThrow(); }
          catch (IOException failure) { throw new UncheckedIOException(failure); }
        }, () -> count(runtime, SchemaFields.EMBEDDING_STATUS_COMPLETED));
  }

  private static int count(RunningRuntime runtime, String status) {
    try { return runtime.indexCountOps().countByFieldOrThrow(SchemaFields.EMBEDDING_STATUS, status); }
    catch (IOException failure) { throw new UncheckedIOException(failure); }
  }
}
