/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSnapshot;
import io.justsearch.core.execution.EngineExecutorSpec;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RuntimeActivationServiceExecutorTest {

  @TempDir Path home;
  private String previousHome;

  @BeforeEach
  void setHome() {
    previousHome = System.getProperty("justsearch.home");
    System.setProperty("justsearch.home", home.toString());
  }

  @AfterEach
  void restoreHome() {
    if (previousHome == null) {
      System.clearProperty("justsearch.home");
    } else {
      System.setProperty("justsearch.home", previousHome);
    }
  }

  @Test
  void registersBoundedBackgroundHttpClientAndClosesOnlyOwnedRegistration() throws Exception {
    CapturingRegistry processExecutors = new CapturingRegistry(false);
    RuntimeActivationService service = newService(processExecutors);
    try {
      assertEquals("head.runtime-activation.http", processExecutors.spec.name());
      assertEquals(EngineExecutorSpec.Kind.BACKGROUND, processExecutors.spec.kind());
      assertEquals(EngineExecutorSpec.Mode.PLATFORM, processExecutors.spec.mode());
      assertEquals(1, processExecutors.spec.threadCount());
      assertEquals(37, processExecutors.spec.queueCapacity());
      assertEquals(1, processExecutors.spec.maxInstances());

      Field httpField = RuntimeActivationService.class.getDeclaredField("http");
      httpField.setAccessible(true);
      var http = (java.net.http.HttpClient) httpField.get(service);
      assertSame(processExecutors.registration.executor, http.executor().orElseThrow());
    } finally {
      service.close();
    }

    assertTrue(processExecutors.registration.closed);
    assertTrue(processExecutors.registration.executor.isShutdown());
    assertFalse(processExecutors.closed, "a component must not close the process registry");
    processExecutors.close();
  }

  @Test
  void constructorFailureClosesRegistrationOpenedBeforeHttpClient() {
    CapturingRegistry processExecutors = new CapturingRegistry(true);
    assertThrows(RuntimeException.class, () -> newService(processExecutors));
    assertTrue(processExecutors.registration.closed);
    assertFalse(processExecutors.closed);
    processExecutors.close();
  }

  private RuntimeActivationService newService(EngineExecutorRegistry processExecutors) {
    return new RuntimeActivationService(
        processExecutors,
        OnlineAiService.unavailable(),
        new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY),
        null,
        null);
  }

  private static final class CapturingRegistry implements EngineExecutorRegistry {
    private final CapturingRegistration registration;
    private final boolean failOpen;
    private EngineExecutorSpec spec;
    private boolean closed;

    private CapturingRegistry(boolean failOpen) {
      this.failOpen = failOpen;
      this.registration = new CapturingRegistration(failOpen);
    }

    @Override
    public Registration register(EngineExecutorSpec spec) {
      this.spec = spec;
      registration.spec = spec;
      return registration;
    }

    @Override
    public Limits limits(EngineExecutorSpec.Kind kind) {
      assertEquals(EngineExecutorSpec.Kind.BACKGROUND, kind);
      return new Limits(4, 37);
    }

    @Override
    public int retryAfterSeconds() { return 1; }
    @Override public int maxConcurrentWork() {
      return 64;
    }

    @Override
    public EngineExecutorSnapshot snapshot() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
      closed = true;
      registration.close();
    }
  }

  private static final class CapturingRegistration implements EngineExecutorRegistry.Registration {
    private final boolean failOpen;
    private ExecutorService executor;
    private boolean closed;
    private EngineExecutorSpec spec;

    private CapturingRegistration(boolean failOpen) {
      this.failOpen = failOpen;
    }

    @Override
    public EngineExecutorSpec spec() {
      return spec;
    }

    @Override
    public ExecutorService open(ThreadFactory factory) {
      if (failOpen) {
        throw new IllegalStateException("test registration failure");
      }
      executor = Executors.newSingleThreadExecutor(factory);
      return executor;
    }

    @Override
    public ScheduledExecutorService openScheduled(ThreadFactory factory) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ExecutorService openVirtual() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
      closed = true;
      if (executor != null) {
        executor.shutdownNow();
      }
    }
  }
}
