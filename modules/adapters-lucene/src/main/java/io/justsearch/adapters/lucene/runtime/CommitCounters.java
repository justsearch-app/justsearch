/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * The session's commit counter, carrying a per-{@link CommitReason} dimension (tempdoc 912 item 2).
 *
 * <p>This REPLACES the plain {@code AtomicLong commitCount} on {@link RuntimeSession} rather than
 * sitting beside it. Tempdoc 885 §3387 already settled that question for the cadence pair: a second
 * commit counter is a fork of this one, and two counters incremented at two sites drift. Here the
 * total is DERIVED — {@link #get()} sums the per-reason slots — so "the total equals the sum of the
 * reasons" is structural, not an invariant something has to maintain. There is no way to increment
 * the total without naming a reason.
 *
 * <p>Same lifetime and same reset semantics as the counter it replaces: it lives on the session, so
 * it is PER SESSION and resets on {@code DeferredRuntime.prepareWriterUpgrade}, a blue/green re-open, or
 * the corruption-recovery rebuild. The process-wide equivalents are the reason-tagged
 * {@code index.runtime.commit_total} counter and {@code index.runtime.commit_ms} histogram, which
 * accumulate across sessions (885 measured 46 here against 114 there).
 */
public final class CommitCounters {

  private static final CommitReason[] REASONS = CommitReason.values();

  private final AtomicLongArray perReason = new AtomicLongArray(REASONS.length);

  /** Records one commit attributed to {@code reason}; null is counted as {@link CommitReason#UNKNOWN}. */
  public void increment(CommitReason reason) {
    perReason.incrementAndGet((reason == null ? CommitReason.UNKNOWN : reason).ordinal());
  }

  /**
   * Total commits on this session — the sum of every reason's slot.
   *
   * <p>Named {@code get()} so it reads as the {@code AtomicLong} it replaces at the call sites that
   * only want the total ({@code RunningRuntime.runtimeGaugesSnapshot}, the status gauge).
   */
  public long get() {
    long total = 0L;
    for (int i = 0; i < REASONS.length; i++) {
      total += perReason.get(i);
    }
    return total;
  }

  /** Commits attributed to one reason. */
  public long get(CommitReason reason) {
    return perReason.get((reason == null ? CommitReason.UNKNOWN : reason).ordinal());
  }

  /**
   * Immutable point-in-time view of every reason with a nonzero count.
   *
   * <p>Reasons that never fired are OMITTED rather than reported as zero: the map is read as
   * attribution evidence, and 23 zeros around three real values is noise, not information. An
   * absent key means "did not fire", which is the same claim as zero.
   */
  public Map<CommitReason, Long> snapshot() {
    Map<CommitReason, Long> out = new EnumMap<>(CommitReason.class);
    for (int i = 0; i < REASONS.length; i++) {
      long v = perReason.get(i);
      if (v > 0L) {
        out.put(REASONS[i], v);
      }
    }
    return Map.copyOf(out);
  }
}
