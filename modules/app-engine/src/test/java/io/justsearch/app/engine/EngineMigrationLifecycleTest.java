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
 * in {@code state.json} is promoted and the previous generation is remembered until retired —
 * all of it inside {@code IndexGenerationManager}
 * ({@code modules/worker-core/.../index/IndexGenerationManager.java}). None of that needed a second
 * process; the retired tests only used one because that was the only way to reach the index at all.
 *
 * <p>Promotion publishes the building generation into the live Engine before the control call
 * completes. {@link EngineTestHarness#restart()} is an explicit test action used to verify that
 * the promoted pointer and its recovery state remain durable after reopening the data directory.
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
  @DisplayName("live cutover retains Blue for a held view, then retires it after release")
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
        engine.client().submitBatch(List.of(file), TestEngineContexts.FOREGROUND).getAcceptedCount() > 0,
        "the file must be accepted for indexing");
    assertTrue(engine.awaitIndexed(1, 120_000), "indexing must complete");
    assertTrue(engine.awaitSearchable(marker, 60_000), "the marker must be searchable before migration");

    String activeBefore = engine.status().getMigration().getActiveGenerationId();
    assertFalse(activeBefore.isBlank(), "active_generation_id must be set before migration");

    assertTrue(engine.client().startMigration("system_test", TestEngineContexts.FOREGROUND).accepted(), "startMigration must be accepted");

    // An explicit restart, not a simulation of one: startMigration re-cuts the layout and the
    // Engine has no in-place reopen (see below).
    engine.restart();

    Path bluePath = engine.indexBase().resolve("indices").resolve(activeBefore);
    try (var heldBlue = engine.captureServingView()) {
      assertEquals(bluePath, heldBlue.activeGenerationPath(), "the lease must hold the actual Blue view");
      assertTrue(engine.client().requestCutover(true, TestEngineContexts.FOREGROUND).accepted(),
          "requestCutover must be accepted");

      assertTrue(awaitActiveGenerationChanged(engine.indexBase(), activeBefore, 180_000),
          "the cutover must promote the building generation");
      StatusResponse live = awaitMigrationState("IDLE", 60_000);
      assertNotEquals(activeBefore, live.getMigration().getActiveGenerationId());
      assertEquals(activeBefore, live.getMigration().getPreviousGenerationId(),
          "the issued Blue view must keep its generation referenced");
      assertTrue(Files.isDirectory(bluePath), "Blue's directory must survive its held view");
      assertTrue(engine.awaitSearchable(marker, 60_000),
          "the promoted generation must serve search without restarting the Engine");
    }

    assertTrue(awaitPreviousRetired(engine.indexBase(), activeBefore, 60_000),
        "Blue must retire only after its last serving view exits");

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
    assertTrue(after.getMigration().getPreviousGenerationId().isBlank(),
        "the retired predecessor must not consume generation capacity after restart");

    assertTrue(
        engine.awaitSearchable(marker, 120_000),
        "the marker must still be findable on the new active generation");
  }

  /** Blue holds two documents; Green holds one, making live serving-view publication observable. */
  @Test
  @DisplayName("cutover publishes Green's document count in the live Engine without a restart")
  void cutoverActivatesTheGenerationInProcess(@TempDir Path tempDir)
      throws Exception {
    Path dataDir = tempDir.resolve("data");
    Path docsDir = dataDir.resolve("count-docs");
    Files.createDirectories(docsDir);

    String markerA = "countmarkera" + System.nanoTime();
    String markerB = "countmarkerb" + System.nanoTime();
    Path fileA = docsDir.resolve("a.txt");
    Path fileB = docsDir.resolve("b.txt");
    Files.writeString(fileA, "hello " + markerA);
    Files.writeString(fileB, "hello " + markerB);
    writeWatchedRoots(dataDir, docsDir);

    engine = EngineTestHarness.start(dataDir);
    assertTrue(
        engine.client().submitBatch(List.of(fileA, fileB), TestEngineContexts.FOREGROUND).getAcceptedCount() > 0,
        "both files must be accepted for indexing");
    assertTrue(engine.awaitIndexed(2, 120_000), "both documents must index");

    long blueCount = engine.status().getMigration().getActiveDocCount();
    assertEquals(2L, blueCount, "precondition: the serving generation holds both documents");

    String activeBefore = engine.status().getMigration().getActiveGenerationId();
    assertTrue(engine.client().startMigration("count_divergence", TestEngineContexts.FOREGROUND).accepted(), "startMigration accepted");

    // Remove one source file so the generation the enumerator builds differs from the one being
    // served. This is what makes the assertion below non-vacuous: with equal counts it would pass
    // whether or not the Engine reopened.
    Files.delete(fileB);
    engine.restart();

    assertTrue(engine.client().requestCutover(true, TestEngineContexts.FOREGROUND).accepted(), "requestCutover must be accepted");
    assertTrue(
        awaitActiveGenerationChanged(engine.indexBase(), activeBefore, 180_000),
        "the cutover must promote the building generation");

    StatusResponse live = engine.status();
    assertNotEquals(
        activeBefore,
        live.getMigration().getActiveGenerationId(),
        "precondition: state.json names the promoted generation");
    long liveCount = live.getMigration().getActiveDocCount();

    assertNotEquals(
        blueCount,
        liveCount,
        "the live Engine must leave Blue's serving count at promotion");
    assertEquals(1L, liveCount, "the live Engine must count Green's documents");

    engine.restart();
    long afterRestartCount = engine.status().getMigration().getActiveDocCount();
    assertEquals(liveCount, afterRestartCount, "restart must preserve the promoted view");
  }

  @Test
  @DisplayName("directory rollback is refused while the promoted generation keeps serving")
  void rollbackCannotRevertThePublishedGeneration(@TempDir Path tempDir) throws Exception {
    Path dataDir = tempDir.resolve("data");
    Path docsDir = dataDir.resolve("rollback-root");
    Files.createDirectories(docsDir);

    String marker = "rollbackmarker" + System.nanoTime();
    Path file = docsDir.resolve("doc.txt");
    Files.writeString(file, "hello " + marker);
    writeWatchedRoots(dataDir, docsDir);

    engine = EngineTestHarness.start(dataDir);

    assertTrue(
        engine.client().submitBatch(List.of(file), TestEngineContexts.FOREGROUND).getAcceptedCount() > 0,
        "the file must be accepted for indexing");
    assertTrue(engine.awaitIndexed(1, 120_000), "indexing must complete");
    assertTrue(engine.awaitSearchable(marker, 60_000), "the doc must be searchable");

    String activeBefore = engine.status().getMigration().getActiveGenerationId();
    assertFalse(activeBefore.isBlank(), "active_generation_id must be present");

    assertTrue(engine.client().startMigration("system_test_rollback", TestEngineContexts.FOREGROUND).accepted(), "startMigration accepted");
    engine.restart();
    assertTrue(engine.client().requestCutover(true, TestEngineContexts.FOREGROUND).accepted(), "requestCutover must be accepted");
    assertTrue(
        awaitActiveGenerationChanged(engine.indexBase(), activeBefore, 180_000),
        "the cutover must promote the building generation");
    engine.restart();

    StatusResponse afterCutover = awaitMigrationState("IDLE", 60_000);
    assertEquals("IDLE", afterCutover.getMigration().getMigrationState(), "IDLE after cutover");
    String activeAfterCutover = afterCutover.getMigration().getActiveGenerationId();
    assertNotEquals(activeBefore, activeAfterCutover, "the active generation must have changed");
    assertTrue(afterCutover.getMigration().getPreviousGenerationId().isBlank(),
        "the unheld Blue generation should be retired before this explicit restart");

    assertFalse(engine.client().rollbackMigration(TestEngineContexts.FOREGROUND).accepted(),
        "a published generation cannot be rolled back by pointer-only control");
    StatusResponse afterRollback = awaitActiveGeneration(activeAfterCutover, 60_000);
    assertEquals(activeAfterCutover, afterRollback.getMigration().getActiveGenerationId(),
        "refused rollback must preserve the published pointer");

    assertTrue(
        engine.awaitSearchable(marker, 120_000),
        "the marker must still be findable after the refused rollback");
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
    // Captured before the migration starts, so the release assertion at the end of this test has a
    // pointer to compare against.
    String activeBeforePause = engine.status().getMigration().getActiveGenerationId();
    assertTrue(engine.client().startMigration("pause_resume_test", TestEngineContexts.FOREGROUND).accepted(), "startMigration accepted");
    assertTrue(engine.client().pauseMigration("system_test", TestEngineContexts.FOREGROUND), "pauseMigration must be accepted");

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

    assertTrue(engine.client().resumeMigration(TestEngineContexts.FOREGROUND), "resumeMigration must be accepted");

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

    // The second half of "resume releases BOTH". Up to here the test has only shown the enumerator
    // was released; the pause window above asserted two things were held (the walk and the switch)
    // and it would be asymmetric — and exactly the kind of half-assertion this review pass is for —
    // to check only one of them on the way out. A resume that clears the flag and restarts the walk
    // but leaves the cutover monitor parked would satisfy every assertion above and still leave the
    // migration unable to ever finish.
    assertTrue(engine.client().requestCutover(true, TestEngineContexts.FOREGROUND).accepted(), "requestCutover must be accepted after resume");
    assertTrue(
        awaitActiveGenerationChanged(engine.indexBase(), activeBeforePause, 180_000),
        "resume must release the cutover monitor too: the promotion has to actually happen, not"
            + " merely be accepted. Active generation was still "
            + activeBeforePause);
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
   * monitor promotes into it before publishing the new serving view.
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

  private static boolean awaitPreviousRetired(Path indexBase, String previous, long timeoutMs)
      throws Exception {
    Path statePath = indexBase.resolve("state.json");
    Path oldPath = indexBase.resolve("indices").resolve(previous);
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      if (Files.exists(statePath)) {
        try {
          JsonNode state = JSON.readTree(Files.readString(statePath, StandardCharsets.UTF_8));
          if (state.path("previous_generation").asText().isBlank() && !Files.exists(oldPath)) {
            return true;
          }
        } catch (RuntimeException midWrite) {
          // Atomic state replacement may briefly make one observation unreadable.
        }
      }
      Thread.sleep(100);
    }
    return false;
  }
}
