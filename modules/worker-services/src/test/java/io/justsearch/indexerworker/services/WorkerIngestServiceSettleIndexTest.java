/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.SettleIndexRequest;
import io.justsearch.ipc.SettleIndexResponse;
import io.justsearch.ipc.UpgradeQuiescenceRequest;
import io.justsearch.ipc.UpgradeQuiescenceResponse;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 931 §E item 10 — {@code SettleIndex} at the gRPC boundary.
 *
 * <p>Pins the two halves that matter to a paired evaluation: the happy path reports the before /
 * after document counts (that report IS the evidence both arms queried equal merge state), and the
 * refusals actually refuse — a force-merge fired underneath a blue/green migration or an upgrade
 * barrier would be writer contention on a writer someone else was promised.
 */
@DisplayName("WorkerIngestService.settleIndex")
final class WorkerIngestServiceSettleIndexTest {

  @TempDir Path tempDir;

  private RunningRuntime runtime;
  private Path indexBasePath;

  @BeforeEach
  void setUp() {
    indexBasePath = tempDir.resolve("index-base");
    runtime =
        IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0))
            .atPath(tempDir.resolve("index"))
            .open();
  }

  @AfterEach
  void tearDown() {
    if (runtime != null) {
      runtime.close();
    }
  }

  private WorkerIngestService service() {
    return new WorkerIngestService(
        null,
        null,
        null,
        IndexingPacing.unthrottled(),
        indexBasePath,
        tempDir.resolve("index"),
        runtime,
        runtime,
        null,
        0L);
  }

  private static SettleIndexResponse settle(WorkerIngestService svc, SettleIndexRequest request) {
    return svc.settleIndex(request, CallContext.none());
  }

  private void index(String docId) {
    Map<String, Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID, docId);
    fields.put(SchemaFields.DOC_UID, docId + "#1");
    fields.put(SchemaFields.PATH, docId);
    fields.put(SchemaFields.CONTENT, "content of " + docId);
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
  }

  /**
   * 20 documents, 3 deleted: 15% sits below TieredMergePolicy's 20% {@code deletesPctAllowed}, so
   * the tombstones survive the commit and there is something for the settle to purge.
   */
  private void seedTwentyDocsWithThreeTombstones() {
    for (int i = 0; i < 20; i++) {
      index("c:/corpus/doc-" + i + ".txt");
    }
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
    for (String docId :
        List.of("c:/corpus/doc-1.txt", "c:/corpus/doc-3.txt", "c:/corpus/doc-5.txt")) {
      runtime.indexingCoordinator().deleteById(docId);
    }
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  @Test
  @DisplayName("happy path returns the before/after document counts")
  void happyPathReportsCounts() {
    seedTwentyDocsWithThreeTombstones();

    SettleIndexResponse resp =
        settle(service(), SettleIndexRequest.newBuilder().setExpungeDeletesOnly(true).build());

    assertTrue(resp.getAccepted(), resp.getError());
    assertEquals("", resp.getError());
    assertEquals(20L, resp.getMaxDocBefore(), "before: the tombstones are still counted");
    assertEquals(17L, resp.getNumDocsBefore());
    assertEquals(17L, resp.getMaxDocAfter(), "after: maxDoc converged on numDocs");
    assertEquals(17L, resp.getNumDocsAfter());
    assertTrue(resp.getSegmentsAfter() >= 1, "segment count must be reported");
    assertEquals(17L, runtime.indexCountOps().maxDoc(), "the live index really was settled");
  }

  @Test
  @DisplayName("refuses while a blue/green migration is in flight")
  void refusesDuringMigration() throws Exception {
    seedTwentyDocsWithThreeTombstones();
    IndexGenerationManager generations = new IndexGenerationManager(indexBasePath);
    generations.initializeOrLoad();
    generations.updateMigrationState(IndexGenerationManager.MigrationState.MIGRATING);

    SettleIndexResponse resp =
        settle(service(), SettleIndexRequest.newBuilder().setExpungeDeletesOnly(true).build());

    assertFalse(resp.getAccepted(), "a settle during MIGRATING must be refused");
    assertTrue(
        resp.getError().contains("MIGRATING"), "the refusal must name the state: " + resp.getError());
    assertEquals(0L, resp.getMaxDocAfter(), "a refusal carries no counts");
    assertEquals(
        20L,
        runtime.indexCountOps().maxDoc(),
        "a refused settle must not have touched the writer");
  }

  @Test
  @DisplayName("refuses while an upgrade quiescence preparation owns the Worker barrier")
  void refusesDuringUpgradeQuiescence() {
    seedTwentyDocsWithThreeTombstones();
    WorkerIngestService svc = service();
    UpgradeQuiescenceResponse prepared =
        svc.prepareUpgrade(
            UpgradeQuiescenceRequest.newBuilder().setPreparationId("upgrade-1").build(),
            CallContext.none());
    assertEquals("upgrade-1", prepared.getPreparationId());

    SettleIndexResponse resp =
        settle(svc, SettleIndexRequest.newBuilder().setExpungeDeletesOnly(true).build());

    assertFalse(resp.getAccepted(), "a settle under an upgrade barrier must be refused");
    assertTrue(
        resp.getError().contains("Upgrade quiescence"),
        "the refusal must name the barrier: " + resp.getError());
    assertEquals(
        20L,
        runtime.indexCountOps().maxDoc(),
        "a refused settle must not have touched the writer");
  }

  @Test
  @DisplayName("refuses when there is no index runtime")
  void refusesWithoutIndexRuntime() {
    WorkerIngestService svc =
        new WorkerIngestService(
            null,
            null,
            null,
            IndexingPacing.unthrottled(),
            indexBasePath,
            tempDir.resolve("index"),
            null,
            null,
            null,
            0L);

    SettleIndexResponse resp = settle(svc, SettleIndexRequest.getDefaultInstance());

    assertFalse(resp.getAccepted());
    assertTrue(
        resp.getError().contains("Index runtime not available"),
        "the refusal must name the cause: " + resp.getError());
  }
}
