/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.justsearch.adapters.lucene.runtime.DeferredRuntime;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.core.execution.TestEngineExecutors;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Regression coverage for deferred serving-owner cleanup and shutdown lock ordering. */
@Timeout(30)
final class KnowledgeServerDeferredRetirementTest {

  @Test
  void failedDeferredPublicationReleasesPreparedEncoderHold(@TempDir Path tempDir)
      throws Exception {
    try (var executors = new TestEngineExecutors()) {
      var server = new KnowledgeServer(
          executors, WorkerBootFixture.workerConfig(tempDir.resolve("data")), null);
      var oldServices = mock(WorkerAppServices.class);
      var successorServices = mock(WorkerAppServices.class);
      var oldRuntime = mock(RunningRuntime.class);
      var successorRuntime = mock(RunningRuntime.class);
      var preparation = mock(DeferredRuntime.PreparedUpgrade.class);
      var surface = mock(InferenceSurface.class);
      var model = IndexFingerprint.ModelFingerprint.present("a");
      var encoder = new EncoderSet(surface,
          new EncoderSet.ModelIdentity(model, model, model, false, 768), Duration.ZERO);
      try {
        setField(server, "searchLifecycle", oldRuntime);
        setField(server, "ingestLifecycle", oldRuntime);
        server.publishServingView(oldServices);
        Field selected = KnowledgeServer.class.getDeclaredField("servingView");
        selected.setAccessible(true);
        Object oldView = selected.get(server);
        Method attach = oldView.getClass().getDeclaredMethod("attachEncoderSet", EncoderSet.class);
        attach.setAccessible(true);
        attach.invoke(oldView, encoder);
        doThrow(new IllegalStateException("upgrade was abandoned"))
            .when(preparation).markPublished();

        assertThrows(IllegalStateException.class, () -> invoke(
            server, "publishDeferredSuccessor",
            new Class<?>[] {WorkerAppServices.class, WorkerAppServices.class,
                RunningRuntime.class, DeferredRuntime.PreparedUpgrade.class},
            successorServices, oldServices, successorRuntime, preparation));
        assertTrue(retiredServingViews(server).isEmpty(),
            "a rejected publication must not register a retired predecessor");
        Method release = oldView.getClass().getDeclaredMethod("releaseEncoderSet");
        release.setAccessible(true);
        release.invoke(oldView);
        encoder.close();
        verify(surface).close();
      } finally {
        server.close();
      }
    }
  }

  @Test
  void refusedRetiredViewCleanupRetainsOwnerForReaperRetry(@TempDir Path tempDir) throws Exception {
    try (var executors = new TestEngineExecutors()) {
      var server = new KnowledgeServer(
          executors, WorkerBootFixture.workerConfig(tempDir.resolve("data")), null);
      try {
        var oldServices = mock(WorkerAppServices.class);
        var successorServices = mock(WorkerAppServices.class);
        var oldRuntime = mock(RunningRuntime.class);
        var successorRuntime = mock(RunningRuntime.class);
        var preparation = mock(DeferredRuntime.PreparedUpgrade.class);
        doThrow(new IOException("old serving owner is still live"))
            .doNothing()
            .when(oldServices)
            .close();
        doNothing().when(preparation).markPublished();

        setField(server, "searchLifecycle", oldRuntime);
        setField(server, "ingestLifecycle", oldRuntime);
        server.publishServingView(oldServices);
        var issuedA = server.captureServingView();

        invoke(
            server,
            "publishDeferredSuccessor",
            new Class<?>[] {
              WorkerAppServices.class,
              WorkerAppServices.class,
              RunningRuntime.class,
              DeferredRuntime.PreparedUpgrade.class
            },
            successorServices,
            oldServices,
            successorRuntime,
            preparation);

        // The first cleanup attempt is made by the actual lease release. It must retain the
        // retired A owner when service close refuses, leaving the retry seam something to do.
        issuedA.close();
        verify(oldServices).close();
        verify(preparation, never()).retireReader();
        assertEquals(1, retiredServingViews(server).size(),
            "a refused retired owner must remain registered for retry");

        invoke(server, "retryRetiredServingViews", new Class<?>[0]);

        verify(oldServices, times(2)).close();
        verify(preparation).retireReader();
        assertTrue(retiredServingViews(server).isEmpty(),
            "successful retry must remove the retired owner");
      } finally {
        server.close();
      }
    }
  }

