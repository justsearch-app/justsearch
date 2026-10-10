/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.scheduling;

import java.util.concurrent.atomic.AtomicLong;

/** Process-local activity shared by request admission and idle bulk work. */
public final class UserActivity {
  private final AtomicLong lastEpochMs = new AtomicLong();
  private final AtomicLong version = new AtomicLong();

  public void record() {
    lastEpochMs.accumulateAndGet(System.currentTimeMillis(), Math::max);
    version.incrementAndGet();
  }

  /** A pass captures this after admission; subsequent activity changes it even in the same ms. */
  public long version() { return version.get(); }

  public long msSince(long nowEpochMs) {
    long last = lastEpochMs.get();
    return last == 0 ? Long.MAX_VALUE : Math.max(0, nowEpochMs - last);
  }
}
