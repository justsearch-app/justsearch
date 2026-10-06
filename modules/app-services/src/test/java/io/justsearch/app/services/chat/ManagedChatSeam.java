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
final class ManagedChatSeam implements AutoCloseable {

  private final Path directory;
  private final Path fakeHome;
  private final Path executable;
  private final ConfigStore previousStore;
  private final String previousLite;
  private final TestEngineExecutors executors;
  private final InferenceConfig base;
  private InferenceLifecycleManager manager;

  ManagedChatSeam(Path directory, long startDelayMs) throws Exception {
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
    manager = BootstrapInferenceFactory.createInferenceManager(executors, true, resolved,
        directory.toString(), null, LoggerFactory.getLogger(getClass()),
        ManagedChildRegistry.noop());
    assertNotNull(manager, "the composition root builds the manager");
    base = manager.currentConfig();
  }

  InferenceLifecycleManager manager() {
    return manager;
  }

  InferenceConfig base() {
    return base;
  }

  /** Changes how long each later launch takes to become healthy. */
  void startDelay(long startDelayMs) throws IOException {
    SlowStartFakeLlamaServer.install(fakeHome, startDelayMs);
  }

  /** Closes the manager now (quit); {@link #close} then only cleans up. */
  void quit() {
    InferenceLifecycleManager closing = manager;
    manager = null;
    if (closing != null) closing.close();
  }

  /** The {@code -m} model file name of every launch, in order. */
  List<String> models() {
    try {
      return SlowStartFakeLlamaServer.launches(fakeHome).stream()
          .map(SlowStartFakeLlamaServer.Launch::model).toList();
    } catch (IOException failure) {
      throw new IllegalStateException(failure);
    }
  }

  boolean noneAlive() {
    try {
      return SlowStartFakeLlamaServer.noneAlive(fakeHome);
    } catch (IOException failure) {
      throw new IllegalStateException(failure);
    }
  }

  /** The base configuration with another model file. */
  InferenceConfig withModel(String name) throws IOException {
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

  static void await(BooleanSupplier condition, String what) throws Exception {
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
        TestResolvedConfigHelper.restoreGlobal(previousStore);
        if (previousLite == null) System.clearProperty("justsearch.lite.mode");
        else System.setProperty("justsearch.lite.mode", previousLite);
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
