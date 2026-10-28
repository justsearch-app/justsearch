/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import io.justsearch.indexerworker.loop.SourceContentHash;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferSyncRoot;
import io.justsearch.indexerworker.server.Release030Fixture;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** The actual ROOT spool/correction path, with real queue creator, source and serving index. */
final class Release030ForgetRootReplayTest extends LuceneExecutorTestBase {
  @TempDir Path directory;

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}") @ValueSource(booleans = {false, true})
  void excludedAbsentRootSettlesWithoutReadmission(boolean enumerate) throws Exception {
    exercise(enumerate, true, false, false, false);
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}") @ValueSource(booleans = {false, true})
  void excludedIndexedRootStaysPendingWithoutReadOrDeletion(boolean enumerate) throws Exception {
    exercise(enumerate, true, true, false, false);
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}") @ValueSource(booleans = {false, true})
  void missingExcludedIndexedRootStaysPendingWithoutDeletion(boolean enumerate) throws Exception {
    exercise(enumerate, true, true, true, false);
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}") @ValueSource(booleans = {false, true})
  void excludedOrphanChunksStillKeepRootPending(boolean enumerate) throws Exception {
    exercise(enumerate, true, true, true, true);
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}") @ValueSource(booleans = {false, true})
  void ordinaryRootStillReadsAndCorrectsWithoutANewPathBudget(boolean enumerate) throws Exception {
    exercise(enumerate, false, true, false, false);
  }

  private void exercise(boolean enumerate, boolean excluded, boolean indexed, boolean missing,
      boolean chunksOnly) throws Exception {
    Release030Fixture.unpack("switching", directory);
    Path root = Files.createDirectories(directory.resolve("roots/ordinary"));
    Path file = Files.writeString(root.resolve("member.txt"), "original source");
    String key = PathNormalizer.normalizeKey(file);
    String oldHash = SourceContentHash.sha256(file);
    var json = JsonMapper.builder().build();
    var state = json.readTree(Files.readString(directory.resolve("data/index/default/state.json")));
    String building = state.path("building_generation").asString();
    var previous = ConfigStore.globalOrNull();
    var policy = ResolvedConfig.builder().putDefault("justsearch.ui.exclude_patterns",
        json.writeValueAsString(excluded ? List.of("ordinary/member.txt") : List.of())).build();
    // Seed accepted work before the user excludes it, as Forget/recovery encounters it.
    var installed = new ConfigStore(ResolvedConfig.builder()
        .putDefault("justsearch.ui.exclude_patterns", "[]").build());
    ConfigStore.setGlobal(installed);
    var fields = new java.util.ArrayList<>(FieldCatalogDef.forChunkTesting(0).fields());
    fields.add(new FieldCatalogDef.FieldDef(SchemaFields.SOURCE_SHA256,
        "keyword", true, false, List.of(), null, null, false));
    try (var queue = new SqliteJobQueue(directory.resolve("data/jobs.db"));
        var runtime = IndexSchema.fromCatalog(new FieldCatalogDef("retirement-root-policy", fields))
            .atPath(directory.resolve("serving")).withExecutorRegistrations(testLuceneExecutors()).open()) {
      queue.open();
      var roots = List.of(directory.resolve("roots/alpha"), directory.resolve("roots/beta"));
      queue.release030RecoveryState().prepared(roots, queue.prepareRelease030Recovery(roots, p -> false));
      queue.prepareRelease030ExplicitFiles();
      queue.release030RecoveryState().beginRetirement(state.path("active_generation").asString(), building, Map.of());
      queue.inventoryRetirementRoots();
      queue.retainRelease030Retirement(building, List.of(), Map.of());
      String rootKey = "sync_root:" + PathNormalizer.normalizePathPrefix(root.toString());
      assertTrue(queue.putSyncRootForGeneration(building, rootKey,
          new SwitchBufferSyncRoot(root.toString(), true, null), "C"));
      var receipt = queue.retirementRootReceipt(building, rootKey);
      assertEquals(1, queue.enqueueRetirementRootEntries(receipt,
          List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(root)), "C", false, false, () -> false));
      queue.prepareRetirementClaims();
      var claim = queue.pollPending(1000).stream().filter(job -> job.path().equals(file)).findFirst().orElseThrow();
      queue.markDoneTransitions(List.of(new JobQueue.IngestionLedgerTransition(claim, null, oldHash)),
          IngestionOutcome.of(IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS", IngestionRetryPolicy.NONE));
      if (indexed) {
        if (!chunksOnly) runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
            SchemaFields.DOC_ID, key, SchemaFields.DOC_UID, "source-file",
            SchemaFields.PATH, key, SchemaFields.COLLECTION, "C",
            SchemaFields.CONTENT, "retained content", SchemaFields.SOURCE_SHA256, oldHash)));
        runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
            SchemaFields.DOC_ID, key + "#chunk-0", SchemaFields.DOC_UID, "source-chunk",
            SchemaFields.PARENT_DOC_ID, key,
            SchemaFields.PATH, key, SchemaFields.COLLECTION, "C", SchemaFields.IS_CHUNK, "true",
            SchemaFields.CONTENT, "retained chunk")));
      }
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      if (missing) Files.delete(file);
      else Files.writeString(file, "changed source requiring a real correction");
      String newHash = missing ? null : SourceContentHash.sha256(file);
      installed.swap(policy);
      var row = queue.watcherReconciliationRow(key);
      var journal = queue.retirementReplayOps();
      long admissions = queue.switchBufferAdmissions();
      var publisher = new WorkerIngestService(null, null, null, IndexingPacing.unthrottled(),
          directory.resolve("base"), null, null, null, null, 0L);
      publisher.useExclusionRoots(() -> List.of(directory.resolve("roots")));
      // Force=true on main does not acquire the candidate-discovery retained-path budget.
      var ops = new SyncDirectoryOps(runtime.readPathOps(), runtime.pruneOps(), runtime.commitOps(),
          queue, IndexingPacing.unthrottled(), null, 1, 1);
      ops.setUserExclusions(publisher::isUserExcluded);
      // The pre-guard merge has no strict policy setter. The same behavioral checks still
      // execute there and must detect the real content read/admission, rather than fail to compile.
      try {
        var predicate = WorkerIngestService.class.getMethod("isUserExcludedForRetirement", Path.class, Path.class);
        SyncDirectoryOps.class.getDeclaredMethod("setRetirementExclusions", java.util.function.BiPredicate.class)
            .invoke(ops, (java.util.function.BiPredicate<Path, Path>) (path, boundary) -> {
              try { return (boolean) predicate.invoke(publisher, path, boundary); }
              catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            });
      } catch (NoSuchMethodException beforeGuard) {
        // Historical interface; no policy hook to install.
      }
      try (var hashes = mockStatic(SourceContentHash.class, CALLS_REAL_METHODS)) {
        var response = enumerate
            ? ops.enumerateRetirementRoot(root.toString(), "C", () -> false, false,
                java.util.function.IntSupplier::getAsInt, receipt)
            : ops.execute(root.toString(), true, null,
                () -> false, "C", SyncDirectoryOps.AdmissionMode.RETIREMENT_REPLAY, receipt);
        if (excluded && indexed) {
          assertTrue(response.getError().contains("retirement waits on excluded paths still indexed"), response.toString());
        } else {
          assertEquals("", response.getError(), response.toString());
          assertFalse(response.getSkipped());
          assertFalse(response.getDeleteDetectionUnverified());
        }
        assertEquals(0, response.getFilesDeleted());
        if (excluded) {
          assertDoesNotThrow(hashes::verifyNoInteractions, "retirement must not hash excluded or unknown-policy sources");
          assertEquals(0, response.getFilesAdded());
          assertEquals(row, queue.watcherReconciliationRow(key), "no replacement admission");
        } else {
          hashes.verify(() -> SourceContentHash.sha256(eq(file), any()), times(1));
          var corrected = queue.watcherReconciliationRow(key);
          assertEquals("PENDING", corrected.get("state"));
          assertEquals(newHash, corrected.get("planned_source_sha256"));
          assertNotEquals(row.get("unit_revision"), corrected.get("unit_revision"));
          assertEquals("C", corrected.get("collection"));
          assertEquals(PathNormalizer.normalizeKey(root), corrected.get("ingestion_root"));
          assertEquals(1, queue.release030RecoveryState().corrections().size());
        }
      }
      assertEquals(admissions, queue.switchBufferAdmissions(), "no fresh acknowledged order");
      assertEquals(journal, queue.retirementReplayOps(), "original ROOT intent retained");
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals(indexed ? chunksOnly ? 1 : 2 : 0, runtime.indexCountOps().countByIdAndChunksStrict(key));
      if (indexed && !chunksOnly) assertEquals("retained content", runtime.documentFieldOps()
          .getDocumentFieldOrThrow(key, SchemaFields.CONTENT));
    } finally {
      ConfigStore.restoreGlobal(installed, previous);
    }
  }
}
