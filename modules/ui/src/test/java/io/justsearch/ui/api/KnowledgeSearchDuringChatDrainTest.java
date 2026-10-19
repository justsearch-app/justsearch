/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.api.chat.GenerationLease;
import io.justsearch.app.api.knowledge.SearchTrace;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.engine.EngineAdmissionController;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.inference.OnlineAiServiceImpl;
import io.justsearch.app.inference.chat.GenerationTracker;
import io.justsearch.app.services.bootstrap.BootstrapInferenceFactory;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.SearchPerSourceExecutor;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * A knowledge search that arrives while a deactivate drains a streaming reply answers without the
 * optional AI steps, exactly as when AI is off (b6d536 item 5, verifier case O2). The lifecycle
 * manager is the one the composition root builds, llama-server is a separate process ({@link
 * StreamingFakeLlamaServer}), the generative client is the production {@link OnlineAiServiceImpl},
 * and the search runs through the real {@code POST /api/knowledge/search} handler. Only the index
 * half is stubbed.
 */
final class KnowledgeSearchDuringChatDrainTest {

  private static final Duration CLOSE_WAIT = Duration.ofSeconds(20);

  @TempDir Path directory;

  private Path home;
  private ConfigStore previousStore;
  private String previousLite;
  private TestEngineExecutors executors;
  private InferenceLifecycleManager manager;

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
  }

  @AfterEach
  void tearDown() throws Exception {
    try {
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
  void aSearchDuringADeactivateDrainAnswersWithoutExpansionLikeAiOff() throws Exception {
    manager.switchToOnlineMode();
    assertTrue(manager.isOnline(), "chat starts");

    // A reply in flight: the GENERATE lease the /v1 proxy holds for a streaming exchange.
    GenerationLease reply = manager.admitExchange(GenerationTracker.Kind.GENERATE, null);
    CompletableFuture<Throwable> deactivate = background(manager::switchToIndexingMode);
    try {
      awaitAdmissionClosed();
      assertTrue(manager.isOnline(),
          "precondition: the drain keeps the mode ONLINE, so AI reports available");

      var admission = new EngineAdmissionController(4, 4, 1);
      var ai = new OnlineAiServiceImpl(admission, manager);
      List<SearchRequest> workerRequests = new CopyOnWriteArrayList<>();
      KnowledgeSearchController controller = new KnowledgeSearchController(
          bootstrap(workerRequests), mock(SearchPerSourceExecutor.class), null, ai, null, null,
          ConfigStore.global());

      List<Integer> statuses = new CopyOnWriteArrayList<>();
      List<Object> bodies = new CopyOnWriteArrayList<>();
      try (EngineWorkHandle request = admission.admit(TestRequestContexts.browser(), false)) {
        Context ctx = searchContext(request.context(), statuses, bodies);
        assertDoesNotThrow(() -> controller.handleSearch(ctx));
      }

      assertEquals(List.of(), statuses,
          "the search answers (no error status) while chat drains: " + bodies);
      assertEquals(1, bodies.size(), "one response body");
      @SuppressWarnings("unchecked")
      Map<String, Object> out = assertInstanceOf(Map.class, bodies.get(0));
      SearchTrace trace = assertInstanceOf(SearchTrace.class, out.get("searchTrace"));
      SearchTrace.TraceStage expansion = trace.stages().stream()
          .filter(stage -> stage.id() == SearchTrace.StageId.EXPANSION)
          .findFirst()
          .orElseThrow(() -> new AssertionError("no expansion stage: " + trace.stages()));
      assertEquals(SearchTrace.StageStatus.SKIPPED, expansion.status());
      assertEquals("AI_UNAVAILABLE", expansion.reason(),
          "the same skip reason as when AI is off");
      assertEquals(1, workerRequests.size(), "only the base search ran; no expanded re-search");
      assertTrue(manager.isOnline(), "the search did not end the drain");
    } finally {
      reply.close();
    }
    assertNull(deactivate.get(60, TimeUnit.SECONDS), "the deactivate completes after the reply");
    assertFalse(manager.isOnline());
  }

  // ==================== helpers ====================

  /** Waits until the deactivate has closed generation admission (the drain is under way). */
  private void awaitAdmissionClosed() throws InterruptedException {
    long deadline = System.nanoTime() + CLOSE_WAIT.toNanos();
    while (true) {
      try {
        GenerationLease probe = manager.admitExchange(GenerationTracker.Kind.AUX, null);
        try (probe) {
          // Still open; the deactivate has not reached its drain yet.
        }
      } catch (IllegalStateException closed) {
        return;
      }
      if (System.nanoTime() > deadline) fail("the deactivate never closed generation admission");
      Thread.sleep(10);
    }
  }

  @SuppressWarnings("unchecked")
  private static KnowledgeServerBootstrap bootstrap(List<SearchRequest> workerRequests) {
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.search(any(SearchRequest.class), any(EngineContext.class)))
        .thenAnswer(invocation -> {
          workerRequests.add(invocation.getArgument(0));
          return SearchResponse.getDefaultInstance();
        });
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    KnowledgeServerBootstrap.ClientLease lease = mock(KnowledgeServerBootstrap.ClientLease.class);
    when(bootstrap.publicationLock()).thenReturn(ConfigStore.global().publicationLock());
    when(bootstrap.acquireClientLease()).thenReturn(lease);
    when(lease.client()).thenReturn(client);
    when(lease.withClient(any())).thenAnswer(invocation ->
        ((java.util.function.Function<KnowledgeClient, ?>) invocation.getArgument(0)).apply(client));
    return bootstrap;
  }

  private static Context searchContext(
      EngineContext engineContext, List<Integer> statuses, List<Object> bodies) {
    Context context = mock(Context.class);
    when(context.attribute(RequestEngineContext.ATTRIBUTE)).thenReturn(engineContext);
    when(context.path()).thenReturn("/api/knowledge/search");
    when(context.bodyAsClass(Map.class)).thenReturn(Map.of(
        "query", "how do solar panels turn sunlight into power",
        "mode", "text",
        "pipeline", Map.of("sparseEnabled", true, "expansionEnabled", true)));
    when(context.status(anyInt())).thenAnswer(invocation -> {
      statuses.add(invocation.getArgument(0));
      return context;
    });
    when(context.json(any())).thenAnswer(invocation -> {
      bodies.add(invocation.getArgument(0));
      return context;
    });
    return context;
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
    // Query understanding is the second optional step a search fires at the generative client.
    builder.putDefault("justsearch.qu.enabled", "true");
    builder.putDefault("justsearch.filter_norm.enabled", "false");
    return builder.build();
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }
}
