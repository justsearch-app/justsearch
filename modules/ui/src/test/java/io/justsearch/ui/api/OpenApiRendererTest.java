package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.javalin.Javalin;
import io.justsearch.ui.api.RouteManifestController.RouteEntry;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies the pure route-entry to classified OpenAPI projection. */
@DisplayName("OpenApiRenderer")
class OpenApiRendererTest {

  @SuppressWarnings("unchecked")
  private static Map<String, Object> operation(
      Map<String, Object> document, String path, String method) {
    Map<String, Object> paths = (Map<String, Object>) document.get("paths");
    assertNotNull(paths, "document has paths");
    Map<String, Object> item = (Map<String, Object>) paths.get(path);
    assertNotNull(item, "path present: " + path);
    Map<String, Object> operation = (Map<String, Object>) item.get(method);
    assertNotNull(operation, "operation present: " + method + " " + path);
    return operation;
  }

  @Test
  @DisplayName("render() classifies the inventory and preserves route metadata")
  void rendersClassifiedStructuralInventory() {
    List<RouteEntry> routes =
        List.of(
            route("POST", "/api/knowledge/search", "knowledge", null, List.of("WORKER"),
                "knowledge-search-response.v1.json"),
            route("GET", "/api/knowledge/status", "knowledge", null, List.of(), null),
            route("POST", "/api/chat/agent", "agent", "AgentApiModule",
                List.of("WORKER", "INFERENCE"), null),
            route("GET", "/api/chat/agent/history/{batchId}", "agent", null, List.of(), null));

    Map<String, Object> document = OpenApiRenderer.render(routes);

    assertEquals("3.1.0", document.get("openapi"));
    @SuppressWarnings("unchecked")
    Map<String, Object> surface = (Map<String, Object>) document.get("x-justsearch-surface");
    assertEquals(OpenApiRenderer.CLASSIFICATION, surface.get("classification"));
    assertEquals(Boolean.FALSE, surface.get("runtimeContract"));
    assertEquals("structural-partial", surface.get("schemaScope"));

    @SuppressWarnings("unchecked")
    Map<String, Object> source = (Map<String, Object>) document.get("x-justsearch-route-source");
    assertEquals(4, source.get("routeCount"));
    assertTrue(source.get("routeDigest").toString().matches("sha256:[0-9a-f]{64}"));

    Map<String, Object> search = operation(document, "/api/knowledge/search", "post");
    assertEquals(List.of("knowledge"), search.get("tags"));
    assertEquals(List.of("WORKER"), search.get("x-required-capabilities"));
    assertEquals(
        "AgentApiModule",
        operation(document, "/api/chat/agent", "post").get("x-owning-module"));
    assertFalse(
        operation(document, "/api/knowledge/status", "get")
            .containsKey("x-required-capabilities"));

    @SuppressWarnings("unchecked")
    Map<String, Object> responses = (Map<String, Object>) search.get("responses");
    @SuppressWarnings("unchecked")
    Map<String, Object> ok = (Map<String, Object>) responses.get("200");
    @SuppressWarnings("unchecked")
    Map<String, Object> content = (Map<String, Object>) ok.get("content");
    @SuppressWarnings("unchecked")
    Map<String, Object> json = (Map<String, Object>) content.get("application/json");
    @SuppressWarnings("unchecked")
    Map<String, Object> schema = (Map<String, Object>) json.get("schema");
    assertEquals("#/components/schemas/knowledge-search-response", schema.get("$ref"));

    Map<String, Object> history =
        operation(document, "/api/chat/agent/history/{batchId}", "get");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> parameters =
        (List<Map<String, Object>>) history.get("parameters");
    assertEquals("batchId", parameters.getFirst().get("name"));
    assertEquals(Boolean.TRUE, parameters.getFirst().get("required"));
  }

  @Test
  @DisplayName("route ordering and digest are deterministic")
  void orderingAndDigestAreDeterministic() {
    RouteEntry first = route("GET", "/api/a", "a", null, List.of(), null);
    RouteEntry second = route("POST", "/api/z", "z", null, List.of("WORKER"), null);

    Map<String, Object> forward = OpenApiRenderer.render(List.of(first, second));
    Map<String, Object> reverse = OpenApiRenderer.render(List.of(second, first));
    assertEquals(forward, reverse);
    assertEquals(
        RouteDescriptorDigest.sha256(List.of(first, second)),
        RouteDescriptorDigest.sha256(List.of(second, first)));

    RouteEntry changed = route("POST", "/api/z", "z", null, List.of(), null);
    assertNotEquals(
        RouteDescriptorDigest.sha256(List.of(first, second)),
        RouteDescriptorDigest.sha256(List.of(first, changed)));
  }

