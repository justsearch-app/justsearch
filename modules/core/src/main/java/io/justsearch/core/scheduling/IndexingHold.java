/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.scheduling;

import java.time.Clock;
import java.util.Objects;

/**
 * User indexing hold, shared by every processing lane. Admission and closing admission are
 * serialized. A lease is an explicit acknowledgment: it is released only after permitted work
 * and its publication finish. Discovery, deletions and transition handshakes need no lease.
 */
public final class IndexingHold {
  public enum State { RUNNING, PAUSING, PAUSED }
  public record Snapshot(State state, String reason, Long untilEpochMs, int waiting) {}

  private final Clock clock;
  private boolean requested;
  private String reason;
  private Long untilEpochMs;
  private int active;
  private int waiting;

  public IndexingHold() { this(Clock.systemUTC()); }
  public IndexingHold(Clock clock) { this.clock = Objects.requireNonNull(clock, "clock"); }

  public synchronized void pause(Long until, String pauseReason) {
    if (!"USER_PAUSED".equals(pauseReason) && !"SETTINGS_RECOVERY".equals(pauseReason)) {
      throw new IllegalArgumentException("Unknown indexing pause reason");
    }
    requested = true;
    reason = pauseReason;
    untilEpochMs = until;
    notifyAll();
  }

  public synchronized void resume() {
    requested = false;
    reason = null;
    untilEpochMs = null;
    notifyAll();
  }

  public synchronized boolean held() {
    if (requested && untilEpochMs != null && clock.millis() >= untilEpochMs) resume();
    return requested;
  }

  /** Null means admission was refused; no processing may start in that case. */
  public synchronized Lease tryAcquire() {
    if (held()) return null;
    active++;
    return new Lease();
  }

  /** Interruptible wait used by explicitly accepted work; each wake rechecks wall-clock expiry. */
  public synchronized Lease acquire() throws InterruptedException {
    waiting++;
    try {
      while (held()) wait(250L);
      active++;
      return new Lease();
    } finally {
      waiting--;
    }
  }

  public synchronized Snapshot snapshot() {
    if (!held()) return new Snapshot(State.RUNNING, null, null, waiting);
    return new Snapshot(active == 0 ? State.PAUSED : State.PAUSING, reason, untilEpochMs, waiting);
  }

  public final class Lease implements AutoCloseable {
    private boolean closed;
    private Lease() {}
    @Override public void close() {
      synchronized (IndexingHold.this) {
        if (!closed) {
          closed = true;
          active--;
          IndexingHold.this.notifyAll();
        }
      }
    }
  }
}
