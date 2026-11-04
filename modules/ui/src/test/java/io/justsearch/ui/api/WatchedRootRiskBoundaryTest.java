/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.agent.tools.AgentToolsOperationCatalog;
import io.justsearch.app.api.IndexingService;
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
import io.justsearch.app.services.registry.operations.handlers.AddWatchedRootHandler;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * Owner scenario S4 through the generic operation route: a program holding only the manifest token
 * cannot add a watched folder without a prompt in the app, while the app window's own click stays
 * automatic (N1) and approving in the app completes the outside request (N2). Real catalog, real
 * lattice, real executor, real handler; only the indexing service is a test double.
 */
@Timeout(30)
final class WatchedRootRiskBoundaryTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String TOKEN = "manifest-token";
  private static final String WINDOW = "window-secret";
  private static final String INVOKE = "/api/operations/core.add-watched-root/invoke";
  @TempDir Path temp;
  private final Clock clock = Clock.systemUTC();
  private final PendingAuthorizationStore pending =
      new PendingAuthorizationStore(clock, Duration.ofMinutes(5));
  private final ConsentCapsuleService capsules = new ConsentCapsuleService(clock, Duration.ofMinutes(5));
  private final IndexingService indexing = mock(IndexingService.class);
  private final HttpClient client = HttpClient.newHttpClient();
  private final ExecutorService slowRequests = Executors.newSingleThreadExecutor();
  private SqliteOperationStore store;
  private Path folder;
  private Javalin app;

  @BeforeEach
  void start() throws Exception {
    folder = Files.createDirectories(temp.resolve("outside-folder"));
    store = new SqliteOperationStore(temp.resolve("operations.db"));
    var handlers = new HandlerRegistry();
    handlers.register(CoreOperationCatalog.ADD_WATCHED_ROOT, new AddWatchedRootHandler(() -> indexing));
    var catalogs = List.<io.justsearch.agent.api.registry.OperationCatalog>of(
        new CoreOperationCatalog(), new AgentToolsOperationCatalog());
    var dispatcher = new OperationExecutorImpl(
        new OperationAttemptRunnerImpl(store, clock, Set.of()), new EngineAdmissionController(4, 4, 4),
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

  private Map<String, Object> addFolder() {
    return Map.of("args", Map.of("path", folder.toString()));
  }

  @Test
  void s4OutsideCallerAddingAFolderGetsAPendingAndNothingIsAdded() throws Exception {
    // Every transport an outside program could claim, including the trusted ones it may not.
    for (String transport : List.of("", "BUTTON", "PALETTE", "RAIL", "SYSTEM_INTERNAL", "MCP")) {
      String[] headers = transport.isEmpty() ? new String[0]
          : new String[] {"X-JustSearch-Transport", transport};
      var response = send(INVOKE, addFolder(), null, headers);
      assertEquals(428, response.statusCode(),
          "outside add-watched-root (" + transport + ") must wait for the app: " + response.body());
      var body = JSON.readTree(response.body());
      assertEquals("CONFIRMATION_REQUIRED", body.path("errorClass").asText(), response.body());
      assertEquals("UNTRUSTED", body.path("sourceTier").asText(), response.body());
      String pendingId = body.path("pendingId").asText();
      assertFalse(pendingId.isBlank(), response.body());
      assertTrue(pending.peek(pendingId).isPresent(), "the request waits in the app");
    }
    verifyNoInteractions(indexing);
  }

  @Test
  void n1WindowClickAddsTheFolderWithoutAPrompt() throws Exception {
    for (String transport : List.of("", "BUTTON", "PALETTE", "RAIL")) {
      String[] headers = transport.isEmpty() ? new String[0]
          : new String[] {"X-JustSearch-Transport", transport};
      var response = send(INVOKE, addFolder(), WINDOW, headers);
      assertEquals(200, response.statusCode(), response.body());
      var body = JSON.readTree(response.body());
      assertTrue(body.path("success").asBoolean(), response.body());
      assertFalse(body.has("pendingId"), "a trusted click is not prompted: " + response.body());
    }
    assertDoesNotThrow(() -> verify(indexing, times(4)).addAcceptedWatchedRoot(
        eq("default"), eq(folder.toAbsolutePath().normalize()), any(), anyString()));
  }

  @Test
  void n2ApprovingTheOutsideRequestInTheAppAddsTheFolder() throws Exception {
    var gated = send(INVOKE, addFolder(), null, "X-JustSearch-Transport", "MCP");
    assertEquals(428, gated.statusCode(), gated.body());
    String pendingId = JSON.readTree(gated.body()).path("pendingId").asText();
    verifyNoInteractions(indexing);
    var approved = send("/api/authorizations/approve",
        Map.of("pendingId", pendingId, "execute", true), WINDOW);
    assertEquals(200, approved.statusCode(), approved.body());
    assertTrue(JSON.readTree(approved.body()).path("executed").asBoolean(), approved.body());
    assertDoesNotThrow(() -> verify(indexing).addAcceptedWatchedRoot(
        eq("default"), eq(folder.toAbsolutePath().normalize()), any(), anyString()));
    assertTrue(pending.peek(pendingId).isEmpty());
  }
}
