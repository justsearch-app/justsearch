package io.justsearch.app.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.justsearch.app.api.SearchRequest;
import io.justsearch.app.api.SearchResponse;
import io.justsearch.core.dto.Query;
import io.justsearch.core.dto.Result;
import io.justsearch.core.search.SearchPort;
import io.justsearch.telemetry.Telemetry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultAppFacadeTest {

  @Test
  void searchDelegatesAndMapsResponse() {
    SearchRequest.Filters filters =
        new SearchRequest.Filters(
            "text/plain", "en", new SearchRequest.TimeRange(1000L, 2000L));
    SearchRequest.Clause clause =
        new SearchRequest.Clause("text", "content", "apollo", List.of("apollo"));
    SearchRequest request =
        new SearchRequest(10, 0, true, filters, List.of("-date"), List.of(clause), null);

    Result coreResult =
        new Result(
            List.of(new Result.Hit("A1", 1.0, Map.of())),
            Map.of(),
            new io.justsearch.core.dto.Cursor("pit", "p1", 123L, Map.of()),
            Map.of());

    SearchPort port =
        (intent, engineContext) -> {
          assertEquals(10, intent.limit());
          assertEquals("text/plain", intent.filters().mime());
          return coreResult;
        };

    HeadAssembly facade = HeadAssembly.bootForSearchPortOnly(org.mockito.Mockito.mock(io.justsearch.app.api.operations.OperationStore.class), new io.justsearch.core.execution.TestEngineExecutors(), port, new NoopTelemetry());
    SearchResponse response =
        facade.workers().search().search(request, TestEngineContexts.internal());

    assertEquals(
        new SearchResponse(
            List.of(new SearchResponse.Hit("A1", 1.0, Map.of())),
            Map.of(),
            new SearchResponse.Cursor("pit", "p1", 123L, Map.of()),
            Map.of()),
        response);
  }

  @Test
  void searchPassesThroughWithEmptyClauses() {
    SearchRequest request = new SearchRequest(5, 0, false, null, null, List.of(), null);
    Result coreResult = new Result(List.of(), Map.of(), null, Map.of());
    SearchPort port =
        (intent, engineContext) -> {
          assertEquals(new Query(5, 0, false, null, null, List.of(), null), intent);
          return coreResult;
        };

    HeadAssembly facade = HeadAssembly.bootForSearchPortOnly(org.mockito.Mockito.mock(io.justsearch.app.api.operations.OperationStore.class), new io.justsearch.core.execution.TestEngineExecutors(), port, new NoopTelemetry());
    SearchResponse response =
        facade.workers().search().search(request, TestEngineContexts.internal());
    assertNotNull(response);
  }

  private static final class NoopTelemetry implements Telemetry {
    @Override
    public void close() {}
  }
}
