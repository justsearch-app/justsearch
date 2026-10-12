/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.scheduling;

import java.util.concurrent.atomic.AtomicLong;

/** Process-local activity shared by request admission and idle bulk work. */
public final class UserActivity {
  /**
   * The furthest in the past a report may place the input it reports. The web UI reports a burst's
   * last input when its three-second presence interval ends, so a report's input is under three
   * seconds old plus timer lateness. A larger claim is placed this far back instead: later than
   * claimed, so it errs towards interrupting background work and moves the idle clock by at most this.
   */
  public static final long MAX_REPORTED_INPUT_AGE_MS = 5_000;

  private final AtomicLong lastEpochMs = new AtomicLong();
  private final AtomicLong version = new AtomicLong();

  /** Input happening now, as every user action request is. */
  public void record() {
    record(0);
  }

  /**
   * Input a report describes as {@code inputAgeMs} old on arrival. The input is placed at receipt
   * minus that age, bounded to {@code [0, MAX_REPORTED_INPUT_AGE_MS]}: never in the future and never
   * arbitrarily far in the past.
   */
  public void record(long inputAgeMs) {
    long age = Math.min(Math.max(inputAgeMs, 0), MAX_REPORTED_INPUT_AGE_MS);
    lastEpochMs.accumulateAndGet(System.currentTimeMillis() - age, Math::max);
    version.incrementAndGet();
  }

  /** A pass captures this after admission; subsequent activity changes it even in the same ms. */
  public long version() { return version.get(); }

  public long msSince(long nowEpochMs) {
    long last = lastEpochMs.get();
    return last == 0 ? Long.MAX_VALUE : Math.max(0, nowEpochMs - last);
  }
}
