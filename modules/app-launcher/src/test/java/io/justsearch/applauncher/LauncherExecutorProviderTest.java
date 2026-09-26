/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.applauncher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.app.api.EngineProcessResources;
import io.justsearch.core.component.ComponentSpec;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import io.justsearch.core.execution.EngineExecutorSpec;
import java.time.Duration;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LauncherExecutorProviderTest {
  @Test
  void missingOrAmbiguousProvidersFailBeforeConstructingAnyOwner() {
    var creations = new AtomicInteger();
    var resources = org.mockito.Mockito.mock(EngineProcessResources.class);
    var provider = provider(resources, creations);
    assertThrows(IllegalStateException.class,
        () -> LauncherEnvironment.loadProcessResources(List.of()));
    assertThrows(IllegalStateException.class,
        () -> LauncherEnvironment.loadProcessResources(List.of(provider, provider)));
    assertEquals(0, creations.get());
  }

  @Test
  void oneProviderSuppliesTheSingleProcessOwner() {
    var expected = org.mockito.Mockito.mock(EngineProcessResources.class);
    var creations = new AtomicInteger();
    assertSame(expected,
        LauncherEnvironment.loadProcessResources(List.of(provider(expected, creations))));
    assertEquals(1, creations.get());
  }

  @Test
  void runtimeClasspathContainsOneCoupledProviderAndCloseReleasesItsRegistries() {
    var actual = LauncherEnvironment.loadProcessResources(
        ServiceLoader.load(EngineProcessResources.class).stream().toList());
    assertSame(actual.admission(), actual.operationLeases());
    assertEquals(64, actual.executors().maxConcurrentWork());
    actual.components().register(new ComponentSpec(
        "launcher-provider-test", false, Set.of(), ComponentSpec.ComposeCapability.BESIDE,
        Duration.ZERO, 0));

    actual.close();

    assertThrows(IllegalStateException.class, () -> actual.components().register(new ComponentSpec(
        "after-close", false, Set.of(), ComponentSpec.ComposeCapability.BESIDE,
        Duration.ZERO, 0)));
    assertThrows(EngineExecutorRejectedException.class, () -> actual.executors().register(
        EngineExecutorSpec.virtual("after-close", EngineExecutorSpec.Kind.FOREGROUND, 1)));
  }

  private static ServiceLoader.Provider<EngineProcessResources> provider(
      EngineProcessResources resources, AtomicInteger creations) {
    return new ServiceLoader.Provider<>() {
      @Override public Class<? extends EngineProcessResources> type() {
        return resources.getClass();
      }

      @Override public EngineProcessResources get() {
        creations.incrementAndGet();
        return resources;
      }
    };
  }
}
