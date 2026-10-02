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
import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.engine.DefaultEngineExecutorRegistry;
import io.justsearch.app.engine.EngineKnowledgeClient;
import io.justsearch.app.engine.ForegroundLoadGate;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.server.WorkerAppServices;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

@Timeout(10)
final class IndexingControllerSaturationTest {
  @TempDir Path dataDir;

  @Test
  @SuppressWarnings("unchecked")
  void admittedRemovalReportsActualUnwatchSubmissionRefusal() throws Exception {
    String previousDataDir = System.getProperty("justsearch.data.dir");
    System.setProperty("justsearch.data.dir", dataDir.toString());
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var callPool = new AtomicReference<ThreadPoolExecutor>();
    var services = mock(WorkerAppServices.class);
    try (var registry = spy(new DefaultEngineExecutorRegistry())) {
      doReturn(new EngineExecutorRegistry.Limits(1, 1))
          .when(registry).limits(EngineExecutorSpec.Kind.FOREGROUND);
      doAnswer(invocation -> {
        var registration = (EngineExecutorRegistry.Registration) invocation.callRealMethod();
        if (!registration.spec().name().equals("engine-knowledge-call-foreground")) return registration;
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
          pool.execute(() -> {});
          assertEquals(1, pool.getQueue().size());

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

            new IndexingController(() -> client, null, null, null).handleRemoveRoot(ctx);

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
}
