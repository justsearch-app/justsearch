/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.engine.EngineRoot;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.KnowledgeServerConfig;
import io.justsearch.core.component.ComponentSpec;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.TestEngineComponents;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

final class HeadlessAppInitialIndexDeadlineTest {
  @Test
  void deadlineEndsOnlyTheApiWaitAndPreservesTheInitialPhysicalOwner() {
    try (var components = new TestEngineComponents()) {
      var index = components.register(new ComponentSpec("index", true, Set.of(),
          ComponentSpec.ComposeCapability.BESIDE, Duration.ofMillis(1), 2));
      index.transition(ComponentState.STARTING, "worker.starting", "index root lock");
      var opening = new CompletableFuture<String>();

      assertNull(HeadlessApp.awaitInitialIndexStart(opening, index));
      assertFalse(opening.isDone(), "the timeout must not cancel the physical open");

      opening.complete("ready");
      assertEquals("ready", HeadlessApp.awaitInitialIndexStart(opening, index));
    }
  }

  @Test
  @Timeout(40)
  void constructedCallbackKeepsInitialOpenUnderBootstrapLock(@TempDir Path dir) throws Exception {
    var constructed = new CountDownLatch(1);
    var releaseConstructionCallback = new CountDownLatch(1);
    var bootstrapRef = new AtomicReference<KnowledgeServerBootstrap>();
    Future<?> opening = null;

    try (var executors = new TestEngineExecutors();
        var components = TestEngineComponents.fourComponents();
        ExecutorService opener = Executors.newSingleThreadExecutor();
        ExecutorService closer = Executors.newSingleThreadExecutor()) {
      try {
        var index = components.handle("index");
        var publicationLock = new ReentrantReadWriteLock();
        var client = mock(KnowledgeClient.class);
        when(client.isHealthy(any())).thenReturn(true);

        var engineRoot = mock(EngineRoot.class);
        when(engineRoot.executors()).thenReturn(executors);
        when(engineRoot.components()).thenReturn(components);
        when(engineRoot.indexComponent()).thenReturn(index);
        when(engineRoot.publicationLock()).thenReturn(publicationLock);
        when(engineRoot.start(any(GpuSchedulingGauge.class), any(IpcTelemetry.class)))
            .thenReturn(client);

        var config = new KnowledgeServerConfig(
            false, dir, dir, dir, 1_000, 1_000, 1, 5_000, 1_000, 0, 64, 0, 0);
        Consumer<KnowledgeServerBootstrap> onConstructed = bootstrap -> {
          bootstrapRef.set(bootstrap);
          constructed.countDown();
          try {
            if (!releaseConstructionCallback.await(20, TimeUnit.SECONDS)) {
              throw new AssertionError(
                  "timed out waiting to release bootstrap construction callback");
            }
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while holding bootstrap construction callback",
                interrupted);
          }
        };

        opening = opener.submit(
            () -> invokeTryStartKnowledgeServer(config, engineRoot, onConstructed));
        assertTrue(constructed.await(5, TimeUnit.SECONDS),
            "HeadlessApp must publish the bootstrap before physical host.start");
        KnowledgeServerBootstrap bootstrap = bootstrapRef.get();
        assertNotNull(bootstrap);

        Future<?> closing = closer.submit(bootstrap::closeForUpgrade);
        assertEquals(io.justsearch.app.services.worker.ShutdownOutcome.FAILED,
            closing.get(8, TimeUnit.SECONDS),
            "ordered close must refuse while the initial physical open owns initLock");
        verify(engineRoot, never()).start(any(GpuSchedulingGauge.class), any(IpcTelemetry.class));

        releaseConstructionCallback.countDown();
        opening.get(10, TimeUnit.SECONDS);
        verify(engineRoot, times(1)).start(any(GpuSchedulingGauge.class), any(IpcTelemetry.class));
        assertTrue(bootstrap.hasClient());
        assertSame(client, bootstrap.client());
        assertEquals(io.justsearch.app.services.worker.ShutdownOutcome.GRACEFUL,
            bootstrap.closeForUpgrade());
      } finally {
        releaseConstructionCallback.countDown();
        if (opening != null) {
          opening.get(10, TimeUnit.SECONDS);
        }
        KnowledgeServerBootstrap bootstrap = bootstrapRef.get();
        if (bootstrap != null) {
          bootstrap.closeForUpgrade();
        }
      }
    }
  }

  private static Object invokeTryStartKnowledgeServer(
      KnowledgeServerConfig config, EngineRoot engineRoot,
      Consumer<KnowledgeServerBootstrap> onConstructed) throws Exception {
    Method method = HeadlessApp.class.getDeclaredMethod(
        "tryStartKnowledgeServer", KnowledgeServerConfig.class, EngineRoot.class,
        boolean.class, Consumer.class);
    method.setAccessible(true);
    return method.invoke(null, config, engineRoot, false, onConstructed);
  }
}
