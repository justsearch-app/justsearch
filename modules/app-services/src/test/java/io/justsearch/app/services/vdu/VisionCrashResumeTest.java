/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.chat.FailedCode;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.ipc.VduUpdateOutcome;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Picture reading keeps working on its own after the picture-reading llama-server crashes or
 * fails to start: no person has to press Activate AI. The crash is a real process kill of the
 * READY vision server while it serves a document; everything between that exit and the next
 * automatic pass is the Engine's own code ({@link VduResumeSeam}).
 */
@Timeout(300)
final class VisionCrashResumeTest {
  /** Enough virtual time for the breaker's longest back-off sequence to reach a probe. */
  private static final Duration RESUME_LIMIT = Duration.ofMinutes(130);
  @TempDir Path directory;
  private VduResumeSeam vdu;

  @AfterEach void tearDown() throws Exception {
    if (vdu != null) vdu.close();
  }

  // S1: chat off, automatic pass, vision server killed.
  @Test void chatOffVisionCrashLeavesChatUnfailedAndPictureReadingResumesUnattended() throws Exception {
    vdu = new VduResumeSeam(directory, false);
    vdu.automaticPassWithVisionCrash();
    assertTrue(VduResumeSeam.awaitFor(() -> !vdu.manager.isOnline(), 20), "chat returns to its off intent");
    assertNotCrashLoop("a crash of the picture-reading server is not a chat failure");
    assertTrue(VduResumeSeam.awaitFor(() -> vdu.manager.chatHolds().vduMayStart(), 10),
        "the controller lets picture reading start again; latch=" + vdu.manager.lastChatFailedCode());
    assertTrue(vdu.resumesWithoutUserAction(RESUME_LIMIT),
        "picture reading resumes without any person: states=" + vdu.states + " outcomes=" + vdu.outcomes
            + " breaker=" + vdu.coordinator.automaticBreaker().snapshot());
  }

  // S1b: chat loaded, automatic pass, vision server killed.
  @Test void loadedChatVisionCrashRestoresNormalChatAndPictureReadingResumesUnattended()
      throws Exception {
    vdu = new VduResumeSeam(directory, true);
    vdu.automaticPassWithVisionCrash();
    assertTrue(VduResumeSeam.awaitFor(() -> vdu.manager.isOnline()
        && !vdu.manager.currentConfig().vduMode(), 20),
        "normal chat is restored after the vision crash; online=" + vdu.manager.isOnline()
            + " latch=" + vdu.manager.lastChatFailedCode());
    assertEquals(vdu.loadedConfig, vdu.manager.currentConfig(), "the user's chat configuration");
    assertNotCrashLoop("a crash of the picture-reading server is not a chat failure");
    assertTrue(vdu.resumesWithoutUserAction(RESUME_LIMIT),
        "picture reading resumes on later idle ticks: states=" + vdu.states
            + " breaker=" + vdu.coordinator.automaticBreaker().snapshot());
    assertTrue(VduResumeSeam.awaitFor(() -> vdu.manager.isOnline()
        && !vdu.manager.currentConfig().vduMode(), 20), "normal chat after the resumed pass");
    assertChatUsable();
  }

  // S1c: the vision start fails, the rollback succeeds; a later automatic retry, chat off and on.
  @ParameterizedTest(name = "chat loaded: {0}") @ValueSource(booleans = {false, true})
  void failedVisionStartIsRetriedLaterWithoutUserAction(boolean loaded) throws Exception {
    vdu = new VduResumeSeam(directory, loaded);
    Files.writeString(vdu.seam.fakeHome().resolve("refuse-vision"), "reject the vision configuration");
    int before = vdu.launches();
    vdu.tick();
    assertTrue(vdu.launches() > before, "the automatic pass tried to start picture reading");
    assertTrue(VduResumeSeam.awaitFor(() -> vdu.manager.isOnline() == loaded
        && !vdu.manager.currentConfig().vduMode(), 20), "the rollback returns chat to its intent");
    Files.delete(vdu.seam.fakeHome().resolve("refuse-vision"));
    assertTrue(vdu.resumesWithoutUserAction(RESUME_LIMIT),
        "a later automatic retry reads the pictures without user activity: states=" + vdu.states
            + " launches=" + vdu.launches() + " breaker=" + vdu.coordinator.automaticBreaker().snapshot());
  }

