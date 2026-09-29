/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;

/**
 * Reference to an i18n message catalog entry.
 *
 * <p>Per tempdoc 429 §C.E + §E.16: keys are dotted-path strings such as
 * {@code "ops.recover-component.label"}, resolved via the per-primitive message catalog
 * (e.g., {@code /api/messages/registry-operation/{locale}}). The
 * {@link io.justsearch.agent.api.registry.validator.RegistryShapeValidator}
 * pipeline (Phase 3) verifies every key in a catalog resolves against the loaded
 * properties resource.
 *
 * <p>Used as a value type rather than a raw String per tempdoc 429
 * §"Type-system invariants" — keys are non-empty by construction.
 *
 * <p>Wire format: serialized as a bare JSON string via {@link JsonValue} +
 * {@link JsonCreator}, matching the convention enums use. Per tempdoc 441:
 * single-field value-class records flatten to bare strings for consistency with
 * enums and FE consumer ergonomics.
 */
public record I18nKey(@JsonValue String value) {
  @JsonCreator
  public I18nKey {
    Objects.requireNonNull(value, "value");
    if (value.isBlank()) {
      throw new IllegalArgumentException("I18nKey value must be non-blank");
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
