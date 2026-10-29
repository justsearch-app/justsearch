/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.javalin.http.Context;
import io.justsearch.app.services.conversation.ConversationEngine;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.SearchPerSourceExecutor;
import io.justsearch.core.execution.TestEngineExecutors;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/** D5/F5: both actual entry handlers refuse malformed restrictions before work or SSE admission. */
class RestrictionValidationEntryTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private io.justsearch.configuration.resolved.ConfigStore previous;

  @org.junit.jupiter.api.BeforeEach
  void configure() {
    previous = io.justsearch.configuration.resolved.ConfigStore.globalOrNull();
    io.justsearch.configuration.resolved.TestResolvedConfigHelper.storeWithDefaults();
  }

  @org.junit.jupiter.api.AfterEach
  void restore() {
    io.justsearch.configuration.resolved.TestResolvedConfigHelper.restoreGlobal(previous);
  }

  @Test
  void searchAndAskRejectTheExactValidationMatrixBeforeAdmission() throws Exception {
    List<String> invalid = List.of(
        "[]", "1", "true", "\"bad\"",
        "{\"pathPrefix\":\"\"}", "{\"pathPrefix\":\"relative\"}",
        "{\"pathPrefix\":false}", "{\"pathPrefix\":[]}",
        "{\"pathPrefix\":\"/" + "a".repeat(4096) + "\"}",
        "{\"modifiedAt\":[]}", "{\"modifiedAt\":3}",
        "{\"modifiedAt\":{\"fromMs\":-1}}", "{\"modifiedAt\":{\"toMs\":1.5}}",
        "{\"modifiedAt\":{\"fromMs\":\"1\"}}", "{\"modifiedAt\":{\"fromMs\":true}}",
        "{\"modifiedAt\":{\"fromMs\":[]}}", "{\"modifiedAt\":{\"toMs\":{}}}",
        "{\"modifiedAt\":{\"fromMs\":2,\"toMs\":1}}",
        "{\"modifiedAt\":{\"toMs\":253402300800000}}",
        "{\"modifiedAt\":{\"fromMs\":253402300799999.00001}}",
        "{\"sizeBytes\":false}", "{\"sizeBytes\":[]}",
        "{\"sizeBytes\":{\"fromBytes\":-1}}", "{\"sizeBytes\":{\"toBytes\":0.1}}",
        "{\"sizeBytes\":{\"fromBytes\":\"0\"}}", "{\"sizeBytes\":{\"fromBytes\":true}}",
        "{\"sizeBytes\":{\"fromBytes\":[]}}", "{\"sizeBytes\":{\"toBytes\":{}}}",
        "{\"sizeBytes\":{\"fromBytes\":1,\"toBytes\":0}}",
        "{\"sizeBytes\":{\"toBytes\":9007199254740992}}",
        "{\"sizeBytes\":{\"fromBytes\":9007199254740990.5}}",
        "{\"sizeBytes\":{\"toBytes\":1e100}}");
    try (var executors = new TestEngineExecutors()) {
      var engine = mock(ConversationEngine.class);
      var sse = mock(SseWriter.class);
      var chat = new ChatController(executors, engine, sse, null);
      var residency = mock(ChatResidencyGate.class);
      chat.setResidencyGate(residency);
      var bootstrap = mock(KnowledgeServerBootstrap.class);
      var search = new KnowledgeSearchController(bootstrap, mock(SearchPerSourceExecutor.class));
      try {
        for (String filters : invalid) {
          String field = filters.contains("pathPrefix") ? "pathPrefix"
              : filters.contains("modifiedAt") ? "modifiedAt"
              : filters.contains("sizeBytes") ? "sizeBytes" : "filters";
          String body = "{\"shapeId\":\"core.rag-ask\",\"query\":\"needle\",\"question\":\"needle\",\"filters\":" + filters + "}";
          var searchCtx = context(body);
          search.handleSearch(searchCtx);
          refused(searchCtx, field);
          var askCtx = context(body);
          chat.dynamicHandler("/api/chat/dispatch").handle(askCtx);
          refused(askCtx, field);
        }
        verifyNoInteractions(bootstrap, sse, residency);
        verify(engine, never()).run(any(), any(), any(), any(), any());
      } finally {
        chat.shutdown();
      }
    }
  }

  @Test
  void invalidRestrictionWinsOverInvalidSearchSyntaxBeforeDispatch() throws Exception {
    var bootstrap = mock(KnowledgeServerBootstrap.class);
    var executor = mock(SearchPerSourceExecutor.class);
    var search = new KnowledgeSearchController(bootstrap, executor);
    var ctx = context("{\"query\":\"title:\",\"querySyntax\":\"search\","
        + "\"filters\":{\"sizeBytes\":{\"fromBytes\":-1}}}");
    search.handleSearch(ctx);
    refused(ctx, "sizeBytes");
    verifyNoInteractions(bootstrap, executor);
  }

  @SuppressWarnings("unchecked")
  private static Context context(String body) {
    var ctx = mock(Context.class, RETURNS_SELF);
    when(ctx.body()).thenReturn(body);
    when(ctx.bodyAsClass(Map.class)).thenReturn(JSON.readValue(body, Map.class));
    when(ctx.attribute(RequestEngineContext.ATTRIBUTE)).thenReturn(TestRequestContexts.browser());
    when(ctx.path()).thenReturn("/api/knowledge/search");
    return ctx;
  }

  @SuppressWarnings("unchecked")
  private static void refused(Context ctx, String field) {
    verify(ctx).status(400);
    var body = ArgumentCaptor.forClass(Object.class);
    verify(ctx).json(body.capture());
    var error = (Map<String, Object>) body.getValue();
    assertEquals("INVALID_REQUEST", error.get("errorCode"));
    assertTrue(error.get("error").toString().contains(field), error.toString());
  }
}
