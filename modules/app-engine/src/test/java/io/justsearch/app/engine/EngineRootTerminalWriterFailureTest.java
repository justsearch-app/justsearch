/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.adapters.lucene.runtime.SwapReason;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.coordination.InProcessWorkerSignalBus;
import io.justsearch.indexerworker.server.KnowledgeServer;
import io.justsearch.indexerworker.util.IndexRootLock;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(180)
final class EngineRootTerminalWriterFailureTest {

  @Test
  void activeReloadPreventsTeardownAndRetryClosesItsPublishedGeneration(@TempDir Path tempDir)
      throws Exception {
    Started started = start(tempDir, ignored -> {});
    var field = KnowledgeServer.class.getDeclaredField("ingestLifecycle");
    field.setAccessible(true);
    var old = (RunningRuntime) field.get(started.server());
    var openerEntered = new CountDownLatch(1);
    var releaseOpener = new CountDownLatch(1);
    var fresh = new AtomicReference<RunningRuntime>();
    var swapFailure = new AtomicReference<Throwable>();
    Thread swap = Thread.ofPlatform().start(() -> {
      try {
        started.server().swapRuntime(() -> {
          openerEntered.countDown();
          boolean interrupted = false;
          while (releaseOpener.getCount() != 0) {
            try { releaseOpener.await(); }
            catch (InterruptedException expected) { interrupted = true; }
          }
          if (interrupted) Thread.currentThread().interrupt();
          var replacement = old.origin().open();
          fresh.set(replacement);
          return replacement;
        }, Duration.ofSeconds(5), SwapReason.UNKNOWN);
      } catch (Throwable failure) { swapFailure.set(failure); }
    });
    try {
      assertTrue(openerEntered.await(5, TimeUnit.SECONDS));
      long startedClose = System.nanoTime();
      var failure = assertThrows(IllegalStateException.class, started.root()::close);
      assertTrue(failure.getCause().getMessage().contains("Active runtime replacement"));
      assertTrue(System.nanoTime() - startedClose < TimeUnit.SECONDS.toNanos(7));
      assertFalse(started.server().awaitClosed(0));
      try (var competing = new IndexRootLock(tempDir.resolve("data/index"))) {
        assertThrows(IOException.class, competing::acquire);
      }
      assertTimeoutPreemptively(Duration.ofMillis(500), () ->
          assertThrows(IllegalStateException.class, () -> started.server().swapRuntime(
              () -> { throw new AssertionError("a later reload must not invoke its opener"); },
              Duration.ofSeconds(1), SwapReason.UNKNOWN)));
      releaseOpener.countDown();
      swap.join(5_000);
      assertFalse(swap.isAlive());
      assertNull(swapFailure.get());
      assertSame(fresh.get(), field.get(started.server()));
    } finally {
      releaseOpener.countDown();
      swap.join(5_000);
      started.root().close();
    }
    assertTrue(started.server().awaitClosed(0));
    assertThrows(IllegalStateException.class, () -> fresh.get().taskLifetime().retain());
    try (var competing = new IndexRootLock(tempDir.resolve("data/index"))) {
      competing.acquire();
    }
  }

  @Test
  void incompleteRuntimeCloseRetainsServerAndIndexLockUntilRetry(@TempDir Path tempDir) throws Exception {
    Started started = start(tempDir, ignored -> {});
    var field = KnowledgeServer.class.getDeclaredField("ingestLifecycle");
    field.setAccessible(true);
    var runtime = (RunningRuntime) field.get(started.server());
    Runnable release = runtime.taskLifetime().retain();
    try {
      var failure = assertThrows(IllegalStateException.class, started.root()::close);
      assertTrue(failure.getCause().getCause().getMessage().contains("generation owners still active"),
          "the held generation must be the reason shutdown remains incomplete");
      assertFalse(started.server().awaitClosed(0), "an incomplete close must not publish completion");
      try (var competing = new IndexRootLock(tempDir.resolve("data/index"))) {
        assertThrows(IOException.class, competing::acquire, "the enclosing index lock must remain held");
      }
      assertThrows(IOException.class,
          () -> started.root().start(new GpuSchedulingGauge(), IpcTelemetry.noop()),
          "a new server cannot replace the retained close owner");
    } finally {
      release.run();
      started.root().close();
    }
    assertTrue(started.server().awaitClosed(0), "retry must close the original server");
    try (var competing = new IndexRootLock(tempDir.resolve("data/index"))) {
      competing.acquire();
    }
    started.root().start(new GpuSchedulingGauge(), IpcTelemetry.noop());
    started.root().close();
  }

