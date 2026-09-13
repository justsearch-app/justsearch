/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Stable, namespaced identifier for a {@link Surface} catalog entry.
 *
 * <p>Format mirrors {@link OperationRef} / {@link DiagnosticChannelRef}:
 * {@code ^(core|vendor\.\w+)\.[a-z][a-z0-9-]*$}. Examples:
 * {@code core.library-surface}, {@code core.engine-log-surface},
 * {@code vendor.acme.dashboard}.
 *
 * <p>Per slice 449 §0 D1: Surface is a {@code Manifest} (composition over
 * primitives), not a fifth primitive. SurfaceRef is parallel to other namespaced
 * identifiers but typed distinctly so cross-primitive references stay
 * unambiguous in the type system.
 *
 * <p>Per slice 447-followup/2.1: implements {@link NamespacedId} sealed interface for
 * shape factoring — namespace regex + validation logic single-sourced.
 */
public record SurfaceRef(@JsonValue String value) implements RegistryRef<Surface> {

  @JsonCreator
  public SurfaceRef {
    value = NamespacedId.validate(value, "SurfaceRef");
  }

  @Override
  public String toString() {
    return value;
  }
}
