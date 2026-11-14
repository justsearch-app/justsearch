/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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

class KnowledgeSearchHelpExclusionValidationTest {
  private final ConfigStore previous = ConfigStore.globalOrNull();

  @AfterEach
  void restore() {
    TestResolvedConfigHelper.restoreGlobal(previous);
  }

  @Test
  void trimsAndDeduplicatesExclusionsBeforeWorkerAdmission() {
    var client = mock(KnowledgeClient.class);
    when(client.search(any(SearchRequest.class), anyBoolean(), any(EngineContext.class)))
        .thenReturn(SearchResponse.getDefaultInstance());
    var ctx = context(Map.of("query", "needle", "excludedCollections",
        List.of(" justsearch-help ", "justsearch-help"), "pipeline", Map.of()));
    controller(client).handleSearch(ctx);
    var request = ArgumentCaptor.forClass(SearchRequest.class);
    verify(client).search(request.capture(), anyBoolean(), any(EngineContext.class));
    assertEquals(List.of("justsearch-help"), request.getValue().getExcludedCollectionsList());
  }

  @Test
  void rejectsMoreThanSixteenValuesBeforeDeduplicating() {
    var client = mock(KnowledgeClient.class);
    var ctx = context(Map.of("query", "needle", "excludedCollections",
        java.util.Collections.nCopies(17, "justsearch-help"), "pipeline", Map.of()));
    controller(client).handleSearch(ctx);
    verify(ctx).status(400);
    var body = ArgumentCaptor.forClass(Object.class);
    verify(ctx).json(body.capture());
    assertTrue(body.getValue().toString().contains("16"), "error must explain the limit");
    verify(client, never()).search(any(SearchRequest.class), anyBoolean(), any(EngineContext.class));
  }

  @Test
  void sixteenValuesAreAcceptedAndTrimmedBlankIsStillBadRequest() {
    var client = mock(KnowledgeClient.class);
    when(client.search(any(SearchRequest.class), anyBoolean(), any(EngineContext.class)))
        .thenReturn(SearchResponse.getDefaultInstance());
    var accepted = context(Map.of("query", "needle", "excludedCollections",
        java.util.Collections.nCopies(16, "justsearch-help"), "pipeline", Map.of()));
    controller(client).handleSearch(accepted);
    verify(client).search(any(SearchRequest.class), anyBoolean(), any(EngineContext.class));
    clearInvocations(client);
    var rejected = context(Map.of("query", "needle", "excludedCollections", List.of(" \t "),
        "pipeline", Map.of()));
    controller(client).handleSearch(rejected);
    verify(rejected).status(400);
    verify(client, never()).search(any(SearchRequest.class), anyBoolean(), any(EngineContext.class));
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
