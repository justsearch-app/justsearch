/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.execution.TestEngineExecutors;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/** S1-S5: walk the production composition, not a test's reconstruction of its security filters. */
final class ProductionWindowRouteBoundaryTest {
  private static final String TOKEN = "manifest-token";
  private static final String WINDOW = "window-secret";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> MUTATIONS = Set.of("POST", "PUT", "PATCH", "DELETE");
  // Independently pinned exceptions: widening the production allowlist must change this contract.
  private static final Set<String> TOKEN_ONLY = Set.of(
      "POST /mcp", "DELETE /mcp", "POST /api/operations/{id}/invoke",
      "POST /api/knowledge/search", "POST /api/knowledge/retrieve-context",
      "POST /api/knowledge/match-citations", "POST /api/knowledge/collection-facet",
      "POST /api/knowledge/folders", "POST /api/knowledge/folder-files",
      "POST /api/indexing-roots/preview", "POST /api/library/resolve-hash",
      "POST /api/library/path-label", "POST /api/documents/presence",
      "POST /api/debug/eval/document-ids", "POST /api/ai/packs/preflight",
      "POST /api/plugins/verify", "POST /api/document/{id}/resolve-address",
      "POST /api/chat/agent/{sessionId}/attach", "POST /api/chat/agent/{sessionId}/ag-ui",
      "POST /api/chat/runs/{runId}/observe", "DELETE /api/authorizations/grants",
      "POST /api/chat/reject");
  @TempDir Path temp;
  private ConfigStore previous;
  private TestEngineExecutors executors;
  private HeadAssembly head;
  private LocalApiServer server;
  private HttpClient client;
  private io.justsearch.app.api.EnterprisePolicyService policies;
  private io.justsearch.ui.runtime.RuntimeManifestPublisher publisher;

  @BeforeEach
  void start() throws Exception {
    startMode(true, true);
  }

  private void startMode(boolean prod, boolean window) throws Exception {
    previous = ConfigStore.globalOrNull();
    ConfigStore.setGlobal(new ConfigStore(TestResolvedConfigHelper.fromEntries(Map.of(
        EnvRegistry.API_PORT.configKey(), "0", "justsearch.prod", Boolean.toString(prod),
        "justsearch.home", temp.toString(), "justsearch.data.dir", temp.toString()))));
    executors = new TestEngineExecutors();
    var operations = mock(OperationStore.class);
    when(operations.subscribeCompletions(any())).thenReturn(() -> {});
    var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY, temp.resolve("settings.json"));
    head = spy(new HeadAssembly(operations, mock(OperationAttemptRunner.class), executors,
        mock(io.justsearch.telemetry.Telemetry.class), new io.justsearch.app.config.ConfigManagerBootstrap(),
        null, settings, io.justsearch.app.api.runtime.ManagedChildRegistry.noop(),
        new io.justsearch.app.services.lease.OperationLeaseServiceImpl(),
        mock(io.justsearch.app.api.EngineAdmissionService.class),
        io.justsearch.app.services.bootstrap.OperationAuthority.inMemory()));
    // Supply both machine-policy states through the production service injection point. The HTTP
    // controllers and filter registration are still constructed by LocalApiServer itself.
    policies = mock(io.justsearch.app.api.EnterprisePolicyService.class);
    when(policies.snapshot()).thenAnswer(call -> new io.justsearch.app.services.policy.EnterprisePolicyServiceImpl().snapshot());
    var services = spy(head.serviceOut());
    doReturn(policies).when(services).enterprisePolicy();
    doReturn(services).when(head).serviceOut();
    publisher = new io.justsearch.ui.runtime.RuntimeManifestPublisher(temp);
    server = LocalApiServer.builder(executors, settings, temp.resolve("index"))
        .HeadAssembly(head).sessionToken(TOKEN).windowCredential(window ? WINDOW : null)
        .runtimeManifestPublisher(publisher)
        .build();
    // The actual worker-ready path registers its conditional search and RAG routes as in production.
    server.lateBindKnowledgeServer(mock(io.justsearch.app.services.worker.KnowledgeServerBootstrap.class,
        RETURNS_DEEP_STUBS), null);
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void stop() throws Exception {
    try {
      if (client != null) client.close();
      if (server != null) server.stop();
      if (head != null) head.close();
      if (publisher != null) publisher.close();
      if (executors != null) executors.close();
    } finally {
      TestResolvedConfigHelper.restoreGlobal(previous);
    }
  }

  private HttpResponse<String> send(String method, String path, String body, String window)
      throws Exception {
    return send(method, path, body, window, new String[0]);
  }

