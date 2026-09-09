/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.applauncher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.TestEngineExecutors;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LauncherExecutorProviderTest {
  @Test
  void missingOrAmbiguousProvidersFailBeforeConstructingAnyOwner() {
    var creations = new AtomicInteger();
    var provider = provider(new TestEngineExecutors(), creations);
    assertThrows(IllegalStateException.class, () -> LauncherEnvironment.loadExecutors(List.of()));
    assertThrows(IllegalStateException.class,
        () -> LauncherEnvironment.loadExecutors(List.of(provider, provider)));
    assertEquals(0, creations.get());
  }

  @Test
  void oneProviderSuppliesTheSingleProcessOwner() {
    try (var expected = new TestEngineExecutors()) {
      var creations = new AtomicInteger();
      assertSame(expected,
          LauncherEnvironment.loadExecutors(List.of(provider(expected, creations))));
      assertEquals(1, creations.get());
    }
  }

  @Test
  void runtimeClasspathActuallyContainsExactlyOneProvider() {
    try (var actual = LauncherEnvironment.loadExecutors(
        ServiceLoader.load(EngineExecutorRegistry.class).stream().toList())) {
      assertEquals(64, actual.maxConcurrentWork());
    }
  }

  private static ServiceLoader.Provider<EngineExecutorRegistry> provider(
      EngineExecutorRegistry registry, AtomicInteger creations) {
    return new ServiceLoader.Provider<>() {
      @Override public Class<? extends EngineExecutorRegistry> type() { return registry.getClass(); }
      @Override public EngineExecutorRegistry get() {
        creations.incrementAndGet();
        return registry;
      }
    };
  }
}
