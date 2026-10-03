/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RouteContractPolicyTest {
  private static final Instant DEPRECATED = Instant.parse("2026-01-01T00:00:00Z");
  private static final URI DOCS = URI.create("https://docs.justsearch.example/deprecations/fake");

  @Test
  void publicRoutesDeclareCapacityAndFrozenAdmissionResponses() {
    for (String path : List.of(
        "/api/runtime/manifest", "/.well-known/justsearch/manifest.json",
        "/api/runtime/ready", "/api/runtime/live", "/api/status")) {
      var responses = RouteContractPolicy.forRoute("GET", path).responseSchemas();
      assertEquals("api-error-response.v1.json", responses.get(429), path);
      assertEquals(
          path.equals("/api/runtime/ready")
              ? "runtime-ready-unavailable-response.v1.json" : "api-error-response.v1.json",
          responses.get(503), path);
    }
    var health = RouteContractPolicy.forRoute("GET", "/api/health").responseSchemas();
    assertNull(health.get(429));
    assertEquals("lifecycle-snapshot.v2.json", health.get(503));
  }

  @Test
  void retiredWorkerRouteNamesReplacementWithoutInventingCalendarSunset() {
    var contract = RouteContractPolicy.forRoute("POST", "/api/worker/restart");
    assertEquals(Map.of(410, "api-error-response.v1.json"), contract.responseSchemas());
    var lifecycle = contract.lifecycle();
    assertEquals(Instant.parse("2026-09-29T00:00:00Z"), lifecycle.deprecatedSince());
    assertNull(lifecycle.sunsetAt());
    assertEquals("POST /api/engine/components/index/recover", lifecycle.replacement());
    assertEquals(URI.create("https://github.com/justsearch-app/justsearch/blob/main/docs/reference/api-contract-map.md"),
        lifecycle.documentationUri());
    assertEquals(410, ApiErrorHandler.httpStatusFor(io.justsearch.app.api.ApiErrorCode.ENDPOINT_RETIRED));
  }

  @Test
  void validatesChronologyAndPublicContractFloor() {
    assertThrows(
        IllegalArgumentException.class,
        () -> lifecycle(DEPRECATED, DEPRECATED));
    assertThrows(
        IllegalArgumentException.class,
        () -> contract(RouteContractPolicy.Stability.PUBLIC_CONTRACT, lifecycle(DEPRECATED, DEPRECATED.plus(89, ChronoUnit.DAYS)), null));
    assertDoesNotThrow(
        () -> contract(RouteContractPolicy.Stability.PUBLIC_CONTRACT, lifecycle(DEPRECATED, DEPRECATED.plus(90, ChronoUnit.DAYS)), null));
  }

  @Test
  void acceptsExplicitPreOneExceptionOnlyForShortPublicContractSunset() {
    RouteContractPolicy.PreOneException exception =
        new RouteContractPolicy.PreOneException(
            "The 0.x compatibility break is recorded.",
            URI.create("https://docs.justsearch.example/decisions/pre-one-break"));
    RouteContractPolicy.Lifecycle shortWindow =
        lifecycle(DEPRECATED, DEPRECATED.plus(30, ChronoUnit.DAYS));

    assertDoesNotThrow(
        () -> contract(RouteContractPolicy.Stability.PUBLIC_CONTRACT, shortWindow, exception));
    assertThrows(
        IllegalArgumentException.class,
        () -> contract(RouteContractPolicy.Stability.REFERENCE_CLIENT, shortWindow, exception));
    assertThrows(
        IllegalArgumentException.class,
        () -> contract(RouteContractPolicy.Stability.PUBLIC_CONTRACT, null, exception));
  }

  @Test
  void lifecycleRowsMustResolveToExactlyOneLiveRoute() {
    RouteContractPolicy.Contract row =
        contract(RouteContractPolicy.Stability.REFERENCE_CLIENT, lifecycle(DEPRECATED, null), null);
    assertThrows(
        IllegalStateException.class,
        () -> RouteContractPolicy.validateLifecycleRoutes(List.of(), List.of(row)));
    assertThrows(
        IllegalStateException.class,
        () -> RouteContractPolicy.validateLifecycleRoutes(List.of(row.key(), row.key()), List.of(row)));
    assertDoesNotThrow(
        () -> RouteContractPolicy.validateLifecycleRoutes(List.of(row.key()), List.of(row)));
  }

  private static RouteContractPolicy.Lifecycle lifecycle(Instant deprecated, Instant sunset) {
    return new RouteContractPolicy.Lifecycle(deprecated, sunset, "GET /replacement", DOCS);
  }

  private static RouteContractPolicy.Contract contract(
      RouteContractPolicy.Stability stability,
      RouteContractPolicy.Lifecycle lifecycle,
      RouteContractPolicy.PreOneException exception) {
    return new RouteContractPolicy.Contract(
        "GET",
        "/fake/{id}",
        stability,
        null,
        null,
        List.of(),
        Map.of(200, "runtime-live-response.v1.json"),
        ApiSecurityFilters.contractSecurity("GET", "/fake/{id}"),
        lifecycle,
        exception);
  }
}
