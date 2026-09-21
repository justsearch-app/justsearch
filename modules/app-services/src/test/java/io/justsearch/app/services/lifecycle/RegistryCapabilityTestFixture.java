/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.lifecycle;

import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.TestEngineComponents;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/** Test-only pairing of the mutable registry producer and its read-only capability projection. */
final class RegistryCapabilityTestFixture implements AutoCloseable {
  private final TestEngineComponents components = TestEngineComponents.fourComponents();
  private final ReasonRetainingComponentHandle producer;
  private final RegistryBackedCapability capability;
  private final List<RegistryBackedCapability.Subscription> subscriptions = new ArrayList<>();

  private RegistryCapabilityTestFixture(String componentName, String capabilityName) {
    producer = new ReasonRetainingComponentHandle(components.handle(componentName));
    capability = new RegistryBackedCapability(components, componentName, capabilityName);
  }

  static RegistryCapabilityTestFixture worker() {
    return new RegistryCapabilityTestFixture("index", "worker");
  }

  static RegistryCapabilityTestFixture inference() {
    return new RegistryCapabilityTestFixture("generative", "inference");
  }

  void transition(ComponentState state, String reasonCode) {
    transition(state, reasonCode, null);
  }

  void transition(ComponentState state, String reasonCode, String detail) {
    producer.transition(state, reasonCode, detail);
  }

  CapabilityHealth health() {
    return capability.health();
  }

  String pendingReason() {
    return capability.pendingReason();
  }

  String pendingDetail() {
    return capability.pendingDetail();
  }

  boolean available() {
    return capability.available();
  }

  void addListener(BiConsumer<CapabilityHealth, CapabilityHealth> listener) {
    subscriptions.add(
        capability.subscribe(
            change -> listener.accept(change.previous().health(), change.current().health())));
  }

  @Override
  public void close() {
    subscriptions.forEach(RegistryBackedCapability.Subscription::close);
    components.close();
  }
}
