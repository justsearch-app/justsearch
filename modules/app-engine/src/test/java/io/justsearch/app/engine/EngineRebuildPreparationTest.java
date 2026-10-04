/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorRegistrations;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding;
import io.justsearch.app.api.knowledge.KnowledgeClientException;
import io.justsearch.app.api.operations.RecordedBulkPlan;
import io.justsearch.app.api.operations.RecordedIngestionService;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.app.services.registry.operations.handlers.BulkReindexHandler;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The recovery bulk plan can be prepared against actual read-only Blue after the rebuild brake. */
final class EngineRebuildPreparationTest {
  private static final String RECOVERY_POLICY = "BLUE_GREEN_MIGRATE";
  private static final String FOREIGN_FINGERPRINT = "f".repeat(64);
  private static final EngineContext CONTEXT = TestEngineContexts.BACKGROUND;

  @TempDir Path directory;

  @Test
  @Timeout(180)
  void brakedReadOnlyEngineCanPrepareRebuildWithoutWriterOrMigrationEffects() throws Exception {
    Path dataDir = directory.resolve("data");
    Path indexBase = dataDir.resolve("index");
    Path watchedRoot = Files.createDirectories(dataDir.resolve("watched"));
    EngineTestHarness.publishConfig(dataDir, indexBase,
        Map.of("index.schema_mismatch.policy", RECOVERY_POLICY));

    var generations = new IndexGenerationManager(indexBase);
    var layout = generations.initializeOrLoad();
    String currentFingerprint = new SsotCommitMetadataSource().build()
        .get(IndexFingerprint.COMMIT_META_KEY).toString();
    seedGeneration(layout.activeGenerationPath(), null);
    var inFlight = generations.startMigration("schema_mismatch");
    Path invalidBuildingPath = generations.resolveGenerationPathStrict(inFlight.building_generation());
    seedGeneration(invalidBuildingPath, FOREIGN_FINGERPRINT);
    for (int attempt = 0; attempt <= IndexGenerationManager.MAX_AUTO_REBUILD_ATTEMPTS; attempt++) {
      generations.recordAutoRebuildAttempt(currentFingerprint);
    }

    try (EngineTestHarness engine = EngineTestHarness.start(dataDir, indexBase,
        Map.of("index.schema_mismatch.policy", RECOVERY_POLICY))) {
      var client = engine.client();
      var before = engine.status();
      assertEquals("BLOCKED_REBUILD_BRAKE", before.getCompatibility().getSchemaCompatState(),
          "the real Engine boot must take the exhausted-brake read-only path");
      assertEquals(IndexGenerationManager.MigrationState.IDLE.name(),
          before.getMigration().getMigrationState());
      assertTrue(before.getMigration().getBuildingGenerationId().isBlank());
      assertEquals(layout.activeGenerationId(), before.getMigration().getActiveGenerationId());
      assertTrue(new IndexGenerationManager(indexBase).autoRebuildAttemptsFor(currentFingerprint)
          > IndexGenerationManager.MAX_AUTO_REBUILD_ATTEMPTS);

      byte[] stateBefore = Files.readAllBytes(indexBase.resolve("state.json"));
      long queueDepthBefore = before.getCore().getQueueDepth();
      long pendingBefore = before.getMigration().getPendingJobsCount();
      long processingBefore = before.getMigration().getProcessingJobsCount();
      var ordinaryCaptureFailure = assertThrows(KnowledgeClientException.class,
          () -> client.captureServingGeneration(CONTEXT));
      assertEquals(KnowledgeClientException.Status.UNAVAILABLE, ordinaryCaptureFailure.status(),
          "ordinary bulk ingestion still requires the absent ingest writer");
      var targetBefore = client.captureIndexTarget(CONTEXT);

      RecordedIngestionService ingestion = mock(RecordedIngestionService.class);
      BulkReindexHandler recovery = new BulkReindexHandler(
          RecordedBulkPlan.Profile.RECOVERY_REBUILD,
          ingestion,
          ignored -> List.of(new RootBinding(watchedRoot, "documents")),
          () -> client,
          List::of);
      var provenance = EngineProvenance.invocation(CONTEXT, ExecutorTag.UI,
          Instant.parse("2026-09-21T00:00:00Z"), Optional.empty());

      var prepared = recovery.prepare("{}", provenance, CONTEXT);
      var plan = RecordedBulkPlan.fromReplayPayload(prepared.replayPayloadJson());

      assertEquals(RecordedBulkPlan.Profile.RECOVERY_REBUILD, plan.profile());
      assertEquals(RecordedBulkPlan.Profile.RECOVERY_REBUILD.defaultSource(), plan.source());
      assertEquals(layout.activeGenerationId(), plan.scope().generation());
      assertEquals(watchedRoot, plan.scope().roots().getFirst().path());
      assertEquals(targetBefore, plan.target(),
          "rebuild preparation freezes the same worker-owned target available on read-only Blue");
      var after = engine.status();
      assertArrayEquals(stateBefore, Files.readAllBytes(indexBase.resolve("state.json")),
          "generation and brake authority remain byte-for-byte unchanged during preparation");
      assertEquals(queueDepthBefore, after.getCore().getQueueDepth());
      assertEquals(pendingBefore, after.getMigration().getPendingJobsCount());
      assertEquals(processingBefore, after.getMigration().getProcessingJobsCount());
      assertEquals("IDLE", after.getMigration().getMigrationState());
      assertTrue(after.getMigration().getBuildingGenerationId().isBlank());
      assertEquals("BLOCKED_REBUILD_BRAKE", after.getCompatibility().getSchemaCompatState());
      verifyNoInteractions(ingestion);
    }
  }

  private static void seedGeneration(Path path, String fingerprintOverride) throws Exception {
    Map<String, Object> metadata = new HashMap<>(new SsotCommitMetadataSource().build());
    if (fingerprintOverride != null) {
      metadata.put(IndexFingerprint.COMMIT_META_KEY, fingerprintOverride);
    }
    Map<String, Object> frozenMetadata = Map.copyOf(metadata);
    var schema = IndexSchema.fromCatalog(
        new JustSearchConfigurationLoader().loadFieldCatalog(),
        () -> frozenMetadata,
        new JsonSchemaCommitMetadataValidator());
    try (var executors = new TestEngineExecutors();
        var luceneExecutors = new LuceneExecutorRegistrations(executors);
        RunningRuntime runtime = schema.atPath(path).withExecutorRegistrations(luceneExecutors).open()) {
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "seeded-document",
          SchemaFields.DOC_UID, "seeded-document#0",
          SchemaFields.CONTENT, "brake recovery preparation seed")));
      runtime.commitOps().commitAndTrack(CommitReason.DRAIN);
    }
  }
}
