/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchResult;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class PerSourceReadAuthorityTest {
  @ParameterizedTest
  @ValueSource(strings = {"children", "backfill", "fallback"})
  void verifiedWindowAuthoritySurvivesChildrenBackfillAndFailureFallback(String mode) throws Exception {
    var context = TestEngineContexts.internal().withWorkId(java.util.UUID.randomUUID());
    var work = mock(EngineWorkHandle.class);
    when(work.context()).thenReturn(context);
    when(work.retain()).thenReturn(work);
    when(work.onCancel(any())).thenReturn(() -> {});
    var admission = mock(EngineAdmissionService.class);
    when(admission.attach(any())).thenReturn(work);
    var client = mock(KnowledgeClient.class);
    when(client.search(any(SearchRequest.class), any(EngineContext.class)))
        .thenThrow(new AssertionError("Verified window authority was lost"));
    when(client.search(any(SearchRequest.class), eq(true), any(EngineContext.class))).thenAnswer(call -> {
      SearchRequest request = call.getArgument(0);
      boolean child = request.getFilters().getMetaSourceCount() > 0;
      if (child && mode.equals("fallback")) throw new IllegalStateException("child unavailable");
      String source = child ? request.getFilters().getMetaSource(0) : "base";
      var response = SearchResponse.newBuilder();
      int count = child ? 2 : 4;
      for (int i = 0; i < count; i++) response.addResults(SearchResult.newBuilder()
          .setId((mode.equals("backfill") && child ? "duplicate" : source) + i));
      return response.build();
    });
    try (var registry = new TestEngineExecutors(); var executor = new SearchPerSourceExecutor(registry, admission)) {
      var request = SearchRequest.newBuilder().setQuery("notes").setLimit(4).build();
      SearchResponse response;
      try {
        var execute = SearchPerSourceExecutor.class.getMethod("execute", KnowledgeClient.class,
            SearchRequest.class, List.class, int.class, boolean.class, EngineContext.class);
        response = (SearchResponse) execute.invoke(executor, client, request, List.of("one", "two"), 4, true, context);
      } catch (NoSuchMethodException beforeAuthorityCarrier) {
        response = executor.execute(client, request, List.of("one", "two"), 4, context);
      }
      assertEquals(4, response.getResultsCount());
      verify(client, times(mode.equals("children") ? 2 : 3)).search(any(SearchRequest.class), eq(true), any(EngineContext.class));
      verify(client, never()).search(any(SearchRequest.class), any(EngineContext.class));
    }
  }
}
