/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.app.services.lifecycle.ReasonRetainingComponentHandle;
import io.justsearch.app.services.lifecycle.RegistryBackedCapability;
import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.TestEngineComponents;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.telemetry.Telemetry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Explicit registry ownership for isolated {@link KnowledgeServerBootstrap} tests. */
final class KnowledgeServerBootstrapTestFixture implements AutoCloseable {
  private final TestEngineExecutors executors = new TestEngineExecutors();
  private final TestEngineComponents components = TestEngineComponents.fourComponents();
  private final ComponentHandle indexComponent =
      new ReasonRetainingComponentHandle(components.handle("index"));
  private final List<io.justsearch.core.component.EngineComponentRegistry.Subscription>
      subscriptions = new ArrayList<>();
  private final KnowledgeServerBootstrap bootstrap;

  static KnowledgeServerBootstrapTestFixture create(KnowledgeServerConfig config) {
    return create(config, null, WorkerHost.unavailable(), true);
  }

  static KnowledgeServerBootstrapTestFixture create(
      KnowledgeServerConfig config, WorkerHost workerHost) {
    return create(config, null, workerHost, true);
  }

  static KnowledgeServerBootstrapTestFixture create(
      KnowledgeServerConfig config,
      Telemetry telemetry,
      WorkerHost workerHost,
      boolean automaticRootProducers) {
    return new KnowledgeServerBootstrapTestFixture(
        config, telemetry, workerHost, automaticRootProducers);
  }

  private KnowledgeServerBootstrapTestFixture(
      KnowledgeServerConfig config,
      Telemetry telemetry,
      WorkerHost workerHost,
      boolean automaticRootProducers) {
    bootstrap =
        new KnowledgeServerBootstrap(
            executors,
            config,
            telemetry,
            components,
            indexComponent,
            workerHost,
            automaticRootProducers,
            new java.util.concurrent.locks.ReentrantReadWriteLock());
  }

  KnowledgeServerBootstrap bootstrap() {
    return bootstrap;
  }

  TestEngineComponents components() {
    return components;
  }

  ComponentHandle indexComponent() {
    return indexComponent;
  }

  /** Models the readiness sampler's successful conjunction, not physical health alone. */
  void publishSamplerReady() {
    indexComponent.transition(ComponentState.READY, null, null);
  }

  /** Records effective legacy health/reason changes from the registry without another authority. */
  List<String> recordIndexTransitions() {
    var seen = new ArrayList<String>();
    var last = new AtomicReference<>(legacyObservation());
    subscriptions.add(
        components.subscribe(
            ignored -> {
              String next = legacyObservation();
              if (!next.equals(last.getAndSet(next))) seen.add(next);
            }));
    return seen;
  }

  private String legacyObservation() {
    var snapshot = indexComponent.snapshot();
    return RegistryBackedCapability.healthOf(snapshot.state()) + "/" + snapshot.reasonCode();
  }

  @Override
  public void close() {
    try {
      bootstrap.close();
    } finally {
      try {
        subscriptions.forEach(
            io.justsearch.core.component.EngineComponentRegistry.Subscription::close);
        components.close();
      } finally {
        executors.close();
      }
    }
  }
}
