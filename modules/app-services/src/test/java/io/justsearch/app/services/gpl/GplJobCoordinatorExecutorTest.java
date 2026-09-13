/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.gpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.gpl.GplJobStatus;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Registry ownership and synchronous-refusal coverage for the GPL coordinator. */
final class GplJobCoordinatorExecutorTest {

  @Test
  void registersBoundedPlatformOwnerAndClosesOnlyItsRegistration(@TempDir Path tempDir) {
    CapturingRegistry processExecutors = new CapturingRegistry(false);
    GplJobCoordinator coordinator =
        new GplJobCoordinator(
            processExecutors,
            () -> null,
            null,
            false,
            new GplTrainingTripleStore(tempDir));
    try {
      assertEquals(1, processExecutors.registrations.size());
      CapturingRegistration registration = processExecutors.registrations.getFirst();
      assertEquals("head.gpl-job-coordinator", registration.spec.name());
      assertEquals(EngineExecutorSpec.Kind.BACKGROUND, registration.spec.kind());
      assertEquals(EngineExecutorSpec.Mode.PLATFORM, registration.spec.mode());
      assertEquals(1, registration.spec.threadCount());
      assertEquals(37, registration.spec.queueCapacity());
      assertEquals(1, registration.spec.maxInstances());
    } finally {
      coordinator.close();
    }
    assertTrue(processExecutors.registrations.getFirst().closed);
    assertFalse(processExecutors.closed);
    processExecutors.close();
  }

  @Test
  void synchronousSubmissionRefusalLeavesTerminalFailure(@TempDir Path tempDir) throws InterruptedException {
    CapturingRegistry processExecutors = new CapturingRegistry(true);
    GplJobCoordinator coordinator =
        new GplJobCoordinator(
            processExecutors,
            () -> null,
            null,
            false,
            new GplTrainingTripleStore(tempDir));
    try {
      assertThrows(RejectedExecutionException.class, coordinator::runAsync);
      assertEquals(GplJobStatus.Status.FAILED, coordinator.getStatus().status());
      assertTrue(coordinator.awaitCompletion(1, TimeUnit.SECONDS));
      assertFalse(processExecutors.registrations.getFirst().closed,
          "a refused run releases its work but the retryable coordinator still owns its registration");
    } finally {
      coordinator.close();
      processExecutors.close();
    }
    assertTrue(processExecutors.registrations.getFirst().closed);
  }

  private static final class CapturingRegistry implements EngineExecutorRegistry {
    private final List<CapturingRegistration> registrations = new ArrayList<>();
    private final boolean rejectSubmission;
    private boolean closed;

    private CapturingRegistry(boolean rejectSubmission) {
      this.rejectSubmission = rejectSubmission;
    }

    @Override
    public Registration register(EngineExecutorSpec spec) {
      CapturingRegistration registration =
          new CapturingRegistration(spec, rejectSubmission);
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
    private final boolean rejectSubmission;
    private ExecutorService executor;
    private boolean closed;

    private CapturingRegistration(EngineExecutorSpec spec, boolean rejectSubmission) {
      this.spec = spec;
      this.rejectSubmission = rejectSubmission;
    }

    @Override
    public EngineExecutorSpec spec() {
      return spec;
    }

    @Override
    public ExecutorService open(ThreadFactory factory) {
      executor = Executors.newSingleThreadExecutor(factory);
      if (rejectSubmission) {
        executor.shutdownNow();
      }
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
