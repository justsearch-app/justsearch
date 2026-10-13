/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** One producer, one outstanding observation, one independently published immutable value. */
final class StatusObservation<T> {
  private final Runnable surfacePublished;

  StatusObservation() { this(() -> {}); }

  StatusObservation(Runnable surfacePublished) {
    this.surfacePublished = surfacePublished;
  }
  /**
   * One published observation. {@code cadenceMs} is the lane's effective cadence when it was
   * published (see {@link #cadenceMs}). {@code staleSource} marks a value whose data was already
   * older than the allowance when it was published: a hung probe or a stale source.
   */
  record Sample<T>(T value, long observedAtMs, long periodMs, boolean failed, long statusCounter,
      long cadenceMs, boolean staleSource) {}

  /**
   * A produced value with the time its source was really probed. A producer that answers from its
   * own cache, or publishes a reading that carries its own timestamp, reports that time, so a cached
   * or older reading is never stamped as a new observation.
   */
  record Probe<T>(T value, long probedAtMs) {}

  private final AtomicReference<CompletableFuture<Void>> running = new AtomicReference<>();
  private volatile Sample<T> sample;

  /**
   * The data ages at publish of this lane's last few values, newest overwriting oldest: from the
   * value's probe time ({@link Probe#probedAtMs}, the source reading's own timestamp where it has
   * one) to its publication. For a producer stamped just before its call this is its probe
   * duration.
   */
  private final long[] recentLagMs = new long[StatusLifecycleHandler.RECENT_PROBES];
  private int recentLags;

  private final java.util.concurrent.atomic.AtomicBoolean refreshAgain =
      new java.util.concurrent.atomic.AtomicBoolean();
  private record Request<T>(Supplier<Probe<T>> producer, long periodMs) {}
  private volatile Request<T> latestRequest;

  /** Refreshes from a producer that probes its source on every call: stamped just before the call. */
  CompletableFuture<Void> refresh(String name, Supplier<T> producer, LongSupplier clock,
      long period, Runnable published, boolean latest) {
    return refreshProbe(name, () -> {
      long probedAt = clock.getAsLong();
      return new Probe<>(producer.get(), probedAt);
    }, clock, period, published, latest);
  }

  /** Refreshes from a producer that reports when its value was really probed. */
  CompletableFuture<Void> refreshProbe(String name, Supplier<Probe<T>> producer, LongSupplier clock,
      long period, Runnable published, boolean latest) {
    if (latest) latestRequest = new Request<>(producer, period);
    return start(name, producer, clock, period, published, latest);
  }

  private CompletableFuture<Void> start(String name, Supplier<Probe<T>> producer, LongSupplier clock,
      long period, Runnable published, boolean latest) {
    var next = new CompletableFuture<Void>();
    if (!running.compareAndSet(null, next)) {
      if (latest) refreshAgain.set(true);
      var existing = running.get();
      return existing == null ? start(name, producer, clock, period, published, latest) : existing;
    }
    Thread.ofPlatform().daemon().name("status-" + name).start(() -> {
      try {
        do {
          refreshAgain.set(false);
          long started = clock.getAsLong();
          var request = latest ? latestRequest : new Request<>(producer, period);
          long statusCounter = io.justsearch.core.status.StatusChangeCounter.process().current();
          try {
            var probe = request.producer().get();
            long lag = clock.getAsLong() - probe.probedAtMs();
            long cadence = recordLag(request.periodMs(), lag);
            sample = new Sample<>(probe.value(), probe.probedAtMs(), request.periodMs(), false,
                statusCounter, cadence, beyondAllowance(request.periodMs(), lag));
          } catch (RuntimeException | LinkageError failure) {
            long cadence = recordLag(request.periodMs(), clock.getAsLong() - started);
            var previous = sample;
            sample = new Sample<>(previous == null ? null : previous.value(),
                previous == null ? started : previous.observedAtMs(), request.periodMs(), true,
                previous == null ? 0L : previous.statusCounter(), cadence, false);
          }
          surfacePublished.run();
          if (published != null) published.run();
        } while (latest && refreshAgain.get());
      } finally {
        running.compareAndSet(next, null);
        // A publication arriving between the loop test and release still gets a final pass.
        if (latest && refreshAgain.getAndSet(false)) {
          start(name, producer, clock, period, published, true)
              .whenComplete((ignored, failure) -> next.complete(null));
        } else {
          next.complete(null);
        }
      }
    });
    return next;
  }

