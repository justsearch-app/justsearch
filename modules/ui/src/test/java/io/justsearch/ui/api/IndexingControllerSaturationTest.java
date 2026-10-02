/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.OperationLeaseService;
import io.justsearch.app.engine.DefaultEngineExecutorRegistry;
import io.justsearch.app.engine.EngineKnowledgeClient;
import io.justsearch.app.engine.ForegroundLoadGate;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import io.justsearch.app.services.registry.operations.handlers.CancelIndexingJobHandler;
import io.justsearch.app.services.registry.operations.handlers.ClearFailedJobsHandler;
import io.justsearch.app.services.registry.operations.handlers.IndexGcHandler;
import io.justsearch.app.services.registry.operations.handlers.RetryIndexingJobHandler;
import io.justsearch.app.services.registry.operations.handlers.SettleIndexHandler;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.RemoteDocumentService;
import io.justsearch.app.services.worker.SearchPerSourceExecutor;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.server.WorkerAppServices;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

@Timeout(10)
final class IndexingControllerSaturationTest {
  @TempDir Path dataDir;
  private ConfigStore previousConfigStore;

  @BeforeEach
  void configure() {
    previousConfigStore = ConfigStore.globalOrNull();
    TestResolvedConfigHelper.storeWithDefaults();
  }

  @AfterEach
  void restore() {
    TestResolvedConfigHelper.restoreGlobal(previousConfigStore);
  }

  @ParameterizedTest
  @ValueSource(strings = {"remove", "retrieve", "openRetrieve", "status", "clear", "retry", "cancel", "gc", "settle"})
  @SuppressWarnings("unchecked")
  void admittedRequestPreservesActualSubmissionRefusal(String endpoint) throws Exception {
    String previousDataDir = System.getProperty("justsearch.data.dir");
    System.setProperty("justsearch.data.dir", dataDir.toString());
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var callPool = new AtomicReference<ThreadPoolExecutor>();
    // S6 (ee0f0806b): open retrieval now dispatches through the isolated inference pool.
    String poolName = endpoint.equals("openRetrieve")
        ? "engine-knowledge-inference-foreground" : "engine-knowledge-call-foreground";
    var services = mock(WorkerAppServices.class);
    try (var registry = spy(new DefaultEngineExecutorRegistry())) {
      doReturn(new EngineExecutorRegistry.Limits(1, 1))
          .when(registry).limits(EngineExecutorSpec.Kind.FOREGROUND);
      doAnswer(invocation -> {
        var registration = (EngineExecutorRegistry.Registration) invocation.callRealMethod();
        if (!registration.spec().name().equals(poolName)) return registration;
        var observed = spy(registration);
        doAnswer(open -> {
          var pool = (ThreadPoolExecutor) open.callRealMethod();
          callPool.set(pool);
          return pool;
        }).when(observed).open(any());
        return observed;
      }).when(registry).register(any());
      try (var client = new EngineKnowledgeClient(registry, () -> services,
          new ForegroundLoadGate(new ForegroundLoad()), 5_000, 100, IpcTelemetry.noop())) {
        try {
          var pool = callPool.get();
          pool.execute(() -> {
            entered.countDown();
            try {
              assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new AssertionError(e);
            }
          });
          assertTrue(entered.await(2, TimeUnit.SECONDS));
          if (!endpoint.equals("openRetrieve")) {
            pool.execute(() -> {});
            assertEquals(1, pool.getQueue().size());
          }
          assertEquals(1, pool.getActiveCount());
          assertEquals(0, pool.getQueue().remainingCapacity(), "the selected pool must be saturated");

          // Share the public client's admission owner, as EngineRoot and EngineObserverPacingTest do.
          var admissionField = EngineKnowledgeClient.class.getDeclaredField("admission");
          admissionField.setAccessible(true);
          var admission = (EngineAdmissionService) admissionField.get(client);
          try (var work = admission.admit(TestRequestContexts.browser(), false)) {
            var ctx = mock(Context.class);
            when(ctx.attribute(RequestEngineContext.ATTRIBUTE)).thenReturn(work.context());
            when(ctx.path()).thenReturn("/api/indexing/roots");
            when(ctx.bodyAsClass(Map.class)).thenReturn(Map.of(
                "path", dataDir.resolve("watched").toString(), "collection", "notes"));
            when(ctx.status(anyInt())).thenReturn(ctx);

            invoke(endpoint, client, ctx);

            verify(ctx).status(429);
            verify(ctx).header("Retry-After", Integer.toString(admission.retryAfterSeconds()));
            var response = ArgumentCaptor.forClass(Object.class);
            verify(ctx).json(response.capture());
            var body = (Map<String, Object>) response.getValue();
            assertEquals("ADMISSION_ENGINE_LIMIT", body.get("errorCode"));
            assertEquals(false, body.get("retrySafe"));
            verify(services, never()).ingestService();
          }
        } finally {
          release.countDown();
        }
      }
    } finally {
      release.countDown();
      if (previousDataDir == null) System.clearProperty("justsearch.data.dir");
      else System.setProperty("justsearch.data.dir", previousDataDir);
    }
  }

