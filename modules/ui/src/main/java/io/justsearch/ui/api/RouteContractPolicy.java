/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Machine-readable HTTP contract metadata keyed by {@code METHOD + route pattern}.
 *
 * <p>The live Javalin router remains the route authority. This policy adds only the contract facets
 * the router cannot express: stability, generated-client exposure, operation ids, declared query
 * parameters, response schemas, and the security projection derived from {@link ApiSecurityFilters}.
 * It supersedes the former one-response {@code RouteResponseSchemas} map.
 */
final class RouteContractPolicy {
  private RouteContractPolicy() {}

  enum Stability {
    INTERNAL,
    REFERENCE_CLIENT,
    PUBLIC_CONTRACT;

    String manifestValue() {
      return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
  }

  record QueryParameter(String name, boolean required, String schemaReference) {
    QueryParameter {
      requireText(name, "query parameter name");
      requireText(schemaReference, "query parameter schema reference");
    }
  }

  record Lifecycle(
      Instant deprecatedSince,
      Instant sunsetAt,
      String replacement,
      URI documentationUri) {
    Lifecycle {
      if (deprecatedSince == null) {
        throw new IllegalArgumentException("deprecatedSince is required");
      }
      requireText(replacement, "replacement");
      requireAbsoluteUri(documentationUri, "lifecycle documentation URI");
      if (sunsetAt != null && !sunsetAt.isAfter(deprecatedSince)) {
        throw new IllegalArgumentException("sunsetAt must be after deprecatedSince");
      }
    }
  }

  record PreOneException(String rationale, URI decisionDocumentUri) {
    PreOneException {
      requireText(rationale, "pre-1.0 exception rationale");
      requireAbsoluteUri(decisionDocumentUri, "pre-1.0 decision-document URI");
    }
  }

  record Contract(
      String method,
      String path,
      Stability stability,
      String sdkOperationId,
      String requestSchema,
      List<QueryParameter> queryParameters,
      Map<Integer, String> responseSchemas,
      ApiSecurityFilters.ContractSecurity security,
      Lifecycle lifecycle,
      PreOneException preOneException) {
    Contract {
      method = requireText(method, "method").toUpperCase(java.util.Locale.ROOT);
      path = requireText(path, "path");
      if (!path.startsWith("/")) {
        throw new IllegalArgumentException("route path must start with '/': " + path);
      }
      if (stability == null) {
        throw new IllegalArgumentException("stability is required for " + method + " " + path);
      }
      if (requestSchema != null) requireText(requestSchema, "request schema");
      queryParameters = List.copyOf(queryParameters == null ? List.of() : queryParameters);
      responseSchemas =
          Collections.unmodifiableMap(
              new java.util.TreeMap<>(responseSchemas == null ? Map.of() : responseSchemas));
      if (responseSchemas.isEmpty()) {
        throw new IllegalArgumentException("at least one response schema is required for " + key());
      }
      for (var response : responseSchemas.entrySet()) {
        if (response.getKey() < 100 || response.getKey() > 599) {
          throw new IllegalArgumentException("invalid HTTP status in " + key() + ": " + response.getKey());
        }
        requireText(response.getValue(), "response schema");
      }
      if (security == null || !security.equals(ApiSecurityFilters.contractSecurity(method, path))) {
        throw new IllegalArgumentException("security projection must come from ApiSecurityFilters for " + key());
      }
      if (sdkOperationId != null) {
        requireText(sdkOperationId, "sdk operation id");
        if (stability != Stability.PUBLIC_CONTRACT) {
          throw new IllegalArgumentException("SDK operation must be PUBLIC_CONTRACT: " + key());
        }
        if (!queryParameters.isEmpty()) {
          throw new IllegalArgumentException("v0.1 SDK operations do not accept query parameters: " + key());
        }
      }
      validateLifecycle(stability, lifecycle, preOneException, key());
    }

    String key() {
      return method + " " + path;
    }

    boolean sdkExposed() {
      return sdkOperationId != null;
    }

    String primaryResponseSchema() {
      return responseSchemas.get(200);
    }
  }

  private static Contract contract(
      String method,
      String path,
      Stability stability,
      String sdkOperationId,
      Map<Integer, String> responses) {
    return new Contract(
        method,
        path,
        stability,
        sdkOperationId,
        null,
        List.of(),
        responses,
        ApiSecurityFilters.contractSecurity(method, path),
        null,
        null);
  }

  private static Contract referenceClient(String method, String path, String responseSchema) {
    return contract(method, path, Stability.REFERENCE_CLIENT, null, Map.of(200, responseSchema));
  }

