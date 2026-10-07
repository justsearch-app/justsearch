/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.chat.ChatDeviceLoss;
import io.justsearch.app.api.chat.DeviceLostEvidence;
import io.justsearch.app.api.chat.FailedCode;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.inference.chat.ChatActuation;
import io.justsearch.app.inference.chat.ChatActuationRouter;
import io.justsearch.app.inference.chat.ChatActuator;
import io.justsearch.app.inference.telemetry.NoopInferenceTelemetryEvents;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec A step 10 on the real seam (§7.3 source 2, §7.4, T33): a lifecycle manager runs
 * llama-server as a separate stand-in process ({@link HealthyFakeLlamaServer}), and the manager's
 * resume probe, device-lost stop, resume timers and device-lost report act on it. There is no chat
 * controller here: the steps run through the manager's own actuator.
 */
final class ChatResumeProbeTest {
  private static final Duration BOUND = Duration.ofSeconds(15);

  @TempDir Path tmp;
  private Path fakeDir;
  private InferenceLifecycleManager manager;
  private final AtomicLong clock = new AtomicLong(System.nanoTime());

  @BeforeEach
  void setUp() {
    fakeDir = tmp.resolve("fake-llama");
  }

  @AfterEach
  void tearDown() throws Exception {
    try {
      if (manager != null) manager.close();
    } finally {
      HealthyFakeLlamaServer.endAll(fakeDir);
    }
  }

  // ==================== the resume probe (§7.4, T33) ====================

  @Test
  void aServerThatAnswersHealthAndOneTokenPassesTheProbe() throws Exception {
    manager = onlineManager();

    var result = probe();

    assertEquals(ChatActuator.ResumeProbe.of(ChatActuator.ResumeOutcome.PASSED), result);
    assertEquals(1, launches());
    assertTrue(manager.isOnline());
  }

  @Test
  void aProbeThatCannotEnterTheLaneIsDeferredWithoutStopOrRelaunch() throws Exception {
    manager = onlineManager("--completion-fails");
    long first = latestPid();
    var field = InferenceLifecycleManager.class.getDeclaredField("generation");
    field.setAccessible(true);
    var tracker = (io.justsearch.app.inference.chat.GenerationTracker) field.get(manager);
    AutoCloseable held;
    try {
      Object lane = tracker.getClass().getMethod("lane").invoke(tracker);
      held = (AutoCloseable) lane.getClass().getMethod("acquire",
          io.justsearch.app.api.EngineWorkHandle.class, long.class, java.util.function.BooleanSupplier.class)
          .invoke(lane, null, System.nanoTime() + Duration.ofSeconds(30).toNanos(),
              (java.util.function.BooleanSupplier) () -> false);
    } catch (NoSuchMethodException before) {
      held = tracker.admit(io.justsearch.app.inference.chat.GenerationTracker.Kind.ASSIST, null);
    }
    try (var laneHold = held) {
      var result = probe();
      assertEquals("DEFERRED", result.outcome().name());
      assertEquals(1, launches());
      assertEquals(first, latestPid());
      assertTrue(manager.isOnline());
      assertTrue(ProcessHandle.of(first).map(ProcessHandle::isAlive).orElse(false));
    }
  }

  @Test
  void aFailedOneTokenProbeStopsTheServerAndRelaunchesItOnce() throws Exception {
    manager = onlineManager("--completion-fails");
    long first = latestPid();

    var result = probe();

    assertEquals(ChatActuator.ResumeProbe.of(ChatActuator.ResumeOutcome.RELAUNCHED), result);
    assertTrue(await(() -> launches() == 2 && manager.isOnline(), BOUND),
        "relaunched once; launches=" + launches() + " online=" + manager.isOnline());
    assertFalse(ProcessHandle.of(first).map(ProcessHandle::isAlive).orElse(false),
        "the server that failed the probe was stopped");
    assertEquals(window(HealthyFakeLlamaServer.launches(fakeDir).get(0)),
        window(HealthyFakeLlamaServer.launches(fakeDir).get(1)), "the same configuration");
    assertNull(manager.lastChatFailedCode());
  }

  @Test
  void aProbeFailureWithDeviceLostTextReportsTheCodeAndLeavesTheServerToTheController()
      throws Exception {
    manager = onlineManager("--completion-cuda-failure");

    var result = probe();

    assertEquals(ChatActuator.ResumeProbe.deviceLost("719"), result);
    Thread.sleep(500);
    assertEquals(1, launches(), "no relaunch: a lost device is the controller's to handle");
  }

  @Test
  void withChatOffThereIsNothingToProbe() throws Exception {
    Path executable = HealthyFakeLlamaServer.install(fakeDir);
    manager = manager(executable);

    assertEquals(ChatActuator.ResumeProbe.of(ChatActuator.ResumeOutcome.NOT_SERVING), probe());
    assertEquals(0, launches());
  }