  // S2: repeated vision crashes without progress back off through the breaker; a probe succeeds.
  @Test void repeatedVisionCrashesBackOffAndALaterProbeReadsThePictures() throws Exception {
    vdu = new VduResumeSeam(directory, false);
    int crashes = 0;
    int quietTicks = 0;
    for (int tick = 0; tick < 8 && quietTicks < 3; tick++) {
      int launches = vdu.launches();
      if (tickCrashingVision()) {
        crashes++;
        quietTicks = 0;
      } else {
        assertEquals(launches, vdu.launches(), "a tick without a pass launches nothing");
        quietTicks++;
      }
      vdu.advance(Duration.ofSeconds(VduOfflineTriggerSampler.CHECK_INTERVAL_SECONDS));
    }
    var paused = vdu.coordinator.automaticBreaker().snapshot();
    assertTrue(crashes >= 1 && crashes <= AutomaticVduBreaker.OPEN_AFTER,
        "crashing passes before the back-off: " + crashes);
    assertEquals(3, quietTicks, "no tight relaunch loop");
    assertEquals(AutomaticVduBreaker.State.OPEN, paused.state(),
        "the breaker backs off after crashes without progress (a crash is no document progress): "
            + paused + " outcomes=" + vdu.outcomes);
    assertNoFailedOutcome("crashes spend no document");

    vdu.advance(Duration.ofMillis(paused.backoffMillis()).plusSeconds(1));
    assertTrue(tickCrashingVision(), "the back-off ends in one probe pass");
    var reopened = vdu.coordinator.automaticBreaker().snapshot();
    assertEquals(AutomaticVduBreaker.State.OPEN, reopened.state(), "a crashing probe pauses again");
    assertEquals(paused.backoffMillis() * 2, reopened.backoffMillis(), "with a doubled back-off");
    int launches = vdu.launches();
    vdu.tick();
    assertEquals(launches, vdu.launches(), "no relaunch before the doubled back-off ends");

    vdu.advance(Duration.ofMillis(reopened.backoffMillis()).plusSeconds(1));
    vdu.tick();
    assertTrue(vdu.allRead(), "the probe succeeds once the server works: states=" + vdu.states);
    assertEquals(AutomaticVduBreaker.State.CLOSED, vdu.coordinator.automaticBreaker().snapshot().state());
    assertNoFailedOutcome("crashes spend no document");
  }

  // S3: a crash of the server running normal chat keeps the chat crash policy.
  @Test void normalChatCrashKeepsTheChatCrashPolicy() throws Exception {
    vdu = new VduResumeSeam(directory, true);
    assertFalse(vdu.newestIsVision());
    int launches = vdu.launches();
    vdu.killNewestServer();
    assertTrue(VduResumeSeam.awaitFor(() -> vdu.manager.lastChatFailedCode() == FailedCode.CRASH_LOOP, 20),
        "T29: a crash soon after READY latches crash_loop; latch=" + vdu.manager.lastChatFailedCode());
    assertFalse(vdu.manager.isOnline());
    Thread.sleep(1_000);
    assertEquals(launches, vdu.launches(), "T29 relaunches nothing");
    assertFalse(vdu.manager.chatHolds().vduMayStart(), "failed chat is not borrowed for pictures");
    int passes = vdu.passes.get();
    vdu.tick();
    assertEquals(passes, vdu.passes.get(), "no automatic pass while chat is FAILED");
    assertEquals(launches, vdu.launches());
    // An explicit run while chat is FAILED is refused and launches nothing.
    assertThrows(Exception.class, () -> vdu.coordinator.startExplicitProcessing(
        TestEngineContexts.ui(), outcome -> {}).toCompletableFuture().get(20, TimeUnit.SECONDS));
    vdu.awaitPass();
    assertEquals(launches, vdu.launches(), "a refused explicit run launches no server");
    assertEquals(FailedCode.CRASH_LOOP, vdu.manager.lastChatFailedCode());
  }

