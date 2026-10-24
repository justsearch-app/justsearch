/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferCapableQueue;
import io.justsearch.indexerworker.queue.SwitchBufferSyncRoot;
import io.justsearch.indexerworker.queue.SwitchBufferUpsert;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.indexerworker.services.WorkerMutationAdmission;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.UpdateVduResultRequest;
import io.justsearch.ipc.VduUpdateOutcome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Task t-20261019-24fb14: a buffered PRUNE_PREFIX or SYNC_ROOT replayed while the live Worker's
 * indexing loop writes. The replay is vouched for by the live Worker's root authority and applied
 * inside its file-mutation fence, so no row a writer publishes for a deleted file outlives the
 * replayed prune. The writer is the loop's real {@code JobBatchWriter}, held inside the fence.
 */
@Timeout(180)
final class PrunePrefixReplayFenceTest extends LuceneExecutorTestBase {
  private static final String GREEN = "green";
  private static final Logger LOG = LoggerFactory.getLogger(PrunePrefixReplayFenceTest.class);

  @TempDir Path tempDir;
  private final FileMutationFence fence = new FileMutationFence();
  private SqliteJobQueue queue;
  private IndexGenerationManager.IndexLayout layout;
  private RunningRuntime runtime;
  private RunningRuntime crashImage;
  private Path root;

  @BeforeEach
  void setUp() throws Exception {
    queue = new SqliteJobQueue(tempDir.resolve("jobs.db"));
    queue.open();
    layout = new IndexGenerationManager(tempDir.resolve("indexBase")).initializeOrLoad();
    runtime = open(layout.activeGenerationPath());
    root = Files.createDirectory(tempDir.resolve("root"));
  }

  @AfterEach
  void tearDown() throws Exception {
    if (crashImage != null) crashImage.close();
    if (runtime != null) runtime.close();
    if (queue != null) queue.close();
  }

  private RunningRuntime open(Path index) {
    var fields=new java.util.ArrayList<>(FieldCatalogDef.forChunkTestingWithVduRetryCount(0).fields());
    fields.add(new FieldCatalogDef.FieldDef(SchemaFields.SOURCE_SHA256,"keyword",true,false,List.of(),null,null,false));
    RunningRuntime opened = IndexSchema.fromCatalog(new FieldCatalogDef("prune-vdu-proof",fields))
        .atPath(index)
        .withExecutorRegistrations(testLuceneExecutors())
        .open();
    opened.commitOps().stopCommitTimer();
    return opened;
  }

  // ---------------------------------------------------------------- fixtures

  private IndexingLoop loop(Runnable afterEachHold) {
    IndexingLoop loop = mock(IndexingLoop.class);
    when(loop.getLastCommitTime()).thenAnswer(ignored -> System.currentTimeMillis());
    when(loop.getCurrentState()).thenReturn("IDLE");
    when(loop.withFileMutationFence(any())).thenAnswer(call -> {
      Supplier<?> mutation = call.getArgument(0);
      Object result = fence.guard(mutation);
      afterEachHold.run();
      return result;
    });
    when(loop.fileMutationFence()).thenReturn(fence);
    return loop;
  }

  /** The live Worker: its loop writes inside {@link #fence}; the person added {@link #root}. */
  private WorkerIngestService publisher(RunningRuntime ingest, Runnable afterEachHold) {
    var service = new WorkerIngestService(
        queue, loop(afterEachHold), mock(WorkerSignalBus.class), IndexingPacing.unthrottled(),
        layout.basePath(), layout.activeGenerationPath(), ingest, ingest, null, 0L);
    Object owner = new Object();
    service.setMutationAdmission(new WorkerMutationAdmission(owner), owner);
    assertTrue(service.watchRoot(io.justsearch.ipc.WatchRootRequest.newBuilder()
            .setRootPath(root.toString()).setRootAdded(true).build(), CallContext.none())
        .getWatching(), "precondition: the person adds the root");
    return service;
  }

  private WorkerIngestService publisher() {
    return publisher(runtime, () -> {});
  }

  /** A post-upgrade or failed-migration drain: best-effort, receipts removed once applied. */
  private KnowledgeServerMigrationOps.DrainSwitchBufferContext bestEffort(
      RunningRuntime target, WorkerIngestService publisher) {
    return new KnowledgeServerMigrationOps.DrainSwitchBufferContext(
        queue, target, null, IndexingPacing.unthrottled(), layout.basePath(),
        layout.activeGenerationPath(), new ObjectMapper(), () -> false, () -> true, LOG)
        .withPublisher(publisher);
  }

