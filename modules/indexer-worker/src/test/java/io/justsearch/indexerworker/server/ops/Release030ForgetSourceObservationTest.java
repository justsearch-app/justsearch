/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.excludes.LiteralExclusion;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.loop.SourceContentHash;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexerworker.loop.SourcePresence;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.server.Release030Fixture;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import tools.jackson.databind.json.JsonMapper;

/** Real SQLite/Lucene retirement reconciliation; content reads are observed at the hash boundary. */
final class Release030ForgetSourceObservationTest extends LuceneExecutorTestBase {
  @TempDir Path directory;

  @Test void retirementSourceObservationNeverHashesAForgottenFile() throws Exception {
    exercise(true, false, false, false, false);
  }

  @Test void excludedAbsentPathIsReconciledWithoutReadmission() throws Exception {
    exercise(true, false, false, true, false);
  }

  @Test void excludedIndexedPathRemainsPendingWithoutReadOrDeletion() throws Exception {
    exercise(true, true, false, true, false);
  }

  @Test void ordinaryNonExcludedPathStillReadsAndReconciles() throws Exception {
    exercise(false, true, false, true, false);
  }

  @Test void unreadablePolicyKeepsIndexedContentAndAdmissionPending() throws Exception {
    exercise(false, true, true, true, false);
  }

  @Test void malformedPolicyCannotBecomePermissionToRead() throws Exception {
    exercise(false, false, true, true, false);
  }

  @Test void suppliedAncestorGlobAlsoSettlesExcludedAbsenceWithoutReading() throws Exception {
    exercise(true, false, false, true, true);
  }

