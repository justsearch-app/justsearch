/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.ipc.StatusResponse;
import io.justsearch.ipc.SyncDirectoryResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Lane F stage A item A12 — the in-process replacement for
 * {@code systemtests.process.SwitchingFenceBufferingE2ETest}.
 *
 * <p><b>The property, and why it survives.</b> While a migration is in {@code SWITCHING} the index
 * has two generations and no safe one to mutate, so mutations are not rejected and not applied —
 * they are written to a durable switch buffer and replayed onto the new active generation after the
 * cutover. That is a property of the <em>index</em> and its buffer, and the retired test needed a
 * Worker process only because that was the only way to reach either. All three buffered operation
 * kinds are kept here: an UPSERT, a DELETE, and a deferred SYNC_ROOT.
 *
 * <p><b>What changed, and what it cost.</b> Two things.
 *
 * <ol>
 *   <li>"The worker restarted for cutover" becomes {@link EngineTestHarness#restart()} — see
 *       {@code EngineMigrationLifecycleTest}'s javadoc for why that is the honest translation.
 *   <li>The retired test forced {@code SWITCHING} with a single-document corpus and then injected
 *       its three mutations. In one JVM that is a race it would usually lose: the cutover monitor
 *       promotes as soon as the queue is drained (KnowledgeServerMigrationOps.java:255-268), and an
 *       empty queue drains before the next call lands. So this test gives the migration a backlog
 *       to chew on — the fence stays up while the enqueued corpus drains, which is the same state
 *       the retired test was trying to catch, held open long enough to be deterministic rather than
 *       lucky. The backlog is not the subject; it is the clock.
 * </ol>
 */
@Timeout(900)
final class EngineSwitchingFenceBufferingTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** Enough queued work that SWITCHING stays up for seconds, not milliseconds. See javadoc (2). */
  private static final int BACKLOG_FILES = 150;

  private EngineTestHarness engine;

  @AfterEach
  void tearDown() {
    if (engine != null) {
      engine.close();
      engine = null;
    }
  }

  @Test
  @DisplayName("mutations during SWITCHING are buffered and replayed onto the new generation")
  void mutationsDuringSwitchingAreBufferedAndReplayed(@TempDir Path tempDir) throws Exception {
    Path dataDir = tempDir.resolve("data");
    Path watchedRoot = dataDir.resolve("switching-root");
    Files.createDirectories(watchedRoot);

    String blueMarker = "BM" + UUID.randomUUID().toString().replace("-", "");
    Path blueFile = watchedRoot.resolve("blue.txt");
    Files.writeString(blueFile, "hello " + blueMarker);
    for (int i = 0; i < BACKLOG_FILES; i++) {
      Files.writeString(watchedRoot.resolve("backlog-" + i + ".txt"), "switching backlog " + i);
    }
    writeWatchedRoots(dataDir, watchedRoot);

    engine = EngineTestHarness.start(dataDir);

    assertTrue(
        engine.client().submitBatch(List.of(blueFile), TestEngineContexts.FOREGROUND).getAcceptedCount() > 0,
        "the blue file must be accepted");
    assertTrue(engine.awaitSearchable(blueMarker, 120_000), "the blue doc must be searchable");

    String blueDocId = PathNormalizer.normalizeKey(blueFile);
    assertFalse(blueDocId.isBlank(), "the blue doc_id must normalize");

    String activeBefore = engine.status().getMigration().getActiveGenerationId();
    assertFalse(activeBefore.isBlank(), "active_generation_id must be present");

    assertTrue(
        engine.client().startMigration("system_test_switching", TestEngineContexts.FOREGROUND).accepted(), "startMigration must be accepted");
    engine.restart();

    assertTrue(engine.client().requestCutover(true, TestEngineContexts.FOREGROUND).accepted(), "requestCutover must be accepted");
    assertTrue(awaitMigrationState("SWITCHING", 60_000), "migration_state must reach SWITCHING");

    // --- The three buffered operation kinds, all issued behind the fence. ---

    // UPSERT.
    String greenMarker = "GM" + UUID.randomUUID().toString().replace("-", "");
    Path greenFile = dataDir.resolve("switching-newfile.txt");
    Files.writeString(greenFile, "hello " + greenMarker);
    assertEquals(
        1,
        engine.client().submitBatch(List.of(greenFile), TestEngineContexts.FOREGROUND).getAcceptedCount(),
        "an upsert during SWITCHING must be accepted (buffered), not refused");

    // DELETE.
    assertTrue(
        engine.client().deleteById(blueDocId, TestEngineContexts.FOREGROUND).getSuccess(),
        "a delete during SWITCHING must be accepted (buffered), not refused");

    // SYNC_ROOT — a separate root so it does not re-enqueue or undo the delete above.
    Path syncRoot = dataDir.resolve("switching-sync-root");
    Files.createDirectories(syncRoot);
    String syncMarker = "SM" + UUID.randomUUID().toString().replace("-", "");
    Files.writeString(syncRoot.resolve("sync.txt"), "hello " + syncMarker);
    SyncDirectoryResponse syncResp = engine.client().syncDirectory(syncRoot.toString(), true, TestEngineContexts.FOREGROUND);
    assertTrue(
        syncResp.getDeferredToSwitchBuffer()
            || syncResp.getError().toUpperCase(Locale.ROOT).contains("DEFERRED"),
        "syncDirectory during SWITCHING must be deferred to the switch buffer; got error='"
            + syncResp.getError() + "' deferred=" + syncResp.getDeferredToSwitchBuffer());

    assertTrue(
        engine.status().getMigration().getSwitchBufferDepth() > 0,
        "switch_buffer_depth must be non-zero while the fence holds buffered mutations");

    // --- Cutover, then the replay. ---

    assertTrue(
        awaitActiveGenerationChanged(engine.indexBase(), activeBefore, 300_000),
        "the cutover must promote the building generation");
    engine.restart();

    // Polled, and the assertions below read the SNAPSHOT THE POLL RETURNED rather than taking a
    // fresh one. See EngineMigrationLifecycleTest#awaitMigrationState: after a restart the status
    // projection can transiently report an empty migration state, and it can do so AGAIN on the
    // very next call — so "poll until IDLE, then re-read and assert" is a race that fails on the
    // second read. Asserting on the settled observation is the honest form of the same claim.
    StatusResponse after = awaitMigrationStateSnapshot("IDLE", 60_000);
    assertEquals("IDLE", after.getMigration().getMigrationState(), "IDLE after cutover");
    assertNotEquals(
        activeBefore,
        after.getMigration().getActiveGenerationId(),
        "the active generation must have changed");

    assertTrue(
        engine.awaitSearchable(greenMarker, 120_000),
        "the buffered UPSERT must be replayed and indexed after the cutover");
    assertTrue(
        engine.awaitSearchable(syncMarker, 120_000),
        "the buffered SYNC_ROOT must be replayed and indexed after the cutover");
    assertTrue(
        engine.awaitNotSearchable(blueMarker, 120_000),
        "the buffered DELETE must be replayed — the blue doc must be gone from the new active"
            + " generation. blueDocId=" + blueDocId
            + " switchBufferDepth=" + engine.status().getMigration().getSwitchBufferDepth()
            + " activeGenerationId=" + engine.status().getMigration().getActiveGenerationId());

    long drainDeadline = System.currentTimeMillis() + 60_000;
    while (System.currentTimeMillis() < drainDeadline
        && engine.status().getMigration().getSwitchBufferDepth() != 0L) {
      Thread.sleep(250);
    }
    assertEquals(
        0L,
        engine.status().getMigration().getSwitchBufferDepth(),
        "the switch buffer must drain once every buffered operation has been replayed");
  }

  // =========================================================================

  private boolean awaitMigrationState(String expected, long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      String state = engine.status().getMigration().getMigrationState();
      if (expected.equalsIgnoreCase(state.trim())) {
        return true;
      }
      Thread.sleep(100);
    }
    return false;
  }

  /** As {@link #awaitMigrationState}, returning the snapshot the assertions must be made against. */
  private StatusResponse awaitMigrationStateSnapshot(String expected, long timeoutMs)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    StatusResponse last = engine.status();
    while (System.currentTimeMillis() < deadline) {
      last = engine.status();
      String state = last.getMigration().getMigrationState();
      if (expected.equalsIgnoreCase(state.trim())) {
        return last;
      }
      Thread.sleep(100);
    }
    return last;
  }

  private static void writeWatchedRoots(Path dataDir, Path root) throws IOException {
    Files.createDirectories(dataDir);
    JSON.writeValue(
        dataDir.resolve("watched_roots.json").toFile(),
        Map.of("roots", List.of(Map.of("path", root.toString()))));
  }

  /** See {@code EngineMigrationLifecycleTest#awaitActiveGenerationChanged} for why state.json. */
  private static boolean awaitActiveGenerationChanged(Path indexBase, String before, long timeoutMs)
      throws Exception {
    Path statePath = indexBase.resolve("state.json");
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      if (Files.exists(statePath)) {
        try {
          JsonNode state = JSON.readTree(Files.readString(statePath, StandardCharsets.UTF_8));
          String active = state.path("active_generation").asText();
          if (!active.isBlank() && !active.equals(before)) {
            return true;
          }
        } catch (RuntimeException midWrite) {
          // state.json is written via a tmp file and renamed; a torn read just retries.
        }
      }
      Thread.sleep(250);
    }
    return false;
  }
}