  // S3b: the retired vision process signals late; then the restored normal chat fails for real.
  @Test void lateSignalOfRetiredVisionProcessIsIgnoredAndRestoredChatKeepsChatPolicy()
      throws Exception {
    vdu = new VduResumeSeam(directory, true);
    vdu.tickHeldAtFirstRequest();
    assertTrue(vdu.newestIsVision());
    Object visionOwner = activeServer();
    assertNotNull(visionOwner);
    vdu.killNewestServer();
    vdu.releaseRequests();
    vdu.awaitPass();
    assertTrue(VduResumeSeam.awaitFor(() -> vdu.manager.isOnline()
        && !vdu.manager.currentConfig().vduMode(), 20),
        "normal chat is restored after the vision crash; latch=" + vdu.manager.lastChatFailedCode());
    assertNotCrashLoop("precondition: the vision crash itself latched no chat failure");
    int launches = vdu.launches();

    // The retired owner's exit signal, delivered after normal chat runs again.
    Object serverOps = field(vdu.manager, "serverOps");
    Method signal = serverOps.getClass().getDeclaredMethod("signalManagedFailure",
        visionOwner.getClass(), boolean.class);
    signal.setAccessible(true);
    signal.invoke(serverOps, visionOwner, true);
    Method handle = vdu.manager.getClass().getDeclaredMethod("handleManagedServerFailure",
        BooleanSupplier.class);
    handle.setAccessible(true);
    handle.invoke(vdu.manager, (BooleanSupplier) () -> false);
    Thread.sleep(1_000);
    assertTrue(vdu.manager.isOnline(), "a late signal of the retired vision process changes nothing");
    assertNull(vdu.manager.lastChatFailedCode(), "and latches nothing");
    assertEquals(launches, vdu.launches());

    assertFalse(vdu.newestIsVision());
    vdu.killNewestServer();
    assertTrue(VduResumeSeam.awaitFor(() -> vdu.manager.lastChatFailedCode() == FailedCode.CRASH_LOOP, 20),
        "a crash of the restored normal chat keeps the chat policy; latch=" + vdu.manager.lastChatFailedCode());
    assertFalse(vdu.manager.isOnline());
  }

  // S4b: an explicit pass that hits a vision crash.
  @ParameterizedTest(name = "chat loaded: {0}") @ValueSource(booleans = {false, true})
  void explicitPassHittingAVisionCrashRestoresChatAndAutomaticReadingContinues(boolean loaded)
      throws Exception {
    vdu = new VduResumeSeam(directory, loaded);
    var pass = vdu.explicitHeldAtFirstRequest();
    assertTrue(vdu.newestIsVision());
    vdu.killNewestServer();
    vdu.releaseRequests();
    try {
      pass.toCompletableFuture().get(40, TimeUnit.SECONDS);
    } catch (java.util.concurrent.ExecutionException failedPass) {
      // How the explicit pass reports the crash is not under test here.
    }
    vdu.awaitPass();
    assertFalse(vdu.coordinator.isProcessing(), "the pass released ownership");
    assertTrue(VduResumeSeam.awaitFor(() -> vdu.manager.isOnline() == loaded
        && !vdu.manager.currentConfig().vduMode(), 20),
        "chat returns to its intent; online=" + vdu.manager.isOnline()
            + " latch=" + vdu.manager.lastChatFailedCode());
    if (loaded) assertEquals(vdu.loadedConfig, vdu.manager.currentConfig());
    assertNotCrashLoop("the vision crash of an explicit pass is not a chat failure");
    assertTrue(vdu.resumesWithoutUserAction(RESUME_LIMIT),
        "later automatic processing still runs: states=" + vdu.states);
    if (loaded) assertChatUsable();
  }

  // S7: the document being read when the server crashed is not charged for the crash.
  @ParameterizedTest(name = "chat loaded: {0}") @ValueSource(booleans = {false, true})
  void documentInFlightAtTheCrashSpendsNoAttemptAndIsReadLater(boolean loaded) throws Exception {
    vdu = new VduResumeSeam(directory, loaded);
    List<String> claimed = new CopyOnWriteArrayList<>();
    vdu.onClaim = claimed::add;
    vdu.automaticPassWithVisionCrash();
    assertFalse(claimed.isEmpty(), "the crash hit a claimed document");
    String inFlight = claimed.getFirst();
    assertFalse(vdu.outcomesOf(inFlight).contains(VduUpdateOutcome.VDU_UPDATE_OUTCOME_FAILED),
        "the crash is an engine failure, not this document's: outcomes=" + vdu.outcomes);
    assertEquals(0, vdu.retries.get(inFlight), "the crash spends none of the document's attempts");
    assertNotEquals("FAILED", vdu.states.get(inFlight));
    vdu.onClaim = id -> {};
    assertTrue(vdu.resumesWithoutUserAction(RESUME_LIMIT), "states=" + vdu.states);
    assertTrue(vdu.outcomesOf(inFlight).contains(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT),
        "the document is read once the server works again");
    assertNoFailedOutcome("no document was charged for the crash");
  }

