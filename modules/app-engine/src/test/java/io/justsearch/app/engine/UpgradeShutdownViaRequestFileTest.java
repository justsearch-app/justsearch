/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.engine.ShutdownRequest.Reason;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * Stage B item B6 — the upgrade path now runs through the request file, and the receipt survives it.
 *
 * <p>{@code commit-shutdown} used to validate the nonce and then invoke the ordered close directly
 * on a daemon thread. It now writes B2's request file instead, and B3's watcher runs the close. The
 * risk that change introduces is precise: the receipt is the updater's only proof the Engine stopped
 * cleanly, and it is nonce- and preparation-bound. If either identifier failed to survive the trip
 * through the file, every upgrade would end in a receipt the updater rejects — which presents as an
 * update that silently never completes.
 *
 * <p>So this drives the whole new path end to end: request file in, receipt out, both identifiers
 * checked on the far side.
 */
@DisplayName("upgrade shutdown through the request file (stage B item B6)")
final class UpgradeShutdownViaRequestFileTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** The production wiring's callback, kept here so the test exercises the real branch. */
  private static void dispatch(EngineShutdownSequence sequence, ShutdownRequest request) {
    if (request.reason() == Reason.UPGRADE && request.preparationId() != null) {
      sequence.runAndExitWithReceipt(request.preparationId(), request.nonce());
    } else {
      sequence.runAndExit(request.reason());
    }
  }

  @Test
  @DisplayName("an upgrade request produces a receipt carrying its nonce and preparation id")
  void upgradeRequestProducesANonceBoundReceipt(@TempDir Path dataDir) throws Exception {
    Path runtime = Files.createDirectories(dataDir.resolve("runtime"));
    var exitCode = new AtomicInteger(-1);
    var sequence =
        new EngineShutdownSequence(
            dataDir,
            List.of(new EngineShutdownSequence.Step(
                EngineShutdownSequence.INDEX_HALF_STEP, r -> "GRACEFUL")),
            exitCode::set);

    // Exactly what the installed UpgradeShutdownAction writes.
    new ShutdownRequest(Reason.UPGRADE, System.currentTimeMillis() + 120_000L,
            "nonce-1", "upgrade-controller", "prep-1")
        .writeTo(runtime);

    try (var watcher =
        new ShutdownRequestWatcher(runtime, r -> true, r -> dispatch(sequence, r), 50L)) {
      watcher.pollOnce();
    }

    Path receipt = dataDir.resolve("upgrade").resolve(EngineShutdownSequence.RECEIPT_FILE);
    assertTrue(
        Files.isRegularFile(receipt),
        "no receipt means the updater reads the upgrade as 'did not stop cleanly' and stops");
    var body = JSON.readTree(Files.readString(receipt));
    assertEquals("nonce-1", body.get("shutdownNonce").stringValue());
    assertEquals(
        "prep-1",
        body.get("preparationId").stringValue(),
        "both identifiers have to survive the trip through the file; before B6 they were passed"
            + " straight to the coordinator in memory");
    assertTrue(body.get("clean").asBoolean());
    assertEquals(0, exitCode.get());
  }

  @Test
  @DisplayName("a quit request writes no receipt — the receipt belongs to the upgrade path alone")
  void quitRequestWritesNoReceipt(@TempDir Path dataDir) throws Exception {
    Path runtime = Files.createDirectories(dataDir.resolve("runtime"));
    var sequence =
        new EngineShutdownSequence(dataDir, List.of(), code -> {});

    new ShutdownRequest(Reason.QUIT, 1L, null, "shell", null).writeTo(runtime);
    try (var watcher =
        new ShutdownRequestWatcher(runtime, r -> true, r -> dispatch(sequence, r), 50L)) {
      watcher.pollOnce();
    }

    assertFalse(
        Files.exists(dataDir.resolve("upgrade").resolve(EngineShutdownSequence.RECEIPT_FILE)),
        "a receipt for a shutdown no updater asked for would be a forged HEAD_STOPPED witness —"
            + " design 7.3 is explicit that the dead-Engine path must never see one");
  }

  @Test
  @DisplayName("an upgrade request without a preparation id does not forge a receipt")
  void upgradeWithoutPreparationIdWritesNoReceipt(@TempDir Path dataDir) throws Exception {
    Path runtime = Files.createDirectories(dataDir.resolve("runtime"));
    var sequence = new EngineShutdownSequence(dataDir, List.of(), code -> {});

    // A supervisor-issued upgrade (B8/B10 will write these) carries no preparation id, because no
    // commit-shutdown ran. It must still shut down, and must NOT produce a receipt the updater
    // would read as proof of a preparation that never happened.
    new ShutdownRequest(Reason.UPGRADE, 1L, null, "supervisor", null).writeTo(runtime);
    try (var watcher =
        new ShutdownRequestWatcher(runtime, r -> true, r -> dispatch(sequence, r), 50L)) {
      watcher.pollOnce();
    }

    assertTrue(sequence.resultIfRun() != null, "it must still perform the ordered close");
    assertFalse(
        Files.exists(dataDir.resolve("upgrade").resolve(EngineShutdownSequence.RECEIPT_FILE)));
  }
}
