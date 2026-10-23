package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.justsearch.app.api.EffectivePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PolicyController#handleValidatePolicy}.
 */
class PolicyControllerTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private Javalin app;
  private int port;
  private HttpClient client;

  @BeforeEach
  void setUp() {
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  }

  @AfterEach
  void tearDown() {
    if (app != null) {
      app.stop();
      app = null;
    }
  }

  private void startServer(Supplier<EffectivePolicy> snapshotSupplier) {
    PolicyController controller = new PolicyController(snapshotSupplier, null);
    app = Javalin.create(cfg -> { cfg.showJavalinBanner = false; cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()); });
    app.get("/api/policy/validate", controller::handleValidatePolicy);
    app.get("/api/policy/effective", controller::handleGetEffectivePolicy);
    app.post("/api/policy/user/allowlist/pack-manifest/add", controller::handleAddPackManifestShaToUserAllowlist);
    app.start("127.0.0.1", 0);
    port = app.port();
  }

  private JsonNode fetch(String path) throws Exception {
    HttpRequest req = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + port + path))
        .GET()
        .build();
    HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, resp.statusCode(), "Expected HTTP 200 for " + path);
    return MAPPER.readTree(resp.body());
  }

  @org.junit.jupiter.api.io.TempDir Path policyRoot;

  @Test
  void effectiveEndpointAndAllowlistAppendKeepAppUpdatesDisabled() throws Exception {
    var previous = io.justsearch.configuration.resolved.ConfigStore.globalOrNull();
    String previousGpu = System.getProperty("policy.gpu_acceleration_enabled");
    String previousExternal = System.getProperty("justsearch.policy.disallowExternalInferenceServers");
    try {
      io.justsearch.configuration.resolved.ConfigStore.setGlobal(
          new io.justsearch.configuration.resolved.ConfigStore(
              io.justsearch.configuration.resolved.TestResolvedConfigHelper.fromEntries(
                  java.util.Map.of("justsearch.home", policyRoot.toString()))));
      Path user = policyRoot.resolve("policy.v1.json");
      java.nio.file.Files.writeString(user, "{\"schemaVersion\":1,\"appUpdatesEnabled\":false}");
      var service = new io.justsearch.app.services.policy.EnterprisePolicyServiceImpl();
      startServer(service::snapshot);
      JsonNode before = fetch("/api/policy/effective");
      assertTrue(before.has("appUpdatesEnabled"), "effective policy must expose the app-update permission");
      assertFalse(before.path("appUpdatesEnabled").asBoolean(true));
      HttpRequest append = HttpRequest.newBuilder()
          .uri(URI.create("http://127.0.0.1:" + port + "/api/policy/user/allowlist/pack-manifest/add"))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString("{\"manifestSha256\":\"" + "a".repeat(64) + "\"}"))
          .build();
      assertEquals(200, client.send(append, HttpResponse.BodyHandlers.ofString()).statusCode());
      assertFalse(MAPPER.readTree(java.nio.file.Files.readString(user)).path("appUpdatesEnabled").asBoolean(true));
      assertFalse(fetch("/api/policy/effective").path("appUpdatesEnabled").asBoolean(true));
    } finally {
      io.justsearch.configuration.resolved.TestResolvedConfigHelper.restoreGlobal(previous);
      if (previousGpu == null) System.clearProperty("policy.gpu_acceleration_enabled");
      else System.setProperty("policy.gpu_acceleration_enabled", previousGpu);
      if (previousExternal == null) System.clearProperty("justsearch.policy.disallowExternalInferenceServers");
      else System.setProperty("justsearch.policy.disallowExternalInferenceServers", previousExternal);
    }
  }

  @Test
  void validateReturnsValidWhenNoPoliciesPresent() throws Exception {
    // No policy files: machine not present, user not present
    EffectivePolicy policy = new EffectivePolicy(
        true,
        true, true, true, false,
        List.of(), List.of(), "none", false,
        new EffectivePolicy.PolicySource(Path.of("/nonexistent/machine"), false, false, null, null),
        new EffectivePolicy.PolicySource(Path.of("/nonexistent/user"), false, false, null, null),
        false
    );
    startServer(() -> policy);

    JsonNode json = fetch("/api/policy/validate");

    assertTrue(json.get("valid").asBoolean(), "Should be valid when no policies present");
    assertFalse(json.get("aiDisabledDueToPolicy").asBoolean(), "AI should not be disabled");
    assertFalse(json.path("machine").path("present").asBoolean());
    assertFalse(json.path("user").path("present").asBoolean());
  }

  @Test
  void validateReturnsValidWhenMachinePolicyLoadedSuccessfully() throws Exception {
    // Machine policy present and loaded successfully
    EffectivePolicy policy = new EffectivePolicy(
        true,
        true, false, true, false,
        List.of(), List.of("abc123"), "machine", true,
        new EffectivePolicy.PolicySource(Path.of("C:/ProgramData/JustSearch/policy.v1.json"), true, true, null, null),
        new EffectivePolicy.PolicySource(Path.of("/user/policy"), false, false, null, null),
        false
    );
    startServer(() -> policy);

    JsonNode json = fetch("/api/policy/validate");

    assertTrue(json.get("valid").asBoolean(), "Should be valid when machine policy loads");
    assertFalse(json.get("aiDisabledDueToPolicy").asBoolean(), "AI should not be disabled");
    assertTrue(json.path("machine").path("present").asBoolean());
    assertTrue(json.path("machine").path("valid").asBoolean());
  }

  @Test
  void validateReturnsInvalidWhenMachinePolicyFailedToLoad() throws Exception {
    // Machine policy present but failed to load (fail-closed behavior)
    EffectivePolicy policy = new EffectivePolicy(
        true,
        false, false, false, true,
        List.of(), List.of(), "machine", false,
        new EffectivePolicy.PolicySource(Path.of("C:/ProgramData/JustSearch/policy.v1.json"), true, false, "Invalid JSON syntax", null),
        new EffectivePolicy.PolicySource(Path.of("/user/policy"), false, false, null, null),
        false
    );
    startServer(() -> policy);

    JsonNode json = fetch("/api/policy/validate");

    assertFalse(json.get("valid").asBoolean(), "Should be invalid when machine policy fails to load");
    assertTrue(json.get("aiDisabledDueToPolicy").asBoolean(), "AI should be disabled due to fail-closed");
    assertTrue(json.path("machine").path("present").asBoolean());
    assertFalse(json.path("machine").path("valid").asBoolean());
    assertEquals("Invalid JSON syntax", json.path("machine").path("error").asText());
  }

  @Test
  void validateReturnsInvalidWhenUserPolicyHasError() throws Exception {
    // User policy present but failed to load
    EffectivePolicy policy = new EffectivePolicy(
        true,
        true, true, true, false,
        List.of(), List.of(), "app", true,
        new EffectivePolicy.PolicySource(Path.of("/machine"), false, false, null, null),
        new EffectivePolicy.PolicySource(Path.of("/user/policy.v1.json"), true, false, "schemaVersion mismatch", null),
        false
    );
    startServer(() -> policy);

    JsonNode json = fetch("/api/policy/validate");

    assertFalse(json.get("valid").asBoolean(), "Should be invalid when user policy fails to load");
    assertFalse(json.get("aiDisabledDueToPolicy").asBoolean(), "AI not disabled for user policy errors");
    assertTrue(json.path("user").path("present").asBoolean());
    assertFalse(json.path("user").path("valid").asBoolean());
  }

  @Test
  void validateReturnsValidWhenBothPoliciesLoadedSuccessfully() throws Exception {
    // Both machine and user policies present and loaded successfully
    EffectivePolicy policy = new EffectivePolicy(
        true,
        true, false, true, false,
        List.of(), List.of("abc123"), "machine", true,
        new EffectivePolicy.PolicySource(Path.of("C:/ProgramData/JustSearch/policy.v1.json"), true, true, null, null),
        new EffectivePolicy.PolicySource(Path.of("/user/policy.v1.json"), true, true, null, null),
        false
    );
    startServer(() -> policy);

    JsonNode json = fetch("/api/policy/validate");

    assertTrue(json.get("valid").asBoolean(), "Should be valid when both policies load");
    assertFalse(json.get("aiDisabledDueToPolicy").asBoolean());
    assertTrue(json.path("machine").path("present").asBoolean());
    assertTrue(json.path("machine").path("valid").asBoolean());
    assertTrue(json.path("user").path("present").asBoolean());
    assertTrue(json.path("user").path("valid").asBoolean());
  }
}
