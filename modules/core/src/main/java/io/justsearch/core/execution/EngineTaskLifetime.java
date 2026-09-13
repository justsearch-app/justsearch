/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.execution;

/**
 * Neutral lifetime ownership for a group of engine tasks.
 *
 * <p>Each call to {@link #retain()} acquires one child lease. The returned callback releases that
 * lease and must be called exactly once by its owner.
 */
@FunctionalInterface
public interface EngineTaskLifetime {
  /** A no-op lifetime for internal callers that have no admitted owner. */
  EngineTaskLifetime NONE = () -> () -> {};

  /** Acquires one child lease and returns its single-use release callback. */
  Runnable retain();

  /** Retains both owners, rolls back a failed second retain, and releases in reverse order. */
  default EngineTaskLifetime and(EngineTaskLifetime other) {
    java.util.Objects.requireNonNull(other, "other");
    return () -> {
      Runnable first = java.util.Objects.requireNonNull(retain(), "first retain");
      Runnable second;
      try {
        second = java.util.Objects.requireNonNull(other.retain(), "second retain");
      } catch (RuntimeException | Error failure) {
        try { first.run(); }
        catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
        throw failure;
      }
      var released = new java.util.concurrent.atomic.AtomicBoolean();
      return () -> {
        if (!released.compareAndSet(false, true)) return;
        Throwable failure = null;
        try { second.run(); }
        catch (RuntimeException | Error cleanup) { failure = cleanup; }
        try { first.run(); }
        catch (RuntimeException | Error cleanup) {
          if (failure == null) failure = cleanup;
          else if (failure != cleanup) failure.addSuppressed(cleanup);
        }
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
      };
    };
  }
}
