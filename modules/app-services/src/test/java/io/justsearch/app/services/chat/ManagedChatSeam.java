/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.chat;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.inference.InferenceConfig;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.services.bootstrap.BootstrapInferenceFactory;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.execution.TestEngineExecutors;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.slf4j.LoggerFactory;

/**
 * The Engine's lifecycle manager as its composition root builds it, launching {@link
 * SlowStartFakeLlamaServer}: the real seam the chat controller tests run against.
 */
public final class ManagedChatSeam implements AutoCloseable {

  private final Path directory;
  private final Path fakeHome;
  private final Path executable;
  private final ConfigStore previousStore;
  private final String previousLite;
  private final TestEngineExecutors executors;
  private final InferenceConfig base;
  private InferenceLifecycleManager manager;

  public ManagedChatSeam(Path directory, long startDelayMs) throws Exception {
    this(directory, startDelayMs, null);
  }

  ManagedChatSeam(Path directory, long startDelayMs,
      io.justsearch.app.api.runtime.child.ChildControl control) throws Exception {
    this.directory = directory;
    previousStore = ConfigStore.globalOrNull();
    previousLite = System.getProperty("justsearch.lite.mode");
    System.setProperty("justsearch.lite.mode", "false");
    fakeHome = directory.resolve("fake-llama");
    executable = SlowStartFakeLlamaServer.install(fakeHome, startDelayMs);
    Path model = Files.writeString(directory.resolve("model.gguf"), "test fixture; never loaded");
    ResolvedConfig resolved = snapshot(model, freePort());
    ConfigStore.setGlobal(new ConfigStore(resolved));
    executors = new TestEngineExecutors();
    manager = control == null ? BootstrapInferenceFactory.createInferenceManager(executors, true, resolved,
        directory.toString(), null, LoggerFactory.getLogger(getClass()),
        ManagedChildRegistry.noop()) : BootstrapInferenceFactory.createInferenceManager(
            executors, true, resolved, directory.toString(), null,
            LoggerFactory.getLogger(getClass()), control);
    assertNotNull(manager, "the composition root builds the manager");
    base = manager.currentConfig();
  }

  public InferenceLifecycleManager manager() {
    return manager;
  }

  public InferenceConfig base() {
    return base;
  }

  /** Where the fake servers are installed and record their launches. */
  public Path fakeHome() {
    return fakeHome;
  }

  void holdPortBinding() throws IOException {
    Files.writeString(fakeHome.resolve("hold-port"), "held");
  }

  void awaitPortBinding(int launchCount) throws Exception {
    await(() -> models().size() == launchCount, "the candidate to launch");
    long pid = SlowStartFakeLlamaServer.launches(fakeHome).get(launchCount - 1).pid();
    await(() -> Files.exists(fakeHome.resolve("port-entered-" + pid)),
        "the candidate to wait before binding its port");
  }

  void releasePortBinding() throws IOException {
    Files.deleteIfExists(fakeHome.resolve("hold-port"));
  }

  void holdHealth() throws IOException {
    Files.writeString(fakeHome.resolve("hold-health"), "held");
  }

  void awaitHealth(int launchCount) throws Exception {
    await(() -> models().size() == launchCount, "the candidate to launch");
    long pid = SlowStartFakeLlamaServer.launches(fakeHome).get(launchCount - 1).pid();
    await(() -> Files.exists(fakeHome.resolve("health-entered-" + pid)),
        "the candidate's health request to enter");
  }

  void releaseHealth() throws IOException {
    Files.deleteIfExists(fakeHome.resolve("hold-health"));
  }

  /** Changes how long each later launch takes to become healthy. */
  void startDelay(long startDelayMs) throws IOException {
    SlowStartFakeLlamaServer.install(fakeHome, startDelayMs);
  }

  /** Closes the manager now (quit); {@link #close} then only cleans up. */
  public void quit() {
    InferenceLifecycleManager closing = manager;
    manager = null;
    if (closing != null) closing.close();
  }

