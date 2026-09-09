/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.lucene.search.ControlledRealTimeReopenThread;
import org.apache.lucene.search.IndexSearcher;
import org.junit.jupiter.api.Test;

final class RuntimeSessionNrtLifetimeTest extends RuntimeTestBase {
  @Test
  void interruptedNrtJoinKeepsWriterAliveUntilThreadActuallyExits() throws Exception {
    var entered = new CountDownLatch(1);
    var closingNrt = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var finished = new CompletableFuture<Void>();
    var writerOpenOnExit = new AtomicBoolean();
    var interruptRestored = new AtomicBoolean();
    try (var runtime = buildSchemaWithDim(4).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open()) {
      var session = new LifecycleTestAccessor(runtime).session();
      var snap = session.snapshot;
      session.crtrt.close();
      var heldNrt = new ControlledRealTimeReopenThread<IndexSearcher>(
          snap.writer(), snap.searcherManager(), 1.0, 0.1) {
        @Override public void run() {
          entered.countDown();
          while (release.getCount() != 0) {
            try { release.await(); }
            catch (InterruptedException ignored) { /* actual exit owns Lucene release */ }
          }
          writerOpenOnExit.set(snap.writer().isOpen());
        }
        @Override public synchronized void close() {
          closingNrt.countDown();
          super.close();
        }
      };
      session.crtrt = heldNrt;
      var closer = new Thread(() -> {
        try {
          runtime.close();
          interruptRestored.set(Thread.currentThread().isInterrupted());
          finished.complete(null);
        } catch (Throwable failure) { finished.completeExceptionally(failure); }
      }, "interrupted-nrt-close");
      try {
        heldNrt.start();
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        closer.start();
        assertTrue(closingNrt.await(3, TimeUnit.SECONDS));
        closer.interrupt();
        assertThrows(TimeoutException.class, () -> finished.get(150, TimeUnit.MILLISECONDS));
        assertTrue(snap.writer().isOpen(), "NRT thread may still use the writer");
        assertSame(heldNrt, session.crtrt, "the live thread must remain owned");
        release.countDown();
        finished.get(3, TimeUnit.SECONDS);
        assertTrue(writerOpenOnExit.get());
        assertFalse(heldNrt.isAlive());
        assertFalse(snap.writer().isOpen());
        assertNull(session.crtrt);
        assertTrue(interruptRestored.get());
      } finally {
        release.countDown();
        heldNrt.join(3000);
        if (closer.getState() != Thread.State.NEW) closer.join(3000);
      }
    }
  }
}
