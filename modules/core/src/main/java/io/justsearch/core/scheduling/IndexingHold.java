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
 *
 * <p>"Read waiting files now" is an in-memory release of the reading rules (hours, battery) for the
 * file-reading loop only: {@link #tryAcquireIngestion()} and {@link #ingestionHeld()} honour it,
 * while {@link #held()}, {@link #tryAcquire()} and {@link #acquire()} keep every other lane held.
 * It ends when the loop's poll under it claims nothing, on a manual pause or settings recovery, on
 * an accepted change of the reading rules, when a rule reason that did not hold at its start begins
 * to hold, when no rule holds any more, and with the process.
 */
public final class IndexingHold {
  public enum State { RUNNING, PAUSING, PAUSED }
  public record Snapshot(State state, String reason, Long untilEpochMs, int waiting,
      List<String> reasons, boolean released) {
    public Snapshot { reasons = List.copyOf(reasons); }
    public Snapshot(State state, String reason, Long untilEpochMs, int waiting, List<String> reasons) {
      this(state, reason, untilEpochMs, waiting, reasons, false);
    }
  }
  public record LocalMinute(int weekday, int minute) {}

  /** What asking to read the waiting files did. */
  public enum ReadNow { STARTED, JOINED, NOTHING_WAITING, NOT_HELD }

  /** The outcome and, for NOT_HELD, the manual reason in effect (null when no rule holds). */
  public record ReadNowResult(ReadNow outcome, String reason) {}

  private static final List<String> RULE_REASONS = List.of("SCHEDULE", "ON_BATTERY");

  /** The active release: its identity and the rule reasons it was asked to cover. */
  private static final class Release {
    private final List<String> covered;
    private Release(List<String> covered) { this.covered = List.copyOf(covered); }
  }
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
    // An accepted change of the reading rules ends a release; an unchanged save does not.
    if (!Objects.equals(schedule, hours) || pauseOnBattery != battery) release = null;
    if (paused) pause(until, pauseReason);
    else resume();
    schedule = hours;
    pauseOnBattery = battery;
    notifyAll();
  }

  public synchronized boolean pauseOnBattery() { return pauseOnBattery; }

  public synchronized void setOnBattery(boolean battery) {
    onBattery = battery;
    releaseActive();
    notifyAll();
  }

  private final Clock clock;
  private boolean requested;
  private String reason;
  private Long untilEpochMs;
  private int active;
  private int waiting;
  private Release release;

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
    release = null;
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
  public Lease acquire() throws InterruptedException {
    return acquire(() -> true);
  }

  /** Returns null if the caller withdraws admission while waiting; policy stays unchanged. */
  public synchronized Lease acquire(java.util.function.BooleanSupplier admitted) throws InterruptedException {
    Objects.requireNonNull(admitted, "admitted");
    waiting++;
    try {
      while (admitted.getAsBoolean()) {
        if (!held()) {
          active++;
          return new Lease();
        }
        wait(250L);
      }
      return null;
    } finally {
      waiting--;
    }
  }

  /**
   * File reading's admission: as {@link #tryAcquire()}, except that an active release lets it
   * through the reading rules. Every other lane keeps using {@link #tryAcquire()}.
   */
  public synchronized Lease tryAcquireIngestion() {
    if (ingestionHeld()) return null;
    active++;
    return new Lease();
  }

  /** Whether file reading is held: held, and no release covers the reasons that hold it. */
  public synchronized boolean ingestionHeld() {
    return held() && !releaseActive();
  }

  /**
   * Asks to read the files the reading rules hold back now. {@code filesWaiting} is the caller's
   * reading of the ready file jobs, taken outside this monitor. The hold is checked again here.
   */
  public synchronized ReadNowResult readWaitingNow(boolean filesWaiting) {
    if (releaseActive()) return new ReadNowResult(ReadNow.JOINED, null);
    var reasons = effectiveReasons();
    if (requested) return new ReadNowResult(ReadNow.NOT_HELD, reason);
    var rules = reasons.stream().filter(RULE_REASONS::contains).toList();
    if (rules.isEmpty()) return new ReadNowResult(ReadNow.NOT_HELD, null);
    if (!filesWaiting) return new ReadNowResult(ReadNow.NOTHING_WAITING, null);
    release = new Release(rules);
    notifyAll();
    return new ReadNowResult(ReadNow.STARTED, null);
  }

  /** The active release's identity, or null; handed back to {@link #endRelease(Object)}. */
  public synchronized Object activeRelease() {
    return releaseActive() ? release : null;
  }

  /** Ends the given release if it is still the active one: its poll claimed nothing. */
  public synchronized void endRelease(Object drained) {
    if (drained != null && release == drained) {
      release = null;
      notifyAll();
    }
  }

  /** Evaluates the end conditions that follow from the current reasons. */
  private boolean releaseActive() {
    if (release == null) return false;
    var reasons = effectiveReasons();
    var rules = reasons.stream().filter(RULE_REASONS::contains).toList();
    if (requested || rules.isEmpty() || !release.covered.containsAll(rules)) {
      release = null;
      notifyAll();
      return false;
    }
    return true;
  }

  public synchronized Snapshot snapshot() {
    var reasons = effectiveReasons();
    if (reasons.isEmpty()) return new Snapshot(State.RUNNING, null, null, waiting, reasons, false);
    return new Snapshot(active == 0 ? State.PAUSED : State.PAUSING, reasons.getFirst(),
        requested ? untilEpochMs : null, waiting, reasons, releaseActive());
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
