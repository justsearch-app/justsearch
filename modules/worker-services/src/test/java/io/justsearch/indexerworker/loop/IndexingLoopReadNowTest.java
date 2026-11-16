/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.core.scheduling.IndexingHold;
import io.justsearch.core.scheduling.IndexingSchedule;
import io.justsearch.indexerworker.coordination.InProcessWorkerSignalBus;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * S6c-1/3/4/6 (C1'): the indexing loop reads waiting files through the reading rules while a "read
 * waiting files now" release is active, ends it at the first poll that claims nothing, and stops
 * admitting at every revocation point, finishing an admitted batch as with Pause. Slice members are
 * reached reflectively so the before-state fails by assertion.
 */
@org.junit.jupiter.api.extension.ExtendWith(io.justsearch.indexerworker.services.NoVolumeIdentityExtension.class)
final class IndexingLoopReadNowTest {
  @TempDir Path directory;

  private static Object call(Object target, String name, Object... args) {
    return assertDoesNotThrow(() -> {
      for (var method : target.getClass().getMethods()) {
        if (method.getName().equals(name) && method.getParameterCount() == args.length) {
          return method.invoke(target, args);
        }
      }
      throw new NoSuchMethodException(name);
    }, "IndexingHold." + name);
  }

  private static void readNow(IndexingHold hold) {
    assertEquals("STARTED", call(call(hold, "readWaitingNow", true), "outcome").toString());
  }

  private static IndexingHold outsideHours() {
    var hold = new IndexingHold();
    hold.setLocalTime(() -> new IndexingHold.LocalMinute(1, 500));
    hold.setPolicy(false, null, null, new IndexingSchedule(true, List.of(1), 600, 660), false);
    return hold;
  }

  private IndexingLoop loop(JobQueue queue, IndexingHold hold, Supplier<ResolvedConfig> config) {
    doCallRealMethod().when(queue).ownsClaimForPublication(any());
    var loop = new IndexingLoop(
        io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocr(),
        io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), queue,
        mock(IndexingCoordinator.class), mock(CommitOps.class), mock(DocumentFieldOps.class),
        mock(IndexCountOps.class), config, new InProcessWorkerSignalBus(new GpuSchedulingGauge(hold)),
        IndexingPacing.unthrottled(), null, null, null, null, null, null, null);
    var witness = io.justsearch.indexerworker.services.RootWitness.inMemory();
    assertTrue(witness.admits(directory));
    loop.useRootCheck(witness);
    return loop;
  }

  private JobQueue.IndexJob job(String name) {
    Path file = directory.resolve(name);
    return new JobQueue.IndexJob(file, null, null, null, null, null, false, null, file);
  }

  private static void await(java.util.function.BooleanSupplier condition, String message) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
    assertTrue(condition.getAsBoolean(), message);
  }

  private static List<Path> published(JobQueue queue) {
    var paths = new java.util.ArrayList<Path>();
    for (var invocation : mockingDetails(queue).getInvocations()) {
      if (!invocation.getMethod().getName().equals("markDoneTransitions")) continue;
      for (var transition : (java.util.Collection<?>) invocation.getArgument(0)) {
        var claim = (JobQueue.IndexJob) assertDoesNotThrow(
            () -> transition.getClass().getMethod("claim").invoke(transition));
        paths.add(claim.path());
      }
    }
    return paths;
  }

  @Test
  void waitingAndArrivingFilesAreReadThenTheEmptyPollEndsTheReleaseAndTheRuleHoldsAgain()
      throws Exception {
    var hold = outsideHours();
    var queue = mock(JobQueue.class);
    var polls = new AtomicInteger();
    var waiting = job("waiting.txt");
    var arrived = job("arrived-during-release.txt");
    when(queue.pollPending(anyInt())).thenAnswer(ignored -> switch (polls.incrementAndGet()) {
      case 1 -> List.of(waiting);
      case 2 -> List.of(arrived);
      default -> List.of();
    });
    var loop = loop(queue, hold, () -> null);
    try {
      loop.start();
      Thread.sleep(300);
      assertEquals(0, polls.get(), "outside reading hours nothing is read");
      readNow(hold);
      await(() -> published(queue).containsAll(List.of(waiting.path(), arrived.path())),
          "waiting files and files arriving during the release are read");
      await(() -> call(hold, "activeRelease") == null, "the poll that claims nothing ends the release");
      await(() -> hold.snapshot().state() == IndexingHold.State.PAUSED, "the loop acknowledges the hold again");
      assertEquals(3, polls.get(), "the empty poll was the third");
      Thread.sleep(300);
      assertEquals(3, polls.get(), "after the release the reading-hours rule holds again");
      assertEquals(List.of("SCHEDULE"), hold.snapshot().reasons(), "the rules are unchanged");
    } finally {
      loop.close();
    }
  }

  @Test
  void aPollOfOnlyRefusedOrBackedOffRowsEndsTheRelease() throws Exception {
    var hold = outsideHours();
    var queue = mock(JobQueue.class);
    var polls = new AtomicInteger();
    when(queue.pollPending(anyInt())).thenAnswer(ignored -> { polls.incrementAndGet(); return List.of(); });
    when(queue.pollPendingBudgetExhausted()).thenReturn(true);
    var loop = loop(queue, hold, () -> null);
    try {
      loop.start();
      readNow(hold);
      await(() -> polls.get() >= 1, "the release admits a poll");
      await(() -> call(hold, "activeRelease") == null, "a bounded scan that claims nothing ends it");
      await(() -> hold.snapshot().state() == IndexingHold.State.PAUSED, "acknowledged");
      int settled = polls.get();
      Thread.sleep(300);
      assertEquals(settled, polls.get(), "no further polling while the rule holds");
    } finally {
      loop.close();
    }
  }

  @Test
  void revocationBeforeAdmissionAdmitsNothing() throws Exception {
    for (var revoke : List.<java.util.function.Consumer<IndexingHold>>of(
        hold -> hold.pause(null, "USER_PAUSED"),
        hold -> hold.setPolicy(false, null, null, new IndexingSchedule(true, List.of(1, 2), 600, 660), false))) {
      var hold = outsideHours();
      var queue = mock(JobQueue.class);
      readNow(hold);
      revoke.accept(hold);
      var loop = loop(queue, hold, () -> null);
      try {
        loop.start();
        Thread.sleep(300);
        verify(queue, never()).pollPending(anyInt());
      } finally {
        loop.close();
      }
    }
  }

  @Test
  void revocationBetweenAdmissionAndThePollClaimsNothing() throws Exception {
    var hold = outsideHours();
    var queue = mock(JobQueue.class);
    var armed = new AtomicBoolean();
    var revoked = new CountDownLatch(1);
    // The loop reads its pacing for the poll after admission and before claiming: revoke there.
    Supplier<ResolvedConfig> config = () -> {
      if (Thread.currentThread().getName().equals("indexing-loop") && armed.compareAndSet(true, false)) {
        hold.pause(null, "USER_PAUSED");
        revoked.countDown();
      }
      return null;
    };
    var loop = loop(queue, hold, config);
    try {
      loop.start();
      armed.set(true);
      readNow(hold);
      assertTrue(revoked.await(5, TimeUnit.SECONDS), "the loop was admitted under the release");
      await(() -> hold.snapshot().state() == IndexingHold.State.PAUSED, "the admission is given back");
      Thread.sleep(200);
      verify(queue, never()).pollPending(anyInt());
    } finally {
      loop.close();
    }
  }

  @Test
  void revocationAfterTheClaimFinishesTheBatchAndClaimsNoMore() throws Exception {
    var hold = outsideHours();
    var queue = mock(JobQueue.class);
    var polls = new AtomicInteger();
    var claimed = job("claimed-before-pause.txt");
    when(queue.pollPending(anyInt())).thenAnswer(ignored -> {
      if (polls.incrementAndGet() == 1) {
        hold.pause(null, "USER_PAUSED");
        return List.of(claimed);
      }
      return List.of();
    });
    var loop = loop(queue, hold, () -> null);
    try {
      loop.start();
      readNow(hold);
      await(() -> published(queue).contains(claimed.path()), "an admitted batch finishes as with Pause");
      await(() -> hold.snapshot().state() == IndexingHold.State.PAUSED, "then the loop is quiet");
      Thread.sleep(200);
      assertEquals(1, polls.get(), "Pause stops further claims");
    } finally {
      loop.close();
    }
  }

  @Test
  void revocationDuringPublicationWaitsForItAndThenTheHoldApplies() throws Exception {
    var hold = outsideHours();
    var queue = mock(JobQueue.class);
    var polls = new AtomicInteger();
    when(queue.pollPending(anyInt())).thenAnswer(ignored ->
        polls.incrementAndGet() == 1 ? List.of(job("publishing.txt")) : List.of());
    var entered = new CountDownLatch(1);
    var finish = new CountDownLatch(1);
    var calls = new CopyOnWriteArrayList<String>();
    doAnswer(ignored -> {
      calls.add("publish");
      entered.countDown();
      assertTrue(finish.await(5, TimeUnit.SECONDS));
      return null;
    }).when(queue).markDoneTransitions(anyCollection(), any());
    var loop = loop(queue, hold, () -> null);
    try {
      loop.start();
      readNow(hold);
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      hold.setPolicy(false, null, null, new IndexingSchedule(true, List.of(1, 3), 600, 660), false);
      assertEquals(IndexingHold.State.PAUSING, hold.snapshot().state(),
          "publication in progress is not quiet");
      int pollsAtRevocation = polls.get();
      finish.countDown();
      await(() -> hold.snapshot().state() == IndexingHold.State.PAUSED, "quiet after publication");
      Thread.sleep(200);
      assertEquals(pollsAtRevocation, polls.get(), "nothing more is claimed after the rule edit");
      assertEquals(List.of("publish"), calls);
    } finally {
      finish.countDown();
      loop.close();
    }
  }

  @Test
  void heavyIndexingCountsTheReleasedBacklogAsProgress() throws Exception {
    var hold = outsideHours();
    var queue = mock(JobQueue.class);
    when(queue.jobStateCounts()).thenReturn(new JobQueue.JobStateCounts(60, 60, 0, 0, 0));
    var polling = new CountDownLatch(1);
    var answer = new CountDownLatch(1);
    when(queue.pollPending(anyInt())).thenAnswer(ignored -> {
      polling.countDown();
      assertTrue(answer.await(5, TimeUnit.SECONDS));
      return List.of();
    });
    var loop = loop(queue, hold, () -> null);
    try {
      loop.start();
      await(loop::isRunning, "loop running");
      assertFalse(loop.heavyIndexing(), "held files are no progress");
      readNow(hold);
      assertTrue(polling.await(5, TimeUnit.SECONDS));
      assertTrue(loop.heavyIndexing(), "a released backlog is being read");
    } finally {
      answer.countDown();
      loop.close();
    }
  }

  @Test
  void migrationInlineEmbeddingRunsForFilesReadUnderTheRelease() throws Exception {
    var hold = outsideHours();
    var queue = mock(JobQueue.class);
    Path file = Files.writeString(directory.resolve("migration.txt"), "text read during the release");
    var claim = new JobQueue.IndexJob(file, null, null, null, null, null, false, null, file);
    var polls = new AtomicInteger();
    when(queue.pollPending(anyInt())).thenAnswer(ignored ->
        polls.incrementAndGet() == 1 ? List.of(claim) : List.of());
    doCallRealMethod().when(queue).ownsClaimForPublication(any());
    var identities = mock(io.justsearch.indexerworker.identity.DocumentIdentityStore.class);
    lenient().when(identities.resolve(anyString(), anyLong())).thenAnswer(invocation ->
        new io.justsearch.indexerworker.identity.DocumentIdentityStore.Identity(invocation.getArgument(0),
            "uid-" + invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(1)));
    var text = new io.justsearch.indexerworker.extract.ContentExtractorProvider() {
      @Override public io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult extract(Path path) {
        return new io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult(
            "text read during the release", null, "text/plain");
      }
      @Override public String detectMimeType(Path path) { return "text/plain"; }
    };
    var loop = new IndexingLoop(
        io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocr(),
        io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), queue,
        mock(IndexingCoordinator.class), mock(CommitOps.class), mock(DocumentFieldOps.class),
        mock(IndexCountOps.class), () -> null, new InProcessWorkerSignalBus(new GpuSchedulingGauge(hold)),
        IndexingPacing.unthrottled(), null, null, null, null,
        new io.justsearch.indexerworker.extract.TimeboxedContentExtractor(
            io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), text,
            java.time.Duration.ofSeconds(5), (io.justsearch.indexerworker.extract.ExtractionMetricCatalog) null),
        null, new IndexingLoopOptions(false, null, identities, null, null, null));
    var witness = io.justsearch.indexerworker.services.RootWitness.inMemory();
    assertTrue(witness.admits(directory));
    loop.useRootCheck(witness);
    var provider = mock(io.justsearch.indexerworker.embed.EmbeddingProvider.class);
    when(provider.isAvailable()).thenReturn(true);
    var embedded = new CountDownLatch(1);
    when(provider.embedDocumentBatch(anyList())).thenAnswer(invocation -> {
      embedded.countDown();
      return List.of(new float[] {1f});
    });
    loop.getEmbeddingLifecycle().setEmbeddingProvider(provider);
    var migration = IndexingLoop.class.getDeclaredField("migrationActiveSupplier");
    migration.setAccessible(true);
    migration.set(loop, (java.util.function.BooleanSupplier) () -> true);
    try {
      loop.start();
      readNow(hold);
      assertTrue(embedded.await(5, TimeUnit.SECONDS),
          "a released file is read as in reading hours, migration batch embedding included");
    } finally {
      loop.close();
    }
  }
}