  @Test
  void closeDoesNotHoldRuntimeSwapLockWhileDeferredInitializerWaits(@TempDir Path tempDir)
      throws Exception {
    try (var executors = new TestEngineExecutors()) {
      var server = new KnowledgeServer(
          executors, WorkerBootFixture.workerConfig(tempDir.resolve("data")), null);
      var lockOwner = server.beginDevReplacement();
      var initializerAttempted = new CountDownLatch(1);
      var initialization = new CompletableFuture<ModelContext>();
      var closeFailure = new AtomicReference<Throwable>();
      var deferred = mock(DeferredRuntime.class);
      setField(server, "ingestLifecycle", deferred);
      setField(server, "searchLifecycle", deferred);
      server.deferredModelInit = initialization;

      Thread initializer = Thread.ofVirtual().start(() -> {
        initializerAttempted.countDown();
        try {
          invoke(
              server,
              "upgradeDeferredServing",
              new Class<?>[] {DeferredRuntime.class},
              deferred);
          initialization.complete(null);
        } catch (Throwable failure) {
          initialization.completeExceptionally(failure);
        }
      });
      Thread closer = Thread.ofVirtual().start(() -> {
        try {
          server.close();
        } catch (Throwable failure) {
          closeFailure.set(failure);
        }
      });

      try {
        assertTrue(initializerAttempted.await(2, TimeUnit.SECONDS));
        assertTrue(awaitCloseStarted(server), "close must enter before the lock is released");
        assertFalse(server.awaitClosed(100),
            "close must wait for the deferred initializer while it owns the swap lock");

        // Releasing the test owner lets the initializer acquire the swap lock. A correct close
        // joins that initializer before trying to acquire the same lock; the old order deadlocked
        // here by holding runtimeSwapLock while joining deferredModelInit.
        lockOwner.close();
        assertThrows(ExecutionException.class,
            () -> initialization.get(2, TimeUnit.SECONDS));
        initializer.join(2_000);
        closer.join(2_000);
        assertFalse(initializer.isAlive());
        assertFalse(closer.isAlive(),
            "shutdown must finish after the deferred initializer releases runtimeSwapLock");
        assertNull(closeFailure.get(), String.valueOf(closeFailure.get()));
        assertTrue(server.awaitClosed(0));
      } finally {
        lockOwner.close();
        if (!initialization.isDone()) {
          initialization.completeExceptionally(new IllegalStateException("test cleanup"));
        }
        initializer.join(2_000);
        closer.join(2_000);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Object> retiredServingViews(KnowledgeServer server) throws Exception {
    Field field = KnowledgeServer.class.getDeclaredField("retiredServingViews");
    field.setAccessible(true);
    return (List<Object>) field.get(server);
  }

  private static boolean awaitCloseStarted(KnowledgeServer server) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    Field field = KnowledgeServer.class.getDeclaredField("closeStarted");
    field.setAccessible(true);
    while (System.nanoTime() < deadline) {
      if ((boolean) field.get(server)) return true;
      Thread.onSpinWait();
    }
    return (boolean) field.get(server);
  }

  private static void setField(KnowledgeServer server, String name, Object value) throws Exception {
    Field field = KnowledgeServer.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(server, value);
  }

  private static Object invoke(
      KnowledgeServer server, String name, Class<?>[] parameterTypes, Object... arguments)
      throws Exception {
    Method method = KnowledgeServer.class.getDeclaredMethod(name, parameterTypes);
    method.setAccessible(true);
    try {
      return method.invoke(server, arguments);
    } catch (InvocationTargetException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof Exception exception) throw exception;
      if (cause instanceof Error error) throw error;
      throw failure;
    }
  }

}
