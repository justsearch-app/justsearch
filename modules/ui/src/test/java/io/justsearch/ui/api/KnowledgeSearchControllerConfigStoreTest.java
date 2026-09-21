/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.SamplingParams;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.SearchPerSourceExecutor;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.context.EngineContext;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class KnowledgeSearchControllerConfigStoreTest {
  private final ConfigStore previousGlobal = ConfigStore.globalOrNull();

  @AfterEach
  void restoreGlobal() {
    TestResolvedConfigHelper.restoreGlobal(previousGlobal);
  }

  @Test
  void borrowedStoreControlsQueryUnderstandingAndObservesLiveUpdates() {
    ConfigStore storeA = store(true);
    ConfigStore contradictoryGlobalB = store(false);
    ConfigStore.setGlobal(contradictoryGlobalB);

    OnlineAiService ai = mock(OnlineAiService.class);
    when(ai.isAvailable()).thenReturn(true);
    when(ai.chatCompletion(
            anyList(), anyInt(), any(SamplingParams.class), any(EngineContext.class)))
        .thenReturn(
            CompletableFuture.completedFuture(
                "{\"query\":\"captured query\",\"meta_source\":[\"captured source\"]}"));
    KnowledgeClient client = mock(KnowledgeClient.class);
    List<SearchRequest> workerRequests = new ArrayList<>();
    when(client.search(any(SearchRequest.class), any(EngineContext.class)))
        .thenAnswer(
            invocation -> {
              workerRequests.add(invocation.getArgument(0));
              return SearchResponse.getDefaultInstance();
            });
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    when(bootstrap.client()).thenReturn(client);

    KnowledgeSearchController controller =
        new KnowledgeSearchController(
            bootstrap,
            mock(SearchPerSourceExecutor.class),
            null,
            ai,
            null,
            null,
            storeA);

    invoke(controller);
    assertEquals(
        List.of("captured source"),
        workerRequests.get(0).getBoostFilters().getMetaSourceList(),
        "the first request must consume the enabled QU result, not merely call the AI");
    verify(ai, times(1))
        .chatCompletion(anyList(), anyInt(), any(SamplingParams.class), any(EngineContext.class));

    storeA.update(
        TestResolvedConfigHelper.fromEntries(
            Map.of(
                "justsearch.qu.enabled", "false",
                "justsearch.filter_norm.enabled", "false")));
    invoke(controller);
    assertTrue(
        workerRequests.get(1).getBoostFilters().getMetaSourceList().isEmpty(),
        "the same controller must observe the live disable on its borrowed store");
    verify(ai, times(1))
        .chatCompletion(anyList(), anyInt(), any(SamplingParams.class), any(EngineContext.class));
    verify(client, times(2)).search(any(SearchRequest.class), any(EngineContext.class));
  }

  private static ConfigStore store(boolean queryUnderstandingEnabled) {
    return new ConfigStore(
        TestResolvedConfigHelper.fromEntries(
            Map.of(
                "justsearch.qu.enabled",
                Boolean.toString(queryUnderstandingEnabled),
                "justsearch.filter_norm.enabled", "false")));
  }

  private static void invoke(KnowledgeSearchController controller) {
    Context context = mock(Context.class);
    when(context.attribute(RequestEngineContext.ATTRIBUTE)).thenReturn(TestRequestContexts.browser());
    when(context.path()).thenReturn("/api/knowledge/search");
    when(context.bodyAsClass(Map.class))
        .thenReturn(Map.of("query", "quarterly report", "pipeline", Map.of()));
    when(context.status(anyInt())).thenReturn(context);
    controller.handleSearch(context);
  }
}
