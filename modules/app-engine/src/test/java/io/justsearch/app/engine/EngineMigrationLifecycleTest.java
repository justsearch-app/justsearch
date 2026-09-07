/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.ipc.StatusResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Lane F stage A item A12 — the in-process replacement for three retired system tests:
 * {@code systemtests.process.MigrationControlE2ETest}, {@code ...RollbackE2ETest} and
 * {@code ...PauseResumeMigrationE2ETest}.
 *
 * <p><b>What survives the collapse.</b> Blue/Green migration is a property of the <em>index</em>,
 * not of the process pair: a building generation is created, the enumerator fills it, the pointer
 * in {@code state.json} is promoted, the previous generation is remembered, and a rollback puts the
 * pointer back — all of it inside {@code IndexGenerationManager}
 * ({@code modules/worker-core/.../index/IndexGenerationManager.java}). None of that needed a second
 * process; the retired tests only used one because that was the only way to reach the index at all.
 *
 * <p><b>What "the worker restarted" becomes.</b> The retired tests drove the restart directly:
 * {@code startMigration}/{@code rollbackMigration} set {@code restart_worker=true}, the worker
 * exited, and the test called {@code spawnWorker()} again. Under the Engine that callback is
 * {@code KnowledgeServer#initiateShutdown} (KnowledgeServer.java:2109-2112) — a latch countdown, not
 * a {@code System.exit} — and the composition root owns what happens next. So the equivalent here
 * is {@link EngineTestHarness#restart()}: close the Engine, re-open it on the same data directory.
 * Everything the old restart re-read ({@code state.json}, the job queue, the switch buffer) is on
 * disk, so the reopened Engine sees exactly what a respawned process saw. The assertions about
 * <em>the index</em> are unchanged; the assertions about a PID terminating are gone with the PID.
 *
 * <p><b>One deliberate speed-up, stated rather than hidden.</b> The retired
 * {@code MigrationControlE2ETest} waited for the cutover monitor to reach its drain criteria on its
 * own (a 60 s budget). This test forces {@code SWITCHING} with {@code requestCutover(true)} the way
 * {@code RollbackE2ETest} already did, because the property under test is the <em>pointer swap and
 * its consequences</em>, not the monitor's own drain heuristic. The drain criteria still run — the
 * force only sets the state the monitor is waiting for.
 */
@Timeout(600)
final class EngineMigrationLifecycleTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private EngineTestHarness engine;

  @AfterEach
  void tearDown() {
    if (engine != null) {
      engine.close();
      engine = null;
    }
  }

  @Test
  @DisplayName("a forced cutover swaps the active generation, remembers the previous one, and the"
      + " document is still searchable afterwards")
  void cutoverSwapsTheGenerationPointerAndPreservesSearch(@TempDir Path tempDir) throws Exception {
    Path dataDir = tempDir.resolve("data");
    Path docsDir = dataDir.resolve("migration-docs");
    Files.createDirectories(docsDir);

    String marker = "migrationmarker" + System.nanoTime();
    Path file = docsDir.resolve("hello.txt");
    Files.writeString(file, "hello " + marker);
    writeWatchedRoots(dataDir, docsDir);

    engine = EngineTestHarness.start(dataDir);

    assertTrue(
        engine.client().submitBatch(List.of(file)).getAcceptedCount() > 0,
        "the file must be accepted for indexing");
    assertTrue(engine.awaitIndexed(1, 120_000), "indexing must complete");
    assertTrue(engine.awaitSearchable(marker, 60_000), "the marker must be searchable before migration");

    String activeBefore = engine.status().getMigration().getActiveGenerationId();
    assertFalse(activeBefore.isBlank(), "active_generation_id must be set before migration");

    assertTrue(engine.client().startMigration("system_test"), "startMigration must be accepted");

    // The restart the migration asked for. Under the Engine the composition root performs it.
    engine.restart();

    assertTrue(engine.client().requestCutover(true), "requestCutover must be accepted");

    // The cutover monitor promotes the building generation and writes state.json BEFORE it asks for
    // the restart (KnowledgeServerMigrationOps.java:261-268), so the file is the observable that
    // replaces "the process exited".
    assertTrue(
        awaitActiveGenerationChanged(engine.indexBase(), activeBefore, 180_000),
        "the cutover must promote the building generation; state.json still reads " + activeBefore);

    engine.restart();

    StatusResponse after = awaitMigrationState("IDLE", 60_000);
    assertEquals(
        "IDLE",
        after.getMigration().getMigrationState(),
        "migration_state must be IDLE once the cutover has completed");
    assertNotEquals(
        activeBefore,
        after.getMigration().getActiveGenerationId(),
        "the active generation must change after cutover");
    assertEquals(
        activeBefore,
        after.getMigration().getPreviousGenerationId(),
        "the old active generation must be remembered as previous — this is what rollback needs");

    assertTrue(
        engine.awaitSearchable(marker, 120_000),
        "the marker must still be findable on the new active generation");
  }

  @Test
  @DisplayName("rollback returns the active generation to the previous one and search still works")
  void rollbackRevertsTheGenerationPointer(@TempDir Path tempDir) throws Exception {
    Path dataDir = tempDir.resolve("data");
    Path docsDir = dataDir.resolve("rollback-root");
    Files.createDirectories(docsDir);

    String marker = "rollbackmarker" + System.nanoTime();
    Path file = docsDir.resolve("doc.txt");
    Files.writeString(file, "hello " + marker);
    writeWatchedRoots(dataDir, docsDir);

    engine = EngineTestHarness.start(dataDir);

    assertTrue(
        engine.client().submitBatch(List.of(file)).getAcceptedCount() > 0,
        "the file must be accepted for indexing");
    assertTrue(engine.awaitIndexed(1, 120_000), "indexing must complete");
    assertTrue(engine.awaitSearchable(marker, 60_000), "the doc must be searchable");

    String activeBefore = engine.status().getMigration().getActiveGenerationId();
    assertFalse(activeBefore.isBlank(), "active_generation_id must be present");

    assertTrue(engine.client().startMigration("system_test_rollback"), "startMigration accepted");
    engine.restart();
    assertTrue(engine.client().requestCutover(true), "requestCutover must be accepted");
    assertTrue(
        awaitActiveGenerationChanged(engine.indexBase(), activeBefore, 180_000),
        "the cutover must promote the building generation");
    engine.restart();

    StatusResponse afterCutover = awaitMigrationState("IDLE", 60_000);
    assertEquals("IDLE", afterCutover.getMigration().getMigrationState(), "IDLE after cutover");
    String activeAfterCutover = afterCutover.getMigration().getActiveGenerationId();
    assertNotEquals(activeBefore, activeAfterCutover, "the active generation must have changed");
    assertEquals(
        activeBefore,
        afterCutover.getMigration().getPreviousGenerationId(),
        "previous must be the old active generation");

    assertTrue(engine.client().rollbackMigration(), "rollback must be accepted");
    engine.restart();

    StatusResponse afterRollback = awaitActiveGeneration(activeBefore, 60_000);
    assertEquals(
        activeBefore,
        afterRollback.getMigration().getActiveGenerationId(),
        "rollback must put the active pointer back to the pre-cutover generation");
    assertEquals(
        activeAfterCutover,
        afterRollback.getMigration().getPreviousGenerationId(),
        "and the two pointers must have swapped, not merely reset");

    assertTrue(
        engine.awaitSearchable(marker, 120_000),
        "the marker must still be findable after the rollback");
  }

  @Test
  @DisplayName("pause holds the enumerator and the cutover; resume releases both")
  void pauseHoldsTheMigrationAndResumeReleasesIt(@TempDir Path tempDir) throws Exception {
    Path dataDir = tempDir.resolve("data");
    Path watchedRoot = dataDir.resolve("pause-root");
    Files.createDirectories(watchedRoot);

    // The retired PauseResumeMigrationE2ETest wrote 1 200 files and paused mid-enumeration, then
    // asserted that two samples of files_seen agreed. That shape does not survive the collapse for
    // a reason worth recording: in one JVM the restart is a close-and-reopen rather than a process
    // spawn, so the enumerator finishes a 1 200-file corpus long before the pause RPC can land —
    // measured on this branch, the sample pair agreed at 1 200/1 200, i.e. the old assertion
    // passed because there was nothing left to enumerate, and then "resume let it continue" could
    // not be true either. That is the wrong-reason failure mode the assertion was meant to catch.
    //
    // So the pause is applied BEFORE the restart, while the state is MIGRATING (pauseMigration
    // refuses only during SWITCHING — MigrationControlOps.java:130-136). The flag is then already
    // in state.json when the enumerator and the cutover monitor start, which makes the assertions
    // below deterministic rather than a race: the enumerator must not have finished, and the
    // monitor must not have switched, because the pause gate is the first thing both consult
    // (KnowledgeServerMigrationOps.java:128-130 and :898).
    int fileCount = 24;
    for (int i = 0; i < fileCount; i++) {
      Files.writeString(watchedRoot.resolve("f-" + i + ".txt"), "pause probe " + i);
    }
    writeWatchedRoots(dataDir, watchedRoot);

    engine = EngineTestHarness.start(dataDir);
    assertTrue(engine.client().startMigration("pause_resume_test"), "startMigration accepted");
    assertTrue(engine.client().pauseMigration("system_test"), "pauseMigration must be accepted");

    engine.restart();

    StatusResponse paused = engine.status();
    assertTrue(paused.getMigration().getPaused(), "migration_paused must survive the restart");
    assertEquals(
        "MIGRATING",
        paused.getMigration().getMigrationState().trim().toUpperCase(java.util.Locale.ROOT),
        "a paused migration must stay MIGRATING");

    // Hold the window open and check the gate the whole time. Both halves matter: the enumerator
    // must not walk (the old test's assertion) and the monitor must not switch (the consequence
    // that assertion existed to protect).
    long seenAtPause = paused.getMigration().getEnumerator().getFilesSeen();
    long holdDeadline = System.currentTimeMillis() + 8_000;
    while (System.currentTimeMillis() < holdDeadline) {
      StatusResponse s = engine.status();
      assertTrue(s.getMigration().getPaused(), "the pause must stay set for the whole window");
      assertNotEquals(
          "SWITCHING",
          s.getMigration().getMigrationState().trim().toUpperCase(java.util.Locale.ROOT),
          "a paused migration must never enter SWITCHING");
      assertEquals(
          seenAtPause,
          s.getMigration().getEnumerator().getFilesSeen(),
          "the enumerator must not walk while the migration is paused");
      assertFalse(
          s.getMigration().getEnumerator().getDone(),
          "the enumerator must not report done while paused — if it does, this window proved"
              + " nothing about the pause gate, only that there was no work left");
      Thread.sleep(500);
    }

    assertTrue(engine.client().resumeMigration(), "resumeMigration must be accepted");

    // Release: the enumerator finishes the corpus it was held off, and the monitor gets to act.
    long resumeDeadline = System.currentTimeMillis() + 120_000;
    boolean released = false;
    while (System.currentTimeMillis() < resumeDeadline) {
      StatusResponse s = engine.status();
      if (s.getMigration().getEnumerator().getDone()
          && s.getMigration().getEnumerator().getFilesSeen() > seenAtPause) {
        released = true;
        break;
      }
      Thread.sleep(250);
    }
    assertTrue(
        released,
        "resume must let the enumerator finish the corpus it was held off; files_seen was stuck at "
            + seenAtPause);
    assertFalse(engine.status().getMigration().getPaused(), "the pause flag must be cleared");
  }

  // =========================================================================

  /**
   * Polls the status until {@code migration_state} settles on {@code expected}, and returns that
   * snapshot (or the last one seen, so the caller's {@code assertEquals} reports what it got).
   *
   * <p><b>Why a poll and not a single read.</b> Observed 2026-09-07 on the whole-repo
   * {@code ./gradlew test}: the read immediately after a restart returned {@code migration_state=""}
   * — not "SWITCHING", not a stale value, empty. Empty means {@code IndexStatusOps} saw a
   * {@code null} state snapshot (IndexStatusOps.java:658-661), which means
   * {@code readStateBestEffort()} found neither {@code state.json} nor {@code state.json.prev}
   * readable at that instant. {@code writeState} replaces the file by two renames
   * (IndexGenerationManager.java:855-873) and the outgoing server's cutover monitor can still be
   * inside that window when the reopened Engine reads, which a loaded machine widens. So the claim
   * this test makes is a settling one — "the migration ends up IDLE" — and asserting it on the first
   * sample was asserting something narrower and untrue. The deadline keeps it a real liveness
   * assertion: a migration that never settles still fails.
   */
  private StatusResponse awaitMigrationState(String expected, long timeoutMs)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    StatusResponse last = engine.status();
    while (System.currentTimeMillis() < deadline) {
      last = engine.status();
      if (expected.equalsIgnoreCase(last.getMigration().getMigrationState().trim())) {
        return last;
      }
      Thread.sleep(200);
    }
    return last;
  }

  /** The rollback half of {@link #awaitMigrationState}: settle on an expected active generation. */
  private StatusResponse awaitActiveGeneration(String expectedActive, long timeoutMs)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    StatusResponse last = engine.status();
    while (System.currentTimeMillis() < deadline) {
      last = engine.status();
      if (expectedActive.equals(last.getMigration().getActiveGenerationId())) {
        return last;
      }
      Thread.sleep(200);
    }
    return last;
  }

  /** Writes the persisted watched-roots file the migration enumerator prefers over config roots. */
  private static void writeWatchedRoots(Path dataDir, Path root) throws IOException {
    Files.createDirectories(dataDir);
    JSON.writeValue(
        dataDir.resolve("watched_roots.json").toFile(),
        Map.of("roots", List.of(Map.of("path", root.toString()))));
  }

  /**
   * Polls {@code <indexBase>/state.json} until {@code active_generation} differs from
   * {@code before}. This file is the cutover's durable record — {@code IndexGenerationManager}
   * writes it at {@code basePath/state.json} (IndexGenerationManager.java:138) and the cutover
   * monitor promotes into it just before it asks for a restart
   * (KnowledgeServerMigrationOps.java:261-268).
   */
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