  // P4: a hung vision process is stopped before another server starts.
  @Test void hungVisionProcessIsStoppedBeforeAnotherServerStarts() throws Exception {
    vdu = new VduResumeSeam(directory, true);
    vdu.tickHeldAtFirstRequest();
    assertTrue(vdu.newestIsVision());
    var vision = io.justsearch.app.services.chat.SlowStartFakeLlamaServer.launches(vdu.seam.fakeHome())
        .getLast();
    int visionLaunch = vdu.launches();
    Object visionOwner = activeServer();
    Object serverOps = field(vdu.manager, "serverOps");
    Method signal = serverOps.getClass().getDeclaredMethod("signalManagedFailure",
        visionOwner.getClass(), boolean.class);
    signal.setAccessible(true);
    // Three failed health probes end in this hang signal (Spec A T30); the process is still alive.
    signal.invoke(serverOps, visionOwner, false);
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    boolean visionAlive = true;
    while (System.nanoTime() < until) {
      visionAlive = ProcessHandle.of(vision.pid())
          .filter(handle -> handle.info().startInstant().filter(vision.birth()::equals).isPresent())
          .map(ProcessHandle::isAlive).orElse(false);
      if (vdu.launches() > visionLaunch) {
        assertFalse(visionAlive, "a new server started while the hung vision process still ran");
      }
      if (!visionAlive) break;
      Thread.sleep(5);
    }
    assertFalse(visionAlive, "the hung vision process is stopped");
    vdu.releaseRequests();
    vdu.awaitPass();
  }

  // P1: a pass that ended normally on a still-idle machine does not loop relaunches.
  @Test void normallyEndedPassDoesNotLoopRelaunchesOnAStillIdleMachine() throws Exception {
    vdu = new VduResumeSeam(directory, true);
    vdu.tick();
    assertTrue(vdu.allRead(), "the normal pass read the pictures: " + vdu.states);
    int launches = vdu.launches();
    int passes = vdu.passes.get();
    for (int i = 0; i < 5; i++) {
      vdu.advance(Duration.ofMillis(VduResumeSeam.IDLE + 1_000));
      vdu.tick();
    }
    assertEquals(launches, vdu.launches(), "no relaunch on a still-idle machine");
    assertEquals(passes, vdu.passes.get());
  }

  /** A tick; when it starts a pass, the vision server is killed at its first model request. */
  private boolean tickCrashingVision() throws Exception {
    int before = vdu.requests();
    int passes = vdu.passes.get();
    Files.writeString(vdu.seam.fakeHome().resolve("pause-completion"), "hold the model exchange");
    vdu.sampler.checkOnce();
    VduResumeSeam.awaitFor(() -> vdu.requests() > before || !vdu.coordinator.isProcessing(), 30);
    boolean crashed = false;
    if (vdu.requests() > before) {
      assertTrue(vdu.newestIsVision());
      vdu.killNewestServer();
      crashed = true;
    }
    vdu.releaseRequests();
    vdu.awaitPass();
    if (!crashed) assertEquals(passes, vdu.passes.get(), "a pass that selected documents reaches the model");
    return crashed;
  }

  private void assertNotCrashLoop(String why) {
    assertNotEquals(FailedCode.CRASH_LOOP, vdu.manager.lastChatFailedCode(), why);
    var failure = vdu.manager.view().lastFailure();
    assertFalse(failure != null && "crash_loop".equals(failure.wireCode()),
        why + ": status reports " + (failure == null ? null : failure.wireCode()));
  }

  private void assertNoFailedOutcome(String why) {
    for (Map.Entry<String, List<VduUpdateOutcome>> entry : vdu.outcomes.entrySet()) {
      assertFalse(entry.getValue().contains(VduUpdateOutcome.VDU_UPDATE_OUTCOME_FAILED),
          why + ": " + vdu.outcomes);
    }
  }

  private void assertChatUsable() throws Exception {
    assertEquals("A clear document with visible words.", vdu.service.chatCompletion(
        List.of(Map.of("role", "user", "content", "hello")), 32,
        io.justsearch.app.api.SamplingParams.VDU, TestEngineContexts.ui()).get(10, TimeUnit.SECONDS),
        "normal chat answers");
  }

  private Object activeServer() throws Exception {
    return field(field(vdu.manager, "serverOps"), "activeServer");
  }

  private static Object field(Object target, String name) throws Exception {
    for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
      try {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
      } catch (NoSuchFieldException next) {
        // look further up
      }
    }
    throw new NoSuchFieldException(name);
  }
}