  private HttpResponse<String> send(String method, String path, String body, String window, String... headers)
      throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + path))
        .timeout(Duration.ofSeconds(5)).header("X-JustSearch-Session", TOKEN)
        .header("Content-Type", "application/json")
        .method(method, HttpRequest.BodyPublishers.ofString(body));
    if (window != null) request.header("X-JustSearch-Window", window);
    if (headers.length > 0) request.headers(headers);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private void refused(String method, String path, String body) throws Exception {
    for (String window : List.of("", "wrong-window")) {
      var response = send(method, path, body, window.isEmpty() ? null : window);
      assertEquals(403, response.statusCode(), method + " " + path + ": " + response.body());
      assertEquals("WINDOW_CREDENTIAL_REQUIRED", JSON.readTree(response.body()).path("errorCode").asText(),
          method + " " + path + ": " + response.body());
    }
  }

  @Test
  void s1ToS5EveryRegisteredMutationDefaultsToWindowAuthority() throws Exception {
    var response = send("GET", "/api/meta/routes", "", null);
    assertEquals(200, response.statusCode(), response.body());
    var seen = new HashSet<String>();
    var checks = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (var route : JSON.readTree(response.body()).path("routes")) {
      String method = route.path("method").asText();
      String pattern = route.path("path").asText();
      String key = method + " " + pattern;
      seen.add(key);
      if (!MUTATIONS.contains(method) || !pattern.startsWith("/api/")) continue;
      String path = pattern.replaceAll("\\{[^}]+}", "probe").replace("*", "probe");
      if (TOKEN_ONLY.contains(key)) {
        checks.add(() -> assertFalse(send(method, path, "{}", null).body()
            .contains("WINDOW_CREDENTIAL_REQUIRED"), key));
      } else {
        checks.add(() -> refused(method, path, "{}"));
      }
    }
    assertTrue(seen.contains("POST /api/operations/{id}/invoke"), "ResourceApiModule must be assembled");
    assertTrue(seen.contains("POST /api/knowledge/retrieve-context"), "worker-ready RAG routes must be assembled");
    for (String key : TOKEN_ONLY) assertTrue(seen.contains(key), "exception has a real route: " + key);
    assertAll(checks);
  }

  private void policyRefused(boolean managed, boolean existingPolicy, String path) throws Exception {
    Path policy = temp.resolve("policy.v1.json");
    Path machine = temp.resolve("machine-policy.v1.json");
    if (managed) Files.writeString(machine, "{\"schemaVersion\":1,\"downloadsEnabled\":false}");
    if (existingPolicy) Files.writeString(policy, "{\"schemaVersion\":1}");
    var parsed = new io.justsearch.app.api.EnterprisePolicy();
    parsed.schemaVersion = 1;
    when(policies.snapshot()).thenReturn(new io.justsearch.app.api.EffectivePolicy(
        true, !managed, true, true, false, List.of(), List.of(), managed ? "machine" : "none", managed,
        new io.justsearch.app.api.EffectivePolicy.PolicySource(machine, managed, managed, null, managed ? parsed : null),
        new io.justsearch.app.api.EffectivePolicy.PolicySource(policy, existingPolicy, existingPolicy, null,
            existingPolicy ? parsed : null), false));
    clearInvocations(policies);
    byte[] before = Files.exists(policy) ? Files.readAllBytes(policy) : null;
    byte[] machineBefore = managed ? Files.readAllBytes(machine) : null;
    refused("POST", path, "{\"manifestSha256\":\"" + "a".repeat(64) + "\"}");
    if (before == null) assertFalse(Files.exists(policy));
    else assertArrayEquals(before, Files.readAllBytes(policy));
    if (managed) assertArrayEquals(machineBefore, Files.readAllBytes(machine));
    verify(policies, never()).snapshot(); // Refusal precedes even the policy controller's read.
  }

  @Test
  void s4PolicyCreateUnmanagedIsRefusedBeforeWriting() throws Exception {
    policyRefused(false, false, "/api/policy/user/create");
  }

  @Test
  void s4PolicyAllowlistUnmanagedIsRefusedBeforeWriting() throws Exception {
    policyRefused(false, true, "/api/policy/user/allowlist/pack-manifest/add");
  }

  @Test
  void s4PolicyCreateManagedIsRefusedBeforeReadingPolicy() throws Exception {
    policyRefused(true, false, "/api/policy/user/create");
  }

  @Test
  void s4PolicyAllowlistManagedIsRefusedBeforeReadingPolicy() throws Exception {
    policyRefused(true, true, "/api/policy/user/allowlist/pack-manifest/add");
  }

  @Test
  void s4AiInstallStartIsRefusedBeforeDownloads() throws Exception {
    refused("POST", "/api/ai/install/start", "{}");
  }

  @Test
  void s4AiInstallRepairIsRefusedBeforeDownloads() throws Exception {
    refused("POST", "/api/ai/install/repair", "{}");
  }

  @Test
  void s4PackImportIsRefusedBeforeStartingAnImport() throws Exception {
    refused("POST", "/api/ai/packs/import", "{\"path\":\"/outside/pack\"}");
  }

  @Test
  void s5RuntimeActivationIsRefusedBeforeActivating() throws Exception {
    refused("POST", "/api/ai/runtime/activate", "{}");
  }

  @Test
  void s1ToS5NewRoutesAndAllMutationMethodsAreClosedBeforeTheirHandler() throws Exception {
    // Add a route to the real assembled server without an endpoint-specific authorization check.
    var field = LocalApiServer.class.getDeclaredField("app");
    field.setAccessible(true);
    var app = (io.javalin.Javalin) field.get(server);
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    for (String method : MUTATIONS) {
      app.addHttpHandler(io.javalin.http.HandlerType.valueOf(method), "/api/window-boundary-probe",
          ctx -> { calls.incrementAndGet(); ctx.status(204); }, ActivityRole.USER_ACTION);
      refused(method, "/api/window-boundary-probe", "{}");
      assertEquals(0, calls.get());
      assertEquals(204, send(method, "/api/window-boundary-probe", "{}", WINDOW).statusCode());
      assertEquals(1, calls.getAndSet(0));
    }
    // Pin trailing-slash routing and case/escape rejection; none bypass the guard.
    for (var variant : Map.of("/API/window-boundary-probe", 404,
        "/%61pi/window-boundary-probe", 404, "/api/window-boundary-probe/", 204).entrySet()) {
      String path = variant.getKey();
      var window = send("POST", path, "{}", WINDOW);
      assertEquals(variant.getValue(), window.statusCode(), path + ": " + window.body());
      assertEquals(variant.getValue() == 204 ? 1 : 0, calls.getAndSet(0), path);
      var outside = send("POST", path, "{}", null);
      assertEquals(variant.getValue() == 204 ? 403 : 404, outside.statusCode(), path + ": " + outside.body());
      assertTrue(outside.body().contains(variant.getValue() == 204
          ? "WINDOW_CREDENTIAL_REQUIRED" : "NOT_FOUND"), path + ": " + outside.body());
      assertEquals(0, calls.get(), path);
    }
    // Javalin supports X-HTTP-Method-Override; the guard and router must agree on that method.
    for (String header : List.of("X-HTTP-Method-Override", "X-Method-Override", "X-HTTP-Method")) {
      boolean supported = header.equals("X-HTTP-Method-Override");
      for (String method : MUTATIONS) {
        var denied = send(method, "/api/window-boundary-probe", "{}", null, header, "GET");
        assertEquals(supported ? 404 : 403, denied.statusCode(), header + " " + method + ": " + denied.body());
        assertEquals(0, calls.get());
        var read = send("GET", "/api/window-boundary-probe", "", null, header, method);
        assertEquals(supported ? 403 : 404, read.statusCode(), header + " " + method + ": " + read.body());
        assertEquals(0, calls.get());
      }
    }
    for (String method : List.of("HEAD", "OPTIONS")) {
      for (String window : List.of("", WINDOW)) {
        var response = send(method, "/api/window-boundary-probe", "", window.isEmpty() ? null : window);
        assertEquals(method.equals("HEAD") ? 404 : 403, response.statusCode(), method + ": " + response.body());
        assertEquals(0, calls.get(), method + " must never dispatch a mutation handler");
        if (method.equals("OPTIONS")) {
          var preflight = send(method, "/api/window-boundary-probe", "", window.isEmpty() ? null : window,
              "Origin", "http://tauri.localhost", "Access-Control-Request-Method", "POST");
          assertEquals(200, preflight.statusCode(), preflight.body());
          assertEquals(0, calls.get(), "CORS preflight must never dispatch a mutation handler");
        }
      }
    }
    for (String method : List.of("GET", "HEAD")) {
      app.addHttpHandler(io.javalin.http.HandlerType.valueOf(method), "/api/window-read-probe",
          ctx -> ctx.status(204), ActivityRole.OBSERVATION);
      assertEquals(204, send(method, "/api/window-read-probe", "", null).statusCode());
    }
    var options = send("OPTIONS", "/api/window-read-probe", "", null);
    assertEquals(send("OPTIONS", "/api/window-read-probe", "", WINDOW).statusCode(), options.statusCode());
    assertFalse(options.body().contains("WINDOW_CREDENTIAL_REQUIRED"));
  }

  @Test
  void n1WindowAndHeadlessAndDevelopmentKeepTheirMutationAccess() throws Exception {
    assertEquals(204, send("POST", "/api/ui/activity", "{}", WINDOW).statusCode());
    for (boolean prod : List.of(true, false)) {
      stop();
      startMode(prod, !prod); // Even a builder-supplied credential is ignored in development.
      assertEquals(204, send("POST", "/api/ui/activity", "{}", null).statusCode());
      assertEquals(204, send("POST", "/api/ui/activity", "{}", "wrong-window").statusCode());
    }
  }
}
