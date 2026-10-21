package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;

import io.javalin.Javalin;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("UI Session Token enforcement (prod mode)")
class LocalApiUiTokenPolicyTest {

  private HttpClient client;
  private Javalin app;
  private ExecutorService executor;
  private int port;
  private static final String TEST_TOKEN = "test-session-token-abc123";

  @BeforeEach
  void setup() {
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  }

  @AfterEach
  void teardown() {
    if (app != null) {
      app.stop();
      app = null;
    }
    if (executor != null) {
      executor.shutdownNow();
      executor = null;
    }
  }

  @Test
  @DisplayName("OPTIONS preflight succeeds without token")
  void optionsPreflightSucceedsWithoutToken() throws Exception {
    startTokenTestServer(true, TEST_TOKEN);

    HttpResponse<String> resp =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/settings/v2"))
                .timeout(Duration.ofSeconds(3))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "tauri://localhost")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type, X-JustSearch-Session")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, resp.statusCode());
    // Should echo back the requested headers
    String allowHeaders = resp.headers().firstValue("Access-Control-Allow-Headers").orElse("");
    assertTrue(allowHeaders.contains("X-JustSearch-Session"), "Should allow X-JustSearch-Session header");
  }

  @Test
  @DisplayName("Public status read succeeds without token")
  void getSucceedsWithoutToken() throws Exception {
    startTokenTestServer(true, TEST_TOKEN);

    HttpResponse<String> resp =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/status"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .header("Origin", "tauri://localhost")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, resp.statusCode());
    assertTrue(resp.body().contains("\"status\":\"ok\""));
  }

  @Test
  @DisplayName("POST without token returns 401 in prod mode")
  void postWithoutTokenReturns401() throws Exception {
    startTokenTestServer(true, TEST_TOKEN);

    HttpResponse<String> resp =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/settings/v2"))
                .timeout(Duration.ofSeconds(3))
                .POST(HttpRequest.BodyPublishers.ofString("{\"ui\":{}}"))
                .header("Content-Type", "application/json")
                .header("Origin", "tauri://localhost")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(401, resp.statusCode());
    assertTrue(resp.body().contains("UI_TOKEN_REQUIRED"));
  }

  @Test
  @DisplayName("POST with correct token succeeds")
  void postWithCorrectTokenSucceeds() throws Exception {
    startTokenTestServer(true, TEST_TOKEN);

    HttpResponse<String> resp =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/settings/v2"))
                .timeout(Duration.ofSeconds(3))
                .POST(HttpRequest.BodyPublishers.ofString("{\"ui\":{}}"))
                .header("Content-Type", "application/json")
                .header("Origin", "tauri://localhost")
                .header(LocalApiServer.SESSION_TOKEN_HEADER, TEST_TOKEN)
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, resp.statusCode());
  }

  @Test
  @DisplayName("POST with wrong token returns 401")
  void postWithWrongTokenReturns401() throws Exception {
    startTokenTestServer(true, TEST_TOKEN);

    HttpResponse<String> resp =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/settings/v2"))
                .timeout(Duration.ofSeconds(3))
                .POST(HttpRequest.BodyPublishers.ofString("{\"ui\":{}}"))
                .header("Content-Type", "application/json")
                .header("Origin", "tauri://localhost")
                .header(LocalApiServer.SESSION_TOKEN_HEADER, "wrong-token")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(401, resp.statusCode());
    assertTrue(resp.body().contains("UI_TOKEN_REQUIRED"));
  }

  @Test
  @DisplayName("DELETE without token returns 401 in prod mode")
  void deleteWithoutTokenReturns401() throws Exception {
    startTokenTestServer(true, TEST_TOKEN);

    HttpResponse<String> resp =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/indexing/roots"))
                .timeout(Duration.ofSeconds(3))
                .method("DELETE", HttpRequest.BodyPublishers.ofString("{\"path\":\"/test\"}"))
                .header("Content-Type", "application/json")
                .header("Origin", "tauri://localhost")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(401, resp.statusCode());
    assertTrue(resp.body().contains("UI_TOKEN_REQUIRED"));
  }

  @Test
  @DisplayName("Dev mode (no token configured) allows all requests")
  void devModeAllowsAllRequests() throws Exception {
    // Start with prodMode=false (no token enforcement)
    startTokenTestServer(false, null);

    HttpResponse<String> resp =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/settings/v2"))
                .timeout(Duration.ofSeconds(3))
                .POST(HttpRequest.BodyPublishers.ofString("{\"ui\":{}}"))
                .header("Content-Type", "application/json")
                .header("Origin", "http://localhost:5173")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    // Should succeed even without token
    assertEquals(200, resp.statusCode());
    var read = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/private"))
        .GET().build();
    assertEquals(200, client.send(read, HttpResponse.BodyHandlers.ofString()).statusCode());
  }

  @Test
  @DisplayName("Token generation produces valid base64url tokens")
  void tokenGenerationProducesValidTokens() {
    String token1 = LocalApiServer.generateSessionToken();
    String token2 = LocalApiServer.generateSessionToken();

    assertNotNull(token1);
    assertNotNull(token2);
    assertFalse(token1.isEmpty());
    assertFalse(token2.isEmpty());
    assertNotEquals(token1, token2, "Each generated token should be unique");
    // Base64URL should not contain + / = (only alphanumeric, -, _)
    assertTrue(token1.matches("[A-Za-z0-9_-]+"), "Token should be base64url encoded");
    // Should be reasonable length (32 bytes encoded ~ 43 chars without padding)
    assertTrue(token1.length() >= 40, "Token should be at least 40 chars (32 bytes base64url)");
  }

  @Test
  @DisplayName("Removed bootstrap requires authentication in prod mode")
  void getMcpTokenRequiresAuthenticationInProdMode() throws Exception {
    startTokenTestServer(true, TEST_TOKEN);

    HttpResponse<String> resp =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/mcp/token"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(401, resp.statusCode());
    assertFalse(resp.body().contains(TEST_TOKEN));
  }

  @Test
  @DisplayName("Removed bootstrap is absent in dev mode")
  void getMcpTokenIsAbsentInDevMode() throws Exception {
    startTokenTestServer(false, null);

    HttpResponse<String> resp =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/mcp/token"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(404, resp.statusCode());
  }

  @Test
  void nonPublicReadsAndOtherMethodsNeedToken() throws Exception {
    startTokenTestServer(true, TEST_TOKEN);
    for (String method : java.util.List.of("GET", "HEAD", "PATCH", "TRACE")) {
      var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/private"))
          .method(method, HttpRequest.BodyPublishers.noBody()).build();
      assertEquals(401, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode(), method);
    }

  }

  /**
   * Starts a test server carrying the PRODUCTION filter chain — {@link ApiSecurityFilters#install}
   * itself, not a re-statement of it.
   *
   * <p>This used to hand-copy the CORS block and the token filter, including the install guard
   * {@code if (prodMode && sessionToken != null && !sessionToken.isBlank())}. That guard was the
   * fail-open predicate tempdoc 884 item 23 removed: once production refuses to construct in the
   * (prod, no-token) case, a copy that still branches on it describes behaviour that no longer
   * exists, and would keep passing while production changed underneath it.
   */
  private void startTokenTestServer(boolean prodMode, String sessionToken) {
    app = Javalin.create(cfg -> { cfg.showJavalinBanner = false; cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()); });

    // Register no-op exception handler to preserve ctx.json() body (mirrors LocalApiServer)
    app.exception(io.javalin.http.HttpResponseException.class, (e, ctx) -> {
      ctx.status(e.getStatus()); // Preserve bodies and propagate routing errors, as production does.
    });

    executor = Executors.newSingleThreadExecutor();
    new ApiSecurityFilters(prodMode, sessionToken, new EventBuffer(), executor, null).install(app);

    app.get("/api/private", ctx -> ctx.result("private"));
    // Test endpoints
    app.get("/api/status", ctx -> ctx.json(Map.of("status", "ok")));
    app.post("/api/settings/v2", ctx -> ctx.json(Map.of("success", true)));
    app.delete("/api/indexing/roots", ctx -> ctx.json(Map.of("success", true)));


    app.start("127.0.0.1", 0);
    port = app.port();
  }
}
