/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import java.util.concurrent.ExecutorService;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asynchronous decorator for {@link InferenceTransitionLog}.
 *
 * <p>Tempdoc 518 Wave A-E defect Fix-3. The previous wiring called
 * {@code transitionLog.record(...)} from inside {@link TransitionRunner}'s
 * {@code synchronized(lock)} block, so a slow disk write blocked the transition for as long
 * as the I/O took — making user-visible "switch to online" latency directly dependent on
 * sidecar I/O hiccups. This decorator wraps any {@link InferenceTransitionLog} (the
 * production {@link NdjsonInferenceTransitionLog} in particular) and queues every
 * {@code record} call to a single-threaded daemon executor. The runner returns from
 * {@code record} immediately; I/O runs out-of-band.
 *
 * <p><b>Ordering</b>: the executor is single-threaded, so submitted writes drain in
 * submission order. Important because the NDJSON file is an append log — out-of-order writes
 * would jumble timestamps.
 *
 * <p><b>Backpressure</b>: the process registry bounds the queue. Capacity refusal is logged;
 * this best-effort diagnostic writer must never stall the transition lock or run disk I/O on it.
 *
 * <p><b>Shutdown</b>: callers must invoke {@link #close()} during graceful shutdown so
 * pending writes drain. Daemon-thread default means a JVM exit without close still terminates,
 * losing pending writes; the head's lifecycle calls close in its shutdown hook.
 */
public final class AsyncInferenceTransitionLog implements InferenceTransitionLog, AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(AsyncInferenceTransitionLog.class);

  private final InferenceTransitionLog delegate;
  private final ExecutorService executor;
  private final EngineExecutorRegistry.Registration executorOwner;

  public AsyncInferenceTransitionLog(EngineExecutorRegistry executors, InferenceTransitionLog delegate) {
    this.delegate = delegate;
    var limits = executors.limits(EngineExecutorSpec.Kind.BACKGROUND);
    this.executorOwner = executors.register(new EngineExecutorSpec(
        "head.inference-transition-log", EngineExecutorSpec.Kind.BACKGROUND,
        EngineExecutorSpec.Mode.PLATFORM, 1, limits.maxQueue(), 1));
    try {
    this.executor =
        executorOwner.open(
            r -> {
              Thread t = new Thread(r, "inference-transition-log");
              t.setDaemon(true);
              return t;
            });
    } catch (RuntimeException | Error failure) {
      try { executorOwner.close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
      throw failure;
    }
  }

  @Override
  public void record(
      long timestampMs,
      String from,
      String to,
      String reason,
      boolean success,
      long durationMs,
      String wireCode,
      long generation) {
    try {
      executor.execute(
          () -> {
            try {
              delegate.record(timestampMs, from, to, reason, success, durationMs, wireCode, generation);
            } catch (RuntimeException e) {
              LOG.warn("Async InferenceTransitionLog write failed (best-effort): {}", e.getMessage());
            }
          });
    } catch (RejectedExecutionException e) {
      if (!executor.isShutdown()) LOG.warn("Inference transition log queue is full; record rejected", e);
    }
  }

  @Override
  public void close() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
        LOG.warn("AsyncInferenceTransitionLog drain timed out; pending writes may be lost");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      executorOwner.close();
    }
  }
}
