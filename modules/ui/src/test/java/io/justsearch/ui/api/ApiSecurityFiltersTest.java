/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;
import io.justsearch.core.context.EngineContext;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.javalin.Javalin;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 884 item 23 — the local API's session-token enforcement FAILS CLOSED.
 *
 * <p><strong>The defect.</strong> {@code setupSessionTokenEnforcement} used to treat (prod mode, no
 * token) as a configuration to warn about and continue from: it logged a token-enforcement-disabled
 * warning and installed no filter, so the shipped API served every mutating request to any local
 * process. That is fail-open on the one control that gates mutation — loopback-only (Hard Invariant
 * #2) is not a trust boundary, which is precisely why the token exists.
 *
 * <p><strong>Why this drives the REAL constructor and the REAL {@link
 * ApiSecurityFilters#install}.</strong> A double would restate the guard, and a restated guard is
 * green in exactly the world where production dropped it — the failure mode this whole change is
 * about. So {@link #prodModeWithoutSessionTokenRefusesToConstruct} calls the production constructor, and
 * {@link #tokenFilterIsActuallyInstalled} drives a live Javalin through {@code install}. The
 * constructor cases alone would still pass if {@code install()} stopped wiring the filter at all;
 * the live case alone would still pass if the refusal were deleted. Both directions are needed.
 */
@DisplayName("ApiSecurityFilters — fail-closed session-token enforcement (884 item 23)")
class ApiSecurityFiltersTest {

  private static final String TEST_TOKEN = "test-session-token-abc123";

  private ExecutorService executor;
  private HttpClient client;
  private Javalin app;
  private int port;

  @BeforeEach
  void setup() {
    executor = Executors.newSingleThreadExecutor();
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  }

  @AfterEach
  void teardown() {
    if (app != null) {
      app.stop();
      app = null;
    }
    executor.shutdownNow();
  }

  @Test
  @DisplayName("prod mode with no session token refuses to construct, naming the reason code")
  void prodModeWithoutSessionTokenRefusesToConstruct() {
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> new ApiSecurityFilters(true, null, new EventBuffer(), executor, null));

    assertTrue(
        thrown.getMessage().contains(LifecycleReasonCode.LOCAL_API_SESSION_TOKEN_MISSING.code()),
        "the refusal must carry the wire reason code so the fatal log line is diagnosable: "
            + thrown.getMessage());
  }

  @Test
  @DisplayName("a BLANK token is refused too, not just a null one")
  void prodWithBlankTokenIsAlsoRefused() {
    // The production guard is isBlank(), not != null. A null-only test would stay green if someone
    // dropped the blank half — and an empty token enforces nothing while looking configured.
    for (String token : new String[] {null, "", "   "}) {
      IllegalStateException thrown =
          assertThrows(
              IllegalStateException.class,
              () -> new ApiSecurityFilters(true, token, new EventBuffer(), executor, null),
              "prod mode must refuse token=" + (token == null ? "null" : "\"" + token + "\""));
      assertTrue(
          thrown.getMessage().contains(LifecycleReasonCode.LOCAL_API_SESSION_TOKEN_MISSING.code()));
    }
  }

  @Test
  @DisplayName("precision: dev mode without a token still constructs, and prod WITH one does too")
  void refusalIsScopedToProdModeWithoutAToken() {
    // Without this the suite would still pass if the implementation refused unconditionally — which
    // would break every dev launch and every prod launch alike. This is what makes the green above
    // mean "refused for the right reason".
    assertDoesNotThrow(
        () -> new ApiSecurityFilters(false, null, new EventBuffer(), executor, null),
        "dev mode has no token by design (HeadlessApp mints one only when justsearch.prod=true)");
    assertDoesNotThrow(
        () -> new ApiSecurityFilters(false, "", new EventBuffer(), executor, null));
    assertDoesNotThrow(
        () -> new ApiSecurityFilters(true, TEST_TOKEN, new EventBuffer(), executor, null),
        "the shipped configuration must still construct");
  }

  @Test
  @DisplayName("the token filter is actually INSTALLED: untokened POST 401s, tokened POST 200s")
  void tokenFilterIsActuallyInstalled() throws Exception {
    startWithRealFilters();

    HttpResponse<String> denied = post("/api/settings/v2", null);
    assertEquals(401, denied.statusCode(), "an untokened mutation must be refused");
    assertTrue(denied.body().contains("UI_TOKEN_REQUIRED"), denied.body());

    HttpResponse<String> allowed = post("/api/settings/v2", TEST_TOKEN);
    assertEquals(200, allowed.statusCode(), "the legitimate UI must still be able to mutate");
  }

  @Test
  @DisplayName("the eval document-ID POST inherits production session-token enforcement")
  void evalDocumentIdRouteRequiresSessionToken() throws Exception {
    startWithRealFilters();
    String body = "{\"offset\":0,\"limit\":50000}";

    HttpResponse<String> denied = post(PreviewController.DOCUMENT_IDS_PATH, null, body);
    assertEquals(401, denied.statusCode(), "private document IDs must not be anonymously readable");
    assertTrue(denied.body().contains("UI_TOKEN_REQUIRED"), denied.body());

    HttpResponse<String> allowed = post(PreviewController.DOCUMENT_IDS_PATH, TEST_TOKEN, body);
    assertEquals(200, allowed.statusCode(), allowed.body());
    assertTrue(allowed.body().contains("nested/b.txt"), allowed.body());
  }

  private HttpResponse<String> post(String path, String token) throws Exception {
    return post(path, token, "{\"ui\":{}}");
  }

  private HttpResponse<String> post(String path, String token, String body) throws Exception {
    HttpRequest.Builder req =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(3))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json")
            .header("Origin", "tauri://localhost");
    if (token != null) {
      req = req.header(LocalApiServer.SESSION_TOKEN_HEADER, token);
    }
    return client.send(req.build(), HttpResponse.BodyHandlers.ofString());
  }

  /** Prod mode with a token, and the PRODUCTION filter chain — not a re-statement of it. */
  private void startWithRealFilters() {
    app =
        Javalin.create(
            cfg -> {
              cfg.showJavalinBanner = false;
              cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
            });
    app.exception(
        io.javalin.http.HttpResponseException.class,
        (e, ctx) -> {
          // Body and status already set by the filter; mirrors LocalApiServer.
        });

    new ApiSecurityFilters(true, TEST_TOKEN, new EventBuffer(), executor, null).install(app);

    app.post("/api/settings/v2", ctx -> ctx.json(Map.of("success", true)));
    DocumentService documentService =
        new DocumentService() {
          @Override
          public CompletableFuture<DocumentRecord> fetch(String docId, EngineContext engineContext) {
            return CompletableFuture.completedFuture(null);
          }

          @Override
          public CompletableFuture<DocumentIdPage> listAllDocumentIds(
              int offset, int limit, EngineContext engineContext) {
            return CompletableFuture.completedFuture(
                new DocumentIdPage(
                    List.of("C:/root/a.txt", "C:/root/nested/b.txt"), 2, 1));
          }
        };
    PreviewController previewController = new PreviewController(documentService);
    app.post(
        PreviewController.DOCUMENT_IDS_PATH, previewController::handleListDocumentIds);

    app.start("127.0.0.1", 0);
    port = app.port();
  }
}