  CompletableFuture<Void> completion() {
    var current = running.get();
    return current == null ? CompletableFuture.completedFuture(null) : current;
  }

  long statusCounter() {
    var current = sample;
    return current == null ? 0L : current.statusCounter();
  }

  T value(T fallback) {
    var current = sample;
    return current == null ? fallback : current.value();
  }

  /**
   * A lane's effective cadence: its configured period plus the largest of its recent data ages at
   * publish ({@link #recentLagMs}). A lane whose value is about one period old when it is published,
   * because its probe is slow or its source samples on its own cadence and is cached on the way,
   * still produces on its cadence, only later, so its age is measured against that cadence. Only a
   * data age within {@link StatusLifecycleHandler#PROBE_ALLOWANCE_PERIODS} periods counts; a longer
   * one is a hung probe or a stale source and adds nothing. So the allowance never exceeds that many
   * periods, and a lane whose probe hangs still goes stale within
   * {@code SAMPLE_STALE_PERIODS x (1 + PROBE_ALLOWANCE_PERIODS)} periods. The caller holds
   * {@code recentLagMs}.
   */
  private long cadenceMs(long periodMs) {
    long allowance = 0L;
    int n = Math.min(recentLags, recentLagMs.length);
    for (int i = 0; i < n; i++) {
      long lag = recentLagMs[i];
      if (!beyondAllowance(periodMs, lag)) allowance = Math.max(allowance, lag);
    }
    return periodMs + allowance;
  }

  /** A data age at publish beyond the allowance: a hung probe or a stale source. */
  private static boolean beyondAllowance(long periodMs, long lagMs) {
    return lagMs > StatusLifecycleHandler.PROBE_ALLOWANCE_PERIODS * periodMs;
  }

  /** Records one value's data age at publish and returns the effective cadence. */
  private long recordLag(long periodMs, long lagMs) {
    synchronized (recentLagMs) {
      recentLagMs[recentLags++ % recentLagMs.length] = Math.max(0L, lagMs);
      return cadenceMs(periodMs);
    }
  }

  /**
   * Stale only from this lane's own evidence: no observation yet, a failed observation, a value
   * whose data was older than the allowance when it was published, or a last real observation older
   * than {@link StatusLifecycleHandler#SAMPLE_STALE_PERIODS} of its own effective cadence
   * ({@link #cadenceMs}). A slow or held refresh in flight does not age the last real observation,
   * and a reader's deadline is not evidence about the lane.
   */
  boolean stale(long now) {
    var current = sample;
    return current == null || current.failed() || current.staleSource()
        || now - current.observedAtMs() > StatusLifecycleHandler.SAMPLE_STALE_PERIODS * current.cadenceMs();
  }

  long staleAfterMs() {
    var current = sample;
    return current == null ? 30_000L : current.cadenceMs() * StatusLifecycleHandler.SAMPLE_STALE_PERIODS;
  }

  /** The staleness limit for a value observed under {@code periodMs}, given the recent data ages. */
  long staleAfterMs(long periodMs) {
    synchronized (recentLagMs) {
      return cadenceMs(periodMs) * StatusLifecycleHandler.SAMPLE_STALE_PERIODS;
    }
  }

  long age(long now) {
    var current = sample;
    return current == null ? 0L : Math.max(0L, now - current.observedAtMs());
  }
}
