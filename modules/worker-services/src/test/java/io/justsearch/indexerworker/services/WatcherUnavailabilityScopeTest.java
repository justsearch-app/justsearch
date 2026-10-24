/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.SwitchBufferCapableQueue;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Failure attribution exercises the actual watcher callback and production failure sink. */
final class WatcherUnavailabilityScopeTest {
  @TempDir Path temporary;

  @Test
  void endedUnavailableIncarnationIsRecoverableOnlyByItsOwnReconciliation() throws Exception {
    try (var f = new Fixture()) {
      Files.move(f.root, temporary.resolve("away"));
      assertTrue(f.watcher.handleWatcherException(f.root, f.witness.watcherEpoch(),
          new IllegalStateException("native watch ended")));
      assertTrue(f.runtimeCertain(), "attributed root loss must not set the runtime-wide latch");
      assertFalse(f.admission.replayCertain());
      verify(f.queue).putSwitchBuffer(anyString(), anyString(), anyString());
      var exact = f.roots();
      assertEquals(1, exact.size());
      f.reconcile(exact);
      assertTrue(f.admission.replayCertain());
    }
  }

  @Test
  void aNewerUnavailableIncarnationCannotBeClearedByAnOlderReconciliation() throws Exception {
    try (var f = new Fixture()) {
      Files.move(f.root, temporary.resolve("away"));
      f.watcher.handleWatcherException(f.root, f.witness.watcherEpoch(), new IllegalStateException("ended"));
      assertTrue(f.runtimeCertain());
      var old = f.roots();
      assertEquals(1, old.size());
      var type = old.getFirst().getClass();
      Object debt = type.getMethod("debt").invoke(old.getFirst());
      Object newer = type.getConstructors()[0].newInstance(debt, new Object());
      f.admission.getClass().getMethod("markRootUnavailable", type).invoke(f.admission, newer);
      f.reconcile(old);
      assertFalse(f.admission.replayCertain());
      assertEquals(List.of(newer), f.roots());
    }
  }

  @Test
  void runtimeWideLostMutationStillRefusesAfterTheRootReconciles() throws Exception {
    try (var f = new Fixture()) {
      f.admission.markReplayUncertain();
      Files.move(f.root, temporary.resolve("away"));
      f.watcher.handleWatcherException(f.root, f.witness.watcherEpoch(), new IllegalStateException("ended"));
      f.reconcile(f.roots());
      assertFalse(f.admission.replayCertain());
      try (var fence = f.admission.beginFinalFence(f.owner, 1_000)) {
        assertNotNull(fence);
        assertFalse(fence.armReplayCertificate());
      }
    }
  }

  @Test
  void unattributedFailureWhileRootIsPresentKeepsTheRuntimeWideLatch() throws Exception {
    try (var f = new Fixture()) {
      f.watcher.handleWatcherException(f.root, f.witness.watcherEpoch(), new IllegalStateException("unknown I/O failure"));
      assertFalse(f.runtimeCertain());
      assertFalse(f.admission.replayCertain());
      verify(f.queue, never()).putSwitchBuffer(anyString(), anyString(), anyString());
    }
  }

  @Test
  void unavailableVolumeEvidenceCannotMakeAnUnexpectedFailureRecoverable() throws Exception {
    try (var f = new Fixture()) {
      Files.move(f.root, temporary.resolve("away"));
      RootIdentity.VOLUME_IDENTITY_UNAVAILABLE.set(true);
      try {
        f.watcher.handleWatcherException(f.root, f.witness.watcherEpoch(), new IllegalStateException("ended"));
        assertFalse(f.runtimeCertain());
        assertFalse(f.admission.replayCertain());
      } finally { RootIdentity.VOLUME_IDENTITY_UNAVAILABLE.set(false); }
    }
  }

  private final class Fixture implements AutoCloseable {
    final Path root = Files.createDirectory(temporary.resolve("root"));
    final Object owner = new Object();
    final WorkerMutationAdmission admission = new WorkerMutationAdmission(owner);
    final SwitchBufferCapableQueue queue = mock(SwitchBufferCapableQueue.class);
    final WorkerIngestService service;
    final WorkerMethvinWatcher watcher;
    final RootWatcherRegistry.Subscription witness;
    final List<RuntimeException> failures = new CopyOnWriteArrayList<>();

    Fixture() throws Exception {
      when(queue.putSwitchBuffer(anyString(), anyString(), anyString())).thenReturn(true);
      service = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
          temporary, temporary, null, null, null, 0L);
      service.setMutationAdmission(admission, owner);
      Class<?> targetType = Class.forName("io.justsearch.indexerworker.server.DefaultWorkerAppServices$WatcherCallbacks$Target");
      var targetConstructor = targetType.getDeclaredConstructors()[0];
      targetConstructor.setAccessible(true);
      Object target = targetConstructor.newInstance(null, service, null, admission, owner);
      Class<?> callbacksType = Class.forName("io.justsearch.indexerworker.server.DefaultWorkerAppServices$WatcherCallbacks");
      var callbackConstructor = callbacksType.getDeclaredConstructor(targetType);
      callbackConstructor.setAccessible(true);
      Object callbacks = callbackConstructor.newInstance(target);
      Method sink;
      try { sink = callbacksType.getDeclaredMethod("failure", RuntimeException.class); sink.setAccessible(true); }
      catch (NoSuchMethodException baseline) { sink = null; }
      Method actualSink = sink;
      Consumer<RuntimeException> report = failure -> {
        failures.add(failure);
        if (actualSink == null) admission.markReplayUncertain(); // baseline's production wiring
        else {
          try { actualSink.invoke(callbacks, failure); }
          catch (ReflectiveOperationException brokenSink) { throw new IllegalStateException(brokenSink); }
        }
      };
      watcher = new WorkerMethvinWatcher(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.watcher(),
          queue, null, ignored -> {}, (ignored, force) -> {}, (registration, collection, path) -> {}, report);
      witness = new RootWatcherRegistry.Subscription(root, "notes", new Object(), RootIdentity.capture(root));
      assertTrue(watcher.registerRoot(witness));
      assertTrue(watcher.activateRoot(witness));
    }
    boolean runtimeCertain() throws Exception {
      try { return (boolean) admission.getClass().getMethod("runtimeReplayCertain").invoke(admission); }
      catch (NoSuchMethodException baseline) { return admission.replayCertain(); }
    }
    List<?> roots() throws Exception {
      try { return (List<?>) admission.getClass().getMethod("unavailableRoots").invoke(admission); }
      catch (NoSuchMethodException baseline) { return List.of(); }
    }
    void reconcile(List<?> exact) throws Exception {
      if (exact.isEmpty()) return;
      Object debt = exact.getFirst().getClass().getMethod("debt").invoke(exact.getFirst());
      service.getClass().getMethod("watcherRootReconciled", debt.getClass(), List.class).invoke(service, debt, exact);
    }
    @Override public void close() { watcher.close(); }
  }
}
