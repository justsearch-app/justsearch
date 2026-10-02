/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.app.api.indexing.AcceptedProjection;
import io.justsearch.app.api.indexing.ProjectionDurability;
import io.justsearch.app.api.indexing.ProjectionReceipt;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.SwitchBufferCapableQueue;
import io.justsearch.indexing.SchemaFields;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkerProjectionPortTest extends LuceneExecutorTestBase {
  @TempDir Path tempDir;

  @Test
  void inPlaceUpsertsReportSemanticDeferralAndKeepTextVisible() throws Exception {
    Path base = tempDir.resolve("index");
    var manager = new IndexGenerationManager(base);
    var generation = manager.initializeOrLoad();
    String building = manager.startMigration("manual").building_generation();
    var schema = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(0),
        () -> Map.of(), ignored -> {});
    var config = new ResolvedConfigBuilder().build();
    var queue = org.mockito.Mockito.mock(SwitchBufferCapableQueue.class);
    var inPlace = new AtomicBoolean();
    org.mockito.Mockito.when(queue.admitProjectionForGeneration(
        org.mockito.ArgumentMatchers.eq(building), org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(ignored -> {
          // A later mode observation must not change the receipt for the already admitted write.
          inPlace.set(!inPlace.get());
          return SwitchBufferCapableQueue.ProjectionAdmission.ACCEPTED;
        });
    try (var runtime = schema.atPath(generation.activeGenerationPath()).withConfig(config)
        .withExecutorRegistrations(testLuceneExecutors()).open()) {
      runtime.commitOps().stopCommitTimer();
      var service = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
          base, generation.activeGenerationPath(), runtime, runtime, null, 0L);
      service.setProjectionSemanticDeferralSupplier(inPlace::get);
      int revision = 0;
      for (boolean deferred : new boolean[] {false, true, false}) {
        inPlace.set(deferred);
        var upsert = new AcceptedProjection("memory", "record-7", ++revision,
            AcceptedProjection.Kind.UPSERT, "{\"content\":\"visible source text\"}");
        ProjectionReceipt receipt = service.applyProjection(
            upsert, ProjectionDurability.NRT, CallContext.none());
        assertEquals(generation.activeGenerationId(), receipt.generationId());
        assertEquals(deferred ? ProjectionReceipt.Visibility.TEXT_ONLY_SEMANTIC_AT_ACTIVATION
            : ProjectionReceipt.Visibility.NRT, receipt.visibility());
        assertEquals("visible source text", runtime.documentFieldOps().getDocumentField(
            upsert.indexId(), SchemaFields.CONTENT));
        org.mockito.Mockito.verify(queue, org.mockito.Mockito.atLeastOnce())
            .admitProjectionForGeneration(building, upsert.encode());
      }
      inPlace.set(true);
      var deletion = new AcceptedProjection("memory", "record-7", 4,
          AcceptedProjection.Kind.DELETE, null);
      assertEquals(ProjectionReceipt.Visibility.NRT, service.applyProjection(
          deletion, ProjectionDurability.NRT, CallContext.none()).visibility());
      assertNull(runtime.documentFieldOps().getDocumentField(deletion.indexId(), SchemaFields.CONTENT));
      org.mockito.Mockito.verify(queue).admitProjectionForGeneration(building, deletion.encode());
    }
  }

  @Test
  void noFileUpsertAndDeleteUseTheServingRuntimeAndExactWitness() throws Exception {
    Path base = tempDir.resolve("index");
    var generation = new IndexGenerationManager(base).initializeOrLoad();
    var schema = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(0),
        () -> Map.of(), ignored -> {});
    var config = new ResolvedConfigBuilder().build();
    try (var runtime = schema.atPath(generation.activeGenerationPath()).withConfig(config)
        .withExecutorRegistrations(testLuceneExecutors()).open()) {
      runtime.commitOps().stopCommitTimer();
      var service = new WorkerIngestService(null, null, null, IndexingPacing.unthrottled(),
          base, generation.activeGenerationPath(), runtime, runtime, null, 0L);
      var upsert = new AcceptedProjection("memory", "record-7", 1,
          AcceptedProjection.Kind.UPSERT, "{\"content\":\"source text\",\"title\":\"Note\"}");

      ProjectionReceipt receipt = service.applyProjection(
          upsert, ProjectionDurability.NRT, CallContext.none());
      assertEquals(generation.activeGenerationId(), receipt.generationId());
      assertEquals(ProjectionReceipt.Visibility.NRT, receipt.visibility());
      assertEquals("source text", runtime.documentFieldOps().getDocumentField(
          upsert.indexId(), SchemaFields.CONTENT));
      assertEquals("memory", runtime.documentFieldOps().getDocumentField(
          upsert.indexId(), SchemaFields.PROJECTION_SOURCE_ID));
      assertEquals("1", runtime.documentFieldOps().getDocumentField(
          upsert.indexId(), SchemaFields.PROJECTION_SOURCE_REVISION));
      assertEquals(upsert.fieldsDigest(), runtime.documentFieldOps().getDocumentField(
          upsert.indexId(), SchemaFields.PROJECTION_DIGEST));
      assertEquals(upsert.documentUid(), runtime.documentFieldOps().getDocumentField(
          upsert.indexId(), SchemaFields.DOC_UID));

      var deletion = new AcceptedProjection("memory", "record-7", 2,
          AcceptedProjection.Kind.DELETE, null);
      assertEquals(ProjectionReceipt.Visibility.NRT, service.applyProjection(
          deletion, ProjectionDurability.NRT, CallContext.none()).visibility());
      assertNull(runtime.documentFieldOps().getDocumentField(
          upsert.indexId(), SchemaFields.CONTENT));
      assertThrows(WorkerServiceException.class, () -> service.applyProjection(
          upsert, ProjectionDurability.DURABLE, CallContext.none()));
    }
  }
}
