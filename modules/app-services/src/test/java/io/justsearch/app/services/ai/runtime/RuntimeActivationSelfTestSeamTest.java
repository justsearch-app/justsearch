/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.runtime;

import static io.justsearch.app.services.chat.ManagedChatSeam.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.justsearch.app.api.AiRuntimeActivationStatus;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.SettingsService;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.services.chat.ManagedChatSeam;
import io.justsearch.app.services.chat.SlowStartFakeLlamaServer;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import io.justsearch.app.services.settings.SettingsServiceImpl;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.TestEngineExecutors;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec A step 5 at the real process seam (U11, I6), seen from outside: a runtime activation on an
 * Engine whose chat server is up. The candidate is a real launch of the fake server, made where
 * the self-test makes it (the variant executable in this fixture is not runnable, so the
 * package-private self-test seam stands in for the launch and launches the fake itself). The
 * fake's own launch records show which servers were alive: the main server is stopped before the
 * candidate starts, the two are never alive together, and the main server loads again once after
 * the activation.
 */
final class RuntimeActivationSelfTestSeamTest {
  private static final long START_DELAY_MS = 300;

  @TempDir Path tmp;

  private final Map<String, String> previousProps = new HashMap<>();
  private final TestEngineExecutors processExecutors = new TestEngineExecutors();
  private ManagedChatSeam seam;
  private RuntimeIntentTestFixture intent;
  private RuntimeReconciler reconciler;
  private AutoCloseable following;

  @AfterEach
  void tearDown() throws Exception {
    try {
      if (following != null) following.close();
      if (reconciler != null) reconciler.close();
      if (intent != null) intent.close();
    } finally {
      try {
        if (seam != null) seam.close();
      } finally {
        processExecutors.close();
        previousProps.forEach((key, value) -> {
          if (value == null) System.clearProperty(key);
          else System.setProperty(key, value);
        });
      }
    }
  }

  // U11 + I6: the main server is stopped before the candidate launches; one load after.
  @Test
  void theMainServerIsStoppedBeforeTheCandidateLaunchesAndLoadsOnceAfter() throws Exception {
    setProp("justsearch.home", tmp.toAbsolutePath().toString());
    setProp("justsearch.data.dir", tmp.toAbsolutePath().toString());
    clearProp("justsearch.chat.profile");
    clearProp("justsearch.server.exe");
    clearProp("justsearch.server.exe.source");
    seam = new ManagedChatSeam(tmp, START_DELAY_MS);
    InferenceLifecycleManager manager = seam.manager();
    boolean windowsRuntime = io.justsearch.configuration.PlatformPaths.isWindows();
    if (!windowsRuntime) {
      // Linux activates the executable supplied by its operator, while the Windows case keeps
      // exercising the installed cuda12 variant. Both run the same real-process assertions.
      setProp("justsearch.server.exe", seam.base().serverExecutable().toString());
    }
    Path variant = tmp.resolve("native-bin/llama-server/variants/cuda12/llama-server.exe");
    Files.createDirectories(variant.getParent());
    Files.writeString(variant, "not runnable; the self-test seam launches the fake instead");
    Path candidate = seam.withModel("candidate.gguf").modelPath();

    UiSettingsStore store = new UiSettingsStore(
        UiSettingsStore.PersistenceMode.READ_WRITE, tmp.resolve("settings.json"));
    UiSettings initial = store.load();
    initial.setChatEnabled(true);
    initial.setLlmModelPath(candidate.toString());
    store.replacePrepared(store.prepare(initial, new SettingsWitness(0, null)));
    ConfigStore intentConfig = new ConfigStore(ConfigStoreRebuilder.prepare(store.load()));
    if (!windowsRuntime) ConfigStore.setGlobal(intentConfig);
    intent = new RuntimeIntentTestFixture(tmp.resolve("intent"), store, intentConfig);
    reconciler = new RuntimeReconciler(
        manager,
        manager::getCurrentMode,
        () -> manager.view().usingExternalLlamaServer(),
        null,
        intent.spec(),
        manager::setActivation,
        manager::componentRecoveryPending);
    Object holds = chatHoldsOf(manager);
    if (holds != null) following = followHolds(reconciler, holds);
    reconciler.start();
    reconciler.requestBootConvergence();
    await(manager::isOnline, "boot intent to bring chat online");
    assertTrue(reconciler.awaitQuiescent(30_000));
    assertEquals(List.of("model.gguf"), seam.models());

    RuntimeActivationService svc = newService(store, holds);
    AtomicBoolean mainAliveAtCandidateLaunch = new AtomicBoolean();
    AtomicBoolean candidateHealthy = new AtomicBoolean();
    svc.setSelfTestOverrideForTest((exe, model) -> {
      mainAliveAtCandidateLaunch.set(!seam.noneAlive());
      candidateHealthy.set(runCandidate(model));
      return new RuntimeActivationService.SelfTestResult("passed", 65001, 0L,
          128L * 1024 * 1024, 128L * 1024 * 1024, List.of(), "12gb_plus", "nvml");
    });

    try (var alive = new AliveSampler(seam.fakeHome())) {
      svc.startActivate(windowsRuntime ? "cuda12" : "default");
      AiRuntimeActivationStatus status = awaitDone(svc);
      assertEquals("completed", status.state, "message=" + status.message);
      assertTrue(candidateHealthy.get(), "the candidate was launched and answered");
      assertFalse(mainAliveAtCandidateLaunch.get(),
          "U11: the main server is stopped before the candidate launches");

      await(manager::isOnline, "chat to come back after the activation");
      assertTrue(reconciler.awaitQuiescent(30_000));
      Thread.sleep(4 * START_DELAY_MS); // room for a second load to record itself, were one made
      assertTrue(alive.samples() > 0);
      assertTrue(alive.max() <= 1, "I6: never two servers alive, saw " + alive.worst());
    }
    assertEquals(List.of("model.gguf", "candidate.gguf", "model.gguf"), seam.models(),
        "the main server loads exactly once after the activation");
  }

