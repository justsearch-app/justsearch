/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** One producer, one outstanding observation, one independently published immutable value. */
final class StatusObservation<T> {
  record Sample<T>(T value, long observedAtMs, long periodMs, boolean failed, long statusCounter) {}
  private final AtomicReference<CompletableFuture<Void>> running = new AtomicReference<>();
  private volatile Sample<T> sample;
  private volatile long startedAtMs;
  private volatile long startedSequence;
  private volatile long publicationSequence;
  private volatile long expiredSequence;

  private final java.util.concurrent.atomic.AtomicBoolean refreshAgain =
      new java.util.concurrent.atomic.AtomicBoolean();
  private volatile CompletableFuture<Void> expired;
  private record Request<T>(Supplier<T> producer, long periodMs) {}
  private volatile Request<T> latestRequest;

  CompletableFuture<Void> refresh(String name, Supplier<T> producer, LongSupplier clock,
      long period, Runnable published, boolean latest) {
    if (latest) latestRequest = new Request<>(producer, period);
    return start(name, producer, clock, period, published, latest);
  }

  private CompletableFuture<Void> start(String name, Supplier<T> producer, LongSupplier clock,
      long period, Runnable published, boolean latest) {
    var next = new CompletableFuture<Void>();
    if (!running.compareAndSet(null, next)) {
      if (latest) refreshAgain.set(true);
      var existing = running.get();
      return existing == null ? start(name, producer, clock, period, published, latest) : existing;
    }
    startedSequence = publicationSequence;
    startedAtMs = clock.getAsLong();
    Thread.ofPlatform().daemon().name("status-" + name).start(() -> {
      try {
        do {
          refreshAgain.set(false);
          startedSequence = publicationSequence;
          long observed = clock.getAsLong();
          startedAtMs = observed;
          var request = latest ? latestRequest : new Request<>(producer, period);
          long statusCounter = io.justsearch.core.status.StatusChangeCounter.process().current();
          try {
            sample = new Sample<>(request.producer().get(), observed, request.periodMs(), false, statusCounter);
          } catch (RuntimeException | LinkageError failure) {
            var previous = sample;
            sample = new Sample<>(previous == null ? null : previous.value(),
                previous == null ? observed : previous.observedAtMs(), request.periodMs(), true,
                previous == null ? 0L : previous.statusCounter());
          }
          publicationSequence++;
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

  void expireOutstanding(long now) {
    if (publicationSequence == startedSequence && now - startedAtMs >= 100L) {
      expiredSequence = startedSequence;
      expired = running.get();
    }
  }

  long statusCounter() {
    var current = sample;
    return current == null ? 0L : current.statusCounter();
  }

  T value(T fallback) {
    var current = sample;
    return current == null ? fallback : current.value();
  }

  boolean stale(long now) {
    var current = sample;
    var inFlight = running.get();
    boolean unpublished = publicationSequence == startedSequence;
    return current == null || current.failed()
        || now - current.observedAtMs() > StatusLifecycleHandler.SAMPLE_STALE_PERIODS * current.periodMs()
        || (inFlight != null && unpublished && ((inFlight == expired && expiredSequence == startedSequence)
            || now - startedAtMs > 1_000L));
  }

  long staleAfterMs() {
    var current = sample;
    return current == null ? 30_000L : current.periodMs() * StatusLifecycleHandler.SAMPLE_STALE_PERIODS;
  }

  long age(long now) {
    var current = sample;
    return current == null ? 0L : Math.max(0L, now - current.observedAtMs());
  }
}
