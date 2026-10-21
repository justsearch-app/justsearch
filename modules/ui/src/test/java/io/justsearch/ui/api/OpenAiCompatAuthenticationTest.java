/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.javalin.Javalin;
import io.justsearch.app.inference.LlamaServerEndpoint;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Managed credentials stay in the Engine proxy; caller credentials remain external-only. */
final class OpenAiCompatAuthenticationTest {
  @ParameterizedTest
  @CsvSource({"true,false", "true,true", "false,false", "false,true"})
  void theProxyAuthenticatesItsCurrentEndpointWithoutDisclosingItsKey(
      boolean managed, boolean streaming) throws Exception {
    var currentKey = new AtomicReference<>(java.util.UUID.randomUUID().toString());
    var authenticated = new AtomicBoolean();
    var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    upstream.createContext("/v1/chat/completions", exchange -> {
      String auth = exchange.getRequestHeaders().getFirst("Authorization");
      String alternative = exchange.getRequestHeaders().getFirst("X-Api-Key");
      boolean correct = managed
          ? ("Bearer " + currentKey.get()).equals(auth) && alternative == null
          : "Bearer caller-key".equals(auth) && "caller-alternative".equals(alternative);
      authenticated.set(correct);
      byte[] body = (streaming ? "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"
          : "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}").getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
      // A credential-bearing upstream header must never be forwarded to the API caller.
      exchange.getResponseHeaders().set("Authorization", "Bearer " + currentKey.get());
      exchange.sendResponseHeaders(correct ? 200 : 401, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    upstream.start();
    var endpoint = new AtomicReference<>(new LlamaServerEndpoint(
        upstream.getAddress().getPort(), managed ? currentKey.get() : null));
    var app = Javalin.create(config -> config.showJavalinBanner = false);
    try (var http = HttpClient.newHttpClient();
        var proxy = new OpenAiCompatController(http, endpoint::get, null)) {
      app.before(ctx -> ctx.attribute(RequestEngineContext.ATTRIBUTE, TestRequestContexts.browser()));
      app.post("/v1/chat/completions", proxy::handleChatCompletions);
      app.start("127.0.0.1", 0);
      for (int i = 0; i < 2; i++) {
        var request = HttpRequest.newBuilder(URI.create(
            "http://127.0.0.1:" + app.port() + "/v1/chat/completions"))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer caller-key")
            .header("X-Api-Key", "caller-alternative")
            .POST(HttpRequest.BodyPublishers.ofString("{\"stream\":" + streaming + "}"))
            .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(authenticated.get(), "only the current endpoint's credential authorizes managed inference");
        assertTrue(response.body().contains("ok"));
        assertTrue(response.headers().firstValue("Authorization").isEmpty());
        assertFalse(response.body().contains(currentKey.get()));
        assertFalse(endpoint.get().toString().contains(currentKey.get()));
        currentKey.set(java.util.UUID.randomUUID().toString());
        endpoint.set(new LlamaServerEndpoint(upstream.getAddress().getPort(), managed ? currentKey.get() : null));
      }
    } finally {
      app.stop();
      upstream.stop(0);
    }
  }
}
