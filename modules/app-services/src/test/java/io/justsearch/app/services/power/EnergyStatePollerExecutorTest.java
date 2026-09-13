/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.util.EnergyState;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSnapshot;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import org.junit.jupiter.api.Test;

/** Registry ownership and restart coverage for the host energy poller. */
final class EnergyStatePollerExecutorTest {

  @Test
  void startRegistersBoundedScheduledPollerAndCloseLeavesRegistryOpen() {
    CapturingRegistry processExecutors = new CapturingRegistry(false);
    EnergyStatePoller poller =
        new EnergyStatePoller(processExecutors, new GpuSchedulingGauge(), EnergyState::unknown);
    try {
      poller.start();
      assertEquals(1, processExecutors.registrations.size());
      CapturingRegistration registration = processExecutors.registrations.getFirst();
      assertEquals("head.energy-state-poller", registration.spec.name());
      assertEquals(EngineExecutorSpec.Kind.BACKGROUND, registration.spec.kind());
      assertEquals(EngineExecutorSpec.Mode.SCHEDULED, registration.spec.mode());
      assertEquals(1, registration.spec.threadCount());
      assertEquals(37, registration.spec.queueCapacity());
      assertEquals(1, registration.spec.maxInstances());
    } finally {
      poller.close();
    }

    assertTrue(processExecutors.registrations.getFirst().closed);
    assertFalse(processExecutors.closed);
    processExecutors.close();
  }

  @Test
  void closeThenStartGetsFreshRegistrationForRestart() {
    CapturingRegistry processExecutors = new CapturingRegistry(false);
    EnergyStatePoller poller =
        new EnergyStatePoller(processExecutors, new GpuSchedulingGauge(), EnergyState::unknown);
    try {
      poller.start();
      poller.close();
      poller.start();
      assertEquals(2, processExecutors.registrations.size());
      assertTrue(processExecutors.registrations.get(0).closed);
      assertFalse(processExecutors.registrations.get(1).closed);
    } finally {
      poller.close();
      processExecutors.close();
    }
  }

  @Test
  void registrationOpenFailureIsTypedAndDoesNotLeakRegistration() {
    CapturingRegistry processExecutors = new CapturingRegistry(true);
    EnergyStatePoller poller =
        new EnergyStatePoller(processExecutors, new GpuSchedulingGauge(), EnergyState::unknown);

    assertThrows(IllegalStateException.class, poller::start);
    assertEquals(1, processExecutors.registrations.size());
    assertTrue(processExecutors.registrations.getFirst().closed);
    assertFalse(processExecutors.closed);
    poller.close();
    processExecutors.close();
  }

  private static final class CapturingRegistry implements EngineExecutorRegistry {
    private final List<CapturingRegistration> registrations = new ArrayList<>();
    private final boolean failOpen;
    private boolean closed;

    private CapturingRegistry(boolean failOpen) {
      this.failOpen = failOpen;
    }

    @Override
    public Registration register(EngineExecutorSpec spec) {
      CapturingRegistration registration = new CapturingRegistration(spec, failOpen);
      registrations.add(registration);
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
      registrations.forEach(CapturingRegistration::close);
    }
  }

  private static final class CapturingRegistration implements EngineExecutorRegistry.Registration {
    private final EngineExecutorSpec spec;
    private final boolean failOpen;
    private ExecutorService executor;
    private boolean closed;

    private CapturingRegistration(EngineExecutorSpec spec, boolean failOpen) {
      this.spec = spec;
      this.failOpen = failOpen;
    }

    @Override
    public EngineExecutorSpec spec() {
      return spec;
    }

    @Override
    public ExecutorService open(ThreadFactory factory) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledExecutorService openScheduled(ThreadFactory factory) {
      if (failOpen) {
        throw new IllegalStateException("registration open failed");
      }
      executor = Executors.newSingleThreadScheduledExecutor(factory);
      return (ScheduledExecutorService) executor;
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
