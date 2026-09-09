/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.status.MigrationSource;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.ipc.MigrationStartRequest;
import io.justsearch.ipc.MigrationStartResponse;
import io.justsearch.ipc.StatusRequest;
import io.justsearch.ipc.StatusResponse;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 915 §C.8 — what a Worker does after it has spent its automatic-rebuild budget.
 *
 * <p>The first cut of the brake returned early from {@code KnowledgeServer.start()}. That skipped
 * the indexing loop and the whole {@code appServices} construction, so the Worker exited with no
 * service surface and no fatal-reason marker: the reason code this change added was
 * unreachable, and so was the read-only serving it promised. A constant existing in
 * {@code LifecycleReasonCode} says nothing about whether anything can emit it — this test drives
 * the real server into the exhausted state and reads the answer off its own service surface.
 *
 * <p>Deliberately an emit-chain test, not a constant test: it asserts the Worker still comes up and
 * serves (a, c) AND that the status payload carries the pair the Head maps to
 * {@code index.rebuild_brake_exhausted} (b), AND that the operator's recovery path is reachable
 * from that state and clears the brake (d).
 */
@Timeout(120)
final class BrakeExhaustedWorkerServesReadOnlyTest {

  /** A recorded shape no runtime produces - a mismatch, not an absent fingerprint. */
  private static final String FOREIGN_SHAPE = "f".repeat(64);

  private KnowledgeServer server;

  @AfterEach
  void tearDown() {
    if (server != null) {
      try {
        server.close();
      } catch (Exception ignored) {
        // teardown best-effort; the assertions have already run
      }
    }
  }

