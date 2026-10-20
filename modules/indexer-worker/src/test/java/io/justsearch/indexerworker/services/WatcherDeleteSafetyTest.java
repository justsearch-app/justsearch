/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.*;
import io.justsearch.indexerworker.server.ops.KnowledgeServerMigrationOps;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/** F3/F4 checks use real files, SQLite receipts and committed Lucene parent/chunk rows. */
final class WatcherDeleteSafetyTest extends LuceneExecutorTestBase {
  @TempDir Path temp;
  private Path base;
  private Path index;
  private Path root;
  private SqliteJobQueue queue;
  private RunningRuntime runtime;
  private WorkerIngestService service;

  @BeforeEach void open() throws Exception {
    base = temp.resolve("index");
    index = new IndexGenerationManager(base).initializeOrLoad().activeGenerationPath();
    root = Files.createDirectory(temp.resolve("files"));
    queue = new SqliteJobQueue(temp.resolve("jobs.db"));
    queue.open();
    reopenRuntime();
    watch(service, root);
  }

  @AfterEach void close() throws Exception {
    if (runtime != null) runtime.close();
    if (queue != null) queue.close();
  }

  private void reopenRuntime() throws Exception {
    runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTestingWithVduRetryCount(0))
        .atPath(index).withExecutorRegistrations(testLuceneExecutors()).open();
    service = service(runtime);
  }

  private WorkerIngestService service(RunningRuntime selected) {
    return new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
        base, index, selected, selected, null, 0L);
  }

  private static void watch(WorkerIngestService target, Path root) {
    var watched = target.watchRoot(WatchRootRequest.newBuilder().setRootPath(root.toString())
        .setCollection("docs").setRootAdded(true).build(), CallContext.none());
    assertTrue(watched.getWatching(), watched.getErrorMessage());
  }

  private Path indexed(String name) throws Exception {
    Path file = Files.writeString(root.resolve(name), "original " + name);
    String id = file.toString();
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, id, SchemaFields.PATH, id,
        SchemaFields.DOC_UID, java.util.UUID.randomUUID().toString(),
        SchemaFields.CONTENT, "original " + name, SchemaFields.COLLECTION, "docs")));
    runtime.commitOps().commitAndTrack(CommitReason.SWITCH_BUFFER_REPLAY);
    runtime.commitOps().maybeRefreshBlocking();
    assertEquals(1, runtime.indexCountOps().countByIdAndChunksStrict(id));
    return file;
  }

  private void switching() throws Exception {
    var json = JsonMapper.builder().build();
    var state = json.readTree(Files.readString(base.resolve("state.json")));
    ((tools.jackson.databind.node.ObjectNode) state).put("migration_state", "SWITCHING")
        .put("building_generation", "g-test");
    Files.writeString(base.resolve("state.json"), json.writeValueAsString(state));
  }

  private boolean replay(String generation) {
    boolean applied = KnowledgeServerMigrationOps.drainSwitchBufferStrict(context(generation));
    runtime.commitOps().maybeRefreshBlocking();
    return applied;
  }

  private KnowledgeServerMigrationOps.DrainSwitchBufferContext context(String generation) {
    return new KnowledgeServerMigrationOps.DrainSwitchBufferContext(queue, runtime, null,
            IndexingPacing.unthrottled(), base, index, JsonMapper.builder().build(), () -> false,
            () -> false, LoggerFactory.getLogger(getClass()),
            System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3), generation,
            ignored -> false).withPublisher(service);
  }

  private void receipt(Path file, String payload) {
    assertTrue(queue.putSwitchBuffer("path:" + file, "DELETE", payload));
  }

  private String watcherPayload(Path file) throws Exception {
    var json = JsonMapper.builder().build();
    return json.writeValueAsString(Map.of("version", 1, "path", file.toString(),
        "origin", "WATCHER", "root", root.toString(),
        "root_identity", RootIdentity.capture(root).toString(), "collection", "docs"));
  }

  @Test void recreatedBeforeSwitchingAdmissionPreservesExactUpsertReceipt() throws Exception {
    Path file = indexed("recreated.txt");
    switching();
    assertTrue(queue.enqueueAndBufferFileForGeneration("g-test",
        JobQueue.EnqueueEntry.stat(file).withinRoot(root), "docs"));
    var before = queue.listSwitchBufferOpsStrict();
    service.acceptWatcherDelete(file.toString(), () -> fail("must not delete recreated source"));
    assertEquals(before, queue.listSwitchBufferOpsStrict(),
        "delayed watcher DELETE must not replace the recreated file's exact UPSERT receipt");
    assertEquals(1, runtime.indexCountOps().countByIdAndChunksStrict(file.toString()));
  }

  @Test void legacyDeleteOfRecreatedFilePreservesRowsAndQueuedRevisionAfterReopen() throws Exception {
    Path file = indexed("legacy-recreated.txt");
    Path sibling = indexed("sibling.txt");
    queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(root)), "docs");
    var claimed = queue.pollPending(1).getFirst();
    queue.returnUnfinishedClaims(List.of(claimed));
    receipt(file, file.toString());
    runtime.close();
    queue.close();
    queue = new SqliteJobQueue(temp.resolve("jobs.db"));
    queue.open();
    reopenRuntime();
    watch(service, root);
    assertTrue(replay(null));
    assertEquals(1, runtime.indexCountOps().countByIdAndChunksStrict(file.toString()),
        "originless DELETE cannot remove a recreated file");
    assertEquals(1, runtime.indexCountOps().countByIdAndChunksStrict(sibling.toString()));
    assertEquals(claimed.unitRevision(), queue.pollPending(1).getFirst().unitRevision(),
        "replay must preserve the admitted UPSERT's exact revision");
    assertTrue(queue.listSwitchBufferOpsStrict().isEmpty());
  }

  @Test void watcherReplayDeletesOnlyExactAbsentFileAndRetiresItsReceipt() throws Exception {
    Path file = indexed("removed.txt");
    Path sibling = indexed("removed.txt.sibling");
    receipt(file, watcherPayload(file));
    Files.delete(file);
    assertTrue(replay(null));
    assertEquals(0, runtime.indexCountOps().countByIdAndChunksStrict(file.toString()),
        "versioned watcher receipt must delete the exact absent file");
    assertEquals(1, runtime.indexCountOps().countByIdAndChunksStrict(sibling.toString()));
    runtime.close();
    reopenRuntime();
    assertEquals(0, runtime.indexCountOps().countByIdAndChunksStrict(file.toString()));
    assertTrue(queue.listSwitchBufferOpsStrict().isEmpty());
  }

  @Test void unavailableRootRetainsLegacyRowsAndExactReceipt() throws Exception {
    Path file = indexed("unavailable.txt");
    receipt(file, file.toString());
    var before = queue.listSwitchBufferOpsStrict();
    Files.move(root, temp.resolve("offline"));
    assertFalse(replay(null), "root uncertainty cannot certify replay");
    assertEquals(1, runtime.indexCountOps().countByIdAndChunksStrict(file.toString()));
    assertEquals(before, queue.listSwitchBufferOpsStrict());
  }

  @Test void promotedPointerRecoverySkipsRecreatedLegacyFile() throws Exception {
    Path file = indexed("pointer-recreated.txt");
    assertTrue(queue.putSwitchBufferForGeneration("green", "path:" + file, "DELETE", file.toString()));
    assertTrue(KnowledgeServerMigrationOps.settleCommittedNativeFileWitnesses(queue, runtime,
        "green", List.of(), LoggerFactory.getLogger(getClass())));
    assertEquals(1, runtime.indexCountOps().countByIdAndChunksStrict(file.toString()),
        "pointer recovery must use the same recreated-file protection");
    assertTrue(queue.listSwitchBufferOpsStrict().isEmpty());
  }

  @Test void explicitSwitchingDeleteStillRemovesPresentFile() throws Exception {
    Path file = indexed("explicit.txt");
    switching();
    assertTrue(service.deleteById(DeleteByIdRequest.newBuilder().setDocId(file.toString()).build(),
        CallContext.none()).getSuccess());
    assertTrue(replay("g-test"));
    assertEquals(0, runtime.indexCountOps().countByIdAndChunksStrict(file.toString()));
    assertTrue(Files.exists(file));
  }

  @Test void drainedRuntimeRetainsDeleteUntilSuccessorReconcilesRoot() throws Exception {
    Path file = indexed("during-reload.txt");
    Path sibling = indexed("kept.txt");
    WorkerIngestService retiring = service;
    RunningRuntime retiringRuntime = runtime;
    runtime.drainAndClose(java.time.Duration.ofSeconds(3), SwapReason.CONFIG_RELOAD);
    Files.delete(file);
    retiring.acceptWatcherDelete(file.toString(), () -> {
      if (retiringRuntime.isAcceptingWrites()) {
        retiringRuntime.indexingCoordinator().deleteByIdAndChunks(file.toString());
      }
    });
    assertFalse(queue.listSwitchBufferOpsStrict().isEmpty(),
        "a settled delete needs a durable effect obligation before it is acknowledged");
    reopenRuntime();
    // Use only baseline API for the fail-before run: absence of the repair leaves the row visible.
    try {
      WorkerIngestService.class.getMethod("reconcileReloadedWatcherRootsFrom", WorkerIngestService.class)
          .invoke(service, retiring);
    } catch (NoSuchMethodException beforeRepair) { /* assert the observable missing effect below */ }
    assertEquals(0, runtime.indexCountOps().countByIdAndChunksStrict(file.toString()),
        "the successor must apply settled deletes before retiring their obligation");
    assertEquals(1, runtime.indexCountOps().countByIdAndChunksStrict(sibling.toString()));
    assertTrue(queue.listSwitchBufferOpsStrict().isEmpty());
  }
  private boolean replayRoute(String route) {
    boolean applied = switch (route) {
      case "ordinary" -> KnowledgeServerMigrationOps.drainSwitchBufferStrict(context("green"));
      case "refusal" -> KnowledgeServerMigrationOps.drainRefusedCandidateOnSource(context("green"));
      case "promoted" -> KnowledgeServerMigrationOps.settleCommittedNativeFileWitnesses(queue,
          runtime, "green", List.of(), LoggerFactory.getLogger(getClass()));
      default -> throw new AssertionError(route);
    };
    runtime.commitOps().maybeRefreshBlocking();
    return applied;
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "watcherReceiptPreservesRecreationAcrossEveryReplayRoute({0})")
  @org.junit.jupiter.params.provider.ValueSource(strings = {"ordinary", "refusal", "promoted"})
  void watcherReceiptPreservesRecreationAcrossEveryReplayRoute(String route) throws Exception {
    Path file = indexed("recreated-route.txt");
    Path sibling = indexed("recreated-route.txt.sibling");
    assertTrue(queue.putSwitchBufferForGeneration("green", "path:" + file, "DELETE", file.toString()));
    var pending = JobQueue.EnqueueEntry.stat(file).withinRoot(root);
    assertEquals(1, queue.enqueueEntries(List.of(pending), "docs"));
    var revision = queue.pollPending(1).getFirst();
    queue.returnUnfinishedClaims(List.of(revision));
    assertTrue(replayRoute(route));
    assertEquals(1, runtime.indexCountOps().countByIdAndChunksStrict(file.toString()));
    assertEquals(1, runtime.indexCountOps().countByIdAndChunksStrict(sibling.toString()));
    assertEquals(revision.unitRevision(), queue.pollPending(1).getFirst().unitRevision());
    assertTrue(queue.listSwitchBufferOpsStrict().isEmpty());
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "watcherReceiptRetainsUnavailableRootAcrossEveryReplayRoute({0})")
  @org.junit.jupiter.params.provider.ValueSource(strings = {"ordinary", "refusal", "promoted"})
  void watcherReceiptRetainsUnavailableRootAcrossEveryReplayRoute(String route) throws Exception {
    Path file = indexed("unavailable-route.txt");
    assertTrue(queue.putSwitchBufferForGeneration("green", "path:" + file, "DELETE", file.toString()));
    var receipt = queue.listSwitchBufferOpsStrict();
    Files.move(root, temp.resolve("original-root"));
    assertFalse(replayRoute(route), "a missing root cannot prove source deletion");
    assertEquals(1, runtime.indexCountOps().countByIdAndChunksStrict(file.toString()));
    assertEquals(receipt, queue.listSwitchBufferOpsStrict());
  }

  @Test void recreationAfterFinalAbsenceProbeKeepsNewerQueuedRevision() throws Exception {
    Path file = indexed("last-probe.txt");
    Files.delete(file);
    // Use the old raw watcher format so the base reaches the actual delete/queue-removal race.
    receipt(file, file.toString());
    var coordinator = org.mockito.Mockito.spy(runtime.indexingCoordinator());
    var target = org.mockito.Mockito.spy(runtime);
    org.mockito.Mockito.doReturn(coordinator).when(target).indexingCoordinator();
    var newerRevision = new java.util.concurrent.atomic.AtomicReference<String>();
    org.mockito.Mockito.doAnswer(call -> {
      call.callRealMethod();
      Files.writeString(file, "recreated after final probe");
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(root)), "docs");
      var newer = queue.pollPending(1).getFirst();
      newerRevision.set(newer.unitRevision());
      queue.returnUnfinishedClaims(List.of(newer));
      return null;
    }).when(coordinator).deleteByIdAndChunks(file.toString());
    assertTrue(KnowledgeServerMigrationOps.drainSwitchBufferStrict(
        new KnowledgeServerMigrationOps.DrainSwitchBufferContext(queue, target, null,
            IndexingPacing.unthrottled(), base, index, JsonMapper.builder().build(), () -> false,
            () -> false, LoggerFactory.getLogger(getClass())).withPublisher(service)));
    assertNotNull(newerRevision.get(), "replay must address the exact source file before queue cleanup");
    assertEquals(1, queue.queueDepth(), "stale delete cannot remove the recreated file's queued update");
    assertEquals(newerRevision.get(), queue.pollPending(1).getFirst().unitRevision(),
        "neither queue deletion nor requeue may overwrite the replacement revision");
    assertTrue(queue.listSwitchBufferOpsStrict().isEmpty());
  }

  @Test void linuxCaseDistinctNamesAndAtomicReplacementArePreserved() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Linux"));
    Path upper = indexed("Case.txt");
    Path lower = indexed("case.txt");
    receipt(upper, watcherPayload(upper));
    Path replacement = Files.writeString(root.resolve("incoming.tmp"), "atomic replacement");
    Files.move(replacement, upper, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    assertTrue(replay(null));
    assertEquals(1, runtime.indexCountOps().countByIdAndChunksStrict(upper.toString()));
    assertEquals(1, runtime.indexCountOps().countByIdAndChunksStrict(lower.toString()));
  }

  private void reconcileFrom(WorkerIngestService retiring) throws Exception {
    try {
      WorkerIngestService.class.getMethod("reconcileReloadedWatcherRootsFrom", WorkerIngestService.class)
          .invoke(service, retiring);
    } catch (NoSuchMethodException beforeRepair) { /* observable assertions still run on the base */ }
    catch (java.lang.reflect.InvocationTargetException failure) {
      if (failure.getCause() instanceof RuntimeException runtimeFailure) throw runtimeFailure;
      throw failure;
    }
  }

  @Test void reloadReconciliationKeepsCollectionAndConsecutiveDeletes() throws Exception {
    Path first = indexed("first-reload.txt");
    WorkerIngestService retiring = service;
    runtime.drainAndClose(java.time.Duration.ofSeconds(3), SwapReason.CONFIG_RELOAD);
    Files.delete(first);
    retiring.acceptWatcherDelete(first.toString(), () -> {});
    Path added = Files.writeString(root.resolve("added-during-reload.txt"), "newly discovered file");
    reopenRuntime();
    reconcileFrom(retiring);
    runtime.commitOps().maybeRefreshBlocking();
    assertEquals(0, runtime.indexCountOps().countByIdAndChunksStrict(first.toString()));
    var queued = queue.pollPending(10);
    assertEquals(1, queued.size());
    assertEquals(added, queued.getFirst().path());
    assertEquals("docs", queued.getFirst().collection(), "reload discovery must keep root attribution");
    queue.returnUnfinishedClaims(queued);
    watch(service, root);
    Path second = indexed("second-reload.txt");
    retiring = service;
    runtime.drainAndClose(java.time.Duration.ofSeconds(3), SwapReason.CONFIG_RELOAD);
    Files.delete(second);
    retiring.acceptWatcherDelete(second.toString(), () -> {});
    reopenRuntime();
    reconcileFrom(retiring);
    runtime.commitOps().maybeRefreshBlocking();
    assertEquals(0, runtime.indexCountOps().countByIdAndChunksStrict(second.toString()));
    assertTrue(queue.listSwitchBufferOpsStrict().isEmpty());
  }

  @Test void removedRootNeverRevivesDuringReloadReconciliation() throws Exception {
    Path file = indexed("removed-root.txt");
    WorkerIngestService retiring = service;
    runtime.drainAndClose(java.time.Duration.ofSeconds(3), SwapReason.CONFIG_RELOAD);
    Files.delete(file);
    retiring.acceptWatcherDelete(file.toString(), () -> {});
    assertFalse(queue.listSwitchBufferOpsStrict().isEmpty());
    assertTrue(retiring.unwatchRoot(UnwatchRootRequest.newBuilder().setRootPath(root.toString()).build(),
        CallContext.none()).getUnwatched());
    reopenRuntime();
    reconcileFrom(retiring);
    runtime.commitOps().maybeRefreshBlocking();
    assertEquals(1, runtime.indexCountOps().countByIdAndChunksStrict(file.toString()),
        "removal cancels the queued root reconciliation rather than reviving its watch");
    assertTrue(queue.listSwitchBufferOpsStrict().isEmpty());
  }

  @Test void versionedWatcherReceiptSurvivesReopenWithoutLosingRecreation() throws Exception {
    Path file = indexed("versioned-reopen.txt");
    Files.delete(file);
    receipt(file, watcherPayload(file));
    var snapshot = queue.listSwitchBufferOpsStrict();
    runtime.close(); queue.close();
    Files.writeString(file, "replacement before restart");
    queue = new SqliteJobQueue(temp.resolve("jobs.db")); queue.open();
    reopenRuntime(); watch(service, root);
    assertEquals(snapshot, queue.listSwitchBufferOpsStrict());
    assertTrue(replay(null));
    assertEquals(1, runtime.indexCountOps().countByIdAndChunksStrict(file.toString()));
    assertEquals(1, queue.queueDepth(), "a recreated source needs its refresh retained before receipt cleanup");
    assertEquals("docs", queue.pollPending(1).getFirst().collection());
    assertTrue(queue.listSwitchBufferOpsStrict().isEmpty());
  }

  @Test void linuxUnreadableRootKeepsExactDeleteReceiptAndRows() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Linux"));
    Path file = indexed("unreadable-root.txt");
    receipt(file, file.toString());
    var before = queue.listSwitchBufferOpsStrict();
    var permissions = Files.getPosixFilePermissions(root);
    try {
      Files.setPosixFilePermissions(root, java.util.Set.of());
      org.junit.jupiter.api.Assumptions.assumeFalse(Files.isReadable(root), "requires a non-privileged OS user");
      assertFalse(replay(null), "unreadable roots cannot prove absence");
      assertEquals(1, runtime.indexCountOps().countByIdAndChunksStrict(file.toString()));
      assertEquals(before, queue.listSwitchBufferOpsStrict());
    } finally { Files.setPosixFilePermissions(root, permissions); }
  }

  @Test void linuxUnreadableSourceKeepsRowsAndRefreshWork() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Linux"));
    Path file = indexed("unreadable-source.txt");
    receipt(file, file.toString());
    var permissions = Files.getPosixFilePermissions(file);
    try {
      Files.setPosixFilePermissions(file, java.util.Set.of());
      org.junit.jupiter.api.Assumptions.assumeFalse(Files.isReadable(file), "requires a non-privileged OS user");
      assertTrue(replay(null));
      assertEquals(1, runtime.indexCountOps().countByIdAndChunksStrict(file.toString()),
          "unreadable source bytes are not deletion authority");
      assertEquals(1, queue.queueDepth());
    } finally { Files.setPosixFilePermissions(file, permissions); }
  }

  @Test void watcherReceiptRejectsReboundDirectoryIncarnation() throws Exception {
    Path file = indexed("rebound-directory.txt");
    receipt(file, watcherPayload(file));
    var before = queue.listSwitchBufferOpsStrict();
    Files.move(root, temp.resolve("original-root"));
    Files.createDirectory(root);
    assertFalse(replay(null), "a different directory on the same volume cannot vouch for the original source");
    assertEquals(1, runtime.indexCountOps().countByIdAndChunksStrict(file.toString()));
    assertEquals(before, queue.listSwitchBufferOpsStrict());
  }

}
