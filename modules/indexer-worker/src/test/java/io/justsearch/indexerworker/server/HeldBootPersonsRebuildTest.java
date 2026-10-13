/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.status.MigrationSource;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.ipc.BatchRequest;
import io.justsearch.ipc.MigrationStartRequest;
import io.justsearch.ipc.MigrationStartResponse;
import io.justsearch.ipc.SearchRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task t-20261013-ef3c41, round 4 — after a person's explicit rebuild in a boot the rebuild brake
 * held, new and changed files are indexed again without any further action by the person.
 *
 * <p>The person's rebuild is driven the way the Engine drives it: the Worker accepts the migration
 * start, then the Engine asks the server to open the new generation live, with an Engine restart
 * as the fallback. Every restart the server asks for is carried out here the way the supervisor
 * carries it out — the server is closed and a new one boots over the same data directory — and no
 * other step is taken. The file is written and submitted after the rebuild, and must become
 * searchable.
 */
@Timeout(240)
final class HeldBootPersonsRebuildTest {
  private static final String FOREIGN_SHAPE = "f".repeat(64);
  private static final int MAX_BOOTS = 4;

  private KnowledgeServer server;

  @AfterEach
  void tearDown() {
    closeQuietly(server);
  }

  @Test
  void aPersonsRebuildInAHeldBootIndexesNewFilesWithoutFurtherAction(@TempDir Path tempDir)
      throws Exception {
    // A boot the brake holds: a rebuild that keeps failing the same way has spent its budget.
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.seed(layout.activePath(), null, 1);
    WorkerBootFixture.seedInFlightGreen(layout, FOREIGN_SHAPE, 1);
    String target = WorkerBootFixture.currentFingerprint();
    for (int i = 0; i <= IndexGenerationManager.MAX_AUTO_REBUILD_ATTEMPTS; i++) {
      layout.genManager().recordAutoRebuildAttempt(target);
    }
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "BLUE_GREEN_MIGRATE");
    AtomicInteger restarts = new AtomicInteger();
    server = boot(layout, restarts);
    assertTrue(server.rebuildBrakeExhaustedForTest(), "precondition: the boot held the rebuild");

    // The person's rebuild, as the Engine sends it.
    MigrationStartResponse rebuild = server.appServices().ingestService().startMigration(
        MigrationStartRequest.newBuilder()
            .setReason(MigrationSource.USER_REQUESTED_REBUILD.wire())
            .build(),
        CallContext.none());
    assertTrue(rebuild.getAccepted(), "the person's rebuild is accepted: " + rebuild.getError());
    boolean live = server
        .beginUnrecordedBuildingLiveAsync(rebuild.getBuildingGenerationId(), restarts::incrementAndGet)
        .get(60, TimeUnit.SECONDS);
    assertTrue(live || restarts.get() > 0,
        "the rebuild neither opened live nor asked for the restart that would build it");

    // A file the person adds after the rebuild.
    Path docs = Files.createDirectories(tempDir.resolve("docs"));
    Path added = Files.writeString(docs.resolve("added-after-rebuild.txt"),
        "zebrafinch quartzite lantern", java.nio.charset.StandardCharsets.UTF_8);

    int boots = 1;
    int handled = 0;
    boolean submitted = false;
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
    while (System.nanoTime() < deadline) {
      if (restarts.get() > handled) {
        // The supervisor's part, and nothing more: the Engine exits and starts again.
        handled = restarts.get();
        assertTrue(++boots <= MAX_BOOTS, "the rebuild kept asking for restarts: " + boots);
        closeQuietly(server);
        server = boot(layout, restarts);
        submitted = false;
      }
      if (!submitted) {
        server.appServices().ingestService().submitBatch(
            BatchRequest.newBuilder().addFilePaths(added.toString()).build(), CallContext.none());
        submitted = true;
      }
      if (searchable(server, "zebrafinch")) {
        assertFalse(server.rebuildBrakeExhaustedForTest(),
            "the serving boot is no longer held");
        return;
      }
      Thread.sleep(250);
    }
    throw new AssertionError("the file added after the person's rebuild never became searchable"
        + " (boots=" + boots + ", restarts asked=" + restarts.get() + ", held="
        + server.rebuildBrakeExhaustedForTest() + ")");
  }

  private static KnowledgeServer boot(WorkerBootFixture.Layout layout, AtomicInteger restarts)
      throws Exception {
    KnowledgeServer booted = new KnowledgeServer(
        new TestEngineExecutors(), WorkerBootFixture.workerConfig(layout.dataDir()));
    booted.onMigrationRestart(restarts::incrementAndGet);
    booted.start();
    return booted;
  }

  private static boolean searchable(KnowledgeServer server, String term) {
    try {
      return server.appServices().searchService()
          .search(SearchRequest.newBuilder().setQuery(term).setLimit(10).build(), CallContext.none())
          .getTotalHits() > 0;
    } catch (RuntimeException notYet) {
      return false;
    }
  }

  private static void closeQuietly(KnowledgeServer server) {
    if (server == null) {
      return;
    }
    try {
      server.close();
    } catch (Exception ignored) {
      // best-effort; the assertions have already run
    }
  }
}
