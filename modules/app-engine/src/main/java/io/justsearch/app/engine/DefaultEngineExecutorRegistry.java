/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import io.justsearch.core.execution.EngineExecutorRejectedException.Reason;
import io.justsearch.core.execution.EngineExecutorSnapshot;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.core.execution.EngineExecutorSpec.Kind;
import io.justsearch.core.execution.EngineExecutorSpec.Mode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RunnableScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Default process-owned implementation of the Engine executor registry. */
public final class DefaultEngineExecutorRegistry implements EngineExecutorRegistry {
  private static final Duration DEFAULT_CLOSE_TIMEOUT = Duration.ofSeconds(2);
  // Synchronous submission bookkeeping only; never caller attribution or work context.
  // Each scheduling call restores the prior value, including reentrant ThreadFactory callbacks.
  private static final ThreadLocal<PermitTask<?>> SUBMITTING = new ThreadLocal<>();
  // Executor identity only: a shutdown callback must never interrupt or await its own worker.
  private static final ThreadLocal<ExecutorService> RUNNING_EXECUTOR = new ThreadLocal<>();

  private final Object lock = new Object();
  private final Map<String, RegisteredExecutor> registrations = new LinkedHashMap<>();
  private final EngineResourcePolicy policy;
  private final Duration closeTimeout;
  private final AtomicInteger timerRegistrations = new AtomicInteger();
  private volatile boolean closed;

  public DefaultEngineExecutorRegistry() {
    this(EngineResourcePolicy.load(), DEFAULT_CLOSE_TIMEOUT);
  }

  DefaultEngineExecutorRegistry(EngineResourcePolicy policy) {
    this(policy, DEFAULT_CLOSE_TIMEOUT);
  }

  DefaultEngineExecutorRegistry(EngineResourcePolicy policy, Duration closeTimeout) {
    this.policy = Objects.requireNonNull(policy, "policy");
    this.closeTimeout = Objects.requireNonNull(closeTimeout, "closeTimeout");
    if (closeTimeout.isNegative()) throw new IllegalArgumentException("closeTimeout must not be negative");
  }

  @Override
  public Registration register(EngineExecutorSpec spec) {
    Objects.requireNonNull(spec, "spec");
    validateAgainstPolicy(spec);
    synchronized (lock) {
      if (closed) throw rejected(Reason.CLOSED, spec.name());
      pruneRegistrationsLocked();
      RegisteredExecutor existing = registrations.get(spec.name());
      if (existing != null && existing.registrationClosed) {
        throw rejected(Reason.INSTANCE_LIMIT, spec.name());
      }
      if (existing != null) {
        throw new IllegalArgumentException("Executor name is already registered: " + spec.name());
      }
      RegisteredExecutor registration = new RegisteredExecutor(spec);
      registrations.put(spec.name(), registration);
      return registration;
    }
  }

  @Override
  public Limits limits(Kind kind) {
    Objects.requireNonNull(kind, "kind");
    String prefix = kind == Kind.FOREGROUND ? "foreground" : "background";
    return new Limits(
        policy.execution().get(prefix + "Threads"), policy.execution().get(prefix + "Queue"));
  }

  @Override
  public int maxConcurrentWork() {
    return policy.execution().get("aggregateLimit");
  }

  @Override
  public int retryAfterSeconds() { return policy.execution().get("retryAfterSeconds"); }

  private void validateAgainstPolicy(EngineExecutorSpec spec) {
    if (spec.mode() == Mode.VIRTUAL) {
      if (spec.maxInstances() > maxConcurrentWork()) {
        throw new IllegalArgumentException("Virtual executor instances exceed aggregate admission");
      }
      return;
    }
    String prefix = spec.kind() == Kind.FOREGROUND ? "foreground" : "background";
    int threadLimit = policy.execution().get(prefix + "Threads");
    int queueLimit = policy.execution().get(prefix + "Queue");
    if (spec.threadCount() > threadLimit || spec.queueCapacity() > queueLimit) {
      throw new IllegalArgumentException(
          "Executor " + spec.name() + " exceeds " + prefix + " execution policy");
    }
  }

  @Override
  public EngineExecutorSnapshot snapshot() {
    synchronized (lock) {
      pruneRegistrationsLocked();
      List<EngineExecutorSnapshot.Registration> rows = registrations.values().stream()
          .map(RegisteredExecutor::snapshotLocked)
          .sorted(Comparator.comparing(row -> row.spec().name()))
          .toList();
      return new EngineExecutorSnapshot(rows, timerRegistrations.get());
    }
  }

