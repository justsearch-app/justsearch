/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.SamplingParams;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequest;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.context.EngineContext;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class KnowledgeSearchCaptureTest {
  private final ConfigStore previousGlobal = ConfigStore.globalOrNull();

  @AfterEach
  void restoreGlobal() {
    TestResolvedConfigHelper.restoreGlobal(previousGlobal);
  }

  @Test
  void inFlightSearchKeepsCapturedFlagsWhenConfigChangesBeforeRetrieval() throws Exception {
    ConfigStore store = new ConfigStore(TestResolvedConfigHelper.fromEntries(Map.of(
        "justsearch.qu.enabled", "true", "justsearch.filter_norm.enabled", "false")));
    ConfigStore.setGlobal(store);
    KnowledgeClient client = mock(KnowledgeClient.class);
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    var lease = mock(KnowledgeServerBootstrap.ClientLease.class);
    when(bootstrap.publicationLock()).thenReturn(store.publicationLock());
    when(bootstrap.acquireClientLease()).thenReturn(lease);
    when(bootstrap.isReady()).thenReturn(true);
    when(lease.client()).thenReturn(client);

    var facetEntered = new CountDownLatch(1);
    var releaseFacet = new CountDownLatch(1);
    var calls = new AtomicInteger();
    List<SearchRequest> sent = new ArrayList<>();
    when(client.search(any(SearchRequest.class), any(EngineContext.class)))
        .thenAnswer(invocation -> {
          if (calls.getAndIncrement() == 0) {
            facetEntered.countDown();
            assertTrue(releaseFacet.await(5, TimeUnit.SECONDS));
          } else {
            sent.add(invocation.getArgument(0));
          }
          return SearchResponse.getDefaultInstance();
        });
    OnlineAiService ai = mock(OnlineAiService.class);
    when(ai.isAvailable()).thenReturn(true);
    when(ai.chatCompletion(anyList(), anyInt(), any(SamplingParams.class),
        any(EngineContext.class)))
        .thenReturn(CompletableFuture.completedFuture(
            "{\"query\":\"captured query\",\"meta_source\":[\"captured source\"]}"));
    var engine = new KnowledgeSearchEngine(bootstrap, mock(SearchPerSourceExecutor.class),
        ai, null, store);
    var request = new KnowledgeSearchRequest("quarterly report", 10, null, null, null,
        List.of(), null, null, null, null, false, false, null);

    try (var tasks = Executors.newSingleThreadExecutor()) {
      var pending = tasks.submit(() -> engine.search(request, TestEngineContexts.internal()));
      try {
        assertTrue(facetEntered.await(5, TimeUnit.SECONDS));
        store.update(TestResolvedConfigHelper.fromEntries(Map.of(
            "justsearch.qu.enabled", "false", "justsearch.filter_norm.enabled", "false")));
      } finally {
        releaseFacet.countDown();
      }
      pending.get(5, TimeUnit.SECONDS);
    }
    assertEquals(List.of("captured source"), sent.get(0).getBoostFilters().getMetaSourceList());
  }
}
