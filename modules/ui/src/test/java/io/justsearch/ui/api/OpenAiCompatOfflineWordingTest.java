/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * When the chat server is not listening (it stopped, or a relaunch moved it), the {@code /v1}
 * proxy tells the client chat is not running and to retry, without the stale internal port
 * (b6d536 item 5: the "port 8081" wording).
 */
final class OpenAiCompatOfflineWordingTest {

  private static final int STALE_PORT = 8081;

  @Test
  void aRefusedConnectionSaysChatIsNotRunningWithoutTheStalePort() throws Exception {
    HttpClient upstream = mock(HttpClient.class);
    when(upstream.send(any(), any())).thenThrow(new ConnectException("Connection refused"));
    List<Integer> statuses = new ArrayList<>();
    List<Object> bodies = new ArrayList<>();

    new OpenAiCompatController(upstream, () -> new io.justsearch.app.inference.LlamaServerEndpoint(STALE_PORT, null), null)
        .handleChatCompletions(context(statuses, bodies));

    assertEquals(List.of(503), statuses);
    Map<?, ?> body = assertInstanceOf(Map.class, bodies.get(0));
    assertEquals("TRANSIENT", body.get("errorClass"));
    String message = String.valueOf(body.get("error"));
    assertFalse(message.contains(Integer.toString(STALE_PORT)),
        "the message names no internal port: " + message);
    assertFalse(message.contains("llama-server"),
        "the message names chat, not the internal process: " + message);
  }

  private static Context context(List<Integer> statuses, List<Object> bodies) {
    Context ctx = mock(Context.class);
    when(ctx.method()).thenReturn(HandlerType.POST);
    when(ctx.path()).thenReturn("/v1/chat/completions");
    when(ctx.endpointHandlerPath()).thenReturn("/v1/chat/completions");
    when(ctx.attribute(RequestEngineContext.ATTRIBUTE)).thenReturn(TestRequestContexts.browser());
    when(ctx.bodyAsBytes()).thenReturn(new byte[0]);
    when(ctx.headerMap()).thenReturn(Map.of("content-type", "application/json"));
    when(ctx.header("content-type")).thenReturn("application/json");
    when(ctx.status(anyInt())).thenAnswer(invocation -> {
      statuses.add(invocation.getArgument(0));
      return ctx;
    });
    when(ctx.json(any())).thenAnswer(invocation -> {
      bodies.add(invocation.getArgument(0));
      return ctx;
    });
    return ctx;
  }
}
