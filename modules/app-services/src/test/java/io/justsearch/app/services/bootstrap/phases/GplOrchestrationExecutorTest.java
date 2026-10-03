/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.gpl.GplJobCoordinator;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSnapshot;
import io.justsearch.core.execution.EngineExecutorSpec;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import org.junit.jupiter.api.Test;

/** Registry ownership and bounded platform scheduling coverage for GPL auto-trigger polling. */
final class GplOrchestrationExecutorTest {

  @Test
  void startsBoundedPlatformOwnerAndClosesOnlyItsRegistration() {
    CapturingRegistry processExecutors = new CapturingRegistry();
    AutoCloseable handle =
        GplOrchestration.startAutoTrigger(
            processExecutors,
            mock(GplJobCoordinator.class),
            () -> (KnowledgeClient) null,
            mock(OnlineAiService.class),
            Path.of("target", "gpl-snapshot.json"),
            mock(io.justsearch.app.services.gpl.GplRevalidationTrigger.class));
    try {
      assertEquals(1, processExecutors.registrations.size());
      CapturingRegistration registration = processExecutors.registrations.getFirst();
      assertEquals("head.gpl-auto-trigger", registration.spec.name());
      assertEquals(EngineExecutorSpec.Kind.BACKGROUND, registration.spec.kind());
      assertEquals(EngineExecutorSpec.Mode.PLATFORM, registration.spec.mode());
      assertEquals(1, registration.spec.threadCount());
      assertEquals(37, registration.spec.queueCapacity());
      assertEquals(1, registration.spec.maxInstances());
    } finally {
      try {
        handle.close();
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    }
    assertTrue(processExecutors.registrations.getFirst().closed);
    assertFalse(processExecutors.closed);
    processExecutors.close();
  }

  private static final class CapturingRegistry implements EngineExecutorRegistry {
    private final List<CapturingRegistration> registrations = new ArrayList<>();
    private boolean closed;

    @Override
    public Registration register(EngineExecutorSpec spec) {
      CapturingRegistration registration = new CapturingRegistration(spec);
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
    private ExecutorService executor;
    private boolean closed;

    private CapturingRegistration(EngineExecutorSpec spec) {
      this.spec = spec;
    }

    @Override
    public EngineExecutorSpec spec() {
      return spec;
    }

    @Override
    public ExecutorService open(ThreadFactory factory) {
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
