/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.feedback;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.core.execution.TestEngineExecutors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class FeedbackObserverTest {
  @Test
  void blockedCaptureDoesNotBlockSubmitterAndOverflowDropsNewest() throws Exception {
    try (var registry = TestEngineExecutors.awaitingTermination();
        var observer = new FeedbackObserver(registry)) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var drained = new CountDownLatch(32);
      var writer = new AtomicReference<Thread>();
      var droppedRan = new AtomicBoolean();
      try {
        assertTrue(observer.observe(() -> {
          writer.set(Thread.currentThread());
          entered.countDown();
          try {
            release.await();
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
        }));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertNotSame(Thread.currentThread(), writer.get());
        for (int i = 0; i < 32; i++) assertTrue(observer.observe(drained::countDown));
        assertFalse(observer.observe(() -> droppedRan.set(true)));
      } finally {
        release.countDown();
      }
      assertTrue(drained.await(2, TimeUnit.SECONDS));
      assertFalse(droppedRan.get());
      observer.close();
      assertFalse(observer.observe(() -> droppedRan.set(true)));
    }
  }
}
