/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real writer/reader proof without a native-volume dependency. */
@Timeout(20)
final class NativeOwnershipInspectionTest extends RuntimeTestBase {
  private void index(RunningRuntime runtime, String path) {
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, path, SchemaFields.DOC_UID, path + "#0",
        SchemaFields.PATH, path, SchemaFields.CONTENT, "ordinary source")));
  }

  private long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(5); }

  @Test
  void bufferedWriteDuringEveryInspectionCannotStarveOwnershipPreparation() throws Exception {
    try (var runtime = createRuntimeWithDim(3); var writer = Executors.newSingleThreadExecutor()) {
      String target = tempDir.resolve("target.txt").toString();
      index(runtime, target);
      for (int attempt = 0; attempt < 3; attempt++) {
        var submitted = new AtomicBoolean();
        var write = new AtomicReference<Future<?>>();
        int ordinal = attempt;
        assertDoesNotThrow(() -> runtime.readPathOps().inspectNativeOwnership(Set.of(target), id -> {
          if (submitted.compareAndSet(false, true)) {
            var started = new CountDownLatch(1);
            write.set(writer.submit(() -> {
              started.countDown();
              index(runtime, tempDir.resolve("buffered-" + ordinal).toString());
            }));
            try {
              assertTrue(started.await(2, TimeUnit.SECONDS));
              // Before repair the write completes inside every inspection and invalidates it.
              // Under the existing writer exclusion it waits until inspection releases ownership.
              write.get().get(100, TimeUnit.MILLISECONDS);
            } catch (TimeoutException excluded) {
              // The writer is excluded, not failed; require its actual completion below.
            } catch (Exception failure) { throw new AssertionError(failure); }
          }
          return false;
        }, deadline()), "ongoing buffered writes must not invalidate every inspection");
        assertTrue(submitted.get());
        write.get().get(2, TimeUnit.SECONDS);
      }
      var stable = runtime.readPathOps().inspectNativeOwnership(Set.of(target), id -> false, deadline());
      assertTrue(stable.ordinary(target));
      assertFalse(stable.absent(target));
    }
  }

  @Test
  void subsequentWritesAndReentrantOwnerWritesStillInvalidateTheProof() throws Exception {
    try (var runtime = createRuntimeWithDim(3)) {
      String target = tempDir.resolve("target.txt").toString();
      index(runtime, target);
      runtime.commitOps().maybeRefreshBlocking();
      var prior = runtime.readPathOps().inspectNativeOwnership(Set.of(target), id -> false, deadline());
      assertTrue(prior.ordinary(target));
      index(runtime, tempDir.resolve("later.txt").toString());
      assertFalse(prior.ordinary(target), "never decide using a stale writer sequence");
      var mutated = new AtomicBoolean();
      assertThrows(IOException.class, () -> runtime.readPathOps().inspectNativeOwnership(Set.of(target), id -> {
        if (mutated.compareAndSet(false, true)) index(runtime, tempDir.resolve("owner-replay.txt").toString());
        return false;
      }, deadline()), "a reentrant owner write must still fail closed");
    }
  }

  @Test
  void writerExclusionWaitIsBoundedAndInterruptionFailsClosed() throws Exception {
    try (var runtime = createRuntimeWithDim(3); var writer = Executors.newSingleThreadExecutor()) {
      String target = tempDir.resolve("target.txt").toString(); index(runtime, target);
      var held = new CountDownLatch(1); var release = new CountDownLatch(1);
      var lock = runtime.session().writeBarrier.writeLock();
      var blocker = writer.submit(() -> {
        lock.lock();
        try { held.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        finally { lock.unlock(); }
      });
      assertTrue(held.await(2, TimeUnit.SECONDS));
      try {
        assertThrows(IOException.class, () -> runtime.readPathOps().inspectNativeOwnership(Set.of(target),
            id -> false, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100)));
        Thread.currentThread().interrupt();
        assertThrows(IOException.class, () -> runtime.readPathOps().inspectNativeOwnership(Set.of(target), id -> false, deadline()));
        assertTrue(Thread.currentThread().isInterrupted());
      } finally { Thread.interrupted(); release.countDown(); }
      blocker.get(2, TimeUnit.SECONDS);
    }
  }
}
