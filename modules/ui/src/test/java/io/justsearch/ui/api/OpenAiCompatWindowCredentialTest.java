/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.sun.net.httpserver.HttpServer;
import io.javalin.Javalin;
import io.justsearch.app.inference.LlamaServerEndpoint;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** S7: inference cannot echo an app-window credential into an API response or its own logs. */
final class OpenAiCompatWindowCredentialTest {
  @Test
  void s7NeitherEngineCredentialCrossesTheInferenceProxy() throws Exception {
    var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    upstream.createContext("/v1/chat/completions", exchange -> {
      byte[] body = ("{\"window\":\"" + exchange.getRequestHeaders().getFirst("X-JustSearch-Window")
          + "\",\"session\":\"" + exchange.getRequestHeaders().getFirst("X-JustSearch-Session")
          + "\"}").getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    upstream.start();
    var app = Javalin.create(config -> config.showJavalinBanner = false);
    try (var http = HttpClient.newHttpClient();
        var proxy = new OpenAiCompatController(http,
            () -> new LlamaServerEndpoint(upstream.getAddress().getPort(), null), null)) {
      app.before(ctx -> ctx.attribute(RequestEngineContext.ATTRIBUTE, TestRequestContexts.browser()));
      app.post("/v1/chat/completions", proxy::handleChatCompletions);
      app.start("127.0.0.1", 0);
      var request = HttpRequest.newBuilder(URI.create(
          "http://127.0.0.1:" + app.port() + "/v1/chat/completions"))
          .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
          .header("X-JustSearch-Session", "manifest-secret")
          .header("X-JustSearch-Window", "window-secret")
          .POST(HttpRequest.BodyPublishers.ofString("{}"))
          .build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertFalse(response.body().contains("window-secret"), response.body());
      assertFalse(response.body().contains("manifest-secret"), response.body());
    } finally {
      app.stop();
      upstream.stop(0);
    }
  }
}
