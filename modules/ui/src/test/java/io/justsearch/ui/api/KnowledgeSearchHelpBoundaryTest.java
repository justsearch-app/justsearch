/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.google.protobuf.DynamicMessage;
import io.javalin.http.Context;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.SearchPerSourceExecutor;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.context.EngineContext;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KnowledgeSearchHelpBoundaryTest {
  private final ConfigStore previous = ConfigStore.globalOrNull();

  @AfterEach
  void restore() {
    TestResolvedConfigHelper.restoreGlobal(previous);
  }

  @Test
  void httpExclusionReachesWorkerAndHelpCountReturnsInSameResponse() {
    var response = SearchResponse.newBuilder().setMatchCount(2).setTotalHits(999);
    var field = response.getDescriptorForType().findFieldByName("help_matches");
    assertNotNull(field, "the Help count has its own response field");
    var help = field.getMessageType();
    response.setField(field, DynamicMessage.newBuilder(help)
        .setField(help.findFieldByName("count"), 3L)
        .setField(help.findFieldByName("capped"), true).build());
    var client = mock(KnowledgeClient.class);
    when(client.search(any(SearchRequest.class), anyBoolean(), any(EngineContext.class)))
        .thenReturn(response.build());
    var controller = controller(client);
    var context = context(Map.of("query", "needle", "excludedCollections", List.of("justsearch-help"),
        "pipeline", Map.of()));
    controller.handleSearch(context);
    var request = ArgumentCaptor.forClass(SearchRequest.class);
    verify(client).search(request.capture(), anyBoolean(), any(EngineContext.class));
    var excluded = request.getValue().getDescriptorForType().findFieldByName("excluded_collections");
    assertNotNull(excluded);
    assertEquals(List.of("justsearch-help"), request.getValue().getField(excluded));
    var body = ArgumentCaptor.forClass(Object.class);
    verify(context).json(body.capture());
    var out = (Map<?, ?>) body.getValue();
    assertEquals(2L, out.get("matchCount"));
    assertNotNull(out.get("helpMatches"));
    // Reflective access leaves this check executable against the before-state DTO.
    Object matches = out.get("helpMatches");
    assertEquals(3L, invoke(matches, "count"));
    assertEquals(true, invoke(matches, "capped"));
  }

  @Test
  void absentFieldPreservesTheWorkerRequestAndOmitsHelpCount() {
    var client = mock(KnowledgeClient.class);
    when(client.search(any(SearchRequest.class), anyBoolean(), any(EngineContext.class)))
        .thenReturn(SearchResponse.getDefaultInstance());
    var context = context(Map.of("query", "needle", "pipeline", Map.of()));
    controller(client).handleSearch(context);
    var request = ArgumentCaptor.forClass(SearchRequest.class);
    verify(client).search(request.capture(), anyBoolean(), any(EngineContext.class));
    assertFalse(request.getValue().getAllFields().keySet().stream()
        .anyMatch(field -> field.getName().equals("excluded_collections")));
    var body = ArgumentCaptor.forClass(Object.class);
    verify(context).json(body.capture());
    assertFalse(((Map<?, ?>) body.getValue()).containsKey("helpMatches"));
  }

  @Test
  void malformedExclusionsFailBeforeWorkerAdmission() {
    for (Object raw : List.of("justsearch-help", List.of(1), List.of(""))) {
      var client = mock(KnowledgeClient.class);
      var context = context(Map.of("query", "needle", "excludedCollections", raw, "pipeline", Map.of()));
      controller(client).handleSearch(context);
      var status = ArgumentCaptor.forClass(Integer.class);
      verify(context, org.mockito.Mockito.atLeast(0)).status(status.capture());
      assertEquals(1, java.util.Collections.frequency(status.getAllValues(), 400), "malformed excludedCollections " + raw + " must be rejected with 400");
      verify(client, never()).search(any(SearchRequest.class), anyBoolean(), any(EngineContext.class));
    }
  }

  private static Object invoke(Object target, String method) {
    try {
      return target.getClass().getMethod(method).invoke(target);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private static KnowledgeSearchController controller(KnowledgeClient client) {
    var store = TestResolvedConfigHelper.storeWithDefaults();
    var bootstrap = mock(KnowledgeServerBootstrap.class);
    var lease = mock(KnowledgeServerBootstrap.ClientLease.class);
    when(bootstrap.publicationLock()).thenReturn(store.publicationLock());
    when(bootstrap.acquireClientLease()).thenReturn(lease);
    when(lease.client()).thenReturn(client);
    when(lease.withClient(any())).thenAnswer(invocation ->
        ((java.util.function.Function<KnowledgeClient, ?>) invocation.getArgument(0)).apply(client));
    return new KnowledgeSearchController(bootstrap, mock(SearchPerSourceExecutor.class),
        null, mock(io.justsearch.app.api.OnlineAiService.class), null, null, store);
  }

  private static Context context(Map<String, Object> body) {
    var context = mock(Context.class);
    when(context.attribute(RequestEngineContext.ATTRIBUTE)).thenReturn(TestRequestContexts.browser());
    when(context.path()).thenReturn("/api/knowledge/search");
    when(context.bodyAsClass(Map.class)).thenReturn(body);
    when(context.status(anyInt())).thenReturn(context);
    return context;
  }
}
