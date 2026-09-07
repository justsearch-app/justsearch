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
import java.util.concurrent.atomic.AtomicReference;
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

  /**
   * How long a finished scan waits for its already-accepted progress frames to reach the consumer.
   * Generous on purpose: the alternative to waiting is dropping the terminal event, and a scan ends
   * once.
   */
  private static final long SCAN_DRAIN_TIMEOUT_MS = 30_000L;

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
   * The in-process equivalent of what {@code WorkerServiceCalls.callContext} read off the two
   * server interceptors. Caller and callee share the thread here, so the OTel span and the logging
   * MDC are already current — there is nothing to propagate across, only to read in place.
   */
  private static String currentTraceId() {
    io.opentelemetry.api.trace.SpanContext spanCtx =
        io.opentelemetry.api.trace.Span.current().getSpanContext();
    return spanCtx.isValid() ? spanCtx.getTraceId() : null;
  }

  /** See {@link #currentTraceId()}. */
  private static String currentRequestId() {
    return org.slf4j.MDC.get("request_id");
  }

  /**
   * A unary call's deadline: a cancellation signal that flips when the budget elapses, plus the
   * means to tell afterwards whether it did. The streaming calls do not use this — their deadline
   * is a cancel with no retro-thrown status, wired through {@link FlowCancelSignal}.
   */
  private final class Budget implements AutoCloseable {
    private final AtomicBoolean expired = new AtomicBoolean(false);
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

    CallContext context() {
      return new CallContext(
          currentTraceId(),
          currentRequestId(),
          new CallContext.CancelSignal() {
            @Override
            public boolean isCancelled() {
              return expired.get();
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
    // Item A8: the same bounded hand-off item A7 built, between the WALKER thread and the SSE
    // fan-out. `WorkerScanOps` emits one progress frame per 100 files straight into the sink, so
    // without it a `ScanProgressRegistry` write (and every SSE writer behind it) runs inside
    // `Files.walkFileTree` — the walk would be paced by the slowest connected browser.
    AtomicReference<ScanRootProgress> last = new AtomicReference<>();
    AtomicReference<Throwable> deliveryFailure = new AtomicReference<>();
    BoundedHandoff<ScanRootProgress> flow =
        new BoundedHandoff<>(
            "scan-root-flow",
            event -> {
              last.set(event);
              progressConsumer.accept(event);
            },
            deliveryFailure::set,
            streamThreads);

    // Cancellation, in the shape WorkerScanOps already polls: it checks `ctx.cancelled()` per file
    // and per directory, so a cancel lands within a file — well inside the 100-file progress tick
    // the item asks for.
    FlowCancelSignal cancel = new FlowCancelSignal();
    flow.onClose(cancel::cancel);
    if (cancelToken != null) {
      cancelToken.onCancel(cancel::cancel);
      if (cancelToken.isCancelled()) {
        cancel.cancel();
      }
    }

    // The scan's deadline is a cancel, not a retro-thrown status: a stream that outruns its budget
    // has to STOP, and the terminal event the walker then emits is the honest answer. This is the
    // same LONG_RUNNING category the wire applied.
    ScheduledFuture<?> alarm =
        deadlines.schedule(
            cancel::cancel, deadline(RpcDeadlineCategory.LONG_RUNNING), TimeUnit.MILLISECONDS);
    try {
      services
          .get()
          .ingestService()
          .scanRoot(
              request,
              event -> {
                if (!flow.publish(event)) {
                  // The consumer stopped draining, or the caller closed the flow: stop walking.
                  cancel.cancel();
                }
              },
              new CallContext(currentTraceId(), currentRequestId(), cancel));
    } finally {
      alarm.cancel(false);
      // The walk has ended; let the frames it already handed over reach the consumer before the
      // flow closes, or the terminal event is exactly what gets dropped.
      flow.drainAndClose(SCAN_DRAIN_TIMEOUT_MS);
    }

    Throwable failed = deliveryFailure.get();
    if (failed != null) {
      throw WorkerServiceException.internal("scanRoot progress delivery failed: " + failed);
    }
    ScanRootProgress terminal = last.get();
    if (terminal != null) {
      // The worker already stamps CLIENT_CANCELLED on its own terminal event when it observes the
      // cancel, so a synthetic one here would be a second terminal event for the same scan.
      return terminal;
    }
    ScanRootProgress synthesised =
        cancel.isCancelled() ? scanCancelledEvent() : scanEmptyStreamEvent();
    progressConsumer.accept(synthesised);
    return synthesised;
  }

  @Override
  public IndexingJobsStream subscribeIndexingJobs(
      Consumer<IndexingJobsFrame> onFrame, Consumer<Throwable> onError, Runnable onCompleted) {
    // Item A7: a bounded hand-off between the change feed's dispatch thread and the SSE fan-out.
    // Without it the fan-out would run ON the SQLite update-hook thread, so a slow HTTP client
    // would pace the indexing loop — the backpressure the Netty send buffer used to absorb.
    BoundedHandoff<IndexingJobsFrame> flow =
        new BoundedHandoff<>("indexing-jobs-flow", onFrame, onError, streamThreads);
    FlowCancelSignal cancel = new FlowCancelSignal();
    flow.onClose(cancel::cancel);
    CallContext ctx = new CallContext(null, null, cancel);
    streamThreads.execute(
        () -> {
          try {
            services
                .get()
                .ingestService()
                .subscribeIndexingJobs(
                    SubscribeIndexingJobsRequest.newBuilder().build(),
                    frame -> {
                      if (!flow.publish(frame)) {
                        // Closed, or the consumer stopped draining: stop the producer. The worker
                        // reads this through ctx.cancelled() on its next delta and closes its
                        // change-feed subscription.
                        cancel.cancel();
                      }
                    },
                    ctx);
            // subscribeIndexingJobs RETURNS WHILE THE STREAM IS STILL OPEN — it registers a
            // change-feed subscription whose deltas arrive later, on the feed's own threads. So a
            // normal return is NOT completion, and calling onCompleted here would tell the bridge
            // the producer had closed the stream while frames were still arriving.
          } catch (RuntimeException e) {
            flow.fail(e);
          }
        });
    // onCompleted has no producer in process, deliberately: the change-feed subscription lives
    // until it is cancelled, so "the producer closed the stream" is a wire-only event (a server
    // shutting down its call). The caller's close() is the only way this flow ends, and the caller
    // already knows it closed it.
    return () -> {
      flow.close();
      cancel.cancel();
    };
  }

  @Override
  protected void closeTransport() {
    deadlines.shutdownNow();
    streamThreads.shutdownNow();
    log.debug("Engine knowledge client call scheduler stopped");
  }
}
