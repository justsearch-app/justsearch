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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MCP Streamable-HTTP Origin validation — the spec's transport-security MUST: "Servers MUST validate
 * the {@code Origin} header on all incoming connections to prevent DNS rebinding attacks... If the
 * {@code Origin} header is present and invalid, servers MUST respond with HTTP 403 Forbidden"
 * (Transports §Streamable HTTP, Security Warning).
 *
 * <p>The live half runs the REAL {@link ApiSecurityFilters#install} wiring — not a re-declared
 * mirror — so a guard registered on the wrong path, or dropped from {@code install()}, fails here.
 */
@DisplayName("MCP endpoint Origin validation (Streamable HTTP transport security)")
class McpOriginValidationTest {

  private HttpClient client;
  private Javalin app;
  private ExecutorService executor;
  private int port;
  /** Mirrors LocalApiServer's inflight counter: incremented in a before-hook, decremented after. */
  private final AtomicInteger inflight = new AtomicInteger();
  private final AtomicInteger afterHandlerRuns = new AtomicInteger();

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

  // ---- pure predicate ----

  @Test
  @DisplayName("Absent Origin is allowed — native MCP hosts are not browsers and send none")
  void allowsAbsentOrigin() {
    assertTrue(ApiSecurityFilters.isAllowedMcpOrigin(null));
    assertTrue(ApiSecurityFilters.isAllowedMcpOrigin(""));
    assertTrue(ApiSecurityFilters.isAllowedMcpOrigin("   "));
  }

  @Test
  @DisplayName("Loopback origins are allowed: 127.0.0.1 / localhost / ::1, any port, http or https")
  void allowsLoopbackOrigins() {
    assertTrue(ApiSecurityFilters.isAllowedMcpOrigin("http://127.0.0.1"));
    assertTrue(ApiSecurityFilters.isAllowedMcpOrigin("http://127.0.0.1:8080"));
    assertTrue(ApiSecurityFilters.isAllowedMcpOrigin("http://localhost"));
    assertTrue(ApiSecurityFilters.isAllowedMcpOrigin("http://localhost:5173"));
    assertTrue(ApiSecurityFilters.isAllowedMcpOrigin("https://localhost:5173"));
    assertTrue(ApiSecurityFilters.isAllowedMcpOrigin("HTTP://LOCALHOST:5173"));
    assertTrue(ApiSecurityFilters.isAllowedMcpOrigin("http://[::1]:8080"));
    assertTrue(ApiSecurityFilters.isAllowedMcpOrigin("  http://127.0.0.1:8080  "));
  }

  @Test
  @DisplayName("Desktop-shell origins are allowed (tauri://localhost, https://tauri.localhost)")
  void allowsDesktopShellOrigins() {
    assertTrue(ApiSecurityFilters.isAllowedMcpOrigin("tauri://localhost"));
    assertTrue(ApiSecurityFilters.isAllowedMcpOrigin("https://tauri.localhost"));
    assertTrue(ApiSecurityFilters.isAllowedMcpOrigin("http://tauri.localhost"));
  }

  @Test
  @DisplayName("Foreign web origins are rejected")
  void rejectsForeignOrigins() {
    assertFalse(ApiSecurityFilters.isAllowedMcpOrigin("http://evil.example.com"));
    assertFalse(ApiSecurityFilters.isAllowedMcpOrigin("https://evil.example.com:8080"));
    assertFalse(ApiSecurityFilters.isAllowedMcpOrigin("http://169.254.169.254"));
  }

  @Test
  @DisplayName("The opaque `null` origin is rejected — it carries no host that can be verified")
  void rejectsNullOrigin() {
    assertFalse(ApiSecurityFilters.isAllowedMcpOrigin("null"));
    assertFalse(ApiSecurityFilters.isAllowedMcpOrigin("NULL"));
  }

  /**
   * The property that makes the check sound: the Origin is parsed as a URI and its HOST COMPONENT is
   * compared for equality. A substring/prefix/suffix match would admit every host below — each
   * contains a loopback name but resolves to a foreign authority.
   */
  @Test
  @DisplayName("Loopback LOOKALIKE origins are rejected (host equality, never substring matching)")
  void rejectsLoopbackLookalikes() {
    assertFalse(ApiSecurityFilters.isAllowedMcpOrigin("http://127.0.0.1.evil.com"));
    assertFalse(ApiSecurityFilters.isAllowedMcpOrigin("http://localhost.evil.com"));
    assertFalse(ApiSecurityFilters.isAllowedMcpOrigin("http://evil-localhost.com"));
    assertFalse(ApiSecurityFilters.isAllowedMcpOrigin("http://tauri.localhost.evil.com"));
    // userinfo, not host
    assertFalse(ApiSecurityFilters.isAllowedMcpOrigin("http://127.0.0.1@evil.com"));
    // path / fragment, not host
    assertFalse(ApiSecurityFilters.isAllowedMcpOrigin("http://evil.com/127.0.0.1"));
    assertFalse(ApiSecurityFilters.isAllowedMcpOrigin("http://evil.com#http://localhost"));
  }

  @Test
  @DisplayName("Unparseable values and non-web schemes are rejected")
  void rejectsMalformedAndNonWebSchemes() {
    assertFalse(ApiSecurityFilters.isAllowedMcpOrigin("localhost"));
    assertFalse(ApiSecurityFilters.isAllowedMcpOrigin("not a uri"));
    assertFalse(ApiSecurityFilters.isAllowedMcpOrigin("file:///C:/tmp"));
    assertFalse(ApiSecurityFilters.isAllowedMcpOrigin("javascript:alert(1)"));
    assertFalse(ApiSecurityFilters.isAllowedMcpOrigin("tauri://evil.com"));
  }

  // ---- live filter, installed exactly as production installs it ----

  @Test
  @DisplayName("Live: POST /mcp with no Origin is served (native MCP host)")
  void livePostWithoutOriginIsServed() throws Exception {
    startServerWithProductionFilters();
    HttpResponse<String> resp = post(null);
    assertEquals(200, resp.statusCode());
  }

  @Test
  @DisplayName("Live: POST /mcp from a loopback Origin is served")
  void livePostFromLoopbackOriginIsServed() throws Exception {
    startServerWithProductionFilters();
    assertEquals(200, post("http://127.0.0.1:5173").statusCode());
    assertEquals(200, post("http://localhost").statusCode());
  }

  @Test
  @DisplayName("Live: POST /mcp from a foreign Origin is 403 with a JSON-RPC error body")
  void livePostFromForeignOriginIsForbidden() throws Exception {
    startServerWithProductionFilters();
    HttpResponse<String> resp = post("http://evil.example.com");
    assertEquals(403, resp.statusCode());
    assertTrue(resp.body().contains("\"jsonrpc\":\"2.0\""), "Body should be JSON-RPC shaped: " + resp.body());
    assertTrue(resp.body().contains("\"id\":null"), "JSON-RPC error must carry no request id: " + resp.body());
    assertTrue(resp.body().contains("MCP_ORIGIN_FORBIDDEN"), "Body should name the error code: " + resp.body());
    assertFalse(resp.body().contains("\"handled\""), "The handler must not have run");
  }

  /**
   * The deny path must halt the handler chain WITHOUT discarding the already-queued after-tasks:
   * in production those close the OTel {@code Scope} on a pooled Jetty thread and decrement
   * {@code inflightRequests}. A halt that cleared them would leak a context per denied request and
   * strand the inflight gauge — attacker-triggerable and unbounded. Asserted together with the body
   * so a future halt-mechanism change cannot trade one for the other silently.
   */
  @Test
  @DisplayName("Live: a denied request still runs after-handlers (no scope/inflight leak) and keeps its body")
  void liveDenyRunsAfterHandlersAndKeepsBody() throws Exception {
    startServerWithProductionFilters();
    afterHandlerRuns.set(0);
    inflight.set(0);

    HttpResponse<String> resp = post("http://evil.example.com");

    assertEquals(403, resp.statusCode());
    assertEquals(1, afterHandlerRuns.get(), "after-handlers must run on the deny path");
    assertEquals(0, inflight.get(), "before/after must stay balanced — a leaked inflight never returns to 0");
    assertTrue(resp.body().contains("\"jsonrpc\":\"2.0\""), "The JSON-RPC body must survive the exception mapper: " + resp.body());
    assertTrue(resp.body().contains("MCP_ORIGIN_FORBIDDEN"), "Body should name the error code: " + resp.body());
  }

  @Test
  @DisplayName("An over-long Origin is truncated before it reaches logs and the event buffer")
  void truncatesOverlongOriginForLogging() {
    assertEquals("http://127.0.0.1", ApiSecurityFilters.truncateForLog("http://127.0.0.1"));
    String overlong = "http://" + "a".repeat(500) + ".evil.com";
    String truncated = ApiSecurityFilters.truncateForLog(overlong);
    assertTrue(truncated.length() < overlong.length(), "An over-long origin must be capped");
    assertTrue(truncated.endsWith("...[truncated]"), "Truncation must be visible: " + truncated);
  }

  @Test
  @DisplayName("Live: POST /mcp from a loopback-lookalike Origin is 403")
  void livePostFromLookalikeOriginIsForbidden() throws Exception {
    startServerWithProductionFilters();
    assertEquals(403, post("http://127.0.0.1.evil.com").statusCode());
  }

  @Test
  @DisplayName("Live: DELETE /mcp is validated too — the check covers all methods on the endpoint")
  void liveDeleteIsValidated() throws Exception {
    startServerWithProductionFilters();
    assertEquals(403, delete("http://evil.example.com").statusCode());
    assertEquals(204, delete(null).statusCode());
    assertEquals(204, delete("http://127.0.0.1:5173").statusCode());
  }

  @Test
  @DisplayName("Live: the MCP guard is endpoint-scoped — other routes keep browser-enforced CORS")
  void liveGuardDoesNotLeakToOtherRoutes() throws Exception {
    startServerWithProductionFilters();
    HttpResponse<String> resp =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/status"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .header("Origin", "http://evil.example.com")
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, resp.statusCode(), "Only /mcp carries the MCP transport-security check");
    assertTrue(
        resp.headers().firstValue("Access-Control-Allow-Origin").isEmpty(),
        "A foreign origin still gets no CORS grant");
  }

  // ---- GET /mcp: Streamable-HTTP conformance, and its interaction with the Origin guard ----

  /**
   * The spec requires the single endpoint to support GET, answering with either an SSE stream or
   * 405. JustSearch offers no stream there, so 405 + {@code Allow} is the conformant answer; before
   * this it 404'd, which tells a probing client nothing. The {@code Allow} header is asserted
   * verbatim because a 405 without it is not a well-formed method-rejection (RFC 9110 §15.5.6 makes
   * {@code Allow} mandatory on 405).
   */
  @Test
  @DisplayName("Live: GET /mcp with no Origin is 405 + Allow — the spec's answer for a server with no SSE stream")
  void liveGetWithoutOriginIsMethodNotAllowed() throws Exception {
    startServerWithProductionFilters();
    HttpResponse<String> resp = get(LocalApiServer.MCP_ENDPOINT_PATH, null);

    assertEquals(405, resp.statusCode(), "Not 404: the endpoint exists and must say so");
    assertEquals(
        "POST, DELETE, OPTIONS",
        resp.headers().firstValue("Allow").orElse(null),
        "405 MUST carry Allow naming the methods this endpoint does serve");
    assertTrue(resp.body().contains("\"jsonrpc\":\"2.0\""), "Body should be JSON-RPC shaped: " + resp.body());
    assertTrue(resp.body().contains("\"id\":null"), "JSON-RPC error must carry no request id: " + resp.body());
    assertTrue(resp.body().contains("MCP_METHOD_NOT_ALLOWED"), "Body should name the error code: " + resp.body());
  }

  @Test
  @DisplayName("Live: GET /mcp from a loopback Origin is 405 + Allow — the guard admits it, the method rejects it")
  void liveGetFromLoopbackOriginIsMethodNotAllowed() throws Exception {
    startServerWithProductionFilters();
    HttpResponse<String> resp = get(LocalApiServer.MCP_ENDPOINT_PATH, "http://127.0.0.1:5173");

    assertEquals(405, resp.statusCode());
    assertEquals("POST, DELETE, OPTIONS", resp.headers().firstValue("Allow").orElse(null));
  }

  /**
   * Ordering matters: the Origin guard is a before-filter, so a foreign caller must be told 403
   * (unauthorized) and never 405 (which would confirm the endpoint's method surface to an origin
   * that has no business learning it). Asserting the absent {@code Allow} header is what
   * distinguishes "the guard ran first" from "the guard happens to also 403 after the handler".
   */
  @Test
  @DisplayName("Live: GET /mcp from a foreign Origin is 403 — the guard wins over the 405")
  void liveGetFromForeignOriginIsForbiddenNotMethodNotAllowed() throws Exception {
    startServerWithProductionFilters();
    HttpResponse<String> resp = get(LocalApiServer.MCP_ENDPOINT_PATH, "http://evil.example.com");

    assertEquals(403, resp.statusCode(), "The Origin guard must pre-empt the 405");
    assertTrue(
        resp.headers().firstValue("Allow").isEmpty(),
        "A rejected origin must not learn the endpoint's method surface");
    assertTrue(resp.body().contains("MCP_ORIGIN_FORBIDDEN"), "Body should name the guard's error code: " + resp.body());
    assertFalse(resp.body().contains("MCP_METHOD_NOT_ALLOWED"), "The 405 handler must not have run");
  }

  @Test
  @DisplayName("Removed token endpoint is absent for every Origin")
  void removedTokenEndpointIsAbsentForEveryOrigin() throws Exception {
    startServerWithProductionFilters();
    for (String origin : new String[] {null, "http://127.0.0.1:5173", "tauri://localhost", "http://evil.example.com"}) {
      var response = get("/api/mcp/token", origin);
      assertEquals(404, response.statusCode());
      assertFalse(response.body().contains("test-token"));
    }
  }

  private HttpResponse<String> get(String path, String origin) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(3))
            .header(LocalApiServer.SESSION_TOKEN_HEADER, "test-token")
            .GET();
    if (origin != null) {
      builder.header("Origin", origin);
    }
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String origin) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
            .timeout(Duration.ofSeconds(3))
            .header("Content-Type", "application/json")
            .header(LocalApiServer.SESSION_TOKEN_HEADER, "test-token")
            .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"));
    if (origin != null) {
      builder.header("Origin", origin);
    }
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> delete(String origin) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
            .timeout(Duration.ofSeconds(3))
            .header(LocalApiServer.SESSION_TOKEN_HEADER, "test-token")
            .DELETE();
    if (origin != null) {
      builder.header("Origin", origin);
    }
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  /**
   * Starts a hermetic app that mirrors the production wiring order in {@code LocalApiServer}: the
   * {@code HttpResponseException} mapper that preserves a handler-set body while propagating the
   * status (`LocalApiServer.java:397-399`), then the paired before/after lifecycle hooks (inflight
   * counter + OTel scope, registered at `:489-550` BEFORE `securityFilters.install`), then the
   * production {@link ApiSecurityFilters#install} (production token, no lease service, no
   * capability gates), then stand-ins for the two methods registered at {@code /mcp}.
   *
   * <p>The mapper and the hook ordering are load-bearing, not decoration: without the mapper the
   * app 403s with Javalin's default envelope instead of the body the filter wrote, and the
   * before/after pairing is what a halt mechanism that discarded queued after-tasks would break.
   */
  private void startServerWithProductionFilters() {
    app =
        Javalin.create(
            cfg -> {
              cfg.showJavalinBanner = false;
              cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
            });

    app.exception(io.javalin.http.HttpResponseException.class, (e, ctx) -> ctx.status(e.getStatus()));

    app.before(ctx -> inflight.incrementAndGet());
    app.after(
        ctx -> {
          afterHandlerRuns.incrementAndGet();
          inflight.decrementAndGet();
        });

    executor = Executors.newSingleThreadExecutor();
    new ApiSecurityFilters(true, "test-token", new EventBuffer(), executor, null).install(app);

    app.post(LocalApiServer.MCP_ENDPOINT_PATH, ctx -> ctx.json(Map.of("handled", true)));
    app.delete(LocalApiServer.MCP_ENDPOINT_PATH, ctx -> ctx.status(204));
    // The PRODUCTION 405 handler, not a stand-in: it is static precisely so the conformance
    // assertions below bind the shipped code path (see McpProtocolHandler#handleGet).
    app.get(
        LocalApiServer.MCP_ENDPOINT_PATH,
        io.justsearch.ui.api.mcp.McpProtocolHandler::handleGet);
    app.get("/api/status", ctx -> ctx.json(Map.of("status", "ok")));

    app.start("127.0.0.1", 0);
    port = app.port();
  }
}
