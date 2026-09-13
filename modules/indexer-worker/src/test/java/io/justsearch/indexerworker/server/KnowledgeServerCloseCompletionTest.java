/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Stage-A checkpoint (re-review) — {@code awaitClosed} answers a question that has two answers.
 *
 * <p><b>The bug this test exists because of.</b> The re-review found the "production consumer" added
 * for {@code isRunning()} was vacuous: {@code EngineRoot.close()} warned if {@code s.isRunning()}
 * was still true after {@code s.close()} returned, but {@code close()} sets {@code running = false}
 * in its first line and {@code isRunning()} is {@code running && latch > 0}, so the predicate was
 * constant-false at that point and the warning could never fire. A check that cannot fail was added
 * in the course of removing checks that could not fail.
 *
 * <p>What this test pins is the two-state property: the predicate is FALSE on a server that has not
 * completed a close and TRUE on one that has. That is precisely what the old read lacked, and
 * without both directions asserted a future refactor could quietly restore a constant-valued
 * predicate — which is what happened once already.
 *
 * <p>The partial-failure case is exercised across the real enclosing owners by
 * app-engine's EngineRootTerminalWriterFailureTest: a held Lucene generation times out close,
 * keeps this latch false and the index lock held, then a retry releases both after actual exit.
 */
@DisplayName("KnowledgeServer.awaitClosed — close completion is observable")
final class KnowledgeServerCloseCompletionTest {

  @BeforeAll
  static void ensureGlobalConfig() {
    if (ConfigStore.globalOrNull() == null) {
      ConfigStore.setGlobal(new ConfigStore(ResolvedConfig.builder().contributeEnvRegistry().build()));
    }
  }

  @Test
  void failedIndexLockCloseRetainsOwnerAndShutdownRemainsIncomplete(@TempDir Path tempDir) throws Exception {
    var server = new KnowledgeServer(new io.justsearch.core.execution.TestEngineExecutors(),
        WorkerBootFixture.workerConfig(tempDir.resolve("data")), null);
    var rootLock = org.mockito.Mockito.mock(io.justsearch.indexerworker.util.IndexRootLock.class);
    var field = KnowledgeServer.class.getDeclaredField("indexRootLock");
    field.setAccessible(true);
    field.set(server, rootLock);
    var failure = new java.io.UncheckedIOException(new java.io.IOException("native close uncertain"));
    org.mockito.Mockito.doThrow(failure).doNothing().when(rootLock).close();
    try {
      org.junit.jupiter.api.Assertions.assertSame(failure,
          org.junit.jupiter.api.Assertions.assertThrows(java.io.UncheckedIOException.class, server::close));
      assertFalse(server.awaitClosed(0));
      org.junit.jupiter.api.Assertions.assertSame(rootLock, field.get(server));
    } finally {
      server.close();
    }
    assertTrue(server.awaitClosed(0));
    org.junit.jupiter.api.Assertions.assertNull(field.get(server));
    org.mockito.Mockito.verify(rootLock, org.mockito.Mockito.times(2)).close();
  }

