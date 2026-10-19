/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
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
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ipc.PruneRequest;
import io.justsearch.ipc.SyncDirectoryRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
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
  private IndexGenerationManager.IndexLayout layout;
  private RunningRuntime runtime;
  private Path root;

  @BeforeEach
  void setUp() throws Exception {
    jobQueue = new SqliteJobQueue(tempDir.resolve("jobs.db"));
    jobQueue.open();
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
    if (jobQueue != null) jobQueue.close();
  }

  /** A service whose loop's fence is {@link #fence}, with the root added by the person. */
  private WorkerIngestService service() {
    IndexingLoop loop = mock(IndexingLoop.class);
    when(loop.getLastCommitTime()).thenAnswer(ignored -> System.currentTimeMillis());
    when(loop.getCurrentState()).thenReturn("IDLE");
    when(loop.withFileMutationFence(any())).thenAnswer(call -> {
      Supplier<?> mutation = call.getArgument(0);
      return fence.guard(mutation);
    });
    when(loop.fileMutationFence()).thenReturn(fence);
    var service = new WorkerIngestService(
        jobQueue, loop, mock(WorkerSignalBus.class), IndexingPacing.unthrottled(),
        layout.basePath(), layout.activeGenerationPath(), runtime, runtime, null, 0L);
    Object owner = new Object();
    service.setMutationAdmission(new WorkerMutationAdmission(owner), owner);
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
}