  @Test
  @DisplayName("every descriptor field contributes to the shared digest")
  void completeDescriptorContributesToDigest() {
    var query = new RouteContractPolicy.QueryParameter("limit", false, "integer.v1.json");
    var lifecycle =
        new RouteManifestController.LifecycleEntry(
            "2026-01-01T00:00:00Z",
            "2026-05-01T00:00:00Z",
            "GET /replacement",
            "https://docs.justsearch.example/deprecations/a");
    RouteEntry baseline =
        new RouteEntry(
            "GET",
            "/api/a",
            "cohort",
            "Owner",
            List.of("WORKER"),
            "result.v1.json",
            "public-contract",
            "getA",
            "request.v1.json",
            List.of(query),
            Map.of(200, "result.v1.json"),
            lifecycle);
    String digest = RouteDescriptorDigest.sha256(List.of(baseline));

    assertNotEquals(
        digest,
        RouteDescriptorDigest.sha256(
            List.of(route("GET", "/api/a", "other", "Owner", List.of("WORKER"), "result.v1.json"))));
    assertNotEquals(
        digest,
        RouteDescriptorDigest.sha256(
            List.of(route("GET", "/api/a", "cohort", "Other", List.of("WORKER"), "result.v1.json"))));
    assertNotEquals(
        digest,
        RouteDescriptorDigest.sha256(
            List.of(route("GET", "/api/a", "cohort", "Owner", List.of(), "result.v1.json"))));
    assertNotEquals(
        digest,
        RouteDescriptorDigest.sha256(
            List.of(route("GET", "/api/a", "cohort", "Owner", List.of("WORKER"), null))));
    assertNotEquals(digest, RouteDescriptorDigest.sha256(List.of(withStability(baseline, "reference-client"))));
    assertNotEquals(digest, RouteDescriptorDigest.sha256(List.of(withSdkOperationId(baseline, "getOther"))));
    assertNotEquals(digest, RouteDescriptorDigest.sha256(List.of(withRequestSchema(baseline, "other.v1.json"))));
    assertNotEquals(
        digest,
        RouteDescriptorDigest.sha256(
            List.of(withQueryParameters(baseline, List.of(new RouteContractPolicy.QueryParameter("limit", true, "integer.v1.json"))))));
    assertNotEquals(
        digest,
        RouteDescriptorDigest.sha256(
            List.of(withResponseSchemas(baseline, Map.of(503, "error.v1.json")))));
    assertNotEquals(
        digest,
        RouteDescriptorDigest.sha256(
            List.of(
                withLifecycle(
                    baseline,
                    new RouteManifestController.LifecycleEntry(
                        lifecycle.deprecatedSince(),
                        lifecycle.sunsetAt(),
                        "GET /other",
                        lifecycle.documentationUri())))));
  }

  @Test
  @DisplayName("lifecycle metadata projects across the route manifest and both OpenAPI documents")
  void projectsLifecycleMetadata() {
    Javalin app = Javalin.create(config -> config.showJavalinBanner = false);
    app.get("/fake/{id}", context -> {});
    RouteContractPolicy.Contract contract =
        new RouteContractPolicy.Contract(
            "GET",
            "/fake/{id}",
            RouteContractPolicy.Stability.PUBLIC_CONTRACT,
            "getFake",
            null,
            List.of(),
            Map.of(200, "runtime-live-response.v1.json"),
            ApiSecurityFilters.contractSecurity("GET", "/fake/{id}"),
            new RouteContractPolicy.Lifecycle(
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-05-01T00:00:00Z"),
                "GET /replacement",
                URI.create("https://docs.justsearch.example/deprecations/fake")),
            null);
    Map<String, RouteContractPolicy.Contract> policy =
        RouteContractPolicy.index(List.of(contract));

    RouteEntry route = RouteManifestController.build(app, List.of(), policy).getFirst();
    assertEquals("public-contract", route.stability());
    assertEquals("getFake", route.sdkOperationId());
    assertEquals("2026-01-01T00:00:00Z", route.lifecycle().deprecatedSince());

    assertLifecycleProjection(
        operation(OpenApiRenderer.render(List.of(route)), "/fake/{id}", "get"));
    assertLifecycleProjection(
        operation(SdkOpenApiProjection.build(app, List.of(), policy), "/fake/{id}", "get"));
  }

  @Test
  @DisplayName("declared response statuses project from the live route contract policy")
  void projectsDeclaredResponseStatuses() {
    Javalin app = Javalin.create(config -> config.showJavalinBanner = false);
    io.javalin.http.Handler noOp = context -> {};
    io.justsearch.ui.api.routes.InferenceRoutes.register(
        app, noOp, noOp, noOp, noOp, noOp, noOp, noOp, noOp, noOp);

    Map<String, Object> document =
        OpenApiRenderer.render(RouteManifestController.build(app, List.of()));

    Map<String, Object> recovery =
        operation(document, "/api/engine/components/{name}/recover", "post");
    assertEquals(
        List.of("202", "404", "409", "429", "503"),
        responseStatuses(recovery));
    assertEquals(
        "#/components/schemas/component-recovery-response",
        responseSchemaReference(recovery, "202"));
    assertEquals(
        "#/components/schemas/api-error-response",
        responseSchemaReference(recovery, "503"));

    Map<String, Object> retired = operation(document, "/api/worker/restart", "post");
    assertEquals(List.of("410"), responseStatuses(retired));
    assertEquals(
        "#/components/schemas/api-error-response",
        responseSchemaReference(retired, "410"));
  }