  @Test
  void startedServerOwnsOneDedicatedTransientExit(@TempDir Path tempDir) throws Exception {
    AtomicInteger exits = new AtomicInteger();
    AtomicReference<String> exitThread = new AtomicReference<>();
    CountDownLatch exited = new CountDownLatch(1);
    Started started =
        start(
            tempDir,
            code -> {
              assertEquals(EngineExit.FATAL_OR_UNCAUGHT, code);
              exits.incrementAndGet();
              exitThread.set(Thread.currentThread().getName());
              exited.countDown();
            });
    try {
      Consumer<Throwable> productionForwarder = terminalWriterForwarder(started.server());

      productionForwarder.accept(new IllegalStateException("writer closed"));
      productionForwarder.accept(new IllegalStateException("duplicate"));

      assertTrue(exited.await(2, TimeUnit.SECONDS), "the active source must dispatch process exit");
      assertEquals(1, exits.get(), "one Engine may accept only one terminal writer fault");
      assertEquals("engine-terminal-writer-exit", exitThread.get());
    } finally {
      started.root().close();
    }
  }

  @Test
  void replacementServerRejectsStaleForwarderAndAcceptsCurrentForwarder(@TempDir Path tempDir)
      throws Exception {
    AtomicInteger exits = new AtomicInteger();
    CountDownLatch exited = new CountDownLatch(1);
    Started started =
        start(
            tempDir,
            ignored -> {
              exits.incrementAndGet();
              exited.countDown();
            });
    Consumer<Throwable> staleForwarder = terminalWriterForwarder(started.server());

    started.root().close();
    started.root().start(new GpuSchedulingGauge(), IpcTelemetry.noop());
    Consumer<Throwable> currentForwarder = terminalWriterForwarder(started.server());
    staleForwarder.accept(new IllegalStateException("late observation from closed server"));

    assertFalse(exited.await(500, TimeUnit.MILLISECONDS));
    assertEquals(0, exits.get(), "the replaced server cannot terminate the current Engine");

    currentForwarder.accept(new IllegalStateException("current writer closed"));
    assertTrue(exited.await(2, TimeUnit.SECONDS));
    assertEquals(1, exits.get());
    started.root().close();
  }

  private static Started start(Path tempDir, IntConsumer exitAction) throws Exception {
    Path dataDir = tempDir.resolve("data");
    EngineTestHarness.publishConfig(dataDir, dataDir.resolve("index"), Map.of());
    KnowledgeServer[] server = new KnowledgeServer[1];
    EngineRoot root =
        new EngineRoot(
            gauge -> {
              server[0] =
                  new KnowledgeServer(new io.justsearch.core.execution.TestEngineExecutors(),
                      WorkerConfig.load(), new InProcessWorkerSignalBus(gauge));
              return server[0];
            },
            30_000L,
            5_000,
            exitAction);
    root.start(new GpuSchedulingGauge(), IpcTelemetry.noop());
    return new Started(root, server);
  }

  private static Consumer<Throwable> terminalWriterForwarder(KnowledgeServer server)
      throws Exception {
    Field field = KnowledgeServer.class.getDeclaredField("terminalWriterFaultHandler");
    field.setAccessible(true);
    Object forwarder = field.get(server);
    Method accept = Consumer.class.getMethod("accept", Object.class);
    return failure -> {
      try {
        accept.invoke(forwarder, failure);
      } catch (ReflectiveOperationException e) {
        throw new AssertionError("production forwarder invocation failed", e);
      }
    };
  }

  private record Started(EngineRoot root, KnowledgeServer[] servers) {
    KnowledgeServer server() {
      return servers[0];
    }
  }
}