  // ==================== the device-lost stop (§7.3) ====================

  @Test
  void theDeviceLostStopEndsTheServerAndLatchesDeviceLostLimit() throws Exception {
    manager = onlineManager();
    long server = latestPid();
    ChatActuator actuator = manager.chatActuator();
    var aliveAtRead = new java.util.concurrent.atomic.AtomicReference<Boolean>();

    Boolean restart = actuator.execute(actuator.deviceLostStop(() -> {
      aliveAtRead.set(ProcessHandle.of(server).map(ProcessHandle::isAlive).orElse(false));
      return false;
    }), null);

    assertEquals(Boolean.FALSE, restart);
    assertEquals(Boolean.FALSE, aliveAtRead.get(), "the server stopped before the ledger was read");
    assertFalse(manager.isOnline());
    assertEquals(FailedCode.DEVICE_LOST_LIMIT, manager.lastChatFailedCode());
    assertTrue(await(() -> !ProcessHandle.of(server).map(ProcessHandle::isAlive).orElse(false),
        BOUND), "the server stopped");
    assertEquals(1, launches(), "nothing relaunches after a lost device over the limit");

    actuator.noteResume();
    assertNull(manager.lastChatFailedCode(), "T34: a resume clears FAILED");
  }

  @Test
  void withinTheLimitTheDeviceLostStopEndsTheServerFirstAndLatchesNothing() throws Exception {
    manager = onlineManager();
    long server = latestPid();
    ChatActuator actuator = manager.chatActuator();
    var aliveAtRead = new java.util.concurrent.atomic.AtomicReference<Boolean>();

    Boolean restart = actuator.execute(actuator.deviceLostStop(() -> {
      aliveAtRead.set(ProcessHandle.of(server).map(ProcessHandle::isAlive).orElse(false));
      return true;
    }), null);

    assertEquals(Boolean.TRUE, restart, "the Engine restarts");
    assertEquals(Boolean.FALSE, aliveAtRead.get(), "the server stopped before the ledger was read");
    assertNull(manager.lastChatFailedCode(), "no FAILED within the limit");
    assertEquals(1, launches(), "nothing relaunches; the Engine restart follows");
  }

  // ==================== the start's health deadline (§7.4) ====================

  @Test
  void aResumeRestartsTheStartsHealthDeadlineInFull() throws Exception {
    Path executable = HealthyFakeLlamaServer.install(fakeDir, "--loading-until-signal");
    manager = manager(executable);
    CompletableFuture<Void> start = CompletableFuture.runAsync(this::switchOnline);
    assertTrue(await(() -> launches() == 1, BOUND), "the server launched and loads");
    Thread.sleep(500); // the start polls health on the test clock by now
    Duration deadline = startHealthTimeout();

    advance(deadline.minusSeconds(5));
    manager.chatActuator().noteResume();
    advance(Duration.ofSeconds(30));
    Thread.sleep(1_500);

    assertFalse(start.isDone(), "suspended time is not counted: the start still waits for health");
    HealthyFakeLlamaServer.signalReady(fakeDir);
    start.get(BOUND.toSeconds(), TimeUnit.SECONDS);
    assertTrue(manager.isOnline());
  }

  @Test
  void withoutAResumeTheStartsHealthDeadlineStillEnds() throws Exception {
    Path executable = HealthyFakeLlamaServer.install(fakeDir, "--loading-until-signal");
    manager = manager(executable);
    CompletableFuture<Void> start = CompletableFuture.runAsync(this::switchOnline);
    assertTrue(await(() -> launches() == 1, BOUND), "the server launched and loads");
    Thread.sleep(500); // the start polls health on the test clock by now

    advance(startHealthTimeout().plusSeconds(25));

    assertTrue(await(start::isDone, BOUND), "the start ends at its deadline");
    assertTrue(start.isCompletedExceptionally());
    assertFalse(manager.isOnline());
  }

  // ==================== §7.3 source 2: a start that exits on a lost device ====================

  @Test
  void aStartExitNamingALostDeviceIsReportedToTheController() throws Exception {
    Path executable = HealthyFakeLlamaServer.install(fakeDir,
        "--log-line", "CUDA error: unspecified launch failure", "--exit-code", "1");
    manager = manager(executable);
    var router = new RecordingRouter(manager.chatActuator());
    manager.installActuationRouter(router);

    CompletableFuture<Void> start = CompletableFuture.runAsync(this::switchOnline);
    assertTrue(await(start::isDone, BOUND));

    assertTrue(start.isCompletedExceptionally());
    assertEquals(List.of(new DeviceLostEvidence(DeviceLostEvidence.Source.LLAMA_EXIT, "719")),
        router.lost);
  }

