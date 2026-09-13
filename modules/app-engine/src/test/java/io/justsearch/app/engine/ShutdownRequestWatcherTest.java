/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static io.justsearch.app.engine.ShutdownRequestTest.writeRequest;
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
      writeRequest(new ShutdownRequest(reason, Long.MAX_VALUE, null, "test", null), runtime);

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
          new ShutdownRequestWatcher(new io.justsearch.core.execution.TestEngineExecutors(),
              runtime, r -> ShutdownRequestWatcher.Acceptance.ACCEPT,
              r -> sequence.run(r.reason()), 50L)) {
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

    try (var watcher =
        new ShutdownRequestWatcher(new io.justsearch.core.execution.TestEngineExecutors(),
            runtime, r -> ShutdownRequestWatcher.Acceptance.ACCEPT, ran::set, 50L)) {
      watcher.pollOnce();
      assertTrue(ran.get() == null, "a corrupt file must never be read as a shutdown");
      assertFalse(watcher.hasFired());

      // The watcher must still be working: a good request written afterwards is acted on.
      writeRequest(new ShutdownRequest(Reason.QUIT, Long.MAX_VALUE, null, "test", null), runtime);
      watcher.pollOnce();
      assertEquals(Reason.QUIT, ran.get().reason(), "one bad file must not end the watch");
    }
  }

  @Test
  @DisplayName("a refused request is ignored and deleted, not re-read every poll")
  void refusedRequestIsIgnoredAndCleared(@TempDir Path tempDir) throws Exception {
    Path runtime = runtimeDir(tempDir);
    writeRequest(new ShutdownRequest(Reason.UPGRADE, Long.MAX_VALUE, "wrong-nonce", "updater", null), runtime);
    var ran = new AtomicReference<ShutdownRequest>();

    try (var watcher =
        new ShutdownRequestWatcher(new io.justsearch.core.execution.TestEngineExecutors(),
            runtime, r -> ShutdownRequestWatcher.Acceptance.REFUSE, ran::set, 50L)) {
      watcher.pollOnce();
      assertTrue(ran.get() == null, "a refused request must not shut the Engine down");
      assertFalse(
          Files.exists(ShutdownRequest.pathIn(runtime)),
          "a refused request left on disk is re-read and re-logged every second forever");
    }
  }

  @Test
  @DisplayName("a null acceptance decision fails closed")
  void nullAcceptanceDecisionIsRefused(@TempDir Path tempDir) throws Exception {
    Path runtime = runtimeDir(tempDir);
    var ran = new AtomicReference<ShutdownRequest>();
    writeRequest(new ShutdownRequest(Reason.UPGRADE, Long.MAX_VALUE, "nonce", "updater", "prep"), runtime);

    try (var watcher = new ShutdownRequestWatcher(new io.justsearch.core.execution.TestEngineExecutors(), runtime, ignored -> null, ran::set, 50L)) {
      watcher.pollOnce();
      assertTrue(ran.get() == null);
      assertFalse(watcher.hasFired());
      assertFalse(Files.exists(ShutdownRequest.pathIn(runtime)));
    }
  }

  @Test
  @DisplayName("a refused request does not consume the next valid host request")
  void refusedRequestDoesNotConsumeTheOneShotGuard(@TempDir Path tempDir) throws Exception {
    Path runtime = runtimeDir(tempDir);
    var decision = new AtomicReference<>(ShutdownRequestWatcher.Acceptance.REFUSE);
    var ran = new AtomicReference<ShutdownRequest>();
    writeRequest(new ShutdownRequest(Reason.UPGRADE, Long.MAX_VALUE, "nonce", "updater", "prep"), runtime);

    try (var watcher = new ShutdownRequestWatcher(new io.justsearch.core.execution.TestEngineExecutors(), runtime, ignored -> decision.get(), ran::set, 50L)) {
      watcher.pollOnce();
      assertFalse(Files.exists(ShutdownRequest.pathIn(runtime)));
      assertFalse(watcher.hasFired());
      assertTrue(ran.get() == null);

      writeRequest(new ShutdownRequest(Reason.RESTART, Long.MAX_VALUE, null, "supervisor", null), runtime);
      decision.set(ShutdownRequestWatcher.Acceptance.ACCEPT);
      watcher.pollOnce();
      assertEquals(Reason.RESTART, ran.get().reason());
      assertTrue(watcher.hasFired());
    }
  }

  @Test
  @DisplayName("the request is consumed before the sequence runs")
  void requestIsConsumedBeforeActing(@TempDir Path tempDir) throws Exception {
    Path runtime = runtimeDir(tempDir);
    writeRequest(new ShutdownRequest(Reason.RESTART, Long.MAX_VALUE, null, "supervisor", null), runtime);
    var fileStillPresentWhenActing = new AtomicReference<Boolean>();

    try (var watcher =
        new ShutdownRequestWatcher(new io.justsearch.core.execution.TestEngineExecutors(),
            runtime,
            r -> ShutdownRequestWatcher.Acceptance.ACCEPT,
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
        new ShutdownRequestWatcher(new io.justsearch.core.execution.TestEngineExecutors(),
            runtime,
            r -> ShutdownRequestWatcher.Acceptance.ACCEPT,
            r -> count.incrementAndGet(),
            50L)) {
      writeRequest(new ShutdownRequest(Reason.QUIT, Long.MAX_VALUE, null, null, null), runtime);
      watcher.pollOnce();
      writeRequest(new ShutdownRequest(Reason.QUIT, Long.MAX_VALUE, null, null, null), runtime);
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
    writeRequest(new ShutdownRequest(Reason.HANG, Long.MAX_VALUE, null, "supervisor", null), runtime);

    try (var watcher =
        new ShutdownRequestWatcher(new io.justsearch.core.execution.TestEngineExecutors(),
            runtime,
            r -> ShutdownRequestWatcher.Acceptance.ACCEPT,
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
    writeRequest(new ShutdownRequest(Reason.QUIT, Long.MAX_VALUE, null, "test", null), runtime);

    var watcher =
        new ShutdownRequestWatcher(new io.justsearch.core.execution.TestEngineExecutors(),
            runtime,
            r -> ShutdownRequestWatcher.Acceptance.ACCEPT,
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

  @Test
  void callbackCanCloseProcessRegistryWithoutInterruptingItselfOrSkippingOtherOwners(@TempDir Path tempDir)
      throws Exception {
    var registry = new DefaultEngineExecutorRegistry();
    var spec = new io.justsearch.core.execution.EngineExecutorSpec(
        "other-shutdown-owner", io.justsearch.core.execution.EngineExecutorSpec.Kind.BACKGROUND,
        io.justsearch.core.execution.EngineExecutorSpec.Mode.PLATFORM, 1, 1, 1);
    var other = registry.register(spec).open(Thread.ofPlatform().daemon().factory());
    var otherEntered = new CountDownLatch(1);
    var otherInterrupted = new CountDownLatch(1);
    var releaseOther = new CountDownLatch(1);
    var registryReturned = new CountDownLatch(1);
    var releaseCallback = new CountDownLatch(1);
    var callbackExited = new CountDownLatch(1);
    var callbackInterrupted = new AtomicReference<Boolean>();
    var watcherRef = new AtomicReference<ShutdownRequestWatcher>();
    Path runtime = runtimeDir(tempDir);
    other.submit(() -> {
      otherEntered.countDown();
      try { new CountDownLatch(1).await(); }
      catch (InterruptedException expected) {
        otherInterrupted.countDown();
        releaseOther.await();
      }
      return null;
    });
    assertTrue(otherEntered.await(1, TimeUnit.SECONDS));
    writeRequest(new ShutdownRequest(Reason.QUIT, Long.MAX_VALUE, null, "test", null), runtime);
    var watcher = new ShutdownRequestWatcher(registry, runtime,
        request -> ShutdownRequestWatcher.Acceptance.ACCEPT, request -> {
          try {
            watcherRef.get().close();
            registry.close();
            callbackInterrupted.set(Thread.currentThread().isInterrupted());
            registryReturned.countDown();
            releaseCallback.await();
          } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
          } finally {
            callbackExited.countDown();
          }
        }, 10L);
    watcherRef.set(watcher);
    try {
      watcher.start();
      assertTrue(otherInterrupted.await(1, TimeUnit.SECONDS));
      assertFalse(registryReturned.await(50, TimeUnit.MILLISECONDS), "close must await the other owner");
      releaseOther.countDown();
      assertTrue(registryReturned.await(1, TimeUnit.SECONDS));
      assertEquals(Boolean.FALSE, callbackInterrupted.get());
      assertTrue(other.isTerminated());
      assertTrue(registry.snapshot().registrations().stream().anyMatch(
          row -> row.spec().name().equals("engine.shutdown-request-watcher") && row.liveInstances() == 1));
    } finally {
      releaseOther.countDown();
      releaseCallback.countDown();
      callbackExited.await(1, TimeUnit.SECONDS);
      watcher.close();
      registry.close();
    }
  }

  @Test
  @DisplayName("an expired request is discarded before acceptance and never fires")
  void expiredRequestIsDiscarded(@TempDir Path tempDir) throws Exception {
    Path runtime = runtimeDir(tempDir);
    var ran = new AtomicReference<ShutdownRequest>();
    writeRequest(new ShutdownRequest(Reason.RESTART, 1L, null, "stale-supervisor", null), runtime);

    try (var watcher =
        new ShutdownRequestWatcher(new io.justsearch.core.execution.TestEngineExecutors(),
            runtime, r -> ShutdownRequestWatcher.Acceptance.ACCEPT, ran::set, 50L)) {
      watcher.pollOnce();
    }

    assertTrue(ran.get() == null, "a deadline from an earlier incarnation must not stop this one");
    assertFalse(Files.exists(ShutdownRequest.pathIn(runtime)));
  }
}
