/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Stable, namespaced identifier for an Operation entry.
 *
 * <p>Format (per tempdoc 429 §A.7 NamespacingValidator): {@code ^(core|vendor\.\w+)\.[a-z][a-z0-9-]*$}.
 * Examples: {@code core.recover-component}, {@code core.bulk-reindex}, {@code vendor.acme.export}.
 *
 * <p>Used as a value type rather than a raw String for type-safety per tempdoc 429
 * §"Type-system invariants" — the constructor enforces the namespace pattern, so an
 * invalid OperationRef is unrepresentable.
 *
 * <p>Wire format: serialized as a bare JSON string (e.g., {@code "core.recover-component"})
 * via {@link JsonValue} + {@link JsonCreator}, matching the convention enums use.
 * Per tempdoc 441: single-field value-class records flatten to bare strings on the wire
 * for consistency with enums and FE consumer ergonomics.
 *
 * <p>Per slice 447-followup/2.1: implements {@link NamespacedId} sealed interface for
 * shape factoring — the namespace regex + validation logic is single-sourced. Construction
 * delegates to {@link NamespacedId#validate(String, String)}.
 */
public record OperationRef(@JsonValue String value) implements RegistryRef<Operation> {

  @JsonCreator
  public OperationRef {
    value = NamespacedId.validate(value, "OperationRef");
  }

  @Override
  public String toString() {
    return value;
  }
}
