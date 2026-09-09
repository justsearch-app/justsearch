/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import io.justsearch.core.execution.EngineExecutorRejectedException.Reason;
import io.justsearch.core.execution.EngineExecutorSnapshot;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.core.execution.EngineExecutorSpec.Kind;
import io.justsearch.core.execution.EngineExecutorSpec.Mode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import org.junit.jupiter.api.Test;

final class WorkerExecutorRegistrationsTest {

  @Test
  void projectsPolicyLimitsIntoStableWorkerOwnerShapes() {
    RecordingRegistry registry = new RecordingRegistry(-1);

    try (WorkerExecutorRegistrations registrations = new WorkerExecutorRegistrations(registry)) {
      assertEquals(
          new EngineExecutorSpec(
              WorkerExecutorRegistrations.WATCHER_RECONCILE,
              Kind.BACKGROUND,
              Mode.SCHEDULED,
              1,
              23,
              2),
          registrations.watcherReconcile().spec());
      assertEquals(
          new EngineExecutorSpec(
              WorkerExecutorRegistrations.EXTRACTION_TIMEBOX,
              Kind.BACKGROUND,
              Mode.PLATFORM,
              1,
              23,
              2),
          registrations.extractionTimebox().spec());
      assertEquals(
          new EngineExecutorSpec(
              WorkerExecutorRegistrations.SANDBOX_READERS,
              Kind.BACKGROUND,
              Mode.PLATFORM,
              7,
              23,
              2),
          registrations.sandboxReaders().spec());
      assertTrue(registry.registrations.stream().noneMatch(RecordingRegistration::closed));
    }

    assertTrue(registry.registrations.stream().allMatch(RecordingRegistration::closed));
  }

  @Test
  void partialAcquisitionRollsBackEarlierNamesInReverseOrder() {
    RecordingRegistry registry = new RecordingRegistry(3);

    EngineExecutorRejectedException failure =
        assertThrows(
            EngineExecutorRejectedException.class,
            () -> new WorkerExecutorRegistrations(registry));

    assertEquals(Reason.INSTANCE_LIMIT, failure.reason());
    assertEquals(
        List.of(
            WorkerExecutorRegistrations.EXTRACTION_TIMEBOX,
            WorkerExecutorRegistrations.WATCHER_RECONCILE),
        registry.closedNames);
  }

  private static final class RecordingRegistry implements EngineExecutorRegistry {
    private final int refuseRegistration;
    private final List<RecordingRegistration> registrations = new ArrayList<>();
    private final List<String> closedNames = new ArrayList<>();

    private RecordingRegistry(int refuseRegistration) {
      this.refuseRegistration = refuseRegistration;
    }

    @Override
    public Registration register(EngineExecutorSpec spec) {
      if (registrations.size() + 1 == refuseRegistration) {
        throw new EngineExecutorRejectedException(Reason.INSTANCE_LIMIT, spec.name(), 1);
      }
      RecordingRegistration registration = new RecordingRegistration(spec, closedNames);
      registrations.add(registration);
      return registration;
    }

    @Override
    public Limits limits(Kind kind) {
      assertEquals(Kind.BACKGROUND, kind);
      return new Limits(7, 23);
    }

    @Override
    public int retryAfterSeconds() { return 1; }
    @Override public int maxConcurrentWork() {
      return 31;
    }

    @Override
    public EngineExecutorSnapshot snapshot() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() {}
  }

  private static final class RecordingRegistration implements EngineExecutorRegistry.Registration {
    private final EngineExecutorSpec spec;
    private final List<String> closedNames;
    private boolean closed;

    private RecordingRegistration(EngineExecutorSpec spec, List<String> closedNames) {
      this.spec = spec;
      this.closedNames = closedNames;
    }

    @Override
    public EngineExecutorSpec spec() {
      return spec;
    }

    @Override
    public ExecutorService open(ThreadFactory threadFactory) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledExecutorService openScheduled(ThreadFactory threadFactory) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ExecutorService openVirtual() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
      closed = true;
      closedNames.add(spec.name());
    }

    boolean closed() {
      return closed;
    }
  }
}
