/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.engine.EngineExit;
import io.justsearch.app.engine.EngineShutdownSequence;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(10)
final class HeadlessAppComponentEscalationTest {
  @Test
  void waitsForTheBoundSequencePublishesBeforeCloseAndExitsFiveOnce(@TempDir Path dataDir)
      throws Exception {
    var binding = new CompletableFuture<EngineShutdownSequence>();
    var publishing = new CountDownLatch(1);
    var allowPublication = new CountDownLatch(1);
    var exited = new CountDownLatch(1);
    var closes = new AtomicInteger();
    var exitCode = new AtomicInteger(-1);
    var halts = new AtomicInteger();
    var launches = new AtomicInteger();
    var launchedThread = new AtomicReference<Thread>();
    var backgroundFailure = new AtomicReference<Throwable>();
    Runnable escalation = HeadlessApp.componentEscalationAction(binding, reason -> {
      assertEquals("restart", reason.wire());
      publishing.countDown();
      assertTrue(allowPublication.await(3, TimeUnit.SECONDS));
      return null;
    }, code -> halts.incrementAndGet(), action -> {
      launches.incrementAndGet();
      Thread thread = new Thread(() -> {
        try {
          action.run();
        } catch (Throwable failure) {
          backgroundFailure.set(failure);
        }
      }, "test-component-escalation");
      launchedThread.set(thread);
      thread.start();
    });

    try {
      escalation.run();
      escalation.run();
      assertEquals(1, launches.get(), "duplicate escalation must dispatch only once");
      assertFalse(publishing.await(100, TimeUnit.MILLISECONDS),
          "escalation must wait for the shutdown sequence without blocking its caller");

      binding.complete(new EngineShutdownSequence(dataDir, List.of(
          new EngineShutdownSequence.Step(EngineShutdownSequence.INDEX_HALF_STEP, reason -> {
            assertEquals(0, allowPublication.getCount(), "handoff must precede ordered close");
            closes.incrementAndGet();
            return "GRACEFUL";
          })), code -> {
            exitCode.set(code);
            exited.countDown();
          }));
      assertTrue(publishing.await(2, TimeUnit.SECONDS));
      assertEquals(0, closes.get());
    } finally {
      if (!binding.isDone()) {
        binding.completeExceptionally(new IllegalStateException("test cleanup"));
      }
      allowPublication.countDown();
      Thread thread = launchedThread.get();
      if (thread != null) {
        thread.join(3_000);
        assertFalse(thread.isAlive(), "component escalation action did not finish");
      }
    }

    assertNull(backgroundFailure.get(), "component escalation action failed");
    assertTrue(exited.await(2, TimeUnit.SECONDS));
    assertEquals(EngineExit.ESCALATED_RESTART, exitCode.get());
    assertEquals(1, closes.get());
    assertEquals(0, halts.get());
  }

  @Test
  void uncleanOrderedCloseSelectsFatalOneInsteadOfEscalatedFive(@TempDir Path dataDir)
      throws Exception {
    var exited = new CountDownLatch(1);
    var exitCode = new AtomicInteger(-1);
    var closes = new AtomicInteger();
    var sequence = new EngineShutdownSequence(dataDir, List.of(
        new EngineShutdownSequence.Step(EngineShutdownSequence.INDEX_HALF_STEP, reason -> {
          closes.incrementAndGet();
          throw new IOException("index close refused");
        })), code -> {
          exitCode.set(code);
          exited.countDown();
        });

    HeadlessApp.componentEscalationAction(CompletableFuture.completedFuture(sequence),
        reason -> null, code -> { throw new AssertionError("fatal halt must not be used"); },
        Runnable::run).run();

    assertTrue(exited.await(2, TimeUnit.SECONDS));
    assertEquals(EngineExit.FATAL_OR_UNCAUGHT, exitCode.get());
    assertEquals(1, closes.get());
  }

  @Test
  void publicationFailureHaltsFatallyWithoutEnteringOrderedClose(@TempDir Path dataDir) {
    var halted = new CountDownLatch(1);
    var fatalCode = new AtomicInteger(-1);
    var closes = new AtomicInteger();
    var sequence = new EngineShutdownSequence(dataDir, List.of(
        new EngineShutdownSequence.Step("must-not-enter", reason -> {
          closes.incrementAndGet();
          return "GRACEFUL";
        })), code -> { throw new AssertionError("ordered exit must not run"); });

    HeadlessApp.componentEscalationAction(CompletableFuture.completedFuture(sequence),
        reason -> { throw new IOException("manifest replacement refused"); },
        code -> { fatalCode.set(code); halted.countDown(); }, Runnable::run).run();

    assertEquals(0, halted.getCount());
    assertEquals(EngineExit.FATAL_OR_UNCAUGHT, fatalCode.get());
    assertEquals(0, closes.get());
  }

  @Test
  void dispatchFailureHaltsFatallyWithoutStartingTheSequence(@TempDir Path dataDir) {
    var halted = new CountDownLatch(1);
    var fatalCode = new AtomicInteger(-1);
    var closes = new AtomicInteger();
    var sequence = new EngineShutdownSequence(dataDir, List.of(
        new EngineShutdownSequence.Step("must-not-enter", reason -> {
          closes.incrementAndGet();
          return "GRACEFUL";
        })), code -> { throw new AssertionError("ordered exit must not run"); });

    HeadlessApp.componentEscalationAction(CompletableFuture.completedFuture(sequence),
        reason -> null, code -> { fatalCode.set(code); halted.countDown(); },
        action -> { throw new OutOfMemoryError("unable to create native thread"); }).run();

    assertEquals(0, halted.getCount());
    assertEquals(EngineExit.FATAL_OR_UNCAUGHT, fatalCode.get());
    assertEquals(0, closes.get());
  }

  @Test
  void exceptionalStartupBindingDoesNotCompeteWithStartupFatalExit() {
    var halted = new AtomicInteger();
    var published = new AtomicInteger();
    var sequence = new CompletableFuture<EngineShutdownSequence>();
    sequence.completeExceptionally(new IOException("startup composition failed"));

    HeadlessApp.componentEscalationAction(sequence,
        reason -> { published.incrementAndGet(); return null; },
        code -> halted.incrementAndGet(), Runnable::run).run();

    assertEquals(0, published.get());
    assertEquals(0, halted.get());
  }
}
