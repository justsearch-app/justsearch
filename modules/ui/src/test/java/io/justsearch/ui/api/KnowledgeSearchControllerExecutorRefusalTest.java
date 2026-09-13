/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.javalin.http.Context;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KnowledgeSearchControllerExecutorRefusalTest {
  private io.justsearch.configuration.resolved.ConfigStore previousConfigStore;

  @org.junit.jupiter.api.BeforeEach
  void configure() {
    previousConfigStore = io.justsearch.configuration.resolved.ConfigStore.globalOrNull();
    io.justsearch.configuration.resolved.TestResolvedConfigHelper.storeWithDefaults();
  }

  @org.junit.jupiter.api.AfterEach
  void restore() {
    io.justsearch.configuration.resolved.TestResolvedConfigHelper.restoreGlobal(previousConfigStore);
  }

  @Test
  @SuppressWarnings("unchecked")
  void suggestPreservesTypedRefusalThroughItsControllerCatch() {
    var client = mock(KnowledgeClient.class);
    when(client.suggest(anyString(), anyInt(), any(EngineContext.class))).thenThrow(
        new java.util.concurrent.CompletionException(new EngineExecutorRejectedException(
            EngineExecutorRejectedException.Reason.QUEUE_LIMIT, "engine.calls", 3)));
    var bootstrap = mock(KnowledgeServerBootstrap.class);
    when(bootstrap.isReady()).thenReturn(true);
    when(bootstrap.client()).thenReturn(client);
    var controller = new KnowledgeSearchController(bootstrap, mock(io.justsearch.app.services.worker.SearchPerSourceExecutor.class));
    var ctx = mock(Context.class);
    when(ctx.attribute(RequestEngineContext.ATTRIBUTE)).thenReturn(TestRequestContexts.browser());
    when(ctx.path()).thenReturn("/api/knowledge/suggest");
    when(ctx.queryParam("query")).thenReturn("test");
    controller.handleSuggest(ctx);
    verify(ctx).status(429);
    verify(ctx).header("Retry-After", "3");
    var response = ArgumentCaptor.forClass(Object.class);
    verify(ctx).json(response.capture());
    var body = (Map<String, Object>) response.getValue();
    assertEquals("ADMISSION_ENGINE_LIMIT", body.get("errorCode"));
    assertEquals(false, body.get("retrySafe"));
  }
}
