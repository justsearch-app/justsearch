/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Boot-time registry mapping {@link OperationRef} to {@link OperationHandler}.
 *
 * <p>Per tempdoc 429 §E.4: explicit registration in {@code HeadAssembly} matches
 * the existing codebase pattern (the deleted {@code ToolRegistry.register(...)} loop +
 * {@code MetricCatalog} substrate). Cross-artifact ServiceLoader discovery for plugin
 * handlers is the V1.5+ extension point — the {@code Map} stays open to late
 * registration.
 *
 * <p>Duplicate registration throws — boot-time mistakes surface fast rather than
 * silently shadowing.
 */
public final class HandlerRegistry {

  private final Map<OperationRef, OperationHandler> handlers = new LinkedHashMap<>();

  public synchronized void register(OperationRef id, OperationHandler handler) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(handler, "handler");
    if (handlers.putIfAbsent(id, handler) != null) {
      throw new IllegalArgumentException("Duplicate handler registration for " + id);
    }
  }

  public synchronized Optional<OperationHandler> resolve(OperationRef id) {
    Objects.requireNonNull(id, "id");
    return Optional.ofNullable(handlers.get(id));
  }

  /** All registered handler ids. Used by validators (per §A.7 ExecutorBindingValidator). */
  public synchronized Set<OperationRef> registeredIds() {
    return Collections.unmodifiableSet(new LinkedHashSet<>(handlers.keySet()));
  }

  public synchronized boolean isEmpty() {
    return handlers.isEmpty();
  }
}
