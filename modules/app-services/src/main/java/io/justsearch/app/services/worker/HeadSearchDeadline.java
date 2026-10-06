/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import java.util.OptionalLong;
import java.util.function.Supplier;

/**
 * The deadline {@code D} the head fixed once for one search request (Spec D §1.2).
 *
 * <p>Every worker search call the head makes for the request, on the thread that bound it, is
 * bounded by the earlier of its category budget and {@code D}, instead of getting a fresh category
 * budget per call. Work handed to another thread (the per-source fan-out) rebinds the same
 * deadline there. Calls made outside a binding keep their category budget as before.
 *
 * <p>The deadline travels as a binding rather than as a parameter of {@link
 * KnowledgeClient#search(io.justsearch.ipc.SearchRequest, io.justsearch.core.context.EngineContext)},
 * so the search port keeps its signature for its many callers and test doubles.
 */
public final class HeadSearchDeadline {

  private static final ThreadLocal<long[]> CURRENT = new ThreadLocal<>();

  private HeadSearchDeadline() {}

  /** Runs {@code body} with {@code deadlineNanos} ({@link System#nanoTime()}) as {@code D}. */
  public static <T> T within(long deadlineNanos, Supplier<T> body) {
    long[] previous = CURRENT.get();
    CURRENT.set(new long[] {deadlineNanos});
    try {
      return body.get();
    } finally {
      if (previous == null) CURRENT.remove();
      else CURRENT.set(previous);
    }
  }

  /** The deadline bound on this thread, if any. */
  public static OptionalLong current() {
    long[] bound = CURRENT.get();
    return bound == null ? OptionalLong.empty() : OptionalLong.of(bound[0]);
  }

  /** The earlier of two {@link System#nanoTime()} deadlines, safe across a wrapping clock. */
  static long earlier(long a, long b) {
    return a - b < 0 ? a : b;
  }
}
