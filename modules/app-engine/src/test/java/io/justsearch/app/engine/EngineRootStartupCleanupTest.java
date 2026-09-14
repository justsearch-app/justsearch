/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.services.bootstrap.OperationAuthority;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.server.KnowledgeServer;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Startup cleanup proof for EngineRoot's retained KnowledgeServer owner. */
final class EngineRootStartupCleanupTest {

  @Test
  void failedCloseRetainsOwnerUntilRetryThenAllowsAnotherServer() throws Exception {
    KnowledgeServer failed = mockServer();
    IOException startFailure = new IOException("startup failure");
    doThrow(startFailure).when(failed).start();
    when(failed.awaitClosed(anyLong())).thenReturn(false, true);
    doThrow(new IOException("close incomplete")).doNothing().when(failed).close();

    KnowledgeServer replacement = mockServer();
    AtomicInteger factoryCalls = new AtomicInteger();
    EngineRoot root = root((gauge, executors, ingestion) -> {
      if (factoryCalls.getAndIncrement() == 0) return failed;
      EngineRootRecordedLifecycleTestSupport.bindOffline(replacement, ingestion);
      return replacement;
    });
    try {
      IOException observed = assertThrows(IOException.class,
          () -> root.start(new GpuSchedulingGauge(), IpcTelemetry.noop()));
      assertSame(startFailure, observed, "the original startup failure remains visible");
      assertThrows(IOException.class,
          () -> root.start(new GpuSchedulingGauge(), IpcTelemetry.noop()),
          "an incompletely cleaned server remains the start owner");
      assertEquals(1, factoryCalls.get(), "retained owner prevents a replacement factory call");

      assertThrows(IllegalStateException.class, root::close,
          "a failed close keeps the owner for a later close retry");
      root.close();
      root.start(new GpuSchedulingGauge(), IpcTelemetry.noop());
      assertEquals(2, factoryCalls.get(), "successful close permits a replacement server");
      root.close();
    } finally {
      root.executors().close();
    }
  }

  @Test
  void alreadyCleanedStartupFailureClearsOwnerAndCanRetry() throws Exception {
    KnowledgeServer failed = mockServer();
    IOException startFailure = new IOException("already cleaned");
    doThrow(startFailure).when(failed).start();
    when(failed.awaitClosed(anyLong())).thenReturn(true);

    KnowledgeServer replacement = mockServer();
    AtomicInteger factoryCalls = new AtomicInteger();
    EngineRoot root = root((gauge, executors, ingestion) -> {
      if (factoryCalls.getAndIncrement() == 0) return failed;
      EngineRootRecordedLifecycleTestSupport.bindOffline(replacement, ingestion);
      return replacement;
    });
    try {
      assertSame(startFailure, assertThrows(IOException.class,
          () -> root.start(new GpuSchedulingGauge(), IpcTelemetry.noop())));
      root.start(new GpuSchedulingGauge(), IpcTelemetry.noop());
      assertEquals(2, factoryCalls.get(), "confirmed cleanup permits a retry");
      root.close();
    } finally {
      root.executors().close();
    }
  }

  @Test
  void fatalStartupErrorIdentityIsPreservedAndOwnerIsRetainedWhenUnclosed() throws Exception {
    KnowledgeServer failed = mockServer();
    AssertionError fatal = new AssertionError("fatal startup");
    doThrow(fatal).when(failed).start();
    when(failed.awaitClosed(anyLong())).thenReturn(false, true);

    AtomicInteger factoryCalls = new AtomicInteger();
    EngineRoot root = root((gauge, executors, ingestion) -> {
      factoryCalls.incrementAndGet();
      return failed;
    });
    try {
      assertSame(fatal, assertThrows(AssertionError.class,
          () -> root.start(new GpuSchedulingGauge(), IpcTelemetry.noop())));
      assertThrows(IOException.class,
          () -> root.start(new GpuSchedulingGauge(), IpcTelemetry.noop()),
          "fatal startup failure still retains an unclosed server owner");
      assertEquals(1, factoryCalls.get());
      root.close();
    } finally {
      root.executors().close();
    }
  }

  @Test
  void interruptedCleanupRestoresInterruptAndRetainsOwner() throws Exception {
    Thread.interrupted();
    KnowledgeServer failed = mockServer();
    IOException startFailure = new IOException("interrupted cleanup");
    doThrow(startFailure).when(failed).start();
    when(failed.awaitClosed(anyLong()))
        .thenThrow(new InterruptedException("cleanup interrupted"))
        .thenReturn(true);

    AtomicInteger factoryCalls = new AtomicInteger();
    EngineRoot root = root((gauge, executors, ingestion) -> {
      factoryCalls.incrementAndGet();
      return failed;
    });
    try {
      assertSame(startFailure, assertThrows(IOException.class,
          () -> root.start(new GpuSchedulingGauge(), IpcTelemetry.noop())));
      assertTrue(Thread.currentThread().isInterrupted(),
          "interrupted cleanup must restore the caller interrupt flag");
      Thread.interrupted();
      assertEquals(1, startFailure.getSuppressed().length);
      assertTrue(startFailure.getSuppressed()[0] instanceof InterruptedException);
      assertThrows(IOException.class,
          () -> root.start(new GpuSchedulingGauge(), IpcTelemetry.noop()),
          "interrupted cleanup retains the server owner");
      assertEquals(1, factoryCalls.get());
      root.close();
    } finally {
      Thread.interrupted();
      root.executors().close();
    }
  }

  private static KnowledgeServer mockServer() throws Exception {
    KnowledgeServer server = mock(KnowledgeServer.class);
    when(server.foregroundLoad()).thenReturn(new ForegroundLoad());
    doNothing().when(server).close();
    when(server.awaitClosed(anyLong())).thenReturn(true);
    return server;
  }

  private static EngineRoot root(EngineRoot.ServerFactory factory) {
    return new EngineRoot(mock(OperationStore.class), mock(OperationAttemptRunner.class), factory,
        1_000, 100, ignored -> {}, () -> {}, OperationAuthority.inMemory());
  }
}
