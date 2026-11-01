/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.SqliteDocumentIdentityStore;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.PathMapping;
import io.justsearch.ipc.PruneRequest;
import io.justsearch.ipc.SyncDirectoryRequest;
import io.justsearch.ipc.UpdatePathsRequest;
import io.justsearch.ipc.UpdateVduResultRequest;
import io.justsearch.ipc.VduUpdateOutcome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
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
import org.junit.jupiter.api.io.TempDir;

/**
 * A file's rows written while a directory sync or a prune runs are never left behind by a reported
 * deletion: primary ingestion and VDU chunk replacement write inside the Worker's file-mutation
 * fence, and the prune applies its deletions inside the same fence. Renames hold it too.
 *
 * <p>The fence is the indexing loop's; the loop here is a stand-in whose fence is a plain lock, so
 * each test can hold a writer at a chosen point and see who waits for it.
 */
@org.junit.jupiter.api.extension.ExtendWith(NoVolumeIdentityExtension.class)
final class PruneWriterFenceTest extends LuceneExecutorTestBase {

  @TempDir Path tempDir;
  private final ReentrantLock fence = new ReentrantLock(true);
  private SqliteJobQueue jobQueue;
  private SqliteDocumentIdentityStore identityStore;
  private IndexGenerationManager.IndexLayout layout;
  private RunningRuntime runtime;
  private Path root;

  @BeforeEach
  void setUp() throws Exception {
    jobQueue = new SqliteJobQueue(tempDir.resolve("jobs.db"));
    jobQueue.open();
    layout = new IndexGenerationManager(tempDir.resolve("indexBase")).initializeOrLoad();
    var fields=new java.util.ArrayList<>(FieldCatalogDef.forChunkTestingWithVduRetryCount(0).fields());
    fields.add(new FieldCatalogDef.FieldDef(SchemaFields.SOURCE_SHA256,"keyword",true,false,List.of(),null,null,false));
    runtime = IndexSchema.fromCatalog(new FieldCatalogDef("prune-vdu-proof",fields))
        .atPath(layout.activeGenerationPath())
        .withExecutorRegistrations(testLuceneExecutors())
        .open();
    runtime.commitOps().stopCommitTimer();
    root = Files.createDirectory(tempDir.resolve("root"));
  }

  @AfterEach
  void tearDown() throws Exception {
    if (fence.isHeldByCurrentThread()) {
      fence.unlock();
    }
    if (runtime != null) {
      runtime.close();
    }
    if (identityStore != null) {
      identityStore.close();
    }
    if (jobQueue != null) {
      jobQueue.close();
    }
  }

  private IndexingLoop loopWithFence() {
    IndexingLoop loop = mock(IndexingLoop.class);
    when(loop.getLastCommitTime()).thenAnswer(ignored -> System.currentTimeMillis());
    when(loop.getCurrentState()).thenReturn("IDLE");
    when(loop.withFileMutationFence(any())).thenAnswer(call -> {
      Supplier<?> mutation = call.getArgument(0);
      fence.lock();
      try {
        return mutation.get();
      } finally {
        fence.unlock();
      }
    });
    return loop;
  }

  /** A service whose direct mutations hold the loop's fence, over {@code ingest}. */
  private WorkerIngestService service(IndexingLoop loop, RunningRuntime ingest) {
    var service = new WorkerIngestService(
        jobQueue, loop, mock(WorkerSignalBus.class), IndexingPacing.unthrottled(),
        layout.basePath(), layout.activeGenerationPath(), ingest, ingest, null, 0L);
    Object owner = new Object();
    service.setMutationAdmission(new WorkerMutationAdmission(owner), owner);
    return service;
  }

  /** The person's add of the root, which records its volume; a prune refuses a root without it. */
  private void addRoot(WorkerIngestService service) {
    assertTrue(service.watchRoot(io.justsearch.ipc.WatchRootRequest.newBuilder()
            .setRootPath(root.toString()).setRootAdded(true).build(), CallContext.none())
        .getWatching(), "precondition: the person adds the root");
  }

  private static String key(Path file) {
    return PathNormalizer.normalizePath(file.toAbsolutePath().toString());
  }

