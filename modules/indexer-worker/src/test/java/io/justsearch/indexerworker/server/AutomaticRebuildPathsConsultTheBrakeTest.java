/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.IndexRecoveryMarker;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.indexerworker.embed.EmbeddingFingerprint;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.StatusRequest;
import io.justsearch.ipc.StatusResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task t-20261013-ef3c41, round 3 — every automatic rebuild consults the repeat-rebuild brake.
 *
 * <p>Only the schema-mismatch rebuild asked it. The embedding-model-change re-embed, the rebuild
 * from source of an index recovered to empty, and the backup-first rebuild all started without
 * asking, so a spent budget held one path and not its neighbours; and a rebuild whose target could
 * not be computed answered "attempt 1" without asking at all. Each pair below is a held case (fails
 * before) and its released control (the same scenario does reach the path).
 */
@Timeout(180)
final class AutomaticRebuildPathsConsultTheBrakeTest {

  private static final String STORED_EMBEDDING = "b".repeat(64);
  private static final String CURRENT_EMBEDDING = "c".repeat(64);

  private KnowledgeServer server;

  @AfterEach
  void tearDown() {
    if (server != null) {
      try {
        server.close();
      } catch (Exception ignored) {
        // teardown best-effort
      }
      server = null;
    }
    EmbeddingFingerprint.invalidate();
  }

  @Test
  void aSpentBrakeStopsTheEmbeddingModelChangeReembed(@TempDir Path tempDir) throws Exception {
    WorkerBootFixture.Layout layout = embeddingChangedLayout(tempDir);
    spendTheBrake(layout);

    boot(layout, "BLUE_GREEN_MIGRATE", Map.of());

    assertTrue(server.rebuildBrakeExhaustedForTest(),
        "the brake for this target is spent, yet the embedding-model-change re-embed started");
    assertNull(stateAfterBoot(layout).building_generation(), "no re-embed generation was started");
    assertBrakedStatus("rebuild_brake_exhausted");
  }

  @Test
  void aReleasedBrakeStillStartsTheEmbeddingModelChangeReembed(@TempDir Path tempDir)
      throws Exception {
    WorkerBootFixture.Layout layout = embeddingChangedLayout(tempDir);

    boot(layout, "BLUE_GREEN_MIGRATE", Map.of());

    assertFalse(server.rebuildBrakeExhaustedForTest());
    IndexGenerationManager.State after = stateAfterBoot(layout);
    assertNotNull(after.building_generation(), "the re-embed generation was started");
    assertEquals("embedding_model_change",
        layout.genManager().readGenerationSourceBestEffort(after.building_generation()),
        "and it is the embedding-model-change path, not another rebuild");
  }

  @Test
  void aSpentBrakeStopsTheRebuildOfAnIndexRecoveredToEmpty(@TempDir Path tempDir)
      throws Exception {
    WorkerBootFixture.Layout layout = recoveredToEmptyLayout(tempDir);
    spendTheBrake(layout);

    boot(layout, "BLUE_GREEN_MIGRATE", Map.of("index.auto_recovery", "true"));

    assertTrue(server.rebuildBrakeExhaustedForTest(),
        "the brake for this target is spent, yet the rebuild from source started");
    assertNull(stateAfterBoot(layout).building_generation(), "no rebuild generation was started");
    assertTrue(IndexRecoveryMarker.exists(layout.activePath()),
        "the rebuild is still owed, so its marker stays");
    assertBrakedStatus("rebuild_brake_exhausted");
  }

  @Test
  void aReleasedBrakeStillRebuildsAnIndexRecoveredToEmpty(@TempDir Path tempDir)
      throws Exception {
    WorkerBootFixture.Layout layout = recoveredToEmptyLayout(tempDir);

    boot(layout, "BLUE_GREEN_MIGRATE", Map.of("index.auto_recovery", "true"));

    assertFalse(server.rebuildBrakeExhaustedForTest());
    IndexGenerationManager.State after = stateAfterBoot(layout);
    assertNotNull(after.building_generation(), "the rebuild from source was started");
    assertEquals("corrupt_index_rebuild",
        layout.genManager().readGenerationSourceBestEffort(after.building_generation()));
  }

  @Test
  void aSpentBrakeStopsTheBackupFirstRebuild(@TempDir Path tempDir) throws Exception {
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.seed(layout.activePath(), "f".repeat(64), 3);
    spendTheBrake(layout);

    boot(layout, "REBUILD_BACKUP_FIRST", Map.of());

    assertTrue(server.rebuildBrakeExhaustedForTest(),
        "the brake for this target is spent, yet the backup-first rebuild ran");
    assertEquals(3, docCount(layout.activePath()),
        "the mismatched index was neither backed up nor replaced");
    assertNull(soleSiblingWithSuffix(layout.activePath(), ".bak-"), "no backup was taken");
    assertBrakedStatus("rebuild_brake_exhausted");
  }

