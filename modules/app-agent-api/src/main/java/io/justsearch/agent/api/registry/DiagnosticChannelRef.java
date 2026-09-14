/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Stable, namespaced identifier for a {@link DiagnosticChannel} entry.
 *
 * <p>Format mirrors {@link OperationRef}: {@code ^(core|vendor\.\w+)\.[a-z][a-z0-9-]*$}.
 * Examples: {@code core.engine-log}, {@code vendor.acme.audit-log}.
 *
 * <p>Per slice 448 §B.A.4: parallels {@link OperationRef} as a value-typed identifier.
 * Distinct from {@link OperationRef} so that DiagnosticChannel ids cannot be silently
 * confused with Operation ids in the cross-primitive type system.
 *
 * <p>Per slice 447-followup/2.1: implements {@link NamespacedId} sealed interface for
 * shape factoring — namespace regex + validation logic single-sourced.
 */
public record DiagnosticChannelRef(@JsonValue String value) implements RegistryRef<DiagnosticChannel> {

  @JsonCreator
  public DiagnosticChannelRef {
    value = NamespacedId.validate(value, "DiagnosticChannelRef");
  }

  @Override
  public String toString() {
    return value;
  }
}