  private void writeParent(String path, String content) {
    Map<String, Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID, path);
    fields.put(SchemaFields.DOC_UID, path + "#uid");
    fields.put(SchemaFields.PATH, path);
    fields.put(SchemaFields.CONTENT, content);
    fields.put(SchemaFields.MIME, "application/pdf");
    fields.put(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_PROCESSING);
    fields.put(SchemaFields.SOURCE_SHA256,"a".repeat(64));
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
  }

  private void writeChunk(String path, int index) {
    String id = "chunk:" + UUID.randomUUID();
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, id,
        SchemaFields.DOC_UID, id + "#uid",
        SchemaFields.PATH, path,
        SchemaFields.CONTENT, "chunk " + index,
        SchemaFields.IS_CHUNK, "true",
        SchemaFields.PARENT_DOC_ID, path)));
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

  private static <T> FutureTask<T> start(String name, Callable<T> work, Thread[] thread) {
    FutureTask<T> task = new FutureTask<>(work);
    thread[0] = new Thread(task, name);
    thread[0].start();
    return task;
  }

  /** Waits until {@code thread} waits for the fence, or {@code task} finished without it. */
  private boolean waitsForFence(Thread thread, FutureTask<?> task) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      if (fence.hasQueuedThread(thread)) {
        return true;
      }
      if (task.isDone()) {
        return false;
      }
      Thread.sleep(5);
    }
    return false;
  }

  private void primaryIngestionDuringThePrune(Callable<Integer> prune, Thread[] pruneThread)
      throws Exception {
    Path file = Files.writeString(root.resolve("report.txt"), "report");
    String path = key(file);
    CountDownLatch parentWritten = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);
    // The indexing loop writes a file's parent and chunks inside the fence.
    Thread[] writerThread = new Thread[1];
    FutureTask<Void> writer = start("held-file-writer", () -> {
      fence.lock();
      try {
        writeParent(path, "report parent");
        writeChunk(path, 0);
        parentWritten.countDown();
        assertTrue(finish.await(10, TimeUnit.SECONDS));
        writeChunk(path, 1);
        writeChunk(path, 2);
      } finally {
        fence.unlock();
      }
      return null;
    }, writerThread);
    assertTrue(parentWritten.await(10, TimeUnit.SECONDS));
    Files.delete(file);

    FutureTask<Integer> pruning = start("prune-under-test", prune, pruneThread);
    boolean waited = waitsForFence(pruneThread[0], pruning);
    finish.countDown();
    writer.get(10, TimeUnit.SECONDS);
    int deleted = pruning.get(10, TimeUnit.SECONDS);

    assertEquals(0, committedRows(path),
        "no row of the file written while the prune ran may survive its reported deletion");
    assertEquals(4, deleted, "the parent and its three chunks are reported removed");
    assertTrue(waited, "the prune applies its deletions only once the file's write is complete");
  }

  @Test
  void aSyncRemovesChunksWrittenWhileItRuns() throws Exception {
    var service = service(loopWithFence(), runtime);
    addRoot(service);
    Thread[] syncThread = new Thread[1];
    primaryIngestionDuringThePrune(() -> {
      var response = service.syncDirectory(SyncDirectoryRequest.newBuilder()
          .setRootPath(root.toString()).build(), CallContext.none());
      assertEquals("", response.getError());
      return response.getFilesDeleted();
    }, syncThread);
  }

  @Test
  void aDirectPruneRemovesChunksWrittenWhileItRuns() throws Exception {
    var service = service(loopWithFence(), runtime);
    addRoot(service);
    Thread[] pruneThread = new Thread[1];
    primaryIngestionDuringThePrune(() -> {
      var response = service.pruneMissing(
          PruneRequest.newBuilder().setPathPrefix(root.toString()).build(), CallContext.none());
      assertEquals("", response.getError());
      return response.getPrunedCount();
    }, pruneThread);
  }

  @Test
  void aSyncRemovesChunksAVduResultWritesWhileItRuns() throws Exception {
    Path file = Files.writeString(root.resolve("scan.pdf"), "scan");
    String path = key(file);
    writeParent(path, "scanned page");
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
    // The VDU result replaces the chunks one by one; it is held after its first new chunk.
    IndexingCoordinator coordinator = spy(runtime.indexingCoordinator());
    CountDownLatch firstChunkWritten = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);
    AtomicInteger chunksWritten = new AtomicInteger();
    doAnswer(call -> {
      Object written = call.callRealMethod();
      IndexDocument document = call.getArgument(0);
      if ("true".equals(document.fields().get(SchemaFields.IS_CHUNK))
          && chunksWritten.incrementAndGet() == 1) {
        firstChunkWritten.countDown();
        assertTrue(finish.await(10, TimeUnit.SECONDS));
      }
      return written;
    }).when(coordinator).indexSingle(any());
    RunningRuntime ingest = mock(RunningRuntime.class);
    when(ingest.indexingCoordinator()).thenReturn(coordinator);
    when(ingest.documentFieldOps()).thenReturn(runtime.documentFieldOps());
    when(ingest.commitOps()).thenReturn(runtime.commitOps());
    when(ingest.pruneOps()).thenReturn(runtime.pruneOps());
    when(ingest.readPathOps()).thenReturn(runtime.readPathOps());
    var service = service(loopWithFence(), ingest);
    addRoot(service);
    String content = "Recognised text with enough words for several chunks. ".repeat(400);
    var base=CallContext.none();var ctx=new CallContext(null,null,null,base.engineContext().withWorkId(UUID.randomUUID()),base.provenance(),base.childLifetime());
    String attempt=UUID.randomUUID().toString();
    assertTrue(service.markVduProcessing(io.justsearch.ipc.MarkVduProcessingRequest.newBuilder().setDocId(path).setAttemptId(attempt).build(),ctx).getSuccess());

    Thread[] vduThread = new Thread[1];
    FutureTask<Boolean> vdu = start("held-vdu-result", () -> service.updateVduResult(
        UpdateVduResultRequest.newBuilder().setDocId(path).setAttemptId(attempt)
            .setSourceSha256("a".repeat(64))
            .setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT)
            .setExtractedContent(content).build(), ctx).getSuccess(), vduThread);
    assertTrue(firstChunkWritten.await(10, TimeUnit.SECONDS));
    Files.delete(file);
    Thread[] syncThread = new Thread[1];
    FutureTask<Integer> sync = start("sync-under-test", () -> {
      var response = service.syncDirectory(SyncDirectoryRequest.newBuilder()
          .setRootPath(root.toString()).build(), CallContext.none());
      assertEquals("", response.getError());
      return response.getFilesDeleted();
    }, syncThread);
    boolean waited = waitsForFence(syncThread[0], sync);
    finish.countDown();
    vdu.get(10, TimeUnit.SECONDS);
    int deleted = sync.get(10, TimeUnit.SECONDS);

    assertTrue(chunksWritten.get() > 1, "precondition: the VDU result wrote several chunks");
    assertEquals(0, committedRows(path),
        "no chunk of a VDU result written while the sync ran may survive the file's removal");
    assertEquals(1 + chunksWritten.get(), deleted, "the parent and every new chunk are removed");
    assertTrue(waited, "the sync applies its deletions only once the VDU result is complete");
  }

  @Test
  void aVduResultThatGetsTheFenceRightAfterTheBatchLeavesNoChunk() throws Exception {
    Path file = Files.writeString(root.resolve("late.pdf"), "late");
    String path = key(file);
    writeParent(path, "scanned page");
    for (int i = 0; i < 3; i++) {
      writeChunk(path, i);
    }
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
    Files.delete(file);
    String content = "Recognised text with enough words for several chunks. ".repeat(400);
    Thread[] syncThread = new Thread[1];
    Thread[] vduThread = new Thread[1];
    AtomicReference<WorkerIngestService> service = new AtomicReference<>();
    AtomicReference<FutureTask<String>> vdu = new AtomicReference<>();
    AtomicBoolean vduWaited = new AtomicBoolean();
    // The VDU result arrives while the prune applies its batch and is next to get the fence; the
    // prune goes on to its commit only once the VDU result is done.
    IndexingLoop loop = loopWithFence();
    doAnswer(call -> {
      Supplier<?> mutation = call.getArgument(0);
      boolean firstBatch = Thread.currentThread() == syncThread[0] && vdu.get() == null;
      fence.lock();
      try {
        if (firstBatch) {
          vdu.set(start("late-vdu-result", () -> {
            var response = service.get().updateVduResult(
                UpdateVduResultRequest.newBuilder().setDocId(path)
                    .setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT)
                    .setExtractedContent(content).build(), CallContext.none());
            return response.getSuccess() + " " + response.getError();
          }, vduThread));
          vduWaited.set(waitsForFence(vduThread[0], vdu.get()));
        }
        return mutation.get();
      } finally {
        fence.unlock();
        if (firstBatch) {
          vdu.get().get(10, TimeUnit.SECONDS);
        }
      }
    }).when(loop).withFileMutationFence(any());
    service.set(service(loop, runtime));
    addRoot(service.get());

    FutureTask<Integer> sync = start("sync-under-test", () -> {
      var response = service.get().syncDirectory(SyncDirectoryRequest.newBuilder()
          .setRootPath(root.toString()).build(), CallContext.none());
      assertEquals("", response.getError());
      return response.getFilesDeleted();
    }, syncThread);
    int deleted = sync.get(20, TimeUnit.SECONDS);

    assertTrue(vduWaited.get(), "precondition: the VDU result waited for the prune's batch");
    assertEquals(4, deleted, "precondition: the file's parent and three chunks were removed");
    assertEquals(0, committedRows(path),
        "no chunk a VDU result writes after the file's removal may survive it; VDU said: "
            + vdu.get().get());
  }

  @Test
  void aRenameWaitsForTheFence() throws Exception {
    Path file = Files.writeString(root.resolve("before.txt"), "before");
    String oldPath = key(file);
    String newPath = key(root.resolve("after.txt"));
    // The filesystem move precedes its notification; test the rename's writer fence.
    Files.move(file, root.resolve("after.txt"));
    writeParent(oldPath, "before parent");
    runtime.commitOps().commitAndTrack();
    var service = service(loopWithFence(), runtime);
    identityStore = new SqliteDocumentIdentityStore(tempDir.resolve("jobs.db"));
    service.setDocumentIdentityStore(identityStore);
    Thread[] renameThread = new Thread[1];
    FutureTask<Integer> rename;
    fence.lock();
    boolean waited;
    try {
      rename = start("rename-under-test", () -> service.updateDocumentPaths(
          UpdatePathsRequest.newBuilder().addMappings(PathMapping.newBuilder()
              .setOldPath(oldPath).setNewPath(newPath)).build(), CallContext.none())
          .getUpdatedCount(), renameThread);
      waited = waitsForFence(renameThread[0], rename);
    } finally {
      fence.unlock();
    }
    assertEquals(1, rename.get(10, TimeUnit.SECONDS));
    assertTrue(waited, "a rename rewrites a file's rows only inside the file-mutation fence");
    assertEquals(1, committedRows(newPath));
  }
}
