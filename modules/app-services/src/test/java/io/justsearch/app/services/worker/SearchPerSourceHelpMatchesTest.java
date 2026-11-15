/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.ipc.HelpMatches;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchResult;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SearchPerSourceHelpMatchesTest {
  @Test
  void fullSourceWindowOmitsSourceScopedHelpCount() {
    assertFalse(execute(2, SearchResponse.getDefaultInstance(), false).hasHelpMatches());
  }

  @Test
  void unfilteredBackfillSuppliesTheOnlyHelpCountIncludingCapped() {
    var help = HelpMatches.newBuilder().setCount(9).setCapped(true).build();
    assertEquals(help, execute(3, SearchResponse.newBuilder().setHelpMatches(help).build(), false)
        .getHelpMatches());
  }

  @Test
  void absentOrFailedBackfillCountCannotLeakAChildCount() {
    assertFalse(execute(3, SearchResponse.getDefaultInstance(), false).hasHelpMatches());
    assertFalse(execute(3, SearchResponse.getDefaultInstance(), true).hasHelpMatches());
  }

  private static SearchResponse execute(int limit, SearchResponse backfill, boolean failBackfill) {
    var context = new EngineContext(EngineContext.ClientKind.INTERNAL, "help-merge-test",
        Optional.empty(), Optional.empty(), "INTERNAL", "TEST",
        EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.FOREGROUND);
    var handle = mock(EngineWorkHandle.class);
    when(handle.context()).thenReturn(context);
    when(handle.retain()).thenReturn(handle);
    when(handle.onCancel(any())).thenReturn(mock(EngineWorkHandle.Registration.class));
    var admission = mock(EngineAdmissionService.class);
    when(admission.attach(context)).thenReturn(handle);
    var base = SearchRequest.newBuilder().setQuery("needle").setLimit(limit)
        .addExcludedCollections("justsearch-help").build();
    var client = mock(KnowledgeClient.class);
    when(client.search(any(SearchRequest.class), any(EngineContext.class))).thenAnswer(call -> {
      SearchRequest request = call.getArgument(0);
      if (request.getFilters().getMetaSourceCount() == 0) {
        assertEquals(base, request, "backfill must retain the base request's scope");
        if (failBackfill) throw new IllegalStateException("backfill unavailable");
        return backfill;
      }
      assertEquals(List.of("justsearch-help"), request.getExcludedCollectionsList());
      return SearchResponse.newBuilder().setHelpMatches(HelpMatches.newBuilder().setCount(1))
          .addResults(SearchResult.newBuilder().setId(request.getFilters().getMetaSource(0))).build();
    });
    try (var registry = new TestEngineExecutors();
        var executor = new SearchPerSourceExecutor(registry, admission)) {
      var result = executor.execute(client, base, List.of("one", "two"), limit, context);
      assertEquals(2, result.getResultsCount());
      return result;
    }
  }
}
