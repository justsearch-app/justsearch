/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.execution;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.function.Supplier;

/** Async results whose queued task cancellation also terminates the exposed completion stage. */
public final class EngineFutures {
  private EngineFutures() {}

  /** Capacity refusal is never a successful optional-computation fallback. */
  public static void rethrowExecutorRefusal(Throwable failure) {
    if (completionCause(failure) instanceof EngineExecutorRejectedException refusal) throw refusal;
  }

  /** Optional search work may fall back on ordinary failures, never on caller abandonment. */
  public static void rethrowCancellation(Throwable failure) {
    Throwable cause = completionCause(failure);
    if (cause instanceof CancellationException cancelled) throw cancelled;
    if (cause instanceof InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new java.util.concurrent.CompletionException(interrupted);
    }
  }

  private static Throwable completionCause(Throwable failure) {
    var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
    while (failure != null && seen.add(failure)) {
      if (!(failure instanceof java.util.concurrent.CompletionException)
          && !(failure instanceof ExecutionException)) return failure;
      failure = failure.getCause();
    }
    return failure;
  }

  /**
   * Keeps submission refusal synchronous. Unlike CompletableFuture's internal AsyncSupply,
   * the queued FutureTask owns completion of the returned stage even when it never runs.
   */
  public static <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier, Executor executor) {
    return supplyAsync(supplier, executor, () -> {});
  }

  /**
   * Releases ownership only after actual task exit, or cancellation before the task starts.
   * Cleanup is attempted once. A cleanup failure after a running task's result was published is
   * reported through the system logger; it cannot change that result or kill its executor worker.
   * Pre-start cancellation/submission callers instead receive the cleanup failure directly or as
   * a suppressed failure on their original refusal/timeout.
   */
  public static <T> CompletableFuture<T> supplyAsync(
      Supplier<T> supplier, Executor executor, Runnable onActualExit) {
    Objects.requireNonNull(executor, "executor");
    var result = new OwnedFuture<>(Objects.requireNonNull(supplier, "supplier"),
        Objects.requireNonNull(onActualExit, "onActualExit"));
    try {
      executor.execute(result.task);
    } catch (RuntimeException | Error failure) {
      try { result.task.cancel(false); }
      catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
      throw failure;
    }
    return result;
  }

  /**
   * Waits interruptibly and cancels the accepted task when its waiter leaves. Cancellation requests
   * task interruption; the supplyAsync actual-exit callback still owns lifetime release.
   */
  public static <T> T await(CompletableFuture<T> future) {
    Objects.requireNonNull(future, "future");
    try {
      return future.get();
    } catch (InterruptedException interrupted) {
      try { future.cancel(true); }
      catch (RuntimeException | Error cleanupFailure) { interrupted.addSuppressed(cleanupFailure); }
      Thread.currentThread().interrupt();
      throw new java.util.concurrent.CompletionException(interrupted);
    } catch (ExecutionException failed) {
      throw new java.util.concurrent.CompletionException(failed.getCause());
    }
  }

  private static final class OwnedFuture<T> extends CompletableFuture<T> {
    private final FutureTask<T> task;
    private volatile boolean timeoutCompleting;

    private OwnedFuture(Supplier<T> supplier, Runnable onActualExit) {
      task = new FutureTask<>(supplier::get) {
        // 0 queued, 1 inside run(), 2 actually exited (or cancelled before entry).
        private final java.util.concurrent.atomic.AtomicInteger lifetime =
            new java.util.concurrent.atomic.AtomicInteger();

        @Override public void run() {
          if (!lifetime.compareAndSet(0, 1)) return;
          try { super.run(); }
          finally {
            lifetime.set(2);
            try { onActualExit.run(); }
            catch (RuntimeException | Error cleanupFailure) {
              System.getLogger(EngineFutures.class.getName()).log(System.Logger.Level.ERROR,
                  "Engine task ownership cleanup failed after execution", cleanupFailure);
            }
          }
        }

        @Override protected void done() {
          try {
          // The timeout caller has already claimed publication and first cancels this task.
          // It publishes TimeoutException itself, after cancellation is physically effective.
          if (timeoutCompleting) return;
          try {
            OwnedFuture.this.complete(get());
          } catch (CancellationException cancelled) {
            OwnedFuture.this.completeExceptionally(cancelled);
          } catch (ExecutionException failed) {
            OwnedFuture.this.completeExceptionally(failed.getCause());
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            OwnedFuture.this.completeExceptionally(interrupted);
          }
          } finally {
            if (lifetime.compareAndSet(0, 2)) onActualExit.run();
          }
        }
      };
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      // FutureTask changes state before done() invokes synchronous stage dependents. They may
      // block indefinitely, so the supplier must already be unable to start at that point.
      try {
        task.cancel(mayInterruptIfRunning);
      } catch (RuntimeException | Error cleanupFailure) {
        super.cancel(mayInterruptIfRunning);
        throw cleanupFailure;
      }
      return super.cancel(mayInterruptIfRunning);
    }

    @Override
    public boolean completeExceptionally(Throwable failure) {
      if (failure instanceof java.util.concurrent.TimeoutException && !isDone()) {
        timeoutCompleting = true;
        try { task.cancel(true); }
        catch (RuntimeException | Error cleanupFailure) { failure.addSuppressed(cleanupFailure); }
      }
      return super.completeExceptionally(failure);
    }
  }
}
