/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import io.justsearch.core.execution.EngineExecutorSnapshot;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.AnalyzerWrapper;
import org.junit.jupiter.api.Test;

final class FanoutRuntimeLifetimeTest extends RuntimeTestBase {
  @Test void refusedHybridRetainsOldRuntimeUntilAcceptedLegAcquiresAndReleasesSearcher() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var readResult = new CompletableFuture<Integer>();
    var closeFinished = new CompletableFuture<Void>();
    var refusalResult = new CompletableFuture<Throwable>();
    var closeInterrupted = new AtomicBoolean();
    var callerReleases = new AtomicInteger();
    var refusedBodies = new AtomicInteger();
    try (var registry = new RefusingFanoutRegistry(entered);
        var registrations = new LuceneExecutorRegistrations(registry);
        var runtime = buildSchemaWithDim(4).ephemeral().withExecutorRegistrations(registrations).open()) {
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "kept", SchemaFields.DOC_UID, "kept#0", SchemaFields.CONTENT, "needle")));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      var session = new LifecycleTestAccessor(runtime).session();
      var search = new Thread(() -> {
        try {
          runtime.hybridSearchOps().executeHybrid((query, limit) -> {
            entered.countDown();
            while (release.getCount() != 0) {
              try { release.await(); }
              catch (InterruptedException expected) { /* actual exit, not interruption, owns release */ }
            }
            try {
              var hits = runtime.textQueryOps().searchText(query, limit, null);
              readResult.complete(hits.hits().size());
              return hits;
            } catch (RuntimeException failure) {
              readResult.completeExceptionally(failure);
              throw failure;
            }
          }, (vector, limit) -> {
            refusedBodies.incrementAndGet();
            return new LuceneRuntimeTypes.SearchResult(java.util.List.of(), 0, 0);
          }, "needle", new float[] {1, 0, 0, 0}, 10, false, "lifetime-test",
              EngineContext.Urgency.FOREGROUND, () -> callerReleases::incrementAndGet);
          refusalResult.complete(new AssertionError("second leg was not refused"));
        } catch (Throwable failure) { refusalResult.complete(failure); }
      }, "runtime-fanout-test");
      var closer = new Thread(() -> {
        try { runtime.close(); closeInterrupted.set(Thread.currentThread().isInterrupted()); closeFinished.complete(null); }
        catch (Throwable failure) { closeFinished.completeExceptionally(failure); }
      }, "runtime-close-test");
      try {
        search.start();
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        assertSame(registry.refusal, refusalResult.get(3, TimeUnit.SECONDS));
        assertEquals(0, callerReleases.get());
        assertEquals(0, refusedBodies.get());
        closer.start();
        assertThrows(java.util.concurrent.TimeoutException.class, () -> closeFinished.get(150, TimeUnit.MILLISECONDS));
        assertThrows(IllegalStateException.class, () -> runtime.taskLifetime().retain(), "close rejects new groups");
        assertNotNull(session.snapshot, "accepted work can still acquire the old searcher");
        closer.interrupt();
        assertThrows(java.util.concurrent.TimeoutException.class, () -> closeFinished.get(150, TimeUnit.MILLISECONDS));
        release.countDown();
        assertEquals(1, readResult.get(3, TimeUnit.SECONDS));
        closeFinished.get(3, TimeUnit.SECONDS);
        assertTrue(closeInterrupted.get(), "close restores interruption after safe cleanup");
        assertEquals(1, callerReleases.get());
        assertNull(session.snapshot);
        assertThrows(IllegalStateException.class, () -> new SearcherBridge(session).withSearcher(searcher -> 0));
      } finally {
        release.countDown();
        search.join(3000);
        if (closer.getState() != Thread.State.NEW) closer.join(3000);
      }
    }
  }

  @Test void refusedDirectChunkRetainsOldRuntimeUntilAcceptedLegActuallyExits() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var closeFinished = new CompletableFuture<Void>();
    var refusalResult = new CompletableFuture<Throwable>();
    var closeInterrupted = new AtomicBoolean();
    var callerReleases = new AtomicInteger();
    try (var registry = new RefusingFanoutRegistry(entered);
        var registrations = new LuceneExecutorRegistrations(registry);
        var runtime = buildSchemaWithDim(4).ephemeral().withExecutorRegistrations(registrations).open()) {
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "kept", SchemaFields.DOC_UID, "kept#0", SchemaFields.CONTENT, "needle")));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      var session = new LifecycleTestAccessor(runtime).session();
      var snapshot = session.snapshot;
      var holdingAnalyzer = new HoldingAnalyzer(snapshot.indexAnalyzer(), entered, release);
      session.snapshot = new LifecycleSnapshot(
          snapshot.directory(), snapshot.writer(), snapshot.searcherManager(), snapshot.indexPath(),
          snapshot.ephemeralPath(), holdingAnalyzer);
      var search = new Thread(() -> {
        try {
          runtime.chunkSearchOps().searchChunksHybrid(
              "needle", new float[] {1, 0, 0, 0}, Set.of("kept"), 10, true, null,
              EngineContext.Urgency.FOREGROUND, () -> callerReleases::incrementAndGet);
          refusalResult.complete(new AssertionError("second leg was not refused"));
        } catch (Throwable failure) { refusalResult.complete(failure); }
      }, "runtime-direct-chunk-fanout-test");
      var closer = new Thread(() -> {
        try {
          runtime.close();
          closeInterrupted.set(Thread.currentThread().isInterrupted());
          closeFinished.complete(null);
        } catch (Throwable failure) { closeFinished.completeExceptionally(failure); }
      }, "runtime-direct-chunk-close-test");
      try {
        search.start();
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        assertSame(registry.refusal, refusalResult.get(3, TimeUnit.SECONDS));
        assertEquals(0, callerReleases.get(), "caller lifetime stays retained by accepted work");
        assertEquals(
            2,
            registry.executeAttempts.get(),
            "one accepted submission and one exact refusal; no replay");
        closer.start();
        assertThrows(
            java.util.concurrent.TimeoutException.class,
            () -> closeFinished.get(150, TimeUnit.MILLISECONDS));
        assertThrows(IllegalStateException.class, () -> runtime.taskLifetime().retain(), "close rejects new groups");
        assertNotNull(session.snapshot, "accepted direct chunk work retains the old Lucene runtime");
        closer.interrupt();
        assertThrows(
            java.util.concurrent.TimeoutException.class,
            () -> closeFinished.get(150, TimeUnit.MILLISECONDS));
        release.countDown();
        closeFinished.get(3, TimeUnit.SECONDS);
        assertTrue(holdingAnalyzer.passes.get() > 0, "accepted leg resumed inside its actual body");
        assertTrue(closeInterrupted.get(), "close restores interruption after safe cleanup");
        assertEquals(1, callerReleases.get());
        assertNull(session.snapshot);
        assertThrows(IllegalStateException.class, () -> new SearcherBridge(session).withSearcher(searcher -> 0));
      } finally {
        release.countDown();
        search.join(3000);
        if (closer.getState() != Thread.State.NEW) closer.join(3000);
      }
    }
  }

  private static final class RefusingFanoutRegistry implements EngineExecutorRegistry {
    private final TestEngineExecutors delegate = new TestEngineExecutors();
    private final CountDownLatch entered;
    private final AtomicInteger executeAttempts = new AtomicInteger();
    private final EngineExecutorRejectedException refusal = new EngineExecutorRejectedException(
        EngineExecutorRejectedException.Reason.QUEUE_LIMIT, "test-fanout", 1);
    private RefusingFanoutRegistry(CountDownLatch entered) {
      this.entered = entered;
    }
    @Override public Registration register(EngineExecutorSpec spec) {
      if (spec.mode() != EngineExecutorSpec.Mode.VIRTUAL) return delegate.register(spec);
      return new Registration() {
        private ExecutorService executor;
        @Override public EngineExecutorSpec spec() { return spec; }
        @Override public ExecutorService open(ThreadFactory factory) { throw new UnsupportedOperationException(); }
        @Override public ScheduledExecutorService openScheduled(ThreadFactory factory) { throw new UnsupportedOperationException(); }
        @Override public ExecutorService openVirtual() {
          executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(),
              (task, owner) -> { throw refusal; }) {
            @Override public void execute(Runnable task) {
              executeAttempts.incrementAndGet();
              super.execute(task);
              try { assertTrue(entered.await(3, TimeUnit.SECONDS)); }
              catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
              }
            }
          };
          return executor;
        }
        @Override public void close() { if (executor != null) executor.shutdownNow(); }
      };
    }
    @Override public Limits limits(EngineExecutorSpec.Kind kind) { return delegate.limits(kind); }
    @Override public int retryAfterSeconds() { return delegate.retryAfterSeconds(); }
    @Override public int maxConcurrentWork() { return delegate.maxConcurrentWork(); }
    @Override public EngineExecutorSnapshot snapshot() { return delegate.snapshot(); }
    @Override public void close() { delegate.close(); }
  }

  private static final class HoldingAnalyzer extends AnalyzerWrapper {
    private final Analyzer delegate;
    private final CountDownLatch entered;
    private final CountDownLatch release;
    private final AtomicInteger passes = new AtomicInteger();

    private HoldingAnalyzer(Analyzer delegate, CountDownLatch entered, CountDownLatch release) {
      super(PER_FIELD_REUSE_STRATEGY);
      this.delegate = delegate;
      this.entered = entered;
      this.release = release;
    }

    @Override protected Analyzer getWrappedAnalyzer(String fieldName) {
      entered.countDown();
      while (release.getCount() != 0) {
        try { release.await(); }
        catch (InterruptedException expected) { /* actual exit, not interruption, owns release */ }
      }
      passes.incrementAndGet();
      return delegate;
    }
  }
}
