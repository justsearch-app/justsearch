/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.execute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorRegistrations;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import io.justsearch.core.execution.EngineExecutorSnapshot;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.services.input.SpladeEncoding;
import io.justsearch.indexerworker.services.input.VectorEncoding;
import io.justsearch.indexerworker.services.plan.LegSet;
import io.opentelemetry.api.trace.Span;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
import org.junit.jupiter.api.io.TempDir;

final class SearchExecutorFanoutLifetimeTest {
  @TempDir Path tempDir;

  @Test void refusedThreeWayRetainsRuntimeAndCallerUntilAcceptedLegActuallyExits() throws Exception {
    String oldConfig = System.getProperty("justsearch.config");
    Path config = tempDir.resolve("config.yaml");
    Files.writeString(
        config,
        "app:\n  data_dir: "
            + tempDir.resolve("data").toString().replace("\\", "\\\\")
            + "\nindex:\n  collections:\n    - name: lifetime\n      roots: ['ignored']\n"
            + "vector:\n  dimension: 4\n");
    System.setProperty("justsearch.config", config.toString());

    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var refusalResult = new CompletableFuture<Throwable>();
    var closeFinished = new CompletableFuture<Void>();
    var closeInterrupted = new AtomicBoolean();
    var callerReleases = new AtomicInteger();
    try (var registry = new RefusingFanoutRegistry(entered);
        var registrations = new LuceneExecutorRegistrations(registry);
        var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(4)).ephemeral()
            .withExecutorRegistrations(registrations).open()) {
      var holdingAnalyzer = installHoldingAnalyzer(runtime, entered, release);
      var executor = new SearchExecutor(
          runtime.textQueryOps(), runtime.readPathOps(), runtime.hybridSearchOps(),
          runtime.chunkSearchOps(), runtime::resolvedConfig, registrations, runtime.taskLifetime());
      var search = new Thread(() -> {
        try {
          invokeRunThreeWay(executor, () -> callerReleases::incrementAndGet);
          refusalResult.complete(new AssertionError("second leg was not refused"));
        } catch (Throwable failure) {
          refusalResult.complete(failure);
        }
      }, "search-executor-three-way-lifetime-test");
      var closer = new Thread(() -> {
        try {
          runtime.close();
          closeInterrupted.set(Thread.currentThread().isInterrupted());
          closeFinished.complete(null);
        } catch (Throwable failure) {
          closeFinished.completeExceptionally(failure);
        }
      }, "search-executor-runtime-close-test");
      try {
        search.start();
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        assertSame(registry.refusal, refusalResult.get(3, TimeUnit.SECONDS));
        assertEquals(
            2,
            registry.executeAttempts.get(),
            "one accepted submission and one exact refusal; no replay");
        assertEquals(0, callerReleases.get(), "caller lifetime stays retained by accepted work");
        closer.start();
        assertThrows(java.util.concurrent.TimeoutException.class,
            () -> closeFinished.get(150, TimeUnit.MILLISECONDS));
        assertThrows(IllegalStateException.class, () -> runtime.taskLifetime().retain(),
            "close rejects new runtime groups");
        closer.interrupt();
        assertThrows(java.util.concurrent.TimeoutException.class,
            () -> closeFinished.get(150, TimeUnit.MILLISECONDS));
        release.countDown();
        closeFinished.get(3, TimeUnit.SECONDS);
        assertTrue(holdingAnalyzer.passes.get() > 0, "accepted leg resumed inside its actual body");
        assertTrue(closeInterrupted.get(), "close restores interruption after safe cleanup");
        assertEquals(1, callerReleases.get());
        assertThrows(IllegalStateException.class,
            () -> runtime.indexCountOps().docCount());
      } finally {
        release.countDown();
        search.join(3000);
        if (closer.getState() != Thread.State.NEW) closer.join(3000);
      }
    } finally {
      if (oldConfig == null) System.clearProperty("justsearch.config");
      else System.setProperty("justsearch.config", oldConfig);
    }
  }

  private static HoldingAnalyzer installHoldingAnalyzer(
      RunningRuntime runtime, CountDownLatch entered, CountDownLatch release) throws Exception {
    var sessionMethod = RunningRuntime.class.getDeclaredMethod("session");
    sessionMethod.setAccessible(true);
    Object session = sessionMethod.invoke(runtime);
    var snapshotField = session.getClass().getDeclaredField("snapshot");
    snapshotField.setAccessible(true);
    Object snapshot = snapshotField.get(session);
    Class<?> snapshotType = snapshot.getClass();
    var components = snapshotType.getRecordComponents();
    var parameterTypes = new Class<?>[components.length];
    var values = new Object[components.length];
    HoldingAnalyzer holdingAnalyzer = null;
    for (int i = 0; i < components.length; i++) {
      parameterTypes[i] = components[i].getType();
      components[i].getAccessor().setAccessible(true);
      values[i] = components[i].getAccessor().invoke(snapshot);
      if ("indexAnalyzer".equals(components[i].getName())) {
        holdingAnalyzer = new HoldingAnalyzer((Analyzer) values[i], entered, release);
        values[i] = holdingAnalyzer;
      }
    }
    if (holdingAnalyzer == null) {
      throw new AssertionError("Runtime snapshot has no indexAnalyzer");
    }
    var constructor = snapshotType.getDeclaredConstructor(parameterTypes);
    constructor.setAccessible(true);
    snapshotField.set(session, constructor.newInstance(values));
    return holdingAnalyzer;
  }

  private static LuceneRuntimeTypes.SearchResult invokeRunThreeWay(
      SearchExecutor executor, io.justsearch.core.execution.EngineTaskLifetime childLifetime)
      throws Throwable {
    var method = SearchExecutor.class.getDeclaredMethod(
        "runThreeWay", LegSet.ThreeWay.class, String.class,
        LuceneRuntimeTypes.RuntimeSearchFilters.class, LuceneRuntimeTypes.RuntimeSearchFilters.class,
        LuceneRuntimeTypes.QuerySyntax.class, boolean.class, Span.class,
        EngineContext.Urgency.class, io.justsearch.core.execution.EngineTaskLifetime.class);
    method.setAccessible(true);
    var legs = new LegSet.ThreeWay(
        new VectorEncoding.Success(List.of(1f, 0f, 0f, 0f), "test"),
        new SpladeEncoding.Success(Map.of("needle", 1f)), 10, 0.5);
    try {
      return (LuceneRuntimeTypes.SearchResult) method.invoke(
          executor, legs, "needle", null, null, LuceneRuntimeTypes.QuerySyntax.SIMPLE, false,
          Span.getInvalid(), EngineContext.Urgency.FOREGROUND, childLifetime);
    } catch (InvocationTargetException wrapped) {
      throw wrapped.getCause();
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
        @Override public ExecutorService open(ThreadFactory factory) {
          throw new UnsupportedOperationException();
        }
        @Override public ScheduledExecutorService openScheduled(ThreadFactory factory) {
          throw new UnsupportedOperationException();
        }
        @Override public ExecutorService openVirtual() {
          executor = new ThreadPoolExecutor(
              1, 1, 0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(),
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
