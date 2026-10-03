/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.settings.QueryRoleSelection;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSnapshot;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.ort.PolicySnapshot;
import io.justsearch.ort.RuntimePolicy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.mockito.MockedStatic;

/** Holds native assembly after the real query-witness callback on the production executor. */
public final class HeldInferenceCompositionFixture {
  private final CountDownLatch witnessPublished = new CountDownLatch(1);
  private final CountDownLatch releaseComposition = new CountDownLatch(1);
  private volatile ExecutorService deferredExecutor;
  private final KnowledgeServer server;

  public HeldInferenceCompositionFixture(EngineExecutorRegistry executors,
      WorkerConfig config, ComponentHandle indexComponent, ComponentHandle encoderComponent,
      ResolvedConfig startupConfiguration) {
    this(executors, config, RecordedIngestionLifecycle.denied(), indexComponent, encoderComponent,
        startupConfiguration);
  }

  public HeldInferenceCompositionFixture(EngineExecutorRegistry executors,
      WorkerConfig config, RecordedIngestionLifecycle recordedIngestion,
      ComponentHandle indexComponent, ComponentHandle encoderComponent,
      ResolvedConfig startupConfiguration) {
    server = new KnowledgeServer(new HeldExecutorRegistry(executors), config, null,
        ManagedChildRegistry.noop(), recordedIngestion, indexComponent,
        encoderComponent, startupConfiguration);
  }

  public KnowledgeServer server() {
    return server;
  }

  public boolean witnessPublished() throws InterruptedException {
    return witnessPublished.await(0, TimeUnit.MILLISECONDS);
  }

  public void releaseComposition() {
    releaseComposition.countDown();
  }

  public CompletableFuture<Void> deferredComposition() {
    return server.deferredModelInit;
  }

  /** The single-worker queue barrier includes the initializer's completion callbacks. */
  public void awaitCompositionCallbacks() throws Exception {
    deferredExecutor.submit(() -> {}).get(5, TimeUnit.SECONDS);
  }

  private void runHeldComposition(Runnable initializer) {
    try (MockedStatic<InferenceCompositionRoot> _ =
        org.mockito.Mockito.mockStatic(InferenceCompositionRoot.class, invocation -> {
          if (invocation.getMethod().getName().equals("compose")
              && invocation.getArguments().length == 10) {
            QueryRoleSelection exact = invocation.getArgument(7);
            if (exact == null) {
              throw new AssertionError("recovery must compose the retained query selection");
            }
            @SuppressWarnings("unchecked")
            Consumer<InferenceSurface.ComponentObservation> callback =
                (Consumer<InferenceSurface.ComponentObservation>) invocation.getArgument(8);
            @SuppressWarnings("unchecked")
            Consumer<InferenceCompositionRoot.CapturedCompositionPlan> planCallback =
                (Consumer<InferenceCompositionRoot.CapturedCompositionPlan>)
                    invocation.getArgument(9);
            EncoderConfigurationProjection projection = invocation.getArgument(0);
            InferenceSurface.ComponentObservation queryObservation =
                new InferenceSurface.ComponentObservation(
                    Optional.of(projection.queryDigest()), Set.of(), Set.of(), Optional.of(exact));
            callback.accept(queryObservation);
            planCallback.accept(new InferenceCompositionRoot.CapturedCompositionPlan(
                new IndexCompositionPlan(projection, invocation.getArgument(1),
                    RuntimePolicy.defaults(), Map.of(), false),
                projection, queryObservation));
            witnessPublished.countDown();
            if (!releaseComposition.await(30, TimeUnit.SECONDS)) {
              throw new AssertionError("native composition release timed out");
            }
            return emptySurface(projection.digest(), exact);
          }
          return invocation.callRealMethod();
        })) {
      initializer.run();
    }
  }

  private static InferenceSurface emptySurface(
      String configurationDigest, QueryRoleSelection selection) {
    return new InferenceSurface(Optional.empty(), Optional.empty(), Optional.empty(),
        Optional.empty(), Optional.empty(), Optional.empty(),
        new PolicySnapshot(RuntimePolicy.defaults(), Map.of()), List.of(),
        new InferenceSurface.ComponentObservation(Optional.of(configurationDigest),
            Set.of(), Set.of(), Optional.of(selection)));
  }

  private final class HeldExecutorRegistry implements EngineExecutorRegistry {
    private final EngineExecutorRegistry delegate;

    private HeldExecutorRegistry(EngineExecutorRegistry delegate) {
      this.delegate = delegate;
    }

    @Override
    public Registration register(EngineExecutorSpec spec) {
      Registration registered = delegate.register(spec);
      if (!WorkerExecutorRegistrations.DEFERRED_MODEL_INIT.equals(spec.name())) {
        return registered;
      }
      return new Registration() {
        @Override public EngineExecutorSpec spec() { return registered.spec(); }

        @Override
        public ExecutorService open(ThreadFactory threadFactory) {
          deferredExecutor = registered.open(task -> threadFactory.newThread(
              () -> runHeldComposition(task)));
          return deferredExecutor;
        }

        @Override
        public ScheduledExecutorService openScheduled(ThreadFactory threadFactory) {
          return registered.openScheduled(threadFactory);
        }

        @Override public ExecutorService openVirtual() { return registered.openVirtual(); }
        @Override public void close() { registered.close(); }
      };
    }

    @Override public Limits limits(EngineExecutorSpec.Kind kind) { return delegate.limits(kind); }
    @Override public int maxConcurrentWork() { return delegate.maxConcurrentWork(); }
    @Override public int retryAfterSeconds() { return delegate.retryAfterSeconds(); }
    @Override public EngineExecutorSnapshot snapshot() { return delegate.snapshot(); }

    /** The Root owns the borrowed registry; server registrations close their own delegates. */
    @Override public void close() {}
  }
}
