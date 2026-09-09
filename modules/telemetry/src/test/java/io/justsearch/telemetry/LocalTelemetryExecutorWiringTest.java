/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Regression coverage for LocalTelemetry's process-owned executor registrations. */
class LocalTelemetryExecutorWiringTest {

  @Test
  void requiresRegistryAndUsesDistinctBoundedRegistrationsForReaderAndHeartbeat() throws Exception {
    RecordingRegistry registry = new RecordingRegistry();
    LocalTelemetry telemetry =
        new LocalTelemetry(registry, Files.createTempDirectory("telemetry-executors"), 60_000,
            "head", "test");
    try {
      assertEquals(2, registry.registrations.size());
      assertEquals("telemetry.head.export", registry.registrations.get(0).spec.name());
      assertEquals("telemetry.head.heartbeat", registry.registrations.get(1).spec.name());
      for (CapturedRegistration registration : registry.registrations) {
        assertEquals(EngineExecutorSpec.Kind.BACKGROUND, registration.spec.kind());
        assertEquals(EngineExecutorSpec.Mode.SCHEDULED, registration.spec.mode());
        assertEquals(1, registration.spec.threadCount());
        assertEquals(64, registration.spec.queueCapacity());
        assertEquals(1, registration.spec.maxInstances());
        assertNotNull(registration.scheduler);
        assertTrue(registration.scheduler.periodicSchedules > 0);
      }
    } finally {
      telemetry.close();
    }
  }

  @Test
  void closeClosesOwnedRegistrationsWithoutClosingInjectedRegistry() throws Exception {
    RecordingRegistry registry = new RecordingRegistry();
    LocalTelemetry telemetry =
        new LocalTelemetry(registry, Files.createTempDirectory("telemetry-close"), 60_000,
            "worker", "test");

    telemetry.close();

    assertFalse(registry.closed, "LocalTelemetry must not close the process registry");
    assertEquals(2, registry.registrations.size());
    for (CapturedRegistration registration : registry.registrations) {
      assertTrue(registration.closed);
      assertTrue(registration.scheduler.isShutdown());
    }
  }

  @Test
  void heartbeatRegistrationFailureCleansReaderAndExportRegistration() throws Exception {
    RecordingRegistry registry = new RecordingRegistry();
    registry.failOnRegister = 2;

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                new LocalTelemetry(
                    registry,
                    Files.createTempDirectory("telemetry-failure"),
                    60_000,
                    "worker",
                    "test"));

    assertTrue(failure.getMessage().contains("second registration"));
    assertEquals(1, registry.registrations.size());
    CapturedRegistration export = registry.registrations.getFirst();
    assertTrue(export.closed);
    assertTrue(export.scheduler.isShutdown());
  }

  @Test
  void heartbeatOpenFailureClosesBothRegistrationsAndExportReader() throws Exception {
    RecordingRegistry registry = new RecordingRegistry();
    registry.failOnOpen = 2;

    assertThrows(
        IllegalStateException.class,
        () ->
            new LocalTelemetry(
                registry,
                Files.createTempDirectory("telemetry-open-failure"),
                60_000,
                "worker",
                "test"));

    assertEquals(2, registry.registrations.size());
    assertTrue(registry.registrations.get(0).closed);
    assertTrue(registry.registrations.get(0).scheduler.isShutdown());
    assertTrue(registry.registrations.get(1).closed);
  }

  @Test
  void nullRegistryIsRejectedBeforeTelemetryResourcesAreCreated() throws Exception {
    assertThrows(
        NullPointerException.class,
        () ->
            new LocalTelemetry(
                null, Files.createTempDirectory("telemetry-null"), 60_000, "worker", "test"));
  }

  private static final class RecordingRegistry implements EngineExecutorRegistry {
    private final List<CapturedRegistration> registrations = new ArrayList<>();
    private int failOnRegister;
    private int failOnOpen;
    private boolean closed;

    @Override
    public EngineExecutorRegistry.Registration register(EngineExecutorSpec spec) {
      if (registrations.size() + 1 == failOnRegister) {
        throw new IllegalStateException("second registration failed");
      }
      CapturedRegistration registration = new CapturedRegistration(spec, this);
      registrations.add(registration);
      return registration;
    }

    @Override
    public Limits limits(EngineExecutorSpec.Kind kind) {
      return new Limits(4, 64);
    }

    @Override
    public int retryAfterSeconds() { return 1; }
    @Override public int maxConcurrentWork() {
      return 64;
    }

    @Override
    public io.justsearch.core.execution.EngineExecutorSnapshot snapshot() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
      closed = true;
      registrations.forEach(CapturedRegistration::close);
    }
  }

  private static final class CapturedRegistration implements EngineExecutorRegistry.Registration {
    private final EngineExecutorSpec spec;
    private final RecordingRegistry owner;
    private RecordingScheduler scheduler;
    private boolean closed;

    private CapturedRegistration(EngineExecutorSpec spec, RecordingRegistry owner) {
      this.spec = spec;
      this.owner = owner;
    }

    @Override
    public EngineExecutorSpec spec() {
      return spec;
    }

    @Override
    public ExecutorService open(ThreadFactory threadFactory) {
      return Executors.newSingleThreadExecutor(threadFactory);
    }

    @Override
    public ScheduledExecutorService openScheduled(ThreadFactory threadFactory) {
      if (owner.registrations.indexOf(this) + 1 == owner.failOnOpen) {
        throw new IllegalStateException("heartbeat open failed");
      }
      scheduler = new RecordingScheduler(threadFactory);
      return scheduler;
    }

    @Override
    public ExecutorService openVirtual() {
      return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public void close() {
      closed = true;
      if (scheduler != null) scheduler.shutdownNow();
    }
  }

  private static final class RecordingScheduler extends ScheduledThreadPoolExecutor {
    private int periodicSchedules;

    private RecordingScheduler(ThreadFactory threadFactory) {
      super(1, threadFactory);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
        Runnable command, long initialDelay, long period, TimeUnit unit) {
      periodicSchedules++;
      return super.scheduleAtFixedRate(command, initialDelay, period, unit);
    }
  }
}