  @SuppressWarnings("unchecked")
  private static void invoke(String endpoint, EngineKnowledgeClient client, Context ctx) {
    switch (endpoint) {
      case "remove" -> new IndexingController(() -> client, null, null, null).handleRemoveRoot(ctx);
      case "retrieve", "openRetrieve" -> {
        when(ctx.bodyAsClass(Map.class)).thenReturn(endpoint.equals("retrieve")
            ? Map.of("query", "q", "doc_ids", List.of("doc")) : Map.of("query", "q"));
        var docs = new RemoteDocumentService(Runnable::run, Runnable::run, () -> client);
        new RetrieveContextController(null, () -> docs, mock(OnlineAiService.class), () -> "")
            .handleRetrieveContext(ctx);
      }
      case "status" -> {
        var bootstrap = mock(KnowledgeServerBootstrap.class);
        when(bootstrap.isReady()).thenReturn(true);
        var capability = mock(io.justsearch.app.api.lifecycle.Capability.class);
        when(capability.health()).thenReturn(io.justsearch.app.api.lifecycle.CapabilityHealth.READY);
        when(bootstrap.workerCapability()).thenReturn(capability);
        var lease = mock(KnowledgeServerBootstrap.ClientLease.class);
        when(bootstrap.captureClient()).thenReturn(lease);
        when(lease.client()).thenReturn(client);
        when(lease.withClient(any())).thenAnswer(invocation ->
            ((java.util.function.Function<KnowledgeClient, ?>) invocation.getArgument(0)).apply(client));
        new KnowledgeSearchController(bootstrap, mock(SearchPerSourceExecutor.class)).handleStatus(ctx);
      }
      default -> {
        OperationHandler handler = switch (endpoint) {
          case "clear" -> new ClearFailedJobsHandler(() -> client);
          case "retry" -> new RetryIndexingJobHandler(() -> client);
          case "cancel" -> new CancelIndexingJobHandler(() -> client);
          case "gc" -> new IndexGcHandler(() -> client, OperationLeaseService.noOp());
          case "settle" -> new SettleIndexHandler(() -> client, OperationLeaseService.noOp());
          default -> throw new AssertionError(endpoint);
        };
        String id = switch (endpoint) {
          case "clear" -> "core.clear-failed-jobs";
          case "retry" -> "core.retry-indexing-job";
          case "cancel" -> "core.cancel-indexing-job";
          case "gc" -> "core.index-gc";
          case "settle" -> "core.settle-index";
          default -> throw new AssertionError(endpoint);
        };
        when(ctx.pathParam("id")).thenReturn(id);
        when(ctx.body()).thenReturn("{\"args\":{\"pathHash\":\"hash\"}}");
        var dispatcher = mock(OperationDispatcher.class);
        // The dispatcher invokes the real handler and client; it does not synthesize a refusal.
        when(dispatcher.dispatch(any(), any(), any(), any(), any())).thenAnswer(invocation ->
            handler.execute(invocation.getArgument(1), invocation.getArgument(4)));
        new OperationsController(List.of(new CoreOperationCatalog()), dispatcher).handleInvoke(ctx);
      }
    }
  }
}
