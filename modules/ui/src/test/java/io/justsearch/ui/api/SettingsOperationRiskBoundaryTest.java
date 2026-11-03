/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.agent.tools.AgentToolsOperationCatalog;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.engine.EngineAdmissionController;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.intent.ConsentCapsuleService;
import io.justsearch.app.services.intent.CoreIntentSourceCatalog;
import io.justsearch.app.services.intent.CoreTrustEvaluator;
import io.justsearch.app.services.intent.DurableGrantStore;
import io.justsearch.app.services.intent.PendingAuthorizationStore;
import io.justsearch.app.services.registry.executor.OperationExecutorImpl;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import io.justsearch.app.services.registry.operations.handlers.SetChatEnabledHandler;
import io.justsearch.app.services.registry.operations.handlers.IndexingRulesHandler;
import io.justsearch.app.services.registry.operations.handlers.IndexingPauseHandler;
import io.justsearch.app.services.runtimestate.RuntimeSpecStore;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.app.services.settings.SettingsCommitCoordinator;
import io.justsearch.app.services.settings.SettingsComponentComposer;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/** S3/S5: real settings handlers and dispatcher must gate outside writes before persistence. */
@Timeout(30)
final class SettingsOperationRiskBoundaryTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String TOKEN = "manifest-token";
  private static final String WINDOW = "window-secret";

  @TempDir Path temp;
  private final Clock clock = Clock.systemUTC();
  private final PendingAuthorizationStore pending =
      new PendingAuthorizationStore(clock, Duration.ofMinutes(5));
  private final ConsentCapsuleService capsules = new ConsentCapsuleService(clock, Duration.ofMinutes(5));
  private UiSettingsStore settings;
  private RuntimeSpecStore spec;
  private final HttpClient client = HttpClient.newHttpClient();
  private final ExecutorService slowRequests = Executors.newSingleThreadExecutor();
  private SqliteOperationStore store;
  private Path settingsFile;
  private Javalin app;

  @BeforeEach
  void start() throws Exception {
    settingsFile = temp.resolve("settings.json");
    settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, settingsFile);
    store = new SqliteOperationStore(temp.resolve("operations.db"));
    var prepared = mock(SettingsComponentComposer.Prepared.class);
    doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; })
        .when(prepared).withOwnerLocks(any());
    var components = mock(SettingsComponentComposer.class);
    when(components.prepare(any(), any(), any(), any(), any())).thenReturn(prepared);
    var owner = new SettingsCommitCoordinator(settings,
        new io.justsearch.configuration.resolved.ConfigStore(
            io.justsearch.app.services.config.ConfigStoreRebuilder.prepare(new UiSettings())),
        () -> { throw new AssertionError("Unexpected restart"); },
        candidate -> OperationResult.success("Committed"), () -> false, components);
    var runner = new OperationAttemptRunnerImpl(store, clock,
        Set.of(OperationKind.SETTINGS_APPLY, OperationKind.RECONFIGURE), owner);
    spec = new RuntimeSpecStore(settings, runner);
    var handlers = new HandlerRegistry();
    var reconciler = mock(RuntimeReconciler.class);
    handlers.register(CoreOperationCatalog.SET_CHAT_ENABLED,
        new SetChatEnabledHandler(() -> spec, () -> reconciler));
    handlers.register(CoreOperationCatalog.SET_INDEXING_RULES, new IndexingRulesHandler(() -> spec));
    handlers.register(CoreOperationCatalog.PAUSE_INDEXING, new IndexingPauseHandler(() -> spec, true));
    handlers.register(CoreOperationCatalog.RESUME_INDEXING, new IndexingPauseHandler(() -> spec, false));
    var catalogs = List.<io.justsearch.agent.api.registry.OperationCatalog>of(
        new CoreOperationCatalog(), new AgentToolsOperationCatalog());
    var dispatcher = new OperationExecutorImpl(
        runner, new EngineAdmissionController(4, 4, 4),
        handlers, null, Map.of(), clock, new CoreTrustEvaluator(), CoreIntentSourceCatalog.catalog(),
        null, capsules);
    var operations = new OperationsController(catalogs, dispatcher, clock, pending);
    var approvals = new AuthorizationController(capsules, pending,
        new DurableGrantStore(clock, temp.resolve("durable-grants.json")), dispatcher, catalogs);
    app = Javalin.create(c -> {
      c.showJavalinBanner = false;
      c.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    });
    WindowAuthority.install(app, WINDOW);
    new ApiSecurityFilters(true, TOKEN, new EventBuffer(), slowRequests, null).install(app);
    app.post("/api/operations/{id}/invoke", operations::handleInvoke);
    app.post("/api/authorizations/approve", approvals::handleApprove);
    app.start("127.0.0.1", 0);
  }

  @AfterEach
  void stop() throws Exception {
    client.close();
    if (app != null) app.stop();
    if (store != null) store.close();
    slowRequests.shutdownNow();
  }

  private HttpResponse<String> send(String path, Object body, String window, String... headers)
      throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
        .timeout(Duration.ofSeconds(10)).header("X-JustSearch-Session", TOKEN)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    if (window != null) request.header("X-JustSearch-Window", window);
    if (headers.length > 0) request.headers(headers);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static Map<String, Object> arguments(String id) {
    return switch (id) {
      case "core.set-chat-enabled" -> Map.of("enabled", true);
      case "core.set-indexing-rules" -> {
        var args = new java.util.HashMap<String, Object>();
        args.put("schedule", null);
        args.put("pauseOnBattery", true);
        yield args;
      }
      default -> Map.of();
    };
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "s3s5OutsideSettingsWriteStaysPendingWithoutPersistence({0})")
  @org.junit.jupiter.params.provider.ValueSource(strings = {
      "core.set-chat-enabled", "core.set-indexing-rules", "core.indexing-pause", "core.indexing-resume"})
  void s3s5OutsideSettingsWriteStaysPendingWithoutPersistence(String id) throws Exception {
    // Seed a pause so resume would change persisted state too.
    assertEquals(200, send("/api/operations/core.indexing-pause/invoke",
        Map.of("args", Map.of()), WINDOW).statusCode());
    byte[] before = Files.readAllBytes(settingsFile);
    var witness = settings.inspect().witness();
    for (String transport : List.of("", "BUTTON", "RAIL", "PALETTE", "SYSTEM_INTERNAL", "MCP")) {
      String[] headers = transport.isEmpty() ? new String[0]
          : new String[] {"X-JustSearch-Transport", transport};
      var response = send("/api/operations/" + id + "/invoke", Map.of("args", arguments(id)), null, headers);
      assertEquals(428, response.statusCode(), id + " " + transport + ": " + response.body());
      var body = JSON.readTree(response.body());
      assertEquals("CONFIRMATION_REQUIRED", body.path("errorClass").asText(), response.body());
      assertTrue(pending.peek(body.path("pendingId").asText()).isPresent(), response.body());
      assertArrayEquals(before, Files.readAllBytes(settingsFile));
      assertEquals(witness, settings.inspect().witness());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "n1WindowSettingsClickPersistsWithoutPrompt({0})")
  @org.junit.jupiter.params.provider.ValueSource(strings = {"", "BUTTON", "RAIL", "PALETTE"})
  void n1WindowSettingsClickPersistsWithoutPrompt(String transport) throws Exception {
    String[] headers = transport.isEmpty() ? new String[0]
        : new String[] {"X-JustSearch-Transport", transport};
    for (String id : List.of("core.set-chat-enabled", "core.set-indexing-rules",
        "core.indexing-pause", "core.indexing-resume")) {
      var witness = settings.inspect().witness();
      var response = send("/api/operations/" + id + "/invoke", Map.of("args", arguments(id)), WINDOW, headers);
      assertEquals(200, response.statusCode(), id + ": " + response.body());
      var body = JSON.readTree(response.body());
      assertTrue(body.path("success").asBoolean(), response.body());
      assertFalse(body.has("pendingId"), response.body());
      assertTrue(settings.inspect().witness().acceptedRevision() > witness.acceptedRevision(), id);
    }
    var reloaded = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, settingsFile).load();
    assertTrue(reloaded.getChatEnabled());
    assertFalse(reloaded.getIndexingPause().paused());
    assertTrue(reloaded.getIndexingPause().pauseOnBattery());
  }
}
