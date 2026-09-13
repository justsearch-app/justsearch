package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.javalin.Javalin;
import io.justsearch.ui.api.routes.RuntimeApiRoutes;
import io.justsearch.ui.api.routes.StatusRoutes;
import io.justsearch.ui.runtime.RuntimeManifestPublisher;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 633 §1a — the Host-header allowlist (DNS-rebinding defense). The loopback bind + CORS Origin
 * allowlist do not stop a DNS-rebound page from executing token-exempt GET reads (post-rebind the page
 * is same-origin, so CORS no longer applies); only a {@code Host} allowlist does. These tests pin both
 * the pure predicate ({@link ApiSecurityFilters#isAllowedHost}) and the live before-filter behaviour.
 */
@DisplayName("Local API Host allowlist & DNS-rebinding safety")
class LocalApiHostValidationTest {

  private Javalin app;
  private int port;

  @AfterEach
  void teardown() {
    if (app != null) {
      app.stop();
      app = null;
    }
  }

  // ---- pure predicate ----

  @Test
  @DisplayName("isAllowedHost: loopback hosts (with/without port, IPv6) are allowed")
  void allowsLoopbackHosts() {
    assertTrue(ApiSecurityFilters.isAllowedHost("127.0.0.1:8080"));
    assertTrue(ApiSecurityFilters.isAllowedHost("127.0.0.1"));
    assertTrue(ApiSecurityFilters.isAllowedHost("localhost:5173"));
    assertTrue(ApiSecurityFilters.isAllowedHost("LOCALHOST:5173"));
    assertTrue(ApiSecurityFilters.isAllowedHost("[::1]:8080"));
  }

  @Test
  @DisplayName("isAllowedHost: non-loopback hosts and missing Host are rejected")
  void rejectsNonLoopbackHosts() {
    assertFalse(ApiSecurityFilters.isAllowedHost("evil.com:8080"));
    assertFalse(ApiSecurityFilters.isAllowedHost("evil.com"));
    assertFalse(ApiSecurityFilters.isAllowedHost("attacker.localhost.evil.com:8080"));
    assertFalse(ApiSecurityFilters.isAllowedHost("169.254.169.254"));
    assertFalse(ApiSecurityFilters.isAllowedHost(null));
    assertFalse(ApiSecurityFilters.isAllowedHost(""));
    assertFalse(ApiSecurityFilters.isAllowedHost("   "));
  }

  // ---- live before-filter (mirrors the production install() guard) ----
  // A raw socket is used so we can set an arbitrary Host header deterministically — the JDK HttpClient
  // restricts overriding Host, and its `allowRestrictedHeaders` property is read too early to toggle here.

  @Test
  @DisplayName("Live: every SDK GET route rejects a foreign Host with the declared 403 body")
  void everySdkGetWithForeignHostIsForbidden() throws Exception {
    startHostGuardedServer();
    for (RouteContractPolicy.Contract contract :
        RouteContractPolicy.CONTRACTS.stream()
            .filter(RouteContractPolicy.Contract::sdkExposed)
            .toList()) {
      assertTrue(
          contract.responseSchemas().containsKey(403),
          () -> contract.key() + " must declare the global Host rejection");
      RawResponse response = rawGet("evil.com", contract.path());
      assertEquals(
          403,
          response.status(),
          () -> contract.key() + " must reject a foreign Host before its handler runs");
      ContractSchemaAssertions.assertConforms(
          contract.key() + " status 403", "api-error-response.v1.json", response.body());
    }
  }

  @Test
  @DisplayName("Live: the same GET read with the real loopback Host succeeds")
  void liveLoopbackHostSucceeds() throws Exception {
    startHostGuardedServer();
    RawResponse response = rawGet("127.0.0.1:" + port, "/api/runtime/live");
    assertEquals(200, response.status(), "Legitimate loopback Host must pass the guard");
  }

  /** Sends a raw HTTP/1.1 GET with an explicit Host header and returns its status and body. */
  private RawResponse rawGet(String hostHeader, String path) throws Exception {
    try (java.net.Socket socket = new java.net.Socket()) {
      socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 2000);
      socket.setSoTimeout(3000);
      String request =
          "GET " + path + " HTTP/1.1\r\n"
              + "Host: " + hostHeader + "\r\n"
              + "Connection: close\r\n"
              + "\r\n";
      socket.getOutputStream().write(request.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      String wire =
          new String(socket.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      int separator = wire.indexOf("\r\n\r\n");
      assertTrue(separator >= 0, "Expected HTTP headers and a response body: " + wire);
      String headers = wire.substring(0, separator);
      String statusLine = headers.lines().findFirst().orElse(null); // e.g. "HTTP/1.1 403 Forbidden"
      assertNotNull(statusLine, "Expected an HTTP status line");
      String[] parts = statusLine.split(" ");
      assertTrue(parts.length >= 2, "Malformed status line: " + statusLine);
      return new RawResponse(Integer.parseInt(parts[1]), wire.substring(separator + 4));
    }
  }

  private record RawResponse(int status, String body) {}

  /** Hermetic SDK server using the production route registrars and security-filter installation. */
  private void startHostGuardedServer() {
    app = Javalin.create(cfg -> {
      cfg.showJavalinBanner = false;
      cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    });
    // Production preserves a body already written by a filter while propagating its status.
    app.exception(io.javalin.http.HttpResponseException.class, (e, ctx) -> ctx.status(e.getStatus()));

    RuntimeManifestPublisher publisher = mock(RuntimeManifestPublisher.class);
    when(publisher.manifestPath()).thenReturn(Path.of("build", "host-validation", "manifest.json"));
    new RuntimeApiRoutes(new io.justsearch.core.execution.TestEngineExecutors(), publisher).register(app);
    StatusRoutes.registerLifecycleRoutes(
        app,
        ctx -> ctx.json(Map.of("status", "ok")),
        ctx -> ctx.json(Map.of("status", "ok")));
    new ApiSecurityFilters(false, null, mock(EventBuffer.class), null, null).install(app);

    app.start("127.0.0.1", 0);
    port = app.port();
  }
}