  @Test
  void shutdownAttemptsBothFailedServiceOwnersBeforeReportingIncomplete(@TempDir Path tempDir)
      throws Exception {
    KnowledgeServer server = org.mockito.Mockito.spy(new KnowledgeServer(
        new io.justsearch.core.execution.TestEngineExecutors(),
        WorkerBootFixture.workerConfig(tempDir.resolve("data")), null));
    var old = org.mockito.Mockito.mock(WorkerAppServices.class);
    var candidate = org.mockito.Mockito.mock(DefaultWorkerAppServices.class,
        org.mockito.Mockito.RETURNS_DEEP_STUBS);
    var release = new java.util.concurrent.atomic.AtomicBoolean();
    org.mockito.Mockito.doReturn(candidate).when(server).newAppServices();
    org.mockito.Mockito.doAnswer(call -> {
      if (!release.get()) throw new java.io.IOException("incumbent still live");
      return null;
    }).when(old).close();
    org.mockito.Mockito.doAnswer(call -> {
      if (!release.get()) throw new java.io.IOException("candidate still live");
      return null;
    }).when(candidate).close();
    server.appServices = old;
    var reconstruct = KnowledgeServer.class.getDeclaredMethod("reconstructAppServicesAfterDeferredUpgrade");
    reconstruct.setAccessible(true);
    try {
      org.junit.jupiter.api.Assertions.assertThrows(java.lang.reflect.InvocationTargetException.class,
          () -> reconstruct.invoke(server));
      var failure = org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, server::close);
      assertTrue(failure.getCause().getSuppressed().length > 0, "both failures must be retained");
      org.mockito.Mockito.verify(old, org.mockito.Mockito.times(2)).close();
      org.mockito.Mockito.verify(candidate, org.mockito.Mockito.times(2)).close();
      assertFalse(server.awaitClosed(0));
    } finally {
      release.set(true);
      server.close();
    }
    assertTrue(server.awaitClosed(0));
  }

  @Test
  void replacementRetainsFailedIncumbentAndFailedRollbackUntilRetry(@TempDir Path tempDir)
      throws Exception {
    KnowledgeServer server = org.mockito.Mockito.spy(new KnowledgeServer(
        new io.justsearch.core.execution.TestEngineExecutors(),
        WorkerBootFixture.workerConfig(tempDir.resolve("data")), null));
    var old = org.mockito.Mockito.mock(WorkerAppServices.class);
    var discarded = org.mockito.Mockito.mock(DefaultWorkerAppServices.class,
        org.mockito.Mockito.RETURNS_DEEP_STUBS);
    var replacement = org.mockito.Mockito.mock(DefaultWorkerAppServices.class,
        org.mockito.Mockito.RETURNS_DEEP_STUBS);
    org.mockito.Mockito.doReturn(discarded, replacement).when(server).newAppServices();
    org.mockito.Mockito.doThrow(new java.io.IOException("incumbent OCR live"))
        .doNothing().when(old).close();
    org.mockito.Mockito.doThrow(new java.io.IOException("rollback OCR live"))
        .doNothing().when(discarded).close();
    server.appServices = old;
    var reconstruct = KnowledgeServer.class.getDeclaredMethod("reconstructAppServicesAfterDeferredUpgrade");
    reconstruct.setAccessible(true);
    try {
      var failed = org.junit.jupiter.api.Assertions.assertThrows(java.lang.reflect.InvocationTargetException.class,
          () -> reconstruct.invoke(server));
      assertTrue(failed.getCause() instanceof IllegalStateException);
      org.junit.jupiter.api.Assertions.assertSame(old, server.appServices());
      org.mockito.Mockito.verify(discarded, org.mockito.Mockito.never()).startIndexingLoop();
      reconstruct.invoke(server);
      org.junit.jupiter.api.Assertions.assertSame(replacement, server.appServices());
      var order = org.mockito.Mockito.inOrder(discarded, old, replacement);
      order.verify(old).close();
      order.verify(discarded, org.mockito.Mockito.times(2)).close(); // Rollback, then retained retry.
      order.verify(old).close();
      order.verify(replacement).startIndexingLoop();
    } finally {
      server.close();
    }
  }

  @Test
  void applicationServiceFailureRetainsServerUntilRetry(@TempDir Path tempDir) throws Exception {
    KnowledgeServer server = new KnowledgeServer(new io.justsearch.core.execution.TestEngineExecutors(),
        WorkerBootFixture.workerConfig(tempDir.resolve("data")), null);
    var services = org.mockito.Mockito.mock(WorkerAppServices.class);
    server.appServices = services;
    org.mockito.Mockito.doThrow(new java.io.IOException("OCR child still alive"))
        .doNothing().when(services).close();
    org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, server::close);
    assertFalse(server.awaitClosed(0));
    org.junit.jupiter.api.Assertions.assertSame(services, server.appServices());
    server.close();
    assertTrue(server.awaitClosed(0));
    org.mockito.Mockito.verify(services, org.mockito.Mockito.times(2)).close();
  }

  @Test
  @DisplayName("false before any close, true after one that completes")
  void awaitClosedDistinguishesTheTwoStates(@TempDir Path tempDir) throws Exception {
    KnowledgeServer server =
        new KnowledgeServer(new io.justsearch.core.execution.TestEngineExecutors(), WorkerBootFixture.workerConfig(tempDir.resolve("data")), null);

    assertFalse(
        server.awaitClosed(0),
        "a server that has never been closed must NOT report a completed close. If this is true"
            + " here, the latch is being counted down somewhere other than the end of close() and"
            + " the predicate has gone constant again.");

    server.close();

    assertTrue(
        server.awaitClosed(0),
        "after close() returns, the latch must be down — close() counts it down as its last"
            + " statement, so a false here means close() did not reach the end. This is the"
            + " direction EngineRoot.close() warns on.");
  }

  @Test
  @DisplayName("close does not abandon its published deferred-init future after five seconds")
  void closeWaitsPastTheFormerDeferredInitializationTimeout(@TempDir Path tempDir)
      throws Exception {
    KnowledgeServer server =
        new KnowledgeServer(new io.justsearch.core.execution.TestEngineExecutors(), WorkerBootFixture.workerConfig(tempDir.resolve("data")), null);
    server.deferredModelInit = new CompletableFuture<>();
    var init = server.deferredModelInit;

    var closeFailure = new AtomicReference<Throwable>();
    Thread closer =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    server.close();
                  } catch (Throwable failure) {
                    closeFailure.set(failure);
                  }
                });
    try {
      assertFalse(
          server.awaitClosed(5_500),
          "the old five-second timeout must not let close race a still-publishing initializer");
    } finally {
      init.complete(null);
      closer.join(2_000L);
    }
    assertFalse(closer.isAlive());
    assertTrue(server.awaitClosed(0));
    assertTrue(closeFailure.get() == null, String.valueOf(closeFailure.get()));
  }

  @Test
  @DisplayName("an exceptional deferred initializer still permits complete cleanup")
  void exceptionalDeferredModelInitializationStillCloses(@TempDir Path tempDir) throws Exception {
    KnowledgeServer server =
        new KnowledgeServer(new io.justsearch.core.execution.TestEngineExecutors(), WorkerBootFixture.workerConfig(tempDir.resolve("data")), null);
    server.deferredModelInit =
        CompletableFuture.failedFuture(new IllegalStateException("model initialization failed"));

    server.close();

    assertTrue(server.awaitClosed(0));
  }
}
