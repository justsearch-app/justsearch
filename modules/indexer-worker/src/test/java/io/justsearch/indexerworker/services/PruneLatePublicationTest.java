/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.FenceRace;
import io.justsearch.indexerworker.loop.FileMutationFence;
import io.justsearch.indexerworker.loop.HeldFileWriter;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.SqliteDocumentIdentityStore;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.server.ops.KnowledgeServerMigrationOps;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ipc.PathMapping;
import io.justsearch.ipc.PruneRequest;
import io.justsearch.ipc.SyncDirectoryRequest;
import io.justsearch.ipc.UpdatePathsRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.SoftDeletesDirectoryReaderWrapper;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Task t-20261019-24fb14, RP3 on the normal paths: the indexing loop's real writer checked a file
 * just before the person deleted it, and is held inside the file-mutation fence before it
 * publishes the file's first row. The prune's scan therefore finds no row of the file. Once the
 * writer finishes, the file must have no row left: the prune judges the files published since its
 * scan began, inside the fence, before it ends.
 */
@Timeout(120)
final class PruneLatePublicationTest extends LuceneExecutorTestBase {

  @TempDir Path tempDir;
  private final FileMutationFence fence = new FileMutationFence();
  private SqliteJobQueue jobQueue;
  private SqliteDocumentIdentityStore identities;
  private IndexGenerationManager.IndexLayout layout;
  private RunningRuntime runtime;
  private Path root;
  private IndexingLoop loop;

