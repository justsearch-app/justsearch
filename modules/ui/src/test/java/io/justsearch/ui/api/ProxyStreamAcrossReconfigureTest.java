/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.javalin.Javalin;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.inference.InferenceConfig;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.inference.chat.ChatActuationRouter;
import io.justsearch.app.inference.chat.ChatDrainPolicy;
import io.justsearch.app.services.bootstrap.BootstrapInferenceFactory;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.telemetry.Telemetry;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * I3 (Spec A §5.2): a reconfigure that arrives while a reply streams through the Engine's {@code
 * /v1} proxy lets the reply complete, then relaunches. The lifecycle manager is the one the
 * composition root builds, the proxy is wired as {@code CoreApiAssembly} wires it, and llama-server
 * is a separate process ({@link StreamingFakeLlamaServer}).
 */
final class ProxyStreamAcrossReconfigureTest {

  @TempDir Path directory;

  private Path home;
  private ConfigStore previousStore;
  private String previousLite;
  private TestEngineExecutors executors;
  private InferenceLifecycleManager manager;
  private InferenceConfig base;
  private Javalin app;

  @BeforeEach
  void setUp() throws Exception {
    previousStore = ConfigStore.globalOrNull();
    previousLite = System.getProperty("justsearch.lite.mode");
    System.setProperty("justsearch.lite.mode", "false");
    home = directory.resolve("fake-llama");
    Path executable = StreamingFakeLlamaServer.install(home);
    ResolvedConfig resolved = snapshot(executable, model("model.gguf"), freePort());
    ConfigStore.setGlobal(new ConfigStore(resolved));
    executors = new TestEngineExecutors();
    manager = BootstrapInferenceFactory.createInferenceManager(executors, true, resolved,
        directory.toString(), null, LoggerFactory.getLogger(getClass()),
        ManagedChildRegistry.noop());
    base = manager.currentConfig();
  }

  @AfterEach
  void tearDown() throws Exception {
    try {
      if (app != null) app.stop();
      if (manager != null) manager.close();
    } finally {
      try {
        executors.close();
      } finally {
        StreamingFakeLlamaServer.endAll(home);
        TestResolvedConfigHelper.restoreGlobal(previousStore);
        if (previousLite == null) System.clearProperty("justsearch.lite.mode");
        else System.setProperty("justsearch.lite.mode", previousLite);
      }
    }
  }

