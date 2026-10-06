/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.inference.telemetry.NoopInferenceTelemetryEvents;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec A §9.2 U10 (READY) and T27 on the real seam: a lifecycle manager runs llama-server as a
 * separate stand-in process ({@link HealthyFakeLlamaServer}) on the CPU ladder (8192, 4096), the
 * process is ended the way a crash ends it, and the manager's own crash handling decides. There is
 * no component monitor in this test: whatever relaunch happens is the chat controller's own (Spec A
 * §7.2). The READY time is measured on the manager's monotonic clock, which the test advances.
 */
final class ChatCrashRestartTest {
  /** Inside the build's 30 s per-test timeout, so a relaunch that never comes fails as an assertion. */
  private static final Duration RELAUNCH_BOUND = Duration.ofSeconds(15);
  private static final Duration CRASH_SEEN_BOUND = Duration.ofSeconds(8);

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

  /**
   * T28 then T29: a crash after ten minutes of READY is relaunched once with the same
   * configuration; the next crash, however long it served, leaves chat FAILED with crash_loop.
   */
  @Test
  void aCrashAfterTenMinutesOfReadyRelaunchesOnceWithTheSameConfiguration() throws Exception {
    manager = onlineManager();
    advance(Duration.ofMinutes(11));

    HealthyFakeLlamaServer.crashLatest(fakeDir);

    assertTrue(await(() -> launches() == 2 && manager.isOnline(), RELAUNCH_BOUND),
        "a crash after a stable READY window is relaunched once; launches=" + launches()
            + " online=" + manager.isOnline());
    List<List<String>> both = HealthyFakeLlamaServer.launches(fakeDir);
    assertEquals(window(both.get(0)), window(both.get(1)), "the same configuration, same rung");

    advance(Duration.ofMinutes(11));
    HealthyFakeLlamaServer.crashLatest(fakeDir);

    assertTrue(await(() -> !manager.isOnline(), CRASH_SEEN_BOUND), "the crash is seen");
    Thread.sleep(2_000);
    assertEquals(2, launches(), "the one same-configuration restart is spent");
    assertFalse(manager.isOnline());
    assertEquals("CRASH_LOOP", lastFailedCode());
  }

  /** U10 / T29: a crash before ten minutes of READY is a crash loop, with no relaunch. */
  @Test
  void aCrashWithinTenMinutesOfReadyFailsAsACrashLoopWithoutRelaunch() throws Exception {
    manager = onlineManager();
    advance(Duration.ofMinutes(10).minusSeconds(1));

    HealthyFakeLlamaServer.crashLatest(fakeDir);

    assertTrue(await(() -> !manager.isOnline(), CRASH_SEEN_BOUND), "the crash is seen");
    Thread.sleep(2_000);
    assertEquals(1, launches(), "no same-configuration restart inside the stability window");
    assertFalse(manager.isOnline());
    assertEquals("CRASH_LOOP", lastFailedCode());
  }

  /**
   * T27: llama-server runs out of memory while serving. The window steps down one rung (8192 to
   * 4096) and the server is relaunched there at once, whatever the READY time.
   */
  @Test
  void anOutOfMemoryWhileServingStepsDownOneRungAndServesThere() throws Exception {
    manager = onlineManager("--oom-on-signal");

    HealthyFakeLlamaServer.signalOutOfMemory(fakeDir);

    assertTrue(await(() -> launches() == 2 && manager.isOnline(), RELAUNCH_BOUND),
        "an out-of-memory exit while serving is relaunched one rung lower; launches=" + launches()
            + " online=" + manager.isOnline());
    List<List<String>> both = HealthyFakeLlamaServer.launches(fakeDir);
    assertEquals(8192, window(both.get(0)));
    assertEquals(4096, window(both.get(1)));
    assertEquals(4096, manager.launchedContextWindow().rung());
    assertEquals("stepped-from:8192", manager.launchedContextWindow().reason());
  }

  // ==================== helpers ====================

  private InferenceLifecycleManager onlineManager(String... behaviour) throws Exception {
    Path executable = HealthyFakeLlamaServer.install(fakeDir, behaviour);
    Path model = Files.createFile(tmp.resolve("model.gguf"));
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
    useTestClock(created);
    created.switchToOnlineMode();
    assertTrue(created.isOnline(), "the manager starts online");
    assertEquals(1, HealthyFakeLlamaServer.launches(fakeDir).size());
    return created;
  }

  /**
   * Gives the manager's launcher the test's monotonic clock. On a tree without the seam the real
   * clock stays, so the stability window cannot have passed there.
   */
  private void useTestClock(InferenceLifecycleManager target) throws Exception {
    Field field = InferenceLifecycleManager.class.getDeclaredField("serverOps");
    field.setAccessible(true);
    Object ops = field.get(target);
    Field monotonic;
    try {
      monotonic = ops.getClass().getDeclaredField("monotonicNanos");
    } catch (NoSuchFieldException absent) {
      return;
    }
    monotonic.setAccessible(true);
    monotonic.set(ops, (LongSupplier) clock::get);
  }

  private void advance(Duration by) {
    clock.addAndGet(by.toNanos());
  }

  /** The manager's latched chat FAILED code, or "none" on a tree that has none. */
  private String lastFailedCode() throws Exception {
    Method code;
    try {
      code = InferenceLifecycleManager.class.getMethod("lastChatFailedCode");
    } catch (NoSuchMethodException absent) {
      return "none";
    }
    return String.valueOf(code.invoke(manager));
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

  private static int freePort() throws java.io.IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }
}
