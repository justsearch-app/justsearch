/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.KnowledgeServerConfig;
import io.justsearch.app.services.worker.WatchedRootsState;
import io.justsearch.app.services.worker.WorkerHost;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.TestEngineComponents;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.server.KnowledgeServer;
import io.justsearch.ui.api.ApiErrorHandler;
import io.justsearch.ui.api.TestRequestContexts;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Both capture entry paths pass a Knowledge Server's "no serving view" refusal on classified as
 * unavailable, and the bootstrap's own "client is unavailable" refusal is classified the same,
 * still as the IllegalStateException that health and readiness callers catch, leaving no
 * admission or client lease behind.
 */
final class ServingViewCaptureBoundaryTest {
  @TempDir Path directory;

  @Test
  void directClientCaptureRefusalIsUnavailableAndReleasesItsAdmission() throws Exception {
    var admission = new EngineAdmissionController(8, 8, 3);
    try (var executors = new TestEngineExecutors()) {
      var server = unpublishedServer(executors);
      try (var client = new EngineKnowledgeClient(executors, () -> null,
          new ForegroundLoadGate(new ForegroundLoad()), 5_000, 100, IpcTelemetry.noop(), () -> {},
          admission, WatchedRootsState.inMemory(), server::captureServingView)) {
        var refused = assertThrows(IllegalStateException.class,
            () -> client.search("quarterly report", 10, TestRequestContexts.browser()));
        assertEquals("Index serving view is unavailable", refused.getMessage());
        assertEquals(ApiErrorCode.SERVICE_UNAVAILABLE, ApiErrorHandler.resolve(refused));
        assertEquals(0, admission.activeWorkCount(), "the refused call released its admission");
      } finally {
        server.close();
      }
    }
  }

  @Test
  void bootstrapLeaseRefusalIsUnavailableAndHealthCallersBehaveAsBefore() throws Exception {
    try (var executors = new TestEngineExecutors();
        var components = TestEngineComponents.fourComponents()) {
      var server = unpublishedServer(executors);
      var refusing = new AtomicBoolean();
      var client = mock(KnowledgeClient.class);
      when(client.isHealthy(any())).thenReturn(true);
      var host = mock(WorkerHost.class);
      when(host.start(any(), any())).thenReturn(client);
      when(host.captureServingView()).thenAnswer(ignored -> {
        if (refusing.get()) server.captureServingView();
        return new WorkerHost.ServingLease() {
          @Override public <T> T withClient(KnowledgeClient captured,
              java.util.function.Function<KnowledgeClient, T> action) {
            return action.apply(captured);
          }
          @Override public void close() {}
        };
      });
      var config = new KnowledgeServerConfig(false, directory, directory, directory,
          5_000L, 2_000L, 3, 2_000L, 1_000L, 300_000L, 100, 0L, 0);
      var bootstrap = new KnowledgeServerBootstrap(executors, config, null, components,
          components.handle("index"), host, new java.util.concurrent.locks.ReentrantReadWriteLock());
      try {
        bootstrap.start();
        components.handle("index").transition(ComponentState.READY, null, null);
        assertTrue(bootstrap.isReady(), "precondition: a published view is ready");
        refusing.set(true);
        var refused = assertThrows(IllegalStateException.class, bootstrap::captureClient);
        assertEquals("Index serving view is unavailable", refused.getMessage());
        assertEquals(ApiErrorCode.SERVICE_UNAVAILABLE, ApiErrorHandler.resolve(refused));
        assertEquals(0, clientHolders(bootstrap), "the refused capture holds no client lease");
        assertFalse(bootstrap.isReady(), "readiness reports not ready without escaping");
        assertThrows(IllegalStateException.class, bootstrap::tryCaptureClient,
            "the observation capture keeps throwing the IllegalStateException its callers catch");
        refusing.set(false);
        try (var lease = bootstrap.captureClient()) {
          assertSame(client, lease.client());
          assertEquals(1, clientHolders(bootstrap));
        }
        assertEquals(0, clientHolders(bootstrap));
        assertTrue(bootstrap.isReady());
      } finally {
        try {
          bootstrap.close();
        } finally {
          server.close();
        }
      }
    }
  }

