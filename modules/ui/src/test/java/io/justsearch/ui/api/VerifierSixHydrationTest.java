package io.justsearch.ui.api;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.javalin.Javalin;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.SearchPerSourceExecutor;
import java.net.*; import java.net.http.*; import java.util.*;
import org.junit.jupiter.api.Test;

/** Independent real-HTTP input contracts. No subprocess or filesystem cleanup. */
public class VerifierSixHydrationTest {
  io.justsearch.configuration.resolved.ConfigStore prior,config;
  @org.junit.jupiter.api.BeforeEach void configure(){ prior=io.justsearch.configuration.resolved.ConfigStore.globalOrNull();config=new io.justsearch.configuration.resolved.ConfigStore(io.justsearch.configuration.resolved.TestResolvedConfigHelper.fromEntries(Map.of("justsearch.qu.enabled","false","justsearch.filter_norm.enabled","false")));io.justsearch.configuration.resolved.ConfigStore.setGlobal(config); }
  @org.junit.jupiter.api.AfterEach void restore(){io.justsearch.configuration.resolved.ConfigStore.restoreGlobal(config,prior);}
  void request(String method, String route, String body, int expected) throws Exception {
    var bootstrap=mock(KnowledgeServerBootstrap.class);
    var indexing=mock(IndexingService.class);
    var search=new KnowledgeSearchController(bootstrap,mock(SearchPerSourceExecutor.class));
    var paths=new IndexingController(()->indexing,null,null,null);
    var dispatcher=mock(io.justsearch.agent.api.registry.OperationDispatcher.class);
    var operations=new OperationsController(List.of(new io.justsearch.app.services.registry.operations.CoreOperationCatalog()),dispatcher);
    Object owner=method.equals("handleInvoke") ? operations : method.equals("handleCollectionFacet") || method.equals("handleSearch") ? search : paths;
    java.lang.reflect.Method handler;
    try {handler=owner.getClass().getMethod(method,io.javalin.http.Context.class);}
    catch(NoSuchMethodException absent){fail("Dedicated bounded unattended handler absent: "+method);return;}
    var app=Javalin.create(c->{c.showJavalinBanner=false;c.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());});
    app.post(route,c->handler.invoke(owner,c));app.start("127.0.0.1",0);
    try(var client=HttpClient.newHttpClient()) {
      var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+app.port()+route.replace("{id}","core.resolve-path-hash"))).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
      assertEquals(expected,response.statusCode(),body+" => "+response.body());
      verifyNoInteractions(indexing);
    }finally{app.stop();}
  }
  @Test void collectionCannotSmuggleQueryFiltersPipelineOrDocumentFields()throws Exception {
    for(String body:List.of("{\"maxDocsScanned\":100,\"query\":\"private words\"}","{\"maxDocsScanned\":100,\"filters\":{}}","{\"maxDocsScanned\":100,\"pipeline\":{}}","{\"maxDocsScanned\":100,\"fields\":[\"content\"]}","{\"maxDocsScanned\":1.5}","{\"maxDocsScanned\":2147483648}","{\"maxDocsScanned\":0}"))
      request("handleCollectionFacet","/api/knowledge/collection-facet",body,400);
  }
  @Test void pathLabelCannotInvokeAnotherOperationOrResolveByRawPath()throws Exception {
    for(String body:List.of("{\"pathHash\":\"abc\",\"path\":\"C:/secret\"}","{\"pathHash\":\"abc\",\"operationId\":\"delete-root\"}","{\"pathHash\":42}","{\"pathHash\":\" \"}","{}"))
      request("handlePathLabel","/api/library/path-label",body,400);
  }
  @Test void collectionMalformedJsonPreservesExistingSearchError()throws Exception {
    request("handleSearch","/api/knowledge/search","{",500);
    request("handleCollectionFacet","/api/knowledge/collection-facet","{",500);
  }
  @Test void existingInvocationMalformedJsonRemainsClientError()throws Exception {request("handleInvoke","/api/operations/{id}/invoke","{",400);}
  @Test void pathLabelMalformedJsonPreservesInvocationClientError()throws Exception {
    request("handleInvoke","/api/operations/{id}/invoke","{",400);
    request("handlePathLabel","/api/library/path-label","{",400);
  }
}
