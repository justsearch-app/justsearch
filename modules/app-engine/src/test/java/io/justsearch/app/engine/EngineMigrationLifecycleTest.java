/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.server.KnowledgeServer;
import io.justsearch.indexerworker.server.MigrationTransitionBarrier;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.StatusResponse;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
  private MigrationTransitionBarrier.Controlled migrationBarrier;

  @AfterEach
  void tearDown() {
    if (migrationBarrier != null) {
      migrationBarrier.release();
      migrationBarrier = null;
    }
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

    var started = engine.client().startMigration("system_test", TestEngineContexts.FOREGROUND);
    assertTrue(started.accepted(), "startMigration must be accepted");
    assertFalse(started.restartRequired(), "the live start must not require an Engine restart");
    Path bluePath = engine.indexBase().resolve("indices").resolve(activeBefore);
    Path greenPath = engine.indexBase().resolve("indices")
        .resolve(started.buildingGenerationId());
    long openDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    boolean liveGreen = false;
    while (System.nanoTime() < openDeadline) {
      try (var view = engine.captureServingView()) {
        liveGreen = bluePath.equals(view.searchRuntime().openedIndexPath())
            && greenPath.equals(view.ingestRuntime().openedIndexPath());
      }
      if (liveGreen) break;
      Thread.sleep(50);
    }
    assertTrue(liveGreen, "the current Engine must serve A while its new Green writer runs");

    var heldBlue = engine.captureServingView();
    try {
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

      // Shutdown's bounded retirement probe is the contractually significant timeout branch.
      // It must report the held owner without dropping the predecessor or closing beneath the
      // issued lease. Running the private close phase directly keeps this a retirement test: a
      // whole Engine close would also stop admission and make the later-release assertion
      // impossible to distinguish from shutdown teardown.
      try (var timeoutProbe = Executors.newSingleThreadExecutor()) {
        long timeoutStarted = System.nanoTime();
        var refused = timeoutProbe.submit(() -> closeRetiredServingViews(engineServer()));
        ExecutionException timeout = assertThrows(
            ExecutionException.class,
            () -> refused.get(10, TimeUnit.SECONDS),
            "the five-second retirement bound must report the held Blue lease");
        long timeoutElapsed = System.nanoTime() - timeoutStarted;
        assertTrue(timeoutElapsed >= TimeUnit.SECONDS.toNanos(5),
            "retirement must hold the lease through its full five-second deadline; elapsed="
                + TimeUnit.NANOSECONDS.toMillis(timeoutElapsed) + "ms");
        assertTrue(timeout.getCause() instanceof IOException, timeout::toString);
        assertTrue(
            timeout.getCause().getMessage().contains("still owns resources"),
            "the timeout must name retained retired-view ownership: " + timeout.getCause());
      }
      StatusResponse retained = engine.status();
      assertEquals(activeBefore, retained.getMigration().getPreviousGenerationId(),
          "a timed-out retirement must retain the exact predecessor reference");
      assertTrue(Files.isDirectory(bluePath),
          "a timed-out retirement must retain Blue's physical directory");
      SearchResponse retainedBlue = heldBlue.services().searchService().search(
          SearchRequest.newBuilder().setQuery(marker).setLimit(10).build(), CallContext.none());
      assertTrue(retainedBlue.getResultsCount() > 0,
          "the timed-out Blue owner must remain query-readable through its issued lease");
    } finally {
      heldBlue.close();
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

  /** Blue gains C through its active lexical projection; Green owns only C after publication. */
  @Test
  @DisplayName("cutover publishes Green's document count in the live Engine without a restart")
  void cutoverActivatesTheGenerationInProcess(@TempDir Path tempDir)
      throws Exception {
    Path dataDir = tempDir.resolve("data");
    Path blueDocs = dataDir.resolve("blue-count-docs");
    Path greenDocs = dataDir.resolve("green-count-docs");
    Files.createDirectories(blueDocs);
    Files.createDirectories(greenDocs);

    String commonMarker = "countcommon" + System.nanoTime();
    Path fileA = blueDocs.resolve("a.txt");
    Path fileB = blueDocs.resolve("b.txt");
    Path fileC = greenDocs.resolve("c.txt");
    Files.writeString(fileA, commonMarker + " blue a");
    Files.writeString(fileB, commonMarker + " blue b");
    Files.writeString(fileC, commonMarker + " green c");

    migrationBarrier = new MigrationTransitionBarrier.Controlled(
        "migration-before-switching");
    engine = EngineTestHarness.start(dataDir, migrationBarrier);
    assertTrue(
        engine.client().submitBatch(List.of(fileA, fileB), TestEngineContexts.FOREGROUND).getAcceptedCount() > 0,
        "both files must be accepted for indexing");
    assertTrue(engine.awaitIndexed(2, 120_000), "both documents must index");

    long blueCount = engine.status().getMigration().getActiveDocCount();
    assertEquals(2L, blueCount, "precondition: the serving generation holds both documents");
    SearchWitness blueWitness = witness(awaitSearchResponse(commonMarker, 2, 60_000));
    assertEquals(2L, blueWitness.totalHits(), "Blue's complete query signature must have two hits");
    assertEquals(2, blueWitness.ids().size(), "Blue's result page must contain both exact ids");
    List<String> expectedBlueIds = List.of(
        PathNormalizer.normalizeKey(fileA), PathNormalizer.normalizeKey(fileB)).stream()
        .sorted().toList();
    assertEquals(expectedBlueIds, blueWitness.ids(),
        "Blue's signature must be the two files explicitly indexed into A");

    String activeBefore = engine.status().getMigration().getActiveGenerationId();
    // Publish the one-document migration source only after the Engine has captured its ordinary
    // watcher roots. Accepted build effects also project C lexically into A (D1's active-source
    // projection), so the paused A signature is the known full set A+B+C, while B contains C.
    writeWatchedRoots(dataDir, greenDocs);
    var started = engine.client().startMigration(
        "count_divergence", TestEngineContexts.FOREGROUND);
    assertTrue(started.accepted(), "startMigration accepted");
    assertTrue(migrationBarrier.awaitReached(180, TimeUnit.SECONDS),
        "the before-SWITCHING barrier must witness completed enumeration and hold publication on A");
    SearchWitness pausedBlueWitness = new SearchWitness(3L,
        List.of(PathNormalizer.normalizeKey(fileA), PathNormalizer.normalizeKey(fileB),
            PathNormalizer.normalizeKey(fileC)).stream().sorted().toList());
    assertEquals(pausedBlueWitness, witness(awaitSearchResponse(commonMarker, 3, 60_000)),
        "paused Blue must expose all three accepted lexical effects before the swap loop");
    GenerationWitness blueGeneration = new GenerationWitness(activeBefore, pausedBlueWitness);
    GenerationWitness greenGeneration = new GenerationWitness(started.buildingGenerationId(),
        new SearchWitness(1L, List.of(PathNormalizer.normalizeKey(fileC))));

    var observedResponses = new ConcurrentLinkedQueue<SearchWitness>();
    var keepSearching = new AtomicBoolean(true);
    var firstResponse = new CountDownLatch(1);
    try (var searches = Executors.newSingleThreadExecutor()) {
      var searchLoop = searches.submit(() -> {
        while (keepSearching.get()) {
          try {
            SearchResponse response = engine.client().search(
                commonMarker, 10, TestEngineContexts.FOREGROUND);
            SearchWitness observed = witness(response);
            assertTrue(observed.equals(blueGeneration.response())
                    || observed.equals(greenGeneration.response()),
                "each response must be the complete A or B fixture signature: " + observed);
            observedResponses.add(observed);
          } finally {
            firstResponse.countDown();
          }
          Thread.sleep(5);
        }
        return null;
      });
      try {
        assertTrue(awaitFirstResponse(firstResponse, observedResponses, searchLoop, 30_000),
            "the across-swap loop must observe Blue before cutover");
        assertTrue(observedResponses.contains(blueGeneration.response()),
            "the across-swap loop must establish Blue's complete response signature");

        migrationBarrier.release();
        assertTrue(
            awaitActiveGenerationChanged(engine.indexBase(), activeBefore, 180_000),
            "the cutover must promote the building generation");
        assertTrue(awaitObservedHitCount(observedResponses, 1, searchLoop, 60_000),
            "the same loop must observe Green's one-document answer after publication");
      } finally {
        keepSearching.set(false);
        migrationBarrier.release();
      }
      searchLoop.get(30, TimeUnit.SECONDS);
    }

    SearchWitness greenWitness = witness(
        engine.client().search(commonMarker, 10, TestEngineContexts.FOREGROUND));
    assertEquals(1L, greenWitness.totalHits(), "Green's complete query signature must have one hit");
    assertEquals(1, greenWitness.ids().size(), "Green's result page must contain its exact id");
    assertEquals(greenGeneration.response(), greenWitness,
        "Green must serve the exact file C identity fixed before the swap loop started");

    assertFalse(observedResponses.isEmpty(), "the swap loop must retain every response witness");
    assertTrue(
        observedResponses.stream().allMatch(
            response -> response.equals(blueGeneration.response())
                || response.equals(greenGeneration.response())),
        "every complete response must equal the frozen generation signature "
            + blueGeneration + " or " + greenGeneration + ": " + observedResponses);
    assertTrue(observedResponses.containsAll(List.of(blueGeneration.response(), greenWitness)),
        "the retained response trace must include both generation signatures: "
            + observedResponses);

    StatusResponse live = awaitMigrationState("IDLE", 60_000);
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
    assertTrue(engine.awaitLiveGreen(60_000), "live Green preparation must complete before restart");
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
    // Pause immediately after start is accepted, before waiting for asynchronous Green preparation.
    // Then restart only after Green is published, verifying that the durable pause still holds its
    // enumerator and cutover monitor. Both consult the pause gate before doing migration work
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

    assertTrue(engine.awaitLiveGreen(60_000), "paused Green preparation must complete before restart");
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

  private static boolean awaitObservedHitCount(
      ConcurrentLinkedQueue<SearchWitness> observed, long expected,
      java.util.concurrent.Future<?> searchLoop, long timeoutMs) throws Exception {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      propagateAsyncFailure(searchLoop);
      if (observed.stream().anyMatch(response -> response.totalHits() == expected)) return true;
      Thread.sleep(25);
    }
    propagateAsyncFailure(searchLoop);
    return observed.stream().anyMatch(response -> response.totalHits() == expected);
  }

  private static boolean awaitFirstResponse(CountDownLatch firstResponse,
      ConcurrentLinkedQueue<SearchWitness> observed, java.util.concurrent.Future<?> searchLoop,
      long timeoutMs) throws Exception {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      propagateAsyncFailure(searchLoop);
      if (firstResponse.await(25, TimeUnit.MILLISECONDS)) {
        if (observed.isEmpty()) {
          try {
            searchLoop.get(5, TimeUnit.SECONDS);
          } catch (java.util.concurrent.TimeoutException stillRunning) {
            // The response completed and a later iteration is running; inspect retained evidence.
          } catch (ExecutionException failure) {
            rethrowAsync(failure);
          }
        }
        propagateAsyncFailure(searchLoop);
        return !observed.isEmpty();
      }
    }
    propagateAsyncFailure(searchLoop);
    return false;
  }

  private static void propagateAsyncFailure(java.util.concurrent.Future<?> task) throws Exception {
    if (!task.isDone()) return;
    try {
      task.get();
    } catch (ExecutionException failure) {
      rethrowAsync(failure);
    }
  }

  private static void rethrowAsync(ExecutionException failure) throws Exception {
    Throwable cause = failure.getCause();
    if (cause instanceof Error error) throw error;
    if (cause instanceof RuntimeException runtime) throw runtime;
    if (cause instanceof Exception checked) throw checked;
    throw failure;
  }

  private SearchResponse awaitSearchResponse(String query, long expectedHits, long timeoutMs)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    SearchResponse last = SearchResponse.getDefaultInstance();
    while (System.currentTimeMillis() < deadline) {
      last = engine.client().search(query, 10, TestEngineContexts.FOREGROUND);
      if (last.getTotalHits() == expectedHits) return last;
      Thread.sleep(50);
    }
    return last;
  }

  /** IPC has no served-generation field, so use a complete, distinct fixture response signature. */
  private static SearchWitness witness(SearchResponse response) {
    List<String> ids = response.getResultsList().stream().map(result -> result.getId()).sorted()
        .toList();
    assertEquals((long) ids.size(), ids.stream().distinct().count(),
        "one response must not duplicate document ids");
    assertEquals(response.getTotalHits(), ids.size(),
        "the ten-result page must be the complete result set for this three-document fixture");
    return new SearchWitness(response.getTotalHits(), ids);
  }

  private record SearchWitness(long totalHits, List<String> ids) {}

  private record GenerationWitness(String generationId, SearchWitness response) {}

  private KnowledgeServer engineServer() throws ReflectiveOperationException {
    Field rootField = EngineTestHarness.class.getDeclaredField("root");
    rootField.setAccessible(true);
    EngineRoot root = (EngineRoot) rootField.get(engine);
    Field serverField = EngineRoot.class.getDeclaredField("server");
    serverField.setAccessible(true);
    return (KnowledgeServer) serverField.get(root);
  }

  private static Void closeRetiredServingViews(KnowledgeServer server) throws Exception {
    Method close = KnowledgeServer.class.getDeclaredMethod("closeRetiredServingViews");
    close.setAccessible(true);
    try {
      close.invoke(server);
      return null;
    } catch (InvocationTargetException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof Exception checked) throw checked;
      if (cause instanceof Error error) throw error;
      throw failure;
    }
  }
}
