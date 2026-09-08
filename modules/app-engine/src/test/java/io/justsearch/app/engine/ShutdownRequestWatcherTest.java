/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.engine.ShutdownRequest.Reason;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Stage B item B3 — the request-file watcher.
 *
 * <p>The two properties worth the most here are the ones that are easy to get silently wrong: a
 * malformed file must shut NOTHING down, and the watcher must not be on the API pool. The second is
 * asserted by thread name against a live poll, because "we intended a separate executor" is exactly
 * the kind of claim that survives a refactor that quietly reuses a shared pool.
 */
@DisplayName("ShutdownRequestWatcher — the out-of-band trigger (stage B item B3)")
final class ShutdownRequestWatcherTest {

  private static Path runtimeDir(Path tempDir) throws Exception {
    return Files.createDirectories(tempDir.resolve("runtime"));
  }

  @Test
  @DisplayName("each of the four reasons reaches the sequence, and reaches its steps")
  void everyReasonReachesTheOrderedShutdown(@TempDir Path tempDir) throws Exception {
    for (Reason reason : Reason.values()) {
      Path runtime = runtimeDir(tempDir.resolve(reason.wire()));
      new ShutdownRequest(reason, 1L, null, "test", null).writeTo(runtime);

      // A real sequence over a fake step, so this covers the whole path the production wiring
      // takes — watcher -> sequence -> step — rather than just the watcher's callback.
      List<Reason> reachedStep = new ArrayList<>();
      var sequence =
          new EngineShutdownSequence(
              tempDir.resolve(reason.wire()),
              List.of(
                  new EngineShutdownSequence.Step(
                      "fake", r -> { reachedStep.add(r); return null; })),
              code -> {});

      try (var watcher =
          new ShutdownRequestWatcher(runtime, r -> true, r -> sequence.run(r.reason()), 50L)) {
        watcher.pollOnce();
      }

      assertEquals(
          List.of(reason),
          reachedStep,
          reason.wire() + " must reach the ordered close's steps — 7.3 step 6 branches on it");
    }
  }

  @Test
  @DisplayName("an unparseable file shuts nothing down, and does not stop the watcher")
  void malformedFileShutsNothingDown(@TempDir Path tempDir) throws Exception {
    Path runtime = runtimeDir(tempDir);
    var ran = new AtomicReference<ShutdownRequest>();
    Files.writeString(
        ShutdownRequest.pathIn(runtime), "{ this is not json", StandardCharsets.UTF_8);

    try (var watcher = new ShutdownRequestWatcher(runtime, r -> true, ran::set, 50L)) {
      watcher.pollOnce();
      assertTrue(ran.get() == null, "a corrupt file must never be read as a shutdown");
      assertFalse(watcher.hasFired());

      // The watcher must still be working: a good request written afterwards is acted on.
      new ShutdownRequest(Reason.QUIT, 1L, null, "test", null).writeTo(runtime);
      watcher.pollOnce();
      assertEquals(Reason.QUIT, ran.get().reason(), "one bad file must not end the watch");
    }
  }

  @Test
  @DisplayName("a refused request is ignored and deleted, not re-read every poll")
  void refusedRequestIsIgnoredAndCleared(@TempDir Path tempDir) throws Exception {
    Path runtime = runtimeDir(tempDir);
    new ShutdownRequest(Reason.UPGRADE, 1L, "wrong-nonce", "updater", null).writeTo(runtime);
    var ran = new AtomicReference<ShutdownRequest>();

    try (var watcher = new ShutdownRequestWatcher(runtime, r -> false, ran::set, 50L)) {
      watcher.pollOnce();
      assertTrue(ran.get() == null, "a refused request must not shut the Engine down");
      assertFalse(
          Files.exists(ShutdownRequest.pathIn(runtime)),
          "a refused request left on disk is re-read and re-logged every second forever");
    }
  }

