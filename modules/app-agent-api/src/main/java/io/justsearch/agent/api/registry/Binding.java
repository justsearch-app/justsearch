/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.Objects;

/**
 * Binding between an Operation declaration and its concrete handler.
 *
 * <p>Per tempdoc 429 §A.7 ExecutorBindingValidator + §E.4: the {@code handlerId} resolves
 * via {@code HandlerRegistry} (boot-time explicit registration in V1; ServiceLoader
 * extension point deferred to V1.5). Validators verify every binding has a registered
 * handler — missing bindings fail the build.
 *
 * <p>Convention: handler id matches the Operation id by default
 * (e.g., {@code core.recover-component} → handlerId {@code "core.recover-component"}). Plugin
 * handlers may use different ids if they multiplex multiple operations onto one handler.
 */
public record Binding(String handlerId) {

  public Binding {
    Objects.requireNonNull(handlerId, "handlerId");
    if (handlerId.isBlank()) {
      throw new IllegalArgumentException("handlerId must be non-blank");
    }
  }

  /** Convenience: derive a Binding from an OperationRef (handlerId == op id). */
  public static Binding of(OperationRef id) {
    return new Binding(id.value());
  }
}
