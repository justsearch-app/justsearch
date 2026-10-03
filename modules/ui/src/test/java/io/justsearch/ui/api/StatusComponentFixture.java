/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.justsearch.app.services.bootstrap.CapabilityGraph;
import io.justsearch.app.services.lifecycle.LifecycleProjection;
import io.justsearch.app.services.lifecycle.ReasonRetainingComponentHandle;
import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.TestEngineComponents;
import java.time.Instant;

/** Four-component registry fixture for status/readiness tests. */
final class StatusComponentFixture implements AutoCloseable {
  private final TestEngineComponents components = TestEngineComponents.fourComponents();
  private final ComponentHandle index =
      new ReasonRetainingComponentHandle(components.handle("index"));
  private final ComponentHandle generative =
      new ReasonRetainingComponentHandle(components.handle("generative"));

  StatusComponentFixture() {
    transition("api", ComponentState.READY, null, "API bound");
    index.transition(ComponentState.READY, null, "Worker serving");
    transition("encoders", ComponentState.READY, null, "Encoders ready");
    transition("generative", ComponentState.READY, null, "Inference ready");
  }

  CapabilityGraph capabilities() {
    return CapabilityGraph.fromRegistry(components);
  }

  LifecycleProjection.Projection projection() {
    return LifecycleProjection.project(components.snapshot(), Instant.now());
  }

  TestEngineComponents registry() {
    return components;
  }

  ComponentHandle index() {
    return index;
  }

  ComponentHandle handle(String name) {
    return switch (name) {
      case "index" -> index;
      case "generative" -> generative;
      default -> components.handle(name);
    };
  }

  void transition(String name, ComponentState state, String reason, String evidence) {
    handle(name).transition(state, reason, evidence);
  }

  void attach(StatusLifecycleHandler handler) {
    handler.setIndexComponent(components, index);
  }

  @Override
  public void close() {
    components.close();
  }
}
