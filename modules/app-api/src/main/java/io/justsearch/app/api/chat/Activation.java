/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.chat;

import java.util.Objects;
import java.util.Optional;

/**
 * Whether chat is wanted: the stored chat switch, enterprise policy and an installed configuration,
 * combined. {@code reasonIfNot} names the first missing input and is present exactly when chat is
 * not effective.
 */
public record Activation(boolean effective, Optional<String> reasonIfNot) {
  public Activation {
    Objects.requireNonNull(reasonIfNot, "reasonIfNot");
    if (effective == reasonIfNot.isPresent()) {
      throw new IllegalArgumentException("reasonIfNot is present exactly when not effective");
    }
    reasonIfNot.ifPresent(
        r -> {
          if (r.isBlank()) throw new IllegalArgumentException("reasonIfNot must not be blank");
        });
  }
}
