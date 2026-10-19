/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.javalin.http.Context;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.worker.*;
import io.justsearch.configuration.resolved.*;
import io.justsearch.core.context.EngineContext;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CollectionFacetObservationTest {
  @Test void enumerationIsFixedLexicalAndDoesNotAcceptAnInteractiveQuery() {
    var config = new ConfigStore(TestResolvedConfigHelper.fromEntries(Map.of(
        "justsearch.qu.enabled", "false", "justsearch.filter_norm.enabled", "false")));
    var previous = ConfigStore.globalOrNull();
    ConfigStore.setGlobal(config);
    try {
    var client = mock(KnowledgeClient.class);
    when(client.search(any(SearchRequest.class), any(EngineContext.class))).thenReturn(SearchResponse.getDefaultInstance());
    var bootstrap = mock(KnowledgeServerBootstrap.class);
    var lease = mock(KnowledgeServerBootstrap.ClientLease.class);
    when(bootstrap.publicationLock()).thenReturn(config.publicationLock());
    when(bootstrap.acquireClientLease()).thenReturn(lease);
    when(lease.client()).thenReturn(client);
    when(lease.withClient(any())).thenAnswer(invocation ->
        ((java.util.function.Function<KnowledgeClient, ?>) invocation.getArgument(0)).apply(client));
    var controller = new KnowledgeSearchController(bootstrap, mock(SearchPerSourceExecutor.class),
        null, OnlineAiService.unavailable(), null, null, config);
    var ctx = mock(Context.class);
    when(ctx.attribute(RequestEngineContext.ATTRIBUTE)).thenReturn(TestRequestContexts.browser());
    when(ctx.path()).thenReturn("/api/knowledge/collection-facet");
    when(ctx.status(anyInt())).thenReturn(ctx);
    when(ctx.bodyAsClass(Map.class)).thenReturn(Map.of("maxDocsScanned", 900_000));
    controller.handleCollectionFacet(ctx);
    var request = ArgumentCaptor.forClass(SearchRequest.class);
    verify(client).search(request.capture(), any(EngineContext.class));
    var actual = request.getValue();
    assertEquals("*:*", actual.getQuery());
    assertEquals(1, actual.getLimit());
    assertEquals(900_000, actual.getFacets().getMaxDocsScanned());
    assertEquals("collection", actual.getFacets().getFields(0).getField());
    assertFalse(actual.getPipeline().getDenseEnabled());
    assertFalse(actual.getPipeline().getSpladeEnabled());
    assertFalse(actual.getPipeline().getExpansionEnabled());
    clearInvocations(client, ctx);
    when(ctx.bodyAsClass(Map.class)).thenReturn(Map.of("maxDocsScanned", 50_000, "query", "deliberate"));
    controller.handleCollectionFacet(ctx);
    verify(ctx).status(400);
    verifyNoInteractions(client);
    } finally {
      ConfigStore.restoreGlobal(config, previous);
    }
  }

  @Test void observationReadPreservesActivityWhileInteractiveSearchAdvancesIt() throws Exception {
    var test = new VerifierRoundFourActivityTest();
    test.http("post", "/api/knowledge/collection-facet", false);
    test.http("post", "/api/library/path-label", false);
    test.http("post", "/api/knowledge/search", true);
  }
}