  @Test
  void anExhaustedBrakeServesSearchReadOnlyAndReportsWhyIngestionStopped(@TempDir Path tempDir)
      throws Exception {
    // 1. A real generation layout: Blue holds one document and is fine; a migration is already in
    //    flight and its Green carries a foreign fingerprint. That is the scenario the brake models —
    //    a rebuild that keeps failing the same way — and it is the path that reaches start()'s
    //    SCHEMA_MISMATCH handler, because the resumed migration opens Green with open() rather than
    //    openDeferred(). Seeded through WorkerBootFixture: the inline copy this test used to carry
    //    seeded Blue with FieldCatalogDef.forTesting(768) instead of the catalog the Worker loads,
    //    which is the fork the fixture exists to prevent.
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.seed(layout.activePath(), null, 1);
    WorkerBootFixture.seedInFlightGreen(layout, "f".repeat(64), 1);
    IndexGenerationManager genManager = layout.genManager();

    // 2. The brake, already spent on the shape THIS runtime would produce. Computing the target
    //    the same way the server does is the point: a hand-written key would make the test pass
    //    while the production lookup missed.
    String target = WorkerBootFixture.currentFingerprint();
    for (int i = 0; i <= IndexGenerationManager.MAX_AUTO_REBUILD_ATTEMPTS; i++) {
      genManager.recordAutoRebuildAttempt(target);
    }
    assertTrue(
        genManager.autoRebuildAttemptsFor(target) > IndexGenerationManager.MAX_AUTO_REBUILD_ATTEMPTS,
        "precondition: the budget for this target is spent");

    // 3. Boot a real Worker over that data directory, under the production policy.
    WorkerBootFixture.publishConfig(
        layout.dataDir(), layout.indexBase(), "BLUE_GREEN_MIGRATE");
    server = new KnowledgeServer(new io.justsearch.core.execution.TestEngineExecutors(), WorkerBootFixture.workerConfig(layout.dataDir()));
    server.start();

    // (a) the Worker took the exhausted-brake path AND finished starting. The first assertion is
    //     what makes the rest mean anything: a running Worker and a served search are equally true
    //     of an ordinary boot, so without it this test passes whether or not the branch ever ran.
    assertTrue(
        server.rebuildBrakeExhaustedForTest(),
        "precondition: the boot actually took the exhausted-brake path");
    assertNotNull(
        server.appServices(),
        "start() must have run to completion: a Worker with no service surface is a Worker gone");

    // (b) the status payload says WHY ingestion stopped, in the vocabulary the Head maps to
    //     index.rebuild_brake_exhausted.
    StatusResponse status =
        server
            .appServices()
            .ingestService()
            .indexStatus(StatusRequest.newBuilder().build(), CallContext.none());
    assertEquals(
        "BLOCKED_REBUILD_BRAKE",
        status.getCompatibility().getSchemaCompatState(),
        "the compat state is the wire carrier for the new reason code");
    assertEquals(
        "rebuild_brake_exhausted",
        status.getCompatibility().getReindexRequiredReason(),
        "reindexRequiredReason is what StatusLifecycleHandler turns into"
            + " index.rebuild_brake_exhausted");
    assertTrue(
        status.getCompatibility().getReindexRequired(),
        "an exhausted brake is a reindex-required state");

    // (b2) and it describes BLUE, the generation those searches reach. Live validation found all
    //      three of these disagreeing in this exact state: indexedDocuments came from
    //      jobQueue.completedCount() (DONE rows in jobs.db - ingest jobs, pruned, unrelated to
    //      corpus size) because there is no ingest runtime to count, and the stored fingerprint
    //      came back empty because its supplier was wired to that absent runtime. An empty stored
    //      fingerprint is what the status path reads as BLOCKED_LEGACY.
    long indexed = status.getCore().getDocCount();
    long searchable = status.getCore().getSearchableDocCount();
    long active = status.getMigration().getActiveDocCount();
    assertTrue(indexed > 0, "Blue holds documents, so the braked worker must not report zero");
    assertEquals(
        searchable,
        indexed,
        "documents indexed and documents searchable are the same set when nothing is being built");
    assertEquals(active, indexed, "and both are the active generation's count");
    assertEquals(
        WorkerBootFixture.currentFingerprint().length(),
        status.getCompatibility().getSchemaFpStored().length(),
        "the stored fingerprint is Blue's, not the empty string: an empty value routes to"
            + " BLOCKED_LEGACY the moment the brake check stops shadowing it");

    // (c) Blue still serves. This is the promise the read-only fall-through makes; a Worker that
    //     comes up but cannot answer a query has kept the letter of it and none of the substance.
    SearchResponse search =
        server
            .appServices()
            .searchService()
            .search(
                SearchRequest.newBuilder().setQuery("*").setLimit(10).build(), CallContext.none());
    assertNotNull(search, "search must answer while the brake is exhausted");

    // (d) the recovery path out of the state, driven through the Worker's own ingest service
    //     rather than by calling the generation manager directly. core.rebuild-index
    //     (RebuildIndexHandler) resolves to IndexingService.startMigration(USER_REQUESTED_REBUILD)
    //     → MigrationOps → this exact call, so this is the Worker half of the chain the readiness
    //     notice's remedy promises. The Head half (handler → op-lease → the knowledge client) is
    //     app-services' and is covered there; what could not be asserted from a fixture call is
    //     that the operation is even reachable in the braked state, which is where appServices is
    //     built from a read-only runtime.
    MigrationStartResponse rebuild =
        server
            .appServices()
            .ingestService()
            .startMigration(
                MigrationStartRequest.newBuilder()
                    .setReason(MigrationSource.USER_REQUESTED_REBUILD.wire())
                    .setRestartWorker(false)
                    .build(),
                CallContext.none());
    assertTrue(rebuild.getAccepted(), "the operator rebuild is reachable from here: "
        + rebuild.getError());
    assertNotNull(rebuild.getBuildingGenerationId(), "and it allocates a Green beside Blue");
    assertTrue(!rebuild.getBuildingGenerationId().isBlank());
    // A FRESH manager for everything after the boot. IndexGenerationManager caches state.json
    // per instance and invalidates only on its OWN writes, so the seeding instance above would
    // answer from a pre-boot snapshot and this arm would assert against state the Worker has since
    // replaced.
    IndexGenerationManager postBoot = new IndexGenerationManager(layout.indexBase());
    IndexGenerationManager.State promoted = postBoot.promoteBuildingGenerationToActive();
    assertNull(promoted.auto_rebuild_key(), "a completed rebuild clears the brake");
    assertEquals(
        0,
        postBoot.autoRebuildAttemptsFor(target),
        "the budget is restored, so a later genuine mismatch is not refused for this one");
  }


