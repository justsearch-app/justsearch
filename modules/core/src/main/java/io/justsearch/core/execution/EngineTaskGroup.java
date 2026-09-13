/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Caller-confined owner for a bounded set of tasks sharing one executor instance.
 *
 * <p>The group keeps its initial lease until it is closed and retains one child lease for every
 * accepted task. A child lease is released by {@link EngineFutures} only after the task has
 * actually exited, including a task that ignored an interruption requested by close.
 */
public final class EngineTaskGroup implements AutoCloseable {
  private final ExecutorService executor;
  private final Runnable releaseGroup;
  private final AtomicInteger pending = new AtomicInteger(1);
  private final List<CompletableFuture<?>> accepted = new ArrayList<>();
  private boolean sealed;

  private EngineTaskGroup(ExecutorService executor, Runnable releaseGroup) {
    this.executor = executor;
    this.releaseGroup = releaseGroup;
  }

  /**
   * Retains the group owner before opening its executor.
   *
   * <p>If opening fails, the group owner is released before the original failure is rethrown.
   */
  public static EngineTaskGroup open(
      Supplier<? extends ExecutorService> executorSupplier, EngineTaskLifetime lifetime) {
    Objects.requireNonNull(executorSupplier, "executorSupplier");
    Objects.requireNonNull(lifetime, "lifetime");
    Runnable releaseGroup = Objects.requireNonNull(lifetime.retain(), "lifetime.retain()");
    try {
      ExecutorService executor = Objects.requireNonNull(executorSupplier.get(), "executor");
      return new EngineTaskGroup(executor, releaseGroup);
    } catch (RuntimeException | Error failure) {
      try {
        releaseGroup.run();
      } catch (RuntimeException | Error cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
  }

  /**
   * Accepts one supplier and returns its completion stage.
   *
   * <p>The supplier is validated before the pending count changes. Submission refusal remains
   * synchronous; {@link EngineFutures} releases the child lease when the refused task is
   * physically cancelled.
   */
  public <T> CompletableFuture<T> submit(Supplier<T> supplier) {
    Objects.requireNonNull(supplier, "supplier");
    if (sealed) throw new IllegalStateException("Task group is closed");

    pending.incrementAndGet();
    CompletableFuture<T> future = EngineFutures.supplyAsync(supplier, executor, this::childExited);
    accepted.add(future);
    return future;
  }

  /**
   * Cancels all accepted tasks, interrupts the executor, and seals the group without waiting.
   * Cleanup failures are aggregated and reported after every cleanup step has been attempted.
   */
  @Override
  public void close() {
    if (sealed) return;
    sealed = true;
    Throwable cleanupFailure = null;

    for (CompletableFuture<?> future : accepted) {
      try {
        future.cancel(true);
      } catch (RuntimeException | Error failure) {
        cleanupFailure = aggregate(cleanupFailure, failure);
      }
    }
    try {
      executor.shutdownNow();
    } catch (RuntimeException | Error failure) {
      cleanupFailure = aggregate(cleanupFailure, failure);
    }

    if (pending.decrementAndGet() == 0) {
      try {
        releaseGroup.run();
      } catch (RuntimeException | Error failure) {
        cleanupFailure = aggregate(cleanupFailure, failure);
      }
    }
    rethrow(cleanupFailure);
  }

  private void childExited() {
    if (pending.decrementAndGet() != 0) return;
    releaseGroup.run();
  }

  private static Throwable aggregate(Throwable aggregate, Throwable failure) {
    if (aggregate == null) return failure;
    if (aggregate != failure) aggregate.addSuppressed(failure);
    return aggregate;
  }

  private static void rethrow(Throwable failure) {
    if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
    if (failure instanceof Error errorFailure) throw errorFailure;
  }
}
