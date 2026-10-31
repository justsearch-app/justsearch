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
@org.junit.jupiter.api.extension.ExtendWith(NoVolumeIdentityExtension.class)
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
        SchemaFields.SIZE_BYTES, Files.size(file),
        SchemaFields.MODIFIED_AT, Files.getLastModifiedTime(file).toMillis(),
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

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"missing", "directory", "link", "dangling"})
  void candidateRetirementPreservesCrossFileChunksInBothGenerations(String replacement) throws Exception {
    Path gone = indexed("candidate-gone.txt");
    Path kept = indexed("candidate-kept.txt");
    var manager = new IndexGenerationManager(base);
    String generation = manager.startMigration("scoped-retirement").building_generation();
    try (var candidate = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTestingWithVduRetryCount(0))
        .atPath(manager.resolveGenerationPathStrict(generation))
        .withExecutorRegistrations(testLuceneExecutors()).open()) {
      for (RunningRuntime selected : List.of(runtime, candidate)) {
        selected.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
            SchemaFields.DOC_ID, gone.toString(), SchemaFields.PATH, gone.toString(),
            SchemaFields.DOC_UID, "old-parent", SchemaFields.CONTENT, "old text")));
        for (String id : List.of("own-chunk", "legacy-chunk", "cross-file-chunk")) {
          var fields = new java.util.HashMap<String, Object>(Map.of(
              SchemaFields.DOC_ID, id, SchemaFields.DOC_UID, id,
              SchemaFields.PARENT_DOC_ID, gone.toString(), SchemaFields.IS_CHUNK, "true",
              SchemaFields.CONTENT, id));
          if (!id.equals("legacy-chunk")) fields.put(SchemaFields.PATH,
              id.equals("cross-file-chunk") ? kept.toString() : gone.toString());
          selected.indexingCoordinator().indexSingle(new IndexDocument(fields));
        }
        selected.commitOps().commitAndTrack(CommitReason.DRAIN);
        selected.commitOps().maybeRefreshBlocking();
      }
      var migrating = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
          base, index, candidate, runtime, null, 0L);
      watch(migrating, root);
      Files.delete(gone);
      if (replacement.equals("directory")) {
        Files.createDirectory(gone);
        Files.writeString(gone.resolve("new.txt"), "new descendant");
      } else if (!replacement.equals("missing")) {
        Files.createSymbolicLink(gone, replacement.equals("link")
            ? Files.writeString(temp.resolve("outside.txt"), "outside text") : temp.resolve("absent"));
      }
      var result = migrating.syncDirectory(SyncDirectoryRequest.newBuilder()
          .setRootPath(root.toString()).build(), CallContext.none());
      assertEquals("", result.getError());
      assertFalse(result.getDeleteDetectionUnverified());
      assertEquals(1, result.getFilesDeleted());
      for (RunningRuntime selected : List.of(runtime, candidate)) {
        selected.commitOps().maybeRefreshBlocking();
        assertEquals("cross-file-chunk", selected.documentFieldOps()
            .getDocumentField("cross-file-chunk", SchemaFields.CONTENT));
        assertNull(selected.documentFieldOps().getDocumentField(gone.toString(), SchemaFields.CONTENT));
        assertNull(selected.documentFieldOps().getDocumentField("own-chunk", SchemaFields.CONTENT));
        assertNull(selected.documentFieldOps().getDocumentField("legacy-chunk", SchemaFields.CONTENT));
      }
      if (replacement.equals("directory")) assertTrue(queue.matchesExpectedCollection(gone.resolve("new.txt"), "docs"));
      assertTrue(queue.listSwitchBufferOpsStrict().stream().anyMatch(op -> op.op().equals("DELETE")));
    }
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

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"missing", "directory", "link", "dangling"})
  void bufferedRetirementPreservesChunksBackedByAnotherFile(String replacement) throws Exception {
    Path gone = indexed("buffered-gone.txt");
    Path kept = indexed("buffered-kept.txt");
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, "cross-file-chunk", SchemaFields.DOC_UID, "cross-file-chunk",
        SchemaFields.PATH, kept.toString(), SchemaFields.PARENT_DOC_ID, gone.toString(),
        SchemaFields.IS_CHUNK, "true", SchemaFields.CONTENT, "live chunk")));
    runtime.commitOps().commitAndTrack(CommitReason.DRAIN);
    receipt(gone, watcherPayload(gone));
    Files.delete(gone);
    if (replacement.equals("directory")) Files.createDirectory(gone);
    else if (!replacement.equals("missing")) Files.createSymbolicLink(gone,
        replacement.equals("link") ? kept : temp.resolve("absent"));
    assertTrue(replay(null));
    assertEquals("live chunk", runtime.documentFieldOps().getDocumentField("cross-file-chunk", SchemaFields.CONTENT));
    assertNull(runtime.documentFieldOps().getDocumentField(gone.toString(), SchemaFields.CONTENT));
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
  @org.junit.jupiter.params.provider.ValueSource(strings = {"ordinary", "promoted"})
  void watcherReceiptRetainsUnavailableRootAcrossEveryReplayRoute(String route) throws Exception {
    Path file = indexed("unavailable-route.txt");
    assertTrue(queue.putSwitchBufferForGeneration("green", "path:" + file, "DELETE", file.toString()));
    var receipt = queue.listSwitchBufferOpsStrict();
    Files.move(root, temp.resolve("original-root"));
    assertFalse(replayRoute(route), "a missing root cannot prove source deletion");
    assertEquals(1, runtime.indexCountOps().countByIdAndChunksStrict(file.toString()));
    assertEquals(receipt, queue.listSwitchBufferOpsStrict());
  }

  /**
   * P1: a root withdrawn during SWITCHING must not wedge a refused candidate. Surviving A keeps
   * the file's rows while the root is uncertain, and A's own root reconciliation removes them
   * once the root is back.
   */
  @org.junit.jupiter.params.ParameterizedTest(name = "refusalLeavesUnprovedDeleteToSurvivingRootReconciliation({0})")
  @org.junit.jupiter.params.provider.ValueSource(strings = {"watcher", "legacy"})
  void refusalLeavesUnprovedDeleteToSurvivingRootReconciliation(String format) throws Exception {
    Path file = indexed("withdrawn-root.txt");
    Path sibling = indexed("withdrawn-root-sibling.txt");
    String payload = "watcher".equals(format) ? watcherPayload(file) : file.toString();
    assertTrue(queue.putSwitchBufferForGeneration("green", "path:" + file, "DELETE", payload));
    Files.delete(file);
    Path away = temp.resolve("withdrawn");
    Files.move(root, away);
    assertTrue(KnowledgeServerMigrationOps.drainRefusedCandidateOnSource(context("green")),
        "an unprovable delete must not keep the refused candidate's Green alive");
    runtime.commitOps().maybeRefreshBlocking();
    assertEquals(1, runtime.indexCountOps().countByIdAndChunksStrict(file.toString()),
        "nothing is deleted while the root is uncertain");
    assertEquals(1, runtime.indexCountOps().countByIdAndChunksStrict(sibling.toString()));
    assertTrue(queue.listSwitchBufferOpsStrict().isEmpty(),
        "the abandoned candidate's journal must not outlive it");
    Files.move(away, root);
    var synced = service.syncDirectory(SyncDirectoryRequest.newBuilder()
        .setRootPath(root.toString()).setForce(false).build(), CallContext.none());
    assertTrue(synced.getError().isBlank(), synced.getError());
    runtime.commitOps().maybeRefreshBlocking();
    assertEquals(0, runtime.indexCountOps().countByIdAndChunksStrict(file.toString()),
        "the surviving generation's root reconciliation must remove the deleted file");
    assertEquals(1, runtime.indexCountOps().countByIdAndChunksStrict(sibling.toString()));
  }

  @Test
  void undeclaredChildVolumeOutageRetainsDocumentsAcrossRestart() throws Exception {
    Path child = Files.createDirectory(root.resolve("child"));
    Path file = indexed("child/document.txt");
    var originalLookup = RootIdentity.VOLUME_IDENTITY_LOOKUP;
    String ancestorVolume = RootIdentity.capture(root).volume();
    try {
      var available = new java.util.concurrent.atomic.AtomicBoolean(true);
      RootIdentity.VOLUME_IDENTITY_LOOKUP = path ->
          path.equals(child) && available.get() ? "test-volume:2" : ancestorVolume;
      RootVolumeLedger.under(base).record(child, "test-volume:2");
      Files.delete(file);
      available.set(false);
      for (int restart = 0; restart < 2; restart++) {
        if (restart > 0) {
          runtime.close();
          runtime = null;
          reopenRuntime();
          watch(service, root);
        }
        var result = service.syncDirectory(SyncDirectoryRequest.newBuilder()
            .setRootPath(root.toString()).build(), CallContext.none());
        assertEquals("", result.getError());
        assertTrue(result.getDeleteDetectionUnverified());
        runtime.commitOps().maybeRefreshBlocking();
        assertEquals(1, runtime.indexCountOps().countByIdAndChunksStrict(file.toString()));
      }
      Files.writeString(file, "original child/document.txt");
      available.set(true);
      var returned = service.syncDirectory(SyncDirectoryRequest.newBuilder()
          .setRootPath(root.toString()).build(), CallContext.none());
      assertEquals("", returned.getError());
      assertFalse(returned.getDeleteDetectionUnverified());
      assertEquals(1, runtime.indexCountOps().countByIdAndChunksStrict(file.toString()));
    } finally {
      RootIdentity.VOLUME_IDENTITY_LOOKUP = originalLookup;
    }
  }

  @Test
  void recreatedRootRecoveryFindsMissedFilesAndRetiresObsoleteText() throws Exception {
    Path obsolete = indexed("obsolete.txt");
    var active = new java.util.concurrent.atomic.AtomicBoolean();
    WorkerMethvinWatcher watcher = org.mockito.Mockito.mock(WorkerMethvinWatcher.class);
    org.mockito.Mockito.when(watcher.registerRoot(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
      active.set(true);
      return true;
    });
    org.mockito.Mockito.when(watcher.activateRoot(org.mockito.ArgumentMatchers.any())).thenReturn(true);
    org.mockito.Mockito.when(watcher.isActive(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenAnswer(call -> active.get());
    var registry = new RootWatcherRegistry(watcher);
    service.setRootWatcherRegistry(registry);
    assertTrue(registry.watch(root.toString(), "docs").watching());
    var old = registry.subscription(root);
    active.set(false);
    Files.move(root, temp.resolve("old-root"));
    Files.createDirectory(root);
    Path missed = Files.writeString(root.resolve("missed.txt"), "missed event");
    service.recoverWatcherRoot(root);
    assertNotSame(old, registry.subscription(root));
    assertNull(registry.enterEvent(old));
    runtime.commitOps().maybeRefreshBlocking();
    assertEquals(0, runtime.indexCountOps().countByIdAndChunksStrict(obsolete.toString()));
    assertTrue(queue.pollPending(10).stream().anyMatch(job ->
        job.path().equals(missed) && "docs".equals(job.collection())));
  }

  @Test
  void failedInitialWatcherRegistrationIsRecoveredByTheWorkerTick() throws Exception {
    var active = new java.util.concurrent.atomic.AtomicBoolean();
    WorkerMethvinWatcher watcher = org.mockito.Mockito.mock(WorkerMethvinWatcher.class);
    org.mockito.Mockito.when(watcher.registerRoot(org.mockito.ArgumentMatchers.any())).thenReturn(false, true);
    org.mockito.Mockito.when(watcher.activateRoot(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
      active.set(true);
      return true;
    });
    org.mockito.Mockito.when(watcher.isActive(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenAnswer(call -> active.get());
    var registry = new RootWatcherRegistry(watcher);
    service.setRootWatcherRegistry(registry);
    assertFalse(registry.watch(root.toString(), "docs").watching());
    assertNull(registry.subscription(root));
    Path missed = Files.writeString(root.resolve("missed.txt"), "during the registration gap");
    service.recoverWatcherRoot(root);
    assertNotNull(registry.subscription(root));
    assertTrue(queue.pollPending(10).stream().anyMatch(job ->
        job.path().equals(missed) && "docs".equals(job.collection())));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"directory", "link", "dangling"})
  void restartReconciliationRetiresNonregularReplacements(String replacement) throws Exception {
    Path file = indexed("replaced.txt");
    Path retained = indexed("retained.txt");
    for (Path source : List.of(file, retained)) {
      String id = "chunk:" + java.util.UUID.randomUUID();
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, id, SchemaFields.DOC_UID, id,
          SchemaFields.PATH, source.toString(), SchemaFields.PARENT_DOC_ID, source.toString(),
          SchemaFields.IS_CHUNK, "true", SchemaFields.CONTENT, "old chunk")));
    }
    runtime.commitOps().commitAndTrack(CommitReason.SWITCH_BUFFER_REPLAY);
    runtime.close();
    runtime = null;
    Files.delete(file);
    if (replacement.equals("directory")) {
      Files.createDirectory(file);
      Files.writeString(file.resolve("new.txt"), "new descendant");
    } else {
      Files.createSymbolicLink(file, replacement.equals("link") ? retained : root.resolve("absent"));
    }
    reopenRuntime();
    watch(service, root);
    var result = service.syncDirectory(SyncDirectoryRequest.newBuilder()
        .setRootPath(root.toString()).build(), CallContext.none());
    assertEquals("", result.getError());
    runtime.commitOps().maybeRefreshBlocking();
    assertEquals(0, runtime.indexCountOps().countByIdAndChunksStrict(file.toString()));
    assertEquals(2, runtime.indexCountOps().countByIdAndChunksStrict(retained.toString()));
    if (replacement.equals("directory")) {
      assertEquals(1, result.getFilesAdded());
      assertTrue(queue.pollPending(10).stream().anyMatch(job -> job.path().equals(file.resolve("new.txt"))));
    }
  }

  @Test void recreationAfterFinalAbsenceProbeKeepsNewerQueuedRevision() throws Exception {
    Path file = indexed("last-probe.txt");
    Files.delete(file);
    // Use the old raw watcher format so the base reaches the actual delete/queue-removal race.
    receipt(file, file.toString());
    var pruner = org.mockito.Mockito.spy(runtime.pruneOps());
    var target = org.mockito.Mockito.spy(runtime);
    org.mockito.Mockito.doReturn(pruner).when(target).pruneOps();
    var newerRevision = new java.util.concurrent.atomic.AtomicReference<String>();
    org.mockito.Mockito.doAnswer(call -> {
      int removed = (int) call.callRealMethod();
      Files.writeString(file, "recreated after final probe");
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(root)), "docs");
      var newer = queue.pollPending(1).getFirst();
      newerRevision.set(newer.unitRevision());
      queue.returnUnfinishedClaims(List.of(newer));
      return removed;
    }).when(pruner).pruneFile(org.mockito.ArgumentMatchers.eq(file.toString()),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
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
    Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Linux"));
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
    Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Linux"));
    Path file = indexed("unreadable-root.txt");
    receipt(file, file.toString());
    var before = queue.listSwitchBufferOpsStrict();
    var permissions = Files.getPosixFilePermissions(root);
    try {
      Files.setPosixFilePermissions(root, java.util.Set.of());
      Assumptions.assumeFalse(Files.isReadable(root), "requires a non-privileged OS user");
      assertFalse(replay(null), "unreadable roots cannot prove absence");
      assertEquals(1, runtime.indexCountOps().countByIdAndChunksStrict(file.toString()));
      assertEquals(before, queue.listSwitchBufferOpsStrict());
    } finally { Files.setPosixFilePermissions(root, permissions); }
  }

  @Test void linuxUnreadableSourceKeepsRowsAndRefreshWork() throws Exception {
    Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Linux"));
    Path file = indexed("unreadable-source.txt");
    receipt(file, file.toString());
    var permissions = Files.getPosixFilePermissions(file);
    try {
      Files.setPosixFilePermissions(file, java.util.Set.of());
      Assumptions.assumeFalse(Files.isReadable(file), "requires a non-privileged OS user");
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