  /** The {@code -m} model file name of every launch, in order. */
  public List<String> models() {
    try {
      return SlowStartFakeLlamaServer.launches(fakeHome).stream()
          .map(SlowStartFakeLlamaServer.Launch::model).toList();
    } catch (IOException failure) {
      throw new IllegalStateException(failure);
    }
  }

  public boolean noneAlive() {
    try {
      return SlowStartFakeLlamaServer.noneAlive(fakeHome);
    } catch (IOException failure) {
      throw new IllegalStateException(failure);
    }
  }

  /** The base configuration with another model file. */
  public InferenceConfig withModel(String name) throws IOException {
    Path model = model(name);
    return new InferenceConfig(base.serverExecutable(), model, base.mmprojPath(),
        base.serverPort(), base.contextSize(), base.gpuLayers(), false);
  }

  /** The resolved snapshot that goes with {@link #withModel}. */
  ResolvedConfig resolvedWithModel(String name) throws IOException {
    return snapshot(model(name), base.serverPort());
  }

  private Path model(String name) throws IOException {
    Path model = directory.resolve(name);
    if (!Files.exists(model)) Files.writeString(model, "test fixture; never loaded");
    return model;
  }

  public static void await(BooleanSupplier condition, String what) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting for " + what);
      Thread.sleep(20);
    }
  }

  @Override
  public void close() throws Exception {
    try {
      quit();
    } finally {
      try {
        executors.close();
      } finally {
        SlowStartFakeLlamaServer.endAll(fakeHome);
        awaitFilesReleased();
        TestResolvedConfigHelper.restoreGlobal(previousStore);
        if (previousLite == null) System.clearProperty("justsearch.lite.mode");
        else System.setProperty("justsearch.lite.mode", previousLite);
      }
    }
  }

  /**
   * Waits until Windows has let go of what the ended servers had open: their inherited log and
   * working directory. Every launched server has exited by now and the Restart Manager names no
   * process holding them, yet the file system keeps them shared for a few milliseconds after the
   * last exit — long enough for the {@code @TempDir} delete that follows to fail. A path is free
   * when it can be renamed, which needs the same access the delete does. Only what a server
   * process held is probed: the server logs, the self-test logs and the fake's home, its working
   * directory.
   */
  private void awaitFilesReleased() throws IOException, InterruptedException {
    List<Path> paths = new java.util.ArrayList<>();
    for (Path root : List.of(directory.resolve("data").resolve("logs"), fakeHome,
        directory.resolve("selftest"))) {
      if (!Files.isDirectory(root)) continue;
      try (var walk = Files.walk(root)) {
        walk.sorted(java.util.Comparator.reverseOrder()).forEach(paths::add);
      }
    }
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    for (Path path : paths) {
      Path probe = path.resolveSibling(path.getFileName() + ".release-probe");
      while (Files.exists(path)) {
        try {
          Files.move(path, probe);
          Files.move(probe, path);
          break;
        } catch (IOException stillShared) {
          if (System.nanoTime() > deadline) throw stillShared;
          Thread.sleep(5);
        }
      }
    }
  }

  private ResolvedConfig snapshot(Path model, int port) {
    var builder = new ResolvedConfigBuilder();
    builder.putDefault("justsearch.home", directory.toString());
    builder.putDefault("justsearch.data.dir", directory.resolve("data").toString());
    builder.putDefault("justsearch.server.exe", executable.toString());
    builder.put("justsearch.llm.model_path", 400, "env_var", "test", model.toString());
    builder.putDefault("justsearch.server.port", Integer.toString(port));
    builder.putDefault("justsearch.context.size", "4096");
    builder.putDefault("justsearch.gpu.layers", "0");
    builder.putDefault("justsearch.ai.disabled", "false");
    builder.putDefault("justsearch.llm.enabled", "true");
    return builder.build();
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }
}
