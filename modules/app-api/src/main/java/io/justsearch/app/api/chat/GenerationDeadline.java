/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.chat;

import java.time.Duration;
import java.util.function.Supplier;

/** Submission-time exchange budget, independent of the lifetime of the parent Engine work. */
public final class GenerationDeadline implements AutoCloseable {
  private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();
  private final Long previous;

  private GenerationDeadline(long deadline) {
    previous = CURRENT.get();
    CURRENT.set(previous == null ? deadline : Math.min(previous, deadline));
  }

  public static long capture(Duration budget) {
    long deadline = System.nanoTime() + budget.toNanos();
    Long inherited = CURRENT.get();
    return inherited == null ? deadline : Math.min(inherited, deadline);
  }

  public static GenerationDeadline open(long deadline) {
    return new GenerationDeadline(deadline);
  }

  public static <T> T within(Duration budget, Supplier<T> submission) {
    try (var ignored = open(capture(budget))) {
      return submission.get();
    }
  }

  public static Duration remaining(Duration maximum) {
    long remaining = capture(maximum) - System.nanoTime();
    if (remaining <= 0) throw busy();
    return Duration.ofNanos(remaining);
  }

  public static io.justsearch.app.api.EngineAdmissionException busy() {
    return new io.justsearch.app.api.EngineAdmissionException(
        io.justsearch.app.api.EngineAdmissionException.Reason.INFERENCE_BUSY, 1);
  }

  public static boolean busyCausedBy(Throwable failure) {
    for (int depth = 0; failure != null && depth < 32; depth++, failure = failure.getCause()) {
      if (failure instanceof io.justsearch.app.api.EngineAdmissionException busy
          && busy.reason() == io.justsearch.app.api.EngineAdmissionException.Reason.INFERENCE_BUSY) return true;
    }
    return false;
  }

  @Override public void close() {
    if (previous == null) CURRENT.remove();
    else CURRENT.set(previous);
  }
}
