/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.scheduling;

import java.time.Clock;
import java.util.Objects;
import java.util.List;
import java.util.ArrayList;
import java.time.ZoneId;
import java.util.function.Supplier;

/**
 * User indexing hold, shared by every processing lane. Admission and closing admission are
 * serialized. A lease is an explicit acknowledgment: it is released only after permitted work
 * and its publication finish. Discovery, deletions and transition handshakes need no lease.
 */
public final class IndexingHold {
  public enum State { RUNNING, PAUSING, PAUSED }
  public record Snapshot(State state, String reason, Long untilEpochMs, int waiting,
      List<String> reasons) {
    public Snapshot { reasons = List.copyOf(reasons); }
  }
  public record LocalMinute(int weekday, int minute) {}

  private IndexingSchedule schedule;
  private boolean pauseOnBattery;
  private boolean onBattery;
  private Supplier<LocalMinute> localTime;

  /** Uses a single current OS-local snapshot. Called only for an enabled schedule. */
  public synchronized void setLocalTime(Supplier<LocalMinute> source) {
    localTime = Objects.requireNonNull(source, "source");
  }

  public synchronized void setPolicy(boolean paused, Long until, String pauseReason,
      IndexingSchedule hours, boolean battery) {
    if (paused) pause(until, pauseReason);
    else resume();
    schedule = hours;
    pauseOnBattery = battery;
    notifyAll();
  }

  public synchronized boolean pauseOnBattery() { return pauseOnBattery; }

  public synchronized void setOnBattery(boolean battery) {
    onBattery = battery;
    notifyAll();
  }

  private final Clock clock;
  private boolean requested;
  private String reason;
  private Long untilEpochMs;
  private int active;
  private int waiting;

  public IndexingHold() { this(Clock.systemUTC()); }
  public IndexingHold(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
    localTime = () -> {
      var now = clock.instant().atZone(ZoneId.systemDefault());
      return new LocalMinute(now.getDayOfWeek().getValue(), now.getHour() * 60 + now.getMinute());
    };
  }

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
    return !effectiveReasons().isEmpty();
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
    var reasons = effectiveReasons();
    if (reasons.isEmpty()) return new Snapshot(State.RUNNING, null, null, waiting, reasons);
    return new Snapshot(active == 0 ? State.PAUSED : State.PAUSING, reasons.getFirst(),
        requested ? untilEpochMs : null, waiting, reasons);
  }

  private List<String> effectiveReasons() {
    if (requested && untilEpochMs != null && clock.millis() >= untilEpochMs) resume();
    var reasons = new ArrayList<String>();
    if (requested) reasons.add(reason);
    if (schedule != null && schedule.enabled()) {
      var now = localTime.get();
      if (!schedule.allows(now.weekday(), now.minute())) reasons.add("SCHEDULE");
    }
    if (pauseOnBattery && onBattery) reasons.add("ON_BATTERY");
    return reasons;
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
