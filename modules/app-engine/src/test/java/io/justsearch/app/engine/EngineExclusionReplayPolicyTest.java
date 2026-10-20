/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferSyncRoot;
import io.justsearch.indexerworker.server.MigrationTransitionBarrier;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ipc.WatchRootRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/** EX5 / S31.5: a replay helper cannot own the published runtime's processing policy. */
@Timeout(300)
final class EngineExclusionReplayPolicyTest {
  @Test
  void EX5LegacySyncReplayKeepsServingPolicyAfterLiveCutover(@TempDir Path temp) throws Exception {
    Path data = temp.resolve("data");
    Path root = Files.createDirectories(data.resolve("documents"));
    Path excluded = Files.writeString(Files.createDirectories(root.resolve("private"))
        .resolve("report.txt"), "private retained text");
    Path allowed = Files.writeString(root.resolve("public.txt"), "public retained text");
    new ObjectMapper().writeValue(data.resolve("watched_roots.json").toFile(),
        Map.of("roots", List.of(Map.of("path", root.toString()))));
    var barrier = new MigrationTransitionBarrier.Controlled("migration-before-switching");
    try (var engine = EngineTestHarness.start(data, barrier)) {
      try {
        try (var view = engine.captureServingView()) {
          assertTrue(view.services().ingestService().watchRoot(WatchRootRequest.newBuilder()
              .setRootPath(root.toString()).setRootAdded(true).build(),
              CallContext.none()).getWatching());
        }
        assertTrue(engine.client().submitBatch(List.of(excluded, allowed), TestEngineContexts.FOREGROUND)
            .getAcceptedCount() > 0);
        assertTrue(engine.awaitIndexed(2, 120_000), "Blue files must finish indexing");
        var migration = engine.client().startMigration("exclusion_replay_policy", TestEngineContexts.FOREGROUND);
        assertTrue(migration.accepted());
        assertTrue(barrier.awaitReached(120, TimeUnit.SECONDS), "Green enumeration must reach cutover");
        try (var view = engine.captureServingView()) {
          seedPending((RunningRuntime) view.ingestRuntime(), excluded, allowed);
          EngineTestHarness.publishConfig(data, engine.indexBase(),
              Map.of("justsearch.ui.exclude_patterns", "[\"private/**\"]"));
          assertProcessingPolicy((RunningRuntime) view.ingestRuntime(), excluded, allowed);
        }
        try (var queue = new SqliteJobQueue(data.resolve("jobs.db"))) {
          queue.open();
          assertTrue(queue.putSyncRootForGeneration(migration.buildingGenerationId(), "sync_root:" + root,
              new SwitchBufferSyncRoot(root.toString(), false, null)));
          assertTrue(queue.listSwitchBufferOpsStrictForGeneration(migration.buildingGenerationId()).stream()
              .anyMatch(op -> op.key().equals("sync_root:" + root) && op.op().equals("SYNC_ROOT")),
              "a persisted legacy SYNC_ROOT must actually be replayed");
        }
        assertTrue(engine.client().requestCutover(true, TestEngineContexts.FOREGROUND).accepted());
        barrier.release();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        boolean promoted = false;
        while (System.nanoTime() < deadline) {
          try (var view = engine.captureServingView()) {
            promoted = view.searchRuntime() == view.ingestRuntime()
                && view.activeGenerationPath().getFileName().toString().equals(migration.buildingGenerationId());
          }
          if (promoted) break;
          Thread.sleep(50);
        }
        assertTrue(promoted, "Green must be serving without a restart");
        try (var queue = new SqliteJobQueue(data.resolve("jobs.db"))) {
          queue.open();
          assertEquals(0, queue.switchBufferDepth(), "the replay must have committed and settled");
        }
        try (var view = engine.captureServingView()) {
          var runtime = (RunningRuntime) view.ingestRuntime();
          // Seed deterministic pending states without loading enrichment models.
          seedPending(runtime, excluded, allowed);
          assertEquals("private retained text", runtime.documentFieldOps()
              .getDocumentContent(PathNormalizer.normalizeKey(excluded)));
          assertProcessingPolicy(runtime, excluded, allowed);
        }
        assertEquals(List.of(PathNormalizer.normalizeKey(allowed)),
            engine.client().queryPendingVduDocIds(10, TestEngineContexts.FOREGROUND),
            "EX5: the live client must use the promoted serving policy");
      } finally {
        barrier.release();
      }
    }
  }

  private static void seedPending(RunningRuntime runtime, Path excluded, Path allowed) throws Exception {
    for (Path file : List.of(excluded, allowed)) {
      String key = PathNormalizer.normalizeKey(file);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
      while (runtime.documentFieldOps().getDocumentContent(key) == null && System.nanoTime() < deadline) {
        Thread.sleep(50);
      }
      assertTrue(runtime.indexingCoordinator().updateDocument(key, Map.of(
          SchemaFields.VDU_STATUS, "PENDING", SchemaFields.EMBEDDING_STATUS, "PENDING",
          SchemaFields.NER_STATUS, "PENDING", SchemaFields.SPLADE_STATUS, "PENDING")),
          "Both real ingested files must exist before pending-state seeding");
    }
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  private static void assertProcessingPolicy(RunningRuntime runtime, Path excluded, Path allowed) {
    String excludedId = PathNormalizer.normalizeKey(excluded);
    String allowedId = PathNormalizer.normalizeKey(allowed);
    assertAll(
        () -> assertEquals(Set.of(excludedId), runtime.documentFieldOps()
            .excludedProcessingIds(List.of(excludedId, allowedId)), "EX5: cache recheck uses serving roots"),
        () -> assertEquals(List.of(allowedId), runtime.documentFieldOps()
            .queryDocIdsByField(SchemaFields.VDU_STATUS, "PENDING", 10), "EX5: VDU uses serving roots"),
        () -> assertEquals(List.of(allowedId), runtime.documentFieldOps()
            .queryDocIdsByField(SchemaFields.EMBEDDING_STATUS, "PENDING", 10), "EX5: embedding uses serving roots"),
        () -> assertEquals(List.of(allowedId), runtime.documentFieldOps()
            .queryDocIdsByField(SchemaFields.NER_STATUS, "PENDING", 10), "EX5: NER uses serving roots"),
        () -> assertEquals(List.of(allowedId), runtime.documentFieldOps()
            .queryDocIdsByField(SchemaFields.SPLADE_STATUS, "PENDING", 10), "EX5: SPLADE uses serving roots"));
  }
}