  static final List<Contract> CONTRACTS =
      List.of(
          referenceClient("GET", "/api/knowledge/search", "knowledge-search-response.v1.json"),
          referenceClient("POST", "/api/knowledge/search", "knowledge-search-response.v1.json"),
          referenceClient("GET", "/api/ai/runtime/status", "ai-runtime-status-response.v1.json"),
          referenceClient("GET", "/api/policy/effective", "effective-policy.v1.json"),
          referenceClient("GET", "/api/runtime-context", "runtime-context.v1.json"),
          referenceClient("GET", "/api/operation-history", "operation-history-entry.v1.json"),
          referenceClient("GET", "/api/registry/resources", "resource.v1.json"),
          referenceClient(
              "GET", "/api/indexing-jobs/failed", "failed-indexing-jobs-response.v1.json"),
          referenceClient(
              "GET",
              "/api/indexing-jobs/failed/by-prefix",
              "failed-indexing-jobs-response.v1.json"),
          contract(
              "GET",
              "/api/runtime/manifest",
              Stability.PUBLIC_CONTRACT,
              "getRuntimeManifest",
              Map.of(
                  200, "runtime-manifest-public.v2.json",
                  403, "api-error-response.v1.json",
                  500, "api-error-response.v1.json",
                  503, "api-error-response.v1.json")),
          contract(
              "GET",
              "/.well-known/justsearch/manifest.json",
              Stability.PUBLIC_CONTRACT,
              "getWellKnownRuntimeManifest",
              Map.of(
                  200, "runtime-manifest-public.v2.json",
                  403, "api-error-response.v1.json",
                  500, "api-error-response.v1.json",
                  503, "api-error-response.v1.json")),
          contract(
              "GET",
              "/api/runtime/ready",
              Stability.PUBLIC_CONTRACT,
              "getRuntimeReadiness",
              Map.of(
                  200, "runtime-ready-response.v1.json",
                  403, "api-error-response.v1.json",
                  503, "runtime-ready-response.v1.json")),
          contract(
              "GET",
              "/api/runtime/live",
              Stability.PUBLIC_CONTRACT,
              "getRuntimeLiveness",
              Map.of(200, "runtime-live-response.v1.json", 403, "api-error-response.v1.json")),
          contract(
              "GET",
              "/api/health",
              Stability.PUBLIC_CONTRACT,
              "getLifecycleHealth",
              Map.of(
                  200, "lifecycle-snapshot.v1.json",
                  403, "api-error-response.v1.json",
                  503, "lifecycle-snapshot.v1.json")),
          contract(
              "GET",
              "/api/status",
              Stability.PUBLIC_CONTRACT,
              "getLifecycleStatus",
              Map.of(200, "lifecycle-snapshot.v1.json", 403, "api-error-response.v1.json")));

  private static final Map<String, Contract> BY_KEY = index(CONTRACTS);

  static Map<String, Contract> index(List<Contract> contracts) {
    Map<String, Contract> byKey = new LinkedHashMap<>();
    Set<String> operationIds = new LinkedHashSet<>();
    for (Contract contract : contracts) {
      Contract previous = byKey.putIfAbsent(contract.key(), contract);
      if (previous != null) {
        throw new IllegalArgumentException("duplicate route contract: " + contract.key());
      }
      if (contract.sdkOperationId() != null && !operationIds.add(contract.sdkOperationId())) {
        throw new IllegalArgumentException("duplicate SDK operation id: " + contract.sdkOperationId());
      }
    }
    return Collections.unmodifiableMap(byKey);
  }

  static Contract forRoute(String method, String path) {
    if (method == null || path == null) return null;
    return BY_KEY.get(method.toUpperCase(java.util.Locale.ROOT) + " " + path);
  }

  @SuppressWarnings("unused") // Called from RouteContractPolicyCoverageTest; tests are excluded by the dead-code scan.
  static Set<String> declaredSchemaFiles() {
    Set<String> names = new LinkedHashSet<>();
    for (Contract contract : CONTRACTS) {
      names.addAll(contract.responseSchemas().values());
    }
    return Collections.unmodifiableSet(names);
  }

  static void validateSdkRoutes(
      Collection<String> registeredMethodPaths, Collection<Contract> contracts) {
    validateLiveRoutes(
        registeredMethodPaths,
        contracts.stream().filter(Contract::sdkExposed).toList(),
        "SDK");
  }

  static void validateLifecycleRoutes(
      Collection<String> registeredMethodPaths, Collection<Contract> contracts) {
    validateLiveRoutes(
        registeredMethodPaths,
        contracts.stream().filter(contract -> contract.lifecycle() != null).toList(),
        "lifecycle");
  }

  private static void validateLiveRoutes(
      Collection<String> registeredMethodPaths, Collection<Contract> required, String label) {
    List<String> invalid = new ArrayList<>();
    for (Contract contract : required) {
      long count = registeredMethodPaths.stream().filter(contract.key()::equals).count();
      if (count != 1) invalid.add(contract.key() + " (matches=" + count + ")");
    }
    if (!invalid.isEmpty()) {
      throw new IllegalStateException(label + " contract rows must resolve exactly once: " + invalid);
    }
  }

  private static void validateLifecycle(
      Stability stability, Lifecycle lifecycle, PreOneException exception, String key) {
    if (lifecycle == null) {
      if (exception != null) {
        throw new IllegalArgumentException("pre-1.0 exception requires lifecycle metadata: " + key);
      }
      return;
    }
    boolean insidePublicFloor =
        stability == Stability.PUBLIC_CONTRACT
            && lifecycle.sunsetAt() != null
            && Duration.between(lifecycle.deprecatedSince(), lifecycle.sunsetAt()).compareTo(Duration.ofDays(90)) < 0;
    if (insidePublicFloor && exception == null) {
      throw new IllegalArgumentException("public-contract sunset inside 90-day floor requires pre-1.0 exception: " + key);
    }
    if (!insidePublicFloor && exception != null) {
      throw new IllegalArgumentException("pre-1.0 exception is allowed only for a short public-contract sunset: " + key);
    }
  }

  private static String requireText(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " must be non-blank");
    }
    return value;
  }

  private static URI requireAbsoluteUri(URI value, String label) {
    if (value == null || !value.isAbsolute()) {
      throw new IllegalArgumentException(label + " must be absolute");
    }
    return value;
  }
}
