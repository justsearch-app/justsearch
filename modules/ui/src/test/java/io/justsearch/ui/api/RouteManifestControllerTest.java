package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.javalin.Javalin;
import io.justsearch.ui.api.RouteManifestController.RouteEntry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 583 §D.3a — verifies the route manifest enumerates routes and tags each with the correct
 * cohort + required-capability, and that the capability tags come from the SAME authority
 * ({@link RouteCapabilityPolicy}) that {@link ApiSecurityFilters} enforces with.
 */
@DisplayName("RouteManifestController + RouteCapabilityPolicy")
class RouteManifestControllerTest {

  private static RouteEntry find(List<RouteEntry> routes, String method, String path) {
    return routes.stream()
        .filter(r -> r.method().equals(method) && r.path().equals(path))
        .findFirst()
        .orElseThrow(() -> new AssertionError("route not in manifest: " + method + " " + path));
  }

  @Test
  @DisplayName("build() enumerates routes with correct cohort + capability tags")
  void buildTagsCohortAndCapability() {
    Javalin app = Javalin.create(cfg -> cfg.showJavalinBanner = false);
    app.post("/api/knowledge/search", ctx -> {});
    app.get("/api/knowledge/status", ctx -> {});
    app.post("/api/indexing/reindex", ctx -> {});
    app.post("/api/chat/agent", ctx -> {});
    app.get("/api/status", ctx -> {});
    app.get("/api/registry/operations", ctx -> {});

    // A module that claims ownership of the knowledge search route (owning-module dimension, §D.3a).
    ApiModule knowledgeModule =
        new ApiModule() {
          @Override
          public void register(Javalin a) {}

          @Override
          public java.util.Set<String> ownedRoutePaths() {
            return java.util.Set.of("/api/knowledge/search");
          }

          @Override
          public String moduleName() {
            return "TestKnowledgeModule";
          }
        };

    List<RouteEntry> routes = RouteManifestController.build(app, List.of(knowledgeModule));

    // Worker-gated non-GET → tagged WORKER, cohort knowledge.
    RouteEntry search = find(routes, "POST", "/api/knowledge/search");
    assertEquals("knowledge", search.cohort());
    assertEquals(List.of("WORKER"), search.requiredCapabilities());
    // Owning-module from the module's own ownedRoutePaths() (single-source attribution).
    assertEquals("TestKnowledgeModule", search.owningModule());
    // Response schema from the declarative RouteContractPolicy (§D.3a schema dimension).
    assertEquals("knowledge-search-response.v1.json", search.responseSchema());
    assertEquals("reference-client", search.stability());
    assertEquals(null, search.sdkOperationId());
    assertEquals(null, search.lifecycle());

    // GET under a get-exempt rule → no required capability.
    RouteEntry status = find(routes, "GET", "/api/knowledge/status");
    assertEquals("knowledge", status.cohort());
    assertEquals(List.of(), status.requiredCapabilities());
    // Not owned by any module, no declared schema → both null.
    assertEquals(null, status.owningModule());
    assertEquals(null, status.responseSchema());

    // Indexing non-GET → WORKER.
    assertEquals(List.of("WORKER"), find(routes, "POST", "/api/indexing/reindex").requiredCapabilities());

    // Agent → WORKER then INFERENCE (precedence order preserved).
    RouteEntry agent = find(routes, "POST", "/api/chat/agent");
    assertEquals("agent", agent.cohort());
    assertEquals(List.of("WORKER", "INFERENCE"), agent.requiredCapabilities());

    // Ungated routes → empty caps.
    assertEquals(List.of(), find(routes, "GET", "/api/status").requiredCapabilities());
    assertEquals("registry", find(routes, "GET", "/api/registry/operations").cohort());

    // Manifest is sorted by (cohort, path, method) and non-empty.
    assertTrue(routes.size() >= 6, "manifest should contain all registered routes");

    Map<String, Object> envelope = RouteManifestController.envelope(routes);
    assertEquals(routes.size(), envelope.get("count"));
    assertEquals(RouteDescriptorDigest.sha256(routes), envelope.get("routeDigest"));
  }

  @Test
  @DisplayName("RouteCapabilityPolicy.requiredFor honors get-exemption + precedence")
  void capabilityPolicyRequiredFor() {
    assertEquals(
        List.of(RouteCapabilityPolicy.Capability.WORKER),
        RouteCapabilityPolicy.requiredFor("POST", "/api/knowledge/search"));
    assertEquals(List.of(), RouteCapabilityPolicy.requiredFor("GET", "/api/knowledge/status"));
    assertEquals(
        List.of(
            RouteCapabilityPolicy.Capability.WORKER, RouteCapabilityPolicy.Capability.INFERENCE),
        RouteCapabilityPolicy.requiredFor("POST", "/api/chat/agent"));
    assertEquals(List.of(), RouteCapabilityPolicy.requiredFor("GET", "/api/status"));
  }

  @Test
  void retiredInferenceReloadRoutesAreAbsentFromTheRegisteredSurface() {
    Javalin app = Javalin.create(cfg -> cfg.showJavalinBanner = false);
    io.javalin.http.Handler noOp = ctx -> {};
    io.justsearch.ui.api.routes.InferenceRoutes.register(app, noOp, noOp, noOp, noOp,
        noOp, noOp, noOp, noOp);
    io.justsearch.ui.api.routes.DebugRoutes.register(app,
        org.mockito.Mockito.mock(DebugStateController.class),
        org.mockito.Mockito.mock(EffectiveConfigController.class),
        org.mockito.Mockito.mock(ChunkInfoController.class), noOp,
        org.mockito.Mockito.mock(LogLevelController.class),
        org.mockito.Mockito.mock(TimeSeriesController.class),
        org.mockito.Mockito.mock(SessionPoliciesController.class), noOp, noOp);

    List<RouteEntry> routes = RouteManifestController.build(app, List.of());

    assertFalse(routes.stream().anyMatch(route ->
        route.path().equals("/api/inference/reload")
            || route.path().equals("/api/admin/inference/reload")));
  }
}
