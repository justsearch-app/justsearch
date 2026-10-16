/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import io.justsearch.app.api.chat.GenerationLease;
import java.io.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

final class OpenAiCompatTokenProgressTest {
  @Test void proxyReportsTokensButDoesNotCreditHeartbeatsOrEmptyDeltas() throws Exception {
    var progress = new AtomicInteger();
    var lease = mock(GenerationLease.class, invocation -> {
      if (invocation.getMethod().getName().equals("tokenProduced")) progress.incrementAndGet();
      return null;
    });
    String heartbeat = ": keep-alive\n\ndata: {\"choices\":[{\"delta\":{}}]}\n\n";
    String token = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking\"}}]}\n\n";
    var input = new ByteArrayInputStream((heartbeat + token + "data: [DONE]\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var http = mock(HttpClient.class);
    @SuppressWarnings("unchecked") var response = (HttpResponse<InputStream>) mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.headers()).thenReturn(HttpHeaders.of(Map.of("content-type", List.of("text/event-stream")), (a,b) -> true));
    when(response.body()).thenReturn(input);
    doReturn(response).when(http).send(any(), any());
    var ctx = mock(Context.class);
    when(ctx.method()).thenReturn(HandlerType.POST);
    when(ctx.path()).thenReturn("/v1/chat/completions");
    when(ctx.bodyAsBytes()).thenReturn("{\"stream\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    when(ctx.headerMap()).thenReturn(Map.of());
    when(ctx.attribute(RequestEngineContext.ATTRIBUTE)).thenReturn(TestRequestContexts.browser());
    var body = ArgumentCaptor.forClass(InputStream.class);
    when(ctx.result(body.capture())).thenReturn(ctx);
    var controller = new OpenAiCompatController(http, () -> 8081, null,
        (OpenAiCompatController.ExchangeAdmission) (kind, work) -> lease);
    try {
      controller.handleChatCompletions(ctx);
      try (var stream = body.getValue()) {
        // Exercise fragmented reads (including the single-byte read), as the servlet does.
        for (int i = 0; i < heartbeat.getBytes(java.nio.charset.StandardCharsets.UTF_8).length; i++) assertTrue(stream.read() >= 0);
        assertEquals(0, progress.get(), "heartbeats and empty deltas prove no token work");
        byte[] rest = stream.readAllBytes();
        assertTrue(rest.length > 0);
        assertEquals(1, progress.get(), "a proxied reasoning token is server health evidence");
      }
      verify(lease).close();
    } finally { controller.close(); }
  }
}