  /** The promotion replay of Green's scoped journal, before the generation pointer commits. */
  private KnowledgeServerMigrationOps.DrainSwitchBufferContext promotion(
      RunningRuntime target, WorkerIngestService publisher) {
    return new KnowledgeServerMigrationOps.DrainSwitchBufferContext(
        queue, target, null, IndexingPacing.unthrottled(), layout.basePath(),
        layout.activeGenerationPath(), new ObjectMapper(), () -> false, () -> true, LOG,
        System.nanoTime() + TimeUnit.SECONDS.toNanos(30), GREEN, ignored -> true, Set.of())
        .withPublisher(publisher);
  }

  private void bufferPrune(String generation) {
    String key = "prune_prefix:" + root;
    assertTrue(generation == null
        ? queue.putSwitchBuffer(key, "PRUNE_PREFIX", root.toString())
        : queue.putSwitchBufferForGeneration(generation, key, "PRUNE_PREFIX", root.toString()));
  }

  private static String key(Path file) {
    return PathNormalizer.normalizeKey(file);
  }

  /** A file's parent and {@code chunks} chunks, committed, as an earlier ingestion left them. */
  private void indexed(RunningRuntime target, String path, int chunks) throws IOException {
    Map<String, Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID, path);
    fields.put(SchemaFields.DOC_UID, path + "#uid");
    fields.put(SchemaFields.PATH, path);
    fields.put(SchemaFields.CONTENT, "scanned page");
    fields.put(SchemaFields.SOURCE_SHA256,"a".repeat(64));
    fields.put(SchemaFields.MIME, "application/pdf");
    fields.put(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_PROCESSING);
    target.indexingCoordinator().indexSingle(new IndexDocument(fields));
    for (int i = 0; i < chunks; i++) {
      String id = "chunk:" + UUID.randomUUID();
      target.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, id,
          SchemaFields.DOC_UID, id + "#uid",
          SchemaFields.PATH, path,
          SchemaFields.CONTENT, "chunk " + i,
          SchemaFields.IS_CHUNK, "true",
          SchemaFields.PARENT_DOC_ID, path)));
    }
    target.commitOps().commitAndTrack();
    target.commitOps().maybeRefreshBlocking();
  }

  /** The rows of {@code path} in the last commit of the index at {@code index}, read from disk. */
  private static int committedRows(RunningRuntime target, Path index, String path)
      throws IOException {
    target.commitOps().commitAndTrack();
    try (var directory = FSDirectory.open(index);
        var reader = new SoftDeletesDirectoryReaderWrapper(
            DirectoryReader.open(directory), SchemaFields.SOFT_DELETE)) {
      return new IndexSearcher(reader).count(new BooleanQuery.Builder()
          .add(new TermQuery(new Term(SchemaFields.PATH, path)), BooleanClause.Occur.SHOULD)
          .add(new TermQuery(new Term(SchemaFields.DOC_ID, path)), BooleanClause.Occur.SHOULD)
          .add(new TermQuery(new Term(SchemaFields.PARENT_DOC_ID, path)), BooleanClause.Occur.SHOULD)
          .build());
    }
  }

  private int committedRows(String path) throws IOException {
    return committedRows(runtime, layout.activeGenerationPath(), path);
  }

  private List<SwitchBufferCapableQueue.SwitchBufferOp> buffered() {
    return queue.listSwitchBufferOpsStrict();
  }

  // ------------------------------------------------- RP1, RP3: late publications

  /**
   * The loop's writer checked {@code file} and is held inside the fence before row publication
   * {@code holdBefore}; the person then deletes the file and the replay runs.
   */
  private <T> T replayAgainstAHeldWriter(Path file, int holdBefore,
      java.util.concurrent.Callable<T> replay) throws Exception {
    HeldFileWriter writer = new HeldFileWriter(runtime, fence, holdBefore);
    var writing = FenceRace.start("held-file-writer", () -> {
      writer.write(file, HeldFileWriter.chunkedContent());
      return null;
    });
    assertTrue(writer.awaitHeld(), "precondition: the writer checked the file and is held");
    Files.delete(file);
    var replaying = FenceRace.start("replay-under-test", replay);
    FenceRace.releaseOnceWaitingOrDone(writer::release, replaying);
    writing.get();
    return replaying.get();
  }

  @Test
  void aPromotionReplayRemovesChunksTheLoopWritesWhileItRuns() throws Exception {
    // RP1, promotion: Green's loop is still writing while the replay runs under the final fence.
    Path file = Files.writeString(root.resolve("report.txt"), "report");
    var publisher = publisher();
    bufferPrune(GREEN);

    Optional<KnowledgeServerMigrationOps.StrictReplay> replay = replayAgainstAHeldWriter(file, 2,
        () -> KnowledgeServerMigrationOps.prepareSwitchReplayForPromotion(
            promotion(runtime, publisher)));

    assertTrue(replay.isPresent(), "the replay applies");
    assertEquals(0, committedRows(key(file)),
        "no chunk the loop writes for a file deleted before the replay may outlive the prune");
    // RP10: the receipt stays until the generation pointer commits.
    assertEquals(List.of("PRUNE_PREFIX"), buffered().stream().map(op -> op.op()).toList());
    assertEquals(GREEN, buffered().getFirst().generation());
  }

  @Test
  void aBestEffortReplayRemovesAFileWhoseFirstRowsArePublishedAfterItsScan() throws Exception {
    // RP3 on replay (and RP1 for the post-upgrade and failed-migration drains): the writer is
    // held before the file's first row, so the replayed prune's scan finds none.
    Path file = Files.writeString(root.resolve("new.txt"), "new");
    var publisher = publisher();
    bufferPrune(null);

    replayAgainstAHeldWriter(file, 1, () -> {
      KnowledgeServerMigrationOps.drainSwitchBufferBestEffort(bestEffort(runtime, publisher));
      return null;
    });

    assertEquals(0, committedRows(key(file)),
        "no row the loop publishes for a file deleted before the replay may outlive the prune");
    assertTrue(buffered().isEmpty(), "the applied prune's receipt is removed");
  }

  @Test
  void aBestEffortReplayRemovesChunksAVduResultWritesWhileItRuns() throws Exception {
    // RP1 with a VDU chunk replacement racing the replayed prune.
    Path file = Files.writeString(root.resolve("scan.pdf"), "scan");
    String path = key(file);
    indexed(runtime, path, 0);
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
        assertTrue(finish.await(30, TimeUnit.SECONDS));
      }
      return written;
    }).when(coordinator).indexSingle(any());
    RunningRuntime ingest = mock(RunningRuntime.class);
    when(ingest.indexingCoordinator()).thenReturn(coordinator);
    when(ingest.documentFieldOps()).thenReturn(runtime.documentFieldOps());
    when(ingest.commitOps()).thenReturn(runtime.commitOps());
    when(ingest.pruneOps()).thenReturn(runtime.pruneOps());
    when(ingest.readPathOps()).thenReturn(runtime.readPathOps());
    var publisher = publisher(ingest, () -> {});
    bufferPrune(null);
    String content = "Recognised text with enough words for several chunks. ".repeat(400);
    var base=CallContext.none();
    var ctx=new CallContext(null,null,null,base.engineContext().withWorkId(UUID.randomUUID()),base.provenance(),base.childLifetime());
    String attempt=UUID.randomUUID().toString();
    assertTrue(publisher.markVduProcessing(io.justsearch.ipc.MarkVduProcessingRequest.newBuilder()
        .setDocId(path).setAttemptId(attempt).build(),ctx).getSuccess());

    var vdu = FenceRace.start("held-vdu-result", () -> publisher.updateVduResult(
        UpdateVduResultRequest.newBuilder().setDocId(path).setAttemptId(attempt)
            .setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT)
            .setExtractedContent(content).build(), ctx).getSuccess());
    assertTrue(firstChunkWritten.await(30, TimeUnit.SECONDS));
    Files.delete(file);
    var replaying = FenceRace.start("replay-under-test", () -> {
      KnowledgeServerMigrationOps.drainSwitchBufferBestEffort(bestEffort(runtime, publisher));
      return null;
    });
    FenceRace.releaseOnceWaitingOrDone(finish::countDown, replaying);
    vdu.get();
    replaying.get();

    assertTrue(chunksWritten.get() > 1, "precondition: the VDU result wrote several chunks");
    assertEquals(0, committedRows(path),
        "no chunk of a VDU result written while the replay ran may outlive the file's removal");
    assertTrue(buffered().isEmpty());
  }

  @Test
  void aVduResultAfterThePrunesRemovalOfItsFileWritesNoRow() throws Exception {
    // RP3 (preserve): a VDU result cannot publish a path's first rows. It writes chunks only
    // under a parent it reads inside the fence; once the prune's first batch removed the file,
    // it writes nothing, so the prune's last pass needs no report from it.
    // The scan visits paths in order: this one is in the first batch, the others fill it.
    Path file = Files.writeString(root.resolve("a-scan.pdf"), "scan");
    String path = key(file);
    indexed(runtime, path, 0);
    for (int i = 0; i < 150; i++) {
      indexed(runtime, key(root.resolve("gone-" + i + ".txt")), 0);
    }
    Files.delete(file);
    String content = "Recognised text with enough words for several chunks. ".repeat(400);
    AtomicBoolean firstBatchDone = new AtomicBoolean();
    AtomicInteger vduApplied = new AtomicInteger(-1);
    WorkerIngestService[] service = new WorkerIngestService[1];
    // Right after the prune's first batch leaves the fence, before its last pass.
    service[0] = publisher(runtime, () -> {
      if (firstBatchDone.compareAndSet(false, true)) {
        vduApplied.set(service[0].updateVduResult(UpdateVduResultRequest.newBuilder()
            .setDocId(path).setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT)
            .setExtractedContent(content).build(), CallContext.none()).getSuccess() ? 1 : 0);
      }
    });

    assertEquals("", service[0].pruneMissing(io.justsearch.ipc.PruneRequest.newBuilder()
        .setPathPrefix(root.toString()).build(), CallContext.none()).getError());

    assertEquals(0, vduApplied.get(),
        "precondition: the VDU result ran after the file's removal and found no parent");
    assertEquals(0, committedRows(path),
        "a VDU result for a removed file leaves no row");
  }

  /**
   * RP3 / RP10: a buffered VDU result replayed after an upgrade read its parent and is held at its
   * first chunk; the person deletes the file and a sync or prune of the root runs. The replayed
   * result writes inside the live Worker's fence, as a live one does, so the prune runs wholly
   * after it and no chunk of it outlives the file.
   */
  private void aReplayedVduResultRacing(boolean sync) throws Exception {
    Path file = Files.writeString(root.resolve("buffered-vdu.pdf"), "scan");
    String path = key(file);
    indexed(runtime, path, 0);
    IndexingCoordinator coordinator=spy(runtime.indexingCoordinator());
    var fields=spy(runtime.documentFieldOps());
    CountDownLatch firstChunk=new CountDownLatch(1);
    CountDownLatch finish=new CountDownLatch(1);
    doAnswer(call->{
      Object captured=call.callRealMethod();firstChunk.countDown();
      assertTrue(finish.await(30,TimeUnit.SECONDS));return captured;
    }).when(fields).getEnrichmentInputs(List.of(path));
    RunningRuntime ingest = mock(RunningRuntime.class);
    when(ingest.indexingCoordinator()).thenReturn(coordinator);
    when(ingest.documentFieldOps()).thenReturn(fields);
    when(ingest.commitOps()).thenReturn(runtime.commitOps());
    when(ingest.pruneOps()).thenReturn(runtime.pruneOps());
    when(ingest.readPathOps()).thenReturn(runtime.readPathOps());
    var publisher = publisher(ingest, () -> {});
    assertTrue(queue.putSwitchBuffer("vdu_update:" + path, "VDU_UPDATE",
        vduPayload(path, "Recognised text with enough words for several chunks. ".repeat(400))));

    var replaying = FenceRace.start("buffered-vdu-replay", () -> {
      KnowledgeServerMigrationOps.drainSwitchBufferBestEffort(bestEffort(ingest, publisher));
      return null;
    });
    try {
      assertTrue(firstChunk.await(30, TimeUnit.SECONDS),
          "precondition: legacy replay read its parent under the live file fence");
      Files.delete(file);
      var pruning = FenceRace.start("prune-under-test", () -> sync
          ? publisher.syncDirectory(io.justsearch.ipc.SyncDirectoryRequest.newBuilder()
              .setRootPath(root.toString()).build(), CallContext.none()).getError()
          : publisher.pruneMissing(io.justsearch.ipc.PruneRequest.newBuilder()
              .setPathPrefix(root.toString()).build(), CallContext.none()).getError());
      FenceRace.releaseOnceWaitingOrDone(finish::countDown, pruning);
      replaying.get();
      assertEquals("", pruning.get(), "the prune reports no error");
    } finally {
      finish.countDown();
    }

    org.mockito.Mockito.verify(coordinator,org.mockito.Mockito.never()).indexSingle(any());
    assertEquals(0, committedRows(path),
        "no chunk a replayed VDU result writes may outlive its file's removal");
  }

  @Test
  void aSyncRemovesEveryChunkOfAReplayedVduResultForADeletedFile() throws Exception {
    aReplayedVduResultRacing(true);
  }

  @Test
  void aPruneRemovesEveryChunkOfAReplayedVduResultForADeletedFile() throws Exception {
    aReplayedVduResultRacing(false);
  }

  // ------------------------------------------------------------ RP2: SYNC_ROOT

  private void bufferSync(String generation, boolean force) {
    String key = "sync_root:" + root;
    String payload = new SwitchBufferSyncRoot(root.toString(), force, null).encode();
    assertTrue(generation == null
        ? queue.putSwitchBuffer(key, "SYNC_ROOT", payload)
        : queue.putSwitchBufferForGeneration(generation, key, "SYNC_ROOT", payload));
  }

  @Test
  void aReplayedSyncRemovesChunksTheLoopWritesWhileItRuns() throws Exception {
    Path file = Files.writeString(root.resolve("report.txt"), "report");
    var publisher = publisher();
    bufferSync(null, false);

    replayAgainstAHeldWriter(file, 2, () -> {
      KnowledgeServerMigrationOps.drainSwitchBufferBestEffort(bestEffort(runtime, publisher));
      return null;
    });

    assertEquals(0, committedRows(key(file)),
        "a replayed sync's prune is fenced against the live loop's writer");
    assertTrue(buffered().isEmpty());
  }

  @Test
  void aReplayedForcedSyncInThePromotionRemovesChunksTheLoopWritesWhileItRuns() throws Exception {
    Path file = Files.writeString(root.resolve("report.txt"), "report");
    var publisher = publisher();
    bufferSync(GREEN, true);

    Optional<KnowledgeServerMigrationOps.StrictReplay> replay = replayAgainstAHeldWriter(file, 2,
        () -> KnowledgeServerMigrationOps.prepareSwitchReplayForPromotion(
            promotion(runtime, publisher)));

    assertTrue(replay.isPresent(), "the replay applies");
    assertEquals(0, committedRows(key(file)),
        "a replayed forced sync's prune is fenced against the live loop's writer");
    assertEquals(List.of("SYNC_ROOT"), buffered().stream().map(op -> op.op()).toList());
  }

  // ------------------------------------------------------- RP4: root authority

  @Test
  void aReplayedPruneOfAFolderReplacedAtTheSamePathRemovesNothingAndStaysBuffered()
      throws Exception {
    Path gone = Files.writeString(root.resolve("gone.txt"), "gone");
    String path = key(gone);
    var publisher = publisher();
    indexed(runtime, path, 2);
    bufferPrune(null);
    // The added folder is moved away and a different, readable folder now has its path.
    Files.move(root, tempDir.resolve("root-moved"));
    Files.createDirectory(root);

    KnowledgeServerMigrationOps.drainSwitchBufferBestEffort(bestEffort(runtime, publisher));

    assertEquals(3, committedRows(path),
        "a different folder at the added folder's path vouches for no deletion");
    assertEquals(List.of("PRUNE_PREFIX"), buffered().stream().map(op -> op.op()).toList(),
        "the replay obligation is kept for a later retry");
  }

  @Test
  void aReplayedPruneOfAFolderWithNoRecordedVolumeRemovesNothingAndStaysBuffered()
      throws Exception {
    if (System.getProperty("os.name", "").startsWith("Linux")) {
      org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(Path.of("/dev/disk/by-uuid")),
          "real Linux UUID lookup requires /dev/disk/by-uuid");
    }
    org.junit.jupiter.api.Assumptions.assumeTrue(
        io.justsearch.indexerworker.services.NoVolumeIdentityExtension.volumeIdentityAvailable(root),
        "the real missing-record comparison requires native comparable volume evidence");
    // The boot replay: no live Worker yet. The folder was never added by the person, so no
    // volume is recorded for it, and nothing proves it is the folder the prune was meant for.
    Path gone = Files.writeString(root.resolve("gone.txt"), "gone");
    String path = key(gone);
    indexed(runtime, path, 2);
    bufferPrune(null);
    Files.delete(gone);

    KnowledgeServerMigrationOps.drainSwitchBufferBestEffort(bestEffort(runtime, null));

    assertEquals(3, committedRows(path), "an unproven root vouches for no deletion");
    assertEquals(List.of("PRUNE_PREFIX"), buffered().stream().map(op -> op.op()).toList());
  }

  @Test
  void aReplayedPruneWithoutAVolumeRecordIsRetainedWithInjectedEvidence()
      throws Exception {
    // This branch requires comparable volume evidence, but no saved record. The initial
    // unavailable-identity policy is separately pinned by RootVolumeUnknownIdentityTest.
    try (var ignored = io.justsearch.indexerworker.services.NoVolumeIdentityExtension
        .withVolumeIdentity(true)) {
      assertTrue(io.justsearch.indexerworker.services.NoVolumeIdentityExtension
          .volumeIdentityAvailable(root), "comparable volume precondition is injected");
      // The boot replay: no live Worker yet. The folder was never added by the person, so no
      // volume is recorded for it, and nothing proves it is the folder the prune was meant for.
      Path gone = Files.writeString(root.resolve("gone.txt"), "gone");
      String path = key(gone);
      indexed(runtime, path, 2);
      bufferPrune(null);
      Files.delete(gone);

      KnowledgeServerMigrationOps.drainSwitchBufferBestEffort(bestEffort(runtime, null));

      assertEquals(3, committedRows(path), "an unproven root vouches for no deletion");
      assertEquals(List.of("PRUNE_PREFIX"), buffered().stream().map(op -> op.op()).toList());
    }
  }

  // ------------------------------------------------------- RP8: restart states

  @Test
  void aPromotionStoppedAtThePrunesFirstRemovalLeavesASafeRetryAndExactCleanup()
      throws Exception {
    Path gone = Files.writeString(root.resolve("gone.txt"), "gone");
    Path live = Files.writeString(root.resolve("live.txt"), "live");
    var publisher = publisher();
    indexed(runtime, key(gone), 2);
    indexed(runtime, key(live), 1);
    bufferPrune(GREEN);
    assertTrue(queue.putSwitchBufferForGeneration("other", "delete:foreign", "DELETE",
        key(live)), "precondition: a foreign generation's receipt");
    Files.delete(gone);
    Path image = tempDir.resolve("crash-image");
    AtomicBoolean stopped = new AtomicBoolean();

    // (a) The process stops at the prune's first removal, before anything of it is committed:
    // the index on disk is what a restart finds.
    var stop = assertThrows(IllegalStateException.class,
        () -> KnowledgeServerMigrationOps.prepareSwitchReplayForPromotion(
            promotion(runtime, publisher), () -> {}, () -> {
              copyIndex(layout.activeGenerationPath(), image);
              stopped.set(true);
              throw new IllegalStateException("process halted");
            }));
    assertTrue(stopped.get(), "the harness stops at the replayed prune's first removal");
    assertEquals("process halted", stop.getMessage());
    crashImage = open(image);
    assertEquals(3, committedRows(crashImage, image, key(gone)),
        "nothing of the prune was committed before the stop");
    assertEquals(2, buffered().size(), "every receipt survives the stop");

    // The retry after the restart is safe: the deleted file goes, the live file stays.
    var replay = KnowledgeServerMigrationOps.prepareSwitchReplayForPromotion(
        promotion(crashImage, null));
    assertTrue(replay.isPresent());
    assertEquals(0, committedRows(crashImage, image, key(gone)));
    assertEquals(2, committedRows(crashImage, image, key(live)));

    // (c) After the pointer commit only the exact replayed receipts go; a foreign generation's
    // receipt stays.
    assertTrue(KnowledgeServerMigrationOps.finishPromotedSwitchReplay(queue, replay.get()));
    assertEquals(List.of("other"), buffered().stream().map(op -> op.generation()).toList());
  }

  private static void copyIndex(Path from, Path to) {
    try {
      Files.createDirectories(to);
      try (Stream<Path> files = Files.list(from)) {
        for (Path file : files.toList()) {
          if (!file.getFileName().toString().equals("write.lock")) {
            Files.copy(file, to.resolve(file.getFileName()));
          }
        }
      }
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  @Test
  void aCommittedButUnacknowledgedPruneRetriesWithoutRemovingAReingestedFile() throws Exception {
    // RP8 (b), preserve: the prune committed but its receipt stayed; the person recreated the
    // file and it was indexed again before the retry.
    Path file = Files.writeString(root.resolve("back.txt"), "back");
    String path = key(file);
    var publisher = publisher();
    indexed(runtime, path, 2);
    bufferPrune(GREEN);
    Files.delete(file);
    assertTrue(KnowledgeServerMigrationOps.prepareSwitchReplayForPromotion(
        promotion(runtime, publisher)).isPresent());
    assertEquals(0, committedRows(path), "precondition: the prune committed");
    Files.writeString(file, "back again");
    indexed(runtime, path, 2);

    assertTrue(KnowledgeServerMigrationOps.prepareSwitchReplayForPromotion(
        promotion(runtime, publisher)).isPresent());

    assertEquals(3, committedRows(path), "the retry keeps the re-ingested file");
  }

  @Test
  void nativeCommittedWitnessSettlementRefusesAPruneAndKeepsIt() throws Exception {
    // RP8, preserve: a native pointer-before-publication cut cannot settle a prune receipt.
    bufferPrune(GREEN);

    assertFalse(KnowledgeServerMigrationOps.settleCommittedNativeFileWitnesses(
        queue, runtime, GREEN, List.of(), LOG));

    assertEquals(List.of("PRUNE_PREFIX"), buffered().stream().map(op -> op.op()).toList());
  }

  // ------------------------------------------------ RP5: reappearance, deletes

  @Test
  void aFileRecreatedAfterItsRemovalIsQueuedBeforeTheReplayEnds() throws Exception {
    // RP5: the file comes back after its last absence check, once the prune has committed its
    // removal. Its rows are gone; the replay queues it for indexing again before it acknowledges
    // the prune (the bound), so the person's file is restored by the indexing loop.
    Path file = Files.writeString(root.resolve("again.txt"), "again");
    String path = key(file);
    var publisher = publisher();
    indexed(runtime, path, 2);
    bufferPrune(null);
    Files.delete(file);
    var pruneOps = spy(runtime.pruneOps());
    AtomicBoolean recreated = new AtomicBoolean();
    doAnswer(call -> {
      Object result = call.callRealMethod();
      Files.writeString(file, "again");
      recreated.set(true);
      return result;
    }).when(pruneOps).pruneByPathPrefix(any(), any(), anyInt(), any(), any(), any());
    RunningRuntime target = mock(RunningRuntime.class);
    when(target.indexingCoordinator()).thenReturn(runtime.indexingCoordinator());
    when(target.documentFieldOps()).thenReturn(runtime.documentFieldOps());
    when(target.commitOps()).thenReturn(runtime.commitOps());
    when(target.readPathOps()).thenReturn(runtime.readPathOps());
    when(target.pruneOps()).thenReturn(pruneOps);

    KnowledgeServerMigrationOps.drainSwitchBufferBestEffort(bestEffort(target, publisher));

    assertTrue(recreated.get(), "precondition: the file came back after the prune committed");
    assertEquals(0, committedRows(path), "precondition: its rows went with the prune");
    assertTrue(buffered().isEmpty(), "precondition: the prune was acknowledged");
    assertEquals(List.of(file.toAbsolutePath().normalize()),
        queue.pollPending(10).stream().map(job -> job.path().toAbsolutePath().normalize())
            .toList(),
        "the recreated file is queued for indexing before the prune is acknowledged");
  }

  @Test
  void aBufferedUpsertAfterThePruneQueuesTheReappearedFileAndKeepsItsRows() throws Exception {
    // RP7, preserve: prune then UPSERT for a file that came back.
    Path file = Files.writeString(root.resolve("back.txt"), "back");
    String path = key(file);
    publisher();
    indexed(runtime, path, 1);
    bufferPrune(null);
    assertTrue(queue.putSwitchBuffer("path:" + path, "UPSERT",
        new SwitchBufferUpsert(file.toString(), null, null).encode()));

    KnowledgeServerMigrationOps.drainSwitchBufferBestEffort(bestEffort(runtime, null));

    assertEquals(2, committedRows(path), "a present file keeps its rows");
    assertEquals(List.of(file.toAbsolutePath().normalize()),
        queue.pollPending(10).stream().map(job -> job.path().toAbsolutePath().normalize())
            .toList());
    assertTrue(buffered().isEmpty());
  }

  @Test
  void anExplicitDeleteAfterThePruneKeepsItsSemantics() throws Exception {
    // RP5 / RP7, preserve: an explicit DELETE removes a present file; the prune removes only
    // the deleted one; a live file keeps its rows.
    Path gone = Files.writeString(root.resolve("gone.txt"), "gone");
    Path removed = Files.writeString(root.resolve("removed.txt"), "removed");
    Path live = Files.writeString(root.resolve("live.txt"), "live");
    var publisher = publisher();
    indexed(runtime, key(gone), 1);
    indexed(runtime, key(removed), 1);
    indexed(runtime, key(live), 1);
    bufferPrune(null);
    assertTrue(queue.putSwitchBuffer("path:" + key(removed), "DELETE", new io.justsearch.indexerworker.queue.SwitchBufferDelete(key(removed),
        io.justsearch.indexerworker.queue.SwitchBufferDelete.Origin.EXPLICIT, null).encode()));
    Files.delete(gone);

    KnowledgeServerMigrationOps.drainSwitchBufferBestEffort(bestEffort(runtime, publisher));

    assertEquals(0, committedRows(key(gone)));
    assertEquals(0, committedRows(key(removed)), "the explicit delete removes a present file");
    assertEquals(2, committedRows(key(live)));
    assertTrue(buffered().isEmpty());
  }

  @Test
  void unverifiableVduUpdatesAroundThePruneRetireWithoutReplacingLiveContent() throws Exception {
    // RP7, preserve: a VDU result before the prune applies to its live file; one after the prune
    // for the removed file finds no document, does not bring it back, and the drain keeps every
    // receipt for a later retry.
    Path gone = Files.writeString(root.resolve("gone.pdf"), "gone");
    Path live = Files.writeString(root.resolve("live.pdf"), "live");
    var publisher = publisher();
    indexed(runtime, key(gone), 0);
    indexed(runtime, key(live), 0);
    String content = "Recognised text with enough words for several chunks. ".repeat(400);
    assertTrue(queue.putSwitchBuffer("vdu_update:" + key(live), "VDU_UPDATE",
        vduPayload(key(live), content)));
    bufferPrune(null);
    assertTrue(queue.putSwitchBuffer("vdu_update:" + key(gone), "VDU_UPDATE",
        vduPayload(key(gone), content)));
    Files.delete(gone);

    KnowledgeServerMigrationOps.drainSwitchBufferBestEffort(bestEffort(runtime, publisher));

    assertEquals(1,committedRows(key(live)),"unverifiable legacy results cannot add chunks to the live file");
    assertEquals(0, committedRows(key(gone)), "a later VDU result does not bring a file back");
    assertTrue(buffered().isEmpty(),"obsolete legacy VDU rows do not block exact receipt cleanup");
  }

  private static String vduPayload(String docId, String content) throws Exception {
    Map<String, Object> payload = new java.util.LinkedHashMap<>();
    payload.put("doc_id", docId);
    payload.put("extracted_content", content);
    payload.put("has_extracted_content", true);
    payload.put("vdu_status", "");
    payload.put("vdu_enrichment", "");
    payload.put("page_count", 0);
    payload.put("outcome", VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT.getNumber());
    return new ObjectMapper().writeValueAsString(payload);
  }

  // ------------------------------------------- RP6, RP1p: no deadlock, live files

  @Test
  void aPromotionReplayWithAHeldWriterOfALiveFileCompletesAndKeepsItsRows() throws Exception {
    Path gone = Files.writeString(root.resolve("gone.txt"), "gone");
    Path live = Files.writeString(root.resolve("live.txt"), "live");
    var publisher = publisher();
    indexed(runtime, key(gone), 2);
    bufferPrune(GREEN);
    Files.delete(gone);
    HeldFileWriter writer = new HeldFileWriter(runtime, fence, 2);
    var writing = FenceRace.start("held-file-writer", () -> {
      writer.write(live, HeldFileWriter.chunkedContent());
      return null;
    });
    assertTrue(writer.awaitHeld());

    var replaying = FenceRace.start("replay-under-test",
        () -> KnowledgeServerMigrationOps.prepareSwitchReplayForPromotion(
            promotion(runtime, publisher)));
    FenceRace.releaseOnceWaitingOrDone(writer::release, replaying);
    writing.get();

    assertTrue(replaying.get().isPresent(), "the replay completes once the writer is done");
    assertEquals(0, committedRows(key(gone)));
    assertTrue(committedRows(key(live)) > 2, "the live file keeps its parent and chunks");
  }

  @Test
  void aRefusedCandidateReplayOnTheSourceRemovesTheDeletedFileAndKeepsTheLiveOne()
      throws Exception {
    // RP1p: the refused-source replay runs with the producer paused; its prune is fenced too.
    Path gone = Files.writeString(root.resolve("gone.txt"), "gone");
    Path live = Files.writeString(root.resolve("live.txt"), "live");
    var publisher = publisher();
    indexed(runtime, key(gone), 2);
    indexed(runtime, key(live), 1);
    bufferPrune(GREEN);
    Files.delete(gone);

    assertTrue(KnowledgeServerMigrationOps.drainRefusedCandidateOnSource(
        promotion(runtime, publisher)));

    assertEquals(0, committedRows(key(gone)));
    assertEquals(2, committedRows(key(live)));
    assertTrue(buffered().isEmpty(), "the refused candidate's receipts are settled on the source");
  }
}