  @BeforeEach
  void setUp() throws Exception {
    jobQueue = new SqliteJobQueue(tempDir.resolve("jobs.db"));
    jobQueue.open();
    identities = new SqliteDocumentIdentityStore(tempDir.resolve("jobs.db"));
    layout = new IndexGenerationManager(tempDir.resolve("indexBase")).initializeOrLoad();
    runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0))
        .atPath(layout.activeGenerationPath())
        .withExecutorRegistrations(testLuceneExecutors())
        .open();
    runtime.commitOps().stopCommitTimer();
    root = Files.createDirectory(tempDir.resolve("root"));
  }

  @AfterEach
  void tearDown() throws Exception {
    if (runtime != null) runtime.close();
    if (identities != null) identities.close();
    if (jobQueue != null) jobQueue.close();
  }

  /** A service whose loop's fence is {@link #fence}, with the root added by the person. */
  private WorkerIngestService service() {
    return service(runtime);
  }

  /** As {@link #service()}, writing through {@code target}. */
  private WorkerIngestService service(RunningRuntime target) {
    loop = mock(IndexingLoop.class);
    when(loop.getLastCommitTime()).thenAnswer(ignored -> System.currentTimeMillis());
    when(loop.getCurrentState()).thenReturn("IDLE");
    when(loop.withFileMutationFence(any())).thenAnswer(call -> {
      Supplier<?> mutation = call.getArgument(0);
      return fence.guard(mutation);
    });
    when(loop.fileMutationFence()).thenReturn(fence);
    var service = new WorkerIngestService(
        jobQueue, loop, mock(WorkerSignalBus.class), IndexingPacing.unthrottled(),
        layout.basePath(), layout.activeGenerationPath(), target, target, null, 0L);
    Object owner = new Object();
    service.setMutationAdmission(new WorkerMutationAdmission(owner), owner);
    service.setDocumentIdentityStore(identities);
    assertTrue(service.watchRoot(io.justsearch.ipc.WatchRootRequest.newBuilder()
            .setRootPath(root.toString()).setRootAdded(true).build(), CallContext.none())
        .getWatching(), "precondition: the person adds the root");
    return service;
  }

  /** The rows of {@code path} in the index's last commit, read from disk. */
  private int committedRows(String path) throws IOException {
    runtime.commitOps().commitAndTrack();
    try (var directory = FSDirectory.open(layout.activeGenerationPath());
        var reader = new SoftDeletesDirectoryReaderWrapper(
            DirectoryReader.open(directory), SchemaFields.SOFT_DELETE)) {
      return new IndexSearcher(reader).count(new BooleanQuery.Builder()
          .add(new TermQuery(new Term(SchemaFields.PATH, path)), BooleanClause.Occur.SHOULD)
          .add(new TermQuery(new Term(SchemaFields.DOC_ID, path)), BooleanClause.Occur.SHOULD)
          .add(new TermQuery(new Term(SchemaFields.PARENT_DOC_ID, path)), BooleanClause.Occur.SHOULD)
          .build());
    }
  }

  private void firstRowsPublishedAfterTheScan(Callable<String> prune) throws Exception {
    Path file = Files.writeString(root.resolve("new-report.txt"), "report");
    String path = PathNormalizer.normalizeKey(file);
    HeldFileWriter writer = new HeldFileWriter(runtime, fence, 1);
    var writing = FenceRace.start("held-file-writer", () -> {
      writer.write(file, HeldFileWriter.chunkedContent());
      return null;
    });
    assertTrue(writer.awaitHeld(), "precondition: the writer checked the file and is held");
    Files.delete(file);

    var pruning = FenceRace.start("prune-under-test", prune);
    FenceRace.releaseOnceWaitingOrDone(writer::release, pruning);
    writing.get();
    assertEquals("", pruning.get(), "the prune reports no error");

    assertEquals(0, committedRows(path),
        "no row a writer publishes for a file deleted before the prune's scan may outlive it");
  }

  @Test
  void aSyncRemovesAFileWhoseFirstRowsArePublishedAfterItsScan() throws Exception {
    var service = service();
    firstRowsPublishedAfterTheScan(() -> service.syncDirectory(SyncDirectoryRequest.newBuilder()
        .setRootPath(root.toString()).build(), CallContext.none()).getError());
  }

  @Test
  void aDirectPruneRemovesAFileWhoseFirstRowsArePublishedAfterItsScan() throws Exception {
    var service = service();
    firstRowsPublishedAfterTheScan(() -> service.pruneMissing(
        PruneRequest.newBuilder().setPathPrefix(root.toString()).build(), CallContext.none())
        .getError());
  }

  @Test
  void aFileStillPresentKeepsTheRowsItsWriterPublishedDuringThePrune() throws Exception {
    // RP5 / RP1p (preserve): the late pass judges a recorded file like any other; one that is
    // there keeps every row.
    Path file = Files.writeString(root.resolve("live.txt"), "live");
    String path = PathNormalizer.normalizeKey(file);
    var service = service();
    HeldFileWriter writer = new HeldFileWriter(runtime, fence, 1);
    var writing = FenceRace.start("held-file-writer", () -> {
      writer.write(file, HeldFileWriter.chunkedContent());
      return null;
    });
    assertTrue(writer.awaitHeld());
    var pruning = FenceRace.start("prune-under-test", () -> service.pruneMissing(
        PruneRequest.newBuilder().setPathPrefix(root.toString()).build(), CallContext.none())
        .getError());
    FenceRace.releaseOnceWaitingOrDone(writer::release, pruning);
    writing.get();
    assertEquals("", pruning.get());

    assertTrue(committedRows(path) > 1, "the live file keeps its parent and chunks");
  }

  // ------------------------------------------------- RP3: a rename publishes a path's first rows

  private enum Prune { SYNC, DIRECT, REPLAY }

  /**
   * A file outside the root with a real parent and chunks is renamed into the root. The rename is
   * held inside the fence before Lucene moves any row, the destination is deleted, and the prune
   * scans: the destination has no row yet. Once both finish, the destination keeps its rows only
   * if it is still there.
   */
  private void renamedInAfterTheScan(Prune kind, boolean destinationStays) throws Exception {
    renamedInAfterTheScan(kind, destinationStays, false);
  }

  /** As above; with {@code failsAfterMoving}, the move's I/O fails after its rows were written. */
  private void renamedInAfterTheScan(Prune kind, boolean destinationStays,
      boolean failsAfterMoving) throws Exception {
    Path original = Files.writeString(tempDir.resolve("outside.txt"), "original");
    Path destination = root.resolve("moved.txt");
    String from = PathNormalizer.normalizeKey(original);
    String to = PathNormalizer.normalizeKey(destination);
    new HeldFileWriter(runtime, fence, 0).write(original, HeldFileWriter.chunkedContent());
    int rows = committedRows(from);
    assertTrue(rows > 1, "precondition: the renamed file has a parent and chunks");

    IndexingCoordinator coordinator = spy(runtime.indexingCoordinator());
    CountDownLatch renaming = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    doAnswer(call -> {
      renaming.countDown();
      assertTrue(release.await(30, TimeUnit.SECONDS));
      Object moved = call.callRealMethod();
      if (failsAfterMoving) {
        throw new java.io.UncheckedIOException(new IOException("injected after the move"));
      }
      return moved;
    }).when(coordinator).updateDocumentPaths(from, to);
    RunningRuntime target = mock(RunningRuntime.class);
    when(target.indexingCoordinator()).thenReturn(coordinator);
    when(target.documentFieldOps()).thenReturn(runtime.documentFieldOps());
    when(target.commitOps()).thenReturn(runtime.commitOps());
    when(target.readPathOps()).thenReturn(runtime.readPathOps());
    when(target.pruneOps()).thenReturn(runtime.pruneOps());
    var service = service(target);

    Files.move(original, destination);
    var rename = FenceRace.start("held-rename", () -> service.updateDocumentPaths(
        UpdatePathsRequest.newBuilder().addMappings(PathMapping.newBuilder()
            .setOldPath(from).setNewPath(to)).build(), CallContext.none()).getUpdatedCount());
    try {
      assertTrue(renaming.await(30, TimeUnit.SECONDS),
          "precondition: the rename holds the fence before any row moves");
      if (!destinationStays) Files.delete(destination);
      if (kind == Prune.REPLAY) {
        assertTrue(jobQueue.putSwitchBuffer("prune_prefix:" + root, "PRUNE_PREFIX",
            root.toString()));
      }
      var pruning = FenceRace.start("prune-under-test", () -> switch (kind) {
        case SYNC -> service.syncDirectory(SyncDirectoryRequest.newBuilder()
            .setRootPath(root.toString()).build(), CallContext.none()).getError();
        case DIRECT -> service.pruneMissing(PruneRequest.newBuilder()
            .setPathPrefix(root.toString()).build(), CallContext.none()).getError();
        case REPLAY -> {
          KnowledgeServerMigrationOps.drainSwitchBufferBestEffort(
              new KnowledgeServerMigrationOps.DrainSwitchBufferContext(jobQueue, runtime, null,
                  IndexingPacing.unthrottled(), layout.basePath(), layout.activeGenerationPath(),
                  new ObjectMapper(), () -> false, () -> true,
                  LoggerFactory.getLogger(PruneLatePublicationTest.class), Long.MAX_VALUE)
                  .withPublisher(service));
          yield "";
        }
      });
      FenceRace.releaseOnceWaitingOrDone(release::countDown, pruning);
      if (failsAfterMoving) {
        var failure = assertThrows(java.util.concurrent.ExecutionException.class, rename::get);
        assertInstanceOf(WorkerServiceException.class, failure.getCause(),
            "precondition: the rename reports its failure");
      } else {
        assertEquals(1, rename.get(), "the rename moved the file");
      }
      assertEquals("", pruning.get(), "the prune reports no error");
    } finally {
      release.countDown();
    }

    if (destinationStays) {
      assertEquals(rows, committedRows(to), "a renamed file that is there keeps every row");
    } else {
      assertEquals(0, committedRows(to),
          "no row a rename moves to a file deleted before the prune's scan may outlive it");
    }
    if (kind == Prune.REPLAY) {
      assertTrue(jobQueue.listSwitchBufferOpsStrict().isEmpty(), "the replayed prune completed");
    }
  }

  @Test
  void aSyncRemovesAFileWhoseRowsARenameMovesInAfterItsScan() throws Exception {
    renamedInAfterTheScan(Prune.SYNC, false);
  }

  @Test
  void aDirectPruneRemovesAFileWhoseRowsARenameMovesInAfterItsScan() throws Exception {
    renamedInAfterTheScan(Prune.DIRECT, false);
  }

  @Test
  void aReplayedPruneRemovesAFileWhoseRowsARenameMovesInAfterItsScan() throws Exception {
    renamedInAfterTheScan(Prune.REPLAY, false);
  }

  @Test
  void aFileRenamedInDuringAReplayedPruneKeepsItsRows() throws Exception {
    // Preserve: the late pass judges the renamed file like any other; one that is there stays.
    renamedInAfterTheScan(Prune.REPLAY, true);
  }

  @Test
  void aSyncRemovesTheRowsOfARenameWhoseMoveFailsAfterWriting() throws Exception {
    // RP10: rows the move wrote before its I/O failed reach a commit, so they are reported too.
    renamedInAfterTheScan(Prune.SYNC, false, true);
  }

  @Test
  void aReplayedPruneRemovesTheRowsOfARenameWhoseMoveFailsAfterWriting() throws Exception {
    renamedInAfterTheScan(Prune.REPLAY, false, true);
  }

  /**
   * RP3: a rename is admitted while the prune holds the fence for its last pass; the destination
   * is deleted while the rename waits. The rename moves nothing once it holds the fence, because
   * no later pass of that prune would judge the moved rows.
   */
  private void renamedInWhileThePruneHoldsItsLastFence(Prune kind) throws Exception {
    // The file comes from another root the person added, which vouches that it left there.
    Path elsewhere = Files.createDirectory(tempDir.resolve("elsewhere"));
    Path original = Files.writeString(elsewhere.resolve("outside-final.txt"), "original");
    Path destination = root.resolve("late-final.txt");
    String from = PathNormalizer.normalizeKey(original);
    String to = PathNormalizer.normalizeKey(destination);
    new HeldFileWriter(runtime, fence, 0).write(original, HeldFileWriter.chunkedContent());
    assertTrue(committedRows(from) > 1, "precondition: the renamed file has a parent and chunks");
    var service = service();
    assertTrue(service.watchRoot(io.justsearch.ipc.WatchRootRequest.newBuilder()
            .setRootPath(elsewhere.toString()).setRootAdded(true).build(), CallContext.none())
        .getWatching(), "precondition: the person added the file's first root");
    Files.move(original, destination);

    // The prune's first hold of the fence, which on an empty root is its last, stays held.
    java.util.concurrent.atomic.AtomicReference<Thread> pruner =
        new java.util.concurrent.atomic.AtomicReference<>();
    CountDownLatch pruneHolds = new CountDownLatch(1);
    CountDownLatch renameAdmitted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    doAnswer(call -> {
      Supplier<?> mutation = call.getArgument(0);
      boolean pruning = Thread.currentThread() == pruner.get();
      if (!pruning) renameAdmitted.countDown();
      return fence.guard(() -> {
        Object result = mutation.get();
        if (pruning && pruneHolds.getCount() > 0) {
          pruneHolds.countDown();
          try {
            assertTrue(release.await(30, TimeUnit.SECONDS));
          } catch (InterruptedException interrupted) {
            throw new IllegalStateException(interrupted);
          }
        }
        return result;
      });
    }).when(loop).withFileMutationFence(any());
    if (kind == Prune.REPLAY) {
      assertTrue(jobQueue.putSwitchBuffer("prune_prefix:" + root, "PRUNE_PREFIX",
          root.toString()));
    }
    var pruning = FenceRace.start("prune-under-test", () -> {
      pruner.set(Thread.currentThread());
      return switch (kind) {
        case SYNC -> service.syncDirectory(SyncDirectoryRequest.newBuilder()
            .setRootPath(root.toString()).build(), CallContext.none()).getError();
        case DIRECT -> service.pruneMissing(PruneRequest.newBuilder()
            .setPathPrefix(root.toString()).build(), CallContext.none()).getError();
        case REPLAY -> {
          KnowledgeServerMigrationOps.drainSwitchBufferBestEffort(
              new KnowledgeServerMigrationOps.DrainSwitchBufferContext(jobQueue, runtime, null,
                  IndexingPacing.unthrottled(), layout.basePath(), layout.activeGenerationPath(),
                  new ObjectMapper(), () -> false, () -> true,
                  LoggerFactory.getLogger(PruneLatePublicationTest.class), Long.MAX_VALUE)
                  .withPublisher(service));
          yield "";
        }
      };
    });
    try {
      assertTrue(pruneHolds.await(30, TimeUnit.SECONDS),
          "precondition: the prune holds the fence for its last pass");
      var rename = FenceRace.start("late-rename", () -> service.updateDocumentPaths(
          UpdatePathsRequest.newBuilder().addMappings(PathMapping.newBuilder()
              .setOldPath(from).setNewPath(to)).build(), CallContext.none()).getUpdatedCount());
      assertTrue(renameAdmitted.await(30, TimeUnit.SECONDS),
          "precondition: the rename was admitted and waits for the fence");
      Files.delete(destination);
      release.countDown();
      assertEquals("", pruning.get(), "the prune reports no error");
      assertEquals(1, rename.get(), "the rename is settled: the file is gone from both paths");
    } finally {
      release.countDown();
    }

    assertEquals(0, committedRows(to),
        "a rename that resumes after the prune's last pass moves no row to a deleted file");
    assertEquals(0, committedRows(from), "nor leaves the rows of a file gone from both paths");
    if (kind == Prune.REPLAY) {
      assertTrue(jobQueue.listSwitchBufferOpsStrict().isEmpty(), "the replayed prune completed");
    }
  }

  @Test
  void aRenameAdmittedDuringASyncsLastPassMovesNoRowToADeletedFile() throws Exception {
    renamedInWhileThePruneHoldsItsLastFence(Prune.SYNC);
  }

  @Test
  void aRenameAdmittedDuringAPrunesLastPassMovesNoRowToADeletedFile() throws Exception {
    renamedInWhileThePruneHoldsItsLastFence(Prune.DIRECT);
  }

  @Test
  void aRenameAdmittedDuringAReplayedPrunesLastPassMovesNoRowToADeletedFile() throws Exception {
    renamedInWhileThePruneHoldsItsLastFence(Prune.REPLAY);
  }
}
