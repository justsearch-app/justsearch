/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.javalin.Javalin;
import io.justsearch.app.services.ai.install.AiInstallService;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.telemetry.Telemetry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tempdoc 840 Phase 4 — the wire contract of the four control endpoints this phase adds:
 * pause/resume for an in-flight run and per-component decline/re-enable.
 *
 * <p>Sibling of {@link AiInstallApiContractTest}, and for the same reason: every refusal on this
 * family has to arrive as a TYPED body a caller can act on, not a bare status code. Two of the four
 * refusals here are the load-bearing ones — pausing while nothing runs (which would otherwise arm
 * the gate for the NEXT install) and declining a component the product cannot run without (which
 * must fail loudly rather than be dropped on the floor).
 *
 * <p>Drives the real {@code AiInstallService} against a temp AI home and a temp settings file, so
 * the decline path is asserted on what actually lands in {@code UiSettings.declinedAiPackages}
 * rather than on a mock's recollection of being called.
 */
final class AiInstallControlApiContractTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @TempDir Path aiHome;
  @TempDir Path settingsDir;

  private Javalin app;
  private UiSettingsStore settingsStore;
  private HttpClient client;
  private io.justsearch.app.observability.operations.SqliteOperationStore operations;

  @BeforeEach
  void startServer() throws Exception {
    settingsStore =
        new UiSettingsStore(
            UiSettingsStore.PersistenceMode.READ_WRITE, settingsDir.resolve("settings.json"));
    operations = new io.justsearch.app.observability.operations.SqliteOperationStore(settingsDir.resolve("operations.db"));
    var config = new io.justsearch.configuration.resolved.ConfigStore(
        io.justsearch.app.services.config.ConfigStoreRebuilder.prepare(settingsStore.load()));
    var owner = new io.justsearch.app.services.settings.SettingsCommitCoordinator(settingsStore, config,
        () -> { throw new AssertionError("Unexpected settings restart"); },
        candidate -> io.justsearch.agent.api.registry.OperationResult.success("Settings committed"));
    var runner = new io.justsearch.app.observability.operations.OperationAttemptRunnerImpl(operations,
        java.time.Clock.systemUTC(), java.util.Set.of(io.justsearch.agent.api.registry.OperationKind.SETTINGS_APPLY,
            io.justsearch.agent.api.registry.OperationKind.RECONFIGURE), owner);
    AiInstallController controller =
        new AiInstallController(
            new AiInstallService(null, settingsStore, null, null, aiHome, null,
                new io.justsearch.app.services.settings.SettingsServiceImpl(settingsStore, runner)), mock(Telemetry.class));
    app =
        Javalin.create(
                cfg -> {
                  cfg.showJavalinBanner = false;
                  cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
                })
            .post("/api/ai/install/pause", controller::handlePause)
            .post("/api/ai/install/resume", controller::handleResume)
            .post(
                "/api/ai/install/packages/{packageId}/decline", controller::handleDeclinePackage)
            .delete(
                "/api/ai/install/packages/{packageId}/decline", controller::handleAcceptPackage)
            .start(0);
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  }

  @AfterEach
  void stopServer() throws Exception {
    if (app != null) {
      app.stop();
      app = null;
    }
    if (operations != null) operations.close();
  }

  private HttpResponse<String> send(String method, String path) throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + path))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json");
    HttpRequest request =
        "DELETE".equals(method)
            ? b.DELETE().build()
            : b.POST(HttpRequest.BodyPublishers.noBody()).build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static JsonNode typedError(HttpResponse<String> resp, int status, String errorCode)
      throws Exception {
    assertEquals(status, resp.statusCode(), resp.body());
    assertFalse(
        resp.body() == null || resp.body().isBlank(), "a refusal must carry a body, not just a code");
    JsonNode json = MAPPER.readTree(resp.body());
    assertEquals(errorCode, json.path("errorCode").asString(), resp.body());
    assertTrue(json.has("error"), "error message");
    assertTrue(json.has("errorClass"), "error class");
    assertTrue(json.has("retryable"), "retryable flag");
    return json;
  }

  @Test
  @DisplayName("pause with no run in flight ⇒ 409 INSTALL_NOT_RUNNING, typed body")
  void pauseWithoutRun() throws Exception {
    typedError(send("POST", "/api/ai/install/pause"), 409, "INSTALL_NOT_RUNNING");
  }

  @Test
  @DisplayName("resume with no run in flight ⇒ 409 INSTALL_NOT_RUNNING, typed body")
  void resumeWithoutRun() throws Exception {
    typedError(send("POST", "/api/ai/install/resume"), 409, "INSTALL_NOT_RUNNING");
  }

  @Test
  @DisplayName("declining a REQUIRED component ⇒ 400 PACKAGE_NOT_DECLINABLE, and nothing is written")
  void decliningRequiredIsRefused() throws Exception {
    typedError(
        send("POST", "/api/ai/install/packages/embedding/decline"), 400, "PACKAGE_NOT_DECLINABLE");
    assertTrue(
        settingsStore.load().getDeclinedAiPackages().isEmpty(),
        "a refused decline must leave the preference untouched");
  }

  @Test
  @DisplayName("declining an INFRASTRUCTURE component ⇒ 400 PACKAGE_NOT_DECLINABLE")
  void decliningInfrastructureIsRefused() throws Exception {
    // cuda-runtime is the non-obvious one: it reads as "GPU DLLs" but also delivers the cuda12
    // llama-server this build needs for chat at all, so it is deliberately not offered as a choice.
    typedError(
        send("POST", "/api/ai/install/packages/cuda-runtime/decline"),
        400,
        "PACKAGE_NOT_DECLINABLE");
  }

  @Test
  @DisplayName("declining an unknown component ⇒ 404 PACKAGE_NOT_FOUND, not 'not declinable'")
  void decliningUnknownPackage() throws Exception {
    typedError(
        send("POST", "/api/ai/install/packages/no-such-component/decline"),
        404,
        "PACKAGE_NOT_FOUND");
  }

  @Test
  @DisplayName("decline then re-enable a declinable component ⇒ 200 each, and the preference follows")
  void declineAndReEnableRoundTrip() throws Exception {
    HttpResponse<String> declined = send("POST", "/api/ai/install/packages/reranker/decline");
    assertEquals(200, declined.statusCode(), declined.body());
    assertTrue(
        MAPPER.readTree(declined.body()).has("state"),
        "the response is the post-call AiInstallStatus, like start/cancel/repair");
    assertEquals(java.util.List.of("reranker"), settingsStore.load().getDeclinedAiPackages());
    assertEquals(1L, settingsStore.inspect().witness().acceptedRevision());

    HttpResponse<String> reEnabled = send("DELETE", "/api/ai/install/packages/reranker/decline");
    assertEquals(200, reEnabled.statusCode(), reEnabled.body());
    assertTrue(
        settingsStore.load().getDeclinedAiPackages().isEmpty(),
        "withdrawing the decline must remove the id, not merely stop adding it");
  }

  @Test
  @DisplayName("re-enabling a component that was never declinable ⇒ 200, never a necessity refusal")
  void reEnableIsNeverRefusedOnNecessity() throws Exception {
    // "Install this after all" cannot be an invalid request — only the DECLINE direction is gated.
    HttpResponse<String> resp = send("DELETE", "/api/ai/install/packages/embedding/decline");
    assertEquals(200, resp.statusCode(), resp.body());
  }

  @Test
  @DisplayName("declining twice is idempotent — the second call is a 200 with no duplicate entry")
  void decliningTwiceIsIdempotent() throws Exception {
    assertEquals(200, send("POST", "/api/ai/install/packages/chat/decline").statusCode());
    assertEquals(200, send("POST", "/api/ai/install/packages/chat/decline").statusCode());
    assertEquals(java.util.List.of("chat"), settingsStore.load().getDeclinedAiPackages());
    assertEquals(1L, settingsStore.inspect().witness().acceptedRevision());
  }
}
