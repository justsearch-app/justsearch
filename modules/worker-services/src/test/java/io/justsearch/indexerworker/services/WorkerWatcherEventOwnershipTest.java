/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

final class WorkerWatcherEventOwnershipTest {
  @TempDir Path tempDir;

  @Test
  void staleEpochIsIgnoredWithoutPoisoningItsSuccessor() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("stale"));
    Fixture fixture = fixture(root);
    RootWatcherRegistry.Subscription stale = fixture.registry.subscription(root);
    assertTrue(fixture.registry.watch(root.toString(), "successor").watching());

    AtomicBoolean oldEffect = new AtomicBoolean();
    fixture.service.acceptWatcherEvent(stale, () -> oldEffect.set(true));
    AtomicBoolean successorEffect = new AtomicBoolean();
    fixture.service.acceptWatcherEvent(
        fixture.registry.subscription(root), () -> successorEffect.set(true));

    assertFalse(oldEffect.get(), "a callback from the replaced epoch must have no effect");
    assertTrue(successorEffect.get(), "the successor registration must remain usable");
    assertTrue(fixture.admission.replayCertain(), "a stale epoch must not poison its successor");
  }

  @Test
  void missingRegisteredRootMarksReplayUncertainAndSuppressesDeleteEffect() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("missing"));
    Fixture fixture = fixture(root);
    RootWatcherRegistry.Subscription witness = fixture.registry.subscription(root);
    Files.delete(root);
    AtomicBoolean deleteEffect = new AtomicBoolean();

    fixture.service.acceptWatcherEvent(witness, () -> deleteEffect.set(true));

    assertFalse(deleteEffect.get(), "an unavailable root must never route a delete");
    assertFalse(fixture.admission.replayCertain());
  }

  @Test
  void reboundRootMarksReplayUncertainAndSuppressesDeleteEffect() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("rebound"));
    Fixture fixture = fixture(root);
    RootWatcherRegistry.Subscription witness = fixture.registry.subscription(root);
    Path replacement = Files.createDirectory(tempDir.resolve("replacement"));
    Files.delete(root);
    Files.move(replacement, root);
    AtomicBoolean deleteEffect = new AtomicBoolean();

    fixture.service.acceptWatcherEvent(witness, () -> deleteEffect.set(true));

    assertFalse(deleteEffect.get(), "a replacement directory must not inherit old callbacks");
    assertFalse(fixture.admission.replayCertain());
  }

  @Test
  void rootRebindDuringEventEffectInvalidatesReplayCertificateAfterward() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("mid-event-rebind"));
    Path replacement = Files.createDirectory(tempDir.resolve("mid-event-replacement"));
    Fixture fixture = fixture(root);
    RootWatcherRegistry.Subscription witness = fixture.registry.subscription(root);
    AtomicBoolean effectRan = new AtomicBoolean();

    fixture.service.acceptWatcherEvent(witness, () -> {
      try {
        Files.delete(root);
        Files.move(replacement, root);
      } catch (java.io.IOException failure) {
        throw new AssertionError(failure);
      }
      effectRan.set(true);
    });

    assertTrue(effectRan.get());
    assertFalse(fixture.admission.replayCertain());
  }

  @Test
  @Timeout(10)
  void rewatchDrainsAnInFlightCallbackAndRejectsTheOldEpochAfterward() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("rewatch-drain"));
    Fixture fixture = fixture(root);
    RootWatcherRegistry.Subscription old = fixture.registry.subscription(root);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch rewatchStarted = new CountDownLatch(1);
    AtomicBoolean completed = new AtomicBoolean();

    try (var executor = Executors.newFixedThreadPool(2)) {
      var event = executor.submit(() -> fixture.service.acceptWatcherEvent(old, () -> {
        entered.countDown();
        await(release);
        completed.set(true);
      }));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      var rewatch = executor.submit(() -> {
        rewatchStarted.countDown();
        return fixture.service.watchRoot(
            io.justsearch.ipc.WatchRootRequest.newBuilder()
                .setRootPath(root.toString()).setCollection("new").build(),
            CallContext.none());
      });
      assertTrue(rewatchStarted.await(5, TimeUnit.SECONDS));
      Thread.sleep(100);
      assertFalse(rewatch.isDone(), "rewatch must drain the issued event effect");
      release.countDown();
      event.get(5, TimeUnit.SECONDS);
      assertTrue(rewatch.get(5, TimeUnit.SECONDS).getWatching());
    }

    assertTrue(completed.get());
    AtomicBoolean lateOldEffect = new AtomicBoolean();
    fixture.service.acceptWatcherEvent(old, () -> lateOldEffect.set(true));
    assertFalse(lateOldEffect.get(), "the drained epoch must stay retired");
  }

  @Test
  @Timeout(10)
  void unwatchDrainsAnInFlightCallback() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("unwatch-drain"));
    Fixture fixture = fixture(root);
    RootWatcherRegistry.Subscription witness = fixture.registry.subscription(root);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch unwatchStarted = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(2)) {
      var event = executor.submit(() -> fixture.service.acceptWatcherEvent(witness, () -> {
        entered.countDown();
        await(release);
      }));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      var unwatch = executor.submit(() -> {
        unwatchStarted.countDown();
        return fixture.service.unwatchRoot(
            io.justsearch.ipc.UnwatchRootRequest.newBuilder().setRootPath(root.toString()).build(),
            CallContext.none());
      });
      assertTrue(unwatchStarted.await(5, TimeUnit.SECONDS));
      Thread.sleep(100);
      assertFalse(unwatch.isDone(), "unwatch must drain the issued event effect");
      release.countDown();
      event.get(5, TimeUnit.SECONDS);
      assertTrue(unwatch.get(5, TimeUnit.SECONDS).getUnwatched());
    }
  }

  @Test
  @Timeout(10)
  void nestedMutationReadCompletesWhileFinalFenceWriterIsQueued() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("nested-read"));
    Path child = Files.writeString(root.resolve("child.txt"), "data");
    Fixture fixture = fixture(root);
    when(fixture.queue.enqueueEntries(any(), any())).thenReturn(1);
    RootWatcherRegistry.Subscription witness = fixture.registry.subscription(root);
    CountDownLatch effectEntered = new CountDownLatch(1);
    CountDownLatch releaseNestedRead = new CountDownLatch(1);
    CountDownLatch writerStarted = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(2)) {
      var event = executor.submit(() -> fixture.service.acceptWatcherEvent(witness, () -> {
        effectEntered.countDown();
        await(releaseNestedRead);
        fixture.service.acceptWatcherUpsert("docs", child);
      }));
      assertTrue(effectEntered.await(5, TimeUnit.SECONDS));
      var writer = executor.submit(() -> {
        writerStarted.countDown();
        try (var fence = fixture.admission.beginFinalFence(fixture.owner, 5_000)) {
          return fence != null;
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new AssertionError(interrupted);
        }
      });
      assertTrue(writerStarted.await(5, TimeUnit.SECONDS));
      Thread.sleep(100);
      releaseNestedRead.countDown();
      event.get(5, TimeUnit.SECONDS);
      assertTrue(writer.get(5, TimeUnit.SECONDS));
    }
  }

  private Fixture fixture(Path root) {
    JobQueue queue = mock(JobQueue.class);
    var service = new WorkerIngestService(
        queue, null, null, IndexingPacing.unthrottled(), null, null,
        null, null, null, 0L);
    Object owner = new Object();
    var admission = new WorkerMutationAdmission(owner);
    service.setMutationAdmission(admission, owner);
    var registry = new RootWatcherRegistry();
    assertTrue(registry.watch(root.toString(), "docs").watching());
    service.setRootWatcherRegistry(registry);
    return new Fixture(service, registry, admission, queue, owner);
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private record Fixture(
      WorkerIngestService service,
      RootWatcherRegistry registry,
      WorkerMutationAdmission admission,
      JobQueue queue,
      Object owner) {}
}
