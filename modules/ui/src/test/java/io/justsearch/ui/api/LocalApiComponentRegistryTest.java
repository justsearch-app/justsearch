/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.engine.DefaultEngineComponentRegistry;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.AppliedConfigurationVersion;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.TestEngineComponents;
import io.justsearch.core.context.RetainedStateBudget;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LocalApiComponentRegistryTest {
  @TempDir Path tempDir;
  private ConfigStore previousStore;

  @BeforeEach
  void installEphemeralApiConfiguration() {
    previousStore = ConfigStore.globalOrNull();
    ConfigStore.setGlobal(new ConfigStore(
        TestResolvedConfigHelper.fromEntries(Map.of(EnvRegistry.API_PORT.configKey(), "0"))));
  }

  @AfterEach
  void restoreConfiguration() {
    TestResolvedConfigHelper.restoreGlobal(previousStore);
  }

  @Test
  void actualBindPublishesConfiguredVersionAndPhysicalPortThenStopPublishesAbsent() {
    var retained = new RetainedStateBudget();
    retained.declare("attempted-configurations", 1, "D1");
    try (var registry = new DefaultEngineComponentRegistry(retained)) {
      var components = registerNonApiComponents(registry);
      var observedStates = new CopyOnWriteArrayList<ComponentState>();
      var subscription = registry.subscribe(snapshot -> snapshot.components().stream()
          .filter(row -> row.spec().name().equals("api"))
          .findFirst()
          .ifPresent(row -> observedStates.add(row.state())));
      try (subscription) {
        var server = LocalApiServer.builder(
              new io.justsearch.core.execution.TestEngineExecutors(),
              new UiSettingsStore(
                  UiSettingsStore.PersistenceMode.IN_MEMORY, tempDir.resolve("settings.json")),
              tempDir.resolve("index"))
            .componentRegistry(registry)
            .indexComponent(components.index())
            .generativeComponent(components.generative())
            .build();
        try {
          var api = registry.snapshot().components().stream()
              .filter(row -> row.spec().name().equals("api"))
              .findFirst().orElseThrow();
          String expected = AppliedConfigurationVersion.digest(
              Set.of(EnvRegistry.API_PORT.configKey()),
              Collections.singletonMap(EnvRegistry.API_PORT.configKey(), 0));
          assertEquals(ComponentState.READY, api.state());
          assertEquals(expected, api.appliedVersion());
          assertEquals(expected, api.desiredVersion());
          assertEquals(Set.of(EnvRegistry.API_PORT.configKey()), api.spec().dependencyKeys());
          assertNotNull(api.evidence());
          assertEquals("boundPort=" + server.getPort(), api.evidence());
          assertTrue(server.getPort() > 0);
        } finally {
          server.stop();
        }
      }
      var stopped = registry.snapshot().components().getFirst();
      assertEquals(ComponentState.ABSENT, stopped.state());
      assertEquals("api.stopped", stopped.reasonCode());
      assertTrue(observedStates.indexOf(ComponentState.STARTING)
          < observedStates.indexOf(ComponentState.READY));
      assertFalse(observedStates.contains(ComponentState.RELOADING));
    }
  }

  @Test
  void occupiedConfiguredPortPublishesEphemeralPolicyAsAppliedAndConfiguredPolicyAsDesired()
      throws Exception {
    try (var occupiedPort = new ServerSocket()) {
      occupiedPort.setReuseAddress(false);
      occupiedPort.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
      int configuredPort = occupiedPort.getLocalPort();
      ConfigStore.setGlobal(new ConfigStore(TestResolvedConfigHelper.fromEntries(
          Map.of(EnvRegistry.API_PORT.configKey(), Integer.toString(configuredPort)))));

      var retained = new RetainedStateBudget();
      retained.declare("attempted-configurations", 1, "D1");
      try (var registry = new DefaultEngineComponentRegistry(retained)) {
        var components = registerNonApiComponents(registry);
        var server = LocalApiServer.builder(
                new io.justsearch.core.execution.TestEngineExecutors(),
                new UiSettingsStore(
                    UiSettingsStore.PersistenceMode.IN_MEMORY, tempDir.resolve("settings.json")),
                tempDir.resolve("index"))
            .componentRegistry(registry)
            .indexComponent(components.index())
            .generativeComponent(components.generative())
            .build();
        try {
          var api = registry.snapshot().components().getFirst();
          String desired = AppliedConfigurationVersion.digest(
              Set.of(EnvRegistry.API_PORT.configKey()),
              Collections.singletonMap(EnvRegistry.API_PORT.configKey(), configuredPort));
          String applied = AppliedConfigurationVersion.digest(
              Set.of(EnvRegistry.API_PORT.configKey()),
              Collections.singletonMap(EnvRegistry.API_PORT.configKey(), null));

          assertEquals(ComponentState.READY, api.state());
          assertEquals(desired, api.desiredVersion());
          assertEquals(applied, api.appliedVersion());
          assertNotEquals(api.desiredVersion(), api.appliedVersion());
          assertTrue(server.getPort() > 0);
          assertNotEquals(configuredPort, server.getPort());
          assertEquals("boundPort=" + server.getPort(), api.evidence());
        } finally {
          server.stop();
        }
      }
    }
  }

  @Test
  void assemblyFailureBeforeBindIsPublishedAsFailed() {
    ConfigStore.setGlobal(new ConfigStore(TestResolvedConfigHelper.fromEntries(Map.of(
        EnvRegistry.API_PORT.configKey(), "0",
        EnvRegistry.PROD_MODE.configKey(), "true"))));
    var retained = new RetainedStateBudget();
    retained.declare("attempted-configurations", 1, "D1");
    try (var registry = new DefaultEngineComponentRegistry(retained)) {
      var components = registerNonApiComponents(registry);
      var builder = LocalApiServer.builder(
              new io.justsearch.core.execution.TestEngineExecutors(),
              new UiSettingsStore(
                  UiSettingsStore.PersistenceMode.IN_MEMORY, tempDir.resolve("settings.json")),
              tempDir.resolve("index"))
          .componentRegistry(registry)
          .indexComponent(components.index())
          .generativeComponent(components.generative());

      assertThrows(IllegalStateException.class, builder::build);
      var api = registry.snapshot().components().getFirst();
      assertEquals(ComponentState.FAILED, api.state());
      assertEquals("api.compose_failed", api.reasonCode());
    }
  }

  private static NonApiComponents registerNonApiComponents(
      DefaultEngineComponentRegistry registry) {
    var handles = new java.util.HashMap<String, ComponentHandle>();
    try (var specs = TestEngineComponents.fourComponents()) {
      specs.snapshot().components().stream()
          .filter(component -> !component.spec().name().equals("api"))
          .forEach(component -> handles.put(
              component.spec().name(), registry.register(component.spec())));
    }
    return new NonApiComponents(handles.get("index"), handles.get("generative"));
  }

  private record NonApiComponents(ComponentHandle index, ComponentHandle generative) {}
}