  /**
   * Tempdoc 915 (live validation D3). The braked verdict is right today only because
   * {@code safeSchemaCompatState()} tests the brake before it looks at the stored fingerprint. With
   * that fingerprint reported as the empty string, the moment an operator does what the brake's own
   * ERROR message tells them to do - clear {@code auto_rebuild_*} in state.json - the next boot has
   * to classify the same index again, and an empty stored value reads as BLOCKED_LEGACY: a
   * "this index predates the key" story about an index that carries a perfectly good, merely
   * different, fingerprint. This drives that exact sequence.
   */
  @Test
  // The `server = null` after the first close() is not dead: if
  // clearAutoRebuildFieldsByHand() throws before the second `server = new KnowledgeServer(...)`,
  // tearDown() reads this field and must not double-close the already-closed first server.
  @SuppressWarnings("PMD.UnusedAssignment")
  void clearingTheBudgetByHandMigratesAsAMismatchNotAsALegacyIndex(@TempDir Path tempDir)
      throws Exception {
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.seed(layout.activePath(), FOREIGN_SHAPE, 1);
    String target = WorkerBootFixture.currentFingerprint();
    for (int i = 0; i <= IndexGenerationManager.MAX_AUTO_REBUILD_ATTEMPTS; i++) {
      layout.genManager().recordAutoRebuildAttempt(target);
    }
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "BLUE_GREEN_MIGRATE");

    server = new KnowledgeServer(new io.justsearch.core.execution.TestEngineExecutors(), WorkerBootFixture.workerConfig(layout.dataDir()));
    server.start();
    assertTrue(server.rebuildBrakeExhaustedForTest(), "precondition: the brake is spent");

    StatusResponse braked =
        server
            .appServices()
            .ingestService()
            .indexStatus(StatusRequest.newBuilder().build(), CallContext.none());
    assertEquals(
        FOREIGN_SHAPE,
        braked.getCompatibility().getSchemaFpStored(),
        "the braked worker reports the shape Blue actually carries");
    server.close();
    server = null;

    clearAutoRebuildFieldsByHand(layout.indexBase());

    server = new KnowledgeServer(new io.justsearch.core.execution.TestEngineExecutors(), WorkerBootFixture.workerConfig(layout.dataDir()));
    server.start();
    assertFalse(
        server.rebuildBrakeExhaustedForTest(),
        "a cleared budget is a fresh budget");
    IndexGenerationManager.State after =
        new IndexGenerationManager(layout.indexBase()).initializeOrLoad().state();
    assertEquals(
        IndexGenerationManager.MigrationState.MIGRATING.name(),
        after.migration_state(),
        "the index is a MISMATCH - a different recorded shape - so it is migrated, not treated as"
            + " an index that never recorded one");
  }

  /** Exactly what the brake's ERROR message tells an operator to do: drop the three fields. */
  private static void clearAutoRebuildFieldsByHand(Path indexBase) throws Exception {
    Path statePath = indexBase.resolve("state.json");
    tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
    tools.jackson.databind.node.ObjectNode root =
        (tools.jackson.databind.node.ObjectNode) mapper.readTree(statePath.toFile());
    root.remove("auto_rebuild_key");
    root.remove("auto_rebuild_count");
    root.remove("auto_rebuild_first_ms");
    Files.writeString(statePath, mapper.writeValueAsString(root), StandardCharsets.UTF_8);
  }
}
