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
import io.justsearch.indexing.SchemaFields;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkerProjectionPortTest extends LuceneExecutorTestBase {
  @TempDir Path tempDir;

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
