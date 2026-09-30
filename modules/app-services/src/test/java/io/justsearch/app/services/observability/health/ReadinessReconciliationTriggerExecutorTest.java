/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.observability.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSnapshot;
import io.justsearch.core.execution.EngineExecutorSpec;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import org.junit.jupiter.api.Test;

/** Wiring and ownership regression for the readiness sampler's bounded process executor. */
final class ReadinessReconciliationTriggerExecutorTest {

  @Test
  void registersPlatformBackgroundExecutorAndClosesOnlyItsRegistration() {
    CapturingRegistry processExecutors = new CapturingRegistry(false);
    ReadinessReconciliationTrigger trigger =
        new ReadinessReconciliationTrigger(processExecutors);
    try {
      assertEquals("head.readiness-reconcile", processExecutors.registration.spec.name());
      assertEquals(EngineExecutorSpec.Kind.BACKGROUND, processExecutors.registration.spec.kind());
      assertEquals(EngineExecutorSpec.Mode.PLATFORM, processExecutors.registration.spec.mode());
      assertEquals(1, processExecutors.registration.spec.threadCount());
      assertEquals(37, processExecutors.registration.spec.queueCapacity());
      assertEquals(1, processExecutors.registration.spec.maxInstances());
    } finally {
      trigger.close();
    }

    assertTrue(processExecutors.registration.closed);
    assertTrue(processExecutors.registration.executor.isShutdown());
    assertFalse(processExecutors.closed);
    processExecutors.close();
  }

  @Test
  void registrationOpenFailureClosesOwnedRegistration() {
    CapturingRegistry processExecutors = new CapturingRegistry(true);

    assertThrows(
        IllegalStateException.class,
        () -> new ReadinessReconciliationTrigger(processExecutors));

    assertTrue(processExecutors.registration.closed);
    assertFalse(processExecutors.closed);
    processExecutors.close();
  }

  private static final class CapturingRegistry implements EngineExecutorRegistry {
    private final CapturingRegistration registration;
    private final boolean failOpen;
    private boolean closed;

    private CapturingRegistry(boolean failOpen) {
      this.failOpen = failOpen;
      this.registration = new CapturingRegistration();
    }

    @Override
    public Registration register(EngineExecutorSpec spec) {
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

    private final class CapturingRegistration implements EngineExecutorRegistry.Registration {
      private EngineExecutorSpec spec;
      private ExecutorService executor;
      private boolean closed;

      @Override
      public EngineExecutorSpec spec() {
        return spec;
      }

      @Override
      public ExecutorService open(ThreadFactory factory) {
        if (failOpen) {
          throw new IllegalStateException("registration open failed");
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
}