  /** Launches the fake as the candidate, waits for its health, then ends it and its children. */
  private boolean runCandidate(Path model) {
    Process process = null;
    try {
      int port = freePort();
      Path log = tmp.resolve("selftest").resolve("candidate.log");
      Files.createDirectories(log.getParent());
      process = new ProcessBuilder(seam.base().serverExecutable().toString(),
          "-m", model.toString(), "--port", Integer.toString(port))
          .redirectErrorStream(true)
          .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
          .start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (System.nanoTime() < deadline) {
        if (healthy(port)) return true;
        Thread.sleep(20);
      }
      return false;
    } catch (Exception failure) {
      return false;
    } finally {
      if (process != null) {
        process.destroyForcibly(); // the original Process this fixture started
        try {
          process.waitFor(10, TimeUnit.SECONDS);
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
          while (!seam.noneAlive() && System.nanoTime() < deadline) Thread.sleep(20);
        } catch (Exception failure) {
          throw new AssertionError("held candidate fixture did not finish", failure);
        }
      }
    }
  }

  private static boolean healthy(int port) {
    try {
      var connection = (HttpURLConnection)
          URI.create("http://127.0.0.1:" + port + "/health").toURL().openConnection();
      connection.setConnectTimeout(500);
      connection.setReadTimeout(2_000);
      try {
        return connection.getResponseCode() == 200;
      } finally {
        connection.disconnect();
      }
    } catch (Exception notYet) {
      return false;
    }
  }

