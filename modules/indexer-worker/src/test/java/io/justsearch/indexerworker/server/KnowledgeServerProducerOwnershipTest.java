/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSnapshot;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.queue.JobQueue;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class KnowledgeServerProducerOwnershipTest {
  @BeforeAll static void config() {
    if (ConfigStore.globalOrNull() == null) {
      ConfigStore.setGlobal(new ConfigStore(ResolvedConfig.builder().contributeEnvRegistry().build()));
    }
  }

  @Test void canceledModelCompletionDoesNotCloseResourcesUntilInitializerActuallyExits(@TempDir Path dir)
      throws Exception {
    var entered = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var registry = new RecordingRegistry()) {
      var server = new KnowledgeServer(registry, WorkerBootFixture.workerConfig(dir), null);
      try {
      var model = mock(io.justsearch.indexerworker.embed.EmbeddingService.class);
      server.embeddingService = model;
      server.startDeferredModelInitialization(() -> {
        entered.countDown();
        awaitExit(release, interrupted);
        return null;
      });
      CompletableFuture<Void> closed = null;
      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        verify(registry.handles.get(WorkerExecutorRegistrations.DEFERRED_MODEL_INIT)).open(any());
        assertTrue(server.deferredModelInit.cancel(true));
        assertTrue(interrupted.await(3, TimeUnit.SECONDS));
        closed = closeInBackground(server);
        assertFalse(server.awaitClosed(150));
        verify(model, never()).close();
        release.countDown();
        closed.get(3, TimeUnit.SECONDS);
        verify(model).close();
        assertTrue(server.awaitClosed(0));
      } finally {
        release.countDown();
        if (closed != null) closed.get(3, TimeUnit.SECONDS);
      }
      } finally {
        if (!server.awaitClosed(0)) server.close();
      }
    }
  }

  @Test void reaperUsesRegisteredSchedulerAndExitsBeforeQueueClose(@TempDir Path dir) throws Exception {
    var entered = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var queue = mock(JobQueue.class);
    when(queue.recoverStuckJobs(anyLong())).thenAnswer(invocation -> {
      entered.countDown();
      awaitExit(release, interrupted);
      return 0;
    });
    try (var registry = new RecordingRegistry()) {
      var server = new KnowledgeServer(registry, WorkerBootFixture.workerConfig(dir), null);
      try {
      var queueField = KnowledgeServer.class.getDeclaredField("jobQueue");
      queueField.setAccessible(true);
      queueField.set(server, queue);
      server.startStuckJobReaper(queue);
      CompletableFuture<Void> closed = null;
      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        verify(registry.handles.get(WorkerExecutorRegistrations.STUCK_JOB_REAPER)).openScheduled(any());
        assertEquals(TimeUnit.MINUTES.toMillis(2), registry.reaperDelayMillis);
        closed = closeInBackground(server);
        assertTrue(interrupted.await(3, TimeUnit.SECONDS));
        assertFalse(server.awaitClosed(150));
        verify(queue, never()).close();
        release.countDown();
        closed.get(3, TimeUnit.SECONDS);
        verify(queue).close();
      } finally {
        release.countDown();
        if (closed != null) closed.get(3, TimeUnit.SECONDS);
      }
      } finally {
        if (!server.awaitClosed(0)) server.close();
      }
    }
  }

  private static CompletableFuture<Void> closeInBackground(KnowledgeServer server) {
    var result = new CompletableFuture<Void>();
    Thread.ofVirtual().start(() -> {
      try { server.close(); result.complete(null); }
      catch (Throwable failure) { result.completeExceptionally(failure); }
    });
    return result;
  }

  private static void awaitExit(CountDownLatch release, CountDownLatch interrupted) {
    while (release.getCount() != 0) {
      try { release.await(); }
      catch (InterruptedException expected) { interrupted.countDown(); }
    }
  }

  private static final class RecordingRegistry implements EngineExecutorRegistry {
    private final TestEngineExecutors delegate = new TestEngineExecutors();
    private final Map<String, Registration> handles = new HashMap<>();
    private long reaperDelayMillis;
    @Override public Registration register(EngineExecutorSpec spec) {
      Registration owned = delegate.register(spec);
      Registration handle = mock(Registration.class, org.mockito.AdditionalAnswers.delegatesTo(owned));
      if (spec.name().equals(WorkerExecutorRegistrations.STUCK_JOB_REAPER)) {
        doAnswer(invocation -> {
          var scheduler = new ScheduledThreadPoolExecutor(1, invocation.getArgument(0, java.util.concurrent.ThreadFactory.class)) {
            @Override public java.util.concurrent.ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable task, long initialDelay, long delay, TimeUnit unit) {
              assertEquals(initialDelay, delay);
              reaperDelayMillis = unit.toMillis(delay);
              // Advance only the fixture clock's first tick; preserve production cadence assertions.
              return super.scheduleWithFixedDelay(task, 0, delay, unit);
            }
          };
          doAnswer(ignored -> { scheduler.shutdownNow(); return null; }).when(handle).close();
          return scheduler;
        }).when(handle).openScheduled(any());
      }
      handles.put(spec.name(), handle);
      return handle;
    }
    @Override public Limits limits(EngineExecutorSpec.Kind kind) { return delegate.limits(kind); }
    @Override public int retryAfterSeconds() { return delegate.retryAfterSeconds(); }
    @Override public int maxConcurrentWork() { return delegate.maxConcurrentWork(); }
    @Override public EngineExecutorSnapshot snapshot() { return delegate.snapshot(); }
    @Override public void close() { delegate.close(); }
  }
}
