/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.core.execution.EngineExecutorSpec.Kind;
import io.justsearch.core.execution.EngineExecutorSpec.Mode;

import io.justsearch.app.services.worker.CancelToken;
import io.justsearch.app.services.worker.HealthServiceCalls;
import io.justsearch.app.services.worker.IngestServiceCalls;
import io.justsearch.app.api.knowledge.KnowledgeClientException;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * <p><b>The foreground gauge.</b> {@link ForegroundLoadGate} reads explicit work urgency at the
 * common unary and streaming lifecycle boundaries. A search that internally reranks is wrapped
 * once. Work ownership outlives a released caller until every asynchronous owner exits; durable
 * foreground work holds one increment until completion or waiting-client detachment.
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
  private final Runnable requestedRestartAction;
  private final EngineExecutorRegistry.Registration deadlineRegistration;
  private final EngineExecutorRegistry.Registration foregroundCallRegistration;
  private final EngineExecutorRegistry.Registration backgroundCallRegistration;
  private final EngineExecutorRegistry.Registration foregroundStreamRegistration;
  private final EngineExecutorRegistry.Registration backgroundStreamRegistration;
  private final ScheduledExecutorService deadlines;
  private final ExecutorService foregroundCallThreads;
  private final ExecutorService backgroundCallThreads;
  private final ExecutorService foregroundStreamThreads;
  private final ExecutorService backgroundStreamThreads;

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
      EngineExecutorRegistry executors,
      java.util.function.Supplier<WorkerAppServices> services,
      ForegroundLoadGate foregroundLoad,
      long deadlineMs,
      int batchSize,
      IpcTelemetry telemetry) {
    this(executors, services, foregroundLoad, deadlineMs, batchSize, telemetry, () -> {});
  }

  EngineKnowledgeClient(
      EngineExecutorRegistry executors,
      java.util.function.Supplier<WorkerAppServices> services,
      ForegroundLoadGate foregroundLoad,
      long deadlineMs,
      int batchSize,
      IpcTelemetry telemetry,
      Runnable requestedRestartAction) {
    this(executors, services, foregroundLoad, deadlineMs, batchSize, telemetry, requestedRestartAction,
        new EngineAdmissionController(EngineResourcePolicy.load()));
  }

  EngineKnowledgeClient(
      EngineExecutorRegistry executors,
      java.util.function.Supplier<WorkerAppServices> services,
      ForegroundLoadGate foregroundLoad, long deadlineMs, int batchSize, IpcTelemetry telemetry,
      Runnable requestedRestartAction, io.justsearch.app.api.EngineAdmissionService admission) {
    super(executors, deadlineMs, batchSize, telemetry);
    Objects.requireNonNull(executors, "executors");
    this.admission = Objects.requireNonNull(admission, "admission");
    this.requestedRestartAction = Objects.requireNonNull(requestedRestartAction, "requestedRestartAction");
    this.services = Objects.requireNonNull(services, "services");
    this.foregroundLoad = Objects.requireNonNull(foregroundLoad, "foregroundLoad");
    ExecutorOwners owners;
    try {
      owners = openExecutorOwners(executors);
    } catch (RuntimeException | Error failure) {
      closeBaseExecutors();
      throw failure;
    }
    this.deadlineRegistration = owners.deadlineRegistration();
    this.foregroundCallRegistration = owners.foregroundCallRegistration();
    this.backgroundCallRegistration = owners.backgroundCallRegistration();
    this.foregroundStreamRegistration = owners.foregroundStreamRegistration();
    this.backgroundStreamRegistration = owners.backgroundStreamRegistration();
    this.deadlines = owners.deadlines();
    this.foregroundCallThreads = owners.foregroundCallThreads();
    this.backgroundCallThreads = owners.backgroundCallThreads();
    this.foregroundStreamThreads = owners.foregroundStreamThreads();
    this.backgroundStreamThreads = owners.backgroundStreamThreads();
  }

  private record ExecutorOwners(
      EngineExecutorRegistry.Registration deadlineRegistration,
      EngineExecutorRegistry.Registration foregroundCallRegistration,
      EngineExecutorRegistry.Registration backgroundCallRegistration,
      EngineExecutorRegistry.Registration foregroundStreamRegistration,
      EngineExecutorRegistry.Registration backgroundStreamRegistration,
      ScheduledExecutorService deadlines,
      ExecutorService foregroundCallThreads,
      ExecutorService backgroundCallThreads,
      ExecutorService foregroundStreamThreads,
      ExecutorService backgroundStreamThreads) {}

  private static ExecutorOwners openExecutorOwners(EngineExecutorRegistry executors) {
    EngineExecutorRegistry.Limits foreground = executors.limits(Kind.FOREGROUND);
    EngineExecutorRegistry.Limits background = executors.limits(Kind.BACKGROUND);
    var registrations = new java.util.ArrayList<EngineExecutorRegistry.Registration>();
    try {
      var deadline =
          scheduled(
              executors,
              "engine-knowledge-deadlines",
              Kind.BACKGROUND,
              1,
              background.maxQueue());
      registrations.add(deadline);
      var foregroundCall =
          platform(executors, "engine-knowledge-call-foreground", Kind.FOREGROUND, foreground);
      registrations.add(foregroundCall);
      var backgroundCall =
          platform(executors, "engine-knowledge-call-background", Kind.BACKGROUND, background);
      registrations.add(backgroundCall);
      var foregroundStream =
          platform(executors, "engine-knowledge-stream-foreground", Kind.FOREGROUND, foreground);
      registrations.add(foregroundStream);
      var backgroundStream =
          platform(executors, "engine-knowledge-stream-background", Kind.BACKGROUND, background);
      registrations.add(backgroundStream);

      ScheduledExecutorService deadlineExecutor =
          deadline.openScheduled(daemonFactory("engine-call-deadlines"));
      ExecutorService foregroundCallExecutor =
          foregroundCall.open(daemonFactory("engine-call-foreground"));
      ExecutorService backgroundCallExecutor =
          backgroundCall.open(daemonFactory("engine-call-background"));
      ExecutorService foregroundStreamExecutor =
          foregroundStream.open(daemonFactory("engine-stream-foreground"));
      ExecutorService backgroundStreamExecutor =
          backgroundStream.open(daemonFactory("engine-stream-background"));
      return new ExecutorOwners(
          deadline,
          foregroundCall,
          backgroundCall,
          foregroundStream,
          backgroundStream,
          deadlineExecutor,
          foregroundCallExecutor,
          backgroundCallExecutor,
          foregroundStreamExecutor,
          backgroundStreamExecutor);
    } catch (RuntimeException | Error failure) {
      for (int i = registrations.size() - 1; i >= 0; i--) {
        try {
          registrations.get(i).close();
        } catch (RuntimeException cleanupFailure) {
          failure.addSuppressed(cleanupFailure);
        }
      }
      throw failure;
    }
  }

  private static EngineExecutorRegistry.Registration platform(
      EngineExecutorRegistry executors,
      String name,
      Kind kind,
      EngineExecutorRegistry.Limits limits) {
    return executors.register(
        new EngineExecutorSpec(
            name, kind, Mode.PLATFORM, limits.maxThreads(), limits.maxQueue(), 1));
  }

  private static EngineExecutorRegistry.Registration scheduled(
      EngineExecutorRegistry executors, String name, Kind kind, int threads, int queue) {
    return executors.register(
        new EngineExecutorSpec(name, kind, Mode.SCHEDULED, threads, queue, 1));
  }

  private static java.util.concurrent.ThreadFactory daemonFactory(String name) {
    return runnable -> {
      Thread thread = new Thread(runnable, name);
      thread.setDaemon(true);
      return thread;
    };
  }

  private static boolean foreground(EngineContext context) {
    return context.urgency() == EngineContext.Urgency.FOREGROUND;
  }

  private ExecutorService callThreads(EngineContext context) {
    return foreground(context) ? foregroundCallThreads : backgroundCallThreads;
  }

  private ExecutorService streamThreads(EngineContext context) {
    return foreground(context) ? foregroundStreamThreads : backgroundStreamThreads;
  }

  @Override
  public io.justsearch.app.api.IndexingService.MigrationOutcome startMigration(String reason, EngineContext engineContext) {
    var outcome = super.startMigration(reason, engineContext);
    if (outcome.accepted() && outcome.restartRequired()) requestedRestartAction.run();
    return outcome;
  }

  @Override
  public io.justsearch.app.api.IndexingService.MigrationOutcome rollbackMigration(EngineContext engineContext) {
    var outcome = super.rollbackMigration(engineContext);
    if (outcome.accepted() && outcome.restartRequired()) requestedRestartAction.run();
    return outcome;
  }

  /**
   * The in-process equivalent of what {@code WorkerServiceCalls.callContext} read off the two
   * server interceptors. Capture on the entering thread before dispatching a unary worker; its
   * executor thread does not inherit the caller's OTel span or logging MDC.
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
  /**
   * One call's outcome, decided exactly once (review B3).
   *
   * <p>The first cut had a race that turned successes into failures: the alarm could fire between
   * {@code body.apply(...)} returning and {@code expired()} being read, so a call that finished
   * inside its budget was reported as DEADLINE_EXCEEDED. The states are now a single CAS —
   * whichever of "the body completed" and "the budget elapsed" gets there first wins, and the other
   * is a no-op.
   */
  private enum Outcome {
    RUNNING,
    COMPLETED,
    EXPIRED,
    CANCELLED
  }

  private final io.justsearch.app.api.EngineAdmissionService admission;

  private final class Budget implements AutoCloseable {
    private final AtomicReference<Outcome> outcome = new AtomicReference<>(Outcome.RUNNING);
    private final FlowCancelSignal signal = new FlowCancelSignal();
    private final String traceId = currentTraceId();
    private final String requestId = currentRequestId();
    private final Object workerLock = new Object();
    private final io.justsearch.app.api.EngineWorkHandle work;
    private final Consumer<Throwable> fail;
    private final io.justsearch.app.api.EngineWorkHandle.Registration cancellation;
    private final AtomicReference<OwnedCallTask> submission = new AtomicReference<>();
    private Thread worker;
    private final ScheduledFuture<?> alarm;

    Budget(long budgetMs, io.justsearch.app.api.EngineWorkHandle work, Consumer<Throwable> fail) {
      this.work = work;
      this.fail = fail;
      this.alarm = scheduleDeadline(this::expire, budgetMs);
      try {
        this.cancellation = work.onCancel(this::cancel);
      } catch (RuntimeException | Error failure) {
        alarm.cancel(false);
        throw failure;
      }
    }

    /** Flips the budget to EXPIRED and signals the worker to unwind. Idempotent. */
    void expire() {
      if (outcome.compareAndSet(Outcome.RUNNING, Outcome.EXPIRED)) {
        fail.accept(new KnowledgeClientException(KnowledgeClientException.Status.DEADLINE_EXCEEDED,
            "Engine call exceeded its deadline"));
        signalAndInterrupt();
        cancelQueuedSubmission();
      }
    }

    boolean cancel(String reason) {
      if (outcome.compareAndSet(Outcome.RUNNING, Outcome.CANCELLED)) {
        fail.accept(new io.justsearch.app.api.EngineWorkCancelledException(reason));
        signalAndInterrupt();
        cancelQueuedSubmission();
        return true;
      }
      return false;
    }

    private void signalAndInterrupt() {
      try {
        signal.cancel();
      } finally {
        synchronized (workerLock) {
          if (worker != null) worker.interrupt();
        }
      }
    }

    boolean startWork() {
      synchronized (workerLock) {
        if (outcome.get() != Outcome.RUNNING) return false;
        worker = Thread.currentThread();
        return true;
      }
    }

    void workerFinished() {
      synchronized (workerLock) {
        worker = null;
        if (signal.isCancelled()) Thread.interrupted();
      }
    }

    void submitted(OwnedCallTask task) {
      submission.set(task);
      if (outcome.get() != Outcome.RUNNING) {
        task.cancelBeforeStart();
      }
    }

    private void cancelQueuedSubmission() {
      OwnedCallTask task = submission.get();
      if (task != null) task.cancelBeforeStart();
    }

    /** @return true if this call completed before the budget elapsed. */
    boolean complete() {
      return outcome.compareAndSet(Outcome.RUNNING, Outcome.COMPLETED);
    }

    CallContext context() {
      EngineContext engineContext = work.context();
      return new CallContext(
          traceId,
          requestId,
          signal, engineContext, enqueueProvenance(engineContext));
    }

    @Override
    public void close() {
      alarm.cancel(false);
      cancellation.close();
    }
  }

  /**
   * A queued call remains an owner of admitted work, even though no worker thread has started it.
   * The registry cancels queued {@link Future}s during owner shutdown, so this task makes that
   * cancellation close the work reference immediately. Once {@link #run()} wins, only its actual
   * exit closes the reference.
   */
  private static final class OwnedCallTask implements Runnable, Future<Void> {
    private static final int QUEUED = 0;
    private static final int RUNNING = 1;
    private static final int FINISHED = 2;
    private static final int CANCELLED = 3;

    private final AtomicInteger state = new AtomicInteger(QUEUED);
    private final java.util.concurrent.CompletableFuture<Void> completion =
        new java.util.concurrent.CompletableFuture<>();
    private final Budget budget;
    private final io.justsearch.app.api.EngineWorkHandle work;
    private final Runnable body;
    private ExecutorService queueOwner;

    private OwnedCallTask(
        Budget budget, io.justsearch.app.api.EngineWorkHandle work, Runnable body) {
      this.budget = budget;
      this.work = work;
      this.body = body;
    }

    synchronized void executeOn(ExecutorService executor) {
      this.queueOwner = executor;
      if (!isCancelled()) executor.execute(this);
    }

    @Override
    public void run() {
      if (!state.compareAndSet(QUEUED, RUNNING)) return;
      try {
        body.run();
        completion.complete(null);
      } catch (Throwable failure) {
        completion.completeExceptionally(failure);
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        throw new IllegalStateException(failure);
      } finally {
        state.set(FINISHED);
      }
    }

    synchronized boolean cancelBeforeStart() {
      if (!state.compareAndSet(QUEUED, CANCELLED)) return false;
      try {
        if (queueOwner instanceof java.util.concurrent.ThreadPoolExecutor pool) {
          pool.remove(this);
        }
        work.close();
      } finally {
        completion.cancel(false);
      }
      return true;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      budget.cancel("engine_shutdown");
      return cancelBeforeStart() || isCancelled();
    }

    @Override
    public boolean isCancelled() {
      return state.get() == CANCELLED;
    }

    @Override
    public boolean isDone() {
      return completion.isDone();
    }

    @Override
    public Void get() throws InterruptedException, java.util.concurrent.ExecutionException {
      return completion.get();
    }

    @Override
    public Void get(long timeout, TimeUnit unit)
        throws InterruptedException,
            java.util.concurrent.ExecutionException,
            java.util.concurrent.TimeoutException {
      return completion.get(timeout, unit);
    }
  }

  /**
   * Translates the index half's failure vocabulary into the port's (review B1).
   *
   * <p>{@code WorkerServiceException} is the right home for the vocabulary — it is a property of
   * the work — but it lives behind an {@code implementation} edge that {@code ui} cannot name, and
   * {@code ui} is where the status has to arrive to become an HTTP code. Before this, every worker
   * error reached the API front as a bare {@code RuntimeException} and fell through to a 500: an
   * invalid cursor stopped being a 400, a deadline a 504, an unavailable index a 503. Nothing
   * failed; the answers silently got worse.
   *
   * <p>Anything that is NOT a {@code WorkerServiceException} is left alone: a bug in the index half
   * is not a client-facing status, and dressing it as one would hide it.
   */
  private static RuntimeException translate(RuntimeException e) {
    if (e instanceof WorkerServiceException w) {
      return new KnowledgeClientException(
          KnowledgeClientException.Status.valueOf(w.status().name()), w.getMessage(), w);
    }
    return e;
  }

  private io.justsearch.app.api.EngineAdmissionException engineLimit() {
    return new io.justsearch.app.api.EngineAdmissionException(
        io.justsearch.app.api.EngineAdmissionException.Reason.ENGINE_LIMIT,
        admission.retryAfterSeconds());
  }

  private ScheduledFuture<?> scheduleDeadline(Runnable task, long delayMs) {
    try {
      return deadlines.schedule(task, delayMs, TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.RejectedExecutionException failure) {
      throw engineLimit();
    }
  }

  /**
   * Runs one unary call under its budget, and <b>returns at the budget</b> (review B3).
   *
   * <p>The first cut armed a cancel and then checked, after the fact, whether the budget had
   * elapsed — so the CALLER was never released early: a slow search ran to completion and only then
   * reported DEADLINE_EXCEEDED. That is not what the deadline meant on the wire, where gRPC
   * completed the caller's call at the deadline while the server unwound on its own. So the body
   * runs on a call thread and the caller waits with the budget as its timeout; on timeout the
   * caller is released immediately and the budget's cancel signal tells the worker to stop, which
   * {@code CallContext.cancelled()} is polled for at every stage boundary.
   *
   * <p>The thread hop is the cost of the property. Its foreground/background thread and queue
   * bounds come from the Engine executor registry; saturation is reported as RESOURCE_EXHAUSTED.
   */
  private <T> T withBudget(String operation, long budgetMs, EngineContext engineContext,
      Function<Budget, T> body) {
    var work = admission.attach(engineContext);
    var pending = new java.util.concurrent.CompletableFuture<T>();
    Budget budget;
    try {
      budget = new Budget(budgetMs, work, pending::completeExceptionally);
    } catch (RuntimeException | Error failure) {
      work.close();
      throw failure;
    }
    try {
      OwnedCallTask task = new OwnedCallTask(budget, work, () -> {
        T result = null;
        Throwable failure = null;
        try {
          if (budget.startWork()) {
            result = foregroundLoad.call(work, () -> body.apply(budget));
          }
        } catch (Throwable cause) {
          failure = cause;
        } finally {
          try {
            budget.workerFinished();
            work.close();
          } catch (Throwable cleanupFailure) {
            if (failure == null) failure = cleanupFailure;
            else failure.addSuppressed(cleanupFailure);
          }
        }
        if (budget.complete()) {
          if (failure == null) pending.complete(result);
          else pending.completeExceptionally(failure);
        }
      });
      ExecutorService executor = callThreads(work.context());
      budget.submitted(task);
      task.executeOn(executor);
    } catch (java.util.concurrent.RejectedExecutionException e) {
      OwnedCallTask task = budget.submission.get();
      if (task == null || !task.isCancelled()) {
        budget.close();
        if (task == null) work.close();
        else task.cancelBeforeStart();
        throw engineLimit();
      }
    }
    try {
      // The scheduled budget completes this future before signalling the worker. The work
      // reference stays with the runnable until it actually exits, even after caller release.
      return pending.get(budgetMs, TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.TimeoutException e) {
      // An independent wait bound survives scheduler shutdown or a delayed timer thread.
      budget.expire();
      return decidedResult(operation, pending);
    } catch (java.util.concurrent.CancellationException cancelled) {
      throw cancellationReason(cancelled);
    } catch (java.util.concurrent.ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException re) {
        throw translate(re);
      }
      if (cause instanceof Error err) {
        throw err;
      }
      throw new KnowledgeClientException(
          KnowledgeClientException.Status.INTERNAL, operation + " failed: " + cause, cause);
    } catch (InterruptedException e) {
      budget.cancel("caller_interrupted");
      Thread.currentThread().interrupt();
      // A caller interrupt cannot overwrite an outcome whose CAS already won. join preserves
      // the interrupt flag while projecting that winner, including a concurrently completed result.
      return decidedResult(operation, pending);
    } finally {
      budget.close();
    }
  }

  private static <T> T decidedResult(String operation, java.util.concurrent.CompletableFuture<T> pending) {
    try {
      return pending.join();
    } catch (java.util.concurrent.CancellationException cancelled) {
      throw cancellationReason(cancelled);
    } catch (java.util.concurrent.CompletionException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof RuntimeException runtime) throw translate(runtime);
      if (cause instanceof Error error) throw error;
      throw new KnowledgeClientException(KnowledgeClientException.Status.INTERNAL,
          operation + " failed: " + cause, cause);
    }
  }

  private static java.util.concurrent.CancellationException cancellationReason(
      java.util.concurrent.CancellationException cancelled) {
    // CompletableFuture may copy a CancellationException across threads, retaining the typed
    // original only as its cause. Preserve the work owner's first reason at the port boundary.
    if (cancelled.getCause() instanceof io.justsearch.app.api.EngineWorkCancelledException reason) {
      return reason;
    }
    return cancelled;
  }

  @Override
  protected <T> T executeSearchRpc(
      String operation, RpcDeadlineCategory category, Function<SearchServiceCalls, T> rpc,
      EngineContext engineContext) {
    // The gate wraps the WORKER's work, not the caller's wait (review B3): when a call times out
    // the caller is released immediately, and the gauge must not drop until the worker actually
    // unwinds — otherwise a timed-out search would read as "no foreground load" while it is still
    // burning CPU, and indexing would un-throttle at exactly the wrong moment. One layer still: a
    // Search that internally reranks counts once.
    return withBudget(
        operation,
        deadline(category),
        engineContext,
        budget -> rpc.apply(new WorkerSearchCalls(services.get().searchService(), budget.context())));
  }

  @Override
  protected <T> T executeIngestRpc(
      String operation, RpcDeadlineCategory category, Function<IngestServiceCalls, T> rpc,
      EngineContext engineContext) {
    return withBudget(
        operation,
        deadline(category),
        engineContext,
        budget -> rpc.apply(new WorkerIngestCalls(services.get().ingestService(), budget.context())));
  }

  @Override
  protected <T> T executeHealthRpc(
      String operation, long callDeadlineMs, Function<HealthServiceCalls, T> rpc,
      EngineContext engineContext) {
    // Review B3: the health call was the one that passed no context at all. WorkerHealthService
    // does not read one today, but a call that cannot be cancelled is a call the budget cannot
    // bound, and the health poll is exactly the call a wedged index half hangs.
    return withBudget(
        operation,
        callDeadlineMs,
        engineContext,
        budget -> rpc.apply(new WorkerHealthCalls(services.get().healthService(), budget.context())));
  }

  /**
   * Turns a saturated urgency-specific stream pool into an answer.
   *
   * <p>Bounding the pool (S4) is only half the change: a {@code RejectedExecutionException} escaping
   * as itself would reach the API front as a bare runtime failure and become a 500 with a stack
   * trace about thread pools. RESOURCE_EXHAUSTED is the same status the call pool already reports
   * for the same condition, it maps to 429, and it tells the caller the true thing — try again,
   * this engine is already carrying as many streams as it will carry.
   */
  private <T> T refuseIfSaturated(java.util.function.Supplier<T> body) {
    try {
      return body.get();
    } catch (java.util.concurrent.RejectedExecutionException e) {
      throw engineLimit();
    }
  }

  /**
   * Submits a stream's producer, closing the flow if the pool has no room for it. Separate from
   * {@link #refuseIfSaturated} because the failure has a side effect to undo, not just a status to
   * report.
   */
  private void submitProducerOrClose(BoundedHandoff<?> flow, Runnable producer,
      io.justsearch.app.api.EngineWorkHandle work) {
    try {
      executeOwnedStream(work, producer, false);
    } catch (java.util.concurrent.RejectedExecutionException e) {
      flow.close();
      throw engineLimit();
    }
  }

  private void executeOwnedStream(io.justsearch.app.api.EngineWorkHandle work, Runnable body,
      boolean countForeground) {
    var owner = work.retain();
    var task = new OwnedStreamTask(work, owner, body, countForeground);
    try {
      ExecutorService executor = streamThreads(work.context());
      task.armCancellation();
      task.executeOn(executor);
    } catch (RuntimeException | Error failure) {
      task.cancel(false);
      throw failure;
    }
  }

  /** A queued stream task releases its retained work when cancellation prevents actual start. */
  private final class OwnedStreamTask implements Runnable, Future<Void> {
    private static final int QUEUED = 0;
    private static final int RUNNING = 1;
    private static final int FINISHED = 2;
    private static final int CANCELLED = 3;

    private final AtomicInteger state = new AtomicInteger(QUEUED);
    private final java.util.concurrent.CompletableFuture<Void> completion =
        new java.util.concurrent.CompletableFuture<>();
    private final io.justsearch.app.api.EngineWorkHandle work;
    private final io.justsearch.app.api.EngineWorkHandle owner;
    private final Runnable body;
    private final boolean countForeground;
    private ExecutorService queueOwner;
    private final AtomicReference<io.justsearch.app.api.EngineWorkHandle.Registration> cancellation =
        new AtomicReference<>();

    private OwnedStreamTask(
        io.justsearch.app.api.EngineWorkHandle work,
        io.justsearch.app.api.EngineWorkHandle owner,
        Runnable body,
        boolean countForeground) {
      this.work = work;
      this.owner = owner;
      this.body = body;
      this.countForeground = countForeground;
    }

    synchronized void executeOn(ExecutorService executor) {
      this.queueOwner = executor;
      if (!isCancelled()) executor.execute(this);
    }

    void armCancellation() {
      var registration = work.onCancel(reason -> cancel(false));
      cancellation.set(registration);
      if (isDone()) registration.close();
    }

    @Override
    public void run() {
      if (!state.compareAndSet(QUEUED, RUNNING)) return;
      Throwable failure = null;
      try {
        if (countForeground) foregroundLoad.run(owner, body);
        else body.run();
      } catch (Throwable cause) {
        failure = cause;
      } finally {
        state.set(FINISHED);
        cleanup();
      }
      if (failure == null) {
        completion.complete(null);
      } else {
        completion.completeExceptionally(failure);
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        throw new IllegalStateException(failure);
      }
    }

    private void cleanup() {
      var registration = cancellation.getAndSet(null);
      if (registration != null) registration.close();
      owner.close();
    }

    @Override
    public synchronized boolean cancel(boolean mayInterruptIfRunning) {
      if (!state.compareAndSet(QUEUED, CANCELLED)) return false;
      try {
        if (queueOwner instanceof java.util.concurrent.ThreadPoolExecutor pool) {
          pool.remove(this);
        }
        cleanup();
      } finally {
        completion.cancel(false);
      }
      return true;
    }

    @Override
    public boolean isCancelled() {
      return state.get() == CANCELLED;
    }

    @Override
    public boolean isDone() {
      return completion.isDone();
    }

    @Override
    public Void get() throws InterruptedException, java.util.concurrent.ExecutionException {
      return completion.get();
    }

    @Override
    public Void get(long timeout, TimeUnit unit)
        throws InterruptedException,
            java.util.concurrent.ExecutionException,
            java.util.concurrent.TimeoutException {
      return completion.get(timeout, unit);
    }
  }

  @Override
  protected ScanRootProgress executeScanRoot(
      ScanRootRequest request, CancelToken cancelToken, Consumer<ScanRootProgress> progressConsumer,
      EngineContext engineContext) {
    try (var work = admission.attach(engineContext)) {
      return foregroundLoad.call(work, () -> scanRootWork(request, cancelToken, progressConsumer, work));
    }
  }

  private ScanRootProgress scanRootWork(ScanRootRequest request, CancelToken cancelToken,
      Consumer<ScanRootProgress> progressConsumer, io.justsearch.app.api.EngineWorkHandle work) {
    String traceId = currentTraceId();
    String requestId = currentRequestId();
    // Item A8: the same bounded hand-off item A7 built, between the WALKER thread and the SSE
    // fan-out. `WorkerScanOps` emits one progress frame per 100 files straight into the sink, so
    // without it a `ScanProgressRegistry` write (and every SSE writer behind it) runs inside
    // `Files.walkFileTree` — the walk would be paced by the slowest connected browser.
    AtomicReference<ScanRootProgress> last = new AtomicReference<>();
    AtomicReference<Throwable> deliveryFailure = new AtomicReference<>();
    BoundedHandoff<ScanRootProgress> flow =
        refuseIfSaturated(
            () ->
                new BoundedHandoff<>(
                    "scan-root-flow",
                    event -> {
                      last.set(event);
                      progressConsumer.accept(event);
                    },
                    deliveryFailure::set,
                    body -> executeOwnedStream(work, body, false),
                    // BLOCK: the producer is the walker thread, which holds a directory iterator
                    // and nothing else. Pausing it pauses this scan and nothing else — the
                    // backpressure the wire used to apply. Compare subscribeIndexingJobs, whose
                    // producer holds a lock somebody else needs.
                    BoundedHandoff.Backpressure.BLOCK));

    // Cancellation, in the shape WorkerScanOps already polls: it checks `ctx.cancelled()` per file
    // and per directory, so a cancel lands within a file — well inside the 100-file progress tick
    // the item asks for.
    FlowCancelSignal cancel = new FlowCancelSignal();
    try (flow) {
      flow.onClose(cancel::cancel);
      try (var _ = work.onCancel(reason -> cancel.cancel())) {
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
            scheduleDeadline(cancel::cancel, deadline(RpcDeadlineCategory.LONG_RUNNING));
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
                  new CallContext(traceId, requestId, cancel, work.context(), enqueueProvenance(work.context())));
        } finally {
          alarm.cancel(false);
          // The walk has ended; let the frames it already handed over reach the consumer before the
          // flow closes, or the terminal event is exactly what gets dropped.
          if (!flow.drainAndClose(SCAN_DRAIN_TIMEOUT_MS)) {
            // Review S3: the return value was being discarded, so a scan whose tail was dropped
            // reported the same success as one that delivered everything — and the frames most likely
            // to be in that tail are the terminal event and the final counts, i.e. exactly the ones the
            // caller reasons about. Recorded as a delivery failure, which the check below raises.
            deliveryFailure.compareAndSet(
                null,
                new IllegalStateException(
                    "scan progress did not drain within " + SCAN_DRAIN_TIMEOUT_MS + "ms"));
          }
        }
      }
    }

    work.cancellationReason().ifPresent(reason -> {
      throw new io.justsearch.app.api.EngineWorkCancelledException(reason);
    });

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
      Consumer<IndexingJobsFrame> onFrame, Consumer<Throwable> onError, Runnable onCompleted, EngineContext engineContext) {
    try (var work = admission.attach(engineContext)) {
      return subscribeIndexingWork(onFrame, onError, work);
    }
  }

  private IndexingJobsStream subscribeIndexingWork(Consumer<IndexingJobsFrame> onFrame,
      Consumer<Throwable> onError, io.justsearch.app.api.EngineWorkHandle work) {
    String traceId = currentTraceId();
    String requestId = currentRequestId();
    // Item A7: a bounded hand-off between the change feed's dispatch thread and the SSE fan-out.
    // Without it the fan-out would run ON the SQLite update-hook thread, so a slow HTTP client
    // would pace the indexing loop — the backpressure the Netty send buffer used to absorb.
    // FAIL_FAST, and this is the review's B4 finding rather than a tuning choice. The producer
    // that ends up inside publish() is not a thread of ours: IndexingJobsChangeStream dispatches
    // deltas from SQLite's commit hook, so it is whichever thread just mutated the jobs table, and
    // it is holding SqliteJobQueue's single write lock for the whole call. A blocking offer there
    // stops the job queue outright — no enqueue, no dequeue, no markDone — so one browser tab that
    // stopped reading its SSE stream would halt indexing for the entire machine for five seconds
    // per frame. The flow fails instead; the bridge re-subscribes and gets a fresh snapshot.
    BoundedHandoff<IndexingJobsFrame> flow =
        refuseIfSaturated(
            () ->
                new BoundedHandoff<>(
                    "indexing-jobs-flow",
                    onFrame,
                    onError,
                    body -> executeOwnedStream(work, body, true),
                    BoundedHandoff.Backpressure.FAIL_FAST));
    try {
      FlowCancelSignal cancel = new FlowCancelSignal();
      var subscriptionOwner = work.retain();
      var registration = new AtomicReference<io.justsearch.app.api.EngineWorkHandle.Registration>();
      flow.onClose(() -> {
        try {
          cancel.cancel();
        } finally {
          var callback = registration.get();
          if (callback != null) callback.close();
          subscriptionOwner.close();
        }
      });
      var cancellation = work.onCancel(reason ->
          flow.fail(new io.justsearch.app.api.EngineWorkCancelledException(reason)));
      registration.set(cancellation);
      if (cancel.isCancelled()) cancellation.close();
      CallContext ctx = new CallContext(traceId, requestId, cancel,
          work.context(), enqueueProvenance(work.context()));
      // If the pool refuses the PRODUCER after the flow's delivery thread was accepted, the flow
      // must be closed on the way out: leaving it open would leak a delivery thread that polls an
      // empty queue for the life of the process, for a subscription that never started.
      submitProducerOrClose(
          flow,
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
            } catch (RuntimeException | Error e) {
              flow.fail(e);
              if (e instanceof Error error) throw error;
            }
          }, work);
      // onCompleted has no producer in process, deliberately: the change-feed subscription lives
      // until it is cancelled, so "the producer closed the stream" is a wire-only event (a server
      // shutting down its call). The caller's close() is the only way this flow ends, and the caller
      // already knows it closed it.
      return () -> {
        flow.close();
        cancel.cancel();
      };
    } catch (RuntimeException | Error failure) {
      flow.close();
      throw failure;
    }
  }

  private static io.justsearch.indexerworker.queue.JobQueue.EnqueueProvenance enqueueProvenance(
      EngineContext context) {
    return new io.justsearch.indexerworker.queue.JobQueue.EnqueueProvenance(
        io.justsearch.app.services.intent.EngineProvenance.originator(context), context.transport());
  }

  @Override
  protected void closeTransport() {
    foregroundStreamRegistration.close();
    backgroundStreamRegistration.close();
    foregroundCallRegistration.close();
    backgroundCallRegistration.close();
    deadlineRegistration.close();
    log.debug("Engine knowledge client call scheduler stopped");
  }
}