  @Test
  void aStartExitWithoutDeviceLostTextIsNotReported() throws Exception {
    Path executable = HealthyFakeLlamaServer.install(fakeDir,
        "--log-line", "CUDA error: out of memory", "--exit-code", "1");
    manager = manager(executable);
    var router = new RecordingRouter(manager.chatActuator());
    manager.installActuationRouter(router);

    CompletableFuture<Void> start = CompletableFuture.runAsync(this::switchOnline);
    assertTrue(await(start::isDone, BOUND));

    assertEquals(List.of(), router.lost, "out of memory is never device loss (F17)");
  }

  // ==================== helpers ====================

  /** The inline router, which also takes the controller's device-loss reports. */
  private static final class RecordingRouter implements ChatActuationRouter, ChatDeviceLoss {
    final List<DeviceLostEvidence> lost = new CopyOnWriteArrayList<>();
    private final ChatActuationRouter inline;

    RecordingRouter(ChatActuator actuator) {
      this.inline = ChatActuationRouter.inline(actuator);
    }

    @Override
    public <T> T route(ChatActuation<T> step) throws Exception {
      return inline.route(step);
    }

    @Override
    public CompletionStage<Void> setActivation(Activation activation) {
      return inline.setActivation(activation);
    }

    @Override
    public void shutdown(StopReason reason, Duration budget) {
      inline.shutdown(reason, budget);
    }

    @Override
    public void reportDeviceLost(DeviceLostEvidence evidence) {
      lost.add(evidence);
    }

    @Override
    public void reportResume() {}
  }

  private ChatActuator.ResumeProbe probe() throws Exception {
    ChatActuator actuator = manager.chatActuator();
    return actuator.execute(actuator.resumeProbe(), null);
  }

  private void switchOnline() {
    try {
      manager.switchToOnlineMode();
    } catch (ModeTransitionException failure) {
      throw new java.util.concurrent.CompletionException(failure);
    }
  }

  private InferenceLifecycleManager onlineManager(String... behaviour) throws Exception {
    Path executable = HealthyFakeLlamaServer.install(fakeDir, behaviour);
    InferenceLifecycleManager created = manager(executable);
    created.switchToOnlineMode();
    assertTrue(created.isOnline(), "the manager starts online");
    assertEquals(1, launches());
    return created;
  }

  private InferenceLifecycleManager manager(Path executable) throws Exception {
    Path model = tmp.resolve("model.gguf");
    if (!Files.exists(model)) Files.createFile(model);
    ResolvedConfig captured = TestResolvedConfigHelper.fromEntries(Map.of(
        "justsearch.context.size", "4096",
        "justsearch.data.dir", tmp.resolve("data").toString()));
    InferenceConfig configuration =
        new InferenceConfig(executable, model, null, freePort(), 4096, 0, false);
    InferenceLifecycleManager created = new InferenceLifecycleManager(
        new io.justsearch.core.execution.TestEngineExecutors(),
        configuration,
        NoopInferenceTelemetryEvents.INSTANCE,
        ManagedChildRegistry.noop(),
        captured);
    Field field = InferenceLifecycleManager.class.getDeclaredField("serverOps");
    field.setAccessible(true);
    Object ops = field.get(created);
    Field monotonic = ops.getClass().getDeclaredField("monotonicNanos");
    monotonic.setAccessible(true);
    monotonic.set(ops, (LongSupplier) clock::get);
    return created;
  }

  /** The start's health deadline, as the launcher reads it from the supervision policy. */
  private static Duration startHealthTimeout() throws Exception {
    Field timeout = LlamaServerOps.class.getDeclaredField("HEALTH_CHECK_TIMEOUT_MS");
    timeout.setAccessible(true);
    return Duration.ofMillis(timeout.getLong(null));
  }

  private void advance(Duration by) {
    clock.addAndGet(by.toNanos());
  }

  private long latestPid() throws Exception {
    List<String> pids = Files.readAllLines(fakeDir.resolve("pids.txt"));
    return Long.parseLong(pids.stream().filter(line -> !line.isBlank()).toList().getLast().trim());
  }

  private int launches() {
    try {
      return HealthyFakeLlamaServer.launches(fakeDir).size();
    } catch (java.io.IOException unreadable) {
      return -1;
    }
  }

  private static int window(List<String> argv) {
    return Integer.parseInt(argv.get(argv.indexOf("-c") + 1));
  }

  private static boolean await(BooleanSupplier condition, Duration bound)
      throws InterruptedException {
    long deadline = System.nanoTime() + bound.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) return true;
      TimeUnit.MILLISECONDS.sleep(50);
    }
    return condition.getAsBoolean();
  }

  private static int freePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }
}
