/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.health;

import java.util.Objects;
import java.util.Optional;

/**
 * Provenance fields attached to every {@link HealthEvent}.
 *
 * <p>Per tempdoc 430 §B.I: with several logical Engine services emitting into one
 * stream, the FE cannot distinguish "Worker says embedding is blocked" from "Inference
 * says embedding is blocked" without a structured source. Adopts OpenTelemetry Resource
 * semconv (Stable group) verbatim:
 *
 * <ul>
 *   <li>{@code service.name} — short identity string ({@code "head"}, {@code "worker"},
 *       {@code "brain"}). Stable Required in OTel SDK semconv.
 *   <li>{@code service.instance.id} — process UUID assigned at startup. Stable Required.
 *   <li>{@code service.version} — build version (e.g., {@code "1.0"}). Stable
 *       Recommended; optional here so tests can omit it.
 * </ul>
 *
 * <p>{@code service.namespace} is implicitly {@code "justsearch"} and is not carried on
 * every wire record.
 */
public record Source(
    String serviceName, String serviceInstanceId, Optional<String> serviceVersion) {

  public Source {
    Objects.requireNonNull(serviceName, "serviceName");
    Objects.requireNonNull(serviceInstanceId, "serviceInstanceId");
    Objects.requireNonNull(serviceVersion, "serviceVersion");
    if (serviceName.isBlank()) {
      throw new IllegalArgumentException("serviceName must be non-blank");
    }
    if (serviceInstanceId.isBlank()) {
      throw new IllegalArgumentException("serviceInstanceId must be non-blank");
    }
  }

  /** Convenience factory matching the {@code HeadAssembly} call shape. */
  public static Source forProcess(String serviceName, String serviceInstanceId, String version) {
    return new Source(
        serviceName,
        serviceInstanceId,
        version == null || version.isBlank() ? Optional.empty() : Optional.of(version));
  }
}
