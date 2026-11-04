/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import io.justsearch.app.api.chat.AnswersPausedException;
import io.justsearch.app.api.chat.ChatRefusal;
import io.justsearch.app.api.chat.RefusalReason;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

final class IndexingPauseWireTest {
  @Test
  void aPauseAfterChatDispatchStillFailsOnTheRunWithItsExplicitReason() {
    var engine = mock(io.justsearch.app.services.conversation.ConversationEngine.class);
    doThrow(new AnswersPausedException()).when(engine).runScoped(any(), any(), any(), any(), any(), any());
    try (var executors = new io.justsearch.core.execution.TestEngineExecutors()) {
      var controller = new ChatController(executors, engine, null, null);
      var events = new java.util.ArrayList<io.justsearch.agent.api.conversation.SseEvent>();
      controller.runToSink(new io.justsearch.agent.api.registry.ConversationShapeRef("core.chat"),
          Map.of(), io.justsearch.agent.api.registry.Audience.USER, events::add, TestRequestContexts.browser());
      assertEquals(1, events.size());
      assertEquals("error", events.getFirst().name());
      assertEquals("CHAT_UNAVAILABLE", events.getFirst().payload().get("errorCode"));
      assertEquals("INDEXING", events.getFirst().payload().get("reason"));
      assertEquals("Answers paused while indexing", events.getFirst().payload().get("error"));
      assertEquals(io.justsearch.app.api.ApiErrorCode.CHAT_UNAVAILABLE,
          ApiErrorHandler.resolve(new java.util.concurrent.CompletionException(new AnswersPausedException())));
    }
  }

  @Test
  void httpRefusalNamesThePauseAndAllowsNoForce() throws Exception {
    var body = ChatResidencyGate.refusalBody(
        new ChatRefusal(RefusalReason.INDEXING, Optional.empty(), false), null, null);
    assertEquals("CHAT_UNAVAILABLE", body.get("errorCode"));
    assertEquals("INDEXING", body.get("reason"));
    assertEquals("Answers paused while indexing", body.get("error"));
    assertEquals(false, body.get("forceAllowed"));
    ContractSchemaAssertions.assertConforms("indexing pause", "api-error-response.v1.json",
        new tools.jackson.databind.ObjectMapper().writeValueAsString(body));
  }

  @Test
  void readinessUnionAcceptsTheTypedIndexingRefusal() throws Exception {
    var body = ChatResidencyGate.refusalBody(
        new ChatRefusal(RefusalReason.INDEXING, Optional.empty(), false), null, null);
    ContractSchemaAssertions.assertConforms("readiness indexing pause", "runtime-ready-unavailable-response.v1.json",
        new tools.jackson.databind.ObjectMapper().writeValueAsString(body));
  }

  @Test
  void proxyKeepsThePausedOutcomeWhenIndexingStartsAfterTheFirstGate() throws Exception {
    var http = mock(HttpClient.class);
    var ctx = mock(Context.class);
    when(ctx.method()).thenReturn(HandlerType.POST);
    when(ctx.path()).thenReturn("/v1/chat/completions");
    when(ctx.bodyAsBytes()).thenReturn("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    when(ctx.headerMap()).thenReturn(Map.of());
    when(ctx.attribute(RequestEngineContext.ATTRIBUTE)).thenReturn(TestRequestContexts.browser());
    when(ctx.status(anyInt())).thenReturn(ctx);
    var controller = new OpenAiCompatController(http,
        () -> new io.justsearch.app.inference.LlamaServerEndpoint(8081, null), null,
        (OpenAiCompatController.ExchangeAdmission) (kind, work) -> { throw new AnswersPausedException(); });
    try {
      controller.handleChatCompletions(ctx);
      verify(ctx).status(503);
      var response = ArgumentCaptor.forClass(Object.class);
      verify(ctx).json(response.capture());
      var body = assertInstanceOf(Map.class, response.getValue());
      assertEquals("CHAT_UNAVAILABLE", body.get("errorCode"));
      assertEquals("INDEXING", body.get("reason"));
      assertEquals("Answers paused while indexing", body.get("error"));
      verify(http, never()).send(any(), any());
    } finally { controller.close(); }
  }
}