  @Test
  void unverifiedClientRefusalAfterAHealthPollIsUnavailableAndHealthCallersBehaveAsBefore()
      throws Exception {
    var previousConfig = io.justsearch.configuration.resolved.ConfigStore.globalOrNull();
    try (var executors = new TestEngineExecutors();
        var components = TestEngineComponents.fourComponents()) {
      var store = io.justsearch.configuration.resolved.TestResolvedConfigHelper.storeWithDefaults();
      var healthy = new AtomicBoolean(true);
      var client = mock(KnowledgeClient.class);
      when(client.isHealthy(any())).thenAnswer(ignored -> healthy.get());
      var host = mock(WorkerHost.class);
      when(host.start(any(), any())).thenReturn(client);
      when(host.captureServingView()).thenReturn(new WorkerHost.ServingLease() {
        @Override public <T> T withClient(KnowledgeClient captured,
            java.util.function.Function<KnowledgeClient, T> action) {
          return action.apply(captured);
        }
        @Override public void close() {}
      });
      var config = new KnowledgeServerConfig(false, directory, directory, directory,
          5_000L, 2_000L, 3, 2_000L, 1_000L, 300_000L, 100, 0L, 0);
      var bootstrap = new KnowledgeServerBootstrap(executors, config, null, components,
          components.handle("index"), host, store.publicationLock());
      try {
        bootstrap.start();
        components.handle("index").transition(ComponentState.READY, null, null);
        assertTrue(bootstrap.isReady(), "precondition: a verified client is ready");
        healthy.set(false);
        // The health monitor's poll: no view to observe and no reload, so it checks directly.
        assertEquals(java.util.Optional.of(false), bootstrap.tryCheckHealth());

        var refused = assertThrows(IllegalStateException.class, bootstrap::captureClient);
        assertEquals("Knowledge Server client is unavailable", refused.getMessage());
        assertEquals(ApiErrorCode.SERVICE_UNAVAILABLE, ApiErrorHandler.resolve(refused));
        assertEquals(0, clientHolders(bootstrap), "the refused capture holds no client lease");
        assertFalse(bootstrap.isReady(), "readiness reports not ready without escaping");
        var observed = assertThrows(IllegalStateException.class, bootstrap::tryCaptureClient);
        assertEquals(IllegalStateException.class, observed.getClass(),
            "the status sampler's observation capture refuses exactly as before");

        var ctx = mock(io.javalin.http.Context.class);
        when(ctx.attribute(io.justsearch.ui.api.RequestEngineContext.ATTRIBUTE))
            .thenReturn(TestRequestContexts.browser());
        when(ctx.path()).thenReturn("/api/knowledge/search");
        when(ctx.status(org.mockito.ArgumentMatchers.anyInt())).thenReturn(ctx);
        when(ctx.bodyAsClass(Map.class)).thenReturn(Map.of("query", "quarterly report"));
        new io.justsearch.ui.api.KnowledgeSearchController(bootstrap,
            mock(io.justsearch.app.services.worker.SearchPerSourceExecutor.class), null,
            io.justsearch.app.api.OnlineAiService.unavailable(), null, null, store).handleSearch(ctx);
        org.mockito.Mockito.verify(ctx).status(503);
        org.mockito.Mockito.verify(ctx).header("Retry-After", "1");
        var body = org.mockito.ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(ctx).json(body.capture());
        var response = (Map<?, ?>) body.getValue();
        assertEquals("SERVICE_UNAVAILABLE", response.get("errorCode"), response.toString());
        assertEquals(true, response.get("retryable"));
        assertEquals(true, response.get("retrySafe"));
        assertEquals("Knowledge Server client is unavailable", response.get("error"));
        assertEquals(0, clientHolders(bootstrap));
      } finally {
        bootstrap.close();
      }
    } finally {
      io.justsearch.configuration.resolved.TestResolvedConfigHelper.restoreGlobal(previousConfig);
    }
  }

  private KnowledgeServer unpublishedServer(TestEngineExecutors executors) {
    return new KnowledgeServer(executors,
        new WorkerConfig(directory, 60_000L, "0.0.0-test", Map.of(), "test", 500L));
  }

  private static int clientHolders(KnowledgeServerBootstrap bootstrap) throws Exception {
    var field = KnowledgeServerBootstrap.class.getDeclaredField("clientHolders");
    field.setAccessible(true);
    return (int) field.get(bootstrap);
  }
}