  @Test
  void anUncomputableTargetHoldsInsteadOfStartingARebuild(@TempDir Path tempDir)
      throws Exception {
    IndexGenerationManager mgr = new IndexGenerationManager(tempDir.resolve("index"));
    mgr.initializeOrLoad();
    byte[] before = Files.readAllBytes(tempDir.resolve("index").resolve("state.json"));

    assertThrows(
        IndexGenerationManager.AutoRebuildBrakeUnreadableException.class,
        () -> KnowledgeServer.recordAutoRebuildAttemptOrSkip(mgr, null),
        "a rebuild whose target cannot be computed has no brake to ask, so it is held");
    assertEquals(new String(before, java.nio.charset.StandardCharsets.UTF_8),
        new String(Files.readAllBytes(tempDir.resolve("index").resolve("state.json")),
            java.nio.charset.StandardCharsets.UTF_8),
        "and nothing is charged to any budget");
  }

  private void boot(WorkerBootFixture.Layout layout, String policy, Map<String, String> extras)
      throws Exception {
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), policy, extras);
    server = new KnowledgeServer(
        new io.justsearch.core.execution.TestEngineExecutors(),
        WorkerBootFixture.workerConfig(layout.dataDir()));
    server.start();
    assertNotNull(server.appServices(), "the Worker finished starting");
  }

  private void assertBrakedStatus(String reason) {
    StatusResponse status = server.appServices().ingestService()
        .indexStatus(StatusRequest.newBuilder().build(), CallContext.none());
    assertEquals("BLOCKED_REBUILD_BRAKE", status.getCompatibility().getSchemaCompatState(),
        "status says why ingestion stopped");
    assertEquals(reason, status.getCompatibility().getReindexRequiredReason());
    assertTrue(status.getCompatibility().getReindexRequired());
  }

  private static void spendTheBrake(WorkerBootFixture.Layout layout) throws Exception {
    String target = WorkerBootFixture.currentFingerprint();
    for (int i = 0; i <= IndexGenerationManager.MAX_AUTO_REBUILD_ATTEMPTS; i++) {
      layout.genManager().recordAutoRebuildAttempt(target);
    }
    assertTrue(
        layout.genManager().autoRebuildAttemptsFor(target)
            > IndexGenerationManager.MAX_AUTO_REBUILD_ATTEMPTS,
        "precondition: the budget for this target is spent");
  }

  /** A compatible index whose vectors were embedded by a model other than the current one. */
  private static WorkerBootFixture.Layout embeddingChangedLayout(Path tempDir) throws Exception {
    EmbeddingFingerprint.setForTesting(CURRENT_EMBEDDING);
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    Map<String, Object> meta = new HashMap<>(new SsotCommitMetadataSource().build());
    meta.put("embedding_model_sha256", STORED_EMBEDDING);
    Map<String, Object> frozen = Map.copyOf(meta);
    try (var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var luceneExecutors =
            new io.justsearch.adapters.lucene.runtime.LuceneExecutorRegistrations(executors);
        RunningRuntime r =
            IndexSchema.fromCatalog(
                    WorkerBootFixture.productionCatalog(),
                    () -> frozen,
                    new JsonSchemaCommitMetadataValidator())
                .atPath(layout.activePath())
                .withExecutorRegistrations(luceneExecutors)
                .open()) {
      r.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "seed-0",
          SchemaFields.DOC_UID, "seed-0#0",
          SchemaFields.CONTENT, "seeded document 0")));
      r.commitOps().commitAndTrack(CommitReason.DRAIN);
    }
    return layout;
  }

  /** A compatible index the adapter has recovered to empty, with its rebuild still owed. */
  private static WorkerBootFixture.Layout recoveredToEmptyLayout(Path tempDir) throws Exception {
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.seed(layout.activePath(), null, 0);
    IndexRecoveryMarker.write(layout.activePath(), "corrupt_index");
    return layout;
  }

  private static IndexGenerationManager.State stateAfterBoot(WorkerBootFixture.Layout layout) {
    return new IndexGenerationManager(layout.indexBase()).readStateBestEffort();
  }

  private static Path soleSiblingWithSuffix(Path dir, String suffix) throws Exception {
    String prefix = dir.getFileName().toString() + suffix;
    try (var siblings = Files.list(dir.getParent())) {
      return siblings
          .filter(p -> p.getFileName().toString().startsWith(prefix))
          .findFirst()
          .orElse(null);
    }
  }

  private static int docCount(Path generation) throws Exception {
    try (org.apache.lucene.store.Directory d =
        org.apache.lucene.store.FSDirectory.open(generation)) {
      if (!org.apache.lucene.index.DirectoryReader.indexExists(d)) {
        return 0;
      }
      try (var reader = org.apache.lucene.index.DirectoryReader.open(d)) {
        return reader.numDocs();
      }
    }
  }
}