  @Test
  @DisplayName("Javalin wildcard paths normalize to OpenAPI path parameters")
  void normalizesWildcards() {
    assertEquals("/{wildcard}", OpenApiRenderer.normalizePath("/*"));
    assertEquals("/assets/{path}", OpenApiRenderer.normalizePath("/assets/<path>"));
  }

  @Test
  @DisplayName("normalized duplicate operations fail instead of silently overwriting")
  void rejectsNormalizedDuplicates() {
    RouteEntry braces = route("GET", "/assets/{path}", "asset", null, List.of(), null);
    RouteEntry angles = route("GET", "/assets/<path>", "asset", null, List.of(), null);

    assertThrows(
        IllegalArgumentException.class, () -> OpenApiRenderer.render(List.of(braces, angles)));
  }

  private static RouteEntry route(
      String method,
      String path,
      String cohort,
      String owner,
      List<String> capabilities,
      String responseSchema) {
    return new RouteEntry(
        method,
        path,
        cohort,
        owner,
        capabilities,
        responseSchema,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static RouteEntry withStability(RouteEntry route, String stability) {
    return copy(route, stability, route.sdkOperationId(), route.requestSchema(), route.queryParameters(), route.responseSchemas(), route.lifecycle());
  }

  private static RouteEntry withSdkOperationId(RouteEntry route, String operationId) {
    return copy(route, route.stability(), operationId, route.requestSchema(), route.queryParameters(), route.responseSchemas(), route.lifecycle());
  }

  private static RouteEntry withRequestSchema(RouteEntry route, String requestSchema) {
    return copy(route, route.stability(), route.sdkOperationId(), requestSchema, route.queryParameters(), route.responseSchemas(), route.lifecycle());
  }

  private static RouteEntry withQueryParameters(
      RouteEntry route, List<RouteContractPolicy.QueryParameter> queryParameters) {
    return copy(route, route.stability(), route.sdkOperationId(), route.requestSchema(), queryParameters, route.responseSchemas(), route.lifecycle());
  }

  private static RouteEntry withResponseSchemas(
      RouteEntry route, Map<Integer, String> responseSchemas) {
    return copy(route, route.stability(), route.sdkOperationId(), route.requestSchema(), route.queryParameters(), responseSchemas, route.lifecycle());
  }

  private static RouteEntry withLifecycle(
      RouteEntry route, RouteManifestController.LifecycleEntry lifecycle) {
    return copy(route, route.stability(), route.sdkOperationId(), route.requestSchema(), route.queryParameters(), route.responseSchemas(), lifecycle);
  }

  private static RouteEntry copy(
      RouteEntry route,
      String stability,
      String sdkOperationId,
      String requestSchema,
      List<RouteContractPolicy.QueryParameter> queryParameters,
      Map<Integer, String> responseSchemas,
      RouteManifestController.LifecycleEntry lifecycle) {
    return new RouteEntry(
        route.method(),
        route.path(),
        route.cohort(),
        route.owningModule(),
        route.requiredCapabilities(),
        route.responseSchema(),
        stability,
        sdkOperationId,
        requestSchema,
        queryParameters,
        responseSchemas,
        lifecycle);
  }

  @SuppressWarnings("unchecked")
  private static List<String> responseStatuses(Map<String, Object> operation) {
    return List.copyOf(((Map<String, Object>) operation.get("responses")).keySet());
  }

  @SuppressWarnings("unchecked")
  private static String responseSchemaReference(Map<String, Object> operation, String status) {
    Map<String, Object> responses = (Map<String, Object>) operation.get("responses");
    Map<String, Object> response = (Map<String, Object>) responses.get(status);
    Map<String, Object> content = (Map<String, Object>) response.get("content");
    Map<String, Object> json = (Map<String, Object>) content.get("application/json");
    Map<String, Object> schema = (Map<String, Object>) json.get("schema");
    return (String) schema.get("$ref");
  }

  @SuppressWarnings("unchecked")
  private static void assertLifecycleProjection(Map<String, Object> operation) {
    assertEquals(Boolean.TRUE, operation.get("deprecated"));
    assertEquals("2026-01-01T00:00:00Z", operation.get("x-deprecated-since"));
    assertEquals("2026-05-01T00:00:00Z", operation.get("x-sunset"));
    assertEquals("GET /replacement", operation.get("x-justsearch-replacement"));
    Map<String, Object> externalDocs = (Map<String, Object>) operation.get("externalDocs");
    assertEquals("https://docs.justsearch.example/deprecations/fake", externalDocs.get("url"));
  }
}
