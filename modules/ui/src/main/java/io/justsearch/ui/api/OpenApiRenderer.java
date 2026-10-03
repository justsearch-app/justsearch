/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.justsearch.ui.api.RouteManifestController.RouteEntry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/* Pure, deterministic route-entry to OpenAPI 3.1 projection. */
final class OpenApiRenderer {
  static final String CLASSIFICATION = "reference-client-structural-inventory";
  private static final String OPENAPI_VERSION = "3.1.0";
  private static final String API_VERSION = "1.0";
  private static final Pattern PARAM = Pattern.compile("\\{([^}]+)\\}");
  private OpenApiRenderer() {}

  /* Render an ordered document without consulting Javalin or any runtime singleton. */
  static Map<String, Object> render(List<RouteEntry> routeEntries) {
    List<RouteEntry> routes =
        routeEntries.stream().sorted(RouteDescriptorDigest.ROUTE_ORDER).toList();

    Map<String, Object> info = new LinkedHashMap<>();
    info.put("title", "JustSearch Local API — reference-client structural inventory");
    info.put("version", API_VERSION);
    info.put(
        "description",
        String.join(
            " ",
            "Structural inventory of the loopback-only Head HTTP surface.",
            "It mixes reference-client and internal routes; it is not the narrowly versioned JustSearch Runtime Contract.",
            "Request bodies and long-tail response schemas are intentionally incomplete."));

    Map<String, Map<String, Object>> paths = new LinkedHashMap<>();
    Map<String, Object> schemas = new TreeMap<>();
    var operationIdentities = new HashSet<String>();
    for (RouteEntry route : routes) {
      String openApiPath = normalizePath(route.path());
      String operationIdentity = route.method().toLowerCase(Locale.ROOT) + " " + openApiPath;
      if (!operationIdentities.add(operationIdentity)) {
        throw new IllegalArgumentException(
            "duplicate route after OpenAPI path normalization: " + operationIdentity);
      }
      Map<String, Object> pathItem =
          paths.computeIfAbsent(openApiPath, ignored -> new LinkedHashMap<>());
      pathItem.put(route.method().toLowerCase(Locale.ROOT), operation(route, openApiPath, schemas));
    }

    Map<String, Object> source = new LinkedHashMap<>();
    source.put("routeCount", routes.size());
    source.put("routeDigest", RouteDescriptorDigest.sha256(routes));

    Map<String, Object> surface = new LinkedHashMap<>();
    surface.put("classification", CLASSIFICATION);
    surface.put("runtimeContract", false);
    surface.put("schemaScope", "structural-partial");

    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("openapi", OPENAPI_VERSION);
    doc.put("info", info);
    doc.put("x-justsearch-surface", surface);
    doc.put("x-justsearch-route-source", source);
    doc.put("paths", paths);
    if (!schemas.isEmpty()) {
      doc.put("components", Map.of("schemas", schemas));
    }
    return doc;
  }

  private static Map<String, Object> operation(
      RouteEntry route, String openApiPath, Map<String, Object> schemas) {
    Map<String, Object> operation = new LinkedHashMap<>();
    operation.put("summary", route.method() + " " + route.path());
    operation.put("tags", List.of(route.cohort()));
    if (route.sdkOperationId() != null) {
      operation.put("operationId", route.sdkOperationId());
    }
    if (route.stability() != null) {
      operation.put("x-justsearch-stability", route.stability());
    }
    projectLifecycle(operation, route.lifecycle());
    if (route.owningModule() != null) {
      operation.put("x-owning-module", route.owningModule());
    }
    if (!route.requiredCapabilities().isEmpty()) {
      operation.put("x-required-capabilities", route.requiredCapabilities());
    }
    List<Map<String, Object>> parameters = pathParameters(openApiPath);
    if (!parameters.isEmpty()) {
      operation.put("parameters", parameters);
    }

    Map<String, Object> responses = new LinkedHashMap<>();
    if (route.responseSchemas() == null) {
      projectResponse(responses, schemas, 200, route.responseSchema());
    } else {
      route.responseSchemas()
          .forEach((status, schema) -> projectResponse(responses, schemas, status, schema));
    }
    operation.put("responses", responses);
    return operation;
  }

  private static void projectResponse(
      Map<String, Object> responses,
      Map<String, Object> schemas,
      int status,
      String schema) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put(
        "description", status >= 200 && status < 300 ? "Successful response." : "Error response.");
    if (schema != null) {
      String componentName = schema.replaceAll("\\.v\\d+\\.json$", "").replace(".json", "");
      schemas.putIfAbsent(componentName, Map.of("$ref", "/api/schemas/" + schema));
      response.put(
          "content",
          Map.of(
              "application/json",
              Map.of("schema", Map.of("$ref", "#/components/schemas/" + componentName))));
    }
    responses.put(Integer.toString(status), response);
  }

  static void projectLifecycle(
      Map<String, Object> operation, RouteManifestController.LifecycleEntry lifecycle) {
    if (lifecycle == null) return;
    operation.put("deprecated", true);
    operation.put("externalDocs", Map.of("url", lifecycle.documentationUri()));
    operation.put("x-deprecated-since", lifecycle.deprecatedSince());
    if (lifecycle.sunsetAt() != null) {
      operation.put("x-sunset", lifecycle.sunsetAt());
    }
    operation.put("x-justsearch-replacement", lifecycle.replacement());
  }

  private static List<Map<String, Object>> pathParameters(String openApiPath) {
    List<Map<String, Object>> parameters = new ArrayList<>();
    Matcher matcher = PARAM.matcher(openApiPath);
    while (matcher.find()) {
      Map<String, Object> parameter = new LinkedHashMap<>();
      parameter.put("name", matcher.group(1));
      parameter.put("in", "path");
      parameter.put("required", true);
      parameter.put("schema", Map.of("type", "string"));
      parameters.add(parameter);
    }
    return parameters;
  }

  static String normalizePath(String javalinPath) {
    String path = javalinPath.replace('<', '{').replace('>', '}');
    if (path.endsWith("/*")) {
      path = path.substring(0, path.length() - 1) + "{wildcard}";
    }
    return path.replace("*", "{wildcard}");
  }

}