  @Override
  public void close() {
    List<ExecutorService> instances;
    synchronized (lock) {
      if (closed) return;
      closed = true;
      instances = registrations.values().stream()
          .flatMap(registration -> registration.markClosedLocked().stream()).toList();
    }
    instances.forEach(DefaultEngineExecutorRegistry::shutdownAndCancelQueued);
    awaitWithinCloseDeadline(instances);
    synchronized (lock) {
      pruneRegistrationsLocked();
    }
  }

  private void awaitWithinCloseDeadline(List<ExecutorService> instances) {
    long deadline = System.nanoTime() + closeTimeout.toNanos();
    for (ExecutorService instance : instances) {
      if (instance == RUNNING_EXECUTOR.get()) continue;
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) break;
      try {
        instance.awaitTermination(remaining, TimeUnit.NANOSECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
  }

  private static void shutdownAndCancelQueued(ExecutorService instance) {
    if (instance == RUNNING_EXECUTOR.get()) {
      instance.shutdown();
      // A platform worker may have queued callers. Retire them without interrupting this worker.
      if (instance instanceof ThreadPoolExecutor pool) {
        var queued = new ArrayList<Runnable>();
        pool.getQueue().drainTo(queued);
        for (Runnable task : queued) {
          if (task instanceof java.util.concurrent.Future<?> future) future.cancel(false);
        }
      }
      return;
    }
    for (Runnable queued : instance.shutdownNow()) {
      if (queued instanceof java.util.concurrent.Future<?> future) future.cancel(false);
    }
  }

  private static ThreadFactory trackingFactory(ThreadFactory delegate,
      java.util.concurrent.atomic.AtomicReference<ExecutorService> executor) {
    return task -> delegate.newThread(() -> {
      ExecutorService previous = RUNNING_EXECUTOR.get();
      RUNNING_EXECUTOR.set(executor.get());
      try {
        task.run();
      } finally {
        if (previous == null) RUNNING_EXECUTOR.remove(); else RUNNING_EXECUTOR.set(previous);
      }
    });
  }

  private void pruneRegistrationsLocked() {
    registrations.values().forEach(RegisteredExecutor::pruneLocked);
    registrations.values().removeIf(
        registration -> registration.registrationClosed && registration.instances.isEmpty());
  }

  private void pruneInstancesAfterClose() {
    synchronized (lock) {
      pruneRegistrationsLocked();
    }
  }

  private EngineExecutorRejectedException rejected(Reason reason, String name) {
    return new EngineExecutorRejectedException(reason, name, policy.execution().get("retryAfterSeconds"));
  }

  private final class RegisteredExecutor implements Registration {
    private final EngineExecutorSpec spec;
    private final Set<ExecutorService> instances = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private volatile boolean registrationClosed;

    private RegisteredExecutor(EngineExecutorSpec spec) {
      this.spec = spec;
    }

    @Override
    public EngineExecutorSpec spec() {
      return spec;
    }

    @Override
    public ExecutorService open(ThreadFactory threadFactory) {
      Objects.requireNonNull(threadFactory, "threadFactory");
      synchronized (lock) {
        requireMode(Mode.PLATFORM);
        prepareOpenLocked();
        BlockingQueue<Runnable> queue = spec.queueCapacity() == 0
            ? new SynchronousQueue<>() : new ArrayBlockingQueue<>(spec.queueCapacity());
        var identity = new java.util.concurrent.atomic.AtomicReference<ExecutorService>();
        ExecutorService executor = new ThreadPoolExecutor(
            spec.threadCount(), spec.threadCount(), 0L, TimeUnit.MILLISECONDS, queue,
            trackingFactory(threadFactory, identity),
            (task, owner) -> {
              throw rejected(owner.isShutdown() ? Reason.CLOSED : Reason.QUEUE_LIMIT, spec.name());
            });
        identity.set(executor);
        instances.add(executor);
        return executor;
      }
    }

    @Override
    public ScheduledExecutorService openScheduled(ThreadFactory threadFactory) {
      Objects.requireNonNull(threadFactory, "threadFactory");
      synchronized (lock) {
        requireMode(Mode.SCHEDULED);
        prepareOpenLocked();
        var identity = new java.util.concurrent.atomic.AtomicReference<ExecutorService>();
        TrackedScheduledExecutor executor = new TrackedScheduledExecutor(this, trackingFactory(threadFactory, identity));
        identity.set(executor);
        instances.add(executor);
        return executor;
      }
    }

    @Override
    public ExecutorService openVirtual() {
      synchronized (lock) {
        requireMode(Mode.VIRTUAL);
        prepareOpenLocked();
        var identity = new java.util.concurrent.atomic.AtomicReference<ExecutorService>();
        ExecutorService executor = Executors.newThreadPerTaskExecutor(trackingFactory(
            Thread.ofVirtual().name(spec.name() + "-", 0).factory(), identity));
        identity.set(executor);
        instances.add(executor);
        return executor;
      }
    }

    private void requireMode(Mode expected) {
      if (spec.mode() != expected) {
        throw new IllegalStateException(spec.name() + " is " + spec.mode() + ", not " + expected);
      }
    }

    private void prepareOpenLocked() {
      pruneLocked();
      if (closed || registrationClosed) throw rejected(Reason.CLOSED, spec.name());
      if (instances.size() >= spec.maxInstances()) throw rejected(Reason.INSTANCE_LIMIT, spec.name());
    }

    private void pruneLocked() {
      instances.removeIf(ExecutorService::isTerminated);
    }

    private EngineExecutorSnapshot.Registration snapshotLocked() {
      pruneLocked();
      int shutdown = 0;
      int queued = 0;
      for (ExecutorService instance : instances) {
        if (instance.isShutdown()) shutdown++;
        if (instance instanceof ThreadPoolExecutor pool) queued += pool.getQueue().size();
      }
      return new EngineExecutorSnapshot.Registration(
          spec, registrationClosed, instances.size(), shutdown, queued);
    }

    @Override
    public void close() {
      List<ExecutorService> copy;
      synchronized (lock) {
        copy = markClosedLocked();
      }
      copy.forEach(DefaultEngineExecutorRegistry::shutdownAndCancelQueued);
      awaitWithinCloseDeadline(copy);
      pruneInstancesAfterClose();
    }

    private List<ExecutorService> markClosedLocked() {
      registrationClosed = true;
      return new ArrayList<>(instances);
    }
  }

  private final class TrackedScheduledExecutor extends ScheduledThreadPoolExecutor {
    private final RegisteredExecutor registration;
    private final Set<PermitTask<?>> permitTasks = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicInteger localTimerRegistrations = new AtomicInteger();

    private TrackedScheduledExecutor(RegisteredExecutor registration, ThreadFactory factory) {
      super(registration.spec.threadCount(), factory, (task, owner) -> {
        if (task instanceof PermitTask<?> permitTask) permitTask.release();
        throw DefaultEngineExecutorRegistry.this.rejected(
            owner.isShutdown() ? Reason.CLOSED : Reason.QUEUE_LIMIT,
            registration.spec.name());
      });
      this.registration = registration;
      setRemoveOnCancelPolicy(true);
      setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
      setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    @Override
    protected <V> RunnableScheduledFuture<V> decorateTask(
        Runnable runnable, RunnableScheduledFuture<V> task) {
      return reserve(task);
    }

    @Override
    protected <V> RunnableScheduledFuture<V> decorateTask(
        java.util.concurrent.Callable<V> callable, RunnableScheduledFuture<V> task) {
      return reserve(task);
    }

    private <V> RunnableScheduledFuture<V> reserve(RunnableScheduledFuture<V> task) {
      if (isShutdown() || closed || registration.registrationClosed) {
        throw rejected(Reason.CLOSED, registration.spec.name());
      }
      if (!tryAcquire(localTimerRegistrations, registration.spec.queueCapacity())) {
        throw rejected(Reason.QUEUE_LIMIT, registration.spec.name());
      }
      if (!tryAcquire(timerRegistrations, policy.execution().get("timerRegistrations"))) {
        localTimerRegistrations.decrementAndGet();
        throw rejected(Reason.TIMER_LIMIT, registration.spec.name());
      }
      PermitTask<V> wrapped = new PermitTask<>(this, task);
      permitTasks.add(wrapped);
      SUBMITTING.set(wrapped);
      if (isShutdown() || closed || registration.registrationClosed) {
        wrapped.cancel(false);
        throw rejected(Reason.CLOSED, registration.spec.name());
      }
      return wrapped;
    }

    private boolean tryAcquire(AtomicInteger counter, int limit) {
      int current;
      do {
        current = counter.get();
        if (current >= limit) return false;
      } while (!counter.compareAndSet(current, current + 1));
      return true;
    }

    private void release(PermitTask<?> task) {
      if (!permitTasks.remove(task)) return;
      localTimerRegistrations.decrementAndGet();
      timerRegistrations.decrementAndGet();
    }

    private void failedSubmission() {
      PermitTask<?> task = SUBMITTING.get();
      if (task != null) {
        remove(task);
        task.cancel(false);
      }
    }

    private <V> java.util.concurrent.ScheduledFuture<V> submitScheduled(
        java.util.function.Supplier<java.util.concurrent.ScheduledFuture<V>> action) {
      PermitTask<?> previous = SUBMITTING.get();
      SUBMITTING.remove();
      try {
        return action.get();
      } catch (RuntimeException | Error failure) {
        failedSubmission();
        throw failure;
      } finally {
        if (previous == null) SUBMITTING.remove();
        else SUBMITTING.set(previous);
      }
    }

    @Override
    public java.util.concurrent.ScheduledFuture<?> schedule(
        Runnable command, long delay, TimeUnit unit) {
      return submitScheduled(() -> super.schedule(command, delay, unit));
    }

    @Override
    public <V> java.util.concurrent.ScheduledFuture<V> schedule(
        java.util.concurrent.Callable<V> callable, long delay, TimeUnit unit) {
      return submitScheduled(() -> super.schedule(callable, delay, unit));
    }

    @Override
    public java.util.concurrent.ScheduledFuture<?> scheduleAtFixedRate(
        Runnable command, long initialDelay, long period, TimeUnit unit) {
      return submitScheduled(() -> super.scheduleAtFixedRate(command, initialDelay, period, unit));
    }

    @Override
    public java.util.concurrent.ScheduledFuture<?> scheduleWithFixedDelay(
        Runnable command, long initialDelay, long delay, TimeUnit unit) {
      return submitScheduled(() -> super.scheduleWithFixedDelay(command, initialDelay, delay, unit));
    }

    @Override
    public void shutdown() {
      super.shutdown();
      permitTasks.stream().filter(PermitTask::isPeriodic).forEach(task -> task.cancel(false));
    }

    @Override
    public List<Runnable> shutdownNow() {
      List<Runnable> queued = super.shutdownNow();
      permitTasks.forEach(task -> task.cancel(true));
      return queued;
    }

    @Override
    protected void terminated() {
      permitTasks.forEach(PermitTask::release);
      super.terminated();
    }
  }

  private static final class PermitTask<V> implements RunnableScheduledFuture<V> {
    private final TrackedScheduledExecutor owner;
    private final RunnableScheduledFuture<V> delegate;
    private boolean released;

    private PermitTask(TrackedScheduledExecutor owner, RunnableScheduledFuture<V> delegate) {
      this.owner = owner;
      this.delegate = delegate;
    }

    private void release() {
      synchronized (this) {
        if (released) return;
        released = true;
      }
      owner.release(this);
    }

    @Override
    public void run() {
      if (!isPeriodic()) release();
      try {
        delegate.run();
      } finally {
        if (isPeriodic() && delegate.isDone()) release();
      }
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      boolean cancelled = delegate.cancel(mayInterruptIfRunning);
      owner.remove(this);
      release();
      return cancelled;
    }

    @Override public boolean isPeriodic() { return delegate.isPeriodic(); }
    @Override public long getDelay(TimeUnit unit) { return delegate.getDelay(unit); }
    @Override public int compareTo(java.util.concurrent.Delayed other) {
      if (other == this) return 0;
      return delegate.compareTo(other instanceof PermitTask<?> task ? task.delegate : other);
    }
    @Override public boolean isCancelled() { return delegate.isCancelled(); }
    @Override public boolean isDone() { return delegate.isDone(); }
    @Override public V get() throws java.util.concurrent.ExecutionException, InterruptedException {
      return delegate.get();
    }
    @Override public V get(long timeout, TimeUnit unit)
        throws java.util.concurrent.ExecutionException, InterruptedException,
        java.util.concurrent.TimeoutException {
      return delegate.get(timeout, unit);
    }
  }
}
