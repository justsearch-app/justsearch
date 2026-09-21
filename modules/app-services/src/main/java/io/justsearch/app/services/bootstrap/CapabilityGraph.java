/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap;

import io.justsearch.app.api.lifecycle.Capability;
import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.services.lifecycle.RegistryBackedCapability;
import io.justsearch.core.component.EngineComponentRegistry;
import java.util.Objects;

/** Read-only gate projections; the Engine component registry owns lifecycle state. */
public record CapabilityGraph(Capability worker, Capability inference) {
  public CapabilityGraph {
    Objects.requireNonNull(worker, "worker");
    Objects.requireNonNull(inference, "inference");
  }

  public static CapabilityGraph fromRegistry(EngineComponentRegistry registry) {
    return new CapabilityGraph(
        new RegistryBackedCapability(registry, "index", "worker"),
        new RegistryBackedCapability(registry, "generative", "inference"));
  }

  /** Explicit immutable unavailable graph for an isolated SearchPort-only composition. */
  public static CapabilityGraph unavailable() {
    return new CapabilityGraph(
        new Unavailable("worker", true, LifecycleReasonCode.WORKER_NOT_CONNECTED.code()),
        new Unavailable("inference", false, LifecycleReasonCode.INFERENCE_MODEL_NOT_CONFIGURED.code()));
  }

  private record Unavailable(String name, boolean required, String pendingReason)
      implements Capability {
    @Override
    public CapabilityHealth health() {
      return CapabilityHealth.OFFLINE;
    }
  }
}
