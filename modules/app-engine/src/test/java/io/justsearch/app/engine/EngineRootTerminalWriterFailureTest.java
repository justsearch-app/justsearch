/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.coordination.InProcessWorkerSignalBus;
import io.justsearch.indexerworker.server.KnowledgeServer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
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
                  new KnowledgeServer(
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
