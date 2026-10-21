/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.justsearch.app.api.WorkerServices;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.app.services.bootstrap.OperationAuthority;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.KnowledgeServerConfig;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.context.EngineContext;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.coordination.InProcessWorkerSignalBus;
import io.justsearch.indexerworker.server.DefaultWorkerAppServices;
import io.justsearch.indexerworker.server.KnowledgeServer;
import io.justsearch.ui.api.*;
import io.justsearch.ui.api.routes.DebugRoutes;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Native runtime/producer, real Engine root/bootstrap and actual admin HTTP handler. Models stubbed. */
@Timeout(60)
final class RuntimeReloadProducersHttpIntegrationTest {
  @TempDir Path directory;
  private static final EngineContext CONTEXT = EngineProvenance.internal(
      "reload-producer-check", EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.FOREGROUND);

  @Test
  void oldProducerMustExitBeforeItsRuntimeDrains() throws Exception {
    try (var f = new Fixture()) {
      var runtime = field(f.server, "ingestLifecycle");
      var closing = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var old = f.spyProducer();
      org.mockito.stubbing.Answer<Object> stop = invocation -> {
        closing.countDown();
        assertTrue(release.await(5, TimeUnit.SECONDS));
        return invocation.callRealMethod();
      };
      doAnswer(stop).when(old).close();
      doAnswer(stop).when(old).closeNativeLoopRetainingModels();
      var reload = CompletableFuture.supplyAsync(f::post);
      try {
        assertTrue(closing.await(5, TimeUnit.SECONDS));
        assertTrue(((io.justsearch.adapters.lucene.runtime.RunningRuntime) runtime).isAcceptingWrites(),
            "old producer still owns work: runtime must remain open until producer close succeeds");
      } finally { release.countDown(); }
      assertReloadSuccess(reload.get(10, TimeUnit.SECONDS));
    }
  }

