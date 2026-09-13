/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Real asynchronous execution for component tests that inject the neutral registry contract.
 * This double is not a timer/admission-policy oracle; those tests use the production registry.
 * Component owners must close their registrations, just as they do in production.
 */
public final class TestEngineExecutors implements EngineExecutorRegistry {
  private final List<Owner> owners = new ArrayList<>();
  private final boolean awaitClose;
  private boolean closed;

  public TestEngineExecutors() { this(false); }

  private TestEngineExecutors(boolean awaitClose) { this.awaitClose = awaitClose; }

  /** For component owners that explicitly verify termination after registered close. */
  public static TestEngineExecutors awaitingTermination() { return new TestEngineExecutors(true); }

  @Override
  public synchronized Registration register(EngineExecutorSpec spec) {
    if (closed) throw new IllegalStateException("Test registry closed");
    Owner owner = new Owner(spec, awaitClose);
    owners.add(owner);
    return owner;
  }

  @Override
  public Limits limits(EngineExecutorSpec.Kind kind) {
    return kind == EngineExecutorSpec.Kind.FOREGROUND ? new Limits(16, 48) : new Limits(4, 64);
  }

  @Override
  public int retryAfterSeconds() { return 1; }
    @Override public int maxConcurrentWork() { return 64; }

  @Override
  public EngineExecutorSnapshot snapshot() {
    throw new UnsupportedOperationException("Use the production registry for accounting assertions");
  }

  @Override
  public synchronized void close() {
    closed = true;
    owners.forEach(Owner::close);
  }

  private static final class Owner implements Registration {
    private final EngineExecutorSpec spec;
    private final boolean awaitClose;
    private final List<ExecutorService> instances = new ArrayList<>();
    private boolean closed;

    private Owner(EngineExecutorSpec spec, boolean awaitClose) {
      this.spec = spec;
      this.awaitClose = awaitClose;
    }

    @Override
    public EngineExecutorSpec spec() { return spec; }

    private synchronized <T extends ExecutorService> T own(T executor) {
      if (closed) {
        executor.shutdownNow();
        throw new IllegalStateException("Test registration closed");
      }
      instances.add(executor);
      return executor;
    }

    @Override
    public ExecutorService open(ThreadFactory factory) {
      return own(new ThreadPoolExecutor(spec.threadCount(), spec.threadCount(), 0,
          TimeUnit.MILLISECONDS, spec.queueCapacity() == 0 ? new SynchronousQueue<>()
              : new ArrayBlockingQueue<>(spec.queueCapacity()), factory));
    }

    @Override
    public ScheduledExecutorService openScheduled(ThreadFactory factory) {
      var executor = new ScheduledThreadPoolExecutor(spec.threadCount(), factory);
      executor.setRemoveOnCancelPolicy(true);
      executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
      executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
      return own(executor);
    }

    @Override
    public ExecutorService openVirtual() {
      return own(Executors.newThreadPerTaskExecutor(
          Thread.ofVirtual().name(spec.name() + "-", 0).factory()));
    }

    @Override
    public synchronized void close() {
      closed = true;
      for (ExecutorService instance : instances) {
        for (Runnable task : instance.shutdownNow()) {
          if (task instanceof java.util.concurrent.Future<?> future) future.cancel(false);
        }
        if (!awaitClose) continue;
        try {
          if (!instance.awaitTermination(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Test executor did not terminate: " + spec.name());
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("Interrupted closing test executor: " + spec.name(), interrupted);
        }
      }
    }
  }
}
