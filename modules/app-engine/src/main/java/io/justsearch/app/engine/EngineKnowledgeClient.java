/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.app.services.worker.CancelToken;
import io.justsearch.app.services.worker.HealthServiceCalls;
import io.justsearch.app.services.worker.IngestServiceCalls;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.SearchServiceCalls;
import io.justsearch.indexerworker.server.WorkerAppServices;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerServiceException;
import io.justsearch.ipc.IndexingJobsFrame;
import io.justsearch.ipc.ScanRootProgress;
import io.justsearch.ipc.ScanRootRequest;
import io.justsearch.ipc.SubscribeIndexingJobsRequest;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link KnowledgeClient} whose calls are method calls (lane F stage A item A6).
 *
 * <p>Every call goes straight to the converted worker service in this JVM. What that removes is
 * the channel: no serialisation, no port, no reconnect, no circuit breaker, no retry policy.
 * What it must NOT remove is the four operation contracts design §6 names, and each of them has an
 * explicit home here:
 *
 * <ul>
 *   <li><b>Deadlines.</b> {@code RpcDeadlineCategory} still multiplies the configured base
 *       deadline; the resulting budget arms a scheduled cancel that flips the call's
 *       {@link CallContext.CancelSignal} — the same signal the streaming methods already poll —
 *       and, if the call returns after the budget elapsed, the client raises
 *       {@link WorkerServiceException.Status#DEADLINE_EXCEEDED}. Before A6 the transport did this;
 *       the categories would otherwise have become decoration.
 *   <li><b>Cancellation.</b> A {@link CancelToken} supplied to {@code scanRoot} is wired to the
 *       same signal, so abandoning an HTTP request still stops the scan.
 *   <li><b>Bounded work.</b> The {@code FetchDocuments} result-size cap and the batch-size clamp
 *       stay on {@link KnowledgeClient}, which is where they always belonged.
 *   <li><b>Flow control.</b> The two streams hand back a handle whose close stops production;
 *       items A7 and A8 state their bound and its policy.
 * </ul>
 *
 * <p><b>The foreground gauge.</b> {@link ForegroundLoadGate} wraps the nine user-waiting calls,
 * replacing {@code ForegroundLoadInterceptor} (deleted at A9). It is applied at exactly one layer —
 * the search executor — because a {@code Search} that internally reranks must count as one
 * foreground call, not two.
 */
public final class EngineKnowledgeClient extends KnowledgeClient {

  private static final Logger log = LoggerFactory.getLogger(EngineKnowledgeClient.class);

  private final java.util.function.Supplier<WorkerAppServices> services;
  private final ForegroundLoadGate foregroundLoad;
  private final ScheduledExecutorService deadlines;
  private final java.util.concurrent.ExecutorService streamThreads;

  /**
   * @param services the composed index half, read per call and never cached: {@code KnowledgeServer}
   *     REPLACES its {@code WorkerAppServices} on a deferred-runtime upgrade and on dev hot-reload,
   *     and a client holding the old instance would keep calling services bound to a closed runtime
   * @param foregroundLoad the gate over the process-wide {@code ForegroundLoad} gauge
   * @param deadlineMs the base deadline the categories multiply
   * @param batchSize the per-batch submission clamp
   * @param telemetry carried through to the status-poll metric; may be null
   */
  public EngineKnowledgeClient(
      java.util.function.Supplier<WorkerAppServices> services,
      ForegroundLoadGate foregroundLoad,
      long deadlineMs,
      int batchSize,
      IpcTelemetry telemetry) {
    super(deadlineMs, batchSize, telemetry);
    this.services = Objects.requireNonNull(services, "services");
    this.foregroundLoad = Objects.requireNonNull(foregroundLoad, "foregroundLoad");
    this.deadlines =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "engine-call-deadlines");
              t.setDaemon(true);
              return t;
            });
    this.streamThreads =
        Executors.newCachedThreadPool(
            r -> {
              Thread t = new Thread(r, "engine-stream");
              t.setDaemon(true);
              return t;
            });
  }

  /**
   * A call's budget: a cancellation signal that flips when the deadline elapses, plus the means to
   * tell afterwards whether it did.
   */
  private final class Budget implements AutoCloseable {
    private final AtomicBoolean expired = new AtomicBoolean(false);
    private final AtomicBoolean cancelledByCaller = new AtomicBoolean(false);
    private volatile Runnable cancelHandler;
    private final ScheduledFuture<?> alarm;
    private final long budgetMs;

    Budget(long budgetMs) {
      this.budgetMs = budgetMs;
      this.alarm =
          deadlines.schedule(
              () -> {
                if (expired.compareAndSet(false, true)) {
                  Runnable handler = cancelHandler;
                  if (handler != null) {
                    handler.run();
                  }
                }
              },
              budgetMs,
              TimeUnit.MILLISECONDS);
    }

    /** Wires an external {@link CancelToken} so caller-abandonment reaches the same signal. */
    void bind(CancelToken token) {
      if (token == null) {
        return;
      }
      token.onCancel(
          () -> {
            if (cancelledByCaller.compareAndSet(false, true)) {
              Runnable handler = cancelHandler;
              if (handler != null) {
                handler.run();
              }
            }
          });
    }

    CallContext context() {
      // The in-process equivalent of the two server interceptors WorkerServiceCalls.callContext
      // reads: caller and callee share the thread, so the OTel span and the logging MDC are
      // already current — there is nothing to propagate across, only to read in place.
      io.opentelemetry.api.trace.SpanContext spanCtx =
          io.opentelemetry.api.trace.Span.current().getSpanContext();
      return new CallContext(
          spanCtx.isValid() ? spanCtx.getTraceId() : null,
          org.slf4j.MDC.get("request_id"),
          new CallContext.CancelSignal() {
            @Override
            public boolean isCancelled() {
              return expired.get() || cancelledByCaller.get();
            }

            @Override
            public void onCancel(Runnable handler) {
              cancelHandler = handler;
              if (isCancelled()) {
                handler.run();
              }
            }
          });
    }

    boolean expired() {
      return expired.get();
    }

    boolean cancelledByCaller() {
      return cancelledByCaller.get();
    }

    long budgetMs() {
      return budgetMs;
    }

    @Override
    public void close() {
      alarm.cancel(false);
    }
  }

  private <T> T withBudget(String operation, long budgetMs, Function<Budget, T> body) {
    try (Budget budget = new Budget(budgetMs)) {
      T result = body.apply(budget);
      if (budget.expired()) {
        throw WorkerServiceException.deadlineExceeded(
            operation + " exceeded its " + budget.budgetMs() + "ms budget");
      }
      return result;
    }
  }

  @Override
  protected <T> T executeSearchRpc(
      String operation, RpcDeadlineCategory category, Function<SearchServiceCalls, T> rpc) {
    // The one gate layer: a Search that internally reranks must count once, not twice.
    return foregroundLoad.call(
        operation,
        () ->
            withBudget(
                operation,
                deadline(category),
                budget -> rpc.apply(new WorkerSearchCalls(services.get().searchService(), budget.context()))));
  }

  @Override
  protected <T> T executeIngestRpc(
      String operation, RpcDeadlineCategory category, Function<IngestServiceCalls, T> rpc) {
    return withBudget(
        operation,
        deadline(category),
        budget -> rpc.apply(new WorkerIngestCalls(services.get().ingestService(), budget.context())));
  }

  @Override
  protected <T> T executeHealthRpc(
      String operation, long callDeadlineMs, Function<HealthServiceCalls, T> rpc) {
    return withBudget(
        operation, callDeadlineMs, budget -> rpc.apply(new WorkerHealthCalls(services.get().healthService())));
  }

  @Override
  protected ScanRootProgress executeScanRoot(
      ScanRootRequest request, CancelToken cancelToken, Consumer<ScanRootProgress> progressConsumer) {
    try (Budget budget = new Budget(deadline(RpcDeadlineCategory.LONG_RUNNING))) {
      budget.bind(cancelToken);
      ScanRootProgress[] last = {null};
      services
          .get()
          .ingestService()
          .scanRoot(
              request,
              event -> {
                last[0] = event;
                progressConsumer.accept(event);
              },
              budget.context());
      if (budget.cancelledByCaller()) {
        ScanRootProgress cancelled = scanCancelledEvent();
        progressConsumer.accept(cancelled);
        return cancelled;
      }
      if (last[0] == null) {
        ScanRootProgress empty = scanEmptyStreamEvent();
        progressConsumer.accept(empty);
        return empty;
      }
      return last[0];
    }
  }

  @Override
  public IndexingJobsStream subscribeIndexingJobs(
      Consumer<IndexingJobsFrame> onFrame, Consumer<Throwable> onError, Runnable onCompleted) {
    AtomicBoolean closed = new AtomicBoolean(false);
    CallContext ctx = new CallContext(null, null, closed::get);
    streamThreads.execute(
        () -> {
          try {
            services
                .get()
                .ingestService()
                .subscribeIndexingJobs(
                    SubscribeIndexingJobsRequest.newBuilder().build(),
                    frame -> {
                      if (!closed.get()) {
                        onFrame.accept(frame);
                      }
                    },
                    ctx);
            if (!closed.get()) {
              onCompleted.run();
            }
          } catch (RuntimeException e) {
            if (!closed.get()) {
              onError.accept(e);
            }
          }
        });
    return () -> closed.set(true);
  }

  @Override
  protected void closeTransport() {
    deadlines.shutdownNow();
    streamThreads.shutdownNow();
    log.debug("Engine knowledge client call scheduler stopped");
  }
}