  @Test
  @Timeout(120)
  void aReconfigureDuringAProxiedStreamCompletesTheReplyThenRelaunches() throws Exception {
    Field router = InferenceLifecycleManager.class.getDeclaredField("actuationRouter");
    router.setAccessible(true);
    Object controller = router.get(manager);
    Field drainBound = controller.getClass().getDeclaredField("drainBound");
    drainBound.setAccessible(true);
    assertEquals(Duration.ofSeconds(30), ChatActuationRouter.DRAIN_BOUND,
        "the chat drain policy allows thirty seconds");
    Duration configuredBound = (Duration) drainBound.get(controller);
    assertEquals(ChatActuationRouter.DRAIN_BOUND, configuredBound,
        "the controller built by production wiring uses the policy's drain limit");
    assertEquals(configuredBound,
        ChatDrainPolicy.forReason(StopReason.RECONFIGURE, configuredBound).bound(),
        "the reconfiguration policy retains the configured drain limit");
    manager.switchToOnlineMode();
    assertEquals(List.of("model.gguf"), launchedModels(), "chat starts once");
    String first = launches().get(0);

    app = Javalin.create(config -> {
      config.showJavalinBanner = false;
      config.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    });
    // The live server's port, as LocalApiServer resolves it: launches bind a fresh loopback port.
    OpenAiCompatController proxy = proxy(executors, manager::servingPort, manager);
    app.post("/v1/chat/completions", proxy::handleChatCompletions);
    app.start("127.0.0.1", 0);

    try (HttpClient http = HttpClient.newHttpClient()) {
      HttpRequest chat = HttpRequest.newBuilder(
              URI.create("http://127.0.0.1:" + app.port() + "/v1/chat/completions"))
          .timeout(Duration.ofSeconds(60))
          .POST(HttpRequest.BodyPublishers.ofString("{\"stream\":true}"))
          .build();
      HttpResponse<InputStream> reply =
          http.sendAsync(chat, HttpResponse.BodyHandlers.ofInputStream()).get(30, TimeUnit.SECONDS);
      try (InputStream body = reply.body()) {
        if (reply.statusCode() != 200) {
          throw new AssertionError("the proxied reply starts, but was " + reply.statusCode() + ": "
              + new String(body.readAllBytes(), StandardCharsets.UTF_8));
        }
        readAtLeast(body, 32_768);
        assertEquals(1, manager.generationActivity().inFlight(),
            "the real proxied stream owns its generation lease");

        InferenceConfig target = withModel("b.gguf");
        CompletableFuture<Throwable> reconfigure = background(() -> manager.applyConfig(
            target, InferenceLifecycleManager.RestartPolicy.RESTART_IF_ONLINE));

        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean draining = false;
        while (!reconfigure.isDone() && System.nanoTime() < until) {
          try {
            var probe = manager.admitExchange(
                io.justsearch.app.inference.chat.GenerationTracker.Kind.AUX, null);
            probe.close();
            Thread.sleep(5);
          } catch (IllegalStateException closedAdmission) {
            draining = true;
            break;
          }
        }
        assertTrue(draining, "reconfiguration entered its drain and closed admission");
        long observationEnd = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (true) {
          assertEquals(1, manager.generationActivity().inFlight(),
              "the drain is held by the proxied stream's generation lease");
          assertEquals(List.of("model.gguf"), launchedModels(),
              "no relaunch while the proxied reply is open");
          assertFalse(reconfigure.isDone(), "the reconfigure waits for the proxied reply");
          if (System.nanoTime() >= observationEnd) break;
          Thread.sleep(5);
        }

        StreamingFakeLlamaServer.release(home);
        String rest = new String(body.readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(rest.contains(StreamingFakeLlamaServer.FINAL_EVENT),
            "the proxied reply completes: " + tail(rest));
        assertTrue(rest.contains("data: [DONE]"), "through its last event: " + tail(rest));

        assertNull(reconfigure.get(60, TimeUnit.SECONDS), "then the reconfigure completes");
      }
    }

    assertEquals(List.of("model.gguf", "b.gguf"), launchedModels(), "one relaunch, to the target");
    List<String> events = StreamingFakeLlamaServer.events(home);
    long firstPid = Long.parseLong(first.split(" ")[2]);
    int done = events.indexOf("done " + firstPid);
    int relaunch = events.indexOf(launches().get(1));
    assertTrue(done >= 0 && done < relaunch,
        "the reply on the first server completes before the relaunch: " + events);
    assertEquals("b.gguf", manager.currentConfig().modelPath().getFileName().toString());
    assertTrue(manager.isOnline());
  }

  // ==================== helpers ====================

  /**
   * The proxy as {@code CoreApiAssembly} wires it: exchanges admitted by the lifecycle manager.
   * Reflective so this check also runs at revisions without that admission seam (fail-before
   * evidence); there it is the public constructor, which is how {@code CoreApiAssembly} built the
   * proxy then.
   */
  private static OpenAiCompatController proxy(
      EngineExecutorRegistry executors, IntSupplier port, InferenceLifecycleManager manager)
      throws Exception {
    Class<?> admissionType;
    try {
      admissionType = Class.forName(OpenAiCompatController.class.getName() + "$ExchangeAdmission");
    } catch (ClassNotFoundException before) {
      return new OpenAiCompatController(executors, port, null);
    }
    Method admitExchange = Arrays.stream(InferenceLifecycleManager.class.getMethods())
        .filter(method -> method.getName().equals("admitExchange"))
        .findFirst()
        .orElseThrow();
    Object admission = Proxy.newProxyInstance(admissionType.getClassLoader(),
        new Class<?>[] {admissionType}, (self, method, args) -> {
          if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
              case "hashCode" -> System.identityHashCode(self);
              case "equals" -> self == args[0];
              default -> "admission(" + manager + ")";
            };
          }
          try {
            return admitExchange.invoke(manager, args);
          } catch (InvocationTargetException failure) {
            throw failure.getCause();
          }
        });
    return (OpenAiCompatController) OpenAiCompatController.class
        .getConstructor(EngineExecutorRegistry.class, IntSupplier.class, Telemetry.class,
            admissionType)
        .newInstance(executors, port, null, admission);
  }

  private interface Call {
    void run() throws Exception;
  }

  private static CompletableFuture<Throwable> background(Call call) {
    var result = new CompletableFuture<Throwable>();
    Thread.ofPlatform().daemon().start(() -> {
      try {
        call.run();
        result.complete(null);
      } catch (Throwable failure) {
        result.complete(failure);
      }
    });
    return result;
  }

  private List<String> launches() throws IOException {
    return StreamingFakeLlamaServer.events(home).stream()
        .filter(event -> event.startsWith("launch ")).toList();
  }

  private List<String> launchedModels() throws IOException {
    return launches().stream().map(event -> event.split(" ")[1]).toList();
  }

  private static void readAtLeast(InputStream body, int bytes) throws IOException {
    byte[] buffer = new byte[8192];
    int total = 0;
    while (total < bytes) {
      int read = body.read(buffer);
      if (read < 0) throw new AssertionError("the reply ended after " + total + " bytes");
      total += read;
    }
  }

  private static String tail(String text) {
    return text.length() <= 200 ? text : "..." + text.substring(text.length() - 200);
  }

  private InferenceConfig withModel(String name) throws IOException {
    return new InferenceConfig(base.serverExecutable(), model(name), base.mmprojPath(),
        base.serverPort(), base.contextSize(), base.gpuLayers(), false);
  }

  private Path model(String name) throws IOException {
    Path model = directory.resolve(name);
    if (!Files.exists(model)) Files.writeString(model, "test fixture; never loaded");
    return model;
  }

  private ResolvedConfig snapshot(Path executable, Path model, int port) {
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
