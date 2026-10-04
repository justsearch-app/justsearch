/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.component;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/** Stable declaration of an Engine-owned component. */
public record ComponentSpec(
    String name,
    boolean essential,
    Set<String> dependencyKeys,
    ComposeCapability composeCapability,
    Duration startDeadline,
    int recoveryBudget) {

  public ComponentSpec {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(dependencyKeys, "dependencyKeys");
    Objects.requireNonNull(composeCapability, "composeCapability");
    Objects.requireNonNull(startDeadline, "startDeadline");
    if (name.isBlank() || startDeadline.isNegative() || recoveryBudget < 0) {
      throw new IllegalArgumentException(
          "name, non-negative start deadline and recovery budget are required");
    }
    dependencyKeys = Set.copyOf(dependencyKeys);
    if (dependencyKeys.stream().anyMatch(key -> key == null || key.isBlank())) {
      throw new IllegalArgumentException("dependency keys must not be null or blank");
    }
  }

  public enum ComposeCapability {
    BESIDE,
    IN_PLACE,
    CHOOSES_PER_APPLY
  }
}
