/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.app.api.indexing.AcceptedProjection;
import io.justsearch.app.api.indexing.ProjectionDurability;
import io.justsearch.app.api.indexing.ProjectionSeedSource;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.ProjectionDocumentMapper;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.indexing.SchemaFields;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

final class ProjectionCandidateReplayTest extends LuceneExecutorTestBase {
  @TempDir Path tempDir;

  @Test
  void incompleteSourceRetainsReplayWhileAppliedRevisionBecomesVisible() throws Exception {
    Path base = tempDir.resolve("incomplete-index");
    var manager = new IndexGenerationManager(base);
    manager.initializeOrLoad();
    String candidateId = manager.startMigration("manual", java.util.List.of("memory"))
        .building_generation();
    Path candidatePath = manager.resolveGenerationPathStrict(candidateId);
    var schema = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(0),
        () -> Map.of(), ignored -> {});
    var accepted = new AcceptedProjection("memory", "record-incomplete", 4,
        AcceptedProjection.Kind.UPSERT, "{\"content\":\"partial enumeration\"}");
    ProjectionSeedSource incomplete = new ProjectionSeedSource() {
      @Override public String sourceId() { return "memory"; }
      @Override public void enumerate(java.util.function.Consumer<AcceptedProjection> sink) {
        sink.accept(accepted);
        throw new IllegalStateException("enumeration stopped after first revision");
      }
    };
    try (var queue = new SqliteJobQueue(tempDir.resolve("incomplete-jobs.db"));
        var green = schema.atPath(candidatePath).withConfig(new ResolvedConfigBuilder().build())
            .withExecutorRegistrations(testLuceneExecutors()).open()) {
      queue.open();
      green.commitOps().stopCommitTimer();
      assertThrows(KnowledgeServerMigrationOps.ProjectionSeedIncompleteException.class,
          () -> KnowledgeServerMigrationOps.seedProjectionSource(queue, candidateId, incomplete));
      AtomicBoolean sourceReady = new AtomicBoolean();
      var replay = new KnowledgeServerMigrationOps.DrainSwitchBufferContext(
          queue, green, null, IndexingPacing.unthrottled(), base, candidatePath,
          new ObjectMapper(), () -> false, () -> true, LoggerFactory.getLogger(getClass()),
          Long.MAX_VALUE, candidateId, ignored -> sourceReady.get());
      assertFalse(KnowledgeServerMigrationOps.prepareSwitchReplayForPromotion(replay).isPresent());
      green.commitOps().maybeRefreshBlocking();
      assertEquals("partial enumeration", green.documentFieldOps().getDocumentField(
          accepted.indexId(), SchemaFields.CONTENT));
      assertEquals(2, queue.listSwitchBufferOpsStrictForGeneration(candidateId).size());
      sourceReady.set(true);
      assertTrue(KnowledgeServerMigrationOps.prepareSwitchReplayForPromotion(replay).isPresent());
    }
  }

  @Test
  void committedCandidateReplayReseedsAndRechecksAfterQueueAndWriterRestart() throws Exception {
    Path base = tempDir.resolve("restart-index");
    var manager = new IndexGenerationManager(base);
    manager.initializeOrLoad();
    String candidateId = manager.startMigration("manual").building_generation();
    Path candidatePath = manager.resolveGenerationPathStrict(candidateId);
    Path jobs = tempDir.resolve("restart-jobs.db");
    var schema = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(0),
        () -> Map.of(), ignored -> {});
    var config = new ResolvedConfigBuilder().build();
    var accepted = new AcceptedProjection("memory", "record-7", 9,
        AcceptedProjection.Kind.UPSERT, "{\"content\":\"retained after restart\"}");
    ProjectionSeedSource source = new ProjectionSeedSource() {
      @Override public String sourceId() { return "memory"; }
      @Override public void enumerate(java.util.function.Consumer<AcceptedProjection> sink) {
        sink.accept(accepted);
      }
    };

    for (int boot = 0; boot < 2; boot++) {
      try (var queue = new SqliteJobQueue(jobs);
          var green = schema.atPath(candidatePath).withConfig(config)
              .withExecutorRegistrations(testLuceneExecutors()).open()) {
        queue.open();
        green.commitOps().stopCommitTimer();
        KnowledgeServerMigrationOps.seedProjectionSource(queue, candidateId, source);
        var replay = new KnowledgeServerMigrationOps.DrainSwitchBufferContext(
            queue, green, null, IndexingPacing.unthrottled(), base, candidatePath,
            new ObjectMapper(), () -> false, () -> true, LoggerFactory.getLogger(getClass()),
            Long.MAX_VALUE, candidateId, "memory"::equals);
        assertTrue(KnowledgeServerMigrationOps.prepareSwitchReplayForPromotion(replay).isPresent());
        assertEquals("retained after restart", green.documentFieldOps().getDocumentField(
            accepted.indexId(), SchemaFields.CONTENT));
        assertEquals(2, queue.listSwitchBufferOpsStrictForGeneration(candidateId).size(),
            "pre-pointer source and projection witnesses must survive both boots");
      }
    }
  }

  @Test
  void firstProjectionCutLeavesLaterVersionAndJournalForRestartReplay() throws Exception {
    Path base = tempDir.resolve("mid-replay-index");
    var manager = new IndexGenerationManager(base);
    manager.initializeOrLoad();
    String candidateId = manager.startMigration("manual", java.util.List.of("memory"))
        .building_generation();
    Path candidatePath = manager.resolveGenerationPathStrict(candidateId);
    Path jobs = tempDir.resolve("mid-replay-jobs.db");
    var schema = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(0),
        () -> Map.of(), ignored -> {});
    var config = new ResolvedConfigBuilder().build();
    var first = new AcceptedProjection("memory", "first", 1,
        AcceptedProjection.Kind.UPSERT, "{\"content\":\"first replay unit\"}");
    var later = new AcceptedProjection("memory", "later", 1,
        AcceptedProjection.Kind.UPSERT, "{\"content\":\"later replay unit\"}");
    ProjectionSeedSource source = new ProjectionSeedSource() {
      @Override public String sourceId() { return "memory"; }
      @Override public void enumerate(java.util.function.Consumer<AcceptedProjection> sink) {
        sink.accept(first);
        sink.accept(later);
      }
    };

    for (int boot = 0; boot < 2; boot++) {
      try (var queue = new SqliteJobQueue(jobs);
          var green = schema.atPath(candidatePath).withConfig(config)
              .withExecutorRegistrations(testLuceneExecutors()).open()) {
        queue.open();
        green.commitOps().stopCommitTimer();
        KnowledgeServerMigrationOps.seedProjectionSource(queue, candidateId, source);
        var replay = new KnowledgeServerMigrationOps.DrainSwitchBufferContext(
            queue, green, null, IndexingPacing.unthrottled(), base, candidatePath,
            new ObjectMapper(), () -> false, () -> true, LoggerFactory.getLogger(getClass()),
            Long.MAX_VALUE, candidateId, "memory"::equals);
        if (boot == 0) {
          assertEquals("first projection cut", assertThrows(IllegalStateException.class,
              () -> KnowledgeServerMigrationOps.prepareSwitchReplayForPromotion(replay, () -> {
                green.commitOps().maybeRefreshBlocking();
                assertEquals("first replay unit", green.documentFieldOps().getDocumentField(
                    first.indexId(), SchemaFields.CONTENT));
                assertNull(green.documentFieldOps().getDocumentField(
                    later.indexId(), SchemaFields.CONTENT));
                throw new IllegalStateException("first projection cut");
              })).getMessage());
          assertEquals(3, queue.listSwitchBufferOpsStrictForGeneration(candidateId).size(),
              "an interrupted replay must retain both exact projections and its source marker");
        } else {
          assertTrue(KnowledgeServerMigrationOps.prepareSwitchReplayForPromotion(replay).isPresent());
          assertEquals("first replay unit", green.documentFieldOps().getDocumentField(
              first.indexId(), SchemaFields.CONTENT));
          assertEquals("later replay unit", green.documentFieldOps().getDocumentField(
              later.indexId(), SchemaFields.CONTENT));
        }
      }
    }
  }

  @Test
  void olderSourceSeedCannotOverwriteConcurrentUpdateAndDeleteOnCandidate() throws Exception {
    Path base = tempDir.resolve("index");
    var manager = new IndexGenerationManager(base);
    var active = manager.initializeOrLoad();
    String candidateId = manager.startMigration("manual").building_generation();
    Path candidatePath = manager.resolveGenerationPathStrict(candidateId);
    var schema = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(0),
        () -> Map.of(), ignored -> {});
    var config = new ResolvedConfigBuilder().build();
    var first = new AcceptedProjection("memory", "record-7", 1,
        AcceptedProjection.Kind.UPSERT, "{\"content\":\"before rebuild\"}");
    var updated = new AcceptedProjection("memory", "record-7", 2,
        AcceptedProjection.Kind.UPSERT, "{\"content\":\"newer accepted text\"}");
    var deleted = new AcceptedProjection("memory", "record-7", 3,
        AcceptedProjection.Kind.DELETE, null);

    try (var queue = new SqliteJobQueue(tempDir.resolve("jobs.db"));
        var blue = schema.atPath(active.activeGenerationPath()).withConfig(config)
            .withExecutorRegistrations(testLuceneExecutors()).open();
        var green = schema.atPath(candidatePath).withConfig(config)
            .withExecutorRegistrations(testLuceneExecutors()).open()) {
      queue.open();
      blue.commitOps().stopCommitTimer();
      green.commitOps().stopCommitTimer();
      blue.indexingCoordinator().indexSingle(ProjectionDocumentMapper.toIndexDocument(first));
      blue.commitOps().maybeRefreshBlocking();
      var service = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
          base, active.activeGenerationPath(), green, blue, null, 0L);
      service.applyProjection(updated, ProjectionDurability.NRT, CallContext.none());
      assertEquals("newer accepted text", blue.documentFieldOps().getDocumentField(
          updated.indexId(), SchemaFields.CONTENT));
      assertNull(green.documentFieldOps().getDocumentField(
          updated.indexId(), SchemaFields.CONTENT));

      ProjectionSeedSource source = new ProjectionSeedSource() {
        @Override public String sourceId() { return "memory"; }
        @Override public void enumerate(java.util.function.Consumer<AcceptedProjection> sink) {
          sink.accept(first); // The source read began before the accepted update.
        }
      };
      KnowledgeServerMigrationOps.seedProjectionSource(queue, candidateId, source);
      var replayContext = new KnowledgeServerMigrationOps.DrainSwitchBufferContext(
          queue, green, null, IndexingPacing.unthrottled(), base, candidatePath,
          new ObjectMapper(), () -> false, () -> true, LoggerFactory.getLogger(getClass()),
          Long.MAX_VALUE, candidateId, "memory"::equals);
      assertTrue(KnowledgeServerMigrationOps.prepareSwitchReplayForPromotion(
          replayContext).isPresent());
      assertEquals("newer accepted text", green.documentFieldOps().getDocumentField(
          updated.indexId(), SchemaFields.CONTENT));
      assertEquals("2", green.documentFieldOps().getDocumentField(
          updated.indexId(), SchemaFields.PROJECTION_SOURCE_REVISION));
      assertEquals(2, queue.listSwitchBufferOpsStrictForGeneration(candidateId).size(),
          "source marker and accepted revision remain until pointer commitment");

      service.applyProjection(deleted, ProjectionDurability.NRT, CallContext.none());
      assertNull(blue.documentFieldOps().getDocumentField(
          deleted.indexId(), SchemaFields.CONTENT));
      assertTrue(KnowledgeServerMigrationOps.prepareSwitchReplayForPromotion(
          replayContext).isPresent());
      assertNull(green.documentFieldOps().getDocumentField(
          deleted.indexId(), SchemaFields.CONTENT));
      assertEquals(2, queue.listSwitchBufferOpsStrictForGeneration(candidateId).size());
    }
  }

  @Test
  void refusedCandidateTransfersExactProjectionVersionsToSourceBeforeAbandonment()
      throws Exception {
    Path base = tempDir.resolve("refused-index");
    var manager = new IndexGenerationManager(base);
    var active = manager.initializeOrLoad();
    String candidateId = manager.startMigration("manual", java.util.List.of("memory"))
        .building_generation();
    Path candidatePath = manager.resolveGenerationPathStrict(candidateId);
    var schema = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(0),
        () -> Map.of(), ignored -> {});
    var oldUpdated = new AcceptedProjection("memory", "updated", 1,
        AcceptedProjection.Kind.UPSERT, "{\"content\":\"old\"}");
    var newUpdated = new AcceptedProjection("memory", "updated", 2,
        AcceptedProjection.Kind.UPSERT, "{\"content\":\"accepted\"}");
    var oldDeleted = new AcceptedProjection("memory", "deleted", 1,
        AcceptedProjection.Kind.UPSERT, "{\"content\":\"must disappear\"}");
    var newDeleted = new AcceptedProjection("memory", "deleted", 2,
        AcceptedProjection.Kind.DELETE, null);
    ProjectionSeedSource source = new ProjectionSeedSource() {
      @Override public String sourceId() { return "memory"; }
      @Override public void enumerate(java.util.function.Consumer<AcceptedProjection> sink) {
        sink.accept(oldUpdated);
        sink.accept(oldDeleted);
      }
    };

    try (var queue = new SqliteJobQueue(tempDir.resolve("refused-jobs.db"));
        var blue = schema.atPath(active.activeGenerationPath())
            .withConfig(new ResolvedConfigBuilder().build())
            .withExecutorRegistrations(testLuceneExecutors()).open()) {
      queue.open();
      blue.commitOps().stopCommitTimer();
      blue.indexingCoordinator().indexSingle(ProjectionDocumentMapper.toIndexDocument(oldUpdated));
      blue.indexingCoordinator().indexSingle(ProjectionDocumentMapper.toIndexDocument(oldDeleted));
      blue.commitOps().commitAndTrack(
          io.justsearch.adapters.lucene.runtime.CommitReason.SWITCH_BUFFER_REPLAY);
      KnowledgeServerMigrationOps.seedProjectionSource(queue, candidateId, source);
      assertTrue(queue.putSwitchBufferForGeneration(candidateId, newUpdated.journalKey(),
          "PROJECTION", newUpdated.encode()));
      assertTrue(queue.putSwitchBufferForGeneration(candidateId, newDeleted.journalKey(),
          "PROJECTION", newDeleted.encode()));
      var replay = new KnowledgeServerMigrationOps.DrainSwitchBufferContext(
          queue, blue, null, IndexingPacing.unthrottled(), base,
          active.activeGenerationPath(), new ObjectMapper(), () -> false, () -> true,
          LoggerFactory.getLogger(getClass()), Long.MAX_VALUE, candidateId,
          ignored -> true);
      assertTrue(KnowledgeServerMigrationOps.drainRefusedCandidateOnSource(replay));
      assertEquals("accepted", blue.documentFieldOps().getDocumentField(
          newUpdated.indexId(), SchemaFields.CONTENT));
      assertEquals("2", blue.documentFieldOps().getDocumentField(
          newUpdated.indexId(), SchemaFields.PROJECTION_SOURCE_REVISION));
      assertNull(blue.documentFieldOps().getDocumentField(
          newDeleted.indexId(), SchemaFields.CONTENT));
      assertTrue(queue.listSwitchBufferOpsStrictForGeneration(candidateId).isEmpty());
    }
    manager.abandonBuildingGeneration("recorded cancellation");
    manager.pruneMarkedForDeletionBestEffort();
    assertEquals(active.state().active_generation(), manager.readStateBestEffort().active_generation());
    assertNull(manager.readStateBestEffort().building_generation());
    assertFalse(Files.exists(candidatePath), "only the surviving source generation may retain an index");
  }
}
