/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.runtime;

import io.justsearch.app.services.HeadAssembly;
import io.justsearch.app.services.lifecycle.RegistryBackedCapability;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.EngineComponentRegistry;
import io.justsearch.core.component.EngineComponentSnapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Connects the runtime manifest to the Engine component registry's immutable observations. */
public final class RuntimeManifestListenerWiring {

  private static final Logger log = LoggerFactory.getLogger(RuntimeManifestListenerWiring.class);

  private RuntimeManifestListenerWiring() {}

  /**
   * Installs the publisher-owned registry subscription and projects the initial observation.
   * Registration precedes the initial read inside {@link RuntimeManifestPublisher#observeComponents}
   * so boot cannot lose an intervening component transition.
   */
  public static void wire(
      RuntimeManifestPublisher publisher,
      HeadAssembly bootstrap,
      EngineComponentRegistry components,
      Supplier<Path> indexBasePathSupplier,
      String modeIntent) {
    try {
      publisher.observeComponents(
          components,
          snapshot ->
              publishSiblingProjections(
                  publisher, bootstrap, snapshot, indexBasePathSupplier, modeIntent));
    } catch (Exception failure) {
      log.warn("Runtime manifest component wiring failed (non-fatal)", failure);
    }
  }

  private static void publishSiblingProjections(
      RuntimeManifestPublisher publisher,
      HeadAssembly bootstrap,
      EngineComponentSnapshot snapshot,
      Supplier<Path> indexBasePathSupplier,
      String modeIntent)
      throws IOException {
    EngineComponentSnapshot.Component index = component(snapshot, "index");
    EngineComponentSnapshot.Component generative = component(snapshot, "generative");

    switch (index.state()) {
      case READY -> {
        Path path = indexBasePathSupplier.get();
        publisher.publishWorkerReady(path == null ? null : path.toString(), index.stateSince());
      }
      case ABSENT, RELOADING, FAILED, UNAVAILABLE ->
          publisher.publishWorkerFailed(
              firstNonBlank(index.reasonCode(), index.evidence(), "Index component unavailable"));
      case STARTING -> publisher.publishWorkerPending();
    }

    var generativeHealth = RegistryBackedCapability.healthOf(generative.state());
    publisher.publishAi(
        generativeHealth.name(),
        generative.spec().essential() || generative.state() != ComponentState.ABSENT,
        generative.state() == ComponentState.READY ? null : generative.reasonCode(),
        generative.state() == ComponentState.READY ? generative.stateSince() : null,
        bootstrap.expectedLlamaServerBuild(),
        bootstrap.actualLlamaServerBuild(),
        bootstrap.llamaServerThinkingSupport(),
        bootstrap.launchedContextWindow());
    publisher.publishMode(modeIntent, realizedMode(index.state(), generative.state()));
    publisher.publishChat(bootstrap.realizedChatIdentity());
  }

  private static EngineComponentSnapshot.Component component(
      EngineComponentSnapshot snapshot, String name) {
    return snapshot.components().stream()
        .filter(candidate -> candidate.spec().name().equals(name))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Component disappeared: " + name));
  }

  private static String realizedMode(ComponentState index, ComponentState generative) {
    if (index != ComponentState.READY) return "degraded";
    return generative == ComponentState.READY ? "full" : "retrieval-only";
  }

  private static String firstNonBlank(String first, String second, String fallback) {
    if (first != null && !first.isBlank()) return first;
    if (second != null && !second.isBlank()) return second;
    return fallback;
  }
}
