/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.engine.EngineShutdownSequence;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(10)
final class HeadlessAppMigrationRestartTest {
  @Test
  void dispatchIsAsyncAndPublishesBeforeCloseExactlyOnce(@TempDir Path dataDir) throws Exception {
    var binding = new CompletableFuture<EngineShutdownSequence>();
    var publishing = new CountDownLatch(1);
    var allowPublication = new CountDownLatch(1);
    var exited = new CountDownLatch(1);
    var closes = new AtomicInteger();
    var exitCode = new AtomicInteger(-1);
    var halts = new AtomicInteger();
    Runnable restart = HeadlessApp.localRestartAction(binding, reason -> {
      assertEquals("restart", reason.wire());
      publishing.countDown();
      assertTrue(allowPublication.await(3, TimeUnit.SECONDS));
      return null;
    }, code -> halts.incrementAndGet());
    restart.run();
    restart.run();
    assertFalse(publishing.await(100, TimeUnit.MILLISECONDS), "wait for composition off caller thread");
    binding.complete(new EngineShutdownSequence(dataDir, List.of(
        new EngineShutdownSequence.Step("close", reason -> {
          assertEquals(0, allowPublication.getCount(), "handoff must precede blocking teardown");
          closes.incrementAndGet();
          return null;
        })), code -> { exitCode.set(code); exited.countDown(); }));
    try {
      assertTrue(publishing.await(2, TimeUnit.SECONDS));
      assertEquals(0, closes.get());
    } finally {
      allowPublication.countDown();
    }
    assertTrue(exited.await(2, TimeUnit.SECONDS));
    assertEquals(4, exitCode.get());
    assertEquals(1, closes.get());
    assertEquals(0, halts.get());
  }

  @Test
  void failedHandoffHaltsFatallyWithoutEnteringUnboundedClose(@TempDir Path dataDir) throws Exception {
    var halted = new CountDownLatch(1);
    var closes = new AtomicInteger();
    var gracefulExits = new AtomicInteger();
    var fatalCode = new AtomicInteger(-1);
    var sequence = new EngineShutdownSequence(dataDir, List.of(
        new EngineShutdownSequence.Step("must-not-enter", reason -> {
          closes.incrementAndGet();
          return null;
        })), code -> gracefulExits.incrementAndGet());
    Runnable restart = HeadlessApp.localRestartAction(CompletableFuture.completedFuture(sequence),
        reason -> { throw new IOException("manifest replacement refused"); },
        code -> { fatalCode.set(code); halted.countDown(); });
    restart.run();
    assertTrue(halted.await(2, TimeUnit.SECONDS));
    assertEquals(1, fatalCode.get());
    assertEquals(0, closes.get());
    assertEquals(0, gracefulExits.get());
  }
  @Test
  void missingManifestCannotPretendToPublishAHandoff(@TempDir Path dataDir) throws Exception {
    var publisher = new io.justsearch.ui.runtime.RuntimeManifestPublisher(dataDir);
    var halted = new CountDownLatch(1);
    var closes = new AtomicInteger();
    var sequence = new EngineShutdownSequence(dataDir, List.of(
        new EngineShutdownSequence.Step("must-not-enter", reason -> {
          closes.incrementAndGet();
          return null;
        })), code -> { throw new AssertionError("unexpected graceful exit"); });
    try {
      HeadlessApp.localRestartAction(CompletableFuture.completedFuture(sequence), reason -> {
        publisher.markShutdownPending(reason.wire());
        return null;
      }, code -> { assertEquals(1, code); halted.countDown(); }).run();
      assertTrue(halted.await(2, TimeUnit.SECONDS));
      assertEquals(0, closes.get());
    } finally {
      publisher.close();
    }
  }

  @Test
  void nativeThreadExhaustionCannotLoseAnAcceptedRestart() {
    var halts = new AtomicInteger();
    Runnable restart = HeadlessApp.localRestartAction(new CompletableFuture<>(),
        reason -> { throw new AssertionError("publication must not run"); },
        code -> { assertEquals(1, code); halts.incrementAndGet(); },
        action -> { throw new OutOfMemoryError("unable to create native thread"); });
    restart.run();
    assertEquals(1, halts.get());
  }

}
