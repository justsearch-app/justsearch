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
}
