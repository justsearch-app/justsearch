/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.justsearch.agent.api.AgentService;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.GateBehavior;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.agent.tools.AgentToolsOperationCatalog;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.services.intent.ConsentCapsuleService;
import io.justsearch.app.services.intent.DurableGrantStore;
import io.justsearch.app.services.intent.PendingAuthorizationStore;
import io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode;
import io.justsearch.app.services.settings.PluginAllowlistStore;
import io.justsearch.core.execution.TestEngineExecutors;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/** Owner scenarios S1-S5/S8: the manifest token authenticates a caller, never a window gesture. */
final class WindowAuthorityBoundaryTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String TOKEN = "manifest-token";
  private static final String WINDOW = "window-secret";
  @TempDir Path temp;
  private final Clock clock = Clock.systemUTC();
  private final PendingAuthorizationStore pending = new PendingAuthorizationStore(clock, Duration.ofMinutes(5));
  private final ConsentCapsuleService capsules = new ConsentCapsuleService(clock, Duration.ofMinutes(5));
  private final OperationDispatcher dispatcher = mock(OperationDispatcher.class);
  private final IndexingService indexing = mock(IndexingService.class);
  private final AgentService agent = mock(AgentService.class);
  private final OperationDispatcher settingsDispatcher = mock(OperationDispatcher.class);
  private final io.justsearch.app.api.SettingsService settingsService =
      mock(io.justsearch.app.api.SettingsService.class);
  private static final io.justsearch.agent.api.registry.Operation RECONFIGURE =
      new io.justsearch.app.services.registry.operations.CoreOperationCatalog().findById(
          io.justsearch.app.services.registry.operations.CoreOperationCatalog.RECONFIGURE).orElseThrow();
  private final HttpClient client = HttpClient.newHttpClient();
  private final ExecutorService slowRequests = Executors.newSingleThreadExecutor();
  private final TestEngineExecutors executors = new TestEngineExecutors();
  private DurableGrantStore grants;
  private PluginVerificationController plugins;
  private AgentController agents;
  private Javalin app;

  @BeforeEach
  void start() throws Exception {
    grants = new DurableGrantStore(clock, temp.resolve("durable-grants.json"));
    plugins = new PluginVerificationController(new PluginAllowlistStore(PersistenceMode.READ_WRITE,
        temp.resolve("plugin-allowlist.json")));
    var approvals = new AuthorizationController(capsules, pending, grants, dispatcher,
        List.of(new AgentToolsOperationCatalog()));
    agents = new AgentController(executors, () -> agent, null, null, null);
    var roots = new IndexingController(() -> indexing,
        new io.justsearch.app.services.excludes.ExcludesServiceImpl(() -> indexing), null, null);
    when(dispatcher.dispatch(any(), any(), any(), any(), any()))
        .thenReturn(OperationResult.success("done", Map.of()));
    app = Javalin.create(c -> {
      c.showJavalinBanner = false;
      c.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    });
    // Only compatibility scaffolding for fail-before: the base has no window-boundary installer.
    // Its controllers still execute normally, so it fails assertions on authority, not compilation.
    try {
      Class.forName("io.justsearch.ui.api.WindowAuthority")
          .getDeclaredMethod("install", Javalin.class, String.class).invoke(null, app, WINDOW);
    } catch (ClassNotFoundException beforeBoundaryExisted) {
      // Base revision: install no replacement policy.
    }
    new ApiSecurityFilters(true, TOKEN, new EventBuffer(), slowRequests, null).install(app);
    app.post("/api/authorizations/approve", approvals::handleApprove);
    app.post("/api/authorizations/grants", approvals::handleGrant);
    app.get("/api/authorizations/grants", approvals::handleListGrants);
    app.delete("/api/authorizations/grants", approvals::handleRevokeGrant);
    app.post("/api/indexing/roots", roots::handleAddRoot);
    app.post("/api/chat/approve", agents::handleApprove);
    app.post("/api/chat/agent/autonomy", agents::handleAutonomy);
    app.post("/api/plugins/allowlist", plugins::handleApprove);
    app.post("/api/settings/v2", new SettingsController(null, temp, null, settingsService,
        settingsDispatcher, RECONFIGURE)::handleUpdateSettingsV2);
    app.post("/api/operations/test/invoke", ctx -> {
      var context = RequestEngineContext.get(ctx);
      var transport = TransportTag.valueOf(context.transport());
      var gate = new io.justsearch.app.services.intent.IntentGateEvaluator(
          new io.justsearch.app.services.intent.CoreTrustEvaluator(),
          io.justsearch.app.services.intent.CoreIntentSourceCatalog.catalog());
      ctx.json(Map.of("transport", context.transport(), "sourceTier", context.sourceTier(),
          "mediumGate", gate.evaluate(RiskTier.MEDIUM, transport).gateBehavior().name(),
          "highGate", gate.evaluate(RiskTier.HIGH, transport).gateBehavior().name()));
    });
    app.start("127.0.0.1", 0);
  }

  @AfterEach
  void stop() {
    if (app != null) app.stop();
    if (agents != null) agents.shutdown();
    executors.close();
    slowRequests.shutdownNow();
  }

  private HttpResponse<String> send(String method, String path, Object body, String window,
      String... extraHeaders) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
        .timeout(Duration.ofSeconds(5)).header("X-JustSearch-Session", TOKEN)
        .header("Content-Type", "application/json")
        .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    if (window != null) request.header("X-JustSearch-Window", window);
    if (extraHeaders.length > 0) request.headers(extraHeaders);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private String newPending(String owner) {
    var context = TestRequestContexts.mcp(owner);
    return pending.create("core.ingest-files", "{\"paths\":[\"/corpus\"]}",
        SourceTier.UNTRUSTED, RiskTier.MEDIUM, GateBehavior.TYPED_CONFIRM, "approve",
        context, TestRequestContexts.provenance(context, ExecutorTag.AGENT));
  }

  @Test
  void s1ManifestCallerCannotConsumeExecuteOrPersistApproval() throws Exception {
    for (String owner : List.of("same-caller", "another-caller")) {
      for (boolean execute : List.of(false, true)) {
        for (boolean always : List.of(false, true)) {
          String id = newPending(owner);
          var body = Map.of("pendingId", id, "execute", execute, "allowAlways", always);
          for (String bad : List.of("", "wrong-window")) {
            var refused = send("POST", "/api/authorizations/approve", body,
                bad.isEmpty() ? null : bad);
            assertEquals(403, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("WINDOW_CREDENTIAL_REQUIRED"));
            assertTrue(pending.peek(id).isPresent(), "refusal must leave it approvable");
            assertTrue(grants.snapshot().isEmpty());
          }
          verifyNoInteractions(dispatcher);
          var approved = send("POST", "/api/authorizations/approve", body, WINDOW);
          assertEquals(200, approved.statusCode(), approved.body());
          assertTrue(pending.peek(id).isEmpty());
          assertEquals(410, send("POST", "/api/authorizations/approve", body, WINDOW).statusCode());
          reset(dispatcher);
          when(dispatcher.dispatch(any(), any(), any(), any(), any()))
              .thenReturn(OperationResult.success("done", Map.of()));
          // Keep each denial independent of an earlier app's allow-always decision.
          for (var grant : grants.snapshot()) {
            grants.revoke(grant.target(), grant.sourceTier());
          }
        }
      }
    }
  }

  @Test
  void s2OnlyWindowCreatesOperationOrFamilyGrantsAndOldFilesSurvive() throws Exception {
    grants.grantAllowAlways("existing", SourceTier.UNTRUSTED);
    byte[] before = Files.readAllBytes(temp.resolve("durable-grants.json"));
    for (String kind : List.of("OPERATION", "FAMILY")) {
      var body = Map.of("kind", kind, "target", "outside", "sourceTier", "UNTRUSTED");
      assertEquals(403, send("POST", "/api/authorizations/grants", body, null).statusCode());
      assertArrayEquals(before, Files.readAllBytes(temp.resolve("durable-grants.json")));
      assertEquals(1, new DurableGrantStore(clock, temp.resolve("durable-grants.json")).snapshot().size());
    }
    assertEquals(200, send("POST", "/api/authorizations/grants", Map.of(
        "kind", "FAMILY", "target", "files", "sourceTier", "UNTRUSTED"), WINDOW).statusCode());
    assertEquals(2, grants.snapshot().size());
  }

  @Test
  void s3MissingUnknownAndForgedTrustedTransportsStayUntrusted() throws Exception {
    for (String transport : List.of("", " ", "unknown", "BUTTON", "RAIL", "PALETTE", "SYSTEM_INTERNAL")) {
      for (String kind : List.of("WEBVIEW", "INTERNAL", "MCP_CLIENT")) {
        String[] headers = transport.isEmpty() ? new String[] {"X-JustSearch-Client-Kind", kind}
            : new String[] {"X-JustSearch-Client-Kind", kind, "X-JustSearch-Transport", transport};
        var response = send("POST", "/api/operations/test/invoke", Map.of(), null, headers);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("UNTRUSTED", JSON.readTree(response.body()).path("sourceTier").asText());
        assertEquals("HTTP_API", JSON.readTree(response.body()).path("transport").asText());
        assertEquals("TYPED_CONFIRM", JSON.readTree(response.body()).path("mediumGate").asText());
      }
    }
    for (String trusted : List.of("BUTTON", "RAIL", "PALETTE")) {
      var response = send("POST", "/api/operations/test/invoke", Map.of(), WINDOW,
          "X-JustSearch-Transport", trusted);
      assertEquals(trusted, JSON.readTree(response.body()).path("transport").asText());
      assertEquals("TRUSTED", JSON.readTree(response.body()).path("sourceTier").asText());
      assertEquals("AUTO", JSON.readTree(response.body()).path("mediumGate").asText());
      assertEquals("TYPED_CONFIRM", JSON.readTree(response.body()).path("highGate").asText());
    }
    for (String weaker : List.of("MCP", "LLM_EMISSION", "AGENT_LOOP", "WORKFLOW", "URL_DEEPLINK")) {
      var response = send("POST", "/api/operations/test/invoke", Map.of(), WINDOW,
          "X-JustSearch-Transport", weaker);
      assertEquals(weaker, JSON.readTree(response.body()).path("transport").asText());
    }
  }

  @Test
  void s3SettingsWriteWithoutPendingFlowIsRefusedClearlyAndWritesNothing() throws Exception {
    // The dispatcher applies the real lattice to the transport the front classified.
    var gate = new io.justsearch.app.services.intent.IntentGateEvaluator(
        new io.justsearch.app.services.intent.CoreTrustEvaluator(),
        io.justsearch.app.services.intent.CoreIntentSourceCatalog.catalog());
    when(settingsDispatcher.dispatch(eq(RECONFIGURE), anyString(), any(), any(), any(), any()))
        .thenAnswer(call -> {
          var provenance = (io.justsearch.agent.api.registry.InvocationProvenance) call.getArgument(2);
          var decision = gate.evaluate(RiskTier.MEDIUM, provenance.transport());
          if (decision.gateBehavior() != GateBehavior.AUTO) {
            throw new io.justsearch.agent.api.registry.ConfirmationRequiredException(RECONFIGURE.id(),
                decision.gateBehavior(), RECONFIGURE.policy().confirm(), decision.sourceTier());
          }
          return OperationResult.success("Settings committed", Map.of("state", "COMPLETE"));
        });
    String key = io.justsearch.app.api.operations.OperationKeys.generate(clock);
    var witness = new java.util.HashMap<String, Object>();
    witness.put("acceptedRevision", 0);
    witness.put("lastCommittedOperationKey", null);
    var body = Map.of("operationKey", key, "witness", witness);
    for (String transport : List.of("", "BUTTON", "SYSTEM_INTERNAL")) {
      String[] headers = transport.isEmpty() ? new String[0]
          : new String[] {"X-JustSearch-Transport", transport};
      var refused = send("POST", "/api/settings/v2", body, null, headers);
      assertEquals(403, refused.statusCode(), refused.body());
      assertTrue(refused.body().contains("WINDOW_CREDENTIAL_REQUIRED"), refused.body());
    }
    verifyNoInteractions(settingsService);
    assertEquals(200, send("POST", "/api/settings/v2", body, WINDOW).statusCode());
  }

  @Test
  void s6DeepLinksFromTheWindowKeepUrlProvenanceAndStillPrompt() throws Exception {
    // The webview attaches its credential to every request, including ones a received link starts.
    for (String url : List.of("URL_DEEPLINK", "URL_BAR")) {
      var json = JSON.readTree(send("POST", "/api/operations/test/invoke", Map.of(), WINDOW,
          "X-JustSearch-Transport", url).body());
      assertEquals(url, json.path("transport").asText());
      assertEquals("MEDIUM", json.path("sourceTier").asText());
      assertEquals("INLINE_CONFIRM", json.path("mediumGate").asText(), "a received link is not a click");
    }
  }

  @Test
  void s4DirectRootAddIsRefusedBeforeIndexing() throws Exception {
    var body = Map.of("path", temp.toString());
    var denied = send("POST", "/api/indexing/roots", body, null);
    assertEquals(403, denied.statusCode(), denied.body());
    verifyNoInteractions(indexing);
    assertEquals(200, send("POST", "/api/indexing/roots", body, WINDOW).statusCode());
    verify(indexing).addWatchedRoot(isNull(), eq(temp.toAbsolutePath().normalize()), any());
  }

  @Test
  void s5AgentWorkflowAutonomyAndPluginTrustNeedTheWindow() throws Exception {
    var workflow = new io.justsearch.app.services.conversation.WorkflowGateRegistry();
    var gate = workflow.create("workflow");
    agents.setWorkflowGateRegistry(workflow);
    when(agent.tryApproveToolCall("session-a", "agent")).thenReturn(true);
    for (var body : List.of(Map.of("callId", "workflow"),
        Map.of("callId", "agent", "sessionId", "session-a"))) {
      assertEquals(403, send("POST", "/api/chat/approve", body, null).statusCode());
    }
    assertEquals(403, send("POST", "/api/chat/agent/autonomy", Map.of(
        "sessionId", "session-a", "level", "auto"), null).statusCode());
    verifyNoInteractions(agent);
    assertFalse(gate.isDone(), "outside approval must leave the workflow parked");
    assertEquals(200, send("POST", "/api/chat/approve", Map.of("callId", "workflow"), WINDOW).statusCode());
    assertTrue(gate.join());
    String existing = "a".repeat(64);
    plugins.addToAllowlist(existing);
    byte[] before = Files.readAllBytes(temp.resolve("plugin-allowlist.json"));
    assertEquals(403, send("POST", "/api/plugins/allowlist", Map.of(
        "artifactSha256", "b".repeat(64)), null).statusCode());
    assertArrayEquals(before, Files.readAllBytes(temp.resolve("plugin-allowlist.json")));
    var reloaded = new PluginVerificationController(new PluginAllowlistStore(PersistenceMode.READ_WRITE,
        temp.resolve("plugin-allowlist.json")));
    assertEquals(java.util.Set.of(existing), reloaded.listAllowlist());
    assertArrayEquals(before, Files.readAllBytes(temp.resolve("plugin-allowlist.json")));
  }

  @Test
  void s8NoWindowProofLeavesPendingForExpiryAndRestart() throws Exception {
    String id = newPending("disconnected-window");
    assertEquals(403, send("POST", "/api/authorizations/approve",
        Map.of("pendingId", id, "execute", true), null).statusCode());
    assertTrue(pending.peek(id).isPresent());
    verifyNoInteractions(dispatcher);
    var restarted = new PendingAuthorizationStore(clock, Duration.ofMinutes(5));
    assertTrue(restarted.peek(id).isEmpty());
  }
}
