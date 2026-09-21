/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.ipc.ScanMode;
import io.justsearch.ipc.ScanRootProgress;
import io.justsearch.ipc.ScanRootRequest;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The Java service boundary admits exact recorded membership into the real jobs database. */
final class WorkerRecordedScanAdmissionTest {
  private static final String KEY = "01994180-0000-7000-8000-000000000911";
  private static final String PLAN = "a".repeat(64);
  @TempDir Path temp;
  private String generation = "fixture-generation";

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void fileAndDirectoryPreserveMembershipAndNeverUseLegacyForce(boolean singleFile) throws Exception {
    Path directory = Files.createDirectory(temp.resolve("source"));
    Path file = Files.writeString(directory.resolve("entry.txt"), "source");
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"), ignored -> JobQueue.RecordedClaimDecision.ALLOW_FORCE)) {
      queue.open();
      long epoch = queue.beginRecordedWalk(KEY, PLAN, true).enumerationEpoch();
      var loop = mock(IndexingLoop.class);
      var service = service(queue, loop);
      List<ScanRootProgress> frames = new ArrayList<>();
      service.scanRecordedRoot(request(singleFile ? file : directory, singleFile, epoch), frames::add, CallContext.none());
      var terminal = frames.getLast();
      assertTrue(terminal.getComplete());
      assertEquals("", terminal.getTerminalReasonCode());
      assertEquals(KEY, terminal.getScanId());
      assertEquals(1, terminal.getFilesAdmitted());
      var claims = queue.pollPending(10);
      assertEquals(1, claims.size());
      var claim = claims.getFirst();
      assertEquals(file, claim.path());
      assertEquals(KEY, claim.scanId());
      assertEquals(epoch, claim.walkEpoch());
      assertEquals("notes", claim.collection());
      assertEquals(CallContext.none().provenance(), claim.provenance());
      assertTrue(claim.recordedForce());
      assertEquals(null, queue.recordedWalk(KEY).orElseThrow().enumerationClosedAt(),
          "only the outer coordinator closes enumeration after actual producer exit");
      verifyNoInteractions(loop);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void staleOrClosedEpochCannotEnterTheQueue(boolean closed) throws Exception {
    Path file = Files.writeString(temp.resolve("entry.txt"), "source");
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"))) {
      queue.open();
      long epoch = queue.beginRecordedWalk(KEY, PLAN, true).enumerationEpoch();
      if (closed) queue.closeRecordedWalkEnumeration(KEY, epoch, JobQueue.WalkEnumerationOutcome.COMPLETE);
      var service = service(queue, mock(IndexingLoop.class));
      List<ScanRootProgress> frames = new ArrayList<>();
      var failure = assertThrows(IllegalStateException.class, () -> service.scanRecordedRoot(
          request(file, true, closed ? epoch : epoch + 1), frames::add, CallContext.none()));
      assertEquals("Recorded enumeration is stale or closed", failure.getMessage());
      assertEquals(0, queue.queueDepth());
      assertFalse(frames.stream().anyMatch(ScanRootProgress::getComplete));
    }
  }

