/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.Javalin;
import io.justsearch.app.api.runtime.RuntimeContract;
import io.justsearch.ui.api.RouteContractPolicy.Contract;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Builds the self-contained OpenAPI 3.1 input for {@code @justsearch/runtime-client}. */
final class SdkOpenApiProjection {
  private static final ObjectMapper MAPPER =
      JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

  private SdkOpenApiProjection() {}

  static Map<String, Object> build(Javalin app, List<ApiModule> modules) {
    return build(app, modules, RouteContractPolicy.index(RouteContractPolicy.CONTRACTS));
  }

  static Map<String, Object> build(
      Javalin app,
      List<ApiModule> modules,
      Map<String, RouteContractPolicy.Contract> contractPolicy) {
    List<RouteManifestController.RouteEntry> liveRoutes =
        RouteManifestController.build(app, modules, contractPolicy);
    List<String> liveKeys = liveRoutes.stream().map(r -> r.method() + " " + r.path()).toList();
    RouteContractPolicy.validateSdkRoutes(liveKeys, contractPolicy.values());

    Map<String, Map<String, Object>> paths = new TreeMap<>();
    Map<String, Object> schemas = new TreeMap<>();
    for (var route : liveRoutes) {
      Contract contract = contractPolicy.get(route.method() + " " + route.path());
      if (contract == null || !contract.sdkExposed()) continue;
      paths
          .computeIfAbsent(route.path(), ignored -> new LinkedHashMap<>())
          .put(route.method().toLowerCase(java.util.Locale.ROOT), operation(contract, schemas));
    }

    Map<String, Object> info = new LinkedHashMap<>();
    info.put("title", "JustSearch Runtime Client API");
    info.put("version", "0.1.0");
    info.put(
        "description",
        "The six read-only, loopback-only Runtime Contract operations supported by"
            + " @justsearch/runtime-client. This is intentionally smaller than the full local API.");

    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("openapi", "3.1.0");
    doc.put("info", info);
    doc.put(
        "x-justsearch-runtime-contract",
        Map.of("supportedVersions", List.of(RuntimeContract.CURRENT_VERSION)));
    doc.put("paths", paths);
    doc.put("components", Map.of("schemas", schemas));
    return doc;
  }

  static byte[] write(Javalin app, List<ApiModule> modules) throws IOException {
    String rendered = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(build(app, modules));
    return rendered.replace("\r\n", "\n").replace('\r', '\n').getBytes(StandardCharsets.UTF_8);
  }

  private static Map<String, Object> operation(Contract contract, Map<String, Object> schemas) {
    Map<String, Object> operation = new LinkedHashMap<>();
    operation.put("operationId", contract.sdkOperationId());
    operation.put("summary", contract.method() + " " + contract.path());
    operation.put("tags", List.of("runtime-contract"));
    operation.put("x-justsearch-stability", contract.stability().manifestValue());
    OpenApiRenderer.projectLifecycle(
        operation, RouteManifestController.LifecycleEntry.from(contract.lifecycle()));
    operation.put(
        "x-justsearch-security",
        Map.of(
            "loopbackHostRequired", contract.security().loopbackHostRequired(),
            "sessionTokenRequired", contract.security().sessionTokenRequired(),
            "mcpOriginValidated", contract.security().mcpOriginValidated()));

    Map<String, Object> responses = new LinkedHashMap<>();
    for (var response : contract.responseSchemas().entrySet()) {
      String schemaName = componentName(response.getValue());
      if (!schemas.containsKey(schemaName)) {
        loadSchema(response.getValue(), schemaName, schemas);
      }
      Map<String, Object> mediaType =
          Map.of("schema", Map.of("$ref", "#/components/schemas/" + schemaName));
      Map<String, Object> responseObject = new LinkedHashMap<>();
      responseObject.put("description", responseDescription(response.getKey()));
      responseObject.put("content", Map.of("application/json", mediaType));
      responses.put(Integer.toString(response.getKey()), responseObject);
    }
    operation.put("responses", responses);
    return operation;
  }

  private static String responseDescription(int status) {
    return switch (status) {
      case 200 -> "Successful response.";
      case 403 -> "Rejected because the Host header is not loopback.";
      case 500 -> "Sanitized internal application failure.";
      case 429 -> "Engine admission capacity refusal; see Retry-After.";
      case 503 -> "Valid lifecycle-unavailable response.";
      default -> "HTTP " + status + " response.";
    };
  }

  private static String componentName(String filename) {
    return filename.replaceAll("\\.v\\d+\\.json$", "").replace(".json", "");
  }

  @SuppressWarnings("unchecked")
  private static void loadSchema(
      String filename, String componentName, Map<String, Object> schemas) {
    String resource = "/SSOT/schemas/" + filename;
    try (InputStream input = SdkOpenApiProjection.class.getResourceAsStream(resource)) {
      if (input == null) throw new IllegalStateException("SDK schema missing from classpath: " + resource);
      Map<String, Object> schema = MAPPER.readValue(input, Map.class);
      schemas.put(componentName, schema);
      rewriteLocalRefs(schema, componentName, schemas);
      schema.remove("$schema");
      schema.remove("$id");
    } catch (IOException e) {
      throw new IllegalStateException("Failed to load SDK schema: " + resource, e);
    }
  }

  @SuppressWarnings("unchecked")
  private static void rewriteLocalRefs(
      Object value, String componentName, Map<String, Object> schemas) {
    if (value instanceof Map<?, ?> raw) {
      Map<String, Object> map = (Map<String, Object>) raw;
      Object ref = map.get("$ref");
      if (ref instanceof String text && text.startsWith("#/$defs/")) {
        map.put("$ref", "#/components/schemas/" + componentName + text.substring(1));
      } else if (ref instanceof String text && text.matches("[a-z0-9-]+\\.v\\d+\\.json")) {
        String referencedComponent = componentName(text);
        if (!schemas.containsKey(referencedComponent)) {
          loadSchema(text, referencedComponent, schemas);
        }
        map.put("$ref", "#/components/schemas/" + referencedComponent);
      }
      for (Object child : map.values()) rewriteLocalRefs(child, componentName, schemas);
    } else if (value instanceof List<?> list) {
      for (Object child : list) rewriteLocalRefs(child, componentName, schemas);
    }
  }
}