  @Test
  @DisplayName("the request is consumed before the sequence runs")
  void requestIsConsumedBeforeActing(@TempDir Path tempDir) throws Exception {
    Path runtime = runtimeDir(tempDir);
    new ShutdownRequest(Reason.RESTART, 1L, null, "supervisor", null).writeTo(runtime);
    var fileStillPresentWhenActing = new AtomicReference<Boolean>();

    try (var watcher =
        new ShutdownRequestWatcher(
            runtime,
            r -> true,
            r -> fileStillPresentWhenActing.set(Files.exists(ShutdownRequest.pathIn(runtime))),
            50L)) {
      watcher.pollOnce();
    }

    assertEquals(
        Boolean.FALSE,
        fileStillPresentWhenActing.get(),
        "the ordered close takes seconds; a request still on disk when the NEXT Engine starts"
            + " would shut that one down too");
  }

  @Test
  @DisplayName("only the first request fires; a second poll does not shut down twice")
  void firesOnlyOnce(@TempDir Path tempDir) throws Exception {
    Path runtime = runtimeDir(tempDir);
    var count = new java.util.concurrent.atomic.AtomicInteger();

    try (var watcher =
        new ShutdownRequestWatcher(runtime, r -> true, r -> count.incrementAndGet(), 50L)) {
      new ShutdownRequest(Reason.QUIT, 1L, null, null, null).writeTo(runtime);
      watcher.pollOnce();
      new ShutdownRequest(Reason.QUIT, 1L, null, null, null).writeTo(runtime);
      watcher.pollOnce();
    }

    assertEquals(1, count.get());
  }

  @Test
  @DisplayName("the poll runs on its own named thread, never a shared pool")
  void pollRunsOnItsOwnNamedThread(@TempDir Path tempDir) throws Exception {
    // Design 7.3 forbids the API pool here: the case this watcher exists for is an Engine that
    // answers no HTTP, so a watcher starved by the API front would be useless exactly when needed.
    // Asserted against a LIVE scheduled poll, not against the constructor's intent.
    Path runtime = runtimeDir(tempDir);
    var threadName = new AtomicReference<String>();
    var latch = new CountDownLatch(1);
    new ShutdownRequest(Reason.HANG, 1L, null, "supervisor", null).writeTo(runtime);

    try (var watcher =
        new ShutdownRequestWatcher(
            runtime,
            r -> true,
            r -> {
              threadName.set(Thread.currentThread().getName());
              latch.countDown();
            },
            20L)) {
      watcher.start();
      assertTrue(latch.await(10, TimeUnit.SECONDS), "the scheduled poll must fire");
    }

    assertEquals(
        ShutdownRequestWatcher.THREAD_NAME,
        threadName.get(),
        "the watcher must own its executor; if this ever reads as an API or common-pool thread"
            + " name, the separation 7.3 requires has been refactored away");
  }

  @Test
  @DisplayName("closing from the accepted-request callback does not interrupt later shutdown work")
  void closeFromOwnCallbackDoesNotInterruptLaterShutdownWork(@TempDir Path tempDir)
      throws Exception {
    Path runtime = runtimeDir(tempDir);
    var watcherRef = new AtomicReference<ShutdownRequestWatcher>();
    var interruptedAfterClose = new AtomicReference<Boolean>();
    var laterStepRan = new CountDownLatch(1);
    new ShutdownRequest(Reason.QUIT, Long.MAX_VALUE, null, "test", null).writeTo(runtime);

    var watcher =
        new ShutdownRequestWatcher(
            runtime,
            r -> true,
            r -> {
              watcherRef.get().close();
              interruptedAfterClose.set(Thread.currentThread().isInterrupted());
              laterStepRan.countDown();
            },
            20L);
    watcherRef.set(watcher);
    watcher.start();

    assertTrue(laterStepRan.await(10, TimeUnit.SECONDS), "the callback must complete after close");
    assertEquals(
        Boolean.FALSE,
        interruptedAfterClose.get(),
        "self-interruption here would disrupt the ordered close steps after the watcher");
  }
}
