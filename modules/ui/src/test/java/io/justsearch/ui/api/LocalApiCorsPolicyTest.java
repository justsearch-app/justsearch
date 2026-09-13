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

@DisplayName("Local API CORS allowlist & loopback safety")
class LocalApiCorsPolicyTest {

  private HttpClient client;
  private Javalin app;
  private int port;
  private ExecutorService executor;

  @BeforeEach
  void setup() {
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    executor = Executors.newSingleThreadExecutor();
  }

  @AfterEach
  void teardown() {
    if (app != null) {
      app.stop();
      app = null;
    }
    if (executor != null) {
      executor.shutdownNow();
    }
  }

  @Test
  @DisplayName("Dev mode: preflight allows loopback http origin and echoes allow-origin")
  void devModeAllowsLoopbackHttpOrigin() throws Exception {
    startCorsTestServer(false);

    HttpResponse<String> resp =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/status"))
                .timeout(Duration.ofSeconds(3))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Content-Type")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, resp.statusCode());
    assertEquals("http://localhost:5173", resp.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
    assertTrue(
        resp.headers().firstValue("Access-Control-Allow-Methods").orElse("").contains("GET"),
        "Should advertise GET support");
    assertEquals(
        "Content-Type", resp.headers().firstValue("Access-Control-Allow-Headers").orElse(null));
    assertEquals("3600", resp.headers().firstValue("Access-Control-Max-Age").orElse(null));
    assertEquals(
        "Deprecation, Sunset, Link, Retry-After",
        resp.headers().firstValue("Access-Control-Expose-Headers").orElse(null));
  }

  @Test
  @DisplayName("Dev mode: preflight denies non-loopback origins with 403")
  void devModeDeniesNonLoopbackOrigin() throws Exception {
    startCorsTestServer(false);

    HttpResponse<String> resp =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/status"))
                .timeout(Duration.ofSeconds(3))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "http://example.com:5173")
                .header("Access-Control-Request-Method", "GET")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(403, resp.statusCode());
    assertTrue(resp.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
    assertTrue(resp.headers().firstValue("Access-Control-Expose-Headers").isEmpty());
  }

  @Test
  @DisplayName("Prod mode: only desktop origins are allowed (tauri://localhost and http(s)://tauri.localhost)")
  void prodModeOnlyAllowsDesktopOrigins() throws Exception {
    startCorsTestServer(true);

    HttpResponse<String> httpOrigin =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/status"))
                .timeout(Duration.ofSeconds(3))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "GET")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(403, httpOrigin.statusCode());

    // Ensure we didn't accidentally allow arbitrary https://localhost browser origins in prod.
    HttpResponse<String> httpsLocalhostOrigin =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/status"))
                .timeout(Duration.ofSeconds(3))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "https://localhost:5173")
                .header("Access-Control-Request-Method", "GET")
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(403, httpsLocalhostOrigin.statusCode());

    HttpResponse<String> tauriOrigin =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/status"))
                .timeout(Duration.ofSeconds(3))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "tauri://localhost")
                .header("Access-Control-Request-Method", "GET")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, tauriOrigin.statusCode());
    assertEquals("tauri://localhost", tauriOrigin.headers().firstValue("Access-Control-Allow-Origin").orElse(null));

    HttpResponse<String> tauriHttpsOrigin =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/status"))
                .timeout(Duration.ofSeconds(3))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "https://tauri.localhost")
                .header("Access-Control-Request-Method", "GET")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, tauriHttpsOrigin.statusCode());
    assertEquals(
        "https://tauri.localhost",
        tauriHttpsOrigin.headers().firstValue("Access-Control-Allow-Origin").orElse(null));

    HttpResponse<String> tauriHttpOrigin =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/status"))
                .timeout(Duration.ofSeconds(3))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "http://tauri.localhost")
                .header("Access-Control-Request-Method", "GET")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, tauriHttpOrigin.statusCode());
    assertEquals(
        "http://tauri.localhost",
        tauriHttpOrigin.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
  }

  private void startCorsTestServer(boolean prodMode) {
    app = Javalin.create(cfg -> { cfg.showJavalinBanner = false; cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()); });

    new ApiSecurityFilters(
            prodMode,
            prodMode ? "test-session-token" : null,
            new EventBuffer(),
            executor,
            null)
        .install(app);

    app.get("/api/status", ctx -> ctx.json(Map.of("status", "ok")));

    app.start("127.0.0.1", 0);
    port = app.port();
  }
}
