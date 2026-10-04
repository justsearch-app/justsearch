/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap;

import java.util.OptionalLong;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Tempdoc 541 §5.2 — thread-safe single-shot memoized supplier.
 *
 * <p>Holds a body {@link Supplier} that runs at most once. The first {@link #get()} call
 * invokes the body, stores the result, and returns it. All subsequent calls return the cached
 * result without re-invoking the body. Concurrent first-call attempts: exactly one body
 * invocation succeeds; the others wait for and read the cached result.
 *
 * <p>Distinguished from {@link AtomicReference} by the body-once contract: a value either has
 * been computed or hasn't; there is no overwrite path.
 *
 * <p>Distinguished from {@link java.lang.ref.SoftReference} / {@link
 * java.lang.ref.WeakReference} by being non-collectible: once computed, the result is held
 * for the lifetime of the {@code Memoized} instance.
 *
 * <p>Distinguished from {@code com.google.common.base.Suppliers.memoize} by being part of the
 * 541 composition-substrate API (named, documented, owned by the substrate). This codebase
 * does not introduce Guava as a dependency for this single primitive.
 *
 * <p>BootTrace integration: when a {@code Memoized<T>} is the Output of a {@code
 * Eagerness.LAZY} phase, the phase records a {@code PhaseRecord.lazyPending} at composition-
 * root sealing. The body's runtime execution can also emit its own {@code
 * composition.phase.<name>.resolve} OTel span (caller's responsibility).
 *
 * <p>Thread safety: instance-level locking via a single {@link Object} monitor. The body is
 * invoked under the lock — callers should not pass bodies that themselves acquire global
 * locks or otherwise block, lest the substrate deadlock. Substrate-internal callers (the head
 * composition root) provide bodies that delegate to existing phase helpers (single-threaded
 * source-encoded order); deadlock risk is bounded by that discipline.
 *
 * @param <T> the body's result type.
 */
public final class Memoized<T> implements Supplier<T> {

  private final Supplier<T> body;
  private final AtomicReference<T> cached = new AtomicReference<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private volatile boolean resolved = false;
  private final Object lock = new Object();
  // Tempdoc 541 §12.G — resolution timestamps captured at first .get() so the substrate
  // can surface LAZY → READY/resolved timing in the boot-trace synthesis path. -1L sentinel
  // for "never resolved." Written under {@link #lock}; volatile-read for fast-path reads.
  private volatile long startedAtMs = -1L;
  private volatile long resolvedAtMs = -1L;

  /** Wraps a body supplier in a memoized cache. The body runs at most once across all calls. */
  public static <T> Memoized<T> of(Supplier<T> body) {
    return new Memoized<>(body);
  }

  private Memoized(Supplier<T> body) {
    if (body == null) {
      throw new IllegalArgumentException("Memoized body required");
    }
    this.body = body;
  }

  /**
   * Returns true if {@link #get()} has been called at least once and the body has resolved
   * (with either a value or a failure). Safe to call before {@link #get()} — returns false in
   * that case. Useful for {@link PhaseRecord.lazyPending} → {@link PhaseRecord} transition.
   */
  public boolean isResolved() {
    return resolved;
  }

  /** The completed value, absent while pending or when resolution threw. Never runs the body. */
  public Optional<T> resolvedValue() {
    return resolved && failure.get() == null ? Optional.ofNullable(cached.get()) : Optional.empty();
  }

  /**
   * Tempdoc 541 §12.G: returns the wall-clock {@code System.currentTimeMillis()} captured
   * just before the body's first invocation, or empty if {@link #get()} has not yet been
   * called. Surfaced by BootRoutes to populate the synthesized PhaseRecord's
   * {@code startedAtMs} when the LAZY entry flips to READY.
   */
  public OptionalLong startedAtMs() {
    long v = startedAtMs;
    return v < 0L ? OptionalLong.empty() : OptionalLong.of(v);
  }

  /**
   * Tempdoc 541 §12.G: returns the wall-clock {@code System.currentTimeMillis()} captured
   * just after the body resolved (success or failure), or empty if not yet resolved.
   * Surfaced by BootRoutes to populate the synthesized PhaseRecord's {@code completedAtMs}
   * and derived {@code durationMs} when the LAZY entry flips to READY.
   */
  public OptionalLong resolvedAtMs() {
    long v = resolvedAtMs;
    return v < 0L ? OptionalLong.empty() : OptionalLong.of(v);
  }

  /**
   * Returns the body's result, computing it on first call. If the body throws on the first
   * call, the exception is captured; subsequent calls re-throw the same captured exception
   * (no retry — the body-once contract is strict). Wrapping a body that should be retryable
   * is the caller's responsibility (e.g., outer {@code try/catch} that rebuilds the {@code
   * Memoized} on retry).
   */
  @Override
  public T get() {
    if (resolved) {
      return reReadResolved();
    }
    synchronized (lock) {
      if (resolved) {
        return reReadResolved();
      }
      startedAtMs = System.currentTimeMillis();
      try {
        T value = body.get();
        cached.set(value);
        resolvedAtMs = System.currentTimeMillis();
        resolved = true;
        return value;
      } catch (RuntimeException e) {
        failure.set(e);
        resolvedAtMs = System.currentTimeMillis();
        resolved = true;
        throw e;
      }
    }
  }

  private T reReadResolved() {
    Throwable f = failure.get();
    if (f != null) {
      if (f instanceof RuntimeException re) {
        throw re;
      }
      throw new RuntimeException("Memoized body failed on first call", f);
    }
    return cached.get();
  }
}
