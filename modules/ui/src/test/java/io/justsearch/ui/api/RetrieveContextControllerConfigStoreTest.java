/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.RetrieveContextParams;
import io.justsearch.app.api.SamplingParams;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.context.EngineContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class RetrieveContextControllerConfigStoreTest {
  private final ConfigStore previousGlobal = ConfigStore.globalOrNull();

  @AfterEach
  void restoreGlobal() {
    TestResolvedConfigHelper.restoreGlobal(previousGlobal);
  }

  @Test
  void borrowedStoreControlsLlmFallbackAndObservesLiveUpdates() {
    ConfigStore storeA = store(true);
    ConfigStore contradictoryGlobalB = store(false);
    ConfigStore.setGlobal(contradictoryGlobalB);

    OnlineAiService ai = mock(OnlineAiService.class);
    when(ai.isAvailable()).thenReturn(true);
    when(ai.chatCompletion(
            anyList(), anyInt(), any(SamplingParams.class), any(EngineContext.class)))
        .thenReturn(CompletableFuture.completedFuture("cbs sports -> cbssports.com"));
    CapturingDocuments documents = new CapturingDocuments();
    RetrieveContextController controller =
        new RetrieveContextController(
            null,
            documents,
            ai,
            () -> "meta_source: cbssports.com (10), fox news (8)",
            storeA);

    invoke(controller, "CBS Sports");
    assertEquals(List.of("cbssports.com"), documents.requests.get(0).metaSource());
    verify(ai, times(1))
        .chatCompletion(anyList(), anyInt(), any(SamplingParams.class), any(EngineContext.class));

    storeA.update(
        TestResolvedConfigHelper.fromEntries(
            Map.of("justsearch.filter_norm.enabled", "false")));
    invoke(controller, "CBS Sports");
    assertEquals(List.of("cbs sports"), documents.requests.get(1).metaSource());
    verify(ai, times(1))
        .chatCompletion(anyList(), anyInt(), any(SamplingParams.class), any(EngineContext.class));

    invoke(controller, "Fox");
    assertEquals(
        List.of("fox news"),
        documents.requests.get(2).metaSource(),
        "disabled LLM fallback must preserve deterministic normalization");
    verify(ai, times(1))
        .chatCompletion(anyList(), anyInt(), any(SamplingParams.class), any(EngineContext.class));
  }

  private static ConfigStore store(boolean filterNormalizationEnabled) {
    return new ConfigStore(
        TestResolvedConfigHelper.fromEntries(
            Map.of(
                "justsearch.qu.enabled", "false",
                "justsearch.filter_norm.enabled",
                Boolean.toString(filterNormalizationEnabled))));
  }

  private static void invoke(RetrieveContextController controller, String source) {
    Context context = mock(Context.class);
    when(context.attribute(RequestEngineContext.ATTRIBUTE)).thenReturn(TestRequestContexts.browser());
    when(context.path()).thenReturn("/api/knowledge/retrieve-context");
    when(context.bodyAsClass(Map.class))
        .thenReturn(
            Map.of(
                "query", "find the report",
                "filters", Map.of("meta_source", List.of(source))));
    when(context.status(anyInt())).thenReturn(context);
    controller.handleRetrieveContext(context);
  }

  private static final class CapturingDocuments implements DocumentService {
    private final List<RetrieveContextParams> requests = new ArrayList<>();

    @Override
    public CompletionStage<DocumentRecord> fetch(String docId, EngineContext engineContext) {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Map<String, DocumentRecord>> fetchBatch(
        List<String> docIds, EngineContext engineContext) {
      return CompletableFuture.completedFuture(Map.of());
    }

    @Override
    public CompletionStage<ContextResult> retrieveContextWithMeta(
        String question,
        Set<String> docIds,
        int topK,
        int maxContextTokens,
        EngineContext engineContext) {
      return CompletableFuture.completedFuture(emptyResult());
    }

    @Override
    public CompletionStage<ContextResult> retrieveContext(
        RetrieveContextParams params, EngineContext engineContext) {
      requests.add(params);
      return CompletableFuture.completedFuture(emptyResult());
    }

    private static ContextResult emptyResult() {
      return new ContextResult("", 0, 0, 0, List.of(), "BM25", "", false, List.of());
    }
  }
}