  @Test
  void admittedWatcherMutationMustExitBeforeItsRuntimeDrains() throws Exception {
    try (var f = new Fixture()) {
      var old = (DefaultWorkerAppServices) f.server.appServices();
      var runtime = (io.justsearch.adapters.lucene.runtime.RunningRuntime)
          field(f.server, "ingestLifecycle");
      var gate = (java.util.concurrent.locks.ReentrantReadWriteLock)
          field(old.mutationAdmission(), "lock");
      CompletableFuture<HttpResponse<String>> reload;
      try (var _ = old.mutationAdmission().enter(old.mutationOwnerToken())) {
        reload = CompletableFuture.supplyAsync(f::post);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!gate.hasQueuedThreads() && !reload.isDone() && System.nanoTime() < until) {
          Thread.sleep(5);
        }
        assertTrue(runtime.isAcceptingWrites(), "an issued watcher effect still owns the native runtime");
        assertTrue(gate.hasQueuedThreads(), "reload must drain the admitted watcher mutation");
        assertFalse(reload.isDone());
      }
      assertReloadSuccess(reload.get(10, TimeUnit.SECONDS));
      assertThrows(IllegalStateException.class,
          () -> old.mutationAdmission().enter(old.mutationOwnerToken()),
          "a late old callback cannot enter after its runtime closes");
    }
  }

  @Test
  void shutdownWinningDuringProducerDrainCannotPublishAReplacement() throws Exception {
    try (var f = new Fixture()) {
      var old = (DefaultWorkerAppServices) f.server.appServices();
      var gate = (java.util.concurrent.locks.ReentrantReadWriteLock)
          field(old.mutationAdmission(), "lock");
      CompletableFuture<HttpResponse<String>> reload;
      CompletableFuture<Void> closed;
      try (var _ = old.mutationAdmission().enter(old.mutationOwnerToken())) {
        reload = CompletableFuture.supplyAsync(f::post);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!gate.hasQueuedThreads() && !reload.isDone() && System.nanoTime() < until) {
          Thread.sleep(5);
        }
        assertTrue(gate.hasQueuedThreads(), "the issued mutation keeps the native swap pending");
        closed = CompletableFuture.runAsync(() -> assertDoesNotThrow(f.server::close));
        until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!(boolean) field(f.server, "closeStarted") && System.nanoTime() < until) Thread.sleep(5);
        assertTrue((boolean) field(f.server, "closeStarted"));
      }
      var refused = reload.get(10, TimeUnit.SECONDS);
      assertEquals(500, refused.statusCode(), refused.body());
      assertTrue(refused.body().contains("during close"), refused.body());
      closed.get(10, TimeUnit.SECONDS);
      assertTrue(f.server.awaitClosed(0));
      assertThrows(IllegalStateException.class, f.server::captureServingView);
    }
  }

  @Test
  void mutationDrainTimeoutRestoresAdmissionAndRetryWorks() throws Exception {
    try (var f = new Fixture()) {
      var old = (DefaultWorkerAppServices) f.server.appServices();
      var runtime = (io.justsearch.adapters.lucene.runtime.RunningRuntime)
          field(f.server, "ingestLifecycle");
      try (var _ = old.mutationAdmission().enter(old.mutationOwnerToken())) {
        var response = f.post();
        assertEquals(500, response.statusCode(), response.body());
        assertTrue(response.body().contains("active producer mutations"), response.body());
        assertTrue(runtime.isAcceptingWrites());
        assertTrue(old.recordedWriterReady());
        assertNotNull(f.client.search("stillopen", 10, CONTEXT));
      }
      assertReloadSuccess(f.post());
    }
  }

  @Test
  void refusedProducerCloseRetainsItsRuntimeUntilRetry() throws Exception {
    try (var f = new Fixture()) {
      var runtime = (io.justsearch.adapters.lucene.runtime.RunningRuntime)
          field(f.server, "ingestLifecycle");
      var mayClose = new java.util.concurrent.atomic.AtomicBoolean();
      var old = f.spyProducer();
      org.mockito.stubbing.Answer<Object> stop = invocation -> {
        if (!mayClose.get()) throw new java.io.IOException("producer still owns an extraction");
        return invocation.callRealMethod();
      };
      doAnswer(stop).when(old).close();
      doAnswer(stop).when(old).closeNativeLoopRetainingModels();
      try {
        var refused = f.post();
        assertEquals(500, refused.statusCode(), refused.body());
        assertTrue(runtime.isAcceptingWrites(), "a refused producer drain must retain its runtime");
        assertSame(old, f.server.appServices(), "the refused owner must remain reachable for cleanup");
      } finally { mayClose.set(true); }
      assertReloadSuccess(f.post());
    }
  }

  @Test
  void fiveHttpReloadsImmediatelyAcceptSearchAndIngestionWithoutFalseRecovery() throws Exception {
    try (var f = new Fixture()) {
      for (int i = 0; i < 5; i++) {
        var observation = new java.util.concurrent.atomic.AtomicReference<Optional<Boolean>>();
        var old = f.spyProducer();
        org.mockito.stubbing.Answer<Object> stop = invocation -> {
          // A real monitor poll overlaps the physical transition on another thread, every time.
          observation.set(CompletableFuture.supplyAsync(f.bootstrap::tryCheckHealth)
              .get(3, TimeUnit.SECONDS));
          return invocation.callRealMethod();
        };
        doAnswer(stop).when(old).close();
        doAnswer(stop).when(old).closeNativeLoopRetainingModels();
        assertReloadSuccess(f.post());
        assertEquals(Optional.empty(), observation.get(),
            "planned reload must not publish index.failed and retire the client's executors");
        assertEquals(ComponentState.READY, f.root.indexComponent().snapshot().state());
        assertNotNull(f.client.search("reloadproducer", 10, CONTEXT));
        Path file = directory.resolve("immediate-" + i + ".txt");
        Files.writeString(file, "reloadproducer immediate ingestion " + i);
        assertEquals(1, f.client.submitBatch(List.of(file), CONTEXT).getAcceptedCount());
        assertEquals(Optional.of(true), f.bootstrap.tryCheckHealth(),
            "the replacement must accept a fresh physical health observation immediately");
      }
    }
  }

  @Test
  void retainedUnpublishedProducerMustDrainBeforeRuntimeClose() throws Exception {
    try (var f = new Fixture()) {
      var runtime = (io.justsearch.adapters.lucene.runtime.RunningRuntime)
          field(f.server, "ingestLifecycle");
      var pending = mock(io.justsearch.indexerworker.server.WorkerAppServices.class);
      var mayClose = new java.util.concurrent.atomic.AtomicBoolean();
      doAnswer(ignored -> {
        if (!mayClose.get()) throw new java.io.IOException("unpublished producer still owns work");
        return null;
      }).when(pending).close();
      set(f.server, "pendingAppServices", pending);
      try {
        assertEquals(500, f.post().statusCode());
        assertTrue(runtime.isAcceptingWrites(), "retained unpublished work must drain before runtime close");
        assertSame(pending, field(f.server, "pendingAppServices"));
      } finally { mayClose.set(true); }
      assertReloadSuccess(f.post());
    }
  }

  @Test
  void statusSamplerDuringReloadCannotPublishIndexFailure() throws Exception {
    try (var f = new Fixture()) {
      for (String name : List.of("api", "generative")) {
        var component = f.root.components().register(new io.justsearch.core.component.ComponentSpec(
            name, name.equals("api"), java.util.Set.of(),
            io.justsearch.core.component.ComponentSpec.ComposeCapability.IN_PLACE, Duration.ZERO, 0));
        if (name.equals("api")) component.transition(ComponentState.READY, null, null);
      }
      var type = Class.forName("io.justsearch.ui.api.StatusLifecycleHandler");
      var constructor = type.getDeclaredConstructors()[0];
      constructor.setAccessible(true);
      var sampler = constructor.newInstance(null, null, null, f.bootstrap, null,
          directory.resolve("index"), java.time.Instant.now(), null, null, null, null, null, null);
      var bind = type.getDeclaredMethod("setIndexComponent",
          io.justsearch.core.component.EngineComponentRegistry.class,
          io.justsearch.core.component.ComponentHandle.class);
      bind.setAccessible(true);
      bind.invoke(sampler, f.root.components(), f.root.indexComponent());
      try {
        var observed = new java.util.concurrent.atomic.AtomicReference<ComponentState>();
        var old = f.spyProducer();
        org.mockito.stubbing.Answer<Object> stop = invocation -> {
          CompletableFuture.runAsync(() -> {
            assertDoesNotThrow(() -> {
              var sample = type.getDeclaredMethod("sampleWorkerIndependently", long.class);
              sample.setAccessible(true);
              sample.invoke(sampler, 2_000L);
              var lanes = (java.util.Map<?, ?>) field(sampler, "observations");
              var publication = lanes.get("index-publication");
              if (publication != null) {
                var completion = publication.getClass().getDeclaredMethod("completion");
                completion.setAccessible(true);
                ((CompletableFuture<?>) completion.invoke(publication)).get(3, TimeUnit.SECONDS);
              }
              observed.set(f.root.indexComponent().snapshot().state());
            });
          }).get(5, TimeUnit.SECONDS);
          return invocation.callRealMethod();
        };
        doAnswer(stop).when(old).close();
        doAnswer(stop).when(old).closeNativeLoopRetainingModels();
        assertReloadSuccess(f.post());
        assertEquals(ComponentState.READY, observed.get(),
            "a planned capture refusal is not an observed index failure");
        assertEquals(ComponentState.READY, f.root.indexComponent().snapshot().state());
      } finally {
        ((io.justsearch.core.component.EngineComponentRegistry.Subscription)
            field(sampler, "componentSubscription")).close();
      }
    }
  }

  @Test
  void previouslyWatchedRootKeepsIngestingAfterReload() throws Exception {
    try (var f = new Fixture()) {
      Path watched = Files.createDirectories(directory.resolve("watched"));
      var registration = f.server.appServices().ingestService().watchRoot(
          io.justsearch.ipc.WatchRootRequest.newBuilder().setRootPath(watched.toString())
              .setRootAdded(true).setCollection("default").build(),
          io.justsearch.indexerworker.services.CallContext.none());
      assertTrue(registration.getWatching(), registration.getErrorMessage());
      Files.writeString(watched.resolve("before.txt"), "producerwatchbeforecanary");
      f.awaitSearchable("producerwatchbeforecanary");
      assertReloadSuccess(f.post());
      Files.writeString(watched.resolve("after.txt"), "producerwatchaftercanary");
      f.awaitSearchable("producerwatchaftercanary");
    }
  }

  @Test
  void replacementKeepsTheSameEmbeddingAndNerBindingsWithoutClosingSharedNer() throws Exception {
    try (var f = new Fixture()) {
      var old = (DefaultWorkerAppServices) f.server.appServices();
      var embedding = mock(io.justsearch.indexerworker.embed.EmbeddingProvider.class);
      when(embedding.isAvailable()).thenReturn(true);
      var ner = mock(io.justsearch.indexerworker.ner.NerService.class);
      var nerClosed = new java.util.concurrent.atomic.AtomicBoolean();
      doAnswer(ignored -> { nerClosed.set(true); return null; }).when(ner).close();
      old.wireEmbeddingProvider(embedding);
      old.wireNerService(ner);
      assertReloadSuccess(f.post());
      assertFalse(nerClosed.get(), "producer close must retain generation-owned NER for its successor");
      var fresh = (DefaultWorkerAppServices) f.server.appServices();
      var loop = (io.justsearch.indexerworker.loop.IndexingLoop) field(fresh, "indexingLoop");
      assertSame(embedding, loop.getEmbeddingLifecycle().embeddingProvider(),
          "a successful reload must leave enrichment able to drain its pending backlog");
      var bindings = (io.justsearch.indexerworker.server.EncoderBindings) field(fresh, "encoderBindings");
      assertSame(ner, bindings.nerService());
    }
  }

  private final class Fixture implements AutoCloseable {
    final ConfigStore previous = ConfigStore.globalOrNull();
    final EngineRoot root;
    final KnowledgeServerBootstrap bootstrap;
    final KnowledgeServer server;
    final KnowledgeClient client;
    final Javalin http;
    final HttpClient transport = HttpClient.newHttpClient();

    Fixture() throws Exception {
      var builder = ResolvedConfig.builder();
      builder.putDefault(EnvRegistry.DATA_DIR.configKey(), directory.resolve("data").toString());
      builder.putDefault(EnvRegistry.INDEX_BASE_PATH.configKey(), directory.resolve("index").toString());
      builder.putDefault("justsearch.ssot.path", directory.resolve("SSOT").toString());
      builder.putDefault(EnvRegistry.EXTRACTION_SANDBOX_MODE.configKey(), "in_process");
      builder.putDefault(EnvRegistry.RERANK_CHUNKS_ENABLED.configKey(), "false");
      for (var key : new EnvRegistry[] {EnvRegistry.AI_EMBED_ENABLED, EnvRegistry.SPLADE_ENABLED,
          EnvRegistry.NER_ENABLED, EnvRegistry.BGE_M3_ENABLED, EnvRegistry.RERANK_ENABLED,
          EnvRegistry.CITATION_SCORER_ENABLED}) builder.putDefault(key.configKey(), "false");
      var configuration = builder.build();
      ConfigStore.setGlobal(new ConfigStore(configuration));
      var built = new java.util.concurrent.atomic.AtomicReference<KnowledgeServer>();
      root = new EngineRoot(mock(OperationStore.class), mock(OperationAttemptRunner.class),
          (gauge, executors, ingestion, index, encoders) -> {
            var worker = WorkerConfig.load(configuration);
            var result = new KnowledgeServer(executors, worker, new InProcessWorkerSignalBus(gauge),
                io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), ingestion, index,
                encoders, configuration);
            built.set(result);
            return result;
          }, 5_000, 100, ignored -> {}, () -> {}, OperationAuthority.inMemory());
      var config = new KnowledgeServerConfig(false, directory.resolve("data"), directory, directory,
          5_000L, 5_000L, 1, 5_000L, 1_000L, 300_000L, 100, 5_000L, 0);
      bootstrap = new KnowledgeServerBootstrap(root.executors(), config, null, root.components(),
          root.indexComponent(), root, false, root.publicationLock());
      bootstrap.startInitial(ignored -> {});
      server = built.get();
      assertTrue(server.awaitIndexRecoveryModels(10_000));
      client = bootstrap.client();
      assertTrue(bootstrap.checkHealth());
      // Head publishes readiness after attaching a physically verified bootstrap to its API.
      // This fixture mounts that API's real admin handler without the rest of HeadAssembly.
      root.indexComponent().transition(ComponentState.READY, null, null);
      assertEquals(ComponentState.READY, root.indexComponent().snapshot().state());
      var head = mock(HeadAssembly.class);
      when(head.workers()).thenReturn(new WorkerServices(client, null, null, null));
      var api = mock(LocalApiServer.class, CALLS_REAL_METHODS);
      set(api, "HeadAssemblyRef", head);
      var handler = LocalApiServer.class.getDeclaredMethod("handleAdminRuntimeReload", Context.class);
      handler.setAccessible(true);
      http = Javalin.create();
      DebugRoutes.register(http, mock(DebugStateController.class), mock(EffectiveConfigController.class),
          mock(ChunkInfoController.class), ctx -> {}, mock(LogLevelController.class),
          mock(TimeSeriesController.class), mock(SessionPoliciesController.class), ctx -> {},
          ctx -> handler.invoke(api, ctx));
      http.start("127.0.0.1", 0);
    }

    DefaultWorkerAppServices spyProducer() throws Exception {
      var producer = spy((DefaultWorkerAppServices) server.appServices());
      var retire = KnowledgeServer.class.getDeclaredMethod("retireServingView");
      retire.setAccessible(true);
      retire.invoke(server);
      var publish = KnowledgeServer.class.getDeclaredMethod("publishServingView",
          io.justsearch.indexerworker.server.WorkerAppServices.class);
      publish.setAccessible(true);
      publish.invoke(server, producer);
      return producer;
    }

    HttpResponse<String> post() {
      try {
        return transport.send(HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + http.port() + "/api/admin/runtime/reload"))
            .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
      } catch (Exception failure) { throw new AssertionError(failure); }
    }

    void awaitSearchable(String marker) throws Exception {
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (client.search(marker, 10, CONTEXT).getResultsCount() == 0) {
        assertTrue(System.nanoTime() < until, "watched-root ingestion did not deliver " + marker);
        Thread.sleep(50);
      }
    }

    @Override public void close() throws Exception {
      try { http.stop(); transport.close(); bootstrap.close(); }
      finally {
        try { root.close(); root.processResources().close(); }
        finally {
          if (previous != null) ConfigStore.setGlobal(previous);
          else {
            var clear = ConfigStore.class.getDeclaredMethod("clearGlobal");
            clear.setAccessible(true);
            clear.invoke(null);
          }
        }
      }
    }
  }

  private static void assertReloadSuccess(HttpResponse<String> response) {
    assertEquals(200, response.statusCode(), response.body());
    assertTrue(new tools.jackson.databind.ObjectMapper().readTree(response.body())
        .path("swapDurationMs").isNumber(), response.body());
  }

  private static Object field(Object target, String name) throws Exception {
    var field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static void set(Object target, String name, Object value) throws Exception {
    var field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }
}
