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
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.StatusRequest;
import io.justsearch.ipc.StatusResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * #758 — the repeat-rebuild brake when its state is lost, and what a
 * held boot reports for the rest of that boot.
 *
 * <p>When neither {@code state.json} nor {@code state.json.prev} can be read, whether a brake was
 * set cannot be told. Under the owner's rule (never act on uncertainty) no automatic rebuild
 * starts when the index would need one; the status names an unreadable brake with its own detail;
 * a person's rebuild still runs; and a compatible index boots and serves exactly as before. Before
 * this, the layout was re-established with a fresh record and the boot counted a first attempt.
 *
 * <p>The state is lost here the way a boot can reach: {@code state.json} is gone and its backup
 * does not parse. A {@code state.json} that is present but does not parse never gets this far: the
 * boot's ownership reconciliation fails closed on it and the Worker does not start, both before and
 * after this change.
 *
 * <p>And once a boot has held an automatic rebuild back, its status stays braked for that boot
 * (ingestion stays stopped), even when the brake record later reads as released.
 */
@Timeout(120)
final class LostBrakeStateBootTest {

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
      server = null;
    }
  }

  @Test
  void aLostStateOverAMismatchedIndexStartsNoAutomaticRebuild(@TempDir Path tempDir)
      throws Exception {
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.seed(layout.activePath(), FOREIGN_SHAPE, 1);
    loseBothStateFiles(layout);

    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "BLUE_GREEN_MIGRATE");
    server = new KnowledgeServer(
        new io.justsearch.core.execution.TestEngineExecutors(),
        WorkerBootFixture.workerConfig(layout.dataDir()));
    server.start();

    assertTrue(server.rebuildBrakeExhaustedForTest(),
        "the brake was lost with the state files, yet the boot started an automatic rebuild");
    assertNotNull(server.appServices(), "the held Worker still finished starting");
    IndexGenerationManager.State after =
        new IndexGenerationManager(layout.indexBase()).readStateBestEffort();
    assertNotNull(after, "the layout is re-established, so the Worker boots");
    assertNull(after.building_generation(), "no automatic rebuild generation was started");

    StatusResponse status = server.appServices().ingestService()
        .indexStatus(StatusRequest.newBuilder().build(), CallContext.none());
    assertEquals("BLOCKED_REBUILD_BRAKE", status.getCompatibility().getSchemaCompatState());
    assertEquals("rebuild_brake_state_lost", status.getCompatibility().getReindexRequiredReason(),
        "the status names a lost brake, which the Head shows as index.rebuild_brake_unreadable");
    assertTrue(status.getCompatibility().getReindexRequired());

    SearchResponse search = server.appServices().searchService()
        .search(SearchRequest.newBuilder().setQuery("*").setLimit(10).build(), CallContext.none());
    assertNotNull(search, "search still answers from the existing index");

    MigrationStartResponse rebuild = server.appServices().ingestService().startMigration(
        MigrationStartRequest.newBuilder()
            .setReason(MigrationSource.USER_REQUESTED_REBUILD.wire())
            .setRestartWorker(false)
            .build(),
        CallContext.none());
    assertTrue(rebuild.getAccepted(), "a person's rebuild is accepted: " + rebuild.getError());
    assertFalse(rebuild.getBuildingGenerationId().isBlank(), "and it allocates a Green beside Blue");
  }

  @Test
  void aLostStateOverACompatibleIndexBootsAndServesNormally(@TempDir Path tempDir)
      throws Exception {
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.seed(layout.activePath(), null, 1);
    loseBothStateFiles(layout);

    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "BLUE_GREEN_MIGRATE");
    server = new KnowledgeServer(
        new io.justsearch.core.execution.TestEngineExecutors(),
        WorkerBootFixture.workerConfig(layout.dataDir()));
    server.start();

    assertFalse(server.rebuildBrakeExhaustedForTest(),
        "a compatible index needs no rebuild, so nothing is held");
    assertNotNull(server.appServices(), "the Worker boots");
    StatusResponse status = server.appServices().ingestService()
        .indexStatus(StatusRequest.newBuilder().build(), CallContext.none());
    assertEquals("COMPATIBLE", status.getCompatibility().getSchemaCompatState());
    assertFalse(status.getCompatibility().getReindexRequired());
    SearchResponse search = server.appServices().searchService()
        .search(SearchRequest.newBuilder().setQuery("*").setLimit(10).build(), CallContext.none());
    assertNotNull(search, "and serves");
  }

  /**
   * A boot that held a rebuild back keeps ingestion stopped for the rest of the boot, so its status
   * stays braked even after the brake record reads as released. Here the boot held because the
   * brake record for a mismatched in-flight Green had no readable count, over a compatible Blue;
   * the record is then repaired on disk.
   */
  @Test
  void aHeldBootStaysBrakedAfterTheBrakeRecordBecomesReadable(@TempDir Path tempDir)
      throws Exception {
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.seed(layout.activePath(), null, 1);
    WorkerBootFixture.seedInFlightGreen(layout, FOREIGN_SHAPE, 1);
    layout.genManager().recordAutoRebuildAttempt(WorkerBootFixture.currentFingerprint());
    Path statePath = layout.indexBase().resolve("state.json");
    String json = Files.readString(statePath, StandardCharsets.UTF_8);
    String withoutCount =
        json.replaceAll("\"auto_rebuild_count\"\\s*:\\s*\\d+", "\"auto_rebuild_count\" : null");
    assertTrue(!json.equals(withoutCount), "precondition: the brake count is recorded");
    Files.writeString(statePath, withoutCount, StandardCharsets.UTF_8);

    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "BLUE_GREEN_MIGRATE");
    server = new KnowledgeServer(
        new io.justsearch.core.execution.TestEngineExecutors(),
        WorkerBootFixture.workerConfig(layout.dataDir()));
    server.start();
    assertTrue(server.rebuildBrakeExhaustedForTest(), "precondition: the boot held the rebuild");

    String afterBoot = Files.readString(statePath, StandardCharsets.UTF_8);
    assertFalse(afterBoot.contains("\"auto_rebuild_count\""),
        "precondition: the brake record still has no readable count");
    String repaired = afterBoot.replaceFirst(
        "(\"auto_rebuild_key\"\\s*:\\s*\"[^\"]*\")", "$1,\n  \"auto_rebuild_count\" : 1");
    assertTrue(!afterBoot.equals(repaired), "precondition: the brake record names this target");
    Files.writeString(statePath, repaired, StandardCharsets.UTF_8);
    assertEquals(
        IndexGenerationManager.AutoRebuildBrake.RELEASED,
        new IndexGenerationManager(layout.indexBase())
            .autoRebuildBrakeFor(WorkerBootFixture.currentFingerprint()),
        "precondition: the record now reads as released");

    StatusResponse status = server.appServices().ingestService()
        .indexStatus(StatusRequest.newBuilder().build(), CallContext.none());
    assertEquals("BLOCKED_REBUILD_BRAKE", status.getCompatibility().getSchemaCompatState(),
        "ingestion is still stopped in this boot, so the status must still say braked");
    assertEquals("rebuild_brake_unreadable", status.getCompatibility().getReindexRequiredReason(),
        "and name what held it");
    assertTrue(status.getCompatibility().getReindexRequired());
  }

  /** Neither record readable: the current one is gone, and its backup does not parse. */
  private static void loseBothStateFiles(WorkerBootFixture.Layout layout) throws Exception {
    Path state = layout.indexBase().resolve("state.json");
    Path prev = layout.indexBase().resolve("state.json.prev");
    assertTrue(Files.exists(state), "precondition: a state record was written");
    Files.delete(state);
    Files.writeString(prev, "{ not json", StandardCharsets.UTF_8);
  }
}