  @Test void cleanupReplayRejectsOutsideAncestorBeforeObservationOrHashing() throws Exception {
    Path root = Files.createDirectory(directory.resolve("root"));
    Path parent = Files.createDirectory(root.resolve("parent"));
    Path file = Files.writeString(parent.resolve("accepted.txt"), "accepted bytes");
    var publisher = spy(new WorkerIngestService(null, null, null, IndexingPacing.unthrottled(),
        directory.resolve("base"), null, null, null, null, 0L));
    var authority = mock(SourcePresence.RootCheck.class, CALLS_REAL_METHODS);
    when(authority.vouches(root)).thenReturn(true);
    doReturn(authority).when(publisher).rootCheck();
    assertEquals(KnowledgeServerMigrationOps.CleanupPathKind.REGULAR,
        KnowledgeServerMigrationOps.observeForCleanup(publisher, file, root).kind());
    Path outside = Files.createDirectory(directory.resolve("outside"));
    Files.writeString(outside.resolve("accepted.txt"), "outside bytes");
    Files.delete(file);
    Files.delete(parent);
    Files.createSymbolicLink(parent, outside);
    try (var hashes = mockStatic(SourceContentHash.class, CALLS_REAL_METHODS)) {
      assertEquals(KnowledgeServerMigrationOps.CleanupPathKind.UNCERTAIN,
          KnowledgeServerMigrationOps.observeForCleanup(publisher, file, root).kind());
      hashes.verifyNoInteractions();
      Files.delete(outside.resolve("accepted.txt"));
      assertEquals(KnowledgeServerMigrationOps.CleanupPathKind.UNCERTAIN,
          KnowledgeServerMigrationOps.observeForCleanup(publisher, file, root).kind());
      hashes.verifyNoInteractions();
    }
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @org.junit.jupiter.params.provider.ValueSource(strings = {"directory", "link", "dangling"})
  void releaseRetirementCorrectionPreservesForeignBackedChunks(String replacement) throws Exception {
    replacementCleanup(replacement, true);
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @org.junit.jupiter.params.provider.ValueSource(strings = {"directory", "regular"})
  void releaseRefusalCleanupDefersUnavailableChildStorageBeforeClassification(String replacement) throws Exception {
    replacementCleanup(replacement, false);
  }

  private void replacementCleanup(String replacement, boolean storageAvailable) throws Exception {
    Release030Fixture.unpack("switching", directory);
    Path root = directory.resolve("roots/alpha");
    Path file = root.resolve("file-01.txt");
    Path live = Files.writeString(root.resolve("live.txt"), "live bytes");
    String key = PathNormalizer.normalizeKey(file);
    String hash = SourceContentHash.sha256(file);
    var json = JsonMapper.builder().build();
    var state = json.readTree(Files.readString(directory.resolve("data/index/default/state.json")));
    String active = state.path("active_generation").asString();
    String building = state.path("building_generation").asString();
    var previous = ConfigStore.globalOrNull();
    var installed = new ConfigStore(ResolvedConfig.builder()
        .putDefault("justsearch.ui.exclude_patterns", "[]").build());
    ConfigStore.setGlobal(installed);
    try (var queue = new SqliteJobQueue(directory.resolve("data/jobs.db"));
        var runtime = IndexSchema.fromCatalog(retirementCatalog()).atPath(directory.resolve("replacement-index"))
            .withExecutorRegistrations(testLuceneExecutors()).open()) {
      queue.open();
      var roots = List.of(root, directory.resolve("roots/beta"));
      queue.release030RecoveryState().prepared(roots, queue.prepareRelease030Recovery(roots, p -> false));
      queue.prepareRelease030ExplicitFiles();
      queue.release030RecoveryState().beginRetirement(active, building, Map.of(file, "default"));
      queue.inventoryRetirementRoots();
      queue.retainRelease030Retirement(building, List.of(), Map.of(file, "default"));
      queue.prepareRetirementClaims();
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, key, SchemaFields.DOC_UID, "original", SchemaFields.PATH, key,
          SchemaFields.COLLECTION, "default", SchemaFields.CONTENT, "retained text", SchemaFields.SOURCE_SHA256, hash)));
      for (String id : List.of("own-chunk", "legacy-chunk", "foreign-chunk")) {
        var fields = new java.util.HashMap<String, Object>(Map.of(
            SchemaFields.DOC_ID, id, SchemaFields.DOC_UID, id, SchemaFields.PARENT_DOC_ID, key,
            SchemaFields.IS_CHUNK, "true", SchemaFields.CONTENT, id, SchemaFields.COLLECTION, "default"));
        if (!id.equals("legacy-chunk")) fields.put(SchemaFields.PATH,
            id.equals("foreign-chunk") ? live.toString() : key);
        runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
      }
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      var fence = new io.justsearch.indexerworker.loop.FileMutationFence();
      var loop = mock(io.justsearch.indexerworker.loop.IndexingLoop.class);
      when(loop.withFileMutationFence(any())).thenAnswer(call -> fence.guard(call.getArgument(0)));
      var publisher = spy(new WorkerIngestService(null, loop, null, IndexingPacing.unthrottled(),
          directory.resolve("base"), null, runtime, runtime, null, 0L));
      publisher.setMutationAdmission(new io.justsearch.indexerworker.services.WorkerMutationAdmission(runtime), runtime);
      var authority = mock(SourcePresence.RootCheck.class, CALLS_REAL_METHODS);
      when(authority.vouches(root)).thenReturn(true);
      when(authority.vouchesForFile(root)).thenReturn(true);
      when(authority.sourceStorageAvailable(eq(file), any())).thenReturn(storageAvailable);
      doReturn(authority).when(publisher).rootCheck();
      Files.delete(file);
      if (replacement.equals("directory")) Files.createDirectory(file);
      else if (replacement.equals("regular")) Files.writeString(file, "underlying unrelated bytes");
      else Files.createSymbolicLink(file, replacement.equals("link") ? live : root.resolve("missing"));
      var context = new KnowledgeServerMigrationOps.DrainSwitchBufferContext(
          queue, runtime, null, IndexingPacing.unthrottled(), directory.resolve("base"),
          directory.resolve("active"), json, () -> false, () -> true, mock(Logger.class),
          Long.MAX_VALUE, building).withPublisher(publisher);
      var row = queue.watcherReconciliationRow(key);
      try (var hashes = mockStatic(SourceContentHash.class, CALLS_REAL_METHODS)) {
        assertFalse(KnowledgeServerMigrationOps.reconcileRelease030Path(context, file, root, "default"));
        runtime.commitOps().commitAndTrack();
        runtime.commitOps().maybeRefreshBlocking();
        assertNotNull(runtime.documentFieldOps().getDocumentFieldOrThrow("foreign-chunk", SchemaFields.CONTENT),
            "retirement must preserve the chunk explicitly backed by another live file");
        assertEquals(storageAvailable ? 0 : 3, runtime.indexCountOps().countOwnedFileStrict(key));
        assertEquals(storageAvailable,
            KnowledgeServerMigrationOps.reconcileRelease030Path(context, file, root, "default"),
            "owned-file absence settles even while a foreign chunk keeps the parent ID");
        hashes.verifyNoInteractions();
      }
      if (!storageAvailable) {
        assertEquals(row, queue.watcherReconciliationRow(key), "uncertain storage cannot replace an admission");
        assertEquals("retained text", runtime.documentFieldOps().getDocumentFieldOrThrow(key, SchemaFields.CONTENT));
      } else {
        verify(publisher).underFileMutationFence(any());
        verify(loop).withFileMutationFence(any());
      }
    } finally { ConfigStore.restoreGlobal(installed, previous); }
  }

  private static FieldCatalogDef retirementCatalog() {
    var fields = new java.util.ArrayList<>(FieldCatalogDef.forChunkTesting(0).fields());
    fields.add(new FieldCatalogDef.FieldDef(SchemaFields.SOURCE_SHA256,
        "keyword", true, false, List.of(), null, null, false));
    return new FieldCatalogDef("retirement-source-observation", fields);
  }

  private void exercise(boolean excluded, boolean indexed, boolean unknown, boolean reconcile, boolean glob)
      throws Exception {
    Release030Fixture.unpack("switching", directory);
    Path root = directory.resolve("roots/alpha");
    Path file = root.resolve("file-01.txt");
    String key = PathNormalizer.normalizeKey(file);
    String hash = SourceContentHash.sha256(file);
    var json = JsonMapper.builder().build();
    var state = json.readTree(Files.readString(directory.resolve("data/index/default/state.json")));
    String active = state.path("active_generation").asString();
    String building = state.path("building_generation").asString();
    String policy = unknown && !indexed ? "{unreadable" : json.writeValueAsString(excluded
        ? glob ? List.of("alpha/file-01.txt") : List.of(LiteralExclusion.create(file, LiteralExclusion.Scope.FILE)) : List.of());
    var previous = ConfigStore.globalOrNull();
    var installed = new ConfigStore(ResolvedConfig.builder()
        .putDefault("justsearch.ui.exclude_patterns", policy).build());
    ConfigStore.setGlobal(installed);
    try (var queue = new SqliteJobQueue(directory.resolve("data/jobs.db"));
        var runtime = IndexSchema.fromCatalog(retirementCatalog())
            .atPath(directory.resolve("observation-index"))
            .withExecutorRegistrations(testLuceneExecutors()).open()) {
      queue.open();
      var roots = List.of(root, directory.resolve("roots/beta"));
      queue.release030RecoveryState().prepared(roots, queue.prepareRelease030Recovery(roots, p -> false));
      queue.prepareRelease030ExplicitFiles();
      queue.release030RecoveryState().beginRetirement(active, building, Map.of(file, "default"));
      queue.inventoryRetirementRoots();
      queue.retainRelease030Retirement(building, List.of(), Map.of(file, "default"));
      queue.prepareRetirementClaims();
      if (indexed) {
        runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
            SchemaFields.DOC_ID, key, SchemaFields.DOC_UID, "source-file",
            SchemaFields.PATH, key, SchemaFields.COLLECTION, "default",
            SchemaFields.CONTENT, "retained content", SchemaFields.SOURCE_SHA256, hash)));
      }
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      var publisher = spy(new WorkerIngestService(null, null, null, IndexingPacing.unthrottled(),
          directory.resolve("base"), null, null, null, null, 0L));
      publisher.useExclusionRoots(() -> List.of(directory.resolve("roots")));
      var authority = mock(SourcePresence.RootCheck.class, CALLS_REAL_METHODS);
      when(authority.vouches(root)).thenReturn(true);
      when(authority.vouchesForFile(root)).thenReturn(true);
      doReturn(authority).when(publisher).rootCheck();
      if (unknown && indexed) doThrow(new IllegalStateException("policy unavailable"))
          .when(publisher).isUserExcluded(any(), any());
      if (excluded) assertTrue(publisher.isUserExcluded(file, root));
      var logger = mock(Logger.class);
      var context = new KnowledgeServerMigrationOps.DrainSwitchBufferContext(
          queue, runtime, null, IndexingPacing.unthrottled(), directory.resolve("base"),
          directory.resolve("active"), json, () -> false, () -> true, logger,
          Long.MAX_VALUE, building).withPublisher(publisher);
      var row = queue.watcherReconciliationRow(key);
      var journal = queue.retirementReplayOps();
      long admissions = queue.switchBufferAdmissions();
      try (var hashes = mockStatic(SourceContentHash.class, CALLS_REAL_METHODS)) {
        if (!reconcile) KnowledgeServerMigrationOps.observeForCleanup(publisher, file, root);
        else {
          for (int attempt = 0; attempt < 2; attempt++) {
            boolean settled = KnowledgeServerMigrationOps.reconcileRelease030Path(context, file, root, "default");
            assertEquals(!unknown && (!excluded || !indexed), settled,
                "only a known excluded absence or ordinary matching content settles retirement");
          }
        }
        if (excluded || unknown) assertDoesNotThrow(hashes::verifyNoInteractions, "retirement must not hash excluded or unknown-policy sources");
        else hashes.verify(() -> SourceContentHash.sha256WithinRoot(file, file), times(2));
      }
      assertEquals(row, queue.watcherReconciliationRow(key), "no replacement admission");
      assertEquals(admissions, queue.switchBufferAdmissions(), "no new journal order");
      assertEquals(journal, queue.retirementReplayOps(), "retained journal is unchanged");
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals(indexed ? 1 : 0, runtime.indexCountOps().countByIdAndChunksStrict(key));
      if (indexed) assertEquals("retained content", runtime.documentFieldOps()
          .getDocumentFieldOrThrow(key, SchemaFields.CONTENT), "no deletion or republication");
      if (excluded && indexed) verify(logger, times(1)).warn(
          "0.3.0 retirement waits on excluded paths still indexed");
    } finally {
      ConfigStore.restoreGlobal(installed, previous);
    }
  }
}