  @Test
  void corruptGenerationRefusesBeforeTraversalAndDoesNotRecreateState() throws Exception {
    Path file = Files.writeString(temp.resolve("entry.txt"), "source");
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"))) {
      queue.open();
      long epoch = queue.beginRecordedWalk(KEY, PLAN, true).enumerationEpoch();
      var service = service(queue, mock(IndexingLoop.class));
      Path state = temp.resolve("index/state.json");
      Files.writeString(state, "broken-generation");
      var failure = assertThrows(WorkerServiceException.class,
          () -> service.scanRecordedRoot(request(file, true, epoch), ignored -> {}, CallContext.none()));
      assertEquals(WorkerServiceException.Status.UNAVAILABLE, failure.status());
      assertEquals(0, queue.queueDepth());
      assertEquals("broken-generation", Files.readString(state));
    }
  }

  @Test
  void differentPersistedGenerationRefusesAnOtherwiseIdleRuntime() throws Exception {
    Path file = Files.writeString(temp.resolve("entry.txt"), "source");
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"))) {
      queue.open();
      long epoch = queue.beginRecordedWalk(KEY, PLAN, true).enumerationEpoch();
      var service = service(queue, mock(IndexingLoop.class));
      generation = "another-generation";
      var failure = assertThrows(WorkerServiceException.class,
          () -> service.scanRecordedRoot(request(file, true, epoch), ignored -> {}, CallContext.none()));
      assertEquals("RECORDED_GENERATION_CHANGED", failure.getMessage());
      assertEquals(0, queue.queueDepth());
    }
  }

  @Test
  void missingCurrentGenerationWithValidPreviousWaitsForPublicationBeforeAdmission() throws Exception {
    Path file = Files.writeString(temp.resolve("entry.txt"), "source");
    try (var queue =
        new SqliteJobQueue(
            temp.resolve("jobs.db"), ignored -> JobQueue.RecordedClaimDecision.ALLOW_FORCE)) {
      queue.open();
      long epoch = queue.beginRecordedWalk(KEY, PLAN, true).enumerationEpoch();
      var service = service(queue, mock(IndexingLoop.class));
      Path state = temp.resolve("index/state.json");
      Path previous = temp.resolve("index/state.json.prev");
      byte[] validState = Files.readAllBytes(state);
      Files.copy(state, previous);
      Files.delete(state);

      List<ScanRootProgress> frames = new CopyOnWriteArrayList<>();
      CountDownLatch started = new CountDownLatch(1);
      ExecutorService executor = Executors.newSingleThreadExecutor();
      try {
        Future<?> scan =
            submitScan(executor, started, service, file, epoch, frames, CallContext.none());
        assertTrue(started.await(1, TimeUnit.SECONDS), "recorded scan must start");
        assertThrows(
            TimeoutException.class,
            () -> scan.get(100, TimeUnit.MILLISECONDS),
            "valid state.json.prev must not authorize traversal while current state is absent");
        assertEquals(0, queue.queueDepth(), "preflight waiting must not admit queue work");
        assertTrue(frames.isEmpty(), "preflight waiting must not emit traversal progress");
        assertFalse(Files.exists(state), "the worker must not restore the missing current state");
        assertArrayEquals(validState, Files.readAllBytes(previous));

        Files.write(state, validState);
        scan.get(3, TimeUnit.SECONDS);

        assertEquals(1L, frames.stream().filter(ScanRootProgress::getComplete).count());
        assertEquals(1, queue.queueDepth());
        var admitted = queue.pollPending(10);
        assertEquals(1, admitted.size(), "a successful retry must admit exactly one member");
        assertEquals(file, admitted.getFirst().path());
      } finally {
        if (!Files.exists(state)) Files.write(state, validState);
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS), "scan executor must terminate");
      }
    }
  }

  @Test
  void heldGenerationLockPastStrictReadTimeoutRetriesBeforeAdmission() throws Exception {
    Path file = Files.writeString(temp.resolve("entry.txt"), "source");
    try (var queue =
        new SqliteJobQueue(
            temp.resolve("jobs.db"), ignored -> JobQueue.RecordedClaimDecision.ALLOW_FORCE)) {
      queue.open();
      long epoch = queue.beginRecordedWalk(KEY, PLAN, true).enumerationEpoch();
      var service = service(queue, mock(IndexingLoop.class));
      Path state = temp.resolve("index/state.json");
      List<ScanRootProgress> frames = new CopyOnWriteArrayList<>();
      CountDownLatch started = new CountDownLatch(1);
      ExecutorService executor = Executors.newSingleThreadExecutor();

      try (FileChannel channel =
          FileChannel.open(state, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
        FileLock lock = channel.lock();
        try {
          Future<?> scan =
              submitScan(executor, started, service, file, epoch, frames, CallContext.none());
          assertTrue(started.await(1, TimeUnit.SECONDS), "recorded scan must start");
          assertThrows(
              TimeoutException.class,
              () -> scan.get(2_200, TimeUnit.MILLISECONDS),
              "the producer must remain in preflight after one bounded lock attempt expires");
          assertEquals(0, queue.queueDepth(), "lock contention must not admit queue work");
          assertTrue(frames.isEmpty(), "lock contention must not emit traversal progress");

          lock.release();
          scan.get(3, TimeUnit.SECONDS);

          assertEquals(1L, frames.stream().filter(ScanRootProgress::getComplete).count());
          var admitted = queue.pollPending(10);
          assertEquals(1, admitted.size(), "release must allow one clean admission");
          assertEquals(file, admitted.getFirst().path());
        } finally {
          if (lock.isValid()) lock.release();
        }
      } finally {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS), "scan executor must terminate");
      }
    }
  }

  @Test
  void cancellationDuringMissingStatePreflightReturnsWithoutRestoringOrAdmitting() throws Exception {
    Path file = Files.writeString(temp.resolve("entry.txt"), "source");
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"))) {
      queue.open();
      long epoch = queue.beginRecordedWalk(KEY, PLAN, true).enumerationEpoch();
      var service = service(queue, mock(IndexingLoop.class));
      Path state = temp.resolve("index/state.json");
      Path previous = temp.resolve("index/state.json.prev");
      byte[] validState = Files.readAllBytes(state);
      Files.copy(state, previous);
      Files.delete(state);

      AtomicBoolean cancelled = new AtomicBoolean();
      CallContext context = cancellableContext(cancelled);
      List<ScanRootProgress> frames = new CopyOnWriteArrayList<>();
      CountDownLatch started = new CountDownLatch(1);
      ExecutorService executor = Executors.newSingleThreadExecutor();
      try {
        Future<?> scan = submitScan(executor, started, service, file, epoch, frames, context);
        assertTrue(started.await(1, TimeUnit.SECONDS), "recorded scan must start");
        assertThrows(
            TimeoutException.class,
            () -> scan.get(100, TimeUnit.MILLISECONDS),
            "missing authoritative state must remain pending until publication or cancellation");
        assertEquals(0, queue.queueDepth());
        assertTrue(frames.isEmpty());

        cancelled.set(true);
        scan.get(1, TimeUnit.SECONDS);

        assertEquals(0, queue.queueDepth(), "cancellation must not admit queue work");
        assertTrue(frames.isEmpty(), "cancellation before traversal must not emit progress");
        assertFalse(Files.exists(state), "preflight cancellation must not restore state.json");
        assertArrayEquals(validState, Files.readAllBytes(previous));
      } finally {
        cancelled.set(true);
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS), "scan executor must terminate");
      }
    }
  }

  @Test
  void cancellationDuringHeldGenerationLockReturnsWithoutAdmissionOrStateRewrite() throws Exception {
    Path file = Files.writeString(temp.resolve("entry.txt"), "source");
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"))) {
      queue.open();
      long epoch = queue.beginRecordedWalk(KEY, PLAN, true).enumerationEpoch();
      var service = service(queue, mock(IndexingLoop.class));
      Path state = temp.resolve("index/state.json");
      byte[] validState = Files.readAllBytes(state);
      AtomicBoolean cancelled = new AtomicBoolean();
      CallContext context = cancellableContext(cancelled);
      List<ScanRootProgress> frames = new CopyOnWriteArrayList<>();
      CountDownLatch started = new CountDownLatch(1);
      ExecutorService executor = Executors.newSingleThreadExecutor();

      try (FileChannel channel =
          FileChannel.open(state, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
        FileLock lock = channel.lock();
        try {
          Future<?> scan = submitScan(executor, started, service, file, epoch, frames, context);
          assertTrue(started.await(1, TimeUnit.SECONDS), "recorded scan must start");
          assertThrows(
              TimeoutException.class,
              () -> scan.get(100, TimeUnit.MILLISECONDS),
              "the scan must still be waiting on the held generation lock");
          assertEquals(0, queue.queueDepth());
          assertTrue(frames.isEmpty());

          cancelled.set(true);
          scan.get(3, TimeUnit.SECONDS);

          assertEquals(0, queue.queueDepth(), "cancellation must not admit queue work");
          assertTrue(frames.isEmpty(), "cancellation before traversal must not emit progress");
        } finally {
          if (lock.isValid()) lock.release();
        }
      } finally {
        cancelled.set(true);
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS), "scan executor must terminate");
      }

      assertArrayEquals(
          validState, Files.readAllBytes(state), "cancelled preflight must preserve state bytes");
      assertFalse(
          Files.exists(temp.resolve("index/state.json.prev")), "preflight must not rotate state");
    }
  }

  @Test
  void nonIdleGenerationRefusesImmediatelyBeforeRecordedTraversal() throws Exception {
    Path file = Files.writeString(temp.resolve("entry.txt"), "source");
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"))) {
      queue.open();
      long epoch = queue.beginRecordedWalk(KEY, PLAN, true).enumerationEpoch();
      var service = service(queue, mock(IndexingLoop.class));
      new IndexGenerationManager(temp.resolve("index"))
          .updateMigrationState(IndexGenerationManager.MigrationState.MIGRATING);
      List<ScanRootProgress> frames = new ArrayList<>();

      var failure = assertThrows(
          WorkerServiceException.class,
          () -> service.scanRecordedRoot(request(file, true, epoch), frames::add, CallContext.none()));

      assertEquals(WorkerServiceException.Status.UNAVAILABLE, failure.status());
      assertEquals(
          "Recorded ingestion requires the current idle serving generation", failure.getMessage());
      assertEquals(0, queue.queueDepth());
      assertTrue(frames.isEmpty(), "non-IDLE state must refuse before traversal");
    }
  }

  private Future<?> submitScan(
      ExecutorService executor,
      CountDownLatch started,
      WorkerIngestService service,
      Path file,
      long epoch,
      List<ScanRootProgress> frames,
      CallContext context) {
    return executor.submit(
        () -> {
          started.countDown();
          service.scanRecordedRoot(request(file, true, epoch), frames::add, context);
        });
  }

  private static CallContext cancellableContext(AtomicBoolean cancelled) {
    CallContext none = CallContext.none();
    return new CallContext(
        null, null, cancelled::get, none.engineContext(), none.provenance(), none.childLifetime());
  }

  @ParameterizedTest
  @ValueSource(ints = {2001, 4000})
  void generationSwitchAfterFirstBatchRefusesFinalOrFullNextBatch(int fileCount) throws Exception {
    Path root = Files.createDirectory(temp.resolve("source"));
    for (int i = 0; i < fileCount; i++) Files.writeString(root.resolve("entry-" + i + ".txt"), "source");
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"))) {
      queue.open();
      long epoch = queue.beginRecordedWalk(KEY, PLAN, true).enumerationEpoch();
      var service = service(queue, mock(IndexingLoop.class));
      List<ScanRootProgress> frames = new ArrayList<>();
    var switched = new AtomicBoolean();
      var failure = assertThrows(WorkerServiceException.class, () -> service.scanRecordedRoot(
          request(root, false, epoch), frame -> {
            frames.add(frame);
            if (frame.getFilesAdmitted() == 2000 && switched.compareAndSet(false, true)) {
              try {
                new IndexGenerationManager(temp.resolve("index"))
                    .updateMigrationState(IndexGenerationManager.MigrationState.SWITCHING);
              } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
              }
            }
          }, CallContext.none()));
      assertEquals(WorkerServiceException.Status.UNAVAILABLE, failure.status());
      assertTrue(switched.get());
      assertEquals(2000, queue.queueDepth());
      assertFalse(frames.stream().anyMatch(ScanRootProgress::getComplete));
    }
  }

  @Test
  void servicePropagatesFrozenGlobsToRealQueueAdmission() throws Exception {
    Path root = Files.createDirectory(temp.resolve("source"));
    Files.writeString(root.resolve("excluded.txt"), "excluded");
    Path included = Files.writeString(root.resolve("included.md"), "included");
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"), ignored -> JobQueue.RecordedClaimDecision.ALLOW)) {
      queue.open();
      long epoch = queue.beginRecordedWalk(KEY, PLAN, true).enumerationEpoch();
      var service = service(queue, mock(IndexingLoop.class));
      var accepted = request(root, false, epoch);
      var recorded = new WorkerIngestService.RecordedRootScan(
          accepted.request().toBuilder().addExcludeGlobs("*.txt").build(),
          KEY, epoch, generation, false, List.of());
      service.scanRecordedRoot(recorded, ignored -> {}, CallContext.none());
      var claims = queue.pollPending(10);
      assertEquals(List.of(included), claims.stream().map(JobQueue.IndexJob::path).toList());
    }
  }

  @Test
  void changedRootKindFailsWithoutFabricatingEmptySuccess() throws Exception {
    Path directory = Files.createDirectory(temp.resolve("changed"));
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"))) {
      queue.open();
      long epoch = queue.beginRecordedWalk(KEY, PLAN, true).enumerationEpoch();
      List<ScanRootProgress> frames = new ArrayList<>();
      service(queue, mock(IndexingLoop.class)).scanRecordedRoot(
          request(directory, true, epoch), frames::add, CallContext.none());
      assertEquals("ROOT_NOT_FILE", frames.getLast().getTerminalReasonCode());
      assertEquals(0, queue.queueDepth());
    }
  }

  @Test
  void cancellationBeforeSingleFileTraversalAdmitsNothing() throws Exception {
    Path file = Files.writeString(temp.resolve("entry.txt"), "source");
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"))) {
      queue.open();
      long epoch = queue.beginRecordedWalk(KEY, PLAN, true).enumerationEpoch();
      var service = service(queue, mock(IndexingLoop.class));
      var none = CallContext.none();
      var cancelled = new CallContext(null, null, new CallContext.CancelSignal() {
        @Override public boolean isCancelled() { return true; }
        @Override public void onCancel(Runnable callback) { callback.run(); }
      }, none.engineContext(), none.provenance(), none.childLifetime());
      List<ScanRootProgress> frames = new ArrayList<>();
      var failure = assertThrows(WorkerServiceException.class,
          () -> service.scanRecordedRoot(request(file, true, epoch), frames::add, cancelled));
      assertEquals(WorkerServiceException.Status.CANCELLED, failure.status());
      assertEquals(0, queue.queueDepth());
      assertTrue(frames.isEmpty());
    }
  }

  @Test
  void requestRejectsMissingMembershipAndEscapingOrNoncanonicalPaths() {
    Path root = temp.resolve("root");
    var proto = ScanRootRequest.newBuilder().setRootPath(root.toString()).build();
    assertThrows(IllegalArgumentException.class, () -> new WorkerIngestService.RecordedRootScan(proto, "", 1, generation, false, List.of()));
    assertThrows(IllegalArgumentException.class, () -> new WorkerIngestService.RecordedRootScan(proto, KEY, 0, generation, false, List.of()));
    assertThrows(IllegalArgumentException.class, () -> new WorkerIngestService.RecordedRootScan(proto, KEY, 1, "", false, List.of()));
    assertThrows(IllegalArgumentException.class, () -> new WorkerIngestService.RecordedRootScan(proto, KEY, 1, "x".repeat(129), false, List.of()));
    assertThrows(IllegalArgumentException.class, () -> new WorkerIngestService.RecordedRootScan(proto, KEY, 1, generation, false, List.of(temp)));
    assertThrows(IllegalArgumentException.class, () -> new WorkerIngestService.RecordedRootScan(
        proto.toBuilder().setRootPath(root.resolve("../other").toString()).build(), KEY, 1, generation, false, List.of()));
  }

  private WorkerIngestService service(JobQueue queue, IndexingLoop loop) throws Exception {
    Path base = temp.resolve("index");
    var layout = new IndexGenerationManager(base).initializeOrLoad();
    generation = layout.activeGenerationId();
    var runtime = mock(RunningRuntime.class);
    return new WorkerIngestService(queue, loop, null, IndexingPacing.unthrottled(), base,
        layout.activeGenerationPath(), runtime, runtime, null, 0L);
  }

  private WorkerIngestService.RecordedRootScan request(Path root, boolean singleFile, long epoch) {
    return new WorkerIngestService.RecordedRootScan(ScanRootRequest.newBuilder()
        .setRootPath(root.toString()).setCollection("notes").setMode(ScanMode.SCAN_MODE_FORCE_REINDEX).build(),
        KEY, epoch, generation, singleFile, List.of());
  }
}