  /**
   * The service as the Engine composes it, chosen by reflection so this check runs against the
   * constructor of either side of Spec A step 5: with the chat controller's holds where the
   * service takes them, with the reconciler alone where it does not.
   */
  private RuntimeActivationService newService(UiSettingsStore store, Object holds)
      throws Exception {
    SettingsService settings = new SettingsServiceImpl(store, intent.runner());
    Constructor<?> widest = null;
    for (Constructor<?> candidate : RuntimeActivationService.class.getConstructors()) {
      if (widest == null || candidate.getParameterCount() > widest.getParameterCount()) {
        widest = candidate;
      }
    }
    Class<?>[] types = widest.getParameterTypes();
    Object[] args = new Object[types.length];
    for (int i = 0; i < types.length; i++) {
      Class<?> type = types[i];
      if (type == EngineExecutorRegistry.class) args[i] = processExecutors;
      else if (type == OnlineAiService.class) args[i] = OnlineAiService.unavailable();
      else if (type == UiSettingsStore.class) args[i] = store;
      else if (type == RuntimeReconciler.class) args[i] = reconciler;
      else if (type == SettingsService.class) args[i] = settings;
      else if (type.getSimpleName().equals("ChatHolds")) args[i] = holds;
      else args[i] = null;
    }
    return (RuntimeActivationService) widest.newInstance(args);
  }

  private static Object chatHoldsOf(InferenceLifecycleManager manager) throws Exception {
    for (Method method : manager.getClass().getMethods()) {
      if (method.getName().equals("chatHolds") && method.getParameterCount() == 0) {
        return method.invoke(manager);
      }
    }
    return null;
  }

  private static AutoCloseable followHolds(RuntimeReconciler reconciler, Object holds)
      throws Exception {
    for (Method method : RuntimeReconciler.class.getMethods()) {
      if (method.getName().equals("followHolds") && method.getParameterCount() == 1) {
        return (AutoCloseable) method.invoke(reconciler, holds);
      }
    }
    return null;
  }

  private static AiRuntimeActivationStatus awaitDone(RuntimeActivationService svc)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (System.nanoTime() < deadline) {
      AiRuntimeActivationStatus status = svc.getActivationStatus();
      if (!"running".equalsIgnoreCase(status.state)) return status;
      Thread.sleep(25);
    }
    fail("timed out waiting for the activation to finish");
    return svc.getActivationStatus();
  }

  private static int freePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  private void setProp(String key, String value) {
    previousProps.putIfAbsent(key, System.getProperty(key));
    System.setProperty(key, value);
  }

  private void clearProp(String key) {
    previousProps.putIfAbsent(key, System.getProperty(key));
    System.clearProperty(key);
  }

  /**
   * Samples how many launched servers are alive at once. Each server's handle is kept from the
   * first sample that sees it, so a pid Windows reuses after the server exited is not counted.
   */
  private static final class AliveSampler implements AutoCloseable {
    private final AtomicBoolean stop = new AtomicBoolean();
    private final AtomicInteger max = new AtomicInteger();
    private final AtomicInteger samples = new AtomicInteger();
    private final Map<Long, java.util.Optional<ProcessHandle>> handles = new ConcurrentHashMap<>();
    private final Thread thread;
    private final java.util.concurrent.atomic.AtomicReference<List<String>> worst =
        new java.util.concurrent.atomic.AtomicReference<>(List.of());

    AliveSampler(Path fakeHome) {
      thread = Thread.ofPlatform().daemon().start(() -> {
        while (!stop.get()) {
          try {
            List<String> alive = SlowStartFakeLlamaServer.launches(fakeHome).stream()
                .filter(launch -> handles.computeIfAbsent(launch.pid(), ProcessHandle::of)
                    .map(ProcessHandle::isAlive).orElse(false))
                .map(launch -> launch.model() + "#" + launch.pid())
                .toList();
            if (alive.size() > max.get()) worst.set(alive);
            max.accumulateAndGet(alive.size(), Math::max);
            samples.incrementAndGet();
          } catch (java.io.IOException | RuntimeException partialLine) {
            // a launch being recorded; the next sample reads it whole
          }
          try {
            Thread.sleep(5);
          } catch (InterruptedException interrupted) {
            return;
          }
        }
      });
    }

    int max() {
      return max.get();
    }

    int samples() {
      return samples.get();
    }

    List<String> worst() {
      return worst.get();
    }

    @Override
    public void close() throws InterruptedException {
      stop.set(true);
      thread.join(5_000);
    }
  }
}
