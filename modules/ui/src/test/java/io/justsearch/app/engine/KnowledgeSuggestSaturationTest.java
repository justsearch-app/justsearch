/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.SearchPerSourceExecutor;
import io.justsearch.app.services.worker.WatchedRootsState;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.context.RetainedStateBudget;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.server.WorkerAppServices;
import io.justsearch.indexerworker.services.WorkerSearchService;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ui.api.KnowledgeSearchController;
import io.justsearch.ui.api.RequestEngineContext;
import io.justsearch.ui.api.TestRequestContexts;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;

@Timeout(10)
final class KnowledgeSuggestSaturationTest {
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

  @Test
  @SuppressWarnings("unchecked")
  void admittedSuggestReportsActualCallPoolSaturation() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var search = mock(WorkerSearchService.class);
    when(search.search(any(), any())).thenAnswer(invocation -> {
      entered.countDown();
      assertTrue(release.await(5, TimeUnit.SECONDS));
      return SearchResponse.getDefaultInstance();
    });
    var services = mock(WorkerAppServices.class);
    when(services.searchService()).thenReturn(search);
    var admission = new EngineAdmissionController(8, 8, 3);
    var context = TestRequestContexts.browser();
    var callers = Executors.newFixedThreadPool(2);

    var policy = new EngineResourcePolicy(Map.of(
        "perContextLimit", 8, "aggregateLimit", 8, "retryAfterSeconds", 3,
        "foregroundThreads", 1, "foregroundQueue", 1,
        "backgroundThreads", 1, "backgroundQueue", 4,
        "timerRegistrations", 16, "directMemoryMiB", 1), new RetainedStateBudget());
    try (var registry = new DefaultEngineExecutorRegistry(policy, Duration.ofSeconds(1));
        var client = new EngineKnowledgeClient(registry, () -> services,
            new ForegroundLoadGate(new ForegroundLoad()), 5_000, 100,
            IpcTelemetry.noop(), () -> {}, admission, WatchedRootsState.inMemory())) {
      try {
        var first = callers.submit(() -> client.search("running", 10, context));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        var queued = callers.submit(() -> client.search("queued", 10, context));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (queuedCalls(registry) != 1 && System.nanoTime() < deadline) {
          Thread.onSpinWait();
        }
        assertEquals(1, queuedCalls(registry));

        // The front door still admits this request; only the call executor refuses it.
        try (var work = admission.admit(context, false)) {
          var bootstrap = mock(KnowledgeServerBootstrap.class);
          when(bootstrap.isReady()).thenReturn(true);
          var lease = mock(KnowledgeServerBootstrap.ClientLease.class);
          when(bootstrap.captureClient()).thenReturn(lease);
          when(lease.client()).thenReturn(client);
          when(lease.withClient(any())).thenAnswer(invocation ->
              ((java.util.function.Function<KnowledgeClient, ?>) invocation.getArgument(0))
                  .apply(client));
          var controller = new KnowledgeSearchController(bootstrap, mock(SearchPerSourceExecutor.class));
          var ctx = mock(Context.class);
          when(ctx.attribute(RequestEngineContext.ATTRIBUTE)).thenReturn(work.context());
          when(ctx.path()).thenReturn("/api/knowledge/suggest");
          when(ctx.queryParam("query")).thenReturn("test");
          when(ctx.status(anyInt())).thenReturn(ctx);

          controller.handleSuggest(ctx);

          verify(ctx).status(429);
          verify(ctx).header("Retry-After", "3");
          var response = ArgumentCaptor.forClass(Object.class);
          verify(ctx).json(response.capture());
          var body = (Map<String, Object>) response.getValue();
          assertEquals("ADMISSION_ENGINE_LIMIT", body.get("errorCode"));
          assertEquals(false, body.get("retrySafe"));
        }
        release.countDown();
        first.get(2, TimeUnit.SECONDS);
        queued.get(2, TimeUnit.SECONDS);
      } finally {
        release.countDown();
      }
    } finally {
      release.countDown();
      callers.shutdownNow();
    }
  }

  private static int queuedCalls(DefaultEngineExecutorRegistry registry) {
    return registry.snapshot().registrations().stream()
        .filter(row -> row.spec().name().equals("engine-knowledge-call-foreground"))
        .findFirst().orElseThrow().queuedTasks();
  }
}
